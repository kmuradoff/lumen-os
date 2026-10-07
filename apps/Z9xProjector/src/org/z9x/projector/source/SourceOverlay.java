package org.z9x.projector.source;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
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

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.GmpfClient;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * MODULE "source" (owner: source+keys agent). The Source/input key overlay: a compact XGIMI-style
 * card at the right edge, drawn over the running video or app (an overlay window, so the app below
 * is never paused), listing
 * <ul>
 *   <li>HDMI 1 (ARC) and HDMI 2: the hardware inputs of org.z9x.tvinput's HdmiInputService
 *       (ids "org.z9x.tvinput/.HdmiInputService/HW1" / "HW2"), each with its TIF state
 *       (TvInputManager.getInputState, kept live with a TvInputCallback while shown):
 *       CONNECTED = "Connected" (green dot); CONNECTED_STANDBY = "No signal" (on this image the
 *       framework reports STANDBY when HDMI hotplug is low, TvInputHardwareManager.java:368-374,
 *       live: both ports state 1 with nothing plugged); DISCONNECTED = "Not connected";</li>
 *   <li>Home screen (CATEGORY_HOME).</li>
 * </ul>
 * D-pad UP/DOWN moves (wraps around), CENTER selects, BACK closes, the Source key toggles
 * (KeyReceiver). Auto-hide after 10 s without a key.
 *
 * <p>Selecting an HDMI input fires exactly what the Google TV launcher and org.z9x.tvinput's own
 * HdmiInputs.viewerIntent fire: ACTION_VIEW + TvContract.buildChannelUriForPassthroughInput(id)
 * ("content://android.media.tv/passthrough/&lt;inputId&gt;"), explicit to
 * org.z9x.tvinput/.PassthroughActivity (exported VIEW filter), NEW_TASK. Background-start is
 * allowed: our overlay window is visible and the app holds START_ACTIVITIES_FROM_BACKGROUND.
 *
 * <p>"On screen" check mark: IGmpf 696 getCurrentInputSource() read on the z9x-hal thread
 * (Hal.query, never on the main thread). UNVERIFIED whether 696 reports 1/2 while our TIF plays
 * HDMI (V61_ARCH §8.4), so the mark is shown only when 696 says HDMI n AND that input's TIF state
 * is CONNECTED; otherwise no row is marked. The mark is a hint only and never drives an action.
 *
 * <p>Threading: everything here runs on the main thread; TvInputManager.getTvInputList is one
 * cheap binder call, getInputState reads the client-side cache. No HAL call on the main thread.
 *
 * <p>Fixed entry point: {@link #toggle(Context)} - KeyReceiver on KEYCODE_TV_INPUT UP (not canceled,
 * setup complete), MAIN thread. The routing change of KEYCODE_TV_INPUT from org.z9x.tvinput to
 * org.z9x.projector is in V61_KEYS_PLAN.md (applied by this module in Z9xFrameworkKeysOverlay).
 */
public final class SourceOverlay extends Panel {
    private static final String TAG = "Z9xSource";

    /** Requirement: auto-hide after 10 s without a key. */
    static final long AUTO_HIDE_MS = 10_000;

    static final String TVINPUT_PKG = "org.z9x.tvinput";
    static final String TVINPUT_VIEWER = "org.z9x.tvinput.PassthroughActivity";
    /** org.z9x.tvinput HdmiInputs.EXTRA_REASON (logged by the viewer). */
    static final String EXTRA_REASON = "org.z9x.tvinput.extra.REASON";
    /** Input id = HdmiInputService component flattenToShortString() + "/HW" + deviceId (deviceId == port). */
    static final String INPUT_ID_PREFIX = "org.z9x.tvinput/.HdmiInputService/HW";
    /** The Z9X has two HDMI ports (dumpsys tv_input: HW1, HW2). */
    static final int[] PORTS = {1, 2};

    private static final float CARD_WIDTH = 600;         // design px
    private static final float CARD_TITLE_SIZE = 34;     // design px
    private static final float WINDOW_MARGIN = 48;       // design px (same edge margin as the notifier)

    private static SourceOverlay sPanel;
    /** Opened from the quick panel's Input tile: BACK returns to the tile grid. Main thread. */
    private static boolean sFromQuickPanel;

