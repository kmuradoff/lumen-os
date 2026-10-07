package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Rounded pill (hero buttons, tab pills, "Customize Home", search chips). Fill/text per state
 * (SPEC 7.4 table): plain / selected (surface2) / outlined / focused (white pill, dark text).
 */
public class PillButton extends View {
    public static final int STYLE_FILLED = 0;   // surface2 fill when unfocused (hero primary, chips)
    public static final int STYLE_OUTLINE = 1;  // 2 px outline (hero secondary)
    public static final int STYLE_PLAIN = 2;    // no fill (tabs)

    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mR = new RectF();
    private CharSequence mLabel = "";
    private Drawable mIcon, mIconFocused;
    private int mStyle;
    private boolean mFocused, mSelected;
    private final int mH, mPadH, mIconSize, mIconGap;
    private float mFocusScale = 1.04f;
    private int mMaxWidth = Integer.MAX_VALUE;
    private Object mTag2;

    public PillButton(Context c, int heightDesign, float textDesign, int style) {
        super(c);
        mH = Theme.px(heightDesign);
        mPadH = Theme.px(heightDesign >= 60 ? 30 : 24);
        mIconSize = Theme.px(textDesign * 1.05f);
        mIconGap = Theme.px(14);
        mStyle = style;
        mText.setTypeface(Theme.MEDIUM);
        mText.setTextSize(Theme.pxf(textDesign) * Theme.fontScale(c));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public PillButton label(CharSequence s) {
        mLabel = s == null ? "" : s;
        setContentDescription(mLabel);
        requestLayout();
        invalidate();
        return this;
    }

    public CharSequence label() {
        return mLabel;
    }

    public PillButton icon(int res) {
        mIcon = res == 0 ? null : Theme.icon(getContext(), res, Theme.TEXT1);
        mIconFocused = res == 0 ? null : Theme.icon(getContext(), res, Theme.ON_FOCUS);
        requestLayout();
        invalidate();
        return this;
    }

    /** Untinted icon (app icons on search chips). */
    public PillButton icon(Drawable d) {
        mIcon = d;
        mIconFocused = d;
        requestLayout();
        invalidate();
        return this;
    }

    public PillButton style(int s) {
        mStyle = s;
        invalidate();
        return this;
    }

    public PillButton maxWidth(int px) {
        mMaxWidth = px;
        return this;
    }

    public PillButton focusScale(float s) {
        mFocusScale = s;
        return this;
    }

    public void setTagObject(Object o) {
        mTag2 = o;
    }

    public Object tagObject() {
        return mTag2;
    }

    public void setFocusState(boolean f, boolean animate) {
        if (mFocused == f) return;
        mFocused = f;
        float s = f ? mFocusScale : 1f;
        if (animate) animate().scaleX(s).scaleY(s).setDuration(f ? Theme.FOCUS_IN_MS : Theme.FOCUS_OUT_MS)
                .setInterpolator(Theme.EMPHASIZED).start();
        else {
            setScaleX(s);
            setScaleY(s);
        }
        invalidate();
    }

    public boolean focusState() {
        return mFocused;
    }

    public void setSelectedState(boolean s) {
        if (mSelected == s) return;
        mSelected = s;
        invalidate();
    }

    public int contentWidth() {
        int w = 2 * mPadH + (int) Math.ceil(mText.measureText(mLabel, 0, mLabel.length()));
        if (mIcon != null) w += mIconSize + (mLabel.length() > 0 ? mIconGap : 0);
        if (mIcon != null && mLabel.length() == 0) w = mH;
        return Math.min(w, mMaxWidth);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        setMeasuredDimension(resolveSize(contentWidth(), wms), mH);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        mR.set(0, 0, w, h);
        float r = h / 2f;
        int textColor = Theme.TEXT1;
        if (mFocused) {
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(Theme.FOCUS);
            c.drawRoundRect(mR, r, r, mFill);
            textColor = Theme.ON_FOCUS;
        } else if (mStyle == STYLE_FILLED || (mStyle == STYLE_PLAIN && mSelected)) {
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(Theme.SURFACE2);
            c.drawRoundRect(mR, r, r, mFill);
        } else if (mStyle == STYLE_OUTLINE) {
            float sw = Theme.pxf(2);
            mFill.setStyle(Paint.Style.STROKE);
            mFill.setStrokeWidth(sw);
            mFill.setColor(Theme.TEXT3);
            mR.inset(sw / 2, sw / 2);
            c.drawRoundRect(mR, r - sw / 2, r - sw / 2, mFill);
        }
        if (mStyle == STYLE_PLAIN && !mSelected && !mFocused) textColor = Theme.TEXT2;
        mText.setColor(textColor);
        float avail = w - 2 * mPadH - (mIcon != null ? mIconSize + mIconGap : 0);
        CharSequence t = TextUtils.ellipsize(mLabel, mText, Math.max(0, avail), TextUtils.TruncateAt.END);
        float tw = mText.measureText(t, 0, t.length());
        float content = tw + (mIcon != null ? mIconSize + (t.length() > 0 ? mIconGap : 0) : 0);
        boolean rtl = Theme.rtl(this);
        float x = (w - content) / 2f;
        Drawable ic = mFocused ? mIconFocused : mIcon;
        if (ic != null) {
            int ix = Math.round(rtl ? x + content - mIconSize : x);
            int iy = (h - mIconSize) / 2;
            ic.setBounds(ix, iy, ix + mIconSize, iy + mIconSize);
            ic.draw(c);
            if (!rtl) x += mIconSize + mIconGap;
        }
        Paint.FontMetrics fm = mText.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(t, 0, t.length(), x, y, mText);
    }
}
