package org.z9x.projector.lens;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.MediaDataSource;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The pictures, videos and music of the magnetic lenses ("Creative optical filter"), read at runtime straight from
 * the stock app that ships them: com.xgimi.starmirror 1.0.0.13 on the vendor partition (v1.7.116:
 * /vendor/priv-app/release_838_starmirror/release_838_starmirror.apk, 95 MB). Nothing of it is copied into the
 * Lumen image or this repository, and the stock app is never installed or run.
 *
 * Where the APK is: Lumen hides /vendor/app and /vendor/priv-app behind an empty folder at post-fs-data (XGIMI's
 * android.uid.system apps abort the package scan, overlay/play6/play6_compat.rc), so the vendor path is empty at
 * runtime. play6_compat.rc binds the one starmirror folder, before the hide, onto the image's empty folder
 * {@value #LUMEN_DIR}: the same vendor file (label vendor_app_file, read only), not a copy, and in no folder the
 * package manager scans. The vendor path itself is tried second (a build without the hide).
 *
 * How the media is found: the APK's resource file paths are shortened by its build (res/HE.mp4, not
 * res/raw/full_moon.mp4), so a zip entry cannot be found by its name; the resource table can. The APK is opened as
 * an archive (PackageManager.getPackageArchiveInfo, then getResourcesForApplication on that ApplicationInfo: an
 * AssetManager over the file, nothing installed), a raw resource by its name (getIdentifier) gives an
 * AssetFileDescriptor = the APK file, the offset of the stored (uncompressed) entry and its length. The players read
 * that range through {@link RangeSource} (MediaDataSource, pread in this process): no file descriptor of the
 * vendor file is handed to mediaserver, which may have no SELinux right on vendor_app_file.
 *
 * Which media: the stock table assets/config/en_mirror_config.json of the same APK (cn_mirror_config.json as a
 * fallback; both list the same items), chosen as stock PlayMaterialActivity.loadCategoryList does for a lens:
 * the categories whose mirrorIds hold the lens id and whose memory is true, of those the one with the lowest
 * category id; its items in order, video "raw:&lt;name&gt;" and soundtrack "raw:&lt;name&gt;" or none. On v1.7.116
 * that is lens 1 Lunar (7 moon stills, no music), 2 Dreamscape (3 videos), 3 Ripple (3 videos), 4 Sunset
 * (7 stills), each video or still with its own soundtrack except Lunar. No category (lens 5 and unknown ids, or an
 * unreadable table): the stock fallback of loadCategoryList (5 mirror_5, 1 / unknown emei_moon, 2 / 3 / 4 the
 * first item of their category), no music. A still is a 1 s HEVC clip of one picture; LensMode shows its first
 * frame instead of looping the clip.
 *
 * Threads: {@link #get} and {@link #open} do file I/O, the p6-lens worker of LensMode only. Log tag P6Lens.
 */
final class LensContent {
    private static final String TAG = LensMode.TAG;
    static final String PKG = "com.xgimi.starmirror";
    /** Lumen's bind of the vendor folder (overlay/play6/play6_compat.rc). */
    static final String LUMEN_DIR = "/system/etc/lumen_lens";
    private static final String VENDOR_DIR = "/vendor/priv-app/release_838_starmirror";
    private static final String APK_NAME = "release_838_starmirror.apk";
    private static final String[] DIRS = {LUMEN_DIR, VENDOR_DIR};
    private static final String[] CONFIGS = {"config/en_mirror_config.json", "config/cn_mirror_config.json"};
    private static final String RAW = "raw:";
    private static final int CONFIG_MAX_BYTES = 1 << 20;

    /** One item of a lens: the video (or still) resource and its soundtrack (null = none). */
    static final class Item {
        final String video, audio;

        Item(String video, String audio) {
            this.video = video;
            this.audio = audio;
        }

        @Override public String toString() { return video + (audio != null ? " + " + audio : ""); }
    }

    final String apk;
    private final Resources res;
    private final JSONArray table;

    private static LensContent sCached;
    private static volatile String sFailure = "not read yet";

    private LensContent(String apk, Resources res, JSONArray table) {
        this.apk = apk;
        this.res = res;
        this.table = table;
    }

    /** Why the last {@link #get} returned null (for the log). Any thread. */
    static String lastFailure() { return sFailure; }

    /** The stock APK opened as an archive, or null (logged; tried again on the next call). p6-lens. */
    static synchronized LensContent get(Context app) {
        if (sCached != null) return sCached;
        StringBuilder why = new StringBuilder();
        for (String dir : DIRS) {
            for (String path : candidates(dir)) {
                LensContent c = openApk(app, path, why);
                if (c != null) {
                    sCached = c;
                    sFailure = null;
                    return c;
                }
            }
        }
        sFailure = why.length() == 0 ? "no APK in " + LUMEN_DIR + " or " + VENDOR_DIR : why.toString();
        Log.w(TAG, "stock lens content not available: " + sFailure);
        return null;
    }

    /** The stock file name first, then any other .apk of the folder (another vendor build). */
    private static List<String> candidates(String dir) {
        List<String> out = new ArrayList<>();
        File d = new File(dir);
        File named = new File(d, APK_NAME);
        if (named.isFile()) out.add(named.getPath());
        File[] all = d.listFiles();
        if (all != null) {
            for (File f : all) {
                if (f.isFile() && f.getName().endsWith(".apk") && !f.getName().equals(APK_NAME)) out.add(f.getPath());
            }
        }
        return out;
    }

    private static LensContent openApk(Context app, String path, StringBuilder why) {
        try {
            File f = new File(path);
            if (!f.canRead()) {
                why.append(path).append(": not readable (SELinux or mode); ");
                return null;
            }
            PackageManager pm = app.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
            if (pi == null || pi.applicationInfo == null) {
                why.append(path).append(": not a readable package; ");
                return null;
            }
            if (!PKG.equals(pi.packageName)) {
                why.append(path).append(": package ").append(pi.packageName).append(", not ").append(PKG).append("; ");
                return null;
            }
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = path;
            ai.publicSourceDir = path;
            Resources res = pm.getResourcesForApplication(ai);
            JSONArray table = readTable(res);
            Log.i(TAG, "stock lens content: " + path + " (" + PKG + " " + pi.versionName + ", " + f.length() / (1024 * 1024)
                    + " MB), table " + (table != null ? table.length() + " categories" : "unreadable: stock fallback only"));
            return new LensContent(path, res, table);
        } catch (Throwable t) {
            why.append(path).append(": ").append(t).append("; ");
            return null;
        }
    }

    private static JSONArray readTable(Resources res) {
        for (String name : CONFIGS) {
            try (InputStream in = res.getAssets().open(name)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > CONFIG_MAX_BYTES) throw new IOException("larger than " + CONFIG_MAX_BYTES + " bytes");
                }
                return new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (Throwable t) {
                Log.w(TAG, "table " + name + ": " + t);
            }
        }
        return null;
    }

    /**
     * The items of lens {@code lens} in stock order (see the class comment), only those whose video resource exists;
     * empty when nothing is left. p6-lens.
     */
    List<Item> items(int lens) {
        List<Item> out = new ArrayList<>();
        JSONObject best = null;
        int bestId = Integer.MAX_VALUE;
        if (table != null) {
            for (int i = 0; i < table.length(); i++) {
                JSONObject cat = table.optJSONObject(i);
                if (cat == null || !cat.optBoolean("memory", true) || !holds(cat, lens)) continue;
                int id = cat.optInt("id", Integer.MAX_VALUE);
                if (best == null || id < bestId) {
                    best = cat;
                    bestId = id;
                }
            }
        }
        JSONArray list = best == null ? null : best.optJSONArray("screensaverList");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.optJSONObject(i);
                if (it == null) continue;
                String video = rawName(it.optString("videoUrl", ""));
                String audio = rawName(it.optString("audioUrl", ""));
                if (video == null || !exists(video)) {
                    Log.w(TAG, "lens " + lens + ": item " + it.optString("videoUrl") + " skipped (no such raw resource)");
                    continue;
                }
                out.add(new Item(video, audio != null && exists(audio) ? audio : null));
            }
        }
        if (out.isEmpty()) {
            String fallback = fallback(lens);
            if (exists(fallback)) out.add(new Item(fallback, null));
            Log.i(TAG, "lens " + lens + ": no category in the stock table, stock fallback " + fallback);
        } else {
            Log.i(TAG, "lens " + lens + ": category " + bestId + ", " + out.size() + " items " + out);
        }
        return Collections.unmodifiableList(out);
    }

    /** Stock PlayMaterialActivity.loadCategoryList without data: one picture per lens id. */
    private static String fallback(int lens) {
        switch (lens) {
            case 2: return "blue_bridge_autumn_moon";
            case 3: return "stream_light_1";
            case 4: return "sunset_red";
            case 5: return "mirror_5";
            default: return "emei_moon";
        }
    }

    private static boolean holds(JSONObject cat, int lens) {
        JSONArray ids = cat.optJSONArray("mirrorIds");
        if (ids == null) return cat.optInt("mirrorId", Integer.MIN_VALUE) == lens;
        for (int i = 0; i < ids.length(); i++) if (ids.optInt(i, Integer.MIN_VALUE) == lens) return true;
        return false;
    }

    /** "raw:name" -> name; anything else (cloud URL, empty) -> null. */
    private static String rawName(String url) {
        if (url == null || !url.startsWith(RAW)) return null;
        String n = url.substring(RAW.length()).trim();
        return n.matches("[a-z0-9_]+") ? n : null;
    }

    private boolean exists(String raw) {
        return raw != null && res.getIdentifier(raw, "raw", PKG) != 0;
    }

    /**
     * The stored range of raw resource {@code raw} inside the APK. Throws when it is missing or compressed (stock
     * stores every mp4 / mp3 uncompressed). The caller closes it (RangeSource does). p6-lens.
     */
    AssetFileDescriptor open(String raw) throws IOException {
        int id = res.getIdentifier(raw, "raw", PKG);
        if (id == 0) throw new IOException("no raw resource " + raw);
        try {
            AssetFileDescriptor afd = res.openRawResourceFd(id);
            if (afd == null) throw new IOException("raw resource " + raw + " is compressed");
            if (afd.getLength() <= 0) {
                afd.close();
                throw new IOException("raw resource " + raw + " has no stored length");
            }
            return afd;
        } catch (Resources.NotFoundException e) {
            throw new IOException("raw resource " + raw + ": " + e.getMessage());
        }
    }

    /**
     * A stored zip entry as a MediaDataSource: positional reads (pread) of the APK file inside [offset, offset +
     * length), in this process. MediaPlayer and MediaMetadataRetriever call it on their own threads; pread keeps no
     * file position, so concurrent reads are safe. close() closes the descriptor (also called by the players).
     */
    static final class RangeSource extends MediaDataSource {
        private final AssetFileDescriptor afd;
        private final long start, size;
        private volatile boolean closed;

        RangeSource(AssetFileDescriptor afd) {
            this.afd = afd;
            this.start = afd.getStartOffset();
            this.size = afd.getLength();
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("closed");
            if (position < 0 || position >= size) return -1;
            if (length <= 0) return 0;
            int n = (int) Math.min(length, size - position);
            try {
                int r = Os.pread(afd.getFileDescriptor(), buffer, offset, n, start + position);
                return r > 0 ? r : -1;
            } catch (ErrnoException e) {
                throw new IOException("pread: " + e.getMessage());
            }
        }

        @Override
        public long getSize() { return size; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try { afd.close(); } catch (Throwable ignored) { }
        }
    }
}
