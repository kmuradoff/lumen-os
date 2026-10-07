package org.z9x.projector.mem;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.SafeHandler;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Smart unloading (speed spec 7): RAM is freed only where the user's intent is clear; otherwise the
 * kernel (zram) and the tuned lmkd do the work. No periodic killer.
 *
 * <ul>
 * <li>P1 Close (a Recents card): removeTask for every task of the app, then forceStopPackage unless it is
 *     protected ({@link ProtectedSet}: then only the card goes).</li>
 * <li>P2 Close all: P1 for every card, then killBackgroundProcesses for every non-protected third-party
 *     package that is still cached.</li>
 * <li>P3 Boot clean (BOOT_COMPLETED / USER_UNLOCKED, once per boot, only in the first minutes): every
 *     recent task that is not running (restored from /data/system_ce/0/recent_tasks) is removed, so
 *     Recents is empty after a reboot; tasks opened in this boot are running and stay.</li>
 * <li>P5 First-boot disable: the APEX apps of speed/proposed/first_boot_disable.txt are disabled while
 *     their state is DEFAULT (a state the user chose is respected).</li>
 * <li>ensureAssistant: the ASSISTANT role (long-press HOME = Recents, invocation_type 5) is given to
 *     org.z9x.projector only when it is empty or its holder is gone; a user's choice is never overridden.</li>
 * </ul>
 * P4 (standby trim) is gone in Lumen OS 1.0: the projector suspends (STR) instead of idling for minutes.
 * Everything runs on the worker "z9x-mem". Logs: tag Z9xMem / Z9xRecents.
 */
public final class MemoryGuard {
    private static final String TAG = "Z9xMem";
    private static final String TAG_RECENTS = "Z9xRecents";
    private static final String[] FIRST_BOOT_DISABLE = {
            "com.android.healthconnect.controller",
            "com.android.health.connect.backuprestore",
            "com.android.ondevicepersonalization.services",
            "com.android.federatedcompute.services",
            "com.android.devicelockcontroller",
            "com.android.adservices.api",
    };
    /** Boot clean only this soon after boot (a later process restart must not clear tasks lmkd killed). */
    private static final long BOOT_CLEAN_MAX_UPTIME_MS = 5 * 60_000L;
    private static final long BOOT_CLEAN_DELAY_MS = 2_000;
    private static final String PREFS = "z9x_mem";
    private static final String K_CLEANED_BOOT = "boot_clean_boot_id";

    private static SafeHandler sWorker;
    private static boolean sBootDone;

    private MemoryGuard() {}

    private static synchronized SafeHandler worker() {
        if (sWorker == null) sWorker = SafeHandler.newThread("z9x-mem");
        return sWorker;
    }

    // ================================================================== P1 / P2

    /** P1. Any thread; the work runs on z9x-mem. */
    public static void close(Context ctx, String pkg, List<Integer> taskIds) {
        final Context app = ctx.getApplicationContext();
        final List<Integer> ids = new ArrayList<>(taskIds);
        worker().post(() -> {
            long before = memAvailableKb();
            Set<String> prot = ProtectedSet.compute(app);
            String forced = closeOne(app, pkg, ids, prot);
            logFreed("close pkg=" + pkg + " tasks=" + ids + " forceStop=" + forced, before);
        });
    }

