package org.lumen.sensors.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import org.lumen.sensors.Hub;
import org.lumen.sensors.HudService;
import org.lumen.sensors.Prefs;
import org.lumen.sensors.R;
import org.lumen.sensors.core.Group;

import java.util.ArrayList;

/**
 * The settings list, ONE Canvas-drawn View in the look of Projector's quick panel: a dark rounded
 * panel (#EB1C1C1C, radius 36) on the left, rows 92 design px high, title 30 / value 26, the focused
 * row a solid white rounded rect (radius 24) with dark text and no scale, on/off switches in the
 * Lumen accent #3B78E7. The rest of the screen stays clear (translucent, dimmed activity), so the live
 * HUD is visible while the user adjusts; a HUD on the left moves right of the panel meanwhile.
 *
 * Rows: Show overlay, Mode, Position, Size, Opacity, Refresh, Start at boot, then "Groups" with one
 * switch per group that has a value on this device (Hub.availableGroups, updated live while the probe
 * runs). D-pad UP / DOWN move the focus (no wrap); LEFT / RIGHT step a choice (no wrap) or set a switch
 * off / on; OK steps a choice forward (with wrap) or flips a switch. Every change is saved at once and
 * the HUD redraws (Prefs listeners). BACK is left to the activity (it closes; the HUD stays as set).
 * Every launch from the remote lands on the first row ({@link #focusFirst}), so the app key and OK
 * always switch the overlay. A short hint at the bottom tells how to reach this screen from the remote.
 * Titles (ellipsized) and choice widths are measured when the items or the size change, not per frame.
 */
public final class SettingsView extends View implements Hub.Listener {
    private static final int T_TOGGLE = 0, T_CHOICE = 1, T_HEADER = 2;
    private static final int K_SHOW = 1, K_MODE = 2, K_POSITION = 3, K_SIZE = 4, K_OPACITY = 5, K_REFRESH = 6,
            K_BOOT = 7, K_GROUPS = 8, K_GROUP0 = 100;

    private static final int[] GROUP_LABELS = {R.string.grp_temps, R.string.grp_cooling, R.string.grp_light,
            R.string.grp_cpu, R.string.grp_memory, R.string.grp_network, R.string.grp_picture, R.string.grp_motion,
            R.string.grp_system};

    private static final class Item {
        final int type, key;
        final String title;
        final String[] options;
        float y, h;
        /** Measured in measureItems(): the title as drawn and the width of the choice area. */
        CharSequence fitTitle;
        float choiceW;

        Item(int type, int key, String title, String[] options) {
            this.type = type;
            this.key = key;
            this.title = title;
            this.options = options;
        }
    }

    private final Hub hub;
    private final Prefs prefs;
    private final ArrayList<Item> items = new ArrayList<>();
    private int focus;
    private float scroll;
    private int shownGroups = -1;

