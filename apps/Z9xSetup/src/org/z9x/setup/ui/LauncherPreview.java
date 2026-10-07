package org.z9x.setup.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Schematic previews of the two home screens, drawn in code (0 bitmap memory instead of two
 * 960x540 WebPs, SPEC 7). LUMEN: Google-TV-like top bar with tabs, a big hero banner and a row of
 * app cards. CLASSIC: the Android TV launcher's favourite-apps row and channel rows.
 */
public class LauncherPreview extends View {
    public static final int LUMEN = 0, CLASSIC = 1;
    private final int mKind;
    private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private final android.graphics.Path mClip = new android.graphics.Path();
    private static final int[] APP_COLORS = {0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00897B, 0xFF3949AB};

    public LauncherPreview(Context c, int kind) {
        super(c);
        mKind = kind;
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mHero.setShader(new LinearGradient(0, 0, w, h * 0.6f,
                new int[]{0xFF2B4A7E, 0xFF5B3F8C, 0xFF1C2433}, null, Shader.TileMode.CLAMP));
    }

    private void rr(Canvas c, float l, float t, float r, float b, float rad, int color) {
        mP.setColor(color);
        mR.set(l, t, r, b);
        c.drawRoundRect(mR, rad, rad, mP);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float rad = Ui.pxf(18);
        c.save();
        mR.set(0, 0, w, h);
        mClip.reset();
        mClip.addRoundRect(mR, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(mClip);
        mP.setColor(0xFF0F1115);
        c.drawRect(0, 0, w, h, mP);
        if (mKind == LUMEN) drawLumen(c, w, h);
        else drawClassic(c, w, h);
        c.restore();
    }

    private void drawLumen(Canvas c, float w, float h) {
        float m = w * 0.045f;
        // top bar: mark, tabs (first focused), clock
        rr(c, m, h * 0.055f, m + w * 0.07f, h * 0.085f, h * 0.015f, 0x99FFFFFF);
        float tx = w * 0.3f;
        for (int i = 0; i < 4; i++) {
            float tw = w * (i == 0 ? 0.1f : 0.075f);
            rr(c, tx, h * 0.045f, tx + tw, h * 0.095f, h * 0.025f, i == 0 ? 0xFFE8EAED : 0x26FFFFFF);
            tx += tw + w * 0.02f;
        }
        rr(c, w - m - w * 0.06f, h * 0.055f, w - m, h * 0.085f, h * 0.015f, 0x66FFFFFF);
        // hero
        mR.set(m, h * 0.14f, w - m, h * 0.6f);
        c.drawRoundRect(mR, h * 0.03f, h * 0.03f, mHero);
        rr(c, m + w * 0.04f, h * 0.36f, m + w * 0.34f, h * 0.405f, h * 0.02f, 0xE6FFFFFF);
        rr(c, m + w * 0.04f, h * 0.43f, m + w * 0.26f, h * 0.455f, h * 0.012f, 0x80FFFFFF);
        rr(c, m + w * 0.04f, h * 0.49f, m + w * 0.14f, h * 0.545f, h * 0.027f, 0xFFE8EAED);
        // row label + app cards
        rr(c, m, h * 0.655f, m + w * 0.12f, h * 0.675f, h * 0.01f, 0x80FFFFFF);
        float cw = (w - 2 * m - 4 * w * 0.02f) / 5f;
        for (int i = 0; i < 5; i++) {
            float l = m + i * (cw + w * 0.02f);
            rr(c, l, h * 0.7f, l + cw, h * 0.7f + cw * 0.5625f, h * 0.02f, APP_COLORS[i] & 0xD9FFFFFF);
            if (i == 0) {
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Ui.pxf(3));
                rr(c, l - Ui.pxf(3), h * 0.7f - Ui.pxf(3), l + cw + Ui.pxf(3), h * 0.7f + cw * 0.5625f + Ui.pxf(3), h * 0.024f, 0xFFE8EAED);
                mP.setStyle(Paint.Style.FILL);
            }
        }
    }

    private void drawClassic(Canvas c, float w, float h) {
        float m = w * 0.045f;
        // search orb + clock
        mP.setColor(0x40FFFFFF);
        c.drawCircle(m + h * 0.04f, h * 0.08f, h * 0.035f, mP);
        rr(c, w - m - w * 0.06f, h * 0.065f, w - m, h * 0.095f, h * 0.015f, 0x66FFFFFF);
        // favourite apps row
        float aw = (w - 2 * m - 6 * w * 0.015f) / 7f;
        for (int i = 0; i < 7; i++) {
            float l = m + i * (aw + w * 0.015f);
            rr(c, l, h * 0.17f, l + aw, h * 0.17f + aw * 0.5625f, h * 0.012f, APP_COLORS[i] & 0xCCFFFFFF);
        }
        // channel rows: logo + programme cards
        for (int r = 0; r < 2; r++) {
            float top = h * (0.4f + r * 0.29f);
            mP.setColor(0x33FFFFFF);
            c.drawCircle(m + h * 0.05f, top + h * 0.09f, h * 0.045f, mP);
            float l = m + h * 0.13f;
            float cw = w * 0.23f;
            for (int i = 0; i < 4; i++) {
                rr(c, l, top, l + cw, top + h * 0.2f, h * 0.012f, i == 0 && r == 0 ? 0x66FFFFFF : 0x22FFFFFF);
                l += cw + w * 0.015f;
            }
        }
    }
}
