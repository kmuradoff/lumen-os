package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.img.ImageLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hero of direction D (D_Home): over the stage art (or the living sky) at x 96, an overline
 * "YOUTUBE · CONTINUE", the title in Prata (132 px, 104 px when it needs two lines), the meta line with
 * the progress bar and the time left, two lines of description, and the buttons "Continue" (launches the
 * program's own intent) and "More info" (details panel). The buttons sit 184 px above the first row (y 556
 * over a row at 740; D_Home: 606 over 790, moved up with the row for the TV safe area) and the text grows
 * upwards, so a long title never pushes them into the row below. LEFT past the first / RIGHT past the
 * last button switches slides; dots show when there is more than one. The art itself is drawn by
 * {@link Stage} behind the whole screen ({@link Host#onHeroShown}).
 */
public class HeroView extends ViewGroup {
    public interface Host {
        ImageLoader images();

        void onHeroAction(Card k, boolean primary);

        void onHeroShown(Card k);
    }

    private static final int TEXT_W = 900;
    /** The buttons' distance above the first row (D_Home: 790 - 606), so the dots keep their gap. */
    private static final int BUTTONS_ABOVE_ROW = 184;
    private static final int TEXT_TOP = 150; // under the header (tabs end at y 122, the clock block at 138)
    private static final int OVERLINE_COLOR = 0xFFDCD4C6;
    private static final int META_COLOR = 0xFFCFC7BA;
    private static final int DESC_COLOR = 0xFFE6DFD3;

    private final Host mHost;
    private final LinearLayout mText;
    private final TextView mOverline, mTitle, mMeta, mLeft, mDesc;
    private final ProgressLine mProgress;
    private final PillButton mPrimary, mSecondary;
    private final Dots mDots;
    private final ArrayList<Card> mSlides = new ArrayList<>();
    private Map<Long, String> mChannelNames;
    private Card mTransient;   // a "Continue watching" card focused in the row, not one of the slides
    private int mIndex;
    private int mFocusBtn = -1;
    private int mRowY = Theme.px(Theme.FIRST_ROW_Y);
    private final float mTitleBig, mTitleSmall;

    public HeroView(Context c, Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mText = new LinearLayout(c);
        mText.setOrientation(LinearLayout.VERTICAL);
        mText.setClipChildren(false);
        mOverline = new TextView(c);
        Theme.text(mOverline, 21, Theme.REGULAR, OVERLINE_COLOR);
        mOverline.setLetterSpacing(0.06f);
        mOverline.setSingleLine(true);
        mOverline.setEllipsize(TextUtils.TruncateAt.END);
        mText.addView(mOverline, new LinearLayout.LayoutParams(-1, -2));
        mTitle = new TextView(c);
        mTitleBig = Theme.pxf(132) * Theme.fontScale(c);
        mTitleSmall = Theme.pxf(104) * Theme.fontScale(c);
        Theme.text(mTitle, 132, Theme.DISPLAY, Theme.TEXT1);
        mTitle.setLetterSpacing(-0.015f);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setLineSpacing(0f, 0.95f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Theme.px(20);
        mText.addView(mTitle, tl);
        LinearLayout metaRow = new LinearLayout(c);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mMeta = new TextView(c);
        Theme.text(mMeta, 24, Theme.REGULAR, META_COLOR);
        mMeta.setSingleLine(true);
        mMeta.setEllipsize(TextUtils.TruncateAt.END);
        metaRow.addView(mMeta, new LinearLayout.LayoutParams(-2, -2));
        mProgress = new ProgressLine(c);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Theme.px(160), Theme.px(5));
        pl.setMarginStart(Theme.px(20));
        metaRow.addView(mProgress, pl);
        mLeft = new TextView(c);
        Theme.text(mLeft, 24, Theme.REGULAR, META_COLOR);
        mLeft.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.setMarginStart(Theme.px(12));
        metaRow.addView(mLeft, ll);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = Theme.px(22);
        mText.addView(metaRow, ml);
        mDesc = new TextView(c);
        Theme.text(mDesc, 28, Theme.REGULAR, DESC_COLOR);
        mDesc.setMaxLines(2);
        mDesc.setEllipsize(TextUtils.TruncateAt.END);
        mDesc.setLineSpacing(0f, 1.32f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Theme.px(760), -2);
        dl.topMargin = Theme.px(22);
        mText.addView(mDesc, dl);
        addView(mText);

        mPrimary = new PillButton(c, 76, 27, PillButton.STYLE_FILLED).icon(R.drawable.ic_play).padding(32, 40).glow(true);
        mSecondary = new PillButton(c, 76, 27, PillButton.STYLE_FILLED).padding(36, 36).glow(true);
        mSecondary.label(c.getString(R.string.hero_more));
        mPrimary.maxWidth(Theme.px(560));
        mSecondary.maxWidth(Theme.px(420));
        addView(mPrimary);
        addView(mSecondary);
        mDots = new Dots(c);
        addView(mDots);
    }

    // ------------------------------------------------------------------ slides

    /** @param channelNames channel id -> row title, for the overline of preview programs */
    public void setSlides(List<Card> slides, Map<Long, String> channelNames) {
        Card cur = current();
        mChannelNames = channelNames;
        mSlides.clear();
        mSlides.addAll(slides);
        if (mTransient != null) {
            for (Card k : slides) if (k.id.equals(mTransient.id)) mTransient = null;
            if (mTransient != null) { // the page re-applies it from the focused row card
                mIndex = Math.min(mIndex, Math.max(0, mSlides.size() - 1));
                return;
            }
        }
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
        if (mTransient != null) return mTransient;
        return mIndex < mSlides.size() ? mSlides.get(mIndex) : null;
    }

    public int count() {
        return mSlides.size();
    }

    public void next(boolean animate) {
        if (mSlides.size() < 2 && mTransient == null) return;
        if (mTransient == null) mIndex = (mIndex + 1) % mSlides.size();
        mTransient = null;
        show(animate);
    }

    public void prev(boolean animate) {
        if (mSlides.size() < 2 && mTransient == null) return;
        if (mTransient == null) mIndex = (mIndex - 1 + mSlides.size()) % mSlides.size();
        mTransient = null;
        show(animate);
    }

    /**
     * The hero follows the focused "Continue watching" card: one of the slides, or the card itself
     * shown in its place (dots hidden) until the slides move on.
     */
    public void showCard(Card k, boolean animate) {
        if (k == null) return;
        Card cur = current();
        if (cur != null && cur.id.equals(k.id)) return;
        for (int i = 0; i < mSlides.size(); i++) {
            if (mSlides.get(i).id.equals(k.id)) {
                mTransient = null;
                mIndex = i;
                show(animate);
                return;
            }
        }
        mTransient = k;
        show(animate);
    }

    public Card transientCard() {
        return mTransient;
    }

    /** The followed card left "Continue watching" (removed, finished): back to the slides. */
    public void dropTransient() {
        if (mTransient == null) return;
        mTransient = null;
        show(false);
    }

    private void show(boolean animate) {
        Card k = current();
        mDots.set(mTransient != null ? 0 : mSlides.size(), mIndex);
        if (k == null) return;
        if (animate && Theme.animations()) {
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
        mHost.onHeroShown(k);
    }

    private void bindText(Card k) {
        Context c = getContext();
        mOverline.setText(overline(c, k));
        mTitle.setText(k.title);
        fitTitle();
        mMeta.setText(k.meta);
        mMeta.setVisibility(k.meta.isEmpty() ? GONE : VISIBLE);
        mProgress.set(k.progress);
        mProgress.setVisibility(k.progress >= 0 ? VISIBLE : GONE);
        ((LinearLayout.LayoutParams) mProgress.getLayoutParams()).setMarginStart(k.meta.isEmpty() ? 0 : Theme.px(20));
        mLeft.setText(k.left);
        mLeft.setVisibility(k.left.isEmpty() ? GONE : VISIBLE);
        mDesc.setText(k.desc);
        mDesc.setMaxLines(2);
        mDesc.setVisibility(k.desc.isEmpty() ? GONE : VISIBLE);
        boolean cont = k.table == Card.T_WATCH_NEXT && (k.progress >= 0 || k.wnType == 0);
        boolean playable = k.intent != null && !k.intent.isEmpty();
        mPrimary.icon(playable ? R.drawable.ic_play : R.drawable.ic_open)
                .label(c.getString(cont ? R.string.hero_resume : (playable ? R.string.hero_watch : R.string.hero_open)));
        mPrimary.setContentDescription(mPrimary.label() + ", " + k.title);
        requestLayout();
    }

    /** "YOUTUBE · CONTINUE": the app in semibold, then what this item is (Watch Next type or channel). */
    private CharSequence overline(Context c, Card k) {
        String kind = "";
        if (k.table == Card.T_WATCH_NEXT) {
            switch (k.wnType) {
                case 1: // WATCH_NEXT_TYPE_NEXT
                    kind = c.getString(R.string.hero_kind_next);
                    break;
                case 2: // WATCH_NEXT_TYPE_NEW
                    kind = c.getString(R.string.badge_new);
                    break;
                case 3: // WATCH_NEXT_TYPE_WATCHLIST
                    kind = c.getString(R.string.hero_kind_watchlist);
                    break;
                default:
                    kind = c.getString(R.string.hero_resume);
            }
        } else if (mChannelNames != null && k.channelId >= 0) {
            String ch = mChannelNames.get(k.channelId);
            if (ch != null && !ch.equals(k.appLabel)) kind = ch;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(Theme.upper(k.appLabel));
        sb.setSpan(new TypefaceSpan(Theme.SEMIBOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!kind.isEmpty()) {
            int s = sb.length();
            sb.append("  ·  ");
            sb.setSpan(new ForegroundColorSpan(0xFF8F877A), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(Theme.upper(kind));
        }
        return sb;
    }

    /** 132 px on one line; a title that needs two lines drops to 104 px (still max two lines). */
    private void fitTitle() {
        float avail = Theme.pxf(TEXT_W);
        mTitle.getPaint().setTextSize(mTitleBig);
        boolean one = mTitle.getPaint().measureText(mTitle.getText().toString()) <= avail;
        mTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, one ? mTitleBig : mTitleSmall);
    }

    public boolean artReady() {
        return true;
    }

    /** Top of the first row below (real px); measured before this view by the page. */
    public void setRowY(int px) {
        mRowY = px;
    }

    private int buttonsY() {
        return mRowY - Theme.px(BUTTONS_ABOVE_ROW);
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
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                boolean fwd = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl;
                if (fwd) {
                    if (mFocusBtn == 0) setFocusButton(1);
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
        int tws = MeasureSpec.makeMeasureSpec(Theme.px(TEXT_W), MeasureSpec.EXACTLY);
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        mText.measure(tws, un);
        // a two-line title leaves room for one line of description, or none (the buttons never move)
        int avail = buttonsY() - Theme.px(34 + TEXT_TOP);
        if (mText.getMeasuredHeight() > avail && mDesc.getVisibility() == VISIBLE && mDesc.getMaxLines() > 1) {
            mDesc.setMaxLines(1);
            mText.measure(tws, un);
        }
        if (mText.getMeasuredHeight() > avail && mDesc.getVisibility() == VISIBLE) {
            mDesc.setVisibility(GONE);
            mText.measure(tws, un);
        }
        mPrimary.measure(un, un);
        mSecondary.measure(un, un);
        mDots.measure(un, un);
        setMeasuredDimension(w, mRowY - Theme.px(20));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        boolean rtl = Theme.rtl(this);
        int by = buttonsY();
        // the text block ends 34 px above the buttons and never starts above y 150 (header)
        int top = Math.max(Theme.px(TEXT_TOP), by - Theme.px(34) - mText.getMeasuredHeight());
        lay(mText, Theme.px(Theme.MARGIN), top, w, rtl);
        int x = Theme.px(Theme.MARGIN);
        lay(mPrimary, x, by, w, rtl);
        lay(mSecondary, x + mPrimary.getMeasuredWidth() + Theme.px(16), by, w, rtl);
        lay(mDots, x, by + mPrimary.getMeasuredHeight() + Theme.px(34), w, rtl);
    }

    private static void lay(View v, int x, int y, int w, boolean rtl) {
        int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
        int lx = rtl ? w - x - vw : x;
        v.layout(lx, y, lx + vw, y + vh);
    }

    /** Progress bar of the meta line: paper 22 % track, accent fill. */
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
            mPaint.setColor(Theme.alpha(Theme.TEXT1, 0.22f));
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
                mPaint.setColor(i == mI ? Theme.TEXT1 : Theme.alpha(Theme.TEXT1, 0.3f));
                float lx = rtl ? getWidth() - x - wi : x;
                mR.set(lx, 0, lx + wi, d);
                c.drawRoundRect(mR, d / 2, d / 2, mPaint);
                x += wi + gap;
            }
        }
    }
}
