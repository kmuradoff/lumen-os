/*
 * "AirPlay video" player: plays the URL from /play (UxPlay's local HLS proxy for YouTube, or a
 * direct media URL) with the platform MediaPlayer (NuPlayer; ExoPlayer is not available) and
 * reports position/duration/rate every 250 ms so UxPlay can answer /playback-info.
 * Ported to Java from jqssun/android-airplay-server v0.0.31 renderer/AirPlayVideoPlayer.kt
 * (GPL-3.0), Media3 replaced by MediaPlayer. Readiness semantics kept: the sender holds /play
 * until "ready" (duration known for VOD, playable for live).
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;

/** Main thread only. */
final class HlsPlayer {
    private static final String TAG = "Z9xAirPlay.Hls";
    private static final long REPORT_MS = 250;

    interface Listener {
        /** Snapshot for UxPlay (position/duration in seconds, rate 0 while paused or buffering). */
        void onPlaybackInfo(float pos, float dur, float rate, boolean ready);
        void onVideoSize(int w, int h);
        /** Playback ended (completion or error); the player is already released. */
        void onEnded(boolean error);
        void onStateChanged();
    }

    private final Handler main;
    private final Listener listener;
    private MediaPlayer mp;
    private Surface surface;
    private boolean prepared, buffering;
    private float speed = 1f, appliedSpeed = 1f;
    private boolean wantPlaying = true;
    private int pendingSeekMs = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            report();
            main.postDelayed(this, REPORT_MS);
        }
    };

    HlsPlayer(Handler main, Listener listener) {
        this.main = main;
        this.listener = listener;
    }

    boolean active() { return mp != null; }
    boolean prepared() { return prepared; }
    boolean buffering() { return buffering; }
    boolean playing() { return mp != null && prepared && wantPlaying; }

    void play(String url, float startSec) {
        releaseInternal();
        MediaPlayer p = new MediaPlayer();
        mp = p;
        prepared = false;
        buffering = false;
        speed = appliedSpeed = 1f;
        wantPlaying = true;
        pendingSeekMs = startSec > 0.5f ? Math.round(startSec * 1000f) : -1;
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build());
        p.setScreenOnWhilePlaying(false);
        p.setOnPreparedListener(m -> {
            if (m != mp) return;
            prepared = true;
            Log.i(TAG, "prepared, duration " + safeDuration() + " ms");
            if (pendingSeekMs > 0) seek(pendingSeekMs);
            pendingSeekMs = -1;
            applyPlaying();
            report();
            listener.onStateChanged();
        });
        p.setOnVideoSizeChangedListener((m, w, h) -> {
            if (m == mp && w > 0 && h > 0) listener.onVideoSize(w, h);
        });
        p.setOnInfoListener((m, what, extra) -> {
            if (m != mp) return false;
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false;
            else return false;
            listener.onStateChanged();
            return true;
        });
        p.setOnCompletionListener(m -> {
            if (m != mp) return;
            Log.i(TAG, "completed");
            end(false);
        });
        p.setOnErrorListener((m, what, extra) -> {
            if (m != mp) return true;
            Log.w(TAG, "error " + what + "/" + extra);
            end(true);
            return true;
        });
        try {
            p.setDataSource(url);
            if (surface != null) p.setSurface(surface);
            p.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + url + ": " + e);
            main.post(() -> {
                if (mp == p) end(true);
            });
            return;
        }
        listener.onPlaybackInfo(startSec, 0f, 0f, false);
        main.removeCallbacks(tick);
        main.postDelayed(tick, REPORT_MS);
        listener.onStateChanged();
    }

    void setSurface(Surface s) {
        surface = s;
        if (mp != null) {
            try {
                mp.setSurface(s);
            } catch (Exception e) {
                Log.w(TAG, "setSurface: " + e);
            }
        }
    }

    /** Sender seek (/scrub). */
    void scrub(float sec) {
        int ms = Math.max(0, Math.round(sec * 1000f));
        if (!prepared) {
            pendingSeekMs = ms;
            return;
        }
        seek(ms);
    }

    /** Sender rate (/rate): 0 = pause, otherwise play at that speed. */
    void setRate(float rate) {
        if (rate <= 0f) {
            wantPlaying = false;
        } else {
            wantPlaying = true;
            speed = Math.max(0.25f, Math.min(rate, 2f));
        }
        applyPlaying();
        listener.onStateChanged();
    }

    /** Local remote control: the sender follows from its next /playback-info poll. */
    void togglePause() {
        if (mp == null) return;
        wantPlaying = !wantPlaying;
        applyPlaying();
        report();
        listener.onStateChanged();
    }

    void seekBy(int deltaMs) {
        if (mp == null || !prepared) return;
        try {
            int pos = mp.getCurrentPosition() + deltaMs;
            int dur = safeDuration();
            if (dur > 0) pos = Math.min(pos, dur - 1000);
            seek(Math.max(0, pos));
        } catch (IllegalStateException ignored) {
        }
        report();
    }

    /** Stops and releases; the "finished" sentinel is reported by the caller. */
    void release() {
        releaseInternal();
    }

    private void end(boolean error) {
        releaseInternal();
        listener.onEnded(error);
    }

    private void releaseInternal() {
        main.removeCallbacks(tick);
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        buffering = false;
        if (p != null) {
            try {
                p.reset();
            } catch (Exception ignored) {
            }
            p.release();   // synchronous: the Surface is free for MediaCodec afterwards
        }
    }

    private void seek(int ms) {
        try {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST_SYNC);
        } catch (Exception e) {
            Log.w(TAG, "seek: " + e);
        }
    }

    private void applyPlaying() {
        if (mp == null || !prepared) return;
        try {
            if (wantPlaying) {
                if (Math.abs(speed - appliedSpeed) > 0.01f) {
                    mp.setPlaybackParams(new PlaybackParams().setSpeed(speed));   // also starts playback
                    appliedSpeed = speed;
                }
                if (!mp.isPlaying()) mp.start();
            } else if (mp.isPlaying()) {
                mp.pause();
            }
        } catch (Exception e) {
            Log.w(TAG, "play/pause: " + e);
        }
    }

    private int safeDuration() {
        try {
            return mp == null ? -1 : mp.getDuration();
        } catch (IllegalStateException e) {
            return -1;
        }
    }

    private void report() {
        if (mp == null) return;
        // before prepare, keep reporting the requested start so the sender's timeline holds
        float pos = pendingSeekMs > 0 ? pendingSeekMs / 1000f : 0f, dur = 0f, rate = 0f;
        try {
            if (prepared) {
                pos = mp.getCurrentPosition() / 1000f;
                int d = mp.getDuration();
                dur = d > 0 ? d / 1000f : 0f;   // 0 = live / unknown
                rate = (mp.isPlaying() && !buffering) ? speed : 0f;
            }
        } catch (IllegalStateException ignored) {
        }
        listener.onPlaybackInfo(pos, dur, rate, prepared);
    }

    /** Position and duration for the on-screen progress (ms; duration <= 0 = live). */
    long positionMs() {
        try {
            return mp != null && prepared ? mp.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    long durationMs() {
        return prepared ? safeDuration() : 0;
    }
}
