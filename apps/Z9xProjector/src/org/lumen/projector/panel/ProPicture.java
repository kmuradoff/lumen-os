package org.lumen.projector.panel;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;

import org.lumen.projector.Hal;
import org.lumen.projector.R;
import org.lumen.projector.Ui;
import org.lumen.projector.hal.Gmpf2Client;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.ui.ChoiceRow;
import org.lumen.projector.ui.HeaderRow;
import org.lumen.projector.ui.NavRow;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.Page;
import org.lumen.projector.ui.SliderRow;
import org.lumen.projector.ui.ToggleRow;

import java.util.Locale;

/**
 * Lumen OS 1.0.4: "Professional settings" of the quick panel's Picture page, the stock image mode 3.0
 * "专业设置" of the Z9X (research/z9x_stock_decompiled, PictureViewModel / GmDisplayManager), in the stock order:
 *   Colour:   White balance (own page: 2-point gains and offsets, 11-point by level), Colour space, Colour
 *             optimisation, Colour correction (own page: 7 colours x hue, saturation, lightness)
 *   Contrast: Gamma (the curve of the signal: BT.1886 SDR, HLG, ST.2084 HDR, or Manual) and Gamma value
 *             1.8..2.6 (Manual only), HDR dynamic tone mapping (HDR10 only), Local contrast, Dynamic
 *             contrast, Dynamic black enhancement
 *   Motion:   Original frame rate (turns Ultra 120 Hz off as stock; motion smoothing waits while it is on)
 *   Clarity:  Noise reduction
 *   Reset professional settings (IGmpf2 278 (0, 0, 2), the stock triple)
 * AI contrast, super resolution and Ultra 120 Hz stay on the Picture page itself. Every value is read from
 * the HAL when a page shows and after every change (nothing is stored here); a row whose getter fails is
 * hidden, and so are 11-point white balance and colour correction when their reply does not pass the
 * client's struct check. Values are sent as the stock app sends them (no remapping). No factory white
 * balance or calibration call exists in the clients (check_hal_callers.py).
 */
final class ProPicture {
    private static final String TAG = "Z9xPanel";
    /** Stock waits 100 ms after a white balance gain move and drops the older value. */
    private static final long WB_COMMIT_MS = 100;
    private static final long CHOICE_COMMIT_MS = 500;

    interface Host {
        void push(Page p);
        void confirm(int titleRes, CharSequence msg, int okRes, Runnable onOk);
    }

    private static final GmpfClient.ColorSpace[] CS_ORDER = {GmpfClient.ColorSpace.NATIVE,
            GmpfClient.ColorSpace.BT2020, GmpfClient.ColorSpace.ADOBE_RGB, GmpfClient.ColorSpace.DCI_P3,
            GmpfClient.ColorSpace.REC709};
    private static final GmpfClient.NoiseReduction[] NR_ORDER = GmpfClient.NoiseReduction.values();
    private static final GmpfClient.WbItem[] WB_ORDER = {GmpfClient.WbItem.RED_GAIN, GmpfClient.WbItem.GREEN_GAIN,
            GmpfClient.WbItem.BLUE_GAIN, GmpfClient.WbItem.RED_OFFSET, GmpfClient.WbItem.GREEN_OFFSET,
            GmpfClient.WbItem.BLUE_OFFSET};
    private static final Gmpf2Client.HsyType[] HSY_ORDER = Gmpf2Client.HsyType.values();
    /** Gamma keys of IGmpf2 327 (stock freshGammaValue): curves, and the 1.8..2.6 index 0..8 ("manual"). */
    private static final int GAMMA_BT1886 = 10, GAMMA_ST2084 = 11, GAMMA_HLG = 12;
    /** Choosing Manual sends 2.2 (index 4), as stock. */
    private static final int GAMMA_MANUAL_DEFAULT = 4;

