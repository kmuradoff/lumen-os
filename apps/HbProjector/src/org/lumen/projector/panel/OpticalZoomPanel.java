package org.lumen.projector.panel;

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

/**
 * Manual OPTICAL zoom, HB fork (hb-t6) of the Z9X file: stock G0130 newsettings ZoomStateView / KeyStoneManager.
 * A full-screen overlay (grey grid, a white line on the picture edge): RIGHT / UP = bigger, LEFT / DOWN = smaller
 * (held: at most one move per 220 ms, stock fastClickTime), MENU = zoom reset, BACK = done.
 *
 * Positions as the stock dial (progress 0 = the biggest picture): limitTotal "limit" positions (63(12,10), 1 on the
 * HB) beyond optical index 0, then the optical indexes 0..max (97, 0..12): 14 positions on the HB.
 *   progress = limitTotal + 190 (190 = 0 or -1..-limitTotal) while 98 = 0, else 98 + limitTotal (initZoom).
 * One press (setZoom$lambda$2 / $4):
 *   smaller: progress < limitTotal -> 189(190 + 1), else 95(98 + 1) (KeyStoneManager.zoomControl(0, 10));
 *   bigger: progress <= limitTotal -> 189(190 - 1), else 95(98 - 1) (zoomControl(1, 10)).
 * Session: 185(true, 2) + 93 on open, 94 + 185(false, 2) on close.
 * Autofocus on close (2026-10-31, stock-verified): only when the OPTICAL step (98) read at close differs from the one
 * read at open, as stock ZoomStateView.release / isZoomFocus (HB newsettings ZoomStateView.java:1389-1418;
 * isOpticalCurStep is 98 taken in initZoom :482): when they differ zoom_direct_trigger_af (default true) starts the
 * autofocus at once; there is no user switch (Z9X stock the same). The limit position (190) does not count:
 * Size 1 <-> 2 of 14 leaves 98 at 0, so no AF. If either 98 read fails, no AF. On the HB 95 and 189 block until the motor stopped (device log
 * 2026-10-29: 95 from 12 down to 0 = 160 motor steps each, 189(-1) / 189(0) = to / from the PI sensor), so 98 and
 * 190 read right after a move are final and are shown at once (stock re-reads them 1 s after the last key).
 * The Z9X half-step guards are not needed here: the Z9X "half-step mode" is this vendor's limit position, which
 * stock reaches only through 189 and leaves through 189.
 * Zoom reset (decision 2026-10-30; since hb-t8b the quick panel's "Восстановление экрана" sends 63(5,10) too, as
 * stock resetKeyStone, and the manual keystone reset leaves the lens alone): MENU (82,
 * on release) sends 63(5,10), the lens home = the biggest picture, as stock ZoomOnlyView.dispatchKeyEvent
 * (keyCode 82 UP -> KeyStoneManager.resetOpticalZoomAndZoom, ZoomOnlyView.java:416-418) and the old keystone reset
 * did; the setIChipReset / 11(50) of that stock reset are not sent here (keystone, 11 banned). 98 / 190 are read
 * again for the counter. Nothing to forget: the HB keeps no saved zoom position (stock keeps none).
 */
public final class OpticalZoomPanel extends Panel {
    static final String TAG = "Z9xZoom";

    private static final String PREFS = "z9x_zoom";
    private static final String K_OPEN = "session_open";

    private static final long MIN_STEP_GAP_MS = 220;     // stock ZoomStateView.dispatchKeyEvent fastClickTime
    private static final int MAX_PENDING = 2;            // presses waiting behind the running move
    private static final long AUTO_HIDE_MS = 60_000;
    private static final int MAX_POSITIONS = 64;         // sanity bound for the 97 optical maximum
    private static final int MAX_LIMIT = 4;              // sanity bound for 63(12,10)
    private static final long RECOVER_DELAY_MS = 6_000;

    private static final int WAIT = 0, READY = 1, FAILED = 2;
    /** BACK exit with a close AF: the quick panel comes back at the latest this long after the AF request (no card). */
    private static final long AF_START_WAIT_MS = 5_000;

