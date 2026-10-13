package org.z9x.projector.cec;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

/**
 * The XGIMI Play 6 class behind the HDMI-CEC seams of the shared code (docs/play6/SETTINGS_PLAN.md 1.5,
 * 2.4: RemotePrompt, StandbyStateProvider). Not a copy of the Z9X CecPolicy (PM51 cec0 arming, wake
 * guards, storm limit): the Play 6 CEC page and policy are plan P2, after the sleep and wake test.
 * Until then the framework's own HDMI-CEC settings act alone.
 */
public final class CecPolicy {
    private static final String TAG = "P6Cec";

    private CecPolicy() {}

    /** This wake was accepted from an HDMI device over CEC. P1: never (no Lumen CEC wake). Main thread. */
    public static boolean cecWakeActive() {
        return false;
    }

    /**
     * StandbyStateProvider "cec_wake" from org.z9x.tvinput: not handled on the Play 6 (P1). The reply has
     * the shape of the Z9X one, {standby, accepted}: standby is the real lamp-only standby state (t13,
     * StandbyController), accepted is always false, so an HDMI source never lights the lamp; the user's
     * remote key does.
     */
    public static Bundle onWakeRequest(Context ctx, Bundle ex) {
        Bundle r = new Bundle();
        r.putBoolean("standby", org.z9x.projector.power.StandbyController.isActive());
        r.putBoolean("accepted", false);
        Log.i(TAG, "cec_wake not handled on the Play 6 (plan P2)");
        return r;
    }
}
