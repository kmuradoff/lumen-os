package org.lumen.projector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import org.lumen.projector.ui.OverlayHost;

/**
 * Z9X HB hb-t7 (2026-10-30): the stock "fit to screen" hint window.
 *
 * With "Fit to screen" on, the first "Fit to screen now" (newAutoKst(11)) makes the HB vendor reset the keystone to
 * the full frame, send focusEvent 105 "AK_Curtain_Fit_Guide" and wait. Stock HB SystemUI (release_838, jadx):
 * FocusUIV2.onProjectorFocusEventCall (FocusUIV2.java:584-588) shows AuxiliaryCurtainAlignDialog, a full-screen white
 * window: title auxiliary_curtain_alignment ("Learn Screen Alignment"), text auxiliary_curtain_alignment_desc ("Move
 * the projector so the projection image exceeds the four edges of the screen") and at the bottom "After adjustment,
 * press [OK] Continue" (btn1, OK icon, btn2). AuxiliaryCurtainAlignDialog.java:
 *   show()    :215-217  super.show(), then GmDisplayManager.temporarySwitchTrigger(2, false)
 *   onKeyUp   :221-233  a key up within 200 ms of the previous one is ignored; KEYCODE_DPAD_CENTER (23): dismiss(),
 *                       then GmDisplayManager.newAutoKst(12); every other key: Dialog.onKeyUp (BACK cancels)
 *   dismiss() :266-268  super.dismiss(), then GmDisplayManager.temporarySwitchTrigger(0, false)
 * No timeout. Here: the same window drawn by us (white, the stock texts in 1920 x 1080 design units, no XGIMI
 * artwork), the same calls in the same order on HalController's threads: (2, false) on show, on OK dismiss with
 * (0, false) and then newAutoKst(12) through HalController.requestCurtainAlign (keystone thread, motor interlock),
 * BACK dismisses with (0, false) only. Not shown during standby. Main thread only.
 */
final class CurtainAlignOverlay {
    private static final String TAG = "Z9xFitGuide";
    /** The focusEvent 105 value that opens the window (stock AK2Window.MSG_AK_UI_CURTAIN_FIT_GUIDE). */
    static final String VALUE = "AK_Curtain_Fit_Guide";
    /** Stock lastKeyOnTime debounce. */
    private static final long KEY_GAP_MS = 200;

    private static Card sCard;
    private static boolean sShown;
    private static long sLastKeyAt;

    private CurtainAlignOverlay() {}

    static boolean isShown() { return sShown; }

    /** MAIN thread, from HalController.dispatchEvent: focusEvent 105 with {@link #VALUE}. */
    static void show() {
        if (sShown) {
            Log.i(TAG, "105 " + VALUE + ": window already shown");
            return;
        }
        if (org.lumen.projector.power.StandbyController.isActive()) {
            Log.w(TAG, "105 " + VALUE + " during standby: not shown");
            return;
        }
        HalController hc = HalController.peek();
        if (hc == null) return;
        Context app = hc.appContext();
        if (sCard == null) sCard = new Card(app);
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        lp.setTitle("Z9xFitGuide");
        OverlayHost host = OverlayHost.get(app);
        host.dismissAll(false);                               // stock BaseExclusionDialog: the other dialogs go
        if (!host.addStatic(sCard, lp)) {
            Log.e(TAG, "105 " + VALUE + ": window could not be added");
            return;
        }
        sShown = true;
        sCard.requestFocus();
        Log.i(TAG, "105 " + VALUE + ": fit-to-screen hint window shown");
        hc.halHandler().post(() -> {
            try {
                Log.i(TAG, "temporarySwitchTrigger(2, false) -> " + hc.client().setRealtimeAkTemporarily(false));
            } catch (Throwable t) {
                Log.w(TAG, "temporarySwitchTrigger(2, false): " + t);
            }
        });
    }

