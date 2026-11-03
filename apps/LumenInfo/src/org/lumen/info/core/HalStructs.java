package org.lumen.info.core;

import java.util.Locale;

/**
 * The vendor parcelables LumenInfo reads on the HB, decoded from plain 32-bit words (pure Java, host tests).
 *
 * Wire (stock gmpf V1 Java proxies, SystemUI release 838 classes3.dex, the same layout the NDK proxies of the
 * vendor gmpf-V1-ndk library write): a stable AIDL parcelable is an int32 total size in
 * bytes (the size word included) followed by its fields in declaration order; int, byte and boolean each take one
 * 32-bit word (Parcel.writeByte / writeBoolean write an int32). readFromParcel stops at the size, so a shorter
 * struct leaves the remaining fields 0 (a newer, longer one is cut at our last field). hal/HbGmpf reads the size,
 * hands the words after it to these decoders and skips to the end of the struct.
 *
 * Field order (readFromParcel of each class, stock source cited):
 *  - IGmTvType.stVideoInfo        width, height, frameRate, bHdmiOrDvi, videoType      (TV 122 getVideoInfo)
 *  - IGmTvType.stHdmiHwCfg        hwPortId, cecEnable(bool), arcEnable(bool), physicalAddr (TV 153)
 *  - IDisplayType.ScreenResolution Center(byte), Horizontal(byte), verticality(byte)   (DISPLAY 31)
 *  - IDisplayType.OutputTimingInfo user_timing_idx, disp_width, disp_height, curr_timing_idx, curr_hres, curr_vres,
 *                                  vfreq, then a nested stDlpSupportTiming (not decoded) (DISPLAY 57)
 *  - IDisplayType.ColorSpaceInfo_st colorSpaceType, colorSpaceLevel                     (DISPLAY 145)
 */
public final class HalStructs {
    private HalStructs() { }

    /** Largest struct we accept (bytes, size word included): far above every struct here, below any garbage. */
    public static final int MAX_SIZE = 512;

    /** Word i of w, 0 when the struct ended before it (as readFromParcel leaves a field at its default). */
    static int word(int[] w, int i) {
        return w != null && i >= 0 && i < w.length ? w[i] : 0;
    }

    /** Number of field words in a struct of total size bytes (the size word excluded); -1 if the size is invalid. */
    public static int fieldWords(int size, int bytesLeft) {
        if (size < 4 || size > MAX_SIZE || size > bytesLeft + 4 || (size & 3) != 0) return -1;
        return (size - 4) / 4;
    }

    // ---------------------------------------------------------------- TV 122 getVideoInfo
    public static final class VideoInfo {
        public final int width, height, frameRate, hdmiOrDvi, videoType;

        VideoInfo(int[] w) {
            width = word(w, 0);
            height = word(w, 1);
            frameRate = word(w, 2);
            hdmiOrDvi = word(w, 3);
            videoType = word(w, 4);
        }

        public boolean plausible() {
            return width >= 320 && width <= 7680 && height >= 200 && height <= 4320;
        }
    }

    public static VideoInfo videoInfo(int[] w) {
        return new VideoInfo(w);
    }

    // ---------------------------------------------------------------- TV 153 getHdmiHwConfig
    public static final class HdmiHwCfg {
        public final int hwPortId, physicalAddr;
        public final boolean cec, arc;

        HdmiHwCfg(int[] w) {
            hwPortId = word(w, 0);
            cec = word(w, 1) != 0;
            arc = word(w, 2) != 0;
            physicalAddr = word(w, 3);
        }
    }

    public static HdmiHwCfg hdmiHwCfg(int[] w) {
        return new HdmiHwCfg(w);
    }

    /** CEC physical address 0x1000 -> "1.0.0.0"; null outside 16 bits or 0 (no address). */
    public static String physicalAddress(int a) {
        if (a <= 0 || a > 0xFFFF) return null;
        return ((a >> 12) & 15) + "." + ((a >> 8) & 15) + "." + ((a >> 4) & 15) + "." + (a & 15);
    }

