package org.z9x.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
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

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.hal.Gmpf2Client;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

/**
 * Lumen OS 1.0.2: manual OPTICAL zoom (the Z9X has a zoom lens, throw ratio 0.98-1.3:1; the stock menu
 * offers it in "zoom_and_move"). A full-screen overlay like the manual keystone (grey grid, a white line on
 * the outermost pixels = the picture edge), so the user lays the edge on the screen frame:
 *   RIGHT / UP = bigger, LEFT / DOWN = smaller (held: repeats), BACK = done.
 *
 * Exactly the stock calls (V6.15.58 dump, ZoomStateView / KeyStoneManager): IGmpf2 18(true, 2), IGmpf 121
 * zoomStart(0), 117 getZoomStepRange, 116 getCurrentZoomStep2; per step a fresh 116 read, then 119
 * setOpticalZoomStep(current - 1 bigger | + 1 smaller), only inside [0, opticalMax]; at the end 120
 * zoomFinish(0) and 18(false, 2), also after errors and process death (a persisted marker makes the
 * connect hook send them). Each 119 is ONE bounded move (~0.4 s, the vendor stops at its limits and end
 * sensor and stores the position), at most one in flight and >= 220 ms apart, as stock: a held key cannot
 * run the motor away. Index 0 = the biggest picture.
 * After the session, if the position changed, autofocus runs (our remote AF path, 307(44); stock's 301
 * mode 47 is not used). Auto keystone / fit to screen move the zoom again (the vendor resets and re-fits it).
 */
public final class OpticalZoomPanel extends Panel {
    static final String TAG = "Z9xZoom";

    private static final String PREFS = "z9x_zoom";
    private static final String K_OPEN = "session_open";
    private static final long MIN_STEP_GAP_MS = 220;
    /** After a 119: poll 116 this often until the motor has stopped, at most SETTLE_MAX_MS. */
    private static final long SETTLE_POLL_MS = 100, SETTLE_MAX_MS = 2_000;
    /** A position other than the target counts as final only after one full move (~0.4 s) has passed. */
    private static final long SETTLE_MIN_MS = 700;
    private static final long AUTO_HIDE_MS = 60_000;
    /** Sanity bound for opticalMax (the Z9X has ~8 positions: 1350 motor steps / 160 per move). */
    private static final int MAX_POSITIONS = 64;
    private static final long RECOVER_DELAY_MS = 6_000;

    private static final int WAIT = 0, READY = 1, FAILED = 2;

    private static OpticalZoomPanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private volatile int state = WAIT;
    private volatile boolean inFlight, closed, backExit;
    private volatile long lastStepAt;
    /** true between 121 and our 120: whoever flips it to false sends the close (enter's end or onDismissed). */
    private final java.util.concurrent.atomic.AtomicBoolean session = new java.util.concurrent.atomic.AtomicBoolean();
    private int max = -1, cur = -1, start = -1;          // optical index; written on the HAL thread
    private TextView value, status;

    private OpticalZoomPanel(Context app, Runnable onBackExit) {
        this.app = app;
        this.onBackExit = onBackExit;
    }

    // =================================================================== entry (main thread)

    /** Opens the zoom screen (it replaces the quick panel); {@code onBackExit} runs after a BACK exit. */
    static void open(Context ctx, Runnable onBackExit) {
        Context app = ctx.getApplicationContext();
        if (!Hal.isReady()) { Notify.show(app, R.string.zoom_unavailable); return; }
        if (Hal.isKeystoneRunning() || AkOverlay.isActive()) { Notify.show(app, R.string.kst_reason_ak_busy); return; }
        if (sOpen != null) return;
        OpticalZoomPanel p = new OpticalZoomPanel(app, onBackExit);
        if (!OverlayHost.get(app).show(p)) { Notify.show(app, R.string.zoom_unavailable); return; }
        sOpen = p;
        if (!Hal.run(p::enter)) {
            p.fail("not queued");
        }
    }

