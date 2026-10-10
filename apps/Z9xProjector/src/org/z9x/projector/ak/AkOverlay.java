package org.z9x.projector.ak;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Debug;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MODULE "ak": the full-screen auto-keystone overlay (FEATURE_SPEC 1.1, the stock SystemUI
 * AK2Window + AkAnimView protocol, VERIFIED in sysui_jadx focus/AK2Window.java, AkAnimView.java,
 * FocusUIV2.java:541-601) and the event-105 result notices (FEATURE_SPEC 2.6, {@link AkNotices}).
 *
 * The vendor AK state machine (libxgimi AK_Process::uiAkDisplayMode @0x6ae8a0) sends a focusEvent
 * per step and then waits up to 5 s for IGmpf 146 uiAkDisplay(type). Each ack here is sent on the
 * "z9x-ak" thread for exactly the type just rendered, and only while that step is still current
 * (a token per event; stale animation ends never ack).
 *
 * | event | value         | UI (stock timings)                                         | ack          |
 * |-------|---------------|------------------------------------------------------------|--------------|
 * | 106   | AK_Ui_Mode    | white oval (FIT_CENTER) scales 0.1 -> 2.1, 500 ms linear,   | at anim end  |
 * |       |               | after 100 ms (400 ms on the first AK while setup runs)      |              |
 * | 107   | AK_Ui_Mode    | generated test pattern full screen on black                 | after the frame is committed (+300 ms the first time) |
 * | 109   | "x-y,x-y,x-y,x-y" TL,TR,BL,BR | after 88 ms, pattern corners slide from the full view to the quad, 500 ms AccelerateDecelerate, 5 px outline | at anim end |
 * | 115   | AK_Ui_Mode    | same pattern with the optical-zoom caption (layer made      | after the frame is committed |
 * |       |               | visible again if our watchdog hid it)                       |              |
 * | 116   | AK_Ui_Mode    | white layer alpha 0 -> 1, 300 ms                            | at anim end  |
 * | 118   | AK_Ui_Mode    | our short "done" animation (stock: Lottie ak.json)          | at its start |
 * | 113   | AK_Ui_Mode    | pattern alpha 1 -> 0, 500 ms                                | at anim end  |
 * | 114   | AK_Ui_Mode    | hide the overlay                                            | yes          |
 * | 110   | any           | hide                                                        | never        |
 * | 105   | result        | notice card (held back while the overlay is up)             | never        |
 * Values other than AK_Ui_Mode / AK_30w_Ui_Mode (Detect = env-monitor pattern, LCK = short-throw,
 * tilt-shift) are not rendered and not acked (returns false): we have no verified pattern for them.
 * KNOWN GAP (V61_REQUIREMENTS "AK Detect steps"): the vendor waits for an ack of 107 / 115 / 118
 * "AK_Ui_Mode_Detect" (ee1_doUserModeAutoKst.dis:1137-1150 for trigger 13, ee1_end.dis:318-322);
 * without it it times out after 5 s and takes its no-UI branch. Which trigger sends Detect on the
 * Z9X is UNVERIFIED (check logcat -d for 'Detect' on the device). So that the white curtain of the
 * preceding 106 does not stay up for the 8 s watchdog, a Detect step hides the overlay at once.
 * Once the stock env-monitor geometry (ak_env_monitor) is verified, render it and ack like the
 * normal steps.
 * 118 with "AK_Tof_Stage_End" is a plain status event (stock ignores it) and is not acked.
 * Event 120 is left to HalController's v6 handling (rate-limited fit-to-screen hint).
 *
 * Safety: an 8 s watchdog hides the overlay when no next event arrives; SCREEN_OFF hides it; the
 * window is focusable while up and eats keys (stock dialog was not cancelable) so nothing is opened
 * by D-pad over the pattern; the notification card is hidden while the pattern is up.
 * 1.0.1 (a friend's projector on an older build: black screen, lamp on, no key reacted after an auto
 * keystone from the quick panel):
 *  - BACK (a full press whose DOWN came >= {@value #BACK_AFTER_MS} ms after the overlay appeared)
 *    hides it. Nothing is acked on that path (hide() bumps the token, so pending acks are skipped);
 *    the rest of that AK run is neither shown nor acked, so the vendor times out on its own (5 s per
 *    step) and finishes without UI. A new run (106, or any step after {@value #WATCHDOG_MS} ms of
 *    silence) shows again; 105 / 110 / 114 end the run. Until then {@link #isActive} stays true (the
 *    vendor still drives its TOF AF, zoom motor and CorrectKeystone without UI), so no AF / focus /
 *    keystone / panel path starts on top of it; the keys of the app below work again at once.
 *  - Main-thread hang watchdog while the overlay is up ("z9x-akhang"): every second it posts a ping
 *    to the main looper; one unanswered for more than {@value #HANG_MS} ms logs the main thread's
 *    stack and kills this (persistent) process, which the system restarts at once; the overlay window
 *    goes with it. It watches only our main thread, never the vendor: between AK events (several
 *    seconds while the vendor measures) the main thread is idle and answers in milliseconds. Uptime
 *    clock (stops in suspend); armed in ensureShown, disarmed in hide (every way the overlay goes).
 *
 * Interface resolution (Lumen OS 1.0.1: 4K by default, 1080p, test-only 2K): the window is the full UI
 * frame and every drawing scales with it. The vendor's 109 corners are taken in the stock 1920 x 1080
 * units (AkPatternSpec W / H; measured at 1080p) and scaled to the view, unless they reach beyond that
 * frame: then they are UI pixels (a vendor that reports the 3840 x 2160 OSD region at the 4K UI;
 * AkPatternSpec.cornerScale, logged). The pattern bitmap is an ALPHA_8 mask at the real UI size, capped
 * at the 3840 x 2160 DLP panel (8.3 MB at 4K, released 30 s after the run; AkPatternRenderer). The raw
 * 109 value and the view size are logged (device check).
 *
 * The same overlay serves every AK trigger: power-on AK, the vendor's own triggers, and our
 * "Auto keystone now" / "Fit to screen now" (Hal.requestKeystone -> 326 / 272(11)), because the
 * vendor sends the same events for all of them.
 *
 * Entry points (main thread): {@link #install}, {@link #onFocusEvent}; {@link #isActive} for other
 * modules (do not open panels over the pattern).
 */
public final class AkOverlay {
    private static final String TAG = "Z9xAk";
    static final String CONFIG_PATH = "/mnt/vendor/xgimiconfig/public/AK/config.yaml";
    private static final long WATCHDOG_MS = 8_000;
    private static final long CURTAIN_DELAY_MS = 100, CURTAIN_DELAY_SETUP_MS = 400;
    private static final long CURTAIN_MS = 500, WHITE_MS = 300, FADE_MS = 500;
    private static final long WARP_DELAY_MS = 88, FIRST_PATTERN_EXTRA_MS = 300;
    private static final long PATTERN_ACK_FALLBACK_MS = 400;
    private static final long BITMAP_RELEASE_MS = 30_000;
    private static final int MAX_CONFIG_BYTES = 256 * 1024;
    /** The pattern mask is never larger than the DLP panel (3840 x 2160 ALPHA_8 = 8.3 MB). */
    private static final int MAX_PATTERN_W = org.z9x.projector.hal.KstPoint.PANEL_W,
            MAX_PATTERN_H = org.z9x.projector.hal.KstPoint.PANEL_H;
    /** BACK hides the overlay only once it has been up this long (a press already in flight does not). */
    private static final long BACK_AFTER_MS = 2_000;
    /** Main-thread hang watchdog: ping period and the silence that counts as a hang. */
    private static final long HANG_PING_MS = 1_000, HANG_MS = 10_000;

    private static Context sApp;
    private static SafeHandler sMain;
    private static SafeHandler sGen;                 // "z9x-akgen": config read + bitmap render
    private static volatile AkPatternSpec sSpec;
    private static volatile Bitmap sBitmap;
    private static volatile boolean sGenerating;

    // views (main thread)
    private static Root sRoot;
    private static CurtainView sCurtain;
    private static View sWhite;
    private static FrameLayout sMainLayer;
    private static AkWarpView sWarp;
    private static WindowManager.LayoutParams sLp;
    /** Written on the main thread only; volatile so worker threads (motor, AF) can read isActive(). */
    private static volatile boolean sShown;
    private static int sToken;                       // bumps on every handled event and on hide
    private static AnimatorSet sCurtainAnim;
    private static ObjectAnimator sFadeAnim;
    private static boolean sFirstTrigger = true, sFirstPattern = true;
    private static long sShownAt;
    /** Main thread: the BACK DOWN came late enough; its UP hides the overlay. */
    private static boolean sBackArmed;
    /**
     * Main thread writes: the user hid this AK run with BACK; its further steps are not shown, not
     * acked. Volatile: {@link #isActive} reads both from any thread.
     */
    private static volatile boolean sUserHidden;
    private static volatile long sLastAkEventAt;         // uptime of the last AK UI event (105..118)

    // main-thread hang watchdog ("z9x-akhang"; see the class comment)
    private static SafeHandler sHang;
    private static volatile boolean sHangArmed;
    private static volatile long sPingAt;                // uptime of the unanswered ping, 0 = answered
    private static final Runnable PONG = () -> sPingAt = 0;
    private static final Runnable HANG_CHECK = AkOverlay::hangCheck;

    private static final Runnable WATCHDOG = () -> {
        Log.w(TAG, "watchdog: no AK event for " + WATCHDOG_MS + " ms, hiding");
        hide("watchdog");
    };
    private static final Runnable RELEASE_BITMAP = () -> {
        if (!sShown) {
            if (sWarp != null) sWarp.setPattern(null);
            sBitmap = null;
            Log.i(TAG, "pattern bitmap released");
        }
    };

    private AkOverlay() {}

    /** App.onCreate (main thread). Cheap: starts the config read on its own thread, no window. */
    public static void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sMain = Ui.main();
        AkNotices.install(sApp);
        sGen = SafeHandler.newThread("z9x-akgen");
        sGen.post(AkOverlay::loadSpec);
        try {
            BroadcastReceiver off = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        if (sShown) hide("screen off");
                    } catch (Throwable t) {
                        Log.e(TAG, "screen off", t);
                    }
                }
            };
            sApp.registerReceiver(off, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "screen-off receiver: " + t);
        }
    }

    /** Locale changed (App.onConfigurationChanged, main thread): re-read the captions. */
    public static void onLocaleChanged() {
        if (sApp == null || sWarp == null) return;
        sWarp.setCaptions(sApp.getString(R.string.ak_caption_keystone), sApp.getString(R.string.ak_caption_zoom));
        sWarp.invalidate();
    }

    /**
     * True while the AK overlay is on screen, or while a run the user hid with BACK is still in flight
     * (any thread). Other modules: do not open panels over it, and do not start focus / motor work
     * (the vendor AK runs its own TOF AF and zoom motor).
     */
    public static boolean isActive() {
        return sShown || hiddenRunInFlight();
    }

    /**
     * Any thread: the user hid the overlay with BACK and that run has not ended yet (no 105 / 110 /
     * 114 / new 106) while its last AK event is at most {@value #WATCHDOG_MS} ms old. The vendor goes
     * on without UI then, so every "AK active" gate must stay closed; bounded by the same silence that
     * makes the next step count as a new run (a stuck vendor frees the gates 8 s after the BACK).
     */
    private static boolean hiddenRunInFlight() {
        return sUserHidden && SystemClock.uptimeMillis() - sLastAkEventAt <= WATCHDOG_MS;
    }

    /**
     * Main thread. true = handled (HalController skips its own handling).
     * v6.5: during the lamp-only standby (StandbyController.isActive) nothing is shown or acked and
     * no notice card appears: the event is swallowed (an un-acked vendor AK times out), the overlay is
     * hidden if it was up; StandbyController re-checks the lamp (PowerUi focus listener).
     */
    public static boolean onFocusEvent(int type, String value) {
        if (sApp == null) return false;
        if (org.z9x.projector.power.StandbyController.isActive()) {
            Log.w(TAG, type + " \"" + value + "\" during standby: not shown, not acked");
            if (sShown) hide("standby");
            return true;
        }
        if (swallowAfterBack(type, value)) return true;
        switch (type) {
            case 105:
                return AkNotices.onResult(value, sShown);
            case 110:
                Log.i(TAG, "110: hide (no ack)");
                hide("110");
                return true;
            case 109:
                onCorners(value);
                return true;
            case 106: case 107: case 113: case 114: case 115: case 116: case 118:
                if (!isOurUiMode(value)) {
                    if (value != null && value.endsWith("_Detect")) {
                        // Known gap (see header): no verified env-monitor pattern, so no ack;
                        // do not leave the 106 curtain up until the watchdog fires.
                        Log.w(TAG, type + " \"" + value + "\": env-monitor pattern not implemented, not acked"
                                + (sShown ? "; hiding the overlay" : ""));
                        hide("detect unsupported");
                        return true;
                    }
                    if (type != 118 || !"AK_Tof_Stage_End".equals(value)) {
                        Log.w(TAG, type + " \"" + value + "\": UI mode not supported (no verified pattern), not acked");
                    }
                    return false;
                }
                onStep(type);
                return true;
            default:
                return false;                                  // 108, 111, 112, 117, 120, ...
        }
    }

    /**
     * Main thread: after the user hid the overlay with BACK, the rest of that AK run is swallowed
     * (logged, not shown, not acked): the steps this overlay would draw and ack (106-118 in our UI
     * mode, 109). 106 (a new run) or {@value #WATCHDOG_MS} ms without such an event ends that; 114
     * ends the run (swallowed too), 105 / 110 end it and get their normal handling (the result notice,
     * a no-op hide). Other events (108, 111, 112, 117, Detect values, ...) are never touched.
     */
    private static boolean swallowAfterBack(int type, String value) {
        boolean step = type == 109 || ((type == 106 || type == 107 || (type >= 113 && type <= 116) || type == 118)
                && isOurUiMode(value));
        boolean end = type == 105 || type == 110;
        if (!step && !end) return false;
        long now = SystemClock.uptimeMillis();
        long gap = now - sLastAkEventAt;
        sLastAkEventAt = now;
        if (!sUserHidden) return false;
        if (end) {
            sUserHidden = false;
            Log.i(TAG, type + ": end of the AK run hidden by BACK");
            return false;
        }
        if (type == 106 || gap > WATCHDOG_MS) {
            sUserHidden = false;
            Log.i(TAG, type + ": new AK run" + (type == 106 ? "" : " (" + gap + " ms quiet)") + ", overlay shown again");
            return false;
        }
        if (type == 114) sUserHidden = false;
        Log.i(TAG, type + " \"" + value + "\" after BACK: not shown, not acked (the vendor times out on its own)");
        return true;
    }

    /** The values for which stock AK2Window draws the ak4_30w pattern (Z9X image type 1). */
    private static boolean isOurUiMode(String v) {
        return "AK_Ui_Mode".equals(v) || "AK_30w_Ui_Mode".equals(v);
    }

    // ================================================================== steps
    private static void onStep(int type) {
        if (!ensureShown()) {
            Log.e(TAG, type + ": overlay window could not be shown, not acked");
            return;
        }
        final int tok = ++sToken;
        armWatchdog();
        Log.i(TAG, "step " + type);
        switch (type) {
            case 106: step106(tok); break;
            case 107: step107(tok); break;
            case 113: step113(tok); break;
            case 114:
                ack(114, tok);
                hide("114");
                break;
            case 115: step115(tok); break;
            case 116: step116(tok); break;
            case 118:
                sWarp.startDone(() -> ack(118, tok));
                break;
            default:
                break;
        }
    }

    /** 106: AK2Window.show(106) non-LCD branch. */
    private static void step106(int tok) {
        startPatternGeneration();
        sWarp.resetFull();                                     // initAllData
        sMainLayer.setVisibility(View.GONE);
        sWhite.setVisibility(View.GONE);
        if (sCurtainAnim != null) sCurtainAnim.cancel();
        sCurtain.setScaleX(0.1f);
        sCurtain.setScaleY(0.1f);
        sCurtain.setVisibility(View.INVISIBLE);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ObjectAnimator.ofFloat(sCurtain, View.SCALE_X, 0.1f, 2.1f),
                ObjectAnimator.ofFloat(sCurtain, View.SCALE_Y, 0.1f, 2.1f));
        set.setInterpolator(new LinearInterpolator());
        set.setDuration(CURTAIN_MS);
        set.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationStart(Animator a) { sCurtain.setVisibility(View.VISIBLE); }
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) { if (!cancelled) ack(106, tok); }
        });
        sCurtainAnim = set;
        long delay = CURTAIN_DELAY_MS;
        if (sFirstTrigger) {
            sFirstTrigger = false;
            if (setupRunning()) delay = CURTAIN_DELAY_SETUP_MS;
        }
        sMain.postDelayed(() -> { if (tok == sToken && sShown) set.start(); }, delay);
    }

    /** 107: pattern full screen on the black layer, ack once the frame is on screen. */
    private static void step107(int tok) {
        if (!ensurePattern()) {
            Log.e(TAG, "107: no pattern, not acked");
            return;
        }
        if (sFadeAnim != null) sFadeAnim.cancel();
        sWarp.setAlpha(1f);
        sWarp.setMode(AkWarpView.MODE_PATTERN);
        sCurtain.setVisibility(View.GONE);
        sWarp.setVisibility(View.VISIBLE);
        sMainLayer.setVisibility(View.VISIBLE);
        sWarp.invalidate();
        AkNotices.onOverlayShown();                            // the card must not be in the photo
        final long extra = sFirstPattern ? FIRST_PATTERN_EXTRA_MS : 0;
        sFirstPattern = false;
        ackAfterFrame(107, tok, extra);
    }

    /**
     * 115: the pattern with the optical-zoom caption. The black layer and the warp view are made
     * visible again first (our 8 s watchdog may have hidden them between 109 and 115, as onCorners
     * handles for 109), so 115 is acked only once the pattern frame is really on screen.
     */
    private static void step115(int tok) {
        if (!ensurePattern()) {
            Log.e(TAG, "115: no pattern, not acked");
            return;
        }
        if (sFadeAnim != null) sFadeAnim.cancel();
        sWarp.setAlpha(1f);
        sCurtain.setVisibility(View.GONE);
        sWarp.setVisibility(View.VISIBLE);
        sMainLayer.setVisibility(View.VISIBLE);
        sWarp.setMode(AkWarpView.MODE_ZOOM);                   // invalidates
        ackAfterFrame(115, tok, 0);
    }

    /** Acks {@code type} once the frame just invalidated was committed (+extra ms), at most once. */
    private static void ackAfterFrame(int type, int tok, long extra) {
        final boolean[] once = {false};
        Runnable fire = () -> {
            if (once[0]) return;
            once[0] = true;
            if (extra > 0) sMain.postDelayed(() -> ack(type, tok), extra); else ack(type, tok);
        };
        try {
            // Better than stock's View.post: wait until the frame was handed to the display.
            sRoot.getViewTreeObserver().registerFrameCommitCallback(() -> sMain.post(fire));
        } catch (Throwable t) {
            Log.w(TAG, "frame-commit callback: " + t);
            sWarp.post(fire);
        }
        sMain.postDelayed(fire, PATTERN_ACK_FALLBACK_MS);      // if no frame is committed
    }

    /** 109: parse the 4 corners and slide the pattern into them (AK2Window handler msg 109). */
    private static void onCorners(String value) {
        final float[] q = parseCorners(value);
        if (!ensureShown()) {
            Log.e(TAG, "109: overlay window could not be shown, not acked");
            return;
        }
        final int tok = ++sToken;
        armWatchdog();
        if (ensurePattern()) sWarp.setMode(AkWarpView.MODE_PATTERN);
        // Stock re-sets the pattern image here. We also make the layer visible, in case our 8 s
        // watchdog hid it while the vendor was still computing (stock had no such watchdog).
        if (sFadeAnim != null) sFadeAnim.cancel();
        sWarp.setAlpha(1f);
        sCurtain.setVisibility(View.GONE);
        sWarp.setVisibility(View.VISIBLE);
        sMainLayer.setVisibility(View.VISIBLE);
        Log.i(TAG, "step 109 \"" + value + "\" (" + AkPatternSpec.W + "x" + AkPatternSpec.H + " units -> view "
                + sWarp.getWidth() + "x" + sWarp.getHeight() + ")" + (q == null ? " (unparsable: pattern stays full)" : ""));
        sMain.postDelayed(() -> {
            if (tok != sToken || !sShown) return;
            int w = sWarp.getWidth(), h = sWarp.getHeight();
            if (w <= 0 || h <= 0) {
                DisplayMetrics m = sApp.getResources().getDisplayMetrics();
                w = m.widthPixels;
                h = m.heightPixels;
            }
            // stock 1920 x 1080 units scaled to the view; a quad that cannot be in them is in UI pixels
            // (a vendor that reports the 3840 x 2160 OSD region at the 4K UI): AkPatternSpec.cornerScale
            float[] f = AkPatternSpec.cornerScale(q, w, h);
            float sx = f[0], sy = f[1];
            if (q != null && sx == 1f && sy == 1f && (w > AkPatternSpec.W || h > AkPatternSpec.H)) {
                Log.w(TAG, "109 corners beyond the stock " + AkPatternSpec.W + "x" + AkPatternSpec.H
                        + " frame: taken as UI pixels of the " + w + "x" + h + " view");
            }
            float[] t;
            if (q != null) {
                // value order TL, TR, BL, BR -> view order LT, RT, RB, LB (AkAnimView.setValues)
                t = new float[]{q[0] * sx, q[1] * sy, q[2] * sx, q[3] * sy,
                        q[6] * sx, q[7] * sy, q[4] * sx, q[5] * sy};
            } else {
                t = new float[]{0, 0, w, 0, w, h, 0, h};
            }
            sWarp.animateTo(t, () -> ack(109, tok));
        }, WARP_DELAY_MS);
    }

    /** 113: pattern alpha 1 -> 0 in 500 ms, then gone. */
    private static void step113(int tok) {
        sCurtain.setVisibility(View.GONE);
        if (sFadeAnim != null) sFadeAnim.cancel();
        ObjectAnimator a = ObjectAnimator.ofFloat(sWarp, View.ALPHA, sWarp.getAlpha(), 0f);
        a.setDuration(FADE_MS);
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(Animator an) {
                if (cancelled) return;
                sWarp.setVisibility(View.GONE);
                ack(113, tok);
            }
        });
        sFadeAnim = a;
        a.start();
    }

    /** 116: white layer (under the black pattern layer, as in the stock layout) fades in. */
    private static void step116(int tok) {
        sWhite.animate().cancel();
        sWhite.setVisibility(View.VISIBLE);
        sWhite.setAlpha(0f);
        sWhite.animate().alpha(1f).setDuration(WHITE_MS).setListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(Animator an) {
                sWhite.animate().setListener(null);
                if (cancelled) return;
                sCurtain.setVisibility(View.GONE);
                ack(116, tok);
            }
        }).start();
    }

    /** Sends 146 uiAkDisplay(type) on "z9x-ak" if the step is still the current one. */
    private static void ack(int type, int tok) {
        try {
            if (tok != sToken || !sShown) {
                Log.i(TAG, "ack " + type + " skipped: step no longer current");
                return;
            }
            long t0 = SystemClock.elapsedRealtime();
            boolean queued = Hal.runAk((g, g2) -> {
                g.uiAkDisplay(type);
                Log.i(TAG, "146 uiAkDisplay(" + type + ") sent (+" + (SystemClock.elapsedRealtime() - t0) + " ms queue)");
            });
            if (!queued) Log.w(TAG, "146 uiAkDisplay(" + type + "): HAL not available");
        } catch (Throwable t) {                               // animator callbacks: never crash
            Log.e(TAG, "ack " + type, t);
        }
    }

    private static void armWatchdog() {
        sMain.removeCallbacks(WATCHDOG);
        sMain.postDelayed(WATCHDOG, WATCHDOG_MS);
    }

    // ================================================================== window
    private static boolean ensureShown() {
        if (sShown) return true;
        if (sRoot == null) buildViews();
        OverlayHost host = OverlayHost.get(sApp);
        host.dismissAll(false);                               // no panel over / under the pattern
        if (!host.addStatic(sRoot, sLp)) return false;
        sShown = true;
        sShownAt = SystemClock.elapsedRealtime();
        sBackArmed = false;
        armHangWatchdog();
        sMain.removeCallbacks(RELEASE_BITMAP);
        Notify.setHeld(true);                                 // no card over the pattern (camera)
        AkNotices.onOverlayShown();
        sRoot.requestFocus();
        return true;
    }

    private static void hide(String why) {
        sMain.removeCallbacks(WATCHDOG);
        sToken++;
        if (!sShown) return;
        sShown = false;
        sBackArmed = false;
        disarmHangWatchdog();
        Log.i(TAG, "hide (" + why + ") after " + (SystemClock.elapsedRealtime() - sShownAt) + " ms");
        if (sCurtainAnim != null) sCurtainAnim.cancel();
        if (sFadeAnim != null) sFadeAnim.cancel();
        sWhite.animate().cancel();
        sWarp.cancelAll();
        sMainLayer.setVisibility(View.GONE);
        sCurtain.setVisibility(View.GONE);
        sWhite.setVisibility(View.GONE);
        OverlayHost.get(sApp).removeStatic(sRoot);
        sMain.postDelayed(RELEASE_BITMAP, BITMAP_RELEASE_MS);
        Notify.setHeld(false);                                // shows a card held back meanwhile
        AkNotices.flushPending();
    }

    private static void buildViews() {
        Context c = sApp;
        sRoot = new Root(c);
        sRoot.setBackgroundColor(Color.TRANSPARENT);          // stock root bg #00000000, no dim
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        sCurtain = new CurtainView(c);
        sCurtain.setVisibility(View.GONE);
        sRoot.addView(sCurtain, mp);
        sWhite = new View(c);
        sWhite.setBackgroundColor(Color.WHITE);
        sWhite.setVisibility(View.GONE);
        sRoot.addView(sWhite, new FrameLayout.LayoutParams(mp));
        sMainLayer = new FrameLayout(c);
        sMainLayer.setBackgroundColor(Color.BLACK);            // ak_flayout @color/tvui_blank
        sMainLayer.setVisibility(View.GONE);
        sWarp = new AkWarpView(c);
        sWarp.setCaptions(c.getString(R.string.ak_caption_keystone), c.getString(R.string.ak_caption_zoom));
        sMainLayer.addView(sWarp, new FrameLayout.LayoutParams(mp));
        sRoot.addView(sMainLayer, new FrameLayout.LayoutParams(mp));
        Bitmap b = sBitmap;
        if (b != null) sWarp.setPattern(b);
        sLp = OverlayHost.fullscreenParams(true);
        sLp.setTitle("Z9xAkOverlay");
    }

    /**
     * Main thread: BACK hid the overlay (see the class comment). No ack: hide() bumps the token, so
     * every ack still pending (animation end, frame commit) is skipped.
     */
    private static void hideByBack() {
        long up = SystemClock.elapsedRealtime() - sShownAt;
        Log.w(TAG, "BACK after " + up + " ms: overlay hidden by the user, nothing acked; the rest of this"
                + " AK run is not shown (the vendor times out on its own)");
        sLastAkEventAt = SystemClock.uptimeMillis();         // before the flag: isActive() reads both
        sUserHidden = true;
        hide("back");
    }

    // ================================================================== main-thread hang watchdog
    /** Main thread (ensureShown). */
    private static void armHangWatchdog() {
        if (sHang == null) sHang = SafeHandler.newThread("z9x-akhang");
        sPingAt = 0;
        sHangArmed = true;
        sHang.removeCallbacks(HANG_CHECK);
        sHang.postDelayed(HANG_CHECK, HANG_PING_MS);
    }

    /** Main thread (hide). */
    private static void disarmHangWatchdog() {
        sHangArmed = false;
        if (sHang != null) sHang.removeCallbacks(HANG_CHECK);
    }

    /** "z9x-akhang", every HANG_PING_MS while armed: one ping in flight at a time. */
    private static void hangCheck() {
        if (!sHangArmed) return;
        long now = SystemClock.uptimeMillis();
        long sent = sPingAt;
        if (sent == 0) {
            sPingAt = now;                                    // before the post: PONG may run at once
            sMain.post(PONG);
        } else if (now - sent > HANG_MS) {
            onMainHang(now - sent);
            return;
        }
        sHang.postDelayed(HANG_CHECK, HANG_PING_MS);
    }

    /** "z9x-akhang": the main thread is stuck with the overlay up: log its stack, restart the process. */
    private static void onMainHang(long silentMs) {
        if (!sHangArmed || !sShown) return;
        Thread main = Looper.getMainLooper().getThread();
        Log.e(TAG, "main thread silent for " + silentMs + " ms with the AK overlay up (state " + main.getState()
                + "); main thread stack:");
        for (StackTraceElement f : main.getStackTrace()) Log.e(TAG, "    at " + f);   // one line each: no 4 KB cut
        if (Debug.isDebuggerConnected()) {
            Log.w(TAG, "debugger attached: process not restarted");
            sHangArmed = false;
            return;
        }
        Log.e(TAG, "restarting the projector process (persistent: the system starts it again; the overlay goes with it)");
        Process.killProcess(Process.myPid());
    }

    /**
     * Full-screen root: eats every key while the AK runs (stock dialog: setCancelable(false)), except a
     * BACK press that starts {@value #BACK_AFTER_MS} ms or more after the overlay appeared: its UP hides
     * it (acting on the UP keeps the whole press inside this window).
     */
    private static final class Root extends FrameLayout {
        Root(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                    long up = SystemClock.elapsedRealtime() - sShownAt;
                    sBackArmed = sShown && up >= BACK_AFTER_MS;
                    if (!sBackArmed) Log.i(TAG, "BACK ignored: overlay up only " + up + " ms");
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    boolean go = sBackArmed && sShown && !e.isCanceled();
                    sBackArmed = false;
                    if (go) hideByBack();
                }
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                Log.i(TAG, "key " + KeyEvent.keyCodeToString(code) + " ignored during AK");
            }
            return true;
        }
    }

    /** The stock ak_scale_bg_shape: a white oval of 1920dp square, FIT_CENTER in the full view. */
    private static final class CurtainView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CurtainView(Context c) {
            super(c);
            p.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), d = Math.min(w, h);
            c.drawOval((w - d) / 2f, (h - d) / 2f, (w + d) / 2f, (h + d) / 2f, p);
        }
    }

    // ================================================================== pattern
    /** "z9x-akgen": reads config.yaml once; falls back to the stock-derived geometry. */
    private static void loadSpec() {
        AkPatternSpec spec = null;
        try {
            String yaml = readSmallFile(new File(CONFIG_PATH));
            spec = AkPatternSpec.fromConfig(yaml);
            Log.i(TAG, "pattern from " + CONFIG_PATH + ": " + spec + (spec.latticeMatchesStock()
                    ? " (lattice == stock ak4_30w)" : " (lattice DIFFERS from stock ak4_30w)"));
        } catch (Throwable t) {
            Log.w(TAG, "config.yaml not usable (" + t + "), using the built-in stock geometry");
        }
        if (spec == null) spec = AkPatternSpec.builtin();
        sSpec = spec;
    }

    private static String readSmallFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_CONFIG_BYTES) throw new IllegalStateException("config.yaml too large");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Renders the bitmap on "z9x-akgen" (106 gives ~600 ms before 107 needs it). */
    private static void startPatternGeneration() {
        if (sBitmap != null || sGenerating || sGen == null) return;
        sGenerating = true;
        int[] size = patternSize();
        final int w = size[0], h = size[1];
        sGen.post(() -> {
            try {
                AkPatternSpec s = sSpec != null ? sSpec : AkPatternSpec.builtin();
                long t0 = SystemClock.elapsedRealtime();
                Bitmap b = AkPatternRenderer.render(s, w, h);
                Log.i(TAG, "pattern " + w + "x" + h + " rendered in " + (SystemClock.elapsedRealtime() - t0) + " ms");
                sMain.post(() -> {
                    sBitmap = b;
                    if (sWarp != null) sWarp.setPattern(b);
                });
            } finally {
                sGenerating = false;
            }
        });
    }

    /** Main thread: makes sure the warp view has the pattern; renders here only as a last resort. */
    private static boolean ensurePattern() {
        Bitmap b = sBitmap;
        if (b == null) {
            try {
                AkPatternSpec s = sSpec != null ? sSpec : AkPatternSpec.builtin();
                int[] size = patternSize();
                b = AkPatternRenderer.render(s, size[0], size[1]);
                sBitmap = b;
                Log.w(TAG, "pattern rendered on the main thread (background render not ready)");
            } catch (Throwable t) {
                Log.e(TAG, "pattern render failed", t);
                return false;
            }
        }
        if (!sWarp.hasPattern()) sWarp.setPattern(b);
        return true;
    }

    /**
     * Pattern mask size: the real UI size (1080p / 2K / 4K, landscape), capped at the DLP panel size
     * (keeps 16:9), so the pattern is as sharp as the frame and never larger than 8.3 MB.
     */
    private static int[] patternSize() {
        DisplayMetrics m = sApp.getResources().getDisplayMetrics();
        int w = Math.max(m.widthPixels, m.heightPixels), h = Math.min(m.widthPixels, m.heightPixels);
        if (w <= 0 || h <= 0) {
            w = AkPatternSpec.W;
            h = AkPatternSpec.H;
        }
        if (w > MAX_PATTERN_W || h > MAX_PATTERN_H) {
            float f = Math.min(MAX_PATTERN_W / (float) w, MAX_PATTERN_H / (float) h);
            w = Math.max(1, Math.round(w * f));
            h = Math.max(1, Math.round(h * f));
        }
        return new int[]{w, h};
    }

    // ================================================================== helpers
    /**
     * "x-y,x-y,x-y,x-y" (TL, TR, BL, BR; stock 1920x1080 units, or UI pixels up to 3840x2160 at the 4K UI:
     * AkPatternSpec.cornerScale) -> 8 floats, or null.
     */
    static float[] parseCorners(String v) {
        if (v == null) return null;
        String s = v.trim().replace(',', '-');
        if (s.isEmpty()) return null;
        String[] p = s.split("-");
        if (p.length != 8) return null;
        float[] out = new float[8];
        try {
            for (int i = 0; i < 8; i++) {
                int n = Integer.parseInt(p[i].trim());
                int lim = (i & 1) == 0 ? AkPatternSpec.W : AkPatternSpec.H;
                if (n < -lim || n > 2 * lim) return null;             // nonsense: keep the full frame
                out[i] = n;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Stock getDelayAKAnimateTime: the first AK while the setup wizard runs waits 400 ms. */
    private static boolean setupRunning() {
        try {
            return Settings.Secure.getInt(sApp.getContentResolver(), "user_setup_complete", 1) == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
