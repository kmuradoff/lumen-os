# 1.0.1, no-Google edition (2026-10-09, docs/NOGMS_PLAN.md)

- `tools/lumen_v1.sh GMS=0` (only with `VARIANT=public`): name `lumen_v1_public_nogms`, the 26 paths of
  `tools/lumen/nogms_remove.txt` removed (checked in preflight: 26, no duplicate, no overlap with the
  static REMOVE), `z9x-sysconfig-nogms.xml` as `system/etc/sysconfig/z9x.xml` (preflight: exactly the main
  file's org.z9x states and LineageParts overrides), no LineageCustomizer replacement, `ro.z9x.gms=0`,
  `update-public-nogms.json`, build id suffix ending in 'np', `.info` gms=, `check_image.py --gms`, base
  check `ro.control_privapp_permissions=disable` (system_ext). `release_files` only for GMS=0.
- `privapp-permissions-z9xhome.xml`: + INSTALL_PACKAGES (Install from USB).

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

## Lumen OS 1.0.0 (integration, 2026-10-08)
- **Version**: `VER=1.0.0` in lumen_v1.sh drives everything: `ro.z9x.version=1.0.0`, `ro.lumen.version=1.0.0`,
  About `Lumen OS 1.0.0 (AP2A.240905.003, <date>)` and `1.0.0 (kmuradoff)`, build id `lumen-1.0.0-<date>`,
  `ro.z9x.version_code=10001`. New scheme (tools/ota/image/ota_props.txt, docs/ota.md): version_code =
  major*10000 + minor*100 + patch + 1, so 1.0.0 is offered over the 1.0 test builds (10000,
  `lumen-1.0-<date>`); the preflight refuses a version_code / build id / prop that does not follow VER, and
  the spec now asserts `ro.z9x.version_code` in the product build.prop. tools/ota/make_manifest.py uses the
  same formula. Incremental suffix unchanged (`lumen10`, internal only).
- **APK set**: all lanes merged (Lumen Home direction D with the living sky and its screensaver, remote
  pairing as the first setup step + remote-lost prompt + one Keystone tile, 2A brand mark). Every app and
  RRO of ours is versionName 1.0.0 / versionCode 101 (lumen_checks.py VERSION_NAME). App icons (and
  banners where an app had one) are the brand drawables of apps/common/brand. Z9xTvInput / Z9xAirPlay
  re-pinned in apps_v1/PINS.sha256 (their sources reproduced the 1.0 pins before the icon change).
- **Framework RRO**: `config_dreamsDefaultComponent` = org.z9x.home/org.z9x.home.sky.SkyDreamService (the
  TvFrameworkOverlay default, BasicDreams Colors, is not in the image). Seeds Settings.Secure
  screensaver_default_component on a fresh userdata; the active screensaver stays Z9xProjector's choice
  (clock, set once on the first boot) or the user's. The sky screensaver stays enabled in classic-launcher
  mode (`org.z9x.keep_in_classic` on the service).
- **Default permissions**: Lumen Home + READ_TV_LISTINGS (z9x-home-default-permissions.xml; the preflight
  assert follows). Applied on a fresh userdata only (the fingerprint does not change between Lumen builds).
- **Boot animation**: "Aperture" from tools/brand/gen_bootanim.py (894,696 bytes, 152x248 box, 240 frames,
  36.2 MB GL peak); the 1.0 "Beam" zip is kept as bootanimation.prev.zip (never installed) and
  tools/lumen/gen_bootanim.py refuses to overwrite the shipped zip.

## Lumen OS 1.0.1 (integration, 2026-10-08)
- **Version**: `VER=1.0.1` in lumen_v1.sh: `ro.z9x.version=1.0.1`, `ro.lumen.version=1.0.1`, About `Lumen OS 1.0.1
  (AP2A.240905.003, <date>)` and `1.0.1 (kmuradoff)`, build id `lumen-1.0.1-<date>`, `ro.z9x.version_code=10002`
  (tools/ota/image/ota_props.txt changed in the same edit; the preflight checks both against VER). Every app and
  RRO of ours: versionName 1.0.1 / versionCode 102 (lumen_checks.py VERSION_NAME; gen_lineage_rro.py too).
- **Boot rescue (L-OTA)**: `system/etc/z9x/z9x_rescue.sh` (0755) + `system/etc/init/z9x_rescue.rc` (0644), label
  system_file, byte-identical to tools/ota/image (verify_tar F lines, check_image.py OTA_FILES); base check
  refuses an existing z9x_rescue.rc; spec asserts `ro.z9x.rescue=1`. Preflight: first line, shlint, the rc
  execs `z9x_rescue.sh count`, no block-device / vbmeta write, and its only `sys.powerctl` use is
  `setprop sys.powerctl reboot,fastboot` (the gate's 'fastboot' word ban does not apply to it, HANDOFF.md).
  The rewritten gate (z9x_ota.sh/.rc, update_verifier.rc) is copied unchanged as before.
- **shlint** also runs `ksh -n` where ksh exists (the device shell mksh is a ksh; the 1.0.1 OTA review found a
  pattern only ksh rejects). Its output is shown only on a failure (ksh -n prints style warnings for scripts
  it accepts: z9x_diag.sh, z9x_nui.sh).
- **Problem reports off**: product_prop_v1.txt has `ro.z9x.report.url=` (empty); preflight and spec assert the
  empty line. Z9xProjector carries the report screen (L-REPORTS) but shows its row only for an https URL.
- **Lumen Home privapp list** = apps/Z9xHome/image copy (+ GRANT_RUNTIME_PERMISSIONS, WRITE_SECURE_SETTINGS for
  the one-shot screensaver default). Not enforced on this base (ro.control_privapp_permissions unset).
- **Built 2026-10-09 00:21 MSK** (`LUMEN_OUT=gsi/build/lumen_v1-1.0.1 bash tools/lumen_v1.sh remote`, BUILD_DATE
  20261008; the first run lost the ssh link to the laptop during the APEX push and was rerun with the same
  BUILD_DATE / MTIME_EPOCH, giving the identical unsigned tar): every gate passed (lint, base check, odex,
  member diff 95 added / 174 removed / 11 changed, package checks, apex_sign 35/35, sign_tar --verify, signed
  diff, mkfs.erofs 1.7.1 + fsck, erofs_kcheck 0 cross-block, 85 members read back, check_image.py --variant
  private OK). `gsi/build/lumen_v1.img` = `gsi/build/lumen_v1-1.0.1-20261008.img`, 1,267,773,440 bytes, sha256
  57253dce7a2347d34e8fa1bc876b30427b4e183123d1c55ef33bc869c485beb5 (PRIVATE). The 1.0 and 1.0.0 images
  (`lumen_v1-1.0-20261008.img`, `lumen_v1-1.0.0-20261008.img`) are untouched; the 1.0.0 signed tar stays in
  `gsi/build/lumen_v1/`, the shipped 1.0.0 APK set in `gsi/build/apps_v1-1.0.0/`.


## Lumen OS 1.0.1 final: UI resolution 1080p / 2K / 4K (integration, 2026-10-09)
- **UI resolution** (owner decision: 2K default; design, modes, fallback and device checks in
  `overlay/v1/z9x_uires/README.md`): `system/etc/z9x/z9x_uires.sh` (0755, system_file) is added and
  `system/product/etc/init/init.lineage.atv.scaling.rc` is REPLACED by `overlay/v1/z9x_uires/init.lineage.atv.scaling.rc`
  (0644, system_file; LineageOS's file set `ro.config.size_override 1920,1080` / `density_override 320` 'on fs'; ours
  execs `z9x_uires.sh fs` there and sets `vendor.display-size`, `vendor.mstar.resize.framebuffer`,
  `ro.config.size_override` and `ro.config.density_override` once, from the script's choice, with the stock 1080p
  values as defaults; it also starts the pick / watch / check services). `overlay/v1/z9x_uires/product_prop.txt`
  follows product_prop_v1.txt in the product build.prop: `ro.surface_flinger.max_graphics_width=3840`,
  `_height=2160` (over the vendor's 1920x1080 cap; product is read last), `ro.z9x.uires.allow=1`.
- **lumen_v1.sh**: preflight checks the script (first line, shlint, only `sys.z9x.ui_res.*` setprops and the one
  `reboot,z9x-uires`, no block device / vbmeta / HAL / /data), the rc (the exec 'on fs' before the four setprops,
  each once with the 1080p default), the props snippet (its three lines; no other snippet or rc of ours sets a mode
  prop; CRLF, backslash, compat-prop and duplicate checks cover it) and runs the host tests
  (`overlay/v1/z9x_uires/test/run.sh`, 42 cases). Base check: the base rc must still be LineageOS's exact
  1920,1080 @ 320 file; `system/etc/z9x/z9x_uires.sh` must be new. Spec: F lines for both members, T lines for the
  three props, X lines for LineageOS's two setprops. `tools/sign/check_image.py` gate `uires` (byte-identical
  members, effective SF cap 3840x2160, the mode props set only by the uires rc, once).
- **Build id suffix**: `BUILD_ID_SUFFIX` (1-4 of [a-z0-9], a letter first) makes `ro.z9x.build_id`
  `lumen-$VER-<date><suffix>` (ota_props.txt keeps its `<DATE>` template; lumen_v1.sh substitutes the whole id)
  and About `Lumen OS 1.0.1 (AP2A.240905.003, <date><suffix>)`. The 1.0.1 final is `lumen-1.0.1-20261009b`, so the
  20261009 test build on the owner's projector is a different build (updater, package-cache reset). The id is in
  the prep `.info`, checked again on the laptop after prep and in the image stage; check_image.py requires
  `lumen-<ro.z9x.version>-<yyyymmdd>[suffix]`. Version stays 1.0.1 / 10002 / apps 102.
- **APK set**: Z9xProjector, Z9xHome, Z9xSetup, Z9xTvInput (pinned), Z9xAirPlay (pinned) rebuilt from the reviewed
  1.0.1 resolution lanes (overlay/apps_v1/README.txt); the 20261009 test build's set is kept in
  `gsi/build/apps_v1-1.0.1-20261009/`.
- **Host test portability**: the uires harness's CRLF case used bash's `$'\r'`; under dash (the laptop's sh) that
  is the pattern "$\r", which GNU grep reads as a plain "$r" and finds in `"$rq"` of the script (a false failure
  in the first prep run). Now `cr=$(printf '\r')`; the check still fails on a real CR (tested).
- **Built 2026-10-09 04:10 MSK** (`LUMEN_OUT=gsi/build/lumen_v1-1.0.1g BUILD_DATE=20261009 BUILD_ID_SUFFIX=b bash
  tools/lumen_v1.sh remote`; the first run stopped in the laptop preflight on the harness's dash issue above, and its
  cleanup removed the laptop's copy of the previous unsigned tar, as prep does; the Mac keeps it in
  gsi/build/lumen_v1-1.0.1f): every gate passed (lint with the uires host tests 42/42 on the Mac and the laptop, base check incl. the
  LineageOS scaling rc, odex, member diff 96 added / 174 removed / 12 changed, 84 content files, 28 required and 6
  forbidden lines, package checks, apex_sign 35/35, sign_tar --verify, signed diff, build id check, mkfs.erofs 1.7.1 +
  fsck, erofs_kcheck 0 cross-block, 87 members read back, check_image.py --variant private OK incl. the new
  `uires` gate). `gsi/build/lumen_v1.img` = `gsi/build/lumen_v1-1.0.1-20261009b.img`, 1,268,015,104 bytes, sha256
  89e480300c762bb460cfb9db8a806bc3d2ec1ebffb9ee33b2841acca8d7d7533 (PRIVATE), build id `lumen-1.0.1-20261009b`.
  The test build `lumen_v1-1.0.1-20261009.img` (b2ff1771..., on the owner's projector) and the older images are
  untouched; this build's signed tar stays in `gsi/build/lumen_v1-1.0.1g/`.


## Lumen OS 1.0.1 final: 4K default, OSD region override (integration, 2026-10-09, build id lumen-1.0.1-20261009c)
- **Why** (owner's device tests, 2026-10-09): the vendor GOP scales the HWC display by panel / Display Region, and
  the region is `osdWidth` / `osdHeight` (1920 / 1080) of the active panel ini, read once by the MI daemon; so the
  20261009b switch gave Dst 5120:2880 (2K) and 7680:4320 (4K). **Fix** (`overlay/v1/z9x_uires`, README sections 1-3):
  `z9x_uires.sh fs` copies the panel ini named by `Customer_1.ini` to `/dev/z9x_uires/` (tmpfs), changes only the
  two values to the mode, proves it (two lines differ, values re-parse, byte-exact round trip with cmp), gives it the
  original's label and binds it read-only over the vendor path, in 'on fs' of the PRODUCT rc: after the vendor's
  `mount_all --early` of /vendor/tvconfig (init.common.rc), before midaemon ('on post-fs', init.fusion.rc) and the
  HWC ('on late-fs'). The vendor partition is never written; nothing XGIMI ships in the image. Any failure: 1080p for
  that start. Owner decision: **4K (3840x2160 @ 640, 1:1) is the default**, 1080p is the other choice in Projector
  settings, 2K is a debug mode only (`/metadata/z9x_uires/debug`, adb root); persist.z9x.ui_res=1440 is ignored.
  The post-boot check also requires the HWC Display Region = the mode. The first start of this build drops the
  `failed.*` records left by 20261009b once (marker `osd_override`), so the default 4K is tried.
- **lumen_v1.sh** (same image members as 20261009b): preflight now also locks `DEFAULT=2160` and the reviewed MODES
  table (1080p stock, 4K 1:1 @ 640 from the app, 2K debug), allows only the script's three mount forms
  (`mount -o bind,ro "$copy" "$src"`, `mount -o remount,bind,ro "$src"`, `umount "$src"`; **superseded in
  20261009d**: on the Z9X `bind,ro` gave a read-write bind and toybox's `remount,bind,ro` remounted the whole
  `/dev` tmpfs, so both forms are now forbidden and the copy lives in a tmpfs of its own, see the 20261009e
  entry below), no write or file
  operation on a /vendor path, no other rc/sh of ours (overlay/v1, tools/ota/image, the uires rc outside comments)
  touching /vendor/tvconfig, and `UIRES_RC` in system/product/etc/init. Header comment updated.
  **check_image.py** (tools/sign/CHANGES.md): the exec in 'on fs' of the product rc, no other init file running
  z9x_uires.sh or named like it, no init file / script of ours but z9x_uires.sh touching /vendor/tvconfig, no .ini /
  tvconfig / vendor member in the image.
- **Host test portability** (integration fix in `test/run.sh`): `osd_nul_byte` assumed awk cannot carry a NUL byte.
  True for the device's one-true-awk and the Mac's awk (1080p, still checked there), not for the laptop's mawk, which
  copies it byte for byte, so the start is a correct 4K with an exact copy. The case now probes the host awk and
  checks that copy with expect_osd (cmp -l: only the two values differ). The first remote run stopped on it in the
  laptop preflight (73/74); the rerun passed 74/74 on the Mac and the laptop.
- **APK set**: only Z9xProjector changed (4434c578…, rebuilt in a scratch dir, byte-identical to its lane review;
  overlay/apps_v1/README.txt). The 20261009b set is kept in `gsi/build/apps_v1-1.0.1-20261009b/`.
- **Built 2026-10-09 14:34 MSK** (`LUMEN_OUT=gsi/build/lumen_v1-1.0.1h BUILD_DATE=20261009 BUILD_ID_SUFFIX=c bash
  tools/lumen_v1.sh remote`): every gate passed (lint with the uires host tests 74/74 on the Mac and the laptop, base
  check incl. the LineageOS scaling rc, odex, member diff 96 added / 174 removed / 12 changed, 84 content files, 28
  required and 6 forbidden lines, package checks, apex_sign 35/35, sign_tar --verify, signed diff, build id check,
  mkfs.erofs 1.7.1 + fsck, erofs_kcheck 0 cross-block, 87 members read back, check_image.py --variant private OK incl.
  the extended `uires` gate, 232 members read back from the image). `gsi/build/lumen_v1.img` =
  `gsi/build/lumen_v1-1.0.1-20261009c.img`, 1,268,027,392 bytes, sha256
  1d8e0553ce54527fd6999bcb6a4a8ef03e83b05de6fb243d2bc8e53f7a988a61 (PRIVATE), build id `lumen-1.0.1-20261009c`.
  The older images (20261009b 89e48030…, 20261009 b2ff1771… on the owner's projector, 1.0.1-20261008, 1.0.0, 1.0)
  are untouched; this build's signed tar stays in `gsi/build/lumen_v1-1.0.1h/`. No OTA package was built.


## Lumen OS 1.0.1 final: 4K in WindowManager, visible OFF switch (integration, 2026-10-09, build id lumen-1.0.1-20261009e)
- **(a) What 20261009d already changed** (not written up here before; `overlay/v1/z9x_uires/README.md`, top and
  section 3): the 20261009c device test (root shell, toybox `mount`) showed that `mount -o bind,ro` of the copy on
  the `/dev` tmpfs returns 0 but gives a **read-write** bind (so `fs` fell back to 1080p safely), and that its
  repair `mount -o remount,bind,ro <ini>` makes toybox remount the **whole `/dev` tmpfs** (it failed only because
  `/dev` was busy). Both forms are gone. `z9x_uires.sh fs` now mounts a tmpfs of its own
  (`mount -t tmpfs -o size=576k,mode=0755 z9x_uires /dev/z9x_uires`, only when mountinfo shows nothing there and
  then shows our tmpfs), writes and verifies the copy in it as before, `chmod 0444`, `chcon` to the original's
  label, remounts **only that tmpfs** read-only (`mount -o remount,ro /dev/z9x_uires`) and binds the copy with a
  plain `mount -o bind <copy> <ini>`, which is read-only because its tmpfs is. `/proc/self/mountinfo` decides,
  never an exit code (`ro` in the mount's own or in its superblock's options); any failure unmounts the bind and
  the tmpfs and starts 1080p. **lumen_v1.sh** preflight (replacing the c forms above): the only mount calls are
  that tmpfs of `"$ram"` (`RAM=/dev/z9x_uires`), its `remount,ro`, the plain bind and the umounts of the bind and
  the tmpfs; `remount,bind`, `bind,ro`, `remount,rw` and `-o rw` are refused. Host tests 86 cases (the stub
  `mount` knows only those forms, as toybox behaved). Built 2026-10-09 16:42 MSK (`BUILD_ID_SUFFIX=d`, signed tar in
  `gsi/build/lumen_v1-1.0.1i/`, log `remote_1.0.1i.log`: lint and laptop preflight 86/86, the same gates as c):
  `gsi/build/lumen_v1-1.0.1-20261009d.img`, 1,268,027,392 bytes, sha256
  066f07c656f8ada89db28e2e79dcdbec213884f95b4e69d73485ffed7d1dde6f (PRIVATE); APK set unchanged from c.
- **Device result of 20261009d** (owner's projector, slot `_a`): the 4K start did everything right at `on fs`
  (OSD region bind: tmpfs `ro`, bound `ro`; `vendor.display-size` 3840x2160, `ro.config.size_override 3840,2160`,
  `density_override 640`; SF mode / target 3840x2160, HWC Display Region 3840x2160, GOP Layer / Dst 3840:2160 at
  17 s uptime), but `wm size` said `Physical size: 3840x2160` / `Override size: 1920x1080` and `dumpsys window
  displays` `init=3840x2160 640dpi base=1920x1080 640dpi`, so `check` failed (`check: WM 1920x1080, GOP
  1920x1080->3840x2160`) and correctly restarted once into 1080p.
- **(b) Fix: config_maxUiWidth 3840** (`apps/Z9xFrameworkKeysOverlay/res/values/config.xml`, manifest comment;
  versionCode stays 102, versionName 1.0.1: lumen_checks.py checks only the name). `cmd overlay lookup android
  android:integer/config_maxUiWidth` was 1920, from `/system/product/overlay/TvFrameworkOverlay.apk`
  (com.android.tv.overlay.framework, AOSP `TvFrameworkOverlay/res/values/config.xml:173`; framework default 0).
  Lineage 21 `DisplayContent.updateBaseDisplayMetrics` (line 3099) clamps the base width to it also for a forced
  size, and `setForcedSize` (3172) clamps too, so neither `ro.config.size_override` nor `wm size 3840x2160` gets
  past it. Our static RRO `org.z9x.overlay.framework` (priority 2000, after TvFrameworkOverlay and the Lineage
  product RRO in `cmd overlay list android`) now sets 3840: the 4K mode runs 1:1, the 2K debug mode (2560) is not
  capped either, 1080p (1920 wide) is below the cap and unchanged. Device check (z9x_uires README section 6,
  check 1): the lookup prints `3840`; in 4K `wm size` shows no `Override size`.
- **Build guard** (so this cannot come back): the `lumen_v1.sh` preflight, next to the uires checks, runs
  `lumen_checks.py maxui <APPS>/Z9xFrameworkKeysOverlay.apk 3840` while `z9x_uires/product_prop.txt` has
  `ro.z9x.uires.allow=1`. It reads `integer/config_maxUiWidth` from the APK's `resources.arsc` without tools
  (new `apkinfo.py resource_values` / `apkinfo.py res APK TYPE NAME`: full, sparse, 16-bit-offset and compact
  encodings) and cross-checks it with `aapt2 dump resources` when an aapt2 is found (`$AAPT2`, `$BUILD_TOOLS`, the
  Mac SDK build-tools 36.0.0 first, `tools/bt` on the laptop, the Lineage out tree, PATH; without one: a note,
  tool-free only). It must be the framework RRO on `android`, set once, in the default configuration, a plain
  integer, >= 3840. The 20261009d APK fails it (`no integer/config_maxUiWidth`); synthetic RROs tested: sparse /
  compact / hex encodings pass, a `values-land` copy, a reference, 1920 and a missing value fail, aapt2 agreeing
  in each case.
- **(c) Z9xProjector: visible OFF switch** (`apps/Z9xProjector/src/org/z9x/projector/ui/ToggleRow.java`):
  `track.setAlpha(0xFF)` replaced the alpha of `Theme.SWITCH_OFF` (0x4DFFFFFF), so an unfocused OFF switch was a
  solid white pill with an invisible white knob (and a focused one a solid black pill). Now
  `track.setAlpha(Color.alpha(col) * (pending ? 0x60 : 0xFF) / 0xFF)`: OFF = 30 % white (unfocused) / 30 % black
  on the white focus plate (focused), as the theme defines; ON (ACCENT, alpha FF) and every pending state of ON
  are unchanged. Only ToggleRow draws with SWITCH_OFF / SWITCH_OFF_FOCUSED; its users (quick panel, CEC page,
  game rows, dream rows) only set states, and no host test looks at the colours.
- **APK set** (overlay/apps_v1/README.txt): the recipe was proved first in scratch dirs: the current overlay
  sources reproduced 45fd61f8… and the Projector sources with the ToggleRow edit taken back reproduced 4434c578…,
  both byte for byte. Then rebuilt with `VERSION_CODE=102 VERSION_NAME=1.0.1 BUILD_DIR=<scratch> sh
  apps/build_apk.sh` (public test platform key; the sign stage re-signs with the release key):
  Z9xFrameworkKeysOverlay a236bd63… (only AndroidManifest.xml (comment lines) and resources.arsc differ: + the one
  integer) and Z9xProjector 95187b20… (only classes.dex differs, only in ToggleRow). `lumen_checks.py apks` OK
  (10 APKs); `lumen_v1.sh lint` OK on the Mac (maxui OK with aapt2, uires host tests 86/86). The 20261009d set is
  kept in `gsi/build/apps_v1-1.0.1-20261009d/`.
- **Not built yet**: next image `LUMEN_OUT=gsi/build/lumen_v1-1.0.1j BUILD_DATE=20261009 BUILD_ID_SUFFIX=e bash
  tools/lumen_v1.sh remote` (same image members as 20261009d; the laptop preflight runs maxui with `tools/bt/aapt2`).

## Lumen OS 1.0.1: 4K switched off, 1080p only (2026-10-09, from build suffix f)

- 20261009e ran real 4K on the owner's projector (all 12 state fields 3840x2160, check=ok), but the owner found the
  UI far too slow, and the verification measured it (logs/owner_<serial>/verify_1.0.1e): org.z9x.home at about
  7-10 fps (97.5 % janky frames, GPU 30-120 ms per frame), DMA-BUF +230 MB for the Home scene (every GOP buffer has
  an uncached twin), one LOW_MEMORY kill of Lampa. Decision (owner): no 4K in 1.0.1.
- `overlay/v1/z9x_uires/product_prop.txt`: `ro.z9x.uires.allow=0` (the existing kill switch: every start is 1080p,
  no bind mount, no check; a stored `persist.z9x.ui_res` is ignored). `max_graphics` 3840x2160 and the overlay's
  `config_maxUiWidth` 3840 stay: on the 1920x1080 display they change nothing, and they keep 4K one switch away.
- `tools/lumen_v1.sh`: the preflight and the build.prop check accept `ro.z9x.uires.allow=0|1` (exactly one line) and
  check the value product_prop.txt sets; the maxui guard still applies only while it is 1. Host test `rc` adapted
  (86/86).
- Z9xProjector: `UiResolution.enabled()` (= `ro.z9x.uires.allow` is 1); while it is not, Projector settings shows no
  "Interface resolution" section and the quick panel's Picture page no resolution row (`install` returns before
  adding it). Rebuilt (VERSION_CODE=102, 1.0.1): overlay/apps_v1/Z9xProjector.apk 31a809e8…; `lumen_v1.sh lint` OK.
