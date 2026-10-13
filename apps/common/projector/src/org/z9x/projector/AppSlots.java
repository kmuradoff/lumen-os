package org.z9x.projector;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Remote app keys, slots 1..5. The user can choose the app for slot 3 (iQIYI key) and slot 4 (bilibili key). */
final class AppSlots {
    private static final String TAG = "Z9xSlots";
    private static final String PREFS = "app_slots";
    static final String PLAY_STORE = "com.android.vending";
    /** Default without Play (Lumen OS without Google): the key opens Lumen Home's Apps page (ACTION_ALL_APPS). */
    static final String HOME_APPS = "org.z9x.home";
    /** Slot 3 default: first installed of these, else the Play Store, else Lumen Home's Apps page. */
    private static final String[] SLOT3_DEFAULTS = {
            "com.google.android.youtube.tv", "com.teamsmart.videomanager.tv"};

    static final class AppEntry {
        final String pkg;
        final String label;
        AppEntry(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    private AppSlots() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** User choice for the slot, or null when the default applies. */
    static String chosenPackage(Context c, int slot) {
        return prefs(c).getString("slot" + slot, null);
    }

    /** null = back to the default. */
    static void setPackage(Context c, int slot, String pkg) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (pkg == null) e.remove("slot" + slot); else e.putString("slot" + slot, pkg);
        e.apply();
    }

    static String defaultPackage(Context c, int slot) {
        if (slot == 3) {
            for (String p : SLOT3_DEFAULTS) if (launchIntent(c, p) != null) return p;
        }
        return launchIntent(c, PLAY_STORE) != null ? PLAY_STORE : HOME_APPS;
    }

    static String effectivePackage(Context c, int slot) {
        String p = chosenPackage(c, slot);
        return p != null ? p : defaultPackage(c, slot);
    }

    /** Leanback launch intent first, then the normal one (HOME_APPS: Lumen Home's AllAppsAlias). */
    static Intent launchIntent(Context c, String pkg) {
        if (pkg == null) return null;
        if (HOME_APPS.equals(pkg)) {
            Intent a = new Intent(Intent.ACTION_ALL_APPS).setPackage(HOME_APPS);
            try {
                return c.getPackageManager().resolveActivity(a, 0) != null ? a : null;
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            return i;
        } catch (Throwable t) {
            return null;
        }
    }

    static String labelOf(Context c, String pkg) {
        if (HOME_APPS.equals(pkg)) return c.getString(R.string.slot_home_apps);
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    /** Launch the app of slot 1..5 (called on the main thread from KeyReceiver). */
    static void launch(Context c, int slot) {
        String pkg = effectivePackage(c, slot);
        Intent i = launchIntent(c, pkg);
        if (i == null && chosenPackage(c, slot) != null) {
            Log.w(TAG, "slot " + slot + ": chosen " + pkg + " not launchable, using the default");
            pkg = defaultPackage(c, slot);
            i = launchIntent(c, pkg);
        }
        if (i == null) {
            Log.w(TAG, "slot " + slot + ": nothing to launch (" + pkg + ")");
            Ui.toast(c, R.string.app_not_found);
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            Ui.wakeFromDream(c);
            c.startActivity(i);
            Log.i(TAG, "slot " + slot + " -> " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "slot " + slot + " launch " + pkg + ": " + t);
            Ui.toast(c, R.string.app_not_found);
        }
    }

    /** Installed apps with a leanback or normal launcher entry, sorted by label (own package excluded). */
    static List<AppEntry> launchableApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> byPkg = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            try {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String p = ri.activityInfo.packageName;
                    if (p.equals(c.getPackageName()) || byPkg.containsKey(p)) continue;
                    byPkg.put(p, labelOf(c, p));
                }
            } catch (Throwable t) {
                Log.w(TAG, "query " + cat + ": " + t);
            }
        }
        List<AppEntry> out = new ArrayList<>();
        for (Map.Entry<String, String> e : byPkg.entrySet()) out.add(new AppEntry(e.getKey(), e.getValue()));
        final Collator col = Collator.getInstance(new Locale("ru"));
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        return out;
    }
}
