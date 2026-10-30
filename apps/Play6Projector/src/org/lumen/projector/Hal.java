package org.lumen.projector;

import android.content.Context;
import android.os.IHwBinder;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.hal.Play6FocusCallback;
import org.lumen.projector.hal.Play6Gmpf;
import org.lumen.projector.hal.Play6Gmpf2;
import org.lumen.projector.hal.Play6MusicPlayerCallback;
import org.lumen.projector.hal.Play6PowerCallback;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public entry point to the vendor HAL of the XGIMI Play 6 for the module packages
 * (docs/play6/SETTINGS_PLAN.md 2.2). The Play 6 counterpart of Z9xProjector Hal + HalController, typed
 * on the Play 6 clients: hal/Play6Gmpf (IGmpf) and hal/Play6Gmpf2 (IGmpf2, Play 6 codes, identity
 * guard). The stock boot handshake (org.lumen.play6.BootHandshake) has its own Play6Gmpf instance and
 * thread and is not part of this class; the handshake is never re-sent from here (OQ 18).
 *
 * Closed until Play6App opens the start gate (boot completed, boot animation gone, user unlocked, the
 * handshake thread "play6-boot" ended, 2 s more), and on every device that is not a Play 6
 * (Device.isPlay6). While closed every call is refused and dropped ("refused (gate closed)" in the log)
 * and a user action shows R.string.toast_not_ready: a Focus key, a Home tile or a Source key during
 * the boot animation sends nothing.
 *
 * Threads (plan 2.2):
 *  - "p6-hal": {@link #run}, {@link #query} and the connection; FIFO, short calls, never the main
 *    thread (the clients refuse the main thread too);
 *  - "p6-long": {@link #runLong}, one short-lived thread per call and one at a time, for the calls that
 *    take seconds inside the HAL (326 autoKst through {@link #requestKeystone}, 404 getAllFeatureList).
 *    The vendor holds a global mutex, so a long call still delays other HAL calls inside the HAL, but the
 *    p6-hal queue keeps moving and the panels show cached values meanwhile;
 *  - "p6-motor": the manual focus motor (MotorController), created on first use.
 *
 * Connection: getService + linkToDeath after the gate; then the IGmpf2 identity check (all or nothing:
 * {@link #hasGmpf2}), the focus listener (298 then 306, one Play6FocusCallback) and the vendor power
 * listener (235 then 242, one Play6PowerCallback; research battery_sound_features.md 6.1 B3, owner
 * decision O2 reversed). After an IGmpf death: reconnect with backoff, identity, 298 / 306 and 235 / 242
 * again, then the connected listeners.
 *
 * Power events (Play6PowerCallback replies at once and posts here): MAIN thread, in arrival order, to
 * every PowerEventListener (battery/Battery logs one P6Battery line per event and reads). Nothing here
 * acts on them: they are only triggers for reads and cards, never a reason to turn anything off. Kill
 * switch {@value #POWER_LISTENER_SWITCH} (default 1; 0 = no 235 / 242 at the next connect, the battery
 * then falls back to its 30 s watchdog read).
 *
 * Focus events (Play6FocusCallback replies at once and posts here): MAIN thread, in arrival order. AK
 * events (105-118, 120) go to AkOverlay first (P1: logged with their timing, AkOverlay.isActive() is
 * false); every other event is logged once (tag P6Focus). Then every FocusEventListener, then the AF
 * busy window as on the Z9X: 4 / 8 / 333..335 busy, 5 done, 2 failed.
 */
public final class Hal {
    private static final String TAG = "P6Hal";
    private static final String FOCUS_TAG = "P6Focus";
    private static final long AF_DEBOUNCE_MS = 1_000;
    private static final long AF_BUSY_MS = 15_000;
    private static final long RECONNECT_MIN_MS = 500;
    private static final long RECONNECT_MAX_MS = 10_000;
    private static final long REFUSED_NOTICE_GAP_MS = 2_000;
    private static final int LISTENER_TRIES = 3;
    private static final String POWER_TAG = "P6Power";
    private static final String MUSIC_TAG = "P6Music";
    /**
     * Kill switch of the music-player listener (IGmpf 215 / 227, stock parity 2026-10-21: the ambient speaker's
     * device-closed event): anything but "0" registers (default 1).
     */
    public static final String MUSIC_LISTENER_SWITCH = "persist.lumen.p6.music_listener";
    /** Kill switch of the vendor power listener (235 / 242): "0" = off; read at every IGmpf connect. */
    public static final String POWER_LISTENER_SWITCH = "persist.lumen.p6.power_listener";

    private Hal() {}

    /** Work on the p6-hal (or p6-long) thread with both clients. */
    public interface Task {
        void run(Play6Gmpf gmpf, Play6Gmpf2 gmpf2) throws Exception;
    }

    /** A read on the p6-hal thread. */
    public interface Query<T> {
        T run(Play6Gmpf gmpf, Play6Gmpf2 gmpf2) throws Exception;
    }

    /** Main-thread result of {@link #query}; value null = failed / refused / not connected. */
    public interface Result<T> {
        void onResult(T value);
    }

    /** Focus / AK / eye events from the vendor, on the MAIN thread in arrival order. Must not block. */
    public interface FocusEventListener {
        void onFocusEvent(int type, String value);
    }

    /** Vendor power events (IPowerCallback.powerEvent), on the MAIN thread in arrival order. Must not block. */
    /** Vendor music-player events (type 4 = the projector closed (status 1) / opened), on the MAIN thread. */
    public interface MusicPlayerEventListener {
        void onMusicPlayerEvent(int type, int keycode, int status);
    }

    public interface PowerEventListener {
        void onPowerEvent(int type, String value);
    }

    /** Request results (as the Z9X Hal). */
    public static final int REQ_OK = 0, REQ_BUSY = 1, REQ_NOT_READY = 2, REQ_UNSUPPORTED = 3;

    private static final CopyOnWriteArrayList<FocusEventListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<PowerEventListener> POWER_LISTENERS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<MusicPlayerEventListener> MUSIC_LISTENERS = new CopyOnWriteArrayList<>();
    private static Play6MusicPlayerCallback sMusicCallback;
    private static boolean sRegistered215, sRegistered227;
    private static int sMusicTries;
    private static final CopyOnWriteArrayList<Runnable> CONNECT_LISTENERS = new CopyOnWriteArrayList<>();
    private static final Play6Gmpf GMPF = new Play6Gmpf();
    private static final Play6Gmpf2 GMPF2 = new Play6Gmpf2();
    private static final AtomicBoolean LONG_IN_FLIGHT = new AtomicBoolean();
    /** An auto keystone (326) is queued or running: claimed by requestKeystone, released when it ends. */
    private static final AtomicBoolean KST_IN_FLIGHT = new AtomicBoolean();
    private static final IHwBinder.DeathRecipient DEATH = cookie -> worker().post(() -> onDied(cookie));

    private static SafeHandler sHal;
    private static SafeHandler sMotor;
    private static volatile Context sApp;
    private static volatile boolean sGateOpen;
    private static volatile boolean sConnected;
    private static volatile long sAfBusyUntil;
    /** Strong reference: the HAL keeps only a binder reference to our callback. */
    private static Play6FocusCallback sCallback;
    /** Strong reference, as sCallback: the power listener of 235 / 242. */
    private static Play6PowerCallback sPowerCallback;
    /** 235 and 242 both returned normally on the current connection (the vendor sends us its events). */
    private static volatile boolean sPowerListening;
    /** Why the power listener is not registered, for Diagnostics; null while it is. */
    private static volatile String sPowerState = "not registered yet";
    // p6-hal thread state
    private static long sGeneration;
    private static long sReconnectDelay = RECONNECT_MIN_MS;
    private static Runnable sFirstConnected;
    /** 298 / 306 reached the HAL on this connection (reset in connectOnce): neither is sent twice. */
    private static boolean sRegistered298, sRegistered306;
    private static int sListenerTries;
    /** 235 / 242 reached the HAL on this connection (reset in connectOnce): neither is sent twice. */
    private static boolean sRegistered235, sRegistered242;
    private static int sPowerTries;
    // main thread state
    private static long sLastAfRequestAt = -100_000L;
    private static long sLastRefusedNoticeAt = -100_000L;

    // ================================================================== start (Play6App)
    /** Play6App.onProcessStart: the application context. No thread, no HAL. */
    static void init(Context app) {
        sApp = app.getApplicationContext();
    }

    /** The p6-hal thread (also runs Play6App's start gate). */
    static synchronized SafeHandler worker() {
        if (sHal == null) sHal = SafeHandler.newThread("p6-hal");
        return sHal;
    }

    /** The p6-motor thread of MotorController, created on first use. */
    static synchronized SafeHandler motorWorker() {
        if (sMotor == null) sMotor = SafeHandler.newThread("p6-motor");
        return sMotor;
    }

    /**
     * Play6App, p6-hal, once: the start gate is open. Connects IGmpf (retrying with backoff), checks the
     * IGmpf2 identity, registers the focus listener and the power listener, then runs {@code firstConnected}
     * once (power/UiReady17) and the connected listeners.
     */
    static void openGate(Runnable firstConnected) {
        if (sGateOpen) return;
        sGateOpen = true;
        sFirstConnected = firstConnected;
        connectTick();
    }

    private static void connectTick() {
        if (!connectOnce()) {
            worker().postDelayed(Hal::connectTick, nextBackoff());
            return;
        }
        Runnable first = sFirstConnected;
        sFirstConnected = null;
        if (first != null) {
            try { first.run(); } catch (Throwable t) { Log.w(TAG, "first connect: " + t); }
        }
        fireConnected();
    }

    /**
     * p6-hal: getService + linkToDeath, then the IGmpf2 identity, the focus listener (298, 306) and the
     * power listener (235, 242).
     */
    private static boolean connectOnce() {
        long gen = ++sGeneration;
        if (!GMPF.connect(DEATH, gen)) {
            Log.w(TAG, "IGmpf not available (try " + gen + ")");
            return false;
        }
        sConnected = true;
        sReconnectDelay = RECONNECT_MIN_MS;
        Log.i(TAG, "connected (IGmpf, generation " + gen + ")");
        if (GMPF2.verifyIdentity()) Log.i(TAG, "IGmpf2 guard open");
        else Log.w(TAG, "IGmpf2 guard closed: every IGmpf2 feature stays unavailable");
        sRegistered298 = sRegistered306 = false;
        sListenerTries = 0;
        registerFocusListener();
        sRegistered235 = sRegistered242 = false;
        sPowerListening = false;
        sPowerTries = 0;
        registerPowerListener();
        sRegistered215 = sRegistered227 = false;
        sMusicTries = 0;
        registerMusicListener();
        Context app = sApp;
        if (app != null) {
            try { MotorController.onConnected(app); } catch (Throwable t) { Log.w(TAG, "motor: " + t); }
        }
        return true;
    }

    private static long nextBackoff() {
        long d = sReconnectDelay;
        sReconnectDelay = Math.min(RECONNECT_MAX_MS, sReconnectDelay * 2);
        return d;
    }

    /**
     * p6-hal: 298 connectProjectorFocusManager then 306 setProjectorFocusListener with the same callback,
     * each at most once per connection, as the Z9X HalController: a call that reached the HAL (also one
     * with a bad reply) is never sent again, and a retry sends only the call that was not delivered (up to
     * LISTENER_TRIES tries per connection, 1 s apart).
     */
    private static void registerFocusListener() {
        if (!sConnected) return;
        if (sCallback == null) {
            sCallback = new Play6FocusCallback((type, value) -> Ui.main().post(() -> dispatchEvent(type, value)));
        }
        final long gen = sGeneration;
        try {
            if (!sRegistered298) {
                GMPF.connectFocusManager(sCallback);           // 298
                sRegistered298 = true;
            }
            if (!sRegistered306) {
                GMPF.setFocusListener(sCallback);              // 306, same callback object
                sRegistered306 = true;
            }
            Log.i(FOCUS_TAG, "listener registered (298, 306)");
        } catch (Play6Gmpf.ReplyException e) {
            // reached the HAL: do not send it again
            String code = sRegistered298 ? "306" : "298";
            if (!sRegistered298) sRegistered298 = true; else sRegistered306 = true;
            Log.w(FOCUS_TAG, "listener: " + code + " delivered, bad reply (not repeated): " + e.getMessage());
            if (!sRegistered306) worker().postDelayed(() -> retryFocusListener(gen), 1_000);
        } catch (Throwable t) {
            Log.w(FOCUS_TAG, "listener failed (" + (sListenerTries + 1) + "/" + LISTENER_TRIES + "): " + t);
            if (++sListenerTries < LISTENER_TRIES) worker().postDelayed(() -> retryFocusListener(gen), 1_000);
        }
    }

    /** p6-hal: a delayed listener retry, only on the connection it was made for. */
    private static void retryFocusListener(long gen) {
        if (sConnected && gen == sGeneration) registerFocusListener();
    }

    /**
     * p6-hal: 235 connectPowerManager then 242 setPowerListener with the same callback, as stock
     * com.xgimi.gmpf.api.PowerManager, each at most once per connection with the retry rule of the focus
     * listener (a call that reached the HAL is never sent again). Skipped while the kill switch says 0.
     * {@link #isPowerListenerRegistered} is true only when both returned normally.
     */
    private static void registerPowerListener() {
        if (!sConnected) return;
        if (!powerListenerEnabled()) {
            sPowerState = "off (" + POWER_LISTENER_SWITCH + "=0)";
            Log.i(POWER_TAG, "listener not registered: " + POWER_LISTENER_SWITCH + "=0");
            return;
        }
        if (sPowerCallback == null) {
            sPowerCallback = new Play6PowerCallback((type, value) -> Ui.main().post(() -> dispatchPowerEvent(type, value)));
        }
        final long gen = sGeneration;
        try {
            if (!sRegistered235) {
                GMPF.connectPowerManager(sPowerCallback);      // 235
                sRegistered235 = true;
            }
            if (!sRegistered242) {
                GMPF.setPowerListener(sPowerCallback);         // 242, same callback object
                sRegistered242 = true;
            }
            sPowerListening = true;
            sPowerState = null;
            Log.i(POWER_TAG, "listener registered (235, 242)");
        } catch (Play6Gmpf.ReplyException e) {
            // reached the HAL: do not send it again
            String code = sRegistered235 ? "242" : "235";
            if (!sRegistered235) sRegistered235 = true; else sRegistered242 = true;
            sPowerState = code + " delivered with a bad reply";
            Log.w(POWER_TAG, "listener: " + code + " delivered, bad reply (not repeated): " + e.getMessage());
            if (!sRegistered242) worker().postDelayed(() -> retryPowerListener(gen), 1_000);
        } catch (Throwable t) {
            sPowerState = "failed: " + t;
            Log.w(POWER_TAG, "listener failed (" + (sPowerTries + 1) + "/" + LISTENER_TRIES + "): " + t);
            if (++sPowerTries < LISTENER_TRIES) worker().postDelayed(() -> retryPowerListener(gen), 1_000);
        }
    }

    /**
     * p6-hal: 215 connectMusicPlayerManager then 227 setMusicPlayerListener with the same callback, as stock
     * MusicPlayerManager.native_setup, with the retry rule of the focus listener (a call that reached the HAL is
     * never sent again). The events only reach {@link #addMusicPlayerEventListener} listeners on the main thread.
     */
    private static void registerMusicListener() {
        if (!sConnected) return;
        boolean on;
        try { on = !"0".equals(SystemProperties.get(MUSIC_LISTENER_SWITCH, "1").trim()); } catch (Throwable t) { on = true; }
        if (!on) {
            Log.i(MUSIC_TAG, "listener not registered: " + MUSIC_LISTENER_SWITCH + "=0");
            return;
        }
        if (sMusicCallback == null) {
            sMusicCallback = new Play6MusicPlayerCallback((type, key, status) -> Ui.main().post(() -> {
                Log.i(MUSIC_TAG, "event type " + type + " keycode " + key + " status " + status);
                for (MusicPlayerEventListener l : MUSIC_LISTENERS) {
                    try { l.onMusicPlayerEvent(type, key, status); } catch (Throwable e) { Log.w(MUSIC_TAG, "listener: " + e); }
                }
            }));
        }
        final long gen = sGeneration;
        try {
            if (!sRegistered215) {
                GMPF.connectMusicPlayer(sMusicCallback);       // 215
                sRegistered215 = true;
            }
            if (!sRegistered227) {
                GMPF.setMusicPlayerListener(sMusicCallback);   // 227, same callback object
                sRegistered227 = true;
            }
            Log.i(MUSIC_TAG, "listener registered (215, 227)");
        } catch (Play6Gmpf.ReplyException e) {
            String code = sRegistered215 ? "227" : "215";
            if (!sRegistered215) sRegistered215 = true; else sRegistered227 = true;
            Log.w(MUSIC_TAG, "listener: " + code + " delivered, bad reply (not repeated): " + e.getMessage());
            if (!sRegistered227) worker().postDelayed(() -> { if (sConnected && gen == sGeneration) registerMusicListener(); }, 1_000);
        } catch (Throwable t) {
            Log.w(MUSIC_TAG, "listener failed (" + (sMusicTries + 1) + "/" + LISTENER_TRIES + "): " + t);
            if (++sMusicTries < LISTENER_TRIES) {
                worker().postDelayed(() -> { if (sConnected && gen == sGeneration) registerMusicListener(); }, 1_000);
            }
        }
    }

    public static void addMusicPlayerEventListener(MusicPlayerEventListener l) { MUSIC_LISTENERS.addIfAbsent(l); }

    /** p6-hal: a delayed power listener retry, only on the connection it was made for. */
    private static void retryPowerListener(long gen) {
        if (sConnected && gen == sGeneration) registerPowerListener();
    }

    /** The kill switch: anything but "0" registers (default 1). */
    private static boolean powerListenerEnabled() {
        try {
            return !"0".equals(SystemProperties.get(POWER_LISTENER_SWITCH, "1").trim());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void fireConnected() {
        for (Runnable r : CONNECT_LISTENERS) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "connected listener: " + t); }
        }
    }

    /** p6-hal: IGmpf died. Reconnect with backoff; the handshake is not re-sent (OQ 18). */
    private static void onDied(long cookie) {
        if (cookie != sGeneration) return;                     // a stale notice of an older binder
        Log.w(TAG, "IGmpf died");
        sConnected = false;
        sPowerListening = false;                               // registered again after the reconnect
        sPowerState = "IGmpf died, waiting for the reconnect";
        GMPF.dropConnection();
        GMPF2.dropConnection();                                // same vendor process (gmpfHw)
        MotorController.onHalDied();                           // the persisted flag stays for the reconnect
        sReconnectDelay = RECONNECT_MIN_MS;
        worker().postDelayed(Hal::reconnectTick, RECONNECT_MIN_MS);
    }

    private static void reconnectTick() {
        if (sConnected) return;
        if (!connectOnce()) {
            worker().postDelayed(Hal::reconnectTick, nextBackoff());
            return;
        }
        Log.i(TAG, "reconnected to IGmpf");
        fireConnected();
    }

    // ================================================================== state
    /** True once the start gate is open (Play6App). Any thread. */
    public static boolean isGateOpen() {
        return sGateOpen;
    }

    /** True while the gate is open and IGmpf is connected. Any thread. */
    public static boolean isReady() {
        return sGateOpen && sConnected;
    }

    /** IGmpf2-dependent features (all or nothing): the identity guard is open on a connected HAL. Any thread. */
    public static boolean hasGmpf2() {
        return isReady() && GMPF2.isIdentityOpen();
    }

    /** A long call (runLong: 326, 404) is queued or running. Any thread. */
    public static boolean isLongRunning() {
        return LONG_IN_FLIGHT.get();
    }

    /** An auto keystone (326, {@link #requestKeystone}) is queued or running. Any thread. */
    public static boolean isKeystoneRunning() {
        return KST_IN_FLIGHT.get();
    }

    /** The IGmpf client of this facade, for MotorController only (it runs on its own thread p6-motor). */
    static Play6Gmpf client() {
        return GMPF;
    }

    /** An autofocus is running (vendor focus events). Any thread. */
    public static boolean isAfBusy() {
        return SystemClock.elapsedRealtime() < sAfBusyUntil;
    }

    /** /proc/sys/kernel/random/boot_id of this boot, or null when unreadable. Any thread. */
    public static String bootId() {
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

    /**
     * True while the vendor sends us its power events: IGmpf is connected and 235 and 242 both returned
     * normally on this connection. Any thread.
     */
    public static boolean isPowerListenerRegistered() {
        return isReady() && sPowerListening;
    }

    /** One line for Projector settings > Diagnostics. Any thread. */
    public static String describePowerListener() {
        if (isPowerListenerRegistered()) return "registered (235, 242), " + POWER_LISTENER_SWITCH + "=1";
        String why = sPowerState;
        return "not registered: " + (why != null ? why : isReady() ? "unknown" : "IGmpf not connected");
    }

    /** {@code r} runs on p6-hal after every successful IGmpf (re)connect. Cheap only. */
    public static void addConnectedListener(Runnable r) { CONNECT_LISTENERS.addIfAbsent(r); }

    public static void addPowerEventListener(PowerEventListener l) { POWER_LISTENERS.addIfAbsent(l); }

    public static void addFocusEventListener(FocusEventListener l) { LISTENERS.addIfAbsent(l); }

    public static void removeFocusEventListener(FocusEventListener l) { LISTENERS.remove(l); }

    // ================================================================== work
    /** Runs {@code t} on p6-hal. False when refused (gate closed, not a Play 6) or not queued. */
    public static boolean run(Task t) {
        if (!admit("run")) return false;
        return worker().post(() -> exec(t, "run"));
    }

    /** Runs {@code t} on p6-hal after {@code delayMs}. False when refused or not queued. */
    public static boolean runDelayed(Task t, long delayMs) {
        if (!admit("runDelayed")) return false;
        return worker().postDelayed(() -> exec(t, "runDelayed"), delayMs);
    }

    /** Reads on p6-hal; {@code r} gets the value (or null) on the main thread. */
    public static <T> void query(Query<T> q, Result<T> r) {
        if (!admit("query")) {
            Ui.main().post(() -> deliver(r, null));
            return;
        }
        boolean queued = worker().post(() -> {
            T v = null;
            if (sConnected) {
                try {
                    v = q.run(GMPF, GMPF2);
                } catch (Throwable e) {
                    Log.w(TAG, "query: " + e);
                }
            }
            final T out = v;
            Ui.main().post(() -> deliver(r, out));
        });
        if (!queued) Ui.main().post(() -> deliver(r, null));
    }

    /**
     * Runs {@code t} on its own short-lived "p6-long" thread (326 autoKst, 404 getAllFeatureList), one at
     * a time. False when refused, when another long call is still running, or when not connected.
     */
    public static boolean runLong(Task t) {
        return runLong(t, null);
    }

    /**
     * {@link #runLong(Task)}; {@code after} (may be null) runs on the p6-long thread when the call ended,
     * also when the task was skipped because IGmpf dropped meanwhile. Not called when this returns false.
     */
    private static boolean runLong(Task t, Runnable after) {
        if (!admit("runLong")) return false;
        if (!sConnected) return false;
        if (!LONG_IN_FLIGHT.compareAndSet(false, true)) {
            Log.i(TAG, "runLong: a long call is still running");
            return false;
        }
        try {
            new Thread(() -> {
                try {
                    exec(t, "runLong");
                } finally {
                    LONG_IN_FLIGHT.set(false);
                    if (after != null) {
                        try { after.run(); } catch (Throwable e) { Log.w(TAG, "runLong after: " + e); }
                    }
                }
            }, "p6-long").start();
            return true;
        } catch (Throwable e) {
            LONG_IN_FLIGHT.set(false);
            Log.w(TAG, "runLong: " + e);
            return false;
        }
    }

    private static <T> void deliver(Result<T> r, T v) {
        try { r.onResult(v); } catch (Throwable e) { Log.w(TAG, "query result: " + e); }
    }

    /** Returns false when the task was skipped because IGmpf is not connected. */
    private static boolean exec(Task t, String what) {
        if (!sConnected) {
            Log.i(TAG, what + ": IGmpf not connected, task skipped");
            return false;
        }
        try {
            t.run(GMPF, GMPF2);
        } catch (Throwable e) {
            Log.w(TAG, what + ": " + e);
        }
        return true;
    }

    /**
     * False (refused, dropped) on another device or while the start gate is closed; then logs and shows
     * toast_not_ready (at most every 2 s, so a burst of reads shows one notice). Any thread.
     */
    private static boolean admit(String what) {
        if (!Device.isPlay6()) {
            Log.w(TAG, "refused (not a Play 6): " + what);
            return false;
        }
        if (sGateOpen) return true;
        Log.i(TAG, "refused (gate closed): " + what);
        Ui.main().post(() -> {
            long now = SystemClock.elapsedRealtime();
            Context app = sApp;
            if (app == null || now - sLastRefusedNoticeAt < REFUSED_NOTICE_GAP_MS) return;
            sLastRefusedNoticeAt = now;
            Ui.toast(app, R.string.toast_not_ready);
        });
        return false;
    }

    // ================================================================== user actions
    /**
     * Remote-style autofocus (307 sendFocusStatus(44)): the Focus key, the Home tile, the panel. Main
     * thread. Debounced (1 s); refused while an AF runs (focus events), while the AK overlay is up and
     * while a long call (keystone) runs. Returns REQ_*.
     */
    public static int requestAutofocus(Context ctx) {
        if (!admit("autofocus")) return REQ_NOT_READY;
        final Context app = ctx.getApplicationContext();
        long now = SystemClock.elapsedRealtime();
        if (now - sLastAfRequestAt < AF_DEBOUNCE_MS) return REQ_BUSY;
        sLastAfRequestAt = now;
        if (now < sAfBusyUntil) { Log.i(TAG, "autofocus key ignored: AF running"); return REQ_BUSY; }
        if (AkOverlay.isActive()) { Log.i(TAG, "autofocus key ignored: auto keystone overlay active"); return REQ_BUSY; }
        if (!sConnected) { Ui.toast(app, R.string.toast_not_ready); return REQ_NOT_READY; }
        if (LONG_IN_FLIGHT.get() || MotorController.isMovingNow()) {
            Log.i(TAG, "autofocus ignored: a long call (keystone) runs or the focus motor moves");
            return REQ_BUSY;
        }
        // ported from the Z9X 1.0.8 (parity focus item 10): the focus key closes the manual focus window first,
        // as stock FocusAdjust does; its exit then sends no manualFocus(2), which would interrupt the AF
        ManualFocusActivity.closeForAutofocus();
        Ui.toast(app, R.string.toast_af);
        worker().post(() -> {
            if (!sConnected || LONG_IN_FLIGHT.get() || MotorController.isMovingNow() || AkOverlay.isActive()) {
                Log.i(TAG, "autofocus skipped: not connected, keystone, motor or AK busy");
                return;
            }
            try {
                GMPF.requestAutofocus();
                Log.i(TAG, "307(44) sent");
            } catch (Throwable t) {
                Log.w(TAG, "307(44): " + t);
                Ui.toast(app, R.string.toast_failed);
            }
        });
        return REQ_OK;
    }

    /**
     * "Auto keystone now" (326 autoKst) on the p6-long thread (plan 1.3). User action only, main thread.
     * The busy flag is claimed here, so a second request while one is queued or running shows "Please
     * wait" instead of running again, and a manual focus move started after this point is refused by
     * MotorController. On p6-long: 390 == 1 and 394 == 2 right now (the vendor UI is up), then a confirmed
     * motor stop, then 326. Returns REQ_*.
     */
    public static int requestKeystone(Context ctx) {
        if (!admit("keystone")) return REQ_NOT_READY;
        final Context app = ctx.getApplicationContext();
        if (!sConnected) { Ui.toast(app, R.string.toast_not_ready); return REQ_NOT_READY; }
        if (AkOverlay.isActive() || isAfBusy()) { Ui.toast(app, R.string.toast_kst_busy); return REQ_BUSY; }
        if (!KST_IN_FLIGHT.compareAndSet(false, true)) { Ui.toast(app, R.string.toast_kst_busy); return REQ_BUSY; }
        if (!runLong((g, g2) -> runKeystone(app, g), () -> KST_IN_FLIGHT.set(false))) {
            KST_IN_FLIGHT.set(false);
            Ui.toast(app, R.string.toast_kst_busy);                // another long call (404) still runs
            return REQ_BUSY;
        }
        return REQ_OK;
    }

    /** p6-long; KST_IN_FLIGHT is released by requestKeystone's after-hook. */
    private static void runKeystone(Context app, Play6Gmpf g) {
        try {
            int ui = g.getSystemUiState();                         // 390
            int anim = g.getBootAnimState();                       // 394
            if (ui != 1 || anim != 2) {
                Log.w(TAG, "keystone refused: systemUIState=" + ui + " bootAnimState=" + anim);
                Ui.toast(app, R.string.toast_wait_boot);
                return;
            }
            // Through the motor thread: queues behind a start that is already posted (it then sees the
            // stop), sends manualFocus(2) only if something may be moving; then a confirmed idle motor.
            MotorController m = MotorController.peek();
            if (m != null && (!m.stopAndWait(2_000) || !m.isIdleConfirmed())) {
                Log.w(TAG, "keystone refused: motor stop not confirmed (" + m.describe() + ")");
                Ui.toast(app, R.string.toast_failed);
                return;
            }
            Ui.toast(app, R.string.toast_kst);
            byte r = g.autoKst();                                  // 326, seconds inside the HAL
            Log.i(TAG, "326 autoKst() -> " + r);
        } catch (Throwable t) {
            Log.w(TAG, "keystone: " + t);
            Ui.toast(app, R.string.toast_failed);
        }
    }

    /** Stops a manual focus move (screen off, power key): posts to p6-motor, sends 2 only if moving. Any thread. */
    public static void stopMotor() {
        MotorController.stopIfAny();
    }

    /** Opens the manual focus overlay (MotorController sends 300 with 17 / 1 / 0 / 2 only). Main thread. */
    public static void openManualFocus(Context ctx) {
        ManualFocusActivity.open(ctx);
    }

    /** Opens the "Projector" settings activity, optionally at a section. Main thread. */
    public static void openProjectorSettings(Context ctx, String section) {
        SettingsActivity.open(ctx, section);
    }

    /**
     * Smart autofocus (plan 1.2): one switch, the stock order of FocusRepository.setSmartAutoFocus: IGmpf2
     * 64 setPowerOnAFUseDistance, then IGmpf 587 setAngTriggerAF. 587 is sent only when 64 was delivered
     * (the IGmpf2 guard is open). p6-hal only (inside a Task); the quick panel and Projector settings.
     */
    public static void setSmartAutofocus(Play6Gmpf g, Play6Gmpf2 g2, boolean on) throws Exception {
        boolean r64;
        try {
            r64 = g2.setSmartAutofocus(on);                        // IGmpf2 64
        } catch (Play6Gmpf2.ReplyException e) {
            r64 = false;                                           // delivered: go on as stock does
            Log.w(TAG, "IGmpf2 64(" + on + ") reply: " + e.getMessage());
        }
        boolean r587 = g.setMoveAf(on);                            // IGmpf 587
        Log.i(TAG, "smart autofocus " + on + ": IGmpf2 64 -> " + r64 + ", 587 -> " + r587);
    }

    // ================================================================== power events
    /** MAIN thread, in arrival order: every PowerEventListener (they log and read; nothing here acts). */
    private static void dispatchPowerEvent(int type, String value) {
        for (PowerEventListener l : POWER_LISTENERS) {
            try { l.onPowerEvent(type, value); } catch (Throwable t) { Log.w(POWER_TAG, "power listener: " + t); }
        }
    }

    // ================================================================== focus events
    /** AK overlay event range (FEATURE_SPEC 1.1 / 2.6 of the Z9X; 120 = curtain). */
    static boolean isAkEvent(int type) {
        return (type >= 105 && type <= 118) || type == 120;
    }

    /** MAIN thread, in arrival order. */
    private static void dispatchEvent(int type, String value) {
        boolean handled = false;
        if (isAkEvent(type)) {
            try {
                handled = AkOverlay.onFocusEvent(type, value);
            } catch (Throwable t) {
                Log.w(FOCUS_TAG, "AkOverlay.onFocusEvent(" + type + "): " + t);
            }
        } else {
            Log.i(FOCUS_TAG, "focusEvent type=" + type + (value == null || value.isEmpty() ? "" : " value=" + value));
        }
        for (FocusEventListener l : LISTENERS) {
            try { l.onFocusEvent(type, value); } catch (Throwable t) { Log.w(FOCUS_TAG, "focus listener: " + t); }
        }
        if (!handled) onFocusEvent(type);
    }

    /** MAIN thread: the AF busy window and its notices, as the Z9X HalController. */
    private static void onFocusEvent(int type) {
        Context app = sApp;
        long now = SystemClock.elapsedRealtime();
        switch (type) {
            case 4: case 8: case 333: case 334: case 335:
                ManualFocusActivity.closeForAutofocus();           // stock: an AF start closes the manual focus window
                sAfBusyUntil = now + AF_BUSY_MS;
                if (app != null) Ui.toast(app, R.string.toast_af_running);
                break;
            case 5:
                sAfBusyUntil = 0;
                break;
            case 2:
                sAfBusyUntil = 0;
                if (app != null) Ui.toast(app, R.string.toast_af_failed);
                break;
            case 0: case 1:
                MotorController.stopIfAny();                       // motor limit reached
                break;
            default:
                break;
        }
    }
}
