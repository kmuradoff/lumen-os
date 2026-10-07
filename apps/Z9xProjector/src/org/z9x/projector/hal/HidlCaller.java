/*
 * HidlCaller: the single transact path shared by GmpfClient (IGmpf) and Gmpf2Client (IGmpf2).
 *
 * Wire rules (research/v6/RESULT_hal.json decisions[0], re-verified from the vendor proxy library
 * /vendor/lib64/xgimi.hardware.gmpf@1.0.so: every BpHwGmpf / BpHwGmpf2 proxy has `mov w4,wzr`):
 *  - every method is two-way: transact(code, req, reply, 0); never FLAG_ONEWAY;
 *  - request = writeInterfaceToken(descriptor) + arguments;
 *  - reply   = int32 Status (verifySuccess) + return value (if any);
 *  - u8 / i8 / bool are one byte (writeInt8 / readInt8 / writeBool / readBool), laptop
 *    ~/lineage/system/libhwbinder/Parcel.cpp:509-517, 539-542;
 *  - a HIDL struct without embedded pointers travels as one writeBuffer / readBuffer of exactly
 *    sizeof(struct); HwParcel.readBuffer(n) throws NoSuchElementException when the size differs
 *    (libhwbinder Parcel::readBuffer -> verifyBufferObject), so a wrong size only fails OUR call.
 *
 * Safety:
 *  - Calls from the main thread are refused (RemoteException) before anything is sent: the HAL
 *    holds a global mutex and some calls take seconds.
 *  - A failure inside transact() is a RemoteException (not delivered or HAL dead). A failure AFTER
 *    transact() returned is a ReplyException: the request DID reach the HAL; one-shot calls must not
 *    be repeated.
 *  - No subclass exposes a raw code: every public method is one typed, whitelisted call.
 */
package org.z9x.projector.hal;

import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

abstract class HidlCaller {
    static final String TAG = "Z9xGmpf";
    static final String INSTANCE = "default";
    private static final int TWO_WAY = 0;

    private final String descriptor;
    private volatile IHwBinder binder;

    HidlCaller(String descriptor) {
        this.descriptor = descriptor;
    }

    // ---------------------------------------------------------------- connection
    /**
     * HwBinder.getService(descriptor, "default", retry=false) + optional linkToDeath. Does not wait
     * for the service. Returns true when connected. Never throws.
     */
    synchronized boolean connectInternal(IHwBinder.DeathRecipient death, long cookie) {
        if (binder != null) return true;
        try {
            IHwBinder b = HwBinder.getService(descriptor, INSTANCE, false);
            if (b == null) return false;
            if (death != null && !b.linkToDeath(death, cookie)) {
                Log.w(TAG, descriptor + ": linkToDeath failed (service already dead)");
                return false;
            }
            binder = b;
            Log.i(TAG, "connected to " + descriptor + "/" + INSTANCE);
            return true;
        } catch (Throwable t) {   // RemoteException, NoSuchElementException, any RuntimeException
            Log.w(TAG, descriptor + " not available: " + t);
            return false;
        }
    }

    /** Forget the binder after a death notice (no HAL call). */
    public synchronized void dropConnection() {
        binder = null;
    }

    public boolean isConnected() {
        return binder != null;
    }

    /** Lazy connect for clients without a death recipient (IGmpf2). Never throws. */
    boolean ensureConnected() {
        return binder != null || connectInternal(null, 0);
    }

    // ------------------------------------------------------------- parcel helpers
    interface Ret<T> { T read(HwParcel r); }
    static final Ret<Void> RET_VOID = r -> null;
    static final Ret<Boolean> RET_BOOL = HwParcel::readBool;
    static final Ret<Byte> RET_I8 = HwParcel::readInt8;
    static final Ret<Integer> RET_I32 = HwParcel::readInt32;
    /** u8 reply as 0..255. */
    static final Ret<Integer> RET_U8 = r -> r.readInt8() & 0xff;

