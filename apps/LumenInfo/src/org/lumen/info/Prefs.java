package org.lumen.info;

import android.content.Context;
import android.content.SharedPreferences;

/** Overlay settings (this app's own SharedPreferences; nothing system-wide). */
public final class Prefs {
    static final String K_ENABLED = "overlay", K_LAYOUT = "layout", K_CORNER = "corner", K_SIZE = "size";
    public static final int TL = 0, TR = 1, BL = 2, BR = 3;

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("info", Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() { return p; }

    public boolean enabled() { return p.getBoolean(K_ENABLED, false); }
    public void setEnabled(boolean v) { p.edit().putBoolean(K_ENABLED, v).apply(); }

    /** Rows.LAYOUT_VIDEO or LAYOUT_FULL. */
    public int layout() { return p.getInt(K_LAYOUT, 0); }
    public void setLayout(int v) { p.edit().putInt(K_LAYOUT, v == 1 ? 1 : 0).apply(); }

    public int corner() { return p.getInt(K_CORNER, TR); }
    public void setCorner(int v) { p.edit().putInt(K_CORNER, Math.max(0, Math.min(3, v))).apply(); }

    /** 0 small, 1 medium, 2 large. */
    public int size() { return p.getInt(K_SIZE, 1); }
    public void setSize(int v) { p.edit().putInt(K_SIZE, Math.max(0, Math.min(2, v))).apply(); }
}
