package org.lumen.info.core;

import java.util.Map;

/**
 * Facts of the XGIMI Z9X HB (goat_elegant, G0130) that LumenInfo uses, as plain data (host tests):
 *  - PLAN: every vendor call this app can send (service, absolute code, stub method, read-only flag). The
 *    test checks each row against devices/goat_elegant/research/allowed.tsv, aidl_codes.tsv and banned.tsv,
 *    and that hal/HbGmpf.java has exactly these calls. All are getters: the EDID choice is HbProjector's stock row
 *    (TV 40 sets the input on screen, and this app's own screen is never an HDMI input).
 *  - wire values of the getters (input source, HDR type, EDID version, HDMI range, colour space);
 *  - which built-in EDID file the vendor loads: libxgimi Device_HdmiInput builds
 *    /mnt/vendor/xgimiconfig/{model}/tv/{name}_{port}.bin itself (the [HDMI_EDID_n] lines of mtk_Customer.ini are
 *    not used for it), name from the stored request, see {@link #edidName}. The kernel then rewrites the audio
 *    block of the non-ARC inputs in passthrough mode, so a file is never the exact live EDID (checked 2026-11-04
 *    against an external grab of HDMI 2: edid20_non_hrr_2.bin plus that rewrite, 0 bytes differ).
 */
public final class Hb {
    private Hb() { }

    /** {service, code, method, "r" read-only or "w" setter}. Meta calls use service "-". */
    public static final String[][] PLAN = {
        {"-", "16777215", "getInterfaceVersion", "r"},
        {"-", "16777214", "getInterfaceHash", "r"},
        {"GmTvManager", "4", "getCurrentInputSource", "r"},
        {"GmTvManager", "34", "getPictureMode", "r"},
        {"GmTvManager", "38", "getHdmiInfo", "r"},
        {"GmTvManager", "39", "getEdidVersion", "r"},
        {"GmTvManager", "64", "getHdmiRange", "r"},
        {"GmTvManager", "78", "getHdrType", "r"},
        {"GmTvManager", "131", "getAllmStatus", "r"},
        {"DisplayManager", "103", "getDispColorSpace", "r"},
        {"TemperatureManager", "6", "getSystemTemperature", "r"},
        // 2026-11-01: the extra read-only getters, rows of research/allowed_lumeninfo.tsv
        {"GmTvManager", "51", "getHdrEnable", "r"},
        {"GmTvManager", "70", "getHdmiConnectStatus", "r"},
        {"GmTvManager", "106", "getSignalStatus", "r"},
        {"GmTvManager", "122", "getVideoInfo", "r"},
        {"GmTvManager", "135", "isHdrVividPlaying", "r"},
        {"GmTvManager", "153", "getHdmiHwConfig", "r"},
        {"DisplayManager", "31", "getScreenResolution", "r"},
        {"DisplayManager", "57", "getOutputTimingInfo", "r"},
        {"DisplayManager", "145", "getColorSpaceInfo", "r"},
        {"GmAudioManager", "13", "getAudioDelay", "r"},
        {"GmAudioManager", "28", "getARCType", "r"},
        {"GmAudioManager", "29", "getSpdifType", "r"},
        {"GmAudioManager", "33", "isAudioDeviceConnected", "r"},
        {"GmAudioManager", "34", "getAudioDeviceSupportType", "r"},
    };

    /**
     * Getters LumenInfo would use but does not (never called; listed for reference). DISPLAY 26 isPlayingHDRVideo
     * was in the list of 2026-11-01 but is a BANNED row of research/banned.tsv (P6-NoteA: forbidden on the
     * Z9X as IGmpf 194); the ban stays until its own decision. TV 135 and TV 78 give the same facts.
     */
    public static final String[] WANTED_NOT_ALLOWED = {
        "DisplayManager 26 isPlayingHDRVideo",
    };

