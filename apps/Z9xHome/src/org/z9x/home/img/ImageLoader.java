package org.z9x.home.img;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.data.IntentGuard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Art for every card (SPEC 10.2):
 *  - memory: LRU of HARDWARE bitmaps at exact card pixels (no texture upload on the UI thread),
 *    banners kept on UI_HIDDEN, everything else dropped;
 *  - disk: one downscaled "master" per source URI (covers 1280x720), so the 392x220 card and the
 *    1280x720 hero share one download; cards decode from it with ImageDecoder target size + crop;
 *  - fetch: https/http, content:// and android.resource:// of the row's own package only (IntentGuard),
 *    6 s timeout, 12 MB cap, sources over 4096 px refused;
 *  - 3 worker threads at background priority, newest request first (LIFO), duplicate requests merged.
 * Public methods are called on the main thread; callbacks arrive on the main thread.
 */
public final class ImageLoader {
    public static final int KIND_BANNER = 0;
    public static final int KIND_POSTER = 1;
    public static final int KIND_HERO = 2;
    public static final int KIND_ICON = 3;

    private static final long MEM_BYTES = 22L * 1024 * 1024; // SPEC 10.2: 16 posters + 4 banners + hero
    private static final int MAX_SRC_PX = 4096;
    private static final int MAX_DOWNLOAD = 12 * 1024 * 1024;
    private static final int TIMEOUT_MS = 6000;
    private static final String UA = "Lumen-Home/1.0 (Android TV)";
    private static final int BLUR_W = 192, BLUR_H = 108;

    public interface Callback {
        /** @param color dominant colour if it was computed now (else 0) */
        void onImage(Bitmap b, int color);
    }

    /** Handle of one request; cancel() drops the callback (the decode may still finish for the cache). */
    public static final class Request {
        final Callback cb;
        volatile boolean cancelled;

        Request(Callback cb) {
            this.cb = cb;
        }

        public void cancel() {
            cancelled = true;
        }
    }

    private static final class Entry {
        final Bitmap bmp;
        final int kind;
        final long bytes;

        Entry(Bitmap b, int kind) {
            this.bmp = b;
            this.kind = kind;
            this.bytes = b.getAllocationByteCount();
        }
    }

    private final class Job implements Runnable {
        final String key;
        final String uri;
        final int w, h, kind;
        final String owner;
        final String label;
        final boolean blur;
        final ArrayList<Request> reqs = new ArrayList<>(2);

        Job(String key, String uri, int w, int h, int kind, String owner, String label, boolean blur) {
            this.key = key;
            this.uri = uri;
            this.w = w;
            this.h = h;
            this.kind = kind;
            this.owner = owner;
            this.label = label;
            this.blur = blur;
        }

        @Override
        public void run() {
            Bitmap out = null;
            int[] color = {0};
            synchronized (ImageLoader.this) {
                boolean any = false;
                for (Request r : reqs) any |= !r.cancelled;
                if (!any) {
                    mJobs.remove(key);
                    return;
                }
            }
            try {
                out = blur ? produceBlur(this) : produce(this, color);
            } catch (Throwable t) {
                Log.w(App.TAG, "image " + shortUri(uri) + ": " + t);
            }
            final Bitmap result = out;
            final ArrayList<Request> rs;
            synchronized (ImageLoader.this) {
                mJobs.remove(key);
                rs = new ArrayList<>(reqs);
                if (result != null) {
                    if (blur) putBlur(key, result);
                    else putMem(key, result, kind);
                }
            }
            if (result == null) return;
            final int col = color[0];
            mApp.main().post(() -> {
                for (Request r : rs) {
                    if (r.cancelled) continue;
                    try {
                        r.cb.onImage(result, col);
                    } catch (Throwable t) {
                        Log.e(App.TAG, "image callback", t);
                    }
                }
            });
        }
    }

    private final App mApp;
    private final DiskCache mDisk;
    private final LinkedHashMap<String, Entry> mMem = new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<String, Bitmap> mBlur = new LinkedHashMap<>(16, 0.75f, true);
    private final HashMap<String, Job> mJobs = new HashMap<>();
    private final LinkedBlockingDeque<Job> mQueue = new LinkedBlockingDeque<>();
    private final HashMap<String, Integer> mColors = new HashMap<>();
    private long mMemBytes;
    private final java.util.concurrent.atomic.AtomicBoolean mTrimmed = new java.util.concurrent.atomic.AtomicBoolean();

