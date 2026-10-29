package org.lumen.projector.game;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.lumen.projector.Device;
import org.lumen.projector.source.HdmiSource;

/**
 * org.lumen.tvinput's HDMI events on the XGIMI Play 6 (t14; plan 2.3 contract row HDMI_EVENT;
 * docs/play6/research/hdmi_stock.md 7.1). The component tvinput addresses explicitly
 * (org.lumen.tvinput ProjectorEvents: org.lumen.projector/org.lumen.projector.game.HdmiEventReceiver), action
 * {@value #ACTION}, guarded by the signature permission org.lumen.projector.permission.HDMI_EVENT in the
 * manifest. Extras: port (int), active (boolean), reason ("session" | "cec_added" | "cec_removed").
 *
 * "session" (a tvinput session took or released the HDMI hardware) goes to source/HdmiSource, which connects
 * HDMI to the video window with IGmpf 698 or gives the window back to Android. "cec_added" / "cec_removed"
 * never switch the source; while HDMI is shown they let HdmiSource run the game mode console check again
 * (t15: game mode Auto, research hdmi_volume_game.md 3.5). Main thread, nothing here calls the HAL. Inert on
 * any device that is not a Play 6.
 */
public final class HdmiEventReceiver extends BroadcastReceiver {
    private static final String TAG = "P6Hdmi";
    public static final String ACTION = "org.lumen.projector.action.HDMI_EVENT";

    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (i == null || !ACTION.equals(i.getAction()) || !Device.isPlay6()) return;
            String reason = i.getStringExtra("reason");
            int port = i.getIntExtra("port", -1);
            boolean active = i.getBooleanExtra("active", false);
            if ("session".equals(reason)) {
                HdmiSource.onSessionEvent(c, port, active);
            } else {
                HdmiSource.onCecEvent(reason, port);
            }
        } catch (Throwable t) {
            Log.w(TAG, "HDMI_EVENT: " + t);
        }
    }
}
