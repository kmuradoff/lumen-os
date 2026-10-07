package org.z9x.home.data;

import java.util.ArrayList;

/** A horizontal row of cards (For you, Inputs and the search screen). */
public final class Row {
    public static final int APPS = 1;
    public static final int WATCH_NEXT = 2;
    public static final int CHANNEL = 3;
    public static final int INPUTS = 4;
    public static final int PROJECTOR = 5;
    public static final int CAST = 6;
    public static final int CUSTOMIZE = 7;
    public static final int SEARCH_APPS = 8;
    public static final int SEARCH_CONTENT = 9;

    public static final String ID_APPS = "apps";
    public static final String ID_WATCH_NEXT = "wn";
    public static final String ID_INPUTS = "inputs";
    public static final String ID_PROJECTOR = "projector";
    public static final String ID_CAST = "cast";
    public static final String ID_CUSTOMIZE = "customize";

    public int type;
    public String id = "";
    public String title = "";
    public String sub = "";           // " · App name" after the title
    public String pkg = "";
    public long channelId = -1;
    public int aspect = Card.A_16_9;  // card size class of the row
    public boolean hidden;            // only in HomeModel.allRows (Customize)
    public final ArrayList<Card> cards = new ArrayList<>();

    public Row() {}

    public Row(int type, String id, String title) {
        this.type = type;
        this.id = id;
        this.title = title == null ? "" : title;
    }

    public static String channelId(long id) {
        return "ch:" + id;
    }
}
