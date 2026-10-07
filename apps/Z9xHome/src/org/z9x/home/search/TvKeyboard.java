package org.z9x.home.search;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.LocaleList;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import org.z9x.home.R;
import org.z9x.home.ui.PageForYou;
import org.z9x.home.ui.Theme;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lumen Home's own on-screen keyboard for search (review 2026-10-07). The only IME of the image is AOSP
 * LeanbackIME (one en_US subtype, Latin layouts only), and the planned wipe removes any keyboard the
 * user had: without this, search could not be typed in Russian, Ukrainian, Belarusian or Kazakh.
 * {@link #nativeLayout} decides: Cyrillic UI language AND no enabled IME subtype for that language
 * -> this keyboard (native layout + Latin + digits); otherwise the system IME as before. For other
 * non-Latin languages (zh, ja, ko, ar) {@link #needsKeyboardApp} lets the search screen offer a
 * keyboard app from Google Play instead.
 *
 * Drawn on one Canvas (no child views, nothing allocated in onDraw); D-pad driven by {@link #onKey}
 * while visible (modal like an IME: BACK hides it). Lower case only (search is case-insensitive).
 */
final class TvKeyboard extends View {
    interface Listener {
        void onKbText(String s);

        void onKbBackspace();

        void onKbAction();
    }

    private static final int T_TEXT = 0, T_SPACE = 1, T_BKSP = 2, T_LANG = 3, T_SYM = 4, T_ACTION = 5;
    private static final int P_NATIVE = 0, P_LATIN = 1, P_SYM = 2;

    /** Letter rows per Cyrillic language (standard ЙЦУКЕН layouts of each language). */
    private static String[] rowsFor(String lang) {
        switch (lang) {
            case "uk": return new String[]{"йцукенгшщзхї", "фівапролджє", "ячсмитьбюґ'"};
            case "be": return new String[]{"йцукенгшўзх'", "фывапролджэ", "ячсмітьбюё"};
            case "kk": return new String[]{"әіңғүұқөһ", "йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё"};
            default: return new String[]{"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбюё"};
        }
    }

    private static final String[] LATIN = {"qwertyuiop", "asdfghjkl", "zxcvbnm-'"};
    private static final String[] SYM = {"1234567890", "-_.,:;!?@#", "&()/+*%\"~"};
    private static final String[] CYRILLIC = {"ru", "uk", "be", "kk"};

    private static final class Key {
        final int type;
        final String text;
        final float weight;
        final RectF r = new RectF();

        Key(int type, String text, float weight) {
            this.type = type;
            this.text = text;
            this.weight = weight;
        }
    }

    private final Listener mL;
    private final String mNativeLabel;
    private final List<List<Key>> mNative, mLatin, mSym;
    private List<List<Key>> mRows;
    private int mPage = P_NATIVE;
    private int mRow, mCol;
    private final Paint mKeyP = new Paint(Paint.ANTI_ALIAS_FLAG), mTextP = new Paint(Paint.ANTI_ALIAS_FLAG),
            mBgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mIcon = new Path();
    private final String mActionLabel;
    private int mLaidW = -1, mLaidH = -1;

    TvKeyboard(Context c, String lang, Listener l) {
        super(c);
        mL = l;
        String[] native0 = rowsFor(lang);
        mNativeLabel = "\u0410\u0411\u0412";              // "АБВ": every layout here is Cyrillic
        mNative = build(native0);
        mLatin = build(LATIN);
        mSym = build(SYM);
        mRows = mNative;
        mActionLabel = c.getString(R.string.search);
        mBgP.setColor(Theme.SURFACE);
        mTextP.setTypeface(Theme.REGULAR);
        mTextP.setTextAlign(Paint.Align.CENTER);
        setFocusable(false);
        setContentDescription(c.getString(R.string.kb_title));
    }

    /** The Cyrillic UI language this keyboard should serve, or null (the system IME is fine). */
    static String nativeLayout(Context c) {
        String lang = uiLanguage(c);
        for (String x : CYRILLIC) if (x.equals(lang)) return imeSupports(c, lang) ? null : lang;
        return null;
    }

    /** Non-Latin UI language (zh, ja, ko, ar, or Cyrillic) without any IME that types it. */
    static boolean needsKeyboardApp(Context c) {
        String lang = uiLanguage(c);
        switch (lang) {
            case "ru": case "uk": case "be": case "kk": case "zh": case "ja": case "ko": case "ar":
                return !imeSupports(c, lang);
            default:
                return false;
        }
    }

    static String uiLanguage(Context c) {
        LocaleList ll = c.getResources().getConfiguration().getLocales();
        return ll.isEmpty() ? "en" : ll.get(0).getLanguage();
    }

    /**
     * Any enabled IME with a subtype for lang? An IME that declares no subtype at all (some third-party
     * keyboards) is trusted, except AOSP LeanbackIME (en_US only, Latin layouts).
     */
    private static boolean imeSupports(Context c, String lang) {
        try {
            InputMethodManager im = c.getSystemService(InputMethodManager.class);
            if (im == null) return false;
            for (InputMethodInfo imi : im.getEnabledInputMethodList()) {
                List<InputMethodSubtype> subs = im.getEnabledInputMethodSubtypeList(imi, true);
                if (subs == null || subs.isEmpty()) {
                    if (imi.getSubtypeCount() == 0 && !imi.getPackageName().startsWith("com.google.leanback.ime")) return true;
                    continue;
                }
                for (InputMethodSubtype s : subs) {
                    String t = s.getLanguageTag();
                    if (t == null || t.isEmpty()) t = s.getLocale();
                    if (t != null && Locale.forLanguageTag(t.replace('_', '-')).getLanguage().equals(lang)) return true;
                }
            }
        } catch (Throwable ignored) {
            // unknown: offer our keyboard / the hint rather than a keyboard that cannot type the language
        }
        return false;
    }

    private List<List<Key>> build(String[] letters) {
        List<List<Key>> rows = new ArrayList<>();
        for (int i = 0; i < letters.length; i++) {
            List<Key> r = new ArrayList<>();
            int n = letters[i].codePointCount(0, letters[i].length());
            for (int j = 0; j < n; j++) {
                int a = letters[i].offsetByCodePoints(0, j), b = letters[i].offsetByCodePoints(0, j + 1);
                r.add(new Key(T_TEXT, letters[i].substring(a, b), 1f));
            }
            if (i == letters.length - 1) r.add(new Key(T_BKSP, null, 1.6f));
            rows.add(r);
        }
        List<Key> bottom = new ArrayList<>();
        bottom.add(new Key(T_LANG, null, 1.6f));
        bottom.add(new Key(T_SYM, null, 1.6f));
        bottom.add(new Key(T_SPACE, " ", 5f));
        bottom.add(new Key(T_ACTION, null, 2.6f));
        rows.add(bottom);
        return rows;
    }

    /** Design height for the current layout. */
    int designHeight() {
        return 32 + mRows.size() * (KEY_H + GAP) - GAP + 32;
    }

    private static final int KEY_H = 76, GAP = 12, MAX_KEY_W = 112;

    void reset() {
        setPage(P_NATIVE);
    }

    private void setPage(int p) {
        mPage = p;
        mRows = p == P_NATIVE ? mNative : p == P_LATIN ? mLatin : mSym;
        mRow = Math.min(mRow, mRows.size() - 1);
        mCol = Math.min(mCol, mRows.get(mRow).size() - 1);
        mLaidW = -1;
        requestLayout();
        invalidate();
    }

    private void layoutKeys(int w) {
        float gap = Theme.pxf(GAP), kh = Theme.pxf(KEY_H);
        float maxW = 0;
        for (List<Key> r : mRows) {
            float s = 0;
            for (Key k : r) s += k.weight;
            maxW = Math.max(maxW, s);
        }
        float avail = w - 2 * Theme.pxf(Theme.MARGIN);
        float unit = Math.min(Theme.pxf(MAX_KEY_W + GAP), avail / maxW);
        float y = Theme.pxf(32);
        for (List<Key> r : mRows) {
            float s = 0;
            for (Key k : r) s += k.weight;
            float x = (w - s * unit) / 2f;
            for (Key k : r) {
                k.r.set(x + gap / 2f, y, x + k.weight * unit - gap / 2f, y + kh);
                x += k.weight * unit;
            }
            y += kh + gap;
        }
        mLaidW = w;
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        setMeasuredDimension(MeasureSpec.getSize(wms), Theme.px(designHeight()));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (changed || mLaidW != r - l || mLaidH != b - t) {
            mLaidH = b - t;
            layoutKeys(r - l);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (mLaidW != getWidth()) layoutKeys(getWidth());
        float rad = Theme.pxf(Theme.RADIUS);
        c.drawRoundRect(0, 0, getWidth(), getHeight() + rad, rad * 2, rad * 2, mBgP);
        mTextP.setTextSize(Theme.pxf(34));
        for (int i = 0; i < mRows.size(); i++) {
            List<Key> row = mRows.get(i);
            for (int j = 0; j < row.size(); j++) {
                Key k = row.get(j);
                boolean f = i == mRow && j == mCol;
                int fill = f ? Theme.FOCUS : (k.type == T_ACTION ? Theme.ACCENT : (k.type == T_TEXT ? Theme.SURFACE2 : Theme.SURFACE3));
                int ink = f || k.type == T_ACTION ? Theme.ON_FOCUS : Theme.TEXT1;
                mKeyP.setColor(fill);
                if (f) {
                    float g = Theme.pxf(3);
                    c.drawRoundRect(k.r.left - g, k.r.top - g, k.r.right + g, k.r.bottom + g, rad, rad, mKeyP);
                } else {
                    c.drawRoundRect(k.r, rad, rad, mKeyP);
                }
                mTextP.setColor(ink);
                drawLabel(c, k);
            }
        }
    }

    private void drawLabel(Canvas c, Key k) {
        float cx = k.r.centerX(), cy = k.r.centerY();
        float base = cy - (mTextP.descent() + mTextP.ascent()) / 2f;
        switch (k.type) {
            case T_TEXT:
                c.drawText(k.text, cx, base, mTextP);
                break;
            case T_LANG:
                mTextP.setTextSize(Theme.pxf(26));
                c.drawText(mPage == P_NATIVE ? "ABC" : mNativeLabel, cx, cy - (mTextP.descent() + mTextP.ascent()) / 2f, mTextP);
                mTextP.setTextSize(Theme.pxf(34));
                break;
            case T_SYM:
                mTextP.setTextSize(Theme.pxf(26));
                c.drawText(mPage == P_SYM ? mNativeLabel : "?123", cx, cy - (mTextP.descent() + mTextP.ascent()) / 2f, mTextP);
                mTextP.setTextSize(Theme.pxf(34));
                break;
            case T_ACTION:
                mTextP.setTextSize(Theme.pxf(28));
                c.drawText(mActionLabel, cx, cy - (mTextP.descent() + mTextP.ascent()) / 2f, mTextP);
                mTextP.setTextSize(Theme.pxf(34));
                break;
            case T_SPACE: {
                float hw = Math.min(k.r.width() * 0.3f, Theme.pxf(120)), y = cy + Theme.pxf(8);
                mTextP.setStrokeWidth(Theme.pxf(3));
                c.drawLine(cx - hw, y, cx + hw, y, mTextP);
                c.drawLine(cx - hw, y, cx - hw, y - Theme.pxf(10), mTextP);
                c.drawLine(cx + hw, y, cx + hw, y - Theme.pxf(10), mTextP);
                break;
            }
            case T_BKSP: {
                float s = Theme.pxf(16);
                mIcon.reset();
                mIcon.moveTo(cx - 1.6f * s, cy);
                mIcon.lineTo(cx - 0.7f * s, cy - s);
                mIcon.lineTo(cx + 1.5f * s, cy - s);
                mIcon.lineTo(cx + 1.5f * s, cy + s);
                mIcon.lineTo(cx - 0.7f * s, cy + s);
                mIcon.close();
                Paint.Style st = mTextP.getStyle();
                mTextP.setStyle(Paint.Style.STROKE);
                mTextP.setStrokeWidth(Theme.pxf(3));
                c.drawPath(mIcon, mTextP);
                c.drawLine(cx - 0.1f * s, cy - 0.45f * s, cx + 0.8f * s, cy + 0.45f * s, mTextP);
                c.drawLine(cx - 0.1f * s, cy + 0.45f * s, cx + 0.8f * s, cy - 0.45f * s, mTextP);
                mTextP.setStyle(st);
                break;
            }
            default:
                break;
        }
    }

    /** D-pad / OK while visible (ACTION_DOWN, repeats included). Returns true when consumed. */
    boolean onKey(int code, KeyEvent e) {
        boolean rtl = Theme.rtl(this);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                move(rtl ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                move(rtl ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                vertical(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                vertical(1);
                return true;
            case KeyEvent.KEYCODE_DEL:
                mL.onKbBackspace();
                return true;
            default:
                if (!PageForYou.isOk(code)) return false;
                Key k = mRows.get(mRow).get(mCol);
                if (e.getRepeatCount() > 0 && k.type != T_BKSP && k.type != T_TEXT) return true;
                press(k);
                return true;
        }
    }

    private void press(Key k) {
        switch (k.type) {
            case T_TEXT: case T_SPACE: mL.onKbText(k.text); break;
            case T_BKSP: mL.onKbBackspace(); break;
            case T_LANG: setPage(mPage == P_NATIVE ? P_LATIN : P_NATIVE); break;
            case T_SYM: setPage(mPage == P_SYM ? P_NATIVE : P_SYM); break;
            case T_ACTION: mL.onKbAction(); break;
            default: break;
        }
        invalidate();
    }

    private void move(int d) {
        int n = mRows.get(mRow).size();
        mCol = Math.max(0, Math.min(n - 1, mCol + d));
        invalidate();
    }

    private void vertical(int d) {
        int r = mRow + d;
        if (r < 0 || r >= mRows.size()) return;
        float x = mRows.get(mRow).get(mCol).r.centerX();
        List<Key> row = mRows.get(r);
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int j = 0; j < row.size(); j++) {
            float dd = Math.abs(row.get(j).r.centerX() - x);
            if (dd < bd) {
                bd = dd;
                best = j;
            }
        }
        mRow = r;
        mCol = best;
        invalidate();
    }
}
