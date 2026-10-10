package org.z9x.home.sky;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import org.z9x.home.R;

import java.util.TimeZone;

/**
 * Live wallpaper of Lumen Home and the screensaver (prototype D_Live): a vector landscape under a sky
 * driven by the real sun and moon at the given place (NOAA model, {@link SolarCalc}), its colours keyed
 * by the sun elevation, tinted by season and weather.
 *
 * Cost model (MT9681): the sky, the land and the sprites are rendered once a minute (and on size,
 * scene, weather or place changes) on a background thread into a fresh set of bitmaps (~8.4 MB at
 * 1080p, 12.1 MB at 2K, 22.7 MB at 4K: the soft layers keep their 1080p size, the land and the moon
 * follow the real pixels, {@link SkyRenderer#frameBytes}); the previous set is freed once it is off
 * screen, so two exist only while rendering or crossfading (peak ~17 / 24 / 45 MB plus the GPU copy of
 * the shown one). Particles, the sun and the scrims are drawn as vectors at the real resolution. Each
 * frame only blits them and moves
 * a few particles (stars, clouds, glints,
 * birds, fog, fireflies, rain, snow) with preallocated state. Frames come from the Choreographer at the
 * rate the visible motion needs (8..30 fps, vsync multiples at 60 Hz); with nothing moving a 1 s tick only watches the clock.
 * Everything stops while paused, still (once a frame is shown), hidden or detached; bitmaps are freed
 * on detach and 20 s after the window is hidden. With system animations off the particles stand still.
 */
public final class SkyView extends View {
    public static final int WEATHER_CLEAR = 0, WEATHER_CLOUDS = 1, WEATHER_FOG = 2, WEATHER_RAIN = 3, WEATHER_SNOW = 4;
    public static final String AUTO = "auto";

    private static final String TAG = "Z9xSky";
    private static final int BG = 0xFF0A0908;
    private static final long FADE_MS = 700;
    private static final long RELEASE_HIDDEN_MS = 20_000;
    private static final long BACK_GRACE_MS = 120;   // a swapped-out frame may still be on screen
    private static final long RETRY_MS = 10_000;     // after a failed render (out of memory, ...)
    private static final float BIRD_PERIOD = 200, BIRD_CROSS = 70;

    private static Handler sWorker;

