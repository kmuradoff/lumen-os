package org.lumen.sensors.core;

/**
 * Parsers for the kernel text files the generic sources read with pread into a reused byte buffer.
 * They work on (byte[], length) and write into caller arrays: no String, no allocation. Plain Java
 * (host tests).
 */
public final class Parse {
    private Parse() {}

    public static final long NONE = Long.MIN_VALUE;

    /** First (optionally negative) integer in b[0..len), or {@link #NONE}. */
    public static long firstLong(byte[] b, int len) {
        int i = 0;
        while (i < len && !isDigit(b[i]) && b[i] != '-') i++;
        return longAt(b, len, i);
    }

    /** Whitespace-separated integers of b[0..len) into out; returns how many were read. */
    public static int longs(byte[] b, int len, long[] out) {
        int n = 0, i = 0;
        while (i < len && n < out.length) {
            while (i < len && isSpace(b[i])) i++;
            if (i >= len) break;
            if (!isDigit(b[i]) && b[i] != '-') break;
            long v = longAt(b, len, i);
            if (v == NONE) break;
            out[n++] = v;
            while (i < len && !isSpace(b[i])) i++;
        }
        return n;
    }

    // ------------------------------------------------------------------ /proc/stat
    /**
     * Reads the "cpu" and "cpuN" lines of /proc/stat. Index 0 = all CPUs, index N + 1 = cpuN.
     * busy = user + nice + system + irq + softirq + steal, total = busy + idle + iowait (guest time is
     * already counted in user / nice, so it is not added again). Entries not found get total -1.
     * Returns 1 + the highest index found (0 = nothing parsed).
     */
    public static int procStat(byte[] b, int len, long[] busy, long[] total) {
        int max = Math.min(busy.length, total.length);
        for (int k = 0; k < max; k++) total[k] = -1;
        int found = 0;
        long[] f = FIELDS.get();
        int i = 0;
        while (i < len) {
            // line start
            if (i + 3 <= len && b[i] == 'c' && b[i + 1] == 'p' && b[i + 2] == 'u') {
                int j = i + 3;
                int idx;
                if (j < len && isDigit(b[j])) {
                    int n = 0;
                    while (j < len && isDigit(b[j])) n = n * 10 + (b[j++] - '0');
                    idx = n + 1;
                } else {
                    idx = 0;
                }
                int end = j;
                while (end < len && b[end] != '\n') end++;
                int nf = longsIn(b, j, end, f);
                if (idx < max && nf >= 4) {
                    long user = f[0], nice = f[1], sys = f[2], idle = f[3];
                    long iowait = nf > 4 ? f[4] : 0, irq = nf > 5 ? f[5] : 0, soft = nf > 6 ? f[6] : 0,
                            steal = nf > 7 ? f[7] : 0;
                    long b0 = user + nice + sys + irq + soft + steal;
                    busy[idx] = b0;
                    total[idx] = b0 + idle + iowait;
                    if (idx + 1 > found) found = idx + 1;
                }
                i = end + 1;
            } else {
                // the cpu lines come first; stop at the first other line ("intr" can be long)
                if (found > 0) break;
                while (i < len && b[i] != '\n') i++;
                i++;
            }
        }
        return found;
    }

    /** Busy percentage between two /proc/stat samples; -1 when the counters did not advance or went back. */
    public static double loadPercent(long busy0, long total0, long busy1, long total1) {
        long dt = total1 - total0, db = busy1 - busy0;
        if (total0 < 0 || total1 < 0 || dt <= 0 || db < 0) return -1;
        double p = 100.0 * db / dt;
        return p > 100 ? 100 : p;
    }

    // ------------------------------------------------------------------ /proc/meminfo
    public static final int MEM_TOTAL = 0, MEM_AVAILABLE = 1, MEM_FREE = 2, SWAP_TOTAL = 3, SWAP_FREE = 4;
    private static final byte[][] MEM_KEYS = {
            "MemTotal:".getBytes(), "MemAvailable:".getBytes(), "MemFree:".getBytes(),
            "SwapTotal:".getBytes(), "SwapFree:".getBytes()};

