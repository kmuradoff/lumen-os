package org.z9x.projector.audio;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.Hal;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.source.TifHdmiState;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v6.4 "AI sound", as stock: sound mode 3 (AUTO) + a per-app sub-mode, i.e. switching between the 4
 * real G0082 amp tables (1 Movie, 2 Music, 12 Sports, 4 Karaoke). It is not a self-adjusting EQ.
 *
 * Vendor mechanism (research/v64/aisound, libxgimi disassembly + live GSI log):
 *  - 61 setSoundeffect(3): Msrv::setSoundEffectsMode reads property xgimi.pkg.effect.mod (0 = unset)
 *    and stores (pkgMod ? pkgMod : 1) as the pending AUTO sub-mode, saves 3 in its DB; on our GSI
 *    nothing set the property, so v6.3 AI was always Movie ("pkgMod:0", movie_5805.cfg written).
 *  - 63 setAutoSoundeffect(u8): Msrv_AudioManage_Control::setAutoSoundEffectsMode only stores the
 *    value at [this+0x178]; the "aud2_effect" thread (100 ms poll) applies a change with
 *    _setSoundEffectsMode(mode, 0) = one amp table write with ~60 ms amp mute. Same value = no write
 *    ("it'S no need to set effect"). It does not check mode 3: we read 62 == 3 right before.
 *  - the vendor itself re-resolves AUTO from the property (or 1) when the output returns to the
 *    speaker (SetOutputType), when the gaming sound mode ends, and after Resume (MUSIC/MOVIE).
 *  - stock XRMService did this on every top-app change from /system/media/auto_effect_pkg.json
 *    (Chinese apps only): property always, 63 only when 62 == 3, unmatched apps = no call.
 *
 * Ours (org.z9x.projector persistent process, thread "z9x-aisound"; HAL calls on "z9x-hal"):
 *  - active only while 62 == 3 (cached from our reads / writes) and the screen is on;
 *  - foreground app: UsageStatsManager ACTIVITY_RESUMED events (PACKAGE_USAGE_STATS, granted),
 *    polled every 2 s; transient windows (System UI, our own activities, the assistant, the IME)
 *    are ignored;
 *  - category: curated package lists (research/v64/aisound/category_map.json) first, then
 *    ApplicationInfo.category (AUDIO -> Music, VIDEO -> Movie); launcher, settings, HDMI input,
 *    games and unknown apps = KEEP (no call, the amp keeps the last sub-mode, as stock); renderers:
 *    AirPlay = its media session (skip actions = music, else video / mirroring = Movie), Chromecast
 *    (mediashell) = content type of its playing players (MUSIC / MOVIE, unknown = keep);
 *  - debounce: foreground stable >= 2 s, only a category different from the last request, at most one
 *    63 per 10 s (a later due request is re-evaluated with the latest foreground);
 *  - playback guard: a media player that was already playing before the foreground change (e.g.
 *    Spotify in the background while the user browses YouTube) and no new player of the foreground
 *    app = postpone until it stops or the foreground app starts playing (no table switch, and so no
 *    amp mute, under music that keeps playing);
 *  - send gates: 62 == 3 (read on the HAL thread), output path reporting active with path 0
 *    (speaker; also never before the first 59 confirmation of a boot, when the vendor thread could
 *    take the value as its baseline), no HDMI TIF session (gaming sound mode);
 *  - property xgimi.pkg.effect.mod is set to the same value first (best effort: default_prop, works
 *    because the device is SELinux permissive; read back and logged), so every vendor fallback
 *    resolves to the right table; 63 is the authoritative call;
 *  - the vendor's own AUTO restores (aud2_effect at boot, Resume after wake) re-resolve the
 *    sub-mode from getBootSounderModeStatus (Msrv vtbl+0x3ec, evidence_auto_sound.ann
 *    @0x15a05bc..0x15a05f0) and ignore the property (which is not persist.* and is empty after a
 *    reboot): they write [0x178] = 1 or 2 with its amp table, after the countdown
 *    m_s_u16InitEffetCountDown (x 100 ms; Init sets 0, Resume sets the u16 of this+0x93 =
 *    AudioInfo_t+0x0c from SystemInformation, ini value not resolved), so the delay is unknown.
 *    So the sub-mode is "unconfirmed" after install (boot / process start), SCREEN_ON, a HAL
 *    reconnect and a confirmed speaker report: while unconfirmed, the foreground category is
 *    requested even when it equals the last one; a successful 63 confirms it;
 *  - re-sends (idempotent: the vendor writes nothing for an unchanged value, so no amp mute) of the
 *    foreground category (or the last one when the foreground is KEEP): when VendorSoundWatch sees
 *    the vendor run its own _setSoundEffectsMode (a restore we did not cause; the sub-mode is then
 *    unconfirmed again), BOOT_COMPLETED +20 / +130 / +180 s, SCREEN_ON +3 / +10 / +20 / +30 / +60 s,
 *    HAL reconnect at the same delays (the same schedule as SoundEq, which covers the same vendor
 *    restore), 600 ms after a confirmed 59 speaker report, and every 60 s while AI is on and the
 *    screen is on (repairs a restore that every other trigger missed);
 *  - nothing is requested (no HAL call) while the confirmed output path is not the speaker (BT,
 *    ARC, wired, USB): the speaker-path listener re-sends when it comes back;
 *  - AirPlay audio with the receiver screen off (Z9xAirPlay MUSIC_SCREEN off: the foreground stays on
 *    the launcher or the previous app): when the foreground is KEEP or unknown and org.z9x.airplay
 *    has a playing media session, its category is used.
 */
