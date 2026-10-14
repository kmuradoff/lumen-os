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
 * The files of Files (Lumen OS 1.0.4, in place of XGIMI's file manager): the projector's own storage
 * (/storage/emulated/0) and the mounted, removable volumes (as UsbApks); per folder its sub-folders and every
 * file: video and music (another app or the built-in player), photos (the built-in viewer), apps (installed
 * one by one) and other files (any app that opens the type). Hidden ones (".", macOS "._", "$...") are not
 * listed. Copy and delete for the long OK menu. Read and written through MANAGE_EXTERNAL_STORAGE, granted to
 * the platform-signed Lumen Home by signature.
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

    /** A place: a mounted USB stick, or the projector's own storage ({@code internal}). */
    public static final class Stick {
        public File root;
        public String label = "";
        public boolean internal;
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

    /** The projector's storage first, then the sticks. Worker thread. */
    public static List<Stick> places(Context c, String internalLabel) {
        List<Stick> out = new ArrayList<>();
        File in = Environment.getExternalStorageDirectory();
        if (in != null && in.isDirectory()) {
            Stick s = new Stick();
            s.root = in;
            s.label = internalLabel;
            s.internal = true;
            out.add(s);
        }
        out.addAll(sticks(c));
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

    /** True when {@code f} lies on a stick or in the projector's own storage (canonical paths). */
    public static boolean inPlaces(Context c, File f) {
        if (onStick(c, f)) return true;
        try {
            File in = Environment.getExternalStorageDirectory();
            if (in == null) return false;
            String p = f.getCanonicalPath(), r = in.getCanonicalPath();
            return p.equals(r) || p.startsWith(r + "/");
        } catch (IOException e) {
            return false;
        }
    }

    /** Progress of a copy: bytes done of total. */
    public interface Progress { void on(long done, long total); }

    /** Bytes of a file or a whole folder. Worker thread. */
    public static long bytes(File f) {
        if (!f.isDirectory()) return f.length();
        long n = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) n += bytes(x);
        return n;
    }

    /** A free name in {@code dir}: "name", else "name (2).ext", "name (3).ext" ... */
    public static File freeName(File dir, String name) {
        File t = new File(dir, name);
        if (!t.exists()) return t;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            t = new File(dir, base + " (" + i + ")" + ext);
            if (!t.exists()) return t;
        }
    }

    /**
     * Copies a file or a folder into {@code dir} under a free name. Worker thread. A failed copy removes what
     * it wrote. Returns the new file.
     */
    public static File copy(File src, File dir, Progress p) throws IOException {
        File dst = freeName(dir, src.getName());
        long total = Math.max(1, bytes(src));
        long[] done = {0};
        try {
            copyInto(src, dst, total, done, p);
        } catch (IOException | RuntimeException e) {
            delete(dst);
            throw e;
        }
        return dst;
    }

    private static void copyInto(File src, File dst, long total, long[] done, Progress p) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        if (src.isDirectory()) {
            if (!dst.mkdirs() && !dst.isDirectory()) throw new IOException("cannot create " + dst.getName());
            File[] l = src.listFiles();
            if (l != null) for (File x : l) copyInto(x, new File(dst, x.getName()), total, done, p);
            return;
        }
        byte[] buf = new byte[1 << 16];
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done[0] += n;
                if (p != null) p.on(done[0], total);
                if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
            }
            out.getFD().sync();
        }
    }

    /** Deletes a file or a whole folder. Worker thread. True when nothing is left. */
    public static boolean delete(File f) {
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File x : l) delete(x);
        }
        return f.delete() || !f.exists();
    }

    /** 1.2 GB / 734 MB / 88 KB, decimal units like Android's own Files. */
    public static String size(long b) {
        if (b >= 1_000_000_000L) return String.format(Locale.getDefault(), "%.1f GB", b / 1e9);
        if (b >= 1_000_000L) return String.format(Locale.getDefault(), "%.0f MB", b / 1e6);
        return String.format(Locale.getDefault(), "%d KB", Math.max(1, (b + 999) / 1000));
    }
}
