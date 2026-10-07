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

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.InputSource;
import org.z9x.home.data.AppSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Apps" tab (SPEC 7.7): headline, a Favorites grid and an A-Z grid of 256x144 banners, 6 columns,
 * row pitch 144 + 64, the last tile "Get more apps". Move mode reorders favorites.
 */
public class PageApps extends ViewGroup implements Page {
    public interface Host extends Page.Host {
        void onFavoritesReordered(List<Card> favorites);
    }

    private static final int COLS = 6;
    private final Host mHost;
    private final ViewGroup mContent;
    private final TextView mHeadline, mFavTitle, mAllTitle, mCaption;
    private final ArrayList<CardView> mFav = new ArrayList<>();
    private final ArrayList<CardView> mAll = new ArrayList<>();
    private final ArrayList<Card> mFavCards = new ArrayList<>();
    private int mSec = 0;      // 0 favorites, 1 all
    private int mIdx = 0;
    private boolean mActive, mShown = true, mMoving;
    private final int mCw, mCh, mGap, mPitch, mMargin;

    public PageApps(Context c, Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mCw = Theme.px(256);
        mCh = Theme.px(144);
        mGap = Theme.px(Theme.GAP);
        mPitch = Theme.px(144 + 64);
        mMargin = Theme.px(Theme.MARGIN);
        mContent = new Content(c);
        addView(mContent);
        mHeadline = title(c, 44);
        mHeadline.setText(R.string.tab_apps);
        mFavTitle = title(c, 30);
        mFavTitle.setText(R.string.apps_favorites);
        mAllTitle = title(c, 30);
        SpannableStringBuilder sb = new SpannableStringBuilder(c.getString(R.string.apps_all));
        int s = sb.length();
        sb.append("  ·  ").append(c.getString(R.string.apps_az));
        sb.setSpan(new ForegroundColorSpan(Theme.TEXT3), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mAllTitle.setText(sb);
        mCaption = new TextView(c);
        Theme.text(mCaption, 26, Theme.MEDIUM, Theme.TEXT1);
        mCaption.setSingleLine(true);
        mCaption.setEllipsize(TextUtils.TruncateAt.END);
        mCaption.setAlpha(0);
        mContent.addView(mCaption);
    }

    private TextView title(Context c, float size) {
        TextView t = new TextView(c);
        Theme.text(t, size, Theme.MEDIUM, Theme.TEXT1);
        t.setSingleLine(true);
        mContent.addView(t);
        return t;
    }

    @Override
    public View view() {
        return this;
    }

    @Override
    public void bind(HomeModel m) {
        String fid = focusedCard() != null ? focusedCard().id : null;
        mFavCards.clear();
        mFavCards.addAll(m.favorites);
        ArrayList<Card> all = new ArrayList<>(m.apps);
        if (InputSource.installed(getContext(), AppSource.PKG_PLAY)) {
            Card more = new Card(Card.MORE_APPS, "more_apps", getContext().getString(R.string.apps_get_more));
            more.pkg = AppSource.PKG_PLAY;
            all.add(more);
        }
        sync(mFav, mFavCards);
        sync(mAll, all);
        mFavTitle.setVisibility(mFav.isEmpty() ? GONE : VISIBLE);
        if (mFav.isEmpty() && mSec == 0) mSec = 1;
        // keep focus on the same app
        if (fid != null) {
            for (int i = 0; i < mFav.size(); i++) if (mFav.get(i).card().id.equals(fid) && mSec == 0) mIdx = i;
            for (int i = 0; i < mAll.size(); i++) if (mAll.get(i).card().id.equals(fid) && mSec == 1) mIdx = i;
        }
        List<CardView> l = list(mSec);
        mIdx = Math.max(0, Math.min(mIdx, l.size() - 1));
        mContent.requestLayout();
        mContent.post(() -> {
            applyFocus(false);
            applyScroll(false);
            loadVisible();
        });
    }

    private void sync(ArrayList<CardView> views, List<Card> cards) {
        while (views.size() > cards.size()) {
            CardView v = views.remove(views.size() - 1);
            v.dropArt();
            mContent.removeView(v);
        }
        for (int i = 0; i < cards.size(); i++) {
            CardView v;
            if (i < views.size()) v = views.get(i);
            else {
                v = new CardView(getContext());
                views.add(v);
                mContent.addView(v);
            }
            v.bind(cards.get(i), mCw, mCh);
        }
    }

    private List<CardView> list(int sec) {
        return sec == 0 ? mFav : mAll;
    }

    public Card focusedCard() {
        List<CardView> l = list(mSec);
        return mIdx < l.size() ? l.get(mIdx).card() : null;
    }

    // ------------------------------------------------------------------ focus

    @Override
    public void focusIn() {
        mActive = true;
        mSec = mFav.isEmpty() ? 1 : 0;
        mIdx = 0;
        applyFocus(true);
        applyScroll(true);
    }

    @Override
    public void focusOut() {
        mActive = false;
        if (mMoving) endMove();
        applyFocus(true);
    }

    private void applyFocus(boolean animate) {
        for (int s = 0; s < 2; s++) {
            List<CardView> l = list(s);
            for (int i = 0; i < l.size(); i++) {
                boolean f = mActive && s == mSec && i == mIdx;
                l.get(i).setFocusState(f, animate, i % COLS == 0);
            }
        }
        CardView v = focusedView();
        if (mActive && v != null) {
            mCaption.setText(v.card().title);
            mCaption.setTranslationX(v.getLeft() - mCaption.getLeft());
            mCaption.setTranslationY(v.getBottom() + Math.round(mCh * (Theme.FOCUS_SCALE - 1) / 2) + Theme.px(14) - mCaption.getTop());
            mCaption.setAlpha(0);
            mCaption.animate().alpha(v.card().kind == Card.MORE_APPS ? 0 : 1).setStartDelay(60).setDuration(120).start();
            mHost.onFocusArt(null, null);
        } else {
            mCaption.setAlpha(0);
        }
    }

    private CardView focusedView() {
        List<CardView> l = list(mSec);
        return mIdx < l.size() ? l.get(mIdx) : null;
    }

    private void applyScroll(boolean animate) {
        CardView v = focusedView();
        if (v == null) return;
        int h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        float cur = mContent.getTranslationY();
        float y = cur;
        int top = v.getTop() - Theme.px(80), bottom = v.getBottom() + Theme.px(90);
        if (top + y < Theme.px(150)) y = Theme.px(150) - top;
        if (bottom + y > h) y = h - bottom;
        if (mSec == 0 && mIdx < COLS) y = 0;
        if (y > 0) y = 0;
        if (animate) mContent.animate().translationY(y).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED)
                .withEndAction(this::loadVisible).start();
        else {
            mContent.setTranslationY(y);
            loadVisible();
        }
    }

