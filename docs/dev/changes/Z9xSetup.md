# 1.0.1, no-Google edition (2026-10-09)

- No flow change. Without Google Play services the "more languages" hint and the Done screen's offline
  note do not mention Google (`lang_more_hint_nogms`, `done_offline_hint_nogms`, 18 locales).

# Z9xSetup (org.z9x.setup): Lumen OS first-run setup

## 1.0 (versionCode 100), lane L-SETUP, W1 (2026-10-07)

This is a new app. It implements `research/v7/setup/SPEC.md` and the binding PLAN rules C1-C3, C6, C12-C14, C17, C22, C23 and C25. The Alice step stays hidden, per the user's decision.

**App shape**
- One HOME activity (`.SetupActivity`, priority 10, no SETUP_WIZARD category). Steps are Views inside it. The app has no receivers, services or jobs.
- Install path: `/system/priv-app/Z9xSetup` (priv-app only, so the HOME priority of 10 is not capped). It is platform-signed.
- Plain Java and Views: no AndroidX, no bitmaps. The QR code, illustrations and launcher previews are drawn in code.
- APK size is about 210 KB. PSS measured on an Android 15 TV emulator was about 34 MB (budget 45).

**Step order**
1. Welcome + language: 17 native names, plus "More languages…".
   - `LocalePicker.updateLocales` applies the language without an activity relaunch; the strings re-bind and RTL is handled.
   - The pre-selected language is the current system locale (English on fresh data; C25 is owned by the image lane).
2. Network: our own Wi-Fi list and password/hidden-network pages.
   - Handles PSK, SAE, OWE and open networks. Wrong password, 30 s timeout, captive portal, no internet and Ethernet are all handled.
   - "Other options" opens the TvSettings Wi-Fi screen.
   - The IP time zone is fetched in the background. With C23, it writes `auto_time_zone=0` when it applies the zone.
3. Remote: the state comes from the SetupBridge (1 Hz poll plus the SETUP_STATE broadcast). The step is skipped when a remote is already connected, and auto-advances when one connects. There is no pairing code in this app.
4. Google (optional): `AccountManager.addAccount("com.google")`.
   - `device_provisioned=1` is set first.
   - After a failure the user gets Try again / Skip, plus "Problems signing in?" (GSF ID and a QR code to google.com/android/uncertified).
5. Picture: a full-screen grid and a card.
   - Buttons: af_run, kst_auto, kst_fit (only if curtainFit), kst_manual, focus_manual. Switches: POWER_ON_AF and MOVE_AK.
   - Each is a named bridge method; this app reaches no HAL code.
   - Busy/ready comes from hal_state. After 20 s without the HAL, the step says "later".
6. Home screen: Lumen Home (recommended) or classic Android TV. The choice is applied at Finish.
7. Alice: hidden unless the bridge's `feature("alice")` returns enabled + url.
8. Done: a summary with Change for the time zone and the projector name (picker per C12, via bridge `set_device_name`).
   - Shows the "protected video components missing" line when `sys.z9x.blobs` is set and not `ok` (C17).
   - Footer: "Lumen OS <ro.z9x.version> · by kmuradoff".

**Finish (idempotent, logged)**
1. Persist `state=finishing`.
2. Bridge `set_launcher`. No local copy of the switch (C1). If the bridge fails twice, the app only records `Settings.Secure z9x_launcher`, which LauncherSwitcher.ensure() applies at the next boot.
3. Set the three setup flags.
4. Send SetupWraith's two broadcasts.
5. Disable SetupWraith's MainActivity (belt and braces).
6. Disable our own activity, start HOME, and kill the process after HOME draws.

**Robustness**
- Re-entry rules from SPEC 4.10:
  - an interrupted finish runs again headless;
  - flags=1 with the launcher not applied shows the launcher step only;
  - flags=1 with the launcher applied runs only the tail of the finish.
- After a power cut, setup resumes at the stored step, or the nearest step that is available.
- Crash guard: 3 uncaught exceptions in one boot within 3 minutes trigger a safe finish. Low-memory kills and power cycles do not count.
- Holding BACK for 8 s shows "Skip setup?", then a safe finish.
- adb rescue on the device: `am start -S -n org.z9x.setup/.SetupActivity --ez safe_finish true`. It is honoured only for shell/root.
- Test hook (T13): on a userdebug build, `debug.z9x.setup.crash=1` makes onCreate throw.

