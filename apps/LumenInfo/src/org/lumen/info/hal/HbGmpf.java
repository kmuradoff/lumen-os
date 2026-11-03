package org.lumen.info.hal;

import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import org.lumen.info.core.Hb;
import org.lumen.info.core.HalStructs;

/**
 * The only path from LumenInfo to XGIMI's vendor services on the Z9X HB (goat_elegant): raw Binder transactions to
 * the stable AIDL services "xgimi.hardware.gmpf.I<Name>/default", laid out as the stock Java proxies do
 * (writeInterfaceToken + arguments -> transact -> readException + value), the same wire as HbProjector's
 * AidlCaller. Allow list: only the members of {@link Call} can be sent; every one is a row of core.Hb.PLAN, which
 * the host test checks against devices/goat_elegant/research/allowed.tsv, aidl_codes.tsv and banned.tsv.
 * All are getters except setEdidVersion, which takes only 1 or 2 (the two stock EDID choices). The getters added on
 * 2026-11-01 are rows of research/allowed_lumeninfo.tsv; arguments are limited to HDMI ports 1 / 2, the
 * HDMI ARC device id and the DISPLAY 103 ranges.
 *
 * Guard (as HbProjector): before the first call on an interface getInterfaceVersion must be 1 and
 * getInterfaceHash the frozen V1 hash; a mismatch closes that interface for the process. checkService only
 * (never the blocking getService); calls on the main thread are refused. The vendor policy lets only system_app
 * reach these services, so this works only in the platform-signed build (sharedUserId android.uid.system); in
 * any other build {@link #available} is false and the app shows the vendor rows as not available.
 */
public final class HbGmpf {
    private static final String TAG = "LumenInfo";
    static final String HASH = "908de964b7da427ad21df846e629b80ffdc183ab";
    static final int VERSION = 1;

    enum Iface {
        DISPLAY("xgimi.hardware.gmpf.IDisplayManager"),
        TV("xgimi.hardware.gmpf.IGmTvManager"),
        TEMPERATURE("xgimi.hardware.gmpf.ITemperatureManager"),
        AUDIO("xgimi.hardware.gmpf.IGmAudioManager");

        final String descriptor, instance;
        IBinder binder;
        boolean verified, refused;

        Iface(String d) {
            descriptor = d;
            instance = d + "/default";
        }
    }

    /** Every transaction this app can produce (core.Hb.PLAN, same order). */
    enum Call {
        GET_INTERFACE_VERSION(null, 0x00FFFFFF, "getInterfaceVersion"),
        GET_INTERFACE_HASH(null, 0x00FFFFFE, "getInterfaceHash"),
        GET_CURRENT_INPUT_SOURCE(Iface.TV, 4, "getCurrentInputSource"),
        GET_PICTURE_MODE(Iface.TV, 34, "getPictureMode"),
        GET_HDMI_INFO(Iface.TV, 38, "getHdmiInfo"),
        GET_EDID_VERSION(Iface.TV, 39, "getEdidVersion"),
        SET_EDID_VERSION(Iface.TV, 40, "setEdidVersion"),
        GET_HDMI_RANGE(Iface.TV, 64, "getHdmiRange"),
        GET_HDR_TYPE(Iface.TV, 78, "getHdrType"),
        GET_ALLM_STATUS(Iface.TV, 131, "getAllmStatus"),
        GET_DISP_COLOR_SPACE(Iface.DISPLAY, 103, "getDispColorSpace"),
        GET_SYSTEM_TEMPERATURE(Iface.TEMPERATURE, 6, "getSystemTemperature"),
        GET_HDR_ENABLE(Iface.TV, 51, "getHdrEnable"),
        GET_HDMI_CONNECT_STATUS(Iface.TV, 70, "getHdmiConnectStatus"),
        GET_SIGNAL_STATUS(Iface.TV, 106, "getSignalStatus"),
        GET_VIDEO_INFO(Iface.TV, 122, "getVideoInfo"),
        IS_HDR_VIVID_PLAYING(Iface.TV, 135, "isHdrVividPlaying"),
        GET_HDMI_HW_CONFIG(Iface.TV, 153, "getHdmiHwConfig"),
        GET_SCREEN_RESOLUTION(Iface.DISPLAY, 31, "getScreenResolution"),
        GET_OUTPUT_TIMING_INFO(Iface.DISPLAY, 57, "getOutputTimingInfo"),
        GET_COLOR_SPACE_INFO(Iface.DISPLAY, 145, "getColorSpaceInfo"),
        GET_AUDIO_DELAY(Iface.AUDIO, 13, "getAudioDelay"),
        GET_ARC_TYPE(Iface.AUDIO, 28, "getARCType"),
        GET_SPDIF_TYPE(Iface.AUDIO, 29, "getSpdifType"),
        IS_AUDIO_DEVICE_CONNECTED(Iface.AUDIO, 33, "isAudioDeviceConnected"),
        GET_AUDIO_DEVICE_SUPPORT_TYPE(Iface.AUDIO, 34, "getAudioDeviceSupportType");

