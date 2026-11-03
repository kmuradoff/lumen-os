package org.lumen.hb.boot;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * HbGmpf: the only path from HbBoot to XGIMI's vendor service on the Z9X HB (goat_elegant, G0130,
 * MT9660). The vendor process /vendor/bin/hw/xgimi.hardware.gmpf-service registers 14 stable AIDL
 * services "xgimi.hardware.gmpf.I*Manager/default" (frozen V1, hash HASH below). HbBoot talks to two
 * of them with raw Binder transactions, laid out exactly as the stock Java proxies do it
 * (com.xgimi.middle.jar, classes xgimi.hardware.gmpf.I{Power,System}Manager$Stub$Proxy):
 *   writeInterfaceToken(DESCRIPTOR) [+ writeInt(arg)] -> transact(code, data, reply, 0)
 *   -> false means "unimplemented"; else reply.readException() [+ readInt / readBoolean / readString].
 *
 * HARD ALLOWLIST (devices/goat_elegant/stock analysis, section 6). Only the members of the enum
 * Call can ever be transacted, with only the argument values listed there:
 *   meta   getInterfaceVersion  0xFFFFFF  ()            -> int    must be 1
 *   meta   getInterfaceHash     0xFFFFFE  ()            -> String must be HASH
 *   IPowerManager  2  goToBootCompleted(int stage)      -> boolean   stage 0 or 1
 *   ISystemManager 32 setBootAnimState(int state)       -> void      state 1 or 2
 *   ISystemManager 33 getBootAnimState()                -> int
 *   ISystemManager 36 setSystemUIState(int state)       -> void      state 1 only
 * IDisplayManager 99 / 100 (uiHeartBeatSet / uiHeartBeatDo) are deliberately NOT here: on the stock
 * system they are the eye protection "NativeUIWatch" heartbeat of SystemUI's EyeProjectionDialog, sent
 * only while that dialog is shown, not a boot or keep-alive signal (handshake.md section 4).
 *
 * VERSION GUARD: before the first real call on an interface, getInterfaceVersion must return 1 and
 * getInterfaceHash must return HASH. A mismatch, or a meta call that fails, latches that interface
 * closed for the life of the process: no further call of any kind is sent to it.
 *
 * Codes are absolute transaction codes (IBinder.FIRST_CALL_TRANSACTION + n with n = AIDL index), read
 * from the stock proxy bytecode (const/4 or const/16 before IBinder.transact) and cross-checked against
 * the native BpPowerManager / BpSystemManager tables in research/aidl_codes.tsv (handshake.md section 5).
 * Not thread safe by design: BootSequence calls it from its one worker thread only.
 */
final class HbGmpf {
    private static final String TAG = "HbBoot";

    /** Frozen hash of xgimi.hardware.gmpf V1 (34 copies in com.xgimi.middle.jar, one per stub). */
    static final String HASH = "908de964b7da427ad21df846e629b80ffdc183ab";
    static final int VERSION = 1;

    /** One stable AIDL interface of the vendor service, with its own guard state. */
    enum Iface {
        POWER("xgimi.hardware.gmpf.IPowerManager"),
        SYSTEM("xgimi.hardware.gmpf.ISystemManager");

        /** Interface descriptor, as the stub's DESCRIPTOR ("xgimi$hardware$gmpf$I..." with '$' -> '.'). */
        final String descriptor;
        /** servicemanager name, as in the stock middle jar and vendor_service_contexts. */
        final String instance;

        IBinder binder;
        boolean verified;   // version and hash checked and equal
        boolean refused;    // latched: never send anything to this interface again

        Iface(String descriptor) {
            this.descriptor = descriptor;
            this.instance = descriptor + "/default";
        }
    }

    /** The complete list of transactions this app can produce. */
    private enum Call {
        GET_INTERFACE_VERSION(null, 0x00FFFFFF, "getInterfaceVersion"),
        GET_INTERFACE_HASH(null, 0x00FFFFFE, "getInterfaceHash"),
        GO_TO_BOOT_COMPLETED(Iface.POWER, 2, "goToBootCompleted"),
        SET_BOOT_ANIM_STATE(Iface.SYSTEM, 32, "setBootAnimState"),
        GET_BOOT_ANIM_STATE(Iface.SYSTEM, 33, "getBootAnimState"),
        SET_SYSTEM_UI_STATE(Iface.SYSTEM, 36, "setSystemUIState");

        final Iface iface;   // null: meta call, valid on any Iface
        final int code;
        final String name;

        Call(Iface iface, int code, String name) {
            this.iface = iface;
            this.code = code;
            this.name = name;
        }
    }

    /** Argument values (stock callers, handshake.md section 3). */
    static final int STAGE_EARLY = 0;           // XRMService.initXRMServices: goToBootCompleted(0)
    static final int STAGE_AFTER_BOOTANIM = 1;  // BootControlService.bootCompletedCheck: goToBootCompleted(1)
    static final int BOOT_ANIM_RUNNING = 1;     // libbootanimation: "set boot anim running to gmpf!"
    static final int BOOT_ANIM_STOPPED = 2;     // libbootanimation: "set boot anim stopped to gmpf!"
    static final int SYSTEM_UI_READY = 1;       // SystemUI SystemUtils.systemUIServicesCompleted

