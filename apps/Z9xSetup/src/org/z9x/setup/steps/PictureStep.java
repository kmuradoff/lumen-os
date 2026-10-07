package org.z9x.setup.steps;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.setup.Bridge;
import org.z9x.setup.L;
import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.ui.GridArt;
import org.z9x.setup.ui.Icon;
import org.z9x.setup.ui.Pill;
import org.z9x.setup.ui.Row;
import org.z9x.setup.ui.SwitchView;
import org.z9x.setup.ui.Ui;

/**
 * Picture: autofocus + keystone (SPEC 4.5). Full-screen grid with a compact card. Every action is
 * one named SetupBridge method (af_run, kst_auto, kst_fit, kst_manual, focus_manual, toggle_set of
 * POWER_ON_AF / MOVE_AK); Z9xProjector keeps the HAL whitelist and its busy gates, and refuses while
 * the vendor's own power-on AF/AK runs. Busy/ready from hal_state (STATE broadcast + 2 Hz poll).
 * Hidden when the bridge is missing.
 */
public class PictureStep extends Step {
    public static final String ID = "picture";
    private static final long POLL_MS = 500;
    private static final long READY_GIVE_UP_MS = 20_000;

    private boolean mVisible;
    private long mEnter;
    private boolean mReady;
    private boolean mBusy;
    private boolean mGaveUp;
    private boolean mFitSupported;

    private Icon mStIcon;
    private TextView mSt;
    private Row mAf, mKst, mFit, mKstManual, mFocusManual, mPowerOnAf, mMoveAk;
    private SwitchView mSwAf, mSwAk;
    private Pill mContinue;

    public PictureStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public boolean available() { return !Boolean.FALSE.equals(host.bridgeOk); }

    @Override
    public int layout() { return FULL; }

    @Override
    public CharSequence title() { return s(R.string.pic_title); }

