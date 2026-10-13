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
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.util.Locale;

/**
 * The one shared XGIMI "capsule" notification card for every status message of the app
 * (FEATURE_SPEC 1.2, VERIFIED SystemUI BaseAnimWindow/BaseAnimView, dialog_move_from_right):
 *  - top-right, margin 48; sized to its text (design px, scaled by W/1920; NOT Android dp). Stock had a
 *    fixed 468 x 156 (Theme.NOTIFY_W / NOTIFY_H, no longer used) and cut longer text with an ellipsis.
 *    Width = the widest text line + icon + paddings, clamped to {@link #MIN_W}..{@link #MAX_W}; content
 *    narrower than MIN_W is centred. The title wraps to at most {@link #TITLE_LINES} lines and the
 *    description to at most {@link #DESC_LINES}, both with balanced line breaks; the height follows the
 *    text. Paddings {@link #PAD_H} / {@link #PAD_V}, icon gap {@link #ICON_GAP};
 *  - a message without a description is split at its first sentence or clause break (". ", ": ", "; ",
 *    "! ", "? " and the full-width marks; at a comma too when the whole text needs more than TITLE_LINES
 *    title lines): the head becomes the title, the rest the description ("Eye protection is not ready.
 *    Restart the projector and try again." is a bold "Eye protection is not ready" over the hint). An
 *    ellipsis appears only past these limits (absurdly long foreign text, see NotifyRequestReceiver);
 *  - background #FA292929, radius 36, 3 px stroke #1AFFFFFF; icon 72 (optional) vertically centred against
 *    the text block; title bold #B2FFFFFF, optional description #99FFFFFF. A right-to-left locale mirrors
 *    the card: icon on the right, lines aligned to the right;
 *  - enters from the screen edge in 500 ms (stock moved the whole window, dialog_move_from_right),
 *    holds 5500 ms, exits back in 500 ms; a new message replaces the text (the card takes its new size
 *    at once) and restarts the hold timer. Our window therefore spans from the card's left edge to the
 *    RIGHT SCREEN EDGE (width = card + right inset, height = card) and the card slides inside it, so it
 *    is never clipped at an invisible line 48 px from the edge. The right inset is the margin, plus the
 *    width of a right-side panel (quick panel, Source card) while one is shown;
 *  - OverlayHost calls {@link #relayout} when a panel is shown or removed: the card moves left of
 *    the panel and is re-added on top of it (same window type, so the later window wins);
 *  - window not focusable and not touchable: keys keep going to the app / panel below.
 * One layout per message (no animation of its own besides the slide, no blur). Callable from any
 * thread (posts to the main thread). If the overlay window cannot be added, a plain Toast is shown
 * instead.
 */
