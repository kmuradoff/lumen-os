package org.z9x.home.sky;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import org.z9x.home.ui.UiScale;

/**
 * Renders the static layers of one minute on the render thread (software canvas into bitmaps that
 * the view then only blits):
 * <ul>
 * <li>sky: gradient, sun and moon glow, grain; half resolution (smooth content, 2 MB at 1080p)</li>
 * <li>land: the scene from {@link Scenes#LAND_TOP} down, full resolution with alpha, grain</li>
 * <li>sprites: clouds (blurred, half resolution), the moon with its phase, a fog band (quarter
 * resolution); drawn by the view at moving positions</li>
 * </ul>
 * UI modes (1.0.1, {@link UiScale}): the soft layers (sky, clouds, fog) keep their 1080p size at 2K and
 * 4K (the GPU enlarges them, a gradient or a blur shows no difference); the crisp ones (the land's
 * silhouettes, the moon) are rendered at the real scale. Per frame ({@link #frameBytes}): 8.4 MB at
 * 1080p, 12.1 MB at 2K, 22.7 MB at 4K; missing or freed bitmaps are (re)allocated here.
 */
final class SkyRenderer {
    static final float CLOUD_W = 820, CLOUD_H = 320, CLOUD_BASE = 215;
    static final float FOG_W = 2500, FOG_H = 320, FOG_PAD = 90;
    static final float MOON_R = 34, MOON_BOX = 80;
    static final int MAX_CLOUDS = 5;

    /** What to render (built on the UI thread). */
    static final class Params {
        long utc, tzOffset;
        double lat, lon;
        int scene, weather;
        float scale;   // real px per design px of the view (1, 1.33 at 2K, 2 at 4K)
    }

    /** One rendered minute. */
    static final class Frame {
        final SkyLook look = new SkyLook();
        Bitmap sky, land, moon, fog;
        final Bitmap[] clouds = new Bitmap[MAX_CLOUDS];
        float scale;
        /** Top of the land layer (design px): {@link Scenes#LAND_TOP} moved up onto a whole real pixel. */
        float landTop = Scenes.LAND_TOP;
        long minute;

        void prepareToDraw() {
            prep(sky);
            prep(land);
            if (look.moonShow) prep(moon);
            if (look.fogN > 0) prep(fog);
            for (int i = 0; i < look.cloudN; i++) prep(clouds[i]);
        }

        private static void prep(Bitmap b) {
            if (b != null && !b.isRecycled()) b.prepareToDraw();
        }

        void recycle() {
            sky = rec(sky);
            land = rec(land);
            moon = rec(moon);
            fog = rec(fog);
            for (int i = 0; i < clouds.length; i++) clouds[i] = rec(clouds[i]);
        }

        private static Bitmap rec(Bitmap b) {
            if (b != null) b.recycle();
            return null;
        }
    }

    private final Scenes mScenes = new Scenes();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint mBlur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGrain = new Paint();
    private final Paint mGrainAtop = new Paint();
    private BitmapShader mLandGrain;
    private final Matrix mGrainM = new Matrix();
    private final RectF mR = new RectF();
    private final Path mPath = new Path();
    private BlurMaskFilter mCloudBlur, mRimBlur, mFogBlur;

    void render(Frame f, Params p) {
        SkyLook k = f.look;
        k.compute(p.utc, p.tzOffset, p.lat, p.lon, p.scene, p.weather);
        f.minute = Math.floorDiv(p.utc, 60_000L);
        f.scale = p.scale;
        f.landTop = landTop(p.scale);
        ensureShared();
        ensure(f, p.scale);
        sky(f);
        land(f);
        for (int i = 0; i < k.cloudN; i++) cloud(f.clouds[i], k, i);
        if (k.moonShow) moon(f.moon, k);
        if (k.fogN > 0) fog(f.fog, k);
    }

    // ------------------------------------------------------------------ layers

