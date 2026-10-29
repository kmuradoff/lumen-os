package org.lumen.home;

import android.os.SystemProperties;

/**
 * Home's device switches, set by the image of a device that needs them (ui/Gfx, data/InputSource):
 * {@code debug.lumen.home.<name>} wins over {@code ro.lumen.home.<name>}, for A/B tests on a test build
 * ({@code adb shell setprop debug.lumen.home.<name> ...}, then {@code adb shell am force-stop org.lumen.home}).
 * Unset (or unreadable) means the default, the 1.0.2 behaviour.
 */
public final class Props {
    private Props() {}

    public static int num(String name, int def) {
        return parse(get(name), def);
    }

    public static float fnum(String name, float def) {
        return parseFloat(get(name), def);
    }

    /** {@code debug.lumen.home.<name>}, else {@code ro.lumen.home.<name>}, else "". */
    private static String get(String name) {
        try {
            String v = SystemProperties.get("debug.lumen.home." + name, "");
            return v.isEmpty() ? SystemProperties.get("ro.lumen.home." + name, "") : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** "" = {@code def}; "true" / "false" = 1 / 0; else the integer, {@code def} if it is none. */
    public static int parse(String v, int def) {
        if (v == null) return def;
        v = v.trim();
        if (v.isEmpty()) return def;
        if (v.equals("true")) return 1;
        if (v.equals("false")) return 0;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** "" = {@code def}; else the number ("0.5"), {@code def} if it is none or not finite. */
    public static float parseFloat(String v, float def) {
        if (v == null) return def;
        v = v.trim();
        if (v.isEmpty()) return def;
        try {
            float f = Float.parseFloat(v);
            return Float.isNaN(f) || Float.isInfinite(f) ? def : f;
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
