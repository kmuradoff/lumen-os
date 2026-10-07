package org.z9x.projector.power;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.z9x.projector.Ui;

/**
 * MODULE "power" (Lumen OS 1.0, PLAN C16/C17 projector side): the updater (org.z9x.updater) tells the
 * projector about an update in progress.
 *
 * Explicit broadcast {@value #ACTION} to org.z9x.projector, guarded by the signature permission
 * {@value #PERMISSION} (only platform-signed apps of this image). Optional boolean extras:
 * <ul>
 * <li>{@value #EXTRA_BUSY}: update_engine is applying a payload. While true the SoC never enters STR
 *     (StrGate): a short POWER press gives the lamp-only standby, a foreign sleep keeps a partial
 *     wakelock. The updater also may set sys.z9x.ota.busy=1 (same effect).</li>
 * <li>{@value #EXTRA_REBOOT_WHEN_OFF}: "Restart when I turn the projector off". The next time the
 *     projector would enter STR it reboots instead (reason "reboot,z9x-ota", an attended reboot for the
 *     boot-dark check) and the next boot goes dark again by itself (StandbyController dark boot). Kept
 *     in prefs (a projector process restart keeps it); false clears it.</li>
 * </ul>
 * In Lumen OS 1.0 no update is published or run; these hooks exist so the updater flow can be tested
 * later without another projector build.
 */
public final class OtaStateReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xStr";
    public static final String ACTION = "org.z9x.projector.action.OTA_STATE";
    public static final String PERMISSION = "org.z9x.projector.permission.OTA_STATE";
    public static final String EXTRA_BUSY = "busy";
    public static final String EXTRA_REBOOT_WHEN_OFF = "reboot_when_off";

    static final String PREFS = "z9x_power";
    static final String K_REBOOT_WHEN_OFF = "ota_reboot_when_off";

    @Override
    public void onReceive(Context ctx, Intent i) {
        try {
            if (i == null || !ACTION.equals(i.getAction())) return;
            final Context app = ctx.getApplicationContext();
            if (i.hasExtra(EXTRA_BUSY)) StrGate.setOtaBusy(i.getBooleanExtra(EXTRA_BUSY, false));
            if (i.hasExtra(EXTRA_REBOOT_WHEN_OFF)) {
                boolean v = i.getBooleanExtra(EXTRA_REBOOT_WHEN_OFF, false);
                prefs(app).edit().putBoolean(K_REBOOT_WHEN_OFF, v).apply();
                Log.i(TAG, "update: restart when turned off = " + v);
            }
            Ui.main().post(() -> StandbyController.onGateChanged(app, "updater"));
        } catch (Throwable t) {
            Log.w(TAG, "OTA_STATE: " + t);
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True once (cleared here): the updater asked for a reboot at the next turn-off. */
    static boolean consumeRebootWhenOff(Context app) {
        try {
            SharedPreferences p = prefs(app);
            if (!p.getBoolean(K_REBOOT_WHEN_OFF, false)) return false;
            p.edit().putBoolean(K_REBOOT_WHEN_OFF, false).commit();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "reboot-when-off pref: " + t);
            return false;
        }
    }
}
