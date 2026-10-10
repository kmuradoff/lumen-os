package org.z9x.projector.dream;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.power.StandbyController;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MODULE "screensaver" (v6.2). Crash-safe "dim the lamp while the screensaver runs, then give the
 * user's level back". research/v62/dream/DREAM_SPEC.md section 2 (there called LampGuard; it lives
 * in the dream package here because the power module owns org.z9x.projector.power).
 *
 * <b>Any screensaver</b> (Lumen OS 1.0.1; before, only our clock dimmed): this persistent process
 * follows ACTION_DREAMING_STARTED / _STOPPED, so Lumen Home's living sky (another process) and every
 * other dream get the "Screensaver light" too. One dim per screensaver run, {@value #DREAM_DIM_DELAY_MS}
 * ms after the start (the dream's window is up); ClockDream asks for the same run's dim itself to time
 * its fade-in ({@link #dimForDream}), and whichever comes first does it. Not for the blank standby
 * dream (screensaver off: it turns the lamp off at once) nor during standby. The run ends at the first
 * of the clock's own wake / stop and ACTION_DREAMING_STOPPED ({@link #onDreamEnded}): the restore below
 * runs exactly then, once. A user change always wins: a level raised meanwhile is kept, the setting
 * applies from the next screensaver, and nothing is dimmed when the user's level is already that low.
 *
 * <b>HAL calls</b> (typed whitelist only): IGmpf 177 getDlpLumensLevel, 176 setDlpLumensLevel
 * (range-checked 1..10 by GmpfClient), 196 getScreenOnOff (read-only, so 176 is never sent while
 * the lamp is off: its effect there is unknown), IGmpf2 51 isBoost (read-only). At most two 176 per
 * transition plus one retry; never a value outside 1..10; never brighter than the user's level on
 * a dim; never a loop.
 *
 * <b>Why a marker.</b> The vendor most likely persists 176 per lumens mode (Msrv_DataBase_Manage::
 * SaveDlpLumensLevel; "_ReCoverLumensMode ... Level=10" on screen on/off, LIKELY, not traced).
 * A crash or a power loss while dimmed would then leave the projector dim for good. So before 176
 * goes out, {saved, dim, ts} is committed to device-protected prefs (write-ahead). On every HAL
 * (re)connect, if this process holds no dim state but a marker exists, and 177 still reads the dim
 * level or lower, the saved level is written back; the marker is cleared either way.
 *
 * <b>Restore rule.</b> The saved level goes back only while 177 reads at or below our dim level
 * (ours, or dimmed further by the power module's sleep-timer fade, TimerLamp, whose own restore
 * only touches levels inside its range); a higher level was raised by someone else and is kept.
 *
 * <b>Ordering against PowerPolicy's lamp-off</b> (SCREEN_OFF -> z9x-power -> z9x-hal: motor stop,
 * 196, 195(false)): this class registers its own SCREEN_OFF receiver one step above App's
 * (SYSTEM_HIGH_PRIORITY vs. SYSTEM_HIGH_PRIORITY - 1, ordered broadcast), and queues the restore
 * on z9x-hal directly from the main thread, so it runs before PowerPolicy's task in the FIFO.
 * If the lamp is already off when the restore runs (196 false), 176 is NOT sent; the restore is
 * retried after the next SCREEN_ON once 196 reads true (1 s steps, 12 tries). For a strict order
 * PowerPolicy may also call {@link #restoreOnHalThread} at the start of its lamp-off task
 * (optional, power module decision).
 *
 * <b>AK/AF safety.</b> While our dream runs (1.0.1: or any screensaver with the lamp dimmed by us), any
 * AK/AF focus event (105..118, 120, 333..335, 1003, 1004) ends the dream (Ui.wakeFromDream), which
 * restores the lamp before the AK pattern needs light.
 *
 * Threads: main (API, receivers, focus events), "z9x-hal" (all HAL work, sSaved / sDimLevel),
 * "z9x-lamp" (prefs: marker, light-level setting, Settings writes of DreamSettings).
 * Everything is wrapped: the persistent process must never crash.
 */
public final class DreamLamp {
    static final String TAG = "Z9xLamp";

    /** Device-protected prefs shared with DreamSettings (marker + settings). */
    static final String PREFS = "z9x_lamp";
    private static final String K_SAVED = "saved";
    private static final String K_DIM = "dim";
    private static final String K_TS = "ts";

    private static final long DONE_TIMEOUT_MS = 700;     // the UI never waits longer for the HAL
    private static final long RETRY_MS = 300;            // one 176 retry after a RemoteException
    private static final long LAMP_ON_POLL_MS = 1_000;   // deferred restore after SCREEN_ON
    private static final int LAMP_ON_POLL_TRIES = 12;    // PowerPolicy STR path polls up to 7 s
    /** Dim this long after ACTION_DREAMING_STARTED (as ClockDream's own DIM_DELAY_MS). */
    private static final long DREAM_DIM_DELAY_MS = 300;

    private static Context sApp;
    private static SafeHandler sLamp;

    /** Incremented by every dim/restore; a dim task of an older generation does nothing. */
    private static final AtomicInteger sGen = new AtomicInteger();

    // ---- z9x-hal thread only
    /** User level saved by the dim (0 = nothing dimmed by us). */
    private static int sSaved;
    /** Level we set (valid while sSaved != 0). */
    private static int sDimLevel;

    // ---- any thread
    private static volatile boolean sDimmed;
    /** Generation of a dim that was started and has not ended yet (0 = none). */
    private static volatile int sInFlightGen;
    /** A restore found the lamp off: retry after SCREEN_ON. */
    private static volatile boolean sRestoreDeferred;
    /** Marker loaded from prefs (z9x-lamp). 0 = none. */
    private static volatile int sMarkerSaved;
    private static volatile int sMarkerDim;
    private static volatile boolean sMarkerLoaded;

    // ---- one screensaver run (any dream; main thread writes, volatile for the z9x-hal readers)
    /** A screensaver runs: ACTION_DREAMING_STARTED or ClockDream's start, until {@link #onDreamEnded}. */
    private static volatile boolean sInDream;
    /** Counts the runs: a late dim callback of an ended run is ignored. */
    private static int sDreamRun;
    /** This run's dim was asked for / is done (or skipped). */
    private static boolean sDreamDimAsked, sDreamDimDone;
    /** Waiting for this run's dim (ClockDream's fade-in). */
    private static final ArrayList<Runnable> sDimWaiters = new ArrayList<>();
    private static final Runnable DREAM_DIM = DreamLamp::dimForAnyDream;

    private DreamLamp() {}

    // =================================================================== install (main)

    /** App.onCreate. Cheap: no HAL call here. Idempotent. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sLamp = SafeHandler.newThread("z9x-lamp");
        sLamp.post(DreamLamp::loadMarker);
        sLamp.post(() -> DreamSettings.loadPrefs(sApp));
        Hal.addConnectedListener(DreamLamp::onHalConnected);
        Hal.addFocusEventListener(DreamLamp::onFocusEvent);
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        String a = i == null ? null : i.getAction();
                        if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff();
                        else if (Intent.ACTION_SCREEN_ON.equals(a)) onScreenOn();
                    } catch (Throwable t) {
                        Log.w(TAG, "screen receiver: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            // One above App's receiver (SYSTEM_HIGH_PRIORITY - 1): our restore is queued on z9x-hal
            // before PowerPolicy's lamp-off task.
            f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            sApp.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "register screen receiver: " + t);
        }
        try {
            // Lumen OS 1.0.1: the screensaver light for every dream (main-thread delivery, in order)
            BroadcastReceiver d = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        String a = i == null ? null : i.getAction();
                        if (Intent.ACTION_DREAMING_STARTED.equals(a)) {
                            onDreamStarted("system");
                            Ui.main().removeCallbacks(DREAM_DIM);
                            Ui.main().postDelayed(DREAM_DIM, DREAM_DIM_DELAY_MS);
                        } else if (Intent.ACTION_DREAMING_STOPPED.equals(a)) {
                            onDreamEnded(c, "screensaver stopped");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "dreaming receiver: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
            f.addAction(Intent.ACTION_DREAMING_STOPPED);
            sApp.registerReceiver(d, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "register dreaming receiver: " + t);
        }
        try {
            // the "Screensaver" section on the quick panel's All settings page
            org.z9x.projector.panel.QuickPanel.addExtension(org.z9x.projector.panel.QuickPanel.SECTION_GENERAL,
                    new DreamPanelRows());
        } catch (Throwable t) {
            Log.w(TAG, "quick panel rows: " + t);
        }
        Log.i(TAG, "installed");
    }

    static SafeHandler worker() {
        if (sLamp == null) {
            synchronized (DreamLamp.class) {
                if (sLamp == null) sLamp = SafeHandler.newThread("z9x-lamp");
            }
        }
        return sLamp;
    }

    /** Any thread: we hold the lamp dimmed right now. */
    public static boolean isDimmed() {
        return sDimmed;
    }

    /** Any thread: a screensaver runs (ours or any other; see the class comment). */
    private static boolean inDream() {
        return sInDream || ClockDream.isRunning();
    }

    // =================================================================== screensaver runs (main)

    /** Main thread: a screensaver started (the system broadcast, or ClockDream itself). Once per run. */
    static void onDreamStarted(String who) {
        if (sInDream) return;                                 // the other path started this run
        sInDream = true;
        sDreamRun++;
        sDreamDimAsked = false;
        sDreamDimDone = false;
        sDimWaiters.clear();
        Log.i(TAG, "screensaver started (" + who + ")");
    }

    /**
     * Main thread: the dim of this screensaver run at the "Screensaver light" level (once per run).
     * {@code onDone} (may be null) runs on the main thread when it is done or skipped, at once when the
     * run ended or its dim is already done.
     */
    static void dimForDream(Context ctx, Runnable onDone) {
        if (sApp == null) install(ctx);
        if (!sInDream || sDreamDimDone) {
            runSafe(onDone);
            return;
        }
        if (onDone != null) sDimWaiters.add(onDone);
        if (sDreamDimAsked) return;
        sDreamDimAsked = true;
        final int run = sDreamRun;
        int lvl = DreamSettings.lampLevel();
        if (lvl == DreamSettings.LAMP_UNCHANGED) {
            Log.i(TAG, "screensaver light: unchanged");
            dreamDimDone(run);
            return;
        }
        dim(ctx, lvl, () -> dreamDimDone(run));
    }

    /** Main, {@value #DREAM_DIM_DELAY_MS} ms after ACTION_DREAMING_STARTED: any dream but the blank standby one. */
    private static void dimForAnyDream() {
        if (!sInDream || sDreamDimAsked || sApp == null) return;
        try {
            DreamManager dm = sApp.getSystemService(DreamManager.class);
            if (dm != null && !dm.isDreaming()) return;       // already over (its STOPPED follows)
        } catch (Throwable t) {
            Log.w(TAG, "isDreaming: " + t);
        }
        String comps = null;
        try {
            comps = Settings.Secure.getString(sApp.getContentResolver(), "screensaver_components");
        } catch (Throwable ignored) {
        }
        if (DreamDefaults.isIdle(comps)) {
            Log.i(TAG, "blank standby dream (screensaver off): no dim");
            return;
        }
        if (StandbyController.isActive()) return;            // the lamp is off
        dimForDream(sApp, null);
    }

    private static void dreamDimDone(int run) {
        if (run != sDreamRun || !sInDream) return;
        sDreamDimDone = true;
        ArrayList<Runnable> w = new ArrayList<>(sDimWaiters);
        sDimWaiters.clear();
        for (Runnable r : w) runSafe(r);
    }

    /**
     * Main thread: the screensaver ended (ClockDream woken / stopped / detached, or
     * ACTION_DREAMING_STOPPED for any dream): the user's level back, at once. Idempotent.
     */
    static void onDreamEnded(Context ctx, String why) {
        endRun(why);
        restore(ctx);
    }

    /** Main thread: forget the current screensaver run (no restore here). */
    private static void endRun(String why) {
        Ui.main().removeCallbacks(DREAM_DIM);
        if (sInDream) Log.i(TAG, "screensaver ended (" + why + ")");
        sInDream = false;
        sDreamDimAsked = false;
        sDreamDimDone = false;
        sDimWaiters.clear();
    }

    private static void runSafe(Runnable r) {
        if (r == null) return;
        try { r.run(); } catch (Throwable t) { Log.w(TAG, "onDone: " + t); }
    }

    // =================================================================== dim (main)

    /**
     * Main thread. Dims the lamp to {@code level} (clamped 1..10) unless Boost is on or unknown,
     * the HAL is not ready, or the user's level is already at or below it. {@code onDone} runs once
     * on the main thread when the dim is done, skipped, or after at most 700 ms. May be null.
     */
    public static void dim(Context ctx, int level, Runnable onDone) {
        if (sApp == null) install(ctx);
        final int lvl = Math.max(1, Math.min(10, level));
        final int gen = sGen.incrementAndGet();
        final Runnable done = once(onDone);
        Ui.main().postDelayed(done, DONE_TIMEOUT_MS);
        if (!Hal.isReady()) {
            Log.i(TAG, "dim: HAL not ready, lamp left as is");
            done.run();
            return;
        }
        sInFlightGen = gen;
        Hal.query((g, g2) -> new int[]{g.getLampLevel(), boostState(g2)}, r -> {
            if (gen != sGen.get()) { endInFlight(gen); done.run(); return; }
            if (r == null) {
                Log.i(TAG, "dim: 177 not readable, lamp left as is");
                endInFlight(gen);
                done.run();
                return;
            }
            final int cur = r[0];
            if (r[1] != 0) {
                Log.i(TAG, "dim: Boost " + (r[1] < 0 ? "unknown" : "on") + ", no dim");
                endInFlight(gen);
                done.run();
                return;
            }
            if (sDimmed) {
                // Already dimmed by an earlier dream start: only go lower, never brighter.
                Hal.run((g, g2) -> {
                    if (gen != sGen.get() || sSaved == 0 || lvl >= sDimLevel) return;
                    g.setLampLevel(lvl);
                    sDimLevel = lvl;
                    Log.i(TAG, "dim: lower " + lvl);
                    writeMarker(sSaved, lvl);
                });
                endInFlight(gen);
                done.run();
                return;
            }
            if (cur < 1 || cur > 10 || cur <= lvl) {
                Log.i(TAG, "dim: level " + cur + " <= target " + lvl + ", nothing to do");
                endInFlight(gen);
                done.run();
                return;
            }
            // Write-ahead marker on z9x-lamp, then the 176 on z9x-hal.
            worker().post(() -> {
                commitMarker(cur, lvl);
                boolean q = Hal.run((g, g2) -> dimOnHal(g, gen, cur, lvl, done));
                if (!q) {
                    clearMarker();
                    endInFlight(gen);
                    Ui.main().post(done);
                }
            });
        });
    }

    /** z9x-hal. */
    private static void dimOnHal(GmpfClient g, int gen, int cur, int lvl, Runnable done) throws Exception {
        try {
            if (gen != sGen.get()) {
                if (sSaved == 0) postClearMarker();
                Log.i(TAG, "dim: cancelled before 176");
                return;
            }
            int now = g.getLampLevel();                          // 177
            if (now != cur) {
                Log.i(TAG, "dim: level changed " + cur + " -> " + now + ", no dim");
                if (sSaved == 0) postClearMarker();
                return;
            }
            g.setLampLevel(lvl);                                 // 176
            sSaved = cur;
            sDimLevel = lvl;
            sDimmed = true;
            Log.i(TAG, "dim " + cur + "->" + lvl);
        } finally {
            endInFlight(gen);
            Ui.main().post(done);
        }
    }

    private static boolean dimInFlight() {
        return sInFlightGen != 0;
    }

    /** Any thread: the dim of {@code gen} ended (a newer dim keeps its own flag). */
    private static synchronized void endInFlight(int gen) {
        if (sInFlightGen == gen) sInFlightGen = 0;
    }

    // =================================================================== restore

    /** Main (or any) thread. Idempotent; cancels a dim still in flight. */
    public static void restore(Context ctx) {
        sGen.incrementAndGet();
        if (!sDimmed && !dimInFlight() && !sRestoreDeferred) return;
        // ifSkipped: the HAL is not connected (gmpf_main restarting). Keep the state and mark the
        // restore deferred; onHalConnected runs it once the dream is over (sSaved stays set).
        boolean q = Hal.runDelayed((g, g2) -> restoreCore(g, "restore"), 0, DreamLamp::onRestoreSkipped);
        if (!q) Log.i(TAG, "restore: HAL not available, the marker recovers on the next connect");
    }

    /**
     * z9x-hal ONLY (e.g. from PowerPolicy's lamp-off task before 196/195(false)). Gives the user's
     * level back if we hold a dim. Never throws.
     */
    public static void restoreOnHalThread(GmpfClient g) {
        sGen.incrementAndGet();
        try {
            restoreCore(g, "lamp-off");
        } catch (Throwable t) {
            Log.w(TAG, "restoreOnHalThread: " + t);
        }
    }

    /** z9x-hal. */
    private static void restoreCore(GmpfClient g, String why) throws Exception {
        if (sSaved == 0) {
            sDimmed = false;
            sRestoreDeferred = false;
            return;
        }
        boolean lampOn;
        try {
            lampOn = g.getScreenOn();                            // 196
        } catch (Exception e) {
            Log.w(TAG, "restore: 196 failed: " + e);
            lampOn = true;     // unknown: behave as before (the vendor accepts 176 with the lamp on)
        }
        if (!lampOn) {
            sRestoreDeferred = true;
            Log.i(TAG, "restore (" + why + "): lamp is off, deferred to the next lamp-on");
            return;
        }
        int cur = g.getLampLevel();                              // 177
        final int saved = sSaved;
        // At or below our dim level: ours (or dimmed further by the sleep-timer fade, TimerLamp,
        // which restores only levels inside its own range). The user cannot change the level while
        // the dream runs (any key wakes it and this restore is queued first), so a HIGHER level
        // means the user / vendor raised it: keep that.
        if (cur >= 1 && cur <= sDimLevel) {
            try {
                g.setLampLevel(saved);                           // 176
                Log.i(TAG, "restore (" + why + ") " + cur + "->" + saved);
            } catch (android.os.RemoteException e) {
                Log.w(TAG, "restore: 176 failed (" + e + "), one retry in " + RETRY_MS + " ms");
                Hal.runDelayed((g1, g2) -> {
                    int c1 = g1.getLampLevel();
                    if (c1 >= 1 && c1 <= sDimLevel) g1.setLampLevel(saved);
                    Log.i(TAG, "restore retry: level now " + g1.getLampLevel());
                    finishRestore();
                }, RETRY_MS);
                return;
            }
        } else {
            Log.i(TAG, "restore (" + why + "): level is " + cur + " (raised meanwhile), kept");
        }
        finishRestore();
    }

    /** z9x-hal (Hal ifSkipped): a restore task was dropped because the HAL was not connected. */
    private static void onRestoreSkipped() {
        if (sSaved == 0) return;
        sRestoreDeferred = true;
        Log.i(TAG, "restore skipped (HAL not connected): runs after the reconnect");
    }

    /**
     * Any thread (PowerUi.onLampOn, after PowerPolicy confirmed the lamp on): a restore that found
     * the lamp off runs now instead of waiting for the SCREEN_ON poll.
     */
    public static void onLampOn() {
        if (!sRestoreDeferred || inDream()) return;
        sGen.incrementAndGet();
        Hal.runDelayed((g, g2) -> restoreCore(g, "lamp on"), 0, DreamLamp::onRestoreSkipped);
    }

    /** z9x-hal. */
    private static void finishRestore() {
        sSaved = 0;
        sDimLevel = 0;
        sDimmed = false;
        sRestoreDeferred = false;
        postClearMarker();
    }

    // =================================================================== screen / HAL / AK hooks

    /** Main, SCREEN_OFF (before App's receiver): restore while the lamp is still on. */
    private static void onScreenOff() {
        endRun("screen off");                                 // no screensaver outlives the screen
        if (!sDimmed && !dimInFlight()) return;
        sGen.incrementAndGet();
        Hal.run((g, g2) -> restoreCore(g, "screen off"));
    }

    /** Main, SCREEN_ON: a restore that found the lamp off waits for 196 true. */
    private static void onScreenOn() {
        if (!sRestoreDeferred) return;
        final int gen = sGen.incrementAndGet();
        pollLampOn(gen, 1);
    }

    private static void pollLampOn(final int gen, final int attempt) {
        Hal.runDelayed((g, g2) -> {
            if (gen != sGen.get() || sSaved == 0) return;
            boolean on = false;
            try { on = g.getScreenOn(); } catch (Exception e) { Log.w(TAG, "poll 196: " + e); }
            if (on) {
                restoreCore(g, "after lamp-on");
            } else if (attempt < LAMP_ON_POLL_TRIES) {
                pollLampOn(gen, attempt + 1);
            } else {
                Log.w(TAG, "deferred restore: lamp still off after " + attempt + " polls; marker kept");
            }
        }, LAMP_ON_POLL_MS, DreamLamp::onRestoreSkipped);   // skipped: the reconnect / lamp-on hook re-arms
    }

    /** z9x-hal, after every HAL (re)connect: recover a dim left by a dead process / power loss. */
    private static void onHalConnected() {
        if (sSaved != 0) {
            // gmpf_main restarted while we hold a dim. During the dream: keep our state. After it
            // (a restore task was skipped while the HAL was down): restore now; restoreCore defers
            // again if the lamp is off.
            if (!inDream() && !dimInFlight()) {
                sGen.incrementAndGet();
                Hal.run((g, g2) -> restoreCore(g, "reconnect"));
            }
            return;
        }
        worker().post(() -> {
            loadMarker();
            final int saved = sMarkerSaved, dimL = sMarkerDim;
            if (saved == 0) return;
            Hal.run((g, g2) -> {
                if (sSaved != 0 || dimInFlight()) return;    // a new dim of this process owns it
                try {
                    boolean lampOn = true;
                    try { lampOn = g.getScreenOn(); } catch (Exception e) { Log.w(TAG, "recover 196: " + e); }
                    if (!lampOn) {
                        // Do not send 176 with the lamp off; adopt the state so the next lamp-on
                        // restores it (deferred path).
                        sSaved = saved;
                        sDimLevel = dimL;
                        sDimmed = true;
                        sRestoreDeferred = true;
                        Log.i(TAG, "recover: lamp off, deferred (" + dimL + "->" + saved + ")");
                        return;
                    }
                    int cur = g.getLampLevel();
                    if (cur >= 1 && cur <= dimL) {
                        g.setLampLevel(saved);
                        Log.i(TAG, "recover " + cur + "->" + saved);
                    } else {
                        Log.i(TAG, "recover: level " + cur + " above dim " + dimL + ", kept");
                    }
                    postClearMarker();
                } catch (Throwable t) {
                    Log.w(TAG, "recover: " + t + " (marker kept)");
                }
            });
        });
    }

    /** Main thread (Hal focus listener). AK/AF during our dream: end the dream gently. */
    private static void onFocusEvent(int type, String value) {
        if (!ClockDream.isRunning() && !(sInDream && (sDimmed || dimInFlight()))) return;
        boolean ak = (type >= 105 && type <= 118) || type == 120
                || (type >= 333 && type <= 335) || type == 1003 || type == 1004;
        if (!ak) return;
        Log.i(TAG, "focus event " + type + " during the screensaver: waking it");
        if (sApp != null) Ui.wakeFromDream(sApp);
    }

    // =================================================================== marker (z9x-lamp)

    static SharedPreferences prefs(Context c) {
        Context dp = c.createDeviceProtectedStorageContext();
        return dp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void loadMarker() {
        try {
            if (sApp == null) return;
            SharedPreferences p = prefs(sApp);
            sMarkerSaved = p.getInt(K_SAVED, 0);
            sMarkerDim = p.getInt(K_DIM, 0);
            if (!sMarkerLoaded && sMarkerSaved != 0) {
                Log.i(TAG, "marker found: saved " + sMarkerSaved + ", dim " + sMarkerDim
                        + ", age " + (System.currentTimeMillis() - p.getLong(K_TS, 0)) + " ms");
            }
            sMarkerLoaded = true;
        } catch (Throwable t) {
            Log.w(TAG, "load marker: " + t);
        }
    }

    /** z9x-lamp: write-ahead (commit, synchronous). */
    private static void commitMarker(int saved, int dim) {
        try {
            prefs(sApp).edit().putInt(K_SAVED, saved).putInt(K_DIM, dim)
                    .putLong(K_TS, System.currentTimeMillis()).commit();
            sMarkerSaved = saved;
            sMarkerDim = dim;
        } catch (Throwable t) {
            Log.w(TAG, "commit marker: " + t);
        }
    }

    private static void writeMarker(int saved, int dim) {
        worker().post(() -> commitMarker(saved, dim));
    }

    private static void clearMarker() {
        try {
            prefs(sApp).edit().remove(K_SAVED).remove(K_DIM).remove(K_TS).commit();
            sMarkerSaved = 0;
            sMarkerDim = 0;
        } catch (Throwable t) {
            Log.w(TAG, "clear marker: " + t);
        }
    }

    private static void postClearMarker() {
        worker().post(DreamLamp::clearMarker);
    }

    // =================================================================== helpers

    /** IGmpf2 51: 1 = Boost on, 0 = off, -1 = unknown (never throws). z9x-hal. */
    private static int boostState(org.z9x.projector.hal.Gmpf2Client g2) {
        try {
            return g2 != null && g2.isBoost() ? 1 : (g2 == null ? -1 : 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Runnable once(Runnable r) {
        return new Runnable() {
            private boolean mRan;
            @Override public void run() {
                if (Thread.currentThread() != Ui.main().getLooper().getThread()) {
                    Ui.main().post(this);
                    return;
                }
                if (mRan) return;
                mRan = true;
                Ui.main().removeCallbacks(this);
                if (r != null) {
                    try { r.run(); } catch (Throwable t) { Log.w(TAG, "onDone: " + t); }
                }
            }
        };
    }
}
