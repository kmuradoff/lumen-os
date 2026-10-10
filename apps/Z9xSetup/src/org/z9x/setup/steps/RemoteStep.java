package org.z9x.setup.steps;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.setup.Bridge;
import org.z9x.setup.L;
import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.ui.Icon;
import org.z9x.setup.ui.Pill;
import org.z9x.setup.ui.RemoteArt;
import org.z9x.setup.ui.Ui;

/**
 * Remote (SPEC 4.3; Lumen OS 1.0: the FIRST step, always shown). The XGIMI remote is Bluetooth only, so
 * before it is paired nothing else can be done with it. Pairing itself runs in Z9xProjector's
 * RemoteAutoPair (LE scan open for the whole setup, auto-confirmed bond); this step shows the remote with
 * Back and Home lit, "hold Back and Home for 3 seconds" (the language is not chosen yet: the device
 * locale, English on fresh data; the drawing carries the meaning) and the live state from the bridge
 * (SETUP_STATE broadcast + 1 Hz remote_state poll): searching / found &lt;name&gt; / press a button (a
 * bonded remote asleep) / connected. "Connected" is the bridge's: bonded AND link up AND its HID input
 * device up. A key from an XGIMI remote is proof too. Once connected the step asks for OK on the remote
 * (the OK key of the drawing lights up and pulses, "Press OK on the remote" under the status) and
 * continues only on an OK / ENTER from an XGIMI remote: the owner wants the remote confirmed working
 * before the setup goes on (1.0.1; the 1.0 auto-advance 1.2 s after "connected" is gone). An OK that is
 * also the first sign of the remote connects and continues at once. Without a remote the only way on
 * is the projector's own keys or a keyboard: the quiet "Skip" (focused) is for them; the remote's OK is
 * consumed by {@link #onKey} and never presses it.
 *
 * 2026-10-08 fix: the old step 3 was skipped silently (autoSkip = host.remoteConnected): the language
 * and network steps came first, the owner paired the remote there by holding Back + Home (the
 * projector's "Pairing the remote" card), so by step 3 it was connected and the step never showed.
 */
public class RemoteStep extends Step {
    public static final String ID = "remote";
    private static final long POLL_MS = 1000;
    /** HID vendor ids of the XGIMI BLE remotes (gsi/overlay keylayouts: 000d_38xx, 1d5a_c081, 26e3_af02). */
    private static final int[] REMOTE_VENDOR_IDS = {0x000d, 0x1d5a, 0x26e3};

    private RemoteArt mArt;
    private TextView mStatus;
    /** "Press OK on the remote": invisible (space kept, nothing jumps) until connected. */
    private TextView mPressOk;
    private Icon mStatusIcon;
    private Pill mSkip;
    private boolean mVisible;
    /** Connected seen on this showing of the step (bridge or a remote key); never flips back. */
    private boolean mConnected;
    /** OK pressed on the remote while connected: its UP continues. */
    private boolean mOkDown;

    public RemoteStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    /** Nothing to do here once a remote worked: BACK from the language step goes nowhere. */
    @Override
    public boolean revisitable() { return !host.remoteConnected && !host.state.remoteSeen(); }

    @Override
    public CharSequence title() { return s(R.string.remote_title); }

