/*
 * Full-screen AirPlay receiver screen. One SurfaceView serves screen mirroring (native
 * MediaCodec output, routed by AirPlayService) and "AirPlay video" (MediaPlayer); overlays
 * show the pairing code, the "now playing" screen for audio-only streams, and short statuses.
 * BACK ends the session for the sender. The screen closes itself when the session ends:
 * "Connecting…" is shown only before the first event; once a session was seen, its end is
 * a plain black screen for a moment.
 *
 * Replaces the Compose MainScreen/VideoControls of jqssun/android-airplay-server (GPL-3.0).
 *
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.z9x.airplay;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class MirrorActivity extends Activity implements AirPlayService.Listener, SurfaceHolder.Callback {
    private static final int MODE_IDLE = 0, MODE_PIN = 1, MODE_MIRROR = 2, MODE_VIDEO = 3, MODE_AUDIO = 4,
            MODE_FAILED = 5, MODE_ENDED = 6, MODE_LOCKED = 7;
    private static final long IDLE_FINISH_MS = 4000;
    private static final long ENDED_FINISH_MS = 1500;
    private static final long TICK_MS = 1000;
    /** Size of the cover on the "now playing" screen (AirPlayService decodes covers for it). */
    static final int COVER_DP = 300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AirPlayService svc;
    private int mode = -1;
    private int densityDpi;
    private boolean sawSession;   // a session state was shown: idle now means "ended"

    private AspectSurfaceView video;
    private View curtain;
    private LinearLayout centerBox, audioBox;
    private ProgressBar spinner, audioProgress;
    private TextView status, pinCaption, pinCode, pinHint, title, artist, album, time;
    private ImageView cover;

    private final Runnable idleFinish = this::finish;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateAudioProgress();
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildViews());
        densityDpi = getResources().getConfiguration().densityDpi;
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        video.getHolder().addCallback(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        svc = AirPlayService.instance();
        if (svc == null) {
            finish();
            return;
        }
        AirPlayService.addListener(this);
        onAirPlayStateChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(tick);
        main.removeCallbacks(idleFinish);
        if (svc != null) {
            AirPlayService.removeListener(this);
            // HOME, another app (e.g. a Google Cast receiver) or screen off: a hidden picture is
            // useless, so end mirroring / video. Music keeps playing in the background.
            if (!isFinishing() && !isChangingConfigurations() && (mode == MODE_MIRROR || mode == MODE_VIDEO)) {
                svc.userStop();
            }
            // a code screen that goes away unpaired is not popped up again for a while
            if (!isChangingConfigurations() && mode == MODE_PIN) svc.pinScreenClosed();
        }
        if (!isChangingConfigurations()) {
            if (svc != null) svc.videoFailed = false;   // the failure message was shown (or skipped)
            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // Lumen OS 1.0.1: the UI resolution (1080p / 2K / 4K) changed under a running session.
        // configChanges keeps this activity and its Surface (the picture only re-measures to the new
        // window); the overlays are sized in px once built, so build them again at the new density.
        if (c.densityDpi == densityDpi || video == null) return;
        densityDpi = c.densityDpi;
        FrameLayout root = (FrameLayout) video.getParent();
        root.removeView(centerBox);
        root.removeView(audioBox);
        buildOverlays(root);
        if (svc != null && mode >= 0) render(svc);
    }

    // ------------------------------------------------------------------ surface

    @Override public void surfaceCreated(SurfaceHolder h) {}

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
        AirPlayService s = AirPlayService.instance();
        if (s != null) s.setDisplaySurface(h.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        AirPlayService s = AirPlayService.instance();
        // only our own Surface; blocks until no producer uses it
        if (s != null) s.releaseDisplaySurface(h.getSurface());
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onAirPlayStateChanged() {
        AirPlayService s = AirPlayService.instance();
        if (s == null || s != svc) {
            finish();
            return;
        }
        int m;
        if (s.videoActive) m = MODE_VIDEO;
        else if (s.mirroring) m = MODE_MIRROR;
        else if (s.pin != null) m = MODE_PIN;
        else if (s.pinLocked()) m = MODE_LOCKED;
        else if (s.audioOnly) m = MODE_AUDIO;
        else if (s.videoFailed) m = MODE_FAILED;
        else m = sawSession ? MODE_ENDED : MODE_IDLE;
        if (m != MODE_IDLE && m != MODE_ENDED) sawSession = true;
        if (m != mode) {
            mode = m;
            if (m == MODE_AUDIO) main.post(tick);
            else main.removeCallbacks(tick);
        }
        render(s);
        main.removeCallbacks(idleFinish);
        if (m == MODE_IDLE || m == MODE_FAILED) main.postDelayed(idleFinish, IDLE_FINISH_MS);
        else if (m == MODE_ENDED) main.postDelayed(idleFinish, ENDED_FINISH_MS);
    }

    private void render(AirPlayService s) {
        boolean picture = mode == MODE_MIRROR || mode == MODE_VIDEO;
        curtain.setVisibility(picture ? View.GONE : View.VISIBLE);
        if (mode == MODE_VIDEO) video.setVideoSize(s.videoW, s.videoH);
        else video.setVideoSize(s.mirrorW, s.mirrorH);

        boolean showPin = mode == MODE_PIN;
        pinCaption.setVisibility(showPin ? View.VISIBLE : View.GONE);
        pinCode.setVisibility(showPin ? View.VISIBLE : View.GONE);
        pinHint.setVisibility(showPin ? View.VISIBLE : View.GONE);
        if (showPin) pinCode.setText(spaced(s.pin));

        boolean busy;
        String msg;
        switch (mode) {
            case MODE_MIRROR:
                busy = !s.firstFrame;
                msg = busy ? getString(R.string.connecting) : null;
                break;
            case MODE_VIDEO: {
                HlsPlayer p = s.player();
                busy = !p.prepared() || p.buffering();
                msg = busy ? getString(R.string.video_loading) : null;
                break;
            }
            case MODE_FAILED:
                busy = false;
                msg = getString(R.string.video_failed);
                break;
            case MODE_IDLE:   // launched, nothing received yet
                busy = true;
                msg = getString(R.string.connecting);
                break;
            case MODE_LOCKED:
                busy = false;
                msg = getString(R.string.pin_locked);
                break;
            default:
                busy = false;
                msg = null;
        }
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setVisibility(msg != null ? View.VISIBLE : View.GONE);
        if (msg != null) status.setText(msg);
        centerBox.setVisibility(showPin || busy || msg != null ? View.VISIBLE : View.GONE);

        boolean audio = mode == MODE_AUDIO;
        audioBox.setVisibility(audio ? View.VISIBLE : View.GONE);
        if (audio) {
            Dmap.Track t = s.track;
            String tt = t != null && !t.title.isEmpty() ? t.title : getString(R.string.audio_unknown);
            title.setText(tt);
            setOrHide(artist, t != null ? t.artist : null);
            setOrHide(album, t != null ? t.album : null);
            if (s.cover != null) {
                cover.setImageBitmap(s.cover);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                cover.setImageResource(R.drawable.ic_airplay);
                cover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }
            updateAudioProgress();
        }
    }

    private void updateAudioProgress() {
        AirPlayService s = svc;
        if (s == null || mode != MODE_AUDIO) return;
        long dur = s.progressDurMs, pos = s.audioPositionMs();
        if (dur <= 0) {
            audioProgress.setVisibility(View.INVISIBLE);
            time.setVisibility(View.INVISIBLE);
            return;
        }
        audioProgress.setVisibility(View.VISIBLE);
        time.setVisibility(View.VISIBLE);
        audioProgress.setMax(1000);
        audioProgress.setProgress((int) Math.min(1000, pos * 1000 / dur));
        time.setText(DateUtils.formatElapsedTime(pos / 1000) + " / " + DateUtils.formatElapsedTime(dur / 1000));
    }

    private static void setOrHide(TextView v, String text) {
        if (text == null || text.isEmpty()) {
            v.setVisibility(View.GONE);
        } else {
            v.setVisibility(View.VISIBLE);
            v.setText(text);
        }
    }

    private static String spaced(String pin) {
        if (pin == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pin.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(pin.charAt(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            e.startTracking();
            return true;
        }
        AirPlayService s = svc;
        if (s == null) return super.onKeyDown(code, e);
        if (mode == MODE_VIDEO) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (e.getRepeatCount() == 0) s.togglePlay(null);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    s.togglePlay(Boolean.TRUE);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    s.togglePlay(Boolean.FALSE);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    s.player().seekBy(10_000);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    s.player().seekBy(-10_000);
                    return true;
                default:
                    break;
            }
        } else if (mode == MODE_AUDIO) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    if (e.getRepeatCount() == 0) s.togglePlay(null);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    s.togglePlay(Boolean.TRUE);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    s.togglePlay(Boolean.FALSE);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (e.getRepeatCount() == 0) s.dacp().next();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (e.getRepeatCount() == 0) s.dacp().previous();
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.isTracking() && !e.isCanceled()) {
                AirPlayService s = svc;
                if (s != null && mode != MODE_FAILED && mode != MODE_IDLE && mode != MODE_ENDED
                        && mode != MODE_LOCKED) {
                    s.userStop();
                }
                finish();
            }
            return true;
        }
        return super.onKeyUp(code, e);
    }

    // ------------------------------------------------------------------ views

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private View buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new AspectSurfaceView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        curtain = new View(this);
        curtain.setBackgroundColor(Color.BLACK);
        root.addView(curtain, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        buildOverlays(root);
        return root;
    }

    /** The code / status box and the "now playing" screen over the picture (sizes in dp / sp). */
    private void buildOverlays(FrameLayout root) {
        final int white = Color.WHITE, dim = 0xB3FFFFFF;

        centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER);
        centerBox.setPadding(dp(32), dp(24), dp(32), dp(24));
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        centerBox.addView(spinner, new LinearLayout.LayoutParams(dp(56), dp(56)));
        status = text(22, white, false);
        status.setPadding(0, dp(16), 0, 0);
        centerBox.addView(status);
        pinCaption = text(26, white, false);
        pinCaption.setText(R.string.pin_title);
        centerBox.addView(pinCaption);
        pinCode = text(110, white, true);
        pinCode.setLetterSpacing(0.15f);
        pinCode.setPadding(0, dp(12), 0, dp(12));
        centerBox.addView(pinCode);
        pinHint = text(18, dim, false);
        pinHint.setText(R.string.pin_back);
        centerBox.addView(pinHint);
        root.addView(centerBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        audioBox = new LinearLayout(this);
        audioBox.setOrientation(LinearLayout.HORIZONTAL);
        audioBox.setGravity(Gravity.CENTER_VERTICAL);
        audioBox.setPadding(dp(64), dp(48), dp(64), dp(48));
        cover = new ImageView(this);
        cover.setBackgroundColor(0xFF1E1E1E);
        cover.setClipToOutline(true);
        audioBox.addView(cover, new LinearLayout.LayoutParams(dp(COVER_DP), dp(COVER_DP)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), 0, 0, 0);
        title = text(34, white, true);
        title.setGravity(Gravity.START);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        artist = text(24, white, false);
        artist.setGravity(Gravity.START);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setPadding(0, dp(8), 0, 0);
        col.addView(artist);
        album = text(20, dim, false);
        album.setGravity(Gravity.START);
        album.setSingleLine(true);
        album.setEllipsize(TextUtils.TruncateAt.END);
        album.setPadding(0, dp(4), 0, 0);
        col.addView(album);
        audioProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pl.topMargin = dp(32);
        col.addView(audioProgress, pl);
        time = text(16, dim, false);
        time.setGravity(Gravity.START);
        time.setPadding(0, dp(8), 0, 0);
        col.addView(time);
        audioBox.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(audioBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }
}
