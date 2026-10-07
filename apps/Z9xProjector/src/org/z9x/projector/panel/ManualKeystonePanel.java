package org.z9x.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.hal.Gmpf2Client;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.hal.KstPoint;
import org.z9x.projector.source.TifHdmiState;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manual 4-point keystone (FEATURE_SPEC 2.4), XGIMI-style (v6.3).
 *
 * Look (v6.6, stock KeyStoneWind): a FULL-SCREEN overlay, opaque black (stock rootView color_33
 * #000000) with a white line on the outermost pixels of the frame (stock keystone_zoom_bk.png, a
 * 1920x1080 border) and a filled L mark whose OUTER edge is the corner pixel itself. Our window is
 * composed before the vendor warp (HWC GL trapezoid pass, TrapezoidVertexGenerator), so the frame
 * edge IS the edge of the projected picture: the user lays the white line on the screen frame, and
 * the picture (and the video, which stock warps with the same TrapezoidParams) lands exactly there.
 * v6.4 BUG fixed here: the brackets were drawn 28 design px INSIDE the window corners (8/10 px
 * stroke centred on that line, round caps) over a transparent window; aligning the bracket vertex
 * with the screen corner left the real picture 24..28 UI px (48..56 panel px, ~1.5 % of the width
 * and ~2.6 % of the height) beyond every screen edge - exactly "the video goes past the edges".
 * MENU cycles the view: frame on black (default) -> frame over whatever plays below (check that the
 * video edges sit on the white line) -> diagnostic quad (the 186 corners mapped 1:2 into the UI, i.e.
 * where the corners are if the OSD were NOT warped). See research v66/keystone/ourpanel.
 * Evidence that the OSD is warped (so brackets must NOT be mapped through 186, or the warp would be
 * applied twice): (1) stock KeyStoneWind (ns_jadx com/xgimi/keystone/view/keystone/KeyStoneWind.java)
 * is a display-sized TYPE 2008 window whose corner dots (dotOneView, dotTwoView, ...) sit at fixed
 * layout positions, never moved by code (no setX / setTranslation on views): stock relies on the
 * warp to carry them to the picture corners; (2) the AK flow (FEATURE_SPEC 0.1) fades its UI pattern out (113) before the vendor
 * applies CorrectKeystone, because the warp would distort the overlay too; (3) the corners live in
 * the 3840x2160 DLP panel space, i.e. the warp runs after the SoC has composed the 1920x1080 frame.
 * If the white line ever stays put while the picture corner moves (OSD not warped), the QUAD view
 * draws (uiX(tx[i]), uiY(ty[i])) instead; never draw both (the warp would be applied twice).
 * A small centre card names the corner, shows the quadrilateral and the keys (three hint lines).
 *
 * Coordinates (v6.4, VERIFIED live 2026-10-06): 186/150/185 use the DLP PANEL space 3840x2160
 * (KstPoint.PANEL_W/H; v6.3 wrongly validated against 1920x1080 and refused every real keystone with
 * "x 3594 not in 0..1920"). Targets are kept and moved in panel space; only the drawing maps them to
 * the 1920x1080 UI ({@link #uiX} / {@link #uiY}).
 *
 * Keys: OK = next corner (TL -> TR -> BR -> BL); D-pad = move the selected corner by the stock step
 * in panel units (x 6.0 / y 3.375 = stock user_manu_keystone_step_x/_y, which stock KeyStoneManager
 * adds straight to the 186 coordinates; kept as floats, rounded on send), faster while held (x1, x2
 * after 4 repeats, x4 after 10, x8 after 20, x16 after 40); hold OK (0.8 s) = reset to the full picture (MENU only
 * switches the view: one stray press must not throw the corners away); BACK = save and exit (back to the
 * quick panel when opened from it). Auto-hide, HOME and screen off also save (the exit runs from
 * onDismissed).
 *
 * HAL (all on "z9x-hal", FIFO; whitelisted codes only; never 421/400, zoom or motor codes here):
 *   enter: 647 (+696 / tvinput session): refused only for top-speed game mode WHILE on HDMI (647
 *          keeps its last value on the Android source, where the warp is active) -> 586 real-time
 *          keystone: only when it is on (stock mAkStatus) the "paused" marker is persisted and
 *          573(2,false) pauses it (failure only logged) -> 186 read the 4 corners -> local shape check
 *          (convex, in TL/TR/BR/BL order) -> 150 with the UNCHANGED corners (self-test of the
 *          UNVERIFIED 186/185 mapping). 186 failing or out of the 3840x2160 panel range (= another
 *          coordinate space; raw values logged) is a refusal with that exact reason. A 186 set in range but in an
 *          unexpected shape (raw kstMode logged: e.g. the 8-point mode, which we do not switch), or a
 *          plausible one that 150 rejects (beyond the manual range), opens the card in "reset first"
 *          mode when 150 accepts the full frame: moves are blocked until hold-OK applies it (150 ->
 *          185, then the 186 read-back below).
 *   first 185 of a session (move or reset): preceded by IGmpf2 18 setKstPrepare(true,0) (stock
 *          notifyKeystone(true,0) before the first move: the vendor's software keystone scene; marker
 *          persisted first). The 185 result (success value UNVERIFIED, logged) is followed by up to 3
 *          186 read-backs 80 ms apart compared with what was sent (all logged). No match = "the
 *          projector did not apply the corner": the previous corners are restored when 150 accepts
 *          them, the card closes with that reason, nothing counts as changed (no 158, no "saved").
 *   move:  150 check -> 185 apply only when accepted (a rejected move is undone in the UI unless
 *          newer moves exist, and the card says the limit is reached); moves are coalesced: at most
 *          one apply task is queued, it sends the latest target. An auto keystone that starts during
 *          the session (ours or the vendor overlay) closes it with "auto keystone is running".
 *   exit:  18(false,0) if 18(true) was sent -> 158(0) regenerate the boot logo (only if a 185 was
 *          confirmed and no auto keystone runs) -> 573(2,true) only if this session paused it. A
 *          failure (or a false 573 result) keeps the marker: a bounded retry (3 x 5 s) runs, and
 *          {@link #install}'s connect hook does the same 5 s after every HAL (re)connect.
 */
public final class ManualKeystonePanel extends Panel {
    private static final String TAG = "Z9xPanelKst";
    /** Stock user_manu_keystone_step_x / _y defaults (FEATURE_SPEC 2.4), in PANEL units (3840x2160). */
    private static final float STEP_X = 6.0f, STEP_Y = 3.375f;
    /**
     * Picture-edge line width (design px; 1 design px = 1 UI px = 2 panel px on the Z9X). Stock
     * keystone_zoom_bk.png: from the edge 1-2 px black, 2 px grey #858585, 4 px white; we draw the
     * white from pixel 0 so the OUTER edge of the line is the picture edge (the extra px absorb the
     * vendor anti-alias pass, vendor.xgimi.hwc.aa, which feathers the outermost pixels).
     */
    private static final float EDGE_PX = 6;
    /** Corner L mark: arm length and thickness (design px), drawn from the corner pixel inwards. */
    private static final float MARK_LEN = 140, MARK_PX = 12;
    /**
     * Default view (v6.5.2, user request): a lit alignment grid over the WHOLE frame, so the picture
     * edge stands out against the black border of the screen even when the film below is dark.
     * Grey fill (not white: a full white frame dazzles in a dark room), 120 px cells (16 x 9 on
     * 1920x1080), white 2 px lines, 4 px centre cross. Opaque: the picture below (and the green/red
     * garbage the realtime-AK path makes of DRM video) is never visible in this view.
     */
    private static final int GRID_BG = 0xFF8A8A8A, GRID_LINE = 0xFFE6E6E6;
    private static final float GRID_CELL = 120, GRID_PX = 2, GRID_CENTRE_PX = 4;
    /** MENU cycles these views (see the class comment). */
    private static final int VIEW_FRAME = 0, VIEW_PREVIEW = 1, VIEW_QUAD = 2;
    private static final long LONG_PRESS_MS = 800;
    private static final long AUTO_HIDE_MS = 120_000;
    private static final long LIMIT_NOTE_GAP_MS = 2_000;
    /** Read-back tolerance (panel px) of the first 185 of a session. */
    private static final int READBACK_TOLERANCE = 8;
    /** The first 185 is read back (186) up to this many times, this far apart (async vendor apply). */
    private static final int READBACK_TRIES = 3;
    private static final long READBACK_GAP_MS = 80;
    /** The enter task must have started by then (HAL dropped / busy otherwise): refusal with a reason. */
    private static final long ENTER_WATCHDOG_MS = 6_000;

    private static final int STATE_WAIT = 0, STATE_READY = 1, STATE_NEED_RESET = 2, STATE_FAILED = 3;
    /** Device-protected prefs: what a session (or the panel reset) still owes the vendor (survives a process death). */
    private static final String PREFS = "z9x_kst";
    /** Real-time keystone was on (586) and we paused it with 573(2,false): 573(2,true) owed. */
    private static final String KEY_PAUSED = "rt_paused";
    /** IGmpf2 18 setKstPrepare(true,0) was sent: 18(false,0) owed. */
    private static final String KEY_PREPARED = "kst_prepared";
    /** Delay of the marker recovery after a HAL (re)connect: past the boot handshake's first steps. */
    private static final long RECOVER_DELAY_MS = 5_000;
    /** Bounded re-tries of a failed resume while the HAL stays connected. */
    private static final int RETRY_MAX = 3;
    private static final long RETRY_GAP_MS = 5_000;
    /** The open session (main thread writes); the connect hook / retries leave a live session alone. */
    private static volatile ManualKeystonePanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private final float[] tx = new float[4], ty = new float[4];   // main thread writes; HAL copies under lock
    private int corner = KstPoint.TL;
    private int viewMode = VIEW_FRAME;                           // main thread; MENU cycles it
    private int state = STATE_WAIT;                              // main thread
    private boolean okDown, okLongDone, backExit, refusedReturn;
    private long lastLimitNote;
    private int moveSeq;                                         // main thread writes under lock
    private KstView view;
    /** Set (main thread) when the card closed: an enter task that runs later does nothing. */
    private volatile boolean closed;

    // HAL-thread state (only touched by tasks on z9x-hal; volatile for the log/diagnostics reads)
    private volatile boolean active;                             // enter passed its checks: exit() undoes
    private volatile boolean rtPaused;                           // this session paused real-time keystone
    private volatile boolean prepared;                           // 18(true,0) sent in this session
    private volatile boolean aborted;                            // closed by a HAL-side refusal: no more 185
    private volatile boolean changed;
    private volatile boolean verified;                           // 150 accepted a 186 set / reset confirmed: moves allowed
    private volatile boolean confirmed;                          // a 185 was confirmed by the 186 read-back
    private volatile KstPoint applied;
    private final AtomicBoolean applyQueued = new AtomicBoolean();
    private final AtomicBoolean resetQueued = new AtomicBoolean();
    private final Object lock = new Object();                    // guards tx/ty copy for the HAL thread

    private ManualKeystonePanel(Context app, Runnable onBackExit) {
        this.app = app;
        this.onBackExit = onBackExit;
    }

    /** Main thread. Same as {@link #open(Context, Runnable)} without a BACK target. */
    static void open(Context ctx) { open(ctx, null); }

    /**
     * Main thread. Opens the overlay (replacing the quick panel) and starts the enter sequence.
     * {@code onBackExit} (may be null) runs on the main thread after a BACK exit (not after a
     * timeout / HOME / screen off) and after a refusal of the enter checks, e.g. to show the quick
     * panel again.
     */
    static void open(Context ctx, Runnable onBackExit) {
        final Context app = ctx.getApplicationContext();
        if (!Hal.isReady()) { refuse(app, "HAL not connected", R.string.kst_reason_not_ready); return; }
        if (akBusy()) {
            refuse(app, "auto keystone running", R.string.kst_reason_ak_busy);
            return;
        }
        final ManualKeystonePanel p = new ManualKeystonePanel(app, onBackExit);
        if (!OverlayHost.get(app).show(p)) {
            refuse(app, "overlay window could not be added", R.string.kst_reason_window);
            return;
        }
        sOpen = p;
        if (!Hal.run((g, g2) -> p.enter(g))) {
            refuse(app, "HAL task not queued", R.string.kst_reason_not_ready);
            p.dismiss();
            return;
        }
        // Hal.run skips a queued task silently when the HAL drops before it runs: never leave the
        // card on "wait" without a reason.
        Ui.main().postDelayed(() -> {
            if (sOpen == p && p.state == STATE_WAIT && p.isShowing()) {
                Log.w(TAG, "enter did not run within " + ENTER_WATCHDOG_MS + " ms (HAL dropped or busy)");
                p.fail(R.string.kst_reason_not_ready);
            }
        }, ENTER_WATCHDOG_MS);
    }

    /** True while an open manual keystone card exists (main thread writes). */
    public static boolean isOpen() { return sOpen != null; }

    /** Any thread: the Notify card "Manual keystone unavailable" + the exact reason. */
    private static void refuse(Context app, String log, int reasonRes) {
        Log.w(TAG, "manual keystone refused: " + log);
        Notify.show(app, app.getString(R.string.kst_refused), app.getString(reasonRes));
    }

    /** Any thread: an auto keystone (ours queued/running, or the vendor's AK overlay) is active. */
    private static boolean akBusy() {
        return Hal.isKeystoneRunning() || AkOverlay.isActive();
    }

    // ------------------------------------------------------------------ persisted markers
    /**
     * App start (main thread). After every HAL (re)connect: whatever a session (or the panel reset)
     * still owes the vendor per the markers (process death, HAL away or a failed call at exit) is
     * sent once (18(false,0), 573(2,true)); a failure re-arms the bounded retry.
     */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> Hal.runDelayed((g, g2) -> {
            if (!recover(app, g, g2, "connect")) scheduleRetry(app, 1);
        }, RECOVER_DELAY_MS));
    }

    private static SharedPreferences prefs(Context app) {
        try {
            return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static boolean flag(Context app, String key) {
        try { return prefs(app).getBoolean(key, false); } catch (Throwable t) { Log.w(TAG, "marker read: " + t); return false; }
    }

    /** HAL thread. Synchronous write: a marker must be on disk before the call it covers. */
    private static void markFlag(Context app, String key, boolean on) {
        try { prefs(app).edit().putBoolean(key, on).commit(); } catch (Throwable t) { Log.w(TAG, "marker " + key + ": " + t); }
    }

    /**
     * HAL thread. Pauses real-time keystone around a 185 sequence only when it is on (586, stock
     * mAkStatus); a "paused" marker left by an earlier session counts as on (it was on then and was
     * not resumed). 586 unreadable and no marker: not paused (a 573(2,true) later could otherwise
     * switch on what the user turned off). Returns true when paused: then {@link #resumeRealtime}.
     */
    static boolean pauseRealtime(Context app, GmpfClient g, String who) {
        boolean on;
        if (flag(app, KEY_PAUSED)) {
            on = true;
            Log.i(TAG, who + ": paused marker present: real-time keystone counted as on");
        } else {
            try {
                on = g.getMoveAk();                                  // 586
                Log.i(TAG, who + ": 586 real-time keystone -> " + on);
            } catch (Throwable t) {
                Log.w(TAG, who + ": 586: " + t + " (573 not sent)");
                return false;
            }
        }
        if (!on) return false;
        markFlag(app, KEY_PAUSED, true);
        try {
            Log.i(TAG, who + ": 573(2,false) -> " + g.setRealtimeAkTemporarily(false));
        } catch (Throwable t) {
            Log.w(TAG, who + ": 573(2,false): " + t + " (continuing)");
        }
        return true;
    }

    /** HAL thread, after {@link #pauseRealtime} returned true. A failure re-arms the bounded retry. */
    static void resumeRealtime(Context app, GmpfClient g, String who) {
        if (!tryResume(app, g, who)) scheduleRetry(app, 1);
    }

    /** HAL thread: 573(2,true); the marker is cleared only on a true result. */
    private static boolean tryResume(Context app, GmpfClient g, String who) {
        try {
            boolean r = g.setRealtimeAkTemporarily(true);
            Log.i(TAG, who + ": 573(2,true) -> " + r);
            if (r) {
                markFlag(app, KEY_PAUSED, false);
                return true;
            }
            Log.w(TAG, who + ": 573(2,true) returned false (marker kept)");
        } catch (Throwable t) {
            Log.w(TAG, who + ": 573(2,true): " + t + " (marker kept)");
        }
        return false;
    }

    /** HAL thread: 18(false,0); its marker is cleared on any reply (result logged, UNVERIFIED). */
    private static boolean tryUnprepare(Context app, Gmpf2Client g2, String who) {
        try {
            boolean r = g2.setKstPrepare(false);
            markFlag(app, KEY_PREPARED, false);
            Log.i(TAG, who + ": 18 setKstPrepare(false,0) -> " + r);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, who + ": 18 setKstPrepare(false,0): " + t + " (marker kept)");
            return false;
        }
    }

    /** HAL thread: settles both markers unless a session is open. True = nothing left to do. */
    private static boolean recover(Context app, GmpfClient g, Gmpf2Client g2, String who) {
        if (sOpen != null) return true;                     // the open session reads/settles the markers
        boolean ok = true;
        if (flag(app, KEY_PREPARED)) {
            Log.i(TAG, who + ": prepare marker found");
            ok &= tryUnprepare(app, g2, who);
        }
        if (flag(app, KEY_PAUSED)) {
            Log.i(TAG, who + ": paused marker found");
            ok &= tryResume(app, g, who);
        }
        return ok;
    }

    /** Any thread: re-run {@link #recover} RETRY_GAP_MS later, at most RETRY_MAX times in a row. */
    private static void scheduleRetry(final Context app, final int attempt) {
        if (attempt > RETRY_MAX) {
            Log.w(TAG, "marker retries used up: left to the next HAL connect");
            return;
        }
        boolean q = Hal.runDelayed((g, g2) -> {
            if (sOpen != null) { Log.i(TAG, "marker retry " + attempt + " skipped: a session is open"); return; }
            if (!recover(app, g, g2, "retry " + attempt)) scheduleRetry(app, attempt + 1);
        }, RETRY_GAP_MS);
        if (!q) Log.w(TAG, "marker retry not queued: left to the next HAL connect");
    }

    // ------------------------------------------------------------------ HAL thread
    private void enter(GmpfClient g) {
        if (closed) { Log.i(TAG, "enter skipped: the card is already closed"); return; }
        int reason = R.string.kst_reason_hal;
        String why;
        try {
            if (akBusy()) {
                reason = R.string.kst_reason_ak_busy;
                throw new IllegalStateException("auto keystone running");
            }
            GmpfClient.GameModeOption o = null;
            try { o = g.getGameModeOption(); } catch (Throwable t) { Log.w(TAG, "647: " + t); }
            if (o != null && o.isTopSpeed()) {
                int src = -1;
                try { src = g.getCurrentInputSource(); } catch (Throwable t) { Log.w(TAG, "696: " + t); }
                Boolean tif = TifHdmiState.read(app);
                boolean hdmi = src == GmpfClient.SOURCE_HDMI1 || src == GmpfClient.SOURCE_HDMI2 || Boolean.TRUE.equals(tif);
                if (hdmi) {
                    reason = R.string.kst_reason_game;
                    throw new IllegalStateException("top-speed game mode on HDMI (647=" + o + " 696=" + src + " tif=" + tif + ")");
                }
                Log.i(TAG, "647 " + o + " but not on HDMI (696=" + src + " tif=" + tif + "): allowed");
            }
            active = true;                                   // exit() undoes whatever follows
            rtPaused = pauseRealtime(app, g, "manual keystone");

            KstPoint cur;
            try {
                cur = g.getKeystonePoints();                 // 186
            } catch (GmpfClient.ReplyException e) {
                // Delivered but unusable: not a 326-byte KstPoint, or a corner outside the 3840x2160
                // panel (KstPoint.set -> "x 4000 not in 0..3840; raw ...", wrapped by getKeystonePoints).
                // Out of range = another coordinate space: refuse, never reset into a guessed one.
                String m = String.valueOf(e.getMessage());
                reason = m.contains(" not in ") || m.contains("out of range")
                        ? R.string.kst_reason_range : R.string.kst_reason_read;
                throw new IllegalStateException("186 reply: " + m);
            }
            if (cur == null) {
                reason = R.string.kst_reason_read;
                throw new IllegalStateException("186 returned nothing");
            }
            final boolean odd = !plausible(cur);
            if (odd) {
                // In range but crossed / out of order (e.g. 8-point mode, which we do not switch):
                // only the self-checked full frame may follow (reset-first), never a move from it.
                why = "186 shape " + cur + " raw " + cur.raw();
            } else if (g.checkKeystonePoints(cur)) {         // 150 self-test with the unchanged corners
                applied = cur;
                verified = true;
                Log.i(TAG, "manual keystone entered: " + cur + " raw " + cur.raw());
                final KstPoint start = cur.copy();
                Ui.main().post(() -> onEntered(start, 0));
                return;
            } else {
                why = "150 rejected the unchanged (plausible) 186 points " + cur + " raw " + cur.raw();
            }
            // Is the vendor check working at all? (150 has no side effects.)
            if (!g.checkKeystonePoints(KstPoint.fullFrame())) {
                reason = odd ? R.string.kst_reason_shape : R.string.kst_reason_check;
                throw new IllegalStateException(why + "; 150 also rejected the full frame");
            }
            Log.i(TAG, "manual keystone entered in reset-first mode: " + why);
            final int note = odd ? R.string.kst_reason_shape : R.string.kst_reason_outside;
            Ui.main().post(() -> onEntered(null, note));
        } catch (Throwable t) {
            Log.w(TAG, "manual keystone refused: " + t);
            failOnMain(reason);
        }
    }

    /** Any thread: the refusal card with {@code reasonRes}, then close (back to where it was opened from). */
    private void failOnMain(final int reasonRes) {
        Ui.main().post(() -> fail(reasonRes));
    }

    /** Main thread: see {@link #failOnMain}. Once per card. */
    private void fail(int reasonRes) {
        if (closed || state == STATE_FAILED) return;
        state = STATE_FAILED;
        Notify.show(app, app.getString(R.string.kst_refused), app.getString(reasonRes));
        refusedReturn = true;
        dismiss();
    }

    /** HAL thread: an auto keystone started during the session: no further 185, close with that reason. */
    private boolean abortIfAkBusy(String what) {
        if (!akBusy()) return false;
        Log.w(TAG, what + " skipped: auto keystone started during the session");
        aborted = true;
        failOnMain(R.string.kst_reason_ak_busy);
        return true;
    }

    /**
     * HAL thread: stock notifyKeystone(true,0) before the first 185 of the session (IGmpf2 18).
     * OFF by default since Lumen 1.0 (user report 2026-10-07): 18(true) switches the vendor to its
     * real-time SW keystone (HWC GL warp), whose geometry on our GSI differs from the DLPC8445 HW warp
     * (G0082 trapezoid props are not loaded), so the picture jumped at the first move and jumped back
     * at exit (18(false) -> HW warp). Without it every 185 goes straight to the HW warp: what the user
     * aligns is what stays; it also never captures DRM video (the green/red garbage). Debug switch:
     * persist.z9x.kst_prepare=1 restores the stock call.
     */
    private void ensurePrepared(Gmpf2Client g2) {
        if (prepared) return;
        prepared = true;
        if (!SystemProperties.getBoolean("persist.z9x.kst_prepare", false)) {
            Log.i(TAG, "18 setKstPrepare not sent (HW warp only; persist.z9x.kst_prepare=0)");
            return;
        }
        markFlag(app, KEY_PREPARED, true);
        try {
            Log.i(TAG, "18 setKstPrepare(true,0) -> " + g2.setKstPrepare(true));
        } catch (Throwable t) {
            Log.w(TAG, "18 setKstPrepare(true,0): " + t + " (continuing)");
        }
    }

    /**
     * HAL thread: 185 {@code p}. The first 185 of the session is followed by up to READBACK_TRIES
     * 186 read-backs that must match {@code p} (READBACK_TOLERANCE); later ones are trusted. The 185
     * i32 result is only logged (its success value is UNVERIFIED). False = the projector did not apply it.
     */
    private boolean send185(GmpfClient g, KstPoint p) throws Exception {
        int r = g.applyKeystonePoints(p);
        if (confirmed) {
            Log.d(TAG, "185 " + p + " -> " + r);
            return true;
        }
        for (int i = 1; i <= READBACK_TRIES; i++) {
            KstPoint back = null;
            String err = null;
            try { back = g.getKeystonePoints(); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            boolean ok = back != null && near(back, p);
            Log.i(TAG, "first 185 " + p + " -> " + r + "; 186 read-back " + i + "/" + READBACK_TRIES + ": "
                    + (back != null ? back + " raw " + back.raw() : "? " + err) + (ok ? " matches" : " differs"));
            if (ok) {
                confirmed = true;
                return true;
            }
            if (i < READBACK_TRIES) SystemClock.sleep(READBACK_GAP_MS);
        }
        Log.w(TAG, "first 185 " + p + ": the projector did not apply the corners");
        return false;
    }

    /**
     * HAL thread, after an unconfirmed first 185: put the session's start corners back (only when
     * 150 accepts them, as before any 185), then close with "the projector did not apply the corner".
     */
    private void notApplied(GmpfClient g) {
        aborted = true;                                      // no further 185 in this session
        KstPoint prev = applied;
        if (prev != null && verified) {
            try {
                if (g.checkKeystonePoints(prev)) Log.i(TAG, "restore 185 " + prev + " -> " + g.applyKeystonePoints(prev));
                else Log.w(TAG, "restore: 150 rejected " + prev);
            } catch (Throwable t) {
                Log.w(TAG, "restore: " + t);
            }
        }
        verified = false;
        failOnMain(R.string.kst_reason_not_applied);
    }

    /**
     * Local shape check of a 186 set: corners in TL, TR, BR, BL order (TL left of TR, BL left of BR,
     * TL above BL, TR above BR) and a convex quadrilateral turning the same way at every corner. A
     * different corner mapping would fail it (crossed edges).
     */
    private static boolean plausible(KstPoint p) {
        if (p.x(KstPoint.TL) >= p.x(KstPoint.TR) || p.x(KstPoint.BL) >= p.x(KstPoint.BR)) return false;
        if (p.y(KstPoint.TL) >= p.y(KstPoint.BL) || p.y(KstPoint.TR) >= p.y(KstPoint.BR)) return false;
        int sign = 0;
        for (int i = 0; i < 4; i++) {
            int a = i, b = (i + 1) % 4, c = (i + 2) % 4;
            long cross = (long) (p.x(b) - p.x(a)) * (p.y(c) - p.y(b)) - (long) (p.y(b) - p.y(a)) * (p.x(c) - p.x(b));
            int s = cross > 0 ? 1 : cross < 0 ? -1 : 0;
            if (s == 0) return false;
            if (sign == 0) sign = s; else if (s != sign) return false;
        }
        return true;
    }

    private void applyLatest(GmpfClient g, Gmpf2Client g2) {
        applyQueued.set(false);
        if (!active || aborted || applied == null || !verified) return;
        if (abortIfAkBusy("185 move")) return;
        KstPoint p = new KstPoint();
        final int seq;
        synchronized (lock) {
            seq = moveSeq;
            for (int c = 0; c < 4; c++) {
                p.set(c, clamp(Math.round(tx[c]), 0, KstPoint.MAX_X), clamp(Math.round(ty[c]), 0, KstPoint.MAX_Y));
            }
        }
        if (same(p, applied)) return;
        try {
            if (plausible(p) && g.checkKeystonePoints(p)) {                 // 150
                ensurePrepared(g2);                                          // 18(true,0) once
                if (!send185(g, p)) {                                        // 185 (+ first-time read-back)
                    notApplied(g);
                    return;
                }
                applied = p;
                if (!changed) {
                    org.z9x.projector.game.GameProfile.noteKeystoneChanged(app, "manual keystone");
                    changed = true;
                }
                return;
            }
            Log.i(TAG, "150 rejected " + p);
            Ui.main().post(this::noteLimit);
        } catch (Throwable t) {
            Log.w(TAG, "apply: " + t);
            Notify.show(app, app.getString(R.string.kst_refused), app.getString(R.string.kst_reason_hal));
        }
        undoTo(applied.copy(), seq);
    }

    /**
     * HAL thread: put the UI targets back to {@code back} (the last applied set) unless the user moved
     * again after the rejected snapshot {@code seq}: those newer moves queued their own apply, whose
     * outcome decides (a rejection there undoes then).
     */
    private void undoTo(final KstPoint back, final int seq) {
        Ui.main().post(() -> {
            synchronized (lock) {
                if (moveSeq != seq) return;
            }
            setTargets(back);
        });
    }

    /** Hold OK: the full frame through 150 -> 185; the first 185 of the session is read back (186). */
    private void resetToFullFrame(GmpfClient g, Gmpf2Client g2) {
        resetQueued.set(false);
        if (!active || aborted) return;
        if (abortIfAkBusy("185 reset")) return;
        KstPoint full = KstPoint.fullFrame();
        try {
            if (!g.checkKeystonePoints(full)) {
                Log.w(TAG, "reset: 150 rejected the full frame");
                Notify.show(app, app.getString(R.string.kst_refused), app.getString(R.string.kst_reason_check));
                return;
            }
            ensurePrepared(g2);                               // 18(true,0) once
            if (!send185(g, full)) {                          // 185 (+ first-time 186 read-back)
                notApplied(g);
                return;
            }
            Log.i(TAG, "reset: 185 full frame applied");
            applied = full;
            changed = true;
            verified = true;                                  // reset-first mode: moves allowed now
            org.z9x.projector.game.GameProfile.noteKeystoneFullFrame(app, "manual keystone reset");
            Notify.show(app, app.getString(R.string.panel_reset_keystone_done));
            Ui.main().post(() -> {
                setTargets(full);
                if (state == STATE_NEED_RESET) state = STATE_READY;
                if (view != null) view.invalidate();
            });
        } catch (Throwable t) {
            Log.w(TAG, "reset: " + t);
            Notify.show(app, app.getString(R.string.kst_refused), app.getString(R.string.kst_reason_hal));
        }
    }

    /** HAL thread: 18(false,0) -> 158(0) -> 573(2,true), each only when owed (markers settle failures). */
    private void exit(GmpfClient g, Gmpf2Client g2) {
        if (!active) return;
        active = false;
        boolean saved = changed && confirmed;                 // only a 185 the read-back confirmed
        boolean ok = true;
        if (prepared || flag(app, KEY_PREPARED)) ok &= tryUnprepare(app, g2, "exit");
        if (saved) {
            if (akBusy()) {
                Log.w(TAG, "158 skipped: an auto keystone is running");
            } else {
                try {
                    Log.i(TAG, "158(0) -> " + g.regenerateBootLogo());
                } catch (Throwable t) {
                    Log.w(TAG, "158: " + t);
                }
            }
        }
        if (rtPaused || flag(app, KEY_PAUSED)) ok &= tryResume(app, g, "exit");
        if (!ok) scheduleRetry(app, 1);
        Log.i(TAG, "manual keystone left (changed=" + changed + ", confirmed=" + confirmed + ", " + applied + ")");
        if (saved) Notify.show(app, app.getString(R.string.kst_saved));
    }

    private static boolean near(KstPoint a, KstPoint b) {
        for (int c = 0; c < 4; c++) {
            if (Math.abs(a.x(c) - b.x(c)) > READBACK_TOLERANCE || Math.abs(a.y(c) - b.y(c)) > READBACK_TOLERANCE) return false;
        }
        return true;
    }

    private static boolean same(KstPoint a, KstPoint b) {
        for (int c = 0; c < 4; c++) if (a.x(c) != b.x(c) || a.y(c) != b.y(c)) return false;
        return true;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Panel x (0..3840) -> x in a UI box {@code uiW} wide (1920 for the full screen). */
    static float uiX(float panelX, float uiW) { return panelX * uiW / KstPoint.PANEL_W; }

    /** Panel y (0..2160) -> y in a UI box {@code uiH} high (1080 for the full screen). */
    static float uiY(float panelY, float uiH) { return panelY * uiH / KstPoint.PANEL_H; }

    // ------------------------------------------------------------------ main thread
    /** start == null: reset-first mode with {@code noteRes} as the reason shown. */
    private void onEntered(KstPoint start, int noteRes) {
        if (!isShowing()) return;
        if (start != null) {
            setTargets(start);
            state = STATE_READY;
        } else {
            setTargets(KstPoint.fullFrame());               // drawing only; nothing is sent until the reset
            state = STATE_NEED_RESET;
            Notify.show(app, app.getString(noteRes), app.getString(R.string.kst_reset_first));
        }
        if (view != null) view.invalidate();
    }

    private void setTargets(KstPoint p) {
        synchronized (lock) {
            for (int c = 0; c < 4; c++) { tx[c] = p.x(c); ty[c] = p.y(c); }
        }
        if (view != null) view.invalidate();
    }

    private void noteLimit() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLimitNote < LIMIT_NOTE_GAP_MS) return;
        lastLimitNote = now;
        Notify.show(app, app.getString(R.string.panel_kst_limit));
    }

    /** Acceleration while a D-pad key is held (repeat count of the auto-repeat). */
    private static float accel(int repeat) {
        return repeat >= 40 ? 16f : repeat >= 20 ? 8f : repeat >= 10 ? 4f : repeat >= 4 ? 2f : 1f;
    }

    private void move(int dx, int dy, int repeat) {
        if (state == STATE_NEED_RESET) {
            noteResetFirst();
            return;
        }
        if (state != STATE_READY || resetQueued.get()) return;   // no move on top of a pending reset
        float mult = accel(repeat);
        boolean clamped, moved;
        synchronized (lock) {
            float wantX = tx[corner] + dx * STEP_X * mult, wantY = ty[corner] + dy * STEP_Y * mult;
            float nx = Math.max(0f, Math.min(KstPoint.MAX_X, wantX));    // panel space
            float ny = Math.max(0f, Math.min(KstPoint.MAX_Y, wantY));
            clamped = nx != wantX || ny != wantY;
            moved = nx != tx[corner] || ny != ty[corner];
            tx[corner] = nx;
            ty[corner] = ny;
            if (moved) moveSeq++;
        }
        if (clamped) noteLimit();                             // edge of the 3840x2160 panel
        if (!moved) return;
        if (view != null) view.invalidate();
        if (applyQueued.compareAndSet(false, true)) {
            if (!Hal.run((g, g2) -> applyLatest(g, g2))) applyQueued.set(false);
        }
    }

    private void noteResetFirst() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLimitNote < LIMIT_NOTE_GAP_MS) return;
        lastLimitNote = now;
        Notify.show(app, app.getString(R.string.kst_outside_title), app.getString(R.string.kst_reset_first));
    }

    private void requestReset() {
        if (state != STATE_READY && state != STATE_NEED_RESET) return;
        if (resetQueued.compareAndSet(false, true)) {
            if (!Hal.run((g, g2) -> resetToFullFrame(g, g2))) resetQueued.set(false);
        }
    }

    private void nextCorner() {
        if (state != STATE_READY && state != STATE_NEED_RESET) return;
        corner = (corner + 1) % 4;
        if (view != null) view.invalidate();
    }

    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        int action = ev.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                    int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                    move(dx, dy, ev.getRepeatCount());
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (ev.getRepeatCount() == 0) {
                        okDown = true;
                        okLongDone = false;
                    } else if (okDown && !okLongDone && ev.getEventTime() - ev.getDownTime() >= LONG_PRESS_MS) {
                        okLongDone = true;                        // hold OK: reset
                        requestReset();
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    if (okDown && !okLongDone && !ev.isCanceled()) nextCorner();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // View only (never a reset: that is hold-OK). Grid -> frame over the picture
                // below -> diagnostic 186 quad (see the class comment).
                if (down && ev.getRepeatCount() == 0) {
                    viewMode = (viewMode + 1) % 3;
                    Log.i(TAG, "view " + (viewMode == VIEW_FRAME ? "grid" : viewMode == VIEW_PREVIEW ? "preview" : "quad"));
                    if (view != null) view.invalidate();
                }
                return true;
            default:
                return false;                                 // BACK: onBack -> dismiss (save)
        }
    }

    @Override
    protected boolean onBack() {
        backExit = true;
        return false;                                         // close; onDismissed saves
    }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        // Stock KeyStoneWind: a window of exactly getDefaultDisplay() width x height at (0,0). The
        // edge line must sit on the real first/last pixel row/column of the composed 1920x1080
        // frame (= the picture edge after the warp), so pin the size to the REAL display size and
        // allow layout outside any decor/inset area (KstView.onLayout logs a MISMATCH otherwise).
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
        view = new KstView(c);
        return view;
    }

    @Override
    protected void onShown(View root) {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Theme.PANEL_FADE_MS).start();
    }

    @Override
    protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(Theme.PANEL_EXIT_MS).withEndAction(end).start();
    }

    @Override
    protected void onDismissed() {
        boolean wasOpen = state == STATE_READY || state == STATE_NEED_RESET;
        state = STATE_FAILED;
        closed = true;
        if (sOpen == this) sOpen = null;
        // Not queued / skipped (HAL away): the persisted markers make the connect hook settle them.
        if (!Hal.run((g, g2) -> exit(g, g2))) Log.w(TAG, "exit not queued: 18/573 left to the connect hook");
        if (((backExit && wasOpen) || refusedReturn) && onBackExit != null) {
            Ui.main().post(() -> {
                try { onBackExit.run(); } catch (Throwable t) { Log.w(TAG, "back target: " + t); }
            });
        }
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Full-screen layer (stock KeyStoneWind look): opaque black, a white line on the outermost
     * pixels (= the picture edge after the vendor warp) and a filled L mark per corner whose OUTER
     * edge is the corner pixel, the selected one in the accent colour with its four move arrows; a
     * centre card with the title, the corner name, the quadrilateral (panel 3840x2160 corners
     * mapped into the 16:9 box) and the key hints. Geometry: 1 UI px (1920x1080) = 2 panel px; the
     * GOP stretches the whole 1920x1080 frame onto 3840x2160 (HWC dump: CustomerSize
     * 1920:1080 -> 3840:2160, RectXYWH 0,0,1920,1080), and the vendor warp maps that frame's corners
     * to the 186 corners. So UI (0,0) / (w,0) / (w,h) / (0,h) land exactly on TL / TR / BR / BL.
     */
    private final class KstView extends View {
        private final Paint cardBg = Theme.fill(Theme.NOTIFY_BG);
        private final Paint cardStroke;
        private final Paint frame, quad, arrowFill, edge, mark, markSel, quadEdge, gridLine;
        private final Paint dot = Theme.fill(Theme.TEXT_DIM);
        private final Paint dotSel = Theme.fill(Theme.ACCENT);
        private final TextPaint title, sub, hint, warn;
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final String titleText, hintMove, hintMore, hintView, waitText, resetFirstText;
        private final String[] cornerNames;
        private boolean geometryLogged;

        KstView(Context c) {
            super(c);
            cardStroke = Theme.stroke(c, Theme.PANEL_STROKE, Theme.STROKE);
            frame = Theme.stroke(c, Theme.TRACK, 2);
            quad = Theme.stroke(c, Theme.TEXT, 3);
            // NO anti-alias on anything that marks the edge: a half-covered outermost pixel would
            // move the visible edge inwards. Rects on whole pixels only.
            edge = new Paint();
            edge.setStyle(Paint.Style.FILL);
            edge.setColor(0xFFFFFFFF);
            mark = new Paint(edge);
            gridLine = new Paint(edge);
            gridLine.setColor(GRID_LINE);
            markSel = new Paint(edge);
            markSel.setColor(Theme.ACCENT);
            quadEdge = new Paint(Paint.ANTI_ALIAS_FLAG);       // diagnostic quad only
            quadEdge.setStyle(Paint.Style.STROKE);
            quadEdge.setStrokeWidth(Theme.pxf(c, 4));
            quadEdge.setColor(0xFFFFD000);
            arrowFill = Theme.fill(Theme.ACCENT);
            title = Theme.text(c, Theme.ROW_TITLE_SIZE, Theme.TEXT, true);
            sub = Theme.text(c, Theme.ROW_VALUE_SIZE, Theme.ACCENT, true);
            hint = Theme.text(c, Theme.NOTIFY_DESC_SIZE, Theme.TEXT_DIM, false);
            warn = Theme.text(c, Theme.NOTIFY_DESC_SIZE, Theme.TEXT, false);
            titleText = c.getString(R.string.panel_kst_manual);
            hintMove = c.getString(R.string.kst_hint_move);
            hintMore = c.getString(R.string.kst_hint_more);
            hintView = c.getString(R.string.kst_hint_view);
            waitText = c.getString(R.string.panel_kst_wait);
            resetFirstText = c.getString(R.string.kst_reset_first);
            cornerNames = new String[]{
                    c.getString(R.string.panel_kst_corner_tl), c.getString(R.string.panel_kst_corner_tr),
                    c.getString(R.string.panel_kst_corner_br), c.getString(R.string.panel_kst_corner_bl)};
            setFocusable(true);
            setWillNotDraw(false);
        }

        private float dp(float design) { return Theme.pxf(getContext(), design); }

        /** Whole device pixels, at least 1 (edge marks must cover full pixels). */
        private float wholePx(float design) { return Math.max(1f, Math.round(dp(design))); }

        @Override
        protected void onLayout(boolean changed, int l, int t, int rr, int b) {
            super.onLayout(changed, l, t, rr, b);
            if (geometryLogged) return;
            geometryLogged = true;
            // The whole alignment relies on this view covering the composed 1920x1080 frame 1:1.
            int[] at = new int[2];
            getLocationOnScreen(at);
            android.graphics.Point real = new android.graphics.Point();
            try { getDisplay().getRealSize(real); } catch (Throwable ignored) { }
            boolean ok = at[0] == 0 && at[1] == 0 && getWidth() == real.x && getHeight() == real.y;
            Log.i(TAG, "layer " + getWidth() + "x" + getHeight() + " at " + at[0] + "," + at[1] + ", display "
                    + real.x + "x" + real.y + (ok ? "" : " MISMATCH: the edge line is NOT on the picture edge"));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean live = state == STATE_READY || state == STATE_NEED_RESET;
            // Opaque grid, so nothing below (launcher margins, a dark or letterboxed film) can be
            // taken for the picture edge. PREVIEW / QUAD keep the picture below visible on purpose.
            if (viewMode == VIEW_FRAME) drawGrid(c, w, h);
            if (viewMode == VIEW_QUAD) {
                if (live) drawQuad(c, w, h);
            } else {
                drawEdge(c, w, h);
                if (live) drawCorners(c, w, h);
            }
            drawCard(c, w, h, live);
        }

        /** Grey fill + white grid with a thicker centre cross; whole-pixel rects, no anti-alias. */
        private void drawGrid(Canvas c, float w, float h) {
            c.drawColor(GRID_BG);
            float cell = wholePx(GRID_CELL), t = wholePx(GRID_PX), tc = wholePx(GRID_CENTRE_PX);
            for (float x = cell; x < w - 1; x += cell) c.drawRect(x - t / 2, 0, x + t / 2, h, gridLine);
            for (float y = cell; y < h - 1; y += cell) c.drawRect(0, y - t / 2, w, y + t / 2, gridLine);
            float cx = Math.round(w / 2f), cy = Math.round(h / 2f);
            c.drawRect(cx - tc / 2, 0, cx + tc / 2, h, gridLine);
            c.drawRect(0, cy - tc / 2, w, cy + tc / 2, gridLine);
        }

        /** The picture edge: a white line on the outermost EDGE_PX pixels of every side. */
        private void drawEdge(Canvas c, float w, float h) {
            float t = wholePx(EDGE_PX);
            c.drawRect(0, 0, w, t, edge);                    // top row(s)
            c.drawRect(0, h - t, w, h, edge);                // bottom
            c.drawRect(0, 0, t, h, edge);                    // left column(s)
            c.drawRect(w - t, 0, w, h, edge);                // right
        }

        /**
         * Filled L marks from the exact corner pixel inwards (outer edge = the picture corner);
         * the selected one in the accent colour, with its four move arrows inside the picture.
         */
        private void drawCorners(Canvas c, float w, float h) {
            float len = wholePx(MARK_LEN), th = wholePx(MARK_PX);
            float[] cx = {0, w, w, 0};                          // TL, TR, BR, BL corner pixels
            float[] cy = {0, 0, h, h};
            int[] sx = {1, -1, -1, 1};                         // direction into the picture
            int[] sy = {1, 1, -1, -1};
            for (int i = 0; i < 4; i++) {
                Paint p = i == corner ? markSel : mark;
                rect(c, cx[i], cy[i], cx[i] + sx[i] * len, cy[i] + sy[i] * th, p);   // horizontal arm
                rect(c, cx[i], cy[i], cx[i] + sx[i] * th, cy[i] + sy[i] * len, p);   // vertical arm
            }
            if (state != STATE_READY) return;
            float hx = cx[corner] + sx[corner] * dp(110), hy = cy[corner] + sy[corner] * dp(110);
            arrows(c, hx, hy);
        }

        /**
         * Diagnostic (MENU, third view): the 186 corners mapped 1:2 into the UI, as if the OSD were
         * NOT warped. On a warped OSD this shape is warped a second time and lies INSIDE the real
         * corners: only use it to tell which drawing the playing video matches.
         */
        private void drawQuad(Canvas c, float w, float h) {
            float[] px = new float[4], py = new float[4];
            synchronized (lock) {
                for (int i = 0; i < 4; i++) { px[i] = uiX(tx[i], w); py[i] = uiY(ty[i], h); }
            }
            path.rewind();
            path.moveTo(px[0], py[0]);                          // TL -> TR -> BR -> BL
            for (int i = 1; i < 4; i++) path.lineTo(px[i], py[i]);
            path.close();
            c.drawPath(path, quadEdge);
            float dr = dp(10);
            for (int i = 0; i < 4; i++) c.drawCircle(px[i], py[i], i == corner ? dr * 1.6f : dr, i == corner ? dotSel : dot);
            if (state != STATE_READY) return;
            int[] sx = {1, -1, -1, 1}, sy = {1, 1, -1, -1};
            arrows(c, px[corner] + sx[corner] * dp(80), py[corner] + sy[corner] * dp(80));
        }

        /** Axis-aligned rect from two opposite corners in any order. */
        private void rect(Canvas c, float x0, float y0, float x1, float y1, Paint p) {
            c.drawRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), p);
        }

        private void arrows(Canvas c, float hx, float hy) {
            float d = dp(30), a = dp(12);
            c.drawCircle(hx, hy, dp(7), arrowFill);
            arrow(c, hx, hy - d, 0, -1, a);
            arrow(c, hx, hy + d, 0, 1, a);
            arrow(c, hx - d, hy, -1, 0, a);
            arrow(c, hx + d, hy, 1, 0, a);
        }

        private void arrow(Canvas c, float x, float y, int dx, int dy, float a) {
            path.rewind();
            path.moveTo(x + dx * a, y + dy * a);                // tip
            path.lineTo(x - dy * a - dx * a * 0.2f, y + dx * a - dy * a * 0.2f);
            path.lineTo(x + dy * a - dx * a * 0.2f, y - dx * a - dy * a * 0.2f);
            path.close();
            c.drawPath(path, arrowFill);
        }

        private void drawCard(Canvas c, float w, float h, boolean live) {
            float cw = Math.min(dp(760), w - dp(96)), ch = dp(520);
            float left = (w - cw) / 2f, top = (h - ch) / 2f;
            float rad = dp(Theme.PANEL_RADIUS), pad = dp(36);
            r.set(left, top, left + cw, top + ch);
            c.drawRoundRect(r, rad, rad, cardBg);
            c.drawRoundRect(r, rad, rad, cardStroke);

            float y = top + pad - title.ascent();
            c.drawText(fit(titleText, title, cw - 2 * pad), left + pad, y, title);
            if (live) {
                String cn = cornerNames[corner];
                float cnw = sub.measureText(cn);
                c.drawText(cn, left + cw - pad - cnw, y, sub);
            }

            float boxW = dp(384), boxH = boxW * 9f / 16f;
            float bl = left + (cw - boxW) / 2f, bt = y + dp(36);
            r.set(bl, bt, bl + boxW, bt + boxH);
            c.drawRect(r, frame);
            if (live) {
                float[] px = new float[4], py = new float[4];
                synchronized (lock) {                          // panel space -> the 16:9 box
                    for (int i = 0; i < 4; i++) { px[i] = bl + uiX(tx[i], boxW); py[i] = bt + uiY(ty[i], boxH); }
                }
                path.rewind();
                path.moveTo(px[0], py[0]);                      // TL -> TR -> BR -> BL
                for (int i = 1; i < 4; i++) path.lineTo(px[i], py[i]);
                path.close();
                c.drawPath(path, quad);
                float dr = dp(8);
                for (int i = 0; i < 4; i++) c.drawCircle(px[i], py[i], i == corner ? dr * 1.6f : dr, i == corner ? dotSel : dot);
            }

            float hy = top + ch - pad;
            if (!live) {
                centred(c, waitText, hint, left, cw, pad, hy);
                return;
            }
            if (state == STATE_NEED_RESET) centred(c, resetFirstText, warn, left, cw, pad, hy - dp(116));
            centred(c, hintMove, hint, left, cw, pad, hy - dp(72));
            centred(c, hintMore, hint, left, cw, pad, hy - dp(36));
            centred(c, hintView, hint, left, cw, pad, hy);
        }

        private void centred(Canvas c, String s, TextPaint p, float left, float cw, float pad, float baseline) {
            String f = fit(s, p, cw - 2 * pad);
            c.drawText(f, left + (cw - p.measureText(f)) / 2f, baseline, p);
        }

        private String fit(String s, TextPaint p, float avail) {
            return TextUtils.ellipsize(s, p, avail, TextUtils.TruncateAt.END).toString();
        }
    }
}
