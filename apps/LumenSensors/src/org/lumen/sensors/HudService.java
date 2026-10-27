package org.lumen.sensors;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.lumen.sensors.ui.HudView;
import org.lumen.sensors.ui.Look;

/**
 * Owns the HUD window. A foreground service (type specialUse) that runs only while "Show overlay" is
 * on; BootReceiver starts it after boot when "Start at boot" is on.
 *
 * The window: ONE TYPE_APPLICATION_OVERLAY, NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN |
 * HARDWARE_ACCELERATED, translucent, no window animation (Projector's OverlayHost.params(false, ..)),
 * so keys always stay with the app below. It is a trusted overlay (privateFlags TRUSTED_OVERLAY, needs
 * INTERNAL_SYSTEM_WINDOW, granted by the platform signature): otherwise DisplayPolicy caps the alpha
 * of a NOT_TOUCHABLE system alert window at 0.8 ("setting alpha to 0.80 to let touches pass through",
 * device test of 0.1), and the Opacity setting could never reach 100 %. The same permission keeps the
 * HUD visible when another window sets HIDE_NON_SYSTEM_OVERLAY_WINDOWS (permission and installer
 * dialogs), as for Projector's OverlayHost windows; harmless, the HUD takes no touches. It is added
 * once when the service starts and removed only when the service ends; position and size changes go
 * through updateViewLayout, content through invalidate. While the screen is off or Projector is in
 * its lamp-only standby the view is GONE (the window keeps its place in the z order, so Projector's
 * AK pattern, quick panel and notice card, added later, stay above it) and the sampler stops: no work, no thread. The view is added GONE and
 * shown only once the state is known; hiding empties its rows, so a later show never draws old values.
 * Width: the HUD fits between the safe margins; while the settings screen is open, between the
 * settings panel and the right margin, for every corner (HudView shrinks to fit), so it never
 * covers the panel.
 *
 * Standby: org.z9x.projector broadcasts org.z9x.projector.action.STANDBY_CHANGED (boolean extra
 * "standby", FLAG_RECEIVER_REGISTERED_ONLY) to holders of org.z9x.projector.permission.STANDBY_STATE,
 * and answers the current value at content://org.z9x.projector.standbystate, call "standby_state"
 * (Bundle "standby"), exactly as org.z9x.tvinput reads it. A missing Projector counts as "not in standby".
 */
