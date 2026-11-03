package org.lumen.sensors.x50;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import org.lumen.sensors.core.DeviceProfile;
import org.lumen.sensors.core.Source;

/**
 * The XGIMI X50 profile (G0111, X50 Ultra, mt9681, stock firmware v1.2.64): how the app recognises an X50
 * (DeviceProfile.isX50 on the system properties, read once; asked only when the Z9X profile said no) and what it adds
 * there: thermal_zone0 (type mtk-thermal) is the SoC, and the X50 gmpf AIDL source. Nothing of this runs on the Z9X or
 * any other device, and nothing here touches the Z9X classes.
 */
public final class X50Profile {
    private static final String TAG = "LumenSensors";

    /** thermal_zone0 is the SoC on the X50 (type mtk-thermal; zones 1 and 2 answer EINVAL and stay hidden). */
    public static final int SOC_ZONE = 0;

    private static volatile Boolean sIsX50;

    private X50Profile() {}

    public static boolean isX50() {
        Boolean v = sIsX50;
        if (v == null) {
            v = DeviceProfile.isX50(prop("ro.lumen.device"), prop("ro.boot.xgimi.modelname"), prop("ro.xgimi.modelname"));
            if (v) Log.i(TAG, "device profile: X50");
            sIsX50 = v;
        }
        return v;
    }

    /** The X50-only sources (after the generic ones). */
    public static Source[] sources(Context app) {
        return new Source[] {new X50HalSource(app)};
    }

    private static String prop(String name) {
        try {
            return SystemProperties.get(name, "").trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
