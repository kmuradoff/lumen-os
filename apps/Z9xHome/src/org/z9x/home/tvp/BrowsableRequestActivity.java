package org.z9x.home.tvp;

import android.app.Activity;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.data.TvpSource;

/**
 * TvContract.ACTION_REQUEST_CHANNEL_BROWSABLE (SPEC 5.4): an app asks to show one of its preview
 * channels. Auto-approved without a dialog (the user only sees content of apps they installed and can
 * hide a row in one click), but only for a channel that belongs to the calling package.
 */
public class BrowsableRequestActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int result = RESULT_CANCELED;
        try {
            String caller = getCallingPackage();
            long id = getIntent().getLongExtra(TvContract.EXTRA_CHANNEL_ID, -1);
            if (caller != null && id >= 0 && approve(this, id, caller)) result = RESULT_OK;
            Log.i(App.TAG, "browsable request pkg=" + caller + " ch=" + id + " ok=" + (result == RESULT_OK));
        } catch (Throwable t) {
            Log.w(App.TAG, "browsable request: " + t);
        }
        setResult(result);
        finish();
    }

    /** Sets browsable=1 if channel {@code id} is owned by {@code pkg}. */
    static boolean approve(android.content.Context c, long id, String pkg) {
        if (!TvpSource.fullAccess(c)) return false;
        String owner = null;
        try (Cursor cu = c.getContentResolver().query(TvContract.buildChannelUri(id),
                new String[]{TvContract.Channels.COLUMN_PACKAGE_NAME}, null, null, null)) {
            if (cu != null && cu.moveToFirst()) owner = cu.getString(0);
        }
        if (owner == null || !owner.equals(pkg)) return false;
        ContentValues cv = new ContentValues();
        cv.put(TvContract.Channels.COLUMN_BROWSABLE, 1);
        return c.getContentResolver().update(TvContract.buildChannelUri(id), cv, null, null) > 0;
    }
}
