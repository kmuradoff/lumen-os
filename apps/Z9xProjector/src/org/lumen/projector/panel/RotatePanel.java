package org.lumen.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
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
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Theme;

import java.util.Locale;

/**
 * Lumen OS 1.0.4: "Picture rotation", the stock RotateWind of the Z9X (picture_screen_rotation): straightens a
 * picture that sits slightly tilted on the screen (a projector that does not stand level), by the keystone
 * engine, without moving anything. A full-screen grid (ZoomShiftPanel.EdgeGrid) shows the picture's edge.
 *   RIGHT: 0.5 degrees clockwise, LEFT: counter-clockwise, OK: back to the angle the screen opened with,
 *   BACK: done.
 * Stock calls: IGmpf2 194(1) and, while real-time keystone is on, IGmpf 573(2, false) on open; IGmpf2 199
 * getScreenRotationInfo {min, max, current, start} (checked by the client before anything moves); per key
 * IGmpf2 207 setScreenRotation(5 clockwise | 6 counter-clockwise, 0.5) inside [min, max], then 199 again; on
 * close 158(0) when the angle changed, 194(0), 573(2, true). The step is the stock default
 * (user_manu_screen_rotation_step 0.5). "Reset keystone" also straightens the picture again.
 */
public final class RotatePanel extends Panel {
    static final String TAG = "Z9xRotate";

    private static final String PREFS = "z9x_rotate";
    private static final String K_OPEN = "session_open";
    private static final float STEP = 0.5f;
    private static final long MIN_STEP_GAP_MS = 150;
    private static final long AUTO_HIDE_MS = 60_000;
    private static final long RECOVER_DELAY_MS = 6_000;

    private static final int WAIT = 0, READY = 1, FAILED = 2;

    private static RotatePanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private volatile int state = WAIT;
    private volatile boolean inFlight, closed, backExit, akPaused, moved;
    private volatile long lastStepAt;
    private final java.util.concurrent.atomic.AtomicBoolean session = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile float min, max, cur, opened;
    private TextView value, status;

    private RotatePanel(Context app, Runnable onBackExit) {
        this.app = app;
        this.onBackExit = onBackExit;
    }

    static void open(Context ctx, Runnable onBackExit) {
        Context app = ctx.getApplicationContext();
        if (!Hal.isReady()) { Notify.show(app, R.string.rot_unavailable); return; }
        if (Hal.isKeystoneRunning() || AkOverlay.isActive()) { Notify.show(app, R.string.kst_reason_ak_busy); return; }
        if (sOpen != null || ZoomShiftPanel.isOpen()) return;
        RotatePanel p = new RotatePanel(app, onBackExit);
        if (!OverlayHost.get(app).show(p)) { Notify.show(app, R.string.rot_unavailable); return; }
        sOpen = p;
        if (!Hal.run(p::enter)) p.fail("not queued");
    }

    static boolean isOpen() { return sOpen != null; }

