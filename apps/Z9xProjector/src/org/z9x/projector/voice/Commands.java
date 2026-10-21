package org.z9x.projector.voice;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lumen assistant (1.0.5, owner 2026-10-21): the commands the projector understands by itself, offline and at once,
 * in Russian and English. Pure Java (no Android calls), tested by tools/voice_test (CommandsTest). Anything else
 * goes to the assistant's brain (Brain, Gemini) when one is set up, else to Lumen Home search.
 */
public final class Commands {
    public enum Kind {
        OPEN_APP, VOLUME_UP, VOLUME_DOWN, VOLUME_SET, MUTE, UNMUTE, PAUSE, PLAY, NEXT, PREVIOUS, HOME,
        SETTINGS, SECTION, AUTOFOCUS, KEYSTONE, TIME, SEARCH, YOUTUBE, UNKNOWN
    }

    /** One parsed command. {@code arg}: app name, search text or quick panel section; {@code n}: steps or percent. */
    public static final class Cmd {
        public final Kind kind;
        public final String arg;
        public final int n;
        /** The whole utterance, normalised (for the brain and for OPEN_APP's fallback to search). */
        public final String text;

        Cmd(Kind kind, String arg, int n, String text) {
            this.kind = kind;
            this.arg = arg;
            this.n = n;
            this.text = text;
        }

        @Override public String toString() {
            return kind + (arg != null ? "(" + arg + ")" : "") + (n != 0 ? "[" + n + "]" : "");
        }
    }

    /** Quick panel sections the commands can open (the same strings as QuickPanel.SECTION_* of both apps). */
    public static final String SEC_SOUND = "sound", SEC_PICTURE = "picture", SEC_LAMP = "lamp", SEC_FOCUS = "focus",
            SEC_KEYSTONE = "keystone", SEC_INPUT = "input";

    private Commands() {}

