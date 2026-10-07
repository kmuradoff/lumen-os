package org.z9x.home.search;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.data.Card;
import org.z9x.home.data.TvpSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Content search through installed apps' suggestion providers (SPEC 7.9): every searchable that takes
 * part in global search (Kinopoisk, Spotify today), queried in parallel with a 1.5 s budget each.
 * Needs GLOBAL_SEARCH (privileged). Results carry the provider's intent, validated by IntentGuard on
 * launch.
 */
public final class SuggestProviders {
    private SuggestProviders() {}

    private static final long BUDGET_MS = 1500;
    private static final int LIMIT = 10;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "z9x-home-suggest");
        t.setDaemon(true);
        return t;
    });

    public static final String EXTRA_SUGGEST_INTENT = "suggest";

    public static List<Card> query(Context c, String q) {
        ArrayList<Card> out = new ArrayList<>();
        if (q == null || q.trim().length() < 2) return out;
        List<SearchableInfo> list;
        SearchManager sm = c.getSystemService(SearchManager.class);
        try {
            list = sm.getSearchablesInGlobalSearch();
        } catch (Throwable t) {
            Log.w(App.TAG, "searchables: " + t);
            return out;
        }
        if (list == null) return out;
        ArrayList<Future<List<Card>>> fs = new ArrayList<>();
        for (SearchableInfo si : list) {
            if (si == null || si.getSuggestAuthority() == null) continue;
            ComponentName act = si.getSearchActivity();
            if (act == null || c.getPackageName().equals(act.getPackageName())) continue;
            fs.add(POOL.submit(() -> one(c, sm, si, q)));
        }
        long deadline = SystemClock.uptimeMillis() + BUDGET_MS;
        for (Future<List<Card>> f : fs) {
            try {
                long left = Math.max(1, deadline - SystemClock.uptimeMillis());
                out.addAll(f.get(left, TimeUnit.MILLISECONDS));
            } catch (Throwable t) {
                f.cancel(true);
            }
        }
        return out;
    }

    private static List<Card> one(Context c, SearchManager sm, SearchableInfo si, String q) {
        ArrayList<Card> out = new ArrayList<>();
        long t0 = SystemClock.uptimeMillis();
        String pkg = si.getSearchActivity().getPackageName();
        String label = pkg;
        try {
            label = c.getPackageManager().getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) {
        }
        try (Cursor cu = sm.getSuggestions(si, q, LIMIT)) {
            if (cu == null) return out;
            int cT1 = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1);
            int cT2 = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);
            int cImg = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE);
            int cIcon = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_ICON_1);
            int cAct = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
            int cData = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
            int cId = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
            int cYear = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR);
            int cDur = cu.getColumnIndex(SearchManager.SUGGEST_COLUMN_DURATION);
            int n = 0;
            while (cu.moveToNext() && n < LIMIT) {
                String t1 = cT1 >= 0 ? cu.getString(cT1) : null;
                if (t1 == null || t1.isEmpty()) continue;
                Card k = new Card(Card.PROGRAM, "sg:" + pkg + ":" + n, t1);
                k.pkg = pkg;
                k.appLabel = label;
                ArrayList<String> meta = new ArrayList<>();
                meta.add(label);
                if (cYear >= 0 && !cu.isNull(cYear) && cu.getInt(cYear) > 0) meta.add(String.valueOf(cu.getInt(cYear)));
                if (cDur >= 0 && !cu.isNull(cDur) && cu.getLong(cDur) > 0) {
                    long min = Math.round(cu.getLong(cDur) / 60_000.0);
                    meta.add(min >= 60 ? c.getString(org.z9x.home.R.string.dur_h_min, (int) (min / 60), (int) (min % 60))
                            : c.getString(org.z9x.home.R.string.dur_min, (int) Math.max(1, min)));
                }
                if (meta.size() == 1 && cT2 >= 0 && cu.getString(cT2) != null) meta.add(cu.getString(cT2));
                k.meta = String.join(" · ", meta);
                String img = cImg >= 0 ? cu.getString(cImg) : null;
                if ((img == null || img.isEmpty()) && cIcon >= 0) {
                    String ic = cu.getString(cIcon);
                    if (ic != null && (ic.startsWith("http") || ic.startsWith("content:") || ic.startsWith("android.resource:"))) img = ic;
                }
                k.image = img != null && !img.isEmpty() ? img : null;
                k.aspect = Card.A_16_9;
                String action = cAct >= 0 ? cu.getString(cAct) : null;
                if (action == null) action = si.getSuggestIntentAction();
                if (action == null) action = Intent.ACTION_SEARCH;
                String data = cData >= 0 ? cu.getString(cData) : null;
                if (data == null) data = si.getSuggestIntentData();
                String dataId = cId >= 0 ? cu.getString(cId) : null;
                if (data != null && dataId != null) data = data + "/" + Uri.encode(dataId);
                Intent i = new Intent(action);
                if (data != null) i.setData(Uri.parse(data));
                i.setPackage(pkg);
                i.putExtra(SearchManager.QUERY, q);
                k.intent = i.toUri(Intent.URI_INTENT_SCHEME);
                k.color = TvpSource.placeholder(t1 + pkg);
                out.add(k);
                n++;
            }
            Log.i(App.TAG, "suggest auth=" + si.getSuggestAuthority() + " n=" + n + " ms=" + (SystemClock.uptimeMillis() - t0));
        } catch (Throwable t) {
            Log.w(App.TAG, "suggest " + si.getSuggestAuthority() + ": " + t);
        }
        return out;
    }
}
