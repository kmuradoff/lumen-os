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
 * "For you" (SPEC 7.5): hero, then rows (Your apps, Continue watching, app channels, Inputs,
 * Projector), then the "Customize Home" pill. Vertical scrolling only translates one track view:
 * the focused row's title is kept at y 120 in browse mode; the hero follows at half speed, dims to
 * 30 % and stops drawing once it is off screen.
 */
public class PageForYou extends ViewGroup implements Page {
    private final Page.Host mHost;
    private final Track mTrack;
    private final HeroView mHero;
    private final ArrayList<RowView> mRows = new ArrayList<>();
    private final HashMap<String, RowView> mById = new HashMap<>();
    private final PillButton mCustomize;
    private final View mCustomizeHolder;
    private int mSection = 0;       // 0 = hero, 1..n = rows, n+1 = customize
    private boolean mActive;
    private boolean mBrowse;
    private boolean mShown = true;
    private boolean mHasHero;

    public PageForYou(Context c, Page.Host host) {
        super(c);
        mHost = host;
        setClipChildren(false);
        mTrack = new Track(c);
        addView(mTrack);
        mHero = new HeroView(c, host);
        mTrack.addView(mHero);
        mCustomize = new PillButton(c, 64, 28, PillButton.STYLE_FILLED).icon(R.drawable.ic_edit)
                .label(c.getString(R.string.customize_home));
        mCustomizeHolder = mCustomize;
        mTrack.addView(mCustomize);
    }

    @Override
    public View view() {
        return this;
    }

    public HeroView hero() {
        return mHero;
    }

    // ------------------------------------------------------------------ binding

