package org.lumen.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.game.GameMode;
import org.lumen.projector.hal.Play6Gmpf;
import org.lumen.projector.hal.Play6Gmpf2;
import org.lumen.projector.hal.Play6KstPoint;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Theme;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manual 4-point keystone of the XGIMI Play 6. A fork of Z9xProjector panel/ManualKeystonePanel.java
 * (tools/play6/forks.tsv): the same full-screen card (grid / frame over the picture / test quad, MENU
 * cycles them), the same keys, coalescing, 150 learning and read-backs, with the Play 6 sequence of
 * docs/play6/SETTINGS_PLAN.md 1.3 (stock KeyStoneWind on the non-GMPF2 platform):
 *
 *   enter: refused while game mode runs with option 2 or 3 (stock isGameKeystoneMutex: 647 is 2 or 3
 *          and game/GameMode.isOn, 666 reads 4 or IGmpf2 33's state field reads 0) -> 586
 *          real-time keystone -> IGmpf2 125(1) setPushPullScreenState (stock order: 125 first); when
 *          it is not delivered (guard closed, a transport error) the card closes BEFORE any IGmpf write
 *          -> 573(2,false) only when real-time keystone is on -> 130 read (log only; 129 is never sent:
 *          stock never reaches it on the Play 6) -> 186(0) the 4 corners -> local shape check -> 150
 *          with the UNCHANGED corners (self-test of the 186 / 185 mapping in the 1920x1080 space,
 *          Play6KstPoint, UNVERIFIED until test B9).
 *   move:  local shape check -> 150 -> 185, as the Z9X (coalesced, 150 skipped inside a learned range,
 *          the first 185 of a session read back with 186).
 *   exit:  a pending target (150 + 185) -> 186 read-back -> 573(2,true) only if this session paused it
 *          -> IGmpf2 125(0) -> "Saved" card. No 158 (owner decision O4) and no IGmpf2 setKstPrepare
 *          (absent on the Play 6).
 * Differences to the Z9X card, from the plan: no full-frame reset (the full frame is not known before
 * test B9: "taken from the device"; hold OK does nothing), so no "reset first" mode either: corners in an
 * unexpected order, or corners that 150 rejects unchanged, close the card with that reason. The
 * keystone reset itself (IGmpf 207) is owner decision O3 and not in this card.
 *
 * Coordinates: 186 / 150 / 185 in the Play 6 panel space 1920x1080 (Play6KstPoint). Our window is
 * composed before the vendor warp, so the frame edge IS the edge of the projected picture (as on the
 * Z9X; at the Play 6 UI of 1920x1080 one UI px is one panel px). Step per key: the stock defaults of
 * user_manu_keystone_step_x / _y (6.0 / 3.375; the G0058 feature list sets no other value, stock
 * KeyStoneManager adds them straight to the 186 coordinates), faster while held (x1, x2 after 4 repeats,
 * x4 after 10, x8 after 20, x16 after 40) but never more than LEAD steps ahead of what the projector shows.
 * Keys: OK = next corner (TL -> TR -> BR -> BL); D-pad = move the corner; MENU = view; BACK = save and exit
 * (back to the quick panel when opened from it). Auto-hide, HOME and screen off also save.
 *
 * Owed calls survive a process death: the markers {@link #KEY_PAUSED} (573(2,true) owed) and {@link
 * #KEY_PUSH_PULL} (IGmpf2 125(0) owed), device-protected, written before the call they cover; {@link
 * #install} settles them 5 s after every IGmpf (re)connect, with a bounded retry (3 x 5 s).
 * HAL: all on p6-hal (Hal.run, FIFO), typed methods of Play6Gmpf / Play6Gmpf2 only.
 */
public final class ManualKeystonePanel extends Panel {
    private static final String TAG = "P6PanelKst";
    /** Stock user_manu_keystone_step_x / _y defaults, in Play 6 panel units (1920x1080). */
    private static final float STEP_X = 6.0f, STEP_Y = 3.375f;
    /** Picture-edge line width (design px), drawn from the outermost pixel inwards. */
    private static final float EDGE_PX = 6;
    /** Corner L mark: arm length and thickness (design px), drawn from the corner pixel inwards. */
    private static final float MARK_LEN = 140, MARK_PX = 12;
    /** Direction from each corner (TL, TR, BR, BL) into the picture (drawing). */
    private static final int[] IN_X = {1, -1, -1, 1}, IN_Y = {1, 1, -1, -1};
    /** Default view: a lit alignment grid over the whole frame (grey, not white: a white frame dazzles). */
    private static final int GRID_BG = 0xFF8A8A8A, GRID_LINE = 0xFFE6E6E6;
    private static final float GRID_CELL = 120, GRID_PX = 2, GRID_CENTRE_PX = 4;
    /** MENU cycles these views. */
    private static final int VIEW_FRAME = 0, VIEW_PREVIEW = 1, VIEW_QUAD = 2;
    private static final long AUTO_HIDE_MS = 120_000;
    private static final long LIMIT_NOTE_GAP_MS = 2_000;
    /** Read-back tolerance (panel px) of the first 185 of a session. */
    private static final int READBACK_TOLERANCE = 8;
    /** The first 185 is read back (186) up to this many times, this far apart (async vendor apply). */
    private static final int READBACK_TRIES = 3;
    private static final long READBACK_GAP_MS = 80;
    /** The enter task must have started by then (HAL dropped / busy otherwise): refusal with a reason. */
    private static final long ENTER_WATCHDOG_MS = 6_000;
    /** Held key: steps the target may run ahead of the corner the projector shows. */
    private static final int LEAD_STEPS = 8, LEAD_STEPS_MAX = 24;
    /** Game options that block keystone while game mode runs (stock isGameKeystoneMutex). */
    private static final int GAME_OPTION_TOP_SPEED = 2, GAME_OPTION_TOP_SPEED_HFR = 3;

    private static final int STATE_WAIT = 0, STATE_READY = 1, STATE_FAILED = 3;
    /** Device-protected prefs: what a session still owes the vendor (survives a process death). */
    private static final String PREFS = "p6_kst";
    /** Real-time keystone was on (586) and we paused it with 573(2,false): 573(2,true) owed. */
    private static final String KEY_PAUSED = "rt_paused";
    /** IGmpf2 125(1) setPushPullScreenState was sent: 125(0) owed. */
    private static final String KEY_PUSH_PULL = "push_pull";
    /** Delay of the marker recovery after an IGmpf (re)connect. */
    private static final long RECOVER_DELAY_MS = 5_000;
    /** Bounded re-tries of a failed settle while the HAL stays connected. */
    private static final int RETRY_MAX = 3;
    private static final long RETRY_GAP_MS = 5_000;
    /** The open session (main thread writes); the connect hook / retries leave a live session alone. */
    private static volatile ManualKeystonePanel sOpen;

    private final Context app;
    private final Runnable onBackExit;
    private final float[] tx = new float[4], ty = new float[4];   // main thread writes; HAL copies under lock
    private int corner = Play6KstPoint.TL;
    private int viewMode = VIEW_FRAME;                           // main thread; MENU cycles it
    private int state = STATE_WAIT;                              // main thread
    private boolean backExit, refusedReturn;
    private long lastLimitNote;
    private int moveSeq;                                         // main thread writes under lock
    private long pendingSince;                                   // under lock: uptime of the oldest move not sent yet (0 = none)
    private KstView view;
    private final Runnable redraw = () -> { if (view != null) view.invalidate(); };
    /** Set (main thread) when the card closed: an enter task that runs later does nothing. */
    private volatile boolean closed;

    // HAL-thread state (only touched by tasks on p6-hal; volatile for the log/diagnostics reads)
    private volatile boolean active;                             // enter passed its checks: exit() undoes
    private volatile boolean rtPaused;                           // this session paused real-time keystone
    private volatile boolean pushPulled;                         // this session sent IGmpf2 125(1)
    private volatile boolean aborted;                            // closed by a HAL-side refusal: no more 185
    private volatile boolean changed;
    private volatile boolean verified;                           // 150 accepted the 186 set: moves allowed
    private volatile boolean confirmed;                          // a 185 was confirmed by the 186 read-back
    private volatile Play6KstPoint applied;                      // what the projector shows (main thread reads it too)
    // Segment learned from 150 (HAL thread), as on the Z9X.
    private Play6KstPoint okBase;                                // null = nothing learned: check
    private int okCorner, okFixed, okLo, okHi;
    private boolean okAlongX;
    private boolean mustCheck;                                   // 150 rejected the last checked target
    // Session timing (HAL thread; logged per 185 and in total at the exit)
    private int n150, nSkip150, n185;
    private long sum150, sum185, max185, sumLag, maxLag;
    private int nLag;
    private final AtomicBoolean applyQueued = new AtomicBoolean();
    private final Object lock = new Object();                    // guards tx/ty copy for the HAL thread

    private ManualKeystonePanel(Context app, Runnable onBackExit) {
        this.app = app;
        this.onBackExit = onBackExit;
    }

    /**
     * Main thread. Opens the overlay (replacing the quick panel) and starts the enter sequence.
     * {@code onBackExit} (may be null) runs on the main thread after a BACK exit (not after a timeout /
     * HOME / screen off) and after a refusal of the enter checks, e.g. to show the quick panel again.
     */
    static void open(Context ctx, Runnable onBackExit) {
        final Context app = ctx.getApplicationContext();
        if (!Hal.isReady()) { refuse(app, "HAL not connected", R.string.kst_reason_not_ready); return; }
        if (!Hal.hasGmpf2()) { refuse(app, "IGmpf2 guard closed (125 cannot be sent)", R.string.kst_reason_not_ready); return; }
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
        if (!Hal.run((g, g2) -> p.enter(g, g2))) {
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
     * Play6App at the start gate (before the first connect). After every IGmpf (re)connect: whatever a
     * session still owes the vendor per the markers (process death, HAL away or a failed call at exit)
     * is sent once (573(2,true), IGmpf2 125(0)); a failure re-arms the bounded retry. No HAL call here.
     */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> {
            if (!flag(app, KEY_PAUSED) && !flag(app, KEY_PUSH_PULL)) return;     // nothing owed: no task
            Hal.runDelayed((g, g2) -> {
                if (!recover(app, g, g2, "connect")) scheduleRetry(app, 1);
            }, RECOVER_DELAY_MS);
        });
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
     * HAL thread. Pauses real-time keystone around the 185 sequence only when it is on (586, stock
     * mAkStatus); a "paused" marker left by an earlier session counts as on. 586 unreadable and no
     * marker: not paused (a 573(2,true) later could otherwise switch on what the user turned off).
     */
    private static boolean pauseRealtime(Context app, Play6Gmpf g, String who) {
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
            Log.i(TAG, who + ": 573(2,false) -> " + g.setRealtimeAkRunning(false));
        } catch (Throwable t) {
            Log.w(TAG, who + ": 573(2,false): " + t + " (continuing)");
        }
        return true;
    }

    /** HAL thread: 573(2,true); the marker is cleared only on a true result. */
    private static boolean tryResume(Context app, Play6Gmpf g, String who) {
        try {
            boolean r = g.setRealtimeAkRunning(true);
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

    /** HAL thread: IGmpf2 125(0); its marker is cleared on any delivered reply (result logged). */
    private static boolean tryPushPullOff(Context app, Play6Gmpf2 g2, String who) {
        try {
            boolean r = g2.setPushPullScreen(false);
            markFlag(app, KEY_PUSH_PULL, false);
            Log.i(TAG, who + ": IGmpf2 125(0) -> " + r);
            return true;
        } catch (Play6Gmpf2.ReplyException e) {
            markFlag(app, KEY_PUSH_PULL, false);
            Log.w(TAG, who + ": IGmpf2 125(0) delivered, bad reply: " + e.getMessage());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, who + ": IGmpf2 125(0): " + t + " (marker kept)");
            return false;
        }
    }

    /** HAL thread: settles both markers unless a session is open. True = nothing left to do. */
    private static boolean recover(Context app, Play6Gmpf g, Play6Gmpf2 g2, String who) {
        if (sOpen != null) return true;                     // the open session reads/settles the markers
        boolean ok = true;
        if (flag(app, KEY_PAUSED)) {
            Log.i(TAG, who + ": paused marker found");
            ok &= tryResume(app, g, who);
        }
        if (flag(app, KEY_PUSH_PULL)) {
            Log.i(TAG, who + ": push-pull marker found");
            ok &= tryPushPullOff(app, g2, who);
        }
        return ok;
    }

    /** Any thread: re-run {@link #recover} RETRY_GAP_MS later, at most RETRY_MAX times in a row. */
    private static void scheduleRetry(final Context app, final int attempt) {
        if (attempt > RETRY_MAX) {
            Log.w(TAG, "marker retries used up: left to the next IGmpf connect");
            return;
        }
        boolean q = Hal.runDelayed((g, g2) -> {
            if (sOpen != null) { Log.i(TAG, "marker retry " + attempt + " skipped: a session is open"); return; }
            if (!recover(app, g, g2, "retry " + attempt)) scheduleRetry(app, attempt + 1);
        }, RETRY_GAP_MS);
        if (!q) Log.w(TAG, "marker retry not queued: left to the next IGmpf connect");
    }

    // ------------------------------------------------------------------ HAL thread
    private void enter(Play6Gmpf g, Play6Gmpf2 g2) {
        if (closed) { Log.i(TAG, "enter skipped: the card is already closed"); return; }
        int reason = R.string.kst_reason_hal;
        try {
            if (akBusy()) {
                reason = R.string.kst_reason_ak_busy;
                throw new IllegalStateException("auto keystone running");
            }
            // stock isGameKeystoneMutex: game mode on (GameMode.isOn: 666 == 4, or IGmpf2 33 state 0;
            // the 33 order {state, type, option} is confirmed, t15) AND 647 option 2 / 3; reads only, an unreadable
            // value counts as "not blocking" (as stock's simpleCatch)
            int pm = -1, opt = -1;
            int[] prop33 = null;
            try { pm = g.getPictureMode(); } catch (Throwable t) { Log.w(TAG, "666: " + t); }
            try { prop33 = g2.getGameModeProp(); } catch (Throwable t) { Log.w(TAG, "IGmpf2 33: " + t); }
            try { opt = g.getGameModeOption(); } catch (Throwable t) { Log.w(TAG, "647: " + t); }
            if ((opt == GAME_OPTION_TOP_SPEED || opt == GAME_OPTION_TOP_SPEED_HFR) && GameMode.isOn(pm, prop33, opt)) {
                reason = R.string.panel_kst_game_mode;
                throw new IllegalStateException("game mode keystone mutex (666=" + pm + " 33 raw " + GameMode.raw(prop33)
                        + " 647=" + opt + ")");
            }
            // IGmpf2 125(1) before any IGmpf write (plan 1.3): not delivered = no session at all
            markFlag(app, KEY_PUSH_PULL, true);
            try {
                Log.i(TAG, "IGmpf2 125(1) -> " + g2.setPushPullScreen(true));
            } catch (Play6Gmpf2.ReplyException e) {
                Log.w(TAG, "IGmpf2 125(1) delivered, bad reply (continuing): " + e.getMessage());
            } catch (Throwable t) {
                markFlag(app, KEY_PUSH_PULL, false);
                reason = R.string.kst_reason_hal;
                throw new IllegalStateException("IGmpf2 125(1) not delivered: " + t);
            }
            pushPulled = true;
            active = true;                                   // exit() undoes whatever follows
            rtPaused = pauseRealtime(app, g, "manual keystone");
            try {
                Log.i(TAG, "130 keystone mode -> " + g.getKeystoneMode() + " (log only, 129 never sent)");
            } catch (Throwable t) {
                Log.w(TAG, "130: " + t);
            }

            Play6KstPoint cur;
            try {
                cur = g.getKeystonePoints();                 // 186
            } catch (Play6Gmpf.ReplyException e) {
                // Delivered but unusable: not a 326-byte KstPoint, or a corner outside 0..1920 x 0..1080
                // (= another coordinate space; raw values in the message): refuse, never guess.
                String m = String.valueOf(e.getMessage());
                reason = m.contains(" not in ") || m.contains("out of range")
                        ? R.string.kst_reason_range : R.string.kst_reason_read;
                throw new IllegalStateException("186 reply: " + m);
            }
            if (cur == null) {
                reason = R.string.kst_reason_read;
                throw new IllegalStateException("186 returned nothing");
            }
            if (!plausible(cur)) {
                // In range but crossed / out of order (e.g. the 8-point mode, which we do not switch).
                reason = R.string.kst_reason_shape;
                throw new IllegalStateException("186 shape " + cur + " raw " + cur.raw());
            }
            final long t150 = SystemClock.uptimeMillis();
            if (!g.checkKeystonePoints(cur)) {               // 150 self-test with the unchanged corners
                reason = R.string.kst_reason_outside;
                throw new IllegalStateException("150 rejected the unchanged 186 points " + cur + " raw " + cur.raw());
            }
            applied = cur;
            verified = true;
            Log.i(TAG, "manual keystone entered: " + cur + " raw " + cur.raw()
                    + " (150 " + (SystemClock.uptimeMillis() - t150) + " ms)");
            final Play6KstPoint start = cur.copy();
            Ui.main().post(() -> onEntered(start));
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
     * HAL thread: 185 {@code p}. The first 185 of the session is followed by up to READBACK_TRIES 186
     * read-backs that must match {@code p} (READBACK_TOLERANCE); later ones are trusted. The 185 i32
     * result is only logged (its success value is UNVERIFIED). False = the projector did not apply it.
     */
    private boolean send185(Play6Gmpf g, Play6KstPoint p, String why, long since) throws Exception {
        long t0 = SystemClock.uptimeMillis();
        int r = g.applyKeystonePoints(p);
        long done = SystemClock.uptimeMillis(), ms = done - t0;
        n185++;
        sum185 += ms;
        max185 = Math.max(max185, ms);
        String timing = why + ", 185 " + ms + " ms";
        if (since > 0) {
            long lag = done - since;
            nLag++;
            sumLag += lag;
            maxLag = Math.max(maxLag, lag);
            timing += ", key -> projector " + lag + " ms";
        }
        if (confirmed) {
            Log.d(TAG, "185 " + p + " -> " + r + " (" + timing + ")");
            return true;
        }
        for (int i = 1; i <= READBACK_TRIES; i++) {
            Play6KstPoint back = null;
            String err = null;
            try { back = g.getKeystonePoints(); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            boolean ok = back != null && near(back, p);
            Log.i(TAG, "first 185 " + p + " -> " + r + " (" + timing + "); 186 read-back " + i + "/" + READBACK_TRIES + ": "
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
     * HAL thread, after an unconfirmed first 185: put the session's start corners back (only when 150
     * accepts them), then close with "the projector did not apply the corner".
     */
    private void notApplied(Play6Gmpf g) {
        aborted = true;                                      // no further 185 in this session
        Play6KstPoint prev = applied;
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
     * Local shape check of a 186 set: corners in TL, TR, BR, BL order (TL left of TR, BL left of BR, TL
     * above BL, TR above BR) and a convex quadrilateral turning the same way at every corner.
     */
    private static boolean plausible(Play6KstPoint p) {
        if (p.x(Play6KstPoint.TL) >= p.x(Play6KstPoint.TR) || p.x(Play6KstPoint.BL) >= p.x(Play6KstPoint.BR)) return false;
        if (p.y(Play6KstPoint.TL) >= p.y(Play6KstPoint.BL) || p.y(Play6KstPoint.TR) >= p.y(Play6KstPoint.BR)) return false;
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

    private void applyLatest(Play6Gmpf g) {
        applyQueued.set(false);
        if (!active || aborted || applied == null || !verified) return;
        if (abortIfAkBusy("185 move")) return;
        Play6KstPoint p = new Play6KstPoint();
        final int seq;
        final long since;
        synchronized (lock) {
            seq = moveSeq;
            since = pendingSince;
            pendingSince = 0;
            targets(p);
        }
        if (same(p, applied)) return;
        try {
            if (checkedApply(g, p, since, false)) {
                if (active && applied == p) resyncAfterMove(g, p, seq);
                return;
            }
            Ui.main().post(this::noteLimit);
        } catch (Throwable t) {
            Log.w(TAG, "apply: " + t);
            Notify.show(app, app.getString(R.string.kst_refused), app.getString(R.string.kst_reason_hal));
        }
        undoTo(applied.copy(), seq);
    }

    /**
     * HAL thread, after a 185 the vendor took: 186 again, as stock does before every key (KeyStoneWind 298-300,
     * KeyStoneManager 393-401: each step starts from what the vendor really holds). When the vendor holds other
     * corners than we sent (it clamped or re-based the warp), they become {@link #applied} and the card's targets,
     * unless the user moved again meanwhile; nothing learned for 150 survives that (audit 2026-10-24, A1).
     */
    private void resyncAfterMove(Play6Gmpf g, Play6KstPoint sent, int seq) {
        Play6KstPoint back;
        try {
            back = g.getKeystonePoints();                                // 186
        } catch (Throwable t) {
            Log.w(TAG, "186 after 185: " + t);
            return;
        }
        if (back == null || near(back, sent)) return;
        Log.i(TAG, "186 after 185 " + sent + " -> " + back + ": the vendor holds other corners, taken");
        applied = back;
        okBase = null;
        undoTo(back.copy(), seq);
    }

    /**
     * HAL thread: local shape check -> 150 (only when {@link #needsCheck}, or always with {@code always})
     * -> 185. True = applied (or the session closed because the projector did not apply it: nothing to
     * undo then); false = rejected, {@link #applied} unchanged.
     */
    private boolean checkedApply(Play6Gmpf g, Play6KstPoint p, long since, boolean always) throws Exception {
        if (!plausible(p)) {                                             // crossed / out of order: no HAL call
            Log.i(TAG, "local shape check rejected " + p);
            return false;
        }
        Play6KstPoint prev = applied;
        boolean check = always || needsCheck(p);
        String why;
        if (check) {
            long t0 = SystemClock.uptimeMillis();
            boolean ok = g.checkKeystonePoints(p);                       // 150
            long ms = SystemClock.uptimeMillis() - t0;
            n150++;
            sum150 += ms;
            if (!ok) {
                mustCheck = true;                                        // the next move is checked too
                Log.i(TAG, "150 rejected " + p + " (" + ms + " ms)");
                return false;
            }
            why = "150 " + ms + " ms";
        } else {
            nSkip150++;
            why = "150 skipped: inside the accepted range";
        }
        if (!send185(g, p, why, since)) {                                // 185 (+ first-time read-back)
            notApplied(g);
            return true;
        }
        if (check) learnAccepted(prev, p);
        applied = p;
        Ui.main().post(redraw);                                          // the card's "projector" ring
        changed = true;
        return true;
    }

    /**
     * HAL thread: 150 needed for {@code p}? Not when only {@link #okCorner} moved (the other three corners
     * exactly as in {@link #okBase}) and it stays on the segment 150 accepted for it. Always on the first
     * move and after a rejection (nothing learned then). The vendor's 185 re-checks the shape itself.
     */
    private boolean needsCheck(Play6KstPoint p) {
        if (mustCheck || okBase == null) return true;
        for (int c = 0; c < 4; c++) {
            if (c != okCorner && (p.x(c) != okBase.x(c) || p.y(c) != okBase.y(c))) return true;
        }
        int along = okAlongX ? p.x(okCorner) : p.y(okCorner);
        int across = okAlongX ? p.y(okCorner) : p.x(okCorner);
        return across != okFixed || along < okLo || along > okHi;
    }

    /**
     * HAL thread, after 150 accepted {@code p} and 185 applied it: when exactly one corner moved from
     * {@code prev} along one axis, its segment grows to hold both positions. Anything else: nothing learned.
     */
    private void learnAccepted(Play6KstPoint prev, Play6KstPoint p) {
        mustCheck = false;
        int moved = -1;
        for (int c = 0; c < 4; c++) {
            if (prev.x(c) == p.x(c) && prev.y(c) == p.y(c)) continue;
            if (moved >= 0) { okBase = null; return; }
            moved = c;
        }
        if (moved < 0) return;
        boolean alongX = prev.y(moved) == p.y(moved);
        if (!alongX && prev.x(moved) != p.x(moved)) { okBase = null; return; }    // diagonal (coalesced)
        int fixed = alongX ? p.y(moved) : p.x(moved);
        int a = alongX ? p.x(moved) : p.y(moved), b = alongX ? prev.x(moved) : prev.y(moved);
        boolean same = okBase != null && okCorner == moved && okAlongX == alongX && okFixed == fixed;
        for (int c = 0; same && c < 4; c++) {
            if (c != moved && (p.x(c) != okBase.x(c) || p.y(c) != okBase.y(c))) same = false;
        }
        if (!same) {
            okBase = p.copy();
            okCorner = moved;
            okAlongX = alongX;
            okFixed = fixed;
            okLo = okHi = b;
        }
        okLo = Math.min(okLo, Math.min(a, b));
        okHi = Math.max(okHi, Math.max(a, b));
    }

    /** Under {@link #lock}: the UI targets rounded and clamped into {@code p}. */
    private void targets(Play6KstPoint p) {
        for (int c = 0; c < 4; c++) {
            p.set(c, clamp(Math.round(tx[c]), 0, Play6KstPoint.PANEL_W), clamp(Math.round(ty[c]), 0, Play6KstPoint.PANEL_H));
        }
    }

    /**
     * HAL thread: put the UI targets back to {@code back} (the last applied set) unless the user moved
     * again after the rejected snapshot {@code seq}.
     */
    private void undoTo(final Play6KstPoint back, final int seq) {
        Ui.main().post(() -> {
            synchronized (lock) {
                if (moveSeq != seq) return;
            }
            setTargets(back);
        });
    }

    /**
     * HAL thread: pending target -> 186 read-back -> 573(2,true) -> IGmpf2 125(0), each only when owed
     * (markers settle failures); then the "Saved" card. Every decision is logged.
     */
    private void exit(Play6Gmpf g, Play6Gmpf2 g2) {
        if (!active) return;
        flushPending(g);
        active = false;
        boolean saved = changed && confirmed;                 // only a 185 the read-back confirmed
        boolean ok = true;
        boolean shown = saved && verifySaved(g);
        if (rtPaused || flag(app, KEY_PAUSED)) {
            boolean r = tryResume(app, g, "exit");
            ok &= r;
            if (r) Log.i(TAG, "exit: real-time keystone re-armed (it corrects only after the projector is moved, as stock)");
        } else {
            Log.i(TAG, "exit: real-time keystone was off (586) or not paused: 573 not sent, nothing re-armed");
        }
        if (pushPulled || flag(app, KEY_PUSH_PULL)) ok &= tryPushPullOff(app, g2, "exit");
        if (!ok) scheduleRetry(app, 1);
        Log.i(TAG, "manual keystone left (changed=" + changed + ", confirmed=" + confirmed + ", " + applied + "); "
                + "185 x" + n185 + (n185 > 0 ? " avg " + sum185 / n185 + " ms max " + max185 + " ms" : "")
                + ", 150 x" + n150 + (n150 > 0 ? " avg " + sum150 / n150 + " ms" : "") + " skipped x" + nSkip150
                + (nLag > 0 ? ", key -> projector avg " + sumLag / nLag + " ms max " + maxLag + " ms" : ""));
        if (shown) Notify.show(app, app.getString(R.string.kst_saved));
    }

    /**
     * HAL thread, exit: a target the card shows that no 185 has sent yet is applied now, always through
     * 150, so what was on the card when the user pressed BACK is exactly what gets saved.
     */
    private void flushPending(Play6Gmpf g) {
        if (aborted || !verified || applied == null) return;
        Play6KstPoint p = new Play6KstPoint();
        final long since;
        synchronized (lock) {
            since = pendingSince;
            pendingSince = 0;
            targets(p);
        }
        if (same(p, applied)) {
            Log.i(TAG, "exit: nothing pending (card == projector " + applied + ")");
            return;
        }
        if (akBusy()) {
            Log.w(TAG, "exit: pending target " + p + " not applied: an auto keystone is running");
            return;
        }
        try {
            if (checkedApply(g, p, since, true)) {
                Log.i(TAG, "exit: pending target applied before saving: " + applied);
            } else {
                Log.i(TAG, "exit: pending target " + p + " rejected: the projector keeps " + applied);
            }
        } catch (Throwable t) {
            Log.w(TAG, "exit: pending target: " + t);
        }
    }

    /** HAL thread, exit: one 186 read-back compared with the last 185 (logged). False only when it clearly differs. */
    private boolean verifySaved(Play6Gmpf g) {
        Play6KstPoint want = applied;
        try {
            Play6KstPoint back = g.getKeystonePoints();
            boolean ok = back != null && want != null && near(back, want);
            if (ok) Log.i(TAG, "exit: 186 read-back " + back + " matches the last 185");
            else Log.w(TAG, "exit: 186 read-back " + back + " differs from the last 185 " + want + ": no \"saved\" card");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "exit: 186 read-back: " + t + " (saved card shown: the 185s were confirmed)");
            return true;
        }
    }

    private static boolean near(Play6KstPoint a, Play6KstPoint b) {
        for (int c = 0; c < 4; c++) {
            if (Math.abs(a.x(c) - b.x(c)) > READBACK_TOLERANCE || Math.abs(a.y(c) - b.y(c)) > READBACK_TOLERANCE) return false;
        }
        return true;
    }

    private static boolean same(Play6KstPoint a, Play6KstPoint b) {
        for (int c = 0; c < 4; c++) if (a.x(c) != b.x(c) || a.y(c) != b.y(c)) return false;
        return true;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Panel x (0..1920) -> x in a UI box {@code uiW} wide (1:1 for the full screen at the 1920x1080 UI). */
    static float uiX(float panelX, float uiW) { return panelX * uiW / Play6KstPoint.PANEL_W; }

    /** Panel y (0..1080) -> y in a UI box {@code uiH} high. */
    static float uiY(float panelY, float uiH) { return panelY * uiH / Play6KstPoint.PANEL_H; }

    // ------------------------------------------------------------------ main thread
    private void onEntered(Play6KstPoint start) {
        if (!isShowing()) return;
        setTargets(start);
        state = STATE_READY;
        if (view != null) view.invalidate();
    }

    private void setTargets(Play6KstPoint p) {
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

    /** Held key: steps the target may run ahead of the projector (LEAD_STEPS x acceleration, capped). */
    static float lead(int repeat) {
        return Math.min(LEAD_STEPS * accel(repeat), LEAD_STEPS_MAX);
    }

    private void move(int dx, int dy, int repeat) {
        if (state != STATE_READY) return;
        float mult = accel(repeat);
        Play6KstPoint shown = applied;                            // the projector's corners (HAL thread writes)
        boolean clamped, moved;
        synchronized (lock) {
            float wantX = tx[corner] + dx * STEP_X * mult, wantY = ty[corner] + dy * STEP_Y * mult;
            float ex = Math.max(0f, Math.min(Play6KstPoint.PANEL_W, wantX));    // panel space
            float ey = Math.max(0f, Math.min(Play6KstPoint.PANEL_H, wantY));
            float nx = ex, ny = ey;
            if (repeat > 0 && shown != null) {
                // Held key: at most lead() steps ahead of the picture, so it stops near where the key is
                // released (single presses always count).
                float lx = lead(repeat) * STEP_X, ly = lead(repeat) * STEP_Y;
                if (dx != 0) nx = Math.max(shown.x(corner) - lx, Math.min(shown.x(corner) + lx, nx));
                if (dy != 0) ny = Math.max(shown.y(corner) - ly, Math.min(shown.y(corner) + ly, ny));
                // ... but never back against the key (a target already further out stays)
                if (dx * (nx - tx[corner]) < 0) nx = tx[corner];
                if (dy * (ny - ty[corner]) < 0) ny = ty[corner];
            }
            clamped = (nx == ex && ex != wantX) || (ny == ey && ey != wantY);   // at the panel edge
            moved = nx != tx[corner] || ny != ty[corner];
            tx[corner] = nx;
            ty[corner] = ny;
            if (moved) {
                moveSeq++;
                if (pendingSince == 0) pendingSince = SystemClock.uptimeMillis();
            }
        }
        if (clamped) noteLimit();                             // edge of the 1920x1080 panel
        if (!moved) return;
        if (view != null) view.invalidate();
        if (applyQueued.compareAndSet(false, true)) {
            if (!Hal.run((g, g2) -> applyLatest(g))) applyQueued.set(false);
        }
    }

    private void nextCorner() {
        if (state != STATE_READY) return;
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
                // OK = next corner on release; holding it does nothing (no full-frame reset on the Play 6)
                if (action == KeyEvent.ACTION_UP && !ev.isCanceled() && ev.getEventTime() - ev.getDownTime() < 800) nextCorner();
                return true;
            case KeyEvent.KEYCODE_MENU:
                // View only. Grid -> frame over the picture below -> diagnostic 186 quad.
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
        // Stock KeyStoneWind: a window of exactly the display size at (0,0), so the edge line sits on
        // the real first/last pixel row/column of the composed UI frame (= the picture edge after the warp).
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
        boolean wasOpen = state == STATE_READY;
        state = STATE_FAILED;
        closed = true;
        if (sOpen == this) sOpen = null;
        // Not queued / skipped (HAL away): the persisted markers make the connect hook settle them.
        if (!Hal.run((g, g2) -> exit(g, g2))) Log.w(TAG, "exit not queued: 573 / 125 left to the connect hook");
        if (((backExit && wasOpen) || refusedReturn) && onBackExit != null) {
            Ui.main().post(() -> {
                try { onBackExit.run(); } catch (Throwable t) { Log.w(TAG, "back target: " + t); }
            });
        }
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Full-screen layer (stock KeyStoneWind look): opaque grey grid (or the picture below), a white line
     * on the outermost pixels (= the picture edge after the vendor warp) and a filled L mark per corner
     * whose OUTER edge is the corner pixel, the selected one in the accent colour with its four move
     * arrows; a centre card with the title, the corner name, the quadrilateral (panel 1920x1080 corners
     * mapped into the 16:9 box) and the key hints.
     */
    private final class KstView extends View {
        private final Paint cardBg = Theme.fill(Theme.NOTIFY_BG);
        private final Paint cardStroke;
        private final Paint frame, quad, arrowFill, edge, mark, markSel, quadEdge, gridLine;
        private final Paint dot = Theme.fill(Theme.TEXT_DIM);
        private final Paint dotSel = Theme.fill(Theme.ACCENT);
        private final Paint shownRing;
        private final TextPaint title, sub, hint;
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final float[] px = new float[4], py = new float[4];   // onDraw scratch (no allocation per frame)
        private final float[] kx = new float[4], ky = new float[4];   // corner pixels TL, TR, BR, BL (onDraw scratch)
        private final String titleText, hintMove, hintBack, hintView, waitText;
        private final String[] cornerNames;
        /** The card texts ellipsized for {@link #fitW} (TextUtils.ellipsize allocates: once per width, not per frame). */
        private String fTitle, fMove, fBack, fView, fWait;
        private float fitW = -1;
        private boolean geometryLogged;

        KstView(Context c) {
            super(c);
            cardStroke = Theme.stroke(c, Theme.PANEL_STROKE, Theme.STROKE);
            frame = Theme.stroke(c, Theme.TRACK, 2);
            quad = Theme.stroke(c, Theme.TEXT, 3);
            shownRing = Theme.stroke(c, Theme.ACCENT, 3);
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
            titleText = c.getString(R.string.panel_kst_manual);
            hintMove = c.getString(R.string.kst_hint_move);
            hintBack = c.getString(R.string.p6_kst_hint_back);
            hintView = c.getString(R.string.kst_hint_view);
            waitText = c.getString(R.string.panel_kst_wait);
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
            // The whole alignment relies on this view covering the composed UI frame 1:1.
            int[] at = new int[2];
            getLocationOnScreen(at);
            android.graphics.Point real = new android.graphics.Point();
            try { getDisplay().getRealSize(real); } catch (Throwable ignored) { }
            boolean ok = at[0] == 0 && at[1] == 0 && getWidth() == real.x && getHeight() == real.y;
            Log.i(TAG, "layer " + getWidth() + "x" + getHeight() + " at " + at[0] + "," + at[1] + ", display "
                    + real.x + "x" + real.y + " (1 UI px = " + (getWidth() > 0 ? Play6KstPoint.PANEL_W / (float) getWidth() : 0f)
                    + " panel px)" + (ok ? "" : " MISMATCH: the edge line is NOT on the picture edge"));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean live = state == STATE_READY;
            // Opaque grid, so nothing below can be taken for the picture edge. PREVIEW / QUAD keep the
            // picture below visible on purpose.
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

        /** Filled L marks from the exact corner pixel inwards; the selected one with its four move arrows. */
        private void drawCorners(Canvas c, float w, float h) {
            float len = wholePx(MARK_LEN), th = wholePx(MARK_PX);
            kx[0] = 0; kx[1] = w; kx[2] = w; kx[3] = 0;        // TL, TR, BR, BL corner pixels
            ky[0] = 0; ky[1] = 0; ky[2] = h; ky[3] = h;
            for (int i = 0; i < 4; i++) {
                Paint p = i == corner ? markSel : mark;
                rect(c, kx[i], ky[i], kx[i] + IN_X[i] * len, ky[i] + IN_Y[i] * th, p);   // horizontal arm
                rect(c, kx[i], ky[i], kx[i] + IN_X[i] * th, ky[i] + IN_Y[i] * len, p);   // vertical arm
            }
            float hx = kx[corner] + IN_X[corner] * dp(110), hy = ky[corner] + IN_Y[corner] * dp(110);
            arrows(c, hx, hy);
        }

        /**
         * Diagnostic (MENU, third view): the 186 corners mapped into the UI, as if the OSD were NOT
         * warped. On a warped OSD this shape is warped a second time: only use it to tell which drawing
         * the playing video matches.
         */
        private void drawQuad(Canvas c, float w, float h) {
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
            arrows(c, px[corner] + IN_X[corner] * dp(80), py[corner] + IN_Y[corner] * dp(80));
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
            float avail = cw - 2 * pad;
            if (avail != fitW) refit(avail);

            float y = top + pad - title.ascent();
            c.drawText(fTitle, left + pad, y, title);
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
                // While the projector catches up: a ring where it shows the corner now.
                Play6KstPoint a = applied;
                if (a != null) {
                    float ax = bl + uiX(a.x(corner), boxW), ay = bt + uiY(a.y(corner), boxH);
                    if (Math.abs(ax - px[corner]) >= 1f || Math.abs(ay - py[corner]) >= 1f) {
                        c.drawCircle(ax, ay, dr * 2.2f, shownRing);
                    }
                }
            }

            float hy = top + ch - pad;
            if (!live) {
                centred(c, fWait, hint, left, cw, hy);
                return;
            }
            centred(c, fMove, hint, left, cw, hy - dp(72));
            centred(c, fBack, hint, left, cw, hy - dp(36));
            centred(c, fView, hint, left, cw, hy);
        }

        /** {@code s} already fitted ({@link #refit}): centred in the card. */
        private void centred(Canvas c, String s, TextPaint p, float left, float cw, float baseline) {
            c.drawText(s, left + (cw - p.measureText(s)) / 2f, baseline, p);
        }

        /** Ellipsizes the card texts for a text width of {@code avail} (first frame / size change only). */
        private void refit(float avail) {
            fitW = avail;
            fTitle = fit(titleText, title, avail);
            fMove = fit(hintMove, hint, avail);
            fBack = fit(hintBack, hint, avail);
            fView = fit(hintView, hint, avail);
            fWait = fit(waitText, hint, avail);
        }

        private String fit(String s, TextPaint p, float avail) {
            return TextUtils.ellipsize(s, p, avail, TextUtils.TruncateAt.END).toString();
        }
    }
}
