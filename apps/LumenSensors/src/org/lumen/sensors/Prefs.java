package org.lumen.sensors;

import android.content.Context;
import android.content.SharedPreferences;

import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Sampler;

/**
 * The settings, in SharedPreferences "sensors" (private to the app). Every change applies live:
 * Hub and HudService listen to this file. Choices are stored as indexes into the tables below.
 */
public final class Prefs {
    static final String FILE = "sensors";

    public static final String K_ENABLED = "enabled";
    public static final String K_MODE = "mode";
    public static final String K_POSITION = "position";
    public static final String K_SIZE = "size";
    public static final String K_OPACITY = "opacity";
    public static final String K_REFRESH = "refresh";
    public static final String K_BOOT = "boot";
    public static final String K_GROUPS = "groups";
    /** Groups seen with a value on this device (so the settings list them before the probe ends). */
    public static final String K_AVAILABLE = "available_groups";

    public static final int POS_TOP_LEFT = 0, POS_TOP_RIGHT = 1, POS_BOTTOM_LEFT = 2, POS_BOTTOM_RIGHT = 3;
    public static final float[] SIZE_SCALE = {0.8f, 1f, 1.25f};
    public static final int[] OPACITY_PCT = {40, 60, 80, 100};
    public static final int[] REFRESH_S = {1, 2, 5};

    private static final int DEF_MODE = Sampler.VIEW_COMPACT, DEF_SIZE = 1, DEF_OPACITY = 2, DEF_REFRESH = 1;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return sp; }

    public boolean enabled() { return sp.getBoolean(K_ENABLED, false); }

    public void setEnabled(boolean on) { sp.edit().putBoolean(K_ENABLED, on).apply(); }

    public int mode() { return clamp(sp.getInt(K_MODE, DEF_MODE), 0, 2, DEF_MODE); }

    public int position() { return clamp(sp.getInt(K_POSITION, POS_TOP_LEFT), 0, 3, POS_TOP_LEFT); }

    public int sizeIndex() { return clamp(sp.getInt(K_SIZE, DEF_SIZE), 0, SIZE_SCALE.length - 1, DEF_SIZE); }

    public float sizeScale() { return SIZE_SCALE[sizeIndex()]; }

    public int opacityIndex() { return clamp(sp.getInt(K_OPACITY, DEF_OPACITY), 0, OPACITY_PCT.length - 1, DEF_OPACITY); }

    public int opacityPct() { return OPACITY_PCT[opacityIndex()]; }

    public int refreshIndex() { return clamp(sp.getInt(K_REFRESH, DEF_REFRESH), 0, REFRESH_S.length - 1, DEF_REFRESH); }

    public long refreshMs() { return REFRESH_S[refreshIndex()] * 1000L; }

    public boolean startAtBoot() { return sp.getBoolean(K_BOOT, false); }

    public int groups() { return sp.getInt(K_GROUPS, Group.ALL) & Group.ALL; }

    public int availableGroups() { return sp.getInt(K_AVAILABLE, 0) & Group.ALL; }

    public void setInt(String key, int v) { sp.edit().putInt(key, v).apply(); }

    public void setBoolean(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    public void setGroup(int group, boolean on) {
        int g = groups();
        setInt(K_GROUPS, on ? g | (1 << group) : g & ~(1 << group));
    }

    private static int clamp(int v, int lo, int hi, int def) {
        return v < lo || v > hi ? def : v;
    }
}
