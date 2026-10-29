package org.lumen.projector.voice;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;

import org.lumen.projector.R;

import java.util.Locale;

/**
 * Projector settings > Voice assistant, the dialogs both projector apps show: the speech language, spoken replies
 * and the optional Gemini key for the voice (Speaker). Main thread.
 */
public final class VoiceSettings {
    /** Speech languages offered (BCP 47); "" = the system language. The recogniser decides what it supports. */
    private static final String[] LANGS = {"", "ru-RU", "en-US", "uk-UA", "kk-KZ", "de-DE", "fr-FR", "es-ES", "it-IT",
            "pt-BR", "pl-PL", "tr-TR", "zh-CN", "ja-JP", "ko-KR", "ar-SA"};

    private VoiceSettings() {}

    public static CharSequence languageValue(Activity a) {
        String l = Assistant.prefs(a).getString(Assistant.K_LANG, "");
        return l == null || l.isEmpty() ? a.getString(R.string.voice_lang_system) : name(l);
    }

    private static String name(String tag) {
        Locale l = Locale.forLanguageTag(tag);
        String n = l.getDisplayLanguage(l);
        return n.isEmpty() ? tag : n.substring(0, 1).toUpperCase(l) + n.substring(1);
    }

    public static void pickLanguage(Activity a, Runnable changed) {
        CharSequence[] items = new CharSequence[LANGS.length];
        String cur = Assistant.prefs(a).getString(Assistant.K_LANG, "");
        int checked = 0;
        for (int i = 0; i < LANGS.length; i++) {
            items[i] = LANGS[i].isEmpty() ? a.getString(R.string.voice_lang_system) : name(LANGS[i]);
            if (LANGS[i].equals(cur)) checked = i;
        }
        new AlertDialog.Builder(a).setTitle(R.string.voice_language)
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    d.dismiss();
                    Assistant.setLanguage(a, LANGS[w]);
                    if (changed != null) changed.run();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    public static CharSequence speakValue(Activity a) {
        return a.getString(Speaker.speakReplies(a) ? R.string.panel_on : R.string.panel_off);
    }

    public static void toggleSpeak(Activity a, Runnable changed) {
        Speaker.setSpeakReplies(a, !Speaker.speakReplies(a));
        if (changed != null) changed.run();
    }

    public static CharSequence keyValue(Activity a) {
        String e = Speaker.lastError();
        if (Speaker.key(a).isEmpty()) return a.getString(R.string.voice_key_off);
        if ("region".equals(e)) return a.getString(R.string.voice_key_region);
        if ("quota".equals(e)) return a.getString(R.string.voice_key_quota);
        if ("bad key".equals(e)) return a.getString(R.string.voice_key_bad);
        return a.getString(R.string.voice_key_on);
    }

    public static void editKey(Activity a, Runnable changed) {
        EditText e = new EditText(a);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        e.setText(Speaker.key(a));
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(a).setTitle(R.string.voice_key).setMessage(R.string.voice_key_hint).setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Speaker.setKey(a, e.getText().toString());
                    if (changed != null) changed.run();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }
}
