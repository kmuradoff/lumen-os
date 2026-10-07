/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * v6.2: tells org.z9x.projector (its game.HdmiEventReceiver, signature permission
 * org.z9x.projector.permission.HDMI_EVENT) when an HDMI port starts or stops being shown through
 * this TIS, and when HDMI-CEC devices come and go, so it can recognise a game console and apply or
 * restore its game profile. One explicit broadcast per change; nothing is sent when the projector app
 * or the permission is missing. The projector re-syncs from HdmiStateProvider anyway, so a lost
 * broadcast is harmless. Any thread; never throws.
 *
 * Extras: port (int 1|2), active (boolean), reason ("session" | "cec_added" | "cec_removed").
 */
final class ProjectorEvents {
    private static final String TAG = "Z9xHdmiEvents";
    static final String ACTION = "org.z9x.projector.action.HDMI_EVENT";
    static final String RECEIVER = "org.z9x.projector.game.HdmiEventReceiver";
    static final String PERMISSION = "org.z9x.projector.permission.HDMI_EVENT";

    private ProjectorEvents() {}

    static void send(Context c, int port, boolean active, String reason) {
        try {
            Context app = c.getApplicationContext();
            if (app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return;
            Intent i = new Intent(ACTION)
                    .setComponent(new ComponentName(HdmiInputs.PROJECTOR_PKG, RECEIVER))
                    .putExtra("port", port)
                    .putExtra("active", active)
                    .putExtra("reason", reason)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            app.sendBroadcast(i);
            Log.i(TAG, "HDMI " + port + " active=" + active + " (" + reason + ")");
        } catch (Throwable t) {
            Log.w(TAG, "send failed: " + t);
        }
    }
}
