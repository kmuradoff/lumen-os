package org.z9x.projector.mem;

import android.app.ActivityManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Packages that "Close" in Recent apps never force-stops (speed spec 7, computed live): Lumen apps
 * (org.z9x.*), the current HOME, the default keyboard, enabled accessibility services, notification
 * listeners, the screensaver, the speech services, the owner of an active VPN (ByeDPI) and any app with a
 * running VpnService, persistent apps, and the Google platform pieces (GMS, GSF, Play Store, Chromecast
 * built-in, Speech Services, TvProvider). For them Close only removes the card.
 */
final class ProtectedSet {
    private static final String TAG = "Z9xMem";
    private static final String[] FIXED = {
            "android", "com.android.systemui", "com.android.providers.tv", "com.android.bluetooth",
            "com.android.networkstack", "com.android.providers.settings", "com.android.tv.settings",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.apps.mediashell", "com.google.android.tts", "com.google.android.leanbacklauncher",
            "com.google.android.inputmethod.latin", "com.google.android.tvrecommendations",
    };

    private ProtectedSet() {}

    static Set<String> compute(Context ctx) {
        Set<String> s = new HashSet<>();
        for (String p : FIXED) s.add(p);
        s.add(ctx.getPackageName());
        try {
            RoleManager rm = ctx.getSystemService(RoleManager.class);
            if (rm != null) {
                s.addAll(rm.getRoleHolders(RoleManager.ROLE_HOME));
                s.addAll(rm.getRoleHolders(RoleManager.ROLE_ASSISTANT));
            }
        } catch (Throwable t) {
            Log.w(TAG, "roles: " + t);
        }
        addSecure(ctx, s, Settings.Secure.DEFAULT_INPUT_METHOD, ':');
        addSecure(ctx, s, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, ':');
        addSecure(ctx, s, "enabled_notification_listeners", ':');
        addSecure(ctx, s, "screensaver_components", ',');
        addSecure(ctx, s, "voice_recognition_service", ':');
        addSecure(ctx, s, "voice_interaction_service", ':');
        addVpn(ctx, s);
        return s;
    }

    static boolean isProtected(Context ctx, Set<String> set, String pkg) {
        if (pkg == null || set.contains(pkg) || pkg.startsWith("org.z9x.")) return true;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void addSecure(Context ctx, Set<String> s, String key, char sep) {
        try {
            String v = Settings.Secure.getString(ctx.getContentResolver(), key);
            if (TextUtils.isEmpty(v)) return;
            for (String part : TextUtils.split(v, sep == ':' ? ":" : ",")) {
                ComponentName cn = ComponentName.unflattenFromString(part.trim());
                if (cn != null) s.add(cn.getPackageName());
                else if (!part.trim().isEmpty() && !part.contains("/")) s.add(part.trim());
            }
        } catch (Throwable t) {
            Log.w(TAG, key + ": " + t);
        }
    }

    /** VPN owner (NETWORK_SETTINGS keeps the owner uid) and every running VpnService. */
    private static void addVpn(Context ctx, Set<String> s) {
        PackageManager pm = ctx.getPackageManager();
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    int uid = nc.getOwnerUid();
                    String[] pkgs = uid > 0 ? pm.getPackagesForUid(uid) : null;
                    if (pkgs != null) for (String p : pkgs) s.add(p);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "vpn networks: " + t);
        }
        try {
            ActivityManager am = ctx.getSystemService(ActivityManager.class);
            @SuppressWarnings("deprecation")
            List<ActivityManager.RunningServiceInfo> rs = am == null ? null : am.getRunningServices(200);
            if (rs == null) return;
            for (ActivityManager.RunningServiceInfo r : rs) {
                if (r.service == null) continue;
                try {
                    ServiceInfo si = pm.getServiceInfo(r.service, 0);
                    if ("android.permission.BIND_VPN_SERVICE".equals(si.permission)) s.add(r.service.getPackageName());
                } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.w(TAG, "vpn services: " + t);
        }
    }
}
