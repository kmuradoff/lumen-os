package org.z9x.projector.home;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.projector.R;
import org.z9x.projector.Ui;

/**
 * Lumen OS 1.0 (PLAN C4, home spec section 9): the remote's mic key opens Lumen Home search. Google
 * Assistant (Katniss) is not in the image any more; the speech recognizer is GoogleTTS, used by the
 * search screen itself.
 *
 * <ul>
 * <li>voice (mic DOWN, after setup, display on): {@value #ACTION_VOICE} on org.z9x.home's
 *     VoiceSearchAlias (requires org.z9x.home.permission.MIC, which only this platform-signed app holds),
 *     extra held=true: the search screen starts listening at once while the key is held;</li>
 * <li>mic UP: the explicit broadcast {@value #ACTION_MIC_UP} (receiver guarded by the same permission)
 *     stops listening;</li>
 * <li>no voice (from sleep / lamp standby, Recents' non-long-press ASSIST, the panel): the
 *     GLOBAL_SEARCH entry of the same activity (keyboard).</li>
 * </ul>
 * Missing Lumen Home (or an older one without the alias): voice falls back to GLOBAL_SEARCH; with no
 * search activity at all a short toast says so. Never SearchManager.launchAssist (the ASSIST role is ours
 * now, that would loop into Recents).
 */
public final class HomeSearch {
    private static final String TAG = "Z9xSearch";
    public static final String PKG = "org.z9x.home";
    public static final String ACTION_VOICE = "org.z9x.home.action.VOICE_SEARCH";
    public static final String ACTION_MIC_UP = "org.z9x.home.action.MIC_UP";
    public static final String EXTRA_HELD = "held";
    /** uptimeMillis of the key DOWN, for the search screen's "voice start dt" log. */
    public static final String EXTRA_DOWN_UPTIME = "down_uptime";
    public static final String EXTRA_UP_UPTIME = "up_uptime";
    /**
     * Tap-to-talk (no key held): the search screen starts listening like its on-screen mic. Used for the
     * classic launcher's voice orb (TVLauncher: ASSIST with search_type 1 = voice orb, 2 = keyboard orb).
     */
    public static final String EXTRA_TAP = "tap_voice";

    private HomeSearch() {}

    /** Opens Lumen Home search; voice = push-to-talk. Returns true when an activity was started. Main thread. */
    public static boolean open(Context ctx, boolean voice, String why) {
        return open(ctx, voice, false, why);
    }

    /**
     * Opens Lumen Home search; voice: push-to-talk (tap=false, the mic key is held) or tap-to-talk
     * (tap=true: an on-screen voice button such as the classic launcher's voice orb). Main thread.
     */
    public static boolean open(Context ctx, boolean voice, boolean tap, String why) {
        long t0 = SystemClock.uptimeMillis();
        if (voice) {
            Intent v = new Intent(ACTION_VOICE).setPackage(PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (tap) v.putExtra(EXTRA_HELD, false).putExtra(EXTRA_TAP, true);
            else v.putExtra(EXTRA_HELD, true).putExtra(EXTRA_DOWN_UPTIME, t0);
            if (start(ctx, v)) {
                Log.i(TAG, "voice search (" + why + ") " + (SystemClock.uptimeMillis() - t0) + " ms");
                return true;
            }
            Log.w(TAG, "voice search not available (" + why + "): keyboard search instead");
        }
        Intent k = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).setPackage(PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (start(ctx, k)) {
            Log.i(TAG, "search (" + why + ", no voice)");
            return true;
        }
        Log.w(TAG, "no Lumen Home search activity (" + why + ")");
        Ui.toast(ctx, R.string.search_unavailable);
        return false;
    }

    /** Mic key released: Lumen Home stops listening. Any thread. */
    public static void micUp(Context ctx) {
        try {
            // up_uptime: Lumen Home remembers the release even before its search screen exists (cold start)
            ctx.sendBroadcast(new Intent(ACTION_MIC_UP).setPackage(PKG).putExtra(EXTRA_UP_UPTIME, SystemClock.uptimeMillis()));
        } catch (Throwable t) {
            Log.w(TAG, "MIC_UP: " + t);
        }
    }

    private static boolean start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "start " + i.getAction() + ": " + t);
            return false;
        }
    }
}
