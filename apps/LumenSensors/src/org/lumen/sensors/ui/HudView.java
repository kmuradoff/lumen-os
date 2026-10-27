package org.lumen.sensors.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.View;

import org.lumen.sensors.Hub;
import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Levels;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Words;

import java.util.ArrayList;
import java.util.Collections;

/**
 * The HUD: ONE Canvas-drawn View in a non-focusable overlay window (HudService). Three modes:
 *  - compact: one rounded strip with up to 7 of the most important values ("label value");
 *  - detailed: group headers with label / value rows of every available value of the enabled groups,
 *    in several columns when one would be taller than 70 % of the screen; a group that goes on in the
 *    next column gets its header again on top of it;
 *  - graphs: a sparkline of the last ~2 minutes for each temperature and the CPU / GPU load, with the
 *    current value and the min / max of that window.
 *
 * Width budget ({@link #setMaxWidth}, from HudService: the space between the safe margins, or right
 * of the settings panel while it is open): when the panel would be wider, the columns first get the
 * full safe height, then everything shrinks by one factor ("fit") until it fits. The fit is worked
 * out again at each setting change and only shrinks while the rows stay the same.
 *
 * Readable over bright video: below 80 % opacity the texts get a soft dark shadow of their own.
 * Stale (Hub.onStale: no publish for a while, the sampler thread is stuck on a HAL call): the values
 * turn faint until the next publish.
 *
 * Fast on weak hardware: the row layout (labels, columns, allocations) is rebuilt only when the set of
 * rows this view draws or a setting changes (a registration or availability change of a row it does
 * not draw only copies values); on a normal publish the view copies the sampler's formatted values
 * into its own arrays (under the registry lock, short), rebuilds the sparkline Paths in place and
 * invalidates. onDraw allocates nothing and takes no lock; requestLayout only when the panel size
 * changes. Value columns only grow while the layout stays the same, so the window does not resize at
 * every refresh. Main thread only.
 */
public final class HudView extends View implements Hub.Listener {
    // design px at size "medium" (scaled by display width / 1920 and the size setting)
    private static final float PAD = 22, RADIUS = 36, RADIUS_COMPACT = 24;
    private static final float LINE = 38, LABEL_SIZE = 23, VALUE_SIZE = 24, HEADER_SIZE = 20, HEADER_LINE = 36,
            GROUP_GAP = 8, COL_GAP = 40, LV_GAP = 24;
    private static final float C_HEIGHT = 56, C_PAD = 24, C_ITEM_GAP = 28, C_LV_GAP = 10;
    private static final float G_WIDTH = 300, G_LABEL_LINE = 32, G_SPARK = 40, G_MINMAX = 24, G_GAP = 16,
            G_SMALL_SIZE = 18, G_STROKE = 2.5f;
    private static final float MAX_HEIGHT = 0.7f;
    private static final int COMPACT_MAX = 7;
    private static final long WINDOW_MS = 120_000;
    /** Text shadow below 80 % opacity (design px), so text stays readable over a bright frame. */
    private static final float SHADOW_RADIUS = 2.5f, SHADOW_DY = 1f;
    private static final int SHADOW_COLOR = 0xCC000000, SHADOW_BELOW_PCT = 80;
    /** Smallest shrink factor for the width budget (text stays legible). */
    private static final float MIN_FIT = 0.5f;

    private static final int E_HEADER = 0, E_ROW = 1, E_COMPACT = 2, E_GRAPH = 3;

    private static final int[] GROUP_LABELS = {R.string.grp_temps, R.string.grp_cooling, R.string.grp_light,
            R.string.grp_cpu, R.string.grp_memory, R.string.grp_network, R.string.grp_picture, R.string.grp_motion,
            R.string.grp_system};

    private final Registry reg;

    // settings
    private int mode = Sampler.VIEW_COMPACT;
    private int groups = Group.ALL;
    private int opacityPct = 80;
    private float sizeScale = 1f;
    private long refreshMs = 2000;
    private float s = 1f, base = 1f, fit = 1f;
    private int screenH = 1080;
    private int maxW = Integer.MAX_VALUE;
    private boolean stale;
    private Words words = Words.english();

