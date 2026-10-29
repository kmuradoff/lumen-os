package org.lumen.home.usb;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.lumen.home.R;
import org.lumen.home.net.NetFilesProvider;
import org.lumen.home.ui.Theme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo viewer of Files (Lumen OS 1.0.4): one photo full screen, scaled down while decoding to the screen
 * (a 48 MP photo never becomes a 190 MB bitmap) and turned upright by its EXIF orientation. LEFT / RIGHT:
 * previous / next photo of the same folder, OK: name and position for a few seconds, BACK: back to Files.
 * Reads only files on a USB stick (UsbFiles.onStick).
 * 1.0.6: or the photos of a network folder: the intent's data and EXTRA_URIS (the folder's photos, for LEFT /
 * RIGHT), NetFilesProvider URIs only; each photo is read once into memory (one SMB connection) and decoded
 * from there.
 */
public class PhotoActivity extends Activity {
    static final String EXTRA_PATH = "path";
    /** ArrayList of the network folder's photo URIs (the intent's data is the one shown first). */
    public static final String EXTRA_URIS = "uris";
    /** A network photo bigger than this is not read (it would not fit the memory of a weak projector). */
    private static final int MAX_NET_BYTES = 64 << 20;
    private static final String TAG = "Z9xHomeFiles";
    private static final long CAPTION_MS = 3000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private ImageView mImage;
    private TextView mCaption;
    private List<File> mPhotos = new ArrayList<>();
    /** Network photos (then mPhotos stays empty). */
    private List<Uri> mUris;
    private int mIndex = -1;
    private int mSeq;
    private final Runnable mHideCaption = () -> mCaption.animate().alpha(0f).setDuration(250).start();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.init(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mImage = new ImageView(this);
        mImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(mImage, new FrameLayout.LayoutParams(-1, -1));
        mCaption = new TextView(this);
        Theme.text(mCaption, 26, Theme.REGULAR, Theme.TEXT1);
        mCaption.setShadowLayer(Theme.pxf(6), 0, 0, 0xCC000000);
        mCaption.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.bottomMargin = Theme.px(Theme.SAFE);
        root.addView(mCaption, lp);
        setContentView(root);
        Uri data = getIntent().getData();
        if (data != null) {
            if (!NetFilesProvider.isOurs(data)) {
                finish();
                return;
            }
            ArrayList<Uri> all = new ArrayList<>();
            ArrayList<Uri> extra = getIntent().getParcelableArrayListExtra(EXTRA_URIS, Uri.class);
            if (extra != null) for (Uri u : extra) if (NetFilesProvider.isOurs(u)) all.add(u);
            int i = all.indexOf(data);
            if (i < 0) {
                all.add(0, data);
                i = 0;
            }
            mUris = all;
            show(i);
            return;
        }
        String path = getIntent().getStringExtra(EXTRA_PATH);
        final File first = path == null ? null : new File(path);
        if (first == null || !UsbFiles.onStick(this, first)) {
            finish();
            return;
        }
        mIo.execute(() -> {
            List<File> all = UsbFiles.photos(first.getParentFile());
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                mPhotos = all;
                int i = all.indexOf(first);
                if (i < 0) {
                    mPhotos = new ArrayList<>(all);
                    mPhotos.add(0, first);
                    i = 0;
                }
                show(i);
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMain.removeCallbacksAndMessages(null);
        mIo.shutdownNow();
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        boolean rtl = getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_NEXT) {
            step(rtl && k == KeyEvent.KEYCODE_DPAD_RIGHT ? -1 : 1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            step(rtl && k == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : -1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_UP
                || k == KeyEvent.KEYCODE_DPAD_DOWN) {
            caption();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    private int count() {
        return mUris != null ? mUris.size() : mPhotos.size();
    }

    private String name(int i) {
        return mUris != null ? NetFilesProvider.name(mUris.get(i)) : mPhotos.get(i).getName();
    }

    private void step(int d) {
        int n = count();
        if (n == 0) return;
        show(((mIndex + d) % n + n) % n);
    }

    private void show(int i) {
        if (i < 0 || i >= count()) return;
        mIndex = i;
        final File f = mUris != null ? null : mPhotos.get(i);
        final Uri u = mUris != null ? mUris.get(i) : null;
        final String name = name(i);
        final int seq = ++mSeq;
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        caption();
        mIo.execute(() -> {
            Bitmap bm = f != null ? decode(f, w, h) : decode(read(u), w, h);
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed()) {
                    if (bm != null) bm.recycle();
                    return;
                }
                mImage.setImageBitmap(bm);
                if (bm == null) {
                    mCaption.setText(getString(R.string.files_open_failed, name));
                    mCaption.setAlpha(1f);
                }
            });
        });
    }

    private void caption() {
        if (mIndex < 0 || mIndex >= count()) return;
        mCaption.setText(String.format(Locale.getDefault(), "%s   %d / %d", name(mIndex),
                mIndex + 1, count()));
        mCaption.animate().cancel();
        mCaption.setAlpha(1f);
        mMain.removeCallbacks(mHideCaption);
        mMain.postDelayed(mHideCaption, CAPTION_MS);
    }

    /** Worker thread: a network photo's bytes (null when unreadable or too big). */
    private byte[] read(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_NET_BYTES) return null;
                if (Thread.currentThread().isInterrupted()) return null;
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "read " + u + ": " + e);
            return null;
        }
    }

    /** Where decode() reads a photo from: a file, or a network photo's bytes. */
    private interface Src {
        Bitmap bitmap(BitmapFactory.Options o);

        ExifInterface exif() throws IOException;
    }

    /** Worker thread: the photo at most twice the screen in each side, upright; null when unreadable. */
    static Bitmap decode(File f, int w, int h) {
        return decode(new Src() {
            @Override
            public Bitmap bitmap(BitmapFactory.Options o) {
                return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            }

            @Override
            public ExifInterface exif() throws IOException {
                return new ExifInterface(f.getAbsolutePath());
            }
        }, w, h, f.toString());
    }

    static Bitmap decode(byte[] b, int w, int h) {
        if (b == null) return null;
        return decode(new Src() {
            @Override
            public Bitmap bitmap(BitmapFactory.Options o) {
                return BitmapFactory.decodeByteArray(b, 0, b.length, o);
            }

            @Override
            public ExifInterface exif() throws IOException {
                return new ExifInterface(new ByteArrayInputStream(b));
            }
        }, w, h, b.length + " bytes");
    }

    private static Bitmap decode(Src src, int w, int h, String what) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            src.bitmap(o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int rot = 0;
            try {
                int or = src.exif().getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                        : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Throwable ignored) {
            }
            int sw = rot % 180 == 0 ? o.outWidth : o.outHeight;
            int sh = rot % 180 == 0 ? o.outHeight : o.outWidth;
            int sample = 1;
            while (sw / (sample * 2) >= w && sh / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = src.bitmap(d);
            if (bm == null || rot == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "decode " + what + ": " + t);
            return null;
        }
    }
}
