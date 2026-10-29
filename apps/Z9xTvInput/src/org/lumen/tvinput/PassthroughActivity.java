/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.lumen.tvinput;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;

/**
 * Full-screen HDMI viewer. Handles ACTION_VIEW content://android.media.tv/passthrough/&lt;inputId&gt;
 * (what the Google TV launcher Inputs panel fires) and our own explicit intents.
 *
 * <p>The TvView session is created in onStart and released in onStop (TvView.reset ->
 * HdmiSession.onRelease -> setSurface(null) + releaseTvInputHardware), so the HAL stream and the
 * HDMI audio patch only exist while the viewer is visible.
 *
 * <p>Keys: BACK leaves; MENU opens the source list; INFO (or OK when no CEC device consumes it)
 * shows the banner; OK on an asleep CEC source wakes it (deviceSelect, CEC control on); everything else goes
 * to TvView -> HdmiSession (forwarded over CEC when a CEC source is present). The SOURCE key never reaches
 * us: it is a global key (GlobalKeyReceiver).
 *
 * <p>Banner (top start, BrandCard): the CEC device's brand (Brands) or name large, with our glyph for its kind on
 * an accent tile, and its port small ("HDMI 1"); without a device the port and its state. Shown on tune, on
 * INFO / OK, when the picture first arrives after a tune or after the source was gone, and when the device's
 * name or brand becomes known while it is on screen (its child input is published again:
 * onTvInputInfoUpdated). It stays {@value #BANNER_MS} ms, then one {@value #BANNER_FADE_MS} ms fade.
 *
 * <p>State card (middle, BrandCard) while there is no picture, from PortState: asleep ("PlayStation is asleep",
 * "Press OK to turn it on"; no return to the home screen), no signal, device not connected (the return to the
 * home screen after {@value #UNPLUG_HOME_DELAY_MS} ms when that setting is on), else connecting. Refreshed on
 * every PortState change (port and child input states, CEC device updates, session video), on the TvView's
 * video callbacks and on tune. Plain views: no blur, no shadow, no animation while shown.
 *
 * <p>Every lifecycle method and callback is crash-shielded (persistent process).
 */
public class PassthroughActivity extends Activity {
    private static final String TAG = "Z9xHdmiViewer";

    /** Input currently on screen (read by HdmiWatcher / picker). Main thread writes. */
    static volatile String sShowingInputId;

    private static final long UNPLUG_HOME_DELAY_MS = 4000;
    /** After (re)start, e.g. wake from standby, HPD may still be low for a few seconds. */
    private static final long START_GRACE_MS = 10000;
    private static final long BANNER_MS = 3000;
    private static final long BANNER_FADE_MS = 400;
    /** "Turning on…" after OK woke a device, until a picture or this long. */
    private static final long WAKE_MS = 15000;
    private static final int MAX_RETRIES = 3;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TvInputManager mTim;
    private TvView mTvView;
    private BrandCard mStatus;
    private BrandCard mBanner;
    /** Title, kind and accent the banner last showed: a device update shows it again only when they change. */
    private String mBannerKey;
    /** Show the banner again when the picture arrives (set by a tune and when the source was gone). */
    private boolean mBannerOnSignal;
    private String mInputId;
    /** Lumen OS 1.0: the input whose state means "source there" (the parent port of a CEC child input). */
    private String mStateFor, mStateId;
    private boolean mStarted;
    private long mStartedAt;
    private boolean mVideoAvailable;
    /** Session error to show instead of the port state (string id; 0 = none): failed, busy, unknown input. */
    private int mError;
    /** A "source unplugged -> home" countdown is running. */
    private boolean mUnplugPending;
    /** uptimeMillis of the last OK wake; 0 = none. */
    private long mWakeAt;
    /** The port as last shown (OK wakes its device only while it is ASLEEP). */
    private PortState.Snapshot mSnap;
    private int mRetries;
    private int mDensityDpi;

    private final Runnable mBannerGone = Safe.wrap(TAG, "bannerGone", () -> {
        if (mBanner != null) mBanner.setVisibility(View.GONE);
    });

