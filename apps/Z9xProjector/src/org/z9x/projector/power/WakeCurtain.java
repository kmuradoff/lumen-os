package org.z9x.projector.power;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

import org.z9x.projector.Ui;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Theme;

/**
 * MODULE "power" (v6.2). One full-screen black "curtain" window for the power transitions
 * (research/v62/powerkey, WakeCurtain reference, adapted):
 *  - fade to black before sleep (short press, 400 ms), power off / restart (with the wordmark) and
 *    at the end of the sleep timer (slow fade);
 *  - stays attached (black) while the projector sleeps; also added at SCREEN_OFF for every other
 *    sleep (IR POWER, timeout), so every wake starts from black;
 *  - after the wake, once the lamp is back on ({@link #onLampOn}, called by PowerPolicy): a thin
 *    "Lumen" wordmark (power_wordmark) fades in, holds briefly, then picture fades in (~600 ms) and the window goes.
 *
 * Window: TYPE_SYSTEM_OVERLAY (2006, layer 23: above our TYPE_APPLICATION_OVERLAY panels (11), the
 * system shutdown dialog (19), the voice UI (21) and the volume UI (22)). Allowed for targetSdk 34
 * because the app holds INTERNAL_SYSTEM_WINDOW (PhoneWindowManager.checkAddPermission
 * :3546-3572, research VERIFIED). DisplayPolicy forces NOT_FOCUSABLE|NOT_TOUCHABLE on this type,
 * so it never takes a key. At screen-on WindowManager waits only for activity / keyguard windows
 * (WindowState.requestDrawIfNeeded), so this window's last (black) buffer is shown at once.
 *
 * Safety nets (a black window above everything must never get stuck):
 *  - a dead process loses its windows (WindowManager);
 *  - {@link #LAMP_WAIT_MS} without a lamp-on signal -> reveal anyway; {@link #HARD_CAP_MS} after
 *    SCREEN_ON -> removed whatever happened;
 *  - {@link #fastReveal} on any global key and on AK/AF focus events (PowerUi);
 *  - PowerKey checks 2 s after goToSleep that the screen really went off, else reveals.
 * Light: draws only while an animator runs; no layer, no bitmap. UNVERIFIED on the device:
 * whether a window added right after SCREEN_OFF is drawn before the next wake (tune on device).
 * Main thread only (public methods post when needed).
 */
public final class WakeCurtain {
    private static final String TAG = "Z9xCurtain";
    /** Lamp-on wait before revealing anyway (PowerPolicy's STR path polls 196 for up to 7 s). */
    private static final long LAMP_WAIT_MS = 8_500;
    /** Absolute cap after SCREEN_ON. */
    private static final long HARD_CAP_MS = 12_000;
    /** LED ramp margin after 195(true) before the wordmark appears (UNVERIFIED, tune on device). */
    private static final long LAMP_SETTLE_MS = 150;
    private static final long MARK_IN_MS = 320;
    private static final long MARK_HOLD_MS = 320;
    /** Picture fade-in after the wake (task: ~600 ms). */
    private static final long REVEAL_MS = 600;
    private static final long FAST_REVEAL_MS = 220;

    private static CurtainView sView;
    private static ValueAnimator sAnim;
    private static boolean sAwaitingLamp;
    /** v6.5: the wait is a standby wake: reveal without the wordmark (picture back in well under 1 s). */
    private static boolean sQuickReveal;
    /** Standby wake: lamp settle + fade (no wordmark). */
    private static final long STANDBY_REVEAL_MS = 350;
    /** True while a fade to black runs whose end action must not be dropped by fastReveal. */
    private static boolean sFadingToBlack;
    private static final Runnable sLampTimeout = () -> {
        if (sAwaitingLamp) {
            Log.w(TAG, "no lamp-on signal in " + LAMP_WAIT_MS + " ms: revealing");
            sAwaitingLamp = false;
            reveal(false);
        }
    };
    private static final Runnable sHardCap = () -> {
        if (sView != null) {
            Log.w(TAG, "hard cap " + HARD_CAP_MS + " ms after SCREEN_ON: removing curtain");
            remove();
        }
    };

    private WakeCurtain() {}

    // ================================================================= entry points

