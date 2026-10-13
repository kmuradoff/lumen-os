package org.z9x.projector.recents;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.projector.R;
import org.z9x.projector.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Recent apps screen (speed spec 6.3, Google TV look, Lumen tokens). Framework views only, built in
 * code (no inflation): a 88 % black scrim (no blur: GPU cost on the MT9681), the title, a "Close all" pill
 * at the top right, and one horizontal row of 16:9 cards (384 x 216 design px, 16 px corners, 32 px gap)
 * with the app name and "now" / "N min ago" below.
 *
 * Focus: the focused card scales to 1.08 in 150 ms (decelerate) with a light ring and an elevation
 * shadow. Keys: OK = switch; DOWN = the card's "Close" pill (OK closes); long-press OK on a card = close;
 * UP = "Close all"; BACK / long-press HOME again = dismiss (RecentsActivity). Closing a card collapses it
 * in 180 ms and focus moves to the neighbour; after the last one "No open apps" shows briefly.
 * All sizes are design px of a 1920-wide UI (Theme.px).
 */
final class RecentsView extends FrameLayout {
    interface Listener {
        void onOpen(RecentsModel.Card c);
        void onClose(RecentsModel.Card c);
        void onCloseAll();
    }

    // Lumen tokens (apps/common/tokens.xml): text, dim text, focus fill / focus text, surfaces
    private static final int SCRIM = 0xE0000000;
    private static final int TEXT = 0xFFE8EAED, TEXT_DIM = 0xFF9AA0A6, FOCUS_TEXT = 0xFF0E0E0F;
    private static final int SURFACE = 0xFF1E232C, SURFACE2 = 0xFF263041;
    private static final float CARD_W = 384, CARD_H = 216, CARD_R = 16, GAP = 32, FOCUS_SCALE = 1.08f;
    private static final long FOCUS_MS = 150, CLOSE_MS = 180;

    private final Listener listener;
    private final LinearLayout row;
    private final HorizontalScrollView scroller;
    private final TextView closeAll;
    private final TextView empty;
    private final List<Column> columns = new ArrayList<>();
    private Column lastFocused;

    RecentsView(Context c, Listener l) {
        super(c);
        listener = l;
        setBackgroundColor(SCRIM);
        setClipChildren(false);

        TextView title = text(c, c.getString(R.string.recents_title), 44, TEXT);
        title.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tl.setMarginStart(px(96));
        tl.topMargin = px(96);
        addView(title, tl);

        closeAll = pill(c, c.getString(R.string.recents_close_all));
        closeAll.setOnClickListener(v -> listener.onCloseAll());
        LayoutParams cl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(64), Gravity.TOP | Gravity.END);
        cl.setMarginEnd(px(96));
        cl.topMargin = px(92);
        addView(closeAll, cl);

        scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setFillViewport(false);
        scroller.setSmoothScrollingEnabled(true);
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(px(96), px(40), px(96), px(40));
        scroller.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL);
        sl.topMargin = px(40);
        addView(scroller, sl);

        empty = text(c, c.getString(R.string.recents_empty), 34, TEXT_DIM);
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    /** Builds the cards; focus goes to {@code focus} (clamped). Main thread. */
    void bind(List<RecentsModel.Card> cards, int focus) {
        row.removeAllViews();
        columns.clear();
        for (RecentsModel.Card card : cards) {
            Column col = new Column(getContext(), card);
            columns.add(col);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(px(GAP));
            row.addView(col, lp);
        }
        linkFocus();
        if (columns.isEmpty()) {
            showEmpty();
            return;
        }
        Column f = columns.get(Math.max(0, Math.min(focus, columns.size() - 1)));
        f.card.requestFocus();
    }

    boolean isEmpty() {
        return columns.isEmpty();
    }

    /** A thumbnail / banner arrived for {@code c} (main thread). */
    void refresh(RecentsModel.Card c) {
        for (Column col : columns) if (col.data == c) col.card.invalidate();
    }

    /** Collapses the card of {@code c}, then removes it; focus goes to the neighbour. Main thread. */
    void remove(RecentsModel.Card c) {
        final Column col = find(c);
        if (col == null) return;
        int idx = columns.indexOf(col);
        columns.remove(col);
        linkFocus();
        Column next = columns.isEmpty() ? null : columns.get(Math.min(idx, columns.size() - 1));
        if (next != null) next.card.requestFocus();
        final int w0 = col.getWidth() + px(GAP);
        col.setFocusable(false);
        col.card.setFocusable(false);
        col.close.setFocusable(false);
        ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
        a.setDuration(CLOSE_MS);
        a.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            col.setAlpha(f);
            col.setScaleX(0.9f + 0.1f * f);
            col.setScaleY(0.9f + 0.1f * f);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) col.getLayoutParams();
            lp.width = Math.max(0, Math.round((w0 - px(GAP)) * f));
            lp.setMarginEnd(Math.round(px(GAP) * f));
            col.setLayoutParams(lp);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                row.removeView(col);
                col.release();
            }
        });
        a.start();
        if (columns.isEmpty()) showEmpty();
    }

    /** Every card goes at once ("Close all"). Main thread. */
    void clear() {
        for (Column col : columns) col.release();
        columns.clear();
        row.animate().alpha(0f).setDuration(CLOSE_MS).withEndAction(row::removeAllViews).start();
        showEmpty();
    }

    void release() {
        for (Column col : columns) col.release();
        columns.clear();
        row.removeAllViews();
    }

    private void showEmpty() {
        closeAll.setVisibility(GONE);
        empty.setAlpha(0f);
        empty.setVisibility(VISIBLE);
        empty.animate().alpha(1f).setDuration(200).start();
        empty.setFocusable(true);
        empty.requestFocus();
    }

    private Column find(RecentsModel.Card c) {
        for (Column col : columns) if (col.data == c) return col;
        return null;
    }

    /** Explicit D-pad links: card DOWN -> its pill, card UP / pill UP -> Close all, pill LEFT/RIGHT -> cards. */
    private void linkFocus() {
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            col.card.setNextFocusDownId(col.close.getId());
            col.card.setNextFocusUpId(closeAll.getId());
            col.close.setNextFocusUpId(col.card.getId());
            col.close.setNextFocusLeftId(i > 0 ? columns.get(i - 1).card.getId() : col.close.getId());
            col.close.setNextFocusRightId(i + 1 < columns.size() ? columns.get(i + 1).card.getId() : col.close.getId());
            col.card.setNextFocusLeftId(i > 0 ? columns.get(i - 1).card.getId() : col.card.getId());
            col.card.setNextFocusRightId(i + 1 < columns.size() ? columns.get(i + 1).card.getId() : col.card.getId());
        }
        if (!columns.isEmpty()) {
            closeAll.setNextFocusDownId((lastFocused != null && columns.contains(lastFocused)
                    ? lastFocused : columns.get(0)).card.getId());
        }
    }

    private int px(float design) {
        return Theme.px(getContext(), design);
    }

    private TextView text(Context c, CharSequence s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** A focusable pill: translucent surface, white with dark text when focused. */
    private TextView pill(Context c, CharSequence s) {
        final TextView t = text(c, s, 26, TEXT);
        t.setId(View.generateViewId());
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(32), 0, px(32), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setDefaultFocusHighlightEnabled(false);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(32));
        bg.setColor(SURFACE2);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? TEXT : SURFACE2);
            t.setTextColor(has ? FOCUS_TEXT : TEXT);
            t.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(FOCUS_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
        return t;
    }

    // ------------------------------------------------------------------ one card column

    private final class Column extends LinearLayout {
        final RecentsModel.Card data;
        final CardView card;
        final TextView close;

        Column(Context c, RecentsModel.Card d) {
            super(c);
            data = d;
            setOrientation(VERTICAL);
            setClipChildren(false);
            setClipToPadding(false);
            card = new CardView(c, d);
            card.setId(View.generateViewId());
            addView(card, new LayoutParams(px(CARD_W), px(CARD_H)));
            TextView name = text(c, d.label, 28, TEXT);
            LayoutParams nl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = px(22);
            addView(name, nl);
            TextView when = text(c, age(c, d.lastActive), 22, TEXT_DIM);
            LayoutParams wl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wl.topMargin = px(4);
            addView(when, wl);
            close = pill(c, c.getString(R.string.recents_close));
            close.setVisibility(INVISIBLE);
            close.setOnClickListener(v -> listener.onClose(data));
            LayoutParams pl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(60));
            pl.topMargin = px(20);
            pl.gravity = Gravity.CENTER_HORIZONTAL;
            addView(close, pl);
            OnFocusChangeListener show = (v, has) -> updatePill();
            card.setOnFocusChangeListener((v, has) -> {
                card.focus(has);
                if (has) {
                    lastFocused = this;
                    closeAll.setNextFocusDownId(card.getId());
                }
                updatePill();
            });
            final OnFocusChangeListener pillFocus = close.getOnFocusChangeListener();
            close.setOnFocusChangeListener((v, has) -> {
                if (pillFocus != null) pillFocus.onFocusChange(v, has);
                show.onFocusChange(v, has);
            });
        }

        private void updatePill() {
            boolean on = card.hasFocus() || close.hasFocus();
            close.setVisibility(on ? VISIBLE : INVISIBLE);
        }

        void release() {
            card.release();
        }
    }

    /** when = TaskInfo.lastActiveTime, which counts from boot (SystemClock.elapsedRealtime), not the epoch:
     *  shown as a calendar date it read "1 января 1970", so it is moved onto the wall clock first. */
    private static CharSequence age(Context c, long when) {
        long now = System.currentTimeMillis();
        if (when > 0) when = now - (android.os.SystemClock.elapsedRealtime() - when);
        if (when <= 0 || now - when < DateUtils.MINUTE_IN_MILLIS) return c.getString(R.string.recents_now);
        return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE);
    }

    // ------------------------------------------------------------------ card (Canvas)

    /** The 16:9 card: thumbnail (center-crop) or banner on a surface, else the icon; focus ring + scale. */
    private final class CardView extends View {
        private final RecentsModel.Card data;
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF r = new RectF();
        private final Rect src = new Rect(), dst = new Rect();
        private final float radius;
        private ValueAnimator anim;
        private float f;                    // focus 0..1
        private boolean longFired;

        CardView(Context c, RecentsModel.Card d) {
            super(c);
            data = d;
            radius = Theme.pxf(c, CARD_R);
            setFocusable(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            setContentDescription(d.label);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Theme.pxf(c, 4));
            ring.setColor(TEXT);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            setOnClickListener(v -> listener.onOpen(data));
        }

        void focus(boolean has) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(f, has ? 1f : 0f);
            anim.setDuration(FOCUS_MS);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                f = (float) a.getAnimatedValue();
                float s = 1f + (FOCUS_SCALE - 1f) * f;
                setScaleX(s);
                setScaleY(s);
                setElevation(Theme.pxf(getContext(), 18) * f);
                invalidate();
            });
            anim.start();
            if (has) {
                // keep the focused card fully visible in the row
                post(() -> {
                    View col = (View) getParent();
                    if (col == null) return;
                    int left = col.getLeft() - px(96), right = col.getRight() + px(96);
                    int x = scroller.getScrollX(), w = scroller.getWidth();
                    if (left < x) scroller.smoothScrollTo(left, 0);
                    else if (right > x + w) scroller.smoothScrollTo(right - w, 0);
                });
            }
        }

        @Override public boolean onKeyDown(int code, KeyEvent ev) {
            if (isOk(code)) {
                if (ev.getRepeatCount() == 0) {
                    longFired = false;
                    ev.startTracking();
                }
                return true;
            }
            return super.onKeyDown(code, ev);
        }

        @Override public boolean onKeyLongPress(int code, KeyEvent ev) {
            if (isOk(code)) {
                longFired = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                listener.onClose(data);
                return true;
            }
            return super.onKeyLongPress(code, ev);
        }

        @Override public boolean onKeyUp(int code, KeyEvent ev) {
            if (isOk(code)) {
                boolean go = !longFired && !ev.isCanceled() && (ev.getFlags() & KeyEvent.FLAG_LONG_PRESS) == 0;
                longFired = false;
                if (go) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    listener.onOpen(data);
                }
                return true;
            }
            return super.onKeyUp(code, ev);
        }

        private boolean isOk(int code) {
            return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            clip.rewind();
            r.set(0, 0, w, h);
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.save();
            c.clipPath(clip);
            fill.setColor(SURFACE);
            c.drawRect(0, 0, w, h, fill);
            Bitmap t = data.thumb;
            if (t != null && !t.isRecycled()) {
                centerCrop(t.getWidth(), t.getHeight(), w, h);
                c.drawBitmap(t, src, dst, bmp);
            } else if (data.banner != null) {
                fill.setColor(SURFACE2);
                c.drawRect(0, 0, w, h, fill);
                Drawable b = data.banner;
                int bw = Math.max(1, b.getIntrinsicWidth()), bh = Math.max(1, b.getIntrinsicHeight());
                float k = Math.min((float) w / bw, (float) h / bh);
                int dw = Math.round(bw * k), dh = Math.round(bh * k);
                b.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                b.draw(c);
            } else if (data.icon != null) {
                fill.setColor(SURFACE2);
                c.drawRect(0, 0, w, h, fill);
                int s = Math.round(h * 0.44f);
                data.icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
                data.icon.draw(c);
            }
            c.restore();
            if (f > 0.01f) {
                ring.setAlpha(Math.round(255 * f));
                float in = ring.getStrokeWidth() / 2f;
                c.drawRoundRect(in, in, w - in, h - in, radius - in, radius - in, ring);
            }
        }

        private void centerCrop(int bw, int bh, int w, int h) {
            float k = Math.max((float) w / bw, (float) h / bh);
            int cw = Math.round(w / k), ch = Math.round(h / k);
            int x = (bw - cw) / 2, y = (bh - ch) / 2;
            src.set(x, y, x + cw, y + ch);
            dst.set(0, 0, w, h);
        }

        void release() {
            if (anim != null) anim.cancel();
            data.banner = null;                   // drawables go with the screen (PLAN C21)
            data.icon = null;
        }
    }
}
