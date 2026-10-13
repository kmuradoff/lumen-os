package org.z9x.projector.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;

/**
 * Opens the Source overlay on request of org.z9x.tvinput (MENU inside its HDMI viewer), so every
 * key that offers an input choice shows the same XGIMI-style overlay (V61_REQUIREMENTS item 2).
 * Exported, but the sender must hold org.z9x.projector.permission.SHOW_SOURCE (signature: only
 * platform-signed apps of this image). Action {@link #ACTION}. Main thread, cheap.
 */
public final class SourceRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xSource";
    public static final String ACTION = "org.z9x.projector.action.SHOW_SOURCE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (intent == null || !ACTION.equals(intent.getAction())) return;
            if (AkOverlay.isActive()) {
                Log.i(TAG, "source request ignored: AK overlay active");
                return;
            }
            if (!KeyReceiver.isSetupComplete(ctx)) {
                Log.i(TAG, "source request ignored: setup not complete");
                Ui.toast(ctx, R.string.toast_setup_running);
                return;
            }
            SourceOverlay.toggle(ctx.getApplicationContext());
        } catch (Throwable t) {
            Log.e(TAG, "source request", t);
        }
    }
}
