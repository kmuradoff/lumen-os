package org.lumen.projector.ak;

import android.os.SystemClock;
import android.util.Log;

/**
 * The XGIMI Play 6 class behind the auto-keystone overlay seams (docs/play6/SETTINGS_PLAN.md 1.3, 2.4:
 * the Hal facade, KeyReceiver, SourceRequestReceiver, RemotePrompt, the power key). Not a copy of the
 * Z9X AkOverlay: in P1 the stock-style AK screens are not drawn (on t10 the vendor ran real-time AK with
 * no app), {@link #isActive()} is false, and the AK events are only logged with their timing, so the
 * t12 log shows which events arrive and how far apart (tests B2 and B8; OQ 7: does the vendor wait for
 * IGmpf 146 acks?). The overlay port is plan P2, then with the 146 acks the device log asks for.
 */
public final class AkOverlay {
    private static final String TAG = "P6Focus";
    /** A pause this long starts a new AK run in the log. */
    private static final long RUN_GAP_MS = 10_000;

    /** Main thread state. */
    private static long sLastAt = -1;
    private static int sRunEvents;

    private AkOverlay() {}

    /** The AK overlay is up. P1: never. Any thread. */
    public static boolean isActive() {
        return false;
    }

    /**
     * The Hal facade, for every AK event (105-118, 120), MAIN thread, before the other listeners. Logs the
     * event with the time since the previous one of the same run. Returns false: not handled (the
     * listeners and the AF busy window still see it).
     */
    public static boolean onFocusEvent(int type, String value) {
        long now = SystemClock.elapsedRealtime();
        boolean newRun = sLastAt < 0 || now - sLastAt > RUN_GAP_MS;
        sRunEvents = newRun ? 1 : sRunEvents + 1;
        Log.i(TAG, "AK event type=" + type + (value == null || value.isEmpty() ? "" : " value=" + value)
                + (newRun ? " (new run)" : " (+" + (now - sLastAt) + " ms, #" + sRunEvents + ")"));
        sLastAt = now;
        return false;
    }
}
