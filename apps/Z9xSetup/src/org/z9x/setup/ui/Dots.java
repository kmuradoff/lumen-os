package org.z9x.setup.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Progress: thin dots, the current one a 24 px accent pill; done dots brighter (SPEC 4.0). */
public class Dots extends View {
    private int mCount = 1;
    private int mCurrent;
    private float mAnimPos;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private ValueAnimator mAnim;

    public Dots(Context c) {
        super(c);
    }

    public void set(int count, int current) {
        mCount = Math.max(1, count);
        int target = Math.max(0, Math.min(current, mCount - 1));
        if (mAnim != null) mAnim.cancel();
        if (!isAttachedToWindow() || mCurrent == target) {
            mCurrent = target;
            mAnimPos = target;
            requestLayout();
            invalidate();
            return;
        }
        mAnim = ValueAnimator.ofFloat(mAnimPos, target);
        mAnim.setDuration(260);
        mAnim.setInterpolator(Ui.decel());
        mAnim.addUpdateListener(a -> { mAnimPos = (float) a.getAnimatedValue(); invalidate(); });
        mAnim.start();
        mCurrent = target;
        requestLayout();
    }

    @Override
    protected void onMeasure(int w, int h) {
        float d = Ui.pxf(8), gap = Ui.pxf(12), pill = Ui.pxf(24);
        setMeasuredDimension((int) Math.ceil((mCount - 1) * (d + gap) + pill), (int) Math.ceil(d));
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = Ui.pxf(8), gap = Ui.pxf(12), pill = Ui.pxf(24);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float x = 0;
        for (int i = 0; i < mCount; i++) {
            float k = Math.max(0f, 1f - Math.abs(mAnimPos - i));   // 1 = current
            float w = d + (pill - d) * k;
            float left = rtl ? getWidth() - x - w : x;
            mR.set(left, 0, left + w, d);
            int base = i < mAnimPos ? 0x99FFFFFF : 0x40FFFFFF;
            mP.setColor(k > 0.01f ? blend(base, Ui.ACCENT, k) : base);
            c.drawRoundRect(mR, d / 2, d / 2, mP);
            x += w + gap;
        }
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}
