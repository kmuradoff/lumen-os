package org.lumen.projector.hal;

import android.os.RemoteException;

/**
 * Play6Gmpf: the typed IGmpf client of the XGIMI Play 6 (xgimi.hardware.gmpf@1.0::IGmpf/default, a
 * 32-bit vendor). Only the stock boot handshake for now; each new call gets its own typed method here.
 *
 * Codes: research/play6/gmpf_codes_play6.tsv, read from the Play 6 vendor proxy library (BpHwGmpf:
 * the same 702 codes as on the Z9X, research/play6/gmpf_diff.txt). Wire types as in Z9xProjector
 * GmpfClient: 391 setSystemUIState(i32), 237 goToBootCompleted(i32) -> bool, 394 getBootAnimState()
 * -> i32, 395 setBootAnimState(i32). The transport (main-thread refusal, two-way, ReplyException)
 * is the shared HidlCaller.
 */
public final class Play6Gmpf extends HidlCaller {
    public static final String IGMPF = "xgimi.hardware.gmpf@1.0::IGmpf";

    private static final int GO_TO_BOOT_COMPLETED = 237;   // (i32) -> bool
    private static final int SET_SYSTEM_UI_STATE = 391;    // (i32) -> void
    private static final int GET_BOOT_ANIM_STATE = 394;    // ()    -> i32
    private static final int SET_BOOT_ANIM_STATE = 395;    // (i32) -> void

    private static final int SYSTEM_UI_STATE_READY = 1;      // stock SystemUI systemUIServicesCompleted
    private static final int BOOT_ANIM_STATE_FINISHED = 2;   // stock bootanimation exit

    /** goToBootCompleted arguments: 0 early (stock XRMService.onCreate), 1 after the boot animation. */
    public static final int STAGE_EARLY = 0, STAGE_AFTER_BOOTANIM = 1;

    public Play6Gmpf() {
        super(IGMPF);
    }

    /** HwBinder.getService(IGmpf, "default"), no wait, no death recipient. Never throws. */
    public boolean connect() {
        return connectInternal(null, 0);
    }

    /** 391 setSystemUIState(1). */
    public void setSystemUiReady() throws RemoteException { callI32(SET_SYSTEM_UI_STATE, SYSTEM_UI_STATE_READY); }

    /** 395 setBootAnimState(2). */
    public void setBootAnimFinished() throws RemoteException { callI32(SET_BOOT_ANIM_STATE, BOOT_ANIM_STATE_FINISHED); }

    /** 394 getBootAnimState(). */
    public int getBootAnimState() throws RemoteException { return callRetI32(GET_BOOT_ANIM_STATE); }

    /** 237 goToBootCompleted(stage). The caller guarantees once per boot per stage. */
    public boolean goToBootCompleted(int stage) throws RemoteException {
        return callI32RetBool(GO_TO_BOOT_COMPLETED, checkRange("stage", stage, STAGE_EARLY, STAGE_AFTER_BOOTANIM));
    }
}