    /** A delivered call whose reply was not what the stub expects (not repeated by the caller). */
    static final class ReplyException extends RemoteException {
        ReplyException(String msg) {
            super(msg);
        }
    }

    /**
     * servicemanager lookup without waiting (checkService, never the blocking getService).
     * true when both interfaces have a live binder. Never throws.
     */
    boolean connect() {
        boolean ok = true;
        for (Iface i : Iface.values()) {
            if (i.refused) continue;
            if (i.binder != null && i.binder.isBinderAlive()) continue;
            i.binder = null;
            i.verified = false;
            try {
                i.binder = ServiceManager.checkService(i.instance);
            } catch (Throwable t) {
                i.binder = null;
            }
            if (i.binder == null) ok = false;
        }
        return ok;
    }

    boolean isConnected() {
        for (Iface i : Iface.values()) {
            if (!i.refused && (i.binder == null || !i.binder.isBinderAlive())) return false;
        }
        return true;
    }

    /** IPowerManager 2 goToBootCompleted(stage), stage 0 or 1. */
    boolean goToBootCompleted(int stage) throws RemoteException {
        if (stage != STAGE_EARLY && stage != STAGE_AFTER_BOOTANIM) throw new IllegalArgumentException("stage " + stage);
        Parcel reply = transact(Call.GO_TO_BOOT_COMPLETED, true, stage);
        try {
            return reply.readBoolean();
        } finally {
            reply.recycle();
        }
    }

    /** ISystemManager 32 setBootAnimState(state), state 1 (running) or 2 (stopped). */
    void setBootAnimState(int state) throws RemoteException {
        if (state != BOOT_ANIM_RUNNING && state != BOOT_ANIM_STOPPED) throw new IllegalArgumentException("state " + state);
        transact(Call.SET_BOOT_ANIM_STATE, true, state).recycle();
    }

    /** ISystemManager 33 getBootAnimState(). */
    int getBootAnimState() throws RemoteException {
        Parcel reply = transact(Call.GET_BOOT_ANIM_STATE, false, 0);
        try {
            return reply.readInt();
        } finally {
            reply.recycle();
        }
    }

    /** ISystemManager 36 setSystemUIState(1). */
    void setSystemUiReady() throws RemoteException {
        transact(Call.SET_SYSTEM_UI_STATE, true, SYSTEM_UI_READY).recycle();
    }

    // ------------------------------------------------------------------ transport (the only transact)

    /** Guarded call: verify the interface first, then send. Returns the reply after readException. */
    private Parcel transact(Call c, boolean hasArg, int arg) throws RemoteException {
        if (c.iface == null) throw new IllegalStateException(c.name + " is a meta call");
        verify(c.iface);
        return raw(c.iface, c, hasArg, arg);
    }

    /** Version and hash check of one interface, once; a failure latches it closed. */
    private void verify(Iface i) throws RemoteException {
        if (i.refused) throw new ReplyException(i.descriptor + " refused earlier (version or hash guard)");
        if (i.verified) return;
        int version;
        String hash;
        try {
            Parcel r = raw(i, Call.GET_INTERFACE_VERSION, false, 0);
            try {
                version = r.readInt();
            } finally {
                r.recycle();
            }
            r = raw(i, Call.GET_INTERFACE_HASH, false, 0);
            try {
                hash = r.readString();
            } finally {
                r.recycle();
            }
        } catch (ReplyException e) {
            i.refused = true;
            Log.e(TAG, "guard: " + i.descriptor + " meta call failed, interface closed: " + e.getMessage());
            throw e;
        }
        if (version != VERSION || !HASH.equals(hash)) {
            i.refused = true;
            String msg = "guard: " + i.descriptor + " version " + version + " hash " + hash
                    + ", expected " + VERSION + " " + HASH + "; interface closed, nothing sent";
            Log.e(TAG, msg);
            throw new ReplyException(msg);
        }
        i.verified = true;
        Log.i(TAG, "guard: " + i.descriptor + " V" + version + " hash " + hash + " ok");
    }

    /** Parcel layout of the stock Stub.Proxy. RemoteException: not delivered (retry is safe). */
    private static Parcel raw(Iface i, Call c, boolean hasArg, int arg) throws RemoteException {
        if (c.iface != null && c.iface != i) throw new IllegalStateException(c.name + " is not on " + i.descriptor);
        IBinder b = i.binder;
        if (b == null) throw new RemoteException(i.instance + " not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean ok = false;
        try {
            data.writeInterfaceToken(i.descriptor);
            if (hasArg) data.writeInt(arg);
            if (!b.transact(c.code, data, reply, 0)) {
                throw new ReplyException("Method " + c.name + " is unimplemented (code " + c.code + ")");
            }
            try {
                reply.readException();
            } catch (RuntimeException e) {
                throw new ReplyException(c.name + " service exception: " + e);
            }
            ok = true;
            return reply;
        } finally {
            data.recycle();
            if (!ok) reply.recycle();
        }
    }
}