    private void loadVisible() {
        int h = getResources().getDisplayMetrics().heightPixels;
        float ty = mContent.getTranslationY();
        for (int s = 0; s < 2; s++) {
            for (CardView v : list(s)) {
                float top = v.getTop() + ty;
                boolean near = mShown && top > -mPitch * 2 && top < h + mPitch;
                if (near) v.loadArt(mHost.images(), false);
                else v.dropArt();
            }
        }
    }

    @Override
    public boolean onKey(int keyCode, KeyEvent e) {
        List<CardView> l = list(mSec);
        if (l.isEmpty()) return keyCode != KeyEvent.KEYCODE_DPAD_UP;
        boolean rtl = Theme.rtl(this);
        int col = mIdx % COLS;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl ? 1 : -1;
                int to = mIdx + dir;
                if (to < 0 || to >= l.size()) return true;
                if (mMoving) {
                    swapFav(mIdx, to);
                    return true;
                }
                if (to / COLS != mIdx / COLS) return true; // no wrap
                mIdx = to;
                applyFocus(true);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP: {
                if (mMoving) return true;
                if (mIdx - COLS >= 0) mIdx -= COLS;
                else if (mSec == 1 && !mFav.isEmpty()) {
                    mSec = 0;
                    int lastRow = (mFav.size() - 1) / COLS;
                    mIdx = Math.min(lastRow * COLS + col, mFav.size() - 1);
                } else return false;
                applyFocus(true);
                applyScroll(true);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (mMoving) return true;
                if (mIdx + COLS < l.size()) mIdx += COLS;
                else if (mIdx / COLS < (l.size() - 1) / COLS) mIdx = l.size() - 1;
                else if (mSec == 0 && !mAll.isEmpty()) {
                    mSec = 1;
                    mIdx = Math.min(col, mAll.size() - 1);
                } else return true;
                applyFocus(true);
                applyScroll(true);
                return true;
            }
            default:
                if (PageForYou.isOk(keyCode)) {
                    if (mMoving) {
                        endMove();
                        return true;
                    }
                    CardView v = focusedView();
                    if (v != null) {
                        Card k = v.card();
                        v.pulse(() -> mHost.onCardClick(k, v));
                    }
                    return true;
                }
                return false;
        }
    }

    // ------------------------------------------------------------------ move mode (favorites)

    public void startMoveFavorite(Card k) {
        int i = mFavCards.indexOf(k);
        if (i < 0) {
            for (int j = 0; j < mFavCards.size(); j++) if (mFavCards.get(j).id.equals(k.id)) i = j;
        }
        if (i < 0 || mFav.size() < 2) return;
        mSec = 0;
        mIdx = i;
        mMoving = true;
        applyFocus(false);
        mFav.get(i).setMoving(true);
    }

    private void swapFav(int a, int b) {
        Collections.swap(mFavCards, a, b);
        Collections.swap(mFav, a, b);
        mFav.get(a).setMoving(false);
        mIdx = b;
        mContent.requestLayout();
        mContent.post(() -> {
            applyFocus(false);
            mFav.get(mIdx).setMoving(true);
        });
    }

    private void endMove() {
        mMoving = false;
        for (CardView v : mFav) v.setMoving(false);
        mHost.onFavoritesReordered(new ArrayList<>(mFavCards));
    }

    @Override
    public void onMenu() {
        Card k = focusedCard();
        if (k != null && k.kind == Card.APP) mHost.onCardMenu(k, null);
    }

    @Override
    public boolean onBack() {
        if (mMoving) {
            endMove();
            return true;
        }
        return false;
    }

    @Override
    public void scrollToTop(boolean animate) {
        mSec = mFav.isEmpty() ? 1 : 0;
        mIdx = 0;
        applyFocus(false);
        applyScroll(animate);
    }

    @Override
    public void setShown(boolean shown) {
        mShown = shown;
        loadVisible();
    }

    @Override
    public void trim() {
        for (CardView v : mFav) v.dropArt();
        for (CardView v : mAll) v.dropArt();
    }

    @Override
    public void reload() {
        loadVisible();
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        mContent.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mContent.layout(0, 0, r - l, mContent.getMeasuredHeight());
    }

    private final class Content extends ViewGroup {
        Content(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int tw = MeasureSpec.makeMeasureSpec(w - 2 * mMargin, MeasureSpec.EXACTLY);
            mHeadline.measure(tw, un);
            mFavTitle.measure(tw, un);
            mAllTitle.measure(tw, un);
            mCaption.measure(MeasureSpec.makeMeasureSpec(Theme.px(560), MeasureSpec.AT_MOST), un);
            int cs = MeasureSpec.makeMeasureSpec(mCw, MeasureSpec.EXACTLY), hs = MeasureSpec.makeMeasureSpec(mCh, MeasureSpec.EXACTLY);
            for (CardView v : mFav) v.measure(cs, hs);
            for (CardView v : mAll) v.measure(cs, hs);
            setMeasuredDimension(w, layoutPass(w, false) + Theme.px(200));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            layoutPass(r - l, true);
        }

        private int layoutPass(int w, boolean apply) {
            boolean rtl = Theme.rtl(this);
            int y = Theme.px(150);
            y = put(mHeadline, w, y, apply, rtl) + Theme.px(56);
            if (!mFav.isEmpty()) {
                y = put(mFavTitle, w, y, apply, rtl) + Theme.px(20);
                y = grid(mFav, w, y, apply, rtl) + Theme.px(40);
            }
            y = put(mAllTitle, w, y, apply, rtl) + Theme.px(20);
            y = grid(mAll, w, y, apply, rtl);
            if (apply) mCaption.layout(rtl ? w - mCaption.getMeasuredWidth() : 0, 0,
                    rtl ? w : mCaption.getMeasuredWidth(), mCaption.getMeasuredHeight());
            return y;
        }

        private int put(View v, int w, int y, boolean apply, boolean rtl) {
            if (v.getVisibility() == GONE) return y;
            int vw = v.getMeasuredWidth();
            int x = rtl ? w - mMargin - vw : mMargin;
            if (apply) v.layout(x, y, x + vw, y + v.getMeasuredHeight());
            return y + v.getMeasuredHeight();
        }

        private int grid(List<CardView> l, int w, int y, boolean apply, boolean rtl) {
            for (int i = 0; i < l.size(); i++) {
                int r = i / COLS, c = i % COLS;
                int x = mMargin + c * (mCw + mGap);
                if (rtl) x = w - x - mCw;
                int yy = y + r * mPitch;
                if (apply) l.get(i).layout(x, yy, x + mCw, yy + mCh);
            }
            int rows = (l.size() + COLS - 1) / COLS;
            return y + Math.max(0, rows * mPitch - (mPitch - mCh));
        }
    }
}
