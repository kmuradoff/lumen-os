package org.z9x.home;

import android.util.Log;

/** Crash guard for callbacks (the projector's style): a bug in one row must never take HOME down. */
public final class Safe {
    private Safe() {}

    public interface Body {
        void run() throws Throwable;
    }

    public static void run(String what, Body b) {
        try {
            b.run();
        } catch (Throwable t) {
            Log.e(App.TAG, what + " failed", t);
        }
    }

    public static Runnable wrap(String what, Body b) {
        return () -> run(what, b);
    }
}