    /** Toggles the overlay (Source key). Main thread. */
    public static void toggle(Context ctx) {
        try {
            if (sPanel == null) sPanel = new SourceOverlay();
            sFromQuickPanel = false;
            OverlayHost.get(ctx).toggle(sPanel);
        } catch (Throwable t) {
            Log.e(TAG, "toggle", t);
        }
    }

    /**
     * The quick panel's Input tile: shows the overlay in place of the quick panel; BACK goes back to
     * the tile grid (which remembers the Input tile) instead of closing everything. Main thread.
     */
    public static void showFromQuickPanel(Context ctx) {
        try {
            if (sPanel == null) sPanel = new SourceOverlay();
            sFromQuickPanel = true;
            OverlayHost.get(ctx).show(sPanel);
        } catch (Throwable t) {
            Log.e(TAG, "show from quick panel", t);
        }
    }

    @Override
    protected boolean onBack() {
        if (!sFromQuickPanel || ctx == null) return false;
        sFromQuickPanel = false;
        final Context c = ctx;
        // posted: not from inside this window's own key dispatch; the quick panel replaces it
        Ui.main().post(() -> {
            if (isShowing()) org.z9x.projector.panel.QuickPanel.show(c, null);
        });
        return true;
    }

    // ------------------------------------------------------------------ instance (one per process)
    private Context ctx;
    private LinearLayout list;
    private final List<Entry> entries = new ArrayList<>();
    private TvInputManager tim;
    private TvInputManager.TvInputCallback callback;
    /** IGmpf 696 result for the current show (null = unknown). */
    private Integer halSource;
    /** True once the user pressed a key in this show (do not move focus under the user). */
    private boolean userMoved;
    /** Incremented per show, so a late HAL result of an earlier show is ignored. */
    private int generation;

    private static final class Entry {
        final int port;          // 1..2, or 0 = Home
        final String inputId;    // null when the input is not registered (or Home)
        final SourceRow row;

        Entry(int port, String inputId, SourceRow row) {
            this.port = port;
            this.inputId = inputId;
            this.row = row;
        }
    }

    private SourceOverlay() {}

    @Override
    protected long autoHideMs() { return AUTO_HIDE_MS; }

    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        int m = Theme.px(c, WINDOW_MARGIN);
        int w = Theme.px(c, CARD_WIDTH) + 2 * m;
        return OverlayHost.params(true, w, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
    }

