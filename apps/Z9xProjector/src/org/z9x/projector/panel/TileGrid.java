package org.z9x.projector.panel;

import android.content.Context;
import android.graphics.Rect;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import org.z9x.projector.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * The quick panel's top level (v6.2): a 3-column grid of {@link Tile}s; a wide tile takes a whole
 * row. It is one "row" of the root {@link org.z9x.projector.ui.Page}, so the PagedPanel page stack,
 * BACK handling and auto-hide stay the foundation's.
 *
 * Focus: the grid itself is focusable with FOCUS_AFTER_DESCENDANTS, so PagedPanel (which focuses
 * the first focusable row, or the row that had focus before a sub-page was pushed) lands here and
 * {@link #onRequestFocusInDescendants} puts the focus on the remembered tile ("remember last
 * tile"). D-pad moves between tiles with the framework focus search (pure geometry). The padding
 * keeps the focused tile's scale-up inside the grid's own bounds (no clipping by the ScrollView).
 * Columns are mirrored in RTL.
 */
final class TileGrid extends ViewGroup {
    private static final int COLS = 3;
    private static final float GAP = 14, PAD = 10;

    private final List<Tile> tiles = new ArrayList<>();
    private int lastFocus;

    TileGrid(Context c) {
        super(c);
        setFocusable(true);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setClipChildren(false);
        setClipToPadding(false);
        int p = Theme.px(c, PAD);
        setPadding(p, p, p, p);
    }

    void addTile(Tile t) {
        tiles.add(t);
        addView(t);
        t.setOnFocusChangeListener((v, has) -> {
            if (has) lastFocus = tiles.indexOf((Tile) v);
        });
    }

    List<Tile> tiles() { return tiles; }

    Tile tile(String section) {
        for (Tile t : tiles) if (t.section.equals(section)) return t;
        return null;
    }

    /** Section key of the tile focused last (or the first tile). */
    String lastSection() {
        return tiles.isEmpty() ? null : tiles.get(Math.max(0, Math.min(lastFocus, tiles.size() - 1))).section;
    }

    /** The tile that gets focus the next time the grid is focused. Unknown keys are ignored. */
    void remember(String section) {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).section.equals(section)) { lastFocus = i; return; }
        }
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        if (lastFocus >= 0 && lastFocus < tiles.size()) {
            Tile t = tiles.get(lastFocus);
            if (t.getVisibility() == VISIBLE && t.isFocusable() && t.requestFocus()) return true;
        }
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
    }

    /** Open animation: tiles fade and rise in, one after the other (14 ms apart, 180 ms each). */
    void staggerIn(long startDelay) {
        float dy = Theme.pxf(getContext(), 18);
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            t.setAlpha(0f);
            t.setTranslationY(dy);
            t.animate().alpha(1f).translationY(0f).setStartDelay(startDelay + i * 14L).setDuration(180)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).withLayer().start();
        }
    }

    /** Ends a running stagger at once (panel re-shown, page switch). */
    void settle() {
        for (Tile t : tiles) {
            t.animate().cancel();
            t.setAlpha(1f);
            t.setTranslationY(0f);
        }
    }

    // ------------------------------------------------------------------ layout
    private int colWidth(int width) {
        int inner = width - getPaddingLeft() - getPaddingRight();
        int gap = Theme.px(getContext(), GAP);
        return Math.max(1, (inner - (COLS - 1) * gap) / COLS);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int width = MeasureSpec.getSize(wSpec);
        Context c = getContext();
        int inner = width - getPaddingLeft() - getPaddingRight();
        int gap = Theme.px(c, GAP);
        int colW = colWidth(width);
        int sqH = Theme.px(c, Tile.SQUARE_H), wideH = Theme.px(c, Tile.WIDE_H);
        int height = getPaddingTop() + getPaddingBottom();
        int col = 0, rowH = 0, rows = 0;
        for (Tile t : tiles) {
            if (t.getVisibility() == GONE) continue;
            if (t.wide) {
                if (col > 0) { height += rowH; rows++; col = 0; rowH = 0; }
                t.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(wideH, MeasureSpec.EXACTLY));
                height += wideH;
                rows++;
                continue;
            }
            t.measure(MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(sqH, MeasureSpec.EXACTLY));
            rowH = sqH;
            if (++col == COLS) { height += rowH; rows++; col = 0; rowH = 0; }
        }
        if (col > 0) { height += rowH; rows++; }
        if (rows > 1) height += (rows - 1) * gap;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int gap = Theme.px(getContext(), GAP);
        int colW = colWidth(width);
        int x0 = getPaddingLeft();
        int y = getPaddingTop();
        int col = 0;
        int rowH = 0;
        for (Tile tile : tiles) {
            if (tile.getVisibility() == GONE) continue;
            int w = tile.getMeasuredWidth(), h = tile.getMeasuredHeight();
            if (tile.wide && col > 0) { y += rowH + gap; col = 0; rowH = 0; }
            int x = tile.wide ? x0 : x0 + col * (colW + gap);
            if (rtl) x = width - x - w;
            tile.layout(x, y, x + w, y + h);
            if (tile.wide) {
                y += h + gap;
                continue;
            }
            rowH = h;
            if (++col == COLS) { y += rowH + gap; col = 0; rowH = 0; }
        }
        // Tiles scale around their centres (default pivot): a focused edge tile grows into the padding.
    }
}
