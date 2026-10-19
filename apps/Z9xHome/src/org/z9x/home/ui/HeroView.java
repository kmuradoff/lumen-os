package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.img.ImageLoader;

/**
 * The info block of Home (Lumen OS 1.0.5, the owner's mockup design/home_v2; it replaces direction D's
 * hero carousel with its buttons and page dots): on the start side over the backdrop art, always about
 * the focused card. An eyebrow "ROW · APP" (Continue watching: the kind, "CONTINUE", in the accent, then
 * the app), the title in Prata (92 px, at most two lines), for a started program the meta line (season
 * and episode, or the year and length) with the progress bar and the time left, the description (three
 * lines at most) and a small hint: OK watches, holding OK opens the details. The block ends above the
 * focused row (the page's row line) and grows upwards; when it would reach the header (y 150) the
 * description gets fewer lines, then the hint goes. The page fades it out when the focus moves and
 * shows the newly focused card after a short dwell ({@link #show}); the art itself is drawn by
 * {@link Stage} behind the whole screen ({@link Host#onHeroShown}).
 */
public class HeroView extends ViewGroup {
    public interface Host {
        ImageLoader images();

        /** OK (primary) or "More info" on a program the info block is about. */
        void onHeroAction(Card k, boolean primary);

        /** The info block now describes {@code k}: its art goes behind the screen. */
        void onHeroShown(Card k);
    }

    private static final int TEXT_W = 900, DESC_W = 820;
    private static final int TEXT_TOP = 150;     // under the header (tabs end at y 122, the clock block at 138)
    private static final int ABOVE_ROW = 44;     // the block's bottom above the focused row's heading
    private static final int DESC_LINES = 3;
    public static final long FADE_OUT_MS = 180, FADE_IN_MS = 350;

    private final LinearLayout mText;
    private final TextView mEyebrow, mTitle, mMeta, mLeft, mDesc;
    private final View mMetaRow;
    private final ProgressLine mProgress;
    private final LinearLayout mHint;
    private final TextView mHintOk;
    private Card mCard;
    private boolean mShown;
    private int mRowY = Theme.px(Theme.FIRST_ROW_Y);

    public HeroView(Context c, Host host) {
        super(c);
        setClipChildren(false);
        mText = new LinearLayout(c);
        mText.setOrientation(LinearLayout.VERTICAL);
        mText.setClipChildren(false);
        mText.setAlpha(0f);
        mEyebrow = new TextView(c);
        Theme.text(mEyebrow, 22, Theme.SEMIBOLD, Theme.TEXT2);
        mEyebrow.setLetterSpacing(0.18f);
        mEyebrow.setSingleLine(true);
        mEyebrow.setEllipsize(TextUtils.TruncateAt.END);
        mText.addView(mEyebrow, new LinearLayout.LayoutParams(-1, -2));
        mTitle = new TextView(c);
        Theme.text(mTitle, 92, Theme.DISPLAY, Theme.TEXT1);
        mTitle.setLetterSpacing(-0.01f);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setLineSpacing(0f, 0.92f);
        // the mockup's soft shadow (0 4px 40px, black 50 %): the title's end over bright art
        mTitle.setShadowLayer(Theme.pxf(20), 0f, Theme.pxf(4), 0x80000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = Theme.px(18);
        mText.addView(mTitle, tl);
        LinearLayout metaRow = new LinearLayout(c);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        mMeta = new TextView(c);
        Theme.text(mMeta, 26, Theme.REGULAR, Theme.TEXT2);
        mMeta.setSingleLine(true);
        mMeta.setEllipsize(TextUtils.TruncateAt.END);
        metaRow.addView(mMeta, new LinearLayout.LayoutParams(-2, -2));
        mProgress = new ProgressLine(c);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Theme.px(220), Theme.px(6));
        pl.setMarginStart(Theme.px(18));
        metaRow.addView(mProgress, pl);
        mLeft = new TextView(c);
        Theme.text(mLeft, 26, Theme.REGULAR, Theme.TEXT2);
        mLeft.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.setMarginStart(Theme.px(18));
        metaRow.addView(mLeft, ll);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = Theme.px(22);
        mText.addView(metaRow, ml);
        mMetaRow = metaRow;
        mDesc = new TextView(c);
        Theme.text(mDesc, 28, Theme.REGULAR, Theme.TEXT2);
        mDesc.setMaxLines(DESC_LINES);
        mDesc.setEllipsize(TextUtils.TruncateAt.END);
        mDesc.setLineSpacing(0f, 1.3f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Theme.px(DESC_W), -2);
        dl.topMargin = Theme.px(18);
        mText.addView(mDesc, dl);
        // hint: [OK] watch   [hold OK] more info
        mHint = new LinearLayout(c);
        mHint.setOrientation(LinearLayout.HORIZONTAL);
        mHint.setGravity(Gravity.CENTER_VERTICAL);
        mHint.addView(kbd(c, "OK"));
        mHintOk = hintText(c);
        mHint.addView(mHintOk, hintLp(10, 34));
        mHint.addView(kbd(c, c.getString(R.string.hint_hold_ok)));
        TextView more = hintText(c);
        more.setText(c.getString(R.string.hero_more));
        mHint.addView(more, hintLp(10, 0));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = Theme.px(26);
        mText.addView(mHint, hl);
        addView(mText);
    }

