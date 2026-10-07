# Z9xProjector: Lumen OS 1.0 (lane L-PROJECTOR, W1)

Base: v6.5.2 (code 70, sha256 d9e213a9…; copy kept in `overlay/apps_v65/`).
Build: `VERSION_CODE=100 VERSION_NAME=1.0 sh apps/build_apk.sh apps/Z9xProjector` (test platform key; the
release re-sign is L-OTA's `tools/sign`). The HAL caller check passes. No new IGmpf code: the new paths only
use existing whitelisted methods (autofocus 44, autoKst / newAutoKst(11), the six user toggles).

## Power: real STR (decision 2026-10-06, supersedes the v6.5 lamp-only standby)
- A short POWER press, the sleep timer, the power menu's new **Sleep** tile, the idle time and the
  screensaver's "turn off after" all call `StandbyController.enter`. It switches the lamp off at once
  (195(false)), pauses media and mutes. As soon as the lamp-off is confirmed (at most 6 s), Android goes to
  sleep and the SoC suspends.
- Removed: the quick-wake window and the power-off after it. The quick panel row "Standby, then power off
  after" is gone; a summary line replaces it.
- Wake: a remote key wakes the SoC through the PM51 wake sources. Android wakes and SCREEN_ON lights the
  lamp. After STR, 196 is polled `persist.z9x.str_lamp_polls` times (default 3, 1 s apart), then 195(true)
  is forced.
  - Device test: v6.5.2 polled 7 times. Tune this value if the picture stays black too long, or if the
    lamp flickers.
- `power/StrGate` blocks STR in these cases (no reset in any of them):
  - `persist.z9x.allow_str=0`: the kill switch, full v6.5 behaviour including the standby window;
  - an update is installing (`sys.z9x.ota.busy=1` or the updater broadcast);
  - an updated slot is not confirmed yet (only in the first 15 min after boot);
  - **a USB host is on the cable.** This was the root cause of every self-reboot: the UDC state is
    "configured", or USB_STATE reports configured, or USB_STATE reports connected while the USB config
    contains adb.
- While STR is blocked, the projector stays in the v6.5 lamp-only standby: display on, lamp off,
  watchdog and thermal guards active. After a foreign sleep it keeps the display off with
  "z9x:quickwake" held. STR follows as soon as the blocker clears. The gate is re-checked on USB_STATE,
  on the updater broadcast, and every 15 s.
- `persist.z9x.str_usb_drop=1` (default 0, for a later device test): with a USB host connected, the
  projector does not stay in standby. Instead it sets `sys.z9x.usb_drop=1` before sleeping (init:
  `sys.usb.config none`), and sets it back to 0 at SCREEN_ON (init restores `persist.sys.usb.config`).
- Foreign sleeps (CEC, adb, a factory remote): the lamp is switched off on SCREEN_OFF. When STR is allowed,
  the short wakelock "z9x:sleepprep" (at most 10 s) holds the suspend until that lamp-off is done.
- Kept unchanged:
  - boot-dark: 0xF1 / 0xD1 + null wake + pm51-wdt-reset after standby or sleep (which now includes STR) →
    power off again;
  - the power-off watchdog magic-close (`z9x_poweroff.rc`, now in L-SYSTEM's `overlay/v1`);
  - manual keystone grey grid;
  - no fan control (IGmpf 434 is never used).
- `overlay/v1/z9x_power.rc` (new, owned by this lane):
  - PM51 wake sources, written at `on boot` and again at boot completed: keypad0, ir0, bt-gpio and
    mute-gpio = 1; rtc0, wifi-gpio, uart, voice, dvi0..3, lan, usb and cast = 0;
  - cec0 = 0, or 1 only while `persist.z9x.cec_wake=1` (the CEC lane sets that property later);
  - unlock of the old `z9x_no_str` wakelock, plus the `allow_str` kill-switch triggers;
  - the `sys.z9x.usb_drop` triggers.
- Power menu: four tiles, Sleep / Power off / Restart / Sleep timer. The timer now has a clock icon; Sleep
  has the moon. `ACTION_SHUTDOWN` (Settings Restart, adb reboot, the updater) fades our curtain with the
  "Lumen" wordmark over the framework dialog (brand spec §8), except while the lamp is already off.

## Recents on LONG-PRESS HOME (speed spec §6, C21)
- `recents/RecentsActivity` is the ASSIST target of the ASSISTANT role:
  - invocation_type 5 (long-press HOME) shows Recents;
  - any other assist opens Lumen Home search;
  - a second long press dismisses it.
- It runs in the persistent process. The first frame is logged as `Z9xRecents: open type=5 tasks=N
  firstFrameMs=…`. Views and drawables are released in onDestroy; only the thumbnail LruCache (≤ 2 MB)
  stays.
- Layout follows the Google TV style with Lumen tokens: 88 % scrim, 384×216 cards, focus scale 1.08 with a
  ring and elevation.
- If the first card is the app you just left, focus starts on the second card, so long-press HOME
  followed by OK switches back like Alt-Tab.
- Keys:
  - OK switches (`startActivityFromRecents`);
  - DOWN shows the card's Close pill;
  - long-press OK closes;
  - UP goes to Close all;
  - BACK dismisses.
- Thumbnail: `takeTaskSnapshot` of the app just left, at 480×270 RGB_565. A black frame (DRM video) falls
  back to the banner, then to the icon.
- `mem/MemoryGuard`:
  - P1 Close: removeTask for all of the app's tasks, plus force-stop unless the app is protected;
  - P2 Close all: P1 for each card, then kill cached third-party processes;
  - P3 boot-clean: removes tasks that are not running at BOOT_COMPLETED or USER_UNLOCKED (once per boot,
    first 5 min only), so Recents is empty after a reboot;
  - P5 first-boot disable of the six APEX apps (only while their state is DEFAULT);
  - `ensureAssistant`: sets the role only when it is empty or its holder is gone.
- `mem/ProtectedSet` decides what Close never force-stops: org.z9x.*, HOME, the IME, accessibility
  services, notification listeners, the dream, speech services, the VPN owner and running VpnServices,
  persistent apps, GMS / GSF / Play / Chromecast / TTS / TvProvider.
- P4 (standby trim) is dropped: the projector suspends now.
- Entry points besides long-press HOME: the quick panel's All settings page ("Recent apps") and the
  SHOW_PANEL `action=recents`.

## Launcher switch (C1/C2/C3) and SetupBridge
- `home/LauncherSwitcher` is the only switch.
  - Entry points:
    - SetupBridge `set_launcher` (synchronous, up to 5.5 s);
    - the `SET_LAUNCHER` broadcast (perm SET_LAUNCHER, extras `mode`, `start_home`);
    - the quick panel "Home screen" row (with a confirm step);
    - Projector settings.
  - Classic set, switched at package level: tvlauncher, plus tvrecommendations and tvcustomizer when
    installed.
  - Lumen set, switched at component level: every activity, alias, receiver and service of
    org.z9x.home, except `org.z9x.home.search.*` and components with meta-data
    `org.z9x.keep_in_classic=true`.
  - Order: the target is enabled first, then the role is assigned, then the other set is disabled.
  - Secure `z9x_launcher` is written only by an explicit apply.
  - `ensure()` at boot does nothing while setup is incomplete or `org.z9x.setup/.SetupActivity` is
    enabled. A missing value counts as undecided. It never starts HOME.
- `setup/SetupBridgeProvider` follows the setup `bridge_api.md` (authority
  `org.z9x.projector.setupbridge`, signature permission SETUP_BRIDGE, checked in `call()`).
  - Methods: ping, remote_state, hal_state, af_run, kst_auto, kst_fit, kst_manual, focus_manual,
    toggles_get, toggle_set, set_device_name, set_launcher, feature (`alice` = false).
  - Not exposed: no generic transact, no factory or calibration codes, no bonding.
  - `setup/SetupEvents` sends `SETUP_STATE` (`what` = remote | hal | af | kst) to org.z9x.setup and
    org.z9x.home only.
  - Supporting changes:
    - `HalController.requestAutofocus` / `requestKeystone` now return a status (callers that ignore it
      are unchanged);
    - `Hal` facade additions: `isAfBusy`, `readToggles`, `setToggle` by name;
    - `RemoteAutoPair.state()`, which is read-only.
- `home/PanelRequestReceiver` handles `SHOW_PANEL` (perm SHOW_PANEL):
  - `section=<QuickPanel.SECTION_*>`;
  - or `action` = autofocus | lamp_standby | sleep_timer | recents.

## Mic key (C4)
- `KeyReceiver`: mic DOWN, after setup with the display on, opens `org.z9x.home.action.VOICE_SEARCH`
  (VoiceSearchAlias, which needs the MIC permission) with `held=true`; UP sends `MIC_UP`.
- From sleep, or in a blocked lamp-only standby, the key wakes the projector and opens `GLOBAL_SEARCH`
  without voice.
- `SearchManager.launchAssist` is no longer used anywhere. If Lumen Home has no search, a toast says so.

## Updater hooks (C16)
- `power/OtaStateReceiver` handles `org.z9x.projector.action.OTA_STATE` (perm OTA_STATE):
  - `busy`: blocks STR;
  - `reboot_when_off`: the next turn-off reboots with reason `z9x-ota` instead of entering STR, and the
    following boot goes dark again by itself (marker `pw_dark_next_boot`).
- Boot-dark and STR are blocked only while `sys.z9x.ota=pending` (z9x_ota.sh: the booted slot is an update
  on probation). `merging` (marked slot, snapshot merge running), `rollback`/`rolledback` (old confirmed
  slot), `none`, `marked` are confirmed (review fix 2026-10-07; the unused `persist.z9x.ota.attempts` is
  gone). `reboot,z9x-ota*` are orderly reasons anyway.

## Manifest union (C5)
- New permissions:
  - MANAGE_ROLE_HOLDERS, CHANGE_COMPONENT_ENABLED_STATE;
  - REAL_GET_TASKS, START_TASKS_FROM_RECENTS, REMOVE_TASKS, READ_FRAME_BUFFER;
  - FORCE_STOP_PACKAGES, KILL_BACKGROUND_PROCESSES, NETWORK_SETTINGS;
  - `org.z9x.home.permission.MIC`;
  - all of them are signature-level or normal, so no privapp allowlist is needed.
- New own signature permissions: SET_LAUNCHER, SHOW_PANEL, SETUP_BRIDGE, OTA_STATE.
- New components: RecentsActivity (ASSIST filter), SetupBridgeProvider, LauncherRequestReceiver,
  PanelRequestReceiver, OtaStateReceiver.

## Brand
- The wordmark on the curtain is "Lumen".
- `Theme.ACCENT` is #3B78E7 (z9x_accent_strong); the settings accent is #8AB4F8 and the background
  #0F1115.

## i18n
- English base files:
  - `res/values/strings_recents.xml` (new);
  - `strings_home.xml` (new);
  - `strings_power.xml`: + power_menu_sleep and power_sleep_summary.
- All 17 locales are in `apps/i18n/pending/projector/<locale>.txt`. W2 merges them into `langs/` and adds
  the two new files to the projector app.
  - `values-ru/strings_power.xml` is hand-written, so its two new keys must be added by hand.
- `power_standby_*` and `panel_standby_off_after` are kept unused (they belong to the allow_str=0 path, and
  removing them would break the existing locale files).

## Hooks for other lanes (not edited here)
- **L-SYSTEM**:
  - remove the `z9x_no_str` block (post-fs `wake_lock` and its `allow_str` triggers) from
    `overlay/v1/z9x_base.rc`;
  - install `overlay/v1/z9x_power.rc` as `/system/etc/init/z9x_power.rc`;
  - Z9xFrameworkKeysOverlay `config_defaultAssistant` = `org.z9x.projector` (C9);
  - `initial-package-state stopped=false` for org.z9x.home, org.z9x.setup and org.z9x.updater (C6).
  - Keep `config_shortPressOnPowerBehavior=0` and the STB_POWER keylayouts from v6.5: our app owns the
    remote's power key.
- **L-HOME** (org.z9x.home):
  - search must live in the `org.z9x.home.search` package (SearchActivity with GLOBAL_SEARCH,
    VoiceSearchAlias, MicUpReceiver);
  - define `org.z9x.home.permission.MIC` (signature);
  - send `SET_LAUNCHER` with `mode=z9x|classic`;
  - send `SHOW_PANEL` with `section` / `action`;
  - mark any component that must survive classic mode with meta-data `org.z9x.keep_in_classic=true`.
- **L-SETUP**: the bridge keys follow `bridge_api.md`. `set_launcher` replies `ok`, `holder`, and
  `pending=true` when the role answer took longer than 5.5 s.
- **L-OTA** (updater): send `OTA_STATE` with `busy` and `reboot_when_off`. z9x_ota.sh publishes
  `sys.z9x.ota` (only `pending` blocks STR / boot-dark) and `sys.z9x.ota.attempt`.
- **CEC lane (W2)**: set `persist.z9x.cec_wake=1/0` for PM51 `cec0`, and call `StandbyController.exit`
  with reason `cec_otp`.

## Device checks before shipping (read-only logs)
- Short POWER logs `Z9xStandby: STR: lamp off, Android sleep now`. On wake: `Z9xLamp: STR resume …` with
  the lamp on within about 3 s, and the app resumed.
- With the Mac's adb connected: `STR blocked (USB host connected …): lamp-only standby` and no reboot.
  Unplug the cable: STR follows within 15 s (or immediately on USB_STATE).
- `cat /sys/mtk_pm/wakeup_source/*` matches the list above.
- Long-press HOME in an app: `Z9xRecents: open type=5 … firstFrameMs<300`. Close leads to `Z9xMem: close …
  forceStop=yes`. After a reboot: `boot-clean removed=N`.
- `cmd role get-role-holders android.app.role.ASSISTANT` returns org.z9x.projector.

## Review fixes (2026-10-07, round 3)
- CEC wake detection without `PowerManager.getLastWakeup` (not in Android 14): the root oneshot
  `z9x_power_wake` (overlay/v1/z9x_power_cec.rc + z9x_power_wake.sh) copies `/sys/mtk_pm/wakeup_reason/name`
  and the kernel's last resume reason into `sys.z9x.wake_now` at every SCREEN_OFF / SCREEN_ON
  (`sys.z9x.wake_query` token). Only an ARMED sleep can end in a CEC wake; a refused one goes back to
  sleep, and a second wake within 2 min of a veto is always the user's (a stale PM51 name cannot lock
  the user out). tvinput's `<Active Source>` right after SCREEN_ON (no remote key) marks a framework
  wake in a blocked sleep (unattended return + storm limit, no veto).
- A sleep the guards refuse (night guard, setup gate C14, storm) also switches the framework
  `tv_wake_on_one_touch_play` off for that sleep (restored at the next user wake or start), so
  HdmiControlService and the vendor HAL's `enableWakeupByOtp` cannot wake Android behind the guards.
  While the CPU stays awake asleep (blocked sleep) the guards are re-checked every 60 s.
- `sleepWork`: `syncWakeProp` (persist.z9x.cec_wake -> init cec0) now runs BEFORE the per-sleep arm, so
  the per-sleep decision is always the last cec0 write.
- Default "Turn on with an HDMI device" is OFF again (Z9xFrameworkKeysOverlay, cec spec guard 8);
  user-configurable on the CEC page / TvSettings.
- StrGate: only `sys.z9x.ota=pending` is "not confirmed" (see Updater hooks).
- Mic: `MIC_UP` carries `up_uptime`; Lumen Home turns an early release / short tap into keyboard search.
- WakeCurtain / shutdown dialog: the same "Lumen" wordmark everywhere (boot animation too).
- Build: 1.0 (100), sha256 2e72f4ecec98b2600a1440353727549f3f3265484a1344f3ba74a4f1fab0e6d7 (test key;
  re-signed with the release platform key in the image). check_hal_callers OK (97 methods).
- Device tests to add (CEC T12/T13): log lines `Z9xCec: wake reason: pm51=... kernel=...` after an STR
  wake by the remote and by a console's One Touch Play; with the night guard set to the current hour,
  `wake from STR by HDMI-CEC (...) refused: night guard` and `turn on with an HDMI device: off for this
  sleep`; `dumpsys hdmi_control` shows tv_wake_on_one_touch_play 0 while asleep in guard hours.

## Review fixes, round 4 (2026-10-07)
- CEC wake from STR after an armed sleep: the lamp-on waits for the CEC verdict
  (`CecPolicy.holdLampOn`, called by `PowerPolicy.onScreenOn` before `LampControl.lampOn`; at most 4 s,
  then it lights anyway). A refused wake (night guard reached during STR) drops the held lamp-on, so the
  lamp / DLP / fans never come on before the veto; StandbyController.enter sends 195(false) if the vendor
  resume relit it. A wake-reason helper that does not answer after an ARMED sleep now counts as a CEC
  wake that goes through the guards (the 120 s veto grace still protects a real user).
- `CecPolicy.startup` restores "Turn on with an HDMI device" only while interactive; a process restart
  inside a guarded sleep keeps it off until the next user wake. `PowerPolicy.resumeSleep` (process
  restarted with the display off) now runs the cec sleep decision (`CecPolicy.onSleepResumed`), so cec0
  and the framework OTP setting match the guards for that sleep.
- Classic launcher voice orb: TVLauncher's search orbs send ASSIST with `search_type` (1 = voice orb,
  2 = keyboard orb; checked in the image's TVLauncher: hda / gkx cases 6 / 7 -> fpq.g(1) / fpq.g(2)).
  RecentsActivity.dispatch opens Lumen search listening for type 1 (tap-to-talk: `HomeSearch.open(ctx,
  true, true, ...)`, extra `tap_voice`), keyboard otherwise.
- SetupBridgeProvider.setDeviceName comment corrected (Z9xAirPlay 1.0 now follows device_name).
- Build: 1.0 (100), sha256 ffb38028ff89749f95e26ff83869f7e74ef58192024ee0637a15e71c6d014a2d (test key; re-signed with the release platform key in the image).
