/*
 * Play6KstPoint: xgimi.hardware.gmpf@1.0::IDisplayType::KstPoint on the XGIMI Play 6, the manual
 * keystone corner set of IGmpf 150 / 185 / 186. A fork of Z9xProjector hal/KstPoint.java
 * (tools/play6/forks.tsv): the same 326-byte wire, another coordinate space.
 *
 * Wire layout (326 bytes, one writeBuffer/readBuffer), as on the Z9X:
 *   offset 0     u8  kstMode         (0 = 4-point, 1 = 8-point 3x3 grid)
 *   offset 1     pad
 *   offset 2     PointCoordinate pointData[9][9], each {int16 S16X; int16 S16Y} = 4 bytes,
 *                row stride 36 bytes: point [r][c] at 2 + r*36 + c*4.
 * VERIFIED for the Play 6 proxy: `mov.w r2,#0x146` (326) in BpHwGmpf 185 at 0xeb980 and 150 at 0xe7686
 * (docs/play6/SETTINGS_PLAN.md 1.3, C 2.1).
 * Mode 0 corners: [0][0] top-left, [0][1] top-right, [1][1] bottom-right, [1][0] bottom-left.
 *
 * Coordinate space: the Play 6 panel is 1920x1080 (G58/panel/xgimi_1080p_lvds.ini m_wPanelWidth = 1920,
 * m_wPanelHeight = 1080; the stock logo prop reads 0,1 .. 1919,1080, C 2.1). Range check: corners must lie
 * in 0..1920 x 0..1080 (read-back tolerant of the edge value). UNVERIFIED on the device: that 186 reports
 * this space (test B9). There is no built-in full frame here (the Z9X one is its vendor default): the
 * full frame is taken from the device first (plan 1.3). Everything else (e.g. [0][2], [2][0], set only
 * in 8-point mode) stays 0 on send and is only logged on read.
 */
package org.lumen.projector.hal;

import android.os.HwBlob;

public final class Play6KstPoint {
    public static final int WIRE_SIZE = 326;
    public static final int MODE_FOUR_POINT = 0;
    /** Panel size of the Play 6 (see the header): 186 / 150 / 185 coordinates are in this space. */
    public static final int PANEL_W = 1920;
    public static final int PANEL_H = 1080;
    private static final int ROW_STRIDE = 36;
    private static final int POINTS_OFFSET = 2;

    /** Corner index order used by the getters/setters below. */
    public static final int TL = 0, TR = 1, BR = 2, BL = 3;
    private static final int[][] CORNER_RC = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

    private final int[] x = new int[4];
    private final int[] y = new int[4];
    /** Raw summary of the 186 blob this set was parsed from (kstMode + points), null otherwise. */
    private String raw;

    public int x(int corner) { return x[corner]; }

    public int y(int corner) { return y[corner]; }

    /** Sets one corner; throws IllegalArgumentException outside 0..1920 x 0..1080 (panel space). */
    public Play6KstPoint set(int corner, int px, int py) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner " + corner);
        HidlCaller.checkRange("x", px, 0, PANEL_W);
        HidlCaller.checkRange("y", py, 0, PANEL_H);
        x[corner] = px;
        y[corner] = py;
        return this;
    }

    public Play6KstPoint copy() {
        Play6KstPoint p = new Play6KstPoint();
        System.arraycopy(x, 0, p.x, 0, 4);
        System.arraycopy(y, 0, p.y, 0, 4);
        return p;
    }

    /** Mode-0 blob for 150 / 185. */
    HwBlob toBlob() {
        HwBlob b = new HwBlob(WIRE_SIZE);
        b.putInt8Array(0, new byte[WIRE_SIZE]);                  // zero everything first
        b.putInt8(0, (byte) MODE_FOUR_POINT);
        for (int c = 0; c < 4; c++) {
            long off = POINTS_OFFSET + CORNER_RC[c][0] * ROW_STRIDE + CORNER_RC[c][1] * 4L;
            b.putInt16(off, (short) x[c]);
            b.putInt16(off + 2, (short) y[c]);
        }
        return b;
    }

    /**
     * Parses a 326-byte reply blob of 186 (mode 0). Out-of-range values throw (reply rejected); the
     * message then carries the raw values (kstMode, the 4 mapped corners and [0][2] / [2][0], which
     * are non-zero in 8-point mode) so a device log settles the coordinate space.
     */
    static Play6KstPoint fromBlob(HwBlob b) {
        Play6KstPoint p = new Play6KstPoint();
        try {
            for (int c = 0; c < 4; c++) {
                long off = POINTS_OFFSET + CORNER_RC[c][0] * ROW_STRIDE + CORNER_RC[c][1] * 4L;
                p.set(c, b.getInt16(off), b.getInt16(off + 2));
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() + "; raw " + raw(b), e);
        }
        p.raw = raw(b);
        return p;
    }

    /** The raw 186 values (kstMode, mapped corners, [0][2]/[2][0]) or "-" for a set built locally. */
    public String raw() { return raw != null ? raw : "-"; }

    private static String raw(HwBlob b) {
        StringBuilder sb = new StringBuilder("mode=").append(b.getInt8(0));
        int[][] rc = {{0, 0}, {0, 1}, {1, 1}, {1, 0}, {0, 2}, {2, 0}};
        for (int[] q : rc) {
            long off = POINTS_OFFSET + q[0] * ROW_STRIDE + q[1] * 4L;
            sb.append(" [").append(q[0]).append("][").append(q[1]).append("]=")
                    .append(b.getInt16(off)).append(',').append(b.getInt16(off + 2));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Play6KstPoint{TL " + x[TL] + "," + y[TL] + " TR " + x[TR] + "," + y[TR]
                + " BR " + x[BR] + "," + y[BR] + " BL " + x[BL] + "," + y[BL] + "}";
    }
}