    private static synchronized Handler worker() {
        if (sWorker == null) {
            HandlerThread t = new HandlerThread("z9x-sky", Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sWorker = new Handler(t.getLooper());
        }
        return sWorker;
    }

    // ------------------------------------------------------------------ settings
    private double mLat = 55.75, mLon = 37.62;
    private int mWeather = WEATHER_CLEAR;
    private String mScene = AUTO;
    private boolean mPaused, mStill;
    private float mScrimLeft, mScrimBottom, mScrimTop = 1080, mHeadEnd;

    // ------------------------------------------------------------------ frames
    private final SkyRenderer mRenderer = new SkyRenderer();   // render thread only
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private SkyRenderer.Frame mFront, mBack, mFadeFrom;
    private long mFadeStart;
    private boolean mInFlight, mBackFree = true, mWantRender, mDirty;
    private long mRetryAt;
    private int mGeneration;
    private int mBuiltScene = -1, mBuiltWeather = -1;

    // ------------------------------------------------------------------ state
    private boolean mAttached, mWinVisible = true, mActive, mFramePosted, mTickPosted;
    private float mS = 1, mOx, mOy, mRenderScale = 1;
    private final long mT0 = SystemClock.uptimeMillis();
    private final double[] mTmp = new double[4];

    // ------------------------------------------------------------------ drawing (preallocated)
    private final Paint mBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mScrimL = new Paint(Paint.DITHER_FLAG);
    private final Paint mScrimB = new Paint(Paint.DITHER_FLAG);
    private final Paint mScrimH = new Paint(Paint.DITHER_FLAG);
    private final RectF mDst = new RectF();
    private final Path mBird = new Path();
    private Bitmap mGlow;

    // particles (design px), rebuilt when the scene or the weather changes
    private static final int STARS = 80, GLINTS = 16, DROPS = 140, FLAKES = 110, FLIES = 22, BIRDS = 5;
    private final float[] mStarX = new float[STARS], mStarY = new float[STARS], mStarR = new float[STARS],
            mStarP = new float[STARS], mStarPh = new float[STARS];
    private final float[] mCloudY = new float[SkyRenderer.MAX_CLOUDS], mCloudP = new float[SkyRenderer.MAX_CLOUDS],
            mCloudPh = new float[SkyRenderer.MAX_CLOUDS];
    private final float[] mGlintDx = new float[GLINTS], mGlintW = new float[GLINTS], mGlintP = new float[GLINTS],
            mGlintPh = new float[GLINTS];
    private final float[] mDropX = new float[DROPS], mDropP = new float[DROPS], mDropPh = new float[DROPS];
    private final float[] mRain = new float[DROPS * 4];
    private final float[] mFlakeX = new float[FLAKES], mFlakeR = new float[FLAKES], mFlakeA = new float[FLAKES],
            mFlakeP = new float[FLAKES], mFlakePh = new float[FLAKES], mSwayP = new float[FLAKES], mSwayPh = new float[FLAKES];
    private final float[] mFlyX = new float[FLIES], mFlyY = new float[FLIES], mFlyP = new float[FLIES],
            mFlyPh = new float[FLIES], mPulseP = new float[FLIES], mPulsePh = new float[FLIES];
    private final float[] mFogP = new float[3], mFogPh = new float[3];
    private static final float[] BIRD_DX = {0, 38, -30, 72, -62}, BIRD_DY = {0, 18, 22, 34, 40};

    public SkyView(Context c) {
        this(c, null);
    }

    public SkyView(Context c, AttributeSet a) {
        super(c, a);
        setWillNotDraw(false);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeCap(Paint.Cap.ROUND);
        mStroke.setStrokeJoin(Paint.Join.ROUND);
    }

    // ------------------------------------------------------------------ public API

    /** Place for the sun and moon (degrees). Default Moscow until set. */
    public void setLocation(double latDeg, double lonDeg) {
        if (Double.isNaN(latDeg) || Double.isNaN(lonDeg)) return;
        latDeg = Math.max(-89.9, Math.min(89.9, latDeg));
        if (Math.abs(latDeg - mLat) < 0.01 && Math.abs(lonDeg - mLon) < 0.01) return;
        mLat = latDeg;
        mLon = lonDeg;
        changed();
    }

    /** One of WEATHER_*. */
    public void setWeather(int weather) {
        if (weather < WEATHER_CLEAR || weather > WEATHER_SNOW) weather = WEATHER_CLEAR;
        if (weather == mWeather) return;
        mWeather = weather;
        changed();
    }

    /** "auto" (a scene per calendar day) or one of {@link #sceneIds()}. */
    public void setScene(String sceneIdOrAuto) {
        String s = sceneIdOrAuto != null && SkyLook.sceneIndex(sceneIdOrAuto) >= 0 ? sceneIdOrAuto : AUTO;
        if (s.equals(mScene)) return;
        mScene = s;
        changed();
    }

    /** Freezes all animation (video playing over Home, etc.). */
    public void setPaused(boolean paused) {
        if (paused == mPaused) return;
        mPaused = paused;
        updateActive();
    }

    /**
     * Stands still under another layer (Home's other tabs under their veil): no motion and no minute
     * render while a frame is shown. Unlike {@link #setPaused} it still makes a frame when there is
     * none (freed while the window was hidden) and fades it in, then stops: the layer above never
     * shows the bare ground colour where the sky belongs.
     */
    public void setStill(boolean still) {
        if (still == mStill) return;
        mStill = still;
        updateActive();
    }

    /**
     * Darkening for UI legibility, 0..1 each, in the ground colour: left = the D_Home side gradient
     * (0.94 at the edge, gone at 76 % of the width) times the strength; bottom = solid for its lower
     * 44 %, then fading, over 620 design px times the strength (1: D_Home rows, 0.7: D_Saver clock).
     */
    public void setScrim(float leftStrength, float bottomStrength) {
        float l = SkyLook.clamp01(leftStrength), b = SkyLook.clamp01(bottomStrength);
        if (l == mScrimLeft && b == mScrimBottom) return;
        mScrimLeft = l;
        mScrimBottom = b;
        mScrimL.setShader(l <= 0 ? null : new LinearGradient(0, 0, 1460, 0,
                new int[]{SkyLook.alpha(BG, 0.94f * l), SkyLook.alpha(BG, 0.8f * l), SkyLook.alpha(BG, 0.22f * l),
                        SkyLook.alpha(BG, 0)},
                new float[]{0, 0.27f / 0.76f, 0.57f / 0.76f, 1}, Shader.TileMode.CLAMP));
        mScrimTop = 1080 - 620 * b;
        mScrimB.setShader(b <= 0 ? null : new LinearGradient(0, 1080, 0, mScrimTop,
                new int[]{SkyLook.alpha(BG, b), SkyLook.alpha(BG, b), SkyLook.alpha(BG, 0)},
                new float[]{0, 0.44f, 1}, Shader.TileMode.CLAMP));
        invalidate();
    }

    /**
     * Home's header scrim, in the ground colour from the top edge: {@code alphas} at {@code pos}
     * (fractions of {@code endDesign}, design px), over the side and bottom scrims. The header (clock,
     * icons, tabs) stays readable over a white cloud or a bright day sky without a plate. Null: none (the
     * screensaver).
     */
    public void setHeaderScrim(float[] alphas, float[] pos, float endDesign) {
        if (alphas == null || pos == null || endDesign <= 0) {
            mHeadEnd = 0;
            mScrimH.setShader(null);
        } else {
            int[] cols = new int[alphas.length];
            for (int i = 0; i < cols.length; i++) cols[i] = SkyLook.alpha(BG, alphas[i]);
            mHeadEnd = endDesign;
            mScrimH.setShader(new LinearGradient(0, 0, 0, endDesign, cols, pos, Shader.TileMode.CLAMP));
        }
        invalidate();
    }

    /** Current sun elevation at the set place (degrees), for callers that adapt their UI. */
    public float sunElevationDeg() {
        SolarCalc.sun(System.currentTimeMillis(), mLat, mLon, mTmp);
        return (float) mTmp[0];
    }

    /** The scene shown now ("auto" resolved for today). */
    public String sceneNow() {
        return SkyLook.IDS[resolveScene(System.currentTimeMillis())];
    }

    public static String[] sceneIds() {
        return SkyLook.IDS.clone();
    }

    /** Localized name of a scene id or "auto". */
    public static String sceneTitle(Context c, String id) {
        if (id == null) id = AUTO;
        switch (id) {
            case "mountains": return c.getString(R.string.sky_scene_mountains);
            case "sea": return c.getString(R.string.sky_scene_sea);
            case "hills": return c.getString(R.string.sky_scene_hills);
            case "city": return c.getString(R.string.sky_scene_city);
            case "dunes": return c.getString(R.string.sky_scene_dunes);
            case "forest": return c.getString(R.string.sky_scene_forest);
            default: return c.getString(R.string.sky_scene_auto);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        mWinVisible = getWindowVisibility() == VISIBLE;
        updateActive();
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        updateActive();
        release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWinVisible = visibility == VISIBLE;
        mMain.removeCallbacks(mReleaseHidden);
        if (!mWinVisible) mMain.postDelayed(mReleaseHidden, RELEASE_HIDDEN_MS);
        updateActive();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateActive();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // "slice": cover the view with the 1920x1080 design frame, centred
        mS = Math.max(w / 1920f, h / 1080f);
        mOx = (w - 1920 * mS) / 2;
        mOy = (h - 1080 * mS) / 2;
        // the renderer keeps the soft layers at 1080p's size and draws the crisp ones at this scale
        float rs = mS;
        if (Math.abs(rs - mRenderScale) > 0.01f || mFront == null) {
            mRenderScale = rs;
            changed();
        }
        updateActive();
    }

    private final Runnable mReleaseHidden = this::release;

    private void updateActive() {
        if (mMain == null) return; // called back from View's constructor, fields not set yet
        // still: runs only for a first frame and its fade-in (freeBack re-checks when that is over)
        boolean still = mStill && mFront != null && mFadeStart == 0;
        boolean act = mAttached && mWinVisible && isShown() && !mPaused && !still && getWidth() > 0 && getHeight() > 0;
        if (act == mActive) return;
        mActive = act;
        if (act) {
            mMain.removeCallbacks(mReleaseHidden);
            if (mFront == null || mDirty || minuteChanged()) requestRender();
            schedule();
        } else {
            stopCallbacks();
        }
    }

    private void changed() {
        mDirty = true;
        if (mActive) requestRender();
    }

    /** Frees the bitmaps; the next activation renders again (fading in). */
    private void release() {
        mMain.removeCallbacks(mReleaseHidden);
        mMain.removeCallbacks(mFreeBack);
        mMain.removeCallbacks(mRetry);
        mRetryAt = 0;
        mGeneration++;   // an in-flight render is dropped (and its frame freed) when it returns
        if (mFront != null) mFront.recycle();
        if (mBack != null && !mInFlight) mBack.recycle();
        if (mFadeFrom != null && mFadeFrom != mBack) mFadeFrom.recycle();
        mFront = null;
        if (!mInFlight) mBack = null;
        mFadeFrom = null;
        mBackFree = true;
        mBuiltScene = -1;
        mDirty = true;
    }

    // ------------------------------------------------------------------ rendering

    private int resolveScene(long utc) {
        int i = SkyLook.sceneIndex(mScene);
        if (i >= 0) return i;
        long local = utc + TimeZone.getDefault().getOffset(utc);
        return SkyLook.autoScene(Math.floorDiv(local, 86_400_000L));
    }

    private boolean minuteChanged() {
        return mFront != null && Math.floorDiv(System.currentTimeMillis(), 60_000L) != mFront.minute;
    }

    private void requestRender() {
        if (!mActive) {
            mDirty = true;
            return;
        }
        if (mInFlight || !mBackFree) {
            mWantRender = true;
            return;
        }
        if (SystemClock.uptimeMillis() < mRetryAt) {
            mDirty = true;
            return;
        }
        mWantRender = false;
        mDirty = false;
        mInFlight = true;
        if (mBack == null) mBack = new SkyRenderer.Frame();
        final SkyRenderer.Frame back = mBack;
        final SkyRenderer.Params p = new SkyRenderer.Params();
        p.utc = System.currentTimeMillis();
        p.tzOffset = TimeZone.getDefault().getOffset(p.utc);
        p.lat = mLat;
        p.lon = mLon;
        p.scene = resolveScene(p.utc);
        p.weather = mWeather;
        p.scale = mRenderScale;
        final int gen = mGeneration;
        worker().post(() -> {
            boolean ok = false;
            try {
                mRenderer.render(back, p);
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "sky render failed: " + t);
            }
            final boolean done = ok;
            mMain.post(() -> onRendered(back, done, gen));
        });
    }

    private void onRendered(SkyRenderer.Frame f, boolean ok, int gen) {
        mInFlight = false;
        if (gen != mGeneration) {
            f.recycle();
            if (mBack == f) mBack = null;
            if (mActive && (mWantRender || mDirty || mFront == null)) requestRender();
            return;
        }
        if (ok) {
            SkyRenderer.Frame old = mFront;
            mFront = f;
            mBack = old;
            f.prepareToDraw();
            SkyLook k = f.look;
            boolean structural = k.scene != mBuiltScene || k.weather != mBuiltWeather;
            if (structural) buildParticles(k);
            if (old == null || structural) {
                mFadeFrom = old;   // null: fade in from the ground colour
                mFadeStart = SystemClock.uptimeMillis();
            }
            mBackFree = false;
            mMain.removeCallbacks(mFreeBack);
            mMain.postDelayed(mFreeBack, mFadeStart > 0 ? FADE_MS + BACK_GRACE_MS : BACK_GRACE_MS);
            invalidate();
            schedule();
        } else {
            mDirty = true;
            mRetryAt = SystemClock.uptimeMillis() + RETRY_MS;
            mMain.removeCallbacks(mRetry);
            mMain.postDelayed(mRetry, RETRY_MS);
            return;
        }
        if (mWantRender) requestRender();
    }

    private final Runnable mRetry = () -> {
        if (mActive) requestRender();
    };

    private final Runnable mFreeBack = this::freeBack;

    /**
     * The swapped-out frame is off screen (and the crossfade over): free its bitmaps (the next render
     * allocates new ones, so only one set is kept between minutes) and let the next render use it.
     */
    private void freeBack() {
        if (mFadeStart > 0 && SystemClock.uptimeMillis() - mFadeStart < FADE_MS) {
            mMain.postDelayed(mFreeBack, BACK_GRACE_MS);
            return;
        }
        mFadeStart = 0;
        mFadeFrom = null;
        if (mBack != null) mBack.recycle();
        mBackFree = true;
        // paused mid-fade, no frame clock runs: draw the finished frame once, so the screen does not
        // keep a half crossfade and the last display list lets go of the freed set
        invalidate();
        updateActive(); // still: the first frame has faded in, stand still now
        if (mWantRender || mDirty) requestRender();
    }

    private void buildParticles(SkyLook k) {
        mBuiltScene = k.scene;
        mBuiltWeather = k.weather;
        Rnd r = new Rnd(11 + k.scene * 7);
        for (int i = 0; i < STARS; i++) {
            mStarX[i] = r.next() * 1920;
            mStarY[i] = r.next() * (k.horizon - 120);
            mStarR[i] = r.next() < 0.15f ? 1.5f : 1f;
            mStarP[i] = 2 + r.next() * 4;
            mStarPh[i] = r.next() * mStarP[i];
        }
        for (int i = 0; i < SkyRenderer.MAX_CLOUDS; i++) {
            mCloudY[i] = 30 + r.next() * (k.horizon - 330);
            mCloudP[i] = 160 + r.next() * 120;
            mCloudPh[i] = r.next() * mCloudP[i];
        }
        for (int i = 0; i < GLINTS; i++) {
            mGlintW[i] = 26 + i * 12 + r.next() * 26;
            mGlintDx[i] = -mGlintW[i] / 2 + (r.next() - 0.5f) * 36;
            mGlintP[i] = 1.6f + r.next() * 2.2f;
            mGlintPh[i] = r.next() * mGlintP[i];
        }
        for (int i = 0; i < DROPS; i++) {
            mDropX[i] = r.next() * 2160;
            mDropP[i] = 0.6f + r.next() * 0.5f;
            mDropPh[i] = r.next() * mDropP[i];
        }
        for (int i = 0; i < FLAKES; i++) {
            mFlakeX[i] = r.next() * 1920;
            mFlakeR[i] = (3 + r.next() * 6) / 2;
            mFlakeA[i] = 0.55f + r.next() * 0.45f;
            mFlakeP[i] = 9 + r.next() * 9;
            mFlakePh[i] = r.next() * mFlakeP[i];
            mSwayP[i] = 3 + r.next() * 3;
            mSwayPh[i] = r.next() * mSwayP[i];
        }
        for (int i = 0; i < FLIES; i++) {
            mFlyX[i] = r.next() * 1920;
            mFlyY[i] = 820 + r.next() * 220;
            mFlyP[i] = 6 + r.next() * 6;
            mFlyPh[i] = r.next() * mFlyP[i];
            mPulseP[i] = 2 + r.next() * 3;
            mPulsePh[i] = r.next() * mPulseP[i];
        }
        for (int i = 0; i < 3; i++) {
            mFogP[i] = 30 + r.next() * 20;
            mFogPh[i] = r.next() * mFogP[i];
        }
        if (k.flies && mGlow == null) mGlow = glowSprite();
    }

    /** Firefly halo (prototype box-shadow 0 0 14px 4px rgba(255,214,120,.6)), 48 design px. */
    private static Bitmap glowSprite() {
        Bitmap b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(24, 24, 24, new int[]{0x99FFD678, 0x99FFD678, 0x00FFD678},
                new float[]{0, 0.2f, 1}, Shader.TileMode.CLAMP));
        c.drawCircle(24, 24, 24, p);
        return b;
    }