    /** One read of everything these pages show (HAL thread). Null fields = getter failed (row hidden). */
    static final class State {
        Integer hdr, colorSpace, gamma, localContrast, noise;
        Boolean colorOpt, hdrMapping, dynContrast, dynBlack, nativeFps;
        final Integer[] wb = new Integer[WB_ORDER.length];
        /** [HsyType][colour], null when 324 failed for any type. */
        int[][] hsy;
        /** {red, green, blue} of the shown 11-point level, null when 281 failed. */
        int[] mpWb;
    }

    private final Context app;
    private final Host host;
    private final SharedPreferences prefs;
    final Page page, wbPage, ccPage;
    private final ChoiceRow colorSpace, gamma, gammaValue, localContrast, noise, wbPoint, ccColor;
    private final ToggleRow colorOpt, hdrMapping, dynContrast, dynBlack, nativeFps;
    private final NavRow ccNav;
    private final HeaderRow wb11Header;
    private final SliderRow[] wb = new SliderRow[WB_ORDER.length];
    private final SliderRow[] wb11 = new SliderRow[3];
    private final SliderRow[] hsy = new SliderRow[HSY_ORDER.length];
    /** Gamma choices shown now: key per option (a curve, or -1 = Manual). */
    private int[] gammaKeys = {GAMMA_BT1886, -1};
    private int wbPointIndex;
    private int ccColorIndex;
    private int gen;
    private State state;

