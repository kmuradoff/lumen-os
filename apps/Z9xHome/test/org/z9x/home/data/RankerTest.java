package org.z9x.home.data;

import java.util.Arrays;

/** Ranker rules (SPEC 6): favourites, hidden apps, channel rules, row order, hero. */
public final class RankerTest {
    static Card app(String pkg, String label) {
        Card k = new Card(Card.APP, pkg + "/.Main", label);
        k.pkg = pkg;
        return k;
    }

    static Card prog(String pkg, long ch, String title, String img, long weight) {
        Card k = new Card(Card.PROGRAM, "pp:" + title, title);
        k.pkg = pkg;
        k.channelId = ch;
        k.image = img;
        k.weight = weight;
        return k;
    }

    static TvpSource.Channel ch(long id, String pkg, String name, boolean browsable, boolean first, boolean system) {
        TvpSource.Channel c = new TvpSource.Channel();
        c.id = id;
        c.pkg = pkg;
        c.name = name;
        c.browsable = browsable;
        c.firstOfPackage = first;
        c.system = system;
        return c;
    }

    public static void run() {
        Ranker.Input in = new Ranker.Input();
        in.now = 1_000_000_000_000L;
        in.apps.addAll(Arrays.asList(app("com.yt", "YouTube"), app("ru.kp", "Кинопоиск"), app("org.z9x.home", "Lumen Home"),
                app("com.spot", "Spotify"), app("com.vending", "Play Store")));
        in.defaultHidden.add("org.z9x.home");
        in.lastUsed.put("com.spot", 50L);
        in.lastUsed.put("ru.kp", 90L);
        in.defaultFavoritePkgs.addAll(Arrays.asList("com.yt", "ru.kp", "com.vending"));
        in.labels.put("com.yt", "YouTube");
        in.labels.put("ru.kp", "Кинопоиск");
        in.tvp.channels.add(ch(1, "com.yt", "Recommended", false, true, false));    // first channel: shown
        in.tvp.channels.add(ch(2, "com.yt", "Music", false, false, false));         // not browsable, not first: hidden
        in.tvp.channels.add(ch(3, "ru.kp", "Новинки", true, true, false));          // browsable: shown
        in.tvp.channels.add(ch(4, "com.vending", "Promo", true, true, true));       // system key: hidden
        in.tvp.channels.add(ch(5, "com.gone", "Uninstalled", true, true, false));   // package not installed
        in.tvp.programs.put(1L, Arrays.asList(prog("com.yt", 1, "A", "https://a", 9), prog("com.yt", 1, "B", null, 8)));
        in.tvp.programs.put(2L, Arrays.asList(prog("com.yt", 2, "M", "https://m", 1)));
        in.tvp.programs.put(3L, Arrays.asList(prog("ru.kp", 3, "K1", "https://k1", 5), prog("ru.kp", 3, "K2", "https://k2", 4)));
        in.tvp.programs.put(4L, Arrays.asList(prog("com.vending", 4, "P", "https://p", 1)));
        in.tvp.programs.put(5L, Arrays.asList(prog("com.gone", 5, "G", "https://g", 1)));
        Card wn = prog("ru.kp", -1, "Continue me", "https://c", 0);
        wn.id = "wn:1";
        wn.table = Card.T_WATCH_NEXT;
        wn.wnType = 0;
        wn.engaged = in.now - 3600_000L;
        in.tvp.watchNext.add(wn);
        Card f1 = new Card(Card.FEATURE, "feature:cast", "Cast");
        in.features.add(f1);

        HomeModel m = Ranker.build(in);
        T.eq(m.apps.size(), 4, "hidden-by-default app removed");
        T.eq(m.hiddenApps.size(), 1, "hidden list");
        // auto favourites: used apps by recency (kp, spot), then defaults (yt, vending)
        T.eq(ids(m.favorites), "ru.kp,com.spot,com.yt,com.vending", "auto favourites");
        // rows: apps, wn, kp channel (used more recently) before yt channel, no hidden/system/uninstalled rows
        T.eq(rowIds(m), "apps,wn,ch:3,ch:1,customize", "row order");
        T.ok(m.allRows.stream().anyMatch(r -> r.id.equals("ch:2") && r.hidden), "non-default channel listed as hidden");
        // hero: WN continue first, then round robin over channel rows, skipping art-less items
        T.eq(m.hero.get(0).id, "wn:1", "hero starts with continue watching");
        T.eq(m.hero.size(), 4, "hero size (round robin continues, art-less B skipped)");
        T.eq(m.hero.get(1).title, "K1", "hero round robin row 1");
        T.eq(m.hero.get(2).title, "A", "hero round robin row 2");
        T.eq(m.hero.get(3).title, "K2", "hero second round");

        // manual favourites + manual row order + hidden row
        in.favoritesSet = true;
        in.favorites = Arrays.asList("com.spot/.Main", "missing/.X", "com.yt/.Main");
        in.rowOrder = Arrays.asList("ch:1", "apps");
        in.hiddenRows.add("wn");
        in.shownRows.add("ch:2");
        m = Ranker.build(in);
        T.eq(ids(m.favorites), "com.spot,com.yt", "manual favourites keep order, drop missing");
        T.eq(rowIds(m), "ch:1,apps,ch:3,ch:2,customize", "manual order first, hidden row out, user-shown row in");
        // fewer than 3 slides -> feature slides fill up
        in.tvp.watchNext.clear();
        in.tvp.channels.clear();
        m = Ranker.build(in);
        T.eq(m.hero.size(), 1, "only features when there is no content");
        T.eq(m.hero.get(0).kind, Card.FEATURE, "feature slide");
    }

    static String ids(java.util.List<Card> l) {
        StringBuilder sb = new StringBuilder();
        for (Card c : l) sb.append(sb.length() == 0 ? "" : ",").append(c.pkg);
        return sb.toString();
    }

    static String rowIds(HomeModel m) {
        StringBuilder sb = new StringBuilder();
        for (Row r : m.rows) sb.append(sb.length() == 0 ? "" : ",").append(r.id);
        return sb.toString();
    }
}
