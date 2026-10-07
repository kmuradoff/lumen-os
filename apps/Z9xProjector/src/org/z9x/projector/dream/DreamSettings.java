package org.z9x.projector.dream;

import android.app.DreamManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.OverlayHost;

/**
 * MODULE "screensaver" (v6.2). Settings helpers for the quick panel's "Screensaver" section
 * ({@link DreamPanelRows} on the All settings page, DREAM_SPEC section 7) and the one-shot defaults
 * (sys module calls {@link #applyDefaultsOnce} from SystemFixes.onBoot).
 *
 * Every Settings / DreamManager write runs on the "z9x-lamp" thread (DreamLamp.worker()); reads
 * are cheap Settings lookups (main thread OK). Permissions already held by the platform-signed
 * app: WRITE_DREAM_STATE (setActiveDream / startDream), WRITE_SECURE_SETTINGS (screensaver_enabled,
 * sleep_timeout), WRITE_SETTINGS (screen_off_timeout).
 *
 * Values are the TvSettings ones (DaydreamFragment "Start after", EnergySaverFragment "Turn off
 * after"), so both UIs show the same choices. Note for the panel: turning the screensaver OFF
 * means the projector sleeps after the idle time instead of dreaming (dream_enabled_summary).
 *
 * v6.5: on / off and "Turn off after" are our own values ({@link IdleOwner}): Android's
 * screensaver_enabled stays 1 and its sleep_timeout -1, so the idle time never turns the display off;
 * "off" makes the blank StandbyIdleDream the active dream (standby at the idle time).
 */
public final class DreamSettings {
    private static final String TAG = "Z9xDream";

    /** Our dream. */
    public static final ComponentName CLOCK =
            new ComponentName("org.z9x.projector", "org.z9x.projector.dream.ClockDream");

    // Settings.Secure keys (@hide constants; literal names as in Settings.java, Lineage 21).
    private static final String SCREENSAVER_ENABLED = "screensaver_enabled";
    private static final String SCREENSAVER_COMPONENTS = "screensaver_components";
    private static final String SLEEP_TIMEOUT = "sleep_timeout";

    /** Screensaver light: lamp level while dreaming (IGmpf 176), or unchanged. */
    public static final int LAMP_UNCHANGED = 0, LAMP_LOWEST = 1, LAMP_LOW = 2;
    public static final int LAMP_DEFAULT = LAMP_LOW;
    private static final int[] LAMP_CHOICES = {LAMP_LOWEST, LAMP_LOW, LAMP_UNCHANGED};

    /** "Start after" (System screen_off_timeout), minutes. */
    private static final int[] DELAY_MIN = {5, 15, 30, 60, 120};
    /**
     * "Turn off after" (label "Standby after"; Secure sleep_timeout before v6.5), ms. Review 6.5: no
     * "never" (-1) any more: a screensaver keeps the lamp on (only dimmed), with more heat than the
     * lamp-off standby whose "Never" was removed; IdleOwner turns a stored -1 into the last choice.
     */
    private static final long[] SLEEP_MS = {3_600_000L, 14_400_000L, 28_800_000L, 43_200_000L, 86_400_000L};

    /** ATV default sleep_timeout (TvSettingsProviderOverlay def_sleep_timeout = 24 h). */
    private static final long ATV_DEFAULT_SLEEP_MS = 86_400_000L;
    /**
     * One-shot: lower an untouched 24 h sleep_timeout to 4 h so a forgotten screensaver ends in
     * sleep instead of running (lamp dimmed, but on) for a day. Never changes a value the user set;
     * video players hold wake locks, so it never stops playback. Set false to drop this default.
     */
    private static final boolean LIMIT_FORGOTTEN_DREAM = true;
    private static final long LIMIT_SLEEP_MS = 14_400_000L;

    private static final String K_LAMP = "dream_lamp";
    private static final String K_DEFAULTS = "dream_defaults_v1";

    private static volatile int sLampLevel = LAMP_DEFAULT;

    private DreamSettings() {}

    // =================================================================== prefs (z9x-lamp)

    static void loadPrefs(Context c) {
        try {
            int v = DreamLamp.prefs(c).getInt(K_LAMP, LAMP_DEFAULT);
            sLampLevel = validLamp(v) ? v : LAMP_DEFAULT;
        } catch (Throwable t) {
            Log.w(TAG, "prefs: " + t);
        }
    }

    private static boolean validLamp(int v) {
        for (int c : LAMP_CHOICES) if (c == v) return true;
        return false;
    }

