package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.img.ImageLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Spotlight hero (SPEC 7.5): full-bleed art in the 1280x720 box at the right, three scrims, the text
 * block at x 96, two buttons at y 540 and page dots at y 628. Auto-advance every 9 s (owner decides
 * when), crossfade 600 ms, text out 150 ms then in 300 ms with a 16 px rise. Portrait art uses the
 * poster layout (2:3 poster on the right over its blurred enlargement).
 */
public class HeroView extends ViewGroup {
    public interface Host {
        ImageLoader images();

        void onHeroAction(Card k, boolean primary);

        void onHeroShown(Card k);
    }

    private final Host mHost;
    private final Art mArt;
    private final View mPoster;
    private final LinearLayout mText;
    private final ImageView mProvIcon;
    private final TextView mProvName, mTitle, mMeta, mDesc;
    private final ProgressLine mProgress;
    private final PillButton mPrimary, mSecondary;
    private final Dots mDots;
    private final Trailer mTrailer;
    private final ArrayList<Card> mSlides = new ArrayList<>();
    private int mIndex;
    private int mFocusBtn = -1;
    private Bitmap mPosterBmp;
    private ImageLoader.Request mReq, mIconReq, mPosterReq;

    public HeroView(Context c, Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mArt = new Art(c);
        addView(mArt);
        mPoster = new View(c) {
            @Override
            protected void onDraw(Canvas cv) {
                if (mPosterBmp != null) cv.drawBitmap(mPosterBmp, null, new RectF(0, 0, getWidth(), getHeight()), mArt.mBmpPaint);
            }
        };
        mPoster.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Theme.pxf(18));
            }
        });
        mPoster.setClipToOutline(true);
        mPoster.setElevation(Theme.pxf(24));
        mPoster.setVisibility(GONE);
        addView(mPoster);
        mTrailer = new Trailer(c);
        addView(mTrailer.view());

        mText = new LinearLayout(c);
        mText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout prov = new LinearLayout(c);
        prov.setOrientation(LinearLayout.HORIZONTAL);
        prov.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mProvIcon = new ImageView(c);
        mProvIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Theme.pxf(10));
            }
        });
        mProvIcon.setClipToOutline(true);
        prov.addView(mProvIcon, new LinearLayout.LayoutParams(Theme.px(40), Theme.px(40)));
        mProvName = new TextView(c);
        Theme.text(mProvName, 24, Theme.MEDIUM, Theme.TEXT2);
        mProvName.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(Theme.px(14));
        prov.addView(mProvName, lp);
        mText.addView(prov, new LinearLayout.LayoutParams(-2, -2));
        mTitle = new TextView(c);
        Theme.text(mTitle, 64, Theme.MEDIUM, Theme.TEXT1);
        mTitle.setLetterSpacing(-0.005f);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setLineSpacing(Theme.pxf(8), 1f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Theme.px(18);
        mText.addView(mTitle, tl);
        LinearLayout metaRow = new LinearLayout(c);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mMeta = new TextView(c);
        Theme.text(mMeta, 24, Theme.REGULAR, Theme.TEXT2);
        mMeta.setSingleLine(true);
        metaRow.addView(mMeta, new LinearLayout.LayoutParams(-2, -2));
        mProgress = new ProgressLine(c);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Theme.px(160), Theme.px(6));
        pl.setMarginStart(Theme.px(16));
        metaRow.addView(mProgress, pl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = Theme.px(12);
        mText.addView(metaRow, ml);
        mDesc = new TextView(c);
        Theme.text(mDesc, 28, Theme.REGULAR, Theme.TEXT2);
        mDesc.setMaxLines(2);
        mDesc.setEllipsize(TextUtils.TruncateAt.END);
        mDesc.setLineSpacing(Theme.pxf(12), 1f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Theme.px(860), -2);
        dl.topMargin = Theme.px(14);
        mText.addView(mDesc, dl);
        addView(mText);

        mPrimary = new PillButton(c, 64, 28, PillButton.STYLE_FILLED).icon(R.drawable.ic_play);
        mSecondary = new PillButton(c, 64, 28, PillButton.STYLE_OUTLINE).icon(R.drawable.ic_open);
        mPrimary.maxWidth(Theme.px(560));
        mSecondary.maxWidth(Theme.px(560));
        addView(mPrimary);
        addView(mSecondary);
        mDots = new Dots(c);
        addView(mDots);
    }

    // ------------------------------------------------------------------ slides

    public void setSlides(List<Card> slides) {
        Card cur = current();
        mSlides.clear();
        mSlides.addAll(slides);
        int idx = 0;
        if (cur != null) {
            for (int i = 0; i < mSlides.size(); i++) if (mSlides.get(i).id.equals(cur.id)) idx = i;
        }
        boolean same = cur != null && idx < mSlides.size() && mSlides.get(idx).sameContent(cur);
        mIndex = idx;
        mDots.set(mSlides.size(), mIndex);
        if (!same) show(false);
    }

    public Card current() {
        return mIndex < mSlides.size() ? mSlides.get(mIndex) : null;
    }

    /** Same slide (a refresh may replace the object with an equal one while art loads). */
    private boolean isCurrent(Card k) {
        Card c = current();
        return c != null && c.id.equals(k.id) && java.util.Objects.equals(c.image, k.image);
    }

    public int count() {
        return mSlides.size();
    }

    public void next(boolean animate) {
        if (mSlides.size() < 2) return;
        mIndex = (mIndex + 1) % mSlides.size();
        show(animate);
    }

    public void prev(boolean animate) {
        if (mSlides.size() < 2) return;
        mIndex = (mIndex - 1 + mSlides.size()) % mSlides.size();
        show(animate);
    }

    private void show(boolean animate) {
        Card k = current();
        mDots.set(mSlides.size(), mIndex);
        mTrailer.stop();
        if (k == null) {
            mArt.setBitmap(null, null, false);
            return;
        }
        if (animate) {
            mText.animate().cancel();
            mText.animate().alpha(0).setDuration(150).withEndAction(() -> {
                bindText(k);
                mText.setTranslationY(Theme.pxf(16));
                mText.animate().alpha(1).translationY(0).setDuration(300).setInterpolator(Theme.EMPHASIZED).start();
            }).start();
        } else {
            bindText(k);
            mText.setAlpha(1);
            mText.setTranslationY(0);
        }
        loadArt(k, animate);
        mHost.onHeroShown(k);
    }

    private void bindText(Card k) {
        boolean feature = k.kind == Card.FEATURE;
        mProvName.setText(feature ? "Lumen OS" : k.appLabel);
        mProvIcon.setImageDrawable(null);
        if (mIconReq != null) mIconReq.cancel();
        if (feature) {
            mProvIcon.setImageDrawable(Theme.icon(getContext(), R.drawable.ic_spotlight, Theme.TEXT1));
        } else if (!k.pkg.isEmpty()) {
            int s = Theme.px(40);
            Bitmap b = mHost.images().peek("icon:" + k.pkg, s, s);
            if (b != null) mProvIcon.setImageBitmap(b);
            else mIconReq = mHost.images().load("icon:" + k.pkg, s, s, ImageLoader.KIND_ICON, k.pkg, null, (bmp, col) -> {
                if (isCurrent(k)) mProvIcon.setImageBitmap(bmp);
            });
        }
        mTitle.setText(k.title);
        mMeta.setText(k.meta);
        mMeta.setVisibility(k.meta.isEmpty() ? GONE : VISIBLE);
        mProgress.set(k.progress);
        mProgress.setVisibility(k.progress >= 0 ? VISIBLE : GONE);
        mDesc.setText(k.desc);
        mDesc.setVisibility(k.desc.isEmpty() ? GONE : VISIBLE);
        Context c = getContext();
        switch (k.intent == null ? "" : k.intent) {
            case "feature:cast":
                mPrimary.icon(R.drawable.ic_cast).label(c.getString(R.string.hero_how_to_cast));
                mSecondary.setVisibility(GONE);
                break;
            case "feature:picture":
                mPrimary.icon(R.drawable.ic_autofocus).label(c.getString(R.string.tile_autofocus));
                mSecondary.icon(R.drawable.ic_keystone).label(c.getString(R.string.tile_keystone));
                mSecondary.setVisibility(VISIBLE);
                break;
            case "feature:customize":
                mPrimary.icon(R.drawable.ic_edit).label(c.getString(R.string.customize_home));
                mSecondary.setVisibility(GONE);
                break;
            default: {
                boolean cont = k.table == Card.T_WATCH_NEXT && k.progress >= 0;
                boolean playable = k.intent != null && !k.intent.isEmpty();
                mPrimary.icon(playable ? R.drawable.ic_play : R.drawable.ic_open)
                        .label(c.getString(cont ? R.string.hero_continue : (playable ? R.string.hero_watch : R.string.hero_open)));
                mSecondary.icon(R.drawable.ic_open).label(c.getString(R.string.hero_open_app, k.appLabel));
                mSecondary.setVisibility(VISIBLE);
            }
        }
        if (mFocusBtn == 1 && mSecondary.getVisibility() != VISIBLE) setFocusButton(0);
        requestLayout();
    }

    private void loadArt(Card k, boolean animate) {
        if (mReq != null) mReq.cancel();
        if (mPosterReq != null) mPosterReq.cancel();
        if (k.kind == Card.FEATURE || k.image == null) {
            mPoster.setVisibility(GONE);
            mArt.setFeature(k, animate);
            return;
        }
        ImageLoader il = mHost.images();
        boolean portrait = k.aspect == Card.A_2_3;
        if (portrait) {
            Bitmap blur = il.peekBlur(k.image);
            mPosterBmp = null;
            int pw = Theme.px(300), ph = Theme.px(450);
            Bitmap pb = il.peek(k.image, pw, ph);
            mPosterBmp = pb;
            mPoster.setVisibility(VISIBLE);
            mPoster.invalidate();
            if (pb == null) mPosterReq = il.load(k.image, pw, ph, ImageLoader.KIND_HERO, k.pkg, null, (b, c) -> {
                if (!isCurrent(k)) return;
                mPosterBmp = b;
                mPoster.setAlpha(0);
                mPoster.invalidate();
                mPoster.animate().alpha(1).setDuration(300).start();
            });
            if (blur != null) mArt.setBitmap(blur, k, animate);
            else mReq = il.loadBlur(k.image, k.pkg, (b, c) -> {
                if (isCurrent(k)) mArt.setBitmap(b, k, true);
            });
            return;
        }
        mPoster.setVisibility(GONE);
        int w = Theme.px(1280), h = Theme.px(720);
        Bitmap b = il.peek(k.image, w, h);
        if (b != null) {
            mArt.setBitmap(b, k, animate);
            mTrailer.arm(k);
            return;
        }
        mReq = il.load(k.image, w, h, ImageLoader.KIND_HERO, k.pkg, null, (bmp, col) -> {
            if (!isCurrent(k)) return;
            mArt.setBitmap(bmp, k, true);
            mTrailer.arm(k);
        });
    }

    public boolean artReady() {
        return mArt.mCur != null || (current() != null && (current().kind == Card.FEATURE || current().image == null));
    }

    /** Low memory / hidden: drop bitmaps (re-loaded from cache on show). */
    public void release() {
        if (mReq != null) mReq.cancel();
        if (mPosterReq != null) mPosterReq.cancel();
        mTrailer.stop();
        mArt.mCur = null;
        mArt.mPrev = null;
        mPosterBmp = null;
    }

    public void reload() {
        Card k = current();
        if (k != null && mArt.mCur == null) loadArt(k, false);
    }

    public void setTrailersEnabled(boolean on) {
        mTrailer.setEnabled(on);
    }

    public void pauseTrailer() {
        mTrailer.stop();
    }

    // ------------------------------------------------------------------ focus

    public boolean hasButtonFocus() {
        return mFocusBtn >= 0;
    }

    public void focusIn() {
        setFocusButton(0);
    }

    public void focusOut() {
        setFocusButton(-1);
    }

    private void setFocusButton(int b) {
        mFocusBtn = b;
        mPrimary.setFocusState(b == 0, true);
        mSecondary.setFocusState(b == 1, true);
    }

    /** LEFT/RIGHT on the buttons; past the first/last button switches slides (SPEC 7.5). */
    public boolean onKey(int keyCode, KeyEvent e) {
        if (mFocusBtn < 0) return false;
        boolean rtl = Theme.rtl(this);
        boolean two = mSecondary.getVisibility() == VISIBLE;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                boolean fwd = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl;
                if (fwd) {
                    if (mFocusBtn == 0 && two) setFocusButton(1);
                    else next(true);
                } else {
                    if (mFocusBtn == 1) setFocusButton(0);
                    else prev(true);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                Card k = current();
                if (k == null) return true;
                PillButton b = mFocusBtn == 0 ? mPrimary : mSecondary;
                b.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).withEndAction(() ->
                        b.animate().scaleX(1.04f).scaleY(1.04f).setDuration(60).start()).start();
                mHost.onHeroAction(k, mFocusBtn == 0);
                return true;
            }
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ layout (design px, mirrored in RTL)

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        mArt.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Theme.px(720), MeasureSpec.EXACTLY));
        mPoster.measure(MeasureSpec.makeMeasureSpec(Theme.px(300), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Theme.px(450), MeasureSpec.EXACTLY));
        mTrailer.view().measure(MeasureSpec.makeMeasureSpec(Theme.px(1280), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Theme.px(720), MeasureSpec.EXACTLY));
        mText.measure(MeasureSpec.makeMeasureSpec(Theme.px(1000), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        mPrimary.measure(un, un);
        mSecondary.measure(un, un);
        mDots.measure(un, un);
        setMeasuredDimension(w, Theme.px(720));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        boolean rtl = Theme.rtl(this);
        mArt.layout(0, 0, w, Theme.px(720));
        lay(mPoster, Theme.px(1404), Theme.px(120), w, rtl);
        lay(mTrailer.view(), Theme.px(640), 0, w, rtl);
        int textBottom = Theme.px(516);
        lay(mText, Theme.px(Theme.MARGIN), textBottom - mText.getMeasuredHeight(), w, rtl);
        int x = Theme.px(Theme.MARGIN);
        lay(mPrimary, x, Theme.px(540), w, rtl);
        lay(mSecondary, x + mPrimary.getMeasuredWidth() + Theme.px(20), Theme.px(540), w, rtl);
        lay(mDots, x, Theme.px(628), w, rtl);
    }

    private static void lay(View v, int x, int y, int w, boolean rtl) {
        int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
        int lx = rtl ? w - x - vw : x;
        v.layout(lx, y, lx + vw, y + vh);
    }

    // ------------------------------------------------------------------ art layer

    /** Draws the art box (crossfade), the feature art and the three scrims in one pass. */
    private final class Art extends View {
        final Paint mBmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint mScrim = new Paint();
        private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bitmap mCur, mPrev;
        Card mCurFeature, mPrevFeature;
        private long mFade;
        private final RectF mBox = new RectF();
        private final Rect mSrc = new Rect();
        private LinearGradient mLeft, mBottom, mTop;
        private final android.graphics.PorterDuffXfermode DST_IN = new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN);

        Art(Context c) {
            super(c);
        }

        void setBitmap(Bitmap b, Card k, boolean animate) {
            mPrev = mCur;
            mPrevFeature = mCurFeature;
            mCur = b;
            mCurFeature = null;
            mFade = animate && Theme.animations() ? android.os.SystemClock.uptimeMillis() : 0;
            invalidate();
        }

        void setFeature(Card k, boolean animate) {
            mPrev = mCur;
            mPrevFeature = mCurFeature;
            mCur = null;
            mCurFeature = k;
            mFade = animate && Theme.animations() ? android.os.SystemClock.uptimeMillis() : 0;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            boolean rtl = Theme.rtl(this);
            int bg = Theme.BG & 0x00FFFFFF;
            float x0 = rtl ? w - Theme.pxf(640) : Theme.pxf(640), x1 = rtl ? w - Theme.pxf(1280) : Theme.pxf(1280);
            // alpha masks: the art fades to transparent (into the ambient backdrop), so there is no seam
            mLeft = new LinearGradient(x0, 0, x1, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
            mBottom = new LinearGradient(0, Theme.pxf(420), 0, Theme.pxf(700), 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
            mTop = new LinearGradient(0, 0, 0, Theme.pxf(230), bg | 0xB3000000, bg, Shader.TileMode.CLAMP);
            float bx = rtl ? 0 : Theme.pxf(640);
            mBox.set(bx, 0, bx + Theme.pxf(1280), Theme.pxf(720));
        }

        @Override
        protected void onDraw(Canvas c) {
            float t = 1f;
            if (mFade > 0) {
                t = Math.min(1f, (android.os.SystemClock.uptimeMillis() - mFade) / (float) Theme.HERO_FADE_MS);
                t = Theme.EMPHASIZED.getInterpolation(t);
                if (t < 1f) postInvalidateOnAnimation();
                else {
                    mFade = 0;
                    mPrev = null;
                    mPrevFeature = null;
                }
            }
            int w = getWidth();
            int sc = c.saveLayer(mBox, null);
            if (t < 1f) layer(c, mPrev, mPrevFeature, 1f);
            layer(c, mCur, mCurFeature, t);
            mScrim.setXfermode(DST_IN);
            mScrim.setShader(mLeft);
            c.drawRect(mBox, mScrim);
            mScrim.setShader(mBottom);
            c.drawRect(mBox, mScrim);
            mScrim.setXfermode(null);
            c.restoreToCount(sc);
            mScrim.setShader(mTop);
            c.drawRect(0, 0, w, Theme.pxf(230), mScrim);
            mScrim.setShader(null);
        }

        private void layer(Canvas c, Bitmap b, Card feature, float a) {
            if (a <= 0f) return;
            if (b != null) {
                mSrc.set(0, 0, b.getWidth(), b.getHeight());
                mBmpPaint.setAlpha((int) (255 * a));
                c.drawBitmap(b, mSrc, mBox, mBmpPaint);
                mBmpPaint.setAlpha(255);
            } else if (feature != null) {
                drawFeature(c, feature, a);
            }
        }

        private void drawFeature(Canvas c, Card k, float a) {
            float cx = mBox.centerX() + (Theme.rtl(this) ? -1 : 1) * Theme.pxf(120), cy = Theme.pxf(330);
            float r = Theme.pxf(520);
            int col = k.color != 0 ? k.color : Theme.SURFACE2;
            mGlow.setShader(new RadialGradient(cx, cy, r, (col & 0x00FFFFFF) | 0xFF000000, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
            mGlow.setAlpha((int) (255 * a));
            c.drawCircle(cx, cy, r, mGlow);
            mGlow.setShader(new RadialGradient(cx + Theme.pxf(160), cy - Theme.pxf(120), Theme.pxf(300),
                    (Theme.ACCENT & 0x00FFFFFF) | 0x55000000, Theme.ACCENT & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(cx + Theme.pxf(160), cy - Theme.pxf(120), Theme.pxf(300), mGlow);
            mGlow.setShader(null);
            if (k.icon != 0) {
                Drawable d = Theme.icon(getContext(), k.icon, Theme.ACCENT);
                if (d != null) {
                    int s = Theme.px(300);
                    d.setAlpha((int) (210 * a));
                    d.setBounds((int) cx - s / 2, (int) cy - s / 2, (int) cx + s / 2, (int) cy + s / 2);
                    d.draw(c);
                }
            }
        }
    }

    /** Small progress line next to the hero meta. */
    static final class ProgressLine extends View {
        private int mP = -1;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();

        ProgressLine(Context c) {
            super(c);
        }

        void set(int permille) {
            mP = permille;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mP < 0) return;
            float h = getHeight(), w = getWidth();
            mPaint.setColor(0x4DFFFFFF);
            mR.set(0, 0, w, h);
            c.drawRoundRect(mR, h / 2, h / 2, mPaint);
            mPaint.setColor(Theme.ACCENT);
            boolean rtl = Theme.rtl(this);
            float pw = w * mP / 1000f;
            mR.set(rtl ? w - pw : 0, 0, rtl ? w : pw, h);
            c.drawRoundRect(mR, h / 2, h / 2, mPaint);
        }
    }

    /** Page dots: 8x8 at 30 %, the active one a 28x8 pill (width animates 250 ms). */
    static final class Dots extends View {
        private int mN, mI;
        private float mAnim = 1f;
        private int mPrevI;
        private long mStart;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();

        Dots(Context c) {
            super(c);
        }

        void set(int n, int i) {
            if (n != mN) {
                mN = n;
                mPrevI = i;
                requestLayout();
            } else if (i != mI) {
                mPrevI = mI;
                mStart = Theme.animations() ? android.os.SystemClock.uptimeMillis() : 0;
            }
            mI = i;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int n = Math.max(0, mN);
            setMeasuredDimension(n <= 1 ? 0 : Theme.px(28 + (n - 1) * 18), Theme.px(8));
        }

        @Override
        protected void onDraw(Canvas c) {
            if (mN <= 1) return;
            float t = mStart == 0 ? 1f : Math.min(1f, (android.os.SystemClock.uptimeMillis() - mStart) / 250f);
            if (t < 1f) postInvalidateOnAnimation();
            else mStart = 0;
            float d = Theme.pxf(8), gap = Theme.pxf(10), wide = Theme.pxf(28);
            boolean rtl = Theme.rtl(this);
            float x = 0;
            for (int i = 0; i < mN; i++) {
                float wi = d;
                if (i == mI) wi = d + (wide - d) * t;
                else if (i == mPrevI && t < 1f) wi = d + (wide - d) * (1f - t);
                mPaint.setColor(i == mI ? 0xFFFFFFFF : 0x4DFFFFFF);
                float lx = rtl ? getWidth() - x - wi : x;
                mR.set(lx, 0, lx + wi, d);
                c.drawRoundRect(mR, d / 2, d / 2, mPaint);
                x += wi + gap;
            }
        }
    }
}
