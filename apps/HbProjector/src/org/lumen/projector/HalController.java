package org.lumen.projector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.util.Log;

import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.hal.HbFeatures;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owner of every vendor HAL interaction of the app. Z9X HB fork (the design notes,
 * section 7) of the Z9X HalController: the transport is AidlCaller (14 stable AIDL services of one vendor process)
 * and the boot handshake is the HbBoot order proven on hb-t2. Everything else is the Z9X class.
 *
 * Threads: "z9x-hal" (handshake, reconnect, getters/setters, callback-driven work, Hal.run/query
 * of the modules), "z9x-motor" (MotorController: manualFocus + watchdog), "z9x-kst" (blocking
 * autoKst), "z9x-ak" (DISPLAY 68 acks of the AK overlay only, Hal.runAk).
 * Nothing here runs a HAL call on the main thread (the clients also refuse it).
 *
 * Focus events: the binder callback (AidlCaller.FocusCallback, oneway) posts the event to the MAIN thread
 * (dispatchEvent): AK events 105-118 and 120 go to AkOverlay.onFocusEvent first; then every
 * Hal.FocusEventListener sees the event; unless AkOverlay handled it, the old handling (AF toasts,
 * motor-limit stop, curtain hint) runs on the "z9x-hal" thread. The event ids are the Z9X ones (same libxgimi).
 *
 * Boot handshake (HbBoot order, devices/goat_elegant/stock analysis), once per process, from the start of
 * the persistent direct boot aware process (before unlock):
 *   connect SYSTEM, POWER, FOCUS (checkService every 500 ms, at most 120 s), version guard of each
 *   -> if manual_moving flag: manualFocus(2)
 *   R  FOCUS 1 setFocusCallback(cb)                     [Z9X 298 + 306]
 *   A  SYSTEM 32 setBootAnimState(1)  only while init.svc.bootanim is "running" and SYSTEM 33 is not 2
 *   B  SYSTEM 36 setSystemUIState(1)                    [Z9X 391]
 *   C  POWER 2 goToBootCompleted(0)                     [Z9X 237(0)]
 *   -> wait init.svc.bootanim != running (200 ms poll, max 180 s)
 *   D  SYSTEM 32 setBootAnimState(2)                    [Z9X 395]
 *   -> wait until the user is unlocked (500 ms poll, max 300 s)
 *   E  POWER 2 goToBootCompleted(1)                     [Z9X 237(1)]
 *   F  FOCUS 8 sendFocusStatus(17) LAST                 [Z9X 307(17); stock HB SystemUI FocusUIV2.start]
 *   -> SYSTEM 33 for the log, "boot handshake complete".
 * Once per boot per phase (early = A to C, late = D and E, F alone): stored with the kernel boot_id in device
 * protected storage after delivery (HbBoot keys); an unreadable boot_id sends anyway (fail open). Not delivered
 * (RemoteException): up to 5 tries 500 ms apart, reconnecting in between; delivered with a bad reply
 * (ReplyException, the guard included): never repeated. Nothing periodic. The Z9X lamp wait
 * (sys.boot_completed, vendor.xgimi.ledOn) is not used on the HB.
 *
 * Vendor death: reconnect with backoff, stop a flagged move, re-register R, then read SYSTEM 33 / 37; if 33 != 2
 * although we had set it (the vendor lost its state) resend B, D, C, E, but never F. After every (re)connect the
 * Hal.addConnectedListener hooks run (PowerPolicy: re-check the lamp).
 */
final class HalController {
    private static final String TAG = "Z9xHal";

    private static final long CONNECT_MAX_MS = 120_000, CONNECT_POLL_MS = 500;
    private static final long UNLOCK_MAX_MS = 300_000, UNLOCK_POLL_MS = 500;
    private static final int MAX_TRIES = 5;
    private static final long RETRY_MS = 500;
    /** Event 120: at most one offer per 10 s, none within 30 s after our own keystone, 5 per process. */
    private static final long CURTAIN_OFFER_GAP_MS = 10_000;
    private static final long CURTAIN_QUIET_AFTER_KST_MS = 30_000;
    private static final int MAX_CURTAIN_OFFERS = 5;
    private static final long BOOTANIM_POLL_MS = 200;
    private static final long BOOTANIM_MAX_WAIT_MS = 180_000;
    private static final long RECONNECT_MIN_MS = 500;
    private static final long RECONNECT_MAX_MS = 10_000;
    private static final long AF_DEBOUNCE_MS = 1_000;
    private static final long AF_BUSY_MS = 15_000;
    private static final int MAX_EVENTS = 20;
    private static final int MAX_RESTART_RESENDS = 3;

    /** HbBoot keys (BootSequence): the phase was delivered in the boot with this boot_id. */
    private static final String K_EARLY = "boot_id_early", K_LATE = "boot_id_late", K_17 = "boot_id_17";

    // Handshake stages
    private static final int ST_IDLE = 0, ST_CONNECT = 1, ST_EARLY = 2, ST_WAIT_ANIM = 3, ST_LATE = 4,
            ST_WAIT_UNLOCK = 5, ST_LATE2 = 6, ST_DONE = 7, ST_OFF = 8;

    /** Lumen OS 1.0.4: POWER_ON_MUSIC (IGmpf 420 / 419, the stock power-on music). */
    enum Toggle { POWER_ON_AF, POWER_ON_AK, MOVE_AF, MOVE_AK, CURTAIN_FIT, OBSTACLE, POWER_ON_MUSIC }

