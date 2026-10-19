package org.z9x.projector.sys;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Lumen OS 1.0.5: Google's Cast receiver (mediashell, only with the Google add-on) is switched off once.
 * Google refuses its device certificate on uncertified hardware (certificateprovisioning ... devicecertificates/create
 * answers 403, "Loading Widevine creds for 3P device"), so senders list the projector but never cast to it:
 * Chrome shows "Available for specific video sites" and YouTube does not list it. Off, it costs no memory and
 * confuses nobody. Done once (marker in prefs): a user who turns it back on in Settings > Apps keeps it on.
 * Casting on Lumen OS: AirPlay, DLNA (Lumen Play), YouTube "Link with TV code".
 */
public final class CastOff {
    private static final String TAG = "Z9xCastOff";
    private static final String PKG = "com.google.android.apps.mediashell";
    private static final String PREF = "cast_off";

    private CastOff() { }

    public static void install(Context ctx) {
        Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (p.getBoolean("done", false)) return;
        PackageManager pm = app.getPackageManager();
        try {
            pm.getPackageInfo(PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;   // edition without Google: nothing to do, check again on the next start
        }
        try {
            if (pm.getApplicationEnabledSetting(PKG) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                pm.setApplicationEnabledSetting(PKG, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0);
                Log.i(TAG, "Google Cast receiver switched off (no Cast certificate for uncertified devices)");
            }
            p.edit().putBoolean("done", true).apply();
        } catch (Throwable t) {
            Log.w(TAG, "switch off: " + t);
        }
    }
}
