package org.z9x.projector.ui;

import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One level of a {@link PagedPanel}: a title and an ordered list of row views (Row subclasses,
 * HeaderRow, TextRow or any light View). Build pages in code; a page may be rebuilt or edited
 * while shown (call {@link PagedPanel#refreshPage()} after adding/removing rows).
 */
public class Page {
    private CharSequence title;
    final ArrayList<View> rows = new ArrayList<>();
    int focusIndex = -1;
    private Runnable onShown;
    private Runnable onHidden;

    public Page(CharSequence title) {
        this.title = title;
    }

    public CharSequence getTitle() { return title; }

    public Page setTitle(CharSequence t) { title = t; return this; }

    /** Appends a row and returns it (fluent building). */
    public <T extends View> T add(T row) {
        rows.add(row);
        return row;
    }

    public void remove(View row) { rows.remove(row); }

    public void clear() { rows.clear(); focusIndex = -1; }

    public List<View> rows() { return rows; }

    /** Index of the row to focus first (default: first focusable). */
    public Page setInitialFocus(int index) { focusIndex = index; return this; }

    /** Runs each time the page becomes visible (load values from the HAL here, async). */
    public Page setOnShown(Runnable r) { onShown = r; return this; }

    /** Runs when the page is left (pop, push of a child, panel closed). */
    public Page setOnHidden(Runnable r) { onHidden = r; return this; }

    void shown() { if (onShown != null) onShown.run(); }

    void hidden() { if (onHidden != null) onHidden.run(); }
}
