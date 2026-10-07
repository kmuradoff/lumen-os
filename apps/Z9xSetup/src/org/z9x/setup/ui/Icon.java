package org.z9x.setup.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Small line glyphs drawn in code (no bitmaps, crisp at any size). The colour follows the focus
 * state of the parent row (duplicateParentState): light on dark, dark on the focus fill.
 */
public class Icon extends View {
    public static final int CHECK = 1, LOCK = 2, WIFI = 3, ETHERNET = 4, CHEVRON = 5, PLUS = 6,
            MORE = 7, BLUETOOTH = 8, SPINNER = 9, DOT = 10, GLOBE = 11, WARN = 12;

    private int mType;
    private int mLevel = 4;
    private boolean mLocked;
    private int mColor;       // 0 = follow focus
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mR = new RectF();
    private ValueAnimator mSpin;
    private float mAngle;

    public Icon(Context c, int type) {
        super(c);
        mType = type;
        mP.setStrokeCap(Paint.Cap.ROUND);
        mP.setStrokeJoin(Paint.Join.ROUND);
    }

    public Icon type(int t) { mType = t; updateSpin(); invalidate(); return this; }

    public int type() { return mType; }

    public Icon level(int l) { mLevel = Math.max(0, Math.min(4, l)); invalidate(); return this; }

    public Icon locked(boolean b) { mLocked = b; invalidate(); return this; }

