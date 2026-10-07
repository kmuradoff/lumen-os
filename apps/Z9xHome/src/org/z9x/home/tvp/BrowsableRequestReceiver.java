package org.z9x.home.tvp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.util.Log;

import org.z9x.home.App;

/**
 * TvContract.ACTION_CHANNEL_BROWSABLE_REQUESTED, sent by system_server after
 * TvInputManager.requestChannelBrowsable(). Same auto-approve with the ownership check.
 */
public class BrowsableRequestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        final long id = i.getLongExtra(TvContract.EXTRA_CHANNEL_ID, -1);
        final String pkg = i.getStringExtra(TvContract.EXTRA_PACKAGE_NAME);
        if (id < 0 || pkg == null) return;
        final PendingResult pr = goAsync();
        App.get().io().post(() -> {
            try {
                boolean ok = BrowsableRequestActivity.approve(c, id, pkg);
                Log.i(App.TAG, "browsable requested pkg=" + pkg + " ch=" + id + " ok=" + ok);
            } catch (Throwable t) {
                Log.w(App.TAG, "browsable requested: " + t);
            } finally {
                pr.finish();
            }
        });
    }
}
