package org.z9x.home;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User choices of Lumen Home (one small SharedPreferences file, credential-protected storage).
 * Lists are stored as '\n'-joined strings so their order survives (StringSet has no order).
 */
public final class Prefs {
    public static final String FILE = "home";

    public static final String K_FAVORITES = "favorites";          // ordered component list
    public static final String K_FAVORITES_SET = "favorites_set";  // the user pinned something
    public static final String K_HIDDEN_APPS = "hidden_apps";      // component or package
    public static final String K_ROW_ORDER = "row_order";          // ordered row ids (manual order)
    public static final String K_HIDDEN_ROWS = "hidden_rows";      // row ids
    public static final String K_SHOWN_ROWS = "shown_rows";        // rows hidden by default the user enabled
    public static final String K_WEATHER_ON = "weather_on";
    public static final String K_UNITS = "units";                  // auto | c | f
    public static final String K_CITY = "city";                    // manual city JSON or ""
    public static final String K_HERO_AUTO = "hero_auto";
    public static final String K_TRAILERS = "trailers";
    public static final String K_INPUT_LABELS = "input_labels";    // id\tlabel lines
    public static final String K_RECENT_SEARCH = "recent_search";
    public static final String K_INIT_DONE = "init_programs_done";
    public static final String K_KNOWN_PKGS = "known_pkgs";        // pkg\tfirstSeen lines
    public static final String K_VOICE_FAIL_UNTIL = "voice_fail_until";
    public static final String K_CONTINUE_HOME = "continue_home";  // 1.0.1: hero + "Continue watching" on Home

    private final SharedPreferences mSp;

    Prefs(Context c) {
        mSp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return mSp;
    }

    // ------------------------------------------------------------------ generic helpers

    public List<String> list(String key) {
        String s = mSp.getString(key, "");
        if (s.isEmpty()) return new ArrayList<>();
        ArrayList<String> out = new ArrayList<>();
        for (String x : s.split("\n")) if (!x.isEmpty()) out.add(x);
        return out;
    }

    public void putList(String key, List<String> l) {
        mSp.edit().putString(key, String.join("\n", l)).apply();
    }

    public Set<String> set(String key) {
        return new HashSet<>(list(key));
    }

    public void putSet(String key, Set<String> s) {
        ArrayList<String> l = new ArrayList<>(s);
        Collections.sort(l);
        putList(key, l);
    }

    public void toggleInSet(String key, String v, boolean present) {
        Set<String> s = set(key);
        if (present ? s.add(v) : s.remove(v)) putSet(key, s);
    }

    public Map<String, String> map(String key) {
        HashMap<String, String> m = new HashMap<>();
        for (String line : list(key)) {
            int i = line.indexOf('\t');
            if (i > 0) m.put(line.substring(0, i), line.substring(i + 1));
        }
        return m;
    }

    public void putMap(String key, Map<String, String> m) {
        ArrayList<String> l = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            String v = e.getValue().replace('\n', ' ').replace('\t', ' ');
            l.add(e.getKey() + "\t" + v);
        }
        Collections.sort(l);
        putList(key, l);
    }

    public boolean bool(String key, boolean def) {
        return mSp.getBoolean(key, def);
    }

    public void putBool(String key, boolean v) {
        mSp.edit().putBoolean(key, v).apply();
    }

    public String str(String key, String def) {
        return mSp.getString(key, def);
    }

    public void putStr(String key, String v) {
        mSp.edit().putString(key, v).apply();
    }

    public long lng(String key, long def) {
        return mSp.getLong(key, def);
    }

    public void putLong(String key, long v) {
        mSp.edit().putLong(key, v).apply();
    }

    // ------------------------------------------------------------------ typed accessors

    public boolean weatherOn() {
        return bool(K_WEATHER_ON, true);
    }

    public boolean heroAuto() {
        return bool(K_HERO_AUTO, true);
    }

    /** Hero trailers: OFF by default (user decision, SPEC D10). */
    public boolean trailers() {
        return bool(K_TRAILERS, false);
    }

    public String units() {
        return str(K_UNITS, "auto");
    }

    /**
     * "Continue watching on Home" (Customize, next to the wallpaper): shown by default. Hidden, Home is
     * always the calm one (big clock, date, weather over the sky, then the apps) and never reads TvProvider.
     */
    public boolean continueOnHome() {
        return bool(K_CONTINUE_HOME, true);
    }
}
