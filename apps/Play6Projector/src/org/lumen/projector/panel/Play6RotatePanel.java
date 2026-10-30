package org.lumen.projector.panel;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.hal.Play6Gmpf;
import org.lumen.projector.hal.Play6Gmpf2;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Theme;

import java.util.Locale;

/**
 * Play 6 "Picture rotation" (P3 item 21; stock Settings > Picture correction > picture_screen_rotation, RotateWind,
 * research B 4.1): straightens a picture that sits slightly tilted on the screen, by the keystone engine, without
 * moving anything. Ideas from Z9xProjector panel/RotatePanel.java (Lumen OS 1.0.4); the codes are the Play 6
 * IGmpf2 ones (130 / 138 on v1.7.116, 129 / 137 on v1.1.67, Play6Gmpf2.c()).
 *   RIGHT: 0.5 degrees clockwise (5), LEFT: counter-clockwise (6) (stock step: user_manu_screen_rotation_step,
 *   default 0.5, not set on the G0058), OK: back to the angle the card opened with, BACK: done.
 * Sequence (Play6AdjustSession for the bracket): IGmpf2 125(1) -> 586 / 573(2,false) -> IGmpf2 130 read {min, max,
 * cur, start} (Play6Gmpf2 refuses an implausible reply and logs its raw values; then the card closes with "not
 * available" and the quick panel row is hidden for this process). Per key: a fresh 130 read, the target inside
 * [min, max] (else "largest angle"), 138(5 | 6, 0.5), 130 again. Close: 573(2,true), 125(0); no 158 (O4).
 * Log tag P6Rotate.
 */
public final class Play6RotatePanel extends Panel {
    static final String TAG = "P6Rotate";

    private static final float STEP = 0.5f;
    private static final long RESYNC_MS = 1_500;
    private static final long AUTO_HIDE_MS = 60_000;
    private static final long ENTER_WATCHDOG_MS = 6_000;
    private static final int WAIT = 0, READY = 1, FAILED = 2;

    private static volatile Play6RotatePanel sOpen;
    /** The 130 reply did not parse in this process: the row stays hidden. */
    private static volatile boolean sUnsupported;

    private final Context app;
    private final Runnable onBackExit;
    private volatile int state = WAIT;
    private volatile boolean inFlight, closed, backExit, active;
    private volatile float min, max, cur, opened;
    /** Main thread adds, HAL thread takes (under the lock): degrees still to send, positive = clockwise. */
    private final Object lock = new Object();
    private float pending;
    private boolean undo;
    private int gen;
    private TextView value, status;

    private Play6RotatePanel(Context app, Runnable onBackExit) {
        this.app = app;
        this.onBackExit = onBackExit;
    }

    static boolean isOpen() { return sOpen != null; }

    static boolean isUnsupported() { return sUnsupported; }

