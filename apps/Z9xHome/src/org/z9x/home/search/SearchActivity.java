package org.z9x.home.search;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.z9x.home.App;
import org.z9x.home.Launch;
import org.z9x.home.Prefs;
import org.z9x.home.R;
import org.z9x.home.Safe;
import org.z9x.home.data.AppSource;
import org.z9x.home.data.Card;
import org.z9x.home.data.HomeModel;
import org.z9x.home.data.Row;
import org.z9x.home.img.ImageLoader;
import org.z9x.home.ui.Backdrop;
import org.z9x.home.ui.ChipRow;
import org.z9x.home.ui.ListPanel;
import org.z9x.home.ui.ContextPanel;
import org.z9x.home.ui.PageForYou;
import org.z9x.home.ui.RowView;
import org.z9x.home.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Search screen (SPEC 7.9, 9). Opened by the remote's mic key (org.z9x.projector starts
 * .search.VoiceSearchAlias, which needs org.z9x.home.permission.MIC: only then does it listen), by
 * GLOBAL_SEARCH (classic mode, Recents ASSIST fallback; never listens) and by the top-bar search pill.
 * Stays enabled in classic mode; costs nothing until used.
 *
 * Typing (review 2026-10-07): the image's only IME (LeanbackIME) types Latin only. With a Cyrillic UI
 * language and no IME for it, {@link TvKeyboard} (our own on-screen keyboard: native layout, Latin,
 * digits) replaces the system IME here; for any non-Latin UI language without a keyboard a "Keyboard"
 * row offers a keyboard app (Gboard) in Google Play, which then also types in Settings.
 *
 * Mic (push-to-talk): the XGIMI remote streams audio only while the mic key is held. A press that was
 * already released when the screen opens (cold start) or a short tap (&lt; {@value #TAP_MS} ms) opens
 * keyboard search with "Hold the mic button while you speak" instead of listening to silence.
 */
public class SearchActivity extends Activity implements RowView.Host, ChipRow.Listener, VoiceInput.Listener,
        TvKeyboard.Listener {
    static volatile SearchActivity sActive;
    public static final String EXTRA_HELD = "held";
    public static final String EXTRA_DOWN_TIME = "down_uptime";
    /** org.z9x.projector HomeSearch.EXTRA_TAP: tap-to-talk (classic launcher voice orb), listen at once. */
    public static final String EXTRA_TAP = "tap_voice";
    private static final String ALIAS = "org.z9x.home.search.VoiceSearchAlias";
    private static final long DEBOUNCE_MS = 250;
    /** A mic press shorter than this is a tap: no audio comes after the release (keyboard search). */
    static final long TAP_MS = 400;
    private static final String KEYBOARD_APP = "com.google.android.inputmethod.latin";
    private static final Object GET_KEYBOARD = new Object();
    private static final String YT = AppSource.PKG_YOUTUBE;
    private static final Object CLEAR = new Object();

    private Root mRoot;
    private EditText mField;
    private MicButton mMic;
    private TextView mStatus;
    private RowView mAppsRow, mContentRow;
    private ChipRow mInRow, mRecentRow, mKbRow;
    private TvKeyboard mKb;        // non-null: our own keyboard replaces the system IME (Cyrillic, no IME)
    private long mVoiceDown;
    private ContextPanel mPanel;
    private final ArrayList<View> mSections = new ArrayList<>();
    private int mZone;            // -1 mic, 0 field, 1.. sections
    private VoiceInput mVoice;
    private long mVoiceStart;
    private View mFieldBg;
    private android.graphics.drawable.GradientDrawable mFieldShape;
    private int mSeq;
    private String mQuery = "";
    private List<Chip0> mSearchIn;
    private volatile List<Card> mClassicApps; // classic launcher mode: Lumen Home has no model
    private final Runnable mRun = () -> runSearch(mField.getText().toString());

    private static final class Chip0 {
        String label;
        Drawable icon;
        ComponentName cn;
        String pkg;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.init(this);
        sActive = this;
        build();
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        sActive = this;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVoice != null) mVoice.cancel();
        mMic.setListening(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sActive == this) sActive = null;
        if (mVoice != null) mVoice.destroy();
        mRoot.removeCallbacks(mRun);
    }

    private void handle(Intent i) {
        boolean voice = i.getComponent() != null && ALIAS.equals(i.getComponent().getClassName());
        boolean held = voice && i.getBooleanExtra(EXTRA_HELD, false);
        String q = i.getStringExtra(SearchManager.QUERY);
        Log.i(App.TAG, "search open voice=" + voice + " held=" + held + " action=" + i.getAction());
        if (q != null && !q.isEmpty()) {
            mField.setText(q);
            mField.setSelection(q.length());
        }
        setZone(0);
        if (voice) {
            if (VoiceInput.recentlyFailed()) {
                showStatus(getString(R.string.voice_unavailable_hint));
                showIme();
                return;
            }
            if (!held) {
                // tap-to-talk (the classic launcher's voice orb): listen like the on-screen mic
                if (i.getBooleanExtra(EXTRA_TAP, false)) startVoice(0);
                return; // else lamp standby / screen was off: open search without voice (SPEC 9)
            }
            long down = i.getLongExtra(EXTRA_DOWN_TIME, 0);
            if (down > 0 && MicUpReceiver.lastUp() >= down) {
                // released before we could listen (cold start of this process) or a short tap: the remote
                // sent no audio after the release, so the recognizer would only hear silence
                Log.i(App.TAG, "mic already released (" + (MicUpReceiver.lastUp() - down) + " ms press): keyboard search");
                showStatus(getString(R.string.voice_hold));
                showIme();
                return;
            }
            startVoice(down);
        } else if (mKb != null) {
            showIme();                       // keyboard search: our keyboard comes up at once
        }
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        mRoot = new Root(this);
        Backdrop bd = new Backdrop(this, App.get().images());
        mRoot.addView(bd);
        mFieldShape = new android.graphics.drawable.GradientDrawable();
        mFieldShape.setCornerRadius(Theme.pxf(44));
        mFieldShape.setColor(Theme.SURFACE2);
        mFieldBg = new View(this);
        mFieldBg.setBackground(mFieldShape);
        mRoot.addView(mFieldBg);
        mMic = new MicButton(this);
        mRoot.addView(mMic);
        mField = new EditText(this);
        mField.setBackground(null);
        Theme.text(mField, 36, Theme.REGULAR, Theme.TEXT1);
        mField.setHintTextColor(Theme.TEXT3);
        mField.setHint(R.string.search_hint);
        mField.setSingleLine(true);
        mField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        mField.setGravity(Gravity.CENTER_VERTICAL);
        mField.setPadding(0, 0, 0, 0);
        mField.setTextCursorDrawable(R.drawable.cursor);
        mField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b2, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mRoot.removeCallbacks(mRun);
                mRoot.postDelayed(mRun, DEBOUNCE_MS);
            }
        });
        mField.setOnEditorActionListener((v, actionId, ev) -> {
            submitQuery();
            return true;
        });
        mRoot.addView(mField);
        String kbLang = TvKeyboard.nativeLayout(this);
        if (kbLang != null) {
            mField.setShowSoftInputOnFocus(false);
            mKb = new TvKeyboard(this, kbLang, this);
            mKb.setVisibility(View.GONE);
            Log.i(App.TAG, "search keyboard: own " + kbLang + " layout (no IME subtype for it)");
        }
        mStatus = new TextView(this);
        Theme.text(mStatus, 24, Theme.REGULAR, Theme.TEXT2);
        mStatus.setSingleLine(true);
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        mRoot.addView(mStatus);
        mAppsRow = new RowView(this, this);
        mContentRow = new RowView(this, this);
        mInRow = new ChipRow(this, getString(R.string.search_in), this);
        mRecentRow = new ChipRow(this, getString(R.string.search_recent), this);
        mKbRow = new ChipRow(this, getString(R.string.kb_title), this);
        for (View v : new View[]{mAppsRow, mContentRow, mInRow, mRecentRow, mKbRow}) {
            v.setVisibility(View.GONE);
            mRoot.results.addView(v);
        }
        if (TvKeyboard.needsKeyboardApp(this)) {
            ArrayList<ChipRow.Chip> kc = new ArrayList<>();
            kc.add(new ChipRow.Chip(R.drawable.ic_keyboard, getString(R.string.kb_get_app,
                    java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.getDefault())), GET_KEYBOARD));
            mKbRow.setChips(kc);
            mKbRow.setVisibility(View.VISIBLE);
        }
        mRoot.addView(mRoot.results);
        if (mKb != null) mRoot.addView(mKb);
        mPanel = new ContextPanel(this);
        mRoot.addView(mPanel);
        setContentView(mRoot);
        showRecent();
    }

    private void showStatus(String s) {
        mStatus.setText(s == null ? "" : s);
    }

    private void showIme() {
        if (mZone != 0) setZone(0);
        mField.requestFocus();
        if (mKb != null) {
            if (mKb.getVisibility() != View.VISIBLE) {
                mKb.reset();
                mKb.setVisibility(View.VISIBLE);
                mKb.setTranslationY(Theme.pxf(40));
                mKb.setAlpha(0f);
                mKb.animate().translationY(0f).alpha(1f).setDuration(Theme.PANEL_MS).setInterpolator(Theme.EMPHASIZED).start();
            }
            return;
        }
        InputMethodManager im = getSystemService(InputMethodManager.class);
        mField.post(() -> im.showSoftInput(mField, 0));
    }

    private void hideIme() {
        if (mKb != null) {
            mKb.animate().cancel();
            mKb.setVisibility(View.GONE);
            return;
        }
        InputMethodManager im = getSystemService(InputMethodManager.class);
        im.hideSoftInputFromWindow(mField.getWindowToken(), 0);
    }

    private boolean kbShown() {
        return mKb != null && mKb.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------ TvKeyboard.Listener

    @Override
    public void onKbText(String t) {
        Editable ed = mField.getText();
        int a = Math.max(0, Math.min(mField.getSelectionStart(), mField.getSelectionEnd()));
        int b = Math.max(0, Math.max(mField.getSelectionStart(), mField.getSelectionEnd()));
        if (ed.length() + t.length() > 200) return;
        ed.replace(a, b, t);
        mField.setSelection(Math.min(ed.length(), a + t.length()));
    }

    @Override
    public void onKbBackspace() {
        Editable ed = mField.getText();
        int a = mField.getSelectionStart(), b = mField.getSelectionEnd();
        if (a < 0) a = ed.length();
        if (b < 0) b = a;
        if (a != b) {
            ed.delete(Math.min(a, b), Math.max(a, b));
        } else if (a > 0) {
            int from = Character.offsetByCodePoints(ed, a, -1);
            ed.delete(from, a);
        }
    }

    @Override
    public void onKbAction() {
        submitQuery();
    }

    private void submitQuery() {
        String q = mField.getText().toString().trim();
        if (!q.isEmpty()) remember(q);
        mRoot.removeCallbacks(mRun);
        runSearch(q);
        hideIme();
        mRoot.postDelayed(() -> {
            if (mSections.size() > 0) setZone(1);
        }, 300);
    }

    private void openKeyboardApp() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + KEYBOARD_APP))
                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (getPackageManager().resolveActivity(i, 0) == null) {
            showStatus(getString(R.string.kb_no_play));
            return;
        }
        Launch.start(this, i, "keyboard app");
    }

    // ------------------------------------------------------------------ search

    private void runSearch(String q) {
        q = q.trim();
        final int seq = ++mSeq;
        mQuery = q;
        if (q.isEmpty()) {
            mAppsRow.setVisibility(View.GONE);
            mContentRow.setVisibility(View.GONE);
            mInRow.setVisibility(View.GONE);
            showRecent();
            relayoutSections();
            return;
        }
        mRecentRow.setVisibility(View.GONE);
        final String fq = q;
        final Context c = this;
        App.get().io().post(() -> Safe.run("search", () -> {
            HomeModel m = App.get().repo().last();
            if (m == null && mClassicApps == null) {
                mClassicApps = org.z9x.home.data.HomeRepository.appCardsForSearch(c, App.get().prefs());
            }
            List<Card> pool = m != null ? m.apps : mClassicApps;
            List<Card> apps = AppMatcher.match(pool, fq, null, 12);
            List<Card> local = m != null ? AppMatcher.matchPrograms(m.programs(), fq, 12) : new ArrayList<>();
            List<Chip0> in = searchIn(c);
            runOnUiThread(() -> {
                if (seq == mSeq) bindResults(fq, apps, local, null, in);
            });
            List<Card> sugg = SuggestProviders.query(c, fq);
            ArrayList<Card> content = new ArrayList<>(local);
            content.addAll(sugg);
            runOnUiThread(() -> {
                if (seq == mSeq) bindResults(fq, apps, content, sugg, in);
            });
        }));
    }

    private void bindResults(String q, List<Card> apps, List<Card> content, List<Card> sugg, List<Chip0> in) {
        Row ra = new Row(Row.SEARCH_APPS, "s_apps", getString(R.string.search_apps));
        ra.cards.addAll(apps);
        mAppsRow.setRow(ra, true);
        mAppsRow.setNear(true);
        mAppsRow.setVisibility(apps.isEmpty() ? View.GONE : View.VISIBLE);
        Row rc = new Row(Row.SEARCH_CONTENT, "s_content", getString(R.string.search_from_apps));
        ArrayList<String> labels = new ArrayList<>();
        for (Card k : content) if (!labels.contains(k.appLabel) && !k.appLabel.isEmpty()) labels.add(k.appLabel);
        rc.sub = TextUtils.join(", ", labels.subList(0, Math.min(3, labels.size())));
        rc.cards.addAll(content);
        rc.aspect = Card.A_16_9;
        mContentRow.setRow(rc, true);
        mContentRow.setNear(true);
        mContentRow.setVisibility(content.isEmpty() ? View.GONE : View.VISIBLE);
        ArrayList<ChipRow.Chip> chips = new ArrayList<>();
        for (Chip0 c0 : in) {
            ChipRow.Chip ch = new ChipRow.Chip(0, c0.label, c0);
            ch.drawable = c0.icon;
            chips.add(ch);
        }
        mInRow.setChips(chips);
        mInRow.setVisibility(chips.isEmpty() ? View.GONE : View.VISIBLE);
        if (sugg != null && apps.isEmpty() && content.isEmpty()) showStatus(getString(R.string.search_no_results));
        else if (mVoice == null || !mVoice.active()) showStatus("");
        relayoutSections();
    }

    private void showRecent() {
        List<String> rec = App.get().prefs().list(Prefs.K_RECENT_SEARCH);
        ArrayList<ChipRow.Chip> chips = new ArrayList<>();
        for (String s : rec) chips.add(new ChipRow.Chip(R.drawable.ic_history, s, s));
        if (!chips.isEmpty()) chips.add(new ChipRow.Chip(R.drawable.ic_trash, getString(R.string.search_clear), CLEAR));
        mRecentRow.setChips(chips);
        mRecentRow.setVisibility(chips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void remember(String q) {
        Prefs p = App.get().prefs();
        List<String> l = p.list(Prefs.K_RECENT_SEARCH);
        l.remove(q);
        l.add(0, q.length() > 80 ? q.substring(0, 80) : q);
        while (l.size() > 8) l.remove(l.size() - 1);
        p.putList(Prefs.K_RECENT_SEARCH, l);
    }

    private void relayoutSections() {
        View cur = mZone >= 1 && mZone <= mSections.size() ? mSections.get(mZone - 1) : null;
        mSections.clear();
        for (View v : new View[]{mAppsRow, mContentRow, mInRow, mRecentRow, mKbRow}) if (v.getVisibility() == View.VISIBLE) mSections.add(v);
        mRoot.results.requestLayout();
        if (mZone >= 1) {
            int i = cur != null ? mSections.indexOf(cur) : -1;
            setZone(i >= 0 ? i + 1 : (mSections.isEmpty() ? 0 : 1));
        }
    }

    /** Leanback apps that handle ACTION_SEARCH (Kinopoisk, Spotify, Play Store), plus YouTube. */
    private List<Chip0> searchIn(Context c) {
        if (mSearchIn != null) return mSearchIn;
        ArrayList<Chip0> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        try {
            pm.getApplicationInfo(YT, 0);
            Chip0 y = new Chip0();
            y.pkg = YT;
            y.label = pm.getApplicationLabel(pm.getApplicationInfo(YT, 0)).toString();
            y.icon = pm.getApplicationIcon(YT);
            out.add(y);
        } catch (Throwable ignored) {
        }
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_SEARCH), 0)) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.equals(YT) || !seen.add(pkg)) continue;
            if (pm.getLeanbackLaunchIntentForPackage(pkg) == null) continue;
            Chip0 c0 = new Chip0();
            c0.pkg = pkg;
            c0.cn = new ComponentName(pkg, ri.activityInfo.name);
            c0.label = ri.loadLabel(pm).toString();
            try {
                c0.label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                c0.icon = pm.getApplicationIcon(pkg);
            } catch (Throwable ignored) {
            }
            out.add(c0);
        }
        for (Chip0 c0 : out) if (c0.icon != null) c0.icon.setBounds(0, 0, Theme.px(30), Theme.px(30));
        mSearchIn = out;
        return out;
    }

    // ------------------------------------------------------------------ actions

    @Override
    public void onChip(ChipRow row, ChipRow.Chip chip) {
        if (row == mKbRow) {
            openKeyboardApp();
            return;
        }
        if (row == mRecentRow && chip.tag == CLEAR) {
            App.get().prefs().putList(Prefs.K_RECENT_SEARCH, new ArrayList<>());
            showRecent();
            setZone(0);
            relayoutSections();
            return;
        }
        if (row == mRecentRow) {
            String q = (String) chip.tag;
            mField.setText(q);
            mField.setSelection(q.length());
            mRoot.removeCallbacks(mRun);
            runSearch(q);
            return;
        }
        Chip0 c0 = (Chip0) chip.tag;
        String q = mQuery;
        if (q.isEmpty()) return;
        remember(q);
        if (YT.equals(c0.pkg)) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
                    .setPackage(YT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(i, 0) == null) {
                i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(YT)
                        .putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            Launch.start(this, i, "search-in youtube");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEARCH).setComponent(c0.cn).putExtra(SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Launch.start(this, i, "search-in " + c0.pkg);
    }

    private void openCard(Card k) {
        if (!mQuery.isEmpty()) remember(mQuery);
        Launch.open(this, k);
    }

    private void startVoice(long downUptime) {
        if (mVoice == null) mVoice = new VoiceInput(this, this);
        mVoiceStart = SystemClock.uptimeMillis();
        mVoiceDown = downUptime;                 // 0 = the on-screen mic (no key held, no MIC_UP)
        hideIme();
        mVoice.start(downUptime > 0 ? downUptime : SystemClock.uptimeMillis());
    }

    /**
     * MIC_UP (main thread): end of push-to-talk. The remote sends no audio after the release, so a short
     * tap (&lt; {@value #TAP_MS} ms held) never becomes tap-to-talk: it means keyboard search.
     */
    void onMicUp(long upUptime) {
        if (mVoice == null || !mVoice.active() || mVoiceDown <= 0 || upUptime < mVoiceDown) return;
        if (upUptime - mVoiceDown < TAP_MS) {
            Log.i(App.TAG, "mic tap (" + (upUptime - mVoiceDown) + " ms): keyboard search");
            mVoice.cancel();
            mMic.setListening(false);
            mField.setTextColor(Theme.TEXT1);
            showStatus(getString(R.string.voice_hold));
            showIme();
            return;
        }
        mVoice.stop();
    }

    @Override
    public void onListening() {
        mMic.setListening(true);
        showStatus(getString(R.string.voice_listening));
    }

    @Override
    public void onRms(float rmsDb) {
        mMic.setLevel(rmsDb);
    }

    @Override
    public void onPartial(String text) {
        mField.setTextColor(Theme.TEXT2);
        mField.setText(text);
        mField.setSelection(text.length());
    }

    @Override
    public void onFinal(String text) {
        mMic.setListening(false);
        mField.setTextColor(Theme.TEXT1);
        mField.setText(text);
        mField.setSelection(text.length());
        remember(text);
        showStatus("");
        mRoot.removeCallbacks(mRun);
        runSearch(text);
    }

    @Override
    public void onFailed(boolean remembered) {
        mMic.setListening(false);
        mField.setTextColor(Theme.TEXT1);
        showStatus(getString(R.string.voice_unavailable));
        showIme();
    }

    @Override
    public void onEnd() {
        mMic.setListening(false);
        mField.setTextColor(Theme.TEXT1);
        if (mField.length() == 0) showStatus(getString(R.string.voice_nothing_heard));
    }

    // ------------------------------------------------------------------ RowView.Host

    @Override
    public ImageLoader images() {
        return App.get().images();
    }

    @Override
    public void onRowFocus(RowView row, Card k) {
    }

    @Override
    public void onMoveDone(RowView row) {
    }

    // ------------------------------------------------------------------ keys

    private void setZone(int z) {
        mZone = z;
        mMic.setFocusState(z == -1);
        mFieldShape.setColor(z == 0 ? Theme.SURFACE3 : Theme.SURFACE2);
        if (z == 0) {
            mField.requestFocus();
        } else if (mField.hasFocus()) {
            mField.clearFocus();
            mRoot.requestFocus();
        }
        for (int i = 0; i < mSections.size(); i++) {
            View v = mSections.get(i);
            boolean a = z == i + 1;
            if (v instanceof RowView) ((RowView) v).setActive(a);
            else ((ChipRow) v).setActive(a);
        }
        mRoot.results.scrollTo(z >= 1 ? mSections.get(z - 1) : null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (mPanel.isOpen()) return mPanel.onKey(k, e) || super.dispatchKeyEvent(e);
        if (kbShown()) {
            // modal like an IME: D-pad and OK drive the keyboard, BACK hides it
            if (k == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideIme();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && mKb.onKey(k, e)) return true;
            if (isNavKey(k) || k == KeyEvent.KEYCODE_DEL) return true;
            return super.dispatchKeyEvent(e);
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (mVoice != null && mVoice.active()) {
                    mVoice.cancel();
                    onEnd();
                } else if (mZone != 0) setZone(0);
                else finish();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_SEARCH) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            if (mZone != 0 && isNavKey(k)) return true;
            return super.dispatchKeyEvent(e);
        }
        boolean rtl = Theme.rtl(mRoot);
        if (mZone == 0) {
            if (mKb != null && PageForYou.isOk(k)) {
                showIme();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (!mSections.isEmpty()) {
                    hideIme();
                    setZone(1);
                }
                return true;
            }
            if ((k == (rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT)) && mField.getSelectionStart() == 0) {
                setZone(-1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP) return true;
            return super.dispatchKeyEvent(e);
        }
        if (mZone == -1) {
            if (k == (rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT)) setZone(0);
            else if (k == KeyEvent.KEYCODE_DPAD_DOWN && !mSections.isEmpty()) setZone(1);
            else if (PageForYou.isOk(k)) {
                if (mVoice != null && mVoice.active()) mVoice.stop();
                else startVoice(0);
            }
            return isNavKey(k) || super.dispatchKeyEvent(e);
        }
        View sec = mSections.get(mZone - 1);
        if (k == KeyEvent.KEYCODE_DPAD_UP) {
            setZone(mZone - 1 >= 1 ? mZone - 1 : 0);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (mZone < mSections.size()) setZone(mZone + 1);
            return true;
        }
        if (sec instanceof ChipRow) {
            ChipRow cr = (ChipRow) sec;
            if (k == KeyEvent.KEYCODE_MENU && cr == mRecentRow) {
                openRecentMenu();
                return true;
            }
            return cr.onKey(k, e) || super.dispatchKeyEvent(e);
        }
        RowView rv = (RowView) sec;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            rv.onKey(k, e);
            return true;
        }
        if (PageForYou.isOk(k)) {
            Card c = rv.focusedCard();
            if (c != null && rv.focusedView() != null) rv.focusedView().pulse(() -> openCard(c));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void openRecentMenu() {
        ArrayList<ListPanel.Item> items = new ArrayList<>();
        items.add(ListPanel.Item.action(R.drawable.ic_trash, getString(R.string.search_clear_recent), () -> {
            App.get().prefs().putList(Prefs.K_RECENT_SEARCH, new ArrayList<>());
            mPanel.close();
            showRecent();
            setZone(0);
            relayoutSections();
        }));
        mPanel.open(getString(R.string.search_recent), null, items);
    }

    private static boolean isNavKey(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || PageForYou.isOk(k);
    }

    // ------------------------------------------------------------------ layout

    private final class Root extends ViewGroup {
        final Results results;

        Root(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClipChildren(false);
            results = new Results(c);
            setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms), h = MeasureSpec.getSize(hms);
            int ex = MeasureSpec.EXACTLY;
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(w, ex), MeasureSpec.makeMeasureSpec(h, ex));
            mFieldBg.measure(MeasureSpec.makeMeasureSpec(w - 2 * Theme.px(Theme.MARGIN), ex), MeasureSpec.makeMeasureSpec(Theme.px(88), ex));
            mMic.measure(MeasureSpec.makeMeasureSpec(Theme.px(88), ex), MeasureSpec.makeMeasureSpec(Theme.px(88), ex));
            int fw = w - 2 * Theme.px(Theme.MARGIN) - Theme.px(88 + 32 + 40);
            mField.measure(MeasureSpec.makeMeasureSpec(fw, ex), MeasureSpec.makeMeasureSpec(Theme.px(88), ex));
            mStatus.measure(MeasureSpec.makeMeasureSpec(w - 2 * Theme.px(Theme.MARGIN), ex), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            results.measure(MeasureSpec.makeMeasureSpec(w, ex), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (mKb != null) mKb.measure(MeasureSpec.makeMeasureSpec(w, ex), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            mPanel.measure(MeasureSpec.makeMeasureSpec(w, ex), MeasureSpec.makeMeasureSpec(h, ex));
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            boolean rtl = Theme.rtl(this);
            getChildAt(0).layout(0, 0, w, h);
            int m = Theme.px(Theme.MARGIN), y = Theme.px(64);
            mFieldBg.layout(m, y, w - m, y + Theme.px(88));
            int mx = rtl ? w - m - Theme.px(88) : m;
            mMic.layout(mx, y, mx + Theme.px(88), y + Theme.px(88));
            int fx = rtl ? w - m - Theme.px(88 + 32) - mField.getMeasuredWidth() : m + Theme.px(88 + 32);
            mField.layout(fx, y, fx + mField.getMeasuredWidth(), y + Theme.px(88));
            int sy = y + Theme.px(88 + 20);
            mStatus.layout(m, sy, m + mStatus.getMeasuredWidth(), sy + mStatus.getMeasuredHeight());
            int ry = Theme.px(232);
            results.layout(0, ry, w, ry + results.getMeasuredHeight());
            if (mKb != null) mKb.layout(0, h - mKb.getMeasuredHeight(), w, h);
            mPanel.layout(0, 0, w, h);
        }

    }

    /** The result sections stacked vertically; scrolls so the focused one is visible. */
    private final class Results extends ViewGroup {
        Results(Context c) {
            super(c);
            setClipChildren(false);
        }

        void scrollTo(View v) {
            float y = 0;
            if (v != null) {
                int bottom = v.getBottom() - (v instanceof RowView ? Theme.px(Theme.ROW_CAPTION_H) - Theme.px(80) : 0);
                int avail = getResources().getDisplayMetrics().heightPixels - getTop() - Theme.px(40);
                if (bottom > avail) y = avail - bottom;
            }
            animate().translationY(y).setDuration(Theme.PAGE_SCROLL_MS).setInterpolator(Theme.EMPHASIZED).start();
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int w = MeasureSpec.getSize(wms);
            int ex = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.measure(ex, un);
                y += v.getMeasuredHeight();
            }
            setMeasuredDimension(w, y);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.layout(0, y, r - l, y + v.getMeasuredHeight());
                y += v.getMeasuredHeight();
            }
        }
    }

    /** 88 px mic circle: red while listening, with an accent ring following the input level. */
    private static final class MicButton extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable mIcon, mIconF;
        private boolean mFocused, mListening;
        private float mLevel, mShown;

        MicButton(Context c) {
            super(c);
            mIcon = Theme.icon(c, R.drawable.ic_mic, Theme.TEXT1);
            mIconF = Theme.icon(c, R.drawable.ic_mic, Theme.ON_FOCUS);
            setContentDescription(c.getString(R.string.voice_search));
        }

        void setFocusState(boolean f) {
            mFocused = f;
            animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(Theme.FOCUS_IN_MS).start();
            invalidate();
        }

        void setListening(boolean l) {
            mListening = l;
            if (!l) mLevel = 0;
            invalidate();
        }

        void setLevel(float rmsDb) {
            mLevel = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 12f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = getWidth() / 2f;
            mShown += (mLevel - mShown) * 0.35f; // ~100 ms low-pass at 60 fps
            if (mListening) {
                float ring = r * (1f + 0.6f * mShown);
                mP.setStyle(Paint.Style.STROKE);
                mP.setStrokeWidth(Theme.pxf(4));
                mP.setColor(Theme.ACCENT);
                c.drawCircle(cx, cy, Math.min(ring, r * 1.59f) - Theme.pxf(2), mP);
                mP.setStyle(Paint.Style.FILL);
                if (Math.abs(mLevel - mShown) > 0.01f) postInvalidateOnAnimation();
            }
            mP.setStyle(Paint.Style.FILL);
            mP.setColor(mListening ? Theme.LIVE : (mFocused ? Theme.FOCUS : Theme.SURFACE3));
            c.drawCircle(cx, cy, r * 0.9f, mP);
            Drawable d = mFocused && !mListening ? mIconF : mIcon;
            int s = Math.round(r * 0.9f);
            d.setBounds((int) cx - s / 2, (int) cy - s / 2, (int) cx + s / 2, (int) cy + s / 2);
            d.draw(c);
        }
    }
}
