#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Lumen OS 1.0 system image ("for XGIMI projectors", author kmuradoff), built from the exact
# known-good system_tv_v4.tar (LineageOS 21 TV GSI + MindTheGapps ATV) like z9x_v65.sh, plus our
# overlays and the APKs of every lane. Successor of tools/z9x_v65.sh (v6.5.2); every v6.x fix that
# still applies is kept, see "Kept from v6.5" below. Design: research/v7 PLAN.md (C1-C25) + the
# speed / home / setup / cec / ota / brand specs, with the user decisions of 2026-10-06.
#
# Stages (each atomic: *.part files, checked, renamed at the end; the base tar is only read):
#   prep    [laptop]  preflight of every input -> base check -> dexpreopt (speed, plan C10) ->
#                     patch_tar -> member/content diff against the base -> package checks
#                     -> $OUTDIR/system_tv_lumen_v1_unsigned.tar (+ .spec, .info)
#   sign    [Mac]     tools/sign/sign_tar.py with the release keys (~/.lumen-keys, never on the
#                     laptop) -> sign_tar.py --verify -> lumen_checks.py signed (only signatures, seinfo
#                     and otacerts changed; dex bytes identical so the odex stay valid)
#                     -> $OUTDIR/system_tv_lumen_v1_signed.tar
#   image   [laptop]  mkfs.erofs + fsck.erofs of the signed tar -> labels of new members -> every added
#                     or replaced member read back (dump.erofs --cat) against the tar -> package checks
#                     -> tools/sign/check_image.py --variant private -> system_tv_lumen_v1.img (+ .sha256)
#   lint    [any]     the input preflight only (no base tar needed)
#   remote  [Mac]     the whole chain: rsync inputs to the laptop, prep there, pull the unsigned tar,
#                     sign on the Mac, push ONLY the signed tar back, image there, pull the image to
#                     gsi/build/system_tv_lumen_v1.img. Nothing secret ever leaves the Mac.
#
#   laptop:  cd ~/z9x/out && bash ../tools/lumen_v1.sh prep        (then image, after sign)
#   Mac:     bash "gsi/tools/lumen_v1.sh" remote                    (or sign, with OUTDIR=...)
#
# Lumen OS 1.0 compared with v6.5.2:
#   - name "Lumen OS" (every user-visible "Z9X OS"); internal package names org.z9x.* and the
#     persist.z9x.* / ro.z9x.* props stay. Props: ro.z9x.version=1.0 (system), ro.lumen.version=1.0
#     (product); About: ro.build.display.id = "Lumen OS 1.0 (<build id>, <date>)",
#     ro.lineage.display.version = "1.0 (kmuradoff)", row titles from Z9xLineagePlatformOverlay
#     ("Lumen OS version" in every target locale). Model / brand / device / fingerprint parts, build
#     type userdebug and tags test-keys unchanged (C18; 'K' asserts below). ro.build.version.incremental
#     gets the suffix 'lumen10' (default), as every v6.x image did with z9x6x.
#   - APK set (overlay/apps_v1, see tools/lumen/lumen_checks.py APKS):
#       /system/app:       Z9xProjector (1.0), Z9xTvInput (1.0, CEC lane, pinned), Z9xAirPlay (1.0:
#                          follows Settings.Global device_name, pinned), Z9xUpdater (1.0, new)
#       /system/priv-app:  Z9xHome (Lumen Home, 1.0, new, HOME priority 3), Z9xSetup (first-run,
#                          1.0, new, HOME priority 10)
#       /system/product/overlay: Z9xFrameworkKeysOverlay (1.0: + cached processes 16, default
#                          assistant org.z9x.projector, 8 CEC defaults), Z9xLineagePlatformOverlay
#                          (new: HOME double-tap 0 = no 300 ms delay, long-press HOME = assist ->
#                          Recents, "Lumen OS" strings), Z9xDeviceConfigOverlay (new: device_config
#                          max_cached_processes 16, freezer on), Z9xTvSettingsHdrOverlay (unchanged)
#       Z9xSetupWraithOverlay is dropped (setup spec 3). Our code APKs are AOT-compiled at build time
#       (oat/arm64/<Name>.odex|vdex next to the APK, compiler filter speed, checked against the base's
#       boot image), then re-signed with the release keys by the sign stage like every test-key APK.
#   - debloat (speed spec 3.1 + setup spec 3 + plan C22): Google Assistant (Katniss) removed; Play
#     Movies is not in the base and the package check forbids it; LineageCustomizer's partner
#     configuration loses its four com.google.android.videos entries (overlay/v1/LineageCustomizer.apk,
#     tools/lumen/gen_customizer.py, brand spec 18); Chromecast built-in (mediashell)
#     and SetupWraith are required to stay. See REMOVE below.
#   - memory: lmkd (swap_free_low 10, thrashing_limit 30, psi_complete_stall 700) and ART thread props
#     in the product build.prop; vm.swappiness 100 (z9x_speed.rc); 16 cached processes (RROs).
#   - power (user decision 2026-10-06, verified on the device): real STR is allowed again. The v6.5
#     'on post-fs' z9x_no_str wakelock is gone (persist.z9x.allow_str unset = allowed; =0 still
#     re-arms the wakelock as an emergency kill switch). The PM51 wake sources are set at boot by
#     L-PROJECTOR's overlay/v1/z9x_power*.rc (+ optional z9x_power*.sh), installed here as they come;
#     z9x_poweroff.rc/.sh (v6.2 watchdog magic-close at a real power-off) and the boot-dark safety
#     (z9x_bootinfo) are kept. z9x_diag: the '!!! vendor suspend' alarm only with allow_str=0; every
#     power capture also logs the USB device controller state and the PM51 wake sources.
#   - first run: org.z9x.setup + sysconfig component-overrides of SetupWraith's MainActivity and
#     BootCompletedReceiver; initial-package-state stopped=false for all six org.z9x apps (C6);
#     privapp allow-lists for the two priv-apps; default permissions for Lumen Home (RECORD_AUDIO)
#     and the updater (POST_NOTIFICATIONS, L-OTA's file).
#   - OTA plumbing from L-OTA (tools/ota/image, HANDOFF.md): z9x_ota.rc + /system/etc/z9x/z9x_ota.sh,
#     update_verifier.rc REPLACED (stock behaviour on normal boots), ota_props.txt (build_id,
#     version_code, keys, ab_ota_partitions without vbmeta, gate) in the product build.prop.
#     No OTA is run and nothing is published in v1; the MTK Codec2 libs stay in this (the owner's)
#     image; public images and z9x_blobs are a later variant (check_image.py --variant public).
#   - boot animation: Lumen "Beam" (tools/lumen/gen_bootanim.py, own render of Apache-2.0 Roboto, no
#     XGIMI/Lineage asset) replaces system/product/media/bootanimation.zip (the -dark symlink stays).
#   - MTK Codec2 store plugin: 6 stock libs (libcodec2store.so dropped, never loaded; PLAN X6).
#   - APEX: not re-signed (apexd trusts pre-installed APEXes on the read-only /system; docs/keys.md#apex).
#
# Kept from v6.5 (same checks as z9x_v65.sh): keylayouts (STB_POWER WAKE, factory remotes POWER),
# Generic.kl (base + key 142), idc, z9x_audio (click curve), z9x_setup.rc (byte-identical, C25),
# z9x_nui, z9x_bootinfo, z9x_poweroff, z9x_diag (passive), the MTK Codec2 store plugin (6 stock libs,
# pinned) + our libcodec2_vndk rebuild (pinned), audio stream volumes, the bluetooth class fix,
# ro.setupwizard.mode removal, persist.z9x.firstrun.
#
# Review fixes 2026-10-07:
#   - USB debugging: ro.adb.secure=1 and persist.sys.usb.config=none (system_ext lines patched, product
#     snippet read last); after the wipe adb is off and asks for the RSA key on screen when turned on.
#   - PackageManager parse cache ON (pm.boot.disable_package_cache gone): added members and their
#     directories get the build time as mtime (patch_tar.py --mtime MTIME_EPOCH, sign_tar.py for the
#     re-signed ones), mkfs.erofs --mkfs-time keeps tar mtimes in the image.
#   - sysconfig: stopped=false for 4 Google packages (fresh wipe), LineageOS stats components off.
#   - PUBLIC VARIANT NOT IMPLEMENTED: this script builds only the owner's PRIVATE image (MTK Codec2 libs
#     embedded, check_image.py --variant private). Never publish it or a payload made from it
#     (tools/ota/make_ota.py refuses it without --private; tools/ota/check_release_assets.py gates any
#     release). A public variant (placeholders + z9x_blobs from the stock firmware) is future work.
#
# Inputs (defaults relative to this script, i.e. ~/z9x/tools on the laptop, gsi/tools on the Mac):
#   $OUTDIR/system_tv_v4.tar      ../overlay/v1/*  ../overlay/apps_v1/*.apk (+ optional PINS.sha256)
#   ./patch_tar.py  ./lumen/{lumen_checks.py,apkinfo.py,dexpreopt_lumen.sh}
#   ./ota/image/{z9x_ota.rc,z9x_ota.sh,update_verifier.rc,z9x-updater-default-permissions.xml,ota_props.txt}
#   ./sign/{testcerts,release_certs}/*.x509.pem (public certificates only)
# Env: BASE OUTDIR TOOLS V1 APPS OTAIMG MKFS FSCK DUMP MIN_FREE_KB (8000000) INCREMENTAL_SUFFIX (lumen10)
#      BUILD_DATE (yyyymmdd, default today UTC) MTIME_EPOCH (mtime of added members, default now) DEXPREOPT (1 on Linux, 0 elsewhere) LINEAGE (~/lineage)
#      ALLOW_MISSING_APKS=1 (dry run without the Home/Setup/Updater/Projector APKs: *_partial outputs)
#      ALLOW_NO_POWER_RC=1 (dry run without L-PROJECTOR's z9x_power*.rc)  KEYS_DIR (~/.lumen-keys, sign)
#      ALLOW_TEST_KEYS=1 (image of the unsigned tar for a lab test; ro.z9x.keys becomes 'test')
#      BUILDER ($BUILDER) BUILDER_KEY (~/.ssh/<builder-ssh-key>) LUMEN_OUT (gsi/build/lumen_v1)
set -euo pipefail