    // ------------------------------------------------------------------ frame clock

    private final Choreographer.FrameCallback mFrameCb = frameTimeNanos -> {
        mFramePosted = false;
        if (!mActive) return;
        if (minuteChanged() && !mInFlight) requestRender();
        invalidate();
        schedule();
    };

    private final Runnable mTick = () -> {
        mTickPosted = false;
        if (!mActive) return;
        if (minuteChanged() && !mInFlight) requestRender();
        schedule();
    };

    private void schedule() {
        if (!mActive || mFront == null) return;
        int fps = targetFps(animTime());
        if (fps > 0) {
            if (mTickPosted) {
                mMain.removeCallbacks(mTick);
                mTickPosted = false;
            }
            if (!mFramePosted) {
                mFramePosted = true;
                Choreographer.getInstance().postFrameCallbackDelayed(mFrameCb, Math.max(0, 1000 / fps - 10));
            }
        } else if (!mTickPosted && !mFramePosted) {
            mTickPosted = true;
            mMain.postDelayed(mTick, 1000);
        }
    }

    private void stopCallbacks() {
        if (mFramePosted) Choreographer.getInstance().removeFrameCallback(mFrameCb);
        mFramePosted = false;
        mMain.removeCallbacks(mTick);
        mTickPosted = false;
    }

