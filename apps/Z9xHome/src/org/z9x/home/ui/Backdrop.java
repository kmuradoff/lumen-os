package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import org.z9x.home.img.ImageLoader;

/**
 * Ambient backdrop (SPEC 7.2): a 192x108 blurred copy of the hero or focused art, scaled to the
 * screen with bilinear filtering at 38 % over the background, plus a radial vignette. Without art it
 * shows the Lumen gradient (two soft glows). Changes crossfade over 400 ms after a 250 ms dwell.
 */
public class Backdrop extends View {
    private static final float ART_ALPHA = 0.38f;
    private final Paint mBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mVignette = new Paint();
    private Bitmap mCur, mPrev;
    private long mFadeStart;
    private String mWantUri;
    private ImageLoader.Request mReq;
    private final ImageLoader mImages;
    private boolean mFrozen;
    private RadialGradient mGlowA, mGlowB;
    private final android.graphics.RectF mFull = new android.graphics.RectF();

    public Backdrop(Context c, ImageLoader il) {
        super(c);
        mImages = il;
        setWillNotDraw(false);
        mApply = () -> {
            if (mReq != null) mReq.cancel();
            final String uri = mWantUri;
            if (uri == null) {
                crossfadeTo(null);
                return;
            }
            Bitmap b = mImages.peekBlur(uri);
            if (b != null) {
                crossfadeTo(b);
                return;
            }
            mReq = mImages.loadBlur(uri, mPendingOwner, (bmp, col) -> {
                if (uri.equals(mWantUri)) crossfadeTo(bmp);
            });
        };
    }

    /** Shows the blurred art of {@code uri} (null = the brand gradient) after the dwell time. */
    public void show(String uri, String ownerPkg) {
        if (mFrozen) return;
        if (uri != null && uri.equals(mWantUri)) return;
        if (uri == null && mWantUri == null) return;
        mWantUri = uri;
        removeCallbacks(mApply);
        mPendingOwner = ownerPkg;
        postDelayed(mApply, Theme.animations() ? Theme.BACKDROP_DWELL_MS : 0);
    }

    private String mPendingOwner;
    private final Runnable mApply;

    /** Low memory: stop updating (keeps the current frame). */
    public void setFrozen(boolean f) {
        mFrozen = f;
    }

    public void release() {
        removeCallbacks(mApply);
        if (mReq != null) mReq.cancel();
        mCur = null;
        mPrev = null;
        mWantUri = null;
        invalidate();
    }

    private void crossfadeTo(Bitmap b) {
        if (b == mCur) return;
        mPrev = mCur;
        mCur = b;
        mFadeStart = Theme.animations() ? android.os.SystemClock.uptimeMillis() : 0;
        if (mFadeStart == 0) mPrev = null;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mFull.set(0, 0, w, h);
        mGlowA = new RadialGradient(w * 0.82f, h * 0.18f, w * 0.42f, Theme.GLOW_A, Theme.GLOW_A & 0x00FFFFFF, Shader.TileMode.CLAMP);
        mGlowB = new RadialGradient(w * 0.12f, h * 0.92f, w * 0.36f, Theme.GLOW_B, Theme.GLOW_B & 0x00FFFFFF, Shader.TileMode.CLAMP);
        mVignette.setShader(new RadialGradient(w / 2f, h / 2f, (float) Math.hypot(w / 2f, h / 2f),
                new int[]{Theme.BG & 0x00FFFFFF, Theme.BG & 0x00FFFFFF, (Theme.BG & 0x00FFFFFF) | 0x8C000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Theme.BG);
        float t = 1f;
        if (mFadeStart > 0) {
            t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - mFadeStart) / (float) Theme.BACKDROP_MS);
            if (t < 1f) postInvalidateOnAnimation();
            else {
                mFadeStart = 0;
                mPrev = null;
            }
        }
        drawLayer(c, mPrev, (1f - t), w, h);
        drawLayer(c, mCur, t, w, h);
        c.drawRect(0, 0, w, h, mVignette);
    }

    private void drawLayer(Canvas c, Bitmap b, float a, int w, int h) {
        if (a <= 0.001f) return;
        if (b == null) {
            drawGlows(c, w, h, a);
            return;
        }
        mBmp.setAlpha((int) (255 * ART_ALPHA * a));
        c.drawBitmap(b, null, mFull, mBmp);
    }

    private void drawGlows(Canvas c, int w, int h, float a) {
        if (mGlowA == null) return;
        mGlow.setAlpha((int) (255 * a));
        mGlow.setShader(mGlowA);
        c.drawCircle(w * 0.82f, h * 0.18f, w * 0.42f, mGlow);
        mGlow.setShader(mGlowB);
        c.drawCircle(w * 0.12f, h * 0.92f, w * 0.36f, mGlow);
        mGlow.setShader(null);
    }
}
