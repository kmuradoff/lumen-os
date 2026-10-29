package org.lumen.projector.diag;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import org.lumen.projector.Device;
import org.lumen.projector.Hal;
import org.lumen.projector.hal.Play6Gmpf;
import org.lumen.projector.hal.Play6Gmpf2;
import org.lumen.projector.hal.Play6KstPoint;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only HAL probe of the XGIMI Play 6 test builds (docs/play6/SETTINGS_PLAN.md section 5 step 11,
 * device test A5 / A6): only when ro.lumen.version starts with play6-t AND persist.lumen.p6.probe=1.
 * Once per process, after the first IGmpf connect (so after 307(17)): one log line (tag P6Probe) per
 * read-only getter of A5, then the live IGmpf2 hash chain next to the recorded one. Every call is a typed
 * getter of Play6Gmpf / Play6Gmpf2; nothing is written. 404 getAllFeatureList runs on the p6-long thread.
 *
 * The live IGmpf chain is not read: check H allows the IBase getHashChain code only in Play6Gmpf2 (Note B
 * rule 2), so the IGmpf line shows the recorded value only (plan conflict noted in
 * docs/play6/impl/p0-5-app-hal-core.md).
 *
 * Turn on: adb shell setprop persist.lumen.p6.probe 1; adb reboot. Turn off: setprop ... 0.
 */
public final class Probe {
    private static final String TAG = "P6Probe";
    private static final String SWITCH = "persist.lumen.p6.probe";
    /**
     * IGmpf hash recorded offline, the same on vendor v1.7.116 and v1.1.67 (research/play6/hidl_hashes.txt,
     * plan 2.1); also Projector settings > Diagnostics.
     */
    public static final String IGMPF_RECORDED = "ac4dd3bdeb689d0b403474cc775cd2ced3ad887b600291315b1e41d4b5fa84dc";

    private static final AtomicBoolean DONE = new AtomicBoolean();

    private interface Read {
        Object get() throws Exception;
    }

    private Probe() {}

    /** Play6App at the start gate (p6-hal). Registers the once-per-process hook; no HAL call here. */
    public static void install(Context ctx) {
        if (!Device.isTestBuild() || SystemProperties.getInt(SWITCH, 0) != 1) return;
        Log.i(TAG, "probe armed (" + SWITCH + "=1): reads after the first IGmpf connect");
        Hal.addConnectedListener(() -> {
            if (DONE.compareAndSet(false, true)) Hal.run(Probe::readAll);
        });
    }

