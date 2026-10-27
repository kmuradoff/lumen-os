package org.lumen.sensors.hal;

/**
 * The ONLY XGIMI HAL codes Lumen Sensors may send, per interface (xgimi.hardware.gmpf@1.0 IGmpf and
 * IGmpf2, instance default, Z9X numbering). Every one is a read without side effects; Z9xGmpfReader
 * calls {@link #check} before every transact, so any other code is refused before anything is sent.
 * IGmpf and IGmpf2 numbers overlap with different meanings (IGmpf 252 is a factory check, IGmpf2 252
 * the LED currents; IGmpf 51 a PEQ setter, IGmpf2 51 the Boost getter), so the lists are per interface.
 * Plain Java: the host tests check the lists and the refusals.
 *
 * Reads (code, reply as decoded by the research from the Z9X proxy library; "unverified" = wire
 * verified, value or field order not yet seen on the device):
 *   IGmpf  438 getSystemTemperature        u8 degrees C (= sensor slot 0)               verified in Projector
 *   IGmpf  439 getTemperatureFromSensor    (u8 index) float, whole degrees, 0 = absent  unverified
 *   IGmpf  339 getTemperatureFromSensorStatus (i8 index) bool                           unverified meaning
 *   IGmpf  435 getFanMode                  u8 0..9 manual, 10 auto, 11 test             unverified
 *   IGmpf  437 getFanRotationRate          (u8 fan) u16 commanded PWM speed, 0 = none   unverified
 *   IGmpf  177 getDlpLumensLevel           u8 1..10                                     verified in Projector
 *   IGmpf  175 getDlpLumensMode            u8 16 D65 / 17 colour temp 1                 verified in Projector
 *   IGmpf  196 getScreenOnOff              bool, read before every HAL round            verified in Projector
 *   IGmpf  279 getTofDistance              i32 mm, an active 0.6 s measurement          unverified
 *   IGmpf  584 getMotionDetection          buffer 14: i16 acc[3], i16 gyro[3], u8 type  unverified order
 *   IGmpf  266 getFocusMotorCurrentStep    i32 steps                                    unverified
 *   IGmpf  696 getCurrentInputSource       i32 0 media / 1 HDMI 1 / 2 HDMI 2            verified in Projector
 *   IGmpf  620 getHdrType                  i32 (GmpfClient HDR_* values)                verified in Projector
 *   IGmpf  662 getHdmiInfo                 buffer 16 (GmpfClient.HdmiSignal layout)     verified
 *   IGmpf  157 getOutputTimingInfo         bool + buffer 40: 7 x i32 + 12-byte struct   size from the proxy
 *   IGmpf2  51 getLensDispMode             i32, 1 = Boost                               verified in Projector
 *   IGmpf2 241 isTempUltraHigh             bool over-temperature                        unverified
 *   IGmpf2 252 getCurHwCurrent             bool + buffer 16: 8 x i16 R G B C R2 G2 B2 C2 unverified order
 *   IGmpf2  25 getDispLumensLevel          i32 0..1000                                  unverified
 *   IGmpf2  27 getLightUsageInfo           hidl_string JSON, usage_time hours (once)    unit to confirm
 *   IGmpf2  68 getPowerSupplyVoltage       float volts                                  unverified
 *   IGmpf2 319 getVideoInfo                bool + buffer 12: u16 w, h, fps x100, hdmi, i32 type  verified w, h, fps
 *   IGmpf2  22 getDispOutputFps            i32 fps                                      unverified scale
 *
 * No longer read (device test 2026-10-27): IGmpf 592 getSignalStatus, an i32 raw vendor enum with no known
 * meaning (it answered 3 on HDMI 1 at 3840x2160p 59.9 Hz); not in the reads, so refused like any other code.
 *
 * BANNED, never called (also listed in the host test; none may ever be added to the reads):
 *   298 connectProjectorFocusManager, 306 setProjectorFocusListener: ONE focus listener exists in the
 *       vendor and Projector's auto keystone / autofocus depend on it; a second app would replace it.
 *   431 extendCmd: a generic command channel with test commands (fan_err_test, fake temperatures).
 *   432 connectTemperatureManager, 440 setTemperatureListener, 589 setMotionDetectionListener:
 *       listeners; the vendor waits for the reply while it holds its listener mutex.
 *   121 zoomStart: the vendor takes the motor position when a zoom session opens.
 *   263 setMotionACalEnable (IGmpf2): motion calibration writer (stock roll / pitch needs it; tilt is
 *       computed here from 584 instead).
 *   275 getAmbientLight: no sensor on the Z9X, and the vendor path may run the camera.
 *   Every setter (fan 430 / 434 / 436 / 531, lamp 174 / 176, screen 195, motors, ToF power 269 / 270,
 *   cameras 293..295 / 323 / 325, factory checks 252 / 255 / 441 of IGmpf, motion 578, AF 319..321 of
 *   IGmpf, Boost 52 of IGmpf2, ...): not in the reads, so refused.
 */