    ProPicture(Context app, SharedPreferences prefs, Host host) {
        this.app = app;
        this.prefs = prefs;
        this.host = host;

        // ---- white balance page
        wbPage = new Page(s(R.string.pro_wb));
        wbPage.setOnShown(this::refresh);
        wbPage.add(new HeaderRow(app, s(R.string.pro_wb_2)));
        int[] wbTitles = {R.string.pro_red_gain, R.string.pro_green_gain, R.string.pro_blue_gain,
                R.string.pro_red_offset, R.string.pro_green_offset, R.string.pro_blue_offset};
        for (int i = 0; i < WB_ORDER.length; i++) {
            final GmpfClient.WbItem item = WB_ORDER[i];
            wb[i] = wbPage.add(new SliderRow(app, s(wbTitles[i]), 0, 100, slide(v ->
                    change("wb " + item + " " + v, (g, g2) -> g.setWb(item, v)))));
            wb[i].setCommitDelay(WB_COMMIT_MS);
        }
        wb11Header = wbPage.add(new HeaderRow(app, s(R.string.pro_wb_11)));
        CharSequence[] points = new CharSequence[Gmpf2Client.WB_POINTS.length];
        for (int i = 0; i < points.length; i++) points[i] = app.getString(R.string.pro_wb_level, Gmpf2Client.WB_POINTS[i]);
        wbPoint = wbPage.add(new ChoiceRow(app, s(R.string.pro_wb_point), points, (row, i) -> safe("wb point", () -> {
            if (i < 0 || i >= points.length) return;
            wbPointIndex = i;
            refresh();
        }).run()));
        int[] rgb = {R.string.pro_red, R.string.pro_green, R.string.pro_blue};
        for (int c = 0; c < 3; c++) {
            final int channel = c;
            wb11[c] = wbPage.add(new SliderRow(app, s(rgb[c]), 0, 100, slide(v -> {
                final int point = Gmpf2Client.WB_POINTS[wbPointIndex];
                change("11-point wb " + point + " ch" + channel + " " + v, (g, g2) -> {
                    int[] cur = g2.getMultiPointWb(point);           // the other two as the HAL has them
                    cur[channel] = v;
                    if (!g2.setMultiPointWb(point, cur[0], cur[1], cur[2])) throw new IllegalStateException("282 false");
                });
            })));
            wb11[c].setCommitDelay(WB_COMMIT_MS);
        }

        // ---- colour correction page
        ccPage = new Page(s(R.string.pro_cc));
        ccPage.setOnShown(this::refresh);
        CharSequence[] colours = labels(R.string.pro_red, R.string.pro_green, R.string.pro_blue, R.string.pro_cyan,
                R.string.pro_magenta, R.string.pro_yellow, R.string.pro_skin);
        ccColor = ccPage.add(new ChoiceRow(app, s(R.string.pro_cc_color), colours, (row, i) -> safe("cc colour", () -> {
            if (i < 0 || i >= Gmpf2Client.HSY_COLORS) return;
            ccColorIndex = i;
            apply(state);
        }).run()));
        int[] hsyTitles = {R.string.pro_hue, R.string.panel_pq_saturation, R.string.panel_pq_brightness};
        for (int t = 0; t < HSY_ORDER.length; t++) {
            final Gmpf2Client.HsyType type = HSY_ORDER[t];
            hsy[t] = ccPage.add(new SliderRow(app, s(hsyTitles[t]), 0, 100, slide(v -> {
                final int colour = ccColorIndex;
                change("hsy " + type + " colour " + colour + " " + v, (g, g2) -> {
                    int[] arr = g2.getHsyColorModel(type);             // whole array, one colour changed (stock)
                    arr[colour] = v;
                    if (!g2.setHsyColorModel(type, arr)) throw new IllegalStateException("325 false");
                });
            })));
            hsy[t].setCommitDelay(WB_COMMIT_MS);
        }

        // ---- the professional settings page
        page = new Page(s(R.string.pro_title));
        page.setOnShown(this::refresh);
        page.add(new HeaderRow(app, s(R.string.pro_color)));
        page.add(new NavRow(app, s(R.string.pro_wb), safe("wb page", () -> host.push(wbPage))));
        CharSequence[] csLabels = {s(R.string.pro_cs_native), "BT.2020", "Adobe RGB", "DCI-P3", "Rec.709"};
        colorSpace = page.add(new ChoiceRow(app, s(R.string.pro_color_space), csLabels, (row, i) -> safe("cs", () -> {
            if (i < 0 || i >= CS_ORDER.length) return;
            final GmpfClient.ColorSpace cs = CS_ORDER[i];
            change("112 colour space " + cs, (g, g2) -> g.setColorSpace(cs));
        }).run()));
        colorSpace.setCommitDelay(CHOICE_COMMIT_MS);
        colorOpt = page.add(new ToggleRow(app, s(R.string.pro_color_opt), (row, w) -> safe("323", () ->
                change("IGmpf2 323 colour optimisation " + w, (g, g2) -> bool(g2.setColorOptimization(w), "323"))).run()));
        ccNav = page.add(new NavRow(app, s(R.string.pro_cc), safe("cc page", () -> host.push(ccPage))));

        page.add(new HeaderRow(app, s(R.string.panel_pq_contrast)));
        gamma = page.add(new ChoiceRow(app, s(R.string.pro_gamma), gammaLabels(gammaKeys), (row, i) -> safe("gamma", () -> {
            if (i < 0 || i >= gammaKeys.length) return;
            final int key = gammaKeys[i] < 0 ? GAMMA_MANUAL_DEFAULT : gammaKeys[i];
            change("IGmpf2 327 gamma " + key, (g, g2) -> bool(g2.setGamma(key), "327"));
        }).run()));
        gamma.setCommitDelay(CHOICE_COMMIT_MS);
        CharSequence[] gv = new CharSequence[9];
        for (int i = 0; i < gv.length; i++) gv[i] = String.format(Locale.getDefault(), "%.1f", 1.8 + i / 10.0);
        gammaValue = page.add(new ChoiceRow(app, s(R.string.pro_gamma_value), gv, (row, i) -> safe("gamma value", () -> {
            if (i < 0 || i > 8) return;
            change("IGmpf2 327 gamma index " + i, (g, g2) -> bool(g2.setGamma(i), "327"));
        }).run()));
        gammaValue.setCommitDelay(CHOICE_COMMIT_MS);
        hdrMapping = page.add(new ToggleRow(app, s(R.string.pro_hdr_mapping), (row, w) -> safe("270", () ->
                change("IGmpf2 270 HDR tone mapping " + w, (g, g2) -> bool(g2.setHdrDynamicToneMapping(w), "270"))).run()));
        CharSequence[] olmh = labels(R.string.panel_off, R.string.panel_low, R.string.panel_medium, R.string.panel_high);
        localContrast = page.add(new ChoiceRow(app, s(R.string.pro_local_contrast), olmh, (row, i) -> safe("655", () -> {
            if (i < 0 || i > 3) return;
            change("655 local contrast " + i, (g, g2) -> bool(g.setLocalContrast(i), "655"));
        }).run()));
        localContrast.setCommitDelay(CHOICE_COMMIT_MS);
        dynContrast = page.add(new ToggleRow(app, s(R.string.pro_dynamic_contrast), (row, w) -> safe("329", () ->
                change("IGmpf2 329 dynamic contrast " + w, (g, g2) -> bool(g2.setDynamicContrast(w), "329"))).run()));
        dynBlack = page.add(new ToggleRow(app, s(R.string.pro_dynamic_black), (row, w) -> safe("31", () ->
                change("IGmpf2 31 dynamic black " + w, (g, g2) -> bool(g2.setDynamicBlack(w), "31"))).run()));

        page.add(new HeaderRow(app, s(R.string.pro_motion)));
        nativeFps = page.add(new ToggleRow(app, s(R.string.pro_native_fps), (row, w) -> safe("284", () ->
                change("IGmpf2 284 original frame rate " + w, (g, g2) -> {
                    bool(g2.setNativeFrameRate(w), "284");
                    if (w) {
                        // stock: original frame rate turns Ultra 120 Hz off (161(6), 2160p60)
                        boolean ok = g.setOutputTiming(GmpfClient.OutputTiming.ULTRA_120_OFF);
                        Log.i(TAG, "161(6) with original frame rate: " + ok);
                        if (ok) prefs.edit().putBoolean(QuickPanelController.KEY_ULTRA_120, false).apply();
                    }
                })).run()));

        page.add(new HeaderRow(app, s(R.string.pro_clarity)));
        CharSequence[] nr = labels(R.string.panel_off, R.string.panel_low, R.string.panel_medium, R.string.panel_high,
                R.string.panel_auto);
        noise = page.add(new ChoiceRow(app, s(R.string.pro_noise), nr, (row, i) -> safe("671", () -> {
            if (i < 0 || i >= NR_ORDER.length) return;
            final GmpfClient.NoiseReduction n = NR_ORDER[i];
            change("671 noise reduction " + n, (g, g2) -> bool(g.setNoiseReduction(n), "671"));
        }).run()));
        noise.setCommitDelay(CHOICE_COMMIT_MS);
        page.add(new NavRow(app, s(R.string.pro_reset), safe("pro reset", () ->
                host.confirm(R.string.pro_reset, s(R.string.pro_reset_msg), R.string.panel_reset,
                        () -> change("IGmpf2 278 reset professional",
                                (g, g2) -> bool(g2.resetPicture(Gmpf2Client.PictureReset.PROFESSIONAL), "278")))))).setChevron(false);
    }

