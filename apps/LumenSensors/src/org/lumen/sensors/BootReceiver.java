package org.lumen.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * After boot: start the HUD when "Show overlay" and "Start at boot" are both on; with "Start at boot"
 * off the overlay starts the boot switched off (the settings then say so). After an update of this
 * app (MY_PACKAGE_REPLACED) the HUD comes back if it was on. Both broadcasts may start a foreground
 * service from the background. No other work here (the HAL source waits 60 s after boot by itself).
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LumenSensors";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        Prefs p = Hub.get(c).prefs;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            if (!p.enabled()) return;
            if (!p.startAtBoot()) {
                p.setEnabled(false);
                return;
            }
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            if (!p.enabled()) return;
        } else {
            return;
        }
        try {
            c.startForegroundService(new Intent(c, HudService.class));
            Log.i(TAG, "HUD started (" + a + ")");
        } catch (Throwable t) {
            Log.w(TAG, "HUD start: " + t);
        }
    }
}
