package org.lumen.projector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.lumen.projector.ak.AkOverlay;
import org.lumen.projector.ui.Notify;
import org.lumen.projector.ui.OverlayHost;

/**
 * Lumen OS 1.0.8 (parity report docs/z9x/parity/focus.md items 1 and 13): the full-screen autofocus card.
 *
 * The vendor's camera AF (algorithm 13 for the remote key, xlab/Autofocus.ini) needs a test card on screen:
 * it sends focusEvent 335 on the Z9X (333 / 334 on other models, 4 / 8 / 10 for the older flows) and then
 * waits about 3 s for sendFocusStatus(4) ("fast_global_focus_wait_UI: wait ui open time out", then
 * EVENT_FLOW_ERR without a motor move). Lumen 1.0 to 1.0.7 only showed a toast, so every AF that needed the
 * card failed (2 of 4 remote AF runs in logs/z9x_zoom_103, both right after the optical zoom).
 *
 * Stock (systemui FocusUIV2 + AutoMaticFA, VERIFIED in jadx): events 4 / 8 / 333 / 334 / 335 open the
 * AutoMaticFA window (TYPE_SYSTEM_ERROR, full screen, eats keys) and close the manual focus window;
 * once the card view is laid out it sends 307(4); the card goes on event 5, on a 20 s timeout or on a key,
 * and a shown card that goes sends 307(5) then 307(3). BACK, LEFT and RIGHT first send 307(3) (stop the AF);
 * LEFT / RIGHT then open the manual focus window.
 *
 * Here: the same protocol with our own card (no XGIMI logo or artwork): the stock auto_focus_dapeng
 * geometry in 1920 x 1080 design units, scaled to the UI frame (4K or 1080p): dark grey with a fine grid,
 * a quarter Siemens star in a black square at each corner and rings in the centre (the vendor measures the
 * sharpness in the corners and the centre, AFG_CLARITY_CORNER_WIN). 307(4) goes out after the first frame
 * was handed to the display (frame-commit callback, 400 ms fallback). Closed on events 5 / 7 / 2, after
 * 20 s, by BACK (cancel) or LEFT / RIGHT (cancel, then manual focus); every other key except the volume
 * keys cancels too (stock oldVontrolAf). Never shown over the auto keystone overlay (the vendor AK draws its
 * own pattern and runs its own AF). Notification cards are held back while it is up (camera).
 * 2026-10-31 (HB video): a quick panel the card had to close comes back (same page, same row) once the card
 * closed; a caller may also queue one action for then (runAfterCard: the optical zoom's BACK exit). Nothing comes
 * back after LEFT / RIGHT (the manual focus window opens), and no other overlay is ever restored.
 * 2026-10-31 (HB af2 test): a queued action also runs when the AF ends (events 5 / 7 / 2 / 18) without any card,
 * whichever comes first (AfAfter); the start-wait fallback only when neither came. It never runs twice.
 * Main thread only; the HAL calls run on HalController's "z9x-hal" thread.
 */
final class AfCardOverlay {
    private static final String TAG = "Z9xAfCard";
    /** Stock AutoMaticFA hideFocusAdjustDelay(3). */
    private static final long TIMEOUT_MS = 20_000;
    /** 307(4) also goes out after this long when no frame-commit callback came. */
    private static final long OPEN_FALLBACK_MS = 400;

    private static Card sCard;
    private static boolean sShown;
    private static boolean sOpenSent;
    private static int sToken;
    private static long sShownAt;
    private static final Runnable TIMEOUT = () -> close("timeout", false, false);
    /** Runs once the card closed or the AF ended: the quick panel the card dismissed, or the action of runAfterCard. */
    private static final AfAfter AFTER = new AfAfter();
    private static final Runnable AFTER_NO_CARD = () -> {
        Runnable r = AFTER.noCardTimeout();
        if (r == null) return;
        Log.i(TAG, "no AF card came up: running the queued action now");
        runAfter(r);
    };

    private AfCardOverlay() {}

    /** The card is on screen. Main thread. */
    static boolean isShown() { return sShown; }

    /** Events that open the card (stock FocusUIV2.onProjectorFocusEventAfCall). */
    static boolean isOpenEvent(int type) {
        return type == 4 || type == 8 || type == 10 || type == 333 || type == 334 || type == 335;
    }

    /**
     * Main thread: {@code r} runs once the next (or the current) card closed, or once the AF ended without a card
     * (events 5 / 7 / 2 / 18), or after {@code startWaitMs} when neither came, whichever is first, and only once.
     * Replaces an action queued before. Not run when the card closes with LEFT / RIGHT (manual focus).
     */
    static void runAfterCard(Runnable r, long startWaitMs) {
        Ui.main().removeCallbacks(AFTER_NO_CARD);
        if (AFTER.queue(r)) Ui.main().postDelayed(AFTER_NO_CARD, startWaitMs);
    }

    /** Main thread: runs a queued action unless an overlay or the AK took the screen meanwhile. */
    private static void runAfter(Runnable r) {
        HalController hc = HalController.peek();
        if (hc == null) return;
        if (AkOverlay.isActive() || OverlayHost.get(hc.appContext()).current() != null) {
            Log.i(TAG, "queued action dropped: the screen is taken");
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            Log.w(TAG, "queued action: " + t);
        }
    }

