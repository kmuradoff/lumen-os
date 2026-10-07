package org.z9x.setup;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client of the Z9xProjector SetupBridge (setup/bridge_api.md). Z9xSetup never talks to the HAL or
 * to Bluetooth bonding itself: it only sends these named methods. There is no generic call; the
 * method set below is the whole surface, so no calibration or factory code is reachable from here.
 *
 * Every call runs on a worker thread with a timeout (default 1.5 s) and always yields a non-null
 * Bundle with "ok" (boolean); on failure "err" is one of the bridge errors or
 * missing | timeout | exception | null.
 */
public final class Bridge {
    public static final String AUTHORITY = "org.z9x.projector.setupbridge";
    public static final String PERMISSION = "org.z9x.projector.permission.SETUP_BRIDGE";
    public static final String ACTION_STATE = "org.z9x.projector.action.SETUP_STATE";
    public static final int API = 1;

    public static final long TIMEOUT_MS = 1500;
    /** set_launcher waits for RoleManager's callback inside Z9xProjector. */
    public static final long LAUNCHER_TIMEOUT_MS = 6000;

    public static final String M_PING = "ping";
    public static final String M_REMOTE_STATE = "remote_state";
    public static final String M_HAL_STATE = "hal_state";
    public static final String M_AF_RUN = "af_run";
    public static final String M_KST_AUTO = "kst_auto";
    public static final String M_KST_FIT = "kst_fit";
    public static final String M_KST_MANUAL = "kst_manual";
    public static final String M_FOCUS_MANUAL = "focus_manual";
    public static final String M_TOGGLES_GET = "toggles_get";
    public static final String M_TOGGLE_SET = "toggle_set";
    public static final String M_SET_DEVICE_NAME = "set_device_name";
    public static final String M_SET_LAUNCHER = "set_launcher";
    public static final String M_FEATURE = "feature";

