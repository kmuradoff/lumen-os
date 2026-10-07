# Z9X v6.1: org.z9x.projector architecture (foundation)

**Status:**
- Builds with `VERSION_CODE=61 VERSION_NAME=6.1 sh build_apk.sh Z9xProjector`: javac + d8 + platform signature OK.
- `aapt2 dump badging`: versionCode 61, versionName 6.1, locales `--_--` and `ru`.
- Nothing has run on the device yet.

**Related documents:**
- `V61_REQUIREMENTS.md`: the requirements, binding.
- `V61_KEYS_PLAN.md`: gear and Source routing.
- `V61_IMAGE_PLAN.md`: image changes and permissions.

## 1. Package map and file ownership

Module agents edit **only their own package and their own strings file**. Everything else belongs to the foundation; ask for changes instead of editing it.

| package / file | owner | contents |
|---|---|---|
| `org.z9x.projector` (App, Hal, HalController, KeyReceiver, Z9xKeys, MotorController, ManualFocusActivity, SettingsActivity, BootReceiver, AppSlots, SafeHandler, Ui) | foundation | process entry, key routing, HAL facade, v6 features |
| `org.z9x.projector.hal` (HidlCaller, GmpfClient, Gmpf2Client, KstPoint) | foundation | typed HAL clients (whitelist) |
| `org.z9x.projector.ui` (OverlayHost, Panel, PagedPanel, Page, Row + NavRow / ToggleRow / ChoiceRow / SliderRow / CheckRow / HeaderRow / TextRow, DialogPanel, Notify, Theme) | foundation | overlay framework and XGIMI UI kit |
| `org.z9x.projector.audio` (Z9xAudioFixer) | v6 audio (unchanged) | stream-mute healer |
| `org.z9x.projector.panel` (`QuickPanel`) + `res/values*/strings_panel.xml` | **panel** module | quick settings over the video |
| `org.z9x.projector.ak` (`AkOverlay`) + `strings_ak.xml` | **ak** module | auto-keystone overlay, AK notices, curtain dialog |
| `org.z9x.projector.power` (`PowerPolicy`) + `strings_power.xml` | **power** module | lamp off/on around SCREEN_OFF/ON |
| `org.z9x.projector.remote` (`RemoteAutoPair`) + `strings_remote.xml` | **remote** module | BLE remote auto-pair |
| `org.z9x.projector.source` (`SourceOverlay`) + `strings_source.xml` | **source+keys** module | input chooser overlay; also applies `V61_KEYS_PLAN.md` |
| `org.z9x.projector.sys` (`SystemFixes`) + `strings_sys.xml` | **sys** module | runtime fixes after boot |
| `AndroidManifest.xml`, `res/values/strings.xml`, `res/values-ru/strings.xml` | foundation | the manifest already declares every permission the modules need (see `V61_IMAGE_PLAN.md` §2) |

**String files:**
- Each module file starts with one `<module>_placeholder` string (`translatable="false"`).
- Prefix every string name with the module name, for example `panel_picture_mode`.
- English goes in `res/values/strings_<m>.xml`. Russian goes in `res/values-ru/strings_<m>.xml`, which the module creates.
- The translate phase adds the other languages.

## 2. Fixed entry points and who calls them

All of these are called on the **main thread**. Each call is wrapped in try/catch by its caller.

| entry point | caller | when |
|---|---|---|
| `AkOverlay.install(Context)` | `App.onCreate` | once per process (cheap: no HAL, no window yet) |
| `AkOverlay.onFocusEvent(int type, String value) → boolean` | `HalController.dispatchEvent` | every vendor focusEvent with type 105..118 or 120, in arrival order. `true` means handled, so HalController skips its v6 handling (for example the event-120 curtain hint). |
| `PowerPolicy.install(Context)` | `App.onCreate` | once per process |
| `PowerPolicy.onScreenOff(Context)` | App's single SCREEN_ON/OFF receiver | after the focus motor stop and `OverlayHost.onScreenOff()` |
| `PowerPolicy.onScreenOn(Context)` | same receiver | ACTION_SCREEN_ON |
| `RemoteAutoPair.install(Context)` | `App.onCreate` | once per process |
| `QuickPanel.toggle(Context)` | `KeyReceiver` | gear released before the long-press threshold (also during setup) |
| `QuickPanel.show(Context, String section)` | `KeyReceiver` | IR KEYSTONE/LENS key UP. Section constants are `QuickPanel.SECTION_*`. |
| `SourceOverlay.toggle(Context)` | `KeyReceiver` | Source key UP, after setup |
| `SystemFixes.onBoot(Context)` | `BootReceiver` | BOOT_COMPLETED (not on a later process restart). Must be idempotent. |
| gear long press | `KeyReceiver` | opens TvSettings itself after `OverlayHost.dismissAll` |

