# 1.0.1, no-Google edition (2026-10-09)

- `AppSlots`: an app key without an assignment falls back to the Play Store only when Play can be launched,
  else to Lumen Home's Apps page (`HOME_APPS`: ACTION_ALL_APPS for org.z9x.home, AllAppsAlias); its label
  in Projector settings is "Lumen Home: Apps" (`slot_home_apps`, 18 locales). `<queries>` + org.z9x.home.

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

## Remote pairing UX and quick panel cleanup (2026-10-08, owner's device test after a full wipe)
- Why the setup's remote step was "skipped": it was step 3 (after language and network) with
  `autoSkip = remoteConnected`. During the language step the owner held Back + Home, RemoteAutoPair's NEW
  window bonded the remote ("Pairing the remote…" card), so at step 3 it was connected and the step never
  showed. The setup now shows the remote step FIRST and never skips it (Z9xSetup CHANGES).
- "Connected" is stricter: `RemoteAutoPair.state().connected` (bridge `remote_state`) = a bonded XGIMI
  remote that is ACL connected AND whose HID input device is up (`hidUp`: the input device carrying its
  Bluetooth address, `InputDevice.getBluetoothAddress`, or an external one with an XGIMI remote vendor id
  0x000d / 0x1d5a / 0x26e3 or the remote's Bluetooth name). New manifest
  permission `android.permission.BLUETOOTH` (normal; InputManagerService enforces it for the address).
  The scan policy keeps the plain ACL test.
- `remote_state` gains `pairing` (a bond / re-pair in progress) and `found` (the advertised name), for
  "Found <name>"; `bondNow` reports the state at once.
- During the first-run setup the RECOVER window also bonds a NEW remote in pairing mode ("XGIMI RC" +
  LE Limited); after the setup RECOVER stays "known addresses only".
- New full-screen prompt `remote/RemotePrompt` ("Reconnect your remote"): the same remote drawing as the
  setup (vectors `remote_xgimi*.xml`, Back / Home lit in #F2B26B with a soft pulse of the glow layer's
  view alpha only), "hold Back and Home for 3 seconds", live status (looking / found <name> / connected),
  a battery tip and one quiet "Not now" button for the projector's keys or a keyboard; D_Calm-like night
  gradient, serif title, text ≥ 36 px. It closes itself 1.2 s after the remote is usable (a key from an
  XGIMI remote also counts and never presses the button).
  - When: after the setup, screen on, not in standby, not dreaming, no other overlay, Bluetooth on, and
    only if this projector had an XGIMI remote before (`had_remote`, or an address we bonded). Reason 1:
    no XGIMI remote bonded any more (5 s after the bond went, or at a wake). Reason 2: none usable within
    60 s of boot / SCREEN_ON (not after an HDMI-CEC wake: new `CecPolicy.cecWakeActive()`).
  - Never in a loop: at most once per wake (a removed bond re-arms it); a dismissal by the user (button,
    BACK, HOME, another window, the 2-minute auto-hide) snoozes it 30 min, then 4 h, then 24 h, and after
    3 dismissals it stays quiet until a remote is usable again (which also clears the snooze). Screen off /
    standby close it without a snooze.
  - While it is shown the scan is a NEW window at LOW_LATENCY (strict match, a new or reset remote may be
    bonded); the "Pairing the remote" / "Remote connected" cards are not shown over it.
  - Review fixes (same day):
    - The 60 s rule starts at the boot and at SCREEN_ON only. Before, every start of this persistent
      process counted as a wake, so a crash/restart in the middle of a film with the remote asleep put the
      full-screen prompt over the film a minute later.
    - `hidUp` also accepts an input device with an XGIMI remote vendor id (or the remote's name) when it
      reports a different Bluetooth address (an LE remote may show another form of its address). Before,
      such a mismatch made a working remote look lost at every wake, and closing the prompt with the
      remote's own key cleared the dismissal count, so the back-off never engaged.
    - A remote whose link stays up ~30 s without a recognised input device counts as back for this wake
      (no prompt when it sleeps later), instead of re-checking every 5 s for as long as the link is up.
    - Status line while a remote is still paired but not connected: "Press any button on the remote"
      (new `remote_lost_wake`, all 17 locales, same text as the setup's `remote_wake`) instead of
      "Looking for the remote…".
- Quick panel: ONE Keystone tile again. The v6.3 "Manual keystone" tile is gone; the Keystone page keeps
  Auto keystone now, Fit to screen now, Manual keystone (4 corners) and Reset. A remembered
  `manual_keystone` tile maps to Keystone; `show(SECTION_MANUAL_KEYSTONE)` (SetupBridge `kst_manual`)
  still opens the corner editor directly. The grid is now 3 × 3 + All settings.
- Quick panel: no "Pair remote" row on the All settings page (pairing is automatic: setup step, NEW
  window after every power-on, the prompt above). Projector settings keep "Pair remote" for a second remote.
- i18n: new `res/values*/strings_pair.xml` (6 strings, 17 locale dirs, hand-written, gen.py validates).
- Unused now (kept): `ic_tile_manual_keystone.xml`, `kst_tile`.
- Build: 1.0 (100), sha256 c47dc9ba61ce45e9ce275d0558f02461dcc4cacd74ce656219e8c5971e593b0d (test key;
  re-signed with the release platform key in the image). check_hal_callers OK (97 methods); no HAL code
  added.
- Device checks: `Z9xRemote: remote usable (hid up)` after the remote connects; unpair the remote in
  TvSettings → the prompt within ~5 s, hold Back + Home → "Found XGIMI RC" → "Remote connected" → it
  closes; "Not now" → `remote-lost prompt dismissed (1): quiet for 30 min`; wake by a console over CEC →
  `woken by an HDMI device, no prompt this wake`.

## 1.0.0 integration (2026-10-08)
- Build: `VERSION_CODE=101 VERSION_NAME=1.0.0`. Icon `brand_icon_projector`, banner `brand_banner_projector`
  (application and SettingsActivity; apps/common/brand). sha256 14ae549f485dcf12a712ab7dbd053eb9d97ca93c07c69caec90fd8b209697bd8 (test key).

## 1.0.1: manual keystone pace and exit, AK overlay escape (2026-10-08, friend's Z9X live log)
Source: `logs/friend_<serial>/live_2134.log` (Z9xPanelKst / Z9xAk / Z9xHal plus the vendor's own
GM_DISP_* lines). No new HAL code, no new call site of a code this class did not use; check_hal_callers OK.

**Why the picture "looked reset" after BACK (root cause, fixed)**
- At every exit with a move, 1.0 sent IGmpf2 18 `setKstPrepare(false,0)` although 18(true) was never sent
  (it is off since 1.0; `ensurePrepared` set `prepared = true` anyway). The vendor then ran
  `setKstPrepareMode(0)`: "recover point:(0,0)(0,0)(0,0)(0,0)" → `SetWarpMap (0 0)(3839 0)(0 2159)(3839 2159)`
  (DLP warp back to the full frame), then a CorrectKeystone with the zero points that its own
  `checkTrapezoidPoint` refused. The DB (`kstenvsave`) kept the user's corners, so 186 still read them and
  158 / "Saved" followed: saved, but the picture showed no keystone until the next boot or AK. That is
  what the owner's friend saw at 21:40:17-18 (he then ran Auto keystone from the quick panel at 21:40:23).
- `prepared` now means "18(true) really sent"; the exit sends 18(false,0) only then (or for a marker an
  earlier session left), and logs `exit: 18 setKstPrepare(false,0) not sent (18(true) was not sent)`.

**Exit (BACK, HOME, auto-hide, screen off)**
- A target the card shows that no 185 has sent yet is applied first (always through 150, one 185), so
  the saved corners are the ones on the card. Normally nothing is pending (the apply queued by the last
  key runs earlier: the HAL thread is FIFO): `exit: nothing pending (card == projector …)`.
- After 158, one 186 read-back is compared with the last 185; "Saved" appears only after all exit calls
  and not when the read-back clearly differs (186 reports the vendor's stored corners, so it cannot see a
  warp reset like the one above; it catches a 185 the vendor refused).
- Logged decisions: 18(false) sent or not, 158, the read-back, 573(2,true) sent ("re-arms the motion
  trigger, as stock") or not needed (586 off). Read paths: nothing in Lumen starts an auto keystone after
  the exit (326 / 272 only on a user action: quick panel, settings, setup bridge); real-time keystone
  only corrects after the projector is moved; the game profile's 207 reset needs a keystone known to be
  the full frame (our 185 marks it changed). The vendor's power-on AK at the next boot (if the user has it
  on) still re-measures, as stock.

**Pace of the corner moves**
- Measured from the vendor's lines: 185 = CorrectKeystone 375-450 ms (SetWarpMap to the DLPC8445 over
  I2C ~350 ms + two DB/CRI saves), 150 ≈ 1 ms, our own overhead ≈ 8 ms. So the projector can show a new
  corner only every ~0.4 s. Stock (KeyStoneWind / KeyStoneManager, decompiled from the 838 newsettings
  APK locally) pays the same on the HW warp: per key 186 → 185 → 150 plus four 186 + 150 arrow pre-checks,
  synchronously; it hides it with 18 setKstPrepare(true) (SW warp preview), which stays off here.
- The real problem was overshoot: with the 1.0 acceleration a held key ran the target up to ~0.8 s of
  travel ahead of the picture; the user let go when the picture looked right and it jumped further
  (TL 364 → 405 → 337 → 364 in the log). A held key now never runs the target more than
  8 × acceleration steps (max 24 = 144 / 81 panel px) ahead of the corner the projector shows; single
  presses always count. Max held speed ≈ 24 steps per apply (~9 % of the picture per second).
- 150 only where it protects: the first move, after a rejection, after a reset, and when the moved corner
  leaves the segment of positions 150 already accepted for it (same line, other corners unchanged).
  Inside it, 150 is skipped (the vendor's 185 re-checks the shape itself and applies / saves nothing
  when it fails). This saves only ~1 ms per move: 150 is not the bottleneck, the 185 above is.
- Every 185 logs `(150 N ms | 150 skipped: inside the accepted range, 185 N ms, key -> projector N ms)`;
  the exit line adds the session totals (185 avg / max, 150 count / skipped, key → projector avg / max).
- Card: while the projector catches up, an accent ring marks where it shows the selected corner now.
- onDraw no longer allocates: the card's two `float[4]`, the corner / direction arrays of the L marks and
  the diagnostic quad are fields or constants, and the card texts are ellipsized once per card width
  (`refit`) instead of `TextUtils.ellipsize` on every frame.

**AK overlay (`ak/AkOverlay`)**
- BACK hides the overlay when the press starts ≥ 2 s after the overlay appeared (acts on the UP, so the
  whole press stays in our window). Nothing is acked on that path (hide bumps the token: pending acks are
  skipped); the rest of that AK run (our UI steps 106-118 / 109) is neither shown nor acked, so the vendor
  times out on its own. 106, or a step after 8 s of silence, starts a new run; 105 / 110 / 114 end it.
  Logs: `BACK after N ms: overlay hidden by the user, nothing acked …`, `… after BACK: not shown, not acked`.
- Until that hidden run ends (or 8 s after its last AK event / the BACK), `AkOverlay.isActive()` stays
  true: the vendor still runs its TOF AF, zoom motor and CorrectKeystone without UI, so the AF key (44),
  manual focus moves, manual keystone, the keystone reset, a new auto keystone, the power menu and the
  sleep-timer fade stay refused exactly as with the overlay up. The keys of the app below work at once.
- Main-thread hang watchdog while the overlay is up (thread "z9x-akhang"): a ping to the main looper
  every second; one unanswered for > 10 s (uptime clock, so a suspend does not count) logs the main
  thread's state and stack (one line per frame) and kills this persistent process, which the system
  restarts; the overlay window goes with it. It never watches the vendor: between AK events the main
  thread is idle and answers in milliseconds. Not with a debugger attached.

**Device checks (cannot be run here)**
- Manual keystone, hold DOWN on a corner 2 s, release: the picture stops within one step of where it was
  when released; `Z9xPanelKst` 185 lines show `key -> projector` ≈ 400-800 ms and `150 skipped` while
  going back and forth inside the explored range.
- BACK right after a move → `exit: nothing pending` or `exit: pending target applied …`, NO
  `exit: 18 setKstPrepare(false,0) ->` line, `exit: 186 read-back … matches`, then "Saved". The vendor
  log must show no `setKstPrepareMode[29] mode:0` / `SetWarpMap (0 0)(3839 0)…` after the last 185, and
  the picture must keep the corners (also after leaving the quick panel).
- Auto keystone from the quick panel, press BACK at once (< 2 s: `BACK ignored`), then after 2 s: the
  overlay goes, the vendor finishes without UI within ~5 s per step, the remote works (D-pad / BACK /
  HOME in the app below at once; global keys such as AF or the gear log `AK overlay active: key … ignored`
  until the run's 105 / 110 / 114, or 8 s after its last event). `adb shell kill -STOP`
  is NOT a valid test of the hang watchdog (it stops every thread); a debug build that sleeps 15 s on
  the main thread during 107 is.
- Build: 1.0.1 (`VERSION_CODE=102 VERSION_NAME=1.0.1`, test key; the integrator sets the release values),
  sha256 dc358f2cababcf93372a55317fdc509ebb155f1f6ff972202ceda3108ff4057e.

## Problem reports (Lumen OS 1.0.1, switched off)
- New package org.z9x.projector.report: ReportActivity (consent > collecting > sending > code), ReportJob (one
  process-wide report, started only by the user's Send / Try again; Cancel and Back stop it and delete its
  files), ReportCollector (summary, props, 30 min logcat of system and image apps, crash/kernel buffers,
  previous-boot pstore/pmsg/z9x_diag when readable, OTA log, DropBox 7 days, exit reasons), Scrubber (known
  Wi-Fi/Bluetooth/device names and serials, plus regexes for e-mail, MAC, IP, SSID, accounts, phone numbers,
  serials, tokens), ReportZip (5 000 000 bytes max, newest lines kept), ReportUploader (one HTTPS POST to
  ro.z9x.report.url, shows the LR-XXXX code).
- Hidden while ro.z9x.report.url is unset or empty (1.0.1 ships it empty). Strings: res/values*/strings_report.xml
  in all 17 languages. Server: gsi/cloud/report-worker. Host test: sh gsi/cloud/report-worker/test/device/run.sh.

## 1.0.1 integration (2026-10-08)
- Manifest: INTERNET, READ_LOGS, DUMP, ACCESS_WIFI_STATE (protection levels checked in the Lineage 21 source:
  normal / signature|privileged|development / signature|privileged|development / normal) and
  `.report.ReportActivity` (not exported, Theme.Z9x.Settings, singleTop). SettingsActivity: "Send a problem
  report" in Diagnostics, only while `ReportActivity.isAvailable()` (an https ro.z9x.report.url).
- Build: `VERSION_CODE=102 VERSION_NAME=1.0.1`, sha256
  6c887ee4d2f64b12b75b61f244908569cc3d1c02739d1e28eb2c9f1cd8490acd (test key; the sign stage re-signs it).

## 1.0.1 owner fix: the living sky as the active screensaver (2026-10-09)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`). Files: `dream/DreamSettings.java`
(applyDefaultsOnce), new `dream/DreamDefaults.java` (the decisions, plain Java), new host test
`test/dream/DreamDefaultsTest.java` + `test/dream/run.sh` (`sh test/dream/run.sh`, 36 checks).
- `applyDefaultsOnce` (SystemFixes.onBoot, "z9x-lamp" thread) has a second marker `dream_defaults_v2`, so it
  runs once more on projectors upgraded over the air (v1 already set). Step 1 replaces v1's "clock while
  nothing was chosen":
  - screensaver on: `screensaver_components` unset, or naming only defaults of earlier images (the v1
    clock `org.z9x.projector/.dream.ClockDream`, BasicDreams Colors, Backdrop, DeskClock) -> Lumen Home's
    `org.z9x.home/.sky.SkyDreamService` (component checked in apps/Z9xHome/AndroidManifest.xml) through
    DreamManager.setActiveDream (Settings fallback). It stays on. Anything else is the user's choice and
    stays; the marker keeps every later choice (the panel's "Use clock", TvSettings) untouched.
  - screensaver off (IdleOwner: `dream_user_off`, the blank standby dream active): stays off (the user's
    "off" and the 6.5 migration's "off" cannot be told apart); the choice "screensaver on" brings back
    (`dream_saved_components`) becomes the sky when it is empty, the standby dream or an earlier default.
    `screensaver_components` is not written then (IdleOwner would read it as "turned on elsewhere").
  - the sky not an installed dream (Home missing or disabled): unset -> clock (the v1 rule), the rest stays.
  - Known limitation: a clock picked by the user before 1.0.1 looks the same as the v1 default and is
    replaced once.
- Step 2 (sleep_timeout 24 h -> 4 h) still runs only with v1 unset (fresh data). Android's on/off switch is
  not touched (IdleOwner keeps `screensaver_enabled` at 1). Every decision is logged: tag Z9xDream,
  "defaults: ...". A failed write leaves both markers unset: tried again at the next boot.
- Lumen Home's own one-shot (`sky/DreamDefault`, marker dream_default_v1) replaces only missing dreams and
  Android defaults and sets `screensaver_default_component`; both converge on the sky.
- Build: sha256 2f74eb95bb2d2064a6d40879ff1ff49abee1a4d5d99f37dff379d9f8cf43087e (test key).
- Not changed here (other files of this app, handoff): the SystemFixes.onBoot comment still says "clock
  as the screensaver"; IdleOwner turns "screensaver on" with nothing saved into the clock (DreamSettings
  .CLOCK; could be DreamSettings.SKY when installed); the panel offers "Use clock" but no way back to the
  sky; the "Screensaver light" setting dims the lamp only for the clock (DreamLamp is driven by ClockDream),
  so the sky runs at the user's lamp level.
- Device checks: upgrade a 1.0.1 projector whose screensaver was the clock -> logcat `Z9xDream: defaults:
  screensaver on, stays on; ... -> sky: true`, TvSettings Screen saver shows the living sky, it starts after
  "Start after"; a user's other dream stays; with the screensaver off it stays off and turning it on in the
  panel brings the sky; a second boot logs nothing new (markers set).

## 1.0.1 final integration (2026-10-09)
- Comments only (same line count, so the APK is byte-identical: sha256 2f74eb95…): the SystemFixes.onBoot
  note and the ClockDream class comment now describe the 1.0.1 default (the living sky; the clock only while
  the sky is not installed, or by the panel's "Use clock").
- Still open for the owner (not changed by the integrator): IdleOwner's "screensaver on with nothing saved"
  fallback is the clock (dead path once dream_defaults_v2 has run, which saves the sky); no panel row back to
  the sky; "Screensaver light" dims the lamp only for the clock (DreamLamp / IGmpf 176 is driven by ClockDream).

## 1.0.1 owner wishes: interface resolution, screensaver light for every dream, sky / clock row, quiet remote after unattended restarts (2026-10-09)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`). No HAL code added, no new HAL call site;
check_hal_callers OK (97 methods).

**Interface resolution 1080p / 2K (default) / 4K** (new package `display`: `UiRes` plain Java,
`UiResolution`, `UiResPanel`)
- Projector settings: new section "Display" with "Interface resolution  2K" and a summary line (18 sp);
  the quick panel's Picture page gets the same row (extension, opens the same screen). Both open a
  full-screen chooser in the Lumen night look (text 36..64 design px = 18..32 sp at every resolution):
  1080p "fastest", 2K (default) "sharp text and smooth menus", 4K "sharpest text; menus and apps may be
  slower", sizes shown, the running one tagged "Now", focus on the wanted one.
- Choosing another one asks "Switch to 4K?": sharper text vs. speed, the projector restarts (about a
  minute) and comes back by itself if the picture does not. "Restart now": a marker {new, previous,
  boot id} is committed (device-protected prefs `z9x_uires`), then `persist.z9x.ui_res` is set and read
  back (a refused write changes nothing and says "The resolution could not be changed"), then
  `StandbyController.noteOrderlyReboot` (now public) and `PowerManager.reboot("z9x-uires")`. A choice that
  already runs (after a fallback the user picks 1080p) is only written: no restart, no question.
- First start in the new mode (another boot id and the new resolution really runs): full-screen
  "Keep this resolution?" with a 15 s countdown that runs only while it is on screen. Keep -> done.
  BACK, "Back to 1080p" or the end of the countdown -> the previous value is written and the projector
  restarts again (no question after that). OK and BACK are ignored for the first second (the remote's
  first key after the restart). Shown only when the screen is free (boot completed, setup done, awake,
  no standby, curtain, screensaver, AK run, eye protection or other panel; checked every 2 s and at
  SCREEN_ON; after 10 min of waiting the new mode stays). Closed by HOME / screen off / an AK run: asked
  again later with the time left (at least 5 s). Another resolution runs (the boot fallback) -> the
  marker goes, no question. No marker (the 2K default after the update) -> never asked.
- Summary: normally what the choice means; after a fallback (`sys.z9x.ui_res.active` differs from the
  wanted value, or, with `active` unset, `sys.z9x.ui_res.why` names a fallback, or our own marker found
  another resolution running at the first start: remembered in `z9x_uires` until the next choice, so it
  also holds if the lane rewrites the persist value) calmly "Now 1080p: 4K did not start properly on this
  projector, so it went back by itself" (no alarm colour; the raw `why` goes to the log and to
  Diagnostics). The display size alone never claims a fallback (an image without the uires lane runs
  1080p while the unset value means 2K). Picking the running resolution after a fallback only writes it
  (the note goes). A restart that fails puts `persist.z9x.ui_res` back exactly as it was.
- Diagnostics: one line "UI resolution: wanted …, active … (why); window WxH @ dpi". Problem reports:
  summary line "ui resolution" and the props vendor.display-size, vendor.mstar.resize.framebuffer,
  vendor.mstar.osd_size, vendor.mtk.gop.fb.*.
- Strings: new `res/values*/strings_display.xml` (21 strings, all 17 locale dirs, hand-written, gen.py
  validates); "1080p", "2K", "4K" and the sizes are not translated.

**Every overlay at 1920x1080 @ 320, 2560x1440 @ 427, 3840x2160 @ 640 dpi** (same 960 x 540 dp)
- Checked: quick panel, tiles, rows, dialogs, notification card, remote prompt, power menu, source
  overlay, recents, play cue, eye mask, curtain, standby, clock dream all size through `Theme.px`
  (width / 1920) or dp, so they keep their on-screen size at every resolution. `Theme` comment says so;
  `EyeGuard`'s mask uses `Theme.scale` instead of its own width / 1920 (same value).
- Manual keystone: it already mapped the 3840x2160 panel corners with the view's own width / height;
  the comments no longer say "1 UI px = 2 panel px" (true at 1080p only: 1.5 at 2K, 1 at 4K; 1 design px
  = 2 panel px at all three) and the layer log adds "1 UI px = N panel px".
- Auto keystone: the pattern bitmap is now an ALPHA_8 coverage mask at the real UI size, capped at the
  3840x2160 panel, drawn in black over a white rect (same picture: DST_OUT for the white data cells is
  "white painted over black"): 2.1 / 3.7 / 8.3 MB at 1080p / 2K / 4K instead of 8.3 / 14.7 / 33 MB ARGB.
  The 109 corners stay in the stock 1920x1080 units scaled to the view; the 109 log line adds the view
  size. The "done" animation no longer allocates a PathMeasure per frame.
- Known: Recents thumbnails stay 480x270 RGB_565 (2 MB cache), a little soft on 4K cards.

**Screensaver light for every screensaver** (`DreamLamp`, `ClockDream`)
- DreamLamp follows ACTION_DREAMING_STARTED / _STOPPED in the persistent process: the living sky (Lumen
  Home's process) and any other dream are dimmed to the "Screensaver light" level 300 ms after the start,
  once per run; not the blank standby dream (screensaver off) and not in standby. ClockDream asks for the
  same run's dim to time its fade-in (`dimForDream`), whichever comes first does it. The run ends at the
  first of the clock's wake / stop and ACTION_DREAMING_STOPPED (`onDreamEnded`): the restore runs then,
  once. The restore rules are unchanged (a level raised meanwhile is kept; nothing is dimmed when the
  user's level is already that low; SCREEN_OFF / lamp-off still restore first); the AK / AF wake and the
  deferred / reconnect restores now treat any dimmed screensaver like the clock.

**Quick panel: "Show  < Living sky | Clock >"** (`DreamPanelRows`, `DreamSettings.makeActive`,
`DreamDefaults.shown`)
- Replaces "Use the clock screensaver"; switches both ways (400 ms commit delay), "—" for another dream,
  disabled while the screensaver is off, only the clock when the sky is not installed. The sky's name is
  read from Lumen Home's dream label (the name TvSettings lists, "Live landscape" / "Живой пейзаж"),
  `dream_sky` ("Living sky" / "Живое небо") only when it cannot be read. New `dream_show`, `dream_sky`
  in all 17 locales; `dream_use_clock` kept unused.

**Remote-lost prompt after unattended restarts** (`RemoteAutoPair`, new `sys/BootReason`)
- At the boot of this persistent process, when sys.boot.reason, sys.boot.reason.last,
  persist.sys.boot.reason or ro.boot.bootreason names z9x-ota (also -rollback, -stuck), z9x-uires*,
  fastboot (rescue "reboot,fastboot", fastbootd "reboot,from_fastboot"), bootloader or RescueParty, the
  remote-lost prompt is not armed for this boot (log `boot after an unattended restart (…)`). A removed
  bond and the next SCREEN_ON re-arm it; normal power-ons are unchanged.

**Tests**: `sh test/uires/run.sh` (new, 73 checks: values, sizes, densities, fallback, restart or not,
the keep question, boot reasons), `sh test/dream/run.sh` (43 checks, + the "Show" row). i18n/gen.py OK
(23 base files x 17 locale dirs).

**Build**: 1.0.1 (102), sha256 8849a95495ec4782065607c78b39c6afc2f4c2fa6515b6c54e08601a25b693eb after the review fixes (test key;
the sign stage re-signs it).

**Handoff (other lanes)**
- uires lane: `persist.z9x.ui_res` must be settable by this app (platform_app, same as
  persist.z9x.cec_wake) and readable by its boot script; publish `sys.z9x.ui_res.active` (1080 | 1440 |
  2160) on every boot and `sys.z9x.ui_res.why` (a fallback should contain "fallback"); a fallback reboot
  should use a reason starting with "z9x-uires" (no remote-lost prompt then).
- The owner's note about "a black background behind the time": in this app only the Clock screensaver
  draws the time on black (by design: lamp dimmed); if Lumen Home's clock or sky was meant, that is the
  Home lane.

**Device checks**
- Settings > Display shows "2K" after the update, no question at that boot. Choose 4K -> "Switch to 4K?"
  -> Restart: `Z9xUiRes: persist.z9x.ui_res = 2160`, `restart for the interface resolution`, boot with
  `sys.boot.reason` reboot,z9x-uires; then "Keep this resolution?" counts 15 -> 0 and goes back to 2K with
  a second restart; Keep instead -> `4K kept by the user`, no question at the next boot.
- At 2K and 4K: quick panel, notification card, power menu, source overlay, recents, remote prompt, eye
  mask, the clock screensaver and the curtain look the same size as at 1080p; `dumpsys SurfaceFlinger`
  GOP line `[0:0|2560:1440|3840:2160]` (2K) / `[0:0|3840:2160|3840:2160]` (4K).
- Manual keystone at 2K / 4K: `layer 2560x1440 … (1 UI px = 1.5 panel px)`, the white edge line on the
  picture edge; auto keystone: `pattern 2560x1440 rendered in N ms` (< 600 ms), the 109 log values stay in
  the 0..1920 / 0..1080 range and the pattern lands on the vendor's quad (if the values look like UI pixels
  at 2K / 4K, report it: the scaling then needs the view size instead of 1920).
- "Ultra 120 Hz" (output timing 1080p120) at 2K / 4K UI: the picture stays correct.
- Living sky with Screensaver light Low: `Z9xLamp: screensaver started (system)`, `dim N->2`; any key ->
  `screensaver ended (screensaver stopped)`, `restore … 2->N`. Blank standby dream: `no dim`.
- Quick panel > All settings > Screensaver > Show: Living sky <-> Clock, TvSettings follows.
- OTA update or "Restart now" for the resolution with the remote asleep: no "Reconnect your remote" a
  minute later (`boot after an unattended restart (reboot,z9x-ota)`); a normal power-on still prompts.

**Review fixes (same day)**
- The restart now waits (at most 4 s, usually well under one) until the uires lane's "pick" took the new
  value over (`sys.z9x.ui_res.want` = the value, and the value no longer in `sys.z9x.ui_res.failed`),
  for "Restart now" and for the way back from "Keep this resolution?". Before, the app rebooted right
  after the write: if the pick had not copied it to /metadata yet, the next start ran the OLD resolution
  (the summary then claimed a fallback) and switched one start later without any question, and a
  re-picked fallback could stay recorded. No wait on an image without the lane (`want` unset). Log
  `uires lane took 4K over in N ms`.
- "Keep this resolution?": closed more than 5 times without an answer (HOME, another window taking
  focus, an AK run, screen off) now counts as no answer and goes back at the next free screen (never while
  the screen goes off). Before, every close re-armed at least 5 s, so something that kept closing it
  could hold the countdown off for ever. A window that cannot be added counts as a busy screen (the
  10 min give-up applies) instead of being retried every 2 s for ever. A new choice in the chooser while
  the question still waits for a free screen answers it (kept) instead of asking later.
- Tests: `sh test/uires/run.sh` 82 checks (+ the lane's pick: want / failed).
- Device check: Settings > Interface resolution > 4K > Restart now: `persist.z9x.ui_res = 2160`, then
  `uires lane took 4K over in N ms` before `restart for the interface resolution`; the next start runs 4K
  (`sys.z9x.ui_res.active` 2160).

## 1.0.1 4K by default: interface resolution after the root cause (2026-10-09, evening)
The device tests showed why the 2K / 4K switch alone could not work: the GOP destination is display size x
(panel / the panel ini's OSD region), and that region (osdWidth / osdHeight 1920 x 1080 in
UD_VB1_16LANE_CSOT_URSA.ini) is fixed when the MI daemon / display stack starts. The platform lane now
overrides it before they start (a generated copy in RAM, never a vendor write) and makes 4K the default.
This lane follows the owner's decision: **4K (3840 x 2160, 1 UI px = 1 panel px) is the default UI, 1080p
stays selectable, 2K is a hidden debug value.** Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`).
No HAL code added, no new HAL call site; check_hal_callers OK (97 methods).

**Setting** (`display/UiRes`, `UiResolution`, `UiResPanel`, `SettingsActivity`)
- `UiRes.DEFAULT` is 2160: an unset `persist.z9x.ui_res` means 4K. 1440 stays a valid value (adb, or set by
  an earlier test build); the app only stops offering it.
- The chooser offers "4K (default) · 3840 × 2160" and "1080p · 1920 × 1080", highest first.
  "2K · 2560 × 1440" sits between them only with the debug property `persist.z9x.ui_res.dev=1` (read
  with SystemProperties.getBoolean semantics; the app never writes it), or while 2K is set or running, so a
  user left on 2K sees where he is and can leave it (the row then disappears) (`UiRes.offered`).
- Texts: 4K "Sharpest text: every pixel of the 4K panel"; 1080p "As on XGIMI: lightest for the
  graphics; text a little softer"; 2K "Test only: the scaling to the panel is not verified". The intro
  says that 4K draws every pixel of the panel while 1080p, as on XGIMI, draws a quarter of them and the
  projector enlarges the picture. The summary reads "4K gives the sharpest text; 1080p, as on XGIMI, is the
  lightest for the graphics. The projector restarts to change it." The fallback note now ends with
  "Choose 4K to try again" (picking it again re-tries it: the lane's pick clears its fallback record).
  The confirm texts speak of the graphics (more / less to draw). 8 strings rewritten in the base and all
  17 locale dirs (hand-written; pt = pt-rBR); `uires_default_fmt` gives "4K (по умолчанию)".
- Unchanged: the "Keep this resolution?" question after a user's change (also for 4K -> 1080p: back to
  4K after 15 s without an answer); no question without a marker, so the 4K default after the update or a
  fresh install never asks. Choosing 4K while the default 4K runs writes nothing.
- The resolution screens scroll with the focus when the column is taller than the screen (a long
  translation with three rows and a fallback note); centred as before otherwise.
- Diagnostics adds "2K offered (persist.z9x.ui_res.dev)" when set; the problem report's "ui resolution"
  line adds dev= and check= (both props were already in props.txt).

**Readiness at 3840 x 2160 @ 640 dpi** (the default now)
- Every overlay (quick panel and its tiles / rows / dialogs, notification card, remote prompt, power menu,
  source overlay, recents, play cue, eye mask, curtain, standby, clock screensaver, the resolution
  screens) sizes through `Theme.px` (width / 1920) or dp: unchanged layout, 2 px per design px. No view
  assumes 1 design px = 1 px. No change needed; comments (`Theme`, `SettingsActivity`) say 4K default.
- Manual keystone: the panel -> UI mapping uses the view's own size, so at 4K `uiX` / `uiY` are the
  identity (1 UI px = 1 panel px); the window is pinned to the real display size (3840 x 2160 under the
  size override); the edge line is 12 UI px = 12 panel px as at 1080p (6 UI px there). Comments only
  (`ManualKeystonePanel`, `KstPoint`); the layer log prints "1 UI px = 1.0 panel px".
- Auto keystone: the pattern is the 3840 x 2160 ALPHA_8 mask (8.3 MB, rendered in the background at the
  first event, released 30 s after the run). New: the unit of the vendor's 109 corners. At 1080p (friend's
  log, 2026-10-08) 109 "86-180,1800-52,11-1016,1899-1034" was exactly half of the 116 actual_result panel
  corners, i.e. the stock 1920 x 1080 units, which the app scales to the view (x2 at 4K). Whether the
  vendor keeps those units once the OSD region is 3840 x 2160 is not measured, so
  `AkPatternSpec.cornerScale` takes a quad that reaches more than 10 % past the stock frame (impossible in
  stock units: the pattern would be off the picture) as UI pixels (1:1), with a log line. At 1080p both
  readings are the same factor, so nothing changes there.
- Recents: the task snapshot is the whole UI frame (3840 x 2160 = 33 MB). Before, createScaledBitmap
  first copied it into a software bitmap (33 MB in the persistent process, 8.3 MB at 1080p); now a Picture
  holding the hardware bitmap is rendered by `Bitmap.createBitmap(Picture, 480, 270, ARGB_8888)` on the
  RenderThread and only the 480 x 270 result is read back (the old copy is the fallback, logged). The log
  says `snapshot task N 3840x2160 -> 480x270 on the GPU in M ms`. Thumbnails stay 480 x 270 RGB_565 in
  the 2 MB cache (a little soft on the 768 x 432 px 4K cards).
- Recents banners: at 640 dpi an xhdpi banner decodes to 1280 x 720 ARGB (3.7 MB, up to 12 while Recents
  is open). They are now decoded for at most 480 dpi (960 x 540, still wider than the card, 2.1 MB);
  1080p (320 dpi) is unchanged. The plain PackageManager banner stays the fallback.

**Tests**: `sh test/uires/run.sh`: UiResTest 109 checks (+ the 4K default, the rows with / without the
debug property, the dev values, 4K <-> 1080p restarts and the keep question) and the new AkUnitsTest
(12 checks: 109 units at 1080p / 2K / 4K, the measured 1080p pair, a strong keystone, the 10 % slack).
`sh test/dream/run.sh` 43 checks. i18n/gen.py OK (23 base files x 17 locale dirs).

**Build**: 1.0.1 (102), sha256 4434c5787dd7a3268a80a05b463a6ca5c9823426c2b483790264d320ec405c08 after the review fixes
(test key; the sign stage re-signs it).

**Handoff (other lanes)**
- uires / platform lane: its `pick` takes only 1080 / 2160 from the app and ignores 1440 (2K = its debug
  file `/metadata/z9x_uires/debug`). The app now follows `sys.z9x.ui_res.want` (review fixes below), so
  a stale 1440 is harmless. With `persist.z9x.ui_res.dev=1` the app still offers 2K, but a pick of it
  ends in "The resolution could not be changed" unless the lane takes 1440 over (decide: the lane accepts
  1440 from the app while `persist.z9x.ui_res.dev=1`, or the dev row stays cosmetic). Keep publishing
  `sys.z9x.ui_res.want` at 'on fs' (2160 for an unset value).
- Fallback records from the 20261009b tests: a `failed.2160` / `failed.1440` written by the old check stays
  final until the user picks that mode again, so the first start of this image would run 1080p and the
  setting would say "Now 1080p: 4K did not start properly ... Choose 4K to try again". If that is not
  wanted after the root-cause fix, the lane should drop failed.* records made by an older image once.
- Kill switch (`ro.z9x.uires.allow=0` / `/metadata/z9x_uires/off`): the app shows it as a fallback note
  (active 1080 differs from the 4K default), as before.

**Device checks**
- After the update (persist unset): Settings > Display shows "4K", no question at that boot;
  `Z9xUiRes: interface resolution: wanted 4K, active 4K`; `wm size` 3840x2160, `wm density` 640; GOP line
  `[0:0|3840:2160|3840:2160]`.
- Chooser: two rows, "4K (по умолчанию) · 3840 × 2160" focused with "Сейчас", then "1080p". `adb shell
  setprop persist.z9x.ui_res.dev 1`: three rows. 1080p -> "Переключить на 1080p?" -> restart -> "Keep this
  resolution?" -> Keep stays at 1080p; no answer goes back to 4K.
- Manual keystone at 4K: `layer 3840x2160 at 0,0, display 3840x2160 (1 UI px = 1.0 panel px)`, the
  white edge line on the picture edge, corners move as at 1080p.
- Auto keystone at 4K: `pattern 3840x2160 rendered in N ms`, `step 109 "..." (1920x1080 units -> view
  3840x2160)`. If the values exceed 2112 / 1188, also `109 corners beyond the stock 1920x1080 frame: taken
  as UI pixels`; either way the pattern must land on the vendor's quad and the AK must succeed (116
  final_state_after_ak success). Also with "Fit to screen".
- Recents at 4K (long-press HOME from an app): `snapshot task N 3840x2160 -> 480x270 on the GPU in M ms`
  (no "full copy" line); banners sharp; `dumpsys meminfo org.z9x.projector` while Recents is open.
- Quick panel, notification card, remote prompt, power menu, standby, the clock screensaver, the curtain
  and the eye mask look the same size as at 1080p, only sharper.

**Review fixes (same evening)** (`UiRes.effectiveWant`, `UiResolution`, `RecentsModel`)
- The setting's wanted value is now the uires lane's `sys.z9x.ui_res.want` when it is published, else
  `persist.z9x.ui_res` as before. The lane ignores a persist value it does not take from the app (1440:
  2K is its debug file only) and its debug file overrides the choice. Before, a `persist.z9x.ui_res=1440`
  left by the 20261009b build (the lane's own case `stale_want_1440`: that start runs the default 4K)
  made the rows say "2K", kept the 2K row offered and focused, and showed the false note "Now 4K: 2K did
  not start properly ... Choose 2K to try again"; with the lane's debug file (2K runs, persist unset) it
  claimed a 4K fallback. Images without the lane (`want` unset) are unchanged.
- "Restart now" no longer restarts when the lane is there and does not take the new value over within
  4 s (`sys.z9x.ui_res.want` / `.failed`): `persist.z9x.ui_res` goes back exactly as it was, the marker is
  cleared and "The resolution could not be changed" is shown. Before, a 2K pick with the debug property
  (ignored by the lane), a choice pinned by the lane's debug file or a fallback record that could not be
  cleared restarted into the same resolution, and the marker then recorded a false "did not start
  properly" note. The way back from "Keep this resolution?" still restarts in any case (it is the way out
  of a mode the user did not confirm; a 4K start restarted before the lane's check passed falls back to
  1080p by itself). Log `uires lane did not take 2K over in N ms (want '2160', failed '')`.
- Recents: the GPU scaling of the task snapshot is used only above 1080p; a 1920 x 1080 snapshot keeps the
  1.0.0 full copy (8.3 MB, proven), so 1080p behaves exactly as before. The 4K device check is unchanged
  (`... 3840x2160 -> 480x270 on the GPU ...`); at 1080p the line ends `by a full copy`.
- Tests: UiResTest 120 checks (+ the lane's want: stale 1440, the debug file, a real fallback, no lane).
- Device checks: with `persist.z9x.ui_res=1440` set by adb (lane ignores it) Settings shows "4K", no
  note, two rows; with `persist.z9x.ui_res.dev=1` choose 2K -> Restart now: about 4 s later "The
  resolution could not be changed", no restart, `persist.z9x.ui_res` back to its old value.

## 1.0.1 final, build 20261009e: visible OFF switch (2026-10-09)
- `ui/ToggleRow.drawRight`: `track.setAlpha(pending ? 0x60 : 0xFF)` replaced the alpha of the switch colour, so
  `Theme.SWITCH_OFF` (0x4DFFFFFF) became a solid white pill with an invisible white knob (and
  `SWITCH_OFF_FOCUSED` a solid black one). Now `track.setAlpha(Color.alpha(col) * (pending ? 0x60 : 0xFF) / 0xFF)`:
  OFF is the theme's 30 % pill again, ON (ACCENT, alpha FF) and pending ON are unchanged. Every switch (quick
  panel, CEC page, game and dream rows) is a ToggleRow; nothing else draws with SWITCH_OFF*.
- APK 95187b20… (VERSION_CODE=102 VERSION_NAME=1.0.1, scratch build dir); against 4434c578… only classes.dex
  differs, only in ToggleRow (the sources with this edit taken back rebuild 4434c578… byte for byte).
- Device check: Projector settings / quick panel, a switch that is OFF: a grey translucent pill with a white knob
  on the left, unfocused and focused.
