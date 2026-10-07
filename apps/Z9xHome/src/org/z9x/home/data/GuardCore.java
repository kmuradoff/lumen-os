package org.z9x.home.data;

/**
 * Pure decision logic of {@link IntentGuard} (no Android types, so it runs in plain JVM tests:
 * test/IntentGuardTest.java, SPEC T12).
 *
 * Lumen Home is privileged and platform-signed; every intent and image URI that another app wrote
 * into TvProvider or returned from a search provider is untrusted input, otherwise a malicious row
 * could make us start a protected component with our identity (confused deputy, SPEC 11).
 */
public final class GuardCore {
    private GuardCore() {}

    // android.content.Intent flag values (stable since API 1-30; copied to stay JVM-testable)
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    public static final int FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002;
    public static final int FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040;
    public static final int FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080;
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_CLEAR_TOP = 0x04000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;
    public static final int ALLOWED_FLAGS = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP;

    public static final String ACTION_VIEW = "android.intent.action.VIEW";

    /** What PackageManager resolved for an intent. */
    public static final class Target {
        public final String pkg;
        public final boolean exported;
        public final String permission;         // ActivityInfo.permission, may be null
        public final boolean permissionOfRowPkg; // that permission is granted to the row's package

        public Target(String pkg, boolean exported, String permission, boolean permissionOfRowPkg) {
            this.pkg = pkg;
            this.exported = exported;
            this.permission = permission;
            this.permissionOfRowPkg = permissionOfRowPkg;
        }
    }

    /** Only NEW_TASK | CLEAR_TOP | SINGLE_TOP survive; every FLAG_GRANT_* and other activity flag is dropped. */
    public static int sanitizeFlags(int flags) {
        return flags & ALLOWED_FLAGS;
    }

    /**
     * @param rowPkg      package that owns the TvProvider row / search provider
     * @param action      intent action (may be null)
     * @param scheme      data scheme (may be null)
     * @param explicitPkg package of an explicit component or setPackage(), or null
     * @param target      resolution of the sanitized intent (null = nothing resolves)
     * @param browsable   resolution with CATEGORY_BROWSABLE added (only needed for foreign deep links)
     * @return null when the intent may be started, else a short reason for the log
     */
    public static String decide(String rowPkg, String action, String scheme, String explicitPkg,
                                Target target, Target browsable) {
        if (rowPkg == null || rowPkg.isEmpty()) return "no-owner";
        if (scheme != null && "file".equalsIgnoreCase(scheme)) return "file-uri";
        if (target == null) return "unresolved";
        String r = checkTarget(target);
        if (r != null) return r;
        if (rowPkg.equals(target.pkg)) {
            // an explicit component of the row's own package is fine; a foreign explicit one never is
            return explicitPkg == null || rowPkg.equals(explicitPkg) ? null : "foreign-component";
        }
        // A foreign target is only allowed as a browsable deep link (ACTION_VIEW on a URL or custom
        // scheme, no explicit component): exactly what any web page could open.
        if (explicitPkg != null) return "foreign-component";
        if (!ACTION_VIEW.equals(action)) return "foreign-action";
        if (scheme == null || scheme.isEmpty()) return "foreign-no-scheme";
        String s = scheme.toLowerCase(java.util.Locale.ROOT);
        if (s.equals("content") || s.equals("android.resource") || s.equals("intent")) return "foreign-scheme";
        if (browsable == null) return "not-browsable";
        r = checkTarget(browsable);
        if (r != null) return r;
        return null;
    }

    private static String checkTarget(Target t) {
        if (!t.exported) return "not-exported";
        if (t.permission != null && !t.permission.isEmpty() && !t.permissionOfRowPkg) return "permission";
        return null;
    }

    /**
     * Image URI policy (SPEC 11.2).
     * @param authorityOwner package owning the content:// authority (null if unknown)
     * @param resourcePkg    package named by an android.resource:// URI
     * @return null if allowed, else a reason
     */
    public static String imageDecision(String scheme, String authorityOwner, String resourcePkg, String rowPkg) {
        if (scheme == null) return "no-scheme";
        switch (scheme.toLowerCase(java.util.Locale.ROOT)) {
            case "http":
            case "https":
                return null;
            case "content":
                return rowPkg != null && rowPkg.equals(authorityOwner) ? null : "foreign-authority";
            case "android.resource":
                return rowPkg != null && rowPkg.equals(resourcePkg) ? null : "foreign-resource";
            default:
                return "scheme-" + scheme;
        }
    }
}
