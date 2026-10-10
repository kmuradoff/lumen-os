package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
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
 * One card, drawn in a single View (no child views, no layout pass on focus), direction D: art with
 * center crop inside a rounded clip, dominant-colour placeholder, provider badge, LIVE/NEW badge,
 * progress. "Continue watching" cards (Watch Next) carry their title, meta and progress inside the art
 * (D_Home). Focus: scale 1.06, a 4 dp light ring just outside the edge, the warm accent glow behind and
 * a RenderNode shadow via translationZ (the parents do not clip children, so ring and glow may draw
 * outside the bounds). Nothing is allocated in onDraw. The art is decoded at the card's own pixel size,
 * so a card at rest (nearly all of them) shows it 1:1; 1.0.0 decoded at the focused size, and every
 * unfocused card was then shrunk by 6 % with a single bilinear tap, which softened banners and posters.
 */
public class CardView extends View {
    private static final Paint ART = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final int CONTINUE_META = 0xFFD3CBBE;
    private static final int BADGE = 36;   // provider icon on program cards, design px

    private Card mCard;
    private int mW, mH;
    private Bitmap mArt;
    private Bitmap mBadgeIcon;
    private ImageLoader.Request mReq, mBadgeReq;
    private long mArtShownAt, mFocusAt;
    private boolean mFocused, mMoving, mOutlined, mContinue;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG); // fills and scrims
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mBadgeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();
    private final RectF mTmp = new RectF();
    private final Path mClip = new Path();
    private final Path mBadgeClip = new Path();
    private Drawable mChevL, mChevR;
    private CharSequence mLine1 = "", mLine2 = "", mLine3 = "";
    private CharSequence[] mTitleLines = new CharSequence[0];
    private Drawable mIcon;
    private LinearGradient mScrim;
    private float mRadius;
    private Bitmap mGlowMask;
    private final int mGlowB;

    public CardView(Context c) {
        super(c);
        mRadius = Theme.pxf(Theme.RADIUS);
        mGlowB = Theme.px(40);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), mRadius);
            }
        });
        // no clipToOutline: the ring and the glow live outside the bounds; the art is clipped by a path
        mRing.setStyle(Paint.Style.STROKE);
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
        boolean sizeChanged = w != mW || h != mH;
        mCard = k;
        mW = w;
        mH = h;
        if (!sameArt) dropArt();
        mContinue = k.kind == Card.PROGRAM && k.table == Card.T_WATCH_NEXT;
        float r = Theme.cardRadius(k.kind);
        if (r != mRadius || sizeChanged) {
            mRadius = r;
            invalidateOutline();
        }
        mClip.reset();
        mClip.addRoundRect(0, 0, w, h, mRadius, mRadius, Path.Direction.CW);
        float scrimH = mContinue ? Theme.pxf(124) : Theme.pxf(72);
        int top = mContinue ? 0x00080706 : Theme.BG & 0x00FFFFFF;
        int bottom = mContinue ? 0xE0080706 : (Theme.BG & 0x00FFFFFF) | 0xB3000000;
        mScrim = new LinearGradient(0, h - scrimH, 0, h, top, bottom, Shader.TileMode.CLAMP);
        if (mFocused || mGlowMask != null) mGlowMask = Glow.mask(w, h, mRadius, mGlowB);
        layoutText();
        String extra = k.meta.isEmpty() ? k.left : (k.left.isEmpty() ? k.meta : k.meta + ", " + k.left);
        setContentDescription(k.title + (extra.isEmpty() ? "" : ", " + extra));
        invalidate();
    }

    private void layoutText() {
        Card k = mCard;
        float fs = Theme.fontScale(getContext());
        mIcon = k.icon != 0 ? Theme.icon(getContext(), k.icon, Theme.TEXT1) : null;
        mLine3 = "";
        switch (k.kind) {
            case Card.INPUT:
            case Card.CAST: {
                mTitle.setTypeface(Theme.SEMIBOLD);
                mTitle.setTextSize(Theme.pxf(28) * fs);
                mSmall.setTypeface(Theme.REGULAR);
                mSmall.setTextSize(Theme.pxf(20) * fs);
                mSmall.setColor(Theme.TEXT2);
                float avail = mW - Theme.pxf(64);
                mLine1 = TextUtils.ellipsize(k.title, mTitle, avail, TextUtils.TruncateAt.END);
                String sub = k.kind == Card.INPUT && !k.desc.isEmpty() ? k.desc + " · " + k.meta : k.meta;
                mLine2 = TextUtils.ellipsize(sub, mSmall, avail - Theme.pxf(22), TextUtils.TruncateAt.END);
                break;
            }
            case Card.TILE:
            case Card.MORE_APPS: {
                mTitle.setTypeface(Theme.MEDIUM);
                mTitle.setTextSize(Theme.pxf(22) * fs);
                mLine1 = TextUtils.ellipsize(k.title, mTitle, mW - Theme.pxf(28), TextUtils.TruncateAt.END);
                if (k.kind == Card.MORE_APPS) mIcon = Theme.icon(getContext(), k.icon != 0 ? k.icon : R.drawable.ic_add_box, Theme.TEXT1);
                break;
            }
            default: {
                if (mContinue) {
                    // D_Home: title 24 semibold + meta 18 inside the art, 22 px from the edges
                    mTitle.setTypeface(Theme.SEMIBOLD);
                    mTitle.setTextSize(Theme.pxf(24) * fs);
                    mSmall.setTypeface(Theme.REGULAR);
                    mSmall.setTextSize(Theme.pxf(18) * fs);
                    mSmall.setColor(CONTINUE_META);
                    float avail = mW - Theme.pxf(44);
                    mLine1 = TextUtils.ellipsize(k.title, mTitle, avail, TextUtils.TruncateAt.END);
                    String second = !k.left.isEmpty() ? k.left : k.meta;
                    String meta = k.appLabel.isEmpty() ? second : (second.isEmpty() ? k.appLabel : k.appLabel + " · " + second);
                    mLine2 = TextUtils.ellipsize(meta, mSmall, avail, TextUtils.TruncateAt.END);
                    mTitleLines = new CharSequence[0];
                    break;
                }
                // programs and apps without art: title inside the card
                mTitle.setTypeface(Theme.SEMIBOLD);
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
        if (k.kind == Card.PROGRAM && !mContinue && mBadgeIcon == null && mBadgeReq == null && !k.pkg.isEmpty()) {
            int s = Theme.px(BADGE); // the size it is drawn at
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
        // 1:1 at rest (the focus zoom of 6 % is the only scaling left)
        int aw = mW, ah = mH;
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
            if (mGlowMask == null) mGlowMask = Glow.mask(mW, mH, mRadius, mGlowB);
            mFocusAt = animate && Theme.animations() ? android.os.SystemClock.uptimeMillis() : 0;
        }
        float s = f ? Theme.FOCUS_SCALE : 1f;
        float z = f ? Theme.pxf(28) : 0;
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

    /** OK press: 1.06 -> 1.02 -> 1.06 over 120 ms, then {@code after}. */
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
        if (mFocused && mGlowMask != null) {
            float a = 1f;
            if (mFocusAt > 0) {
                a = Math.min(1f, (android.os.SystemClock.uptimeMillis() - mFocusAt) / (float) Theme.FOCUS_IN_MS);
                if (a < 1f) postInvalidateOnAnimation();
                else mFocusAt = 0;
            }
            mGlowPaint.setColor(Theme.alpha(Theme.ACCENT, 0.34f * a));
            Glow.box(mTmp, mW, mH, mGlowB); // a 1080p-scale mask, enlarged at 2K / 4K
            c.drawBitmap(mGlowMask, null, mTmp, mGlowPaint);
        }
        int bg = k.color != 0 ? k.color : Theme.SURFACE;
        if (k.kind == Card.TILE || k.kind == Card.MORE_APPS || k.kind == Card.INPUT || k.kind == Card.CAST) {
            bg = mFocused && k.kind == Card.TILE ? Theme.SURFACE3 : Theme.SURFACE2;
        }
        int save = c.save();
        c.clipPath(mClip);
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
        c.restoreToCount(save);
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
        if (mContinue) {
            drawContinue(c, k, w, h);
            return;
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
            float inset = Theme.px(12); // whole pixels: the badge icon is drawn 1:1
            float right = inset;
            if (badge) {
                float s = Theme.px(BADGE);
                if (rtl) mTmp.set(w - inset - s, h - inset - s, w - inset, h - inset);
                else mTmp.set(inset, h - inset - s, inset + s, h - inset);
                c.save();
                mBadgeClip.reset();
                mBadgeClip.addRoundRect(mTmp, Theme.pxf(8), Theme.pxf(8), Path.Direction.CW);
                c.clipPath(mBadgeClip);
                c.drawBitmap(mBadgeIcon, null, mTmp, ART);
                c.restore();
                right = inset + s + Theme.pxf(12);
            }
            if (k.progress >= 0) {
                float bh = Theme.pxf(5);
                float y1 = h - Theme.pxf(16) - (badge ? Theme.pxf(15) - bh / 2 : 0);
                float x0 = rtl ? Theme.pxf(16) : right, x1 = rtl ? w - right : w - Theme.pxf(16);
                drawProgress(c, x0, x1, y1, bh, k.progress, rtl);
            }
            if (mLine3.length() > 0) {
                float tw = mBadgeText.measureText(mLine3, 0, mLine3.length());
                float bw = tw + Theme.pxf(20), bh = Theme.pxf(30), by = inset;
                float bx = rtl ? w - inset - bw : inset;
                mFill.setColor(k.live ? Theme.LIVE : Theme.ACCENT);
                mBadgeText.setColor(k.live ? 0xFFFFFFFF : Theme.ON_FOCUS);
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

    /** D_Home "Continue watching" card: scrim, title, app · time left, 4 px progress 13 px from the bottom. */
    private void drawContinue(Canvas c, Card k, int w, int h) {
        mFill.setShader(mScrim);
        c.drawRect(0, h - Theme.pxf(124), w, h, mFill);
        mFill.setShader(null);
        boolean rtl = Theme.rtl(this);
        float pad = Theme.pxf(22);
        float metaBase = h - Theme.pxf(26) - mSmall.descent();
        float titleBase = metaBase + mSmall.ascent() - Theme.pxf(4) - mTitle.descent();
        float t1 = mTitle.measureText(mLine1, 0, mLine1.length());
        c.drawText(mLine1, 0, mLine1.length(), rtl ? w - pad - t1 : pad, titleBase, mTitle);
        float t2 = mSmall.measureText(mLine2, 0, mLine2.length());
        c.drawText(mLine2, 0, mLine2.length(), rtl ? w - pad - t2 : pad, metaBase, mSmall);
        if (k.progress >= 0) {
            float bh = Theme.pxf(4);
            drawProgress(c, pad, w - pad, h - Theme.pxf(13), bh, k.progress, rtl);
        }
    }

    private void drawProgress(Canvas c, float x0, float x1, float bottom, float bh, int permille, boolean rtl) {
        mFill.setColor(Theme.alpha(Theme.TEXT1, 0.24f));
        mTmp.set(x0, bottom - bh, x1, bottom);
        c.drawRoundRect(mTmp, bh / 2, bh / 2, mFill);
        mFill.setColor(Theme.ACCENT);
        float pw = (x1 - x0) * permille / 1000f;
        if (rtl) mTmp.set(x1 - pw, bottom - bh, x1, bottom);
        else mTmp.set(x0, bottom - bh, x0 + pw, bottom);
        c.drawRoundRect(mTmp, bh / 2, bh / 2, mFill);
    }

    private void drawInput(Canvas c, Card k, int w, int h) {
        float pad = Theme.pxf(32);
        boolean rtl = Theme.rtl(this);
        if (mIcon != null) {
            int s = Theme.px(60);
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
        c.drawCircle(dotX, dotY - Theme.pxf(7), Theme.pxf(5), mFill);
        float t2 = mSmall.measureText(mLine2, 0, mLine2.length());
        c.drawText(mLine2, 0, mLine2.length(), rtl ? w - pad - Theme.pxf(22) - t2 : pad + Theme.pxf(22), dotY, mSmall);
    }

    private void drawTile(Canvas c, int w, int h) {
        if (mIcon != null) {
            int s = Theme.px(52);
            int x = (w - s) / 2, y = Theme.px(30);
            mIcon.setBounds(x, y, x + s, y + s);
            mIcon.draw(c);
        }
        float tw = mTitle.measureText(mLine1, 0, mLine1.length());
        c.drawText(mLine1, 0, mLine1.length(), (w - tw) / 2f, h - Theme.pxf(28), mTitle);
    }

    @Override
    public void onDrawForeground(Canvas c) {
        super.onDrawForeground(c);
        int w = getWidth(), h = getHeight();
        if (mFocused) {
            // D: the ring sits just outside the tile edge (box-shadow 0 0 0 4dp)
            float sw = Theme.ring();
            mRing.setColor(Theme.FOCUS);
            mRing.setStrokeWidth(sw);
            mTmp.set(-sw / 2, -sw / 2, w + sw / 2, h + sw / 2);
            c.drawRoundRect(mTmp, mRadius + sw / 2, mRadius + sw / 2, mRing);
        } else if (mOutlined) {
            mRing.setColor(Theme.ACCENT);
            float s2 = Theme.pxf(3);
            mRing.setStrokeWidth(s2);
            mTmp.set(s2 / 2, s2 / 2, w - s2 / 2, h - s2 / 2);
            c.drawRoundRect(mTmp, mRadius, mRadius, mRing);
        }
        if (mMoving) {
            Drawable l = mChevL, r = mChevR;
            int s = Theme.px(44);
            int y = (h - s) / 2;
            mFill.setColor(0x99000000);
            c.drawCircle(Theme.px(30), h / 2f, s / 2f + Theme.px(4), mFill);
            c.drawCircle(w - Theme.px(30), h / 2f, s / 2f + Theme.px(4), mFill);
            if (l != null) {
                l.setBounds(Theme.px(30) - s / 2, y, Theme.px(30) + s / 2, y + s);
                l.draw(c);
            }
            if (r != null) {
                r.setBounds(w - Theme.px(30) - s / 2, y, w - Theme.px(30) + s / 2, y + s);
                r.draw(c);
            }
        }
    }
}
