package org.lumen.projector.fit;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test of the fit core (docs/fit_screen/DESIGN.md section 11, tests 1-33): sh test/fit/run.sh.
 * Lengths in mm, angles in degrees. TR values are FIXTURES (test inputs), not device values: the device values
 * come from the calibration.
 */
public final class FitTest {
    private static int checks, failures;

    static void near(String what, double want, double got, double tol) {
        checks++;
        if (!(Math.abs(want - got) <= tol)) {
            failures++;
            System.out.println("FAIL " + what + ": want " + want + " +-" + tol + ", got " + got);
        }
    }

    static void is(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    static void eq(String what, Object want, Object got) {
        is(what + " (want " + want + ", got " + got + ")", want == null ? got == null : want.equals(got));
    }

    static final double MM = 0.5;
    /** The owner's logged AK corners (RESEARCH 3.3, LOG2:12891-12894, ceiling front, pitch 4.06). */
    static final int[] LOGGED = {106, 23, 3683, 89, 3725, 2055, 68, 2057};
    static final double[] TABLE15 = new double[14];

    static ThrowModel fixtureModel() {
        ThrowModel.Built b = ThrowModel.build(new double[][] {{0, 0.900}, {13, 1.188}});
        is("fixture model builds", b.ok());
        return b.model;
    }

    public static void main(String[] args) {
        screenSizes();
        trapezoid();
        corners();
        throwModel();
        planner();
        run();
        store();
        System.out.println("fit: " + checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ 1-2 screen sizes
    static void screenSizes() {
        ScreenSize s = ScreenSize.fromDiagonal(100, true, 0, 0);
        near("1 100in 16:9 w", 2213.8, s.wMm, 0.05);
        near("1 100in 16:9 h", 1245.3, s.hMm, 0.05);
        s = ScreenSize.fromDiagonal(120, true, 1, 0);
        near("1 120in 16:10 w", 2584.7, s.wMm, 0.05);
        near("1 120in 16:10 h", 1615.4, s.hMm, 0.05);
        s = ScreenSize.fromDiagonal(100, true, 2, 0);
        near("1 100in 4:3 w", 2032.0, s.wMm, 0.05);
        near("1 100in 4:3 h", 1524.0, s.hMm, 0.05);
        s = ScreenSize.fromDiagonal(80, true, 0, 0);
        near("1 80in w", 1771.0, s.wMm, 0.05);
        near("1 80in h", 996.2, s.hMm, 0.05);
        s = ScreenSize.fromDiagonal(150, true, 0, 0);
        near("1 150in w", 3320.7, s.wMm, 0.05);
        near("1 150in h", 1867.9, s.hMm, 0.05);
        s = ScreenSize.fromDiagonal(110, true, 3, 0);
        near("1 110in 2.35 w", 2570.9, s.wMm, 0.05);
        near("1 110in 2.35 h", 1094.0, s.hMm, 0.05);
        s = ScreenSize.fromDiagonal(254, false, 0, 0);
        near("2 254 cm w", 2213.8, s.wMm, 0.05);
        s = ScreenSize.fromWidthHeight(2210, 1245, 0);
        near("2 wh kept", 2210, s.wMm, 1e-9);
        near("2 wh kept h", 1245, s.hMm, 1e-9);
        s = ScreenSize.fromDiagonal(100, true, 0, 20);
        near("2 margin box w", 2173.8, s.targetW(), 0.05);
        near("2 margin box h", 1205.3, s.targetH(), 0.05);
        is("2 width < 500 refused", refused(() -> ScreenSize.fromWidthHeight(499, 600, 0)));
        is("2 width > 5000 refused", refused(() -> ScreenSize.fromWidthHeight(5001, 2000, 0)));
        is("2 margin > 100 refused", refused(() -> ScreenSize.fromWidthHeight(2000, 1100, 101)));
    }

    static boolean refused(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    // ------------------------------------------------------------------ 3-9 trapezoid
    static void trapezoid() {
        FitGeometry g = new FitGeometry(2400, 0);
        double[] t = g.trapezoid(1.20);
        near("3 level Wt", 2000.0, t[0], MM);
        near("3 level Wb", 2000.0, t[1], MM);
        near("3 level H", 1125.0, t[2], MM);
        double[] in = g.inscribed(1.20);
        near("3 level inscribed w", 2000.0, in[0], MM);
        near("3 level inscribed h", 1125.0, in[1], MM);

        g = new FitGeometry(2450, 4.06);
        t = g.trapezoid(1.20);
        near("4 Wt", 2046.8, t[0], MM);
        near("4 Wb", 2117.2, t[1], MM);
        near("4 H", 1193.9, t[2], MM);
        near("4 Wt/Wb", 0.9667, t[0] / t[1], 0.0001);
        in = g.inscribed(1.20);
        near("4 inscribed w", 2049.2, in[0], MM);
        near("4 inscribed h", 1152.7, in[1], MM);

        t = g.trapezoid(0.90);
        near("5 Wt", 2729.1, t[0], MM);
        near("5 Wb", 2855.8, t[1], MM);
        near("5 H", 1610.4, t[2], MM);
        near("5 ratio", 0.9556, t[0] / t[1], 0.0001);
        in = g.inscribed(0.90);
        near("5 inscribed w", 2734.7, in[0], MM);
        near("5 inscribed h", 1538.3, in[1], MM);

        t = g.trapezoid(1.188);
        near("6 Wt", 2067.5, t[0], MM);
        near("6 Wb", 2139.4, t[1], MM);
        near("6 H", 1206.4, t[2], MM);
        in = g.inscribed(1.188);
        near("6 inscribed w", 2070.0, in[0], MM);
        near("6 inscribed h", 1164.4, in[1], MM);

        FitGeometry steep = new FitGeometry(3000, 10);
        t = steep.trapezoid(1.20);
        near("7 Wt", 2538.6, t[0], MM);
        near("7 Wb", 2767.3, t[1], MM);
        near("7 H", 1580.6, t[2], MM);
        near("7 ratio", 0.9173, t[0] / t[1], 0.0001);
        in = steep.inscribed(1.20);
        near("7 inscribed w", 2559.0, in[0], MM);
        near("7 inscribed h", 1439.4, in[1], MM);

        double[][] want = {{-1058.6, 1367.8}, {1058.6, 1367.8}, {1023.4, 173.9}, {-1023.4, 173.9}};
        int[][] px = {{0, 0}, {3840, 0}, {3840, 2160}, {0, 2160}};
        for (int i = 0; i < 4; i++) {
            double[] p = g.map(px[i][0], px[i][1], 1.20);
            near("8 map x " + i, want[i][0], p[0], MM);
            near("8 map y " + i, want[i][1], p[1], MM);
        }
        is("8 ceiling: the lens-side (top) edge is narrower than the far edge", t(g, 1.20)[0] < t(g, 1.20)[1]);

        for (double tr : new double[] {0.8, 1.0, 1.3, 1.7}) {
            double[] lv = new FitGeometry(2000, 0).trapezoid(tr);
            near("9 level Wt == Wb at TR " + tr, lv[0], lv[1], 1e-6);
        }
        double[] neg = new FitGeometry(2450, -4.06).trapezoid(1.2);
        is("9 negative tilt: the lens-side edge is the wider one", neg[0] > neg[1]);
    }

    static double[] t(FitGeometry g, double tr) { return g.trapezoid(tr); }

    // ------------------------------------------------------------------ 10-13 corner estimator
    static void corners() {
        FitGeometry g = new FitGeometry(2450, 4.06);
        FitGeometry.Quad q = g.quad(LOGGED, 1.20);
        near("10 far/near", 1.0093, q.farNearRatio(), 0.0005);
        near("10 mean w", 1961.4, q.meanW, MM);
        near("10 mean h", 1106.3, q.meanH, MM);
        near("10 outer w", 1970.5, q.outerW, MM);
        near("10 outer h", 1125.7, q.outerH, MM);
        near("10 inner w", 1952.4, q.innerW, MM);
        near("10 inner h", 1086.9, q.innerH, MM);
        near("10 mismatch", 0.0175, q.mismatch, 0.0005);
        is("10 keystone on", !q.keystoneOff);

        q = g.quad(LOGGED, 0.90);
        near("11 far/near", 1.0201, q.farNearRatio(), 0.0005);
        near("11 mean w", 2630.8, q.meanW, MM);
        near("11 mean h", 1492.5, q.meanH, MM);
        near("11 outer w", 2657.0, q.outerW, MM);
        near("11 outer h", 1518.9, q.outerH, MM);
        near("11 inner w", 2604.6, q.innerW, MM);
        near("11 inner h", 1466.1, q.innerH, MM);

        q = g.quad(LOGGED, 1.30);
        near("12 far/near near the vendor's TR 1.30", 1.0068, q.farNearRatio(), 0.0005);

        int[] full = {0, 0, 3839, 0, 3839, 2159, 0, 2159};
        q = g.quad(full, 1.20);
        is("13 full frame = keystone off", q.keystoneOff);
        eq("13 estimator", FitGeometry.Estimator.FULL_PANEL, FitGeometry.estimatorFor(full));
        double[] tz = g.trapezoid(1.20);
        near("13 outer w = Wb", tz[1], q.outerW, MM);
        near("13 outer h = H", tz[2], q.outerH, MM);
        eq("13 null corners", FitGeometry.Estimator.INSCRIBED, FitGeometry.estimatorFor(null));
        eq("13 logged corners", FitGeometry.Estimator.CORNERS, FitGeometry.estimatorFor(LOGGED));
    }

    // ------------------------------------------------------------------ 14-17 throw model
    static void throwModel() {
        near("14 m0", 0, ZoomModel.motor(0), 0);
        near("14 m1", 129, ZoomModel.motor(1), 0);
        near("14 m2", 289, ZoomModel.motor(2), 0);
        near("14 m7", 1089, ZoomModel.motor(7), 0);
        near("14 m13", 2049, ZoomModel.motor(13), 0);

        ThrowModel m = fixtureModel();
        double[] want = {0.900, 0.9159, 0.9359, Double.NaN, 0.9774, Double.NaN, Double.NaN, 1.0431, Double.NaN,
                Double.NaN, 1.1132, Double.NaN, 1.1625, 1.188};
        for (int p = 0; p < 14; p++) {
            TABLE15[p] = m.tr(p);
            if (!Double.isNaN(want[p])) near("15 TR p" + p, want[p], m.tr(p), p == 0 || p == 13 ? 1e-12 : 0.0005);
        }
        eq("15 kind", ThrowModel.Kind.TWO, m.kind());

        FitGeometry g = new FitGeometry(2450, 4.06);
        double tr = ThrowModel.solve(x -> g.quad(LOGGED, x).meanW, 2630.8);
        near("16 solve mean width 2630.8", 0.9000, tr, 0.0005);
        near("16 solve inscribed 2734.7", 0.9000, ThrowModel.solve(x -> g.inscribed(x)[0], 2734.7), 0.0005);
        near("16 solve inscribed 2070.0", 1.1880, ThrowModel.solve(x -> g.inscribed(x)[0], 2070.0), 0.0005);
        FitGeometry lv = new FitGeometry(2400, 0);
        near("16 solve level 2000", 1.2000, ThrowModel.solve(x -> lv.inscribed(x)[0], 2000), 0.0005);
        for (int p = 0; p < 14; p++) {
            double w = g.quad(LOGGED, m.tr(p)).meanW;
            near("16 round trip p" + p, m.tr(p), ThrowModel.solve(x -> g.quad(LOGGED, x).meanW, w), 1e-6);
        }
        is("16 width out of range -> NaN", Double.isNaN(ThrowModel.solve(x -> g.inscribed(x)[0], 99999)));

        eq("17 TR 0.55", ThrowModel.Problem.TR_RANGE, ThrowModel.build(new double[][] {{0, 0.55}, {13, 0.70}}).problem);
        eq("17 ratio 1.10", ThrowModel.Problem.RATIO, ThrowModel.build(new double[][] {{0, 1.0}, {13, 1.10}}).problem);
        double[][] full = new double[14][];
        for (int p = 0; p < 14; p++) full[p] = new double[] {p, m.tr(p)};
        full[5][1] = full[4][1];
        eq("17 non-increasing table", ThrowModel.Problem.NOT_INCREASING, ThrowModel.build(full).problem);
        full[5][1] = m.tr(5);
        ThrowModel.Built fb = ThrowModel.build(full);
        eq("17 full table", ThrowModel.Kind.FULL, fb.ok() ? fb.model.kind() : null);
        double mid = m.tr(7);
        ThrowModel.Built off = ThrowModel.build(new double[][] {{0, 0.9}, {7, mid * 1.015}, {13, 1.188}});
        eq("17 middle off by 1.5 %", ThrowModel.Problem.FULL_TABLE_NEEDED, off.problem);
        ThrowModel.Built okb = ThrowModel.build(new double[][] {{0, 0.9}, {7, mid * 1.004}, {13, 1.188}});
        is("17 middle off by 0.4 % accepted", okb.ok() && okb.model.kind() == ThrowModel.Kind.THREE);
        near("17 middle predicted", mid, okb.predicted, 1e-9);
    }

    // ------------------------------------------------------------------ 18-25 planner
    static void planner() {
        ThrowModel m = fixtureModel();
        FitGeometry g = new FitGeometry(2450, 4.06);
        ScreenSize s100 = ScreenSize.fromDiagonal(100, true, 0, 0);
        FitPlanner.Plan p = FitPlanner.plan(g, FitGeometry.Estimator.INSCRIBED, null, m, s100, FitPlanner.Mode.INSIDE);
        near("18 p0 w", 2735, p.sizes[0].w, 1);
        near("18 p0 h", 1538, p.sizes[0].h, 1);
        near("18 p9 w", 2258, p.sizes[9].w, 1);
        near("18 p9 h", 1270, p.sizes[9].h, 1);
        near("18 p10 w", 2209, p.sizes[10].w, 1);
        near("18 p10 h", 1243, p.sizes[10].h, 1);
        near("18 p11 w", 2162, p.sizes[11].w, 1);
        near("18 p11 h", 1216, p.sizes[11].h, 1);
        near("18 p13 w", 2070, p.sizes[13].w, 1);
        near("18 p13 h", 1164, p.sizes[13].h, 1);
        eq("18 target", 11, p.target);
        eq("18 kind", FitPlanner.Kind.FIT, p.kind);
        is("18 p10 fits without the budget only", p.sizes[10].w <= s100.targetW() && p.sizes[10].w * 1.01 > s100.targetW());
        near("18 Dexact", 2509, p.exactMm, 1);
        is("18 advice shown, about 6 cm further", p.showAdvice() && Math.round(p.adviceMm / 10) == 6);

        p = FitPlanner.plan(g, FitGeometry.Estimator.INSCRIBED, null, m, s100, FitPlanner.Mode.CLOSEST);
        eq("19 closest target", 10, p.target);
        near("19 Dexact", 2455, p.exactMm, 1);
        is("19 no advice under 1 cm", !p.showAdvice());
        near("19 gap left/right", 2.4, (s100.wMm - p.size().w) / 2, 0.5);
        near("19 gap top/bottom", 1.1, (s100.hMm - p.size().h) / 2, 0.5);

        ScreenSize m20 = ScreenSize.fromDiagonal(100, true, 0, 20);
        eq("20 margin 20 mm", 12, FitPlanner.plan(g, FitGeometry.Estimator.INSCRIBED, null, m, m20, FitPlanner.Mode.INSIDE).target);

        FitGeometry level = new FitGeometry(2450, 0);
        p = FitPlanner.plan(level, FitGeometry.Estimator.INSCRIBED, null, m, s100, FitPlanner.Mode.INSIDE);
        near("21 level p10 w", 2201, p.sizes[10].w, 1);
        near("21 level p11 w", 2154, p.sizes[11].w, 1);
        eq("21 level target", 11, p.target);

        p = FitPlanner.plan(g, FitGeometry.Estimator.CORNERS, LOGGED, m, s100, FitPlanner.Mode.INSIDE);
        near("22 p9 outer w", 2178, p.sizes[9].outerW, 1);
        near("22 p9 outer h", 1245, p.sizes[9].outerH, 1);
        is("22 p9 fails on height", p.sizes[9].outerH * 1.01 > s100.hMm);
        near("22 p10 outer w", 2130, p.sizes[10].outerW, 1);
        near("22 p10 outer h", 1217, p.sizes[10].outerH, 1);
        eq("22 corners target", 10, p.target);
        near("22 report w", 2117, p.sizes[10].w, 1);
        near("22 report h", 1196, p.sizes[10].h, 1);

        ScreenSize s80 = ScreenSize.fromDiagonal(80, true, 0, 0);
        p = FitPlanner.plan(g, FitGeometry.Estimator.INSCRIBED, null, m, s80, FitPlanner.Mode.INSIDE);
        eq("23 80in target", 13, p.target);
        eq("23 80in kind", FitPlanner.Kind.MOVE_CLOSER, p.kind);
        near("23 exact", 2075, p.exactMm, 1);
        near("23 closer by 37.5 cm", -374.7, p.adviceMm, 1);

        ScreenSize s150 = ScreenSize.fromDiagonal(150, true, 0, 0);
        p = FitPlanner.plan(g, FitGeometry.Estimator.INSCRIBED, null, m, s150, FitPlanner.Mode.INSIDE);
        eq("24 150in target", 0, p.target);
        eq("24 150in kind", FitPlanner.Kind.MOVE_FURTHER, p.kind);
        near("24 exact", 2975, p.exactMm, 1);
        near("24 further by 52.5 cm", 525, p.adviceMm, 1.5);

        ScreenSize s235 = ScreenSize.fromDiagonal(110, true, 3, 0);
        p = FitPlanner.plan(g, FitGeometry.Estimator.INSCRIBED, null, m, s235, FitPlanner.Mode.INSIDE);
        eq("25 2.35:1 target", 13, p.target);
        eq("25 2.35:1 kind", FitPlanner.Kind.MOVE_CLOSER, p.kind);
        near("25 2.35:1 exact", 2279, p.exactMm, 1);
    }

    // ------------------------------------------------------------------ 26-33 run state machine
    /** Records every call; scripted values. */
    static final class FakeDevice implements FitDevice {
        final List<String> calls = new ArrayList<>();
        boolean supported = true;
        int positions = 14, mount = 1;
        int[] distances = {2450, 2450, 2450};
        int distanceIdx;
        double pitch = 4.06;
        int progress = 0, afterAk = 0;
        int[] corners;
        int badAt = -1, badValue;
        int cancelAt = -1;
        FitRun run;
        int sessions, open;

        @Override public String modelId() { calls.add("modelId"); return "TEST"; }
        @Override public boolean supported() { calls.add("supported"); return supported; }
        @Override public int mountMode() { calls.add("mount"); return mount; }
        @Override public int readDistanceMm() { calls.add("distance"); return distances[Math.min(distanceIdx++, distances.length - 1)]; }
        @Override public double readPitchDeg() { calls.add("pitch"); return pitch; }
        @Override public boolean runAutoKeystone() { calls.add("ak"); progress = afterAk; return true; }
        @Override public int[] readKeystoneCorners() { calls.add("corners"); return corners; }
        @Override public int positionCount() { calls.add("positionCount"); return positions; }
        @Override public int readProgress() { calls.add("progress"); return progress; }
        @Override public void homeZoom() { calls.add("home"); progress = 0; }
        @Override public void beginZoomSession() { calls.add("begin"); sessions++; open++; }
        @Override public void endZoomSession() { calls.add("end"); open--; }
        @Override public int stepSmaller() {
            calls.add("step");
            if (open != 1) throw new IllegalStateException("step outside a session");
            if (progress >= positions - 1) throw new IllegalStateException("step past the smallest");
            progress++;
            int steps = count("step");
            if (steps == cancelAt) run.cancel();
            if (steps == badAt) { progress = badValue; return badValue; }
            return progress;
        }
        @Override public void autofocus() { calls.add("af"); }

        int count(String c) {
            int n = 0;
            for (String s : calls) if (s.equals(c)) n++;
            return n;
        }
    }

    static ScreenSize screenForTarget9() {
        return ScreenSize.fromWidthHeight(2290, 1288.1, 0);
    }

    static void run() {
        ThrowModel m = fixtureModel();
        FitRun.Sleeper none = ms -> { };

        FakeDevice d = new FakeDevice();
        FitRun r = new FitRun(d, none, null);
        d.run = r;
        FitRun.Result res = r.run(screenForTarget9(), m, FitPlanner.Mode.INSIDE);
        eq("26 status", FitRun.Status.DONE, res.status);
        eq("26 target", 9, res.plan.target);
        eq("26 steps", 9, d.count("step"));
        eq("26 final", 9, res.finalProgress);
        eq("26 one session begun", 1, d.count("begin"));
        eq("26 one session ended", 1, d.count("end"));
        eq("26 one autofocus", 1, d.count("af"));
        eq("26 three distance reads", 3, d.count("distance"));
        eq("26 no home", 0, d.count("home"));
        is("26 final size known", res.finalSize != null);

        d = new FakeDevice();
        d.afterAk = 4;
        r = new FitRun(d, none, null);
        d.run = r;
        res = r.run(screenForTarget9(), m, FitPlanner.Mode.INSIDE);
        eq("27 home once", 1, d.count("home"));
        eq("27 walk from 0", 9, d.count("step"));
        eq("27 status", FitRun.Status.DONE, res.status);

        d = new FakeDevice();
        r = new FitRun(d, none, null);
        d.run = r;
        res = r.run(ScreenSize.fromDiagonal(150, true, 0, 0), m, FitPlanner.Mode.INSIDE);
        eq("27 target 0: no step", 0, d.count("step"));
        eq("27 target 0: no session", 0, d.count("begin"));
        eq("27 target 0: no autofocus", 0, d.count("af"));
        eq("27 target 0: kind", FitPlanner.Kind.MOVE_FURTHER, res.plan.kind);

        d = new FakeDevice();
        d.badAt = 3;
        d.badValue = 4;
        r = new FitRun(d, none, null);
        d.run = r;
        res = r.run(screenForTarget9(), m, FitPlanner.Mode.INSIDE);
        eq("28 status", FitRun.Status.LENS_UNEXPECTED, res.status);
        eq("28 no 4th step", 3, d.count("step"));
        eq("28 session ended", 1, d.count("end"));
        eq("28 no autofocus after a lost lens", 0, d.count("af"));

        d = new FakeDevice();
        d.cancelAt = 2;
        r = new FitRun(d, none, null);
        d.run = r;
        res = r.run(screenForTarget9(), m, FitPlanner.Mode.INSIDE);
        eq("29 status", FitRun.Status.CANCELLED, res.status);
        eq("29 step 2 completes, no step 3", 2, d.count("step"));
        eq("29 session ended", 1, d.count("end"));
        eq("29 stopped at", 2, res.finalProgress);

        d = new FakeDevice();
        d.distances = new int[] {2440, 2450, 2490};
        r = new FitRun(d, none, null);
        d.run = r;
        res = r.run(screenForTarget9(), m, FitPlanner.Mode.INSIDE);
        eq("30 status", FitRun.Status.DISTANCE_UNSTABLE, res.status);
        is("30 no AK, no lens call", d.count("ak") == 0 && d.count("step") == 0 && d.count("home") == 0
                && d.count("begin") == 0 && d.count("af") == 0);

        for (int k = 0; k < 2; k++) {
            d = new FakeDevice();
            if (k == 0) d.positions = 13; else d.supported = false;
            r = new FitRun(d, none, null);
            res = r.run(screenForTarget9(), m, FitPlanner.Mode.INSIDE);
            eq("31 unsupported " + k, FitRun.Status.UNSUPPORTED, res.status);
            boolean only = true;
            for (String c : d.calls) only &= c.equals("supported") || c.equals("positionCount");
            is("31 no call but supported / positionCount " + k + " " + d.calls, only);
        }

        d = new FakeDevice();
        r = new FitRun(d, none, null);
        d.run = r;
        for (int i = 0; i < 3; i++) r.readDistance();
        boolean refusedFourth = false;
        try { r.readDistance(); } catch (IllegalStateException e) { refusedFourth = true; }
        is("32 4th distance read refused", refusedFourth);
        eq("32 device read 3 times", 3, d.count("distance"));

        d = new FakeDevice();
        r = new FitRun(d, none, null);
        d.run = r;
        res = r.run(ScreenSize.fromDiagonal(60, true, 0, 0), m, FitPlanner.Mode.INSIDE);
        boolean inWalk = false, onlySteps = true;
        for (String c : d.calls) {
            if (c.equals("begin")) inWalk = true;
            else if (c.equals("end")) inWalk = false;
            else if (inWalk) onlySteps &= c.equals("step");
        }
        is("33 the walk sends only stepSmaller", onlySteps);
        eq("33 smallest reached, never past it", 13, res.finalProgress);
        eq("33 13 steps", 13, d.count("step"));

        // calibration over the fake: 0 and 13, solved back to the fixture TRs
        d = new FakeDevice();
        r = new FitRun(d, none, null);
        d.run = r;
        FitCalibration cal = new FitCalibration(r, FitCalibration.Kind.TWO);
        is("calibration prepare", cal.prepare() == null);
        FitGeometry g = r.prepared().geometry;
        eq("calibration position 0", 0, cal.position());
        near("calibration TR p0", 0.9, cal.record(g.inscribed(0.9)[0]), 1e-6);
        is("calibration walk", cal.next() == null);
        eq("calibration position 13", 13, cal.position());
        eq("calibration 13 steps", 13, d.count("step"));
        near("calibration TR p13", 1.188, cal.record(g.inscribed(1.188)[0]), 1e-6);
        ThrowModel.Built b = cal.finish();
        is("calibration model", b.ok() && Math.abs(b.model.tr(7) - m.tr(7)) < 1e-6);
    }

    // ------------------------------------------------------------------ storage
    static void store() {
        FitStore.Screen s = new FitStore.Screen();
        s.mode = "wh";
        s.wMm = 2210;
        s.hMm = 1245;
        s.marginMm = 15;
        s.fitMode = "closest";
        FitStore.Screen back = FitStore.screenFromJson(FitStore.toJson(s));
        is("store screen round trip", back != null && back.mode.equals("wh") && back.wMm == 2210 && back.marginMm == 15
                && back.planMode() == FitPlanner.Mode.CLOSEST);
        is("store bad screen", FitStore.screenFromJson("{\"mode\":\"wh\",\"wMm\":10}") == null);
        is("store garbage", FitStore.screenFromJson("not json") == null && FitStore.screenFromJson(null) == null);
        FitStore.Calib c = new FitStore.Calib();
        c.points = new double[][] {{0, 0.9}, {13, 1.188}};
        c.distanceMm = 2450;
        c.pitchDeg = 4.06;
        c.corners = LOGGED.clone();
        c.date = 1793318400000L;
        FitStore.Calib cb = FitStore.calibFromJson(FitStore.toJson(c));
        is("store calib round trip", cb != null && cb.model() != null && Math.abs(cb.model().tr(13) - 1.188) < 1e-6
                && cb.corners[7] == 2057 && cb.date == c.date);
        is("store refused calib", FitStore.calibFromJson("{\"v\":1,\"points\":[[0,0.9],[13,0.95]]}") == null);
        FitStore.LastFit f = new FitStore.LastFit();
        f.progress = 10;
        f.distanceMm = 2450;
        FitStore.LastFit fb = FitStore.lastFitFromJson(FitStore.toJson(f));
        is("store last fit", fb != null && fb.progress == 10);
    }
}
