package org.z9x.setup.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Rounded button: light fill when focused, quiet outline (or surface fill when primary) otherwise. */
public class Pill extends TextView {
    private boolean mBusy;

    public Pill(Context c, CharSequence text, boolean primary) {
        super(c);
        setText(text);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(28));
        setTypeface(Ui.medium());
        setIncludeFontPadding(false);
        setTextColor(Ui.focusText());
        setMinWidth(Ui.px(200));
        setMinHeight(Ui.px(72));
        setPadding(Ui.px(44), 0, Ui.px(44), 0);
        setBackground(primary ? Ui.focusBg(Ui.SURFACE2, 36, 0) : Ui.focusBg(0x00000000, 36, 0x33FFFFFF));
    }

    /** A busy pill keeps its focus but ignores clicks and looks dimmed. */
    public void setBusy(boolean busy) {
        mBusy = busy;
        setAlpha(busy ? 0.45f : 1f);
    }

    public boolean isBusy() { return mBusy; }

    @Override
    public boolean performClick() {
        if (mBusy) return true;
        return super.performClick();
    }
}
