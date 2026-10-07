package org.z9x.projector.power;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.projector.R;
import org.z9x.projector.ui.NavRow;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Row;
import org.z9x.projector.ui.Theme;

/**
 * MODULE "power" (v6.2): the long-press power menu (V62_REQUIREMENTS 2). Lumen OS 1.0: four round
 * tiles: Sleep (lamp off, then STR, the same as a short POWER press) / Power off / Restart / Sleep
 * timer, centred over a dimmed picture.
 * LEFT / RIGHT move, OK acts, BACK closes. Auto-hide 15 s.
 *
 * v6.2b safety (a menu must never act unseen):
 *  - never shown while the wake / sleep curtain is up ({@link #show} refuses; PowerKey lifts the
 *    curtain and waits for a new press), and no tile or row acts while a curtain is up;
 *  - no tile has focus until the enter animation has finished (the tiles are blocked, a neutral
 *    holder takes the window focus); then focus goes to the first tile, Sleep (order kept);
 *  - OK / ENTER is ignored for {@link #OK_GUARD_MS} after the menu opened and until focus is
 *    placed; an OK press that began in that time (or before the menu) never acts, because a tile
 *    acts only on the UP of a DOWN it received itself. D-pad keys are held back until then too.
 *
 * Sleep timer opens a small centred card (same panel window): 15 min, 30 min, Custom
 * (LEFT / RIGHT change it in 5-minute steps, 15 when held; OK starts) and "Turn off timer" while a
 * timer runs. BACK returns to the tiles. Starting or stopping the timer closes the menu and shows
 * the Notify card.
 *
 * Canvas tiles, framework widgets only (no AndroidX), own line icons (no XGIMI assets).
 * Animations: the scrim fades in (200 ms), the tiles rise and settle with a 40 ms stagger
 * (Theme.panelInterpolator, 320 ms); the focused tile's disc grows by 8 % (160 ms). Main thread.
 */
public final class PowerMenu extends Panel {
    private static PowerMenu sMenu;

    private FrameLayout stage;
    private LinearLayout tiles;
    private View timerCard;
    private Tile timerTile;
    private boolean timerPage;
    /** uptimeMillis when the menu was built; OK is ignored for OK_GUARD_MS after it. */
    private long openedAt;
    /** Initial focus placed (enter animation finished). */
    private boolean focusReady;
    private View focusHolder;
    private final Runnable readyFallback = this::makeReady;

    private static final String TAG = "Z9xPowerMenu";
    /** OK / ENTER is ignored this long after the menu opened. */
    static final long OK_GUARD_MS = 400;
    /** Enter animation length (scrim 200 ms; tiles 60 + 40 * 2 + 320 ms) plus margin: focus fallback. */
    private static final long READY_FALLBACK_MS = 700;

    public static void show(Context ctx) {
        show(ctx, false);
    }

    /** focusTimer: initial focus on the Sleep timer tile (Lumen Home's "Sleep timer" shortcut). */
    public static void show(Context ctx, boolean focusTimer) {
        if (WakeCurtain.isShowing()) {
            Log.i(TAG, "not shown: a curtain is up");
            return;
        }
        if (sMenu == null) sMenu = new PowerMenu();
        sMenu.focusTimerFirst = focusTimer;
        OverlayHost.get(ctx).show(sMenu);
    }

    /** One-shot: makeReady focuses the timer tile instead of the first one. */
    private boolean focusTimerFirst;

    /** No action may run under a curtain or before the menu is settled. Main thread. */
    private boolean canAct() {
        if (WakeCurtain.isShowing()) {
            Log.i(TAG, "OK ignored: a curtain is up");
            return false;
        }
        if (!focusReady || SystemClock.uptimeMillis() - openedAt < OK_GUARD_MS) {
            Log.i(TAG, "OK ignored: menu not settled yet");
            return false;
        }
        return true;
    }

    private Runnable guarded(Runnable r) {
        return () -> { if (canAct()) r.run(); };
    }