    @Override
    protected View onCreateView(Context c) {
        ctx = c;
        generation++;
        userMoved = false;
        halSource = null;
        tim = tvInputManager(c);

        int m = Theme.px(c, WINDOW_MARGIN);
        int padH = Theme.px(c, Theme.PANEL_PAD_H);
        FrameLayout outer = new FrameLayout(c);
        outer.setClipToPadding(false);
        outer.setPadding(m, m, m, m);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PANEL_BG);
        bg.setCornerRadius(Theme.pxf(c, Theme.PANEL_RADIUS));
        bg.setStroke(Theme.px(c, Theme.STROKE), Theme.PANEL_STROKE);
        card.setBackground(bg);
        card.setPadding(padH, Theme.px(c, 32), padH, padH);
        outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(c);
        title.setText(R.string.source_title);
        title.setTextColor(Theme.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, CARD_TITLE_SIZE));
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPaddingRelative(Theme.px(c, Theme.ROW_PAD_H), 0, 0, Theme.px(c, 18));
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        card.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        buildRows(-1);
        registerCallback();
        queryCurrentSource(generation);
        return outer;
    }

    @Override
    protected void onShown(View root) {
        super.onShown(root);
        if (list == null) return;
        float dx = Theme.pxf(root.getContext(), 40);
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            v.setTranslationX(dx);
            v.animate().translationX(0f).setStartDelay(i * Theme.ROW_STAGGER_MS)
                    .setDuration(Theme.PANEL_ENTER_MS).setInterpolator(Theme.panelInterpolator()).start();
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            userMoved = true;
            // Wrap around at the ends (short list, like a stock source picker).
            if (list == null || list.getChildCount() == 0) return false;
            View f = list.getFocusedChild();
            int n = list.getChildCount();
            if (f == null) return false;
            int idx = list.indexOfChild(f);
            boolean down = code == KeyEvent.KEYCODE_DPAD_DOWN;
            if ((down && idx == lastFocusable()) || (!down && idx == firstFocusable())) {
                int target = down ? firstFocusable() : lastFocusable();
                if (target >= 0 && target < n && target != idx) {
                    list.getChildAt(target).requestFocus();
                    return true;
                }
            }
            return false;
        }
        if (code != KeyEvent.KEYCODE_BACK) userMoved = true;
        return false;
    }

    @Override
    protected void onDismissed() {
        unregisterCallback();
        generation++;
        if (list != null) list.removeAllViews();
        list = null;
        entries.clear();
    }

    // ------------------------------------------------------------------ rows
    /** (Re)builds the rows. focusIndex -1 = keep current or first. Main thread. */
    private void buildRows(int focusIndex) {
        if (list == null) return;
        Context c = ctx;
        int keep = focusIndex;
        if (keep < 0) {
            View f = list.getFocusedChild();
            if (f != null) keep = list.indexOfChild(f);
        }
        list.removeAllViews();
        entries.clear();

        List<TvInputInfo> inputs = ourInputs();
        for (int port : PORTS) {
            TvInputInfo info = null;
            for (TvInputInfo i : inputs) {
                if (portOf(i.getId()) == port) { info = i; break; }
            }
            final String id = info == null ? null : info.getId();
            final int p = port;
            SourceRow row = new SourceRow(c, labelOf(c, info, port), () -> onPick(p, id));
            entries.add(new Entry(port, id, row));
        }
        SourceRow home = new SourceRow(c, c.getString(R.string.source_home), this::goHome);
        entries.add(new Entry(0, null, home));

        int gap = Theme.px(c, Theme.ROW_GAP);
        for (Entry e : entries) {
            updateState(e);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            list.addView(e.row, lp);
        }
        applyCurrentMark();
        int target = keep >= 0 && keep < list.getChildCount() ? keep : -1;
        if (target < 0 || !list.getChildAt(target).isFocusable()) target = firstFocusable();
        focusLater(target);
    }

    private void updateState(Entry e) {
        Context c = ctx;
        if (e.port == 0) {                                   // Home
            e.row.setState(null, null);
            return;
        }
        if (e.inputId == null) {
            e.row.setState(c.getString(R.string.source_state_unavailable), null);
            e.row.setRowEnabled(false);
            return;
        }
        e.row.setRowEnabled(true);
        int st = stateOf(e.inputId);
        switch (st) {
            case TvInputManager.INPUT_STATE_CONNECTED:
                e.row.setState(c.getString(R.string.source_state_connected), Boolean.TRUE);
                break;
            case TvInputManager.INPUT_STATE_CONNECTED_STANDBY:
                e.row.setState(c.getString(R.string.source_state_no_signal), Boolean.FALSE);
                break;
            default:
                e.row.setState(c.getString(R.string.source_state_disconnected), Boolean.FALSE);
                break;
        }
    }

    /** Marks the HDMI row that 696 reports, only if that input is CONNECTED (see class doc). */
    private void applyCurrentMark() {
        Integer src = halSource;
        for (Entry e : entries) {
            boolean cur = src != null && e.port > 0 && e.port == src && e.inputId != null
                    && stateOf(e.inputId) == TvInputManager.INPUT_STATE_CONNECTED;
            e.row.setCurrent(cur);
        }
    }

    private int firstFocusable() {
        if (list == null) return -1;
        for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).isFocusable()) return i;
        return -1;
    }

    private int lastFocusable() {
        if (list == null) return -1;
        for (int i = list.getChildCount() - 1; i >= 0; i--) if (list.getChildAt(i).isFocusable()) return i;
        return -1;
    }

    private void focusLater(int index) {
        if (list == null || index < 0 || index >= list.getChildCount()) return;
        final View v = list.getChildAt(index);
        v.post(() -> {
            if (v.isAttachedToWindow() && isShowing()) v.requestFocus();
        });
    }

    // ------------------------------------------------------------------ actions
    private void onPick(int port, String inputId) {
        Context c = ctx;
        if (inputId == null) return;                         // disabled row; defensive
        Log.i(TAG, "pick HDMI " + port + " -> " + inputId);
        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId));
        i.setComponent(new ComponentName(TVINPUT_PKG, TVINPUT_VIEWER));
        i.putExtra(EXTRA_REASON, "source_overlay");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        dismiss();
        try {
            Ui.wakeFromDream(c);
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "tvinput viewer missing: " + e);
            failed(c, port);
        } catch (Throwable t) {
            Log.e(TAG, "open HDMI " + port, t);
            failed(c, port);
        }
    }

    private void goHome() {
        Context c = ctx;
        Log.i(TAG, "pick Home");
        dismiss();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Ui.wakeFromDream(c);
            c.startActivity(home);
        } catch (Throwable t) {
            Log.e(TAG, "go home", t);
        }
    }

    private static void failed(Context c, int port) {
        try {
            org.z9x.projector.ui.Notify.show(c, c.getString(R.string.source_open_failed,
                    c.getString(R.string.source_hdmi, port)));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ TIF
    private static TvInputManager tvInputManager(Context c) {
        try {
            return c.getSystemService(TvInputManager.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Our HDMI port inputs: org.z9x.tvinput passthrough HDMI inputs without a parent (no CEC children); public SDK calls only. */
    private List<TvInputInfo> ourInputs() {
        List<TvInputInfo> out = new ArrayList<>();
        if (tim == null) return out;
        try {
            List<TvInputInfo> all = tim.getTvInputList();
            if (all == null) return out;
            for (TvInputInfo i : all) {
                if (i == null || i.getId() == null) continue;
                if (!i.getId().startsWith(INPUT_ID_PREFIX)) continue;
                if (i.getType() != TvInputInfo.TYPE_HDMI || !i.isPassthroughInput() || i.getParentId() != null) continue;
                out.add(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getTvInputList: " + t);
        }
        return out;
    }

    /** Port number from ".../HW&lt;n&gt;" (deviceId == HDMI port on the Z9X), or -1. */
    static int portOf(String inputId) {
        if (inputId == null || !inputId.startsWith(INPUT_ID_PREFIX)) return -1;
        try {
            return Integer.parseInt(inputId.substring(INPUT_ID_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int stateOf(String inputId) {
        if (tim == null || inputId == null) return TvInputManager.INPUT_STATE_DISCONNECTED;
        try {
            return tim.getInputState(inputId);
        } catch (Throwable t) {
            return TvInputManager.INPUT_STATE_DISCONNECTED;
        }
    }

    /**
     * The user's custom label (TvSettings "rename input") first, then the label org.z9x.tvinput
     * registered ("HDMI 1 (ARC)" from the CEC port info), then ours (port 1 is the ARC port).
     */
    private static CharSequence labelOf(Context c, TvInputInfo info, int port) {
        if (info != null) {
            try {
                CharSequence l = info.loadCustomLabel(c);
                if (TextUtils.isEmpty(l)) l = info.loadLabel(c);
                if (!TextUtils.isEmpty(l)) return l;
            } catch (Throwable ignored) {
            }
        }
        return port == 1 ? c.getString(R.string.source_hdmi_arc, port) : c.getString(R.string.source_hdmi, port);
    }

    private void registerCallback() {
        if (tim == null || callback != null) return;
        callback = new TvInputManager.TvInputCallback() {
            @Override public void onInputStateChanged(String inputId, int state) {
                if (!isShowing() || list == null) return;
                for (Entry e : entries) {
                    if (inputId != null && inputId.equals(e.inputId)) updateState(e);
                }
                applyCurrentMark();
            }
            @Override public void onInputAdded(String inputId) { rebuildIfShowing(); }
            @Override public void onInputRemoved(String inputId) { rebuildIfShowing(); }
            @Override public void onInputUpdated(String inputId) { rebuildIfShowing(); }
        };
        try {
            tim.registerCallback(callback, Ui.main());
        } catch (Throwable t) {
            Log.w(TAG, "registerCallback: " + t);
            callback = null;
        }
    }

    private void rebuildIfShowing() {
        try {
            if (isShowing() && list != null) buildRows(-1);
        } catch (Throwable t) {
            Log.w(TAG, "rebuild: " + t);
        }
    }

    private void unregisterCallback() {
        if (tim != null && callback != null) {
            try {
                tim.unregisterCallback(callback);
            } catch (Throwable t) {
                Log.w(TAG, "unregisterCallback: " + t);
            }
        }
        callback = null;
    }

    // ------------------------------------------------------------------ HAL hint (off main thread)
    private void queryCurrentSource(final int gen) {
        Hal.query((g, g2) -> g.getCurrentInputSource(), (Integer v) -> {
            if (gen != generation || !isShowing() || list == null) return;
            if (v == null || (v != GmpfClient.SOURCE_HDMI1 && v != GmpfClient.SOURCE_HDMI2)) return;
            halSource = v;
            applyCurrentMark();
            if (userMoved) return;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).row.isCurrent()) { focusLater(i); break; }
            }
        });
    }
}
