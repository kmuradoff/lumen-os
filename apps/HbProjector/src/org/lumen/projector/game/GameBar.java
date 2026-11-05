package org.lumen.projector.game;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.hal.HbFeatures;
import org.lumen.projector.hal.HbFeatures.F;
import org.lumen.projector.source.TifHdmiState;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Panel;
import org.lumen.projector.ui.Theme;

/**
 * HB game bar (devices/goat_elegant/stock analysis): the stock xhplayer GameMenuActivity with the
 * Product_dapengse list, opened by a gear long press while an HDMI input is on screen and the game mode runs
 * (DISPLAY 148 state 0); otherwise the long press opens TV Settings as before (KeyReceiver, HB fork).
 *
 * Bottom bar of cards in the stock order; OK on a card opens its choices above it, OK on a choice sends it, BACK
 * closes the choices, then the bar. Closes 10 s after the last key (30 s on the first show), as stock.
 * Items whose read failed, or whose HbFeatures row is not ON, are not built (rule: never a disabled row).
 * Not built: virtual crosshair and screen shift (PENDING), AMD FreeSync (no feature key on the G0130), menu theme
 * (vendor skins), mini map (not in the HB list).
 *
 * Every HAL call runs on the HAL thread (Hal.run / Hal.query); after each write the whole state is read again.
 */
public final class GameBar extends Panel {
    private static final String TAG = "Z9xGameBar";
    static final String PREFS = "game_bar";
    static final String KEY_SEEN = "seen";

    /** Stock GameModeService.GAME_MODE_DIALOG_LONG_SHOW_TIME, and the first show (GameMenuUtils.isFirstShowGameMenu). */
    private static final long AUTO_HIDE_MS = 10_000, FIRST_AUTO_HIDE_MS = 30_000;
    /** Stock GameModeApi.afterGameModeApi: VRR and the game picture are sent again 200 ms after a speed change. */
    private static final long AFTER_SPEED_MS = 200;
    private static final long GAME_OPTION_WAIT_MS = 6_000, GAME_OPTION_POLL_MS = 100, PENDING_SPEED_MS = 10_000;

    // ================================================================== entry
    /**
     * KeyReceiver (HB fork), gear long press, main thread: the bar when an HDMI input is on screen and the game mode
     * runs, else {@code fallback} (TV Settings).
     */
    public static void gearLong(Context ctx, Runnable fallback) {
        if (!HbFeatures.on(F.GAME_MODE) || !HbFeatures.on(F.GAME_BAR)) {
            fallback.run();
            return;
        }
        final Context app = ctx.getApplicationContext();
        Hal.query((g, g2) -> State.read(app, g, g2), st -> {
            if (st == null || st.src == 0 || !st.running) {
                Log.i(TAG, "gear long press: no game bar (" + (st == null ? "no HAL" : "src " + st.src
                        + " running " + st.running) + "), TV Settings");
                fallback.run();
                return;
            }
            OverlayHost.get(app).show(new GameBar(app, st));
        });
    }

    // ================================================================== state (HAL thread)
    static final class State {
        int src;                              // 1 | 2, 0 = no HDMI input on screen
        boolean running;                      // 148 state 0
        GmpfClient.GameModeOption opt;
        Boolean vrr, dark, boost;
        Integer gamePic, colorSpace, pictureMode, aspect, hdrType, hdrMode;
        GmpfClient.HdmiSignal sig;
        CharSequence device;

        boolean topSpeed() { return opt != null && opt.isTopSpeed(); }
        boolean hfr() { return opt == GmpfClient.GameModeOption.BASIC_HFR || opt == GmpfClient.GameModeOption.TOP_SPEED_HFR; }
        boolean wide() { return aspect != null && (aspect == GmpfClient.AspectRatio.R21_9.wire || aspect == GmpfClient.AspectRatio.R32_9.wire); }
        boolean uhd() { return sig != null && sig.plausible() && sig.width == 3840 && sig.height == 2160; }

