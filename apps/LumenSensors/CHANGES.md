# 0.3 (versionCode 3), 2026-10-27: real projector output

The owner read "Interface resolution 1920×1080" as the picture going out at 1080p while a PS5 sent 4K. That
row is the Android interface plane (the Lumen build draws its UI at 1080p); HDMI video goes on its own plane
and reaches the DLP chip at 4K (vendor log: SetOutputRect 0,0,3840,2160; output timing 3840×2160 at 60).

- New row "Projector output" / "Выход на проектор" (Picture and signal, detailed mode): IGmpf 157
  getOutputTimingInfo, a read-only getter (bool + a 40-byte struct, size from the Z9X proxy: 7 x i32
  user_timing_idx, disp_width, disp_height, curr_timing_idx, curr_hres, curr_vres, vfreq, then a 12-byte
  stDlpSupportTiming that is not read), every 10 s with the other picture reads. Shows
  curr_hres × curr_vres at vfreq. Added to the allowlist; the layout matches the vendor's own log line.
- Light source time: the vendor answers usage_time 0 for every light on Lumen (raw reply logged on the
  owner's Z9X), so a 0 now hides the row instead of showing "0 h".
- "Interface resolution" / "Refresh rate" are now "Menu resolution" / "Menu refresh rate"
  («Разрешение меню» / «Частота меню»).
- Detailed mode asks for about 3.3 calls per second (one more 10 s read); the 2 per second cap is unchanged.

# 0.2 (versionCode 2), 2026-10-27: fixes from the first device test

Device test of 0.1 on the owner's Z9X (Lumen OS 1.0.5, HDMI 1 3840x2160p 59.9 Hz SDR): profile, HAL
connection, all three modes and the settings worked; these were measured wrong and are fixed:

- ToF (279) is an active vendor measurement, not a cached value: each call kept the one gmpf thread busy
  for about 0.57 s (5 ToF samples), and the next gmpf call of anyone came about 0.8 s later. 0.1 called it
  every 4 to 7 s. Now at most once per 60 s (also across a quick restart), only while its row is drawn
  (detailed mode, Motion group on), only 5 s after every other first read of a start and after a view
  change, and then in its turn behind every read that was due before it.
- HAL pacing: 0.1 kept the gmpf thread at about 4.8 calls per second in detailed mode (each with vendor
  log lines). New plan in `z9x/Z9xSchedule.java` (plain Java, host-tested): temperatures, fans and the
  196 gate every max(4 s, refresh); light, picture and signal, motion, focus and over-temperature every
  10 s; supply voltage every 30 s; ToF every 60 s; light usage once per start. The first reads of a start
  go out at least 200 ms apart, every later read at least 500 ms apart, so never more than 2 calls in a
  second and no bursts. Steady (refresh 2 s, every group on, 4 sensors and 1 fan): compact 1.25 calls per
  second and graphs 1.5, both on the planned periods. Detailed mode would ask for about 3.2 and gets 2 at
  most (under half of 0.1): a read that goes out more than 2 s late is next due a full period after its
  call, so every read waits the same few seconds more (196 every 4 s, the other temperatures and the fan
  about every 8 s, the 10 s reads about every 14 s, ToF about every 65 s; a model of the pump in the host
  test gives these numbers). The sysfs values still follow the refresh setting.
- Light source time showed "0.0 h": usage_time of IGmpf2 27 was divided by 3600 as seconds. It is whole
  hours (stock PJLink answers LAMP with it as is); range 0..100000 h, shown as whole hours. The raw JSON is
  logged once per start (`IGmpf2 27 light usage`) to confirm the unit.
- "Signal status code 3" is gone: 592 getSignalStatus is a raw vendor enum with no known meaning; it is
  no longer read and no longer in the allowlist.
- No duplicate signal row on HDMI: 662 HDMI info is read only on an HDMI input, IGmpf2 319 video info only
  on the media input; an unknown input reads and shows neither.
- Accelerometer in g with two decimals ("0.06 / -0.01 / 1.00 g", the language's decimal separator);
  gyroscope as raw counts with separators, labelled "Gyroscope (raw)" / "Гироскоп (сырые данные)".
- Opacity really reaches 100 %: system_server capped the not-touchable overlay at alpha 0.8. The HUD
  window is now a trusted overlay (`setTrustedOverlay()`, permission INTERNAL_SYSTEM_WINDOW, granted by the
  platform signature), and the Opacity setting is the panel's own alpha (100 % = opaque). The same
  permission keeps the HUD on screen over windows that hide other apps' overlays
  (HIDE_NON_SYSTEM_OVERLAY_WINDOWS: permission and installer dialogs), where 0.1 was hidden, as for
  Projector's own windows; harmless, the HUD takes no touches.
- The HUD redraws only when something it draws changed (a value text, a level colour, a graph point, the
  layout). Each redraw over the HDMI sideband made hwcomposer recompose and PowerHAL boost, about every 2 s.
  With CPU load, clocks, RAM or network rates on screen a value still changes at almost every publish, so
  the saving shows with those groups off (and not in graphs mode, whose lines move at every publish).
- Host tests: `sh test/run.sh` (885 checks: the read plan, the steady gap and the next-due rule, a model
  of the pump per mode, ToF period, 319 / 662 choice, light hours, 592 refused, g and raw formatting in
  English and Russian, static checks of the fixes).

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
