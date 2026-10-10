package org.z9x.home.customize;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.z9x.home.App;
import org.z9x.home.Prefs;
import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.HomeRepository;
import org.z9x.home.data.Row;
import org.z9x.home.data.TvpSource;
import org.z9x.home.proj.ProjectorBridge;
import org.z9x.home.sky.SkySettings;
import org.z9x.home.sky.SkyView;
import org.z9x.home.ui.ContextPanel;
import org.z9x.home.ui.ListPanel;
import org.z9x.home.ui.Theme;
import org.z9x.home.weather.Weather;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Customize Home (SPEC 7.10): rows, row order, favourites, hidden apps, weather (city, units), wallpaper
 * (the living sky's landscape: a new one every day, or one of the scenes; shared with the screensaver),
 * "Continue watching on Home" (show / hide; hidden, Home is only the clock, date and weather over the
 * wallpaper plus the apps, 1.0.1), Spotlight (auto-advance, trailers), home screen (Lumen Home / classic
 * Android TV via the projector's LauncherSwitcher, PLAN C1) and About. A translucent activity over Home
 * with the side panel; every change is a preference write that Home observes.
 */
public class CustomizeActivity extends Activity {
    public static final String EXTRA_PAGE = "page";
    private ContextPanel mPanel;
    private App mApp;
    private Prefs mPrefs;
    private int mCitySeq;
    private ListPanel.Item mWallItem, mContinueItem;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.init(this);
        mApp = App.get();
        mPrefs = mApp.prefs();
        FrameLayout root = new FrameLayout(this);
        mPanel = new ContextPanel(this);
        mPanel.setWidthDesign(640);
        mPanel.setOnClosed(() -> root.postDelayed(this::finish, 200));
        root.addView(mPanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        openRoot();
        if ("city".equals(getIntent().getStringExtra(EXTRA_PAGE))) openCity();
        else if ("weather".equals(getIntent().getStringExtra(EXTRA_PAGE))) openWeather();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (mPanel.onKey(e.getKeyCode(), e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private HomeModel model() {
        HomeModel m = mApp.repo().last();
        return m != null ? m : new HomeModel();
    }

    private void openRoot() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_rows, getString(R.string.cz_rows), this::openRows));
        l.add(ListPanel.Item.action(R.drawable.ic_reorder, getString(R.string.cz_reorder), this::openReorder));
        l.add(ListPanel.Item.action(R.drawable.ic_star, getString(R.string.cz_favorites), this::openFavorites));
        l.add(ListPanel.Item.action(R.drawable.ic_eye_off, getString(R.string.cz_hidden_apps), this::openHidden));
        l.add(ListPanel.Item.action(R.drawable.ic_cloud, getString(R.string.weather), this::openWeather));
        mWallItem = ListPanel.Item.action(R.drawable.ic_picture, getString(R.string.cz_wallpaper), this::openWallpaper)
                .value(SkyView.sceneTitle(this, SkySettings.scene(this)));
        l.add(mWallItem);
        mContinueItem = ListPanel.Item.action(R.drawable.ic_history, getString(R.string.cz_continue), this::openContinue)
                .value(continueLabel());
        l.add(mContinueItem);
        l.add(ListPanel.Item.action(R.drawable.ic_spotlight, getString(R.string.cz_spotlight), this::openSpotlight));
        l.add(ListPanel.Item.action(R.drawable.ic_home, getString(R.string.cz_home_screen), this::openHomeScreen)
                .value(getString(R.string.lumen_home)));
        l.add(ListPanel.Item.action(R.drawable.ic_info, getString(R.string.cz_about), this::openAbout));
        mPanel.open(getString(R.string.customize_home), getString(R.string.cz_subtitle), l);
    }

    // ------------------------------------------------------------------ rows

    private void openRows() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        for (Row r : model().allRows) {
            if (r.type == Row.CUSTOMIZE) continue;
            String text = r.sub.isEmpty() ? r.title : r.title + " · " + r.sub;
            ListPanel.Item it = ListPanel.Item.action(0, text, null).kind(ListPanel.TOGGLE).on(!r.hidden);
            it.action = () -> setRowShown(r, it.on);
            l.add(it);
        }
        if (l.isEmpty()) l.add(ListPanel.Item.info(0, getString(R.string.cz_rows_empty)));
        mPanel.push(getString(R.string.cz_rows), getString(R.string.cz_rows_sub), l);
    }

    private void setRowShown(Row r, boolean shown) {
        if (r.type == Row.CHANNEL) {
            mPrefs.toggleInSet(Prefs.K_HIDDEN_ROWS, r.id, !shown);
            mPrefs.toggleInSet(Prefs.K_SHOWN_ROWS, r.id, shown);
            final long ch = r.channelId;
            mApp.io().post(() -> TvpSource.setChannelBrowsable(this, ch, shown));
        } else {
            mPrefs.toggleInSet(Prefs.K_HIDDEN_ROWS, r.id, !shown);
        }
    }

    private void openReorder() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        for (Row r : model().rows) {
            if (r.type == Row.CUSTOMIZE) continue;
            ListPanel.Item it = ListPanel.Item.action(0, r.sub.isEmpty() ? r.title : r.title + " · " + r.sub, null)
                    .kind(ListPanel.REORDER);
            it.tag = r.id;
            l.add(it);
        }
        mPanel.push(getString(R.string.cz_reorder), getString(R.string.cz_reorder_sub), l);
        // the move listener is on the panel's list; ContextPanel exposes it through the items' tags
        ListPanel lp = findListPanel();
        if (lp != null) lp.setMoveListener(items -> {
            ArrayList<String> ids = new ArrayList<>();
            for (ListPanel.Item it : items) if (it.tag instanceof String) ids.add((String) it.tag);
            mPrefs.putList(Prefs.K_ROW_ORDER, keepUnlisted(ids));
        });
    }

    /**
     * Rows that are not in the list (hidden with "Continue watching on Home", or empty right now) keep
     * their place in the saved order; "Continue watching" that was never ordered keeps its natural place,
     * first. Else a reorder while it is hidden would put it below the projector row once it is shown.
     */
    private List<String> keepUnlisted(List<String> ids) {
        ArrayList<String> out = new ArrayList<>(ids);
        List<String> old = mPrefs.list(Prefs.K_ROW_ORDER);
        for (int i = 0; i < old.size(); i++) {
            String id = old.get(i);
            if (!out.contains(id)) out.add(Math.min(i, out.size()), id);
        }
        if (!out.contains(Row.ID_WATCH_NEXT)) out.add(0, Row.ID_WATCH_NEXT);
        return out;
    }

    private ListPanel findListPanel() {
        for (int i = 0; i < mPanel.getChildCount(); i++) {
            if (mPanel.getChildAt(i) instanceof ListPanel) return (ListPanel) mPanel.getChildAt(i);
        }
        return null;
    }

    // ------------------------------------------------------------------ apps

    private void openFavorites() {
        HomeModel m = model();
        ArrayList<String> fav = new ArrayList<>();
        for (Card c : m.favorites) fav.add(c.id);
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        for (Card a : m.apps) {
            ListPanel.Item it = ListPanel.Item.action(0, a.title, null).kind(ListPanel.CHECK).on(fav.contains(a.id));
            it.action = () -> {
                it.on = !it.on;
                if (it.on) {
                    if (!fav.contains(a.id)) fav.add(a.id);
                } else fav.remove(a.id);
                mPrefs.putList(Prefs.K_FAVORITES, fav);
                mPrefs.putBool(Prefs.K_FAVORITES_SET, true);
                refreshItem(it);
            };
            l.add(it);
        }
        mPanel.push(getString(R.string.cz_favorites), getString(R.string.cz_favorites_sub), l);
    }

    private void openHidden() {
        HomeModel m = model();
        ArrayList<Card> all = new ArrayList<>(m.apps);
        all.addAll(m.hiddenApps);
        all.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        Set<String> hiddenIds = new java.util.HashSet<>();
        for (Card c : m.hiddenApps) hiddenIds.add(c.id);
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        for (Card a : all) {
            ListPanel.Item it = ListPanel.Item.action(0, a.title, null).kind(ListPanel.TOGGLE).on(hiddenIds.contains(a.id));
            it.action = () -> {
                mPrefs.toggleInSet(Prefs.K_HIDDEN_APPS, a.id, it.on);
                mPrefs.toggleInSet(HomeRepository.K_UNHIDDEN_APPS, a.id, !it.on);
                if (!it.on) mPrefs.toggleInSet(Prefs.K_HIDDEN_APPS, a.pkg, false);
            };
            l.add(it);
        }
        mPanel.push(getString(R.string.cz_hidden_apps), getString(R.string.cz_hidden_sub), l);
    }

    private void refreshItem(ListPanel.Item it) {
        ListPanel lp = findListPanel();
        if (lp != null) lp.refresh(it);
    }

    // ------------------------------------------------------------------ weather

    private void openWeather() {
        mPanel.push(getString(R.string.weather), null, weatherItems());
    }

    private List<ListPanel.Item> weatherItems() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        ListPanel.Item on = ListPanel.Item.action(0, getString(R.string.weather_show), null).kind(ListPanel.TOGGLE).on(mPrefs.weatherOn());
        on.action = () -> mPrefs.putBool(Prefs.K_WEATHER_ON, on.on);
        l.add(on);
        boolean manual = Weather.manualCity() != null;
        l.add(ListPanel.Item.action(R.drawable.ic_location, getString(R.string.weather_city), this::openCity)
                .value(manual ? Weather.cityName(this) : getString(R.string.weather_city_auto)));
        ListPanel.Item units = ListPanel.Item.action(R.drawable.ic_thermometer, getString(R.string.weather_units), null)
                .value(unitsLabel());
        units.action = () -> {
            String cur = mPrefs.units();
            mPrefs.putStr(Prefs.K_UNITS, "auto".equals(cur) ? "c" : ("c".equals(cur) ? "f" : "auto"));
            units.value = unitsLabel();
            refreshItem(units);
        };
        l.add(units);
        l.add(ListPanel.Item.info(0, getString(R.string.weather_privacy)));
        return l;
    }

    private String unitsLabel() {
        String u = mPrefs.units();
        return getString("c".equals(u) ? R.string.units_c : ("f".equals(u) ? R.string.units_f : R.string.units_auto));
    }

    private void openCity() {
        EditText e = new EditText(this);
        Theme.text(e, 30, Theme.REGULAR, Theme.TEXT1);
        e.setHint(R.string.weather_city_hint);
        e.setHintTextColor(Theme.TEXT3);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(Theme.px(28), 0, Theme.px(28), 0);
        e.setTextCursorDrawable(R.drawable.cursor);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE2);
        bg.setCornerRadius(Theme.pxf(38));
        e.setBackground(bg);
        Runnable search = () -> searchCity(e.getText().toString());
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                e.removeCallbacks(search);
                e.postDelayed(search, 500);
            }
        });
        e.setOnEditorActionListener((v, id, ev) -> {
            e.removeCallbacks(search);
            search.run();
            return true;
        });
        mPanel.pushEditor(getString(R.string.weather_city), getString(R.string.weather_city_sub), e, cityItems(new ArrayList<>()));
    }

    private List<ListPanel.Item> cityItems(List<Weather.City> found) {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        boolean manual = Weather.manualCity() != null;
        l.add(ListPanel.Item.action(R.drawable.ic_location, getString(R.string.weather_city_auto), () -> {
            Weather.setManualCity(null);
            Weather.invalidate(this);
            mPanel.close();
        }).kind(ListPanel.CHECK).on(!manual));
        for (Weather.City c : found) {
            l.add(ListPanel.Item.action(0, c.name, () -> {
                Weather.setManualCity(c);
                Weather.invalidate(this);
                mPanel.close();
            }).value(c.detail));
        }
        return l;
    }

    private void searchCity(String q) {
        final int seq = ++mCitySeq;
        final String lang = Locale.getDefault().getLanguage();
        mApp.io().post(() -> {
            List<Weather.City> r;
            try {
                r = Weather.search(q, lang, 8);
            } catch (Throwable t) {
                r = new ArrayList<>();
            }
            final List<Weather.City> res = r;
            runOnUiThread(() -> {
                if (seq == mCitySeq) mPanel.replace(cityItems(res));
            });
        });
    }

    // ------------------------------------------------------------------ wallpaper

    /** "Обои": a new landscape every day (auto) or one scene; Home and the screensaver read SkySettings. */
    private void openWallpaper() {
        String cur = SkySettings.scene(this);
        ArrayList<String> ids = new ArrayList<>();
        ids.add(SkyView.AUTO);
        for (String id : SkyView.sceneIds()) ids.add(id);
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        for (String id : ids) {
            ListPanel.Item it = ListPanel.Item.action(0, SkyView.sceneTitle(this, id), null).kind(ListPanel.CHECK).on(id.equals(cur));
            it.tag = id;
            l.add(it);
        }
        for (ListPanel.Item it : l) {
            it.action = () -> {
                SkySettings.setScene(this, (String) it.tag);
                if (mWallItem != null) mWallItem.value(it.text);
                for (ListPanel.Item o : l) {
                    o.on = o == it;
                    refreshItem(o);
                }
            };
        }
        mPanel.push(getString(R.string.cz_wallpaper), getString(R.string.cz_wallpaper_sub), l);
    }

    // ------------------------------------------------------------------ continue watching on Home

    private String continueLabel() {
        return getString(mPrefs.continueOnHome() ? R.string.cz_continue_show : R.string.cz_continue_hide);
    }

    /**
     * "Continue watching on Home": show (the hero and the row, as 1.0) or hide (always the calm Home:
     * clock, date and weather over the wallpaper, then the apps; Home stops reading TvProvider).
     */
    private void openContinue() {
        boolean on = mPrefs.continueOnHome();
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(0, getString(R.string.cz_continue_show), null).kind(ListPanel.CHECK).on(on));
        l.add(ListPanel.Item.action(0, getString(R.string.cz_continue_hide), null).kind(ListPanel.CHECK).on(!on));
        for (int i = 0; i < l.size(); i++) {
            final ListPanel.Item it = l.get(i);
            final boolean show = i == 0;
            it.action = () -> {
                mPrefs.putBool(Prefs.K_CONTINUE_HOME, show);
                if (mContinueItem != null) mContinueItem.value(it.text);
                for (ListPanel.Item o : l) {
                    o.on = o == it;
                    refreshItem(o);
                }
            };
        }
        mPanel.push(getString(R.string.cz_continue), getString(R.string.cz_continue_sub), l);
    }

    // ------------------------------------------------------------------ spotlight, home screen, about

    private void openSpotlight() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        ListPanel.Item auto = ListPanel.Item.action(0, getString(R.string.cz_hero_auto), null).kind(ListPanel.TOGGLE).on(mPrefs.heroAuto());
        auto.action = () -> mPrefs.putBool(Prefs.K_HERO_AUTO, auto.on);
        l.add(auto);
        ListPanel.Item tr = ListPanel.Item.action(0, getString(R.string.cz_trailers), null).kind(ListPanel.TOGGLE)
                .on(mPrefs.trailers()).value(getString(R.string.cz_trailers_sub));
        tr.action = () -> mPrefs.putBool(Prefs.K_TRAILERS, tr.on);
        l.add(tr);
        mPanel.push(getString(R.string.cz_spotlight), null, l);
    }

    private void openHomeScreen() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.action(R.drawable.ic_home, getString(R.string.lumen_home), () -> mPanel.close())
                .kind(ListPanel.CHECK).on(true).value(getString(R.string.cz_lumen_home_sub)));
        if (HomeRepository.classicAvailable(this)) {
            l.add(ListPanel.Item.action(R.drawable.ic_apps, getString(R.string.classic_launcher), this::confirmClassic)
                    .kind(ListPanel.CHECK).on(false).value(getString(R.string.cz_classic_sub)));
        } else {
            l.add(ListPanel.Item.info(0, getString(R.string.cz_classic_missing)));
        }
        mPanel.push(getString(R.string.cz_home_screen), null, l);
    }

    private void confirmClassic() {
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.info(0, getString(R.string.cz_classic_confirm)));
        l.add(ListPanel.Item.action(R.drawable.ic_check, getString(R.string.cz_switch), () -> {
            ProjectorBridge.setLauncher(this, "classic");
            mPanel.close();
        }));
        l.add(ListPanel.Item.action(R.drawable.ic_close, getString(R.string.cancel), () -> mPanel.close()));
        mPanel.push(getString(R.string.classic_launcher), null, l);
    }

    private void openAbout() {
        String ver = "1.0";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            ver = pi.versionName;
        } catch (Throwable ignored) {
        }
        ArrayList<ListPanel.Item> l = new ArrayList<>();
        l.add(ListPanel.Item.info(R.drawable.ic_info, getString(R.string.about_version, ver)));
        l.add(ListPanel.Item.info(0, getString(R.string.about_os)));
        l.add(ListPanel.Item.info(0, getString(R.string.about_content)));
        l.add(ListPanel.Item.info(0, getString(R.string.weather_attribution)));
        l.add(ListPanel.Item.info(0, getString(R.string.about_geo)));
        HomeModel m = model();
        if (!m.tvpMode.isEmpty()) l.add(ListPanel.Item.info(0, "TvProvider: " + m.tvpMode));
        mPanel.push(getString(R.string.cz_about), null, l);
    }
}