    /** A 130 parsed on this projector before (any process): the quick panel shows the row without a probe. */
    static boolean knownSupported(Context c) {
        try {
            return !sUnsupported && c.getSharedPreferences("p6rot", Context.MODE_PRIVATE).getBoolean("ok", false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void markSupported(Context c) {
        try {
            c.getSharedPreferences("p6rot", Context.MODE_PRIVATE).edit().putBoolean("ok", true).apply();
        } catch (Throwable ignored) { }
    }

    static void markUnsupported(String why) {
        if (!sUnsupported) Log.w(TAG, "picture rotation hidden for this process: " + why);
        sUnsupported = true;
    }

    /** Main thread. {@code onBackExit} (may be null) runs after a BACK exit. */
    static void open(Context ctx, Runnable onBackExit) {
        final Context app = ctx.getApplicationContext();
        if (!Hal.isReady() || !Hal.hasGmpf2()) { refuse(app, "HAL not ready or IGmpf2 guard closed", R.string.rot_unavailable); return; }
        if (Hal.isKeystoneRunning() || AkOverlay.isActive()) { refuse(app, "auto keystone running", R.string.kst_reason_ak_busy); return; }
        if (Play6AdjustSession.anyOpen()) { Log.i(TAG, "another adjustment card is open"); return; }
        final Play6RotatePanel p = new Play6RotatePanel(app, onBackExit);
        if (!OverlayHost.get(app).show(p)) { refuse(app, "overlay window could not be added", R.string.rot_unavailable); return; }
        sOpen = p;
        if (!Hal.run((g, g2) -> p.enter(g, g2))) {
            p.fail("not queued");
            return;
        }
        Ui.main().postDelayed(() -> {
            if (sOpen == p && p.state == WAIT && p.isShowing()) p.fail("enter did not run within " + ENTER_WATCHDOG_MS + " ms");
        }, ENTER_WATCHDOG_MS);
    }

    private static void refuse(Context app, String log, int res) {
        Log.w(TAG, "refused: " + log);
        Notify.show(app, res);
    }

    // =================================================================== HAL thread
    private void enter(Play6Gmpf g, Play6Gmpf2 g2) {
        if (closed) { Log.i(TAG, "enter skipped: the card is already closed"); return; }
        try {
            if (!Play6AdjustSession.pushPullOn(app, g2, "rotate enter")) {
                ui(() -> fail("IGmpf2 125(1) not delivered"));
                return;
            }
            active = true;
            Play6AdjustSession.pauseRealtime(app, g, "rotate enter");
            float[] r = g2.getScreenRotation();                      // IGmpf2 130
            min = r[0];
            max = r[1];
            cur = opened = r[2];
            Log.i(TAG, "130 -> min " + r[0] + " max " + r[1] + " cur " + r[2] + " start " + r[3]);
            markSupported(app);
            state = READY;
            ui(this::render);
            if (closed) exitNow(g, g2, "closed during enter");
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (t instanceof Play6Gmpf2.ReplyException || t instanceof Play6Gmpf2.AbsentException) {
                markUnsupported("130: " + t.getMessage());
            }
            exitNow(g, g2, "enter failed");
            ui(() -> fail("enter"));
        }
    }

    private void exitNow(Play6Gmpf g, Play6Gmpf2 g2, String who) {
        if (!active) return;
        active = false;
        if (!Play6AdjustSession.close(app, g, g2, who)) Log.w(TAG, who + ": close incomplete, left to the connect hook");
    }

    /** HAL thread: sends what the keys queued, as few 138s as possible, then lets the main thread re-read later. */
    private void drain(Play6Gmpf2 g2) {
        try {
            while (state == READY && !closed) {
                float deg;
                synchronized (lock) {
                    if (undo) { pending = opened - cur; undo = false; }
                    deg = pending;
                    pending = 0;
                }
                if (Math.abs(deg) < 0.05f) break;
                boolean cw = deg > 0;
                float abs = Math.min(Math.abs(deg), 45f);               // Play6Gmpf2.checkRotationStep bound
                boolean ok = g2.rotateScreen(cw, abs);                   // IGmpf2 138
                if (ok) cur = Math.max(min, Math.min(max, cur + (cw ? abs : -abs)));
                Log.i(TAG, "138(" + (cw ? 5 : 6) + ", " + abs + ") -> " + ok + ", now " + cur);
                ui(() -> { render(); if (!ok) say(R.string.rot_refused); });
            }
        } catch (Throwable t) {
            Log.w(TAG, "step failed: " + t);
            ui(() -> say(R.string.rot_refused));
        } finally {
            inFlight = false;
            final int g = ++gen;
            Ui.main().postDelayed(() -> resync(g), RESYNC_MS);
        }
    }

    /** Main thread, 1.5 s after the last 138: one 130 read, unless more steps came meanwhile. */
    private void resync(int g) {
        if (g != gen || inFlight || closed || state != READY) return;
        Hal.run((gm, g2) -> {
            if (g != gen || inFlight || closed) return;
            try {
                cur = g2.getScreenRotation()[2];
                ui(this::render);
            } catch (Throwable t) {
                Log.d(TAG, "re-read: " + t);
            }
        });
    }

    // =================================================================== keys and window (main thread)
    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!ok && !left && !right) return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0 && ok) return true;
        if (state != READY) return true;
        synchronized (lock) {
            if (ok) {
                undo = true;
                pending = 0;
            } else {
                float target = cur + pending + (right ? STEP : -STEP);
                if (target > max + 0.01f || target < min - 0.01f) {
                    say(R.string.rot_limit);
                    return true;
                }
                pending += right ? STEP : -STEP;
            }
        }
        showTarget();
        if (!inFlight) {
            inFlight = true;
            if (!Hal.run((g, g2) -> drain(g2))) inFlight = false;
        }
        return true;
    }

    /** The angle the queued steps lead to, shown at once (the projector catches up). */
    private void showTarget() {
        if (value == null) return;
        float t;
        synchronized (lock) { t = undo ? opened : cur + pending; }
        float a = Math.round(t * 10f) / 10f;
        value.setText(String.format(Locale.ROOT, "%+.1f°", a == 0f ? 0f : a));
        status.setText("");
    }

    @Override
    protected boolean onBack() {
        backExit = true;
        return false;
    }

    @Override
    protected void onDismissed() {
        closed = true;
        if (sOpen == this) sOpen = null;
        if (!Hal.run((g, g2) -> exitNow(g, g2, "rotate close"))) Log.w(TAG, "close not queued: left to the connect hook");
        if (backExit && onBackExit != null) Ui.main().post(onBackExit);
    }

    private void fail(String why) {
        Log.w(TAG, "unavailable: " + why);
        state = FAILED;
        Notify.show(app, R.string.rot_unavailable);
        dismiss();
    }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        return Play6ZoomShiftPanel.EdgeGrid.fullscreen(c, TAG);
    }

    @Override
    protected View onCreateView(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.addView(new Play6ZoomShiftPanel.EdgeGrid(c), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = Play6ZoomShiftPanel.EdgeGrid.card(c);
        card.addView(Play6ZoomShiftPanel.EdgeGrid.text(c, c.getString(R.string.rot_title), 40, Theme.TEXT));
        value = Play6ZoomShiftPanel.EdgeGrid.text(c, c.getString(R.string.zoom_wait), 56, Theme.TEXT);
        card.addView(value);
        card.addView(Play6ZoomShiftPanel.EdgeGrid.text(c, c.getString(R.string.rot_hint), 28, Theme.TEXT_DIM));
        status = Play6ZoomShiftPanel.EdgeGrid.text(c, "", 28, Theme.TEXT_DIM);
        card.addView(status);
        root.addView(card, new FrameLayout.LayoutParams(Theme.px(c, 900), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return root;
    }

    private void render() {
        if (value == null || state != READY) return;
        float a = Math.round(cur * 10f) / 10f;
        value.setText(String.format(Locale.ROOT, "%+.1f°", a == 0f ? 0f : a));
        status.setText("");
    }

    private void say(int res) {
        if (status != null) status.setText(app.getString(res));
    }

    private static void ui(Runnable r) { Ui.main().post(r); }
}