        final Iface iface;
        final int code;
        final String method;

        Call(Iface iface, int code, String method) {
            this.iface = iface;
            this.code = code;
            this.method = method;
        }
    }

    /** Decoded IGmTvType.stHdmiInfo (int width, int height, boolean interlace, boolean hdmiMode, int rate 1/100 Hz). */
    public static final class HdmiInfo {
        public final int width, height, frameRateCenti;
        public final boolean interlace, hdmiMode;

        HdmiInfo(int w, int h, boolean i, boolean m, int r) {
            width = w;
            height = h;
            interlace = i;
            hdmiMode = m;
            frameRateCenti = r;
        }

        /** Zero or nonsense means no signal (or a struct that did not decode). */
        public boolean plausible() {
            return width >= 320 && width <= 7680 && height >= 200 && height <= 4320
                    && frameRateCenti >= 2300 && frameRateCenti <= 24500;
        }
    }

    private interface Args { void write(Parcel p); }
    private interface Ret<T> { T read(Parcel p); }

    private HbGmpf() { }

    /** The vendor TV service is registered (an HB) and reachable from this process. Never throws. */
    public static boolean available() {
        try {
            return ServiceManager.checkService(Iface.TV.instance) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- getters (background thread only)
    public static int currentInputSource() throws RemoteException { return call(Call.GET_CURRENT_INPUT_SOURCE, null, Parcel::readInt); }

    public static int pictureMode() throws RemoteException { return call(Call.GET_PICTURE_MODE, p -> p.writeInt(0), Parcel::readInt); }

    public static HdmiInfo hdmiInfo() throws RemoteException {
        return call(Call.GET_HDMI_INFO, null, p -> {
            if (p.readInt() == 0) throw new IllegalStateException("null stHdmiInfo");
            int start = p.dataPosition();
            int size = p.readInt();
            if (size < 4 || size > p.dataSize() - start) throw new IllegalStateException("parcelable size " + size);
            HdmiInfo h = new HdmiInfo(p.readInt(), p.readInt(), p.readBoolean(), p.readBoolean(), p.readInt());
            if (p.dataPosition() - start > size) throw new IllegalStateException("stHdmiInfo shorter than its fields");
            p.setDataPosition(start + size);
            return h;
        });
    }

    public static int edidVersion() throws RemoteException { return call(Call.GET_EDID_VERSION, null, Parcel::readInt); }

    /** TV 40 with 1 (EDID 1.4) or 2 (EDID 2.0) only; anything else is refused before sending. */
    public static boolean setEdidVersion(int v) throws RemoteException {
        if (v != 1 && v != 2) throw new IllegalArgumentException("EDID version " + v + " is not a stock value");
        return call(Call.SET_EDID_VERSION, p -> p.writeInt(v), Parcel::readBoolean);
    }

    public static int hdmiRange() throws RemoteException { return call(Call.GET_HDMI_RANGE, null, Parcel::readByte); }

    public static int hdrType() throws RemoteException { return call(Call.GET_HDR_TYPE, null, Parcel::readInt); }

    /** ALLM flag received on HDMI 1 or 2. */
    public static boolean allmStatus(int hdmi) throws RemoteException {
        if (hdmi != 1 && hdmi != 2) throw new IllegalArgumentException("hdmi " + hdmi);
        return call(Call.GET_ALLM_STATUS, p -> p.writeInt(hdmi), Parcel::readBoolean);
    }

    /** source 0..2, picture mode 0..40 (as HbProjector). */
    public static int colorSpace(int source, int pictureMode) throws RemoteException {
        if (source < 0 || source > 2 || pictureMode < 0 || pictureMode > 40) throw new IllegalArgumentException("range");
        return call(Call.GET_DISP_COLOR_SPACE, p -> { p.writeInt(source); p.writeInt(pictureMode); }, Parcel::readInt);
    }

    public static int systemTemperature() throws RemoteException {
        return call(Call.GET_SYSTEM_TEMPERATURE, null, p -> p.readByte() & 0xff);
    }

    // ---------------------------------------------------------------- 2026-11-01: extra read-only getters
    // Wire of each one = the stock Java proxy (SystemUI release 838, xgimi/hardware/gmpf/I*Manager$Stub$Proxy):
    // arguments as written there, reply = readException, then the value; for the three "out" structs (TV 122,
    // DISPLAY 57, DISPLAY 145) a boolean result, then an int32 flag and, if not 0, the struct.

    /** TV 51: the HDR switch of the picture settings (stock hdrEnable(boolean) is its setter, never sent here). */
    public static boolean hdrEnable() throws RemoteException { return call(Call.GET_HDR_ENABLE, null, Parcel::readBoolean); }

    /** TV 70 with HDMI port 1 or 2 (a byte on the wire). */
    public static boolean hdmiConnectStatus(int port) throws RemoteException {
        if (port != 1 && port != 2) throw new IllegalArgumentException("port " + port);
        return call(Call.GET_HDMI_CONNECT_STATUS, p -> p.writeByte((byte) port), Parcel::readBoolean);
    }

    public static int signalStatus() throws RemoteException { return call(Call.GET_SIGNAL_STATUS, null, Parcel::readInt); }

    /** TV 122; null when the vendor answered false or sent no struct. */
    public static HalStructs.VideoInfo videoInfo() throws RemoteException {
        return call(Call.GET_VIDEO_INFO, null, p -> {
            boolean ok = p.readBoolean();
            int[] w = outStruct(p);
            return ok && w != null ? HalStructs.videoInfo(w) : null;
        });
    }

    public static boolean hdrVividPlaying() throws RemoteException { return call(Call.IS_HDR_VIVID_PLAYING, null, Parcel::readBoolean); }

    /** TV 153 with HDMI port 1 or 2; null for a null struct. */
    public static HalStructs.HdmiHwCfg hdmiHwConfig(int port) throws RemoteException {
        if (port != 1 && port != 2) throw new IllegalArgumentException("port " + port);
        return call(Call.GET_HDMI_HW_CONFIG, p -> p.writeInt(port), p -> {
            int[] w = outStruct(p);
            return w == null ? null : HalStructs.hdmiHwCfg(w);
        });
    }

    public static HalStructs.ScreenResolution screenResolution() throws RemoteException {
        return call(Call.GET_SCREEN_RESOLUTION, null, p -> {
            int[] w = outStruct(p);
            return w == null ? null : HalStructs.screenResolution(w);
        });
    }

    public static HalStructs.OutputTiming outputTiming() throws RemoteException {
        return call(Call.GET_OUTPUT_TIMING_INFO, null, p -> {
            boolean ok = p.readBoolean();
            int[] w = outStruct(p);
            return ok && w != null ? HalStructs.outputTiming(w) : null;
        });
    }

    /** DISPLAY 145, same arguments as DISPLAY 103: source 0..2, picture mode 0..40. */
    public static HalStructs.ColorSpaceInfo colorSpaceInfo(int source, int pictureMode) throws RemoteException {
        if (source < 0 || source > 2 || pictureMode < 0 || pictureMode > 40) throw new IllegalArgumentException("range");
        return call(Call.GET_COLOR_SPACE_INFO, p -> { p.writeInt(source); p.writeInt(pictureMode); }, p -> {
            boolean ok = p.readBoolean();
            int[] w = outStruct(p);
            return ok && w != null ? HalStructs.colorSpaceInfo(w) : null;
        });
    }

    public static int audioDelay() throws RemoteException { return call(Call.GET_AUDIO_DELAY, null, Parcel::readInt); }

    public static int arcType() throws RemoteException { return call(Call.GET_ARC_TYPE, null, Parcel::readByte); }

    public static int spdifType() throws RemoteException { return call(Call.GET_SPDIF_TYPE, null, Parcel::readByte); }

    /** GmAudioManager 33 / 34 take a device id; only HDMI ARC (2), the id the stock VoiceHelper asks, is sent. */
    public static boolean arcConnected() throws RemoteException {
        return call(Call.IS_AUDIO_DEVICE_CONNECTED, p -> p.writeByte(Hb.AUDIO_DEVICE_ARC), Parcel::readBoolean);
    }

    public static int arcSupportType() throws RemoteException {
        return call(Call.GET_AUDIO_DEVICE_SUPPORT_TYPE, p -> p.writeByte(Hb.AUDIO_DEVICE_ARC), Parcel::readByte);
    }

    /**
     * A nullable stable parcelable at the reply position (readTypedObject / the out-struct flag): int32 0 = null,
     * else the struct; returns its field words (see core.HalStructs) and leaves the position after the struct.
     */
    private static int[] outStruct(Parcel p) {
        if (p.readInt() == 0) return null;
        int start = p.dataPosition();
        int size = p.readInt();
        int n = HalStructs.fieldWords(size, p.dataAvail());
        if (n < 0) throw new IllegalStateException("parcelable size " + size);
        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = p.readInt();
        p.setDataPosition(start + size);
        return w;
    }

    // ---------------------------------------------------------------- transport
    private static <T> T call(Call c, Args a, Ret<T> r) throws RemoteException {
        if (Looper.getMainLooper().isCurrentThread()) throw new RemoteException("vendor call on the main thread refused");
        IBinder b = need(c.iface);
        return send(b, c.iface, c, a, r);
    }

    private static IBinder need(Iface i) throws RemoteException {
        synchronized (HbGmpf.class) {
            if (i.refused) throw new RemoteException(i.descriptor + " closed by the version guard");
            if (i.binder == null || !i.binder.isBinderAlive()) {
                i.binder = null;
                i.verified = false;
                IBinder b;
                try {
                    b = ServiceManager.checkService(i.instance);
                } catch (Throwable t) {
                    throw new RemoteException(i.instance + ": " + t);
                }
                if (b == null) throw new RemoteException(i.instance + " not available");
                i.binder = b;
            }
            if (!i.verified) {
                int v;
                String h;
                try {
                    v = send(i.binder, i, Call.GET_INTERFACE_VERSION, null, Parcel::readInt);
                    h = send(i.binder, i, Call.GET_INTERFACE_HASH, null, Parcel::readString);
                } catch (RemoteException e) {
                    i.binder = null;
                    throw e;
                }
                if (v != VERSION || !HASH.equals(h)) {
                    i.refused = true;
                    i.binder = null;
                    Log.e(TAG, "guard: " + i.descriptor + " version " + v + " hash " + h + ": closed, nothing sent");
                    throw new RemoteException(i.descriptor + " version/hash mismatch");
                }
                i.verified = true;
            }
            return i.binder;
        }
    }

    private static <T> T send(IBinder b, Iface i, Call c, Args a, Ret<T> r) throws RemoteException {
        if (c.iface != null && c.iface != i) throw new IllegalStateException(c.method + " is not on " + i.descriptor);
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(i.descriptor);
            if (a != null) a.write(data);
            boolean done;
            try {
                done = b.transact(c.code, data, reply, 0);
            } catch (RuntimeException e) {
                drop(i, b);
                throw new RemoteException(c.method + ": " + e);
            } catch (RemoteException e) {
                drop(i, b);
                throw e;
            }
            if (!done) throw new RemoteException(c.method + " unimplemented");
            try {
                reply.readException();
                return r.read(reply);
            } catch (RuntimeException e) {
                throw new RemoteException("reply of " + c.method + ": " + e);
            }
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void drop(Iface i, IBinder b) {
        synchronized (HbGmpf.class) {
            if (i.binder == b) {
                i.binder = null;
                i.verified = false;
            }
        }
    }
}
