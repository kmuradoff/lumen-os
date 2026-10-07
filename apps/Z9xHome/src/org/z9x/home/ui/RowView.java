package org.z9x.home.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.z9x.home.data.Card;
import org.z9x.home.data.Row;
import org.z9x.home.img.ImageLoader;

import java.util.ArrayList;

/**
 * One horizontal row (SPEC 7.5/7.6): title, a track of cards scrolled by translationX only, and the
 * caption (title + meta) under the focused card. Left-aligned focus: the focused card stays at the
 * left content edge while the row can scroll; the row's end aligns its last card to the right edge.
 * Focus is managed by the page (no Android focus search); nothing requests layout while navigating.
 */
public class RowView extends ViewGroup {
    public interface Host {
        ImageLoader images();

        /** Focus moved inside this row (for the ambient backdrop and the page). */
        void onRowFocus(RowView row, Card k);

        void onMoveDone(RowView row);
    }

    private final Host mHost;
    private final TextView mTitle;
    private final Track mTrack;
    private final TextView mCapTitle, mCapMeta;
    private final ArrayList<CardView> mCards = new ArrayList<>();
    private Row mRow;
    private int mFocus;
    private boolean mActive, mNear, mBuilt, mMoving;
    private int mCw, mCh, mGap, mMargin;
    private long mLastRepeat;
    private final Runnable mLoadVisible = this::loadVisible;
    private boolean mShowTitle = true;

    public RowView(Context c, Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        setClipToPadding(false);
        mMargin = Theme.px(Theme.MARGIN);
        mGap = Theme.px(Theme.GAP);
        mTitle = new TextView(c);
        Theme.text(mTitle, 30, Theme.MEDIUM, (Theme.TEXT1 & 0x00FFFFFF) | 0xE0000000);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        addView(mTitle);
        mTrack = new Track(c);
        addView(mTrack);
        mCapTitle = new TextView(c);
        Theme.text(mCapTitle, 26, Theme.MEDIUM, Theme.TEXT1);
        mCapTitle.setSingleLine(true);
        mCapTitle.setEllipsize(TextUtils.TruncateAt.END);
        mCapMeta = new TextView(c);
        Theme.text(mCapMeta, 22, Theme.REGULAR, Theme.TEXT2);
        mCapMeta.setSingleLine(true);
        mCapMeta.setEllipsize(TextUtils.TruncateAt.END);
        mCapTitle.setAlpha(0);
        mCapMeta.setAlpha(0);
        addView(mCapTitle);
        addView(mCapMeta);
    }

    public Row row() {
        return mRow;
    }

    public void setShowTitle(boolean show) {
        mShowTitle = show;
        mTitle.setVisibility(show ? VISIBLE : GONE);
    }

