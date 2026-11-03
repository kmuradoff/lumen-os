package org.lumen.projector.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.lumen.projector.Ui;
import org.lumen.projector.power.HbSleepTimerPanel;
import org.lumen.projector.power.StandbyActivity;
import org.lumen.projector.power.StandbyController;

/**
 * HB only (hb-t5): the receiver of Lumen Home's "Projector" shortcut tiles on the HB (AndroidManifest.xml
 * names it for org.lumen.projector.action.SHOW_PANEL, same signature permission). Two actions differ from
 * the shared {@link PanelRequestReceiver} (a Z9X-owned file of apps/common/projector, so not edited here);
 * everything else is handed to it unchanged:
 * <ul>
 * <li>{@code sleep_timer} opens {@link HbSleepTimerPanel} (the timer card itself) instead of the power menu
 *     with its timer tile focused;</li>
 * <li>{@code lamp_standby} and {@code sleep_timer} are refused for {@link #WAKE_GUARD_MS} after a wake by a
 *     key of the standby screen: the press that woke the projector must never start the standby again
 *     (the device test of hb-t4: wake by OK, then the "Sleep" tile fired 357 ms later, a loop).</li>
 * </ul>
 */
public final class HbPanelRequestReceiver extends BroadcastReceiver {
    private static final String TAG = "Z9xPanel";
    /** Same window as PowerKey.WAKE_GUARD_MS: presses this soon after a wake belong to the wake. */
    static final long WAKE_GUARD_MS = 1500;

    @Override
    public void onReceive(Context ctx, Intent i) {
        try {
            if (i == null || !PanelRequestReceiver.ACTION.equals(i.getAction())) return;
            final String action = i.getStringExtra("action");
            if ("lamp_standby".equals(action) || "sleep_timer".equals(action)) {
                final Context app = ctx.getApplicationContext();
                Ui.main().post(() -> handlePower(app, action));
                return;
            }
            new PanelRequestReceiver().onReceive(ctx, i);
        } catch (Throwable t) {
            Log.w(TAG, "SHOW_PANEL: " + t);
        }
    }

    private static void handlePower(Context app, String action) {
        try {
            if (StandbyController.isActive()) {
                Log.i(TAG, "SHOW_PANEL ignored: standby");
                return;
            }
            long since = StandbyActivity.msSinceWake();
            if (since < WAKE_GUARD_MS) {
                Log.i(TAG, "SHOW_PANEL " + action + " ignored: " + since + " ms after a standby wake");
                return;
            }
            if ("lamp_standby".equals(action)) {
                org.lumen.projector.power.PowerKey.PowerActions.sleepWithFade(app, "Lumen Home tile");
            } else {
                HbSleepTimerPanel.show(app);
            }
        } catch (Throwable t) {
            Log.w(TAG, "SHOW_PANEL " + action + ": " + t);
        }
    }
}
