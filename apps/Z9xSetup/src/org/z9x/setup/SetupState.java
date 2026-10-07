package org.z9x.setup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Wizard state in device-protected storage (survives a power cut, wiped with /data, so a factory
 * reset or the wipe-on-flash brings the wizard back). Nothing setup-related lives outside /data.
 */
public final class SetupState {
    public static final String STATE_UI = "ui";
    public static final String STATE_FINISHING = "finishing";
    public static final String STATE_DONE = "done";

    public static final String LAUNCHER_LUMEN = "z9x";
    public static final String LAUNCHER_CLASSIC = "classic";

    private static final String K_STATE = "state";
    private static final String K_STEP = "step";
    private static final String K_LAUNCHER = "launcher";
    private static final String K_LAUNCHER_APPLIED = "launcherApplied";
    private static final String K_CRASHES = "crashes";
    private static final String K_TZ_SOURCE = "tzSource";
    private static final String K_TZ_MANUAL = "tzManual";
    private static final String K_OFFLINE = "offline";
    private static final String K_REMOTE_SEEN = "remoteSeen";
    private static final String K_GEO = "geo";
    private static final String K_GEO_TS = "geoTs";
    private static final String K_GEO_CC = "geoCc";

    private final SharedPreferences p;

    public SetupState(Context c) {
        Context de = c.createDeviceProtectedStorageContext();
        p = de.getSharedPreferences("setup", Context.MODE_PRIVATE);
    }

    public String state() { return p.getString(K_STATE, STATE_UI); }

    /** Synchronous: a crash or power cut right after must resume the finish, not the UI. */
    public void setState(String s) { p.edit().putString(K_STATE, s).commit(); }

    public String step() { return p.getString(K_STEP, null); }

    public void setStep(String id) { p.edit().putString(K_STEP, id).apply(); }

    public String launcher() { return p.getString(K_LAUNCHER, null); }

    public void setLauncher(String l) { p.edit().putString(K_LAUNCHER, l).apply(); }

    public boolean launcherApplied() { return p.getBoolean(K_LAUNCHER_APPLIED, false); }

    public void setLauncherApplied(boolean b) { p.edit().putBoolean(K_LAUNCHER_APPLIED, b).commit(); }

    public String tzSource() { return p.getString(K_TZ_SOURCE, "none"); }

    public void setTzSource(String s) { p.edit().putString(K_TZ_SOURCE, s).apply(); }

    public boolean tzManual() { return p.getBoolean(K_TZ_MANUAL, false); }

    public void setTzManual(boolean b) { p.edit().putBoolean(K_TZ_MANUAL, b).apply(); }

    public boolean offline() { return p.getBoolean(K_OFFLINE, false); }

    public void setOffline(boolean b) { p.edit().putBoolean(K_OFFLINE, b).apply(); }

    public boolean remoteSeen() { return p.getBoolean(K_REMOTE_SEEN, false); }

    public void setRemoteSeen(boolean b) { p.edit().putBoolean(K_REMOTE_SEEN, b).apply(); }

    /** GeoIp cache: the shared org.z9x.common.GeoIp.Result JSON (setup's own prefs only, SPEC 4.2). */
    public String geo() { return p.getString(K_GEO, null); }

    /** Country of the last IP lookup (time-zone picker starts with its zones), or null. */
    public String geoCountry() { return p.getString(K_GEO_CC, null); }

    public long geoTs() { return p.getLong(K_GEO_TS, 0); }

    public void setGeo(String json, String country, long ts) {
        p.edit().putString(K_GEO, json).putString(K_GEO_CC, country).putLong(K_GEO_TS, ts).apply();
    }

    // ------------------------------------------------------------------ crash guard (SPEC F1)

    private java.util.List<long[]> crashList() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (String s : p.getString(K_CRASHES, "").split(",")) {
            int c = s.indexOf(':');
            if (c <= 0) continue;
            try {
                out.add(new long[]{Long.parseLong(s.substring(0, c)), Long.parseLong(s.substring(c + 1))});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * Records one crash ("boot:elapsedRealtime"). Synchronous: called from the uncaught-exception
     * handler right before the process dies. Only the last 8 entries are kept.
     */
    public void recordCrash(int boot, long elapsed) {
        java.util.List<long[]> l = crashList();
        l.add(new long[]{boot, elapsed});
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, l.size() - 8); i < l.size(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(l.get(i)[0]).append(':').append(l.get(i)[1]);
        }
        p.edit().putString(K_CRASHES, sb.toString()).commit();
    }

    /**
     * Crashes in this boot within the last {@code windowMs} since the last completed step. Power
     * cycles and low-memory kills (no exception) never count, so a crash loop is the only trigger.
     */
    public int recentCrashes(int boot, long elapsed, long windowMs) {
        int n = 0;
        for (long[] e : crashList()) {
            if (e[0] == boot && elapsed - e[1] >= 0 && elapsed - e[1] < windowMs) n++;
        }
        return n;
    }

    /** A step was completed: the UI is alive and usable. */
    public void clearCrashes() {
        if (!p.getString(K_CRASHES, "").isEmpty()) p.edit().putString(K_CRASHES, "").apply();
    }
}
