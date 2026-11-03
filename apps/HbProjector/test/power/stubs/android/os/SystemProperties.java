package android.os;

import java.util.HashMap;
import java.util.Map;

/** Host stub for test/power: an in-memory property table. */
public class SystemProperties {
    public static final Map<String, String> P = new HashMap<>();

    public static String get(String k, String def) { String v = P.get(k); return v == null ? def : v; }

    public static boolean getBoolean(String k, boolean def) {
        String v = P.get(k);
        if (v == null || v.isEmpty()) return def;
        return "1".equals(v) || "true".equals(v) || "y".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    public static void set(String k, String v) { P.put(k, v); }
}
