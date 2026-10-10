package org.z9x.projector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Projector" settings (LEANBACK_LAUNCHER). Framework widgets only, D-pad friendly.
 * All HAL I/O goes through HalController (worker threads); results come back on the main thread.
 * Toggles are read on open and written ONLY on click.
 */
public final class SettingsActivity extends Activity {
    private static final String TAG = "Z9xSettings";
    static final String EXTRA_SECTION = "org.z9x.projector.extra.SECTION";
    static final String SECTION_KEYSTONE = "keystone";
    /** Lumen OS 1.0.1: the Display section (interface resolution). */
    static final String SECTION_DISPLAY = "display";

    private HalController hal;
    private boolean feat;
    private LinearLayout list;
    private View keystoneAnchor;
    private View uiResRow;
    private TextView uiResValue, uiResSummary;
    private final Map<HalController.Toggle, TextView> toggleValues = new EnumMap<>(HalController.Toggle.class);
    private final Map<HalController.Toggle, View> toggleRows = new EnumMap<>(HalController.Toggle.class);
    private final Map<HalController.Toggle, Boolean> toggleState = new EnumMap<>(HalController.Toggle.class);
    private TextView slot3Value, slot4Value, launcherValue;
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

    // ------------------------------------------------------------------ UI building
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.bg));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(96), dp(32), dp(96), dp(48));
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text(getString(R.string.app_name), 30, R.color.text, true);
        list.addView(title);
        TextView ver = text(getString(R.string.version_line, versionName()), 14, R.color.text_dim, false);
        ver.setPadding(0, 0, 0, dp(12));
        list.addView(ver);

        header(R.string.sec_focus);
        action(R.string.act_af_now, true, v -> hal.requestAutofocus());
        action(R.string.act_manual_focus, true, v -> ManualFocusActivity.open(this));

        header(R.string.sec_keystone);
        keystoneAnchor = action(R.string.act_kst_now, true, v -> hal.requestKeystone(false));
        action(R.string.act_fit_now, true, v -> {
            Boolean cf = toggleState.get(HalController.Toggle.CURTAIN_FIT);
            if (Boolean.FALSE.equals(cf)) { Ui.toast(this, R.string.toast_need_curtain); return; }
            hal.requestKeystone(true);
        });

        header(R.string.sec_auto);
        toggle(HalController.Toggle.POWER_ON_AF, R.string.tg_power_on_af);
        toggle(HalController.Toggle.POWER_ON_AK, R.string.tg_power_on_ak);
        toggle(HalController.Toggle.MOVE_AF, R.string.tg_move_af);
        toggle(HalController.Toggle.MOVE_AK, R.string.tg_move_ak);
        toggle(HalController.Toggle.CURTAIN_FIT, R.string.tg_curtain);
        toggle(HalController.Toggle.OBSTACLE, R.string.tg_obstacle);

        // Lumen OS 1.0.1: interface resolution 4K / 1080p (display.UiResolution), only while the image allows it
        if (org.z9x.projector.display.UiResolution.enabled()) {
            header(R.string.uires_section);
            uiResRow = uiResRow();
        }

        header(R.string.sec_keys);
        slot3Value = valueRow(R.string.key_slot3, v -> pickApp(3));
        slot4Value = valueRow(R.string.key_slot4, v -> pickApp(4));
        // Pair a new / second / reset remote (120 s fast LE scan; RemoteAutoPair.startPairing).
        action(R.string.remote_pair_action, false,
                v -> org.z9x.projector.remote.RemoteAutoPair.startPairing(getApplicationContext()));

        // Lumen OS 1.0 (PLAN C1): the way back to Lumen Home in classic mode (gear long press -> Projector)
        if (org.z9x.projector.home.LauncherSwitcher.choiceAvailable(this)) {
            launcherValue = valueRow(R.string.launcher_row, v -> pickLauncher());
        }

        header(R.string.sec_diag);
        // Lumen OS 1.0.1: problem reports, only while the image has a report server (ro.z9x.report.url)
        if (org.z9x.projector.report.ReportActivity.isAvailable()) {
            action(R.string.report_title, false, v -> org.z9x.projector.report.ReportActivity.open(this));
        }
        action(R.string.act_refresh, false, v -> refreshDiagnostics());
        diagBox = new LinearLayout(this);
        diagBox.setOrientation(LinearLayout.VERTICAL);
        list.addView(diagBox);

        setContentView(scroll);
    }

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void header(int res) {
        TextView h = text(getString(res), 15, R.color.accent, true);
        h.setPadding(dp(4), dp(20), 0, dp(6));
        list.addView(h);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackgroundResource(R.drawable.row_bg);
        r.setPadding(dp(20), dp(12), dp(20), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        list.addView(r, lp);
        return r;
    }

    private View action(int res, boolean needsHal, View.OnClickListener l) {
        LinearLayout r = row();
        TextView t = text(getString(res), 18, R.color.text, false);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(v -> {
            try { l.onClick(v); } catch (Throwable e) { Log.w(TAG, "click: " + e); }
        });
        if (needsHal && !feat) disable(r);
        return r;
    }

    private TextView valueRow(int res, View.OnClickListener l) {
        LinearLayout r = row();
        r.addView(text(getString(res), 18, R.color.text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("…", 16, R.color.text_dim, false);
        r.addView(value);
        r.setOnClickListener(v -> {
            try { l.onClick(v); } catch (Throwable e) { Log.w(TAG, "click: " + e); }
        });
        return value;
    }

    /**
     * "Interface resolution  4K" with its summary below (what the choice means, or a fallback told
     * calmly); OK opens the full-screen chooser (display.UiResPanel). Text 18 sp.
     */
    private View uiResRow() {
        LinearLayout r = row();
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(getString(R.string.uires_row), 18, R.color.text, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        uiResValue = text("…", 18, R.color.text_dim, false);
        head.addView(uiResValue);
        r.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        uiResSummary = text("", 18, R.color.text_dim, false);
        uiResSummary.setPadding(0, dp(4), 0, 0);
        r.addView(uiResSummary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setOnClickListener(v -> {
            try {
                if (!KeyReceiver.isSetupComplete(this)) {
                    Ui.toast(this, R.string.toast_setup_running);
                    return;
                }
                org.z9x.projector.display.UiResolution.openChooser(this);
            } catch (Throwable e) {
                Log.w(TAG, "click: " + e);
            }
        });
        return r;
    }

    private void refreshUiRes() {
        if (uiResValue == null) return;
        try {
            org.z9x.projector.display.UiRes.Status st = org.z9x.projector.display.UiResolution.status(this);
            uiResValue.setText(org.z9x.projector.display.UiRes.label(st.wanted));
            uiResSummary.setText(org.z9x.projector.display.UiResolution.summary(this, st));   // a fallback calmly, no alarm colour
        } catch (Throwable t) {
            Log.w(TAG, "ui resolution row: " + t);
        }
    }

    private void toggle(HalController.Toggle t, int res) {
        LinearLayout r = row();
        r.addView(text(getString(res), 18, R.color.text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("…", 16, R.color.text_dim, false);
        r.addView(value);
        toggleValues.put(t, value);
        toggleRows.put(t, r);
        r.setOnClickListener(v -> onToggleClick(t));
        if (!feat) disable(r);
    }

    private static void disable(View r) {
        r.setEnabled(false);
        r.setFocusable(false);
        r.setAlpha(0.4f);
    }

    private void focusSection(Intent i) {
        String s = i == null ? null : i.getStringExtra(EXTRA_SECTION);
        View target = null;
        if (SECTION_KEYSTONE.equals(s)) target = keystoneAnchor;
        if (SECTION_DISPLAY.equals(s)) target = uiResRow;
        if (target == null || !target.isFocusable()) {
            for (int k = 0; k < list.getChildCount(); k++) {
                View c = list.getChildAt(k);
                if (c.isFocusable()) { target = c; break; }
            }
        }
        final View f = target;
        if (f != null) {
            f.post(f::requestFocus);                    // ScrollView scrolls to the focused row
        }
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
            TextView tv = toggleValues.get(t);
            if (tv == null) continue;
            if (v == null) tv.setText(hal.isConnected() ? R.string.val_unknown : R.string.val_not_ready);
            else tv.setText(v ? R.string.val_on : R.string.val_off);
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
        TextView tv = toggleValues.get(t);
        if (tv != null) tv.setText("…");
        toggleState.put(t, null);                       // no second click until the read-back
        hal.setToggle(t, !cur, this::showToggles);
    }

    // ------------------------------------------------------------------ remote app keys
    private void refreshSlots() {
        slot3Value.setText(slotText(3));
        slot4Value.setText(slotText(4));
        refreshLauncher();
    }

    // ------------------------------------------------------------------ home screen (Lumen OS 1.0)
    private void refreshLauncher() {
        if (launcherValue == null) return;
        boolean classic = org.z9x.projector.home.LauncherSwitcher.MODE_CLASSIC.equals(
                org.z9x.projector.home.LauncherSwitcher.currentMode(this));
        launcherValue.setText(classic ? R.string.launcher_classic : R.string.launcher_lumen);
    }

    private void pickLauncher() {
        final String[] modes = {org.z9x.projector.home.LauncherSwitcher.MODE_LUMEN,
                org.z9x.projector.home.LauncherSwitcher.MODE_CLASSIC};
        CharSequence[] items = {getString(R.string.launcher_lumen), getString(R.string.launcher_classic)};
        String cur = org.z9x.projector.home.LauncherSwitcher.currentMode(this);
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
                    launcherValue.setText("…");
                    org.z9x.projector.home.LauncherSwitcher.apply(this, modes[which], true, "projector settings",
                            (ok, holder, err) -> { if (!isFinishing() && !isDestroyed()) refreshLauncher(); });
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
        new AlertDialog.Builder(this)
                .setTitle(slot == 3 ? R.string.key_slot3 : R.string.key_slot4)
                .setItems(items, (d, which) -> {
                    AppSlots.setPackage(this, slot, which == 0 ? null : apps.get(which - 1).pkg);
                    refreshSlots();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ diagnostics
    private void refreshDiagnostics() {
        hal.readDiagnostics(text -> {
            if (isFinishing() || isDestroyed()) return;
            diagBox.removeAllViews();
            String extra = getString(R.string.diag_app_version, versionName()) + "\n"
                    + org.z9x.projector.display.UiResolution.diagLine(this);
            for (String line : (text + "\n" + extra).split("\n")) {
                TextView t = text(line, 14, R.color.text_dim, false);
                t.setTypeface(Typeface.MONOSPACE);
                t.setFocusable(true);                  // D-pad can scroll through the lines
                t.setBackgroundResource(R.drawable.row_bg);
                t.setPadding(dp(20), dp(3), dp(20), dp(3));
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
