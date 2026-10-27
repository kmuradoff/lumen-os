package org.lumen.sensors.hal;

import android.os.HwBlob;
import android.os.HwParcel;
import android.os.RemoteException;

/**
 * Read-only typed client of the XGIMI Z9X vendor HAL for Lumen Sensors:
 *   xgimi.hardware.gmpf@1.0::IGmpf/default and ::IGmpf2/default (vendor service gmpfHw),
 * over the copied {@link HidlCaller} (two-way transact, Status first, refused on the main thread),
 * with the same descriptors, instance and wire types as Projector's GmpfClient / Gmpf2Client.
 * Z9X ONLY: the IGmpf2 numbers here are the Z9X table (the Play 6 differs); the device profile
 * creates this reader only on the Z9X.
 *
 * Safety:
 *  - every transact goes through {@link #tx}, which calls {@link GmpfAllowlist#check} first: a code that
 *    is not a reviewed read of that interface is refused before anything is sent (SecurityException);
 *  - there is no generic call, no setter, no listener, no session: each public method is one read;
 *  - the struct replies are read with readBuffer(exact size) and decoded by {@link GmpfStructs}, so a
 *    wrong size fails only this call;
 *  - the connection is lazy and dropped on a transact failure (no death recipient), so the next call
 *    reconnects. Sampler thread only (one call at a time; the scratch buffers are not shared).
 */
public final class Z9xGmpfReader {
    public static final String IGMPF = "xgimi.hardware.gmpf@1.0::IGmpf";
    public static final String IGMPF2 = "xgimi.hardware.gmpf@1.0::IGmpf2";

    private static final class Iface extends HidlCaller {
        final int which;

        Iface(String descriptor, int which) {
            super(descriptor);
            this.which = which;
        }

        @Override
        boolean onTransactFailed() { return true; }
    }

    private final Iface gmpf = new Iface(IGMPF, GmpfAllowlist.IGMPF);
    private final Iface gmpf2 = new Iface(IGMPF2, GmpfAllowlist.IGMPF2);
    private final byte[] scratch = new byte[16];

    /** Looks up IGmpf (no transact). True when the vendor service answers getService. */
    public boolean connect() {
        return gmpf.ensureConnected();
    }

    public void disconnect() {
        gmpf.dropConnection();
        gmpf2.dropConnection();
    }

    /** The only transact path of this app. */
    private <T> T tx(Iface i, int code, HwParcel q, HidlCaller.Ret<T> ret) throws RemoteException {
        GmpfAllowlist.check(i.which, code);
        if (!i.ensureConnected()) throw new RemoteException("XGIMI HAL not available");
        return i.call(code, q, ret);
    }

    private HwParcel withU8(Iface i, int v) {
        HwParcel q = i.request();
        q.writeInt8((byte) v);
        return q;
    }