    // ================================================================== read / show
    void refresh() {
        final int my = ++gen;
        final int point = Gmpf2Client.WB_POINTS[wbPointIndex];
        Hal.query((g, g2) -> read(g, g2, point), st -> {
            if (my != gen) return;
            state = Hal.isReady() ? st : null;
            apply(state);
        });
    }

    /** HAL thread. Never throws. */
    private static State read(GmpfClient g, Gmpf2Client g2, int point) {
        State s = new State();
        s.hdr = opt("620", g::getHdrType);
        Integer src = opt("696", g::getCurrentInputSource);
        Integer pm = opt("666", g::getPictureModeRaw);
        if (src != null && pm != null) {
            final int source = src == GmpfClient.SOURCE_HDMI1 || src == GmpfClient.SOURCE_HDMI2 ? src : 0;
            final int mode = pm;
            if (mode >= 0 && mode <= 40) s.colorSpace = opt("111", () -> g.getColorSpaceRaw(source, mode));
        }
        s.localContrast = opt("651", g::getLocalContrast);
        s.noise = opt("670", g::getNoiseReduction);
        for (int i = 0; i < WB_ORDER.length; i++) {
            final GmpfClient.WbItem it = WB_ORDER[i];
            s.wb[i] = opt("wb " + it, () -> g.getWb(it));
        }
        s.gamma = opt("g2 326", g2::getGamma);
        s.colorOpt = opt("g2 330", g2::getColorOptimization);
        s.hdrMapping = opt("g2 269", g2::getHdrDynamicToneMapping);
        s.dynContrast = opt("g2 328", g2::getDynamicContrast);
        s.dynBlack = opt("g2 29", g2::getDynamicBlack);
        s.nativeFps = opt("g2 283", g2::getNativeFrameRate);
        int[][] h = new int[HSY_ORDER.length][];
        boolean hsyOk = true;
        for (int t = 0; t < HSY_ORDER.length && hsyOk; t++) {
            final Gmpf2Client.HsyType type = HSY_ORDER[t];
            h[t] = opt("g2 324 " + type, () -> g2.getHsyColorModel(type));
            hsyOk = h[t] != null;
        }
        s.hsy = hsyOk ? h : null;
        s.mpWb = opt("g2 281 " + point, () -> g2.getMultiPointWb(point));
        return s;
    }