    /** MAIN thread, from HalController.dispatchEvent, before any other handling of the event. */
    static void onFocusEvent(int type) {
        if (isOpenEvent(type)) {
            ManualFocusActivity.closeForAutofocus();        // stock: AF start closes the manual focus window
            show(type);
        } else if (sShown && (type == 5 || type == 7 || type == 2)) {
            close("event " + type, false, false);
        } else if (!sShown) {
            // the AF ended without a card (HB af2: optical step 0 -> 5 and 18 in 1.4 s, no 335): no fallback wait
            Runnable r = AFTER.focusEvent(type);
            if (r != null) {
                Ui.main().removeCallbacks(AFTER_NO_CARD);
                Log.i(TAG, "AF ended (event " + type + ") without a card: running the queued action now");
                runAfter(r);
            }
        }
    }

    private static void show(int type) {
        if (sShown) return;
        if (AkOverlay.isActive()) {
            Log.i(TAG, "event " + type + ": auto keystone overlay active, no AF card");
            return;
        }
        HalController hc = HalController.peek();
        if (hc == null) return;
        Context app = hc.appContext();
        if (sCard == null) sCard = new Card(app);
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        lp.setTitle("Z9xAfCard");
        OverlayHost host = OverlayHost.get(app);
        // a quick panel shown now comes back after the card (a queued runAfterCard action wins)
        Runnable restore = AFTER.hasPending() ? null : org.lumen.projector.panel.QuickPanel.restorer();
        host.dismissAll(false);                               // no panel over the card (camera)
        if (!host.addStatic(sCard, lp)) {
            Log.e(TAG, "event " + type + ": card window could not be added, vendor will time out");
            if (restore != null) Ui.main().post(() -> runAfter(restore));
            return;
        }
        Ui.main().removeCallbacks(AFTER_NO_CARD);
        AFTER.cardShown(restore);
        if (restore != null) Log.i(TAG, "quick panel closed for the card: it comes back after it");
        sShown = true;
        sOpenSent = false;
        sShownAt = SystemClock.elapsedRealtime();
        final int token = ++sToken;
        Notify.setHeld(true);
        sCard.requestFocus();
        Log.i(TAG, "event " + type + ": AF card shown");
        Runnable open = () -> sendOpen(token);
        try {
            sCard.getViewTreeObserver().registerFrameCommitCallback(() -> Ui.main().post(open));
        } catch (Throwable t) {
            Log.w(TAG, "frame-commit callback: " + t);
            sCard.post(open);
        }
        Ui.main().postDelayed(open, OPEN_FALLBACK_MS);
        Ui.main().removeCallbacks(TIMEOUT);
        Ui.main().postDelayed(TIMEOUT, TIMEOUT_MS);
    }

    /** Main thread: 307(4) once per shown card. */
    private static void sendOpen(int token) {
        if (!sShown || token != sToken || sOpenSent) return;
        sOpenSent = true;
        final long after = SystemClock.elapsedRealtime() - sShownAt;
        HalController hc = HalController.peek();
        if (hc == null) return;
        hc.halHandler().post(() -> {
            try {
                hc.client().sendAfUiOpen();
                Log.i(TAG, "sendFocusStatus(4) " + after + " ms after the card was added");
            } catch (Throwable t) {
                Log.w(TAG, "sendFocusStatus(4): " + t);
            }
        });
    }

    /**
     * Main thread: removes the card. {@code stop}: the user ended the AF (307(3) first, as stock).
     * {@code manual}: then open the manual focus window (LEFT / RIGHT on the card).
     */
    private static void close(String why, boolean stop, boolean manual) {
        Ui.main().removeCallbacks(TIMEOUT);
        sToken++;
        if (!sShown) return;
        sShown = false;
        final Runnable after = AFTER.cardClosed(manual);
        Log.i(TAG, "AF card closed (" + why + ") after " + (SystemClock.elapsedRealtime() - sShownAt) + " ms");
        HalController hc = HalController.peek();
        Context app = hc == null ? null : hc.appContext();
        if (app != null) OverlayHost.get(app).removeStatic(sCard);
        Notify.setHeld(false);
        if (hc == null) return;
        final boolean opened = sOpenSent;
        if (stop) hc.noteAfEnded();
        hc.halHandler().post(() -> {
            try {
                if (stop) hc.client().sendAfStop();
                if (opened) hc.client().sendAfCardClosed();
                Log.i(TAG, (stop ? "sendFocusStatus(3), " : "") + (opened ? "sendFocusStatus(5), (3)" : "no 5 / 3 (4 not sent)"));
            } catch (Throwable t) {
                Log.w(TAG, "sendFocusStatus on close: " + t);
            }
        });
        if (manual && app != null) {
            final Context c = app;
            Ui.main().postDelayed(() -> ManualFocusActivity.open(c), 150);
        } else if (after != null) {
            Ui.main().post(() -> runAfter(after));
        }
    }

