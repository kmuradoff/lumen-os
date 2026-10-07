package org.z9x.projector.eye;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MODULE "eye" (v6.2b): runtime side of smart eye protection on Z9X (ToF source 4, vendor class
 * HumDet_Process, thread "hd_mult-tof"). research/v62x RESULT_eye.json / RESULT_drm.json,
 * verdict.corrected_decisions; V63_REQUIREMENTS 2 (fix B: our UI, no XGIMI binaries).
 *
 * Vendor sequence (libxgimi HumDet_Process::setHumanDetectScreenOnOffMultTof, VERIFIED):
 *   person close -> focusEvent 500 "humDetByMultTof"
 *                -> nuiCommand "1;generalGraphics;{..HDNativeUI_MultTof.bmp..}" (stock nativeui drew a
 *                   black card; on this GSI the init service z9x_nui only acknowledges it)
 *                -> the vendor dims the light, then focusEvent 501 (statistics JSON)
 *   restore (person gone, 148(true), or UI watchdog timeout) -> "3;generalGraphics; " -> 601.
 *
 * | event           | meaning                                       | UI                                  |
 * |-----------------|-----------------------------------------------|-------------------------------------|
 * | 500             | person detected, vendor dims the light        | opaque black mask, eats keys        |
 * | 601             | light restored by the vendor                  | mask removed                        |
 * | 501             | statistics JSON                               | none                                |
 * | 608             | "brightnessAdjust" (distance dimming thread)  | notice, at most once per 30 min     |
 * | 502/503/602/603 | cutout-class models (not seen on Z9X)         | unchanged from v6.1: 502 daily      |
 * |                 |                                               | notice, 503 transparent key layer   |
 * |                 |                                               | (BACK restores), 602/603 remove it  |
 *
 * The user always gets the picture back:
 *  - on the mask: BACK / ESC / OK / ENTER (key UP of a press that began on the mask) -> mask off +
 *    148 setHumanDetectScreenOnOff(TRUE);
 *  - gear (GLOBAL_BUTTON, via KeyReceiver -> {@link #onGlobalKey}): short -> same as BACK; held ->
 *    148(true), then 152 setHumanDetectOnOff(false) and a "turned off" notice;
 *  - while the opaque mask is up the vendor UI watchdog is armed: 115 uiHeartBeatSet("NativeUIWatch",
 *    1, 3000) and 114 uiHeartBeatDo every 1 s; if this process dies the vendor restores the light
 *    itself (heartBeat -> vtable+0x34 = setHumanDetectScreenOnOff(1), VERIFIED). 115(name, 0) on hide.
 *    114/115 run on their own "z9x-eye" thread (Hal.runEye), never queued behind a slow z9x-hal
 *    task; a beat that completes later than period/2 is logged (tune on the device). A 115(0)
 *    refused because sys.z9x.nui was not "ready" is retried every 1 s (up to 30 s) once it is.
 *  - SCREEN_OFF (or a 500 while the screen is off) removes the mask without 148; the vendor may
 *    still hold the light dimmed. Unless a 601 arrives first, the next lamp-on (PowerUi.onLampOn,
 *    after SCREEN_ON) sends one gated 148(true) unconditionally (harmless when the light is on;
 *    PowerPolicy's own 147 -> 148 check covers only the no-STR path with 196 false).
 * Gate (GmpfClient.nuiReaderReady, sys.z9x.nui == ready, set by the z9x_nui service): 148, 152, 114
 * and 115 are sent only while the fifo reader is up, because without it they block a HAL thread
 * (and ours) inside nuiCommand until reboot. Without the reader the mask is still shown on 500 (it
 * alone removes the image light) and BACK still removes it; nothing is sent. Never 148(false), 87, 88.
 *
 * Main thread (Hal focus listeners and GLOBAL_BUTTON run there); HAL I/O only through Hal.run
 * (148 / 152) and Hal.runEye (114 / 115).
 */
public final class EyeGuard {
    private static final String TAG = "Z9xEye";
    private static final String PREFS = "z9x_eye";
    private static final String KEY_NOTICE_DAY = "follow_notice_day";
    /** Stock watchdog name (GlobalConfig EyeProjectionService / SystemUI EyeProjectionDialog). */
    private static final String HB_NAME = "NativeUIWatch";
    /** Stock 1500; 3000 tolerates a busy z9x-hal thread (vendor ticks are 1 ms, so ~3 s+). */
    private static final int HB_PERIOD_MS = 3000;
    private static final long HB_PUMP_MS = 1000;
    /** 608 notice at most this often. */
    private static final long BRIGHT_NOTICE_GAP_MS = 30 * 60_000L;
    /** Remote gear key (Z9xKeys.GEAR, package-private there). */
    private static final int KEYCODE_GEAR = KeyEvent.KEYCODE_SETTINGS;

    /** Opaque black card on 500 (Z9X multi-ToF) or the transparent key layer on 503 (cutout). */
    private static final int MODE_NONE = 0, MODE_MASK = 1, MODE_LAYER = 2;

    private static Context sApp;
    private static View sView;
    private static int sMode = MODE_NONE;
    private static volatile boolean sHbArmed;
    /** uptime when the beat in flight was queued (log only). */
    private static volatile long sBeatQueuedAt;
    private static boolean sLateLogged;
    /** 115(0) refused by the NUI gate: retries left / scheduled (main thread). */
    private static int sDisarmRetries;
    private static final int DISARM_RETRY_MAX = 30;
    /** Masked (or 500) at screen-off and no 601 since: 148(true) at the next lamp-on. */
    private static volatile boolean sRestoreAtLampOn;
    /** One 114 beat in flight at most: a stuck HAL thread must not collect a queue of beats. */
    private static final AtomicBoolean sBeatInFlight = new AtomicBoolean();
    private static long sLastBrightNotice = -BRIGHT_NOTICE_GAP_MS;
    /** Gear press that began while the mask was up (downTime), and whether its hold already fired. */
    private static long sGearDown = -1;
    private static boolean sGearLongFired;

    private EyeGuard() {}

    /** App.onCreate, main thread. */
    public static void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        Hal.addFocusEventListener(EyeGuard::onFocusEvent);
        try {
            BroadcastReceiver off = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        if (sMode == MODE_MASK) sRestoreAtLampOn = true;
                        if (sMode != MODE_NONE) hide("screen off");
                    } catch (Throwable t) {
                        Log.w(TAG, "screen off: " + t);
                    }
                }
            };
            sApp.registerReceiver(off, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            Log.w(TAG, "screen-off receiver: " + t);
        }
    }

    /** True while the mask or the key layer is up. Main thread. */
    public static boolean isActive() { return sMode != MODE_NONE; }

    private static void onFocusEvent(int type, String value) {
        try {
            switch (type) {
                case 500:
                    if (!interactive()) {
                        Log.i(TAG, "500 while the screen is off: no mask, 148(true) at the next lamp-on");
                        sRestoreAtLampOn = true;
                        break;
                    }
                    Log.i(TAG, "500 person in front of the lens (" + value + "): mask up, nui="
                            + GmpfClient.nuiReaderReady());
                    show(MODE_MASK);
                    break;
                case 601:
                    Log.i(TAG, "601 light restored by the vendor");
                    sRestoreAtLampOn = false;
                    if (sMode == MODE_MASK) hide("601");
                    break;
                case 501:
                    break;                                          // statistics JSON
                case 608:
                    brightnessNotice(value);
                    break;
                case 502:
                    Log.i(TAG, "502 mask on (follow)");
                    followNoticeOncePerDay();
                    break;
                case 503:
                    Log.i(TAG, "503 mask on (safe): key layer up, BACK restores the light");
                    show(MODE_LAYER);
                    break;
                case 602:
                    Log.i(TAG, "602 mask off (follow)");
                    if (sMode == MODE_LAYER) hide("602");
                    break;
                case 603:
                    Log.i(TAG, "603 mask off (safe)");
                    if (sMode == MODE_LAYER) hide("603");
                    Notify.show(sApp, sApp.getString(R.string.eye_notice_exit));
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.e(TAG, "event " + type, t);
        }
    }

    /**
     * v6.5 StandbyController (main thread): lamp-only standby started / ended. The display stays on in
     * standby, so there is no SCREEN_OFF: entering it is handled like one (mask removed without 148,
     * 148(true) at the next lamp-on if the mask was up), and {@link #interactive} reports false while
     * it lasts, so a 500 in standby raises no mask.
     */
    public static void onStandbyChanged(boolean standby) {
        if (!standby) return;
        try {
            if (sMode == MODE_MASK) sRestoreAtLampOn = true;
            if (sMode != MODE_NONE) hide("standby");
        } catch (Throwable t) {
            Log.w(TAG, "standby: " + t);
        }
    }

    private static boolean interactive() {
        if (org.z9x.projector.power.StandbyController.isActive()) return false;
        try {
            PowerManager pm = sApp.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * PowerUi.onLampOn (any thread): the lamp is confirmed on after a wake. If the screen went off
     * under the mask (or a 500 came while it was off) and no 601 followed, the vendor may still hold
     * the light dimmed: send one gated 148(true). Skipped while a new mask is up (the user restores).
     */
    public static void onLampOn() {
        if (!sRestoreAtLampOn) return;
        Ui.main().post(() -> {
            try {
                if (!sRestoreAtLampOn) return;
                sRestoreAtLampOn = false;
                if (sMode == MODE_MASK) {
                    Log.i(TAG, "lamp on: a new mask is up, no 148 (Back restores)");
                    return;
                }
                if (!GmpfClient.nuiReaderReady()) {
                    Log.w(TAG, "lamp on after a masked screen-off: no NUI fifo reader, 148 not sent");
                    return;
                }
                boolean queued = Hal.run((g, g2) ->
                        Log.i(TAG, "lamp on after a masked screen-off: 148(true) -> " + g.restoreEyeProtectionScreen()));
                if (!queued) Log.w(TAG, "lamp on after a masked screen-off: HAL not available, 148 not sent");
            } catch (Throwable t) {
                Log.w(TAG, "onLampOn: " + t);
            }
        });
    }

    // ------------------------------------------------------------------ notices
    private static void followNoticeOncePerDay() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());
        SharedPreferences p = sApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (today.equals(p.getString(KEY_NOTICE_DAY, null))) return;
        p.edit().putString(KEY_NOTICE_DAY, today).apply();
        Notify.show(sApp, sApp.getString(R.string.panel_eye), sApp.getString(R.string.eye_notice_where));
    }

    /** 608 "brightnessAdjust": stock HighTemperatureTipsDialog-like tip, rate-limited, never over the mask. */
    private static void brightnessNotice(String value) {
        Log.i(TAG, "608 " + value);
        if (value == null || !value.contains("brightnessAdjust") || sMode != MODE_NONE) return;
        long now = SystemClock.elapsedRealtime();
        if (now - sLastBrightNotice < BRIGHT_NOTICE_GAP_MS) return;
        sLastBrightNotice = now;
        Notify.show(sApp, sApp.getString(R.string.eye_bright_reduced));
    }

    // ------------------------------------------------------------------ mask / layer
    private static void show(int mode) {
        if (sMode == mode) return;
        if (sMode != MODE_NONE) hide("replaced");
        View v = mode == MODE_MASK ? new MaskView(sApp) : new KeyLayer(sApp);
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        lp.setTitle(mode == MODE_MASK ? "Z9xEyeMask" : "Z9xEyeLayer");
        OverlayHost host = OverlayHost.get(sApp);
        host.dismissAll(false);                                     // the mask must get key focus
        if (!host.addStatic(v, lp)) {
            Log.e(TAG, "mask could not be added");
            return;
        }
        sView = v;
        sMode = mode;
        sGearDown = -1;
        sGearLongFired = false;
        v.requestFocus();
        if (mode == MODE_MASK) armHeartbeat(true);
    }

    /** Removes the mask / layer and disarms the watchdog. Sends no 148. */
    private static void hide(String why) {
        if (sMode == MODE_NONE) return;
        View v = sView;
        sView = null;
        sMode = MODE_NONE;
        Log.i(TAG, "mask removed (" + why + ")");
        if (v != null) {
            try { OverlayHost.get(sApp).removeStatic(v); } catch (Throwable t) { Log.w(TAG, "remove: " + t); }
        }
        armHeartbeat(false);
    }

    /**
     * User action: mask off, then (only with the fifo reader up) 148(true); turnOff also sends
     * 152(false) after it and shows "turned off". Main thread.
     */
    private static void restoreByUser(String why, final boolean turnOff) {
        hide(why);
        if (!GmpfClient.nuiReaderReady()) {
            Log.w(TAG, why + ": no NUI fifo reader (" + GmpfClient.NUI_READY_PROP
                    + " != ready): 148" + (turnOff ? "/152" : "") + " not sent (would block in nuiCommand)");
            if (turnOff) Notify.show(sApp, sApp.getString(R.string.eye_unavailable));
            return;
        }
        boolean queued = Hal.run((g, g2) -> {
            try {
                Log.i(TAG, why + ": 148(true) -> " + g.restoreEyeProtectionScreen());
            } catch (Exception e) {
                Log.w(TAG, why + ": 148(true) failed: " + e);
            }
            if (turnOff) {
                boolean r = false;
                try {
                    r = g.setEyeProtection(false);
                    Log.i(TAG, why + ": 152(false) -> " + r);
                } catch (Exception e) {
                    Log.w(TAG, why + ": 152(false) failed: " + e);
                }
                Notify.show(sApp, sApp.getString(r ? R.string.eye_off_done : R.string.toast_failed));
            }
        });
        if (!queued) Log.w(TAG, why + ": HAL not available, 148 not sent");
    }

    // ------------------------------------------------------------------ keys
    private static boolean isRestoreKey(int code, boolean okKeysToo) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return okKeysToo;
            default:
                return false;
        }
    }

    /**
     * KeyReceiver, every GLOBAL_BUTTON after the power key, main thread. Returns true (consumed)
     * while the mask or layer is up: gear UP (short) restores the light, gear held (first repeat)
     * restores it and turns eye protection off; every other global key is eaten so no panel or
     * app opens behind the mask. A gear press that began before the mask appeared only gets eaten.
     */
    public static boolean onGlobalKey(KeyEvent ev) {
        if (sMode == MODE_NONE) return false;
        try {
            if (ev.getKeyCode() != KEYCODE_GEAR) {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                    Log.i(TAG, "global key " + ev.getKeyCode() + " eaten (mask up)");
                }
                return true;
            }
            int action = ev.getAction();
            if (action == KeyEvent.ACTION_DOWN) {
                if (ev.getRepeatCount() == 0) {
                    sGearDown = ev.getDownTime();
                    sGearLongFired = false;
                } else if (!sGearLongFired && ev.getDownTime() == sGearDown) {
                    sGearLongFired = true;
                    restoreByUser("gear held", true);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                boolean shortPress = !sGearLongFired && !ev.isCanceled() && ev.getDownTime() == sGearDown;
                sGearDown = -1;
                sGearLongFired = false;
                if (shortPress) restoreByUser("gear", false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "global key: " + t);
        }
        return true;
    }

    // ------------------------------------------------------------------ vendor UI watchdog
    private static final Runnable PUMP = new Runnable() {
        @Override public void run() {
            if (!sHbArmed) return;
            if (sBeatInFlight.compareAndSet(false, true)) {
                // own "z9x-eye" thread; ifSkipped clears the flag when the task is dropped because
                // the HAL is not connected
                final long t0 = SystemClock.uptimeMillis();
                sBeatQueuedAt = t0;
                sLateLogged = false;
                boolean queued = Hal.runEye((g, g2) -> {
                    try {
                        if (sHbArmed) g.uiHeartBeatDo(HB_NAME);
                    } finally {
                        sBeatInFlight.set(false);
                        long dt = SystemClock.uptimeMillis() - t0;
                        if (dt > HB_PERIOD_MS / 2) {
                            Log.w(TAG, "114 beat completed " + dt + " ms after it was queued (period "
                                    + HB_PERIOD_MS + " ms)");
                        }
                    }
                }, () -> sBeatInFlight.set(false));
                if (!queued) sBeatInFlight.set(false);
            } else if (!sLateLogged && SystemClock.uptimeMillis() - sBeatQueuedAt > HB_PERIOD_MS / 2) {
                sLateLogged = true;
                Log.w(TAG, "114 beat still in flight " + (SystemClock.uptimeMillis() - sBeatQueuedAt)
                        + " ms (period " + HB_PERIOD_MS + " ms)");
            }
            Ui.main().postDelayed(this, HB_PUMP_MS);
        }
    };

    /** Main thread. Arms only with the fifo reader up (the timeout path goes through nuiCommand). */
    private static void armHeartbeat(final boolean on) {
        if (on == sHbArmed) return;
        if (on && !GmpfClient.nuiReaderReady()) {
            Log.w(TAG, "no NUI fifo reader: vendor UI watchdog not armed");
            return;
        }
        sHbArmed = on;
        Ui.main().removeCallbacks(PUMP);
        Ui.main().removeCallbacks(DISARM_RETRY);
        sDisarmRetries = 0;
        send115(on);
        if (on) Ui.main().postDelayed(PUMP, HB_PUMP_MS);
    }

    /** 115 on the "z9x-eye" thread (after any queued beat). A refused disarm is retried. */
    private static void send115(final boolean on) {
        boolean queued = Hal.runEye((g, g2) -> {
            try {
                Log.i(TAG, "115 uiHeartBeatSet(" + HB_NAME + ", " + (on ? 1 : 0) + ", " + HB_PERIOD_MS
                        + ") -> " + g.uiHeartBeatSet(HB_NAME, on, HB_PERIOD_MS));
            } catch (GmpfClient.NuiNotReadyException e) {
                Log.w(TAG, "115(" + (on ? 1 : 0) + ") not sent: " + e.getMessage());
                if (!on) Ui.main().post(EyeGuard::scheduleDisarmRetry);
            }
        }, null);
        if (!queued) Log.w(TAG, "115(" + (on ? 1 : 0) + "): HAL not available");
    }

    /** Main thread: the vendor watchdog may still be armed; retry 115(0) once the reader is back. */
    private static void scheduleDisarmRetry() {
        if (sHbArmed) return;                                   // re-armed meanwhile: its hide disarms
        if (++sDisarmRetries > DISARM_RETRY_MAX) {
            Log.w(TAG, "115(0) still refused after " + DISARM_RETRY_MAX + " s: giving up (the vendor "
                    + "watchdog restores the light on its own)");
            return;
        }
        Ui.main().removeCallbacks(DISARM_RETRY);
        Ui.main().postDelayed(DISARM_RETRY, 1000);
    }

    private static final Runnable DISARM_RETRY = () -> {
        try {
            if (sHbArmed) return;
            if (!GmpfClient.nuiReaderReady()) { scheduleDisarmRetry(); return; }
            send115(false);
        } catch (Throwable t) {
            Log.w(TAG, "115(0) retry: " + t);
        }
    };

    // ------------------------------------------------------------------ views
    /**
     * Shared key handling: takes key focus and eats every key; the UP of a restore key whose DOWN
     * also came to this window restores the light (a press that began elsewhere is ignored).
     */
    private static boolean handleWindowKey(KeyEvent e, boolean okKeysToo, int[] downCode) {
        try {
            int code = e.getKeyCode();
            if (isRestoreKey(code, okKeysToo)) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) downCode[0] = code;
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    boolean go = downCode[0] == code && !e.isCanceled();
                    downCode[0] = 0;
                    if (go) restoreByUser("key " + KeyEvent.keyCodeToString(code), false);
                }
            } else if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                Log.i(TAG, "key " + KeyEvent.keyCodeToString(code) + " eaten (Back restores the picture)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "key: " + t);
        }
        return true;
    }

    /**
     * Opaque black card in the stock HDNativeUI_MultTof layout (1920x1080 design px, scaled by
     * width/1920): #242424 disc of 128 px centred at y=459 with our vector eye, title #B3B3B3
     * 40 px, text #999999 30 px, hint #808080 28 px about 56 px above the bottom. Localized text.
     */
    private static final class MaskView extends FrameLayout {
        private final int[] down = new int[1];

        MaskView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setDefaultFocusHighlightEnabled(false);
            float s = c.getResources().getDisplayMetrics().widthPixels / 1920f;

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);

            FrameLayout disc = new FrameLayout(c);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0xFF242424);
            disc.setBackground(circle);
            ImageView eye = new ImageView(c);
            eye.setImageResource(R.drawable.ic_eye_mask);
            eye.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int icon = Math.round(64 * s);
            disc.addView(eye, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.round(128 * s), Math.round(128 * s));
            dlp.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(disc, dlp);

            int maxW = Math.round(1400 * s);
            col.addView(text(c, R.string.eye_mask_title, 0xFFB3B3B3, 40 * s, true, Math.round(60 * s), maxW));
            col.addView(text(c, R.string.eye_mask_desc, 0xFF999999, 30 * s, false, Math.round(24 * s), maxW));
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            clp.bottomMargin = Math.round(60 * s);                     // disc centre near y=459 like stock
            addView(col, clp);

            TextView hint = text(c, R.string.eye_mask_hint, 0xFF808080, 28 * s, false, 0, Math.round(1600 * s));
            FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            hp.bottomMargin = Math.round(56 * s);
            addView(hint, hp);
        }

        private static TextView text(Context c, int res, int color, float px, boolean bold, int top, int maxW) {
            TextView t = new TextView(c);
            t.setText(res);
            t.setTextColor(color);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            t.setGravity(Gravity.CENTER);
            t.setMaxWidth(maxW);
            t.setMaxLines(3);
            if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = top;
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            t.setLayoutParams(lp);
            return t;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            return handleWindowKey(e, true, down);
        }
    }

    /** 503 (cutout models): transparent, draws nothing, takes key focus; BACK (UP) restores. */
    private static final class KeyLayer extends View {
        private final int[] down = new int[1];

        KeyLayer(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setDefaultFocusHighlightEnabled(false);
            setWillNotDraw(true);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent e) {
            return handleWindowKey(e, false, down);
        }
    }
}
