/*
 * Play6MusicPlayerCallback: our Java xgimi.hardware.gmpf@1.0::IMusicPlayerCallback for IGmpf 215 / 227 on the
 * XGIMI Play 6 (P3 item 21, stock parity: the "device closed / opened" event of the ambient speaker).
 *
 * musicplayerEvent = transaction 1: token, i32 type, i32 keycode, i32 status; reply Status(0) + bool(true), sent
 * at once, also on a parse error (BpHwMusicPlayerCallback::_hidl_musicplayerEvent of the stock v1.7.116 library
 * reads Status then a bool). Stock com.xgimi.xrmservice MachineStateService acts on type 4 only: status 1 = the
 * projector was closed with the slide function "ambient speaker" (IGmpf2 69 = 1), anything else = opened. The
 * vendor also reports its MCU body keys here (stock NewSounderMode HwDevice: 4 play / pause, 8 next); this app
 * only logs those. The callback never calls the HAL and never blocks; it hands the event to the Sink. The
 * IBase-reserved codes are answered like the hidl-gen Java IBase.Stub; getHashChain returns the
 * IMusicPlayerCallback hash recorded offline from both stock vendor libraries (research/play6/hidl_hashes.txt:
 * 56684d1e...d6fc, the same on v1.7.116 and v1.1.67) and the IBase hash as element 1.
 */
package org.z9x.projector.hal;

import android.os.HwBinder;
import android.os.HwBlob;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;

public final class Play6MusicPlayerCallback extends HwBinder implements IHwInterface {
    private static final String TAG = "P6Music";
    public static final String IMUSIC_PLAYER_CALLBACK = "xgimi.hardware.gmpf@1.0::IMusicPlayerCallback";
    static final String IBASE = "android.hidl.base@1.0::IBase";

    static final int MUSIC_PLAYER_EVENT = 1;
    static final int HIDL_DESCRIPTOR_CHAIN = 0x0f43484e;        // 256067662 interfaceChain
    static final int HIDL_DEBUG = 0x0f444247;                   // 256131655 debug
    static final int HIDL_GET_DESCRIPTOR = 0x0f445343;          // 256136003 interfaceDescriptor
    static final int HIDL_HASH_CHAIN = 0x0f485348;              // 256398152 getHashChain
    static final int HIDL_SET_HAL_INSTRUMENTATION = 0x0f494e54; // 256462420 oneway
    static final int HIDL_PING = 0x0f504e47;                    // 256921159 ping
    static final int HIDL_GET_REF_INFO = 0x0f524546;            // 257049926 getDebugInfo
    static final int HIDL_SYSPROPS_CHANGED = 0x0f535953;        // 257120595 oneway

    /** Recorded offline (research/play6/hidl_hashes.txt): IMusicPlayerCallback, then IBase. */
    private static final byte[] HASH_SELF = hex("56684d1e7f030fdde318d46504290bf35be646a5e3c71bbcf0144aea2e88d6fc");
    private static final byte[] HASH_BASE = hex("ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c");

    /** Receives music-player events. Called on the hwbinder thread AFTER the reply was sent: only post. */
    public interface Sink {
        void onMusicPlayerEvent(int type, int keycode, int status);
    }

    private final Sink sink;

    /** The caller keeps a strong reference (the HAL holds only a binder reference to this object). */
    public Play6MusicPlayerCallback(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void onTransact(int code, HwParcel req, HwParcel reply, int flags) {
        if (code == MUSIC_PLAYER_EVENT) {
            int type = -1, keycode = -1, status = -1;
            boolean parsed = false;
            try {
                req.enforceInterface(IMUSIC_PLAYER_CALLBACK);
                type = req.readInt32();
                keycode = req.readInt32();
                status = req.readInt32();
                parsed = true;
            } catch (Throwable t) {
                Log.w(TAG, "musicplayerEvent parse: " + t);
            }
            // Reply at once, also on a parse error: the vendor waits for Status + bool.
            try {
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.writeBool(true);
                reply.send();
            } catch (Throwable t) {
                Log.w(TAG, "musicplayerEvent reply: " + t);
            }
            if (parsed && sink != null) {
                try { sink.onMusicPlayerEvent(type, keycode, status); } catch (Throwable t) { Log.w(TAG, "sink: " + t); }
            }
            return;
        }
        try {
            switch (code) {
                case HIDL_DESCRIPTOR_CHAIN: {
                    req.enforceInterface(IBASE);
                    ArrayList<String> chain = new ArrayList<>();
                    chain.add(IMUSIC_PLAYER_CALLBACK);
                    chain.add(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeStringVector(chain);
                    reply.send();
                    break;
                }
                case HIDL_GET_DESCRIPTOR: {
                    req.enforceInterface(IBASE);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeString(IMUSIC_PLAYER_CALLBACK);
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
        return IMUSIC_PLAYER_CALLBACK.equals(descriptor) || IBASE.equals(descriptor) ? this : null;
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
