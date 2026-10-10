package org.z9x.projector.ak;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * Re-implementation of the stock AkAnimView (SystemUI focus/AkAnimView.java, VERIFIED):
 * onDraw maps the view rectangle onto the quad of 8 animated floats with
 * Matrix.setPolyToPoly(src = view corners LT,RT,RB,LB -> dst = the 8 floats), draws the pattern
 * under that matrix and then a black outline of the view rectangle (stroke 5 design px, so it is
 * warped together with the picture). Event 109 animates the 8 floats from the view bounds to the
 * vendor's corners in 500 ms with the AnimatorSet default AccelerateDecelerate interpolator.
 *
 * Content modes: the generated pattern with the keystone caption (107/109), the same pattern with
 * the optical-zoom caption (115), or our own short "done" animation (118, stock played the Lottie
 * file ak.json there; we draw a check mark in the corrected quad instead).
 *
 * Every size is relative to the view (= the full UI frame at 1080p, 2K or 4K, Lumen OS 1.0.1): the
 * pattern and its caption are in AkPatternSpec design units (1920 x 1080) scaled to the view, the
 * pattern itself is an ALPHA_8 mask drawn in black over white (AkPatternRenderer). onDraw allocates
 * nothing.
 *
 * Main thread only.
 */
final class AkWarpView extends View {
    static final int MODE_PATTERN = 0, MODE_ZOOM = 1, MODE_DONE = 2;
    static final long WARP_MS = 500;                 // AkAnimView.mAllAnimSet.setDuration(500)
    private static final long DONE_DRAW_MS = 600, DONE_PULSE_MS = 1600;

    private Bitmap pattern;
    private int mode = MODE_PATTERN;
    private String captionKeystone = "", captionZoom = "";
    /** Corners LT(x,y), RT, RB, LB in view px; valid only when !full. */
    private final float[] cur = new float[8];
    private final float[] from = new float[8], to = new float[8];
    private boolean full = true;
    private final float[] src = new float[8], dst = new float[8];
    private final Matrix matrix = new Matrix();
    /** The ALPHA_8 pattern mask is drawn in this paint's colour (black) over {@link #white}. */
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint();
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AkPatternRenderer.CaptionPaints captionPaints = new AkPatternRenderer.CaptionPaints();
    private ValueAnimator warp, done, pulse;
    private float doneProgress, doneAlpha = 1f;
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path donePath = new Path(), doneSeg = new Path();
    private final PathMeasure doneMeasure = new PathMeasure();
    private final RectF tmp = new RectF();

    AkWarpView(Context c) {
        super(c);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(Color.BLACK);
        bitmapPaint.setColor(Color.BLACK);
        white.setStyle(Paint.Style.FILL);
        white.setColor(Color.WHITE);
        donePaint.setStyle(Paint.Style.STROKE);
        donePaint.setStrokeCap(Paint.Cap.ROUND);
        donePaint.setStrokeJoin(Paint.Join.ROUND);
        donePaint.setColor(Color.WHITE);
        setWillNotDraw(false);
    }

    void setCaptions(String keystone, String zoom) {
        captionKeystone = keystone == null ? "" : keystone;
        captionZoom = zoom == null ? "" : zoom;
    }

    void setPattern(Bitmap b) {
        pattern = b;
        invalidate();
    }

    boolean hasPattern() {
        return pattern != null;
    }

    /** 107/109: the pattern; 115: pattern with the zoom caption. Stops the done animation. */
    void setMode(int m) {
        if (m != MODE_DONE) stopDone();
        mode = m;
        invalidate();
    }

    /** Stock initAllData (event 106): corners back to the full view, alpha 1. */
    void resetFull() {
        if (warp != null) warp.cancel();
        full = true;
        setAlpha(1f);
        invalidate();
    }

