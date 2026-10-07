package org.z9x.home.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import org.z9x.home.App;

/**
 * Validates intents and art URIs coming from other apps (TvProvider rows, search suggestions)
 * before our privileged launcher uses them. Decision logic: {@link GuardCore}.
 */
public final class IntentGuard {
    private IntentGuard() {}

    /**
     * Parses and sanitizes {@code uri} for a row owned by {@code rowPkg}. Returns a startable intent
     * or null (then the caller opens the owning app instead).
     */
    public static Intent forRow(Context c, String uri, String rowPkg) {
        if (uri == null || uri.isEmpty()) return null;
        Intent i;
        try {
            i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (Throwable t) {
            Log.w(App.TAG, "intent rejected pkg=" + rowPkg + " reason=parse");
            return null;
        }
        return check(c, i, rowPkg);
    }

    /** Sanitizes an already built intent (search suggestions) owned by {@code rowPkg}. */
    public static Intent check(Context c, Intent i, String rowPkg) {
        i.setSelector(null);
        i.setClipData(null);
        i.setFlags(GuardCore.sanitizeFlags(i.getFlags()) | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setSourceBounds(null);
        PackageManager pm = c.getPackageManager();
        String explicitPkg = i.getComponent() != null ? i.getComponent().getPackageName() : i.getPackage();
        Uri data = i.getData();
        String scheme = data != null ? data.getScheme() : null;
        GuardCore.Target t = resolve(pm, i, rowPkg);
        GuardCore.Target b = null;
        if (t != null && !rowPkg.equals(t.pkg) && explicitPkg == null) {
            Intent bi = new Intent(i);
            bi.addCategory(Intent.CATEGORY_BROWSABLE);
            b = resolve(pm, bi, rowPkg);
            if (b != null) i.addCategory(Intent.CATEGORY_BROWSABLE);
        }
        String reason = GuardCore.decide(rowPkg, i.getAction(), scheme, explicitPkg, t, b);
        if (reason != null) {
            Log.w(App.TAG, "intent rejected pkg=" + rowPkg + " reason=" + reason);
            return null;
        }
        return i;
    }

    private static GuardCore.Target resolve(PackageManager pm, Intent i, String rowPkg) {
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return null;
        ActivityInfo ai = ri.activityInfo;
        // A chooser/resolver (several matches) is the framework's own and is fine to start.
        boolean granted = ai.permission == null
                || pm.checkPermission(ai.permission, rowPkg) == PackageManager.PERMISSION_GRANTED;
        return new GuardCore.Target(ai.packageName, ai.exported, ai.permission, granted);
    }

    /** True if art at {@code uri} may be loaded for a row of {@code rowPkg}. */
    public static boolean imageAllowed(Context c, Uri uri, String rowPkg) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        String owner = null;
        String resPkg = null;
        if ("content".equalsIgnoreCase(scheme) && uri.getAuthority() != null) {
            ProviderInfo pi = c.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            owner = pi != null ? pi.packageName : null;
        } else if ("android.resource".equalsIgnoreCase(scheme)) {
            resPkg = uri.getAuthority();
        }
        String r = GuardCore.imageDecision(scheme, owner, resPkg, rowPkg);
        if (r != null) {
            Log.w(App.TAG, "image rejected pkg=" + rowPkg + " reason=" + r);
            return false;
        }
        return true;
    }

    /** Launch intent of an app (leanback first). */
    public static Intent appLaunch(Context c, String pkg) {
        if (pkg == null) return null;
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }
}
