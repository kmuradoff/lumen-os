package org.z9x.projector.dream;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.DreamManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.z9x.projector.Ui;
import org.z9x.projector.power.StandbyController;

/**
 * MODULE "screensaver" (v6.5): the idle timeout is ours, so it never turns Android's display off
 * (research/v65 decision: a display power cycle without STR leaves block noise after the wake; the
 * lamp-only standby of {@link StandbyController} keeps the display on).
 *
 * PowerManagerService, as configured here, would put Android to sleep at the idle time
 * (screensaver_activate_on_sleep was 0) and end a screensaver after Secure sleep_timeout. Instead:
 *  - Secure screensaver_activate_on_sleep = 1: the idle time always starts a dream;
 *  - Secure sleep_timeout = -1: PowerManagerService never ends the dream; the "Turn off after" value
 *    (screensaver section of the quick panel, pref dream_turn_off_ms; a value TvSettings writes to
 *    sleep_timeout is taken over and the setting reset to -1) is timed here from
 *    ACTION_DREAMING_STARTED for any screensaver (our clock, Backdrop, Colors), and then
 *    StandbyController.enter runs (lamp off, display on; the power-off deadline follows);
 *  - "Screensaver off" (quick panel, or screensaver_enabled = 0 written by TvSettings) is kept as our
 *    pref dream_user_off; Android's screensaver_enabled stays 1 and the active dream becomes the
 *    blank {@link StandbyIdleDream}, which enters standby at once. The chosen screensaver is saved and
 *    comes back when the screensaver is turned on again (or the user picks one in TvSettings).
 *  - first v6.5 start (one-shot marker idle_migrated_v65, only if IdleOwner never ran before): when
 *    screensaver_activate_on_sleep was not 1 (live: mDreamsActivateOnSleepSetting=false), the idle
 *    time used to put Android to sleep, i.e. the lamp went off at screen_off_timeout (15 min) and no
 *    screensaver ever ran. That is kept: dream_user_off = true, so StandbyIdleDream enters standby at
 *    the idle time; sleep_timeout is then NOT taken over as "Turn off after" (it never applied).
 *    Only with activate_on_sleep already 1 (a screensaver that really ran on idle) is a positive
 *    sleep_timeout adopted (the ATV default 24 h as 4 h). Later sleep_timeout writes (TvSettings) are
 *    the user's choice and are always adopted;
 *  - Secure attentive_timeout (TvSettings "Energy saver" / "Turn off display"; config default if the
 *    setting is unset) is observed too: PowerManagerService enforces it even while wakelocks are held,
 *    also our SCREEN_BRIGHT standby lock, i.e. a foreign sleep with a display power cycle (block noise
 *    after the wake) and the attentive warning dialog. A positive value is adopted as "Turn off after"
 *    and the setting reset to -1. Difference: our value starts with the screensaver at the idle time,
 *    so video that keeps the display awake without any key press is not turned off by it (KNOWN GAP).
 *  - review 6.5: "Turn off after" (label "Standby after") has no "Never" any more (a dream keeps the lamp
 *    on, only dimmed: more heat than the lamp-off standby, whose "Never" was removed for thermal
 *    reasons); a stored -1 becomes the longest choice ({@link #LONGEST_TURN_OFF_MS}). A process start
 *    while a screensaver already runs (no ACTION_DREAMING_STARTED for this process) arms the timer
 *    from that moment ({@link #checkAlreadyDreaming}).
 * Every Secure write runs on "z9x-lamp" and is idempotent; a ContentObserver re-applies the rules when
 * anything else changes these keys. If the dream cannot start or standby cannot be entered,
 * PowerManagerService's own sleep follows (PowerPolicy's sleep path: lamp off, no STR, power-off).
 */
public final class IdleOwner {
    private static final String TAG = "Z9xIdle";

    public static final ComponentName IDLE_DREAM =
            new ComponentName("org.z9x.projector", "org.z9x.projector.dream.StandbyIdleDream");

    private static final String SCREENSAVER_ENABLED = "screensaver_enabled";
    private static final String SCREENSAVER_COMPONENTS = "screensaver_components";
    private static final String ACTIVATE_ON_SLEEP = "screensaver_activate_on_sleep";
    private static final String SLEEP_TIMEOUT = "sleep_timeout";
    private static final String ATTENTIVE_TIMEOUT = "attentive_timeout";

    static final String K_USER_OFF = "dream_user_off";
    private static final String K_SAVED = "dream_saved_components";
    static final String K_TURN_OFF_MS = "dream_turn_off_ms";
    private static final String K_ADOPTED = "dream_turn_off_adopted_v1";
    /** One-shot v6.5 migration of the v6.4 idle behaviour (see the class comment). */
    private static final String K_MIGRATED = "idle_migrated_v65";