    interface ToggleResult {
        /** Main thread. Value null = unknown (not connected / error). */
        void onToggles(Map<Toggle, Boolean> values);
    }

    interface TextResult {
        void onText(String text);
    }

    private static volatile HalController sInstance;

    static synchronized HalController get(Context c) {
        if (sInstance == null) sInstance = new HalController(c.getApplicationContext());
        return sInstance;
    }

    /** The instance if App.onCreate created it, else null (used by the public Hal facade). */
    static HalController peek() {
        return sInstance;
    }

    /**
     * Former feature gate (sys.z9x.feat). v6.1 removed the kill switch completely
     * (v6.1 requirements item 13), so the projector features are always on. Kept as a method so the
     * existing call sites stay unchanged.
     */
    static boolean featureEnabled() {
        return true;
    }

    private final Context app;
    private final SafeHandler hal = SafeHandler.newThread("z9x-hal");
    private final SafeHandler kst = SafeHandler.newThread("z9x-kst");
    private final SafeHandler ak = SafeHandler.newThread("z9x-ak");
    private final GmpfClient client;
    private final Gmpf2Client client2 = new Gmpf2Client();
    private final SharedPreferences state;
    private final AtomicBoolean kstInFlight = new AtomicBoolean();
    private final MotorController motor;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> events = new ArrayDeque<>();

    // hal-thread state
    private boolean started;
    private volatile int stage = ST_IDLE;
    private long connectStart = -1;
    private int curtainOffers;
    private long animWaitStart = -1, unlockWaitStart = -1;
    private long reconnectDelay = RECONNECT_MIN_MS;
    private long generation;
    private boolean reconnecting;
    private boolean registered;
    private int registerRetries;
    private boolean sentUiState, sentBootAnim;
    private boolean doneA, doneB, doneC, doneD, doneE, done17;
    private boolean deliveredB, deliveredC, deliveredD, deliveredE;
    private int triesA, triesB, triesC, triesD, triesE, tries17;
    private int restartResends;
    private long lastCurtainEventAt = -100_000L;

    private volatile boolean connected;
    private volatile long afBusyUntil;
    /** elapsedRealtime when our last autoKst/newAutoKst returned (kst thread writes). */
    private volatile long lastKstEndAt = -100_000L;
    private long lastAfRequestAt = -100_000L;   // main thread

    /** Vendor death (AidlCaller: the first notice of a generation, binder thread): only post. */
    private final GmpfClient.DeathListener death = () -> {
        final long cookie = generation;
        hal.post(() -> onHalDied(cookie));
    };

    private final Runnable tick = this::tick;
    private final Runnable reconnectTick = this::reconnectTick;

    private HalController(Context app) {
        this.app = app;
        // device protected storage: the handshake runs before the user unlocks (direct boot)
        this.state = app.createDeviceProtectedStorageContext().getSharedPreferences("hal_state", Context.MODE_PRIVATE);
        // binder thread (oneway callback): only post.
        this.client = new GmpfClient((type, value) -> Ui.main().post(() -> dispatchEvent(type, value)));
        this.motor = new MotorController(app, client, state, kstInFlight);
    }

    MotorController motor() { return motor; }

    Context appContext() { return app; }

    /** 1.0.8: the user stopped the AF on the AF card (AfCardOverlay): the busy flag ends now. Any thread. */
    void noteAfEnded() { afBusyUntil = 0; }

    GmpfClient client() { return client; }

    Gmpf2Client client2() { return client2; }

    SafeHandler halHandler() { return hal; }

    SafeHandler akHandler() { return ak; }

    boolean isKeystoneInFlight() { return kstInFlight.get(); }

    boolean isConnected() { return connected; }

    /** v6.3.1: true once the boot handshake (incl. 237 goToBootCompleted(1)) is complete. Any thread. */
    boolean isHandshakeDone() { return stage == ST_DONE; }

    void addListener(Runnable r) { listeners.add(r); }

    void removeListener(Runnable r) { listeners.remove(r); }

    private void notifyListeners() {
        for (Runnable r : listeners) Ui.main().post(r);
    }

    // ================================================================== start / handshake
    /** Idempotent. Called from Application.onCreate and BOOT_COMPLETED. */
    void start() {
        hal.post(() -> {
            if (started) return;
            started = true;
            stage = ST_CONNECT;
            connectStart = SystemClock.elapsedRealtime();
            Log.i(TAG, "start: connecting (bootanim=" + prop("init.svc.bootanim") + ")");
            hal.post(tick);
        });
    }

    void onBootCompleted() {
        start();
    }

    String stageText() {
        switch (stage) {
            case ST_IDLE: return "not started";
            case ST_CONNECT: return "connecting to the HAL";
            case ST_EARLY: return "handshake (early part)";
            case ST_WAIT_ANIM: return "waiting for the boot animation to end";
            case ST_LATE: return "handshake (late part)";
            case ST_WAIT_UNLOCK: return "waiting for the user to unlock";
            case ST_LATE2: return "handshake (after unlock)";
            case ST_DONE: return "done";
            case ST_OFF: return "off";
            default: return "?";
        }
    }

