package org.z9x.projector.cec;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.ChoiceRow;
import org.z9x.projector.ui.HeaderRow;
import org.z9x.projector.ui.NavRow;
import org.z9x.projector.ui.Page;
import org.z9x.projector.ui.TextRow;
import org.z9x.projector.ui.ToggleRow;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * MODULE "cec": the quick panel page "HDMI devices (CEC)" (All settings), cec spec 3.2 rows: one place
 * for every CEC behaviour, each row backed by its single owner (CecSettings). Values are read on
 * "z9x-cec" each time the page is shown and after every change (read-back); rows show "pending" until
 * then. Built by QuickPanelController (main thread), which supplies the {@link Host}.
 */
public final class CecPage {
    private static final String TAG = "Z9xCec";
    private static final String TVINPUT_PKG = "org.z9x.tvinput";
    private static final String TVINPUT_VIEWER = "org.z9x.tvinput.PassthroughActivity";
    /** org.z9x.tvinput's port inputs: ".../HW&lt;deviceId&gt;", deviceId == HDMI port id on the Z9X. */
    private static final String PORT_INPUT_PREFIX = "org.z9x.tvinput/.HdmiInputService/HW";

    /** What the page needs from the quick panel. Main thread. */
    public interface Host {
        /** Re-render the page if it is the one shown (rows were added / removed). */
        void refreshPage(Page page);
        /** Close the panel, then run r (opening another window). */
        void closeThen(Runnable r);
        /** org.z9x.tvinput's own HDMI options (auto switch, return home, open at power-on). */
        void openHdmiOptions();
        /** First-run gate (toast + false while setup runs). */
        boolean setupDone(String what);
    }

    private final Context app;
    private final Host host;
    private final Page page;
    private final ToggleRow master, wake, sendStandby, soundbar, avrOn, otp, control, internalExit;
    private final ChoiceRow night;
    private final TextRow paused, unavailable, name;
    private final HeaderRow devicesHeader;
    private final List<View> deviceRows = new ArrayList<>();
    /** Device list shown now (rows are rebuilt only when it changes). */
    private String deviceSig;
    /** Our own refreshPage re-renders the page, which runs onShown again: that one load is skipped. */
    private boolean skipNextLoad;

    /** Values read on z9x-cec for one apply on the main thread. */
    private static final class Values {
        Boolean master, wake, sendStandby, soundbar, otp, control, internalExit;
        boolean avrOn, paused;
        int night;
        String deviceName;
        List<CecDevices.Dev> devices;
    }

    public static Page build(Context app, Host host) {
        return new CecPage(app.getApplicationContext(), host).page;
    }

    private CecPage(Context app, Host host) {
        this.app = app;
        this.host = host;
        CecPolicy.install(app);
        page = new Page(s(R.string.cec_title));
        unavailable = page.add(new TextRow(app, s(R.string.cec_unavailable)));
        unavailable.setVisibility(View.GONE);
        master = page.add(new ToggleRow(app, s(R.string.cec_master), (row, w) ->
                change(row, () -> CecSettings.setMaster(app, w))));
        wake = page.add(new ToggleRow(app, s(R.string.cec_wake), (row, w) ->
                change(row, () -> CecSettings.setWake(app, w))));
        night = page.add(new ChoiceRow(app, s(R.string.cec_night), nightLabels(), (row, i) ->
                run(() -> CecSettings.setNightChoice(app, i))));
        night.setCommitDelay(500);
        page.add(new TextRow(app, s(R.string.cec_night_desc)));
        paused = page.add(new TextRow(app, s(R.string.cec_paused)));
        paused.setVisibility(View.GONE);
        otp = page.add(new ToggleRow(app, s(R.string.cec_switch), (row, w) ->
                change(row, () -> CecSettings.setTvinputPref(app, CecSettings.TV_OTP, w))));
        sendStandby = page.add(new ToggleRow(app, s(R.string.cec_send_standby), (row, w) ->
                change(row, () -> CecSettings.setSendStandby(app, w))));
        page.add(new TextRow(app, s(R.string.cec_follow_info)));
        soundbar = page.add(new ToggleRow(app, s(R.string.cec_soundbar), (row, w) ->
                change(row, () -> CecSettings.setSoundbar(app, w))));
        avrOn = page.add(new ToggleRow(app, s(R.string.cec_avr_on), (row, w) ->
                change(row, () -> { CecSettings.setAvrOn(app, w); return true; })));
        control = page.add(new ToggleRow(app, s(R.string.cec_control), (row, w) ->
                change(row, () -> CecSettings.setTvinputPref(app, CecSettings.TV_CONTROL, w))));
        internalExit = page.add(new ToggleRow(app, s(R.string.cec_internal_exit), (row, w) ->
                change(row, () -> CecSettings.setTvinputPref(app, CecSettings.TV_INTERNAL_ON_EXIT, w))));
        devicesHeader = page.add(new HeaderRow(app, s(R.string.cec_devices)));
        name = page.add(new TextRow(app, ""));
        page.add(new NavRow(app, s(R.string.cec_hdmi_options), () -> {
            if (host.setupDone("hdmi options")) host.closeThen(host::openHdmiOptions);
        }));
        page.setOnShown(this::load);
    }

