package org.z9x.setup.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Draws a QR code on a white rounded card with the standard 4-module quiet zone. */
public class QrView extends View {
    private Qr mQr;
    private final Paint mDark = new Paint();
    private final Paint mLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();

    public QrView(Context c) {
        super(c);
        mDark.setColor(0xFF000000);
        mLight.setColor(0xFFFFFFFF);
    }

    public void setText(String s) {
        mQr = s == null ? null : Qr.encode(s);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        mR.set(0, 0, s, s);
        c.drawRoundRect(mR, Ui.pxf(16), Ui.pxf(16), mLight);
        if (mQr == null) return;
        int n = mQr.size + 8;
        float m = (float) Math.floor(s / n);
        float off = (s - m * mQr.size) / 2f;
        for (int y = 0; y < mQr.size; y++) {
            for (int x = 0; x < mQr.size; x++) {
                if (mQr.get(x, y)) c.drawRect(off + x * m, off + y * m, off + (x + 1) * m, off + (y + 1) * m, mDark);
            }
        }
    }
}
