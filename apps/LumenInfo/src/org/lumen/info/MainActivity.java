package org.lumen.info;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.lumen.info.core.Edid;
import org.lumen.info.core.Hb;
import org.lumen.info.data.Collector;
import org.lumen.info.data.Snapshot;
import org.lumen.info.ui.EdidText;
import org.lumen.info.ui.Rows;

import java.util.ArrayList;
import java.util.List;

/**
 * The TV screen: sections on the left (Video, Audio, HDMI inputs, System, Overlay), live rows on the right,
 * refreshed every second. D-pad: up / down in either column, right / left between them; every row is focusable so
 * long pages scroll with the remote. Built in code (no layouts), dark, readable from the sofa.
 */
public final class MainActivity extends Activity implements Collector.Listener {
    private static final int OVERLAY = 4;
    private static final int C_BG = 0xFF101114, C_PANEL = 0xFF17181B, C_FOCUS = 0xFF2B3B5C, C_TEXT = 0xFFF4EFE6,
            C_DIM = 0xFFAAB0BA, C_ACCENT = 0xFFF2B26B;

    private Collector collector;
    private Prefs prefs;
    private LinearLayout nav, list;
    private ScrollView scroll;
    private final List<TextView> navItems = new ArrayList<>();
    private int section = Rows.VIDEO;
    private final List<String> shownKeys = new ArrayList<>();
    private final List<TextView> shownValues = new ArrayList<>();
    private Snapshot last;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        collector = Collector.get(this);
        prefs = new Prefs(this);
        setContentView(build());
        select(Rows.VIDEO, false);
        navItems.get(0).requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        collector.addListener(this);
        collector.acquire(this, true);
    }

    @Override
    protected void onStop() {
        collector.removeListener(this);
        collector.release(this);
        super.onStop();
    }

    // ================================================================ layout
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(C_BG);
        int safe = dp(27);
        root.setPadding(safe, safe, safe, safe);

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(getString(R.string.app_name), 26, C_TEXT, true);
        nav.addView(title);
        TextView sub = text(getString(R.string.app_subtitle), 14, C_DIM, false);
        sub.setPadding(0, dp(4), 0, dp(20));
        nav.addView(sub);
        int[] names = {R.string.sec_video, R.string.sec_audio, R.string.sec_hdmi, R.string.sec_system, R.string.sec_overlay};
        for (int i = 0; i < names.length; i++) {
            final int sec = i;
            TextView t = focusable(getString(names[i]), 18);
            t.setOnFocusChangeListener((v, has) -> { if (has) select(sec, false); });
            t.setOnClickListener(v -> select(sec, true));
            nav.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            navItems.add(t);
        }
        root.addView(nav, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(C_PANEL);
        panel.setCornerRadius(dp(14));
        scroll.setBackground(panel);
        scroll.setPadding(dp(18), dp(14), dp(18), dp(14));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        sp.leftMargin = dp(24);
        root.addView(scroll, sp);
        return root;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private StateListDrawable focusBg() {
        GradientDrawable f = new GradientDrawable();
        f.setColor(C_FOCUS);
        f.setCornerRadius(dp(10));
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[] {android.R.attr.state_focused}, f);
        d.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    private TextView focusable(String s, float sp) {
        TextView t = text(s, sp, C_TEXT, false);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBg());
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        return t;
    }

    // ================================================================ sections
    private void select(int sec, boolean moveFocus) {
        if (sec == section && list.getChildCount() > 0 && !moveFocus) return;
        section = sec;
        for (int i = 0; i < navItems.size(); i++) navItems.get(i).setTextColor(i == sec ? C_ACCENT : C_TEXT);
        rebuild();
        scroll.scrollTo(0, 0);
        if (moveFocus && list.getChildCount() > 0) {
            View first = firstFocusable(list);
            if (first != null) first.requestFocus();
        }
    }

    private View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i).isFocusable()) return g.getChildAt(i);
        return null;
    }

    private void rebuild() {
        list.removeAllViews();
        shownKeys.clear();
        shownValues.clear();
        if (section == OVERLAY) {
            overlayPage();
            return;
        }
        List<Rows.Row> rows = rows(last);
        for (Rows.Row r : rows) addRow(r);
        if (section == Rows.HDMI) hdmiPage();
    }

    private List<Rows.Row> rows(Snapshot s) {
        List<Rows.Row> rows = new ArrayList<>(Rows.section(this, s, section));
        if (section == Rows.SYSTEM) rows.addAll(Rows.about(this, s));
        return rows;
    }

    private void addRow(Rows.Row r) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setFocusable(true);
        line.setBackground(focusBg());
        line.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView l = text(r.label, 16, C_DIM, false);
        TextView v = text(r.value == null ? getString(R.string.na) : r.value, 16, r.value == null ? C_DIM : C_TEXT, false);
        line.addView(l, new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        list.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shownKeys.add(r.key);
        shownValues.add(v);
    }

    @Override
    public void onSnapshot(Snapshot s) {
        String before = hdmiShape(last);
        last = s;
        if (section == OVERLAY) return;
        List<Rows.Row> rows = rows(s);
        boolean same = rows.size() == shownKeys.size();
        for (int i = 0; same && i < rows.size(); i++) same = rows.get(i).key.equals(shownKeys.get(i));
        if (!same || (section == Rows.HDMI && !before.equals(hdmiShape(s)))) {
            View f = getCurrentFocus();
            boolean inList = f != null && f.getParent() == list;
            int idx = inList ? list.indexOfChild(f) : -1;
            rebuild();
            if (inList) {
                View n = list.getChildAt(Math.min(idx, list.getChildCount() - 1));
                if (n != null) n.requestFocus();
            }
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Rows.Row r = rows.get(i);
            TextView v = shownValues.get(i);
            String text = r.value == null ? getString(R.string.na) : r.value;
            if (!text.contentEquals(v.getText())) {
                v.setText(text);
                v.setTextColor(r.value == null ? C_DIM : C_TEXT);
            }
        }
    }

    // ================================================================ HDMI page
    /** What the HDMI page's buttons depend on: rebuilt when it changes. */
    private static String hdmiShape(Snapshot s) {
        if (s == null) return "";
        return (s.ports != null) + "/" + s.edidVersion + "/" + s.vendor + "/" + (s.builtinFiles != null);
    }

    private void hdmiPage() {
        Snapshot s = last;
        if (s != null && !s.vendor && s.time > 0 && s.inputSource == null) {
            TextView t = text(getString(R.string.not_hb), 15, C_DIM, false);
            t.setPadding(dp(12), dp(16), dp(12), dp(8));
            list.addView(t);
            return;
        }
        TextView note = text(getString(R.string.hdmi_note), 14, C_DIM, false);
        note.setPadding(dp(12), dp(16), dp(12), dp(8));
        list.addView(note);
        if (s != null && s.ports != null) {
            for (Snapshot.Port p : s.ports) {
                final Snapshot.Port port = p;
                final String name = p.file.substring(p.file.lastIndexOf('/') + 1);
                action(getString(R.string.btn_details_port, p.port), v -> details(port.edid, name));
                action(getString(R.string.btn_export_port, p.port), v -> export(name, port.edid));
            }
        }
        if (s != null && s.edidVersion != null) {
            final int other = s.edidVersion == Hb.EDID_20 ? Hb.EDID_14 : Hb.EDID_20;
            action(getString(R.string.btn_switch_edid, Hb.edidVersionName(other)), v -> confirmSwitch(other));
        }
        action(getString(R.string.btn_builtin), v -> builtin());
    }

    private void action(String label, View.OnClickListener l) {
        TextView t = focusable(label, 16);
        t.setTextColor(C_ACCENT);
        t.setOnClickListener(l);
        list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void details(Edid e, String name) {
        if (e == null) {
            Toast.makeText(this, R.string.v_edid_unreadable, Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        for (EdidText.Line l : EdidText.lines(this, e, name)) {
            TextView t = text(l.label + ": " + l.value, 14, C_TEXT, false);
            t.setFocusable(true);
            t.setBackground(focusBg());
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            box.addView(t);
        }
        TextView raw = text(getString(R.string.e_raw) + ":\n" + e.hexDump(), 12, C_DIM, false);
        raw.setTypeface(Typeface.MONOSPACE);
        raw.setFocusable(true);
        raw.setPadding(dp(8), dp(8), dp(8), dp(4));
        box.addView(raw);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name)
                .setView(sv)
                .setPositiveButton(R.string.export, (d, w) -> export(name, e))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void export(String name, Edid e) {
        String txt = e == null ? null : EdidText.text(this, e, name);
        collector.export(name, txt, path -> Toast.makeText(this,
                path == null ? getString(R.string.export_failed) : getString(R.string.exported, path),
                Toast.LENGTH_LONG).show());
    }

    private void confirmSwitch(int version) {
        String v = Hb.edidVersionName(version);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.switch_title)
                .setMessage(getString(R.string.switch_text, v))
                .setPositiveButton(R.string.switch_ok, (d, w) -> collector.switchEdid(version, ok -> {
                    Toast.makeText(this, ok ? getString(R.string.switch_done, v) : getString(R.string.switch_failed),
                            Toast.LENGTH_LONG).show();
                    if (section == Rows.HDMI) rebuild();
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void builtin() {
        Snapshot s = last;
        if (s == null || s.builtinFiles == null || s.builtinFiles.isEmpty()) {
            Toast.makeText(this, R.string.v_edid_unreadable, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = s.builtinFiles.toArray(new String[0]);
        final String[] shown = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String l = s.builtinLinks == null ? null : s.builtinLinks.get(names[i]);
            shown[i] = l == null ? names[i] : names[i] + "  \u2192 " + l;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(s.edidDir == null ? getString(R.string.builtin_title) : getString(R.string.builtin_title) + ": " + s.edidDir)
                .setItems(shown, (d, w) -> collector.decodeBuiltin(names[w], e -> details(e, names[w])))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ================================================================ overlay page
    private void overlayPage() {
        String[] layouts = {getString(R.string.o_layout_video), getString(R.string.o_layout_full)};
        String[] corners = {getString(R.string.o_corner_tl), getString(R.string.o_corner_tr),
                            getString(R.string.o_corner_bl), getString(R.string.o_corner_br)};
        String[] sizes = {getString(R.string.o_size_s), getString(R.string.o_size_m), getString(R.string.o_size_l)};
        setting(getString(R.string.o_show), () -> getString(prefs.enabled() ? R.string.on : R.string.off),
                () -> OverlayService.setEnabled(this, !prefs.enabled()));
        setting(getString(R.string.o_layout), () -> layouts[prefs.layout()], () -> prefs.setLayout(1 - prefs.layout()));
        setting(getString(R.string.o_corner), () -> corners[prefs.corner()], () -> prefs.setCorner((prefs.corner() + 1) % 4));
        setting(getString(R.string.o_size), () -> sizes[prefs.size()], () -> prefs.setSize((prefs.size() + 1) % 3));
        TextView hint = text(getString(R.string.o_hint), 14, C_DIM, false);
        hint.setPadding(dp(12), dp(16), dp(12), dp(8));
        list.addView(hint);
    }

    private interface Value { String get(); }

    private void setting(String label, Value value, Runnable change) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setFocusable(true);
        line.setClickable(true);
        line.setBackground(focusBg());
        line.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView l = text(label, 17, C_TEXT, false);
        TextView v = text(value.get(), 17, C_ACCENT, false);
        v.setGravity(Gravity.END);
        line.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        line.setOnClickListener(x -> {
            change.run();
            v.setText(value.get());
        });
        line.setOnKeyListener((x, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                change.run();
                v.setText(value.get());
                return true;
            }
            return false;
        });
        list.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
