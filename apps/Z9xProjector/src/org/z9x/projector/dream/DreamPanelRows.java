package org.z9x.projector.dream;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.ui.ChoiceRow;
import org.z9x.projector.ui.HeaderRow;
import org.z9x.projector.ui.NavRow;
import org.z9x.projector.ui.Page;
import org.z9x.projector.ui.TextRow;
import org.z9x.projector.ui.ToggleRow;

/**
 * MODULE "screensaver" (v6.2): the "Screensaver" section on the quick panel's All settings page,
 * added through the panel's extension point (registered by {@link DreamLamp#install}), so no panel
 * file is edited. Rows: on/off, start after, screensaver light, turn off after, show (Lumen OS 1.0.1:
 * the living sky or the clock, both ways; it replaces "Use the clock screensaver"), start screensaver.
 *
 * Main thread. Reads are cheap Settings lookups; every write goes through {@link DreamSettings},
 * which runs it on "z9x-lamp". No HAL call here (the lamp level is applied by DreamLamp while
 * dreaming).
 */
final class DreamPanelRows implements QuickPanel.Extension {
    private static final String TAG = "Z9xDream";

    private Context app;
    private ToggleRow enabled;
    private ChoiceRow delay, light, sleep;
    /** Lumen OS 1.0.1: "Show  < Living sky | Clock >" (only the clock while the sky is not installed). */
    private ChoiceRow show;
    private ComponentName[] showDreams;
    private NavRow startNow;
    private int[] delayMin;
    private int[] lampChoices;
    private long[] sleepMs;

    @Override
    public void addRows(Context c, Page page) {
        app = c.getApplicationContext();
        delayMin = DreamSettings.delayChoicesMin();
        lampChoices = DreamSettings.lampChoices();
        sleepMs = DreamSettings.sleepChoicesMs();

        page.add(new HeaderRow(app, app.getString(R.string.dream_section)));
        enabled = page.add(new ToggleRow(app, app.getString(R.string.dream_enabled), (row, wanted) -> {
            try {
                row.setPending(true);
                DreamSettings.setEnabled(app, wanted, this::refresh);
            } catch (Throwable t) {
                Log.w(TAG, "enabled row: " + t);
            }
        }));
        page.add(new TextRow(app, app.getString(R.string.dream_enabled_summary)));

        CharSequence[] delayLabels = new CharSequence[delayMin.length];
        for (int i = 0; i < delayMin.length; i++) delayLabels[i] = DreamSettings.minutesLabel(app, delayMin[i]);
        delay = page.add(new ChoiceRow(app, app.getString(R.string.dream_delay), delayLabels, (row, i) -> {
            try {
                if (i >= 0 && i < delayMin.length) DreamSettings.setDelayMin(app, delayMin[i], this::refresh);
            } catch (Throwable t) {
                Log.w(TAG, "delay row: " + t);
            }
        }));

        CharSequence[] lightLabels = new CharSequence[lampChoices.length];
        for (int i = 0; i < lampChoices.length; i++) lightLabels[i] = DreamSettings.lampLabel(app, lampChoices[i]);
        light = page.add(new ChoiceRow(app, app.getString(R.string.dream_light), lightLabels, (row, i) -> {
            try {
                if (i >= 0 && i < lampChoices.length) DreamSettings.setLampLevel(app, lampChoices[i]);
            } catch (Throwable t) {
                Log.w(TAG, "light row: " + t);
            }
        }));
        page.add(new TextRow(app, app.getString(R.string.dream_light_summary)));

        CharSequence[] sleepLabels = new CharSequence[sleepMs.length];
        for (int i = 0; i < sleepMs.length; i++) sleepLabels[i] = DreamSettings.sleepLabel(app, sleepMs[i]);
        sleep = page.add(new ChoiceRow(app, app.getString(R.string.dream_sleep_after), sleepLabels, (row, i) -> {
            try {
                if (i >= 0 && i < sleepMs.length) DreamSettings.setSleepMs(app, sleepMs[i], this::refresh);
            } catch (Throwable t) {
                Log.w(TAG, "sleep row: " + t);
            }
        }));

        // the sky's name as TvSettings lists it (Lumen Home's label), the clock as ours
        boolean sky = DreamSettings.skyInstalled(app);
        showDreams = sky ? new ComponentName[]{DreamSettings.SKY, DreamSettings.CLOCK}
                : new ComponentName[]{DreamSettings.CLOCK};
        CharSequence[] showLabels = sky
                ? new CharSequence[]{DreamSettings.skyLabel(app), app.getString(R.string.dream_label)}
                : new CharSequence[]{app.getString(R.string.dream_label)};
        show = page.add(new ChoiceRow(app, app.getString(R.string.dream_show), showLabels, (row, i) -> {
            try {
                if (i < 0 || i >= showDreams.length) return;
                Log.i(TAG, "panel: show " + showDreams[i].flattenToShortString());
                DreamSettings.makeActive(app, showDreams[i], this::refresh);
            } catch (Throwable t) {
                Log.w(TAG, "show row: " + t);
            }
        })).setCommitDelay(400);                              // quick LEFT/RIGHT presses: one write
        startNow = page.add(new NavRow(app, app.getString(R.string.dream_start_now), () -> {
            try {
                if (!KeyReceiver.isSetupComplete(app)) {
                    Log.i(TAG, "setup not complete: start screensaver ignored");
                    return;
                }
                DreamSettings.startNow(app);
            } catch (Throwable t) {
                Log.w(TAG, "start now row: " + t);
            }
        })).setChevron(false);
        refresh();
    }

    @Override
    public void onPageShown() {
        refresh();
    }

    private void refresh() {
        if (app == null || enabled == null) return;
        try {
            boolean on = DreamSettings.isEnabled(app);
            enabled.setPending(false);
            enabled.setChecked(on);
            delay.setSelected(indexOf(delayMin, DreamSettings.getDelayMin(app)));
            light.setSelected(indexOf(lampChoices, DreamSettings.lampLevel()));
            long ms = DreamSettings.getSleepMs(app);
            int si = -1;
            for (int i = 0; i < sleepMs.length; i++) if (sleepMs[i] == ms || (sleepMs[i] < 0 && ms < 0)) si = i;
            sleep.setSelected(si);
            light.setRowEnabled(on);
            DreamDefaults.Shown shown = DreamSettings.shown(app);
            int si2 = -1;
            for (int i = 0; i < showDreams.length; i++) {
                if ((shown == DreamDefaults.Shown.SKY && DreamSettings.SKY.equals(showDreams[i]))
                        || (shown == DreamDefaults.Shown.CLOCK && DreamSettings.CLOCK.equals(showDreams[i]))) si2 = i;
            }
            show.setSelected(si2);                            // another dream: "—"
            show.setRowEnabled(on);
            if (startNow != null) startNow.setRowEnabled(on);   // startNow() does nothing while off
        } catch (Throwable t) {
            Log.w(TAG, "screensaver rows: " + t);
        }
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }
}
