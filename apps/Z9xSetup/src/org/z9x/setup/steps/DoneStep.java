package org.z9x.setup.steps;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.z9x.setup.Bridge;
import org.z9x.setup.L;
import org.z9x.setup.Langs;
import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.SetupState;
import org.z9x.setup.Sys;
import org.z9x.setup.net.TimeZones;
import org.z9x.setup.ui.CheckArt;
import org.z9x.setup.ui.Field;
import org.z9x.setup.ui.Icon;
import org.z9x.setup.ui.Pill;
import org.z9x.setup.ui.Row;
import org.z9x.setup.ui.Sheet;
import org.z9x.setup.ui.Ui;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Done (SPEC 4.8, PLAN C12, C17): "Lumen OS is ready", a summary (language, network, remote, Google,
 * time zone [Change], projector name [Change: XGIMI Z9X / Z9X / Projector / rooms / Other…], home
 * screen) and [Start], which runs the finish. Shows the "protected video components missing" line
 * when sys.z9x.blobs is set and not ok, and the offline hint when there was no internet.
 */
public class DoneStep extends Step {
    public static final String ID = "done";
    private static final String DEFAULT_NAME = "XGIMI Z9X";

    private Row mTz;
    private Row mName;
    private Pill mStart;
    private CheckArt mCheck;

    public DoneStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public CharSequence title() { return s(R.string.done_title); }

    @Override
    public CharSequence subtitle() { return s(R.string.done_subtitle); }