    public ImageLoader(App app) {
        mApp = app;
        mDisk = new DiskCache(app.getCacheDir());
        for (int i = 0; i < 3; i++) {
            Thread t = new Thread(this::worker, "z9x-home-img" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    private void worker() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
        if (mTrimmed.compareAndSet(false, true)) mDisk.trim(); // once per process, off the lock
        while (true) {
            try {
                mQueue.takeLast().run();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.e(App.TAG, "image worker", t);
            }
        }
    }

    public static String key(String uri, int w, int h) {
        return uri + "@" + w + "x" + h;
    }

    public synchronized Bitmap peek(String uri, int w, int h) {
        Entry e = mMem.get(key(uri, w, h));
        return e != null ? e.bmp : null;
    }

    public synchronized Bitmap peekBlur(String uri) {
        return mBlur.get(uri);
    }

    public synchronized Integer knownColor(String uri) {
        return mColors.get(uri);
    }

    /**
     * Loads art at exactly w x h (center-crop). {@code ownerPkg} is the package the art belongs to
     * (content:// and android.resource:// must be its own). {@code label} is used for generated tiles.
     */
    public Request load(String uri, int w, int h, int kind, String ownerPkg, String label, Callback cb) {
        return enqueue(key(uri, w, h), uri, w, h, kind, ownerPkg, label, false, cb);
    }

    /** 192x108 blurred art for the ambient backdrop. */
    public Request loadBlur(String uri, String ownerPkg, Callback cb) {
        return enqueue("blur:" + uri, uri, BLUR_W, BLUR_H, KIND_POSTER, ownerPkg, null, true, cb);
    }

    private synchronized Request enqueue(String key, String uri, int w, int h, int kind, String owner, String label,
                                         boolean blur, Callback cb) {
        Request r = new Request(cb);
        if (uri == null || uri.isEmpty() || w <= 0 || h <= 0) return r;
        Job j = mJobs.get(key);
        if (j != null) {
            j.reqs.add(r);
            if (mQueue.remove(j)) mQueue.offerLast(j); // bump to the front of the LIFO
            return r;
        }
        j = new Job(key, uri, w, h, kind, owner, label, blur);
        j.reqs.add(r);
        mJobs.put(key, j);
        mQueue.offerLast(j);
        return r;
    }

    // ------------------------------------------------------------------ memory

    private void putMem(String key, Bitmap b, int kind) {
        Entry old = mMem.put(key, new Entry(b, kind));
        if (old != null) mMemBytes -= old.bytes;
        mMemBytes += b.getAllocationByteCount();
        Iterator<Map.Entry<String, Entry>> it = mMem.entrySet().iterator();
        while (mMemBytes > MEM_BYTES && it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getKey().equals(key)) continue;
            mMemBytes -= e.getValue().bytes;
            it.remove();
        }
    }

    private void putBlur(String key, Bitmap b) {
        mBlur.put(key.substring(5), b);
        while (mBlur.size() > 16) {
            Iterator<String> it = mBlur.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** Main thread. Drops posters and hero art (banners and icons stay), SPEC 10.2. */
    public synchronized void trimToBanners() {
        Iterator<Map.Entry<String, Entry>> it = mMem.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.kind == KIND_POSTER || e.kind == KIND_HERO) {
                mMemBytes -= e.bytes;
                it.remove();
            }
        }
        mBlur.clear();
    }

    public synchronized void trim(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            mMem.clear();
            mBlur.clear();
            mMemBytes = 0;
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            trimToBanners();
        }
    }

    // ------------------------------------------------------------------ producing (worker threads)

    private Bitmap produce(Job j, int[] color) throws Exception {
        String u = j.uri;
        if (u.startsWith("app:")) {
            File f = mDisk.get(j.key, false);
            if (f == null) {
                String comp = u.substring(4);
                int bar = comp.indexOf('|');
                if (bar > 0) comp = comp.substring(0, bar);
                Bitmap sw = AppArt.banner(mApp, comp, j.label, j.w, j.h, 0xFF1E232C);
                if (sw == null) return null;
                f = mDisk.put(j.key, sw, false);
                Bitmap hw = sw.copy(Bitmap.Config.HARDWARE, false);
                sw.recycle();
                return hw;
            }
            return decodeHw(f, j.w, j.h);
        }
        if (u.startsWith("icon:")) {
            File f = mDisk.get(j.key, true);
            if (f == null) {
                String pkg = u.substring(5);
                int bar = pkg.indexOf('|');
                if (bar > 0) pkg = pkg.substring(0, bar);
                Bitmap sw = AppArt.icon(mApp, pkg, j.w);
                if (sw == null) return null;
                mDisk.put(j.key, sw, true);
                Bitmap hw = sw.copy(Bitmap.Config.HARDWARE, false);
                sw.recycle();
                return hw;
            }
            return decodeHw(f, j.w, j.h);
        }
        File master = master(j, color);
        return master != null ? decodeHw(master, j.w, j.h) : null;
    }

