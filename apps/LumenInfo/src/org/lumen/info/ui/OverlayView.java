package org.lumen.info.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The overlay panel: a title and label / value rows drawn straight on a canvas (no child views, so a once-a-second
 * update is one invalidate and, only when the size changes, one layout). Long values are cut with an ellipsis at
 * the maximum width.
 */
public final class OverlayView extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String head = "";
    private List<Rows.Row> rows = new ArrayList<>();
    private float textPx, pad, gap, labelW, valueW, lineH;
    private int maxWidth = Integer.MAX_VALUE;
    private int lastW, lastH;

    public OverlayView(Context c) {
        super(c);
        bg.setColor(Color.argb(205, 12, 13, 16));
        title.setColor(Color.rgb(242, 178, 107));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        label.setColor(Color.rgb(170, 176, 186));
        value.setColor(Color.rgb(244, 239, 230));
        value.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
        setSize(1);
    }

    /** 0 small, 1 medium, 2 large (scaled by the display density). */
    public void setSize(int size) {
        float d = getResources().getDisplayMetrics().density;
        float sp = size == 0 ? 11 : size == 2 ? 17 : 14;
        textPx = sp * d;
        pad = textPx * 0.7f;
        gap = textPx * 0.9f;
        lineH = textPx * 1.35f;
        title.setTextSize(textPx);
        label.setTextSize(textPx);
        value.setTextSize(textPx);
        measureRows();
        requestLayout();
        invalidate();
    }

    public void setMaxWidth(int px) {
        maxWidth = px;
        requestLayout();
    }

    public void setRows(String head, List<Rows.Row> rows) {
        this.head = head;
        this.rows = rows;
        measureRows();
        int w = desiredW(), h = desiredH();
        if (w != lastW || h != lastH) requestLayout();
        invalidate();
    }

    private void measureRows() {
        labelW = 0;
        valueW = title.measureText(head);
        for (Rows.Row r : rows) {
            labelW = Math.max(labelW, label.measureText(r.label));
            valueW = Math.max(valueW, value.measureText(r.value == null ? "" : r.value));
        }
    }

    private int desiredW() {
        int w = (int) Math.ceil(pad * 2 + labelW + gap + valueW);
        return Math.min(w, maxWidth);
    }

    private int desiredH() {
        return (int) Math.ceil(pad * 2 + lineH * (rows.size() + 1));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        lastW = desiredW();
        lastH = desiredH();
        setMeasuredDimension(lastW, lastH);
    }

    @Override
    protected void onDraw(Canvas c) {
        rect.set(0, 0, getWidth(), getHeight());
        float r = textPx * 0.6f;
        c.drawRoundRect(rect, r, r, bg);
        float baseline = pad + textPx;
        c.drawText(ellipsize(head, title, getWidth() - 2 * pad), pad, baseline, title);
        float vx = pad + labelW + gap;
        float vMax = getWidth() - pad - vx;
        for (Rows.Row row : rows) {
            baseline += lineH;
            c.drawText(row.label, pad, baseline, label);
            c.drawText(ellipsize(row.value == null ? "" : row.value, value, vMax), vx, baseline, value);
        }
    }

    private static String ellipsize(String s, Paint p, float max) {
        if (max <= 0) return "";
        if (p.measureText(s) <= max) return s;
        int n = p.breakText(s, true, max - p.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }
}
