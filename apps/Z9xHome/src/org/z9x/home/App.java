package org.z9x.home;

import android.app.Application;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.z9x.home.data.HomeRepository;
import org.z9x.home.img.ImageLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Lumen Home process (org.z9x.home, priv-app, NOT persistent). Application.onCreate does nothing
 * expensive (cold start budget, SPEC 10.1): the io thread, the image loader and the repository are
 * created lazily on first use.
 *
 * Crash safety (SPEC 12): every uncaught exception is appended to files/crash before the default
 * handler kills us; 3 crashes within 60 s switch HomeActivity to the plain safe-mode apps list, so a
 * crash loop in HOME can never brick the projector UI.
 */
public final class App extends Application {
    public static final String TAG = "Z9xHome";
    private static final long CRASH_WINDOW_MS = 60_000;
    private static final int CRASH_LIMIT = 3;

    private static App sApp;
    private Handler mMain;
    private HandlerThread mIoThread;
    private Handler mIo;
    private Prefs mPrefs;
    private ImageLoader mImages;
    private HomeRepository mRepo;

    public static App get() {
        return sApp;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        mMain = new Handler(Looper.getMainLooper());
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                recordCrash(e);
            } catch (Throwable ignored) {
            }
            if (def != null) def.uncaughtException(t, e);
            else Process.killProcess(Process.myPid());
        });
    }

    public Handler main() {
        return mMain;
    }

    public synchronized Handler io() {
        if (mIo == null) {
            mIoThread = new HandlerThread("z9x-home-io", Process.THREAD_PRIORITY_BACKGROUND
                    + Process.THREAD_PRIORITY_MORE_FAVORABLE);
            mIoThread.start();
            mIo = new Handler(mIoThread.getLooper());
        }
        return mIo;
    }

    public synchronized Prefs prefs() {
        if (mPrefs == null) mPrefs = new Prefs(this);
        return mPrefs;
    }

    public synchronized ImageLoader images() {
        if (mImages == null) mImages = new ImageLoader(this);
        return mImages;
    }

    public synchronized HomeRepository repo() {
        if (mRepo == null) mRepo = new HomeRepository(this);
        return mRepo;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        ImageLoader il;
        synchronized (this) {
            il = mImages;
        }
        if (il != null) il.trim(level);
    }

    // ------------------------------------------------------------------ crash counter

    private File crashFile() {
        return new File(getFilesDir(), "crash");
    }

    private void recordCrash(Throwable e) throws Exception {
        Log.e(TAG, "crash recorded", e);
        try (FileOutputStream out = new FileOutputStream(crashFile(), true)) {
            out.write((System.currentTimeMillis() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** True if HOME crashed CRASH_LIMIT times within the last minute. */
    public boolean isCrashLooping() {
        File f = crashFile();
        if (!f.exists()) return false;
        try {
            long now = System.currentTimeMillis();
            int n = 0;
            for (String s : new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).split("\n")) {
                if (s.isEmpty()) continue;
                long t = Long.parseLong(s.trim());
                if (now - t >= 0 && now - t < CRASH_WINDOW_MS) n++;
            }
            return n >= CRASH_LIMIT;
        } catch (Throwable t) {
            return false;
        }
    }

    public void clearCrashes() {
        //noinspection ResultOfMethodCallIgnored
        crashFile().delete();
    }
}
