package org.z9x.projector.power;

import android.app.DreamManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

import java.util.Date;

/**
 * MODULE "power" (v6.2): the sleep timer of the power menu (15 / 30 min / custom).
 *
 * The projector is awake while the timer runs, so a main-thread uptime Handler is enough (no
 * alarm, no wakelock). Any SCREEN_OFF drops the timer (the user turned the projector off).
 *  - T - 60 s: one Notify card ("Turning off in 1 minute", hold power to change).
 *  - T: {@link #FADE_MS} fade-out: the curtain fades the picture to black, STREAM_MUSIC steps down
 *    to index 1 (never 0: Z9xAudioFixer notes that a zero index latches a stream mute), the lamp
 *    steps down to level 1 (TimerLamp, IGmpf 176; skipped while a screensaver runs, because the
 *    dream module dims the lamp itself). Any key during the fade keeps watching: a small focusable
 *    key catcher window takes the D-pad / OK / BACK keys, global keys come through PowerUi.
 *    Volume keys and HOME never reach a window on this TV build (PhoneWindowManager routes volume
 *    directly to audio, HOME is handled before dispatch), so while fading a receiver watches
 *    VOLUME_CHANGED_ACTION (a STREAM_MUSIC change that is not one of our own steps) and
 *    ACTION_CLOSE_SYSTEM_DIALOGS reason "homekey"; both abort the fade too.
 *  - end of the fade: MEDIA_PAUSE to the active session, then the normal sleep (PowerKey).
 *  - The volume saved before the fade is restored at the next SCREEN_ON, or after a cold boot
 *    (pref "timer_vol"); the lamp level is restored by PowerPolicy's lamp-off task before the
 *    lamp goes off (TimerLamp.restoreOnHalThread), so the next wake has the user's brightness.
 * Main thread only, except {@link #restoreVolumeIfNeeded} (any thread).
 */
public final class SleepTimer {
    private static final String TAG = "Z9xSleepTimer";
    private static final String PREFS = "z9x_power";
    private static final String KEY_VOL = "timer_vol";
    private static final String KEY_CUSTOM = "timer_custom_min";
    private static final long WARN_BEFORE_MS = 60_000;
    /** Task: sound and light fade over ~20 s. */
    static final long FADE_MS = 20_000;
    private static final int VOL_STEPS = 20;
    public static final int CUSTOM_MIN = 5;
    public static final int CUSTOM_MAX = 240;
    /**
     * First value of the Custom row. v6.2b: not 60, so the menu never shows a "1 h" choice next
     * to the presets; the user's choices are exactly 15 min, 30 min and Custom (5..240 min).
     */
    public static final int CUSTOM_DEFAULT = 45;

    private static long sEndAt = -1;                     // uptimeMillis, -1 = off
    private static boolean sFading;
    /** The final goToSleep was sent (end of the fade): a late key no longer aborts. */
    private static boolean sSleepSent;
    private static final Runnable sWarn = SleepTimer::warn;
    private static final Runnable sExpire = SleepTimer::expire;
    private static Context sApp;
    private static SafeHandler sWorker;
    private static View sCatcher;
    /** Fade steps (volume, end) posted on the main thread; removed together on abort. */
    private static final Object FADE_TOKEN = new Object();
    /** Hidden AudioManager constants (stable strings since API 1x). */
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOL_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final String EXTRA_VOL_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    private static final String EXTRA_VOL_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    /** The last few (from, to) volume changes the fade itself made: their broadcasts are ours. */
    private static final int[][] sOwnVol = new int[4][];
    private static int sOwnVolNext;
    private static BroadcastReceiver sWatch;

    private SleepTimer() {}

