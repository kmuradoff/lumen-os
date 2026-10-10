package org.z9x.home.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.Row;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * "Home" of direction D. With something to continue (Watch Next) or app preview programs it is
 * D_Home: the hero over the stage art, "Continue watching" at y 740 (D_Home: 790), then the apps and the
 * app channels. With nothing started, or with "Continue watching on Home" hidden, it is D_Calm: the big
 * clock, date and weather over the living sky and the apps shelf at y 752. Then the "Customize Home"
 * pill. Vertical scrolling only translates one track view: below the first row the focused row's
 * heading is kept at y 132 (browse mode) and the hero / calm block fades out. Focus in "Continue
 * watching" moves the hero (after a 250 ms dwell). TV safe area (1.0.1 follow-up): the first row moves
 * up if its focused card would come closer than 54 px to the bottom edge, and rows (and the pill) that
 * the screen edge would cut are faded out until they scroll in.
 */
public class PageForYou extends ViewGroup implements Page {
    private static final long HERO_FOLLOW_MS = 250;
    private final Page.Host mHost;
    private final Track mTrack;
    private final HeroView mHero;
    private final CalmView mCalm;
    private final ArrayList<RowView> mRows = new ArrayList<>();
    private final HashMap<String, RowView> mById = new HashMap<>();
    private final PillButton mCustomize;
    private int mSection = 0;       // 0 = hero buttons / calm weather line, 1..n = rows, n+1 = customize
    private boolean mActive;
    private boolean mBrowse;
    private boolean mShown = true;
    private boolean mHasHero;
    private boolean mBound;
    private Card mFollow;
    private final Runnable mFollowHero = this::followHero;
    private int mScreenH;
    private int mRowY;            // the first row's top (real px), see firstRowY()
    private int mHeroBottom = -1; // last value told to the host
    private float mTrackY;        // the track's target translation
    private boolean mPillInBand = true;

    public PageForYou(Context c, Page.Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mTrack = new Track(c);
        addView(mTrack);
        // until the first model arrives Home is calm: the clock needs no data
        mHero = new HeroView(c, host);
        mHero.setVisibility(GONE);
        mTrack.addView(mHero);
        mCalm = new CalmView(c);
        mTrack.addView(mCalm);
        mCustomize = new PillButton(c, 64, 26, PillButton.STYLE_FILLED).icon(R.drawable.ic_edit)
                .label(c.getString(R.string.customize_home));
        mTrack.addView(mCustomize);
    }

    @Override
    public View view() {
        return this;
    }

    public HeroView hero() {
        return mHero;
    }

    public CalmView calm() {
        return mCalm;
    }

    /** D_Home (true) or D_Calm (false). */
    public boolean heroMode() {
        return mHasHero;
    }

    // ------------------------------------------------------------------ binding