    private void tick() {
        hal.removeCallbacks(tick);
        if (!featureEnabled()) { stage = ST_OFF; return; }
        long now = SystemClock.elapsedRealtime();
        switch (stage) {
            case ST_CONNECT: {
                if (!connectAndPrepare()) {
                    if (now - connectStart > CONNECT_MAX_MS) {
                        Log.e(TAG, "gmpf services not available after " + CONNECT_MAX_MS / 1000 + " s; still trying, slower");
                        hal.postDelayed(tick, nextBackoff());
                    } else {
                        hal.postDelayed(tick, CONNECT_POLL_MS);
                    }
                    return;
                }
                Log.i(TAG, "connected after " + (now - connectStart) + " ms");
                fireConnected();
                stage = ST_EARLY;
                hal.post(tick);
                return;
            }
            case ST_EARLY: {
                if (!connected) return;                       // reconnect resumes the tick
                String boot = bootId();
                if (boot != null && boot.equals(state.getString(K_EARLY, null))) {
                    Log.i(TAG, "early phase already sent for this boot");
                    doneA = doneB = doneC = true;
                    sentUiState = true;
                }
                if (!doneA && !stepA()) return;
                if (!doneB && !stepB()) return;
                if (!doneC && !stepC()) return;
                if (deliveredB && deliveredC) persistBoot(K_EARLY, boot);
                stage = ST_WAIT_ANIM;
                animWaitStart = now;
                hal.post(tick);
                return;
            }
            case ST_WAIT_ANIM: {
                boolean running = isBootanimRunning();
                if (running && now - animWaitStart < BOOTANIM_MAX_WAIT_MS) {
                    hal.postDelayed(tick, BOOTANIM_POLL_MS);
                    return;
                }
                if (running) Log.w(TAG, "bootanim still running after " + BOOTANIM_MAX_WAIT_MS + " ms, continuing");
                Log.i(TAG, "boot animation: " + prop("init.svc.bootanim") + " after " + (now - animWaitStart) + " ms");
                stage = ST_LATE;
                hal.post(tick);
                return;
            }
            case ST_LATE: {
                if (!connected) return;
                String boot = bootId();
                if (boot != null && boot.equals(state.getString(K_LATE, null))) {
                    Log.i(TAG, "late phase already sent for this boot");
                    doneD = doneE = true;
                    sentBootAnim = true;
                }
                if (!doneD && !stepD()) return;
                stage = ST_WAIT_UNLOCK;
                unlockWaitStart = now;
                hal.post(tick);
                return;
            }
            case ST_WAIT_UNLOCK: {
                UserManager um = app.getSystemService(UserManager.class);
                boolean unlocked = um == null || um.isUserUnlocked();
                if (!unlocked && now - unlockWaitStart < UNLOCK_MAX_MS) {
                    hal.postDelayed(tick, UNLOCK_POLL_MS);
                    return;
                }
                Log.i(TAG, "user unlocked: " + (um == null ? "?" : String.valueOf(um.isUserUnlocked())));
                stage = ST_LATE2;
                hal.post(tick);
                return;
            }
            case ST_LATE2: {
                if (!connected) return;
                if (!doneE && !stepE()) return;
                if (deliveredD && deliveredE) persistBoot(K_LATE, bootId());
                if (!done17 && !step17()) return;
                try {
                    Log.i(TAG, "33 getBootAnimState() -> " + client.getBootAnimState());
                } catch (Throwable t) {
                    Log.w(TAG, "33 getBootAnimState(): " + t);
                }
                stage = ST_DONE;
                Log.i(TAG, "boot handshake complete");
                notifyListeners();
                return;
            }
            default:
                // ST_DONE / ST_OFF / ST_IDLE: nothing to do
        }
    }

    private static boolean isBootanimRunning() {
        return "running".equals(prop("init.svc.bootanim"));
    }

    private long nextBackoff() {
        long d = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
        return d;
    }

    /** checkService + linkToDeath + guard of SYSTEM, POWER, FOCUS, stop a flagged move, register R. */
    private boolean connectAndPrepare() {
        ++generation;
        if (!client.connect(death)) return false;
        connected = true;
        reconnectDelay = RECONNECT_MIN_MS;
        registered = false;
        registerRetries = 0;
        motor.stopIfFlaggedAndWait(3_000);
        registerListener();
        notifyListeners();
        return true;
    }

    private void registerListener() {
        if (!connected || registered) return;
        try {
            client.connectFocusManager();                      // R: FOCUS 1 setFocusCallback
            client.setFocusListener();                         // folded on the HB: sends nothing
            registered = true;
            Log.i(TAG, "focus callback registered (FOCUS 1 setFocusCallback)");
        } catch (GmpfClient.ReplyException e) {
            registered = true;                                 // reached the vendor: do not send it again
            Log.w(TAG, "focus callback registration reply: " + e);
        } catch (Throwable t) {
            Log.w(TAG, "focus callback registration failed: " + t);
            if (connected && ++registerRetries <= 3) hal.postDelayed(this::registerListener, 1_000);
        }
    }

    /** The result of one handshake row: delivered (also with a bad reply) or given up. */
    private interface Row { String run() throws Exception; }

