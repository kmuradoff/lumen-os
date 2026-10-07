package org.z9x.projector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.IHwBinder;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.hal.Gmpf2Client;
import org.z9x.projector.hal.GmpfClient;

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
 * Owner of every IGmpf interaction of the app.
 *
 * Threads: "z9x-hal" (handshake, reconnect, getters/setters, callback-driven work, Hal.run/query
 * of the modules), "z9x-motor" (MotorController: manualFocus + watchdog), "z9x-kst" (blocking
 * autoKst/newAutoKst), "z9x-ak" (IGmpf 146 acks of the AK overlay only, Hal.runAk).
 * Nothing here runs a HAL call on the main thread (the clients also refuse it).
 *
 * v6.1: the feature kill switch (sys.z9x.feat / z9x_features.rc, lastboot/strike/featblock) is
 * removed (V61_REQUIREMENTS item 13): the HAL features and the handshake are always on.
 *
 * Focus events: the hwbinder callback (GmpfClient.FocusCallback) replies at once and posts the event
 * to the MAIN thread (dispatchEvent): AK events 105-118 and 120 go to AkOverlay.onFocusEvent first;
 * then every Hal.FocusEventListener sees the event; unless AkOverlay handled it, the old handling
 * (AF toasts, motor-limit stop, curtain hint) runs on the "z9x-hal" thread.
 *
 * Boot handshake (RESULT_hal.json corrected order), once per process:
 *   wait sys.boot_completed=1 and (vendor.xgimi.ledOn=true or 30 s after boot_completed was seen);
 *   a process that starts long after boot (crash/restart of the persistent process: boot_completed
 *   already 1 at the first poll and uptime >= 3 min) skips the lamp wait, so a flagged move is
 *   stopped at once
 *   -> getService + linkToDeath (retry with backoff)
 *   -> if manual_moving flag: manualFocus(2)
 *   -> 298 connectProjectorFocusManager(pid, cb) -> 306 setProjectorFocusListener(pid, same cb)
 *   -> 391 setSystemUIState(1) -> 237 goToBootCompleted(0) once per boot_id
 *   -> wait init.svc.bootanim != running (100 ms poll, max 180 s)
 *   -> 395 setBootAnimState(2) -> 237 goToBootCompleted(1) once per boot_id
 *   -> 307 sendFocusStatus(17) LAST, once per boot_id.
 * boot_id guards are fail-open: the boot_id is persisted only after the call reached the HAL,
 * and an unreadable boot_id disables the cross-process guard (never persists a placeholder).
 * Within one process every one-shot call is sent at most twice (one retry if not delivered).
 *
 * HAL death: reconnect with backoff, stop a flagged move, re-register 298/306, then read 394/390;
 * if 394 != 2 although we had set it (gmpf_main lost its state) resend 391(1), 395(2), 237(0),
 * 237(1), but never 17. After every (re)connect the Hal.addConnectedListener hooks run (PowerPolicy:
 * re-check 196 and send 195(true) when a lamp-on is still pending while the screen is on).
 */
final class HalController {
    private static final String TAG = "Z9xHal";

    private static final long LED_WAIT_MAX_MS = 30_000;
    /** Uptime from which a process that finds boot_completed=1 at its first poll is a late (re)start. */
    private static final long LATE_START_UPTIME_MS = 180_000;
    /** Event 120: at most one offer per 10 s, none within 30 s after our own keystone, 5 per process. */
    private static final long CURTAIN_OFFER_GAP_MS = 10_000;
    private static final long CURTAIN_QUIET_AFTER_KST_MS = 30_000;
    private static final int MAX_CURTAIN_OFFERS = 5;
    private static final long BOOT_POLL_MS = 500;
    private static final long BOOTANIM_POLL_MS = 100;
    private static final long BOOTANIM_MAX_WAIT_MS = 180_000;
    private static final long RECONNECT_MIN_MS = 500;
    private static final long RECONNECT_MAX_MS = 10_000;
    private static final long AF_DEBOUNCE_MS = 1_000;
    private static final long AF_BUSY_MS = 15_000;
    private static final int MAX_EVENTS = 20;
    private static final int MAX_RESTART_RESENDS = 3;