    private static final Set<String> METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            M_PING, M_REMOTE_STATE, M_HAL_STATE, M_AF_RUN, M_KST_AUTO, M_KST_FIT, M_KST_MANUAL,
            M_FOCUS_MANUAL, M_TOGGLES_GET, M_TOGGLE_SET, M_SET_DEVICE_NAME, M_SET_LAUNCHER, M_FEATURE)));

    /** Toggle names of toggles_get / toggle_set that the wizard shows (subset of the bridge's). */
    public static final String T_POWER_ON_AF = "POWER_ON_AF";
    public static final String T_MOVE_AK = "MOVE_AK";

    public interface Callback {
        void onResult(Bundle r);
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "z9x-bridge");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bridge() {}

    public static boolean ok(Bundle r) {
        return r != null && r.getBoolean("ok", false);
    }

    public static String err(Bundle r) {
        return r == null ? "null" : r.getString("err", ok(r) ? "" : "unknown");
    }

    private static Bundle fail(String err) {
        Bundle b = new Bundle();
        b.putBoolean("ok", false);
        b.putString("err", err);
        return b;
    }

    private static Bundle doCall(Context c, String method, String arg, Bundle extras) {
        if (!METHODS.contains(method)) return fail("denied");
        ContentResolver cr = c.getApplicationContext().getContentResolver();
        ContentProviderClient cpc = null;
        try {
            cpc = cr.acquireUnstableContentProviderClient(AUTHORITY);
            if (cpc == null) return fail("missing");
            Bundle b = cpc.call(method, arg, extras);
            return b == null ? fail("null") : b;
        } catch (Throwable t) {
            L.w("bridge " + method + " exception", t);
            return fail("exception");
        } finally {
            if (cpc != null) cpc.close();
        }
    }

    /** Blocking call with a timeout. Never call on the main thread. */
    public static Bundle callSync(Context c, String method, String arg, Bundle extras, long timeoutMs) {
        long t0 = SystemClock.elapsedRealtime();
        Bundle r;
        Future<Bundle> f = POOL.submit(() -> doCall(c, method, arg, extras));
        try {
            r = f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            r = fail("timeout");
        } catch (Throwable t) {
            r = fail("exception");
        }
        log(method, r, t0);
        return r;
    }

    /** Async call; the callback runs on the main thread exactly once (result or timeout). */
    public static void call(Context c, String method, String arg, Bundle extras, long timeoutMs, Callback cb) {
        final long t0 = SystemClock.elapsedRealtime();
        final AtomicBoolean done = new AtomicBoolean();
        final Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {
                Bundle r = fail("timeout");
                log(method, r, t0);
                if (cb != null) cb.onResult(r);
            }
        };
        MAIN.postDelayed(timeout, timeoutMs);
        POOL.execute(() -> {
            Bundle r = doCall(c, method, arg, extras);
            MAIN.post(() -> {
                if (done.compareAndSet(false, true)) {
                    MAIN.removeCallbacks(timeout);
                    log(method, r, t0);
                    if (cb != null) cb.onResult(r);
                }
            });
        });
    }

    private static void log(String method, Bundle r, long t0) {
        // the 2 Hz / 1 Hz state polls would flood the log; log them only when they fail
        boolean poll = M_HAL_STATE.equals(method) || M_REMOTE_STATE.equals(method);
        if (poll && ok(r)) return;
        L.i("bridge " + method + " -> " + (ok(r) ? "ok" : err(r)) + " "
                + (SystemClock.elapsedRealtime() - t0) + "ms");
    }

    // ------------------------------------------------------------------ typed methods

    public static void ping(Context c, Callback cb) { call(c, M_PING, null, null, TIMEOUT_MS, cb); }

    public static void remoteState(Context c, Callback cb) { call(c, M_REMOTE_STATE, null, null, TIMEOUT_MS, cb); }

    public static void halState(Context c, Callback cb) { call(c, M_HAL_STATE, null, null, TIMEOUT_MS, cb); }

    public static void afRun(Context c, Callback cb) { call(c, M_AF_RUN, null, null, TIMEOUT_MS, cb); }

    public static void kstAuto(Context c, Callback cb) { call(c, M_KST_AUTO, null, null, TIMEOUT_MS, cb); }

    public static void kstFit(Context c, Callback cb) { call(c, M_KST_FIT, null, null, TIMEOUT_MS, cb); }

    public static void kstManual(Context c, Callback cb) { call(c, M_KST_MANUAL, null, null, TIMEOUT_MS, cb); }

    public static void focusManual(Context c, Callback cb) { call(c, M_FOCUS_MANUAL, null, null, TIMEOUT_MS, cb); }

    public static void togglesGet(Context c, Callback cb) { call(c, M_TOGGLES_GET, null, null, TIMEOUT_MS, cb); }

    public static void toggleSet(Context c, String name, boolean on, Callback cb) {
        if (!T_POWER_ON_AF.equals(name) && !T_MOVE_AK.equals(name)) {
            if (cb != null) cb.onResult(fail("denied"));
            return;
        }
        Bundle e = new Bundle();
        e.putString("name", name);
        e.putBoolean("on", on);
        call(c, M_TOGGLE_SET, null, e, TIMEOUT_MS, cb);
    }

    public static void feature(Context c, String name, Callback cb) { call(c, M_FEATURE, name, null, TIMEOUT_MS, cb); }

    /** 1..32 chars after trimming, as the bridge requires. Returns null when invalid. */
    public static String cleanName(String name) {
        if (name == null) return null;
        String n = name.trim().replaceAll("\\s+", " ");
        if (n.isEmpty()) return null;
        if (n.length() > 32) n = n.substring(0, 32).trim();
        return n;
    }

    public static Bundle setDeviceNameSync(Context c, String name) {
        String n = cleanName(name);
        if (n == null) return fail("denied");
        Bundle e = new Bundle();
        e.putString("name", n);
        return callSync(c, M_SET_DEVICE_NAME, null, e, TIMEOUT_MS);
    }

    /** target = z9x | classic. Reply: ok, holder. */
    public static Bundle setLauncherSync(Context c, String target) {
        if (!SetupState.LAUNCHER_LUMEN.equals(target) && !SetupState.LAUNCHER_CLASSIC.equals(target)) {
            return fail("denied");
        }
        Bundle e = new Bundle();
        e.putString("target", target);
        return callSync(c, M_SET_LAUNCHER, null, e, LAUNCHER_TIMEOUT_MS);
    }
}
