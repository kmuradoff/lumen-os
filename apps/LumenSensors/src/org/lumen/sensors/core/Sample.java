package org.lumen.sensors.core;

/**
 * One readable value of the HUD: identity (id, group, label, format, threshold kind, in which modes
 * it is shown) plus its current state. Plain Java: the label is a resource id that the UI resolves.
 *
 * Built once by a source (fluent setters before {@link Registry#add}), then only the state changes.
 * State fields are written by the sampler thread and read by the UI, both under {@link Registry#lock}:
 *  - {@link #available}: false hides the row (never shown: a value that cannot be read right now);
 *  - value / value2 / value3 / flags / text: the raw reading (meaning per {@link Fmt} format);
 *  - level, chars/len: filled by {@link Sampler} at every publish (Levels, Fmt), so the UI only copies;
 *  - hist: the graph history (one point per publish), only for {@link #MODE_GRAPH} samples.
 * No allocation after construction (chars and hist are fixed arrays).
 */
public final class Sample {
    /** Shown in the detailed panel (default). */
    public static final int MODE_DETAILED = 1;
    /** Shown in the compact strip (with a rank and a short label). */
    public static final int MODE_COMPACT = 2;
    /** Shown in the graphs mode (sparkline with min and max). */
    public static final int MODE_GRAPH = 4;

    /** Graph history: one point per publish; the view shows about 2 minutes of it. */
    public static final int HIST = 120;

    public final String id;
    public final int group;
    public final int format;

    public int order;
    public int labelRes;
    /** Format argument of the label ("Fan %1$d"), or -1. */
    public int labelArg = -1;
    /** Text format argument of the label ("LED current %1$s"), or null. */
    public String labelArgStr;
    public int shortRes;
    public int kind = Levels.K_NONE;
    public int modes = MODE_DETAILED;
    /** Compact strip priority, 1 = most important; 0 = not in the strip. */
    public int compactRank;

    /** Resolved by the UI on the main thread (labelRes, labelArg, shortRes). */
    public String label, shortLabel;

    // ---------------------------------------------------------------- state (Registry.lock)
    public boolean available;
    public double value = Double.NaN, value2, value3;
    public int flags;
    public String text;
    public int level;
    public final char[] chars = new char[48];
    public int len;
    /** Availability at the last publish (Sampler detects layout changes with it). */
    boolean shownAvailable;

    public float[] hist;
    public int histHead, histSize;

    public Sample(String id, int group, int format) {
        this.id = id;
        this.group = group;
        this.format = format;
    }

    // ---------------------------------------------------------------- construction (fluent)
    public Sample label(int res) { labelRes = res; return this; }

    public Sample label(int res, int arg) { labelRes = res; labelArg = arg; return this; }

    public Sample label(int res, String arg) { labelRes = res; labelArgStr = arg; return this; }

    public Sample order(int o) { order = o; return this; }

    public Sample kind(int k) { kind = k; return this; }

    /** Also in the compact strip at this rank, labelled with {@code shortLabelRes} (0 = the full label). */
    public Sample compact(int rank, int shortLabelRes) {
        compactRank = rank;
        shortRes = shortLabelRes;
        modes |= MODE_COMPACT;
        return this;
    }

    /** Only in the compact strip (not a detailed row). */
    public Sample compactOnly(int rank, int shortLabelRes) {
        compact(rank, shortLabelRes);
        modes = MODE_COMPACT;
        return this;
    }

    /** Also in the graphs mode. */
    public Sample graph() {
        modes |= MODE_GRAPH;
        if (hist == null) hist = new float[HIST];
        return this;
    }

    // ---------------------------------------------------------------- state (sampler thread, lock held)
    public void set(double v) {
        value = v;
        available = true;
    }

    public void set(double v, double v2) {
        value = v;
        value2 = v2;
        available = true;
    }

    public void set(double v, double v2, double v3) {
        value = v;
        value2 = v2;
        value3 = v3;
        available = true;
    }

    public void setText(String t) {
        text = t;
        available = t != null;
    }

    public void hide() {
        available = false;
    }

    /** Appends one graph point (oldest dropped). */
    void push(float v) {
        if (hist == null) return;
        hist[histHead] = v;
        histHead = (histHead + 1) % HIST;
        if (histSize < HIST) histSize++;
    }

    public void clearHistory() {
        histHead = 0;
        histSize = 0;
    }

    /** History point {@code i} of the last {@code n} (0 = oldest of them); n <= histSize. */
    public float histAt(int n, int i) {
        int idx = (histHead - n + i) % HIST;
        if (idx < 0) idx += HIST;
        return hist[idx];
    }
}
