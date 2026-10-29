/*
 * Play6Gmpf2: the typed IGmpf2 client of the XGIMI Play 6 (xgimi.hardware.gmpf@1.0::IGmpf2/default).
 * IGmpf2 is a separate interface with its own code table, and its numbers are NOT the Z9X ones: the
 * Play 6 table is the Z9X one with methods removed, so every number moved (research/play6/gmpf_diff.txt,
 * C 2.3: Z9X 18 setKstPrepare is Play 6 setEnergyConMode, Z9X 61 correctKeystone is Play 6
 * setTiltShiftMove). Every code below is the Play 6 v1.7.116 number of research/play6/gmpf_codes_play6.tsv,
 * checked by name (`// tsv:<method>`, check H: tools/play6/check_play6_hal.py); never copy a number
 * from Z9xProjector Gmpf2Client. Wire types: research/play6/z9x_gmpf_codes.txt under the Z9X number
 * (same argument lists, C 2.2), quoted as "W=... R=..." with the Z9X number.
 *
 * Identity guard (docs/play6/SETTINGS_PLAN.md 2.1, D2): before the first call of a connection this
 * client sends IBase getHashChain (0x0f485348, interface token android.hidl.base@1.0::IBase) to
 * IGmpf2/default and compares element 0 with the hashes recorded offline from two stock vendor libraries
 * (research/play6/hidl_hashes.txt): {@link #RECORDED_HASH} (v1.7.116) and {@link #RECORDED_HASH_V1_1_67}
 * (v1.1.67). Equal to one: the guard opens for this connection with that vendor's code table. Any other
 * hash, or an unreadable chain (a delivered call with a bad reply): every method throws before sending,
 * one log line. A call that was not delivered drops the binder and leaves the guard unknown, so the next
 * call reconnects and checks again. There is no property or debug switch that opens it.
 *
 * Code tables (docs/play6/research/vendor_v1167.md): every code constant below is the v1.7.116 number
 * and every IGmpf2 send goes out as c(CONSTANT). On v1.7.116 c() returns the constant; on v1.1.67 it
 * returns the v1.1.67 number from its case list. v1.7.116 inserted five methods into IGmpf2
 * (getPowerSupplyVoltage at 46, GetMIoTKey / SetMIoTKey / GetMIoTDeviceID / SetMIoTDeviceID at 172..175),
 * so v1.1.67 has the same 230 other methods with the same arguments and wire layout, 47..171 one lower and
 * 176..235 five lower (tools/play6/gmpf_layout.py, research/play6/vendor_v1167/). A method missing on
 * v1.1.67 throws AbsentException before anything is sent. Check H rule 8 proves each case by name.
 *
 * Connection: lazy getService on first use (no death recipient); a failed transact drops the binder
 * (and the guard state) so the next call reconnects. The Hal facade also drops it when IGmpf dies
 * (same vendor process gmpfHw). Same wire rules and safety as Play6Gmpf (HidlCaller): two-way, Status
 * first, refused on the main thread, no generic transact, fixed or checked arguments only. Only methods
 * with a reviewed caller outside hal/ are compiled (check H rule 6).
 */
package org.lumen.projector.hal;

import android.os.HwBlob;
import android.os.HwParcel;
import android.os.RemoteException;
import android.util.Log;

public final class Play6Gmpf2 extends HidlCaller {
    public static final String IGMPF2 = "xgimi.hardware.gmpf@1.0::IGmpf2";
    static final String IBASE = "android.hidl.base@1.0::IBase";
    /** IGmpf2 hash (chain element 0) recorded offline from the stock v1.7.116 library (plan 2.1). */
    public static final String RECORDED_HASH = "6816d9efd3c7da9c522bfa05b090f7a0795081319ee20396d1343c81a6380157";
    /**
     * IGmpf2 hash recorded offline from the stock v1.1.67 vendor library (research/play6/hidl_hashes.txt,
     * docs/play6/research/vendor_v1167.md); the live value on v1.1.67 (logs/<device>_<serial>/t17_on_1167).
     */
    public static final String RECORDED_HASH_V1_1_67 = "fa2afea7cb29be93bba20d99a3d60adadbaebe32a9cfa14964b074c781910b37";
    /** IBase hash: element 1 of every chain (plan 2.1). */
    public static final String IBASE_HASH = "ec7fd79ed02dfa85bc499426adae3ebe23ef0524f3cd6957139324b83b18ca4c";

