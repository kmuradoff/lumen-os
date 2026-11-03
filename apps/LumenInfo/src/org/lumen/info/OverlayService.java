package org.lumen.info;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.PowerManager;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.lumen.info.data.Collector;
import org.lumen.info.data.Snapshot;
import org.lumen.info.ui.OverlayView;
import org.lumen.info.ui.Rows;

/**
 * Owns the overlay window while "Show overlay" is on (foreground service, type specialUse). One
 * TYPE_APPLICATION_OVERLAY window, not focusable and not touchable, so the remote keys stay with the app or HDMI
 * picture below; trusted overlay (INTERNAL_SYSTEM_WINDOW, platform signature) so it keeps full opacity and stays
 * visible over windows that hide non-system overlays. Hidden and not collecting while the screen is off.
 * Started and stopped from the app, the notification actions, the quick tile and ToggleActivity.
 */
public final class OverlayService extends Service implements Collector.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "LumenInfo";
    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION_ID = 1;
    static final String ACTION_LAYOUT = "org.lumen.info.action.LAYOUT";
    static final String ACTION_HIDE = "org.lumen.info.action.HIDE";

    private Prefs prefs;
    private Collector collector;
    private WindowManager wm;
    private OverlayView view;
    private WindowManager.LayoutParams lp;
    private boolean added, active, screenOn = true, receiver;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            updateActive();
        }
    };

    // ================================================================ control
    public static void setEnabled(Context c, boolean on) {
        new Prefs(c).setEnabled(on);
        Intent i = new Intent(c, OverlayService.class);
        if (on) c.startForegroundService(i);
        else c.stopService(i);
        try {
            TileService.requestListeningState(c, new ComponentName(c, OverlayTile.class));
        } catch (Throwable ignored) {
        }
    }

    public static void toggle(Context c) {
        setEnabled(c, !new Prefs(c).enabled());
    }

    // ================================================================ service
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        collector = Collector.get(this);
        wm = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        prefs.raw().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground", t);
        }
        String a = intent == null ? null : intent.getAction();
        if (ACTION_HIDE.equals(a)) {
            setEnabled(this, false);
            return START_NOT_STICKY;
        }
        if (ACTION_LAYOUT.equals(a)) prefs.setLayout(prefs.layout() == Rows.LAYOUT_FULL ? Rows.LAYOUT_VIDEO : Rows.LAYOUT_FULL);
        if (!prefs.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        show();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        prefs.raw().unregisterOnSharedPreferenceChangeListener(this);
        if (receiver) {
            try { unregisterReceiver(screen); } catch (Throwable ignored) { }
            receiver = false;
        }
        if (active) {
            active = false;
            collector.removeListener(this);
            collector.release(this);
        }
        if (added) {
            try { wm.removeViewImmediate(view); } catch (Throwable ignored) { }
            added = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (view == null || key == null) return;
        if (Prefs.K_ENABLED.equals(key)) {
            if (!prefs.enabled()) stopSelf();
            return;
        }
        if (Prefs.K_SIZE.equals(key)) view.setSize(prefs.size());
        if (Prefs.K_CORNER.equals(key)) place();
        if (Prefs.K_LAYOUT.equals(key)) {
            Snapshot s = collector.last();
            if (s != null) onSnapshot(s);
        }
    }

    // ================================================================ window
    private void show() {
        if (added) return;
        view = new OverlayView(this);
        view.setSize(prefs.size());
        view.setVisibility(View.GONE);
        int f = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT);
        lp.windowAnimations = 0;
        lp.setTitle("LumenInfoOverlay");
        if (checkSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW") == PackageManager.PERMISSION_GRANTED) {
            try { lp.setTrustedOverlay(); } catch (Throwable ignored) { }
        }
        try { lp.setFitInsetsTypes(0); } catch (Throwable ignored) { }
        place();
        try {
            wm.addView(view, lp);
            added = true;
        } catch (Throwable t) {
            Log.e(TAG, "cannot add the overlay window", t);
            prefs.setEnabled(false);
            stopSelf();
            return;
        }
        IntentFilter fl = new IntentFilter(Intent.ACTION_SCREEN_ON);
        fl.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screen, fl, Context.RECEIVER_NOT_EXPORTED);
        receiver = true;
        PowerManager pm = getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        updateActive();
    }

    private void place() {
        if (lp == null) return;
        int c = prefs.corner();
        boolean left = c == Prefs.TL || c == Prefs.BL, top = c == Prefs.TL || c == Prefs.TR;
        lp.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.LEFT : Gravity.RIGHT);
        DisplayMetrics m = getResources().getDisplayMetrics();
        int safe = Math.round(Math.min(m.widthPixels, m.heightPixels) * 0.05f);
        lp.x = safe;
        lp.y = safe;
        if (view != null) view.setMaxWidth(Math.max(1, m.widthPixels / 2 - safe));
        if (added) {
            try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) { }
        }
    }

    private void updateActive() {
        if (!added) return;
        boolean want = screenOn;
        if (want == active) return;
        active = want;
        if (want) {
            view.setVisibility(View.VISIBLE);
            collector.addListener(this);
            collector.acquire(this, false);
        } else {
            view.setVisibility(View.GONE);
            collector.removeListener(this);
            collector.release(this);
        }
    }

    @Override
    public void onSnapshot(Snapshot s) {
        if (view == null) return;
        int layout = prefs.layout();
        String head = getString(layout == Rows.LAYOUT_FULL ? R.string.o_title_full : R.string.o_title_video);
        view.setRows(head, Rows.overlay(this, s, layout));
    }

    // ================================================================ notification
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent layout = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class)
                .setAction(ACTION_LAYOUT), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent hide = PendingIntent.getService(this, 2, new Intent(this, OverlayService.class)
                .setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_info)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_layout), layout).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_hide), hide).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
