package org.lumen.common;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.UserManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lumen OS 1.0.8 bridge: the one-time import of an app's data from its pre-1.0.8 package (org.z9x.X) into
 * this package (org.lumen.X). Copied unchanged into each app as src/org/lumen/common/LumenImport.java by
 * gsi/apps/common/sync.sh (item migrate); never edit the copies.
 *
 * Android gives a renamed app a new uid and an empty data folder, and wipes the data of a system package that
 * disappears. So the 1.0.8 image keeps every old package as a tiny bridge (apps/LumenBridge): same name, same
 * signature, higher versionCode, nothing but a read-only provider "org.z9x.X.bridge" that answers only to
 * org.lumen.X signed with the same key. This class asks it for the old files and copies them in:
 *  - device-protected storage at once, credential-protected storage once the user is unlocked;
 *  - only files the new app does not have yet (never overwrites), caches and the OTA download are skipped;
 *  - inside shared_prefs, "org.z9x." becomes "org.lumen." (component names, TV input ids, favorites);
 *  - copy, never move: a rollback to the old slot still finds its data.
 * A marker in the new app's device-protected prefs ("lumen_import") makes each half run once. Call
 * {@link #run(Context)} first thing in Application.attachBaseContext, before any getSharedPreferences.
 */
public final class LumenImport {
    private static final String TAG = "LumenImport";
    public static final String OLD_PREFIX = "org.z9x.";
    public static final String NEW_PREFIX = "org.lumen.";
    private static final String MARK = "lumen_import";
    private static final String K_DE = "de_done", K_CE = "ce_done";
    private static final long MAX_FILE = 16L << 20;
    private static volatile boolean sWaiting;

    private LumenImport() {}

    /** The old package name of this app, or null when it never had one. */
    public static String oldPackage(String pkg) {
        return pkg.startsWith(NEW_PREFIX) ? OLD_PREFIX + pkg.substring(NEW_PREFIX.length()) : null;
    }

    public static void run(Context base) {
        try {
            String old = oldPackage(base.getPackageName());
            if (old == null) return;
            Context de = base.createDeviceProtectedStorageContext();
            SharedPreferences m = de.getSharedPreferences(MARK, Context.MODE_PRIVATE);
            if (m.getBoolean(K_DE, false) && m.getBoolean(K_CE, false)) return;
            String auth = old + ".bridge";
            if (base.getPackageManager().resolveContentProvider(auth, 0) == null) {
                // no bridge in this image (a fresh install, or a later release): nothing to import, ever
                m.edit().putBoolean(K_DE, true).putBoolean(K_CE, true).commit();
                return;
            }
            if (!m.getBoolean(K_DE, false)) {
                int n = copyAll(base, auth, "de", de.getDataDir());
                if (n >= 0) {
                    m.edit().putBoolean(K_DE, true).commit();
                    Log.i(TAG, old + ": " + n + " device-protected files imported");
                }
            }
            if (!m.getBoolean(K_CE, false)) {
                UserManager um = base.getSystemService(UserManager.class);
                if (um == null || um.isUserUnlocked()) importCe(base, auth, m);
                else waitForUnlock(base, auth, m);
            }
        } catch (Throwable t) {
            Log.w(TAG, "import failed (the app starts with empty settings): " + t);
        }
    }

    /** Secure settings that the system stores with our component names or TV input ids in them. */
    private static final String[] SECURE_KEYS = {
            "screensaver_components", "screensaver_default_component", "tv_input_custom_labels",
            "tv_input_hidden_inputs", "assistant", "voice_interaction_service", "voice_recognition_service",
    };

    /**
     * Once per device, from the projector app only (it holds WRITE_SECURE_SETTINGS): rewrite "org.z9x." to
     * "org.lumen." in the Secure settings that name our components (screensavers, HDMI input labels and hidden
     * inputs, the assistant). Returns true when something changed. Marker: "settings_done" in this app's
     * device-protected "lumen_import" prefs.
     */
    public static boolean migrateSystemSettings(Context c) {
        SharedPreferences m = c.createDeviceProtectedStorageContext().getSharedPreferences(MARK, Context.MODE_PRIVATE);
        if (m.getBoolean("settings_done", false)) return false;
        boolean changed = false;
        try {
            ContentResolver cr = c.getContentResolver();
            for (String k : SECURE_KEYS) {
                String v = android.provider.Settings.Secure.getString(cr, k);
                if (v != null && v.contains(OLD_PREFIX)) {
                    String nv = v.replace(OLD_PREFIX, NEW_PREFIX);
                    if (android.provider.Settings.Secure.putString(cr, k, nv)) {
                        changed = true;
                        Log.i(TAG, "secure " + k + ": " + v + " -> " + nv);
                    }
                }
            }
            m.edit().putBoolean("settings_done", true).commit();
        } catch (Throwable t) {
            Log.w(TAG, "secure settings not migrated: " + t);
        }
        return changed;
    }

    /** True once both halves ran (or there was nothing to import). */
    public static boolean done(Context c) {
        SharedPreferences m = c.createDeviceProtectedStorageContext().getSharedPreferences(MARK, Context.MODE_PRIVATE);
        return m.getBoolean(K_DE, false) && m.getBoolean(K_CE, false);
    }

    private static void importCe(Context base, String auth, SharedPreferences m) {
        int n = copyAll(base, auth, "ce", base.getDataDir());
        if (n >= 0) {
            m.edit().putBoolean(K_CE, true).commit();
            Log.i(TAG, auth + ": " + n + " credential-protected files imported");
        }
    }

    private static void waitForUnlock(Context base, String auth, SharedPreferences m) {
        if (sWaiting) return;
        sWaiting = true;
        Context app = base.getApplicationContext() != null ? base.getApplicationContext() : base;
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    c.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                }
                try {
                    importCe(c, auth, m);
                } catch (Throwable t) {
                    Log.w(TAG, "credential-protected import failed: " + t);
                }
            }
        }, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED);
    }

    /** Copies every file the bridge lists for one storage; returns the number copied, -1 when it could not ask. */
    private static int copyAll(Context base, String auth, String storage, File root) {
        ContentResolver cr = base.getContentResolver();
        Uri u = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(auth).build();
        Bundle b;
        try {
            b = cr.call(u, "list", storage, null);
        } catch (Exception e) {
            Log.w(TAG, auth + " list " + storage + ": " + e);
            return -1;
        }
        if (b == null) return -1;
        String[] paths = b.getStringArray("paths");
        long[] sizes = b.getLongArray("sizes");
        if (paths == null) return 0;
        int n = 0;
        for (int i = 0; i < paths.length; i++) {
            String p = paths[i];
            if (!safe(p) || skip(p) || (sizes != null && i < sizes.length && sizes[i] > MAX_FILE)) continue;
            File dst = new File(root, p);
            if (dst.exists()) continue;
            if (copyOne(cr, u.buildUpon().appendPath(storage).appendEncodedPath(p).build(), dst, p)) n++;
        }
        return n;
    }

    private static boolean copyOne(ContentResolver cr, Uri src, File dst, String p) {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".lumen-import");
        try {
            File dir = dst.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(src, "r");
                 InputStream in = new FileInputStream(pfd.getFileDescriptor());
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                if (p.startsWith("shared_prefs/") && p.endsWith(".xml")) {
                    java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                    for (int r; (r = in.read(buf)) > 0; ) all.write(buf, 0, r);
                    String s = new String(all.toByteArray(), StandardCharsets.UTF_8);
                    out.write(s.replace(OLD_PREFIX, NEW_PREFIX).getBytes(StandardCharsets.UTF_8));
                } else {
                    for (int r; (r = in.read(buf)) > 0; ) out.write(buf, 0, r);
                }
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            tmp.delete();
            Log.w(TAG, "import " + p + ": " + e);
            return false;
        }
    }

    /** Relative, no "..", no leading slash. */
    static boolean safe(String p) {
        return p != null && !p.isEmpty() && !p.startsWith("/") && !p.contains("..") && !p.contains("\0");
    }

    /** Rebuildable or huge: caches, the OTA download, our own marker, the bridge's own files. */
    static boolean skip(String p) {
        return p.startsWith("cache/") || p.startsWith("code_cache/") || p.startsWith("no_backup/ota/")
                || p.startsWith("app_webview/") || p.equals("shared_prefs/" + MARK + ".xml")
                || p.startsWith("shared_prefs/lumen_bridge") || p.endsWith(".lumen-import");
    }
}
