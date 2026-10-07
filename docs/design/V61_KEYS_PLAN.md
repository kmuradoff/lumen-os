# Z9X v6.1: gear and Source key routing plan

**Owner:**
- The foundation agent wrote this plan and the `KeyReceiver` side, which is already in `Z9xProjector`.
- The **source+keys module agent** applies the RRO change below.

**Status:**
- The app side is done and compiles.
- The RRO change is specified here and validated with `check_global_keys.py`.
- Nothing has been tested on the device yet.

## 1. Decision: the gear key stays `KEYCODE_SETTINGS`. No new keycode is needed.

The task asked whether Lineage's ATV `KeyHandler` consumes `KEYCODE_SETTINGS`. I checked the Lineage 21 source on the laptop (`~/lineage`, read-only).

**KeyHandler does consume SETTINGS, but only when nothing earlier claims the key.**
- `lineage-sdk/lineage/res/res/values/arrays.xml:82-104` maps keycode 176 (`KEYCODE_SETTINGS`) to the intent `android.settings.SETTINGS`.
- `packages/apps/LineageParts/src/org/lineageos/lineageparts/atv/KeyHandler.java` launches it on `ACTION_UP`, and only after `tv_user_setup_complete`.

**A global key is handled before KeyHandler**, in `frameworks/base/services/core/java/com/android/server/policy/PhoneWindowManager.java`:
- In `interceptKeyBeforeQueueing`, the early return for global keys (`isValidGlobalKey && mGlobalKeyManager.shouldHandleGlobalKey`, lines 5263-5277) runs before `dispatchKeyToKeyHandlers` (line 5311).
- In `interceptKeyBeforeDispatching`:
  - `case KEYCODE_SETTINGS` (line 4304) acts only when `mShortPressOnSettingsBehavior == SHORT_PRESS_SETTINGS_NOTIFICATION_PANEL`. On the live device it is `SHORT_PRESS_SETTINGS_NOTHING`, so the case just breaks.
  - `handleGlobalKey` (line 4328) then runs before `dispatchKeyToKeyHandlers` (line 4334).
- `isValidGlobalKey` (line 5872) excludes only POWER, WAKEUP and SLEEP.

**How the key reaches our app.** `GlobalKeyManager.handleGlobalKey` (lines 74-89) sends an explicit `GLOBAL_BUTTON` broadcast for every DOWN, every repeated DOWN and the UP.
- The extra literally named `"EXTRA_BEGAN_FROM_NON_INTERACTIVE"` comes from `GlobalKeyIntent.java:29-30`.
- When the global-keys RRO maps `KEYCODE_SETTINGS`, KeyHandler never sees the key, so TvSettings no longer opens by itself.

So a new TV keycode such as `KEYCODE_TV_MEDIA_CONTEXT_MENU` is **not needed**. Switching to one would mean editing all 10 keylayouts, and nothing would be gained.

**Where the gear key comes from today.** All of these already map to `SETTINGS` and stay unchanged:

| file | scancodes |
|---|---|
| `Vendor_000d_Product_3838/3840/3841/3842/3843.kl` | 66 |
| `Vendor_26e3_Product_af02.kl` | 66 |
| `Vendor_1d5a_Product_c081.kl` | 64 |
| `Vendor_3697_Product_0002.kl` | 251, 66, 275 |
| `Vendor_3697_Product_0001.kl` (IR) | 192 |
| `Vendor_9999_Product_0666.kl` | 9 |
| `Vendor_9999_Product_0777.kl` | 4 |

## 2. Decision: the Source key goes to `org.z9x.projector`

**v6:** `KEYCODE_TV_INPUT` goes to `org.z9x.tvinput/.GlobalKeyReceiver`, which opens the Google TV Inputs panel or its own `InputPickerActivity`.

**v6.1:**
- `KEYCODE_TV_INPUT` goes to `org.z9x.projector/.KeyReceiver`, which calls `SourceOverlay.toggle(ctx)`. The reason: the overlay framework (`OverlayHost`, the persistent process, the XGIMI UI kit) lives in the projector app. One panel at a time also means the Source overlay correctly replaces an open quick panel.
- `KEYCODE_TV_INPUT_HDMI_1` and `KEYCODE_TV_INPUT_HDMI_2` (direct HDMI keys) **stay** with `org.z9x.tvinput`.
- `org.z9x.tvinput` is otherwise unchanged. Its `GlobalKeyReceiver` `TV_INPUT` case becomes unused but harmless; leave it as a fallback.
- `SourceOverlay` opens inputs through tvinput's public intents: `ACTION_VIEW` + `TvContract.buildChannelUriForPassthroughInput(inputId)`, the same as `HdmiInputs.viewerIntent`. Home is `CATEGORY_HOME`.

