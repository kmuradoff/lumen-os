package org.z9x.setup.net;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/** Read-only Gservices lookups (READ_GSERVICES is a normal permission). */
public final class Gservices {
    private static final Uri URI = Uri.parse("content://com.google.android.gsf.gservices");

    private Gservices() {}

    /** Value of a Gservices key, or null (GSF missing, not checked in yet). Worker thread only. */
    public static String get(Context c, String key) {
        try (Cursor cur = c.getContentResolver().query(URI, null, null, new String[]{key}, null)) {
            if (cur == null || !cur.moveToFirst() || cur.getColumnCount() < 2) return null;
            String v = cur.getString(1);
            return v == null || v.isEmpty() ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** GSF Android ID (decimal string as stored), or null before the first check-in. */
    public static String androidId(Context c) {
        String v = get(c, "android_id");
        if (v == null || "0".equals(v)) return null;
        return v;
    }
}
