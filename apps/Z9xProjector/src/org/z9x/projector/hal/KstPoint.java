/*
 * KstPoint: xgimi.hardware.gmpf@1.0::IDisplayType::KstPoint, the manual keystone corner set.
 *
 * Wire layout (326 bytes, one writeBuffer/readBuffer):
 *   offset 0     u8  kstMode         (0 = 4-point, 1 = 8-point 3x3 grid)
 *   offset 1     pad
 *   offset 2     PointCoordinate pointData[9][9], each {int16 S16X; int16 S16Y} = 4 bytes,
 *                row stride 36 bytes: point [r][c] at 2 + r*36 + c*4.
 * VERIFIED: BpHwGmpf::_hidl_correctKeystoneFullPiont @0x173d78 `mov w2,#0x146` (326) before
 * Parcel::writeBuffer; checkTrapezoidCoordinate @0x16e7d8 the same (research/focus/hidl64.dis).
 * The field names/types (u8 kstMode, PointCoordinate{S16X,S16Y}[9][9]) come from the stock AIDL twin
 * xgimi/hardware/gmpf/IDisplayType/KstPoint.java + PointCoordinate.java (research/v61/display/ns_jadx);
 * 1 + 1 pad + 9*9*4 = 326 matches the proxy size.
 * Mode 0 corners: [0][0] top-left, [0][1] top-right, [1][1] bottom-right, [1][0] bottom-left
 * (FEATURE_SPEC 2.4). UNVERIFIED: that the stock JNI maps KeyStoneFullCoordinates to 185 this way;
 * round-trip 186 -> 150 with unchanged points on the device before moving any corner.
 *
 * Coordinate space (v6.4, VERIFIED from the live log of 2026-10-06 09:44:01): 186 returns the corners
 * in DLP PANEL coordinates 3840x2160, e.g. vendor GM_DISP_KST_CTRL [GetCorrectKeystone:763] type=10
 * p_kstPoint (200,300)(3594,14)(86,2108)(3798,1968); v6.3 rejected it ("x 3594 not in 0..1920").
 * Panel size evidence (read-only, the owner's Z9X):
 *  - /mnt/vendor/xgimiconfig/G0082/panel/UD_VB1_8LANE_DLP_PROJECTOR_60.ini (the G0082 panel named by
 *    mtk_Customer.ini, research/v61/RESULT_display.json) and the live dmesg panel
 *    /vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini: m_wPanelWidth = 3840,
 *    m_wPanelHeight = 2160; dmesg "MHAL_GOP_SetMixer4Size ... w = 3840, h = 2160";
 *  - vendor libxgimi default of persist.sys.keystone.positions "0,0|0,2159|3839,0|3839,2159"
 *    (research/focus/libxgimi.strings 0x20bde45): the full frame is 0..3839 x 0..2159.
 * Range check (our rule): mode-0 corners must lie in 0..3840 x 0..2160 (read-back tolerant of the
 * edge value); moves are clamped to 0..MAX_X x 0..MAX_Y and the full frame is the vendor default
 * above. The UI maps panel -> screen itself (ManualKeystonePanel: 1:1 at the 3840x2160 UI, the Lumen OS
 * 1.0.1 default; 2:1 at 1080p). Everything else
 * (e.g. [0][2], [2][0], set only in 8-point mode) stays 0 on send and is only logged on read.
 */
package org.z9x.projector.hal;

import android.os.HwBlob;

public final class KstPoint {
    public static final int WIRE_SIZE = 326;
    public static final int MODE_FOUR_POINT = 0;
    /** DLP panel size (see the header): 186/150/185 coordinates are in this space (= the 4K UI, not a 1080p UI). */
    public static final int PANEL_W = 3840;
    public static final int PANEL_H = 2160;
    /** Last pixel column / row: the vendor full frame (persist.sys.keystone.positions default). */
    public static final int MAX_X = PANEL_W - 1;
    public static final int MAX_Y = PANEL_H - 1;
    private static final int ROW_STRIDE = 36;
    private static final int POINTS_OFFSET = 2;

    /** Corner index order used by the getters/setters below. */
    public static final int TL = 0, TR = 1, BR = 2, BL = 3;
    private static final int[][] CORNER_RC = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

    private final int[] x = new int[4];
    private final int[] y = new int[4];
    /** Raw summary of the 186 blob this set was parsed from (kstMode + points), null otherwise. */
    private String raw;

    /** A full-frame 4-point set (no keystone): TL(0,0) TR(3839,0) BR(3839,2159) BL(0,2159), the vendor default. */
    public static KstPoint fullFrame() {
        KstPoint p = new KstPoint();
        p.set(TL, 0, 0);
        p.set(TR, MAX_X, 0);
        p.set(BR, MAX_X, MAX_Y);
        p.set(BL, 0, MAX_Y);
        return p;
    }

    public int x(int corner) { return x[corner]; }

    public int y(int corner) { return y[corner]; }

    /** Sets one corner; throws IllegalArgumentException outside 0..3840 x 0..2160 (panel space). */
    public KstPoint set(int corner, int px, int py) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner " + corner);
        HidlCaller.checkRange("x", px, 0, PANEL_W);
        HidlCaller.checkRange("y", py, 0, PANEL_H);
        x[corner] = px;
        y[corner] = py;
        return this;
    }

    public KstPoint copy() {
        KstPoint p = new KstPoint();
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
    static KstPoint fromBlob(HwBlob b) {
        KstPoint p = new KstPoint();
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
        return "KstPoint{TL " + x[TL] + "," + y[TL] + " TR " + x[TR] + "," + y[TR]
                + " BR " + x[BR] + "," + y[BR] + " BL " + x[BL] + "," + y[BL] + "}";
    }

}
