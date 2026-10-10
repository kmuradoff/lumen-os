/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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
 * shows the banner; everything else goes to TvView -> HdmiSession (forwarded over CEC when a CEC
 * source is present). The SOURCE key never reaches us: it is a global key (GlobalKeyReceiver).
 * Every lifecycle method and callback is crash-shielded (persistent process).
 */
public class PassthroughActivity extends Activity {
    private static final String TAG = "Z9xHdmiViewer";

    /** Input currently on screen (read by HdmiWatcher / picker). Main thread writes. */
    static volatile String sShowingInputId;

    private static final long UNPLUG_HOME_DELAY_MS = 4000;
    /** After (re)start, e.g. wake from standby, HPD may still be low for a few seconds. */
    private static final long START_GRACE_MS = 10000;
    private static final long BANNER_MS = 3000;
    private static final int MAX_RETRIES = 3;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TvInputManager mTim;
    private TvView mTvView;
    private TextView mStatus;
    private TextView mBanner;
    private String mInputId;
    /** Lumen OS 1.0: the input whose state means "source there" (the parent port of a CEC child input). */
    private String mStateFor, mStateId;
    private boolean mStarted;
    private long mStartedAt;
    private boolean mVideoAvailable;
    /** A "source unplugged -> home" countdown is running; its message must stay on screen. */
    private boolean mUnplugPending;
    private int mRetries;
    private int mDensityDpi;

    private final Runnable mHideBanner = Safe.wrap(TAG, "hideBanner", () -> {
        if (mBanner != null) mBanner.setVisibility(View.GONE);
    });

    private final Runnable mUnpluggedGoHome = Safe.wrap(TAG, "unpluggedGoHome", () -> {
        mUnplugPending = false;
        if (mInputId == null || isFinishing() || !mStarted) return;
        if (HdmiInputs.stateOf(this, stateId()) == TvInputManager.INPUT_STATE_CONNECTED) return;
        if (!Prefs.get(this, Prefs.RETURN_HOME)) return;
        Log.i(TAG, "source unplugged -> home");
        HdmiInputs.goHome(this);
        finish();
    });

    private final Runnable mRetune = Safe.wrap(TAG, "retune", () -> {
        if (mStarted && mInputId != null) tune(false);
    });

