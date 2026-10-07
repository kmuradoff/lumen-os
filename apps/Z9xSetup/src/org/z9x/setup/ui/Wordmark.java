package org.z9x.setup.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

/**
 * The text-rendered "Lumen OS" mark (brand SPEC 9: Roboto light/thin + tracking, no bitmap). At rest
 * 28 px high text, 60 % white, top-start of the wizard; the welcome screen scales it up from the
 * centre of the screen (the boot animation's last frame) to here.
 */
public class Wordmark extends View {
    private final TextPaint mMain = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mOs = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final String MAIN = "Lumen";
    private static final String OS = "OS";
    private final float mGap;

    public Wordmark(Context c) {
        super(c);
        float size = Ui.pxf(30);
        mMain.setTypeface(Ui.light());
        mMain.setTextSize(size);
        mMain.setLetterSpacing(0.14f);
        mMain.setColor(0xD9FFFFFF);
        mOs.setTypeface(Ui.thin());
        mOs.setTextSize(size);
        mOs.setLetterSpacing(0.14f);
        mOs.setColor(0x99FFFFFF);
        mLine.setColor(Ui.ACCENT);
        mLine.setStrokeWidth(Math.max(1f, Ui.pxf(2)));
        mGap = Ui.pxf(12);
        // The mark itself is always drawn left-to-right (onDraw ignores the layout direction); the
        // view keeps the inherited direction so its start margin resolves on the correct side in RTL.
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        float w = mMain.measureText(MAIN) + mGap + mOs.measureText(OS);
        Paint.FontMetrics fm = mMain.getFontMetrics();
        float h = (fm.descent - fm.ascent) + Ui.pxf(14);
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h));
    }

    @Override
    protected void onDraw(Canvas c) {
        Paint.FontMetrics fm = mMain.getFontMetrics();
        float base = -fm.ascent;
        c.drawText(MAIN, 0, base, mMain);
        float x = mMain.measureText(MAIN) + mGap;
        c.drawText(OS, x, base, mOs);
        float ly = base + Ui.pxf(10);
        c.drawLine(0, ly, Ui.pxf(36), ly, mLine);
    }
}