## 3. Exact change to apply: `gsi/apps/Z9xFrameworkKeysOverlay/res/xml/global_keys.xml`

**The only file to change.** The validated result is `<scratch>`; it passed `check_global_keys.py` against `../build/aosp14_keycodes.txt` with "20 keys, 0 errors".

```diff
-    <key keyCode="KEYCODE_TV_INPUT" component="org.z9x.tvinput/.GlobalKeyReceiver" />
+    <key keyCode="KEYCODE_TV_INPUT" component="org.z9x.projector/.KeyReceiver" />
 ...
     <!-- ===== Z9X: org.z9x.projector ===== -->
+    <!-- Gear key (BT 66/64/251/275, IR 192, keypad 9/4) stays KEYCODE_SETTINGS in the .kl files.
+         As a global key it is dispatched before LineageParts atv KeyHandler (PhoneWindowManager
+         :5263 before :5311, :4328 before :4334), so TvSettings no longer opens by itself.
+         Short press = quick panel, long press = TvSettings (org.z9x.projector KeyReceiver). -->
+    <key keyCode="KEYCODE_SETTINGS" component="org.z9x.projector/.KeyReceiver" />
```

**Rules:**
- Do **not** set `dispatchWhenNonInteractive` on either key. A gear or Source press while the screen is off must not do anything.
- Each keyCode may appear only once: GlobalKeyManager's SparseArray silently keeps the last duplicate.
- The power module also edits this RRO (`config_globalActionsList`), but in `res/values`, not in `global_keys.xml`. Merge both changes into the single `org.z9x.overlay.framework` overlay.
- Rebuild with `sh build_apk.sh Z9xFrameworkKeysOverlay`; it runs `check_global_keys.py` automatically.

**`.kl` changes:**
- None are functionally required.
- Optional, comments only: in the keylayouts, the trailing comments `gear -> Android TV Settings (LineageParts ATV KeyHandler)` and `Source -> org.z9x.tvinput inputs` are now misleading. Replace them with `gear -> org.z9x.projector quick panel (short) / TvSettings (long)` and `Source -> org.z9x.projector input overlay`. The files live in `gsi/overlay/v6/keylayout/` and are installed to `/system/usr/keylayout` by `z9x_v6.sh`.

## 4. What `KeyReceiver` does (already implemented)

| key | event | action |
|---|---|---|
| `KEYCODE_SETTINGS` (gear) | DOWN, repeat 0 | arms the press: remembers its downTime and clears the long-press flag |
| | first repeated DOWN of the same press (about 400 ms or more; framework key repeat) | long press: `OverlayHost.dismissAll`, then TvSettings (`android.settings.SETTINGS`, NEW_TASK), only after setup |
| | UP that is not canceled and had no long press | `QuickPanel.toggle(ctx)`, also during setup |
| | any event with `EXTRA_BEGAN_FROM_NON_INTERACTIVE` | ignored |
| `KEYCODE_TV_INPUT` (Source) | UP that is not canceled, after setup | `SourceOverlay.toggle(ctx)` |
| `KEYCODE_TV_NETWORK` (IR KEYSTONE/LENS, keypad LENS) | UP that is not canceled | `QuickPanel.show(ctx, QuickPanel.SECTION_PROJECTION)`. v6 opened the `SettingsActivity` keystone section instead. |
| mic, focus, focus steps, ambient, app slots | unchanged from v6 | unchanged from v6 |

**Global keys and the panel window.** Global keys never reach the focused window. So the gear UP reaches `KeyReceiver` even while our quick panel holds focus, and `toggle` closes the panel. D-pad, BACK and OK go to the panel window.

## 5. Device checks after flashing (read-only plus a normal key press by the user)

1. `dumpsys window policy | grep -A30 mKeyMapping` should show `KEYCODE_SETTINGS` and `KEYCODE_TV_INPUT` mapped to `org.z9x.projector/.KeyReceiver`.
2. Short gear press: the panel opens and the video keeps playing. A second short press closes it.
3. Holding gear for about 1 s opens TvSettings, and no panel flashes.
4. Source opens the input overlay. HDMI1 and HDMI2 keys on the keypad or IR still switch directly through tvinput.
5. `logcat -d | grep Z9xKeys` should show no "setup not complete" lines once setup is complete.
