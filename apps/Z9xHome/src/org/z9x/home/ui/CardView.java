package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.img.ImageLoader;

/**
 * One card, drawn in a single View (no child views, no layout pass on focus): art with center crop,
 * dominant-colour placeholder, provider badge, LIVE/NEW badge, progress, focus ring in
 * onDrawForeground, RenderNode shadow via translationZ (SPEC 7.5, 7.6).
 */
public class CardView extends View {
    private static final Paint ART = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private Card mCard;
    private int mW, mH;
    private Bitmap mArt;
    private Bitmap mBadgeIcon;
    private ImageLoader.Request mReq, mBadgeReq;
    private long mArtShownAt;
    private boolean mFocused, mMoving, mOutlined;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mBadgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final RectF mTmp = new RectF();
    private final android.graphics.Path mClip = new android.graphics.Path();
    private Drawable mChevL, mChevR;
    private CharSequence mLine1 = "", mLine2 = "", mLine3 = "";
    private CharSequence[] mTitleLines = new CharSequence[0];
    private Drawable mIcon;
    private LinearGradient mScrim;
    private final float mRadius;

    public CardView(Context c) {
        super(c);
        mRadius = Theme.pxf(Theme.RADIUS);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), mRadius);
            }
        });
        setClipToOutline(true);
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(Theme.pxf(Theme.RING));
        mRing.setColor(Theme.FOCUS);
        mTitle.setColor(Theme.TEXT1);
        mSmall.setColor(Theme.TEXT2);
        mBadgeText.setTypeface(Theme.BOLD);
        mBadgeText.setTextSize(Theme.pxf(18));
        mBadgeText.setLetterSpacing(0.06f);
        mBadgeText.setColor(0xFFFFFFFF);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public Card card() {
        return mCard;
    }

    /** w/h in real px. Keeps the current art if the card's art key did not change. */
    public void bind(Card k, int w, int h) {
        boolean sameArt = mCard != null && k != null && java.util.Objects.equals(mCard.image, k.image) && mW == w && mH == h;
        mCard = k;
        mW = w;
        mH = h;
        if (!sameArt) dropArt();
        mScrim = new LinearGradient(0, h - Theme.pxf(72), 0, h, Theme.BG & 0x00FFFFFF, (Theme.BG & 0x00FFFFFF) | 0xB3000000,
                Shader.TileMode.CLAMP);
        layoutText();
        setContentDescription(k.title + (k.meta.isEmpty() ? "" : ", " + k.meta));
        invalidate();
    }

    private void layoutText() {
        Card k = mCard;
        float fs = Theme.fontScale(getContext());
        mIcon = k.icon != 0 ? Theme.icon(getContext(), k.icon, Theme.TEXT1) : null;
        switch (k.kind) {
            case Card.INPUT:
            case Card.CAST: {
                mTitle.setTypeface(Theme.MEDIUM);
                mTitle.setTextSize(Theme.pxf(30) * fs);
                mSmall.setTypeface(Theme.REGULAR);
                mSmall.setTextSize(Theme.pxf(22) * fs);
                float avail = mW - Theme.pxf(64);
                mLine1 = TextUtils.ellipsize(k.title, mTitle, avail, TextUtils.TruncateAt.END);
                String sub = k.kind == Card.INPUT && !k.desc.isEmpty() ? k.desc + " · " + k.meta : k.meta;
                mLine2 = TextUtils.ellipsize(sub, mSmall, avail - Theme.pxf(22), TextUtils.TruncateAt.END);
                break;
            }
            case Card.TILE:
            case Card.MORE_APPS: {
                mTitle.setTypeface(Theme.MEDIUM);
                mTitle.setTextSize(Theme.pxf(24) * fs);
                mLine1 = TextUtils.ellipsize(k.title, mTitle, mW - Theme.pxf(28), TextUtils.TruncateAt.END);
                if (k.kind == Card.MORE_APPS) mIcon = Theme.icon(getContext(), R.drawable.ic_add_box, Theme.TEXT1);
                break;
            }
            default: {
                // programs and apps without art: title inside the card
                mTitle.setTypeface(Theme.BOLD);
                mTitle.setTextSize(Theme.pxf(k.kind == Card.APP ? 26 : 28) * fs);
                float avail = mW - Theme.pxf(40);
                mTitleLines = twoLines(k.title, mTitle, avail);
                mBadgeText.setTextSize(Theme.pxf(18));
                mLine3 = k.live ? getResources().getString(R.string.badge_live)
                        : (k.isNew && k.kind == Card.PROGRAM ? getResources().getString(R.string.badge_new) : "");
            }
        }
    }

    private static CharSequence[] twoLines(String s, TextPaint p, float w) {
        if (s == null || s.isEmpty()) return new CharSequence[0];
        int n = p.breakText(s, true, w, null);
        if (n >= s.length()) return new CharSequence[]{s};
        int cut = s.lastIndexOf(' ', n);
        if (cut <= 0) cut = n;
        String a = s.substring(0, cut).trim();
        String b = s.substring(cut).trim();
        return new CharSequence[]{a, TextUtils.ellipsize(b, p, w, TextUtils.TruncateAt.END)};
    }

    // ------------------------------------------------------------------ art

    public boolean hasArt() {
        return mArt != null || mCard == null || mCard.image == null;
    }

    public void loadArt(ImageLoader il, boolean deferNetwork) {
        Card k = mCard;
        if (k == null) return;
        if (k.kind == Card.PROGRAM && mBadgeIcon == null && mBadgeReq == null && !k.pkg.isEmpty()) {
            int s = Theme.px(40);
            String key = "icon:" + k.pkg;
            Bitmap b = il.peek(key, s, s);
            if (b != null) mBadgeIcon = b;
            else mBadgeReq = il.load(key, s, s, ImageLoader.KIND_ICON, k.pkg, null, (bmp, col) -> {
                mBadgeReq = null;
                // compare by content, not identity: a refresh rebinds an equal card while this loads
                if (mCard != null && k.pkg.equals(mCard.pkg)) {
                    mBadgeIcon = bmp;
                    invalidate();
                }
            });
        }
        if (k.image == null || mArt != null || mReq != null) return;
        int aw = Theme.artW(k.kind, k.aspect), ah = Theme.artH(k.kind, k.aspect);
        if (k.kind == Card.PROGRAM) {
            aw = Math.round(mW * Theme.FOCUS_SCALE);
            ah = Math.round(mH * Theme.FOCUS_SCALE);
        }
        Bitmap b = il.peek(k.image, aw, ah);
        if (b != null) {
            setArt(b, false);
            return;
        }
        if (deferNetwork) return;
        int kind = k.kind == Card.APP ? ImageLoader.KIND_BANNER : ImageLoader.KIND_POSTER;
        final String img = k.image;
        mReq = il.load(img, aw, ah, kind, k.pkg, k.title, (bmp, col) -> {
            mReq = null;
            Card cur = mCard;
            if (cur == null || !img.equals(cur.image)) return;
            if (col != 0) cur.color = col;
            setArt(bmp, true);
        });
    }

    public void setArt(Bitmap b, boolean fade) {
        mArt = b;
        if (b != null) mSrc.set(0, 0, b.getWidth(), b.getHeight());
        mArtShownAt = fade && Theme.animations() ? android.os.SystemClock.uptimeMillis() : 0;
        invalidate();
    }

    public void dropArt() {
        if (mReq != null) mReq.cancel();
        if (mBadgeReq != null) mBadgeReq.cancel();
        mReq = null;
        mBadgeReq = null;
        mArt = null;
        mBadgeIcon = null;
    }

    // ------------------------------------------------------------------ focus

    public void setFocusState(boolean f, boolean animate, boolean pivotLeft) {
        if (mFocused == f) return;
        mFocused = f;
        if (f) { // the pivot never changes while a card is scaled, so unfocus never jumps
            setPivotY(mH / 2f);
            setPivotX(pivotLeft ? (Theme.rtl(this) ? mW : 0) : mW / 2f);
        }
        float s = f ? Theme.FOCUS_SCALE : 1f;
        float z = f ? Theme.pxf(32) : 0;
        animate().cancel();
        if (animate) {
            animate().scaleX(s).scaleY(s).translationZ(z).setDuration(f ? Theme.FOCUS_IN_MS : Theme.FOCUS_OUT_MS)
                    .setInterpolator(Theme.EMPHASIZED).start();
        } else {
            setScaleX(s);
            setScaleY(s);
            setTranslationZ(z);
        }
        invalidate();
    }

    public boolean focused() {
        return mFocused;
    }

    public void setMoving(boolean m) {
        mMoving = m;
        if (m && mChevL == null) {
            mChevL = Theme.icon(getContext(), R.drawable.ic_chevron_left, Theme.TEXT1);
            mChevR = Theme.icon(getContext(), R.drawable.ic_chevron_right, Theme.TEXT1);
        }
        invalidate();
    }

    /** The "now showing" input keeps an accent outline even when unfocused. */
    public void setOutlined(boolean o) {
        mOutlined = o;
        invalidate();
    }

    /** OK press: 1.08 -> 1.04 -> 1.08 over 120 ms, then {@code after}. */
    public void pulse(Runnable after) {
        float s = mFocused ? Theme.FOCUS_SCALE : 1f;
        animate().cancel();
        animate().scaleX(s - 0.04f).scaleY(s - 0.04f).setDuration(60).setInterpolator(Theme.LINEAR)
                .withEndAction(() -> {
                    animate().scaleX(s).scaleY(s).setDuration(60).start();
                    if (after != null) after.run();
                }).start();
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        Card k = mCard;
        if (k == null) return;
        int w = getWidth(), h = getHeight();
        int bg = k.color != 0 ? k.color : Theme.SURFACE;
        if (k.kind == Card.TILE || k.kind == Card.MORE_APPS || k.kind == Card.INPUT || k.kind == Card.CAST) {
            bg = mFocused && k.kind == Card.TILE ? Theme.SURFACE3 : Theme.SURFACE;
        }
        c.drawColor(bg);
        switch (k.kind) {
            case Card.INPUT:
            case Card.CAST:
                drawInput(c, k, w, h);
                break;
            case Card.TILE:
            case Card.MORE_APPS:
                drawTile(c, w, h);
                break;
            default:
                drawArtCard(c, k, w, h);
        }
    }

    private void drawArtCard(Canvas c, Card k, int w, int h) {
        if (mArt != null) {
            int alpha = 255;
            if (mArtShownAt > 0) {
                long dt = android.os.SystemClock.uptimeMillis() - mArtShownAt;
                if (dt < 150) {
                    alpha = (int) (255 * dt / 150);
                    postInvalidateOnAnimation();
                } else {
                    mArtShownAt = 0;
                }
            }
            ART.setAlpha(alpha);
            mDst.set(0, 0, w, h);
            c.drawBitmap(mArt, mSrc, mDst, ART);
            ART.setAlpha(255);
        }
        if (mArt == null && mTitleLines.length > 0) {
            float pad = Theme.pxf(20);
            float lh = mTitle.getTextSize() * 1.18f;
            float y = h - pad - (k.kind == Card.PROGRAM ? Theme.pxf(48) : 0) - (mTitleLines.length - 1) * lh;
            if (k.kind == Card.APP) y = h / 2f + mTitle.getTextSize() * 0.35f - (mTitleLines.length - 1) * lh / 2f;
            for (CharSequence l : mTitleLines) {
                float x = k.kind == Card.APP ? (w - mTitle.measureText(l, 0, l.length())) / 2f : pad;
                c.drawText(l, 0, l.length(), x, y, mTitle);
                y += lh;
            }
        }
        if (k.kind == Card.PROGRAM) {
            boolean badge = mBadgeIcon != null;
            if (badge || k.progress >= 0) {
                mFill.setShader(mScrim);
                c.drawRect(0, h - Theme.pxf(72), w, h, mFill);
                mFill.setShader(null);
            }
            boolean rtl = Theme.rtl(this);
            float inset = Theme.pxf(12);
            float right = inset;
            if (badge) {
                float s = Theme.pxf(36);
                if (rtl) mTmp.set(w - inset - s, h - inset - s, w - inset, h - inset);
                else mTmp.set(inset, h - inset - s, inset + s, h - inset);
                c.save();
                mClip.reset();
                mClip.addRoundRect(mTmp, Theme.pxf(8), Theme.pxf(8), android.graphics.Path.Direction.CW);
                c.clipPath(mClip);
                c.drawBitmap(mBadgeIcon, null, mTmp, ART);
                c.restore();
                right = inset + s + Theme.pxf(12);
            }
            if (k.progress >= 0) {
                float bh = Theme.pxf(6);
                float y1 = h - Theme.pxf(16) - (badge ? Theme.pxf(15) - bh / 2 : 0);
                float x0 = rtl ? Theme.pxf(16) : right, x1 = rtl ? w - right : w - Theme.pxf(16);
                mFill.setColor(0x4DFFFFFF);
                mTmp.set(x0, y1 - bh, x1, y1);
                c.drawRoundRect(mTmp, bh / 2, bh / 2, mFill);
                mFill.setColor(Theme.ACCENT);
                float pw = (x1 - x0) * k.progress / 1000f;
                if (rtl) mTmp.set(x1 - pw, y1 - bh, x1, y1);
                else mTmp.set(x0, y1 - bh, x0 + pw, y1);
                c.drawRoundRect(mTmp, bh / 2, bh / 2, mFill);
            }
            if (mLine3.length() > 0) {
                float tw = mBadgeText.measureText(mLine3, 0, mLine3.length());
                float bw = tw + Theme.pxf(20), bh = Theme.pxf(30), by = inset;
                float bx = rtl ? w - inset - bw : inset;
                mFill.setColor(k.live ? Theme.LIVE : Theme.ACCENT_STRONG);
                mTmp.set(bx, by, bx + bw, by + bh);
                c.drawRoundRect(mTmp, Theme.pxf(6), Theme.pxf(6), mFill);
                c.drawText(mLine3, 0, mLine3.length(), bx + Theme.pxf(10), by + bh / 2 + Theme.pxf(6.5f), mBadgeText);
            }
        }
        if (k.kind == Card.APP && k.isNew) {
            mFill.setColor(Theme.ACCENT);
            float r = Theme.pxf(6);
            c.drawCircle(Theme.rtl(this) ? Theme.pxf(18) : w - Theme.pxf(18), Theme.pxf(18), r, mFill);
        }
    }

    private void drawInput(Canvas c, Card k, int w, int h) {
        float pad = Theme.pxf(32);
        boolean rtl = Theme.rtl(this);
        if (mIcon != null) {
            int s = Theme.px(64);
            int ix = rtl ? (int) (w - pad - s) : (int) pad;
            mIcon.setBounds(ix, (int) pad, ix + s, (int) pad + s);
            mIcon.draw(c);
        }
        float t1 = mTitle.measureText(mLine1, 0, mLine1.length());
        c.drawText(mLine1, 0, mLine1.length(), rtl ? w - pad - t1 : pad, h - Theme.pxf(66), mTitle);
        float dotY = h - Theme.pxf(34);
        boolean connected = k.kind == Card.CAST || k.state == TvInputManager.INPUT_STATE_CONNECTED;
        boolean standby = k.kind == Card.INPUT && k.state == TvInputManager.INPUT_STATE_CONNECTED_STANDBY;
        mFill.setColor(connected ? Theme.OK : (standby ? Theme.ACCENT : Theme.TEXT3));
        float dotX = rtl ? w - pad - Theme.pxf(5) : pad + Theme.pxf(5);
        c.drawCircle(dotX, dotY - Theme.pxf(8), Theme.pxf(5), mFill);
        float t2 = mSmall.measureText(mLine2, 0, mLine2.length());
        c.drawText(mLine2, 0, mLine2.length(), rtl ? w - pad - Theme.pxf(22) - t2 : pad + Theme.pxf(22), dotY, mSmall);
    }

    private void drawTile(Canvas c, int w, int h) {
        if (mIcon != null) {
            int s = Theme.px(52);
            int x = (w - s) / 2, y = Theme.px(26);
            mIcon.setBounds(x, y, x + s, y + s);
            mIcon.draw(c);
        }
        float tw = mTitle.measureText(mLine1, 0, mLine1.length());
        c.drawText(mLine1, 0, mLine1.length(), (w - tw) / 2f, h - Theme.pxf(26), mTitle);
    }

    @Override
    public void onDrawForeground(Canvas c) {
        super.onDrawForeground(c);
        float sw = mRing.getStrokeWidth();
        if (mFocused) {
            mRing.setColor(Theme.FOCUS);
            mRing.setStrokeWidth(Theme.pxf(Theme.RING));
            mTmp.set(sw / 2, sw / 2, getWidth() - sw / 2, getHeight() - sw / 2);
            c.drawRoundRect(mTmp, mRadius - sw / 2, mRadius - sw / 2, mRing);
        } else if (mOutlined) {
            mRing.setColor(Theme.ACCENT);
            float s2 = Theme.pxf(2);
            mRing.setStrokeWidth(s2);
            mTmp.set(s2 / 2, s2 / 2, getWidth() - s2 / 2, getHeight() - s2 / 2);
            c.drawRoundRect(mTmp, mRadius, mRadius, mRing);
            mRing.setStrokeWidth(Theme.pxf(Theme.RING));
        }
        if (mMoving) {
            Drawable l = mChevL, r = mChevR;
            int s = Theme.px(44);
            int y = (getHeight() - s) / 2;
            mFill.setColor(0x99000000);
            c.drawCircle(Theme.px(30), getHeight() / 2f, s / 2f + Theme.px(4), mFill);
            c.drawCircle(getWidth() - Theme.px(30), getHeight() / 2f, s / 2f + Theme.px(4), mFill);
            if (l != null) {
                l.setBounds(Theme.px(30) - s / 2, y, Theme.px(30) + s / 2, y + s);
                l.draw(c);
            }
            if (r != null) {
                r.setBounds(getWidth() - Theme.px(30) - s / 2, y, getWidth() - Theme.px(30) + s / 2, y + s);
                r.draw(c);
            }
        }
    }
}
