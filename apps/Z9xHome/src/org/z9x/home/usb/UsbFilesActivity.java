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
import android.os.Environment;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Files (Lumen OS 1.0.4, in place of XGIMI's file manager; the one Files entry of the Apps page, Install from USB
 * is folded into it): the projector's own storage and the USB sticks in the Lumen side panel. The first page lists
 * the places; OK on a folder opens it (BACK goes up), on a video or a song plays it in the app the user picked for
 * that type (Кино, VLC ...; Android asks once when there are several) or, with none installed, in the built-in
 * PlayerActivity; on a photo opens PhotoActivity (LEFT / RIGHT: the folder's other photos); on an .apk installs
 * that file (UsbInstallActivity, the system's confirm screen); on any other file opens the app for its type.
 * A long OK on a file or a folder opens its menu: open, copy to another place (the projector's Download folder or a
 * stick's root, under a free name, with progress and cancel), delete (after a confirmation). Removing the stick
 * that is shown goes back to the start. Players read the file through UsbFilesProvider.
 */
public class UsbFilesActivity extends Activity {
    private static final String TAG = "Z9xHomeFiles";
    /** A held OK this long opens the file's menu instead of opening the file. */
    private static final long LONG_OK_MS = 600;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final ExecutorService mCopyIo = Executors.newSingleThreadExecutor();
    private ContextPanel mPanel;
    /** The folders pushed on top of the first page, in order (the first page itself is not in it). */
    private final List<File> mPath = new ArrayList<>();
    /** Pages pushed above the folder shown (menu, confirmation, copy progress). */
    private int mMenuDepth;
    private List<UsbFiles.Stick> mPlaces = new ArrayList<>();
    private int mSeq;
    private boolean mOkDown, mOkLong;
    private Future<?> mCopy;

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
        if (mCopy != null) mCopy.cancel(true);
        mIo.shutdownNow();
        mCopyIo.shutdownNow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (isOk(k) && mPanel.isOpen() && mMenuDepth == 0 && longOk(k, e)) return true;
        // the panel pops its page on BACK up: keep our folder stack in step with it
        if ((k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) && e.getAction() == KeyEvent.ACTION_UP
                && !e.isCanceled() && mPanel.isOpen()) {
            if (mMenuDepth > 0) {
                mMenuDepth--;
            } else if (!mPath.isEmpty()) {
                mPath.remove(mPath.size() - 1);
                mSeq++;                               // a folder still being read is not shown any more
            }
        }
        if (mPanel.onKey(k, e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** OK on a file or a folder: a short press opens it (on key up), a long one opens its menu. */
    private boolean longOk(int k, KeyEvent e) {
        ListPanel.Item it = mPanel.focusedItem();
        if (it == null || !(it.tag instanceof UsbFiles.Entry)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                mOkDown = true;
                mOkLong = false;
            } else if (mOkDown && !mOkLong && e.getEventTime() - e.getDownTime() >= LONG_OK_MS) {
                mOkLong = true;
                showMenu((UsbFiles.Entry) it.tag);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            boolean click = mOkDown && !mOkLong && !e.isCanceled();
            mOkDown = false;
            if (click) mPanel.onKey(k, new KeyEvent(e.getDownTime(), e.getEventTime(), KeyEvent.ACTION_DOWN, k, 0));
            return true;
        }
        return true;
    }

    // ------------------------------------------------------------------ pages

    /** First page: the places (the projector's storage, then the sticks), read on the worker. */
    private void showStart() {
        final int seq = ++mSeq;
        final Context app = getApplicationContext();
        final String internal = getString(R.string.files_internal);
        mIo.execute(() -> {
            List<UsbFiles.Stick> places = UsbFiles.places(app, internal);
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed()) return;
                mPlaces = places;
                mPath.clear();
                mMenuDepth = 0;
                mPanel.open(getString(R.string.files_title), getString(R.string.files_hint_menu), placeItems(places));
            });
        });
    }

    private ArrayList<ListPanel.Item> placeItems(List<UsbFiles.Stick> places) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        for (UsbFiles.Stick s : places) {
            ListPanel.Item it = ListPanel.Item.action(s.internal ? R.drawable.ic_folder : R.drawable.ic_usb, s.label,
                    () -> open(s.root));
            it.tag = s;
            l.add(it);
        }
        boolean stick = false;
        for (UsbFiles.Stick s : places) stick |= !s.internal;
        if (!stick) l.add(ListPanel.Item.info(R.drawable.ic_usb, getString(R.string.files_none)));
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

    /** The folder shown again (after a copy or a delete): its items replaced, the focus kept. */
    private void refresh() {
        if (mPath.isEmpty()) {
            showStart();
            return;
        }
        final File dir = mPath.get(mPath.size() - 1);
        final int seq = ++mSeq;
        mIo.execute(() -> {
            UsbFiles.Listing l = UsbFiles.list(dir);
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed() || !mPanel.isOpen() || mMenuDepth > 0) return;
                mPanel.replace(items(l));
            });
        });
    }

    /** "Stick label / Movies / 2024" for a folder's subtitle. */
    private String where(File dir) {
        for (UsbFiles.Stick s : mPlaces) {
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
            ListPanel.Item it = ListPanel.Item.action(icon(e.type), e.file.getName(), () -> openEntry(e));
            if (e.type != UsbFiles.DIR) it.value(UsbFiles.size(e.size));
            it.tag = e;
            l.add(it);
        }
        if (listing.hidden > 0) {
            l.add(ListPanel.Item.info(0, getString(R.string.files_more, listing.hidden)));
        }
        return l;
    }

    private static int icon(int type) {
        switch (type) {
            case UsbFiles.DIR: return R.drawable.ic_folder;
            case UsbFiles.VIDEO: return R.drawable.ic_movie;
            case UsbFiles.AUDIO: return R.drawable.ic_music;
            case UsbFiles.IMAGE: return R.drawable.ic_picture;
            case UsbFiles.APK: return R.drawable.ic_apps;
            default: return R.drawable.ic_open;
        }
    }

    private void openEntry(UsbFiles.Entry e) {
        switch (e.type) {
            case UsbFiles.DIR: open(e.file); break;
            case UsbFiles.VIDEO:
            case UsbFiles.AUDIO: play(e.file); break;
            case UsbFiles.IMAGE: photo(e.file); break;
            case UsbFiles.APK: install(e.file); break;
            default: view(e.file); break;
        }
    }

    // ------------------------------------------------------------------ opening

    /** A video or a song: the user's app for the type, else the built-in player. */
    private void play(File f) {
        String mime = UsbFiles.mime(f.getName());
        Intent view = viewIntent(f, mime);
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

    /** Any other file: the app for its type (Android asks when there are several). */
    private void view(File f) {
        try {
            startActivity(viewIntent(f, UsbFiles.mime(f.getName())));
        } catch (ActivityNotFoundException | SecurityException t) {
            Log.i(TAG, "no app for " + f.getName() + ": " + t);
            Toast.makeText(this, getString(R.string.files_open_failed, f.getName()), Toast.LENGTH_LONG).show();
        }
    }

    private static Intent viewIntent(File f, String mime) {
        return new Intent(Intent.ACTION_VIEW).setDataAndType(UsbFilesProvider.uri(f), mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private void photo(File f) {
        startActivity(new Intent(this, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_PATH, f.getAbsolutePath()));
    }

    private void install(File f) {
        startActivity(new Intent(this, UsbInstallActivity.class).putExtra(UsbInstallActivity.EXTRA_PATH, f.getAbsolutePath()));
    }

    // ------------------------------------------------------------------ the long OK menu

    private void showMenu(UsbFiles.Entry e) {
        final File f = e.file;
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(icon(e.type), getString(R.string.files_menu_open), () -> {
            popMenus();
            openEntry(e);
        }));
        for (UsbFiles.Stick p : mPlaces) {
            if (contains(p.root, f)) continue;                 // a copy goes to another place
            File dir = p.internal ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) : p.root;
            String to = p.internal ? p.label + " / " + dir.getName() : p.label;
            l.add(ListPanel.Item.action(p.internal ? R.drawable.ic_folder : R.drawable.ic_usb,
                    getString(R.string.files_copy_to, to), () -> copy(f, dir)));
        }
        l.add(ListPanel.Item.action(R.drawable.ic_trash, getString(R.string.files_delete), () -> confirmDelete(f)));
        mMenuDepth++;
        mPanel.push(f.getName(), where(f.getParentFile()), l);
    }

    private static boolean contains(File root, File f) {
        String r = root.getAbsolutePath(), p = f.getAbsolutePath();
        return p.equals(r) || p.startsWith(r + "/");
    }

    /** Drops the menu, confirmation and progress pages: the folder is shown again. */
    private void popMenus() {
        while (mMenuDepth > 0) {
            mPanel.pop();
            mMenuDepth--;
        }
    }

    private void confirmDelete(File f) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_trash, getString(R.string.files_delete), () -> delete(f)));
        l.add(ListPanel.Item.action(R.drawable.ic_close, getString(R.string.files_cancel), this::popMenus));
        mMenuDepth++;
        mPanel.push(getString(R.string.files_delete_confirm, f.getName()), where(f.getParentFile()), l);
    }

    private void delete(File f) {
        popMenus();
        final Context app = getApplicationContext();
        mIo.execute(() -> {
            boolean ok = UsbFiles.delete(f);
            Log.i(TAG, "delete " + f + " -> " + ok);
            mMain.post(() -> {
                Toast.makeText(app, getString(ok ? R.string.files_deleted : R.string.files_failed, f.getName()),
                        Toast.LENGTH_SHORT).show();
                if (!isFinishing() && !isDestroyed()) refresh();
            });
        });
    }

    /** One copy at a time, on its own worker; a page shows the progress and cancels it. */
    private void copy(File src, File dir) {
        if (mCopy != null && !mCopy.isDone()) return;
        popMenus();
        final Context app = getApplicationContext();
        final String name = src.getName();
        final ListPanel.Item progress = ListPanel.Item.info(R.drawable.ic_update, getString(R.string.files_copying, 0));
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(progress);
        l.add(ListPanel.Item.action(R.drawable.ic_close, getString(R.string.files_cancel), () -> {
            if (mCopy != null) mCopy.cancel(true);
        }));
        mMenuDepth++;
        mPanel.push(name, getString(R.string.files_copy_to, where(dir)), l);
        mCopy = mCopyIo.submit(() -> {
            String err = null;
            File out = null;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder " + dir);
                long need = UsbFiles.bytes(src);
                if (dir.getUsableSpace() < need) throw new IOException(getString(R.string.files_no_space));
                final int[] last = {-1};
                out = UsbFiles.copy(src, dir, (done, total) -> {
                    int pc = (int) (done * 100 / total);
                    if (pc == last[0]) return;
                    last[0] = pc;
                    mMain.post(() -> {
                        progress.text = getString(R.string.files_copying, pc);
                        mPanel.refresh(progress);              // only while the progress page is shown
                    });
                });
            } catch (IOException | RuntimeException t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            }
            final String fErr = err;
            final File fOut = out;
            Log.i(TAG, "copy " + src + " -> " + dir + ": " + (fErr == null ? "OK " + fOut : fErr));
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(app, fErr == null ? getString(R.string.files_copied, fOut.getName())
                        : getString(R.string.files_failed, name) + " (" + fErr + ")", Toast.LENGTH_LONG).show();
                popMenus();
                refresh();
            });
        });
    }

    /** A stick came or went: back to the start when the folder shown is gone, else refresh the start page. */
    private void onStorageChanged() {
        if (isFinishing() || isDestroyed() || !mPanel.isOpen()) return;
        File shown = !mPath.isEmpty() ? mPath.get(mPath.size() - 1) : null;
        if (shown != null && shown.isDirectory() && UsbFiles.inPlaces(this, shown)) return;
        if (mCopy != null && !mCopy.isDone()) mCopy.cancel(true);
        showStart();
    }
}
