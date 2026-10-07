package org.z9x.setup.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The XGIMI remote, drawn in code: the Back and Home keys pulse (1.2 s) to show which two keys to
 * hold. When connected, a check replaces the pulse. Animates only while shown.
 */
public class RemoteArt extends View {
    private final Paint mBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKey = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private ValueAnimator mAnim;
    private float mPulse;
    private boolean mConnected;

    public RemoteArt(Context c) {
        super(c);
        mKey.setColor(0x33FFFFFF);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setColor(0x26FFFFFF);
        mStroke.setStrokeWidth(Ui.pxf(2));
        mGlow.setColor(Ui.ACCENT);
    }

    public void setConnected(boolean c) {
        mConnected = c;
        update();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mBody.setShader(new LinearGradient(0, 0, 0, h, 0xFF2A3140, 0xFF1A1F28, Shader.TileMode.CLAMP));
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
        boolean want = !mConnected && isAttachedToWindow() && isShown();
        if (want && mAnim == null) {
            mAnim = ValueAnimator.ofFloat(0f, 1f, 0f);
            mAnim.setDuration(1200);
            mAnim.setRepeatCount(ValueAnimator.INFINITE);
            mAnim.addUpdateListener(a -> { mPulse = (float) a.getAnimatedValue(); invalidate(); });
            mAnim.start();
        } else if (!want && mAnim != null) {
            mAnim.cancel();
            mAnim = null;
            mPulse = 0;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        float w = h * 0.30f;
        float x0 = (getWidth() - w) / 2f;
        mR.set(x0, 0, x0 + w, h);
        float rad = w * 0.42f;
        c.drawRoundRect(mR, rad, rad, mBody);
        c.drawRoundRect(mR, rad, rad, mStroke);
        float cx = x0 + w / 2f;
        // power + status light
        mKey.setColor(0x33FFFFFF);
        c.drawCircle(cx, h * 0.07f, w * 0.06f, mKey);
        if (mConnected) {
            mGlow.setAlpha(255);
            c.drawCircle(cx + w * 0.26f, h * 0.07f, w * 0.03f, mGlow);
        } else {
            mGlow.setAlpha((int) (80 + 175 * mPulse));
            c.drawCircle(cx + w * 0.26f, h * 0.07f, w * 0.03f, mGlow);
        }
        // d-pad ring and centre
        float dy = h * 0.27f, dr = w * 0.36f;
        mKey.setColor(0x26FFFFFF);
        c.drawCircle(cx, dy, dr, mKey);
        mKey.setColor(0x40FFFFFF);
        c.drawCircle(cx, dy, dr * 0.42f, mKey);
        // Back (left) and Home (right) under the d-pad: the two keys to hold
        float ky = h * 0.46f, kr = w * 0.12f;
        float bx = cx - w * 0.22f, hx = cx + w * 0.22f;
        if (!mConnected) {
            mGlow.setAlpha((int) (60 + 195 * mPulse));
            float gr = kr * (1.15f + 0.35f * mPulse);
            c.drawCircle(bx, ky, gr, mGlow);
            c.drawCircle(hx, ky, gr, mGlow);
        }
        mKey.setColor(mConnected ? 0x40FFFFFF : 0xFFE8EAED);
        c.drawCircle(bx, ky, kr, mKey);
        c.drawCircle(hx, ky, kr, mKey);
        // glyphs on the two keys
        Paint g = mStroke;
        int old = g.getColor();
        float sw = g.getStrokeWidth();
        g.setColor(mConnected ? 0x99FFFFFF : 0xFF0E0E0F);
        g.setStrokeWidth(Math.max(2f, kr * 0.16f));
        c.drawLine(bx + kr * 0.35f, ky, bx - kr * 0.35f, ky, g);
        c.drawLine(bx - kr * 0.35f, ky, bx - kr * 0.05f, ky - kr * 0.3f, g);
        c.drawLine(bx - kr * 0.35f, ky, bx - kr * 0.05f, ky + kr * 0.3f, g);
        c.drawLine(hx - kr * 0.38f, ky - kr * 0.02f, hx, ky - kr * 0.38f, g);
        c.drawLine(hx, ky - kr * 0.38f, hx + kr * 0.38f, ky - kr * 0.02f, g);
        c.drawLine(hx - kr * 0.26f, ky - kr * 0.1f, hx - kr * 0.26f, ky + kr * 0.32f, g);
        c.drawLine(hx + kr * 0.26f, ky - kr * 0.1f, hx + kr * 0.26f, ky + kr * 0.32f, g);
        c.drawLine(hx - kr * 0.26f, ky + kr * 0.32f, hx + kr * 0.26f, ky + kr * 0.32f, g);
        g.setColor(old);
        g.setStrokeWidth(sw);
        // remaining keys
        mKey.setColor(0x26FFFFFF);
        for (int i = 0; i < 2; i++) {
            float yy = h * (0.57f + i * 0.08f);
            c.drawCircle(cx - w * 0.22f, yy, kr * 0.85f, mKey);
            c.drawCircle(cx + w * 0.22f, yy, kr * 0.85f, mKey);
        }
        mR.set(cx - w * 0.1f, h * 0.76f, cx + w * 0.1f, h * 0.9f);
        c.drawRoundRect(mR, w * 0.1f, w * 0.1f, mKey);
    }
}