    private void apply(State st) {
        boolean ok = st != null;
        // colour
        int cs = -1;
        if (ok && st.colorSpace != null) for (int i = 0; i < CS_ORDER.length; i++) if (CS_ORDER[i].wire == st.colorSpace) cs = i;
        showChoice(colorSpace, cs, ok && st.colorSpace != null);
        showToggle(colorOpt, ok ? st.colorOpt : null, true);
        ccNav.setVisibility(ok && st.hsy != null ? View.VISIBLE : View.GONE);
        // gamma: the curve list follows the signal, as stock (SDR BT.1886, HLG, the HDR types ST.2084)
        int hdr = ok && st.hdr != null ? st.hdr : GmpfClient.HDR_SDR;
        int curve = hdr == GmpfClient.HDR_SDR ? GAMMA_BT1886 : hdr == GmpfClient.HDR_HLG ? GAMMA_HLG : GAMMA_ST2084;
        int gk = ok && st.gamma != null ? st.gamma : -2;
        gammaKeys = gk >= 10 && gk != curve ? new int[]{gk, curve, -1} : new int[]{curve, -1};
        gamma.setOptions(gammaLabels(gammaKeys));
        int gsel = gk >= 0 && gk <= 8 ? gammaKeys.length - 1 : indexOf(gammaKeys, gk);
        showChoice(gamma, gsel, ok && st.gamma != null && gsel >= 0);
        showChoice(gammaValue, gk, ok && gk >= 0 && gk <= 8);
        showToggle(hdrMapping, ok ? st.hdrMapping : null, hdr == GmpfClient.HDR_HDR10);   // stock: HDR10 only
        showChoice(localContrast, ok && st.localContrast != null ? st.localContrast : -1,
                ok && st.localContrast != null && st.localContrast >= 0 && st.localContrast <= 3);
        showToggle(dynContrast, ok ? st.dynContrast : null, true);
        showToggle(dynBlack, ok ? st.dynBlack : null, true);
        showToggle(nativeFps, ok ? st.nativeFps : null, true);
        showChoice(noise, ok && st.noise != null ? st.noise : -1, ok && st.noise != null && st.noise >= 0 && st.noise <= 4);
        // white balance
        for (int i = 0; i < wb.length; i++) {
            Integer v = ok ? st.wb[i] : null;
            if (!busy(wb[i])) wb[i].setValue(v);
            wb[i].setVisibility(v != null ? View.VISIBLE : View.GONE);
        }
        boolean mp = ok && st.mpWb != null;
        wb11Header.setVisibility(mp ? View.VISIBLE : View.GONE);
        wbPoint.setVisibility(mp ? View.VISIBLE : View.GONE);
        wbPoint.setSelected(wbPointIndex);
        for (int c = 0; c < 3; c++) {
            if (mp && !busy(wb11[c])) wb11[c].setValue(st.mpWb[c]);
            wb11[c].setVisibility(mp ? View.VISIBLE : View.GONE);
        }
        // colour correction
        ccColor.setSelected(ccColorIndex);
        for (int t = 0; t < hsy.length; t++) {
            Integer v = ok && st.hsy != null ? st.hsy[t][ccColorIndex] : null;
            if (!busy(hsy[t])) hsy[t].setValue(v);
            hsy[t].setRowEnabled(v != null);
        }
    }

