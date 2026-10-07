package org.z9x.projector.sys;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import org.z9x.projector.SafeHandler;

/**
 * MODULE "sys". Idempotent runtime fixes after BOOT_COMPLETED:
 *
 * 1. <b>Settings.Global restricted_networking_mode = 0</b> (V61_REQUIREMENTS item 11). Lineage's
 *    LineageDatabaseHelper.loadRestrictedNetworkingModeSetting() writes 1 whenever the Lineage
 *    settings DB is created (first boot after the settings reset) or upgraded to v15 (VERIFIED,
 *    lineage-sdk LineageDatabaseHelper.java:247-251, 382-389, 473-476). With mode 1, the stale
 *    stock REJECT_ALL uid policies cut apps off the network: Lineage NPMS applies REJECT_ALL only
 *    through FIREWALL_CHAIN_RESTRICTED (VERIFIED NetworkPolicyManagerService.java:4829-4874, 4919).
 *    So: at boot, if != 0, set 0; and for the rest of this boot a ContentObserver sets it back to 0
 *    if the Lineage provider writes 1 later (its DB may be created after BOOT_COMPLETED).
 *    Consequence (by design of the requirement): Lineage's per-app "network access" switch has no
 *    effect while the mode is 0. Live device (read-only, 2026-10-03): mode 0, 28 uids still carry
 *    REJECT_ALL from the stock netpolicy.xml; the image's first-run reset deletes that file.
 *    Permission: WRITE_SECURE_SETTINGS (signature|privileged|development|role|installer, VERIFIED
 *    core/res/AndroidManifest.xml:4734) - granted by the platform key.
 *
 * 2. <b>Settings.System sound_effects_enabled = 1, once</b> (requirement 9), only when the image
 *    carries the click-volume fix, marked by ro.z9x.clickcurve=1 (set by the image module). A pref
 *    flag makes it one-shot, so a later "off" by the user is respected. sound_effects_enabled is
 *    in Settings.System.PUBLIC_SETTINGS (Settings.java:6290); WRITE_SETTINGS
 *    (signature|preinstalled|appop|pre23|role, AndroidManifest.xml:4407) - platform key.
 *    AudioService.playSoundEffect reads the setting on every click (VERIFIED AudioService.java:
 *    6370-6382), so no restart is needed.
 *
 * Entry point: {@link #onBoot(Context)} (BootReceiver, main thread). All work runs on the
 * "z9x-sys" thread and is wrapped; nothing here touches the gmpf HAL.
 */
public final class SystemFixes {
    private static final String TAG = "Z9xSys";

    private static final String RESTRICTED_NETWORKING_MODE = "restricted_networking_mode"; // Settings.Global, @hide
    private static final String PROP_CLICK_CURVE = "ro.z9x.clickcurve";
    private static final String PREFS = "z9x_sys";
    private static final String KEY_SFX_DONE = "sound_effects_reenabled";

    private static SafeHandler sH;
    private static ContentObserver sRestrictedObserver;

    private SystemFixes() {}

    public static void onBoot(Context ctx) {
        final Context app = ctx.getApplicationContext();
        synchronized (SystemFixes.class) {
            if (sH == null) sH = SafeHandler.newThread("z9x-sys");
        }
        sH.post(() -> {
            try { fixRestrictedNetworking(app, "boot"); } catch (Throwable t) { Log.w(TAG, "restricted mode: " + t); }
            try { watchRestrictedNetworking(app); } catch (Throwable t) { Log.w(TAG, "restricted observer: " + t); }
            try { reenableSoundEffectsOnce(app); } catch (Throwable t) { Log.w(TAG, "sound effects: " + t); }
        });
        // ===== v6.2 module "screensaver": one-shot defaults (clock as the screensaver only while none
        // was ever chosen; untouched 24 h sleep_timeout -> 4 h). Runs on its own thread "z9x-lamp". =====
        try { org.z9x.projector.dream.DreamSettings.applyDefaultsOnce(app); } catch (Throwable t) { Log.w(TAG, "dream defaults: " + t); }
    }

    // ------------------------------------------------------------------ 1. restricted networking

    private static void fixRestrictedNetworking(Context c, String why) {
        ContentResolver cr = c.getContentResolver();
        int v = Settings.Global.getInt(cr, RESTRICTED_NETWORKING_MODE, 0);
        if (v == 0) {
            Log.i(TAG, "restricted_networking_mode already 0 (" + why + ")");
            return;
        }
        boolean ok = Settings.Global.putInt(cr, RESTRICTED_NETWORKING_MODE, 0);
        Log.i(TAG, "restricted_networking_mode " + v + " -> 0 (" + why + "): " + ok);
    }

    private static void watchRestrictedNetworking(Context c) {
        if (sRestrictedObserver != null) return;
        sRestrictedObserver = new ContentObserver(sH) {
            @Override public void onChange(boolean selfChange) {
                try { fixRestrictedNetworking(c, "changed"); } catch (Throwable t) { Log.w(TAG, "restricted mode: " + t); }
            }
        };
        c.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(RESTRICTED_NETWORKING_MODE), false, sRestrictedObserver);
    }

    // ------------------------------------------------------------------ 2. sound effects

    private static void reenableSoundEffectsOnce(Context c) {
        String mark = SystemProperties.get(PROP_CLICK_CURVE, "");
        if (!"1".equals(mark)) {
            Log.i(TAG, "click-volume fix not in this image (" + PROP_CLICK_CURVE + "='" + mark
                    + "'): sound effects left as they are");
            return;
        }
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_SFX_DONE, false)) return;
        ContentResolver cr = c.getContentResolver();
        int cur = Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 0);
        boolean ok = true;
        if (cur != 1) ok = Settings.System.putInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, 1);
        Log.i(TAG, "sound_effects_enabled " + cur + " -> 1: " + ok);
        if (!ok) return;    // retry on the next boot
        try {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am != null) am.loadSoundEffects();
        } catch (Throwable t) {
            Log.w(TAG, "loadSoundEffects: " + t);
        }
        p.edit().putBoolean(KEY_SFX_DONE, true).apply();
    }
}
