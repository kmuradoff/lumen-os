package org.lumen.sensors;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;
import org.lumen.sensors.core.Words;
import org.lumen.sensors.sources.CpuSource;
import org.lumen.sensors.sources.DisplaySource;
import org.lumen.sensors.sources.GpuSource;
import org.lumen.sensors.sources.MemorySource;
import org.lumen.sensors.sources.NetSource;
import org.lumen.sensors.sources.ThermalSource;
import org.lumen.sensors.sources.UptimeSource;
import org.lumen.sensors.z9x.Z9xProfile;

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;

/**
 * Process-wide owner of the registry and the sampler (main thread API).
 *
 * The sampler runs while at least one owner holds the hub: HudService while its HUD is on screen
 * (hud = true: periodic HAL reads of what the HUD shows), SettingsActivity while it is resumed
 * (hud = false: only the probe, so it can list the groups that exist). With no owner nothing runs.
 * Sources by device profile: the generic ones everywhere, plus the XGIMI HAL source on the Z9X.
 * Publishes reach the main thread through one reused Runnable; listeners (HudView, SettingsView)
 * are called there.
 *
 * Stale watch: every source shares the one sampler thread, and a HIDL call has no timeout, so a
 * gmpf call waiting on the vendor's global mutex (AK, zoom, a long Projector call) stops every
 * publish. When no publish came for max(3 x refresh, 5 s) while the sampler runs, listeners hear
 * onStale() (the HUD dims its values) until the next publish.
 */
public final class Hub {
    private static final String TAG = "LumenSensors";

    /** Main thread, after every sampler publish. */
    public interface Listener {
        void onSamples();

        /** No publish for a while (the sampler thread is stuck): the values on screen are old. */
        default void onStale() {}
    }

    private static final long STALE_MIN_MS = 5_000;

    private static Hub sInstance;

    public static synchronized Hub get(Context c) {
        if (sInstance == null) sInstance = new Hub(c.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    public final Prefs prefs;
    public final Registry registry = new Registry();
    private final Sampler sampler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final ArrayList<Object> owners = new ArrayList<>();
    private final ArrayList<Object> hudOwners = new ArrayList<>();
    private final Runnable dispatch = this::dispatch;
    private final Runnable staleCheck = this::staleCheck;
    private long staleMs = STALE_MIN_MS;
    /** Strong reference: SharedPreferences keeps its listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (!Prefs.K_AVAILABLE.equals(key)) applyView();
    };

    private Hub(Context app) {
        this.app = app;
        this.prefs = new Prefs(app);
        ArrayList<Source> src = new ArrayList<>();
        boolean z9x = Z9xProfile.isZ9x();
        src.add(new ThermalSource(z9x ? Z9xProfile.SOC_ZONE : -1));
        src.add(new CpuSource());
        src.add(new GpuSource());
        src.add(new MemorySource());
        src.add(new NetSource(app));
        src.add(new DisplaySource(app));
        src.add(new UptimeSource());
        if (z9x) {
            for (Source s : Z9xProfile.sources(app)) src.add(s);   // last: they see what the generic ones found
        }
        sampler = new Sampler(registry, src.toArray(new Source[0]), () -> {
            main.removeCallbacks(dispatch);
            main.post(dispatch);
        });
        prefs.raw().registerOnSharedPreferenceChangeListener(prefsListener);
    }

    // ================================================================== owners
    public void acquire(Object owner, boolean hud) {
        if (!owners.contains(owner)) owners.add(owner);
        if (hud && !hudOwners.contains(owner)) hudOwners.add(owner);
        if (!hud) hudOwners.remove(owner);
        applyView();
        sampler.start(words(app.getResources()));
        armStaleCheck();
    }

    public void release(Object owner) {
        owners.remove(owner);
        hudOwners.remove(owner);
        if (owners.isEmpty()) {
            main.removeCallbacks(staleCheck);
            sampler.stop();
        } else {
            applyView();
        }
    }

    /** True while an owner holds the hub (the sampler runs and the registry is current). */
    public boolean running() {
        return !owners.isEmpty();
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The settings changed (or an owner came or went): tell the sampler what is on screen. */
    public void applyView() {
        long refresh = prefs.refreshMs();
        staleMs = Math.max(STALE_MIN_MS, 3 * refresh);
        sampler.setView(prefs.mode(), prefs.groups(), refresh, !hudOwners.isEmpty());
    }

    /** Language or display changed (HudService / SettingsActivity onConfigurationChanged). */
    public void onConfigurationChanged() {
        sampler.setWords(words(app.getResources()));
    }

    /** Groups with a value on this device: live, plus what earlier probes found (persisted). */
    public int availableGroups() {
        return registry.availableGroups() | sampler.seenGroups() | prefs.availableGroups();
    }

    private void dispatch() {
        armStaleCheck();
        int seen = sampler.seenGroups(), known = prefs.availableGroups();
        if ((seen & ~known) != 0) prefs.setInt(Prefs.K_AVAILABLE, known | seen);
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).onSamples();
            } catch (Throwable t) {
                Log.w(TAG, "listener: " + t);
            }
        }
    }

    /** (Re)starts the stale watch while the sampler runs (reused Runnable, no allocation). */
    private void armStaleCheck() {
        main.removeCallbacks(staleCheck);
        if (!owners.isEmpty()) main.postAtTime(staleCheck, SystemClock.uptimeMillis() + staleMs);
    }

    private void staleCheck() {
        if (owners.isEmpty()) return;
        Log.w(TAG, "no publish for " + staleMs + " ms (sampler thread busy): values dimmed");
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).onStale();
            } catch (Throwable t) {
                Log.w(TAG, "listener: " + t);
            }
        }
    }

    /** Units and words of the current language for the sampler thread. */
    public static Words words(Resources r) {
        int[] ids = {R.string.u_c, R.string.u_pct, R.string.u_mhz, R.string.u_mb, R.string.u_gb, R.string.u_mbits,
                R.string.u_dbm, R.string.u_mm, R.string.u_v, R.string.u_h, R.string.u_hz, R.string.u_min, R.string.u_deg,
                R.string.u_ghz, R.string.w_on, R.string.w_off, R.string.w_yes, R.string.w_no, R.string.w_min,
                R.string.w_max, R.string.u_g};
        String[] w = new String[ids.length];
        for (int i = 0; i < ids.length; i++) w[i] = r.getString(ids[i]);
        char dec = '.';
        try {
            dec = DecimalFormatSymbols.getInstance(r.getConfiguration().getLocales().get(0)).getDecimalSeparator();
        } catch (Throwable ignored) {
        }
        return new Words(w, dec);
    }
}
