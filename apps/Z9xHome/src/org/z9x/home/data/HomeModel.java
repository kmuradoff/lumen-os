package org.z9x.home.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Everything the home screen shows; built on io, then treated as immutable by the UI. */
public final class HomeModel {
    public final ArrayList<Card> hero = new ArrayList<>();
    public final ArrayList<Row> rows = new ArrayList<>();       // For you, in display order
    public final ArrayList<Row> allRows = new ArrayList<>();    // incl. hidden rows (Customize)
    public final ArrayList<Card> favorites = new ArrayList<>();
    public final ArrayList<Card> apps = new ArrayList<>();      // all visible apps, A-Z
    public final ArrayList<Card> hiddenApps = new ArrayList<>();
    public final ArrayList<Card> inputs = new ArrayList<>();
    public final ArrayList<Card> casts = new ArrayList<>();
    public final ArrayList<Card> tiles = new ArrayList<>();
    public final Set<String> packages = new HashSet<>();
    public String tvpMode = "";
    public String locale = "";
    public boolean fromSnapshot;
    public boolean classicAvailable;

    /** Every program card (preview + watch next) for the search screen's local title index. */
    public List<Card> programs() {
        ArrayList<Card> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (Row r : allRows) {
            if (r.type != Row.CHANNEL && r.type != Row.WATCH_NEXT) continue;
            for (Card c : r.cards) if (seen.add(c.id)) out.add(c);
        }
        return out;
    }

    public Card findApp(String component) {
        for (Card c : apps) if (c.id.equals(component)) return c;
        for (Card c : hiddenApps) if (c.id.equals(component)) return c;
        return null;
    }
}