public final class Notify {
    private static final String TAG = "Z9xNotify";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Card geometry (design px; the colours, radius, margin, icon and text sizes are in Theme).
    /** Narrowest card: a short message ("Autofocus") still reads as a card, centred in it. */
    private static final float MIN_W = 360;
    /** Widest card: longer text wraps instead of running across the picture. */
    private static final float MAX_W = 760;
    private static final float PAD_H = 32;
    private static final float PAD_V = 28;
    private static final float ICON_GAP = 24;
    private static final float LINE_GAP = 6;
    private static final int TITLE_LINES = 2;
    private static final int DESC_LINES = 3;

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
        if (sCard == null) {
            sCard = new CardView(app);
            sLp = OverlayHost.params(false, 1, 1, Gravity.TOP | Gravity.RIGHT);
            sLp.x = 0;                                        // the window touches the screen edge
            sLp.y = Theme.px(app, Theme.NOTIFY_MARGIN);
            sLp.setTitle("Z9xNotify");
        }
        sCard.set(title, desc, icon);                         // lays the text out: the card's size
        // Window = card + right inset. Keep the card clear of a right-side panel (quick panel, source
        // card): stock had its panel on the left, ours is on the right, so the card moves to the
        // panel's left edge.
        applyLayout(app, false);
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
            applyLayout(app, raise);
        } catch (Throwable t) {
            Log.w(TAG, "relayout: " + t);
        }
    }

    /** Window = card width + inset (margin + right panel width) by card height. Re-adds when raise. */
    private static void applyLayout(Context app, boolean raise) {
        int inset = Theme.px(app, Theme.NOTIFY_MARGIN) + OverlayHost.get(app).rightPanelWidth();
        int w = sCard.cardW + inset, h = sCard.cardH;
        boolean changed = sLp.width != w || sLp.height != h || sInset != inset;
        sInset = inset;
        sLp.width = w;
        sLp.height = h;
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
        float dx = sLp != null ? sLp.width : card.getWidth();
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

    /**
     * The card, drawn in one View at the window's left edge (the rest of the window up to the screen
     * edge is the transparent inset). {@link #set} lays the text out once per message and sizes the card.
     */
    private static final class CardView extends View {
        /** Sentence and clause breaks for a split; commas only when the whole text is too long for the title. */
        private static final String BREAKS = ".:;!?\u3002\uFF1A\uFF1B\uFF01\uFF1F", COMMAS = ",\uFF0C\u3001";
        /** Separator of a list of parts (the game cue's chips). */
        private static final String SEP = " \u00b7 ";
        /** Marks dropped from the end of a head that becomes the title ("!" and "?" stay). */
        private static final String DROP = ".:;,\u3002\uFF1A\uFF1B\uFF0C\u3001";

        private final Paint bg = Theme.fill(Theme.NOTIFY_BG);
        private final Paint stroke;
        private final TextPaint titlePaint, descPaint;
        private final RectF r = new RectF();
        private final float radius, padH, padV, iconSize, iconGap, lineGap, minW, maxW;
        private Drawable icon;
        private StaticLayout titleLayout, descLayout;
        private boolean rtl;
        /** Card size (px), set by {@link #set}. */
        int cardW = 1, cardH = 1;
        /** Left edge of the content (icon + text column) and the column's width (px). */
        private float contentX, textW;

        CardView(Context c) {
            super(c);
            stroke = Theme.stroke(c, Theme.NOTIFY_STROKE, Theme.STROKE);
            titlePaint = Theme.text(c, Theme.NOTIFY_TITLE_SIZE, Theme.NOTIFY_TITLE, true);
            descPaint = Theme.text(c, Theme.NOTIFY_DESC_SIZE, Theme.NOTIFY_DESC, false);
            radius = Theme.pxf(c, Theme.NOTIFY_RADIUS);
            padH = Theme.pxf(c, PAD_H);
            padV = Theme.pxf(c, PAD_V);
            iconSize = Theme.pxf(c, Theme.NOTIFY_ICON);
            iconGap = Theme.pxf(c, ICON_GAP);
            lineGap = Theme.pxf(c, LINE_GAP);
            minW = Theme.pxf(c, MIN_W);
            maxW = Theme.pxf(c, MAX_W);
            setWillNotDraw(false);
        }

        /** New content: lays the text out and sizes the card. Main thread. */
        void set(CharSequence t, CharSequence d, Drawable i) {
            CharSequence title = t == null ? "" : t;
            CharSequence desc = (d == null || d.length() == 0) ? null : d;
            icon = i;
            rtl = getResources().getConfiguration().getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            float iconW = icon != null ? iconSize + iconGap : 0;
            int cap = Math.max(1, (int) (maxW - 2 * padH - iconW));
            if (desc == null) {
                CharSequence[] parts = split(title, cap);
                if (parts != null) {
                    title = parts[0];
                    desc = parts[1];
                }
            }
            title = keepParts(title, titlePaint, cap);
            if (desc != null) desc = keepParts(desc, descPaint, cap);
            // The widest line at the widest column decides the column; lay out again at that width so
            // that a right-to-left paragraph is aligned to the card, unless that would change the lines.
            StaticLayout tl = layout(title, titlePaint, cap, TITLE_LINES);
            StaticLayout dl = desc == null ? null : layout(desc, descPaint, cap, DESC_LINES);
            int col = Math.max(1, Math.min(cap, (int) Math.ceil(Math.max(widest(tl), widest(dl)))));
            if (col < cap) {
                StaticLayout tl2 = layout(title, titlePaint, col, TITLE_LINES);
                StaticLayout dl2 = desc == null ? null : layout(desc, descPaint, col, DESC_LINES);
                if (sameLines(tl, tl2) && sameLines(dl, dl2)) {
                    tl = tl2;
                    dl = dl2;
                } else {
                    col = cap;
                }
            }
            titleLayout = tl;
            descLayout = dl;
            textW = col;
            float contentW = iconW + col;
            cardW = Math.round(Math.max(minW, Math.min(maxW, contentW + 2 * padH)));
            contentX = (cardW - contentW) / 2f;               // = padH unless the card is at MIN_W
            cardH = Math.round(Math.max(textHeight(), icon != null ? iconSize : 0) + 2 * padV);
            invalidate();
        }

        private float textHeight() {
            return titleLayout.getHeight() + (descLayout != null ? lineGap + descLayout.getHeight() : 0);
        }

        /**
         * Lines start at the card's start side (the right in a right-to-left locale) whatever the text's own
         * direction ("HDMI-CEC" over an Arabic description); the text keeps its own direction. Balanced lines,
         * except for text past maxLines: full lines then, so the ellipsis comes as late as possible.
         */
        private StaticLayout layout(CharSequence s, TextPaint p, int width, int maxLines) {
            TextDirectionHeuristic dir = rtl ? TextDirectionHeuristics.FIRSTSTRONG_RTL
                    : TextDirectionHeuristics.FIRSTSTRONG_LTR;
            boolean textRtl = dir.isRtl(s, 0, s.length());
            int strategy = lineCount(s, p, width) > maxLines ? Layout.BREAK_STRATEGY_SIMPLE
                    : Layout.BREAK_STRATEGY_BALANCED;
            return StaticLayout.Builder.obtain(s, 0, s.length(), p, width)
                    .setAlignment(textRtl == rtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE)
                    .setIncludePad(false).setBreakStrategy(strategy).setTextDirection(dir)
                    .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
        }

        private int lineCount(CharSequence s, TextPaint p, int width) {
            return StaticLayout.Builder.obtain(s, 0, s.length(), p, width)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).build().getLineCount();
        }

        private static float widest(StaticLayout l) {
            float w = 0;
            if (l != null) for (int i = 0; i < l.getLineCount(); i++) w = Math.max(w, l.getLineWidth(i));
            return w;
        }

        private static boolean sameLines(StaticLayout a, StaticLayout b) {
            if (a == null || b == null) return a == b;
            if (a.getLineCount() != b.getLineCount()) return false;
            for (int i = 0; i < a.getLineCount(); i++) {
                if (a.getEllipsisCount(i) != b.getEllipsisCount(i)) return false;
            }
            return true;
        }

        /**
         * A message without a description: {head, rest} at the first break of {@link #BREAKS} (a mark
         * followed by a space; a full-width mark needs none), or of {@link #COMMAS} when the whole text
         * needs more than TITLE_LINES title lines, whose head fits in the title lines and whose rest fits
         * in the description lines; null when there is none (the text stays the title, cut with an
         * ellipsis past TITLE_LINES).
         */
        private CharSequence[] split(CharSequence text, int cap) {
            String s = text.toString();
            boolean tooLong = lineCount(s, titlePaint, cap) > TITLE_LINES;
            for (String marks : tooLong ? new String[]{BREAKS, COMMAS} : new String[]{BREAKS}) {
                for (int i = 0; i < s.length() - 1; i++) {
                    char ch = s.charAt(i);
                    if (marks.indexOf(ch) < 0) continue;
                    if (ch < 0x3000 && !Character.isWhitespace(s.charAt(i + 1))) continue;
                    if (ch == '.' && !sentenceEnd(s, i)) continue;
                    String head = s.substring(0, i + 1).trim();
                    String rest = s.substring(i + 1).trim();
                    while (!head.isEmpty() && DROP.indexOf(head.charAt(head.length() - 1)) >= 0) {
                        head = head.substring(0, head.length() - 1).trim();
                    }
                    if (head.isEmpty() || rest.isEmpty()) continue;
                    if (lineCount(head, titlePaint, cap) > TITLE_LINES) break;   // later breaks: longer heads
                    if (lineCount(rest, descPaint, cap) > DESC_LINES) continue;
                    int n = Character.charCount(rest.codePointAt(0));
                    rest = rest.substring(0, n).toUpperCase(Locale.getDefault()) + rest.substring(n);
                    return new CharSequence[]{head, rest};
                }
            }
            return null;
        }

        /**
         * A list of parts joined by " \u00b7 " ("PlayStation 5 \u00b7 Low latency \u00b7 120 Hz") breaks only after
         * a separator: spaces inside a part and before a dot become no-break spaces, while every part fits
         * the column (else the text is left as it is).
         */
        private static CharSequence keepParts(CharSequence text, TextPaint p, int width) {
            String s = text.toString();
            if (!s.contains(SEP)) return text;
            String[] parts = s.split(SEP, -1);
            StringBuilder b = new StringBuilder(s.length());
            for (int i = 0; i < parts.length; i++) {
                if (p.measureText(parts[i]) > width) return text;
                if (i > 0) b.append("\u00a0\u00b7 ");
                b.append(parts[i].replace(' ', '\u00a0'));
            }
            return b.toString();
        }

        /**
         * The full stop at {@code i} ends a sentence: not after a one-letter word or a number ("z. B.",
         * "1."), and not followed by a lower-case letter or a digit ("Nr. 2").
         */
        private static boolean sentenceEnd(String s, int i) {
            int b = i;
            while (b > 0 && Character.isLetterOrDigit(s.charAt(b - 1))) b--;
            String word = s.substring(b, i);
            if (word.length() < 2 || TextUtils.isDigitsOnly(word)) return false;
            int a = i + 1;
            while (a < s.length() && Character.isWhitespace(s.charAt(a))) a++;
            return a < s.length() && !Character.isLowerCase(s.codePointAt(a)) && !Character.isDigit(s.codePointAt(a));
        }

        @Override
        protected void onDraw(Canvas c) {
            float half = stroke.getStrokeWidth() / 2f;
            r.set(half, half, cardW - half, cardH - half);
            c.drawRoundRect(r, radius, radius, bg);
            c.drawRoundRect(r, radius, radius, stroke);
            if (titleLayout == null) return;
            float textX = contentX;
            if (icon != null) {
                float iconX = rtl ? contentX + textW + iconGap : contentX;
                if (!rtl) textX += iconSize + iconGap;
                int top = Math.round((cardH - iconSize) / 2f);
                icon.setBounds(Math.round(iconX), top, Math.round(iconX + iconSize), Math.round(top + iconSize));
                icon.draw(c);
            }
            c.save();
            c.translate(textX, (cardH - textHeight()) / 2f);
            titleLayout.draw(c);
            if (descLayout != null) {
                c.translate(0, titleLayout.getHeight() + lineGap);
                descLayout.draw(c);
            }
            c.restore();
        }
    }
}
