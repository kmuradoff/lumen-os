package org.lumen.projector.fit;

import android.content.Context;

import org.lumen.projector.R;
import org.lumen.projector.ui.ChoiceRow;
import org.lumen.projector.ui.NavRow;
import org.lumen.projector.ui.Page;
import org.lumen.projector.ui.SliderRow;
import org.lumen.projector.ui.TextRow;

import java.util.Locale;

/**
 * Fit to my screen size: the quick panel page behind the keystone page's "Fit to screen size" row
 * (docs/fit_screen/DESIGN.md 1.2): the screen size (diagonal + unit + aspect, or width x height), the margin, the
 * fit mode, then "Fit now" and "Calibrate the zoom". Remote friendly, no keyboard: every number is a slider row
 * (0.5 steps, a held key speeds up). Only the rows of the chosen input are shown (never a disabled row). Every
 * change is saved at once (FitPrefs). Device-neutral Android code: the device is FitDevices.get().
 */
public final class FitScreenPage {
    /** What the page needs from the quick panel. */
    public interface Host {
        void push(Page p);
        void refreshPage(Page p);
        void closeThen(Runnable r);
    }

    private static final int DIAG_IN_MIN = 40, DIAG_IN_MAX = 300, CM_MIN = 50, CM_MAX = 500, MARGIN_MAX_CM = 10;

    private final Context app;
    private final Host host;
    private final String model;
    private final FitStore.Screen s;
    private final Page page;
    private TextRow summary;

    private FitScreenPage(Context app, Host host) {
        this.app = app;
        this.host = host;
        this.model = FitDevices.get(app).modelId();
        FitStore.Screen saved = FitPrefs.screen(app, model);
        this.s = saved != null ? saved : new FitStore.Screen();
        this.page = new Page(app.getString(R.string.fit_page_title));
        page.setOnShown(this::updateSummary);
        build();
    }

    public static Page build(Context ctx, Host host) {
        return new FitScreenPage(ctx.getApplicationContext(), host).page;
    }

    private String str(int res) { return app.getString(res); }

    private void build() {
        page.clear();
        summary = page.add(new TextRow(app, ""));
        boolean wh = "wh".equals(s.mode);
        ChoiceRow input = page.add(new ChoiceRow(app, str(R.string.fit_input),
                new CharSequence[] {str(R.string.fit_input_diag), str(R.string.fit_input_wh)}, (row, i) -> {
                    String m = i == 1 ? "wh" : "diag";
                    if (m.equals(s.mode)) return;
                    if (i == 1) {                                     // keep the size the diagonal gave
                        try {
                            ScreenSize z = s.toSize();
                            s.wMm = round05(z.wMm / 10) * 10;
                            s.hMm = round05(z.hMm / 10) * 10;
                        } catch (IllegalArgumentException ignored) { }
                    }
                    s.mode = m;
                    save();
                    build();
                    page.setInitialFocus(1);
                    host.refreshPage(page);
                }));
        input.setSelected(wh ? 1 : 0);
        if (!wh) {
            SliderRow diag = page.add(new SliderRow(app, str(R.string.fit_diagonal), 0, 1, null));
            setDiagRange(diag, s.inches);
            diag.setFormatter(v -> app.getString(s.inches ? R.string.fit_value_in : R.string.fit_value_cm, num(v / 2.0)));
            diag.setOnSlide(new SliderRow.OnSlide() {
                @Override public void onChanging(SliderRow row, int v) { s.diag = v / 2.0; updateSummary(); }
                @Override public void onCommit(SliderRow row, int v) { s.diag = v / 2.0; save(); }
            });
            ChoiceRow unit = page.add(new ChoiceRow(app, str(R.string.fit_unit),
                    new CharSequence[] {str(R.string.fit_unit_in), str(R.string.fit_unit_cm)}, (row, i) -> {
                        boolean in = i == 0;
                        if (in == s.inches) return;
                        s.diag = round05(in ? s.diag / 2.54 : s.diag * 2.54);
                        s.inches = in;
                        setDiagRange(diag, in);
                        save();
                    }));
            unit.setSelected(s.inches ? 0 : 1);
            ChoiceRow aspect = page.add(new ChoiceRow(app, str(R.string.fit_aspect), ScreenSize.ASPECT_LABELS, (row, i) -> {
                s.aspect = i;
                save();
            }));
            aspect.setSelected(s.aspect);
        } else {
            SliderRow w = page.add(cmRow(R.string.fit_width, s.wMm, v -> s.wMm = v));
            SliderRow h = page.add(cmRow(R.string.fit_height, s.hMm, v -> s.hMm = v));
            w.setValue(clamp((int) Math.round(s.wMm / 5), CM_MIN * 2, CM_MAX * 2));
            h.setValue(clamp((int) Math.round(s.hMm / 5), CM_MIN * 2, CM_MAX * 2));
        }
        SliderRow margin = page.add(new SliderRow(app, str(R.string.fit_margin), 0, MARGIN_MAX_CM * 2, null));
        margin.setFormatter(v -> app.getString(R.string.fit_value_cm, num(v / 2.0)));
        margin.setValue((int) Math.round(s.marginMm / 5));
        margin.setOnSlide(new SliderRow.OnSlide() {
            @Override public void onChanging(SliderRow row, int v) { s.marginMm = v * 5; updateSummary(); }
            @Override public void onCommit(SliderRow row, int v) { s.marginMm = v * 5; save(); }
        });
        ChoiceRow mode = page.add(new ChoiceRow(app, str(R.string.fit_mode),
                new CharSequence[] {str(R.string.fit_mode_inside), str(R.string.fit_mode_closest)}, (row, i) -> {
                    s.fitMode = i == 1 ? "closest" : "inside";
                    save();
                }));
        mode.setSelected("closest".equals(s.fitMode) ? 1 : 0);
        page.add(new NavRow(app, str(R.string.fit_start), () -> {
            save();
            host.closeThen(() -> FitRunner.startFit(app));
        }).setChevron(false));
        page.add(new NavRow(app, str(R.string.fit_calibrate), () -> host.push(calibPage())));
        updateSummary();
    }

