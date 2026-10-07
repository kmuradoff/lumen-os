package org.z9x.setup.steps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.setup.L;
import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.SetupState;
import org.z9x.setup.Sys;
import org.z9x.setup.ui.LauncherPreview;
import org.z9x.setup.ui.Ui;

/**
 * Home screen choice (SPEC 4.6, PLAN C1-C3): two big cards, Lumen Home (recommended, focused by
 * default) and the classic Android TV launcher. Only stored here; Finish applies it through
 * Z9xProjector's LauncherSwitcher (bridge set_launcher). Hidden when only one launcher is installed.
 * Also the only step of the one-time "choose your home screen" after an upgrade over old data.
 */
public class LauncherStep extends Step {
    public static final String ID = "launcher";
    /** Two cards + 80 px gap fit the 1600 px content width; total height fits the 800 px column. */
    private static final int CARD_W = 720;
    private View mLumenCard;
    private View mClassicCard;

    public LauncherStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public boolean available() { return Sys.hasLumenHome(host) && Sys.hasClassic(host); }

    @Override
    public int layout() { return WIDE; }

    @Override
    public CharSequence title() { return s(R.string.launcher_title); }

    @Override
    public View createContent() {
        LinearLayout v = Ui.vbox(ctx());
        v.setClipChildren(false);
        v.setClipToPadding(false);
        TextView t = Ui.title(ctx());
        t.setText(s(R.string.launcher_title));
        v.addView(t);
        TextView sub = Ui.subtitle(ctx());
        sub.setText(s(R.string.launcher_subtitle));
        v.addView(sub, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        LinearLayout cards = Ui.hbox(ctx());
        cards.setGravity(Gravity.TOP | Gravity.START);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        mLumenCard = card(LauncherPreview.LUMEN, s(R.string.launcher_lumen), s(R.string.launcher_lumen_desc), true,
                SetupState.LAUNCHER_LUMEN);
        mClassicCard = card(LauncherPreview.CLASSIC, s(R.string.launcher_classic), s(R.string.launcher_classic_desc), false,
                SetupState.LAUNCHER_CLASSIC);
        cards.addView(mLumenCard, new LinearLayout.LayoutParams(Ui.px(CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.px(CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMarginStart(Ui.px(80));
        cards.addView(mClassicCard, cl);
        v.addView(cards, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 40));
        v.setPadding(0, Ui.px(10), 0, 0);
        return v;
    }

    private View card(int kind, String title, String desc, boolean recommended, String value) {
        Card c = new Card(ctx());
        c.setOrientation(LinearLayout.VERTICAL);
        c.setFocusable(true);
        c.setClickable(true);
        c.setPadding(Ui.px(14), Ui.px(14), Ui.px(14), Ui.px(20));
        LauncherPreview p = new LauncherPreview(ctx(), kind);
        // 16:9 preview inside the 14 px card padding
        c.addView(p, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.px((CARD_W - 28) * 9 / 16)));
        LinearLayout head = Ui.hbox(ctx());
        head.setPaddingRelative(Ui.px(10), 0, Ui.px(10), 0);
        TextView tt = Ui.text(ctx(), 34, Ui.TEXT, Ui.regular());
        tt.setText(title);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (recommended) {
            TextView badge = Ui.text(ctx(), 20, Ui.FOCUS_TEXT, Ui.medium());
            badge.setText(s(R.string.launcher_recommended));
            badge.setBackground(Ui.roundRect(Ui.ACCENT, 14));
            badge.setPadding(Ui.px(14), Ui.px(5), Ui.px(14), Ui.px(5));
            head.addView(badge);
        }
        c.addView(head, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));
        TextView dd = Ui.text(ctx(), 24, Ui.TEXT_DIM, Ui.regular());
        dd.setText(desc);
        dd.setMaxLines(2);
        dd.setEllipsize(TextUtils.TruncateAt.END);
        dd.setPaddingRelative(Ui.px(10), 0, Ui.px(10), 0);
        c.addView(dd, Ui.lpTop(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        c.setOnFocusChangeListener((view, f) -> view.animate().scaleX(f ? 1.025f : 1f).scaleY(f ? 1.025f : 1f)
                .setDuration(160).setInterpolator(Ui.decel()).start());
        c.setOnClickListener(x -> {
            host.state.setLauncher(value);
            L.i("launcher choice=" + value);
            host.next("next");
        });
        return c;
    }

    @Override
    public View initialFocus() {
        return SetupState.LAUNCHER_CLASSIC.equals(host.state.launcher()) ? mClassicCard : mLumenCard;
    }

    /** Card with the brand focus ring (light 4 px ring + soft fill) drawn over its children. */
    private static final class Card extends LinearLayout {
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();

        Card(Context c) {
            super(c);
            setWillNotDraw(false);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(Ui.pxf(4));
            mRing.setColor(Ui.TEXT);
            mFill.setColor(0x14FFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float rad = Ui.pxf(28);
            mR.set(0, 0, getWidth(), getHeight());
            c.drawRoundRect(mR, rad, rad, mFill);
            super.onDraw(c);
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (isFocused()) {
                float in = Ui.pxf(2);
                mR.set(in, in, getWidth() - in, getHeight() - in);
                c.drawRoundRect(mR, Ui.pxf(28), Ui.pxf(28), mRing);
            }
        }

        @Override
        protected void onFocusChanged(boolean f, int dir, android.graphics.Rect prev) {
            super.onFocusChanged(f, dir, prev);
            mFill.setColor(f ? 0x26FFFFFF : 0x14FFFFFF);
            invalidate();
        }
    }
}
