package org.z9x.home.img;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.data.IntentGuard;
import org.z9x.home.ui.UiScale;

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
 *    banners kept on UI_HIDDEN, everything else dropped; its budget follows the UI mode
 *    ({@link UiScale#imageMemBytes}: 30 MB at 1080p, 53 MB at 2K, 64 MB at 4K);
 *  - disk: one downscaled "master" per source URI (landscape art covers the screen, at most 2560x1440,
 *    {@link UiScale#masterW}; portrait 640x960), so the card and the full-screen hero share one
 *    download (1080p keeps 1.0.1's 1920x1080 "m2:" masters, 2K and 4K get their own); cards decode from
 *    it with ImageDecoder target size + crop at exactly their pixels; hero art (KIND_HERO, its own memory
 *    keys) is never enlarged by the decoder and is cropped from the top (1.0.1 follow-up: a centre crop
 *    cut the top of the picture), and {@link #probe} reads a master's real size (bounds only) so the
 *    hero can decide between full-bleed art and a framed card ({@link HeroArt});
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

    private static final int MAX_SRC_PX = 4096;
    private static final int MAX_DOWNLOAD = 12 * 1024 * 1024;
    private static final int TIMEOUT_MS = 6000;
    private static final String UA = "Lumen-Home/1.0 (Android TV)";
    private static final int BLUR_W = 192, BLUR_H = 108;
    /** 1.0.1's masters: at most this, key "m2:" (1080p keeps them). */
    private static final int MASTER_W = 1920, MASTER_H = 1080;
    /**
     * "m2:" masters cover the screen (1.0.1); the 1280x720 "m:" ones of 1.0 are left to the LRU trim. A
     * bigger UI mode (2K, 4K) gets "m2@2560x1440:" masters: a 1080p master could never fill its hero.
     */
    private static final String MASTER = "m2:", MASTER_AT = "m2@";

    public interface Callback {
        /** @param color dominant colour if it was computed now (else 0) */
        void onImage(Bitmap b, int color);
    }

    public interface SizeCallback {
        /** Real pixels of the art's master; 0 x 0 when it could not be fetched or read. */
        void onSize(int w, int h);
    }

    /** Handle of one request; cancel() drops the callback (the decode may still finish for the cache). */
    public static final class Request {
        final Callback cb;
        final SizeCallback scb;
        volatile boolean cancelled;

        Request(Callback cb) {
            this.cb = cb;
            this.scb = null;
        }

        Request(SizeCallback scb) {
            this.cb = null;
            this.scb = scb;
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
        final boolean size;
        final ArrayList<Request> reqs = new ArrayList<>(2);

        Job(String key, String uri, int w, int h, int kind, String owner, String label, boolean blur, boolean size) {
            this.key = key;
            this.uri = uri;
            this.w = w;
            this.h = h;
            this.kind = kind;
            this.owner = owner;
            this.label = label;
            this.blur = blur;
            this.size = size;
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
            if (size) {
                runSize();
                return;
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

        /** Size probe: always answers (0 x 0 on failure), so a caller waiting for several can decide. */
        private void runSize() {
            int[] wh = {0, 0};
            try {
                wh = probeSize(this);
            } catch (Throwable t) {
                Log.w(App.TAG, "image size " + shortUri(uri) + ": " + t);
            }
            final int sw = wh[0], sh = wh[1];
            final ArrayList<Request> rs;
            synchronized (ImageLoader.this) {
                mJobs.remove(key);
                rs = new ArrayList<>(reqs);
                if (sw > 0 && sh > 0) {
                    mSizes.put(masterKey(uri), new int[]{sw, sh});
                    while (mSizes.size() > 64) {
                        Iterator<String> it = mSizes.keySet().iterator();
                        it.next();
                        it.remove();
                    }
                }
            }
            mApp.main().post(() -> {
                for (Request r : rs) {
                    if (r.cancelled) continue;
                    try {
                        r.scb.onSize(sw, sh);
                    } catch (Throwable t) {
                        Log.e(App.TAG, "image size callback", t);
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
    private final LinkedHashMap<String, int[]> mSizes = new LinkedHashMap<>(16, 0.75f, true);
    private long mMemBytes;
    private final java.util.concurrent.atomic.AtomicBoolean mTrimmed = new java.util.concurrent.atomic.AtomicBoolean();

    public ImageLoader(App app) {
        mApp = app;
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        mDisk = new DiskCache(app.getCacheDir(), UiScale.diskBytes(dm.widthPixels, dm.heightPixels));
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

    /** Hero art (top crop, never enlarged) already in memory at exactly this box, or null. */
    public synchronized Bitmap peekHero(String uri, int w, int h) {
        Entry e = mMem.get(heroKey(uri, w, h));
        return e != null ? e.bmp : null;
    }

    /** Real pixels {w, h} of the art's master if a probe already read them, else null. */
    public synchronized int[] peekSize(String uri) {
        int[] s = mSizes.get(masterKey(uri));
        return s != null ? s.clone() : null;
    }

    /** Masters of this UI mode: {w, h} a landscape master covers at most. */
    private int[] masterBox() {
        DisplayMetrics dm = mApp.getResources().getDisplayMetrics();
        return new int[]{UiScale.masterW(dm.widthPixels, dm.heightPixels), UiScale.masterH(dm.widthPixels, dm.heightPixels)};
    }

    /** Disk key of the master of {@code uri} for this UI mode (also the key of its probed size). */
    private String masterKey(String uri) {
        int[] m = masterBox();
        return (m[0] > MASTER_W || m[1] > MASTER_H ? MASTER_AT + m[0] + "x" + m[1] + ":" : MASTER) + uri;
    }

    private static String heroKey(String uri, int w, int h) {
        return "hero:" + key(uri, w, h);
    }

    public synchronized Integer knownColor(String uri) {
        return mColors.get(uri);
    }

    /**
     * Loads art at exactly w x h (center-crop). {@code ownerPkg} is the package the art belongs to
     * (content:// and android.resource:// must be its own). {@code label} is used for generated tiles.
     */
    public Request load(String uri, int w, int h, int kind, String ownerPkg, String label, Callback cb) {
        String k = kind == KIND_HERO ? heroKey(uri, w, h) : key(uri, w, h);
        return enqueue(k, uri, w, h, kind, ownerPkg, label, false, false, new Request(cb));
    }

    /**
     * Hero art at exactly w x h when the source has those pixels, else the box's shape at the source's own
     * size; cropped from the top (centred horizontally). Kept apart from the centre-cropped card art.
     */
    public Request loadHero(String uri, int w, int h, String ownerPkg, Callback cb) {
        return load(uri, w, h, KIND_HERO, ownerPkg, null, cb);
    }

    /** 192x108 blurred art for the ambient backdrop. */
    public Request loadBlur(String uri, String ownerPkg, Callback cb) {
        return enqueue("blur:" + uri, uri, BLUR_W, BLUR_H, KIND_POSTER, ownerPkg, null, true, false, new Request(cb));
    }

    /**
     * Real pixels of the art's disk master (downloaded first if needed; bounds only, no decode). The
     * callback always comes, with 0 x 0 when the art cannot be had.
     */
    public Request probe(String uri, String ownerPkg, SizeCallback cb) {
        Request r = new Request(cb);
        if (uri == null || uri.isEmpty()) {
            mApp.main().post(() -> {
                if (!r.cancelled) cb.onSize(0, 0);
            });
            return r;
        }
        return enqueue("size:" + uri, uri, 1, 1, KIND_HERO, ownerPkg, null, false, true, r);
    }

    private synchronized Request enqueue(String key, String uri, int w, int h, int kind, String owner, String label,
                                         boolean blur, boolean size, Request r) {
        if (uri == null || uri.isEmpty() || w <= 0 || h <= 0) return r;
        Job j = mJobs.get(key);
        if (j != null) {
            j.reqs.add(r);
            if (mQueue.remove(j)) mQueue.offerLast(j); // bump to the front of the LIFO
            return r;
        }
        j = new Job(key, uri, w, h, kind, owner, label, blur, size);
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
        // SPEC 10.2: 16 posters + 4 banners + the hero at 1080p (1.0.1: a 1920x1080 hero is 8.3 MB, was 3.7
        // at 1280x720) in 30 MB; at 2K (a 2560x1440 hero is 14.7 MB) and 4K (cards only, at most 1280x720:
        // 3.7 MB) the budget grows with the pixels
        DisplayMetrics dm = mApp.getResources().getDisplayMetrics();
        long budget = UiScale.imageMemBytes(dm.widthPixels, dm.heightPixels);
        Iterator<Map.Entry<String, Entry>> it = mMem.entrySet().iterator();
        while (mMemBytes > budget && it.hasNext()) {
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
            // lossless (1.0.1): a JPEG copy halves the colour resolution (4:2:0) and rings around the
            // logos and the generated tile's name, and every later start shows that copy
            File f = mDisk.get(j.key, true);
            if (f == null) {
                String comp = u.substring(4);
                int bar = comp.indexOf('|');
                if (bar > 0) comp = comp.substring(0, bar);
                Bitmap sw = AppArt.banner(mApp, comp, j.label, j.w, j.h, 0xFF1E232C);
                if (sw == null) return null;
                f = mDisk.put(j.key, sw, true);
                Bitmap hw = sw.copy(Bitmap.Config.HARDWARE, false);
                sw.recycle();
                return hw;
            }
            return decodeHw(f, j.w, j.h, true);
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
            return decodeHw(f, j.w, j.h, true);
        }
        File master = master(j, color);
        boolean hero = j.kind == KIND_HERO;
        return master != null ? decodeHw(master, j.w, j.h, !hero, hero) : null;
    }

    /** The master's real pixels: the source's, or the screen-covering copy of a larger source. */
    private int[] probeSize(Job j) throws Exception {
        if (j.uri.startsWith("app:") || j.uri.startsWith("icon:")) return new int[]{0, 0}; // generated art
        File m = mDisk.get(masterKey(j.uri), false);
        if (m == null) m = master(j, new int[1]);
        if (m == null) return new int[]{0, 0};
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(m.getPath(), o);
        return new int[]{Math.max(0, o.outWidth), Math.max(0, o.outHeight)};
    }

    /** Disk master of a remote/provider URI for this UI mode; downloads and downscales it on a miss. */
    private File master(Job j, int[] color) throws Exception {
        String mkey = masterKey(j.uri);
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
        int[] box = masterBox();
        final float mw = box[0], mh = box[1];
        Bitmap sw = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data)), (dec, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            if (w > MAX_SRC_PX || h > MAX_SRC_PX || w <= 0 || h <= 0) {
                throw new IllegalArgumentException("source too large " + w + "x" + h);
            }
            float s = w >= h ? Math.max(mw / w, mh / h) : Math.max(640f / w, 960f / h);
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

    /**
     * Crop to tw x th (centred; {@code top}: from the top edge, centred horizontally). {@code enlarge}
     * false: a source smaller than that box is cropped to the box's aspect at its own size instead (an
     * enlarged copy would look the same, at more memory).
     */
    private static Bitmap decodeHw(File f, int tw, int th, boolean enlarge) throws Exception {
        return decodeHw(f, tw, th, enlarge, false);
    }

    private static Bitmap decodeHw(File f, int tw, int th, boolean enlarge, boolean top) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (dec, info, src) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float s = Math.max(tw / (float) w, th / (float) h);
            int ow = tw, oh = th;
            if (!enlarge && s > 1f) {
                ow = Math.max(1, Math.min(w, Math.round(tw / s)));
                oh = Math.max(1, Math.min(h, Math.round(th / s)));
                s = 1f;
            }
            int sw = Math.max(ow, Math.round(w * s)), sh = Math.max(oh, Math.round(h * s));
            dec.setTargetSize(sw, sh);
            int x = (sw - ow) / 2, y = top ? 0 : (sh - oh) / 2;
            dec.setCrop(new Rect(x, y, x + ow, y + oh));
            dec.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE);
        });
    }

    /** 192x108 at every UI mode: blurred art is soft, the GPU enlarges it (UiScale: soft layers). */
    private Bitmap produceBlur(Job j) throws Exception {
        File m = mDisk.get(masterKey(j.uri), false);
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
