package org.lumen.sensors.core;

/**
 * Value formatting into a reused StringBuilder: no allocation (numbers are written digit by digit,
 * never through Double.toString or String.format). Plain Java (host tests).
 *
 * Formats (what value / value2 / value3 / flags / text mean):
 *   TEMP      value degrees C, whole degrees                     "58 °C"
 *   PERCENT   value 0..100                                       "23%"
 *   MHZ       value MHz                                          "1895 MHz"
 *   MEM       value kB; MB below 1 GB, GB with one decimal       "483 MB", "2.7 GB"
 *   MEM_PAIR  value used kB, value2 total kB                     "1.9 / 2.7 GB"
 *   RATE      value bit/s as Mbit/s (one decimal below 100)      "12.3 Mbit/s"
 *   DBM       value dBm                                          "-65 dBm"
 *   MM        value millimetres                                  "2394 mm"
 *   VOLT      value volts, one decimal                           "19.5 V"
 *   HOURS     value whole hours                                  "1234 h"
 *   HZ        value Hz, up to two decimals                       "23.98 Hz", "60 Hz"
 *   DEG       value degrees of angle, one decimal                "8.8°"
 *   GHZ       value GHz, up to one decimal                       "2.4 GHz"
 *   INT       value plain integer                                "87"
 *   XYZ       three integers (raw counts)                        "0 / 5 / -2"
 *   XYZ_G     three values in g, two decimals                    "0.06 / -0.01 / 1.00 g"
 *   FRACTION  value of value2; 0 = "Off"                         "1/3"
 *   RES       value x value2                                     "3840×2160"
 *   SIGNAL    value x value2, flags bit 0 = interlaced, value3 Hz "1920×1080p 60 Hz"
 *   TEXT      text as is
 *   DURATION  value seconds                                      "35 min", "2 h 05 min"
 *   ON_OFF    value != 0                                         "On" / "Off"
 *   YES_NO    value != 0                                         "Yes" / "No"
 */
public final class Fmt {
    private Fmt() {}

    public static final int TEMP = 1, PERCENT = 2, MHZ = 3, MEM = 4, MEM_PAIR = 5, RATE = 6, DBM = 7, MM = 8,
            VOLT = 9, HOURS = 10, HZ = 11, DEG = 12, GHZ = 13, INT = 14, XYZ = 15, FRACTION = 16, RES = 17,
            SIGNAL = 18, TEXT = 19, DURATION = 20, ON_OFF = 21, YES_NO = 22, XYZ_G = 23;

    private static final double KB_PER_MB = 1024, KB_PER_GB = 1024 * 1024;

    /** Formats the sample's current state (the caller holds the registry lock). */
    public static void format(StringBuilder sb, Sample s, Words w) {
        value(sb, s.format, s.value, s.value2, s.value3, s.flags, s.text, w);
    }

