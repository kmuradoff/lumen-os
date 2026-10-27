# Lumen Sensors (`org.lumen.sensors`)

"Sensors" ("Датчики") shows every temperature and sensor value that can really be read on the
projector, as an overlay (HUD) on top of whatever plays: a film, HDMI input, the home screen. Keys always
stay with the app below. Version 0.2 (versionCode 2), Android 14 / Lumen OS, XGIMI Z9X first.

## What it shows

Values are grouped; each group can be switched off, and a value (or a group) that cannot be read on this
device is never shown, not even greyed out.

| Group | Values | Source |
|---|---|---|
| Temperatures | SoC; system, air inside, DMD chip, LED (one row when the LED sensors agree), other XGIMI sensors; other thermal zones; overheat flag | `thermal_zone*`; XGIMI HAL 438, 439, 241 |
| Cooling | fan mode, fan speed (the vendor's commanded PWM speed, not RPM); kernel cooling devices named by type (CPU throttling, CPU cores off, picture throttling, GPU throttling, else "Cooling device N") | XGIMI HAL 435, 437; `cooling_device*` |
| Light | brightness level, color temperature, Boost, LED power, LED currents, light source hours (whole hours), supply voltage | XGIMI HAL 177, 175, IGmpf2 51, 25, 252, 27, 68 |
| Processor and graphics | CPU load (total, per core), CPU clock and clock limit (per cluster), GPU clock and load | `/proc/stat`, cpufreq, Mali module parameters |
| Memory | RAM in use, zram swap and its memory | `/proc/meminfo`, `/sys/block/zram0/mm_stat` |
| Network | download / upload rate; Wi-Fi signal, link speed, band (Wi-Fi only) | `/sys/class/net/IFACE/statistics`, WifiManager |
| Picture and signal | input, video HDR, HDMI signal (HDMI input) or video (media input), output frame rate; interface resolution, refresh rate, HDR support | XGIMI HAL 696, 620, 662 or IGmpf2 319, IGmpf2 22; DisplayManager |
| Motion | tilt, accelerometer (in g), gyroscope (raw counts), distance to the wall (once a minute), focus motor step | XGIMI HAL 584, 279, 266 |
| System | uptime | SystemClock |

Modes: **compact** (one strip with up to 7 key values: SoC, DMD, LED, air, fan, CPU, GPU, RAM, clock),
**detailed** (all values of the enabled groups, several columns when one would be taller than 70 % of the
screen; a group that goes on in the next column gets its header again) and **graphs** (sparklines of the
last ~2 minutes for each temperature and the CPU / GPU load, with min and max). Values are amber when warm
and red when hot; the thresholds are one table in `core/Levels.java` (SoC 75 / 85, DMD 65 / 75, LED 60 / 70,
air 40 / 45, board 60 / 70 °C).

The HUD always fits the screen: it may use the width between the safe margins (while the settings screen is
open, the width right of its panel, for every corner, so it never covers the panel). When it would be wider,
the columns first get the full safe height, then everything shrinks to the largest size that fits (down to
half). Opacity is the panel's own (100 % = opaque: the window is a trusted overlay, so the system does not
cap it at 80 % as it does for other not-touchable overlays). The permission for that,
INTERNAL_SYSTEM_WINDOW, also keeps the HUD on screen over windows that hide other apps' overlays
(HIDE_NON_SYSTEM_OVERLAY_WINDOWS: permission and installer dialogs, some Settings screens), as for
Projector's own windows; harmless, since the HUD takes no touches. Below 80 % opacity the texts get a soft
dark shadow, so they stay readable over a bright frame. When
no new values came for max(3 x refresh, 5 s) (the sampler thread waits on a busy vendor HAL call), the values
turn faint until fresh ones arrive.

Settings (the launcher entry, Lumen look of the Projector panel): Show overlay, Mode, Position (four
corners inside the 5 % safe area), Size (0.8 / 1 / 1.25), Opacity (40 / 60 / 80 / 100 %), Refresh (1 / 2 / 5 s),
Start at boot, and one switch per group that exists on this device. Every change applies live. From the
remote: Projector → Remote keys can give the iQIYI or bilibili key to Sensors; the key opens the settings
(always on the first row, also when the screen was left open in the background) and OK there switches the
overlay.

## How it works

