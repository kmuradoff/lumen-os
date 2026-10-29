package org.lumen.play6;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.lumen.projector.hal.Play6Gmpf;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Boot handshake with XGIMI's gmpf_main on the Play 6, in the order stock sends it (the same order as
 * Z9xProjector HalController on the Z9X, research/v6/RESULT_hal.json):
 *   connect IGmpf (every 500 ms, up to 120 s)
 *   -> 391 setSystemUIState(1) -> 237 goToBootCompleted(0)     once per boot_id (stock SystemUI + XRMService)
 *   -> wait until init.svc.bootanim is no longer "running" (200 ms poll, at most 180 s)
 *   -> 395 setBootAnimState(2) -> 237 goToBootCompleted(1)     once per boot_id (stock boot animation exit)
 * Nothing else: no focus, keystone, lamp or power call. A call that was not delivered (RemoteException)
 * is retried up to 5 times; a delivered call with a bad reply (ReplyException) is never repeated.
 * The boot_id guard is fail-open: an unreadable boot_id sends, and a stage is stored only after it was
 * delivered. Every step is logged with the uptime (tag Play6Boot) for the USB boot log of test builds.
 */
final class BootHandshake implements Runnable {
    private static final String TAG = "Play6Boot";
    private static final long CONNECT_MAX_MS = 120_000;
    private static final long BOOTANIM_MAX_MS = 180_000;
    private static final int MAX_TRIES = 5;
    private static final String K_EARLY = "boot_id_early", K_LATE = "boot_id_late";

    private static boolean started;

    private final Context app;
    private final Play6Gmpf gmpf = new Play6Gmpf();

    private BootHandshake(Context app) {
        this.app = app;
    }

    /** Idempotent, any thread. */
    static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        new Thread(new BootHandshake(ctx.getApplicationContext()), "play6-boot").start();
    }

    private interface Call { String run() throws RemoteException; }

    @Override
    public void run() {
        log("start (bootanim=" + SystemProperties.get("init.svc.bootanim") + ")");
        long t0 = SystemClock.uptimeMillis();
        while (!gmpf.connect()) {
            if (SystemClock.uptimeMillis() - t0 > CONNECT_MAX_MS) {
                log("IGmpf not available after " + CONNECT_MAX_MS / 1000 + " s, giving up");
                return;
            }
            SystemClock.sleep(500);
        }
        log("IGmpf connected");
        String boot = bootId();
        SharedPreferences sp = app.createDeviceProtectedStorageContext()
                .getSharedPreferences("boot", Context.MODE_PRIVATE);

        if (boot != null && boot.equals(sp.getString(K_EARLY, null))) {
            log("early part already sent for this boot");
        } else {
            boolean a = send("391 setSystemUIState(1)", () -> { gmpf.setSystemUiReady(); return "ok"; });
            boolean b = send("237 goToBootCompleted(0)",
                    () -> String.valueOf(gmpf.goToBootCompleted(Play6Gmpf.STAGE_EARLY)));
            if (a && b && boot != null) sp.edit().putString(K_EARLY, boot).commit();
        }

        long w0 = SystemClock.uptimeMillis();
        while ("running".equals(SystemProperties.get("init.svc.bootanim"))
                && SystemClock.uptimeMillis() - w0 < BOOTANIM_MAX_MS) {
            SystemClock.sleep(200);
        }
        log("boot animation: " + SystemProperties.get("init.svc.bootanim", "?"));

        if (boot != null && boot.equals(sp.getString(K_LATE, null))) {
            log("late part already sent for this boot");
        } else {
            boolean a = send("395 setBootAnimState(2)", () -> { gmpf.setBootAnimFinished(); return "ok"; });
            boolean b = send("237 goToBootCompleted(1)",
                    () -> String.valueOf(gmpf.goToBootCompleted(Play6Gmpf.STAGE_AFTER_BOOTANIM)));
            if (a && b && boot != null) sp.edit().putString(K_LATE, boot).commit();
        }
        send("394 getBootAnimState()", () -> String.valueOf(gmpf.getBootAnimState()));
        log("handshake done");
    }

    /** true when the call reached the HAL (also with a bad reply), false after MAX_TRIES failures. */
    private boolean send(String what, Call c) {
        for (int i = 1; i <= MAX_TRIES; i++) {
            try {
                log(what + " -> " + c.run());
                return true;
            } catch (Play6Gmpf.ReplyException e) {
                log(what + " delivered, bad reply (not repeated): " + e.getMessage());
                return true;
            } catch (Throwable t) {
                log(what + " failed (" + i + "/" + MAX_TRIES + "): " + t);
                if (!gmpf.isConnected()) gmpf.connect();
                SystemClock.sleep(500);
            }
        }
        return false;
    }

    private static String bootId() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String s = r.readLine();
            return s == null || s.trim().isEmpty() ? null : s.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, "[" + SystemClock.uptimeMillis() / 1000.0 + " s] " + msg);
    }
}
