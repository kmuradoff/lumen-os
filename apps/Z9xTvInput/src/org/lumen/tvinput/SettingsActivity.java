/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.lumen.tvinput;

import android.app.Activity;
import android.graphics.Color;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** HDMI settings: plain focusable check boxes (works with a D-pad remote). */
public class SettingsActivity extends Activity {
    private static final String TAG = "Z9xHdmiSettings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Safe.run(TAG, "onCreate", () -> setTitle(R.string.settings_label));
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            build();
        } catch (Throwable t) {
            Log.e(TAG, "build failed", t);
            finish();
        }
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(48);
        list.setPadding(pad, dp(32), pad, dp(32));
        scroll.addView(list);

        TextView title = new TextView(this);
        title.setText(R.string.settings_label);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, dp(16));
        list.addView(title);

        if (CrashGuard.isSafeMode()) {
            TextView safe = new TextView(this);
            safe.setText(R.string.settings_safe_mode);
            safe.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            safe.setTextColor(0xFFFFC107);
            safe.setPadding(0, 0, 0, dp(12));
            list.addView(safe);
        }

        CheckBox first = toggle(list, Prefs.AUTO_SWITCH, R.string.pref_auto_switch);
        toggle(list, Prefs.RETURN_HOME, R.string.pref_return_home);
        // Lumen OS 1.0: the HDMI-CEC options (switch on One Touch Play, remote control, internal source on
        // exit) moved to the projector's quick panel page "HDMI devices (CEC)" (HdmiStateProvider
        // get_cec_prefs / set_cec_pref); this page keeps the plain HDMI options.
        bootSourceRow(list);

        TextView header = new TextView(this);
        header.setText(R.string.settings_inputs_header);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        header.setTextColor(Color.WHITE);
        header.setPadding(0, dp(24), 0, dp(8));
        list.addView(header);

        StringBuilder sb = new StringBuilder();
        for (TvInputInfo info : HdmiInputs.portInputs(this)) {
            sb.append(HdmiInputs.labelOf(this, info)).append(" — ")
                    .append(HdmiInputs.stateText(this, HdmiInputs.stateOf(this, info.getId())))
                    .append('\n').append("   ").append(info.getId()).append('\n');
        }
        if (sb.length() == 0) sb.append(getString(R.string.picker_empty));
        TextView inputs = new TextView(this);
        inputs.setText(sb.toString().trim());
        inputs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        inputs.setTextColor(Color.LTGRAY);
        list.addView(inputs);

        setContentView(scroll);
        first.requestFocus();
    }

    /**
     * The stock "Boot source" choice (home screen, then every HDMI port in order) as one focusable row; OK steps
     * to the next choice. Below it the stock note that a CEC power-on wins.
     */
    private void bootSourceRow(LinearLayout parent) {
        final List<Integer> ports = new ArrayList<>();
        ports.add(BootSource.HOME);
        for (TvInputInfo info : HdmiInputs.portInputs(this)) {
            int p = HdmiInputs.portOf(info);
            if (p > 0 && !ports.contains(p)) ports.add(p);
        }
        final TextView row = new TextView(this);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        row.setTextColor(Color.WHITE);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        int cur = BootSource.get(this);
        if (!ports.contains(cur)) cur = BootSource.HOME;
        final int[] at = {ports.indexOf(cur)};
        row.setText(getString(R.string.pref_boot_source, bootSourceName(ports.get(at[0]))));
        row.setOnClickListener(v -> {
            at[0] = (at[0] + 1) % ports.size();
            int p = ports.get(at[0]);
            BootSource.set(this, p);
            row.setText(getString(R.string.pref_boot_source, bootSourceName(p)));
        });
        parent.addView(row);
        TextView note = new TextView(this);
        note.setText(R.string.pref_boot_source_note);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        note.setTextColor(Color.LTGRAY);
        note.setPadding(dp(8), 0, dp(8), dp(8));
        parent.addView(note);
    }

    private String bootSourceName(int port) {
        return port == BootSource.HOME ? getString(R.string.boot_source_home) : "HDMI " + port;
    }

    private CheckBox toggle(LinearLayout parent, final String key, int textRes) {
        CheckBox cb = new CheckBox(this);
        cb.setText(textRes);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        cb.setFocusable(true);
        cb.setChecked(Prefs.get(this, key));
        cb.setPadding(dp(8), dp(8), dp(8), dp(8));
        cb.setOnCheckedChangeListener((v, checked) -> Prefs.set(this, key, checked));
        parent.addView(cb);
        return cb;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
