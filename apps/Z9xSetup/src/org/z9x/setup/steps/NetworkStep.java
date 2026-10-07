package org.z9x.setup.steps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.z9x.setup.L;
import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.net.WifiUtil;
import org.z9x.setup.ui.Field;
import org.z9x.setup.ui.Icon;
import org.z9x.setup.ui.Pill;
import org.z9x.setup.ui.Row;
import org.z9x.setup.ui.SwitchView;
import org.z9x.setup.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Network (SPEC 4.2): our own Wi-Fi list (de-duplicated, by signal, lock for secured, refreshed every
 * 10 s), password and hidden-network pages, inline progress (Connecting… -> Checking internet… ->
 * Connected), wrong password / timeout / captive portal / no internet handling, Ethernet, and
 * "Other options" (TvSettings Wi-Fi for enterprise networks). [Set up later] marks the wizard offline.
 */
public class NetworkStep extends Step {
    public static final String ID = "network";
    private static final long SCAN_MS = 10_000;
    private static final long POLL_MS = 500;
    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long NO_INTERNET_MS = 20_000;
    private static final int MAX_ROWS = 40;

    private static final int P_CONNECTED = 0, P_LIST = 1, P_PASSWORD = 2, P_MANUAL = 3, P_STATUS = 4;

    private WifiManager mWm;
    private int mPage = -1;
    private boolean mEntered;
    private boolean mReceiverOn;

    // list page
    private LinearLayout mRows;
    private TextView mListStatus;
    private Icon mListSpinner;
    private final Map<String, Row> mRowBySsid = new HashMap<>();
    private final Map<String, WifiUtil.Ap> mAps = new HashMap<>();
    private final List<String> mOrder = new ArrayList<>();
    private boolean mScanSeen;

    // password page
    private Field mPw;
    private TextView mPwError;
    private WifiUtil.Ap mTarget;

    // manual page
    private Field mManSsid;
    private Field mManPw;
    private int mManSec = WifiUtil.SEC_PSK;
    private TextView mManError;

    // attempt
    private boolean mActive;
    private String mASsid;
    private int mASec;
    private String mAPw;
    private boolean mAHidden;
    private int mANetId = -1;
    private boolean mANewConfig;
    private long mAStart;
    private long mAOnTarget;
    private int mABaseline;
    private long mALastSavedCheck;
    private boolean mAShownCaptive;
    private boolean mAShownNoInternet;
    private boolean mAWrongBroadcast;

    // status page
    private TextView mStTitle;
    private TextView mStText;
    private Icon mStIcon;
    private TextView mStMsg;
    private LinearLayout mStButtons;

    public NetworkStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public CharSequence title() { return s(R.string.net_title); }

    @Override
    public CharSequence subtitle() { return s(R.string.net_subtitle); }

    private WifiManager wm() {
        if (mWm == null) mWm = host.getSystemService(WifiManager.class);
        return mWm;
    }

    private boolean online() { return host.net != null && host.net.online(); }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public View createContent() {
        FrameLayout p = pages();
        mPage = -1;
        p.post(() -> {
            if (mActive) showStatusPage();
            else if (online()) showConnected();
            else showList();
        });
        return p;
    }

    @Override
    public View initialFocus() { return null; }

