package org.z9x.home.usb;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The files of the USB sticks for Files (Lumen OS 1.0.4, in place of XGIMI's file manager): the mounted,
 * removable volumes (as UsbApks), and per folder its sub-folders and the files Lumen can open: video and
 * music (another app or the built-in player), photos (the built-in viewer) and apps (Install from USB).
 * Other files and hidden ones (".", macOS "._") are not listed. Read through MANAGE_EXTERNAL_STORAGE, granted
 * to the platform-signed Lumen Home by signature.
 */
public final class UsbFiles {
    public static final int DIR = 0, VIDEO = 1, AUDIO = 2, IMAGE = 3, APK = 4, OTHER = 5;
    /** More entries than this in one folder: the rest is counted, not listed (each row is a view). */
    static final int MAX_ENTRIES = 400;

    private static final Map<String, Integer> TYPES = new HashMap<>();
    private static final Map<String, String> MIME = new HashMap<>();

    static {
        for (String e : new String[]{"mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "webm", "wmv", "flv",
                "mpg", "mpeg", "vob", "3gp", "rmvb", "rm", "divx", "ogv", "asf"}) TYPES.put(e, VIDEO);
        for (String e : new String[]{"mp3", "flac", "aac", "m4a", "ogg", "oga", "opus", "wav", "wma", "ape", "ac3",
                "eac3", "dts", "mka", "aif", "aiff", "amr"}) TYPES.put(e, AUDIO);
        for (String e : new String[]{"jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"}) TYPES.put(e, IMAGE);
        TYPES.put("apk", APK);
        // types MimeTypeMap does not know (players filter on video/* or audio/*)
        MIME.put("mkv", "video/x-matroska");
        MIME.put("mka", "audio/x-matroska");
        MIME.put("ts", "video/mp2t");
        MIME.put("m2ts", "video/mp2t");
        MIME.put("mts", "video/mp2t");
        MIME.put("rmvb", "video/x-pn-realvideo");
        MIME.put("rm", "video/x-pn-realvideo");
        MIME.put("divx", "video/x-msvideo");
        MIME.put("vob", "video/mpeg");
        MIME.put("asf", "video/x-ms-asf");
        MIME.put("ape", "audio/x-ape");
        MIME.put("ac3", "audio/ac3");
        MIME.put("eac3", "audio/eac3");
        MIME.put("dts", "audio/vnd.dts");
        MIME.put("opus", "audio/ogg");
        MIME.put("heic", "image/heic");
        MIME.put("heif", "image/heif");
        MIME.put("apk", "application/vnd.android.package-archive");
    }

    private UsbFiles() {}

    /** A mounted USB stick. */
    public static final class Stick {
        public File root;
        public String label = "";
    }

    /** One row of a folder. */
    public static final class Entry {
        public File file;
        public int type;
        public long size;
    }

    /** A folder's rows: sub-folders first, then files, each by name; {@code hidden} rows over the limit. */
    public static final class Listing {
        public final List<Entry> entries = new ArrayList<>();
        public int hidden;
        public boolean readable = true;
    }

    public static List<Stick> sticks(Context c) {
        List<Stick> out = new ArrayList<>();
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm == null) return out;
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File root = v.getDirectory();
            if (root == null) continue;
            Stick s = new Stick();
            s.root = root;
            String d = v.getDescription(c);
            s.label = d == null || d.isEmpty() ? root.getName() : d;
            out.add(s);
        }
        return out;
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static int type(File f) {
        if (f.isDirectory()) return DIR;
        Integer t = TYPES.get(ext(f.getName()));
        return t == null ? OTHER : t;
    }

    /** The MIME type players and viewers are asked with, from the extension. */
    public static String mime(String name) {
        String e = ext(name);
        String m = MIME.get(e);
        if (m == null) m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m != null) return m;
        Integer t = TYPES.get(e);
        if (t == null) return "application/octet-stream";
        return t == VIDEO ? "video/*" : t == AUDIO ? "audio/*" : t == IMAGE ? "image/*" : "application/octet-stream";
    }

    /** Worker thread. */
    public static Listing list(File dir) {
        Listing l = new Listing();
        File[] all = dir.listFiles();
        if (all == null) {
            l.readable = false;
            return l;
        }
        List<Entry> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.startsWith(".") || n.startsWith("$") || "System Volume Information".equals(n)) continue;
            int t = type(f);
            if (t == OTHER) continue;
            Entry e = new Entry();
            e.file = f;
            e.type = t;
            e.size = t == DIR ? 0 : f.length();
            (t == DIR ? dirs : files).add(e);
        }
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.SECONDARY);
        Collections.sort(dirs, (a, b) -> col.compare(a.file.getName(), b.file.getName()));
        Collections.sort(files, (a, b) -> col.compare(a.file.getName(), b.file.getName()));
        for (List<Entry> part : List.of(dirs, files)) {
            for (Entry e : part) {
                if (l.entries.size() < MAX_ENTRIES) l.entries.add(e);
                else l.hidden++;
            }
        }
        return l;
    }

    /** The photos of a folder in list order (the viewer's LEFT / RIGHT). Worker thread. */
    public static List<File> photos(File dir) {
        List<File> out = new ArrayList<>();
        for (Entry e : list(dir).entries) if (e.type == IMAGE) out.add(e.file);
        return out;
    }

    /** True when {@code f} lies on a mounted USB stick (canonical paths: no "..", no link out of it). */
    public static boolean onStick(Context c, File f) {
        try {
            String p = f.getCanonicalPath();
            for (Stick s : sticks(c)) {
                String r = s.root.getCanonicalPath();
                if (p.equals(r) || p.startsWith(r + "/")) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /** 1.2 GB / 734 MB / 88 KB, decimal units like Android's own Files. */
    public static String size(long b) {
        if (b >= 1_000_000_000L) return String.format(Locale.getDefault(), "%.1f GB", b / 1e9);
        if (b >= 1_000_000L) return String.format(Locale.getDefault(), "%.0f MB", b / 1e6);
        return String.format(Locale.getDefault(), "%d KB", Math.max(1, (b + 999) / 1000));
    }
}
