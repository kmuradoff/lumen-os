/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The viewer's card (PassthroughActivity: the banner at the top and the state card in the middle): our glyph for
 * the device's kind on an accent tile (Brands), a title and a smaller line. Plain views on a rounded dark
 * background: no blur, no shadow, no animation of its own.
 *
 * <p>Sized to its text: as wide as the widest line plus paddings, at least {@link #MIN_DP} (narrower content is
 * centred), the text column at most {@link #TEXT_MAX_DP}. Longer text wraps with balanced line breaks (title at
 * most 2 lines, the line at most 3) and the card grows in height, the tile centred against the text; an ellipsis
 * only past those limits. Both text views are WRAP_CONTENT: a vertical LinearLayout gives its children
 * MATCH_PARENT by default, and a TextView that is not WRAP_CONTENT keeps its old width when its text changes
 * and its height does not (no new layout pass), so a longer state after a shorter one ("Connecting...", then
 * "PlayStation is asleep") was cut with an ellipsis at the old width.
 */
final class BrandCard extends LinearLayout {
    /** Narrowest card (dp). */
    private static final int MIN_DP = 180;
    /** Widest text column (dp): longer text wraps instead of running across the picture. */
    private static final int TEXT_MAX_DP = 480;

    private final ImageView mIcon;
    private final TextView mTitle;
    private final TextView mLine;
    private final GradientDrawable mBg = new GradientDrawable();
    private final GradientDrawable mTile = new GradientDrawable();
    private final float mTitleSp;
    /** Title, line, kind and accent last set (a refresh with the same content touches nothing). */
    private String mKey;

    BrandCard(Context c, float titleSp) {
        super(c);
        mTitleSp = titleSp;
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER);
        mBg.setColor(0xE6121417);
        setBackground(mBg);
        setVisibility(View.GONE);
        mIcon = new ImageView(c);
        mIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcon.setBackground(mTile);
        addView(mIcon, new LinearLayout.LayoutParams(0, 0));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        mTitle = new FitText(c);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mLine = new FitText(c);
        mLine.setTextColor(0xB3FFFFFF);
        mLine.setMaxLines(3);
        mLine.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        texts.addView(mLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(texts, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        size();
    }

    /** dp / sp sizes (px once set): call again after a density change. */
    void size() {
        mBg.setCornerRadius(dp(18));
        setPaddingRelative(dp(14), dp(14), dp(16), dp(14));
        setMinimumWidth(dp(MIN_DP));
        mTile.setCornerRadius(dp(13));
        mIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams il = (LinearLayout.LayoutParams) mIcon.getLayoutParams();
        il.width = dp(52);
        il.height = dp(52);
        il.setMarginEnd(dp(16));
        mIcon.setLayoutParams(il);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTitleSp);
        mTitle.setMaxWidth(dp(TEXT_MAX_DP));
        mLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mLine.setMaxWidth(dp(TEXT_MAX_DP));
    }

    /**
     * Sets the content; false when it is the same as before. An empty line is hidden. Kind: Brands.KIND_*
     * (console: gamepad, player: streaming box, other: HDMI plug).
     */
    boolean set(String title, String line, int kind, int accent) {
        String key = title + "\n" + line + "\n" + kind + "\n" + accent;
        if (key.equals(mKey)) return false;
        mKey = key;
        mTitle.setText(title);
        mLine.setText(line);
        mLine.setVisibility(TextUtils.isEmpty(line) ? View.GONE : View.VISIBLE);
        mTile.setColor(accent);
        mIcon.setImageResource(kind == Brands.KIND_CONSOLE ? R.drawable.ic_banner_console
                : kind == Brands.KIND_PLAYER ? R.drawable.ic_banner_player : R.drawable.ic_banner_hdmi);
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * A text view as wide as its widest line: a wrapped TextView is otherwise as wide as its maximum width,
     * which leaves an empty band at the end of the card. Balanced line breaks keep the lines even.
     */
    private static final class FitText extends TextView {
        FitText(Context c) {
            super(c);
            setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec, hSpec);
            Layout l = getLayout();
            if (l == null || l.getLineCount() < 2) return;
            float w = 0;
            for (int i = 0; i < l.getLineCount(); i++) {
                if (l.getEllipsisCount(i) > 0) return;        // cut text keeps the full width
                w = Math.max(w, l.getLineWidth(i));
            }
            int want = (int) Math.ceil(w) + getCompoundPaddingLeft() + getCompoundPaddingRight();
            if (want < getMeasuredWidth()) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(want, MeasureSpec.EXACTLY), hSpec);
            }
        }
    }
}
