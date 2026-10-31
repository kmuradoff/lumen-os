package org.lumen.projector;

/**
 * Host check of the queued "after the autofocus" action (AfCardOverlay.runAfterCard, decisions in {@link AfAfter}):
 * it runs on the card close, or on the AF end without a card (HB af2 test 2026-10-31: 12, 5, 18 and no 335), or on
 * the fallback timer, whichever is first, and never twice. sh test/afcard/run.sh
 */
public final class AfAfterTest {
    private static int failed, passed;

    private static void eq(String what, Object want, Object got) {
        if (want == got || (want != null && want.equals(got))) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    private static Runnable named(String n) {
        return new Runnable() {
            @Override public void run() {}
            @Override public String toString() { return n; }
        };
    }

    public static void main(String[] a) {
        Runnable back = named("back"), restore = named("restore"), other = named("other");

        // ---- b2: AF without a card, vendor 12 / 5 / 18: the action runs on 5, once
        AfAfter q = new AfAfter();
        eq("b2 queue arms the timer", true, q.queue(back));
        eq("b2 event 330", null, q.focusEvent(330));
        eq("b2 event 12", null, q.focusEvent(12));
        eq("b2 event 5", back, q.focusEvent(5));
        eq("b2 event 18 after", null, q.focusEvent(18));
        eq("b2 timer after", null, q.noCardTimeout());
        eq("b2 nothing pending", false, q.hasPending());

        // ---- each AF end event alone releases it; others do not
        for (int t : new int[] {5, 7, 2, 18}) {
            q = new AfAfter();
            q.queue(back);
            eq("end event " + t, back, q.focusEvent(t));
        }
        for (int t : new int[] {0, 1, 4, 12, 106, 116, 120, 330}) {
            q = new AfAfter();
            q.queue(back);
            eq("not an end event " + t, null, q.focusEvent(t));
            eq("still pending after " + t, true, q.hasPending());
        }

        // ---- b1: the card comes: AF end events while it is up do not run it, the close does (once)
        q = new AfAfter();
        q.queue(back);
        q.cardShown(null);
        eq("b1 18 while card up", null, q.focusEvent(18));
        eq("b1 timer while card up", null, q.noCardTimeout());
        eq("b1 close", back, q.cardClosed(false));
        eq("b1 event 5 after close", null, q.focusEvent(5));
        eq("b1 event 18 after close", null, q.focusEvent(18));
        eq("b1 timer after close", null, q.noCardTimeout());

        // ---- the card closed by LEFT / RIGHT: nothing comes back, and nothing later either
        q = new AfAfter();
        q.queue(back);
        q.cardShown(null);
        eq("manual close", null, q.cardClosed(true));
        eq("manual then 5", null, q.focusEvent(5));
        eq("manual then timer", null, q.noCardTimeout());

        // ---- the AF never started: the timer runs it, then nothing else
        q = new AfAfter();
        q.queue(back);
        eq("timer", back, q.noCardTimeout());
        eq("timer then 5", null, q.focusEvent(5));

        // ---- no action queued: AF end events do nothing; the card restores its own panel
        q = new AfAfter();
        eq("idle 5", null, q.focusEvent(5));
        q.cardShown(restore);
        eq("restore on close", restore, q.cardClosed(false));

        // ---- a queued action wins over the card's restore
        q = new AfAfter();
        q.queue(back);
        q.cardShown(restore);
        eq("queued wins", back, q.cardClosed(false));

        // ---- queue while the card is up: no timer, runs on close
        q = new AfAfter();
        q.cardShown(null);
        eq("queue during card: no timer", false, q.queue(back));
        eq("queue during card: close", back, q.cardClosed(false));

        // ---- a later queue replaces the earlier action; null clears it
        q = new AfAfter();
        q.queue(back);
        q.queue(other);
        eq("replaced", other, q.focusEvent(5));
        q.queue(back);
        eq("clear arms no timer", false, q.queue(null));
        eq("cleared", null, q.focusEvent(5));

        System.out.println("AfAfterTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
