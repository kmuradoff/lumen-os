package org.lumen.projector.settings;

import android.app.Activity;
import android.content.Context;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Lumen OS 1.0.9: the two-pane frame of the Projector settings (mock design/projector_v2): brand and the section
 * rail on the left, the content of the section in focus on the right.
 * Keys: UP / DOWN in the rail switch the section at once; RIGHT or OK enters it; LEFT at the left edge of the
 * content (or BACK) goes back to the rail; BACK in the rail closes the screen. Focus never jumps between the
 * panes on UP / DOWN.
 */
public final class Shell {
    private final Activity a;
    private final FrameLayout root;
    private final LinearLayout rail;
    private final LinearLayout nav;
    private final ScrollView scroll;
    private final FrameLayout pages;
    private final List<Section> sections = new ArrayList<>();
    private Section current;
    private boolean backEaten;

    private final class Section {
        final String id;
        final LinearLayout item;
        final View bar;
        final ImageView icon;
        final TextView label;
        final LinearLayout page;

        Section(String id, LinearLayout item, View bar, ImageView icon, TextView label, LinearLayout page) {
            this.id = id; this.item = item; this.bar = bar; this.icon = icon; this.label = label; this.page = page;
        }
    }

    public Shell(Activity a, CharSequence brand, CharSequence subline) {
        this.a = a;
        Context c = a;
        root = new FrameLayout(c);
        Kit.background(root);
        LinearLayout panes = new LinearLayout(c);
        panes.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(panes, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rail = new LinearLayout(c);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setPadding(Kit.dp(c, 32), Kit.dp(c, 36), Kit.dp(c, 8), Kit.dp(c, 24));
        panes.addView(rail, new LinearLayout.LayoutParams(Kit.dp(c, 264), ViewGroup.LayoutParams.MATCH_PARENT));
        rail.addView(Kit.text(c, brand, 30, Kit.T1, Kit.display(c)));
        TextView sub = Kit.text(c, subline, 10.5f, Kit.T3, Kit.regular(c));
        sub.setLetterSpacing(0.02f);
        sub.setPadding(0, Kit.dp(c, 8), 0, Kit.dp(c, 28));
        rail.addView(sub);
        nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.VERTICAL);
        rail.addView(nav);

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(Kit.dp(c, 20), Kit.dp(c, 36), Kit.dp(c, 48), Kit.dp(c, 56));
        pages = new FrameLayout(c);
        pages.setClipChildren(false);
        scroll.addView(pages, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panes.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    /** Adds a section; returns its page (vertical) to fill. */
    public LinearLayout section(String id, int iconRes, CharSequence label) {
        Context c = a;
        LinearLayout item = new LinearLayout(c);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setPadding(Kit.dp(c, 12), 0, Kit.dp(c, 12), 0);
        View bar = new View(c);
        bar.setBackground(Kit.round(Kit.ACC, Kit.dp(c, 1.5f)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Kit.dp(c, 3), Kit.dp(c, 14));
        blp.rightMargin = Kit.dp(c, 9);
        item.addView(bar, blp);
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Kit.T2);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Kit.dp(c, 14), Kit.dp(c, 14));
        ilp.rightMargin = Kit.dp(c, 10);
        item.addView(icon, ilp);
        TextView label2 = Kit.text(c, label, 13, Kit.T2, Kit.regular(c));
        label2.setSingleLine(true);
        item.addView(label2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Kit.dp(c, 32));
        lp.bottomMargin = Kit.dp(c, 3);
        nav.addView(item, lp);

        LinearLayout page = new LinearLayout(c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setVisibility(View.GONE);
        pages.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Section s = new Section(id, item, bar, icon, label2, page);
        sections.add(s);
        item.setOnFocusChangeListener((v, f) -> {
            if (f) select(s);
            paint(s);
        });
        item.setOnClickListener(v -> enter());
        paint(s);
        return page;
    }

    /** A section with nothing usable in it has no rail entry. */
    public void hideIfEmpty(LinearLayout page) {
        for (Section s : sections) {
            if (s.page == page && firstFocusable(page) == null) s.item.setVisibility(View.GONE);
        }
    }

    private void select(Section s) {
        if (current == s) return;
        Section old = current;
        current = s;
        if (old != null) {
            old.page.setVisibility(View.GONE);
            paint(old);
        }
        s.page.setVisibility(View.VISIBLE);
        paint(s);
        scroll.scrollTo(0, 0);
    }

    private void paint(Section s) {
        boolean focused = s.item.isFocused();
        boolean selected = s == current;
        float r = Kit.dp(a, 16);
        s.item.setBackground(Kit.round(focused ? Kit.FOCUS : (selected ? Kit.S2 : 0x00000000), r));
        int fg = focused ? Kit.ON_FOCUS : (selected ? Kit.T1 : Kit.T2);
        s.label.setTextColor(fg);
        s.label.setTypeface(focused || selected ? Kit.medium(a) : Kit.regular(a));
        s.icon.setColorFilter(focused ? Kit.ACC2 : fg);
        s.bar.setVisibility(selected && !focused ? View.VISIBLE : View.INVISIBLE);
    }

    /** Puts the frame on screen with the hint line, the given section selected and its rail entry in focus. */
    public void attach(CharSequence hintOk, CharSequence hintNav) {
        Context c = a;
        LinearLayout hint = new LinearLayout(c);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.addView(key(c, "OK"));
        hint.addView(hintText(c, hintOk));
        hint.addView(key(c, "◀"));
        hint.addView(hintText(c, hintNav));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = Kit.dp(c, 48);
        lp.bottomMargin = Kit.dp(c, 18);
        root.addView(hint, lp);
        a.setContentView(root);
    }

    private static TextView key(Context c, String s) {
        TextView t = Kit.text(c, s, 9.5f, Kit.T2, Kit.medium(c));
        android.graphics.drawable.GradientDrawable g = Kit.round(0x00000000, Kit.dp(c, 4));
        g.setStroke(Math.max(1, Kit.dp(c, 0.5f)), 0xFF4A433B);
        t.setBackground(g);
        t.setPadding(Kit.dp(c, 5), Kit.dp(c, 2), Kit.dp(c, 5), Kit.dp(c, 2));
        return t;
    }

    private static TextView hintText(Context c, CharSequence s) {
        TextView t = Kit.text(c, s, 9.5f, Kit.T3, Kit.regular(c));
        t.setPadding(Kit.dp(c, 6), 0, Kit.dp(c, 12), 0);
        return t;
    }

    /** Focus the rail entry of a section (null: the first shown one) and show its page. */
    public void focusSection(String id) {
        Section target = null;
        for (Section s : sections) {
            if (s.item.getVisibility() != View.VISIBLE) continue;
            if (target == null || s.id.equals(id)) target = s;
            if (s.id.equals(id)) break;
        }
        if (target == null) return;
        select(target);
        final Section t = target;
        t.item.post(t.item::requestFocus);
    }

    /** Focus a given view inside a page (its section becomes current). */
    public void focusView(View v) {
        for (Section s : sections) {
            if (isInside(v, s.page)) {
                select(s);
                paint(s);
                v.post(v::requestFocus);
                return;
            }
        }
    }

    private void enter() {
        if (current == null) return;
        View f = firstFocusable(current.page);
        if (f != null) f.requestFocus();
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v.isFocusable() && v.isEnabled()) return v;
            if (v instanceof ViewGroup) {
                View r = firstFocusable((ViewGroup) v);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isInside(View v, View container) {
        if (v == null) return false;
        if (v == container) return true;
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == container) return true;
            p = p.getParent();
        }
        return false;
    }

    /** Call from Activity.dispatchKeyEvent; true = consumed. */
    public boolean handleKey(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && backEaten) {
            backEaten = false;          // the DOWN went back to the rail: this UP must not close the screen
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || current == null) return false;
        View f = a.getCurrentFocus();
        boolean inRail = isInside(f, nav);
        boolean inPage = isInside(f, current.page);
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (inRail) { enter(); return true; }
                return false;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (inPage) {
                    View next = FocusFinder.getInstance().findNextFocus(root, f, View.FOCUS_LEFT);
                    if (next == null || !isInside(next, current.page)) { current.item.requestFocus(); return true; }
                }
                return inRail;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
                if (inRail || inPage) {
                    View next = FocusFinder.getInstance().findNextFocus(root, f, dir);
                    View pane = inRail ? nav : current.page;
                    if (next == null || !isInside(next, pane)) return true;     // stay in the pane
                }
                return false;
            }
            case KeyEvent.KEYCODE_BACK:
                if (inPage) { current.item.requestFocus(); backEaten = true; return true; }
                return false;
            default:
                return false;
        }
    }
}
