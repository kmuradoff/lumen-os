package org.z9x.projector.power;

import android.app.DreamManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.battery.Battery;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;

/**
 * The lamp-only standby of the XGIMI Play 6: the stock power menu's "Screen off" (关屏), now the default
 * short press of the remote power key (docs/play6/research/sleep_stock.md 1, 4 and 9). The owner saw the
 * t12 short press (Android sleep, the vendor's STR) come back only as a cold boot; until the STR test
 * passes, Android stays awake and only the lamp goes off (IGmpf 195 through power/Lamp), so any remote key
 * brings the picture back in well under a second. A fork of the Z9X StandbyController's v6.5 part, much
 * smaller: no STR gate inside (PowerKey.PowerActions decides STR first), no motion-trigger marks, no lamp
 * watchdog, no CEC, no persisted state.
 *
 * {@link #enter} (main thread; the wake curtain is already black after the short-press fade): every overlay
 * closed, a running sleep-timer fade ended, a running dream stopped, the screen wake lock "p6:standby"
 * (SCREEN_BRIGHT | ON_AFTER_RELEASE, so neither the screen timeout nor the screensaver starts), audio focus
 * GAIN (playing media pauses) and STREAM_MUSIC muted (only when it was not muted already), the curtain
 * black, a full-screen focusable key catcher (keys go to us, not to the app below), the lamp off (Lamp.off:
 * 196, 195(false), 196), the deadline armed, STANDBY_CHANGED sent.
 * {@link #exit} (the first key DOWN of any remote key, a HOME press, another window taking the focus):
 * the catcher closed, the lamp on (Lamp.on: 195(true), 196 polled), then the curtain's quick reveal,
 * sound and audio focus back, the sleep timer's faded volume back, the wake lock released.
 * Deadline: after {@value #ADAPTER_MAX_MIN} min on the adapter (persist.lumen.p6.standby_max_min) and
 * {@value #BATTERY_MAX_MIN} min on battery (or the adapter value when that is smaller), measured from
 * enter and re-armed when the battery state changes, the projector really powers off through the power
 * menu's own Power off (PowerKey.PowerActions.powerOff, PowerManager.shutdown). The lamp is not relit.
 * A SCREEN_OFF or ACTION_SHUTDOWN while active ends the state without touching the lamp.
 *
 * The HDMI source is never switched by the standby (t14): the TIF session stays while Android is awake, and
 * source/HdmiSource holds a session event that comes during the standby until {@link #exit}.
 * {@link #isActive()} is the real state, so the shared seams (StandbyStateProvider for org.z9x.tvinput and
 * Lumen Home, RemotePrompt, RecentsActivity, PanelRequestReceiver) see it unchanged. Broadcast
 * {@value #ACTION_STANDBY_CHANGED} (extra "standby") under {@link #PERMISSION_STANDBY_STATE}, as the Z9X
 * app sends it (Lumen Home and org.z9x.tvinput listen). Log tag P6Standby. Main thread only.
 */
public final class StandbyController {
    private static final String TAG = "P6Standby";
    /** Signature permission of the standby state (defined by this app, held by org.z9x.tvinput and Home). */
    public static final String PERMISSION_STANDBY_STATE = "org.z9x.projector.permission.STANDBY_STATE";
    public static final String ACTION_STANDBY_CHANGED = "org.z9x.projector.action.STANDBY_CHANGED";
    public static final String EXTRA_STANDBY = "standby";
    /** Our power-off / restart call is considered in flight this long after it was made. */
    private static final long SHUTDOWN_STUCK_MS = 60_000;
    /** Deadline on the adapter, minutes; persist.lumen.p6.standby_max_min overrides it (tests). */
    static final int ADAPTER_MAX_MIN = 120;
    /** Deadline on battery (or unknown power source), minutes; never longer than the adapter value. */
    static final int BATTERY_MAX_MIN = 15;
    static final String PROP_MAX_MIN = "persist.lumen.p6.standby_max_min";