    /** Disk master of a remote/provider URI; downloads and downscales it on a miss. */
    private File master(Job j, int[] color) throws Exception {
        String mkey = "m:" + j.uri;
        File f = mDisk.get(mkey, false);
        if (f != null) return f;
        Uri uri = Uri.parse(j.uri);
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        byte[] data;
        long t0 = SystemClock.uptimeMillis();
        if (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) {
            if (!online()) return null;
            data = download(j.uri);
        } else {
            if (!IntentGuard.imageAllowed(mApp, uri, j.owner)) return null;
            try (InputStream in = mApp.getContentResolver().openInputStream(uri)) {
                data = readAll(in);
            }
        }
        if (data == null) return null;
        Bitmap sw = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data)), (dec, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            if (w > MAX_SRC_PX || h > MAX_SRC_PX || w <= 0 || h <= 0) {
                throw new IllegalArgumentException("source too large " + w + "x" + h);
            }
            float s = w >= h ? Math.max(1280f / w, 720f / h) : Math.max(640f / w, 960f / h);
            if (s < 1f) dec.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        int c = DominantColor.of(sw);
        color[0] = c;
        if (c != 0) {
            synchronized (this) {
                mColors.put(j.uri, c);
            }
        }
        f = mDisk.put(mkey, sw, false);
        sw.recycle();
        Log.i(App.TAG, "img fetched " + shortUri(j.uri) + " bytes=" + data.length + " ms=" + (SystemClock.uptimeMillis() - t0));
        return f;
    }

    private static Bitmap decodeHw(File f, int tw, int th) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (dec, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float s = Math.max(tw / (float) w, th / (float) h);
            int sw = Math.max(tw, Math.round(w * s)), sh = Math.max(th, Math.round(h * s));
            dec.setTargetSize(sw, sh);
            int x = (sw - tw) / 2, y = (sh - th) / 2;
            dec.setCrop(new Rect(x, y, x + tw, y + th));
            dec.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE);
        });
    }

    private Bitmap produceBlur(Job j) throws Exception {
        File m = mDisk.get("m:" + j.uri, false);
        if (m == null) m = master(j, new int[1]);
        if (m == null) return null;
        Bitmap sw = ImageDecoder.decodeBitmap(ImageDecoder.createSource(m), (dec, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float s = Math.max(BLUR_W / (float) w, BLUR_H / (float) h);
            int tw = Math.max(BLUR_W, Math.round(w * s)), th = Math.max(BLUR_H, Math.round(h * s));
            dec.setTargetSize(tw, th);
            int x = (tw - BLUR_W) / 2, y = (th - BLUR_H) / 2;
            dec.setCrop(new Rect(x, y, x + BLUR_W, y + BLUR_H));
            dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            dec.setMutableRequired(true);
        });
        Blur.blur(sw, 6);
        return sw;
    }

    private boolean online() {
        try {
            ConnectivityManager cm = mApp.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    private static byte[] download(String url) throws Exception {
        // cleartext is blocked by network_security_config (only ip-api.com may use it): upgrade http art
        String cur = url.regionMatches(true, 0, "http://", 0, 7) ? "https://" + url.substring(7) : url;
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "image/webp,image/*;q=0.9");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    URL next = new URL(new URL(cur), loc);
                    String p = next.getProtocol();
                    if (!"http".equals(p) && !"https".equals(p)) return null;
                    cur = "http".equals(p) ? "https" + next.toString().substring(4) : next.toString();
                    continue;
                }
                if (code != 200) {
                    Log.w(App.TAG, "img http " + code + " " + shortUri(cur));
                    return null;
                }
                long len = c.getContentLengthLong();
                if (len > MAX_DOWNLOAD) return null;
                try (InputStream in = c.getInputStream()) {
                    return readAll(in);
                }
            } finally {
                c.disconnect();
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > MAX_DOWNLOAD) throw new IllegalStateException("image too large");
        }
        return bo.toByteArray();
    }

    static String shortUri(String u) {
        if (u == null) return "null";
        return u.length() > 90 ? u.substring(0, 90) + "…" : u;
    }

    /** Test/debug: forget the whole disk cache entry of a URI is not needed; kept small on purpose. */
    public Context context() {
        return mApp;
    }
}
