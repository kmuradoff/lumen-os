package org.z9x.home.tvp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.z9x.home.App;

import java.util.Collections;

/**
 * Best effort only (SPEC 5.2/5.4): a newly installed app gets INITIALIZE_PROGRAMS right away. The
 * authoritative diff runs at every HomeActivity start, because a stopped launcher misses broadcasts.
 */
public class PackageReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Uri d = i.getData();
        if (d == null || i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
        final String pkg = d.getSchemeSpecificPart();
        if (pkg == null) return;
        final PendingResult pr = goAsync();
        App.get().io().post(() -> {
            try {
                ProgramsInitializer.send(c, Collections.singleton(pkg));
                // record it so the start-up diff does not send it again (keeps the 24 h "new" dot)
                org.z9x.home.Prefs p = App.get().prefs();
                if (p.bool(org.z9x.home.Prefs.K_INIT_DONE, false)) {
                    java.util.Map<String, String> known = p.map(org.z9x.home.Prefs.K_KNOWN_PKGS);
                    if (!known.containsKey(pkg)) {
                        known.put(pkg, Long.toString(System.currentTimeMillis()));
                        p.putMap(org.z9x.home.Prefs.K_KNOWN_PKGS, known);
                    }
                }
            } finally {
                pr.finish();
            }
        });
    }
}
