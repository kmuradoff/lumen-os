package org.z9x.play6;

import android.app.Application;

/** Persistent process of Lumen OS on the XGIMI Play 6: starts the vendor boot handshake at once. */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        BootHandshake.start(this);
        org.z9x.projector.Play6App.onProcessStart(this);
    }
}