    // ================================================================== helpers
    private interface IntAction { void run(int v); }

    private SliderRow.OnSlide slide(IntAction commit) {
        return new SliderRow.OnSlide() {
            @Override public void onChanging(SliderRow row, int value) { row.setTag(android.os.SystemClock.uptimeMillis()); }
            @Override public void onCommit(SliderRow row, int value) { safe("slider", () -> commit.run(value)).run(); }
        };
    }

    /** A slider the user is moving is not moved back by an older read-back. */
    private static boolean busy(SliderRow r) {
        Object t = r.getTag();
        return r.isFocused() && t instanceof Long && android.os.SystemClock.uptimeMillis() - (Long) t < 800;
    }

    private void change(String what, Hal.Task t) {
        if (!Hal.isReady()) {
            Ui.toast(app, R.string.toast_not_ready);
            refresh();
            return;
        }
        boolean queued = Hal.run((g, g2) -> {
            try {
                t.run(g, g2);
                Log.i(TAG, what);
            } catch (Throwable e) {
                Log.w(TAG, what + ": " + e);
                Ui.toast(app, R.string.toast_failed);
            }
        });
        if (!queued) Ui.toast(app, R.string.toast_not_ready);
        refresh();
    }

    /** A setter that answered false: the change did not happen (the read-back shows the real value). */
    private static void bool(boolean ok, String code) {
        if (!ok) throw new IllegalStateException(code + " returned false");
    }

    private interface Get<T> { T get() throws Exception; }

    private static <T> T opt(String what, Get<T> g) {
        try {
            return g.get();
        } catch (Throwable t) {
            Log.d(TAG, "read " + what + ": " + t);
            return null;
        }
    }

    /** Unknown (getter failed) or not offered now: hidden, never shown disabled. */
    private static void showToggle(ToggleRow r, Boolean v, boolean offered) {
        r.setChecked(v);
        r.setVisibility(v != null && offered ? View.VISIBLE : View.GONE);
    }

    private static void showChoice(ChoiceRow r, int index, boolean shown) {
        r.setSelected(shown ? index : -1);
        r.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    private CharSequence[] gammaLabels(int[] keys) {
        CharSequence[] out = new CharSequence[keys.length];
        for (int i = 0; i < keys.length; i++) {
            int k = keys[i];
            out[i] = k < 0 ? s(R.string.pro_gamma_manual) : k == GAMMA_BT1886 ? "BT.1886" : k == GAMMA_ST2084 ? "ST.2084"
                    : k == GAMMA_HLG ? "HLG" : "DICOM";
        }
        return out;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    private CharSequence[] labels(int... res) {
        CharSequence[] out = new CharSequence[res.length];
        for (int i = 0; i < res.length; i++) out[i] = app.getString(res[i]);
        return out;
    }

    private String s(int res) { return app.getString(res); }

    private static Runnable safe(String what, Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable t) {
                Log.e(TAG, what + " failed", t);
            }
        };
    }
}
