package org.z9x.home.search;

import android.icu.text.Transliterator;

import org.z9x.home.data.Card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * App search (SPEC 7.9): prefix, word-prefix and substring matches on the label, plus a transliterated
 * match both ways (Cyrillic and other scripts to Latin ASCII), so "kinopoisk" finds "Кинопоиск" and
 * "ютуб" finds nothing worse than before. Ranked by match quality, then by recent use.
 */
public final class AppMatcher {
    private static Transliterator sTr;

    private AppMatcher() {}

    private static synchronized Transliterator tr() {
        if (sTr == null) {
            try {
                sTr = Transliterator.getInstance("Any-Latin; Latin-ASCII; Lower");
            } catch (Throwable t) {
                sTr = null;
            }
        }
        return sTr;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.getDefault()).trim();
    }

    static String latin(String s) {
        Transliterator t = tr();
        String r = t != null ? t.transliterate(s) : s.toLowerCase(Locale.ROOT);
        return r.replaceAll("[^a-z0-9 ]", "");
    }

    public static List<Card> match(List<Card> apps, String query, Map<String, Long> lastUsed, int max) {
        String q = norm(query);
        ArrayList<Card> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        String ql = latin(q);
        final java.util.HashMap<Card, Integer> score = new java.util.HashMap<>();
        for (Card a : apps) {
            String l = norm(a.title);
            int s = 0;
            if (l.startsWith(q)) s = 100;
            else if (l.contains(" " + q)) s = 90;
            else if (l.contains(q)) s = 70;
            else if (!ql.isEmpty()) {
                String ll = latin(l);
                if (ll.startsWith(ql)) s = 60;
                else if (ll.contains(ql)) s = 40;
                else if (a.pkg.contains(ql) && ql.length() >= 3) s = 20;
            }
            if (s > 0) {
                score.put(a, s);
                out.add(a);
            }
        }
        Collections.sort(out, (x, y) -> {
            int c = Integer.compare(score.get(y), score.get(x));
            if (c != 0) return c;
            long ux = lastUsed == null ? 0 : lastUsed.getOrDefault(x.pkg, 0L);
            long uy = lastUsed == null ? 0 : lastUsed.getOrDefault(y.pkg, 0L);
            if (ux != uy) return Long.compare(uy, ux);
            return x.title.compareToIgnoreCase(y.title);
        });
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    /** Local title index of TvProvider programs (preview + watch next). */
    public static List<Card> matchPrograms(List<Card> programs, String query, int max) {
        String q = norm(query);
        ArrayList<Card> out = new ArrayList<>();
        if (q.length() < 2) return out;
        String ql = latin(q);
        for (Card k : programs) {
            String t = norm(k.title);
            if (t.contains(q) || (!ql.isEmpty() && ql.length() >= 3 && latin(t).contains(ql))) {
                out.add(k);
                if (out.size() >= max) break;
            }
        }
        return out;
    }
}
