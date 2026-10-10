package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import org.z9x.home.App;
import org.z9x.home.data.Card;
import org.z9x.home.img.HeroArt;
import org.z9x.home.img.ImageLoader;

/**
 * The hero's art behind the whole Home (D_Home), between the living sky and the pages. 1.0.1 follow-up
 * (owner report): the layout follows the art's real pixels, never the declared aspect ({@link HeroArt}).
 * Of the program's poster art and thumbnail the larger is used. Big 16:9-ish art (at most 1.25x
 * enlarged) fills the backdrop, cropped from the top; anything else gets the 1.0.0 poster layout: a
 * blurred, darkened copy full-bleed and the art itself as a crisp framed card at no more than its own
 * pixels (16:9, square or 2:3; at 2K and 4K up to 1.25x, {@link UiScale#cardEnlarge}), on the end side
 * above the scrims. All of it in real pixels of the UI mode: the 16:9 card box is 640x360 at 1080p,
 * 853x480 at 2K, 1280x720 at 4K, and full-bleed art must cover the real screen (masters stop at
 * 2560x1440, so at 4K every hero is a card). The optional silent trailer plays full-bleed under the
 * scrims (the card steps aside while it plays). The D scrims (start side 94 % -> 0 at 76 %, bottom 620
 * px) and the header scrim ({@link Scrims#HEAD_ALPHA} to y 140, eased out by y 340: the whole header
 * reads on white art; the sky draws the same one when the stage is empty) are dithered. Without art the
 * stage is empty and the sky shows through. Art changes crossfade over 700 ms; {@link #covering()} tells
 * the activity when the sky is fully hidden (it then pauses the sky).
 */
public class Stage extends ViewGroup {
    public interface Listener {
        /** The stage became fully opaque (true) or not (false). */
        void onStageCovering(boolean covering);
    }