- `core/`: device-neutral. `Sample` (one value), `Registry` (all samples, one lock), `Sampler` (ONE
  HandlerThread "lumen-sensors" for every source; publish = level + formatted text + graph point),
  `Fmt` (allocation-free formatting), `Parse` (kernel file parsers), `Levels` (thresholds),
  `DeviceProfile` (which profile), `SysFile` (fd kept open, pread at offset 0).
- `sources/`: generic sources for any device (thermal, CPU, GPU, memory, network, display, uptime).
- `z9x/`: Z9X only. `Z9xProfile` (detection: `ro.lumen.device` unset and model `G0082` in
  `ro.boot.xgimi.modelname` / `ro.xgimi.modelname`, every one that is set; board `mt9952` decides only when
  both are empty, since another XGIMI model on that board may number IGmpf2 differently; zone0 is the SoC),
  `Z9xSchedule` (the read plan: period per HAL code, gap, input and ToF rules; plain Java, host-tested)
  and `Z9xHalSource` (runs the plan). Any other device, the Play 6 included (`ro.lumen.device=play6`),
  runs the generic sources only: no XGIMI HAL at all. The host test checks that only `hal/` and `z9x/`
  name the HAL classes and only `Z9xProfile` creates the HAL source.
- `hal/`: `HidlCaller` (a copy of `apps/common/hal/HidlCaller.java`, only the package differs; the host
  test checks that), `GmpfAllowlist`, `GmpfStructs`, `Z9xGmpfReader` (read-only typed client).
- `HudService`: foreground service (specialUse) that owns the HUD window while "Show overlay" is on;
  `BootReceiver` starts it after boot when "Start at boot" is on. `SettingsActivity` + `ui/SettingsView`.
- `ui/HudView`: ONE Canvas-drawn view; layout rebuilt only when the set of rows or a setting changes, no
  allocation per frame, tabular digits. It redraws only when something it draws changed (a value text, a
  level colour, a graph point, the layout): over the HDMI sideband each redraw makes hwcomposer recompose
  (`mmSidebandPrepare ... skip here!` in the log) and PowerHAL boost.

Fast on weak hardware: nothing runs while the overlay is off (the sampler thread quits 5 s after the last
owner), while the screen is off, or during Projector's lamp-only standby
(`org.z9x.projector.action.STANDBY_CHANGED`, extra `standby`, permission
`org.z9x.projector.permission.STANDBY_STATE`; initial state from `content://org.z9x.projector.standbystate`
call `standby_state`, through an unstable provider client). The HUD window is added once (GONE until the
state is known) and only hidden (view GONE, rows emptied so a later show draws nothing old) in those states,
so Projector's AK pattern, quick panel and notice card, added later, stay above it. Kernel nodes that are
missing at a start (cpufreq, Mali, cooling devices load at boot on the Z9X) are looked for every 30 s, at most
10 times. A source that throws is logged once and never ends the sampler loop.

## XGIMI HAL rules (Z9X)

- Read-only allowlist, per interface (`hal/GmpfAllowlist.java`, enforced before every transact):
  IGmpf 438, 439, 339, 435, 437, 177, 175, 196, 279, 584, 266, 696, 620, 662;
  IGmpf2 51, 241, 252, 25, 27, 68, 319, 22. Anything else is refused before it is sent. 592 getSignalStatus
  was read by 0.1 and showed "Signal status code 3", a raw vendor enum with no known meaning: no longer read.
- Banned, never called (listed in the code and the host test): 298 / 306 (the focus listener; Projector's
  auto keystone and autofocus depend on it), 431 extendCmd, 432 / 440 / 589 listeners, 121 zoom session,
  IGmpf2 263 motion calibration writer, 275 ambient light, every setter.
- Budget (`z9x/Z9xSchedule.java`): the vendor serves every gmpf call of every app on ONE thread
  (gmpf_main) and logs each call, so Projector's keystone, focus and brightness calls wait behind ours.
  0.1 asked for 6.3 calls per second in detailed mode and got about 4.8 (device test). Now the shortest
  periods are: temperatures (438, 439 per sensor), fans (435, 437) and the screen check (196) every max(4 s,
  refresh); light (177, 175, IGmpf2 51, 25, 252), picture and signal (696, 620, 662 or IGmpf2 319, IGmpf2
  22), motion and focus (584, 266) and over-temperature (IGmpf2 241) every 10 s; supply voltage (IGmpf2 68)
  every 30 s; ToF (279) every 60 s at most; light usage (IGmpf2 27), fan ids and sensor indexes once per
  start. The first reads of a start go out at least 200 ms apart (a few seconds of probing), every later
  read at least 500 ms apart: never more than 2 calls in a second, and no bursts. A value is read only
  while the HUD draws it in the current mode (compact draws only ranked values, so e.g. 438 and the
  unranked sensors are not read there). No HAL call in the first 60 s after boot. The sysfs values (SoC
  zone, CPU, GPU, memory, network) follow the refresh setting.
