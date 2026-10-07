package org.z9x.projector.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Theme;

import java.util.List;

/**
 * MODULE "game" (v6.2): the "Play" cue. A minimal capsule at the bottom centre of the screen,
 * about 1.5 s, shown once after the console game profile was applied:
 *
 *   0-280 ms    capsule fades in, rises 24 px and scales 0.94 -> 1   PathInterpolator(0.2,0,0,1)
 *   80-580 ms   a thin ring draws itself around the icon (0 -> 360 deg, decelerate)
 *   420-700 ms  a play triangle pops in with a slight overshoot   PathInterpolator(0.34,1.56,0.64,1)
 *   300-600 ms  title fades in and slides 16 px; the chips line follows 80 ms later
 *   600-1000 ms one soft light sweep crosses the capsule
 *   1150-1500   capsule fades out and drifts up 12 px              PathInterpolator(0.4,0,1,1)
 *
 * Drawn on one Canvas (no XML, no AndroidX, no Lottie, no brand logos or XGIMI assets). Frames are
 * driven by postInvalidateOnAnimation with uptime (independent of the animator duration scale);
 * when the user turned animations off (ANIMATOR_DURATION_SCALE 0) it only fades, 1.2 s. Alpha is
 * applied per paint (no offscreen layer); all Paths, Paints and the sweep shader are allocated once.
 * The window is a static, non-focusable, non-touchable TYPE_APPLICATION_OVERLAY (OverlayHost),
 * removed at the end. Main thread only.
 */
final class PlayCueView extends View {
    private static final String TAG = "Z9xGame";

    // design px (1920-wide screen, Theme.px)
    private static final float CAP_H = 120, CAP_MIN_W = 520, CAP_MAX_W = 1040, PAD_L = 24, PAD_R = 44;
    private static final float ICON = 72, ICON_GAP = 24, RING_W = 4, BOTTOM_GAP = 120, MARGIN = 36;
    private static final float TITLE_SIZE = 32, CHIP_SIZE = 22, CHIP_H = 34, CHIP_PAD = 14, CHIP_GAP = 10;

    private static final long TOTAL_MS = 1500, REDUCED_MS = 1200;

    private static PlayCueView sShowing;

    private final OverlayHost host;
    private final boolean reduced;
    private final boolean rtl;
    private final float s;
    private final String title;
    private final String[] chips;
    private final float[] chipW;

