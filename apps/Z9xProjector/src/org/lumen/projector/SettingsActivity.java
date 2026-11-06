package org.lumen.projector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lumen.projector.settings.Kit;
import org.lumen.projector.settings.Shell;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Projector" settings (LEANBACK_LAUNCHER). Framework widgets only, D-pad friendly.
 * Lumen OS 1.0.9: the new look (design/projector_v2, approved 2026-11-05): sections in a rail on the left,
 * the "now" actions as large tiles, settings in cards with one explanation line each (settings.Kit, settings.Shell).
 * The behaviour is the one of 1.0.8: the same rows, the same HAL calls and the same rules.
 * All HAL I/O goes through HalController (worker threads); results come back on the main thread.
 * Toggles are read on open and written ONLY on click.
 */
public final class SettingsActivity extends Activity {
    private static final String TAG = "Z9xSettings";
    static final String EXTRA_SECTION = "org.lumen.projector.extra.SECTION";
    static final String SECTION_KEYSTONE = "keystone";
    /** Lumen OS 1.0.1: the Display section (interface resolution). */
    static final String SECTION_DISPLAY = "display";

    private HalController hal;
    private boolean feat;
    private Shell shell;
    private View keystoneAnchor;
    private Kit.Row uiResRow;
    private final Map<HalController.Toggle, Kit.Row> toggleRows = new EnumMap<>(HalController.Toggle.class);
    private final Map<HalController.Toggle, Boolean> toggleState = new EnumMap<>(HalController.Toggle.class);
    private Kit.Row slot3Row, slot4Row, launcherRow, bootSourceRow;
    private Kit.Row voiceLang, voiceSpeak, voiceKey;
    private Kit.Row statsRow;
    private LinearLayout diagBox;
    private boolean resumed;
    private final Runnable onHalState = () -> { if (resumed) { refreshToggles(); refreshDiagnostics(); } };

