package org.lumen.projector.fit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Fit to my screen size: SharedPreferences "fit_screen" (device protected storage, like the zoom panel's), one JSON
 * string per key, keys prefixed by the model id (docs/fit_screen/DESIGN.md section 10). Any thread.
 */
public final class FitPrefs {
    static final String TAG = "Z9xFit";
    private static final String PREFS = "fit_screen";

    private FitPrefs() {}

    private static SharedPreferences prefs(Context app) {
        return app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(Context app, String key) {
        try { return prefs(app).getString(key, null); } catch (Throwable t) { Log.w(TAG, "read " + key + ": " + t); return null; }
    }

    private static void put(Context app, String key, String json) {
        try { prefs(app).edit().putString(key, json).apply(); } catch (Throwable t) { Log.w(TAG, "write " + key + ": " + t); }
    }

    /** The stored screen, or null when none was saved yet. */
    public static FitStore.Screen screen(Context app, String model) {
        return FitStore.screenFromJson(get(app, model + ".screen"));
    }

    public static void setScreen(Context app, String model, FitStore.Screen s) {
        put(app, model + ".screen", FitStore.toJson(s));
    }

    /** The user's calibration, or null. */
    public static FitStore.Calib calib(Context app, String model) {
        return FitStore.calibFromJson(get(app, model + ".calib"));
    }

    public static void setCalib(Context app, String model, FitStore.Calib c) {
        put(app, model + ".calib", FitStore.toJson(c));
    }

    /** The throw model the fit uses: the user's calibration, else the shipped default (none yet, O6), else null. */
    public static ThrowModel model(Context app, String model) {
        FitStore.Calib c = calib(app, model);
        if (c != null) return c.model();
        double[][] d = ThrowModel.defaultPoints(model);
        if (d == null) return null;
        ThrowModel.Built b = ThrowModel.build(d);
        return b.ok() ? b.model : null;
    }

    public static void setLastFit(Context app, String model, FitStore.LastFit f) {
        put(app, model + ".lastFit", f == null ? null : FitStore.toJson(f));
    }
}
