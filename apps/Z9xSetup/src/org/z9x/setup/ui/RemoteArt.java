package org.z9x.setup.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.z9x.setup.R;

/**
 * The XGIMI remote (Lumen OS 1.0 remote step; the projector's remote-lost prompt draws the same):
 * stacked vector layers with one viewport, res/drawable/remote_xgimi*.xml: the neutral remote, a warm
 * glow and the lit Back / Home keys (#F2B26B), and (1.0.1) the lit OK key with its halo. While
 * searching only the glow layer's view alpha pulses (a RenderNode property: the vectors are rasterised
 * once, nothing is redrawn per frame); when connected the lit keys and the glow fade out, and while
 * the step waits for OK ({@link #setAwaitOk}) the OK layer pulses the same way. The pulse runs only
 * while attached and visible.
 */
public class RemoteArt extends FrameLayout {
    /** The warm accent of the lit keys (text that refers to them uses it too). */
    public static final int LIT = 0xFFF2B26B;
    private static final long PULSE_MS = 1_100;

    private final ImageView mGlow;
    private final ImageView mKeys;
    private final ImageView mOk;
    private ObjectAnimator mPulse;
    private View mPulsing;
    private boolean mConnected;
    private boolean mAwaitOk;

    public RemoteArt(Context c) {
        super(c);
        setClipChildren(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer(c, R.drawable.remote_xgimi);
        mGlow = layer(c, R.drawable.remote_xgimi_glow);
        mKeys = layer(c, R.drawable.remote_xgimi_keys);
        mOk = layer(c, R.drawable.remote_xgimi_ok);
        mOk.setAlpha(0f);
    }

    private ImageView layer(Context c, int res) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return v;
    }

    public void setConnected(boolean c) {
        if (mConnected == c) return;
        mConnected = c;
        mKeys.animate().alpha(c ? 0f : 1f).setDuration(320).start();
        if (c) mGlow.animate().alpha(0f).setDuration(320).start();
        update();
    }

    /** Light the OK key and pulse it (the step waits for OK on the remote); false fades it out. */
    public void setAwaitOk(boolean a) {
        if (mAwaitOk == a) return;
        mAwaitOk = a;
        if (!a) mOk.animate().alpha(0f).setDuration(320).start();
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
        View target = !mConnected ? mGlow : mAwaitOk ? mOk : null;
        boolean want = target != null && isAttachedToWindow() && isShown();
        if (want && mPulsing != target) stopPulse();
        if (want && mPulse == null) {
            target.animate().cancel();
            mPulse = ObjectAnimator.ofFloat(target, View.ALPHA, target == mOk ? 0.45f : 0.15f, 1f);
            mPulse.setDuration(PULSE_MS);
            mPulse.setRepeatMode(ValueAnimator.REVERSE);
            mPulse.setRepeatCount(ValueAnimator.INFINITE);
            mPulse.setInterpolator(new AccelerateDecelerateInterpolator());
            mPulsing = target;
            mPulse.start();
        } else if (!want) {
            stopPulse();
        }
    }

    private void stopPulse() {
        if (mPulse == null) return;
        mPulse.cancel();
        mPulse = null;
        if (mPulsing == mGlow && !mConnected) mGlow.setAlpha(0.6f);
        if (mPulsing == mOk && mAwaitOk) mOk.setAlpha(1f);   // lit, just not pulsing (detached / hidden)
        mPulsing = null;
    }
}
