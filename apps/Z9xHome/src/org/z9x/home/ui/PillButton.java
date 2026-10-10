package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Rounded pill (hero buttons, tabs, icon buttons, "Customize Home", search chips), direction D:
 * unfocused pills are a translucent paper tint (or nothing), the focused one is the light pill with dark
 * text; {@link #glow} adds the 6 px halo ring and the warm accent glow of D_Home's primary button,
 * drawn outside the bounds (parents do not clip children). {@link #shadow} gives the label and the icon
 * a soft dark shadow while unfocused (the header's tabs and icons over art, 1.0.1: no plate).
 */
public class PillButton extends View {
    public static final int STYLE_FILLED = 0;   // paper tint 14 % when unfocused (hero buttons, chips)
    public static final int STYLE_OUTLINE = 1;  // 2 px outline
    public static final int STYLE_PLAIN = 2;    // no fill (tabs, icon buttons); selected = paper tint 15 %

    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF mR = new RectF();
    private final RectF mBox = new RectF();
    private CharSequence mLabel = "";
    private Drawable mIcon, mIconFocused;
    private int mStyle;
    private boolean mFocused, mSelected, mGlow, mShadow;
    private int mIconRes;
    private Glow.Shadow mIconShadow;
    /** Header shadow: as the clock's (TopBar.ClockBlock), a little tighter for the smaller type. */
    private static final int SHADOW = 0x8C000000;
    private final int mH, mIconSize, mIconGap;
    private int mPadStart, mPadEnd;
    private float mFocusScale = 1.04f;
    private int mMaxWidth = Integer.MAX_VALUE;
    private Object mTag2;
    private Bitmap mGlowMask;
    private int mGlowB;

    public PillButton(Context c, int heightDesign, float textDesign, int style) {
        super(c);
        mH = Theme.px(heightDesign);
        mPadStart = mPadEnd = Theme.px(heightDesign >= 60 ? 36 : 26);
        mIconSize = Theme.px(Math.round(textDesign * (heightDesign >= 60 ? 0.96f : 1.1f)));
        mIconGap = Theme.px(14);
        mStyle = style;
        mText.setTypeface(Theme.MEDIUM);
        mText.setTextSize(Theme.pxf(textDesign) * Theme.fontScale(c));
        mGlowB = Theme.px(46);
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
        mIconRes = res;
        mIcon = res == 0 ? null : Theme.icon(getContext(), res, Theme.TEXT1);
        mIconFocused = res == 0 ? null : Theme.icon(getContext(), res, Theme.ON_FOCUS);
        makeIconShadow();
        requestLayout();
        invalidate();
        return this;
    }

    /** Untinted icon (app icons on search chips). */
    public PillButton icon(Drawable d) {
        mIconRes = 0;
        mIconShadow = null; // app icons (search chips): no shadow
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

    /** Horizontal padding in design px (D_Home primary: 32 on the icon side, 40 after the text). */
    public PillButton padding(int startDesign, int endDesign) {
        mPadStart = Theme.px(startDesign);
        mPadEnd = Theme.px(endDesign);
        requestLayout();
        return this;
    }

    /**
     * Soft shadow under the label and the icon while unfocused (header): readable over bright art in
     * the header scrim's fade; the focused light pill needs none.
     */
    public PillButton shadow(boolean s) {
        mShadow = s;
        makeIconShadow();
        invalidate();
        return this;
    }

    /**
     * Pills with shadows (header) fade in browse mode without an offscreen layer: a layer is cut to the
     * view's bounds, and the soft shadows reach past them (TopBar.ClockBlock does the same).
     */
    @Override
    public boolean hasOverlappingRendering() {
        return !mShadow;
    }

    /** The icon's shadow bitmap, made once here (never in onDraw); cached per icon and size (Glow). */
    private void makeIconShadow() {
        mIconShadow = mShadow && mIconRes != 0 ? Glow.iconShadow(mIcon, mIconRes, mIconSize, Theme.pxf(10)) : null;
    }

    /** Halo ring + warm glow when focused (hero buttons). */
    public PillButton glow(boolean g) {
        mGlow = g;
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
        if (f && mGlow && getWidth() > 0) mGlowMask = Glow.mask(getWidth(), getHeight(), getHeight() / 2f, mGlowB);
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
        mText.setTypeface(Theme.SEMIBOLD);
        int w = mPadStart + mPadEnd + (int) Math.ceil(mText.measureText(mLabel, 0, mLabel.length()));
        if (mIcon != null) w += mIconSize + (mLabel.length() > 0 ? mIconGap : 0);
        if (mIcon != null && mLabel.length() == 0) w = mH;
        return Math.min(w, mMaxWidth);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        setMeasuredDimension(resolveSize(contentWidth(), wms), mH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (mFocused && mGlow) mGlowMask = Glow.mask(w, h, h / 2f, mGlowB);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        mR.set(0, 0, w, h);
        float r = h / 2f;
        int textColor = Theme.TEXT1;
        boolean strong = mFocused || mSelected;
        if (mFocused) {
            if (mGlow) {
                if (mGlowMask == null) mGlowMask = Glow.mask(w, h, r, mGlowB);
                if (mGlowMask != null) {
                    mGlowPaint.setColor(Theme.alpha(Theme.ACCENT, 0.32f));
                    Glow.box(mBox, w, h, mGlowB); // a 1080p-scale mask, enlarged at 2K / 4K
                    c.drawBitmap(mGlowMask, null, mBox, mGlowPaint);
                }
                float ring = Theme.pxf(6);
                mFill.setStyle(Paint.Style.STROKE);
                mFill.setStrokeWidth(ring);
                mFill.setColor(Theme.alpha(Theme.FOCUS, 0.2f));
                mR.inset(-ring / 2, -ring / 2);
                c.drawRoundRect(mR, r + ring / 2, r + ring / 2, mFill);
                mR.set(0, 0, w, h);
            }
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(Theme.FOCUS);
            c.drawRoundRect(mR, r, r, mFill);
            textColor = Theme.ON_FOCUS;
        } else if (mStyle == STYLE_FILLED || (mStyle == STYLE_PLAIN && mSelected)) {
            mFill.setStyle(Paint.Style.FILL);
            mFill.setColor(Theme.alpha(Theme.TEXT1, mStyle == STYLE_FILLED ? 0.14f : 0.15f));
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
        mText.setTypeface(strong ? Theme.SEMIBOLD : Theme.MEDIUM);
        mText.setColor(textColor);
        boolean shade = mShadow && !mFocused;
        if (shade) mText.setShadowLayer(Theme.pxf(12), 0, Theme.pxf(1), SHADOW);
        else mText.clearShadowLayer();
        float avail = w - mPadStart - mPadEnd - (mIcon != null ? mIconSize + mIconGap : 0);
        CharSequence t = TextUtils.ellipsize(mLabel, mText, Math.max(0, avail), TextUtils.TruncateAt.END);
        float tw = mText.measureText(t, 0, t.length());
        float content = tw + (mIcon != null ? mIconSize + (t.length() > 0 ? mIconGap : 0) : 0);
        boolean rtl = Theme.rtl(this);
        // icon + text: start padding on the icon side (D: 32 / 40); icon only or text only: centred
        float x = (mIcon != null && t.length() > 0) ? (rtl ? w - mPadStart - content : mPadStart) : (w - content) / 2f;
        Drawable ic = mFocused ? mIconFocused : mIcon;
        if (ic != null) {
            int ix = Math.round(rtl ? x + content - mIconSize : x);
            int iy = (h - mIconSize) / 2;
            Glow.Shadow sh = mIconShadow;
            if (shade && sh != null) {
                float dy = Theme.pxf(1); // as the text's
                mBox.set(ix + sh.x, iy + sh.y + dy, ix + sh.x + sh.w, iy + sh.y + dy + sh.h);
                mGlowPaint.setColor(SHADOW);
                c.drawBitmap(sh.bmp, null, mBox, mGlowPaint);
            }
            ic.setBounds(ix, iy, ix + mIconSize, iy + mIconSize);
            ic.draw(c);
            if (!rtl) x += mIconSize + mIconGap;
        }
        Paint.FontMetrics fm = mText.getFontMetrics();
        float y = h / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(t, 0, t.length(), x, y, mText);
    }
}
