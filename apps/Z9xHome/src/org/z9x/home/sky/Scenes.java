package org.z9x.home.sky;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The six landscapes, drawn with Paths in design px (1920x1080) into the land layer on the render
 * thread. Geometry is built once per scene from fixed seeds (every render matches), colours come from
 * {@link SkyLook} on every render (daylight, season, weather). Shapes: mountains, sea, hills and city
 * follow the prototype D_Live, the dunes the screensaver D_Saver, the forest the D_Walls sheet.
 * Only the render thread uses an instance.
 */
final class Scenes {
    /** Nothing of any land is higher than this; the land layer starts here. */
    static final float LAND_TOP = 460;
    private static final float LAKE_Y = 800;

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mTmp = new Path();
    private final RectF mR = new RectF();

    Scenes() {
        mFill.setDither(true);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mLine.setStrokeJoin(Paint.Join.ROUND);
    }

    void draw(Canvas c, SkyLook k) {
        switch (k.scene) {
            case SkyLook.MOUNTAINS: mountains(c, k); break;
            case SkyLook.SEA: sea(c, k); break;
            case SkyLook.HILLS: hills(c, k); break;
            case SkyLook.CITY: city(c, k); break;
            case SkyLook.DUNES: dunes(c, k); break;
            default: forest(c, k);
        }
        mFill.setShader(null);
        mFill.setAlpha(255);
    }

    /** Where the light comes from (x), for the shaded side of peaks and dunes. */
    private static float lightX(SkyLook k) {
        if (k.el > -6) return k.sunX;
        if (k.moonEl > 0) return k.moonX;
        return 960;
    }

    // ------------------------------------------------------------------ mountains and lake

    private static final float[] FAR = {0, 640, 170, 552, 330, 600, 520, 500, 705, 585, 870, 535, 1050, 600,
            1220, 515, 1410, 595, 1570, 545, 1750, 600, 1920, 560};
    private static final float[] MID = {0, 742, 230, 660, 390, 706, 610, 626, 810, 700, 1000, 650, 1190, 722,
            1360, 668, 1550, 732, 1730, 672, 1920, 722};
    private static final int SUB = 3, STEP = 1 << SUB;
    private float[] mFarRidge, mMidRidge, mRipples, mBankTrees;
    private Path mFarPath, mMidPath, mBankL, mBankR, mShore, mBankTreePath;

