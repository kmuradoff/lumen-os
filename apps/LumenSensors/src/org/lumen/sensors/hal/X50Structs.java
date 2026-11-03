package org.lumen.sensors.hal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X50 ONLY: decoders of the gmpf AIDL replies of the X50 (G0111), from the reply's int32 words after readException
 * (X50Gmpf copies them). Layouts from the X50's own stock code (release_838_newsettings, xgimi/hardware/gmpf/
 * IDisplayType, IGmTvType, IMotionType readFromParcel and the I<Name>$Stub$Proxy readers) and checked against raw
 * replies of the X50 (`service call`, 2026-11-01, host test fixtures):
 *  - a struct returned directly (TV 38, MOTION 2) = readTypedObject: int32 flag (0 = null), then the parcelable;
 *  - an out struct (DISPLAY 57, TV 122): boolean result, int32 flag, then the parcelable;
 *  - a parcelable: int32 total size in bytes (the size word included), then its fields; a boolean or a byte is one
 *    int32, an int[] is an int32 length (-1 = null) and the values. Fields past the size are not read, a reply
 *    shorter than the size or a size below the fields we need makes the value unavailable.
 * Also the units and the enum texts of the X50 values. Plain Java (host tests); nothing here is shared with the Z9X
 * classes (device separation), even where a Z9X decoder looks alike.
 */
public final class X50Structs {
    private X50Structs() {}

    /** Accelerometer counts per g (as on the Z9X; the X50 lying flat read z = -4141 and -4129, about 1.01 g). */
    public static final double ACCEL_LSB_PER_G = 4096;
    /** Plausible light source hours (DISPLAY 176). */
    public static final long LIGHT_HOURS_MAX = 100_000;
    /** At most this many light sources in DISPLAY 176 (the X50 lists 3: its three lasers). */
    public static final int LIGHTS_MAX = 8;

    /**
     * The fields of a parcelable that starts at word {@code at} (its size word): returns the index of the first field
     * word, or -1 when the size is not a whole number of words, is below {@code minFieldWords} fields or runs past
     * the reply.
     */
    static int parcelable(int[] w, int n, int at, int minFieldWords) {
        if (at < 0 || at >= n) return -1;
        int size = w[at];
        if (size < 4 + 4 * minFieldWords || (size & 3) != 0) return -1;
        if (at + size / 4 > n) return -1;
        return at + 1;
    }

    /**
     * TV 38 getHdmiInfo -> stHdmiInfo (readTypedObject): u32Width, u32Height, bInterlace, bIsHdmiMode, u32FrameRate
     * (1/100 Hz). out[0] width, out[1] height, out[2] interlaced 0/1, out[3] HDMI (not DVI) 0/1, out[4] fps x100.
     */
    public static boolean hdmiInfo(int[] w, int n, int[] out) {
        if (n < 1 || w[0] == 0 || out.length < 5) return false;
        int f = parcelable(w, n, 1, 5);
        if (f < 0) return false;
        out[0] = w[f];
        out[1] = w[f + 1];
        out[2] = w[f + 2] != 0 ? 1 : 0;
        out[3] = w[f + 3] != 0 ? 1 : 0;
        out[4] = w[f + 4];
        return true;
    }

    /**
     * TV 122 getVideoInfo(out stVideoInfo) -> boolean: width, height, frameRate (1/100 Hz), bHdmiOrDvi, videoType.
     * out[0] width, out[1] height, out[2] fps x100, out[3] HDMI-or-DVI, out[4] video type.
     */
    public static boolean videoInfo(int[] w, int n, int[] out) {
        if (n < 2 || w[0] == 0 || w[1] == 0 || out.length < 5) return false;
        int f = parcelable(w, n, 2, 5);
        if (f < 0) return false;
        for (int i = 0; i < 5; i++) out[i] = w[f + i];
        return true;
    }

    /**
     * DISPLAY 57 getOutputTimingInfo(out OutputTimingInfo) -> boolean: user_timing_idx, disp_width, disp_height,
     * curr_timing_idx, curr_hres, curr_vres, vfreq, then a nullable stDlpSupportTiming (not read). out[0..6] in that
     * order; out[4] x out[5] at out[6] Hz is what the DLP chip gets.
     */
    public static boolean outputTiming(int[] w, int n, int[] out) {
        if (n < 2 || w[0] == 0 || w[1] == 0 || out.length < 7) return false;
        int f = parcelable(w, n, 2, 7);
        if (f < 0) return false;
        for (int i = 0; i < 7; i++) out[i] = w[f + i];
        return true;
    }

