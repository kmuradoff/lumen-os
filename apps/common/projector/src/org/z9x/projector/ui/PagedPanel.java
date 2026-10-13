package org.z9x.projector.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * The XGIMI-style right-side panel: dark translucent rounded card, title on top, a scrolling list of
 * rows, and a page stack (sub-pages slide in; BACK pops, BACK on the root page closes).
 *
 * Usage (main thread):
 * <pre>
 *   Page root = new Page(ctx.getString(R.string.panel_title));
 *   root.add(new HeaderRow(ctx, "Picture"));
 *   ChoiceRow mode = root.add(new ChoiceRow(ctx, "Picture mode", labels, (row, i) -> ...));
 *   PagedPanel p = new PagedPanel(root);
 *   OverlayHost.get(ctx).toggle(p);
 * </pre>
 * Values come from the HAL asynchronously (Hal.query); update rows with their setters on the main
 * thread. Rows are created once per page; switching pages re-attaches the same row objects.
 */
public class PagedPanel extends Panel {
    private static final String TAG = "Z9xPagedPanel";
    private final ArrayDeque<Page> stack = new ArrayDeque<>();
    private TextView titleView;
    private ScrollView scroll;
    private LinearLayout list;
    private Context ctx;

    public PagedPanel(Page root) {
        stack.push(root);
    }

    public Page currentPage() { return stack.peek(); }

    public int depth() { return stack.size(); }

    /** Opens a sub-page (slides in from the right). */
    public void push(Page p) {
        Page cur = stack.peek();
        if (cur != null) {
            cur.focusIndex = focusedIndex();
            cur.hidden();
        }
        stack.push(p);
        if (list != null) render(true, 1);
    }

    /** Goes back one page. Returns false on the root page. */
    public boolean pop() {
        if (stack.size() <= 1) return false;
        Page top = stack.pop();
        top.hidden();
        if (list != null) render(true, -1);
        return true;
    }

    /** Re-renders the current page after rows were added/removed. Keeps the focused index. */
    public void refreshPage() {
        Page cur = stack.peek();
        if (cur == null || list == null) return;
        cur.focusIndex = focusedIndex();
        render(false, 0);
    }

    @Override
    protected boolean onBack() { return pop(); }

    @Override
    protected View onCreateView(Context c) {
        ctx = c;
        int padH = Theme.px(c, Theme.PANEL_PAD_H);
        int padTop = Theme.px(c, Theme.PANEL_PAD_TOP);
        int margin = Theme.px(c, Theme.PANEL_MARGIN);

        FrameLayout outer = new FrameLayout(c);
        outer.setClipToPadding(false);
        outer.setPadding(margin, margin, margin, margin);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PANEL_BG);
        bg.setCornerRadius(Theme.pxf(c, Theme.PANEL_RADIUS));
        bg.setStroke(Theme.px(c, Theme.STROKE), Theme.PANEL_STROKE);
        card.setBackground(bg);
        card.setPadding(padH, padTop, padH, padH);
        outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        titleView = new TextView(c);
        titleView.setTextColor(Theme.TEXT);
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, Theme.PANEL_TITLE_SIZE));
        titleView.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPaddingRelative(Theme.px(c, Theme.ROW_PAD_H), 0, 0, Theme.px(c, 20));
        titleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        card.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Theme.px(c, 48));
        scroll.setSmoothScrollingEnabled(true);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        render(false, 0);
        return outer;
    }

    @Override
    protected void onShown(View root) {
        super.onShown(root);
        // Row i starts i*16 ms later (stock misckey stagger), mirrored slide.
        float dx = Theme.pxf(root.getContext(), 40);
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            v.setTranslationX(dx);
            v.animate().translationX(0f).setStartDelay(i * Theme.ROW_STAGGER_MS)
                    .setDuration(Theme.PANEL_ENTER_MS).setInterpolator(Theme.panelInterpolator()).start();
        }
    }

    @Override
    protected void onDismissed() {
        Page cur = stack.peek();
        if (cur != null) cur.hidden();
        if (list != null) list.removeAllViews();     // rows may be re-attached by a later show
    }

    private int focusedIndex() {
        if (list == null) return -1;
        View f = list.getFocusedChild();
        return f == null ? -1 : list.indexOfChild(f);
    }

    private void render(boolean animate, int direction) {
        final Page page = stack.peek();
        if (page == null) return;
        titleView.setText(page.getTitle());
        list.removeAllViews();
        int gap = Theme.px(ctx, Theme.ROW_GAP);
        for (View v : page.rows) {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            v.setTranslationX(0f);
            list.addView(v, lp);
        }
        View target = null;
        if (page.focusIndex >= 0 && page.focusIndex < list.getChildCount()) {
            View v = list.getChildAt(page.focusIndex);
            if (v.isFocusable()) target = v;
        }
        if (target == null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).isFocusable()) { target = list.getChildAt(i); break; }
            }
        }
        final View f = target;
        if (f != null) f.post(() -> {
            if (f.isAttachedToWindow()) f.requestFocus();
        });
        else scroll.scrollTo(0, 0);
        if (animate && direction != 0) {
            float dx = Theme.pxf(ctx, 60) * direction;
            list.setTranslationX(dx);
            list.setAlpha(0f);
            list.animate().translationX(0f).alpha(1f).setDuration(Theme.PAGE_SWITCH_MS)
                    .setInterpolator(Theme.panelInterpolator()).start();
        }
        try {
            page.shown();
        } catch (Throwable t) {
            Log.w(TAG, "page shown hook: " + t);
        }
    }
}
