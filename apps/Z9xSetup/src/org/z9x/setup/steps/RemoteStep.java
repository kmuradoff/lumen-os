package org.z9x.setup.steps;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
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
 * Remote (SPEC 4.3). Pairing itself runs in Z9xProjector's RemoteAutoPair (LE scan open for the
 * whole setup, auto-confirmed bond); this step only shows how to start pairing and the live state
 * from the bridge (STATE broadcast + 1 Hz remote_state poll). Hidden when a remote is already
 * connected; auto-advances 1.2 s after it connects. No new pairing code, no MANUAL window.
 */
public class RemoteStep extends Step {
    public static final String ID = "remote";
    private static final long POLL_MS = 1000;
    private static final long ADVANCE_MS = 1200;

    private RemoteArt mArt;
    private TextView mStatus;
    private Icon mStatusIcon;
    private boolean mVisible;
    private boolean mAdvancing;

    public RemoteStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public boolean autoSkip() { return host.remoteConnected; }

    @Override
    public CharSequence title() { return s(R.string.remote_title); }

    @Override
    public CharSequence subtitle() { return s(R.string.remote_subtitle); }

    @Override
    public View leftExtra() {
        TextView t = Ui.body(ctx(), s(R.string.remote_skip_hint));
        return t;
    }

    @Override
    public View createContent() {
        LinearLayout v = Ui.vbox(ctx());
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        mArt = new RemoteArt(ctx());
        v.addView(mArt, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(440)));
        LinearLayout st = Ui.hbox(ctx());
        st.setGravity(Gravity.CENTER);
        mStatusIcon = new Icon(ctx(), Icon.SPINNER).color(Ui.TEXT_DIM);
        st.addView(mStatusIcon, new LinearLayout.LayoutParams(Ui.px(32), Ui.px(32)));
        mStatus = Ui.text(ctx(), 28, Ui.TEXT, Ui.regular());
        mStatus.setText(s(R.string.remote_searching));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMarginStart(Ui.px(16));
        st.addView(mStatus, sl);
        v.addView(st, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 32));
        LinearLayout btns = Ui.hbox(ctx());
        btns.setGravity(Gravity.CENTER);
        Pill skip = new Pill(ctx(), s(R.string.action_skip), true);
        skip.setOnClickListener(x -> host.next(host.remoteConnected ? "next" : "skip"));
        Pill other = new Pill(ctx(), s(R.string.remote_other), false);
        other.setOnClickListener(x -> {
            try {
                host.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                L.w("bt settings", e);
            }
        });
        btns.addView(skip);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.setMarginStart(Ui.px(20));
        btns.addView(other, ol);
        v.addView(btns, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 36));
        return v;
    }

    @Override
    public void onEnter() {
        mVisible = true;
        mAdvancing = false;
        host.main.post(mPoll);
    }

    @Override
    public void onExit() {
        mVisible = false;
        host.main.removeCallbacks(mPoll);
        host.main.removeCallbacks(mAdvance);
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

    private final Runnable mAdvance = () -> {
        if (mVisible && host.current() == this) host.next("auto");
    };

    @Override
    public void onBridgeEvent(Bundle ex) {
        if ("remote".equals(ex.getString("what"))) apply(ex);
    }

    private void apply(Bundle r) {
        if (!mVisible || mStatus == null) return;
        boolean connected = r.getBoolean("connected", false);
        String name = r.getString("name", null);
        if (connected) {
            mStatus.setText(s(R.string.remote_connected));
            mStatusIcon.type(Icon.CHECK).color(Ui.OK);
            mArt.setConnected(true);
            if (!mAdvancing) {
                mAdvancing = true;
                L.i("remote connected on the step -> auto-advance");
                host.main.postDelayed(mAdvance, ADVANCE_MS);
            }
        } else {
            mArt.setConnected(false);
            mStatusIcon.type(Icon.SPINNER).color(Ui.TEXT_DIM);
            if (name != null && !name.isEmpty()) mStatus.setText(s(R.string.remote_found, name));
            else mStatus.setText(s(R.string.remote_searching));
        }
    }
}