    // paints (sizes set in applyScale)
    private final Paint bg = Look.fill(Look.PANEL_BG);
    private final Paint border = Look.stroke(Look.PANEL_STROKE, 1);
    private final TextPaint label = Look.text(10, Look.LABEL, null, false);
    private final TextPaint value = Look.text(10, Look.TEXT, Look.MEDIUM, true);
    private final TextPaint header = Look.text(10, Look.FAINT, Look.BOLD, false);
    private final TextPaint small = Look.text(10, Look.FAINT, null, true);
    private final TextPaint[] texts = {label, value, header, small};
    private final Paint spark = Look.stroke(Look.GRAPH, 2);
    private final Paint guide = Look.stroke(Look.GRAPH_GUIDE, 1);
    private final RectF rect = new RectF();
    private final StringBuilder sb = new StringBuilder(64);
    private final ArrayList<Sample> pickS = new ArrayList<>();
    private final ArrayList<Integer> pickK = new ArrayList<>();

    // ------------------------------------------------------------------ layout model (rebuilt on change)
    private int n;
    private int[] kind = new int[0];
    private int[] headGroup = new int[0];
    private Sample[] sample = new Sample[0];
    private String[] text = new String[0];
    private float[] labelW = new float[0];
    private int[] col = new int[0];
    private float[] ex = new float[0], ey = new float[0];
    private char[][] val = new char[0][];
    private int[] valLen = new int[0], level = new int[0];
    private float[] valW = new float[0], valHW = new float[0];
    // graphs
    private float[][] pts = new float[0][];
    private int[] ptsN = new int[0];
    private Path[] path = new Path[0];
    private char[][] mm = new char[0][];
    private int[] mmLen = new int[0];
    private float[] guideY = new float[0];
    // columns (capacity grows with n, never per call)
    private int cols;
    private float[] colX = new float[0], colW = new float[0], colLabelW = new float[0], colValW = new float[0],
            colHeadW = new float[0];
    /** Detailed: the header entry repeated on top of a column whose first rows go on from the column before, or -1. */
    private int[] colCont = new int[0];
    private float panelW, panelH;
    private int builtVersion = -1;
    private boolean dirty = true;

    public HudView(Context c, Hub hub) {
        super(c);
        this.reg = hub.registry;
        setWillNotDraw(false);
        setFocusable(false);
        spark.setStrokeCap(Paint.Cap.ROUND);
        spark.setStrokeJoin(Paint.Join.ROUND);
        applyScale();
    }

    // ================================================================== settings
    /** Main thread. Every change rebuilds the layout at once (the HUD redraws while the user adjusts). */
    public void applySettings(int mode, int groups, int opacityPct, float sizeScale, long refreshMs) {
        this.mode = mode;
        this.groups = groups;
        this.opacityPct = opacityPct;
        this.sizeScale = sizeScale;
        this.refreshMs = refreshMs;
        relayout();
    }

    /** Display size or language changed. */
    public void onConfigChanged() {
        words = null;
        relayout();
    }

    /** The widest the panel may be, in real px (HudService: safe margins, settings panel). */
    public void setMaxWidth(int px) {
        if (px == maxW) return;
        maxW = px;
        relayout();
    }

    /** Hidden (screen off, standby): forget the rows, so the next show draws nothing old. */
    public void reset() {
        n = 0;
        builtVersion = -1;
        dirty = true;
        stale = false;
        panelW = panelH = 0;
        requestLayout();
        invalidate();
    }

    private void relayout() {
        fit = 1f;
        dirty = true;
        applyScale();
        onSamples();
    }

    private void applyScale() {
        Context c = getContext();
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        screenH = Math.min(m.widthPixels, m.heightPixels);
        base = Look.scale(c);
        s = base * sizeScale * fit;
        label.setTextSize(LABEL_SIZE * s);
        value.setTextSize(VALUE_SIZE * s);
        header.setTextSize(HEADER_SIZE * s);
        small.setTextSize(G_SMALL_SIZE * s);
        border.setStrokeWidth(Math.max(1f, Look.STROKE * s / 2f));
        spark.setStrokeWidth(G_STROKE * s);
        guide.setStrokeWidth(Math.max(1f, s));
        int a = Math.round(((Look.PANEL_BG >>> 24) & 0xff) * opacityPct / 100f);
        bg.setColor((a << 24) | (Look.PANEL_BG & 0x00FFFFFF));
        boolean shadow = opacityPct < SHADOW_BELOW_PCT;
        for (TextPaint p : texts) {
            if (shadow) p.setShadowLayer(SHADOW_RADIUS * s, 0, SHADOW_DY * s, SHADOW_COLOR);
            else p.clearShadowLayer();
        }
    }

