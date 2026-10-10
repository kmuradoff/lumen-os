# Z9xProjector — HDMI-CEC phase 1 (W2 lane CEC, Lumen OS 1.0, versionCode 100)

Spec: research/v7/cec/SPEC.md + PLAN C11/C14/C15, adapted to real STR. New package `org.z9x.projector.cec`
(no HAL call anywhere in it; only HdmiControlService @SystemApi, PowerManager, two init properties).

- **CecSettings**: one owner per row. Framework (HdmiControlManager): HDMI-CEC master, **"Turn on with an HDMI
  device" = tv_wake_on_one_touch_play** (now user-configurable, default ON in Z9xFrameworkKeysOverlay, so the
  vendor HAL's enableWakeupByOtp agrees with cec0), send standby on sleep, soundbar (system audio + volume control).
  tvinput (provider): switch on One Touch Play, remote control, internal source on exit. Ours (prefs z9x_cec):
  soundbar on with the projector, night guard (Off / 22–7 / 23–7 / 0–7 / **1–7 default**).
- **PM51 cec0** (overlay/v1/z9x_power_cec.rc): before every sleep (SCREEN_OFF) `sys.z9x.cec_arm` = 1 only with CEC on,
  wake on, setup complete (C14), no storm pause, outside the night hours; 0 at ACTION_SHUTDOWN (and the framework OTP
  wake is switched off until the next start: no CEC cold boot). `persist.z9x.cec_wake` mirrors master && wake
  (boot default, z9x_power.rc). A 2 s "z9x:cecstandby" wakelock after SCREEN_OFF lets the framework's `<Standby>` out.
- **Wake from STR**: SCREEN_ON checks the wake source (PowerManager last wakeup = HDMI, or
  /sys/mtk_pm/wakeup_reason/name "cec*" changed since the sleep); a CEC wake the guards refuse (night hours reached
  during the sleep, …) goes straight back to STR via `StandbyController.enter("cec_veto: …")` before the lamp-on.
- **Wake from the lamp-only standby** (STR blocked): tvinput's `cec_wake` call → `CecPolicy.onWakeRequest` (source
  devices only + guards) → `StandbyController.exit("cec_otp:<name>")`.
- **Unattended return**: 90 s after a CEC wake with no remote key and no HDMI picture, a card "Turned on by an HDMI
  device … Stay on" (30 s), then `StandbyController.enter("cec_unattended")`. **Storm limit**: > 3 unwanted CEC wakes
  in 30 min pause CEC wake until the next remote key (one card on the next wake).
- **Going dark**: STR = Android sleep, so the framework sends `<Standby>`. The lamp-only standby sends it itself
  (`onLampOnlyStandby`: TV takes the active source, 300 ms, directed `<Standby>` to every device, AVR first).
- **Console off** (phase 1): the framework's goToSleep(HDMI) is a foreign sleep → PowerPolicy's sleep path (lamp off,
  STR). Logged. Phase 2 (services.jar hook) deferred (C11).
- **Waking up**: 4 s later, soundbar on (`<System Audio Mode Request>`, then once UCP Power On) if enabled and an
  audio system is known but system audio is off.
- **Quick panel**: All settings › "HDMI devices (CEC)" page (CecPage): the rows above, connected devices (OK opens a
  source's child input), the projector's HDMI name, "HDMI input options" (tvinput's SettingsActivity).
- Hooks in existing files: App (install, SCREEN_OFF/ON), StandbyController (lamp-only standby → onLampOnlyStandby,
  exit → onStandbyExit, noteUserActivity → onUserKey, shutdownInFlight public, javadoc), StandbyStateProvider
  (`cec_wake`), QuickPanelController (row → CEC page), PowerPolicy (v6.5 one-shot OTP switch-off removed: it would
  undo the new default).
- Strings: res/values/strings_cec.xml (English); 16 languages in apps/i18n/pending/cec (W2 merge:
  `merge_pending.py --write cec && gen.py --write`; simulated: only adds values-*/strings_cec.xml, validates, compiles).

Not tested on the Z9X (no CEC hardware, adb not connected). Device checks: SPEC T0–T15, plus: does a PM51 CEC wake
from STR inject KEY_POWER (or does the framework OTP wake bring Android up), what /sys/mtk_pm/wakeup_reason/name says
after an STR wake, and that the vendor HAL keeps cec0 as written.