**Focus events.** Besides AkOverlay, any module can listen to all focus events with `Hal.addFocusEventListener(l)`. The listener runs on the main thread, after AkOverlay, and must not block. Uses: AF events 333-335/1003/1004/7, eye protection 502/503/602/603.

## 3. Threading rules (hard)

**Main thread.** UI only.
- Never block it. Never call the HAL from it.
- The clients **refuse** main-thread calls with a RemoteException before anything is sent (`HidlCaller.call`).

**HAL thread "z9x-hal".** The single worker for all getters and setters.
- `Hal.run(task)`, `Hal.runDelayed(task, ms)`, `Hal.query(query, result)`.
- `query` delivers the value, or `null` on error or when not connected, on the main thread.
- Tasks run FIFO, one at a time, and must not sleep or loop.
- Exceptions are caught and logged.
- Before the boot handshake has connected IGmpf, tasks are skipped (logged) and queries return null.

**AK thread "z9x-ak".** Only for `GmpfClient.uiAkDisplay(type)` acks, via `Hal.runAk(task)`. Each ack is never queued behind a slow panel call; the vendor waits at most 5 s per AK step.

**Keystone thread "z9x-kst" and motor thread "z9x-motor".** Use `Hal.requestKeystone(ctx, fit)` and `Hal.openManualFocus(ctx)`. These keep the v6 interlocks: keystone in flight, confirmed motor stop, 700 ms watchdog. **Do not** call `autoKst` or `newAutoKst` yourself from `Hal.run`, because they block for seconds.

**The hwbinder callback.**
- `GmpfClient.FocusCallback` replies `Status + true` immediately.
- It never calls the HAL and never blocks.
- It only posts to the main thread through `HalController.dispatchEvent`.

**Settings writes, BLE scanning, file reads such as `config.yaml`.**
- Put them on your own `SafeHandler.newThread("z9x-<module>")`, never on z9x-hal.
- Light ones can stay on the main thread if they take under about 1 ms.

**The persistent process must never crash.**
- Use `SafeHandler`, or wrap every callback in try/catch.
- An uncaught exception restarts the process in a loop, and the lamp, focus and keystone control go with it.

## 4. HAL client API (typed whitelist)

**General rules:**
- Each method sends exactly one fixed code with fixed wire types; the "set sound enhancement" method sends two calls in stock order.
- Values are typed enums, or range-checked ints that throw IllegalArgumentException before anything is sent.
- **No generic transact exists.**
- All codes and wire types were machine-checked against `research/focus/gmpf_IGmpf_codes.tsv` and `gmpf_IGmpf2_codes.tsv`: 125 code constants (90 IGmpf, 35 IGmpf2), all matching method name, W= and R=. The 8 `PqItem` enum codes (688-695) were checked by hand. No forbidden code is present.
- The source of each method is quoted in its javadoc.

**Errors:**
- `RemoteException`: not delivered.
- `GmpfClient.ReplyException`: delivered, but the reply was bad. Do not repeat one-shot calls.

### GmpfClient (`xgimi.hardware.gmpf@1.0::IGmpf/default`)

The existing v6 methods are unchanged: handshake (298/306/391/395/237/307(17)), `sendRemoteAutofocus` (307(44)), `manualFocus` {17,0,1,2}, `autoKst` (326), `newAutoKst` (272), and the 289-292 / 302/303 / 552/553 / 585-588 toggles.