    /** Main thread: removes the window and sends (0, false); {@code cont}: then newAutoKst(12). */
    private static void dismiss(String why, boolean cont) {
        if (!sShown) return;
        sShown = false;
        HalController hc = HalController.peek();
        Context app = hc == null ? null : hc.appContext();
        if (app != null) OverlayHost.get(app).removeStatic(sCard);
        Log.i(TAG, "hint window closed (" + why + ")" + (cont ? ": continue with newAutoKst(12)" : ""));
        if (hc == null) return;
        hc.halHandler().post(() -> {
            try {
                Log.i(TAG, "temporarySwitchTrigger(0, false) -> " + hc.client().resetRealtimeAkTemporary());
            } catch (Throwable t) {
                Log.w(TAG, "temporarySwitchTrigger(0, false): " + t);
            }
            if (cont) hc.requestCurtainAlign();               // after (0, false), as stock (dismiss() first)
        });
    }

    /** The window: white, the stock texts, takes the keys while it is up. */
    private static final class Card extends View {
        private static final float W = 1920f, H = 1080f;
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint desc = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final String sTitle, sDesc, sBtn1, sBtn2;

        Card(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            sTitle = c.getString(R.string.hb_fit_guide_title);
            sDesc = c.getString(R.string.hb_fit_guide_desc);
            sBtn1 = c.getString(R.string.hb_fit_guide_btn1);
            sBtn2 = c.getString(R.string.hb_fit_guide_btn2);
            title.setColor(0xE6000000);                       // stock black_90, 40
            title.setTextSize(40f);
            title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            title.setTextAlign(Paint.Align.CENTER);
            desc.setColor(0x80000000);                        // stock black_50, 30
            desc.setTextSize(30f);
            hint.setColor(0x66000000);                        // stock black_40, 26
            hint.setTextSize(26f);
            line.setStyle(Paint.Style.STROKE);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.dispatchKeyEvent(e);
            }
            if (e.getAction() != KeyEvent.ACTION_UP) return true;         // stock acts on key up only
            long now = SystemClock.elapsedRealtime();
            boolean tooSoon = now - sLastKeyAt <= KEY_GAP_MS;
            sLastKeyAt = now;
            if (tooSoon) return true;
            if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
                dismiss("OK", true);
            } else if (code == KeyEvent.KEYCODE_BACK) {
                dismiss("BACK", false);
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            c.drawColor(Color.WHITE);
            c.save();
            c.scale(vw / W, vh / H);
            // a screen outline in the icon area (stock keystone_icon_curtain_align, 128 px high at y 360)
            line.setColor(0x66000000);
            line.setStrokeWidth(4f);
            r.set(W / 2f - 100, 360, W / 2f + 100, 360 + 112);
            c.drawRoundRect(r, 8, 8, line);
            line.setStrokeWidth(2f);
            r.set(W / 2f - 120, 352, W / 2f + 120, 360 + 120);
            c.drawRect(r, line);
            c.drawText(sTitle, W / 2f, 552 + 44, title);
            StaticLayout d = StaticLayout.Builder.obtain(sDesc, 0, sDesc.length(), desc, 1300)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            c.save();
            c.translate(W / 2f - 650, 620);
            d.draw(c);
            c.restore();
            // bottom line at y 986: btn1, the OK key (36 x 36), btn2
            float w1 = hint.measureText(sBtn1), w2 = hint.measureText(sBtn2);
            float total = w1 + 8 + 36 + 8 + w2, x = W / 2f - total / 2f, base = 986 + 27;
            c.drawText(sBtn1, x, base, hint);
            x += w1 + 8;
            line.setColor(0x66000000);
            line.setStrokeWidth(2f);
            c.drawCircle(x + 18, 986 + 18, 17, line);
            TextPaint ok = new TextPaint(hint);
            ok.setTextSize(14f);
            ok.setTextAlign(Paint.Align.CENTER);
            c.drawText("OK", x + 18, 986 + 23, ok);
            x += 36 + 8;
            c.drawText(sBtn2, x, base, hint);
            c.restore();
        }
    }
}