    private static OpticalZoomPanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private volatile int state = WAIT;
    private volatile boolean closed, backExit;
    /** MENU zoom reset queued or running (arrows wait); main thread sets, HAL thread clears. */
    private volatile boolean resetting;
    private long lastKeyAt;                              // main thread
    /** Queued presses: > 0 smaller, < 0 bigger. Main thread adds, HAL thread takes. */
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    /** true between 93 and our 94: whoever flips it to false sends the close (enter's end or onDismissed). */
    private final java.util.concurrent.atomic.AtomicBoolean session = new java.util.concurrent.atomic.AtomicBoolean();
    // HAL thread writes, UI reads
    private volatile int max = -1, limitTotal = 0, progress = -1, startProgress = -1;
    /** HAL thread: 98 optical step read at open (stock isOpticalCurStep); -1 = unknown, no AF on close. */
    private int startOptical = -1;
    private TextView value, status;
    private SizeBar bar;

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

    /** HalController / keystone reset: an AK or a reset moved the lens. Stock keeps no state: log only. */
    public static void onVendorZoomReset(Context ctx, int type) {
        Log.i(TAG, "event " + type + ": lens moved by XGIMI (nothing kept, stock reads 98 / 190 on open)");
    }

    /** HalController: the AK report (event 116). Stock does not put a user zoom back after an AK: log only. */
    public static void onAkReport(Context ctx, String json) {
        Log.i(TAG, "AK report: zoom left as XGIMI set it (stock)");
    }

    // =================================================================== HAL thread

    private void enter(GmpfClient g, Gmpf2Client g2) throws Exception {
        if (closed) return;
        setMarker(app, true);
        try {
            session.set(true);
            Log.i(TAG, "enter: 185(true,2) -> " + g2.setKstPrepareForZoom(true) + ", 93(0) -> " + g.zoomSessionStart());
            int[] range = g.getZoomStepRange();
            int lim = g.getZoomLimitTotal();
            Log.i(TAG, "97 -> ret " + range[0] + " optical " + range[1] + ".." + range[2] + " digital " + range[3]
                    + ".." + range[4] + "; 63(12,10) limit positions " + lim);
            if (range[1] != 0 || range[2] < 1 || range[2] > MAX_POSITIONS) {
                throw new IllegalStateException("unexpected zoom range");
            }
            max = range[2];
            limitTotal = lim >= 0 && lim <= MAX_LIMIT ? lim : 0;
            progress = startProgress = readProgress(g);
            startOptical = readOptical(g);
            Log.i(TAG, "enter: position " + progress + " of 0.." + (limitTotal + max) + ", 98 optical " + startOptical);
            state = READY;
            ui(this::render);
            if (closed && session.compareAndSet(true, false)) close(app, g, g2);   // dismissed while entering
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (session.compareAndSet(true, false)) close(app, g, g2); else setMarker(app, false);
            ui(() -> fail("enter"));
        }
    }

    /** 98 then 190 (both fresh for the guards), as the stock dial position. */
    private int readProgress(GmpfClient g) throws Exception {
        int cur = g.getZoomStep()[1];
        int lim = limitTotal > 0 ? g.getZoomLimitStep()[1] : 0;
        if (cur < 0 || cur > max || lim > 0 || lim < -limitTotal) {
            throw new IllegalStateException("98 optical " + cur + ", 190 limit " + lim);
        }
        if (cur != 0 && lim != 0) throw new IllegalStateException("limit " + lim + " away from index 0 (" + cur + ")");
        return cur == 0 ? limitTotal + lim : cur + limitTotal;
    }

    /** 98 optical step alone (stock KeyStoneManager.getCurOpticalCurZoomStep), -1 when the read fails. */
    private int readOptical(GmpfClient g) {
        try {
            int cur = g.getZoomStep()[1];
            return cur >= 0 && cur <= max ? cur : -1;
        } catch (Throwable t) {
            Log.w(TAG, "98 read: " + t);
            return -1;
        }
    }

    /** Runs the queued presses one after another (each move blocks until the motor stopped). */
    private void drain(GmpfClient g) {
        do {
            int p;
            while ((p = pending.get()) != 0 && !closed && state == READY) {
                int dir = Integer.signum(p);
                if (pending.compareAndSet(p, p - dir)) step(g, dir);
            }
            draining.set(false);
        } while (pending.get() != 0 && !closed && state == READY && draining.compareAndSet(false, true));
    }