    // ================================================================== data
    @Override
    public void onSamples() {
        stale = false;
        float w0 = panelW, h0 = panelH;
        if (dirty || reg.layoutVersion() != builtVersion) {
            rebuild();
        } else {
            if (copyValues()) layoutEntries();
            updateGraphs();
        }
        if (panelW != w0 || panelH != h0) requestLayout();
        invalidate();
    }

    @Override
    public void onStale() {
        if (stale) return;
        stale = true;
        invalidate();
    }

    private void rebuild() {
        boolean full = dirty;
        dirty = false;
        if (words == null) words = wordsOf(getResources());
        pickS.clear();
        pickK.clear();
        synchronized (reg.lock) {
            builtVersion = reg.layoutVersion();
            ArrayList<Sample> all = reg.samplesLocked();
            if (mode == Sampler.VIEW_DETAILED) {
                int lastGroup = -1;
                for (int i = 0; i < all.size(); i++) {
                    Sample x = all.get(i);
                    if (!x.available || (x.modes & Sample.MODE_DETAILED) == 0 || !Group.in(groups, x.group)) continue;
                    if (x.group != lastGroup) {
                        lastGroup = x.group;
                        pickS.add(null);
                        pickK.add(x.group);
                    }
                    pickS.add(x);
                    pickK.add(-1);
                }
            } else if (mode == Sampler.VIEW_GRAPHS) {
                for (int i = 0; i < all.size(); i++) {
                    Sample x = all.get(i);
                    if (x.available && x.hist != null && (x.modes & Sample.MODE_GRAPH) != 0 && Group.in(groups, x.group)) {
                        pickS.add(x);
                        pickK.add(-1);
                    }
                }
            } else {
                for (int i = 0; i < all.size(); i++) {
                    Sample x = all.get(i);
                    if (x.available && x.compactRank > 0 && (x.modes & Sample.MODE_COMPACT) != 0
                            && Group.in(groups, x.group)) {
                        pickS.add(x);
                    }
                }
                Collections.sort(pickS, (a, b) -> Integer.compare(a.compactRank, b.compactRank));
                while (pickS.size() > COMPACT_MAX) pickS.remove(pickS.size() - 1);
                for (int i = 0; i < pickS.size(); i++) pickK.add(-1);
            }
        }
        if (!full && samePicks()) {
            // a registration or availability change of a row this view does not draw: nothing to rebuild
            if (copyValues()) layoutEntries();
            updateGraphs();
            return;
        }
        allocate(pickS.size());
        Resources r = getResources();
        for (int i = 0; i < n; i++) {
            Sample x = pickS.get(i);
            sample[i] = x;
            if (x == null) {
                kind[i] = E_HEADER;
                headGroup[i] = pickK.get(i);
                text[i] = r.getString(GROUP_LABELS[headGroup[i]]);
            } else {
                kind[i] = mode == Sampler.VIEW_DETAILED ? E_ROW : (mode == Sampler.VIEW_GRAPHS ? E_GRAPH : E_COMPACT);
                headGroup[i] = -1;
                text[i] = labelOf(r, x, kind[i] == E_COMPACT);
                if (kind[i] == E_GRAPH) {
                    pts[i] = new float[Sample.HIST];
                    path[i] = new Path();
                    mm[i] = new char[64];
                }
            }
        }
        measureLabels();
        copyValues();
        layoutEntries();
        updateGraphs();
    }

    /** The rows just picked are the ones laid out (same samples, same headers, same order). */
    private boolean samePicks() {
        if (pickS.size() != n) return false;
        for (int i = 0; i < n; i++) {
            Sample x = pickS.get(i);
            if (x != sample[i]) return false;
            if (x == null && pickK.get(i) != headGroup[i]) return false;
        }
        return true;
    }

    private void allocate(int count) {
        n = count;
        kind = new int[count];
        headGroup = new int[count];
        sample = new Sample[count];
        text = new String[count];
        labelW = new float[count];
        col = new int[count];
        ex = new float[count];
        ey = new float[count];
        val = new char[count][48];
        valLen = new int[count];
        level = new int[count];
        valW = new float[count];
        valHW = new float[count];
        pts = new float[count][];
        ptsN = new int[count];
        path = new Path[count];
        mm = new char[count][];
        mmLen = new int[count];
        guideY = new float[count];
    }

