package org.z9x.home.usb;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
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

import org.z9x.home.R;
import org.z9x.home.ui.Theme;

import java.io.File;
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
 */
public class PhotoActivity extends Activity {
    static final String EXTRA_PATH = "path";
    private static final String TAG = "Z9xHomeFiles";
    private static final long CAPTION_MS = 3000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private ImageView mImage;
    private TextView mCaption;
    private List<File> mPhotos = new ArrayList<>();
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

    private void step(int d) {
        if (mPhotos.isEmpty()) return;
        int n = mPhotos.size();
        show(((mIndex + d) % n + n) % n);
    }

    private void show(int i) {
        if (i < 0 || i >= mPhotos.size()) return;
        mIndex = i;
        final File f = mPhotos.get(i);
        final int seq = ++mSeq;
        final int w = getResources().getDisplayMetrics().widthPixels;
        final int h = getResources().getDisplayMetrics().heightPixels;
        caption();
        mIo.execute(() -> {
            Bitmap bm = decode(f, w, h);
            mMain.post(() -> {
                if (seq != mSeq || isFinishing() || isDestroyed()) {
                    if (bm != null) bm.recycle();
                    return;
                }
                mImage.setImageBitmap(bm);
                if (bm == null) {
                    mCaption.setText(getString(R.string.files_open_failed, f.getName()));
                    mCaption.setAlpha(1f);
                }
            });
        });
    }

    private void caption() {
        if (mIndex < 0 || mIndex >= mPhotos.size()) return;
        mCaption.setText(String.format(Locale.getDefault(), "%s   %d / %d", mPhotos.get(mIndex).getName(),
                mIndex + 1, mPhotos.size()));
        mCaption.animate().cancel();
        mCaption.setAlpha(1f);
        mMain.removeCallbacks(mHideCaption);
        mMain.postDelayed(mHideCaption, CAPTION_MS);
    }

    /** Worker thread: the photo at most twice the screen in each side, upright; null when unreadable. */
    static Bitmap decode(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int rot = 0;
            try {
                int or = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,
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
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), d);
            if (bm == null || rot == 0) return bm;
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "decode " + f + ": " + t);
            return null;
        }
    }
}
