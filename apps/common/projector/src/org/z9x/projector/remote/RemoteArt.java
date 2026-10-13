package org.z9x.projector.remote;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.z9x.projector.R;

/**
 * Lumen OS 1.0: the XGIMI remote illustration of the remote-lost prompt (same drawing as Lumen Setup's
 * remote step). Three stacked vector layers with one viewport: the neutral remote, a warm glow and the
 * lit Back / Home keys. While searching, only the glow layer's view alpha pulses (a RenderNode property:
 * no redraw of the vectors, which are rasterised once); once connected, the lit keys and the glow fade
 * out. The pulse runs only while the view is attached and visible. Main thread.
 */
final class RemoteArt extends FrameLayout {
    private static final long PULSE_MS = 1_100;

    private final ImageView mGlow;
    private final ImageView mKeys;
    private ObjectAnimator mPulse;
    private boolean mConnected;

    RemoteArt(Context c) {
        super(c);
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer(c, R.drawable.remote_xgimi);
        mGlow = layer(c, R.drawable.remote_xgimi_glow);
        mKeys = layer(c, R.drawable.remote_xgimi_keys);
    }

    private ImageView layer(Context c, int res) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return v;
    }

    /** Connected: the keys to hold and the glow fade out, the pulse stops. */
    void setConnected(boolean connected) {
        if (mConnected == connected) return;
        mConnected = connected;
        mKeys.animate().alpha(connected ? 0f : 1f).setDuration(320).start();
        if (connected) mGlow.animate().alpha(0f).setDuration(320).start();
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        update();
    }

    private void update() {
        boolean want = !mConnected && isAttachedToWindow() && isShown();
        if (want && mPulse == null) {
            mGlow.animate().cancel();
            mPulse = ObjectAnimator.ofFloat(mGlow, View.ALPHA, 0.15f, 1f);
            mPulse.setDuration(PULSE_MS);
            mPulse.setRepeatMode(ValueAnimator.REVERSE);
            mPulse.setRepeatCount(ValueAnimator.INFINITE);
            mPulse.setInterpolator(new AccelerateDecelerateInterpolator());
            mPulse.start();
        } else if (!want) {
            stopPulse();
        }
    }

    private void stopPulse() {
        if (mPulse == null) return;
        mPulse.cancel();
        mPulse = null;
        if (!mConnected) mGlow.setAlpha(0.6f);
    }
}