    @Override
    public View leftExtra() {
        LinearLayout v = Ui.vbox(ctx());
        TextView how = Ui.text(ctx(), 36, Ui.TEXT, Ui.regular());
        how.setText(s(R.string.remote_subtitle));
        how.setLineSpacing(Ui.pxf(8), 1f);
        v.addView(how);
        TextView later = Ui.text(ctx(), 36, Ui.TEXT_DIM, Ui.regular());
        later.setText(s(R.string.remote_later_hint));
        later.setLineSpacing(Ui.pxf(8), 1f);
        v.addView(later, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 36));
        return v;
    }

    @Override
    public View createContent() {
        LinearLayout v = Ui.vbox(ctx());
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        mArt = new RemoteArt(ctx());
        // 450 (was 500): room for the "press OK" line in the 800 px column (art + status + prompt + 2 pills)
        v.addView(mArt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(450)));
        LinearLayout st = Ui.hbox(ctx());
        st.setGravity(Gravity.CENTER);
        mStatusIcon = new Icon(ctx(), Icon.SPINNER).color(Ui.TEXT_DIM);
        st.addView(mStatusIcon, new LinearLayout.LayoutParams(Ui.px(36), Ui.px(36)));
        mStatus = Ui.text(ctx(), 36, Ui.TEXT, Ui.regular());
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        mStatus.setText(s(R.string.remote_searching));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMarginStart(Ui.px(18));
        st.addView(mStatus, sl);
        v.addView(st, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 36));
        mPressOk = Ui.text(ctx(), 40, RemoteArt.LIT, Ui.medium());   // warm accent = the lit OK key; 10:1 on the bg
        mPressOk.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        mPressOk.setSingleLine(true);
        mPressOk.setEllipsize(TextUtils.TruncateAt.END);
        mPressOk.setText(s(R.string.remote_press_ok));
        mPressOk.setVisibility(View.INVISIBLE);
        v.addView(mPressOk, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        // quiet (outline) pills, one above the other (36 px labels do not fit side by side in every
        // language): with a remote nobody needs them; the projector's keys or a keyboard do
        LinearLayout btns = Ui.vbox(ctx());
        btns.setGravity(Gravity.CENTER_HORIZONTAL);
        mSkip = new Pill(ctx(), s(R.string.action_skip), false);
        mSkip.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(36));
        mSkip.setOnClickListener(x -> {
            if (host.current() == this) host.next(mConnected ? "next" : "skip");
        });
        Pill other = new Pill(ctx(), s(R.string.remote_other), false);
        other.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(36));
        other.setOnClickListener(x -> {
            try {
                host.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                L.w("bt settings", e);
            }
        });
        btns.addView(mSkip);
        btns.addView(other, Ui.lpTop(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 32));
        return v;
    }

    @Override
    public View initialFocus() { return mSkip; }

    @Override
    public void onEnter() {
        mVisible = true;
        mOkDown = false;
        mConnected = false;
        host.main.post(mPoll);
    }

    @Override
    public void onExit() {
        mVisible = false;
        host.main.removeCallbacks(mPoll);
    }

    @Override
    public void onResume() {
        if (mVisible) {
            host.main.removeCallbacks(mPoll);
            host.main.post(mPoll);
        }
    }

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            if (!mVisible) return;
            if (!Boolean.FALSE.equals(host.bridgeOk)) {
                Bridge.remoteState(host, r -> {
                    if (Bridge.ok(r)) apply(r);
                });
            }
            host.main.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onBridgeEvent(Bundle ex) {
        if ("remote".equals(ex.getString("what"))) apply(ex);
    }

    /**
     * Keys of the XGIMI remote prove it works: "connected" at once. Its OK (a full press that started
     * on this step) is the confirmation the step waits for and continues (the DOWN and UP are consumed
     * so they never press Skip); other keys (BACK of the pairing combo too) go on as usual.
     */
    @Override
    public boolean onKey(KeyEvent e) {
        if (!mVisible || !fromRemote(e)) return false;
        if (!mConnected) {
            L.i("key " + e.getKeyCode() + " from the XGIMI remote on the remote step -> connected");
            onConnected();
        }
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) mOkDown = true;
        } else if (e.getAction() == KeyEvent.ACTION_UP && mOkDown && !e.isCanceled()) {
            mOkDown = false;
            L.i("OK on the XGIMI remote -> remote confirmed, continue");
            if (host.current() == this) host.next("next");
        }
        return true;
    }

    private void apply(Bundle r) {
        if (!mVisible || mStatus == null || mConnected) return;
        if (r.getBoolean("connected", false)) {
            onConnected();
            return;
        }
        mArt.setConnected(false);
        mStatusIcon.type(Icon.SPINNER).color(Ui.TEXT_DIM);
        String name = r.getString("found", "");
        if (TextUtils.isEmpty(name)) name = r.getString("name", "");
        if (r.getBoolean("pairing", false) && !TextUtils.isEmpty(name)) {
            mStatus.setText(s(R.string.remote_found, name));
        } else if (r.getInt("bonded", 0) > 0) {
            mStatus.setText(s(R.string.remote_wake));       // paired before, asleep: a key press wakes it
        } else {
            mStatus.setText(s(R.string.remote_searching));
        }
    }

    private void onConnected() {
        mConnected = true;
        host.setRemoteConnected(true);
        showConnected();
        L.i("remote connected on the step -> waiting for OK on the remote");
    }

    private void showConnected() {
        if (mStatus == null) return;
        mStatus.setText(s(R.string.remote_connected));
        mStatusIcon.type(Icon.CHECK).color(Ui.OK);
        mArt.setConnected(true);
        mArt.setAwaitOk(true);
        if (mPressOk.getVisibility() != View.VISIBLE) {
            mPressOk.setAlpha(0f);
            mPressOk.setVisibility(View.VISIBLE);
            mPressOk.animate().alpha(1f).setDuration(240).start();
        }
    }

    /** A key from an XGIMI BLE remote (not the projector's keypad, the IR receiver or a keyboard). */
    private static boolean fromRemote(KeyEvent e) {
        InputDevice d = e.getDevice();
        if (d == null || !d.isExternal()) return false;
        int vid = d.getVendorId();
        for (int v : REMOTE_VENDOR_IDS) if (v == vid) return true;
        return false;
    }
}
