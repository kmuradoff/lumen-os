package org.z9x.home.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TvProvider content of installed apps (SPEC 5.3): preview channels, preview programs and Watch Next.
 *
 * Full mode: we hold ACCESS_ALL_EPG_DATA (priv-app), so selection and sort are allowed and we see every
 * app's rows. Limited mode (privileged permission missing): no selection, client-side filtering, only
 * searchable rows (READ_TV_LISTINGS) or nothing. Runs on the io thread only.
 */
public final class TvpSource {
    private TvpSource() {}

    public static final String PERM_ALL = "com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA";
    public static final int MAX_PER_CHANNEL = 20;
    public static final int MAX_WATCH_NEXT = 30;
    public static final long WN_MAX_AGE_MS = 60L * 86_400_000L;
    private static final int MAX_TEXT = 200;

    public static final class Channel {
        public long id;
        public String pkg = "";
        public String name = "";
        public String appLink;
        public boolean browsable;
        public boolean system;   // system_channel_key set (Play / recommendations promo channels)
        public boolean firstOfPackage;
    }

    public static final class Result {
        public String mode = "full";
        public final ArrayList<Channel> channels = new ArrayList<>();
        public final Map<Long, List<Card>> programs = new HashMap<>();
        public final ArrayList<Card> watchNext = new ArrayList<>();
    }

    /** "Continue watching on Home" hidden (1.0.1): no TvProvider query at all, Home stays calm. */
    public static Result off() {
        Result r = new Result();
        r.mode = "off";
        return r;
    }

    public static boolean fullAccess(Context c) {
        return c.checkSelfPermission(PERM_ALL) == PackageManager.PERMISSION_GRANTED;
    }