    // ================================================================== IGmpf
    /** 438 getSystemTemperature() -> u8 degrees C. */
    public int systemTemperature() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.SYSTEM_TEMPERATURE, gmpf.request(), HidlCaller.RET_U8);
    }

    /** 439 getTemperatureFromSensor(u8 index) -> float degrees C (0 = no such sensor). */
    public float sensorTemperature(int index) throws RemoteException {
        HidlCaller.checkRange("sensor index", index, 0, 15);
        return tx(gmpf, GmpfAllowlist.SENSOR_TEMPERATURE, withU8(gmpf, index), HwParcel::readFloat);
    }

    /** 339 getTemperatureFromSensorStatus(i8 index) -> bool (meaning unverified: true = valid). */
    public boolean sensorStatus(int index) throws RemoteException {
        HidlCaller.checkRange("sensor index", index, 0, 15);
        return tx(gmpf, GmpfAllowlist.SENSOR_STATUS, withU8(gmpf, index), HidlCaller.RET_BOOL);
    }

    /** 435 getFanMode() -> u8: 0..9 manual 1..10, 10 auto, 11 test. */
    public int fanMode() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.FAN_MODE, gmpf.request(), HidlCaller.RET_U8);
    }

    /** 437 getFanRotationRate(u8 fan) -> u16 commanded PWM speed (not RPM); 0 = no such fan. */
    public int fanSpeed(int fan) throws RemoteException {
        HidlCaller.checkRange("fan", fan, 0, 7);
        return tx(gmpf, GmpfAllowlist.FAN_SPEED, withU8(gmpf, fan), r -> r.readInt16() & 0xffff);
    }

    /** 177 getDlpLumensLevel() -> 1..10. */
    public int lampLevel() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.LAMP_LEVEL, gmpf.request(), HidlCaller.RET_U8);
    }

    /** 175 getDlpLumensMode() -> 16 D65 / 17 colour temperature 1. */
    public int lumensMode() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.LUMENS_MODE, gmpf.request(), HidlCaller.RET_U8);
    }

    /** 196 getScreenOnOff() -> light / screen on. */
    public boolean screenOn() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.SCREEN_ON, gmpf.request(), HidlCaller.RET_BOOL);
    }

    /** 279 getTofDistance() -> mm. Not cached: the vendor measures for about 0.6 s (Z9xSchedule.TOF_MS). */
    public int tofDistance() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.TOF_DISTANCE, gmpf.request(), HidlCaller.RET_I32);
    }

    /** 584 getMotionDetection() -> buffer 14 decoded into out (GmpfStructs.motion). */
    public boolean motion(int[] out) throws RemoteException {
        return tx(gmpf, GmpfAllowlist.MOTION, gmpf.request(),
                r -> GmpfStructs.motion(copy(r.readBuffer(GmpfStructs.MOTION_SIZE), GmpfStructs.MOTION_SIZE),
                        GmpfStructs.MOTION_SIZE, out));
    }

    /** 266 getFocusMotorCurrentStep() -> motor steps. */
    public int focusMotorStep() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.FOCUS_STEP, gmpf.request(), HidlCaller.RET_I32);
    }

    /** 696 getCurrentInputSource() -> 0 media, 1 HDMI 1, 2 HDMI 2. */
    public int inputSource() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.INPUT_SOURCE, gmpf.request(), HidlCaller.RET_I32);
    }

    /** 620 getHdrType() -> 0 SDR, 1 HDR10, 2 Dolby Vision, 4 HLG, 5 HDR10+, 6 HDR Vivid. */
    public int hdrType() throws RemoteException {
        return tx(gmpf, GmpfAllowlist.HDR_TYPE, gmpf.request(), HidlCaller.RET_I32);
    }

    /** 662 getHdmiInfo() -> buffer 16 decoded into out (GmpfStructs.hdmiInfo). No bool before the struct. */
    public boolean hdmiInfo(int[] out) throws RemoteException {
        return tx(gmpf, GmpfAllowlist.HDMI_INFO, gmpf.request(),
                r -> GmpfStructs.hdmiInfo(copy(r.readBuffer(GmpfStructs.HDMI_INFO_SIZE), GmpfStructs.HDMI_INFO_SIZE),
                        GmpfStructs.HDMI_INFO_SIZE, out));
    }

    // ================================================================== IGmpf2 (Z9X numbering)
    /** IGmpf2 51 getLensDispMode() -> 1 = Boost. */
    public int lensDispMode() throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.LENS_DISP_MODE, gmpf2.request(), HidlCaller.RET_I32);
    }

    /** IGmpf2 241 isTempUltraHigh() -> over-temperature flag. */
    public boolean overTemperature() throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.OVER_TEMPERATURE, gmpf2.request(), HidlCaller.RET_BOOL);
    }

    /** IGmpf2 252 getCurHwCurrent() -> bool + buffer 16 decoded into out (8 currents). False = the HAL said no. */
    public boolean ledCurrents(int[] out) throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.LED_CURRENTS, gmpf2.request(), r -> {
            if (!r.readBool()) return false;
            return GmpfStructs.ledCurrents(copy(r.readBuffer(GmpfStructs.LED_CURRENTS_SIZE), GmpfStructs.LED_CURRENTS_SIZE),
                    GmpfStructs.LED_CURRENTS_SIZE, out);
        });
    }

    /** IGmpf2 25 getDispLumensLevel() -> 0..1000. */
    public int ledLevel() throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.LED_LEVEL, gmpf2.request(), HidlCaller.RET_I32);
    }

    /** IGmpf2 27 getLightUsageInfo() -> JSON text {current_light, error_code, light_list:[{light_id, usage_time hours}]}. */
    public String lightUsageJson() throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.LIGHT_USAGE, gmpf2.request(), HwParcel::readString);
    }

    /** IGmpf2 68 getPowerSupplyVoltage() -> volts (0 on a mains supply). */
    public float supplyVoltage() throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.SUPPLY_VOLTAGE, gmpf2.request(), HwParcel::readFloat);
    }

    /** IGmpf2 319 getVideoInfo() -> bool + buffer 12 decoded into out (GmpfStructs.videoInfo). */
    public boolean videoInfo(int[] out) throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.VIDEO_INFO, gmpf2.request(), r -> {
            if (!r.readBool()) return false;
            return GmpfStructs.videoInfo(copy(r.readBuffer(GmpfStructs.VIDEO_INFO_SIZE), GmpfStructs.VIDEO_INFO_SIZE),
                    GmpfStructs.VIDEO_INFO_SIZE, out);
        });
    }

    /** IGmpf2 22 getDispOutputFps() -> frames per second (scale unverified). */
    public int outputFps() throws RemoteException {
        return tx(gmpf2, GmpfAllowlist.OUTPUT_FPS, gmpf2.request(), HidlCaller.RET_I32);
    }

    // ------------------------------------------------------------------ helpers
    private byte[] copy(HwBlob blob, int size) {
        blob.copyToInt8Array(0, scratch, size);
        return scratch;
    }
}
