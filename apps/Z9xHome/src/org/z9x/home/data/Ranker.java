package org.z9x.home.data;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the home model from the raw sources (SPEC 6). Pure Java (no Android calls), so it runs in
 * plain JVM tests (test/RankerTest.java).
 */
public final class Ranker {
    private Ranker() {}

    public static final int HERO_MAX = 6;
    public static final int HERO_WN_MAX = 3;
    public static final int CHANNEL_ROWS_MAX = 10;
    public static final int AUTO_FAVORITES = 8;
    public static final int WN_CONTINUE = 0; // TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE

    public static final class Input {
        public List<Card> apps = new ArrayList<>();          // one card per launcher activity
        public Map<String, Long> lastUsed = new HashMap<>();  // package -> last use
        public List<String> favorites = new ArrayList<>();
        public boolean favoritesSet;
        public Set<String> hiddenApps = new HashSet<>();      // user-hidden components or packages
        public Set<String> unhiddenApps = new HashSet<>();    // default-hidden ones the user shows
        public Set<String> defaultHidden = new HashSet<>();   // packages
        public List<String> rowOrder = new ArrayList<>();
        public Set<String> hiddenRows = new HashSet<>();
        public Set<String> shownRows = new HashSet<>();
        public TvpSource.Result tvp = new TvpSource.Result();
        public List<Card> inputs = new ArrayList<>();
        public List<Card> casts = new ArrayList<>();
        public List<Card> tiles = new ArrayList<>();
        public Map<String, String> labels = new HashMap<>(); // package -> app label
        public long now;
        public Locale locale = Locale.getDefault();
        public String tYourApps = "Your apps";
        public String tContinue = "Continue watching";
        public String tInputs = "Inputs";
        public String tProjector = "Projector";
        public String tCustomize = "Customize";
        public List<String> defaultFavoritePkgs = new ArrayList<>();
    }

    public static HomeModel build(Input in) {
        HomeModel m = new HomeModel();
        m.tvpMode = in.tvp.mode;

        // ---- apps
        Collator col = Collator.getInstance(in.locale);
        col.setStrength(Collator.PRIMARY);
        for (Card a : in.apps) {
            m.packages.add(a.pkg);
            if (isHidden(in, a)) m.hiddenApps.add(a);
            else m.apps.add(a);
        }
        Collections.sort(m.apps, (x, y) -> {
            int c = col.compare(x.title, y.title);
            return c != 0 ? c : x.id.compareTo(y.id);
        });
        Collections.sort(m.hiddenApps, (x, y) -> col.compare(x.title, y.title));
        m.favorites.addAll(favorites(in, m.apps));

        // ---- rows (natural order, direction D: "Continue watching" right under the hero, then the apps)
        LinkedHashMap<String, Row> natural = new LinkedHashMap<>();
        Row wn = new Row(Row.WATCH_NEXT, Row.ID_WATCH_NEXT, in.tContinue);
        wn.cards.addAll(in.tvp.watchNext);
        wn.aspect = Card.A_16_9;
        natural.put(wn.id, wn);

        Row apps = new Row(Row.APPS, Row.ID_APPS, in.tYourApps);
        apps.cards.addAll(m.favorites);
        natural.put(apps.id, apps);

        ArrayList<Row> channelRows = new ArrayList<>();
        for (TvpSource.Channel ch : in.tvp.channels) {
            List<Card> progs = in.tvp.programs.get(ch.id);
            if (progs == null || progs.isEmpty()) continue;
            if (!m.packages.contains(ch.pkg)) continue; // content of installed apps only
            String id = Row.channelId(ch.id);
            Row r = new Row(Row.CHANNEL, id, ch.name.isEmpty() ? label(in, ch.pkg) : ch.name);
            r.sub = label(in, ch.pkg);
            if (r.sub.equals(r.title)) r.sub = "";
            r.pkg = ch.pkg;
            r.channelId = ch.id;
            r.cards.addAll(progs);
            r.aspect = majorityAspect(progs);
            boolean shownByDefault = !ch.system && (ch.browsable || ch.firstOfPackage);
            r.hidden = in.hiddenRows.contains(id) || (!shownByDefault && !in.shownRows.contains(id));
            channelRows.add(r);
        }
        // channel rows ordered by the owning app's last use, then channel id
        Collections.sort(channelRows, (a, b) -> {
            long ua = in.lastUsed.getOrDefault(a.pkg, 0L), ub = in.lastUsed.getOrDefault(b.pkg, 0L);
            if (ua != ub) return Long.compare(ub, ua);
            return Long.compare(a.channelId, b.channelId);
        });
        int visibleChannels = 0;
        for (Row r : channelRows) {
            if (!r.hidden && ++visibleChannels > CHANNEL_ROWS_MAX) r.hidden = true;
            natural.put(r.id, r);
        }

        Row inputs = new Row(Row.INPUTS, Row.ID_INPUTS, in.tInputs);
        inputs.cards.addAll(in.inputs);
        natural.put(inputs.id, inputs);
        Row proj = new Row(Row.PROJECTOR, Row.ID_PROJECTOR, in.tProjector);
        proj.cards.addAll(in.tiles);
        natural.put(proj.id, proj);

        for (Row r : natural.values()) {
            if (r.type != Row.CHANNEL) r.hidden = in.hiddenRows.contains(r.id);
        }

        // ---- manual order: rows the user ordered first (in that order), the rest in natural order
        ArrayList<Row> ordered = new ArrayList<>();
        HashSet<String> used = new HashSet<>();
        for (String id : in.rowOrder) {
            Row r = natural.get(id);
            if (r != null && used.add(id)) ordered.add(r);
        }
        for (Row r : natural.values()) if (used.add(r.id)) ordered.add(r);
        m.allRows.addAll(ordered);
        for (Row r : ordered) if (!r.hidden && !r.cards.isEmpty()) m.rows.add(r);
        Row cz = new Row(Row.CUSTOMIZE, Row.ID_CUSTOMIZE, in.tCustomize);
        Card ck = new Card(Card.TILE, "customize", in.tCustomize);
        ck.intent = "customize";
        cz.cards.add(ck);
        m.rows.add(cz);

        // ---- hero
        m.hero.addAll(hero(in, m.rows));
        m.inputs.addAll(in.inputs);
        m.casts.addAll(in.casts);
        m.tiles.addAll(in.tiles);
        return m;
    }

