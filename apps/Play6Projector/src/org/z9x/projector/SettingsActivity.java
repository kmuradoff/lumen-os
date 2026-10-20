package org.z9x.projector;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemProperties;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.z9x.projector.battery.Battery;
import org.z9x.projector.battery.BatteryNotices;
import org.z9x.projector.diag.Probe;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.hal.Play6Gmpf2;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Projector" settings of the XGIMI Play 6 (quick panel > All settings > Projector settings). A fork of
 * Z9xProjector SettingsActivity.java (tools/play6/forks.tsv): framework widgets only, D-pad friendly, the
 * same sections with the Play 6 rows (docs/play6/SETTINGS_PLAN.md 1.1 and 3.4):
 *  - Focus: Autofocus now, Manual focus; Keystone: Auto keystone now;
 *  - Automation: auto keystone at power-on (553 / 552), auto keystone after moving = real-time keystone
 *    (586 / 585), smart autofocus (IGmpf2 63, then 64 + IGmpf 587 through Hal.setSmartAutofocus; hidden
 *    while the IGmpf2 guard is closed);
 *  - Remote keys (app slots, pair a remote) and the home screen choice (shared code);
 *  - Diagnostics, read only and only on a test build (ro.lumen.version play6-t...; research
 *    battery_sound_features.md 6.4: the section and its reads do not exist on any other build): the start
 *    gate, IGmpf / IGmpf2 and the guard, the recorded and live hash chains (the live IGmpf chain is not
 *    read: check H allows the IBase code only in Play6Gmpf2), 588, 402, 438, the battery raw values (240 /
 *    238 / 239 / 327, IGmpf2 52 / 51 / 46 / 47) next to the battery state (its watchdog, age and agreed
 *    state, battery/Battery), the vendor power listener (235 / 242) and the cards (battery/BatteryNotices),
 *    212, the focus motor and the 404 feature list (on p6-long, with support_battery next to
 *    Device.hasBattery()).
 * All HAL I/O goes through the Hal facade (p6-hal / p6-long); results come back on the main thread.
 * Toggles are read on open and written ONLY on click. Not on the Play 6: interface resolution, problem
 * reports, fit to screen and obstacle avoidance, autofocus at power-on.
 */
public final class SettingsActivity extends Activity {
    private static final String TAG = "P6Settings";
    static final String EXTRA_SECTION = "org.z9x.projector.extra.SECTION";
    static final String SECTION_KEYSTONE = "keystone";

    /** The automation toggles of the Play 6. */
    private enum Toggle { POWER_ON_AK, MOVE_AK, SMART_AF, BOOT_MUSIC }

    private LinearLayout list;
    private View keystoneAnchor;
    private final Map<Toggle, TextView> toggleValues = new EnumMap<>(Toggle.class);
    private final Map<Toggle, View> toggleRows = new EnumMap<>(Toggle.class);
    private final Map<Toggle, Boolean> toggleState = new EnumMap<>(Toggle.class);
    private TextView slot3Value, slot4Value, launcherValue;
    private LinearLayout diagBox;
    private int diagGen;

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
        refreshToggles();
        refreshTemperature();
        refreshSlots();
        refreshDiagnostics();
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
        action(R.string.act_af_now, v -> Hal.requestAutofocus(this));
        action(R.string.act_manual_focus, v -> ManualFocusActivity.open(this));

        header(R.string.sec_keystone);
        keystoneAnchor = action(R.string.act_kst_now, v -> Hal.requestKeystone(this));

        header(R.string.sec_auto);
        toggle(Toggle.POWER_ON_AK, R.string.tg_power_on_ak);
        toggle(Toggle.MOVE_AK, R.string.tg_move_ak);
        toggle(Toggle.SMART_AF, R.string.panel_smart_af);
        // stock parity 2026-10-21: General > Advanced > Power on / off > boot music (IGmpf 420 / 419)
        toggle(Toggle.BOOT_MUSIC, R.string.tg_power_on_music);

        // stock parity 2026-10-21: General > About > Device info > temperature (IGmpf 438), read only
        tempRow = valueRow(R.string.p6_temperature, v -> refreshTemperature());
        ((View) tempRow.getParent()).setVisibility(View.GONE);         // shown once 438 answered (owner rule)

