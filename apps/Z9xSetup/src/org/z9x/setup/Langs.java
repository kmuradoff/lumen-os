package org.z9x.setup;

import java.util.Locale;

/** The 17 UI languages of the Lumen apps, with native names (SPEC 4.1). Not translated on purpose. */
public final class Langs {
    private Langs() {}

    /** Language tags passed to LocalePicker.updateLocales. */
    public static final String[] TAGS = {
            "en-US", "ru-RU", "uk-UA", "be-BY", "kk-KZ", "de-DE", "fr-FR", "es-ES", "it-IT",
            "pt-BR", "pl-PL", "tr-TR", "zh-CN", "zh-TW", "ja-JP", "ko-KR", "ar-EG",
    };
    public static final String[] NAMES = {
            "English", "Русский", "Українська", "Беларуская", "Қазақ тілі", "Deutsch", "Français",
            "Español", "Italiano", "Português (Brasil)", "Polski", "Türkçe", "简体中文", "繁體中文",
            "日本語", "한국어", "العربية",
    };

    /** Index of the row that matches the locale best (exact region, else language), or -1. */
    public static int indexOf(Locale l) {
        if (l == null) return -1;
        String lang = l.getLanguage();
        String region = l.getCountry();
        if ("zh".equals(lang)) {
            String script = l.getScript();
            boolean trad = "Hant".equals(script) || "TW".equals(region) || "HK".equals(region) || "MO".equals(region);
            return trad ? 13 : 12;
        }
        int langOnly = -1;
        for (int i = 0; i < TAGS.length; i++) {
            Locale t = Locale.forLanguageTag(TAGS[i]);
            if (!t.getLanguage().equals(lang)) continue;
            if (t.getCountry().equals(region)) return i;
            if (langOnly < 0) langOnly = i;
        }
        return langOnly;
    }

    /** Native display name of any locale (for the "More languages" list and the summary). */
    public static String nativeName(Locale l) {
        int i = indexOf(l);
        if (i >= 0 && Locale.forLanguageTag(TAGS[i]).getCountry().equals(l.getCountry())) return NAMES[i];
        String n = l.getDisplayName(l);
        if (n.isEmpty()) return l.toLanguageTag();
        return n.substring(0, 1).toUpperCase(l) + n.substring(1);
    }
}
