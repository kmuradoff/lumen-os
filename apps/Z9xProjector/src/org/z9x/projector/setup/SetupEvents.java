package org.z9x.projector.setup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;

/**
 * Lumen OS 1.0 SetupBridge events (setup/bridge_api.md): {@value #ACTION} sent with
 * {@code sendBroadcast(intent, SETUP_BRIDGE)} to org.z9x.setup and org.z9x.home only (explicit
 * packages, registered-only receivers: a client that is not running costs nothing). Extras: {@code what}
 * = remote | hal | af | kst plus the fields of the matching query. Lossy by design (the vendor event
 * queue drops events); the clients poll while visible. Sent from a small worker, never the HAL thread.
 */
public final class SetupEvents {
    private static final String TAG = "Z9xBridge";
    public static final String ACTION = "org.z9x.projector.action.SETUP_STATE";
    private static final String[] TARGETS = {"org.z9x.setup", "org.z9x.home"};
    /** AF start (4, 8, 333-335) and end (2, 5) focus events (HalController.onFocusEvent). */
    private static final int[] AF_EVENTS = {2, 4, 5, 8, 333, 334, 335};

    private static Context sApp;
    private static SafeHandler sWorker;

    private SetupEvents() {}

    /** App.onCreate: HAL (re)connect and AF events become "hal" / "af" events. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = SafeHandler.newThread("z9x-bridge-ev");
        Hal.addConnectedListener(() -> hal(sApp));
        Hal.addFocusEventListener((type, value) -> {
            for (int t : AF_EVENTS) {
                if (t == type) {
                    // after HalController updated its AF-busy window (it handles the event right after us)
                    if (sWorker != null) sWorker.postDelayed(() -> send("af", SetupBridgeProvider.halBundle(sApp, false)), 150);
                    return;
                }
            }
        });
    }

    public static void remote(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        post(() -> send("remote", SetupBridgeProvider.remoteBundle(app)));
    }

    public static void hal(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        post(() -> send("hal", SetupBridgeProvider.halBundle(app, false)));
    }

    public static void kst(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        post(() -> send("kst", SetupBridgeProvider.halBundle(app, false)));
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
                app.sendBroadcast(i, SetupBridgeProvider.PERMISSION);
            } catch (Throwable t) {
                Log.w(TAG, "event " + what + " -> " + pkg + ": " + t);
            }
        }
    }
}
