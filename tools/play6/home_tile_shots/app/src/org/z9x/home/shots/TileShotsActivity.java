package org.z9x.home.shots;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.PixelCopy;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.z9x.home.R;
import org.z9x.home.data.Card;
import org.z9x.home.data.CecNames;
import org.z9x.home.data.InputSource;
import org.z9x.home.ui.CardView;
import org.z9x.home.ui.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Render harness of Lumen Home's Inputs tiles (tools/play6/home_tile_shots, docs/play6/impl/t14-home.md t17).
 * Runs on the 1920 x 1080 test emulator (density 320: 1 design px = 1 px, as Home at 1080p), never on a
 * projector. One frame per language and font scale: three rows of real CardViews (Home's own CardView, Theme,
 * strings and fonts) at the Inputs row's size and spacing, the second row's first card focused, captured with
 * PixelCopy. The grey captions are the harness's, not Home's. report.tsv: per card the title, status and hint
 * lines as laid out and whether any of them was cut. Extra "before": en / ru / de only, named before_*.
 * Output: getExternalFilesDir/shots/*.png, report.tsv, done.txt.
 */
public class TileShotsActivity extends Activity {
    private static final String TAG = "Z9xTileShots";
    private static final int W = 1920, H = 1080;
    private static final int CW = 384, CH = 216, GAP = 24, MARGIN = 96;
    private static final int[] ROW_Y = {120, 470, 820};

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> plan = new ArrayList<>();
    private int next;
    private FrameLayout root;
    private File out;
    private PrintWriter report;
    private boolean before;
    private int errors, cut;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        before = getIntent().getBooleanExtra("before", false);
        Theme.init(this);
        root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundColor(Theme.BG);
        setContentView(root);
        getWindow().setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController ic = getWindow().getInsetsController();
        if (ic != null) ic.hide(android.view.WindowInsets.Type.systemBars());
        try {
            out = new File(getExternalFilesDir(null), "shots");
            File[] old = out.listFiles();
            if (old != null) for (File f : old) f.delete();
            out.mkdirs();
            report = new PrintWriter(new FileOutputStream(new File(out, "report.tsv")));
            report.println("frame\tlang\tfont_scale\tcase\ttitle\tstatus\thint\tlines\tcut");
        } catch (Throwable t) {
            Log.e(TAG, "setup", t);
            return;
        }
        for (String lang : new String[]{"en", "ru", "de"}) {
            frame(lang, 1f, (before ? "before_" : "") + "frame_home_tile_" + lang);
            frame(lang, 1.3f, (before ? "before_" : "") + "frame_home_tile_" + lang + "_fs130");
        }
        if (!before) frame("ar", 1f, "frame_home_tile_ar");   // right to left
        root.post(this::go);
    }

    // ------------------------------------------------------------------ one frame
    private void frame(String lang, float fs, String name) {
        plan.add(() -> {
            Configuration conf = new Configuration(getResources().getConfiguration());
            conf.setLocale(new Locale(lang));
            conf.fontScale = fs;
            Context c = createConfigurationContext(conf);
            root.removeAllViews();
            root.setLayoutDirection(conf.getLayoutDirection());
            caption(name + "   (" + lang + ", font scale " + fs + ")", 24, 36);
            caption("device states from the TV input app", MARGIN, ROW_Y[0] - 44);
            card(c, name, 0, 0, "on", dev(c, "PlayStation", "HDMI 1 (ARC)", Card.DEV_ON, R.drawable.ic_gamepad, 0xFF0070D1), false);
            card(c, name, 0, 1, "asleep", dev(c, "PlayStation", "HDMI 1 (ARC)", Card.DEV_ASLEEP, R.drawable.ic_gamepad, 0xFF0070D1), false);
            card(c, name, 0, 2, "no_signal", dev(c, "Nintendo Switch", "HDMI 2", Card.DEV_NO_SIGNAL, R.drawable.ic_gamepad, 0xFFE60012), false);
            card(c, name, 0, 3, "not_connected", dev(c, "HDMI 1 (ARC)", "", Card.DEV_DISCONNECTED, R.drawable.ic_hdmi, 0), false);
            caption("focused asleep card (hint), longest status lines", MARGIN, ROW_Y[1] - 44);
            card(c, name, 1, 0, "asleep_focused", dev(c, "PlayStation", "HDMI 1 (ARC)", Card.DEV_ASLEEP, R.drawable.ic_gamepad, 0xFF0070D1), true);
            card(c, name, 1, 1, "not_connected_named", dev(c, "Xbox", "HDMI 1 (ARC)", Card.DEV_DISCONNECTED, R.drawable.ic_gamepad, 0xFF107C10), false);
            card(c, name, 1, 2, "no_signal_named", dev(c, "Xbox Series X", "HDMI 1 (ARC)", Card.DEV_NO_SIGNAL, R.drawable.ic_gamepad, 0xFF107C10), false);
            card(c, name, 1, 3, "on_player", dev(c, "Blu-ray", "HDMI 2", Card.DEV_ON, R.drawable.ic_player, 0), false);
            caption("TV input states only (the Z9X today)", MARGIN, ROW_Y[2] - 44);
            card(c, name, 2, 0, "tif_connected", tif(c, "PlayStation 5", "HDMI 1", TvInputManager.INPUT_STATE_CONNECTED), false);
            card(c, name, 2, 1, "tif_standby_long", tif(c, "PlayStation 5", "HDMI 1 (ARC)", TvInputManager.INPUT_STATE_CONNECTED_STANDBY), false);
            card(c, name, 2, 2, "tif_standby", tif(c, "HDMI 2", "", TvInputManager.INPUT_STATE_CONNECTED_STANDBY), false);
            card(c, name, 2, 3, "tif_not_connected", tif(c, "HDMI 2", "", TvInputManager.INPUT_STATE_DISCONNECTED), false);
            h.postDelayed(() -> capture(name), 700);
        });
    }

    /** A card as InputSource.applyDevice makes it (title, port, state text, glyph, softened accent). */
    private static Card dev(Context c, String title, String port, int state, int icon, int accent) {
        Card k = new Card(Card.INPUT, "shot:" + title + port + state, title);
        k.desc = port;
        k.state = state;
        k.meta = InputSource.devText(c, state);
        k.icon = icon;
        k.color = accent == 0 ? 0 : CecNames.softAccent(accent);
        return k;
    }

    /** A card from the TIF state only, as on the Z9X (no device state, no accent). */
    private static Card tif(Context c, String title, String port, int state) {
        Card k = new Card(Card.INPUT, "shot:" + title + port + state, title);
        k.desc = port;
        k.state = state;
        k.meta = InputSource.stateText(c, state);
        k.icon = R.drawable.ic_hdmi;
        return k;
    }

    private void card(Context c, String frame, int row, int col, String name, Card k, boolean focused) {
        CardView v = new CardView(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Theme.px(CW), Theme.px(CH), Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = Theme.px(MARGIN + col * (CW + GAP));
        lp.topMargin = Theme.px(ROW_Y[row]);
        root.addView(v, lp);
        v.bind(k, Theme.px(CW), Theme.px(CH));
        if (focused) v.setFocusState(true, false, false);
        String title = str(v, "mLine1");
        String status = before ? str(v, "mLine2") : str(v, "mStatus");
        String hint = before ? str(v, "mWakeLine") : str(v, "mHint");
        int ns = status.isEmpty() ? 0 : status.split(" \\| ").length;
        int nh = hint.isEmpty() ? 0 : hint.split(" \\| ").length;
        // drawn lines: the title plus the status, or on a focused asleep card the hint under a one-line status
        // (new layout) or in its place
        boolean showHint = focused && nh > 0, hintBelow = showHint && !before && ns == 1 && nh == 1;
        int lines = 1 + (showHint ? (hintBelow ? 2 : nh) : ns);
        String drawn = (showHint && !hintBelow ? "" : status) + (showHint ? hint : "");   // what is on screen
        boolean isCut = drawn.indexOf('…') >= 0;
        if (isCut) cut++;
        report.println(frame + "\t" + c.getResources().getConfiguration().getLocales().get(0).getLanguage() + "\t"
                + c.getResources().getConfiguration().fontScale + "\t" + name + "\t" + title + "\t" + status + "\t"
                + hint + "\t" + lines + "\t" + (isCut ? "CUT" : "") + (title.indexOf('…') >= 0 ? " title_cut" : ""));
    }

    private void caption(String s, int x, int y) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(0xFF8A8F98);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        lp.leftMargin = x;
        lp.topMargin = y;
        root.addView(t, lp);
    }

    /** A CardView text field as laid out (CharSequence or CharSequence[] joined by " | "), "" if absent. */
    private static String str(CardView v, String field) {
        try {
            Field f = CardView.class.getDeclaredField(field);
            f.setAccessible(true);
            Object o = f.get(v);
            if (o == null) return "";
            if (o instanceof CharSequence[]) {
                StringBuilder b = new StringBuilder();
                for (CharSequence s : (CharSequence[]) o) b.append(b.length() > 0 ? " | " : "").append(s);
                return b.toString();
            }
            return o.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ capture and run
    private void capture(String name) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        PixelCopy.request(getWindow(), bmp, res -> {
            if (res == PixelCopy.SUCCESS) {
                try (FileOutputStream f = new FileOutputStream(new File(out, name + ".png"))) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, f);
                } catch (Throwable t) {
                    errors++;
                    report.println("ERROR\t" + name + "\t" + t);
                }
            } else {
                errors++;
                report.println("ERROR\t" + name + "\tPixelCopy " + res);
            }
            h.post(this::go);
        }, h);
    }

    private void go() {
        if (next >= plan.size()) {
            report.println("DONE\terrors " + errors + "\tcut " + cut);
            report.close();
            try (FileOutputStream f = new FileOutputStream(new File(out, "done.txt"))) {
                f.write(("errors " + errors + ", cut " + cut + "\n").getBytes());
            } catch (Throwable t) {
                Log.e(TAG, "done", t);
            }
            return;
        }
        try {
            plan.get(next++).run();
        } catch (Throwable t) {
            errors++;
            Log.e(TAG, "step " + (next - 1), t);
            report.println("ERROR\tstep " + (next - 1) + "\t" + t);
            h.post(this::go);
        }
    }
}
