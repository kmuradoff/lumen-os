package org.lumen.sensors.z9x;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import org.lumen.sensors.core.DeviceProfile;
import org.lumen.sensors.core.Source;

/**
 * The XGIMI Z9X profile: how the app recognises a Z9X (DeviceProfile.decide on the system properties,
 * read once) and what it adds there: thermal_zone0 is the SoC (mtktv_thermal tsensor) and the XGIMI
 * HAL source. Nothing of this runs on another device; a later Play 6 profile gets its own classes
 * (its IGmpf2 numbers differ).
 */
public final class Z9xProfile {
    private static final String TAG = "LumenSensors";

    /** thermal_zone0 is the SoC on the Z9X. */
    public static final int SOC_ZONE = 0;

    private static volatile Boolean sIsZ9x;

    private Z9xProfile() {}

    public static boolean isZ9x() {
        Boolean v = sIsZ9x;
        if (v == null) {
            int p = DeviceProfile.decide(prop("ro.lumen.device"), prop("ro.boot.hardware"), prop("ro.hardware"),
                    prop("ro.boot.xgimi.modelname"), prop("ro.xgimi.modelname"));
            v = p == DeviceProfile.Z9X;
            Log.i(TAG, "device profile: " + (v ? "Z9X" : "generic"));
            sIsZ9x = v;
        }
        return v;
    }

    /** The Z9X-only sources (after the generic ones). */
    public static Source[] sources(Context app) {
        return new Source[] {new Z9xHalSource(app)};
    }

    private static String prop(String name) {
        try {
            return SystemProperties.get(name, "").trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
