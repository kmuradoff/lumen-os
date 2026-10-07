package org.z9x.home.data;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import org.z9x.home.App;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installed apps (SPEC 5.2): every MAIN+LEANBACK_LAUNCHER activity, plus MAIN+LAUNCHER for packages
 * without a leanback entry, so phone-only apps (LocalSend, ByeDPI) stay reachable. One entry per
 * activity. Runs on the io thread.
 */
public final class AppSource {
    private AppSource() {}

    /** Hidden unless the user un-hides them in Customize. */
    public static final Set<String> HIDDEN_BY_DEFAULT = new HashSet<>(java.util.Arrays.asList(
            "org.z9x.home", "com.android.tv.settings", "org.z9x.tvinput", "org.z9x.setup",
            "com.android.tv.frameworkpackagestubs", "com.google.android.tungsten.setupwraith",
            "org.lineageos.setupwizard", "com.google.android.katniss", "org.z9x.updater"));

    public static final String PKG_YOUTUBE = "com.google.android.youtube.tv";
    public static final String PKG_KINOPOISK = "ru.kinopoisk.tv";
    public static final String PKG_PLAY = "com.android.vending";

    public static final class Entry {
        public ComponentName cn;
        public String label;
        public boolean leanback;
        public boolean system;
        public long updated;
        public long installed;
    }

    public static List<Entry> load(Context c) {
        PackageManager pm = c.getPackageManager();
        ArrayList<Entry> out = new ArrayList<>();
        HashSet<String> lbPkgs = new HashSet<>();
        HashSet<String> seen = new HashSet<>();
        Map<String, long[]> times = new HashMap<>();
        Intent lb = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(lb, 0)) {
            Entry a = make(pm, ri, true, times);
            if (a != null && seen.add(a.cn.flattenToShortString())) {
                out.add(a);
                lbPkgs.add(a.cn.getPackageName());
            }
        }
        Intent la = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(la, 0)) {
            if (ri.activityInfo == null || lbPkgs.contains(ri.activityInfo.packageName)) continue;
            Entry a = make(pm, ri, false, times);
            if (a != null && seen.add(a.cn.flattenToShortString())) out.add(a);
        }
        return out;
    }

    private static Entry make(PackageManager pm, ResolveInfo ri, boolean leanback, Map<String, long[]> times) {
        if (ri.activityInfo == null) return null;
        try {
            Entry a = new Entry();
            a.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            CharSequence l = ri.loadLabel(pm);
            a.label = l == null ? ri.activityInfo.packageName : l.toString().trim();
            if (a.label.length() > 120) a.label = a.label.substring(0, 120);
            a.leanback = leanback;
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            a.system = ai != null && (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            long[] t = times.get(a.cn.getPackageName());
            if (t == null) {
                t = new long[]{0, 0};
                try {
                    PackageInfo pi = pm.getPackageInfo(a.cn.getPackageName(), 0);
                    t[0] = pi.lastUpdateTime;
                    t[1] = pi.firstInstallTime;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                times.put(a.cn.getPackageName(), t);
            }
            a.updated = t[0];
            a.installed = t[1];
            return a;
        } catch (Throwable t) {
            Log.w(App.TAG, "app entry " + ri.activityInfo.packageName + ": " + t);
            return null;
        }
    }

    /** package -> lastTimeUsed over the last 28 days (PACKAGE_USAGE_STATS; empty if not granted). */
    public static Map<String, Long> lastUsed(Context c) {
        HashMap<String, Long> m = new HashMap<>();
        try {
            UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            Map<String, UsageStats> agg = um.queryAndAggregateUsageStats(now - 28L * 86_400_000L, now);
            if (agg != null) {
                for (Map.Entry<String, UsageStats> e : agg.entrySet()) {
                    UsageStats s = e.getValue();
                    long t = Math.max(s.getLastTimeUsed(), s.getLastTimeVisible());
                    if (t > 0 && s.getTotalTimeInForeground() > 0) m.put(e.getKey(), t);
                }
            }
        } catch (Throwable t) {
            Log.w(App.TAG, "usage stats: " + t);
        }
        return m;
    }

    /** Art key of an app banner/tile: changes with the app version and its label. */
    public static String imageKey(Entry a) {
        // the generated tile renders the label, so a new label or language means new art
        return "app:" + a.cn.flattenToString() + "|" + a.updated + "|" + Integer.toHexString(a.label.hashCode());
    }
}
