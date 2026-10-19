package org.z9x.home.tmdb;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.z9x.home.App;
import org.z9x.home.data.Card;
import org.z9x.home.data.Ranker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Key art for Continue watching (Lumen OS 1.0.5, approved by the owner): Kinopoisk gives these cards a
 * random episode still and the Kino player none, so the series or movie is looked up on TMDB
 * (/3/search/multi in the system language, then in English; {@link TmdbMatch} picks the result) and its
 * backdrop_path shown: w780 on the card, w1280 as the full-screen backdrop (ArtUrl). The previous art
 * stays until TMDB's arrives (then a refresh: the card and the backdrop crossfade); the old art is kept
 * as the card's other art. Each title is looked up once: results on disk for 30 days, misses for a day,
 * network failures retried after 10 minutes. Lookups run on their own thread ("z9x-home-tmdb"), one at a
 * time, never on the UI or the refresh thread. The API key is not in the sources: the build packs it as
 * the asset "tmdb_key" (build_apk.sh TMDB_KEY_FILE); without it this does nothing. The key never goes
 * to the log. TMDB hosts the network's DNS hides are reached through {@link TmdbNet}.
 */
public final class TmdbArt {
    public static final String IMG = org.z9x.home.img.ArtUrl.TMDB_IMG;
    static final long HIT_MS = 30L * 86_400_000L, MISS_MS = 86_400_000L, RETRY_MS = 10 * 60_000L;
    private static final String FILE = "tmdb_art.txt";
    private static final int MAX_ENTRIES = 500;

    private final App mApp;
    private final String mKey;
    private final File mFile;
    private final HashMap<String, String[]> mCache = new HashMap<>(); // key -> {path or "", time}
    private final HashMap<String, Long> mFailed = new HashMap<>();
    private final HashSet<String> mPending = new HashSet<>();
    private Handler mWorker;

    private TmdbArt(App app, String key) {
        mApp = app;
        mKey = key;
        mFile = new File(app.getFilesDir(), FILE);
        load();
    }

    /** The lookup, or null when this build has no TMDB key (the feature is off). */
    public static TmdbArt create(App app) {
        String key = null;
        try (InputStream in = app.getAssets().open("tmdb_key")) {
            byte[] b = new byte[128];
            int n = in.read(b);
            if (n > 0) key = new String(b, 0, n, StandardCharsets.US_ASCII).trim();
        } catch (Throwable ignored) {
            // no key in this build
        }
        if (!validKey(key)) return null;
        Log.i(App.TAG, "tmdb on");
        return new TmdbArt(app, key);
    }

    /** A TMDB v3 key: 32 hex digits. */
    public static boolean validKey(String k) {
        return k != null && k.matches("[0-9a-fA-F]{32}");
    }

    /** Cards that get TMDB key art: Continue watching (episode stills, or no art). */
    public static boolean wants(Card k) {
        return k != null && k.kind == Card.PROGRAM && k.table == Card.T_WATCH_NEXT && !k.title.trim().isEmpty()
                && (k.image == null || !k.image.startsWith(IMG));
    }

    static String cacheKey(Card k) {
        boolean ep = k.episodic || TmdbMatch.episodic(k.title);
        return Ranker.normTitle(TmdbMatch.query(k.title)) + "|" + (ep ? "tv" : "any") + "|" + lang();
    }

    private static String lang() {
        String l = Locale.getDefault().toLanguageTag();
        return l.isEmpty() || "und".equals(l) ? "en-US" : l;
    }

    /**
     * Refresh thread: TMDB art already known goes onto the cards now; the rest is looked up in the
     * background, and a refresh follows when something was found.
     */
    public void apply(List<Card> cards) {
        long now = System.currentTimeMillis();
        for (Card k : cards) {
            if (!wants(k)) continue;
            String key = cacheKey(k);
            String[] e;
            synchronized (this) {
                e = mCache.get(key);
            }
            if (e != null) {
                long age = now - Long.parseLong(e[1]);
                if (!e[0].isEmpty() && age < HIT_MS) {
                    use(k, e[0]);
                    continue;
                }
                if (e[0].isEmpty() && age < MISS_MS) continue;
            }
            queue(key, k.title, k.episodic);
        }
    }

