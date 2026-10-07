# Lumen OS 1.0 — lane L-SYSTEM (image, overlays, RROs, branding, speed)

Owner of: `tools/lumen_v1.sh`, `tools/lumen/**` (new helper dir), `overlay/v1/**` except
`z9x_power*.rc` (L-PROJECTOR; `z9x_poweroff.rc` is the kept v6.2 file), `overlay/apps_v1/` assembly
rules, `apps/Z9xFrameworkKeysOverlay`, `apps/Z9xLineagePlatformOverlay` (new),
`apps/Z9xDeviceConfigOverlay` (new).

## Build

```
Mac:     bash gsi/tools/lumen_v1.sh lint            # inputs only
Mac:     bash gsi/tools/lumen_v1.sh remote          # whole chain (W3): laptop prep -> Mac sign -> laptop image
laptop:  cd ~/z9x/out && bash ../tools/lumen_v1.sh prep | image
Mac:     OUTDIR=... bash gsi/tools/lumen_v1.sh sign
```
Stages: `prep` (laptop: preflight, base check, dexpreopt, patch_tar, member diff, package checks) →
`sign` (Mac only: `tools/sign/sign_tar.py` with `~/.lumen-keys`, `--verify`, unsigned→signed diff) →
`image` (mkfs/fsck.erofs, labels, read-back of every added member, `tools/sign/check_image.py
--variant private`). Only public certificates and the signed tar ever go to the laptop.

## What changed against v6.5.2 (`z9x_v65.sh` + `overlay/v65`)

- **Name/version**: `ro.z9x.version=1.0`, `ro.lumen.version=1.0`; About: `ro.build.display.id =
  Lumen OS 1.0 (AP2A.240905.003, <date>)`, `ro.lineage.display.version = 1.0 (kmuradoff)`, row
  title "Lumen OS version" (all 54 target locales, generated RRO). Incremental suffix `lumen10`.
  Compat props asserted unchanged (`K` lines: system/product model, build id/tags/type, ...).
- **Debloat**: Katniss (Assistant), DfuService, OneTimeInitializer, CalendarSync, GoogleFeedback,
  TvFeedbackConsent, LineageSetupWizard (+RRO), SetupWraith pairing overlay, emulator overlays,
  NfcNci, SecureElement, PrintSpooler, ManagedProvisioning, LocalTransport, ... (61 REMOVE paths).
  Package check forbids Katniss / Play Movies / LineageSetupWizard and requires mediashell
  (Chromecast), SetupWraith, GMS, the classic launcher set and all org.z9x apps.
- **Memory**: lmkd 10/30/700, ART threads 4 (+bg 2 on cpus 2,3) in product props;
  `z9x_speed.rc` swappiness 100; cached processes 16 (framework RRO + DeviceConfig RRO).
