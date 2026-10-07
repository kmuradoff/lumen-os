package org.z9x.projector.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lumen OS 1.0 (PLAN C1): {@code org.z9x.projector.action.SET_LAUNCHER} from Lumen Home's Customize
 * screen (or any platform-signed app of this image: signature permission
 * org.z9x.projector.permission.SET_LAUNCHER). Extras: {@code mode} = z9x | classic, optional
 * {@code start_home} (default true: the new launcher comes to the front). The switch runs on
 * LauncherSwitcher's worker; this receiver returns at once (persistent process).
 */
public final class LauncherRequestReceiver extends BroadcastReceiver {
    public static final String ACTION = "org.z9x.projector.action.SET_LAUNCHER";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_START_HOME = "start_home";

    @Override
    public void onReceive(Context ctx, Intent i) {
        try {
            if (i == null || !ACTION.equals(i.getAction())) return;
            String mode = i.getStringExtra(EXTRA_MODE);
            if (!LauncherSwitcher.MODE_LUMEN.equals(mode) && !LauncherSwitcher.MODE_CLASSIC.equals(mode)) {
                Log.w("Z9xLauncher", "SET_LAUNCHER: unknown mode " + mode);
                return;
            }
            LauncherSwitcher.apply(ctx, mode, i.getBooleanExtra(EXTRA_START_HOME, true), "SET_LAUNCHER", null);
        } catch (Throwable t) {
            Log.w("Z9xLauncher", "SET_LAUNCHER: " + t);
        }
    }
}
