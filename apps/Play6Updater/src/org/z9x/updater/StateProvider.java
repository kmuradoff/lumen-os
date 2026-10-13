// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONException;

/**
 * content://org.z9x.updater, call("state") -> Bundle for the Lumen Home "Update ready" badge (C17)
 * and org.z9x.projector's power policy (C16). Callers need org.z9x.updater.permission.OTA_STATE
 * (signature). Keys: state (idle|checking|available|downloading|paused|downloaded|installing|ready|
 * error), busy (boolean), ready (boolean), available (boolean), version (offered version or ""),
 * current (running Lumen OS version), progress (0..1 of the current phase).
 */
public final class StateProvider extends ContentProvider {
    @Override
    public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(Ota.PERM_STATE, "OTA state");
        if (!"state".equals(method)) return null;
        Store st = Store.get(getContext());
        Store.State s = st.state();
        Bundle b = new Bundle();
        b.putString("state", s.name().toLowerCase());
        b.putBoolean("busy", s == Store.State.DOWNLOADING || s == Store.State.INSTALLING);
        b.putBoolean("ready", s == Store.State.READY);
        b.putBoolean("available", s == Store.State.AVAILABLE || s == Store.State.DOWNLOADED
                || s == Store.State.PAUSED);
        String v = "";
        try {
            if (!st.manifestJson().isEmpty()) v = new UpdateManifest(st.manifestJson()).version;
        } catch (JSONException ignored) {
        }
        b.putString("version", v);
        b.putString("current", Ota.currentVersion());
        float pr = s == Store.State.INSTALLING ? st.progress()
                : st.dlTotal() > 0 ? (float) st.dlBytes() / st.dlTotal() : 0f;
        b.putFloat("progress", pr);
        return b;
    }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }

    @Override
    public String getType(Uri u) { return null; }

    @Override
    public Uri insert(Uri u, ContentValues v) { return null; }

    @Override
    public int delete(Uri u, String s, String[] a) { return 0; }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