    /** One press. dir +1 = smaller, -1 = bigger (stock setZoom). */
    private void step(GmpfClient g, int dir) {
        try {
            int now = readProgress(g);              // fresh 98 + 190 for the guards
            int last = limitTotal + max;
            if ((dir < 0 && now <= 0) || (dir > 0 && now >= last)) {
                pending.set(0);
                progress = now;
                ui(() -> { render(); say(dir < 0 ? R.string.zoom_limit_big : R.string.zoom_limit_small); });
                return;
            }
            long t0 = SystemClock.uptimeMillis();
            int r;
            String what;
            boolean limitZone = dir > 0 ? now < limitTotal : now <= limitTotal;
            if (limitZone) {
                int lim = now - limitTotal;          // 0 or -1..-limitTotal
                r = g.setOpticalZoomLimitStep(lim + dir);
                what = "189(" + (lim + dir) + ")";
            } else {
                int cur = now - limitTotal;
                r = g.setOpticalZoomStep(cur + dir);
                what = "95(" + (cur + dir) + ")";
            }
            int after = readProgress(g);
            Log.i(TAG, what + " -> " + r + " in " + (SystemClock.uptimeMillis() - t0) + " ms, position " + now + " -> " + after);
            progress = after;
            if (r != 0) { pending.set(0); ui(() -> { render(); say(R.string.zoom_refused); }); return; }
            ui(this::render);
        } catch (Throwable t) {
            Log.w(TAG, "step failed: " + t);
            pending.set(0);
            ui(() -> say(R.string.zoom_refused));
        }
    }

    /**
     * MENU: 63(5,10) (the lens home, the biggest picture), then 98 / 190 for the counter. Inside the 93 session.
     * Runs holding {@code draining}, so no move runs at the same time.
     */
    private void resetZoom(GmpfClient g) {
        try {
            if (closed || state != READY) return;
            long t0 = SystemClock.uptimeMillis();
            int r = g.zoomHome();
            int after = readProgress(g);
            Log.i(TAG, "MENU zoom reset: 63(5,10) -> " + r + " in " + (SystemClock.uptimeMillis() - t0)
                    + " ms, position " + progress + " -> " + after);
            progress = after;
            ui(() -> { render(); say(R.string.zoom_reset_done); });
        } catch (Throwable t) {
            Log.w(TAG, "zoom reset failed: " + t);
            ui(() -> say(R.string.zoom_refused));
        } finally {
            pending.set(0);
            resetting = false;
            draining.set(false);
        }
    }

    /** 94(0) + 185(false,2), marker cleared on success. HAL thread. */
    private static void close(Context app, GmpfClient g, Gmpf2Client g2) {
        boolean ok = true;
        try { Log.i(TAG, "94(0) -> " + g.zoomSessionFinish()); } catch (Throwable t) { ok = false; Log.w(TAG, "94: " + t); }
        try { Log.i(TAG, "185(false,2) -> " + g2.setKstPrepareForZoom(false)); } catch (Throwable t) { ok = false; Log.w(TAG, "185(false,2): " + t); }
        if (ok) setMarker(app, false);
    }

