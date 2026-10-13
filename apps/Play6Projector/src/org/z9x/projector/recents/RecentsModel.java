package org.z9x.projector.recents;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.window.TaskSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recent apps (speed spec 6.2): the task list, its filter, the app banners and the thumbnail of the app
 * just left.
 *
 * <b>Tasks</b>: ActivityTaskManager.getRecentTasks (REAL_GET_TASKS). TV has config_hasRecents=false, so
 * RecentTasks keeps every task untrimmed; we show activityType STANDARD tasks whose package has a
 * LEANBACK_LAUNCHER or LAUNCHER entry (no setup flows, AirPlay mirror, our own panels), one card per
 * app (its newest task; Close removes all of its tasks), newest first, at most {@value #MAX_CARDS}.
 *
 * <b>Thumbnails</b>: the system never takes task snapshots on TV (AbsAppSnapshotController
 * .shouldDisableSnapshots on TV), but takeTaskSnapshot(task, false) (READ_FRAME_BUFFER) snapshots a
 * visible task directly. RecentsActivity is translucent, so the app just left is still visible when it
 * opens: we capture it once, off the main thread, at {@value #THUMB_W}x{@value #THUMB_H} RGB_565 (above 1080p
 * scaled on the GPU: the snapshot is the full UI frame, 3840x2160 = 33 MB at the Lumen OS 1.0.1 4K default). DRM or
 * tunnelled video (secure / sideband layers) captures black: 64 samples near black -> no thumbnail.
 * Other cards reuse the bitmap captured the last time Recents opened over them (static LruCache, at most
 * {@value #CACHE_BYTES} bytes, the only thing that stays in the persistent process: PLAN C21), else the
 * app banner, else its icon on a tinted card.
 */
final class RecentsModel {
    private static final String TAG = "Z9xRecents";
    static final int MAX_CARDS = 12;
    private static final int QUERY_MAX = 40;
    static final int THUMB_W = 480, THUMB_H = 270;
    static final int CACHE_BYTES = 2 * 1024 * 1024;
    /** Snapshots larger than this (2K / 4K UI) are scaled on the GPU; 1080p keeps the full copy of 1.0.0. */
    private static final int GPU_SCALE_ABOVE_W = 1920, GPU_SCALE_ABOVE_H = 1080;
    /**
     * Banners are decoded for at most this density (Lumen OS 1.0.1, 4K UI at 640 dpi): a 320 dp banner is
     * then 960 px wide, still more than the 4K card (384 design px = 768 px, 830 focused), at about half
     * the memory of the 640 dpi decode (an xhdpi banner becomes 1280 x 720 ARGB = 3.7 MB there, up to
     * {@value #MAX_CARDS} of them while Recents is open). At 1080p (320 dpi) nothing changes.
     */
    private static final int BANNER_MAX_DPI = DisplayMetrics.DENSITY_XXHIGH;

    /** One card: an app with its tasks (newest first). */
    static final class Card {
        final String pkg;
        final List<Integer> taskIds = new ArrayList<>();
        final ComponentName component;
        CharSequence label = "";
        long lastActive;
        boolean visible;
        Drawable banner;
        Drawable icon;
        Bitmap thumb;

        Card(String pkg, ComponentName component) {
            this.pkg = pkg;
            this.component = component;
        }

        int taskId() {
            return taskIds.isEmpty() ? -1 : taskIds.get(0);
        }
    }

    private static final LruCache<Integer, Bitmap> THUMBS = new LruCache<Integer, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(Integer k, Bitmap b) {
            return b == null ? 0 : b.getByteCount();
        }
    };

    private RecentsModel() {}

    /** Main thread is fine: one binder call plus cheap PackageManager queries (labels only). */
    static List<Card> load(Context ctx) {
        List<Card> out = new ArrayList<>();
        List<ActivityManager.RecentTaskInfo> tasks;
        try {
            tasks = ActivityTaskManager.getInstance().getRecentTasks(QUERY_MAX,
                    ActivityManager.RECENT_IGNORE_UNAVAILABLE, UserHandle.myUserId());
        } catch (Throwable t) {
            Log.w(TAG, "getRecentTasks: " + t);
            return out;
        }
        if (tasks == null) return out;
        PackageManager pm = ctx.getPackageManager();
        String self = ctx.getPackageName();
        Map<String, Card> byPkg = new LinkedHashMap<>();
        Map<String, Boolean> launchable = new LinkedHashMap<>();
        for (ActivityManager.RecentTaskInfo ti : tasks) {
            try {
                if (ti.getActivityType() != WindowConfiguration.ACTIVITY_TYPE_STANDARD) continue;
                ComponentName cn = component(ti);
                if (cn == null) continue;
                String pkg = cn.getPackageName();
                if (pkg.equals(self)) continue;
                Card c = byPkg.get(pkg);
                if (c != null) {
                    c.taskIds.add(ti.taskId);
                    c.visible |= ti.isVisible;
                    continue;
                }
                Boolean ok = launchable.get(pkg);
                if (ok == null) {
                    ok = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
                    launchable.put(pkg, ok);
                }
                if (!ok) continue;
                if (byPkg.size() >= MAX_CARDS) continue;
                c = new Card(pkg, cn);
                c.taskIds.add(ti.taskId);
                c.lastActive = ti.lastActiveTime;
                c.visible = ti.isVisible;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    c.label = pm.getApplicationLabel(ai);
                } catch (Throwable t) {
                    c.label = pkg;
                }
                c.thumb = THUMBS.get(ti.taskId);
                byPkg.put(pkg, c);
            } catch (Throwable t) {
                Log.w(TAG, "task " + ti.taskId + ": " + t);
            }
        }
        out.addAll(byPkg.values());
        return out;
    }

    private static ComponentName component(ActivityManager.RecentTaskInfo ti) {
        if (ti.baseIntent != null && ti.baseIntent.getComponent() != null) return ti.baseIntent.getComponent();
        if (ti.baseActivity != null) return ti.baseActivity;
        if (ti.realActivity != null) return ti.realActivity;
        return ti.topActivity;
    }

    /** Worker thread: banner (activity, then app), else icon. Drawables stay with the views only. */
    static void loadArt(Context ctx, Card c) {
        PackageManager pm = ctx.getPackageManager();
        boolean cap = ctx.getResources().getDisplayMetrics().densityDpi > BANNER_MAX_DPI;
        Drawable d = activityBanner(pm, c.component, cap);
        if (d == null) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(c.pkg, 0);
                d = cap ? capped(pm, ai, ai.banner) : null;
                if (d == null) d = pm.getApplicationBanner(ai);
            } catch (Throwable ignored) { }
        }
        if (d == null) {
            try {
                Intent li = pm.getLeanbackLaunchIntentForPackage(c.pkg);
                if (li != null && li.getComponent() != null) d = activityBanner(pm, li.getComponent(), cap);
            } catch (Throwable ignored) { }
        }
        c.banner = d;
        if (d == null) {
            try { c.icon = pm.getApplicationIcon(c.pkg); } catch (Throwable ignored) { }
        }
    }

    /**
     * Worker thread: snapshot of a visible task (the app just left), scaled to THUMB_W x THUMB_H RGB_565.
     * Null when the system gives none or it is (near) black (DRM / tunnelled video).
     */
    static Bitmap capture(int taskId) {
        long t0 = android.os.SystemClock.uptimeMillis();
        TaskSnapshot snap = null;
        Bitmap hw = null, scaled = null;
        try {
            snap = ActivityTaskManager.getService().takeTaskSnapshot(taskId, false);
            if (snap == null) {
                Log.i(TAG, "snapshot task " + taskId + ": none");
                return null;
            }
            HardwareBuffer hb = snap.getHardwareBuffer();
            if (hb == null) return null;
            ColorSpace cs = snap.getColorSpace();
            hw = Bitmap.wrapHardwareBuffer(hb, cs != null ? cs : ColorSpace.get(ColorSpace.Named.SRGB));
            if (hw == null) return null;
            // above 1080p scaled on the GPU, only the thumbnail is read back; createScaledBitmap (1080p as
            // before, and the fallback) first copies the whole hardware bitmap into a software one (33 MB at
            // the 4K UI, 8.3 MB at 1080p)
            boolean big = (long) hw.getWidth() * hw.getHeight() > (long) GPU_SCALE_ABOVE_W * GPU_SCALE_ABOVE_H;
            scaled = big ? scaleOnGpu(hw, taskId) : null;
            final boolean gpu = scaled != null;
            if (!gpu) scaled = Bitmap.createScaledBitmap(hw, THUMB_W, THUMB_H, true);
            Bitmap out = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.RGB_565);
            Bitmap src = scaled.getConfig() == Bitmap.Config.HARDWARE ? scaled.copy(Bitmap.Config.ARGB_8888, false) : scaled;
            new Canvas(out).drawBitmap(src, null, new Rect(0, 0, THUMB_W, THUMB_H), new Paint(Paint.FILTER_BITMAP_FLAG));
            if (src != scaled) src.recycle();
            if (nearBlack(out)) {
                Log.i(TAG, "snapshot task " + taskId + ": black (protected video?), banner instead");
                out.recycle();
                THUMBS.remove(taskId);
                return null;
            }
            THUMBS.put(taskId, out);
            Log.i(TAG, "snapshot task " + taskId + " " + hw.getWidth() + "x" + hw.getHeight() + " -> " + THUMB_W + "x"
                    + THUMB_H + (gpu ? " on the GPU" : " by a full copy") + " in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "snapshot task " + taskId + ": " + t);
            return null;
        } finally {
            if (scaled != null && scaled != hw) scaled.recycle();
            if (hw != null) hw.recycle();
            try { if (snap != null && snap.getHardwareBuffer() != null) snap.getHardwareBuffer().close(); } catch (Throwable ignored) { }
        }
    }

    /** The banner of {@code cn} (its own, else its application's), at most {@link #BANNER_MAX_DPI} when {@code cap}; null if none. */
    private static Drawable activityBanner(PackageManager pm, ComponentName cn, boolean cap) {
        if (cn == null) return null;
        Drawable d = null;
        if (cap) {
            try {
                ActivityInfo ai = pm.getActivityInfo(cn, 0);
                d = capped(pm, ai.applicationInfo, ai.getBannerResource());
            } catch (Throwable ignored) { }
        }
        if (d == null) {
            try { d = pm.getActivityBanner(cn); } catch (Throwable ignored) { }
        }
        return d;
    }

    /** Resource {@code res} of the app decoded for {@link #BANNER_MAX_DPI} (bitmaps scaled to it), or null. */
    private static Drawable capped(PackageManager pm, ApplicationInfo app, int res) {
        if (app == null || res == 0) return null;
        try {
            return pm.getResourcesForApplication(app).getDrawableForDensity(res, BANNER_MAX_DPI, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Worker thread: the HARDWARE snapshot bitmap scaled to THUMB_W x THUMB_H without a full-size read-back.
     * A Picture that holds a hardware bitmap is rendered by Bitmap.createBitmap(Picture, ...) through a
     * RenderNode on the RenderThread into a THUMB_W x THUMB_H hardware bitmap, and only that is copied to
     * ARGB_8888 (0.5 MB). Null on any failure (the caller falls back to createScaledBitmap).
     */
    private static Bitmap scaleOnGpu(Bitmap hw, int taskId) {
        try {
            Picture pic = new Picture();
            Canvas c = pic.beginRecording(THUMB_W, THUMB_H);
            c.drawBitmap(hw, null, new Rect(0, 0, THUMB_W, THUMB_H), new Paint(Paint.FILTER_BITMAP_FLAG));
            pic.endRecording();
            Bitmap b = Bitmap.createBitmap(pic, THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888);
            if (b != null && b.getWidth() == THUMB_W && b.getHeight() == THUMB_H) return b;
            Log.w(TAG, "snapshot task " + taskId + ": GPU scale gave " + (b == null ? "null" : b.getWidth() + "x" + b.getHeight())
                    + ", full-size copy instead");
            if (b != null) b.recycle();
        } catch (Throwable t) {
            Log.w(TAG, "snapshot task " + taskId + ": GPU scale failed (" + t + "), full-size copy instead");
        }
        return null;
    }

    /** 8 x 8 samples; every one darker than luma 16 = black. */
    private static boolean nearBlack(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int c = b.getPixel((2 * x + 1) * w / 16, (2 * y + 1) * h / 16);
                int luma = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
                if (luma >= 16) return false;
            }
        }
        return true;
    }

    /** A closed task's thumbnail goes too. */
    static void forget(List<Integer> taskIds) {
        for (Integer id : taskIds) THUMBS.remove(id);
    }

    /** onTrimMemory(RUNNING_LOW / BACKGROUND+): the cache is only a nicety. */
    static void trim() {
        THUMBS.evictAll();
    }
}
