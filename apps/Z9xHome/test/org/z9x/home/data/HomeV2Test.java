package org.z9x.home.data;

import org.z9x.home.img.ArtUrl;
import org.z9x.home.img.HeroArt;
import org.z9x.home.ui.Gfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lumen OS 1.0.5 Home (mockups design/home_v2, tpl2 the approved one): the "For you" row (Continue
 * watching, then the recommendations of every app, app by app, each title once, at most 24), landscape
 * art for a Continue watching card with portrait art, Kinopoisk art asked at 1920x1080 for the backdrop,
 * sharp or blurred backdrop by the art's real pixels, the backdrop motion switch.
 */
final class HomeV2Test {
    private static final String KP = "ru.kinopoisk.tv", LAMPA = "top.rootu.lampa";
    private static final String Y = "https://cdnx.svc.cdn.yandex.net/get-ott/18164279/2a0000019c2368bd";

    static Card prog(String pkg, long ch, String title, String img, int aspect) {
        Card k = new Card(Card.PROGRAM, "pp:" + ch + ":" + title, title);
        k.pkg = pkg;
        k.appLabel = pkg.equals(KP) ? "Кинопоиск" : "LAMPA";
        k.channelId = ch;
        k.table = Card.T_PREVIEW;
        k.image = img;
        k.aspect = aspect;
        return k;
    }

    static Ranker.Input input() {
        Ranker.Input in = new Ranker.Input();
        in.now = 1_000_000_000_000L;
        in.apps.addAll(Arrays.asList(RankerTest.app(KP, "Кинопоиск"), RankerTest.app(LAMPA, "LAMPA")));
        in.labels.put(KP, "Кинопоиск");
        in.labels.put(LAMPA, "LAMPA");
        in.lastUsed.put(KP, 90L);
        in.lastUsed.put(LAMPA, 50L);
        in.tvp.channels.add(RankerTest.ch(1, KP, "Популярное", true, true, false));
        in.tvp.channels.add(RankerTest.ch(2, LAMPA, "Рекомендации", true, true, false));
        in.tvp.channels.add(RankerTest.ch(3, LAMPA, "История", true, false, false));
        List<Card> kp = new ArrayList<>();
        kp.add(prog(KP, 1, "K1", "https://avatars.mds.yandex.net/get-ott/1/k1/640x360", Card.A_16_9));
        kp.add(prog(KP, 1, "Великолепный век", Y + "/800x450", Card.A_16_9));
        for (int i = 2; i <= 26; i++) kp.add(prog(KP, 1, "K" + i, "https://avatars.mds.yandex.net/get-ott/1/k" + i + "/800x450", Card.A_16_9));
        in.tvp.programs.put(1L, kp);
        in.tvp.programs.put(2L, Arrays.asList(prog(LAMPA, 2, "L1", "https://imagetmdb.com/t/p/w1280/l1.jpg", Card.A_16_9),
                prog(LAMPA, 2, "Poster", "https://imagetmdb.com/t/p/w300/p.jpg", Card.A_2_3),
                prog(LAMPA, 2, "NoArt", null, Card.A_16_9),
                prog(LAMPA, 2, "L2", "https://imagetmdb.com/t/p/w1280/l2.jpg", Card.A_16_9)));
        in.tvp.programs.put(3L, Arrays.asList(prog(LAMPA, 3, "ВЕЛИКОЛЕПНЫЙ  век!", "https://imagetmdb.com/t/p/w1280/v.jpg", Card.A_16_9),
                prog(LAMPA, 3, "L1", "https://imagetmdb.com/t/p/w1280/l1b.jpg", Card.A_16_9),
                prog(LAMPA, 3, "L3", "https://imagetmdb.com/t/p/w1280/l3.jpg", Card.A_16_9)));
        Card wn = prog(KP, -1, "Великолепный век", Y.replace("2a0000019c2368bd", "2a000001975f563f") + "/460x690", Card.A_16_9);
        wn.id = "wn:1";
        wn.table = Card.T_WATCH_NEXT;
        wn.wnType = 0;
        wn.progress = 800;
        Card wn2 = prog("com.xgimiru.player", -1, "Breaking Bad", null, Card.A_16_9);
        wn2.id = "wn:2";
        wn2.table = Card.T_WATCH_NEXT;
        wn2.wnType = 0;
        in.tvp.watchNext.addAll(Arrays.asList(wn, wn2));
        Card hdmi = new Card(Card.INPUT, "hdmi1", "HDMI 1");
        in.inputs.add(hdmi);
        return in;
    }

