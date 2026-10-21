package org.z9x.projector.voice;

import java.util.Arrays;
import java.util.List;

/** Plain JVM test of the assistant's offline parts: sh apps/common/projector/test/run_voice_test.sh */
public final class VoiceTest {
    private static int pass, fail;

    private static void eq(Object got, Object want, String what) {
        if (String.valueOf(got).equals(String.valueOf(want))) pass++;
        else { fail++; System.out.println("FAIL " + what + ": got " + got + ", want " + want); }
    }

    private static void cmd(String said, String want) { eq(Commands.parse(said), want, "\"" + said + "\""); }

    public static void main(String[] a) {
        cmd("Сделай погромче", "VOLUME_UP[2]");
        cmd("сделай намного громче", "VOLUME_UP[5]");
        cmd("Тише, пожалуйста", "VOLUME_DOWN[2]");
        cmd("громкость 30", "VOLUME_SET[30]");
        cmd("Поставь громкость на 50%", "VOLUME_SET[50]");
        cmd("volume 20 percent", "VOLUME_SET[20]");
        cmd("выключи звук", "MUTE");
        cmd("пауза", "PAUSE");
        cmd("Продолжи", "PLAY");
        cmd("следующая серия", "NEXT");
        cmd("на главную", "HOME");
        cmd("Сделай автофокус", "AUTOFOCUS");
        cmd("выровняй картинку", "KEYSTONE");
        cmd("настройки звука", "SECTION(sound)");
        cmd("убавь яркость", "SECTION(lamp)");
        cmd("Который час?", "TIME");
        cmd("открой ютуб", "OPEN_APP(ютуб)");
        cmd("Запусти Кинопоиск", "OPEN_APP(кинопоиск)");
        cmd("open netflix", "OPEN_APP(netflix)");
        cmd("включи вк видео", "OPEN_APP(вк видео)[1]");
        cmd("найди интерстеллар", "SEARCH(интерстеллар)");
        cmd("хочу посмотреть Кухню", "SEARCH(кухню)");
        cmd("найди котиков на ютубе", "YOUTUBE(котиков)");
        cmd("ютуб лофай музыка", "YOUTUBE(лофай музыка)");
        cmd("play lofi on youtube", "YOUTUBE(lofi)");
        cmd("Люмен, расскажи анекдот", "UNKNOWN");
        cmd("какая погода завтра", "UNKNOWN");

        List<AppMatcher.App> apps = Arrays.asList(new AppMatcher.App("com.google.android.youtube.tv", "YouTube"),
                new AppMatcher.App("ru.kinopoisk.tv", "Кинопоиск"), new AppMatcher.App("com.vk.tv", "VK Видео"),
                new AppMatcher.App("ru.rutube.app.tv", "RUTUBE"), new AppMatcher.App("top.rootu.lampa", "LAMPA"),
                new AppMatcher.App("com.xgimiru.player", "Lumen Play"), new AppMatcher.App("com.netflix.ninja", "Netflix"),
                new AppMatcher.App("org.videolan.vlc", "VLC"), new AppMatcher.App("com.plexapp.android", "Plex"));
        eq(pkg(AppMatcher.match("ютуб", apps)), "com.google.android.youtube.tv", "ютуб");
        eq(pkg(AppMatcher.match("кинопоиск", apps)), "ru.kinopoisk.tv", "кинопоиск");
        eq(pkg(AppMatcher.match("вк видео", apps)), "com.vk.tv", "вк видео");
        eq(pkg(AppMatcher.match("рутуб", apps)), "ru.rutube.app.tv", "рутуб");
        eq(pkg(AppMatcher.match("лампу", apps)), "top.rootu.lampa", "лампу");
        eq(pkg(AppMatcher.match("нетфликс", apps)), "com.netflix.ninja", "нетфликс");
        eq(pkg(AppMatcher.match("плекс", apps)), "com.plexapp.android", "плекс (translit)");
        eq(pkg(AppMatcher.match("влц", apps)), "org.videolan.vlc", "влц");
        eq(pkg(AppMatcher.match("люмен плей", apps)), "com.xgimiru.player", "люмен плей");
        eq(pkg(AppMatcher.match("интерстеллар", apps)), "null", "not an app");
        System.out.println("voice tests passed=" + pass + " failed=" + fail);
        if (fail > 0) System.exit(1);
    }

    private static String pkg(AppMatcher.App a) { return a == null ? "null" : a.pkg; }
}
