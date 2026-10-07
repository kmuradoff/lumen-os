package org.z9x.projector.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Shows the shared XGIMI-style card ({@link Notify}) on request of another app of this image
 * (org.z9x.tvinput: setup-running hint, "no input", "bad input"), so every message of the system
 * looks the same and sits above our overlay windows (V61_REQUIREMENTS decision "one shared
 * XGIMI-style card for all notifications"). Exported, but the sender must hold
 * org.z9x.projector.permission.NOTIFY (signature: only platform-signed apps of this image).
 * Extras: {@link #EXTRA_TITLE} (required), {@link #EXTRA_DESCRIPTION} (optional); plain text,
 * cut to {@link #MAX_LEN} characters. Main thread, cheap.
 */
public final class NotifyRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xNotify";
    public static final String ACTION = "org.z9x.projector.action.NOTIFY";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_DESCRIPTION = "description";
    private static final int MAX_LEN = 160;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (intent == null || !ACTION.equals(intent.getAction())) return;
            String title = clip(intent.getStringExtra(EXTRA_TITLE));
            if (title == null) return;
            String desc = clip(intent.getStringExtra(EXTRA_DESCRIPTION));
            Notify.show(ctx.getApplicationContext(), title, desc);
        } catch (Throwable t) {
            Log.e(TAG, "notify request", t);
        }
    }

    private static String clip(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_LEN ? s.substring(0, MAX_LEN) : s;
    }
}
