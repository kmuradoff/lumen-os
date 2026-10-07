package org.z9x.projector.source;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

/**
 * "Is an HDMI input being shown through our TIF right now?" asked from org.z9x.tvinput's
 * HdmiStateProvider (ContentProvider.call "hdmi_state", signature permission
 * org.z9x.tvinput.permission.HDMI_STATE). The answer is true while one of its HdmiSessions holds an
 * HDMI hardware (acquireTvInputHardware .. release), i.e. a TvView plays HDMI 1/2.
 *
 * Why: IGmpf 696 getCurrentInputSource() is the only HAL signal for "on HDMI", and it is UNVERIFIED
 * whether it reports 1/2 while our TIF (not the stock XGIMI source switch) plays HDMI. The quick
 * panel therefore uses both: HDMI-only rows need 696 == 1/2 OR this == true; Ultra 120 Hz needs
 * 696 == 0 AND this == false.
 *
 * Returns TRUE / FALSE, or null when unknown (call failed). FALSE also when org.z9x.tvinput (and so
 * its provider) is not installed: then no TIF HDMI session can exist. Worker threads only (one
 * cross-process binder call); never the main thread. Never throws.
 */
public final class TifHdmiState {
    private static final String TAG = "Z9xTifState";
    static final String AUTHORITY = "org.z9x.tvinput.hdmistate";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final String METHOD = "hdmi_state";
    private static final String KEY_ACTIVE = "active";

    private TifHdmiState() {}

    public static Boolean read(Context ctx) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "read on the main thread refused");
            return null;
        }
        try {
            if (ctx.getPackageManager().resolveContentProvider(AUTHORITY, 0) == null) {
                return Boolean.FALSE;                        // no tvinput provider: no TIF HDMI session
            }
            Bundle b = ctx.getContentResolver().call(URI, METHOD, null, null);
            if (b == null || !b.containsKey(KEY_ACTIVE)) return null;
            return b.getBoolean(KEY_ACTIVE);
        } catch (Throwable t) {
            Log.w(TAG, "hdmi_state: " + t);
            return null;
        }
    }
}