        header(R.string.sec_keys);
        slot3Value = valueRow(R.string.key_slot3, v -> pickApp(3));
        slot4Value = valueRow(R.string.key_slot4, v -> pickApp(4));
        // Pair a new / second / reset remote (120 s fast LE scan; RemoteAutoPair.startPairing).
        action(R.string.remote_pair_action,
                v -> org.z9x.projector.remote.RemoteAutoPair.startPairing(getApplicationContext()));

        // The way back to Lumen Home in classic mode (gear long press -> Projector)
        if (org.z9x.projector.home.LauncherSwitcher.choiceAvailable(this)) {
            launcherValue = valueRow(R.string.launcher_row, v -> pickLauncher());
        }

        if (Device.isTestBuild()) {                     // Diagnostics: test builds only
            header(R.string.sec_diag);
            action(R.string.act_refresh, v -> refreshDiagnostics());
            diagBox = new LinearLayout(this);
            diagBox.setOrientation(LinearLayout.VERTICAL);
            list.addView(diagBox);
        }

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

    private View action(int res, View.OnClickListener l) {
        LinearLayout r = row();
        TextView t = text(getString(res), 18, R.color.text, false);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(v -> {
            try { l.onClick(v); } catch (Throwable e) { Log.w(TAG, "click: " + e); }
        });
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

    private void toggle(Toggle t, int res) {
        LinearLayout r = row();
        r.addView(text(getString(res), 18, R.color.text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text("…", 16, R.color.text_dim, false);
        r.addView(value);
        toggleValues.put(t, value);
        toggleRows.put(t, r);
        r.setOnClickListener(v -> onToggleClick(t));
    }

    private void focusSection(Intent i) {
        String s = i == null ? null : i.getStringExtra(EXTRA_SECTION);
        View target = null;
        if (SECTION_KEYSTONE.equals(s)) target = keystoneAnchor;
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
        Hal.query(SettingsActivity::readToggles, this::showToggles);
    }

    /** p6-hal: every toggle (null = unknown); SMART_AF only while the IGmpf2 guard is open. */
    private static Map<Toggle, Boolean> readToggles(Play6Gmpf g, Play6Gmpf2 g2) {
        Map<Toggle, Boolean> m = new EnumMap<>(Toggle.class);
        for (Toggle t : Toggle.values()) {
            try {
                switch (t) {
                    case POWER_ON_AK: m.put(t, g.getPowerOnAk()); break;          // 553
                    case MOVE_AK: m.put(t, g.getMoveAk()); break;                 // 586
                    case SMART_AF: m.put(t, g2.isIdentityOpen() ? g2.getSmartAutofocus() : null); break;   // IGmpf2 63
                    case BOOT_MUSIC: m.put(t, g.getBootMusic()); break;           // 420
                    default: break;
                }
            } catch (Throwable e) {
                Log.w(TAG, "get " + t + ": " + e);
                m.put(t, null);
            }
        }
        return m;
    }

    private void showToggles(Map<Toggle, Boolean> m) {
        if (isFinishing() || isDestroyed()) return;
        View smart = toggleRows.get(Toggle.SMART_AF);
        if (smart != null) smart.setVisibility(Hal.hasGmpf2() ? View.VISIBLE : View.GONE);
        View music = toggleRows.get(Toggle.BOOT_MUSIC);              // hidden when 420 does not answer (owner rule)
        if (music != null) music.setVisibility(m != null && m.get(Toggle.BOOT_MUSIC) != null ? View.VISIBLE : View.GONE);
        for (Toggle t : Toggle.values()) {
            Boolean v = m == null ? null : m.get(t);
            toggleState.put(t, v);
            TextView tv = toggleValues.get(t);
            if (tv == null) continue;
            if (v == null) tv.setText(Hal.isReady() ? R.string.val_unknown : R.string.val_not_ready);
            else tv.setText(v ? R.string.val_on : R.string.val_off);
        }
    }

    private void onToggleClick(Toggle t) {
        Boolean cur = toggleState.get(t);
        if (cur == null || !Hal.isReady()) {
            Ui.toast(this, R.string.toast_not_ready);
            refreshToggles();
            return;
        }
        final boolean on = !cur;
        TextView tv = toggleValues.get(t);
        if (tv != null) tv.setText("…");
        toggleState.put(t, null);                       // no second click until the read-back
        final Context app = getApplicationContext();
        boolean queued = Hal.run((g, g2) -> {
            try {
                switch (t) {
                    case POWER_ON_AK: g.setPowerOnAk(on); break;                // 552
                    case MOVE_AK: g.setMoveAk(on); break;                       // 585
                    case SMART_AF: Hal.setSmartAutofocus(g, g2, on); break;     // IGmpf2 64, then 587
                    case BOOT_MUSIC: g.setBootMusic(on); break;                 // 419
                    default: break;
                }
                Log.i(TAG, "toggle " + t + " -> " + on);
            } catch (Throwable e) {
                Log.w(TAG, "toggle " + t + ": " + e);
                Ui.toast(app, R.string.toast_failed);
            }
        });
        if (!queued) Ui.toast(this, R.string.toast_not_ready);
        refreshToggles();                               // FIFO: the read-back runs after the write
    }

    // ------------------------------------------------------------------ remote app keys
    private TextView tempRow;

    /** IGmpf 438 getSystemTemperature() -> °C, as stock Device info; the row stays hidden without an answer. */
    private void refreshTemperature() {
        if (tempRow == null) return;
        Hal.query((g, g2) -> g.getSystemTemperature(), t -> {
            if (isFinishing() || isDestroyed() || tempRow == null) return;
            boolean ok = t != null && t > 0 && t < 150;
            ((View) tempRow.getParent()).setVisibility(ok ? View.VISIBLE : View.GONE);
            if (ok) tempRow.setText(getString(R.string.p6_temperature_value, t));
        });
    }

    private void refreshSlots() {
        slot3Value.setText(slotText(3));
        slot4Value.setText(slotText(4));
        refreshLauncher();
    }

    // ------------------------------------------------------------------ home screen
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
    /**
     * One read of every diagnostic value on p6-hal, then the 404 feature list on p6-long (appended). Nothing
     * on a build without the Diagnostics section.
     */
    private void refreshDiagnostics() {
        if (diagBox == null) return;
        final int gen = ++diagGen;
        final String head = localLines();
        if (!Hal.isGateOpen()) {
            showDiag(gen, head + "\nHAL: start gate closed (nothing read)", false);
            return;
        }
        Hal.query(SettingsActivity::readDiagnostics, text -> {
            showDiag(gen, head + "\n" + (text != null ? text : "HAL: not connected (nothing read)"), false);
            if (text == null) return;
            boolean queued = Hal.runLong((g, g2) -> {
                String f;
                try {
                    f = Probe.featureSummary(g.getAllFeatureList());                  // 404
                } catch (Throwable t) {
                    f = "error: " + t;
                }
                final String line = "404 getAllFeatureList: " + f;
                Ui.main().post(() -> showDiag(gen, line, true));
            });
            if (!queued) showDiag(gen, "404 getAllFeatureList: not read (p6-long busy)", true);
        });
    }

    /** Lines without a HAL call (main thread). */
    private String localLines() {
        StringBuilder sb = new StringBuilder();
        line(sb, "ro.lumen.version", prop("ro.lumen.version"));
        line(sb, "ro.product.model", prop("ro.product.model"));
        String gm = prop("init.svc.gmpf_main");
        line(sb, "gmpf_main", "running".equals(gm) ? "running" : ("NOT running (" + gm + ")"));
        line(sb, "gmpfHw", prop("init.svc.gmpfHw"));
        line(sb, "Device.isPlay6 / hasBattery", Device.isPlay6() + " / " + Device.hasBattery());
        line(sb, "Start gate", Hal.isGateOpen() ? "open" : "closed");
        line(sb, "IGmpf", Hal.isReady() ? "connected" : "not connected");
        line(sb, "IGmpf2 guard", Hal.hasGmpf2() ? "open" : "closed");
        MotorController m = MotorController.peek();
        line(sb, "Focus motor", m == null ? "not used" : m.describe());
        line(sb, "Auto keystone", Hal.isKeystoneRunning() ? "running" : "idle");
        line(sb, "Battery", Battery.describe());
        line(sb, "Battery cards", BatteryNotices.describe());
        line(sb, "Vendor power listener", Hal.describePowerListener());
        line(sb, "Standby", org.z9x.projector.power.StandbyController.describe());
        line(sb, "HDMI source", org.z9x.projector.source.HdmiSource.describe());
        line(sb, "IGmpf hash (recorded)", Probe.IGMPF_RECORDED + " (live chain not read)");
        sb.append(getString(R.string.diag_app_version, versionName()));
        return sb.toString();
    }

    /** p6-hal: the read-only values of plan 1.1 and 3.4. */
    private static String readDiagnostics(Play6Gmpf g, Play6Gmpf2 g2) {
        StringBuilder sb = new StringBuilder();
        read(sb, "390 / 394 systemUIState / bootAnimState", () -> g.getSystemUiState() + " / " + g.getBootAnimState());
        read(sb, "588 autofocus after moving", g::getMoveAf);
        read(sb, "402 vendor HDMI auto switch", g::getHdmiAutoSwitch);
        read(sb, "438 system temperature", g::getSystemTemperature);
        read(sb, "240 battery level", g::getBatteryLevel);
        read(sb, "238 adapter powered", g::isAdapterPowered);
        read(sb, "239 battery full", g::isBatteryFull);
        read(sb, "327 power saving", g::getEcoState);
        if (g2.isIdentityOpen()) {
            Integer src = null;
            try {
                src = g2.getPowerSourceType();
                line(sb, "IGmpf2 52 power source", String.valueOf(src));
            } catch (Throwable t) {
                line(sb, "IGmpf2 52 power source", "error: " + t);
            }
            if (src != null && (src == 1 || src == 4)) read(sb, "IGmpf2 51 stand battery", g2::getStandBatteryLevel);
            else line(sb, "IGmpf2 51 stand battery", "not read (no stand)");
            read(sb, "IGmpf2 46 supply voltage (unit unverified)", g2::getPowerSupplyVoltage);
            read(sb, "IGmpf2 47 auto eco", g2::getAutoEcoMode);
            read(sb, "IGmpf2 212 ALLM status (HDMI)", g2::getAllmStatus);
        } else {
            line(sb, "IGmpf2 values", "not read (guard closed)");
        }
        try {
            String[] c = g2.liveHashChain();
            String e0 = c.length > 0 ? c[0] : "-";
            String v = Play6Gmpf2.recordedVendor(e0);
            line(sb, "IGmpf2 hash (live)", e0 + (v != null ? " = recorded " + v : " != recorded"));
        } catch (Throwable t) {
            line(sb, "IGmpf2 hash (live)", "error: " + t);
        }
        line(sb, "IGmpf2 hash (recorded)", Play6Gmpf2.RECORDED_HASH + " (v1.7.116), "
                + Play6Gmpf2.RECORDED_HASH_V1_1_67 + " (v1.1.67)");
        return sb.toString().trim();
    }

    private interface Read { Object get() throws Exception; }

    private static void read(StringBuilder sb, String k, Read r) {
        try {
            line(sb, k, String.valueOf(r.get()));
        } catch (Throwable t) {
            line(sb, k, "error: " + t);
        }
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append(k).append(": ").append(v == null || v.isEmpty() ? "n/a" : v).append('\n');
    }

    private static String prop(String k) {
        try {
            return SystemProperties.get(k);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Main thread: replaces (or, with {@code append}, extends) the lines of refresh {@code gen}. */
    private void showDiag(int gen, String text, boolean append) {
        if (diagBox == null || isFinishing() || isDestroyed() || gen != diagGen) return;
        if (!append) diagBox.removeAllViews();
        for (String line : text.split("\n")) {
            TextView t = text(line, 14, R.color.text_dim, false);
            t.setTypeface(Typeface.MONOSPACE);
            t.setFocusable(true);                  // D-pad can scroll through the lines
            t.setBackgroundResource(R.drawable.row_bg);
            t.setPadding(dp(20), dp(3), dp(20), dp(3));
            diagBox.addView(t);
        }
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