    @Override
    public void bind(HomeModel m) {
        String focusedRow = currentRow() != null ? currentRow().row().id : null;
        boolean firstBind = mRows.isEmpty();
        boolean onCustomize = !firstBind && mSection == mRows.size() + 1;
        mHero.setSlides(m.hero);
        mHasHero = !m.hero.isEmpty();
        mHero.setVisibility(mHasHero ? VISIBLE : GONE);
        ArrayList<RowView> next = new ArrayList<>();
        HashMap<String, RowView> nextById = new HashMap<>();
        int idx = 0;
        for (Row r : m.rows) {
            if (r.type == Row.CUSTOMIZE) continue;
            RowView rv = mById.get(r.id);
            if (rv == null) {
                rv = new RowView(getContext(), mHost);
                mTrack.addView(rv, mTrack.indexOfChild(mCustomizeHolder));
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
        // keep track children in model order so drawing order matches
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
        if (firstBind) s = mRows.isEmpty() ? 0 : 1; // Google TV default: "Your apps" #1
        else if (onCustomize) s = mRows.size() + 1;
        else if (focusedRow != null && mById.containsKey(focusedRow)) s = mRows.indexOf(mById.get(focusedRow)) + 1;
        else s = Math.min(mSection, mRows.size() + 1);
        if (!mHasHero && s == 0) s = 1;
        mTrack.requestLayout();
        final int fs = s;
        mTrack.post(() -> {
            setSection(fs, false, true);
            updateNear();
        });
    }

    private RowView currentRow() {
        int i = mSection - 1;
        return i >= 0 && i < mRows.size() ? mRows.get(i) : null;
    }

    // ------------------------------------------------------------------ focus

    @Override
    public void focusIn() {
        mActive = true;
        setSection(mHasHero ? 0 : 1, true, true);
    }

    /** HOME pressed on Home: back to the top, focus "Your apps" #1 (SPEC 12). */
    public void focusFirstApp() {
        mActive = true;
        RowView r = mRows.isEmpty() ? null : mRows.get(0);
        if (r != null) r.focusIndex(0);
        setSection(r != null ? 1 : 0, true, true);
    }

    @Override
    public void focusOut() {
        mActive = false;
        applyFocus();
    }

    private void setSection(int s, boolean animate, boolean force) {
        int max = mRows.size() + 1;
        s = Math.max(mHasHero ? 0 : 1, Math.min(s, max));
        if (s == mSection && !force) return;
        mSection = s;
        applyFocus();
        applyScroll(animate);
        updateNear();
    }

    private void applyFocus() {
        mHero.focusOut();
        if (mActive && mSection == 0) mHero.focusIn();
        for (int i = 0; i < mRows.size(); i++) mRows.get(i).setActive(mActive && mSection == i + 1);
        mCustomize.setFocusState(mActive && mSection == mRows.size() + 1, true);
        if (mActive && mSection == 0) {
            Card k = mHero.current();
            mHost.onFocusArt(k != null ? k.image : null, k != null ? k.pkg : null);
        } else if (mActive && mSection == mRows.size() + 1) {
            mHost.onFocusArt(null, null);
        }
    }

    private int sectionTop(int s) {
        if (s <= 0) return 0;
        if (s <= mRows.size()) return mRows.get(s - 1).getTop();
        return mCustomize.getTop() - Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP);
    }

    private void applyScroll(boolean animate) {
        boolean browse = mSection >= 2;
        float y = browse ? -(sectionTop(mSection) - Theme.px(Theme.FOCUS_LINE_Y)) : 0;
        if (!mHasHero && mSection <= 1) y = -(sectionTop(1) - Theme.px(Theme.FOCUS_LINE_Y + 80));
        mTrack.animate().cancel();
        mHero.animate().cancel();
        // half-speed parallax while it leaves; it fades out completely so it never sits under a row
        // (the ambient backdrop keeps the art's colour behind the rows)
        float heroY = -y * 0.5f;
        float heroA = browse ? 0f : 1f;
        if (browse) mHero.pauseTrailer();
        if (browse != mBrowse) {
            mBrowse = browse;
            mHost.onBrowseMode(browse);
        }
        if (heroA > 0 && mHasHero) mHero.setVisibility(VISIBLE);
        boolean heroGone = browse;
        if (animate) {
            mTrack.animate().translationY(y).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
            mHero.animate().translationY(heroY).alpha(heroA).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED)
                    .withEndAction(() -> {
                        if (heroGone) mHero.setVisibility(INVISIBLE);
                    }).start();
        } else {
            mTrack.setTranslationY(y);
            mHero.setTranslationY(heroY);
            mHero.setAlpha(heroA);
            if (heroGone) mHero.setVisibility(INVISIBLE);
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
                if (mSection == 0 || (mSection == 1 && !mHasHero)) return false;
                setSection(mSection - 1, true, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mSection < mRows.size() + 1) setSection(mSection + 1, true, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mSection == 0) return mHero.onKey(keyCode, e);
                if (row != null) row.onKey(keyCode, e);
                return true;
            default:
                if (isOk(keyCode)) {
                    if (mSection == 0) return mHero.onKey(keyCode, e);
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
        else if (mSection == 0 && mHero.current() != null && mHero.current().kind == Card.PROGRAM) mHost.onCardMenu(mHero.current(), null);
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
        return mSection <= 0 || (!mHasHero && mSection == 1);
    }

    public boolean inTopMode() {
        return !mBrowse;
    }

    public boolean heroFocused() {
        return mActive && mSection == 0;
    }

    @Override
    public void scrollToTop(boolean animate) {
        setSection(mHasHero ? 0 : 1, animate, true);
    }

    @Override
    public void setShown(boolean shown) {
        mShown = shown;
        updateNear();
        if (!shown) mHero.pauseTrailer();
    }

    @Override
    public void trim() {
        mHero.release();
        for (RowView r : mRows) r.setNear(false);
    }

    @Override
    public void reload() {
        mHero.reload();
        updateNear();
    }

    /** First full frame of art: hero and the first two rows (reportFullyDrawn). */
    public boolean artReady() {
        if (!mHero.artReady()) return false;
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
        mTrack.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        mTrack.layout(0, 0, r - l, mTrack.getMeasuredHeight());
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
            mHero.measure(exact, un);
            int y = mHasHero ? Theme.px(Theme.FIRST_ROW_Y) : Theme.px(Theme.FIRST_ROW_Y - 420);
            for (RowView r : mRows) {
                r.measure(exact, un);
                y += r.getMeasuredHeight();
            }
            mCustomize.measure(un, un);
            y += Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP) + mCustomize.getMeasuredHeight() + Theme.px(400);
            setMeasuredDimension(w, y);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            mHero.layout(0, 0, w, mHero.getMeasuredHeight());
            int y = mHasHero ? Theme.px(Theme.FIRST_ROW_Y) : Theme.px(Theme.FIRST_ROW_Y - 420);
            for (RowView rv : mRows) {
                rv.layout(0, y, w, y + rv.getMeasuredHeight());
                y += rv.getMeasuredHeight();
            }
            y += Theme.px(Theme.ROW_TITLE_H + Theme.ROW_TITLE_GAP);
            int cw = mCustomize.getMeasuredWidth();
            int x = Theme.rtl(this) ? w - Theme.px(Theme.MARGIN) - cw : Theme.px(Theme.MARGIN);
            mCustomize.layout(x, y, x + cw, y + mCustomize.getMeasuredHeight());
        }
    }
}