| group | methods (code) |
|---|---|
| AK overlay | `uiAkDisplay(type)` (146). Only 106/107/109/113/114/115/116/118; 110 is never acked. |
| keystone | `newAutoKst(AutoKstMode.MISCKEY 9 / RETRY_ANGLE 10 / CURTAIN_REFIT 11)` (272); `getKeystonePoints()` (186 → KstPoint); `checkKeystonePoints(KstPoint)` (150); `applyKeystonePoints(KstPoint)` (185); `regenerateBootLogo()` (158(0)); `setRealtimeAkTemporarily(bool)` (573(2,b)); `resetKeystoneForTopSpeedGame()` (207(0), refused unless 647 reads 2 or 3); `zoomSessionStart/Finish()` (121/120 with 0); `shiftPicture(ShiftDir, onlyCheck)` (141) |
| projection | `get/setCurtainFit` (289/290), `get/setObstacleAvoid` (291/292), `get/setPowerOnAk` (553/552), `get/setMoveAk` (586/585, real-time keystone), `get/setAutoReverse` (579/580), `getPutMode` / `setPutMode(PutMode)` (173/172, u8 0..3) |
| focus | `get/setPowerOnAf` (303/302), `get/setMoveAf` (588/587), `sendAfCardClosed()` (307(5) then 307(3), FEATURE_SPEC 2.7: only after our AF card was shown) |
| lamp and screen | `getLampLevel` / `setLampLevel(1..10)` (177/176 u8); `getColorTemp` / `setColorTemp(D65 16 / COLOR_TEMP_1 17)` (175/174 u8); `getScreenOn` / `setScreenOn(bool)` (196/195); `getSystemTemperature()` (438) |
| eye protection | `get/setEyeProtection` (151/152); `getEyeProtectionMode` / `setEyeProtectionMode(FOLLOW 0 / SAFE 1)` (87/88); `getEyeProtectionScreenOn` (147); `restoreEyeProtectionScreen()` (148(true) only) |
| picture (window 0) | `getPictureModeRaw` / `setPictureMode(PictureMode: AI 10, STANDARD 1, SPORT 9, HDR_VIVID 26, COLOR_ACCURACY 29, OFFICE 25, PERFORMANCE 5)` (666/667); `setAiScene(AiScene 10..17)`; `getPq` / `setPq(PqItem, 0..100)` (692/693, 694/695, 690/691, 688/689); `get/setMemcLevel` (668/669, 0..3); `get/setNoiseReduction` (670/671, 0..4); `get/setLocalContrast(0..3)` (651/655); `getColorSpaceRaw(src, mode)` (111, argument meaning UNVERIFIED); `setColorSpace(ColorSpace)` (112); `getHdrType()` (620); `getDolbyVisionPicMode` / `setDolbyVisionPicMode(soft)` (623/624, **gated**: refused unless 620 == 2 at that moment; practically unreachable on this SKU); `isVideoPlaying()` (180); `getCurrentInputSource()` (696) |
| 3D | `getCurrent3DFormat` (192), `is3DTo2DEnabled` (193), `set3DMode(Mode3D 1/2/3)` (190), `set3DTo2D(topBottom)` (191) |
| HDMI, game, 120 Hz | `getAspectRatioRaw` / `setAspectRatio(4:3 0, 16:9 1, AUTO 2, ORIGINAL 4)` (664/665); `getGameModeOption` / `setGameModeOption(0..3)` (647/648); `setOutputTiming(ULTRA_120_ON 4 / OFF 6)` (161) |
| sound | `getSoundEffect` / `setSoundEffect(AI 3, STANDARD 20, MOVIE 1, MUSIC 2, SPORTS 12, KARAOKE 4)` (62/61 u8); `getDtsEffects` (43); `getSoundProcessRaw` (26); `setSoundEnhancement(HARMAN 2 / DTS_VIRTUAL_X 5)` (44(false) then 27); `get/setVolumeBalance` (41/42); `get/setPowerOnMusic` (420/419) |
| wall colour | `startWallColorAdaptation()` (106); `resetWallColorAdaptation()` (100); `get/setAutoColorAfterAk` (249/250) |
| power diagnostics | `getBootReason()` (230), `getWakeUpSource()` (639): read-only, for logging |

`KstPoint` is the 326-byte manual-keystone struct.
- Mode 0 only: corners TL/TR/BR/BL, each checked to lie inside 1920×1080.
- `KstPoint.fullFrame()` gives the full-frame set.
- UNVERIFIED: how the stock JNI maps these points to 185. Round-trip 186 → 150 with unchanged points on the device before moving any corner.

**Deliberately NOT implemented** (UNVERIFIED struct layouts; persist the last value set instead):
- 157 `getOutputTimingInfo`: Ultra 120 Hz read-back.
- 662 `getHdmiInfo`: so the "HDMI ≥ 62 Hz" precondition cannot be read. Be conservative.
- 116/117 zoom step structs, so no zoom steps 118/119.
- 153 `zoomControl`.
- 197 `setScreenResolution`.
- 59 `setAudioOutput` (audio path).

