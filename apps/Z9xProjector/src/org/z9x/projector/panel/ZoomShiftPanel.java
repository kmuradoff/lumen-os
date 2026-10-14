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
 * Lumen OS 1.0.4: "Zoom and shift" without the zoom motor, the stock zoom_and_move screen in its digital form
 * (ZoomMoveWind: ZoomStateView "digital only" + PtzViewModel): the picture is made smaller and moved inside
 * the projected area by the keystone engine, so it can be fitted to the screen frame without moving the
 * projector. A full-screen grid with a white edge (as the manual keystone) shows the picture's edge.
 *   Size (first): LEFT / DOWN smaller, RIGHT / UP bigger. OK switches to Shift: the arrows move the picture.
 *   BACK: done.
 * Stock calls, in stock order: IGmpf2 194(1) and IGmpf 121 zoomStart(0) on open; IGmpf 573(2, false) while
 * real-time keystone is on; IGmpf2 18(true, 2) before the first move; a size step = IGmpf 118
 * setDigitalZoomStep(digital +1 smaller | -1 bigger) inside the 117 digital range, from a fresh 116 read; a
 * shift step = IGmpf 141(dir, true) and, when that answers 0, 141(dir, false). On close: 120 zoomFinish(0),
 * 18(false, 2), 573(2, true), 158(0) when something moved, 194(0). Never 119 (the optical zoom, removed in
 * 1.0.4): every size step checks that 116's optical position stayed where it was, and the screen closes if
 * it did not. A process that dies with the session open is cleaned up by the connect hook (marker), as the
 * optical zoom did. "Reset keystone" puts size and position back.
 */
public final class ZoomShiftPanel extends Panel {
    static final String TAG = "Z9xZoomShift";

    private static final String PREFS = "z9x_zoomshift";
    private static final String K_OPEN = "session_open";
    private static final long MIN_STEP_GAP_MS = 150;
    private static final long AUTO_HIDE_MS = 60_000;
    private static final long RECOVER_DELAY_MS = 6_000;
    /** Sanity bound of the digital range (the stock dial has tens of steps). */
    private static final int MAX_DIGITAL_STEPS = 200;

    private static final int WAIT = 0, READY = 1, FAILED = 2;

    private static ZoomShiftPanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private volatile int state = WAIT;
    private volatile boolean inFlight, closed, backExit, shiftMode, moved, prepared, akPaused;
    private volatile long lastStepAt;
    private final java.util.concurrent.atomic.AtomicBoolean session = new java.util.concurrent.atomic.AtomicBoolean();
    /** Digital range and position (HAL thread writes); optical position at open (must not change). */
    private int lo, hi, cur, optical;
    private boolean digital;
    private TextView mode, value, hint, status;

    private ZoomShiftPanel(Context app, Runnable onBackExit) {
        this.app = app;
        this.onBackExit = onBackExit;
    }

    // =================================================================== entry (main thread)

    static void open(Context ctx, Runnable onBackExit) {
        Context app = ctx.getApplicationContext();
        if (!Hal.isReady()) { Notify.show(app, R.string.zs_unavailable); return; }
        if (Hal.isKeystoneRunning() || AkOverlay.isActive()) { Notify.show(app, R.string.kst_reason_ak_busy); return; }
        if (sOpen != null || RotatePanel.isOpen()) return;
        ZoomShiftPanel p = new ZoomShiftPanel(app, onBackExit);
        if (!OverlayHost.get(app).show(p)) { Notify.show(app, R.string.zs_unavailable); return; }
        sOpen = p;
        if (!Hal.run(p::enter)) p.fail("not queued");
    }

    static boolean isOpen() { return sOpen != null; }