    // ---------------------------------------------------------------- vendor call schedule
    /** Values of the scheduled vendor reads (see {@link #schedule}). */
    public static final String SRC = "source", HDMI_INFO = "hdmiInfo", HDR_TYPE = "hdrType", VIDEO_INFO = "videoInfo",
            HDR_VIVID = "hdrVivid", OUTPUT_TIMING = "outputTiming", EDID_VERSION = "edidVersion", RANGE = "range",
            ALLM = "allm", PICTURE_MODE = "pictureMode", COLOR_SPACE = "colorSpace", SYS_TEMP = "sysTemp",
            SIGNAL_STATUS = "signalStatus", HDR_ENABLE = "hdrEnable", COLOR_SPACE_INFO = "colorSpaceInfo",
            SCREEN_RES = "screenRes", ARC_TYPE = "arcType", SPDIF_TYPE = "spdifType", ARC_CONNECTED = "arcConnected",
            ARC_SUPPORT = "arcSupport", AUDIO_DELAY = "audioDelay", CONNECT1 = "connect1", CONNECT2 = "connect2",
            HWCFG1 = "hwCfg1", HWCFG2 = "hwCfg2";

    /** Fast group, one per tick in a 5 s round: HDR type twice, the rest every 5 s. */
    private static final String[] FAST = {HDR_TYPE, VIDEO_INFO, HDR_VIVID, OUTPUT_TIMING, HDR_TYPE};
    /** Slow group, one per tick in a 20 s round (signal status twice: every 10 s). */
    private static final String[] SLOW = {
        EDID_VERSION, RANGE, ALLM, PICTURE_MODE, COLOR_SPACE, SYS_TEMP, SIGNAL_STATUS, HDR_ENABLE, COLOR_SPACE_INFO,
        SCREEN_RES, ARC_TYPE, SPDIF_TYPE, ARC_CONNECTED, ARC_SUPPORT, AUDIO_DELAY, CONNECT1, CONNECT2, HWCFG1, HWCFG2,
        SIGNAL_STATUS,
    };
    public static final int FAST_ROUND = FAST.length, SLOW_ROUND = SLOW.length;

    /**
     * The vendor reads of one tick (one tick per second), in order. Lumen rule (fast on weak hardware): at most 4
     * calls while an HDMI input is on screen (input source, HDMI timing, one fast, one slow), at most 3 otherwise;
     * HDMI-only reads (timing, range, ALLM, EDID setting) are skipped off HDMI and the per-port reads (cable, port config) only
     * while the HDMI page or the log asks (ports). The two meta calls of the version guard come once per interface.
     */
    public static java.util.List<String> schedule(long tick, boolean hdmi, boolean ports) {
        java.util.List<String> l = new java.util.ArrayList<>(4);
        l.add(SRC);
        if (hdmi) l.add(HDMI_INFO);
        l.add(FAST[(int) (tick % FAST.length)]);
        String s = SLOW[(int) (tick % SLOW.length)];
        boolean hdmiOnly = s.equals(RANGE) || s.equals(ALLM) || s.equals(EDID_VERSION);   // TV 39 off HDMI: the media slot
        boolean port = s.equals(CONNECT1) || s.equals(CONNECT2) || s.equals(HWCFG1) || s.equals(HWCFG2);
        if ((!hdmiOnly || hdmi) && (!port || ports)) l.add(s);
        return l;
    }

    /** GmAudioManager device id of HDMI ARC (stock com.xgimi.device.GmAudioManager.EN_AUDIO_ARC = 2). */
    public static final byte AUDIO_DEVICE_ARC = 2;

    // ---------------------------------------------------------------- wire values
    public static final int SOURCE_MEDIA = 0, SOURCE_HDMI1 = 1, SOURCE_HDMI2 = 2;
    /**
     * TV 39 / TV 40 raw EDID requests. Stock SignalEnum sends 1 (1.4) / 2 (2.0) to
     * com.xgimi.gmuiapi.manager.GmTvManager.setEDIDVersion, which does version-- on Android 8+, and getEDIDVersion
     * adds 1 back. So the wire is 0 = 1.4, 1 = 2.0; 2 lets the game mode pick the file, 3 = 2.1 (no stock row on G0130).
     */
    public static final int EDID_14 = 0, EDID_20 = 1, EDID_GAME = 2, EDID_21 = 3;
    public static final int[] HDMI_PORTS = {SOURCE_HDMI1, SOURCE_HDMI2};