### Gmpf2Client (`xgimi.hardware.gmpf@1.0::IGmpf2/default`, a separate interface)

**Connection.** IGmpf2 connects lazily on first use. A failed transact drops the binder, and HalController drops it when IGmpf dies (both live in the same vendor process).

| group | methods (IGmpf2 code) |
|---|---|
| Boost | `isBoost()` (51), `setBoost(bool)` (52; keep the two-press confirmation, refuse in 3D or when hot) |
| wall colour | `disableAutoExposureForWallColor()` (53(false) only) |
| game / ALLM / VRR | `setGameModeType(MANUAL 0 / AUTO 1)` (56); `setGameModeState(on)` (57: 0 = on, 1 = off); `get/setAllmAutoSwitch` (311/312); `getAllmStatus(hdmi 1..2)` (310); `getVrr(hdmi)` (300); `setVrr(on, hdmi)` (301, state values 1/0 **UNVERIFIED**: device-test first) |
| focus / keystone | `get/setSmartAutofocus` (90/91); `notifyAutokstCancelled()` (192(0)); `disablePushPullScreenForCeiling()` (201(false)); `resetKeystoneRatio()` (46(3)); v6.3 `setKstPrepare(enter)` (18(bool, 0), manual keystone session only) |
| AI / picture (window 0) | `get/setSuperResolution` (321/322); `get/setAiContrast` (287/288); `get/setHdrDynamicToneMapping` (269/270); `get/setNativeFrameRate` (283/284); `get/setDynamicBlack` (29/31); `get/setDynamicContrast` (328/329); `get/setColorOptimization` (330/323); `setHdrVividAutoSwitch` (280); `getGamma` / `setGamma(0..8 or 10..17)` (326/327); `resetPicture(BASIC / PROFESSIONAL / ALL)` (278, the stock triples only) |
| style filters | `get3dLut()` (289); `set3dLut(index 0..4)` (290, only the vendor `G0082/HDR/3dlut0..4.cube` files) |

**Not present:**
- 76 (factory), 236/237 (radar), 303 (stub), 203-206 (pan-tilt), 207 (rotation).
- 55, 199, 281/282, 324/325: struct or value range UNVERIFIED.

## 5. Overlay framework and UI kit (`org.z9x.projector.ui`)

**Sizes are XGIMI design px of a 1920-wide screen,** scaled by W/1920 (`Theme.px`). They are **not Android dp**: on the Z9X (density 320), 1 design px = 1 real px.
- The task text said the notification card is "468x156dp". FEATURE_SPEC 1.2 gives 468×156 *design px*, and that is what is implemented. 468 dp would be half the screen.

### OverlayHost

`OverlayHost.get(ctx)` is a per-process singleton; use it on the main thread.

**Panels:**
- `show(Panel)`: one panel at a time; it replaces the current one.
- `toggle(Panel)`: show the panel, or close it if it is the one showing.
- `dismissAll(animate)`, `current()`.

**Static windows** (non-panel, module-owned: the AK overlay, the eye-protection key-eater, the notification card):
- `addStatic(view, lp)`, `updateStatic`, `removeStatic`.
- `params(focusable, w, h, gravity)` and `fullscreenParams(focusable)` build the LayoutParams: TYPE_APPLICATION_OVERLAY, LAYOUT_IN_SCREEN, HARDWARE_ACCELERATED; when not focusable, also NOT_FOCUSABLE and NOT_TOUCHABLE.
- Hardware acceleration works in our persistent process: `ro.config.low_ram=false`, and Android 14 only marks persistent processes, without disabling the renderer.

**Window and focus behaviour:**
- The window belongs to no activity, so the video app is never paused; it only loses window focus.
- Every key restarts the panel's auto-hide timer (`Panel.autoHideMs()`, default 60 s as on stock).
- BACK UP calls `Panel.onBack()` (true = consumed, for example a page pop); otherwise the panel closes.
- Media keys are forwarded with `AudioManager.dispatchMediaKeyEvent`. Volume keys never reach us on TV.
- The panel closes on any of:
  - ACTION_CLOSE_SYSTEM_DIALOGS (HOME, assistant, recents);
  - SCREEN_OFF;
  - losing window focus for more than 300 ms (an activity we launched, the assistant).

### Panel

