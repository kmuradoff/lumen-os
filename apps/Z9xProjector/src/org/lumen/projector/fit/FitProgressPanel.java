package org.lumen.projector.fit;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lumen.projector.R;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Theme;

/**
 * Fit to my screen size: the small progress card at the bottom of the picture while a fit or a calibration runs
 * ("Measuring the distance", "Zoom: step 3 of 9", "Focusing"). Not shown during the auto keystone (the camera
 * must see the pattern alone). BACK closes it and stops the run after the running step. Main thread.
 */
final class FitProgressPanel extends Panel {
    private final Runnable onBack;
    private TextView text;
    private CharSequence pending = "";
    private boolean closedByCode;

    FitProgressPanel(Runnable onBack) { this.onBack = onBack; }

    void setText(CharSequence s) {
        pending = s;
        if (text != null) text.setText(s);
    }

    /** Closes without counting as BACK. */
    void close() {
        closedByCode = true;
        dismiss();
    }

    @Override protected long autoHideMs() { return 0; }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        WindowManager.LayoutParams lp = OverlayHost.params(true, Theme.px(c, Theme.DIALOG_WIDTH),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.y = Theme.px(c, 60);
        return lp;
    }

    @Override
    protected View onCreateView(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Theme.px(c, 28);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.NOTIFY_BG);
        bg.setCornerRadius(Theme.pxf(c, Theme.PANEL_RADIUS));
        card.setBackground(bg);
        text = label(c, pending, 32, Theme.TEXT);
        card.addView(text);
        card.addView(label(c, c.getString(R.string.fit_p_back), 24, Theme.TEXT_DIM));
        card.setFocusable(true);
        return card;
    }

    private static TextView label(Context c, CharSequence s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    @Override
    protected boolean onBack() {
        closedByCode = true;
        if (onBack != null) onBack.run();
        return false;
    }

    @Override
    protected void onDismissed() {
        if (!closedByCode && onBack != null) onBack.run();     // closed by another panel: stop too
    }
}
