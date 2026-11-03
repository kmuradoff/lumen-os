package org.lumen.info.core;

import java.util.Locale;

/** Number formatting shared by the screen, the overlay and the export (pure Java). */
public final class Fmt {
    private Fmt() { }

    /** 59.94 -> "59.94", 60.000004 -> "60", 23.976 -> "23.98". */
    public static String hz(double v) {
        if (Double.isNaN(v) || v <= 0) return "?";
        double r = Math.round(v * 100) / 100.0;
        if (Math.abs(r - Math.rint(r)) < 0.005) return String.valueOf((long) Math.rint(r));
        String s = String.format(Locale.US, "%.2f", r);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Centi-hertz of the vendor (5990) -> "59.9". */
    public static String centiHz(int c) {
        return hz(c / 100.0);
    }

    /** Bytes -> "1.2 GB" / "850 MB" (binary units, written as KB / MB / GB). */
    public static String bytes(long b) {
        if (b < 0) return "?";
        if (b >= 1L << 30) return String.format(Locale.US, "%.2f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return (b >> 20) + " MB";
        if (b >= 1L << 10) return (b >> 10) + " KB";
        return b + " B";
    }

    /** Bits per second -> "12.3 Mbit/s" (decimal units). */
    public static String bitrate(double bps) {
        if (Double.isNaN(bps) || bps < 0) return "?";
        if (bps >= 1e9) return String.format(Locale.US, "%.2f Gbit/s", bps / 1e9);
        if (bps >= 1e6) return String.format(Locale.US, "%.1f Mbit/s", bps / 1e6);
        if (bps >= 1e3) return String.format(Locale.US, "%.0f kbit/s", bps / 1e3);
        return String.format(Locale.US, "%.0f bit/s", bps);
    }

    /** kHz -> "1.29 GHz" / "800 MHz". */
    public static String khz(long khz) {
        if (khz <= 0) return "?";
        if (khz >= 1_000_000) return String.format(Locale.US, "%.2f GHz", khz / 1e6);
        return (khz / 1000) + " MHz";
    }

    public static String nits(double v) {
        if (Double.isNaN(v)) return "?";
        if (v < 1) return String.format(Locale.US, "%.4f", v);
        return String.format(Locale.US, "%.0f", v);
    }

    public static String pct(double v) {
        if (Double.isNaN(v)) return "?";
        return String.format(Locale.US, "%.0f %%", v);
    }
}