    /** Frames per second the visible motion needs; 0 = nothing moves (1 s clock tick only). */
    private int targetFps(double t) {
        if (mFadeStart > 0) return 30;
        if (!ValueAnimator.areAnimatorsEnabled()) return 0;
        SkyLook k = mFront.look;
        if (k.precip != 0) return 30;
        if (k.flies || (k.birds && birdsFlying(t))) return 20;
        if (k.glintN > 0) return 15;
        if (k.cloudN > 0 || k.fogN > 0 || k.starA > 0.01f) return 12;
        if (k.scene == SkyLook.CITY && k.windows > 0.3f) return 8;
        return 0;
    }

    private double animTime() {
        return ValueAnimator.areAnimatorsEnabled() ? (SystemClock.uptimeMillis() - mT0) / 1000.0 : 0;
    }

    private static boolean birdsFlying(double t) {
        return (t + 40) % BIRD_PERIOD < BIRD_CROSS;
    }

    // ------------------------------------------------------------------ drawing (no allocations)

    @Override
    protected void onDraw(Canvas c) {
        SkyRenderer.Frame f = mFront;
        if (f == null || f.sky == null || f.sky.isRecycled()) {
            c.drawColor(BG);
            return;
        }
        double t = animTime();
        float a = 1f;
        if (mFadeStart > 0) a = Math.min(1f, (SystemClock.uptimeMillis() - mFadeStart) / (float) FADE_MS);
        c.save();
        c.translate(mOx, mOy);
        c.scale(mS, mS);
        if (a < 1f) {
            SkyRenderer.Frame from = mFadeFrom;
            if (from != null && from.sky != null && !from.sky.isRecycled()) {
                drawLayers(c, from, 1f);
            } else {
                c.drawColor(BG);
            }
        }
        drawFrame(c, f, t, a);
        if (mScrimLeft > 0) c.drawRect(0, 0, 1460, 1080, mScrimL);
        if (mScrimBottom > 0) c.drawRect(0, mScrimTop, 1920, 1080, mScrimB);
        if (mHeadEnd > 0) c.drawRect(0, 0, 1920, mHeadEnd, mScrimH);
        c.restore();
    }