- Steady HAL calls per second, every group on, refresh 2 s, the owner's Z9X (4 sensors, 1 fan, HDMI,
  no 68 on mains): compact = 5 / 4 s (196, 3 x 439, 437) = 1.25 (1.0 at refresh 5 s); graphs = 6 / 4 s
  = 1.5; both keep the periods above. Detailed asks for 8 / 4 s (196, 438, 4 x 439, 435, 437) + 12 / 10 s
  (177, 175, 51, 25, 252, 696, 620, 662, 22, 584, 266, 241) + 1 / 60 s (279) = about 3.2 and gets 2 at
  most: a read that goes out more than 2 s late is next due a full period after its call, so every read
  waits the same few seconds more (196 every 4 s, the other temperatures and the fan about every 8 s, the
  10 s reads about every 14 s, ToF about every 65 s). The host test repeats the sums and runs a model of
  the pump that gives these numbers.
- ToF 279 getTofDistance is not a cached read: each call makes the vendor (ProjectorFocusManagerService,
  on the one gmpf thread) take an active 5-sample ToF measurement, about 0.57 s, and the next gmpf call of
  anyone came about 0.8 s later. 0.1 called it every 4 to 7 s. Now: only while its row is drawn
  (detailed mode, Motion group on), at most once per 60 s (also across a quick restart), never with the
  first reads of a start: it waits until every other first read is done, then 5 s more (also 5 s after a
  view change), and then takes its turn behind every read that was due before it.
- 196 is the gate: no other read goes out without a 196 reading from this round saying the light is on.
  With the light off (or 196 failing) only 196 is read, once per round; if 196 fails 3 times nothing is
  read until the next start. 196 itself is read only right before another read is due.
- A failed call hides its row; 3 failures in a row stop that value until the next start. A call that
  answers but takes more than 2 s (the vendor mutex was busy) hides its row until the next good read and
  is not counted as a failure; 5 slow calls in a row stop it. The input (696) picks the signal read: 662
  HDMI info only on HDMI 1 / 2, IGmpf2 319 video info only on the media input (on HDMI both gave the same
  "3840×2160p 59.9 Hz" row); a failed or unknown input reads and shows neither. Struct replies are read
  with their exact size (584: 14, 252: bool + 16, 662: 16, 319: bool + 12); a size mismatch only makes
  that value unavailable. Never on the main thread.
- Units: the accelerometer is shown in g (4096 counts per g; z reads about 4092 lying flat), the
  gyroscope as raw counts (no known scale). IGmpf2 27 light_list[].usage_time is read as whole HOURS:
  stock PJLink (settings `LampCommand`) answers the LAMP query, cumulative hours, with it as is. Stock
  System info divides it by 3600 as seconds, as 0.1 did ("0.0 h"); the raw JSON is logged once per start
  (`IGmpf2 27 light usage`) to confirm the unit on the device. Outside 0..100000 h the row is hidden.
