package org.lumen.projector.speaker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * The Bluetooth A2DP role of the Play 6 for the speaker mode (P3 item 21; docs/play6/TASKS.md "P3 stock
 * features", risk notes there).
 *
 * Stock v1.7.116 (Android 11) runs A2DP source AND sink at once (dumpsys bluetooth_manager: "A2DP Source State:
 * Enabled", "A2DP Sink State: Enabled", a phone with A2DP_SINK=100 next to headphones with A2DP=100). The
 * Android 14 Bluetooth app (com.android.btservices, AP2A) starts each profile from its sysprop at process start
 * (Config.PROFILE_SERVICES_AND_FLAGS: bluetooth.profile.a2dp.source.enabled / .a2dp.sink.enabled /
 * .avrcp.controller.enabled), and its native stack only runs both A2DP roles together with
 * bluetooth.a2dp.src_sink_coexist.enabled=true (libbluetooth_jni.so), a path not tested on this device. Our image
 * enables the source only (headphones). So the speaker mode switches the role:
 *
 *  speaker: sys.lumen.p6.bt_role=speaker -> init (overlay/play6/play6_base.rc) sets a2dp.source false,
 *           a2dp.sink true, avrcp.controller true -> Bluetooth off, wait OFF -> on (the Bluetooth process exits
 *           when its AdapterService is destroyed, AdapterService.onDestroy System.exit, so the new process reads
 *           the new sysprops) -> wait ON -> the A2DP sink profile must answer getProfileProxy.
 *  normal:  sys.lumen.p6.bt_role=normal -> the image values back (source true, sink and controller false) ->
 *           the same restart. Headphones work again.
 * Already both roles at boot (opt-in persist.lumen.p6.bt_coexist=1, overlay/play6/play6_base.rc; the default from
 * 2026-10-21 to 1.0.6, dropped because AVRCP to BlueZ speakers broke): nothing is switched.
 * The image without the rc lines (sys.lumen.p6.bt_roles is not 1): {@link #supported} is false and the speaker
 * mode is hidden (owner rule: nothing unsupported is shown).
 *
 * Risks (TASKS.md): the BLE remote drops for the seconds of each restart (its keys come back by themselves, as
 * after any Bluetooth restart); a restart that stops in BLE_ON keeps the old process (then the sink check fails
 * and the role goes back to normal); our sys.* write needs the property permission of the projector app
 * (SELinux is permissive on the Play 6 today, ro.boot.selinux).
 *
 * All work on the "p6-btrole" thread; the result comes back on that thread. Log tag P6BtRole.
 */
public final class BtRole {
    static final String TAG = "P6BtRole";
    /** Written by us, read by init (play6_base.rc). */
    static final String PROP_ROLE = "sys.lumen.p6.bt_role";
    /** Set to 1 by init at early-boot: the image has the role triggers. */
    static final String PROP_ROLES = "sys.lumen.p6.bt_roles";
    static final String PROP_SINK = "bluetooth.profile.a2dp.sink.enabled";
    static final String PROP_SOURCE = "bluetooth.profile.a2dp.source.enabled";
    static final String ROLE_SPEAKER = "speaker", ROLE_NORMAL = "normal";

    private static final long PROP_WAIT_MS = 3_000;
    private static final long OFF_WAIT_MS = 12_000;
    private static final long ON_WAIT_MS = 20_000;
    private static final long PROFILE_WAIT_MS = 8_000;
    private static final long POLL_MS = 200;

    /** Result on the p6-btrole thread. */
    public interface Done { void done(boolean ok); }

    private static Handler sH;

    private BtRole() {}

    private static synchronized Handler handler() {
        if (sH == null) {
            HandlerThread t = new HandlerThread("p6-btrole");
            t.start();
            sH = new Handler(t.getLooper());
        }
        return sH;
    }

    static String prop(String name) {
        try { return SystemProperties.get(name, "").trim(); } catch (Throwable t) { return ""; }
    }

    /** Both A2DP roles run already (the coexist image): no switch is needed. */
    static boolean coexist() {
        return "true".equals(prop(PROP_SINK)) && "true".equals(prop(PROP_SOURCE));
    }

    /** The image can give the projector the A2DP sink role (role triggers, or both roles at boot). */
    public static boolean supported() {
        return coexist() || "1".equals(prop(PROP_ROLES));
    }

    /** The speaker role is set right now (our property). */
    public static boolean speakerRoleSet() {
        return ROLE_SPEAKER.equals(prop(PROP_ROLE));
    }

    /** Switches to the A2DP sink role (phone -> projector). {@code done} on p6-btrole. */
    public static void toSpeaker(Context ctx, Done done) {
        final Context app = ctx.getApplicationContext();
        handler().post(() -> deliver(done, switchTo(app, ROLE_SPEAKER, true)));
    }

    /** Back to the image role (A2DP source: headphones). {@code done} (may be null) on p6-btrole. */
    public static void toNormal(Context ctx, String why, Done done) {
        final Context app = ctx.getApplicationContext();
        handler().post(() -> {
            Log.i(TAG, "normal role (" + why + ")");
            deliver(done, switchTo(app, ROLE_NORMAL, false));
        });
    }

    /**
     * App start (Play6App, after the start gate): a speaker role left by a dead process (the sys.* property lives
     * until reboot) goes back to normal unless the speaker mode is on.
     */
    public static void recoverIfNeeded(Context ctx) {
        if (!speakerRoleSet() || SpeakerMode.isActive()) return;
        Log.w(TAG, "speaker role left set without a speaker mode: back to normal");
        toNormal(ctx, "recovery", null);
    }

    private static void deliver(Done d, boolean ok) {
        if (d == null) return;
        try { d.done(ok); } catch (Throwable t) { Log.w(TAG, "done: " + t); }
    }

    /** p6-btrole. */
    private static boolean switchTo(Context app, String role, boolean sinkWanted) {
        long t0 = SystemClock.uptimeMillis();
        if (coexist() && !speakerRoleSet()) {
            Log.i(TAG, role + ": both A2DP roles run already (coexist image): no switch");
            return !sinkWanted || waitProfile(app, BluetoothProfile.A2DP_SINK);
        }
        if (!"1".equals(prop(PROP_ROLES))) {
            Log.w(TAG, role + ": the image has no role triggers (" + PROP_ROLES + " not 1)");
            return false;
        }
        boolean already = sinkWanted == "true".equals(prop(PROP_SINK)) && sinkWanted != "true".equals(prop(PROP_SOURCE));
        if (!already || !role.equals(prop(PROP_ROLE))) {
            try {
                SystemProperties.set(PROP_ROLE, role);
            } catch (Throwable t) {
                Log.e(TAG, role + ": " + PROP_ROLE + " not written: " + t);
                return false;
            }
            if (!waitProps(sinkWanted)) {
                Log.e(TAG, role + ": init did not apply the role within " + PROP_WAIT_MS + " ms (sink "
                        + prop(PROP_SINK) + ", source " + prop(PROP_SOURCE) + ")");
                return false;
            }
        }
        Log.i(TAG, role + ": props sink " + prop(PROP_SINK) + ", source " + prop(PROP_SOURCE) + ", controller "
                + prop("bluetooth.profile.avrcp.controller.enabled"));
        BluetoothAdapter a = adapter(app);
        if (a == null) {
            Log.e(TAG, role + ": no Bluetooth adapter");
            return false;
        }
        boolean ok = restart(a, role);
        if (ok && sinkWanted) ok = waitProfile(app, BluetoothProfile.A2DP_SINK);
        Log.i(TAG, role + ": " + (ok ? "done" : "FAILED") + " in " + (SystemClock.uptimeMillis() - t0) + " ms");
        return ok;
    }

    private static boolean waitProps(boolean sinkWanted) {
        long end = SystemClock.uptimeMillis() + PROP_WAIT_MS;
        while (SystemClock.uptimeMillis() < end) {
            if (sinkWanted == "true".equals(prop(PROP_SINK)) && sinkWanted != "true".equals(prop(PROP_SOURCE))) return true;
            SystemClock.sleep(POLL_MS);
        }
        return false;
    }

    static BluetoothAdapter adapter(Context app) {
        try {
            BluetoothManager m = app.getSystemService(BluetoothManager.class);
            return m == null ? null : m.getAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Bluetooth off (wait OFF), then on (wait ON). A switched-off adapter is turned on (the remote needs it). */
    private static boolean restart(BluetoothAdapter a, String role) {
        try {
            int s = a.getState();
            if (s != BluetoothAdapter.STATE_OFF) {
                Log.i(TAG, role + ": Bluetooth off (state " + s + ") -> " + a.disable());
                if (!waitState(a, BluetoothAdapter.STATE_OFF, OFF_WAIT_MS)) {
                    Log.e(TAG, role + ": Bluetooth did not reach OFF (state " + a.getState() + ")");
                    // turn it on again in any case: the remote needs Bluetooth
                }
            }
            Log.i(TAG, role + ": Bluetooth on -> " + a.enable());
            if (!waitState(a, BluetoothAdapter.STATE_ON, ON_WAIT_MS)) {
                Log.e(TAG, role + ": Bluetooth did not reach ON (state " + a.getState() + ")");
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, role + ": restart: " + t);
            try { a.enable(); } catch (Throwable ignored) { }
            return false;
        }
    }

    private static boolean waitState(BluetoothAdapter a, int want, long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            if (a.getState() == want) return true;
            SystemClock.sleep(POLL_MS);
        }
        return a.getState() == want;
    }

    /** The profile answers getProfileProxy within PROFILE_WAIT_MS (the A2DP sink service runs). */
    private static boolean waitProfile(Context app, int profile) {
        BluetoothAdapter a = adapter(app);
        if (a == null) return false;
        final Object lock = new Object();
        final boolean[] got = {false};
        boolean asked;
        try {
            asked = a.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int p, BluetoothProfile proxy) {
                    synchronized (lock) { got[0] = true; lock.notifyAll(); }
                    try { a.closeProfileProxy(p, proxy); } catch (Throwable ignored) { }
                }
                @Override public void onServiceDisconnected(int p) { }
            }, profile);
        } catch (Throwable t) {
            Log.w(TAG, "getProfileProxy(" + profile + "): " + t);
            return false;
        }
        if (!asked) {
            Log.w(TAG, "getProfileProxy(" + profile + ") refused: profile not supported by this Bluetooth process");
            return false;
        }
        long end = SystemClock.uptimeMillis() + PROFILE_WAIT_MS;
        synchronized (lock) {
            while (!got[0]) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) break;
                try { lock.wait(left); } catch (InterruptedException e) { break; }
            }
        }
        Log.i(TAG, "profile " + profile + (got[0] ? " connected" : " did not connect within " + PROFILE_WAIT_MS + " ms"));
        return got[0];
    }
}