    // ------------------------------------------- IBase and IGmpf2 codes (private, Play 6 numbers)
    private static final int IBASE_GET_HASH_CHAIN = 0x0f485348;      // ibase:getHashChain W= R=vec<u8[32]>
    private static final int GET_GAME_MODE_PROP = 33;                // tsv:getGameModeProp Z9X 55 W= R=cb(bool, 12 bytes)
    private static final int SET_GAME_MODE_TYPE = 34;                // tsv:setGameModeType Z9X 56 W=i32 R=bool
    private static final int SET_GAME_MODE_STATE = 35;               // tsv:setGameModeState Z9X 57 W=i32 R=bool
    private static final int GET_POWER_SUPPLY_VOLTAGE = 46;          // tsv:getPowerSupplyVoltage Z9X 68 W= R=float
    private static final int GET_AUTO_ECO_MODE = 47;                 // tsv:getAutoEcoMode Z9X 69 W= R=bool
    private static final int GET_COPOWER_BATTERY_LEVEL = 51;         // tsv:getCopowerBatteryLevel Z9X 73 W= R=i8
    private static final int GET_COPOWER_DEVICE_TYPE = 52;           // tsv:getCopowerDeviceType Z9X 74 W= R=i32
    private static final int GET_POWER_ON_AF_USE_DISTANCE = 63;      // tsv:getPowerOnAFUseDistance Z9X 90 W= R=bool
    private static final int SET_POWER_ON_AF_USE_DISTANCE = 64;      // tsv:setPowerOnAFUseDistance Z9X 91 W=bool R=bool
    private static final int SET_SLIDE_FUNC = 69;                    // tsv:setSlideFunc Z9X 138 W=i8 R=bool
    private static final int GET_SLIDE_FUNC = 70;                    // tsv:getSlideFunc Z9X 139 W= R=i8
    private static final int GET_IS_AUTO_ATMOSPHERE_LIGHT = 71;      // tsv:getIsAutoAtmoSphereLight Z9X 140 W= R=bool
    private static final int GET_CONVEX_LENS_TYPE = 75;              // tsv:getConvexLensType Z9X 144 W= R=i32
    private static final int GET_STAND_VERTICAL_ANGLE = 81;          // tsv:getStandVerticalAngle Z9X 150 W= R=float
    private static final int IS_CONVEX_LENS_IN = 84;                 // tsv:isConvexLensIn Z9X 153 W= R=bool
    private static final int SET_PUSH_PULL_SCREEN_STATE = 125;       // tsv:setPushPullScreenState Z9X 194 W=i32 R=bool
    private static final int GET_SCREEN_ROTATION_INFO = 130;         // tsv:getScreenRotationInfo Z9X 199 W= R=cb(bool, 16 bytes)
    private static final int SET_SCREEN_ROTATION = 138;              // tsv:setScreenRotation Z9X 207 W=i32,float R=bool
    private static final int GET_ALLM_STATUS = 212;                  // tsv:getAllmStatus Z9X 310 W=i32 R=bool
    private static final int GET_ALLM_ENABLE_STATE = 213;            // tsv:getAllmEnableState Z9X 311 W= R=bool
    private static final int GET_TV_GAMMA_LEVEL = 228;               // tsv:getTvGammaLevel Z9X 326 W=i32 R=i32
    private static final int SET_TV_GAMMA_LEVEL = 229;               // tsv:setTvGammaLevel Z9X 327 W=i32,i32 R=bool
    private static final int GET_TV_DYNAMIC_CONTRAST = 230;          // tsv:getTvDynamicContrastEnable Z9X 328 W=i32 R=bool
    private static final int SET_TV_DYNAMIC_CONTRAST = 231;          // tsv:setTvDynamicContrastEnable Z9X 329 W=i32,bool R=bool

