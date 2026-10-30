package org.lumen.projector.fit;

/**
 * Fit to my screen size: the screen the user typed in (docs/fit_screen/DESIGN.md 1.2 and 6.1). Plain Java.
 * Width and height in mm, from width x height or from a diagonal (inches or cm) plus an aspect; a margin per side.
 */
public final class ScreenSize {
    /** Aspect choices, in the order of the UI list. */
    public static final String[] ASPECT_LABELS = {"16:9", "16:10", "4:3", "2.35:1"};
    private static final double[][] ASPECTS = {{16, 9}, {16, 10}, {4, 3}, {2.35, 1}};
    public static final double MIN_MM = 500, MAX_MM = 5000, MAX_MARGIN_MM = 100;
    public static final double MM_PER_INCH = 25.4;

    public final double wMm, hMm, marginMm;

    private ScreenSize(double w, double h, double margin) {
        if (!(w >= MIN_MM && w <= MAX_MM)) throw new IllegalArgumentException("width " + w + " mm");
        if (!(h >= MIN_MM && h <= MAX_MM)) throw new IllegalArgumentException("height " + h + " mm");
        if (!(margin >= 0 && margin <= MAX_MARGIN_MM)) throw new IllegalArgumentException("margin " + margin + " mm");
        if (2 * margin >= Math.min(w, h)) throw new IllegalArgumentException("margin larger than the screen");
        wMm = w;
        hMm = h;
        this.marginMm = margin;
    }

    public static ScreenSize fromWidthHeight(double wMm, double hMm, double marginMm) {
        return new ScreenSize(wMm, hMm, marginMm);
    }

    /** Diagonal in inches (inches true) or in cm, aspect index into {@link #ASPECT_LABELS}. */
    public static ScreenSize fromDiagonal(double diagonal, boolean inches, int aspect, double marginMm) {
        if (aspect < 0 || aspect >= ASPECTS.length) throw new IllegalArgumentException("aspect " + aspect);
        double d = inches ? diagonal * MM_PER_INCH : diagonal * 10;
        double a = ASPECTS[aspect][0], b = ASPECTS[aspect][1], k = d / Math.hypot(a, b);
        return new ScreenSize(a * k, b * k, marginMm);
    }

    /** The box the picture must fit into: screen minus the margin on every side. */
    public double targetW() { return wMm - 2 * marginMm; }

    public double targetH() { return hMm - 2 * marginMm; }

    /** Diagonal in inches of the whole screen. */
    public double diagonalInches() { return Math.hypot(wMm, hMm) / MM_PER_INCH; }

    @Override public String toString() {
        return String.format(java.util.Locale.ROOT, "%.1f x %.1f mm, margin %.1f", wMm, hMm, marginMm);
    }
}