**Shared sources**
- `res/values/z9x_tokens.xml` and `src/org/z9x/common/GeoIp.java` are copies made by `apps/common/sync.sh` (C7/C13); never edit them here.
- `net/TzLookup` adds setup's Gservices `ip_timezone` fallback on top of the shared chain.

**i18n**
- The English base is `res/values/strings.xml`, generated from `apps/i18n/pending/setup/en.txt`.
- All 16 translations are in `apps/i18n/pending/setup/<locale>.txt`. They pass gen.py's checks and compile with aapt2.
- W2 must add `"setup": APPS + "/Z9xSetup/res"` to gen.py `RES` and append the `@setup/strings.xml` sections to `langs/`.

**Permissions** (all granted by the platform signature)
- Beyond the spec sketch, two were found missing on the emulator: WRITE_SETTINGS (`updatePersistentConfiguration` also enforces it) and READ_WIFI_CREDENTIAL (`getPrivilegedConfiguredNetworks`).
- MANAGE_ROLE_HOLDERS is dropped (C1).

**Build:** `sh gsi/apps/build_apk.sh gsi/apps/Z9xSetup`. The version comes from the manifest.

## 1.0, remote first (2026-10-08, owner's device test after a full wipe)
- Bug: the remote step was skipped silently. It was step 3 with `autoSkip = remoteConnected`; the owner
  held Back + Home during the language step, Z9xProjector paired the remote there, so step 3 saw
  "connected" and never showed. Language came first although the BT-only remote could not be used yet.
- The remote step is now the FIRST step and is always shown (no `autoSkip`). It is shown in the device
  locale (English on fresh data); the drawing carries the meaning.
  - New illustration (`ui/RemoteArt`, vectors `res/drawable/remote_xgimi*.xml`): long rounded body,
    D-pad ring with OK, Back and Home lit in the warm accent #F2B26B; only the glow layer's view alpha
    pulses (no redraw), stopped when the step is not shown.
  - Instruction "hold Back and Home together for 3 seconds" (the combo of Z9xBtPair / RemoteAutoPair,
    unchanged), a hint for users without a remote, live status from the bridge: searching / Found <name>
    (`pairing` + `found`, new bridge fields) / "Press any button on the remote" (bonded, asleep) /
    connected. "Connected" is the bridge's stricter one (bonded + link + HID input device up).
  - A key from an XGIMI remote (vendor 0x000d / 0x1d5a / 0x26e3) proves it works: "connected" at once,
    and its OK continues immediately (consumed, never presses Skip).
  - Continues by itself 1.2 s after "connected".
  - Without a remote: a quiet "Skip" (focused) and "Pair another accessory", one above the other, for the
    projector's own keys or a keyboard. Text on this step is ≥ 36 px.
- BACK from the language step no longer returns to a remote step that has nothing left to do
  (`Step.revisitable`). The wordmark intro now plays on whichever step comes first.
- New hooks: `Step.onKey` (keys before the views while no sheet is shown), `Step.revisitable`;
  `SetupActivity.setRemoteConnected` is public (the step reports a connected remote for the summary).
- i18n: new `res/values*/strings_pair.xml` (`remote_wake`, `remote_later_hint`; 17 locale dirs,
  hand-written, gen.py validates). `remote_skip_hint` ("pair it later from the quick panel") is no longer
  used: the quick panel has no pairing row any more.
- Build: 1.0 (100), sha256 943da7d6e259797208921f7b7ecb45fe4672cf4457e23e8a3780bfc39fc84b05 (test key).

## 1.0.0 integration (2026-10-08)
- versionCode 101, versionName 1.0.0 (manifest). Icon `brand_icon_setup` (apps/common/brand).
  sha256 f7108176de4e39a6fc81294bfe4473e4e0b6f50fc92440de7ba170746aa9b3f1 (test key).