    private static boolean isOkKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private static boolean isDpadKey(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    @Override protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean settled = focusReady && SystemClock.uptimeMillis() - openedAt >= OK_GUARD_MS
                && !WakeCurtain.isShowing();
        if (settled) return false;
        if (isOkKey(code)) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                Log.i(TAG, "OK ignored: menu not settled yet");
            }
            return true;                    // the DOWN never reaches a tile, so its UP cannot act
        }
        return !focusReady && isDpadKey(code);
    }

    /** End of the enter animation (or the fallback): unblock the tiles, focus the first one. */
    private void makeReady() {
        if (focusReady || tiles == null || stage == null || !isShowing()) return;
        stage.removeCallbacks(readyFallback);
        focusReady = true;
        tiles.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View first = focusTimerFirst && timerTile != null ? timerTile
                : tiles.getChildCount() > 0 ? tiles.getChildAt(0) : null;
        focusTimerFirst = false;
        if (first != null) first.requestFocus();
        if (focusHolder != null) focusHolder.setFocusable(false);
    }

    /** "15 min", "1 h", "1 h 30 min". */
    static String duration(Context c, int minutes) {
        if (minutes < 60) return c.getString(R.string.power_timer_min, minutes);
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? c.getString(R.string.power_timer_hours, h) : c.getString(R.string.power_timer_hours_min, h, m);
    }

    @Override protected long autoHideMs() { return timerPage ? 30_000 : 15_000; }

    @Override protected WindowManager.LayoutParams onCreateLayoutParams(Context ctx) {
        return OverlayHost.params(true, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
    }

    @Override protected View onCreateView(Context ctx) {
        timerPage = false;
        timerCard = null;
        focusReady = false;
        openedAt = SystemClock.uptimeMillis();
        stage = new FrameLayout(ctx);
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0xB3000000, 0xD9000000});
        stage.setBackground(scrim);
        stage.setClipChildren(false);
        tiles = new LinearLayout(ctx);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setClipChildren(false);
        tiles.setClipToPadding(false);
        addTile(ctx, Tile.ICON_SLEEP, ctx.getString(R.string.power_menu_sleep),
                guarded(() -> PowerKey.PowerActions.sleepWithFade(ctx, "power menu sleep")));
        addTile(ctx, Tile.ICON_POWER, ctx.getString(R.string.power_menu_off),
                guarded(() -> PowerKey.PowerActions.powerOff(ctx)));
        addTile(ctx, Tile.ICON_RESTART, ctx.getString(R.string.power_menu_restart),
                guarded(() -> PowerKey.PowerActions.restart(ctx)));
        timerTile = addTile(ctx, Tile.ICON_TIMER, ctx.getString(R.string.power_menu_timer),
                guarded(() -> showTimer(ctx)));
        updateTimerTile(ctx);
        stage.addView(tiles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // Until the enter animation ends no tile may hold focus: the tiles are blocked and this
        // invisible holder takes the window's initial focus (non-touch mode auto-focuses the
        // first focusable view as soon as the window gets focus).
        tiles.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        focusHolder = new View(ctx);
        focusHolder.setFocusable(true);
        focusHolder.setDefaultFocusHighlightEnabled(false);
        stage.addView(focusHolder, 0, new FrameLayout.LayoutParams(1, 1));
        return stage;
    }

    @Override protected void onShown(View root) {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(200).start();
        float dy = Theme.pxf(root.getContext(), 36);
        int n = tiles.getChildCount();
        if (focusHolder != null) focusHolder.requestFocus();
        for (int i = 0; i < n; i++) {
            View t = tiles.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dy);
            android.view.ViewPropertyAnimator a = t.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60 + 40L * i).setDuration(320).setInterpolator(Theme.panelInterpolator());
            if (i == n - 1) a.withEndAction(this::makeReady);      // fully visible: focus Sleep
            a.start();
        }
        stage.removeCallbacks(readyFallback);
        stage.postDelayed(readyFallback, n == 0 ? 0 : READY_FALLBACK_MS);
    }

    @Override protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(180).withEndAction(end).start();
    }

    @Override protected boolean onBack() {
        if (timerPage) {
            showMain(stage.getContext());
            return true;
        }
        return false;
    }

    @Override protected void onDismissed() {
        timerPage = false;
        focusReady = false;
        if (stage != null) stage.removeCallbacks(readyFallback);
    }

    // ------------------------------------------------------------------ pages

    private Tile addTile(Context c, int icon, String label, Runnable onClick) {
        Tile t = new Tile(c, icon, label, onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Theme.px(c, 230), Theme.px(c, 270));
        int m = Theme.px(c, 22);
        lp.setMargins(m, 0, m, 0);
        tiles.addView(t, lp);
        return t;
    }

    private void updateTimerTile(Context c) {
        int left = SleepTimer.minutesLeft();
        timerTile.value = left > 0 ? c.getString(R.string.power_timer_left, duration(c, left)) : null;
        timerTile.invalidate();
    }

    private void showMain(Context c) {
        timerPage = false;
        resetAutoHide();
        final View card = timerCard;
        timerCard = null;
        if (card != null) {
            if (card instanceof ViewGroup) {
                ((ViewGroup) card).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            }
            card.animate().cancel();
            card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140)
                    .withEndAction(() -> stage.removeView(card)).start();
        }
        updateTimerTile(c);
        tiles.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        tiles.setVisibility(View.VISIBLE);
        tiles.setAlpha(0f);
        tiles.setScaleX(1.04f);
        tiles.setScaleY(1.04f);
        tiles.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(240)
                .setInterpolator(Theme.panelInterpolator()).start();
        timerTile.requestFocus();
    }

    private void showTimer(Context c) {
        timerPage = true;
        resetAutoHide();
        timerCard = buildTimerCard(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Theme.px(c, 640),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        stage.addView(timerCard, lp);
        tiles.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        tiles.animate().cancel();
        tiles.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(140)
                .withEndAction(() -> { if (timerPage) tiles.setVisibility(View.INVISIBLE); }).start();
        timerCard.setAlpha(0f);
        timerCard.setScaleX(1.04f);
        timerCard.setScaleY(1.04f);
        timerCard.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(240)
                .setInterpolator(Theme.panelInterpolator()).start();
        View first = ((ViewGroup) timerCard).getChildAt(2);
        if (first != null) first.requestFocus();
    }

    private View buildTimerCard(Context c) {
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

        int[] presets = {15, 30};                 // V62_REQUIREMENTS 2: 15, 30 or custom
        for (int m : presets) {
            final int min = m;
            addRow(card, new NavRow(c, duration(c, m), guarded(() -> start(c, min))).setChevron(false));
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

    // ------------------------------------------------------------------ custom row

    /** "Custom    ‹ 45 min ›": LEFT / RIGHT change the time, OK starts it. */
    private final class CustomRow extends Row {
        private int minutes;
        private CharSequence text;

        CustomRow(Context c) {
            super(c, c.getString(R.string.power_timer_custom));
            minutes = SleepTimer.customMinutes(c);
            text = duration(c, minutes);
        }

        @Override protected float rightWidth() {
            float w = valuePaint.measureText(duration(getContext(), 225));      // widest: "3 h 45 min"
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
                text = duration(getContext(), v);
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

    // ------------------------------------------------------------------ tile

    /** One focusable Canvas tile: a disc with a line icon, the label, an optional value line. */
    static final class Tile extends View {
        static final int ICON_POWER = 0, ICON_RESTART = 1, ICON_TIMER = 2, ICON_SLEEP = 3;

        final int icon;
        final String label;
        String value;
        private final Runnable onClick;
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text;
        private final TextPaint small;
        private final RectF r = new RectF();
        private final Path p = new Path();
        private final float discR, iconR;
        private float focus;                         // 0..1, animated
        private ValueAnimator focusAnim;
        private boolean down;
        private CharSequence shownLabel;

        Tile(Context c, int icon, String label, Runnable onClick) {
            super(c);
            this.icon = icon;
            this.label = label;
            this.onClick = onClick;
            setFocusable(true);
            setDefaultFocusHighlightEnabled(false);
            setWillNotDraw(false);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(Theme.pxf(c, 4.5f));
            ring = Theme.stroke(c, 0x24FFFFFF, 2);
            text = Theme.text(c, 28, Theme.TEXT_DIM, false);
            text.setTextAlign(Paint.Align.CENTER);
            small = Theme.text(c, 22, Theme.TEXT_FAINT, false);
            small.setTextAlign(Paint.Align.CENTER);
            discR = Theme.pxf(c, 70);
            iconR = Theme.pxf(c, 28);
            setContentDescription(label);
        }

        @Override protected void onFocusChanged(boolean gain, int dir, Rect prev) {
            super.onFocusChanged(gain, dir, prev);
            if (focusAnim != null) focusAnim.cancel();
            focusAnim = ValueAnimator.ofFloat(focus, gain ? 1f : 0f);
            focusAnim.setDuration(160);
            focusAnim.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
            focusAnim.addUpdateListener(a -> { focus = (float) a.getAnimatedValue(); invalidate(); });
            focusAnim.start();
        }

        @Override public boolean onKeyDown(int code, KeyEvent ev) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (ev.getRepeatCount() == 0) down = true;
                return true;
            }
            return super.onKeyDown(code, ev);
        }

        @Override public boolean onKeyUp(int code, KeyEvent ev) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                boolean go = down && !ev.isCanceled();
                down = false;
                if (go) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    onClick.run();
                }
                return true;
            }
            return super.onKeyUp(code, ev);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            buildIcon(w / 2f, Theme.pxf(getContext(), 96), iconR);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float cx = w / 2f, cy = Theme.pxf(getContext(), 96);
            float f = focus;
            float k = 1f + 0.08f * f;
            // disc: translucent white -> white when focused
            int a = Math.round(0x24 + (0xFF - 0x24) * f);
            disc.setColor((a << 24) | 0xFFFFFF);
            c.drawCircle(cx, cy, discR * k, disc);
            if (f < 1f) {
                ring.setAlpha(Math.round(0x24 * (1f - f)));
                c.drawCircle(cx, cy, discR * k, ring);
            }
            line.setColor(blend(0xE5FFFFFF, 0xE5000000, f));
            c.save();
            c.scale(k, k, cx, cy);
            c.drawPath(p, line);
            c.restore();
            text.setColor(blend(Theme.TEXT_DIM, 0xFFFFFFFF, f));
            float maxW = w - Theme.pxf(getContext(), 8);
            if (shownLabel == null) shownLabel = TextUtils.ellipsize(label, text, maxW, TextUtils.TruncateAt.END);
            float ty = cy + discR * 1.08f + Theme.pxf(getContext(), 50);
            c.drawText(shownLabel, 0, shownLabel.length(), cx, ty, text);
            if (value != null) {
                small.setColor(blend(Theme.TEXT_FAINT, Theme.TEXT_DIM, f));
                c.drawText(value, cx, ty + Theme.pxf(getContext(), 36), small);
            }
        }

        /** Line icon of radius s around (cx, cy), built once per size. */
        private void buildIcon(float cx, float cy, float s) {
            p.rewind();
            if (icon == ICON_POWER) {                                   // open ring + stem
                r.set(cx - s, cy - s + s * 0.1f, cx + s, cy + s + s * 0.1f);
                p.addArc(r, -55, 290);
                p.moveTo(cx, cy - s * 1.12f);
                p.lineTo(cx, cy - s * 0.12f);
            } else if (icon == ICON_RESTART) {                          // circular arrow, head at the gap
                r.set(cx - s, cy - s, cx + s, cy + s);
                p.addArc(r, -50, 290);
                double th = Math.toRadians(-50);
                float ex = cx + s * (float) Math.cos(th), ey = cy + s * (float) Math.sin(th);
                // travel direction at the start of a clockwise sweep, reversed: the head points back
                // along the tangent (counter-clockwise), like a "reload" glyph
                double dx = Math.sin(th), dy = -Math.cos(th);
                float len = s * 0.5f;
                for (int sign = -1; sign <= 1; sign += 2) {
                    double a = Math.toRadians(35 * sign);
                    double wx = dx * Math.cos(a) - dy * Math.sin(a), wy = dx * Math.sin(a) + dy * Math.cos(a);
                    p.moveTo(ex, ey);
                    p.lineTo(ex - (float) (wx * len), ey - (float) (wy * len));
                }
            } else if (icon == ICON_TIMER) {                            // clock: ring + two hands
                p.addCircle(cx, cy, s, Path.Direction.CW);
                p.moveTo(cx, cy - s * 0.62f);
                p.lineTo(cx, cy);
                p.lineTo(cx + s * 0.45f, cy + s * 0.22f);
            } else {                                                    // crescent moon (Sleep)
                Path outer = new Path();
                outer.addCircle(cx - s * 0.05f, cy + s * 0.05f, s, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(cx + s * 0.5f, cy - s * 0.42f, s * 0.82f, Path.Direction.CW);
                if (!p.op(outer, bite, Path.Op.DIFFERENCE)) p.set(outer);
            }
        }

        private static int blend(int c0, int c1, float f) {
            int a = Math.round(((c0 >>> 24) & 0xFF) + (((c1 >>> 24) & 0xFF) - ((c0 >>> 24) & 0xFF)) * f);
            int rr = Math.round(((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * f);
            int g = Math.round(((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * f);
            int b = Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * f);
            return (a << 24) | (rr << 16) | (g << 8) | b;
        }
    }
}
