package org.z9x.home.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import org.z9x.home.App;
import org.z9x.home.data.Card;

/**
 * Optional silent hero trailer (preview_video_uri). OFF by default (user decision; costs 40-80 MB and
 * a decoder while visible). When enabled: starts 3 s after a slide's art is shown, muted, never takes
 * audio focus, http(s) only, released on any slide change, page change, pause or completion.
 */
final class Trailer implements TextureView.SurfaceTextureListener {
    interface OnPlaying {
        /** The trailer started (true) or stopped after having started (false). */
        void onPlaying(boolean playing);
    }

    private static final long DELAY_MS = 3000;
    private final TextureView mView;
    private MediaPlayer mPlayer;
    private Surface mSurface;
    private boolean mEnabled, mPlaying;
    private Card mArmed;
    private OnPlaying mOnPlaying;
    private final Runnable mStart = this::start;

    Trailer(Context c) {
        mView = new TextureView(c);
        mView.setAlpha(0f);
        mView.setVisibility(View.GONE);
        mView.setSurfaceTextureListener(this);
    }

    View view() {
        return mView;
    }

    void setOnPlaying(OnPlaying l) {
        mOnPlaying = l;
    }

    void setEnabled(boolean on) {
        mEnabled = on;
        if (!on) stop();
    }

    void arm(Card k) {
        stop();
        if (!mEnabled || k == null || k.video == null) return;
        String s = Uri.parse(k.video).getScheme();
        if (!"https".equalsIgnoreCase(s) && !"http".equalsIgnoreCase(s)) return;
        mArmed = k;
        mView.postDelayed(mStart, DELAY_MS);
    }

    private void start() {
        if (mArmed == null) return;
        mView.setVisibility(View.VISIBLE);
        if (mView.isAvailable()) play();
    }

    private void play() {
        Card k = mArmed;
        if (k == null || mPlayer != null) return;
        try {
            mSurface = new Surface(mView.getSurfaceTexture());
            MediaPlayer mp = new MediaPlayer();
            mPlayer = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setSurface(mSurface);
            mp.setDataSource(k.video);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (mPlayer != p) return;
                p.setVolume(0f, 0f);
                p.start();
                mView.animate().alpha(1f).setDuration(600).start();
                mPlaying = true;
                if (mOnPlaying != null) mOnPlaying.onPlaying(true);
                Log.i(App.TAG, "hero trailer start pkg=" + k.pkg);
            });
            mp.setOnCompletionListener(p -> stop());
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(App.TAG, "hero trailer error " + what + "/" + extra);
                stop();
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(App.TAG, "hero trailer: " + t);
            stop();
        }
    }

    void stop() {
        mView.removeCallbacks(mStart);
        mArmed = null;
        MediaPlayer p = mPlayer;
        mPlayer = null;
        if (p != null) {
            try {
                p.release();
            } catch (Throwable ignored) {
            }
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        mView.animate().cancel();
        mView.setAlpha(0f);
        mView.setVisibility(View.GONE);
        if (mPlaying) {
            mPlaying = false;
            if (mOnPlaying != null) mOnPlaying.onPlaying(false);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (mArmed != null) play();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stop();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
