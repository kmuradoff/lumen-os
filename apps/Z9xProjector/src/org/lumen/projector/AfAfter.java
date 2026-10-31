package org.lumen.projector;

/**
 * The one action queued for "after the autofocus" (AfCardOverlay.runAfterCard, the quick panel the AF card closed).
 * Plain Java, no Android: AfCardOverlay keeps the windows and the timers, this class only decides who runs the
 * action and makes sure it runs at most once (sh test/afcard/run.sh).
 *
 * The action is handed out by whichever comes first:
 * <ul>
 * <li>the AF card closed (not by LEFT / RIGHT: the manual focus window opens then, nothing comes back);</li>
 * <li>the AF ended while no card is up (2026-10-31, HB af2 test: a lens move that ends at optical step 0 gets an AF
 *     that the vendor finishes in about 1.4 s with events 12, 5 "1:44" and 18 "AF_Success_Into_Adjust" and no
 *     335, so no card ever came and the panel waited the whole fallback, about 4 s too long);</li>
 * <li>the caller's fallback timer when neither came (the AF never started).</li>
 * </ul>
 * Not thread safe: main thread only, as AfCardOverlay.
 */
final class AfAfter {
    private Runnable pending;
    private boolean card;

    /**
     * Vendor focus events that end an AF (same set on the Z9X and the HB, docs/z9x/parity/focus.md items 1 and 14):
     * 5 AF done (closes the card too), 7 and 2 AF stopped / failed (close the card too), 18 "AF_Success_Into_Adjust"
     * (the stock "focus complete" hint, comes right after 5).
     */
    static boolean isAfEndEvent(int type) {
        return type == 5 || type == 7 || type == 2 || type == 18;
    }

    /** Replaces the queued action ({@code null} clears it). True when the caller should arm its no-card timer. */
    boolean queue(Runnable r) {
        pending = r;
        return r != null && !card;
    }

    /** An action is waiting. */
    boolean hasPending() { return pending != null; }

    /** The card went up; {@code restore} (may be null) is queued only when nothing else is. */
    void cardShown(Runnable restore) {
        card = true;
        if (pending == null) pending = restore;
    }

    /** The card went: the action to run now, or null ({@code manual}: LEFT / RIGHT opened the manual focus window). */
    Runnable cardClosed(boolean manual) {
        card = false;
        Runnable r = pending;
        pending = null;
        return manual ? null : r;
    }

    /** A vendor focus event: the action to run now when it ends an AF that showed no card, else null. */
    Runnable focusEvent(int type) {
        if (card || pending == null || !isAfEndEvent(type)) return null;
        Runnable r = pending;
        pending = null;
        return r;
    }

    /** The caller's no-card timer fired: the action to run now, or null (a card is up, or it already ran). */
    Runnable noCardTimeout() {
        if (card) return null;
        Runnable r = pending;
        pending = null;
        return r;
    }
}