Subclass hooks:
- `onCreateView(ctx)`: build in code.
- `onCreateLayoutParams(ctx)`: default right side, full height, 0.34 × W.
- `autoHideMs()`, `onBack()`, `onKeyEvent(ev)`.
- `onShown(root)`: default slide-in, 360 ms PathInterpolator(0,0,0,1) over 608 px, alpha 250 ms.
- `animateOut(root, end)`: default 260 ms.
- `onDismissed()`.

Final methods: `dismiss()`, `isShowing()`, `resetAutoHide()`, `rootView()`.

### PagedPanel and Page

`PagedPanel(rootPage)` is the right-side XGIMI card:
- background `#EB1C1C1C`, radius 36, 3 px stroke `#1AFFFFFF`;
- a title, a ScrollView of rows, and a page stack: `push(page)` / `pop()`, and BACK pops;
- rows stagger in by 16 ms each;
- `refreshPage()` after editing rows.

`Page(title)`:
- `add(row)` returns the row;
- `setOnShown(runnable)`: load HAL values here with `Hal.query`;
- `setOnHidden`, `setInitialFocus(index)`.

### Rows

One View per row, drawn on Canvas, no XML. Focused rows have a white background and black text.

| row | behaviour |
|---|---|
| `NavRow(ctx, title, onClick)` | `.setValue(cs)`, `.setChevron(false)` for a button row |
| `ToggleRow(ctx, title, (row, wanted) -> …)` | CENTER marks the row pending and calls the listener. The module writes the HAL value, reads it back, then calls `row.setChecked(value)` on the main thread. `null` = unknown. |
| `ChoiceRow(ctx, title, labels, (row, i) -> …)` | LEFT/RIGHT step without wrapping. CENTER runs `setOnOpen` (for example push a full list page of `CheckRow`s); without it, CENTER steps forward with wrap. `setCommitDelay(ms)` coalesces fast presses. `setSelected(i)` sets the read-back without calling the listener. |
| `SliderRow(ctx, title, min, max, OnSlide)` | LEFT/RIGHT with auto-repeat acceleration. `onChanging` fires at once; `onCommit` fires 200 ms after the last key with the final value only (stock DROP_OLDEST). A commit still pending when the row is detached is applied. `setEdgeListener` handles presses beyond min or max (the Boost "press → again" pattern). `setFormatter`, `setStep`, `setValue(Integer or null)`. |
| `CheckRow(ctx, title, checked, onClick)` | radio item in a sub-list |
| `HeaderRow(ctx, text)` | section header, not focusable |
| `TextRow(ctx, text)` | multi-line text, not focusable |

### DialogPanel

`DialogPanel(title, message, ok, cancel, ok -> …)` is a centred confirmation card.
- Focus starts on Cancel.
- The result is delivered once: ok = false on BACK, timeout or close.
- It replaces the current panel.
- To confirm without leaving the quick panel, push `DialogPanel.confirmPage(ctx, panel, title, msg, ok, cancel, onOk)` instead.

### Notify

`Notify.show(ctx, title)`, `show(ctx, titleRes)`, `show(ctx, title, description)`, `show(ctx, title, description, icon)`, `hide()`. Callable from any thread.

**The card:**
- one shared card, top-right, margin 48, 468×156;
- `#FA292929`, radius 36, 3 px stroke `#1AFFFFFF`;
- optional round icon 72; title bold `#B2FFFFFF`; description `#99FFFFFF`.

**Motion:**
- slides in from the right in 500 ms, holds 5.5 s, slides out in 500 ms;
- a new message replaces the text and restarts the hold;
- the window is not focusable or touchable.

**Fallback and use:**
- If the window cannot be added, a Toast is shown instead.
- `Ui.toast(...)`, which all v6 code uses, now goes to Notify.

### Theme

Holds all colours, sizes and durations, with their FEATURE_SPEC sources.

Helpers: `px`, `pxf`, `scale`, `text(...)`, `fill`, `stroke`, `panelInterpolator()`.

### Minimal module example (panel)

```java
public static void toggle(Context ctx) {
    PagedPanel p = sPanel != null ? sPanel : (sPanel = build(ctx.getApplicationContext()));
    OverlayHost.get(ctx).toggle(p);
}
private static PagedPanel build(Context c) {
    Page root = new Page(c.getString(R.string.panel_title));
    root.add(new HeaderRow(c, c.getString(R.string.panel_sec_picture)));
    SliderRow lamp = root.add(new SliderRow(c, c.getString(R.string.panel_lamp), 1, 10,
            (row, v) -> Hal.run((g, g2) -> g.setLampLevel(v))));
    root.setOnShown(() -> Hal.query((g, g2) -> g.getLampLevel(), lamp::setValue));
    return new PagedPanel(root);
}
```

