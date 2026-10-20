package org.z9x.projector.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.z9x.projector.Device;
import org.z9x.projector.Hal;

/**
 * Test hook of the battery cards and warnings (docs/play6/SETTINGS_PLAN.md 3.3, device tests C5 to C7).
 * Feeds a vendor power event, IPowerCallback.powerEvent(type, value), into the path a real vendor event
 * takes since the power listener is registered (Battery.onPowerEvent: the read, the panel refresh, the
 * cards), so cards can be tried without pulling the adapter. Stock had the same idea
 * (com.xgimi.ststemui.action.test.PowerEvent).
 *
 *   adb shell am broadcast -a org.z9x.projector.action.TEST_POWER_EVENT -p org.z9x.projector \
 *       --ei type 1 --es value LowPowerPoweroff
 *
 * Exported, guarded by android.permission.DUMP in the manifest (signature|privileged|development: the adb
 * shell, the system and privileged apps allow-listed for it hold it, an ordinary installed app cannot), and inert unless this is a Play 6 test build (ro.lumen.version play6-t...) with the start gate
 * open. Main thread; the reads of the event path run on p6-hal through the Hal facade, nothing is written.
 */
public final class BatteryTestReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.z9x.projector.action.TEST_POWER_EVENT";

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (i == null || !ACTION.equals(i.getAction())) return;
            if (!Device.isPlay6() || !Device.isTestBuild() || !Device.hasBattery()) {
                Log.w(Battery.TAG, "test event ignored: not a Play 6 test build");
                return;
            }
            if (!Hal.isGateOpen()) {
                Log.i(Battery.TAG, "test event ignored: the start gate is still closed");
                return;
            }
            if (!i.hasExtra("type")) {
                Log.w(Battery.TAG, "test event ignored: no int extra 'type'");
                return;
            }
            int type = i.getIntExtra("type", -1);
            Battery.onPowerEvent(type, i.getStringExtra("value"), true);
        } catch (Throwable t) {
            Log.w(Battery.TAG, "test event: " + t);
        }
    }
}