    // the framed card, design px: end edge 220 from the screen edge, centred in the band from y 170 to the
    // art bottom (1.0.0's poster: 340x510 at y 170)
    private static final int CARD_END = 220, CARD_TOP = 170, WIDE_W = 640, SQUARE = 420, POSTER_H = 510;
    private static final int ART_BOTTOM = 680;
    private static final float BLUR_ALPHA = 0.8f; // the blurred copy over the ground: darkened by a fifth
    private final ImageLoader mImages;
    private final Paint mBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mScrim = new Paint(Paint.DITHER_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final View mCardView;
    private final Trailer mTrailer;
    private Listener mListener;
    private LinearGradient mStart, mBottom, mTop;
    private static final Layer EMPTY = new Layer(); // never mutated
    private Layer mCur = EMPTY, mPrev = EMPTY;
    private long mFade;
    private float mScrimA;
    private Card mCard;
    private ImageLoader.Request mReq, mCardReq, mProbe0, mProbe1;
    private boolean mCovering;
    private int mArtBottom;

    /** One art state: a full-bleed backdrop, or a blurred copy plus the framed card. */
    private static final class Layer {
        Bitmap back;
        Bitmap card;      // decoded at the card's size, or the art's own pixels when it has fewer
        int cw, ch;       // the card's size on screen (real px)
        boolean blurred;

        boolean empty() {
            return back == null;
        }
    }

    public Stage(Context c, ImageLoader il) {
        super(c);
        mImages = il;
        mArtBottom = Theme.px(ART_BOTTOM);
        setWillNotDraw(false);
        setClipChildren(false);
        mTrailer = new Trailer(c);
        mTrailer.setOnPlaying(this::onTrailerPlaying);
        addView(mTrailer.view());
        final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Theme.pxf(2));
        frame.setColor(Theme.alpha(Theme.TEXT1, 0.14f));
        final RectF fr = new RectF();
        mCardView = new View(c) {
            @Override
            protected void onDraw(Canvas cv) {
                Bitmap b = mCur.card;
                if (b == null) return;
                // the view is the card's size: the bitmap 1:1, or enlarged up to 1.25x (2K / 4K, small art)
                mDst.set(0, 0, getWidth(), getHeight());
                cv.drawBitmap(b, null, mDst, mBmp);
                float sw = frame.getStrokeWidth();
                fr.set(sw / 2, sw / 2, getWidth() - sw / 2, getHeight() - sw / 2);
                cv.drawRoundRect(fr, Theme.pxf(20) - sw / 2, Theme.pxf(20) - sw / 2, frame);
            }
        };
        mCardView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Theme.pxf(20));
            }
        });
        mCardView.setClipToOutline(true);
        mCardView.setElevation(Theme.pxf(24));
        mCardView.setVisibility(GONE);
        addView(mCardView);
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public Card card() {
        return mCard;
    }

    /**
     * Lowest y of the framed card (real px): the first row's top minus a gap, so the card never reaches
     * the row (PageForYou moves the row up when its cards are tall).
     */
    public void setArtBottom(int px) {
        if (px == mArtBottom) return;
        mArtBottom = px;
        placeCard();
    }

    /** Shows the art of {@code k} (null or art-less = empty stage: the sky shows). */
    public void show(Card k, boolean animate) {
        if (k != null && mCard != null && k.id.equals(mCard.id) && java.util.Objects.equals(k.image, mCard.image)
                && java.util.Objects.equals(k.image2, mCard.image2) && !mCur.empty()) {
            mCard = k;
            return;
        }
        mCard = k;
        cancelRequests();
        mTrailer.stop();
        removeCallbacks(mLoadTimeout);
        String[] uris = candidates(k);
        if (uris.length == 0) {
            crossfade(EMPTY, animate);
            return;
        }
        // the real size of each candidate decides the layout; known sizes decide at once
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

    /** The program's art and its other art (thumbnail next to poster art), each once. */
    private static String[] candidates(Card k) {
        if (k == null) return new String[0];
        boolean a = k.image != null && !k.image.isEmpty();
        boolean b = k.image2 != null && !k.image2.isEmpty() && !k.image2.equals(k.image);
        if (a && b) return new String[]{k.image, k.image2};
        if (a) return new String[]{k.image};
        if (b) return new String[]{k.image2};
        return new String[0];
    }

    private void apply(Card k, String[] uris, int[] sizes, boolean animate) {
        int sw = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        int sh = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        // the card boxes in real px, never taller than the band between y 170 and the art bottom
        int band = Math.max(Theme.px(160), mArtBottom - Theme.px(CARD_TOP));
        HeroArt.Plan p = HeroArt.choose(sizes, sw, sh, Math.min(Theme.px(WIDE_W), band * 16 / 9), Math.min(Theme.px(SQUARE), band),
                Math.min(Theme.px(POSTER_H), band), UiScale.cardEnlarge(Theme.scale()));
        if (p.mode == HeroArt.NONE) {
            Log.i(App.TAG, "hero art none id=" + k.id);
            crossfade(EMPTY, animate); // nothing could be fetched: the sky
            return;
        }
        final String uri = uris[p.index];
        final String id = k.id;
        StringBuilder sz = new StringBuilder();
        for (int i = 0; i + 1 < sizes.length; i += 2) sz.append(i == 0 ? "" : ",").append(sizes[i]).append('x').append(sizes[i + 1]);
        Log.i(App.TAG, "hero art id=" + id + " src=" + sz + " ui=" + sw + "x" + sh + " -> " + p);
        if (p.mode == HeroArt.FILL) {
            Bitmap b = mImages.peekHero(uri, p.w, p.h);
            if (b != null) {
                Layer l = new Layer();
                l.back = b;
                crossfade(l, animate);
                mTrailer.arm(k);
                return;
            }
            expectSoon(id);
            mReq = mImages.loadHero(uri, p.w, p.h, k.pkg, (bmp, col) -> {
                if (!isCard(id)) return;
                Layer l = new Layer();
                l.back = bmp;
                crossfade(l, true);
                mTrailer.arm(mCard);
            });
            return;
        }
        // the framed card: the blurred copy behind, the sharp card pops in when it arrives
        final Layer l = new Layer();
        l.blurred = true;
        l.cw = p.w;
        l.ch = p.h;
        l.back = mImages.peekBlur(uri);
        l.card = mImages.peekHero(uri, p.w, p.h);
        // as 1.0.0: no trailer next to a poster; landscape art may have one (the card steps aside)
        final boolean trailer = p.shape != HeroArt.POSTER;
        if (l.back != null) {
            crossfade(l, animate);
            if (trailer) mTrailer.arm(k);
        } else {
            expectSoon(id);
            mReq = mImages.loadBlur(uri, k.pkg, (b, col) -> {
                if (!isCard(id)) return;
                l.back = b;
                crossfade(l, true);
                if (trailer) mTrailer.arm(mCard);
            });
        }
        if (l.card == null) mCardReq = mImages.loadHero(uri, p.w, p.h, k.pkg, (b, col) -> {
            if (!isCard(id)) return;
            l.card = b;
            if (mCur == l) showCard(true);
        });
    }

    private void cancelRequests() {
        if (mReq != null) mReq.cancel();
        if (mCardReq != null) mCardReq.cancel();
        if (mProbe0 != null) mProbe0.cancel();
        if (mProbe1 != null) mProbe1.cancel();
        mReq = mCardReq = mProbe0 = mProbe1 = null;
    }

    /** If the art of {@code id} has not arrived in 2.5 s (offline, failed), fade to the sky. */
    private void expectSoon(String id) {
        removeCallbacks(mLoadTimeout);
        mWaitId = id;
        postDelayed(mLoadTimeout, 2500);
    }

    private String mWaitId;
    // crossfade() cancels it: if it runs, nothing new was shown for this card
    private final Runnable mLoadTimeout = () -> {
        if (isCard(mWaitId) && !mCur.empty()) crossfade(EMPTY, true);
    };

    private boolean isCard(String id) {
        return mCard != null && mCard.id.equals(id);
    }

    private void crossfade(Layer to, boolean animate) {
        if (to == mCur) return;
        removeCallbacks(mLoadTimeout);
        mPrev = mCur;
        mCur = to;
        mFade = animate && Theme.animations() && !(mPrev.empty() && to.empty()) ? android.os.SystemClock.uptimeMillis() : 0;
        if (mFade == 0) mPrev = EMPTY;
        showCard(animate);
        // art -> art stays opaque all the way; to or from nothing the sky shows through the fade
        setCovering(mCovering && !mPrev.empty() && !to.empty());
        invalidate();
        if (mFade == 0) setCovering(!to.empty());
    }

    private void showCard(boolean animate) {
        Bitmap p = mCur.card;
        mCardView.animate().cancel();
        mCardView.setVisibility(p != null ? VISIBLE : GONE);
        if (p == null) return;
        placeCard();
        mCardView.invalidate();
        if (animate && Theme.animations()) {
            mCardView.setAlpha(0f);
            mCardView.animate().alpha(1f).setDuration(Theme.STAGE_MS).start();
        } else {
            mCardView.setAlpha(1f);
        }
    }

    /**
     * Sizes and places the card view at the card's size (whole pixels: the bitmap is drawn 1:1 when it
     * has them). Called outside layout passes too: laying out one child does not lay out the window.
     */
    private void placeCard() {
        Layer l = mCur;
        if (l.card == null || getWidth() == 0) return;
        int cw = l.cw, ch = l.ch;
        int w = getWidth();
        int top = Theme.px(CARD_TOP);
        int y = top + Math.max(0, (mArtBottom - top - ch) / 2);
        int x = Theme.rtl(this) ? Theme.px(CARD_END) : w - Theme.px(CARD_END) - cw;
        if (mCardView.getWidth() != cw || mCardView.getHeight() != ch) {
            mCardView.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
        mCardView.layout(x, y, x + cw, y + ch);
    }

    /** The trailer plays full-bleed: the card steps aside meanwhile and comes back when it stops. */
    private void onTrailerPlaying(boolean playing) {
        if (mCardView.getVisibility() != VISIBLE) return;
        mCardView.animate().cancel();
        mCardView.animate().alpha(playing ? 0f : 1f).setDuration(playing ? 600 : Theme.STAGE_MS).start();
    }

    private void setCovering(boolean c) {
        if (c == mCovering) return;
        mCovering = c;
        // may change during onDraw (end of a fade): tell the activity outside the draw pass
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

    public void setTrailersEnabled(boolean on) {
        mTrailer.setEnabled(on);
    }

    public void pauseTrailer() {
        mTrailer.stop();
    }

    /** Drop bitmaps (Home stopped / low memory); {@link #reload()} brings them back from the caches. */
    public void release() {
        cancelRequests();
        mTrailer.stop();
        mCur = EMPTY;
        mPrev = EMPTY;
        mCardView.setVisibility(GONE);
        setCovering(false);
        invalidate();
    }

    public void reload() {
        Card k = mCard;
        mCard = null;
        show(k, false);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        mTrailer.view().measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        boolean card = mCur.card != null;
        int cw = card ? mCur.cw : 1, ch = card ? mCur.ch : 1;
        mCardView.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mTrailer.view().layout(0, 0, r - l, b - t);
        if (mCur.card != null) placeCard();
        else mCardView.layout(0, 0, mCardView.getMeasuredWidth(), mCardView.getMeasuredHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int g = Theme.BG & 0x00FFFFFF;
        boolean rtl = Theme.rtl(this);
        // D_Home: linear-gradient(90deg, .94 0%, .8 27%, .22 57%, 0 76%); bottom 620 px: ground 44% -> 0
        int[] cols = new int[Scrims.SIDE_ALPHA.length];
        for (int i = 0; i < cols.length; i++) cols[i] = Theme.alpha(Theme.BG, Scrims.SIDE_ALPHA[i]);
        mStart = rtl ? new LinearGradient(w, 0, 0, 0, cols, Scrims.SIDE_POS, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, w, 0, cols, Scrims.SIDE_POS, Shader.TileMode.CLAMP);
        float top = h - Theme.pxf(620);
        mBottom = new LinearGradient(0, h, 0, top, new int[]{g | 0xFF000000, g | 0xFF000000, g},
                new float[]{0f, 0.44f, 1f}, Shader.TileMode.CLAMP);
        // the header scrim: even behind the header, then eased out (the whole header reads over white art)
        int[] head = new int[Scrims.HEAD_A.length];
        for (int i = 0; i < head.length; i++) head[i] = Theme.alpha(Theme.BG, Scrims.HEAD_A[i]);
        mTop = new LinearGradient(0, 0, 0, Theme.pxf(Scrims.HEAD_END), head, Scrims.HEAD_POS, Shader.TileMode.CLAMP);
        if (mCur.card != null) post(this::placeCard);
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = 1f;
        if (mFade > 0) {
            t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - mFade) / (float) Theme.STAGE_MS);
            t = Theme.EMPHASIZED.getInterpolation(t);
            if (t < 1f) postInvalidateOnAnimation();
            else {
                mFade = 0;
                mPrev = EMPTY;
                setCovering(!mCur.empty());
            }
        }
        boolean any = !mCur.empty() || (!mPrev.empty() && t < 1f);
        // the scrims fade with the art, so the sky's own (lighter) scrims never jump
        mScrimA = Math.min(1f, (mCur.empty() ? 0f : t) + (mPrev.empty() || t >= 1f ? 0f : 1f - t));
        if (!any) return;
        int w = getWidth(), h = getHeight();
        // art -> art: the new one fades in over the old; art -> nothing: the old one fades out
        if (t < 1f) layer(c, mPrev, mCur.empty() ? 1f - t : 1f, w, h);
        layer(c, mCur, t, w, h);
    }

    private void layer(Canvas c, Layer l, float a, int w, int h) {
        if (l.empty() || a <= 0f) return;
        mSrc.set(0, 0, l.back.getWidth(), l.back.getHeight());
        mDst.set(0, 0, w, h);
        mBmp.setAlpha((int) (255 * a * (l.blurred ? BLUR_ALPHA : 1f)));
        if (l.blurred) c.drawColor(Theme.alpha(Theme.BG, a));
        c.drawBitmap(l.back, mSrc, mDst, mBmp);
        mBmp.setAlpha(255);
    }

    /**
     * Back to front: the backdrop (onDraw), the trailer, the scrims, then the framed card above them
     * (a dimmed card would not look sharp), with its elevation shadow.
     */
    @Override
    protected void dispatchDraw(Canvas c) {
        long when = getDrawingTime();
        View tv = mTrailer.view();
        if (tv.getVisibility() == VISIBLE) drawChild(c, tv, when);
        if (!(mCur.empty() && mPrev.empty()) && mStart != null && mScrimA > 0f) {
            int w = getWidth(), h = getHeight();
            mScrim.setAlpha(Math.round(255 * mScrimA));
            mScrim.setShader(mStart);
            c.drawRect(0, 0, w, h, mScrim);
            mScrim.setShader(mBottom);
            c.drawRect(0, h - Theme.pxf(620), w, h, mScrim);
            // the header's: over the sky's own (the same), so a fade to or from no art never lightens it
            mScrim.setAlpha(Math.round(255 * Scrims.headOverArt(mScrimA)));
            mScrim.setShader(mTop);
            c.drawRect(0, 0, w, Theme.pxf(Scrims.HEAD_END), mScrim);
            mScrim.setShader(null);
        }
        if (mCardView.getVisibility() == VISIBLE) {
            c.enableZ(); // the card's elevation shadow, as ViewGroup.dispatchDraw does for its children
            drawChild(c, mCardView, when);
            c.disableZ();
        }
    }
}
