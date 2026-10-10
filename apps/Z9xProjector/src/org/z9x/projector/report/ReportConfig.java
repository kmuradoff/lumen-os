package org.z9x.projector.report;

import android.os.SystemProperties;
import android.util.Log;

/**
 * Lumen OS 1.0.1 problem reports: switches and limits. The whole feature is OFF unless the image sets
 * {@code ro.z9x.report.url} to an https URL (the report Worker's POST /v1/report, see
 * gsi/cloud/report-worker/README.md). 1.0.1 ships without the property: the Settings row is hidden and
 * nothing in this package ever runs. A report is only ever sent from ReportActivity after the user
 * pressed "Send report": no background, scheduled or automatic sending exists.
 */
public final class ReportConfig {
    static final String TAG = "Z9xReport";

    /** Read-only image property with the upload URL; empty or not https = feature off. */
    public static final String PROP_URL = "ro.z9x.report.url";

    /** Logcat window. The number is also written in the text report_inc_logs (all languages). */
    static final int LOG_MINUTES = 30;
    /** Upper bound of the zip we send; the Worker accepts up to 5 MiB (MAX_BYTES in validate.js). */
    static final long MAX_ZIP_BYTES = 5_000_000L;
    /** Raw (scrubbed, uncompressed) caps per section; ReportZip halves them while the zip is too big. */
    static final long CAP_LOGCAT = 6L << 20;
    static final long CAP_KERNEL = 1L << 20;
    static final long CAP_PREV_BOOT = 1L << 20;
    static final long CAP_PSTORE = 512L << 10;
    static final long CAP_OTA = 256L << 10;
    static final long CAP_DIAG = 256L << 10;
    static final long CAP_DROPBOX_ENTRY = 256L << 10;
    static final long CAP_DROPBOX_TOTAL = 2L << 20;
    /** Dropbox and exit-reason window. */
    static final long HISTORY_MS = 7L * 24 * 3600 * 1000;
    /** One logcat run; the first one may wait for Android's "allow access to device logs" dialog. */
    static final long LOGCAT_TIMEOUT_MS = 120_000;
    static final long SHORT_EXEC_TIMEOUT_MS = 15_000;

    private ReportConfig() {}

    /** The upload URL, or null when the feature is off. Any thread. */
    static String url() {
        String u;
        try {
            u = SystemProperties.get(PROP_URL, "").trim();
        } catch (Throwable t) {
            Log.w(TAG, "url: " + t);
            return null;
        }
        return u.startsWith("https://") && u.length() > "https://".length() ? u : null;
    }

    /** True when this image has a report server. */
    public static boolean enabled() {
        return url() != null;
    }
}
