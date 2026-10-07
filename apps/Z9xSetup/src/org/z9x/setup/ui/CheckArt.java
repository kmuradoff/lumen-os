package org.z9x.setup.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.view.View;

/** Done screen: an accent ring that scales 0.9 -> 1 with the check drawn in (200 ms + 260 ms). */
public class CheckArt extends View {
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCheck = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Path mPart = new Path();
    private float mT = 1f;

    public CheckArt(Context c) {
        super(c);
        mRing.setColor(Ui.ACCENT_STRONG);
        mCheck.setStyle(Paint.Style.STROKE);
        mCheck.setStrokeCap(Paint.Cap.ROUND);
        mCheck.setStrokeJoin(Paint.Join.ROUND);
        mCheck.setColor(0xFFFFFFFF);
    }

    public void play() {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(460);
        a.setStartDelay(120);
        a.setInterpolator(Ui.decel());
        a.addUpdateListener(an -> { mT = (float) an.getAnimatedValue(); invalidate(); });
        mT = 0f;
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float ringT = Math.min(1f, mT / 0.45f);
        float scale = 0.9f + 0.1f * ringT;
        mRing.setAlpha((int) (255 * ringT));
        c.drawCircle(cx, cy, s / 2f * scale, mRing);
        float checkT = Math.max(0f, (mT - 0.3f) / 0.7f);
        if (checkT <= 0f) return;
        mCheck.setStrokeWidth(s * 0.08f);
        mPath.reset();
        mPath.moveTo(cx - s * 0.2f, cy + s * 0.01f);
        mPath.lineTo(cx - s * 0.05f, cy + s * 0.16f);
        mPath.lineTo(cx + s * 0.22f, cy - s * 0.14f);
        PathMeasure pm = new PathMeasure(mPath, false);
        float len = 0;
        do { len += pm.getLength(); } while (pm.nextContour());
        mPart.reset();
        pm.setPath(mPath, false);
        pm.getSegment(0, len * checkT, mPart, true);
        c.drawPath(mPart, mCheck);
    }
}
