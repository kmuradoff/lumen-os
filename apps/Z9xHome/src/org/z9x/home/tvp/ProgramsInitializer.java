package org.z9x.home.tvp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.util.Log;

import org.z9x.home.App;

import java.util.Collection;
import java.util.List;

/**
 * Sends ACTION_INITIALIZE_PROGRAMS to apps (SPEC D6, 5.4). Nothing in the framework sends it, and
 * with tvrecommendations disabled nobody else does: YouTube, Kinopoisk and Twitch publish their
 * channels only after it. Explicit per component, so it reaches stopped/background receivers.
 */
public final class ProgramsInitializer {
    private ProgramsInitializer() {}

    public static int send(Context c, Collection<String> packages) {
        int n = 0;
        for (String pkg : packages) {
            try {
                Intent q = new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(pkg);
                List<ResolveInfo> l = c.getPackageManager().queryBroadcastReceivers(q, 0);
                for (ResolveInfo ri : l) {
                    if (ri.activityInfo == null) continue;
                    Intent i = new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS);
                    i.setComponent(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                    i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
                    c.sendBroadcast(i);
                    n++;
                    Log.i(App.TAG, "init-programs pkg=" + pkg + " receiver=" + ri.activityInfo.name);
                }
            } catch (Throwable t) {
                Log.w(App.TAG, "init-programs " + pkg + ": " + t);
            }
        }
        return n;
    }
}