    static void run() {
        // ---- the For you row
        Ranker.Input in = input();
        HomeModel m = Ranker.build(in);
        T.eq(RankerTest.rowIds(m), "foryou,apps,customize", "two rows: For you, apps (inputs hidden by default)");
        Row fy = m.rows.get(0);
        T.eq(fy.type, Row.FOR_YOU, "For you row type");
        T.eq(fy.cards.get(0).id, "wn:1", "Continue watching first");
        T.eq(fy.cards.get(1).id, "wn:2", "an art-less Continue item stays (tinted card)");
        T.eq(titles(fy.cards.subList(2, 8)), "K1,L1,K2,L2,K3,L3", "recommendations app by app, posters, art-less and repeats out");
        T.ok(fy.cards.stream().noneMatch(k -> k.table == Card.T_PREVIEW && Ranker.normTitle(k.title).equals("великолепныйвек")),
                "a title in Continue watching is not recommended again (any app, normalised)");
        T.eq((int) fy.cards.stream().filter(k -> k.table == Card.T_PREVIEW).count(), Ranker.FOR_YOU_RECS, "at most 24 recommendations");
        T.eq(fy.title, "Continue watching", "heading on a Continue item");
        T.eq(fy.title2, "Recommendations", "heading on a recommendation");
        T.eq(fy.sub2, "Кинопоиск · LAMPA", "the apps after it");
        T.ok(!m.hero.isEmpty(), "the backdrop Home");
        // landscape art for the portrait Continue item: the same title's art from its app's channel
        Card wn = fy.cards.get(0);
        T.eq(wn.image, Y + "/800x450", "Continue item: the channel's landscape art");
        T.ok(wn.image2.endsWith("/460x690") && wn.aspect == Card.A_16_9, "its portrait art kept as the other art");
        // the Continue row hidden in Customize: For you starts with the recommendations
        in = input();
        in.hiddenRows.add(Row.ID_WATCH_NEXT);
        m = Ranker.build(in);
        fy = m.rows.get(0);
        T.eq(fy.cards.get(0).title, "K1", "Continue watching hidden: recommendations first");
        T.eq(fy.title, "Recommendations", "Continue watching hidden: heading");
        T.ok(fy.cards.stream().anyMatch(k -> k.title.equals("Великолепный век")), "hidden: its title may be recommended");
        // a hidden channel feeds no recommendations; inputs shown by the user; an old order with wn first
        in = input();
        in.hiddenRows.add("ch:1");
        in.shownRows.add(Row.ID_INPUTS);
        in.rowOrder = Arrays.asList(Row.ID_WATCH_NEXT, Row.ID_APPS, Row.ID_INPUTS);
        m = Ranker.build(in);
        T.ok(m.rows.get(0).cards.stream().noneMatch(k -> k.pkg.equals(KP) && k.table == Card.T_PREVIEW), "hidden channel: none of its programs");
        T.eq(RankerTest.rowIds(m), "foryou,apps,inputs,customize", "inputs shown by the user; old order keeps For you first");
        // the 1.0.4 rows on request: one per channel, channels before the apps
        in = input();
        in.forYou = false;
        m = Ranker.build(in);
        T.eq(RankerTest.rowIds(m), "wn,ch:1,ch:2,ch:3,apps,inputs,customize", "per-channel rows (forYou off)");
        T.ok(m.rows.get(1).cards.stream().noneMatch(k -> k.title.equals("Великолепный век")), "per-channel: no repeat of Continue");
        // Kinopoisk portrait art without a channel match: the same picture asked as 800x450
        Card p = prog(KP, -1, "Кухня", "https://cdnx.svc.cdn.yandex.net/get-ott/17735790/2a00/460x690", Card.A_16_9);
        Ranker.landscapeArt(p, new TvpSource.Result());
        T.eq(p.image, "https://cdnx.svc.cdn.yandex.net/get-ott/17735790/2a00/800x450", "KP portrait: 800x450");
        Ranker.landscapeArt(p, new TvpSource.Result());
        T.ok(p.image2.endsWith("/460x690"), "twice changes nothing more");

        // ---- ArtUrl
        T.eq(ArtUrl.backdrop("https://avatars.mds.yandex.net/get-ott/200035/2a00/640x360"),
                "https://avatars.mds.yandex.net/get-ott/200035/2a00/1920x1080", "avatars.mds: backdrop size");
        T.eq(ArtUrl.backdrop(Y + "/orig"), Y + "/1920x1080", "orig: backdrop size (orig can be a 10 MB 4K JPEG)");
        T.eq(ArtUrl.backdrop("https://imagetmdb.com/t/p/w1280/x.jpg?email="), "https://imagetmdb.com/t/p/w1280/x.jpg?email=", "TMDB unchanged");
        T.eq(ArtUrl.backdrop("https://evil.example/get-ott/1/2/640x360"), "https://evil.example/get-ott/1/2/640x360", "other hosts unchanged");
        T.eq(ArtUrl.backdrop("https://avatars.mds.yandex.net/get-kinopoisk-image/1/2/640x360"),
                "https://avatars.mds.yandex.net/get-kinopoisk-image/1/2/640x360", "only get-ott namespaces");
        T.ok(ArtUrl.portraitHint(Y + "/460x690") && !ArtUrl.portraitHint(Y + "/800x450"), "portrait by the URL's size");
        T.eq(ArtUrl.landscapeCard(Y + "/800x450"), null, "landscape: nothing to ask for");
        T.eq(ArtUrl.sizeHint("content://x/460x690"), null, "content uri: no hint");

        // ---- backdrop: sharp landscape art, else blurred
        T.eq(HeroArt.backdrop(new int[]{800, 450}, 1920, 1080).mode, HeroArt.FILL, "800x450 sharp (2.4x, as the mockup)");
        T.eq(HeroArt.backdrop(new int[]{640, 360}, 1920, 1080).mode, HeroArt.BLUR, "640x360: blurred");
        T.eq(HeroArt.backdrop(new int[]{300, 450}, 1920, 1080).mode, HeroArt.BLUR, "poster: blurred");
        HeroArt.Plan b = HeroArt.backdrop(new int[]{460, 690, 1920, 1080}, 1920, 1080);
        T.eq(b.mode + "/" + b.index, HeroArt.FILL + "/1", "landscape other art wins");
        b = HeroArt.backdrop(new int[]{1280, 720, 1920, 1080}, 1920, 1080);
        T.eq(b.index, 1, "two sharp: the larger");
        T.eq(HeroArt.backdrop(new int[]{0, 0}, 1920, 1080).mode, HeroArt.NONE, "nothing known");

        // ---- the motion switch
        T.eq(Gfx.BACKDROP_FPS, Gfx.BACKDROP_FPS_DEFAULT, "backdrop motion unset: default rate (Z9X)");
        T.eq(Gfx.backdropFps(-1, true), 0, "lite (Play 6): still");
        T.eq(Gfx.backdropFps(0, false), 0, "0: still");
        T.eq(Gfx.backdropFps(15, true), 15, "set: wins over lite");
        T.eq(Gfx.backdropFps(240, false), 60, "at most 60");
    }

    static String titles(List<Card> l) {
        StringBuilder sb = new StringBuilder();
        for (Card c : l) sb.append(sb.length() == 0 ? "" : ",").append(c.title);
        return sb.toString();
    }
}