    private static final int WINDOW_MAIN = 0;                 // value picture calls address window 0
    private static final int HDMI_SOURCE_1 = 1;               // value EN_INPUT_SOURCE_HDMI1 (the one Play 6 port)
    private static final int GAME_STATE_ON = 0;               // value setGameModeState: 0 on
    private static final int GAME_STATE_OFF = 1;              // value setGameModeState: 1 off
    private static final int ROTATE_CLOCKWISE = 5;            // value setScreenRotation: stock RotateWind RIGHT
    private static final int ROTATE_COUNTER_CLOCKWISE = 6;    // value setScreenRotation: stock RotateWind LEFT
    private static final int ROTATION_INFO_SIZE = 16;         // value ScreenRotationInfo_st: 4 x float
    /** setSlideFunc: 0 SLIDE_FUNC_POWER (closing shuts down), 1 SLIDE_FUNC_SOUND_ATMOSPHERE (ambient speaker). */
    public static final int SLIDE_POWER = 0;                  // value
    public static final int SLIDE_AMBIENT_SPEAKER = 1;        // value

    /** Guard state of the current connection. */
    private enum Identity { UNKNOWN, OPEN, CLOSED }

    /** The vendor code tables the guard can open with (one per recorded hash). */
    private enum Table {
        V1_7_116("v1.7.116"),
        V1_1_67("v1.1.67");

        final String label;

        Table(String label) { this.label = label; }
    }

    private volatile Identity identity = Identity.UNKNOWN;
    /** Code table of the hash that opened the guard; set together with OPEN in need(). */
    private volatile Table table;
    /** Element 0 of the last chain read (hex), or the reason it could not be read. */
    private volatile String lastLiveHash;

    public Play6Gmpf2() {
        super(IGMPF2);
    }

    @Override boolean onTransactFailed() { return true; }

    /** Forgets the binder and the guard state: the next call connects and checks the identity again. */
    @Override
    public synchronized void dropConnection() {
        identity = Identity.UNKNOWN;
        super.dropConnection();
    }

    /** Thrown before anything is sent while the guard is closed for this connection. */
    public static final class IdentityException extends RemoteException {
        IdentityException(String msg) { super(msg); }
    }

    /** Thrown before anything is sent: the method does not exist in the IGmpf2 of this vendor build. */
    public static final class AbsentException extends RemoteException {
        AbsentException(String msg) { super(msg); }
    }

    // ------------------------------------------------------------------ identity guard
    /**
     * Connects lazily and opens the guard for this connection, or throws (nothing else was sent):
     * IdentityException when the live hash differs or its reply was bad, RemoteException when IGmpf2
     * is not available or the IBase call was not delivered (then the next call checks again).
     */
    private synchronized void need() throws RemoteException {
        if (!ensureConnected()) throw new RemoteException(IGMPF2 + " not available");
        if (identity == Identity.OPEN) return;
        if (identity == Identity.CLOSED) throw new IdentityException("IGmpf2 identity guard closed (live " + lastLiveHash + ")");
        String[] chain;
        try {
            chain = readHashChain();
        } catch (ReplyException e) {
            lastLiveHash = "unreadable";
            identity = Identity.CLOSED;
            Log.e(TAG, "IGmpf2 identity guard CLOSED: hash chain unreadable (" + e.getMessage() + ")");
            throw new IdentityException("IGmpf2 hash chain unreadable");
        }
        lastLiveHash = chain.length > 0 ? chain[0] : "empty";
        Table t = chain.length > 0 ? tableFor(chain[0]) : null;
        if (t != null) {
            table = t;
            identity = Identity.OPEN;
            Log.i(TAG, "IGmpf2 identity guard open (hash " + chain[0].substring(0, 8) + " = recorded " + t.label + ")");
            return;
        }
        identity = Identity.CLOSED;
        Log.e(TAG, "IGmpf2 identity guard CLOSED: live " + lastLiveHash + ", recorded " + RECORDED_HASH
                + " (v1.7.116) and " + RECORDED_HASH_V1_1_67 + " (v1.1.67)");
        throw new IdentityException("IGmpf2 hash " + lastLiveHash + " is not a recorded one");
    }

