package org.z9x.home.data;

/** SPEC T12: crafted intents and art URIs from foreign TvProvider rows must be rejected. */
public final class IntentGuardTest {
    static final String ROW = "com.example.tvapp";
    static final String VIEW = GuardCore.ACTION_VIEW;

    static GuardCore.Target t(String pkg, boolean exported, String perm, boolean granted) {
        return new GuardCore.Target(pkg, exported, perm, granted);
    }

    public static void run() {
        // own deep link, own component: allowed
        T.eq(GuardCore.decide(ROW, VIEW, "https", null, t(ROW, true, null, false), null), null, "own https");
        T.eq(GuardCore.decide(ROW, null, null, ROW, t(ROW, true, null, false), null), null, "own explicit component");
        // a selector is stripped before resolution; what is left targets a foreign explicit component -> rejected
        T.eq(GuardCore.decide(ROW, null, null, "com.android.settings", t("com.android.settings", true, null, false), null),
                "foreign-component", "foreign explicit component");
        // same package but non-exported activity (e.g. an internal debug screen)
        T.eq(GuardCore.decide(ROW, VIEW, "myapp", ROW, t(ROW, false, null, false), null), "not-exported", "non-exported");
        // activity protected by a permission the row's app does not hold
        T.eq(GuardCore.decide(ROW, VIEW, "myapp", null, t(ROW, true, "android.permission.MASTER_CLEAR", false), null),
                "permission", "protected activity");
        T.eq(GuardCore.decide(ROW, VIEW, "myapp", null, t(ROW, true, "com.example.PERM", true), null), null, "perm held by row app");
        // foreign target only as a browsable VIEW deep link without explicit component
        T.eq(GuardCore.decide(ROW, VIEW, "https", null, t("com.android.chrome", true, null, false), t("com.android.chrome", true, null, false)),
                null, "foreign browsable https");
        T.eq(GuardCore.decide(ROW, VIEW, "https", null, t("com.other", true, null, false), null), "not-browsable", "foreign non-browsable");
        T.eq(GuardCore.decide(ROW, "android.intent.action.DELETE", "package", null, t("com.android.packageinstaller", true, null, false),
                t("com.android.packageinstaller", true, null, false)), "foreign-action", "foreign DELETE");
        T.eq(GuardCore.decide(ROW, VIEW, "content", null, t("com.other", true, null, false), t("com.other", true, null, false)),
                "foreign-scheme", "foreign content:// view");
        T.eq(GuardCore.decide(ROW, VIEW, "file", null, t(ROW, true, null, false), null), "file-uri", "file:// data");
        T.eq(GuardCore.decide(ROW, VIEW, "https", null, null, null), "unresolved", "unresolved");
        T.eq(GuardCore.decide("", VIEW, "https", null, t("x", true, null, false), null), "no-owner", "no owner");
        // FLAG_GRANT_READ_URI_PERMISSION and friends are dropped; only NEW_TASK|CLEAR_TOP|SINGLE_TOP survive
        int flags = GuardCore.FLAG_GRANT_READ_URI_PERMISSION | GuardCore.FLAG_GRANT_WRITE_URI_PERMISSION
                | GuardCore.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | GuardCore.FLAG_GRANT_PREFIX_URI_PERMISSION
                | GuardCore.FLAG_ACTIVITY_NEW_TASK | 0x00008000 /* CLEAR_TASK */ | 0x00800000 /* EXCLUDE_FROM_RECENTS */;
        T.eq(GuardCore.sanitizeFlags(flags), GuardCore.FLAG_ACTIVITY_NEW_TASK, "flags sanitized");
        // art URIs
        T.eq(GuardCore.imageDecision("file", null, null, ROW), "scheme-file", "file:// art");
        T.eq(GuardCore.imageDecision("https", null, null, ROW), null, "https art");
        T.eq(GuardCore.imageDecision("content", "com.other", null, ROW), "foreign-authority", "foreign content art");
        T.eq(GuardCore.imageDecision("content", ROW, null, ROW), null, "own content art");
        T.eq(GuardCore.imageDecision("android.resource", null, "android", ROW), "foreign-resource", "foreign resource art");
        T.eq(GuardCore.imageDecision("android.resource", null, ROW, ROW), null, "own resource art");
    }
}
