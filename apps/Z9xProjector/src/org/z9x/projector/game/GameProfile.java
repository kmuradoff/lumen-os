package org.z9x.projector.game;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.Gmpf2Client;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.hal.KstPoint;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MODULE "game" (v6.2, requirement 5): recognise a game console on HDMI and apply the game settings
 * automatically, then restore the previous settings when the console leaves.
 *
 * <h3>Inputs</h3>
 * <ul>
 *   <li>HDMI session on screen: org.z9x.tvinput broadcasts {port, active} to {@link HdmiEventReceiver}
 *       whenever one of its HdmiSessions gets or loses its HDMI hardware (= a TvView plays HDMI 1/2),
 *       plus CEC device added/removed. Re-synced from its HdmiStateProvider (method hdmi_state,
 *       "ports") at process start, SCREEN_ON and cable hotplug, so a lost broadcast cannot leave the
 *       profile applied.</li>
 *   <li>CEC: HdmiControlManager.getConnectedDevices (SystemApi, HDMI_CEC) on our own thread
 *       "z9x-game", re-polled at +1/+3/+6 s after a session start, hotplug or CEC device change
 *       (AOSP adds a device with an unknown vendor and empty name first) and every 12 s while
 *       undecided; {@link ConsoleMatcher}. Hotplug via HdmiControlManager.HotplugEventListener.</li>
 *   <li>HAL reads (z9x-hal, every 3 s while an HDMI session is on screen and the screen is on, for the
 *       first 2 min of an undecided session or after a CEC / hotplug / screen-on / settings change;
 *       then every {@link #SLOW_TICK_MS}): IGmpf 662 getHdmiInfo (struct order UNVERIFIED: gated by
 *       plausible()), IGmpf2 310 getAllmStatus(port).</li>
 * </ul>
 *
 * <h3>Detection order</h3>
 * 1. the user marked this input as a console ("This input is a console");
 * 2. a CEC console behind the session's port (after 1 s);
 * 3. ALLM received for >= 2 s;
 * 4. a source at >= 100 Hz (plausible 662).
 * 2-4 only with "Auto game mode for consoles" on (default on). A 60 Hz console with CEC off and no
 * ALLM cannot be told from a streaming box: the per-input flag covers it.
 *
 * <h3>Apply</h3> (one Hal.run task, stock DisplayManager.setGameMode(1) order, research/v62):
 * gate (tvinput still reports a session on that port, read on z9x-game right before queuing; 696
 * getCurrentInputSource == that port, because the vendor keeps game type / state / option per input
 * source and writes them for its current source; 3D off); snapshot 647 and 55 once its layout is
 * known (see {@link #readGameState}; else the quick panel's persisted game mode). The snapshot is
 * committed on z9x-game BEFORE the second HAL task writes anything (write-ahead, no fsync on z9x-hal).
 * One snapshot slot per HDMI port (prefs keys suffixed "_1" / "_2"): the vendor state is per source,
 * so a console on HDMI 2 is handled while the restore of HDMI 1 still waits for HDMI 1.
 * 648: "120 Hz for consoles" (default on; G0082 support_hfr=true, VERIFIED) moves 0 BASIC to
 * 1 BASIC_HFR, so the vendor's EDID offers 120 Hz (UNVERIFIED on the device: what 648(1) does to the
 * EDID; device test). 3 TOP_SPEED never moves to 2 (2/3 need the 207 keystone reset); 2 stays.
 * 1 BASIC_HFR moves to 2 TOP_SPEED_HFR only when the input is >= 100 Hz AND the keystone is known
 * neutral (see {@link #keystoneNeutral}, UNVERIFIED) AND top speed is allowed (falls back to 1 when
 * 648(2) returns false). IGmpf2 56 MANUAL, 57 on; after a move to top speed 207
 * (resetKeystoneForTopSpeedGame, which re-checks 647; if it fails, 648 goes back to 1 so top speed
 * never runs without the keystone reset). 311/312 (ALLM auto switch) are
 * not touched: the vendor's 311 reads false whenever the type is not AUTO and the flag only acts in
 * AUTO, so with MANUAL + ON it has no effect (low latency is on already). The vendor closes Ultra 120 Hz
 * (force_120hz) when game mode turns on: the quick panel's "ultra_120" pref is cleared to match, and
 * 161(4) is re-sent once no profile is active on the source on screen (a profile whose restore waits
 * for its HDMI port does not count) and no HDMI session is open (the panel's own rules: 696 == 0,
 * tvinput reports no session, 3D off). Then the Play cue (+300 ms), or a Notify card when a panel is
 * open. A refusal with the same reason 3 times in a session stops detection until the next session or
 * settings change (one Notify: game settings unavailable on this input).
 *
 * <h3>Restore</h3> (silent): session inactive for > 3 s, port change, cable unplugged or the matched
 * CEC console removed (both debounced {@link #CHURN_DEBOUNCE_MS}, ignored for {@link #CHURN_GUARD_MS}
 * after our own apply / restore because the vendor switches the EDID by game mode, and followed by a
 * {@link #FLAP_HOLD_MS} hold on that port), auto mode turned off, or a stale applied flag at process
 * start / HAL connect with no session. Only while 696 reports the applied port (per-source state);
 * otherwise the restore stays pending (no attempt counted) and runs first when that port is on screen
 * again. Reverse order, compare-and-restore: each item is written back only while its read-back still
 * equals what we set (a change made in the quick panel wins). A failed restore is retried at the next
 * session start / HAL connect (3 attempts).
 *
 * Threads: state on the main thread; CEC and provider reads on "z9x-game"; HAL only through Hal.run /
 * Hal.query. Never applies on a non-HDMI source. Every entry point is wrapped (persistent process).
 */
public final class GameProfile {
    private static final String TAG = "Z9xGame";

    // ---- user settings (prefs z9x_game)
    static final String PREFS = "z9x_game";
    static final String KEY_AUTO = "auto_console";              // bool, default true
    private static final String KEY_TOP_SPEED = "top_speed";    // bool, default true (no UI yet)
    private static final String KEY_CONSOLE_PORT = "console_port_";  // + port: bool, default false

    /** "120 Hz for consoles": 0 BASIC -> 1 BASIC_HFR while the profile is applied. Default on. */
    private static final String KEY_HFR = "hfr_consoles";
    /** G0082 feature.xml support_hfr=true (VERIFIED, research v61/RESULT_display.json). */
    private static final boolean SUPPORT_HFR = true;

    // ---- applied snapshot, one slot per HDMI port: key + "_" + port (see k()). Committed on
    // z9x-game before any HAL write.
    private static final String K_ACTIVE = "applied";
    private static final String K_SET_OPT = "set_opt";
    private static final String K_PREV_OPT = "prev_opt";
    private static final String K_SET_ALLM = "set_allm";        // legacy (pre-fix builds): we turned 312 on
    private static final String K_DEFERRED = "restore_deferred"; // restore waits for 696 == applied port
    /** Survives the snapshot: Ultra 120 Hz was on before game mode closed it; re-sent after the session. */
    private static final String K_U120_PENDING = "u120_pending";
    /** Our own memory that the keystone is the full frame (default false = unknown, not neutral). */
    private static final String K_KST_NEUTRAL = "kst_neutral";
    private static final String K_PREV_STATE = "prev_state";    // MODE_*: game mode before us
    private static final String K_PREV_PANEL = "prev_panel";    // panel game_mode before us (-1 none)
    private static final String K_ATTEMPTS = "restore_attempts";
    /** IGmpf2 55 field order learned from a decisive reading (see readGameState). Global. */
    private static final String K_LAYOUT55 = "layout55";

    /** Shared with the quick panel (QuickPanel.PREFS / QuickPanelController.KEY_GAME_MODE): 0 off, 1 on, 2 auto. */
    private static final String PANEL_PREFS = "z9x_panel";
    private static final String PANEL_KEY_GAME_MODE = "game_mode";
    /** QuickPanelController.KEY_ULTRA_120: last Ultra 120 Hz value set (157 not readable). */
    private static final String PANEL_KEY_ULTRA_120 = "ultra_120";

    private static final int MODE_UNKNOWN = -1, MODE_OFF = 0, MODE_ON = 1, MODE_AUTO = 2;

    private static final long TICK_MS = 3_000;
    private static final long MONITOR_TICK_MS = 6_000;
    private static final long CEC_SETTLE_MS = 1_000;
    private static final long ALLM_HOLD_MS = 2_000;
    private static final long RESTORE_DEBOUNCE_MS = 3_000;
    /** Hotplug-disconnect / CEC console removal: an EDID switch pulses HPD, so wait longer. */
    private static final long CHURN_DEBOUNCE_MS = 8_000;
    /** Hotplug / CEC removal on the applied port this soon after our own apply / restore is ignored. */
    private static final long CHURN_GUARD_MS = 10_000;
    /** No new apply on a port for this long after a hotplug / CEC triggered restore there. */
    private static final long FLAP_HOLD_MS = 2 * 60_000L;
    /** Undecided sessions poll fast this long (from start, or the last CEC / hotplug / screen / setting change). */
    private static final long FAST_DETECT_MS = 2 * 60_000L;
    private static final long SLOW_TICK_MS = 24_000;
    private static final long U120_DELAY_MS = 4_000;
    private static final long CUE_DELAY_MS = 300;
    private static final long CUE_REPEAT_MS = 30 * 60_000L;
    private static final long[] CEC_REPOLL_MS = {1_000, 3_000, 6_000};
    private static final int CEC_TICK_EVERY = 4;                // CEC re-scan every 4th tick while undecided
    private static final int MAX_RESTORE_ATTEMPTS = 3;
    /** The same refusal this many times in a session stops detection until the next session / setting. */
    private static final int MAX_REFUSALS = 3;
    private static final int KST_TOLERANCE = 2;

    private static final String HDMI_STATE_URI = "content://org.z9x.tvinput.hdmistate";

    private static GameProfile sInstance;

    // ------------------------------------------------------------------ state (main thread)
    private final Context app;
    private final SharedPreferences prefs;
    private final SafeHandler main = Ui.main();
    private final SafeHandler game = SafeHandler.newThread("z9x-game");

    private boolean screenOn = true;
    /** 0 = no HDMI session on screen. Written on main only; volatile for the HAL-thread gate. */
    private volatile int sessionPort;
    private long sessionStart;
    private ConsoleMatcher.Match cec;        // console seen behind sessionPort
    private long allmSince;                  // first ALLM=true of the current run, 0 = none
    private boolean allmSeen;
    private GmpfClient.HdmiSignal signal;    // last plausible-or-not read of 662
    private String lastSignalLog;
    private int tickCount;
    private int hfrStreak;

    // ---- per HDMI port, index 1..2 (the vendor keeps game state per source: one slot per port)
    private final boolean[] applied = new boolean[3];          // mirror of K_ACTIVE_<port>
    private final int[] appliedOpt = {-1, -1, -1};
    private final boolean[] appliedByCec = new boolean[3];
    private final boolean[] restoreDeferred = new boolean[3];  // mirror of K_DEFERRED_<port>
    private final boolean[] restoreInFlight = new boolean[3];
    private final boolean[] restorePending = new boolean[3];   // restoreDebounced[port] is posted
    private final long[] restoreAt = new long[3];              // uptime of the posted restoreDebounced
    private final Runnable[] restoreDebounced = new Runnable[3];
    private boolean applyInFlight;
    private long holdUntil;                  // no new apply attempt before this (after a refusal)
    private String refusalKind;              // last refusal of this session (kind), null = none
    private int refusalCount;
    private boolean refusalStopped;          // MAX_REFUSALS reached: no detection this session
    private long upgradeHoldUntil;           // no new top-speed attempt before this
    private static final long UPGRADE_HOLD_MS = 5 * 60_000L;
    /** Bumps on every session change; stale results are dropped. Main writes, HAL-thread gate reads. */
    private volatile int gen;
    private final boolean[] cableDown = new boolean[3];   // per port, hotplug disconnected (not churn)
    private long cecLostAt;                  // the applied-by-CEC console vanished (0 = present)
    private long ownWriteAt;                 // last apply / restore / upgrade we did (EDID churn guard)
    private int flapPort;
    private long flapUntil;
    private long fastUntil;                  // undecided session: 3 s ticks until this uptime
    private boolean slowLogged;
    private boolean u120InFlight;

    private final Map<String, Long> cueShownAt = new HashMap<>();
    private String cueSessionKey;            // one cue per connection
    private final Set<String> devicesLogged = new HashSet<>();   // z9x-game thread only

    private final Runnable tick = () -> safe("tick", this::onTick);
    private final Runnable u120Task = () -> safe("ultra 120 back", this::maybeRestoreUltra120);
    private static final long REFUSED_HOLD_MS = 30_000;

    private GameProfile(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // (no migration of the pre-slot keys: that build never ran on the device)
        for (int p = 1; p <= 2; p++) {
            applied[p] = prefs.getBoolean(k(K_ACTIVE, p), false);
            appliedOpt[p] = prefs.getInt(k(K_SET_OPT, p), -1);
            restoreDeferred[p] = applied[p] && prefs.getBoolean(k(K_DEFERRED, p), false);
            final int port = p;
            restoreDebounced[p] = () -> safe("restore (debounced)", () -> {
                restorePending[port] = false;
                if (!applied[port]) return;
                String why = restoreReason(port);
                if (why == null) {
                    Log.i(TAG, "HDMI " + port + " is back (session, cable and console present): profile kept");
                    return;
                }
                restoreNow(port, why);
            });
        }
    }

    /** Snapshot key of HDMI {@code port}. */
    private static String k(String key, int port) {
        return key + "_" + port;
    }

    private static boolean validPort(int port) {
        return port == 1 || port == 2;
    }

    private boolean anyApplied() {
        return applied[1] || applied[2];
    }

    /**
     * A profile is active on a source that may be on screen: applied and its restore not waiting for
     * its own HDMI port (a deferred profile's source is not on screen, 696 said so).
     */
    private boolean activeProfileNotDeferred() {
        return (applied[1] && !restoreDeferred[1]) || (applied[2] && !restoreDeferred[2]);
    }

    private int attempts(int port) {
        return prefs.getInt(k(K_ATTEMPTS, port), 0);
    }

    // ================================================================== public API (main thread)
    /** App.onCreate. Cheap: no HAL call; registers receivers / listeners and resyncs the session. */
    public static void install(Context c) {
        if (sInstance != null) return;
        GameProfile p = new GameProfile(c);
        sInstance = p;
        p.start();
    }

    /** org.z9x.tvinput event (HdmiEventReceiver). Main thread. */
    static void onHdmiEvent(Context c, Intent i) {
        GameProfile p = sInstance;
        if (p == null || i == null) return;
        final int port = i.getIntExtra("port", 0);
        final boolean active = i.getBooleanExtra("active", false);
        final String reason = String.valueOf(i.getStringExtra("reason"));
        safe("hdmi event", () -> p.onEvent(port, active, reason));
    }

    /** "Auto game mode for consoles" (default on). */
    public static boolean isAutoEnabled(Context c) {
        return prefs(c).getBoolean(KEY_AUTO, true);
    }

    public static void setAutoEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_AUTO, on).apply();
        Log.i(TAG, "auto console mode " + on);
        GameProfile p = sInstance;
        if (p != null) safe("auto toggle", () -> p.onSettingsChanged());
    }

    /** "120 Hz for consoles" (default on): the profile offers HFR (648 0 -> 1). */
    public static boolean isHfrEnabled(Context c) {
        return prefs(c).getBoolean(KEY_HFR, true);
    }

    /** Takes effect at the next apply (an applied profile is not changed). */
    public static void setHfrEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_HFR, on).apply();
        Log.i(TAG, "120 Hz for consoles " + on);
    }

    /** HDMI port of the session on screen (1/2), 0 = none. Main thread. */
    public static int activePort() {
        GameProfile p = sInstance;
        return p == null ? 0 : p.sessionPort;
    }

    /** "This input is a console" for HDMI {@code port} (default off). */
    public static boolean isConsolePort(Context c, int port) {
        return port > 0 && prefs(c).getBoolean(KEY_CONSOLE_PORT + port, false);
    }

    public static void setConsolePort(Context c, int port, boolean on) {
        if (port <= 0) return;
        prefs(c).edit().putBoolean(KEY_CONSOLE_PORT + port, on).apply();
        Log.i(TAG, "HDMI " + port + " marked as console: " + on);
        GameProfile p = sInstance;
        if (p != null) safe("console flag", () -> p.onSettingsChanged());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Keystone memory for the top-speed decision (186 alone cannot tell a 3x3 keystone; stock reads
     * getKstMode, which is not whitelisted). Called by the manual keystone card after a 185 apply,
     * and internally on every AK / curtain focus event. Any thread.
     */
    public static void noteKeystoneChanged(Context c, String why) {
        try {
            SharedPreferences p = prefs(c);
            if (p.getBoolean(K_KST_NEUTRAL, false)) {
                p.edit().putBoolean(K_KST_NEUTRAL, false).apply();
                Log.i(TAG, "keystone changed (" + why + "): top speed needs a full-frame reset first");
            }
        } catch (Throwable t) {
            Log.w(TAG, "keystone note: " + t);
        }
    }

    /** The full frame was applied (quick panel reset, or 207 after top speed). Any thread. */
    public static void noteKeystoneFullFrame(Context c, String why) {
        try {
            prefs(c).edit().putBoolean(K_KST_NEUTRAL, true).apply();
            Log.i(TAG, "keystone full frame (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "keystone note: " + t);
        }
    }

    private static void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.e(TAG, what + " failed", t);
        }
    }

    // ================================================================== start
    private void start() {
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            screenOn = pm == null || pm.isInteractive();
        } catch (Throwable ignored) { }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                safe("screen", () -> onScreen(Intent.ACTION_SCREEN_ON.equals(i.getAction())));
            }
        }, f, Context.RECEIVER_EXPORTED);
        game.post(this::registerHotplug);
        // After every HAL (re)connect: a stale applied flag (crash, reboot) with no session is restored.
        Hal.addConnectedListener(() -> main.post(() -> safe("hal connected", this::onHalConnected)));
        // Any AK / curtain run (focus events 105..118, 120) may leave a keystone: forget "neutral".
        Hal.addFocusEventListener((type, value) -> {
            if ((type >= 105 && type <= 118) || type == 120) noteKeystoneChanged(app, "AK event " + type);
        });
        resync("start");
        try {
            org.z9x.projector.panel.QuickPanel.addExtension(org.z9x.projector.panel.QuickPanel.SECTION_GAME,
                    new GamePanelRows());
        } catch (Throwable t) {
            Log.w(TAG, "quick panel rows: " + t);
        }
        Log.i(TAG, "installed (applied HDMI1=" + applied[1] + (restoreDeferred[1] ? " deferred" : "")
                + " HDMI2=" + applied[2] + (restoreDeferred[2] ? " deferred" : "") + ")");
    }

    /** z9x-game thread. */
    private void registerHotplug() {
        try {
            HdmiControlManager m = app.getSystemService(HdmiControlManager.class);
            if (m == null) {
                Log.w(TAG, "no HdmiControlManager: hotplug not watched");
                return;
            }
            m.addHotplugEventListener(game::post, ev -> {
                final int port = ev.getPort();
                final boolean connected = ev.isConnected();
                main.post(() -> safe("hotplug", () -> onHotplug(port, connected)));
            });
            Log.i(TAG, "HDMI hotplug listener registered");
        } catch (Throwable t) {
            Log.w(TAG, "hotplug listener: " + t);
        }
    }

    // ================================================================== events (main thread)
    private void onEvent(int port, boolean active, String reason) {
        Log.i(TAG, "tvinput event port=" + port + " active=" + active + " reason=" + reason);
        if (port < 1 || port > 2) return;
        if (reason.startsWith("cec")) {
            if (port == sessionPort) {
                boostPolling("CEC change");
                scheduleCecPolls();
            }
            return;
        }
        if (active) onSessionStart(port, reason);
        else onSessionEnd(port, reason);
    }

    private void onSessionStart(int port, String why) {
        if (port == sessionPort) return;
        gen++;
        if (sessionPort != 0) Log.i(TAG, "session moved HDMI " + sessionPort + " -> " + port);
        cancelScheduledRestore(port);                 // this port is back: decided below
        holdUntil = 0;
        resetRefusals();
        // The other port's profile: restore it now (it defers unless 696 still reads that port). Its
        // slot is independent, so detection on this port goes on meanwhile.
        for (int q = 1; q <= 2; q++) {
            if (q == port || !applied[q]) continue;
            if (restoreDeferred[q]) {
                Log.i(TAG, "restore for HDMI " + q + " stays pending until it is on screen");
            } else {
                restoreNow(q, "port change " + q + " -> " + port);
            }
        }
        boolean restoreFirst = false;
        if (validPort(port) && applied[port] && (restoreDeferred[port] || attempts(port) > 0)) {
            restoreFirst = true;                      // pending / failed restore: undo first, then detect
        } else if (validPort(port) && applied[port]) {
            Log.i(TAG, "HDMI " + port + " back within the debounce: profile kept");
        }
        sessionPort = port;
        if (restoreFirst) restoreNow(port, restoreDeferred[port] ? "pending restore" : "retry failed restore");
        sessionStart = SystemClock.uptimeMillis();
        upgradeHoldUntil = 0;
        cec = null;
        allmSince = 0;
        allmSeen = false;
        signal = null;
        hfrStreak = 0;
        tickCount = 0;
        cueSessionKey = null;
        cecLostAt = 0;
        fastUntil = sessionStart + FAST_DETECT_MS;
        slowLogged = false;
        Log.i(TAG, "HDMI " + port + " session on screen (" + why + ")");
        scheduleCecPolls();
        restartTick(1_500);
    }

    private void onSessionEnd(int port, String why) {
        if (port != sessionPort) {
            if (validPort(port) && applied[port] && sessionPort == 0) scheduleRestore(port, why, RESTORE_DEBOUNCE_MS);
            return;
        }
        gen++;
        Log.i(TAG, "HDMI " + port + " session closed (" + why + ")");
        sessionPort = 0;
        cec = null;
        main.removeCallbacks(tick);
        if (validPort(port) && applied[port]) scheduleRestore(port, why, RESTORE_DEBOUNCE_MS);
        else scheduleUltra120(why);
    }

    private void onHotplug(int port, boolean connected) {
        Log.i(TAG, "hotplug HDMI " + port + " connected=" + connected);
        boolean known = port >= 1 && port < cableDown.length;
        if (!connected) {
            if (known && applied[port] && SystemClock.uptimeMillis() - ownWriteAt < CHURN_GUARD_MS) {
                // the vendor switches the EDID by game mode, which pulses HPD: not an unplug
                Log.i(TAG, "hotplug HDMI " + port + " within " + CHURN_GUARD_MS + " ms of our own change: ignored");
                return;
            }
            if (port == sessionPort) {
                cec = null;
                allmSince = 0;
                signal = null;
            }
            if (known) cableDown[port] = true;
            if (known && applied[port]) scheduleRestore(port, "cable unplugged", CHURN_DEBOUNCE_MS);
        } else {
            if (known) cableDown[port] = false;
            if (port == sessionPort && refusalStopped) {
                resetRefusals();                       // a device was (re)connected: try again
                restartTick(1_000);
            }
            boostPolling("hotplug");
            resync("hotplug");
            if (port == sessionPort) scheduleCecPolls();
        }
    }

    private void onScreen(boolean on) {
        screenOn = on;
        if (!on) {
            main.removeCallbacks(tick);
            PlayCueView.hide();
        } else {
            fastUntil = SystemClock.uptimeMillis() + FAST_DETECT_MS;
            resync("screen on");
            if (sessionPort != 0) restartTick(1_500);
            else scheduleUltra120("screen on");
        }
    }

    private void onHalConnected() {
        if (sessionPort == 0) {
            if (anyApplied()) resync("hal connected");
            scheduleUltra120("hal connected");           // no-op while a profile is active on screen
            return;
        }
        for (int p = 1; p <= 2; p++) {
            if (!applied[p]) continue;
            if (p == sessionPort) {
                if (restoreDeferred[p] || attempts(p) > 0) restoreNow(p, "hal connected");
            } else if (!restoreDeferred[p]) {
                restoreNow(p, "hal connected");          // defers unless 696 still reads that port
            }
        }
    }

    private void onSettingsChanged() {
        final int sp = sessionPort;
        if (validPort(sp) && applied[sp] && !wantedFor(sp)) {
            restoreNow(sp, "turned off by the user");
        } else if (sp != 0) {
            resetRefusals();
            holdUntil = 0;
            fastUntil = SystemClock.uptimeMillis() + FAST_DETECT_MS;
            restartTick(200);
        }
    }

    private void resetRefusals() {
        refusalKind = null;
        refusalCount = 0;
        refusalStopped = false;
    }

    /** An event that may change the detection result: back to fast polling for a while. */
    private void boostPolling(String why) {
        long now = SystemClock.uptimeMillis();
        boolean wasSlow = now >= fastUntil;
        fastUntil = now + FAST_DETECT_MS;
        if (wasSlow && validPort(sessionPort) && !applied[sessionPort] && !refusalStopped) {
            Log.i(TAG, "detection polling back to " + TICK_MS / 1000 + " s (" + why + ")");
            slowLogged = false;
            restartTick(1_000);
        }
    }

    /** Auto mode on, or this input was marked as a console. */
    private boolean wantedFor(int port) {
        return isAutoEnabled(app) || isConsolePort(app, port);
    }

    // ================================================================== resync with tvinput
    private void resync(String why) {
        game.post(() -> {
            final int[] ports = readSessionPorts(app);
            main.post(() -> safe("resync", () -> onResync(ports, why)));
        });
    }

    private void onResync(int[] ports, String why) {
        if (ports == null) {
            Log.i(TAG, "resync (" + why + "): tvinput state unknown");
            return;
        }
        boolean cur = sessionPort != 0 && contains(ports, sessionPort);
        if (sessionPort != 0 && !cur) onSessionEnd(sessionPort, "resync " + why);
        if (sessionPort == 0 && ports.length > 0) onSessionStart(ports[0], "resync " + why);
        for (int p = 1; p <= 2; p++) {
            if (p != sessionPort && applied[p] && !restoreInFlight[p] && !restorePending[p] && !restoreDeferred[p]) {
                scheduleRestore(p, "stale profile (" + why + ")", RESTORE_DEBOUNCE_MS);   // a session may be re-opening
            }
        }
    }

    /**
     * org.z9x.tvinput HdmiStateProvider: ports with an open HDMI session, {} when tvinput is not
     * installed, null when unknown. Worker threads only (z9x-game, z9x-hal).
     */
    static int[] readSessionPorts(Context c) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null;
        try {
            if (c.getPackageManager().resolveContentProvider("org.z9x.tvinput.hdmistate", 0) == null) {
                return new int[0];
            }
            Bundle b = c.getContentResolver().call(Uri.parse(HDMI_STATE_URI), "hdmi_state", null, null);
            if (b == null) return null;
            int[] p = b.getIntArray("ports");
            if (p != null) return p;
            return b.getBoolean("active", false) ? null : new int[0];
        } catch (Throwable t) {
            Log.w(TAG, "hdmi_state: " + t);
            return null;
        }
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    // ================================================================== CEC (z9x-game thread)
    private void scheduleCecPolls() {
        final int port = sessionPort;
        final int g = gen;
        if (port == 0) return;
        for (long d : CEC_REPOLL_MS) game.postDelayed(() -> pollCec(port, g), d);
    }

    private void pollCec(final int port, final int g) {
        List<HdmiDeviceInfo> list = null;
        try {
            HdmiControlManager m = app.getSystemService(HdmiControlManager.class);
            if (m != null) list = m.getConnectedDevices();
        } catch (Throwable t) {
            Log.w(TAG, "getConnectedDevices: " + t);
        }
        if (list != null) {
            for (HdmiDeviceInfo d : list) {
                String line = ConsoleMatcher.describe(d);
                if (devicesLogged.add(line)) Log.i(TAG, "CEC device " + line);
            }
            if (devicesLogged.size() > 64) devicesLogged.clear();
        }
        final boolean known = list != null;
        final ConsoleMatcher.Match m = ConsoleMatcher.find(list, port);
        main.post(() -> safe("cec result", () -> onCec(port, g, known, m)));
    }

    private void onCec(int port, int g, boolean listKnown, ConsoleMatcher.Match m) {
        if (g != gen || port != sessionPort) return;
        if (m != null) {
            if (cec == null || !cec.key().equals(m.key())) Log.i(TAG, "console on HDMI " + port + ": " + m);
            if (cecLostAt != 0) Log.i(TAG, "console back on HDMI " + port + ": restore canceled");
            cec = m;
            cecLostAt = 0;
            evaluate();
        } else if (listKnown && cec != null) {
            boolean ours = validPort(port) && applied[port] && appliedByCec[port];
            if (ours && SystemClock.uptimeMillis() - ownWriteAt < CHURN_GUARD_MS) {
                // AOSP drops the CEC devices behind a port on an HPD pulse (our own EDID switch)
                Log.i(TAG, "console " + cec + " not listed within " + CHURN_GUARD_MS + " ms of our own change: ignored");
                return;
            }
            Log.i(TAG, "console " + cec + " no longer listed on HDMI " + port);
            cec = null;
            if (ours) {
                cecLostAt = SystemClock.uptimeMillis();
                scheduleRestore(port, "console removed", CHURN_DEBOUNCE_MS);
            }
        }
    }

    // ================================================================== polling (main thread)
    private void restartTick(long delay) {
        main.removeCallbacks(tick);
        if (sessionPort != 0 && screenOn) main.postDelayed(tick, delay);
    }

    private void onTick() {
        final int port = sessionPort;
        if (!validPort(port) || !screenOn) return;
        final int g = gen;
        tickCount++;
        boolean monitoring = applied[port];
        if (!monitoring && !wantedFor(port)) return;            // nothing to detect: no polling at all
        if (!monitoring && refusalStopped) return;              // refused MAX_REFUSALS times: until next session
        // (a profile of the OTHER port that waits for its restore has its own slot: detection goes on)
        if (monitoring && restoreDeferred[port]) {
            if (!restoreInFlight[port]) restoreNow(port, "pending restore");
            main.postDelayed(tick, MONITOR_TICK_MS);
            return;
        }
        boolean slow = !monitoring && SystemClock.uptimeMillis() >= fastUntil;
        if (slow && !slowLogged) {
            slowLogged = true;
            Log.i(TAG, "HDMI " + port + " undecided for " + FAST_DETECT_MS / 1000 + " s: polling every "
                    + SLOW_TICK_MS / 1000 + " s");
        }
        main.postDelayed(tick, monitoring ? MONITOR_TICK_MS : slow ? SLOW_TICK_MS : TICK_MS);

        // Cross-check with tvinput (covers a lost "session closed" broadcast).
        int every = slow ? 2 : CEC_TICK_EVERY;
        if (monitoring || tickCount % every == 0) resync("tick");
        if (!monitoring && cec == null && tickCount % every == 0) game.post(() -> pollCec(port, g));

        final boolean wantSignal = !monitoring || (appliedOpt[port] == GmpfClient.GameModeOption.BASIC_HFR.wire && topSpeedAllowed()
                && upgradeHoldUntil != Long.MAX_VALUE);
        if (!wantSignal) return;
        final boolean wantAllm = !monitoring && isAutoEnabled(app);
        Hal.query((gm, g2) -> {
            Object[] r = new Object[2];
            try { r[0] = gm.getHdmiInfo(); } catch (Throwable t) { Log.d(TAG, "662: " + t); }
            if (wantAllm) {
                try { r[1] = g2.getAllmStatus(port); } catch (Throwable t) { Log.d(TAG, "310: " + t); }
            }
            return r;
        }, r -> {
            if (r == null || g != gen || port != sessionPort) return;
            onSignal(port, (GmpfClient.HdmiSignal) r[0], (Boolean) r[1]);
        });
    }

    private void onSignal(int port, GmpfClient.HdmiSignal sig, Boolean allm) {
        if (sig != null) {
            String line = sig.toString();
            if (!line.equals(lastSignalLog)) {
                lastSignalLog = line;
                Log.i(TAG, "HDMI " + port + " signal " + line + (sig.plausible() ? "" : " (implausible: ignored)"));
            }
            signal = sig;
        }
        if (allm != null) {
            if (allm) {
                if (allmSince == 0) {
                    allmSince = SystemClock.uptimeMillis();
                    Log.i(TAG, "HDMI " + port + " ALLM on");
                    boostPolling("ALLM");                     // confirm the 2 s hold without a slow tick
                }
                allmSeen = true;
            } else {
                if (allmSince != 0) Log.i(TAG, "HDMI " + port + " ALLM off");
                allmSince = 0;
            }
        }
        if (validPort(port) && applied[port]) {
            maybeUpgradeToTopSpeed(port);
        } else {
            evaluate();
        }
    }

    // ================================================================== decision (main thread)
    private static final int TRIGGER_MANUAL = 1, TRIGGER_CEC = 2, TRIGGER_ALLM = 3, TRIGGER_HFR = 4;

    private void evaluate() {
        final int port = sessionPort;
        if (!validPort(port) || !screenOn || applied[port] || applyInFlight || restoreInFlight[port] || refusalStopped) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now < holdUntil) return;
        if (port == flapPort && now < flapUntil) return;         // restored by hotplug / CEC: hold
        if (!KeyReceiver.isSetupComplete(app)) return;
        boolean auto = isAutoEnabled(app);
        int trigger = 0;
        if (isConsolePort(app, port)) {
            trigger = TRIGGER_MANUAL;
        } else if (auto && cec != null && now - sessionStart >= CEC_SETTLE_MS) {
            trigger = TRIGGER_CEC;
        } else if (auto && allmSince != 0 && now - allmSince >= ALLM_HOLD_MS) {
            trigger = TRIGGER_ALLM;
        } else if (auto && signal != null && signal.isHighFrameRate()) {
            trigger = TRIGGER_HFR;
        }
        if (trigger != 0) apply(port, trigger);
    }

    private boolean topSpeedAllowed() {
        return prefs.getBoolean(KEY_TOP_SPEED, true);
    }

    // ================================================================== apply
    private static final class Outcome {
        boolean ok;
        String refused;
        String refusedKind;      // stable reason key for the refusal cap
        int opt = -1;
        boolean top;

        void refuse(String kind, String why) {
            refusedKind = kind;
            refused = why;
        }
    }

    /** Values read by the first HAL task of an apply (z9x-hal), committed on z9x-game. */
    private static final class Snap {
        GmpfClient.GameModeOption prevOpt, opt;
        int prevState, prevPanel;
    }

    /**
     * Three steps, so the write-ahead snapshot is committed (fsync) off the FIFO z9x-hal thread:
     * z9x-game (tvinput provider read) -> z9x-hal (gate + reads) -> z9x-game (commit the snapshot) ->
     * z9x-hal (re-check gate + writes) -> main (onApplied).
     */
    private void apply(final int port, final int trigger) {
        applyInFlight = true;
        final int g = gen;
        final GmpfClient.HdmiSignal sig = signal;
        final boolean top = topSpeedAllowed();
        final boolean hfr = isHfrEnabled(app);
        final ConsoleMatcher.Match match = cec;
        Log.i(TAG, "apply on HDMI " + port + " trigger=" + trigger + " console=" + match + " signal=" + sig);
        // The tvinput provider read (cross-process, may cold-start it) runs here on z9x-game, never on
        // the FIFO z9x-hal thread, so a slow tvinput cannot hold up lamp-off / keystone work.
        game.post(() -> {
            final int[] ports = readSessionPorts(app);
            // runDelayed(.., 0, ifSkipped): Hal.run would drop the task silently while the HAL is not
            // connected, and applyInFlight would never clear.
            boolean queued = Hal.runDelayed((gm, g2) -> {
                final Outcome o = new Outcome();
                Snap snap = null;
                try {
                    snap = readForApplyOnHal(gm, g2, port, g, ports, sig, top, hfr, o);
                } catch (Throwable t) {
                    Log.w(TAG, "apply (read): " + t);
                    if (o.refused == null) o.refuse("read", "HAL read failed: " + t);
                }
                if (snap == null) {
                    if (o.refused == null) o.refuse("read", "no snapshot");
                    postApplied(port, g, trigger, match, sig, o);
                    return;
                }
                final Snap sn = snap;
                game.post(() -> commitAndWrite(sn, port, g, trigger, match, sig, o));
            }, 0, () -> main.post(() -> {
                applyInFlight = false;
                Log.w(TAG, "apply skipped: HAL not connected");
            }));
            if (!queued) {
                main.post(() -> {
                    applyInFlight = false;
                    Log.w(TAG, "apply not queued (HAL not ready)");
                });
            }
        });
    }

    private void postApplied(int port, int g, int trigger, ConsoleMatcher.Match match, GmpfClient.HdmiSignal sig,
                             Outcome o) {
        main.post(() -> safe("applied", () -> onApplied(port, g, trigger, match, sig, o)));
    }

    /** z9x-game: drops a snapshot that was committed but never acted on (nothing was written). */
    private void clearSnapshot(int port) {
        prefs.edit().putBoolean(k(K_ACTIVE, port), false).commit();
    }

    /** z9x-game: write-ahead snapshot (commit), then the HAL writes. */
    private void commitAndWrite(Snap sn, int port, int g, int trigger, ConsoleMatcher.Match match,
                                GmpfClient.HdmiSignal sig, Outcome o) {
        boolean saved = false;
        try {
            saved = prefs.edit()
                    .putBoolean(k(K_ACTIVE, port), true)
                    .putInt(k(K_SET_OPT, port), sn.opt.wire).putInt(k(K_PREV_OPT, port), sn.prevOpt.wire)
                    .putBoolean(k(K_SET_ALLM, port), false).putInt(k(K_PREV_STATE, port), sn.prevState)
                    .putInt(k(K_PREV_PANEL, port), sn.prevPanel).putInt(k(K_ATTEMPTS, port), 0)
                    .putBoolean(k(K_DEFERRED, port), false)
                    .commit();
        } catch (Throwable t) {
            Log.w(TAG, "snapshot: " + t);
        }
        if (!saved) {
            clearSnapshot(port);
            o.refuse("snapshot", "snapshot not saved");
            postApplied(port, g, trigger, match, sig, o);
            return;
        }
        Log.i(TAG, "snapshot HDMI " + port + " 647=" + sn.prevOpt + " state=" + sn.prevState + " panel=" + sn.prevPanel
                + " -> 648=" + sn.opt);
        boolean queued = Hal.runDelayed((gm, g2) -> {
            try {
                writeOnHal(gm, g2, port, g, sn, o);
            } catch (Throwable t) {
                Log.w(TAG, "apply: " + t);
            }
            if (o.refused != null) {
                game.post(() -> {                      // nothing written: drop the snapshot first
                    clearSnapshot(port);
                    postApplied(port, g, trigger, match, sig, o);
                });
            } else {
                postApplied(port, g, trigger, match, sig, o);
            }
        }, 0, () -> game.post(() -> {
            clearSnapshot(port);
            main.post(() -> {
                applyInFlight = false;
                Log.w(TAG, "apply skipped: HAL not connected");
            });
        }));
        if (!queued) {
            clearSnapshot(port);
            main.post(() -> {
                applyInFlight = false;
                Log.w(TAG, "apply not queued (HAL not ready)");
            });
        }
    }

    /** 696 value of HDMI {@code port} (GmpfClient.SOURCE_HDMI1/2). */
    private static int sourceOf(int port) {
        return port == 1 ? GmpfClient.SOURCE_HDMI1 : port == 2 ? GmpfClient.SOURCE_HDMI2 : Integer.MIN_VALUE;
    }

    /** HAL thread: 696, or -1 when unreadable. */
    private static int readSource(GmpfClient gm) {
        try {
            return gm.getCurrentInputSource();
        } catch (Throwable t) {
            Log.d(TAG, "696: " + t);
            return -1;
        }
    }

    /** z9x-hal thread, read only: the gate and the snapshot values. null (with o.refused) = no apply. */
    private Snap readForApplyOnHal(GmpfClient gm, Gmpf2Client g2, int port, int g, int[] ports,
                                   GmpfClient.HdmiSignal sig, boolean topAllowed, boolean hfr, Outcome o)
            throws Exception {
        // ---- gate: same session (cheap in-process state), tvinput still reports a session on this
        // port (read on z9x-game just before), and the vendor's current source (696) is that port: the
        // vendor stores game type / state / option per source and writes them for m_eCurrInputSrc, so
        // this is also what keeps every write off a non-HDMI source.
        if (g != gen || port != sessionPort) {
            o.refuse("session", "session changed before the HAL task ran");
            return null;
        }
        if (!contains(ports, port)) {
            o.refuse("tvinput", "no HDMI " + port + " session (tvinput " + java.util.Arrays.toString(ports) + ")");
            return null;
        }
        int src = readSource(gm);
        if (src != sourceOf(port)) {
            // UNVERIFIED: what 696 reports while our TIF plays HDMI (first game device test)
            o.refuse("696", "696=" + src + " is not HDMI " + port + " (vendor current source differs)");
            return null;
        }
        Log.i(TAG, "gate ok: tvinput session on HDMI " + port + ", 696=" + src);
        int fmt3d = gm.getCurrent3DFormat();                        // 192
        if (fmt3d != 1 || gm.is3DTo2DEnabled()) {                   // vendor refuses game mode in 3D
            o.refuse("3d", "3D active (192=" + fmt3d + ")");
            return null;
        }
        Snap sn = new Snap();
        sn.prevOpt = gm.getGameModeOption();                        // 647
        if (sn.prevOpt == null) {
            o.refuse("647", "647 unreadable");
            return null;
        }
        sn.prevState = readGameState(g2, sn.prevOpt.wire);
        SharedPreferences panel = app.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE);
        sn.prevPanel = panel.getInt(PANEL_KEY_GAME_MODE, -1);
        int other = port == 1 ? 2 : 1;
        if (prefs.getBoolean(k(K_ACTIVE, other), false)) {
            // the panel's game mode was set by the other port's profile: the user's value is in that snapshot
            sn.prevPanel = prefs.getInt(k(K_PREV_PANEL, other), -1);
        }
        if (sn.prevState == MODE_UNKNOWN && sn.prevPanel >= MODE_OFF && sn.prevPanel <= MODE_AUTO) {
            sn.prevState = sn.prevPanel;
        }

        // ---- option. 0 BASIC -> 1 BASIC_HFR with "120 Hz for consoles" (UNVERIFIED: 648(1) from 0 on
        // the device; the vendor switches the EDID by option). 3 never moves to 2 (2/3 need 207);
        // 1 BASIC_HFR moves to 2 TOP_SPEED_HFR only for a >= 100 Hz input with a neutral keystone.
        GmpfClient.GameModeOption opt = sn.prevOpt;
        if (opt == GmpfClient.GameModeOption.BASIC && hfr && SUPPORT_HFR) opt = GmpfClient.GameModeOption.BASIC_HFR;
        if (opt == GmpfClient.GameModeOption.BASIC_HFR && topAllowed && sig != null && sig.isHighFrameRate()
                && keystoneNeutral(gm, app)) {
            opt = GmpfClient.GameModeOption.TOP_SPEED_HFR;
        }
        sn.opt = opt;
        return sn;
    }

    /** z9x-hal thread, after the snapshot is committed: re-check the gate, then the writes. */
    private void writeOnHal(GmpfClient gm, Gmpf2Client g2, int port, int g, Snap sn, Outcome o) throws Exception {
        if (g != gen || port != sessionPort) {
            o.refuse("session", "session changed before the HAL writes");
            return;
        }
        int src = readSource(gm);
        if (src != sourceOf(port)) {
            o.refuse("696", "696=" + src + " is not HDMI " + port + " any more");
            return;
        }
        GmpfClient.GameModeOption prevOpt = sn.prevOpt;
        GmpfClient.GameModeOption now = gm.getGameModeOption();     // 647
        if (now != prevOpt) {
            o.refuse("647", "647 changed since the snapshot (" + prevOpt + " -> " + now + ")");
            return;
        }
        // ---- writes (stock setGameMode(1) order)
        GmpfClient.GameModeOption opt = sn.opt;
        boolean ok648 = opt == prevOpt || gm.setGameModeOption(opt);                       // 648
        if (!ok648 && opt.isTopSpeed() && !prevOpt.isTopSpeed()) {
            Log.i(TAG, "648(" + opt + ") refused: falling back to BASIC_HFR");
            opt = GmpfClient.GameModeOption.BASIC_HFR;
            ok648 = opt == prevOpt || gm.setGameModeOption(opt);
        }
        if (!ok648) {
            Log.w(TAG, "648(" + opt + ") returned false: option left at " + prevOpt);
            opt = prevOpt;
        }
        if (opt != sn.opt) prefs.edit().putInt(k(K_SET_OPT, port), opt.wire).apply();
        boolean ok56 = g2.setGameModeType(Gmpf2Client.GameModeType.MANUAL);                // 56(0)
        boolean ok57 = g2.setGameModeState(true);                                           // 57(0) = on
        // Mirrors right after 57, before 207: a failing 207 must not leave them out (the restore uses
        // the panel value while 55 is not trusted).
        SharedPreferences panel = app.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE);
        panel.edit().putInt(PANEL_KEY_GAME_MODE, MODE_ON).apply();
        if (ok57 && panel.getBoolean(PANEL_KEY_ULTRA_120, false)) {
            // the vendor closes force_120hz when game mode turns on: mirror it, give it back later
            prefs.edit().putBoolean(K_U120_PENDING, true).apply();
            panel.edit().putBoolean(PANEL_KEY_ULTRA_120, false).apply();
            Log.i(TAG, "Ultra 120 Hz closed by game mode: re-sent after the session");
        }
        boolean wantTop = opt.isTopSpeed() && !prevOpt.isTopSpeed();
        if (wantTop && !resetKeystoneAfterTopSpeed(gm, port)) {
            opt = GmpfClient.GameModeOption.fromWire(prefs.getInt(k(K_SET_OPT, port), opt.wire));
            if (opt == null) opt = prevOpt;
            wantTop = false;
        }
        Log.i(TAG, "applied HDMI " + port + " 648=" + opt + "(" + ok648 + ") 56=" + ok56 + " 57=" + ok57
                + (wantTop ? " 207" : ""));
        o.ok = ok57;
        o.opt = opt.wire;
        o.top = opt.isTopSpeed();
    }

    /**
     * z9x-hal: 207 after a move to top speed. If it fails, 648 goes back to BASIC_HFR (top speed must
     * never run without the keystone reset) and the snapshot's set option follows. True when 207 ran.
     */
    private boolean resetKeystoneAfterTopSpeed(GmpfClient gm, int port) {
        try {
            gm.resetKeystoneForTopSpeedGame();                                              // 207(0)
            noteKeystoneFullFrame(app, "207 after top speed");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "207 failed (" + t + "): 648 back to BASIC_HFR");
            try {
                if (gm.setGameModeOption(GmpfClient.GameModeOption.BASIC_HFR)) {
                    prefs.edit().putInt(k(K_SET_OPT, port), GmpfClient.GameModeOption.BASIC_HFR.wire).apply();
                } else {
                    Log.w(TAG, "648(BASIC_HFR) rollback returned false");
                }
            } catch (Throwable t2) {
                Log.w(TAG, "648 rollback: " + t2);
            }
            return false;
        }
    }

    private void onApplied(int port, int g, int trigger, ConsoleMatcher.Match match, GmpfClient.HdmiSignal sig,
                           Outcome o) {
        applyInFlight = false;
        applied[port] = prefs.getBoolean(k(K_ACTIVE, port), false);
        appliedOpt[port] = prefs.getInt(k(K_SET_OPT, port), -1);
        appliedByCec[port] = applied[port] && trigger == TRIGGER_CEC;
        if (o.refused != null) {
            onRefused(port, g, o);
            return;
        }
        if (!applied[port]) return;
        if (g == gen) resetRefusals();
        ownWriteAt = SystemClock.uptimeMillis();
        cableDown[port] = false;
        restoreDeferred[port] = false;
        cecLostAt = 0;
        refreshQuickPanel();
        if (g != gen || sessionPort != port) {
            Log.i(TAG, "session changed while applying: restoring");
            scheduleRestore(port, "session changed", RESTORE_DEBOUNCE_MS);
            return;
        }
        restartTick(MONITOR_TICK_MS);
        if (o.ok) main.postDelayed(() -> safe("cue", () -> showCue(port, g, trigger, match, sig, o.top)), CUE_DELAY_MS);
    }

    /** Main: a refused apply. The same reason MAX_REFUSALS times in a session stops detection. */
    private void onRefused(int port, int g, Outcome o) {
        if (g != gen || port != sessionPort || "session".equals(o.refusedKind)) {
            Log.i(TAG, "not applied: " + o.refused);
            if (sessionPort != 0) restartTick(TICK_MS);
            return;
        }
        if (o.refusedKind.equals(refusalKind)) {
            refusalCount++;
        } else {
            refusalKind = o.refusedKind;
            refusalCount = 1;
        }
        if (refusalCount >= MAX_REFUSALS) {
            refusalStopped = true;
            main.removeCallbacks(tick);
            Log.w(TAG, "not applied " + refusalCount + " times on HDMI " + port + " (" + o.refused
                    + "): detection stopped until the next session or settings change");
            if (screenOn) {
                try {
                    Notify.show(app, app.getString(R.string.game_unavailable));
                } catch (Throwable t) {
                    Log.w(TAG, "notify: " + t);
                }
            }
            return;
        }
        Log.i(TAG, "not applied: " + o.refused + " (" + refusalCount + "/" + MAX_REFUSALS + ", next try in "
                + REFUSED_HOLD_MS / 1000 + " s)");
        holdUntil = SystemClock.uptimeMillis() + REFUSED_HOLD_MS;
        restartTick(TICK_MS);
    }

    /** Main: the Game mode row of an open quick panel shows the new value (cheap; no-op when closed). */
    private void refreshQuickPanel() {
        try {
            org.z9x.projector.panel.QuickPanel.refreshIfShowing();
        } catch (Throwable t) {
            Log.w(TAG, "panel refresh: " + t);
        }
    }

    /**
     * HAL thread: keystone neutral, so 207 (top speed) cannot wipe a user keystone. UNVERIFIED and NOT
     * the stock isTrapzoid: stock first reads the keystone mode (getKstMode, not whitelisted) and in
     * 3x3 mode checks 9 grid points; 186 only gives the mode-0 corners, which can read full frame while
     * a 3x3 keystone is warped. So both must hold: our own memory says the full frame was applied
     * since the last manual keystone / AK run ({@link #noteKeystoneFullFrame}, default unknown = no),
     * AND 186 mode 0 equals the full frame. Unknown = not neutral (keeps BASIC_HFR).
     */
    private static boolean keystoneNeutral(GmpfClient gm, Context app) {
        if (!prefs(app).getBoolean(K_KST_NEUTRAL, false)) {
            Log.i(TAG, "keystone not known to be the full frame (no reset since the last keystone change): top speed not used");
            return false;
        }
        try {
            KstPoint p = gm.getKeystonePoints();
            KstPoint f = KstPoint.fullFrame();
            for (int c = 0; c < 4; c++) {
                if (Math.abs(p.x(c) - f.x(c)) > KST_TOLERANCE || Math.abs(p.y(c) - f.y(c)) > KST_TOLERANCE) {
                    Log.i(TAG, "keystone active " + p + ": top speed not used");
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "186 unreadable (" + t + "): keystone treated as active");
            return false;
        }
    }

    // ---- IGmpf2 55 (getGameModeProp): field ORDER UNVERIFIED. The research has two candidates:
    // v61/RESULT_display.json {gameModeOpt, state, type} and the AIDL twin in RESULT_quicksettings.json
    // {state, type, gameModeOpt}. With 647 = 0/1 the same bytes can fit both (e.g. [0,1,0]), so such a
    // reading proves nothing. Only a reading with 647 = 2 or 3 tells them apart (state / type are 0/1,
    // so the option field is unmistakable); the layout learned from it is kept in prefs. Until then 55
    // is not used (MODE_UNKNOWN: callers fall back to the quick panel's persisted value).
    private static final int L55_UNKNOWN = 0, L55_STATE_TYPE_OPT = 1, L55_OPT_STATE_TYPE = 2, L55_UNUSABLE = -1;
    /** Integer.MIN_VALUE = not loaded yet. Written on z9x-hal only. */
    private volatile int layout55 = Integer.MIN_VALUE;

    private static boolean bit(int v) {
        return v == 0 || v == 1;
    }

    /** HAL thread: MODE_* from 55 when its layout is known and the reading fits it, else MODE_UNKNOWN. */
    private int readGameState(Gmpf2Client g2, int opt647) {
        int layout = layout55;
        if (layout == Integer.MIN_VALUE) layout = layout55 = prefs.getInt(K_LAYOUT55, L55_UNKNOWN);
        if (layout == L55_UNUSABLE) return MODE_UNKNOWN;
        int[] p;
        try {
            p = g2.getGameModeProp();
        } catch (Throwable t) {
            layout55 = L55_UNUSABLE;                                  // this process only (not persisted)
            Log.i(TAG, "55 unreadable (" + t + "): using the persisted value");
            return MODE_UNKNOWN;
        }
        if (p == null || p.length < 3) return MODE_UNKNOWN;
        boolean sto = p[2] == opt647 && bit(p[0]) && bit(p[1]);      // {state, type, opt}
        boolean ost = p[0] == opt647 && bit(p[1]) && bit(p[2]);      // {opt, state, type}
        String raw = "{" + p[0] + "," + p[1] + "," + p[2] + "} vs 647=" + opt647;
        if (layout == L55_UNKNOWN) {
            if (opt647 != 2 && opt647 != 3) return MODE_UNKNOWN;     // ambiguous: wait for a decisive one
            if (sto == ost) {                                         // neither fits (both cannot here)
                layout55 = L55_UNUSABLE;
                Log.i(TAG, "55 = " + raw + ": fits neither layout, NOT trusted (persisted value used)");
                return MODE_UNKNOWN;
            }
            layout = sto ? L55_STATE_TYPE_OPT : L55_OPT_STATE_TYPE;
            layout55 = layout;
            prefs.edit().putInt(K_LAYOUT55, layout).apply();
            Log.i(TAG, "55 = " + raw + ": layout " + (sto ? "{state,type,opt}" : "{opt,state,type}") + " learned");
        }
        boolean fits = layout == L55_STATE_TYPE_OPT ? sto : ost;
        if (!fits) {
            Log.i(TAG, "55 = " + raw + " does not fit the learned layout: not used");
            return MODE_UNKNOWN;
        }
        int state = layout == L55_STATE_TYPE_OPT ? p[0] : p[1];       // 0 = on, 1 = off (as 57)
        int type = layout == L55_STATE_TYPE_OPT ? p[1] : p[2];        // 0 manual, 1 auto (as 56)
        if (type == 1) return MODE_AUTO;
        return state == 0 ? MODE_ON : MODE_OFF;
    }

    // ================================================================== top speed later (main thread)
    private void maybeUpgradeToTopSpeed(final int port) {
        GmpfClient.HdmiSignal sig = signal;
        if (!validPort(port) || appliedOpt[port] != GmpfClient.GameModeOption.BASIC_HFR.wire || !topSpeedAllowed()
                || applyInFlight || restoreInFlight[port]) {
            hfrStreak = 0;
            return;
        }
        hfrStreak = sig != null && sig.isHighFrameRate() ? hfrStreak + 1 : 0;
        if (hfrStreak < 2 || SystemClock.uptimeMillis() < upgradeHoldUntil) return;
        hfrStreak = 0;
        applyInFlight = true;
        final int g = gen;
        // provider read on z9x-game (never on the FIFO z9x-hal thread), then the HAL task
        game.post(() -> {
            final int[] ports = readSessionPorts(app);
            boolean queued = Hal.runDelayed((gm, g2) -> {
                boolean done = false, stop = false;
                try {
                    if (g != gen || port != sessionPort || !contains(ports, port)) return;
                    if (readSource(gm) != sourceOf(port)) return;          // per-source state: not this port now
                    GmpfClient.GameModeOption cur = gm.getGameModeOption();
                    if (cur != GmpfClient.GameModeOption.BASIC_HFR) { stop = true; return; }   // user changed it
                    // a quick-panel change wins: game mode must still be ON (55, else the panel's
                    // persisted value), and the vendor refuses game mode in 3D
                    int st = readGameState(g2, cur.wire);
                    if (st == MODE_UNKNOWN) {
                        st = app.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE)
                                .getInt(PANEL_KEY_GAME_MODE, MODE_UNKNOWN);
                    }
                    if (st != MODE_ON) {
                        Log.i(TAG, "top speed: game mode is " + st + " now (user change): no upgrade this session");
                        stop = true;
                        return;
                    }
                    if (gm.getCurrent3DFormat() != 1 || gm.is3DTo2DEnabled()) {
                        Log.i(TAG, "top speed: 3D on: no upgrade this session");
                        stop = true;
                        return;
                    }
                    if (!keystoneNeutral(gm, app)) return;
                    if (gm.setGameModeOption(GmpfClient.GameModeOption.TOP_SPEED_HFR)) {
                        // apply(): a crash before the write lands only makes the restore keep top speed
                        prefs.edit().putInt(k(K_SET_OPT, port), GmpfClient.GameModeOption.TOP_SPEED_HFR.wire).apply();
                        if (resetKeystoneAfterTopSpeed(gm, port)) {             // 207, else 648 back to 1
                            done = true;
                            Log.i(TAG, "input >= 100 Hz: top speed + HFR");
                        } else {
                            stop = true;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "top speed: " + t);
                } finally {
                    final boolean d = done, s = stop;
                    main.post(() -> {
                        applyInFlight = false;
                        appliedOpt[port] = prefs.getInt(k(K_SET_OPT, port), appliedOpt[port]);
                        if (d) {
                            ownWriteAt = SystemClock.uptimeMillis();
                            Log.i(TAG, "profile now top speed (gen " + g + ")");
                        } else {
                            // until the next session (onSessionStart resets it)
                            upgradeHoldUntil = s ? Long.MAX_VALUE : SystemClock.uptimeMillis() + UPGRADE_HOLD_MS;
                        }
                    });
                }
            }, 0, () -> main.post(() -> applyInFlight = false));
            if (!queued) main.post(() -> applyInFlight = false);
        });
    }

    // ================================================================== restore
    /** Debounced restore of HDMI {@code port}; a pending one is never moved earlier. Main thread. */
    private void scheduleRestore(int port, String why, long delayMs) {
        if (!validPort(port)) return;
        long now = SystemClock.uptimeMillis();
        long at = now + delayMs;
        if (restorePending[port] && restoreAt[port] > at) at = restoreAt[port];
        Log.i(TAG, "restore HDMI " + port + " in " + (at - now) + " ms (" + why + ")");
        main.removeCallbacks(restoreDebounced[port]);
        restorePending[port] = true;
        restoreAt[port] = at;
        main.postDelayed(restoreDebounced[port], at - now);
    }

    /** Why the profile of {@code port} must go now, or null when session, cable and console are all present. */
    private String restoreReason(int port) {
        if (sessionPort != port) return "session ended";
        if (cableDown[port]) return "cable unplugged";
        if (appliedByCec[port] && cec == null && cecLostAt != 0) return "console removed";
        return null;
    }

    private void cancelScheduledRestore(int port) {
        if (!validPort(port)) return;
        main.removeCallbacks(restoreDebounced[port]);
        restorePending[port] = false;
    }

    private void restoreNow(final int port, final String why) {
        if (!validPort(port) || !applied[port] || restoreInFlight[port]) return;
        cancelScheduledRestore(port);
        restoreInFlight[port] = true;
        if (port == sessionPort || sessionPort == 0) PlayCueView.hide();
        Log.i(TAG, "restore HDMI " + port + " (" + why + ")");
        if (why.equals("cable unplugged") || why.equals("console removed")) {
            flapPort = port;                              // an EDID/HPD loop must not re-apply at once
            flapUntil = SystemClock.uptimeMillis() + FLAP_HOLD_MS;
        }
        boolean queued = Hal.runDelayed((gm, g2) -> {
            int r = RESTORE_RETRY;
            try {
                r = restoreOnHal(gm, g2, port);
            } catch (Throwable t) {
                Log.w(TAG, "restore: " + t);
            }
            final int result = r;
            main.post(() -> safe("restored", () -> onRestored(port, result)));
        }, 0, () -> main.post(() -> {
            restoreInFlight[port] = false;         // retried after the next HAL connect (onHalConnected)
            Log.w(TAG, "restore skipped: HAL not connected");
        }));
        if (!queued) {
            restoreInFlight[port] = false;
            Log.w(TAG, "restore not queued (HAL not ready): retried at HAL connect");
        }
    }

    private static final int RESTORE_DONE = 0, RESTORE_DEFERRED = 1, RESTORE_RETRY = 2;

    /**
     * z9x-hal thread. Compare-and-restore in reverse order. RESTORE_DONE when nothing is left to undo,
     * RESTORE_DEFERRED when the vendor's current source (696) is not the applied port (its game state is
     * per source: reads and writes now would hit another source; no attempt counted), else RESTORE_RETRY.
     * Bookkeeping uses apply() (no fsync on z9x-hal): a lost write only repeats a harmless
     * compare-and-restore.
     */
    private int restoreOnHal(GmpfClient gm, Gmpf2Client g2, int port) throws Exception {
        if (!prefs.getBoolean(k(K_ACTIVE, port), false)) return RESTORE_DONE;
        int src = readSource(gm);
        if (src != sourceOf(port)) {
            prefs.edit().putBoolean(k(K_DEFERRED, port), true).apply();
            Log.i(TAG, "restore deferred: 696=" + src + " is not HDMI " + port + " (runs when it is on screen)");
            return RESTORE_DEFERRED;
        }
        boolean ok = true;
        // 312 (legacy snapshots only: this build never turns it on). 311 reads false whenever the type
        // is not AUTO, so it cannot gate this: write false unconditionally.
        if (prefs.getBoolean(k(K_SET_ALLM, port), false)) {
            try {
                ok &= g2.setAllmAutoSwitch(false);
            } catch (Throwable t) {
                Log.w(TAG, "restore 312: " + t);
                ok = false;
            }
        }
        // 56/57: only if game mode is still the "on, manual" we set
        int prevState = prefs.getInt(k(K_PREV_STATE, port), MODE_UNKNOWN);
        int prevPanel = prefs.getInt(k(K_PREV_PANEL, port), -1);
        SharedPreferences panel = app.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE);
        if (prevState != MODE_ON) {
            boolean stillOurs;
            GmpfClient.GameModeOption cur = null;
            try { cur = gm.getGameModeOption(); } catch (Throwable t) { Log.d(TAG, "647: " + t); }
            int st = cur == null ? MODE_UNKNOWN : readGameState(g2, cur.wire);
            if (st != MODE_UNKNOWN) {
                stillOurs = st == MODE_ON;
            } else {
                // 55 untrusted: the quick panel's persisted value still says "on" (= nobody changed it there)
                stillOurs = panel.getInt(PANEL_KEY_GAME_MODE, -1) == MODE_ON;
            }
            if (stillOurs) {
                try {
                    if (prevState == MODE_AUTO) {
                        ok &= g2.setGameModeType(Gmpf2Client.GameModeType.AUTO);          // 56(1)
                    } else {
                        // MODE_OFF, or UNKNOWN (assumption, labelled: off is the stock default)
                        ok &= g2.setGameModeType(Gmpf2Client.GameModeType.MANUAL);        // 56(0)
                        ok &= g2.setGameModeState(false);                                  // 57(1)
                    }
                    int panelBack = prevPanel >= MODE_OFF ? prevPanel : (prevState == MODE_AUTO ? MODE_AUTO : MODE_OFF);
                    panel.edit().putInt(PANEL_KEY_GAME_MODE, panelBack).apply();
                } catch (Throwable t) {
                    Log.w(TAG, "restore 56/57: " + t);
                    ok = false;
                }
            } else {
                Log.i(TAG, "game mode changed by the user meanwhile: kept");
            }
        }
        // 648: only if the option is still the one we set
        int setOpt = prefs.getInt(k(K_SET_OPT, port), -1);
        int prevOptW = prefs.getInt(k(K_PREV_OPT, port), -1);
        GmpfClient.GameModeOption prevOpt = GmpfClient.GameModeOption.fromWire(prevOptW);
        if (prevOpt != null && setOpt != prevOptW) {
            try {
                GmpfClient.GameModeOption cur = gm.getGameModeOption();
                if (cur != null && cur.wire == setOpt) {
                    boolean r = gm.setGameModeOption(prevOpt);
                    ok &= r;
                    if (r && prevOpt.isTopSpeed()) gm.resetKeystoneForTopSpeedGame();   // stock rule after 2/3
                } else {
                    Log.i(TAG, "game option changed by the user meanwhile (" + cur + "): kept");
                }
            } catch (Throwable t) {
                Log.w(TAG, "restore 648: " + t);
                ok = false;
            }
        }
        if (ok) {
            prefs.edit().putBoolean(k(K_ACTIVE, port), false).putInt(k(K_ATTEMPTS, port), 0)
                    .putBoolean(k(K_DEFERRED, port), false).apply();
            Log.i(TAG, "restored HDMI " + port);
            return RESTORE_DONE;
        }
        int attempts = prefs.getInt(k(K_ATTEMPTS, port), 0) + 1;
        if (attempts >= MAX_RESTORE_ATTEMPTS) {
            Log.w(TAG, "restore failed " + attempts + " times: giving up, profile flag cleared");
            prefs.edit().putBoolean(k(K_ACTIVE, port), false).putInt(k(K_ATTEMPTS, port), 0)
                    .putBoolean(k(K_DEFERRED, port), false).apply();
            return RESTORE_DONE;
        }
        prefs.edit().putInt(k(K_ATTEMPTS, port), attempts).putBoolean(k(K_DEFERRED, port), false).apply();
        Log.w(TAG, "restore incomplete (attempt " + attempts + "): retried later");
        return RESTORE_RETRY;
    }

    private void onRestored(int port, int result) {
        restoreInFlight[port] = false;
        applied[port] = prefs.getBoolean(k(K_ACTIVE, port), false);
        restoreDeferred[port] = applied[port] && result == RESTORE_DEFERRED;
        if (result != RESTORE_DEFERRED) ownWriteAt = SystemClock.uptimeMillis();
        if (!applied[port]) {
            appliedOpt[port] = -1;
            appliedByCec[port] = false;
            if (port == sessionPort || sessionPort == 0) cecLostAt = 0;
            refreshQuickPanel();
        }
        if (sessionPort != 0) {
            if (port == sessionPort) hfrStreak = 0;
            restartTick(TICK_MS);       // a console on the session's port is detected again
        } else {
            scheduleUltra120("restored");   // also after a deferral: that source is not on screen
        }
    }

    // ================================================================== Ultra 120 Hz back (main thread)
    private void scheduleUltra120(String why) {
        if (activeProfileNotDeferred() || !prefs.getBoolean(K_U120_PENDING, false)) return;
        Log.i(TAG, "Ultra 120 Hz back in " + U120_DELAY_MS + " ms if no HDMI is on screen (" + why + ")");
        main.removeCallbacks(u120Task);
        main.postDelayed(u120Task, U120_DELAY_MS);
    }

    /**
     * Gives back the Ultra 120 Hz the vendor closed when our profile turned game mode on, under the
     * quick panel's own rules for 161(4): no game profile active on the source on screen (a profile
     * whose restore waits for its HDMI port does not count: 696 reads media, and the vendor's game
     * state is per source), no HDMI session (tvinput reports none AND 696 == 0), 3D off. Tried once per
     * pending flag; a refusal leaves it off (the panel row shows off, which is then the truth). A value
     * set in the panel meanwhile wins.
     */
    private void maybeRestoreUltra120() {
        if (u120InFlight || activeProfileNotDeferred() || sessionPort != 0 || !screenOn
                || !prefs.getBoolean(K_U120_PENDING, false)) {
            return;
        }
        u120InFlight = true;
        game.post(() -> {
            final int[] ports = readSessionPorts(app);
            if (ports == null || ports.length != 0) {
                Log.i(TAG, "Ultra 120 Hz not given back yet: tvinput " + (ports == null ? "unknown" : "HDMI on screen"));
                main.post(() -> u120InFlight = false);
                return;
            }
            boolean queued = Hal.runDelayed((gm, g2) -> {
                try {
                    if (!prefs.getBoolean(K_U120_PENDING, false)) return;
                    for (int p = 1; p <= 2; p++) {
                        if (prefs.getBoolean(k(K_ACTIVE, p), false) && !prefs.getBoolean(k(K_DEFERRED, p), false)) return;
                    }
                    SharedPreferences panel = app.getSharedPreferences(PANEL_PREFS, Context.MODE_PRIVATE);
                    if (panel.getBoolean(PANEL_KEY_ULTRA_120, false)) {
                        prefs.edit().remove(K_U120_PENDING).apply();           // the user set it meanwhile
                        return;
                    }
                    int src = readSource(gm);
                    if (src != GmpfClient.SOURCE_MEDIA) {
                        Log.i(TAG, "Ultra 120 Hz not given back yet: 696=" + src);
                        return;
                    }
                    if (gm.getCurrent3DFormat() != 1 || gm.is3DTo2DEnabled()) {
                        Log.i(TAG, "Ultra 120 Hz not given back: 3D on");
                        prefs.edit().remove(K_U120_PENDING).apply();
                        return;
                    }
                    boolean ok = gm.setOutputTiming(GmpfClient.OutputTiming.ULTRA_120_ON);   // 161(4)
                    if (ok) panel.edit().putBoolean(PANEL_KEY_ULTRA_120, true).apply();
                    prefs.edit().remove(K_U120_PENDING).apply();
                    Log.i(TAG, "Ultra 120 Hz given back after game mode: 161(4) -> " + ok);
                } catch (Throwable t) {
                    Log.w(TAG, "Ultra 120 Hz back: " + t);
                } finally {
                    main.post(() -> u120InFlight = false);
                }
            }, 0, () -> main.post(() -> u120InFlight = false));
            if (!queued) main.post(() -> u120InFlight = false);
        });
    }

    // ================================================================== Play cue (main thread)
    private void showCue(int port, int g, int trigger, ConsoleMatcher.Match match, GmpfClient.HdmiSignal sig,
                         boolean top) {
        if (g != gen || port != sessionPort || !validPort(port) || !applied[port] || !screenOn) return;
        if (!KeyReceiver.isSetupComplete(app)) return;
        String key = match != null ? match.key() : ("port" + port + ":" + trigger);
        long now = SystemClock.uptimeMillis();
        Long last = cueShownAt.get(key);
        if (key.equals(cueSessionKey) || (last != null && now - last < CUE_REPEAT_MS)) return;
        cueSessionKey = key;
        cueShownAt.put(key, now);

        // The title always says "Game mode on" (the "ready to play" message); the console's name, when
        // known, is the first chip.
        String title = app.getString(R.string.game_cue_title);
        List<String> chips = new ArrayList<>(5);
        if (match != null && !match.osdName.isEmpty()) {
            chips.add(match.osdName);
        } else if (match != null) {
            chips.add(app.getString(match.brand == ConsoleMatcher.Brand.PLAYSTATION ? R.string.game_brand_playstation
                    : match.brand == ConsoleMatcher.Brand.XBOX ? R.string.game_brand_xbox : R.string.game_brand_nintendo));
        }
        chips.add(app.getString(R.string.game_chip_low_latency));
        if (sig != null && sig.plausible() && sig.frameRateCenti >= 11_000) {
            chips.add(app.getString(R.string.game_chip_hz, Math.round(sig.frameRateCenti / 100f)));
        }
        if (allmSeen) chips.add(app.getString(R.string.game_chip_allm));
        if (top) chips.add(app.getString(R.string.game_chip_top_speed));
        if (OverlayHost.get(app).current() != null) {
            // a panel is open (e.g. the user just marked this input as a console): the Notify card
            // confirms it without covering the panel
            Notify.show(app, title, android.text.TextUtils.join(" \u00b7 ", chips));
            Log.i(TAG, "Play cue as a notification (a panel is open): " + chips);
            return;
        }
        boolean shown = PlayCueView.show(app, title, chips);
        Log.i(TAG, "Play cue '" + title + "' " + chips + " shown=" + shown);
    }
}
