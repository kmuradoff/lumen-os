package org.z9x.setup.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Picture step: black screen, thin white 3x3 grid, corner marks and a centre cross (SPEC 4.5). */
public class GridArt extends View {
    private final Paint mLine = new Paint();
    private final Paint mMark = new Paint();

    public GridArt(Context c) {
        super(c);
        mLine.setColor(0x80FFFFFF);
        mLine.setStrokeWidth(Math.max(1f, Ui.pxf(2)));
        mMark.setColor(0xFFFFFFFF);
        mMark.setStrokeWidth(Math.max(2f, Ui.pxf(6)));
        setBackgroundColor(0xFF000000);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        for (int i = 1; i < 3; i++) {
            c.drawLine(w * i / 3f, 0, w * i / 3f, h, mLine);
            c.drawLine(0, h * i / 3f, w, h * i / 3f, mLine);
        }
        float in = Ui.pxf(3), len = Ui.pxf(90);
        // corners
        c.drawLine(in, in, in + len, in, mMark);
        c.drawLine(in, in, in, in + len, mMark);
        c.drawLine(w - in, in, w - in - len, in, mMark);
        c.drawLine(w - in, in, w - in, in + len, mMark);
        c.drawLine(in, h - in, in + len, h - in, mMark);
        c.drawLine(in, h - in, in, h - in - len, mMark);
        c.drawLine(w - in, h - in, w - in - len, h - in, mMark);
        c.drawLine(w - in, h - in, w - in, h - in - len, mMark);
        float cx = w / 2f, cy = h / 2f, k = Ui.pxf(40);
        c.drawLine(cx - k, cy, cx + k, cy, mMark);
        c.drawLine(cx, cy - k, cx, cy + k, mMark);
    }
}
