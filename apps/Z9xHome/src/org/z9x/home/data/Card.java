package org.z9x.home.data;

import java.util.Objects;

/**
 * One tile of the home screen. Built on the io thread, then handed to the UI; after that only
 * {@link #color} (the dominant colour of the decoded art) is updated, on the main thread.
 */
public final class Card {
    public static final int APP = 1;
    public static final int PROGRAM = 2;
    public static final int INPUT = 3;
    public static final int TILE = 4;
    public static final int CAST = 5;
    public static final int FEATURE = 6;
    public static final int MORE_APPS = 7;

    // aspect classes (TvContract ratios mapped to our card sizes)
    public static final int A_16_9 = 0;
    public static final int A_3_2 = 1;
    public static final int A_4_3 = 2;
    public static final int A_1_1 = 3;
    public static final int A_2_3 = 4;

    public static final int T_NONE = 0;
    public static final int T_PREVIEW = 1;
    public static final int T_WATCH_NEXT = 2;

    public int kind;
    public String id = "";            // stable key (component, program id, input id ...)
    public String title = "";
    public String meta = "";          // second line under the focused card
    public String desc = "";          // hero description
    public String pkg = "";           // owning package
    public String appLabel = "";      // owning app's label
    public String image;              // art uri (app:, icon:, https:, content:, android.resource:)
    public int aspect = A_16_9;
    public int color;                 // placeholder / dominant colour (ARGB)
    public String intent;             // intent URI (programs), component (apps), action (tiles)
    public int progress = -1;         // permille, -1 = none
    public boolean live;
    public boolean isNew;
    public boolean system;            // app: system app (no uninstall)
    public int table = T_NONE;
    public long programId = -1;
    public long channelId = -1;
    public int wnType = -1;           // watch_next_type
    public long engaged;              // last engagement (watch next)
    public long weight;               // preview program weight
    public String video;              // preview_video_uri (hero trailers)
    public String contentId = "";
    public int icon;                  // drawable res id (tiles, inputs, casts, features)
    public int state;                 // input: TvInputManager state; cast: 0 ready
    public boolean showing;           // input currently on screen

    public Card() {}

    public Card(int kind, String id, String title) {
        this.kind = kind;
        this.id = id;
        this.title = title == null ? "" : title;
    }

    /** Everything that changes what is drawn (colour excluded: it is derived from the art). */
    public boolean sameContent(Card o) {
        return o != null && kind == o.kind && id.equals(o.id) && title.equals(o.title) && meta.equals(o.meta)
                && Objects.equals(image, o.image) && aspect == o.aspect && progress == o.progress
                && live == o.live && isNew == o.isNew && Objects.equals(intent, o.intent) && icon == o.icon
                && state == o.state && showing == o.showing && desc.equals(o.desc) && appLabel.equals(o.appLabel)
                && pkg.equals(o.pkg);
    }

    public boolean isProgram() {
        return kind == PROGRAM;
    }
}
