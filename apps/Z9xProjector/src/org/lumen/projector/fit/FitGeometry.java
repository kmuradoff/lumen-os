package org.lumen.projector.fit;

/**
 * Fit to my screen size, the geometry (docs/fit_screen/DESIGN.md section 4). Device neutral, plain Java, no
 * Android class and no HAL code: the same file runs in the host test (test/fit/run.sh).
 *
 * Panel: 3840 x 2160 PHYSICAL panel pixels (the coordinates of the keystone corner read, Z9X IGmpf 186 / HB
 * DisplayManager 16). The lens axis passes through the panel's bottom centre (axisRow 2160, axisCol 1920; offset
 * about 100 %): the picture starts at the lens axis and extends away from it. Row 0 is therefore the FAR edge of
 * the picture and row 2160 the edge next to the lens (the top edge on a ceiling mount, the bottom edge on a desk).
 *
 * Tilt theta (degrees): angle between the lens axis and the screen normal, positive when the picture side tilts
 * away from the normal (ceiling mount pointing down at a lower screen: theta > 0; desk mount pointing up: theta >
 * 0). With theta > 0 the far edge is the wide one and the lens-side edge the narrow one: on the owner's ceiling
 * projector the top edge of the picture is the narrow one. This comes out of the projection, not from a factor.
 *
 * Distance D: perpendicular distance from the lens to the screen plane, in mm (from the ToF read).
 * Throw ratio TR: perpendicular distance / full panel width on a perpendicular screen, at one zoom position.
 */
public final class FitGeometry {
    public static final double PANEL_W = 3840, PANEL_H = 2160;
    public static final double AXIS_COL = 1920, DEFAULT_AXIS_ROW = 2160;
    /** The panel's height relative to its width. */
    private static final double ASPECT_Y = 9.0 / 16.0;
    /** The full panel as corners TL, TR, BR, BL (x, y), on the pixel edges. */
    public static final int[] FULL_FRAME = {0, 0, 3840, 0, 3840, 2160, 0, 2160};

    /** Which picture the lit area is (section 4.3). */
    public enum Estimator {
        /** A: the corrected keystone corners, mapped onto the screen. */
        CORNERS,
        /** B, keystone off: the lit picture is the whole trapezoid of the panel. */
        FULL_PANEL,
        /** B, keystone active but corners unknown: the largest 16:9 rectangle inside the trapezoid. */
        INSCRIBED
    }

    public final double distanceMm, thetaDeg, axisRow;
    private final double cos, sin;

    public FitGeometry(double distanceMm, double thetaDeg) {
        this(distanceMm, thetaDeg, DEFAULT_AXIS_ROW);
    }

    public FitGeometry(double distanceMm, double thetaDeg, double axisRow) {
        if (!(distanceMm > 0) || Double.isInfinite(distanceMm)) throw new IllegalArgumentException("distance " + distanceMm);
        if (!(Math.abs(thetaDeg) <= 45)) throw new IllegalArgumentException("tilt " + thetaDeg);
        this.distanceMm = distanceMm;
        this.thetaDeg = thetaDeg;
        this.axisRow = axisRow;
        double t = Math.toRadians(thetaDeg);
        cos = Math.cos(t);
        sin = Math.sin(t);
    }

    /**
     * Section 4.2: panel pixel (px, py) to the screen point {X, Y} in mm at throw ratio tr. X is horizontal (0 = the
     * lens centre line), Y runs along the screen from the height of the lens axis, growing away from the lens.
     * The exact pinhole projection onto a plane: lines stay lines.
     */
    public double[] map(double px, double py, double tr) {
        double y = ((axisRow - py) / PANEL_H) * ASPECT_Y / tr;
        double u = ((px - AXIS_COL) / PANEL_W) / tr;
        double den = cos - y * sin;
        if (den <= 1e-6) throw new IllegalArgumentException("ray does not reach the screen");
        return new double[] {u * distanceMm / den, distanceMm * (y * cos + sin) / den};
    }

    /** The four corners mapped to the screen and what the planner needs from them. */
    public static final class Quad {
        /** Edge lengths in mm: far edge (panel row 0 side), near (lens side), left, right. */
        public final double far, near, left, right;
        /** Mean of the far and near edges / of the left and right edges. */
        public final double meanW, meanH;
        /** Bounding box of the four mapped points (the lit area must not spill outside the screen). */
        public final double outerW, outerH;
        /** The axis-aligned box inside the four points. */
        public final double innerW, innerH;
        /** Half the larger relative difference of two opposite edges (unmodelled yaw / roll, section 4.4). */
        public final double mismatch;
        public final boolean keystoneOff;
        /** Mapped corners TL, TR, BR, BL as {X, Y}. */
        public final double[][] points;