    private final TvInputManager.TvInputCallback mStateCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            Safe.run(TAG, "onInputStateChanged", () -> {
                if (mStarted && inputId != null && mInputId != null && inputId.equals(stateId())) onSourceState(state);
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
                    showStatus(getString(R.string.status_bad_input));
                }
            });
        }
    };

    private final TvView.TvInputCallback mViewCallback = new TvView.TvInputCallback() {
        @Override
        public void onConnectionFailed(String inputId) {
            Safe.run(TAG, "onConnectionFailed", () -> {
                Log.w(TAG, "onConnectionFailed " + inputId);
                showStatus(getString(R.string.status_failed));
                scheduleRetry();
            });
        }

        @Override
        public void onDisconnected(String inputId) {
            Safe.run(TAG, "onDisconnected", () -> {
                Log.w(TAG, "onDisconnected " + inputId);
                showStatus(getString(R.string.status_connecting));
                scheduleRetry();
            });
        }

        @Override
        public void onVideoAvailable(String inputId) {
            Safe.run(TAG, "onVideoAvailable", () -> {
                mRetries = 0;
                mVideoAvailable = true;
                hideStatus();
            });
        }

        @Override
        public void onVideoUnavailable(String inputId, int reason) {
            Safe.run(TAG, "onVideoUnavailable", () -> {
                mVideoAvailable = false;
                if (mUnplugPending) return; // keep "Источник отключён. Возврат…"
                switch (reason) {
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING:
                        showStatus(getString(R.string.status_connecting));
                        break;
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED:
                        showStatus(getString(R.string.status_no_cable));
                        break;
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE:
                        showStatus(getString(R.string.status_busy));
                        break;
                    default:
                        showStatus(getString(R.string.status_no_signal));
                        break;
                }
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

        mStatus = new TextView(this);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setBackgroundColor(0xB0000000);
        mStatus.setVisibility(View.GONE);
        root.addView(mStatus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        mBanner = new TextView(this);
        mBanner.setTextColor(Color.WHITE);
        mBanner.setBackgroundColor(0xB0000000);
        mBanner.setVisibility(View.GONE);
        root.addView(mBanner, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        sizeOverlays();
        mDensityDpi = getResources().getConfiguration().densityDpi;

        setContentView(root);
        tv.requestFocus();
        mTvView = tv;
    }

    /** dp / sp sizes of the status and the banner (they are px once set). */
    private void sizeOverlays() {
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        mStatus.setPadding(dp(24), dp(16), dp(24), dp(16));
        mBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mBanner.setPadding(dp(20), dp(10), dp(20), dp(10));
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) mBanner.getLayoutParams();
        bl.setMargins(dp(32), dp(24), 0, 0);
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
            tune(true);
        });
    }

    @Override
    protected void onStop() {
        Safe.run(TAG, "onStop", () -> {
            mStarted = false;
            mUnplugPending = false;
            mHandler.removeCallbacksAndMessages(null);
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
        showStatus(getString(R.string.status_connecting));
        Uri uri = TvContract.buildChannelUriForPassthroughInput(id);
        Log.i(TAG, "tune " + id);
        try {
            mTvView.tune(id, uri);
        } catch (Throwable t) {
            Log.e(TAG, "tune failed", t);
            showStatus(getString(R.string.status_failed));
            return;
        }
        Prefs.setString(this, Prefs.LAST_INPUT, id);
        showBanner();
        int state = HdmiInputs.stateOf(this, stateId());
        if (state != TvInputManager.INPUT_STATE_CONNECTED) onSourceState(state);
    }

    private void scheduleRetry() {
        if (!mStarted || mRetries >= MAX_RETRIES) return;
        mRetries++;
        mHandler.removeCallbacks(mRetune);
        mHandler.postDelayed(mRetune, 1500L * mRetries);
    }

    private void onSourceState(int state) {
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            mHandler.removeCallbacks(mUnpluggedGoHome);
            mUnplugPending = false;
            // The session gets the new stream configs from the HAL and reopens the stream itself;
            // the state change and onVideoAvailable can arrive in either order.
            if (mVideoAvailable) {
                hideStatus();
            } else {
                showStatus(getString(R.string.status_connecting));
            }
            return;
        }
        boolean goHome = Prefs.get(this, Prefs.RETURN_HOME);
        showStatus(getString(goHome ? R.string.status_disconnected_home
                : R.string.status_disconnected));
        mHandler.removeCallbacks(mUnpluggedGoHome);
        mUnplugPending = goHome;
        if (goHome) {
            long sinceStart = SystemClock.uptimeMillis() - mStartedAt;
            long delay = UNPLUG_HOME_DELAY_MS + Math.max(0, START_GRACE_MS - sinceStart);
            mHandler.postDelayed(mUnpluggedGoHome, delay);
        }
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

    private void showStatus(String text) {
        if (mStatus == null) return;
        mStatus.setText(text);
        mStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        if (mStatus != null) mStatus.setVisibility(View.GONE);
    }

    private void showBanner() {
        if (mInputId == null || mBanner == null) return;
        String label = HdmiInputs.labelOf(this, mInputId);
        String state = HdmiInputs.stateText(this, HdmiInputs.stateOf(this, stateId()));
        mBanner.setText(label + " · " + state);
        mBanner.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideBanner);
        mHandler.postDelayed(mHideBanner, BANNER_MS);
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
                        // picker only when org.z9x.projector is not installed.
                        if (!HdmiInputs.openSourceOverlay(this)) HdmiInputs.openPicker(this);
                    }
                    return true;
                case KeyEvent.KEYCODE_INFO:
                    if (event.getAction() == KeyEvent.ACTION_UP) showBanner();
                    return true;
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
