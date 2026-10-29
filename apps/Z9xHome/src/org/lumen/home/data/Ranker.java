package org.lumen.home.data;

import org.lumen.home.img.ArtUrl;

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
 * plain JVM tests (test/RankerTest.java, HomeV2Test.java). Lumen OS 1.0.5: Home shows one content row,
 * "For you" ({@link #forYou}: Continue watching, then the recommendations of every shown channel), then
 * the apps; inputs and projector tiles only when the user shows them. Customize still lists Continue
 * watching and each channel. A title in Continue watching is not shown again as a recommendation, and a
 * Continue watching card with portrait art gets landscape art ({@link #landscapeArt}). With
 * {@link Input#forYou} off: the rows of 1.0.4, one per channel, the channels before the apps.
 */
public final class Ranker {
    private Ranker() {}

    public static final int HERO_MAX = 6;
    public static final int HERO_WN_MAX = 3;
    public static final int CHANNEL_ROWS_MAX = 10;
    public static final int AUTO_FAVORITES = 8;
    public static final int WN_CONTINUE = 0; // TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
    /** The recommendations in the "For you" row, at most (the owner's mockup home_v2 tpl2: 24). */
    public static final int FOR_YOU_RECS = 24;
    /**
     * App stores whose channels only advertise apps (owner 2026-10-21: "RuStore recommendations are nonsense, remove
     * them"): hidden by default, like the system promo channels; Customize can still show them.
     */
    public static final Set<String> STORE_PKGS = new HashSet<>(java.util.Arrays.asList(
            "ru.vk.store.tv", "ru.vk.store", "com.android.vending", "com.huawei.appmarket", "com.xiaomi.mipicks"));

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
        public String tRecommendations = "Recommendations";
        /**
         * 1.0.5 (owner: many stacked rows were too much): Continue watching and every shown app channel
         * as one "For you" row ({@link #forYou}); false = the rows of 1.0.4, one per channel.
         */
        public boolean forYou = true;
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

        // ---- rows (natural order, 1.0.5: "Continue watching", the app channels, then the apps)
        LinkedHashMap<String, Row> natural = new LinkedHashMap<>();
        Row wn = new Row(Row.WATCH_NEXT, Row.ID_WATCH_NEXT, in.tContinue);
        for (Card k : in.tvp.watchNext) landscapeArt(k, in.tvp);
        wn.cards.addAll(in.tvp.watchNext);
        wn.aspect = Card.A_16_9;
        natural.put(wn.id, wn);
        // what Continue watching shows is not repeated in a channel row (only while that row is shown)
        HashSet<String> continued = new HashSet<>();
        if (!in.hiddenRows.contains(Row.ID_WATCH_NEXT)) {
            for (Card k : wn.cards) continued.addAll(sameKeys(k));
        }

        Row apps = new Row(Row.APPS, Row.ID_APPS, in.tYourApps);
        apps.cards.addAll(m.favorites);

        ArrayList<Row> channelRows = new ArrayList<>();
        for (TvpSource.Channel ch : in.tvp.channels) {
            List<Card> progs = withoutContinued(in.tvp.programs.get(ch.id), continued);
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
            boolean shownByDefault = !ch.system && !STORE_PKGS.contains(ch.pkg) && (ch.browsable || ch.firstOfPackage);
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
        natural.put(apps.id, apps);

        Row inputs = new Row(Row.INPUTS, Row.ID_INPUTS, in.tInputs);
        inputs.cards.addAll(in.inputs);
        natural.put(inputs.id, inputs);
        Row proj = new Row(Row.PROJECTOR, Row.ID_PROJECTOR, in.tProjector);
        proj.cards.addAll(in.tiles);
        natural.put(proj.id, proj);

        for (Row r : natural.values()) {
            if (r.type != Row.CHANNEL) r.hidden = in.hiddenRows.contains(r.id);
            // "For you" Home is two rows (owner): inputs and projector tiles only when the user shows them
            if (in.forYou && (r.type == Row.INPUTS || r.type == Row.PROJECTOR) && !in.shownRows.contains(r.id)) r.hidden = true;
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
        ArrayList<Row> visible = new ArrayList<>();
        for (Row r : ordered) if (!r.hidden && !r.cards.isEmpty()) visible.add(r);
        if (in.forYou) {
            // Home shows one content row, then the other rows (apps, inputs, projector) in their order;
            // Customize still lists Continue watching and each channel (hidden ones feed no recommendations)
            ArrayList<Row> shown = new ArrayList<>();
            Row fy = forYou(in, ordered);
            if (fy != null) shown.add(fy);
            for (Row r : visible) if (r.type != Row.WATCH_NEXT && r.type != Row.CHANNEL) shown.add(r);
            // the user's order of these rows (Customize, Reorder; "For you" takes Continue watching's place),
            // then the rest as above
            for (String id : in.rowOrder) {
                for (Row r : shown) {
                    boolean is = r.id.equals(id) || (r.type == Row.FOR_YOU && id.equals(Row.ID_WATCH_NEXT));
                    if (is && !m.rows.contains(r)) m.rows.add(r);
                }
            }
            for (Row r : shown) if (!m.rows.contains(r)) m.rows.add(r);
        } else {
            m.rows.addAll(visible);
        }
        Row cz = new Row(Row.CUSTOMIZE, Row.ID_CUSTOMIZE, in.tCustomize);
        Card ck = new Card(Card.TILE, "customize", in.tCustomize);
        ck.intent = "customize";
        cz.cards.add(ck);
        m.rows.add(cz);

        // ---- hero: what Home has to show (non-empty = the backdrop Home, empty = D_Calm)
        m.hero.addAll(hero(in, visible));
        m.inputs.addAll(in.inputs);
        m.casts.addAll(in.casts);
        m.tiles.addAll(in.tiles);
        return m;
    }

    /**
     * "For you" (1.0.5): the Continue watching cards (newest first) unless that row is hidden, then the
     * recommendations of the shown channel rows taken app by app in turn (the first of app A, of app B,
     * the second of A, ...; an app's channels one after the other), at most {@link #FOR_YOU_RECS}, each
     * title once (normalised title or content id, also against Continue watching). Only landscape art
     * is recommended (a poster or no art would be a soft crop or an empty card in the 16:9 row). Its
     * title is Continue watching ({@link Row#title}) or, on a recommendation, Recommendations
     * ({@link Row#title2}) with the apps after it ({@link Row#sub2}). Null when it has no card.
     */
    static Row forYou(Input in, List<Row> ordered) {
        Row fy = new Row(Row.FOR_YOU, Row.ID_FOR_YOU, in.tContinue);
        fy.aspect = Card.A_16_9;
        HashSet<String> seen = new HashSet<>();
        LinkedHashMap<String, ArrayList<Card>> byApp = new LinkedHashMap<>();
        for (Row r : ordered) {
            if (r.hidden) continue;
            if (r.type == Row.WATCH_NEXT) {
                for (Card k : r.cards) {
                    fy.cards.add(k);
                    seen.addAll(sameKeys(k));
                }
            } else if (r.type == Row.CHANNEL) {
                ArrayList<Card> l = byApp.get(r.pkg);
                if (l == null) byApp.put(r.pkg, l = new ArrayList<>());
                l.addAll(r.cards);
            }
        }
        boolean cont = !fy.cards.isEmpty();
        ArrayList<String> sources = new ArrayList<>();
        ArrayList<ArrayList<Card>> apps = new ArrayList<>(byApp.values());
        int[] next = new int[apps.size()];
        int recs = 0;
        boolean progress = true;
        while (recs < FOR_YOU_RECS && progress) {
            progress = false;
            for (int i = 0; i < apps.size() && recs < FOR_YOU_RECS; i++) {
                List<Card> l = apps.get(i);
                while (next[i] < l.size()) {
                    Card k = l.get(next[i]++);
                    if (k.image == null || k.image.isEmpty() || portrait(k.image, k.aspect)) continue;
                    List<String> keys = sameKeys(k);
                    boolean dup = false;
                    for (String key : keys) dup |= seen.contains(key);
                    if (dup) continue;
                    seen.addAll(keys);
                    fy.cards.add(k);
                    recs++;
                    progress = true;
                    String app = label(in, k.pkg);
                    if (!sources.contains(app)) sources.add(app);
                    break;
                }
            }
        }
        if (fy.cards.isEmpty()) return null;
        if (!cont) fy.title = in.tRecommendations;
        fy.title2 = in.tRecommendations;
        fy.sub2 = String.join(" · ", sources);
        return fy;
    }

    /** TvProvider content out of a model (hero, Watch Next, channel rows): "Continue watching on Home" hidden. */
    public static void dropTvp(HomeModel m) {
        m.hero.clear();
        m.rows.removeIf(r -> r.type == Row.WATCH_NEXT || r.type == Row.CHANNEL || r.type == Row.FOR_YOU);
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

    /**
     * The keys under which a card counts as the same title: its normalised title (any app) and, when the
     * app gives one, its package and content id.
     */
    static List<String> sameKeys(Card k) {
        ArrayList<String> out = new ArrayList<>(2);
        String t = normTitle(k.title);
        if (!t.isEmpty()) out.add("t|" + t);
        if (!k.contentId.isEmpty()) out.add("c|" + k.pkg + "|" + k.contentId);
        return out;
    }

    /** The programs of a channel without the titles in {@code continued} (a new list when one is dropped). */
    static List<Card> withoutContinued(List<Card> progs, Set<String> continued) {
        if (progs == null || continued.isEmpty()) return progs;
        ArrayList<Card> out = null;
        for (int i = 0; i < progs.size(); i++) {
            Card k = progs.get(i);
            boolean dup = false;
            for (String key : sameKeys(k)) dup |= continued.contains(key);
            if (dup && out == null) out = new ArrayList<>(progs.subList(0, i));
            else if (!dup && out != null) out.add(k);
        }
        return out == null ? progs : out;
    }

    /** Lower case, "ё" as "е", letters and digits only: "Великолепный век!" and "великолепный  век" match. */
    public static String normTitle(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        String l = s.toLowerCase(Locale.ROOT);
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == 'ё') c = 'е';
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    /** Portrait art: by the size its URL asks for (Kinopoisk), else by the aspect the app declared. */
    static boolean portrait(String uri, int aspect) {
        int[] h = ArtUrl.sizeHint(uri);
        if (h != null) return h[0] * 5 < h[1] * 4;
        return aspect == Card.A_2_3;
    }

    /**
     * A "Continue watching" card (a 16:9 row and the full-screen backdrop) whose art is portrait gets
     * landscape art: the art of the same title (normalised, or the same content id) in one of the same
     * app's preview channels, else for Kinopoisk the same picture asked at 800x450 (ArtUrl). The portrait
     * art stays as the other art ({@link Card#image2}). Nothing changes when the card already has
     * landscape other art (a thumbnail) or no art; running it twice changes nothing more.
     */
    static void landscapeArt(Card k, TvpSource.Result tvp) {
        if (k.image == null || k.image.isEmpty() || !portrait(k.image, k.aspect)) return;
        if (k.image2 != null && !k.image2.isEmpty() && !portrait(k.image2, k.aspect2)) return;
        String t = normTitle(k.title);
        String land = null;
        for (TvpSource.Channel ch : tvp.channels) {
            if (land != null || !ch.pkg.equals(k.pkg)) continue;
            List<Card> progs = tvp.programs.get(ch.id);
            if (progs == null) continue;
            for (Card p : progs) {
                if (p.image == null || p.image.isEmpty() || portrait(p.image, p.aspect)) continue;
                boolean same = (!t.isEmpty() && t.equals(normTitle(p.title)))
                        || (!k.contentId.isEmpty() && k.contentId.equals(p.contentId));
                if (same) {
                    land = p.image;
                    break;
                }
            }
        }
        if (land == null) land = ArtUrl.landscapeCard(k.image);
        if (land == null) return;
        k.image2 = k.image;
        k.aspect2 = Card.A_2_3;
        k.image = land;
        k.aspect = Card.A_16_9;
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