    /**
     * Fades the curtain in over {@code ms}, then runs {@code end} (may be null). withMark: the thin
     * wordmark fades in too (power off / restart). slow: an ease-in curve for the long sleep-timer
     * fade. Returns false if the window could not be added (the caller then acts without the
     * animation). Main thread.
     */
    public static boolean fadeToBlack(Context ctx, long ms, boolean withMark, boolean slow, Runnable end) {
        if (!attach(ctx)) return false;
        cancelTimers();
        sAwaitingLamp = false;
        sFadingToBlack = true;
        final CurtainView v = sView;
        TimeInterpolator ip = slow ? new PathInterpolator(0.45f, 0f, 0.85f, 0.55f)
                : new PathInterpolator(0.4f, 0f, 0.2f, 1f);
        animate(v.black, 1f, v.mark, withMark ? 1f : 0f, ms, ip, () -> {
            sFadingToBlack = false;
            if (end != null) end.run();
        });
        return true;
    }

    /** PowerUi, SCREEN_OFF (v6.5: also standby enter): every wake starts from black. Main thread. */
    static void onScreenOff(Context ctx) {
        cancelTimers();
        sAwaitingLamp = false;
        sQuickReveal = false;
        sFadingToBlack = false;
        stopAnim();                      // a running fade jumps to black; its end action is dropped
        if (!attach(ctx)) return;        // (the screen is already off)
        sView.black = 1f;
        sView.mark = 0f;
        sView.invalidate();
    }

    /** PowerUi, SCREEN_ON: wait for the lamp, with a fallback and a hard cap. Main thread. */
    static void onScreenOn() {
        sQuickReveal = false;
        awaitLamp();
    }

    /** v6.5 PowerUi, standby exit: the same wait, then a short reveal without the wordmark. Main thread. */
    static void onStandbyWake() {
        awaitLamp();
        sQuickReveal = true;
    }

    private static void awaitLamp() {
        if (sView == null) return;
        sAwaitingLamp = true;
        Ui.main().removeCallbacks(sLampTimeout);
        Ui.main().removeCallbacks(sHardCap);
        Ui.main().postDelayed(sLampTimeout, LAMP_WAIT_MS);
        Ui.main().postDelayed(sHardCap, HARD_CAP_MS);
    }

    /** PowerPolicy: the lamp is confirmed on (196 true or 195(true) sent). Any thread. */
    public static void onLampOn() {
        Ui.main().post(() -> {
            if (!sAwaitingLamp || sView == null) return;
            sAwaitingLamp = false;
            Ui.main().removeCallbacks(sLampTimeout);
            final CurtainView v = sView;
            final boolean quick = sQuickReveal;
            sQuickReveal = false;
            Ui.main().postDelayed(() -> {
                if (sView != v || sFadingToBlack) return;
                if (quick) {
                    animate(v.black, 0f, v.mark, 0f, STANDBY_REVEAL_MS, new PathInterpolator(0.4f, 0f, 0.2f, 1f),
                            WakeCurtain::remove);
                } else {
                    reveal(false);
                }
            }, LAMP_SETTLE_MS);
        });
    }

    /**
     * Short fade out: a wake by the mic key, any global key while the curtain is up after a wake,
     * an AK/AF event, an aborted power action or sleep-timer fade. Any thread.
     */
    public static void fastReveal() {
        Runnable r = () -> {
            if (sView == null) return;
            sAwaitingLamp = false;
            sFadingToBlack = false;
            Ui.main().removeCallbacks(sLampTimeout);
            reveal(true);
        };
        // inline on the main thread, so a fade to black started right after it (same task) wins
        if (Ui.main().isCurrentThread()) r.run(); else Ui.main().post(r);
    }

    public static boolean isShowing() {
        return sView != null;
    }

    /** A fade to black (sleep / power off / restart / timer) is running. Main thread. */
    static boolean isFadingToBlack() {
        return sFadingToBlack;
    }

    // ================================================================= internals

    private static void reveal(boolean fast) {
        final CurtainView v = sView;
        if (v == null) return;
        if (fast) {
            animate(v.black, 0f, v.mark, 0f, FAST_REVEAL_MS, new DecelerateInterpolator(), WakeCurtain::remove);
            return;
        }
        // thin wordmark on black, a short hold, then the picture fades in under it
        animate(v.black, 1f, v.mark, 1f, MARK_IN_MS, new PathInterpolator(0.2f, 0f, 0f, 1f), () ->
                Ui.main().postDelayed(() -> {
                    if (sView == v && !sFadingToBlack) {
                        animate(v.black, 0f, v.mark, 0f, REVEAL_MS, new PathInterpolator(0.4f, 0f, 0.2f, 1f),
                                WakeCurtain::remove);
                    }
                }, MARK_HOLD_MS));
    }

