package org.lumen.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.provider.Settings;
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
 * Manual OPTICAL zoom (Z9X zoom lens). A full-screen overlay like the manual keystone (grey grid, a white line
 * on the outermost pixels = the picture edge): RIGHT / UP = bigger, LEFT / DOWN = smaller (held: repeats), BACK = done.
 *
 * Stock calls (V6.15.62 ZoomStateView / KeyStoneManager.zoomControl(0|1, 10)): IGmpf2 18(true, 2), IGmpf 121
 * zoomStart(0), 117 getZoomStepRange (optical 0..12 on the Z9X = 13 positions, as the stock dial), 116 before
 * every move, 119 setOpticalZoomStep(116 + 1 smaller | 116 - 1 bigger), at the end 120 zoomFinish(0) and
 * 18(false, 2). Index 0 = the biggest picture.
 *
 * What XGIMI's controller really does (logs/z9x_zoom_103/live_full2.txt, live_full3.txt, 2026-10-14):
 *  - 119 is a RELATIVE move of one unit toward the target ("step = N, m_prevStep = M"), and it BLOCKS until the
 *    motor stopped; 116 read right after it is final. (1.0.3 polled 116 for 700 ms more and dropped keys.)
 *  - index = (motorStep - 80 + dirPos) / 160, 0..12 over 2121 motor steps.
 *  - Full-step mode: one 119 = 160 steps = one index (live_full3 02:21:34..41).
 *  - Half-step mode: after an auto keystone walks the lens to its PI sensor it leaves m_zoomInLimitPos = 1;
 *    then one 119 = 80 steps, the index changes every 2nd move, the first position needs 4 (02:13:49..59), and
 *    the 2nd BIGGER move in a row resets its counter to 0 wherever the lens is ("Reverse Limit Front Just Exit
 *    Pi", 02:14:03.843 harmless near the wide end, 02:21:29.808 at step ~1900 = lens lost). That reset is also
 *    what leaves half-step mode (m_zoomInLimitPos:[0], then 160 per move).
 * So: one 119 per press, presses queued (not dropped), and from index 0 in an unknown mode the first "smaller"
 * press runs the resync (4 x smaller, 2 x bigger, 1 x smaller) so the reset happens < 1 position from the wide
 * end; after a resync the smallest position is not used (the counter is ~110 steps behind the lens). A 2nd
 * bigger move in half-step mode farther out is refused. Every auto keystone (event 106) makes the mode unknown.
 * The user's last position is kept and put back after an auto keystone that re-homed the lens, unless
 * "Fit to the screen" is on or the AK was a screen fit (then the size is XGIMI's by design).
 */
public final class OpticalZoomPanel extends Panel {
    static final String TAG = "Z9xZoom";

    private static final String PREFS = "z9x_zoom";
    private static final String K_OPEN = "session_open";
    private static final String K_MODE = "vendor_mode", K_MODE_BOOT = "vendor_mode_boot", K_CAP = "cap_max";
    private static final String K_USER = "user_index";
    static final int MODE_UNKNOWN = 0, MODE_HALF = 1, MODE_GOOD = 2;

    private static final long MIN_STEP_GAP_MS = 220;     // a held key: at most one queued move per 220 ms (stock)
    private static final int MAX_PENDING = 2;            // presses waiting behind the running move
    private static final int RESYNC_MAX_MOVES = 5;       // half-step mode needs 4 moves for the first position
    private static final long AUTO_HIDE_MS = 60_000;
    private static final int MAX_POSITIONS = 64;         // sanity bound for opticalMax (the Z9X says 12)
    private static final long RECOVER_DELAY_MS = 6_000;
    /** Put the user's zoom back after an auto keystone that moved it (UNVERIFIED on the device: see onAkReport). */
    private static final boolean RESTORE_AFTER_AK = true;
    private static final long RESTORE_DELAY_MS = 2_000;

    private static final int WAIT = 0, READY = 1, FAILED = 2;

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
    // HAL thread
    private int max = -1, cur = -1, start = -1, mode = MODE_UNKNOWN, lastDir;
    private boolean changedLast;                         // the previous move changed 116 (mode detection)
    private volatile boolean half, busy;                 // lens between two positions / resync running (UI reads)
    private volatile int top = -1;                       // highest index we use (max, or max - 1 after a resync)
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

    /**
     * HalController.dispatchEvent, main thread: an auto keystone started (106) or XGIMI reported a failed walk
     * back (303). Every AK walks the lens to the PI sensor (zoomReset:1917, live_full3 02:22:27 / 02:22:58 / 02:23:20)
     * or at least runs zoomReset: XGIMI's mode is unknown again.
     */
    public static void onVendorZoomReset(Context ctx, int type) {
        Context app = ctx.getApplicationContext();
        Log.i(TAG, "event " + type + ": XGIMI zoom mode unknown again");
        saveMode(app, MODE_UNKNOWN);
    }

    /**
     * HalController.dispatchEvent, main thread: the AK report (event 116 JSON, trigger_type and the rest). A screen
     * fit (trigger "lckst_curtain" = 272(11) "Fit to screen now") owns the size: forget the user's zoom. Otherwise
     * put it back if the AK moved the lens. UNVERIFIED: whether 326 / power-on AK ever move the lens (326 at
     * index 12 did not, 02:21:00 and 02:21:10).
     */
    public static void onAkReport(Context ctx, String json) {
        final Context app = ctx.getApplicationContext();
        boolean fit = json != null && json.contains("\"lckst_curtain\"");
        int user = userIndex(app);
        Log.i(TAG, "AK report: screen fit=" + fit + ", user zoom index " + user);
        if (fit) { setUserIndex(app, -1); return; }
        if (!RESTORE_AFTER_AK || user <= 0) return;
        Hal.runDelayed((g, g2) -> restoreAfterAk(app, g, g2, user), RESTORE_DELAY_MS);
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
            top = topIndex(app, max);
            mode = loadMode(app);
            cur = start = step[1];
            Log.i(TAG, "enter: index " + cur + ", XGIMI mode " + modeName(mode) + ", top " + top);
            state = READY;
            ui(this::render);
            if (closed && session.compareAndSet(true, false)) close(app, g, g2);   // dismissed while entering
        } catch (Throwable t) {
            Log.w(TAG, "enter failed: " + t);
            if (session.compareAndSet(true, false)) close(app, g, g2); else setMarker(app, false);
            ui(() -> fail("enter"));
        }
    }

    /** Runs the queued presses one after another (each 119 blocks until the motor stopped). */
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

    /** One press. dir +1 = smaller, -1 = bigger. */
    private void step(GmpfClient g, int dir) {
        try {
            int now = g.getZoomStep()[1];
            if (now < 0 || now > max) throw new IllegalStateException("116 optical " + now);
            if (dir < 0 && now == 0 && mode == MODE_GOOD) {
                // after a resync index 0 is ~240 motor steps short of the real widest position (where auto keystone
                // leaves the lens, owner 2026-10-15): one more "bigger" walks the lens home as stock's MENU reset
                pending.set(0);
                long t0 = SystemClock.uptimeMillis();
                int r = g.zoomHome();
                int after = g.getZoomStep()[1];
                Log.i(TAG, "153(5,10) home walk -> " + r + " in " + (SystemClock.uptimeMillis() - t0) + " ms, 116 " + after);
                mode = MODE_HALF;                           // the vendor's limit flag is set again
                saveMode(app, mode);
                lastDir = -1;
                changedLast = false;
                half = false;
                cur = after;
                ui(() -> { render(); say(R.string.zoom_limit_big); });
                return;
            }
            if ((dir < 0 && now == 0) || (dir > 0 && now >= top)) {
                pending.set(0);
                cur = now;
                ui(() -> { render(); say(dir < 0 ? R.string.zoom_limit_big : R.string.zoom_limit_small); });
                return;
            }
            if (dir > 0 && now == 0 && mode != MODE_GOOD) {
                busy = true;
                ui(() -> say(R.string.zoom_wait));
                cur = resyncFromStart(app, g, max);
                mode = MODE_GOOD;
                top = topIndex(app, max);
                lastDir = +1;
                changedLast = true;
                half = false;
            } else if (dir < 0 && lastDir < 0 && mode != MODE_GOOD && now >= 2) {
                // the 2nd bigger move in a row would reset XGIMI's counter here (live_full3 02:21:29.808)
                Log.w(TAG, "guard: no 2nd bigger move in a row in " + modeName(mode) + " mode at index " + now);
                pending.set(0);
                ui(() -> say(R.string.zoom_refused));
                return;
            } else {
                int[] m = move119(g, now, dir);
                int after = m[1];
                if (dir < 0 && after < now - 1) {               // XGIMI reset its counter: the lens is lost
                    Log.e(TAG, "116 jumped " + now + " -> " + after + " on a bigger move: XGIMI counter reset");
                    saveMode(app, MODE_UNKNOWN);
                    setUserIndex(app, -1);
                    mode = MODE_UNKNOWN;
                    cur = start;                                // nothing to save, no autofocus
                    state = FAILED;
                    ui(() -> { say(R.string.zoom_refused); dismiss(); });
                    return;
                }
                boolean changed = after != now;
                if (m[0] == 0 && !changed) {
                    mode = MODE_HALF;                           // a move that did not reach the next index
                    saveMode(app, mode);
                } else if (changed && changedLast && lastDir == dir && mode != MODE_GOOD) {
                    mode = MODE_GOOD;                           // two index changes in a row: 160 per move
                    saveMode(app, mode);
                }
                half = m[0] == 0 && !changed;
                changedLast = changed;
                lastDir = dir;
                cur = after;
                if (m[0] != 0) { pending.set(0); ui(() -> { render(); say(R.string.zoom_refused); }); return; }
            }
            ui(this::render);
        } catch (Throwable t) {
            Log.w(TAG, "step failed: " + t);
            pending.set(0);
            ui(() -> say(R.string.zoom_refused));
        } finally {
            busy = false;
        }
    }

    /** One 119 and the 116 right after it (final: 119 returns when the motor stopped). {ret, index after}. */
    private static int[] move119(GmpfClient g, int from, int dir) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        int r = g.setOpticalZoomStep(from + dir);
        int after = g.getZoomStep()[1];
        Log.i(TAG, "119(" + (from + dir) + ") -> " + r + " in " + (SystemClock.uptimeMillis() - t0)
                + " ms, 116 " + from + " -> " + after);
        return new int[] {r, after};
    }

    /**
     * From index 0 (where an auto keystone leaves the lens) one step smaller, leaving XGIMI in full-step mode.
     * Inside a 121 session. Returns the index (1). The sequence is the one of live_full2 02:13:49..02:14:04.
     */
    private static int resyncFromStart(Context app, GmpfClient g, int max) throws Exception {
        int idx = 0, moves = 0;
        while (idx == 0 && moves < RESYNC_MAX_MOVES) {
            int[] m = move119(g, 0, +1);
            if (m[0] != 0) throw new IllegalStateException("resync: 119 refused");
            idx = m[1];
            moves++;
        }
        if (idx != 1) throw new IllegalStateException("resync: index " + idx + " after " + moves + " moves");
        if (moves == 1) {                                     // 160 per move already (live_full3 02:22:51)
            Log.i(TAG, "resync: full-step mode, nothing to fix");
            saveMode(app, MODE_GOOD);
            return 1;
        }
        Log.i(TAG, "resync: half-step mode (" + moves + " moves for one position): two bigger moves reset XGIMI here");
        int a = move119(g, 1, -1)[1];                         // 271 -> 191, still index 1
        if (a != 1) throw new IllegalStateException("resync: index " + a + " after the 1st bigger move");
        int b = move119(g, 1, -1)[1];                         // "Reverse Limit Front Just Exit Pi": counter 0
        if (b != 0) throw new IllegalStateException("resync: index " + b + " after the 2nd bigger move");
        saveMode(app, MODE_GOOD);
        prefs(app).edit().putInt(K_CAP, max - 1).apply();     // counter ~110 steps behind the lens: keep off the end
        int c = move119(g, 0, +1)[1];                         // the user's step: 160 (+80 backlash)
        if (c != 1) throw new IllegalStateException("resync: index " + c + " after the user's step");
        return 1;
    }

    /** After an AK that re-homed the lens (index 0): back to the user's index, smaller moves only. */
    private static void restoreAfterAk(Context app, GmpfClient g, Gmpf2Client g2, int user) {
        if (sOpen != null || AkOverlay.isActive() || Hal.isKeystoneRunning()) { Log.i(TAG, "restore: busy, skipped"); return; }
        boolean opened = false;
        try {
            if (g.getCurtainFit()) { Log.i(TAG, "restore: Fit to the screen is on, the size stays XGIMI's"); return; }
            setMarker(app, true);
            Log.i(TAG, "restore: 18(true,2) -> " + g2.setKstPrepareForZoom(true) + ", 121(0) -> " + g.zoomSessionStart());
            opened = true;
            int max = g.getZoomStepRange()[2];
            int now = g.getZoomStep()[1];
            int goal = Math.min(user, topIndex(app, max));
            Log.i(TAG, "restore: index " + now + " -> " + goal + ", mode " + modeName(loadMode(app)));
            if (now != 0 || goal <= 0) return;                // only from the re-homed start, never bigger
            if (loadMode(app) != MODE_GOOD) now = resyncFromStart(app, g, max);
            goal = Math.min(user, topIndex(app, max));
            for (int i = 0; now < goal && i < 2 * max + 4; i++) {
                int[] m = move119(g, now, +1);
                if (m[0] != 0) break;
                now = m[1];
            }
            Log.i(TAG, "restore: done at index " + now);
            if (now > 0) Ui.main().post(() -> Hal.requestAutofocus(app));
        } catch (Throwable t) {
            Log.w(TAG, "restore failed: " + t);
        } finally {
            if (opened) close(app, g, g2);
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
        boolean bigger = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP;
        boolean smaller = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!bigger && !smaller) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;   // OK: nothing
        }
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            if (ev.getEventTime() - ev.getDownTime() > 500) pending.set(0);   // a held key stops at once (stock)
            return true;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN || state != READY) return true;
        long now = SystemClock.uptimeMillis();
        if (ev.getRepeatCount() > 0 && now - lastKeyAt < MIN_STEP_GAP_MS) return true;
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
        // a session still entering is closed by the end of enter(); an open one here (queued behind the moves)
        if ((state == READY || state == FAILED) && !Hal.run((g, g2) -> {
            boolean moved = cur >= 0 && cur != start;
            if (moved) setUserIndex(app, cur);
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
        card.addView(text(c, c.getString(R.string.panel_optical_zoom_summary), 26, Theme.TEXT_DIM));
        bar = new SizeBar(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Theme.px(c, 760), Theme.px(c, 44));
        blp.topMargin = Theme.px(c, 8);
        blp.bottomMargin = Theme.px(c, 8);
        card.addView(bar, blp);
        value = text(c, c.getString(R.string.zoom_wait), 30, Theme.TEXT_DIM);
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

    /** Bar (left small, right big) and "Size N of M" (M = the positions we use); a half segment between two. */
    private void render() {
        if (value == null || state != READY || top < 0) return;
        int c = Math.min(cur, top);
        value.setText(app.getString(R.string.zoom_position, top - c + 1, top + 1));
        bar.set(top - c, top, half && lastDir > 0);
        if (!busy) status.setText("");
    }

    private void say(int res) {
        if (status != null) status.setText(app.getString(res));
    }

    private static void ui(Runnable r) { Ui.main().post(r); }

    // =================================================================== persisted state

    private static SharedPreferences prefs(Context app) {
        return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int bootCount(Context app) {
        try { return Settings.Global.getInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
        catch (Throwable t) { return -1; }
    }

    /** XGIMI's mode as last seen in this boot (gmpf_main keeps it in memory; a reboot makes it unknown). */
    private static int loadMode(Context app) {
        try {
            SharedPreferences p = prefs(app);
            return p.getInt(K_MODE_BOOT, -2) == bootCount(app) ? p.getInt(K_MODE, MODE_UNKNOWN) : MODE_UNKNOWN;
        } catch (Throwable t) { return MODE_UNKNOWN; }
    }

    private static void saveMode(Context app, int mode) {
        try { prefs(app).edit().putInt(K_MODE, mode).putInt(K_MODE_BOOT, bootCount(app)).apply(); }
        catch (Throwable t) { Log.w(TAG, "mode: " + t); }
    }

    /** max, or the lower cap a resync left in this boot. */
    private static int topIndex(Context app, int max) {
        try {
            SharedPreferences p = prefs(app);
            int cap = p.getInt(K_MODE_BOOT, -2) == bootCount(app) ? p.getInt(K_CAP, -1) : -1;
            return cap > 0 && cap < max ? cap : max;
        } catch (Throwable t) { return max; }
    }

    private static int userIndex(Context app) {
        try { return prefs(app).getInt(K_USER, -1); } catch (Throwable t) { return -1; }
    }

    private static void setUserIndex(Context app, int i) {
        try { prefs(app).edit().putInt(K_USER, i).apply(); } catch (Throwable t) { Log.w(TAG, "user index: " + t); }
    }

    private static String modeName(int m) { return m == MODE_GOOD ? "full-step" : m == MODE_HALF ? "half-step" : "unknown"; }

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
