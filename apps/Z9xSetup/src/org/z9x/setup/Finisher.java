package org.z9x.setup;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

/**
 * The end state of setup (SPEC 4.9, PLAN C1/C2). Every step is idempotent and logged
 * ("finish step=<n> ok"); state=finishing is written first so a crash or power cut re-runs the finish
 * headless at the next HOME start instead of showing the UI again.
 *
 * Steps 1-6 run on a worker thread ({@link #runBackground}); 7-8 (disable our activity, start HOME,
 * leave) run on the main thread in SetupActivity.
 */
public final class Finisher {
    /** Full finish after the UI (or a headless re-run of an interrupted finish). */
    public static final int FULL = 0;
    /** Upgrade over existing data, flags already 1: launcher step only, then steps 2, 6-8. */
    public static final int LAUNCHER_ONLY = 1;
    /** Flags 1 and launcher applied: steps 6-8 only. */
    public static final int TAIL = 2;

    private static final String ACTION_TV_SETTINGS_POST_SETUP =
            "com.google.android.tungsten.setupwraith.TV_SETTINGS_POST_SETUP";
    private static final String ACTION_SETUP_WIZARD_FINISHED =
            "com.google.android.setupwizard.SETUP_WIZARD_FINISHED";

    private Finisher() {}

    /** Steps 1-6. Worker thread only (the bridge call can take seconds). */
    public static void runBackground(Context c, SetupState st, int mode, String reason) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        L.i("finish begin mode=" + mode + (reason != null ? " reason=" + reason : ""));
        // 1. persist state=finishing (FULL only; LAUNCHER_ONLY keeps the flags-1 re-entry rule)
        if (mode == FULL) {
            st.setState(SetupState.STATE_FINISHING);
            L.i("finish step=1 ok");
        }
        // 2. launcher through Z9xProjector LauncherSwitcher (C1: no local copy of the switch)
        if (mode == FULL || mode == LAUNCHER_ONLY) {
            applyLauncher(c, st);
        }
        // 3. the setup flags (KeyReceiver, PhoneWindowManager HOME, our other apps gate on them)
        if (mode == FULL) {
            ContentResolver cr = c.getContentResolver();
            boolean ok = Settings.Global.putInt(cr, Settings.Global.DEVICE_PROVISIONED, 1)
                    & Settings.Secure.putInt(cr, Settings.Secure.USER_SETUP_COMPLETE, 1)
                    & Settings.Secure.putInt(cr, Settings.Secure.TV_USER_SETUP_COMPLETE, 1);
            L.i("finish step=3 " + (ok ? "ok" : "partial"));
            // 4. what SetupWraith's performFinishingTasks broadcasts (not copied: screensaver_* and
            //    stay_on_while_plugged_in, which Z9xProjector IdleOwner owns; deferred job, PAI, Edu)
            broadcasts(c);
            L.i("finish step=4 ok");
        }
        // 6. belt and braces: SetupWraith's own wizard stays off (sysconfig component-override too)
        try {
            if (Sys.installed(c, Sys.PKG_SETUPWRAITH)) {
                c.getPackageManager().setComponentEnabledSetting(Sys.SETUPWRAITH_MAIN,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            L.i("finish step=6 ok");
        } catch (Throwable t) {
            L.w("finish step=6 failed", t);
        }
        st.setState(SetupState.STATE_DONE);
        L.i("finish background done ms=" + (android.os.SystemClock.elapsedRealtime() - t0));
    }

    private static void applyLauncher(Context c, SetupState st) {
        String choice = st.launcher();
        if (choice == null) choice = Sys.defaultLauncher(c);
        if (choice == null) {
            L.w("finish step=2 skipped: no launcher installed");
            st.setLauncherApplied(true);
            return;
        }
        Bundle r = Bridge.setLauncherSync(c, choice);
        if (!Bridge.ok(r)) {
            // one retry: Z9xProjector may be restarting (persistent app, comes back in seconds)
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            r = Bridge.setLauncherSync(c, choice);
        }
        if (Bridge.ok(r)) {
            st.setLauncherApplied(true);
            L.i("finish step=2 ok target=" + choice + " holder=" + r.getString("holder", "?"));
        } else {
            // Record the choice so LauncherSwitcher.ensure() applies it at the next boot (it runs once
            // setup is complete, C2). HOME still resolves meanwhile: Lumen Home (prio 3) or tvlauncher.
            try {
                Settings.Secure.putString(c.getContentResolver(), Sys.KEY_LAUNCHER, choice);
            } catch (Throwable t) {
                L.w("z9x_launcher write", t);
            }
            L.w("finish step=2 bridge failed err=" + Bridge.err(r) + "; recorded z9x_launcher=" + choice);
        }
    }

    private static void broadcasts(Context c) {
        try {
            if (Sys.installed(c, Sys.PKG_TVSETTINGS)) {
                Intent i = new Intent(ACTION_TV_SETTINGS_POST_SETUP).setPackage(Sys.PKG_TVSETTINGS);
                c.sendBroadcast(i, Manifest.permission.WRITE_SECURE_SETTINGS);
            }
            if (Sys.installed(c, Sys.PKG_GMS)) {
                c.sendBroadcast(new Intent(ACTION_SETUP_WIZARD_FINISHED).setPackage(Sys.PKG_GMS));
            }
        } catch (Throwable t) {
            L.w("finish step=4 broadcast", t);
        }
    }

    /** Step 7a: our HOME activity off for good (0 RAM, never started again). Main or worker thread. */
    public static void disableSelf(Context c) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, SetupActivity.class),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            L.i("finish step=7 ok");
        } catch (Throwable t) {
            L.w("finish step=7 failed", t);
        }
    }

    public static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
}
