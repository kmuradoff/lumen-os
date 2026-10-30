package org.lumen.projector.fit;

/**
 * Fit to my screen size: choosing the zoom position (docs/fit_screen/DESIGN.md section 6). Plain Java.
 *
 * Target box: screen minus the margin. For every position p the picture size comes from the geometry at TR(p)
 * with the SAME corners (the auto keystone warp stays in panel pixels while the lens moves).
 *   Inside the frame (default): the biggest picture (smallest p) whose OUTER box, grown by eps, fits the box.
 *   Closest size: the p with the smallest max(|w/Ws - 1|, |h/Hs - 1|) among those not larger than the box by more
 *     than eps (w, h = mean edges or the rectangle).
 *   Nothing fits: the smallest picture, "move the projector closer". Even the biggest picture is smaller than the
 *     box by more than one position step: the biggest picture, "move the projector further".
 * Distance advice (6.4): Dexact = D * min(Ws / w, Hs / h) at the chosen position; shown when it is 1 cm or more
 * away. That is the only way past the optical step of about 2.2 % (no digital zoom, owner decision O8).
 */
public final class FitPlanner {
    /**
     * Relative size uncertainty, 1 %: ToF noise +-3 mm at 2.45 m (0.1 %), tape +-0.5 cm on 220 cm (0.25 %), pitch
     * error 1 degree (about 0.8 % on the inscribed width), additive ToF offset after a 1 m change (unknown until
     * measurement M2, target below 0.3 %), gear slack repeatability (unknown until M6). Revisit after the device
     * test. A corner mismatch above 3 % (strong yaw change since the calibration) is added on top.
     */
    public static final double EPS = 0.01;
    public static final double MISMATCH_FREE = 0.03;
    /** Advice below this is not shown. */
    public static final double ADVICE_MIN_MM = 10;

    public enum Mode { INSIDE, CLOSEST }

    public enum Kind { FIT, MOVE_CLOSER, MOVE_FURTHER }

    public static final class Plan {
        public final int target;
        public final Kind kind;
        public final FitGeometry.Size[] sizes;
        public final double eps;
        /** The distance at which the chosen position fits exactly, and Dexact - D (positive = further). */
        public final double exactMm, adviceMm;

        Plan(int target, Kind kind, FitGeometry.Size[] sizes, double eps, double exactMm, double adviceMm) {
            this.target = target;
            this.kind = kind;
            this.sizes = sizes;
            this.eps = eps;
            this.exactMm = exactMm;
            this.adviceMm = adviceMm;
        }

        public FitGeometry.Size size() { return sizes[target]; }

        /** True when the advice is worth showing (1 cm or more, or a screen outside the zoom range). */
        public boolean showAdvice() { return kind != Kind.FIT || Math.abs(adviceMm) >= ADVICE_MIN_MM; }
    }

    private FitPlanner() {}

    public static Plan plan(FitGeometry g, FitGeometry.Estimator est, int[] corners, ThrowModel model,
                            ScreenSize screen, Mode mode) {
        return plan(g, est, corners, model, screen, mode, EPS);
    }

    public static Plan plan(FitGeometry g, FitGeometry.Estimator est, int[] corners, ThrowModel model,
                            ScreenSize screen, Mode mode, double eps0) {
        int n = model.positions();
        FitGeometry.Size[] s = new FitGeometry.Size[n];
        for (int p = 0; p < n; p++) s[p] = g.size(est, corners, model.tr(p));
        double eps = eps0 + Math.max(0, s[0].mismatch - MISMATCH_FREE);
        double ws = screen.targetW(), hs = screen.targetH();
        boolean inside = mode == Mode.INSIDE;
        int target = -1;
        if (inside) {
            for (int p = 0; p < n && target < 0; p++) {
                if (s[p].outerW * (1 + eps) <= ws && s[p].outerH * (1 + eps) <= hs) target = p;
            }
        } else {
            double best = Double.MAX_VALUE;
            for (int p = 0; p < n; p++) {
                if (s[p].w > ws * (1 + eps) || s[p].h > hs * (1 + eps)) continue;
                double err = Math.max(Math.abs(s[p].w / ws - 1), Math.abs(s[p].h / hs - 1));
                if (err < best) {
                    best = err;
                    target = p;
                }
            }
        }
        double d = g.distanceMm;
        if (target < 0) {                                   // the screen is smaller than the smallest picture
            int t = n - 1;
            double k = scale(s[t], ws, hs, inside);
            double exact = d * k / (1 + eps);
            return new Plan(t, Kind.MOVE_CLOSER, s, eps, exact, exact - d);
        }
        double k = scale(s[target], ws, hs, inside);
        double exact = d * k;
        if (target == 0 && n > 1) {
            double step = (inside ? s[0].outerW / s[1].outerW : s[0].w / s[1].w);
            if (k > step) return new Plan(0, Kind.MOVE_FURTHER, s, eps, exact, exact - d);
        }
        return new Plan(target, Kind.FIT, s, eps, exact, exact - d);
    }

    /** min(Ws / w, Hs / h) with the box of the mode. */
    private static double scale(FitGeometry.Size z, double ws, double hs, boolean inside) {
        return inside ? Math.min(ws / z.outerW, hs / z.outerH) : Math.min(ws / z.w, hs / z.h);
    }
}
