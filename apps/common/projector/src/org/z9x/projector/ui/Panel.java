package org.z9x.projector.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * One focusable overlay window managed by {@link OverlayHost} (quick panel, source chooser,
 * dialogs). At most one Panel is shown at a time; showing another one dismisses the current one.
 *
 * Lifecycle (all on the main thread):
 *   OverlayHost.show(panel) -> onCreateView(ctx) once per show -> window added -> onShown(root)
 *   -> keys: every key resets the auto-hide timer; onKeyEvent() sees it first; BACK (UP) calls
 *      onBack() and dismisses the panel when that returns false
 *   -> dismiss(): animateOut() then window removed -> onDismissed().
 * The panel also closes on: auto-hide timeout, ACTION_CLOSE_SYSTEM_DIALOGS (HOME, assistant,
 * recents), screen off, and losing window focus to another window.
 *
 * Rules: never do HAL I/O here (use org.z9x.projector.Hal), keep onCreateView cheap (build views in
 * code, no XML inflation in hot paths), no blocking work on the main thread.
 */
public abstract class Panel {
    /** Default auto-hide: stock SettingActionContract.SHOW_TIME = 60000 (RESULT_quicksettings). */
    public static final long DEFAULT_AUTO_HIDE_MS = 60_000;

    OverlayHost host;
    View root;
    boolean showing;
    /** Incremented by OverlayHost on every show; a late exit of an older show checks it. */
    int showSerial;

    /** Builds the content. ctx is the application context (themed for framework widgets). */
    protected abstract View onCreateView(Context ctx);

    /** Window parameters. Default: right side, full height, 0.34 x W wide, focusable. */
    protected WindowManager.LayoutParams onCreateLayoutParams(Context ctx) {
        int w = Math.round(ctx.getResources().getDisplayMetrics().widthPixels * Theme.PANEL_WIDTH_FRACTION);
        return OverlayHost.params(true, w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.TOP);
    }

    /** Milliseconds without a key after which the panel closes; 0 = never. */
    protected long autoHideMs() { return DEFAULT_AUTO_HIDE_MS; }

    /** BACK pressed. Return true when consumed (e.g. went up one page); false closes the panel. */
    protected boolean onBack() { return false; }

    /** Every key event before the view hierarchy gets it. Return true to consume. */
    protected boolean onKeyEvent(KeyEvent ev) { return false; }

    /** Window added and laid out: start the enter animation. Default: slide in from the right. */
    protected void onShown(View root) {
        float dx = Theme.pxf(root.getContext(), Theme.PANEL_SLIDE);
        root.setTranslationX(dx);
        root.setAlpha(0f);
        root.animate().translationX(0f).setDuration(Theme.PANEL_ENTER_MS)
                .setInterpolator(Theme.panelInterpolator()).withLayer().start();
        ObjectAnimator fade = ObjectAnimator.ofFloat(root, View.ALPHA, 0f, 1f);
        fade.setDuration(Theme.PANEL_FADE_MS);
        fade.start();
    }

    /** Exit animation; must call {@code end} exactly once. Default: slide out to the right. */
    protected void animateOut(View root, Runnable end) {
        float dx = Theme.pxf(root.getContext(), Theme.PANEL_SLIDE);
        root.animate().cancel();
        root.animate().translationX(dx).alpha(0f).setDuration(Theme.PANEL_EXIT_MS)
                .setInterpolator(Theme.panelInterpolator()).withLayer().withEndAction(end).start();
    }

    /** Window removed. Release listeners / pending HAL result callbacks here. */
    protected void onDismissed() {}

    // ------------------------------------------------------------------ final API
    public final boolean isShowing() { return showing; }

    /** Close this panel (animated). Safe to call twice. Main thread. */
    public final void dismiss() {
        if (host != null) host.dismiss(this, true);
    }

    /** Restart the auto-hide timer (e.g. after an async value change the user is watching). */
    public final void resetAutoHide() {
        if (host != null) host.resetAutoHide(this);
    }

    public final View rootView() { return root; }

    /** Format helper: a translucent overlay window format. */
    static int format() { return PixelFormat.TRANSLUCENT; }
}