        Quad(double[][] q, boolean off) {
            points = q;
            keystoneOff = off;
            far = dist(q[0], q[1]);
            near = dist(q[3], q[2]);
            left = dist(q[0], q[3]);
            right = dist(q[1], q[2]);
            meanW = (far + near) / 2;
            meanH = (left + right) / 2;
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] p : q) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
            outerW = maxX - minX;
            outerH = maxY - minY;
            innerW = Math.min(q[1][0], q[2][0]) - Math.max(q[0][0], q[3][0]);
            innerH = Math.min(q[0][1], q[1][1]) - Math.max(q[2][1], q[3][1]);
            mismatch = Math.max(Math.abs(far - near) / (far + near), Math.abs(left - right) / (left + right));
        }

        /** Far edge / near edge. */
        public double farNearRatio() { return far / near; }

        private static double dist(double[] a, double[] b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }
    }

    /**
     * Section 4.3 A: corners TL, TR, BR, BL as 8 ints (x, y) in physical panel pixels. Corners within 2 px of the
     * panel corners count as "keystone off" and are mapped on the exact panel edges.
     */
    public Quad quad(int[] c, double tr) {
        if (!validCorners(c)) throw new IllegalArgumentException("corners");
        boolean off = isFullFrame(c);
        int[] k = off ? FULL_FRAME : c;
        double[][] q = new double[4][];
        for (int i = 0; i < 4; i++) q[i] = map(k[2 * i], k[2 * i + 1], tr);
        return new Quad(q, off);
    }

    /** The full-panel trapezoid (no keystone): {Wt lens-side edge, Wb far edge, H height}. */
    public double[] trapezoid(double tr) {
        Quad q = quad(FULL_FRAME, tr);
        double h = q.points[0][1] - q.points[3][1];
        return new double[] {q.near, q.far, h};
    }

    /**
     * The largest 16:9 rectangle inside the full-panel trapezoid, standing on its wide edge: {w, h}.
     * w = Wwide * H / (H + (Wwide - Wnarrow) / (16/9)), h = w * 9/16, capped at h <= H.
     */
    public double[] inscribed(double tr) {
        double[] t = trapezoid(tr);
        double wide = Math.max(t[0], t[1]), narrow = Math.min(t[0], t[1]), h = t[2];
        double w = wide * h / (h + (wide - narrow) / (16.0 / 9.0));
        double hh = w * ASPECT_Y;
        if (hh > h) {
            hh = h;
            w = h / ASPECT_Y;
        }
        return new double[] {w, hh};
    }

    /** The picture size of one estimator at throw ratio tr. */
    public static final class Size {
        /** Reported size: mean edges (A, full panel) or the rectangle (inscribed). */
        public final double w, h;
        /** The box the lit area needs (the "inside the frame" check). */
        public final double outerW, outerH;
        public final double mismatch;

        Size(double w, double h, double outerW, double outerH, double mismatch) {
            this.w = w;
            this.h = h;
            this.outerW = outerW;
            this.outerH = outerH;
            this.mismatch = mismatch;
        }

        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%.1f x %.1f (outer %.1f x %.1f)", w, h, outerW, outerH);
        }
    }

    public Size size(Estimator e, int[] corners, double tr) {
        switch (e) {
            case CORNERS: {
                Quad q = quad(corners, tr);
                return new Size(q.meanW, q.meanH, q.outerW, q.outerH, q.mismatch);
            }
            case FULL_PANEL: {
                Quad q = quad(FULL_FRAME, tr);
                return new Size(q.meanW, q.meanH, q.outerW, q.outerH, q.mismatch);
            }
            default: {
                double[] r = inscribed(tr);
                return new Size(r[0], r[1], r[0], r[1], 0);
            }
        }
    }

    /** The estimator for a corner read after an auto keystone: null or invalid -> inscribed. */
    public static Estimator estimatorFor(int[] corners) {
        if (!validCorners(corners)) return Estimator.INSCRIBED;
        return isFullFrame(corners) ? Estimator.FULL_PANEL : Estimator.CORNERS;
    }

    /** 8 ints, inside the panel, TL left of TR, BL left of BR, TL / TR above BL / BR, at least half the panel each way (the vendor refuses less, RESEARCH 3.3). */
    public static boolean validCorners(int[] c) {
        if (c == null || c.length != 8) return false;
        for (int i = 0; i < 8; i += 2) {
            if (c[i] < 0 || c[i] > PANEL_W || c[i + 1] < 0 || c[i + 1] > PANEL_H) return false;
        }
        if (c[2] - c[0] < PANEL_W / 2 || c[4] - c[6] < PANEL_W / 2) return false;
        return c[7] - c[1] >= PANEL_H / 2 && c[5] - c[3] >= PANEL_H / 2;
    }

    public static boolean isFullFrame(int[] c) {
        if (c == null || c.length != 8) return false;
        for (int i = 0; i < 8; i++) if (Math.abs(c[i] - FULL_FRAME[i]) > 2) return false;
        return true;
    }
}
