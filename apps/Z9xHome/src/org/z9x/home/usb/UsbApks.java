package org.z9x.home.usb;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The .apk files on the USB sticks (Install from USB, 1.0.1): every removable, mounted volume
 * (StorageManager, like the updater's USB check), its root and the folders apks/ and lumen-os/, one level
 * deep. Plain .apk files only (no .apks / .xapk bundles). Read through MANAGE_EXTERNAL_STORAGE, granted to
 * the platform-signed Lumen Home by signature.
 */
public final class UsbApks {
    private static final String TAG = "Z9xHomeUsb";
    /** The folders looked at on every stick ("" = its root). */
    static final String[] DIRS = {"", "apks", "lumen-os"};
    /** More files than this on one stick: the rest is not listed (a stick full of APKs stays usable). */
    static final int MAX_FILES = 200;

    private UsbApks() {}

    /** One installable file. */
    public static final class Entry {
        public File file;
        public String where = "";        // apks/foo.apk, relative to the stick
        public String pkg = "";
        public String label = "";
        public String versionName = "";
        public long versionCode;
        public Drawable icon;
        public boolean installed;        // an app of this package is installed (the file is an update)
    }

    /** Result of a scan: no stick, or the sticks' APKs (maybe none). */
    public static final class Scan {
        public int sticks;
        public final List<Entry> apks = new ArrayList<>();
    }

    /** A file name that may be an APK: *.apk (any case), not a hidden / macOS "._" resource file. */
    public static boolean candidate(String name) {
        return name != null && !name.startsWith(".") && name.length() > 4
                && name.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    /** The mounted, removable volumes' roots. */
    static List<File> roots(Context c) {
        List<File> out = new ArrayList<>();
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm == null) return out;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File root = v.getDirectory();
            if (root != null) out.add(root);
        }
        return out;
    }

    /** The candidate files of one stick, by folder, then by name. */
    public static List<File> files(File root) {
        List<File> out = new ArrayList<>();
        for (String d : DIRS) {
            File dir = d.isEmpty() ? root : new File(root, d);
            File[] l = dir.listFiles();
            if (l == null) continue;
            Arrays.sort(l, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : l) {
                if (out.size() >= MAX_FILES) return out;
                if (candidate(f.getName()) && f.isFile() && f.length() > 0) out.add(f);
            }
        }
        return out;
    }

    /** Scan every stick (worker thread: parses each APK's manifest). */
    public static Scan scan(Context c) {
        Scan s = new Scan();
        PackageManager pm = c.getPackageManager();
        for (File root : roots(c)) {
            s.sticks++;
            for (File f : files(root)) {
                Entry e = read(pm, f, root);
                if (e != null) s.apks.add(e);
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(s.apks, (a, b) -> col.compare(a.label, b.label));
        return s;
    }

    /** Label, icon and version of an APK file, or null if it is not one Android can read. */
    static Entry read(PackageManager pm, File f, File root) {
        try {
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.PackageInfoFlags.of(0));
            if (pi == null || pi.applicationInfo == null) {
                Log.i(TAG, "not an APK Android can read: " + f);
                return null;
            }
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = f.getAbsolutePath();
            ai.publicSourceDir = f.getAbsolutePath();
            Entry e = new Entry();
            e.file = f;
            String rp = root.getAbsolutePath(), fp = f.getAbsolutePath();
            e.where = fp.startsWith(rp + "/") ? fp.substring(rp.length() + 1) : f.getName();
            e.pkg = pi.packageName;
            CharSequence l = ai.loadLabel(pm);
            e.label = l == null || l.length() == 0 ? pi.packageName : l.toString();
            e.versionName = pi.versionName == null ? "" : pi.versionName;
            e.versionCode = pi.getLongVersionCode();
            try {
                e.icon = ai.loadIcon(pm);
            } catch (Throwable t) {
                e.icon = null;
            }
            try {
                pm.getPackageInfo(pi.packageName, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
                e.installed = true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            return e;
        } catch (Throwable t) {
            Log.w(TAG, "read " + f + ": " + t);
            return null;
        }
    }
}