H=$(cd "$(dirname "$0")" && pwd)
TOOLS=${TOOLS:-$H}
TARGET=${1:-prep}
V1=${V1:-$TOOLS/../overlay/v1}
APPS=${APPS:-$TOOLS/../overlay/apps_v1}
OTAIMG=${OTAIMG:-$TOOLS/ota/image}
LT=$TOOLS/lumen
CHK=$LT/lumen_checks.py
PT=$TOOLS/patch_tar.py
TESTCERTS=$TOOLS/sign/testcerts
RELCERTS=$TOOLS/sign/release_certs
INCREMENTAL_SUFFIX=${INCREMENTAL_SUFFIX-lumen10}
BUILD_DATE=${BUILD_DATE:-$(date -u +%Y%m%d)}
MTIME_EPOCH=${MTIME_EPOCH:-$(date +%s)}
NAME=lumen_v1
case $(uname -s) in Linux) DEXPREOPT=${DEXPREOPT:-1} ;; *) DEXPREOPT=${DEXPREOPT:-0} ;; esac
pick() { for c in "$@"; do if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return 0; fi; done; return 1; }
LH=${LINEAGE:-$HOME/lineage}/out/host/linux-x86/bin
MKFS=${MKFS:-$(pick mkfs.erofs "$LH/mkfs.erofs" || true)}
FSCK=${FSCK:-$(pick fsck.erofs "$LH/fsck.erofs" || true)}
DUMP=${DUMP:-$(pick dump.erofs "$LH/dump.erofs" || true)}

