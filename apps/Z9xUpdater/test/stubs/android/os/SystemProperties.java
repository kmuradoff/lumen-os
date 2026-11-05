// SPDX-License-Identifier: Apache-2.0
package android.os;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Host test stand-in for the hidden android.os.SystemProperties (test/run.sh): values from a map. set() logs
 * "key=value" to SETS and throws for a key in DENY, as the real one does when SELinux refuses the write.
 */
public final class SystemProperties {
    private SystemProperties() {}

    public static final Map<String, String> VALUES = new HashMap<>();
    public static final List<String> SETS = new ArrayList<>();
    public static final Set<String> DENY = new HashSet<>();

    public static String get(String key, String def) {
        String v = VALUES.get(key);
        return v == null ? def : v;
    }

    public static void set(String key, String val) {
        if (DENY.contains(key)) throw new RuntimeException("failed to set system property (check logcat for reason)");
        SETS.add(key + "=" + val);
        VALUES.put(key, val);
    }
}
