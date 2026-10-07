package org.z9x.setup.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** On/off switch drawn in code: on = accent_strong track, white thumb (brand tokens). */
public class SwitchView extends View {
    private boolean mOn;
    private float mPos;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private ValueAnimator mAnim;

    public SwitchView(Context c) {
        super(c);
    }

    public boolean isOn() { return mOn; }

    public void setOn(boolean on, boolean animate) {
        if (mOn == on && (mAnim == null || !animate)) {
            mPos = on ? 1f : 0f;
            invalidate();
            return;
        }
        mOn = on;
        if (mAnim != null) mAnim.cancel();
        if (!animate || !isAttachedToWindow()) {
            mPos = on ? 1f : 0f;
            invalidate();
            return;
        }
        mAnim = ValueAnimator.ofFloat(mPos, on ? 1f : 0f);
        mAnim.setDuration(160);
        mAnim.setInterpolator(Ui.decel());
        mAnim.addUpdateListener(a -> { mPos = (float) a.getAnimatedValue(); invalidate(); });
        mAnim.start();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean f = Ui.focusedState(this);
        float h = Math.min(getHeight(), getWidth() / 1.8f);
        float w = h * 1.8f;
        float x0 = (getWidth() - w) / 2f, y0 = (getHeight() - h) / 2f;
        mR.set(x0, y0, x0 + w, y0 + h);
        int off = f ? 0x4D000000 : 0x4DFFFFFF;
        mP.setColor(blend(off, Ui.ACCENT_STRONG, mPos));
        c.drawRoundRect(mR, h / 2f, h / 2f, mP);
        float r = h / 2f - Ui.pxf(4);
        float cx = x0 + h / 2f + (w - h) * mPos;
        mP.setColor(0xFFFFFFFF);
        c.drawCircle(cx, y0 + h / 2f, r, mP);
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
