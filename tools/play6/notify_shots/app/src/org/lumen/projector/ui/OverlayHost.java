package org.lumen.projector.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Harness stand-in for the projector app's OverlayHost (tools/play6/notify_shots): the members Notify uses, with
 * the same meaning. A "static window" is a child of the harness activity's layer, placed as the window manager
 * would place it (gravity, x, y, width, height), so Notify's own window logic (card + right inset, relayout
 * next to a right-side panel) is what the shots show. Never shipped.
 */
public final class OverlayHost {
    private static final OverlayHost I = new OverlayHost();
    /** The activity layer that stands for the screen (set by the harness). */
    public static FrameLayout layer;
    /** Width of the mock right-side panel window (0 = none), as OverlayHost.rightPanelWidth reports it. */
    public static int panelWidth;

    private OverlayHost() {}

    public static OverlayHost get(Context c) {
        return I;
    }

    public static WindowManager.LayoutParams params(boolean focusable, int w, int h, int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        return lp;
    }

    public int rightPanelWidth() {
        return panelWidth;
    }

    public boolean addStatic(View v, WindowManager.LayoutParams lp) {
        layer.addView(v, frame(lp));
        return true;
    }

    public void updateStatic(View v, WindowManager.LayoutParams lp) {
        v.setLayoutParams(frame(lp));
    }

    public void removeStatic(View v) {
        layer.removeView(v);
    }

    private static FrameLayout.LayoutParams frame(WindowManager.LayoutParams lp) {
        FrameLayout.LayoutParams f = new FrameLayout.LayoutParams(lp.width, lp.height, lp.gravity);
        int g = lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        if (g == Gravity.RIGHT) f.rightMargin = lp.x; else f.leftMargin = lp.x;
        f.topMargin = lp.y;
        return f;
    }
}