## 1.0.1: the remote is confirmed with OK (2026-10-08, owner's request)
- After the remote connects the step no longer continues by itself (the 1.2 s auto-advance is gone). It
  shows "Press OK on the remote" (new `remote_press_ok`, warm accent #F2B26B like the lit keys, 40 px,
  17 locale dirs + English, hand-written in `strings_pair.xml`, gen.py validates) and the drawing lights
  the OK key with a soft pulse (new layer `res/drawable/remote_xgimi_ok.xml`, view-alpha pulse only, no
  redraw; `RemoteArt.setAwaitOk`). It continues only on OK / ENTER / NUMPAD_ENTER from an XGIMI remote
  (vendor 0x000d / 0x1d5a / 0x26e3): a full press that started on the step, consumed so it never presses
  Skip. An OK that is also the first sign of the remote connects and continues at once.
- Unchanged: the quiet "Skip" / "Pair another accessory" for the projector's own keys or a keyboard.
- The remote drawing is 450 px tall (was 500) so the new line fits the 800 px column with both pills.
- Log: `remote connected on the step -> waiting for OK on the remote`, `OK on the XGIMI remote -> remote
  confirmed, continue`.
- Device check: fresh setup, pair with Back + Home → "Remote connected" + the lit OK pulses, the step
  stays; OK on the remote → language step. The projector's keypad OK still presses Skip.
- Build: versionCode/versionName still 101 / 1.0.0 in the manifest (the integrator bumps it for 1.0.1),
  sha256 cb4a2fbe935e4be4640661ad5934497e44be3beee648804260cd9bead8f4b84c (test key).

## 1.0.1 integration (2026-10-08)
- versionCode 102, versionName 1.0.1 (manifest). sha256
  dc346be0e9b4d4114bfc1a27054995c35b564808924b56c8710679f63c4eeeaa (test key).

## 1.0.1: UI resolution 1080p / 2K / 4K (2026-10-09, owner's request)
Versions unchanged (versionCode 102 / versionName 1.0.1 in the manifest). The UI resolution becomes a choice
1080p (1920x1080 @ 320) / 2K (2560x1440 @ 427, the new default) / 4K (3840x2160 @ 640), 960x540 dp each.
- Checked at all three: every size is a design px of a 1920-wide screen scaled by width / 1920 (`Ui.px`,
  1.33 at 2K, 2 at 4K), so the layout is the same picture at every resolution. The remote drawing and the
  icons are vectors (rasterised at the shown size), the QR code uses whole-px modules (floor(size / modules)),
  the launcher previews, grid, dots, switch and wordmark are drawn in proportion. No bitmaps, no other
  1920x1080 assumption; `DESIGN_WIDTH` 1920 is only the scale's base.
- A resolution change while setup is shown: `configChanges` keeps the activity, but `Ui`'s scale and every
  px of the skeleton and the steps were of the old size. Now `Ui.displayChanged` (density or width scale)
  re-inits the scale and the activity is recreated at the stored step, the power-cut resume path (F9);
  log `display changed -> WxH N dpi`. During the finish only the scale is refreshed.
- `AuroraView`: the redraw interval grows with the pixel count (50 ms x area / 1920x1080, at least 50 ms):
  20 fps at 1080p (unchanged), ~11 fps (89 ms) at 2K, 5 fps (200 ms) at 4K, i.e. the same GPU fill per
  second as at 1080p (each redraw repaints the whole window). A drift step is then at most ~15 px of a
  1600 px gradient at 0x2E alpha: not visible.
- Build: sha256 0f359161082e7d2cdeca2c0754389beef6134ddf5eb6e8fb81499aa4d3846249 (test key). The sources
  before this change rebuilt exactly to the 1.0.1 pin dc346be0... first.
- Device checks (2K default, then 4K and 1080p): every step fills the screen like at 1080p (wordmark, dots,
  title column, remote drawing, Wi-Fi list, sheets, picture grid with its corner marks on the screen edges);
  text sharp; the QR codes (Google help, Alice) scan; focus moves without stutter on the welcome and network
  steps at 4K (`dumpsys gfxinfo org.z9x.setup`: janky frames). A runtime change (`wm size 1920x1080; wm
  density 320` as root on a test unit, then reset) relaunches at the same step with the logcat line above.
