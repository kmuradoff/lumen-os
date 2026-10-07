package org.z9x.projector.power;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * MODULE "power" (v6.5 review): the current lamp-only standby state for other Z9X apps.
 *
 * Only {@link #call}: method {@value #METHOD} -> Bundle {standby: boolean} =
 * {@link StandbyController#isActive()} (also true while a standby of this boot waits for its re-enter
 * after a process restart). Authority {@value #AUTHORITY}, exported, guarded by the signature
 * permission {@link StandbyController#PERMISSION_STANDBY_STATE}, checked by hand in call() (the
 * framework enforces a provider's read / write permissions only for query / insert / update / delete).
 *
 * Lumen OS 1.0: method {@value #METHOD_CEC_WAKE} (same permission): the CEC wake request of
 * org.z9x.tvinput (HdmiWatcher, &lt;Active Source&gt; during the lamp-only standby), answered by the cec module.
 *
 * Client: org.z9x.tvinput (HdmiWatcher) asks before every automatic HDMI switch (cable plug, HPD /
 * +5V re-assert, CEC &lt;Active Source&gt;, open on boot) and treats standby like "screen is off", so its
 * viewer and its TIF hardware audio patch never start behind the black standby screen. A query
 * instead of only the broadcast: it is right after a restart of either process.
 */
public final class StandbyStateProvider extends ContentProvider {
    public static final String AUTHORITY = "org.z9x.projector.standbystate";
    public static final String METHOD = "standby_state";
    public static final String KEY_STANDBY = "standby";
    /**
     * Lumen OS 1.0 (cec module): org.z9x.tvinput saw a CEC source become the active source.
     * Extras port, la, type, vendor, osd -> {accepted, standby} (org.z9x.projector.cec.CecPolicy).
     */
    public static final String METHOD_CEC_WAKE = "cec_wake";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD.equals(method) && !METHOD_CEC_WAKE.equals(method)) return null;
        if (getContext() == null || getContext().checkCallingPermission(StandbyController.PERMISSION_STANDBY_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("needs " + StandbyController.PERMISSION_STANDBY_STATE);
        }
        if (METHOD_CEC_WAKE.equals(method)) return org.z9x.projector.cec.CecPolicy.onWakeRequest(getContext(), extras);
        Bundle b = new Bundle();
        b.putBoolean(KEY_STANDBY, StandbyController.isActive());
        return b;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
