package org.lumen.updater;

import android.app.Application;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * The one Application of org.lumen.updater. Device-neutral fork of apps/Z9xUpdater LumenApp.java
 *: no bridge import (a device of this updater never had
 * org.z9x.updater data). An image without a device wire id (ro.lumen.ota.device) takes no update, so the
 * update screen is disabled and TvSettings About shows no "Lumen OS update" row (it lists the
 * SYSTEM_UPDATE_SETTINGS activity only while one resolves); with a wire id it is back to its manifest state.
 */
public final class LumenApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            PackageManager pm = getPackageManager();
            ComponentName ui = new ComponentName(this, UpdaterActivity.class);
            int want = Ota.device().isEmpty() ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (pm.getComponentEnabledSetting(ui) != want) {
                pm.setComponentEnabledSetting(ui, want, PackageManager.DONT_KILL_APP);
                Log.i(Ota.TAG, "update screen " + (want == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        ? "hidden: no ro.lumen.ota.device" : "shown"));
            }
        } catch (Throwable t) {
            Log.w(Ota.TAG, "update screen state: " + t);
        }
    }
}