    private static volatile long sShutdownAt = -1;
    private static volatile boolean sActive;
    // main thread state
    private static Context sApp;
    private static long sEnteredAt;
    private static String sEnterWhy;
    private static PowerManager.WakeLock sWakeLock;
    private static AudioFocusRequest sFocus;
    private static boolean sMutedByUs;
    private static KeyCatcher sCatcher;
    /** exit / deadline / screen off is closing the catcher itself: its dismissal is not a user exit. */
    private static boolean sClosing;
    /** The key whose first DOWN ended the standby: its repeats and UP are swallowed too (PowerKey). */
    private static long sSwallowDownTime = -1;
    private static final Runnable sDeadline = StandbyController::onDeadline;

    private StandbyController() {}

    /** PowerKey.install (main thread, once): re-arm the deadline when the battery state changes. */
    static void install(Context app) {
        if (sApp != null) return;
        sApp = app.getApplicationContext();
        Battery.addListener(() -> { if (sActive) armDeadline(); });
    }

    /** The lamp-only standby is on (also while its lamp-off is still running). Any thread. */
    public static boolean isActive() {
        return sActive;
    }

    /** Our power-off or restart (power menu) was requested less than a minute ago. Any thread. */
    public static boolean shutdownInFlight() {
        long at = sShutdownAt;
        return at >= 0 && SystemClock.uptimeMillis() - at < SHUTDOWN_STUCK_MS;
    }

    /** PowerKey.PowerActions, right before PowerManager.shutdown / reboot. */
    static void noteShutdown() {
        sShutdownAt = SystemClock.uptimeMillis();
    }

    /** PowerKey.PowerActions: the shutdown / reboot call failed. */
    static void clearShutdown() {
        sShutdownAt = -1;
    }

    /** One line for Projector settings > Diagnostics. Main thread. */
    public static String describe() {
        if (!sActive) {
            String b = PowerKey.PowerActions.strBlocker(sApp);
            return "off; a short press now gives " + (b == null ? "lamp off + STR" : "the lamp-only standby (STR: " + b + ")");
        }
        long min = (SystemClock.elapsedRealtime() - sEnteredAt) / 60_000;
        return "on for " + min + " min (" + sEnterWhy + "), power off after " + limitMin() + " min";
    }

