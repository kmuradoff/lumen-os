package org.lumen.hb.boot;

import android.app.Application;

/** Persistent, direct boot aware process of HbBoot: starts the vendor boot handshake at once. */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        BootSequence.start(this);
    }
}
