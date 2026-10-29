package org.lumen.shots;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.util.Log;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.NotifyProbe;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.tvinput.BrandProbe;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Render harness of the notification cards (tools/play6/notify_shots, docs/play6/impl/t17-notify.md). Runs on
 * the 1920 x 1080 test emulator (density 320: 1 design px = 1 px, as the stock 1080p UI), never on a projector:
 *  - frames: whole-screen captures (PixelCopy of this window) of the real Notify window logic (through the
 *    OverlayHost stand-in: top right, card + inset, relayout next to a right-side panel) and of the TV input
 *    viewer's BrandCards placed as PassthroughActivity places them;
 *  - sheets: every card of assets/cards.tsv (cards.py) drawn off screen at 1:1 on contact sheets, with its
 *    layout facts in report.tsv (lines, ellipsis, size, split).
 * Extra "before" (boolean): only the frames, for the sources as they were (the sheets read fields the old
 * Notify does not have). Output: getExternalFilesDir/shots/*.png, report.tsv, done.txt.
 */
public class ShotsActivity extends Activity {
    private static final String TAG = "Z9xShots";
    private static final int W = 1920, H = 1080;
    private static final int PANEL_W = Math.round(W * 0.34f);       // Panel window: 0.34 x W, card 24 inside

    private interface Step {
        void run(Runnable done) throws Exception;
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<Step> plan = new ArrayList<>();
    private int next;
    private final List<String[]> cards = new ArrayList<>();
    private FrameLayout layer, viewer;
    private View panel;
    private BrandProbe state, banner;
    private File out;
    private PrintWriter report;
    private boolean before;
    private int errors;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        before = getIntent().getBooleanExtra("before", false);
        FrameLayout root = new FrameLayout(this);
        root.addView(new Scene(this), new FrameLayout.LayoutParams(-1, -1));
        panel = new View(this);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(0xEB1C1C1C);
        pb.setCornerRadius(36);
        pb.setStroke(3, 0x1AFFFFFF);
        panel.setBackground(pb);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(PANEL_W - 48, H - 48, Gravity.RIGHT | Gravity.TOP);
        pl.setMargins(24, 24, 24, 24);
        root.addView(panel, pl);
        panel.setVisibility(View.GONE);
        viewer = new FrameLayout(this);
        viewer.setBackgroundColor(Color.BLACK);
        viewer.setVisibility(View.GONE);
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        state = new BrandProbe(this, false);                         // PassthroughActivity.buildUi
        viewer.addView(state.view, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        banner = new BrandProbe(this, true);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bl.setMargins(0, dp(24), 0, 0);
        bl.setMarginStart(dp(32));
        viewer.addView(banner.view, bl);
        layer = new FrameLayout(this);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        OverlayHost.layer = layer;
        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);                // the whole 1920 x 1080, no bars
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) {
            ic.hide(android.view.WindowInsets.Type.systemBars());
            ic.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        try {
            out = new File(getExternalFilesDir(null), "shots");
            File[] old = out.listFiles();
            if (old != null) for (File f : old) f.delete();
            out.mkdirs();
            report = new PrintWriter(new FileOutputStream(new File(out, "report.tsv")));
            BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("cards.tsv"), "UTF-8"));
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split("\t", -1);
                for (int i = 0; i < f.length; i++) f[i] = unesc(f[i]);
                cards.add(f);
            }
            r.close();
            plan();
        } catch (Throwable t) {
            Log.e(TAG, "setup", t);
            return;
        }
        root.post(this::go);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ the plan
    private String[] row(String kind, String lang, String key, String variant) {
        for (String[] c : cards) {
            if (c[0].equals(kind) && c[1].equals(lang) && c[2].equals(key) && (variant == null || c[3].equals(variant))) return c;
        }
        throw new IllegalArgumentException("no card " + kind + " " + lang + " " + key + " " + variant);
    }

    /** The longest card of a language, or the shortest one with a description (the shortest of all is Autofocus). */
    private String[] extreme(String lang, boolean longest) {
        String[] best = null;
        for (String[] c : cards) {
            if (!c[0].equals("notify") || !c[1].equals(lang) || !c[6].isEmpty()) continue;
            if (!longest && c[5].isEmpty()) continue;
            int n = c[4].length() + c[5].length();
            if (best == null || (longest ? n > best[4].length() + best[5].length() : n < best[4].length() + best[5].length())) best = c;
        }
        return best;
    }

    private void plan() {
        String[] langs = before ? new String[]{"ru"} : new String[]{"en", "ru", "de"};
        for (String lang : langs) {
            notifyFrame("frame_notify_autofocus_" + lang, row("notify", lang, "Z9xProjector:toast_af", null), false);
            notifyFrame("frame_notify_battery_" + lang, row("notify", lang, "Play6Projector:p6_card_low_power+eco", null), false);
            notifyFrame("frame_notify_shortest_" + lang, extreme(lang, false), false);
            notifyFrame("frame_notify_longest_" + lang, extreme(lang, true), false);
            panelFrame("frame_notify_panel_" + lang, row("notify", lang, "Play6Projector:p6_card_low_power+eco", null));
            viewerFrame("frame_viewer_asleep_" + lang, lang, null);
            viewerFrame("frame_viewer_banner_" + lang, lang, "banner");
        }
        if (!before) {
            notifyFrame("frame_notify_icon_en", row("notify", "en", "Z9xProjector:cec_paused", null), true);
            notifyFrame("frame_notify_foreign", row("notify", "xx", "foreign:title_desc", null), false);
            act(Notify::hide);
            sheets("notify");
            sheets("brand");
            plan.add(done -> {
                battery();
                done.run();
            });
        }
    }

    private void act(Runnable r) {
        plan.add(done -> {
            r.run();
            done.run();
        });
    }

    private void pause(long ms) {
        plan.add(done -> h.postDelayed(done, ms));
    }

    private Drawable icon() {
        GradientDrawable tile = new GradientDrawable();
        tile.setShape(GradientDrawable.OVAL);
        tile.setColor(0xFF0070D1);
        Drawable glyph = getDrawable(R.drawable.ic_banner_console).mutate();
        return new LayerDrawable(new Drawable[]{tile, new InsetDrawable(glyph, 16)});
    }

    /** Previous card out (as after its hold time), the real Notify.show, the slide in, the capture. */
    private void notifyFrame(String name, String[] c, boolean withIcon) {
        showCard(c, withIcon);
        capture(name);
    }

    private void showCard(String[] c, boolean withIcon) {
        act(() -> {
            viewer.setVisibility(View.GONE);
            panel.setVisibility(View.GONE);
            OverlayHost.panelWidth = 0;
            Notify.hide();
        });
        pause(800);
        act(() -> Notify.show(this, c[4], c[5].isEmpty() ? null : c[5], withIcon ? icon() : null));
        pause(900);
    }

    /** A card is up, then the quick panel opens: OverlayHost's relayout moves the card left of the panel. */
    private void panelFrame(String name, String[] c) {
        showCard(c, false);                                        // as frame_notify_battery_*
        act(() -> {
            panel.setVisibility(View.VISIBLE);
            OverlayHost.panelWidth = PANEL_W;
            Notify.relayout(this, true);
        });
        pause(500);
        capture(name);
        act(() -> {
            panel.setVisibility(View.GONE);
            OverlayHost.panelWidth = 0;
            Notify.relayout(this, false);
        });
    }

    /**
     * The viewer: the state card says "Connecting..." first and then "<device> is asleep" on the same card (the
     * owner's photo), or the banner over a state card with the longest strings.
     */
    private void viewerFrame(String name, String lang, String what) {
        act(() -> {
            Notify.hide();
            panel.setVisibility(View.GONE);
            viewer.setVisibility(View.VISIBLE);
            banner.view.setVisibility(View.GONE);
            String[] con = row("brand", lang, "Z9xTvInput:connecting", null);
            state.set(con[4], con[5], 1, 0xFF0070D1);                // a known PlayStation: its glyph and colour
        });
        pause(600);
        act(() -> {
            if (what == null) {
                String[] a = row("brand", lang, "Z9xTvInput:asleep_photo", null);
                state.set(a[4], a[5], 1, 0xFF0070D1);
            } else {
                String[] d = row("brand", lang, "Z9xTvInput:no_signal", "long");
                String[] o = d[6].split(",");
                state.set(d[4], d[5], Integer.parseInt(o[1]), (int) Long.parseLong(o[2]));
                String[] bn = row("brand", lang, "Z9xTvInput:banner_device", "long");
                o = bn[6].split(",");
                banner.set(bn[4], bn[5], Integer.parseInt(o[1]), (int) Long.parseLong(o[2]));
            }
        });
        pause(600);
        capture(name);
        act(() -> {
            report.println("V\t" + name + "\tstate " + state.view.getWidth() + "x" + state.view.getHeight()
                    + " title lines " + BrandProbe.lines(state.title) + " ellipsis " + BrandProbe.ellipsis(state.title)
                    + " line lines " + BrandProbe.lines(state.line) + " ellipsis " + BrandProbe.ellipsis(state.line)
                    + (banner.view.getVisibility() == View.VISIBLE ? "; banner " + banner.view.getWidth() + "x"
                    + banner.view.getHeight() + " ellipsis " + (BrandProbe.ellipsis(banner.title)
                    + BrandProbe.ellipsis(banner.line)) : ""));
            viewer.setVisibility(View.GONE);
        });
    }

    private void capture(String name) {
        plan.add(done -> {
            Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            PixelCopy.request(getWindow(), bmp, res -> {
                if (res == PixelCopy.SUCCESS) {
                    save(bmp, name);
                } else {
                    errors++;
                    report.println("ERROR\t" + name + "\tPixelCopy " + res);
                }
                done.run();
            }, h);
        });
    }

    private void save(Bitmap bmp, String name) {
        try (FileOutputStream f = new FileOutputStream(new File(out, name + ".png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
        } catch (Throwable t) {
            errors++;
            report.println("ERROR\t" + name + "\t" + t);
        }
    }

    // ------------------------------------------------------------------ sheets
    /** Every card of a kind, per language, packed on 1920 x 1080 sheets at 1:1 with a label over each. */
    private void sheets(String kind) {
        plan.add(done -> {
            String[] langs = {"en", "ru", "de", "ar", "xx"};
            for (String lang : langs) {
                android.content.Context ctx = this;
                if (lang.equals("ar")) {                               // right to left, as in an Arabic locale
                    android.content.res.Configuration conf = new android.content.res.Configuration(
                            getResources().getConfiguration());
                    conf.setLocale(new java.util.Locale("ar"));
                    ctx = createConfigurationContext(conf);
                }
                Sheet s = null;
                int n = 0;
                for (String[] c : cards) {
                    if (!c[0].equals(kind) || !c[1].equals(lang)) continue;
                    View v;
                    String facts;
                    if (kind.equals("notify")) {
                        NotifyProbe p = NotifyProbe.make(ctx, c[4], c[5].isEmpty() ? null : c[5],
                                c[6].equals("icon") ? icon() : null);
                        v = p.view;
                        facts = notifyFacts(c, p);
                    } else {
                        String[] o = c[6].split(",");
                        BrandProbe p = new BrandProbe(this, o[0].equals("banner"));
                        p.set(c[4], c[5], Integer.parseInt(o[1]), (int) Long.parseLong(o[2]));
                        p.measure();
                        v = p.view;
                        facts = brandFacts(c, p, o[0]);
                    }
                    String label = shortKey(c[2]) + " " + c[3] + (c[6].equals("icon") ? " +icon" : "") + "  " + facts;
                    if (s == null || !s.fits(v.getWidth(), v.getHeight())) {
                        if (s != null) s.save();
                        s = new Sheet(kind, lang, ++n);
                    }
                    s.put(v, label);
                }
                if (s != null) s.save();
            }
            done.run();
        });
    }

    /**
     * The Play 6 battery notices with their own layout: the countdown band (a replica of BatteryNotices.Countdown
     * from the same shared parts: NOTIFY_BG, padding 120 / 40, TextRow title 40 and message 30, full width) and
     * the 5 % notice's one-line title (PagedPanel: bold 40, ellipsized) and buttons (Row: 30) against the panel.
     */
    private void battery() throws Exception {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Scene.paint(c, W, H);
        TextPaint head = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        head.setColor(0xFFFFFFFF);
        head.setTextSize(22);
        c.drawText("sheet_play6_battery  (countdown band, replica of BatteryNotices.Countdown; 5 % notice text widths)", 40, 40, head);
        int y = 70;
        TextPaint small = new TextPaint(head);
        small.setTextSize(20);
        // PagedPanel title: panel 0.34 W, outer margin 24, card padding 28, title start padding 28
        float titleRoom = PANEL_W - 2 * 24 - 2 * 28 - 28, rowRoom = PANEL_W - 2 * 24 - 2 * 28 - 2 * 28;
        TextPaint t40 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t40.setTextSize(40);
        t40.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        TextPaint t30 = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        t30.setTextSize(30);
        for (String[] r : cards) {
            if (r[0].equals("band")) {
                android.widget.LinearLayout band = new android.widget.LinearLayout(this);
                band.setOrientation(android.widget.LinearLayout.VERTICAL);
                band.setBackgroundColor(org.lumen.projector.ui.Theme.NOTIFY_BG);
                band.setPadding(120, 40, 120, 40);
                android.widget.LinearLayout.LayoutParams wrap = new android.widget.LinearLayout.LayoutParams(-1, -2);
                band.addView(new org.lumen.projector.ui.TextRow(this, r[4], org.lumen.projector.ui.Theme.PANEL_TITLE_SIZE,
                        org.lumen.projector.ui.Theme.TEXT), wrap);
                band.addView(new org.lumen.projector.ui.TextRow(this, r[5], org.lumen.projector.ui.Theme.ROW_TITLE_SIZE,
                        org.lumen.projector.ui.Theme.TEXT_DIM), wrap);
                band.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.AT_MOST));
                band.layout(0, 0, W, band.getMeasuredHeight());
                c.drawText(r[1] + " " + r[2] + "  " + W + "x" + band.getMeasuredHeight(), 40, y + 22, small);
                c.save();
                c.translate(0, y + 32);
                band.draw(c);
                c.restore();
                report.println("BAND	" + r[1] + "	" + W + "x" + band.getMeasuredHeight());
                y += 32 + band.getMeasuredHeight() + 24;
            } else if (r[0].equals("dialog")) {
                String[] b = r[6].split("\\|");
                String line = r[1] + " 5 % notice: title \"" + r[4] + "\" " + Math.round(t40.measureText(r[4])) + " of "
                        + Math.round(titleRoom) + " px; buttons " + Math.round(t30.measureText(b[0])) + ", "
                        + Math.round(t30.measureText(b[1])) + " of " + Math.round(rowRoom) + " px (message wraps)";
                report.println("DIALOG	" + line);
                c.drawText(line, 40, y + 22, small);
                y += 40;
            }
        }
        save(bmp, "sheet_play6_battery");
    }

    private static String shortKey(String k) {
        return k.replace("Z9xProjector:", "Z:").replace("Play6Projector:", "P6:").replace("Z9xTvInput:", "TV:");
    }

    private static int ell(android.text.Layout l) {
        int n = 0;
        if (l != null) for (int i = 0; i < l.getLineCount(); i++) n += l.getEllipsisCount(i);
        return n;
    }

    private String notifyFacts(String[] c, NotifyProbe p) {
        boolean split = !p.title.getText().toString().equals(c[4]);
        int tl = p.title.getLineCount(), dl = p.desc == null ? 0 : p.desc.getLineCount();
        int te = ell(p.title), de = ell(p.desc);
        report.println("N\t" + c[1] + "\t" + c[2] + "\t" + c[3] + "\t" + c[6] + "\t" + p.w + "\t" + p.h + "\t" + tl + "\t"
                + dl + "\t" + te + "\t" + de + "\t" + (split ? 1 : 0) + "\t" + Math.round(p.contentX) + "\t"
                + Math.round(p.textW) + "\t" + p.title.getText().toString().replace('\n', ' ')
                + "\t" + (p.desc == null ? "" : p.desc.getText().toString().replace('\n', ' ')));
        return p.w + "x" + p.h + " t" + tl + (dl > 0 ? " d" + dl : "") + (split ? " SPLIT" : "")
                + (te + de > 0 ? " ELLIPSIS" : "");
    }

    private String brandFacts(String[] c, BrandProbe p, String where) {
        int te = BrandProbe.ellipsis(p.title), le = BrandProbe.ellipsis(p.line);
        report.println("B\t" + c[1] + "\t" + c[2] + "\t" + c[3] + "\t" + where + "\t" + p.view.getWidth() + "\t"
                + p.view.getHeight() + "\t" + BrandProbe.lines(p.title) + "\t" + BrandProbe.lines(p.line) + "\t" + te + "\t"
                + le + "\t" + BrandProbe.slack(p.title) + "\t" + BrandProbe.slack(p.line));
        return where + " " + p.view.getWidth() + "x" + p.view.getHeight() + " t" + BrandProbe.lines(p.title)
                + " l" + BrandProbe.lines(p.line) + (te + le > 0 ? " ELLIPSIS" : "");
    }

    /** One contact sheet: shelves left to right, top to bottom. */
    private final class Sheet {
        static final int M = 40, GAP_X = 48, GAP_Y = 22, LABEL = 30, TOP = 64;
        final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(bmp);
        final String name;
        final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        int x = M, y = TOP, shelf;

        Sheet(String kind, String lang, int n) {
            name = "sheet_" + kind + "_" + lang + "_" + n;
            Scene.paint(c, W, H);
            label.setColor(0xCCFFFFFF);
            label.setTextSize(18);
            TextPaint head = new TextPaint(label);
            head.setTextSize(22);
            head.setColor(0xFFFFFFFF);
            c.drawText(name + "  (1920 x 1080, 1 design px = 1 px; label: size, title / desc lines)", M, 40, head);
        }

        boolean fits(int w, int hgt) {
            if (x + w <= W - M) return y + LABEL + hgt <= H - 16 || shelf == 0 && y == TOP;
            return y + shelf + GAP_Y + LABEL + hgt <= H - 16;
        }

        void put(View v, String text) {
            int w = v.getWidth(), hgt = v.getHeight();
            if (x + w > W - M && x > M) {
                x = M;
                y += shelf + GAP_Y;
                shelf = 0;
            }
            c.drawText(text, x, y + 20, label);
            c.save();
            c.translate(x, y + LABEL);
            v.draw(c);
            c.restore();
            shelf = Math.max(shelf, LABEL + hgt);
            x += Math.max(w, (int) label.measureText(text)) + GAP_X;
        }

        void save() {
            ShotsActivity.this.save(bmp, name);
        }
    }

    // ------------------------------------------------------------------ run
    private void go() {
        if (next >= plan.size()) {
            report.println("DONE\terrors " + errors);
            report.close();
            try (FileOutputStream f = new FileOutputStream(new File(out, "done.txt"))) {
                f.write(("errors " + errors + "\n").getBytes());
            } catch (Throwable t) {
                Log.e(TAG, "done", t);
            }
            Log.i(TAG, "done, errors " + errors);
            return;
        }
        Step s = plan.get(next++);
        try {
            s.run(() -> h.post(this::go));
        } catch (Throwable t) {
            errors++;
            Log.e(TAG, "step " + (next - 1), t);
            report.println("ERROR\tstep " + (next - 1) + "\t" + t);
            h.post(this::go);
        }
    }

    /** The picture behind the cards: a muted frame of a film with a row of tiles, so contrast reads as on screen. */
    static final class Scene extends View {
        Scene(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            paint(c, getWidth(), getHeight());
        }

        static void paint(Canvas c, int w, int h) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, w, h, 0xFF56606E, 0xFF15191F, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setColor(0x26FFFFFF);
            for (int i = 0; i < 6; i++) {
                c.drawRoundRect(new RectF(80 + i * 300, 700, 340 + i * 300, 860), 18, 18, p);
            }
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(80, 560, 900, 600), 12, 12, p);
        }
    }
}