    private void sky(Frame f) {
        SkyLook k = f.look;
        Canvas c = new Canvas(f.sky);
        float s = f.sky.getWidth() / 1920f;
        c.save();
        c.scale(s, s);
        mPaint.setShader(new LinearGradient(0, 0, 0, 1080, new int[]{k.skyTop, k.skyMid, k.skyLow, k.skyLow},
                new float[]{0, k.midStop, k.lowStop, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, mPaint);
        if (k.glowA > 0.005f) {
            mPaint.setShader(new RadialGradient(k.sunX, k.sunY, 560, SkyLook.alpha(k.glowColor, k.glowA),
                    SkyLook.alpha(k.glowColor, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, mPaint);
        }
        if (k.moonShow && k.moonGlowA > 0.005f) {
            float a = k.moonGlowA;
            mPaint.setShader(new RadialGradient(k.moonX, k.moonY, 320,
                    new int[]{SkyLook.alpha(0xFFE8EEF2, a), SkyLook.alpha(0xFF9FB6C6, a * 0.33f), SkyLook.alpha(0xFF9FB6C6, 0)},
                    new float[]{0, 0.25f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 1920, 1080, mPaint);
        }
        mPaint.setShader(null);
        c.restore();
        c.drawRect(0, 0, f.sky.getWidth(), f.sky.getHeight(), mGrain);
    }

    private void land(Frame f) {
        Bitmap b = f.land;
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        float s = b.getWidth() / 1920f;
        c.save();
        c.scale(s, s);
        c.translate(0, -f.landTop);
        mScenes.draw(c, f.look);
        c.restore();
        // grain only where there is land; its specks stay one design px at 2K / 4K (filtered: not blocky)
        mGrainM.setScale(s, s);
        mLandGrain.setLocalMatrix(mGrainM);
        mGrainAtop.setFilterBitmap(s != 1f);
        c.drawRect(0, 0, b.getWidth(), b.getHeight(), mGrainAtop);
    }

    /**
     * A cloud of the prototype's size: soft puffs (a touch lighter) over a flat, slightly darker base,
     * as cumulus are; long and low under an overcast sky. A low sun lights the underside (dusk).
     * Everything stays 3 sigma inside the sprite, so no blur is cut off.
     */
    private void cloud(Bitmap b, SkyLook k, int i) {
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        float s = b.getWidth() / CLOUD_W;
        c.scale(s, s);
        Rnd r = new Rnd(100 + i);
        boolean flat = k.weather >= SkyLook.FOG;
        float sx = r.range(0.85f, 1.1f);
        mBlur.setMaskFilter(mCloudBlur);
        mBlur.setColor(SkyLook.mix(k.cloudColor, 0xFFFFFFFF, 0.06f));
        int n = 5 + (int) (r.next() * 3);
        for (int j = 0; j < n; j++) {
            float f = j / (float) (n - 1);
            float rad = (46 + 56 * (float) Math.sin(Math.PI * f)) * r.range(0.8f, 1.15f) * (flat ? 0.65f : 1f);
            float x = 410 + (f - 0.5f) * 460 * sx + r.range(-24, 24);
            ellipse(c, x, CLOUD_BASE - rad * 0.5f, rad * (flat ? 1.6f : 1.05f), rad * (flat ? 0.55f : 0.85f));
        }
        mBlur.setColor(SkyLook.mix(k.cloudColor, 0xFF000000, 0.1f));
        ellipse(c, 410, CLOUD_BASE + 6, 300 * sx, flat ? 30 : 38);
        float rim = Math.min(1f, k.rimA / Math.max(0.01f, k.cloudA));
        if (rim > 0.01f) {
            mBlur.setMaskFilter(mRimBlur);
            mBlur.setColor(SkyLook.alpha(k.rimColor, rim));
            ellipse(c, 410 + r.range(-40, 40), CLOUD_BASE + 26, 260 * sx, 14);
        }
        mBlur.setMaskFilter(null);
    }

    private void ellipse(Canvas c, float cx, float cy, float rx, float ry) {
        mR.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(mR, mBlur);
    }

    /**
     * The moon with its real phase: the lit limb points along +x, then the sprite is turned so it faces
     * the sun. A faint earthshine disc at night, a few darker maria on the lit part.
     */
    private void moon(Bitmap b, SkyLook k) {
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        float s = b.getWidth() / MOON_BOX;
        c.scale(s, s);
        c.translate(MOON_BOX / 2, MOON_BOX / 2);
        c.rotate((float) Math.toDegrees(k.moonAngle));
        float r = MOON_R;
        if (k.dark > 0) {
            mPaint.setColor(SkyLook.alpha(SkyLook.mix(k.skyTop, 0xFFFFFFFF, 0.1f), 0.5f * k.dark));
            c.drawCircle(0, 0, r, mPaint);
        }
        float kk = k.moonK;
        float bx = Math.abs(r * (1 - 2 * kk));
        mPath.rewind();
        mR.set(-r, -r, r, r);
        mPath.arcTo(mR, -90, 180, true);
        mR.set(-bx, -r, bx, r);
        mPath.arcTo(mR, 90, kk < 0.5f ? -180 : 180, false);
        mPath.close();
        mPaint.setColor(0xFFEEF2F2);
        c.drawPath(mPath, mPaint);
        c.save();
        c.clipPath(mPath);
        mPaint.setColor(0x1A5A6470);
        mR.set(-14, -18, 6, -2);
        c.drawOval(mR, mPaint);
        mR.set(2, -4, 22, 14);
        c.drawOval(mR, mPaint);
        mR.set(-20, 6, -6, 20);
        c.drawOval(mR, mPaint);
        c.restore();
    }

    private void fog(Bitmap b, SkyLook k) {
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        float s = b.getWidth() / FOG_W;
        c.scale(s, s);
        mBlur.setMaskFilter(mFogBlur);
        mBlur.setColor(k.mist);
        mR.set(FOG_PAD, FOG_PAD, FOG_W - FOG_PAD, FOG_H - FOG_PAD);
        c.drawRoundRect(mR, 70, 70, mBlur);
        mBlur.setMaskFilter(null);
    }

    // ------------------------------------------------------------------ buffers

    // layer sizes for a view scale: {skyW, skyH, landW, landH, cloudW, cloudH, moon, fogW, fogH}
    static final int SKY_W = 0, SKY_H = 1, LAND_W = 2, LAND_H = 3, CLOUD_PX_W = 4, CLOUD_PX_H = 5, MOON = 6, FOG_PX_W = 7,
            FOG_PX_H = 8;

    /**
     * Top of the land layer in design px for a view scale: LAND_TOP on a whole real pixel (2K: 460 would
     * be y 613.33, and the crisp layer drawn a third of a pixel off would be resampled, soft).
     */
    static float landTop(float scale) {
        float crisp = UiScale.crisp(scale);
        return (float) Math.floor(Scenes.LAND_TOP * crisp + 1e-3f) / crisp;
    }

    /** Bitmap sizes of a frame for a view of {@code scale} real px per design px (soft layers capped at 1080p's). */
    static int[] sizes(float scale) {
        float soft = UiScale.soft(scale), crisp = UiScale.crisp(scale);
        int landTop = (int) Math.floor(Scenes.LAND_TOP * crisp + 1e-3f);
        return new int[]{
                Math.max(2, Math.round(1920 * soft / 2)), Math.max(2, Math.round(1080 * soft / 2)),
                Math.max(2, Math.round(1920 * crisp)), Math.max(2, Math.round(1080 * crisp) - landTop),
                Math.max(2, Math.round(CLOUD_W / 2 * soft)), Math.max(2, Math.round(CLOUD_H / 2 * soft)),
                Math.max(2, Math.round(MOON_BOX * crisp)),
                Math.max(2, Math.round(FOG_W / 4 * soft)), Math.max(2, Math.round(FOG_H / 4 * soft))};
    }

    /** Bytes of the largest frame (all clouds, the moon and fog) at a view scale: the memory estimate. */
    static long frameBytes(float scale) {
        int[] z = sizes(scale);
        long px = (long) z[SKY_W] * z[SKY_H] + (long) z[LAND_W] * z[LAND_H] + (long) MAX_CLOUDS * z[CLOUD_PX_W] * z[CLOUD_PX_H]
                + (long) z[MOON] * z[MOON] + (long) z[FOG_PX_W] * z[FOG_PX_H];
        return px * 4;
    }

    private void ensure(Frame f, float scale) {
        int[] z = sizes(scale);
        f.sky = fit(f.sky, z[SKY_W], z[SKY_H]);
        f.sky.setHasAlpha(false);
        f.land = fit(f.land, z[LAND_W], z[LAND_H]);
        for (int i = 0; i < f.look.cloudN; i++) f.clouds[i] = fit(f.clouds[i], z[CLOUD_PX_W], z[CLOUD_PX_H]);
        if (f.look.moonShow) f.moon = fit(f.moon, z[MOON], z[MOON]);
        if (f.look.fogN > 0) f.fog = fit(f.fog, z[FOG_PX_W], z[FOG_PX_H]);
    }

    private static Bitmap fit(Bitmap b, int w, int h) {
        if (b != null && !b.isRecycled() && b.getWidth() == w && b.getHeight() == h) return b;
        if (b != null) b.recycle();
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    }

    /** Film grain tile (white specks, 2 % on average, at most 8 %: the prototypes' feTurbulence), blurs; once. */
    private void ensureShared() {
        if (mCloudBlur != null) return;
        // BlurMaskFilter radius -> sigma is 0.577 r + 0.5 and follows the canvas scale (design px)
        mCloudBlur = new BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL);
        mRimBlur = new BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL);
        mFogBlur = new BlurMaskFilter(51f, BlurMaskFilter.Blur.NORMAL);
        int n = 128;
        int[] px = new int[n * n];
        Rnd r = new Rnd(3);
        for (int i = 0; i < px.length; i++) {
            float v = (r.next() + r.next()) / 2;
            px[i] = (Math.round(v * v * 255 * 0.08f) << 24) | 0xFFFFFF;
        }
        Bitmap tile = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        mGrain.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        mGrain.setAlpha(128); // the sky is upscaled 2x: half strength, or the specks show
        mLandGrain = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        mGrainAtop.setShader(mLandGrain);
        mGrainAtop.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    }
}