    /** kB values of MemTotal, MemAvailable, MemFree, SwapTotal, SwapFree into out[0..4] (-1 = missing). */
    public static int meminfo(byte[] b, int len, long[] out) {
        for (int k = 0; k < out.length; k++) out[k] = -1;
        int found = 0, i = 0;
        while (i < len) {
            for (int k = 0; k < MEM_KEYS.length && k < out.length; k++) {
                if (out[k] < 0 && startsWith(b, len, i, MEM_KEYS[k])) {
                    long v = firstLongFrom(b, len, i + MEM_KEYS[k].length);
                    if (v != NONE) {
                        out[k] = v;
                        found++;
                    }
                    break;
                }
            }
            while (i < len && b[i] != '\n') i++;
            i++;
        }
        return found;
    }

    // ------------------------------------------------------------------ thermal
    /**
     * A thermal_zone temp reading in degrees C, or NaN when it is not a plausible temperature.
     * Kernel thermal zones report millidegrees (58000); a few drivers report degrees (58) or
     * tenths (580). 1000 and above = millidegrees, 200..999 = tenths, below 200 = degrees. Values
     * at or below 0 or above 150 C are not plausible (absent or broken sensor) and are hidden.
     */
    public static double thermalCelsius(long raw) {
        if (raw == NONE) return Double.NaN;
        double c;
        if (raw >= 1000) c = raw / 1000.0;
        else if (raw >= 200) c = raw / 10.0;
        else c = raw;
        return (c > 0 && c <= 150) ? c : Double.NaN;
    }

    // ------------------------------------------------------------------ clocks and GPU
    /**
     * A clock module parameter or sysfs value in MHz, or NaN. The unit is not documented for the Mali
     * module parameter (research: "probably MHz"), so it is taken from the size: 10^7 and above = Hz,
     * 10^4 and above = kHz, else MHz. Above 5000 MHz is not plausible.
     */
    public static double clockMhz(long raw) {
        if (raw == NONE || raw < 0) return Double.NaN;
        double m;
        if (raw >= 10_000_000L) m = raw / 1e6;
        else if (raw >= 10_000L) m = raw / 1e3;
        else m = raw;
        return m <= 5000 ? m : Double.NaN;
    }

    /**
     * GPU utilisation module parameter as a percentage, or NaN. The scale is not documented (0..100 or
     * 0..255): the caller passes {@code scale255} once any reading above 100 was seen.
     */
    public static double gpuPercent(long raw, boolean scale255) {
        if (raw == NONE || raw < 0 || raw > 255) return Double.NaN;
        if (!scale255 && raw > 100) return Double.NaN;
        return scale255 ? raw * 100.0 / 255.0 : raw;
    }

    // ------------------------------------------------------------------ helpers
    private static final ThreadLocal<long[]> FIELDS = ThreadLocal.withInitial(() -> new long[10]);

    private static int longsIn(byte[] b, int from, int to, long[] out) {
        int n = 0, i = from;
        while (i < to && n < out.length) {
            while (i < to && isSpace(b[i])) i++;
            if (i >= to || !isDigit(b[i])) break;
            long v = 0;
            while (i < to && isDigit(b[i])) v = v * 10 + (b[i++] - '0');
            out[n++] = v;
        }
        return n;
    }

    private static long firstLongFrom(byte[] b, int len, int i) {
        while (i < len && b[i] != '\n' && !isDigit(b[i]) && b[i] != '-') i++;
        if (i >= len || b[i] == '\n') return NONE;
        return longAt(b, len, i);
    }

    private static long longAt(byte[] b, int len, int i) {
        boolean neg = false;
        if (i < len && b[i] == '-') {
            neg = true;
            i++;
        }
        if (i >= len || !isDigit(b[i])) return NONE;
        long v = 0;
        while (i < len && isDigit(b[i])) v = v * 10 + (b[i++] - '0');
        return neg ? -v : v;
    }

    private static boolean startsWith(byte[] b, int len, int at, byte[] key) {
        if (at + key.length > len) return false;
        for (int k = 0; k < key.length; k++) if (b[at + k] != key[k]) return false;
        return true;
    }

    private static boolean isDigit(byte c) { return c >= '0' && c <= '9'; }

    private static boolean isSpace(byte c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
}