log() { echo "$(date +%T) $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }
sha256in() { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | awk '{print $1}'; }
need() { [ -s "$1" ] || die "missing or empty file: $1"; }
shlint() { local sl; for sl in dash "busybox ash" sh; do if command -v ${sl%% *} >/dev/null 2>&1; then $sl -n "$1" || die "$1: syntax ($sl)"; fi; done; }
nocomment() { grep -v '^[[:space:]]*#' "$1" | grep -v '^[[:space:]]*$' | tr -s ' ' || [ $? -eq 1 ]; }

KL=(Vendor_0001_Product_0001 Vendor_000d_Product_3838 Vendor_000d_Product_3839 Vendor_000d_Product_3840
    Vendor_000d_Product_3841 Vendor_000d_Product_3842 Vendor_000d_Product_3843 Vendor_1d5a_Product_c081
    Vendor_26e3_Product_af02 Vendor_3697_Product_0001 Vendor_3697_Product_0002 Vendor_9999_Product_0666
    Vendor_9999_Product_0777)
IDC=(Vendor_000d_Product_3838 Vendor_000d_Product_3839 Vendor_000d_Product_3840 Vendor_000d_Product_3841
     Vendor_000d_Product_3842 Vendor_000d_Product_3843 Vendor_1d5a_Product_c081 Vendor_26e3_Product_af02)
# init files of ours (overlay/v1) -> system/etc/init; z9x_power*.rc of L-PROJECTOR are added on top
RCS=(z9x_base.rc z9x_audio.rc z9x_setup.rc z9x_poweroff.rc z9x_nui.rc z9x_bootinfo.rc z9x_speed.rc)
SCRIPTS=(z9x_diag.sh z9x_poweroff.sh z9x_nui.sh z9x_bootinfo.sh)
# OTA plumbing of L-OTA (tools/ota/image/HANDOFF.md section 1), byte-identical
OTA_RC=z9x_ota.rc; OTA_SH=z9x_ota.sh; OTA_UV=update_verifier.rc; OTA_PERM=z9x-updater-default-permissions.xml
AB_OTA=boot,bootdata,dtbo,mboot,optee,owl,satf,tvconfig,tvservice,vbmeta_system,xgimiconfig
# z9x_setup.rc must stay byte-identical to v6.5 (setup spec 6, plan C25: locale default stays en-US)
SETUPRC_SHA=$(sha256 "$TOOLS/../overlay/v65/z9x_setup.rc" 2>/dev/null || echo none)

REMOVE=(
  # v6: sample launcher
  system/product/priv-app/TvSampleLeanbackLauncher
  system/product/etc/permissions/com.example.sampleleanbacklauncher.xml
  # user decision 2026-10-06: Google Assistant / Google app for TV
  system/product/priv-app/Katniss
  # speed spec 3.1: GApps that do nothing useful here
  system/product/priv-app/DfuService
  system/product/priv-app/GoogleOneTimeInitializer
  system/product/app/GoogleCalendarSyncAdapter
  system/system_ext/priv-app/GoogleFeedback
  # speed spec 3.1: AOSP/GSI leftovers
  system/system_ext/priv-app/EmulatorRadioConfig
  system/priv-app/DynamicSystemInstallationService
  system/priv-app/NfcNci
  system/etc/permissions/NfcNci.xml
  system/app/SecureElement
  system/app/PrintSpooler
  system/priv-app/ManagedProvisioning
  system/product/overlay/ManagedProvisioning__lineage_gsi_tv_arm64__auto_generated_rro_product.apk
  system/priv-app/LocalTransport
  system/app/CameraExtensionsProxy
  system/app/BasicDreams
  system/app/WallpaperBackup
  # speed spec 3.1: emulator / phone overlays (never enabled on a TV)
  system/product/overlay/CompanionDeviceManager__emulator__auto_generated_characteristics_rro.apk
  system/product/overlay/framework-res__emulator__auto_generated_characteristics_rro.apk
  system/product/overlay/NavigationBarMode2Button
  system/product/overlay/EmulationPixel2XL system/product/overlay/EmulationPixel3
  system/product/overlay/EmulationPixel3XL system/product/overlay/EmulationPixel3a
  system/product/overlay/EmulationPixel3aXL system/product/overlay/EmulationPixel4
  system/product/overlay/EmulationPixel4XL system/product/overlay/EmulationPixel4a
  system/product/overlay/EmulationPixel5 system/product/overlay/EmulationPixel6
  system/product/overlay/EmulationPixel6Pro system/product/overlay/EmulationPixel6a
  system/product/overlay/EmulationPixel7 system/product/overlay/EmulationPixel7Pro
  system/product/overlay/EmulationPixel7a system/product/overlay/EmulationPixel8
  system/product/overlay/EmulationPixel8Pro system/product/overlay/EmulationPixelFold
  system/product/overlay/SystemUIEmulationPixel3 system/product/overlay/SystemUIEmulationPixel3XL
  system/product/overlay/SystemUIEmulationPixel3a system/product/overlay/SystemUIEmulationPixel3aXL
  system/product/overlay/SystemUIEmulationPixel4 system/product/overlay/SystemUIEmulationPixel4XL
  system/product/overlay/SystemUIEmulationPixel4a system/product/overlay/SystemUIEmulationPixel5
  system/product/overlay/SystemUIEmulationPixel6 system/product/overlay/SystemUIEmulationPixel6Pro
  system/product/overlay/SystemUIEmulationPixel6a system/product/overlay/SystemUIEmulationPixel7
  system/product/overlay/SystemUIEmulationPixel7Pro system/product/overlay/SystemUIEmulationPixel7a
  system/product/overlay/SystemUIEmulationPixel8 system/product/overlay/SystemUIEmulationPixel8Pro
  system/product/overlay/SystemUIEmulationPixelFold
  # setup spec 3 + plan C22: our first-run replaces Lineage's; the feedback consent goes with GoogleFeedback
  system/system_ext/priv-app/LineageSetupWizard
  system/product/overlay/LineageSetupWizard__lineage_gsi_tv_arm64__auto_generated_rro_product.apk
  system/product/overlay/LineageGoogleSetupWraithPairingOverlay
  system/system_ext/priv-app/TvFeedbackConsent
)
# v6.2b MTK Codec2 store plugin (Kinopoisk secure decoder): stock libs, unmodified, sha256-pinned
LIBLABEL=u:object_r:system_lib_file:s0
# Lumen OS 1.0 (PLAN X6): libcodec2store.so (both ABIs) is dropped: an MTK component store that
# nothing loads (not NEEDED by libc2plugin_store, no dlopen string; v6.2b kept it only as listed).
C2LIBS=(system_ext/lib/libc2plugin_store.so system_ext/lib64/libc2plugin_store.so
        system_ext/lib/vendor.mediatek.hardware.c2.info@1.0.so system_ext/lib64/vendor.mediatek.hardware.c2.info@1.0.so
        system/lib/libcodec2_soft_common.so system/lib64/libcodec2_soft_common.so)
C2SHA=(4d848dec4b480ed37690095d67702466bb1d17e924c6af42fa7e2c50e784b0ea
       4f6d375fb4d7c0aae92ccd901ca01e9a0a8c368c145c1c45a666a8a1c0f66d5c
       e8eb86b04fa79f33739df5a417381be8125938b7692ba29270b20c1790fbeaaa
       f1e325c8955b1af57dafadc77bdd09efc9f8b3cd0119b1846d606abc34385c65
       66e9a1676bcaa8a7dddb724eab3e5d9f4b474ea857bf09eca1ea2e2372953b83
       21dffeba0d3fae032d53b607d838141d58aea2e826a0a44e803d9ae73e260645)
VNDKLIBS=(system/lib/libcodec2_vndk.so system/lib64/libcodec2_vndk.so)
VNDKSHA=(8164f62fd64015c3db567900859b48138fa4facd24fe28d15a3e3e00c8d357ee
         a7c29ef2c1fd31abcce24d447042c4e8f4fd87e420de8736e2d91e250ad11182)
VNDKBASESHA=(143175136c483f455f3ea4a61c65e03bf9b62250a8803a9c71c450726f1ce049
             295489d93b4f06712f2fca086830483e6ca3acb9246f979598a57d2cf74f2099)
c2dest() { case $1 in system_ext/*) echo "system/$1" ;; *) echo "$1" ;; esac; }
# compatibility props that must stay byte-identical to the base (brand spec 5, C18)
COMPAT_SYS='^ro\.(product\.system\.|system\.build\.)|^ro\.build\.(id|tags|type|version\.(release|sdk|security_patch|release_or_codename|codename))$|^ro\.build\.(flavor|description|product|characteristics|fingerprint)$'
COMPAT_PRODUCT='^ro\.product\.(product\.|build\.)|^ro\.build\.characteristics$'

# ======================================================================== preflight of the inputs
preflight() {
  [ -f "$PT" ] || die "no $PT"
  grep -q -- "'--mkdir'" "$PT" && grep -q 'def opt_label' "$PT" || die "$PT is not the v6.2b patch_tar.py (--mkdir + LABEL)"
  for f in "$CHK" "$LT/apkinfo.py" "$LT/dexpreopt_lumen.sh"; do need "$f"; done
  [ -d "$TESTCERTS" ] && [ -s "$RELCERTS/platform.x509.pem" ] || die "no public certificates in $TESTCERTS / $RELCERTS"
  if ls "$RELCERTS" "$TESTCERTS" 2>/dev/null | grep -qE '\.(pk8|key|pem\.key)$'; then die "a private key file in the cert dirs"; fi
  case $INCREMENTAL_SUFFIX in *[!A-Za-z0-9_]*) die "INCREMENTAL_SUFFIX: only [A-Za-z0-9_]" ;; esac
  case $BUILD_DATE in [0-9][0-9][0-9][0-9][01][0-9][0-3][0-9]) ;; *) die "BUILD_DATE must be yyyymmdd" ;; esac
  case $MTIME_EPOCH in *[!0-9]*|'') die "MTIME_EPOCH must be a UNIX time" ;; esac
  [ "$MTIME_EPOCH" -gt 1700000000 ] || die "MTIME_EPOCH $MTIME_EPOCH is before 2023 (the parse cache would keep stale entries)"

  for f in "${RCS[@]}" "${SCRIPTS[@]}" z9x_diag.rc z9x-sysconfig.xml build_prop_v1.txt product_prop_v1.txt \
           audio_policy_engine_stream_volumes.xml z9x-projector-default-permissions.xml \
           z9x-airplay-default-permissions.xml z9x-home-default-permissions.xml \
           privapp-permissions-z9xhome.xml privapp-permissions-z9xsetup.xml bootanimation.zip LineageCustomizer.apk; do
    need "$V1/$f"
  done
  need "$LT/gen_customizer.py"
  for f in "$OTA_RC" "$OTA_SH" "$OTA_UV" "$OTA_PERM" ota_props.txt; do need "$OTAIMG/$f"; done
  [ ! -e "$V1/z9x_features.rc" ] || die "$V1/z9x_features.rc exists (v6 kill switch, gone since v6.1)"
  for k in "${KL[@]}"; do need "$V1/keylayout/$k.kl"; done
  need "$V1/keylayout/Generic.kl"
  for k in "${IDC[@]}"; do need "$V1/idc/$k.idc"; done
  n=$(ls "$V1"/keylayout/*.kl | wc -l); [ "$n" -eq 14 ] || die "$V1/keylayout has $n .kl, expected 14"
  n=$(ls "$V1"/idc/*.idc | wc -l); [ "$n" -eq 8 ] || die "$V1/idc has $n .idc, expected 8"
  for f in "$V1"/*.rc "$V1"/*.sh "$V1"/*.txt "$V1"/*.xml "$V1"/keylayout/*.kl "$V1"/idc/*.idc "$OTAIMG"/*; do
    [ -f "$f" ] || continue
    if grep -q $'\r' "$f"; then die "CRLF in $f"; fi
  done

  # ---- L-PROJECTOR power init (wake sources): z9x_power*.rc other than the v6.2 z9x_poweroff.rc
  POWERRC=(); POWERSH=()
  for f in "$V1"/z9x_power*.rc; do [ -e "$f" ] || continue; [ "${f##*/}" = z9x_poweroff.rc ] || POWERRC+=("${f##*/}"); done
  for f in "$V1"/z9x_power*.sh; do [ -e "$f" ] || continue; [ "${f##*/}" = z9x_poweroff.sh ] || POWERSH+=("${f##*/}"); done
  if [ ${#POWERRC[@]} -eq 0 ]; then
    [ "${ALLOW_NO_POWER_RC:-0}" = 1 ] || die "no overlay/v1/z9x_power*.rc from L-PROJECTOR (PM51 wake sources for STR); ALLOW_NO_POWER_RC=1 only for a dry run"
    echo "WARN no z9x_power*.rc (dry run): STR wake sources stay at the vendor defaults"
  fi
  for r in ${POWERRC[@]+"${POWERRC[@]}"}; do power_rc_check "$V1/$r"; done
  for s in ${POWERSH[@]+"${POWERSH[@]}"}; do
    [ "$(head -n1 "$V1/$s")" = '#!/system/bin/sh' ] || die "$s: first line is not #!/system/bin/sh"
    shlint "$V1/$s"
    grep -q "/system/etc/xgimi/$s" "$V1"/z9x_power*.rc || die "$s is not used by any z9x_power*.rc"
    power_forbidden "$V1/$s"
  done
  [ "$(grep -h -c 'wakeup_source' ${POWERRC[@]+"${POWERRC[@]/#/$V1/}"} ${POWERSH[@]+"${POWERSH[@]/#/$V1/}"} /dev/null | awk '{s+=$1} END{print s+0}')" -gt 0 ] \
    || [ ${#POWERRC[@]} -eq 0 ] || die "z9x_power*: no /sys/mtk_pm/wakeup_source write (user decision 2026-10-06)"

  # ---- z9x_base.rc: STR allowed (no post-fs wakelock), kill switch both ways, smart_ak rule, builtins only
  base_src="$(nocomment "$V1/z9x_base.rc")"
  if grep -qi 'afbc' <<<"$base_src"; then die "z9x_base.rc: an AFBC property is set"; fi
  if grep -qx 'on post-fs' <<<"$base_src"; then die "z9x_base.rc: 'on post-fs' (the v6.5 never-STR wakelock) must be gone"; fi
  if grep -q 'z9x_no_str' <<<"$base_src"; then die "z9x_base.rc: the z9x_no_str wakelock belongs to z9x_power.rc (L-PROJECTOR) now"; fi
  [ "$(grep -A1 -x 'on property:init.svc.smart_ak_server=restarting' <<<"$base_src" | tail -n 1)" = ' stop smart_ak_server' ] \
    || die "z9x_base.rc: smart_ak_server rule missing"
  if grep -qE '^ *(exec|start|service|class_start|wait|exec_start|exec_background) ' <<<"$base_src"; then
    die "z9x_base.rc: only init builtins setprop / write / stop allowed"
  fi
  # z9x_no_str only as the persist.z9x.allow_str=0 kill switch of the power init (never unconditionally)
  for f in "$V1"/*.rc; do
    case ${f##*/} in z9x_power*.rc) continue ;; esac
    if nocomment "$f" | grep -q 'z9x_no_str'; then die "${f##*/}: z9x_no_str outside z9x_power*.rc"; fi
  done
  if [ ${#POWERRC[@]} -gt 0 ]; then
    pw_src=$(for r in "${POWERRC[@]}"; do nocomment "$V1/$r"; done)
    [ "$(grep -A1 -x 'on property:persist.z9x.allow_str=0' <<<"$pw_src" | tail -n 1)" = ' write /sys/power/wake_lock z9x_no_str' ] \
      || die "z9x_power*.rc: the allow_str=0 kill switch (wake_lock z9x_no_str) is missing"
    if awk '/^on /{t=$0} / write \/sys\/power\/wake_lock z9x_no_str/{ if (t !~ /persist\.z9x\.allow_str=0/) bad=1 } END{exit !bad}' <<<"$pw_src"; then
      die "z9x_power*.rc: z9x_no_str is locked outside the allow_str=0 triggers (STR must be allowed by default)"
    fi
  fi
  # ---- z9x_speed.rc: exactly the swappiness write
  [ "$(nocomment "$V1/z9x_speed.rc")" = "$(printf '%s\n' 'on property:sys.boot_completed=1' ' write /proc/sys/vm/swappiness 100')" ] \
    || die "z9x_speed.rc: unexpected content (only vm.swappiness 100; E2 stays commented out)"
  # ---- z9x_setup.rc byte-identical to v6.5
  [ "$(sha256 "$V1/z9x_setup.rc")" = "$SETUPRC_SHA" ] || die "z9x_setup.rc differs from overlay/v65 (must stay byte-identical, C25)"
  # ---- z9x_bootinfo (v6.5, unchanged rules)
  [ "$(nocomment "$V1/z9x_bootinfo.rc")" = \
    "$(printf '%s\n' 'on boot' ' start z9x_bootinfo' 'service z9x_bootinfo /system/bin/sh /system/etc/xgimi/z9x_bootinfo.sh' \
       ' user root' ' group root system' ' seclabel u:r:su:s0' ' oneshot' ' disabled')" ] || die "z9x_bootinfo.rc: unexpected content"
  [ "$(head -n1 "$V1/z9x_bootinfo.sh")" = '#!/system/bin/sh' ] || die "z9x_bootinfo.sh: first line"
  [ "$(grep -v '^[[:space:]]*#' "$V1/z9x_bootinfo.sh" | grep 'setprop' | tr -s ' ')" = \
    "$(printf '%s\n' 'setprop sys.z9x.wake_name "$w"' 'setprop sys.z9x.wdt_reset_chk "$c"' 'setprop sys.z9x.boot_reason "$r"')" ] \
    || die "z9x_bootinfo.sh: only the three sys.z9x setprops (boot_reason last) allowed"
  [ "$(grep -v '^[[:space:]]*#' "$V1/z9x_bootinfo.sh" | grep -c '/sys/')" -eq 3 ] || die "z9x_bootinfo.sh: expected exactly 3 sysfs reads"
  if grep -v '^[[:space:]]*#' "$V1/z9x_bootinfo.sh" | grep -E '/sys/' \
      | grep -vE 'timeout 1 cat (/sys/mtk_pm/|/sys/bus/platform/devices/1c400600\.wdt0/wdt_extend/wdt_reset_chk )' | grep -q .; then
    die "z9x_bootinfo.sh: unbounded or unexpected sysfs access"
  fi
  # ---- z9x_poweroff (v6.2 watchdog magic close; the .rc may be refined by L-PROJECTOR, the exec stays)
  grep -qx 'on shutdown' "$V1/z9x_poweroff.rc" \
    && grep -qE '^ +exec u:r:su:s0 root root -- /system/bin/sh /system/etc/xgimi/z9x_poweroff\.sh$' "$V1/z9x_poweroff.rc" \
    || die "z9x_poweroff.rc: the 'on shutdown' exec of z9x_poweroff.sh is missing"
  [ "$(head -n1 "$V1/z9x_poweroff.sh")" = '#!/system/bin/sh' ] || die "z9x_poweroff.sh: first line"
  grep -q '^  shutdown\*) ;;$' "$V1/z9x_poweroff.sh" || die "z9x_poweroff.sh: no shutdown* gate"
  if grep -v '^[[:space:]]*#' "$V1/z9x_poweroff.sh" | grep -q 'setprop'; then die "z9x_poweroff.sh calls setprop"; fi
  grep -v '^[[:space:]]*#' "$V1/z9x_poweroff.sh" | awk '/echo V > \/dev\/watchdog0/{if(!w)w=NR} /mtk_pm|\/proc\/driver|wdt_extend/{if(!m)m=NR} END{exit !(w && m && w<m)}' \
    || die "z9x_poweroff.sh: watchdog stop is not before the diagnostic reads"
  grep -q "^( timeout 3 /system/bin/sh -c '$" "$V1/z9x_poweroff.sh" || die "z9x_poweroff.sh: diagnostics not under timeout 3"
  # ---- z9x_nui (v6.2b, exact)
  [ "$(nocomment "$V1/z9x_nui.rc")" = \
    "$(printf '%s\n' 'service z9x_nui /system/bin/sh /system/etc/xgimi/z9x_nui.sh' ' class main' ' user root' \
        ' group root system log' ' seclabel u:r:su:s0' ' oom_score_adjust -1000' \
        'service z9x_nuihold /system/bin/sh /system/etc/xgimi/z9x_nui.sh hold' ' class main' ' user root' \
        ' group root system log' ' seclabel u:r:su:s0' ' oom_score_adjust -1000' \
        'on property:init.svc.z9x_nui=restarting' ' setprop sys.z9x.nui down' \
        'on property:init.svc.z9x_nui=stopped' ' setprop sys.z9x.nui down')" ] || die "z9x_nui.rc: unexpected content"
  grep -q '^setprop sys\.z9x\.nui ready$' "$V1/z9x_nui.sh" && [ "$(grep -v '^[[:space:]]*#' "$V1/z9x_nui.sh" | grep -c 'setprop')" -eq 1 ] \
    || die "z9x_nui.sh: only 'setprop sys.z9x.nui ready' allowed"
  # ---- z9x_diag (passive)
  [ "$(head -n1 "$V1/z9x_diag.sh")" = '#!/system/bin/sh' ] || die "z9x_diag.sh: first line"
  if grep -v '^[[:space:]]*#' "$V1/z9x_diag.sh" | grep -q 'setprop'; then die "z9x_diag.sh must be passive (setprop found)"; fi
  if grep -v '^[[:space:]]*#' "$V1/z9x_diag.sh" | grep -q 'wakeup_count'; then die "z9x_diag.sh reads /sys/power/wakeup_count (blocks)"; fi
  grep -q 'CLASS=SELF-RESET' "$V1/z9x_diag.sh" && grep -q '^pwatch() {' "$V1/z9x_diag.sh" || die "z9x_diag.sh: boot class / power watch missing"
  grep -q 'gp persist.z9x.allow_str)" = 0 ]' "$V1/z9x_diag.sh" || die "z9x_diag.sh: the vendor-suspend alarm must fire only with allow_str=0"
  grep -qF 'usb_dev=$(gp persist.z9x.diag_usb)' "$V1/z9x_diag.sh" && grep -qF 'if [ ! -d "$m/z9x_diag_logs" ]; then' "$V1/z9x_diag.sh" \
    || die "z9x_diag.sh: the USB log copy must be opt-in (existing z9x_diag_logs/ folder or persist.z9x.diag_usb=1)"
  for s in "${SCRIPTS[@]}"; do shlint "$V1/$s"; done
  # ---- L-OTA image files
  [ "$(head -n1 "$OTAIMG/$OTA_SH")" = '#!/system/bin/sh' ] || die "$OTA_SH: first line"
  shlint "$OTAIMG/$OTA_SH"
  grep -qE '^service update_verifier ' "$OTAIMG/$OTA_UV" || die "$OTA_UV: no 'service update_verifier'"
  grep -q '/system/etc/z9x/z9x_ota.sh' "$OTAIMG/$OTA_RC" || die "$OTA_RC does not run /system/etc/z9x/z9x_ota.sh"
  if grep -v '^[[:space:]]*#' "$OTAIMG/$OTA_SH" | grep -qE '(dd|cat|echo|printf)[^|]*>[[:space:]]*/dev/block|of=/dev/block|fastboot|avbtool'; then
    die "$OTA_SH writes a block device / vbmeta (never allowed)"
  fi
  # ---- props
  if grep -q '\\' "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$OTAIMG/ota_props.txt"; then die "backslash in a prop snippet"; fi
  for l in 'ro.z9x.version=1.0' 'ro.z9x.clickcurve=1' 'persist.z9x.firstrun=1'; do
    grep -qx "$l" "$V1/build_prop_v1.txt" || die "build_prop_v1.txt: '$l' missing"
  done
  if grep -q '^pm\.boot\.disable_package_cache' "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt"; then
    die "pm.boot.disable_package_cache must stay off (parse cache on, member mtimes carry the build time)"
  fi
  for l in 'ro.product.locale=en-US' 'persist.sys.timezone=Europe/Moscow' 'bluetooth.device.default_name=XGIMI Z9X' \
           'ro.lumen.version=1.0' 'ro.lmk.swap_free_low_percentage=10' 'ro.lmk.thrashing_limit=30' \
           'ro.lmk.psi_complete_stall_ms=700' 'dalvik.vm.dex2oat-threads=4' 'dalvik.vm.background-dex2oat-threads=2' \
           'ro.adb.secure=1' 'persist.sys.usb.config=none'; do
    grep -qx "$l" "$V1/product_prop_v1.txt" || die "product_prop_v1.txt: '$l' missing"
  done
  for l in "ro.product.ab_ota_partitions=$AB_OTA" 'ro.z9x.keys=release' 'ro.z9x.device=z9x' 'ro.z9x.build_id=lumen-1.0-<DATE>'; do
    grep -qx "$l" "$OTAIMG/ota_props.txt" || die "tools/ota/image/ota_props.txt: '$l' missing"
  done
  grep -qE '^ro\.z9x\.version_code=[0-9]+$' "$OTAIMG/ota_props.txt" || die "ota_props.txt: ro.z9x.version_code not an integer"
  case ",$AB_OTA," in *,vbmeta,*|*,system,*|*,vendor,*) die "ab_ota_partitions must not list vbmeta/system/vendor" ;; esac
  if cat "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$OTAIMG/ota_props.txt" | grep -v '^#' \
      | grep -qE '^(ro\.product\.(system\.|product\.|system_ext\.|vendor\.|odm\.)?(brand|model|manufacturer|device|name)|ro\.build\.(fingerprint|tags|type|id|version\.release|version\.sdk)|ro\.setupwizard\.mode|ro\.build\.display\.id|ro\.lineage)='; then
    die "a prop snippet sets a compatibility / display prop directly (only lumen_v1.sh may patch display props)"
  fi
  dup=$(cat "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$OTAIMG/ota_props.txt" | grep -v '^#' | grep '=' | cut -d= -f1 | sort | uniq -d)
  [ -z "$dup" ] || die "prop set twice across the snippets: $dup"
  if grep -qE 'featblock|persist\.z9x\.strike|persist\.z9x\.lastfeat|persist\.z9x\.lastboot' \
      <(cat "$V1/build_prop_v1.txt"; for r in "${RCS[@]}" z9x_diag.rc; do nocomment "$V1/$r"; done); then
    die "v6 kill-switch property still used"
  fi
  # ---- keylayouts (v6.5 rules)
  for k in Vendor_000d_Product_3838:113 Vendor_000d_Product_3840:113 Vendor_000d_Product_3841:113 \
           Vendor_000d_Product_3842:113 Vendor_000d_Product_3843:113 Vendor_1d5a_Product_c081:116 \
           Vendor_26e3_Product_af02:116 Vendor_3697_Product_0001:116 Vendor_3697_Product_0001:190 \
           Vendor_3697_Product_0001:75 Vendor_3697_Product_0002:30 Vendor_3697_Product_0002:116 \
           Vendor_3697_Product_0002:260 Vendor_0001_Product_0001:116 Vendor_0001_Product_0001:183; do
    grep -qE "^key ${k#*:}[[:space:]]+STB_POWER WAKE([[:space:]]|$)" "$V1/keylayout/${k%%:*}.kl" \
      || die "${k%%:*}.kl: key ${k#*:} is not STB_POWER WAKE"
  done
  for k in Vendor_9999_Product_0666:4 Vendor_9999_Product_0666:6 Vendor_9999_Product_0777:14; do
    grep -qE "^key ${k#*:}[[:space:]]+POWER WAKE([[:space:]]|$)" "$V1/keylayout/${k%%:*}.kl" \
      || die "${k%%:*}.kl: key ${k#*:} is not POWER WAKE (factory remote)"
  done
  grep -qE '^key 142[[:space:]]+STB_POWER WAKE([[:space:]]|$)' "$V1/keylayout/Generic.kl" || die "Generic.kl: key 142 is not STB_POWER WAKE"
  for f in "$V1"/keylayout/*.kl; do
    case ${f##*/} in Vendor_9999_Product_0666.kl|Vendor_9999_Product_0777.kl) continue ;; esac
    re='(SLEEP|SOFT_SLEEP|TV_POWER)'; [ "${f##*/}" = Generic.kl ] || re='(SLEEP|SOFT_SLEEP|POWER|TV_POWER)'
    if grep -qE "^[[:space:]]*key[[:space:]]+(usage[[:space:]]+)?[0-9a-fA-Fx]+[[:space:]]+$re([[:space:]]|$)" "$f"; then
      die "${f##*/}: a sleeping/system power key in a user keylayout (STB_POWER only)"
    fi
  done
  # ---- Codec2 libs (v6.2b), pinned
  n=$(find "$V1/c2store" -type f | wc -l); [ "$n" -eq 6 ] || die "$V1/c2store has $n files, expected 6"
  [ ${#C2LIBS[@]} -eq 6 ] && [ ${#C2SHA[@]} -eq 6 ] || die "C2LIBS/C2SHA must list 6 entries"
  for i in "${!C2LIBS[@]}"; do [ "$(sha256 "$V1/c2store/${C2LIBS[$i]}")" = "${C2SHA[$i]}" ] || die "c2store/${C2LIBS[$i]}: not the stock sha256"; done
  n=$(find "$V1/c2vndk" -type f | wc -l); [ "$n" -eq 2 ] || die "$V1/c2vndk has $n files, expected 2"
  for i in "${!VNDKLIBS[@]}"; do [ "$(sha256 "$V1/c2vndk/${VNDKLIBS[$i]}")" = "${VNDKSHA[$i]}" ] || die "c2vndk/${VNDKLIBS[$i]}: not the pinned rebuild"; done
  # ---- XML inputs
  python3 - "$V1" "$OTAIMG/$OTA_PERM" <<'PY' || die "XML check failed"
import os, sys, xml.etree.ElementTree as ET
d, otaperm = sys.argv[1], sys.argv[2]
for f in sorted(os.listdir(d)) + [otaperm]:
    p = f if f == otaperm else os.path.join(d, f)
    if p.endswith('.xml'):
        ET.parse(p)
r = ET.parse(os.path.join(d, 'z9x-sysconfig.xml')).getroot()
st = {(e.get('package'), e.get('stopped')) for e in r.findall('initial-package-state')}
want = {(p, 'false') for p in ('org.z9x.projector', 'org.z9x.tvinput', 'org.z9x.airplay', 'org.z9x.home',
                                'org.z9x.setup', 'org.z9x.updater', 'com.google.android.tv.remote.service',
                                'com.android.vending', 'com.google.android.gms', 'com.google.android.gsf')}
assert st == want, st
co = {(e.get('package'), c.get('class'), c.get('enabled')) for e in r.findall('component-override') for c in e}
LP = 'org.lineageos.lineageparts'
assert co == {('com.google.android.tungsten.setupwraith', 'com.google.android.tungsten.setupwraith.MainActivity', 'false'),
              ('com.google.android.tungsten.setupwraith', 'com.google.android.tvsetup.app.BootCompletedReceiver', 'false'),
              (LP, LP + '.lineagestats.ReportingServiceManager', 'false'), (LP, LP + '.lineagestats.ReportingService', 'false'),
              (LP, LP + '.lineagestats.StatsUploadJobService', 'false')}, co
def exc(f):
    return [(e.get('package'), sorted(q.get('name') for q in e.findall('permission'))) for e in ET.parse(f).getroot().findall('exception')]
assert exc(os.path.join(d, 'z9x-home-default-permissions.xml')) == [('org.z9x.home', ['android.permission.RECORD_AUDIO'])]
assert exc(os.path.join(d, 'z9x-airplay-default-permissions.xml')) == [('org.z9x.airplay', ['android.permission.POST_NOTIFICATIONS'])]
assert exc(os.path.join(d, 'z9x-projector-default-permissions.xml'))[0][0] == 'org.z9x.projector'
assert [e.get('package') for e in ET.parse(otaperm).getroot().findall('exception')] == ['org.z9x.updater']
for f, pkg in (('privapp-permissions-z9xhome.xml', 'org.z9x.home'), ('privapp-permissions-z9xsetup.xml', 'org.z9x.setup')):
    assert [e.get('package') for e in ET.parse(os.path.join(d, f)).getroot().findall('privapp-permissions')] == [pkg], f
r = ET.parse(os.path.join(d, 'audio_policy_engine_stream_volumes.xml')).getroot()
g = {x.findtext('name'): {v.get('deviceCategory'): v.get('ref') for v in x.findall('volume')} for x in r.findall('volumeGroup')}
assert len(g) == 12 and g['system']['DEVICE_CATEGORY_SPEAKER'] == 'MM' and g['system']['DEVICE_CATEGORY_HEADSET'] == 'MM'
print('xml ok: sysconfig (6 org.z9x + 4 Google packages not stopped, SetupWraith + LineageOS stats overrides), permissions, stream volumes')
PY
  # ---- boot animation (brand spec 4.4): STORED, small box, 'c' intro + 'f' fading loop, <= 1 MB
  python3 - "$V1/bootanimation.zip" <<'PY' || die "bootanimation.zip check failed"
import sys, zipfile, os
p = sys.argv[1]
assert os.path.getsize(p) <= 1 << 20, 'bigger than 1 MiB'
z = zipfile.ZipFile(p)
bad = [i.filename for i in z.infolist() if i.compress_type != zipfile.ZIP_STORED]
assert not bad, 'compressed entries: %s' % bad[:3]
d = z.read('desc.txt').decode().split('\n')
w, h, fps = map(int, d[0].split())
assert w <= 1920 and h <= 1080 and fps == 60 and w % 2 == 0 and h % 2 == 0, d[0]
parts = [l.split() for l in d[1:] if l.strip()]
assert parts[0][:4] == ['c', '1', '0', 'part0'] and parts[-1][0] == 'f' and parts[-1][3] == 'part1', parts
names = z.namelist()
for _, _, _, part, *_ in parts:
    fr = [n for n in names if n.startswith(part + '/') and n.endswith('.png')]
    assert fr, 'no frames in ' + part
    assert all(z.read(n)[:8] == b'\x89PNG\r\n\x1a\n' for n in fr), part
assert not any(n.endswith(('audio.wav', 'trim.txt')) for n in names)
frames = sum(1 for n in names if n.endswith('.png'))
print('bootanimation ok: %dx%d@%d, %d frames, %.1f MB GL peak, %d bytes' % (w, h, fps, frames, frames * w * h * 4 / 1e6, os.path.getsize(p)))
PY
  # ---- the APK set of all lanes
  PLAN=$WORK/apk_plan.tsv
  python3 "$CHK" apks "$APPS" "$TESTCERTS" "$RELCERTS" "$PLAN" || die "APK set check failed (overlay/apps_v1)"
}

# PM51 wake sources: L-PROJECTOR owns the values; these are the hard rules around them
power_forbidden() {
  if nocomment "$1" | grep -qE '/sys/power/state|sys\.powerctl|(^| )reboot( |$)|/dev/block|setprop persist\.|IGmpf|service call'; then
    die "${1##*/}: touches power state / reboots / block devices / persist props / the HAL (not allowed in an init power file)"
  fi
}
power_rc_check() {
  local f=$1 src
  power_forbidden "$f"
  src=$(nocomment "$f")
  # direct writes that contradict the user decision of 2026-10-06 are refused
  while read -r ws val; do
    case "$ws=$val" in
      keypad0=1|ir0=1|bt-gpio=1|mute-gpio=1|cec0=0|cec0=1|rtc0=0|wifi-gpio=0|uart=0|voice=0|dvi[0-3]=0|lan=0|usb=0|cast=0) ;;
      *) die "${f##*/}: wake source $ws=$val contradicts the decision (keypad0/ir0/bt-gpio/mute-gpio 1; rtc0/wifi-gpio/uart/voice/dvi0..3/lan/usb/cast 0; cec0 by the CEC setting)" ;;
    esac
  done < <(sed -n 's|^ *write /sys/mtk_pm/wakeup_source/\([A-Za-z0-9_-]*\) \([0-9]*\)$|\1 \2|p' <<<"$src")
  if grep -qE '^ *(exec|service) ' <<<"$src"; then
    grep -oE '/system/etc/xgimi/[A-Za-z0-9_.-]+\.sh' <<<"$src" | while read -r s; do
      [ -f "$V1/${s##*/}" ] || die "${f##*/} runs $s, which is not in overlay/v1"
    done
  fi
}

# ======================================================================== member diff (python)
verify_tar() { python3 "$CHK" verify "$BASE" "$1" "$2"; }

# add DEST SRC MODE [replace]: new member (A) or replaced keylayout (M) + content (F)
add() {
  ACT+=(--add "$1" "$2" "$3")
  if [ "${4:-}" = replace ]; then echo "M $1" >> "$S"; else echo "A $1 $3" >> "$S"; fi
  echo "F $1 $2" >> "$S"
}
mkd() { ACT+=(--mkdir "$1" "$2"); echo "A $1 $2" >> "$S"; }
addl() { ACT+=(--add "$1" "$2" "$3" "$4"); echo "A $1 $3 $4" >> "$S"; echo "F $1 $2" >> "$S"; }
mkdl() { ACT+=(--mkdir "$1" "$2" "$3"); echo "A $1 $2 $3" >> "$S"; }
repl() { ACT+=(--add "$1" "$2" "$3" "$4"); echo "P $1 $3 $4" >> "$S"; echo "F $1 $2" >> "$S"; }

tarprop() {  # tarprop TAR MEMBER KEY -> value
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys, tarfile
t = tarfile.open(sys.argv[1])
for m in t:
    if m.name.rstrip('/') == sys.argv[2]:
        v = re.findall(r'^%s=(.*)$' % re.escape(sys.argv[3]), t.extractfile(m).read().decode('utf-8', 'replace'), re.M)
        print(v[0] if len(v) == 1 else ''); break
PY
}

# ======================================================================== stage: prep
stage_prep() {
  OUTDIR=$(cd "${OUTDIR:-.}" && pwd)
  BASE=${BASE:-$OUTDIR/system_tv_v4.tar}
  [ -f "$BASE" ] || die "no base $BASE"
  SFX=; [ "${ALLOW_MISSING_APKS:-0}" = 1 ] && SFX=_partial
  [ "$DEXPREOPT" = 1 ] || SFX=${SFX}_nodex
  OUT=$OUTDIR/system_tv_${NAME}_unsigned$SFX.tar
  PARTS=("$OUT.part" "$OUT" "$OUT.spec" "$OUT.info")
  preflight
  avail=$(df -Pk "$OUTDIR" | awk 'NR==2{print $4}')
  [ "$avail" -gt "${MIN_FREE_KB:-8000000}" ] || die "not enough space in $OUTDIR (${avail} KB)"
  rm -f "${PARTS[@]}"

  log "base member check"
  NEW_PATHS=$(printf '%s\n' system/app/Z9xProjector system/app/Z9xTvInput system/app/Z9xAirPlay system/app/Z9xUpdater \
      system/priv-app/Z9xHome system/priv-app/Z9xSetup system/product/usr/idc system/etc/sysconfig/z9x.xml \
      system/product/usr/keylayout/Generic.kl system/etc/z9x system/etc/init/z9x_ota.rc \
      system/etc/permissions/privapp-permissions-z9xhome.xml system/etc/permissions/privapp-permissions-z9xsetup.xml \
      system/product/etc/default-permissions/z9x-projector.xml system/product/etc/default-permissions/z9x-airplay.xml \
      system/product/etc/default-permissions/z9x-home.xml system/product/etc/default-permissions/z9x-updater.xml \
      system/etc/xgimi/audio_policy_engine_stream_volumes.xml system/etc/init/z9x_features.rc \
      "${RCS[@]/#/system/etc/init/}" "${SCRIPTS[@]/#/system/etc/xgimi/}" system/etc/init/z9x_diag.rc \
      ${POWERRC[@]+"${POWERRC[@]/#/system/etc/init/}"} ${POWERSH[@]+"${POWERSH[@]/#/system/etc/xgimi/}"} \
      system/product/overlay/Z9xFrameworkKeysOverlay.apk system/product/overlay/Z9xLineagePlatformOverlay.apk \
      system/product/overlay/Z9xDeviceConfigOverlay.apk system/product/overlay/Z9xTvSettingsHdrOverlay.apk \
      system/etc/init/nativeui.rc system/bin/nativeui \
      $(for l in "${C2LIBS[@]}"; do c2dest "$l"; done)) \
  NEED_DIRS=$(printf '%s\n' system/app system/priv-app system/etc/init system/etc/xgimi system/etc/sysconfig \
      system/etc/permissions system/product/overlay system/product/usr system/product/usr/keylayout system/product/etc \
      system/product/media system/system_ext/etc system/product/etc/default-permissions system/etc/security) \
  NEED_FILES=$(printf '%s\n' system/build.prop system/product/etc/build.prop system/system_ext/etc/build.prop \
      system/etc/init/xgimi_compat.rc system/etc/xgimi/audio_policy_configuration.xml system/etc/init/update_verifier.rc \
      system/product/app/webview/oat/arm64/webview.odex system/product/media/bootanimation.zip \
      system/fonts/Roboto-Regular.ttf system/etc/security/otacerts.zip system/framework/org.lineageos.platform-res.apk \
      system/system_ext/priv-app/SimpleDeviceConfig/SimpleDeviceConfig.apk system/product/priv-app/SetupWraithPrebuilt \
      system/product/priv-app/LineageCustomizer/LineageCustomizer.apk) \
  VNDK="$(for i in "${!VNDKLIBS[@]}"; do echo "${VNDKLIBS[$i]}=${VNDKBASESHA[$i]}"; done)" \
  LIBLABEL=$LIBLABEL GENERIC_KL=$V1/keylayout/Generic.kl LINEAGE_DISPLAY_PROP_FILE=system/build.prop \
    python3 "$CHK" base "$BASE" "${REMOVE[@]}" || die "base check failed"

  # ---- dexpreopt (laptop): the base's own webview odex is the reference for GC mode + boot checksums
  ODEX=$WORK/odex; mkdir -p "$ODEX"
  if [ "$DEXPREOPT" = 1 ]; then
    log "dexpreopt (speed) against the base boot image"
    python3 - "$BASE" "$WORK/ref_webview.odex" <<'PY'
import sys, tarfile
t = tarfile.open(sys.argv[1])
for m in t:
    if m.name == 'system/product/app/webview/oat/arm64/webview.odex':
        open(sys.argv[2], 'wb').write(t.extractfile(m).read()); break
PY
    LD=${LINEAGE:-$HOME/lineage}/out/soong/dexpreopt_arm64
    python3 "$CHK" bootgate "$BASE" "$LD/dex_bootjars/android/system/framework/arm64" \
      "$LD/dex_mainlinejars/android/system/framework/arm64" || die "boot image gate failed: the laptop out tree is not the base's build"
    while IFS=$'\t' read -r n pkg dest dexo _; do
      [ "$dexo" = yes ] || continue
      bash "$LT/dexpreopt_lumen.sh" "$APPS/$n.apk" "/$dest" "$ODEX/$n" "$WORK/ref_webview.odex" >"$WORK/dexopt_$n.log" 2>&1 \
        || { cat "$WORK/dexopt_$n.log"; die "dexpreopt of $n failed"; }
      tail -n 3 "$WORK/dexopt_$n.log"
    done < "$PLAN"
  else
    echo "WARN DEXPREOPT=0: no build-time AOT (dry run; the device JIT-compiles our apps as in v6.x)"
  fi

  # ---- members
  ACT=(); S=$OUTDIR/.$NAME.spec.tmp; : > "$S"
  while IFS=$'\t' read -r n pkg dest dexo _; do
    d=${dest%/*}
    case $d in system/product/overlay) ;; *) mkd "$d" 755 ;; esac
    add "$dest" "$APPS/$n.apk" 644
    if [ "$dexo" = yes ] && [ -s "$ODEX/$n/oat/arm64/$n.odex" ]; then
      mkd "$d/oat" 755; mkd "$d/oat/arm64" 755
      add "$d/oat/arm64/$n.odex" "$ODEX/$n/oat/arm64/$n.odex" 644
      add "$d/oat/arm64/$n.vdex" "$ODEX/$n/oat/arm64/$n.vdex" 644
    fi
  done < "$PLAN"
  for k in "${KL[@]}"; do add "system/product/usr/keylayout/$k.kl" "$V1/keylayout/$k.kl" 644 replace; done
  add system/product/usr/keylayout/Generic.kl "$V1/keylayout/Generic.kl" 644
  mkd system/product/usr/idc 755
  for k in "${IDC[@]}"; do add "system/product/usr/idc/$k.idc" "$V1/idc/$k.idc" 644; done
  add system/etc/init/z9x_diag.rc "$V1/z9x_diag.rc" 644
  for r in "${RCS[@]}" ${POWERRC[@]+"${POWERRC[@]}"}; do add "system/etc/init/$r" "$V1/$r" 644; done
  for s in "${SCRIPTS[@]}" ${POWERSH[@]+"${POWERSH[@]}"}; do add "system/etc/xgimi/$s" "$V1/$s" 755; done
  add system/etc/sysconfig/z9x.xml "$V1/z9x-sysconfig.xml" 644
  add system/etc/permissions/privapp-permissions-z9xhome.xml "$V1/privapp-permissions-z9xhome.xml" 644
  add system/etc/permissions/privapp-permissions-z9xsetup.xml "$V1/privapp-permissions-z9xsetup.xml" 644
  add system/etc/xgimi/audio_policy_engine_stream_volumes.xml "$V1/audio_policy_engine_stream_volumes.xml" 644
  add system/product/etc/default-permissions/z9x-projector.xml "$V1/z9x-projector-default-permissions.xml" 644
  add system/product/etc/default-permissions/z9x-airplay.xml "$V1/z9x-airplay-default-permissions.xml" 644
  add system/product/etc/default-permissions/z9x-home.xml "$V1/z9x-home-default-permissions.xml" 644
  add system/product/etc/default-permissions/z9x-updater.xml "$OTAIMG/$OTA_PERM" 644
  # L-OTA plumbing: gate + replaced update_verifier.rc (same label/mode as the base member)
  mkd system/etc/z9x 755
  add system/etc/z9x/z9x_ota.sh "$OTAIMG/$OTA_SH" 755
  add system/etc/init/z9x_ota.rc "$OTAIMG/$OTA_RC" 644
  repl system/etc/init/update_verifier.rc "$OTAIMG/$OTA_UV" 644 u:object_r:system_file:s0
  # Lumen boot animation (the -dark symlink keeps pointing at it)
  repl system/product/media/bootanimation.zip "$V1/bootanimation.zip" 644 u:object_r:system_file:s0
  # brand spec 18: the classic launcher's partner configuration without Play Movies (removed by the user
  # decision); only res/raw/configuration.xml differs from the base, classes.dex is identical (the base
  # odex/vdex stay valid); test platform key here, the sign stage re-signs it with the release key
  python3 "$LT/gen_customizer.py" check "$BASE" "$V1/LineageCustomizer.apk" || die "LineageCustomizer.apk check failed"
  repl system/product/priv-app/LineageCustomizer/LineageCustomizer.apk "$V1/LineageCustomizer.apk" 644 u:object_r:system_file:s0
  # v6.2b Codec2 store plugin + libcodec2_vndk rebuild (the owner's private image)
  mkdl system/system_ext/lib 755 "$LIBLABEL"
  for l in "${C2LIBS[@]}"; do addl "$(c2dest "$l")" "$V1/c2store/$l" 644 "$LIBLABEL"; done
  for l in "${VNDKLIBS[@]}"; do repl "$l" "$V1/c2vndk/$l" 644 "$LIBLABEL"; done

  # ---- props
  bid=$(tarprop "$BASE" system/build.prop ro.build.id); [ -n "$bid" ] || die "no ro.build.id in the base"
  DISPLAY_ID="Lumen OS 1.0 ($bid, $BUILD_DATE)"
  LINEAGE_DISPLAY="1.0 (kmuradoff)"
  OTAPROPS=$(sed "s/<DATE>/$BUILD_DATE/" "$OTAIMG/ota_props.txt")
  ACT+=(--sub system/build.prop '^# end of file$' "$(cat "$V1/build_prop_v1.txt")
# end of file"
        --sub system/product/etc/build.prop '^# end of file$' "$(cat "$V1/product_prop_v1.txt")
# ---- OTA (tools/ota/image/ota_props.txt, L-OTA)
$OTAPROPS
# end of file"
        --sub system/product/etc/build.prop '^bluetooth\.device\.class_of_device=90,2,12$' 'bluetooth.device.class_of_device=44,4,60'
        --sub system/system_ext/etc/build.prop '^ro\.setupwizard\.mode=DISABLED\n' ''
        --sub system/system_ext/etc/build.prop '^# GSI always disables adb authentication\nro\.adb\.secure=0$' \
              '# Lumen OS: adb asks for the RSA key (product build.prop repeats it, read last)
ro.adb.secure=1'
        --sub system/system_ext/etc/build.prop '^persist\.sys\.usb\.config=adb$' 'persist.sys.usb.config=none'
        --sub system/build.prop '^ro\.build\.display\.id=.*$' "ro.build.display.id=$DISPLAY_ID"
        --sub system/build.prop '^ro\.lineage\.display\.version=.*$' "ro.lineage.display.version=$LINEAGE_DISPLAY")
  printf '%s\n' 'C system/build.prop' 'C system/product/etc/build.prop' 'C system/system_ext/etc/build.prop' \
    'T system/build.prop ro.z9x.version=1.0' 'T system/build.prop ro.z9x.clickcurve=1' \
    'X system/build.prop pm.boot.disable_package_cache=true' 'T system/build.prop persist.z9x.firstrun=1' \
    "T system/build.prop ro.build.display.id=$DISPLAY_ID" "T system/build.prop ro.lineage.display.version=$LINEAGE_DISPLAY" \
    'T system/build.prop ro.build.type=userdebug' 'T system/build.prop ro.build.tags=test-keys' \
    'T system/product/etc/build.prop ro.product.locale=en-US' 'T system/product/etc/build.prop persist.sys.timezone=Europe/Moscow' \
    'T system/product/etc/build.prop bluetooth.device.class_of_device=44,4,60' \
    'T system/product/etc/build.prop bluetooth.device.default_name=XGIMI Z9X' \
    'T system/product/etc/build.prop ro.lumen.version=1.0' \
    'T system/product/etc/build.prop ro.lmk.swap_free_low_percentage=10' \
    "T system/product/etc/build.prop ro.product.ab_ota_partitions=$AB_OTA" \
    'T system/product/etc/build.prop ro.z9x.keys=release' \
    "T system/product/etc/build.prop ro.z9x.build_id=lumen-1.0-$BUILD_DATE" \
    'T system/product/etc/build.prop ro.product.product.model=XGIMI Z9X' \
    'X system/system_ext/etc/build.prop ro.setupwizard.mode=DISABLED' \
    'T system/system_ext/etc/build.prop ro.adb.secure=1' 'X system/system_ext/etc/build.prop ro.adb.secure=0' \
    'T system/system_ext/etc/build.prop persist.sys.usb.config=none' 'X system/system_ext/etc/build.prop persist.sys.usb.config=adb' \
    'T system/product/etc/build.prop ro.adb.secure=1' 'T system/product/etc/build.prop persist.sys.usb.config=none' \
    "K system/build.prop $COMPAT_SYS" "K system/product/etc/build.prop $COMPAT_PRODUCT" >> "$S"
  if [ -n "$INCREMENTAL_SUFFIX" ]; then
    ACT+=(--sub system/build.prop '^ro\.build\.version\.incremental=(.*)$' "ro.build.version.incremental=\\1.$INCREMENTAL_SUFFIX")
    inc=$(tarprop "$BASE" system/build.prop ro.build.version.incremental)
    [ -n "$inc" ] || die "no ro.build.version.incremental in the base system/build.prop"
    echo "T system/build.prop ro.build.version.incremental=$inc.$INCREMENTAL_SUFFIX" >> "$S"
    log "ro.build.version.incremental: $inc -> $inc.$INCREMENTAL_SUFFIX"
  fi
  for r in "${REMOVE[@]}"; do ACT+=(--remove "$r"); echo "R $r" >> "$S"; done

  log "[prep] 1/3 patch_tar"
  python3 "$PT" "$BASE" "$OUT.part" --mtime "$MTIME_EPOCH" "${ACT[@]}" > "$WORK/patch_tar.log" || { tail -n 20 "$WORK/patch_tar.log"; die "patch_tar failed"; }
  grep -c '^removed ' "$WORK/patch_tar.log" | sed 's/^/removed members: /'
  log "[prep] 2/3 member and content check against the base"
  verify_tar "$OUT.part" "$S" || die "member check failed"
  log "[prep] 3/3 package checks"
  python3 "$CHK" packages "$OUT.part" || die "package check failed"
  mv "$S" "$OUT.spec"
  {
    echo "name=$NAME"; echo "build_date=$BUILD_DATE"; echo "mtime_epoch=$MTIME_EPOCH"; echo "dexpreopt=$DEXPREOPT"
    echo "partial=${ALLOW_MISSING_APKS:-0}"; echo "power_rc=${POWERRC[*]:-none}"
    echo "base=$(basename "$BASE") $(wc -c < "$BASE" | tr -d ' ')"
    echo "script_sha256=$(sha256 "$0")"
    while IFS=$'\t' read -r n pkg dest dexo signer h vn vc; do echo "apk=$n $pkg $vn ($vc) $signer dexpreopt=$dexo sha256=$h"; done < "$PLAN"
  } > "$OUT.info"
  mv "$OUT.part" "$OUT"
  PARTS=()
  ls -la "$OUT"; cat "$OUT.info"
  log "prep done: $OUT (next: sign on the Mac)"
}

# ======================================================================== stage: sign (Mac only)
stage_sign() {
  [ "$(uname -s)" = Darwin ] || die "sign runs on the Mac only: the release keys never leave it"
  OUTDIR=$(cd "${OUTDIR:-.}" && pwd)
  KEYS=${KEYS_DIR:-$HOME/.lumen-keys}
  case $KEYS in *"XGIMI PLAY 6"*|*/gsi/*) die "keys inside the project tree: $KEYS" ;; esac
  [ -d "$KEYS" ] && [ -s "$KEYS/platform.pk8" ] || die "no release keys in $KEYS"
  [ "$(stat -f %Lp "$KEYS")" = 700 ] || die "$KEYS must be mode 700"
  for c in platform shared media networkstack releasekey ota ota_next; do
    [ "$(openssl x509 -in "$KEYS/$c.x509.pem" -outform DER | sha256in)" = "$(openssl x509 -in "$RELCERTS/$c.x509.pem" -outform DER | sha256in)" ] \
      || die "$KEYS/$c.x509.pem differs from the published tools/sign/release_certs copy"
  done
  IN=${UNSIGNED:-$OUTDIR/system_tv_${NAME}_unsigned.tar}
  [ -f "$IN" ] && [ -f "$IN.info" ] && [ -f "$IN.spec" ] || die "no $IN (+ .info/.spec) from the prep stage"
  if grep -qx 'partial=1' "$IN.info" && [ "${ALLOW_PARTIAL:-0}" != 1 ]; then die "$IN is a partial dry-run build"; fi
  OUT=$OUTDIR/system_tv_${NAME}_signed.tar
  REP=$OUTDIR/sign_report_$NAME
  PARTS=("$OUT" "$OUT.part" "$OUT.info" "$OUT.spec")
  rm -f "${PARTS[@]}"; mkdir -p "$REP"
  log "[sign] 1/3 sign_tar.py (release keys from $KEYS)"
  python3 "$TOOLS/sign/sign_tar.py" "$IN" "$OUT" --keys "$KEYS" --report-dir "$REP" || die "sign_tar.py failed"
  log "[sign] 2/3 sign_tar.py --verify"
  python3 "$TOOLS/sign/sign_tar.py" --verify "$OUT" --keys "$KEYS" --report-dir "$REP" || die "sign_tar.py --verify failed"
  log "[sign] 3/3 unsigned -> signed diff"
  SIGN_REPORT=$REP python3 "$CHK" signed "$IN" "$OUT" "$TESTCERTS" "$RELCERTS" || die "signed tar check failed"
  cp "$IN.spec" "$OUT.spec"
  { cat "$IN.info"; echo "signed=$(date -u +%Y-%m-%dT%H:%M:%SZ) keys=release"; } > "$OUT.info"
  PARTS=()
  ls -la "$OUT"
  log "sign done: $OUT (push only this tar back to the laptop)"
}

# ======================================================================== stage: image
stage_image() {
  OUTDIR=$(cd "${OUTDIR:-.}" && pwd)
  BASE=${BASE:-$OUTDIR/system_tv_v4.tar}
  [ -n "$MKFS" ] && [ -n "$FSCK" ] && [ -n "$DUMP" ] || die "no mkfs.erofs/fsck.erofs/dump.erofs"
  IN=${SIGNED:-$OUTDIR/system_tv_${NAME}_signed.tar}
  IMG=$OUTDIR/system_tv_$NAME.img
  if [ "${ALLOW_TEST_KEYS:-0}" = 1 ]; then
    U=${UNSIGNED:-$OUTDIR/system_tv_${NAME}_unsigned.tar}
    [ -f "$U" ] || die "no $U"
    IN=$OUTDIR/system_tv_${NAME}_testkeys.tar; IMG=$OUTDIR/system_tv_${NAME}_testkeys.img
    cp "$U.spec" "$IN.spec"; { cat "$U.info"; echo "keys=test"; } > "$IN.info"
    python3 "$PT" "$U" "$IN.part" --sub system/product/etc/build.prop '^ro\.z9x\.keys=release$' 'ro.z9x.keys=test' >/dev/null
    mv "$IN.part" "$IN"
    sed -i.bak 's/^T system\/product\/etc\/build.prop ro.z9x.keys=release$/T system\/product\/etc\/build.prop ro.z9x.keys=test/' "$IN.spec" && rm -f "$IN.spec.bak"
    echo "WARN ALLOW_TEST_KEYS=1: lab image with the PUBLIC AOSP test keys (never for the user's device)"
  fi
  [ -f "$IN" ] && [ -f "$IN.spec" ] && [ -f "$IN.info" ] || die "no $IN (+ .spec/.info)"
  if grep -qx 'partial=1' "$IN.info" && [ "${ALLOW_PARTIAL:-0}" != 1 ]; then die "$IN is a partial dry-run build"; fi
  PARTS=("$IMG.part" "$IMG" "$IMG.sha256")
  rm -f "${PARTS[@]}"
  if [ "${ALLOW_TEST_KEYS:-0}" = 1 ]; then
    log "[image] 1/5 member diff of the input against the base"
    verify_tar "$IN" "$IN.spec" > "$WORK/verify.log" || { cat "$WORK/verify.log"; die "member check of $IN failed"; }
    tail -n 2 "$WORK/verify.log"
  else
    # the signed tar differs from the base by every re-signed APK, seinfo and otacerts: that diff was
    # checked member by member against the unsigned tar in the sign stage (lumen_checks.py signed)
    log "[image] 1/5 signed input"
    grep -q '^signed=.* keys=release$' "$IN.info" || die "$IN.info: not from the sign stage"
  fi
  ALLOW_MISSING_APKS=${ALLOW_PARTIAL:-0} python3 "$CHK" packages "$IN" || die "package check failed"
  log "[image] 2/5 mkfs.erofs + fsck.erofs"
  # --mkfs-time: -T is only the build time; every inode keeps its tar mtime (parse cache, see header)
  if ! "$MKFS" -b4096 -zlz4hc -E noinline_data -T1230768000 --mkfs-time --tar=f "$IMG.part" "$IN" > "$WORK/mkfs.log" 2>&1; then
    cat "$WORK/mkfs.log"; die "mkfs.erofs failed"
  fi
  grep -E "total inodes|Build completed" "$WORK/mkfs.log" || true
  "$FSCK" "$IMG.part" >/dev/null || die "fsck.erofs failed"
  log "[image] 3/5 labels of the new members"
  while read -r k p _; do
    [ "$k" = A ] || [ "$k" = P ] || continue
    info=$("$DUMP" --path="/$p" "$IMG.part" 2>&1) || { echo "$info"; die "dump.erofs $p"; }
    echo "$info" | grep -q 'Uid: 0' || die "$p: uid"
    echo "$info" | grep -qE 'Xattr size: [1-9]' || die "$p: no xattr (SELinux label)"
  done < "$IN.spec"
  log "[image] 4/5 read-back of every added / replaced file"
  python3 - "$IN" "$IN.spec" > "$WORK/readback.tsv" <<'PY'
import hashlib, sys, tarfile
want = set()
for line in open(sys.argv[2]):
    k, rest = line.rstrip('\n').split(' ', 1)
    if k in ('A', 'P', 'M', 'C'):
        want.add(rest.split()[0])
with tarfile.open(sys.argv[1]) as t:
    for m in t:
        n = m.name.rstrip('/')
        if n in want and m.isreg():
            print('%s\t%s' % (n, hashlib.sha256(t.extractfile(m).read()).hexdigest()))
PY
  n=0
  while IFS=$'\t' read -r p want; do
    got=$("$DUMP" --cat --path="/$p" "$IMG.part" | sha256in) || die "dump.erofs --cat $p"
    [ "$got" = "$want" ] || die "$p in the image: sha256 $got, tar $want"
    n=$((n + 1))
  done < "$WORK/readback.tsv"
  echo "read back $n members: identical to the tar"
  log "[image] 5/5 release gate (tools/sign/check_image.py)"
  if [ "${ALLOW_TEST_KEYS:-0}" = 1 ]; then
    echo "WARN check_image.py skipped (test keys)"
  elif [ -f "$TOOLS/sign/check_image.py" ]; then
    # check_image.py runs sign_tar.py --verify (apksigner, aapt2, zipalign + a JDK): on the laptop these
    # come from tools/bt (apksigner wrapper + jar, aapt2/zipalign links into the Lineage out tree) and the
    # Lineage prebuilt JDK 21, unless BUILD_TOOLS / JAVA_HOME are set
    if [ -z "${BUILD_TOOLS:-}" ] && [ -x "$TOOLS/bt/apksigner" ]; then export BUILD_TOOLS=$TOOLS/bt; fi
    if [ -z "${JAVA_HOME:-}" ] && [ -x "${LINEAGE:-$HOME/lineage}/prebuilts/jdk/jdk21/linux-x86/bin/java" ]; then
      export JAVA_HOME=${LINEAGE:-$HOME/lineage}/prebuilts/jdk/jdk21/linux-x86
    fi
    # the laptop has no keys: sign_tar.py --verify needs only the PUBLIC release certificates (a mode-700
    # dir holding copies of tools/sign/release_certs/*.x509.pem)
    if [ -z "${KEYS_DIR:-}" ] && [ ! -d "$HOME/.lumen-keys" ]; then
      mkdir -m 700 "$WORK/pubcerts" && cp "$RELCERTS"/*.x509.pem "$WORK/pubcerts/" && export KEYS_DIR=$WORK/pubcerts
    fi
    python3 "$TOOLS/sign/check_image.py" --tar "$IN" --img "$IMG.part" --variant private --base "$BASE" \
      --report-dir "$OUTDIR/check_image_$NAME" || die "check_image.py failed"
  else
    die "no tools/sign/check_image.py"
  fi
  mv "$IMG.part" "$IMG"
  (cd "$OUTDIR" && { sha256sum "${IMG##*/}" 2>/dev/null || shasum -a 256 "${IMG##*/}"; }) | tee "$IMG.sha256"
  PARTS=()
  ls -la "$IMG"
  log "image done: $IMG"
}

# ======================================================================== stage: remote (Mac)
stage_remote() {
  [ "$(uname -s)" = Darwin ] || die "remote runs on the Mac"
  BUILDER=${BUILDER:-$BUILDER}
  BKEY=${BUILDER_KEY:-$HOME/.ssh/<builder-ssh-key>}
  SSH=(ssh -i "$BKEY" -o BatchMode=yes "$BUILDER")
  RS=(rsync -e "ssh -i $BKEY -o BatchMode=yes" --rsync-path="nice -n 19 ionice -c3 rsync")
  GSI=$(cd "$TOOLS/.." && pwd)
  LOUT=${LUMEN_OUT:-$GSI/build/lumen_v1}
  mkdir -p "$LOUT"
  thermal() {
    local t i
    for i in $(seq 1 60); do
      t=$("${SSH[@]}" 'cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -n 1')
      [ "${t:-0}" -lt 85000 ] && return 0
      log "laptop at $((t / 1000)) C: waiting"; sleep 10
    done
    die "the laptop stays hot"
  }
  log "[remote] 1/6 lint on the Mac"
  ( WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT; preflight ) || die "lint failed"
  log "[remote] 2/6 inputs -> laptop (public files only)"
  "${SSH[@]}" 'mkdir -p ~/z9x/tools/lumen ~/z9x/tools/ota/image ~/z9x/tools/sign/testcerts ~/z9x/tools/sign/release_certs ~/z9x/overlay/v1 ~/z9x/overlay/apps_v1 ~/z9x/overlay/v65'
  "${RS[@]}" -a "$TOOLS/lumen_v1.sh" "$TOOLS/patch_tar.py" "$BUILDER:z9x/tools/"
  "${RS[@]}" -a --delete "$LT/" "$BUILDER:z9x/tools/lumen/"
  "${RS[@]}" -a --delete "$OTAIMG/" "$BUILDER:z9x/tools/ota/image/"
  "${RS[@]}" -a --delete --include='*.x509.pem' --include='FINGERPRINTS.txt' --exclude='*' "$TESTCERTS/" "$BUILDER:z9x/tools/sign/testcerts/"
  "${RS[@]}" -a --delete --include='*.x509.pem' --include='FINGERPRINTS.txt' --exclude='*' "$RELCERTS/" "$BUILDER:z9x/tools/sign/release_certs/"
  "${RS[@]}" -a "$TOOLS/sign/check_image.py" "$TOOLS/sign/sign_tar.py" "$TOOLS/sign/keymap.json" "$BUILDER:z9x/tools/sign/"
  "${RS[@]}" -a "$GSI/overlay/v65/z9x_setup.rc" "$BUILDER:z9x/overlay/v65/"
  "${RS[@]}" -a --delete "$V1/" "$BUILDER:z9x/overlay/v1/"
  "${RS[@]}" -a --delete "$APPS/" "$BUILDER:z9x/overlay/apps_v1/"
  "${SSH[@]}" 'ls ~/z9x/tools/sign ~/z9x/tools/sign/*/ | grep -E "\.(pk8|key)$"' && die "a private key reached the laptop" || true
  log "[remote] 3/6 prep on the laptop"
  thermal
  "${SSH[@]}" "cd ~/z9x/out && BUILD_DATE=$BUILD_DATE MTIME_EPOCH=$MTIME_EPOCH INCREMENTAL_SUFFIX=$INCREMENTAL_SUFFIX nice -n 10 ionice -c3 bash ../tools/lumen_v1.sh prep"
  log "[remote] 4/6 unsigned tar -> Mac, sign"
  "${RS[@]}" -a --partial "$BUILDER:z9x/out/system_tv_${NAME}_unsigned.tar" "$BUILDER:z9x/out/system_tv_${NAME}_unsigned.tar.spec" \
    "$BUILDER:z9x/out/system_tv_${NAME}_unsigned.tar.info" "$LOUT/"
  OUTDIR=$LOUT stage_sign
  log "[remote] 5/6 signed tar -> laptop, image"
  "${RS[@]}" -a --partial "$LOUT/system_tv_${NAME}_signed.tar" "$LOUT/system_tv_${NAME}_signed.tar.spec" \
    "$LOUT/system_tv_${NAME}_signed.tar.info" "$BUILDER:z9x/out/"
  thermal
  "${SSH[@]}" "cd ~/z9x/out && nice -n 10 ionice -c3 bash ../tools/lumen_v1.sh image"
  log "[remote] 6/6 image -> gsi/build/$NAME.img (delta rsync over the previous image, or a copy of v6.5.2)"
  DST=$GSI/build/$NAME.img
  [ -f "$DST" ] || cp "$GSI/build/system_tv_v652.img" "$DST"
  "${RS[@]}" --inplace --no-whole-file "$BUILDER:z9x/out/system_tv_$NAME.img" "$DST"
  want=$("${SSH[@]}" "cut -d' ' -f1 ~/z9x/out/system_tv_$NAME.img.sha256")
  got=$(sha256 "$DST")
  [ -n "$want" ] && [ "$got" = "$want" ] || die "image checksum mismatch after the copy ($got != $want)"
  printf '%s  %s\n' "$got" "$NAME.img" > "$GSI/build/SHA256SUMS_$NAME.txt"
  log "remote done: $DST sha256 $got (flash = the user's own step)"
}

# ======================================================================== main
PARTS=()
WORK=$(mktemp -d)
cleanup() {
  local rc=$1
  rm -rf "$WORK"
  if [ "$rc" -ne 0 ] && [ ${#PARTS[@]} -gt 0 ]; then
    echo "failed (code $rc): deleting partial outputs: ${PARTS[*]}" >&2
    rm -f "${PARTS[@]}"
  fi
  rm -f "${OUTDIR:-.}/.$NAME.spec.tmp" 2>/dev/null || true
  exit "$rc"
}
trap 'cleanup $?' EXIT
case $TARGET in
  lint) preflight; log "lint ok" ;;
  prep) stage_prep ;;
  sign) stage_sign ;;
  image) stage_image ;;
  remote) stage_remote ;;
  *) die "target: lint | prep | sign | image | remote" ;;
esac