    /** Height of this row in real px: title, gap, cards, caption space. */
    public int rowHeight() {
        return (mShowTitle ? Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP) : 0) + mCh + Theme.px(Theme.ROW_CAPTION_H);
    }

    public int cardTop() {
        return mShowTitle ? Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP) : 0;
    }

    public int cardHeight() {
        return mCh;
    }

    /** Binds (or re-binds after a refresh) keeping the focused card by id. */
    public void setRow(Row r, boolean build) {
        String focusedId = mRow != null && mFocus < mRow.cards.size() ? mRow.cards.get(mFocus).id : null;
        boolean sizeChanged = mRow == null || sizeKind(mRow) != sizeKind(r) || mRow.aspect != r.aspect;
        mRow = r;
        int kind = r.cards.isEmpty() ? Card.APP : r.cards.get(0).kind;
        mCw = Theme.px(Theme.cardW(kind, r.aspect));
        mCh = Theme.px(Theme.cardH(kind, r.aspect));
        CharSequence t = r.title;
        if (!r.sub.isEmpty()) {
            SpannableStringBuilder sb = new SpannableStringBuilder(r.title);
            int s = sb.length();
            sb.append("  ·  ").append(r.sub);
            sb.setSpan(new ForegroundColorSpan(Theme.TEXT3), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t = sb;
        }
        mTitle.setText(t);
        int nf = 0;
        if (focusedId != null) {
            for (int i = 0; i < r.cards.size(); i++) {
                if (r.cards.get(i).id.equals(focusedId)) {
                    nf = i;
                    break;
                }
            }
            if (nf == 0 && mFocus < r.cards.size() && !r.cards.isEmpty() && !r.cards.get(0).id.equals(focusedId)) {
                nf = Math.min(mFocus, r.cards.size() - 1);
            }
        }
        mFocus = Math.max(0, Math.min(nf, r.cards.size() - 1));
        if (sizeChanged && mBuilt) {
            for (CardView v : mCards) v.dropArt();
            mTrack.removeAllViews();
            mCards.clear();
            mBuilt = false;
        }
        if (build || mBuilt || mNear) buildCards();
        if (sizeChanged) requestLayout();
    }

    private static int sizeKind(Row r) {
        return r.cards.isEmpty() ? 0 : r.cards.get(0).kind;
    }

    private void buildCards() {
        mBuilt = true;
        Context c = getContext();
        int n = mRow.cards.size();
        while (mCards.size() > n) {
            CardView v = mCards.remove(mCards.size() - 1);
            v.dropArt();
            mTrack.removeView(v);
        }
        for (int i = 0; i < n; i++) {
            CardView v;
            if (i < mCards.size()) {
                v = mCards.get(i);
            } else {
                v = new CardView(c);
                mCards.add(v);
                mTrack.addView(v);
            }
            v.bind(mRow.cards.get(i), mCw, mCh);
            v.setOutlined(mRow.cards.get(i).showing);
            v.setFocusState(mActive && i == mFocus, false, isAtLeftEdge(i));
        }
        mTrack.requestLayout();
        applyScroll(false, false);
        if (mNear) loadVisible();
        if (mActive) showCaption(false);
    }

    // ------------------------------------------------------------------ near / art

    /** Rows near the viewport keep art; far rows release it (it stays in the memory LRU). */
    public void setNear(boolean near) {
        if (mNear == near) return;
        mNear = near;
        if (near) {
            if (!mBuilt) buildCards();
            else loadVisible();
        } else {
            for (CardView v : mCards) v.dropArt();
        }
    }

    private void loadVisible() {
        if (!mNear || !mBuilt) return;
        int first = Math.max(0, firstVisible() - 1);
        int last = Math.min(mCards.size() - 1, first + visibleCount() + 2);
        ImageLoader il = mHost.images();
        // focused card first, then outwards
        if (mFocus >= first && mFocus <= last) mCards.get(mFocus).loadArt(il, false);
        for (int i = first; i <= last; i++) mCards.get(i).loadArt(il, false);
        for (int i = 0; i < mCards.size(); i++) {
            if (i < first - 2 || i > last + 2) mCards.get(i).dropArt();
        }
    }

    public boolean artReady() {
        if (!mBuilt) return false;
        int first = firstVisible(), last = Math.min(mCards.size() - 1, first + visibleCount());
        for (int i = first; i <= last; i++) if (!mCards.get(i).hasArt()) return false;
        return true;
    }

    private int visibleCount() {
        return (getResources().getDisplayMetrics().widthPixels - mMargin) / Math.max(1, mCw + mGap) + 1;
    }

    private int firstVisible() {
        return Math.max(0, (int) Math.floor(scrollPx() / (float) (mCw + mGap)));
    }

    // ------------------------------------------------------------------ focus

    public boolean isEmpty() {
        return mRow == null || mRow.cards.isEmpty();
    }

    public int focusIndex() {
        return mFocus;
    }

    public CardView focusedView() {
        return mFocus < mCards.size() ? mCards.get(mFocus) : null;
    }

    public Card focusedCard() {
        return mRow != null && mFocus < mRow.cards.size() ? mRow.cards.get(mFocus) : null;
    }

    public void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        if (!mBuilt) buildCards();
        CardView v = focusedView();
        if (v != null) v.setFocusState(active, true, isAtLeftEdge(mFocus));
        if (active) {
            showCaption(true);
            mHost.onRowFocus(this, focusedCard());
        } else {
            hideCaption();
            if (mMoving) endMove();
        }
    }

    public void focusIndex(int i) {
        if (mRow == null || mRow.cards.isEmpty()) return;
        i = Math.max(0, Math.min(i, mRow.cards.size() - 1));
        if (i == mFocus) return;
        moveTo(i, false);
    }

    /** LEFT/RIGHT. Returns false at the row's edge. */
    public boolean onKey(int keyCode, KeyEvent e) {
        if (mRow == null || mRow.cards.isEmpty()) return false;
        boolean rtl = Theme.rtl(this);
        int dir;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dir = rtl ? -1 : 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dir = rtl ? 1 : -1;
        else return false;
        int to = mFocus + dir;
        if (to < 0 || to >= mRow.cards.size()) return false;
        boolean repeat = e.getRepeatCount() > 0;
        if (mMoving) {
            swap(mFocus, to);
            return true;
        }
        moveTo(to, repeat);
        return true;
    }

    private void moveTo(int to, boolean repeat) {
        int from = mFocus;
        mFocus = to;
        if (!mBuilt) buildCards();
        long now = android.os.SystemClock.uptimeMillis();
        boolean fast = repeat || now - mLastRepeat < 120;
        if (repeat) mLastRepeat = now;
        if (from < mCards.size()) mCards.get(from).setFocusState(false, !fast, isAtLeftEdge(from));
        applyScroll(true, fast);
        if (mActive) {
            mCards.get(to).setFocusState(true, !fast, isAtLeftEdge(to));
            showCaption(!fast);
            mHost.onRowFocus(this, focusedCard());
        }
        removeCallbacks(mLoadVisible);
        if (fast) postDelayed(mLoadVisible, 120);
        else loadVisible();
    }

    private boolean isAtLeftEdge(int i) {
        return Math.abs(i * (mCw + mGap) - scrollPxFor(mFocus)) < 2 && i == mFocus;
    }

    private int totalWidth() {
        int n = mRow == null ? 0 : mRow.cards.size();
        return n == 0 ? 0 : n * mCw + (n - 1) * mGap;
    }

    private int scrollPxFor(int focus) {
        int content = getResources().getDisplayMetrics().widthPixels - 2 * mMargin;
        int max = Math.max(0, totalWidth() - content);
        return Math.min(focus * (mCw + mGap), max);
    }

    private int scrollPx() {
        return scrollPxFor(mFocus);
    }

    private void applyScroll(boolean animate, boolean fast) {
        float tx = Theme.rtl(this) ? scrollPx() : -scrollPx();
        mTrack.animate().cancel();
        if (animate && mTrack.getTranslationX() != tx) {
            mTrack.animate().translationX(tx).setDuration(fast ? Theme.ROW_REPEAT_MS : Theme.ROW_SCROLL_MS)
                    .setInterpolator(fast ? Theme.LINEAR : Theme.EMPHASIZED).start();
        } else {
            mTrack.setTranslationX(tx);
        }
    }

    // ------------------------------------------------------------------ caption

    private void showCaption(boolean animate) {
        Card k = focusedCard();
        if (k == null || !(k.kind == Card.PROGRAM || k.kind == Card.APP)) {
            hideCaption();
            return;
        }
        mCapTitle.setText(k.title);
        mCapMeta.setText(k.kind == Card.PROGRAM ? (k.meta.isEmpty() ? k.appLabel : k.meta) : "");
        float off = mMargin + mFocus * (mCw + mGap) - scrollPx();
        float tx = Theme.rtl(this) ? -off : off;
        mCapTitle.setTranslationX(tx);
        mCapMeta.setTranslationX(tx);
        mCapTitle.animate().cancel();
        mCapMeta.animate().cancel();
        if (animate) {
            mCapTitle.setAlpha(0);
            mCapMeta.setAlpha(0);
            mCapTitle.animate().alpha(1).setStartDelay(60).setDuration(120).start();
            mCapMeta.animate().alpha(1).setStartDelay(60).setDuration(120).start();
        } else {
            mCapTitle.setAlpha(1);
            mCapMeta.setAlpha(1);
        }
    }

    private void hideCaption() {
        mCapTitle.animate().cancel();
        mCapMeta.animate().cancel();
        mCapTitle.setAlpha(0);
        mCapMeta.setAlpha(0);
    }

    // ------------------------------------------------------------------ move mode (Your apps)

    public boolean moving() {
        return mMoving;
    }

    public void startMove() {
        if (!mActive || mRow == null || mRow.cards.size() < 2) return;
        mMoving = true;
        CardView v = focusedView();
        if (v != null) v.setMoving(true);
    }

    public void endMove() {
        if (!mMoving) return;
        mMoving = false;
        for (CardView v : mCards) v.setMoving(false);
        mHost.onMoveDone(this);
    }

    private void swap(int a, int b) {
        java.util.Collections.swap(mRow.cards, a, b);
        java.util.Collections.swap(mCards, a, b);
        mCards.get(a).setFocusState(false, false, false);
        mCards.get(a).setMoving(false);
        mFocus = b;
        mTrack.requestLayout();
        applyScroll(true, false);
        mCards.get(b).setFocusState(true, false, isAtLeftEdge(b));
        mCards.get(b).setMoving(true);
        showCaption(false);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        int tw = w - 2 * mMargin;
        mTitle.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Theme.px(Theme.ROW_TITLE_H), MeasureSpec.EXACTLY));
        mTrack.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(mCh, MeasureSpec.EXACTLY));
        int cap = MeasureSpec.makeMeasureSpec(Math.max(mCw, Theme.px(560)), MeasureSpec.AT_MOST);
        mCapTitle.measure(cap, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        mCapMeta.measure(cap, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(w, rowHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        boolean rtl = Theme.rtl(this);
        int tx = rtl ? w - mMargin - mTitle.getMeasuredWidth() : mMargin;
        mTitle.layout(tx, 0, tx + mTitle.getMeasuredWidth(), mTitle.getMeasuredHeight());
        int top = cardTop();
        mTrack.layout(0, top, w, top + mCh);
        int capY = top + mCh + Math.round(mCh * (Theme.FOCUS_SCALE - 1f) / 2f) + Theme.px(14);
        mCapTitle.layout(0, capY, mCapTitle.getMeasuredWidth(), capY + mCapTitle.getMeasuredHeight());
        int my = capY + mCapTitle.getMeasuredHeight() + Theme.px(4);
        mCapMeta.layout(0, my, mCapMeta.getMeasuredWidth(), my + mCapMeta.getMeasuredHeight());
        if (rtl) {
            mCapTitle.layout(w - mCapTitle.getMeasuredWidth(), capY, w, capY + mCapTitle.getMeasuredHeight());
            mCapMeta.layout(w - mCapMeta.getMeasuredWidth(), my, w, my + mCapMeta.getMeasuredHeight());
        }
        if (mActive) showCaption(false);
    }

    /** Lays out the cards at fixed x positions once; scrolling only translates this view. */
    private final class Track extends ViewGroup {
        Track(Context c) {
            super(c);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int ws = MeasureSpec.makeMeasureSpec(mCw, MeasureSpec.EXACTLY);
            int hs = MeasureSpec.makeMeasureSpec(mCh, MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(ws, hs);
            setMeasuredDimension(MeasureSpec.getSize(wms), mCh);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            boolean rtl = Theme.rtl(this);
            for (int i = 0; i < mCards.size(); i++) {
                View v = mCards.get(i);
                int x = mMargin + i * (mCw + mGap);
                if (rtl) x = w - x - mCw;
                v.layout(x, 0, x + mCw, mCh);
            }
        }
    }
}
