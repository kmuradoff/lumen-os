package org.lumen.info.core;

import java.util.Map;

/**
 * Facts of the XGIMI Z9X HB (goat_elegant, G0130) that LumenInfo uses, as plain data (host tests):
 *  - PLAN: every vendor call this app can send (service, absolute code, stub method, read-only flag). The
 *    test checks each row against devices/goat_elegant/research/allowed.tsv, aidl_codes.tsv and banned.tsv,
 *    and that hal/HbGmpf.java has exactly these calls. The only non-getter is setEdidVersion, restricted to the
 *    two stock values 1 (EDID 1.4) and 2 (EDID 2.0), the same choice as the stock signal settings and HbProjector.
 *  - wire values of the getters (input source, HDR type, EDID version, HDMI range, colour space);
 *  - where the built-in EDID files are: the ini /mnt/vendor/xgimiconfig/G0130/Customer_1.ini names them
 *    under [HDMI_EDID_n] as /config/EDID_BIN/..., which is /vendor/tvconfig/bsp/common/EDID_BIN/... in the
 *    Android mount namespace (same file names; the folder is mapped, not proven by the vendor).
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
        {"GmTvManager", "40", "setEdidVersion", "w"},
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
     * HDMI-only reads (timing, range, ALLM) are skipped off HDMI and the per-port reads (cable, port config) only
     * while the HDMI page or the log asks (ports). The two meta calls of the version guard come once per interface.
     */
    public static java.util.List<String> schedule(long tick, boolean hdmi, boolean ports) {
        java.util.List<String> l = new java.util.ArrayList<>(4);
        l.add(SRC);
        if (hdmi) l.add(HDMI_INFO);
        l.add(FAST[(int) (tick % FAST.length)]);
        String s = SLOW[(int) (tick % SLOW.length)];
        boolean hdmiOnly = s.equals(RANGE) || s.equals(ALLM);
        boolean port = s.equals(CONNECT1) || s.equals(CONNECT2) || s.equals(HWCFG1) || s.equals(HWCFG2);
        if ((!hdmiOnly || hdmi) && (!port || ports)) l.add(s);
        return l;
    }

    /** GmAudioManager device id of HDMI ARC (stock com.xgimi.device.GmAudioManager.EN_AUDIO_ARC = 2). */
    public static final byte AUDIO_DEVICE_ARC = 2;

    // ---------------------------------------------------------------- wire values
    public static final int SOURCE_MEDIA = 0, SOURCE_HDMI1 = 1, SOURCE_HDMI2 = 2;
    public static final int EDID_14 = 1, EDID_20 = 2;
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

    /** TV 39 getEdidVersion: 1 = 1.4, 2 = 2.0 (the stock settings show every other value as 1.4). */
    public static String edidVersionName(int v) {
        switch (v) {
            case EDID_14: return "1.4";
            case EDID_20: return "2.0";
            default: return "? (" + v + ")";
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
    public static final String EDID_DIR = "/vendor/tvconfig/bsp/common/EDID_BIN";
    public static final String VENDOR_INI = "/mnt/vendor/xgimiconfig/G0130/Customer_1.ini";
    private static final String CONFIG_PREFIX = "/config/EDID_BIN/";

    /** "/config/EDID_BIN/X.bin" -> EDID_DIR + "/X.bin"; any other path unchanged. Only plain file names pass. */
    public static String mapConfigPath(String p) {
        if (p == null) return null;
        if (p.startsWith(CONFIG_PREFIX)) {
            String name = p.substring(CONFIG_PREFIX.length());
            if (name.isEmpty() || name.contains("/") || name.contains("..")) return null;
            return EDID_DIR + "/" + name;
        }
        return p;
    }

    /**
     * The built-in EDID file of an HDMI port (1 or 2) for an EDID version (1 or 2): from the ini when it has the
     * section, else the stock G0130 names. Never null for a valid port and version.
     */
    public static String edidFile(Map<String, Map<String, String>> ini, int port, int version) {
        String key = version == EDID_20 ? "HDMI_EDID_File_2_0" : "HDMI_EDID_File_1_4";
        Map<String, String> sec = ini == null ? null : ini.get("HDMI_EDID_" + port);
        String p = sec == null ? null : mapConfigPath(sec.get(key));
        if (p != null && !p.isEmpty()) return p;
        return EDID_DIR + "/" + (version == EDID_20
                ? "Mstar_EDID" + port + "_v2.0_4K_2K_3D_HDR.bin"
                : "Mstar_EDID" + port + "_v1.4_3D_Frame_SideHalf_Top.bin");
    }

    /** true when the ini itself named the file (the fallback names were not needed). */
    public static boolean edidFromIni(Map<String, Map<String, String>> ini, int port, int version) {
        String key = version == EDID_20 ? "HDMI_EDID_File_2_0" : "HDMI_EDID_File_1_4";
        Map<String, String> sec = ini == null ? null : ini.get("HDMI_EDID_" + port);
        return sec != null && mapConfigPath(sec.get(key)) != null;
    }

    /** A file name of EDID_DIR that may be listed and read: *.bin, no path parts. */
    public static boolean isEdidFileName(String n) {
        return n != null && n.endsWith(".bin") && !n.contains("/") && !n.startsWith(".") && n.length() < 128;
    }
}
