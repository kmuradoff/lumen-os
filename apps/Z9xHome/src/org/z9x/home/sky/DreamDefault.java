package org.z9x.home.sky;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.provider.Settings;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The living sky as the screensaver default on projectors upgraded from Lumen OS 1.0 (Home 1.0.1, once
 * per data partition). The image's framework overlay names {@link SkyDreamService} in
 * config_dreamsDefaultComponent, but SettingsProvider copies that into Settings.Secure only on a fresh
 * userdata, so an upgraded projector keeps what 1.0 left there: an old Android TV default whose package
 * is not in the image any more (BasicDreams Colors, Backdrop, ...) or nothing.
 * <ul>
 * <li>screensaver_default_component (DreamManager's fallback; no settings screen writes it): unset, a
 * known Android default or a dream that is not installed -> the living sky.</li>
 * <li>screensaver_components (the active screensaver): only when it names nothing but known Android
 * defaults or dreams that are not installed -> the living sky. An installed dream (Z9xProjector's
 * clock, the user's pick) is never replaced: Home cannot tell the projector's automatic choice from the
 * user's. Empty stays empty: DreamManager then uses the default above, and Z9xProjector's own one-shot
 * default still decides on a fresh projector.</li>
 * </ul>
 * Needs WRITE_SECURE_SETTINGS (platform-signed priv-app); without it nothing is written and the next
 * start tries again. Runs on the io thread. The decisions are plain Java ({@link #newDefault},
 * {@link #newActive}; host test test/org/z9x/home/sky/DreamDefaultTest).
 */
public final class DreamDefault {
    private DreamDefault() {}

    private static final String TAG = "Z9xSky";
    private static final String K_DONE = "dream_default_v1";
    // Settings.Secure keys (@hide constants; literal names as in Settings.java, Android 14)
    private static final String SCREENSAVER_COMPONENTS = "screensaver_components";
    private static final String SCREENSAVER_DEFAULT_COMPONENT = "screensaver_default_component";

    /** Dreams Android / Android TV images name by default (package/class); none of them ships in Lumen OS. */
    private static final Set<String> ANDROID_DEFAULTS = new HashSet<>(Arrays.asList(
            "com.android.dreams.basic/com.android.dreams.basic.Colors",
            "com.google.android.backdrop/com.google.android.backdrop.Backdrop",
            "com.google.android.deskclock/com.android.deskclock.Screensaver",
            "com.android.deskclock/com.android.deskclock.Screensaver"));

    private static boolean sNoPermissionLogged;

    public static void applyOnce(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(SkySettings.FILE, Context.MODE_PRIVATE);
            if (p.getBoolean(K_DONE, false)) return;
            if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                if (!sNoPermissionLogged) Log.w(TAG, "dream default: no WRITE_SECURE_SETTINGS, not applied");
                sNoPermissionLogged = true;
                return;
            }
            ComponentName sky = new ComponentName(c, SkyDreamService.class);
            String flat = sky.flattenToString();
            ContentResolver cr = c.getContentResolver();
            Predicate<String> installed = n -> installedDream(c, n);
            boolean ok = true;
            String def = Settings.Secure.getString(cr, SCREENSAVER_DEFAULT_COMPONENT);
            if (newDefault(def, installed)) {
                ok = Settings.Secure.putString(cr, SCREENSAVER_DEFAULT_COMPONENT, flat);
                Log.i(TAG, "dream default: screensaver_default_component " + def + " -> sky: " + ok);
            } else {
                Log.i(TAG, "dream default: screensaver_default_component " + def + " kept");
            }
            String comps = Settings.Secure.getString(cr, SCREENSAVER_COMPONENTS);
            if (newActive(comps, installed)) {
                boolean w = Settings.Secure.putString(cr, SCREENSAVER_COMPONENTS, flat);
                Log.i(TAG, "dream default: screensaver_components " + comps + " -> sky: " + w);
                ok &= w;
            } else {
                Log.i(TAG, "dream default: screensaver_components " + comps + " kept");
            }
            if (ok) p.edit().putBoolean(K_DONE, true).apply();
        } catch (Throwable t) {
            Log.w(TAG, "dream default: " + t + " (tried again on the next start)");
        }
    }

    /** screensaver_default_component gets the sky: unset, or nothing in it is a usable dream of this image. */
    static boolean newDefault(String value, Predicate<String> installedDream) {
        return value == null || value.trim().isEmpty() || replaceable(value, installedDream);
    }

    /** screensaver_components gets the sky: set, and nothing in it is a usable dream of this image. */
    static boolean newActive(String value, Predicate<String> installedDream) {
        return value != null && !value.trim().isEmpty() && replaceable(value, installedDream);
    }

    /** True if every entry of the comma-separated list is a known Android default or not an installed dream. */
    private static boolean replaceable(String list, Predicate<String> installedDream) {
        for (String e : list.split(",")) {
            String n = normalize(e);
            if (n == null || ANDROID_DEFAULTS.contains(n)) continue; // unreadable: no usable dream either
            if (installedDream.test(n)) return false;
        }
        return true;
    }

    /** "pkg/cls" as ComponentName.unflattenFromString reads it ("pkg/.Cls" = "pkg/pkg.Cls"); null if none. */
    static String normalize(String flat) {
        if (flat == null) return null;
        String s = flat.trim();
        int i = s.indexOf('/');
        if (i <= 0 || i == s.length() - 1) return null;
        String pkg = s.substring(0, i), cls = s.substring(i + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }

    /** An enabled service that the system may bind as a dream (DreamManagerService validates the same). */
    private static boolean installedDream(Context c, String flat) {
        ComponentName cn = ComponentName.unflattenFromString(flat);
        if (cn == null) return false;
        try {
            ServiceInfo si = c.getPackageManager().getServiceInfo(cn, 0);
            return si != null && Manifest.permission.BIND_DREAM_SERVICE.equals(si.permission);
        } catch (Exception e) { // NameNotFoundException (named loosely: the host test loads this class)
            return false;
        }
    }
}
