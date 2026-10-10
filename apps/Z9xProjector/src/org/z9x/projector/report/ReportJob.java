package org.z9x.projector.report;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one report the user confirmed, process-wide (the app is persistent), so ReportActivity can be left
 * and reopened while it runs and still shows the result. A run starts ONLY from ReportActivity's
 * "Send report" / "Try again" buttons; nothing retries or sends on its own, and a canceled run can never
 * upload (its own cancel flag is checked between steps, per 1024 log lines and per 16 KB sent).
 * State is read and changed on the main thread; each run collects and uploads on its own worker thread
 * ("z9x-report") in its own directory (cacheDir/report/run&lt;n&gt;), which ends with the run.
 */
final class ReportJob {
    private static final String TAG = ReportConfig.TAG;

    enum State { IDLE, COLLECTING, UPLOADING, DONE, FAILED }

    interface Listener {
        void onReportChanged();
    }

    /** One press of "Send report" / "Try again". */
    private static final class Run {
        final int gen;
        final File dir;
        volatile boolean canceled;
        volatile Process process;
        volatile ReportUploader uploader;
        SafeHandler worker;

        Run(int gen, File dir) {
            this.gen = gen;
            this.dir = dir;
        }
    }

    private static ReportJob sJob;

    private final List<Listener> listeners = new ArrayList<>();
    private State state = State.IDLE;
    private int step, steps = ReportCollector.STEPS;
    private long sent, total;
    private String code;
    private ReportUploader.Failure failure;
    /** The zip of a failed upload: "Try again" sends it again instead of collecting anew. */
    private File zip;
    private Run current;
    private int generation;

    /** Main thread. */
    static ReportJob get() {
        if (sJob == null) sJob = new ReportJob();
        return sJob;
    }

    State state() { return state; }
    int step() { return step; }
    int steps() { return steps; }
    long sent() { return sent; }
    long total() { return total; }
    String code() { return code; }
    ReportUploader.Failure failure() { return failure; }
    boolean busy() { return state == State.COLLECTING || state == State.UPLOADING; }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    /** "Send report" / "Try again": the user's explicit go. Main thread. */
    void start(Context ctx) {
        if (busy()) return;
        final Context app = ctx.getApplicationContext();
        final String url = ReportConfig.url();
        if (url == null) {
            fail(ReportUploader.Failure.REJECTED);
            return;
        }
        final File retry = zip != null && zip.isFile() ? zip : null;
        final int gen = ++generation;
        final Run r = new Run(gen, retry != null ? retry.getParentFile() : new File(root(app), "run" + gen));
        current = r;
        failure = null;
        code = null;
        sent = total = 0;
        step = 0;
        state = retry != null ? State.UPLOADING : State.COLLECTING;
        notifyListeners();
        r.worker = SafeHandler.newThread("z9x-report");
        r.worker.post(() -> run(app, url, r, retry));
    }

    /** Back / Cancel while busy. Main thread. The run stops at its next check and deletes its files. */
    void cancel() {
        if (!busy() || current == null) return;
        Run r = current;
        current = null;
        r.canceled = true;
        Process p = r.process;
        if (p != null) p.destroy();
        ReportUploader u = r.uploader;
        if (u != null) u.abort();
        zip = null;
        state = State.IDLE;
        notifyListeners();
    }

    /** The user left a finished screen (Close / Back): next time starts at the consent screen. */
    void reset() {
        if (busy()) return;
        File z = zip;
        zip = null;
        if (z != null) deleteDir(z.getParentFile());   // a failed run's zip kept for "Try again"
        state = State.IDLE;
        code = null;
        failure = null;
        notifyListeners();
    }

    // ------------------------------------------------------------------ worker thread
    private static File root(Context app) {
        return new File(app.getCacheDir(), "report");
    }

    private void run(Context app, String url, Run r, File retry) {
        File z = retry;
        try {
            if (z == null) {
                // leftovers of earlier runs (a canceled run still stopping only loses its own files)
                File[] old = root(app).listFiles();
                if (old != null) for (File d : old) if (!d.equals(r.dir)) deleteDir(d);
                deleteDir(r.dir);
                if (!r.dir.mkdirs()) throw new IOException("no dir " + r.dir);
                ReportCollector col = new ReportCollector(app, r.dir, new ReportCollector.Progress() {
                    @Override public void onStep(int s, int n) { post(r, () -> { step = s; steps = n; notifyListeners(); }); }
                    @Override public boolean isCanceled() { return r.canceled; }
                    @Override public void setProcess(Process p) { r.process = p; }
                });
                z = col.collect();
            }
            final File done = z;
            post(r, () -> { zip = done; state = State.UPLOADING; total = done.length(); notifyListeners(); });
            if (r.canceled) throw new CanceledException();
            if (!online(app)) throw new ReportUploader.UploadException(ReportUploader.Failure.NO_NETWORK, "offline");
            ReportUploader up = new ReportUploader();
            r.uploader = up;
            if (r.canceled) throw new CanceledException();     // cancel() may have missed the uploader
            final long[] lastPost = {0};
            String c = up.upload(url, z, ReportStore.installId(app), SystemProperties.get("ro.lumen.version", ""),
                    (s, t) -> {
                        long now = SystemClock.uptimeMillis();
                        if (s == t || now - lastPost[0] > 250) {
                            lastPost[0] = now;
                            post(r, () -> { sent = s; total = t; notifyListeners(); });
                        }
                        return !r.canceled;
                    });
            ReportStore.setLast(app, c, System.currentTimeMillis());
            Log.i(TAG, "report sent: " + c + " (" + z.length() + " bytes)");
            deleteDir(r.dir);
            post(r, () -> {
                code = c;
                zip = null;
                state = State.DONE;
                current = null;
                notifyListeners();
            });
        } catch (Throwable t) {
            if (r.canceled) {
                Log.i(TAG, "report canceled");
                deleteDir(r.dir);
                return;
            }
            final ReportUploader.Failure f = t instanceof ReportUploader.UploadException
                    ? ((ReportUploader.UploadException) t).failure
                    : (z == null ? ReportUploader.Failure.COLLECT : ReportUploader.Failure.NETWORK);
            Log.w(TAG, "report failed (" + f + "): " + t);
            final File keep = f == ReportUploader.Failure.COLLECT ? null : z;
            if (keep == null) {
                deleteDir(r.dir);
            } else {
                File[] fs = r.dir.listFiles();                 // section files are no longer needed
                if (fs != null) for (File x : fs) if (!x.equals(keep)) x.delete();
            }
            post(r, () -> {
                zip = keep;
                current = null;
                fail(f);
            });
        } finally {
            r.uploader = null;
            r.process = null;
            r.worker.getLooper().quitSafely();               // the thread ends after this task
        }
    }

    private static boolean online(Context app) {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            Network n = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;                                 // let the upload itself find out
        }
    }

    private static void deleteDir(File d) {
        if (d == null) return;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) if (!f.delete()) Log.w(TAG, "cannot delete " + f);
        d.delete();
    }

    // ------------------------------------------------------------------ main thread helpers
    /** Runs {@code u} on the main thread if {@code r} is still the current run (not canceled, not replaced). */
    private void post(Run r, Runnable u) {
        Ui.main().post(() -> {
            if (r == current) u.run();
        });
    }

    private void fail(ReportUploader.Failure f) {
        failure = f;
        state = State.FAILED;
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onReportChanged();
            } catch (Throwable t) {
                Log.w(TAG, "listener: " + t);
            }
        }
    }
}
