package org.z9x.home.usb;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Files (Lumen OS 1.0.4, in place of XGIMI's file manager): the folders of the USB sticks in the Lumen side
 * panel. OK on a folder opens it (BACK goes up), on a video or a song plays it in the app the user picked
 * for that type (Кино, VLC ...; Android asks once when there are several) or, with none installed, in the
 * built-in PlayerActivity; on a photo opens PhotoActivity (LEFT / RIGHT: the folder's other photos); on an
 * .apk opens Install from USB. One stick: its folders right away; several: the sticks first. Removing the
 * stick that is shown goes back to the start. Players read the file through UsbFilesProvider.
 * Open from the Apps page, from launchers (the Files entry) and in classic-launcher mode.
 */
public class UsbFilesActivity extends Activity {
    private static final String TAG = "Z9xHomeFiles";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private ContextPanel mPanel;
    /** The folders pushed on top of the first page, in order (the first page itself is not in it). */
    private final List<File> mPath = new ArrayList<>();
    /** What the first page shows: the only stick's root, or null = the list of sticks. */
    private File mBase;
    private List<UsbFiles.Stick> mSticks = new ArrayList<>();
    private int mSeq;

    private final BroadcastReceiver mMedia = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Log.i(TAG, "storage " + i.getAction());
            mMain.postDelayed(UsbFilesActivity.this::onStorageChanged, 300);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.init(this);
        FrameLayout root = new FrameLayout(this);
        mPanel = new ContextPanel(this);
        mPanel.setWidthDesign(760);
        mPanel.setOnClosed(() -> root.postDelayed(this::finish, 200));
        root.addView(mPanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.info(0, "…"));
        mPanel.open(getString(R.string.files_title), "", l);
        showStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_REMOVED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        // system broadcasts (protected): no other app can send them
        registerReceiver(mMedia, f, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(mMedia);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mIo.shutdownNow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // the panel pops its page on BACK up: keep our folder stack in step with it
        if ((k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) && e.getAction() == KeyEvent.ACTION_UP
                && !e.isCanceled() && mPanel.isOpen() && !mPath.isEmpty()) {
            mPath.remove(mPath.size() - 1);
            mSeq++;                                   // a folder still being read is not shown any more
        }
        if (mPanel.onKey(k, e)) return true;
        return super.dispatchKeyEvent(e);
    }

    /** First page: the sticks are read on the worker (StorageManager), then shown. */
    private void showStart() {
        final int seq = ++mSeq;
        final Context app = getApplicationContext();
        mIo.execute(() -> {
            List<UsbFiles.Stick> sticks = UsbFiles.sticks(app);
            UsbFiles.Listing only = sticks.size() == 1 ? UsbFiles.list(sticks.get(0).root) : null;
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed()) return;
                mSticks = sticks;
                mPath.clear();
                if (sticks.size() == 1) {
                    mBase = sticks.get(0).root;
                    mPanel.open(sticks.get(0).label, getString(R.string.files_title), items(only));
                } else {
                    mBase = null;
                    mPanel.open(getString(R.string.files_title), sticks.isEmpty() ? getString(R.string.files_none) : "",
                            stickItems(sticks));
                }
            });
        });
    }

    private ArrayList<ListPanel.Item> stickItems(List<UsbFiles.Stick> sticks) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        if (sticks.isEmpty()) l.add(ListPanel.Item.info(R.drawable.ic_usb, getString(R.string.files_none)));
        for (UsbFiles.Stick s : sticks) {
            ListPanel.Item it = ListPanel.Item.action(R.drawable.ic_usb, s.label, () -> open(s.root));
            it.tag = s;
            l.add(it);
        }
        return l;
    }

    /** OK on a folder: read it on the worker, then push it. */
    private void open(File dir) {
        final int seq = ++mSeq;
        mIo.execute(() -> {
            UsbFiles.Listing l = UsbFiles.list(dir);
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed() || !mPanel.isOpen()) return;
                mPath.add(dir);
                mPanel.push(dir.getName(), where(dir), items(l));
            });
        });
    }

    /** "Stick label / Movies / 2024" for a folder's subtitle. */
    private String where(File dir) {
        for (UsbFiles.Stick s : mSticks) {
            String r = s.root.getAbsolutePath(), p = dir.getAbsolutePath();
            if (p.equals(r)) return s.label;
            if (p.startsWith(r + "/")) return s.label + " / " + p.substring(r.length() + 1).replace("/", " / ");
        }
        return dir.getAbsolutePath();
    }

    private ArrayList<ListPanel.Item> items(UsbFiles.Listing listing) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        if (!listing.readable || listing.entries.isEmpty()) {
            l.add(ListPanel.Item.info(R.drawable.ic_folder, getString(R.string.files_empty)));
            return l;
        }
        for (UsbFiles.Entry e : listing.entries) {
            final File f = e.file;
            ListPanel.Item it;
            switch (e.type) {
                case UsbFiles.DIR:
                    it = ListPanel.Item.action(R.drawable.ic_folder, f.getName(), () -> open(f));
                    break;
                case UsbFiles.VIDEO:
                    it = ListPanel.Item.action(R.drawable.ic_movie, f.getName(), () -> play(f));
                    break;
                case UsbFiles.AUDIO:
                    it = ListPanel.Item.action(R.drawable.ic_music, f.getName(), () -> play(f));
                    break;
                case UsbFiles.IMAGE:
                    it = ListPanel.Item.action(R.drawable.ic_picture, f.getName(), () -> photo(f));
                    break;
                default:
                    it = ListPanel.Item.action(R.drawable.ic_apps, f.getName(), this::installFromUsb);
                    break;
            }
            if (e.type != UsbFiles.DIR) it.value(UsbFiles.size(e.size));
            it.tag = e;
            l.add(it);
        }
        if (listing.hidden > 0) {
            l.add(ListPanel.Item.info(0, getString(R.string.files_more, listing.hidden)));
        }
        return l;
    }

    /** A video or a song: the user's app for the type, else the built-in player. */
    private void play(File f) {
        String mime = UsbFiles.mime(f.getName());
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(UsbFilesProvider.uri(f), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY);
        boolean other = false;
        for (ResolveInfo r : apps) {
            if (r.activityInfo != null && !getPackageName().equals(r.activityInfo.packageName)) other = true;
        }
        try {
            if (other) {
                startActivity(view);
                Log.i(TAG, "view " + mime + " with " + apps.size() + " app(s)");
                return;
            }
            startActivity(new Intent(this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_PATH, f.getAbsolutePath()));
        } catch (ActivityNotFoundException | SecurityException t) {
            Log.w(TAG, "play " + f + ": " + t);
            Toast.makeText(this, getString(R.string.files_open_failed, f.getName()), Toast.LENGTH_LONG).show();
        }
    }

    private void photo(File f) {
        startActivity(new Intent(this, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_PATH, f.getAbsolutePath()));
    }

    private void installFromUsb() {
        startActivity(new Intent(this, UsbInstallActivity.class));
    }

    /** A stick came or went: back to the start when the folder shown is gone, else refresh the start page. */
    private void onStorageChanged() {
        if (isFinishing() || isDestroyed() || !mPanel.isOpen()) return;
        File shown = !mPath.isEmpty() ? mPath.get(mPath.size() - 1) : mBase;
        if (shown != null && shown.isDirectory() && UsbFiles.onStick(this, shown)) {
            if (mBase != null || !mPath.isEmpty()) return;   // still there: nothing changes on screen
        }
        showStart();
    }
}
