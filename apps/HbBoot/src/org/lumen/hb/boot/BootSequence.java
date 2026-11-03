package org.lumen.hb.boot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * The stock boot handshake of the Z9X HB, replayed in one thread (stock analysis
 * handshake.md, section 3). On the stock system five different places send it; under a GSI none of
 * them exists, so this class sends the same values in the same phases:
 *
 *   connect ISystemManager + IPowerManager (checkService every 500 ms, at most 120 s)
 *   guard   getInterfaceVersion == 1 and getInterfaceHash == frozen hash, per interface (HbGmpf)
 *   A  setBootAnimState(1)    only while init.svc.bootanim is "running" and the state is not 2 yet
 *                             (stock: BootAnimation constructor in libbootanimation.so)
 *   B  setSystemUIState(1)    (stock: SystemUI FocusUIV2.start -> SystemUtils.systemUIServicesCompleted)
 *   C  goToBootCompleted(0)   (stock: XRMService.onCreate -> initXRMServices)
 *   wait until init.svc.bootanim is no longer "running" (200 ms poll, at most 180 s)
 *   D  setBootAnimState(2)    (stock: end of BootAnimation::threadLoop in libbootanimation.so)
 *   wait until the user is unlocked (500 ms poll, at most 300 s)
 *   E  goToBootCompleted(1)   (stock: XRMService.onUserUnlocked -> BootControlService "acon"
 *                             -> bootCompletedCheck, which first waits for the boot animation to stop)
 *   read back getBootAnimState() for the log.
 * Nothing is periodic: the stock system has no keep-alive towards gmpf (handshake.md section 4).
 *
 * Exactly once per boot: each phase (early = A..C, late = D..E) is stored with the kernel boot_id in
 * device protected storage after it was delivered, so a restart of this persistent process does not
 * replay it. An unreadable boot_id sends anyway (fail open). A call that was not delivered
 * (RemoteException) is retried up to 5 times, 500 ms apart, reconnecting in between; a delivered call
 * with a bad reply (HbGmpf.ReplyException, including the guard) is never repeated.
 * Every step is logged with the uptime, tag HbBoot.
 */
final class BootSequence implements Runnable {
    private static final String TAG = "HbBoot";
    private static final long CONNECT_MAX_MS = 120_000, CONNECT_POLL_MS = 500;
    private static final long BOOTANIM_MAX_MS = 180_000, BOOTANIM_POLL_MS = 200;
    private static final long UNLOCK_MAX_MS = 300_000, UNLOCK_POLL_MS = 500;
    private static final int MAX_TRIES = 5;
    private static final long RETRY_MS = 500;
    private static final String K_EARLY = "boot_id_early", K_LATE = "boot_id_late";

    private static boolean started;

    private final Context app;
    private final HbGmpf gmpf = new HbGmpf();

    private BootSequence(Context app) {
        this.app = app;
    }

    /** Idempotent, any thread. */
    static synchronized void start(Context ctx) {
        if (started) return;
        started = true;
        new Thread(new BootSequence(ctx.getApplicationContext()), "hb-boot").start();
    }

    private interface Step { String run() throws RemoteException; }

    @Override
    public void run() {
        log("start (bootanim=" + bootanim() + ")");
        long t0 = SystemClock.uptimeMillis();
        while (!gmpf.connect()) {
            if (SystemClock.uptimeMillis() - t0 > CONNECT_MAX_MS) {
                log("gmpf services not available after " + CONNECT_MAX_MS / 1000 + " s, giving up");
                return;
            }
            SystemClock.sleep(CONNECT_POLL_MS);
        }
        log("connected: " + HbGmpf.Iface.SYSTEM.instance + ", " + HbGmpf.Iface.POWER.instance);

        String boot = bootId();
        SharedPreferences sp = app.createDeviceProtectedStorageContext()
                .getSharedPreferences("boot", Context.MODE_PRIVATE);

        if (boot != null && boot.equals(sp.getString(K_EARLY, null))) {
            log("early phase already sent for this boot");
        } else {
            if (isBootanimRunning()) {
                int state = read("33 getBootAnimState()");
                if (state != HbGmpf.BOOT_ANIM_STOPPED) {
                    send("32 setBootAnimState(1)", () -> { gmpf.setBootAnimState(HbGmpf.BOOT_ANIM_RUNNING); return "ok"; });
                }
            } else {
                log("A skipped: boot animation not running");
            }
            boolean b = send("36 setSystemUIState(1)", () -> { gmpf.setSystemUiReady(); return "ok"; });
            boolean c = send("2 goToBootCompleted(0)",
                    () -> String.valueOf(gmpf.goToBootCompleted(HbGmpf.STAGE_EARLY)));
            if (b && c && boot != null) sp.edit().putString(K_EARLY, boot).commit();
        }

        long w0 = SystemClock.uptimeMillis();
        while (isBootanimRunning() && SystemClock.uptimeMillis() - w0 < BOOTANIM_MAX_MS) {
            SystemClock.sleep(BOOTANIM_POLL_MS);
        }
        log("boot animation: " + bootanim() + " after " + (SystemClock.uptimeMillis() - w0) + " ms");

        if (boot != null && boot.equals(sp.getString(K_LATE, null))) {
            log("late phase already sent for this boot");
        } else {
            boolean d = send("32 setBootAnimState(2)", () -> { gmpf.setBootAnimState(HbGmpf.BOOT_ANIM_STOPPED); return "ok"; });
            UserManager um = app.getSystemService(UserManager.class);
            long u0 = SystemClock.uptimeMillis();
            while (um != null && !um.isUserUnlocked() && SystemClock.uptimeMillis() - u0 < UNLOCK_MAX_MS) {
                SystemClock.sleep(UNLOCK_POLL_MS);
            }
            log("user unlocked: " + (um == null ? "?" : String.valueOf(um.isUserUnlocked())));
            boolean e = send("2 goToBootCompleted(1)",
                    () -> String.valueOf(gmpf.goToBootCompleted(HbGmpf.STAGE_AFTER_BOOTANIM)));
            if (d && e && boot != null) sp.edit().putString(K_LATE, boot).commit();
        }
        read("33 getBootAnimState()");
        log("handshake done");
    }

    /** true when the call reached the service (also with a bad reply), false after MAX_TRIES failures. */
    private boolean send(String what, Step s) {
        for (int i = 1; i <= MAX_TRIES; i++) {
            try {
                log(what + " -> " + s.run());
                return true;
            } catch (HbGmpf.ReplyException e) {
                log(what + " delivered or refused, not repeated: " + e.getMessage());
                return true;
            } catch (Throwable t) {
                log(what + " failed (" + i + "/" + MAX_TRIES + "): " + t);
                if (!gmpf.isConnected()) gmpf.connect();
                SystemClock.sleep(RETRY_MS);
            }
        }
        return false;
    }

    /** A logged read; -1 when it did not work. */
    private int read(String what) {
        try {
            int v = gmpf.getBootAnimState();
            log(what + " -> " + v);
            return v;
        } catch (Throwable t) {
            log(what + " failed: " + t);
            return -1;
        }
    }

    private static String bootanim() {
        return SystemProperties.get("init.svc.bootanim", "?");
    }

    private static boolean isBootanimRunning() {
        return "running".equals(SystemProperties.get("init.svc.bootanim"));
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
