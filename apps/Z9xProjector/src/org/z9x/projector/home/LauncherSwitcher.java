package org.z9x.projector.home;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.Notify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lumen OS 1.0: the ONE launcher switch (PLAN C1/C2/C3, home spec 8.1). Lives here because this app is
 * persistent, platform-signed and always enabled, so switching back works from either launcher.
 *
 * <b>Entry points</b> (C1): the SetupBridge call {@code set_launcher} (synchronous, {ok, holder}),
 * the {@code SET_LAUNCHER} broadcast (Lumen Home Customize; perm SET_LAUNCHER), the quick panel's
 * "Home screen" row and Projector settings; plus {@link #ensure} at boot.
 *
 * <b>Sets</b>:
 * <ul>
 * <li>classic (package level): com.google.android.tvlauncher (+ tvrecommendations, org.lineageos.tvcustomizer
 *     when installed);</li>
 * <li>Lumen Home (component level, NEVER the package: its search must stay for the mic key): every
 *     activity, activity-alias, receiver and service of org.z9x.home except the {@code .search.}
 *     subpackage and components marked {@code <meta-data android:name="org.z9x.keep_in_classic"
 *     android:value="true"/>}. Providers are never touched.</li>
 * </ul>
 * Backdrop is not launcher-linked (0 RAM unless it is the screensaver).
 *
 * <b>Apply</b> (worker "z9x-launcher"): write Secure {@value #SETTING}; enable the target set (DEFAULT);
 * RoleManager.addRoleHolderAsUser(ROLE_HOME, target); only after the role callback said yes, disable the
 * other set (DISABLED, which also kills it), so HOME never resolves to nothing; optionally start HOME
 * (user-initiated switches outside the first-run setup). A refused role keeps the old state (the setting
 * is put back) and shows a card.
 *
 * <b>ensure()</b> at boot (C2): a no-op while user_setup_complete is 0 or org.z9x.setup/.SetupActivity is
 * still enabled (it must never take HOME from the first-run wizard); a missing setting means "undecided"
 * and nothing happens; otherwise the role holder and the enabled states are repaired silently (a Play
 * update re-enabling tvlauncher, an image update), never starting HOME.
 *
 * C3 HOME priorities: Z9xSetup 10 (priv-app), Lumen Home 3, tvlauncher 2, so the fallback holder is
 * deterministic. Log tag Z9xLauncher: {@code apply mode=... role=ok disabled=[...] ms=...}.
 */
public final class LauncherSwitcher {
    private static final String TAG = "Z9xLauncher";

    public static final String MODE_LUMEN = "z9x";
    public static final String MODE_CLASSIC = "classic";
    /** Settings.Secure key of the user's choice (written only by an explicit apply: setup, Customize, panel). */
    public static final String SETTING = "z9x_launcher";

    public static final String HOME_PKG = "org.z9x.home";
    public static final String CLASSIC_HOME = "com.google.android.tvlauncher";
    private static final String[] CLASSIC_PKGS = {
            CLASSIC_HOME, "com.google.android.tvrecommendations", "org.lineageos.tvcustomizer"};
    private static final ComponentName SETUP_ACTIVITY =
            new ComponentName("org.z9x.setup", "org.z9x.setup.SetupActivity");
    private static final String KEEP_META = "org.z9x.keep_in_classic";
    private static final long ROLE_TIMEOUT_MS = 5_000;
    private static final int MATCH_ALL = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;

    /** Result of an apply: ok, the ROLE_HOME holder afterwards (may be null), err when !ok. */
    public interface Result { void done(boolean ok, String holder, String err); }

    private static SafeHandler sWorker;
    private static final AtomicBoolean sBusy = new AtomicBoolean();

    private LauncherSwitcher() {}

    private static synchronized SafeHandler worker() {
        if (sWorker == null) sWorker = SafeHandler.newThread("z9x-launcher");
        return sWorker;
    }

    // ================================================================== queries

    static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, MATCH_ALL);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The classic launcher can be chosen (tvlauncher is in the image). */
    public static boolean classicAvailable(Context ctx) {
        return installed(ctx, CLASSIC_HOME);
    }

    /** Lumen Home can be chosen. */
    public static boolean lumenAvailable(Context ctx) {
        return installed(ctx, HOME_PKG);
    }

    /** Both launchers are installed (only then is there anything to choose). */
    public static boolean choiceAvailable(Context ctx) {
        return classicAvailable(ctx) && lumenAvailable(ctx);
    }

    /** The stored choice, or null = undecided. */
    public static String storedMode(Context ctx) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), SETTING);
            return MODE_LUMEN.equals(v) || MODE_CLASSIC.equals(v) ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The mode shown in the UI: from the HOME role holder, else the stored choice, else Lumen. */
    public static String currentMode(Context ctx) {
        String h = homeHolder(ctx);
        if (HOME_PKG.equals(h)) return MODE_LUMEN;
        if (CLASSIC_HOME.equals(h)) return MODE_CLASSIC;
        String m = storedMode(ctx);
        return m != null ? m : MODE_LUMEN;
    }

    static String homeHolder(Context ctx) {
        try {
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            List<String> l = rm == null ? null : rm.getRoleHolders(RoleManager.ROLE_HOME);
            return l == null || l.isEmpty() ? null : l.get(0);
        } catch (Throwable t) {
            Log.w(TAG, "role holders: " + t);
            return null;
        }
    }

    private static boolean setupComplete(Context ctx) {
        try {
            return Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Effective enabled state of a component (DEFAULT -> manifest value, app disabled -> false). */
    static boolean componentEnabled(Context ctx, ComponentName cn) {
        PackageManager pm = ctx.getPackageManager();
        try {
            int st = pm.getComponentEnabledSetting(cn);
            if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (st != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return false;
            ActivityInfo ai = pm.getActivityInfo(cn, MATCH_ALL);
            return ai.enabled && ai.applicationInfo != null && ai.applicationInfo.enabled;
        } catch (Throwable t) {
            return false;                                   // not installed
        }
    }

    private static boolean packageEnabled(Context ctx, String pkg) {
        try {
            int st = ctx.getPackageManager().getApplicationEnabledSetting(pkg);
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, MATCH_ALL);
                return ai.enabled;
            }
            return st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== apply

    /**
     * Switches the launcher on the worker thread; {@code cb} (may be null) runs on the main thread.
     * startHome: bring the new home to the front (user switches; never at boot or from the setup, which
     * starts HOME itself after Finish).
     */
    public static void apply(Context ctx, String mode, boolean startHome, String why, Result cb) {
        final Context app = ctx.getApplicationContext();
        worker().post(() -> {
            Bundle r = applyNow(app, mode, startHome, why);
            if (cb != null) {
                final boolean ok = r.getBoolean("ok");
                final String holder = r.getString("holder");
                final String err = r.getString("err");
                Ui.main().post(() -> {
                    try { cb.done(ok, holder, err); } catch (Throwable t) { Log.w(TAG, "callback: " + t); }
                });
            }
        });
    }

    /**
     * Synchronous switch for the SetupBridge (binder thread, never the main thread): runs on the worker
     * and waits at most {@code waitMs}. On a timeout the switch goes on in the background and the reply is
     * ok=true, pending=true.
     */
    public static Bundle applySync(Context ctx, String mode, String why, long waitMs) {
        final Context app = ctx.getApplicationContext();
        final AtomicReference<Bundle> out = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = worker().post(() -> {
            out.set(applyNow(app, mode, false, why));
            done.countDown();
        });
        if (!posted) return reply(false, null, "not_ready");
        try {
            if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
                Bundle b = reply(true, null, null);
                b.putBoolean("pending", true);
                return b;
            }
        } catch (InterruptedException e) {
            return reply(false, null, "busy");
        }
        Bundle b = out.get();
        return b != null ? b : reply(false, null, "not_ready");
    }

    private static Bundle reply(boolean ok, String holder, String err) {
        Bundle b = new Bundle();
        b.putBoolean("ok", ok);
        if (holder != null) b.putString("holder", holder);
        if (!ok && err != null) b.putString("err", err);
        return b;
    }

    /** Worker thread only. */
    private static Bundle applyNow(Context app, String mode, boolean startHome, String why) {
        long t0 = SystemClock.uptimeMillis();
        if (!MODE_LUMEN.equals(mode) && !MODE_CLASSIC.equals(mode)) return reply(false, null, "unsupported");
        boolean lumen = MODE_LUMEN.equals(mode);
        String target = lumen ? HOME_PKG : CLASSIC_HOME;
        if (!installed(app, target)) {
            Log.w(TAG, "apply mode=" + mode + " (" + why + "): " + target + " is not installed");
            return reply(false, homeHolder(app), "unsupported");
        }
        if (!sBusy.compareAndSet(false, true)) return reply(false, homeHolder(app), "busy");
        try {
            String before = storedMode(app);
            putSetting(app, mode);
            // 1. the target set first, so HOME never resolves to nothing
            List<String> enabled = lumen ? setLumenComponents(app, true) : setClassicPackages(app, true);
            // 2. the role
            boolean role = addHomeRole(app, target);
            if (!role) {
                Log.w(TAG, "apply mode=" + mode + " (" + why + "): role=refused, old state kept");
                putSetting(app, before);
                Ui.main().post(() -> Notify.show(app, app.getString(R.string.launcher_switch_failed)));
                return reply(false, homeHolder(app), "denied");
            }
            // 3. only now the other set goes (DISABLED also kills its process)
            List<String> disabled = lumen ? setClassicPackages(app, false) : setLumenComponents(app, false);
            String holder = homeHolder(app);
            Log.i(TAG, "apply mode=" + mode + " (" + why + ") role=ok holder=" + holder + " enabled=" + enabled
                    + " disabled=" + disabled + " ms=" + (SystemClock.uptimeMillis() - t0));
            if (startHome) startHome(app);
            return reply(true, holder, null);
        } catch (Throwable t) {
            Log.e(TAG, "apply mode=" + mode + " (" + why + ") failed", t);
            return reply(false, homeHolder(app), "not_ready");
        } finally {
            sBusy.set(false);
        }
    }

    private static void putSetting(Context app, String mode) {
        try {
            Settings.Secure.putString(app.getContentResolver(), SETTING, mode);
        } catch (Throwable t) {
            Log.w(TAG, "setting: " + t);
        }
    }

    /** RoleManager (MANAGE_ROLE_HOLDERS, platform signature); waits for its callback. Worker thread. */
    private static boolean addHomeRole(Context app, String pkg) {
        if (pkg.equals(homeHolder(app))) return true;
        RoleManager rm = app.getSystemService(RoleManager.class);
        if (rm == null) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean();
        try {
            rm.addRoleHolderAsUser(RoleManager.ROLE_HOME, pkg, 0, Process.myUserHandle(), Runnable::run, r -> {
                ok.set(Boolean.TRUE.equals(r));
                latch.countDown();
            });
            if (!latch.await(ROLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "ROLE_HOME " + pkg + ": no answer in " + ROLE_TIMEOUT_MS + " ms");
                return pkg.equals(homeHolder(app));
            }
        } catch (Throwable t) {
            Log.w(TAG, "ROLE_HOME " + pkg + ": " + t);
            return false;
        }
        return ok.get();
    }

    /** Classic packages that are installed: enable (DEFAULT) or disable (DISABLED). Returns those changed. */
    private static List<String> setClassicPackages(Context app, boolean on) {
        List<String> changed = new ArrayList<>();
        PackageManager pm = app.getPackageManager();
        for (String p : CLASSIC_PKGS) {
            if (!installed(app, p)) continue;
            try {
                int st = pm.getApplicationEnabledSetting(p);
                if (on) {
                    if (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            || st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) continue;
                    pm.setApplicationEnabledSetting(p, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
                } else {
                    if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) continue;
                    pm.setApplicationEnabledSetting(p, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
                }
                changed.add(p);
            } catch (Throwable t) {
                Log.w(TAG, (on ? "enable " : "disable ") + p + ": " + t);
            }
        }
        return changed;
    }

    /** Lumen Home's launcher components (class comment): enable (DEFAULT) or disable. Returns those changed. */
    private static List<String> setLumenComponents(Context app, boolean on) {
        List<String> changed = new ArrayList<>();
        PackageManager pm = app.getPackageManager();
        if (on && !packageEnabled(app, HOME_PKG)) {
            // a whole-package disable (an older switch, a user) would also hide search: undo it
            try {
                pm.setApplicationEnabledSetting(HOME_PKG, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
                changed.add(HOME_PKG);
            } catch (Throwable t) {
                Log.w(TAG, "enable " + HOME_PKG + ": " + t);
            }
        }
        for (ComponentName cn : lumenLauncherComponents(app)) {
            try {
                int st = pm.getComponentEnabledSetting(cn);
                if (on) {
                    if (st != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) continue;
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            PackageManager.DONT_KILL_APP);
                } else {
                    if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) continue;
                    // no DONT_KILL_APP: Lumen Home's process goes (search starts it again when used)
                    pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
                }
                changed.add(cn.getShortClassName());
            } catch (Throwable t) {
                Log.w(TAG, (on ? "enable " : "disable ") + cn.flattenToShortString() + ": " + t);
            }
        }
        return changed;
    }

    /** Every launcher-linked component of org.z9x.home (class comment), read from its manifest. */
    static List<ComponentName> lumenLauncherComponents(Context app) {
        List<ComponentName> out = new ArrayList<>();
        PackageInfo pi;
        try {
            pi = app.getPackageManager().getPackageInfo(HOME_PKG, MATCH_ALL | PackageManager.GET_ACTIVITIES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            return out;
        }
        addAll(out, pi.activities);
        addAll(out, pi.receivers);
        if (pi.services != null) {
            for (ServiceInfo s : pi.services) add(out, s);
        }
        return out;
    }

    private static void addAll(List<ComponentName> out, ActivityInfo[] list) {
        if (list == null) return;
        for (ActivityInfo a : list) add(out, a);
    }

    private static void add(List<ComponentName> out, ComponentInfo ci) {
        if (ci == null || ci.name == null) return;
        if (ci.name.startsWith(HOME_PKG + ".search.")) return;      // the mic key's search stays
        if (ci.metaData != null && ci.metaData.getBoolean(KEEP_META, false)) return;
        out.add(new ComponentName(HOME_PKG, ci.name));
    }

    private static void startHome(Context app) {
        Ui.main().post(() -> {
            try {
                app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (Throwable t) {
                Log.w(TAG, "start HOME: " + t);
            }
        });
    }

    // ================================================================== boot (C2)

    /** BOOT_COMPLETED: repairs the chosen launcher silently (class comment). Any thread. */
    public static void ensure(Context ctx) {
        final Context app = ctx.getApplicationContext();
        worker().post(() -> {
            try {
                ensureNow(app);
            } catch (Throwable t) {
                Log.w(TAG, "ensure: " + t);
            }
        });
    }

    private static void ensureNow(Context app) {
        if (!setupComplete(app)) {
            Log.i(TAG, "ensure: setup not complete, the launcher is the first-run setup's choice");
            return;
        }
        if (componentEnabled(app, SETUP_ACTIVITY)) {
            Log.i(TAG, "ensure: the first-run setup is still enabled, launcher untouched");
            return;
        }
        String mode = storedMode(app);
        if (mode == null) {
            Log.i(TAG, "ensure: launcher undecided (no " + SETTING + "), untouched");
            return;
        }
        if (MODE_CLASSIC.equals(mode) && !classicAvailable(app) && lumenAvailable(app)) mode = MODE_LUMEN;
        if (MODE_LUMEN.equals(mode) && !lumenAvailable(app) && classicAvailable(app)) mode = MODE_CLASSIC;
        String why = consistent(app, mode);
        if (why == null) {
            Log.i(TAG, "ensure: mode=" + mode + " consistent");
            return;
        }
        Log.i(TAG, "ensure: mode=" + mode + " needs repair (" + why + ")");
        applyNow(app, mode, false, "boot repair");
    }

    /** Null when the role holder and the enabled sets match {@code mode}, else what differs. */
    private static String consistent(Context app, String mode) {
        boolean lumen = MODE_LUMEN.equals(mode);
        String holder = homeHolder(app);
        String target = lumen ? HOME_PKG : CLASSIC_HOME;
        if (!target.equals(holder)) return "holder " + holder;
        PackageManager pm = app.getPackageManager();
        for (ComponentName cn : lumenLauncherComponents(app)) {
            try {
                boolean off = pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                if (lumen == off) return cn.getShortClassName() + (off ? " disabled" : " enabled");
            } catch (Throwable ignored) { }
        }
        for (String p : CLASSIC_PKGS) {
            if (!installed(app, p)) continue;
            boolean on = packageEnabled(app, p);
            if (lumen == on) return p + (on ? " enabled" : " disabled");
        }
        return null;
    }
}
