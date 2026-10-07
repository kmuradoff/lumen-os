package org.z9x.projector;

import android.view.KeyEvent;

/**
 * Keycodes produced by the Z9X keylayouts (research/v6/remote/keylayout) and routed to
 * org.z9x.projector/.KeyReceiver by the global-keys RRO (research/v6/remote/global_keys.xml).
 */
final class Z9xKeys {
    private Z9xKeys() {}
    /** Mic key (BT 63 = HID F5) and AI key (137): Google Assistant, push-to-talk. WAKE. */
    static final int VOICE = KeyEvent.KEYCODE_SEARCH;                       // 84
    /** Focus key short press: autofocus. */
    static final int AUTOFOCUS = KeyEvent.KEYCODE_FOCUS;                     // 80
    /** Focus key long press: manual-focus overlay. */
    static final int MANUAL_FOCUS = KeyEvent.KEYCODE_TV_CONTENTS_MENU;       // 256
    /** Dedicated focus step keys of other remotes / IR / keypad: stock LEFT -> manualFocus(1). */
    static final int FOCUS_STEP_LEFT = KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD; // 275
    /** Dedicated focus step keys of other remotes / IR / keypad: stock RIGHT -> manualFocus(0). */
    static final int FOCUS_STEP_RIGHT = KeyEvent.KEYCODE_MEDIA_STEP_FORWARD; // 274
    /** Ambient key: screensaver (Google TV Ambient mode). */
    static final int AMBIENT = KeyEvent.KEYCODE_TV_SATELLITE_SERVICE;        // 240
    /** IR KEYSTONE / LENS, keypad LENS: quick panel, projection section (v6: settings screen). */
    static final int PROJECTOR_PANEL = KeyEvent.KEYCODE_TV_NETWORK;          // 241
    /**
     * Gear key (BT 66, IR 192, BT 64/251/275 and keypad 9/4 in the .kl files): stays KEYCODE_SETTINGS.
     * The global_keys entry routes it here BEFORE LineageParts atv KeyHandler (which would open
     * TvSettings on UP): PhoneWindowManager handles global keys at :5263 / :4328, KeyHandler at
     * :5311 / :4334 (V61_KEYS_PLAN.md). Short press = quick panel, long press = TvSettings.
     */
    static final int GEAR = KeyEvent.KEYCODE_SETTINGS;                       // 176
    /** Source key (BT 430, IR 88, keypad 225/268/466): input chooser overlay (v6.1, from org.z9x.tvinput). */
    static final int SOURCE = KeyEvent.KEYCODE_TV_INPUT;                     // 178
    /** App-key slots 1..5 (stock VIDEO0..VIDEO4). Z9X remote: slot 3 = iQIYI key, slot 4 = bilibili key. */
    static final int[] APP_SLOTS = {
            KeyEvent.KEYCODE_TV_TERRESTRIAL_ANALOG,   // 235 slot 1
            KeyEvent.KEYCODE_TV_TERRESTRIAL_DIGITAL,  // 236 slot 2
            KeyEvent.KEYCODE_TV_SATELLITE,            // 237 slot 3 (iQIYI key)
            KeyEvent.KEYCODE_TV_SATELLITE_BS,         // 238 slot 4 (bilibili key)
            KeyEvent.KEYCODE_TV_SATELLITE_CS,         // 239 slot 5
    };
    /** Input-device vendor ids of XGIMI remotes / IR receiver / keypad (InputDevice#getVendorId). */
    static final int[] XGIMI_VENDOR_IDS = {0x000d, 0x1d5a, 0x26e3, 0x3697, 0x0001, 0x9999};
}
