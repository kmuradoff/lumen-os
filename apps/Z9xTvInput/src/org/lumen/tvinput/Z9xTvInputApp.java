/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.lumen.tvinput;

import android.app.Application;
import android.util.Log;

/**
 * Persistent process (android:persistent="true", /system/app): ActivityManagerService starts it
 * after user unlock and restarts it immediately if it dies. Nothing here talks to a HAL: the
 * watcher only registers passive callbacks with system_server (TvInputManager, HdmiControlManager).
 */
public class Z9xTvInputApp extends Application {

    /** Lumen OS 1.0.8 bridge: the pre-1.0.8 org.z9x data comes over before any prefs are read. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        org.lumen.common.LumenImport.run(base);
    }
    private static final String TAG = "Z9xHdmiApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Safe.run(TAG, "CrashGuard.install", () -> CrashGuard.install(this));
        boolean safeMode = false;
        try {
            safeMode = CrashGuard.checkSafeMode(this);
        } catch (Throwable t) {
            Log.w(TAG, "crash log check failed: " + t);
        }
        if (safeMode) {
            Log.e(TAG, "safe mode: HdmiWatcher not started");
            return;
        }
        Safe.run(TAG, "HdmiWatcher.start", () -> HdmiWatcher.start(this));
    }
}
