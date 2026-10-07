package org.z9x.projector.ak;

import android.content.Context;
import android.util.Log;

import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.Notify;

import java.util.HashMap;
import java.util.Map;

/**
 * Focus event 105 result notices (FEATURE_SPEC 2.6; vendor values VERIFIED in
 * AK_Process::sentEventAfterAk / EagleEye1_SingleCam_AK::end and stock ConstantFocus) shown on the
 * shared XGIMI notification card. While the AK overlay is up, a notice is held back and shown when
 * the overlay hides, so the card is never photographed together with the test pattern.
 *
 * Differences from stock, on purpose:
 *  - "AK_Success" shows a short "complete" card (FEATURE_SPEC 2.6; stock showed nothing);
 *  - the curtain notices are plain cards: stock CenterNoticeDialog took key focus and any key sent
 *    IGmpf2 192 notifyAutokst(0). We do not take focus for them (the vendor's smart_ak_server that
 *    drives curtain fit crash-loops on this device, open item 7), so no 192 is sent from here;
 *  - "Alignment Complete" has no "manual positioning" button (stock opened its zoom page).
 * Main thread.
 */
final class AkNotices {
    private static final String TAG = "Z9xAk";
    private static final Map<String, Integer> TEXT = new HashMap<>();
    static final String PICTURE_INSIDE_CURTAIN = "SAK_Picture_Inside_Curtain";
    static final String CANCEL_ALL = "SAK_Cancel_All_Toast";

    static {
        TEXT.put("AK_Success", R.string.ak_done);
        TEXT.put("AK_Success_Into_Adjust", R.string.ak_done);          // stock: end-tip dialog
        TEXT.put("AK_Success_Into_Adjust_Select", R.string.ak_done);   // stock: end dialog
        TEXT.put("AK_Tof_Start", R.string.ak_running);                 // stock: AkTipsDialog (icon)
        TEXT.put("AK_Out_Range", R.string.ak_out_range);
        TEXT.put("AK_Adv_Out_Range", R.string.ak_adv_out_range);
        TEXT.put("AK_Usr_Cali_Out_Range", R.string.ak_adv_out_range);
        TEXT.put("AK_Photo_Error", R.string.ak_camera_error);
        TEXT.put("AK_Find_Point_Error", R.string.ak_camera_error);
        TEXT.put("AK_Not_Support_Upside_Down_Mode", R.string.ak_upside_down);
        TEXT.put("AK_Not_Support_GameMode", R.string.ak_mode_mutex);
        TEXT.put("AK_Not_Support_3D", R.string.ak_mode_mutex);
        TEXT.put("AK_Not_Support_Convex", R.string.ak_convex_mutex);
        TEXT.put("AK_Into_STR", R.string.ak_into_str);
        TEXT.put("AK_Device_Init_Error", R.string.ak_device_init_error);
        TEXT.put("AK_Motor_Busy", R.string.ak_motor_busy);
        TEXT.put("AK_Not_Support_21To9_Mode", R.string.ak_21to9);
        TEXT.put("AK_Init_Error", R.string.ak_init_error);
        TEXT.put("AK_Not_Support_Imax_Mode", R.string.ak_imax);
        TEXT.put("SAK_Curtain_Doing", R.string.ak_curtain_doing);
        TEXT.put("SAK_Success_Curtain", R.string.ak_curtain_success);
        TEXT.put("AK_Background_Not_Support", R.string.ak_wall_unsupported);
        TEXT.put("AK_Usr_Cali_Success", R.string.ak_cali_success);
        TEXT.put("SAK_Curtain_Not_Full_Cover", R.string.ak_curtain_not_cover);
        TEXT.put("AK_Large_Angle", R.string.ak_large_angle);
        TEXT.put("SAK_Fail_Curtain", R.string.ak_curtain_fail);
        TEXT.put("SAK_Curtain_Interrupt", R.string.ak_curtain_interrupt);
        TEXT.put("AK_3D_AC_Enable", R.string.ak_wall_color_on);
        TEXT.put("AK_Usr_Cali_Point_Error", R.string.ak_cali_point_error);
    }

    /** Known values that intentionally show nothing (stock showed nothing or a dialog we skip). */
    private static final String[] SILENT = {"AK_Success_No_Toast", "AK_Doing", "AK_NUI_TIMEOUT",
            "AK_Screen_Off", "AK_Tof_Success", "AK_Tof_Stage_End", "AK_Success_Env_Detect",
            "AK_Into_STR_NO_TIP", "SAK_Success_Obstacle", "SAK_Fail_Init", "AK_into_tilt_shift_adjust"};

    private static final SafeHandler MAIN = Ui.main();
    private static Context sApp;
    private static String sPending;              // value held back while the overlay is up
    private static int sCountdown;
    private static final Runnable COUNTDOWN = AkNotices::tick;

    private AkNotices() {}

    static void install(Context app) {
        sApp = app;
    }

    /** Event 105. Returns true when the value is known (handled or deliberately silent). */
    static boolean onResult(String value, boolean overlayActive) {
        if (value == null) return false;
        if (!TEXT.containsKey(value) && !PICTURE_INSIDE_CURTAIN.equals(value) && !CANCEL_ALL.equals(value)) {
            for (String s : SILENT) if (s.equals(value)) { stopCountdown(); return true; }
            return false;
        }
        stopCountdown();
        if (CANCEL_ALL.equals(value)) {
            sPending = null;
            Notify.hide();
            return true;
        }
        if (overlayActive) {
            Log.i(TAG, "notice held until the overlay hides: " + value);
            sPending = value;
            return true;
        }
        show(value);
        return true;
    }

    /** The overlay has hidden: show the held-back notice, if any. */
    static void flushPending() {
        String v = sPending;
        sPending = null;
        if (v != null) show(v);
    }

    /** The overlay starts: keep the card off the projected pattern. */
    static void onOverlayShown() {
        stopCountdown();
        Notify.hide();
    }

    private static void show(String value) {
        Context c = sApp;
        if (c == null) return;
        if (PICTURE_INSIDE_CURTAIN.equals(value)) {        // stock: 10 s countdown, 1 s steps
            sCountdown = 10;
            Notify.show(c, c.getString(R.string.ak_curtain_found, sCountdown));
            MAIN.postDelayed(COUNTDOWN, 1000);
            return;
        }
        Integer res = TEXT.get(value);
        if (res != null) Notify.show(c, res);
    }

    private static void tick() {
        Context c = sApp;
        if (c == null) return;
        sCountdown--;
        if (sCountdown <= 0) {
            Notify.hide();
            return;
        }
        Notify.show(c, c.getString(R.string.ak_curtain_found, sCountdown));
        MAIN.postDelayed(COUNTDOWN, 1000);
    }

    private static void stopCountdown() {
        if (sCountdown > 0) {
            MAIN.removeCallbacks(COUNTDOWN);
            sCountdown = 0;
        }
    }
}