        /** HAL thread. Never throws; a failed read leaves its field null (the item is then not built). */
        static State read(Context app, GmpfClient g, Gmpf2Client g2) {
            State s = new State();
            Integer src = opt("696", g::getCurrentInputSource);
            if (src != null && (src == GmpfClient.SOURCE_HDMI1 || src == GmpfClient.SOURCE_HDMI2)) {
                s.src = src;
            } else {
                int[] ports = TifHdmiState.readPorts(app);
                if (ports != null && ports.length > 0 && (ports[0] == 1 || ports[0] == 2)) s.src = ports[0];
            }
            if (s.src == 0) return s;
            int[] prop = opt("148", g2::getGameModeProp);
            s.running = prop != null && prop.length > 0 && prop[0] == 0;
            if (!s.running) return s;
            final int src0 = s.src;
            s.opt = opt("77", g::getGameModeOption);
            if (HbFeatures.on(F.GAME_VRR)) s.vrr = opt("146", () -> g2.getVrr(src0));
            if (HbFeatures.on(F.GAME_PICTURE) || HbFeatures.on(F.GAME_DARK_BOOST)) {
                s.gamePic = opt("148 picture", () -> g2.getGamePicture(src0));
            }
            if (HbFeatures.on(F.GAME_DARK_BOOST) && s.gamePic != null) {
                final int pic = s.gamePic;
                s.dark = opt("150", () -> g2.getDarkBoost(pic, src0));
            }
            s.hdrType = opt("78", g::getHdrType);
            if (HbFeatures.on(F.PICTURE)) {
                s.pictureMode = opt("34", g::getPictureModeRaw);
                if (s.pictureMode != null && s.pictureMode >= 0 && s.pictureMode <= 40) {
                    final int pm = s.pictureMode;
                    s.colorSpace = opt("103", () -> g.getColorSpaceRaw(src0, pm));
                }
                s.boost = opt("152", g2::isBoost);
            }
            s.aspect = opt("36", g::getAspectRatioRaw);
            if (HbFeatures.on(F.GAME_HDR)) {
                Boolean hgig = opt("192", g2::getHgig);
                Boolean map = opt("175", g2::getHdrDynamicToneMapping);
                if (hgig != null && map != null) s.hdrMode = hgig ? HDR_HGIG : map ? HDR_MAPPING : HDR_OFF;
            }
            s.sig = opt("38", g::getHdmiInfo);
            s.device = deviceName(app, src0);
            return s;
        }
    }

    /** The CEC device behind HDMI {@code src} (the TIF child input of that port), or null. Worker thread. */
    private static CharSequence deviceName(Context app, int src) {
        try {
            TvInputManager tim = app.getSystemService(TvInputManager.class);
            if (tim == null) return null;
            String parent = INPUT_ID_PREFIX + src;
            for (TvInputInfo i : tim.getTvInputList()) {
                if (!parent.equals(i.getParentId())) continue;
                CharSequence l = i.loadCustomLabel(app);
                if (TextUtils.isEmpty(l)) l = i.loadLabel(app);
                if (!TextUtils.isEmpty(l)) return l;
            }
        } catch (Throwable t) {
            Log.w(TAG, "device name: " + t);
        }
        return null;
    }

    /** source.SourceOverlay.INPUT_ID_PREFIX: org.lumen.tvinput's HDMI input of port n. */
    private static final String INPUT_ID_PREFIX = "org.lumen.tvinput/.HdmiInputService/HW";

    interface Read<T> { T get() throws Exception; }

    static <T> T opt(String what, Read<T> r) {
        try {
            return r.get();
        } catch (Throwable t) {
            Log.w(TAG, what + ": " + t);
            return null;
        }
    }

    // ================================================================== items
    private static final int HDR_OFF = 0, HDR_MAPPING = 1, HDR_HGIG = 2;
    private static final GmpfClient.ColorSpace[] CS_ORDER = {GmpfClient.ColorSpace.NATIVE,
            GmpfClient.ColorSpace.BT2020, GmpfClient.ColorSpace.ADOBE_RGB, GmpfClient.ColorSpace.DCI_P3,
            GmpfClient.ColorSpace.REC709};
    private static final GmpfClient.AspectRatio[] ASPECT_ORDER = {GmpfClient.AspectRatio.AUTO,
            GmpfClient.AspectRatio.R16_9, GmpfClient.AspectRatio.R21_9, GmpfClient.AspectRatio.R32_9};

    /** One card: its choices, the current one (-1 = shown as the raw label) and what a choice does. */
    private static final class Item {
        final String key;
        final CharSequence title;
        final CharSequence[] labels;
        final CharSequence[] tips;
        int current;
        CharSequence other;                    // label for a current value outside the choices (4:3, Original)
        TextView card, value;

