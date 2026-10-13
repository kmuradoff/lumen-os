// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.text.format.Formatter;
import android.util.Log;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

/**
 * Foreground service (dataSync) for the two long jobs: download (resumable, sha256-checked) and
 * install. Background priority, one thread, 1 MB buffers. Stops itself when idle, so the app keeps 0 MB
 * resident. Play 6 fork (tools/play6/forks.tsv): the package is a whole recovery OTA zip on /cache; install
 * = signature check, BCB, restart into the recovery (Engine), the result is read after the boot.
 */
public final class UpdateService extends Service implements Engine.Listener {
    public static final String ACTION_DOWNLOAD = "org.z9x.updater.DOWNLOAD";
    public static final String ACTION_INSTALL = "org.z9x.updater.INSTALL";
    public static final String ACTION_PAUSE = "org.z9x.updater.PAUSE";
    public static final String ACTION_CANCEL = "org.z9x.updater.CANCEL";
    public static final String ACTION_REVERT = "org.z9x.updater.REVERT";
    public static final String ACTION_WATCH = "org.z9x.updater.WATCH";
    /** Test T5: preflight + payload metadata signature + snapshot space, never applies. */
    public static final String ACTION_VERIFY_ONLY = "org.z9x.updater.VERIFY_ONLY";
    /** extra: continue with the install after the download (the user pressed "Download and install") */
    public static final String EXTRA_THEN_INSTALL = "then_install";

    static final int NOTIF_PROGRESS = 1, NOTIF_RESULT = 2;

    private volatile boolean stop;
    private volatile Thread worker;
    private PowerManager.WakeLock wake;
    private long lastNotif;

    public static void start(Context c, String action, boolean thenInstall) {
        c.startForegroundService(new Intent(c, UpdateService.class).setAction(action)
                .putExtra(EXTRA_THEN_INSTALL, thenInstall));
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        channel(this);
        Engine.get().addListener(this);
    }