public final class AiSoundEngine {
    private static final String TAG = "Z9xAiSound";
    static final String PROP_PKG_MOD = "xgimi.pkg.effect.mod";
    private static final String PREFS = "z9x_aisound";
    private static final String KEY_LAST = "last_sub";

    private static final long POLL_MS = 2_000;
    private static final long STABLE_MS = 2_000;
    private static final long MIN_GAP_MS = 10_000;
    private static final long BLOCKED_RETRY_MS = 10_000;
    /** Same schedule as SoundEq (the same vendor restore can land as late as these). */
    private static final long[] BOOT_RESEND_MS = {20_000, 130_000, 180_000};
    private static final long[] SCREEN_ON_RESEND_MS = {3_000, 10_000, 20_000, 30_000, 60_000};
    /** Idempotent re-send of the current sub-mode while AI is on, the screen on and the speaker in use. */
    private static final long PERIODIC_RESEND_MS = 60_000;
    private static final long VENDOR_WRITE_RESEND_MS = 300;
    private static final long IN_FLIGHT_RETRY_MS = 1_000;
    private static final long PATH_RESEND_MS = 600;
    private static final long PROP_FALLBACK_63_MS = 300;
    private static final long FIRST_LOOKBACK_MS = 6 * 3600_000L;

    private static final String PKG_AIRPLAY = "org.z9x.airplay";
    private static final String PKG_MEDIASHELL = "com.google.android.apps.mediashell";

