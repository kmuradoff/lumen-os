package org.lumen.projector.device;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.lumen.projector.Device;
import org.lumen.projector.battery.Battery;

/**
 * Read-only device state of the XGIMI Play 6 for Lumen Home (docs/play6/impl/home-contract.md; research
 * battery_sound_features.md 6.1 B8: the battery moves from this app's quick panel to Home). Authority
 * {@value #AUTHORITY}. Home does not read it yet (it stays byte-identical until the owner takes O6);
 * this is the contract a later Home reads.
 *
 * Contract:
 *  - call("battery") -> Bundle, and query(content://org.lumen.projector.device/battery) -> one row, with
 *      level    int      0..100, the internal battery in percent (IGmpf 240); -1 while present is false
 *      adapter  boolean  the power adapter is in (IGmpf 238)
 *      charging boolean  adapter in and the battery not full yet
 *      full     boolean  the battery is charged full (IGmpf 239)
 *      present  boolean  this projector has an internal battery and its state has been read; while false
 *                        the other fields carry no meaning (before the start gate, on a device without one)
 *      eco      boolean  power saving is on (IGmpf 327; the vendor turns it on on battery and off when the
 *                        brightness is raised; the Brightness page of the quick panel has the switch)
 *    The cursor carries the booleans as int 0 / 1 (columns in the order above).
 *  - notifyChange(content://org.lumen.projector.device/battery) whenever any of the six values changes
 *    (registerContentObserver on that Uri; one notification per change, never per read).
 *  - call("caps") -> Bundle {unsupported: String[]}: the Lumen Home Projector tab tiles this projector does
 *    not support, by the tile intent of apps/Z9xHome InputSource.tiles (Card id "tile:" + it), for Home to
 *    hide them. Today {"section:eye"} (eye protection waits for the NUI reader, Device.hasEyeProtection).
 *  - Any other method or Uri: null. Nothing can be written (insert / update / delete do nothing).
 *
 * No permission: the values are the facts Android hands every app without a permission in
 * ACTION_BATTERY_CHANGED, plus a list of tile names; nothing is writable, and a call never reaches the HAL
 * (it answers from battery/Battery's cache), so a caller cannot make this app talk to the vendor. The
 * pinned Lumen Home therefore needs no new permission to read it. A reader must still check that the
 * authority belongs to the system app org.lumen.projector and that ro.lumen.device is play6 (plan 3.6), as
 * any app can declare an authority on a device where this provider does not exist.
 *
 * onCreate does nothing (plan 2.2 step 1: no thread, no HAL, no I/O); not direct-boot aware (manifest).
 */
public final class DeviceStateProvider extends ContentProvider {
    private static final String TAG = "P6Battery";
    public static final String AUTHORITY = "org.lumen.projector.device";
    public static final String PATH_BATTERY = "battery";
    public static final Uri BATTERY_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_BATTERY);
    public static final String METHOD_BATTERY = "battery";
    public static final String METHOD_CAPS = "caps";
    public static final String KEY_LEVEL = "level", KEY_ADAPTER = "adapter", KEY_CHARGING = "charging",
            KEY_FULL = "full", KEY_PRESENT = "present", KEY_ECO = "eco";
    public static final String KEY_UNSUPPORTED = "unsupported";
    static final String[] COLUMNS = {KEY_LEVEL, KEY_ADAPTER, KEY_CHARGING, KEY_FULL, KEY_PRESENT, KEY_ECO};

    /** The six values of the contract. Immutable. */
    static final class Snapshot {
        final int level;
        final boolean adapter, charging, full, present, eco;

        Snapshot(Battery.State s) {
            present = Device.hasBattery() && s != null && s.level != null;
            level = present ? s.level : -1;
            adapter = s != null && Boolean.TRUE.equals(s.adapter);
            charging = s != null && s.charging();
            full = s != null && Boolean.TRUE.equals(s.full);
            eco = s != null && Boolean.TRUE.equals(s.eco);
        }

        boolean sameAs(Snapshot o) {
            return o != null && level == o.level && adapter == o.adapter && charging == o.charging
                    && full == o.full && present == o.present && eco == o.eco;
        }

        @Override
        public String toString() {
            return "level=" + level + " adapter=" + adapter + " charging=" + charging + " full=" + full
                    + " present=" + present + " eco=" + eco;
        }
    }

    /** The values the last notification announced. */
    private static Snapshot sNotified;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Battery (p6-hal, after every change of its agreed state): notifyChange on {@link #BATTERY_URI} when one
     * of the six values changed. Cheap: no HAL call.
     */
    public static void notifyIfChanged(Context app) {
        if (app == null) return;
        Snapshot now = new Snapshot(Battery.current());
        synchronized (DeviceStateProvider.class) {
            if (now.sameAs(sNotified)) return;
            sNotified = now;
        }
        try {
            app.getContentResolver().notifyChange(BATTERY_URI, null);
        } catch (Throwable t) {
            Log.w(TAG, "notifyChange: " + t);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_BATTERY.equals(method)) {
            Snapshot s = new Snapshot(Battery.current());
            Bundle b = new Bundle();
            b.putInt(KEY_LEVEL, s.level);
            b.putBoolean(KEY_ADAPTER, s.adapter);
            b.putBoolean(KEY_CHARGING, s.charging);
            b.putBoolean(KEY_FULL, s.full);
            b.putBoolean(KEY_PRESENT, s.present);
            b.putBoolean(KEY_ECO, s.eco);
            return b;
        }
        if (METHOD_CAPS.equals(method)) {
            Bundle b = new Bundle();
            b.putStringArray(KEY_UNSUPPORTED, Device.unsupportedHomeTiles());
            return b;
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (uri == null || !PATH_BATTERY.equals(uri.getLastPathSegment())) return null;
        Snapshot s = new Snapshot(Battery.current());
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[] {s.level, b(s.adapter), b(s.charging), b(s.full), b(s.present), b(s.eco)});
        Context ctx = getContext();
        if (ctx != null) c.setNotificationUri(ctx.getContentResolver(), BATTERY_URI);
        return c;
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
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