    /** P2. Any thread; {@code done} runs on z9x-mem when finished (may be null). */
    public static void closeAll(Context ctx, List<String> pkgs, List<List<Integer>> taskIds, Runnable done) {
        final Context app = ctx.getApplicationContext();
        final List<String> p = new ArrayList<>(pkgs);
        final List<List<Integer>> t = new ArrayList<>(taskIds);
        worker().post(() -> {
            long before = memAvailableKb();
            Set<String> prot = ProtectedSet.compute(app);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < p.size(); i++) {
                String f = closeOne(app, p.get(i), i < t.size() ? t.get(i) : new ArrayList<>(), prot);
                sb.append(p.get(i)).append('=').append(f).append(' ');
            }
            List<String> killed = killCachedThirdParty(app, prot);
            logFreed("close all: " + sb.toString().trim() + " cached killed=" + killed, before);
            if (done != null) done.run();
        });
    }

    /** z9x-mem. Returns "yes" or "no(reason)". */
    private static String closeOne(Context app, String pkg, List<Integer> taskIds, Set<String> prot) {
        for (Integer id : taskIds) {
            try {
                ActivityTaskManager.getInstance().removeTask(id);       // REMOVE_TASKS
            } catch (Throwable t) {
                Log.w(TAG, "removeTask " + id + ": " + t);
            }
        }
        if (ProtectedSet.isProtected(app, prot, pkg)) return "no(protected)";
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            am.forceStopPackage(pkg);                                 // FORCE_STOP_PACKAGES
            return "yes";
        } catch (Throwable t) {
            return "no(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static List<String> killCachedThirdParty(Context app, Set<String> prot) {
        List<String> out = new ArrayList<>();
        try {
            ActivityManager am = app.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return out;
            PackageManager pm = app.getPackageManager();
            for (ActivityManager.RunningAppProcessInfo pi : procs) {
                if (pi.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED || pi.pkgList == null) continue;
                for (String pkg : pi.pkgList) {
                    if (out.contains(pkg) || ProtectedSet.isProtected(app, prot, pkg)) continue;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                    } catch (Throwable t) {
                        continue;
                    }
                    am.killBackgroundProcesses(pkg);                    // KILL_BACKGROUND_PROCESSES
                    out.add(pkg);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "kill cached: " + t);
        }
        return out;
    }

    private static void logFreed(String what, long beforeKb) {
        worker().postDelayed(() -> {
            long after = memAvailableKb();
            Log.i(TAG, what + (beforeKb > 0 && after > 0 ? " freedKB~" + (after - beforeKb) : ""));
        }, 1_000);
    }

    /** /proc/meminfo MemAvailable in kB, -1 if unreadable. */
    private static long memAvailableKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemAvailable:")) return Long.parseLong(l.replaceAll("[^0-9]", ""));
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    // ================================================================== boot: P3, P5, ensureAssistant

    /** BOOT_COMPLETED / USER_UNLOCKED (BootReceiver). Any thread. */
    public static void onBoot(Context ctx, String why) {
        final Context app = ctx.getApplicationContext();
        worker().postDelayed(() -> {
            if (!sBootDone) {
                sBootDone = true;
                firstBootDisable(app);
                ensureAssistant(app);
            }
            bootClean(app, why);
        }, BOOT_CLEAN_DELAY_MS);
    }

    /** P3 (z9x-mem). */
    private static void bootClean(Context app, String why) {
        if (SystemClock.elapsedRealtime() > BOOT_CLEAN_MAX_UPTIME_MS) return;
        String boot = Hal.bootId();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (boot != null && boot.equals(p.getString(K_CLEANED_BOOT, ""))) return;
        int removed = 0, kept = 0;
        try {
            List<ActivityManager.RecentTaskInfo> tasks = ActivityTaskManager.getInstance()
                    .getRecentTasks(200, 0, UserHandle.myUserId());
            if (tasks != null) {
                for (ActivityManager.RecentTaskInfo ti : tasks) {
                    if (ti.isRunning) {
                        kept++;
                        continue;
                    }
                    try {
                        if (ActivityTaskManager.getInstance().removeTask(ti.taskId)) removed++;
                    } catch (Throwable t) {
                        Log.w(TAG_RECENTS, "boot-clean removeTask " + ti.taskId + ": " + t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG_RECENTS, "boot-clean: " + t);
            return;
        }
        if (boot != null) p.edit().putString(K_CLEANED_BOOT, boot).apply();
        Log.i(TAG_RECENTS, "boot-clean (" + why + ") removed=" + removed + " kept=" + kept);
    }

    /** P5 (z9x-mem): idempotent, only while the state is DEFAULT. */
    private static void firstBootDisable(Context app) {
        PackageManager pm = app.getPackageManager();
        for (String pkg : FIRST_BOOT_DISABLE) {
            String r;
            try {
                int st = pm.getApplicationEnabledSetting(pkg);
                if (st != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    r = "skip (state " + st + ")";
                } else {
                    pm.setApplicationEnabledSetting(pkg, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
                    r = "ok";
                }
            } catch (IllegalArgumentException e) {
                r = "skip (not installed)";
            } catch (Throwable t) {
                r = "fail " + t;
            }
            Log.i(TAG, "disable " + pkg + " -> " + r);
        }
    }

    /** z9x-mem: ASSISTANT role -> org.z9x.projector only when empty or its holder is gone. */
    private static void ensureAssistant(Context app) {
        try {
            RoleManager rm = app.getSystemService(RoleManager.class);
            if (rm == null) return;
            List<String> h = rm.getRoleHolders(RoleManager.ROLE_ASSISTANT);
            if (h != null && !h.isEmpty()) {
                String cur = h.get(0);
                boolean gone = false;
                try {
                    app.getPackageManager().getApplicationInfo(cur, 0);
                } catch (PackageManager.NameNotFoundException e) {
                    gone = true;
                }
                if (!gone) {
                    Log.i(TAG_RECENTS, "assistant role: " + cur + " (kept)");
                    return;
                }
            }
            rm.addRoleHolderAsUser(RoleManager.ROLE_ASSISTANT, app.getPackageName(), 0, Process.myUserHandle(),
                    Runnable::run, ok -> Log.i(TAG_RECENTS, "assistant role -> " + app.getPackageName() + ": " + ok));
        } catch (Throwable t) {
            Log.w(TAG_RECENTS, "assistant role: " + t);
        }
    }
}
