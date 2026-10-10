package org.z9x.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-side panel with a title and a focusable list (context menu, weather popover, Customize pages).
 * Manual focus, white focus pill, toggles, checks, reorder mode (OK picks an item up, UP/DOWN moves
 * it, OK/BACK drops it), optional text editor row at the top (city search, rename). TV safe area (1.0.1
 * follow-up): on the screen-edge side the content keeps 64 px (the focus pill 56 px) off the edge, and
 * a focused item never scrolls closer than 64 px to the bottom.
 */
public class ListPanel extends ViewGroup {
    public static final int ACTION = 0, TOGGLE = 1, CHECK = 2, INFO = 3, REORDER = 4, HEADER = 5;

    public static final class Item {
        public int kind = ACTION;
        public int icon;
        public Drawable drawable;         // a ready icon in its own colours (an APK's icon), instead of icon
        public CharSequence text = "";
        public CharSequence value = "";
        public boolean on;
        public boolean rawIcon;           // keep the icon's own colours (weather)
        public boolean enabled = true;
        public Runnable action;
        public Object tag;

        public static Item action(int icon, CharSequence text, Runnable r) {
            Item i = new Item();
            i.icon = icon;
            i.text = text;
            i.action = r;
            return i;
        }

        public Item value(CharSequence v) {
            value = v == null ? "" : v;
            return this;
        }

        public Item kind(int k) {
            kind = k;
            return this;
        }

        public Item on(boolean b) {
            on = b;
            return this;
        }

        public Item raw() {
            rawIcon = true;
            return this;
        }

        public static Item info(int icon, CharSequence text) {
            Item i = new Item();
            i.kind = INFO;
            i.icon = icon;
            i.text = text;
            return i;
        }
    }

    public interface MoveListener {
        void onMoved(List<Item> items);
    }

    private final TextView mTitle, mSub;
    private EditText mEditor;
    private final ViewGroup mList;
    private final ArrayList<ItemView> mViews = new ArrayList<>();
    private final ArrayList<Item> mItems = new ArrayList<>();
    private int mFocus = -1;          // -1 = editor (if any)
    private int mPicked = -1;
    private MoveListener mMove;
    private final int mPad, mPadEdge;

