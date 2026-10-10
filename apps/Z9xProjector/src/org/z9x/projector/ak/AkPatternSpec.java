package org.z9x.projector.ak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geometry of the auto-keystone test pattern ("ak4_30w" layout, Z9X obstacle_ak_image_type=1),
 * in 1920 x 1080 design pixels (the stock UI size; Lumen OS 1.0.1 scales them to the real UI frame at
 * 1080p, 2K or 4K, so the pattern covers the same part of the picture at every resolution). Pure Java (no android.* imports) so the exact same code is rendered
 * by the app (AkPatternRenderer, android.graphics.Canvas) and by the Mac test harness (Java2D) that
 * compares it with the stock image.
 *
 * Where the numbers come from:
 *  - The checkerboard lattice (origin, square size, size, the hole in the middle) is derived at
 *    runtime from the device's own /mnt/vendor/xgimiconfig/public/AK/config.yaml "keyPoints":
 *    those are the inner checker corners the vendor detector expects (pixel-centre convention,
 *    e.g. 184.48 = corner at pixel edge 185). A board cell is drawn black when (row + col) is odd and
 *    at least one of its in-lattice corners is a keyPoint; cells whose corners are all missing form
 *    the white hole that holds the markers.
 *  - Markers: 4 ArUco markers, dictionary DICT_4X4_50, ids 0..3 in config "arCorners" order
 *    (TL, TR, BR, BL), 160 px, 6x6 cells (1-cell black border). VERIFIED by decoding the stock
 *    SystemUI drawable ak4_30w.png with OpenCV (research/v61/ak_impl, facts only; the PNG is not
 *    shipped). The config arCorners are sub-pixel detector values up to 2.4 px off the stock image,
 *    so when they agree with the stock positions within 4 px the stock positions are used.
 *  - Parity (top-left cell white), the 4 corner brackets (inset 48, arm 80, 12 thick) and the
 *    caption band are not in config.yaml: VERIFIED constants measured on the stock image.
 * If config.yaml cannot be read or parsed (SELinux enforcing: only system_app may read
 * mnt_xgimiconfig_file), {@link #builtin()} gives the same geometry from the stock-derived facts.
 */
final class AkPatternSpec {
    static final int W = 1920, H = 1080;

    /** DICT_4X4_50 ids 0..3, 4x4 data bits row-major, MSB = top-left, 1 = white (OpenCV). */
    static final int[] ARUCO_4X4_50 = {0xB532, 0x0F9A, 0x332D, 0x9946};

    // ---- stock reference geometry (VERIFIED on ak4_30w.png, see class doc) ----
    static final float REF_X0 = 135, REF_Y0 = 115, REF_STEP = 50;   // top-left of cell (0,0)
    static final int REF_CORNER_COLS = 32, REF_CORNER_ROWS = 16;    // inner-corner lattice
    static final float[][] REF_MARKERS = {                          // left, top, size; ids 0..3
            {728, 364, 160}, {1031, 364, 160}, {1031, 561, 160}, {728, 561, 160}};
    static final float BRACKET_INSET = 48, BRACKET_ARM = 80, BRACKET_THICK = 12;
    /** Caption band (stock: icon 36 px at y 999..1035, text ~32 px, group centred at x ~960). */
    static final float CAPTION_CENTER_X = 960, CAPTION_TOP = 999, CAPTION_ICON = 36,
            CAPTION_TEXT_SIZE = 32, CAPTION_GAP = 16, CAPTION_MAX_W = 1400;

    /** Top-left of board cell (0,0), square size; cells = corners + 1 in each direction. */
    final float x0, y0, step;
    final int cornerCols, cornerRows;
    /** present[i][j]: inner corner (row i, col j) is a keyPoint. */
    final boolean[][] present;
    /** markers[id] = {left, top, size}. */
    final float[][] markers;
    final String source;

    private AkPatternSpec(float x0, float y0, float step, boolean[][] present, float[][] markers,
                          String source) {
        this.x0 = x0;
        this.y0 = y0;
        this.step = step;
        this.present = present;
        this.cornerRows = present.length;
        this.cornerCols = present[0].length;
        this.markers = markers;
        this.source = source;
    }

    /** The stock geometry without config.yaml. */
    static AkPatternSpec builtin() {
        boolean[][] p = new boolean[REF_CORNER_ROWS][REF_CORNER_COLS];
        for (int i = 0; i < REF_CORNER_ROWS; i++) {
            for (int j = 0; j < REF_CORNER_COLS; j++) {
                boolean hole = ((i == 3 || i == 12) && j >= 10 && j <= 21)
                        || (i >= 4 && i <= 11 && j >= 9 && j <= 22);
                p[i][j] = !hole;
            }
        }
        return new AkPatternSpec(REF_X0, REF_Y0, REF_STEP, p, copy(REF_MARKERS), "builtin");
    }

    /**
     * Parses config.yaml text (OpenCV FileStorage YAML: "keyPoints: [ x, y, ... ]" and
     * "arCorners:" followed by 4 "- [ 8 floats ]"). Throws IllegalArgumentException when anything
     * is missing or out of range; the caller then uses {@link #builtin()}.
     */
    static AkPatternSpec fromConfig(String yaml) {
        float[] kp = floatList(yaml, "keyPoints");
        if (kp.length < 40 || (kp.length & 1) != 0) throw new IllegalArgumentException("keyPoints: " + kp.length);
        int n = kp.length / 2;
        // convert to pixel-edge convention (+0.5) and find the lattice step from row neighbours
        float[] xs = new float[n], ys = new float[n];
        for (int k = 0; k < n; k++) { xs[k] = kp[2 * k] + 0.5f; ys[k] = kp[2 * k + 1] + 0.5f; }
        List<Float> dxs = new ArrayList<>();
        for (int k = 0; k + 1 < n; k++) {
            float dx = xs[k + 1] - xs[k];
            if (Math.abs(ys[k + 1] - ys[k]) < 2f && dx > 5f) dxs.add(dx);
        }
        if (dxs.size() < 10) throw new IllegalArgumentException("no lattice rows");
        float[] d = new float[dxs.size()];
        for (int k = 0; k < d.length; k++) d[k] = dxs.get(k);
        Arrays.sort(d);
        float step = Math.round(d[d.length / 2]);
        if (step < 20 || step > 200) throw new IllegalArgumentException("step " + step);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        for (int k = 0; k < n; k++) { minX = Math.min(minX, xs[k]); minY = Math.min(minY, ys[k]); }
        float cx0 = Math.round(minX), cy0 = Math.round(minY);       // first inner corner
        int maxI = 0, maxJ = 0;
        int[][] node = new int[n][];
        for (int k = 0; k < n; k++) {
            float fj = (xs[k] - cx0) / step, fi = (ys[k] - cy0) / step;
            int j = Math.round(fj), i = Math.round(fi);
            if (Math.abs(fj - j) * step <= 2f && Math.abs(fi - i) * step <= 2f && i >= 0 && j >= 0) {
                node[k] = new int[]{i, j};
                maxI = Math.max(maxI, i);
                maxJ = Math.max(maxJ, j);
            }
        }
        int rows = maxI + 1, cols = maxJ + 1;
        if (rows < 4 || cols < 4 || rows > 64 || cols > 64) throw new IllegalArgumentException("lattice " + rows + "x" + cols);
        boolean[][] p = new boolean[rows][cols];
        int on = 0;
        for (int k = 0; k < n; k++) if (node[k] != null && !p[node[k][0]][node[k][1]]) { p[node[k][0]][node[k][1]] = true; on++; }
        if (on < rows * cols / 3) throw new IllegalArgumentException("sparse lattice " + on);
        float x0 = cx0 - step, y0 = cy0 - step;
        if (x0 < 0 || y0 < 0 || x0 + (cols + 1) * step > W || y0 + (rows + 1) * step > H)
            throw new IllegalArgumentException("board outside the frame");

        float[][] quads = arCorners(yaml);
        float[][] mk = new float[4][];
        boolean allRef = true;
        for (int m = 0; m < 4; m++) {
            float[] q = quads[m];
            float qx = 0, qy = 0;
            for (int c = 0; c < 4; c++) { qx += q[2 * c]; qy += q[2 * c + 1]; }
            qx /= 4; qy /= 4;
            float side = (dist(q, 0, 1) + dist(q, 1, 2) + dist(q, 2, 3) + dist(q, 3, 0)) / 4f;
            float size = Math.round(side);
            if (size < 40 || size > 400) throw new IllegalArgumentException("marker " + m + " size " + side);
            float left = Math.round(qx - size / 2f), top = Math.round(qy - size / 2f);
            if (left < 0 || top < 0 || left + size > W || top + size > H) throw new IllegalArgumentException("marker " + m + " outside");
            mk[m] = new float[]{left, top, size};
            float[] r = REF_MARKERS[m];
            float rcx = r[0] + r[2] / 2f, rcy = r[1] + r[2] / 2f;
            if (Math.abs(qx + 0.5f - rcx) > 4 || Math.abs(qy + 0.5f - rcy) > 4 || Math.abs(side - r[2]) > 4) allRef = false;
        }
        String src = "config";
        if (allRef) { mk = copy(REF_MARKERS); src = "config (markers at stock positions)"; }
        return new AkPatternSpec(x0, y0, step, p, mk, src);
    }

    /** True when this lattice equals the stock one (logged by the app as a sanity check). */
    boolean latticeMatchesStock() {
        AkPatternSpec b = builtin();
        return x0 == b.x0 && y0 == b.y0 && step == b.step && Arrays.deepEquals(present, b.present);
    }

    // ------------------------------------------------------------------ drawing primitives
    interface RectSink {
        void rect(float l, float t, float r, float b);
    }

    int cellRows() { return cornerRows + 1; }

    int cellCols() { return cornerCols + 1; }

    /** Board cell (r, c) is black. */
    boolean cellBlack(int r, int c) {
        if (((r + c) & 1) == 0) return false;
        for (int i = r - 1; i <= r; i++) {
            for (int j = c - 1; j <= c; j++) {
                if (i < 0 || j < 0 || i >= cornerRows || j >= cornerCols) return true;  // board edge
                if (present[i][j]) return true;
            }
        }
        return false;                                                // inside the marker hole
    }

    /** Black checker cells. */
    void boardRects(RectSink s) {
        for (int r = 0; r < cellRows(); r++) {
            for (int c = 0; c < cellCols(); c++) {
                if (cellBlack(r, c)) {
                    float l = x0 + c * step, t = y0 + r * step;
                    s.rect(l, t, l + step, t + step);
                }
            }
        }
    }

    /** The four L-shaped corner brackets (black), 2 rects each. */
    static void bracketRects(RectSink s) {
        float i = BRACKET_INSET, a = BRACKET_ARM, k = BRACKET_THICK;
        s.rect(i, i, i + a, i + k);                 s.rect(i, i, i + k, i + a);              // TL
        s.rect(W - i - a, i, W - i, i + k);         s.rect(W - i - k, i, W - i, i + a);      // TR
        s.rect(i, H - i - k, i + a, H - i);         s.rect(i, H - i - a, i + k, H - i);      // BL
        s.rect(W - i - a, H - i - k, W - i, H - i); s.rect(W - i - k, H - i - a, W - i, H - i); // BR
    }

    /** Marker {@code id}: its full black square. */
    void markerSquare(int id, RectSink s) {
        float[] m = markers[id];
        s.rect(m[0], m[1], m[0] + m[2], m[1] + m[2]);
    }

    /** Marker {@code id}: its white data cells (fractional edges; draw as ONE anti-aliased path). */
    void markerWhiteCells(int id, RectSink s) {
        float[] m = markers[id];
        float cell = m[2] / 6f;
        int bits = ARUCO_4X4_50[id];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                if (((bits >> (15 - (r * 4 + c))) & 1) == 1) {
                    float l = m[0] + (c + 1) * cell, t = m[1] + (r + 1) * cell;
                    s.rect(l, t, l + cell, t + cell);
                }
            }
        }
    }

    /**
     * Lumen OS 1.0.1 (4K UI default): the factors {sx, sy} that turn the vendor's 109 corners {@code q}
     * (8 values, x/y pairs, as AkOverlay.parseCorners returns them; null = none) into pixels of a
     * {@code viewW} x {@code viewH} view. Measured at the 1080p UI (friend's Z9X, 2026-10-08): 109
     * "86-180,1800-52,11-1016,1899-1034" was exactly half of the 116 actual_result panel corners
     * (172,360 3600,104 22,2032 3798,2068), i.e. the stock {@link #W} x {@link #H} units: scaled by
     * view / 1920. Whether the vendor keeps those units once the OSD region is 3840 x 2160 (the 4K UI) or
     * then reports OSD = UI pixels is NOT measured. A quad in the stock units lies inside the 1920 x 1080
     * frame (it is where the pattern is drawn); one reaching more than {@value #STOCK_UNITS_SLACK}x beyond
     * it cannot be (the pattern would be off the picture), while UI-pixel corners of a keystone
     * correction always reach past 55 % of a 4K frame. So such a quad is taken as view pixels (1, 1).
     * At the 1080p UI both readings are the same factor.
     */
    static float[] cornerScale(float[] q, int viewW, int viewH) {
        float sx = viewW / (float) W, sy = viewH / (float) H;
        if (q == null || q.length != 8 || (viewW <= W * STOCK_UNITS_SLACK && viewH <= H * STOCK_UNITS_SLACK)) {
            return new float[] {sx, sy};
        }
        float maxX = 0, maxY = 0;
        for (int i = 0; i < 8; i += 2) {
            maxX = Math.max(maxX, q[i]);
            maxY = Math.max(maxY, q[i + 1]);
        }
        return maxX > W * STOCK_UNITS_SLACK || maxY > H * STOCK_UNITS_SLACK ? new float[] {1f, 1f} : new float[] {sx, sy};
    }

    /** {@link #cornerScale}: how far past the stock 1920 x 1080 frame a 109 corner may still be in its units. */
    static final float STOCK_UNITS_SLACK = 1.1f;

    /** Inner checker corners we draw, pixel-centre convention, for the self-check. */
    int keyPointCount() {
        int on = 0;
        for (boolean[] row : present) for (boolean b : row) if (b) on++;
        return on;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "AkPatternSpec[%s board %.0f,%.0f step %.0f %dx%d cells, %d corners, markers %s]",
                source, x0, y0, step, cellCols(), cellRows(), keyPointCount(), Arrays.deepToString(markers));
    }

    // ------------------------------------------------------------------ parsing helpers
    private static float dist(float[] q, int a, int b) {
        float dx = q[2 * a] - q[2 * b], dy = q[2 * a + 1] - q[2 * b + 1];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float[][] copy(float[][] a) {
        float[][] r = new float[a.length][];
        for (int i = 0; i < a.length; i++) r[i] = a[i].clone();
        return r;
    }

    private static float[] floatList(String yaml, String key) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + ":\\s*\\[([^\\]]*)\\]").matcher(yaml);
        if (!m.find()) throw new IllegalArgumentException("no " + key);
        return parseFloats(m.group(1));
    }

    private static float[][] arCorners(String yaml) {
        int at = yaml.indexOf("\narCorners:");
        if (at < 0) throw new IllegalArgumentException("no arCorners");
        Matcher m = Pattern.compile("-\\s*\\[([^\\]]*)\\]").matcher(yaml);
        m.region(at, yaml.length());
        float[][] q = new float[4][];
        for (int i = 0; i < 4; i++) {
            if (!m.find()) throw new IllegalArgumentException("arCorners: " + i + " quads");
            q[i] = parseFloats(m.group(1));
            if (q[i].length != 8) throw new IllegalArgumentException("arCorners[" + i + "]: " + q[i].length);
        }
        return q;
    }

    private static float[] parseFloats(String body) {
        String[] parts = body.split(",");
        float[] out = new float[parts.length];
        int n = 0;
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            float v = Float.parseFloat(t);
            if (Float.isNaN(v) || Float.isInfinite(v)) throw new IllegalArgumentException("bad number " + t);
            out[n++] = v;
        }
        return Arrays.copyOf(out, n);
    }
}