    /** p6-hal. */
    private static void readAll(Play6Gmpf g, Play6Gmpf2 g2) {
        Log.i(TAG, "---- IGmpf (read only)");
        line("IGmpf 240 getBatteryLevel", () -> g.getBatteryLevel());
        line("IGmpf 238 isAdapterPowered", () -> g.isAdapterPowered());
        line("IGmpf 239 isBatteryChargedFull", () -> g.isBatteryFull());
        line("IGmpf 327 getEcoState", () -> g.getEcoState());
        line("IGmpf 438 getSystemTemperature", () -> g.getSystemTemperature());
        line("IGmpf 390 getSystemUIState", () -> g.getSystemUiState());
        line("IGmpf 394 getBootAnimState", () -> g.getBootAnimState());
        line("IGmpf 666 getPictureMode(0)", () -> g.getPictureMode());
        line("IGmpf 177 getDlpLumensLevel", () -> g.getLampLevel());
        line("IGmpf 668 getMemcLevel(0)", () -> g.getMemcLevel());
        line("IGmpf 62 getSoundeffect", () -> g.getSoundEffect());
        line("IGmpf 151 getHumanDetectOnOff", () -> g.getEyeProtection());
        line("IGmpf 553 getPowerOnAKFlag", () -> g.getPowerOnAk());
        line("IGmpf 586 getAccTriggerAK", () -> g.getMoveAk());
        line("IGmpf 588 getAngTriggerAF", () -> g.getMoveAf());
        line("IGmpf 173 getProjectorPutMode", () -> g.getPutMode());
        line("IGmpf 579 getAutoReverse", () -> g.getAutoProjection());
        line("IGmpf 696 getCurrentInputSource", () -> g.getCurrentInputSource());
        line("IGmpf 620 getHdrType", () -> g.getHdrType());
        line("IGmpf 647 getGameModeOption", () -> g.getGameModeOption());
        line("IGmpf 664 getAspectRatio", () -> g.getAspectRatio());
        line("IGmpf 130 getCurrentKeystoneMode", () -> g.getKeystoneMode());
        line("IGmpf 186 getCorrectKeystoneFullPiont(0)", () -> {
            Play6KstPoint p = g.getKeystonePoints();
            return p + " raw " + p.raw();
        });
        line("IGmpf 402 getHdmiAutoSwitch", () -> g.getHdmiAutoSwitch());
        line("IGmpf 632 getEARCEnableState", () -> g.getEarc());
        line("IGmpf 179 getDlpLumensComponent_RGB_Level", () -> java.util.Arrays.toString(g.getRgbLevels()));
        Log.i(TAG, "---- IGmpf2 (read only, behind the identity guard)");
        line("IGmpf2 33 getGameModeProp", () -> {
            int[] v = g2.getGameModeProp();
            return "raw " + v[0] + "," + v[1] + "," + v[2];
        });
        line("IGmpf2 63 getPowerOnAFUseDistance", () -> g2.getSmartAutofocus());
        line("IGmpf2 213 getAllmEnableState", () -> g2.getAllmAutoSwitch());
        line("IGmpf2 51 getCopowerBatteryLevel", () -> g2.getStandBatteryLevel());
        line("IGmpf2 52 getCopowerDeviceType", () -> g2.getPowerSourceType());
        line("IGmpf2 46 getPowerSupplyVoltage", () -> g2.getPowerSupplyVoltage());
        line("IGmpf2 47 getAutoEcoMode", () -> g2.getAutoEcoMode());
        line("IGmpf2 81 getStandVerticalAngle", () -> g2.getStandVerticalAngle());
        line("IGmpf2 84 isConvexLensIn", () -> g2.isConvexLensIn());
        line("IGmpf2 75 getConvexLensType", () -> g2.getConvexLensType());
        line("IGmpf2 228 getTvGammaLevel(0)", () -> g2.getGamma());
        line("IGmpf2 230 getTvDynamicContrastEnable(0)", () -> g2.getDynamicContrast());
        Log.i(TAG, "---- HIDL hash chains (A6)");
        line("IGmpf2 live chain", () -> {
            String[] c = g2.liveHashChain();
            String e0 = c.length > 0 ? c[0] : "-";
            String e1 = c.length > 1 ? c[1] : "-";
            String v = Play6Gmpf2.recordedVendor(e0);
            return "[0] " + e0 + (v != null ? " = recorded " + v : " != recorded " + Play6Gmpf2.RECORDED_HASH
                    + " (v1.7.116) / " + Play6Gmpf2.RECORDED_HASH_V1_1_67 + " (v1.1.67)")
                    + ", [1] " + e1 + (Play6Gmpf2.IBASE_HASH.equals(e1) ? " = IBase" : " != IBase");
        });
        Log.i(TAG, "IGmpf chain: recorded " + IGMPF_RECORDED + " (live not read: the IBase code is Play6Gmpf2 only, check H)");
        Log.i(TAG, "IGmpf2 guard " + (g2.isIdentityOpen() ? "open" : "closed"));
        boolean queued = Hal.runLong((lg, lg2) -> line("IGmpf 404 getAllFeatureList", () -> featureSummary(lg.getAllFeatureList())));
        if (!queued) Log.i(TAG, "IGmpf 404 getAllFeatureList: not run (p6-long busy or not connected)");
    }

    /** Length and the support_battery entry of the 404 list, next to Device.hasBattery() (also Diagnostics). */
    public static String featureSummary(String list) {
        if (list == null) return "null";
        int i = list.indexOf("support_battery");
        String bat = "absent";
        if (i >= 0) {
            int end = i;
            while (end < list.length() && end - i < 60 && ",;\n\r\t ".indexOf(list.charAt(end)) < 0) end++;
            bat = list.substring(i, end);
        }
        return "length " + list.length() + ", " + bat + ", Device.hasBattery() " + Device.hasBattery();
    }

    private static void line(String what, Read r) {
        try {
            Log.i(TAG, what + " = " + r.get());
        } catch (Throwable t) {
            Log.i(TAG, what + " = error: " + t);
        }
    }
}
