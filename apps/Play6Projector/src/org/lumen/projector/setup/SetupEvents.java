package org.lumen.projector.setup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.SafeHandler;
import org.lumen.projector.remote.RemoteAutoPair;

/**
 * The XGIMI Play 6 class behind the setup seam of the shared RemoteAutoPair (docs/play6/SETTINGS_PLAN.md
 * 2.4) and the events of its SetupBridge (setup/SetupBridgeProvider, docs/play6/impl/setup-design.md
 * section 3): {@value #ACTION} with the same action, extras, targets and permission as the Z9X SetupEvents,
 * sent with {@code sendBroadcast(intent, SETUP_BRIDGE)} to org.lumen.setup and org.lumen.home only (explicit
 * packages, registered-only receivers: a client that is not running costs nothing). Extras: {@code what} =
 * remote | hal | af plus the fields of the matching query (no "kst": the 2 Hz hal_state poll covers it). Lossy by design; the clients poll while
 * visible. The sender must hold SETUP_BRIDGE itself (the Setup registers its receiver with it), so the
 * manifest requests it.
 *
 * {@link #install} (Play6App at the start gate, before the Hal facade connects): an IGmpf (re)connect sends
 * "hal", and the AF start (4, 8, 333-335) and end (2, 5) focus events send "af" 150 ms later, after the Hal
 * facade updated its AF busy window. Everything is sent from a small worker, never from p6-hal or the main
 * thread; before install a "remote" event is sent on the caller's thread (RemoteAutoPair's own).
 */
public final class SetupEvents {
    private static final String TAG = "P6Bridge";
    public static final String ACTION = "org.lumen.projector.action.SETUP_STATE";
    /** Signature permission of the bridge (defined by this app). */
    public static final String PERMISSION = SetupBridgeProvider.PERMISSION;
    private static final String[] TARGETS = {"org.lumen.setup", "org.lumen.home"};
    /** AF start (4, 8, 333-335) and end (2, 5) focus events (Hal.onFocusEvent). */
    private static final int[] AF_EVENTS = {2, 4, 5, 8, 333, 334, 335};
    private static final long AF_EVENT_DELAY_MS = 150;

    private static volatile Context sApp;
    private static volatile SafeHandler sWorker;

    private SetupEvents() {}

    /** Play6App at the start gate (p6-hal), once: HAL (re)connect and AF events become "hal" / "af" events. */
    public static synchronized void install(Context ctx) {
        if (sWorker != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = SafeHandler.newThread("p6-bridge-ev");
        Hal.addConnectedListener(() -> hal(sApp));
        Hal.addFocusEventListener((type, value) -> {
            for (int t : AF_EVENTS) {
                if (t == type) {
                    // the Hal facade updates its AF busy window right after its listeners
                    SafeHandler w = sWorker;
                    if (w != null) w.postDelayed(() -> send("af", SetupBridgeProvider.halBundle()), AF_EVENT_DELAY_MS);
                    return;
                }
            }
        });
    }

    /** Bond / connection of a remote changed (RemoteAutoPair, its own thread, after its install). */
    public static void remote(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        if (sApp == null) sApp = app;
        // RemoteAutoPair is installed when it calls this, so its state is read directly
        Runnable r = () -> send("remote", SetupBridgeProvider.remoteBundle(RemoteAutoPair.state(app)));
        SafeHandler w = sWorker;
        if (w == null || !w.post(r)) r.run();
    }

    /** The HAL became ready (IGmpf connected after the gate). */
    public static void hal(Context ctx) {
        if (ctx == null) return;
        post(() -> send("hal", SetupBridgeProvider.halBundle()));
    }

    private static void post(Runnable r) {
        SafeHandler w = sWorker;
        if (w != null) w.post(r);
    }

    private static void send(String what, Bundle fields) {
        Context app = sApp;
        if (app == null) return;
        for (String pkg : TARGETS) {
            try {
                Intent i = new Intent(ACTION).setPackage(pkg).putExtra("what", what)
                        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                if (fields != null) i.putExtras(fields);
                app.sendBroadcast(i, PERMISSION);
            } catch (Throwable t) {
                Log.w(TAG, "event " + what + " -> " + pkg + ": " + t);
            }
        }
    }
}
