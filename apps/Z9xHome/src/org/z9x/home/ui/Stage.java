package org.z9x.home.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import org.z9x.home.App;
import org.z9x.home.data.Card;
import org.z9x.home.img.ArtUrl;
import org.z9x.home.img.HeroArt;
import org.z9x.home.img.ImageLoader;

import java.util.Objects;

/**
 * Home's full-screen backdrop (Lumen OS 1.0.5, the owner's mockup design/home_v2), between the living
 * sky and the pages: the art of the focused card fills the whole screen, under the scrims. It replaces
 * 1.0.1's stage (art full-bleed only when it was large, else a blurred copy and the art as a small
 * framed card). Two stacked layers: new art fades in over the old one in 1.2 s (to or from no art, the
 * sky, in 0.7 s); a crossfade that starts while another runs completes that one at once. The art is the
 * program's poster art or its other art (thumbnail), whichever {@link HeroArt#backdrop} prefers by real
 * pixels: landscape art at least 768 px wide is shown sharp, cropped to the screen's shape from the top
 * and decoded once at no more than the screen's pixels (ImageLoader.loadHero, a HARDWARE bitmap); other
 * art (posters, small thumbnails) as its blurred copy. Kinopoisk art is asked at 1920x1080
 * ({@link ArtUrl#backdrop}). Slow motion ("Ken Burns", {@link Gfx#BACKDROP_FPS}): the visible layer
 * scales 1.02 -> 1.12 and pans 2.5 % / 1.5 % over 22 s and back, eased, through the views' scale and
 * translation only (the RenderNode properties: nothing is redrawn or decoded per frame), at most 30
 * frames per second by default; still on weak GPUs (lite) and while it is hidden, paused or a trailer
 * plays. The optional silent trailer plays full-bleed over the art. Scrims, back to front: start side
 * ({@link Scrims#SIDE_ALPHA}), bottom ({@link Scrims#BOTTOM_ALPHA}) and the header's ({@link Scrims},
 * the same as the sky's, at {@link Scrims#headOverArt} while fading to or from no art), dithered.
 * A program without art (or whose art cannot be had in 4 s) gets a soft glow of its colour ({@link Tint});
 * with no program focused yet the stage is empty and the sky shows through. {@link #covering()} tells the activity when
 * the sky is fully hidden (it then pauses the sky). Weak GPUs ({@link Gfx#LITE}): the stage keeps a GPU
 * layer while trailers are off.
 */
public class Stage extends ViewGroup {
    public interface Listener {
        /** The stage became fully opaque (true) or not (false). */
        void onStageCovering(boolean covering);
    }

    private static final long FADE_MS = 1200, EMPTY_FADE_MS = 700;
    private static final long LOAD_TIMEOUT_MS = 4000;
    /** One way of the motion; it then runs back (CSS "alternate"). */
    private static final long MOTION_MS = 22000;
    private static final float SCALE_FROM = 1.02f, SCALE_TO = 1.12f, PAN_X = 0.025f, PAN_Y = 0.015f;
    private static final float BLUR_ALPHA = 0.8f; // the blurred copy over the ground: darkened by a fifth

    private final ImageLoader mImages;
    private final Art[] mArt = new Art[2];
    private int mFront;
    private final Trailer mTrailer;
    private final ScrimView mScrims;
    private ValueAnimator mScrimAnim;
    private Listener mListener;
    private Card mCard;
    private String mShownUri;
    private ImageLoader.Request mReq, mProbe0, mProbe1;
    private boolean mCovering;
    private String mWaitId;
    // motion: a clock that only runs while the motion runs, so pausing and resuming never jumps
    private final long mFrameMs;
    private boolean mMotionAllowed, mTrailerPlaying, mMotionRunning;
    private long mClock, mLastStep;
    private final Runnable mStep = this::step;

    /** One layer: a bitmap drawn over the whole view (the views' scale and translation move it). */
    private static final class Art extends View {
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF mDst = new RectF();
        Bitmap bmp;
        boolean blurred;
        boolean leaving; // fading out to the sky
        boolean moving;  // takes part in the slow motion (not the tint of a program without art)
        long t0;

        Art(Context c) {
            super(c);
            setVisibility(GONE);
        }

        void set(Bitmap b, boolean blur) {
            bmp = b;
            blurred = blur;
            leaving = false;
            invalidate();
        }

