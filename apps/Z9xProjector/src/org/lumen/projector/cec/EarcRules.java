/*
 * EarcRules: the decisions of cec/EarcLink (eARC the stock way, devices/goat_elegant/stock analysis), kept free of
 * Android classes so test/earc/run.sh checks them on the host. Same vendor flow on the Z9X and the Z9X HB.
 */
package org.lumen.projector.cec;

public final class EarcRules {
    /** getEARCStatus (Z9X IGmpf 619, HB TV 79): vendor EN_CEC_EarcStatus, 3 = eARC linked (stock XRMService "== 3"). */
    public static final int EARC_LINKED = 3;

    private EarcRules() {}

    /** What EarcLink does with the HDMI ARC audio output after a status read. */
    public enum Step { NONE, CONNECT, DISCONNECT }

    /**
     * Stock (XRMService eARCstate -> AudioService.seteARCStatus, HdmiCecLocalDeviceTv "don't disconnect ARC while eARC
     * mode"): the HDMI ARC output stays connected while eARC is linked. ours = EarcLink connected it; arcPresent = an
     * HDMI ARC output is listed now (CEC ARC's, or ours). The framework removes CEC ARC's output on the soundbar's
     * &lt;Terminate ARC&gt; or on "Speaker", so it is connected again while the link holds; an output that is there
     * (with CEC ARC's descriptors) is never touched.
     */
    public static Step step(int status, boolean ours, boolean arcPresent) {
        if (status == EARC_LINKED) return arcPresent ? Step.NONE : Step.CONNECT;
        return ours ? Step.DISCONNECT : Step.NONE;
    }

    /**
     * After an eARC link ended (or an eARC attempt failed) and no ARC output came back: ask the framework for ARC again
     * (IHdmiControlService.setArcMode(true), the AOSP RequestArcInitiationAction) for "Auto" or the soundbar (kind =
     * AudioOutputChoice.Kind name, null = none stored), never with a local output chosen ("Speaker" ends ARC on purpose)
     * and only with an audio system known.
     */
    public static boolean arcRestart(String kind, boolean arcPresent, boolean avrKnown) {
        if (arcPresent || !avrKnown) return false;
        return kind == null || "AUTO".equals(kind) || "ARC".equals(kind);
    }

    /**
     * 2026-11-04: the "no eARC, no ARC" restart is scheduled only after an eARC event of this process: the panel row
     * pressed within windowMs (a failed Auto, the vendor's "Amp Not Support EARC"), or a link of 3 seen before. A plain
     * ARC soundbar the user turned off (it sends &lt;Terminate ARC&gt;) is never asked for ARC again.
     */
    public static boolean restartAllowed(long sinceUserMs, boolean userPressed, boolean sawLinked, long windowMs) {
        if (sawLinked) return true;
        return userPressed && sinceUserMs >= 0 && sinceUserMs < windowMs;
    }

    /** The restart itself: only with the screen on and an audio system that does not report standby. */
    public static boolean restartAwake(boolean interactive, boolean avrStandby) {
        return interactive && !avrStandby;
    }
}
