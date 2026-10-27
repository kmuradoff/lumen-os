# 0.1 (versionCode 1), 2026-10-27: first version, not yet device-tested

New app `org.lumen.sensors` ("Sensors" / "Датчики"): an on-screen overlay (HUD) with every temperature and
sensor value that can be read on the projector, over whatever plays.

- Modes compact / detailed / graphs; four corners inside the 5 % safe area; sizes 0.8 / 1 / 1.25; opacity
  40..100 % (text shadow below 80 %); refresh 1 / 2 / 5 s; nine groups, each can be switched off. Values
  amber when warm, red when hot (one threshold table, `core/Levels.java`), faint while no fresh values
  arrive. The HUD fits the screen (and right of the settings panel while it is open): taller columns
  first, then a smaller size; a group that goes on in the next column repeats its header.
- Generic sources (any device): thermal zones and cooling devices, CPU load (total, per core) and clocks,
  Mali GPU clock and load, RAM and zram, network rates, Wi-Fi signal / link / band, display mode and HDR
  support, uptime. Kernel files are kept open and read with pread at offset 0.
- Z9X profile (`ro.lumen.device` unset and model `G0082`; board `mt9952` only when no model property is
  set): XGIMI HAL values through a read-only allowlist (IGmpf 438, 439, 339, 435, 437, 177, 175, 196, 279,
  584, 266, 696, 620, 662, 592; IGmpf2 51, 241, 252, 25, 27, 68, 319, 22), at most 5 calls per second, only
  for values the current mode draws; a fresh 196 (light on) gates every other read; failures hide the row
  and stop after 3, slow answers (over 2 s) hide it until the next good read. Banned codes (298, 306, 431,
  432, 440, 589, 121, 263, 275, every setter) are refused in code and listed in the host test. Other
  devices (Play 6) get no XGIMI HAL at all.
- One foreground service (specialUse) owns one non-focusable TYPE_APPLICATION_OVERLAY window, added once;
  hidden and no polling while the screen is off or during Projector's lamp-only standby. Optional start at
  boot.
- Settings activity in the Projector panel look (D-pad, live changes, groups that exist on this device),
  with a hint about the remote's app keys; every launch lands on "Show overlay".
- Strings: English and Russian. Other languages come through apps/i18n when the app ships in the image.
- Host tests: `sh test/run.sh` (726 checks, including static checks of the HAL path and the device separation).

Not verified on a device yet: everything. Most XGIMI readings (all but 438, 177, 175, 196, 696, 620, IGmpf2 51)
have never been seen through these calls; the first device test should record them (README.md).