    /**
     * MOTION 2 getMotionDetection -> MsensorDataStruct (readTypedObject): int[] accData, int[] angData, byte
     * msensorType. Both arrays must have 3 values. out[0..2] accData x, y, z (raw counts, as sent), out[3..5]
     * angData, out[6] msensorType (0 when the struct ends before it).
     */
    public static boolean motion(int[] w, int n, int[] out) {
        if (n < 1 || w[0] == 0 || out.length < 7) return false;
        int f = parcelable(w, n, 1, 8);
        if (f < 0) return false;
        int end = 1 + w[1] / 4;
        int p = f;
        if (w[p] != 3 || p + 4 > end) return false;
        for (int i = 0; i < 3; i++) out[i] = w[p + 1 + i];
        p += 4;
        if (p >= end || w[p] != 3 || p + 4 > end) return false;
        for (int i = 0; i < 3; i++) out[3 + i] = w[p + 1 + i];
        p += 4;
        out[6] = p < end ? w[p] & 0xff : 0;
        return true;
    }

    public static boolean hdmiPlausible(int[] h) {
        return h[0] >= 640 && h[0] <= 7680 && h[1] >= 480 && h[1] <= 4320 && h[4] >= 2300 && h[4] <= 24500;
    }

    public static boolean videoPlausible(int[] v) {
        return v[0] >= 320 && v[0] <= 7680 && v[1] >= 240 && v[1] <= 4320;
    }

    public static boolean outputPlausible(int[] o) {
        return o[4] >= 640 && o[4] <= 7680 && o[5] >= 480 && o[5] <= 4320 && o[6] >= 23 && o[6] <= 240;
    }

    /** One accelerometer axis in g. */
    public static double accelG(int raw) {
        return raw / ACCEL_LSB_PER_G;
    }

    /**
     * The z axis in g with the X50's sign turned: the X50 lying flat reads z about -4141 where the Z9X reads +4092,
     * so the HUD shows +1.00 g on both.
     */
    public static double accelZUpG(int raw) {
        return -raw / ACCEL_LSB_PER_G;
    }

    /** Tilt from horizontal in degrees (axis-independent), NaN when the vector is not about 1 g. */
    public static double tiltDegrees(int ax, int ay, int az) {
        double g = Math.sqrt((double) ax * ax + (double) ay * ay + (double) az * az);
        if (g < 1000 || g > 20000) return Double.NaN;
        return Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(az) / g)));
    }

    private static final Pattern LIGHT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern LIGHT_ID = Pattern.compile("\"light_id\"\\s*:\\s*(-?\\d{1,9})");
    private static final Pattern USAGE = Pattern.compile("\"usage_time\"\\s*:\\s*(-?\\d{1,18})");

    /**
     * DISPLAY 176 getLightUsageInfo JSON: the entries of light_list in order, ids[k] = light_id, times[k] = usage_time
     * (-1 when an entry has none). Returns the number of entries, or -1 when the text has no light_list array. Read as
     * whole HOURS, as on the Z9X (stock PJLink answers LAMP with it as is); the X50 stock System info divides light 0
     * by 3600 as seconds, so the HAL source logs the raw JSON once per start to settle the unit on the device.
     */
    public static int lightUsage(String json, int[] ids, long[] times) {
        if (json == null) return -1;
        int at = json.indexOf("\"light_list\"");
        if (at < 0) return -1;
        int open = json.indexOf('[', at);
        int close = open < 0 ? -1 : json.indexOf(']', open);
        if (open < 0 || close < 0) return -1;
        Matcher m = LIGHT.matcher(json.substring(open + 1, close));
        int k = 0;
        while (m.find() && k < ids.length && k < times.length) {
            String e = m.group();
            Matcher id = LIGHT_ID.matcher(e), u = USAGE.matcher(e);
            ids[k] = id.find() ? Integer.parseInt(id.group(1)) : k;
            times[k] = u.find() ? Long.parseLong(u.group(1)) : -1;
            k++;
        }
        return k;
    }

    /** Hours of one light source, or NaN outside 1..{@link #LIGHT_HOURS_MAX} (0 = the vendor keeps no hours). */
    public static double lightHours(long usageTime) {
        return usageTime > 0 && usageTime <= LIGHT_HOURS_MAX ? usageTime : Double.NaN;
    }

    /**
     * TEMPERATURE 3 fan mode with a known meaning: the Z9X / HB table, 0..9 manual 1..10, 10 auto, 11 test. Anything
     * else (the X50 answered 14 at the survey) is unknown: the HUD shows the raw code with a note.
     */
    public static boolean fanModeKnown(int v) {
        return v >= 0 && v <= 11;
    }

    /** DISPLAY 5 light mode with a known name (Z9X: 16 D65, 17 colour temperature 1); 24 (the X50) is unknown. */
    public static boolean lightModeKnown(int v) {
        return v == 16 || v == 17;
    }

    /** TV 78 HDR type names (the formats' own, not translated), null for an unknown value. */
    public static String hdrName(int v) {
        switch (v) {
            case 0: return "SDR";
            case 1: return "HDR10";
            case 2: return "Dolby Vision";
            case 4: return "HLG";
            case 5: return "HDR10+";
            case 6: return "HDR Vivid";
            default: return null;
        }
    }
}
