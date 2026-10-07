package org.z9x.projector.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

/**
 * The one shared XGIMI "capsule" notification card for every status message of the app
 * (FEATURE_SPEC 1.2, VERIFIED SystemUI BaseAnimWindow/BaseAnimView, dialog_move_from_right):
 *  - top-right, margin 48, 468 x 156 design px (scaled by W/1920; NOT Android dp);
 *  - background #FA292929, radius 36, 3 px stroke #1AFFFFFF; round icon 72 on the left (optional);
 *  - title bold #B2FFFFFF, optional description #99FFFFFF;
 *  - enters from the screen edge in 500 ms (stock moved the whole window, dialog_move_from_right),
 *    holds 5500 ms, exits back in 500 ms; a new message replaces the text and restarts the hold
 *    timer. Our window therefore spans from the card's left edge to the RIGHT SCREEN EDGE (width =
 *    card + right inset) and the card slides inside it, so it is never clipped at an invisible
 *    line 48 px from the edge. The right inset is the margin, plus the width of a right-side panel
 *    (quick panel, Source card) while one is shown;
 *  - OverlayHost calls {@link #relayout} when a panel is shown or removed: the card moves left of
 *    the panel and is re-added on top of it (same window type, so the later window wins);
 *  - window not focusable and not touchable: keys keep going to the app / panel below.
 * Callable from any thread (posts to the main thread). If the overlay window cannot be added, a
 * plain Toast is shown instead.
 */
public final class Notify {
    private static final String TAG = "Z9xNotify";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static CardView sCard;
    private static WindowManager.LayoutParams sLp;
    private static boolean sAdded;
    /** Right inset of the card inside its window (px): margin + right-side panel width. */
    private static int sInset;
    private static final Runnable HIDE = Notify::slideOut;
    /** Main thread: while true (AK test pattern on screen) a new card is held back, the last one wins. */
    private static boolean sHeld;
    private static Context sHeldApp;
    private static CharSequence sHeldTitle, sHeldDesc;
    private static Drawable sHeldIcon;

    private Notify() {}

    public static void show(Context ctx, int titleRes) {
        show(ctx, ctx.getString(titleRes), null, null);
    }

    public static void show(Context ctx, CharSequence title) {
        show(ctx, title, null, null);
    }

    public static void show(Context ctx, CharSequence title, CharSequence description) {
        show(ctx, title, description, null);
    }