    @Override
    public void bind(HomeModel m) {
        String focusedRow = currentRow() != null ? currentRow().row().id : null;
        boolean firstBind = !mBound;
        mBound = true;
        boolean onCustomize = !firstBind && mSection == mRows.size() + 1;
        boolean onTop = !firstBind && mSection == 0;
        HashMap<Long, String> channels = new HashMap<>();
        for (Row r : m.allRows) if (r.type == Row.CHANNEL) channels.put(r.channelId, r.title);
        boolean hero = !m.hero.isEmpty();
        if (hero) mHero.setSlides(m.hero, channels);
        boolean modeChanged = setMode(hero, firstBind);
        ArrayList<RowView> next = new ArrayList<>();
        HashMap<String, RowView> nextById = new HashMap<>();
        int idx = 0;
        for (Row r : m.rows) {
            if (r.type == Row.CUSTOMIZE) continue;
            RowView rv = mById.get(r.id);
            if (rv == null) {
                rv = new RowView(getContext(), mHost);
                mTrack.addView(rv, mTrack.indexOfChild(mCustomize));
            }
            rv.setRow(r, idx < 3);
            next.add(rv);
            nextById.put(r.id, rv);
            idx++;
        }
        for (RowView old : mRows) {
            if (!nextById.containsKey(old.row().id)) {
                old.setActive(false);
                old.setNear(false);
                mTrack.removeView(old);
            }
        }
        // keep track children in model order so drawing order matches (hero and calm come first)
        for (int i = 0; i < next.size(); i++) {
            RowView rv = next.get(i);
            if (mTrack.indexOfChild(rv) != i + 2) {
                mTrack.removeView(rv);
                mTrack.addView(rv, i + 2);
            }
        }
        mRows.clear();
        mRows.addAll(next);
        mById.clear();
        mById.putAll(nextById);
        Card followed = mHero.transientCard();
        if (followed != null) {
            Card now = null;
            RowView wn = mById.get(Row.ID_WATCH_NEXT);
            if (wn != null) for (Card c : wn.row().cards) if (c.id.equals(followed.id)) now = c;
            if (now == null) mHero.dropTransient();
            else if (!now.sameContent(followed)) {
                mHero.dropTransient();
                mHero.showCard(now, false);
            }
        }
        int s;
        // D_Home: "Continue"; D_Calm: the first app (also when the layout just switched under the user)
        if (firstBind || modeChanged) s = mHasHero ? 0 : 1;
        else if (onCustomize) s = mRows.size() + 1;
        else if (onTop) s = 0;
        else if (focusedRow != null && mById.containsKey(focusedRow)) s = mRows.indexOf(mById.get(focusedRow)) + 1;
        else s = Math.min(mSection, mRows.size() + 1);
        mTrack.requestLayout();
        final int fs = s;
        mTrack.post(() -> {
            setSection(fs, false, true);
            updateNear();
        });
    }

    /** Returns true if the layout changed between D_Home and D_Calm. */
    private boolean setMode(boolean hero, boolean first) {
        boolean changed = hero != mHasHero || first;
        mHasHero = hero;
        mHero.setVisibility(hero ? VISIBLE : GONE);
        mCalm.setVisibility(hero ? GONE : VISIBLE);
        if (changed) {
            View in = hero ? mHero : mCalm;
            if (Theme.animations() && mShown && !first) {
                in.setAlpha(0f);
                in.animate().alpha(mBrowse ? 0f : 1f).setDuration(Theme.HERO_FADE_MS).start();
            }
            mHost.onHomeMode(hero);
        }
        return changed && !first;
    }

    private RowView currentRow() {
        int i = mSection - 1;
        return i >= 0 && i < mRows.size() ? mRows.get(i) : null;
    }

    // ------------------------------------------------------------------ focus

    private int minSection() {
        return mHasHero || mCalm.hasWeather() ? 0 : 1;
    }

    /** DOWN from the header: the first thing below it (hero buttons, else the weather line, else the apps). */
    @Override
    public void focusIn() {
        mActive = true;
        setSection(minSection(), true, true);
    }

    /** HOME pressed on Home: back to the top; D_Home focuses "Continue", D_Calm the first app. */
    public void focusHome() {
        mActive = true;
        removeCallbacks(mFollowHero);
        if (mHasHero) mHero.dropTransient();
        RowView r = mRows.isEmpty() ? null : mRows.get(0);
        if (r != null) r.focusIndex(0);
        setSection(mHasHero || r == null ? minSection() : 1, true, true);
    }

    @Override
    public void focusOut() {
        mActive = false;
        applyFocus();
    }

    /** The calm weather line can appear or go while focused: keep the section valid. */
    public void onWeatherChanged() {
        if (mSection < minSection()) setSection(minSection(), false, true);
    }

    private void setSection(int s, boolean animate, boolean force) {
        int max = mRows.size() + 1;
        s = Math.max(minSection(), Math.min(s, max));
        if (s == mSection && !force) return;
        mSection = s;
        applyFocus();
        applyScroll(animate);
        updateNear();
    }

    private void applyFocus() {
        mHero.focusOut();
        mCalm.setWeatherFocused(false);
        if (mActive && mSection == 0) {
            if (mHasHero) mHero.focusIn();
            else mCalm.setWeatherFocused(true);
        }
        for (int i = 0; i < mRows.size(); i++) mRows.get(i).setActive(mActive && mSection == i + 1);
        mCustomize.setFocusState(mActive && mSection == mRows.size() + 1, true);
    }

