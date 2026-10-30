package org.lumen.projector.fit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fit to my screen size: the one-time calibration (docs/fit_screen/DESIGN.md section 8). Plain Java, driven by
 * the calibration screen on a worker thread:
 *   prepare()                 the run's steps 1-6 (distance, pitch, auto keystone, lens home, corners)
 *   position()                the position to measure now (starts at 0, the biggest picture)
 *   predictedWidthMm()        start value of the tape input (the stored model, or TR 1.0)
 *   record(widthMm)           the tape width of the white frame; solved to a TR with the fit's own estimator
 *   next()                    walks to the next position with "smaller" moves only (the gear slack stays on the
 *                             same side as at fit time), then the autofocus
 *   finish()                  the model, or why it is refused (ThrowModel.build)
 * Positions: TWO = 0 and 13; THREE = 0, 7 (check) and 13; FULL = all 14.
 */
public final class FitCalibration {
    public enum Kind { TWO, THREE, FULL }

    private final FitRun run;
    private final int[] plan;
    private final List<double[]> points = new ArrayList<>();
    private int index;

    public FitCalibration(FitRun run, Kind kind) {
        this.run = run;
        this.plan = positions(kind);
    }

    public static int[] positions(Kind k) {
        int last = ZoomModel.POSITIONS - 1;
        if (k == Kind.TWO) return new int[] {0, last};
        if (k == Kind.THREE) return new int[] {0, 7, last};
        int[] a = new int[ZoomModel.POSITIONS];
        for (int i = 0; i < a.length; i++) a[i] = i;
        return a;
    }

    public FitRun.Result prepare() { return run.prepare(); }

    public FitRun.Prepared prepared() { return run.prepared(); }

    /** Position to measure now, -1 when every position is measured. */
    public int position() { return index < plan.length ? plan[index] : -1; }

    public int step() { return index; }

    public int steps() { return plan.length; }

    /** The picture width the fit would predict at the current position (prior model, or TR 1.0). */
    public double predictedWidthMm(ThrowModel prior) {
        FitRun.Prepared p = run.prepared();
        int pos = Math.max(0, position());
        double tr = prior != null ? prior.tr(pos) : 1.0;
        return p.geometry.size(p.estimator, p.corners, tr).w;
    }

    /** TR for a measured width at the current position (NaN if no TR 0.5..3.0 gives it); kept for finish(). */
    public double record(double widthMm) {
        FitRun.Prepared p = run.prepared();
        double tr = solve(p, widthMm);
        if (!Double.isNaN(tr) && position() >= 0) points.add(new double[] {position(), tr});
        return tr;
    }

    static double solve(FitRun.Prepared p, double widthMm) {
        return ThrowModel.solve(t -> p.geometry.size(p.estimator, p.corners, t).w, widthMm);
    }

    /** Walks to the next planned position and focuses; null = there, else the stopping result. */
    public FitRun.Result next() {
        index++;
        if (index >= plan.length) return null;
        FitRun.Result r = run.walkSmallerTo(plan[index]);
        if (r == null) run.focus();
        return r;
    }

    public boolean done() { return index >= plan.length - 1 && points.size() == plan.length; }

    public List<double[]> points() { return points; }

    public ThrowModel.Built finish() {
        return ThrowModel.build(points.toArray(new double[0][]));
    }
}
