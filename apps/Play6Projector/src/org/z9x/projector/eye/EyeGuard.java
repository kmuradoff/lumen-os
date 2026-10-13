package org.z9x.projector.eye;

import android.view.KeyEvent;

/**
 * The XGIMI Play 6 class behind the eye-protection seams (docs/play6/SETTINGS_PLAN.md 1.7, 2.4: the
 * shared RemotePrompt and the KeyReceiver fork). Not a copy of the Z9X EyeGuard: the mask runtime (focus
 * events 500 / 601, IGmpf 148(true) behind the NUI fifo reader) is plan P2, and the vendor UI heartbeat
 * 114 / 115 is banned until its own review (Note A). Until then no mask is ever shown.
 */
public final class EyeGuard {
    private EyeGuard() {}

    /** The eye-protection mask is up. P1: never. Any thread. */
    public static boolean isActive() {
        return false;
    }

    /** KeyReceiver, every global key: true would mean "eaten by the mask". P1: never. Main thread. */
    public static boolean onGlobalKey(KeyEvent ev) {
        return false;
    }
}