    private void buildMountains() {
        if (mFarPath != null) return;
        Rnd r = new Rnd(7);
        mFarRidge = subdivide(FAR, SUB, 0.07f, r);
        mMidRidge = subdivide(MID, SUB, 0.06f, r);
        mFarPath = ridgePath(mFarRidge, 820);
        mMidPath = ridgePath(mMidRidge, 820);
        mBankL = svg("M0 1080 L0 930 C220 900 380 940 560 960 C700 975 760 1020 800 1080 Z");
        mBankR = svg("M1920 1080 L1920 960 C1760 930 1600 960 1460 1000 C1380 1025 1340 1050 1320 1080 Z");
        mShore = svg("M0 800 L1920 800 L1920 812 Q960 806 0 812 Z");
        int n = 22;
        mRipples = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float f = i / (float) n;
            float w = (60 + r.next() * 300) * (0.4f + f);
            mRipples[i * 4] = r.next() * 2000 - 80;
            mRipples[i * 4 + 1] = LAKE_Y + 8 + (float) Math.pow(f, 1.6) * 270;
            mRipples[i * 4 + 2] = w;
            mRipples[i * 4 + 3] = 1.2f + f * 1.4f;
        }
        // a few spruces on the near banks, for scale
        mBankTreePath = new Path();
        float[][] at = {{70, 930}, {118, 924}, {170, 921}, {236, 922}, {1730, 945}, {1790, 940}, {1856, 944}};
        for (float[] p : at) {
            float h = r.range(70, 130);
            conifer(mBankTreePath, p[0] + r.range(-8, 8), p[1] + 12, h, h * 0.4f, r);
        }
    }

    private void mountains(Canvas c, SkyLook k) {
        buildMountains();
        float lx = lightX(k);
        int shade = SkyLook.alpha(0xFF000000, 0.1f * k.landLight);

        fill(c, mFarPath, k.far);
        facets(c, mFarRidge, FAR.length / 2, 820, lx, shade);
        caps(c, k, mFarRidge, FAR.length / 2, mFarPath);
        fill(c, mMidPath, k.mid);

        // the lake mirrors the sky (prototype lw-mirror at 0.92), then the ranges upside down
        mFill.setShader(new LinearGradient(0, LAKE_Y, 0, 1080, new int[]{k.skyLow, k.skyMid, k.skyTop},
                new float[]{0, 0.5f, 1}, Shader.TileMode.CLAMP));
        mFill.setAlpha(235);
        c.drawRect(0, LAKE_Y, 1920, 1080, mFill);
        mFill.setShader(null);
        mFill.setAlpha(255);
        c.save();
        c.clipRect(0, LAKE_Y, 1920, 1080);
        c.scale(1, -1, 0, LAKE_Y);
        fill(c, mFarPath, SkyLook.alpha(SkyLook.mix(k.far, k.skyLow, 0.35f), 0.55f));
        fill(c, mMidPath, SkyLook.alpha(SkyLook.mix(k.mid, k.skyLow, 0.25f), 0.7f));
        c.restore();
        // wind ripples break the mirror
        mFill.setColor(SkyLook.alpha(SkyLook.mix(k.skyLow, 0xFFFFFFFF, 0.15f), 0.05f + 0.06f * k.light));
        for (int i = 0; i < mRipples.length; i += 4) {
            float x = mRipples[i], y = mRipples[i + 1], w = mRipples[i + 2], h = mRipples[i + 3];
            mR.set(x, y, x + w, y + h);
            c.drawRoundRect(mR, h / 2, h / 2, mFill);
        }
        fill(c, mShore, SkyLook.alpha(SkyLook.mix(k.mid, k.near, 0.5f), 0.9f));
        fill(c, mBankL, k.near);
        fill(c, mBankR, k.near);
        fill(c, mBankTreePath, k.conifer);
    }

    /** Low-poly shading: the flank of each peak that faces away from the light is a bit darker. */
    private void facets(Canvas c, float[] ridge, int nOrig, float base, float lightX, int color) {
        if ((color >>> 24) < 2) return;
        mFill.setColor(color);
        for (int j = 1; j < nOrig - 1; j++) {
            int pi = j * STEP;
            float px = ridge[pi * 2], py = ridge[pi * 2 + 1];
            if (!(py < ridge[(pi - STEP) * 2 + 1] && py < ridge[(pi + STEP) * 2 + 1])) continue;
            int dir = lightX < px ? 1 : -1;
            int vi = pi + dir * STEP;
            Path p = mTmp;
            p.rewind();
            p.moveTo(px, py);
            for (int i = pi + dir; i != vi + dir; i += dir) p.lineTo(ridge[i * 2], ridge[i * 2 + 1]);
            p.lineTo(px + (ridge[vi * 2] - px) * 0.3f, base);
            p.close();
            c.drawPath(p, mFill);
        }
    }

    /** Snow caps on the high peaks, deeper in winter and while it snows, clipped to the range. */
    private void caps(Canvas c, SkyLook k, float[] ridge, int nOrig, Path clip) {
        float depth = 70 * k.capScale;
        int n = ridge.length / 2;
        Rnd r = new Rnd(31);
        c.save();
        c.clipPath(clip);
        mFill.setColor(k.cap);
        for (int j = 1; j < nOrig - 1; j++) {
            int pi = j * STEP;
            float py = ridge[pi * 2 + 1];
            if (py > 560 || !(py < ridge[(pi - STEP) * 2 + 1] && py < ridge[(pi + STEP) * 2 + 1])) continue;
            float limit = py + depth;
            int a = pi;
            while (a > 0 && ridge[(a - 1) * 2 + 1] < limit) a--;
            int b = pi;
            while (b < n - 1 && ridge[(b + 1) * 2 + 1] < limit) b++;
            float lx = cross(ridge, a, a - 1, limit, true), ly = cross(ridge, a, a - 1, limit, false);
            float rx = cross(ridge, b, b + 1, limit, true), ry = cross(ridge, b, b + 1, limit, false);
            Path p = mTmp;
            p.rewind();
            p.moveTo(lx, ly);
            for (int i = a; i <= b; i++) p.lineTo(ridge[i * 2], ridge[i * 2 + 1]);
            p.lineTo(rx, ry);
            int jags = 6;
            for (int s = jags - 1; s >= 1; s--) {
                float f = s / (float) jags;
                float x = lx + (rx - lx) * f;
                float y = ly + (ry - ly) * f + ((s & 1) == 0 ? -1 : 1) * depth * r.range(0.12f, 0.3f);
                p.lineTo(x, y);
            }
            p.close();
            c.drawPath(p, mFill);
        }
        c.restore();
    }

    /** Point where the ridge segment from inside (y < limit) to outside crosses y = limit. */
    private static float cross(float[] ridge, int in, int out, float limit, boolean wantX) {
        int n = ridge.length / 2;
        if (out < 0 || out >= n) return wantX ? ridge[in * 2] : ridge[in * 2 + 1];
        float x0 = ridge[in * 2], y0 = ridge[in * 2 + 1], x1 = ridge[out * 2], y1 = ridge[out * 2 + 1];
        float t = y1 == y0 ? 0 : (limit - y0) / (y1 - y0);
        return wantX ? x0 + (x1 - x0) * t : limit;
    }

    // ------------------------------------------------------------------ sea

    private Path mHeadL, mHeadR;
    private float[] mSwell;

    private void buildSea() {
        if (mHeadL != null) return;
        Rnd r = new Rnd(17);
        mHeadL = ridgePath(subdivide(new float[]{0, 690, 140, 662, 260, 672, 360, 652, 470, 680, 560, 690}, 2, 0.05f, r), 692);
        mHeadR = ridgePath(subdivide(new float[]{1500, 690, 1610, 668, 1720, 674, 1830, 656, 1920, 662}, 2, 0.05f, r), 692);
        int n = 28;
        mSwell = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float f = (i + 1) / (float) n;
            mSwell[i * 4] = r.next() * 2000 - 100;
            mSwell[i * 4 + 1] = 694 + (float) Math.pow(f, 1.8) * 380;
            mSwell[i * 4 + 2] = (40 + r.next() * 160) * (0.3f + 1.7f * f);
            mSwell[i * 4 + 3] = 1 + 2.2f * f;
        }
    }

    private void sea(Canvas c, SkyLook k) {
        buildSea();
        int head = SkyLook.mix(k.far, k.skyLow, 0.3f);
        fill(c, mHeadL, head);
        fill(c, mHeadR, head);
        mFill.setShader(new LinearGradient(0, 690, 0, 1080, k.seaTop, k.seaBottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 690, 1920, 1080, mFill);
        // haze where the sea meets the sky
        mFill.setShader(new LinearGradient(0, 689, 0, 736, SkyLook.alpha(k.skyLow, 0.35f), SkyLook.alpha(k.skyLow, 0),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 689, 1920, 736, mFill);
        mFill.setShader(null);
        // swell: light crests and dark troughs, wider towards the viewer
        int crest = SkyLook.alpha(SkyLook.mix(k.skyMid, k.skyLow, 0.5f), 0.05f + 0.05f * k.light);
        int trough = SkyLook.alpha(k.seaBottom, 0.16f);
        for (int i = 0; i < mSwell.length; i += 4) {
            float x = mSwell[i], y = mSwell[i + 1], w = mSwell[i + 2], h = mSwell[i + 3];
            mFill.setColor((i & 4) == 0 ? crest : trough);
            mR.set(x, y, x + w, y + h);
            c.drawRoundRect(mR, h / 2, h / 2, mFill);
        }
    }

    // ------------------------------------------------------------------ hills with a lone tree

    private Path mHillFar, mHillMid, mHillNear;
    private float[] mBranch;   // x0, y0, x1, y1, width, depth
    private float[] mCrown;    // cx, cy, r
    private int mBranchN, mCrownN;

    private void buildHills() {
        if (mHillFar != null) return;
        mHillFar = svg("M0 760 C300 690 560 700 820 740 C1080 780 1340 690 1620 700 C1760 705 1860 730 1920 740 L1920 1080 L0 1080 Z");
        mHillMid = svg("M0 860 C260 800 520 820 760 860 C1020 905 1260 820 1520 830 C1700 838 1820 860 1920 870 L1920 1080 L0 1080 Z");
        mHillNear = svg("M0 980 C320 930 640 950 960 985 C1240 1015 1560 950 1920 975 L1920 1080 L0 1080 Z");
        mBranch = new float[64 * 6];
        mCrown = new float[64 * 3];
        Rnd r = new Rnd(5);
        addBranch(1386, 852, 1391, 776, 9, -1);
        branch(1391, 776, -Math.PI / 2, 30, 6.5f, 0, r);
    }

    private void addBranch(float x0, float y0, float x1, float y1, float w, int depth) {
        if (mBranchN * 6 + 6 > mBranch.length) return;
        int i = mBranchN++ * 6;
        mBranch[i] = x0;
        mBranch[i + 1] = y0;
        mBranch[i + 2] = x1;
        mBranch[i + 3] = y1;
        mBranch[i + 4] = w;
        mBranch[i + 5] = depth;
    }

    private void branch(float x, float y, double ang, float len, float w, int depth, Rnd r) {
        float x1 = x + (float) Math.cos(ang) * len, y1 = y + (float) Math.sin(ang) * len;
        addBranch(x, y, x1, y1, w, depth);
        if (depth >= 2 && mCrownN * 3 + 3 <= mCrown.length) {
            int i = mCrownN++ * 3;
            mCrown[i] = x1 + r.range(-6, 6);
            mCrown[i + 1] = y1 + r.range(-4, 6);
            mCrown[i + 2] = depth == 2 ? r.range(20, 27) : r.range(13, 20);
        }
        if (depth == 4) return;
        int n = depth == 0 ? 3 : 2;
        for (int i = 0; i < n; i++) {
            double spread = (n == 3 ? (i - 1) * 0.6 : (i == 0 ? -0.45 : 0.45)) + (r.next() - 0.5) * 0.3;
            branch(x1, y1, ang + spread, len * r.range(0.68f, 0.8f), w * 0.62f, depth + 1, r);
        }
    }

    private void hills(Canvas c, SkyLook k) {
        buildHills();
        vfill(c, mHillFar, SkyLook.mix(k.far, k.skyLow, 0.18f), k.far, 690, 900);
        vfill(c, mHillMid, k.mid, SkyLook.mix(k.mid, 0xFF000000, 0.18f), 800, 1080);
        vfill(c, mHillNear, k.near, SkyLook.mix(k.near, 0xFF000000, 0.2f), 930, 1080);

        // the lone tree: bare in winter, a crown the rest of the year (thinner in spring)
        float[] s = k.season;
        boolean bare = s[SkyLook.WINTER] >= 0.5f;
        float other = s[SkyLook.SPRING] + s[SkyLook.SUMMER] + s[SkyLook.AUTUMN];
        float dense = other <= 0 ? 1 : (0.6f * s[SkyLook.SPRING] + s[SkyLook.SUMMER] + 0.8f * s[SkyLook.AUTUMN]) / other;
        mLine.setColor(k.tree);
        for (int i = 0; i < mBranchN; i++) {
            int b = i * 6;
            if (!bare && mBranch[b + 5] > 1) continue;
            mLine.setStrokeWidth(Math.max(1f, mBranch[b + 4]));
            c.drawLine(mBranch[b], mBranch[b + 1], mBranch[b + 2], mBranch[b + 3], mLine);
        }
        if (!bare) {
            mFill.setColor(k.tree);
            int n = Math.round(mCrownN * dense);
            float rs = 0.75f + 0.25f * dense;
            for (int i = 0; i < n; i++) {
                c.drawCircle(mCrown[i * 3], mCrown[i * 3 + 1], mCrown[i * 3 + 2] * rs, mFill);
            }
        }
    }

    // ------------------------------------------------------------------ city

    /** City layout, shared with the view (aviation lights); immutable once built. */
    static final class City {
        final float[] towers;   // x, top, w, h, shade
        final float[] far;      // x, top, w, h
        final float[] win;      // x, y, threshold, opacity, late, warmth
        final float[] masts;    // x, top, h
        final float[] lights;   // x, y, phase (aviation lights on the tallest masts)

        private City(float[] t, float[] f, float[] w, float[] m, float[] l) {
            towers = t;
            far = f;
            win = w;
            masts = m;
            lights = l;
        }
    }

    private static City sCity;

    static synchronized City city() {
        if (sCity != null) return sCity;
        Rnd r = new Rnd(11);
        FloatList t = new FloatList(), w = new FloatList(), m = new FloatList(), f = new FloatList();
        float x = 0;
        while (x < 1920) {
            float tw = 70 + Math.round(r.next() * 90), th = 160 + Math.round(r.next() * 300);
            t.add(x, 1000 - th, tw - 8, th, r.range(-0.07f, 0.07f));
            for (float wy = 1000 - th + 24; wy < 990; wy += 26) {
                for (float wx = x + 12; wx < x + tw - 20; wx += 20) {
                    w.add(wx, wy, r.next(), 0.55f + r.next() * 0.45f, r.next(), r.next());
                }
            }
            if (th > 380 && r.next() < 0.7f) m.add(x + (tw - 8) / 2f - 1.5f, 1000 - th, r.range(24, 46));
            x += tw;
        }
        Rnd r2 = new Rnd(23);
        x = -10;
        while (x < 1940) {
            float fw = 30 + r2.next() * 70, fh = 120 + r2.next() * 240;
            f.add(x, 1000 - fh, fw - 3, fh);
            x += fw;
        }
        // lights on the three highest masts
        float[] ms = m.toArray();
        int nm = ms.length / 3;
        FloatList l = new FloatList();
        boolean[] used = new boolean[nm];
        for (int k = 0; k < Math.min(3, nm); k++) {
            int best = -1;
            for (int i = 0; i < nm; i++) {
                if (!used[i] && (best < 0 || ms[i * 3 + 1] - ms[i * 3 + 2] < ms[best * 3 + 1] - ms[best * 3 + 2])) best = i;
            }
            used[best] = true;
            l.add(ms[best * 3] + 1.5f, ms[best * 3 + 1] - ms[best * 3 + 2], k * 0.53f);
        }
        sCity = new City(t.toArray(), f.toArray(), w.toArray(), ms, l.toArray());
        return sCity;
    }

    private Path mCityGround;

    private void city(Canvas c, SkyLook k) {
        if (mCityGround == null) {
            mCityGround = svg("M0 760 C400 740 800 750 1200 744 C1500 740 1760 750 1920 748 L1920 1080 L0 1080 Z");
        }
        City g = city();
        fill(c, mCityGround, k.far);
        mFill.setColor(SkyLook.mix(k.cityFar, k.skyLow, 0.35f));
        for (int i = 0; i < g.far.length; i += 4) c.drawRect(g.far[i], g.far[i + 1], g.far[i] + g.far[i + 2], 1000, mFill);
        float lx = lightX(k);
        int edge = SkyLook.alpha(0xFFFFFFFF, 0.08f * k.light);
        for (int i = 0; i < g.towers.length; i += 5) {
            float sh = g.towers[i + 4], x = g.towers[i], w = g.towers[i + 2];
            mFill.setColor(SkyLook.mix(k.cityMid, sh > 0 ? 0xFFFFFFFF : 0xFF000000, Math.abs(sh)));
            c.drawRect(x, g.towers[i + 1], x + w, 1000, mFill);
            if (k.light > 0.05f) { // the side towards the sun catches light
                mFill.setColor(edge);
                float ex = lx > x + w / 2 ? x + w - 5 : x;
                c.drawRect(ex, g.towers[i + 1], ex + 5, 1000, mFill);
            }
        }
        mFill.setColor(k.cityMid);
        for (int i = 0; i < g.masts.length; i += 3) {
            c.drawRect(g.masts[i], g.masts[i + 1] - g.masts[i + 2], g.masts[i] + 3, g.masts[i + 1], mFill);
        }
        // windows light up as it gets dark (prototype rule), some go out late at night
        // by day the unlit panes only catch the sky a little
        int warm = SkyLook.mix(0xFFF6C27C, 0xFFFFE2B0, 0.5f);
        int glass = SkyLook.alpha(SkyLook.mix(k.skyMid, k.cityMid, 0.4f), 0.35f * k.light);
        for (int i = 0; i < g.win.length; i += 6) {
            float thr = g.win[i + 2];
            float o;
            if (k.windows > thr * 1.2f && g.win[i + 4] >= k.late) o = g.win[i + 3];
            else if (k.light < 0.6f && thr < 0.08f) o = 0.5f;
            else o = 0;
            if (o > 0) mFill.setColor(SkyLook.alpha(SkyLook.mix(0xFFF6C27C, warm, g.win[i + 5]), o));
            else if (k.light > 0.05f) mFill.setColor(glass);
            else continue;
            c.drawRect(g.win[i], g.win[i + 1], g.win[i] + 6, g.win[i + 1] + 8, mFill);
        }
        mFill.setColor(k.cityGround);
        c.drawRect(0, 1000, 1920, 1080, mFill);
    }

    // ------------------------------------------------------------------ dunes

    private Path mDune1, mDune2, mDune3, mCrest1, mCrest2;
    private float[] mDuneRipples;

    private void buildDunes() {
        if (mDune1 != null) return;
        mDune1 = svg("M0 690 C220 640 420 640 640 680 C880 720 1080 600 1340 610 C1560 618 1760 680 1920 660 L1920 1080 L0 1080 Z");
        mDune2 = svg("M0 800 C260 730 520 760 760 800 C1010 842 1240 720 1520 740 C1700 752 1820 790 1920 780 L1920 1080 L0 1080 Z");
        mDune3 = svg("M0 930 C300 860 600 880 900 930 C1180 978 1460 870 1920 900 L1920 1080 L0 1080 Z");
        mCrest2 = svg("M760 800 C1010 842 1240 720 1520 740");
        mCrest1 = svg("M640 680 C880 720 1080 600 1340 610");
        Rnd r = new Rnd(41);
        int n = 12;
        mDuneRipples = new float[n * 4];
        for (int i = 0; i < n; i++) {
            boolean near = i >= 5;
            mDuneRipples[i * 4] = r.range(-100, 1800);
            mDuneRipples[i * 4 + 1] = near ? r.range(960, 1060) : r.range(830, 900);
            mDuneRipples[i * 4 + 2] = near ? r.range(180, 360) : r.range(120, 240);
            mDuneRipples[i * 4 + 3] = r.range(-10, 10);
        }
    }

    private void dunes(Canvas c, SkyLook k) {
        buildDunes();
        boolean litLeft = lightX(k) < 960;
        float l = k.landLight;
        float snow = k.weather == SkyLook.SNOW ? 0.45f : 0f;
        float cool = 0.15f * k.season[SkyLook.WINTER];
        duneFill(c, k, mDune1, 0xFF2A2B38, 0xFF22232F, 0xFFD8A374, 0xFFC08656, l, k.warm * 1.2f, snow, cool, litLeft, 610, 1080);
        duneLine(c, mCrest1, k, 0.3f);
        duneFill(c, k, mDune2, 0xFF1E1E29, 0xFF181822, 0xFFB07448, 0xFF94603A, l, k.warm * 0.8f, snow, cool, litLeft, 720, 1080);
        duneLine(c, mCrest2, k, 0.5f);
        duneFill(c, k, mDune3, 0xFF121219, 0xFF0D0D12, 0xFF6E4229, 0xFF55311E, l, k.warm * 0.4f, snow, cool, litLeft, 860, 1080);
        // wind ripples
        mLine.setStrokeWidth(1.5f);
        mLine.setColor(SkyLook.alpha(0xFF000000, 0.04f + 0.03f * l));
        for (int i = 0; i < mDuneRipples.length; i += 4) {
            float x = mDuneRipples[i], y = mDuneRipples[i + 1], w = mDuneRipples[i + 2], b = mDuneRipples[i + 3];
            Path p = mTmp;
            p.rewind();
            p.moveTo(x, y);
            p.quadTo(x + w / 2, y - 12 + b, x + w, y + b * 0.5f);
            c.drawPath(p, mLine);
        }
    }

    private void duneFill(Canvas c, SkyLook k, Path p, int nightA, int nightB, int dayA, int dayB, float l,
                          float warm, float snow, float cool, boolean litLeft, float y0, float y1) {
        int a = SkyLook.mix(SkyLook.mix(nightA, dayA, l), 0xFFE0905A, warm);
        int b = SkyLook.mix(SkyLook.mix(nightB, dayB, l), 0xFFE0905A, warm * 0.6f);
        a = SkyLook.mix(SkyLook.mix(a, 0xFF8A93A0, cool), 0xFFDDE5EB, snow * l);
        b = SkyLook.mix(SkyLook.mix(b, 0xFF8A93A0, cool), 0xFFDDE5EB, snow * l);
        // lit flank towards the light, the far one in its own shadow; slightly darker at the foot
        int left = litLeft ? a : b, right = litLeft ? b : a;
        mFill.setShader(new LinearGradient(0, 0, 1920, 0, left, right, Shader.TileMode.CLAMP));
        c.drawPath(p, mFill);
        mFill.setShader(new LinearGradient(0, y0, 0, y1, 0, SkyLook.alpha(0xFF000000, 0.18f), Shader.TileMode.CLAMP));
        c.drawPath(p, mFill);
        mFill.setShader(null);
    }

    private void duneLine(Canvas c, Path crest, SkyLook k, float strength) {
        float a = strength * SkyLook.clamp01((k.el + 2) / 6f) * (k.clear > 0 ? 1f : 0.4f);
        if (a <= 0.01f) return;
        mLine.setStrokeWidth(3);
        mLine.setColor(SkyLook.alpha(SkyLook.mix(k.sunColor, 0xFFF3C79A, 0.5f), a));
        c.drawPath(crest, mLine);
    }

    // ------------------------------------------------------------------ forest in mist

    private Path mRow0, mRow1a, mRow1b, mRow2a, mRow2b;

    private void buildForest() {
        if (mRow0 != null) return;
        Rnd r = new Rnd(29);
        mRow0 = new Path();
        row(r, mRow0, mRow0, 762, 8, 36, 84, 9, 22, 0.36f, 0);
        mRow1a = new Path();
        mRow1b = new Path();
        row(r, mRow1a, mRow1b, 866, 14, 90, 190, 22, 52, 0.34f, 0.05f);
        mRow2a = new Path();
        mRow2b = new Path();
        row(r, mRow2a, mRow2b, 1030, 40, 210, 430, 46, 150, 0.32f, 0.12f);
    }

    /** One row of spruces along {@code base} (+- jitter), shared out over two paths for two tones. */
    private static void row(Rnd r, Path a, Path b, float base, float jit, float hMin, float hMax, float sMin, float sMax,
                            float ratio, float gaps) {
        float x = -20 - r.next() * sMax;
        while (x < 1940 + sMax) {
            if (r.next() >= gaps) {
                float h = r.range(hMin, hMax);
                conifer(r.next() < 0.5f ? a : b, x, base + r.range(-jit, jit), h, h * ratio * r.range(0.75f, 1.15f), r);
            }
            // clumps: mostly close neighbours, now and then a clearing
            x += r.next() < 0.8f ? r.range(sMin, (sMin + sMax) / 2) : r.range(sMax, sMax * 1.6f);
        }
    }

    /** Rows of spruces, each standing on its own canopy (as in D_Walls), mist rising between them. */
    private void forest(Canvas c, SkyLook k) {
        buildForest();
        float mistA = (0.5f + 0.5f * k.light) * (k.weather == SkyLook.FOG ? 1.2f : 1f);
        int row0 = SkyLook.mix(k.conifer, k.mist, 0.58f);
        fill(c, mRow0, row0);
        mFill.setColor(row0);
        c.drawRect(0, 758, 1920, 1080, mFill);
        mistBand(c, k.mist, 700, 800, 880, 0.4f * mistA);
        int row1 = SkyLook.mix(k.conifer, k.mist, 0.32f);
        fill(c, mRow1a, row1);
        fill(c, mRow1b, SkyLook.mix(row1, k.mist, 0.1f));
        mFill.setColor(row1);
        c.drawRect(0, 852, 1920, 1080, mFill);
        mistBand(c, k.mist, 800, 915, 1010, 0.32f * mistA);
        mFill.setColor(SkyLook.mix(k.conifer, k.near, 0.15f));
        c.drawRect(0, 1004, 1920, 1080, mFill);
        fill(c, mRow2a, k.conifer);
        fill(c, mRow2b, SkyLook.mix(k.conifer, k.mist, 0.06f));
    }

    private void mistBand(Canvas c, int color, float top, float peak, float bottom, float a) {
        float pk = (peak - top) / (bottom - top);
        mFill.setShader(new LinearGradient(0, top, 0, bottom,
                new int[]{SkyLook.alpha(color, 0), SkyLook.alpha(color, a), SkyLook.alpha(color, 0)},
                new float[]{0, pk, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, top, 1920, bottom, mFill);
        mFill.setShader(null);
    }

    // ------------------------------------------------------------------ shared shapes

    /**
     * A spruce: a thin leader, then tiers of drooping branch tips on both sides of a slightly leaning
     * stem, the crown a little convex, each side jittered on its own (no two trees match), a short
     * trunk at the base.
     */
    static void conifer(Path p, float cx, float base, float h, float w, Rnd r) {
        int tiers = 6 + (int) (r.next() * 4);
        float top = base - h;
        float lean = r.range(-0.035f, 0.035f) * h;
        float spike = h * r.range(0.04f, 0.09f);
        float tierH = (h * 0.94f - spike) / tiers;
        p.moveTo(cx + lean, top);
        for (int i = 1; i <= tiers; i++) {
            float f = i / (float) tiers;
            float y = top + spike + tierH * i;
            float sx = cx + lean * (1 - f);
            float half = w * 0.5f * (float) Math.pow(f, 0.85) * r.range(0.8f, 1.15f);
            p.lineTo(sx + half, y + tierH * r.range(0f, 0.15f));
            if (i < tiers) p.lineTo(sx + half * r.range(0.25f, 0.45f), y - tierH * r.range(0.1f, 0.35f));
        }
        p.lineTo(cx + w * 0.04f, base);
        p.lineTo(cx - w * 0.04f, base);
        for (int i = tiers; i >= 1; i--) {
            float f = i / (float) tiers;
            float y = top + spike + tierH * i;
            float sx = cx + lean * (1 - f);
            float half = w * 0.5f * (float) Math.pow(f, 0.85) * r.range(0.8f, 1.15f);
            if (i < tiers) p.lineTo(sx - half * r.range(0.25f, 0.45f), y - tierH * r.range(0.1f, 0.35f));
            p.lineTo(sx - half, y + tierH * r.range(0f, 0.15f));
        }
        p.close();
    }

    private void fill(Canvas c, Path p, int color) {
        mFill.setShader(null);
        mFill.setColor(color);
        c.drawPath(p, mFill);
    }

    /** Fill with a vertical gradient (volume: lighter crest, darker foot). */
    private void vfill(Canvas c, Path p, int top, int bottom, float y0, float y1) {
        mFill.setShader(new LinearGradient(0, y0, 0, y1, top, bottom, Shader.TileMode.CLAMP));
        c.drawPath(p, mFill);
        mFill.setShader(null);
    }

    /**
     * Midpoint displacement: each level splits every segment and nudges the midpoint (mostly
     * vertically), so ridges get natural detail while the original vertices stay where they were:
     * original vertex j is at index j << levels.
     */
    static float[] subdivide(float[] pts, int levels, float rough, Rnd r) {
        float[] cur = pts;
        for (int l = 0; l < levels; l++) {
            int n = cur.length / 2;
            float[] nx = new float[(n * 2 - 1) * 2];
            for (int i = 0; i < n - 1; i++) {
                float x0 = cur[i * 2], y0 = cur[i * 2 + 1], x1 = cur[i * 2 + 2], y1 = cur[i * 2 + 3];
                float len = (float) Math.hypot(x1 - x0, y1 - y0);
                nx[i * 4] = x0;
                nx[i * 4 + 1] = y0;
                nx[i * 4 + 2] = (x0 + x1) / 2 + (r.next() - 0.5f) * len * rough * 0.5f;
                nx[i * 4 + 3] = (y0 + y1) / 2 + (r.next() - 0.5f) * 2 * len * rough;
            }
            nx[(n - 1) * 4] = cur[(n - 1) * 2];
            nx[(n - 1) * 4 + 1] = cur[(n - 1) * 2 + 1];
            cur = nx;
            rough *= 0.55f;
        }
        return cur;
    }

    /** Closed shape under a ridge polyline, down to {@code base}. */
    static Path ridgePath(float[] ridge, float base) {
        Path p = new Path();
        p.moveTo(ridge[0], base);
        for (int i = 0; i < ridge.length; i += 2) p.lineTo(ridge[i], ridge[i + 1]);
        p.lineTo(ridge[ridge.length - 2], base);
        p.close();
        return p;
    }

    /** The absolute SVG path subset of the prototypes: M, L, C, Q, Z. */
    static Path svg(String d) {
        Path p = new Path();
        String[] t = d.replaceAll("([MLCQZ])", " $1 ").trim().split("[\\s,]+");
        char cmd = 'M';
        int i = 0;
        while (i < t.length) {
            String s = t[i];
            if (s.length() == 1 && Character.isLetter(s.charAt(0))) {
                cmd = s.charAt(0);
                i++;
                if (cmd == 'Z') p.close();
                continue;
            }
            switch (cmd) {
                case 'M':
                    p.moveTo(f(t[i]), f(t[i + 1]));
                    i += 2;
                    cmd = 'L';
                    break;
                case 'L':
                    p.lineTo(f(t[i]), f(t[i + 1]));
                    i += 2;
                    break;
                case 'C':
                    p.cubicTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]), f(t[i + 4]), f(t[i + 5]));
                    i += 6;
                    break;
                case 'Q':
                    p.quadTo(f(t[i]), f(t[i + 1]), f(t[i + 2]), f(t[i + 3]));
                    i += 4;
                    break;
                default:
                    i++;
            }
        }
        return p;
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    /** Growable float array for building geometry. */
    static final class FloatList {
        private float[] mA = new float[64];
        private int mN;

        void add(float... v) {
            if (mN + v.length > mA.length) mA = java.util.Arrays.copyOf(mA, Math.max(mA.length * 2, mN + v.length));
            System.arraycopy(v, 0, mA, mN, v.length);
            mN += v.length;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(mA, mN);
        }
    }
}
