package org.lumen.projector.fit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lumen.projector.R;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Theme;

/**
 * Fit to my screen size, calibration: one measurement (docs/fit_screen/DESIGN.md 8, steps 3-4). Full screen over
 * the corrected picture: black field, a white frame on the outermost pixels (the picture edge), a white middle
 * line with ticks every 10 %, and a card with the tape value. LEFT / RIGHT 1 mm (a held key 5 mm after 10
 * repeats), UP / DOWN 10 cm, OK gives the value, BACK stops the calibration. Main thread.
 */
final class FitCalibPanel extends Panel {
    interface Result {
        /** mm, or NaN when the user stopped (BACK, closed). Called once. */
        void onWidth(double mm);
    }

    private static final double MIN_MM = 300, MAX_MM = 6000;

    private final CharSequence step, error;
    private final Result result;
    private double mm;
    private boolean delivered;
    private TextView value;

    FitCalibPanel(CharSequence step, double startMm, CharSequence error, Result result) {
        this.step = step;
        this.error = error;
        this.result = result;
        this.mm = Math.max(MIN_MM, Math.min(MAX_MM, Math.round(startMm)));
    }

    @Override protected long autoHideMs() { return 0; }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        try {
            android.graphics.Point real = new android.graphics.Point();
            c.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(real);
            if (real.x > 0 && real.y > 0) { lp.width = real.x; lp.height = real.y; }
        } catch (Throwable t) {
            Log.w(FitRunner.TAG, "real display size: " + t);
        }
        lp.x = 0;
        lp.y = 0;
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        return lp;
    }

    @Override
    protected View onCreateView(Context c) {
        FrameLayout root = new FrameLayout(c);
        root.addView(new Pattern(c), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Theme.px(c, 32);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PANEL_BG);
        bg.setCornerRadius(Theme.px(c, 24));
        card.setBackground(bg);
        card.addView(text(c, c.getString(R.string.fit_cal_title), 36, Theme.TEXT));
        card.addView(text(c, step, 26, Theme.TEXT_DIM));
        card.addView(text(c, c.getString(R.string.fit_cal_measure), 28, Theme.TEXT));
        value = text(c, "", 56, Theme.TEXT);
        card.addView(value);
        card.addView(text(c, c.getString(R.string.fit_cal_keys), 24, Theme.TEXT_DIM));
        if (error != null) card.addView(text(c, error, 26, 0xFFFFB74D));
        root.addView(card, new FrameLayout.LayoutParams(Theme.px(c, 980), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        render();
        return root;
    }

    private void render() {
        if (value != null) value.setText(value.getContext().getString(R.string.fit_value_cm, FitScreenPage.num(mm / 10)));
    }

    private static TextView text(Context c, CharSequence s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Theme.px(c, 6), 0, Theme.px(c, 6));
        return t;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                deliver(mm);
                dismiss();
            }
            return true;
        }
        double d = 0;
        int fast = ev.getRepeatCount() > 10 ? 5 : 1;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) d = fast;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) d = -fast;
        else if (code == KeyEvent.KEYCODE_DPAD_UP) d = 100;
        else if (code == KeyEvent.KEYCODE_DPAD_DOWN) d = -100;
        else return false;
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            mm = Math.max(MIN_MM, Math.min(MAX_MM, mm + d));
            render();
        }
        return true;
    }

    @Override
    protected void onDismissed() { deliver(Double.NaN); }

    private void deliver(double v) {
        if (delivered) return;
        delivered = true;
        result.onWidth(v);
    }

    /** Black field, white frame on the outermost pixels, a middle line with ticks every 10 %. */
    private static final class Pattern extends View {
        private final Paint white = new Paint();

        Pattern(Context c) {
            super(c);
            white.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), t = Math.max(2f, Math.round(w / 640f)), tick = h / 30f;
            c.drawColor(0xFF000000);
            c.drawRect(0, 0, w, t, white);
            c.drawRect(0, h - t, w, h, white);
            c.drawRect(0, 0, t, h, white);
            c.drawRect(w - t, 0, w, h, white);
            float mid = h / 2f;
            c.drawRect(0, mid - t / 2, w, mid + t / 2, white);
            for (int i = 1; i < 10; i++) {
                float x = w * i / 10f;
                c.drawRect(x - t / 2, mid - tick, x + t / 2, mid + tick, white);
                c.drawRect(x - t / 2, 0, x + t / 2, tick, white);
            }
        }
    }
}
