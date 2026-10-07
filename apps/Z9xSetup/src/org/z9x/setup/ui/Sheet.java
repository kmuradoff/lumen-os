package org.z9x.setup.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Modal card over the wizard (dialogs and pickers): dark scrim, surface card 860 px wide, title,
 * optional text, a body (list or field) and buttons. The host blocks focus behind it and routes
 * BACK to {@link #dismiss()}.
 */
public class Sheet extends FrameLayout {
    public final LinearLayout card;
    public final LinearLayout body;
    public final LinearLayout buttons;
    private Runnable mOnDismiss;
    private boolean mDismissed;

    public Sheet(Context c, CharSequence title, CharSequence text) {
        super(c);
        setBackgroundColor(0xB3000000);
        setClickable(true);
        card = Ui.vbox(c);
        card.setBackground(Ui.cardOpaque(36));
        card.setPadding(Ui.px(48), Ui.px(44), Ui.px(48), Ui.px(40));
        TextView t = Ui.text(c, 40, Ui.TEXT, Ui.regular());
        t.setText(title);
        card.addView(t);
        if (text != null) {
            TextView b = Ui.body(c, text);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.pxf(28));
            card.addView(b, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));
        }
        body = Ui.vbox(c);
        card.addView(body, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));
        buttons = Ui.hbox(c);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        card.addView(buttons, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        LayoutParams lp = new LayoutParams(Ui.px(860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(card, lp);
    }

    public Pill addButton(CharSequence label, boolean primary, View.OnClickListener l) {
        Pill p = new Pill(getContext(), label, primary);
        p.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (buttons.getChildCount() > 0) lp.setMarginStart(Ui.px(20));
        buttons.addView(p, lp);
        return p;
    }

    /** A scrollable list (max 640 px high) inside the body; returns the row container. */
    public LinearLayout addList() {
        ScrollView sv = new ScrollView(getContext()) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Ui.px(640), MeasureSpec.AT_MOST));
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(Ui.px(40));
        sv.setClipToPadding(false);
        LinearLayout list = Ui.vbox(getContext());
        list.setPadding(0, Ui.px(4), 0, Ui.px(4));
        sv.addView(list);
        body.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return list;
    }

    public void setOnDismiss(Runnable r) { mOnDismiss = r; }

    public void show(ViewGroup parent, View focus) {
        parent.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        animate().alpha(1f).setDuration(180).setInterpolator(Ui.decel()).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(Ui.decel()).start();
        if (focus != null) focus.requestFocus();
        else if (buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
    }

    public boolean isDismissed() { return mDismissed; }

    public void dismiss() {
        if (mDismissed) return;
        mDismissed = true;
        animate().alpha(0f).setDuration(140).withEndAction(() -> {
            ViewGroup p = (ViewGroup) getParent();
            if (p != null) p.removeView(this);
        }).start();
        if (mOnDismiss != null) mOnDismiss.run();
    }
}