    static void install(Context ctx, SafeHandler worker) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        sWorker = worker;
        // A volume saved by a fade that ended in a cold boot (STR resume cold-boots today):
        // give it back once the boot is complete (BOOT_COMPLETED is not re-sent to a restarted
        // process, so poll the property; a process restart later in the day restores at once).
        waitBootThenRestore(0);
    }

    private static void waitBootThenRestore(int tries) {
        sWorker.postDelayed(() -> {
            boolean booted = "1".equals(SystemProperties.get("sys.boot_completed", ""));
            if (!booted && tries < 60) {
                waitBootThenRestore(tries + 1);
                return;
            }
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            if (pm != null && pm.isInteractive()) restoreVolumeIfNeeded(sApp);
        }, tries == 0 ? 3_000 : 5_000);
    }

    // ================================================================== API (main thread)

    /** Minutes left (rounded up), 0 when no timer runs. */
    public static int minutesLeft() {
        if (sEndAt < 0) return 0;
        long ms = sEndAt - SystemClock.uptimeMillis();
        return ms <= 0 ? 0 : (int) ((ms + 59_999) / 60_000);
    }

    public static boolean isRunning() { return sEndAt >= 0 || sFading; }

    static boolean isFading() { return sFading; }

    /** Wall-clock time at which the timer ends, formatted per the user's 12/24 h setting. */
    static String endClock(Context c) {
        if (sEndAt < 0) return "";
        long wall = System.currentTimeMillis() + (sEndAt - SystemClock.uptimeMillis());
        return DateFormat.getTimeFormat(c).format(new Date(wall));
    }

    public static void start(Context ctx, int minutes) {
        sApp = ctx.getApplicationContext();
        minutes = Math.max(1, Math.min(CUSTOM_MAX, minutes));
        if (sFading) abortFade(sApp, null);
        cancelCallbacks();
        long dur = minutes * 60_000L;
        sEndAt = SystemClock.uptimeMillis() + dur;
        if (dur > WARN_BEFORE_MS) Ui.main().postDelayed(sWarn, dur - WARN_BEFORE_MS);
        Ui.main().postDelayed(sExpire, dur);
        Notify.show(sApp, sApp.getString(R.string.power_timer_started, PowerMenu.duration(sApp, minutes)),
                sApp.getString(R.string.power_timer_running, endClock(sApp)));
        Log.i(TAG, "started: " + minutes + " min");
    }

    /** Turns the timer off (menu "Turn off timer", power off / restart). */
    public static void cancel(Context ctx, boolean notify) {
        if (sFading) {
            abortFade(ctx, notify ? "canceled" : null);
            return;
        }
        if (sEndAt < 0) return;
        cancelCallbacks();
        sEndAt = -1;
        Log.i(TAG, "canceled");
        if (notify) Notify.show(ctx, ctx.getString(R.string.power_timer_canceled));
    }

    /** Last custom value (minutes) for the menu's custom row. */
    static int customMinutes(Context c) {
        try {
            int v = prefs(c).getInt(KEY_CUSTOM, CUSTOM_DEFAULT);
            return v >= CUSTOM_MIN && v <= CUSTOM_MAX ? v : CUSTOM_DEFAULT;
        } catch (Throwable t) {
            return CUSTOM_DEFAULT;
        }
    }

    static void saveCustomMinutes(Context c, int v) {
        try { prefs(c).edit().putInt(KEY_CUSTOM, v).apply(); } catch (Throwable t) { Log.w(TAG, "save custom: " + t); }
    }

    // ================================================================== screen hooks (PowerUi)

    static void onScreenOff(Context ctx) {
        if (sFading) {
            // our own sleep (or IR POWER during the fade): the fade is over; volume and lamp are
            // given back at SCREEN_ON / before the lamp goes off
            Ui.main().removeCallbacksAndMessages(FADE_TOKEN);
            removeCatcher();
            removeWatch();
            sFading = false;
        } else if (sEndAt >= 0) {
            Log.i(TAG, "screen off: timer dropped");
        }
        cancelCallbacks();
        sEndAt = -1;
        sSleepSent = false;
    }

    static void onScreenOn(Context ctx) {
        restoreVolumeIfNeeded(ctx);
    }

    // ================================================================== internals

    private static void cancelCallbacks() {
        Ui.main().removeCallbacks(sWarn);
        Ui.main().removeCallbacks(sExpire);
    }

    private static void warn() {
        if (sApp == null) return;
        Notify.show(sApp, sApp.getString(R.string.power_timer_warn), sApp.getString(R.string.power_timer_warn_desc));
    }

    private static void expire() {
        sEndAt = -1;
        final Context app = sApp;
        if (app == null) return;
        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return;
        if (org.z9x.projector.ak.AkOverlay.isActive()) {
            // never darken or dim the lamp under an auto keystone run: try again a little later
            Log.i(TAG, "expired during AK: retry in 30 s");
            sEndAt = SystemClock.uptimeMillis() + 30_000;
            Ui.main().postDelayed(sExpire, 30_000);
            return;
        }
        if (PowerKey.PowerActions.isBusy() || WakeCurtain.isFadingToBlack()) {
            // a power action (short press fade, power off, restart) owns the curtain: its end
            // action must not be replaced by our fade; try again shortly
            Log.i(TAG, "expired during a power action: retry in 5 s");
            sEndAt = SystemClock.uptimeMillis() + 5_000;
            Ui.main().postDelayed(sExpire, 5_000);
            return;
        }
        Log.i(TAG, "expired: fading out over " + FADE_MS + " ms");
        sFading = true;
        sSleepSent = false;
        OverlayHost.get(app).dismissAll(false);
        Notify.hide();
        final AudioManager am = app.getSystemService(AudioManager.class);
        int v0 = -1;
        try { v0 = am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC); } catch (Throwable t) { Log.w(TAG, "vol: " + t); }
        final int vol0 = v0;
        if (vol0 > 1) {
            try { prefs(app).edit().putInt(KEY_VOL, vol0).apply(); } catch (Throwable t) { Log.w(TAG, "save vol: " + t); }
            for (int i = 1; i <= VOL_STEPS; i++) {
                final int idx = Math.max(1, Math.round(vol0 - (vol0 - 1) * (i / (float) VOL_STEPS)));
                Ui.main().postAtTime(() -> {
                    try {
                        int from = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (from == idx) return;
                        sOwnVol[sOwnVolNext] = new int[]{from, idx};
                        sOwnVolNext = (sOwnVolNext + 1) % sOwnVol.length;
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
                    } catch (Throwable t) { Log.w(TAG, "vol step: " + t); }
                }, FADE_TOKEN, SystemClock.uptimeMillis() + FADE_MS * i / VOL_STEPS);
            }
        }
        WakeCurtain.fadeToBlack(app, FADE_MS, false, true, null);
        boolean dreaming = false;
        try {
            DreamManager dm = app.getSystemService(DreamManager.class);
            dreaming = dm != null && dm.isDreaming();
        } catch (Throwable t) {
            Log.w(TAG, "isDreaming: " + t);
        }
        if (dreaming) Log.i(TAG, "screensaver running: its own lamp dim stays, no timer lamp fade");
        else TimerLamp.fadeDown(app, 1, FADE_MS - 2_000);
        addCatcher(app);
        addWatch(app);
        Ui.main().postAtTime(() -> end(app), FADE_TOKEN, SystemClock.uptimeMillis() + FADE_MS + 100);
    }

    /** End of the fade: pause the player, then the normal sleep. */
    private static void end(Context app) {
        removeCatcher();
        removeWatch();
        pauseMedia(app);
        sendSleep(app, "sleep timer");
    }

    /**
     * The final sleep. From here a late key no longer aborts (sSleepSent); if the screen is still on
     * 3 s later (goToSleep refused), everything is given back.
     */
    private static void sendSleep(Context app, String why) {
        sSleepSent = true;
        PowerKey.PowerActions.sleepNow(app, why);
        Ui.main().postDelayed(() -> {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (sFading && pm != null && pm.isInteractive()) {
                sSleepSent = false;
                abortFade(app, "sleep did not happen");
            }
        }, 3_000);
    }

    /** Power key short press during the fade: go now (PowerKey). */
    static void finishNow(Context ctx, String why) {
        if (!sFading) return;
        Log.i(TAG, "finish now: " + why);
        Ui.main().removeCallbacksAndMessages(FADE_TOKEN);
        removeCatcher();
        removeWatch();
        pauseMedia(ctx.getApplicationContext());
        // the curtain is part-way: let it reach black quickly, then sleep
        final Context app = ctx.getApplicationContext();
        sSleepSent = true;                                           // no abort from here on
        Runnable go = () -> sendSleep(app, "sleep timer (finished early)");
        if (!WakeCurtain.fadeToBlack(ctx, PowerKey.PowerActions.FADE_SHORT_MS, false, false, go)) go.run();
    }

    /**
     * Any key / AK event during the fade: keep watching. Picture, sound and light come back, the
     * timer is off. why == null: silent (a new timer replaces it). Main thread.
     */
    static void abortFade(Context ctx, String why) {
        if (!sFading) return;
        if (sSleepSent) {
            Log.i(TAG, "abort ignored (" + why + "): sleep already sent");
            return;
        }
        Log.i(TAG, "fade aborted" + (why == null ? "" : ": " + why));
        sFading = false;
        sEndAt = -1;
        Ui.main().removeCallbacksAndMessages(FADE_TOKEN);
        removeCatcher();
        removeWatch();
        WakeCurtain.fastReveal();
        TimerLamp.restore();
        restoreVolumeIfNeeded(ctx);
        if (why != null) Notify.show(ctx, ctx.getString(R.string.power_timer_canceled));
    }

    private static void pauseMedia(Context app) {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null) return;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        } catch (Throwable t) {
            Log.w(TAG, "pause: " + t);
        }
    }

    /** SCREEN_ON, an aborted fade and boot: give back the volume the timer faded. Any thread. */
    public static void restoreVolumeIfNeeded(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            int v = p.getInt(KEY_VOL, -1);
            if (v < 0) return;
            p.edit().remove(KEY_VOL).apply();
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            Log.i(TAG, "volume restored to " + v);
        } catch (Throwable t) {
            Log.w(TAG, "restore volume: " + t);
        }
    }

    // ------------------------------------------------------------------ key catcher
    /**
     * A 1x1 transparent focusable overlay during the fade: D-pad, OK, BACK and media keys go to the
     * focused window, which is ours now, so "press any key to keep watching" works for every key,
     * not only the global ones. Removed at the end of the fade or on abort.
     */
    private static void addCatcher(Context app) {
        if (sCatcher != null) return;
        try {
            View v = new View(app) {
                @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                    if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && sFading) {
                        Ui.main().post(() -> abortFade(getContext(), "key " + ev.getKeyCode()));
                    }
                    return true;                                     // the key never reaches the app
                }
            };
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            WindowManager.LayoutParams lp = OverlayHost.params(true, 1, 1, Gravity.TOP | Gravity.START);
            lp.setTitle("Z9xTimerKeys");
            if (OverlayHost.get(app).addStatic(v, lp)) {
                sCatcher = v;
                v.post(v::requestFocus);
            }
        } catch (Throwable t) {
            Log.w(TAG, "key catcher: " + t);
        }
    }

    private static void removeCatcher() {
        View v = sCatcher;
        sCatcher = null;
        if (v != null) {
            try { OverlayHost.get(v.getContext()).removeStatic(v); } catch (Throwable t) { Log.w(TAG, "catcher remove: " + t); }
        }
    }

    // ------------------------------------------------------------------ volume / HOME watch
    /**
     * Volume keys and HOME are consumed before any window sees them, so the catcher cannot take
     * them. While fading, a STREAM_MUSIC volume change that is not one of our own steps (matched by
     * its (previous, new) pair; the fade only ever steps down) or HOME aborts the fade. Main thread.
     */
    private static void addWatch(Context app) {
        if (sWatch != null) return;
        for (int i = 0; i < sOwnVol.length; i++) sOwnVol[i] = null;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent in) {
                try {
                    if (!sFading || sWatch != this) return;
                    String a = in.getAction();
                    if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(a)) {
                        if ("homekey".equals(in.getStringExtra("reason"))) abortFade(c, "home key");
                        return;
                    }
                    if (!ACTION_VOLUME_CHANGED.equals(a)) return;
                    if (in.getIntExtra(EXTRA_VOL_STREAM, -1) != AudioManager.STREAM_MUSIC) return;
                    int now = in.getIntExtra(EXTRA_VOL_VALUE, -1);
                    int prev = in.getIntExtra(EXTRA_VOL_PREV, -1);
                    if (now < 0 || now == prev) return;
                    for (int[] own : sOwnVol) {
                        if (own != null && own[1] == now && (prev < 0 || own[0] == prev)) return;
                    }
                    abortFade(c, "volume " + prev + " -> " + now);
                } catch (Throwable t) {
                    Log.w(TAG, "watch: " + t);
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter(ACTION_VOLUME_CHANGED);
            f.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);    // main-thread delivery
            sWatch = r;
        } catch (Throwable t) {
            Log.w(TAG, "volume watch: " + t);
        }
    }

    private static void removeWatch() {
        BroadcastReceiver r = sWatch;
        sWatch = null;
        if (r != null && sApp != null) {
            try { sApp.unregisterReceiver(r); } catch (Throwable t) { Log.w(TAG, "watch remove: " + t); }
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
