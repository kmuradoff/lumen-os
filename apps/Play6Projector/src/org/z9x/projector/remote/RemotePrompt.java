package org.z9x.projector.remote;

import android.app.DreamManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.projector.KeyReceiver;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ak.AkOverlay;
import org.z9x.projector.power.StandbyController;
import org.z9x.projector.power.WakeCurtain;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

/**
 * Lumen OS 1.0: the full-screen "Reconnect your remote" prompt (RemoteAutoPair decides when). Dark
 * Lumen look (the D_Calm night sky, warm accent): the XGIMI remote with Back and Home lit and pulsing,
 * "hold Back and Home for 3 seconds", a live status (looking / press any button (still paired) / found
 * &lt;name&gt; / connected) and one
 * quiet "Not now" button for the projector's own keys or a keyboard (BACK closes too).
 *
 * While it is shown RemoteAutoPair scans as a fast NEW window; the status is polled once a second and
 * pushed on every bond / link change. Once the remote is usable (HID up) the prompt shows "Remote
 * connected" and closes itself {@value #CONNECTED_HOLD_MS} ms later. A key from an XGIMI remote is proof
 * too (and never acts on the button). Closing by the user (button, BACK, HOME, another window, the
 * {@value #AUTO_HIDE_MS} ms auto-hide) snoozes the next prompt; screen off / standby does not.
 * Never during the first-run setup, in standby, while dreaming or under another overlay. Main thread.
 */
public final class RemotePrompt extends Panel {
    private static final String TAG = "Z9xRemote";

    /** maybeShow results. */
    static final int SHOWN = 0, LATER = 1, SKIP = 2;
    /** How the prompt closed (RemoteAutoPair.onPromptClosed). */
    static final String CLOSED_CONNECTED = "connected", CLOSED_USER = "user", CLOSED_OFF = "off";

    private static final long CONNECTED_HOLD_MS = 1_200;
    private static final long AUTO_HIDE_MS = 120_000;
    private static final long POLL_MS = 1_000;
    /** OK is ignored this long after the prompt opened (a press meant for something else). */
    private static final long OK_GUARD_MS = 400;

    // Lumen palette (design reference: ground, text, secondary, tertiary, warm accent)
    private static final int SKY_TOP = 0xFF05080D, SKY_MID = 0xFF0D1A26, GROUND = 0xFF070A0E;
    private static final int TEXT = 0xFFF4EFE6, TEXT_2 = 0xFFC9C1B4, TEXT_3 = 0xFF9D9587, ACCENT = 0xFFF2B26B;
    private static final int ON_LIGHT = 0xFF0A0908;

    private static RemotePrompt sShown;

