package org.z9x.projector.recents;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.UserHandle;
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
 * opens: we capture it once, off the main thread, at {@value #THUMB_W}x{@value #THUMB_H} RGB_565. DRM or
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
        Drawable d = null;
        try { d = pm.getActivityBanner(c.component); } catch (Throwable ignored) { }
        if (d == null) {
            try { d = pm.getApplicationBanner(c.pkg); } catch (Throwable ignored) { }
        }
        if (d == null) {
            try {
                Intent li = pm.getLeanbackLaunchIntentForPackage(c.pkg);
                if (li != null && li.getComponent() != null) d = pm.getActivityBanner(li.getComponent());
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
            // a hardware bitmap is read back once by createScaledBitmap; the result is drawn into RGB_565
            scaled = Bitmap.createScaledBitmap(hw, THUMB_W, THUMB_H, true);
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
            Log.i(TAG, "snapshot task " + taskId + " " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
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