    private static final String K_BOOT237_0 = "boot_id_237_0";
    private static final String K_HS_STARTED = "boot_id_hs_started";
    private static final String K_BOOT237_1 = "boot_id_237_1";
    private static final String K_BOOT17 = "boot_id_307_17";

    // Handshake stages
    private static final int ST_IDLE = 0, ST_WAIT_BOOT = 1, ST_CONNECT = 2, ST_EARLY = 3,
            ST_WAIT_ANIM = 4, ST_LATE = 5, ST_DONE = 6, ST_OFF = 7;

    enum Toggle { POWER_ON_AF, POWER_ON_AK, MOVE_AF, MOVE_AK, CURTAIN_FIT, OBSTACLE }

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
     * (V61_REQUIREMENTS item 13), so the projector features are always on. Kept as a method so the
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
    private long bootSeenAt = -1;
    private boolean bootPolled, lateStart;
    private int curtainOffers;
    private long animWaitStart = -1;
    private long reconnectDelay = RECONNECT_MIN_MS;
    private long generation;
    private boolean reconnecting;
    private boolean registered298, registered306;
    private int registerRetries;
    private boolean sentUiState, sentBootAnim;
    private boolean done237_0, done237_1, done17;
    private int tries237_0, tries237_1, tries17, tries391, tries395;
    private int restartResends;
    private long lastCurtainEventAt = -100_000L;

    private volatile boolean connected;
    private volatile long afBusyUntil;
    /** elapsedRealtime when our last autoKst/newAutoKst returned (kst thread writes). */
    private volatile long lastKstEndAt = -100_000L;
    private long lastAfRequestAt = -100_000L;   // main thread

    private final IHwBinder.DeathRecipient death = new IHwBinder.DeathRecipient() {
        @Override public void serviceDied(long cookie) {
            hal.post(() -> onHalDied(cookie));
        }
    };

    private final Runnable tick = this::tick;
    private final Runnable reconnectTick = this::reconnectTick;

    private HalController(Context app) {
        this.app = app;
        this.state = app.getSharedPreferences("hal_state", Context.MODE_PRIVATE);
        // hwbinder thread: only post (the reply was already sent by FocusCallback).
        this.client = new GmpfClient((type, value) -> Ui.main().post(() -> dispatchEvent(type, value)));
        this.motor = new MotorController(app, client, state, kstInFlight);
    }