    /** The code table of a live IGmpf2 hash, or null: only the vendor builds checked offline. */
    private static Table tableFor(String hash) {
        if (RECORDED_HASH.equals(hash)) return Table.V1_7_116;
        if (RECORDED_HASH_V1_1_67.equals(hash)) return Table.V1_1_67;
        return null;
    }

    /**
     * The number to send for an IGmpf2 code constant (a v1.7.116 number) on the vendor whose hash opened
     * the guard: v1.7.116 the constant itself, v1.1.67 the number of its case below (checked by name
     * against research/play6/vendor_v1167/gmpf_codes_play6.tsv, check H rule 8). No case: the method does
     * not exist on v1.1.67, AbsentException before anything is sent. Called right after need().
     */
    private int c(int code) throws RemoteException {
        Table t = table;
        if (identity != Identity.OPEN || t == null) throw new IdentityException("IGmpf2 identity guard not open");
        if (t == Table.V1_7_116) return code;
        switch (code) {
            case GET_GAME_MODE_PROP: return 33;
            case SET_GAME_MODE_TYPE: return 34;
            case SET_GAME_MODE_STATE: return 35;
            case GET_AUTO_ECO_MODE: return 46;
            case GET_COPOWER_BATTERY_LEVEL: return 50;
            case GET_COPOWER_DEVICE_TYPE: return 51;
            case GET_POWER_ON_AF_USE_DISTANCE: return 62;
            case SET_POWER_ON_AF_USE_DISTANCE: return 63;
            case SET_SLIDE_FUNC: return 68;
            case GET_SLIDE_FUNC: return 69;
            case GET_IS_AUTO_ATMOSPHERE_LIGHT: return 70;
            case GET_CONVEX_LENS_TYPE: return 74;
            case GET_STAND_VERTICAL_ANGLE: return 80;
            case IS_CONVEX_LENS_IN: return 83;
            case SET_PUSH_PULL_SCREEN_STATE: return 124;
            case GET_SCREEN_ROTATION_INFO: return 129;
            case SET_SCREEN_ROTATION: return 137;
            case GET_ALLM_STATUS: return 207;
            case GET_ALLM_ENABLE_STATE: return 208;
            case GET_TV_GAMMA_LEVEL: return 223;
            case SET_TV_GAMMA_LEVEL: return 224;
            case GET_TV_DYNAMIC_CONTRAST: return 225;
            case SET_TV_DYNAMIC_CONTRAST: return 226;
            default: throw new AbsentException("IGmpf2 " + code + " does not exist on vendor " + t.label);
        }
    }

    /**
     * The vendor build whose recorded IGmpf2 hash equals {@code hash} ("v1.7.116" / "v1.1.67"), or null.
     * For the diagnostics lines next to the live chain; the guard itself uses tableFor in need().
     */
    public static String recordedVendor(String hash) {
        Table t = tableFor(hash);
        return t == null ? null : t.label;
    }

    /**
     * Checks the identity of IGmpf2/default now (the Hal facade, once after the start gate and after a
     * reconnect). True = the guard is open; false = closed or not available (logged in need()).
     */
    public boolean verifyIdentity() {
        try {
            need();
            return true;
        } catch (Throwable t) {
            if (!(t instanceof IdentityException)) Log.w(TAG, "IGmpf2 identity not checked: " + t);
            return false;
        }
    }

    /** True while the guard is open for the current connection (no call). Any thread. */
    public boolean isIdentityOpen() {
        return identity == Identity.OPEN;
    }

    /**
     * The live IBase hash chain of IGmpf2/default as hex (element 0 IGmpf2, element 1 IBase), read again
     * for the diagnostics probe; it shows the live value next to {@link #RECORDED_HASH} also while the guard
     * is closed. The chain read is the guard's own IBase call and never opens the guard.
     */
    public String[] liveHashChain() throws RemoteException {
        if (!ensureConnected()) throw new RemoteException(IGMPF2 + " not available");
        return readHashChain();
    }

