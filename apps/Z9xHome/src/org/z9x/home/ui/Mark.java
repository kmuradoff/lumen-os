package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The Lumen mark, variant 2A "aperture, facets" (D_Mark): six facets alternating #F4EFE6 / #CFC7BA
 * around a hexagonal opening with a warm core, followed by the "lumen" wordmark. Drawn from paths
 * (viewBox -100..100, the mockup's exact geometry); nothing is allocated in onDraw.
 */
public class Mark extends View {
    private static final Path FACET = new Path();

    static {
        // M0 -36 L59.4 -70.3 A92 92 0 0 1 90.5 16.3 L31.2 -18 Z; the other five are this one turned by 60 deg
        FACET.moveTo(0f, -36f);
        FACET.lineTo(59.4f, -70.3f);
        FACET.arcTo(new RectF(-92f, -92f, 92f, 92f), -49.8f, 60f, false);
        FACET.lineTo(31.2f, -18f);
        FACET.close();
    }

    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mWord = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint.FontMetrics mFm = new Paint.FontMetrics();
    private final int mSize, mGap;
    private final boolean mWordmark;
    private static final String WORD = "lumen";

    /** @param sizeDesign mark size in design px; {@code wordmark} adds "lumen" after it */
    public Mark(Context c, int sizeDesign, boolean wordmark) {
        super(c);
        mSize = Theme.px(sizeDesign);
        mGap = Theme.px(14);
        mWordmark = wordmark;
        mWord.setTypeface(Theme.SEMIBOLD);
        mWord.setTextSize(Theme.pxf(30));
        mWord.setLetterSpacing(-0.015f);
        mWord.setColor(Theme.TEXT1);
        setContentDescription("Lumen OS");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = mSize + (mWordmark ? mGap + (int) Math.ceil(mWord.measureText(WORD)) : 0);
        setMeasuredDimension(w, Math.max(mSize, Theme.px(40)));
    }

    @Override
    protected void onDraw(Canvas c) {
        int h = getHeight();
        boolean rtl = Theme.rtl(this);
        int total = getMeasuredWidth();
        float markX = rtl ? total - mSize : 0;
        draw(c, mFill, markX + mSize / 2f, h / 2f, mSize);
        if (mWordmark) {
            mWord.getFontMetrics(mFm);
            float y = h / 2f - (mFm.ascent + mFm.descent) / 2f;
            float x = rtl ? 0 : mSize + mGap;
            c.drawText(WORD, x, y, mWord);
        }
    }

    /** Draws the mark centred at (cx, cy), {@code size} px across. */
    public static void draw(Canvas c, Paint p, float cx, float cy, float size) {
        float s = size / 200f;
        int save = c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 6; i++) {
            p.setColor(i % 2 == 0 ? Theme.FACET_A : Theme.FACET_B);
            c.drawPath(FACET, p);
            c.rotate(60f);
        }
        p.setColor(Theme.ACCENT);
        // small sizes get a larger core (D_Mark: 19 at 164 px, 24 at 40 px)
        c.drawCircle(0f, 0f, size >= 120 ? 19f : 24f, p);
        c.restoreToCount(save);
    }
}