    /** One short fade, then GONE (a new showBanner cancels the fade before its end action runs). */
    private final Runnable mHideBanner = Safe.wrap(TAG, "hideBanner", () -> {
        if (mBanner == null || mBanner.getVisibility() != View.VISIBLE) return;
        mBanner.animate().alpha(0f).setDuration(BANNER_FADE_MS).withLayer().withEndAction(mBannerGone).start();
    });

    private final Runnable mUnpluggedGoHome = Safe.wrap(TAG, "unpluggedGoHome", () -> {
        mUnplugPending = false;
        if (mInputId == null || isFinishing() || !mStarted) return;
        if (PortState.of(this, mInputId).state != PortState.DISCONNECTED) return;
        if (!Prefs.get(this, Prefs.RETURN_HOME)) return;
        Log.i(TAG, "source unplugged -> home");
        HdmiInputs.goHome(this);
        finish();
    });

    private final Runnable mRetune = Safe.wrap(TAG, "retune", () -> {
        if (mStarted && mInputId != null) tune(false);
    });

    /** PortState changed (also the end of the "Turning on…" time). */
    private final Runnable mRefresh = Safe.wrap(TAG, "refresh", this::refreshState);

    private final TvInputManager.TvInputCallback mStateCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            Safe.run(TAG, "onInputStateChanged", () -> {
                if (mStarted && inputId != null && mInputId != null && inputId.equals(stateId())) refreshState();
            });
        }

        @Override
        public void onInputRemoved(String inputId) {
            Safe.run(TAG, "onInputRemoved", () -> {
                if (inputId != null && inputId.equals(mInputId)) {
                    String port = stateId();
                    if (mStarted && port != null && !port.equals(inputId) && HdmiInputs.isOurPortInput(PassthroughActivity.this, port)) {
                        // Lumen OS 1.0: the CEC device of this child input left the bus: keep the picture
                        // on its HDMI port input
                        Log.i(TAG, "CEC child input " + inputId + " removed: showing " + port);
                        mInputId = port;
                        tune(false);
                        return;
                    }
                    mError = R.string.status_bad_input;
                    refreshState();
                }
            });
        }

        @Override
        public void onInputAdded(String inputId) {
            Safe.run(TAG, "onInputAdded", () -> onInputInfo(HdmiInputs.info(PassthroughActivity.this, inputId)));
        }

        @Override
        public void onTvInputInfoUpdated(TvInputInfo info) {
            Safe.run(TAG, "onTvInputInfoUpdated", () -> onInputInfo(info));
        }
    };

    private final TvView.TvInputCallback mViewCallback = new TvView.TvInputCallback() {
        @Override
        public void onConnectionFailed(String inputId) {
            Safe.run(TAG, "onConnectionFailed", () -> {
                Log.w(TAG, "onConnectionFailed " + inputId);
                mError = R.string.status_failed;
                refreshState();
                scheduleRetry();
            });
        }

        @Override
        public void onDisconnected(String inputId) {
            Safe.run(TAG, "onDisconnected", () -> {
                Log.w(TAG, "onDisconnected " + inputId);
                mVideoAvailable = false;
                mError = 0;
                refreshState();
                scheduleRetry();
            });
        }

        @Override
        public void onVideoAvailable(String inputId) {
            Safe.run(TAG, "onVideoAvailable", () -> {
                mRetries = 0;
                mVideoAvailable = true;
                mError = 0;
                mWakeAt = 0;
                refreshState();
                if (mBannerOnSignal) {
                    mBannerOnSignal = false;
                    showBanner(true);
                }
            });
        }

        @Override
        public void onVideoUnavailable(String inputId, int reason) {
            Safe.run(TAG, "onVideoUnavailable", () -> {
                mVideoAvailable = false;
                mError = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE
                        ? R.string.status_busy : 0;
                refreshState();
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mTim = HdmiInputs.tim(this);
            buildUi();
            mInputId = parseInputId(getIntent());
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed", t);
            mInputId = null;
        }
        if (mInputId == null || mTvView == null) {
            Log.w(TAG, "no usable input in " + getIntent());
            try {
                HdmiInputs.notify(this, getString(R.string.status_bad_input));
            } catch (Throwable ignored) {
            }
            finish();
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        TvView tv = new TvView(this);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setCallback(mViewCallback);
        tv.setOnUnhandledInputEventListener(this::onUnhandledInput);
        root.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mStatus = new BrandCard(this, 28);
        root.addView(mStatus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        mBanner = new BrandCard(this, 26);
        root.addView(mBanner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        sizeOverlays();
        mDensityDpi = getResources().getConfiguration().densityDpi;

        setContentView(root);
        tv.requestFocus();
        mTvView = tv;
    }

    /** dp / sp sizes of the state card and the banner (they are px once set). */
    private void sizeOverlays() {
        mStatus.size();
        mBanner.size();
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) mBanner.getLayoutParams();
        bl.setMargins(0, dp(24), 0, 0);
        bl.setMarginStart(dp(32));
        mBanner.setLayoutParams(bl);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Lumen OS 1.0.1: the UI resolution (1080p / 2K / 4K) changed under the viewer (configChanges
        // keeps it and its stream; the TvView fills the window at any size): size the overlays again.
        Safe.run(TAG, "onConfigurationChanged", () -> {
            if (mStatus == null || mBanner == null || newConfig.densityDpi == mDensityDpi) return;
            mDensityDpi = newConfig.densityDpi;
            sizeOverlays();
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Safe.run(TAG, "onNewIntent", () -> {
            setIntent(intent);
            String id = parseInputId(intent);
            if (id == null) return;
            if (id.equals(mInputId)) {
                showBanner();
                return;
            }
            mInputId = id;
            if (mStarted) tune(true);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Safe.run(TAG, "onStart", () -> {
            mStarted = true;
            mStartedAt = SystemClock.uptimeMillis();
            if (mInputId == null || mTvView == null) return;
            if (mTim != null) {
                try {
                    mTim.registerCallback(mStateCallback, mHandler);
                } catch (Throwable t) {
                    Log.w(TAG, "registerCallback failed: " + t);
                }
            }
            PortState.addListener(mRefresh);
            tune(true);
        });
    }

    @Override
    protected void onStop() {
        Safe.run(TAG, "onStop", () -> {
            mStarted = false;
            mUnplugPending = false;
            mHandler.removeCallbacksAndMessages(null);
            PortState.removeListener(mRefresh);
            if (mBanner != null) {
                mBanner.animate().cancel();
                mBanner.setVisibility(View.GONE);
            }
            if (mTim != null) {
                try {
                    mTim.unregisterCallback(mStateCallback);
                } catch (Throwable ignored) {
                }
            }
            // Releases our TIS session -> HdmiSession.onRelease -> setSurface(null) +
            // releaseTvInputHardware (closes the HAL stream and the HDMI-in audio patch).
            if (mTvView != null) {
                try {
                    mTvView.reset();
                } catch (Throwable t) {
                    Log.w(TAG, "reset failed: " + t);
                }
            }
            if (mInputId != null && mInputId.equals(sShowingInputId)) sShowingInputId = null;
        });
        super.onStop();
    }

    private void tune(boolean userVisibleSwitch) {
        String id = mInputId;
        if (id == null || mTvView == null) return;
        sShowingInputId = id;
        mHandler.removeCallbacks(mUnpluggedGoHome);
        mUnplugPending = false;
        mHandler.removeCallbacks(mRetune);
        if (userVisibleSwitch) mRetries = 0;
        mVideoAvailable = false;
        mError = 0;
        mBannerOnSignal = true;
        Uri uri = TvContract.buildChannelUriForPassthroughInput(id);
        Log.i(TAG, "tune " + id);
        try {
            mTvView.tune(id, uri);
        } catch (Throwable t) {
            Log.e(TAG, "tune failed", t);
            mError = R.string.status_failed;
            refreshState();
            return;
        }
        Prefs.setString(this, Prefs.LAST_INPUT, id);
        showBanner();
        refreshState();
    }

    private void scheduleRetry() {
        if (!mStarted || mRetries >= MAX_RETRIES) return;
        mRetries++;
        mHandler.removeCallbacks(mRetune);
        mHandler.postDelayed(mRetune, 1500L * mRetries);
    }

    /**
     * The state card from the picture, a session error and PortState (main thread; binder calls). Idempotent:
     * BrandCard only changes when its content does.
     */
    private void refreshState() {
        if (!mStarted || mInputId == null || mStatus == null) return;
        if (mVideoAvailable) {
            mSnap = null;
            cancelGoHome();
            mStatus.setVisibility(View.GONE);
            return;
        }
        PortState.Snapshot s = PortState.of(this, mInputId);
        mSnap = s;
        String port = s.portLabel != null ? s.portLabel : HdmiInputs.labelOf(this, mInputId);
        String where = s.title != null ? s.title + " · " + port : port;
        int kind = s.brand != null ? s.brand.kind : Brands.KIND_OTHER;
        int accent = s.brand != null ? s.brand.accent : Brands.ACCENT_NEUTRAL;
        boolean waking = mWakeAt != 0 && SystemClock.uptimeMillis() - mWakeAt < WAKE_MS;
        if (s.state != PortState.DISCONNECTED) cancelGoHome();
        if (s.state == PortState.ASLEEP || s.state == PortState.DISCONNECTED) mBannerOnSignal = true;
        String title;
        String line;
        if (mError != 0) {
            title = getString(mError);
            line = where;
        } else if (waking && s.state != PortState.DISCONNECTED) {
            title = s.title != null ? s.title : port;
            line = getString(R.string.state_waking);
        } else if (s.state == PortState.ASLEEP) {
            title = s.title != null ? getString(R.string.state_asleep, s.title) : getString(R.string.state_asleep_device);
            line = canWake() ? getString(R.string.state_press_ok_wake) : port;
        } else if (s.state == PortState.NO_SIGNAL) {
            title = getString(R.string.status_no_signal);
            line = where;
        } else if (s.state == PortState.DISCONNECTED) {
            title = getString(R.string.status_device_disconnected);
            line = scheduleGoHome() ? port + " · " + getString(R.string.status_returning_home) : port;
            kind = Brands.KIND_OTHER;
            accent = Brands.ACCENT_NEUTRAL;
        } else {
            title = getString(R.string.status_connecting);
            line = where;
        }
        mStatus.set(title, line, kind, accent);
        mStatus.setVisibility(View.VISIBLE);
    }

    /** Starts the "unplugged -> home" countdown once (setting on); true while it runs. */
    private boolean scheduleGoHome() {
        if (!Prefs.get(this, Prefs.RETURN_HOME)) return false;
        if (!mUnplugPending) {
            mUnplugPending = true;
            long sinceStart = SystemClock.uptimeMillis() - mStartedAt;
            mHandler.postDelayed(mUnpluggedGoHome, UNPLUG_HOME_DELAY_MS + Math.max(0, START_GRACE_MS - sinceStart));
        }
        return true;
    }

    private void cancelGoHome() {
        mHandler.removeCallbacks(mUnpluggedGoHome);
        mUnplugPending = false;
    }

    /** OK may wake the asleep source: one is listed and CEC control is on. */
    private boolean canWake() {
        return mSnap != null && mSnap.device != null && Prefs.get(this, Prefs.CEC_CONTROL);
    }

    /**
     * OK on an asleep source: HdmiTvClient.deviceSelect (the framework asks its power status, wakes it if it is in
     * standby and routes to it; falls back to portSelect), as a tune of its CEC child input does.
     */
    private void wake() {
        final PortState.Snapshot s = mSnap;
        if (s == null || s.device == null) return;
        final int deviceId = s.device.getId();
        final int port = s.port;
        final Context app = getApplicationContext();
        Log.i(TAG, "OK: wake CEC device " + deviceId + " on HDMI " + port);
        HdmiInputService.hwHandler().post(Safe.wrap(TAG, "wake",
                () -> CecHelper.deviceSelect(app, deviceId, port)));
        mWakeAt = SystemClock.uptimeMillis();
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, WAKE_MS);
        refreshState();
    }

    /** Main thread: the port input of a CEC child input, else mInputId itself (cached per input). */
    private String stateId() {
        String id = mInputId;
        if (id == null) return null;
        if (!id.equals(mStateFor)) {
            mStateFor = id;
            mStateId = HdmiInputs.stateInputId(this, id);
        }
        return mStateId;
    }

    private void showBanner() {
        showBanner(true);
    }

    /**
     * Fills and shows the banner for the input on screen. {@code always} false (a device or input info change):
     * only when its title, kind or accent differ from what it last showed.
     */
    private void showBanner(boolean always) {
        if (mInputId == null || mBanner == null) return;
        PortState.Snapshot s = PortState.of(this, mInputId);
        String port = s.portLabel != null ? s.portLabel : HdmiInputs.labelOf(this, mInputId);
        String word = getString(s.state == PortState.ON || s.state == PortState.NO_SIGNAL ? R.string.state_connected
                : s.state == PortState.ASLEEP ? R.string.state_standby : R.string.state_disconnected);
        String title = s.title;
        String line;
        if (title == null) {
            title = port;
            line = word;
        } else {
            line = s.state == PortState.ON ? port : port + " · " + word;
        }
        int kind = s.brand != null ? s.brand.kind : Brands.KIND_OTHER;
        int accent = s.brand != null ? s.brand.accent : Brands.ACCENT_NEUTRAL;
        String key = title + "|" + kind + "|" + accent;
        if (!always && key.equals(mBannerKey)) return;
        mBannerKey = key;
        mBanner.set(title, line, kind, accent);
        mBanner.animate().cancel();
        mBanner.setAlpha(1f);
        mBanner.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideBanner);
        mHandler.postDelayed(mHideBanner, BANNER_MS);
    }

    /** An input was added or its TvInputInfo republished: the input on screen, or a CEC child of its port. */
    private void onInputInfo(TvInputInfo info) {
        if (!mStarted || info == null || mInputId == null) return;
        if (info.getId().equals(mInputId)
                || (info.getParentId() != null && info.getParentId().equals(stateId()))) {
            showBanner(false);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        try {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) finish();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_TV_INPUT:
                    if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                        // v6.1: the same XGIMI-style Source overlay as the Source key; our own
                        // picker only when org.lumen.projector is not installed.
                        if (!HdmiInputs.openSourceOverlay(this)) HdmiInputs.openPicker(this);
                    }
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    if (event.getAction() == KeyEvent.ACTION_UP) showBanner();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    // the state card says "Press OK to turn it on": OK is ours, not the session's
                    if (!mVideoAvailable && mError == 0 && mSnap != null && mSnap.state == PortState.ASLEEP && canWake()) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) wake();
                        return true;
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "dispatchKeyEvent failed: " + t);
        }
        return super.dispatchKeyEvent(event);
    }

    /** Keys the session did not consume (no CEC device / CEC control off). */
    private boolean onUnhandledInput(InputEvent e) {
        try {
            if (e instanceof KeyEvent) {
                KeyEvent k = (KeyEvent) e;
                if (k.getAction() == KeyEvent.ACTION_UP
                        && (k.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                        || k.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    showBanner();
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onUnhandledInput failed: " + t);
        }
        return false;
    }

    /** content://android.media.tv/passthrough/&lt;inputId&gt; -> inputId (only inputs the framework knows). */
    private String parseInputId(Intent intent) {
        if (intent == null) return null;
        Uri uri = intent.getData();
        String id = null;
        if (uri != null) {
            try {
                if (TvContract.isChannelUriForPassthroughInput(uri)) {
                    List<String> seg = uri.getPathSegments();
                    if (seg != null && seg.size() >= 2) id = seg.get(1);
                }
            } catch (Throwable ignored) {
            }
        }
        if (TextUtils.isEmpty(id)) return null;
        if (!HdmiInputs.isOurPortInput(this, id)) {
            // Accept any passthrough input the framework knows (e.g. a future CEC child input);
            // reject garbage so we never tune an unknown id.
            if (HdmiInputs.info(this, id) == null) return null;
        }
        return id;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