    /** ATV default sleep_timeout (24 h) and the 4 h the v6.2 defaults used for a forgotten screensaver. */
    private static final long ATV_DEFAULT_SLEEP_MS = 86_400_000L;
    static final long DEFAULT_TURN_OFF_MS = 14_400_000L;
    /** The longest "Turn off after" choice (DreamSettings.SLEEP_MS); a stored "never" (-1) becomes it. */
    static final long LONGEST_TURN_OFF_MS = 86_400_000L;
    private static final long ENFORCE_DEBOUNCE_MS = 300;

    private static Context sApp;
    private static volatile boolean sUserOff;
    private static volatile long sTurnOffMs = DEFAULT_TURN_OFF_MS;
    private static long sDreamStartedAt = -1;
    private static final Runnable sEnforce = () -> enforce("settings change", false);
    private static final Runnable sTurnOff = IdleOwner::onTurnOffTime;

    private IdleOwner() {}

    /** App.onCreate (main thread), after DreamLamp.install. */
    public static synchronized void install(Context ctx) {
        if (sApp != null) return;
        sApp = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            loadPrefs();
            enforce("install", false);
            Ui.main().post(IdleOwner::checkAlreadyDreaming);    // after the prefs (sTurnOffMs)
        });
        try {
            ContentObserver obs = new ContentObserver(DreamLamp.worker()) {
                @Override public void onChange(boolean selfChange) {
                    DreamLamp.worker().removeCallbacks(sEnforce);
                    DreamLamp.worker().postDelayed(sEnforce, ENFORCE_DEBOUNCE_MS);
                }
            };
            ContentResolver cr = sApp.getContentResolver();
            for (String k : new String[]{SCREENSAVER_ENABLED, SCREENSAVER_COMPONENTS, ACTIVATE_ON_SLEEP, SLEEP_TIMEOUT,
                    ATTENTIVE_TIMEOUT}) {
                cr.registerContentObserver(Settings.Secure.getUriFor(k), false, obs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "observer: " + t);
        }
        try {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    try {
                        if (Intent.ACTION_DREAMING_STARTED.equals(i.getAction())) onDreamingStarted();
                        else if (Intent.ACTION_DREAMING_STOPPED.equals(i.getAction())) onDreamingStopped();
                    } catch (Throwable t) {
                        Log.w(TAG, "dreaming: " + t);
                    }
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
            f.addAction(Intent.ACTION_DREAMING_STOPPED);
            sApp.registerReceiver(r, f, Context.RECEIVER_EXPORTED);       // main-thread delivery
        } catch (Throwable t) {
            Log.w(TAG, "dreaming receiver: " + t);
        }
    }

    // =================================================================== user-facing values

    /** The user wants no screensaver (the idle time goes straight to standby). Any thread. */
    static boolean isUserOff() {
        return sUserOff;
    }

    /** "Turn off after" of a running screensaver, ms (never -1 since the v6.5 review). Any thread. */
    static long turnOffMs() {
        return sTurnOffMs;
    }

    /** Quick panel: screensaver on / off. z9x-lamp thread (DreamSettings). */
    static void setUserOff(boolean off) {
        sUserOff = off;
        try { prefs().edit().putBoolean(K_USER_OFF, off).commit(); } catch (Throwable t) { Log.w(TAG, "save: " + t); }
        enforce(off ? "screensaver turned off" : "screensaver turned on", true);
    }

    /** Quick panel: "Turn off after". z9x-lamp thread (DreamSettings). */
    static void setTurnOffMs(long ms) {
        if (ms <= 0) ms = LONGEST_TURN_OFF_MS;                  // no "never" (class comment)
        sTurnOffMs = ms;
        try { prefs().edit().putLong(K_TURN_OFF_MS, ms).commit(); } catch (Throwable t) { Log.w(TAG, "save: " + t); }
        Log.i(TAG, "turn off after -> " + ms);
        Ui.main().post(IdleOwner::rearmIfDreaming);
    }

    // =================================================================== Secure settings (z9x-lamp)

    private static SharedPreferences prefs() {
        return DreamLamp.prefs(sApp);
    }

    private static void loadPrefs() {
        try {
            SharedPreferences p = prefs();
            sUserOff = p.getBoolean(K_USER_OFF, false);
            long v = p.getLong(K_TURN_OFF_MS, DEFAULT_TURN_OFF_MS);
            if (v == -1) {
                v = LONGEST_TURN_OFF_MS;
                p.edit().putLong(K_TURN_OFF_MS, v).commit();
                Log.w(TAG, "turn off after 'never' is not offered any more: " + v / 3_600_000L + " h");
            }
            sTurnOffMs = v >= 60_000L ? v : DEFAULT_TURN_OFF_MS;
        } catch (Throwable t) {
            Log.w(TAG, "prefs: " + t);
        }
    }

    private static boolean isIdle(String comps) {
        if (TextUtils.isEmpty(comps)) return false;
        ComponentName cn = ComponentName.unflattenFromString(comps.split(",")[0].trim());
        return IDLE_DREAM.equals(cn);
    }

    private static int enabledDefault() {
        return boolDefault("config_dreamsEnabledByDefault", 1);
    }

    /** PowerManagerService's default for an unset screensaver_activate_on_sleep. */
    private static int activateOnSleepDefault() {
        return boolDefault("config_dreamsActivatedOnSleepByDefault", 0);
    }

    private static int boolDefault(String name, int fallback) {
        try {
            int id = Resources.getSystem().getIdentifier(name, "bool", "android");
            if (id != 0) return Resources.getSystem().getBoolean(id) ? 1 : 0;
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** PowerManagerService's default for an unset attentive_timeout (config_attentiveTimeout), else -1. */
    private static long attentiveDefault() {
        try {
            int id = Resources.getSystem().getIdentifier("config_attentiveTimeout", "integer", "android");
            if (id != 0) return Resources.getSystem().getInteger(id);
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * z9x-lamp. Idempotent: writes only what differs. userAction: our own panel just changed
     * dream_user_off, so the components are not read as a choice made in TvSettings.
     */
    static synchronized void enforce(String why, boolean userAction) {
        if (sApp == null) return;
        try {
            ContentResolver cr = sApp.getContentResolver();
            SharedPreferences p = prefs();
            boolean keepUserOff = userAction;
            boolean adoptSleepTimeout = true;
            // 0. first v6.5 start: keep the v6.4 idle behaviour (Android sleep = lamp off at the idle
            //    time) when no screensaver ran on idle before; K_ADOPTED set = IdleOwner ran already
            if (!p.getBoolean(K_MIGRATED, false)) {
                int aos = Settings.Secure.getInt(cr, ACTIVATE_ON_SLEEP, activateOnSleepDefault());
                boolean ranBefore = p.getBoolean(K_ADOPTED, false);
                if (!ranBefore && aos != 1) {
                    sUserOff = true;
                    keepUserOff = true;
                    adoptSleepTimeout = false;
                    p.edit().putBoolean(K_USER_OFF, true).commit();
                    Log.i(TAG, "v6.5 migration: " + ACTIVATE_ON_SLEEP + "=" + aos + " (the idle time slept): "
                            + "screensaver off, the idle time goes straight to standby; sleep_timeout not adopted");
                } else {
                    Log.i(TAG, "v6.5 migration: " + ACTIVATE_ON_SLEEP + "=" + aos + (ranBefore ? ", ran before" : "")
                            + ": screensaver settings kept");
                }
                p.edit().putBoolean(K_MIGRATED, true).commit();
            }
            // 1. sleep_timeout: take a positive value over as our "turn off after", then never again
            long st = Settings.Secure.getLong(cr, SLEEP_TIMEOUT, Long.MIN_VALUE);
            if (st != -1) {
                if (st > 0 && adoptSleepTimeout) {
                    long adopt = st;
                    if (!p.getBoolean(K_ADOPTED, false) && st == ATV_DEFAULT_SLEEP_MS) adopt = DEFAULT_TURN_OFF_MS;
                    sTurnOffMs = adopt;
                    p.edit().putLong(K_TURN_OFF_MS, adopt).commit();
                    Log.i(TAG, "sleep_timeout " + st + " taken over as turn off after " + adopt + " (" + why + ")");
                }
                boolean ok = Settings.Secure.putLong(cr, SLEEP_TIMEOUT, -1);
                Log.i(TAG, "sleep_timeout " + st + " -> -1: " + ok);
            }
            if (!p.getBoolean(K_ADOPTED, false)) p.edit().putBoolean(K_ADOPTED, true).commit();
            // 1b. attentive_timeout: enforced by PowerManagerService even over wakelocks -> ours
            long at = Settings.Secure.getLong(cr, ATTENTIVE_TIMEOUT, attentiveDefault());
            if (at > 0) {
                long adopt = Math.max(60_000L, at);
                sTurnOffMs = adopt;
                p.edit().putLong(K_TURN_OFF_MS, adopt).commit();
                boolean ok = Settings.Secure.putLong(cr, ATTENTIVE_TIMEOUT, -1);
                Log.i(TAG, "attentive_timeout " + at + " taken over as turn off after " + adopt + ", setting -> -1: "
                        + ok + " (" + why + ")");
                Ui.main().post(IdleOwner::rearmIfDreaming);
            }
            // 2. the idle time starts a dream, never an Android sleep
            if (Settings.Secure.getInt(cr, ACTIVATE_ON_SLEEP, 0) != 1) {
                boolean ok = Settings.Secure.putInt(cr, ACTIVATE_ON_SLEEP, 1);
                Log.i(TAG, ACTIVATE_ON_SLEEP + " -> 1: " + ok);
            }
            // 3. screensaver on / off is ours; Android's switch stays on
            int en = Settings.Secure.getInt(cr, SCREENSAVER_ENABLED, enabledDefault());
            String comps = Settings.Secure.getString(cr, SCREENSAVER_COMPONENTS);
            boolean off = sUserOff;
            if (keepUserOff) {
                // keep sUserOff as the panel (or the v6.5 migration) set it
            } else if (en == 0 && !off) {
                off = true;
                Log.i(TAG, "screensaver_enabled=0 written elsewhere (TvSettings): screensaver off");
            } else if (off && !TextUtils.isEmpty(comps) && !isIdle(comps)) {
                off = false;
                Log.i(TAG, "a screensaver was chosen elsewhere (" + comps + "): screensaver on");
            }
            if (off != sUserOff) {
                sUserOff = off;
                p.edit().putBoolean(K_USER_OFF, off).commit();
            }
            if (off) {
                if (!isIdle(comps)) {
                    if (!TextUtils.isEmpty(comps)) p.edit().putString(K_SAVED, comps).commit();
                    boolean ok = Settings.Secure.putString(cr, SCREENSAVER_COMPONENTS, IDLE_DREAM.flattenToString());
                    Log.i(TAG, "screensaver off: blank standby dream active (saved " + comps + "): " + ok);
                }
            } else if (isIdle(comps)) {
                String back = p.getString(K_SAVED, "");
                if (TextUtils.isEmpty(back) || isIdle(back)) back = DreamSettings.CLOCK.flattenToString();
                boolean ok = Settings.Secure.putString(cr, SCREENSAVER_COMPONENTS, back);
                Log.i(TAG, "screensaver on: " + back + " active again: " + ok);
            }
            if (en != 1) {
                boolean ok = Settings.Secure.putInt(cr, SCREENSAVER_ENABLED, 1);
                Log.i(TAG, "screensaver_enabled -> 1 (idle time = dream, never Android sleep): " + ok);
            }
        } catch (Throwable t) {
            Log.w(TAG, "enforce (" + why + "): " + t);
        }
    }

    // =================================================================== dreaming (main thread)

    private static void onDreamingStarted() {
        sDreamStartedAt = SystemClock.uptimeMillis();
        Ui.main().removeCallbacks(sTurnOff);
        long ms = sTurnOffMs;
        String comps = null;
        try { comps = Settings.Secure.getString(sApp.getContentResolver(), SCREENSAVER_COMPONENTS); } catch (Throwable ignored) { }
        if (isIdle(comps)) {
            Log.i(TAG, "dreaming: blank standby dream (it enters standby itself)");
            return;
        }
        if (ms <= 0) {
            Log.i(TAG, "dreaming: turn off after = never");
            return;
        }
        Log.i(TAG, "dreaming: standby in " + ms / 60_000L + " min");
        Ui.main().postDelayed(sTurnOff, ms);
    }

    /**
     * Main thread, once per process after the prefs are loaded: a screensaver that was already running
     * when this process (re)started never sends ACTION_DREAMING_STARTED to it, so its "Turn off after"
     * timer would never run (review 6.5): treat it as started now.
     */
    private static void checkAlreadyDreaming() {
        if (sDreamStartedAt >= 0) return;                       // the broadcast came first
        try {
            DreamManager dm = sApp.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) {
                Log.i(TAG, "process start while a screensaver runs: turn-off timer armed from now");
                onDreamingStarted();
            }
        } catch (Throwable t) {
            Log.w(TAG, "isDreaming: " + t);
        }
    }

    private static void onDreamingStopped() {
        sDreamStartedAt = -1;
        Ui.main().removeCallbacks(sTurnOff);
    }

    private static void rearmIfDreaming() {
        if (sDreamStartedAt < 0) return;
        Ui.main().removeCallbacks(sTurnOff);
        long ms = sTurnOffMs;
        if (ms <= 0) return;
        long left = sDreamStartedAt + ms - SystemClock.uptimeMillis();
        Ui.main().postDelayed(sTurnOff, Math.max(0, left));
    }

    private static void onTurnOffTime() {
        sDreamStartedAt = -1;
        Log.i(TAG, "screensaver ran for " + sTurnOffMs / 60_000L + " min: standby");
        if (!StandbyController.enter(sApp, "screensaver turn off after")) {
            try {
                PowerManager pm = sApp.getSystemService(PowerManager.class);
                if (pm != null && pm.isInteractive()) {
                    pm.goToSleep(SystemClock.uptimeMillis(), PowerManager.GO_TO_SLEEP_REASON_TIMEOUT, 0);
                }
            } catch (Throwable t) {
                Log.w(TAG, "goToSleep: " + t);
            }
        }
    }
}