- Probing details: sensor index 0 is skipped (438 is the same slot); index 9 (CPU) only when no thermal
  zone gives the SoC temperature (each 439(9) adds a sample to the vendor's CPU average); 339 is applied
  only when it marks at least one sensor valid (its meaning is unverified).
- While only the settings screen is open (overlay off), the HAL is probed once so the group list is right,
  but not polled. The sensor and fan probes run to their end whatever the mode or the groups.

Device test of 0.1 (2026-10-27, HDMI 1 3840x2160p 59.9 Hz SDR): plausible readings of 439 (sensors 1,
2, 5, 12), 435, 437, 177, 175, IGmpf2 51, 25, 252 (R G B 755 / 800 / 839), 584, 266, 279, IGmpf2 241, 662,
IGmpf2 319 (same signal as 662) and IGmpf2 22; 339 marked no sensor valid (not applied). Still open: the
unit of IGmpf2 27 (see above), IGmpf2 68 (absent on mains?), the axes of 584 and the gyroscope scale,
the Mali parameter units.

## Build and test

Host tests (parsers, HAL structs, allowlist, the Z9X read plan, formatting, levels, profile, static checks
of the HAL path, the device-test fixes and the device separation over every source file; plain Java):

    sh test/run.sh

Build with the release platform key, into a scratch directory (never into the repo):

    KEY_PK8=$HOME/.lumen-keys/platform.pk8 KEY_PEM=$HOME/.lumen-keys/platform.x509.pem BUILD_DIR=<scratch>/b \
      sh apps/build_apk.sh apps/LumenSensors <scratch>/LumenSensors.apk

The default sdk key is the public AOSP test key: on a release image such an APK gets no signature
permissions (no overlay, no standby broadcast). The Play 6 has its own platform key
(`~/.lumen-keys/play6/`), and the XGIMI HAL source does not run there anyway.

Test install on a Z9X (not persistent, so a plain install works; versionCode 2 stays below the image's):

1. In a separate step, check that nothing is playing (`dumpsys window | grep mCurrentFocus`, audio
   playback); never install while the owner watches.
2. `adb install -r LumenSensors.apk`, then check `appops get org.lumen.sensors SYSTEM_ALERT_WINDOW` and
   `dumpsys package org.lumen.sensors | grep -e STANDBY_STATE -e INTERNAL_SYSTEM_WINDOW` (both granted).
3. Open "Sensors", switch the overlay on, and check: each mode over HDMI, Lumen Play (tunnelled 4K) and an
   HDR title; a manual AK run and the quick panel stay above the HUD; lamp standby hides it; `logcat -s
   LumenSensors Z9xGmpf` shows the probe results and no refused code. Note the first real values of the
   open items above.
4. Since 0.2: no "has a system alert window ... setting alpha to 0.80" line for org.lumen.sensors in
   logcat, and 100 % opacity looks opaque; ToF (279) at most once a minute in the vendor log; at most 2
   gmpf calls in any second in detailed mode (about 2 per second there) and about 1.25 per second in
   compact mode. The HUD now also stays on screen over windows that hide overlays (a system permission
   dialog, the package installer): check that this is acceptable. Redraws: in detailed or compact mode
   with the Processor and graphics, Memory and Network groups switched off and the projector idle, no
   `mmSidebandPrepare` line between two value changes (the uptime changes once a minute). With those
   groups on, CPU %, clocks, RAM and rates change at almost every publish, so a redraw about every refresh
   is expected; in graphs mode the sparklines move at every publish too.

SELinux is permissive on Lumen; reads of `sysfs_thermal`, `proc_stat`, `sysfs_net`, `sysfs_zram` and
module parameters work but log an audit line each. An enforcing build would need a small policy rule.

## Shipping it in the image later

- i18n: add a `sensors` target to `RES` in `apps/i18n/gen.py`, `@sensors/strings.xml` sections in
  `apps/i18n/langs/<locale>.txt` (all 17 locale dirs; values-pt-rBR = pt), `gen.py --write`. Only once
  every locale is complete: `z9x_gate.sh` runs `gen.py --check-only`. Today only values (English) and
  values-ru (hand-written) exist.
- publish: `docs/repo/publish_audit.py` publishes `apps/(Z9x|Play6)*` only. Extend its three app rules
  (build/ excluded, *.apk excluded, own app source) to `LumenSensors` before the next `export_repo.py`
  run, or the export stops on REVIEW rows.
- image: an entry `('LumenSensors', 'org.lumen.sensors', 'system/app/LumenSensors', 'app')` in
  `tools/lumen/lumen_checks.py` APKS (not persistent, not required) and the APK in `overlay/apps_v1`
  (+ PINS), or `patch_tar.py --add` lines (APK + oat odex/vdex) as in the 1.0.6 build script. No privapp or
  default-permissions XML is needed.
- gate: `z9x_gate.sh` hashes `apps/Z9x*`; a `Lumen*` directory is not a gate input today. Shipping changes
  overlay/apps_v1 and tools/lumen, so the Z9X gate needs the owner's OK to re-record
  (`z9x_gate.sh --full --record`).
- brand: add the app to `tools/brand/gen_drawables.py` APPS (and a glyph in `lumen_brand.py`), then
  `common/brand/sync.sh`; the icon and banner here are hand-made in the same palette until then.
- `apps/check_hal_callers.py` covers Projector only; this app's equivalent is the allowlist plus the static
  checks in `test/run.sh` (one transact path, no HidlCaller helper anywhere in `src/`, the HAL classes only
  in `hal/` and `z9x/`, HidlCaller copy identical).
- versionCode: the image build must be above every test build (test builds are 2 so far).
