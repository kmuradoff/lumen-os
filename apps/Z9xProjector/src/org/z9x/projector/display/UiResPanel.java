package org.z9x.projector.display;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.panel.QuickPanel;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

/**
 * Lumen OS 1.0.1: the full-screen screens of the interface resolution ({@link UiResolution}), in the
 * Lumen night look of the remote prompt (gradient, serif title, warm accent; text 36..64 design px,
 * i.e. 18..32 sp at every resolution, contrast well above 4.5:1):
 *  - CHOOSE: 4K (default) / 1080p (2K between them only with persist.z9x.ui_res.dev=1 or while 2K is
 *    set or runs: {@link UiRes#offered}), each with one line on what it means; the running one is
 *    marked, focus starts on the wanted one; a fallback is told calmly. BACK closes (back to the quick
 *    panel's Picture page when opened from there).
 *  - CONFIRM: what changes (sharper text vs. speed), that the projector restarts and that it comes back
 *    by itself if the picture does not; "Restart now" (focused) / "Cancel" (and BACK) back to CHOOSE.
 *  - KEEP: "Keep this resolution?" with a countdown ({@value UiRes#KEEP_SECONDS} s while shown); "Keep"
 *    (focused; OK and BACK ignored for the first {@value #OK_GUARD_MS} ms: the remote's first key after
 *    the restart) / "Back to ..." / BACK / the end of the countdown. No auto-hide. Closed by anything
 *    else: UiResolution asks again later.
 * Main thread. Views are built per show; the countdown updates one TextView once a second.
 */
final class UiResPanel extends Panel {
    private static final String TAG = UiResolution.TAG;

    private static final int CHOOSE = 0, CONFIRM = 1, KEEP = 2;
    private static final long OK_GUARD_MS = 1_000;

    // Lumen palette (same tokens as remote.RemotePrompt)
    private static final int SKY_TOP = 0xFF05080D, SKY_MID = 0xFF0D1A26, GROUND = 0xFF070A0E;
    private static final int TEXT = 0xFFF4EFE6, TEXT_2 = 0xFFC9C1B4, TEXT_3 = 0xFF9D9587, ACCENT = 0xFFF2B26B;
    private static final int ON_LIGHT = 0xFF0A0908, ON_LIGHT_2 = 0xFF3A352E;

    private static UiResPanel sShown;

    private final int mode;
    /** CONFIRM: the target. KEEP: the new resolution. */
    private final int target;
    /** KEEP: the resolution "Back to ..." restores. */
    private final int prev;
    private final boolean fromQuickPanel;
    private Context app;
    private TextView countdown;
    private long openedAt;
    private long keepLeftMs;
    /** Uptime when the countdown ends (KEEP, set when shown). */
    private long deadline;
    /** How it closed: "keep", "revert", "back", "timeout", "restart", "cancel", "choose" (null: by the host). */
    private String closeHow;
    private final Runnable tick = this::tick;

    private UiResPanel(int mode, int target, int prev, boolean fromQuickPanel) {
        this.mode = mode;
        this.target = target;
        this.prev = prev;
        this.fromQuickPanel = fromQuickPanel;
    }

    // =================================================================== entry points (main)

    /** The chooser (Projector settings row, quick panel row). */
    static void showChooser(Context ctx, boolean fromQuickPanel) {
        show(ctx, new UiResPanel(CHOOSE, 0, 0, fromQuickPanel));
    }

    /** UiResolution.keepCheck: false if the window could not be shown (retried). */
    static boolean showKeep(Context ctx, int nw, int prev, long leftMs) {
        UiResPanel p = new UiResPanel(KEEP, nw, prev, false);
        p.keepLeftMs = leftMs;
        return show(ctx, p);
    }

    static boolean isKeepShowing() {
        UiResPanel p = sShown;
        return p != null && p.mode == KEEP && p.isShowing();
    }