        Item(String key, CharSequence title, CharSequence[] labels, CharSequence[] tips, int current) {
            this.key = key;
            this.title = title;
            this.labels = labels;
            this.tips = tips;
            this.current = current;
        }

        CharSequence valueLabel() {
            return current >= 0 && current < labels.length ? labels[current] : other != null ? other : "";
        }
    }

    private final Context app;
    private final SharedPreferences prefs;
    private volatile State st;
    private final ArrayList<Item> items = new ArrayList<>();
    private LinearLayout cards, options;
    private HorizontalScrollView scroll;
    private TextView tip, info;
    private FrameLayout frame;
    private Item open;
    private long autoHide = AUTO_HIDE_MS;

    private GameBar(Context app, State st) {
        this.app = app;
        this.st = st;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_SEEN, false)) autoHide = FIRST_AUTO_HIDE_MS;
    }

    private CharSequence s(int res) { return app.getString(res); }

    private void buildItems(State st) {
        items.clear();
        if (st.opt != null) {
            items.add(new Item("speed", s(R.string.gbar_game_mode),
                    new CharSequence[] {s(R.string.gbar_standard), s(R.string.gbar_top_speed)},
                    new CharSequence[] {null, s(R.string.gbar_tip_top_speed)}, st.topSpeed() ? 1 : 0));
        }
        if (st.vrr != null && st.opt != null) {
            items.add(new Item("vrr", s(R.string.gbar_vrr), new CharSequence[] {s(R.string.gbar_off), s(R.string.gbar_on)},
                    new CharSequence[] {null, s(R.string.gbar_tip_vrr)}, st.vrr ? 1 : 0));
        }
        if (st.opt != null) {
            items.add(new Item("hfr", s(R.string.gbar_hfr), new CharSequence[] {s(R.string.gbar_off), s(R.string.gbar_auto)},
                    new CharSequence[] {s(R.string.gbar_tip_hfr_off), s(R.string.gbar_tip_hfr_on)}, st.hfr() ? 1 : 0));
        }
        if (HbFeatures.on(F.GAME_PICTURE) && st.gamePic != null) {
            items.add(new Item("picture", s(R.string.gbar_picture), new CharSequence[] {s(R.string.gbar_pic_standard),
                    "FPS", "RPG", "RTS", s(R.string.gbar_pic_sports)}, null, st.gamePic));
        }
        // ProPicture rule: no colour space in the AI picture modes 10..17 or under an HDR source
        boolean csLocked = (st.pictureMode != null && st.pictureMode >= 10 && st.pictureMode <= 17)
                || (st.hdrType != null && st.hdrType != GmpfClient.HDR_SDR);
        if (st.colorSpace != null && !csLocked) {
            int cs = -1;
            for (int i = 0; i < CS_ORDER.length; i++) if (CS_ORDER[i].wire == st.colorSpace) cs = i;
            if (cs >= 0) {
                items.add(new Item("cs", s(R.string.gbar_color_space), new CharSequence[] {s(R.string.gbar_cs_native),
                        "BT.2020", "Adobe RGB", "DCI-P3", "Rec.709"}, null, cs));
            }
        }
        if (st.dark != null) {
            items.add(new Item("dark", s(R.string.gbar_dark), new CharSequence[] {s(R.string.gbar_off), s(R.string.gbar_on)},
                    new CharSequence[] {null, s(R.string.gbar_tip_dark)}, st.dark ? 1 : 0));
        }
        if (st.aspect != null) {
            int as = -1;
            for (int i = 0; i < ASPECT_ORDER.length; i++) if (ASPECT_ORDER[i].wire == st.aspect) as = i;
            Item a = new Item("aspect", s(R.string.gbar_aspect), new CharSequence[] {s(R.string.gbar_auto), "16:9", "21:9", "32:9"},
                    new CharSequence[] {null, null, s(R.string.gbar_tip_wide), s(R.string.gbar_tip_wide)}, as);
            if (st.aspect == GmpfClient.AspectRatio.R4_3.wire) a.other = s(R.string.panel_aspect_4_3);
            else if (st.aspect == GmpfClient.AspectRatio.ORIGINAL.wire) a.other = s(R.string.panel_aspect_original);
            items.add(a);
        }
        if (HbFeatures.on(F.GAME_FPS)) {
            items.add(new Item("fps", s(R.string.gbar_fps), new CharSequence[] {s(R.string.gbar_off),
                    s(R.string.gbar_fps_left), s(R.string.gbar_fps_center), s(R.string.gbar_fps_right)}, null,
                    FpsOverlay.position(app)));
        }
        if (st.hdrMode != null) {
            items.add(new Item("hdr", s(R.string.gbar_hdr), new CharSequence[] {s(R.string.gbar_off),
                    s(R.string.gbar_hdr_mapping), s(R.string.gbar_hdr_hgig)}, new CharSequence[] {null,
                    s(R.string.gbar_tip_hdr_mapping), s(R.string.gbar_tip_hgig)}, st.hdrMode));
        }
    }

    // ================================================================== Panel
    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        return OverlayHost.fullscreenParams(true);
    }

    @Override
    protected long autoHideMs() { return autoHide; }

    @Override
    protected View onCreateView(Context c) {
        frame = new FrameLayout(c);
        // the bottom band
        View band = new View(c);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF0000000, 0xB0000000, 0x00000000});
        band.setBackground(bg);
        frame.addView(band, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(c, 420),
                Gravity.BOTTOM));
        // HDMI info, top left
        info = new TextView(c);
        info.setTextColor(Theme.TEXT);
        info.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, 26));
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(Theme.PANEL_BG);
        ib.setCornerRadius(Theme.pxf(c, 20));
        info.setBackground(ib);
        int ip = Theme.px(c, 20);
        info.setPadding(ip, ip / 2, ip, ip / 2);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        ilp.leftMargin = Theme.px(c, 20);
        ilp.topMargin = Theme.px(c, 16);
        frame.addView(info, ilp);
        // the choices of the open card and its tip
        options = new LinearLayout(c);
        options.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable ob = new GradientDrawable();
        ob.setColor(Theme.PANEL_BG);
        ob.setCornerRadius(Theme.pxf(c, Theme.ROW_RADIUS));
        ob.setStroke(Theme.px(c, Theme.STROKE), Theme.PANEL_STROKE);
        options.setBackground(ob);
        int op = Theme.px(c, 10);
        options.setPadding(op, op, op, op);
        options.setVisibility(View.GONE);
        frame.addView(options, new FrameLayout.LayoutParams(Theme.px(c, 420), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        tip = new TextView(c);
        tip.setTextColor(Theme.TEXT_DIM);
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, 24));
        tip.setVisibility(View.GONE);
        frame.addView(tip, new FrameLayout.LayoutParams(Theme.px(c, 420), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START));
        // the cards
        scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        int sp = Theme.px(c, 48);
        scroll.setPadding(sp, 0, sp, 0);
        cards = new LinearLayout(c);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(cards, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        slp.bottomMargin = Theme.px(c, 48);
        frame.addView(scroll, slp);
        rebuild(st, null);
        return frame;
    }

    @Override
    protected void onShown(View root) {
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(Theme.PANEL_FADE_MS).start();
        prefs.edit().putBoolean(KEY_SEEN, true).apply();
        FpsOverlay.hideForBar(app, true);
    }

    @Override
    protected void animateOut(View root, Runnable end) {
        root.animate().cancel();
        root.animate().alpha(0f).setDuration(Theme.PANEL_EXIT_MS).withEndAction(end).start();
    }

    @Override
    protected void onDismissed() {
        FpsOverlay.hideForBar(app, false);
    }

    @Override
    protected boolean onBack() {
        if (open != null) {
            closeOptions();
            return true;
        }
        return false;
    }

    // ================================================================== views
    /** Builds the cards from {@code s}; keeps the focus on the card with key {@code focusKey} (null = first). */
    private void rebuild(State s, String focusKey) {
        buildItems(s);
        cards.removeAllViews();
        Context c = cards.getContext();
        View focus = null, first = null;
        for (Item it : items) {
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int p = Theme.px(c, 24);
            card.setPadding(p, p / 2, p, p / 2);
            card.setFocusable(true);
            TextView title = text(c, it.title, Theme.ROW_VALUE_SIZE, Theme.TEXT_DIM, false);
            TextView value = text(c, it.valueLabel(), Theme.ROW_TITLE_SIZE, Theme.TEXT, true);
            card.addView(title);
            card.addView(value);
            it.value = value;
            card.setBackground(cardBg(c, false));
            card.setOnFocusChangeListener((v, has) -> {
                v.setBackground(cardBg(c, has));
                title.setTextColor(has ? Theme.TEXT_FOCUSED_DIM : Theme.TEXT_DIM);
                value.setTextColor(has ? Theme.TEXT_FOCUSED : Theme.TEXT);
            });
            card.setOnClickListener(v -> safe("open " + it.key, () -> openOptions(it, v)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Theme.px(c, 300), Theme.px(c, 132));
            lp.rightMargin = Theme.px(c, 16);
            cards.addView(card, lp);
            if (first == null) first = card;
            if (it.key.equals(focusKey)) focus = card;
        }
        final View f = focus != null ? focus : first;
        if (f != null) f.post(f::requestFocus);
        updateInfo(s);
    }

    private void updateInfo(State s) {
        StringBuilder b = new StringBuilder();
        b.append(TextUtils.isEmpty(s.device) ? app.getString(R.string.gbar_hdmi_n, s.src) : s.device);
        if (s.sig != null && s.sig.plausible()) {
            b.append("   ").append(s.sig.width).append('x').append(s.sig.height)
                    .append("   ").append(Math.round(s.sig.frameRateCenti / 100f)).append(" Hz");
        }
        info.setText(b);
    }

    private void openOptions(Item it, View card) {
        String refused = refuseOpen(it);
        if (refused != null) {
            Notify.show(app, it.title, refused);
            return;
        }
        open = it;
        options.removeAllViews();
        Context c = options.getContext();
        View focus = null;
        for (int i = 0; i < it.labels.length; i++) {
            final int idx = i;
            TextView row = text(c, it.labels[i], Theme.ROW_TITLE_SIZE * 0.9f, Theme.TEXT, i == it.current);
            int p = Theme.px(c, 20);
            row.setPadding(p, p / 2, p, p / 2);
            row.setMinHeight(Theme.px(c, 72));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setFocusable(true);
            row.setBackground(rowBg(c, false));
            row.setOnFocusChangeListener((v, has) -> {
                v.setBackground(rowBg(c, has));
                ((TextView) v).setTextColor(has ? Theme.TEXT_FOCUSED : Theme.TEXT);
                showTip(it, idx, has);
            });
            row.setOnClickListener(v -> safe("choose " + it.key + " " + idx, () -> choose(it, idx)));
            options.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i == it.current || focus == null) focus = row;
        }
        for (int i = 0; i < cards.getChildCount(); i++) cards.getChildAt(i).setFocusable(false);
        // place the choices above the card
        int[] loc = new int[2];
        card.getLocationOnScreen(loc);
        int w = Theme.px(c, 420);
        int left = Math.max(Theme.px(c, 20), Math.min(loc[0], frame.getWidth() - w - Theme.px(c, 20)));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) options.getLayoutParams();
        lp.leftMargin = left;
        lp.bottomMargin = frame.getHeight() - loc[1] + Theme.px(c, 16);
        options.setLayoutParams(lp);
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) tip.getLayoutParams();
        tl.leftMargin = left + w + Theme.px(c, 16) + w <= frame.getWidth() ? left + w + Theme.px(c, 16) : Math.max(0, left - w - Theme.px(c, 16));
        tl.bottomMargin = lp.bottomMargin;
        tip.setLayoutParams(tl);
        options.setVisibility(View.VISIBLE);
        final View f = focus;
        f.post(f::requestFocus);
    }

    private void closeOptions() {
        Item it = open;
        open = null;
        options.setVisibility(View.GONE);
        tip.setVisibility(View.GONE);
        options.removeAllViews();
        View back = null;
        for (int i = 0; i < cards.getChildCount(); i++) {
            View v = cards.getChildAt(i);
            v.setFocusable(true);
            if (it != null && i < items.size() && items.get(i) == it) back = v;
        }
        if (back != null) back.requestFocus();
    }

    private void showTip(Item it, int idx, boolean has) {
        CharSequence t = it.tips != null && idx < it.tips.length ? it.tips[idx] : null;
        if (has && !TextUtils.isEmpty(t)) {
            tip.setText(t);
            tip.setVisibility(View.VISIBLE);
        } else if (has) {
            tip.setVisibility(View.GONE);
        }
    }

    private static TextView text(Context c, CharSequence s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable cardBg(Context c, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? Theme.ROW_BG_FOCUSED : Theme.TILE_BG);
        d.setCornerRadius(Theme.pxf(c, Theme.ROW_RADIUS));
        return d;
    }

    private static GradientDrawable rowBg(Context c, boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? Theme.ROW_BG_FOCUSED : Theme.ROW_BG);
        d.setCornerRadius(Theme.pxf(c, Theme.ROW_RADIUS * 0.75f));
        return d;
    }

    private void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.w(TAG, what + ": " + t);
        }
    }

    // ================================================================== rules (stock MutexManager / OtherFactory)
    /** Main thread: why the card's choices may not open now, or null. */
    private String refuseOpen(Item it) {
        State s = st;
        switch (it.key) {
            case "aspect":
                if (Boolean.TRUE.equals(s.vrr)) return app.getString(R.string.gbar_vrr_blocks_aspect);
                return null;
            case "hdr":
                if (s.hdrType == null || s.hdrType != GmpfClient.HDR_HDR10) return app.getString(R.string.gbar_need_hdr);
                return null;
            default:
                return null;
        }
    }

    /** Main thread: why choice {@code idx} of {@code it} is refused now, or null. */
    private String refuseChoice(Item it, int idx) {
        State s = st;
        switch (it.key) {
            case "vrr":
                if (idx == 1 && !s.topSpeed()) return app.getString(R.string.gbar_need_top_speed);
                if (idx == 1 && s.wide()) return app.getString(R.string.gbar_need_16_9);
                return null;
            case "hfr":
                if (idx == 0 && Boolean.TRUE.equals(s.vrr)) return app.getString(R.string.gbar_vrr_blocks_hfr);
                return null;
            case "aspect":
                if (idx >= 2 && !s.uhd()) return app.getString(R.string.gbar_need_4k);
                return null;
            case "cs":
                if (Boolean.TRUE.equals(s.boost)) return app.getString(R.string.gbar_boost_blocks);
                return null;
            default:
                return null;
        }
    }

    private void choose(Item it, int idx) {
        if (idx == it.current) {
            closeOptions();
            return;
        }
        String refused = refuseChoice(it, idx);
        if (refused != null) {
            Notify.show(app, it.title, refused);
            return;
        }
        it.current = idx;
        it.value.setText(it.valueLabel());
        closeOptions();
        final String key = it.key;
        if ("fps".equals(key)) {
            FpsOverlay.setPosition(app, idx);
            return;
        }
        final State s = st;
        final int src = s.src;
        Hal.run((g, g2) -> {
            boolean ok;
            try {
                ok = send(g, g2, s, key, idx, src);
            } catch (Throwable t) {
                Log.w(TAG, key + " " + idx + ": " + t);
                ok = false;
            }
            if (!ok) Notify.show(app, s(R.string.gbar_failed));
            State now = State.read(app, g, g2);
            Ui.main().post(() -> {
                if (!isShowing()) return;
                st = now;
                if (now.src == 0 || !now.running) {
                    dismiss();
                    return;
                }
                rebuild(now, key);
            });
        });
    }

    /** HAL thread: one choice, as the stock OtherViewModel / GameModeApi sends it. true = accepted. */
    private boolean send(GmpfClient g, Gmpf2Client g2, State s, String key, int idx, int src) throws Exception {
        switch (key) {
            case "speed": {
                boolean top = idx == 1;
                // stock GameModeFactory: back to standard with VRR on while VRR needs top speed -> VRR off first
                if (!top && Boolean.TRUE.equals(s.vrr)) Log.i(TAG, "145(0) before standard -> " + g2.setVrr(false, src));
                boolean hfr = s.hfr();
                GmpfClient.GameModeOption o = top ? (hfr ? GmpfClient.GameModeOption.TOP_SPEED_HFR : GmpfClient.GameModeOption.TOP_SPEED)
                        : (hfr ? GmpfClient.GameModeOption.BASIC_HFR : GmpfClient.GameModeOption.BASIC);
                if (!setOption(g, o)) return false;
                if (top) topSpeedKeystone(o, SystemClock.uptimeMillis() + PENDING_SPEED_MS);
                afterSpeed(src);
                return true;
            }
            case "hfr": {
                GmpfClient.GameModeOption cur = s.opt;
                if (cur == null) return false;
                GmpfClient.GameModeOption o = cur;
                if (idx == 1 && cur == GmpfClient.GameModeOption.BASIC) o = GmpfClient.GameModeOption.BASIC_HFR;
                if (idx == 1 && cur == GmpfClient.GameModeOption.TOP_SPEED) o = GmpfClient.GameModeOption.TOP_SPEED_HFR;
                if (idx == 0 && cur == GmpfClient.GameModeOption.BASIC_HFR) o = GmpfClient.GameModeOption.BASIC;
                if (idx == 0 && cur == GmpfClient.GameModeOption.TOP_SPEED_HFR) o = GmpfClient.GameModeOption.TOP_SPEED;
                return o == cur || setOption(g, o);
            }
            case "vrr": {
                int r = g2.setVrr(idx == 1, src);
                Log.i(TAG, "145(" + idx + ", " + src + ") -> " + r);
                return g2.getVrr(src) == (idx == 1);
            }
            case "picture": {
                int r = g2.setGamePicture(idx, src);
                Log.i(TAG, "147(" + idx + ", " + src + ") -> " + r);
                return g2.getGamePicture(src) == idx;
            }
            case "cs": {
                int r = g.setColorSpace(CS_ORDER[idx]);
                Log.i(TAG, "102(" + CS_ORDER[idx] + ") -> " + r);
                return true;
            }
            case "dark": {
                if (s.gamePic == null) return false;
                int r = g2.setDarkBoost(idx == 1, s.gamePic, src);
                Log.i(TAG, "149(" + (idx == 1) + ", " + s.gamePic + ", " + src + ") -> " + r);
                return g2.getDarkBoost(s.gamePic, src) == (idx == 1);
            }
            case "aspect": {
                boolean r = g.setAspectRatio(ASPECT_ORDER[idx]);
                Log.i(TAG, "35(" + ASPECT_ORDER[idx] + ") -> " + r);
                return r;
            }
            case "hdr": {
                // stock OtherViewModel.setHdrMode: 0 = 174(0, 0) + 191(false), 1 = 174(0, 1) + 191(false),
                // 2 = 174(0, 0) + 191(true)
                boolean map = g2.setHdrDynamicToneMapping(idx == HDR_MAPPING);
                boolean hgig = g2.setHgig(idx == HDR_HGIG);
                Log.i(TAG, "hdr " + idx + ": 174 -> " + map + ", 191 -> " + hgig);
                return map && hgig;
            }
            default:
                return false;
        }
    }

    /**
     * DISPLAY 76 as the quick panel's game speed (QuickPanelController.onGameSpeed): 76 true = done; false = done only if
     * 77 reads the option within GAME_OPTION_WAIT_MS (77 lags a write by seconds).
     */
    private boolean setOption(GmpfClient g, GmpfClient.GameModeOption o) throws Exception {
        boolean r = g.setGameModeOption(o);
        GmpfClient.GameModeOption now = g.getGameModeOption();
        Log.i(TAG, "76(" + o + ") -> " + r + ", 77 reads " + now);
        if (!r && now != o) {
            long end = SystemClock.uptimeMillis() + GAME_OPTION_WAIT_MS;
            while (now != o && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(GAME_OPTION_POLL_MS);
                try { now = g.getGameModeOption(); } catch (Throwable t) { Log.w(TAG, "77: " + t); }
            }
            if (now != o) return false;
        }
        return true;
    }

    /** Stock afterGameModeApi: 200 ms after a speed change, VRR and the game picture of the input are sent again. */
    private void afterSpeed(int src) {
        Hal.runDelayed((g, g2) -> {
            if (HbFeatures.on(F.GAME_VRR)) {
                boolean v = g2.getVrr(src);
                Log.i(TAG, "after speed: 145(" + v + ") -> " + g2.setVrr(v, src));
            }
            if (HbFeatures.on(F.GAME_PICTURE)) {
                int p = g2.getGamePicture(src);
                Log.i(TAG, "after speed: 147(" + p + ") -> " + g2.setGamePicture(p, src));
            }
        }, AFTER_SPEED_MS);
    }

    /** DISPLAY 38 for top speed once 77 reads it (as the quick panel; re-polled every 300 ms, never blocking). */
    private void topSpeedKeystone(GmpfClient.GameModeOption o, long deadline) {
        Hal.runDelayed((g, g2) -> {
            GmpfClient.GameModeOption now = g.getGameModeOption();
            if (now == o) {
                g.resetKeystoneForTopSpeedGame();
                Log.i(TAG, "38 after 77 reads " + o);
                Ui.main().post(() -> Notify.show(app, s(R.string.panel_speed_top_note)));
            } else if (SystemClock.uptimeMillis() < deadline) {
                topSpeedKeystone(o, deadline);
            } else {
                Log.w(TAG, "38 skipped: 77 still reads " + now + " instead of " + o);
            }
        }, 300);
    }
}
