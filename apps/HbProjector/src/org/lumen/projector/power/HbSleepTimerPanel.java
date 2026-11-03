package org.lumen.projector.power;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lumen.projector.R;
import org.lumen.projector.ui.NavRow;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Row;
import org.lumen.projector.ui.Theme;

/**
 * HB only (hb-t5): Lumen Home's "Sleep timer" tile opens the sleep timer card itself, not the power menu.
 *
 * On the Z9X the tile calls PowerMenu.show(ctx, true): the four power tiles (Sleep / Power off / Restart /
 * Sleep timer) with the timer tile focused, one OK more to reach the timer. The the device test of hb-t4
 * (2026-10-29) read that as "the sleep timer entry opens the power menu". PowerMenu and
 * PanelRequestReceiver are shared files (apps/common/projector/FILES) owned by the Z9X, so the HB gets this
 * panel instead, reached through home.HbPanelRequestReceiver. It is PowerMenu's timer card on its own:
 * same rows (15 min, 30 min, Custom with LEFT / RIGHT in 5-minute steps, 15 when held, "Turn off timer"
 * while a timer runs), same SleepTimer calls, same strings and look. BACK closes it. Starting or stopping
 * the timer closes it and SleepTimer shows its Notify card. Auto-hide 30 s.
 *
 * Safety as PowerMenu: no row acts under a curtain, and OK is ignored for {@link #OK_GUARD_MS} after the
 * panel opened (a press that began before it never acts). Main thread.
 */
public final class HbSleepTimerPanel extends Panel {
    private static final String TAG = "Z9xPowerMenu";
    /** OK / ENTER is ignored this long after the panel opened (PowerMenu.OK_GUARD_MS). */
    static final long OK_GUARD_MS = PowerMenu.OK_GUARD_MS;

    private static HbSleepTimerPanel sPanel;
    private long openedAt;

    public static void show(Context ctx) {
        if (WakeCurtain.isShowing()) {
            Log.i(TAG, "sleep timer not shown: a curtain is up");
            return;
        }
        if (sPanel == null) sPanel = new HbSleepTimerPanel();
        OverlayHost.get(ctx).show(sPanel);
    }

    private boolean canAct() {
        if (WakeCurtain.isShowing()) {
            Log.i(TAG, "OK ignored: a curtain is up");
            return false;
        }
        if (SystemClock.uptimeMillis() - openedAt < OK_GUARD_MS) {
            Log.i(TAG, "OK ignored: sleep timer not settled yet");
            return false;
        }
        return true;
    }

    private Runnable guarded(Runnable r) {
        return () -> { if (canAct()) r.run(); };
    }

    @Override protected long autoHideMs() { return 30_000; }

    @Override protected WindowManager.LayoutParams onCreateLayoutParams(Context ctx) {
        return OverlayHost.params(true, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
    }

    @Override protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // the DOWN never reaches a row while not settled, so its UP cannot act either
        return ok && SystemClock.uptimeMillis() - openedAt < OK_GUARD_MS;
    }

    @Override protected View onCreateView(Context ctx) {
        openedAt = SystemClock.uptimeMillis();
        FrameLayout stage = new FrameLayout(ctx);
        stage.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0xB3000000, 0xD9000000}));
        LinearLayout card = buildCard(ctx);
        stage.addView(card, new FrameLayout.LayoutParams(Theme.px(ctx, 640),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        View first = card.getChildAt(2);
        if (first != null) first.requestFocus();
        return stage;
    }

    @Override protected void onShown(View root) {
        root.setAlpha(0f);
        root.setScaleX(1.04f);
        root.setScaleY(1.04f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
                .setInterpolator(Theme.panelInterpolator()).start();
    }

    @Override protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(180).withEndAction(end).start();
    }

    private LinearLayout buildCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PANEL_BG);
        bg.setCornerRadius(Theme.pxf(c, Theme.PANEL_RADIUS));
        bg.setStroke(Theme.px(c, Theme.STROKE), Theme.PANEL_STROKE);
        card.setBackground(bg);
        int ph = Theme.px(c, 24);
        card.setPadding(ph, Theme.px(c, 36), ph, Theme.px(c, 24));

        TextView title = new TextView(c);
        title.setText(R.string.power_menu_timer);
        title.setTextColor(Theme.TEXT);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, 38));
        title.setPadding(Theme.px(c, Theme.ROW_PAD_H), 0, Theme.px(c, Theme.ROW_PAD_H), 0);
        card.addView(title);

        TextView sub = new TextView(c);
        boolean running = SleepTimer.isRunning() && SleepTimer.minutesLeft() > 0;
        sub.setText(running ? c.getString(R.string.power_timer_running, SleepTimer.endClock(c))
                : c.getString(R.string.power_timer_hint));
        sub.setTextColor(Theme.TEXT_DIM);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, 24));
        sub.setPadding(Theme.px(c, Theme.ROW_PAD_H), Theme.px(c, 6), Theme.px(c, Theme.ROW_PAD_H), Theme.px(c, 18));
        card.addView(sub);

        int[] presets = {15, 30};
        for (int m : presets) {
            final int min = m;
            addRow(card, new NavRow(c, PowerMenu.duration(c, m), guarded(() -> start(c, min))).setChevron(false));
        }
        addRow(card, new CustomRow(c));
        if (running) {
            addRow(card, new NavRow(c, c.getString(R.string.power_timer_cancel), guarded(() -> {
                SleepTimer.cancel(c, true);
                dismiss();
            })).setChevron(false));
        }
        return card;
    }

    private static void addRow(LinearLayout card, Row r) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.px(card.getContext(), Theme.ROW_GAP);
        card.addView(r, lp);
    }

    private void start(Context c, int min) {
        SleepTimer.start(c, min);
        dismiss();
    }

    /** "Custom    ‹ 45 min ›": LEFT / RIGHT change the time, OK starts it (PowerMenu's CustomRow). */
    private final class CustomRow extends Row {
        private int minutes;
        private CharSequence text;

        CustomRow(Context c) {
            super(c, c.getString(R.string.power_timer_custom));
            minutes = SleepTimer.customMinutes(c);
            text = PowerMenu.duration(c, minutes);
        }

        @Override protected float rightWidth() {
            float w = valuePaint.measureText(PowerMenu.duration(getContext(), 225));   // widest: "3 h 45 min"
            return w + 2 * (chevronWidth(getContext()) + padH / 2);
        }

        @Override protected void drawRight(Canvas c, float right, float cy, boolean focused) {
            float cw = chevronWidth(getContext());
            drawChevronRight(c, right, cy, focused, minutes >= SleepTimer.CUSTOM_MAX);
            float textRight = right - cw - padH / 2;
            float w = drawValueRight(c, text, textRight, cy);
            drawChevronLeft(c, textRight - w - padH / 2 - cw, cy, focused, minutes <= SleepTimer.CUSTOM_MIN);
        }

        @Override protected boolean onStep(int dir, int repeatCount) {
            int step = repeatCount > 6 ? 15 : 5;
            int v = Math.max(SleepTimer.CUSTOM_MIN, Math.min(SleepTimer.CUSTOM_MAX, minutes + dir * step));
            if (repeatCount > 6) v = Math.round(v / 5f) * 5;
            if (v != minutes) {
                minutes = v;
                text = PowerMenu.duration(getContext(), v);
                invalidate();
            }
            resetAutoHide();
            return true;                                                     // never leaves the row sideways
        }

        @Override protected boolean onActivate() {
            if (!canAct()) return true;
            SleepTimer.saveCustomMinutes(getContext(), minutes);
            start(getContext(), minutes);
            return true;
        }
    }
}
