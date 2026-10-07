/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings of the HDMI app (credential-encrypted prefs; the app is not directBootAware). */
final class Prefs {
    static final String FILE = "z9x_hdmi";

    static final String AUTO_SWITCH = "auto_switch_on_plug";
    static final String RETURN_HOME = "return_home_on_unplug";
    static final String CEC_ONE_TOUCH_PLAY = "cec_one_touch_play";
    static final String CEC_CONTROL = "cec_control";
    static final String CEC_INTERNAL_ON_EXIT = "cec_internal_on_exit";
    static final String OPEN_ON_BOOT = "open_on_boot";
    static final String LAST_INPUT = "last_input_id";
    static final String BOOT_OPENED_FOR = "boot_opened_for";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean get(Context c, String key) {
        try {
            return sp(c).getBoolean(key, defaultOf(key));
        } catch (RuntimeException e) {
            return defaultOf(key);
        }
    }

    static void set(Context c, String key, boolean value) {
        try {
            sp(c).edit().putBoolean(key, value).apply();
        } catch (RuntimeException ignored) {
        }
    }

    static boolean defaultOf(String key) {
        switch (key) {
            case AUTO_SWITCH:
            case RETURN_HOME:
            case CEC_ONE_TOUCH_PLAY:
            case CEC_CONTROL:
                return true;
            case CEC_INTERNAL_ON_EXIT:
            case OPEN_ON_BOOT:
            default:
                return false;
        }
    }

    static String getString(Context c, String key) {
        try {
            return sp(c).getString(key, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void setString(Context c, String key, String value) {
        try {
            sp(c).edit().putString(key, value).apply();
        } catch (RuntimeException ignored) {
        }
    }
}
