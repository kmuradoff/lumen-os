package org.z9x.setup.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A simple four-colour "G" ring (generic glyph, drawn in code) for the Google sign-in step. */
public class GoogleG extends View {
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();

    public GoogleG(Context c) {
        super(c);
        mP.setStyle(Paint.Style.STROKE);
        mP.setStrokeCap(Paint.Cap.BUTT);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float sw = s * 0.18f;
        float r = (s - sw) / 2f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        mR.set(cx - r, cy - r, cx + r, cy + r);
        mP.setStrokeWidth(sw);
        mP.setColor(0xFFEA4335);
        c.drawArc(mR, 200, 115, false, mP);
        mP.setColor(0xFFFBBC05);
        c.drawArc(mR, 140, 60, false, mP);
        mP.setColor(0xFF34A853);
        c.drawArc(mR, 40, 100, false, mP);
        mP.setColor(0xFF4285F4);
        c.drawArc(mR, 0, 40, false, mP);
        mP.setStyle(Paint.Style.FILL);
        c.drawRect(cx, cy - sw / 2f, cx + r + sw / 2f, cy + sw / 2f, mP);
        mP.setStyle(Paint.Style.STROKE);
    }
}