- **Power (STR)**: `z9x_base.rc` no longer holds `z9x_no_str` (no post-fs lock, no kill switch:
  the `persist.z9x.allow_str` 0/1 triggers live only in L-PROJECTOR's `z9x_power.rc`, single owner).
  Wake sources: `z9x_power*.rc` (+ `.sh`), installed as delivered; the script refuses wake-source
  values that contradict the decision, any unconditional `z9x_no_str` lock, writes to
  /sys/power/state, reboots, block devices, persist props or HAL calls in those files. `z9x_diag.sh`: vendor-suspend alarm only with allow_str=0; each power capture logs UDC
  state, USB config and PM51 wake sources. `z9x_poweroff` and `z9x_bootinfo` unchanged.
- **Framework RRO** (`Z9xFrameworkKeysOverlay` 1.0, code 100): + `config_customizedMaxCachedProcesses`
  16, `config_defaultAssistant` org.z9x.projector (Recents on long-press HOME), 8 CEC default bools
  (cec spec 3.3). `config_shortPressOnPowerBehavior` 0 etc. unchanged. Shutdown layout: the "Lumen" wordmark
  (review fix 2026-10-07, the same mark as WakeCurtain / power_wordmark).
- **New RROs**: `Z9xLineagePlatformOverlay` (HOME double-tap 0, long-press HOME 3, Lumen OS strings),
  `Z9xDeviceConfigOverlay` (max_cached_processes 16, freezer on).
- **Boot animation**: Lumen "Beam" (`tools/lumen/gen_bootanim.py`, "LUMEN / OS", 352x160 box,
  48+120 frames, 37.8 MB GL peak, 0.50 MB, all STORED); replaces `product/media/bootanimation.zip`.
- **First run / apps**: Home + Setup in `/system/priv-app` (privapp allow-lists shipped), Updater in
  `/system/app`; sysconfig: stopped=false for all 6 org.z9x packages + SetupWraith component-overrides;
  default permissions for Home (RECORD_AUDIO) and the updater (L-OTA file).
- **OTA plumbing** (L-OTA files, byte-identical): `z9x_ota.rc`, `/system/etc/z9x/z9x_ota.sh`,
  replaced `update_verifier.rc`, `ota_props.txt` (ab_ota_partitions without vbmeta, keys, build_id).
  No OTA runs in v1.
- **Dexpreopt** (C10): `tools/lumen/dexpreopt_lumen.sh`, speed, PCL[], GC mode and boot checksums taken
  from the base's own webview odex and checked; boot-image gate against the laptop out tree.
  Linted on the laptop with Z9xProjector 6.5.2: 2.4 s, odex accepted against the reference.
- **APEX** stay signed with the base keys (sign_tar.py policy); hence networkstack /system APKs
  keep the test key (shared UID with an APK inside an APEX) and seinfo keeps the test entries that
  APEX APKs still use. `lumen_checks.py signed` follows that policy from sign_tar's report.
- **MTK Codec2**: 6 stock libs (libcodec2store.so dropped: never loaded, PLAN X6).

## Dry run (Mac, 2026-10-07, base = system_tv_v4.tar sha256 4e1bc168..., partial APK set)
prep OK (59 added, 174 removed, 10 changed; compat props identical; package checks) -> sign OK
(sign_tar.py: 63 APKs re-signed, verify OK, unsigned->signed diff OK) -> image: mkfs/fsck OK, labels
OK, 64 members read back identical, check_image.py: only "Z9xUpdater.apk missing" (not delivered yet).

## Not done here / for W2-W3
- Projector, Home, Setup, Updater APKs and `z9x_power*.rc` come from the other lanes; until they are
  in `overlay/apps_v1` / `overlay/v1` the script only runs with `ALLOW_MISSING_APKS=1
  ALLOW_NO_POWER_RC=1` (outputs named `*_partial*`, refused by sign/image).
- i18n: no new translatable strings in this lane (the Lineage RRO keeps the target's own
  translations, only "LineageOS" -> "Lumen OS"), so no `i18n/pending/system` files.

## Review fixes (2026-10-07, round 3)
- **USB debugging**: system_ext build.prop `ro.adb.secure=0` -> 1 and `persist.sys.usb.config=adb` ->
  none (patched by lumen_v1.sh), repeated in product_prop_v1.txt (read last). After the wipe adb is off;
  when the owner enables it, TvSystemUI's TvUsbDebuggingActivity asks for the RSA key (the framework RRO
  of the base points there). check_image.py asserts the effective ro.adb.secure=1.
- **Parse cache on**: `pm.boot.disable_package_cache` removed (now an 'X' line). patch_tar.py --mtime gives
  every added / --sub'd member, --mkdir dir and parent dir of an added file the build time; sign_tar.py
  does the same for re-signed APKs and their directories; mkfs.erofs runs with --mkfs-time, so the image
  keeps tar mtimes (verified with erofs-utils 1.9 on the laptop). BOOT_COMPLETED latency before/after:
  to be measured on the device (read-only logcat) - not measurable here.
- **sysconfig**: initial-package-state stopped=false also for com.google.android.tv.remote.service,
  com.android.vending, com.google.android.gms, com.google.android.gsf (first boot after the wipe);
  component-override disables LineageParts' stats (ReportingServiceManager, ReportingService,
  StatsUploadJobService): Lumen OS never reports to stats.lineageos.org.
- **Boot animation**: mark "Lumen" (mixed case) like the curtain, Setup and the shutdown dialog;
  bootanimation.zip regenerated (481321 bytes, 37.8 MB GL peak).
- **Framework RRO**: tv_wake_on_one_touch_play default OFF (cec spec guard 8); shutdown dialog "Lumen".
- **CEC init**: z9x_power_cec.rc + z9x_power_wake.sh (L-PROJECTOR) add the root wake-reason helper.
- lumen_v1.sh: shlint no longer clobbers the caller's loop variable (made every z9x_power*.sh fail lint);
  header documents that only the PRIVATE variant exists (public variant not implemented).

## Review fixes, round 4 (2026-10-07)
- **Play Movies out of the classic launcher's partner data** (brand spec 18): overlay/v1/LineageCustomizer.apk
  = the base LineageCustomizer (org.lineageos.tvcustomizer, Apache-2.0) with the four
  com.google.android.videos lines removed from res/raw/configuration.xml (favourites, apps row, channel
  order, channel quota); every other member byte-identical (classes.dex unchanged, so the base odex/vdex
  stay valid). Made on the Mac by tools/lumen/gen_customizer.py make (test platform key), checked in
  prep by gen_customizer.py check against the base tar, replaced like bootanimation.zip, re-signed with
  the release platform key by the sign stage.
- **z9x_diag USB copy is opt-in**: only a stick that already has a z9x_diag_logs/ folder, or any stick
  with persist.z9x.diag_usb=1 (developer prop, default unset). lumen_v1.sh lint asserts it.
- lumen_v1.sh remote, step 6: delta rsync (--inplace --no-whole-file) into gsi/build/lumen_v1.img
  (seeded from the previous image or system_tv_v652.img), checksum against the laptop, writes
  build/SHA256SUMS_lumen_v1.txt.
