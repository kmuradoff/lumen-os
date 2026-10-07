package org.z9x.projector;

import android.content.Context;
import android.util.Log;

import org.z9x.projector.hal.Gmpf2Client;
import org.z9x.projector.hal.GmpfClient;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public entry point to the vendor HAL for the module packages (panel, ak, power, remote, source,
 * sys). It hides HalController (package-private) and enforces the threading rules:
 *
 *  - {@link #run}: on the single "z9x-hal" worker thread (all getters/setters of the panels, power
 *    policy, AI picture engine). FIFO, one call in flight, never the main thread.
 *  - {@link #query}: same thread; the result (or null on any error / not connected) is delivered on
 *    the main thread.
 *  - {@link #runAk}: on the dedicated "z9x-ak" thread, ONLY for IGmpf 146 uiAkDisplay acks of the AK
 *    overlay, so an ack is never queued behind a slow panel call (the vendor waits at most 5 s per
 *    AK step, FEATURE_SPEC 1.1).
 *  - {@link #runEye}: on the dedicated "z9x-eye" thread, ONLY for the eye-protection UI watchdog
 *    (IGmpf 114 uiHeartBeatDo / 115 uiHeartBeatSet), so a beat is never late behind a slow
 *    z9x-hal task (a missed beat makes the vendor restore full light in front of a person).
 *  - Blocking keystone calls (272/326) go through {@link #requestKeystone} (thread "z9x-kst", with
 *    the motor interlock); focus through {@link #requestAutofocus} / {@link #openManualFocus}.
 *  - The clients themselves refuse calls made on the main thread (RemoteException).
 *
 * Exceptions thrown by a task are caught and logged; a task must not loop or sleep for long.
 * Before the boot handshake connected the HAL, tasks are skipped (logged) and queries return null.
 */
public final class Hal {
    private static final String TAG = "Z9xHalApi";

    private Hal() {}

    /** Work on the HAL thread with both clients. */
    public interface Task {
        void run(GmpfClient gmpf, Gmpf2Client gmpf2) throws Exception;
    }

    /** A read on the HAL thread. */
    public interface Query<T> {
        T run(GmpfClient gmpf, Gmpf2Client gmpf2) throws Exception;
    }

    /** Main-thread result of {@link #query}; value null = failed / not connected. */
    public interface Result<T> {
        void onResult(T value);
    }

    /**
     * Focus/AK/eye-protection events from the vendor (IProjectorFocusCallback.focusEvent), delivered
     * on the MAIN thread in arrival order, after AkOverlay had its turn. Must not block.
     */
    public interface FocusEventListener {
        void onFocusEvent(int type, String value);
    }

    static final CopyOnWriteArrayList<FocusEventListener> LISTENERS = new CopyOnWriteArrayList<>();
    /** Run on the "z9x-hal" thread after every successful (re)connect; must not block. */
    static final CopyOnWriteArrayList<Runnable> CONNECT_LISTENERS = new CopyOnWriteArrayList<>();

    private static HalController controller() {
        return HalController.peek();
    }

    /** True once IGmpf is connected (boot handshake past the connect step). */
    public static boolean isReady() {
        HalController c = controller();
        return c != null && c.isConnected();
    }

    /**
     * v6.3.1: true once the boot handshake is complete (237 goToBootCompleted(1) sent, so the vendor
     * audio thread may start). Any thread.
     */
    public static boolean isHandshakeDone() {
        HalController c = controller();
        return c != null && c.isConnected() && c.isHandshakeDone();
    }

    /** True while an auto keystone (272/326) is queued or running. */
    public static boolean isKeystoneRunning() {
        HalController c = controller();
        return c != null && c.isKeystoneInFlight();
    }

    /** Runs {@code t} on the "z9x-hal" thread. Returns false if it could not be queued. */
    public static boolean run(Task t) {
        HalController c = controller();
        if (c == null) return false;
        return c.halHandler().post(() -> exec(c, t, "run"));
    }

    /** Runs {@code t} on the "z9x-hal" thread after {@code delayMs}. */
    public static boolean runDelayed(Task t, long delayMs) {
        return runDelayed(t, delayMs, null);
    }

    /**
     * Like {@link #runDelayed(Task, long)}, but {@code ifSkipped} runs (on the "z9x-hal" thread)
     * when the task is dropped because the HAL is not connected at that moment, so the caller can
     * re-arm. A false return (not queued at all) does NOT call {@code ifSkipped}.
     */
    public static boolean runDelayed(Task t, long delayMs, Runnable ifSkipped) {
        HalController c = controller();
        if (c == null) return false;
        return c.halHandler().postDelayed(() -> {
            if (!exec(c, t, "runDelayed") && ifSkipped != null) {
                try { ifSkipped.run(); } catch (Throwable e) { Log.w(TAG, "ifSkipped: " + e); }
            }
        }, delayMs);
    }

    /** Runs {@code t} on the "z9x-ak" thread (AK acks only). */
    public static boolean runAk(Task t) {
        HalController c = controller();
        if (c == null) return false;
        return c.akHandler().post(() -> exec(c, t, "runAk"));
    }

    private static SafeHandler sEye;

    private static synchronized SafeHandler eyeHandler() {
        if (sEye == null) sEye = SafeHandler.newThread("z9x-eye");
        return sEye;
    }

    /**
     * Runs {@code t} on the "z9x-eye" thread (EyeGuard watchdog 114/115 only). {@code ifSkipped}
     * (may be null) runs there when the task is dropped because the HAL is not connected. Returns
     * false if it could not be queued (ifSkipped is then NOT called).
     */
    public static boolean runEye(Task t, Runnable ifSkipped) {
        HalController c = controller();
        if (c == null) return false;
        return eyeHandler().post(() -> {
            if (!exec(c, t, "runEye") && ifSkipped != null) {
                try { ifSkipped.run(); } catch (Throwable e) { Log.w(TAG, "ifSkipped: " + e); }
            }
        });
    }

    /** Reads on the "z9x-hal" thread; {@code r} gets the value (or null) on the main thread. */
    public static <T> void query(Query<T> q, Result<T> r) {
        HalController c = controller();
        if (c == null) {
            Ui.main().post(() -> r.onResult(null));
            return;
        }
        boolean queued = c.halHandler().post(() -> {
            T v = null;
            if (c.isConnected()) {
                try {
                    v = q.run(c.client(), c.client2());
                } catch (Throwable e) {
                    Log.w(TAG, "query: " + e);
                }
            }
            final T out = v;
            Ui.main().post(() -> {
                try { r.onResult(out); } catch (Throwable e) { Log.w(TAG, "query result: " + e); }
            });
        });
        if (!queued) Ui.main().post(() -> r.onResult(null));
    }

    /** Returns false when the task was skipped because the HAL is not connected. */
    private static boolean exec(HalController c, Task t, String what) {
        if (!c.isConnected()) {
            Log.i(TAG, what + ": HAL not connected, task skipped");
            return false;
        }
        try {
            t.run(c.client(), c.client2());
        } catch (Throwable e) {
            Log.w(TAG, what + ": " + e);
        }
        return true;
    }

    /**
     * Stops a manual focus move and waits for it (from a worker thread, never the main thread):
     * true when the stop ran within {@code timeoutMs} AND the motor is idle with the persisted
     * manual_moving flag clear. True when there is no controller (nothing of ours can move).
     */
    public static boolean stopMotorAndWait(long timeoutMs) {
        HalController c = controller();
        if (c == null) return true;
        MotorController m = c.motor();
        return m.stopAndWait(timeoutMs) && m.isIdleConfirmed();
    }

    /** /proc/sys/kernel/random/boot_id of this boot, or null when unreadable. Any thread. */
    public static String bootId() { return HalController.bootId(); }

    /** {@code r} runs on the "z9x-hal" thread after every successful HAL (re)connect. Cheap only. */
    public static void addConnectedListener(Runnable r) { CONNECT_LISTENERS.addIfAbsent(r); }

    // ------------------------------------------------------------------ existing user actions
    /** Request results (Lumen OS 1.0, SetupBridge). */
    public static final int REQ_OK = HalController.REQ_OK, REQ_BUSY = HalController.REQ_BUSY,
            REQ_NOT_READY = HalController.REQ_NOT_READY, REQ_UNSUPPORTED = HalController.REQ_UNSUPPORTED;

    /** Remote-style autofocus (sendFocusStatus 44), debounced. Main thread. Returns REQ_*. */
    public static int requestAutofocus(Context ctx) {
        return HalController.get(ctx).requestAutofocus();
    }

    /** Auto keystone now (326), or fit to screen (272 with 11) when {@code fit}. Main thread. Returns REQ_*. */
    public static int requestKeystone(Context ctx, boolean fit) {
        return HalController.get(ctx).requestKeystone(fit);
    }

    /** An autofocus is running (vendor focus events). Any thread. */
    public static boolean isAfBusy() {
        HalController c = controller();
        return c != null && c.isAfBusy();
    }

    /** Toggle names of {@link #readToggles} / {@link #setToggle} (HalController.Toggle). */
    public static String[] toggleNames() {
        HalController.Toggle[] v = HalController.Toggle.values();
        String[] out = new String[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i].name();
        return out;
    }

    /** Reads every toggle on z9x-hal; {@code cb} gets name -> value (null = unknown) on the main thread. */
    public static void readToggles(Context ctx, java.util.function.Consumer<java.util.Map<String, Boolean>> cb) {
        HalController.get(ctx).readToggles(m -> cb.accept(byName(m)));
    }

    /**
     * User action only (same coupling helpers as the panel): sets one toggle by name, then reads all back.
     * Returns false for an unknown name (nothing sent).
     */
    public static boolean setToggle(Context ctx, String name, boolean on,
                                    java.util.function.Consumer<java.util.Map<String, Boolean>> cb) {
        HalController.Toggle t;
        try {
            t = HalController.Toggle.valueOf(name);
        } catch (Throwable e) {
            return false;
        }
        HalController.get(ctx).setToggle(t, on, m -> cb.accept(byName(m)));
        return true;
    }

    private static java.util.Map<String, Boolean> byName(java.util.Map<HalController.Toggle, Boolean> m) {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<HalController.Toggle, Boolean> e : m.entrySet()) out.put(e.getKey().name(), e.getValue());
        return out;
    }

    /** Opens the manual-focus overlay (motor whitelist 17/0/1/2 inside MotorController). */
    public static void openManualFocus(Context ctx) {
        ManualFocusActivity.open(ctx);
    }

    /** Opens the full "Projector" settings activity, optionally at a section. */
    public static void openProjectorSettings(Context ctx, String section) {
        SettingsActivity.open(ctx, section);
    }

    // ------------------------------------------------------------------ projection flags
    // One sequence for every UI that writes these flags (quick panel and SettingsActivity), with
    // the stock interlocks. HAL thread only (inside a Task); the clients refuse the main thread.

    /** 290 fit to screen; ON also turns 292 obstacle avoidance on (FEATURE_SPEC 2.3). */
    public static void setCurtainFitCoupled(GmpfClient g, boolean on) throws Exception {
        g.setCurtainFit(on);
        if (on) g.setObstacleAvoid(true);
    }

    /** 292 obstacle avoidance; OFF also turns 290 fit to screen off (FEATURE_SPEC 2.3). */
    public static void setObstacleAvoidCoupled(GmpfClient g, boolean on) throws Exception {
        g.setObstacleAvoid(on);
        if (!on) g.setCurtainFit(false);
    }

    /**
     * 585 real-time keystone. Turning it ON is refused (false, nothing written) while 173 reports a
     * ceiling mount (1 / 3) or cannot be read (FEATURE_SPEC 2.2). OFF is always written.
     */
    public static boolean setMoveAkChecked(GmpfClient g, boolean on) throws Exception {
        if (on) {
            GmpfClient.PutMode m = g.getPutMode();                    // 173
            if (m == null || m == GmpfClient.PutMode.CEILING_FRONT || m == GmpfClient.PutMode.CEILING_REAR) {
                Log.i(TAG, "585(true) refused: put mode " + m);
                return false;
            }
        }
        g.setMoveAk(on);
        return true;
    }

    // ------------------------------------------------------------------ focus events
    public static void addFocusEventListener(FocusEventListener l) { LISTENERS.addIfAbsent(l); }

    public static void removeFocusEventListener(FocusEventListener l) { LISTENERS.remove(l); }
}
