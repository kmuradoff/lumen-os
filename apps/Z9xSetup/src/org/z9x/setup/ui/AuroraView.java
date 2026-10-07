package org.z9x.setup.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Setup background (SPEC 4.0, PLAN C7: setup-only treatment): brand bg gradient plus two very soft
 * radial "aurora" blobs that drift over a 20 s loop. Bitmap-free, shaders are built once per size;
 * redraws are capped at 20 fps and stop entirely after 60 s without a key press (no GPU waste).
 */
public class AuroraView extends View {
    private static final long LOOP_MS = 20_000;
    private static final long IDLE_MS = 60_000;
    private static final long FRAME_MS = 50;   // 20 fps: the drift is ~70 px/s on blobs this soft

    private final Paint mBase = new Paint();
    private final Paint mA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mRa, mRb;
    private ValueAnimator mAnim;
    private float mT;
    private long mLastFrame;
    private long mLastPoke = SystemClock.uptimeMillis();
    private boolean mEnabled = true;

    public AuroraView(Context c) {
        super(c);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mBase.setShader(new LinearGradient(0, 0, 0, h, Ui.BG, Ui.BG_BOTTOM, Shader.TileMode.CLAMP));
        mRa = w * 0.42f;
        mRb = w * 0.36f;
        int a = (Ui.ACCENT_STRONG & 0x00FFFFFF) | 0x2E000000;
        int b = (Ui.ACCENT & 0x00FFFFFF) | 0x1C000000;
        mA.setShader(new RadialGradient(0, 0, mRa, new int[]{a, a & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        mB.setShader(new RadialGradient(0, 0, mRb, new int[]{b, b & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
    }

    /** Any user activity resumes the drift. */
    public void poke() {
        mLastPoke = SystemClock.uptimeMillis();
        if (mAnim != null && mAnim.isPaused()) mAnim.resume();
    }

    /** The picture step shows a black grid instead; stop drawing blobs there. */
    public void setAuroraEnabled(boolean on) {
        mEnabled = on;
        update();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnim != null) mAnim.cancel();
        mAnim = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View v, int vis) {
        super.onVisibilityChanged(v, vis);
        update();
    }

    private void update() {
        boolean want = mEnabled && isAttachedToWindow() && isShown();
        if (want && mAnim == null) {
            mAnim = ValueAnimator.ofFloat(0f, 1f);
            mAnim.setDuration(LOOP_MS);
            mAnim.setRepeatCount(ValueAnimator.INFINITE);
            mAnim.setInterpolator(new LinearInterpolator());
            mAnim.addUpdateListener(an -> {
                long now = SystemClock.uptimeMillis();
                if (now - mLastPoke > IDLE_MS) {
                    an.pause();
                    return;
                }
                if (now - mLastFrame < FRAME_MS) return;
                mLastFrame = now;
                mT = (float) an.getAnimatedValue();
                invalidate();
            });
            mAnim.start();
        } else if (!want && mAnim != null) {
            mAnim.cancel();
            mAnim = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, mBase);
        if (!mEnabled) return;
        double ph = mT * Math.PI * 2;
        float ax = (float) (w * (0.78 + 0.06 * Math.cos(ph)));
        float ay = (float) (h * (0.22 + 0.08 * Math.sin(ph)));
        float bx = (float) (w * (0.18 + 0.07 * Math.sin(ph + 1.3)));
        float by = (float) (h * (0.86 + 0.06 * Math.cos(ph * 2 + 0.4) * 0.5));
        c.save();
        c.translate(ax, ay);
        c.drawCircle(0, 0, mRa, mA);
        c.restore();
        c.save();
        c.translate(bx, by);
        c.drawCircle(0, 0, mRb, mB);
        c.restore();
    }
}