    MotorController motor() { return motor; }

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
            stage = ST_WAIT_BOOT;
            Log.i(TAG, "start: waiting for sys.boot_completed and the lamp");
            hal.post(tick);
        });
    }

    void onBootCompleted() {
        start();
    }

    String stageText() {
        switch (stage) {
            case ST_IDLE: return "not started";
            case ST_WAIT_BOOT: return "waiting for boot and lamp";
            case ST_CONNECT: return "connecting to the HAL";
            case ST_EARLY: return "handshake (early part)";
            case ST_WAIT_ANIM: return "waiting for the boot animation to end";
            case ST_LATE: return "handshake (late part)";
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
            case ST_WAIT_BOOT: {
                boolean bc = "1".equals(SystemProperties.get("sys.boot_completed"));
                if (!bootPolled) {
                    bootPolled = true;
                    // Restarted within a boot that already passed the lamp wait (the persistent
                    // process crashed): connect at once so that a flagged (stale) move gets its stop
                    // without a 30 s delay. Decided by a per-boot marker, not by uptime: a slow first
                    // boot must still wait for the lamp.
                    String boot = bootId();
                    lateStart = bc && boot != null && boot.equals(state.getString(K_HS_STARTED, null));
                }
                if (!bc) {
                    hal.postDelayed(tick, BOOT_POLL_MS);
                    return;
                }
                if (bootSeenAt < 0) bootSeenAt = now;
                boolean led = "true".equals(SystemProperties.get("vendor.xgimi.ledOn"));
                if (!led && !lateStart && now - bootSeenAt < LED_WAIT_MAX_MS) {
                    hal.postDelayed(tick, BOOT_POLL_MS);
                    return;
                }
                Log.i(TAG, "boot completed, ledOn=" + led + " lateStart=" + lateStart
                        + " (waited " + (now - bootSeenAt) + " ms)");
                persistBoot(K_HS_STARTED, bootId());
                stage = ST_CONNECT;
                hal.post(tick);
                return;
            }
            case ST_CONNECT: {
                if (!connectAndPrepare()) {
                    hal.postDelayed(tick, nextBackoff());
                    return;
                }
                fireConnected();
                stage = ST_EARLY;
                hal.post(tick);
                return;
            }
            case ST_EARLY: {
                if (!connected) return;                       // reconnect resumes the tick
                if (!sentUiState) {
                    if (!step391()) return;
                }
                if (!done237_0) {
                    if (!step237(GmpfClient.BootStage.EARLY)) return;
                }
                stage = ST_WAIT_ANIM;
                animWaitStart = now;
                hal.post(tick);
                return;
            }
            case ST_WAIT_ANIM: {
                boolean running = "running".equals(SystemProperties.get("init.svc.bootanim"));
                if (running && now - animWaitStart < BOOTANIM_MAX_WAIT_MS) {
                    hal.postDelayed(tick, BOOTANIM_POLL_MS);
                    return;
                }
                if (running) Log.w(TAG, "bootanim still running after " + BOOTANIM_MAX_WAIT_MS + " ms, continuing");
                stage = ST_LATE;
                hal.post(tick);
                return;
            }
            case ST_LATE: {
                if (!connected) return;
                if (!sentBootAnim) {
                    if (!step395()) return;
                }
                if (!done237_1) {
                    if (!step237(GmpfClient.BootStage.AFTER_BOOTANIM)) return;
                }
                if (!done17) {
                    if (!step17()) return;
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

    private long nextBackoff() {
        long d = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
        return d;
    }

    /** getService + linkToDeath, stop a flagged move, register 298 then 306. */
    private boolean connectAndPrepare() {
        long cookie = ++generation;
        if (!client.connect(death, cookie)) return false;
        connected = true;
        reconnectDelay = RECONNECT_MIN_MS;
        registered298 = registered306 = false;
        registerRetries = 0;
        motor.stopIfFlaggedAndWait(3_000);
        registerListener();
        notifyListeners();
        return true;
    }

    private void registerListener() {
        if (!connected) return;
        try {
            if (!registered298) {
                client.connectFocusManager();                  // 298
                registered298 = true;
            }
            if (!registered306) {
                client.setFocusListener();                     // 306, same callback object
                registered306 = true;
            }
            Log.i(TAG, "focus listener registered (298 + 306)");
        } catch (GmpfClient.ReplyException e) {
            // reached the HAL: do not send it again
            if (!registered298) registered298 = true; else registered306 = true;
            Log.w(TAG, "listener registration reply: " + e);
            if (!registered306) hal.postDelayed(this::registerListener, 1_000);
        } catch (Throwable t) {
            Log.w(TAG, "listener registration failed: " + t);
            if (connected && ++registerRetries <= 3) hal.postDelayed(this::registerListener, 1_000);
        }
    }

    /** 391(1). Returns true when the handshake may continue (sent, or given up after retries). */
    private boolean step391() {
        try {
            client.setSystemUiReady();
            sentUiState = true;
            Log.i(TAG, "setSystemUIState(1)");
            return true;
        } catch (Throwable t) {
            return retryOrSkip("391", ++tries391, 5, t, () -> sentUiState = true);
        }
    }

    /** 395(2). */
    private boolean step395() {
        try {
            client.setBootAnimFinished();
            sentBootAnim = true;
            Log.i(TAG, "setBootAnimState(2)");
            return true;
        } catch (Throwable t) {
            return retryOrSkip("395", ++tries395, 5, t, () -> sentBootAnim = true);
        }
    }

    /** 237(stage) once per boot_id (fail-open), at most two attempts per process. */
    private boolean step237(GmpfClient.BootStage s) {
        boolean early = s == GmpfClient.BootStage.EARLY;
        String key = early ? K_BOOT237_0 : K_BOOT237_1;
        String boot = bootId();
        if (boot != null && boot.equals(state.getString(key, null))) {
            Log.i(TAG, "goToBootCompleted(" + (early ? 0 : 1) + ") already sent for this boot");
            markDone237(early);
            return true;
        }
        try {
            boolean ok = client.goToBootCompleted(s);
            Log.i(TAG, "goToBootCompleted(" + (early ? 0 : 1) + ") -> " + ok);
            persistBoot(key, boot);
            markDone237(early);
            return true;
        } catch (GmpfClient.ReplyException e) {
            Log.w(TAG, "goToBootCompleted reply error (delivered, not repeated): " + e);
            persistBoot(key, boot);
            markDone237(early);
            return true;
        } catch (Throwable t) {
            int n = early ? ++tries237_0 : ++tries237_1;
            return retryOrSkip("237", n, 2, t, () -> markDone237(early));
        }
    }

    private void markDone237(boolean early) {
        if (early) done237_0 = true; else done237_1 = true;
    }

    /** 307(17) LAST, once per boot_id (fail-open), at most two attempts per process. */
    private boolean step17() {
        String boot = bootId();
        if (boot != null && boot.equals(state.getString(K_BOOT17, null))) {
            Log.i(TAG, "sendFocusStatus(17) already sent for this boot");
            done17 = true;
            return true;
        }
        try {
            client.sendPowerOnUiReady();
            Log.i(TAG, "sendFocusStatus(17)");
            persistBoot(K_BOOT17, boot);
            done17 = true;
            return true;
        } catch (GmpfClient.ReplyException e) {
            Log.w(TAG, "sendFocusStatus(17) reply error (delivered): " + e);
            persistBoot(K_BOOT17, boot);
            done17 = true;
            return true;
        } catch (Throwable t) {
            return retryOrSkip("307(17)", ++tries17, 2, t, () -> done17 = true);
        }
    }

    /**
     * Failure of a handshake step that did not reach the HAL. If the HAL is gone, wait for the
     * reconnect (which re-posts the tick). Otherwise retry after 1 s up to max attempts, then skip.
     */
    private boolean retryOrSkip(String what, int attempt, int max, Throwable t, Runnable skip) {
        Log.w(TAG, what + " failed (attempt " + attempt + "/" + max + "): " + t);
        if (attempt >= max) {
            Log.e(TAG, what + ": giving up for this process");
            skip.run();
            return true;
        }
        if (connected) hal.postDelayed(tick, 1_000);
        return false;
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
        Log.w(TAG, "IGmpf died");
        connected = false;
        client.dropConnection();
        client2.dropConnection();                             // same vendor process (gmpfHw)
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
        Log.i(TAG, "reconnected to IGmpf");
        checkVendorStateAfterReconnect();
        fireConnected();
        if (stage != ST_DONE) hal.post(tick);                 // resume an unfinished handshake
    }

    /** hal thread: Hal.addConnectedListener hooks (e.g. PowerPolicy re-checks the lamp). */
    private void fireConnected() {
        for (Runnable r : Hal.CONNECT_LISTENERS) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "connected listener: " + t); }
        }
    }

    /** After a reconnect: if gmpf_main lost the handshake state, resend it (never 17). */
    private void checkVendorStateAfterReconnect() {
        int anim, ui;
        try {
            anim = client.getBootAnimState();
            ui = client.getSystemUiState();
        } catch (Throwable t) {
            Log.w(TAG, "394/390 after reconnect: " + t);
            return;
        }
        Log.i(TAG, "after reconnect: bootAnimState=" + anim + " systemUIState=" + ui);
        if (sentBootAnim && anim != 2) {
            if (restartResends >= MAX_RESTART_RESENDS) {
                Log.e(TAG, "vendor state lost again; resend limit reached");
                return;
            }
            restartResends++;
            Log.w(TAG, "gmpf_main lost its boot state: resending 391(1), 395(2), 237(0), 237(1) (not 17)");
            try { client.setSystemUiReady(); } catch (Throwable t) { Log.w(TAG, "391: " + t); }
            try { client.setBootAnimFinished(); } catch (Throwable t) { Log.w(TAG, "395: " + t); }
            try { client.goToBootCompleted(GmpfClient.BootStage.EARLY); } catch (Throwable t) { Log.w(TAG, "237(0): " + t); }
            try { client.goToBootCompleted(GmpfClient.BootStage.AFTER_BOOTANIM); } catch (Throwable t) { Log.w(TAG, "237(1): " + t); }
        } else if (sentUiState && ui != 1) {
            try { client.setSystemUiReady(); Log.i(TAG, "391(1) resent"); } catch (Throwable t) { Log.w(TAG, "391: " + t); }
        }
    }

    // ================================================================== focus events
    /** AK overlay event range forwarded to AkOverlay first (FEATURE_SPEC 1.1 / 2.6, 120 = curtain). */
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
        if (isAkEvent(type)) {
            try {
                handled = AkOverlay.onFocusEvent(type, value);
            } catch (Throwable t) {
                Log.w(TAG, "AkOverlay.onFocusEvent(" + type + "): " + t);
            }
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
            case 4: case 8: case 333: case 334: case 335:
                afBusyUntil = now + AF_BUSY_MS;
                Ui.toast(app, R.string.toast_af_running);
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
     * runs only on the user's click), so this never calls a keystone itself: if "Fit to the screen"
     * is on it shows a hint that points to "Fit to screen now". Ignored while a
     * keystone runs and for 30 s after ours returned; at most one hint per 10 s and 5 per process.
     * hal thread.
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
                Ui.toastLong(app, R.string.toast_curtain_offer);
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
            try { org.z9x.projector.setup.SetupEvents.kst(app); } catch (Throwable ignored) { }
        }
    }

    // ================================================================== toggles
    void readToggles(ToggleResult cb) {
        hal.post(() -> {
            Map<Toggle, Boolean> m = new EnumMap<>(Toggle.class);
            for (Toggle t : Toggle.values()) m.put(t, connected && featureEnabled() ? safeGet(t) : null);
            Ui.main().post(() -> cb.onToggles(m));
        });
    }

    /** Called only on a user click. Sets, then reads back. */
    void setToggle(Toggle t, boolean on, ToggleResult cb) {
        hal.post(() -> {
            if (!featureEnabled() || !connected) {
                Ui.toast(app, R.string.toast_not_ready);
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
            for (Toggle x : Toggle.values()) m.put(x, connected && featureEnabled() ? safeGet(x) : null);
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
            case MOVE_AF: client.setMoveAf(on); break;
            case MOVE_AK:                                      // not on a ceiling mount (FS 2.2)
                if (!Hal.setMoveAkChecked(client, on)) Ui.toast(app, R.string.toast_rt_kst_ceiling);
                break;
            case CURTAIN_FIT: Hal.setCurtainFitCoupled(client, on); break;      // ON also obstacle ON
            case OBSTACLE: Hal.setObstacleAvoidCoupled(client, on); break;      // OFF also fit OFF
            default: break;
        }
    }

    // ================================================================== diagnostics
    void readDiagnostics(TextResult cb) {
        hal.post(() -> {
            StringBuilder sb = new StringBuilder();
            line(sb, "ro.z9x.version", prop("ro.z9x.version"));
            line(sb, "vendor.xgimi.ledOn", prop("vendor.xgimi.ledOn"));
            String gm = prop("init.svc.gmpf_main");
            line(sb, "gmpf_main", "running".equals(gm) ? "running" : ("NOT running (" + gm + ")"));
            line(sb, "gmpfHw", prop("init.svc.gmpfHw"));
            line(sb, "HAL", connected ? "connected" : "not connected");
            line(sb, "IGmpf2", client2.isConnected() ? "connected" : "not connected (lazy)");
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
