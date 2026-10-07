package org.z9x.setup.steps;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.z9x.setup.SetupActivity;
import org.z9x.setup.ui.Ui;

/**
 * One wizard step: a View tree swapped inside the single SetupActivity window. Content is always
 * built fresh by {@link #createContent()} (also after a language switch), so steps keep their
 * durable state in fields or SetupState, never only in Views.
 */
public abstract class Step {
    /** Title left, content right (most steps). */
    public static final int SPLIT = 0;
    /** Title on top, content across the full width (launcher cards). */
    public static final int WIDE = 1;
    /** Full screen, no wordmark/title column (picture grid). */
    public static final int FULL = 2;

    protected final SetupActivity host;
    private FrameLayout mPages;
    private View mPage;

    protected Step(SetupActivity host) {
        this.host = host;
    }

    public abstract String id();

    /** Shown at all (cheap, synchronous; may use the host's cached async state). */
    public boolean available() { return true; }

    /** Already done (for example the remote is connected): skipped silently, the dot shows done. */
    public boolean autoSkip() { return false; }

    public int layout() { return SPLIT; }

    public abstract CharSequence title();

    public CharSequence subtitle() { return null; }

    /** Optional view under the subtitle in the left column (SPLIT only). */
    public View leftExtra() { return null; }

    public abstract View createContent();

    /** View to focus after the transition; null = first focusable of the content. */
    public View initialFocus() { return null; }

    public void onEnter() {}

    public void onExit() {}

    /** BACK inside the step (sub-page, busy state). True = handled. */
    public boolean onBack() { return false; }

    public void onResume() {}

    public void onPause() {}

    /** Default network or validation changed (host NetMon). */
    public void onNetChanged() {}

    /** SETUP_STATE event from Z9xProjector (lossy; steps also poll). */
    public void onBridgeEvent(Bundle extras) {}

    // ------------------------------------------------------------------ helpers

    protected Context ctx() { return host; }

    protected String s(int id) { return host.getString(id); }

    protected String s(int id, Object... args) { return host.getString(id, args); }

    /** A page container for steps with sub-pages (list -> password -> status). */
    protected FrameLayout pages() {
        mPages = new FrameLayout(host);
        mPage = null;
        return mPages;
    }

    /** Swap the visible sub-page with a short cross-fade and move focus into it. */
    protected void showPage(View page, View focus) {
        if (mPages == null) return;
        View old = mPage;
        mPage = page;
        if (old != null && old != page) {
            old.animate().cancel();
            if (old instanceof ViewGroup) ((ViewGroup) old).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            old.animate().alpha(0f).setDuration(120).withEndAction(() -> mPages.removeView(old)).start();
        }
        if (page.getParent() == null) {
            ViewGroup.LayoutParams lp = page.getLayoutParams();
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_VERTICAL);
            }
            mPages.addView(page, lp);
        }
        page.setAlpha(0f);
        page.setTranslationY(Ui.pxf(16));
        page.animate().alpha(1f).translationY(0f).setDuration(200).setStartDelay(old == null ? 0 : 60)
                .setInterpolator(Ui.decel()).start();
        View f = focus != null ? focus : page;
        f.post(() -> {
            if (f.isAttachedToWindow()) f.requestFocus();
        });
    }

    protected View currentPage() { return mPage; }
}
