/*
 * AirPlay receiver service: owns the native UxPlay server (libz9xairplay), the mDNS
 * advertisement, the session state shown by MirrorActivity, the "AirPlay video" player,
 * audio focus (AirPlay yields to Google Cast and other media apps), the Wi-Fi/wake locks
 * and the MediaSession for the remote's media keys.
 *
 * Foreground service of type connectedDevice (prerequisite CHANGE_WIFI_MULTICAST_STATE),
 * started at boot (BootReceiver), by SettingsActivity and by WatchdogJob (which brings it
 * back when its process is gone but not marked "bad"; see WatchdogJob for what it cannot
 * do). Not android:persistent on purpose (no endless crash loop; see AndroidManifest.xml).
 * The native server is health-checked (network changes, every minute) and restarted if its
 * RTSP/HTTP thread has died; the same check re-registers a missing mDNS service.
 * v1.1: a failed start (library load, NativeBridge.create, no free port 7000-7010, any
 * exception) is never final: it is retried after 5 s, 15 s, 60 s and then every 5 min (sooner
 * when a network comes up, or when WatchdogJob runs), and the notification / settings status
 * say so, so the projector never stays un-advertised silently. Likewise, while RUNNING but not
 * advertised over mDNS for 30 s (NsdPublisher.notVisible: registration failing / stuck / no
 * network), the notification and the settings status say "not visible on the network, retrying".
 *
 * Ported to Java from jqssun/android-airplay-server v0.0.31 service/AirPlayService.kt
 * (GPL-3.0), reduced to what the Z9X needs: video and audio are rendered natively, the
 * Compose UI is replaced by MirrorActivity, Media3 by HlsPlayer (MediaPlayer).
 *
 * Threading: everything here runs on the main thread except the native lifecycle calls
 * (create/start/stop/destroy/disconnectAll), which run on the "airplay-ctl" thread. The native
 * handle is guarded by nlock: main-thread natives take the read lock, swapping the handle
 * takes the write lock.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.OsConstants;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class AirPlayService extends Service {
    static final String TAG = "Z9xAirPlay";

    static final String ACTION_START = "org.z9x.airplay.action.START";
    static final String ACTION_RELOAD = "org.z9x.airplay.action.RELOAD";

    private static final String CHANNEL = "airplay";
    private static final int NOTIFICATION_ID = 1;
    private static final int FIRST_PORT = 7000, LAST_PORT = 7010;
    private static final int FPS = 60;
    private static final long NET_SETTLE_MS = 2000;
    private static final long HEALTH_CHECK_MS = 60_000;
    /** Back-off of the start retries: 5 s, 15 s, 60 s, then every 5 min. */
    private static final long[] START_RETRY_MS = {5_000, 15_000, 60_000, 5 * 60_000};
    /* a code screen closed without pairing is not brought back by pair-pin-start for this long
       (any admitted LAN host could otherwise pop it over whatever is playing, again and again) */
    private static final long PIN_QUIET_MS = 30_000;

    enum ServerState { STOPPED, STARTING, RUNNING, ERROR }

    /** UI listener (main thread). */
    interface Listener { void onAirPlayStateChanged(); }

    private static AirPlayService sInstance;

    /** The running service or null (main thread). */
    static AirPlayService instance() { return sInstance; }

    /** Starts (or reloads) the service if AirPlay is enabled, stops it otherwise. */
    static void reload(Context c) {
        Intent i = new Intent(c, AirPlayService.class).setAction(ACTION_RELOAD);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            Log.w(TAG, "start service: " + t);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread ctlThread;
    private Handler ctl;

    private final ReentrantReadWriteLock nlock = new ReentrantReadWriteLock();
    private volatile long handle;           // written under nlock's write lock
    private int gen;                        // main thread: generation of the current server
    private Prefs.Config config;            // config of the running / starting server
    private int startFailures;              // main thread: failed starts since the last success

    // ---- server state (main thread) ----
    ServerState serverState = ServerState.STOPPED;
    int port;
    String advertisedName = "";

    // ---- session state (main thread) ----
    int connections;
    boolean mirroring, firstFrame, audioOnly, videoActive, videoFailed;
    String pin;
    long pinLockedUntil;                    // elapsedRealtime; PIN pairing locked until then
    private long pinDismissedAt = -PIN_QUIET_MS;   // elapsedRealtime a code screen was closed unpaired
    int mirrorW, mirrorH, videoW, videoH;
    Dmap.Track track;
    Bitmap cover;
    long progressPosMs, progressDurMs, progressAt;
    boolean audioPlaying;
    private boolean sessionLocks;

    /** Settings.Global device_name changes (Lumen Setup, Android Settings): re-advertise, debounced. */
    private static final long NAME_DEBOUNCE_MS = 500;
    private static final long NAME_SESSION_RETRY_MS = 30_000;
    private ContentObserver nameObserver;
    private final Runnable nameChanged = this::onDeviceNameChanged;

    /** UI listeners; static so a screen can register before the service instance exists. */
    private static final ArrayList<Listener> sListeners = new ArrayList<>();
    private final Set<String> trusted = ConcurrentHashMap.newKeySet();

    private SharedPreferences prefs;
    private NsdPublisher nsd;
    private HlsPlayer hls;
    private Dacp dacp;
    private MediaSession mediaSession;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean hasFocus, focusMuted;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback netCallback;
    private final Map<Network, String> netAddrs = new HashMap<>();
    private String lastNetSig;

    // surface routing (main thread): who renders into the MirrorActivity SurfaceView
    private static final int OWNER_NONE = 0, OWNER_NATIVE = 1, OWNER_PLAYER = 2;
    private Surface surface;
    private Surface appliedSurface;
    private long appliedHandle;             // native server the surface was given to
    private int surfaceOwner = OWNER_NONE;
    private String notifText;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        prefs = Prefs.get(this);
        trusted.addAll(Prefs.trusted(prefs));
        audio = getSystemService(AudioManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        nsd = new NsdPublisher(this, main, () -> {
            // advertised state changed: "not visible on the network" in the notification / settings
            updateNotification();
            notifyListeners();
        });
        dacp = new Dacp(this);
        hls = new HlsPlayer(main, playerListener);
        ctlThread = new HandlerThread("airplay-ctl");
        ctlThread.start();
        ctl = new Handler(ctlThread.getLooper());
        createChannel();
        setupMediaSession();
        watchDeviceName();
        notifyListeners();
    }

    private void watchDeviceName() {
        try {
            nameObserver = new ContentObserver(main) {
                @Override public void onChange(boolean selfChange, Uri uri) {
                    main.removeCallbacks(nameChanged);
                    main.postDelayed(nameChanged, NAME_DEBOUNCE_MS);
                }
            };
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVICE_NAME),
                    false, nameObserver);
        } catch (Throwable t) {
            nameObserver = null;
            Log.w(TAG, "device name observer: " + t);
        }
    }

    /** Main thread: the device name changed; the receiver is re-created (new mDNS records) if it is ours. */
    private void onDeviceNameChanged() {
        if (config == null || serverState == ServerState.STOPPED) return;      // not running: next start reads it
        if (new Prefs.Config(this).sameAs(config)) return;                     // an AirPlay-only name is set
        if (sessionActive()) {
            // never cut a running mirror / audio session for a rename: retry later
            main.postDelayed(nameChanged, NAME_SESSION_RETRY_MS);
            return;
        }
        Log.i(TAG, "device name changed: re-advertising");
        applyConfig();
    }

    /** True while PIN pairing is locked after repeated wrong codes. */
    boolean pinLocked() {
        return pinLockedUntil > SystemClock.elapsedRealtime();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // a service started with startForegroundService must call startForeground, even to stop
        promote();
        if (!prefs.getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED)) {
            Log.i(TAG, "AirPlay is turned off");
            WatchdogJob.cancel(this);
            shutdownServer();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        WatchdogJob.schedule(this);
        applyConfig();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(nameChanged);
        if (nameObserver != null) {
            try { getContentResolver().unregisterContentObserver(nameObserver); } catch (Throwable ignored) { }
            nameObserver = null;
        }
        shutdownServer();
        ctl.post(() -> ctlThread.quitSafely());
        if (mediaSession != null) mediaSession.release();
        dacp.release();
        if (sInstance == this) sInstance = null;
        notifyListeners();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ server control

    /** (Re)creates the native server when the settings differ from the running one. */
    private void applyConfig() {
        Prefs.Config c = new Prefs.Config(this);
        if (c.sameAs(config) && (serverState == ServerState.RUNNING || serverState == ServerState.STARTING)) {
            return;
        }
        boolean restart = serverState != ServerState.STOPPED;
        if (config != null && !c.sameAs(config)) startFailures = 0;   // new settings: fresh back-off
        main.removeCallbacks(retryStart);
        config = c;
        final int g = ++gen;
        if (restart) {
            if (serverState != ServerState.ERROR) Log.i(TAG, "settings changed, restarting the receiver");
            endSession(true);
            nsd.unpublish();
            ctl.post(this::stopNative);
        }
        serverState = ServerState.STARTING;
        acquireMulticast();
        startNetworkWatch();
        notifyListeners();
        ctl.post(() -> startNative(c, g));
    }

    private void shutdownServer() {
        boolean wasUp = serverState != ServerState.STOPPED;
        gen++;
        serverState = ServerState.STOPPED;   // first, so no notification is re-posted while stopping
        config = null;
        main.removeCallbacks(healthCheck);
        main.removeCallbacks(retryStart);
        startFailures = 0;
        endSession(true);
        nsd.unpublish();
        stopNetworkWatch();
        releaseMulticast();
        if (wasUp) ctl.post(this::stopNative);
        notifyListeners();
    }

    /** ctl thread. Any failure ends in onStartFailed (which schedules a retry), never silently. */
    private void startNative(Prefs.Config c, int g) {
        long hd = 0;
        boolean started = false;
        try {
            if (!NativeBridge.load()) {
                main.post(() -> onStartFailed(g, "native library not loaded"));
                return;
            }
            int w = c.hevc4k ? 3840 : 1920, h = c.hevc4k ? 2160 : 1080;
            String key = new File(getFilesDir(), "airplay.pem").getAbsolutePath();
            hd = NativeBridge.create(new Cb(this, g), Prefs.deviceId(this), c.name, key,
                    c.pinMode, c.fixedPin, c.nohold, c.hevc4k, c.videoUrl, w, h, FPS, c.latencyMs);
            if (hd == 0) {
                main.post(() -> onStartFailed(g, "NativeBridge.create failed"));
                return;
            }
            int p = -1;
            for (int candidate = FIRST_PORT; candidate <= LAST_PORT && p <= 0; candidate++) {
                p = NativeBridge.start(hd, candidate);
            }
            if (p <= 0) {
                long dead = hd;
                hd = 0;
                NativeBridge.destroy(dead);
                main.post(() -> onStartFailed(g, "no free port in " + FIRST_PORT + ".." + LAST_PORT));
                return;
            }
            started = true;
            final String[] raopTxt = NativeBridge.txt(hd, NativeBridge.SERVICE_RAOP);
            final String[] airplayTxt = NativeBridge.txt(hd, NativeBridge.SERVICE_AIRPLAY);
            final String raopName = NativeBridge.raopServiceName(hd);
            nlock.writeLock().lock();
            try {
                handle = hd;
            } finally {
                nlock.writeLock().unlock();
            }
            hd = 0;   // owned by `handle` now (stopNative releases it)
            final int bound = p;
            main.post(() -> onStarted(g, c, bound, raopName, raopTxt, airplayTxt));
        } catch (Throwable t) {
            Log.e(TAG, "native start", t);
            if (hd != 0) {
                try {
                    if (started) NativeBridge.stop(hd);
                    NativeBridge.destroy(hd);
                } catch (Throwable t2) {
                    Log.w(TAG, "native cleanup: " + t2);
                }
            }
            final String why = String.valueOf(t);
            main.post(() -> onStartFailed(g, why));
        }
    }

    /** ctl thread: stop and destroy the current native server, if any. */
    private void stopNative() {
        long hd;
        nlock.writeLock().lock();   // waits for main-thread natives in flight
        try {
            hd = handle;
            handle = 0;
        } finally {
            nlock.writeLock().unlock();
        }
        if (hd == 0) return;
        NativeBridge.stop(hd);
        NativeBridge.destroy(hd);   // releases the display surface it held
        main.post(() -> {
            if (appliedHandle == hd) appliedHandle = 0;   // that decoder released the window
            routeSurface();
        });
    }

    private void onStarted(int g, Prefs.Config c, int p, String raopName, String[] raopTxt, String[] airplayTxt) {
        if (g != gen) return;   // superseded; its stopNative is already queued
        if (startFailures > 0) Log.i(TAG, "AirPlay receiver started after " + startFailures + " failed attempt(s)");
        startFailures = 0;
        main.removeCallbacks(retryStart);
        serverState = ServerState.RUNNING;
        port = p;
        advertisedName = c.name;
        String rn = raopName != null && !raopName.isEmpty() ? raopName : c.name;
        nsd.publish(c.name, rn, p, raopTxt, airplayTxt);
        Log.i(TAG, "AirPlay receiver '" + c.name + "' on port " + p);
        updateNotification();
        routeSurface();
        notifyListeners();
        main.removeCallbacks(healthCheck);
        main.postDelayed(healthCheck, HEALTH_CHECK_MS);
    }

    private final Runnable healthCheck = this::checkServerHealth;

    /**
     * Main thread: asks the native server (on the ctl thread) whether its RTSP/HTTP thread is
     * still serving, and restarts the receiver if it is not: it would otherwise stay
     * advertised and show RUNNING while accepting nothing. Runs every HEALTH_CHECK_MS and on
     * network changes.
     */
    private void checkServerHealth() {
        main.removeCallbacks(healthCheck);
        if (serverState != ServerState.RUNNING) return;
        main.postDelayed(healthCheck, HEALTH_CHECK_MS);
        nsd.check();   // a registration that is missing or never completed: register it again
        final int g = gen;
        ctl.post(() -> {
            boolean alive;
            nlock.readLock().lock();
            try {
                alive = handle == 0 || NativeBridge.isRunning(handle);
            } finally {
                nlock.readLock().unlock();
            }
            if (alive) return;
            main.post(() -> {
                if (g != gen || serverState != ServerState.RUNNING) return;
                Log.w(TAG, "the AirPlay server thread has stopped; restarting the receiver");
                config = null;   // forces applyConfig to recreate the native server
                applyConfig();
            });
        });
    }

    private void onStartFailed(int g, String why) {
        if (g != gen) return;
        serverState = ServerState.ERROR;
        long delay = START_RETRY_MS[Math.min(startFailures, START_RETRY_MS.length - 1)];
        startFailures++;
        Log.e(TAG, "AirPlay receiver could not start (" + why + "), failure " + startFailures
                + ", retrying in " + delay / 1000 + " s");
        main.removeCallbacks(retryStart);
        main.postDelayed(retryStart, delay);
        updateNotification();
        notifyListeners();
    }

    /** Main thread: a failed start is retried (back-off in START_RETRY_MS) as long as AirPlay is on. */
    private final Runnable retryStart = () -> {
        if (serverState != ServerState.ERROR) return;
        if (!prefs.getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED)) return;
        Log.i(TAG, "retrying the AirPlay receiver start (attempt " + (startFailures + 1) + ")");
        config = null;   // forces applyConfig to create the native server again
        applyConfig();
    };

    /** WatchdogJob / a network coming up: retry a failed start now instead of at the back-off time. */
    void retryIfFailed(String why) {
        if (serverState != ServerState.ERROR) return;
        Log.i(TAG, "start retry brought forward: " + why);
        main.removeCallbacks(retryStart);
        main.postDelayed(retryStart, NET_SETTLE_MS);
    }

    // ------------------------------------------------------------------ native helpers

    private void nativeSetSurface(Surface s) {
        nlock.readLock().lock();
        try {
            long h = handle;
            if (h != 0) NativeBridge.setSurface(h, s);
            appliedHandle = s != null ? h : 0;
        } finally {
            nlock.readLock().unlock();
        }
    }

    private void nativeMute(boolean m) {
        nlock.readLock().lock();
        try {
            if (handle != 0) NativeBridge.setOutputMuted(handle, m);
        } finally {
            nlock.readLock().unlock();
        }
    }

    private void nativePlaybackInfo(float pos, float dur, float rate, boolean ready) {
        if (!nlock.readLock().tryLock()) return;   // never block the UI on a restart
        try {
            if (handle != 0) NativeBridge.updatePlaybackInfo(handle, pos, dur, rate, ready);
        } finally {
            nlock.readLock().unlock();
        }
    }

    private void disconnectAllAsync() {
        ctl.post(() -> {
            nlock.readLock().lock();
            try {
                if (handle != 0) NativeBridge.disconnectAll(handle);
            } finally {
                nlock.readLock().unlock();
            }
        });
    }

    // ------------------------------------------------------------------ native callbacks

    /**
     * NativeBridge.Callback for one server generation. Runs on native threads: only posts to
     * the main thread (stale generations are ignored there). Every method is declared here,
     * because the native side looks them up on this class.
     */
    static final class Cb implements NativeBridge.Callback {
        private final AirPlayService s;
        private final int g;

        Cb(AirPlayService s, int g) {
            this.s = s;
            this.g = g;
        }

        private void post(Runnable r) {
            s.main.post(() -> {
                if (g == s.gen) r.run();
            });
        }

        @Override public void onEvent(int what, int a, int b) { post(() -> s.onNativeEvent(what, a, b)); }
        @Override public void onPin(String pin) { post(() -> s.onPin(pin)); }
        @Override public void onMetadata(byte[] dmap) {
            Dmap.Track t = Dmap.track(dmap);
            post(() -> s.onTrack(t));
        }
        @Override public void onCoverArt(byte[] image) {
            Bitmap bmp = decodeCover(image);
            post(() -> s.onCover(bmp));
        }
        @Override public void onProgress(long start, long current, long end) {
            post(() -> s.onProgress(start, current, end));
        }
        @Override public void onDacp(String dacpId, String activeRemote) {
            post(() -> s.dacp.update(dacpId, activeRemote));
        }
        @Override public void onVideoPlay(String url, float startSec) { post(() -> s.onVideoPlay(url, startSec)); }
        @Override public void onVideoScrub(float sec) { post(() -> s.hls.scrub(sec)); }
        @Override public void onVideoRate(float rate) { post(() -> s.hls.setRate(rate)); }
        @Override public void onVideoStop() { post(() -> s.endVideo(false)); }
        @Override public boolean isTrustedClient(String pk) { return pk != null && s.trusted.contains(pk); }
        @Override public void onTrustClient(String deviceId, String pk, String name) {
            if (pk == null || pk.isEmpty()) return;
            Log.i(TAG, "trusted sender " + name + " (" + deviceId + ")");
            s.trusted.add(pk);
            post(s::saveTrusted);
        }
    }

    private static Bitmap decodeCover(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / sample > 1024 || o.outHeight / sample > 1024) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    private void onNativeEvent(int what, int a, int b) {
        switch (what) {
            case NativeBridge.EV_CONN_INIT:
                connections = a;
                break;
            case NativeBridge.EV_CONN_DESTROY:
                connections = a;
                if (a == 0) {
                    Log.i(TAG, "sender disconnected");
                    endSession(false);
                }
                break;
            case NativeBridge.EV_CONN_RESET:
                Log.i(TAG, a == NativeBridge.RESET_NO_FEEDBACK
                        ? "the sender stopped responding (no /feedback), disconnecting"
                        : "connection reset (" + a + "), disconnecting");
                endVideo(true);
                disconnectAllAsync();
                break;
            case NativeBridge.EV_MIRROR_ON:
                pin = null;
                // a previous sender's "AirPlay video" must not keep the surface (mirroring
                // would only decode into the hidden sink)
                endVideo(true);
                mirroring = true;
                firstFrame = false;
                launchUi();
                break;
            case NativeBridge.EV_MIRROR_OFF:
                mirroring = false;
                firstFrame = false;
                mirrorW = mirrorH = 0;
                break;
            case NativeBridge.EV_AUDIO_FORMAT:
                pin = null;
                if (b == 0 && !audioOnly) {   // music / podcast only, not mirroring audio
                    audioOnly = true;
                    audioPlaying = true;
                    if (prefs.getBoolean(Prefs.MUSIC_SCREEN, Prefs.DEF_MUSIC_SCREEN)) launchUi();
                }
                break;
            case NativeBridge.EV_AUDIO_TEARDOWN:
                if (audioOnly) {
                    audioOnly = false;
                    clearTrack();
                }
                break;
            case NativeBridge.EV_VIDEO_SIZE:
                pin = null;
                mirrorW = a;
                mirrorH = b;
                break;
            case NativeBridge.EV_FIRST_FRAME:
                firstFrame = true;
                break;
            case NativeBridge.EV_PIN_LOCKOUT:
                Log.w(TAG, "PIN pairing locked for " + a + " s after repeated wrong codes");
                pin = null;
                pinLockedUntil = SystemClock.elapsedRealtime() + a * 1000L;
                main.removeCallbacks(lockoutOver);
                main.postDelayed(lockoutOver, a * 1000L + 100);
                // no launchUi(): a PIN screen already up shows the lock; a locked
                // pair-pin-start must not let anyone pop a screen over what is playing
                break;
            case NativeBridge.EV_VOLUME:
            default:
                return;   // nothing visible changes
        }
        sessionChanged();
    }

    private final Runnable lockoutOver = this::sessionChanged;

    private void onPin(String p) {
        if (p == null || p.isEmpty()) return;
        pinLockedUntil = 0;
        if (p.equals(pin)) return;   // repeated pair-pin-start: the same code is already up
        if (SystemClock.elapsedRealtime() - pinDismissedAt < PIN_QUIET_MS) {
            Log.w(TAG, "pair-pin-start ignored: a code screen was closed less than "
                    + PIN_QUIET_MS / 1000 + " s ago");
            return;
        }
        pin = p;
        launchUi();
        sessionChanged();
    }

    /** The receiver screen showing a code was closed (BACK, HOME, another app) without pairing. */
    void pinScreenClosed() {
        if (pin == null) return;
        pin = null;
        pinDismissedAt = SystemClock.elapsedRealtime();
        sessionChanged();
    }

    private void onTrack(Dmap.Track t) {
        track = t;
        if (t.durationMs > 0 && progressDurMs <= 0) progressDurMs = t.durationMs;
        updateMediaSession();
        notifyListeners();
    }

    private void onCover(Bitmap bmp) {
        cover = bmp;
        updateMediaSession();
        notifyListeners();
    }

    private void onProgress(long start, long cur, long end) {
        long pos = ((cur - start) & 0xFFFFFFFFL) * 1000L / 44100L;
        long dur = ((end - start) & 0xFFFFFFFFL) * 1000L / 44100L;
        if (dur <= 0 || pos > dur + 5000) return;   // degenerate values around pause/resume
        progressPosMs = pos;
        progressDurMs = dur;
        progressAt = SystemClock.elapsedRealtime();
        audioPlaying = true;
        updateMediaSession();
        notifyListeners();
    }

    /** Extrapolated audio position for the progress bar. */
    long audioPositionMs() {
        if (progressAt == 0) return 0;
        long p = progressPosMs + (audioPlaying ? SystemClock.elapsedRealtime() - progressAt : 0);
        return progressDurMs > 0 ? Math.min(p, progressDurMs) : p;
    }

    private void saveTrusted() {
        prefs.edit().putStringSet(Prefs.TRUSTED, new TreeSet<>(trusted)).apply();
    }

    int trustedCount() {
        return trusted.size();
    }

    static void forgetTrusted(Context c) {
        Prefs.get(c).edit().remove(Prefs.TRUSTED).apply();
        AirPlayService s = sInstance;
        if (s != null) s.trusted.clear();
    }

    // ------------------------------------------------------------------ "AirPlay video"

    private void onVideoPlay(String url, float startSec) {
        if (url == null || url.isEmpty()) return;
        Log.i(TAG, "video play @" + startSec + "s");
        pin = null;
        videoActive = true;
        videoFailed = false;
        videoW = videoH = 0;
        hls.play(url, startSec);
        routeSurface();
        launchUi();
        sessionChanged();
    }

    /** Ends "AirPlay video"; reportStopped pushes UxPlay's "finished" sentinel. */
    void endVideo(boolean reportStopped) {
        if (!videoActive) return;
        videoActive = false;
        hls.release();
        if (reportStopped) nativePlaybackInfo(0f, -1f, 0f, false);
        routeSurface();
        sessionChanged();
    }

    private final HlsPlayer.Listener playerListener = new HlsPlayer.Listener() {
        @Override public void onPlaybackInfo(float pos, float dur, float rate, boolean ready) {
            nativePlaybackInfo(pos, dur, rate, ready);
            if (videoActive) updateMediaSession();
        }
        @Override public void onVideoSize(int w, int h) {
            videoW = w;
            videoH = h;
            notifyListeners();
        }
        @Override public void onEnded(boolean error) {
            if (!videoActive) return;
            videoActive = false;
            videoFailed = error;
            nativePlaybackInfo(0f, -1f, 0f, false);
            routeSurface();
            sessionChanged();
        }
        @Override public void onStateChanged() {
            notifyListeners();
        }
    };

    HlsPlayer player() {
        return hls;
    }

    // ------------------------------------------------------------------ session

    boolean sessionActive() {
        return mirroring || audioOnly || videoActive;
    }

    /** BACK on the receiver screen: end the session for every sender. */
    void userStop() {
        Log.i(TAG, "stopped on the projector");
        if (pin != null) pinDismissedAt = SystemClock.elapsedRealtime();
        endVideo(true);
        disconnectAllAsync();
        endSession(false);
    }

    /** Clears everything about the current session (sender gone, server restart or stop). */
    private void endSession(boolean serverGoing) {
        if (videoActive) {
            videoActive = false;
            hls.release();
            if (!serverGoing) nativePlaybackInfo(0f, -1f, 0f, false);
        }
        mirroring = false;
        firstFrame = false;
        audioOnly = false;
        pin = null;
        if (serverGoing) {
            pinLockedUntil = 0;   // a new server starts unlocked
            main.removeCallbacks(lockoutOver);
        }
        mirrorW = mirrorH = videoW = videoH = 0;
        if (serverGoing) connections = 0;
        clearTrack();
        dacp.reset();
        routeSurface();
        sessionChanged();
    }

    private void clearTrack() {
        track = null;
        cover = null;
        progressPosMs = progressDurMs = progressAt = 0;
        audioPlaying = false;
        updateMediaSession();
    }

    private void sessionChanged() {
        boolean active = sessionActive();
        if (active != sessionLocks) {
            sessionLocks = active;
            if (active) {
                acquireSessionLocks();
                requestFocus();
            } else {
                releaseSessionLocks();
                abandonFocus();
            }
        }
        updateMediaSession();
        updateNotification();
        notifyListeners();
    }

    private void launchUi() {
        try {
            startActivity(new Intent(this, MirrorActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Throwable t) {
            Log.w(TAG, "cannot show the receiver screen: " + t);
        }
    }

    static void addListener(Listener l) {
        if (!sListeners.contains(l)) sListeners.add(l);
    }

    static void removeListener(Listener l) {
        sListeners.remove(l);
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(sListeners)) l.onAirPlayStateChanged();
    }

    // ------------------------------------------------------------------ surface routing

    /** MirrorActivity's SurfaceView surface (null when destroyed). Blocks until detached. */
    void setDisplaySurface(Surface s) {
        surface = s;
        routeSurface();
    }

    /**
     * surfaceDestroyed of one MirrorActivity instance: detaches only if that instance's Surface
     * is still the current one. A finishing instance must not detach a newer instance's
     * Surface (singleTask can still briefly give two instances when the old one is finishing).
     */
    void releaseDisplaySurface(Surface s) {
        if (s != null && surface == s) setDisplaySurface(null);
    }

    private void routeSurface() {
        Surface s = surface != null && surface.isValid() ? surface : null;
        int target = s == null ? OWNER_NONE : (videoActive ? OWNER_PLAYER : OWNER_NATIVE);
        if (target == surfaceOwner && s == appliedSurface
                && (target != OWNER_NATIVE || appliedHandle == handle)) {
            return;
        }
        // detach from the previous owner first: a Surface has a single producer
        if (surfaceOwner == OWNER_PLAYER) {
            hls.setSurface(null);
        } else if (surfaceOwner == OWNER_NATIVE && target != OWNER_NATIVE && appliedHandle != 0) {
            nativeSetSurface(null);
        }
        if (target == OWNER_NATIVE) {
            if (hls.active()) hls.release();   // MediaPlayer must let go of the Surface first
            nativeSetSurface(s);
        } else if (target == OWNER_PLAYER) {
            hls.setSurface(s);
        }
        surfaceOwner = target;
        appliedSurface = s;
    }

    // ------------------------------------------------------------------ audio focus

    private void requestFocus() {
        if (hasFocus) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(this::onFocusChange, main)
                    .build();
        }
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        if (!hasFocus) Log.w(TAG, "audio focus not granted");
        setFocusMuted(false);   // a new session always starts audible
    }

    /** Keeps a focus-loss mute until the next session, so a yielding session goes quiet at once. */
    private void abandonFocus() {
        if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        hasFocus = false;
    }

    private void onFocusChange(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // another media app (e.g. a Google Cast session) took over: AirPlay yields
                Log.i(TAG, "audio focus lost, ending the AirPlay session");
                hasFocus = false;
                setFocusMuted(true);
                userStop();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                setFocusMuted(true);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                hasFocus = true;
                setFocusMuted(false);
                break;
            default:
                break;   // ducking: keep playing at the sender's volume
        }
    }

    private void setFocusMuted(boolean m) {
        if (focusMuted == m) return;
        focusMuted = m;
        nativeMute(m);
    }

    // ------------------------------------------------------------------ locks

    private void acquireMulticast() {
        if (multicastLock != null) return;
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            multicastLock = wm.createMulticastLock("z9x-airplay-mdns");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock: " + t);
            multicastLock = null;
        }
    }

    private void releaseMulticast() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
    }

    private void acquireSessionLocks() {
        try {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager.class)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "z9x-airplay:session");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(6 * 60 * 60 * 1000L);
            if (wifiLock == null) {
                wifiLock = getSystemService(WifiManager.class)
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "z9x-airplay:session");
                wifiLock.setReferenceCounted(false);
            }
            wifiLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "session locks: " + t);
        }
    }

    private void releaseSessionLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable t) {
            Log.w(TAG, "release locks: " + t);
        }
    }

    // ------------------------------------------------------------------ network changes

    private void startNetworkWatch() {
        if (netCallback != null) return;
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                String sig = linkSignature(lp);
                main.post(() -> {
                    netAddrs.put(n, sig);
                    netChanged();
                });
            }
            @Override public void onLost(Network n) {
                main.post(() -> {
                    netAddrs.remove(n);
                    netChanged();
                });
            }
        };
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();   // keeps NOT_VPN: a VPN's addresses are not where senders are
        try {
            connectivity.registerNetworkCallback(req, netCallback);
        } catch (Throwable t) {
            Log.w(TAG, "network callback: " + t);
            netCallback = null;
        }
    }

    private void stopNetworkWatch() {
        main.removeCallbacks(republish);
        if (netCallback != null) {
            try {
                connectivity.unregisterNetworkCallback(netCallback);
            } catch (Throwable ignored) {
            }
            netCallback = null;
        }
        netAddrs.clear();
        lastNetSig = null;
    }

    private final Runnable republish = () -> {
        if (serverState != ServerState.RUNNING) return;
        Log.i(TAG, "network changed, re-announcing AirPlay");
        nsd.republish();
        checkServerHealth();
    };

    /**
     * Interface + IPv4 addresses + stable IPv6 addresses. IPv6 temporary (privacy) and
     * deprecated addresses rotate on their own and NsdManager's advertiser follows them; they
     * must not make the receiver drop out of the senders' lists for a re-announce.
     */
    private static String linkSignature(LinkProperties lp) {
        TreeSet<String> sorted = new TreeSet<>();
        int unstable = OsConstants.IFA_F_TEMPORARY | OsConstants.IFA_F_DEPRECATED | OsConstants.IFA_F_TENTATIVE;
        for (LinkAddress la : lp.getLinkAddresses()) {
            if (!(la.getAddress() instanceof Inet4Address) && (la.getFlags() & unstable) != 0) continue;
            sorted.add(la.getAddress().getHostAddress());
        }
        StringBuilder sb = new StringBuilder(String.valueOf(lp.getInterfaceName())).append(':');
        for (String a : sorted) sb.append(a).append(',');
        return sb.toString();
    }

    private void netChanged() {
        TreeSet<String> all = new TreeSet<>(netAddrs.values());
        String sig = String.join("|", all);
        // the receiver failed to start: a network that came up / changed is worth a retry now
        if (serverState == ServerState.ERROR && !netAddrs.isEmpty() && !sig.equals(lastNetSig)) {
            retryIfFailed("network changed");
        }
        // a network is up: retry a registration that failed (or gave up) while it was not
        if (!netAddrs.isEmpty() && serverState == ServerState.RUNNING) {
            nsd.ensureRegistered();
            checkServerHealth();   // httpd may have died on a network error meanwhile
        }
        if (lastNetSig == null) {   // first report after start: the initial publish covers it
            lastNetSig = sig;
            return;
        }
        if (sig.equals(lastNetSig)) return;
        lastNetSig = sig;
        main.removeCallbacks(republish);
        if (!sig.isEmpty()) main.postDelayed(republish, NET_SETTLE_MS);
    }

    // ------------------------------------------------------------------ MediaSession

    private void setupMediaSession() {
        mediaSession = new MediaSession(this, "Z9xAirPlay");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { togglePlay(true); }
            @Override public void onPause() { togglePlay(false); }
            @Override public void onSkipToNext() { if (audioOnly) dacp.next(); }
            @Override public void onSkipToPrevious() { if (audioOnly) dacp.previous(); }
            @Override public void onFastForward() { if (videoActive) hls.seekBy(10_000); }
            @Override public void onRewind() { if (videoActive) hls.seekBy(-10_000); }
            @Override public void onStop() { if (sessionActive()) userStop(); }
        }, main);
    }

    /** Remote play/pause: local for "AirPlay video", forwarded to the sender for audio. */
    void togglePlay(Boolean play) {
        if (videoActive) {
            if (play == null || play != hls.playing()) hls.togglePause();
        } else if (audioOnly) {
            if (play == null) dacp.playPause();
            else if (play) dacp.play();
            else dacp.pause();
            audioPlaying = play == null ? !audioPlaying : play;
            if (!audioPlaying) progressPosMs = audioPositionMs();
            progressAt = SystemClock.elapsedRealtime();
            updateMediaSession();
            notifyListeners();
        }
    }

    Dacp dacp() {
        return dacp;
    }

    private void updateMediaSession() {
        if (mediaSession == null) return;
        boolean active = audioOnly || videoActive;
        if (!active) {
            if (mediaSession.isActive()) mediaSession.setActive(false);
            return;
        }
        PlaybackState.Builder pb = new PlaybackState.Builder();
        if (videoActive) {
            pb.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_STOP | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND);
            pb.setState(hls.playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    hls.positionMs(), hls.playing() ? 1f : 0f, SystemClock.elapsedRealtime());
            mediaSession.setMetadata(null);
        } else {
            pb.setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP);
            pb.setState(audioPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                    audioPositionMs(), audioPlaying ? 1f : 0f, SystemClock.elapsedRealtime());
            MediaMetadata.Builder mb = new MediaMetadata.Builder();
            if (track != null) {
                mb.putString(MediaMetadata.METADATA_KEY_TITLE, track.title);
                mb.putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist);
                mb.putString(MediaMetadata.METADATA_KEY_ALBUM, track.album);
            }
            if (progressDurMs > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, progressDurMs);
            if (cover != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
            mediaSession.setMetadata(mb.build());
        }
        mediaSession.setPlaybackState(pb.build());
        if (!mediaSession.isActive()) mediaSession.setActive(true);
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private String notificationText() {
        if (serverState == ServerState.ERROR) return getString(R.string.notif_error);
        if (pin != null) return getString(R.string.notif_pin, pin);
        if (sessionActive()) return getString(R.string.notif_session);
        if (notVisible()) return getString(R.string.notif_not_visible);
        return getString(R.string.notif_ready, config != null ? config.name : Prefs.name(this));
    }

    /** RUNNING, but the mDNS advertisement has been missing for a while (retries go on). */
    boolean notVisible() {
        return serverState == ServerState.RUNNING && nsd != null && nsd.notVisible();
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    private void promote() {
        notifText = notificationText();
        try {
            startForeground(NOTIFICATION_ID, buildNotification(notifText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground: " + t);
        }
    }

    /** Re-posts the notification only when its text changed. */
    private void updateNotification() {
        if (serverState == ServerState.STOPPED) return;
        String t = notificationText();
        if (t.equals(notifText)) return;
        notifText = t;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(t));
    }
}
