package org.z9x.play6;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** (LOCKED_)BOOT_COMPLETED: make sure the handshake runs (idempotent; normally App.onCreate did it). */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        BootHandshake.start(ctx);
        // the chosen sound output again once the audio services are up (the role lives in AudioService memory)
        org.z9x.projector.audio.AudioOutputChoice.onBootCompleted(ctx);
    }
}