    private static boolean show(Context ctx, UiResPanel p) {
        try {
            p.app = ctx.getApplicationContext();
            sShown = p;
            if (OverlayHost.get(p.app).show(p)) return true;
            if (sShown == p) sShown = null;
        } catch (Throwable t) {
            Log.w(TAG, "show: " + t);
        }
        return false;
    }

    // =================================================================== Panel

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context ctx) {
        return OverlayHost.fullscreenParams(true);
    }

    @Override
    protected long autoHideMs() { return mode == KEEP ? 0 : DEFAULT_AUTO_HIDE_MS; }

    @Override
    protected View onCreateView(Context ctx) {
        openedAt = SystemClock.uptimeMillis();
        FrameLayout root = new FrameLayout(ctx);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {SKY_TOP, SKY_MID, GROUND}));
        // centred as before; a column taller than the screen (a long translation, or three rows with a
        // fallback note) scrolls with the focus instead of being cut at the top and bottom
        ScrollView scroll = new ScrollView(ctx);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        FrameLayout box = new FrameLayout(ctx);
        box.setPadding(0, px(ctx, 48), 0, px(ctx, 48));
        scroll.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        box.addView(col, new FrameLayout.LayoutParams(px(ctx, 1180), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        if (mode == CHOOSE) buildChoose(ctx, col);
        else if (mode == CONFIRM) buildConfirm(ctx, col);
        else buildKeep(ctx, col);
        return root;
    }

    private void buildChoose(Context ctx, LinearLayout col) {
        UiRes.Status st = UiResolution.status(ctx);
        col.addView(title(ctx, ctx.getString(R.string.uires_row)));
        TextView intro = text(ctx, 38, TEXT_2, Typeface.NORMAL);
        intro.setText(R.string.uires_choose_text);
        col.addView(intro, top(ctx, 28));
        if (st.fallback && st.active != 0) {
            TextView fb = text(ctx, 36, TEXT, Typeface.NORMAL);   // told calmly: no alarm colour
            fb.setText(UiResolution.summary(ctx, st));
            col.addView(fb, top(ctx, 20));
        }
        int[] rows = UiResolution.offered(st);
        View focus = null;
        for (int i = 0; i < rows.length; i++) {
            final int h = rows[i];
            View opt = option(ctx, UiResolution.choiceLabel(ctx, h) + "  ·  " + UiRes.size(h),
                    ctx.getString(meaning(h)), h == st.active, v -> onChosen(h, st));
            col.addView(opt, top(ctx, i == 0 ? 44 : 16));
            if (h == st.wanted) focus = opt;
        }
        if (focus != null) focus.post(focus::requestFocus);
    }

    /** The line under a choice: 4K sharpest text, 1080p as on XGIMI (lightest for the graphics), 2K test only. */
    private static int meaning(int h) {
        return h == UiRes.P2160 ? R.string.uires_opt_2160 : h == UiRes.P1440 ? R.string.uires_opt_1440 : R.string.uires_opt_1080;
    }

    private void buildConfirm(Context ctx, LinearLayout col) {
        UiRes.Status st = UiResolution.status(ctx);
        int from = st.active != 0 ? st.active : st.wanted;
        String to = UiRes.label(target);
        col.addView(title(ctx, ctx.getString(R.string.uires_confirm_title, to)));
        TextView what = text(ctx, 38, TEXT_2, Typeface.NORMAL);
        what.setText(target > from ? R.string.uires_confirm_up : R.string.uires_confirm_down);
        col.addView(what, top(ctx, 28));
        TextView restart = text(ctx, 38, TEXT_2, Typeface.NORMAL);
        restart.setText(ctx.getString(R.string.uires_confirm_restart, UiRes.label(UiRes.previous(st.active, st.wanted))));
        col.addView(restart, top(ctx, 20));
        LinearLayout buttons = buttonRow(ctx, col);
        TextView go = button(ctx, ctx.getString(R.string.uires_restart), v -> {
            if (okGuarded()) return;
            closeHow = "restart";
            UiResolution.change(app, target, null);
            dismiss();
        });
        buttons.addView(go);
        buttons.addView(button(ctx, ctx.getString(R.string.cancel), v -> {
            closeHow = "cancel";
            dismiss();
        }), startGap(ctx));
        go.post(go::requestFocus);
    }

    private void buildKeep(Context ctx, LinearLayout col) {
        col.addView(title(ctx, ctx.getString(R.string.uires_keep_title)));
        TextView what = text(ctx, 38, TEXT_2, Typeface.NORMAL);
        what.setText(ctx.getString(R.string.uires_keep_text, UiRes.label(target) + " (" + UiRes.size(target) + ")"));
        col.addView(what, top(ctx, 28));
        countdown = text(ctx, 40, ACCENT, Typeface.NORMAL);
        countdown.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(countdown, top(ctx, 36));
        LinearLayout buttons = buttonRow(ctx, col);
        TextView keep = button(ctx, ctx.getString(R.string.uires_keep), v -> {
            if (okGuarded()) return;
            closeHow = "keep";
            UiResolution.onKeep();
            dismiss();
        });
        buttons.addView(keep);
        buttons.addView(button(ctx, ctx.getString(R.string.uires_revert, UiRes.label(prev)), v -> {
            if (okGuarded()) return;
            closeHow = "revert";
            UiResolution.onRevert("button");
            dismiss();
        }), startGap(ctx));
        keep.post(keep::requestFocus);
        showSeconds(UiRes.secondsLeft(keepLeftMs));
    }

    @Override
    protected void onShown(View root) {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(260).setInterpolator(Theme.panelInterpolator()).withLayer().start();
        if (mode == KEEP) {
            deadline = SystemClock.uptimeMillis() + keepLeftMs;
            Ui.main().removeCallbacks(tick);
            Ui.main().post(tick);
        }
        Log.i(TAG, (mode == CHOOSE ? "chooser" : mode == CONFIRM ? "confirm " + UiRes.label(target)
                : "keep question " + UiRes.label(target) + " (" + keepLeftMs / 1000 + " s)") + " shown");
    }

    @Override
    protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(200).withLayer().withEndAction(end).start();
    }

    @Override
    protected boolean onBack() {
        if (closeHow == null) closeHow = "back";
        return false;                                   // OverlayHost closes the panel
    }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // KEEP: an early BACK (the remote's first key after the restart) must not restart again either
        boolean back = mode == KEEP && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE);
        return (ok || back) && okGuarded();
    }

    /** KEEP / CONFIRM: an OK this early was meant for something else (the screen before, the remote's wake). */
    private boolean okGuarded() {
        return mode != CHOOSE && SystemClock.uptimeMillis() - openedAt < OK_GUARD_MS;
    }

    @Override
    protected void onDismissed() {
        Ui.main().removeCallbacks(tick);
        if (sShown == this) sShown = null;
        String how = closeHow;
        if (mode == KEEP) {
            if ("back".equals(how)) {
                UiResolution.onRevert("BACK");
            } else if (how == null) {
                long left = deadline > 0 ? deadline - SystemClock.uptimeMillis() : keepLeftMs;
                UiResolution.onKeepInterrupted(left);
            }
        } else if (mode == CONFIRM && ("back".equals(how) || "cancel".equals(how))) {
            Ui.main().post(() -> showChooser(app, fromQuickPanel));
        } else if (mode == CHOOSE && "back".equals(how) && fromQuickPanel) {
            Ui.main().post(() -> QuickPanel.show(app, QuickPanel.SECTION_PICTURE));
        }
    }

    // =================================================================== actions

    private void onChosen(int h, UiRes.Status st) {
        closeHow = "choose";
        if (h == st.active && (st.fallback || h != st.wanted)) {
            // runs already (the boot fallback left it): only written (the fallback note goes), nothing
            // restarts, nothing to confirm
            UiResolution.change(app, h, null);
            dismiss();
            return;
        }
        if (h == st.wanted && (st.active == 0 || h == st.active)) {
            Log.i(TAG, UiRes.label(h) + " chosen: already set" + (st.active == 0 ? "" : " and running"));
            dismiss();
            return;
        }
        // the next panel replaces this one (OverlayHost: one panel at a time)
        show(app, new UiResPanel(CONFIRM, h, 0, fromQuickPanel));
    }

    /** KEEP: once a second until the deadline, then back to the previous resolution. */
    private void tick() {
        if (!isShowing() || mode != KEEP) return;
        long left = deadline - SystemClock.uptimeMillis();
        if (left <= 0) {
            closeHow = "timeout";
            UiResolution.onRevert("no answer in " + UiRes.KEEP_SECONDS + " s");
            dismiss();
            return;
        }
        showSeconds(UiRes.secondsLeft(left));
        long next = left % 1000;
        Ui.main().postDelayed(tick, next == 0 ? 1000 : next);
    }

    private void showSeconds(int s) {
        if (countdown != null && app != null) {
            countdown.setText(app.getString(R.string.uires_keep_countdown, UiRes.label(prev), s));
        }
    }

    // =================================================================== views

    private static int px(Context c, float design) { return Theme.px(c, design); }

    private static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, 64));
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setMaxLines(2);
        t.setText(s);
        return t;
    }

    private static TextView text(Context c, float size, int color, int style) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", style));
        t.setLineSpacing(Theme.pxf(c, 8), 1f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    private static LinearLayout.LayoutParams top(Context c, float margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(c, margin);
        return lp;
    }

    private static LinearLayout.LayoutParams startGap(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(px(c, 28));
        return lp;
    }

    private static LinearLayout buttonRow(Context c, LinearLayout col) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(c, 64);
        col.addView(row, lp);
        return row;
    }

    /** A pill button: light fill with dark text when focused, a quiet outline otherwise. */
    private static TextView button(Context c, String label, View.OnClickListener l) {
        TextView b = new TextView(c);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, 38));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ON_LIGHT, TEXT}));
        b.setText(label);
        b.setSingleLine(true);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(px(c, 260));
        b.setMinHeight(px(c, 88));
        b.setPadding(px(c, 52), 0, px(c, 52), 0);
        b.setBackground(pillBg(c, Theme.pxf(c, 44)));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    /**
     * One choice: "4K (default) · 3840 × 2160" over a line on what it means; the running one carries
     * the accent "Now" tag. The texts follow the row's focus (duplicateParentState).
     */
    private static View option(Context c, String name, String sub, boolean running, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(l);
        row.setBackground(pillBg(c, Theme.pxf(c, 28)));
        row.setPadding(px(c, 40), px(c, 22), px(c, 40), px(c, 24));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setDuplicateParentStateEnabled(true);
        TextView n = text(c, 42, TEXT, Typeface.NORMAL);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        n.setTextColor(focusColors(ON_LIGHT, TEXT));
        n.setDuplicateParentStateEnabled(true);
        n.setSingleLine(true);
        n.setText(name);
        head.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (running) {
            TextView now = text(c, 36, ACCENT, Typeface.NORMAL);
            now.setTextColor(focusColors(ON_LIGHT_2, ACCENT));
            now.setDuplicateParentStateEnabled(true);
            now.setText(R.string.uires_current);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(px(c, 24));
            head.addView(now, lp);
        }
        row.addView(head);
        TextView s = text(c, 36, TEXT_3, Typeface.NORMAL);
        s.setTextColor(focusColors(ON_LIGHT_2, TEXT_2));
        s.setDuplicateParentStateEnabled(true);
        s.setText(sub);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ls.topMargin = px(c, 6);
        row.addView(s, ls);
        return row;
    }

    private static ColorStateList focusColors(int focused, int normal) {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {focused, normal});
    }

    private static Drawable pillBg(Context c, float radius) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(TEXT);
        on.setCornerRadius(radius);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(radius);
        off.setStroke(Theme.px(c, 3), 0x66F4EFE6);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {}, off);
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        return s;
    }
}