    // ---------------------------------------------------------------- DISPLAY 31 getScreenResolution
    /** Digital zoom / shift state of the keystone page (stock KeyStoneManager reads Center, index = Center / 5). */
    public static final class ScreenResolution {
        public final int center, horizontal, vertical;

        ScreenResolution(int[] w) {
            center = (byte) word(w, 0);
            horizontal = (byte) word(w, 1);
            vertical = (byte) word(w, 2);
        }
    }

    public static ScreenResolution screenResolution(int[] w) {
        return new ScreenResolution(w);
    }

    // ---------------------------------------------------------------- DISPLAY 57 getOutputTimingInfo
    public static final class OutputTiming {
        public final int userTimingIdx, dispWidth, dispHeight, currTimingIdx, currH, currV, vfreq;

        OutputTiming(int[] w) {
            userTimingIdx = word(w, 0);
            dispWidth = word(w, 1);
            dispHeight = word(w, 2);
            currTimingIdx = word(w, 3);
            currH = word(w, 4);
            currV = word(w, 5);
            vfreq = word(w, 6);
        }

        /** Stock V3DisplayManager.isHighFrameRateOn: user_timing_idx == 4. */
        public boolean highFrameRate() {
            return userTimingIdx == 4;
        }

        public boolean plausible() {
            return currH >= 320 && currH <= 7680 && currV >= 200 && currV <= 4320;
        }
    }

    public static OutputTiming outputTiming(int[] w) {
        return new OutputTiming(w);
    }

    // ---------------------------------------------------------------- DISPLAY 145 getColorSpaceInfo
    /** Stock V3DisplayManager.getColorSpaceInfo: type 0 = off (native gamut), type 1 = on with level = DISPLAY 103 value. */
    public static final class ColorSpaceInfo {
        public final int type, level;

        ColorSpaceInfo(int[] w) {
            type = word(w, 0);
            level = word(w, 1);
        }
    }

    public static ColorSpaceInfo colorSpaceInfo(int[] w) {
        return new ColorSpaceInfo(w);
    }

    // ---------------------------------------------------------------- units
    /** Common refresh rates, to pick the scale of a vendor value (see {@link #rate}). */
    private static final double[] STANDARD = {23.976, 24, 25, 29.97, 30, 47.952, 48, 50, 59.94, 60, 100, 119.88, 120, 144, 165, 240};
    /** Scales tried in order (a tie keeps the earlier): 1/100 Hz as stHdmiInfo.frameRate, 1/1000 Hz, Hz, 1/10 Hz. */
    private static final int[] SCALES = {100, 1000, 1, 10};

    /**
     * A vendor refresh value of unknown scale -> "59.94 Hz", null if no scale gives 10..250 Hz. The HB writes
     * stHdmiInfo.frameRate in 1/100 Hz (5994); vfreq and stVideoInfo.frameRate are not documented by the stock code,
     * so the scale whose result is nearest (relative) to a common rate wins (23976 -> 23.976, 5994 -> 59.94, 60 -> 60).
     */
    public static String rate(int v) {
        if (v <= 0) return null;
        double best = Double.NaN, bestErr = Double.MAX_VALUE;
        for (int sc : SCALES) {
            double hz = v / (double) sc;
            if (hz < 10 || hz > 250) continue;
            double err = Double.MAX_VALUE;
            for (double st : STANDARD) err = Math.min(err, Math.abs(hz - st) / st);
            if (err < bestErr - 1e-9) {
                bestErr = err;
                best = hz;
            }
        }
        return Double.isNaN(best) ? null : Fmt.hz(best) + " Hz";
    }

    /** "WxH" or null. */
    public static String size(int w, int h) {
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null;
        return String.format(Locale.US, "%dx%d", w, h);
    }
}