    /** The card shows the key art; its own art (the episode still) stays as its other art. */
    static void use(Card k, String path) {
        if (k.image != null && !k.image.isEmpty()) {
            k.image2 = k.image;
            k.aspect2 = k.aspect;
        }
        k.image = IMG + "w780" + path;
        k.aspect = Card.A_16_9;
    }

    private synchronized void queue(String key, String title, boolean episodic) {
        Long f = mFailed.get(key);
        if (f != null && System.currentTimeMillis() - f < RETRY_MS) return;
        if (!mPending.add(key)) return;
        if (mWorker == null) {
            HandlerThread t = new HandlerThread("z9x-home-tmdb", Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            mWorker = new Handler(t.getLooper());
        }
        mWorker.post(() -> lookup(key, title, episodic));
    }

    private void lookup(String key, String title, boolean episodic) {
        String path = null;
        boolean failed = false;
        try {
            String q = TmdbMatch.query(title);
            TmdbMatch.Result r = search(q, title, episodic, lang());
            if (r == null && !lang().startsWith("en")) r = search(q, title, episodic, "en-US");
            path = r != null ? r.backdrop : "";
            Log.i(App.TAG, "tmdb \"" + q + "\" -> " + (r == null ? "none" : r.type + " " + r.year + " " + r.backdrop));
        } catch (Throwable t) {
            failed = true;
            Log.w(App.TAG, "tmdb lookup failed: " + t.getClass().getSimpleName());
        }
        synchronized (this) {
            mPending.remove(key);
            if (failed) {
                mFailed.put(key, System.currentTimeMillis());
                return;
            }
            mCache.put(key, new String[]{path, Long.toString(System.currentTimeMillis())});
            save();
        }
        if (!path.isEmpty()) mApp.repo().refresh("tmdb", 400);
    }

    private TmdbMatch.Result search(String q, String title, boolean episodic, String lang) throws Exception {
        if (q.isEmpty()) return null;
        String url = "https://" + TmdbNet.API_HOST + "/3/search/multi?include_adult=false&page=1&language="
                + URLEncoder.encode(lang, "UTF-8") + "&query=" + URLEncoder.encode(q, "UTF-8") + "&api_key=" + mKey;
        String body = TmdbNet.get(url, 512 * 1024);
        if (body == null) throw new IllegalStateException("tmdb http");
        return TmdbMatch.pick(results(body), title, episodic);
    }

    static List<TmdbMatch.Result> results(String body) throws Exception {
        ArrayList<TmdbMatch.Result> out = new ArrayList<>();
        JSONArray a = new JSONObject(body).optJSONArray("results");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("media_type", "");
            boolean tv = "tv".equals(type);
            String date = o.optString(tv ? "first_air_date" : "release_date", "");
            int year = 0;
            if (date.length() >= 4) {
                try {
                    year = Integer.parseInt(date.substring(0, 4));
                } catch (NumberFormatException ignored) {
                }
            }
            String bd = o.isNull("backdrop_path") ? "" : o.optString("backdrop_path", "");
            out.add(new TmdbMatch.Result(type, o.optString(tv ? "name" : "title", ""),
                    o.optString(tv ? "original_name" : "original_title", ""), bd, o.optDouble("popularity", 0), year));
        }
        return out;
    }

    // ------------------------------------------------------------------ disk cache (key \t path \t time)

    private synchronized void load() {
        try {
            if (!mFile.exists()) return;
            for (String line : new String(Files.readAllBytes(mFile.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String[] p = line.split("\t", -1);
                if (p.length != 3 || !p[2].matches("\\d+")) continue;
                if (!p[1].isEmpty() && !p[1].matches("/[A-Za-z0-9_.-]+")) continue;
                mCache.put(p[0], new String[]{p[1], p[2]});
            }
        } catch (Throwable t) {
            Log.w(App.TAG, "tmdb cache: " + t);
        }
    }

    private void save() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Map.Entry<String, String[]> e : mCache.entrySet()) {
            String[] v = e.getValue();
            long age = now - Long.parseLong(v[1]);
            if (age > (v[0].isEmpty() ? MISS_MS : HIT_MS) || ++n > MAX_ENTRIES) continue;
            sb.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(v[0]).append('\t').append(v[1]).append('\n');
        }
        File tmp = new File(mFile.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            Log.w(App.TAG, "tmdb cache save: " + t);
            return;
        }
        if (!tmp.renameTo(mFile)) tmp.delete();
    }
}
