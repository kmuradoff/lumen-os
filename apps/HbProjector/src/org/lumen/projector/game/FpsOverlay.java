package org.lumen.projector.game;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import org.lumen.projector.Hal;
import org.lumen.projector.Ui;
import org.lumen.projector.hal.GmpfClient;
import org.lumen.projector.hal.HbFeatures;
import org.lumen.projector.hal.HbFeatures.F;
import org.lumen.projector.source.TifHdmiState;
import org.lumen.projector.ui.OverlayHost;
import org.lumen.projector.ui.Theme;

/**
 * FPS overlay of the HB game bar (stock analysis, stock xhplayer FpsCell): a small frame-rate card at the top
 * left, centre or right while an HDMI input is on screen. Every 2 s one read: DISPLAY 179 getDispOutputFps while VRR
 * is on, else the TV 38 frame rate (stock reads TV 122 getVideoInfo for the same input frame rate; 38 is the
 * decoded struct Lumen already uses). Hidden while the game bar is open; it stops (no more reads) when no HDMI input
 * is on screen and starts again when the bar closes. Main thread except the reads.
 */
public final class FpsOverlay {
    private static final String TAG = "Z9xGameFps";
    static final String KEY_POS = "fps_pos";
    public static final int OFF = 0, LEFT = 1, CENTER = 2, RIGHT = 3;
    private static final long POLL_MS = 2_000;

    private static TextView sView;
    private static boolean sBarOpen;
    private static int sSerial;

    private FpsOverlay() {}

    /** The stored position (OFF .. RIGHT). */
    public static int position(Context ctx) {
        int p = ctx.getSharedPreferences(GameBar.PREFS, Context.MODE_PRIVATE).getInt(KEY_POS, OFF);
        return p < OFF || p > RIGHT ? OFF : p;
    }

    /** Game bar choice. Main thread. */
    static void setPosition(Context ctx, int pos) {
        ctx.getSharedPreferences(GameBar.PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_POS, pos).apply();
        if (!sBarOpen) update(ctx.getApplicationContext());
    }

    /** The bar opened (true) or closed (false). Main thread. */
    static void hideForBar(Context ctx, boolean open) {
        sBarOpen = open;
        update(ctx.getApplicationContext());
    }

    private static void update(Context app) {
        int pos = HbFeatures.on(F.GAME_FPS) ? position(app) : OFF;
        if (sBarOpen || pos == OFF) {
            remove(app);
            return;
        }
        if (sView == null) {
            TextView t = new TextView(app);
            t.setTextColor(Theme.TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(app, 30));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x99000000);
            bg.setCornerRadius(Theme.pxf(app, 16));
            t.setBackground(bg);
            int p = Theme.px(app, 16);
            t.setPadding(p, p / 2, p, p / 2);
            t.setText("FPS --");
            if (!OverlayHost.get(app).addStatic(t, params(app, pos))) return;
            sView = t;
            int serial = ++sSerial;
            poll(app, serial);
        } else {
            OverlayHost.get(app).updateStatic(sView, params(app, pos));
        }
    }

    private static WindowManager.LayoutParams params(Context app, int pos) {
        int g = pos == LEFT ? Gravity.START : pos == RIGHT ? Gravity.END : Gravity.CENTER_HORIZONTAL;
        WindowManager.LayoutParams lp = OverlayHost.params(false, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | g);
        lp.x = Theme.px(app, 32);
        lp.y = Theme.px(app, 24);
        return lp;
    }

    private static void remove(Context app) {
        sSerial++;
        if (sView != null) {
            OverlayHost.get(app).removeStatic(sView);
            sView = null;
        }
    }

    /** One read now, then every POLL_MS while this overlay (serial) is the one shown. */
    private static void poll(Context app, int serial) {
        Hal.query((g, g2) -> {
            Integer src = GameBar.opt("696", g::getCurrentInputSource);
            int s = src != null && (src == GmpfClient.SOURCE_HDMI1 || src == GmpfClient.SOURCE_HDMI2) ? src : 0;
            if (s == 0) {
                int[] ports = TifHdmiState.readPorts(app);
                if (ports != null && ports.length > 0 && (ports[0] == 1 || ports[0] == 2)) s = ports[0];
            }
            if (s == 0) return -1;
            final int s0 = s;
            Boolean vrr = HbFeatures.on(F.GAME_VRR) ? GameBar.opt("146", () -> g2.getVrr(s0)) : Boolean.FALSE;
            if (Boolean.TRUE.equals(vrr)) {
                Integer f = GameBar.opt("179", g2::getOutputFps);
                return f == null ? 0 : f;
            }
            GmpfClient.HdmiSignal sig = GameBar.opt("38", g::getHdmiInfo);
            return sig != null && sig.plausible() ? Math.round(sig.frameRateCenti / 100f) : 0;
        }, fps -> {
            if (serial != sSerial || sView == null) return;
            if (fps != null && fps < 0) {
                Log.i(TAG, "no HDMI input on screen: overlay stopped");
                remove(app);
                return;
            }
            sView.setText(fps == null || fps <= 0 ? "FPS --" : "FPS " + fps);
            Ui.main().postDelayed(() -> {
                if (serial == sSerial && sView != null) poll(app, serial);
            }, POLL_MS);
        });
    }
}