    private interface Setter { void set(double mm); }

    /** Width / height in cm, 0.5 cm steps (value = half centimetres). */
    private SliderRow cmRow(int title, double mm, Setter set) {
        SliderRow r = new SliderRow(app, str(title), CM_MIN * 2, CM_MAX * 2, null);
        r.setFormatter(v -> app.getString(R.string.fit_value_cm, num(v / 2.0)));
        r.setOnSlide(new SliderRow.OnSlide() {
            @Override public void onChanging(SliderRow row, int v) { set.set(v * 5); updateSummary(); }
            @Override public void onCommit(SliderRow row, int v) { set.set(v * 5); save(); }
        });
        return r;
    }

    private void setDiagRange(SliderRow diag, boolean inches) {
        int min = inches ? DIAG_IN_MIN * 2 : (int) Math.round(DIAG_IN_MIN * 2.54 * 2);
        int max = inches ? DIAG_IN_MAX * 2 : (int) Math.round(DIAG_IN_MAX * 2.54 * 2);
        diag.setRange(min, max);
        diag.setValue(clamp((int) Math.round(s.diag * 2), min, max));
        s.diag = diag.getValue() / 2.0;
    }

    /** "Calibrate the zoom": how many positions, then start (the quick panel closes for the run). */
    private Page calibPage() {
        Page p = new Page(str(R.string.fit_cal_title));
        p.add(new TextRow(app, str(R.string.fit_cal_intro)));
        final int[] kind = {0};
        ChoiceRow k = p.add(new ChoiceRow(app, str(R.string.fit_cal_kind), new CharSequence[] {
                str(R.string.fit_cal_two), str(R.string.fit_cal_three), str(R.string.fit_cal_all)}, (row, i) -> kind[0] = i));
        k.setSelected(0);
        p.add(new NavRow(app, str(R.string.fit_cal_start), () -> host.closeThen(() -> FitRunner.startCalibration(app,
                kind[0] == 2 ? FitCalibration.Kind.FULL : kind[0] == 1 ? FitCalibration.Kind.THREE
                        : FitCalibration.Kind.TWO))).setChevron(false));
        return p;
    }

    private void save() {
        FitPrefs.setScreen(app, model, s);
        updateSummary();
    }

    private void updateSummary() {
        if (summary == null) return;
        StringBuilder b = new StringBuilder();
        try {
            ScreenSize z = s.toSize();
            b.append(app.getString(R.string.fit_screen_is, num(z.wMm / 10), num(z.hMm / 10)));
        } catch (IllegalArgumentException e) {
            b.append(str(R.string.fit_screen_bad));
        }
        FitStore.Calib c = FitPrefs.calib(app, model);
        b.append('\n').append(c != null ? app.getString(R.string.fit_calib_done, num1(c.distanceMm / 1000))
                : str(R.string.fit_calib_none));
        summary.setText(b);
    }

    static double round05(double v) { return Math.round(v * 2) / 2.0; }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /** 100 / 221.5: at most one decimal, a point in every language (as the gamma value, Locale.ROOT). */
    static String num(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.1f", r);
    }

    static String num1(double v) { return String.format(Locale.ROOT, "%.2f", v); }
}
