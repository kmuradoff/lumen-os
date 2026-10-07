/*
 * Starts the AirPlay receiver after boot and after an app update, if it is enabled.
 * Android 14 allows starting a connectedDevice foreground service from these broadcasts.
 * Ported to Java from jqssun/android-airplay-server v0.0.31 BootReceiver.kt (GPL-3.0).
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!Prefs.get(c).getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED)) return;
        AirPlayService.reload(c);
    }
}
