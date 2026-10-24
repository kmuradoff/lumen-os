package org.z9x.home.usb;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import org.z9x.home.R;
import org.z9x.home.net.NetFilesProvider;
import org.z9x.home.ui.Theme;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The built-in player of Files (Lumen OS 1.0.4), used only when no installed app plays the type (Кино, VLC
 * and other players take the file first): Android's own MediaPlayer (VideoView), so it plays what the
 * projector's decoders play (H.264, HEVC, VP9, AV1, AAC, AC-3 / E-AC-3 ... in MP4, MKV, TS, WebM).
 * OK / play-pause: pause or play, LEFT / RIGHT: 10 s back / forward (30 s while held), UP / DOWN: the time
 * bar, BACK: back to Files. A song is followed by the next song of the same folder.
 * 1.0.6: also a file of a network folder, as the intent's data (a NetFilesProvider content:// URI, played
 * through its seekable descriptor); a network song is not followed by the next one.
 */
public class PlayerActivity extends Activity {
    static final String EXTRA_PATH = "path";
    private static final String TAG = "Z9xHomeFiles";
    private static final long BAR_MS = 3500;
    private static final int STEP_MS = 10_000, STEP_HELD_MS = 30_000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private VideoView mVideo;
    private ImageView mMusic;
    private LinearLayout mBar;
    private TextView mTitle, mTime;
    private ProgressBar mProgress;
    private File mFile;
    private Uri mUri;
    private boolean mAudio, mFailed;
    private final Runnable mHideBar = () -> {
        if (mVideo != null && mVideo.isPlaying() && !mAudio) mBar.animate().alpha(0f).setDuration(250).start();
    };
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateTime();
            mMain.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        mVideo = new VideoView(this);
        root.addView(mVideo, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        mMusic = new ImageView(this);
        mMusic.setImageResource(R.drawable.ic_music);
        mMusic.setColorFilter(Theme.TEXT2);
        mMusic.setVisibility(View.GONE);
        root.addView(mMusic, new FrameLayout.LayoutParams(Theme.px(160), Theme.px(160), Gravity.CENTER));
        mBar = new LinearLayout(this);
        mBar.setOrientation(LinearLayout.VERTICAL);
        mBar.setPadding(Theme.px(Theme.SAFE), Theme.px(24), Theme.px(Theme.SAFE), Theme.px(Theme.SAFE));
        mBar.setBackgroundColor(0x99000000);
        mTitle = new TextView(this);
        Theme.text(mTitle, 30, Theme.DISPLAY, Theme.TEXT1);
        mTitle.setSingleLine(true);
        mBar.addView(mTitle);
        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(1000);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, Theme.px(8));
        pl.topMargin = Theme.px(16);
        mBar.addView(mProgress, pl);
        mTime = new TextView(this);
        Theme.text(mTime, 24, Theme.REGULAR, Theme.TEXT2);
        mBar.addView(mTime);
        root.addView(mBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);

        Uri data = getIntent().getData();
        String path = getIntent().getStringExtra(EXTRA_PATH);
        File f = path == null ? null : new File(path);
        if (data != null ? !NetFilesProvider.isOurs(data) : f == null || !UsbFiles.onStick(this, f)) {
            finish();
            return;
        }
        mVideo.setOnPreparedListener(mp -> {
            mVideo.start();
            showBar();
        });
        mVideo.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "player " + (mFile != null ? mFile : mUri) + ": error " + what + " / " + extra);
            mFailed = true;
            mTime.setText(getString(R.string.files_play_failed));
            mBar.animate().cancel();
            mBar.setAlpha(1f);
            return true;
        });
        mVideo.setOnCompletionListener(mp -> {
            if (mAudio && mFile != null) nextSong();
            else finish();
        });
        if (data != null) play(data);
        else play(f);
    }

    /** A network file: its name is the URI's last segment. */
    private void play(Uri u) {
        mUri = u;
        mFile = null;
        mFailed = false;
        String name = NetFilesProvider.name(u);
        mAudio = UsbFiles.type(name) == UsbFiles.AUDIO;
        mMusic.setVisibility(mAudio ? View.VISIBLE : View.GONE);
        mTitle.setText(name);
        mTime.setText("");
        mProgress.setProgress(0);
        mVideo.setVideoURI(u);
        showBar();
    }

    private void play(File f) {
        mFile = f;
        mFailed = false;
        mAudio = UsbFiles.type(f) == UsbFiles.AUDIO;
        mMusic.setVisibility(mAudio ? View.VISIBLE : View.GONE);
        mTitle.setText(f.getName());
        mTime.setText("");
        mProgress.setProgress(0);
        mVideo.setVideoPath(f.getAbsolutePath());
        showBar();
    }

    private void nextSong() {
        final File cur = mFile;
        mIo.execute(() -> {
            List<File> songs = new ArrayList<>();
            for (UsbFiles.Entry e : UsbFiles.list(cur.getParentFile()).entries) if (e.type == UsbFiles.AUDIO) songs.add(e.file);
            int i = songs.indexOf(cur);
            File next = i >= 0 && i + 1 < songs.size() ? songs.get(i + 1) : null;
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (next == null) finish();
                else play(next);
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mMain.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mMain.removeCallbacks(mTick);
        if (mVideo.isPlaying()) mVideo.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMain.removeCallbacksAndMessages(null);
        mIo.shutdownNow();
        mVideo.stopPlayback();
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        boolean rtl = getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int step = e.getRepeatCount() > 0 ? STEP_HELD_MS : STEP_MS;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (!mVideo.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (mVideo.isPlaying()) togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(rtl && k == KeyEvent.KEYCODE_DPAD_RIGHT ? -step : step);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(rtl && k == KeyEvent.KEYCODE_DPAD_LEFT ? step : -step);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_INFO:
                showBar();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
            default:
                return super.onKeyDown(k, e);
        }
    }

    private void togglePause() {
        if (mFailed) return;
        if (mVideo.isPlaying()) mVideo.pause();
        else mVideo.start();
        showBar();
    }

    private void seek(int deltaMs) {
        if (mFailed || !mVideo.canSeekForward() && deltaMs > 0 || !mVideo.canSeekBackward() && deltaMs < 0) return;
        int d = mVideo.getDuration();
        int to = Math.max(0, mVideo.getCurrentPosition() + deltaMs);
        if (d > 0) to = Math.min(to, Math.max(0, d - 1000));
        mVideo.seekTo(to);
        showBar();
    }

    private void showBar() {
        updateTime();
        mBar.animate().cancel();
        mBar.setAlpha(1f);
        mMain.removeCallbacks(mHideBar);
        mMain.postDelayed(mHideBar, BAR_MS);
    }

    private void updateTime() {
        if (mFailed || mVideo == null) return;
        int d = mVideo.getDuration(), p = mVideo.getCurrentPosition();
        if (d <= 0) return;
        mProgress.setProgress((int) (1000L * p / d));
        mTime.setText(time(p) + " / " + time(d) + (mVideo.isPlaying() ? "" : "   ❚❚"));
    }

    private static String time(int ms) {
        int s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", m, s % 60);
    }
}