    private String s(int res) {
        return app.getString(res);
    }

    private CharSequence[] nightLabels() {
        CharSequence[] out = new CharSequence[CecSettings.NIGHT_CHOICES.length];
        java.text.DateFormat f = DateFormat.getTimeFormat(app);
        Calendar cal = Calendar.getInstance();
        for (int i = 0; i < out.length; i++) {
            int[] r = CecSettings.NIGHT_CHOICES[i];
            if (r[0] < 0) {
                out[i] = s(R.string.cec_night_off);
                continue;
            }
            cal.set(2000, Calendar.JANUARY, 1, r[0], 0, 0);
            String from = f.format(cal.getTime());
            cal.set(2000, Calendar.JANUARY, 1, r[1], 0, 0);
            String to = f.format(cal.getTime());
            out[i] = app.getString(R.string.cec_night_range, from, to);
        }
        return out;
    }

    // =================================================================== changes

    private interface Setter { boolean set(); }

    /** A toggle asked for a change: write on z9x-cec, then read everything back. */
    private void change(ToggleRow row, Setter s) {
        run(() -> {
            boolean ok = s.set();
            if (!ok) Ui.main().post(() -> Ui.toast(app, R.string.toast_failed));
        });
    }

    private void run(Runnable r) {
        CecPolicy.worker().post(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.w(TAG, "page change: " + t);
            }
            readAndApply();
        });
    }

    // =================================================================== load / apply

    private void load() {
        if (skipNextLoad) {
            skipNextLoad = false;
            return;
        }
        CecPolicy.worker().post(this::readAndApply);
    }

    /** z9x-cec. */
    private void readAndApply() {
        final Values v = new Values();
        v.master = CecSettings.masterOn(app);
        v.wake = CecSettings.wakeOn(app);
        v.sendStandby = CecSettings.sendStandbyOn(app);
        v.soundbar = CecSettings.soundbarOn(app);
        v.avrOn = CecSettings.avrOn(app);
        v.night = CecSettings.nightChoice(app);
        v.paused = CecPolicy.stormPaused();
        Bundle tp = CecSettings.tvinputPrefs(app);
        if (tp != null) {
            v.otp = tp.containsKey(CecSettings.TV_OTP) ? tp.getBoolean(CecSettings.TV_OTP) : null;
            v.control = tp.containsKey(CecSettings.TV_CONTROL) ? tp.getBoolean(CecSettings.TV_CONTROL) : null;
            v.internalExit = tp.containsKey(CecSettings.TV_INTERNAL_ON_EXIT) ? tp.getBoolean(CecSettings.TV_INTERNAL_ON_EXIT) : null;
        }
        try {
            v.deviceName = Settings.Global.getString(app.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Throwable t) {
            v.deviceName = null;
        }
        v.devices = Boolean.TRUE.equals(v.master) ? CecDevices.read(app) : new ArrayList<>();
        // a changed setting may change the boot default of the PM51 CEC wake source
        CecSettings.syncWakeProp(app);
        Ui.main().post(() -> apply(v));
    }

    /** Main thread. */
    private void apply(Values v) {
        boolean on = Boolean.TRUE.equals(v.master);
        unavailable.setVisibility(v.master == null ? View.VISIBLE : View.GONE);
        master.setChecked(v.master);
        master.setRowEnabled(v.master != null);
        wake.setChecked(v.wake);
        night.setSelected(v.night);
        otp.setChecked(v.otp);
        sendStandby.setChecked(v.sendStandby);
        soundbar.setChecked(v.soundbar);
        avrOn.setChecked(v.avrOn);
        control.setChecked(v.control);
        internalExit.setChecked(v.internalExit);
        for (View r : new View[]{wake, night, otp, sendStandby, soundbar, control, internalExit}) {
            if (r instanceof org.z9x.projector.ui.Row) ((org.z9x.projector.ui.Row) r).setRowEnabled(on);
        }
        avrOn.setRowEnabled(on && Boolean.TRUE.equals(v.soundbar));
        paused.setVisibility(on && v.paused ? View.VISIBLE : View.GONE);
        name.setText(app.getString(R.string.cec_name_on_hdmi,
                TextUtils.isEmpty(v.deviceName) ? android.os.Build.MODEL : v.deviceName));
        if (rebuildDevices(on ? v.devices : null)) {
            skipNextLoad = true;
            host.refreshPage(page);
            // not shown (panel closed meanwhile): no render, so no onShown to skip
            Ui.main().post(() -> skipNextLoad = false);
        }
    }

    /** True when the device rows changed (the page must be re-rendered). */
    private boolean rebuildDevices(List<CecDevices.Dev> devs) {
        StringBuilder sig = new StringBuilder();
        if (devs != null) {
            for (CecDevices.Dev d : devs) {
                sig.append(d.id).append('/').append(d.port).append('/').append(d.power).append('/')
                        .append(d.name).append('/').append(d.inputId).append(';');
            }
        }
        String now = sig.toString();
        if (now.equals(deviceSig)) return false;
        deviceSig = now;
        List<View> rows = page.rows();
        for (View r : deviceRows) rows.remove(r);
        deviceRows.clear();
        int at = rows.indexOf(devicesHeader) + 1;
        if (devs == null || devs.isEmpty()) {
            deviceRows.add(new TextRow(app, s(R.string.cec_no_devices)));
        } else {
            for (CecDevices.Dev d : devs) deviceRows.add(deviceRow(d));
        }
        rows.addAll(at, deviceRows);
        return true;
    }

    private View deviceRow(CecDevices.Dev d) {
        String title = !d.name.isEmpty() ? d.name
                : s(d.isAudioSystem() ? R.string.cec_dev_soundbar : R.string.cec_dev_unnamed);
        String state = d.power == android.hardware.hdmi.HdmiControlManager.POWER_STATUS_ON
                || d.power == android.hardware.hdmi.HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON
                ? s(R.string.cec_state_on)
                : d.power == android.hardware.hdmi.HdmiControlManager.POWER_STATUS_STANDBY
                || d.power == android.hardware.hdmi.HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY
                ? s(R.string.cec_state_standby) : "";
        String value = state.isEmpty() ? app.getString(R.string.cec_dev_port, d.port)
                : app.getString(R.string.cec_dev_value, d.port, state);
        NavRow row = new NavRow(app, title);
        row.setValue(value);
        if (!d.isSource()) {
            row.setChevron(false);
            return row;
        }
        final String input = d.inputId != null ? d.inputId : PORT_INPUT_PREFIX + d.port;
        row.setOnClick(() -> {
            if (host.setupDone("cec device")) host.closeThen(() -> openInput(input));
        });
        return row;
    }

    private void openInput(String inputId) {
        try {
            Ui.wakeFromDream(app);
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId))
                    .setClassName(TVINPUT_PKG, TVINPUT_VIEWER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
            Log.i(TAG, "panel: open " + inputId);
        } catch (Throwable t) {
            Log.w(TAG, "open " + inputId + ": " + t);
            Ui.toast(app, R.string.toast_failed);
        }
    }
}