    /** Lower case, ё -> е, no punctuation, single spaces, without a leading wake word or "please". */
    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        t = t.replaceAll("[^\\p{L}\\p{N}%+ ]", " ").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^(окей |ок |эй |hey |ok )?(люмен|lumen|ассистент|assistant)\\b ?", "");
        t = t.replaceAll("\\b(пожалуйста|please)\\b", "").replaceAll("\\s+", " ").trim();
        return t;
    }

    private static boolean has(String t, String re) {
        return Pattern.compile(re, U).matcher(t).find();
    }

    /** Cyrillic counts as letters for \\b and \\w. */
    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern YT_AT_END = Pattern.compile(
            "^(?:(?:найди|найти|поищи|покажи|включи|поставь|search|find|play|show)(?: мне)? )?(.+?) (?:на|в|on|in) (?:ютубе|ютуб|youtube|you tube)$", U);
    private static final Pattern YT_AT_START = Pattern.compile(
            "^(?:(?:найди|поищи|открой|включи) )?(?:на |в )?(?:ютубе?|youtube|you tube)(?: найди| поищи| покажи| включи| search| play)? (.+)$", U);
    private static final Pattern VOLUME_SET = Pattern.compile(
            "^(?:сделай |поставь |установи |set )?(?:громкость|звук|volume)(?: на| в| to| at)? (\\d{1,3}) ?(?:%|процент\\w*|percent)?$", U);
    private static final Pattern OPEN = Pattern.compile(
            "^(?:открой|открыть|запусти|запустить|зайди в|перейди в|open|launch|start|go to) (.+)$", U);
    private static final Pattern TURN_ON = Pattern.compile("^(?:включи|включить|turn on) (.+)$", U);
    private static final Pattern SEARCH = Pattern.compile(
            "^(?:найди|найти|поищи|поиск|покажи|хочу посмотреть|хочу смотреть|давай посмотрим|посмотреть|search for|search|find|show me|i want to watch|play the movie|play) (.+)$", U);

    public static Cmd parse(String raw) {
        String t = normalize(raw);
        if (t.isEmpty()) return new Cmd(Kind.UNKNOWN, null, 0, t);
        Matcher m;

        // volume
        if ((m = VOLUME_SET.matcher(t)).find()) return new Cmd(Kind.VOLUME_SET, null, Math.min(100, Integer.parseInt(m.group(1))), t);
        if (has(t, "\\b(без звука|выключи звук|отключи звук|заглуши|mute|silence)\\b")) return new Cmd(Kind.MUTE, null, 0, t);
        if (has(t, "^(включи звук|верни звук|unmute|sound on)$")) return new Cmd(Kind.UNMUTE, null, 0, t);
        if (has(t, "(погромче|громче|louder|volume up|turn it up|turn up)")) {
            return new Cmd(Kind.VOLUME_UP, null, has(t, "(намного|гораздо|сильно|much|a lot)") ? 5 : 2, t);
        }
        if (has(t, "(потише|тише|quieter|volume down|turn it down|turn down)")) {
            return new Cmd(Kind.VOLUME_DOWN, null, has(t, "(намного|гораздо|сильно|much|a lot)") ? 5 : 2, t);
        }
        // media
        if (has(t, "^(пауза|поставь на паузу|на паузу|останови|стоп|pause|stop)$")) return new Cmd(Kind.PAUSE, null, 0, t);
        if (has(t, "^(продолжи|продолжить|играй|воспроизведи|сними с паузы|play|resume|continue)$")) return new Cmd(Kind.PLAY, null, 0, t);
        if (has(t, "^(следующ\\w*|дальше|вперед|next)( серия| трек| видео| episode| track| video)?$")) return new Cmd(Kind.NEXT, null, 0, t);
        if (has(t, "^(предыдущ\\w*|назад к предыдущ\\w*|previous)( серия| трек| видео| episode| track| video)?$")) return new Cmd(Kind.PREVIOUS, null, 0, t);
        // navigation
        if (has(t, "^(домой|на главную|главная|главный экран|на главный экран|home|go home|home screen)$")) return new Cmd(Kind.HOME, null, 0, t);
        // projector
        if (has(t, "(автофокус|сфокусируй|наведи резкость|фокус|focus)")) return new Cmd(Kind.AUTOFOCUS, null, 0, t);
        if (has(t, "(трапеци|выровняй (картинку|изображение|экран)|keystone|straighten)")) return new Cmd(Kind.KEYSTONE, null, 0, t);
        if (has(t, "(настройк\\w* звука|sound settings)")) return new Cmd(Kind.SECTION, SEC_SOUND, 0, t);
        if (has(t, "(настройк\\w* (изображения|картинки)|режим изображения|picture settings|picture mode)")) return new Cmd(Kind.SECTION, SEC_PICTURE, 0, t);
        if (has(t, "(яркост\\w*|brightness)")) return new Cmd(Kind.SECTION, SEC_LAMP, 0, t);
        if (has(t, "(источник\\w*|hdmi|input source|inputs)") && !has(t, "^(открой|open) ")) return new Cmd(Kind.SECTION, SEC_INPUT, 0, t);
        if (has(t, "^(настройки|открой настройки|настройки проектора|settings|projector settings|open settings)$")) return new Cmd(Kind.SETTINGS, null, 0, t);
        // time
        if (has(t, "(который час|сколько времени|сколько сейчас времени|what time|what s the time|current time)")) return new Cmd(Kind.TIME, null, 0, t);
        // YouTube search
        if ((m = YT_AT_END.matcher(t)).find()) return new Cmd(Kind.YOUTUBE, m.group(1).trim(), 0, t);
        if ((m = YT_AT_START.matcher(t)).find()) return new Cmd(Kind.YOUTUBE, m.group(1).trim(), 0, t);
        // apps
        if ((m = OPEN.matcher(t)).find()) return new Cmd(Kind.OPEN_APP, m.group(1).trim(), 0, t);
        if ((m = TURN_ON.matcher(t)).find()) return new Cmd(Kind.OPEN_APP, m.group(1).trim(), 1, t);   // n=1: app or search
        if ((m = SEARCH.matcher(t)).find()) return new Cmd(Kind.SEARCH, m.group(1).trim(), 0, t);
        return new Cmd(Kind.UNKNOWN, null, 0, t);
    }
}
