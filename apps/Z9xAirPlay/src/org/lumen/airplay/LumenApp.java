package org.lumen.airplay;

import android.app.Application;
import android.content.Context;

/**
 * Lumen OS 1.0.8: the one Application of org.lumen.airplay. It only runs the bridge import of the pre-1.0.8
 * org.z9x.airplay data (org.lumen.common.LumenImport) before any component reads its prefs.
 */
public final class LumenApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        org.lumen.common.LumenImport.run(base);
    }
}