    /** Fixed colour instead of the focus-following one. */
    public Icon color(int c) { mColor = c; invalidate(); return this; }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateSpin();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mSpin != null) mSpin.cancel();
        mSpin = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int vis) {
        super.onVisibilityChanged(changed, vis);
        updateSpin();
    }

    private void updateSpin() {
        boolean want = mType == SPINNER && isAttachedToWindow() && isShown();
        if (want && mSpin == null) {
            mSpin = ValueAnimator.ofFloat(0f, 360f);
            mSpin.setDuration(1000);
            mSpin.setRepeatCount(ValueAnimator.INFINITE);
            mSpin.setInterpolator(new LinearInterpolator());
            mSpin.addUpdateListener(a -> { mAngle = (float) a.getAnimatedValue(); invalidate(); });
            mSpin.start();
        } else if (!want && mSpin != null) {
            mSpin.cancel();
            mSpin = null;
        }
    }

    private int color() {
        if (mColor != 0) return mColor;
        return Ui.focusedState(this) ? Ui.FOCUS_TEXT : Ui.TEXT;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        int col = color();
        mP.setColor(col);
        mP.setStyle(Paint.Style.STROKE);
        mP.setStrokeWidth(Math.max(2f, s * 0.085f));
        mPath.reset();
        switch (mType) {
            case CHECK:
                mPath.moveTo(cx - s * 0.32f, cy + s * 0.02f);
                mPath.lineTo(cx - s * 0.08f, cy + s * 0.26f);
                mPath.lineTo(cx + s * 0.34f, cy - s * 0.24f);
                c.drawPath(mPath, mP);
                break;
            case LOCK:
                drawLock(c, cx, cy, s);
                break;
            case WIFI: {
                // four quarter-arcs fanning up from a point near the bottom
                float by = cy + s * 0.36f;
                for (int i = 0; i < 4; i++) {
                    float r = s * (0.14f + 0.2f * i);
                    mP.setAlpha(i < mLevel ? Color.alpha(col) : Color.alpha(col) / 4);
                    if (i == 0) {
                        mP.setStyle(Paint.Style.FILL);
                        c.drawCircle(cx, by - s * 0.02f, s * 0.07f, mP);
                        mP.setStyle(Paint.Style.STROKE);
                        continue;
                    }
                    mR.set(cx - r, by - r, cx + r, by + r);
                    c.drawArc(mR, 225, 90, false, mP);
                }
                mP.setAlpha(255);
                if (mLocked) {
                    mP.setColor(col);
                    drawLock(c, cx + s * 0.34f, cy + s * 0.26f, s * 0.42f);
                }
                break;
            }
            case ETHERNET: {
                float a = s * 0.3f;
                mR.set(cx - a, cy - a * 0.9f, cx + a, cy + a * 0.6f);
                c.drawRoundRect(mR, s * 0.06f, s * 0.06f, mP);
                c.drawLine(cx - a * 0.4f, cy + a * 0.6f, cx - a * 0.4f, cy + a, mP);
                c.drawLine(cx + a * 0.4f, cy + a * 0.6f, cx + a * 0.4f, cy + a, mP);
                c.drawLine(cx, cy - a * 0.45f, cx, cy + a * 0.15f, mP);
                break;
            }
            case CHEVRON: {
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                float d = rtl ? -1 : 1;
                mPath.moveTo(cx - d * s * 0.12f, cy - s * 0.26f);
                mPath.lineTo(cx + d * s * 0.14f, cy);
                mPath.lineTo(cx - d * s * 0.12f, cy + s * 0.26f);
                c.drawPath(mPath, mP);
                break;
            }
            case PLUS:
                c.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, mP);
                c.drawLine(cx, cy - s * 0.28f, cx, cy + s * 0.28f, mP);
                break;
            case MORE:
                mP.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.26f, cy, s * 0.07f, mP);
                break;
            case BLUETOOTH:
                mPath.moveTo(cx - s * 0.2f, cy - s * 0.17f);
                mPath.lineTo(cx + s * 0.18f, cy + s * 0.17f);
                mPath.lineTo(cx, cy + s * 0.36f);
                mPath.lineTo(cx, cy - s * 0.36f);
                mPath.lineTo(cx + s * 0.18f, cy - s * 0.17f);
                mPath.lineTo(cx - s * 0.2f, cy + s * 0.17f);
                c.drawPath(mPath, mP);
                break;
            case SPINNER: {
                float r = s * 0.36f;
                mR.set(cx - r, cy - r, cx + r, cy + r);
                mP.setAlpha(Color.alpha(col) / 5);
                c.drawOval(mR, mP);
                mP.setAlpha(255);
                c.drawArc(mR, mAngle - 90, 100, false, mP);
                break;
            }
            case DOT:
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, s * 0.14f, mP);
                break;
            case GLOBE: {
                float r = s * 0.36f;
                c.drawCircle(cx, cy, r, mP);
                mR.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r);
                c.drawOval(mR, mP);
                c.drawLine(cx - r, cy, cx + r, cy, mP);
                break;
            }
            case WARN: {
                mPath.moveTo(cx, cy - s * 0.36f);
                mPath.lineTo(cx + s * 0.38f, cy + s * 0.3f);
                mPath.lineTo(cx - s * 0.38f, cy + s * 0.3f);
                mPath.close();
                c.drawPath(mPath, mP);
                c.drawLine(cx, cy - s * 0.1f, cx, cy + s * 0.08f, mP);
                mP.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy + s * 0.19f, s * 0.035f, mP);
                break;
            }
            default:
                break;
        }
    }

    private void drawLock(Canvas c, float cx, float cy, float s) {
        mP.setStyle(Paint.Style.FILL);
        mR.set(cx - s * 0.24f, cy - s * 0.04f, cx + s * 0.24f, cy + s * 0.3f);
        c.drawRoundRect(mR, s * 0.05f, s * 0.05f, mP);
        mP.setStyle(Paint.Style.STROKE);
        float sw = mP.getStrokeWidth();
        mP.setStrokeWidth(Math.max(1.5f, s * 0.07f));
        mR.set(cx - s * 0.15f, cy - s * 0.28f, cx + s * 0.15f, cy + s * 0.04f);
        c.drawArc(mR, 180, 180, false, mP);
        mP.setStrokeWidth(sw);
    }

    /** android.graphics.Color without the import clash in this file. */
    private static final class Color {
        static int alpha(int c) { return c >>> 24; }
    }
}
