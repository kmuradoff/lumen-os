/*
 * KstPoint: xgimi.hardware.gmpf.IDisplayType.KstPoint of the Z9X HB (stable AIDL), the manual keystone corner set.
 * Same public API as the Z9X class (4 corners in DLP panel space 3840x2160); only the wire differs.
 *
 * Wire (stock IDisplayType$KstPoint.writeToParcel @054514 / readFromParcel @0543fc of com.xgimi.middle.jar, read
 * through AidlCaller.Wire): stable parcelable frame { byte kstMode (writeByte), PointCoordinate[] pointData
 * (writeTypedArray) }, PointCoordinate = frame { int S16X, int S16Y }.
 * Array length and order (S0, devices/goat_elegant/stock analysis (a)): the stock wrapper
 * com.xgimi.middle.DisplayManagerWrapper builds 81 elements, index r * 9 + c of the Z9X 9x9 grid
 * (correctKeystone @02fd6c, checkTrapezoidCoordinate @02d334, getCorrectKeystone @02ff74), the same
 * KeyStoneFullCoordinates grid the Z9X stock sends; correctKeystone sends setCorrectKeystoneFullPiont(kstMode, kst).
 * (The 65x65 = 4225 array of convertTo1DAidlPointArray belongs to MultiCorrectKeystone, never used here.)
 * Mode 0 corners: [0][0] top-left, [0][1] top-right, [1][1] bottom-right, [1][0] bottom-left, as on the Z9X
 * (same UNVERIFIED mapping; the first device read of 16 must show corners inside the panel before the gate opens).
 *
 * Rule 3 of the design notes: a set that is sent always starts from a grid read with 16
 * getCorrectKeystoneFullPiont(0): its own (a read set and its copies) or, for a set built with new KstPoint() (the
 * Z9X ManualKeystonePanel builds its targets so, after reading 16 first), the last grid read in this process. A
 * read of another length is rejected, and a set with no read grid behind it is refused before sending
 * (IllegalStateException); only fullFrame() may fall back to an all-zero grid of the confirmed length, as the Z9X
 * blob did. Only the four corners are sent; every other element is 0 (hb-t8c: 16 returns uninitialised memory there).
 */
package org.lumen.projector.hal;

public final class KstPoint {
    /** Z9X public API (size of the HIDL blob); not used on the HB, where the wire is the AIDL parcelable below. */
    public static final int WIRE_SIZE = 326;
    /** Number of PointCoordinate elements the stock wrapper sends and reads (9 x 9). */
    static final int GRID = 9;
    static final int WIRE_POINTS = GRID * GRID;
    public static final int MODE_FOUR_POINT = 0;
    /** DLP panel size: 16/64/15 coordinates are in this space (= the 4K UI, not a 1080p UI). */
    public static final int PANEL_W = 3840;
    public static final int PANEL_H = 2160;
    /** Last pixel column / row: the vendor full frame. */
    public static final int MAX_X = PANEL_W - 1;
    public static final int MAX_Y = PANEL_H - 1;

    /** Corner index order used by the getters/setters below. */
    public static final int TL = 0, TR = 1, BR = 2, BL = 3;
    private static final int[][] CORNER_RC = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

    private final int[] x = new int[4];
    private final int[] y = new int[4];
    /** The grid as read from the vendor (x, y per element), null for a set built locally. */
    private int[] gridX, gridY;
    private byte mode = MODE_FOUR_POINT;
    /** fullFrame(): may fall back to an all-zero grid when nothing was read yet. */
    private boolean full;
    /** The last grid read with 16 in this process (both arrays together), null before the first read. */
    private static volatile int[][] sLastGrid;
    /** Raw summary of the read set (kstMode + points), null otherwise. */
    private String raw;

    /** A full-frame 4-point set (no keystone): TL(0,0) TR(3839,0) BR(3839,2159) BL(0,2159), the vendor default. */
    public static KstPoint fullFrame() {
        KstPoint p = new KstPoint();
        p.set(TL, 0, 0);
        p.set(TR, MAX_X, 0);
        p.set(BR, MAX_X, MAX_Y);
        p.set(BL, 0, MAX_Y);
        p.full = true;
        return p;
    }

    public int x(int corner) { return x[corner]; }

    public int y(int corner) { return y[corner]; }

