package org.lumen.tvinput;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Harness only (tools/play6/notify_shots): the real BrandCard (state card 28 sp, banner 26 sp, as
 * PassthroughActivity makes them), off screen or in the harness window, with its layout facts. Never shipped.
 */
public final class BrandProbe {
    public final View view;
    public final TextView title, line;

    public BrandProbe(Context c, boolean banner) {
        BrandCard b = new BrandCard(c, banner ? 26 : 28);
        view = b;
        ViewGroup texts = (ViewGroup) b.getChildAt(1);
        title = (TextView) texts.getChildAt(0);
        line = (TextView) texts.getChildAt(1);
    }

    public void set(String t, String l, int kind, int accent) {
        ((BrandCard) view).set(t, l, kind, accent);
        view.setVisibility(View.VISIBLE);
    }

    /** Off screen: measured as PassthroughActivity's WRAP_CONTENT child of a 1920 x 1080 window, laid out at 0, 0. */
    public void measure() {
        view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    public static int ellipsis(TextView v) {
        Layout l = v.getLayout();
        int n = 0;
        if (l != null) for (int i = 0; i < l.getLineCount(); i++) n += l.getEllipsisCount(i);
        return n;
    }

    public static int lines(TextView v) {
        Layout l = v.getLayout();
        return v.getVisibility() != View.VISIBLE || l == null ? 0 : l.getLineCount();
    }

    /** Width of the view minus its widest line (px): the empty band at the end of a text view. */
    public static int slack(TextView v) {
        Layout l = v.getLayout();
        if (l == null || v.getVisibility() != View.VISIBLE) return 0;
        float w = 0;
        for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineWidth(i));
        return Math.round(v.getWidth() - v.getCompoundPaddingLeft() - v.getCompoundPaddingRight() - w);
    }
}
