package org.z9x.home.img;

import android.graphics.Bitmap;
import android.util.Log;

import org.z9x.home.App;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * cacheDir/img: downscaled "master" copies of every piece of art (JPEG q88, PNG for icons with alpha).
 * LRU by modification time, trimmed to 64 MB once per process start. The system may clear it any time;
 * that only costs a re-download.
 */
final class DiskCache {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long TOUCH_AFTER_MS = 24L * 3600_000L;
    private final File mDir;

    DiskCache(File cacheDir) {
        mDir = new File(cacheDir, "img");
        //noinspection ResultOfMethodCallIgnored
        mDir.mkdirs();
    }

    File file(String key, boolean png) {
        return new File(mDir, hash(key) + (png ? ".png" : ".jpg"));
    }

    /** Returns the cached file or null; refreshes its LRU time at most once a day. */
    File get(String key, boolean png) {
        File f = file(key, png);
        if (!f.isFile() || f.length() == 0) return null;
        long now = System.currentTimeMillis();
        if (now - f.lastModified() > TOUCH_AFTER_MS) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(now);
        }
        return f;
    }

    File put(String key, Bitmap sw, boolean png) {
        File f = file(key, png);
        File tmp = new File(mDir, f.getName() + ".tmp" + Thread.currentThread().getId());
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            sw.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, png ? 100 : 88, out);
        } catch (Throwable t) {
            Log.w(App.TAG, "disk cache write: " + t);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
        return f;
    }

    void trim() {
        File[] fs = mDir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) {
            if (f.getName().contains(".tmp")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                continue;
            }
            total += f.length();
        }
        if (total <= MAX_BYTES) return;
        Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int n = 0;
        for (File f : fs) {
            if (total <= MAX_BYTES * 3 / 4) break;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                n++;
            }
        }
        Log.i(App.TAG, "img cache trimmed " + n + " files, now " + (total >> 20) + " MB");
    }

    static String hash(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
