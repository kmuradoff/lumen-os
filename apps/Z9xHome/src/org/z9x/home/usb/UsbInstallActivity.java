package org.z9x.home.usb;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.z9x.home.R;
import org.z9x.home.ui.ContextPanel;
import org.z9x.home.ui.ListPanel;
import org.z9x.home.ui.Theme;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apps > Install from USB (Lumen OS 1.0.1; the way to install apps without Google Play): the .apk files of
 * the USB sticks (UsbApks) in the Lumen side panel, each with its icon, version and "New" / "Update". OK
 * installs one through a PackageInstaller session that always asks the user (USER_ACTION_REQUIRED: the
 * system installer's confirm screen comes up for every app); the result arrives at UsbInstallReceiver. The
 * list follows sticks being inserted and removed. Not exported: opened by Lumen Home itself (the Apps tile,
 * the keyboard hint of search).
 */
public class UsbInstallActivity extends Activity {
    private static final String TAG = "Z9xHomeUsb";
    /** Optional explanation shown under the title (search: "install a keyboard app"). */
    public static final String EXTRA_HINT = "hint";
    static final String ACTION_RESULT = "org.z9x.home.usb.action.INSTALL_RESULT";
    static final String EXTRA_LABEL = "label";

    private static UsbInstallActivity sShown;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private ContextPanel mPanel;
    private UsbApks.Scan mScan;
    private String mHint;
    private int mSeq;
    private String mBusyPkg;              // the app being copied into a session (one at a time)
    private CharSequence mShownSub;
    private int mBusyPercent;

    private final BroadcastReceiver mMedia = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Log.i(TAG, "storage " + i.getAction());
            rescan();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.init(this);
        mHint = getIntent().getStringExtra(EXTRA_HINT);
        FrameLayout root = new FrameLayout(this);
        mPanel = new ContextPanel(this);
        mPanel.setWidthDesign(720);
        mPanel.setOnClosed(() -> root.postDelayed(this::finish, 200));
        root.addView(mPanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.info(0, "…"));
        mShownSub = sub();
        mPanel.open(getString(R.string.usb_title), mShownSub, l);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sShown = this;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        // system broadcasts (protected): no other app can send them
        registerReceiver(mMedia, f, Context.RECEIVER_EXPORTED);
        rescan();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (sShown == this) sShown = null;
        try {
            unregisterReceiver(mMedia);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIo.shutdown();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (mPanel.onKey(e.getKeyCode(), e)) return true;
        return super.dispatchKeyEvent(e);
    }

    /** UsbInstallReceiver: an install finished (main thread). */
    static void onResult(String pkg) {
        UsbInstallActivity a = sShown;
        if (a != null) a.rescan();
    }

    private CharSequence sub() {
        if (mHint != null && !mHint.isEmpty()) return mHint;
        if (mScan == null) return "";
        if (mScan.sticks == 0) return getString(R.string.usb_none);
        return mScan.apks.isEmpty() ? getString(R.string.usb_empty) : "";
    }

    private void rescan() {
        final int seq = ++mSeq;
        mIo.execute(() -> {
            UsbApks.Scan s = UsbApks.scan(getApplicationContext());
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed()) return;
                mScan = s;
                show();
            });
        });
    }

    /** The list; the page is reopened only when its subtitle changes (else the focus stays). */
    private void show() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        if (mScan.sticks == 0) l.add(ListPanel.Item.info(R.drawable.ic_usb, getString(R.string.usb_none)));
        else if (mScan.apks.isEmpty()) l.add(ListPanel.Item.info(R.drawable.ic_usb, getString(R.string.usb_empty)));
        for (UsbApks.Entry e : mScan.apks) {
            ListPanel.Item it = ListPanel.Item.action(e.icon != null ? 0 : R.drawable.ic_apps, e.label, null);
            it.drawable = e.icon;
            if (e.icon != null) e.icon.setBounds(0, 0, Theme.px(36), Theme.px(36));
            it.value(value(e));
            it.tag = e;
            it.action = () -> install(e);
            l.add(it);
        }
        CharSequence sub = sub();
        if (!sub.toString().equals(String.valueOf(mShownSub))) {
            mShownSub = sub;
            mPanel.open(getString(R.string.usb_title), sub, l);
        } else {
            mPanel.replace(l);
        }
    }

    private String value(UsbApks.Entry e) {
        StringBuilder v = new StringBuilder();
        if (e.pkg.equals(mBusyPkg)) return getString(R.string.usb_installing, mBusyPercent);
        if (!e.versionName.isEmpty()) v.append(e.versionName).append(" · ");
        v.append(getString(e.installed ? R.string.usb_update : R.string.usb_new)).append(" · ").append(e.where);
        return v.toString();
    }

    private void progress(String pkg, int percent) {
        mMain.post(() -> {
            if (isFinishing() || isDestroyed() || mScan == null) return;
            mBusyPkg = pkg;
            mBusyPercent = percent;
            show();
        });
    }

    private void install(UsbApks.Entry e) {
        if (mBusyPkg != null) return;
        mBusyPkg = e.pkg;
        mBusyPercent = 0;
        show();
        final Context app = getApplicationContext();
        mIo.execute(() -> {
            String err = stage(app, e);
            mMain.post(() -> {
                mBusyPkg = null;
                if (err != null) {
                    Toast.makeText(app, app.getString(R.string.usb_failed, e.label, err), Toast.LENGTH_LONG).show();
                    rescan();
                } else if (!isFinishing() && !isDestroyed() && mScan != null) {
                    show();
                }
            });
        });
    }

    /** Streams the file into a new session and commits it (worker thread); null = committed. */
    private String stage(Context app, UsbApks.Entry e) {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            sp.setAppPackageName(e.pkg);
            long total = e.file.length();
            sp.setSize(total);
            id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(e.file);
                     OutputStream out = s.openWrite("base.apk", 0, total)) {
                    byte[] buf = new byte[1 << 16];
                    long done = 0;
                    int last = -1;
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                        done += n;
                        int pc = (int) (done * 100 / Math.max(1, total));
                        if (pc / 5 != last / 5) {
                            last = pc;
                            s.setStagingProgress(done / (float) Math.max(1, total));
                            progress(e.pkg, pc);
                        }
                    }
                    s.fsync(out);
                }
                Intent cb = new Intent(app, UsbInstallReceiver.class).setAction(ACTION_RESULT)
                        .setPackage(app.getPackageName()).putExtra(EXTRA_LABEL, e.label);
                // explicit, so it may be mutable: PackageInstaller adds the status extras
                PendingIntent p = PendingIntent.getBroadcast(app, id, cb,
                        PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.commit(p.getIntentSender());
                Log.i(TAG, "committed session " + id + " " + e.pkg + " from " + e.file);
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "install " + e.file + ": " + t);
            if (id >= 0) {
                try {
                    pi.abandonSession(id);
                } catch (Throwable ignored) {
                }
            }
            String m = t.getMessage();
            return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
        }
    }
}