    @Override
    public View createContent() {
        FrameLayout root = new FrameLayout(ctx());
        root.addView(new GridArt(ctx()), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = Ui.vbox(ctx());
        card.setBackground(Ui.cardOpaque(36));
        card.setPadding(Ui.px(28), Ui.px(32), Ui.px(28), Ui.px(28));
        TextView t = Ui.text(ctx(), 36, Ui.TEXT, Ui.regular());
        t.setText(s(R.string.pic_title));
        t.setPaddingRelative(Ui.px(24), 0, Ui.px(24), 0);
        card.addView(t);
        TextView sub = Ui.text(ctx(), 22, Ui.TEXT_DIM, Ui.regular());
        sub.setText(s(R.string.pic_subtitle));
        sub.setPaddingRelative(Ui.px(24), 0, Ui.px(24), 0);
        sub.setLineSpacing(Ui.pxf(4), 1f);
        card.addView(sub, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        LinearLayout st = Ui.hbox(ctx());
        st.setPaddingRelative(Ui.px(24), 0, Ui.px(24), 0);
        mStIcon = new Icon(ctx(), Icon.SPINNER).color(Ui.TEXT_DIM);
        st.addView(mStIcon, new LinearLayout.LayoutParams(Ui.px(26), Ui.px(26)));
        mSt = Ui.text(ctx(), 22, Ui.TEXT_DIM, Ui.regular());
        mSt.setMaxLines(3);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sl.setMarginStart(Ui.px(12));
        st.addView(mSt, sl);
        card.addView(st, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px(56), 12));

        mAf = action(card, R.string.pic_af, () -> Bridge.afRun(host, this::onActionReply));
        mKst = action(card, R.string.pic_kst, () -> Bridge.kstAuto(host, this::onActionReply));
        mFit = action(card, R.string.pic_fit, () -> Bridge.kstFit(host, r -> {
            if ("unsupported".equals(Bridge.err(r))) {
                mFitSupported = false;
                mFit.setVisibility(View.GONE);
            }
            onActionReply(r);
        }));
        mFit.setVisibility(mFitSupported ? View.VISIBLE : View.GONE);
        mKstManual = action(card, R.string.pic_kst_manual, () -> Bridge.kstManual(host, this::onActionReply));
        mFocusManual = action(card, R.string.pic_focus_manual, () -> Bridge.focusManual(host, this::onActionReply));
        mSwAf = new SwitchView(ctx());
        mPowerOnAf = toggle(card, R.string.pic_poweron_af, mSwAf, Bridge.T_POWER_ON_AF);
        mSwAk = new SwitchView(ctx());
        mMoveAk = toggle(card, R.string.pic_move_ak, mSwAk, Bridge.T_MOVE_AK);
        mPowerOnAf.setVisibility(View.GONE);
        mMoveAk.setVisibility(View.GONE);

        mContinue = new Pill(ctx(), s(R.string.action_continue), true);
        mContinue.setOnClickListener(x -> host.next("next"));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = Ui.px(16);
        card.addView(mContinue, cl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.px(560), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        lp.setMarginEnd(Ui.px(96));
        root.addView(card, lp);
        bindState();
        return root;
    }

    private Row action(LinearLayout card, int label, Runnable r) {
        Row row = new Row(ctx(), s(label)).compact();
        row.setOnClickListener(x -> {
            if (!mReady || mBusy) {
                bindState();
                return;
            }
            r.run();
        });
        card.addView(row, rowLp());
        return row;
    }

    private Row toggle(LinearLayout card, int label, SwitchView sw, String name) {
        Row row = new Row(ctx(), s(label)).compact();
        row.end(sw, Ui.px(64), Ui.px(36));
        row.setOnClickListener(x -> {
            boolean on = !sw.isOn();
            sw.setOn(on, true);
            Bridge.toggleSet(host, name, on, r -> {
                if (Bridge.ok(r) && r.containsKey(name)) sw.setOn(r.getBoolean(name), true);
                else if (!Bridge.ok(r)) sw.setOn(!on, true);
            });
        });
        card.addView(row, rowLp());
        return row;
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.px(4);
        return lp;
    }

    @Override
    public View initialFocus() { return mAf; }

    @Override
    public void onEnter() {
        mVisible = true;
        mEnter = SystemClock.elapsedRealtime();
        mGaveUp = false;
        host.main.post(mPoll);
        Bridge.togglesGet(host, r -> {
            if (!Bridge.ok(r) || mSwAf == null) return;
            if (r.containsKey(Bridge.T_POWER_ON_AF)) {
                mSwAf.setOn(r.getBoolean(Bridge.T_POWER_ON_AF), false);
                mPowerOnAf.setVisibility(View.VISIBLE);
            }
            if (r.containsKey(Bridge.T_MOVE_AK)) {
                mSwAk.setOn(r.getBoolean(Bridge.T_MOVE_AK), false);
                mMoveAk.setVisibility(View.VISIBLE);
            }
        });
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
            Bridge.halState(host, r -> {
                if (Bridge.ok(r)) applyHal(r);
                else if (!mReady) bindState();
            });
            host.main.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onBridgeEvent(Bundle ex) {
        String w = ex.getString("what");
        if ("hal".equals(w) || "af".equals(w) || "kst".equals(w)) {
            if (ex.containsKey("ready")) applyHal(ex);
        }
    }

    private void applyHal(Bundle r) {
        boolean ready = r.getBoolean("ready", false);
        boolean busy = r.getBoolean("kstRunning", false) || r.getBoolean("afBusy", false) || r.getBoolean("akOverlay", false);
        if (r.containsKey("curtainFit")) {
            boolean fit = r.getBoolean("curtainFit", false);
            if (fit != mFitSupported) {
                mFitSupported = fit;
                if (mFit != null) mFit.setVisibility(fit ? View.VISIBLE : View.GONE);
            }
        }
        if (ready != mReady || busy != mBusy) {
            mReady = ready;
            mBusy = busy;
            bindState();
        } else if (!ready) {
            bindState();
        }
    }

    private void onActionReply(Bundle r) {
        if (Bridge.ok(r)) {
            mBusy = true; // until hal_state says otherwise
            bindState();
            return;
        }
        String e = Bridge.err(r);
        if ("busy".equals(e)) mBusy = true;
        else if ("not_ready".equals(e)) mReady = false;
        L.i("picture action refused err=" + e);
        bindState();
    }

    private void bindState() {
        if (mSt == null) return;
        if (!mReady && !mGaveUp && SystemClock.elapsedRealtime() - mEnter > READY_GIVE_UP_MS && mEnter > 0) {
            mGaveUp = true;
            L.i("picture: HAL not ready after 20 s");
        }
        boolean disabled = !mReady || mBusy;
        for (Row row : new Row[]{mAf, mKst, mFit, mKstManual, mFocusManual}) {
            if (row != null) row.setBusy(disabled);
        }
        if (mReady && !mBusy) {
            mStIcon.type(Icon.CHECK).color(Ui.OK);
            mSt.setText(s(R.string.pic_ready));
        } else if (mReady) {
            mStIcon.type(Icon.SPINNER).color(Ui.TEXT_DIM);
            mSt.setText(s(R.string.pic_busy));
        } else if (mGaveUp) {
            mStIcon.type(Icon.WARN).color(Ui.TEXT_DIM);
            mSt.setText(s(R.string.pic_unavailable));
            if (mAf != null && mAf.isFocused()) mContinue.requestFocus();
        } else {
            mStIcon.type(Icon.SPINNER).color(Ui.TEXT_DIM);
            mSt.setText(s(R.string.pic_starting));
        }
    }
}