    private final float s;
    private final Paint panel = Look.fill(Look.PANEL_BG);
    private final Paint stroke;
    private final Paint rowBg = Look.fill(Look.FOCUS_BG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = Look.fill(0xFFFFFFFF);
    private final Paint chevron;
    private final TextPaint titlePaint, rowTitle, rowValue, headerPaint, hintPaint;
    private final RectF rect = new RectF();
    private final RectF panelRect = new RectF();
    private final Path chev = new Path();
    private StaticLayout hint;
    private final String title;
    private float listTop, listBottom;

    public SettingsView(Context c, Hub hub) {
        super(c);
        this.hub = hub;
        this.prefs = hub.prefs;
        s = Look.scale(c);
        stroke = Look.stroke(Look.PANEL_STROKE, Look.STROKE * s);
        chevron = Look.stroke(Look.LABEL, 3 * s);
        chevron.setStrokeCap(Paint.Cap.ROUND);
        chevron.setStrokeJoin(Paint.Join.ROUND);
        titlePaint = Look.text(Look.PANEL_TITLE_SIZE * s, Look.TEXT, Look.BOLD, false);
        rowTitle = Look.text(Look.ROW_TITLE_SIZE * s, Look.TEXT, null, false);
        rowValue = Look.text(Look.ROW_VALUE_SIZE * s, Look.LABEL, null, true);
        headerPaint = Look.text(Look.HEADER_SIZE * s, Look.FAINT, Look.BOLD, false);
        hintPaint = Look.text(Look.HINT_SIZE * s, Look.FAINT, null, false);
        title = c.getString(R.string.app_name);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setWillNotDraw(false);
        buildItems();
    }

    // ================================================================== items
    private void buildItems() {
        Resources r = getResources();
        int focusKey = items.isEmpty() ? K_SHOW : items.get(focus).key;
        items.clear();
        items.add(new Item(T_TOGGLE, K_SHOW, r.getString(R.string.set_show), null));
        items.add(new Item(T_CHOICE, K_MODE, r.getString(R.string.set_mode), new String[] {
                r.getString(R.string.mode_compact), r.getString(R.string.mode_detailed), r.getString(R.string.mode_graphs)}));
        items.add(new Item(T_CHOICE, K_POSITION, r.getString(R.string.set_position), new String[] {
                r.getString(R.string.pos_tl), r.getString(R.string.pos_tr), r.getString(R.string.pos_bl),
                r.getString(R.string.pos_br)}));
        items.add(new Item(T_CHOICE, K_SIZE, r.getString(R.string.set_size), new String[] {
                r.getString(R.string.size_small), r.getString(R.string.size_medium), r.getString(R.string.size_large)}));
        String[] op = new String[Prefs.OPACITY_PCT.length];
        for (int i = 0; i < op.length; i++) op[i] = r.getString(R.string.opacity_fmt, Prefs.OPACITY_PCT[i]);
        items.add(new Item(T_CHOICE, K_OPACITY, r.getString(R.string.set_opacity), op));
        String[] rf = new String[Prefs.REFRESH_S.length];
        for (int i = 0; i < rf.length; i++) rf[i] = r.getString(R.string.refresh_fmt, Prefs.REFRESH_S[i]);
        items.add(new Item(T_CHOICE, K_REFRESH, r.getString(R.string.set_refresh), rf));
        items.add(new Item(T_TOGGLE, K_BOOT, r.getString(R.string.set_boot), null));
        int avail = hub.availableGroups();
        shownGroups = avail;
        if (avail != 0) {
            items.add(new Item(T_HEADER, K_GROUPS, r.getString(R.string.set_groups), null));
            for (int g = 0; g < Group.COUNT; g++) {
                if (Group.in(avail, g)) items.add(new Item(T_TOGGLE, K_GROUP0 + g, r.getString(GROUP_LABELS[g]), null));
            }
        }
        focus = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key == focusKey) focus = i;
        layoutItems();
    }

    private void layoutItems() {
        float y = 0;
        for (Item it : items) {
            it.y = y;
            it.h = (it.type == T_HEADER ? Look.HEADER_HEIGHT : Look.ROW_HEIGHT) * s;
            y += it.h + (it.type == T_HEADER ? 0 : Look.ROW_GAP * s);
        }
        measureItems();
        ensureVisible();
    }

    /** Choice widths and the titles as they fit (they change only with the items or the panel size). */
    private void measureItems() {
        float padH = Look.PANEL_PAD_H * s, rowPad = Look.ROW_PAD_H * s;
        float left = panelRect.left + padH, right = panelRect.right - padH;
        for (Item it : items) {
            it.choiceW = 0;
            if (it.options != null) {
                float max = 0;
                for (String o : it.options) max = Math.max(max, rowValue.measureText(o));
                it.choiceW = max + 2 * (chevronW() + 16 * s);
            }
            if (it.type == T_HEADER || panelRect.width() <= 0) {
                it.fitTitle = it.title;
                continue;
            }
            float rightW = it.type == T_TOGGLE ? 64 * s : it.choiceW;
            float titleRoom = (right - rowPad) - rightW - rowPad / 2f - (left + rowPad);
            it.fitTitle = TextUtils.ellipsize(it.title, rowTitle, Math.max(0, titleRoom), TextUtils.TruncateAt.END);
        }
    }