        void clear() {
            animate().cancel();
            bmp = null;
            leaving = false;
            setVisibility(GONE);
            setAlpha(1f);
            still();
        }

        void still() {
            setScaleX(1f);
            setScaleY(1f);
            setTranslationX(0f);
            setTranslationY(0f);
        }

        boolean shown() {
            return bmp != null && getVisibility() == VISIBLE;
        }

        @Override
        public boolean hasOverlappingRendering() {
            // one bitmap: its alpha needs no offscreen buffer while it fades; the ground under it does
            return bmp != null && (blurred || bmp.hasAlpha());
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap b = bmp;
            if (b == null) return;
            if (blurred || b.hasAlpha()) c.drawColor(Theme.BG);
            mPaint.setAlpha(blurred ? Math.round(255 * BLUR_ALPHA) : 255);
            mDst.set(0, 0, getWidth(), getHeight());
            c.drawBitmap(b, null, mDst, mPaint);
        }
    }

    public Stage(Context c, ImageLoader il) {
        super(c);
        mImages = il;
        setClipChildren(true);
        int fps = Gfx.BACKDROP_FPS;
        // a step a little before each 1/fps: the next vsync after it keeps the rate (30 fps on a 60 Hz display)
        mFrameMs = fps <= 0 ? 0 : Math.max(1, 1000 / fps - 8);
        // weak GPUs (Gfx): art, trailer and scrims as one texture (dropped while trailers are allowed)
        if (Gfx.LITE) setLayerType(LAYER_TYPE_HARDWARE, null);
        for (int i = 0; i < 2; i++) {
            mArt[i] = new Art(c);
            addView(mArt[i]);
        }
        mTrailer = new Trailer(c);
        mTrailer.setOnPlaying(p -> {
            mTrailerPlaying = p;
            updateMotion();
        });
        addView(mTrailer.view());
        mScrims = new ScrimView(c);
        addView(mScrims);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public Card card() {
        return mCard;
    }

    /** Shows the art of {@code k} (null or art-less = empty stage: the sky shows). */
    public void show(Card k, boolean animate) {
        String[] uris = candidates(k);
        if (k != null && mCard != null && k.id.equals(mCard.id) && Objects.equals(k.image, mCard.image)
                && Objects.equals(k.image2, mCard.image2) && mArt[mFront].shown()) {
            mCard = k;
            return;
        }
        mCard = k;
        cancelRequests();
        mTrailer.stop();
        removeCallbacks(mLoadTimeout);
        if (uris.length == 0) {
            fallback(k, animate);
            return;
        }
        // the real size of each candidate decides sharp or blurred; known sizes decide at once
        final int[] sizes = new int[uris.length * 2];
        boolean known = true;
        for (int i = 0; i < uris.length; i++) {
            int[] s = mImages.peekSize(uris[i]);
            if (s == null) known = false;
            else {
                sizes[2 * i] = s[0];
                sizes[2 * i + 1] = s[1];
            }
        }
        if (known) {
            apply(k, uris, sizes, animate);
            return;
        }
        // the previous art stays until this one arrives (no flash to the sky between two programs)
        expectSoon(k.id);
        final String id = k.id;
        final int[] pending = {0};
        for (int i = 0; i < uris.length; i++) {
            if (mImages.peekSize(uris[i]) != null) continue;
            pending[0]++;
            final int idx = i;
            ImageLoader.Request r = mImages.probe(uris[i], k.pkg, (w, h) -> {
                if (!isCard(id)) return;
                sizes[2 * idx] = w;
                sizes[2 * idx + 1] = h;
                if (--pending[0] == 0) apply(mCard, uris, sizes, true);
            });
            if (i == 0) mProbe0 = r;
            else mProbe1 = r;
        }
    }

    /** The program's art and its other art (thumbnail next to poster art), as the backdrop asks them, each once. */
    private static String[] candidates(Card k) {
        if (k == null) return new String[0];
        String a = k.image != null && !k.image.isEmpty() ? ArtUrl.backdrop(k.image) : null;
        String b = k.image2 != null && !k.image2.isEmpty() ? ArtUrl.backdrop(k.image2) : null;
        if (a != null && b != null && !b.equals(a)) return new String[]{a, b};
        if (a != null) return new String[]{a};
        if (b != null) return new String[]{b};
        return new String[0];
    }

    private void apply(Card k, String[] uris, int[] sizes, boolean animate) {
        int sw = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int sh = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        HeroArt.Plan p = HeroArt.backdrop(sizes, sw, sh);
        if (p.mode == HeroArt.NONE) {
            Log.i(App.TAG, "backdrop none id=" + k.id);
            fallback(k, animate); // nothing could be fetched: the tint, or the sky
            return;
        }
        final String uri = uris[p.index];
        final String id = k.id;
        final boolean blur = p.mode == HeroArt.BLUR;
        StringBuilder sz = new StringBuilder();
        for (int i = 0; i + 1 < sizes.length; i += 2) sz.append(i == 0 ? "" : ",").append(sizes[i]).append('x').append(sizes[i + 1]);
        Log.i(App.TAG, "backdrop id=" + id + " src=" + sz + " ui=" + sw + "x" + sh + " -> " + p);
        if (uri.equals(mShownUri) && mArt[mFront].shown()) {
            // the same picture (another card of the same title): it stays, moving on
            removeCallbacks(mLoadTimeout);
            mTrailer.arm(k);
            return;
        }
        Bitmap b = blur ? mImages.peekBlur(uri) : mImages.peekHero(uri, sw, sh);
        if (b != null) {
            crossfade(b, blur, uri, animate);
            mTrailer.arm(k);
            return;
        }
        expectSoon(id);
        ImageLoader.Callback cb = (bmp, col) -> {
            if (!isCard(id) || bmp == null) return;
            crossfade(bmp, blur, uri, true);
            mTrailer.arm(mCard);
        };
        mReq = blur ? mImages.loadBlur(uri, k.pkg, cb) : mImages.loadHero(uri, sw, sh, k.pkg, cb);
    }

    private void cancelRequests() {
        if (mReq != null) mReq.cancel();
        if (mProbe0 != null) mProbe0.cancel();
        if (mProbe1 != null) mProbe1.cancel();
        mReq = mProbe0 = mProbe1 = null;
    }

    /** If the art of {@code id} has not arrived in 4 s (offline, failed), fade to the sky. */
    private void expectSoon(String id) {
        removeCallbacks(mLoadTimeout);
        mWaitId = id;
        postDelayed(mLoadTimeout, LOAD_TIMEOUT_MS);
    }

    // crossfade() cancels it: if it runs, nothing new was shown for this card
    private final Runnable mLoadTimeout = () -> {
        if (isCard(mWaitId) && mArt[mFront].shown()) fallback(mCard, true);
    };

    private static final String TINT = "tint:";

    /**
     * No art for {@code k}: a program gets the soft glow of its colour ({@link Tint}, never a flat block),
     * anything else the sky.
     */
    private void fallback(Card k, boolean animate) {
        if (k == null || k.kind != Card.PROGRAM) {
            crossfade(null, false, null, animate);
            return;
        }
        int color = k.color != 0 ? k.color : Theme.SURFACE;
        String key = TINT + Integer.toHexString(color);
        if (key.equals(mShownUri) && mArt[mFront].shown()) return;
        crossfade(Tint.backdrop(color, Theme.BG), false, key, animate);
    }

    private boolean isCard(String id) {
        return mCard != null && id != null && mCard.id.equals(id);
    }

    /** New art over the old (b != null), or the old faded out to the sky (b == null). */
    private void crossfade(Bitmap b, boolean blurred, String uri, boolean animate) {
        removeCallbacks(mLoadTimeout);
        boolean anim = animate && Theme.animations();
        final Art from = mArt[mFront];
        final Art back = mArt[1 - mFront];
        // a crossfade still running: it completes now (the art fading in is shown fully); art fading out
        // to the sky stays where it is and the new art fades in over it
        from.animate().cancel();
        if (from.shown() && !from.leaving) from.setAlpha(1f);
        boolean fromArt = from.shown();
        if (b == null) {
            mShownUri = null;
            back.clear();
            setCovering(false);
            fadeScrims(0f, anim);
            if (!fromArt) {
                from.clear();
            } else if (anim) {
                from.leaving = true;
                from.animate().alpha(0f).setDuration(EMPTY_FADE_MS).setInterpolator(Theme.EMPHASIZED).withEndAction(() -> {
                    from.clear();
                    updateMotion();
                }).start();
            } else {
                from.clear();
            }
            updateMotion();
            return;
        }
        // the new art goes on the back layer, which is then drawn on top (dispatchDraw)
        final Art to = back;
        to.clear();
        to.set(b, blurred);
        to.moving = uri == null || !uri.startsWith(TINT);
        to.t0 = mClock;
        mFront = 1 - mFront;
        mShownUri = uri;
        // art -> art stays opaque all the way; from nothing the sky shows through the fade
        setCovering(mCovering && fromArt);
        fadeScrims(1f, anim);
        Runnable done = () -> {
            if (mArt[mFront] != to) return;
            if (from != to) from.clear();
            setCovering(true);
            updateMotion();
        };
        to.setVisibility(VISIBLE);
        if (anim) {
            to.setAlpha(0f);
            to.animate().alpha(1f).setDuration(fromArt ? FADE_MS : EMPTY_FADE_MS).setInterpolator(Theme.EMPHASIZED)
                    .withEndAction(done).start();
        } else {
            to.setAlpha(1f);
            done.run();
        }
        if (mMotionRunning && to.moving) motion(to);
        updateMotion();
    }

    /** The scrims with the art: 1 over art, 0 over the sky (it has its own). */
    private void fadeScrims(float to, boolean anim) {
        if (mScrimAnim != null) mScrimAnim.cancel();
        mScrimAnim = null;
        if (mScrims.art == to) return;
        if (!anim) {
            mScrims.setArt(to);
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(mScrims.art, to);
        va.setDuration(EMPTY_FADE_MS);
        va.setInterpolator(Theme.EMPHASIZED);
        va.addUpdateListener(a -> mScrims.setArt((Float) a.getAnimatedValue()));
        mScrimAnim = va;
        va.start();
    }

    private void setCovering(boolean c) {
        if (c == mCovering) return;
        mCovering = c;
        // may change during an animation callback: tell the activity outside it
        removeCallbacks(mNotify);
        post(mNotify);
    }

    private final Runnable mNotify = () -> {
        if (mListener != null) mListener.onStageCovering(mCovering);
    };

    /** True while an opaque backdrop fills the screen (no fade running, the view fully visible). */
    public boolean covering() {
        return mCovering && getAlpha() >= 1f && getVisibility() == VISIBLE;
    }

    // ------------------------------------------------------------------ motion

    /**
     * The activity allows the motion while the stage can be seen: Home resumed, its tab shown, the
     * projector awake. It also needs art, no trailer playing, animations on and a rate above 0.
     */
    public void setMotionAllowed(boolean on) {
        mMotionAllowed = on;
        updateMotion();
    }

    private void updateMotion() {
        boolean art = (mArt[0].shown() && mArt[0].moving) || (mArt[1].shown() && mArt[1].moving);
        boolean on = mFrameMs > 0 && mMotionAllowed && !mTrailerPlaying && art && getVisibility() == VISIBLE
                && Theme.animations();
        if (on == mMotionRunning) return;
        mMotionRunning = on;
        removeCallbacks(mStep);
        if (on) {
            mLastStep = SystemClock.uptimeMillis();
            postOnAnimation(mStep);
        }
    }

    private void step() {
        if (!mMotionRunning) return;
        long now = SystemClock.uptimeMillis();
        mClock += Math.min(100, Math.max(0, now - mLastStep));
        mLastStep = now;
        for (Art a : mArt) if (a.shown() && a.moving) motion(a);
        postOnAnimationDelayed(mStep, mFrameMs);
    }

    /** Scale and pan of a layer at the motion clock: eased there and back, never past the art's edge. */
    private void motion(Art a) {
        long t = Math.max(0, mClock - a.t0) % (2 * MOTION_MS);
        float f = t <= MOTION_MS ? t / (float) MOTION_MS : (2 * MOTION_MS - t) / (float) MOTION_MS;
        float e = (float) (1 - Math.cos(Math.PI * f)) / 2f;
        float s = SCALE_FROM + (SCALE_TO - SCALE_FROM) * e;
        // the pan never shows an edge: at scale s the art reaches (s - 1) / 2 past each side, at least 1 % + 5 % x e
        float dir = Theme.rtl(this) ? 1f : -1f;
        a.setScaleX(s);
        a.setScaleY(s);
        a.setTranslationX(dir * PAN_X * getWidth() * e);
        a.setTranslationY(-PAN_Y * getHeight() * e);
    }

    // ------------------------------------------------------------------ trailer, lifecycle

    public void setTrailersEnabled(boolean on) {
        mTrailer.setEnabled(on);
        if (!Gfx.LITE) return;
        // a playing trailer would refresh the layer every video frame: no layer while trailers are allowed
        int want = on ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE;
        if (getLayerType() != want) setLayerType(want, null);
    }

    public void pauseTrailer() {
        mTrailer.stop();
    }

    /** Drop bitmaps (Home stopped / low memory); {@link #reload()} brings them back from the caches. */
    public void release() {
        cancelRequests();
        removeCallbacks(mLoadTimeout);
        mTrailer.stop();
        mArt[0].clear();
        mArt[1].clear();
        mShownUri = null;
        fadeScrims(0f, false);
        setCovering(false);
        updateMotion();
    }

    public void reload() {
        Card k = mCard;
        mCard = null;
        show(k, false);
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (mArt != null && mArt[1] != null) updateMotion();
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        int ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(ws, hs);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(0, 0, r - l, b - t);
    }

    /** Back to front: the old art, the new art (fading in over it), the trailer, the scrims. */
    @Override
    protected void dispatchDraw(Canvas c) {
        long when = getDrawingTime();
        Art front = mArt[mFront], back = mArt[1 - mFront];
        if (back.getVisibility() == VISIBLE) drawChild(c, back, when);
        if (front.getVisibility() == VISIBLE) drawChild(c, front, when);
        View tv = mTrailer.view();
        if (tv.getVisibility() == VISIBLE) drawChild(c, tv, when);
        drawChild(c, mScrims, when);
    }

    /** The three scrims, at the share of art on screen ({@link #art}: 0 = only the sky, 1 = art). */
    private static final class ScrimView extends View {
        private final Paint mPaint = new Paint(Paint.DITHER_FLAG);
        private LinearGradient mStart, mBottom, mTop;
        float art;

        ScrimView(Context c) {
            super(c);
        }

        void setArt(float a) {
            if (a == art) return;
            art = a;
            invalidate();
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false; // drawn with its own alpha, never through an offscreen buffer
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            boolean rtl = Theme.rtl(this);
            int[] side = new int[Scrims.SIDE_ALPHA.length];
            for (int i = 0; i < side.length; i++) side[i] = Theme.alpha(Theme.BG, Scrims.SIDE_ALPHA[i]);
            mStart = rtl ? new LinearGradient(w, 0, 0, 0, side, Scrims.SIDE_POS, Shader.TileMode.CLAMP)
                    : new LinearGradient(0, 0, w, 0, side, Scrims.SIDE_POS, Shader.TileMode.CLAMP);
            int n = Scrims.BOTTOM_ALPHA.length;
            float reach = Scrims.BOTTOM_POS[n - 1];
            int[] bottom = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                bottom[i] = Theme.alpha(Theme.BG, Scrims.BOTTOM_ALPHA[i]);
                pos[i] = Scrims.BOTTOM_POS[i] / reach;
            }
            mBottom = new LinearGradient(0, h, 0, h - h * reach, bottom, pos, Shader.TileMode.CLAMP);
            // the header scrim: even behind the header, then eased out (the whole header reads over white art)
            int[] head = new int[Scrims.HEAD_A.length];
            for (int i = 0; i < head.length; i++) head[i] = Theme.alpha(Theme.BG, Scrims.HEAD_A[i]);
            mTop = new LinearGradient(0, 0, 0, Theme.pxf(Scrims.HEAD_END), head, Scrims.HEAD_POS, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (art <= 0f || mStart == null) return;
            int w = getWidth(), h = getHeight();
            mPaint.setAlpha(Math.round(255 * art));
            mPaint.setShader(mStart);
            c.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(mBottom);
            c.drawRect(0, h - h * Scrims.BOTTOM_POS[Scrims.BOTTOM_POS.length - 1], w, h, mPaint);
            // the header's: over the sky's own (the same), so a fade to or from no art never lightens it
            mPaint.setAlpha(Math.round(255 * Scrims.headOverArt(art)));
            mPaint.setShader(mTop);
            c.drawRect(0, 0, w, Theme.pxf(Scrims.HEAD_END), mPaint);
            mPaint.setShader(null);
        }
    }
}