    /** TV 78 getHdrType. */
    public static String hdrTypeName(int v) {
        switch (v) {
            case 0: return "SDR";
            case 1: return "HDR10";
            case 2: return "Dolby Vision";
            case 4: return "HLG";
            case 5: return "HDR10+";
            case 6: return "HDR Vivid";
            default: return "? (" + v + ")";
        }
    }

    /** TV 39 raw value as an EDID version; null for EDID_GAME and unknown values (the caller words those). */
    public static String edidVersionName(int v) {
        switch (v) {
            case EDID_14: return "1.4";
            case EDID_20: return "2.0";
            case EDID_21: return "2.1";
            default: return null;
        }
    }

    /** DISPLAY 103 colour space wire: 4 Native, 1 BT.2020, 7 Adobe RGB, 2 DCI-P3, 3 Rec.709; null if unknown. */
    public static String colorSpaceName(int v) {
        switch (v) {
            case 1: return "BT.2020";
            case 2: return "DCI-P3";
            case 3: return "Rec.709";
            case 7: return "Adobe RGB";
            case 4: return null;   // Native: translated by the UI
            default: return "? (" + v + ")";
        }
    }

    public static boolean isHdmi(int source) {
        return source == SOURCE_HDMI1 || source == SOURCE_HDMI2;
    }

    // ---------------------------------------------------------------- built-in EDID files
    /** The vendor's own config folder of a model, /mnt/vendor/xgimiconfig/{model} (model = ro.boot.xgimi.modelname). */
    public static final String XGIMICONFIG = "/mnt/vendor/xgimiconfig/";

    /** The model folder, or null for a model name that is not plain (letters and digits, 3 to 12). */
    public static String modelDir(String model) {
        if (model == null || !model.matches("[A-Za-z0-9]{3,12}")) return null;
        return XGIMICONFIG + model;
    }

    public static String featureXml(String model) {
        String d = modelDir(model);
        return d == null ? null : d + "/feature/feature.xml";
    }

    public static String edidDir(String model) {
        String d = modelDir(model);
        return d == null ? null : d + "/tv";
    }

    /**
     * libxgimi Msrv_Tv_Control::_setEdidVersion: a 2.0 request loads edid20_non_hrr instead of edid20 when the model
     * has support_hfr=true and display_gamemode_version=3 in its feature.xml (G0130 has both).
     */
    public static boolean hfrRule(Map<String, String> feature) {
        return feature != null && "true".equals(feature.get("support_hfr")) && "3".equals(feature.get("display_gamemode_version"));
    }

    /** File name stem the vendor loads for a raw request (0-based table of libxgimi), null for EDID_GAME / unknown. */
    public static String edidName(int raw, boolean hfr) {
        switch (raw) {
            case EDID_14: return "edid14";
            case EDID_20: return hfr ? "edid20_non_hrr" : "edid20";
            case EDID_21: return "edid21";
            default: return null;
        }
    }

    /** The built-in EDID file of an HDMI port (1 or 2) for a raw request, or null (no usable model, EDID_GAME). */
    public static String edidFile(String model, int port, int raw, boolean hfr) {
        String d = edidDir(model), n = edidName(raw, hfr);
        if (d == null || n == null || (port != SOURCE_HDMI1 && port != SOURCE_HDMI2)) return null;
        return d + "/" + n + "_" + port + ".bin";
    }

    /** The ARC input of the HB (mtk_sys.ini F_CEC_ARC_HDMI_PORT): the kernel leaves its audio block as the file has it. */
    public static final int ARC_INPUT = SOURCE_HDMI1;

    /** A file name of the EDID folder that may be listed and read: *.bin, no path parts. */
    public static boolean isEdidFileName(String n) {
        return n != null && n.endsWith(".bin") && !n.contains("/") && !n.startsWith(".") && n.length() < 128;
    }
}