    private void measureLabels() {
        for (int i = 0; i < n; i++) labelW[i] = (kind[i] == E_HEADER ? header : label).measureText(text[i]);
    }

    /** Copies the sampler's formatted values; true when a value became wider than its column. */
    private boolean copyValues() {
        int window = windowPoints();
        synchronized (reg.lock) {
            for (int i = 0; i < n; i++) {
                Sample x = sample[i];
                if (x == null) continue;
                int len = Math.min(x.len, val[i].length);
                System.arraycopy(x.chars, 0, val[i], 0, len);
                valLen[i] = x.available ? len : 0;
                level[i] = x.level;
                if (pts[i] != null && x.hist != null) {
                    int m = Math.min(x.histSize, window);
                    for (int k = 0; k < m; k++) pts[i][k] = x.histAt(m, k);
                    ptsN[i] = m;
                }
            }
        }
        boolean grew = false;
        for (int i = 0; i < n; i++) {
            if (sample[i] == null) continue;
            valW[i] = value.measureText(val[i], 0, valLen[i]);
            if (valW[i] > valHW[i] + 0.5f) {
                valHW[i] = valW[i];
                grew = true;
            }
        }
        return grew;
    }

    private int windowPoints() {
        long r = Math.max(500, refreshMs);
        return (int) Math.max(2, Math.min(Sample.HIST, WINDOW_MS / r));
    }

    // ================================================================== layout
    /**
     * Positions every entry and sets panelW / panelH, inside the width budget: the normal layout first,
     * then (detailed, graphs) columns of the full safe height, then the largest shrink factor that fits
     * (a short bisection between MIN_FIT and the current fit: fewer columns at a smaller size means the
     * width does not shrink in proportion). Only on a rebuild or when a value outgrows its column.
     */
    private void layoutEntries() {
        if (n == 0) {
            panelW = panelH = 0;
            return;
        }
        layoutPass(false);
        if (panelW <= maxW) return;
        boolean tall = mode != Sampler.VIEW_COMPACT;
        if (tall) {
            layoutPass(true);
            if (panelW <= maxW) return;
        }
        float bad = fit, good = Math.min(MIN_FIT, fit);
        setFit(good, tall);
        if (panelW > maxW) return;                      // even the smallest does not fit: keep the smallest
        for (int k = 0; k < 5; k++) {
            float mid = (good + bad) / 2f;
            setFit(mid, tall);
            if (panelW <= maxW) good = mid; else bad = mid;
        }
        if (fit != good) setFit(good, tall);
    }

    /** Applies a shrink factor: text sizes, label widths, value widths (high-water scaled), one pass. */
    private void setFit(float f, boolean tall) {
        float old = s;
        fit = f;
        applyScale();
        float ratio = s / old;
        measureLabels();
        for (int i = 0; i < n; i++) {
            if (sample[i] == null) continue;
            valW[i] = value.measureText(val[i], 0, valLen[i]);
            valHW[i] = Math.max(valHW[i] * ratio, valW[i]);
        }
        layoutPass(tall);
    }

