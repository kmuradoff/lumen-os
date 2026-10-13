package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * Centred XGIMI-style confirmation card (title, message, up to two buttons). It is a Panel, so it
 * replaces the current panel; to confirm INSIDE the quick panel instead, push
 * {@link #confirmPage} onto the PagedPanel. The result callback runs on the main thread once:
 * ok = true (positive button) or false (negative button, BACK, timeout, closed).
 */
public class DialogPanel extends Panel {
    public interface Result { void onResult(boolean ok); }

    private final CharSequence title, message, okLabel, cancelLabel;
    private final Result result;
    private boolean delivered;
    private long autoHide = 30_000;

    /** cancelLabel null = single-button notice. */
    public DialogPanel(CharSequence title, CharSequence message, CharSequence okLabel,
                       CharSequence cancelLabel, Result result) {
        this.title = title;
        this.message = message;
        this.okLabel = okLabel;
        this.cancelLabel = cancelLabel;
        this.result = result;
    }

    public DialogPanel setAutoHideMs(long ms) { autoHide = ms; return this; }

    @Override
    protected long autoHideMs() { return autoHide; }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        return OverlayHost.params(true, Theme.px(c, Theme.DIALOG_WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
    }

    @Override
    protected View onCreateView(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.NOTIFY_BG);
        bg.setCornerRadius(Theme.pxf(c, Theme.PANEL_RADIUS));
        bg.setStroke(Theme.px(c, Theme.STROKE), Theme.PANEL_STROKE);
        card.setBackground(bg);
        int pad = Theme.px(c, 36);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams wrap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (title != null) card.addView(new TextRow(c, title, Theme.PANEL_TITLE_SIZE * 0.9f, Theme.TEXT), wrap);
        if (message != null) card.addView(new TextRow(c, message), wrap);
        View spacer = new View(c);
        card.addView(spacer, new LinearLayout.LayoutParams(1, Theme.px(c, 16)));
        NavRow ok = new NavRow(c, okLabel, () -> finish(true)).setChevron(false);
        card.addView(ok, wrap);
        if (cancelLabel != null) {
            NavRow cancel = new NavRow(c, cancelLabel, () -> finish(false)).setChevron(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap);
            lp.topMargin = Theme.px(c, Theme.ROW_GAP);
            card.addView(cancel, lp);
            // Safer default: focus "Cancel" for risky actions (Performance mode, projection flip).
            cancel.post(cancel::requestFocus);
        } else {
            ok.post(ok::requestFocus);
        }
        return card;
    }

    @Override
    protected void onShown(View root) {
        root.setAlpha(0f);
        root.setScaleX(0.96f);
        root.setScaleY(0.96f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Theme.PANEL_FADE_MS)
                .setInterpolator(Theme.panelInterpolator()).start();
    }

    @Override
    protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(Theme.PANEL_EXIT_MS).withEndAction(end).start();
    }

    @Override
    protected void onDismissed() {
        deliver(false);
    }

    private void finish(boolean ok) {
        deliver(ok);
        dismiss();
    }

    private void deliver(boolean ok) {
        if (delivered) return;
        delivered = true;
        if (result != null) result.onResult(ok);
    }

    /**
     * The same confirmation as a page inside a PagedPanel: message + OK + Cancel rows. OK runs
     * {@code onOk} and pops the page; Cancel pops. Focus starts on Cancel.
     */
    public static Page confirmPage(Context c, PagedPanel panel, CharSequence title, CharSequence message,
                                   CharSequence okLabel, CharSequence cancelLabel, Runnable onOk) {
        Page p = new Page(title);
        if (message != null) p.add(new TextRow(c, message));
        p.add(new NavRow(c, okLabel, () -> {
            panel.pop();
            if (onOk != null) onOk.run();
        }).setChevron(false));
        p.add(new NavRow(c, cancelLabel, panel::pop).setChevron(false));
        p.setInitialFocus(message != null ? 2 : 1);
        return p;
    }
}