    private Context app;
    private RemoteArt art;
    private TextView status;
    private ImageView statusMark;
    private View statusDot;
    private TextView button;
    private long openedAt;
    private boolean connected;
    private String closeHow;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isShowing()) return;
            RemoteAutoPair.requestPromptState();
            Ui.main().postDelayed(this, POLL_MS);
        }
    };
    private final Runnable closeConnected = () -> close(CLOSED_CONNECTED);

    // =================================================================== entry points

    /**
     * Main thread (RemoteAutoPair.lostCheck). bondedCase = a remote is still bonded (the 60 s rule):
     * not after an HDMI-CEC wake. Returns SHOWN, LATER (blocked for now: dream, another overlay) or SKIP
     * (not this wake).
     */
    static int maybeShow(Context ctx, String reason, boolean bondedCase) {
        Context app = ctx.getApplicationContext();
        try {
            if (sShown != null && sShown.isShowing()) return SKIP;
            if (!KeyReceiver.isSetupComplete(app)) return SKIP;           // the setup has its own step
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (pm != null && !pm.isInteractive()) return SKIP;
            if (StandbyController.isActive() || StandbyController.shutdownInFlight()) return SKIP;
            if (bondedCase && org.z9x.projector.cec.CecPolicy.cecWakeActive()) {
                Log.i(TAG, "remote lost (" + reason + "): woken by an HDMI device, no prompt this wake");
                return SKIP;
            }
            DreamManager dm = app.getSystemService(DreamManager.class);
            if (dm != null && dm.isDreaming()) return LATER;
            if (OverlayHost.get(app).current() != null || AkOverlay.isActive() || WakeCurtain.isShowing()
                    || org.z9x.projector.eye.EyeGuard.isActive()) {
                return LATER;
            }
            RemotePrompt p = new RemotePrompt();
            p.app = app;
            sShown = p;
            RemoteAutoPair.onPromptShown();
            if (!OverlayHost.get(app).show(p)) {
                sShown = null;
                RemoteAutoPair.onPromptClosed(CLOSED_OFF);
                return LATER;
            }
            return SHOWN;
        } catch (Throwable t) {
            Log.w(TAG, "remote prompt: " + t);
            return SKIP;
        }
    }

    /** Main thread: the latest remote state (RemoteAutoPair.postPromptState). */
    static void apply(RemoteAutoPair.State st) {
        RemotePrompt p = sShown;
        if (p != null && p.isShowing() && st != null) p.show(st);
    }

    // =================================================================== Panel

    @Override
    protected View onCreateView(Context ctx) {
        openedAt = SystemClock.uptimeMillis();
        FrameLayout root = new FrameLayout(ctx);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {SKY_TOP, SKY_MID, GROUND}));

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        art = new RemoteArt(ctx);
        row.addView(art, new LinearLayout.LayoutParams(px(ctx, 250), px(ctx, 840)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(px(ctx, 820), ViewGroup.LayoutParams.WRAP_CONTENT);
        lc.setMarginStart(px(ctx, 140));
        row.addView(col, lc);

        TextView title = text(ctx, 68, TEXT, Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        title.setText(R.string.remote_lost_title);
        title.setMaxLines(2);
        col.addView(title);

        TextView how = text(ctx, 38, TEXT_2, Typeface.create("sans-serif", Typeface.NORMAL));
        how.setText(R.string.remote_lost_text);
        how.setLineSpacing(px(ctx, 10), 1f);
        col.addView(how, top(ctx, 36));

        LinearLayout st = new LinearLayout(ctx);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout mark = new FrameLayout(ctx);
        statusDot = new View(ctx);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ACCENT);
        statusDot.setBackground(dot);
        mark.addView(statusDot, new FrameLayout.LayoutParams(px(ctx, 18), px(ctx, 18), Gravity.CENTER));
        statusMark = new ImageView(ctx);
        statusMark.setImageResource(R.drawable.ic_remote_check);
        statusMark.setVisibility(View.GONE);
        mark.addView(statusMark, new FrameLayout.LayoutParams(px(ctx, 44), px(ctx, 44), Gravity.CENTER));
        st.addView(mark, new LinearLayout.LayoutParams(px(ctx, 44), px(ctx, 44)));
        status = text(ctx, 38, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setText(R.string.remote_pair_searching);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ls.setMarginStart(px(ctx, 20));
        st.addView(status, ls);
        col.addView(st, top(ctx, 64));

        TextView battery = text(ctx, 36, TEXT_3, Typeface.create("sans-serif", Typeface.NORMAL));
        battery.setText(R.string.remote_lost_battery);
        col.addView(battery, top(ctx, 24));

        button = text(ctx, 36, TEXT, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setText(R.string.remote_lost_dismiss);
        button.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {ON_LIGHT, TEXT}));
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(px(ctx, 260));
        button.setMinHeight(px(ctx, 84));
        button.setPadding(px(ctx, 52), 0, px(ctx, 52), 0);
        button.setBackground(pillBg(ctx));
        button.setFocusable(true);
        button.setClickable(true);
        button.setOnClickListener(v -> close(CLOSED_USER));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lb.topMargin = px(ctx, 72);
        col.addView(button, lb);
        return root;
    }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context ctx) {
        return OverlayHost.fullscreenParams(true);
    }

    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected void onShown(View root) {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(260).setInterpolator(Theme.panelInterpolator()).withLayer().start();
        if (button != null) button.requestFocus();
        if (statusDot != null) pulseDot(true);
        Ui.main().removeCallbacks(poll);
        Ui.main().post(poll);
        Log.i(TAG, "remote-lost prompt shown");
    }

    @Override
    protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(200).withLayer().withEndAction(end).start();
    }

    @Override
    protected boolean onBack() {
        if (closeHow == null) closeHow = CLOSED_USER;
        return false;                                   // OverlayHost closes the panel
    }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (fromRemote(ev)) {
            // the remote works: show it as connected and close; its keys never press "Not now"
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && !connected) {
                Log.i(TAG, "key from the XGIMI remote on the prompt: connected");
                RemoteAutoPair.State st = new RemoteAutoPair.State();
                st.connected = true;
                show(st);
            }
            return true;
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        return ok && SystemClock.uptimeMillis() - openedAt < OK_GUARD_MS;
    }

    @Override
    protected void onDismissed() {
        Ui.main().removeCallbacks(poll);
        Ui.main().removeCallbacks(closeConnected);
        if (statusDot != null) statusDot.animate().cancel();
        if (sShown == this) sShown = null;
        String how = closeHow;
        if (how == null) {
            // closed by OverlayHost: screen off / standby, or the user (HOME, another window, auto-hide)
            PowerManager pm = app == null ? null : app.getSystemService(PowerManager.class);
            boolean awake = pm == null || pm.isInteractive();
            how = !awake || StandbyController.isActive() ? CLOSED_OFF : connected ? CLOSED_CONNECTED : CLOSED_USER;
        }
        Log.i(TAG, "remote-lost prompt closed: " + how);
        RemoteAutoPair.onPromptClosed(how);
    }

    // =================================================================== state

    private void show(RemoteAutoPair.State st) {
        if (status == null || connected) return;
        if (st.connected) {
            connected = true;
            status.setText(R.string.remote_connected);
            statusDot.animate().cancel();
            statusDot.setVisibility(View.GONE);
            statusMark.setVisibility(View.VISIBLE);
            art.setConnected(true);
            Ui.main().removeCallbacks(closeConnected);
            Ui.main().postDelayed(closeConnected, CONNECTED_HOLD_MS);
            return;
        }
        if (st.pairing) {
            String name = !TextUtils.isEmpty(st.found) ? st.found : st.name;
            if (!TextUtils.isEmpty(name)) {
                status.setText(app.getString(R.string.remote_lost_found, name));
                return;
            }
        }
        // still paired, only not connected (the 60 s rule): any key wakes it, as on the setup's step;
        // Back + Home (the text above) is for a remote that lost its pairing
        status.setText(st.bonded > 0 && !st.pairing ? R.string.remote_lost_wake : R.string.remote_pair_searching);
    }

    private void close(String how) {
        if (!isShowing()) return;
        if (closeHow == null) closeHow = connected ? CLOSED_CONNECTED : how;
        dismiss();
    }

    /** The status dot breathes with the illustration's glow (alpha only, stopped when closed). */
    private void pulseDot(boolean dim) {
        if (statusDot == null || connected || !isShowing()) return;
        statusDot.animate().alpha(dim ? 0.25f : 1f).setDuration(1_100)
                .withEndAction(() -> pulseDot(!dim)).start();
    }

    /** A key from an XGIMI BLE remote (not the keypad, the IR receiver or a keyboard). */
    private static boolean fromRemote(KeyEvent ev) {
        InputDevice d = ev.getDevice();
        return d != null && d.isExternal() && RemoteAutoPair.isRemoteVendor(d.getVendorId());
    }

    // =================================================================== views

    private static int px(Context c, float design) { return Theme.px(c, design); }

    private static TextView text(Context c, float size, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    private static LinearLayout.LayoutParams top(Context c, float margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(c, margin);
        return lp;
    }

    /** Light fill when focused (dark text), a quiet outline otherwise. */
    private static Drawable pillBg(Context c) {
        float r = Theme.pxf(c, 42);
        GradientDrawable on = new GradientDrawable();
        on.setColor(TEXT);
        on.setCornerRadius(r);
        GradientDrawable off = new GradientDrawable();
        off.setColor(0x00000000);
        off.setCornerRadius(r);
        off.setStroke(Theme.px(c, 3), 0x66F4EFE6);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, on);
        s.addState(new int[] {}, off);
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        return s;
    }
}
