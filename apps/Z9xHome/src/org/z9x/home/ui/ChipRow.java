package org.z9x.home.ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A titled row of pill chips ("Search in", "Recent searches") with manual focus and h-scroll. */
public class ChipRow extends ViewGroup {
    public static final class Chip {
        public int icon;
        public android.graphics.drawable.Drawable drawable;
        public String label;
        public Object tag;

        public Chip(int icon, String label, Object tag) {
            this.icon = icon;
            this.label = label;
            this.tag = tag;
        }
    }

    public interface Listener {
        void onChip(ChipRow row, Chip chip);
    }

    private final TextView mTitle;
    private final ViewGroup mTrack;
    private final ArrayList<PillButton> mPills = new ArrayList<>();
    private final ArrayList<Chip> mChips = new ArrayList<>();
    private final Listener mL;
    private int mFocus;
    private boolean mActive;

    public ChipRow(Context c, CharSequence title, Listener l) {
        super(c);
        mL = l;
        setClipChildren(false);
        mTitle = new TextView(c);
        Theme.text(mTitle, 30, Theme.MEDIUM, (Theme.TEXT1 & 0x00FFFFFF) | 0xE0000000);
        mTitle.setText(title);
        mTitle.setSingleLine(true);
        addView(mTitle);
        mTrack = new ViewGroup(c) {
            @Override
            protected void onMeasure(int wms, int hms) {
                int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                for (PillButton p : mPills) p.measure(un, un);
                setMeasuredDimension(MeasureSpec.getSize(wms), Theme.px(64));
            }

            @Override
            protected void onLayout(boolean changed, int l2, int t, int r, int b) {
                int w = r - l2;
                int x = Theme.px(Theme.MARGIN);
                boolean rtl = Theme.rtl(this);
                for (PillButton p : mPills) {
                    int pw = p.getMeasuredWidth();
                    int lx = rtl ? w - x - pw : x;
                    p.layout(lx, 0, lx + pw, p.getMeasuredHeight());
                    x += pw + Theme.px(24);
                }
            }
        };
        mTrack.setClipChildren(false);
        addView(mTrack);
    }

    public void setChips(List<Chip> chips) {
        mChips.clear();
        mChips.addAll(chips);
        mTrack.removeAllViews();
        mPills.clear();
        for (Chip ch : chips) {
            PillButton p = new PillButton(getContext(), 64, 26, PillButton.STYLE_FILLED).label(ch.label);
            if (ch.drawable != null) p.icon(ch.drawable);
            else if (ch.icon != 0) p.icon(ch.icon);
            p.maxWidth(Theme.px(440));
            mPills.add(p);
            mTrack.addView(p);
        }
        mFocus = Math.min(mFocus, Math.max(0, mPills.size() - 1));
        applyFocus();
        requestLayout();
    }

    public boolean isEmpty() {
        return mChips.isEmpty();
    }

    public Chip focusedChip() {
        return mFocus < mChips.size() ? mChips.get(mFocus) : null;
    }

    public void setActive(boolean a) {
        mActive = a;
        applyFocus();
    }

    private void applyFocus() {
        for (int i = 0; i < mPills.size(); i++) mPills.get(i).setFocusState(mActive && i == mFocus, true);
        scrollToFocus();
    }

    private void scrollToFocus() {
        if (mPills.isEmpty() || mFocus >= mPills.size()) return;
        PillButton p = mPills.get(mFocus);
        int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        float tx = mTrack.getTranslationX();
        boolean rtl = Theme.rtl(this);
        float left = p.getLeft() + tx, right = p.getRight() + tx;
        int m = Theme.px(Theme.MARGIN);
        if (!rtl) {
            if (right > w - m) tx -= right - (w - m);
            if (left + 0 < m) tx += m - left;
        } else {
            if (left < m) tx += m - left;
            if (right > w - m) tx -= right - (w - m);
        }
        mTrack.animate().translationX(tx).setDuration(Theme.ROW_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
    }

    public boolean onKey(int keyCode, KeyEvent e) {
        boolean rtl = Theme.rtl(this);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int dir = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl ? 1 : -1;
            int to = mFocus + dir;
            if (to >= 0 && to < mPills.size()) {
                mFocus = to;
                applyFocus();
            }
            return true;
        }
        if (PageForYou.isOk(keyCode)) {
            Chip ch = focusedChip();
            if (ch != null) mL.onChip(this, ch);
            return true;
        }
        return false;
    }

    public int rowHeight() {
        return Theme.px(36 + 16 + 64 + 56);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms);
        mTitle.measure(MeasureSpec.makeMeasureSpec(w - 2 * Theme.px(Theme.MARGIN), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Theme.px(36), MeasureSpec.EXACTLY));
        mTrack.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Theme.px(64), MeasureSpec.EXACTLY));
        setMeasuredDimension(w, rowHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int tx = Theme.rtl(this) ? w - Theme.px(Theme.MARGIN) - mTitle.getMeasuredWidth() : Theme.px(Theme.MARGIN);
        mTitle.layout(tx, 0, tx + mTitle.getMeasuredWidth(), mTitle.getMeasuredHeight());
        int y = Theme.px(36 + 16);
        mTrack.layout(0, y, w, y + mTrack.getMeasuredHeight());
    }
}
