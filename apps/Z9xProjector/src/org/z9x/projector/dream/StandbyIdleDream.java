package org.z9x.projector.dream;

import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.View;

import org.z9x.projector.SafeHandler;
import org.z9x.projector.power.StandbyController;

/**
 * MODULE "screensaver" (v6.5): the blank "dream" that is active while the user has the screensaver
 * turned off ({@link IdleOwner}). PowerManagerService starts it at the idle time instead of putting
 * Android to sleep; it shows black and hands over to the lamp-only standby at once
 * ({@link StandbyController#enter}, which ends this dream while holding its screen wakelock, so the
 * display stays on). If standby cannot start, the dream finishes and PowerManagerService's own sleep
 * follows (PowerPolicy's sleep path).
 *
 * Declared without the DreamService intent-filter, so TvSettings does not list it; DreamManagerService
 * binds it by component (validateDream checks only the BIND_DREAM_SERVICE permission).
 */
public final class StandbyIdleDream extends DreamService {
    private static final String TAG = "Z9xIdle";
    private static final long ENTER_DELAY_MS = 200;

    private final SafeHandler mH = new SafeHandler(Looper.getMainLooper(), "idle-dream");
    private boolean mStopped;

    private final Runnable mEnter = () -> {
        if (mStopped) return;
        boolean ok = false;
        try {
            ok = StandbyController.enter(getApplicationContext(), "idle time (screensaver off)");
        } catch (Throwable t) {
            Log.e(TAG, "standby", t);
        }
        if (!ok) {
            Log.w(TAG, "standby not possible: ending the blank dream (Android sleep follows)");
            try { finish(); } catch (Throwable t) { Log.w(TAG, "finish: " + t); }
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            setInteractive(false);
            setFullscreen(true);
            setScreenBright(true);
            if (getWindow() != null) getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000));
            View v = new View(this);
            v.setBackgroundColor(0xFF000000);
            setContentView(v);
        } catch (Throwable t) {
            Log.e(TAG, "attach", t);
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mStopped = false;
        Log.i(TAG, "blank standby dream started");
        mH.postDelayed(mEnter, ENTER_DELAY_MS);
    }

    @Override
    public void onDreamingStopped() {
        mStopped = true;
        mH.removeCallbacks(mEnter);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        mStopped = true;
        mH.removeCallbacks(mEnter);
        super.onDetachedFromWindow();
    }
}
