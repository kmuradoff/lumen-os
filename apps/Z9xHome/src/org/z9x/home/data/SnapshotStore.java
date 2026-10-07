package org.z9x.home.data;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.AtomicFile;
import android.util.Log;

import org.z9x.home.App;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/** files/snapshot.bin, written atomically on io (SPEC 5.8). */
public final class SnapshotStore {
    private final AtomicFile mFile;
    private final long mStamp;
    private static Context sCtx;

    public SnapshotStore(Context c) {
        mFile = new AtomicFile(new File(c.getFilesDir(), "snapshot.bin"));
        sCtx = c.getApplicationContext();
        long stamp = 0;
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            stamp = pi.lastUpdateTime ^ ((long) pi.versionCode << 40);
        } catch (Throwable ignored) {
        }
        mStamp = stamp;
    }

    /** The UI locale actually used by resources (per-app locales included). */
    public static String localeTag() {
        Context c = sCtx;
        if (c != null) return c.getResources().getConfiguration().getLocales().toLanguageTags();
        return Locale.getDefault().toLanguageTag();
    }

    public HomeModel load() {
        long t0 = android.os.SystemClock.uptimeMillis();
        if (!mFile.getBaseFile().exists()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(mFile.openRead(), 32 * 1024))) {
            HomeModel m = SnapshotCodec.read(in, mStamp, localeTag());
            if (m == null) Log.i(App.TAG, "snapshot stale (apk or locale changed)");
            else Log.i(App.TAG, "snapshot loaded rows=" + m.rows.size() + " ms=" + (android.os.SystemClock.uptimeMillis() - t0));
            return m;
        } catch (Throwable t) {
            Log.w(App.TAG, "snapshot invalid: " + t);
            mFile.delete();
            return null;
        }
    }

    public void save(HomeModel m) {
        FileOutputStream fo = null;
        try {
            fo = mFile.startWrite();
            DataOutputStream o = new DataOutputStream(new BufferedOutputStream(fo, 32 * 1024));
            SnapshotCodec.write(o, m, mStamp, localeTag());
            o.flush();
            mFile.finishWrite(fo);
        } catch (Throwable t) {
            Log.w(App.TAG, "snapshot save failed: " + t);
            if (fo != null) mFile.failWrite(fo);
        }
    }
}