    /** Sets one corner; throws IllegalArgumentException outside 0..3840 x 0..2160 (panel space). */
    public KstPoint set(int corner, int px, int py) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner " + corner);
        AidlCaller.checkRange("x", px, 0, PANEL_W);
        AidlCaller.checkRange("y", py, 0, PANEL_H);
        x[corner] = px;
        y[corner] = py;
        return this;
    }

    public KstPoint copy() {
        KstPoint p = new KstPoint();
        System.arraycopy(x, 0, p.x, 0, 4);
        System.arraycopy(y, 0, p.y, 0, 4);
        if (gridX != null) {
            p.gridX = gridX.clone();
            p.gridY = gridY.clone();
        }
        p.mode = mode;
        p.full = full;
        return p;
    }

    /** Mode-0 fields for 64 / 15 (inside the parcelable frame AidlCaller writes): the read grid, four corners replaced. */
    void writeTo(AidlCaller.Out o) {
        int[] bx = gridX, by = gridY;
        if (bx == null) {
            int[][] last = sLastGrid;
            if (last != null) {
                bx = last[0];
                by = last[1];
            } else if (full) {
                bx = new int[WIRE_POINTS];
                by = new int[WIRE_POINTS];
            }
        }
        if (bx == null || bx.length != WIRE_POINTS || by.length != WIRE_POINTS) {
            throw new IllegalStateException("keystone set without a grid read from the projector: not sent");
        }
        // hb-t8c device read: 16 fills only the four corners; the other 77 elements of its reply are uninitialised
        // vendor memory (they change from read to read). They are sent as 0, the set proven with 64 + 15.
        final int[] gx = new int[WIRE_POINTS], gy = new int[WIRE_POINTS];
        for (int c = 0; c < 4; c++) {
            int i = CORNER_RC[c][0] * GRID + CORNER_RC[c][1];
            gx[i] = x[c];
            gy[i] = y[c];
        }
        o.i8((byte) MODE_FOUR_POINT);
        o.typedArray(WIRE_POINTS, (e, k) -> {
            e.i32(gx[k]);
            e.i32(gy[k]);
        });
    }

    /**
     * Parses the fields of a reply KstPoint of 16 (mode 0; AidlCaller reads the frame around them). A length other than 81 or a corner outside the panel throws
     * (reply rejected); the message carries the raw values so a device log settles the layout.
     */
    static KstPoint readFrom(AidlCaller.In r) {
        final KstPoint p = new KstPoint();
        final int[] gx = new int[WIRE_POINTS], gy = new int[WIRE_POINTS];
        final byte[] mode = new byte[1];
        final int[] n = new int[1];
        mode[0] = r.i8();
        n[0] = r.typedArray(WIRE_POINTS, (e, k) -> {
            gx[k] = e.i32();
            gy[k] = e.i32();
        });
        String rawText = raw(mode[0], n[0], gx, gy);
        if (n[0] != WIRE_POINTS) throw new IllegalStateException("16 returned " + n[0] + " points; raw " + rawText);
        try {
            for (int c = 0; c < 4; c++) {
                int i = CORNER_RC[c][0] * GRID + CORNER_RC[c][1];
                p.set(c, gx[i], gy[i]);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() + "; raw " + rawText, e);
        }
        p.gridX = gx;
        p.gridY = gy;
        p.mode = mode[0];
        p.raw = rawText;
        sLastGrid = new int[][] {gx.clone(), gy.clone()};
        return p;
    }

    /** The raw 16 values (kstMode, length, mapped corners, [0][2] / [2][0]) or "-" for a set built locally. */
    public String raw() { return raw != null ? raw : "-"; }

    private static String raw(byte mode, int n, int[] gx, int[] gy) {
        StringBuilder sb = new StringBuilder("mode=").append(mode).append(" n=").append(n);
        int[][] rc = {{0, 0}, {0, 1}, {1, 1}, {1, 0}, {0, 2}, {2, 0}};
        for (int[] q : rc) {
            int i = q[0] * GRID + q[1];
            sb.append(" [").append(q[0]).append("][").append(q[1]).append("]=").append(gx[i]).append(',').append(gy[i]);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "KstPoint{TL " + x[TL] + "," + y[TL] + " TR " + x[TR] + "," + y[TR]
                + " BR " + x[BR] + "," + y[BR] + " BL " + x[BL] + "," + y[BL] + "}";
    }
}