    /**
     * One handshake row. true = go on (delivered, or given up after MAX_TRIES); false = wait (a retry or the
     * reconnect re-posts the tick). Delivered with a bad reply (ReplyException, the guard included): never repeated.
     */
    private boolean row(String what, int attempt, Row r, Runnable delivered, Runnable done) {
        try {
            Log.i(TAG, what + " -> " + r.run());
            delivered.run();
            done.run();
            return true;
        } catch (GmpfClient.ReplyException e) {
            Log.w(TAG, what + " delivered or refused, not repeated: " + e.getMessage());
            delivered.run();
            done.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, what + " failed (" + attempt + "/" + MAX_TRIES + "): " + t);
            if (attempt >= MAX_TRIES) {
                Log.e(TAG, what + ": giving up for this process");
                done.run();
                return true;
            }
            if (connected) hal.postDelayed(tick, RETRY_MS);
            return false;
        }
    }

    /** A: SYSTEM 32 setBootAnimState(1), only while the boot animation runs and 33 is not 2 yet. */
    private boolean stepA() {
        if (!isBootanimRunning()) {
            Log.i(TAG, "A skipped: boot animation not running");
            doneA = true;
            return true;
        }
        int st;
        try {
            st = client.getBootAnimState();
            Log.i(TAG, "33 getBootAnimState() -> " + st);
        } catch (Throwable t) {
            Log.w(TAG, "33 getBootAnimState() before A: " + t + "; A skipped");
            doneA = true;
            return true;
        }
        if (st == 2) {
            doneA = true;
            return true;
        }
        return row("32 setBootAnimState(1)", ++triesA, () -> { client.setBootAnimRunning(); return "ok"; },
                () -> { }, () -> doneA = true);
    }

    /** B: SYSTEM 36 setSystemUIState(1). */
    private boolean stepB() {
        return row("36 setSystemUIState(1)", ++triesB, () -> { client.setSystemUiReady(); return "ok"; },
                () -> { deliveredB = true; sentUiState = true; }, () -> doneB = true);
    }

    /** C: POWER 2 goToBootCompleted(0). */
    private boolean stepC() {
        return row("2 goToBootCompleted(0)", ++triesC,
                () -> String.valueOf(client.goToBootCompleted(GmpfClient.BootStage.EARLY)),
                () -> deliveredC = true, () -> doneC = true);
    }

    /** D: SYSTEM 32 setBootAnimState(2). */
    private boolean stepD() {
        return row("32 setBootAnimState(2)", ++triesD, () -> { client.setBootAnimFinished(); return "ok"; },
                () -> { deliveredD = true; sentBootAnim = true; }, () -> doneD = true);
    }

    /** E: POWER 2 goToBootCompleted(1). */
    private boolean stepE() {
        return row("2 goToBootCompleted(1)", ++triesE,
                () -> String.valueOf(client.goToBootCompleted(GmpfClient.BootStage.AFTER_BOOTANIM)),
                () -> deliveredE = true, () -> doneE = true);
    }

    /** F: FOCUS 8 sendFocusStatus(17) LAST, once per boot_id (fail-open). */
    private boolean step17() {
        String boot = bootId();
        if (boot != null && boot.equals(state.getString(K_17, null))) {
            Log.i(TAG, "sendFocusStatus(17) already sent for this boot");
            done17 = true;
            return true;
        }
        return row("8 sendFocusStatus(17)", ++tries17, () -> { client.sendPowerOnUiReady(); return "ok"; },
                () -> persistBoot(K_17, boot), () -> done17 = true);
    }

    private void persistBoot(String key, String boot) {
        if (boot == null) return;                             // unreadable boot_id: never persist a placeholder
        if (!state.edit().putString(key, boot).commit()) Log.e(TAG, "could not persist " + key);
    }

    /** /proc/sys/kernel/random/boot_id, or null when unreadable (also exposed as Hal.bootId). */
    static String bootId() {
        try (InputStream in = new FileInputStream("/proc/sys/kernel/random/boot_id")) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) return null;
            String s = new String(buf, 0, n, StandardCharsets.US_ASCII).trim();
            return s.length() >= 16 ? s : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ================================================================== death / reconnect
    private void onHalDied(long cookie) {
        if (cookie != generation) return;                     // stale notice of an older binder
        Log.w(TAG, "vendor gmpf service died");
        connected = false;
        client.dropConnection();
        client2.dropConnection();                             // same state (one vendor process)
        motor.onHalDied();
        notifyListeners();
        if (stage >= ST_EARLY && stage <= ST_DONE) {
            reconnecting = true;
            reconnectDelay = RECONNECT_MIN_MS;
            hal.removeCallbacks(reconnectTick);
            hal.postDelayed(reconnectTick, RECONNECT_MIN_MS);
        } else if (stage == ST_CONNECT) {
            hal.post(tick);
        }
    }

    private void reconnectTick() {
        if (!reconnecting) return;
        if (!featureEnabled()) return;
        if (!connectAndPrepare()) {
            hal.postDelayed(reconnectTick, nextBackoff());
            return;
        }
        reconnecting = false;
        Log.i(TAG, "reconnected to the vendor gmpf service");
        checkVendorStateAfterReconnect();
        fireConnected();
        if (stage != ST_DONE) hal.post(tick);                 // resume an unfinished handshake
    }

    /** hal thread: Hal.addConnectedListener hooks (e.g. PowerPolicy re-checks the lamp). */
    private void fireConnected() {
        firedHooks.clear();
        for (Runnable r : Hal.CONNECT_LISTENERS) {
            firedHooks.add(r);
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "connected listener: " + t); }
        }
    }

    /**
     * Z9X HB fork (hb-t7): the hooks that ran on the last connect (hal thread only). With the direct boot split
     * (App.startModules after the unlock, or after the connect on a process restart) the modules add their
     * connect hooks after the HAL already connected, so DreamLamp / TimerLamp never recovered a dim marker
     * (seen on hb-t7: marker saved 10, dim 2, lamp left at 2 after a reboot and after a process restart).
     */
    private final java.util.Set<Runnable> firedHooks =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Z9X HB fork (hb-t7): runs, once, the connect hooks added after the last connect fired (App.startModules
     * calls it at its end). Not connected: nothing, the next connect runs every hook. Any thread.
     */
    void runLateConnectHooks() {
        hal.post(() -> {
            if (!connected) return;
            for (Runnable r : Hal.CONNECT_LISTENERS) {
                if (!firedHooks.add(r)) continue;
                try { r.run(); } catch (Throwable t) { Log.w(TAG, "late connected listener: " + t); }
            }
            Log.i(TAG, "late connect hooks run (" + firedHooks.size() + " in all)");
        });
    }

    /** After a reconnect: if the vendor lost the handshake state, resend B, D, C, E (never F). */
    private void checkVendorStateAfterReconnect() {
        int anim, ui;
        try {
            anim = client.getBootAnimState();
            ui = client.getSystemUiState();
        } catch (Throwable t) {
            Log.w(TAG, "33/37 after reconnect: " + t);
            return;
        }
        Log.i(TAG, "after reconnect: bootAnimState=" + anim + " systemUIState=" + ui);
        if (sentBootAnim && anim != 2) {
            if (restartResends >= MAX_RESTART_RESENDS) {
                Log.e(TAG, "vendor state lost again; resend limit reached");
                return;
            }
            restartResends++;
            Log.w(TAG, "the vendor lost its boot state: resending 36(1), 32(2), 2(0), 2(1) (not 17)");
            try { client.setSystemUiReady(); } catch (Throwable t) { Log.w(TAG, "36: " + t); }
            try { client.setBootAnimFinished(); } catch (Throwable t) { Log.w(TAG, "32(2): " + t); }
            try { client.goToBootCompleted(GmpfClient.BootStage.EARLY); } catch (Throwable t) { Log.w(TAG, "2(0): " + t); }
            try { client.goToBootCompleted(GmpfClient.BootStage.AFTER_BOOTANIM); } catch (Throwable t) { Log.w(TAG, "2(1): " + t); }
        } else if (sentUiState && ui != 1) {
            try { client.setSystemUiReady(); Log.i(TAG, "36(1) resent"); } catch (Throwable t) { Log.w(TAG, "36: " + t); }
        }
    }

    // ================================================================== focus events
    /** AK overlay event range forwarded to AkOverlay first (stock spec 1.1 / 2.6, 120 = curtain). */
    static boolean isAkEvent(int type) {
        return (type >= 105 && type <= 118) || type == 120;
    }

    /** MAIN thread, in arrival order. */
    private void dispatchEvent(int type, String value) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + type
                + (value == null || value.isEmpty() ? "" : " \"" + value + "\"");
        Log.i(TAG, "focusEvent type=" + type + " value=" + value);
        synchronized (events) {
            events.addLast(line);
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
        boolean handled = false;
        // optical zoom: every AK runs XGIMI's zoomReset (106 = AK start, 303 = its walk back failed), and the
        // AK report (116) says whether it was a screen fit (logs/z9x_zoom_103/live_full3.txt)
        try {
            if (type == 106 || type == 303) org.lumen.projector.panel.OpticalZoomPanel.onVendorZoomReset(app, type);
            else if (type == 116) org.lumen.projector.panel.OpticalZoomPanel.onAkReport(app, value);
        } catch (Throwable t) {
            Log.w(TAG, "zoom hook " + type + ": " + t);
        }
        // Lumen 1.0.8 (Z9X HB t6 test 2026-10-30): the AF card (stock HB SystemUI FocusUIV2 + AutoMaticFA, background
        // auto_focus_dapeng for 335) opens on 4/8/10/333/334/335 and sends sendFocusStatus(4) after its first frame;
        // it closes on 5/7/2. Without it the vendor waits 3 s for 4 and ends every AF with EVENT_FLOW_ERR.
        try {
            AfCardOverlay.onFocusEvent(type);
        } catch (Throwable t) {
            Log.w(TAG, "AfCardOverlay.onFocusEvent(" + type + "): " + t);
        }
        if (isAkEvent(type)) {
            try {
                handled = AkOverlay.onFocusEvent(type, value);
            } catch (Throwable t) {
                Log.w(TAG, "AkOverlay.onFocusEvent(" + type + "): " + t);
            }
        }
        // Z9X HB hb-t7: the stock fit-to-screen hint window (SystemUI FocusUIV2 105 "AK_Curtain_Fit_Guide" ->
        // AuxiliaryCurtainAlignDialog); AkNotices does not know the value, so AkOverlay leaves it unhandled
        if (type == 105 && CurtainAlignOverlay.VALUE.equals(value)) {
            try {
                CurtainAlignOverlay.show();
            } catch (Throwable t) {
                Log.w(TAG, "CurtainAlignOverlay.show: " + t);
            }
            handled = true;
        }
        for (Hal.FocusEventListener l : Hal.LISTENERS) {
            try { l.onFocusEvent(type, value); } catch (Throwable t) { Log.w(TAG, "focus listener: " + t); }
        }
        if (!handled) hal.post(() -> onFocusEvent(type, value));
    }

    /** "z9x-hal" thread: the v6 handling of events nobody else consumed. */
    private void onFocusEvent(int type, String value) {
        long now = SystemClock.elapsedRealtime();
        switch (type) {
            case 4: case 8: case 10: case 333: case 334: case 335:
                afBusyUntil = now + AF_BUSY_MS;
                // no toast over the AF card (the vendor's camera measures the card); a toast only when the card
                // could not be shown (the auto keystone overlay is up, or the window was refused)
                Ui.main().post(() -> { if (!AfCardOverlay.isShown()) Ui.toast(app, R.string.toast_af_running); });
                break;
            case 5:
                afBusyUntil = 0;
                break;
            case 2:
                afBusyUntil = 0;
                Ui.toast(app, R.string.toast_af_failed);
                break;
            case 0: case 1:
                motor.stop();                                  // motor limit reached
                break;
            case 120:
                onCurtainEvent(now);
                break;
            default:
                break;
        }
    }

    /**
     * Event 120 (curtain adapt). Stock only OFFERS the re-fit here (CurtainGuideDialog; newAutoKst(11)
     * runs only on the user's click), so this never calls a keystone itself.
     * Z9X HB fork (area projection): as stock SystemUI FocusUIV2 (type 120 -> CurtainAdaptManager.show ->
     * CurtainGuideDialog, button dialog_adapt_curtain_btn -> newAutoKst(11)) a card with the stock title, text and
     * one button; the button runs the same "Fit to screen now" request (newAutoKst(11) after the motor interlock).
     * Lumen keeps its guards: only while "Fit to screen" is on, not while a keystone runs, not for 30 s after ours,
     * at most one card per 10 s and 5 per process, never two cards at once. hal thread.
     */
    private void onCurtainEvent(long now) {
        if (kstInFlight.get() || now - lastKstEndAt < CURTAIN_QUIET_AFTER_KST_MS) {
            Log.i(TAG, "curtain event ignored: keystone running or just finished");
            return;
        }
        if (now - lastCurtainEventAt < CURTAIN_OFFER_GAP_MS || curtainOffers >= MAX_CURTAIN_OFFERS) return;
        lastCurtainEventAt = now;
        try {
            if (client.getCurtainFit()) {
                curtainOffers++;
                Log.i(TAG, "curtain event: offering fit-to-screen (" + curtainOffers + "/" + MAX_CURTAIN_OFFERS + ")");
                Ui.main().post(this::showCurtainGuide);
            }
        } catch (Throwable t) {
            Log.w(TAG, "curtain event: " + t);
        }
    }

    List<String> recentEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    // ================================================================== user actions
    /** Result of a user request (Lumen OS 1.0: the SetupBridge reports it; the other callers ignore it). */
    static final int REQ_OK = 0, REQ_BUSY = 1, REQ_NOT_READY = 2, REQ_UNSUPPORTED = 3;

    /** An autofocus is running (vendor focus events) right now. Any thread. */
    boolean isAfBusy() {
        return SystemClock.elapsedRealtime() < afBusyUntil;
    }

    /** Remote focus key (UP) or settings: sendFocusStatus(44). Main thread (or the bridge's binder thread). */
    int requestAutofocus() {
        if (!featureEnabled()) { Ui.featureOffNotice(app); return REQ_UNSUPPORTED; }
        long now = SystemClock.elapsedRealtime();
        if (now - lastAfRequestAt < AF_DEBOUNCE_MS) return REQ_BUSY;
        lastAfRequestAt = now;
        if (now < afBusyUntil) { Log.i(TAG, "autofocus key ignored: AF running"); return REQ_BUSY; }
        if (AkOverlay.isActive()) { Log.i(TAG, "autofocus key ignored: auto keystone overlay active"); return REQ_BUSY; }
        if (!connected) { Ui.toast(app, R.string.toast_not_ready); return REQ_NOT_READY; }
        if (kstInFlight.get() || motor.isMoving()) { Log.i(TAG, "autofocus ignored: keystone or motor busy"); return REQ_BUSY; }
        // 1.0.8 (parity focus item 10): the focus key closes the manual focus window first, as stock FocusAdjust
        // does; its exit then sends no manualFocus(2), which would interrupt the AF just started
        ManualFocusActivity.closeForAutofocus();
        Ui.toast(app, R.string.toast_af);
        hal.post(() -> {
            if (kstInFlight.get() || motor.isMoving() || AkOverlay.isActive()) {
                Log.i(TAG, "autofocus skipped: keystone (ours or the vendor's) or motor busy");
                return;
            }
            try {
                client.sendRemoteAutofocus();
                Log.i(TAG, "sendFocusStatus(44)");
            } catch (Throwable t) {
                Log.w(TAG, "sendFocusStatus(44): " + t);
                Ui.toast(app, R.string.toast_failed);
            }
        });
        return REQ_OK;
    }

    /**
     * "Auto keystone now" (326) or "Fit to screen now" (272 with 11). User action only.
     * The busy flag is claimed HERE, on the calling thread, so a second request while one is
     * queued or running shows "Please wait: auto keystone is running" instead of running again, and a
     * manual move started after this point is refused by MotorController.
     */
    int requestKeystone(boolean fit) {
        if (!featureEnabled()) { Ui.featureOffNotice(app); return REQ_UNSUPPORTED; }
        // HB: the remote, voice and Setup paths reach here without a panel row; a feature that is not ON sends
        // nothing (fit needs CURTAIN_FIT for newAutoKst(11), approved 9.1: refused here, not after a motor stop)
        if (!HbFeatures.on(fit ? HbFeatures.F.CURTAIN_FIT : HbFeatures.F.AUTO_KEYSTONE)) {
            Log.i(TAG, (fit ? "fit" : "keystone") + " request refused: feature not ON on the HB");
            return REQ_UNSUPPORTED;
        }
        if (!connected) { Ui.toast(app, R.string.toast_not_ready); return REQ_NOT_READY; }
        if (AkOverlay.isActive() || SystemClock.elapsedRealtime() < afBusyUntil) {
            Ui.toast(app, R.string.toast_kst_busy);
            return REQ_BUSY;
        }
        if (!kstInFlight.compareAndSet(false, true)) {
            Ui.toast(app, R.string.toast_kst_busy);
            return REQ_BUSY;
        }
        if (!kst.post(() -> runKeystone(fit, true))) {
            kstInFlight.set(false);
            return REQ_NOT_READY;
        }
        return REQ_OK;
    }

    /**
     * Z9X HB hb-t7: "Continue" (OK) on the stock fit-to-screen hint window (CurtainAlignOverlay): newAutoKst(12), as
     * stock AuxiliaryCurtainAlignDialog.onKeyUp. Stock sends it at once; Lumen keeps its keystone interlock (one
     * keystone at a time, confirmed motor stop) and the CURTAIN_FIT feature gate. Any thread.
     */
    void requestCurtainAlign() {
        if (!featureEnabled() || !HbFeatures.on(HbFeatures.F.CURTAIN_FIT)) {
            Log.i(TAG, "curtain align refused: feature not ON");
            return;
        }
        if (!connected) { Ui.toast(app, R.string.toast_not_ready); return; }
        if (!kstInFlight.compareAndSet(false, true)) {
            Log.w(TAG, "curtain align: a keystone is still running");
            Ui.toast(app, R.string.toast_kst_busy);
            return;
        }
        if (!kst.post(this::runCurtainAlign)) kstInFlight.set(false);
    }

    /** kst thread: newAutoKst(12) after a confirmed motor stop. The caller claimed kstInFlight. */
    private void runCurtainAlign() {
        try {
            if (!motor.stopAndWait(2_000) || !motor.isIdleConfirmed()) {
                Log.w(TAG, "curtain align refused: motor stop not confirmed (" + motor.describe() + ")");
                Ui.toast(app, R.string.toast_failed);
                return;
            }
            try {
                byte r = client.newAutoKst(GmpfClient.AutoKstMode.CURTAIN_ALIGN);
                Log.i(TAG, "newAutoKst(12) -> " + r);
            } finally {
                lastKstEndAt = SystemClock.elapsedRealtime();
            }
        } catch (Throwable t) {
            Log.w(TAG, "curtain align: " + t);
            Ui.toast(app, R.string.toast_failed);
        } finally {
            kstInFlight.set(false);
            try { org.lumen.projector.setup.SetupEvents.kst(app); } catch (Throwable ignored) { }
        }
    }

    /**
     * kst thread. The caller has claimed kstInFlight; it is released here in finally.
     * Blocking HAL calls; gated on 390 == 1 and 394 == 2 right now, and on a confirmed motor stop.
     */
    private void runKeystone(boolean fit, boolean user) {
        try {
            if (!featureEnabled() || !connected) return;
            int ui = client.getSystemUiState();
            int anim = client.getBootAnimState();
            if (ui != 1 || anim != 2) {
                Log.w(TAG, "keystone refused: systemUIState=" + ui + " bootAnimState=" + anim);
                if (user) Ui.toast(app, R.string.toast_wait_boot);
                return;
            }
            if (fit && !client.getCurtainFit()) {
                if (user) Ui.toast(app, R.string.toast_need_curtain);
                return;
            }
            // Always go through the motor thread: this queues behind a startNow that is already
            // posted (it then sees the stop), sends manualFocus(2) only if something may be moving,
            // and every later startNow sees kstInFlight and refuses. Then require a confirmed idle
            // motor: not moving and the persisted manual_moving flag clear (a failed stop keeps both).
            if (!motor.stopAndWait(2_000) || !motor.isIdleConfirmed()) {
                Log.w(TAG, "keystone refused: motor stop not confirmed (" + motor.describe() + ")");
                if (user) Ui.toast(app, R.string.toast_failed);
                return;
            }
            Ui.toast(app, fit ? R.string.toast_fit : R.string.toast_kst);
            try {
                byte r = fit ? client.newAutoKst(GmpfClient.AutoKstMode.CURTAIN_REFIT) : client.autoKst();
                Log.i(TAG, (fit ? "newAutoKst(11)" : "autoKst()") + " -> " + r);
            } finally {
                lastKstEndAt = SystemClock.elapsedRealtime();
            }
        } catch (Throwable t) {
            Log.w(TAG, "keystone: " + t);
            if (user) Ui.toast(app, R.string.toast_failed);
        } finally {
            kstInFlight.set(false);
            try { org.lumen.projector.setup.SetupEvents.kst(app); } catch (Throwable ignored) { }
        }
    }

    /** The stock CurtainGuideDialog card (event 120). Main thread. */
    private org.lumen.projector.ui.DialogPanel curtainGuide;

    private void showCurtainGuide() {
        if (curtainGuide != null) return;                       // stock: not while the dialog is showing
        org.lumen.projector.ui.DialogPanel d = new org.lumen.projector.ui.DialogPanel(
                app.getString(R.string.hb_curtain_guide_title), app.getString(R.string.hb_curtain_guide_desc),
                app.getString(R.string.hb_curtain_guide_btn), null, ok -> {
                    curtainGuide = null;
                    Log.i(TAG, "curtain guide: " + (ok ? "start (fit to screen now)" : "closed"));
                    if (ok) requestKeystone(true);
                });
        curtainGuide = d;
        try {
            if (!org.lumen.projector.ui.OverlayHost.get(app).show(d)) curtainGuide = null;
        } catch (Throwable t) {
            curtainGuide = null;
            Log.w(TAG, "curtain guide: " + t);
        }
    }

    // ================================================================== toggles
    void readToggles(ToggleResult cb) {
        hal.post(() -> {
            Map<Toggle, Boolean> m = new EnumMap<>(Toggle.class);
            for (Toggle t : Toggle.values()) m.put(t, connected && featureEnabled() && toggleOn(t) ? safeGet(t) : null);
            Ui.main().post(() -> cb.onToggles(m));
        });
    }

    /**
     * HB: a toggle whose feature is not ON is neither read nor written (its value stays null = unknown), whoever
     * asks (panel, Projector settings, the Setup bridge); the setters in GmpfClient refuse it as well.
     */
    static boolean toggleOn(Toggle t) {
        switch (t) {
            case CURTAIN_FIT: return HbFeatures.on(HbFeatures.F.CURTAIN_FIT);
            case OBSTACLE: return HbFeatures.on(HbFeatures.F.OBSTACLE_AVOID);
            case MOVE_AK: return HbFeatures.on(HbFeatures.F.MOVE_AK);
            default: return true;
        }
    }

    /** Called only on a user click. Sets, then reads back. */
    void setToggle(Toggle t, boolean on, ToggleResult cb) {
        hal.post(() -> {
            if (!featureEnabled() || !connected) {
                Ui.toast(app, R.string.toast_not_ready);
            } else if (!toggleOn(t)) {
                Log.i(TAG, "toggle " + t + " refused: feature not ON on the HB, nothing sent");
            } else {
                try {
                    set(t, on);
                    Log.i(TAG, "toggle " + t + " -> " + on);
                } catch (Throwable e) {
                    Log.w(TAG, "toggle " + t + ": " + e);
                    Ui.toast(app, R.string.toast_failed);
                }
            }
            Map<Toggle, Boolean> m = new EnumMap<>(Toggle.class);
            for (Toggle x : Toggle.values()) m.put(x, connected && featureEnabled() && toggleOn(x) ? safeGet(x) : null);
            Ui.main().post(() -> cb.onToggles(m));
        });
    }

    private Boolean safeGet(Toggle t) {
        try {
            switch (t) {
                case POWER_ON_AF: return client.getPowerOnAf();
                case POWER_ON_AK: return client.getPowerOnAk();
                case MOVE_AF: return client.getMoveAf();
                case MOVE_AK: return client.getMoveAk();
                case CURTAIN_FIT: return client.getCurtainFit();
                case OBSTACLE: return client.getObstacleAvoid();
                case POWER_ON_MUSIC: return client.getPowerOnMusic();
                default: return null;
            }
        } catch (Throwable e) {
            Log.w(TAG, "get " + t + ": " + e);
            return null;
        }
    }

    /** The 290/292/585 interlocks are the shared Hal helpers (same sequence as the quick panel). */
    private void set(Toggle t, boolean on) throws Exception {
        switch (t) {
            case POWER_ON_AF: client.setPowerOnAf(on); break;
            case POWER_ON_AK: client.setPowerOnAk(on); break;
            case MOVE_AF:
                client.setMoveAf(on);
                Ui.main().post(org.lumen.projector.power.StandbyController::noteUserMoveAf);
                break;
            case MOVE_AK:                                      // Z9X HB fork: also on a ceiling mount (stock HB
                client.setMoveAk(on);                          // timely AK under hoisting), as the quick panel
                break;
            case CURTAIN_FIT: Hal.setCurtainFitCoupled(client, on); break;      // ON also obstacle ON
            case OBSTACLE: Hal.setObstacleAvoidCoupled(client, on); break;      // OFF also fit OFF
            case POWER_ON_MUSIC: client.setPowerOnMusic(on); break;
            default: break;
        }
    }

    // ================================================================== diagnostics
    void readDiagnostics(TextResult cb) {
        hal.post(() -> {
            StringBuilder sb = new StringBuilder();
            line(sb, "ro.lumen.version", prop("ro.lumen.version"));
            line(sb, "vendor.xgimi.ledOn", prop("vendor.xgimi.ledOn"));
            line(sb, "HAL", connected ? "connected (System, Power, Focus)" : "not connected");
            line(sb, "Handshake", stageText());
            if (connected) {
                try {
                    line(sb, "systemUIState / bootAnimState", client.getSystemUiState() + " / " + client.getBootAnimState());
                } catch (Throwable t) {
                    line(sb, "systemUIState / bootAnimState", "error");
                }
            }
            line(sb, "Focus motor", motor.describe());
            List<String> ev = recentEvents();
            sb.append("Recent focus events:");
            if (ev.isEmpty()) sb.append(" none");
            for (int i = ev.size() - 1, n = 0; i >= 0 && n < 8; i--, n++) sb.append('\n').append("  ").append(ev.get(i));
            String text = sb.toString();
            Ui.main().post(() -> cb.onText(text));
        });
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(k).append(": ").append(v == null || v.isEmpty() ? "—" : v).append('\n');
    }

    private static String prop(String k) {
        try {
            return SystemProperties.get(k);
        } catch (Throwable t) {
            return "";
        }
    }
}
