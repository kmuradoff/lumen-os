package org.z9x.home.search;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * org.z9x.home.action.MIC_UP from org.z9x.projector's KeyReceiver (the mic key was released). Guarded
 * by org.z9x.home.permission.MIC (signature). Remembers the release time (extra up_uptime, else now)
 * even when no search screen is open yet: on a cold start of this process the release often arrives
 * before the screen exists, and SearchActivity then opens keyboard search instead of listening to
 * silence (the remote streams audio only while the key is held).
 */
public class MicUpReceiver extends BroadcastReceiver {
    public static final String EXTRA_UP_TIME = "up_uptime";
    private static volatile long sLastUp;

    static long lastUp() {
        return sLastUp;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        long up = i == null ? 0 : i.getLongExtra(EXTRA_UP_TIME, 0);
        if (up <= 0) up = SystemClock.uptimeMillis();
        sLastUp = up;
        SearchActivity a = SearchActivity.sActive;
        if (a != null) a.onMicUp(up);
    }
}