    // =================================================================== screensaver light

    /** Lamp level while dreaming: 1, 2 or LAMP_UNCHANGED. Any thread; never blocks. */
    public static int lampLevel() {
        return sLampLevel;
    }

    public static int[] lampChoices() {
        return LAMP_CHOICES.clone();
    }

    public static String lampLabel(Context c, int v) {
        if (v == LAMP_LOWEST) return c.getString(R.string.dream_light_lowest);
        if (v == LAMP_LOW) return c.getString(R.string.dream_light_low);
        return c.getString(R.string.dream_light_unchanged);
    }

    /** Applies from the next screensaver start. Any thread. */
    public static void setLampLevel(Context ctx, int v) {
        final int lvl = validLamp(v) ? v : LAMP_DEFAULT;
        sLampLevel = lvl;
        final Context app = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            try {
                DreamLamp.prefs(app).edit().putInt(K_LAMP, lvl).apply();
            } catch (Throwable t) {
                Log.w(TAG, "save lamp: " + t);
            }
        });
    }

    // =================================================================== on / off

    public static boolean isEnabled(Context c) {
        if (IdleOwner.isUserOff()) return false;                // v6.5: our switch
        int def = 1;
        try {
            int id = Resources.getSystem().getIdentifier("config_dreamsEnabledByDefault", "bool", "android");
            if (id != 0) def = Resources.getSystem().getBoolean(id) ? 1 : 0;
        } catch (Throwable ignored) {
        }
        try {
            return Settings.Secure.getInt(c.getContentResolver(), SCREENSAVER_ENABLED, def) != 0;
        } catch (Throwable t) {
            return def != 0;
        }
    }

    /** {@code done} (may be null) runs on the main thread after the write. */
    public static void setEnabled(Context ctx, boolean on, Runnable done) {
        final Context app = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            try {
                // v6.5: Android's screensaver_enabled stays 1 (IdleOwner); off = the blank standby dream
                IdleOwner.setUserOff(!on);
                Log.i(TAG, "screensaver -> " + on);
            } catch (Throwable t) {
                Log.w(TAG, "set enabled: " + t);
            }
            if (done != null) Ui.main().post(done);
        });
    }

    // =================================================================== start after

    public static int[] delayChoicesMin() {
        return DELAY_MIN.clone();
    }

    /** System screen_off_timeout in minutes (rounded). */
    public static int getDelayMin(Context c) {
        try {
            long ms = Settings.System.getLong(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 900_000L);
            return (int) Math.max(1, Math.round(ms / 60_000.0));
        } catch (Throwable t) {
            return 15;
        }
    }

    public static void setDelayMin(Context ctx, int min, Runnable done) {
        if (min < 1 || min > 24 * 60) return;
        final Context app = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            try {
                boolean ok = Settings.System.putLong(app.getContentResolver(),
                        Settings.System.SCREEN_OFF_TIMEOUT, min * 60_000L);
                Log.i(TAG, "screen_off_timeout -> " + min + " min: " + ok);
            } catch (Throwable t) {
                Log.w(TAG, "set delay: " + t);
            }
            if (done != null) Ui.main().post(done);
        });
    }

    // =================================================================== turn off after

    public static long[] sleepChoicesMs() {
        return SLEEP_MS.clone();
    }

    /** "Turn off after" in ms. v6.5: our value (IdleOwner), not Secure sleep_timeout; never -1 (review). */
    public static long getSleepMs(Context c) {
        return IdleOwner.turnOffMs();
    }

    public static void setSleepMs(Context ctx, long ms, Runnable done) {
        if (ms < 60_000L || ms > 7L * 86_400_000L) return;      // v6.5 review: no "never" (-1)
        final Context app = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            try {
                IdleOwner.setTurnOffMs(ms);                     // v6.5: Secure sleep_timeout stays -1
            } catch (Throwable t) {
                Log.w(TAG, "set sleep: " + t);
            }
            if (done != null) Ui.main().post(done);
        });
    }

    /** Labels: "5 min", "2 h", "Never". */
    public static String minutesLabel(Context c, long minutes) {
        if (minutes < 0) return c.getString(R.string.dream_never);
        if (minutes >= 60 && minutes % 60 == 0) return c.getString(R.string.dream_hours, (int) (minutes / 60));
        return c.getString(R.string.dream_minutes, (int) minutes);
    }

    public static String sleepLabel(Context c, long ms) {
        return minutesLabel(c, ms < 0 ? -1 : ms / 60_000L);
    }

    // =================================================================== which dream

    /** Our clock is the active screensaver (first entry of screensaver_components). */
    public static boolean isClockActive(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), SCREENSAVER_COMPONENTS);
            if (TextUtils.isEmpty(s)) return false;     // falls back to the default (Backdrop)
            String first = s.split(",")[0].trim();
            ComponentName cn = ComponentName.unflattenFromString(first);
            return CLOCK.equals(cn);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Makes the clock the active screensaver (Backdrop / Colors stay selectable in TvSettings). */
    public static void makeClockActive(Context ctx, Runnable done) {
        final Context app = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            setActive(app);
            if (done != null) Ui.main().post(done);
        });
    }

    /** z9x-lamp. */
    private static boolean setActive(Context app) {
        try {
            DreamManager dm = app.getSystemService(DreamManager.class);
            if (dm != null) {
                dm.setActiveDream(CLOCK);                       // WRITE_DREAM_STATE
                Log.i(TAG, "active dream -> " + CLOCK.flattenToShortString());
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "setActiveDream: " + t + " (falling back to Settings)");
        }
        try {
            boolean ok = Settings.Secure.putString(app.getContentResolver(), SCREENSAVER_COMPONENTS,
                    CLOCK.flattenToString());                   // WRITE_SECURE_SETTINGS
            Log.i(TAG, "screensaver_components -> clock: " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "screensaver_components: " + t);
            return false;
        }
    }

    // =================================================================== start now

    /** Closes our overlays, then starts the active screensaver (if enabled). Main thread. */
    public static void startNow(Context ctx) {
        final Context app = ctx.getApplicationContext();
        try { OverlayHost.get(app).dismissAll(false); } catch (Throwable t) { Log.w(TAG, "dismiss: " + t); }
        Ui.main().postDelayed(() -> {
            try {
                if (!isEnabled(app)) {
                    Log.i(TAG, "start now: screensaver disabled");
                    return;
                }
                DreamManager dm = app.getSystemService(DreamManager.class);
                if (dm != null && !dm.isDreaming()) dm.startDream();
            } catch (Throwable t) {
                Log.w(TAG, "startDream: " + t);
            }
        }, 350);
    }

    // =================================================================== one-shot defaults

    /**
     * SystemFixes.onBoot (BOOT_COMPLETED). Once per data partition (pref marker), idempotent:
     *  1. screensaver_components empty (never chosen; live value null -> Backdrop by default)
     *     -> our clock becomes the active screensaver. A user choice is never overridden, and the
     *     marker keeps a later TvSettings choice (e.g. Backdrop) from being replaced.
     *  2. sleep_timeout still the ATV default 24 h -> 4 h (LIMIT_FORGOTTEN_DREAM). v6.5: IdleOwner has
     *     already taken sleep_timeout over (with the same 24 h -> 4 h rule) and set it to -1, so this
     *     step only logs "kept".
     * screensaver_enabled, activate_on_sleep and screen_off_timeout are not touched here (v6.5: IdleOwner).
     */
    public static void applyDefaultsOnce(Context ctx) {
        final Context app = ctx.getApplicationContext();
        DreamLamp.worker().post(() -> {
            try {
                SharedPreferences p = DreamLamp.prefs(app);
                if (p.getBoolean(K_DEFAULTS, false)) return;
                ContentResolver cr = app.getContentResolver();
                String comps = Settings.Secure.getString(cr, SCREENSAVER_COMPONENTS);
                boolean ok = true;
                if (TextUtils.isEmpty(comps)) {
                    ok = setActive(app);
                } else {
                    Log.i(TAG, "defaults: screensaver already chosen (" + comps + "), kept");
                }
                if (LIMIT_FORGOTTEN_DREAM) {
                    long st = Settings.Secure.getLong(cr, SLEEP_TIMEOUT, Long.MIN_VALUE);
                    if (st == ATV_DEFAULT_SLEEP_MS) {
                        boolean w = Settings.Secure.putLong(cr, SLEEP_TIMEOUT, LIMIT_SLEEP_MS);
                        Log.i(TAG, "defaults: sleep_timeout 24 h -> 4 h: " + w);
                    } else {
                        Log.i(TAG, "defaults: sleep_timeout " + st + " kept");
                    }
                }
                if (ok) p.edit().putBoolean(K_DEFAULTS, true).commit();
            } catch (Throwable t) {
                Log.w(TAG, "defaults: " + t + " (retried on the next boot)");
            }
        });
    }
}
