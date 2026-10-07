package org.z9x.setup.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * List row: [start icon] title / subtitle [end widget]. 84 px tall, radius 24, light fill on focus
 * (the XGIMI/TvSettings pill). Children use duplicateParentState so text and icons flip with focus.
 */
public class Row extends LinearLayout {
    public final TextView title;
    public final TextView sub;
    private View mStart;
    private View mEnd;
    private TextView mValue;
    private boolean mBusy;

    public Row(Context c, CharSequence titleText) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(true);
        setClickable(true);
        setMinimumHeight(Ui.px(84));
        setPadding(Ui.px(28), Ui.px(14), Ui.px(28), Ui.px(14));
        setBackground(Ui.focusBg(0x00000000, 24, 0));
        LinearLayout texts = Ui.vbox(c);
        texts.setDuplicateParentStateEnabled(true);
        title = Ui.text(c, 30, 0, Ui.regular());
        title.setTextColor(Ui.focusText());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setDuplicateParentStateEnabled(true);
        title.setText(titleText);
        sub = Ui.text(c, 22, 0, Ui.regular());
        sub.setTextColor(Ui.focusTextDim());
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setDuplicateParentStateEnabled(true);
        sub.setVisibility(GONE);
        texts.addView(title, Ui.lp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        texts.addView(sub, Ui.lpTop(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, 6));
        addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    }

    /** Denser variant for the picture card (64 px rows, 26 px text). */
    public Row compact() {
        setMinimumHeight(Ui.px(64));
        setPadding(Ui.px(24), Ui.px(8), Ui.px(24), Ui.px(8));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.pxf(26));
        return this;
    }

    public Row subtitle(CharSequence s) {
        sub.setText(s);
        sub.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
        return this;
    }

    public Row startIcon(View v, float sizeDesign) {
        if (mStart != null) removeView(mStart);
        mStart = v;
        v.setDuplicateParentStateEnabled(true);
        LayoutParams p = new LayoutParams(Ui.px(sizeDesign), Ui.px(sizeDesign));
        p.setMarginEnd(Ui.px(24));
        addView(v, 0, p);
        return this;
    }

    /** Replaces the end widget (check, switch, spinner, icon). */
    public Row end(View v, int w, int h) {
        if (mEnd != null) removeView(mEnd);
        mEnd = v;
        if (v != null) {
            v.setDuplicateParentStateEnabled(true);
            LayoutParams p = new LayoutParams(w, h);
            p.setMarginStart(Ui.px(20));
            addView(v, p);
        }
        return this;
    }

    /** A dim value text at the end (e.g. "Connected", "Change"). */
    public Row value(CharSequence s) {
        if (mValue == null) {
            mValue = Ui.text(getContext(), 26, 0, Ui.regular());
            mValue.setTextColor(Ui.focusTextDim());
            mValue.setSingleLine(true);
            mValue.setEllipsize(TextUtils.TruncateAt.END);
            mValue.setMaxWidth(Ui.px(380));
            mValue.setDuplicateParentStateEnabled(true);
            LayoutParams p = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            p.setMarginStart(Ui.px(20));
            addView(mValue, mEnd == null ? getChildCount() : indexOfChild(mEnd), p);
        }
        mValue.setText(s);
        mValue.setVisibility(s == null || s.length() == 0 ? GONE : VISIBLE);
        return this;
    }

    public void setBusy(boolean busy) {
        mBusy = busy;
        setAlpha(busy ? 0.45f : 1f);
    }

    @Override
    public boolean performClick() {
        if (mBusy) return true;
        return super.performClick();
    }
}