    public ListPanel(Context c) {
        super(c);
        mPad = Theme.px(40);
        mPadEdge = Theme.px(Theme.SAFE + 10); // the panel sits at the screen's end edge
        setBackgroundColor((Theme.SURFACE & 0x00FFFFFF) | 0xFA000000);
        mTitle = new TextView(c);
        Theme.text(mTitle, 40, Theme.DISPLAY, Theme.TEXT1);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        addView(mTitle);
        mSub = new TextView(c);
        Theme.text(mSub, 24, Theme.REGULAR, Theme.TEXT2);
        // up to 6 lines: a setting's explanation (cz_continue_sub: 4 lines in most languages in the safe
        // area's 536 px, 6 at font scale 1.3) must not lose its last sentence
        mSub.setMaxLines(6);
        mSub.setEllipsize(TextUtils.TruncateAt.END);
        addView(mSub);
        mList = new ViewGroup(c) {
            @Override
            protected void onMeasure(int wms, int hms) {
                int w = MeasureSpec.getSize(wms);
                int h = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                    h += v.getMeasuredHeight() + Theme.px(6);
                }
                setMeasuredDimension(w, h);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int y = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    v.layout(0, y, r - l, y + v.getMeasuredHeight());
                    y += v.getMeasuredHeight() + Theme.px(6);
                }
            }
        };
        mList.setClipChildren(false);
        addView(mList);
        setClipChildren(true);
    }

    public void setHeader(CharSequence title, CharSequence sub) {
        mTitle.setText(title);
        mSub.setText(sub == null ? "" : sub);
        mSub.setVisibility(sub == null || sub.length() == 0 ? GONE : VISIBLE);
    }

    /** Adds (or removes, null) a single-line text editor above the list. */
    public void setEditor(EditText e) {
        if (mEditor != null) removeView(mEditor);
        mEditor = e;
        if (e != null) addView(e);
    }

    public EditText editor() {
        return mEditor;
    }

    public void setMoveListener(MoveListener l) {
        mMove = l;
    }

    public void setItems(List<Item> items, int focus) {
        mItems.clear();
        mItems.addAll(items);
        mList.removeAllViews();
        mViews.clear();
        for (Item it : mItems) {
            ItemView v = new ItemView(getContext(), it);
            mViews.add(v);
            mList.addView(v);
        }
        mPicked = -1;
        mFocus = -2;
        int f = focus;
        if (f < 0 && mEditor != null) f = -1;
        else f = firstFocusable(Math.max(0, focus), 1);
        setFocus(f);
        requestLayout();
    }

    public List<Item> items() {
        return mItems;
    }

    public void refresh(Item it) {
        int i = mItems.indexOf(it);
        if (i >= 0) mViews.get(i).invalidate();
    }

    public int focus() {
        return mFocus;
    }

    public boolean editorFocused() {
        return mFocus == -1 && mEditor != null;
    }

    private int firstFocusable(int from, int dir) {
        for (int i = from; i >= 0 && i < mItems.size(); i += dir) {
            Item it = mItems.get(i);
            if (it.kind != INFO && it.kind != HEADER && it.enabled) return i;
        }
        return mEditor != null ? -1 : (mItems.isEmpty() ? -1 : Math.max(0, Math.min(from, mItems.size() - 1)));
    }

    private void setFocus(int f) {
        if (mFocus >= 0 && mFocus < mViews.size()) mViews.get(mFocus).setFocused2(false);
        mFocus = f;
        if (mEditor != null) {
            if (f == -1) {
                mEditor.requestFocus();
            } else if (mEditor.hasFocus()) {
                mEditor.clearFocus();
                requestFocusRoot();
            }
        }
        if (f >= 0 && f < mViews.size()) {
            mViews.get(f).setFocused2(true);
            ensureVisible(f);
        }
    }

    private void requestFocusRoot() {
        View root = getRootView();
        root.setFocusableInTouchMode(true);
        root.requestFocus();
    }

    private void ensureVisible(int i) {
        if (getHeight() == 0) {
            post(() -> ensureVisible(i));
            return;
        }
        View v = mViews.get(i);
        int top = mList.getTop() + v.getTop() + (int) mList.getTranslationY();
        int bottom = top + v.getHeight();
        int minY = mList.getTop(), maxY = getHeight() - mPadEdge;
        float ty = mList.getTranslationY();
        if (bottom > maxY) ty -= bottom - maxY;
        else if (top < minY) ty += minY - top;
        if (ty != mList.getTranslationY()) mList.animate().translationY(ty).setDuration(160).setInterpolator(Theme.EMPHASIZED).start();
    }

    /** Returns true if the key was used. BACK is left to the owner unless an item is picked up. */
    public boolean onKey(int keyCode, KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                if (mPicked >= 0) {
                    movePicked(-1);
                    return true;
                }
                if (mFocus == -1) return true;
                int to = mFocus - 1;
                while (to >= 0 && (mItems.get(to).kind == INFO || mItems.get(to).kind == HEADER || !mItems.get(to).enabled)) to--;
                if (to >= 0) setFocus(to);
                else if (mEditor != null) setFocus(-1);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (mPicked >= 0) {
                    movePicked(1);
                    return true;
                }
                int to = mFocus + 1;
                while (to < mItems.size() && (mItems.get(to).kind == INFO || mItems.get(to).kind == HEADER || !mItems.get(to).enabled)) to++;
                if (to < mItems.size()) setFocus(to);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return mFocus >= 0; // in the editor they move the cursor
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: {
                if (mFocus < 0 || mFocus >= mItems.size()) return false; // editor: the EditText shows the IME
                if (e.getRepeatCount() > 0) return true; // a held OK (the long press that opened us) never clicks
                Item it = mItems.get(mFocus);
                if (it.kind == REORDER) {
                    if (mPicked >= 0) drop();
                    else {
                        mPicked = mFocus;
                        mViews.get(mFocus).setPicked(true);
                    }
                    return true;
                }
                if (it.kind == TOGGLE) {
                    it.on = !it.on;
                    mViews.get(mFocus).invalidate();
                }
                if (it.action != null) it.action.run();
                return true;
            }
            case KeyEvent.KEYCODE_BACK:
                if (mPicked >= 0) {
                    drop();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void movePicked(int dir) {
        int to = mPicked + dir;
        if (to < 0 || to >= mItems.size() || mItems.get(to).kind != REORDER) return;
        java.util.Collections.swap(mItems, mPicked, to);
        ItemView a = mViews.get(mPicked), b = mViews.get(to);
        a.bind(mItems.get(mPicked));
        b.bind(mItems.get(to));
        a.setPicked(false);
        a.setFocused2(false);
        mPicked = to;
        mFocus = to;
        b.setFocused2(true);
        b.setPicked(true);
        ensureVisible(to);
    }

    private void drop() {
        if (mPicked >= 0 && mPicked < mViews.size()) mViews.get(mPicked).setPicked(false);
        mPicked = -1;
        if (mMove != null) mMove.onMoved(mItems);
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
        int cw = w - mPad - mPadEdge;
        int un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        mTitle.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), un);
        mSub.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), un);
        if (mEditor != null) mEditor.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Theme.px(76), MeasureSpec.EXACTLY));
        mList.measure(MeasureSpec.makeMeasureSpec(cw + Theme.px(16), MeasureSpec.EXACTLY), un);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = Theme.rtl(this) ? mPadEdge : mPad;
        int y = Theme.px(72);
        mTitle.layout(x, y, x + mTitle.getMeasuredWidth(), y + mTitle.getMeasuredHeight());
        y += mTitle.getMeasuredHeight() + Theme.px(8);
        if (mSub.getVisibility() == VISIBLE) {
            mSub.layout(x, y, x + mSub.getMeasuredWidth(), y + mSub.getMeasuredHeight());
            y += mSub.getMeasuredHeight();
        }
        y += Theme.px(28);
        if (mEditor != null) {
            mEditor.layout(x, y, x + mEditor.getMeasuredWidth(), y + mEditor.getMeasuredHeight());
            y += mEditor.getMeasuredHeight() + Theme.px(20);
        }
        int lx = x - Theme.px(8);
        mList.layout(lx, y, lx + mList.getMeasuredWidth(), y + mList.getMeasuredHeight());
    }

    // ------------------------------------------------------------------ item view

    static final class ItemView extends View {
        private Item mIt;
        private boolean mFocused, mPicked;
        private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final TextPaint mValue = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mR = new RectF();
        private Drawable mIcon, mIconF, mCheck, mCheckF, mMove, mMoveF;
        private StaticLayout mL1;

        ItemView(Context c, Item it) {
            super(c);
            float fs = Theme.fontScale(c);
            mText.setTextSize(Theme.pxf(28) * fs);
            mValue.setTextSize(Theme.pxf(22) * fs);
            mValue.setTypeface(Theme.REGULAR);
            mCheck = Theme.icon(c, org.z9x.home.R.drawable.ic_check, Theme.ACCENT);
            mCheckF = Theme.icon(c, org.z9x.home.R.drawable.ic_check, Theme.ON_FOCUS);
            mMove = Theme.icon(c, org.z9x.home.R.drawable.ic_reorder, Theme.TEXT2);
            mMoveF = Theme.icon(c, org.z9x.home.R.drawable.ic_reorder, Theme.ON_FOCUS);
            bind(it);
        }

        void bind(Item it) {
            mIt = it;
            if (it.drawable != null) {
                mIcon = it.drawable;
                mIconF = it.drawable;
            } else {
                mIcon = it.icon != 0 ? Theme.icon(getContext(), it.icon, it.rawIcon ? 0 : Theme.TEXT1) : null;
                mIconF = it.icon != 0 ? Theme.icon(getContext(), it.icon, it.rawIcon ? 0 : Theme.ON_FOCUS) : null;
            }
            mText.setTypeface(it.kind == HEADER ? Theme.MEDIUM : (it.kind == INFO ? Theme.REGULAR : Theme.MEDIUM));
            mL1 = null;
            setContentDescription(it.text + (it.value.length() > 0 ? ", " + it.value : ""));
            requestLayout();
            invalidate();
        }

        void setFocused2(boolean f) {
            mFocused = f;
            invalidate();
        }

        void setPicked(boolean p) {
            mPicked = p;
            animate().scaleX(p ? 1.03f : 1f).scaleY(p ? 1.03f : 1f).setDuration(120).start();
            invalidate();
        }

        private int textWidth(int w) {
            int left = Theme.px(mIt.icon != 0 || mIt.drawable != null ? 84 : 28);
            int right = Theme.px(mIt.kind == TOGGLE ? 110 : (mIt.kind == CHECK || mIt.kind == REORDER ? 70 : 28));
            return Math.max(10, w - left - right);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            mText.setTextSize(Theme.pxf(mIt.kind == INFO ? 24 : (mIt.kind == HEADER ? 22 : 28)) * Theme.fontScale(getContext()));
            mL1 = StaticLayout.Builder.obtain(mIt.text, 0, mIt.text.length(), mText, textWidth(w))
                    .setMaxLines(mIt.kind == INFO ? 6 : 2).setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            int h = mL1.getHeight() + (mIt.value.length() > 0 ? Theme.px(30) : 0) + Theme.px(mIt.kind == HEADER ? 22 : 34);
            if (mIt.kind != INFO && mIt.kind != HEADER) h = Math.max(h, Theme.px(84));
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            boolean rtl = Theme.rtl(this);
            if (mFocused || mPicked) {
                mR.set(0, 0, w, h);
                mFill.setColor(mPicked ? Theme.ACCENT : Theme.FOCUS);
                c.drawRoundRect(mR, Theme.pxf(24), Theme.pxf(24), mFill);
            }
            boolean f = mFocused || mPicked;
            int tc = f ? Theme.ON_FOCUS : (mIt.kind == INFO || mIt.kind == HEADER ? Theme.TEXT2 : (mIt.enabled ? Theme.TEXT1 : Theme.TEXT3));
            Drawable ic = f ? mIconF : mIcon;
            int left = Theme.px(28);
            if (ic != null) {
                int s = Theme.px(36);
                int iy = (h - s) / 2;
                int ix = rtl ? w - left - s : left;
                ic.setBounds(ix, iy, ix + s, iy + s);
                ic.draw(c);
                left = Theme.px(84);
            }
            mText.setColor(tc);
            int textH = mL1.getHeight() + (mIt.value.length() > 0 ? Theme.px(30) : 0);
            float ty = (h - textH) / 2f;
            c.save();
            c.translate(rtl ? w - left - mL1.getWidth() : left, ty);
            mL1.draw(c);
            c.restore();
            if (mIt.value.length() > 0) {
                mValue.setColor(f ? (Theme.ON_FOCUS & 0x00FFFFFF) | 0xB0000000 : Theme.TEXT2);
                CharSequence v = TextUtils.ellipsize(mIt.value, mValue, textWidth(w), TextUtils.TruncateAt.END);
                float vx = rtl ? w - left - mValue.measureText(v, 0, v.length()) : left;
                c.drawText(v, 0, v.length(), vx, ty + mL1.getHeight() + Theme.px(26), mValue);
            }
            int rx = rtl ? Theme.px(28) : w - Theme.px(28);
            if (mIt.kind == TOGGLE) drawSwitch(c, rx, h / 2f, f, rtl);
            else if (mIt.kind == CHECK && mIt.on) {
                Drawable d = f ? mCheckF : mCheck;
                int s = Theme.px(36);
                int x = rtl ? rx : rx - s;
                d.setBounds(x, (h - s) / 2, x + s, (h + s) / 2);
                d.draw(c);
            } else if (mIt.kind == REORDER) {
                Drawable d = f ? mMoveF : mMove;
                int s = Theme.px(32);
                int x = rtl ? rx : rx - s;
                d.setBounds(x, (h - s) / 2, x + s, (h + s) / 2);
                d.draw(c);
            }
        }

        private void drawSwitch(Canvas c, int rx, float cy, boolean focused, boolean rtl) {
            float tw = Theme.pxf(64), th = Theme.pxf(32);
            float x1 = rtl ? rx + tw : rx, x0 = x1 - tw;
            mR.set(x0, cy - th / 2, x1, cy + th / 2);
            int track = mIt.on ? Theme.ACCENT_STRONG : (focused ? 0x33000000 : 0x4DFFFFFF);
            mFill.setColor(track);
            c.drawRoundRect(mR, th / 2, th / 2, mFill);
            float r = th / 2 - Theme.pxf(4);
            boolean right = mIt.on != rtl;
            float cx = right ? x1 - th / 2 : x0 + th / 2;
            mFill.setColor(mIt.on ? 0xFFFFFFFF : (focused ? 0xFF5F6368 : 0xFFDADCE0));
            c.drawCircle(cx, cy, r, mFill);
        }
    }
}
