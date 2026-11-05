// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONException;

/**
 * content://org.lumen.updater, call("state") -> Bundle for the Lumen Home "Update ready" badge (C17)
 * and org.lumen.projector's power policy (C16). Callers need org.lumen.updater.permission.OTA_STATE
 * (signature). Keys: state (idle|checking|available|downloading|paused|downloaded|installing|ready|
 * error), busy (boolean), ready (boolean), available (boolean), version (offered version or ""),
 * current (running Lumen OS version), progress (0..1 of the current phase).
 * The anonymous statistics switch (Stats; its one owner is this app): call("stats") -> {on, available}, and
 * call("stats_set", "1" | "0") -> the same after the change. The setup wizard and the projector settings use it.
 */
public final class StateProvider extends ContentProvider {
    @Override
    public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingOrSelfPermission(Ota.PERM_STATE, "OTA state");
        Store st = Store.get(getContext());
        if ("stats".equals(method) || "stats_set".equals(method)) {
            if ("stats_set".equals(method) && ("1".equals(arg) || "0".equals(arg))) st.setStatsOn("1".equals(arg));
            Bundle b = new Bundle();
            b.putBoolean("on", st.statsOn());
            // false: no statistics server in this build; Setup and the projector settings then show no switch
            b.putBoolean("available", Stats.configured());
            return b;
        }
        if (!"state".equals(method)) return null;
        Store.State s = st.state();
        // the update already runs (the first seconds of its boot, before BootReceiver stored IDLE): not "ready"
        if (Outcome.readyStale(s == Store.State.READY, st.pendingTarget(), st.pendingFrom(), st.pendingVersion(),
                Ota.buildId(), Ota.currentVersion())) {
            s = Store.State.IDLE;
        }
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