    /** App start: closes a rotation screen a dead process left open (marker), after every HAL (re)connect. */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> Hal.runDelayed((g, g2) -> {
            if (sOpen == null && marker(app)) {
                Log.i(TAG, "connect: closing a rotation screen left open");
                close(app, g, g2, true, false);
            }
        }, RECOVER_DELAY_MS));
    }

    // =================================================================== HAL thread

    private void enter(GmpfClient g, Gmpf2Client g2) throws Exception {
        if (closed) return;
        setMarker(app, true);
        try {
            session.set(true);
            Log.i(TAG, "enter: 194(1) -> " + g2.setAdjustScreenOpen(true));
            if (g.getMoveAk()) {
                akPaused = true;
                Log.i(TAG, "573(2,false) -> " + g.setRealtimeAkTemporarily(false));
            }
            float[] r = g2.getScreenRotation();
            min = r[0];
            max = r[1];
            cur = opened = r[2];
            Log.i(TAG, "199 -> min " + r[0] + " max " + r[1] + " cur " + r[2] + " start " + r[3]);
            state = READY;
            ui(this::render);
            if (closed && session.compareAndSet(true, false)) close(app, g, g2, akPaused, moved);
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (session.compareAndSet(true, false)) close(app, g, g2, akPaused, moved); else setMarker(app, false);
            ui(() -> fail("enter"));
        }
    }

    /** One step towards {@code clockwise}, or (undo) back to the opening angle. */
    private void step(Gmpf2Client g2, Boolean clockwise) {
        try {
            if (state != READY || closed) return;
            float now = g2.getScreenRotation()[2];
            boolean cw;
            float deg;
            if (clockwise == null) {                           // undo: one relative move back
                float diff = opened - now;
                if (Math.abs(diff) < 0.05f) return;
                cw = diff > 0;
                deg = Math.min(Math.abs(diff), 10f);
            } else {
                cw = clockwise;
                deg = STEP;
                float target = cw ? now + STEP : now - STEP;
                if (target > max + 0.01f || target < min - 0.01f) {
                    cur = now;
                    ui(() -> { render(); say(R.string.rot_limit); });
                    return;
                }
            }
            boolean ok = g2.rotateScreen(cw, deg);
            cur = g2.getScreenRotation()[2];
            moved = true;
            Log.i(TAG, "207(" + (cw ? 5 : 6) + ", " + deg + ") -> " + ok + ", now " + cur);
            ui(() -> { render(); if (!ok) say(R.string.rot_refused); });
        } catch (Throwable t) {
            Log.w(TAG, "step failed: " + t);
            ui(() -> say(R.string.rot_refused));
        } finally {
            lastStepAt = SystemClock.uptimeMillis();
            inFlight = false;
        }
    }

    private static void close(Context app, GmpfClient g, Gmpf2Client g2, boolean akPaused, boolean moved) {
        if (moved) {
            try { Log.i(TAG, "158(0) -> " + g.regenerateBootLogo()); } catch (Throwable t) { Log.w(TAG, "158: " + t); }
        }
        boolean ok = true;
        try { Log.i(TAG, "194(0) -> " + g2.setAdjustScreenOpen(false)); } catch (Throwable t) { ok = false; Log.w(TAG, "194: " + t); }
        if (akPaused) {
            try { Log.i(TAG, "573(2,true) -> " + g.setRealtimeAkTemporarily(true)); } catch (Throwable t) { Log.w(TAG, "573: " + t); }
        }
        if (ok) setMarker(app, false);
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
        if (state != READY || inFlight || SystemClock.uptimeMillis() - lastStepAt < MIN_STEP_GAP_MS) return true;
        inFlight = true;
        final Boolean cw = ok ? null : (Boolean) right;
        if (!Hal.run((g, g2) -> step(g2, cw))) inFlight = false;
        return true;
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
        if (state == READY && !Hal.run((g, g2) -> {
            if (session.compareAndSet(true, false)) close(app, g, g2, akPaused, moved);
        })) {
            Log.w(TAG, "close not queued: left to the connect hook");
        }
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
        return ZoomShiftPanel.EdgeGrid.fullscreen(c, TAG);
    }

    @Override
    protected View onCreateView(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.addView(new ZoomShiftPanel.EdgeGrid(c), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = ZoomShiftPanel.EdgeGrid.card(c);
        card.addView(ZoomShiftPanel.EdgeGrid.text(c, c.getString(R.string.rot_title), 40, Theme.TEXT));
        value = ZoomShiftPanel.EdgeGrid.text(c, c.getString(R.string.zoom_wait), 56, Theme.TEXT);
        card.addView(value);
        card.addView(ZoomShiftPanel.EdgeGrid.text(c, c.getString(R.string.rot_hint), 28, Theme.TEXT_DIM));
        status = ZoomShiftPanel.EdgeGrid.text(c, "", 28, Theme.TEXT_DIM);
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

    private static SharedPreferences prefs(Context app) {
        return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean marker(Context app) {
        try { return prefs(app).getBoolean(K_OPEN, false); } catch (Throwable t) { return false; }
    }

    private static void setMarker(Context app, boolean on) {
        try { prefs(app).edit().putBoolean(K_OPEN, on).commit(); } catch (Throwable t) { Log.w(TAG, "marker: " + t); }
    }
}
