package org.z9x.setup.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Text field for passwords, SSIDs and names: surface fill, accent ring when focused, system IME. */
public class Field extends EditText {
    public Field(Context c, CharSequence hint, boolean password) {
        super(c);
        setHint(hint);
        setSingleLine(true);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.pxf(30));
        setTypeface(Ui.regular());
        setTextColor(Ui.TEXT);
        setHintTextColor(0x80FFFFFF);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        setPadding(Ui.px(28), 0, Ui.px(28), 0);
        setMinHeight(Ui.px(84));
        setHighlightColor(0x663B78E7);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable f = (android.graphics.drawable.GradientDrawable) Ui.roundRect(Ui.SURFACE2, 24);
        f.setStroke(Ui.px(3), Ui.ACCENT);
        bg.addState(Ui.FOCUSED, f);
        bg.addState(Ui.EMPTY, Ui.roundRect(Ui.SURFACE2, 24));
        bg.setEnterFadeDuration(120);
        bg.setExitFadeDuration(120);
        setBackground(bg);
        setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        setPassword(password, false);
    }

    public void setPassword(boolean password, boolean visible) {
        int sel = getSelectionEnd();
        if (password) {
            setInputType(InputType.TYPE_CLASS_TEXT | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        } else {
            setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        setTypeface(Ui.regular());
        if (sel >= 0 && sel <= length()) setSelection(sel);
    }

    public void showIme() {
        requestFocus();
        post(() -> {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    public void hideIme() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }
}