    @Override
    public void onEnter() {
        mEntered = true;
        if (!mReceiverOn) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
            host.registerReceiver(mRcv, f, null, host.main, Context.RECEIVER_NOT_EXPORTED);
            mReceiverOn = true;
        }
        host.main.post(mScanTick);
        refreshList();
        if (mActive) host.main.post(mPoll);
    }

    @Override
    public void onExit() {
        mEntered = false;
        host.main.removeCallbacks(mScanTick);
        host.main.removeCallbacks(mPoll);
        if (mReceiverOn) {
            try {
                host.unregisterReceiver(mRcv);
            } catch (RuntimeException ignored) {
            }
            mReceiverOn = false;
        }
        if (mPw != null) mPw.hideIme();
    }

    @Override
    public void onResume() {
        // back from TvSettings Wi-Fi or the captive portal login
        if (!mEntered) return;
        if (mActive) {
            host.main.removeCallbacks(mPoll);
            host.main.post(mPoll);
        } else if (online() && (mPage == P_LIST || mPage == -1)) {
            showConnected();
        }
    }

    @Override
    public void onNetChanged() {
        if (!mEntered || mActive) return;
        if (online() && mPage == P_LIST) showConnected();
        else if (!online() && mPage == P_CONNECTED) showList();
    }

    @Override
    public boolean onBack() {
        switch (mPage) {
            case P_PASSWORD:
            case P_MANUAL:
                showList();
                return true;
            case P_STATUS:
                if (mActive) cancelAttempt("back");
                showList();
                return true;
            case P_LIST:
                if (online()) {      // came here through "Change network"
                    showConnected();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private final BroadcastReceiver mRcv = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(a)) {
                mScanSeen = true;
                refreshList();
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(a)) {
                int st = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                if (st == WifiManager.WIFI_STATE_ENABLED) startScan();
                updateListStatus();
            } else if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(a)) {
                if (mActive && i.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING) {
                    mAWrongBroadcast = true;
                }
            }
        }
    };

    private final Runnable mScanTick = new Runnable() {
        @Override
        public void run() {
            if (!mEntered) return;
            if (mPage == P_LIST || mPage == -1) startScan();
            host.main.postDelayed(this, SCAN_MS);
        }
    };

    private void startScan() {
        host.bg.execute(() -> {
            try {
                WifiManager w = wm();
                if (w != null && w.isWifiEnabled()) w.startScan();
            } catch (RuntimeException e) {
                L.w("startScan", e);
            }
        });
    }

    // ------------------------------------------------------------------ connected page

    private String currentName() {
        if (host.net != null && host.net.defaultIsEthernet()) return s(R.string.net_ethernet);
        String n = host.net == null ? null : host.net.currentName();
        return n == null ? "Wi-Fi" : n;
    }

    private void showConnected() {
        mPage = P_CONNECTED;
        LinearLayout v = Ui.vbox(ctx());
        LinearLayout card = Ui.hbox(ctx());
        card.setBackground(Ui.card(28));
        card.setPadding(Ui.px(32), Ui.px(28), Ui.px(32), Ui.px(28));
        boolean eth = host.net != null && host.net.defaultIsEthernet();
        Icon ic = new Icon(ctx(), eth ? Icon.ETHERNET : Icon.WIFI).color(Ui.TEXT);
        card.addView(ic, new LinearLayout.LayoutParams(Ui.px(48), Ui.px(48)));
        TextView t = Ui.text(ctx(), 34, Ui.TEXT, Ui.regular());
        t.setText(s(R.string.net_connected, currentName()));
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.px(24));
        card.addView(t, tl);
        Icon ok = new Icon(ctx(), Icon.CHECK).color(Ui.OK);
        card.addView(ok, new LinearLayout.LayoutParams(Ui.px(40), Ui.px(40)));
        v.addView(card, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout btns = Ui.hbox(ctx());
        Pill cont = new Pill(ctx(), s(R.string.action_continue), true);
        cont.setOnClickListener(x -> {
            host.state.setOffline(false);
            host.next("next");
        });
        Pill change = new Pill(ctx(), s(R.string.net_change), false);
        change.setOnClickListener(x -> showList());
        btns.addView(cont);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMarginStart(Ui.px(20));
        btns.addView(change, cl);
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 40));
        showPage(v, cont);
    }

    // ------------------------------------------------------------------ list page

    private void showList() {
        mPage = P_LIST;
        LinearLayout v = Ui.vbox(ctx());
        v.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout head = Ui.hbox(ctx());
        head.setPaddingRelative(Ui.px(28), 0, 0, 0);
        mListSpinner = new Icon(ctx(), Icon.SPINNER).color(Ui.TEXT_DIM);
        head.addView(mListSpinner, new LinearLayout.LayoutParams(Ui.px(28), Ui.px(28)));
        mListStatus = Ui.text(ctx(), 24, Ui.TEXT_DIM, Ui.regular());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMarginStart(Ui.px(14));
        head.addView(mListStatus, sl);
        v.addView(head, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(44)));

        ScrollView sv = new ScrollView(ctx());
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(Ui.px(48));
        sv.setClipToPadding(false);
        mRows = Ui.vbox(ctx());
        mRows.setPadding(0, Ui.px(8), 0, Ui.px(8));
        sv.addView(mRows);
        v.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mRowBySsid.clear();
        mOrder.clear();

        Row manual = new Row(ctx(), s(R.string.net_add_manual));
        manual.startIcon(new Icon(ctx(), Icon.PLUS), 36);
        manual.setTag("manual");
        manual.setOnClickListener(x -> showManual());
        Row other = new Row(ctx(), s(R.string.net_other));
        other.startIcon(new Icon(ctx(), Icon.MORE), 36);
        other.setTag("other");
        other.setOnClickListener(x -> openWifiSettings());
        mRows.addView(manual, rowLp());
        mRows.addView(other, rowLp());

        Pill later = new Pill(ctx(), s(R.string.net_later), false);
        later.setOnClickListener(x -> {
            host.state.setOffline(!online());
            host.next(online() ? "next" : "skip");
        });
        LinearLayout btns = Ui.hbox(ctx());
        btns.addView(later);
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));

        refreshList();
        updateListStatus();
        showPage(v, null);
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.px(6);
        return lp;
    }

    private void updateListStatus() {
        if (mListStatus == null) return;
        WifiManager w = wm();
        boolean enabled = w != null && w.isWifiEnabled();
        String txt;
        boolean spin;
        if (!enabled) {
            txt = s(R.string.net_wifi_on);
            spin = true;
        } else if (mOrder.isEmpty()) {
            txt = mScanSeen ? s(R.string.net_none_found) : s(R.string.net_scanning);
            spin = !mScanSeen;
        } else {
            txt = "";
            spin = false;
        }
        mListStatus.setText(txt);
        mListSpinner.setVisibility(spin ? View.VISIBLE : View.GONE);
    }

    /** Scan results on the worker thread; rows updated in place so focus never jumps. */
    private void refreshList() {
        host.bg.execute(() -> {
            WifiManager w = wm();
            List<ScanResult> res = null;
            List<WifiConfiguration> saved = null;
            try {
                if (w != null) res = w.getScanResults();
            } catch (RuntimeException e) {
                L.w("scan results", e);
            }
            try {
                if (w != null) saved = w.getPrivilegedConfiguredNetworks();
            } catch (RuntimeException e) {
                L.w("saved networks", e);
            }
            List<WifiUtil.Ap> aps = WifiUtil.group(res, saved);
            final List<WifiUtil.Ap> fin = aps;
            host.main.post(() -> applyList(fin));
        });
    }

    private void applyList(List<WifiUtil.Ap> aps) {
        if (mRows == null || mPage != P_LIST) {
            mAps.clear();
            for (WifiUtil.Ap a : aps) mAps.put(a.ssid, a);
            return;
        }
        String connected = host.net == null ? null : host.net.wifiSsid();
        View focused = host.getCurrentFocus();
        Map<String, WifiUtil.Ap> now = new HashMap<>();
        for (WifiUtil.Ap a : aps) now.put(a.ssid, a);
        // drop vanished networks (keep the focused row)
        for (int i = mOrder.size() - 1; i >= 0; i--) {
            String ssid = mOrder.get(i);
            if (!now.containsKey(ssid)) {
                Row r = mRowBySsid.get(ssid);
                if (r != null && r == focused) continue;
                if (r != null) mRows.removeView(r);
                mRowBySsid.remove(ssid);
                mOrder.remove(i);
            }
        }
        mAps.clear();
        mAps.putAll(now);
        boolean hadNone = mOrder.isEmpty();
        int insertAt = mOrder.size();
        for (WifiUtil.Ap a : aps) {
            Row r = mRowBySsid.get(a.ssid);
            if (r == null) {
                if (mOrder.size() >= MAX_ROWS) continue;
                r = new Row(ctx(), a.ssid);
                r.startIcon(new Icon(ctx(), Icon.WIFI), 40);
                r.setTag(a.ssid);
                final String ssid = a.ssid;
                r.setOnClickListener(x -> onNetworkClicked(ssid));
                mRows.addView(r, insertAt, rowLp());
                insertAt++;
                mRowBySsid.put(a.ssid, r);
                mOrder.add(a.ssid);
            }
            Icon ic = (Icon) r.getChildAt(0);
            ic.level(a.bars()).locked(a.secured());
            String sub = a.ssid.equals(connected) ? s(R.string.net_ok) : a.saved ? s(R.string.net_saved) : null;
            r.subtitle(sub);
        }
        updateListStatus();
        if (hadNone && !mOrder.isEmpty() && focused != null && "manual".equals(focused.getTag())) {
            // the first networks arrived while focus sat on "Add network manually": start at the top
            mRows.getChildAt(0).requestFocus();
        } else if (mPage == P_LIST && (focused == null || !focused.isAttachedToWindow())) {
            View first = mRows.getChildAt(0);
            if (first != null) first.requestFocus();
        }
    }

    private void onNetworkClicked(String ssid) {
        WifiUtil.Ap a = mAps.get(ssid);
        if (a == null) return;
        String connected = host.net == null ? null : host.net.wifiSsid();
        if (ssid.equals(connected) && online()) {
            showConnected();
            return;
        }
        if (a.sec == WifiUtil.SEC_OTHER) {
            openWifiSettings();
            return;
        }
        if (a.saved && a.networkId >= 0) {
            startAttempt(a.ssid, a.sec, null, false, a.networkId);
            return;
        }
        if (!a.secured()) {
            startAttempt(a.ssid, a.sec, null, false, -1);
            return;
        }
        showPassword(a, null);
    }

    private void openWifiSettings() {
        try {
            host.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            L.i("wifi other options -> TvSettings");
        } catch (RuntimeException e) {
            L.w("wifi settings", e);
        }
    }

    // ------------------------------------------------------------------ password page

    private void showPassword(WifiUtil.Ap a, String error) {
        mPage = P_PASSWORD;
        mTarget = a;
        LinearLayout v = Ui.vbox(ctx());
        TextView t = Ui.text(ctx(), 34, Ui.TEXT, Ui.regular());
        t.setText(s(R.string.net_password_title, a.ssid));
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(t);
        mPw = new Field(ctx(), s(R.string.net_password_hint), true);
        v.addView(mPw, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        Row show = new Row(ctx(), s(R.string.net_show_password));
        SwitchView sw = new SwitchView(ctx());
        show.end(sw, Ui.px(72), Ui.px(40));
        show.setOnClickListener(x -> {
            sw.setOn(!sw.isOn(), true);
            mPw.setPassword(true, sw.isOn());
        });
        v.addView(show, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        mPwError = Ui.text(ctx(), 26, Ui.ERROR, Ui.regular());
        mPwError.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error != null) mPwError.setText(error);
        v.addView(mPwError, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout btns = Ui.hbox(ctx());
        Pill connect = new Pill(ctx(), s(R.string.net_connect), true);
        connect.setOnClickListener(x -> submitPassword());
        Pill cancel = new Pill(ctx(), s(R.string.action_cancel), false);
        cancel.setOnClickListener(x -> showList());
        btns.addView(connect);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMarginStart(Ui.px(20));
        btns.addView(cancel, cl);
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        mPw.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_UP)) {
                mPw.hideIme();
                submitPassword();
                return true;
            }
            return false;
        });
        showPage(v, mPw);
        mPw.postDelayed(() -> {
            if (mPage == P_PASSWORD && mPw.isAttachedToWindow()) mPw.showIme();
        }, 280);
    }

    private void submitPassword() {
        if (mTarget == null || mPw == null) return;
        String pw = mPw.getText().toString();
        int min = mTarget.sec == WifiUtil.SEC_PSK ? 8 : 1;
        if (pw.length() < min || pw.length() > 63) {
            mPwError.setText(s(R.string.net_password_short));
            mPwError.setVisibility(View.VISIBLE);
            mPw.requestFocus();
            return;
        }
        mPw.hideIme();
        startAttempt(mTarget.ssid, mTarget.sec, pw, false, -1);
    }

    // ------------------------------------------------------------------ manual (hidden) page

    private void showManual() {
        mPage = P_MANUAL;
        LinearLayout v = Ui.vbox(ctx());
        TextView t = Ui.text(ctx(), 34, Ui.TEXT, Ui.regular());
        t.setText(s(R.string.net_add_manual));
        v.addView(t);
        mManSsid = new Field(ctx(), s(R.string.net_ssid_hint), false);
        v.addView(mManSsid, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        Row sec = new Row(ctx(), s(R.string.net_security));
        mManPw = new Field(ctx(), s(R.string.net_password_hint), true);
        Runnable bindSec = () -> {
            sec.value(mManSec == WifiUtil.SEC_OPEN ? s(R.string.net_security_none) : s(R.string.net_security_wpa));
            mManPw.setVisibility(mManSec == WifiUtil.SEC_OPEN ? View.GONE : View.VISIBLE);
        };
        sec.setOnClickListener(x -> {
            mManSec = mManSec == WifiUtil.SEC_OPEN ? WifiUtil.SEC_PSK : WifiUtil.SEC_OPEN;
            bindSec.run();
        });
        bindSec.run();
        v.addView(sec, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        v.addView(mManPw, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        mManError = Ui.text(ctx(), 26, Ui.ERROR, Ui.regular());
        mManError.setVisibility(View.GONE);
        v.addView(mManError, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        LinearLayout btns = Ui.hbox(ctx());
        Pill connect = new Pill(ctx(), s(R.string.net_connect), true);
        connect.setOnClickListener(x -> submitManual());
        Pill cancel = new Pill(ctx(), s(R.string.action_cancel), false);
        cancel.setOnClickListener(x -> showList());
        btns.addView(connect);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMarginStart(Ui.px(20));
        btns.addView(cancel, cl);
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        mManSsid.setOnEditorActionListener((tv, id, ev) -> {
            mManSsid.hideIme();
            if (mManSec != WifiUtil.SEC_OPEN) mManPw.requestFocus();
            return true;
        });
        mManPw.setOnEditorActionListener((tv, id, ev) -> {
            mManPw.hideIme();
            submitManual();
            return true;
        });
        showPage(v, mManSsid);
    }

    private void submitManual() {
        String ssid = mManSsid.getText().toString().trim();
        if (ssid.isEmpty() || ssid.length() > 32) {
            mManError.setText(s(R.string.net_ssid_hint));
            mManError.setVisibility(View.VISIBLE);
            mManSsid.requestFocus();
            return;
        }
        String pw = mManSec == WifiUtil.SEC_OPEN ? null : mManPw.getText().toString();
        if (pw != null && (pw.length() < 8 || pw.length() > 63)) {
            mManError.setText(s(R.string.net_password_short));
            mManError.setVisibility(View.VISIBLE);
            mManPw.requestFocus();
            return;
        }
        mManSsid.hideIme();
        startAttempt(ssid, mManSec, pw, true, -1);
    }

    // ------------------------------------------------------------------ attempt + status page

    private void startAttempt(String ssid, int sec, String pw, boolean hidden, int networkId) {
        WifiManager w = wm();
        if (w == null) return;
        mActive = true;
        mASsid = ssid;
        mASec = sec;
        mAPw = pw;
        mAHidden = hidden;
        mANetId = networkId;
        mAStart = SystemClock.elapsedRealtime();
        mAOnTarget = 0;
        mALastSavedCheck = 0;
        mAShownCaptive = false;
        mAShownNoInternet = false;
        mAWrongBroadcast = false;
        mABaseline = 0;
        mANewConfig = networkId < 0;
        L.i("wifi connect ssid_hash=" + L.hash(ssid) + " sec=" + sec + (hidden ? " hidden" : "") + " start");
        showStatusPage();
        setStatus(Icon.SPINNER, s(R.string.net_connecting), null);
        host.bg.execute(() -> {
            try {
                List<WifiConfiguration> saved = w.getPrivilegedConfiguredNetworks();
                int base = WifiUtil.wrongPasswordCount(WifiUtil.findSaved(saved, ssid));
                WifiManager.ActionListener al = new WifiManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        L.i("wifi connect ssid_hash=" + L.hash(ssid) + " accepted");
                    }

                    @Override
                    public void onFailure(int reason) {
                        host.main.post(() -> {
                            if (mActive && ssid.equals(mASsid)) fail("error:" + reason, false);
                        });
                    }
                };
                host.main.post(() -> mABaseline = base);
                if (networkId >= 0) w.connect(networkId, al);
                else w.connect(WifiUtil.config(ssid, sec, pw, hidden), al);
            } catch (RuntimeException e) {
                L.w("wifi connect", e);
                host.main.post(() -> {
                    if (mActive && ssid.equals(mASsid)) fail("error:exception", false);
                });
            }
        });
        host.main.removeCallbacks(mPoll);
        host.main.postDelayed(mPoll, POLL_MS);
    }

    private void cancelAttempt(String why) {
        if (!mActive) return;
        L.i("wifi connect ssid_hash=" + L.hash(mASsid) + " result=" + why);
        mActive = false;
        host.main.removeCallbacks(mPoll);
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mActive || !mEntered) return;
            long now = SystemClock.elapsedRealtime();
            String cur = host.net == null ? null : host.net.wifiSsid();
            boolean onTarget = mASsid.equals(cur);
            if (onTarget && host.net.wifiValidated()) {
                success(now);
                return;
            }
            if (onTarget && host.net.wifiCaptive()) {
                if (!mAShownCaptive) {
                    mAShownCaptive = true;
                    L.i("wifi connect ssid_hash=" + L.hash(mASsid) + " captive portal");
                    setStatus(Icon.WARN, s(R.string.net_captive), null);
                    buttons(s(R.string.net_captive_open), v -> openCaptive(), s(R.string.net_choose_other), v -> chooseOther());
                }
            } else if (onTarget) {
                if (mAOnTarget == 0) {
                    mAOnTarget = now;
                    setStatus(Icon.SPINNER, s(R.string.net_checking), null);
                }
                if (!mAShownNoInternet && now - mAOnTarget > NO_INTERNET_MS) {
                    mAShownNoInternet = true;
                    L.i("wifi connect ssid_hash=" + L.hash(mASsid) + " result=no_internet");
                    setStatus(Icon.WARN, s(R.string.net_no_internet), null);
                    buttons(s(R.string.action_continue), v -> {
                        cancelAttempt("continue_without_internet");
                        host.state.setOffline(true);
                        host.next("next");
                    }, s(R.string.net_choose_other), v -> chooseOther());
                }
            } else {
                if (mAWrongBroadcast && secured()) {
                    wrongPassword();
                    return;
                }
                if (secured() && now - mALastSavedCheck >= 1000) {
                    mALastSavedCheck = now;
                    final String ssid = mASsid;
                    final long started = mAStart;
                    host.bg.execute(() -> {
                        WifiConfiguration c = null;
                        try {
                            c = WifiUtil.findSaved(wm().getPrivilegedConfiguredNetworks(), ssid);
                        } catch (RuntimeException ignored) {
                        }
                        boolean wrong = WifiUtil.wrongPasswordCount(c) > mABaseline
                                || (WifiUtil.disabledByWrongPassword(c) && SystemClock.elapsedRealtime() - started > 3000);
                        if (wrong) host.main.post(() -> {
                            if (mActive && ssid.equals(mASsid)) wrongPassword();
                        });
                    });
                }
                if (now - mAStart > CONNECT_TIMEOUT_MS) {
                    fail("timeout", true);
                    return;
                }
            }
            host.main.postDelayed(this, POLL_MS);
        }
    };

    private boolean secured() { return mASec == WifiUtil.SEC_PSK || mASec == WifiUtil.SEC_SAE; }

    private void success(long now) {
        L.i("wifi connect ssid_hash=" + L.hash(mASsid) + " result=ok ms=" + (now - mAStart));
        mActive = false;
        host.state.setOffline(false);
        setStatus(Icon.CHECK, s(R.string.net_ok), null);
        if (mStButtons != null) mStButtons.removeAllViews();
        host.main.postDelayed(() -> {
            if (host.current() == this && mPage == P_STATUS && !mActive) host.next("next");
        }, 1000);
    }

    private void wrongPassword() {
        L.i("wifi connect ssid_hash=" + L.hash(mASsid) + " result=wrong_password");
        mActive = false;
        host.main.removeCallbacks(mPoll);
        forgetIfNew();
        WifiUtil.Ap a = mAps.get(mASsid);
        if (a == null) {
            a = new WifiUtil.Ap(mASsid);
        }
        a.sec = mASec;
        a.saved = false;
        a.networkId = -1;
        if (mAHidden) {
            showManual();
            mManSsid.setText(mASsid);
            mManError.setText(s(R.string.net_wrong_password));
            mManError.setVisibility(View.VISIBLE);
            mManPw.requestFocus();
        } else {
            showPassword(a, s(R.string.net_wrong_password));
        }
    }

    private void fail(String why, boolean forget) {
        L.i("wifi connect ssid_hash=" + L.hash(mASsid) + " result=" + why);
        mActive = false;
        host.main.removeCallbacks(mPoll);
        if (forget) forgetIfNew();
        setStatus(Icon.WARN, s(R.string.net_cant_connect), null);
        buttons(s(R.string.net_try_again), v -> startAttempt(mASsid, mASec, mAPw, mAHidden, mANetId),
                s(R.string.net_choose_other), v -> chooseOther());
    }

    /** A config we created and that never worked is removed again (no stale disabled entries). */
    private void forgetIfNew() {
        if (!mANewConfig) return;
        final String ssid = mASsid;
        host.bg.execute(() -> {
            try {
                WifiConfiguration c = WifiUtil.findSaved(wm().getPrivilegedConfiguredNetworks(), ssid);
                if (c != null && !c.getNetworkSelectionStatus().hasEverConnected()) wm().forget(c.networkId, null);
            } catch (RuntimeException ignored) {
            }
        });
    }

    private void chooseOther() {
        cancelAttempt("choose_other");
        showList();
    }

    private void openCaptive() {
        Network n = host.net == null ? null : host.net.wifiNetwork();
        if (n == null) return;
        try {
            host.getSystemService(ConnectivityManager.class).startCaptivePortalApp(n);
            L.i("captive portal app started");
        } catch (RuntimeException e) {
            L.w("captive portal", e);
        }
    }

    private void showStatusPage() {
        mPage = P_STATUS;
        LinearLayout v = Ui.vbox(ctx());
        mStTitle = Ui.text(ctx(), 34, Ui.TEXT, Ui.regular());
        mStTitle.setText(mASsid);
        mStTitle.setMaxLines(2);
        mStTitle.setEllipsize(TextUtils.TruncateAt.END);
        v.addView(mStTitle);
        LinearLayout row = Ui.hbox(ctx());
        mStIcon = new Icon(ctx(), Icon.SPINNER).color(Ui.TEXT);
        row.addView(mStIcon, new LinearLayout.LayoutParams(Ui.px(40), Ui.px(40)));
        mStText = Ui.text(ctx(), 30, Ui.TEXT, Ui.regular());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.px(20));
        row.addView(mStText, tl);
        v.addView(row, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 32));
        mStMsg = Ui.body(ctx(), "");
        mStMsg.setVisibility(View.GONE);
        v.addView(mStMsg, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        mStButtons = Ui.hbox(ctx());
        v.addView(mStButtons, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 36));
        // focus holder while connecting: a quiet Cancel
        Pill cancel = new Pill(ctx(), s(R.string.action_cancel), false);
        cancel.setOnClickListener(x -> chooseOther());
        mStButtons.addView(cancel);
        showPage(v, cancel);
    }

    private void setStatus(int icon, String text, String msg) {
        if (mStIcon == null) return;
        mStIcon.type(icon).color(icon == Icon.CHECK ? Ui.OK : icon == Icon.WARN ? Ui.ERROR : Ui.TEXT);
        mStText.setText(text);
        mStMsg.setText(msg == null ? "" : msg);
        mStMsg.setVisibility(msg == null ? View.GONE : View.VISIBLE);
    }

    private void buttons(String a, View.OnClickListener la, String b, View.OnClickListener lb) {
        if (mStButtons == null) return;
        mStButtons.removeAllViews();
        Pill pa = new Pill(ctx(), a, true);
        pa.setOnClickListener(la);
        mStButtons.addView(pa);
        if (b != null) {
            Pill pb = new Pill(ctx(), b, false);
            pb.setOnClickListener(lb);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.setMarginStart(Ui.px(20));
            mStButtons.addView(pb, cl);
        }
        pa.requestFocus();
    }
}