    /** TvProvider content out of a model (hero, Watch Next, channel rows): "Continue watching on Home" hidden. */
    public static void dropTvp(HomeModel m) {
        m.hero.clear();
        m.rows.removeIf(r -> r.type == Row.WATCH_NEXT || r.type == Row.CHANNEL);
        m.allRows.removeIf(r -> r.type == Row.WATCH_NEXT || r.type == Row.CHANNEL);
    }

    static boolean isHidden(Input in, Card a) {
        if (in.hiddenApps.contains(a.id) || in.hiddenApps.contains(a.pkg)) return true;
        if (in.defaultHidden.contains(a.pkg)) return !(in.unhiddenApps.contains(a.id) || in.unhiddenApps.contains(a.pkg));
        return false;
    }

    static List<Card> favorites(Input in, List<Card> visible) {
        ArrayList<Card> out = new ArrayList<>();
        HashMap<String, Card> byId = new HashMap<>();
        for (Card a : visible) byId.put(a.id, a);
        if (in.favoritesSet) {
            for (String id : in.favorites) {
                Card a = byId.get(id);
                if (a != null && !out.contains(a)) out.add(a);
            }
            return out;
        }
        ArrayList<Card> used = new ArrayList<>();
        for (Card a : visible) if (in.lastUsed.getOrDefault(a.pkg, 0L) > 0) used.add(a);
        Collections.sort(used, (x, y) -> Long.compare(in.lastUsed.get(y.pkg), in.lastUsed.get(x.pkg)));
        HashSet<String> pkgs = new HashSet<>();
        for (Card a : used) {
            if (out.size() >= AUTO_FAVORITES) break;
            if (pkgs.add(a.pkg)) out.add(a);
        }
        for (String p : in.defaultFavoritePkgs) {
            if (pkgs.contains(p)) continue;
            for (Card a : visible) {
                if (a.pkg.equals(p)) {
                    out.add(a);
                    pkgs.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Hero of direction D: what can be continued (Watch Next, newest first, art optional: the living sky
     * stands in), then app preview programs round robin over the channel rows (art required). Empty =
     * the calm Home (big clock, no hero card); there are no filler slides.
     */
    static List<Card> hero(Input in, List<Row> rows) {
        ArrayList<Card> out = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        for (Row r : rows) {
            if (r.type != Row.WATCH_NEXT) continue;
            for (Card k : r.cards) {
                if (out.size() >= HERO_WN_MAX) break;
                if (keys.add(key(k))) out.add(k);
            }
        }
        ArrayList<Row> ch = new ArrayList<>();
        for (Row r : rows) if (r.type == Row.CHANNEL) ch.add(r);
        int[] next = new int[ch.size()];
        boolean progress = true;
        while (out.size() < HERO_MAX && progress) {
            progress = false;
            for (int i = 0; i < ch.size() && out.size() < HERO_MAX; i++) {
                List<Card> cards = ch.get(i).cards;
                while (next[i] < cards.size()) {
                    Card k = cards.get(next[i]++);
                    if (k.image == null || k.image.isEmpty()) continue;
                    if (keys.add(key(k))) {
                        out.add(k);
                        progress = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    static String key(Card k) {
        return k.pkg + "|" + (k.contentId.isEmpty() ? k.title.toLowerCase(Locale.ROOT) : k.contentId);
    }

    static int majorityAspect(List<Card> cards) {
        int[] n = new int[5];
        for (Card k : cards) n[Math.max(0, Math.min(4, k.aspect))]++;
        int best = Card.A_16_9;
        for (int i = 0; i < 5; i++) if (n[i] > n[best]) best = i;
        return best;
    }

    private static String label(Input in, String pkg) {
        String l = in.labels.get(pkg);
        return l == null ? pkg : l;
    }
}
