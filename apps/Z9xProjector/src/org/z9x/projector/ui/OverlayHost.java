package org.z9x.projector.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * Adds and removes the app's overlay windows from the persistent process (no Activity, so the app
 * underneath - video, HDMI TvView - is never paused; it only loses window focus).
 *
 * Window type TYPE_APPLICATION_OVERLAY (2038). The app holds SYSTEM_ALERT_WINDOW (appop granted,
 * live dumpsys) and INTERNAL_SYSTEM_WINDOW (signature, platform key), which keeps our windows visible
 * when another app sets HIDE_NON_SYSTEM_OVERLAY_WINDOWS (Lineage WindowState
 * setForceHideNonSystemOverlayWindowIfNeeded skips sessions that may add internal system windows).
 * A focusable window that belongs to no activity takes key focus
 * (DisplayContent.mFindFocusedWindow, RESULT_quicksettings verdict "confirmed").
 *
 * Two kinds of windows:
 *  - {@link Panel}: focusable, D-pad driven, ONE at a time, auto-hide timer reset by every key,
 *    BACK goes up / closes, closed on HOME/assistant (ACTION_CLOSE_SYSTEM_DIALOGS), screen off and
 *    focus loss. Media keys pressed while a panel has focus are forwarded to the active media
 *    session (AudioManager.dispatchMediaKeyEvent), because a bare WindowManager view has no
 *    PhoneFallbackEventHandler. Volume keys never reach us on TV (PhoneWindowManager routes them).
 *  - static windows ({@link #addStatic}): non-focusable full-screen or small windows owned by a
 *    module (AK overlay, notification card, eye-protection layer). The module adds/removes them.
 *
 * Main thread only (every public method checks and re-posts if needed where noted).
 */
public final class OverlayHost {
    private static final String TAG = "Z9xOverlay";
    private static OverlayHost sInstance;

    private final Context app;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<View> statics = new ArrayList<>();
    private Panel current;
    private final Runnable autoHide = () -> {
        Panel p = current;
        if (p != null) {
            Log.i(TAG, "auto-hide " + p.getClass().getSimpleName());
            dismiss(p, true);
        }
    };

    public static synchronized OverlayHost get(Context c) {
        if (sInstance == null) sInstance = new OverlayHost(c.getApplicationContext());
        return sInstance;
    }

    private OverlayHost(Context app) {
        this.app = app;
        this.wm = app.getSystemService(WindowManager.class);
        try {
            BroadcastReceiver close = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String reason = i.getStringExtra("reason");
                    // Our own Notify card never sends this; "assist"/"homekey"/"recentapps" do.
                    if (current != null) {
                        Log.i(TAG, "close system dialogs (" + reason + ")");
                        dismissAll(false);
                    }
                }
            };
            app.registerReceiver(close, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "close-dialogs receiver: " + t);
        }
    }

    public Context context() { return app; }

    // ================================================================== layout params
    /**
     * Overlay window params. focusable=true: takes D-pad focus (panels). focusable=false: neither
     * focusable nor touchable (notification card, AK overlay); keys keep going to the app below.
     */
    public static WindowManager.LayoutParams params(boolean focusable, int w, int h, int gravity) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (focusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.windowAnimations = 0;                      // we animate the content ourselves
        try { lp.setFitInsetsTypes(0); } catch (Throwable ignored) { }
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return lp;
    }

    /** Full-screen non-focusable params (AK overlay, key-less layers). */
    public static WindowManager.LayoutParams fullscreenParams(boolean focusable) {
        return params(focusable, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.START);
    }

    // ================================================================== panels
    /** The panel currently shown, or null. */
    public Panel current() { return current; }

    /**
     * Width in px taken at the right screen edge by the current panel window (its width plus its x
     * offset), or 0 when no panel is shown or it is not right-aligned. Main thread.
     */
    public int rightPanelWidth() {
        Panel p = current;
        if (p == null || !p.showing || p.root == null) return 0;
        ViewGroup.LayoutParams vlp = p.root.getLayoutParams();
        if (!(vlp instanceof WindowManager.LayoutParams)) return 0;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) vlp;
        int g = Gravity.getAbsoluteGravity(lp.gravity, View.LAYOUT_DIRECTION_LTR) & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (g != Gravity.RIGHT) return 0;
        int w = p.root.getWidth() > 0 ? p.root.getWidth() : lp.width;
        return w > 0 ? w + Math.max(0, lp.x) : 0;
    }

    /**
     * Shows {@code p}, replacing the current panel (one panel at a time). If {@code p} is already
     * showing it stays and its timer is reset. Returns false if the window could not be added.
     * Main thread.
     */
    public boolean show(Panel p) {
        if (!onMain("show")) { main.post(() -> show(p)); return true; }
        if (p == current && p.showing) { resetAutoHide(p); return true; }
        if (current != null) dismiss(current, false);
        try {
            View content = p.onCreateView(app);
            PanelRoot root = new PanelRoot(app, p);
            root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            WindowManager.LayoutParams lp = p.onCreateLayoutParams(app);
            lp.setTitle("Z9x" + p.getClass().getSimpleName());
            p.host = this;
            p.root = root;
            p.showSerial++;
            p.showing = true;
            current = p;
            wm.addView(root, lp);
            Notify.relayout(app, true);               // card left of the new panel and above it
            root.post(() -> {
                if (p.showing) {
                    try { p.onShown(root); } catch (Throwable t) { Log.w(TAG, "onShown: " + t); }
                    Notify.relayout(app, false);      // the measured width (WRAP_CONTENT panels)
                }
            });
            resetAutoHide(p);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot show " + p.getClass().getSimpleName(), t);
            p.showing = false;
            if (current == p) current = null;
            return false;
        }
    }

    /** Shows {@code p} or, if it is the current panel, dismisses it (gear / source key toggles). */
    public void toggle(Panel p) {
        if (p.showing && p == current) dismiss(p, true); else show(p);
    }

    /** Dismisses every panel (not static windows). Main thread. */
    public void dismissAll(boolean animate) {
        if (!onMain("dismissAll")) { main.post(() -> dismissAll(animate)); return; }
        if (current != null) dismiss(current, animate);
    }

    void dismiss(Panel p, boolean animate) {
        if (!onMain("dismiss")) { main.post(() -> dismiss(p, animate)); return; }
        if (!p.showing) return;
        p.showing = false;
        if (current == p) {
            current = null;
            main.removeCallbacks(autoHide);
        }
        final View root = p.root;
        final int serial = p.showSerial;
        Runnable remove = () -> {
            try {
                if (root != null && root.isAttachedToWindow()) wm.removeViewImmediate(root);
            } catch (Throwable t) {
                Log.w(TAG, "remove: " + t);
            }
            Notify.relayout(app, false);                  // card back to the screen edge
            // The same panel object may have been shown again while this exit animation ran (e.g.
            // Source pressed twice quickly): then the old show's onDismissed must not tear down the
            // new show's views and callbacks.
            if (p.showSerial != serial) {
                Log.i(TAG, p.getClass().getSimpleName() + " re-shown during its exit: old onDismissed skipped");
                return;
            }
            try { p.onDismissed(); } catch (Throwable t) { Log.w(TAG, "onDismissed: " + t); }
        };
        if (animate && root != null && root.isAttachedToWindow()) {
            final boolean[] done = {false};
            Runnable once = () -> { if (!done[0]) { done[0] = true; remove.run(); } };
            try {
                p.animateOut(root, once);
            } catch (Throwable t) {
                once.run();
                return;
            }
            main.postDelayed(once, Theme.PANEL_EXIT_MS + 300);   // safety net
        } else {
            remove.run();
        }
    }

    void resetAutoHide(Panel p) {
        if (p != current) return;
        main.removeCallbacks(autoHide);
        long ms = p.autoHideMs();
        if (ms > 0) main.postDelayed(autoHide, ms);
    }

    /** Screen went off: close panels at once (App's SCREEN_OFF hook). */
    public void onScreenOff() {
        dismissAll(false);
    }

    // ================================================================== static windows
    /** Adds a module-owned window (not a Panel). Returns false on failure. Main thread. */
    public boolean addStatic(View v, WindowManager.LayoutParams lp) {
        if (!onMain("addStatic")) return false;
        try {
            if (lp.getTitle() == null || lp.getTitle().length() == 0) lp.setTitle("Z9xStatic");
            wm.addView(v, lp);
            statics.add(v);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "addStatic", t);
            return false;
        }
    }

    public void updateStatic(View v, WindowManager.LayoutParams lp) {
        if (!onMain("updateStatic")) return;
        try {
            if (v.isAttachedToWindow()) wm.updateViewLayout(v, lp);
        } catch (Throwable t) {
            Log.w(TAG, "updateStatic: " + t);
        }
    }

    public void removeStatic(View v) {
        if (!onMain("removeStatic")) { main.post(() -> removeStatic(v)); return; }
        statics.remove(v);
        try {
            if (v.isAttachedToWindow()) wm.removeViewImmediate(v);
        } catch (Throwable t) {
            Log.w(TAG, "removeStatic: " + t);
        }
    }

    private static boolean onMain(String what) {
        if (Looper.myLooper() == Looper.getMainLooper()) return true;
        Log.w(TAG, what + " called off the main thread");
        return false;
    }

    // ================================================================== root view of a panel
    /** Wraps every panel: key routing, timer reset, BACK, media keys, focus loss. */
    private final class PanelRoot extends FrameLayout {
        private final Panel panel;
        private boolean backDown;
        private boolean hadFocus;
        private final Runnable focusLostCheck = new Runnable() {
            @Override public void run() {
                if (panel.showing && !hasWindowFocus()) {
                    Log.i(TAG, "focus lost: closing " + panel.getClass().getSimpleName());
                    dismiss(panel, true);
                }
            }
        };

        PanelRoot(Context c, Panel panel) {
            super(c);
            this.panel = panel;
            setClipChildren(false);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent ev) {
            if (!panel.showing) return true;
            resetAutoHide(panel);
            int code = ev.getKeyCode();
            if (KeyEvent.isMediaSessionKey(code)) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN || ev.getAction() == KeyEvent.ACTION_UP) {
                    try {
                        AudioManager am = app.getSystemService(AudioManager.class);
                        if (am != null) am.dispatchMediaKeyEvent(ev);
                    } catch (Throwable t) {
                        Log.w(TAG, "media key: " + t);
                    }
                }
                return true;
            }
            try {
                if (panel.onKeyEvent(ev)) return true;
            } catch (Throwable t) {
                Log.w(TAG, "onKeyEvent: " + t);
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                    if (ev.getRepeatCount() == 0) backDown = true;
                } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                    if (backDown && !ev.isCanceled()) {
                        boolean consumed = false;
                        try { consumed = panel.onBack(); } catch (Throwable t) { Log.w(TAG, "onBack: " + t); }
                        if (!consumed) dismiss(panel, true);
                    }
                    backDown = false;
                }
                return true;
            }
            try {
                return super.dispatchKeyEvent(ev);
            } catch (Throwable t) {
                Log.w(TAG, "dispatchKeyEvent: " + t);
                return true;
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            super.onWindowFocusChanged(hasFocus);
            if (hasFocus) {
                hadFocus = true;
                removeCallbacks(focusLostCheck);
            } else if (hadFocus) {
                // Another window took focus (assistant, an activity we launched): close, after a
                // short grace period that ignores transient focus changes.
                removeCallbacks(focusLostCheck);
                postDelayed(focusLostCheck, 300);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(focusLostCheck);
            super.onDetachedFromWindow();
        }
    }
}