    /** App start: closes a session a dead process left open (marker), after every HAL (re)connect. */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> Hal.runDelayed((g, g2) -> {
            if (sOpen == null && marker(app)) {
                Log.i(TAG, "connect: closing a zoom session left open");
                close(app, g, g2);
            }
        }, RECOVER_DELAY_MS));
    }

    // =================================================================== HAL thread

    private void enter(GmpfClient g, Gmpf2Client g2) throws Exception {
        if (closed) return;
        setMarker(app, true);
        try {
            session.set(true);
            Log.i(TAG, "enter: 18(true,2) -> " + g2.setKstPrepareForZoom(true) + ", 121(0) -> " + g.zoomSessionStart());
            int[] range = g.getZoomStepRange();
            int[] step = g.getZoomStep();
            Log.i(TAG, "117 -> ret " + range[0] + " optical " + range[1] + ".." + range[2] + " digital " + range[3]
                    + ".." + range[4] + "; 116 -> ret " + step[0] + " optical " + step[1] + " digital " + step[2]);
            if (range[1] != 0 || range[2] < 1 || range[2] > MAX_POSITIONS || step[1] < 0 || step[1] > range[2]) {
                throw new IllegalStateException("unexpected zoom range / step");
            }
            max = range[2];
            cur = start = step[1];
            state = READY;
            ui(this::render);
            if (closed && session.compareAndSet(true, false)) close(app, g, g2);   // dismissed while entering
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (session.compareAndSet(true, false)) close(app, g, g2); else setMarker(app, false);
            ui(() -> fail("enter"));
        }
    }

    private void step(GmpfClient g, boolean bigger) {
        try {
            if (state != READY || closed) return;
            int now = g.getZoomStep()[1];
            if (now < 0 || now > max) throw new IllegalStateException("116 optical " + now);
            int target = bigger ? now - 1 : now + 1;
            if (target < 0 || target > max) {
                cur = now;
                ui(() -> { render(); say(bigger ? R.string.zoom_limit_big : R.string.zoom_limit_small); });
                return;
            }
            int r = g.setOpticalZoomStep(target);
            cur = settled(g, target, now);
            Log.i(TAG, "119(" + target + ") -> " + r + ", now " + cur);
            ui(() -> { render(); if (r != 0) say(R.string.zoom_refused); });
        } catch (Throwable t) {
            Log.w(TAG, "step failed: " + t);
            ui(() -> say(R.string.zoom_refused));
        } finally {
            lastStepAt = SystemClock.uptimeMillis();
            inFlight = false;
        }
    }

    /**
     * The position after a 119. The motor moves for ~0.4 s and 116 lags behind it: reading it right away
     * left the number stale, and a key pressed right after (LEFT then RIGHT at once) stepped from that
     * stale or in-between value, so the screen later claimed the biggest picture (owner, 2026-10-12).
     * Poll 116 until it reaches {@code target}, or reads the same valid value 3 times in a row after
     * SETTLE_MIN_MS (the vendor stopped elsewhere, e.g. at a limit), at most SETTLE_MAX_MS. inFlight
     * stays true meanwhile, so keys wait. HAL thread.
     */
    private int settled(GmpfClient g, int target, int before) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        int last = before, same = 0;
        while (SystemClock.uptimeMillis() - t0 < SETTLE_MAX_MS) {
            SystemClock.sleep(SETTLE_POLL_MS);
            int now = g.getZoomStep()[1];
            if (now < 0 || now > max) continue;            // in between: not a position yet
            if (now == target) return now;
            if (now == last) {
                if (++same >= 3 && SystemClock.uptimeMillis() - t0 >= SETTLE_MIN_MS) return now;
            } else { same = 0; last = now; }
        }
        Log.w(TAG, "119(" + target + "): no settled position after " + SETTLE_MAX_MS + " ms, last " + last);
        return last;
    }

    /** 120(0) + 18(false,2), marker cleared on success. HAL thread. */
    private static void close(Context app, GmpfClient g, Gmpf2Client g2) {
        boolean ok = true;
        try { Log.i(TAG, "120(0) -> " + g.zoomSessionFinish()); } catch (Throwable t) { ok = false; Log.w(TAG, "120: " + t); }
        try { Log.i(TAG, "18(false,2) -> " + g2.setKstPrepareForZoom(false)); } catch (Throwable t) { ok = false; Log.w(TAG, "18(false,2): " + t); }
        if (ok) setMarker(app, false);
    }

    // =================================================================== keys and window (main thread)

    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean bigger = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP;
        boolean smaller = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!bigger && !smaller) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;   // OK: nothing
        }
        // one move at a time and >= 220 ms apart; a key up simply stops new moves (none is queued)
        if (ev.getAction() == KeyEvent.ACTION_DOWN && state == READY && !inFlight
                && SystemClock.uptimeMillis() - lastStepAt >= MIN_STEP_GAP_MS) {
            inFlight = true;
            if (!Hal.run((g, g2) -> step(g, bigger))) inFlight = false;
        }
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
        boolean moved = state == READY && cur != start;
        // a session still entering is closed by the end of enter(); an open one here
        if (state == READY && !Hal.run((g, g2) -> {
            if (session.compareAndSet(true, false)) close(app, g, g2);
            if (moved) ui(() -> Hal.requestAutofocus(app));
        })) {
            Log.w(TAG, "close not queued: left to the connect hook");
        }
        if (backExit && onBackExit != null) Ui.main().post(onBackExit);
    }

    private void fail(String why) {
        Log.w(TAG, "unavailable: " + why);
        state = FAILED;
        Notify.show(app, R.string.zoom_unavailable);
        dismiss();
    }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        try {
            android.graphics.Point real = new android.graphics.Point();
            c.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(real);
            if (real.x > 0 && real.y > 0) { lp.width = real.x; lp.height = real.y; }
        } catch (Throwable t) {
            Log.w(TAG, "real display size: " + t);
        }
        lp.x = 0;
        lp.y = 0;
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        return lp;
    }

    @Override
    protected View onCreateView(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.addView(new EdgeView(c), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Theme.px(c, 36);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PANEL_BG);
        bg.setCornerRadius(Theme.px(c, 24));
        card.setBackground(bg);
        card.addView(text(c, c.getString(R.string.panel_optical_zoom), 40, Theme.TEXT));
        value = text(c, c.getString(R.string.zoom_wait), 56, Theme.TEXT);
        card.addView(value);
        card.addView(text(c, c.getString(R.string.zoom_hint), 30, Theme.TEXT_DIM));
        status = text(c, "", 28, Theme.TEXT_DIM);
        card.addView(status);
        root.addView(card, new FrameLayout.LayoutParams(Theme.px(c, 900), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return root;
    }

    private static TextView text(Context c, CharSequence s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Theme.px(c, 6), 0, Theme.px(c, 6));
        return t;
    }

    /** "Position N of M": 1 = the smallest picture, M = the biggest (index 0). */
    private void render() {
        if (value == null || state != READY) return;
        value.setText(app.getString(R.string.zoom_position, max - cur + 1, max + 1));
        status.setText("");
    }

    private void say(int res) {
        if (status != null) status.setText(app.getString(res));
    }

    private static void ui(Runnable r) { Ui.main().post(r); }

    // =================================================================== markers

    private static SharedPreferences prefs(Context app) {
        return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean marker(Context app) {
        try { return prefs(app).getBoolean(K_OPEN, false); } catch (Throwable t) { return false; }
    }

    /** Synchronous: the marker is on disk before 121 is sent. */
    private static void setMarker(Context app, boolean on) {
        try { prefs(app).edit().putBoolean(K_OPEN, on).commit(); } catch (Throwable t) { Log.w(TAG, "marker: " + t); }
    }

    /** Grey grid with a white line on the outermost pixels (the picture edge), as the manual keystone. */
    private static final class EdgeView extends View {
        private static final int BG = 0xFF8A8A8A, LINE = 0xFFE6E6E6;
        private final Paint line = new Paint(), edge = new Paint();

        EdgeView(Context c) {
            super(c);
            line.setColor(LINE);
            edge.setColor(0xFFFFFFFF);
        }

        private float px(float design) { return Math.max(1f, Math.round(Theme.pxf(getContext(), design))); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cell = px(120), t = px(2), e = px(6);
            c.drawColor(BG);
            for (float x = cell; x < w - 1; x += cell) c.drawRect(x - t / 2, 0, x + t / 2, h, line);
            for (float y = cell; y < h - 1; y += cell) c.drawRect(0, y - t / 2, w, y + t / 2, line);
            c.drawRect(0, 0, w, e, edge);
            c.drawRect(0, h - e, w, h, edge);
            c.drawRect(0, 0, e, h, edge);
            c.drawRect(w - e, 0, w, h, edge);
        }
    }
}