    /** Curated lists (category_map.json; stock auto_effect_pkg.json apps included). */
    private static final Set<String> MUSIC = set(
            "com.spotify.tv.android", "ru.yandex.music", "com.yandex.tv.music", "com.google.android.youtube.tvmusic",
            "deezer.android.tv", "com.zvooq.openplay", "com.vkontakte.android", "com.apple.android.music",
            "com.aspiro.tidal", "tunein.player", "com.hifimusic.xgimi", "com.kanjian.radio.tv", "fm.wawa.tv",
            "com.iptv.lxyy", "com.rainbowex", "cn.kuwo.music.tv", "com.kugou.android.tv", "tv.icntv.migu",
            "hk.reco.qqmusic", "com.tencent.qqmusictv", "com.xgimi.doubanfm");
    private static final Set<String> VIDEO = set(
            "ru.kinopoisk.tv", "com.google.android.youtube.tv", "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "top.rootu.lampa", "org.videolan.vlc",
            "org.xbmc.kodi", "com.mxtech.videoplayer.ad", "is.xyz.mpv", "tv.twitch.android.app", "ru.ivi.client",
            "ru.okko.tv", "ru.more.play", "ru.rt.video.app.tv", "ru.mts.mtstv", "ru.start.androidmobile",
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.plexapp.android",
            "org.jellyfin.androidtv", "com.xgimi.xhplayer", "com.gitvjimi.video", "com.xiaodianshi.tv.yst",
            "com.ktcp.tvvideo", "com.vcinema.client.tv", "cn.cibntv.ott_4k", "net.cibntv.ott.sk",
            "com.xgimi.gimiplayer", "net.myvst.v2", "com.hunantv.license", "com.cibn.tv",
            "com.bestv.ott.baseservices", "com.newtv.cboxtv", "com.huanxi.tv", "tv.fun.orange",
            "com.sohuott.tv.vod", "com.utv.android", "com.baofeng.tv", "com.moretv.android",
            "com.pptv.androidxl", "com.iptv.liyuanhang_ott", "tv.fun.foods", "cn.beevideo");
    private static final Set<String> KTV = set(
            "com.changba.sd", "com.tencent.karaoketv", "com.iflytek.aichang.tv", "cn.kuwo.sing.tv", "com.baosheng.ktv");
    private static final Set<String> SPORTS = set("com.pptv.tvsports", "com.dazn", "ru.matchtv.android");
    /** Foreground = KEEP (no request): launcher, settings, HDMI input, our apps. */
    private static final Set<String> KEEP = set(
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.android.tv.settings",
            "com.google.android.tungsten.setupwraith", "org.z9x.tvinput", "org.z9x.btpair", "com.android.vending");
    /** Not a foreground change at all (overlays / dialogs over the app the user watches). */
    private static final Set<String> TRANSIENT = set(
            "com.android.systemui", "org.z9x.projector", "com.google.android.katniss",
            "com.android.inputmethod.leanback", "com.google.android.inputmethod.latin", "android",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller");

    private static Context sApp;
    private static Handler sH;
    private static boolean sInstalled;

    /** 62 == 3 as last seen (our reads and writes). */
    private static volatile boolean sAiMode;
    private static volatile boolean sScreenOn = true;
    /** Last sub-mode requested (63 sent) or chosen with 61(3), for the UI label. */
    private static volatile GmpfClient.SoundEffect sLast;
    /**
     * A 63 of the current sub-mode went through since the last boot / process start, wake, HAL
     * reconnect or speaker report (the vendor restore may have replaced the sub-mode before that).
     */
    private static volatile boolean sConfirmed;
    private static int sConnects;   // "z9x-hal" thread

    // "z9x-aisound" thread only
    private static String sFgPkg;
    private static long sFgSince;           // wall clock (UsageEvents timestamps)
    private static long sLastEventTs;
    private static long sLastSendAt = -MIN_GAP_MS;   // elapsedRealtime
    private static long sBlockedUntil;
    private static boolean sInFlight;
    private static String sLoggedPostpone;
    /** STARTED media players: player id -> {uid, wall start time, content type}. */
    private static final Map<Integer, long[]> sPlayers = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Runnable POLL = AiSoundEngine::poll;

    private AiSoundEngine() {}

    // ------------------------------------------------------------------ install / inputs
    /** Application.onCreate. Idempotent, never throws, no HAL call. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        try {
            sInstalled = true;
            sApp = ctx.getApplicationContext();
            HandlerThread t = new HandlerThread("z9x-aisound");
            t.start();
            sH = new Handler(t.getLooper());
            sLast = persisted(sApp);
            sConfirmed = false;                                 // vendor boot restore ignores our property
            try {
                PowerManager pm = sApp.getSystemService(PowerManager.class);
                if (pm != null) sScreenOn = pm.isInteractive();
            } catch (Throwable e) {
                Log.w(TAG, "isInteractive: " + e);
            }
            AudioManager am = sApp.getSystemService(AudioManager.class);
            if (am != null) {
                sH.post(() -> {
                    try { updatePlayers(am.getActivePlaybackConfigurations(), true); } catch (Throwable e) { Log.w(TAG, "players: " + e); }
                });
                am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
                    @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                        updatePlayers(configs, false);
                        if (sAiMode && sScreenOn) evaluate();           // a guard may have cleared
                    }
                }, sH);
            }
            AudioPathReporter.addPathListener(path -> {
                if (path == GmpfClient.AudioPath.SPEAKER.wire) {
                    sConfirmed = false;                         // SetOutputType re-resolved AUTO
                    resend("speaker path confirmed", PATH_RESEND_MS);
                }
            });
            Hal.addConnectedListener(() -> {                    // "z9x-hal" thread
                if (++sConnects > 1) {
                    sConfirmed = false;                         // gmpf_main restarted: vendor restore
                    for (long d : SCREEN_ON_RESEND_MS) resend("HAL reconnected +" + d / 1000 + " s", d);
                }
            });
            VendorSoundWatch.addListener(why -> {               // "z9x-sndwatch" thread
                if (!sAiMode) return;
                sConfirmed = false;                             // the restore may have replaced the sub-mode
                resend(why, VENDOR_WRITE_RESEND_MS);
            });
            if ("1".equals(SystemProperties.get("sys.boot_completed", ""))) bootResends();   // process restart after boot
            Log.i(TAG, "installed (last sub-mode " + sLast + ")");
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    /** Any thread: 62 as read / written (null = unknown, ignored). Starts / stops the engine. */
    public static void noteMode(Integer wire62, String why) {
        if (wire62 == null) return;
        boolean ai = wire62 == GmpfClient.SoundEffect.AI.wire;
        if (ai == sAiMode) return;
        sAiMode = ai;
        Log.i(TAG, (ai ? "AI sound on" : "AI sound off (62=" + wire62 + ")") + " (" + why + ")");
        Handler h = sH;
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(POLL);
            if (sAiMode && sScreenOn) h.post(POLL);
        });
    }

    /** BOOT_COMPLETED receiver: re-sends after the vendor aud2_effect restore. */
    public static void onBootCompleted(Context ctx) {
        install(ctx);
        sConfirmed = false;
        bootResends();
    }

    private static void bootResends() {
        for (long d : BOOT_RESEND_MS) resend("boot +" + d / 1000 + " s", d);
    }

    public static void onScreenOn() {
        sScreenOn = true;
        sConfirmed = false;                                     // vendor Resume restore re-resolves AUTO
        Handler h = sH;
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(POLL);
            if (sAiMode) h.post(POLL);
        });
        for (long d : SCREEN_ON_RESEND_MS) resend("screen on +" + d / 1000 + " s", d);
    }

    public static void onScreenOff() {
        sScreenOn = false;
        Handler h = sH;
        if (h != null) h.post(() -> h.removeCallbacks(POLL));
    }

    /** The sub-mode to show as "AI · X": the last one requested, else Movie (the vendor default). Any thread. */
    public static GmpfClient.SoundEffect shownSubMode() {
        GmpfClient.SoundEffect s = sLast;
        return s != null ? s : GmpfClient.SoundEffect.MOVIE;
    }

    // ------------------------------------------------------------------ user action (HAL thread)
    /**
     * HAL thread, user picked "AI sound": property = the current foreground category (or the last
     * one), then 61(3); the vendor applies the property's table within ~100 ms. When the property
     * could not be written and the category is not Movie, 63(category) follows 300 ms later.
     * Returns the 62 read-back.
     */
    public static int userChoseAi(GmpfClient g) throws Exception {
        GmpfClient.SoundEffect cat = null;
        try { cat = categoryNow(); } catch (Throwable t) { Log.w(TAG, "category now: " + t); }
        if (cat == null) cat = shownSubMode();
        boolean propOk = setProp(cat);
        g.setSoundEffect(GmpfClient.SoundEffect.AI);                          // 61(3)
        int back = -1;
        try { back = g.getSoundEffectRaw(); } catch (Throwable t) { Log.w(TAG, "62: " + t); }
        Log.i(TAG, "user chose AI: property " + PROP_PKG_MOD + "=" + cat.wire + (propOk ? " (ok)" : " (not written)")
                + ", 61(3) -> 62=" + back);
        remember(cat);
        noteMode(back, "user");
        final GmpfClient.SoundEffect c = cat;
        Handler h = sH;
        if (h != null) h.post(() -> sLastSendAt = SystemClock.elapsedRealtime());   // counts as a switch
        if (!propOk && cat != GmpfClient.SoundEffect.MOVIE && back == GmpfClient.SoundEffect.AI.wire) {
            Hal.runDelayed((g1, g2) -> send(g1, c, "AI chosen, property not writable"), PROP_FALLBACK_63_MS);
        }
        return back;
    }

    // ------------------------------------------------------------------ engine thread
    private static void poll() {
        if (!sAiMode || !sScreenOn) return;
        try {
            updateForeground();
            evaluate();
            periodic();
        } catch (Throwable t) {
            Log.w(TAG, "poll: " + t);
        }
        sH.removeCallbacks(POLL);
        sH.postDelayed(POLL, POLL_MS);
    }

    /** Latest ACTIVITY_RESUMED of a non-transient package since the last poll. */
    private static void updateForeground() {
        UsageStatsManager usm = sApp.getSystemService(UsageStatsManager.class);
        if (usm == null) return;
        long now = System.currentTimeMillis();
        long begin = sLastEventTs > 0 ? sLastEventTs - 1 : now - FIRST_LOOKBACK_MS;
        UsageEvents ev = usm.queryEvents(begin, now);
        if (ev == null) return;
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null;
        long ts = -1;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            long t = e.getTimeStamp();
            if (t > sLastEventTs) sLastEventTs = t;
            if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
            String p = e.getPackageName();
            if (p == null || TRANSIENT.contains(p)) continue;
            if (t >= ts) { ts = t; pkg = p; }
        }
        if (pkg != null && !pkg.equals(sFgPkg)) {
            sFgPkg = pkg;
            sFgSince = ts;
            sLoggedPostpone = null;
            Log.i(TAG, "foreground " + pkg);
        }
    }

    /** Decides whether the foreground app asks for another sub-mode now. */
    private static void evaluate() {
        if (!sAiMode || !sScreenOn || sInFlight) return;
        String pkg = sFgPkg;
        if (pkg == null) return;
        long wall = System.currentTimeMillis();
        if (wall - sFgSince < STABLE_MS) return;
        GmpfClient.SoundEffect cat = categoryOf(pkg);
        String guardPkg = pkg;
        if (cat == null) {                                  // launcher / unknown: AirPlay audio in the background?
            cat = airplayPlayingCategory();
            if (cat != null) guardPkg = PKG_AIRPLAY;        // its own player is the one playing
        }
        if (cat == null) return;
        final boolean same = cat == sLast;
        if (same && sConfirmed) return;
        if (!speakerPath()) return;                         // send() would refuse: no HAL call, the path listener re-sends
        long now = SystemClock.elapsedRealtime();
        if (now < sBlockedUntil || now - sLastSendAt < MIN_GAP_MS) return;   // the next poll looks again
        // re-confirming the sub-mode the label shows is not a category change: no playback guard
        if (!same && postpone(guardPkg)) {
            if (!pkg.equals(sLoggedPostpone)) {
                Log.i(TAG, pkg + " -> " + cat + " postponed: an earlier media player is still playing");
                sLoggedPostpone = pkg;
            }
            return;
        }
        request(cat, (same ? "re-confirm, foreground " : "foreground ") + pkg
                + (guardPkg.equals(pkg) ? "" : " (AirPlay audio playing)"));
    }

    /**
     * Engine thread (poll): every {@link #PERIODIC_RESEND_MS} without a send, an idempotent 63 of the
     * re-send category while the speaker is in use (a 63 of the value already stored writes nothing).
     */
    private static void periodic() {
        if (!sAiMode || !sScreenOn || sInFlight || !speakerPath()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - sLastSendAt < PERIODIC_RESEND_MS || now < sBlockedUntil) return;
        GmpfClient.SoundEffect cat = resendCategory();
        if (cat == null) return;
        request(cat, "periodic (re-send)");
    }

    /** The confirmed output path is the built-in speaker (cached, no HAL call). Any thread. */
    private static boolean speakerPath() {
        return AudioPathReporter.confirmedPath() == GmpfClient.AudioPath.SPEAKER.wire;
    }

    /** Engine thread: queue 63 now (respecting the 10 s gap) for an idempotent re-send of the last sub-mode. */
    private static void resend(String why, long delayMs) {
        Handler h = sH;
        if (h == null) return;
        h.postDelayed(() -> {
            if (!sAiMode || !sScreenOn) return;
            if (!speakerPath()) return;                     // not the speaker: the speaker-path listener re-sends
            if (sInFlight) {
                resend(why, IN_FLIGHT_RETRY_MS);
                return;
            }
            long wait = sLastSendAt + MIN_GAP_MS - SystemClock.elapsedRealtime();
            if (wait > 0) {
                resend(why, wait);
                return;
            }
            GmpfClient.SoundEffect cat = resendCategory();
            if (cat == null) return;
            request(cat, why + " (re-send)");
        }, Math.max(0, delayMs));
    }

    /**
     * Engine thread: what a re-send asks for: the foreground category when it is due (stable, not
     * held back by the playback guard), else the last sub-mode (the one the label shows).
     */
    private static GmpfClient.SoundEffect resendCategory() {
        GmpfClient.SoundEffect cat = sLast;
        String pkg = sFgPkg;
        try {
            if (pkg != null && System.currentTimeMillis() - sFgSince >= STABLE_MS) {
                GmpfClient.SoundEffect fg = categoryOf(pkg);
                String guardPkg = pkg;
                if (fg == null) {
                    fg = airplayPlayingCategory();
                    guardPkg = PKG_AIRPLAY;
                }
                if (fg != null && fg != cat && !postpone(guardPkg)) cat = fg;
            }
        } catch (Throwable t) {
            Log.d(TAG, "re-send category: " + t);
        }
        return cat;
    }

    private static void request(final GmpfClient.SoundEffect cat, final String why) {
        sInFlight = true;
        sLastSendAt = SystemClock.elapsedRealtime();
        // runDelayed with ifSkipped: Hal.run drops a task silently when the HAL is not connected at
        // run time, which would leave sInFlight set for good.
        boolean q = Hal.runDelayed((g, g2) -> {
            boolean ok = false;
            try {
                ok = send(g, cat, why);
            } finally {
                final boolean sent = ok;
                sH.post(() -> done(sent));
            }
        }, 0, () -> sH.post(() -> done(false)));
        if (!q) done(false);
    }

    /** Engine thread: the request finished (or was dropped); a failure blocks retries for 10 s. */
    private static void done(boolean sent) {
        sInFlight = false;
        if (sent) sConfirmed = true;
        if (!sent) sBlockedUntil = SystemClock.elapsedRealtime() + BLOCKED_RETRY_MS;
    }

    /** HAL thread: the gated 63. True when sent. */
    private static boolean send(GmpfClient g, GmpfClient.SoundEffect cat, String why) {
        try {
            int m = g.getSoundEffectRaw();                                    // 62
            if (m != GmpfClient.SoundEffect.AI.wire) {
                Log.i(TAG, why + ": 62=" + m + " is not AI, 63 not sent");
                noteMode(m, "62 read");
                return false;
            }
            if (!AudioPathReporter.isActive(g) || AudioPathReporter.confirmedPath() != GmpfClient.AudioPath.SPEAKER.wire) {
                Log.i(TAG, why + ": output is not the reported speaker path (" + AudioPathReporter.confirmedPath() + "), 63 not sent");
                return false;
            }
            if (Boolean.TRUE.equals(TifHdmiState.read(sApp))) {
                Log.i(TAG, why + ": HDMI input session open (gaming sound mode), 63 not sent");
                return false;
            }
            boolean propOk = setProp(cat);
            g.setAutoSoundEffect(cat);                                        // 63
            Log.i(TAG, why + ": 63(" + cat.wire + " " + cat + "), property " + (propOk ? "set" : "not written"));
            remember(cat);
            Ui.main().post(org.z9x.projector.panel.QuickPanel::refreshIfShowing);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, why + ": 63(" + cat.wire + "): " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ categories
    /** Any worker thread: category of the current foreground app right now (fresh UsageStats read). */
    private static GmpfClient.SoundEffect categoryNow() {
        UsageStatsManager usm = sApp.getSystemService(UsageStatsManager.class);
        if (usm == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - FIRST_LOOKBACK_MS, now);
        if (ev == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED && e.getPackageName() != null
                    && !TRANSIENT.contains(e.getPackageName())) pkg = e.getPackageName();
        }
        GmpfClient.SoundEffect cat = pkg == null ? null : categoryOf(pkg);
        return cat != null ? cat : airplayPlayingCategory();             // AirPlay audio behind the launcher
    }

    /** MOVIE / MUSIC / SPORTS / KARAOKE, or null = keep the current sub-mode. */
    static GmpfClient.SoundEffect categoryOf(String pkg) {
        if (pkg == null || KEEP.contains(pkg)) return null;
        if (PKG_AIRPLAY.equals(pkg)) return airplayCategory();
        if (PKG_MEDIASHELL.equals(pkg)) return playerCategory(pkg);
        if (SPORTS.contains(pkg)) return GmpfClient.SoundEffect.SPORTS;
        if (VIDEO.contains(pkg)) return GmpfClient.SoundEffect.MOVIE;
        if (MUSIC.contains(pkg)) return GmpfClient.SoundEffect.MUSIC;
        if (KTV.contains(pkg)) return GmpfClient.SoundEffect.KARAOKE;
        try {
            ApplicationInfo ai = sApp.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == ApplicationInfo.CATEGORY_AUDIO) return GmpfClient.SoundEffect.MUSIC;
            if (ai.category == ApplicationInfo.CATEGORY_VIDEO) return GmpfClient.SoundEffect.MOVIE;
        } catch (Throwable t) {
            Log.d(TAG, "category of " + pkg + ": " + t);
        }
        return null;                                                          // games, tools, unknown: keep
    }

    /**
     * org.z9x.airplay: its MediaSession is active with skip actions for AirPlay audio (music,
     * podcasts) and with fast-forward / rewind for AirPlay video; mirroring has no active session.
     * Its players always say CONTENT_TYPE_MOVIE, so they cannot tell music apart.
     */
    private static GmpfClient.SoundEffect airplayCategory() {
        try {
            MediaSessionManager msm = sApp.getSystemService(MediaSessionManager.class);
            if (msm != null) {
                for (MediaController mc : msm.getActiveSessions(null)) {
                    if (!PKG_AIRPLAY.equals(mc.getPackageName())) continue;
                    PlaybackState st = mc.getPlaybackState();
                    long a = st == null ? 0 : st.getActions();
                    if ((a & PlaybackState.ACTION_SKIP_TO_NEXT) != 0 && (a & PlaybackState.ACTION_FAST_FORWARD) == 0) {
                        return GmpfClient.SoundEffect.MUSIC;
                    }
                    return GmpfClient.SoundEffect.MOVIE;
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "airplay session: " + t);
        }
        return GmpfClient.SoundEffect.MOVIE;                                  // mirroring / unknown
    }

    /**
     * Category of a PLAYING (or buffering) org.z9x.airplay media session, else null. Used when the
     * foreground app is KEEP / unknown: with MUSIC_SCREEN off Z9xAirPlay plays audio without bringing
     * its screen to the front.
     */
    private static GmpfClient.SoundEffect airplayPlayingCategory() {
        try {
            MediaSessionManager msm = sApp.getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            for (MediaController mc : msm.getActiveSessions(null)) {
                if (!PKG_AIRPLAY.equals(mc.getPackageName())) continue;
                PlaybackState st = mc.getPlaybackState();
                if (st == null) continue;
                int s = st.getState();
                if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_BUFFERING) continue;
                long a = st.getActions();
                return (a & PlaybackState.ACTION_SKIP_TO_NEXT) != 0 && (a & PlaybackState.ACTION_FAST_FORWARD) == 0
                        ? GmpfClient.SoundEffect.MUSIC : GmpfClient.SoundEffect.MOVIE;
            }
        } catch (Throwable t) {
            Log.d(TAG, "airplay session (background): " + t);
        }
        return null;
    }

    /** Content type of the playing media players of {@code pkg}'s uid: MUSIC / MOVIE, else keep. */
    private static GmpfClient.SoundEffect playerCategory(String pkg) {
        int uid = uidOf(pkg);
        if (uid < 0) return null;
        boolean music = false, movie = false;
        for (long[] p : sPlayers.values()) {
            if (p[0] != uid) continue;
            if (p[2] == AudioAttributes.CONTENT_TYPE_MUSIC) music = true;
            if (p[2] == AudioAttributes.CONTENT_TYPE_MOVIE) movie = true;
        }
        return movie ? GmpfClient.SoundEffect.MOVIE : music ? GmpfClient.SoundEffect.MUSIC : null;
    }

    // ------------------------------------------------------------------ playback guard
    /** Engine thread: tracks the STARTED media players with their start time. */
    private static void updatePlayers(List<AudioPlaybackConfiguration> configs, boolean initial) {
        Set<Integer> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        if (configs != null) {
            for (AudioPlaybackConfiguration c : configs) {
                try {
                    if (c.getPlayerState() != AudioPlaybackConfiguration.PLAYER_STATE_STARTED) continue;
                    AudioAttributes aa = c.getAudioAttributes();
                    if (aa == null || aa.getUsage() != AudioAttributes.USAGE_MEDIA) continue;
                    int id = c.getPlayerInterfaceId();
                    seen.add(id);
                    long[] p = sPlayers.get(id);
                    if (p == null) sPlayers.put(id, new long[]{c.getClientUid(), initial ? 0 : now, aa.getContentType()});
                    else p[2] = aa.getContentType();
                } catch (Throwable t) {
                    Log.d(TAG, "player: " + t);
                }
            }
        }
        sPlayers.keySet().retainAll(seen);
    }

    /**
     * True when a media player that started before the foreground change is still playing and no
     * player of the foreground app (or, with an unknown uid, none newer than the change) plays.
     */
    private static boolean postpone(String fgPkg) {
        int fgUid = uidOf(fgPkg);
        boolean old = false, fresh = false;
        for (long[] p : sPlayers.values()) {
            long uid = p[0];
            if (uid >= 0 && fgUid >= 0) {
                if (uid == fgUid) fresh = true;
                else if (p[1] < sFgSince) old = true;
            } else if (p[1] < sFgSince) {
                old = true;
            } else {
                fresh = true;
            }
        }
        return old && !fresh;
    }

    // ------------------------------------------------------------------ helpers
    private static int uidOf(String pkg) {
        try {
            return sApp.getPackageManager().getPackageUid(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Best effort (default_prop; works on this permissive build only). Read back. */
    private static boolean setProp(GmpfClient.SoundEffect cat) {
        String v = Integer.toString(cat.wire);
        try {
            if (!v.equals(SystemProperties.get(PROP_PKG_MOD, ""))) SystemProperties.set(PROP_PKG_MOD, v);
            return v.equals(SystemProperties.get(PROP_PKG_MOD, ""));
        } catch (Throwable t) {
            Log.w(TAG, PROP_PKG_MOD + "=" + v + ": " + t);
            return false;
        }
    }

    private static void remember(GmpfClient.SoundEffect cat) {
        sLast = cat;
        try { prefs(sApp).edit().putInt(KEY_LAST, cat.wire).apply(); } catch (Throwable t) { Log.w(TAG, "save: " + t); }
    }

    private static GmpfClient.SoundEffect persisted(Context app) {
        try {
            GmpfClient.SoundEffect s = GmpfClient.SoundEffect.fromWire(prefs(app).getInt(KEY_LAST, -1));
            if (s == GmpfClient.SoundEffect.MOVIE || s == GmpfClient.SoundEffect.MUSIC
                    || s == GmpfClient.SoundEffect.SPORTS || s == GmpfClient.SoundEffect.KARAOKE) return s;
        } catch (Throwable ignored) { }
        return null;
    }

    private static SharedPreferences prefs(Context app) {
        try {
            return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static Set<String> set(String... s) {
        Set<String> out = new HashSet<>();
        java.util.Collections.addAll(out, s);
        return out;
    }
}