    private void drawLayers(Canvas c, SkyRenderer.Frame f, float a) {
        mBmp.setAlpha(Math.round(255 * a));
        mDst.set(0, 0, 1920, 1080);
        c.drawBitmap(f.sky, null, mDst, mBmp);
        if (f.land != null && !f.land.isRecycled()) {
            mDst.set(0, f.landTop, 1920, 1080);
            c.drawBitmap(f.land, null, mDst, mBmp);
        }
    }

    private void drawFrame(Canvas c, SkyRenderer.Frame f, double t, float a) {
        SkyLook k = f.look;
        mBmp.setAlpha(Math.round(255 * a));
        mDst.set(0, 0, 1920, 1080);
        c.drawBitmap(f.sky, null, mDst, mBmp);

        // stars twinkle (prototype lw-twinkle: 0.2 .. 1 over 2..6 s)
        if (k.starA > 0.01f) {
            mDot.setColor(0xFFFFFFFF);
            for (int i = 0; i < STARS; i++) {
                float o = k.starA * (0.2f + 0.8f * wave(t, mStarP[i], mStarPh[i])) * a;
                mDot.setAlpha(Math.round(255 * o));
                c.drawCircle(mStarX[i], mStarY[i], mStarR[i], mDot);
            }
        }
        if (k.sunShow) {
            mDot.setColor(k.sunColor);
            mDot.setAlpha(Math.round(255 * a));
            c.drawCircle(k.sunX, k.sunY, 46, mDot);
        }
        if (k.moonShow && f.moon != null) {
            mBmp.setAlpha(Math.round(255 * k.moonA * a));
            // on whole screen pixels and at the sprite's own pixels (MOON_BOX at the real scale): 1:1, sharp
            float box = f.moon.getWidth() / mS, h = box / 2;
            float x0 = (Math.round(mOx + mS * (k.moonX - h)) - mOx) / mS;
            float y0 = (Math.round(mOy + mS * (k.moonY - h)) - mOy) / mS;
            mDst.set(x0, y0, x0 + box, y0 + box);
            c.drawBitmap(f.moon, null, mDst, mBmp);
        }
        // clouds drift across in 160..280 s (prototype lw-drift)
        for (int i = 0; i < k.cloudN; i++) {
            Bitmap b = f.clouds[i];
            if (b == null) continue;
            float x = -820 + 3020 * cyc(t, mCloudP[i], mCloudPh[i]);
            float y = mCloudY[i];
            mBmp.setAlpha(Math.round(255 * k.cloudA * a));
            mDst.set(x, y, x + SkyRenderer.CLOUD_W, y + SkyRenderer.CLOUD_H);
            c.drawBitmap(b, null, mDst, mBmp);
        }
        // a distant flock glides by now and then, wings beating slowly (not frozen mid-air with
        // system animations off: t is then 0)
        if (k.birds && t > 0 && birdsFlying(t)) {
            float q = (float) (((t + 40) % BIRD_PERIOD) / BIRD_CROSS);
            float bx = -300 + 2600 * q, by = -120 * q;
            mStroke.setColor(k.birdColor);
            mStroke.setAlpha(Math.round(255 * a));
            mStroke.setStrokeWidth(2);
            for (int i = 0; i < BIRDS; i++) {
                float x = 80 + BIRD_DX[i] + bx, y = k.horizon - 330 + BIRD_DY[i] + by;
                float s = 0.675f + 0.325f * (float) Math.cos(2 * Math.PI * cyc(t, 1.1f + (i % 3) * 0.15f, i * 0.3f));
                mBird.rewind();
                mBird.moveTo(x + 1, y + 7 + 2 * s);
                mBird.quadTo(x + 8, y + 7 - 6 * s, x + 15, y + 7 + s);
                mBird.quadTo(x + 22, y + 7 - 6 * s, x + 29, y + 7 + 2 * s);
                c.drawPath(mBird, mStroke);
            }
        }

        if (f.land != null && !f.land.isRecycled()) {
            mBmp.setAlpha(Math.round(255 * a));
            mDst.set(0, f.landTop, 1920, 1080);
            c.drawBitmap(f.land, null, mDst, mBmp);
        }

        // aviation lights on the city masts
        if (k.scene == SkyLook.CITY && k.windows > 0.3f) {
            float[] l = Scenes.city().lights;
            for (int i = 0; i < l.length; i += 3) {
                float on = cyc(t, 1.6f, l[i + 2]) < 0.2f ? 1f : 0.15f;
                mDot.setColor(0xFFFF4A3D);
                mDot.setAlpha(Math.round(255 * on * k.windows * a));
                c.drawCircle(l[i], l[i + 1], 2.5f, mDot);
                mDot.setAlpha(Math.round(64 * on * k.windows * a));
                c.drawCircle(l[i], l[i + 1], 7, mDot);
            }
        }
        // glints on the water under the sun or the moon (prototype lw-glint 0.08 .. 0.85)
        if (k.glintN > 0) {
            mDot.setColor(k.glintColor);
            for (int i = 0; i < k.glintN; i++) {
                float y = k.glintTop + i * i * 1.4f + i * 9;
                if (y > k.glintMaxY) break;
                float o = (0.08f + 0.77f * wave(t, mGlintP[i], mGlintPh[i])) * k.glintA * a;
                mDot.setAlpha(Math.round(255 * o));
                float x = k.glintX + mGlintDx[i];
                mDst.set(x, y, x + mGlintW[i], y + 3);
                c.drawRoundRect(mDst, 1.5f, 1.5f, mDot);
            }
        }
        // fog bands sway (prototype lw-fog: +-60 px over 30..50 s)
        if (k.fogN > 0 && f.fog != null) {
            for (int i = 0; i < k.fogN; i++) {
                float dx = -60 + 120 * wave(t, mFogP[i], mFogPh[i]);
                float x = -200 - SkyRenderer.FOG_PAD + dx, y = k.fogY[i] - SkyRenderer.FOG_PAD;
                mBmp.setAlpha(Math.round(255 * k.fogA[i] * a));
                mDst.set(x, y, x + SkyRenderer.FOG_W, y + SkyRenderer.FOG_H);
                c.drawBitmap(f.fog, null, mDst, mBmp);
            }
        }
        // fireflies on summer nights over the hills
        if (k.flies && mGlow != null) {
            float fa = SkyLook.clamp01((k.dark - 0.4f) / 0.3f) * a;
            for (int i = 0; i < FLIES; i++) {
                float p = cyc(t, mFlyP[i], mFlyPh[i]) * 3;
                int seg = Math.min(2, (int) p);
                float e = smooth(p - seg);
                float x = mFlyX[i] + lerp(FLY_X[seg], FLY_X[seg + 1], e);
                float y = mFlyY[i] + lerp(FLY_Y[seg], FLY_Y[seg + 1], e);
                float o = (0.15f + 0.85f * wave(t, mPulseP[i], mPulsePh[i])) * fa;
                mBmp.setAlpha(Math.round(255 * o));
                mDst.set(x - 21, y - 21, x + 27, y + 27);
                c.drawBitmap(mGlow, null, mDst, mBmp);
                mDot.setColor(0xFFFFE9A8);
                mDot.setAlpha(Math.round(255 * o));
                c.drawCircle(x + 3, y + 3, 3, mDot);
            }
        }
        // rain streaks and snowflakes
        if (k.precip == SkyLook.RAIN) {
            for (int i = 0; i < DROPS; i++) {
                float q = cyc(t, mDropP[i], mDropPh[i]);
                float x = mDropX[i] - 240 * q, y = -90 + 1260 * q;
                int j = i * 4;
                mRain[j] = x + 3.5f;
                mRain[j + 1] = y - 17;
                mRain[j + 2] = x - 3.5f;
                mRain[j + 3] = y + 17;
            }
            mStroke.setColor(0xFFD2E1F0);
            mStroke.setAlpha(Math.round(128 * a));
            mStroke.setStrokeWidth(2);
            c.drawLines(mRain, 0, DROPS * 4, mStroke);
        } else if (k.precip == SkyLook.SNOW) {
            mDot.setColor(0xFFFFFFFF);
            for (int i = 0; i < FLAKES; i++) {
                float y = -60 + 1200 * cyc(t, mFlakeP[i], mFlakePh[i]);
                float x = mFlakeX[i] - 14 + 28 * wave(t, mSwayP[i], mSwayPh[i]);
                mDot.setAlpha(Math.round(255 * mFlakeA[i] * a));
                c.drawCircle(x, y, mFlakeR[i], mDot);
            }
        }
    }

    private static final float[] FLY_X = {0, 18, -14, 0}, FLY_Y = {0, -22, -8, 0};

    /** 0..1 position within a cycle. */
    private static float cyc(double t, float period, float phase) {
        return (float) (((t + phase) % period) / period);
    }

    /** Ease-in-out 0 -> 1 -> 0 over a cycle (the prototypes' 0% / 50% / 100% keyframes). */
    private static float wave(double t, float period, float phase) {
        return 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * cyc(t, period, phase));
    }

    private static float smooth(float x) {
        return x * x * (3 - 2 * x);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
