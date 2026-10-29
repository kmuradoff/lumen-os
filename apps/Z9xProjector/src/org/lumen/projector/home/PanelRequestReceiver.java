package org.lumen.projector.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.lumen.projector.Hal;
import org.lumen.projector.KeyReceiver;
import org.lumen.projector.Ui;
import org.lumen.projector.panel.QuickPanel;
import org.lumen.projector.power.PowerKey;
import org.lumen.projector.power.PowerMenu;
import org.lumen.projector.power.StandbyController;

/**
 * Lumen OS 1.0 (home spec 5.6): Lumen Home's "Projector" shortcut tiles. Explicit broadcast
 * {@code org.lumen.projector.action.SHOW_PANEL}, signature permission org.lumen.projector.permission.SHOW_PANEL.
 * Extras (one of):
 * <ul>
 * <li>{@code section}: a quick panel page (QuickPanel.SECTION_*: keystone, picture, sound, eye, general,
 *     ...); empty or unknown = the tile grid;</li>
 * <li>{@code action}: {@code autofocus} (remote-style AF, debounced and gated as the focus key),
 *     {@code lamp_standby} (the short POWER press: lamp off, then STR), {@code sleep_timer} (the power
 *     menu with the timer tile focused), {@code recents} (Recent apps).</li>
 * </ul>
 * Nothing here can reach a HAL code that the quick panel itself cannot.
 */
public final class PanelRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xPanel";
    public static final String ACTION = "org.lumen.projector.action.SHOW_PANEL";

    @Override
    public void onReceive(Context ctx, Intent i) {
        try {
            if (i == null || !ACTION.equals(i.getAction())) return;
            final Context app = ctx.getApplicationContext();
            final String action = i.getStringExtra("action");
            final String section = i.getStringExtra("section");
            Ui.main().post(() -> handle(app, action, section));
        } catch (Throwable t) {
            Log.w(TAG, "SHOW_PANEL: " + t);
        }
    }

    private static void handle(Context app, String action, String section) {
        try {
            if (StandbyController.isActive()) {
                Log.i(TAG, "SHOW_PANEL ignored: standby");
                return;
            }
            if (action != null) {
                switch (action) {
                    case "autofocus":
                        Hal.requestAutofocus(app);
                        return;
                    case "lamp_standby":
                        PowerKey.PowerActions.sleepWithFade(app, "Lumen Home tile");
                        return;
                    case "sleep_timer":
                        PowerMenu.show(app, true);
                        return;
                    case "recents":
                        if (KeyReceiver.isSetupComplete(app)) org.lumen.projector.recents.RecentsActivity.open(app);
                        return;
                    default:
                        Log.w(TAG, "SHOW_PANEL: unknown action " + action);
                        return;
                }
            }
            QuickPanel.show(app, section == null || section.isEmpty() ? null : section);
        } catch (Throwable t) {
            Log.w(TAG, "SHOW_PANEL " + action + "/" + section + ": " + t);
        }
    }
}