    private static void stopAnim() {
        if (sAnim != null) {
            sAnim.removeAllListeners();
            sAnim.cancel();
            sAnim = null;
        }
    }

    private static void animate(final float b0, final float b1, final float m0, final float m1, long ms,
                                TimeInterpolator ip, final Runnable end) {
        stopAnim();
        final CurtainView v = sView;
        if (v == null) return;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.max(0, ms));
        a.setInterpolator(ip);
        a.addUpdateListener(an -> {
            float f = (float) an.getAnimatedValue();
            v.black = b0 + (b1 - b0) * f;
            v.mark = m0 + (m1 - m0) * f;
            v.invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;
            @Override public void onAnimationCancel(Animator an) { canceled = true; }
            @Override public void onAnimationEnd(Animator an) {
                if (sAnim == an) sAnim = null;
                if (!canceled && end != null) {
                    try { end.run(); } catch (Throwable t) { Log.e(TAG, "end action", t); }
                }
            }
        });
        sAnim = a;
        a.start();
    }

    private static boolean attach(Context ctx) {
        if (sView != null) return true;
        try {
            Context app = ctx.getApplicationContext();
            CurtainView v = new CurtainView(app);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.windowAnimations = 0;
            try { lp.setFitInsetsTypes(0); } catch (Throwable ignored) { }
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("Z9xCurtain");
            if (!OverlayHost.get(app).addStatic(v, lp)) return false;
            sView = v;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "attach", t);
            return false;
        }
    }

    private static void remove() {
        cancelTimers();
        sAwaitingLamp = false;
        sFadingToBlack = false;
        stopAnim();
        CurtainView v = sView;
        sView = null;
        if (v != null) {
            try {
                OverlayHost.get(v.getContext()).removeStatic(v);
            } catch (Throwable t) {
                Log.w(TAG, "remove: " + t);
            }
        }
    }

    private static void cancelTimers() {
        Ui.main().removeCallbacks(sLampTimeout);
        Ui.main().removeCallbacks(sHardCap);
    }

    /**
     * Black fill with alpha {@link #black}; the "Lumen" wordmark (power_wordmark, system sans-serif at weight 200,
     * wide tracking: our own mark, no XGIMI asset) with alpha {@link #mark}, settling from 96 % to
     * 100 % size as it appears. Canvas only, nothing allocated in onDraw.
     */
    private static final class CurtainView extends View {
        float black;
        float mark;
        private final Paint fill = new Paint();
        private final TextPaint word;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String text;
        private final float lineHalf;
        private final float lineGap;

        CurtainView(Context c) {
            super(c);
            fill.setColor(Color.BLACK);
            word = Theme.text(c, 84, 0xFFFFFFFF, false);
            Typeface base = Typeface.create("sans-serif", Typeface.NORMAL);
            word.setTypeface(Typeface.create(base, 200, false));
            word.setLetterSpacing(0.42f);
            word.setTextAlign(Paint.Align.CENTER);
            text = c.getString(org.z9x.projector.R.string.power_wordmark);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(Math.max(1f, Theme.pxf(c, 1.5f)));
            lineHalf = Theme.pxf(c, 28);
            lineGap = Theme.pxf(c, 34);
            setWillNotDraw(false);
        }

        @Override protected void onDraw(Canvas c) {
            if (black > 0f) {
                fill.setAlpha(Math.round(255 * Math.min(1f, black)));
                c.drawRect(0, 0, getWidth(), getHeight(), fill);
            }
            if (mark > 0.004f) {
                float m = Math.min(1f, mark);
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                c.save();
                float s = 0.96f + 0.04f * m;
                c.scale(s, s, cx, cy);
                word.setAlpha(Math.round(0xE0 * m));
                // Minikin splits letterSpacing half before / half after each glyph, so CENTER stays centred
                float base = cy - (word.descent() + word.ascent()) / 2f;
                c.drawText(text, cx, base, word);
                line.setAlpha(Math.round(0x66 * m));                 // one hairline under the mark
                float ly = base + lineGap;
                c.drawLine(cx - lineHalf * m, ly, cx + lineHalf * m, ly, line);
                c.restore();
            }
        }
    }
}