    // =================================================================== keys and window (main thread)

    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code == KeyEvent.KEYCODE_MENU) {
            // zoom reset on release (stock ZoomOnlyView: 82 UP); not while a move is queued or running
            if (ev.getAction() == KeyEvent.ACTION_UP && !ev.isCanceled() && state == READY && !resetting
                    && pending.get() == 0 && draining.compareAndSet(false, true)) {
                resetting = true;
                say(R.string.zoom_wait);
                Log.i(TAG, "key MENU: zoom reset");
                if (!Hal.run((g, g2) -> resetZoom(g))) { resetting = false; draining.set(false); say(R.string.zoom_refused); }
            }
            return true;
        }
        boolean bigger = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP;
        boolean smaller = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!bigger && !smaller) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;   // OK: nothing
        }
        if (resetting) return true;                      // the lens is walking home
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            pending.set(0);                              // stock: key up drops the queued moves
            return true;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN || state != READY) return true;
        long now = SystemClock.uptimeMillis();
        if (now - lastKeyAt < MIN_STEP_GAP_MS) return true;
        lastKeyAt = now;
        int dir = bigger ? -1 : +1;
        int p = pending.updateAndGet(x -> Integer.signum(x) == -dir ? dir
                : Math.max(-MAX_PENDING, Math.min(MAX_PENDING, x + dir)));
        Log.i(TAG, "key " + (bigger ? "bigger" : "smaller") + " repeat " + ev.getRepeatCount() + ", queued " + p);
        if (draining.compareAndSet(false, true) && !Hal.run((g, g2) -> drain(g))) draining.set(false);
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
        pending.set(0);
        if (sOpen == this) sOpen = null;
        final boolean back = backExit && onBackExit != null;
        // a session still entering is closed by the end of enter(); an open one here (queued behind the moves)
        boolean ready = state == READY || state == FAILED;
        boolean queued = ready && Hal.run((g, g2) -> {
            boolean af = false;
            try {
                // stock isZoomFocus: a fresh 98 against the one read at open, before the session closes
                int endOptical = startOptical >= 0 ? readOptical(g) : -1;
                boolean opticalChanged = startOptical >= 0 && endOptical >= 0 && endOptical != startOptical;
                Log.i(TAG, "close: 98 optical " + startOptical + " -> " + endOptical
                        + (opticalChanged ? ": autofocus" : ": no autofocus"));
                if (session.compareAndSet(true, false)) close(app, g, g2);
                af = opticalChanged;
            } finally {
                final boolean runAf = af;
                if (runAf || back) ui(() -> afterClose(runAf, back));
            }
        });
        if (ready && !queued) Log.w(TAG, "close not queued: left to the connect hook");
        if (back && !queued) Ui.main().post(onBackExit);
    }

    /**
     * Main thread, after the close went out. 2026-10-31 (HB video, 16:06:51): BACK after a lens move reopened the
     * quick panel, then the autofocus card of the stock close AF dismissed it for good. Now the AF goes first and
     * the quick panel (its row) comes back only when the AF card closed, or after AF_START_WAIT_MS when no card
     * came (AfCardOverlay.runAfterCard). Without an optical step change, or when the AF was refused, the panel comes
     * back now.
     */
    private void afterClose(boolean af, boolean back) {
        int r = af ? Hal.requestAutofocus(app) : -1;
        if (!back) return;
        if (af && r == Hal.REQ_OK) {
            Log.i(TAG, "BACK exit: quick panel after the AF card");
            Hal.runAfterAutofocus(onBackExit, AF_START_WAIT_MS);
        } else {
            onBackExit.run();
        }
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
        card.addView(text(c, c.getString(R.string.panel_optical_zoom_summary), 26, Theme.TEXT_DIM));
        bar = new SizeBar(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Theme.px(c, 760), Theme.px(c, 44));
        blp.topMargin = Theme.px(c, 8);
        blp.bottomMargin = Theme.px(c, 8);
        card.addView(bar, blp);
        value = text(c, c.getString(R.string.zoom_wait), 30, Theme.TEXT_DIM);
        card.addView(value);
        card.addView(text(c, c.getString(R.string.zoom_hint), 30, Theme.TEXT_DIM));
        card.addView(text(c, c.getString(R.string.zoom_reset_hint), 26, Theme.TEXT_DIM));
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

    /** Bar (left small, right big) and "Size N of M" over all stock positions (limit ones included). */
    private void render() {
        if (value == null || state != READY || max < 0 || progress < 0) return;
        int last = limitTotal + max;
        int p = Math.min(progress, last);
        value.setText(app.getString(R.string.zoom_position, last - p + 1, last + 1));
        bar.set(last - p, last, false);
        status.setText("");
    }

    private void say(int res) {
        if (status != null) status.setText(app.getString(res));
    }

    private static void ui(Runnable r) { Ui.main().post(r); }

    // =================================================================== persisted state

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

    /** One segment per lens position, filled up to the current one; left = smaller, right = bigger. */
    private static final class SizeBar extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG),
                mid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pos = -1, last = 0;
        private boolean half;

        SizeBar(Context c) {
            super(c);
            on.setColor(Theme.TEXT);
            off.setColor(0x40FFFFFF);
            mid.setColor(0x99FFFFFF);
        }

        /** {@code halfBelow}: the lens stopped between this position and the next smaller one. */
        void set(int position, int lastPos, boolean halfBelow) {
            pos = position;
            last = lastPos;
            half = halfBelow;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = last + 1;
            if (pos < 0 || n <= 0) return;
            float w = getWidth(), h = getHeight(), gap = Math.max(2f, w / n * 0.18f);
            float seg = (w - gap * (n - 1)) / n, r = Math.min(seg, h) / 4f;
            for (int i = 0; i < n; i++) {
                float x = i * (seg + gap);
                float sh = h * (0.45f + 0.55f * i / Math.max(1, n - 1));
                Paint p = i < pos ? on : i == pos ? (half ? mid : on) : off;
                c.drawRoundRect(x, h - sh, x + seg, h, r, r, p);
            }
        }
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