    /**
     * Event 109. {@code target} = 8 floats in view px, order LT, RT, RB, LB. Starts from the view
     * bounds like the stock initAnim (getLeft/getTop/measured size). {@code onEnd} runs on the main
     * thread when the animation ends or is cancelled (stock: AnimatorSet end listener).
     */
    void animateTo(float[] target, Runnable onEnd) {
        if (warp != null) warp.cancel();
        int w = getWidth(), h = getHeight();
        from[0] = 0; from[1] = 0; from[2] = w; from[3] = 0;
        from[4] = w; from[5] = h; from[6] = 0; from[7] = h;
        System.arraycopy(target, 0, to, 0, 8);
        System.arraycopy(from, 0, cur, 0, 8);
        full = false;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(WARP_MS);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            for (int i = 0; i < 8; i++) cur[i] = from[i] + (to[i] - from[i]) * f;
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean fired;
            @Override public void onAnimationEnd(Animator an) {
                for (int i = 0; i < 8; i++) cur[i] = to[i];
                invalidate();
                if (!fired) { fired = true; if (onEnd != null) onEnd.run(); }
            }
        });
        warp = a;
        a.start();
        invalidate();
    }

    /** Event 118: our "done" animation; {@code onStart} runs when it starts (stock acks at start). */
    void startDone(Runnable onStart) {
        stopDone();
        mode = MODE_DONE;
        doneProgress = 0f;
        doneAlpha = 1f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(DONE_DRAW_MS);
        a.setInterpolator(new DecelerateInterpolator(1.5f));
        a.addUpdateListener(v -> { doneProgress = (float) v.getAnimatedValue(); invalidate(); });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean started, cancelled;
            @Override public void onAnimationStart(Animator an) {
                if (!started) { started = true; if (onStart != null) onStart.run(); }
            }
            @Override public void onAnimationCancel(Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(Animator an) {
                if (cancelled || mode != MODE_DONE) return;
                ValueAnimator p = ValueAnimator.ofFloat(1f, 0.55f);   // gentle loop until hidden
                p.setDuration(DONE_PULSE_MS / 2);
                p.setRepeatMode(ValueAnimator.REVERSE);
                p.setRepeatCount(ValueAnimator.INFINITE);
                p.setInterpolator(new LinearInterpolator());
                p.addUpdateListener(v -> { doneAlpha = (float) v.getAnimatedValue(); invalidate(); });
                pulse = p;
                p.start();
            }
        });
        done = a;
        a.start();
    }

    private void stopDone() {
        if (done != null) { done.cancel(); done = null; }
        if (pulse != null) { pulse.cancel(); pulse = null; }
        doneProgress = 0f;
        doneAlpha = 1f;
    }

    /** Cancels everything (hide / dismiss). End listeners still run; callers guard with a token. */
    void cancelAll() {
        if (warp != null) warp.cancel();
        stopDone();
        animate().cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAll();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int save = canvas.getSaveCount();
        try {
            drawContent(canvas);
        } catch (Throwable t) {                               // persistent process: never crash
            canvas.restoreToCount(save);
            android.util.Log.e("Z9xAk", "draw", t);
        }
    }

    private void drawContent(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        src[0] = 0; src[1] = 0; src[2] = w; src[3] = 0;
        src[4] = w; src[5] = h; src[6] = 0; src[7] = h;
        if (full) System.arraycopy(src, 0, dst, 0, 8); else System.arraycopy(cur, 0, dst, 0, 8);
        canvas.save();
        if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) canvas.concat(matrix);
        if (mode == MODE_DONE) {
            drawDone(canvas, w, h);
        } else if (pattern != null) {
            tmp.set(0, 0, w, h);
            canvas.drawRect(tmp, white);
            canvas.drawBitmap(pattern, null, tmp, bitmapPaint);   // ALPHA_8: drawn in black
            canvas.save();
            canvas.scale(w / (float) AkPatternSpec.W, h / (float) AkPatternSpec.H);
            boolean zoom = mode == MODE_ZOOM;
            AkPatternRenderer.drawCaption(canvas, captionPaints, zoom ? captionZoom : captionKeystone,
                    zoom ? AkPatternRenderer.CAPTION_ZOOM : AkPatternRenderer.CAPTION_KEYSTONE);
            canvas.restore();
        }
        outline.setStrokeWidth(5f * w / AkPatternSpec.W);  // AutoAdaptation.getDisplayWidthValue(5)
        canvas.drawRect(0, 0, w, h, outline);
        canvas.restore();
    }

    /** Our 118 animation, in design units inside the (warped) frame: circle sweep, then check. */
    private void drawDone(Canvas c, int w, int h) {
        c.save();
        c.scale(w / (float) AkPatternSpec.W, h / (float) AkPatternSpec.H);
        float cx = AkPatternSpec.W / 2f, cy = AkPatternSpec.H / 2f, r = 110f;
        int a = Math.round(255 * doneAlpha);
        donePaint.setAlpha(Math.round(a * 0.35f));
        donePaint.setStrokeWidth(8f);
        c.drawRect(24, 24, AkPatternSpec.W - 24, AkPatternSpec.H - 24, donePaint);   // the new frame
        donePaint.setAlpha(a);
        donePaint.setStrokeWidth(12f);
        float ring = Math.min(1f, doneProgress / 0.6f);
        tmp.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(tmp, -90, 360 * ring, false, donePaint);
        float check = Math.max(0f, (doneProgress - 0.45f) / 0.55f);
        if (check > 0f) {
            donePath.reset();
            donePath.moveTo(cx - 48, cy + 2);
            donePath.lineTo(cx - 12, cy + 38);
            donePath.lineTo(cx + 52, cy - 34);
            doneMeasure.setPath(donePath, false);
            doneSeg.reset();
            doneMeasure.getSegment(0, doneMeasure.getLength() * check, doneSeg, true);
            c.drawPath(doneSeg, donePaint);
        }
        c.restore();
    }
}
