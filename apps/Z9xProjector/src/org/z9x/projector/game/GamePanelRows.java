package org.z9x.projector.game;

import android.content.Context;
import android.util.Log;

import org.z9x.projector.R;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.ui.Page;
import org.z9x.projector.ui.ToggleRow;

/**
 * MODULE "game" (v6.2): rows this module adds to the quick panel's Game mode page through the panel's
 * extension point (QuickPanel.addExtension(SECTION_GAME, ...), registered by GameProfile.install), so
 * no panel file is edited.
 *
 *  - "Auto game mode for consoles" (default on): CEC console / ALLM / >= 100 Hz detection.
 *  - "120 Hz for consoles" (default on): the profile moves game speed Standard to Standard + HFR so
 *    the console is offered 120 Hz (UNVERIFIED on the device; takes effect at the next apply).
 *  - "This input is a console": per HDMI port, for consoles that cannot be detected (CEC off, no
 *    ALLM, 60 Hz). Enabled only while an HDMI input is on screen.
 * Only preferences are written here (no HAL call); GameProfile applies or restores on its own threads.
 */
final class GamePanelRows implements QuickPanel.Extension {
    private static final String TAG = "Z9xGame";

    private Context app;
    private ToggleRow auto, console, hfr;

    @Override
    public void addRows(Context c, Page page) {
        app = c.getApplicationContext();
        auto = page.add(new ToggleRow(app, app.getString(R.string.game_auto_console), (row, wanted) -> {
            try {
                GameProfile.setAutoEnabled(app, wanted);
            } catch (Throwable t) {
                Log.w(TAG, "auto row: " + t);
            }
            row.setChecked(GameProfile.isAutoEnabled(app));
        }));
        hfr = page.add(new ToggleRow(app, app.getString(R.string.game_hfr_consoles), (row, wanted) -> {
            try {
                GameProfile.setHfrEnabled(app, wanted);
            } catch (Throwable t) {
                Log.w(TAG, "hfr row: " + t);
            }
            row.setChecked(GameProfile.isHfrEnabled(app));
        }));
        console = page.add(new ToggleRow(app, app.getString(R.string.game_input_is_console), (row, wanted) -> {
            try {
                int port = GameProfile.activePort();
                if (port > 0) GameProfile.setConsolePort(app, port, wanted);
            } catch (Throwable t) {
                Log.w(TAG, "console row: " + t);
            }
            refresh();
        }));
        refresh();
    }

    @Override
    public void onPageShown() {
        refresh();
    }

    private void refresh() {
        if (app == null || auto == null || console == null) return;
        auto.setChecked(GameProfile.isAutoEnabled(app));
        if (hfr != null) hfr.setChecked(GameProfile.isHfrEnabled(app));
        int port = GameProfile.activePort();
        console.setChecked(port > 0 ? GameProfile.isConsolePort(app, port) : null);
        console.setRowEnabled(port > 0);
    }
}
