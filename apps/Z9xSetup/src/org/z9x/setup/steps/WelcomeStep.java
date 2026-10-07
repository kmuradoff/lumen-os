package org.z9x.setup.steps;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.app.LocalePicker;

import org.z9x.setup.L;
import org.z9x.setup.Langs;
import org.z9x.setup.R;
import org.z9x.setup.SetupActivity;
import org.z9x.setup.ui.Icon;
import org.z9x.setup.ui.Row;
import org.z9x.setup.ui.Sheet;
import org.z9x.setup.ui.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Welcome + language (SPEC 4.1): the 17 Lumen languages with native names, then "More languages…"
 * (all system asset locales; our apps fall back to English there). Selecting a language applies it
 * at once without an activity relaunch and moves on; the current one only moves on.
 */
public class WelcomeStep extends Step {
    public static final String ID = "welcome";
    private View mFocus;

    public WelcomeStep(SetupActivity h) {
        super(h);
    }

    @Override
    public String id() { return ID; }

    @Override
    public CharSequence title() { return s(R.string.welcome_title); }

    @Override
    public CharSequence subtitle() { return s(R.string.welcome_subtitle); }

    @Override
    public View leftExtra() {
        TextView t = Ui.text(ctx(), 26, Ui.TEXT_DIM, Ui.light());
        t.setText(s(R.string.tagline));
        t.setLetterSpacing(0.04f);
        return t;
    }

    private static ScrollView scroller(android.content.Context c) {
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(Ui.px(56));
        sv.setClipToPadding(false);
        sv.setSmoothScrollingEnabled(true);
        return sv;
    }

    @Override
    public View createContent() {
        ScrollView sv = scroller(ctx());
        LinearLayout list = Ui.vbox(ctx());
        list.setPadding(0, Ui.px(8), 0, Ui.px(8));
        Locale cur = host.getResources().getConfiguration().getLocales().get(0);
        int sel = Langs.indexOf(cur);
        mFocus = null;
        for (int i = 0; i < Langs.TAGS.length; i++) {
            final String tag = Langs.TAGS[i];
            Row r = new Row(ctx(), Langs.NAMES[i]);
            r.title.setTextLocale(Locale.forLanguageTag(tag));
            if (i == sel) {
                r.end(new Icon(ctx(), Icon.CHECK), Ui.px(36), Ui.px(36));
                mFocus = r;
            }
            final boolean isCurrent = i == sel;
            r.setOnClickListener(v -> pick(Locale.forLanguageTag(tag), isCurrent));
            list.addView(r, rowLp());
        }
        Row more = new Row(ctx(), s(R.string.lang_more));
        more.startIcon(new Icon(ctx(), Icon.GLOBE), 36);
        more.end(new Icon(ctx(), Icon.CHEVRON), Ui.px(28), Ui.px(28));
        if (sel < 0) {
            more.subtitle(Langs.nativeName(cur));
            mFocus = more;
        }
        more.setOnClickListener(v -> showMore());
        list.addView(more, rowLp());
        sv.addView(list);
        return sv;
    }

    private static LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.px(6);
        return lp;
    }

    @Override
    public View initialFocus() { return mFocus; }

    private void pick(Locale l, boolean isCurrent) {
        if (isCurrent) {
            host.next("next");
            return;
        }
        host.setLocale(l, () -> {
            if (host.current() == this) host.next("next");
        });
    }

    /**
     * Locales the system has resources for: LocalePicker.getAllAssetLocales (Android 14, hidden),
     * else the public AssetManager list. Pseudo-locales (en-XA, ar-XB…) are dropped.
     */
    private List<Locale> assetLocales() {
        List<Locale> out = new ArrayList<>();
        try {
            for (LocalePicker.LocaleInfo li : LocalePicker.getAllAssetLocales(host, false)) {
                if (li.getLocale() != null) out.add(li.getLocale());
            }
        } catch (Throwable t) {
            L.w("getAllAssetLocales unavailable (" + t.getClass().getSimpleName() + "), using AssetManager");
            out.clear();
            for (String tag : android.content.res.Resources.getSystem().getAssets().getLocales()) {
                if (tag == null || tag.isEmpty()) continue;
                Locale l = Locale.forLanguageTag(tag);
                String c = l.getCountry();
                if (l.getLanguage().isEmpty() || "XA".equals(c) || "XB".equals(c) || "XC".equals(c)) continue;
                out.add(l);
            }
            // "af" next to "af-ZA": keep only the regional entries of a language
            Set<String> regional = new HashSet<>();
            for (Locale l : out) if (!l.getCountry().isEmpty()) regional.add(l.getLanguage());
            out.removeIf(l -> l.getCountry().isEmpty() && regional.contains(l.getLanguage()));
        }
        return out;
    }

    /** All asset locales of the system, native names, sorted; ours already listed are left out. */
    private void showMore() {
        host.bg.execute(() -> {
            Set<String> ours = new HashSet<>();
            for (String t : Langs.TAGS) ours.add(t);
            Set<String> seen = new HashSet<>();
            List<Locale> locs = new ArrayList<>();
            for (Locale l : assetLocales()) {
                String tag = l.toLanguageTag();
                if (ours.contains(tag) || !seen.add(tag)) continue;
                locs.add(l);
            }
            List<String> names = new ArrayList<>();
            for (Locale l : locs) names.add(Langs.nativeName(l));
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < locs.size(); i++) order.add(i);
            java.text.Collator col = java.text.Collator.getInstance(Locale.ROOT);
            Collections.sort(order, (a, b) -> col.compare(names.get(a), names.get(b)));
            host.main.post(() -> {
                if (host.current() != this) return;
                Sheet sh = new Sheet(ctx(), s(R.string.lang_more), s(R.string.lang_more_hint));
                LinearLayout list = sh.addList();
                View first = null;
                Locale cur = host.getResources().getConfiguration().getLocales().get(0);
                for (int idx : order) {
                    Locale l = locs.get(idx);
                    Row r = new Row(ctx(), names.get(idx));
                    r.title.setTextLocale(l);
                    if (l.equals(cur)) {
                        r.end(new Icon(ctx(), Icon.CHECK), Ui.px(36), Ui.px(36));
                        first = r;
                    }
                    r.setOnClickListener(v -> {
                        sh.dismiss();
                        pick(l, l.equals(cur));
                    });
                    list.addView(r, rowLp());
                    if (first == null && list.getChildCount() == 1) first = r;
                }
                sh.addButton(s(R.string.action_cancel), false, v -> sh.dismiss());
                host.showSheet(sh, first);
            });
        });
    }
}
