package org.z9x.setup;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemProperties;
import android.provider.Settings;

/** Small system helpers: package presence, the setup flags, image props. */
public final class Sys {
    public static final String PKG_PROJECTOR = "org.z9x.projector";
    public static final String PKG_HOME = "org.z9x.home";
    public static final String PKG_TVLAUNCHER = "com.google.android.tvlauncher";
    public static final String PKG_GMS = "com.google.android.gms";
    public static final String PKG_GSF = "com.google.android.gsf";
    public static final String PKG_SETUPWRAITH = "com.google.android.tungsten.setupwraith";
    public static final String PKG_TVSETTINGS = "com.android.tv.settings";

    public static final ComponentName SETUPWRAITH_MAIN =
            new ComponentName(PKG_SETUPWRAITH, "com.google.android.tungsten.setupwraith.MainActivity");

    /** Settings.Secure key of the launcher mode (owned by Z9xProjector LauncherSwitcher, C1/C2). */
    public static final String KEY_LAUNCHER = "z9x_launcher";

    private Sys() {}

    /** Installed at all (also when disabled: the inactive launcher set is disabled at package level). */
    public static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg,
                    PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Installed and enabled (package level). */
    public static boolean enabled(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean setupFlagsComplete(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 0) == 1
                && Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.TV_USER_SETUP_COMPLETE, 0) == 1;
    }

    public static String prop(String key, String def) {
        try {
            String v = SystemProperties.get(key, def);
            return v == null || v.isEmpty() ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    /** userdebug/eng build (Build.TYPE is public; ro.debuggable may be unreadable by SELinux). */
    public static boolean debuggable() {
        return "1".equals(prop("ro.debuggable", "0"))
                || "userdebug".equals(android.os.Build.TYPE) || "eng".equals(android.os.Build.TYPE);
    }

    /** Lumen OS version for the footer (image prop names stay ro.z9x.* in v1). */
    public static String osVersion() {
        return prop("ro.z9x.version", "1.0");
    }

    /** Which launchers can be chosen. Lumen Home first. */
    public static boolean hasLumenHome(Context c) { return installed(c, PKG_HOME); }

    public static boolean hasClassic(Context c) { return installed(c, PKG_TVLAUNCHER); }

    /** The safe-finish / single-launcher default: Lumen Home if installed, else classic, else null. */
    public static String defaultLauncher(Context c) {
        if (hasLumenHome(c)) return SetupState.LAUNCHER_LUMEN;
        if (hasClassic(c)) return SetupState.LAUNCHER_CLASSIC;
        return null;
    }
}