    private void layoutPass(boolean tall) {
        float pad = PAD * s;
        if (mode == Sampler.VIEW_COMPACT) {
            float x = C_PAD * s;
            for (int i = 0; i < n; i++) {
                ex[i] = x;
                ey[i] = 0;
                x += labelW[i] + C_LV_GAP * s + valHW[i] + C_ITEM_GAP * s;
            }
            panelW = x - C_ITEM_GAP * s + C_PAD * s;
            panelH = C_HEIGHT * s;
            return;
        }
        float maxH = (tall ? screenH - 2 * Look.SAFE * base : screenH * MAX_HEIGHT) - 2 * pad;
        if (mode == Sampler.VIEW_GRAPHS) {
            float gh = (G_LABEL_LINE + G_SPARK + G_MINMAX) * s, gap = G_GAP * s, gw = G_WIDTH * s;
            int perCol = Math.max(1, (int) ((maxH + gap) / (gh + gap)));
            cols = (n + perCol - 1) / perCol;
            for (int i = 0; i < n; i++) {
                int c = i / perCol, row = i % perCol;
                col[i] = c;
                ex[i] = pad + c * (gw + COL_GAP * s);
                ey[i] = pad + row * (gh + gap);
            }
            int rows = Math.min(n, perCol);
            panelW = 2 * pad + cols * gw + (cols - 1) * COL_GAP * s;
            panelH = 2 * pad + rows * gh + (rows - 1) * gap;
            return;
        }
        // detailed: fill columns top-down; a header never ends a column, and a group that goes on in the
        // next column gets its header again on top of it
        if (colX.length < n) {
            colX = new float[n];
            colW = new float[n];
            colLabelW = new float[n];
            colValW = new float[n];
            colHeadW = new float[n];
            colCont = new int[n];
        }
        float line = LINE * s, hl = HEADER_LINE * s, gg = GROUP_GAP * s;
        float y = 0, tallest = 0;
        int c = 0, lastHeader = -1;
        colCont[0] = -1;
        for (int i = 0; i < n; i++) {
            float h;
            if (kind[i] == E_HEADER) {
                float gapAbove = y > 0 ? gg : 0;
                if (y > 0 && y + gapAbove + hl + line > maxH) {
                    c++;
                    colCont[c] = -1;
                    y = 0;
                    gapAbove = 0;
                }
                ey[i] = y + gapAbove;
                h = gapAbove + hl;
                lastHeader = i;
            } else {
                if (y > 0 && y + line > maxH) {
                    c++;
                    colCont[c] = lastHeader;
                    y = lastHeader >= 0 ? hl : 0;
                }
                ey[i] = y;
                h = line;
            }
            col[i] = c;
            y += h;
            tallest = Math.max(tallest, y);
        }
        cols = c + 1;
        for (int k = 0; k < cols; k++) {
            colLabelW[k] = 0;
            colValW[k] = 0;
            colHeadW[k] = colCont[k] >= 0 ? labelW[colCont[k]] : 0;
        }
        for (int i = 0; i < n; i++) {
            int k = col[i];
            if (kind[i] == E_HEADER) {
                colHeadW[k] = Math.max(colHeadW[k], labelW[i]);
            } else {
                colLabelW[k] = Math.max(colLabelW[k], labelW[i]);
                colValW[k] = Math.max(colValW[k], valHW[i]);
            }
        }
        float x = pad;
        for (int k = 0; k < cols; k++) {
            colW[k] = Math.max(colLabelW[k] + LV_GAP * s + colValW[k], colHeadW[k]);
            colX[k] = x;
            x += colW[k] + COL_GAP * s;
        }
        for (int i = 0; i < n; i++) {
            ex[i] = colX[col[i]];
            ey[i] += pad;
        }
        panelW = x - COL_GAP * s + pad;
        panelH = tallest + 2 * pad;
    }

