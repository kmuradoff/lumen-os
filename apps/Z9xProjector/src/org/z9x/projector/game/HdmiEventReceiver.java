package org.z9x.projector.game;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * MODULE "game" (v6.2). Explicit broadcasts from org.z9x.tvinput (its ProjectorEvents), guarded by the
 * signature permission org.z9x.projector.permission.HDMI_EVENT (manifest):
 *   action org.z9x.projector.action.HDMI_EVENT, extras
 *   port (int 1|2), active (boolean), reason (String: "session" | "cec_added" | "cec_removed").
 * "session": one of tvinput's HdmiSessions got (active) or lost its HDMI hardware, i.e. a TvView
 * started / stopped showing that HDMI port. Main thread; hands over to StandbyController (v6.5: a
 * session that starts during the lamp-only standby never wakes it: the black screen comes back and
 * the lamp stays off) and GameProfile.
 */
public final class HdmiEventReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.z9x.projector.action.HDMI_EVENT";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (intent == null || !ACTION.equals(intent.getAction())) return;
            // v6.5: during the lamp-only standby an HDMI session is covered again, never a wake
            try {
                org.z9x.projector.power.StandbyController.onHdmiEvent(intent.getIntExtra("port", -1),
                        intent.getBooleanExtra("active", false), intent.getStringExtra("reason"));
            } catch (Throwable t) {
                Log.w("Z9xGame", "standby HDMI event: " + t);
            }
            GameProfile.onHdmiEvent(ctx, intent);
        } catch (Throwable t) {
            Log.e("Z9xGame", "HdmiEventReceiver", t);
        }
    }
}
