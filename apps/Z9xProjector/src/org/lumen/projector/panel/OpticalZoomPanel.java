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
 * Manual OPTICAL zoom (Z9X zoom lens), stock V6.15.62 ZoomStateView / ZoomUtils / KeyStoneManager logic. A full-screen
 * overlay like the manual keystone (grey grid, a white line on the outermost pixels = the picture edge): RIGHT / UP =
 * bigger, LEFT / DOWN = smaller (held: at most one move per 220 ms), MENU = zoom reset, BACK = done.
 * Lumen OS 1.0.8: OK switches to the picture position (141 shift, see {@link #shiftMode}) and back.
 *
 * Positions as the stock dial (Lumen OS 1.0.8, zoom audit F1 / F3, docs/fit_screen/Z9X_ZOOM_AUDIT.md; owner O1
 * 2026-10-30; the same model as the HB fork, device-tested there 2026-10-29, SURVEY_hb-t5b Z1): progress 0 = the
 * biggest picture. limitTotal "limit" positions (153(12,10), stock ZoomStateView.getLimitStep :606-609; 1 expected on
 * the Z9X, zoom.ini zoomLimitStepEn = 1, zoomMotorLimitStep = 129) beyond optical index 0, then the optical indexes
 * 0..max (117, 0..12): 14 positions, "Size N of 14".
 *   progress = limitTotal + IGmpf2 13 (0 or -1..-limitTotal) while 116 = 0, else 116 + limitTotal (initZoom :509-510).
 * One press (ZoomStateView.setZoom :685-694 smaller, :846-853 bigger):
 *   smaller: progress < limitTotal -> IGmpf2 14(13 + 1), else 119(116 + 1) (KeyStoneManager.zoomControl(0, 10));
 *   bigger: progress <= limitTotal -> IGmpf2 14(13 - 1), else 119(116 - 1) (zoomControl(1, 10)).
 * Every move is one stock position, one motor unit (119 = 160 motor steps, 14 = to / from the PI sensor); both calls
 * block until the motor stopped, so 116 / 13 read right after a move are final and are shown at once. The clients
 * refuse a 14 or 119 that is not the neighbour of a fresh read (GmpfClient.checkZoomLimitStep / setOpticalZoomStep).
 * Session: IGmpf2 18(true, 2) + 121 zoomStart(0) on open, 120 zoomFinish(0) + 18(false, 2) on close.
 * Autofocus on close (2026-10-31, stock-verified): only when the OPTICAL step (116) read at close differs from the
 * one read at open, as stock ZoomStateView.release / isZoomFocus (Z9X jadx ZoomStateView.java:1228-1236, the same in
 * HB newsettings :1389-1418): isOpticalCurStep is 116 taken in initZoom (:466), compared with a fresh 116 on close,
 * and when they differ zoom_direct_trigger_af (default true) starts the autofocus at once; there is no user switch.
 * The limit position (IGmpf2 13) does not count: Size 1 <-> 2 of 14 leaves 116 at 0, so no AF (stock). A MENU reset
 * that brings the lens back to the same 116 starts no AF either (the old resetMoved flag is gone). If either 116 read
 * fails, no AF.
 * Keys as stock ZoomStateView (zoom audit F5): at most one move per 220 ms for EVERY key down, and every arrow key up
 * drops the queued moves (stock handler.removeCallbacksAndMessages).
 * Zoom reset (owner decision 2026-10-30, zoom only): MENU (on release) sends 153(5,10), the lens home, which leaves it
 * IN the limit position (motor -129, the vendor's m_zoomInLimitPos 1): read back it shows "Size 14 of 14", and the
 * next "smaller" is one IGmpf2 14(0) as stock. On the Z9X zoom_and_move screen stock MENU runs the FULL resetKeyStone
 * (setIChipReset, 181(50), 153(5,10); ZoomMoveView.java:576-580); Lumen sends only its zoom part, by the owner
 * decision (zoom audit F6). The full reset is the quick panel's "Restore default picture".
 *
 * Removed in Lumen OS 1.0.8 (zoom audit F4), kept here as the record of the old code path: up to the 1.0.8 test
 * builds this panel had no limit position (13 positions, IGmpf2 14 was banned) and treated the lens after an auto
 * keystone or 153(5,10) as a "half-step mode" (logs/z9x_zoom_103/live_full2.txt, live_full3.txt: each 119 while
 * m_zoomInLimitPos was 1 ran the vendor's limit error path, 80-step moves, a counter reset on the 2nd bigger move).
 * It kept MODE_UNKNOWN / HALF / GOOD per boot in prefs (vendor_mode, vendor_mode_boot), ran a resync from index 0
 * (4 x smaller, 2 x bigger, 1 x smaller, then the smallest position capped off, cap_max), refused a 2nd bigger move in
 * a row, sent 153(5,10) on "bigger" at index 0 after a resync, and could put a saved index back after an auto
 * keystone (user_index, RESTORE_AFTER_AK, off since F7). The vendor log shows all of that is the limit position
 * used the wrong way; stock leaves it with one IGmpf2 14(0). The old prefs keys are no longer read.
 * Kept for one release as a log-and-stop safety (F4): a bigger move whose read-back jumps more than one position.
 */
public final class OpticalZoomPanel extends Panel {
    static final String TAG = "Z9xZoom";

    private static final String PREFS = "z9x_zoom";
    private static final String K_OPEN = "session_open";

    private static final long MIN_STEP_GAP_MS = 220;     // at most one move per 220 ms, every key down (stock fastClickTime)
    private static final int MAX_PENDING = 2;            // presses waiting behind the running move
    private static final long AUTO_HIDE_MS = 60_000;
    private static final int MAX_POSITIONS = 64;         // sanity bound for the 117 optical maximum (the Z9X says 12)
    private static final int MAX_LIMIT = 4;              // sanity bound for 153(12,10) (1 expected)
    private static final long RECOVER_DELAY_MS = 6_000;

    private static final int WAIT = 0, READY = 1, FAILED = 2;
    /** BACK exit with a close AF: the quick panel comes back at the latest this long after the AF request (no card). */
    private static final long AF_START_WAIT_MS = 5_000;

    private static OpticalZoomPanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private volatile int state = WAIT;
    private volatile boolean closed, backExit;
    private long lastKeyAt;                              // main thread
    /** Queued presses: > 0 smaller, < 0 bigger. Main thread adds, HAL thread takes. */
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean();
    /** true between 121 and our 120: whoever flips it to false sends the close (enter's end or onDismissed). */
    private final java.util.concurrent.atomic.AtomicBoolean session = new java.util.concurrent.atomic.AtomicBoolean();
    // HAL thread writes, UI reads
    private volatile int max = -1, limitTotal = 0, progress = -1, startProgress = -1;
    /** HAL thread: 116 optical step read at open (stock isOpticalCurStep); -1 = unknown, no AF on close. */
    private int startOptical = -1;
    private volatile boolean resetting;                  // MENU zoom reset queued or running (main sets, HAL clears)
    private TextView value, status, hint;
    private SizeBar bar;
    /**
     * Lumen OS 1.0.8 (parity keystone item 10): OK switches the arrows between size (the lens) and position (the
     * picture shift of stock zoom_and_move, PtzViewModel.java:215-262: 141 translationKst(dir, true) as a check,
     * then 141(dir, false) when it answers 0; real-time keystone paused with 573(2,false) meanwhile). The vendor
     * moves the picture inside the panel, so it moves only while the picture is smaller than the panel (after a
     * zoom in or a keystone). No digital zoom (118): G0082 has none. Main thread writes shiftMode.
     */
    private volatile boolean shiftMode, shiftInFlight;
    // HAL thread: a shift moved the picture (158 at the end) / we paused real-time keystone (573(2,true) owed)
    private boolean shifted, shiftAkPaused;
    private long lastShiftAt;

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

    /**
     * HalController.dispatchEvent, main thread: an auto keystone started (106) or XGIMI reported a failed walk back
     * (303). The AK walks the lens home into the limit position (zoomReset:1917). Stock keeps no state: the next open
     * reads 116 and IGmpf2 13 again. Log only.
     */
    public static void onVendorZoomReset(Context ctx, int type) {
        Log.i(TAG, "event " + type + ": lens moved by XGIMI (nothing kept, 116 / 13 are read on open)");
    }

    /**
     * QuickPanelController.resetKeystone, HAL thread, right after its 153(5,10) (stock resetKeyStone): the lens is in
     * the limit position, the next open shows "Size 14 of 14". Nothing kept since 1.0.8 (no saved zoom, no mode). Log only.
     */
    public static void onLensHome(Context ctx) {
        Log.i(TAG, "keystone reset walked the lens home (limit position; nothing kept)");
    }

    /**
     * HalController.dispatchEvent, main thread: the AK report (event 116 JSON). Stock does not put a user zoom back
     * after an AK (zoom audit F7), and "fit to my screen size" owns the size when it is used. Log only.
     */
    public static void onAkReport(Context ctx, String json) {
        boolean fit = json != null && json.contains("\"lckst_curtain\"");
        Log.i(TAG, "AK report: zoom left as XGIMI set it (stock), screen fit=" + fit);
    }

    // =================================================================== HAL thread

    private void enter(GmpfClient g, Gmpf2Client g2) throws Exception {
        if (closed) return;
        setMarker(app, true);
        try {
            session.set(true);
            Log.i(TAG, "enter: 18(true,2) -> " + g2.setKstPrepareForZoom(true) + ", 121(0) -> " + g.zoomSessionStart());
            int[] range = g.getZoomStepRange();
            int lim = g.getZoomLimitTotal();
            Log.i(TAG, "117 -> ret " + range[0] + " optical " + range[1] + ".." + range[2] + " digital " + range[3]
                    + ".." + range[4] + "; 153(12,10) limit positions " + lim);
            if (range[1] != 0 || range[2] < 1 || range[2] > MAX_POSITIONS) {
                throw new IllegalStateException("unexpected zoom range");
            }
            max = range[2];
            if (lim < 0 || lim > MAX_LIMIT) Log.w(TAG, "153(12,10) = " + lim + " not plausible: no limit position used");
            limitTotal = lim >= 0 && lim <= MAX_LIMIT ? lim : 0;
            progress = startProgress = readProgress(g, g2);
            startOptical = readOptical(g);
            Log.i(TAG, "enter: position " + progress + " of 0.." + (limitTotal + max) + ", 116 optical " + startOptical);
            state = READY;
            ui(this::render);
            if (closed && session.compareAndSet(true, false)) close(app, g, g2);   // dismissed while entering
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (session.compareAndSet(true, false)) close(app, g, g2); else setMarker(app, false);
            ui(() -> fail("enter"));
        }
    }

    /** 116 then IGmpf2 13 (both fresh for the client guards), as the stock dial position (initZoom). */
    private int readProgress(GmpfClient g, Gmpf2Client g2) throws Exception {
        int cur = g.getZoomStep()[1];
        int lim = limitTotal > 0 ? g2.getZoomLimitStep()[1] : 0;
        if (cur < 0 || cur > max || lim > 0 || lim < -limitTotal) {
            throw new IllegalStateException("116 optical " + cur + ", 13 limit " + lim);
        }
        if (cur != 0 && lim != 0) throw new IllegalStateException("limit " + lim + " away from index 0 (" + cur + ")");
        return cur == 0 ? limitTotal + lim : cur + limitTotal;
    }

    /** 116 optical step alone (stock KeyStoneManager.getCurOpticalCurZoomStep), -1 when the read fails. */
    private int readOptical(GmpfClient g) {
        try {
            int cur = g.getZoomStep()[1];
            return cur >= 0 && cur <= max ? cur : -1;
        } catch (Throwable t) {
            Log.w(TAG, "116 read: " + t);
            return -1;
        }
    }

    /** Runs the queued presses one after another (each move blocks until the motor stopped). */
    private void drain(GmpfClient g, Gmpf2Client g2) {
        do {
            int p;
            while ((p = pending.get()) != 0 && !closed && state == READY) {
                int dir = Integer.signum(p);
                if (pending.compareAndSet(p, p - dir)) step(g, g2, dir);
            }
            draining.set(false);
        } while (pending.get() != 0 && !closed && state == READY && draining.compareAndSet(false, true));
    }

    /** One press. dir +1 = smaller, -1 = bigger (stock setZoom). */
    private void step(GmpfClient g, Gmpf2Client g2, int dir) {
        try {
            int now = readProgress(g, g2);          // fresh 116 + 13 for the guards
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
                r = g2.setOpticalZoomLimitStep(lim + dir);
                what = "IGmpf2 14(" + (lim + dir) + ")";
            } else {
                int cur = now - limitTotal;
                r = g.setOpticalZoomStep(cur + dir);
                what = "119(" + (cur + dir) + ")";
            }
            int after = readProgress(g, g2);
            Log.i(TAG, what + " -> " + r + " in " + (SystemClock.uptimeMillis() - t0) + " ms, position " + now + " -> " + after);
            if (Math.abs(after - now) > 1) {
                // F4 safety for one release: a move is one position; a jump means XGIMI's counter is not ours
                Log.e(TAG, "position jumped " + now + " -> " + after + " on one move: stopped");
                pending.set(0);
                progress = startProgress = after;
                startOptical = -1;                          // no autofocus on a lens we do not know
                state = FAILED;
                ui(() -> { say(R.string.zoom_refused); dismiss(); });
                return;
            }
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
     * MENU: 153(5,10) (the lens home, the biggest picture, in the limit position), then 116 / 13 for the counter
     * ("Size 14 of 14" expected; zoom audit F6). Inside the 121 session, holding {@code draining} so no move runs at
     * the same time.
     */
    private void resetZoom(GmpfClient g, Gmpf2Client g2) {
        try {
            if (closed || state != READY) return;
            long t0 = SystemClock.uptimeMillis();
            int r = g.zoomHome();
            int after = readProgress(g, g2);
            Log.i(TAG, "MENU zoom reset: 153(5,10) -> " + r + " in " + (SystemClock.uptimeMillis() - t0)
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
        if (code == KeyEvent.KEYCODE_MENU) {
            // zoom reset on release; not while lens moves are queued or running
            if (ev.getAction() == KeyEvent.ACTION_UP && !ev.isCanceled() && state == READY && !resetting
                    && pending.get() == 0 && draining.compareAndSet(false, true)) {
                resetting = true;
                if (shiftMode) { shiftMode = false; render(); }
                say(R.string.zoom_wait);
                Log.i(TAG, "key MENU: zoom reset");
                if (!Hal.run(this::resetZoom)) {
                    resetting = false;
                    draining.set(false);
                    say(R.string.zoom_refused);
                }
            }
            return true;
        }
        if (resetting) return true;                      // the lens is walking home
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            // OK: size <-> position (1.0.8); not while lens moves are queued or running
            if (ev.getAction() == KeyEvent.ACTION_UP && state == READY && pending.get() == 0 && !draining.get()) {
                shiftMode = !shiftMode;
                Log.i(TAG, "OK: " + (shiftMode ? "position (shift)" : "size (zoom)"));
                render();
            }
            return true;
        }
        if (shiftMode) return onShiftKey(ev, code);
        boolean bigger = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP;
        boolean smaller = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!bigger && !smaller) return false;
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            pending.set(0);                              // stock: any key up drops the queued moves (audit F5)
            return true;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN || state != READY) return true;
        long now = SystemClock.uptimeMillis();
        if (now - lastKeyAt < MIN_STEP_GAP_MS) return true;   // every key down, as stock fastClickTime (audit F5)
        lastKeyAt = now;
        int dir = bigger ? -1 : +1;
        int p = pending.updateAndGet(x -> Integer.signum(x) == -dir ? dir
                : Math.max(-MAX_PENDING, Math.min(MAX_PENDING, x + dir)));
        Log.i(TAG, "key " + (bigger ? "bigger" : "smaller") + " repeat " + ev.getRepeatCount() + ", queued " + p);
        if (draining.compareAndSet(false, true) && !Hal.run(this::drain)) draining.set(false);
        return true;
    }

    /** Position mode: one 141 step per press (a held key repeats every MIN_STEP_GAP_MS at most). Main thread. */
    private boolean onShiftKey(KeyEvent ev, int code) {
        final GmpfClient.ShiftDir d = code == KeyEvent.KEYCODE_DPAD_UP ? GmpfClient.ShiftDir.UP
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? GmpfClient.ShiftDir.DOWN
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? GmpfClient.ShiftDir.LEFT
                : code == KeyEvent.KEYCODE_DPAD_RIGHT ? GmpfClient.ShiftDir.RIGHT : null;
        if (d == null) return false;
        if (ev.getAction() != KeyEvent.ACTION_DOWN || state != READY || shiftInFlight
                || SystemClock.uptimeMillis() - lastShiftAt < MIN_STEP_GAP_MS) return true;
        shiftInFlight = true;
        if (!Hal.run((g, g2) -> shiftStep(g, d))) shiftInFlight = false;
        return true;
    }

    /** HAL thread: 141(dir, true); when it answers 0 (possible), 141(dir, false). -1 = at the edge. */
    private void shiftStep(GmpfClient g, GmpfClient.ShiftDir d) {
        try {
            if (state != READY || closed) return;
            if (!shiftAkPaused && !shifted) shiftAkPaused = ManualKeystonePanel.pauseRealtime(app, g, "zoom shift");
            int check = g.shiftPicture(d, true);
            int r = check == 0 ? g.shiftPicture(d, false) : check;
            Log.i(TAG, "141(" + d + ") check " + check + " -> " + r);
            if (check == 0 && r != -1) shifted = true;
            ui(() -> { if (check != 0) say(R.string.zs_edge); else if (status != null) status.setText(""); });
        } catch (Throwable t) {
            Log.w(TAG, "shift step failed: " + t);
            ui(() -> say(R.string.zs_refused));
        } finally {
            lastShiftAt = SystemClock.uptimeMillis();
            shiftInFlight = false;
        }
    }

    /** HAL thread, at the close: 158(0) after a shift, 573(2,true) if the shift paused real-time keystone. */
    private void endShift(GmpfClient g) {
        if (shifted) {
            try { Log.i(TAG, "158(0) after a shift -> " + g.regenerateBootLogo()); } catch (Throwable t) { Log.w(TAG, "158: " + t); }
        }
        if (shiftAkPaused) {
            shiftAkPaused = false;
            ManualKeystonePanel.resumeRealtime(app, g, "zoom shift");
        }
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
                // stock isZoomFocus: a fresh 116 against the one read at open, before the session closes
                int endOptical = startOptical >= 0 ? readOptical(g) : -1;
                boolean opticalChanged = startOptical >= 0 && endOptical >= 0 && endOptical != startOptical;
                Log.i(TAG, "close: 116 optical " + startOptical + " -> " + endOptical
                        + (opticalChanged ? ": autofocus" : ": no autofocus"));
                endShift(g);
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
        hint = text(c, c.getString(R.string.zs_hint_size), 30, Theme.TEXT_DIM);
        card.addView(hint);
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

    /** Bar (left small, right big) and "Size N of M" over all stock positions (the limit one included, M = 14). */
    private void render() {
        if (value == null || state != READY || max < 0 || progress < 0) return;
        if (hint != null) hint.setText(app.getString(shiftMode ? R.string.zs_hint_shift : R.string.zs_hint_size));
        int last = limitTotal + max;
        int p = Math.min(progress, last);
        value.setText(app.getString(R.string.zoom_position, last - p + 1, last + 1));
        bar.set(last - p, last);
        if (!resetting) status.setText("");
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
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pos = -1, last = 0;

        SizeBar(Context c) {
            super(c);
            on.setColor(Theme.TEXT);
            off.setColor(0x40FFFFFF);
        }

        void set(int position, int lastPos) {
            pos = position;
            last = lastPos;
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
                c.drawRoundRect(x, h - sh, x + seg, h, r, r, i <= pos ? on : off);
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