    /** IBase getHashChain(): reply Status + hidl_vec<uint8_t[32]> (16-byte header + embedded buffer). */
    private String[] readHashChain() throws RemoteException {
        HwParcel q = new HwParcel();
        q.writeInterfaceToken(IBASE);
        return call(IBASE_GET_HASH_CHAIN, q, r -> {
            HwBlob vec = r.readBuffer(16);
            int n = vec.getInt32(8);
            if (n < 1 || n > 8) throw new IllegalStateException("hash chain of " + n + " elements");
            HwBlob data = r.readEmbeddedBuffer(n * 32L, vec.handle(), 0, true);
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                byte[] h = new byte[32];
                data.copyToInt8Array(i * 32L, h, 32);
                out[i] = hex(h);
            }
            return out;
        });
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    // ---------------------------------------------------------------- focus, game, ALLM
    /** 63 getPowerOnAFUseDistance(): smart autofocus state (stock FocusRepository). */
    public boolean getSmartAutofocus() throws RemoteException { need(); return callRetBool(c(GET_POWER_ON_AF_USE_DISTANCE)); }

    /**
     * 33 getGameModeProp() -> {state, type, option}: GameModeProp_st {int32 state @0; int32 type @4; int32
     * gameModeOpt @8} (stock IDisplayType; confirmed by the vendor's own t14 log, research
     * hdmi_volume_game.md 3.1): state 0 open / 1 closed, type 0 manual / 1 auto, option the 647 latency
     * value. Wire as Z9X 55 (Status, readBool, then readBuffer(12)); a reply of another size fails only this
     * call (ReplyException).
     */
    public int[] getGameModeProp() throws RemoteException {
        need();
        return call(c(GET_GAME_MODE_PROP), request(), r -> {
            if (!r.readBool()) throw new IllegalStateException("33 returned false");
            HwBlob b = r.readBuffer(12);
            return new int[] {b.getInt32(0), b.getInt32(4), b.getInt32(8)};
        });
    }

    /** 213 getAllmEnableState(): switch to game mode on ALLM content (read by the diagnostics probe only). */
    public boolean getAllmAutoSwitch() throws RemoteException { need(); return callRetBool(c(GET_ALLM_ENABLE_STATE)); }

    // ------------------------------------------------- power board, stand, lens (read only)
    /** 52 getCopowerDeviceType() -> 0 adapter, 1 stand, 2 low power, 3 internal battery, 4 stand + adapter. */
    public int getPowerSourceType() throws RemoteException { need(); return callRetI32(c(GET_COPOWER_DEVICE_TYPE)); }

    /** 51 getCopowerBatteryLevel() -> charging stand battery percent (read only when 52 is 1 or 4). */
    public int getStandBatteryLevel() throws RemoteException { need(); return callRetI8(c(GET_COPOWER_BATTERY_LEVEL)); }

    /** 46 getPowerSupplyVoltage() -> float, unit UNVERIFIED (gmpf_main logs "TypeC Voltage: 19.5V"). */
    public float getPowerSupplyVoltage() throws RemoteException {
        need();
        return call(c(GET_POWER_SUPPLY_VOLTAGE), request(), r -> r.readFloat());
    }

    /** 47 getAutoEcoMode() (read only; 48 is banned, Note A). */
    public boolean getAutoEcoMode() throws RemoteException { need(); return callRetBool(c(GET_AUTO_ECO_MODE)); }

    /** 81 getStandVerticalAngle() -> float degrees (passive stand, MCU Hall sensor). */
    public float getStandVerticalAngle() throws RemoteException {
        need();
        return call(c(GET_STAND_VERTICAL_ANGLE), request(), r -> r.readFloat());
    }