    private static TextView kbd(Context c, String s) {
        TextView t = new TextView(c);
        Theme.text(t, 20, Theme.MEDIUM, Theme.TEXT2);
        t.setSingleLine(true);
        t.setText(s);
        t.setPadding(Theme.px(10), Theme.px(3), Theme.px(10), Theme.px(3));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Theme.pxf(8));
        bg.setStroke(Math.max(1, Theme.px(1.5f)), Theme.alpha(Theme.TEXT1, 0.3f));
        t.setBackground(bg);
        return t;
    }

    private static TextView hintText(Context c) {
        TextView t = new TextView(c);
        Theme.text(t, 22, Theme.REGULAR, Theme.TEXT3);
        t.setSingleLine(true);
        return t;
    }

    private static LinearLayout.LayoutParams hintLp(int start, int end) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(Theme.px(start));
        lp.setMarginEnd(Theme.px(end));
        return lp;
    }

    // ------------------------------------------------------------------ content

    /** The card the block describes (also while faded out for a moment), or null. */
    public Card current() {
        return mCard;
    }

    public boolean shown() {
        return mShown;
    }

    /**
     * Describes {@code k} and fades in. {@code rowTitle} is the title of its row (null for Continue
     * watching: the kind of the item instead).
     */
    public void show(Card k, String rowTitle, boolean animate) {
        if (k == null) return;
        mCard = k;
        mShown = true;
        bind(k, rowTitle);
        mText.animate().cancel();
        if (animate && Theme.animations()) {
            mText.animate().alpha(1f).setDuration(FADE_IN_MS).setInterpolator(Theme.DECEL).withLayer().start();
        } else {
            mText.setAlpha(1f);
        }
    }

    /** Fades out (the focus moved; the next card follows after the dwell, or none: an app, a tile). */
    public void fadeOut(boolean animate) {
        mShown = false;
        mText.animate().cancel();
        if (animate && Theme.animations() && mText.getAlpha() > 0f) {
            mText.animate().alpha(0f).setDuration(FADE_OUT_MS).setInterpolator(Theme.LINEAR).withLayer().start();
        } else {
            mText.setAlpha(0f);
        }
    }

    /** Nothing to describe any more (the model changed under it). */
    public void clear() {
        fadeOut(false);
        mCard = null;
    }

    private void bind(Card k, String rowTitle) {
        Context c = getContext();
        mEyebrow.setText(eyebrow(c, k, rowTitle));
        mTitle.setText(k.title);
        mMeta.setText(k.meta);
        mMeta.setVisibility(k.meta.isEmpty() ? GONE : VISIBLE);
        mProgress.set(k.progress);
        mProgress.setVisibility(k.progress >= 0 ? VISIBLE : GONE);
        ((LinearLayout.LayoutParams) mProgress.getLayoutParams()).setMarginStart(k.meta.isEmpty() ? 0 : Theme.px(18));
        mLeft.setText(k.left);
        mLeft.setVisibility(k.left.isEmpty() ? GONE : VISIBLE);
        boolean meta = !k.meta.isEmpty() || k.progress >= 0 || !k.left.isEmpty();
        mMetaRow.setVisibility(meta ? VISIBLE : GONE);
        mDesc.setText(k.desc);
        mDesc.setMaxLines(DESC_LINES);
        mDesc.setVisibility(k.desc.isEmpty() ? GONE : VISIBLE);
        mHint.setVisibility(VISIBLE);
        boolean cont = k.table == Card.T_WATCH_NEXT && (k.progress >= 0 || k.wnType == 0);
        boolean playable = k.intent != null && !k.intent.isEmpty();
        mHintOk.setText(c.getString(cont ? R.string.hero_resume : (playable ? R.string.hero_watch : R.string.hero_open)));
        setContentDescription(k.title);
        requestLayout();
    }

    /**
     * "CONTINUE · КИНОПОИСК" (the Watch Next kind in the accent, then the app) or "ROW · APP" (the
     * channel's row title, then the app when the row is not simply named after it).
     */
    private static CharSequence eyebrow(Context c, Card k, String rowTitle) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String app = Theme.upper(k.appLabel);
        if (k.table == Card.T_WATCH_NEXT) {
            String kind;
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
            sb.append(Theme.upper(kind));
            sb.setSpan(new ForegroundColorSpan(Theme.ACCENT), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!app.isEmpty()) sb.append("  ·  ").append(app);
            return sb;
        }
        String row = rowTitle == null ? "" : Theme.upper(rowTitle);
        if (!row.isEmpty()) sb.append(row);
        if (!app.isEmpty() && !app.equals(row)) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(app);
        }
        return sb;
    }

    /** Top of the focused row (real px): the block ends above it. Set by the page right before measuring. */
    public void setRowY(int px) {
        mRowY = px;
    }

    // ------------------------------------------------------------------ layout (design px, mirrored in RTL)

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        int tws = MeasureSpec.makeMeasureSpec(Theme.px(TEXT_W), MeasureSpec.EXACTLY);
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        mText.measure(tws, un);
        // too tall for the room above the row: fewer description lines, then no hint, then no description
        int avail = mRowY - Theme.px(ABOVE_ROW + TEXT_TOP);
        while (mText.getMeasuredHeight() > avail && mDesc.getVisibility() == VISIBLE && mDesc.getMaxLines() > 1) {
            mDesc.setMaxLines(mDesc.getMaxLines() - 1);
            mText.measure(tws, un);
        }
        if (mText.getMeasuredHeight() > avail && mHint.getVisibility() == VISIBLE) {
            mHint.setVisibility(GONE);
            mText.measure(tws, un);
        }
        if (mText.getMeasuredHeight() > avail && mDesc.getVisibility() == VISIBLE) {
            mDesc.setVisibility(GONE);
            mText.measure(tws, un);
        }
        setMeasuredDimension(w, mRowY);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int top = Math.max(Theme.px(TEXT_TOP), mRowY - Theme.px(ABOVE_ROW) - mText.getMeasuredHeight());
        int x = Theme.px(Theme.MARGIN), tw = mText.getMeasuredWidth();
        int lx = Theme.rtl(this) ? w - x - tw : x;
        mText.layout(lx, top, lx + tw, top + mText.getMeasuredHeight());
    }

    /** Progress bar of the meta line: paper 20 % track, accent fill. */
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
            mPaint.setColor(Theme.alpha(Theme.TEXT1, 0.2f));
            mR.set(0, 0, w, h);
            c.drawRoundRect(mR, h / 2, h / 2, mPaint);
            mPaint.setColor(Theme.ACCENT);
            boolean rtl = Theme.rtl(this);
            float pw = w * mP / 1000f;
            mR.set(rtl ? w - pw : 0, 0, rtl ? w : pw, h);
            c.drawRoundRect(mR, h / 2, h / 2, mPaint);
        }
    }
}
