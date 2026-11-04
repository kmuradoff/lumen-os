/*
 * Copyright (C) 2026 Lumen OS. Licensed under the Apache License, Version 2.0.
 */
package org.lumen.tvinput;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The source the projector opens at power-on, as the stock signal settings of the Z9X and the HB offer it (newsettings
 * SignalRepository.getSupportBootSignalSourceList, SignalManager.setBootSourcePro, persist.sys.hdmi.bootsource):
 * 0 the home screen, 1 HDMI 1, 2 HDMI 2. Stock XRMService BootControlService applies it at power-on and on every
 * wake from sleep (HdmiWatcher). Kept in this app's own preferences (the z9x_hdmi file of Prefs). The old Lumen check box "open HDMI at
 * power-on" (Prefs.OPEN_ON_BOOT) is moved over once: on, it becomes the port of the last input used, else HDMI 1.
 */
final class BootSource {
    static final String KEY = "boot_source";
    static final int HOME = 0;

    private BootSource() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
    }

    /** 0 home, or an HDMI port number. */
    static int get(Context c) {
        try {
            SharedPreferences p = sp(c);
            if (p.contains(KEY)) return Math.max(HOME, p.getInt(KEY, HOME));
            int v = HOME;
            if (Prefs.get(c, Prefs.OPEN_ON_BOOT)) {
                int port = HdmiInputs.portOf(HdmiInputs.info(c, Prefs.getString(c, Prefs.LAST_INPUT)));
                v = port > 0 ? port : 1;
            }
            p.edit().putInt(KEY, v).apply();
            return v;
        } catch (RuntimeException e) {
            return HOME;
        }
    }

    static void set(Context c, int value) {
        try {
            sp(c).edit().putInt(KEY, Math.max(HOME, value)).apply();
        } catch (RuntimeException ignored) {
        }
    }
}
