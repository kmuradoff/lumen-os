package org.lumen.sensors.hal;

/**
 * Decoders of the XGIMI HAL struct replies, from little-endian bytes (the reader copies each HwBlob
 * into a reused byte array). The reply size is checked twice: HwParcel.readBuffer(n) fails when the
 * vendor's buffer is not n bytes, and every decoder here refuses a length other than its exact size,
 * so a size mismatch only makes the value unavailable. Field order comes from the stock AIDL twins
 * (research); sizes from the Z9X proxy library. Also the units of the motion and light usage
 * values. Plain Java (host tests).
 */
public final class GmpfStructs {
    private GmpfStructs() {}

    /** IGmpf 584 getMotionDetection: int16 acc x,y,z @0/2/4, int16 gyro x,y,z @6/8/10, u8 type @12, 1 pad. */
    public static final int MOTION_SIZE = 14;
    /** Accelerometer counts per g: z reads 4092 with the owner's Z9X lying flat (device test 2026-10-27). */
    public static final double ACCEL_LSB_PER_G = 4096;
    /** Plausible light source hours (IGmpf2 27). */
    public static final long LIGHT_HOURS_MAX = 100_000;
    /** IGmpf2 252 getCurHwCurrent: 8 x int16 R, G, B, C, R2, G2, B2, C2. */
    public static final int LED_CURRENTS_SIZE = 16;
    /** IGmpf 662 getHdmiInfo: u32 width @0, u32 height @4, u8 interlace @8, u8 hdmi @9, u32 fps x100 @12. */
    public static final int HDMI_INFO_SIZE = 16;
    /** IGmpf2 319 getVideoInfo: u16 width @0, u16 height @2, u16 fps x100 @4, u16 hdmiOrDvi @6, i32 type @8. */
    public static final int VIDEO_INFO_SIZE = 12;

    /** Motion: out[0..2] accelerometer x, y, z; out[3..5] gyroscope x, y, z; out[6] sensor type. */
    public static boolean motion(byte[] b, int len, int[] out) {
        if (len != MOTION_SIZE || b.length < len || out.length < 7) return false;
        for (int i = 0; i < 6; i++) out[i] = i16(b, i * 2);
        out[6] = b[12] & 0xff;
        return true;
    }

    /** LED currents: out[0..7] in the order R, G, B, C, R2, G2, B2, C2. */
    public static boolean ledCurrents(byte[] b, int len, int[] out) {
        if (len != LED_CURRENTS_SIZE || b.length < len || out.length < 8) return false;
        for (int i = 0; i < 8; i++) out[i] = i16(b, i * 2);
        return true;
    }

    /** HDMI info: out[0] width, out[1] height, out[2] interlaced 0/1, out[3] HDMI (not DVI) 0/1, out[4] fps x100. */
    public static boolean hdmiInfo(byte[] b, int len, int[] out) {
        if (len != HDMI_INFO_SIZE || b.length < len || out.length < 5) return false;
        out[0] = i32(b, 0);
        out[1] = i32(b, 4);
        out[2] = b[8] != 0 ? 1 : 0;
        out[3] = b[9] != 0 ? 1 : 0;
        out[4] = i32(b, 12);
        return true;
    }

    /** As GmpfClient.HdmiSignal.plausible: a real signal (not "no signal" zeros or a misread). */
    public static boolean hdmiPlausible(int[] h) {
        return h[0] >= 640 && h[0] <= 7680 && h[1] >= 480 && h[1] <= 4320 && h[4] >= 2300 && h[4] <= 24500;
    }

    /** Video info: out[0] width, out[1] height, out[2] fps x100, out[3] HDMI-or-DVI flag, out[4] video type. */
    public static boolean videoInfo(byte[] b, int len, int[] out) {
        if (len != VIDEO_INFO_SIZE || b.length < len || out.length < 5) return false;
        out[0] = u16(b, 0);
        out[1] = u16(b, 2);
        out[2] = u16(b, 4);
        out[3] = u16(b, 6);
        out[4] = i32(b, 8);
        return true;
    }

    public static boolean videoPlausible(int[] v) {
        return v[0] >= 320 && v[0] <= 7680 && v[1] >= 240 && v[1] <= 4320;
    }

    /** One accelerometer axis of 584 in g. */
    public static double accelG(int raw) {
        return raw / ACCEL_LSB_PER_G;
    }

    /**
     * Tilt of the projector from horizontal in degrees (angle between the accelerometer vector and its
     * z axis), or NaN when the reading is not about 1 g. Axis-independent, so the sign conventions of
     * the stock struct do not matter; z is about 4092 when the Z9X lies flat (the research logs had
     * -4020 on another unit; about 4096 per g).
     */
    public static double tiltDegrees(int ax, int ay, int az) {
        double g = Math.sqrt((double) ax * ax + (double) ay * ay + (double) az * az);
        if (g < 1000 || g > 20000) return Double.NaN;
        return Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(az) / g)));
    }

    /**
     * Light source hours from light_list[].usage_time of the IGmpf2 27 JSON, or NaN outside
     * 0..{@link #LIGHT_HOURS_MAX}. usage_time is whole HOURS: stock PJLink (settings LampCommand)
     * answers the LAMP query, cumulative lamp hours by the PJLink spec, with it as is. (Stock System
     * info divides it by 3600 as seconds; 0.1 did the same and showed "0.0 h". The HAL source logs the
     * raw JSON once per start so the device can confirm the unit.)
     */
    public static double lightHours(long usageTime) {
        return usageTime >= 0 && usageTime <= LIGHT_HOURS_MAX ? usageTime : Double.NaN;
    }

    static int i16(byte[] b, int o) {
        return (short) ((b[o] & 0xff) | (b[o + 1] << 8));
    }

    static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    static int i32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | (b[o + 3] << 24);
    }
}