    private final RectF cap = new RectF();
    private final RectF tmp = new RectF();
    private final Path triangle = new Path();
    private final Paint capFill = Theme.fill(0xE0141414);
    private final Paint capStroke;
    private final Paint ringTrack;
    private final Paint ring;
    private final Paint tri = Theme.fill(0xFFFFFFFF);
    private final Paint chipFill = Theme.fill(0x24FFFFFF);
    private final Paint sweep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint;
    private final TextPaint chipPaint;
    private final Matrix sweepMatrix = new Matrix();
    private final PathInterpolator inInterp = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final PathInterpolator popInterp = new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);
    private final PathInterpolator outInterp = new PathInterpolator(0.4f, 0f, 1f, 1f);
    private final PathInterpolator decel = new PathInterpolator(0f, 0f, 0.2f, 1f);
    private final float capW, winW, winH;
    /** (ascent + descent) / 2 of the title and chip fonts, measured once. */
    private final float titleMid, chipMid;
    private final String titleDrawn;
    private LinearGradient sweepShader;
    private float sweepBand;
    private long startAt;
    private boolean finished;

    /**
     * Shows the cue (replacing one still running). Returns false when the window could not be added.
     * Main thread.
     */
    static boolean show(Context ctx, String title, List<String> chips) {
        try {
            hide();
            PlayCueView v = new PlayCueView(ctx.getApplicationContext(), title, chips);
            WindowManager.LayoutParams lp = OverlayHost.params(false, Math.round(v.winW), Math.round(v.winH),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.y = Math.round((BOTTOM_GAP - MARGIN) * v.s);
            lp.setTitle("Z9xPlayCue");
            if (!v.host.addStatic(v, lp)) return false;
            sShowing = v;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "play cue: " + t);
            return false;
        }
    }

    /** Removes a running cue at once (screen off, quick panel opened). Main thread. */
    static void hide() {
        PlayCueView v = sShowing;
        sShowing = null;
        if (v != null) v.finish();
    }

    private PlayCueView(Context c, String title, List<String> chipList) {
        super(c);
        host = OverlayHost.get(c);
        s = Theme.scale(c);
        reduced = animationsOff(c);
        rtl = c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        this.title = title == null ? "" : title;
        chips = chipList == null ? new String[0] : chipList.toArray(new String[0]);

        capStroke = Theme.stroke(c, 0x1FFFFFFF, 2);
        ringTrack = Theme.stroke(c, 0x26FFFFFF, RING_W);
        ring = Theme.stroke(c, 0xD9FFFFFF, RING_W);
        ring.setStrokeCap(Paint.Cap.ROUND);
        titlePaint = Theme.text(c, TITLE_SIZE, 0xF2FFFFFF, false);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chipPaint = Theme.text(c, CHIP_SIZE, 0xCCFFFFFF, false);
        chipPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));

        Paint.FontMetrics fm = titlePaint.getFontMetrics();
        titleMid = (fm.ascent + fm.descent) / 2f;
        fm = chipPaint.getFontMetrics();
        chipMid = (fm.ascent + fm.descent) / 2f;
        titlePaint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        chipPaint.setTextAlign(Paint.Align.CENTER);

        chipW = new float[chips.length];
        float chipsTotal = 0;
        for (int i = 0; i < chips.length; i++) {
            chipW[i] = chipPaint.measureText(chips[i]) + 2 * CHIP_PAD * s;
            chipsTotal += chipW[i] + (i > 0 ? CHIP_GAP * s : 0);
        }
        float textMax = (CAP_MAX_W - PAD_L - ICON - ICON_GAP - PAD_R) * s;
        titleDrawn = TextUtils.ellipsize(this.title, titlePaint, textMax, TextUtils.TruncateAt.END).toString();
        float content = Math.max(titlePaint.measureText(titleDrawn), Math.min(chipsTotal, textMax));
        capW = Math.max(CAP_MIN_W * s, Math.min(CAP_MAX_W * s, (PAD_L + ICON + ICON_GAP + PAD_R) * s + content));
        winW = capW + 2 * MARGIN * s;
        winH = (CAP_H + 2 * MARGIN) * s;

        // play triangle around (0,0), optical centre slightly right
        float u = s;
        triangle.moveTo(-10 * u, -14 * u);
        triangle.lineTo(17 * u, 0);
        triangle.lineTo(-10 * u, 14 * u);
        triangle.close();
        setWillNotDraw(false);
    }

    private static boolean animationsOff(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startAt = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float left = (w - capW) / 2f;
        float top = (h - CAP_H * s) / 2f;
        cap.set(left, top, left + capW, top + CAP_H * s);
        sweepBand = 180 * s;
        sweepShader = new LinearGradient(0, 0, sweepBand, sweepBand * 0.35f,
                new int[] {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        sweep.setShader(sweepShader);
    }

    /** 0..1 progress of [from, to] at time t, clamped. */
    private static float seg(long t, long from, long to) {
        if (t <= from) return 0f;
        if (t >= to) return 1f;
        return (t - from) / (float) (to - from);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (finished || cap.isEmpty()) return;
        long t = SystemClock.uptimeMillis() - startAt;
        long total = reduced ? REDUCED_MS : TOTAL_MS;
        try {
            drawFrame(c, t);
        } catch (Throwable e) {
            Log.w(TAG, "play cue draw: " + e);
            t = total;
        }
        if (t < total) {
            postInvalidateOnAnimation();
        } else {
            post(this::finish);
        }
    }

    private void drawFrame(Canvas c, long t) {
        float alpha, scale, dy, ringSweep, triScale, triAlpha, titleA, titleDx, chipsA, chipsDx, sweepP;
        if (reduced) {
            // animations off: fade only (200 ms in, 300 ms out), everything else in its final state
            alpha = Math.min(seg(t, 0, 200), 1f - seg(t, 900, 1200));
            scale = 1f; dy = 0; ringSweep = 360f; triScale = 1f; triAlpha = 1f;
            titleA = 1f; titleDx = 0; chipsA = 1f; chipsDx = 0; sweepP = -1f;
        } else {
            float in = inInterp.getInterpolation(seg(t, 0, 280));
            float out = outInterp.getInterpolation(seg(t, 1150, 1500));
            alpha = in * (1f - out);
            scale = 0.94f + 0.06f * in;
            dy = (24f * (1f - in) - 12f * out) * s;
            ringSweep = 360f * decel.getInterpolation(seg(t, 80, 580));
            float pop = seg(t, 420, 700);
            triScale = 0.4f + 0.6f * popInterp.getInterpolation(pop);
            triAlpha = Math.min(1f, pop / 0.4f);
            float ti = inInterp.getInterpolation(seg(t, 300, 600));
            titleA = ti;
            titleDx = 16f * s * (1f - ti);
            float ci = inInterp.getInterpolation(seg(t, 380, 680));
            chipsA = ci;
            chipsDx = 16f * s * (1f - ci);
            sweepP = (t >= 600 && t <= 1000) ? seg(t, 600, 1000) : -1f;
        }
        if (alpha <= 0.001f) return;
        if (rtl) { titleDx = -titleDx; chipsDx = -chipsDx; }

        c.save();
        c.translate(0, dy);
        c.scale(scale, scale, cap.centerX(), cap.centerY());

        float r = cap.height() / 2f;
        capFill.setAlpha(Math.round(0xE0 * alpha));
        c.drawRoundRect(cap, r, r, capFill);
        capStroke.setAlpha(Math.round(0x1F * alpha));
        c.drawRoundRect(cap, r, r, capStroke);

        // icon: ring + play triangle
        float iconR = ICON * s / 2f;
        float icx = rtl ? cap.right - PAD_L * s - iconR : cap.left + PAD_L * s + iconR;
        float icy = cap.centerY();
        float rr = iconR - RING_W * s / 2f;
        tmp.set(icx - rr, icy - rr, icx + rr, icy + rr);
        ringTrack.setAlpha(Math.round(0x26 * alpha));
        c.drawOval(tmp, ringTrack);
        if (ringSweep > 0.5f) {
            ring.setAlpha(Math.round(0xD9 * alpha));
            c.drawArc(tmp, -90f, rtl ? -ringSweep : ringSweep, false, ring);
        }
        if (triAlpha > 0f) {
            tri.setAlpha(Math.round(0xFF * alpha * triAlpha));
            c.save();
            c.translate(icx, icy);
            c.scale(triScale, triScale);
            c.drawPath(triangle, tri);
            c.restore();
        }

        // text block
        float textStart = rtl ? cap.right - (PAD_L + ICON + ICON_GAP) * s : cap.left + (PAD_L + ICON + ICON_GAP) * s;
        float titleBase = chips.length == 0 ? icy - titleMid : cap.top + 50 * s;
        titlePaint.setAlpha(Math.round(0xF2 * alpha * titleA));
        c.drawText(titleDrawn, textStart + titleDx, titleBase, titlePaint);

        if (chips.length > 0 && chipsA > 0f) {
            float top = cap.top + 64 * s;
            float h = CHIP_H * s;
            float base = top + h / 2f - chipMid;
            float limit = rtl ? cap.left + PAD_R * s : cap.right - PAD_R * s;
            float x = textStart + chipsDx;
            chipFill.setAlpha(Math.round(0x24 * alpha * chipsA));
            chipPaint.setAlpha(Math.round(0xCC * alpha * chipsA));
            for (int i = 0; i < chips.length; i++) {
                float w = chipW[i];
                float l = rtl ? x - w : x;
                float rgt = rtl ? x : x + w;
                if (rtl ? l < limit : rgt > limit) break;           // never draw outside the capsule
                tmp.set(l, top, rgt, top + h);
                c.drawRoundRect(tmp, h / 2f, h / 2f, chipFill);
                c.drawText(chips[i], (l + rgt) / 2f, base, chipPaint);
                x = rtl ? l - CHIP_GAP * s : rgt + CHIP_GAP * s;
            }
        }

        // one soft light sweep across the capsule (the shader is transparent outside its band)
        if (sweepP >= 0f && sweepShader != null) {
            float from = cap.left - sweepBand, to = cap.right;
            float pos = rtl ? to - (to - from) * sweepP : from + (to - from) * sweepP;
            sweepMatrix.setTranslate(pos, cap.top);
            sweepShader.setLocalMatrix(sweepMatrix);
            sweep.setAlpha(Math.round(0xFF * alpha));
            c.drawRoundRect(cap, r, r, sweep);
        }
        c.restore();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        if (sShowing == this) sShowing = null;
        try {
            host.removeStatic(this);
        } catch (Throwable t) {
            Log.w(TAG, "play cue remove: " + t);
        }
    }
}
