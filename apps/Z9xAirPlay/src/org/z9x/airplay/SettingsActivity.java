/*
 * AirPlay settings. No launcher or home-screen tile. Entry points: the "Open" button of
 * Settings > Apps > AirPlay (TvSettings' App info uses getLaunchIntentForPackage, which finds
 * this activity through its MAIN + INFO filter; launchers only list LAUNCHER /
 * LEANBACK_LAUNCHER), the service notification, and the intent action
 * org.z9x.airplay.SETTINGS (adb, other apps). Framework widgets only, D-pad friendly.
 * Opening it also starts the receiver if it is enabled, and asks once for the notification
 * permission (pre-granted in the image by default-permissions/z9x-airplay.xml).
 *
 * Replaces the Compose SettingsScreen of jqssun/android-airplay-server (GPL-3.0).
 *
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.z9x.airplay;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class SettingsActivity extends Activity implements AirPlayService.Listener {
    private SharedPreferences prefs;
    private LinearLayout list;
    private TextView statusValue, enabledValue, nameValue, accessValue, fixedValue, forgetValue,
            qualityValue, latencyValue, takeoverValue, videoLinksValue, musicValue;
    private View fixedRow, forgetRow;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        setContentView(buildViews());
        askNotificationPermission();
    }

    /** Once: the status notification (another way back here) needs POST_NOTIFICATIONS. */
    private void askNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || prefs.getBoolean(Prefs.NOTIF_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(Prefs.NOTIF_ASKED, true).apply();
        try {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        } catch (Throwable ignored) {
            // no permission UI on this image: the receiver works without the notification
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED)) AirPlayService.reload(this);
        AirPlayService.addListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AirPlayService.removeListener(this);
    }

    @Override
    public void onAirPlayStateChanged() {
        refresh();
    }

    // ------------------------------------------------------------------ values

    private void refresh() {
        boolean enabled = prefs.getBoolean(Prefs.ENABLED, Prefs.DEF_ENABLED);
        enabledValue.setText(enabled ? R.string.on : R.string.off);
        AirPlayService s = AirPlayService.instance();
        String st;
        if (!enabled) {
            st = getString(R.string.status_off);
        } else if (s == null || s.serverState == AirPlayService.ServerState.STARTING
                || s.serverState == AirPlayService.ServerState.STOPPED) {
            st = getString(R.string.connecting);
        } else if (s.serverState == AirPlayService.ServerState.ERROR) {
            st = getString(R.string.status_error);
        } else if (s.sessionActive()) {
            st = getString(R.string.status_in_use);
        } else if (s.notVisible()) {
            st = getString(R.string.status_not_visible);
        } else {
            st = getString(R.string.status_ready, s.advertisedName);
        }
        statusValue.setText(st);
        nameValue.setText(Prefs.name(this));
        int pm = new Prefs.Config(this).pinMode;
        accessValue.setText(pm == NativeBridge.PIN_RANDOM ? R.string.access_pin
                : pm == NativeBridge.PIN_FIXED ? R.string.access_fixed : R.string.access_off);
        fixedRow.setVisibility(pm == NativeBridge.PIN_FIXED ? View.VISIBLE : View.GONE);
        String fp = prefs.getString(Prefs.FIXED_PIN, "");
        fixedValue.setText(Prefs.validPin(fp) ? fp : "");
        forgetRow.setVisibility(pm != NativeBridge.PIN_OFF || !Prefs.trusted(prefs).isEmpty() ? View.VISIBLE : View.GONE);
        forgetValue.setText(getString(R.string.trusted_count, Prefs.trusted(prefs).size()));
        qualityValue.setText(prefs.getBoolean(Prefs.QUALITY_4K, Prefs.DEF_QUALITY_4K) ? R.string.quality_4k : R.string.quality_1080);
        int lat = Prefs.latencyMs(prefs);
        latencyValue.setText(lat == 150 ? R.string.latency_low : lat == 500 ? R.string.latency_smooth : R.string.latency_normal);
        takeoverValue.setText(prefs.getBoolean(Prefs.NOHOLD, Prefs.DEF_NOHOLD) ? R.string.on : R.string.off);
        videoLinksValue.setText(prefs.getBoolean(Prefs.VIDEO_URL, Prefs.DEF_VIDEO_URL) ? R.string.on : R.string.off);
        musicValue.setText(prefs.getBoolean(Prefs.MUSIC_SCREEN, Prefs.DEF_MUSIC_SCREEN) ? R.string.on : R.string.off);
    }

    /** Saves synchronously, then lets the service pick the change up (or stop). */
    private void changed(SharedPreferences.Editor e) {
        e.commit();
        AirPlayService.reload(this);
        refresh();
    }

    private void toggle(String key, boolean def) {
        changed(prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)));
    }

    // ------------------------------------------------------------------ dialogs

    private void editName() {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(Prefs.name(this));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_name)
                .setView(pad(et))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String n = Prefs.sanitizeName(et.getText().toString());
                    SharedPreferences.Editor e = prefs.edit();
                    if (n.isEmpty() || n.equals(Prefs.fallbackName(this))) e.remove(Prefs.NAME);   // follow the device name
                    else e.putString(Prefs.NAME, n);
                    changed(e);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void chooseAccess() {
        String[] items = {getString(R.string.access_off), getString(R.string.access_pin), getString(R.string.access_fixed)};
        int cur = new Prefs.Config(this).pinMode;
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_access)
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    d.dismiss();
                    if (which == NativeBridge.PIN_FIXED && !Prefs.validPin(prefs.getString(Prefs.FIXED_PIN, null))) {
                        editFixedPin(true);
                    } else {
                        changed(prefs.edit().putInt(Prefs.PIN_MODE, which));
                    }
                })
                .show();
    }

    private void editFixedPin(boolean selectMode) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setFilters(new InputFilter[] {new InputFilter.LengthFilter(4)});
        et.setHint(R.string.fixed_pin_hint);
        String cur = prefs.getString(Prefs.FIXED_PIN, "");
        if (Prefs.validPin(cur)) et.setText(cur);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.access_fixed)
                .setView(pad(et))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = et.getText().toString().trim();
            if (!Prefs.validPin(p)) {
                et.setError(getString(R.string.fixed_pin_invalid));
                return;
            }
            SharedPreferences.Editor e = prefs.edit().putString(Prefs.FIXED_PIN, p);
            if (selectMode) e.putInt(Prefs.PIN_MODE, NativeBridge.PIN_FIXED);
            changed(e);
            d.dismiss();
        }));
        d.show();
    }

    private void forgetTrusted() {
        AirPlayService.forgetTrusted(this);
        Toast.makeText(this, R.string.forget_done, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private void chooseQuality() {
        String[] items = {getString(R.string.quality_1080), getString(R.string.quality_4k)};
        int cur = prefs.getBoolean(Prefs.QUALITY_4K, Prefs.DEF_QUALITY_4K) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_quality)
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    d.dismiss();
                    changed(prefs.edit().putBoolean(Prefs.QUALITY_4K, which == 1));
                })
                .show();
    }

    private void chooseLatency() {
        String[] items = {getString(R.string.latency_low), getString(R.string.latency_normal), getString(R.string.latency_smooth)};
        int lat = Prefs.latencyMs(prefs);
        int cur = 1;
        for (int i = 0; i < Prefs.LATENCIES.length; i++) if (Prefs.LATENCIES[i] == lat) cur = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_latency)
                .setSingleChoiceItems(items, cur, (d, which) -> {
                    d.dismiss();
                    changed(prefs.edit().putInt(Prefs.LATENCY_MS, Prefs.LATENCIES[which]));
                })
                .show();
    }

    private void showLicences() {
        String[] files;
        try {
            files = getAssets().list("licenses");
        } catch (Exception e) {
            files = null;
        }
        if (files == null || files.length == 0) return;
        Arrays.sort(files);
        final String[] names = files;
        new AlertDialog.Builder(this)
                .setTitle(R.string.set_licenses)
                .setItems(names, (d, which) -> showText(names[which], readAsset("licenses/" + names[which])))
                .show();
    }

    private void showText(String title, String body) {
        TextView tv = new TextView(this);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setFocusable(true);
        tv.setPadding(dp(24), dp(8), dp(24), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(sv)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return String.valueOf(e);
        }
    }

    // ------------------------------------------------------------------ views

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(24), dp(8), dp(24), 0);
        f.addView(v);
        return f;
    }

    /** A D-pad focusable row: title above, current value below; returns the value view. */
    private TextView row(int titleRes, String summary, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(20), dp(12), dp(20), dp(12));
        r.setBackgroundResource(R.drawable.row_bg);
        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTextColor(getColor(R.color.text_primary));
        r.addView(t);
        if (summary != null) {
            TextView s = new TextView(this);
            s.setText(summary);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s.setTextColor(getColor(R.color.text_secondary));
            r.addView(s);
        }
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setTextColor(getColor(R.color.accent));
        r.addView(v);
        if (click != null) {
            r.setFocusable(true);
            r.setClickable(true);
            r.setOnClickListener(click);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        list.addView(r, lp);
        v.setTag(r);
        return v;
    }

    private View buildViews() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(getColor(R.color.background));
        sv.setFillViewport(true);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(dp(48), dp(32), dp(48), dp(32));
        sv.addView(outer);

        TextView header = new TextView(this);
        header.setText(R.string.settings_title);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        header.setTextColor(getColor(R.color.text_primary));
        header.setPadding(0, 0, 0, dp(20));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        outer.addView(header, new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(list, lp);

        enabledValue = row(R.string.set_enabled, null, v -> toggle(Prefs.ENABLED, Prefs.DEF_ENABLED));
        statusValue = row(R.string.set_status, null, null);
        nameValue = row(R.string.set_name, null, v -> editName());
        accessValue = row(R.string.set_access, null, v -> chooseAccess());
        fixedValue = row(R.string.access_fixed, null, v -> editFixedPin(false));
        fixedRow = (View) fixedValue.getTag();
        forgetValue = row(R.string.set_forget, null, v -> forgetTrusted());
        forgetRow = (View) forgetValue.getTag();
        qualityValue = row(R.string.set_quality, null, v -> chooseQuality());
        latencyValue = row(R.string.set_latency, null, v -> chooseLatency());
        takeoverValue = row(R.string.set_takeover, null, v -> toggle(Prefs.NOHOLD, Prefs.DEF_NOHOLD));
        videoLinksValue = row(R.string.set_video_links, getString(R.string.video_links_summary),
                v -> toggle(Prefs.VIDEO_URL, Prefs.DEF_VIDEO_URL));
        musicValue = row(R.string.set_music_screen, null, v -> toggle(Prefs.MUSIC_SCREEN, Prefs.DEF_MUSIC_SCREEN));
        TextView lic = row(R.string.set_licenses, null, v -> showLicences());
        lic.setText("GPL-3.0");
        TextView ver = row(R.string.version, null, null);
        String vn = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            vn = pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception ignored) {
        }
        ver.setText(vn);
        ((View) enabledValue.getTag()).requestFocus();
        return sv;
    }
}
