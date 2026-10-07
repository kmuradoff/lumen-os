/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.tv.TvInputInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Small source list: our HDMI inputs with their state, "Home screen", "HDMI settings".
 * Used by the MENU key inside the viewer and as the launcher tile (LEANBACK_LAUNCHER only).
 * v6.1: the remote's SOURCE key opens org.z9x.projector's input overlay instead; this list is only
 * its last-resort fallback when org.z9x.projector is missing (GlobalKeyReceiver).
 */
public class InputPickerActivity extends Activity {
    private static final String TAG = "Z9xHdmiPicker";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Safe.run(TAG, "onCreate", () -> setTitle(R.string.picker_title));
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
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        list.setPadding(pad, pad, pad, pad);
        list.setMinimumWidth(dp(420));

        TextView title = new TextView(this);
        title.setText(R.string.picker_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, dp(12));
        list.addView(title);

        List<TvInputInfo> inputs = HdmiInputs.portInputs(this);
        View first = null;
        View focus = null;
        String showing = PassthroughActivity.sShowingInputId;
        if (inputs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.picker_empty);
            empty.setTextColor(Color.LTGRAY);
            list.addView(empty);
        }
        for (TvInputInfo info : inputs) {
            final String id = info.getId();
            Button b = button(HdmiInputs.labelOf(this, info) + " — "
                    + HdmiInputs.stateText(this, HdmiInputs.stateOf(this, id)));
            b.setOnClickListener(v -> Safe.run(TAG, "pick", () -> {
                HdmiInputs.openViewer(this, id, "picker");
                finish();
            }));
            list.addView(b);
            if (first == null) first = b;
            if (id.equals(showing)) focus = b;
        }

        Button home = button(getString(R.string.picker_home));
        home.setOnClickListener(v -> Safe.run(TAG, "home", () -> {
            HdmiInputs.goHome(this);
            finish();
        }));
        list.addView(home);
        if (first == null) first = home;

        Button settings = button(getString(R.string.picker_settings));
        settings.setOnClickListener(v -> Safe.run(TAG, "settings", () -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        }));
        list.addView(settings);

        setContentView(list);
        (focus != null ? focus : first).requestFocus();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