    // ================================================================== enter / exit
    /** PowerKey.PowerActions.sleep: the lamp-only standby. The curtain is black already. Main thread. */
    static void enter(Context ctx, String why) {
        try { org.z9x.projector.cec.CecPolicy.onUserKey(); } catch (Throwable t) { Log.w(TAG, "cec: " + t); }
        final Context app = ctx.getApplicationContext();
        if (sApp == null) sApp = app;
        if (sActive) return;
        sActive = true;
        try { org.z9x.projector.eye.EyeGuard.onStandbyChanged(true); } catch (Throwable t) { Log.w(TAG, "eye: " + t); }
        sClosing = false;
        sEnteredAt = SystemClock.elapsedRealtime();
        sEnterWhy = why;
        Log.i(TAG, "enter (" + why + ")");
        try { OverlayHost.get(app).dismissAll(false); } catch (Throwable t) { Log.w(TAG, "overlays: " + t); }
        try { SleepTimer.onScreenOff(app); } catch (Throwable t) { Log.w(TAG, "timer: " + t); }  // a timer fade ends here
        try { Hal.stopMotor(); } catch (Throwable t) { Log.w(TAG, "motor: " + t); }
        try {
            DreamManager dm = app.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) dm.stopDream();
        } catch (Throwable t) {
            Log.w(TAG, "stopDream: " + t);
        }
        holdScreen(app);
        quiet(app);
        try { WakeCurtain.onScreenOff(app); } catch (Throwable t) { Log.w(TAG, "curtain: " + t); }   // black now
        showCatcher(app);
        Lamp.off("standby", on -> {
            if (!sActive) return;
            if (!Boolean.FALSE.equals(on)) Log.w(TAG, "lamp off not confirmed (196 " + on + "): staying in standby");
        });
        armDeadline();
        broadcast(app, true);
    }

    /** An HDMI device turned the projector on over CEC (cec.CecPolicy, stock parity 2026-10-21). Main thread. */
    public static void exitForCec(Context ctx, String why) {
        exit(ctx, why);
    }

    /** The user is back (a key, HOME, another window): lamp on, picture, sound. Main thread. */
    static void exit(Context ctx, String why) {
        if (!sActive) return;
        final Context app = ctx.getApplicationContext();
        Log.i(TAG, "exit (" + why + ") after " + (SystemClock.elapsedRealtime() - sEnteredAt) / 1000 + " s");
        end(app);
        try { WakeCurtain.onStandbyWake(); } catch (Throwable t) { Log.w(TAG, "curtain: " + t); }
        Lamp.on("standby exit", on -> {
            try { org.z9x.projector.eye.EyeGuard.onLampOn(); } catch (Throwable t) { Log.w(TAG, "eye: " + t); }
            WakeCurtain.onLampOn();                                  // reveal also if 196 stayed unknown
            if (!Boolean.TRUE.equals(on)) Log.w(TAG, "lamp on not confirmed (196 " + on + ")");
        });
        try { SleepTimer.restoreVolumeIfNeeded(app); } catch (Throwable t) { Log.w(TAG, "volume: " + t); }
        try { org.z9x.projector.source.HdmiSource.onStandbyEnd(); } catch (Throwable t) { Log.w(TAG, "hdmi: " + t); }
        // stock parity 2026-10-21: "Open at power-on" (the HDMI input, if chosen)
        try { org.z9x.projector.source.BootSource.onPowerOn(app, "standby exit"); } catch (Throwable t) { Log.w(TAG, "boot source: " + t); }
        broadcast(app, false);
    }

    /** SCREEN_OFF while active (an Android sleep took over): the state ends, the lamp is left alone. */
    static void onScreenOff(Context ctx) {
        if (!sActive) return;
        Log.i(TAG, "screen off during standby: standby ends (the lamp stays as it is)");
        Context app = ctx.getApplicationContext();
        end(app);
        broadcast(app, false);
    }

    /** ACTION_SHUTDOWN while active (our deadline or any other power-off): end the state quietly. */
    static void onShutdown(Context ctx) {
        if (!sActive) return;
        Log.i(TAG, "shutdown during standby");
        end(ctx.getApplicationContext());
    }

    /** Releases what enter took (wake lock, audio, catcher, deadline). The lamp is the caller's. */
    private static void end(Context app) {
        sActive = false;
        Ui.main().removeCallbacks(sDeadline);
        closeCatcher();
        loud(app);
        releaseScreen();
    }

    // ================================================================== deadline
    /** The deadline for the power source right now, in minutes. */
    private static int limitMin() {
        int adapter = ADAPTER_MAX_MIN;
        try {
            int p = SystemProperties.getInt(PROP_MAX_MIN, ADAPTER_MAX_MIN);
            if (p >= 1) adapter = p;
        } catch (Throwable ignored) {
        }
        Battery.State s = Battery.current();
        boolean onAdapter = s != null && Boolean.TRUE.equals(s.adapter);
        return onAdapter ? adapter : Math.min(BATTERY_MAX_MIN, adapter);
    }

    /** (Re)arms the deadline from the moment of enter; a deadline already past powers off now. */
    private static void armDeadline() {
        Ui.main().removeCallbacks(sDeadline);
        long left = sEnteredAt + limitMin() * 60_000L - SystemClock.elapsedRealtime();
        Ui.main().postDelayed(sDeadline, Math.max(0, left));
    }

    /** The standby lasted its limit: the real power off (the power menu's Power off). */
    private static void onDeadline() {
        if (!sActive || sApp == null) return;
        final Context app = sApp;
        Log.i(TAG, "deadline: " + limitMin() + " min in standby, powering off");
        end(app);
        broadcast(app, false);
        PowerKey.PowerActions.powerOff(app);
    }

    // ================================================================== screen, sound
    private static void holdScreen(Context app) {
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm == null) return;
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ON_AFTER_RELEASE, "p6:standby");
            wl.setReferenceCounted(false);
            wl.acquire((ADAPTER_MAX_MIN + 10) * 60_000L);         // the deadline ends it long before
            sWakeLock = wl;
        } catch (Throwable t) {
            Log.w(TAG, "wake lock: " + t);
        }
    }

    private static void releaseScreen() {
        PowerManager.WakeLock wl = sWakeLock;
        sWakeLock = null;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Throwable t) { Log.w(TAG, "release: " + t); }
    }

    /** Audio focus GAIN (players pause) and STREAM_MUSIC muted when it was not. */
    private static void quiet(Context app) {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null) return;
            AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(change -> { })
                    .build();
            am.requestAudioFocus(r);
            sFocus = r;
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                sMutedByUs = true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "quiet: " + t);
        }
    }

    /** Undoes {@link #quiet}: unmute only what we muted, give the audio focus back. */
    private static void loud(Context app) {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am == null) return;
            if (sMutedByUs) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            if (sFocus != null) am.abandonAudioFocusRequest(sFocus);
        } catch (Throwable t) {
            Log.w(TAG, "loud: " + t);
        } finally {
            sMutedByUs = false;
            sFocus = null;
        }
    }

    private static void broadcast(Context app, boolean on) {
        try {
            Intent i = new Intent(ACTION_STANDBY_CHANGED).putExtra(EXTRA_STANDBY, on)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            app.sendBroadcast(i, PERMISSION_STANDBY_STATE);
        } catch (Throwable t) {
            Log.w(TAG, "broadcast: " + t);
        }
    }

    // ================================================================== keys
    /**
     * PowerKey.onGlobalKey, every global key (the power key too): while active, the first DOWN of any key
     * ends the standby and is consumed, and so are the repeats and the UP of that same press. True when
     * consumed. Main thread.
     */
    static boolean onGlobalKey(Context ctx, KeyEvent ev) {
        try { org.z9x.projector.cec.CecPolicy.onUserKey(); } catch (Throwable t) { Log.w(TAG, "cec: " + t); }
        if (sSwallowDownTime >= 0 && ev.getDownTime() == sSwallowDownTime) {
            if (ev.getAction() == KeyEvent.ACTION_UP) sSwallowDownTime = -1;
            return true;
        }
        if (!sActive) return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            sSwallowDownTime = ev.getDownTime();
            exit(ctx, "key " + ev.getKeyCode());
        }
        return true;
    }

    private static void showCatcher(Context app) {
        try {
            KeyCatcher c = new KeyCatcher();
            sCatcher = c;
            if (!OverlayHost.get(app).show(c)) {
                sCatcher = null;
                Log.w(TAG, "key catcher could not be shown: only global keys end the standby");
            }
        } catch (Throwable t) {
            Log.w(TAG, "key catcher: " + t);
        }
    }

    private static void closeCatcher() {
        KeyCatcher c = sCatcher;
        sCatcher = null;
        if (c == null) return;
        sClosing = true;
        try { if (c.isShowing()) c.dismiss(); } catch (Throwable t) { Log.w(TAG, "catcher: " + t); }
        sClosing = false;
    }

    /** The catcher went away without us (HOME closes panels, another window took the focus): the user is back. */
    private static void onCatcherGone(KeyCatcher c) {
        if (c != sCatcher || sClosing || !sActive || sApp == null) return;
        sCatcher = null;
        exit(sApp, "home key or another window");
    }

    /**
     * A full-screen, empty, focusable overlay under the black curtain while the lamp is off: D-pad, OK,
     * BACK and the other non-global keys reach this window instead of the app below; the first DOWN ends
     * the standby, every key is consumed. Never hides by itself.
     */
    private static final class KeyCatcher extends Panel {
        @Override protected View onCreateView(Context ctx) {
            View v = new View(ctx);
            v.setFocusable(true);
            v.setDefaultFocusHighlightEnabled(false);
            return v;
        }

        @Override protected WindowManager.LayoutParams onCreateLayoutParams(Context ctx) {
            WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
            lp.setTitle("P6StandbyKeys");
            return lp;
        }

        @Override protected long autoHideMs() { return 0; }

        @Override protected void onShown(View root) { }

        @Override protected void animateOut(View root, Runnable end) { end.run(); }

        @Override protected boolean onKeyEvent(KeyEvent ev) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && sActive && sApp != null) {
                exit(sApp, "key " + ev.getKeyCode() + " (catcher)");
            }
            return true;
        }

        @Override protected void onDismissed() {
            onCatcherGone(this);
        }
    }
}