    final HwParcel request() {
        HwParcel q = new HwParcel();
        q.writeInterfaceToken(descriptor);
        return q;
    }

    /** The only place that transacts. Package-private: codes come only from the typed methods. */
    final <T> T call(int code, HwParcel q, Ret<T> ret) throws RemoteException {
        if (Looper.getMainLooper().isCurrentThread()) {
            Log.e(TAG, descriptor + " code " + code + " refused: called on the main thread");
            throw new RemoteException("HAL call on the main thread refused");
        }
        IHwBinder b = binder;
        if (b == null) throw new RemoteException(descriptor + " not connected");
        HwParcel r = new HwParcel();
        try {
            try {
                b.transact(code, q, r, TWO_WAY);
            } catch (RemoteException e) {
                if (onTransactFailed()) dropConnection();
                throw e;
            } catch (RuntimeException e) {
                throw new RemoteException("transact " + code + ": " + e);
            }
            try {
                r.verifySuccess();
                q.releaseTemporaryStorage();
                return ret.read(r);
            } catch (RuntimeException e) {
                throw new GmpfClient.ReplyException("reply of " + code + ": " + e);
            }
        } finally {
            try { r.release(); } catch (RuntimeException ignored) { }
        }
    }

    /** true: drop the binder on a transact failure so the next call reconnects (IGmpf2 only). */
    boolean onTransactFailed() { return false; }

    final void callU8(int code, int v) throws RemoteException {
        HwParcel q = request();
        q.writeInt8((byte) v);
        call(code, q, RET_VOID);
    }

    final boolean callU8RetBool(int code, int v) throws RemoteException {
        HwParcel q = request();
        q.writeInt8((byte) v);
        return call(code, q, RET_BOOL);
    }

    final void callI32(int code, int v) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(v);
        call(code, q, RET_VOID);
    }

    final boolean callI32RetBool(int code, int v) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(v);
        return call(code, q, RET_BOOL);
    }

    final int callI32RetI32(int code, int v) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(v);
        return call(code, q, RET_I32);
    }

    final boolean callI32I32RetBool(int code, int a, int b) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(a);
        q.writeInt32(b);
        return call(code, q, RET_BOOL);
    }

    final int callI32I32RetI32(int code, int a, int b) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(a);
        q.writeInt32(b);
        return call(code, q, RET_I32);
    }

    final boolean callI32BoolRetBool(int code, int a, boolean b) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(a);
        q.writeBool(b);
        return call(code, q, RET_BOOL);
    }

    final void callBool(int code, boolean v) throws RemoteException {
        HwParcel q = request();
        q.writeBool(v);
        call(code, q, RET_VOID);
    }

    final boolean callBoolRetBool(int code, boolean v) throws RemoteException {
        HwParcel q = request();
        q.writeBool(v);
        return call(code, q, RET_BOOL);
    }

    final int callBoolRetI32(int code, boolean v) throws RemoteException {
        HwParcel q = request();
        q.writeBool(v);
        return call(code, q, RET_I32);
    }

    final int callI32x4RetI32(int code, int a, int b, int c, int d) throws RemoteException {
        HwParcel q = request();
        q.writeInt32(a);
        q.writeInt32(b);
        q.writeInt32(c);
        q.writeInt32(d);
        return call(code, q, RET_I32);
    }

    final boolean callRetBool(int code) throws RemoteException { return call(code, request(), RET_BOOL); }

    final int callRetI32(int code) throws RemoteException { return call(code, request(), RET_I32); }

    final int callRetU8(int code) throws RemoteException { return call(code, request(), RET_U8); }

    final byte callRetI8(int code) throws RemoteException { return call(code, request(), RET_I8); }

    // ------------------------------------------------------------- argument checks
    static int checkRange(String what, int v, int min, int max) {
        if (v < min || v > max) throw new IllegalArgumentException(what + " " + v + " not in " + min + ".." + max);
        return v;
    }
}