    /** Focus on "Show overlay", list at the top (each launch from the remote). */
    public void focusFirst() {
        focus = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key == K_SHOW) {
                focus = i;
                break;
            }
        }
        scroll = 0;
        ensureVisible();
        invalidate();
    }

    @Override
    public void onSamples() {
        int avail = hub.availableGroups();
        if (avail != shownGroups) {
            buildItems();
            invalidate();
        }
    }

    // ================================================================== values
    private boolean isOn(Item it) {
        if (it.key == K_SHOW) return prefs.enabled();
        if (it.key == K_BOOT) return prefs.startAtBoot();
        return Group.in(prefs.groups(), it.key - K_GROUP0);
    }

    private void setOn(Item it, boolean on) {
        if (isOn(it) == on) return;
        if (it.key == K_SHOW) HudService.setEnabled(getContext(), on);
        else if (it.key == K_BOOT) prefs.setBoolean(Prefs.K_BOOT, on);
        else prefs.setGroup(it.key - K_GROUP0, on);
        playSoundEffect(SoundEffectConstants.CLICK);
        invalidate();
    }

    private int index(Item it) {
        switch (it.key) {
            case K_MODE: return prefs.mode();
            case K_POSITION: return prefs.position();
            case K_SIZE: return prefs.sizeIndex();
            case K_OPACITY: return prefs.opacityIndex();
            default: return prefs.refreshIndex();
        }
    }

    private void setIndex(Item it, int i) {
        if (i < 0 || i >= it.options.length || i == index(it)) return;
        String key;
        switch (it.key) {
            case K_MODE: key = Prefs.K_MODE; break;
            case K_POSITION: key = Prefs.K_POSITION; break;
            case K_SIZE: key = Prefs.K_SIZE; break;
            case K_OPACITY: key = Prefs.K_OPACITY; break;
            default: key = Prefs.K_REFRESH; break;
        }
        prefs.setInt(key, i);
        playSoundEffect(SoundEffectConstants.CLICK);
        invalidate();
    }

    // ================================================================== keys
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent ev) {
        if (items.isEmpty()) return super.onKeyDown(keyCode, ev);
        Item it = items.get(focus);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveFocus(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocus(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int dir = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (it.type == T_CHOICE) setIndex(it, index(it) + dir);
                else if (it.type == T_TOGGLE && ev.getRepeatCount() == 0) setOn(it, dir > 0);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (ev.getRepeatCount() == 0) {
                    if (it.type == T_CHOICE) setIndex(it, (index(it) + 1) % it.options.length);
                    else if (it.type == T_TOGGLE) setOn(it, !isOn(it));
                }
                return true;
            default:
                return super.onKeyDown(keyCode, ev);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent ev) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;                                  // never a second (click) action
            default:
                return super.onKeyUp(keyCode, ev);
        }
    }

    private void moveFocus(int dir) {
        int i = focus + dir;
        while (i >= 0 && i < items.size() && items.get(i).type == T_HEADER) i += dir;
        if (i < 0 || i >= items.size()) return;              // no wrap
        focus = i;
        ensureVisible();
        invalidate();
    }

    /** Scrolls so the focused row (and a header right above it) is inside the list area. */
    private void ensureVisible() {
        float listH = listBottom - listTop;
        if (listH <= 0 || items.isEmpty()) return;
        Item it = items.get(focus);
        float top = it.y;
        if (focus > 0 && items.get(focus - 1).type == T_HEADER) top = items.get(focus - 1).y;
        float bottom = it.y + it.h;
        if (top < scroll) scroll = top;
        if (bottom > scroll + listH) scroll = bottom - listH;
        Item last = items.get(items.size() - 1);
        float total = last.y + last.h;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - listH)));
    }

    // ================================================================== layout / draw
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float safe = Look.SAFE * s;
        panelRect.set(safe, safe, safe + Look.DESIGN_WIDTH * Look.PANEL_WIDTH_FRACTION * s, h - safe);
        float inner = panelRect.width() - 2 * (Look.PANEL_PAD_H + Look.ROW_PAD_H) * s;
        String text = getResources().getString(R.string.set_hint);
        hint = StaticLayout.Builder.obtain(text, 0, text.length(), hintPaint, Math.max(1, (int) inner))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.1f).build();
        float titleBottom = panelRect.top + Look.PANEL_PAD_TOP * s - titlePaint.ascent() + titlePaint.descent();
        listTop = titleBottom + 16 * s;
        listBottom = panelRect.bottom - Look.PANEL_PAD_H * s - hint.getHeight() - 20 * s;
        measureItems();
        ensureVisible();
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = Look.PANEL_RADIUS * s;
        c.drawRoundRect(panelRect, r, r, panel);
        c.drawRoundRect(panelRect, r, r, stroke);
        float padH = Look.PANEL_PAD_H * s, rowPad = Look.ROW_PAD_H * s;
        float left = panelRect.left + padH, right = panelRect.right - padH;
        c.drawText(title, left + rowPad,
                panelRect.top + Look.PANEL_PAD_TOP * s - titlePaint.ascent(), titlePaint);

        c.save();
        c.clipRect(panelRect.left, listTop, panelRect.right, listBottom);
        c.translate(0, listTop - scroll);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.y + it.h < scroll || it.y > scroll + (listBottom - listTop)) continue;
            if (it.type == T_HEADER) {
                c.drawText(it.title, left + rowPad, it.y + it.h - 14 * s, headerPaint);
                continue;
            }
            boolean focused = i == focus && isFocused();
            if (focused) {
                rect.set(left, it.y, right, it.y + it.h);
                c.drawRoundRect(rect, Look.ROW_RADIUS * s, Look.ROW_RADIUS * s, rowBg);
            }
            float cy = it.y + it.h / 2f;
            rowTitle.setColor(focused ? Look.TEXT_FOCUSED : Look.TEXT);
            rowValue.setColor(focused ? Look.TEXT_FOCUSED_DIM : Look.LABEL);
            float valueRight = right - rowPad;
            CharSequence t = it.fitTitle != null ? it.fitTitle : it.title;
            c.drawText(t, 0, t.length(), left + rowPad, cy - (rowTitle.ascent() + rowTitle.descent()) / 2f, rowTitle);
            if (it.type == T_TOGGLE) drawSwitch(c, valueRight, cy, isOn(it), focused);
            else drawChoice(c, it, valueRight, cy, focused);
        }
        c.restore();

        if (hint != null) {
            c.save();
            c.translate(left + rowPad, panelRect.bottom - padH - hint.getHeight());
            hint.draw(c);
            c.restore();
        }
    }

    private float chevronW() {
        return 18 * s * 0.55f;
    }

    private void drawSwitch(Canvas c, float right, float cy, boolean on, boolean focused) {
        float tw = 64 * s, th = 36 * s;
        rect.set(right - tw, cy - th / 2, right, cy + th / 2);
        int col = on ? Look.ACCENT : (focused ? Look.SWITCH_OFF_FOCUSED : Look.SWITCH_OFF);
        track.setColor(col);
        track.setAlpha(Color.alpha(col));
        c.drawRoundRect(rect, th / 2, th / 2, track);
        float kr = th / 2 - 4 * s;
        c.drawCircle(on ? right - th / 2 : right - tw + th / 2, cy, kr, knob);
    }

    private void drawChoice(Canvas c, Item it, float right, float cy, boolean focused) {
        int idx = index(it);
        float w = it.choiceW, cw = chevronW(), h = 18 * s;
        float leftEdge = right - w;
        int col = focused ? Look.TEXT_FOCUSED_DIM : Look.LABEL;
        int faint = (col & 0x00FFFFFF) | 0x33000000;
        chevron.setColor(idx <= 0 ? faint : col);
        chev.rewind();
        chev.moveTo(leftEdge + cw, cy - h / 2);
        chev.lineTo(leftEdge, cy);
        chev.lineTo(leftEdge + cw, cy + h / 2);
        c.drawPath(chev, chevron);
        chevron.setColor(idx >= it.options.length - 1 ? faint : col);
        chev.rewind();
        chev.moveTo(right - cw, cy - h / 2);
        chev.lineTo(right, cy);
        chev.lineTo(right - cw, cy + h / 2);
        c.drawPath(chev, chevron);
        String v = it.options[Math.max(0, Math.min(idx, it.options.length - 1))];
        float vw = rowValue.measureText(v);
        c.drawText(v, leftEdge + w / 2f - vw / 2f, cy - (rowValue.ascent() + rowValue.descent()) / 2f, rowValue);
    }
}
