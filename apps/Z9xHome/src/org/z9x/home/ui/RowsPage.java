package org.z9x.home.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * A page made of a Prata headline and rows (the Projector tab). Vertical scrolling translates
 * the content so the focused row stays fully visible.
 */
public class RowsPage extends ViewGroup implements Page {
    public interface RowsSource {
        List<Row> rows(HomeModel m);
    }

    private final Page.Host mHost;
    private final TextView mHeadline;
    private final ViewGroup mContent;
    private final ArrayList<RowView> mRows = new ArrayList<>();
    private final RowsSource mSource;
    private int mFocus;
    private boolean mActive, mShown = true;
    private final int mTopY;

    public RowsPage(Context c, Page.Host host, CharSequence headline, int topYDesign, RowsSource src) {
        super(c);
        mHost = host;
        mSource = src;
        mTopY = Theme.px(topYDesign);
        setClipChildren(false);
        mContent = new ViewGroup(c) {
            @Override
            protected void onMeasure(int wms, int hms) {
                int w = MeasureSpec.getSize(wms);
                int exact = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
                int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                int y = mTopY;
                if (mHeadline != null && mHeadline.getVisibility() == VISIBLE) {
                    mHeadline.measure(MeasureSpec.makeMeasureSpec(w - 2 * Theme.px(Theme.MARGIN), MeasureSpec.EXACTLY), un);
                    y += mHeadline.getMeasuredHeight() + Theme.px(40);
                }
                for (RowView r : mRows) {
                    r.measure(exact, un);
                    y += r.getMeasuredHeight();
                }
                setMeasuredDimension(w, y + Theme.px(200));
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int w = r - l;
                int y = mTopY;
                if (mHeadline != null && mHeadline.getVisibility() == VISIBLE) {
                    int hx = Theme.rtl(this) ? w - Theme.px(Theme.MARGIN) - mHeadline.getMeasuredWidth() : Theme.px(Theme.MARGIN);
                    mHeadline.layout(hx, y, hx + mHeadline.getMeasuredWidth(), y + mHeadline.getMeasuredHeight());
                    y += mHeadline.getMeasuredHeight() + Theme.px(40);
                }
                for (RowView rv : mRows) {
                    rv.layout(0, y, w, y + rv.getMeasuredHeight());
                    y += rv.getMeasuredHeight();
                }
            }
        };
        mContent.setClipChildren(false);
        addView(mContent);
        if (headline != null) {
            mHeadline = new TextView(c);
            Theme.text(mHeadline, 64, Theme.DISPLAY, Theme.TEXT1);
            mHeadline.setText(headline);
            mHeadline.setSingleLine(true);
            mHeadline.setEllipsize(TextUtils.TruncateAt.END);
            mContent.addView(mHeadline);
        } else {
            mHeadline = null;
        }
    }

    @Override
    public View view() {
        return this;
    }

    @Override
    public void bind(HomeModel m) {
        setRows(mSource.rows(m));
    }

    public void setRows(List<Row> rows) {
        String fid = mFocus < mRows.size() ? mRows.get(mFocus).row().id : null;
        ArrayList<RowView> next = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.cards.isEmpty()) continue;
            RowView rv = null;
            for (RowView o : mRows) if (o.row().id.equals(r.id)) rv = o;
            if (rv == null) {
                rv = new RowView(getContext(), mHost);
                mContent.addView(rv);
            }
            rv.setShowTitle(!r.title.isEmpty()); // a page with one row (Projector) needs no heading
            rv.setRow(r, true);
            next.add(rv);
        }
        for (RowView o : mRows) {
            if (!next.contains(o)) {
                o.setActive(false);
                o.setNear(false);
                mContent.removeView(o);
            }
        }
        mRows.clear();
        mRows.addAll(next);
        int f = 0;
        for (int i = 0; i < mRows.size(); i++) if (mRows.get(i).row().id.equals(fid)) f = i;
        mFocus = Math.min(f, Math.max(0, mRows.size() - 1));
        for (RowView rv : mRows) rv.setNear(mShown);
        mContent.requestLayout();
        mContent.post(() -> {
            applyFocus();
            applyScroll(false);
        });
    }

    public boolean isEmpty() {
        return mRows.isEmpty();
    }

    public RowView focusedRow() {
        return mFocus < mRows.size() ? mRows.get(mFocus) : null;
    }

    @Override
    public void focusIn() {
        mActive = true;
        mFocus = 0;
        applyFocus();
        applyScroll(true);
    }

    @Override
    public void focusOut() {
        mActive = false;
        applyFocus();
    }

    private void applyFocus() {
        for (int i = 0; i < mRows.size(); i++) mRows.get(i).setActive(mActive && i == mFocus);
    }

    private void applyScroll(boolean animate) {
        if (mRows.isEmpty()) return;
        RowView r = mRows.get(mFocus);
        int top = r.getTop(), bottom = r.getBottom() - Theme.px(Theme.ROW_CAPTION_H) + Theme.px(60);
        int h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        float y = 0;
        if (bottom > h - Theme.px(40)) y = -(bottom - (h - Theme.px(40)));
        if (mFocus == 0) y = 0;
        if (animate) mContent.animate().translationY(y).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
        else mContent.setTranslationY(y);
    }

    @Override
    public boolean onKey(int keyCode, KeyEvent e) {
        RowView row = focusedRow();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mFocus == 0) return false;
                mFocus--;
                applyFocus();
                applyScroll(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mFocus < mRows.size() - 1) {
                    mFocus++;
                    applyFocus();
                    applyScroll(true);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row != null) row.onKey(keyCode, e);
                return true;
            default:
                if (PageForYou.isOk(keyCode)) {
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

    @Override
    public void onMenu() {
        RowView row = focusedRow();
        if (row != null && row.focusedCard() != null) mHost.onCardMenu(row.focusedCard(), row);
    }

    @Override
    public boolean onBack() {
        if (mFocus > 0) {
            mFocus = 0;
            applyFocus();
            applyScroll(true);
            return true;
        }
        return false;
    }

    @Override
    public void scrollToTop(boolean animate) {
        mFocus = 0;
        applyFocus();
        applyScroll(animate);
    }

    @Override
    public void setShown(boolean shown) {
        mShown = shown;
        for (RowView r : mRows) r.setNear(shown);
    }

    @Override
    public void trim() {
        for (RowView r : mRows) r.setNear(false);
    }

    @Override
    public void reload() {
        for (RowView r : mRows) r.setNear(mShown);
    }

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
}