    /** Sparkline Paths and min / max texts from the copied history (graphs mode). */
    private void updateGraphs() {
        if (mode != Sampler.VIEW_GRAPHS) return;
        float gw = G_WIDTH * s, sh = G_SPARK * s, inset = G_STROKE * s;
        int window = windowPoints();
        for (int i = 0; i < n; i++) {
            Path p = path[i];
            if (p == null) continue;
            p.rewind();
            mmLen[i] = 0;
            guideY[i] = Float.NaN;
            int m = ptsN[i];
            if (m <= 0) continue;
            float[] v = pts[i];
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int k = 0; k < m; k++) {
                lo = Math.min(lo, v[k]);
                hi = Math.max(hi, v[k]);
            }
            float min = lo, max = hi;
            Sample x = sample[i];
            if (x.format == Fmt.PERCENT) {
                lo = 0;
                hi = 100;
            } else {
                lo -= 2;
                hi += 2;
                if (hi - lo < 6) {
                    float mid = (hi + lo) / 2;
                    lo = mid - 3;
                    hi = mid + 3;
                }
            }
            float top = ey[i] + G_LABEL_LINE * s + inset, bottom = ey[i] + G_LABEL_LINE * s + sh - inset;
            float x1 = ex[i] + gw - inset;
            float step = (gw - 2 * inset) / (window - 1);
            for (int k = 0; k < m; k++) {
                float px = x1 - (m - 1 - k) * step;
                float py = bottom - (v[k] - lo) / (hi - lo) * (bottom - top);
                if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
            }
            if (m == 1) p.lineTo(x1, bottom - (v[0] - lo) / (hi - lo) * (bottom - top));
            float warm = Levels.warmAt(x.kind);
            if (!Float.isNaN(warm) && warm > lo && warm < hi) {
                guideY[i] = bottom - (warm - lo) / (hi - lo) * (bottom - top);
            }
            sb.setLength(0);
            sb.append(words.get(Words.MIN_W)).append(' ');
            Fmt.value(sb, x.format, min, 0, 0, 0, null, words);
            sb.append("   ").append(words.get(Words.MAX_W)).append(' ');
            Fmt.value(sb, x.format, max, 0, 0, 0, null, words);
            int len = Math.min(sb.length(), mm[i].length);
            sb.getChars(0, len, mm[i], 0);
            mmLen[i] = len;
        }
    }

    // ================================================================== View
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (n == 0) {
            setMeasuredDimension(1, 1);
            return;
        }
        setMeasuredDimension((int) Math.ceil(panelW), (int) Math.ceil(panelH));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) updateGraphs();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (n == 0) return;
        float radius = (mode == Sampler.VIEW_COMPACT ? RADIUS_COMPACT : RADIUS) * s;
        rect.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(rect, radius, radius, bg);
        float bw = border.getStrokeWidth() / 2f;
        rect.inset(bw, bw);
        c.drawRoundRect(rect, radius, radius, border);

        if (mode == Sampler.VIEW_COMPACT) {
            float cy = getHeight() / 2f;
            float lb = cy - (label.ascent() + label.descent()) / 2f, vb = cy - (value.ascent() + value.descent()) / 2f;
            for (int i = 0; i < n; i++) {
                c.drawText(text[i], ex[i], lb, label);
                value.setColor(valueColor(i));
                c.drawText(val[i], 0, valLen[i], ex[i] + labelW[i] + C_LV_GAP * s, vb, value);
            }
            return;
        }
        if (mode == Sampler.VIEW_GRAPHS) {
            float gw = G_WIDTH * s;
            for (int i = 0; i < n; i++) {
                float mid = ey[i] + G_LABEL_LINE * s / 2f;
                c.drawText(text[i], ex[i], mid - (label.ascent() + label.descent()) / 2f, label);
                value.setColor(valueColor(i));
                c.drawText(val[i], 0, valLen[i], ex[i] + gw - valW[i], mid - (value.ascent() + value.descent()) / 2f, value);
                if (!Float.isNaN(guideY[i])) c.drawLine(ex[i], guideY[i], ex[i] + gw, guideY[i], guide);
                spark.setColor(stale ? Look.FAINT : (level[i] == Levels.NORMAL ? Look.GRAPH : Look.levelColor(level[i])));
                if (path[i] != null) c.drawPath(path[i], spark);
                float mmMid = ey[i] + (G_LABEL_LINE + G_SPARK) * s + G_MINMAX * s / 2f;
                c.drawText(mm[i], 0, mmLen[i], ex[i], mmMid - (small.ascent() + small.descent()) / 2f, small);
            }
            return;
        }
        float line = LINE * s, hl = HEADER_LINE * s;
        float lOff = -(label.ascent() + label.descent()) / 2f, vOff = -(value.ascent() + value.descent()) / 2f;
        float hOff = -(header.ascent() + header.descent()) / 2f;
        float pad = PAD * s;
        for (int k = 0; k < cols; k++) {                   // a group going on from the column before
            int h = colCont[k];
            if (h >= 0) c.drawText(text[h], colX[k], pad + hl / 2f + hOff, header);
        }
        for (int i = 0; i < n; i++) {
            if (kind[i] == E_HEADER) {
                c.drawText(text[i], ex[i], ey[i] + hl / 2f + hOff, header);
                continue;
            }
            float mid = ey[i] + line / 2f;
            c.drawText(text[i], ex[i], mid + lOff, label);
            value.setColor(valueColor(i));
            float right = ex[i] + colW[col[i]];
            c.drawText(val[i], 0, valLen[i], right - valW[i], mid + vOff, value);
        }
    }

    // ================================================================== helpers
    /** Level colour, or faint while the values are stale. */
    private int valueColor(int i) {
        return stale ? Look.FAINT : Look.levelColor(level[i]);
    }

    private static String labelOf(Resources r, Sample x, boolean compact) {
        if (compact && x.shortRes != 0) return r.getString(x.shortRes);
        if (x.labelRes == 0) return x.id;
        if (x.labelArgStr != null) return r.getString(x.labelRes, x.labelArgStr);
        if (x.labelArg >= 0) return r.getString(x.labelRes, x.labelArg);
        return r.getString(x.labelRes);
    }

    private static Words wordsOf(Resources r) {
        return Hub.words(r);
    }
}