    public static Result load(Context c, Map<String, String> labels) {
        Result r = new Result();
        boolean full = fullAccess(c);
        r.mode = full ? "full" : "limited";
        ContentResolver cr = c.getContentResolver();
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            loadChannels(cr, full, r);
            loadPrograms(c, cr, full, r, labels);
            loadWatchNext(c, cr, full, r, labels);
        } catch (Throwable t) {
            Log.w(App.TAG, "tvp load failed mode=" + r.mode + ": " + t);
        }
        logCounts(r, android.os.SystemClock.uptimeMillis() - t0);
        return r;
    }

    // ------------------------------------------------------------------ channels

    private static final String COL_SYSTEM_KEY = "system_channel_key"; // TvProvider column, not in the SDK
    private static final String[] CH_COLS = {
            TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, TvContract.Channels.COLUMN_BROWSABLE,
            COL_SYSTEM_KEY, TvContract.Channels.COLUMN_TYPE};

    private static boolean noSystemKey;

    private static void loadChannels(ContentResolver cr, boolean full, Result r) {
        String sel = full ? TvContract.Channels.COLUMN_TYPE + "=?" : null;
        String[] args = full ? new String[]{TvContract.Channels.TYPE_PREVIEW} : null;
        String sort = full ? TvContract.Channels._ID + " ASC" : null;
        HashMap<String, Long> first = new HashMap<>();
        String[] cols = CH_COLS;
        Cursor probe;
        try {
            probe = cr.query(TvContract.Channels.CONTENT_URI, cols, sel, args, sort);
        } catch (Throwable t) { // an older TvProvider without system_channel_key
            cols = CH_COLS.clone();
            cols[5] = TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID;
            probe = cr.query(TvContract.Channels.CONTENT_URI, cols, sel, args, sort);
            noSystemKey = true;
        }
        try (Cursor cu = probe) {
            if (cu == null) return;
            while (cu.moveToNext()) {
                if (!TvContract.Channels.TYPE_PREVIEW.equals(cu.getString(6))) continue;
                Channel ch = new Channel();
                ch.id = cu.getLong(0);
                ch.pkg = nn(cu.getString(1));
                ch.name = clip(cu.getString(2));
                ch.appLink = cu.getString(3);
                ch.browsable = cu.getInt(4) == 1;
                ch.system = !noSystemKey && cu.getString(5) != null && !cu.getString(5).isEmpty();
                r.channels.add(ch);
                Long f = first.get(ch.pkg);
                if (f == null || ch.id < f) first.put(ch.pkg, ch.id);
            }
        }
        for (Channel ch : r.channels) ch.firstOfPackage = first.get(ch.pkg) == ch.id;
        Collections.sort(r.channels, (a, b) -> Long.compare(a.id, b.id));
    }

    // ------------------------------------------------------------------ programs

    private static final String[] PG_COLS = {
            TvContract.PreviewPrograms._ID, TvContract.PreviewPrograms.COLUMN_CHANNEL_ID,
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_ASPECT_RATIO,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI, TvContract.PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_LIVE, TvContract.PreviewPrograms.COLUMN_RELEASE_DATE,
            TvContract.PreviewPrograms.COLUMN_CONTENT_ID, TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE};

    private static void loadPrograms(Context c, ContentResolver cr, boolean full, Result r, Map<String, String> labels) {
        String sel = full ? TvContract.PreviewPrograms.COLUMN_BROWSABLE + "=1" : null;
        String sort = full ? TvContract.PreviewPrograms.COLUMN_CHANNEL_ID + " ASC, "
                + TvContract.PreviewPrograms.COLUMN_WEIGHT + " DESC" : null;
        Resources res = c.getResources();
        try (Cursor cu = cr.query(TvContract.PreviewPrograms.CONTENT_URI, PG_COLS, sel, null, sort)) {
            if (cu == null) return;
            while (cu.moveToNext()) {
                if (cu.getInt(20) != 1) continue;
                Card k = program(res, cu, labels, Card.T_PREVIEW);
                k.weight = cu.getLong(19);
                List<Card> l = r.programs.get(k.channelId);
                if (l == null) r.programs.put(k.channelId, l = new ArrayList<>());
                l.add(k);
            }
        }
        for (List<Card> l : r.programs.values()) {
            if (!full) Collections.sort(l, (a, b) -> Long.compare(b.weight, a.weight));
            while (l.size() > MAX_PER_CHANNEL) l.remove(l.size() - 1);
        }
    }

    private static final String[] WN_EXTRA = {
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS};

    private static void loadWatchNext(Context c, ContentResolver cr, boolean full, Result r, Map<String, String> labels) {
        String[] cols = new String[PG_COLS.length + 2];
        System.arraycopy(PG_COLS, 0, cols, 0, PG_COLS.length);
        cols[1] = TvContract.PreviewPrograms._ID; // watch next has no channel_id; keep the index layout
        cols[PG_COLS.length] = WN_EXTRA[0];
        cols[PG_COLS.length + 1] = WN_EXTRA[1];
        String sel = full ? TvContract.WatchNextPrograms.COLUMN_BROWSABLE + "=1" : null;
        String sort = full ? TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC" : null;
        long now = System.currentTimeMillis();
        Resources res = c.getResources();
        try (Cursor cu = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, sel, null, sort)) {
            if (cu == null) return;
            while (cu.moveToNext()) {
                if (cu.getInt(20) != 1) continue;
                long eng = cu.getLong(PG_COLS.length + 1);
                if (eng > 0 && now - eng > WN_MAX_AGE_MS) continue;
                Card k = program(res, cu, labels, Card.T_WATCH_NEXT);
                k.channelId = -1;
                k.wnType = cu.isNull(PG_COLS.length) ? -1 : cu.getInt(PG_COLS.length);
                k.engaged = eng;
                r.watchNext.add(k);
            }
        }
        if (!full) Collections.sort(r.watchNext, (a, b) -> Long.compare(b.engaged, a.engaged));
        while (r.watchNext.size() > MAX_WATCH_NEXT) r.watchNext.remove(r.watchNext.size() - 1);
    }

    private static Card program(Resources res, Cursor cu, Map<String, String> labels, int table) {
        Card k = new Card();
        k.kind = Card.PROGRAM;
        k.table = table;
        k.programId = cu.getLong(0);
        k.channelId = cu.getLong(1);
        k.id = (table == Card.T_WATCH_NEXT ? "wn:" : "pp:") + k.programId;
        k.pkg = nn(cu.getString(2));
        String lab = labels.get(k.pkg);
        k.appLabel = lab != null ? lab : k.pkg;
        k.title = clip(cu.getString(3));
        String episode = clip(cu.getString(4));
        String season = cu.getString(5);
        String ep = cu.getString(6);
        k.desc = clip(cu.getString(7));
        String poster = cu.getString(8);
        String thumb = cu.getString(10);
        if (poster != null && !poster.isEmpty()) {
            k.image = poster;
            k.aspect = aspect(cu, 9);
            // the hero decides by real pixels which of the two to show (Stage, HeroArt)
            if (thumb != null && !thumb.isEmpty() && !thumb.equals(poster)) {
                k.image2 = thumb;
                k.aspect2 = aspect(cu, 11);
            }
        } else if (thumb != null && !thumb.isEmpty()) {
            k.image = thumb;
            k.aspect = aspect(cu, 11);
        }
        k.intent = cu.getString(12);
        k.video = cu.getString(13);
        long dur = cu.getLong(14);
        long pos = cu.getLong(15);
        k.live = cu.getInt(16) == 1;
        String release = cu.getString(17);
        k.contentId = nn(cu.getString(18));
        if (dur > 0 && pos > 0 && pos < dur) {
            k.progress = (int) Math.max(1, Math.min(1000, pos * 1000 / dur));
            if (!k.live) k.left = res.getString(R.string.meta_left, duration(res, dur - pos));
        }
        if (k.title.isEmpty() && !episode.isEmpty()) k.title = episode;
        k.meta = meta(res, season, ep, episode.equals(k.title) ? "" : episode, dur, k.live, release);
        k.color = placeholder(k.title + k.pkg);
        return k;
    }

    private static int aspect(Cursor cu, int col) {
        if (cu.isNull(col)) return Card.A_16_9;
        switch (cu.getInt(col)) {
            case TvContract.PreviewPrograms.ASPECT_RATIO_3_2:
                return Card.A_3_2;
            case TvContract.PreviewPrograms.ASPECT_RATIO_4_3:
                return Card.A_4_3;
            case TvContract.PreviewPrograms.ASPECT_RATIO_1_1:
                return Card.A_1_1;
            case TvContract.PreviewPrograms.ASPECT_RATIO_2_3:
            case 5: // ASPECT_RATIO_MOVIE_POSTER (1:1.441)
                return Card.A_2_3;
            default:
                return Card.A_16_9;
        }
    }

    /** "S1 · E2 · episode · 1 h 30 min" (the time left of a started program is {@link Card#left}). */
    static String meta(Resources res, String season, String ep, String episodeTitle, long dur, boolean live,
                       String release) {
        ArrayList<String> parts = new ArrayList<>(4);
        if (live) parts.add(res.getString(R.string.meta_live_now));
        if (season != null && !season.isEmpty() && ep != null && !ep.isEmpty()) {
            parts.add(res.getString(R.string.meta_season_episode, season, ep));
        } else if (ep != null && !ep.isEmpty()) {
            parts.add(res.getString(R.string.meta_episode, ep));
        }
        if (!episodeTitle.isEmpty() && parts.size() < 2) parts.add(episodeTitle);
        if (!live && release != null && release.length() >= 4 && parts.size() < 2 && release.substring(0, 4).matches("\\d{4}")) {
            parts.add(0, release.substring(0, 4));
        }
        if (!live && dur > 0) parts.add(duration(res, dur));
        return String.join(" · ", parts);
    }

    static String duration(Resources res, long ms) {
        long min = Math.max(1, Math.round(ms / 60_000.0));
        if (min < 60) return res.getString(R.string.dur_min, (int) min);
        return res.getString(R.string.dur_h_min, (int) (min / 60), (int) (min % 60));
    }

    /** Pleasant muted placeholder colours (dark enough for white text, never pure grey). */
    private static final int[] PALETTE = {0xFF22324A, 0xFF2D2A4A, 0xFF1F3B3A, 0xFF3A2A2A, 0xFF2A3A24, 0xFF3A2F1F,
            0xFF1E3046, 0xFF35243F};

    public static int placeholder(String s) {
        return PALETTE[(s.hashCode() & 0x7fffffff) % PALETTE.length];
    }

    private static void logCounts(Result r, long ms) {
        LinkedHashMap<String, int[]> m = new LinkedHashMap<>();
        for (Channel ch : r.channels) {
            int[] a = m.computeIfAbsent(ch.pkg, k -> new int[4]);
            a[0]++;
            if (ch.browsable) a[1]++;
            List<Card> l = r.programs.get(ch.id);
            if (l != null) a[2] += l.size();
        }
        for (Card k : r.watchNext) m.computeIfAbsent(k.pkg, x -> new int[4])[3]++;
        for (Map.Entry<String, int[]> e : m.entrySet()) {
            int[] a = e.getValue();
            Log.i(App.TAG, "tvp pkg=" + e.getKey() + " ch=" + a[1] + "/" + a[0] + " prev=" + a[2] + " wn=" + a[3]);
        }
        Log.i(App.TAG, "tvp mode=" + r.mode + " channels=" + r.channels.size() + " wn=" + r.watchNext.size() + " ms=" + ms);
    }

    // ------------------------------------------------------------------ writes (io thread)

    /** Shows/hides a preview channel for the app too (writes browsable). */
    public static void setChannelBrowsable(Context c, long channelId, boolean on) {
        if (!fullAccess(c)) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(TvContract.Channels.COLUMN_BROWSABLE, on ? 1 : 0);
            int n = c.getContentResolver().update(TvContract.buildChannelUri(channelId), cv, null, null);
            Log.i(App.TAG, "channel " + channelId + " browsable=" + (on ? 1 : 0) + " n=" + n);
        } catch (Throwable t) {
            Log.w(App.TAG, "channel browsable: " + t);
        }
    }

    /** Removes a program from its row and tells the owning app (Twitch listens for Watch Next). */
    public static void hideProgram(Context c, Card k) {
        if (k.programId < 0 || k.pkg.isEmpty()) return;
        boolean wn = k.table == Card.T_WATCH_NEXT;
        Uri uri = wn ? TvContract.buildWatchNextProgramUri(k.programId) : TvContract.buildPreviewProgramUri(k.programId);
        if (fullAccess(c)) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 0);
                c.getContentResolver().update(uri, cv, null, null);
            } catch (Throwable t) {
                Log.w(App.TAG, "program browsable: " + t);
            }
        }
        Intent i = new Intent(wn ? TvContract.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED
                : TvContract.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED);
        i.setPackage(k.pkg);
        i.putExtra(wn ? TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID : TvContract.EXTRA_PREVIEW_PROGRAM_ID, k.programId);
        i.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        c.sendBroadcast(i);
        Log.i(App.TAG, (wn ? "wn-disabled" : "pp-disabled") + " pkg=" + k.pkg + " id=" + k.programId);
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

    static String clip(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT - 1) + "…" : s;
    }
}