    private int sectionTop(int s) {
        if (s <= 0) return 0;
        if (s <= mRows.size()) return mRows.get(s - 1).getTop();
        return mCustomize.getTop() - Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP);
    }

    private void applyScroll(boolean animate) {
        boolean browse = mSection >= 2;
        float y = browse ? -(sectionTop(mSection) - Theme.px(Theme.FOCUS_LINE_Y)) : 0;
        View top = mHasHero ? mHero : mCalm;
        mTrack.animate().cancel();
        top.animate().cancel();
        // half-speed parallax while it leaves; it fades out completely so it never sits under a row
        float topY = -y * 0.5f;
        float topA = browse ? 0f : 1f;
        if (browse != mBrowse) {
            mBrowse = browse;
            mHost.onBrowseMode(browse);
        }
        mTrackY = y;
        if (animate) {
            mTrack.animate().translationY(y).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
            top.animate().translationY(topY).alpha(topA).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
        } else {
            mTrack.setTranslationY(y);
            top.setTranslationY(topY);
            top.setAlpha(topA);
        }
        applyBand(animate);
    }

    /**
     * Only what lies fully inside the TV safe band at the target scroll is shown: a row from its heading
     * to its focused card's ring and caption, the pill with its focus scale. At the top that is the first
     * row alone; in browse mode the focused row and what fits below it.
     */
    private void applyBand(boolean animate) {
        if (mScreenH <= 0) return;
        int top = Theme.px(Theme.SAFE), bottom = mScreenH - top;
        for (RowView r : mRows) {
            float y = r.getTop() + mTrackY;
            r.setInBand(y >= top && y + r.focusExtent() <= bottom, animate);
        }
        float py = mCustomize.getTop() + mTrackY;
        boolean in = py >= top && py + mCustomize.getHeight() * 1.04f <= bottom;
        if (in != mPillInBand) {
            mPillInBand = in;
            if (animate && Theme.animations()) mCustomize.animate().alpha(in ? 1f : 0f).setDuration(Theme.PAGE_SCROLL_MS).start();
            else mCustomize.setAlpha(in ? 1f : 0f);
        }
    }

    private void updateNear() {
        if (!mShown) {
            for (RowView r : mRows) r.setNear(false);
            return;
        }
        int s = Math.max(1, mSection);
        for (int i = 0; i < mRows.size(); i++) {
            int sec = i + 1;
            boolean near = mSection <= 1 ? sec <= 3 : (sec >= s - 1 && sec <= s + 2);
            mRows.get(i).setNear(near);
        }
    }

    /** From the activity's RowView.Host: a "Continue watching" card moves the hero after a dwell. */
    public void rowFocused(RowView row, Card k) {
        removeCallbacks(mFollowHero);
        if (!mHasHero || row == null || row.row() == null || row.row().type != Row.WATCH_NEXT || k == null) return;
        mFollow = k;
        postDelayed(mFollowHero, Theme.animations() ? HERO_FOLLOW_MS : 0);
    }

    private void followHero() {
        if (mFollow != null && mHasHero) mHero.showCard(mFollow, true);
    }

    @Override
    public boolean onKey(int keyCode, KeyEvent e) {
        RowView row = currentRow();
        if (row != null && row.moving()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return row.onKey(keyCode, e);
            if (isOk(keyCode)) {
                row.endMove();
                return true;
            }
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mSection <= minSection()) return false;
                setSection(mSection - 1, true, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mSection < mRows.size() + 1) setSection(mSection + 1, true, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mSection == 0) return !mHasHero || mHero.onKey(keyCode, e);
                if (row != null) row.onKey(keyCode, e);
                return true;
            default:
                if (isOk(keyCode)) {
                    if (mSection == 0) {
                        if (mHasHero) return mHero.onKey(keyCode, e);
                        mHost.onWeatherAction();
                        return true;
                    }
                    if (mSection == mRows.size() + 1) {
                        mCustomize.animate().scaleX(1f).scaleY(1f).setDuration(60).withEndAction(() -> {
                            mCustomize.animate().scaleX(1.04f).scaleY(1.04f).setDuration(60).start();
                            mHost.onCustomize();
                        }).start();
                        return true;
                    }
                    if (row != null) {
                        CardView v = row.focusedView();
                        Card k = row.focusedCard();
                        if (v != null && k != null) v.pulse(() -> mHost.onCardClick(k, v));
                    }
                    return true;
                }
                return false;
        }
    }

    public static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public void onMenu() {
        RowView row = currentRow();
        if (row != null && row.focusedCard() != null) mHost.onCardMenu(row.focusedCard(), row);
        else if (mSection == 0 && mHasHero && mHero.current() != null && mHero.current().kind == Card.PROGRAM) {
            mHost.onCardMenu(mHero.current(), null);
        }
    }

    @Override
    public boolean onBack() {
        RowView row = currentRow();
        if (row != null && row.moving()) {
            row.endMove();
            return true;
        }
        if (mSection >= 2) {
            setSection(1, true, false);
            return true;
        }
        if (mSection == 1 && mHasHero) {
            setSection(0, true, false);
            return true;
        }
        return false;
    }

    public boolean atTop() {
        return mSection <= minSection() || (!mHasHero && mSection == 1);
    }

    public boolean inTopMode() {
        return !mBrowse;
    }

    public boolean heroFocused() {
        return mActive && mSection == 0 && mHasHero;
    }

    @Override
    public void scrollToTop(boolean animate) {
        setSection(mHasHero ? 0 : 1, animate, true);
    }

    @Override
    public void setShown(boolean shown) {
        mShown = shown;
        updateNear();
    }

    @Override
    public void trim() {
        for (RowView r : mRows) r.setNear(false);
    }

    @Override
    public void reload() {
        updateNear();
    }

    /** First full frame of art: the first two rows (reportFullyDrawn). */
    public boolean artReady() {
        for (int i = 0; i < Math.min(2, mRows.size()); i++) if (!mRows.get(i).artReady()) return false;
        return true;
    }

    public RowView appsRow() {
        return mById.get(Row.ID_APPS);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        mScreenH = h;
        mTrack.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mTrack.layout(0, 0, r - l, mTrack.getMeasuredHeight());
    }

    /**
     * The first row's top: D's 740 (hero) / 752 (calm), higher when its focused card (scale, ring,
     * caption) would come closer than 54 px to the bottom edge (a row of tall posters first in the
     * user's row order). Rows must be measured.
     */
    private int firstRowY() {
        int y = Theme.px(mHasHero ? Theme.FIRST_ROW_Y : Theme.FIRST_ROW_Y_CALM);
        if (!mRows.isEmpty() && mScreenH > 0) y = Math.min(y, mScreenH - Theme.px(Theme.SAFE) - mRows.get(0).focusExtent());
        return y;
    }

    private final class Track extends ViewGroup {
        Track(Context c) {
            super(c);
            setClipChildren(false);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            int exact = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            for (RowView r : mRows) r.measure(exact, un);
            mRowY = firstRowY();
            mHero.setRowY(mRowY);
            mCalm.setRowY(mRowY);
            mHero.measure(exact, un);
            mCalm.measure(exact, un);
            int y = mRowY;
            for (RowView r : mRows) y += r.getMeasuredHeight();
            mCustomize.measure(un, un);
            y += Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP) + mCustomize.getMeasuredHeight() + Theme.px(400);
            setMeasuredDimension(w, y);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            mHero.layout(0, 0, w, mHero.getMeasuredHeight());
            mCalm.layout(0, 0, w, mCalm.getMeasuredHeight());
            int y = mRowY;
            for (RowView rv : mRows) {
                rv.layout(0, y, w, y + rv.getMeasuredHeight());
                y += rv.getMeasuredHeight();
            }
            y += Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP);
            int cw = mCustomize.getMeasuredWidth();
            int x = Theme.rtl(this) ? w - Theme.px(Theme.MARGIN) - cw : Theme.px(Theme.MARGIN);
            mCustomize.layout(x, y, x + cw, y + mCustomize.getMeasuredHeight());
            // the rows' places are known now (a bind may have scrolled before this layout)
            applyBand(false);
            // the hero's art card ends 60 px above the first row (Stage)
            final int hb = mRowY - Theme.px(60);
            if (hb != mHeroBottom) {
                mHeroBottom = hb;
                post(() -> mHost.onHeroBottom(hb));
            }
        }
    }
}
