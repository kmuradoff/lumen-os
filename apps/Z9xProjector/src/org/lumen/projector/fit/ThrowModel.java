package org.lumen.projector.fit;

import java.util.Arrays;
import java.util.function.DoubleUnaryOperator;

/**
 * Fit to my screen size: throw ratio TR(p) per zoom position (docs/fit_screen/DESIGN.md sections 5 and 8).
 * Plain Java. No vendor file has these numbers (RESEARCH 2.1, 2.5), so they come from the one-time calibration:
 * the user measures the picture width with a tape at some positions, each one is solved to a TR with the same
 * estimator the fit uses ({@link #solve}), and the model interpolates the rest:
 *   two points a, b (0 and 13): ln(1/TR(p)) = ln(1/TR(a)) + (m(p) - m(a)) / (m(b) - m(a)) * ln(TR(a) / TR(b))
 *     (the picture width is geometric in motor steps, m = {@link ZoomModel#motor});
 *   more points: the same line through 0 and 13, every other measured point is a check (1 % at most, else the
 *     full table is needed: the vendor size curve y = -0.1165 x^0.772 + 105.5 is curved, RESEARCH 2.5);
 *   all 14 points: the table as it is.
 * Plausibility (refused otherwise): 0.6 <= TR <= 2.0, TR(13) / TR(0) in 1.20..1.45 (vendor ratios 1.30 / 1.32 /
 * 1.38), TR strictly growing with p.
 */
public final class ThrowModel {
    public static final double TR_MIN = 0.6, TR_MAX = 2.0, RATIO_MIN = 1.20, RATIO_MAX = 1.45;
    public static final double CHECK_TOLERANCE = 0.01;
    /** Bisection range of {@link #solve}. */
    public static final double SOLVE_MIN = 0.5, SOLVE_MAX = 3.0;

    public enum Kind { TWO, THREE, FULL }

    public enum Problem { NONE, TOO_FEW, TR_RANGE, RATIO, NOT_INCREASING, FULL_TABLE_NEEDED }

    /** Result of {@link #build}: a model, or the reason it was refused. */
    public static final class Built {
        public final ThrowModel model;
        public final Problem problem;
        /** The worst check point (-1 = none): its predicted and measured TR. */
        public final int checkPos;
        public final double predicted, measured;

        Built(ThrowModel m, Problem p, int checkPos, double predicted, double measured) {
            model = m;
            problem = p;
            this.checkPos = checkPos;
            this.predicted = predicted;
            this.measured = measured;
        }

        public boolean ok() { return problem == Problem.NONE; }
    }

    private final double[] tr;
    private final double[][] points;
    private final Kind kind;

    private ThrowModel(double[] tr, double[][] points, Kind kind) {
        this.tr = tr;
        this.points = points;
        this.kind = kind;
    }

    public double tr(int p) {
        if (p < 0 || p >= tr.length) throw new IllegalArgumentException("position " + p);
        return tr[p];
    }

    public int positions() { return tr.length; }

    public Kind kind() { return kind; }

    /** The measured points {p, TR}, sorted by p (a copy). */
    public double[][] points() {
        double[][] o = new double[points.length][];
        for (int i = 0; i < points.length; i++) o[i] = points[i].clone();
        return o;
    }

    /** Two-point model through (a, trA) and (b, trB). */
    static double[] line(int a, double trA, int b, double trB) {
        double[] t = new double[ZoomModel.POSITIONS];
        double ma = ZoomModel.motor(a), mb = ZoomModel.motor(b);
        for (int p = 0; p < t.length; p++) {
            double f = (ZoomModel.motor(p) - ma) / (mb - ma);
            t[p] = Math.exp(-(Math.log(1 / trA) + f * Math.log(trA / trB)));
        }
        t[a] = trA;
        t[b] = trB;
        return t;
    }

    /** points: {p, TR} pairs, distinct positions 0..13. */
    public static Built build(double[][] points) {
        if (points == null || points.length < 2) return new Built(null, Problem.TOO_FEW, -1, 0, 0);
        double[][] pts = new double[points.length][];
        for (int i = 0; i < points.length; i++) pts[i] = points[i].clone();
        Arrays.sort(pts, (x, y) -> Double.compare(x[0], y[0]));
        double[] measured = new double[ZoomModel.POSITIONS];
        Arrays.fill(measured, Double.NaN);
        for (double[] q : pts) {
            int p = (int) q[0];
            if (q.length != 2 || p != q[0] || p < 0 || p >= ZoomModel.POSITIONS || !Double.isNaN(measured[p])) {
                return new Built(null, Problem.TOO_FEW, -1, 0, 0);
            }
            if (!(q[1] >= TR_MIN && q[1] <= TR_MAX)) return new Built(null, Problem.TR_RANGE, p, 0, q[1]);
            measured[p] = q[1];
        }
        for (int i = 1; i < pts.length; i++) {
            if (!(pts[i][1] > pts[i - 1][1])) return new Built(null, Problem.NOT_INCREASING, (int) pts[i][0], 0, pts[i][1]);
        }
        int last = ZoomModel.POSITIONS - 1;
        if (Double.isNaN(measured[0]) || Double.isNaN(measured[last])) return new Built(null, Problem.TOO_FEW, -1, 0, 0);
        double ratio = measured[last] / measured[0];
        if (!(ratio >= RATIO_MIN && ratio <= RATIO_MAX)) return new Built(null, Problem.RATIO, last, 0, ratio);
        if (pts.length == ZoomModel.POSITIONS) {
            return new Built(new ThrowModel(measured, pts, Kind.FULL), Problem.NONE, -1, 0, 0);
        }
        double[] t = line(0, measured[0], last, measured[last]);
        int worst = -1;
        double worstErr = 0;
        for (double[] q : pts) {
            int p = (int) q[0];
            double err = Math.abs(t[p] / q[1] - 1);
            if (p != 0 && p != last && err > worstErr) {
                worstErr = err;
                worst = p;
            }
        }
        if (worst >= 0 && worstErr > CHECK_TOLERANCE) {
            return new Built(null, Problem.FULL_TABLE_NEEDED, worst, t[worst], measured[worst]);
        }
        Kind k = pts.length == 2 ? Kind.TWO : Kind.THREE;
        return new Built(new ThrowModel(t, pts, k), Problem.NONE, worst, worst >= 0 ? t[worst] : 0,
                worst >= 0 ? measured[worst] : 0);
    }

    /**
     * The TR at which {@code widthOfTr} (picture width in mm, falling with TR) equals {@code measuredMm}; NaN when
     * the width is outside what TR 0.5..3.0 can give. Bisection, 60 rounds.
     */
    public static double solve(DoubleUnaryOperator widthOfTr, double measuredMm) {
        double lo = SOLVE_MIN, hi = SOLVE_MAX;
        double wLo = widthOfTr.applyAsDouble(lo), wHi = widthOfTr.applyAsDouble(hi);
        if (!(measuredMm <= wLo && measuredMm >= wHi)) return Double.NaN;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (widthOfTr.applyAsDouble(mid) > measuredMm) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /**
     * Shipped default per model (docs/fit_screen/DESIGN.md 10, owner decision O6): empty until the owner's own
     * calibration is reviewed. modelId -> {p, TR} points.
     */
    public static double[][] defaultPoints(String modelId) {
        return null;
    }
}