public final class HudService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "LumenSensors";
    private static final String CHANNEL = "hud";
    private static final int NOTIFICATION_ID = 1;

    static final String ACTION_STANDBY_CHANGED = "org.z9x.projector.action.STANDBY_CHANGED";
    static final String EXTRA_STANDBY = "standby";
    static final String PERMISSION_STANDBY = "org.z9x.projector.permission.STANDBY_STATE";
    static final String STANDBY_AUTHORITY = "org.z9x.projector.standbystate";
    static final String STANDBY_METHOD = "standby_state";
    /** Signature permission (system API constant), held for the trusted overlay flag. */
    static final String PERMISSION_INTERNAL_WINDOW = "android.permission.INTERNAL_SYSTEM_WINDOW";

    /** The running service (main thread), for the settings screen's "move the HUD aside". */
    private static HudService sInstance;
    /** Left edge (px) a HUD on the left keeps while the settings screen is open; 0 = none. */
    private static int sAvoidLeft;

    private Hub hub;
    private Prefs prefs;
    private WindowManager wm;
    private HudView view;
    private WindowManager.LayoutParams lp;
    private boolean added, active, screenOn = true, standby, receivers;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            updateActive();
        }
    };

    private final BroadcastReceiver standbyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            standby = i != null && i.getBooleanExtra(EXTRA_STANDBY, false);
            Log.i(TAG, "projector standby " + (standby ? "entered" : "left"));
            updateActive();
        }
    };

    // ================================================================== control (main thread)
    /** "Show overlay": save it and start or stop the service. */
    public static void setEnabled(Context c, boolean on) {
        Prefs p = Hub.get(c).prefs;
        p.setEnabled(on);
        Intent i = new Intent(c, HudService.class);
        if (on) {
            c.startForegroundService(i);
        } else {
            c.stopService(i);
        }
    }

    /** The settings screen opened (px = its panel's right edge) or closed (0). */
    public static void setAvoidLeft(int px) {
        if (sAvoidLeft == px) return;
        sAvoidLeft = px;
        HudService s = sInstance;
        if (s != null) s.updatePosition();
    }

    // ================================================================== Service
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        hub = Hub.get(this);
        prefs = hub.prefs;
        wm = getSystemService(WindowManager.class);
        createChannel();
        prefs.raw().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground", t);
        }
        if (!prefs.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showWindow();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        prefs.raw().unregisterOnSharedPreferenceChangeListener(this);
        if (receivers) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            try {
                unregisterReceiver(standbyReceiver);
            } catch (Throwable ignored) {
            }
            receivers = false;
        }
        if (active) {
            active = false;
            hub.removeListener(view);
            hub.release(this);
        }
        if (added) {
            try {
                wm.removeViewImmediate(view);
            } catch (Throwable t) {
                Log.w(TAG, "remove window: " + t);
            }
            added = false;
        }
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        hub.onConfigurationChanged();
        if (view != null) {
            view.onConfigChanged();
            updatePosition();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (key == null || Prefs.K_AVAILABLE.equals(key)) return;
        if (Prefs.K_ENABLED.equals(key)) {
            if (!prefs.enabled()) stopSelf();
            return;
        }
        if (view == null) return;
        applySettings();
        if (Prefs.K_POSITION.equals(key)) updatePosition();
    }

    // ================================================================== window
    private void showWindow() {
        if (added) return;
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted (platform signature expected); trying anyway");
        }
        view = new HudView(this, hub);
        view.setVisibility(View.GONE);                     // shown by updateActive once the state is known
        applySettings();
        lp = params();
        try {
            wm.addView(view, lp);
            added = true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot add the HUD window", t);
            prefs.setEnabled(false);                       // the settings row shows the real state
            stopSelf();
            return;
        }
        registerReceivers();
        PowerManager pm = getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        standby = projectorStandby();
        updateActive();
    }

    private WindowManager.LayoutParams params() {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags,
                PixelFormat.TRANSLUCENT);
        p.windowAnimations = 0;
        p.alpha = 1f;                                      // the Opacity setting is the panel's own (HudView)
        trusted(p);
        try { p.setFitInsetsTypes(0); } catch (Throwable ignored) { }
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("LumenSensorsHud");
        place(p);
        return p;
    }

    /**
     * Trusted overlay, so the system keeps alpha 1 (AOSP 14 DisplayPolicy.adjustWindowParamsLw skips
     * the 0.8 cap with PRIVATE_FLAG_TRUSTED_OVERLAY). WindowManager requires INTERNAL_SYSTEM_WINDOW for
     * that flag, so it is set only with the permission (a test-key build keeps the cap but still shows).
     */
    private void trusted(WindowManager.LayoutParams p) {
        if (checkSelfPermission(PERMISSION_INTERNAL_WINDOW) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "INTERNAL_SYSTEM_WINDOW not granted (platform signature expected): HUD capped at 80 % opacity");
            return;
        }
        try {
            p.setTrustedOverlay();
        } catch (Throwable t) {
            Log.w(TAG, "trusted overlay: " + t);
        }
    }

    /**
     * Corner from the settings, inside the 5 % safe area. While the settings screen is open the HUD
     * keeps right of its panel: a left corner moves there, and every corner gets the width between the
     * panel and the right margin (HudView.setMaxWidth), so a wide HUD never reaches over the panel.
     */
    private void place(WindowManager.LayoutParams p) {
        int pos = prefs.position();
        boolean left = pos == Prefs.POS_TOP_LEFT || pos == Prefs.POS_BOTTOM_LEFT;
        boolean top = pos == Prefs.POS_TOP_LEFT || pos == Prefs.POS_TOP_RIGHT;
        p.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.LEFT : Gravity.RIGHT);
        int safe = Look.px(this, Look.SAFE);
        int avoid = sAvoidLeft;
        p.x = left ? Math.max(safe, avoid) : safe;
        p.y = safe;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int displayW = Math.max(m.widthPixels, m.heightPixels);
        int maxW = avoid > 0 ? displayW - Math.max(safe, avoid) - safe : displayW - 2 * safe;
        if (view != null) view.setMaxWidth(Math.max(1, maxW));
    }

    private void updatePosition() {
        if (!added || lp == null) return;
        place(lp);
        try {
            wm.updateViewLayout(view, lp);
        } catch (Throwable t) {
            Log.w(TAG, "update window: " + t);
        }
    }

    private void applySettings() {
        view.applySettings(prefs.mode(), prefs.groups(), prefs.opacityPct(), prefs.sizeScale(), prefs.refreshMs());
    }

    /** On screen and sampling only while the screen is on and Projector is not in its lamp-only standby. */
    private void updateActive() {
        if (!added) return;
        boolean want = screenOn && !standby;
        if (want == active) return;
        active = want;
        if (want) {
            boolean current = hub.running();               // the settings screen keeps the registry current
            view.setVisibility(View.VISIBLE);
            hub.addListener(view);
            hub.acquire(this, true);
            if (current) view.onSamples();                 // else the rows come with the first publish
        } else {
            view.setVisibility(View.GONE);
            hub.removeListener(view);
            hub.release(this);
            view.reset();                                  // nothing old is drawn at the next show
        }
        Log.i(TAG, "HUD " + (want ? "shown" : "hidden (screen off or standby)"));
    }

    private void registerReceivers() {
        if (receivers) return;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        // only org.z9x.projector (holder of the signature permission) can send it
        registerReceiver(standbyReceiver, new IntentFilter(ACTION_STANDBY_CHANGED), PERMISSION_STANDBY, null,
                Context.RECEIVER_EXPORTED);
        receivers = true;
    }

    /** Unstable client (as Z9xHome's ProjectorBridge.standby): Projector dying here never kills this process. */
    private boolean projectorStandby() {
        try (ContentProviderClient cl = getContentResolver().acquireUnstableContentProviderClient(
                Uri.parse("content://" + STANDBY_AUTHORITY))) {
            if (cl == null) return false;
            Bundle b = cl.call(STANDBY_METHOD, null, null);
            return b != null && b.getBoolean(EXTRA_STANDBY, false);
        } catch (Throwable t) {
            Log.i(TAG, "projector standby state not available: " + t);
            return false;
        }
    }

    // ================================================================== notification
    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sensors)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