    /** Appends one value of {@code format}. */
    public static void value(StringBuilder sb, int format, double v, double v2, double v3, int flags, String text,
                             Words w) {
        switch (format) {
            case TEMP: sb.append(Math.round(v)).append(w.get(Words.C)); break;
            case PERCENT: sb.append(Math.round(clamp(v, 0, 100))).append(w.get(Words.PCT)); break;
            case MHZ: sb.append(Math.round(v)).append(w.get(Words.MHZ)); break;
            case MEM:
                if (v >= KB_PER_GB) {
                    fixed(sb, v / KB_PER_GB, 1, w.dec);
                    sb.append(w.get(Words.GB));
                } else {
                    sb.append(Math.round(v / KB_PER_MB)).append(w.get(Words.MB));
                }
                break;
            case MEM_PAIR:
                if (v2 >= KB_PER_GB) {
                    fixed(sb, v / KB_PER_GB, 1, w.dec);
                    sb.append(" / ");
                    fixed(sb, v2 / KB_PER_GB, 1, w.dec);
                    sb.append(w.get(Words.GB));
                } else {
                    sb.append(Math.round(v / KB_PER_MB)).append(" / ").append(Math.round(v2 / KB_PER_MB))
                            .append(w.get(Words.MB));
                }
                break;
            case RATE: {
                double m = v / 1e6;
                if (m < 100) fixed(sb, m, 1, w.dec); else sb.append(Math.round(m));
                sb.append(w.get(Words.MBITS));
                break;
            }
            case DBM: sb.append(Math.round(v)).append(w.get(Words.DBM)); break;
            case MM: sb.append(Math.round(v)).append(w.get(Words.MM)); break;
            case VOLT: fixed(sb, v, 1, w.dec); sb.append(w.get(Words.V)); break;
            case HOURS: sb.append(Math.round(v)).append(w.get(Words.H)); break;
            case HZ: trim(sb, v, 2, w.dec); sb.append(w.get(Words.HZ)); break;
            case DEG: fixed(sb, v, 1, w.dec); sb.append(w.get(Words.DEG)); break;
            case GHZ: trim(sb, v, 1, w.dec); sb.append(w.get(Words.GHZ)); break;
            case INT: sb.append(Math.round(v)); break;
            case XYZ: sb.append(Math.round(v)).append(" / ").append(Math.round(v2)).append(" / ").append(Math.round(v3)); break;
            case XYZ_G:
                fixed(sb, v, 2, w.dec);
                sb.append(" / ");
                fixed(sb, v2, 2, w.dec);
                sb.append(" / ");
                fixed(sb, v3, 2, w.dec);
                sb.append(w.get(Words.G));
                break;
            case FRACTION:
                if (Math.round(v) == 0) sb.append(w.get(Words.OFF));
                else sb.append(Math.round(v)).append('/').append(Math.round(v2));
                break;
            case RES: sb.append(Math.round(v)).append('×').append(Math.round(v2)); break;
            case SIGNAL:
                sb.append(Math.round(v)).append('×').append(Math.round(v2)).append((flags & 1) != 0 ? 'i' : 'p');
                if (v3 > 0) {
                    sb.append(' ');
                    trim(sb, v3, 2, w.dec);
                    sb.append(w.get(Words.HZ));
                }
                break;
            case TEXT: if (text != null) sb.append(text); break;
            case DURATION: {
                long min = Math.max(0, (long) (v / 60));
                if (min < 60) {
                    sb.append(min).append(w.get(Words.MIN));
                } else {
                    sb.append(min / 60).append(w.get(Words.H)).append(' ');
                    long m = min % 60;
                    if (m < 10) sb.append('0');
                    sb.append(m).append(w.get(Words.MIN));
                }
                break;
            }
            case ON_OFF: sb.append(w.get(v != 0 ? Words.ON : Words.OFF)); break;
            case YES_NO: sb.append(w.get(v != 0 ? Words.YES : Words.NO)); break;
            default: break;
        }
    }

    /** Appends {@code v} with exactly {@code decimals} decimals (0..3). */
    public static void fixed(StringBuilder sb, double v, int decimals, char dec) {
        long pow = POW10[decimals];
        long scaled = Math.round(Math.abs(v) * pow);
        if (v < 0 && scaled != 0) sb.append('-');
        sb.append(scaled / pow);
        if (decimals > 0) {
            sb.append(dec);
            long frac = scaled % pow;
            for (long p = pow / 10; p > 1 && frac < p; p /= 10) sb.append('0');
            sb.append(frac);
        }
    }

    /** Appends {@code v} with up to {@code maxDecimals} decimals, trailing zeros dropped ("60", "23.98", "2.4"). */
    public static void trim(StringBuilder sb, double v, int maxDecimals, char dec) {
        long pow = POW10[maxDecimals];
        long scaled = Math.round(Math.abs(v) * pow);
        int d = maxDecimals;
        while (d > 0 && scaled % 10 == 0) {
            scaled /= 10;
            d--;
        }
        fixed(sb, (v < 0 ? -scaled : scaled) / (double) POW10[d], d, dec);
    }

    private static final long[] POW10 = {1, 10, 100, 1000};

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