Keep one Panel instance per module and rebuild its pages only when needed. Rows can be re-attached.

## 6. Other v6.1 foundation changes

**Kill switch removed.** `HalController.featureEnabled()` now always returns true, so `sys.z9x.feat` is no longer read (requirement 13). The diagnostics no longer show featblock/lastboot.

**Strings.**
- Base `res/values/strings.xml` is now English.
- `res/values-ru/strings.xml` carries the former Russian texts.
- Strings that only served the kill switch were removed.
- Hard-coded Russian diagnostic text in HalController and MotorController is now English.

**Keys.** Gear, Source and KEYSTONE/LENS routing are described in `V61_KEYS_PLAN.md`. The IR KEYSTONE/LENS key now opens the quick panel's projection section instead of `SettingsActivity`.

**Screen receiver.** There is one SCREEN_ON/OFF receiver in App, with priority SYSTEM_HIGH-1. On SCREEN_OFF it runs, in order: motor stop, `OverlayHost.onScreenOff`, `PowerPolicy.onScreenOff`. On SCREEN_ON it runs `PowerPolicy.onScreenOn`.

**Visibility.** `SafeHandler` and `Ui.main()` are public so that modules can use them.

**Manifest.**
- New permissions are listed in `V61_IMAGE_PLAN.md` §2.
- `<queries>` gained `org.z9x.tvinput`, `com.android.tv.settings` and the HOME intent.
- `bluetooth_le` is declared as not required.
- The app stays persistent; the only launcher entry is the LEANBACK_LAUNCHER one (no CATEGORY_LAUNCHER).

## 7. Safety rules that still apply (unchanged from v6)

**Never call:**
- IGmpf 301 `autoFocus` (AF uses 307(44)), 453, 583, 241, 422, 229, 233, 400, 421, 94, 95, 99, 101, 102, 155, 80;
- IGmpf2 76, 236/237, 303, 203-206;
- newAutoKst 24;
- manualFocus other than 17/0/1/2;
- anything factory, burn, test, calibration or `*ForTest`.

**Gmpf main.** Nothing may stop, restart or crash `gmpf_main`. Keep the wire types strict, keep one call in flight on z9x-hal, and send setters only on user action. Exceptions: the boot handshake, and the PowerPolicy screen hooks the decisions explicitly allow.

**Packaging and code.**
- No XGIMI binaries, images, sounds or Lottie files are copied.
- No AndroidX.
- The app stays in `/system/app`, platform-signed, with no privileged-only permission.

## 8. Open items and UNVERIFIED points the modules must respect

1. **KstPoint (186/150/185):** the struct size is VERIFIED (326). The corner-to-index mapping and the 186 reply layout are UNVERIFIED. Round-trip with unchanged points first.
2. **`sendAfCardClosed` (307(5), then 307(3))** extends the v6 307 whitelist of {44, 17}, per FEATURE_SPEC 2.7. Use it only after showing our own AF card for events 333-335.
3. **VRR state values** (IGmpf2 301) are UNVERIFIED. **Game-mode readback** (IGmpf2 55) and **Ultra 120 Hz readback** (157) are not implemented: persist the last value set.
4. **`getCurrentInputSource` (696)** while our TIF plays HDMI is UNVERIFIED. If it returns 0, ask the tvinput side for the active input.
5. **RTL** (Arabic is among the requested languages): the manifest keeps `supportsRtl="false"` and the Canvas rows draw left-to-right. Arabic text renders, but the layout is not mirrored. The translate phase should decide whether to add RTL drawing.
6. **Assumptions about overlays over players:**
   - The panel closes via ACTION_CLOSE_SYSTEM_DIALOGS (a deprecated action, still sent by the system) and via the focus-loss check.
   - Media-key forwarding while the panel has focus is UNVERIFIED with YouTube, Netflix and TvView.
   - Whether a player reacts to losing window focus (for example by showing its own controls) is UNVERIFIED.
7. **Default-permission grant** needs a fingerprint or incremental change to run on an upgrade (`V61_IMAGE_PLAN.md` §1). The `remote` module must keep the self-grant fallback.
