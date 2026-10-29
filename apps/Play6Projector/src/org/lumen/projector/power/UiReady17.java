package org.lumen.projector.power;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.Play6App;
import org.lumen.projector.hal.Play6Gmpf;

/**
 * IGmpf 307 sendFocusStatus(17), the power-on UI-ready signal of the XGIMI Play 6
 * (docs/play6/SETTINGS_PLAN.md 1.1, 1.3, Note C). Stock SystemUI FocusUIV2.start() sends it once per boot
 * (the Play 6 is not ATV); the vendor then starts its power-on AF / AK flow (auto keystone at power-on,
 * IGmpf 553). BootHandshake does not send it: this class does, once per boot_id, from Play6App after the
 * start gate (Hal connected), on p6-hal. The only caller of Play6Gmpf.sendPowerOnUiReady (check H).
 *
 * Kill switch: persist.lumen.p6.ui_ready17=0 (default on). One log line (tag P6Power).
 * boot_id guard as BootHandshake's, fail-open: the boot id is stored (device-protected prefs) only after
 * the call reached the HAL; a ReplyException counts as delivered and is never repeated; an unreadable
 * boot id sends without storing. A call that was not delivered is tried once more after 1 s.
 */
public final class UiReady17 {
    private static final String TAG = "P6Power";
    private static final String KILL_SWITCH = "persist.lumen.p6.ui_ready17";
    private static final String K_BOOT17 = "boot_id_307_17";
    private static final int MAX_TRIES = 2;
    private static final long RETRY_MS = 1_000;

    private UiReady17() {}

    /** Play6App (Hal's first connect, p6-hal). Posts the send on p6-hal; never throws. */
    public static void sendOnce(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (SystemProperties.getInt(KILL_SWITCH, 1) == 0) {
            Log.i(TAG, "ui_ready17 off (" + KILL_SWITCH + "=0)");
            return;
        }
        final String boot = Hal.bootId();
        final SharedPreferences p = Play6App.prefs(app);
        if (boot != null && boot.equals(p.getString(K_BOOT17, null))) {
            Log.i(TAG, "ui_ready17 already sent for this boot");
            return;
        }
        attempt(p, boot, 1);
    }

    private static void attempt(SharedPreferences p, String boot, int n) {
        Hal.run((g, g2) -> send(g, p, boot, n));
    }

    /** p6-hal. */
    private static void send(Play6Gmpf g, SharedPreferences p, String boot, int n) {
        boolean delivered = false;
        try {
            g.sendPowerOnUiReady();
            delivered = true;
            Log.i(TAG, "ui_ready17 sent (307(17), boot_id " + (boot == null ? "unreadable" : "stored") + ")");
        } catch (Play6Gmpf.ReplyException e) {
            delivered = true;
            Log.w(TAG, "ui_ready17 delivered, bad reply (not repeated): " + e.getMessage());
        } catch (RemoteException e) {
            Log.w(TAG, "ui_ready17 not delivered (" + n + "/" + MAX_TRIES + "): " + e);
            if (n < MAX_TRIES) Hal.runDelayed((g1, g2) -> send(g1, p, boot, n + 1), RETRY_MS);
        }
        if (delivered && boot != null) p.edit().putString(K_BOOT17, boot).commit();
    }
}