    /**
     * 71 getIsAutoAtmoSphereLight(): the stock gate of the ambient (atmosphere) light settings entry (newsettings
     * LightFactory: the "lighting_ambient_settings" cell exists only while it reads true). Read only; the light
     * itself is drawn by the projector (ambient/AmbientLight), no atmosphere setter is ever sent (TASKS.md).
     */
    public boolean getAmbientLightSupported() throws RemoteException { need(); return callRetBool(c(GET_IS_AUTO_ATMOSPHERE_LIGHT)); }

    /**
     * 70 getSlideFunc() -> what closing the projector does: 0 shut down, 1 ambient speaker (stock SystemUI
     * AmbientLightSettingActivity "Close device"; anything but 0 is shown as ambient speaker, as stock).
     */
    public int getSlideFunction() throws RemoteException { need(); return callRetI8(c(GET_SLIDE_FUNC)); }

    /** 69 setSlideFunc(0 / 1): the stock "Close device" choice, on the user's row action only. */
    public boolean setSlideFunction(int f) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeInt8((byte) checkRange("slide function", f, SLIDE_POWER, SLIDE_AMBIENT_SPEAKER));
        return call(c(SET_SLIDE_FUNC), q, RET_BOOL);
    }

    /** 229 setTvGammaLevel(0, 0..8) = gamma 1.8 .. 2.6 (stock image_mode_custom_gamma, Custom only). */
    public boolean setGamma(int index) throws RemoteException {
        need();
        return callI32I32RetBool(c(SET_TV_GAMMA_LEVEL), WINDOW_MAIN, checkRange("gamma", index, 0, 8));
    }

    /** 231 setTvDynamicContrastEnable(0, on) (stock image_mode_custom_dynamic_contrast, Custom only). */
    public boolean setDynamicContrast(boolean on) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeInt32(WINDOW_MAIN);
        q.writeBool(on);
        return call(c(SET_TV_DYNAMIC_CONTRAST), q, RET_BOOL);
    }

    /** 84 isConvexLensIn(): starry sky lens attached. */
    public boolean isConvexLensIn() throws RemoteException { need(); return callRetBool(c(IS_CONVEX_LENS_IN)); }

    /** 75 getConvexLensType() -> lens index (meaning UNVERIFIED, 10 lenses). */
    public int getConvexLensType() throws RemoteException { need(); return callRetI32(c(GET_CONVEX_LENS_TYPE)); }

    // ------------------------------------------------------------- picture (read only)
    /** 228 getTvGammaLevel(0) -> index 0..8 = gamma 1.8..2.6. */
    public int getGamma() throws RemoteException { need(); return callI32RetI32(c(GET_TV_GAMMA_LEVEL), WINDOW_MAIN); }

    /** 230 getTvDynamicContrastEnable(0). */
    public boolean getDynamicContrast() throws RemoteException { need(); return callI32RetBool(c(GET_TV_DYNAMIC_CONTRAST), WINDOW_MAIN); }

    // ------------------------------------------------------------- setters (plan step 16)
    /** 64 setPowerOnAFUseDistance(on): first half of the smart autofocus switch (then IGmpf 587, stock order). */
    public boolean setSmartAutofocus(boolean on) throws RemoteException {
        need();
        return callBoolRetBool(c(SET_POWER_ON_AF_USE_DISTANCE), on);
    }

    /** 34 setGameModeType(0 manual / 1 auto). Caller: the game-mode path (game/GameMode). */
    public boolean setGameModeType(int t) throws RemoteException {
        need();
        return callI32RetBool(c(SET_GAME_MODE_TYPE), checkRange("game mode type", t, 0, 1));
    }

    /** 35 setGameModeState(0 on / 1 off). Caller: the game-mode path (game/GameMode). */
    public boolean setGameModeState(boolean on) throws RemoteException {
        need();
        return callI32RetBool(c(SET_GAME_MODE_STATE), on ? GAME_STATE_ON : GAME_STATE_OFF);
    }

    /** 212 getAllmStatus(1): ALLM flag received on the HDMI port right now (Projector settings > Diagnostics). */
    public boolean getAllmStatus() throws RemoteException {
        need();
        return callI32RetBool(c(GET_ALLM_STATUS), HDMI_SOURCE_1);
    }

    /**
     * 125 setPushPullScreenState(1) on entering manual keystone, (0) on leaving (stock KeyStoneWind).
     * The card aborts before any IGmpf write when 125(1) was not delivered (plan 1.3).
     */
    public boolean setPushPullScreen(boolean on) throws RemoteException {
        need();
        return callI32RetBool(c(SET_PUSH_PULL_SCREEN_STATE), on ? 1 : 0);
    }

    // ----------------------------------------------------------- picture rotation (P3 item 21)
    // Stock Play 6 RotateWind (picture_screen_rotation): 125(1), 573(2,false) while real-time keystone is on,
    // getScreenRotationInfo -> {minAng, maxAng, curAng, startAng} (stock rp.ScreenRotationInfo), per key
    // setScreenRotation(5 clockwise | 6 counter-clockwise, step 0.5 = user_manu_screen_rotation_step default,
    // G0058 sets none) inside [min, max], the menu "reset" sends the session's sum back the other way; on
    // close 158(0) (never here: O4), 125(0), 573(2,true). Caller: panel/Play6RotatePanel, panel/Play6Snapshot
    // (parse probe only).

    /** Largest relative rotation one 138 may carry (degrees; a session undo is at most max - min). */
    public static float checkRotationStep(String what, float degrees) {
        if (!(degrees > 0f && degrees <= 45f)) throw new IllegalArgumentException(what + " " + degrees + " not in (0, 45]");
        return degrees;
    }

    /**
     * 130 getScreenRotationInfo() -> {minAng, maxAng, curAng, startAng} in degrees. Wire as Z9X 199: Status,
     * bool, readBuffer(16) (4 floats). Fails (ReplyException with the raw values) unless the reply is plausible:
     * bool true, -45 <= min < 0 < max <= 45, min <= cur <= max. Nothing rotates on a misread.
     */
    public float[] getScreenRotation() throws RemoteException {
        need();
        return call(c(GET_SCREEN_ROTATION_INFO), request(), r -> {
            boolean ok = r.readBool();
            HwBlob b = r.readBuffer(ROTATION_INFO_SIZE);
            float min = b.getFloat(0), max = b.getFloat(4), cur = b.getFloat(8), start = b.getFloat(12);
            if (!ok || !(min >= -45f && min < 0f && max > 0f && max <= 45f && cur >= min - 0.01f && cur <= max + 0.01f)) {
                throw new IllegalStateException("130 raw ok=" + ok + " min=" + min + " max=" + max + " cur=" + cur
                        + " start=" + start);
            }
            return new float[] {min, max, cur, start};
        });
    }

    /** 138 setScreenRotation(5 clockwise | 6 counter-clockwise, degrees > 0, relative), as stock RotateWind. */
    public boolean rotateScreen(boolean clockwise, float degrees) throws RemoteException {
        need();
        HwParcel q = request();
        q.writeInt32(clockwise ? ROTATE_CLOCKWISE : ROTATE_COUNTER_CLOCKWISE);
        q.writeFloat(checkRotationStep("rotation step", degrees));
        return call(c(SET_SCREEN_ROTATION), q, RET_BOOL);
    }

    // ================================================================== PARKED (not compiled)
    // The quick panel's "Auto game mode" (ALLM) row is gone (research battery_sound_features.md 5.1 and
    // 6.4): the stock dayao game page has no such row, its game mode "Auto" already is the ALLM-driven type
    // 1 (34(1)). 213 stays for the probe; the setter waits here without a caller (check H rule 6). When it
    // comes back, c() needs its v1.1.67 case too (setAllmEnable is 209 there, check H rule 8):
    //
    // private static final int SET_ALLM_ENABLE = 214;                  // tsv:setAllmEnable Z9X 312 W=bool R=bool
    //
    // /** 214 setAllmEnable(on): switch to game mode on ALLM content. */
    // public boolean setAllmAutoSwitch(boolean on) throws RemoteException {
    //     need();
    //     return callBoolRetBool(c(SET_ALLM_ENABLE), on);
    // }
}
