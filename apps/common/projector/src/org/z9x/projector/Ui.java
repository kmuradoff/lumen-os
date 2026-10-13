package org.z9x.projector;

import android.app.DreamManager;
import android.content.Context;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.z9x.projector.ui.Notify;

/**
 * Main-thread helpers. v6.1: every status message goes to the shared XGIMI notification card
 * (org.z9x.projector.ui.Notify: top-right capsule, a new message replaces the old one); Notify
 * falls back to a Toast if the overlay window cannot be added.
 */
public final class Ui {
    private static SafeHandler sMain;

    private Ui() {}

    /** Main-thread handler that never lets an exception escape (persistent process). */
    public static synchronized SafeHandler main() {
        if (sMain == null) sMain = new SafeHandler(Looper.getMainLooper(), "main");
        return sMain;
    }

    /**
     * Ends a running screensaver (Ambient mode / DreamActivity) before we start an activity or
     * react to a global key: global keys never reach the dream window, and the dream task is
     * always on top (WindowConfiguration ACTIVITY_TYPE_DREAM isAlwaysOnTop), so an activity started
     * under it would stay hidden. Uses READ_/WRITE_DREAM_STATE (signature). Any thread; never throws.
     * UNVERIFIED on the device with Ambient mode running.
     */
    public static void wakeFromDream(Context ctx) {
        try {
            // Only a screensaver on a lit screen (wakefulness DREAMING counts as interactive); never
            // touch a doze dream while the screen is off.
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) return;
            DreamManager dm = ctx.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) {
                Log.i("Z9xUi", "screensaver running: stopDream");
                dm.stopDream();
            }
        } catch (Throwable t) {
            Log.w("Z9xUi", "stopDream: " + t);
        }
    }

    /** Shows a status message from any thread. */
    public static void toast(final Context ctx, final int resId) {
        Notify.show(ctx, resId);
    }

    /** Longer hint text: same card (it holds 5.5 s and has room for two lines). */
    public static void toastLong(final Context ctx, final int resId) {
        Notify.show(ctx, resId);
    }

    /**
     * v6: "projector features are off after a failed boot". v6.1 removed the kill switch, so this
     * can only mean the HAL is not connected yet.
     */
    static void featureOffNotice(Context ctx) {
        toast(ctx, R.string.toast_not_ready);
    }
}
