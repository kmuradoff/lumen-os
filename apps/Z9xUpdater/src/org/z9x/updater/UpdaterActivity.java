// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.app.Activity;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;

import java.io.File;
import java.util.Locale;

/**
 * "Lumen OS update" (TvSettings > About, android.settings.SYSTEM_UPDATE_SETTINGS). Left: the update
 * itself, one screen per state; right: settings and the About block (version, build, author,
 * project page with QR, licenses). Views are rebuilt only when the screen changes; progress is
 * updated in place.
 */
public final class UpdaterActivity extends Activity {
    private enum Screen { MAIN, CONSENT, LICENSES }

    private Screen screen = Screen.MAIN;
    private LinearLayout left, right;
    private String renderedKey = "";
    private Ui.ProgressLine progress;
    private TextView progressText, etaText;
    private final Runnable onChange = this::refresh;
    private volatile boolean working;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        UpdateService.channel(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        int ph = Ui.dp(this, 48), pv = Ui.dp(this, 32);
        root.setPadding(ph, pv, ph, pv);
        left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        ScrollView ls = new ScrollView(this);
        int pad = Ui.dp(this, 8);
        left.setClipChildren(false);
        left.setClipToPadding(false);
        left.setPadding(pad, pad, pad, pad);
        ls.setClipToPadding(false);
        ls.addView(left, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView rs = new ScrollView(this);
        rs.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f);
        lp.setMarginEnd(Ui.dp(this, 48));
        root.addView(ls, lp);
        root.addView(rs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    /** adb: am start -n org.z9x.updater/.UpdaterActivity --ez verify_only true  (test T5, read-only) */
    private void handleIntent(android.content.Intent i) {
        if (i != null && i.getBooleanExtra("verify_only", false)) {
            UpdateService.start(this, UpdateService.ACTION_VERIFY_ONLY, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Store.listen(onChange);
        renderedKey = "";
        refresh();
        Store st = Store.get(this);
        Store.State s = st.state();
        if (s == Store.State.INSTALLING) {
            UpdateService.start(this, UpdateService.ACTION_WATCH, false);
        } else if ((s == Store.State.IDLE || s == Store.State.ERROR) && result(st).isEmpty()
                && System.currentTimeMillis() - st.lastCheck() > 30 * 60 * 1000L) {
            check();
        }
    }

    @Override
    protected void onPause() {
        Store.unlisten(onChange);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (screen != Screen.MAIN) {
            screen = Screen.MAIN;
            refresh();
            return;
        }
        Store st = Store.get(this);
        if (!st.lastResult().isEmpty()) st.setLastResult("");
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ actions
    private void bg(Runnable r) {
        if (working) return;
        working = true;
        new Thread(() -> {
            try {
                r.run();
            } finally {
                working = false;
                runOnUiThread(this::refresh);
            }
        }, "updater-ui").start();
    }

    private void check() {
        Store.get(this).setLastResult("");
        bg(() -> Checker.check(this));
    }

    private void checkUsb() {
        bg(() -> {
            Checker.Result r = Checker.checkUsb(this);
            if (r.outcome == Checker.Outcome.ERROR && "usb_none".equals(r.res)) {
                Store.get(this).setError("usb_none", "");
            }
        });
    }

    /** "Download and install" / "Install now": preflight first, then the one-time consent. */
    private void install() {
        bg(() -> {
            Store st = Store.get(this);
            Store.State before = st.state();
            Preflight.Block b = Preflight.install(this);
            if (b != null) {
                st.setError(b.res, b.arg);
                return;
            }
            if (!st.firmwareConsent()) {
                runOnUiThread(() -> {
                    screen = Screen.CONSENT;
                    refresh();
                });
                return;
            }
            startInstall(before);
        });
    }

    private void startInstall(Store.State before) {
        if (before == Store.State.DOWNLOADED) {
            UpdateService.start(this, UpdateService.ACTION_INSTALL, false);
        } else {
            UpdateService.start(this, UpdateService.ACTION_DOWNLOAD, true);
        }
    }

    private void retry() {
        Store st = Store.get(this);
        if (st.packageJson().isEmpty()) {
            check();
            return;
        }
        try {
            UpdateManifest.Pkg p = UpdateManifest.Pkg.parse(st.packageJson());
            File src = UpdateService.payloadSource(this, st, p);
            boolean have = src.isFile() && src.length() == p.size && UpdateService.metaFile(this, p).isFile();
            st.setState(have ? Store.State.DOWNLOADED : Store.State.AVAILABLE);
        } catch (JSONException e) {
            check();
        }
    }

    private void reboot() {
        PowerManager pm = getSystemService(PowerManager.class);
        pm.reboot(Ota.REBOOT_REASON);
    }

    // ------------------------------------------------------------------ rendering
    private UpdateManifest manifest() {
        try {
            String j = Store.get(this).manifestJson();
            return j.isEmpty() ? null : new UpdateManifest(j);
        } catch (JSONException e) {
            return null;
        }
    }

    private UpdateManifest.Pkg pkg() {
        try {
            String j = Store.get(this).packageJson();
            return j.isEmpty() ? null : UpdateManifest.Pkg.parse(j);
        } catch (JSONException e) {
            return null;
        }
    }

    private String str(String res, String arg) {
        int id = getResources().getIdentifier(res, "string", getPackageName());
        if (id == 0) return res;
        String s = getString(id);
        return s.contains("%1$") ? String.format(Locale.getDefault(), s.replace("%1$d", "%1$s"), arg) : s;
    }

    private void refresh() {
        Store st = Store.get(this);
        Store.State s = st.state();
        String key = screen + "|" + s + "|" + st.errorRes() + "|" + st.errorArg() + "|" + st.lastResult()
                + "|" + st.autoMode() + "|" + working + "|" + Locale.getDefault();
        if (key.equals(renderedKey)) {
            updateDynamic(st);
            return;
        }
        renderedKey = key;
        left.removeAllViews();
        progress = null;
        progressText = null;
        etaText = null;
        View focus;
        switch (screen) {
            case CONSENT: focus = renderConsent(); break;
            case LICENSES: focus = renderLicenses(); break;
            default: focus = renderMain(st, s);
        }
        renderSide(st);
        updateDynamic(st);
        if (focus != null) focus.post(focus::requestFocus);
    }

    private TextView brand() {
        TextView t = Ui.text(this, "Lumen OS", 13, Ui.ACCENT, 500);   // the brand mark is always mixed case (brand spec 4.2)
        t.setLetterSpacing(0.32f);
        left.addView(t, Ui.margins(Ui.wrap(), 0, 0, 0, Ui.dp(this, 14)));
        return t;
    }

    private TextView title(CharSequence s) {
        TextView t = Ui.text(this, s, 34, Ui.TEXT, 300);
        left.addView(t, Ui.margins(Ui.match(), 0, 0, 0, Ui.dp(this, 12)));
        return t;
    }

    private TextView body(CharSequence s, int color) {
        TextView t = Ui.text(this, s, 18, color, 400);
        left.addView(t, Ui.margins(Ui.match(), 0, 0, 0, Ui.dp(this, 10)));
        return t;
    }

    private LinearLayout buttons() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int p = Ui.dp(this, 8);
        row.setPadding(0, p, 0, p);
        left.addView(row, Ui.margins(Ui.match(), 0, Ui.dp(this, 18), 0, 0));
        return row;
    }

    private TextView addButton(LinearLayout row, int label, boolean primary, View.OnClickListener l) {
        TextView b = Ui.button(this, getString(label), primary, l);
        row.addView(b, Ui.margins(Ui.wrap(), 0, 0, Ui.dp(this, 14), 0));
        return b;
    }

    private void addProgress() {
        progress = new Ui.ProgressLine(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        left.addView(progress, Ui.margins(lp, 0, Ui.dp(this, 18), 0, Ui.dp(this, 12)));
        progressText = body("", Ui.DIM);
        etaText = body("", Ui.DIM);
    }

    private View renderMain(Store st, Store.State s) {
        brand();
        UpdateManifest m = manifest();
        UpdateManifest.Pkg p = pkg();
        String ver = m != null ? m.version : "";
        LinearLayout row;
        switch (s) {
            case CHECKING:
                title(getString(R.string.status_checking));
                addProgress();
                return null;
            case AVAILABLE:
            case PAUSED:
            case DOWNLOADED: {
                title(getString(R.string.avail_title, ver));
                if (p != null) {
                    String size = Formatter.formatShortFileSize(this, p.size);
                    body(getString(p.isDelta() ? R.string.avail_size_delta : R.string.avail_size_full, size), Ui.DIM);
                }
                if (s == Store.State.PAUSED) {
                    addProgress();
                    progressText.setText(R.string.dl_paused);
                }
                if (m != null && Preflight.blobsMissing(m)) body(getString(R.string.warn_blobs), Ui.WARN);
                if (m != null) changelog(m);
                row = buttons();
                View first;
                if (s == Store.State.PAUSED) {
                    first = addButton(row, R.string.btn_resume, true, v -> UpdateService.start(this, UpdateService.ACTION_DOWNLOAD, false));
                    addButton(row, R.string.btn_cancel, false, v -> UpdateService.start(this, UpdateService.ACTION_CANCEL, false));
                } else {
                    first = addButton(row, s == Store.State.DOWNLOADED ? R.string.btn_install : R.string.btn_download_install,
                            true, v -> install());
                    addButton(row, R.string.btn_later, false, v -> finish());
                }
                return first;
            }
            case DOWNLOADING:
                title(getString(R.string.dl_title, ver));
                addProgress();
                row = buttons();
                View f = addButton(row, R.string.btn_pause, false, v -> UpdateService.start(this, UpdateService.ACTION_PAUSE, false));
                addButton(row, R.string.btn_cancel, false, v -> UpdateService.start(this, UpdateService.ACTION_CANCEL, false));
                return f;
            case INSTALLING:
                title(getString(R.string.inst_title, ver));
                addProgress();
                body(getString(R.string.inst_hint), Ui.DIM);
                row = buttons();
                return addButton(row, R.string.btn_cancel, false, v -> UpdateService.start(this, UpdateService.ACTION_CANCEL, false));
            case READY:
                title(getString(R.string.ready_title));
                body(getString(R.string.ready_body, ver), Ui.TEXT);
                body(getString(R.string.ready_later_note), Ui.DIM);
                row = buttons();
                row.setOrientation(LinearLayout.VERTICAL);    // three actions: stacked, any language fits
                row.setGravity(Gravity.START);
                View r = addButton(row, R.string.btn_restart_now, true, v -> reboot());
                addButton(row, R.string.btn_restart_later, false, v -> finish());
                addButton(row, R.string.btn_cancel_update, false, v -> UpdateService.start(this, UpdateService.ACTION_REVERT, false));
                for (int i = 0; i < row.getChildCount(); i++) {
                    ((LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams()).bottomMargin = Ui.dp(this, 12);
                }
                return r;
            case ERROR:
                return renderError(st);
            default:
                return renderIdle(st);
        }
    }

    /** The result after the last restart, "" when there is none or it was recorded on another build. */
    private static String result(Store st) {
        String res = st.lastResult();
        return Outcome.resultStale(res, st.lastResultBuild(), Ota.buildId()) ? "" : res;   // BootReceiver drops it too
    }

    private View renderIdle(Store st) {
        String res = result(st);
        if (res.startsWith("unhealthy:")) {
            renderProblem(res.substring(10), st.lastWhy());
        } else if ("unhealthy".equals(Ota.prop("sys.z9x.ota"))) {
            // the gate's verdict came before CheckJob's look (or the result was dismissed): say it anyway
            renderProblem(Ota.currentVersion(), Ota.prop("sys.z9x.ota.why"));
        } else if (res.startsWith("ok:")) {
            title(getString(R.string.done_ok_title, res.substring(3)));
        } else if (res.startsWith("rollback:")) {
            title(getString(R.string.done_rollback_title));
            body(getString(R.string.done_rollback_body, res.substring(9)), Ui.TEXT);
        } else {
            title(getString(R.string.status_up_to_date));
        }
        body(getString(R.string.status_version, Ota.currentVersion()), Ui.TEXT);
        long lc = st.lastCheck();
        body(lc > 0 ? getString(R.string.status_checked, DateUtils.formatSameDayTime(lc, System.currentTimeMillis(),
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)) : getString(R.string.status_never_checked), Ui.DIM);
        LinearLayout row = buttons();
        return addButton(row, R.string.btn_check, true, v -> check());
    }

    /**
     * The update runs, but the boot gate's check after it failed (z9x_ota.sh: unhealthy, no rollback
     * possible any more): what was found in plain words, the rescue hint with a QR code to the guide, and
     * the gate's own words for support.
     */
    private void renderProblem(String version, String why) {
        title(getString(R.string.done_unhealthy_title, version));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(this, getString(R.string.done_unhealthy_body, BootReceiver.problemText(this, why)),
                18, Ui.TEXT, 400), Ui.match());
        col.addView(Ui.text(this, getString(R.string.done_rescue_scan), 18, Ui.DIM, 400),
                Ui.margins(Ui.match(), 0, Ui.dp(this, 10), 0, 0));
        Outcome.Why kind = Outcome.why(why);
        if (kind != Outcome.Why.NONE && kind != Outcome.Why.OTHER) {   // OTHER: already in the sentence
            col.addView(Ui.text(this, getString(R.string.done_details, why.trim()), 18, Ui.DIM, 400),
                    Ui.margins(Ui.match(), 0, Ui.dp(this, 10), 0, 0));
        }
        box.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String url = "ru".equals(Locale.getDefault().getLanguage()) ? Ota.GUIDE_RESCUE_RU : Ota.GUIDE_RESCUE_EN;
        int s = Ui.dp(this, 150);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(s, s);
        qp.setMarginStart(Ui.dp(this, 24));     // the gap stays between text and code in RTL (Arabic)
        box.addView(new Ui.QrView(this, url), qp);
        left.addView(box, Ui.margins(Ui.match(), 0, 0, 0, Ui.dp(this, 10)));
    }

    private View renderError(Store st) {
        String res = st.errorRes(), arg = st.errorArg();
        LinearLayout row;
        if ("blk_vbmeta_title".equals(res)) {
            title(getString(R.string.blk_vbmeta_title));
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            TextView t = Ui.text(this, getString(R.string.blk_vbmeta_body, arg), 18, Ui.TEXT, 400);
            box.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String url = "ru".equals(Locale.getDefault().getLanguage()) ? Ota.GUIDE_VBMETA_RU : Ota.GUIDE_VBMETA_EN;
            Ui.QrView q = new Ui.QrView(this, url);
            int s = Ui.dp(this, 150);
            box.addView(q, Ui.margins(new LinearLayout.LayoutParams(s, s), Ui.dp(this, 24), 0, 0, 0));
            left.addView(box, Ui.match());
            row = buttons();
            View f = addButton(row, R.string.btn_retry, true, v -> retry());
            addButton(row, R.string.btn_later, false, v -> finish());
            return f;
        }
        if ("blk_merge".equals(res)) {
            title(getString(R.string.blk_merge));
            body(getString(R.string.blk_merge_body), Ui.DIM);
        } else {
            title(str(res, arg));
        }
        row = buttons();
        View f = addButton(row, R.string.btn_retry, true, v -> retry());
        addButton(row, R.string.btn_later, false, v -> finish());
        return f;
    }

    private void changelog(UpdateManifest m) {
        String text = m.changelogFor(Locale.getDefault());
        if (text.isEmpty()) return;
        LinearLayout c = Ui.card(this);
        int p = Ui.dp(this, 12);
        TextView h = Ui.text(this, getString(R.string.avail_whats_new), 15, Ui.ACCENT, 500);
        h.setPadding(p, p / 2, p, 0);
        c.addView(h);
        TextView t = Ui.text(this, text, 17, Ui.TEXT, 400);
        t.setPadding(p, p / 2, p, p);
        c.addView(t);
        left.addView(c, Ui.margins(Ui.match(), 0, Ui.dp(this, 14), 0, 0));
    }

    private View renderConsent() {
        brand();
        title(getString(R.string.consent_title));
        body(getString(R.string.consent_body, Ota.slotLetter(Ota.otherSlot()), Ota.slotLetter(Ota.slot())), Ui.TEXT);
        LinearLayout row = buttons();
        View deny = addButton(row, R.string.consent_deny, false, v -> {
            screen = Screen.MAIN;
            refresh();
        });
        addButton(row, R.string.consent_allow, true, v -> {
            Store st = Store.get(this);
            st.setFirmwareConsent(true);
            screen = Screen.MAIN;
            startInstall(st.state());
            refresh();
        });
        return deny;   // safe default: the user must move to "Allow" on purpose
    }

    private View renderLicenses() {
        brand();
        title(getString(R.string.about_licenses));
        // Google's terms only where Google apps are installed (Lumen OS without Google has none)
        boolean gms = false;
        try {
            getPackageManager().getPackageInfo("com.google.android.gms",
                    android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
            gms = true;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        int[] ids = gms ? new int[] {R.string.lic_lumen, R.string.lic_aosp, R.string.lic_gapps, R.string.lic_thirdparty,
                R.string.lic_full, R.string.lic_disclaimer}
                : new int[] {R.string.lic_lumen, R.string.lic_aosp, R.string.lic_thirdparty, R.string.lic_full,
                R.string.lic_disclaimer};
        for (int id : ids) body(getString(id), id == R.string.lic_disclaimer ? Ui.DIM : Ui.TEXT);
        if (left.getParent() instanceof ScrollView) ((ScrollView) left.getParent()).scrollTo(0, 0);
        return null;   // BACK returns; focus stays on the Licenses row
    }

    private void renderSide(Store st) {
        right.removeAllViews();
        LinearLayout set = Ui.card(this);
        int[] autoNames = {R.string.auto_off, R.string.auto_notify, R.string.auto_download};
        int mode = Math.max(0, Math.min(2, st.autoMode()));
        set.addView(Ui.row(this, getString(R.string.row_auto), getString(autoNames[mode]), v -> {
            st.setAutoMode((st.autoMode() + 1) % 3);
            if (st.autoMode() != Store.AUTO_OFF) CheckJob.schedule(this);
        }, true), Ui.match());
        set.addView(Ui.row(this, getString(R.string.row_usb), "", v -> checkUsb()), Ui.match());
        TextView hint = Ui.text(this, getString(R.string.auto_hint), 14, Ui.DIM, 400);
        int p = Ui.dp(this, 20);
        hint.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 10));
        set.addView(hint);
        right.addView(set, Ui.match());

        TextView h = Ui.text(this, getString(R.string.about_header), 15, Ui.DIM, 500);
        right.addView(h, Ui.margins(Ui.wrap(), Ui.dp(this, 20), Ui.dp(this, 22), 0, Ui.dp(this, 8)));
        LinearLayout ab = Ui.card(this);
        ab.addView(Ui.row(this, getString(R.string.about_version), "Lumen OS " + Ota.currentVersion(), null), Ui.match());
        String build = Ota.buildId().isEmpty() ? Ota.prop("ro.build.display.id") : Ota.buildId();
        ab.addView(Ui.row(this, getString(R.string.about_build), build, null, true), Ui.match());
        ab.addView(Ui.row(this, getString(R.string.about_author), Ota.AUTHOR, null), Ui.match());
        ab.addView(Ui.row(this, getString(R.string.about_project), "github.com/" + Ota.REPO, null, true), Ui.match());
        LinearLayout qrRow = new LinearLayout(this);
        qrRow.setOrientation(LinearLayout.HORIZONTAL);
        qrRow.setGravity(Gravity.CENTER_VERTICAL);
        qrRow.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 10));
        int qs = Ui.dp(this, 96);
        qrRow.addView(new Ui.QrView(this, Ota.PROJECT_URL), new LinearLayout.LayoutParams(qs, qs));
        TextView scan = Ui.text(this, getString(R.string.about_scan), 14, Ui.DIM, 400);
        qrRow.addView(scan, Ui.margins(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                Ui.dp(this, 16), 0, 0, 0));
        ab.addView(qrRow, Ui.match());
        ab.addView(Ui.row(this, getString(R.string.about_licenses), "›", v -> {
            screen = Screen.LICENSES;
            refresh();
        }), Ui.match());
        right.addView(ab, Ui.match());
    }

    private void updateDynamic(Store st) {
        if (progress == null) return;
        Store.State s = st.state();
        if (s == Store.State.DOWNLOADING || s == Store.State.PAUSED) {
            long b = st.dlBytes(), t = st.dlTotal();
            progress.set(t > 0 ? (float) b / t : -1f);
            if (s == Store.State.DOWNLOADING && t > 0) {
                long sp = st.dlSpeed();
                progressText.setText(getString(R.string.dl_progress, Formatter.formatShortFileSize(this, b),
                        Formatter.formatShortFileSize(this, t), Formatter.formatShortFileSize(this, sp)));
                if (sp > 0) {
                    int min = (int) Math.max(1, (t - b) / sp / 60);
                    etaText.setText(getResources().getQuantityString(R.plurals.dl_eta, min, min));
                } else {
                    etaText.setText(R.string.dl_waiting_network);
                }
            }
        } else if (s == Store.State.INSTALLING) {
            float f = st.progress();
            progress.set(f);
            progressText.setText(String.format(Locale.getDefault(), "%d%%", Math.round(f * 100)));
            etaText.setText("");
        } else if (s == Store.State.CHECKING) {
            progress.set(-1f);
        }
    }
}
