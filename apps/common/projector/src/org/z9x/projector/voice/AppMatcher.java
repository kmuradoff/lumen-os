package org.z9x.projector.voice;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the installed app a spoken name means ("ютуб", "кинопоиск", "вк видео", "open netflix"). Pure Java: the
 * caller passes the launchable apps (Assistant lists them). Known names first (ALIASES, by package), then the
 * app labels, compared in Latin letters (Cyrillic speech is transliterated) with a small edit distance.
 */
public final class AppMatcher {
    /** One launchable app: its package and label. */
    public static final class App {
        public final String pkg;
        public final String label;

        public App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label == null ? "" : label;
        }
    }

    /** Spoken names (normalised) -> packages, the first installed one wins. */
    private static final Map<String, String[]> ALIASES = new HashMap<>();

    private static void alias(String pkgs, String... names) {
        String[] p = pkgs.split(",");
        for (String n : names) ALIASES.put(n, p);
    }

    static {
        alias("com.google.android.youtube.tv,com.google.android.youtube.tvkids,com.teamsmart.videomanager.tv",
                "ютуб", "ютьюб", "ютюб", "youtube", "you tube", "ютубчик");
        alias("ru.kinopoisk.tv,ru.kinopoisk", "кинопоиск", "кино поиск", "kinopoisk", "яндекс кинопоиск");
        alias("com.vk.tv,com.vk.vkvideo", "вк видео", "вк", "vk видео", "vk video", "вконтакте", "вконтакте видео", "вк видос");
        alias("ru.rutube.app.tv,ru.rutube.app", "рутуб", "рутюб", "rutube", "ру туб");
        alias("top.rootu.lampa", "лампа", "lampa", "лампу");
        alias("com.xgimiru.player", "кино", "люмен плей", "lumen play", "люмен плеер", "плеер", "торренты", "iptv", "тв каналы", "телевизор");
        alias("ru.ivi.client", "иви", "ivi");
        alias("ru.more.play,ru.okko.tv", "окко", "okko");
        alias("ru.start.androidmobile,ru.start.tv", "старт", "start");
        alias("com.netflix.ninja,com.netflix.mediaclient", "нетфликс", "netflix");
        alias("com.spotify.tv.android,com.spotify.music", "спотифай", "spotify");
        alias("com.yandex.music,ru.yandex.music", "яндекс музыка", "yandex music");
        alias("ru.vk.store.tv,ru.vk.store", "рустор", "ру стор", "rustore");
        alias("com.android.vending", "гугл плей", "плей маркет", "плей стор", "google play", "play store");
        alias("com.android.tv.settings", "настройки андроид", "настройки системы", "android settings", "system settings");
        alias("org.videolan.vlc", "влц", "вэлси", "vlc", "влс");
        alias("org.z9x.airplay", "эйрплей", "airplay", "air play");
        alias("com.google.android.apps.tv.launcherx,org.z9x.home", "лаунчер", "launcher");
    }

    private AppMatcher() {}

    /** The best match for {@code spoken}, or null when nothing is close enough. */
    public static App match(String spoken, List<App> apps) {
        String s = Commands.normalize(spoken).replaceAll("^(приложение|app|the app|игру|игра) ", "");
        if (s.isEmpty() || apps == null || apps.isEmpty()) return null;
        String[] pkgs = ALIASES.get(s);
        if (pkgs != null) {
            for (String p : pkgs) for (App a : apps) if (a.pkg.equals(p)) return a;
        }
        String ls = latin(s);
        App best = null;
        int bestScore = Integer.MAX_VALUE;
        for (App a : apps) {
            String la = latin(Commands.normalize(a.label));
            if (la.isEmpty()) continue;
            if (la.equals(ls)) return a;
            int d;
            if (la.startsWith(ls) || ls.startsWith(la)) d = Math.abs(la.length() - ls.length()) / 3;
            else if (la.contains(ls) && ls.length() >= 4) d = 1 + (la.length() - ls.length()) / 4;
            else d = distance(la, ls);
            int allowed = Math.max(1, Math.min(la.length(), ls.length()) / 4);
            if (d <= allowed && d < bestScore) {
                best = a;
                bestScore = d;
            }
        }
        return best;
    }

    private static final String CYR = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final String[] LAT = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};

    /** Latin letters and digits only, Cyrillic transliterated, and a few spellings folded (you->yu, ph->f, w->v). */
    static String latin(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            int i = CYR.indexOf(c);
            if (i >= 0) b.append(LAT[i]);
            else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString().replace("you", "yu").replace("ph", "f").replace("w", "v").replace("ck", "k")
                .replace("x", "ks").replace("q", "k").replace("ee", "i").replace("oo", "u").replace("y", "i")
                .replace("c", "k");
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
