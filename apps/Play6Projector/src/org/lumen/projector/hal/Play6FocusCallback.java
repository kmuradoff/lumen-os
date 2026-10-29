/*
 * Play6FocusCallback: our Java xgimi.hardware.gmpf@1.0::IProjectorFocusCallback for IGmpf 298 / 306 on
 * the XGIMI Play 6 (the object of Z9xProjector GmpfClient.FocusCallback, in its own file because the
 * Play 6 client Play6Gmpf keeps its frozen handshake members, check K).
 *
 * focusEvent = transaction 1: token, i32 type, hidl_string value; reply Status(0) + bool(true), sent at
 * once, also on a parse error: the vendor waits for it while it holds its listener mutex. The callback
 * never calls the HAL and never blocks; it only hands the event to the Sink, which must just post it.
 * The IBase-reserved codes are answered like the hidl-gen Java IBase.Stub; getHashChain returns the
 * IProjectorFocusCallback hash recorded offline from the stock v1.7.116 library
 * (research/play6/hidl_hashes.txt, plan 2.1: 657623bc...611e) and the IBase hash as element 1.
 */
package org.lumen.projector.hal;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;

public final class Play6FocusCallback extends HwBinder implements IHwInterface {
    private static final String TAG = "P6Focus";
    public static final String IPROJECTOR_FOCUS_CALLBACK = "xgimi.hardware.gmpf@1.0::IProjectorFocusCallback";
    static final String IBASE = "android.hidl.base@1.0::IBase";

    static final int FOCUS_EVENT = 1;
    static final int HIDL_DESCRIPTOR_CHAIN = 0x0f43484e;        // 256067662 interfaceChain
    static final int HIDL_DEBUG = 0x0f444247;                   // 256131655 debug
    static final int HIDL_GET_DESCRIPTOR = 0x0f445343;          // 256136003 interfaceDescriptor
    static final int HIDL_HASH_CHAIN = 0x0f485348;              // 256398152 getHashChain
    static final int HIDL_SET_HAL_INSTRUMENTATION = 0x0f494e54; // 256462420 oneway
    static final int HIDL_PING = 0x0f504e47;                    // 256921159 ping
    static final int HIDL_GET_REF_INFO = 0x0f524546;            // 257049926 getDebugInfo
    static final int HIDL_SYSPROPS_CHANGED = 0x0f535953;        // 257120595 oneway

    /** Recorded offline (plan 2.1): IProjectorFocusCallback, then IBase. */
    private static final byte[] HASH_SELF = hex("657623bcdeadd6be8c60deb99c61eecef80732eebedd3dad9ae54949dbd2611e");
    private static final byte[] HASH_BASE = hex("ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c");

    /** Receives focus events. Called on the hwbinder thread AFTER the reply was sent: only post. */
    public interface Sink {
        void onFocusEvent(int type, String value);
    }

    private final Sink sink;

    /** The caller keeps a strong reference (the HAL holds only a binder reference to this object). */
    public Play6FocusCallback(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void onTransact(int code, HwParcel req, HwParcel reply, int flags) {
        if (code == FOCUS_EVENT) {
            int type = -1;
            String value = null;
            boolean parsed = false;
            try {
                req.enforceInterface(IPROJECTOR_FOCUS_CALLBACK);
                type = req.readInt32();
                value = req.readString();
                parsed = true;
            } catch (Throwable t) {
                Log.w(TAG, "focusEvent parse: " + t);
            }
            // Reply at once, also on a parse error: the vendor waits for Status + bool.
            try {
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.writeBool(true);
                reply.send();
            } catch (Throwable t) {
                Log.w(TAG, "focusEvent reply: " + t);
            }
            if (parsed && sink != null) {
                try { sink.onFocusEvent(type, value); } catch (Throwable t) { Log.w(TAG, "sink: " + t); }
            }
            return;
        }
        try {
            switch (code) {
                case HIDL_DESCRIPTOR_CHAIN: {
                    req.enforceInterface(IBASE);
                    ArrayList<String> chain = new ArrayList<>();
                    chain.add(IPROJECTOR_FOCUS_CALLBACK);
                    chain.add(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeStringVector(chain);
                    reply.send();
                    break;
                }
                case HIDL_GET_DESCRIPTOR: {
                    req.enforceInterface(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeString(IPROJECTOR_FOCUS_CALLBACK);
                    reply.send();
                    break;
                }
                case HIDL_PING: {
                    req.enforceInterface(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.send();
                    break;
                }
                case HIDL_HASH_CHAIN: {
                    req.enforceInterface(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    HwBlob vec = new HwBlob(16);              // hidl_vec<uint8_t[32]>
                    vec.putInt32(8, 2);                       // mSize
                    vec.putBool(12, false);                   // mOwnsBuffer
                    HwBlob data = new HwBlob(2 * 32);
                    data.putInt8Array(0, HASH_SELF);
                    data.putInt8Array(32, HASH_BASE);
                    vec.putBlob(0, data);                     // mBuffer
                    reply.writeBuffer(vec);
                    reply.send();
                    break;
                }
                case HIDL_GET_REF_INFO: {                      // DebugInfo{i32 pid; u64 ptr; i32 arch}
                    req.enforceInterface(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    HwBlob info = new HwBlob(24);
                    info.putInt32(0, Process.myPid());
                    info.putInt64(8, 0L);
                    info.putInt32(16, 0);                     // Architecture.UNKNOWN
                    reply.writeBuffer(info);
                    reply.send();
                    break;
                }
                case HIDL_DEBUG: {
                    req.enforceInterface(IBASE);
                    req.readNativeHandle();
                    req.readStringVector();
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.send();
                    break;
                }
                case HIDL_SET_HAL_INSTRUMENTATION:
                case HIDL_SYSPROPS_CHANGED:
                default:
                    // oneway IBase calls, linkToDeath/unlinkToDeath and unknown codes: no reply
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "IBase code " + code + ": " + t);
        }
    }

    @Override public IHwInterface queryLocalInterface(String descriptor) {
        return IPROJECTOR_FOCUS_CALLBACK.equals(descriptor) || IBASE.equals(descriptor) ? this : null;
    }
    @Override public IHwBinder asBinder() { return this; }
    @Override public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) { return true; }
    @Override public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) { return true; }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}
