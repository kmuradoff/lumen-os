package org.lumen.projector.device;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.lumen.projector.hal.HbFeatures;

import java.util.ArrayList;

/**
 * Z9X HB: the read-only device state contract of Lumen Home (apps/Z9xHome ProjectorBridge, the same authority
 * and methods as apps/Play6Projector device/DeviceStateProvider). HB-only file, no HAL access.
 *
 * Contract:
 *  - call("caps") -> Bundle {unsupported: String[]}: the Lumen Home Projector tab tiles this projector does not
 *    offer, by tile intent of apps/Z9xHome InputSource.tiles, for Home to hide them. A tile is listed when the
 *    quick panel would have no page for it: today "section:eye" (HbFeatures.F.EYE_PROTECTION is PENDING), and
 *    "section:hdmi" while F.GAME_MODE is not ON (no Home tile names it yet; listed so a later Home hides it too).
 *  - call("battery") -> Bundle {present false, level -1, ...}: the HB has no internal battery.
 *  - Any other method or Uri: null. Nothing can be written.
 *
 * No permission: a list of tile names and a constant; a call never reaches the HAL. onCreate does nothing; not
 * direct boot aware (manifest).
 */
public final class DeviceStateProvider extends ContentProvider {
    public static final String AUTHORITY = "org.lumen.projector.device";
    public static final String METHOD_BATTERY = "battery";
    public static final String METHOD_CAPS = "caps";
    public static final String KEY_UNSUPPORTED = "unsupported";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The Home tile intents without a quick panel page on this HB (from the feature table). */
    static String[] unsupportedHomeTiles() {
        ArrayList<String> out = new ArrayList<>();
        if (!HbFeatures.on(HbFeatures.F.EYE_PROTECTION)) out.add("section:eye");
        if (!HbFeatures.on(HbFeatures.F.GAME_MODE)) out.add("section:hdmi");
        return out.toArray(new String[0]);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_CAPS.equals(method)) {
            Bundle b = new Bundle();
            b.putStringArray(KEY_UNSUPPORTED, unsupportedHomeTiles());
            return b;
        }
        if (METHOD_BATTERY.equals(method)) {
            Bundle b = new Bundle();
            b.putInt("level", -1);
            b.putBoolean("adapter", true);
            b.putBoolean("charging", false);
            b.putBoolean("full", false);
            b.putBoolean("present", false);
            b.putBoolean("eco", false);
            return b;
        }
        return null;
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