public final class GmpfAllowlist {
    private GmpfAllowlist() {}

    public static final int IGMPF = 1, IGMPF2 = 2;

    // ------------------------------------------------------------------ IGmpf reads
    public static final int SENSOR_STATUS = 339;
    public static final int LUMENS_MODE = 175;
    public static final int LAMP_LEVEL = 177;
    public static final int SCREEN_ON = 196;
    public static final int FOCUS_STEP = 266;
    public static final int TOF_DISTANCE = 279;
    public static final int FAN_MODE = 435;
    public static final int FAN_SPEED = 437;
    public static final int SYSTEM_TEMPERATURE = 438;
    public static final int SENSOR_TEMPERATURE = 439;
    public static final int MOTION = 584;
    public static final int HDR_TYPE = 620;
    public static final int HDMI_INFO = 662;
    public static final int INPUT_SOURCE = 696;
    public static final int OUTPUT_TIMING = 157;

    // ------------------------------------------------------------------ IGmpf2 reads (Z9X numbering)
    public static final int OUTPUT_FPS = 22;
    public static final int LED_LEVEL = 25;
    public static final int LIGHT_USAGE = 27;
    public static final int LENS_DISP_MODE = 51;
    public static final int SUPPLY_VOLTAGE = 68;
    public static final int OVER_TEMPERATURE = 241;
    public static final int LED_CURRENTS = 252;
    public static final int VIDEO_INFO = 319;

    private static final int[] IGMPF_READS = {
            SENSOR_STATUS, LUMENS_MODE, LAMP_LEVEL, SCREEN_ON, FOCUS_STEP, TOF_DISTANCE, FAN_MODE, FAN_SPEED,
            SYSTEM_TEMPERATURE, SENSOR_TEMPERATURE, MOTION, HDR_TYPE, HDMI_INFO, INPUT_SOURCE, OUTPUT_TIMING};
    private static final int[] IGMPF2_READS = {
            OUTPUT_FPS, LED_LEVEL, LIGHT_USAGE, LENS_DISP_MODE, SUPPLY_VOLTAGE, OVER_TEMPERATURE, LED_CURRENTS,
            VIDEO_INFO};

    /** Never sent on IGmpf (see the class comment). */
    public static final int[] BANNED_IGMPF = {
            298, 306, 431, 432, 440, 589, 121, 263, 275,
            430, 434, 436, 531, 174, 176, 195, 148, 152, 161, 300, 301, 307, 118, 119, 120, 153, 150, 185,
            269, 270, 293, 294, 295, 323, 325, 252, 255, 441, 578, 319, 320, 321};
    /** Never sent on IGmpf2 (the listener / session / writer codes are banned on both interfaces). */
    public static final int[] BANNED_IGMPF2 = {
            263, 262, 52, 76, 298, 306, 431, 432, 440, 589, 121, 275};

    public static boolean allowed(int iface, int code) {
        int[] reads = iface == IGMPF ? IGMPF_READS : (iface == IGMPF2 ? IGMPF2_READS : null);
        if (reads == null) return false;
        for (int r : reads) if (r == code) return true;
        return false;
    }

    /** Throws (nothing sent) unless {@code code} is a reviewed read of {@code iface}. */
    public static void check(int iface, int code) {
        if (!allowed(iface, code)) {
            throw new SecurityException("XGIMI HAL " + (iface == IGMPF2 ? "IGmpf2 " : "IGmpf ") + code
                    + " refused: not a reviewed read of Lumen Sensors");
        }
    }

    /** A copy of the reads of one interface (tests, README). */
    public static int[] reads(int iface) {
        return (iface == IGMPF ? IGMPF_READS : IGMPF2_READS).clone();
    }
}
