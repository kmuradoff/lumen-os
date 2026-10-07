package org.z9x.projector;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

/**
 * Handler that never lets an exception escape into the Looper. The app is persistent: an
 * uncaught exception would kill and immediately restart the process (crash loop), so every
 * worker and main-thread task of this app runs through a SafeHandler. Modules: use Ui.main() for
 * the main thread and Hal.run for HAL work; create an own thread only for non-HAL background work.
 */
public final class SafeHandler extends Handler {
    private final String tag;

    public SafeHandler(Looper looper, String tag) {
        super(looper);
        this.tag = tag;
    }

    /** Starts a new HandlerThread with the given name and returns a SafeHandler on it. */
    public static SafeHandler newThread(String name) {
        HandlerThread t = new HandlerThread(name);
        t.start();
        return new SafeHandler(t.getLooper(), name);
    }

    public boolean isCurrentThread() {
        return Looper.myLooper() == getLooper();
    }

    @Override
    public void dispatchMessage(Message msg) {
        try {
            super.dispatchMessage(msg);
        } catch (Throwable t) {
            Log.e("Z9xProjector", "uncaught in " + tag + " (ignored)", t);
        }
    }
}
