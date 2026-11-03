package org.lumen.sensors.hal;

import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * X50 ONLY (XGIMI G0111, X50 Ultra, mt9681): the one path from Lumen Sensors to XGIMI's stable AIDL vendor services
 * "xgimi.hardware.gmpf.I<Name>/default" (format aidl, V1, hash {@link #HASH}). Raw Binder
 * transactions laid out as the stock Java proxies of the X50 (release_838_newsettings,
 * xgimi/hardware/gmpf/I<Name>$Stub$Proxy): writeInterfaceToken + arguments (a byte goes as int32) -> transact ->
 * readException + value; for the "out" structs a boolean result, then an int32 flag and, if not 0, the struct.
 * The same wire as LumenInfo's HbGmpf and HbProjector's AidlCaller on the HB, whose 1111 methods the X50 has at the
 * same codes.
 *
 * Allow list: only the members of {@link Call} can be sent, every one a getter; the list equals
 * devices/g0111/research/allowed_lumensensors.tsv (stock citations there) and is checked by
 * tools/devices/check_aidl_hal.py (test/run.sh runs it on this file alone) against the X50 code table
 * research/aidl_codes.tsv and the HB banned.tsv. Arguments: sensor index 1..12 (TEMPERATURE 7), fan 0 / 1
 * (TEMPERATURE 5), nothing else. Not here on purpose: the laser currents (GmFactoryManager 128, banned on the HB) and
 * the ambient light (ProjectorFocusManager 50, banned on the Z9X / HB; decision pending), the ToF distance and
 * the supply voltage (not verified on the X50).
 *
 * Guard (as HbGmpf): before the first call on an interface getInterfaceVersion must be 1 and getInterfaceHash the
 * X50 hash; a mismatch closes that interface for the process (so an HB or a later X50 firmware with a changed
 * interface gets nothing). checkService only (never the blocking getService); calls on the main thread are refused.
 *
 * Who may call: the vendor sepolicy lets only system_app reach gmpf, but the stock X50 runs SELinux permissive
 * (androidboot.selinux=permissive) and the vendor service does not look at the caller's uid (no calling-uid API among
 * the imports of xgimi.hardware.gmpf-service and its gmpf libraries), so a normally signed app is expected to get
 * answers there too; unverified until a device test (README).
 */
public final class X50Gmpf {
    private static final String TAG = "LumenSensors";
    static final String HASH = "715affd032170b4d256c28c9bbc3c6cbaf5de492";
    static final int VERSION = 1;
    /** At most this many reply words are copied (the largest reply, DISPLAY 57, has 13). */
    private static final int MAX_WORDS = 64;

    public enum Iface {
        TEMPERATURE("xgimi.hardware.gmpf.ITemperatureManager"),
        DISPLAY("xgimi.hardware.gmpf.IDisplayManager"),
        TV("xgimi.hardware.gmpf.IGmTvManager"),
        MOTION("xgimi.hardware.gmpf.IMotionDetectionManager"),
        FOCUS("xgimi.hardware.gmpf.IProjectorFocusManager");

        final String descriptor, instance;
        /** The service name of the code table (descriptor without the package and the leading I). */
        public final String service;
        IBinder binder;
        boolean verified, refused;

        Iface(String d) {
            descriptor = d;
            instance = d + "/default";
            service = d.substring(d.lastIndexOf('.') + 2);
        }
    }

    /** Every transaction this app can produce on the X50 (= research/allowed_lumensensors.tsv). */
    public enum Call {
        GET_INTERFACE_VERSION(null, 0x00FFFFFF, "getInterfaceVersion"),
        GET_INTERFACE_HASH(null, 0x00FFFFFE, "getInterfaceHash"),
        GET_SCREEN_ON_OFF(Iface.DISPLAY, 28, "getScreenOnOff"),
        GET_SYSTEM_TEMPERATURE(Iface.TEMPERATURE, 6, "getSystemTemperature"),
        GET_TEMPERATURE_FROM_SENSOR(Iface.TEMPERATURE, 7, "getTemperatureFromSensor"),
        IS_TEMP_ULTRA_HIGH(Iface.TEMPERATURE, 12, "isTempUltraHigh"),
        GET_FAN_MODE(Iface.TEMPERATURE, 3, "getFanMode"),
        GET_FAN_ROTATION_RATE(Iface.TEMPERATURE, 5, "getFanRotationRate"),
        GET_DLP_LUMENS_LEVEL(Iface.DISPLAY, 7, "getDlpLumensLevel"),
        GET_DLP_LUMENS_MODE(Iface.DISPLAY, 5, "getDlpLumensMode"),
        GET_LENS_DISP_MODE(Iface.DISPLAY, 152, "getLensDispMode"),
        GET_DISP_LUMENS_LEVEL(Iface.DISPLAY, 178, "getDispLumensLevel"),
        GET_LIGHT_USAGE_INFO(Iface.DISPLAY, 176, "getLightUsageInfo"),
        GET_OUTPUT_TIMING_INFO(Iface.DISPLAY, 57, "getOutputTimingInfo"),
        GET_DISP_OUTPUT_FPS(Iface.DISPLAY, 179, "getDispOutputFps"),
        GET_CURRENT_INPUT_SOURCE(Iface.TV, 4, "getCurrentInputSource"),
        GET_HDR_TYPE(Iface.TV, 78, "getHdrType"),
        GET_HDMI_INFO(Iface.TV, 38, "getHdmiInfo"),
        GET_VIDEO_INFO(Iface.TV, 122, "getVideoInfo"),
        GET_MOTION_DETECTION(Iface.MOTION, 2, "getMotionDetection"),
        GET_FOCUS_MOTOR_CURRENT_STEP(Iface.FOCUS, 59, "getFocusMotorCurrentStep");

        public final Iface iface;
        public final int code;
        public final String method;

        Call(Iface iface, int code, String method) {
            this.iface = iface;
            this.code = code;
            this.method = method;
        }

        /** Service name of the code table ("TemperatureManager"), or "-" for a meta call. */
        public String service() { return iface == null ? "-" : iface.service; }
    }

    private interface Args { void write(Parcel p); }
    private interface Ret<T> { T read(Parcel p); }

    private final int[] words = new int[MAX_WORDS];

    /** The display service is registered and reachable from this process (no transact). Never throws. */
    public boolean connect() {
        try {
            return ServiceManager.checkService(Iface.DISPLAY.instance) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Drops every binder (the next call looks the service up again; the guard is kept). */
    public void disconnect() {
        synchronized (X50Gmpf.class) {
            for (Iface i : Iface.values()) {
                i.binder = null;
                i.verified = false;
            }
        }
    }

    // ---------------------------------------------------------------- getters (sampler thread only)
    /** DISPLAY 28: light / screen on (the gate of every other read). */
    public boolean screenOn() throws RemoteException { return call(Call.GET_SCREEN_ON_OFF, null, Parcel::readBoolean); }

    /** TEMPERATURE 6: byte, degrees C. */
    public int systemTemperature() throws RemoteException {
        return call(Call.GET_SYSTEM_TEMPERATURE, null, p -> p.readByte() & 0xff);
    }

    /** TEMPERATURE 7 (byte sensor): signed byte, degrees C; 0 = no such sensor. Index 1..12 only. */
    public int sensorTemperature(int index) throws RemoteException {
        if (index < 1 || index > 12) throw new IllegalArgumentException("sensor " + index);
        return call(Call.GET_TEMPERATURE_FROM_SENSOR, p -> p.writeByte((byte) index), Parcel::readByte);
    }

    /** TEMPERATURE 12: over-temperature flag. */
    public boolean overTemperature() throws RemoteException { return call(Call.IS_TEMP_ULTRA_HIGH, null, Parcel::readBoolean); }

    /** TEMPERATURE 3: fan mode byte (X50Structs.fanModeKnown). */
    public int fanMode() throws RemoteException { return call(Call.GET_FAN_MODE, null, p -> p.readByte() & 0xff); }

    /** TEMPERATURE 5 (byte fan): commanded PWM speed (not RPM). Fan 0 or 1 only (tempctrl: two fans). */
    public int fanSpeed(int fan) throws RemoteException {
        if (fan != 0 && fan != 1) throw new IllegalArgumentException("fan " + fan);
        return call(Call.GET_FAN_ROTATION_RATE, p -> p.writeByte((byte) fan), Parcel::readInt);
    }

    /** DISPLAY 7: brightness level 1..10. */
    public int lampLevel() throws RemoteException { return call(Call.GET_DLP_LUMENS_LEVEL, null, p -> p.readByte() & 0xff); }

    /** DISPLAY 5: light mode byte (24 on the X50 at the survey, meaning unknown). */
    public int lumensMode() throws RemoteException { return call(Call.GET_DLP_LUMENS_MODE, null, p -> p.readByte() & 0xff); }

    /** DISPLAY 152: 1 = Boost. */
    public int lensDispMode() throws RemoteException { return call(Call.GET_LENS_DISP_MODE, null, Parcel::readInt); }

    /** DISPLAY 178: light power 0..1000. */
    public int lightPower() throws RemoteException { return call(Call.GET_DISP_LUMENS_LEVEL, null, Parcel::readInt); }

    /** DISPLAY 176: JSON {current_light, error_code, light_list:[{light_id, usage_time}]} (X50Structs.lightUsage). */
    public String lightUsageJson() throws RemoteException { return call(Call.GET_LIGHT_USAGE_INFO, null, Parcel::readString); }

    /** DISPLAY 57 into out (X50Structs.outputTiming): false when the vendor said no or the struct did not decode. */
    public boolean outputTiming(int[] out) throws RemoteException {
        return call(Call.GET_OUTPUT_TIMING_INFO, null, p -> X50Structs.outputTiming(words, copy(p), out));
    }

    /** DISPLAY 179: output frames per second. */
    public int outputFps() throws RemoteException { return call(Call.GET_DISP_OUTPUT_FPS, null, Parcel::readInt); }

    /** TV 4: 0 media, 1 HDMI 1, 2 HDMI 2 (as on the Z9X / HB; the X50 answered 2 on HDMI 2). */
    public int inputSource() throws RemoteException { return call(Call.GET_CURRENT_INPUT_SOURCE, null, Parcel::readInt); }

    /** TV 78: 0 SDR, 1 HDR10, 2 Dolby Vision, 4 HLG, 5 HDR10+, 6 HDR Vivid. */
    public int hdrType() throws RemoteException { return call(Call.GET_HDR_TYPE, null, Parcel::readInt); }

    /** TV 38 into out (X50Structs.hdmiInfo). */
    public boolean hdmiInfo(int[] out) throws RemoteException {
        return call(Call.GET_HDMI_INFO, null, p -> X50Structs.hdmiInfo(words, copy(p), out));
    }

    /** TV 122 into out (X50Structs.videoInfo). */
    public boolean videoInfo(int[] out) throws RemoteException {
        return call(Call.GET_VIDEO_INFO, null, p -> X50Structs.videoInfo(words, copy(p), out));
    }

    /** MOTION 2 into out (X50Structs.motion): accData[3], angData[3], msensorType. */
    public boolean motion(int[] out) throws RemoteException {
        return call(Call.GET_MOTION_DETECTION, null, p -> X50Structs.motion(words, copy(p), out));
    }

    /** FOCUS 59: focus motor step. */
    public int focusMotorStep() throws RemoteException { return call(Call.GET_FOCUS_MOTOR_CURRENT_STEP, null, Parcel::readInt); }

    /** The reply words after readException into {@link #words}; returns how many. */
    private int copy(Parcel p) {
        int n = Math.min(MAX_WORDS, Math.max(0, p.dataAvail() / 4));
        for (int i = 0; i < n; i++) words[i] = p.readInt();
        return n;
    }

    // ---------------------------------------------------------------- transport
    private <T> T call(Call c, Args a, Ret<T> r) throws RemoteException {
        if (Looper.getMainLooper().isCurrentThread()) throw new RemoteException("vendor call on the main thread refused");
        IBinder b = need(c.iface);
        return send(b, c.iface, c, a, r);
    }

    private <T> T meta(Iface i, Call c, Ret<T> r) throws RemoteException {
        return send(i.binder, i, c, null, r);
    }

    private IBinder need(Iface i) throws RemoteException {
        synchronized (X50Gmpf.class) {
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
                    v = meta(i, Call.GET_INTERFACE_VERSION, Parcel::readInt);
                    h = meta(i, Call.GET_INTERFACE_HASH, Parcel::readString);
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

    private <T> T send(IBinder b, Iface i, Call c, Args a, Ret<T> r) throws RemoteException {
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
        synchronized (X50Gmpf.class) {
            if (i.binder == b) {
                i.binder = null;
                i.verified = false;
            }
        }
    }
}