    @Override
    public void onDestroy() {
        Engine.get().removeListener(this);
        releaseWake();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_WATCH;
        startForeground(NOTIF_PROGRESS, progressNotification(getString(R.string.inst_preparing), 0, true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        Store st = Store.get(this);
        switch (a) {
            case ACTION_PAUSE:
                stop = true;
                if (st.state() == Store.State.DOWNLOADING) st.setState(Store.State.PAUSED);
                stopIfIdle();
                break;
            case ACTION_CANCEL:
                stop = true;
                if (st.state() == Store.State.INSTALLING) Engine.get().cancel();
                runWorker(() -> {
                    joinOther();
                    deleteFiles(this);
                    st.setState(Store.State.AVAILABLE);
                    Ota.setBusy(this, false, "idle");
                    stopIfIdle();
                });
                break;
            case ACTION_REVERT:
                runWorker(() -> {
                    Engine.get().revertReady();
                    st.clearPending();
                    st.setState(Store.State.DOWNLOADED);
                    cancelNotif(NOTIF_RESULT);
                    stopIfIdle();
                });
                break;
            case ACTION_DOWNLOAD: {
                boolean then = intent.getBooleanExtra(EXTRA_THEN_INSTALL, false);
                runWorker(() -> {
                    if (download() && then) install();
                    stopIfIdle();
                });
                break;
            }
            case ACTION_INSTALL:
                // one install at a time: a second press must not race the USB copy or the signature check
                if (worker != null && worker.isAlive()) break;
                runWorker(() -> {
                    install();
                    stopIfIdle();
                });
                break;
            case ACTION_VERIFY_ONLY:
                runWorker(() -> {
                    verifyOnly();
                    stopIfIdle();
                });
                break;
            default: // WATCH: re-attach to an install that runs in update_engine
                runWorker(() -> {
                    int s = Engine.get().status();
                    if (st.state() == Store.State.INSTALLING && (s == Ota.UE_IDLE || s < 0)) {
                        st.setError("err_install", "-1");
                    } else if (s == Ota.UE_NEED_REBOOT) {
                        st.setState(Store.State.READY);
                    }
                    stopIfIdle();
                });
        }
        return START_NOT_STICKY;
    }

    private void runWorker(Runnable r) {
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                r.run();
            } catch (Throwable e) {
                Log.e(Ota.TAG, "worker", e);
                Store.get(this).setError("err_install", "-2");
                stopIfIdle();
            }
        }, "updater");
        joinOther();
        stop = false;
        worker = t;
        t.start();
    }

    private void joinOther() {
        Thread w = worker;
        if (w != null && w != Thread.currentThread() && w.isAlive()) {
            stop = true;
            try {
                w.join(10000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void stopIfIdle() {
        Store.State s = Store.get(this).state();
        if (s != Store.State.DOWNLOADING && s != Store.State.INSTALLING) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    // ------------------------------------------------------------------ files
    static File zipFile(Context c, UpdateManifest.Pkg p) { return new File(Ota.dir(c), p.fileName()); }
    static File metaFile(Context c, UpdateManifest.Pkg p) { return new File(Ota.dir(c), p.fileName() + ".metadata"); }

    /** The file update_engine reads: the download, or the zip on the USB stick. */
    static File payloadSource(Context c, Store st, UpdateManifest.Pkg p) {
        return st.fromUsb() ? new File(st.usbPath(), p.fileName()) : zipFile(c, p);
    }

    public static void deleteFiles(Context c) {
        File[] fs = Ota.dir(c).listFiles();
        if (fs != null) for (File f : fs) if (!f.delete()) Log.w(Ota.TAG, "cannot delete " + f);
    }

    private UpdateManifest.Pkg pkg() {
        try {
            return UpdateManifest.Pkg.parse(Store.get(this).packageJson());
        } catch (JSONException e) {
            return null;
        }
    }

    private String version() {
        try {
            return new UpdateManifest(Store.get(this).manifestJson()).version;
        } catch (JSONException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ download
    /** @return true when the verified zip + metadata are ready */
    private boolean download() {
        Store st = Store.get(this);
        UpdateManifest.Pkg p = pkg();
        if (p == null) {
            st.setState(Store.State.IDLE);
            return false;
        }
        String ver = version();
        File meta = metaFile(this, p);
        File src = payloadSource(this, st, p);
        if (st.state() == Store.State.DOWNLOADED && src.isFile()) return true;
        st.setState(Store.State.DOWNLOADING);
        Ota.setBusy(this, true, "downloading");
        try {
            if (st.fromUsb()) {
                if (!p.metadataUrl.isEmpty()) copyUsbMetadata(st, p, meta);
            } else {
                Preflight.Block b = Preflight.downloadSpace(this, p, src.isFile() ? src.length() : 0);
                if (b != null) {
                    st.setError(b.res, b.arg);
                    return false;
                }
                if (!p.metadataUrl.isEmpty() && !meta.isFile()) {
                    byte[] m = fetchWithRetry(p.metadataUrl);
                    if (m == null) return false;
                    if (!p.metadataSha256.isEmpty() && !Trust.hex(Trust.sha256().digest(m)).equals(p.metadataSha256)) {
                        throw new IOException("metadata sha256 mismatch");
                    }
                    try (FileOutputStream o = new FileOutputStream(meta)) {
                        o.write(m);
                    }
                }
                long t0 = System.nanoTime(), b0 = src.isFile() ? src.length() : 0;
                int attempt = 0;
                while (true) {
                    if (stop) return false;
                    if (src.isFile() && src.length() == p.size) break;   // complete: straight to the checks
                    if (!online()) {
                        st.setDownload(src.isFile() ? src.length() : 0, p.size, st.dlEtag(), 0);
                        notifyProgress(getString(R.string.dl_waiting_network), -1);
                        sleep(5000);
                        continue;
                    }
                    try {
                        final long tStart = t0, bStart = b0;
                        String tag = Http.download(p.url, src, p.size, st.dlEtag(), (bytes, total) -> {
                            long dt = System.nanoTime() - tStart;
                            long speed = dt > 0 ? (bytes - bStart) * 1_000_000_000L / dt : 0;
                            st.setDownload(bytes, total, st.dlEtag(), speed);
                            notifyProgress(getString(R.string.dl_title, ver), total > 0 ? (int) (bytes * 100 / total) : -1);
                            return !stop;
                        });
                        st.setDownload(p.size, p.size, tag, 0);
                        break;
                    } catch (Http.InterruptedIOException2 e) {
                        return false;
                    } catch (Http.HttpException e) {
                        if (e.code == 404 || e.code == 410) throw e;
                        attempt++;
                    } catch (IOException e) {
                        Log.w(Ota.TAG, "download: " + e);
                        attempt++;
                    }
                    sleep(Math.min(60000, 5000L * attempt));
                    t0 = System.nanoTime();
                    b0 = src.isFile() ? src.length() : 0;
                }
            }
            // verify: whole-zip sha256, metadata = payload prefix
            notifyProgress(getString(R.string.dl_verifying), -1);
            String h = Trust.sha256(src, -1, () -> stop);
            if (!h.equals(p.sha256)) {
                Log.w(Ota.TAG, "zip sha256 " + h + " != " + p.sha256);
                if (!st.fromUsb()) src.delete();
                st.setError("err_hash", "");
                return false;
            }
            if (!Engine.verifyPackage(src)) {
                if (!st.fromUsb()) src.delete();
                st.setError("err_signature", "");
                return false;
            }
            st.setState(Store.State.DOWNLOADED);
            Log.i(Ota.TAG, "download verified: " + p.fileName());
            return true;
        } catch (IOException e) {
            Log.w(Ota.TAG, "download failed", e);
            if (!stop) st.setError(st.fromUsb() ? "usb_none" : "err_network", "");
            return false;
        } finally {
            if (stop && st.state() == Store.State.DOWNLOADING) st.setState(Store.State.PAUSED);
            Ota.setBusy(this, false, st.state().name().toLowerCase());
        }
    }

    private void copyUsbMetadata(Store st, UpdateManifest.Pkg p, File meta) throws IOException {
        String name = p.metadataUrl.substring(p.metadataUrl.lastIndexOf('/') + 1);
        File m = new File(st.usbPath(), name);
        if (!m.isFile() || m.length() > (8 << 20)) throw new IOException("no " + m);
        byte[] data = java.nio.file.Files.readAllBytes(m.toPath());
        try (FileOutputStream o = new FileOutputStream(meta)) {
            o.write(data);
        }
    }

    private boolean metadataMatches(File zip, UpdateManifest.Pkg p, File meta) throws IOException {
        byte[] m = java.nio.file.Files.readAllBytes(meta.toPath());
        if (m.length == 0 || m.length > p.payloadSize) return false;
        byte[] z = new byte[m.length];
        try (RandomAccessFile r = new RandomAccessFile(zip, "r")) {
            r.seek(p.payloadOffset);
            r.readFully(z);
        }
        return Arrays.equals(m, z);
    }

    private byte[] fetchWithRetry(String url) throws IOException {
        for (int i = 0; ; i++) {
            if (stop) return null;
            try {
                return Http.fetch(url, 8 << 20);
            } catch (Http.HttpException e) {
                if (e.code == 404 || e.code == 410) throw e;
            } catch (IOException e) {
                if (i > 20) throw e;
            }
            sleep(Math.min(60000, 5000L * (i + 1)));
        }
    }

    private boolean online() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    private void sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stop && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ install
    private void install() {
        Store st = Store.get(this);
        UpdateManifest.Pkg p = pkg();
        UpdateManifest m;
        try {
            m = new UpdateManifest(st.manifestJson());
        } catch (JSONException e) {
            st.setState(Store.State.IDLE);
            return;
        }
        if (p == null) return;
        File src = payloadSource(this, st, p);
        if (!src.isFile()) {
            st.setState(Store.State.AVAILABLE);
            return;
        }
        if (!st.firmwareConsent()) {          // the activity asks first; never install without it
            st.setState(Store.State.DOWNLOADED);
            return;
        }
        Preflight.Block b = Preflight.install(this);
        if (b != null) {
            st.setError(b.res, b.arg);
            return;
        }
        // a package on a USB stick is copied to /cache first: the recovery reads only /cache
        if (st.fromUsb()) {
            File dst = zipFile(this, p);
            try {
                notifyProgress(getString(R.string.inst_title, version()), -1);
                java.nio.file.Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!Trust.sha256(dst, -1, () -> stop).equals(p.sha256)) throw new IOException("copy sha256");
                src = dst;
            } catch (IOException e) {
                Log.w(Ota.TAG, "USB copy to " + dst + ": " + e);
                dst.delete();
                st.setError("err_hash", "");
                return;
            }
        }
        // the whole-file signature, while the state still says DOWNLOADED (the screen offers nothing to restart)
        notifyProgress(getString(R.string.dl_verifying), -1);
        if (!Engine.verifyPackage(src)) {
            src.delete();
            st.setError("err_signature", "");
            return;
        }
        if (stop) {
            st.setState(Store.State.DOWNLOADED);
            return;
        }
        // READY only right before the BCB and the restart: after the boot, Outcome.afterBoot says "updated" when
        // the new build runs and "could not be installed, the previous version runs" when the recovery refused
        st.setPending(m.buildId, m.version, Ota.buildId(), Ota.currentVersion());
        st.setProgress(1f);
        st.setState(Store.State.READY);
        Ota.setBusy(this, true, "installing");
        acquireWake();
        try {
            Engine.get().apply(this, src, p);
        } catch (IOException e) {
            Log.w(Ota.TAG, "apply failed", e);
            st.clearPending();
            st.setError("err_install", "-1");
            Ota.setBusy(this, false, "error");
            releaseWake();
        }
    }

    /** Dry run of everything install() checks; logs the result, writes nothing to any slot. */
    private void verifyOnly() {
        Store st = Store.get(this);
        UpdateManifest.Pkg p = pkg();
        if (p == null || st.state() != Store.State.DOWNLOADED) {
            Log.i(Ota.TAG, "verify-only: no downloaded update (state " + st.state() + ")");
            return;
        }
        Preflight.Block b = Preflight.install(this);
        boolean sig = Engine.verifyPackage(payloadSource(this, st, p));
        Log.i(Ota.TAG, "verify-only: preflight=" + (b == null ? "ok" : b.res + ":" + b.arg)
                + " package_signature=" + (sig ? "ok" : "FAILED")
                + " consent=" + st.firmwareConsent() + " (nothing written)");
    }

    @Override
    public void onStatus(int status, float percent) {
        Store st = Store.get(this);
        if (st.state() != Store.State.INSTALLING) return;
        float f;
        switch (status) {
            case Ota.UE_DOWNLOADING: f = 0.90f * percent; break;
            case Ota.UE_VERIFYING: f = 0.90f + 0.05f * percent; break;
            case Ota.UE_FINALIZING: f = 0.95f + 0.05f * percent; break;
            default: return;
        }
        st.setProgress(f);
        notifyProgress(getString(R.string.inst_title, version()), (int) (f * 100));
    }

    @Override
    public void onComplete(int code) {
        Store st = Store.get(this);
        if (st.state() != Store.State.INSTALLING) return;
        String ver = version();
        if (code == Ota.ERR_SUCCESS || code == Ota.ERR_UPDATED_BUT_NOT_ACTIVE) {
            st.setProgress(1f);
            st.setState(Store.State.READY);
            Ota.setBusy(this, false, "ready");
            result(getString(R.string.n_ready_title), getString(R.string.ready_body, ver));
        } else if (code == Ota.ERR_USER_CANCELED) {
            st.clearPending();
            st.setState(Store.State.DOWNLOADED);
            Ota.setBusy(this, false, "idle");
        } else {
            st.clearPending();
            st.setError(code == Ota.ERR_NOT_ENOUGH_SPACE ? "err_space_device" : "err_install", String.valueOf(code));
            Ota.setBusy(this, false, "error");
        }
        releaseWake();
        cancelNotif(NOTIF_PROGRESS);
    }

    private void acquireWake() {
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lumen:ota-install");
            wake.setReferenceCounted(false);
        }
        wake.acquire(40 * 60 * 1000L);
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
    }

    // ------------------------------------------------------------------ notifications
    static void channel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(Ota.NOTIF_CHANNEL, c.getString(R.string.ch_updates),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    static PendingIntent openUi(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, UpdaterActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progressNotification(String title, int pct, boolean indeterminate) {
        return new Notification.Builder(this, Ota.NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_update).setContentTitle(title)
                .setProgress(100, Math.max(0, pct), indeterminate || pct < 0)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openUi(this)).build();
    }

    private void notifyProgress(String title, int pct) {
        long now = System.currentTimeMillis();
        if (now - lastNotif < 1000) return;
        lastNotif = now;
        getSystemService(NotificationManager.class).notify(NOTIF_PROGRESS, progressNotification(title, pct, false));
    }

    private void result(String title, String text) { notifyResult(this, title, text); }

    static void notifyResult(Context c, String title, String text) {
        channel(c);
        Notification n = new Notification.Builder(c, Ota.NOTIF_CHANNEL)
                .setSmallIcon(R.drawable.ic_update).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(openUi(c)).build();
        c.getSystemService(NotificationManager.class).notify(NOTIF_RESULT, n);
    }

    private void cancelNotif(int id) { getSystemService(NotificationManager.class).cancel(id); }
}