    /** The card: draws the target and takes the keys while it is up. */
    private static final class Card extends FrameLayout {
        private static final float W = 1920f, H = 1080f;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path wedge = new Path();
        private final RectF oval = new RectF();

        Card(Context c) {
            super(c);
            setWillNotDraw(false);
            setFocusable(true);
            setFocusableInTouchMode(true);
            line.setStyle(Paint.Style.STROKE);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            int code = e.getKeyCode();
            switch (code) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    return super.dispatchKeyEvent(e);
                default:
                    break;
            }
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;
            boolean lr = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
            close("key " + KeyEvent.keyCodeToString(code), true, lr);
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            c.save();
            c.scale(vw / W, vh / H);
            c.drawColor(0xFF4A4A4A);
            // fine grid (stock card: a faint 24 px grid over the grey)
            line.setColor(0xFF555555);
            line.setStrokeWidth(1f);
            for (float x = 0; x <= W; x += 24) c.drawLine(x, 0, x, H, line);
            for (float y = 0; y <= H; y += 24) c.drawLine(0, y, W, y, line);
            // corner stars: a 230 px black square 120 / 130 px from the edges, its star centred on the outer corner
            corner(c, 120, 130, 0);
            corner(c, W - 120, 130, 90);
            corner(c, W - 120, H - 130, 180);
            corner(c, 120, H - 130, 270);
            centre(c, W / 2f, H / 2f);
            c.restore();
        }

        /** A quarter Siemens star; (x, y) is the outer corner, rot the quarter (0 = top left). */
        private void corner(Canvas c, float x, float y, float rot) {
            final float size = 230, hub = 52, ringIn = 88, ringOut = 112;
            c.save();
            c.translate(x, y);
            c.rotate(rot);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.BLACK);
            c.drawRect(0, 0, size, size, fill);
            fill.setColor(Color.WHITE);
            final int spokes = 24;                                // white / black pairs over 90 degrees
            float step = 90f / spokes;
            oval.set(-size * 1.42f, -size * 1.42f, size * 1.42f, size * 1.42f);
            c.save();
            c.clipRect(0, 0, size, size);
            for (int i = 0; i < spokes; i++) {
                wedge.reset();
                wedge.moveTo(0, 0);
                wedge.arcTo(oval, i * step, step / 2f, false);
                wedge.close();
                c.drawPath(wedge, fill);
            }
            // dash ring
            fill.setColor(Color.BLACK);
            oval.set(-ringOut, -ringOut, ringOut, ringOut);
            c.drawArc(oval, 0, 90, true, fill);
            fill.setColor(Color.WHITE);
            for (int i = 0; i < 8; i++) {
                wedge.reset();
                oval.set(-ringOut, -ringOut, ringOut, ringOut);
                wedge.arcTo(oval, i * 11.25f + 2f, 7.25f, true);
                oval.set(-ringIn, -ringIn, ringIn, ringIn);
                wedge.arcTo(oval, i * 11.25f + 9.25f, -7.25f, false);
                wedge.close();
                c.drawPath(wedge, fill);
            }
            fill.setColor(0xFFDDDDDD);
            oval.set(-hub, -hub, hub, hub);
            c.drawArc(oval, 0, 90, true, fill);
            c.restore();
            c.restore();
        }

        /** Centre rings: outer arcs, a dash ring, a fine tick ring and a dark disc with a cross. */
        private void centre(Canvas c, float cx, float cy) {
            fill.setStyle(Paint.Style.FILL);
            line.setColor(Color.WHITE);
            // outer arcs (4, with gaps)
            line.setStrokeWidth(8f);
            line.setStrokeCap(Paint.Cap.ROUND);
            oval.set(cx - 205, cy - 205, cx + 205, cy + 205);
            for (int i = 0; i < 4; i++) c.drawArc(oval, i * 90 + 12, 66, false, line);
            line.setStrokeCap(Paint.Cap.BUTT);
            // dash ring
            line.setStrokeWidth(12f);
            oval.set(cx - 180, cy - 180, cx + 180, cy + 180);
            for (int i = 0; i < 36; i++) c.drawArc(oval, i * 10, 6, false, line);
            // fine tick ring
            line.setStrokeWidth(3f);
            for (int i = 0; i < 120; i++) {
                double a = Math.toRadians(i * 3);
                float co = (float) Math.cos(a), si = (float) Math.sin(a);
                c.drawLine(cx + co * 128, cy + si * 128, cx + co * 160, cy + si * 160, line);
            }
            // dark disc with a cross and a ring
            fill.setColor(0xFF1E1E1E);
            c.drawCircle(cx, cy, 120, fill);
            line.setStrokeWidth(4f);
            c.drawCircle(cx, cy, 46, line);
            c.drawLine(cx - 70, cy, cx - 24, cy, line);
            c.drawLine(cx + 24, cy, cx + 70, cy, line);
            c.drawLine(cx, cy - 70, cx, cy - 24, line);
            c.drawLine(cx, cy + 24, cx, cy + 70, line);
            fill.setColor(Color.WHITE);
            c.drawCircle(cx, cy, 5, fill);
        }
    }
}