    /** icon may be null. Any thread. */
    public static void show(Context ctx, CharSequence title, CharSequence description, Drawable icon) {
        final Context app = ctx.getApplicationContext();
        MAIN.post(() -> {
            try {
                showOnMain(app, title, description, icon);
            } catch (Throwable t) {
                Log.w(TAG, "card failed, toast instead: " + t);
                try {
                    Toast.makeText(app, title, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * Main thread. AkOverlay sets this while its test pattern is up (the camera must not photograph
     * a card); clearing it shows the last card that came in meanwhile.
     */
    public static void setHeld(boolean held) {
        if (sHeld == held) return;
        sHeld = held;
        if (held) return;
        Context app = sHeldApp;
        CharSequence t = sHeldTitle, d = sHeldDesc;
        Drawable i = sHeldIcon;
        sHeldApp = null;
        sHeldTitle = sHeldDesc = null;
        sHeldIcon = null;
        if (app != null && t != null) show(app, t, d, i);
    }

    /** Hides the card now (animated). Any thread. */
    public static void hide() {
        MAIN.post(() -> {
            MAIN.removeCallbacks(HIDE);
            slideOut();
        });
    }

    private static void showOnMain(Context app, CharSequence title, CharSequence desc, Drawable icon) {
        if (sHeld) {
            Log.i(TAG, "card held while the AK overlay is up: " + title);
            sHeldApp = app;
            sHeldTitle = title;
            sHeldDesc = desc;
            sHeldIcon = icon;
            return;
        }
        int m = Theme.px(app, Theme.NOTIFY_MARGIN);
        if (sCard == null) {
            sCard = new CardView(app);
            sLp = OverlayHost.params(false, Theme.px(app, Theme.NOTIFY_W) + m, Theme.px(app, Theme.NOTIFY_H),
                    Gravity.TOP | Gravity.RIGHT);
            sLp.x = 0;                                        // the window touches the screen edge
            sLp.y = m;
            sLp.setTitle("Z9xNotify");
        }
        // Keep the card clear of a right-side panel (quick panel, source card): stock had its panel
        // on the left, ours is on the right, so the card moves to the panel's left edge.
        applyInset(app, false);
        sCard.set(title, desc, icon);
        MAIN.removeCallbacks(HIDE);
        if (!sAdded) {
            if (!OverlayHost.get(app).addStatic(sCard, sLp)) throw new IllegalStateException("addStatic failed");
            sAdded = true;
            sCard.setTranslationX(sLp.width);                 // card's left edge at the screen edge
        }
        sCard.animate().cancel();
        sCard.animate().translationX(0f).setDuration(Theme.NOTIFY_SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f)).setListener(null).start();
        MAIN.postDelayed(HIDE, Theme.NOTIFY_SLIDE_MS + Theme.NOTIFY_HOLD_MS);
    }

    /**
     * Main thread (OverlayHost, after a panel window was added or removed): if the card is up, move
     * it next to the current right-side panel and, when a panel was just added, re-add the card so
     * it stays above the panel window (same window type: the later window is on top).
     */
    public static void relayout(Context ctx, boolean raise) {
        try {
            if (sCard == null || !sAdded) return;
            Context app = ctx.getApplicationContext();
            applyInset(app, raise);
        } catch (Throwable t) {
            Log.w(TAG, "relayout: " + t);
        }
    }

    /** Window width = card + inset, inset = margin + right panel width. Re-adds when raise. */
    private static void applyInset(Context app, boolean raise) {
        int m = Theme.px(app, Theme.NOTIFY_MARGIN);
        int inset = m + OverlayHost.get(app).rightPanelWidth();
        int w = Theme.px(app, Theme.NOTIFY_W) + inset;
        boolean changed = sLp.width != w || sInset != inset;
        sInset = inset;
        sLp.width = w;
        sCard.setInset(inset);
        if (!sAdded) return;
        OverlayHost host = OverlayHost.get(app);
        if (raise) {
            host.removeStatic(sCard);
            if (!host.addStatic(sCard, sLp)) {
                sAdded = false;
                MAIN.removeCallbacks(HIDE);
                Log.w(TAG, "card could not be re-added over the panel");
            }
        } else if (changed) {
            host.updateStatic(sCard, sLp);
        }
    }

    private static void slideOut() {
        final CardView card = sCard;
        if (card == null || !sAdded) return;
        float dx = sLp != null ? sLp.width : Theme.pxf(card.getContext(), Theme.NOTIFY_W + Theme.NOTIFY_MARGIN);
        card.animate().cancel();
        card.animate().translationX(dx).setDuration(Theme.NOTIFY_SLIDE_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .setListener(new AnimatorListenerAdapter() {
                    private boolean cancelled;
                    @Override public void onAnimationCancel(Animator a) { cancelled = true; }
                    @Override public void onAnimationEnd(Animator a) {
                        card.animate().setListener(null);
                        if (cancelled) return;                 // a new message came in
                        OverlayHost.get(card.getContext()).removeStatic(card);
                        sAdded = false;
                    }
                }).start();
    }

    /** The card, drawn in one View. */
    private static final class CardView extends View {
        private final Paint bg = Theme.fill(Theme.NOTIFY_BG);
        private final Paint stroke;
        private final TextPaint titlePaint, descPaint;
        private final RectF r = new RectF();
        private final float radius, pad, iconSize;
        private CharSequence title = "", desc;
        private Drawable icon;
        private StaticLayout titleLayout, descLayout;
        private int builtWidth = -1;
        private int inset;

        CardView(Context c) {
            super(c);
            stroke = Theme.stroke(c, Theme.NOTIFY_STROKE, Theme.STROKE);
            titlePaint = Theme.text(c, Theme.NOTIFY_TITLE_SIZE, Theme.NOTIFY_TITLE, true);
            descPaint = Theme.text(c, Theme.NOTIFY_DESC_SIZE, Theme.NOTIFY_DESC, false);
            radius = Theme.pxf(c, Theme.NOTIFY_RADIUS);
            pad = Theme.pxf(c, 36);
            iconSize = Theme.pxf(c, Theme.NOTIFY_ICON);
            setWillNotDraw(false);
        }

        /** Right inset (px) between the card and the window's right edge (= the screen edge). */
        void setInset(int px) {
            if (inset == px) return;
            inset = px;
            builtWidth = -1;
            invalidate();
        }

        void set(CharSequence t, CharSequence d, Drawable i) {
            title = t == null ? "" : t;
            desc = (d == null || d.length() == 0) ? null : d;
            icon = i;
            builtWidth = -1;
            invalidate();
        }

        private void build(int w) {
            if (w == builtWidth) return;
            float left = pad + (icon != null ? iconSize + pad * 0.6f : 0);
            int tw = Math.max(1, (int) (w - left - pad));
            titleLayout = StaticLayout.Builder.obtain(title, 0, title.length(), titlePaint, tw)
                    .setMaxLines(desc == null ? 2 : 1).setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            descLayout = desc == null ? null : StaticLayout.Builder.obtain(desc, 0, desc.length(), descPaint, tw)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            builtWidth = w;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = Math.max(1, getWidth() - inset), h = getHeight();
            float half = stroke.getStrokeWidth() / 2f;
            r.set(half, half, w - half, h - half);
            c.drawRoundRect(r, radius, radius, bg);
            c.drawRoundRect(r, radius, radius, stroke);
            build(w);
            float left = pad;
            if (icon != null) {
                int top = Math.round((h - iconSize) / 2f);
                icon.setBounds(Math.round(left), top, Math.round(left + iconSize), Math.round(top + iconSize));
                icon.draw(c);
                left += iconSize + pad * 0.6f;
            }
            float gap = Theme.pxf(getContext(), 6);
            float total = titleLayout.getHeight() + (descLayout != null ? gap + descLayout.getHeight() : 0);
            float y = (h - total) / 2f;
            c.save();
            c.translate(left, y);
            titleLayout.draw(c);
            if (descLayout != null) {
                c.translate(0, titleLayout.getHeight() + gap);
                descLayout.draw(c);
            }
            c.restore();
        }
    }
}