    static void open(Context ctx, String section) {
        try {
            Intent i = new Intent(ctx, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (section != null) i.putExtra(EXTRA_SECTION, section);
            Ui.wakeFromDream(ctx);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "open: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hal = HalController.get(this);
        feat = HalController.featureEnabled();
        buildUi();
        focusSection(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        focusSection(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hal.addListener(onHalState);
        refreshToggles();
        refreshSlots();
        refreshUiRes();
        refreshDiagnostics();
        refreshStats();
    }

    /** keyboard / navigation / uiMode (manifest configChanges): handled in place, an open dialog stays. */
    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Log.i(TAG, "config change in place: keyboard " + c.keyboard + " hidden " + c.keyboardHidden
                + " navigation " + c.navigation + " uiMode 0x" + Integer.toHexString(c.uiMode));
    }

    /** The resolution chooser is an overlay window: the row shows the value again when it closes. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && resumed) refreshUiRes();
    }

    @Override
    protected void onPause() {
        resumed = false;
        hal.removeListener(onHalState);
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (shell != null && shell.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ UI building
    private void buildUi() {
        String model = Build.MODEL == null ? "" : Build.MODEL;
        String lumen = SystemProperties.get("ro.lumen.version", "");
        CharSequence subline = lumen.isEmpty() ? getString(R.string.version_line, versionName())
                : getString(R.string.s2_brand_line, model, lumen);
        shell = new Shell(this, getString(R.string.app_name), subline);

        // Focus and keystone: the "now" actions as tiles, the automation switches in two cards
        LinearLayout focus = shell.section("focus", R.drawable.ic_s2_focus, getString(R.string.s2_sec_focus));
        Kit.heading(focus, getString(R.string.s2_now), getString(R.string.s2_now_title));
        View af = tile(R.drawable.ic_s2_t_af, R.string.s2_t_af, R.string.s2_t_af_sub, v -> hal.requestAutofocus());
        keystoneAnchor = tile(R.drawable.ic_s2_t_kst, R.string.s2_t_kst, R.string.s2_t_kst_sub, v -> hal.requestKeystone(false));
        View fit = tile(R.drawable.ic_s2_t_fit, R.string.s2_t_fit, R.string.s2_t_fit_sub, v -> {
            Boolean cf = toggleState.get(HalController.Toggle.CURTAIN_FIT);
            if (Boolean.FALSE.equals(cf)) { Ui.toast(this, R.string.toast_need_curtain); return; }
            hal.requestKeystone(true);
        });
        View manual = tile(R.drawable.ic_s2_t_manual, R.string.s2_t_manual, R.string.s2_t_manual_sub,
                v -> ManualFocusActivity.open(this));
        focus.addView(Kit.tileRow(this, af, keystoneAnchor, fit, manual));
        focus.addView(Kit.space(this, 20));
        LinearLayout left = Kit.card(this), right = Kit.card(this);
        toggle(left, HalController.Toggle.POWER_ON_AF, R.string.tg_power_on_af, R.string.s2_d_power_on_af);
        toggle(left, HalController.Toggle.POWER_ON_AK, R.string.tg_power_on_ak, R.string.s2_d_power_on_ak);
        toggle(left, HalController.Toggle.MOVE_AF, R.string.tg_move_af, R.string.s2_d_move);
        toggle(right, HalController.Toggle.MOVE_AK, R.string.tg_move_ak, R.string.s2_d_move);
        toggle(right, HalController.Toggle.CURTAIN_FIT, R.string.tg_curtain, R.string.s2_d_curtain);
        toggle(right, HalController.Toggle.OBSTACLE, R.string.tg_obstacle, R.string.s2_d_obstacle);
        focus.addView(Kit.columns(this, left, right));

        // Power on (Lumen OS 1.0.4: the stock power on/off settings, power-on music and boot source)
        LinearLayout power = shell.section("power", R.drawable.ic_s2_power, getString(R.string.s2_sec_power));
        Kit.heading(power, null, getString(R.string.s2_sec_power));
        LinearLayout pc = Kit.card(this);
        toggle(pc, HalController.Toggle.POWER_ON_MUSIC, R.string.tg_power_on_music, R.string.s2_d_music);
        bootSourceRow = valueRow(pc, R.string.boot_source, R.string.s2_d_boot, v -> pickBootSource());
        power.addView(pc);

        // Remote and keys
        LinearLayout remote = shell.section("remote", R.drawable.ic_s2_remote, getString(R.string.s2_sec_remote));
        Kit.heading(remote, null, getString(R.string.s2_sec_remote));
        LinearLayout rc = Kit.card(this);
        slot3Row = valueRow(rc, R.string.key_slot3, R.string.s2_d_slot, v -> pickApp(3));
        slot4Row = valueRow(rc, R.string.key_slot4, R.string.s2_d_slot, v -> pickApp(4));
        // Pair a new / second / reset remote (120 s fast LE scan; RemoteAutoPair.startPairing).
        actionRow(rc, R.string.remote_pair_action, R.string.s2_d_pair,
                v -> org.lumen.projector.remote.RemoteAutoPair.startPairing(getApplicationContext()));
        remote.addView(rc);

        // Lumen OS 1.0.5: the voice assistant (mic key), only where a speech recogniser exists
        if (org.lumen.projector.voice.Assistant.available(this)) {
            LinearLayout voice = shell.section("voice", R.drawable.ic_s2_mic, getString(R.string.voice_settings));
            Kit.heading(voice, null, getString(R.string.voice_settings));
            LinearLayout vc = Kit.card(this);
            voiceLang = valueRow(vc, R.string.voice_language, R.string.s2_d_voice_lang,
                    v -> org.lumen.projector.voice.VoiceSettings.pickLanguage(this, this::refreshVoice));
            voiceSpeak = valueRow(vc, R.string.voice_speak, R.string.s2_d_voice_speak,
                    v -> org.lumen.projector.voice.VoiceSettings.toggleSpeak(this, this::refreshVoice));
            voiceKey = valueRow(vc, R.string.voice_key, R.string.s2_d_voice_key,
                    v -> org.lumen.projector.voice.VoiceSettings.editKey(this, this::refreshVoice));
            voice.addView(vc);
            refreshVoice();
        }

        // Home screen: Lumen Home / classic (Lumen OS 1.0 PLAN C1) and the interface resolution (1.0.1)
        LinearLayout home = shell.section("home", R.drawable.ic_s2_home, getString(R.string.s2_sec_home));
        Kit.heading(home, null, getString(R.string.s2_sec_home));
        LinearLayout hc = Kit.card(this);
        if (org.lumen.projector.home.LauncherSwitcher.choiceAvailable(this)) {
            launcherRow = valueRow(hc, R.string.launcher_row, R.string.s2_d_launcher, v -> pickLauncher());
        }
        // interface resolution 4K / 1080p (display.UiResolution), only while the image allows it
        if (org.lumen.projector.display.UiResolution.enabled()) {
            uiResRow = Kit.valueRow(this, getString(R.string.uires_row), "").onClick(v -> {
                if (!KeyReceiver.isSetupComplete(this)) {
                    Ui.toast(this, R.string.toast_setup_running);
                    return;
                }
                org.lumen.projector.display.UiResolution.openChooser(this);
            });
            Kit.add(hc, uiResRow);
        }
        if (hc.getChildCount() > 0) home.addView(hc);
        shell.hideIfEmpty(home);

        // Help: the user guide (Lumen OS 1.0.4, in place of XGIMI's manual app): a QR code to the online guide
        LinearLayout help = shell.section("help", R.drawable.ic_s2_help, getString(R.string.s2_sec_help));
        Kit.heading(help, null, getString(R.string.s2_sec_help));
        LinearLayout gc = Kit.card(this);
        actionRow(gc, R.string.guide_title, R.string.s2_d_guide, v -> org.lumen.projector.guide.GuideDialog.show(this));
        help.addView(gc);

        // Diagnostics
        LinearLayout diag = shell.section("diag", R.drawable.ic_s2_diag, getString(R.string.sec_diag));
        Kit.heading(diag, null, getString(R.string.sec_diag));
        LinearLayout dc = Kit.card(this);
        // anonymous statistics: the updater owns the switch (sys.UpdaterStats); hidden without an updater
        statsRow = Kit.switchRow(this, getString(R.string.stats_row), getString(R.string.stats_note)).onClick(v -> toggleStats());
        Kit.add(dc, statsRow);
        statsRow.setShown(false);
        // Lumen OS 1.0.1: problem reports, only while the image has a report server (ro.z9x.report.url)
        if (org.lumen.projector.report.ReportActivity.isAvailable()) {
            actionRow(dc, R.string.report_title, R.string.s2_d_report, v -> org.lumen.projector.report.ReportActivity.open(this));
        }
        actionRow(dc, R.string.act_refresh, R.string.s2_d_refresh, v -> refreshDiagnostics());
        diag.addView(dc);
        diag.addView(Kit.space(this, 14));
        diagBox = Kit.card(this);
        diagBox.setPadding(Kit.dp(this, 4), Kit.dp(this, 8), Kit.dp(this, 4), Kit.dp(this, 8));
        diag.addView(diagBox);

        shell.attach(getString(R.string.s2_hint_ok), getString(R.string.s2_hint_nav));
    }

    private View tile(int icon, int title, int sub, View.OnClickListener l) {
        View t = Kit.tile(this, icon, getString(title), getString(sub), l);
        if (!feat) disable(t);
        return t;
    }

    private Kit.Row valueRow(LinearLayout card, int title, int sub, View.OnClickListener l) {
        Kit.Row r = Kit.valueRow(this, getString(title), getString(sub)).onClick(l);
        Kit.add(card, r);
        return r;
    }

    private Kit.Row actionRow(LinearLayout card, int title, int sub, View.OnClickListener l) {
        Kit.Row r = Kit.actionRow(this, getString(title), getString(sub)).onClick(l);
        Kit.add(card, r);
        return r;
    }

    private void toggle(LinearLayout card, HalController.Toggle t, int title, int sub) {
        Kit.Row r = Kit.switchRow(this, getString(title), getString(sub)).onClick(v -> onToggleClick(t));
        Kit.add(card, r);
        r.sw.setOn(null);
        toggleRows.put(t, r);
        if (!feat) r.setUsable(false);
    }

    private static void disable(View v) {
        v.setEnabled(false);
        v.setFocusable(false);
        v.setAlpha(0.4f);
    }

    // ------------------------------------------------------------------ anonymous statistics
    private void refreshStats() {
        new Thread(() -> {
            Boolean on = org.lumen.projector.sys.UpdaterStats.get(this);
            runOnUiThread(() -> showStats(on));
        }, "stats-read").start();
    }

    private void toggleStats() {
        Boolean cur = statsRow.sw.isOn();
        if (cur == null) return;
        boolean want = !cur;
        statsRow.sw.setOn(null);
        new Thread(() -> {
            Boolean on = org.lumen.projector.sys.UpdaterStats.set(this, want);
            runOnUiThread(() -> showStats(on));
        }, "stats-set").start();
    }

    private void showStats(Boolean on) {
        if (statsRow == null || isFinishing() || isDestroyed()) return;
        statsRow.setShown(on != null);
        if (on != null) statsRow.sw.setOn(on);
    }

    private void refreshVoice() {
        if (voiceLang == null) return;
        voiceLang.value.setText(org.lumen.projector.voice.VoiceSettings.languageValue(this));
        voiceSpeak.value.setText(org.lumen.projector.voice.VoiceSettings.speakValue(this));
        voiceKey.value.setText(org.lumen.projector.voice.VoiceSettings.keyValue(this));
    }

    private void refreshUiRes() {
        if (uiResRow == null) return;
        try {
            org.lumen.projector.display.UiRes.Status st = org.lumen.projector.display.UiResolution.status(this);
            uiResRow.value.setText(org.lumen.projector.display.UiRes.label(st.wanted));
            CharSequence sum = org.lumen.projector.display.UiResolution.summary(this, st);   // a fallback calmly, no alarm colour
            uiResRow.sub.setText(sum);
            uiResRow.sub.setVisibility(sum == null || sum.length() == 0 ? View.GONE : View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "ui resolution row: " + t);
        }
    }

    private void focusSection(Intent i) {
        String s = i == null ? null : i.getStringExtra(EXTRA_SECTION);
        View target = null;
        if (SECTION_KEYSTONE.equals(s)) target = keystoneAnchor;
        if (SECTION_DISPLAY.equals(s) && uiResRow != null) target = uiResRow.view;
        if (target != null && target.isFocusable()) shell.focusView(target);
        else shell.focusSection(null);
    }

    // ------------------------------------------------------------------ toggles
    private void refreshToggles() {
        if (!feat) return;
        hal.readToggles(this::showToggles);
    }

    private void showToggles(Map<HalController.Toggle, Boolean> m) {
        if (isFinishing() || isDestroyed()) return;
        for (HalController.Toggle t : HalController.Toggle.values()) {
            Boolean v = m.get(t);
            toggleState.put(t, v);
            Kit.Row r = toggleRows.get(t);
            if (r != null) r.sw.setOn(v);         // null: not read (HAL not ready), the switch shows dim
        }
    }

    private void onToggleClick(HalController.Toggle t) {
        if (!feat) { Ui.featureOffNotice(this); return; }
        Boolean cur = toggleState.get(t);
        if (cur == null) {
            Ui.toast(this, R.string.toast_not_ready);
            refreshToggles();
            return;
        }
        Kit.Row r = toggleRows.get(t);
        if (r != null) r.sw.setOn(null);
        toggleState.put(t, null);                       // no second click until the read-back
        hal.setToggle(t, !cur, this::showToggles);
    }

    // ------------------------------------------------------------------ remote app keys
    private void refreshSlots() {
        slot3Row.value.setText(slotText(3));
        slot4Row.value.setText(slotText(4));
        refreshLauncher();
        refreshBootSource();
    }

    // ------------------------------------------------------------------ home screen (Lumen OS 1.0)
    private void refreshLauncher() {
        if (launcherRow == null) return;
        boolean classic = org.lumen.projector.home.LauncherSwitcher.MODE_CLASSIC.equals(
                org.lumen.projector.home.LauncherSwitcher.currentMode(this));
        launcherRow.value.setText(classic ? R.string.launcher_classic : R.string.launcher_lumen);
    }

    private void pickLauncher() {
        final String[] modes = {org.lumen.projector.home.LauncherSwitcher.MODE_LUMEN,
                org.lumen.projector.home.LauncherSwitcher.MODE_CLASSIC};
        CharSequence[] items = {getString(R.string.launcher_lumen), getString(R.string.launcher_classic)};
        String cur = org.lumen.projector.home.LauncherSwitcher.currentMode(this);
        int checked = modes[1].equals(cur) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_row)
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    if (modes[which].equals(cur)) return;
                    if (!KeyReceiver.isSetupComplete(this)) {
                        Ui.toast(this, R.string.toast_setup_running);
                        return;
                    }
                    launcherRow.value.setText("…");
                    org.lumen.projector.home.LauncherSwitcher.apply(this, modes[which], true, "projector settings",
                            (ok, holder, err) -> { if (!isFinishing() && !isDestroyed()) refreshLauncher(); });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ open at power-on (Lumen OS 1.0.4)
    private CharSequence[] bootSourceItems() {
        return new CharSequence[]{getString(R.string.boot_source_home), getString(R.string.source_hdmi, 1),
                getString(R.string.source_hdmi, 2)};
    }

    private void refreshBootSource() {
        if (bootSourceRow != null) bootSourceRow.value.setText(bootSourceItems()[org.lumen.projector.source.BootSource.get(this)]);
    }

    private void pickBootSource() {
        int cur = org.lumen.projector.source.BootSource.get(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.boot_source)
                .setSingleChoiceItems(bootSourceItems(), cur, (d, which) -> {
                    d.dismiss();
                    org.lumen.projector.source.BootSource.set(this, which);
                    refreshBootSource();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String slotText(int slot) {
        String chosen = AppSlots.chosenPackage(this, slot);
        String eff = AppSlots.effectivePackage(this, slot);
        String label = AppSlots.labelOf(this, eff);
        return chosen == null ? getString(R.string.slot_default_fmt, label) : label;
    }

    private void pickApp(final int slot) {
        final List<AppSlots.AppEntry> apps = AppSlots.launchableApps(this);
        final CharSequence[] items = new CharSequence[apps.size() + 1];
        items[0] = getString(R.string.slot_default_item, AppSlots.labelOf(this, AppSlots.defaultPackage(this, slot)));
        for (int i = 0; i < apps.size(); i++) items[i + 1] = apps.get(i).label;
        Log.i(AppSlots.TAG, "chooser open: slot " + slot + ", " + apps.size() + " apps, chosen "
                + AppSlots.chosenPackage(this, slot));
        new AlertDialog.Builder(this)
                .setTitle(slot == 3 ? R.string.key_slot3 : R.string.key_slot4)
                .setItems(items, (d, which) -> {
                    String pkg = which == 0 ? null : apps.get(which - 1).pkg;
                    Log.i(AppSlots.TAG, "chooser pick: slot " + slot + " item " + which + " -> " + pkg);
                    AppSlots.setPackage(this, slot, pkg);
                    refreshSlots();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> Log.i(AppSlots.TAG, "chooser cancel button: slot " + slot))
                .setOnCancelListener(d -> Log.i(AppSlots.TAG, "chooser cancelled: slot " + slot))
                .setOnDismissListener(d -> Log.i(AppSlots.TAG, "chooser dismissed: slot " + slot
                        + (isFinishing() || isDestroyed() ? " (activity finishing)" : "")))
                .show();
    }

    // ------------------------------------------------------------------ diagnostics
    private void refreshDiagnostics() {
        hal.readDiagnostics(text -> {
            if (isFinishing() || isDestroyed()) return;
            diagBox.removeAllViews();
            String extra = getString(R.string.diag_app_version, versionName()) + "\n"
                    + org.lumen.projector.display.UiResolution.diagLine(this);
            for (String line : (text + "\n" + extra).split("\n")) {
                TextView t = Kit.text(this, line, 10.5f, Kit.T2, Typeface.MONOSPACE);
                t.setFocusable(true);                  // D-pad can scroll through the lines
                t.setPadding(Kit.dp(this, 12), Kit.dp(this, 3), Kit.dp(this, 12), Kit.dp(this, 3));
                t.setOnFocusChangeListener((v, f) -> {
                    v.setBackgroundColor(f ? Kit.S2 : 0x00000000);
                    ((TextView) v).setTextColor(f ? Kit.T1 : Kit.T2);
                });
                diagBox.addView(t);
            }
        });
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "?";
        }
    }
}
