package org.z9x.home.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.Row;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * "Home". With something to continue (Watch Next) or app preview programs it is the Lumen OS 1.0.5 Home
 * (the owner's mockups design/home_v2, tpl2 the approved variant): the focused card's art fills the
 * screen behind everything ({@link Stage}), the info block on the start side ({@link HeroView})
 * describes the focused card, and the rows sit below it: "For you" (Continue watching, then the
 * recommendations of every app, Ranker.forYou), the apps, then inputs and projector tiles when shown. The focused row stays on
 * one line (the row line: at most y 740, higher when a row's focused card would come closer than 54 px
 * to the bottom edge, so the same line for every row); vertical moves only translate the track, rows
 * above the focused one fade out (they would overlap the info block), rows below show while they fit
 * the TV safe band. When the focus moves the info block fades out, and 350 ms after it settles it shows
 * the newly focused program and its art crossfades in behind. Apps, inputs and tiles have no info: the
 * block stays out and the last art stays. With nothing started, or with "Continue watching on Home"
 * hidden, it is D_Calm as before: the big clock, date and weather over the living sky and the apps
 * shelf at y 752; below the first row the focused row's heading is kept at y 132 (browse mode) and the
 * calm block fades out. Then the "Customize Home" pill. TV safe area (1.0.1 follow-up): rows (and the
 * pill) that the screen edge would cut are faded out until they scroll in.
 */
public class PageForYou extends ViewGroup implements Page {
    /** The info block and the art follow the focus once it rests this long (the owner's mockup: 350 ms). */
    private static final long INFO_DWELL_MS = 350;
    private final Page.Host mHost;
    private final Track mTrack;
    private final HeroView mHero;
    private final CalmView mCalm;
    private final ArrayList<RowView> mRows = new ArrayList<>();
    private final HashMap<String, RowView> mById = new HashMap<>();
    private final PillButton mCustomize;
    private int mSection = 1;       // 0 = calm weather line, 1..n = rows, n+1 = customize
    private boolean mActive;
    private boolean mBrowse;
    private boolean mShown = true;
    private boolean mHasHero;
    private boolean mBound;
    private RowView mFollowRow;
    private Card mFollow;
    private final Runnable mFollowInfo = this::followInfo;
    private int mScreenH;
    private int mRowY;            // the row line (real px), see rowLine()
    private float mTrackY;        // the track's target translation
    private boolean mPillInBand = true;

    public PageForYou(Context c, Page.Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        // the info block under the track: the rows draw over it
        mHero = new HeroView(c, host);
        mHero.setVisibility(GONE);
        addView(mHero);
        mTrack = new Track(c);
        addView(mTrack);
        // until the first model arrives Home is calm: the clock needs no data
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

    /** The backdrop Home (true) or D_Calm (false). */
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
        boolean hero = !m.hero.isEmpty();
        boolean modeChanged = setMode(hero, firstBind);
        ArrayList<RowView> next = new ArrayList<>();
        HashMap<String, RowView> nextById = new HashMap<>();
        int idx = 0;
        for (Row r : m.rows) {
            if (r.type == Row.CUSTOMIZE) continue;
            RowView rv = mById.get(r.id);
            if (rv == null) {
                rv = new RowView(getContext(), mHost);
                // the info block shows the focused program's title: no caption under its card
                rv.setProgramCaptions(false);
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
        // keep track children in model order so drawing order matches (the calm block comes first)
        for (int i = 0; i < next.size(); i++) {
            RowView rv = next.get(i);
            if (mTrack.indexOfChild(rv) != i + 1) {
                mTrack.removeView(rv);
                mTrack.addView(rv, i + 1);
            }
        }
        mRows.clear();
        mRows.addAll(next);
        mById.clear();
        mById.putAll(nextById);
        int s;
        // the first row: "Continue watching" (or the first channel), D_Calm the first app (also when the
        // layout just switched under the user)
        if (firstBind || modeChanged) s = 1;
        else if (onCustomize) s = mRows.size() + 1;
        else if (onTop) s = 0;
        else if (focusedRow != null && mById.containsKey(focusedRow)) s = mRows.indexOf(mById.get(focusedRow)) + 1;
        else s = Math.min(mSection, mRows.size() + 1);
        mTrack.requestLayout();
        final int fs = s;
        final boolean now = firstBind || modeChanged;
        mTrack.post(() -> {
            setSection(fs, false, true);
            updateNear();
            refreshInfo(now);
        });
    }

    /** Returns true if the layout changed between the backdrop Home and D_Calm. */
    private boolean setMode(boolean hero, boolean first) {
        boolean changed = hero != mHasHero || first;
        mHasHero = hero;
        mHero.setVisibility(hero ? VISIBLE : GONE);
        mCalm.setVisibility(hero ? GONE : VISIBLE);
        if (changed) {
            removeCallbacks(mFollowInfo);
            mHero.clear();
            if (!hero && Theme.animations() && mShown && !first) {
                mCalm.setAlpha(0f);
                mCalm.animate().alpha(mBrowse ? 0f : 1f).setDuration(Theme.HERO_FADE_MS).start();
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
        return !mHasHero && mCalm.hasWeather() ? 0 : 1;
    }

    /** DOWN from the header: the first row (D_Calm: the weather line when there is one). */
    @Override
    public void focusIn() {
        mActive = true;
        setSection(minSection(), true, true);
    }

    /** HOME pressed on Home: back to the top; the first card of the first row. */
    public void focusHome() {
        mActive = true;
        RowView r = mRows.isEmpty() ? null : mRows.get(0);
        if (r != null) r.focusIndex(0);
        setSection(mHasHero || r == null ? minSection() : 1, true, true);
        refreshInfo(false);
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
        // the pill has no info: the block goes, the last art stays
        if (mHasHero && s == max) {
            removeCallbacks(mFollowInfo);
            mHero.fadeOut(animate);
        }
    }

    private void applyFocus() {
        mCalm.setWeatherFocused(mActive && mSection == 0 && !mHasHero);
        for (int i = 0; i < mRows.size(); i++) mRows.get(i).setActive(mActive && mSection == i + 1);
        mCustomize.setFocusState(mActive && mSection == mRows.size() + 1, true);
    }

    private int sectionTop(int s) {
        if (s <= 0) return 0;
        if (s <= mRows.size()) return mRows.get(s - 1).getTop();
        return mCustomize.getTop() - Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP);
    }

    private void applyScroll(boolean animate) {
        mTrack.animate().cancel();
        if (mHasHero) {
            // the focused row (or the pill's heading space) on the row line, whatever row it is
            float y = mSection >= 1 ? -(sectionTop(mSection) - mRowY) : 0;
            if (mBrowse) {
                mBrowse = false;
                mHost.onBrowseMode(false);
            }
            mTrackY = y;
            if (animate) {
                mTrack.animate().translationY(y).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
            } else {
                mTrack.setTranslationY(y);
            }
            applyBand(animate);
            return;
        }
        boolean browse = mSection >= 2;
        float y = browse ? -(sectionTop(mSection) - Theme.px(Theme.FOCUS_LINE_Y)) : 0;
        View top = mCalm;
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
            ViewPropertyAnimator ta = top.animate().translationY(topY).alpha(topA).setDuration(Theme.PAGE_SCROLL_MS)
                    .setInterpolator(Theme.EMPHASIZED);
            // weak GPUs (Gfx): the block rendered once into a layer, not into an offscreen buffer every frame
            if (Gfx.LITE) ta.withLayer();
            ta.start();
        } else {
            mTrack.setTranslationY(y);
            top.setTranslationY(topY);
            top.setAlpha(topA);
        }
        applyBand(animate);
    }

    /**
     * Only what lies fully inside the TV safe band at the target scroll is shown: a row from its heading
     * to its focused card's ring and caption, the pill with its focus scale. On the backdrop Home rows
     * above the focused one are out too (the info block is there); D_Calm at the top: the first row
     * alone; in browse mode the focused row and what fits below it.
     */
    private void applyBand(boolean animate) {
        if (mScreenH <= 0) return;
        int top = Theme.px(Theme.SAFE), bottom = mScreenH - top;
        for (int i = 0; i < mRows.size(); i++) {
            RowView r = mRows.get(i);
            float y = r.getTop() + mTrackY;
            boolean above = mHasHero && i + 1 < mSection;
            r.setInBand(!above && y >= top && y + r.focusExtent() <= bottom, animate);
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

    // ------------------------------------------------------------------ the info block and the art

    /**
     * From the activity's RowView.Host: the focus moved in a row (or to another row). The info block
     * fades out now; the newly focused program follows after the dwell.
     */
    public void rowFocused(RowView row, Card k) {
        removeCallbacks(mFollowInfo);
        if (!mHasHero || row == null || row.row() == null) return;
        Card cur = mHero.current();
        if (k != null && k.kind == Card.PROGRAM && mHero.shown() && cur != null && cur.id.equals(k.id)) return;
        mHero.fadeOut(true);
        mFollowRow = row;
        mFollow = k;
        postDelayed(mFollowInfo, Theme.animations() ? INFO_DWELL_MS : 0);
    }

    private void followInfo() {
        Card k = mFollow;
        RowView row = mFollowRow;
        if (!mHasHero || k == null || row == null || row != currentRow() || k != row.focusedCard()) return;
        if (k.kind != Card.PROGRAM) return; // apps, inputs, tiles: no info, the last art stays
        mHero.show(k, rowTitle(row), true);
        mHost.onHeroShown(k);
    }

    /**
     * After a bind or HOME: the info block about the focused card, at once ({@code now}: the first frame,
     * a mode switch) or after the dwell; a refreshed card (progress, art) is re-shown in place.
     */
    private void refreshInfo(boolean now) {
        if (!mHasHero) return;
        RowView row = currentRow();
        Card k = row != null ? row.focusedCard() : null;
        if (k == null || k.kind != Card.PROGRAM) {
            removeCallbacks(mFollowInfo);
            if (mHero.shown()) mHero.fadeOut(true);
            return;
        }
        Card cur = mHero.current();
        if (mHero.shown() && cur != null && cur.id.equals(k.id)) {
            if (!cur.sameContent(k)) {
                mHero.show(k, rowTitle(row), false);
                mHost.onHeroShown(k);
            }
            return;
        }
        if (now) {
            removeCallbacks(mFollowInfo);
            mHero.show(k, rowTitle(row), false);
            mHost.onHeroShown(k);
            return;
        }
        rowFocused(row, k);
    }

    /**
     * The eyebrow's first word for a card that is not a started program (those show their kind,
     * "Continue"): "Recommendation" in "For you", else the channel's title.
     */
    private String rowTitle(RowView row) {
        Row r = row.row();
        if (r == null || r.type == Row.WATCH_NEXT) return null;
        return r.type == Row.FOR_YOU ? getContext().getString(R.string.hero_kind_recommendation) : r.title;
    }

    // ------------------------------------------------------------------ keys

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
                if (row != null) row.onKey(keyCode, e);
                return true;
            default:
                if (isOk(keyCode)) {
                    if (mSection == 0) {
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

    /** Long OK / MENU: a program's details ("More info", the info block's hint), else the card's menu. */
    @Override
    public void onMenu() {
        RowView row = currentRow();
        Card k = row != null ? row.focusedCard() : null;
        if (k == null) return;
        if (k.kind == Card.PROGRAM) mHost.onHeroAction(k, false);
        else mHost.onCardMenu(k, row);
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
        return false;
    }

    public boolean atTop() {
        return mSection <= minSection() || (!mHasHero && mSection == 1);
    }

    @Override
    public void scrollToTop(boolean animate) {
        setSection(1, animate, true);
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
        mHero.setRowY(mRowY);
        mHero.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mHero.layout(0, 0, r - l, mHero.getMeasuredHeight());
        mTrack.layout(0, 0, r - l, mTrack.getMeasuredHeight());
    }

    /**
     * The row line: the top of the focused row on the backdrop Home, of the first row on D_Calm. At most
     * 740 (backdrop) / 752 (calm), higher when a focused card (scale, ring) would come closer than 54 px
     * to the bottom edge: on the backdrop Home for every row (one line for all, a row of tall posters
     * moves it for every row), on D_Calm for the first row. Rows must be measured.
     */
    private int rowLine() {
        int y = Theme.px(mHasHero ? Theme.FIRST_ROW_Y : Theme.FIRST_ROW_Y_CALM);
        if (mScreenH <= 0 || mRows.isEmpty()) return y;
        int bottom = mScreenH - Theme.px(Theme.SAFE);
        if (mHasHero) for (RowView r : mRows) y = Math.min(y, bottom - r.focusExtent());
        else y = Math.min(y, bottom - mRows.get(0).focusExtent());
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
            mRowY = rowLine();
            mCalm.setRowY(mRowY);
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
        }
    }
}