    /** App start: closes a session a dead process left open (marker), after every HAL (re)connect. */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> Hal.runDelayed((g, g2) -> {
            if (sOpen == null && marker(app)) {
                Log.i(TAG, "connect: closing a zoom / shift session left open");
                close(app, g, g2, true, true, false);
            }
        }, RECOVER_DELAY_MS));
    }

    // =================================================================== HAL thread

    private void enter(GmpfClient g, Gmpf2Client g2) throws Exception {
        if (closed) return;
        setMarker(app, true);
        try {
            session.set(true);
            Log.i(TAG, "enter: 194(1) -> " + g2.setAdjustScreenOpen(true) + ", 121(0) -> " + g.zoomSessionStart());
            if (g.getMoveAk()) {
                akPaused = true;
                Log.i(TAG, "573(2,false) -> " + g.setRealtimeAkTemporarily(false));
            }
            int[] range = g.getZoomStepRange();
            int[] step = g.getZoomStep();
            Log.i(TAG, "117 -> ret " + range[0] + " optical " + range[1] + ".." + range[2] + " digital " + range[3]
                    + ".." + range[4] + "; 116 -> ret " + step[0] + " optical " + step[1] + " digital " + step[2]);
            optical = step[1];
            lo = Math.min(range[3], range[4]);
            hi = Math.max(range[3], range[4]);
            cur = step[2];
            digital = hi > lo && hi - lo <= MAX_DIGITAL_STEPS && cur >= lo && cur <= hi;
            if (!digital) {
                Log.w(TAG, "digital zoom range not usable: shift only");
                shiftMode = true;
            }
            state = READY;
            ui(this::render);
            if (closed && session.compareAndSet(true, false)) close(app, g, g2, prepared, akPaused, moved);
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (session.compareAndSet(true, false)) close(app, g, g2, prepared, akPaused, moved); else setMarker(app, false);
            ui(() -> fail("enter"));
        }
    }

    private void prepare(Gmpf2Client g2) throws Exception {
        if (prepared) return;
        prepared = true;
        Log.i(TAG, "18(true,2) -> " + g2.setKstPrepareForZoom(true));
    }

    private void sizeStep(GmpfClient g, Gmpf2Client g2, boolean smaller) {
        try {
            if (state != READY || closed || !digital) return;
            int[] now = g.getZoomStep();
            if (now[1] != optical) throw new IllegalStateException("optical position moved: " + optical + " -> " + now[1]);
            int target = smaller ? now[2] + 1 : now[2] - 1;
            if (target < lo || target > hi) {
                cur = now[2];
                ui(() -> { render(); say(smaller ? R.string.zoom_limit_small : R.string.zoom_limit_big); });
                return;
            }
            prepare(g2);
            int r = g.setDigitalZoomStep(target);
            int[] after = g.getZoomStep();
            cur = after[2];
            moved = true;
            Log.i(TAG, "118(" + target + ") -> " + r + ", now digital " + after[2] + " optical " + after[1]);
            if (after[1] != optical) throw new IllegalStateException("optical position moved: " + optical + " -> " + after[1]);
            ui(this::render);
        } catch (Throwable t) {
            Log.w(TAG, "size step failed: " + t);
            ui(() -> fail("size step"));
        } finally {
            lastStepAt = SystemClock.uptimeMillis();
            inFlight = false;
        }
    }

    private void shiftStep(GmpfClient g, Gmpf2Client g2, GmpfClient.ShiftDir d) {
        try {
            if (state != READY || closed) return;
            prepare(g2);
            int check = g.shiftPicture(d, true);
            int r = check == 0 ? g.shiftPicture(d, false) : check;
            Log.i(TAG, "141(" + d + ") check " + check + " -> " + r);
            if (check == 0 && r != -1) moved = true;
            ui(() -> { if (check != 0) say(R.string.zs_edge); else if (status != null) status.setText(""); });
        } catch (Throwable t) {
            Log.w(TAG, "shift step failed: " + t);
            ui(() -> say(R.string.zs_refused));
        } finally {
            lastStepAt = SystemClock.uptimeMillis();
            inFlight = false;
        }
    }

    /** 120(0), 18(false,2) if sent, 573(2,true) if paused, 158(0) if moved, 194(0); marker cleared on success. */
    private static void close(Context app, GmpfClient g, Gmpf2Client g2, boolean prepared, boolean akPaused, boolean moved) {
        boolean ok = true;
        try { Log.i(TAG, "120(0) -> " + g.zoomSessionFinish()); } catch (Throwable t) { ok = false; Log.w(TAG, "120: " + t); }
        if (prepared) {
            try { Log.i(TAG, "18(false,2) -> " + g2.setKstPrepareForZoom(false)); } catch (Throwable t) { ok = false; Log.w(TAG, "18: " + t); }
        }
        if (akPaused) {
            try { Log.i(TAG, "573(2,true) -> " + g.setRealtimeAkTemporarily(true)); } catch (Throwable t) { Log.w(TAG, "573: " + t); }
        }
        if (moved) {
            try { Log.i(TAG, "158(0) -> " + g.regenerateBootLogo()); } catch (Throwable t) { Log.w(TAG, "158: " + t); }
        }
        try { Log.i(TAG, "194(0) -> " + g2.setAdjustScreenOpen(false)); } catch (Throwable t) { Log.w(TAG, "194: " + t); }
        if (ok) setMarker(app, false);
    }

    // =================================================================== keys and window (main thread)

    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (ev.getAction() == KeyEvent.ACTION_UP && state == READY && digital) {
                shiftMode = !shiftMode;
                render();
            }
            return true;
        }
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!up && !down && !left && !right) return false;
        if (ev.getAction() != KeyEvent.ACTION_DOWN || state != READY || inFlight
                || SystemClock.uptimeMillis() - lastStepAt < MIN_STEP_GAP_MS) return true;
        inFlight = true;
        boolean queued;
        if (shiftMode) {
            final GmpfClient.ShiftDir d = up ? GmpfClient.ShiftDir.UP : down ? GmpfClient.ShiftDir.DOWN
                    : left ? GmpfClient.ShiftDir.LEFT : GmpfClient.ShiftDir.RIGHT;
            queued = Hal.run((g, g2) -> shiftStep(g, g2, d));
        } else {
            final boolean smaller = left || down;
            queued = Hal.run((g, g2) -> sizeStep(g, g2, smaller));
        }
        if (!queued) inFlight = false;
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
            if (session.compareAndSet(true, false)) close(app, g, g2, prepared, akPaused, moved);
        })) {
            Log.w(TAG, "close not queued: left to the connect hook");
        }
        if (backExit && onBackExit != null) Ui.main().post(onBackExit);
    }

    private void fail(String why) {
        Log.w(TAG, "unavailable: " + why);
        state = FAILED;
        Notify.show(app, R.string.zs_unavailable);
        dismiss();
    }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        return EdgeGrid.fullscreen(c, TAG);
    }

    @Override
    protected View onCreateView(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.addView(new EdgeGrid(c), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = EdgeGrid.card(c);
        card.addView(EdgeGrid.text(c, c.getString(R.string.zs_title), 40, Theme.TEXT));
        mode = EdgeGrid.text(c, "", 34, Theme.TEXT);
        card.addView(mode);
        value = EdgeGrid.text(c, c.getString(R.string.zoom_wait), 52, Theme.TEXT);
        card.addView(value);
        hint = EdgeGrid.text(c, "", 28, Theme.TEXT_DIM);
        card.addView(hint);
        status = EdgeGrid.text(c, "", 28, Theme.TEXT_DIM);
        card.addView(status);
        root.addView(card, new FrameLayout.LayoutParams(Theme.px(c, 900), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return root;
    }

    /** Size N of M: M = the full picture, 1 = the smallest. */
    private void render() {
        if (value == null || state != READY) return;
        mode.setText(app.getString(shiftMode ? R.string.zs_mode_shift : R.string.zs_mode_size));
        value.setText(shiftMode ? "" : app.getString(R.string.zoom_position, hi - cur + 1, hi - lo + 1));
        value.setVisibility(shiftMode ? View.GONE : View.VISIBLE);
        hint.setText(app.getString(shiftMode ? (digital ? R.string.zs_hint_shift : R.string.zs_hint_shift_only)
                : R.string.zs_hint_size));
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

    private static void setMarker(Context app, boolean on) {
        try { prefs(app).edit().putBoolean(K_OPEN, on).commit(); } catch (Throwable t) { Log.w(TAG, "marker: " + t); }
    }

    /** Grey grid with a white line on the outermost pixels (the picture edge), and the shared card pieces. */
    static final class EdgeGrid extends View {
        private static final int BG = 0xFF8A8A8A, LINE = 0xFFE6E6E6;
        private final Paint line = new Paint(), edge = new Paint();

        EdgeGrid(Context c) {
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

        static WindowManager.LayoutParams fullscreen(Context c, String tag) {
            WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
            try {
                android.graphics.Point real = new android.graphics.Point();
                c.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(real);
                if (real.x > 0 && real.y > 0) { lp.width = real.x; lp.height = real.y; }
            } catch (Throwable t) {
                Log.w(tag, "real display size: " + t);
            }
            lp.x = 0;
            lp.y = 0;
            lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            return lp;
        }

        static LinearLayout card(Context c) {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = Theme.px(c, 36);
            card.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.PANEL_BG);
            bg.setCornerRadius(Theme.px(c, 24));
            card.setBackground(bg);
            return card;
        }

        static TextView text(Context c, CharSequence s, float size, int color) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextColor(color);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Theme.px(c, 6), 0, Theme.px(c, 6));
            return t;
        }
    }
}