    @Override
    public View leftExtra() {
        LinearLayout v = Ui.vbox(ctx());
        mCheck = new CheckArt(ctx());
        v.addView(mCheck, new LinearLayout.LayoutParams(Ui.px(96), Ui.px(96)));
        TextView foot = Ui.text(ctx(), 22, 0x80FFFFFF, Ui.regular());
        foot.setText(s(R.string.footer_version, Sys.osVersion()));
        v.addView(foot, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 48));
        return v;
    }

    @Override
    public View createContent() {
        LinearLayout v = Ui.vbox(ctx());
        v.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ScrollView sv = new ScrollView(ctx());
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(Ui.px(40));
        sv.setClipToPadding(false);
        LinearLayout list = Ui.vbox(ctx());
        list.setPadding(0, Ui.px(4), 0, Ui.px(4));
        sv.addView(list);

        Locale loc = host.getResources().getConfiguration().getLocales().get(0);
        info(list, R.string.done_language, Langs.nativeName(loc));

        boolean online = host.net != null && host.net.online();
        String net;
        if (!online) net = s(R.string.done_no_network);
        else if (host.net.defaultIsEthernet()) net = s(R.string.net_ethernet);
        else {
            String n = host.net.currentName();
            net = n == null ? "Wi-Fi" : n;
        }
        info(list, R.string.done_network, net);

        boolean remote = host.remoteConnected || host.state.remoteSeen();
        info(list, R.string.done_remote, remote ? s(R.string.done_remote_ok) : s(R.string.done_remote_none));

        if (Sys.installed(host, Sys.PKG_GMS)) {
            String acc = null;
            try {
                Account[] a = AccountManager.get(host).getAccountsByType("com.google");
                if (a.length > 0) acc = a[0].name;
            } catch (RuntimeException ignored) {
            }
            info(list, R.string.done_google, acc != null ? acc : s(R.string.done_not_signed_in));
        }

        mTz = new Row(ctx(), s(R.string.done_timezone)).compact();
        mTz.end(new Icon(ctx(), Icon.CHEVRON), Ui.px(26), Ui.px(26));
        mTz.setOnClickListener(x -> pickTimeZone());
        list.addView(mTz, rowLp());
        bindTz();

        mName = new Row(ctx(), s(R.string.done_name)).compact();
        mName.end(new Icon(ctx(), Icon.CHEVRON), Ui.px(26), Ui.px(26));
        mName.setOnClickListener(x -> pickName());
        list.addView(mName, rowLp());
        bindName();

        if (Sys.hasLumenHome(host) || Sys.hasClassic(host)) {
            String l = host.state.launcher();
            if (l == null) l = Sys.defaultLauncher(host);
            Row home = new Row(ctx(), s(R.string.done_home)).compact();
            home.value(SetupState.LAUNCHER_CLASSIC.equals(l) ? s(R.string.launcher_classic) : s(R.string.launcher_lumen));
            if (Sys.hasLumenHome(host) && Sys.hasClassic(host)) {
                home.end(new Icon(ctx(), Icon.CHEVRON), Ui.px(26), Ui.px(26));
                home.setOnClickListener(x -> host.goTo(LauncherStep.ID));
            } else {
                home.setFocusable(false);
            }
            list.addView(home, rowLp());
        }

        String blobs = Sys.prop("sys.z9x.blobs", "");
        if (!blobs.isEmpty() && !"ok".equals(blobs)) {
            list.addView(note(Icon.WARN, s(R.string.done_blobs_missing)), rowLp());
        }
        if (!online) {
            list.addView(note(Icon.WARN, s(Sys.installed(host, Sys.PKG_GMS) ? R.string.done_offline_hint
                    : R.string.done_offline_hint_nogms)), rowLp());
        }

        v.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mStart = new Pill(ctx(), s(R.string.done_start), true);
        mStart.setMinWidth(Ui.px(280));
        mStart.setOnClickListener(x -> host.finishSetup());
        LinearLayout btns = Ui.hbox(ctx());
        btns.addView(mStart);
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));
        return v;
    }

    private void info(LinearLayout list, int label, String value) {
        Row r = new Row(ctx(), s(label)).compact();
        r.value(value);
        r.setFocusable(true);
        r.setClickable(false);
        list.addView(r, rowLp());
    }

    private View note(int icon, String text) {
        LinearLayout n = Ui.hbox(ctx());
        n.setGravity(Gravity.TOP);
        n.setPadding(Ui.px(24), Ui.px(14), Ui.px(24), Ui.px(8));
        n.addView(new Icon(ctx(), icon).color(Ui.TEXT_DIM), new LinearLayout.LayoutParams(Ui.px(30), Ui.px(30)));
        TextView t = Ui.body(ctx(), text);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.pxf(24));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(Ui.px(16));
        n.addView(t, lp);
        return n;
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.px(4);
        return lp;
    }

    @Override
    public View initialFocus() { return mStart; }

    @Override
    public void onEnter() {
        if (mCheck != null) mCheck.play();
    }

    @Override
    public void onNetChanged() {
        bindTz(); // the IP time zone may arrive while the summary is open
    }

    // ------------------------------------------------------------------ time zone

    private void bindTz() {
        if (mTz == null) return;
        String id = TimeZone.getDefault().getID();
        String label = TimeZones.label(id);
        boolean detected = !"none".equals(host.state.tzSource()) && !host.state.tzManual();
        mTz.value(detected ? s(R.string.done_tz_detected, label) : label);
    }

    private void pickTimeZone() {
        String cc = host.state.geoCountry();
        if (cc == null) cc = host.getResources().getConfiguration().getLocales().get(0).getCountry();
        final String country = cc;
        host.bg.execute(() -> {
            List<String> zones = TimeZones.forCountry(country);
            host.main.post(() -> {
                if (host.current() != this) return;
                if (zones.isEmpty()) {
                    pickRegion();
                    return;
                }
                showZoneSheet(zones, true);
            });
        });
    }

    private void showZoneSheet(List<String> zones, boolean withAll) {
        Sheet sh = new Sheet(ctx(), s(R.string.tz_pick_title), null);
        LinearLayout list = sh.addList();
        String cur = TimeZone.getDefault().getID();
        View focus = null;
        for (String id : zones) {
            Row r = new Row(ctx(), TimeZones.label(id));
            if (id.equals(cur)) {
                r.end(new Icon(ctx(), Icon.CHECK), Ui.px(32), Ui.px(32));
                focus = r;
            }
            r.setOnClickListener(x -> {
                sh.dismiss();
                applyTz(id);
            });
            list.addView(r, rowLp());
            if (focus == null && list.getChildCount() == 1) focus = r;
        }
        if (withAll) {
            Row all = new Row(ctx(), s(R.string.tz_all));
            all.end(new Icon(ctx(), Icon.CHEVRON), Ui.px(28), Ui.px(28));
            all.setOnClickListener(x -> {
                sh.dismiss();
                host.main.postDelayed(this::pickRegion, 160);
            });
            list.addView(all, rowLp());
            if (focus == null) focus = all;
        }
        sh.addButton(s(R.string.action_cancel), false, x -> sh.dismiss());
        host.showSheet(sh, focus);
    }

    private void pickRegion() {
        Sheet sh = new Sheet(ctx(), s(R.string.tz_all), null);
        LinearLayout list = sh.addList();
        View first = null;
        for (String region : TimeZones.regions()) {
            Row r = new Row(ctx(), region);
            r.end(new Icon(ctx(), Icon.CHEVRON), Ui.px(28), Ui.px(28));
            r.setOnClickListener(x -> {
                sh.dismiss();
                host.bg.execute(() -> {
                    List<String> zones = TimeZones.forRegion(region);
                    host.main.post(() -> {
                        if (host.current() == this) showZoneSheet(zones, false);
                    });
                });
            });
            list.addView(r, rowLp());
            if (first == null) first = r;
        }
        sh.addButton(s(R.string.action_cancel), false, x -> sh.dismiss());
        host.showSheet(sh, first);
    }

    private void applyTz(String id) {
        host.bg.execute(() -> {
            boolean ok = TimeZones.apply(host, id);
            host.main.post(() -> {
                if (ok) {
                    host.state.setTzManual(true);
                    host.state.setTzSource("manual");
                    L.i("tz source=manual id=" + id);
                }
                // TimeZone.getDefault() of this process follows ACTION_TIMEZONE_CHANGED; set it now
                TimeZone.setDefault(TimeZone.getTimeZone(id));
                bindTz();
            });
        });
    }

    // ------------------------------------------------------------------ projector name (C12)

    private String currentName() {
        String n = null;
        try {
            n = Settings.Global.getString(host.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (RuntimeException ignored) {
        }
        return n == null || n.trim().isEmpty() ? DEFAULT_NAME : n;
    }

    private void bindName() {
        if (mName != null) mName.value(currentName());
    }

    private void pickName() {
        Sheet sh = new Sheet(ctx(), s(R.string.name_pick_title), null);
        LinearLayout list = sh.addList();
        String cur = currentName();
        String[] options = {DEFAULT_NAME, "Z9X", s(R.string.name_projector), s(R.string.name_living_room),
                s(R.string.name_bedroom), s(R.string.name_cinema), s(R.string.name_office)};
        View focus = null;
        boolean matched = false;
        for (String o : options) {
            Row r = new Row(ctx(), o);
            if (o.equals(cur)) {
                r.end(new Icon(ctx(), Icon.CHECK), Ui.px(32), Ui.px(32));
                focus = r;
                matched = true;
            }
            r.setOnClickListener(x -> {
                sh.dismiss();
                applyName(o);
            });
            list.addView(r, rowLp());
            if (focus == null && list.getChildCount() == 1) focus = r;
        }
        Row other = new Row(ctx(), s(R.string.name_other));
        if (!matched) {
            other.subtitle(cur);
            other.end(new Icon(ctx(), Icon.CHECK), Ui.px(32), Ui.px(32));
            focus = other;
        }
        other.setOnClickListener(x -> {
            sh.dismiss();
            host.main.postDelayed(() -> nameField(cur), 160);
        });
        list.addView(other, rowLp());
        sh.addButton(s(R.string.action_cancel), false, x -> sh.dismiss());
        host.showSheet(sh, focus);
    }

    private void nameField(String cur) {
        Sheet sh = new Sheet(ctx(), s(R.string.name_pick_title), null);
        Field f = new Field(ctx(), s(R.string.name_hint), false);
        f.setText(cur);
        f.setSelection(f.length());
        f.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        sh.body.addView(f, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable ok = () -> {
            String n = Bridge.cleanName(f.getText().toString());
            if (n == null) {
                f.requestFocus();
                return;
            }
            f.hideIme();
            sh.dismiss();
            applyName(n);
        };
        f.setOnEditorActionListener((tv, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE || ev != null) {
                ok.run();
                return true;
            }
            return false;
        });
        sh.addButton(s(R.string.action_continue), true, x -> ok.run());
        sh.addButton(s(R.string.action_cancel), false, x -> {
            f.hideIme();
            sh.dismiss();
        });
        host.showSheet(sh, f);
        f.postDelayed(f::showIme, 300);
    }

    /** Through the bridge (device_name + Bluetooth name; Cast and AirPlay follow device_name). */
    private void applyName(String name) {
        String n = Bridge.cleanName(name);
        if (n == null) return;
        if (mName != null) mName.value(n);
        host.bg.execute(() -> {
            Bundle r = Bridge.setDeviceNameSync(host, n);
            if (!Bridge.ok(r)) {
                // bridge missing: at least the Settings name (Cast/AirPlay); Bluetooth keeps its name
                try {
                    Settings.Global.putString(host.getContentResolver(), Settings.Global.DEVICE_NAME, n);
                } catch (RuntimeException e) {
                    L.w("device_name", e);
                }
                L.w("device name via bridge failed err=" + Bridge.err(r) + "; wrote device_name only");
            } else {
                L.i("device name set");
            }
            host.main.post(this::bindName);
        });
    }
}
