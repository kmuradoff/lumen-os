package org.z9x.home.tmdb;

import org.z9x.home.data.Ranker;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which TMDB search result is the program on a Continue watching card (Lumen OS 1.0.5; pure Java,
 * test/TmdbTest). The query is the card's title without what file names add (" S01 E01", the year,
 * "1080p", "WEB-DL", ...). A result needs key art (backdrop_path) and its title or original title must be
 * the query's (normalised as Ranker.normTitle: case, "ё", punctuation and spaces do not count) or contain
 * it (or be contained), never just anything the search returned. Then: an exact title before a partial
 * one, a series (tv) for an item with a season or episode, the year when the title carried one, and
 * TMDB's popularity last.
 */
public final class TmdbMatch {
    private TmdbMatch() {}

    /** One result of /3/search/multi. */
    public static final class Result {
        public String type = "";      // "tv", "movie" ("person" and others are skipped)
        public String title = "";     // title (movie) or name (tv), in the asked language
        public String original = "";  // original_title / original_name
        public String backdrop = "";  // backdrop_path ("/abc.jpg"), "" = none
        public double popularity;
        public int year;              // of release_date / first_air_date, 0 = unknown

        public Result() {}

        public Result(String type, String title, String original, String backdrop, double popularity, int year) {
            this.type = type;
            this.title = title == null ? "" : title;
            this.original = original == null ? "" : original;
            this.backdrop = backdrop == null ? "" : backdrop;
            this.popularity = popularity;
            this.year = year;
        }
    }

    private static final Pattern EPISODE = Pattern.compile("(?i)\\bS\\d{1,2}\\s*E\\d{1,3}\\b|\\b\\d{1,2}x\\d{2}\\b|\\bS\\d{1,2}\\b");
    private static final Pattern YEAR = Pattern.compile("(?<![\\d])(19\\d{2}|20\\d{2})(?![\\d])");
    private static final Pattern RIP = Pattern.compile("(?i)\\b(\\d{3,4}p|[48]k|uhd|hdr\\d*|sdr|bdrip|bdremux|bluray|blu-ray"
            + "|web-?dl|web-?rip|webrip|hdtv|dvdrip|remux|x26[45]|h\\.?26[45]|hevc|avc|aac\\d?|ac3|dts|ddp?\\d?"
            + "|repack|okko|amzn)\\b");

    /** The title to search: from the start up to the first episode mark, year or rip word; trimmed. */
    public static String query(String raw) {
        if (raw == null) return "";
        String s = raw.replace('_', ' ').replace('.', ' ').trim();
        int cut = s.length();
        for (Pattern p : new Pattern[]{EPISODE, YEAR, RIP}) {
            Matcher m = p.matcher(s);
            if (m.find() && m.start() > 0) cut = Math.min(cut, m.start());
        }
        s = s.substring(0, cut).trim();
        // trailing separators left by the cut ("Title -", "Title (")
        while (!s.isEmpty() && "-–([,:".indexOf(s.charAt(s.length() - 1)) >= 0) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    /** The year a file-name title carries ("Gran Turismo 2023 1080p" -> 2023), else 0. */
    public static int year(String raw) {
        if (raw == null) return 0;
        Matcher m = YEAR.matcher(raw);
        while (m.find()) if (m.start() > 0) return Integer.parseInt(m.group(1));
        return 0;
    }

    /** True when the title itself names an episode or season ("S01 E01", "1x02"). */
    public static boolean episodic(String raw) {
        return raw != null && EPISODE.matcher(raw).find();
    }

    /** How well a result's title matches: 2 exact, 1 one contains the other (4+ letters), 0 not at all. */
    static int match(String query, Result r) {
        String q = Ranker.normTitle(query);
        if (q.isEmpty()) return 0;
        int best = 0;
        for (String t : new String[]{r.title, r.original}) {
            String n = Ranker.normTitle(t);
            if (n.isEmpty()) continue;
            if (n.equals(q)) return 2;
            if (Math.min(n.length(), q.length()) >= 4 && (n.contains(q) || q.contains(n))) best = 1;
        }
        return best;
    }

    /**
     * The result that is the program, or null.
     *
     * @param title    the card's title (raw)
     * @param episodic the card is an episode (season / episode columns) or its title says so
     */
    public static Result pick(List<Result> results, String title, boolean episodic) {
        if (results == null) return null;
        String q = query(title);
        int year = year(title);
        boolean ep = episodic || episodic(title);
        Result best = null;
        double bestScore = 0;
        for (Result r : results) {
            if (r == null || r.backdrop.isEmpty() || !("tv".equals(r.type) || "movie".equals(r.type))) continue;
            int m = match(q, r);
            if (m == 0) continue;
            double s = m * 100;
            if (ep && "tv".equals(r.type)) s += 40;
            if (year > 0 && r.year == year) s += 30;
            s += Math.min(30, Math.log1p(Math.max(0, r.popularity)) * 5);
            if (best == null || s > bestScore) {
                best = r;
                bestScore = s;
            }
        }
        return best;
    }
}
