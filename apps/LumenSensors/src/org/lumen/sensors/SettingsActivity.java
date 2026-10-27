package org.lumen.sensors;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.lumen.sensors.ui.Look;
import org.lumen.sensors.ui.SettingsView;

/**
 * The launcher entry (TV launcher banner and the normal launcher): the settings of the HUD. A plain
 * full-screen activity with a translucent, dimmed window, so the live HUD and the picture behind stay
 * visible while adjusting. While it is resumed it holds the Hub for probing (the group list shows only
 * groups with a value on this device) and moves a HUD on the left to the right of its panel.
 * BACK closes it and leaves the HUD as configured. It is singleTask, so a launch while it lives (the
 * remote's app key after HOME) arrives in onNewIntent: the focus goes back to "Show overlay" then.
 */
public final class SettingsActivity extends Activity {
    private Hub hub;
    private SettingsView view;
    /** Strong reference (SharedPreferences keeps its listeners weakly): redraw when a value changes. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener = (sp, key) -> {
        if (view != null) view.invalidate();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = Hub.get(this);
        view = new SettingsView(this, hub);
        setContentView(view);
        view.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (view != null) view.focusFirst();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.prefs.raw().registerOnSharedPreferenceChangeListener(prefsListener);
        hub.addListener(view);
        hub.acquire(this, false);
        HudService.setAvoidLeft(Look.settingsPanelRight(this));
        view.onSamples();
        view.invalidate();
    }

    @Override
    protected void onPause() {
        HudService.setAvoidLeft(0);
        hub.removeListener(view);
        hub.release(this);
        hub.prefs.raw().unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onPause();
    }
}
