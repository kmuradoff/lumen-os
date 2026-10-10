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
#   sign    [Mac]     tools/sign/apex_sign.py (every APEX re-signed with the Lumen APEX keys; the key-free
#                     payload rebuild / compression / deapexer checks run on the laptop over ssh, on its
#                     copy of the unsigned tar) -> tools/sign/sign_tar.py --apex-dir with the release
#                     keys (~/.lumen-keys, never on the laptop) -> sign_tar.py --verify -> lumen_checks.py
#                     signed (only signatures, seinfo, otacerts and the pinned APEXes changed; dex bytes
#                     identical so the odex stay valid) -> $OUTDIR/system_tv_lumen_v1_signed.tar
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
#     persist.z9x.* / ro.z9x.* props stay. Props: ro.z9x.version=$VER (system), ro.lumen.version=$VER
#     (product); About: ro.build.display.id = "Lumen OS $VER (<build id>, <date>)",
#     ro.lineage.display.version = "$VER (kmuradoff)", row titles from Z9xLineagePlatformOverlay
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
#     and SetupWraith are required to stay (GMS=1). See REMOVE below.
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
#     privapp allow-lists for the two priv-apps; default permissions for Lumen Home (RECORD_AUDIO,
#     + READ_TV_LISTINGS in 1.0.0)
#     and the updater (POST_NOTIFICATIONS, L-OTA's file).
#   - OTA plumbing from L-OTA (tools/ota/image, HANDOFF.md): z9x_ota.rc + /system/etc/z9x/z9x_ota.sh,
#     update_verifier.rc REPLACED (stock behaviour on normal boots), ota_props.txt (build_id,
#     version_code, keys, ab_ota_partitions without vbmeta, gate) in the product build.prop.
#     No OTA is run and nothing is published in v1; the MTK Codec2 libs stay in the owner's (private)
#     image; VARIANT=public (1.0.1, below) builds the publishable image with z9x_blobs instead.
#   - boot animation (1.0.0): Lumen "Aperture" (tools/brand/gen_bootanim.py: the 2A mark opening like an
#     iris + the hand-drawn "lumen" wordmark, no font, no XGIMI/Lineage asset; regenerate it there, the 1.0
#     "Beam" generator tools/lumen/gen_bootanim.py is legacy) replaces system/product/media/bootanimation.zip
#     (the -dark symlink stays). 1.0 had: Lumen "Beam" (tools/lumen/gen_bootanim.py, own render of Apache-2.0 Roboto, no
#     XGIMI/Lineage asset) replaces system/product/media/bootanimation.zip (the -dark symlink stays).
#   - MTK Codec2 store plugin: 6 stock libs (libcodec2store.so dropped, never loaded; PLAN X6).
#   - APEX (2026-10-07, docs/keys.md#apex): every APEX re-signed (new payload + container key per module,
#     the APKs inside on the release keys); no AOSP test certificate is left anywhere in the image.
#   - 1.0.1: boot rescue of L-OTA (z9x_rescue.rc + /system/etc/z9x/z9x_rescue.sh, ro.z9x.rescue=1 in
#     ota_props.txt: 3 starts in a row without boot_completed -> fastbootd), the rewritten gate
#     (z9x_ota.sh, boot HAL through service call), ro.z9x.report.url= (empty: problem reports stay off),
#     Lumen Home's privapp list + WRITE_SECURE_SETTINGS (screensaver default one-shot).
#   - 1.0.1 final (2026-10-09): UI resolution 1080p / 2K (2560x1440, the default) / 4K, same 960x540 dp
#     layout, picked in Projector settings, automatic fallback to 1080p (overlay/v1/z9x_uires/README.md):
#     /system/etc/z9x/z9x_uires.sh (0755) is added and system/product/etc/init/init.lineage.atv.scaling.rc
#     is REPLACED by the uires rc (LineageOS's file set ro.config.size_override 1920,1080 and
#     density_override 320 'on fs'; the uires rc sets them once, from the script's choice, 1080p defaults);
#     overlay/v1/z9x_uires/product_prop.txt goes into the product build.prop after product_prop_v1.txt
#     (ro.surface_flinger.max_graphics_* 3840x2160 over the vendor's 1920x1080 cap, ro.z9x.uires.allow=1).
#     Preflight runs its host tests. BUILD_ID_SUFFIX (e.g. b): build id lumen-$VER-<date><suffix>, so a
#     rebuild on the same date is a different build for the updater and the package-cache reset.
#   - 1.0.1 final, 4K default (20261009c, owner decision 2026-10-09): the 20261009b switch alone could not
#     work (the vendor GOP scales by panel / the panel ini's osdWidth x osdHeight, read once by the MI
#     daemon). z9x_uires.sh now binds a RAM copy of that ini with the mode's region read-only over the
#     vendor file at 'on fs' of the PRODUCT rc (after the vendor's mount_all of /vendor/tvconfig, before
#     midaemon at post-fs and the HWC at late-fs; the vendor partition is never written, no XGIMI file is
#     in the image). Modes: 4K (3840x2160 @ 640, the default) and 1080p from the app, 2K only from the
#     debug file /metadata/z9x_uires/debug. Same image members as 20261009b; preflight and check_image.py
#     also lock the mode table, the script's mounts, and that nothing else of ours touches /vendor/tvconfig.
#   - 1.0.1 final, 20261009e: Z9xFrameworkKeysOverlay sets config_maxUiWidth 3840 (TvFrameworkOverlay's 1920
#     made WindowManager run the 4K start of 20261009d at 1920x1080, so it fell back); preflight requires it
#     while ro.z9x.uires.allow=1 (lumen_checks.py maxui). Z9xProjector: the OFF switch is visible again.
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
#   - PackageManager parse cache ON (pm.boot.disable_package_cache gone). The image has ONE fixed mtime
#     (mkfs.erofs -T, compact 32-byte inodes; --mkfs-time is forbidden, see stage_image) and the build
#     fingerprint stays the base one (C18), so z9x_ota.sh (exec at post-fs-data, before system_server)
#     clears /data/system/package_cache once per ro.z9x.build_id: every Lumen build is parsed afresh.
#   - sysconfig: stopped=false for 4 Google packages (fresh wipe), LineageOS stats components off.
#   - VARIANT=private (the default) builds the owner's PRIVATE image exactly as before (MTK Codec2 libs and
#     the XGIMI/MediaTek audio files embedded, check_image.py --variant private). Never publish it or a
#     payload made from it (tools/ota/make_ota.py refuses it without --private; tools/ota/check_release_assets.py
#     gates any release).
#
# VARIANT=public (1.0.1, 2026-10-09; tools/ota/image/HANDOFF.md section 2): the same content without any
# MediaTek / XGIMI file, publishable:
#   - the 6 Codec2 libs are 0-byte placeholders (0644, system_lib_file); the base's three XGIMI/MediaTek
#     audio files (system/etc/xgimi/audio_policy_configuration.xml, libstagefright_foundation{,64}.so) are
#     removed and xgimi_compat.rc is replaced by tools/ota/image/xgimi_compat_public.rc (no bind of them);
#     z9x_blobs.sh + z9x_blobs.rc + blobs_allow.txt are added: at post-fs-data they bind the user's own
#     copies of all nine files from z9x_blobs<slot> (made by the installer's 'blobs' step). Without that
#     partition a public install has no sound and no secure video.
#   - runtime marks (docs/ota.md "Variants"): ro.z9x.variant=public (the private image has no such prop),
#     ro.z9x.ota.manifest_url = .../update-public.json (private images keep update-stable.json), and a build
#     id whose suffix ends in 'p' (BUILD_ID_SUFFIX, required; private suffixes never end in 'p'), so a delta
#     (chosen by from_build_id) never crosses variants.
#   - the boot side of the Google services add-on (both public editions; it acts only on ro.z9x.gms=0 with the
#     user's own z9x_gapps<slot>, made by the installer's 'gapps --zip FILE' from his MindTheGapps download):
#     z9x_gapps.sh + z9x_gapps.rc + gapps_allow.txt + the sysconfig layer z9x-gapps-sysconfig.xml
#     (system/etc/z9x/gapps/sysconfig/z9x-gapps.xml); tools/ota/image/HANDOFF.md section 2b.
#   - outputs are named system_tv_lumen_v1_public* (never the private files); 'remote' also writes
#     $LUMEN_OUT/release/lumen-os-$VER-system.img + SHA256SUMS + SHA256SUMS.sig (OTA key, Mac only: what the
#     installer checks with installer/certs/ota.x509.pem); check_image.py --variant public is the gate, and
#     tools/ota/check_release_assets.py (MediaTek / XGIMI files, personal data) runs on the image BEFORE
#     SHA256SUMS is signed. overlay/v1/c2store is not even sent to the laptop.
#
# GMS=0 (1.0.1, 2026-10-09; docs/NOGMS_PLAN.md, owner decisions): "Lumen OS без Google", the only edition
# that is ever published (the Google edition, GMS=1, never is). Allowed only with VARIANT=public:
#   - the 26 paths of tools/lumen/nogms_remove.txt leave the image (MindTheGapps ATV, LineageCustomizer,
#     LineageGoogleSetupWraithOverlay; same base tar, a longer REMOVE, no other member changes) and
#     overlay/v1/z9x-sysconfig-nogms.xml is the sysconfig (the org.z9x and LineageParts entries only);
#   - runtime marks: ro.z9x.gms=0 (GMS=1 images have no such line), ro.z9x.ota.manifest_url =
#     .../update-public-nogms.json, a build id whose suffix ends in 'np' (an 'n' before the variant letter
#     is reserved for no-Google builds), so a delta never crosses editions;
#   - outputs are named system_tv_lumen_v1_public_nogms*; 'remote' writes the release files
#     $RELDIR/lumen-os-$VER-nogms-system.img + SHA256SUMS + SHA256SUMS.sig (a GMS=1 build writes none);
#     check_image.py --gms 0 (no Google signer, path, package, prop or config entry) is the gate.
#
# Inputs (defaults relative to this script, i.e. ~/z9x/tools on the laptop, gsi/tools on the Mac):
#   $OUTDIR/system_tv_v4.tar      ../overlay/v1/*  ../overlay/apps_v1/*.apk (+ optional PINS.sha256)
#   ./patch_tar.py  ./lumen/{lumen_checks.py,apkinfo.py,dexpreopt_lumen.sh}
#   ./ota/image/{z9x_ota.rc,z9x_ota.sh,z9x_rescue.rc,z9x_rescue.sh,update_verifier.rc,
#                z9x-updater-default-permissions.xml,ota_props.txt}
#     VARIANT=public: ./ota/image/{z9x_blobs.sh,z9x_blobs.rc,blobs_allow.txt,xgimi_compat_public.rc} (no c2store),
#                     ./ota/image/{z9x_gapps.sh,z9x_gapps.rc,gapps_allow.txt,z9x-gapps-sysconfig.xml}, ./ota/gapps_allow.py
#   ../overlay/v1/z9x_uires/{z9x_uires.sh,init.lineage.atv.scaling.rc,product_prop.txt,test/}
#   ./sign/{testcerts,release_certs}/*.x509.pem (public certificates only)
# Env: BASE OUTDIR TOOLS V1 APPS OTAIMG MKFS FSCK DUMP MIN_FREE_KB (8000000) INCREMENTAL_SUFFIX (lumen10)
#      BUILD_DATE (yyyymmdd, default today UTC) MTIME_EPOCH (mtime of added members, default now) DEXPREOPT (1 on Linux, 0 elsewhere) LINEAGE (~/lineage)
#      BUILD_ID_SUFFIX (default empty; 1-4 of [a-z0-9], starting with a letter: lumen-$VER-<date><suffix>)
#      ALLOW_MISSING_APKS=1 (dry run without the Home/Setup/Updater/Projector APKs: *_partial outputs)
#      ALLOW_NO_POWER_RC=1 (dry run without L-PROJECTOR's z9x_power*.rc)  KEYS_DIR (~/.lumen-keys, sign)
#      ALLOW_TEST_KEYS=1 (image of the unsigned tar for a lab test; ro.z9x.keys becomes 'test')
#      VARIANT (private | public, default private; see above)  GMS (1 | 0, default 1; 0 only with VARIANT=public)
#      RELDIR (release files of 'remote', default $LUMEN_OUT/release)
#      BUILDER (user@host of the build laptop) BUILDER_KEY (its ssh key; else SSH_KEY, else ~/.ssh/id_ed25519):
#      sign / remote only, from the environment or ~/.config/lumen/builder.env (personal, never in the repo)
#      LUMEN_OUT (gsi/build/lumen_v1)
#      REMOTE_UNSIGNED (sign: the laptop's copy of the unsigned tar, default z9x/out/<its name>)
set -euo pipefail

H=$(cd "$(dirname "$0")" && pwd)
TOOLS=${TOOLS:-$H}
TARGET=${1:-prep}
V1=${V1:-$TOOLS/../overlay/v1}
APPS=${APPS:-$TOOLS/../overlay/apps_v1}
OTAIMG=${OTAIMG:-$TOOLS/ota/image}
# 1.0.1 UI resolution (overlay/v1/z9x_uires/README.md section 1)
UIRES=$V1/z9x_uires
UIRES_RC=system/product/etc/init/init.lineage.atv.scaling.rc
LT=$TOOLS/lumen
CHK=$LT/lumen_checks.py
PT=$TOOLS/patch_tar.py
TESTCERTS=$TOOLS/sign/testcerts
RELCERTS=$TOOLS/sign/release_certs
INCREMENTAL_SUFFIX=${INCREMENTAL_SUFFIX-lumen10}
BUILD_DATE=${BUILD_DATE:-$(date -u +%Y%m%d)}
# a second build of one date (1.0.1 final over the 20261009 test build): another ro.z9x.build_id
BUILD_ID_SUFFIX=${BUILD_ID_SUFFIX-}
MTIME_EPOCH=${MTIME_EPOCH:-$(date +%s)}
NAME=lumen_v1
# private (default: the owner's image, unchanged) | public (no MediaTek / XGIMI file; outputs named
# system_tv_lumen_v1_public*, never the private ones)
VARIANT=${VARIANT:-private}
case $VARIANT in
  private) ;;
  public) NAME=lumen_v1_public ;;
  *) echo "ERROR: VARIANT must be private or public (got '$VARIANT')" >&2; exit 1 ;;
esac
# GMS=0: Lumen OS without Google (see above); public only (owner decision 2026-10-09)
GMS=${GMS:-1}
case $VARIANT:$GMS in
  *:1) ;;
  public:0) NAME=${NAME}_nogms ;;
  private:0) echo "ERROR: GMS=0 needs VARIANT=public (the no-Google edition is a public image only)" >&2; exit 1 ;;
  *) echo "ERROR: GMS must be 1 or 0 (got '$GMS')" >&2; exit 1 ;;
esac
export GMS
EDITION_SFX=; [ "$GMS" = 0 ] && EDITION_SFX=-nogms
# docs/ota.md "Variants": each edition reads its own manifest file
MANIFEST_BASE=https://github.com/kmuradoff/lumen-os/releases/latest/download
CHANNEL=$([ "$VARIANT" = public ] && echo public || echo stable)$EDITION_SFX
MANIFEST_URL=$MANIFEST_BASE/update-$CHANNEL.json
# Lumen OS release (1.0.1; 1.0.0 shipped 2026-10-08): user-facing version and the updater's version_code.
# Scheme (tools/ota/image/ota_props.txt, docs/ota.md): version_code = major*10000 + minor*100 + patch + 1,
# so 1.0.0 = 10001 is offered over the 1.0 test builds (version_code 10000, build_id lumen-1.0-<date>)
# and 1.0.1 = 10002 over 1.0.0.
# ro.z9x.version, ro.lumen.version, the build id lumen-$VER-<date>[suffix] and the version_code are all
# checked against VER in the preflight.
VER=1.0.1
BUILD_ID=lumen-$VER-$BUILD_DATE$BUILD_ID_SUFFIX
case $(uname -s) in Linux) DEXPREOPT=${DEXPREOPT:-1} ;; *) DEXPREOPT=${DEXPREOPT:-0} ;; esac
pick() { for c in "$@"; do if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return 0; fi; done; return 1; }
LH=${LINEAGE:-$HOME/lineage}/out/host/linux-x86/bin
# mkfs.erofs: the Android 14 erofs-utils of the Lineage tree (1.7.1), NOT /usr/bin (1.9). 1.9 lays inline
# symlink data across block boundaries, which the Z9X kernel (5.15) refuses ("inline data cross block
# boundary", EUCLEAN): Lumen OS 1.0 images died in the first seconds of boot because of '/etc'
# (2026-10-08). tools/erofs_kcheck.py gates every image on the kernel's rule whatever the tool.
MKFS=${MKFS:-$(pick "$LH/mkfs.erofs" || true)}
FSCK=${FSCK:-$(pick fsck.erofs "$LH/fsck.erofs" || true)}
DUMP=${DUMP:-$(pick dump.erofs "$LH/dump.erofs" || true)}

log() { echo "$(date +%T) $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
# BUILDER / BUILDER_KEY of the build laptop (sign, remote): the environment wins over ~/.config/lumen/builder.env
builder_env() {
  local b=${BUILDER:-} k=${BUILDER_KEY:-}
  [ ! -f "$HOME/.config/lumen/builder.env" ] || . "$HOME/.config/lumen/builder.env"
  BUILDER=${b:-${BUILDER:-}}
  BUILDER_KEY=${k:-${BUILDER_KEY:-${SSH_KEY:-$HOME/.ssh/id_ed25519}}}
  [ -n "$BUILDER" ] || die "set BUILDER=user@host of the build laptop (environment or ~/.config/lumen/builder.env)"
}
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi | awk '{print $1}'; }
sha256in() { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi | awk '{print $1}'; }
need() { [ -s "$1" ] || die "missing or empty file: $1"; }
# ksh (1.0.1): the device shell is mksh, a ksh; ksh93 rejects some patterns dash accepts (L-OTA review).
# Output only on a failure (ksh -n also prints style warnings for scripts it accepts).
shlint() {
  local sl out
  for sl in dash "busybox ash" sh ksh; do
    if command -v ${sl%% *} >/dev/null 2>&1; then out=$($sl -n "$1" 2>&1) || { echo "$out" >&2; die "$1: syntax ($sl)"; }; fi
  done
}
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
# 1.0.1 boot rescue (HANDOFF.md section 1): 3 failed starts in a row -> fastbootd, byte-identical too
RESCUE_RC=z9x_rescue.rc; RESCUE_SH=z9x_rescue.sh
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
# VARIANT=public (tools/ota/image/HANDOFF.md section 2): the base's XGIMI / MediaTek audio files (bound by the
# base's xgimi_compat.rc on the private image) leave the image; z9x_blobs.sh binds the user's own copies
# (blobs_allow.txt lines with bind=), xgimi_compat_public.rc replaces xgimi_compat.rc
XGIMI_FILES=(system/etc/xgimi/audio_policy_configuration.xml system/etc/xgimi/libstagefright_foundation.so
             system/etc/xgimi/libstagefright_foundation64.so)
BLOBS_SH=z9x_blobs.sh; BLOBS_RC=z9x_blobs.rc; BLOBS_ALLOW=blobs_allow.txt; COMPAT_PUB=xgimi_compat_public.rc
# VARIANT=public: the boot side of the Google services add-on (HANDOFF.md section 2b)
GAPPS_SH=z9x_gapps.sh; GAPPS_RC=z9x_gapps.rc; GAPPS_ALLOW=gapps_allow.txt; GAPPS_XML=z9x-gapps-sysconfig.xml
if [ "$VARIANT" = public ]; then REMOVE+=("${XGIMI_FILES[@]}"); fi
# GMS=0: the Google members (tools/lumen/nogms_remove.txt, checked in preflight; read here for every run so
# the preflight and the host test see the same list)
REMOVE_STATIC=("${REMOVE[@]}")
NOGMS_LIST=$LT/nogms_remove.txt
REMOVE_GMS=()
if [ -f "$NOGMS_LIST" ]; then
  while IFS= read -r l || [ -n "$l" ]; do
    l=${l%%#*}; l=$(printf '%s' "$l" | tr -d '[:space:]')
    [ -n "$l" ] && REMOVE_GMS+=("$l")
  done < "$NOGMS_LIST"
fi
if [ "$GMS" = 0 ]; then REMOVE+=(${REMOVE_GMS[@]+"${REMOVE_GMS[@]}"}); fi
# compatibility props that must stay byte-identical to the base (brand spec 5, C18)
COMPAT_SYS='^ro\.(product\.system\.|system\.build\.)|^ro\.build\.(id|tags|type|version\.(release|sdk|security_patch|release_or_codename|codename))$|^ro\.build\.(flavor|description|product|characteristics|fingerprint)$'
COMPAT_PRODUCT='^ro\.product\.(product\.|build\.)|^ro\.build\.characteristics$'

# ======================================================================== preflight of the inputs
preflight() {
  [ -f "$PT" ] || die "no $PT"
  grep -q -- "'--mkdir'" "$PT" && grep -q 'def opt_label' "$PT" || die "$PT is not the v6.2b patch_tar.py (--mkdir + LABEL)"
  for f in "$CHK" "$LT/apkinfo.py" "$LT/dexpreopt_lumen.sh"; do need "$f"; done
  [ -d "$TESTCERTS" ] && [ -s "$RELCERTS/platform.x509.pem" ] || die "no public certificates in $TESTCERTS / $RELCERTS"
  if find "$RELCERTS" "$TESTCERTS" -type f \( -name '*.pk8' -o -name '*.key' -o -name '*.pem.key' \) | grep -q .; then die "a private key file in the cert dirs"; fi
  if grep -rlE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' "$RELCERTS" "$TESTCERTS" >/dev/null 2>&1; then die "a private key in the cert dirs"; fi
  [ -s "$RELCERTS/apex/FINGERPRINTS.txt" ] || die "no public APEX keys in $RELCERTS/apex (tools/sign/gen_keys.sh)"
  case $INCREMENTAL_SUFFIX in *[!A-Za-z0-9_]*) die "INCREMENTAL_SUFFIX: only [A-Za-z0-9_]" ;; esac
  case $BUILD_DATE in [0-9][0-9][0-9][0-9][01][0-9][0-3][0-9]) ;; *) die "BUILD_DATE must be yyyymmdd" ;; esac
  # the build id ends in the date: a suffix starting with a digit would read as another date
  case $BUILD_ID_SUFFIX in ''|[a-z]|[a-z][a-z0-9]|[a-z][a-z0-9][a-z0-9]|[a-z][a-z0-9][a-z0-9][a-z0-9]) ;;
    *) die "BUILD_ID_SUFFIX: 1-4 of [a-z0-9], starting with a letter (got '$BUILD_ID_SUFFIX')" ;; esac
  suffix_check
  case $MTIME_EPOCH in *[!0-9]*|'') die "MTIME_EPOCH must be a UNIX time" ;; esac
  [ "$MTIME_EPOCH" -gt 1700000000 ] || die "MTIME_EPOCH $MTIME_EPOCH is before 2023 (the parse cache would keep stale entries)"

  for f in "${RCS[@]}" "${SCRIPTS[@]}" z9x_diag.rc z9x-sysconfig.xml build_prop_v1.txt product_prop_v1.txt \
           audio_policy_engine_stream_volumes.xml z9x-projector-default-permissions.xml \
           z9x-airplay-default-permissions.xml z9x-home-default-permissions.xml \
           privapp-permissions-z9xhome.xml privapp-permissions-z9xsetup.xml bootanimation.zip LineageCustomizer.apk \
           z9x-sysconfig-nogms.xml; do
    need "$V1/$f"
  done
  need "$LT/gen_customizer.py"
  nogms_preflight
  for f in "$OTA_RC" "$OTA_SH" "$RESCUE_RC" "$RESCUE_SH" "$OTA_UV" "$OTA_PERM" ota_props.txt "$GAPPS_XML"; do need "$OTAIMG/$f"; done
  for f in z9x_uires.sh init.lineage.atv.scaling.rc product_prop.txt test/run.sh; do need "$UIRES/$f"; done
  [ ! -e "$V1/z9x_features.rc" ] || die "$V1/z9x_features.rc exists (v6 kill switch, gone since v6.1)"
  for k in "${KL[@]}"; do need "$V1/keylayout/$k.kl"; done
  need "$V1/keylayout/Generic.kl"
  for k in "${IDC[@]}"; do need "$V1/idc/$k.idc"; done
  n=$(ls "$V1"/keylayout/*.kl | wc -l); [ "$n" -eq 14 ] || die "$V1/keylayout has $n .kl, expected 14"
  n=$(ls "$V1"/idc/*.idc | wc -l); [ "$n" -eq 8 ] || die "$V1/idc has $n .idc, expected 8"
  for f in "$V1"/*.rc "$V1"/*.sh "$V1"/*.txt "$V1"/*.xml "$V1"/keylayout/*.kl "$V1"/idc/*.idc "$OTAIMG"/* \
           "$UIRES"/* "$UIRES"/test/* "$UIRES"/test/stubs/*; do
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
  # 1.0.1 boot rescue: same block-device / vbmeta rule, without the bare word 'fastboot' (its one reboot
  # is init's 'reboot,fastboot' into fastbootd, HANDOFF.md); that must be its only sys.powerctl use
  [ "$(head -n1 "$OTAIMG/$RESCUE_SH")" = '#!/system/bin/sh' ] || die "$RESCUE_SH: first line"
  shlint "$OTAIMG/$RESCUE_SH"
  grep -qE '^ +exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_rescue\.sh count$' "$OTAIMG/$RESCUE_RC" \
    || die "$RESCUE_RC does not exec /system/etc/z9x/z9x_rescue.sh count"
  if grep -v '^[[:space:]]*#' "$OTAIMG/$RESCUE_SH" | grep -qE '(dd|cat|echo|printf)[^|]*>[[:space:]]*/dev/block|of=/dev/block|avbtool|vbmeta'; then
    die "$RESCUE_SH writes a block device / vbmeta (never allowed)"
  fi
  [ "$(grep -v '^[[:space:]]*#' "$OTAIMG/$RESCUE_SH" | grep 'sys\.powerctl' | tr -s ' ')" = ' setprop sys.powerctl reboot,fastboot' ] \
    || die "$RESCUE_SH: the only sys.powerctl use must be 'setprop sys.powerctl reboot,fastboot'"
  if [ "$VARIANT" = public ]; then public_preflight; fi
  # ---- 1.0.1 UI resolution (overlay/v1/z9x_uires/README.md section 1): the script chooses the mode at
  # 'on fs', the replaced LineageOS rc sets the four mode props once (stock 1080p values when the script
  # published nothing), the product snippet lifts SurfaceFlinger's cap
  [ "$(head -n1 "$UIRES/z9x_uires.sh")" = '#!/system/bin/sh' ] || die "z9x_uires.sh: first line"
  shlint "$UIRES/z9x_uires.sh"
  grep -qE '^ +exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_uires\.sh fs$' "$UIRES/init.lineage.atv.scaling.rc" \
    || die "init.lineage.atv.scaling.rc does not exec z9x_uires.sh fs"
  [ "$(grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep 'sys\.powerctl' | tr -s ' ')" = ' setprop sys.powerctl reboot,z9x-uires' ] \
    || die "z9x_uires.sh: the only sys.powerctl use must be reboot,z9x-uires"
  if grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep -oE 'setprop [^ ]+' | grep -vqE '^setprop (sys\.z9x\.ui_res\.[a-z_]+|sys\.powerctl)$'; then
    die "z9x_uires.sh: setprop only of sys.z9x.ui_res.* (and the one sys.powerctl)"
  fi
  if grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep -qE '/dev/block|of=/dev|avbtool|vbmeta|IGmpf|service call|/data/'; then
    die "z9x_uires.sh: block device / vbmeta / HAL / /data access (never allowed)"
  fi
  [ "$(nocomment "$UIRES/init.lineage.atv.scaling.rc" | awk '/^on /{t=$0} /^ setprop (vendor\.display-size|vendor\.mstar\.resize\.framebuffer|ro\.config\.)/{print t "|" $0}')" = \
    "$(printf '%s\n' 'on fs| setprop vendor.display-size ${sys.z9x.ui_res.display_size:-1920x1080}' \
       'on fs| setprop vendor.mstar.resize.framebuffer ${sys.z9x.ui_res.resize_fb:-1}' \
       'on fs| setprop ro.config.size_override ${sys.z9x.ui_res.size_override:-1920,1080}' \
       'on fs| setprop ro.config.density_override ${sys.z9x.ui_res.density_override:-320}')" ] \
    || die "init.lineage.atv.scaling.rc: the four mode props must be set once, 'on fs', with the stock 1080p defaults"
  nocomment "$UIRES/init.lineage.atv.scaling.rc" | awk '/^on fs$/{f=1; next} /^on /{f=0} f && /^ exec /{e=NR} f && /^ setprop /{if (!s) s=NR} END{exit !(e && s && e < s)}' \
    || die "init.lineage.atv.scaling.rc: the z9x_uires.sh exec must come before the setprops of 'on fs'"
  for l in ro.surface_flinger.max_graphics_width=3840 ro.surface_flinger.max_graphics_height=2160; do
    grep -qx "$l" "$UIRES/product_prop.txt" || die "z9x_uires/product_prop.txt: '$l' missing"
  done
  # 1 = 4K offered (default 4K), 0 = kill switch: always 1080p (Lumen OS 1.0.1 ships 0)
  [ "$(grep -cxE 'ro\.z9x\.uires\.allow=[01]' "$UIRES/product_prop.txt")" = 1 ] \
    || die "z9x_uires/product_prop.txt: exactly one 'ro.z9x.uires.allow=0|1' line needed"
  # the mode props belong to the uires rc alone (ro.* can be set once; a build.prop line would win over it)
  if cat "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$OTAIMG/ota_props.txt" | grep -v '^#' \
      | grep -qE '^(ro\.config\.(size|density)_override|vendor\.display-size|vendor\.mstar\.resize\.framebuffer|ro\.surface_flinger\.max_graphics_(width|height))='; then
    die "a prop snippet other than z9x_uires/product_prop.txt sets a UI resolution prop"
  fi
  if grep -v '^#' "$UIRES/product_prop.txt" | grep -qE '^(ro\.config\.|vendor\.)'; then
    die "z9x_uires/product_prop.txt: ro.config.* / vendor.* belong to the rc (vendor.* cannot be set from build.prop)"
  fi
  for f in "$V1"/*.rc "$OTAIMG"/*.rc; do
    if nocomment "$f" | grep -qE 'ro\.config\.(size|density)_override|vendor\.display-size|vendor\.mstar\.resize\.framebuffer'; then
      die "${f##*/} sets a UI resolution prop (only z9x_uires/init.lineage.atv.scaling.rc may)"
    fi
  done
  # 20261009c (owner decision 2026-10-09): 4K is the default and, with 1080p, the only mode the app may pick;
  # 2K is a debug mode (the file /metadata/z9x_uires/debug only). This is the reviewed table: a new value
  # needs a device test first (z9x_uires README section 3)
  [ "$(awk '/^DEFAULT=/{print $1}' "$UIRES/z9x_uires.sh")" = DEFAULT=2160 ] || die "z9x_uires.sh: DEFAULT must be 2160 (4K default)"
  [ "$(sed -n "/^MODES='/,/'\$/p" "$UIRES/z9x_uires.sh" | sed -e "s/^MODES='//" -e "s/'\$//")" = \
    "$(printf '%s\n' '1080 - 1920x1080 1 1920,1080 320 app' '2160 3840x2160 3840x2160 1 3840,2160 640 app' \
       '1440 2560x1440 2560x1440 1 2560,1440 427 debug')" ] \
    || die "z9x_uires.sh: MODES is not the reviewed table (1080p stock, 4K 1:1 @ 640 from the app, 2K debug only)"
  # the 4K OSD region is a RAM copy of the panel ini in a tmpfs of the script's own, remounted read-only, then
  # bound over the vendor file: the vendor partition is never written. The only mount calls are that tmpfs
  # (RAM=/dev/z9x_uires), its read-only remount (its own mount point only: on the Z9X, 2026-10-09, toybox's
  # 'remount,bind,ro' of a file bound from /dev remounted the whole /dev tmpfs, and 'bind,ro' gave a
  # read-write bind), the plain bind of the copy and the umounts of the bind and the tmpfs; no write or file
  # operation on a /vendor path or on the bound ini
  um=$(grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep -oE '(^|[^A-Za-z0-9_.-])u?mount +(-[^ ]*|"\$)[^;&|)]*' \
    | sed -E -e 's/^[^a-z]//' -e 's/ *2>\/dev\/null//' -e 's/[[:space:]]+$//' | sort -u) || true
  for l in 'mount -t tmpfs -o size=$RAM_SIZE,mode=0755 z9x_uires "$ram"' 'mount -o remount,ro "$ram"' 'mount -o bind "$copy" "$src"'; do
    grep -qxF "$l" <<<"$um" || die "z9x_uires.sh: no '$l' (its tmpfs, that tmpfs's read-only remount, the bind of the RAM copy)"
  done
  while IFS= read -r l; do
    case $l in 'mount -t tmpfs -o size=$RAM_SIZE,mode=0755 z9x_uires "$ram"'|'mount -o remount,ro "$ram"'|'mount -o bind "$copy" "$src"'|'umount "$src"'|'umount "$ram"') ;;
      *) die "z9x_uires.sh: unexpected mount '$l' (only its tmpfs, that tmpfs's ro remount, the bind of the RAM copy, their umounts)" ;; esac
  done <<<"$um"
  grep -qE '^RAM=/dev/z9x_uires( |$)' "$UIRES/z9x_uires.sh" || die "z9x_uires.sh: RAM must be /dev/z9x_uires (its own tmpfs)"
  if grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep -qE 'remount,bind|bind,ro|remount,rw|-o rw'; then
    die "z9x_uires.sh: a bind remount, 'bind,ro' or a read-write (re)mount"
  fi
  if grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" | grep -qE '>[[:space:]]*"?(\$R)?/vendor'; then die "z9x_uires.sh writes into /vendor"; fi
  if grep -v '^[[:space:]]*#' "$UIRES/z9x_uires.sh" \
      | grep -qE '(^|[^A-Za-z0-9_$.-])(rm|mv|cp|chmod|chown|chcon|touch|dd|truncate|ln|restorecon|setfattr|tee)[[:space:]][^;&|]*("\$src"|"\$R\$PN"|"\$R\$CUST"|/vendor)'; then
    die "z9x_uires.sh: a file operation on a /vendor path"
  fi
  # ... and nothing else of ours touches /vendor/tvconfig (comments aside): z9x_uires.sh is its only user
  for f in "$V1"/*.rc "$V1"/*.sh "$OTAIMG"/*.rc "$OTAIMG"/*.sh "$UIRES/init.lineage.atv.scaling.rc"; do
    [ -f "$f" ] || continue
    if nocomment "$f" | grep -q 'tvconfig'; then die "${f##*/} touches /vendor/tvconfig (only z9x_uires.sh may)"; fi
  done
  # the rc must stay in /product/etc/init: init parses it after /vendor/etc/init, so its 'on fs' exec runs
  # after the vendor's mount_all of /vendor/tvconfig and before 'on post-fs' (midaemon); from
  # /system/etc/init every start would be 1080p (README section 2)
  [ "$UIRES_RC" = system/product/etc/init/init.lineage.atv.scaling.rc ] || die "UIRES_RC must stay in system/product/etc/init"
  # 20261009e: WindowManager's max UI width. TvFrameworkOverlay sets config_maxUiWidth 1920 and DisplayContent
  # clamps the base (and any forced) width to it, so the 4K start of 20261009d ran WM at 1920x1080 over a
  # 3840x2160 display and fell back (README "Why"). While the image allows the 4K mode (ro.z9x.uires.allow=1
  # in the product snippet), our framework RRO must set it to at least 3840 (the 4K row of MODES above);
  # read from the APK itself (tool-free, cross-checked with aapt2 when one is found)
  if grep -v '^#' "$UIRES/product_prop.txt" | grep -qx 'ro.z9x.uires.allow=1'; then
    python3 "$CHK" maxui "$APPS/Z9xFrameworkKeysOverlay.apk" 3840 \
      || die "Z9xFrameworkKeysOverlay.apk: config_maxUiWidth must be >= 3840 while ro.z9x.uires.allow=1 (apps/Z9xFrameworkKeysOverlay)"
  fi
  sh "$UIRES/test/run.sh" > "$WORK/uires_test.log" 2>&1 || { tail -n 30 "$WORK/uires_test.log"; die "z9x_uires host tests failed"; }
  echo "z9x_uires host tests: $(tail -n 1 "$WORK/uires_test.log")"
  # ---- props
  if grep -q '\\' "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$UIRES/product_prop.txt" "$OTAIMG/ota_props.txt"; then die "backslash in a prop snippet"; fi
  for l in "ro.z9x.version=$VER" 'ro.z9x.clickcurve=1' 'persist.z9x.firstrun=1'; do
    grep -qx "$l" "$V1/build_prop_v1.txt" || die "build_prop_v1.txt: '$l' missing"
  done
  if grep -q '^pm\.boot\.disable_package_cache' "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt"; then
    die "pm.boot.disable_package_cache must stay off (parse cache on, member mtimes carry the build time)"
  fi
  for l in 'ro.product.locale=en-US' 'persist.sys.timezone=Europe/Moscow' 'bluetooth.device.default_name=XGIMI Z9X' \
           "ro.lumen.version=$VER" 'ro.lmk.swap_free_low_percentage=10' 'ro.lmk.thrashing_limit=30' \
           'ro.lmk.psi_complete_stall_ms=700' 'dalvik.vm.dex2oat-threads=4' 'dalvik.vm.background-dex2oat-threads=2' \
           'ro.adb.secure=1' 'persist.sys.usb.config=none' 'ro.z9x.report.url='; do
    grep -qx "$l" "$V1/product_prop_v1.txt" || die "product_prop_v1.txt: '$l' missing"
  done
  for l in "ro.product.ab_ota_partitions=$AB_OTA" 'ro.z9x.keys=release' 'ro.z9x.device=z9x' "ro.z9x.build_id=lumen-$VER-<DATE>" \
           'ro.z9x.rescue=1'; do
    grep -qx "$l" "$OTAIMG/ota_props.txt" || die "tools/ota/image/ota_props.txt: '$l' missing"
  done
  grep -qE '^ro\.z9x\.version_code=[0-9]+$' "$OTAIMG/ota_props.txt" || die "ota_props.txt: ro.z9x.version_code not an integer"
  # version_code follows VER (scheme above) and stays above the 1.0 test builds (10000), or the updater
  # would never offer this build over the air
  case $VER in [0-9]*.[0-9]*.[0-9]*) ;; *) die "VER=$VER: expected major.minor.patch" ;; esac
  vmaj=${VER%%.*}; vrest=${VER#*.}; vmin=${vrest%%.*}; vpat=${vrest#*.}
  case "$vmaj$vmin$vpat" in *[!0-9]*) die "VER=$VER: not numeric" ;; esac
  [ "$vmin" -lt 100 ] && [ "$vpat" -lt 100 ] || die "VER=$VER: minor and patch below 100 (version_code scheme)"
  OS_VERSION_CODE=$((vmaj * 10000 + vmin * 100 + vpat + 1))
  grep -qx "ro.z9x.version_code=$OS_VERSION_CODE" "$OTAIMG/ota_props.txt" \
    || die "ota_props.txt: ro.z9x.version_code must be $OS_VERSION_CODE for $VER (major*10000 + minor*100 + patch + 1)"
  [ "$OS_VERSION_CODE" -gt 10000 ] || die "version_code $OS_VERSION_CODE is not above the 1.0 test builds (10000)"
  case ",$AB_OTA," in *,vbmeta,*|*,system,*|*,vendor,*) die "ab_ota_partitions must not list vbmeta/system/vendor" ;; esac
  if cat "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$UIRES/product_prop.txt" "$OTAIMG/ota_props.txt" | grep -v '^#' \
      | grep -qE '^(ro\.product\.(system\.|product\.|system_ext\.|vendor\.|odm\.)?(brand|model|manufacturer|device|name)|ro\.build\.(fingerprint|tags|type|id|version\.release|version\.sdk)|ro\.setupwizard\.mode|ro\.build\.display\.id|ro\.lineage)='; then
    die "a prop snippet sets a compatibility / display prop directly (only lumen_v1.sh may patch display props)"
  fi
  # ro.z9x.variant=public and ro.z9x.gms=0 are lumen_v1.sh's (VARIANT=public, GMS=0 only); the manifest URL
  # line is substituted for them
  if cat "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$UIRES/product_prop.txt" "$OTAIMG/ota_props.txt" | grep -v '^#' \
      | grep -qE '^ro\.z9x\.(variant|gms)='; then
    die "a prop snippet sets ro.z9x.variant / ro.z9x.gms (only lumen_v1.sh VARIANT=public / GMS=0 may)"
  fi
  [ "$(grep -c '^ro\.z9x\.ota\.manifest_url=https://github\.com/kmuradoff/lumen-os/releases/latest/download/update-stable\.json$' "$OTAIMG/ota_props.txt")" = 1 ] \
    || die "ota_props.txt: expected one ro.z9x.ota.manifest_url=.../update-stable.json line (VARIANT=public replaces it)"
  dup=$(cat "$V1/build_prop_v1.txt" "$V1/product_prop_v1.txt" "$UIRES/product_prop.txt" "$OTAIMG/ota_props.txt" | grep -v '^#' | grep '=' | cut -d= -f1 | sort | uniq -d)
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
  # ---- Codec2 libs (v6.2b), pinned (the private image's; a public one has 0-byte placeholders and needs
  # no proprietary input)
  [ ${#C2LIBS[@]} -eq 6 ] && [ ${#C2SHA[@]} -eq 6 ] || die "C2LIBS/C2SHA must list 6 entries"
  if [ "$VARIANT" = private ]; then
    n=$(find "$V1/c2store" -type f | wc -l); [ "$n" -eq 6 ] || die "$V1/c2store has $n files, expected 6"
    for i in "${!C2LIBS[@]}"; do [ "$(sha256 "$V1/c2store/${C2LIBS[$i]}")" = "${C2SHA[$i]}" ] || die "c2store/${C2LIBS[$i]}: not the stock sha256"; done
  fi
  n=$(find "$V1/c2vndk" -type f | wc -l); [ "$n" -eq 2 ] || die "$V1/c2vndk has $n files, expected 2"
  for i in "${!VNDKLIBS[@]}"; do [ "$(sha256 "$V1/c2vndk/${VNDKLIBS[$i]}")" = "${VNDKSHA[$i]}" ] || die "c2vndk/${VNDKLIBS[$i]}: not the pinned rebuild"; done
  # ---- XML inputs
  python3 - "$V1" "$OTAIMG/$OTA_PERM" "$V1/z9x-sysconfig-nogms.xml" <<'PY' || die "XML check failed"
import os, sys, xml.etree.ElementTree as ET
d, otaperm, nogms = sys.argv[1], sys.argv[2], sys.argv[3]
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
              (LP, LP + '.lineagestats.StatsUploadJobService', 'false'),
              ('com.google.android.gms', 'com.google.android.gms.update.SystemUpdatePanoActivity', 'false')}, co
# GMS=0 (docs/NOGMS_PLAN.md section 3): exactly the main file's org.z9x states and LineageParts overrides, so
# the two files cannot drift apart; nothing that names a Google package
rn = ET.parse(nogms).getroot()
stn = {(e.get('package'), e.get('stopped')) for e in rn.findall('initial-package-state')}
con = {(e.get('package'), c.get('class'), c.get('enabled')) for e in rn.findall('component-override') for c in e}
assert stn == {x for x in st if x[0].startswith('org.z9x.')} and len(stn) == 6, stn
assert con == {x for x in co if x[0] == LP} and len(con) == 3, con
assert {c.tag for c in rn} == {'initial-package-state', 'component-override'}, {c.tag for c in rn}
bad = [v for e in rn.iter() for v in e.attrib.values() if 'google' in v.lower() or 'vending' in v]
assert not bad, 'Google names in %s: %s' % (nogms, bad)
def exc(f):
    return [(e.get('package'), sorted(q.get('name') for q in e.findall('permission'))) for e in ET.parse(f).getroot().findall('exception')]
assert exc(os.path.join(d, 'z9x-home-default-permissions.xml')) == [('org.z9x.home', ['android.permission.READ_TV_LISTINGS', 'android.permission.RECORD_AUDIO'])]
assert exc(os.path.join(d, 'z9x-airplay-default-permissions.xml')) == [('org.z9x.airplay', ['android.permission.POST_NOTIFICATIONS'])]
assert exc(os.path.join(d, 'z9x-projector-default-permissions.xml'))[0][0] == 'org.z9x.projector'
assert [e.get('package') for e in ET.parse(otaperm).getroot().findall('exception')] == ['org.z9x.updater']
for f, pkg in (('privapp-permissions-z9xhome.xml', 'org.z9x.home'), ('privapp-permissions-z9xsetup.xml', 'org.z9x.setup')):
    assert [e.get('package') for e in ET.parse(os.path.join(d, f)).getroot().findall('privapp-permissions')] == [pkg], f
r = ET.parse(os.path.join(d, 'audio_policy_engine_stream_volumes.xml')).getroot()
g = {x.findtext('name'): {v.get('deviceCategory'): v.get('ref') for v in x.findall('volume')} for x in r.findall('volumeGroup')}
assert len(g) == 12 and g['system']['DEVICE_CATEGORY_SPEAKER'] == 'MM' and g['system']['DEVICE_CATEGORY_HEADSET'] == 'MM'
print('xml ok: sysconfig (6 org.z9x + 4 Google packages not stopped, SetupWraith + LineageOS stats overrides; nogms: the 6 + the 3 stats), permissions, stream volumes')
PY
  # the Google services add-on's sysconfig layer (tools/ota/image, both public editions) = exactly the Google
  # entries of the Google edition's z9x.xml (main minus nogms), so the three files cannot drift apart
  python3 - "$V1/z9x-sysconfig.xml" "$V1/z9x-sysconfig-nogms.xml" "$OTAIMG/$GAPPS_XML" <<'PY' || die "$GAPPS_XML check failed"
import sys, xml.etree.ElementTree as ET
def ents(f):
    r = ET.parse(f).getroot()
    st = {(e.get('package'), e.get('stopped')) for e in r.findall('initial-package-state')}
    co = {(e.get('package'), c.get('class'), c.get('enabled')) for e in r.findall('component-override') for c in e}
    return r, st, co
_, st, co = ents(sys.argv[1])
_, stn, con = ents(sys.argv[2])
r, stg, cog = ents(sys.argv[3])
assert {c.tag for c in r} == {'initial-package-state', 'component-override'}, {c.tag for c in r}
assert stg == st - stn and len(stg) == 4, stg
assert cog == co - con and len(cog) == 3, cog
print('gapps sysconfig ok: %d package states + %d overrides = the Google entries of z9x-sysconfig.xml' % (len(stg), len(cog)))
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

# build ids never coincide across variants and editions (the updater picks a delta by from_build_id), one
# formula (also tools/sign/check_image.py): core = the suffix without its final 'p' when VARIANT=public;
# public suffixes end in 'p' (e.g. 'ep', the public twin of the private 'e'), private ones never do; GMS=0
# if and only if core ends in 'n' (the no-Google 'enp')
suffix_check() {
  local core=$BUILD_ID_SUFFIX
  if [ "$VARIANT" = public ]; then
    case $core in *p) core=${core%p} ;; *) die "VARIANT=public: BUILD_ID_SUFFIX must end in 'p' (e.g. ep; no-Google: enp), got '$BUILD_ID_SUFFIX'" ;; esac
  else
    case $core in *p) die "VARIANT=private: BUILD_ID_SUFFIX must not end in 'p' (reserved for public builds), got '$BUILD_ID_SUFFIX'" ;; esac
  fi
  case $GMS:$core in
    0:*n|1:|1:*[!n]) ;;
    0:*) die "GMS=0: BUILD_ID_SUFFIX must end in 'np' (e.g. enp), got '$BUILD_ID_SUFFIX'" ;;
    *) die "GMS=1: an 'n' before the variant letter is reserved for no-Google builds, got '$BUILD_ID_SUFFIX'" ;;
  esac
}

# GMS=0 input (docs/NOGMS_PLAN.md section 2): tools/lumen/nogms_remove.txt, 26 base paths under system/, no
# duplicate, nothing equal to / under / above a static REMOVE path (patch_tar would see a path twice), and
# bash reads the same list as Python
nogms_preflight() {
  need "$NOGMS_LIST"
  python3 - "$NOGMS_LIST" "${REMOVE_STATIC[*]}" "${REMOVE_GMS[*]}" <<'PY' || die "$NOGMS_LIST check failed"
import re, sys
lst, static, from_bash = sys.argv[1], sys.argv[2].split(), sys.argv[3].split()
paths = []
for raw in open(lst, encoding='utf-8'):
    line = raw.split('#', 1)[0].strip()
    if not line:
        continue
    assert re.fullmatch(r'system/[A-Za-z0-9_./-]+', line) and '..' not in line and '//' not in line \
        and not line.endswith('/'), 'bad path %r' % raw
    paths.append(line)
assert len(paths) == 26, '%d paths, expected 26' % len(paths)
assert len(set(paths)) == len(paths), 'duplicates: %s' % sorted({p for p in paths if paths.count(p) > 1})
for p in paths:
    for q in paths + static:
        if q != p and (q.startswith(p + '/') or p.startswith(q + '/')):
            raise AssertionError('%s overlaps %s' % (p, q))
    assert p not in static, '%s is already in the static REMOVE' % p
assert paths == from_bash, 'bash read %s' % from_bash
print('nogms_remove ok: %d paths' % len(paths))
PY
}

# VARIANT=public inputs (tools/ota/image/HANDOFF.md section 2): z9x_blobs.{sh,rc}, blobs_allow.txt and
# xgimi_compat_public.rc; the allow-list must name exactly the files the public image does not carry
public_preflight() {
  local f um
  for f in "$BLOBS_SH" "$BLOBS_RC" "$BLOBS_ALLOW" "$COMPAT_PUB"; do need "$OTAIMG/$f"; done
  [ "$(head -n1 "$OTAIMG/$BLOBS_SH")" = '#!/system/bin/sh' ] || die "$BLOBS_SH: first line"
  shlint "$OTAIMG/$BLOBS_SH"
  # exactly one exec at post-fs-data: after init.rc's part of it (default mount namespace, APEXes active),
  # before 'on boot' (audioserver, the vendor audio HAL, the media codecs)
  [ "$(nocomment "$OTAIMG/$BLOBS_RC")" = \
    "$(printf '%s\n' 'on post-fs-data' ' exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_blobs.sh')" ] \
    || die "$BLOBS_RC: expected only the post-fs-data exec of /system/etc/z9x/z9x_blobs.sh"
  # reads its partition, writes none; no lpdump (a binder client of lpdumpd, which init cannot start while
  # it waits for this exec: every boot would stall); no reboot, no persist prop
  if grep -v '^[[:space:]]*#' "$OTAIMG/$BLOBS_SH" \
      | grep -qE '>[[:space:]]*"?[$A-Za-z_]*/dev/block|of=[^ ]*/dev/|avbtool|vbmeta|lpdump|sys\.powerctl|setprop persist\.|(^|[^a-z])reboot'; then
    die "$BLOBS_SH: block-device write / lpdump / reboot / persist prop (never allowed)"
  fi
  grep -qE '^timeout [1-5] dd if="\$dev" of=\$img bs=65536 count=64 ' "$OTAIMG/$BLOBS_SH" \
    || die "$BLOBS_SH: the partition read must be one bounded dd (timeout <= 5 s, 4 MiB)"
  # the untar is bounded too; before it: no absolute or '..' name, no link / device / fifo member (nothing
  # written outside the tmpfs); after it, before anything is hashed: only directories and regular files
  # <= 4 MiB (no link out of the tmpfs, no sparse giant to hash)
  grep -qE '^\( cd \$M/x && timeout [1-5] tar -xf \$img \) ' "$OTAIMG/$BLOBS_SH" \
    && grep -qE '^timeout [1-5] tar -tf \$img .*&& fail bad:path$' "$OTAIMG/$BLOBS_SH" \
    && grep -qF "odd=\$(timeout 3 tar -tvf \$img 2>/dev/null | grep '^[lhcbps]' | head -n 1)" "$OTAIMG/$BLOBS_SH" \
    && grep -qF 'odd=$(find $M/x ! -type d \( ! -type f -o -size +4194304c \)' "$OTAIMG/$BLOBS_SH" \
    || die "$BLOBS_SH: the untar must be bounded and followed by the name and entry checks"
  # its only mounts: its own tmpfs (required on the device: /mnt is noexec, and a bind keeps the flags of
  # its source mount), the binds of the checked copies, and the tmpfs read-only afterwards
  grep -qE '^\[ -n "\$R" \] \|\| mount -t tmpfs .*\|\| fail bad:tmpfs$' "$OTAIMG/$BLOBS_SH" \
    || die "$BLOBS_SH: its tmpfs must be required (|| fail bad:tmpfs)"
  um=$(grep -v '^[[:space:]]*#' "$OTAIMG/$BLOBS_SH" | grep -oE '(^|[^A-Za-z0-9_.-])u?mount +[^;&|)]*' \
    | sed -E -e 's/^[^a-z]//' -e 's/ *2>\/dev\/null.*//' -e 's/[[:space:]]+$//' | sort -u) || true
  [ "$um" = "$(printf '%s\n' 'mount --bind "$f" "$R$dst"' 'mount -t tmpfs -o mode=0750,uid=0,gid=0,size=8m,nosuid,nodev tmpfs $M' \
      'mount -o remount,ro $M' | sort -u)" ] \
    || die "$BLOBS_SH: unexpected mounts: $um"
  # the image's allow-list and the installer's are one file (the installer dir is not on the laptop; the
  # host test's OTAIMG is a copy with test hashes)
  if [ -f "$TOOLS/../installer/lib/$BLOBS_ALLOW" ] && [ "$OTAIMG" -ef "$TOOLS/ota/image" ]; then
    cmp -s "$OTAIMG/$BLOBS_ALLOW" "$TOOLS/../installer/lib/$BLOBS_ALLOW" \
      || die "installer/lib/$BLOBS_ALLOW differs from tools/ota/image/$BLOBS_ALLOW (must be identical)"
  fi
  # placeholder lines = the private image's pinned Codec2 libs (C2LIBS/C2SHA) at their image paths;
  # bind= lines = exactly the XGIMI_FILES the public image drops (their hashes are checked against the
  # base in prep)
  python3 - "$OTAIMG/$BLOBS_ALLOW" "${C2LIBS[*]}" "${C2SHA[*]}" "${XGIMI_FILES[*]}" <<'PY' || die "$BLOBS_ALLOW check failed"
import re, sys
allow, c2libs, c2sha, xg = sys.argv[1], sys.argv[2].split(), sys.argv[3].split(), sys.argv[4].split()
c2 = {('system/' + l if l.startswith('system_ext/') else l): h for l, h in zip(c2libs, c2sha)}
place, binds, sets = {}, {}, []
for line in open(allow):
    w = line.split()
    if not w or w[0].startswith('#'):
        continue
    if w[0].startswith('set='):
        assert re.fullmatch(r'set=[a-z0-9][a-z0-9.-]{0,40}', w[0]) and len(w) == 1, line
        sets.append(w[0]); continue
    assert re.fullmatch(r'[0-9a-f]{64}', w[0]) and len(w) >= 3, line
    h, rel, src, opts = w[0], w[1], w[2], w[3:]
    assert re.fullmatch(r'[A-Za-z0-9_.@/-]+', rel) and not rel.startswith('/') and '..' not in rel, line
    assert src.startswith('/') and '..' not in src, line
    o = dict(x.split('=', 1) for x in opts)
    assert len(o) == len(opts) and set(o) <= {'bind', 'from'}, line
    if 'from' in o:
        assert 'bind' in o and re.fullmatch(r'[0-9a-f]{64}:\d+(,\d+)?d(;\d+(,\d+)?d)*', o['from']), line
    if 'bind' in o:
        assert re.fullmatch(r'/(vendor/etc|apex/com\.android\.vndk\.v\d+/lib(64)?)/[A-Za-z0-9_.@-]+', o['bind']), line
        binds['system/' + rel] = h
    else:
        place['system/' + rel] = h
assert len(sets) == 1, 'exactly one set= line: %s' % sets
assert place == c2, 'placeholder lines %s != the private pins %s' % (place, c2)
assert sorted(binds) == sorted(xg), 'bind= lines %s != %s' % (sorted(binds), xg)
print('blobs_allow ok: %s, %d placeholders (= the private Codec2 pins), %d bind= files' % (sets[0], len(place), len(binds)))
PY
  # xgimi_compat_public.rc keeps no bind of a dropped file, and nothing else of ours uses one
  for f in "$OTAIMG/$COMPAT_PUB" "$V1"/*.rc "$V1"/*.sh "$OTAIMG"/*.rc "$UIRES/init.lineage.atv.scaling.rc"; do
    [ -f "$f" ] || continue
    if nocomment "$f" | grep -qE '/system/etc/xgimi/(libstagefright_foundation|audio_policy_configuration)'; then
      die "${f##*/} uses a file the public image does not carry"
    fi
  done
  gapps_preflight
}

# VARIANT=public, both editions (HANDOFF.md section 2b): the boot side of the Google services add-on. z9x_gapps.sh
# runs while init waits (the vendor PWM watchdog reboots when early-boot is late) and in front of PackageManager:
# it may only read its partition (read-only mount, bounded), overlay it read-only, set its three properties and
# write its one marker; it never unpacks anything at boot.
gapps_preflight() {
  local f um w b
  for f in "$GAPPS_SH" "$GAPPS_RC" "$GAPPS_ALLOW" "$GAPPS_XML"; do need "$OTAIMG/$f"; done
  [ "$(head -n1 "$OTAIMG/$GAPPS_SH")" = '#!/system/bin/sh' ] || die "$GAPPS_SH: first line"
  shlint "$OTAIMG/$GAPPS_SH"
  [ "$(nocomment "$OTAIMG/$GAPPS_RC")" = \
    "$(printf '%s\n' 'on post-fs-data' ' exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_gapps.sh')" ] \
    || die "$GAPPS_RC: expected only the post-fs-data exec of /system/etc/z9x/z9x_gapps.sh"
  if grep -v '^[[:space:]]*#' "$OTAIMG/$GAPPS_SH" \
      | grep -qE 'of=|setrw|mke2fs|make_f2fs|unzip|tar |lpdump|sys\.powerctl|setprop persist\.|(^|[^a-z])reboot|/data/|rw,|,rw|remount'; then
    die "$GAPPS_SH: block-device write / read-write mount / unpacking / lpdump / reboot / persist prop / /data (never allowed)"
  fi
  # its mounts: the partition read-only (system_file context, nosuid, nodev; bounded), the read-only lower-only
  # overlays, and their undo
  um=$(grep -v '^[[:space:]]*#' "$OTAIMG/$GAPPS_SH" | grep -oE '(^|[^A-Za-z0-9_.:-])u?mount +[^;&|)]*' \
    | sed -E -e 's/^[^a-z]//' -e 's/ *2>\/dev\/null.*//' -e 's/[[:space:]]+$//' | sort -u) || true
  [ "$um" = "$(printf '%s\n' 'mount -t ext4 -o ro,nosuid,nodev,context=$CTX "$dev" "$M"' \
      'mount -t overlay overlay -o "ro,lowerdir=$1:$R$2" "$R$2"' 'umount "$M"' 'umount "$t"' | sort -u)" ] \
    || die "$GAPPS_SH: unexpected mounts: $um"
  grep -qE '^timeout [1-5] mount -t ext4 -o ro,' "$OTAIMG/$GAPPS_SH" && grep -qF 'blockdev --setro "$dev"' "$OTAIMG/$GAPPS_SH" \
    && grep -qF 'CTX=u:object_r:system_file:s0' "$OTAIMG/$GAPPS_SH" \
    || die "$GAPPS_SH: the partition must be mounted read-only (system_file, timeout <= 5 s) after blockdev --setro"
  # one time budget for the whole boot run (the PWM watchdog margin is ~7 s): at most 3 s, checked before the
  # mount; every tree listing has a timeout
  b=$(sed -n 's/^BUDGET=\([0-9][0-9]*\)[[:space:]].*/\1/p' "$OTAIMG/$GAPPS_SH")
  [ -n "$b" ] && [ "$b" -le 300 ] && grep -qx 'budget mount' "$OTAIMG/$GAPPS_SH" \
    && [ -z "$(grep -v '^[[:space:]]*#' "$OTAIMG/$GAPPS_SH" | grep -E '\$\(find "\$M" ' )" ] \
    || die "$GAPPS_SH: needs its time budget (BUDGET <= 300 centiseconds, 'budget mount') and timeouts on its tree listings"
  [ "$(grep -v '^[[:space:]]*#' "$OTAIMG/$GAPPS_SH" | grep -oE 'setprop [^ ]+' | sort -u)" = \
    "$(printf '%s\n' 'setprop ro.com.google.gmsversion' 'setprop sys.z9x.gapps' 'setprop sys.z9x.gapps.zip' | sort -u)" ] \
    || die "$GAPPS_SH: setprop only of sys.z9x.gapps, sys.z9x.gapps.zip and ro.com.google.gmsversion"
  w=$(grep -v '^[[:space:]]*#' "$OTAIMG/$GAPPS_SH" | grep -oE '[^2]>[[:space:]]*[^ &|;)]+' | grep -v '/dev/null' \
    | sed -E 's/^.>[[:space:]]*//' | sort -u) || true
  [ "$w" = '"$OFF"' ] || die "$GAPPS_SH: it may write only its marker \$OFF (got: $w)"
  # the image's allow-list and the installer's are one file; its rules (tools/ota/gapps_allow.py)
  if [ -f "$TOOLS/../installer/lib/$GAPPS_ALLOW" ] && [ "$OTAIMG" -ef "$TOOLS/ota/image" ]; then
    cmp -s "$OTAIMG/$GAPPS_ALLOW" "$TOOLS/../installer/lib/$GAPPS_ALLOW" \
      || die "installer/lib/$GAPPS_ALLOW differs from tools/ota/image/$GAPPS_ALLOW (must be identical)"
  fi
  need "$TOOLS/ota/gapps_allow.py"
  python3 "$TOOLS/ota/gapps_allow.py" check "$OTAIMG/$GAPPS_ALLOW" || die "$GAPPS_ALLOW check failed"
}

# VARIANT=public, with the base: the bind= files of blobs_allow.txt are exactly the base's XGIMI_FILES (the
# owner's image carries these very bytes), and xgimi_compat_public.rc is the base's xgimi_compat.rc without
# their three binds (everything else it does stays)
public_base_check() {
  local f h want rx='/system/etc/xgimi/(audio_policy_configuration\.xml|libstagefright_foundation(64)?\.so) '
  for f in "${XGIMI_FILES[@]}"; do
    h=$(tarcat "$BASE" "$f" | sha256in)
    want=$(awk -v p="${f#system/}" '$1 !~ /^#/ && $2 == p {print $1}' "$OTAIMG/$BLOBS_ALLOW")
    [ -n "$want" ] && [ "$h" = "$want" ] || die "base $f (sha256 $h) is not the $BLOBS_ALLOW entry ('$want')"
  done
  tarcat "$BASE" system/etc/init/xgimi_compat.rc > "$WORK/base_xgimi_compat.rc"
  [ "$(nocomment "$WORK/base_xgimi_compat.rc" | grep -cE "$rx")" = 3 ] \
    || die "base xgimi_compat.rc: expected exactly the 3 binds of ${XGIMI_FILES[*]}"
  [ "$(nocomment "$WORK/base_xgimi_compat.rc" | grep -vE "$rx")" = "$(nocomment "$OTAIMG/$COMPAT_PUB")" ] \
    || die "$COMPAT_PUB is not the base's xgimi_compat.rc without the binds of ${XGIMI_FILES[*]}"
  echo "public: base ${XGIMI_FILES[*]} = the $BLOBS_ALLOW bind= entries; $COMPAT_PUB = base xgimi_compat.rc minus their binds"
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
tarcat() {  # tarcat TAR MEMBER -> its bytes on stdout (nothing when it is not a regular member)
  python3 - "$1" "$2" <<'PY'
import sys, tarfile
t = tarfile.open(sys.argv[1])
for m in t:
    if m.name.rstrip('/') == sys.argv[2]:
        if m.isreg():
            sys.stdout.buffer.write(t.extractfile(m).read())
        break
PY
}

# the members that differ between the variants (prep; 'members' runs them alone for the host test)
codec_members() {
  # v6.2b Codec2 store plugin + libcodec2_vndk rebuild (the owner's private image)
  mkdl system/system_ext/lib 755 "$LIBLABEL"
  if [ "$VARIANT" = public ]; then
    # public (HANDOFF.md section 2): 0-byte placeholders, same mode and label, bound over at boot by
    # z9x_blobs.sh with the user's own copies (the spec's content check reads /dev/null: 0 bytes)
    for l in "${C2LIBS[@]}"; do addl "$(c2dest "$l")" /dev/null 644 "$LIBLABEL"; done
  else
    for l in "${C2LIBS[@]}"; do addl "$(c2dest "$l")" "$V1/c2store/$l" 644 "$LIBLABEL"; done
  fi
  for l in "${VNDKLIBS[@]}"; do repl "$l" "$V1/c2vndk/$l" 644 "$LIBLABEL"; done
  if [ "$VARIANT" = public ]; then
    # the boot-time binder of the user's own files + its allow-list (modes of the gate's files), and the
    # base's xgimi_compat.rc without the binds of the XGIMI_FILES (removed with REMOVE)
    add system/etc/z9x/z9x_blobs.sh "$OTAIMG/$BLOBS_SH" 755
    add system/etc/init/z9x_blobs.rc "$OTAIMG/$BLOBS_RC" 644
    add system/etc/z9x/blobs_allow.txt "$OTAIMG/$BLOBS_ALLOW" 644
    repl system/etc/init/xgimi_compat.rc "$OTAIMG/$COMPAT_PUB" 644 u:object_r:system_file:s0
  fi
}

# VARIANT=public (HANDOFF.md section 2b): the boot side of the Google services add-on, in both editions (z9x_gapps.sh
# does nothing unless ro.z9x.gms=0 and the user made z9x_gapps<slot>); modes and label of the gate's files
gapps_members() {
  add system/etc/z9x/z9x_gapps.sh "$OTAIMG/$GAPPS_SH" 755
  add system/etc/init/z9x_gapps.rc "$OTAIMG/$GAPPS_RC" 644
  add system/etc/z9x/gapps_allow.txt "$OTAIMG/$GAPPS_ALLOW" 644
  mkd system/etc/z9x/gapps 755
  mkd system/etc/z9x/gapps/sysconfig 755
  add system/etc/z9x/gapps/sysconfig/z9x-gapps.xml "$OTAIMG/$GAPPS_XML" 644
}

# ======================================================================== stage: members (host test)
# tools/ota/test/public_members.sh: only codec_members (+ the XGIMI_FILES removal of a public image, + for
# GMS=0 the removal of every nogms_remove.txt path the test base has) applied to BASE (a small test tar)
# with patch_tar -> $OUTDIR/members_$NAME.tar + .spec, then prep's member diff
stage_members() {
  local r
  OUTDIR=$(cd "${OUTDIR:-.}" && pwd)
  BASE=${BASE:?BASE=<test base tar>}
  OUT=$OUTDIR/members_$NAME.tar
  ACT=(); S=$OUT.spec; : > "$S"
  suffix_check
  if [ "$VARIANT" = public ]; then public_preflight; public_base_check; mkd system/etc/z9x 755; fi
  codec_members
  if [ "$VARIANT" = public ]; then gapps_members; fi
  if [ "$VARIANT" = public ]; then for r in "${XGIMI_FILES[@]}"; do ACT+=(--remove "$r"); echo "R $r" >> "$S"; done; fi
  if [ "$GMS" = 0 ]; then
    nogms_preflight
    while IFS= read -r r; do ACT+=(--remove "$r"); echo "R $r" >> "$S"; done < <(python3 - "$BASE" "${REMOVE_GMS[@]}" <<'PY'
import sys, tarfile
names = {m.name.rstrip('/') for m in tarfile.open(sys.argv[1])}
for r in sys.argv[2:]:
    if r in names:
        print(r)
PY
)
  fi
  python3 "$PT" "$BASE" "$OUT" --mtime "$MTIME_EPOCH" "${ACT[@]}" > "$WORK/patch_tar.log" || { cat "$WORK/patch_tar.log"; die "patch_tar failed"; }
  verify_tar "$OUT" "$S" || die "member check failed"
  log "members done: $OUT ($VARIANT, gms=$GMS)"
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
      system/product/usr/keylayout/Generic.kl system/etc/z9x system/etc/init/z9x_ota.rc system/etc/init/z9x_rescue.rc \
      system/etc/permissions/privapp-permissions-z9xhome.xml system/etc/permissions/privapp-permissions-z9xsetup.xml \
      system/product/etc/default-permissions/z9x-projector.xml system/product/etc/default-permissions/z9x-airplay.xml \
      system/product/etc/default-permissions/z9x-home.xml system/product/etc/default-permissions/z9x-updater.xml \
      system/etc/xgimi/audio_policy_engine_stream_volumes.xml system/etc/init/z9x_features.rc \
      "${RCS[@]/#/system/etc/init/}" "${SCRIPTS[@]/#/system/etc/xgimi/}" system/etc/init/z9x_diag.rc \
      ${POWERRC[@]+"${POWERRC[@]/#/system/etc/init/}"} ${POWERSH[@]+"${POWERSH[@]/#/system/etc/xgimi/}"} \
      system/product/overlay/Z9xFrameworkKeysOverlay.apk system/product/overlay/Z9xLineagePlatformOverlay.apk \
      system/product/overlay/Z9xDeviceConfigOverlay.apk system/product/overlay/Z9xTvSettingsHdrOverlay.apk \
      system/etc/init/nativeui.rc system/bin/nativeui system/etc/z9x/z9x_uires.sh system/etc/init/z9x_blobs.rc \
      system/etc/init/z9x_gapps.rc \
      $(for l in "${C2LIBS[@]}"; do c2dest "$l"; done)) \
  NEED_DIRS=$(printf '%s\n' system/app system/priv-app system/etc/init system/etc/xgimi system/etc/sysconfig \
      system/etc/permissions system/product/overlay system/product/usr system/product/usr/keylayout system/product/etc \
      system/product/media system/system_ext/etc system/product/etc/default-permissions system/etc/security) \
  NEED_FILES=$(printf '%s\n' system/build.prop system/product/etc/build.prop system/system_ext/etc/build.prop \
      system/etc/init/xgimi_compat.rc system/etc/xgimi/audio_policy_configuration.xml system/etc/init/update_verifier.rc \
      system/product/app/webview/oat/arm64/webview.odex system/product/media/bootanimation.zip \
      system/fonts/Roboto-Regular.ttf system/etc/security/otacerts.zip system/framework/org.lineageos.platform-res.apk \
      system/system_ext/priv-app/SimpleDeviceConfig/SimpleDeviceConfig.apk system/product/priv-app/SetupWraithPrebuilt \
      system/product/priv-app/LineageCustomizer/LineageCustomizer.apk "$UIRES_RC") \
  VNDK="$(for i in "${!VNDKLIBS[@]}"; do echo "${VNDKLIBS[$i]}=${VNDKBASESHA[$i]}"; done)" \
  LIBLABEL=$LIBLABEL GENERIC_KL=$V1/keylayout/Generic.kl LINEAGE_DISPLAY_PROP_FILE=system/build.prop \
    python3 "$CHK" base "$BASE" "${REMOVE[@]}" || die "base check failed"
  # the LineageOS file z9x_uires replaces: still exactly its two 'on fs' setprops (a rebase that changes it
  # is noticed before the replacement hides it)
  tarcat "$BASE" "$UIRES_RC" > "$WORK/base_scaling.rc"
  [ "$(nocomment "$WORK/base_scaling.rc")" = \
    "$(printf '%s\n' 'on fs' ' setprop ro.config.size_override 1920,1080' ' setprop ro.config.density_override 320')" ] \
    || die "base $UIRES_RC is not LineageOS's 'on fs' 1920,1080 @ 320 file (z9x_uires replaces it)"
  echo "base $UIRES_RC: LineageOS's 1920,1080 @ 320 (replaced by z9x_uires)"
  if [ "$VARIANT" = public ]; then public_base_check; fi
  # GMS=0 drops MindTheGapps' system_ext privapp allow-list (it also lists SystemUI): only safe while the
  # base does not enforce privapp allow-lists (the device shows 'disable' too)
  if [ "$GMS" = 0 ]; then
    [ "$(tarprop "$BASE" system/system_ext/etc/build.prop ro.control_privapp_permissions)" = disable ] \
      || die "GMS=0: the base system_ext build.prop must set ro.control_privapp_permissions=disable (the Google allow-lists leave)"
    echo "nogms: base ro.control_privapp_permissions=disable (system_ext); ${#REMOVE_GMS[@]} Google paths leave"
  fi

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
  add system/etc/sysconfig/z9x.xml "$V1/z9x-sysconfig$EDITION_SFX.xml" 644
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
  # 1.0.1 boot rescue (same label system_file, modes as the gate)
  add system/etc/z9x/z9x_rescue.sh "$OTAIMG/$RESCUE_SH" 755
  add system/etc/init/z9x_rescue.rc "$OTAIMG/$RESCUE_RC" 644
  repl system/etc/init/update_verifier.rc "$OTAIMG/$OTA_UV" 644 u:object_r:system_file:s0
  # 1.0.1 UI resolution: the mode script (modes of the gate's files) + Lumen's replacement of LineageOS's
  # scaling rc (same path, label and mode as the base member)
  add system/etc/z9x/z9x_uires.sh "$UIRES/z9x_uires.sh" 755
  repl "$UIRES_RC" "$UIRES/init.lineage.atv.scaling.rc" 644 u:object_r:system_file:s0
  # Lumen boot animation (the -dark symlink keeps pointing at it)
  repl system/product/media/bootanimation.zip "$V1/bootanimation.zip" 644 u:object_r:system_file:s0
  # brand spec 18: the classic launcher's partner configuration without Play Movies (removed by the user
  # decision); only res/raw/configuration.xml differs from the base, classes.dex is identical (the base
  # odex/vdex stay valid); test platform key here, the sign stage re-signs it with the release key
  # (GMS=0: LineageCustomizer leaves with the classic launcher, nogms_remove.txt)
  if [ "$GMS" = 1 ]; then
    python3 "$LT/gen_customizer.py" check "$BASE" "$V1/LineageCustomizer.apk" || die "LineageCustomizer.apk check failed"
    repl system/product/priv-app/LineageCustomizer/LineageCustomizer.apk "$V1/LineageCustomizer.apk" 644 u:object_r:system_file:s0
  fi
  codec_members
  if [ "$VARIANT" = public ]; then gapps_members; fi

  # ---- props
  bid=$(tarprop "$BASE" system/build.prop ro.build.id); [ -n "$bid" ] || die "no ro.build.id in the base"
  # About shows the build id's date part (with the suffix, so two builds of one date differ there too)
  DISPLAY_ID="Lumen OS $VER ($bid, $BUILD_DATE$BUILD_ID_SUFFIX)"
  LINEAGE_DISPLAY="$VER (kmuradoff)"
  OTAPROPS=$(sed -e "s/^ro\\.z9x\\.build_id=lumen-${VER//./\\.}-<DATE>\$/ro.z9x.build_id=$BUILD_ID/" -e "s/<DATE>/$BUILD_DATE/" "$OTAIMG/ota_props.txt")
  grep -qx "ro.z9x.build_id=$BUILD_ID" <<<"$OTAPROPS" || die "ota_props.txt: build id line not substituted ($BUILD_ID)"
  # public / no-Google: its own update manifest (docs/ota.md "Variants": private devices keep reading
  # update-stable.json), ro.z9x.variant=public and ro.z9x.gms=0; the private image gets none (unchanged)
  PUBPROPS=
  if [ "$MANIFEST_URL" != "$MANIFEST_BASE/update-stable.json" ]; then
    OTAPROPS=$(sed -e "s#^ro\\.z9x\\.ota\\.manifest_url=.*/update-stable\\.json\$#ro.z9x.ota.manifest_url=$MANIFEST_URL#" <<<"$OTAPROPS")
    grep -qx "ro.z9x.ota.manifest_url=$MANIFEST_URL" <<<"$OTAPROPS" || die "ota_props.txt: manifest URL not substituted"
  fi
  if [ "$VARIANT" = public ]; then
    PUBPROPS=$'\n# ---- public image (tools/lumen_v1.sh VARIANT=public, tools/ota/image/HANDOFF.md section 2)\nro.z9x.variant=public'
  fi
  if [ "$GMS" = 0 ]; then
    PUBPROPS+=$'\n# ---- no-Google edition (tools/lumen_v1.sh GMS=0)\nro.z9x.gms=0'
  fi
  log "build id $BUILD_ID ($VARIANT, gms=$GMS)"
  ACT+=(--sub system/build.prop '^# end of file$' "$(cat "$V1/build_prop_v1.txt")
# end of file"
        --sub system/product/etc/build.prop '^# end of file$' "$(cat "$V1/product_prop_v1.txt")
$(cat "$UIRES/product_prop.txt")
# ---- OTA (tools/ota/image/ota_props.txt, L-OTA)
$OTAPROPS$PUBPROPS
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
    "T system/build.prop ro.z9x.version=$VER" 'T system/build.prop ro.z9x.clickcurve=1' \
    'X system/build.prop pm.boot.disable_package_cache=true' 'T system/build.prop persist.z9x.firstrun=1' \
    "T system/build.prop ro.build.display.id=$DISPLAY_ID" "T system/build.prop ro.lineage.display.version=$LINEAGE_DISPLAY" \
    'T system/build.prop ro.build.type=userdebug' 'T system/build.prop ro.build.tags=test-keys' \
    'T system/product/etc/build.prop ro.product.locale=en-US' 'T system/product/etc/build.prop persist.sys.timezone=Europe/Moscow' \
    'T system/product/etc/build.prop bluetooth.device.class_of_device=44,4,60' \
    'T system/product/etc/build.prop bluetooth.device.default_name=XGIMI Z9X' \
    "T system/product/etc/build.prop ro.lumen.version=$VER" \
    'T system/product/etc/build.prop ro.lmk.swap_free_low_percentage=10' \
    "T system/product/etc/build.prop ro.product.ab_ota_partitions=$AB_OTA" \
    'T system/product/etc/build.prop ro.z9x.keys=release' \
    "T system/product/etc/build.prop ro.z9x.build_id=$BUILD_ID" \
    "T system/product/etc/build.prop ro.z9x.version_code=$OS_VERSION_CODE" \
    'T system/product/etc/build.prop ro.z9x.rescue=1' 'T system/product/etc/build.prop ro.z9x.report.url=' \
    'T system/product/etc/build.prop ro.surface_flinger.max_graphics_width=3840' \
    'T system/product/etc/build.prop ro.surface_flinger.max_graphics_height=2160' \
    "T system/product/etc/build.prop $(grep -xE 'ro\.z9x\.uires\.allow=[01]' "$UIRES/product_prop.txt")" \
    "X $UIRES_RC     setprop ro.config.size_override 1920,1080" "X $UIRES_RC     setprop ro.config.density_override 320" \
    'T system/product/etc/build.prop ro.product.product.model=XGIMI Z9X' \
    'X system/system_ext/etc/build.prop ro.setupwizard.mode=DISABLED' \
    'T system/system_ext/etc/build.prop ro.adb.secure=1' 'X system/system_ext/etc/build.prop ro.adb.secure=0' \
    'T system/system_ext/etc/build.prop persist.sys.usb.config=none' 'X system/system_ext/etc/build.prop persist.sys.usb.config=adb' \
    'T system/product/etc/build.prop ro.adb.secure=1' 'T system/product/etc/build.prop persist.sys.usb.config=none' \
    "K system/build.prop $COMPAT_SYS" "K system/product/etc/build.prop $COMPAT_PRODUCT" >> "$S"
  # exactly this edition's manifest URL, none of the other three
  for u in stable public stable-nogms public-nogms; do
    if [ "$u" = "$CHANNEL" ]; then k=T; else k=X; fi
    echo "$k system/product/etc/build.prop ro.z9x.ota.manifest_url=$MANIFEST_BASE/update-$u.json" >> "$S"
  done
  if [ "$VARIANT" = public ]; then echo 'T system/product/etc/build.prop ro.z9x.variant=public' >> "$S"; fi
  if [ "$GMS" = 0 ]; then echo 'T system/product/etc/build.prop ro.z9x.gms=0' >> "$S"; fi
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
    echo "name=$NAME"; echo "variant=$VARIANT"; echo "gms=$GMS"; echo "build_date=$BUILD_DATE"; echo "build_id=$BUILD_ID"; echo "mtime_epoch=$MTIME_EPOCH"; echo "dexpreopt=$DEXPREOPT"
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
  for c in platform shared media networkstack sdk_sandbox bluetooth nfc releasekey ota ota_next; do
    [ "$(openssl x509 -in "$KEYS/$c.x509.pem" -outform DER | sha256in)" = "$(openssl x509 -in "$RELCERTS/$c.x509.pem" -outform DER | sha256in)" ] \
      || die "$KEYS/$c.x509.pem differs from the published tools/sign/release_certs copy"
  done
  IN=${UNSIGNED:-$OUTDIR/system_tv_${NAME}_unsigned.tar}
  [ -f "$IN" ] && [ -f "$IN.info" ] && [ -f "$IN.spec" ] || die "no $IN (+ .info/.spec) from the prep stage"
  if grep -qx 'partial=1' "$IN.info" && [ "${ALLOW_PARTIAL:-0}" != 1 ]; then die "$IN is a partial dry-run build"; fi
  OUT=$OUTDIR/system_tv_${NAME}_signed.tar
  REP=$OUTDIR/sign_report_$NAME
  APEXOUT=$OUTDIR/apex_$NAME
  PARTS=("$OUT" "$OUT.part" "$OUT.info" "$OUT.spec")
  rm -f "${PARTS[@]}"; mkdir -p "$REP"
  builder_env
  log "[sign] 1/4 apex_sign.py: every APEX re-signed (APEX keys from $KEYS/apex; laptop $BUILDER key-free)"
  python3 "$TOOLS/sign/apex_sign.py" run "$IN" "$APEXOUT" --keys "$KEYS" \
    --builder "$BUILDER" --ssh-key "$BUILDER_KEY" \
    --remote-tar "${REMOTE_UNSIGNED:-z9x/out/$(basename "$IN")}" || die "apex_sign.py failed"
  log "[sign] 2/4 sign_tar.py (release keys from $KEYS, APEXes from $APEXOUT)"
  python3 "$TOOLS/sign/sign_tar.py" "$IN" "$OUT" --apex-dir "$APEXOUT" --keys "$KEYS" --report-dir "$REP" || die "sign_tar.py failed"
  log "[sign] 3/4 sign_tar.py --verify"
  python3 "$TOOLS/sign/sign_tar.py" --verify "$OUT" --keys "$KEYS" --report-dir "$REP" || die "sign_tar.py --verify failed"
  log "[sign] 4/4 unsigned -> signed diff"
  SIGN_APEX_MANIFEST=$APEXOUT/apex_signed.json python3 "$CHK" signed "$IN" "$OUT" "$TESTCERTS" "$RELCERTS" || die "signed tar check failed"
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
  # the build id prep wrote (BUILD_ID_SUFFIX) is the one in the tar (.info of builds before it has none)
  bwant=$(sed -n 's/^build_id=//p' "$IN.info")
  if [ -n "$bwant" ]; then
    bgot=$(tarprop "$IN" system/product/etc/build.prop ro.z9x.build_id)
    [ "$bgot" = "$bwant" ] || die "ro.z9x.build_id in $IN is '$bgot', prep built '$bwant'"
    echo "build id $bgot"
  fi
  # the variant prep built (an .info from before VARIANT has none: private)
  vgot=$(sed -n 's/^variant=//p' "$IN.info")
  [ "${vgot:-private}" = "$VARIANT" ] || die "$IN was built with VARIANT=${vgot:-private}, this run is VARIANT=$VARIANT"
  # the edition prep built (an .info from before GMS has none: 1)
  ggot=$(sed -n 's/^gms=//p' "$IN.info")
  [ "${ggot:-1}" = "$GMS" ] || die "$IN was built with GMS=${ggot:-1}, this run is GMS=$GMS"
  ALLOW_MISSING_APKS=${ALLOW_PARTIAL:-0} python3 "$CHK" packages "$IN" || die "package check failed"
  log "[image] 2/5 mkfs.erofs + fsck.erofs"
  # -T: one fixed timestamp for every inode, so all inodes stay compact (32 bytes) like the base image.
  # Never --mkfs-time (per-inode mtimes = 64-byte inodes) and never erofs-utils 1.9: see MKFS above.
  if ! "$MKFS" -b4096 -zlz4hc -E noinline_data -T1230768000 --tar=f "$IMG.part" "$IN" > "$WORK/mkfs.log" 2>&1; then
    cat "$WORK/mkfs.log"; die "mkfs.erofs failed"
  fi
  grep -E "total inodes|Build completed" "$WORK/mkfs.log" || true
  "$FSCK" "$IMG.part" >/dev/null || die "fsck.erofs failed"
  # the Z9X kernel's own rule (fsck.erofs does not check it): no inline data across a block boundary
  python3 "$TOOLS/erofs_kcheck.py" "$IMG.part" || die "erofs_kcheck: the Z9X kernel would refuse this image"
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
      mkdir -m 700 "$WORK/pubcerts" "$WORK/pubcerts/apex" && cp "$RELCERTS"/*.x509.pem "$WORK/pubcerts/" \
        && cp "$RELCERTS"/apex/*.x509.pem "$RELCERTS"/apex/*.avbpubkey "$RELCERTS"/apex/*.pubkey.pem "$WORK/pubcerts/apex/" \
        && export KEYS_DIR=$WORK/pubcerts
    fi
    python3 "$TOOLS/sign/check_image.py" --tar "$IN" --img "$IMG.part" --variant "$VARIANT" --gms "$GMS" --base "$BASE" \
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
  builder_env
  BKEY=$BUILDER_KEY
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
  log "[remote] 2/6 inputs -> laptop (no keys)"
  "${SSH[@]}" 'mkdir -p ~/z9x/tools/lumen ~/z9x/tools/ota/image ~/z9x/tools/sign/testcerts ~/z9x/tools/sign/release_certs ~/z9x/overlay/v1 ~/z9x/overlay/apps_v1 ~/z9x/overlay/v65'
  "${RS[@]}" -a "$TOOLS/lumen_v1.sh" "$TOOLS/patch_tar.py" "$TOOLS/erofs_kcheck.py" "$BUILDER:z9x/tools/"
  "${RS[@]}" -a --delete "$LT/" "$BUILDER:z9x/tools/lumen/"
  "${RS[@]}" -a --delete "$OTAIMG/" "$BUILDER:z9x/tools/ota/image/"
  "${RS[@]}" -a "$TOOLS/ota/gapps_allow.py" "$BUILDER:z9x/tools/ota/"
  "${RS[@]}" -a --delete --include='*.x509.pem' --include='FINGERPRINTS.txt' --exclude='*' "$TESTCERTS/" "$BUILDER:z9x/tools/sign/testcerts/"
  "${RS[@]}" -a --delete --include='apex/' --include='*.x509.pem' --include='*.avbpubkey' --include='*.pubkey.pem' \
    --include='FINGERPRINTS.txt' --exclude='*' "$RELCERTS/" "$BUILDER:z9x/tools/sign/release_certs/"
  "${SSH[@]}" 'mkdir -p ~/z9x/tools/sign/third_party'
  "${RS[@]}" -a "$TOOLS/sign/check_image.py" "$TOOLS/sign/sign_tar.py" "$TOOLS/sign/keymap.json" \
    "$TOOLS/sign/apexlib.py" "$TOOLS/sign/apex_laptop.py" "$BUILDER:z9x/tools/sign/"
  "${RS[@]}" -a "$TOOLS/sign/third_party/avbtool.py" "$TOOLS/sign/third_party/README.md" "$BUILDER:z9x/tools/sign/third_party/"
  "${RS[@]}" -a "$GSI/overlay/v65/z9x_setup.rc" "$BUILDER:z9x/overlay/v65/"
  # a public build never reads overlay/v1/c2store (the MediaTek Codec2 libs): not sent (an exclude also
  # keeps --delete off the laptop's copy, which private builds use)
  if [ "$VARIANT" = public ]; then
    "${RS[@]}" -a --delete --exclude=c2store/ "$V1/" "$BUILDER:z9x/overlay/v1/"
  else
    "${RS[@]}" -a --delete "$V1/" "$BUILDER:z9x/overlay/v1/"
  fi
  "${RS[@]}" -a --delete "$APPS/" "$BUILDER:z9x/overlay/apps_v1/"
  "${SSH[@]}" 'find ~/z9x/tools/sign -name "*.pk8" -o -name "*.key" | grep -q . || grep -rlE -- "-----BEGIN [A-Z ]*PRIVATE KEY-----" ~/z9x/tools/sign >/dev/null 2>&1' \
    && die "a private key reached the laptop" || true
  log "[remote] 3/6 prep on the laptop"
  thermal
  "${SSH[@]}" "cd ~/z9x/out && VARIANT=$VARIANT GMS=$GMS BUILD_DATE=$BUILD_DATE BUILD_ID_SUFFIX=$BUILD_ID_SUFFIX MTIME_EPOCH=$MTIME_EPOCH INCREMENTAL_SUFFIX=$INCREMENTAL_SUFFIX nice -n 10 ionice -c3 bash ../tools/lumen_v1.sh prep"
  grep -qx "build_id=$BUILD_ID" <("${SSH[@]}" "cat ~/z9x/out/system_tv_${NAME}_unsigned.tar.info") \
    || die "the laptop's unsigned tar is not build $BUILD_ID"
  log "[remote] 4/6 unsigned tar -> Mac, sign"
  "${RS[@]}" -a --partial "$BUILDER:z9x/out/system_tv_${NAME}_unsigned.tar" "$BUILDER:z9x/out/system_tv_${NAME}_unsigned.tar.spec" \
    "$BUILDER:z9x/out/system_tv_${NAME}_unsigned.tar.info" "$LOUT/"
  OUTDIR=$LOUT stage_sign
  log "[remote] 5/6 signed tar -> laptop, image"
  "${RS[@]}" -a --partial "$LOUT/system_tv_${NAME}_signed.tar" "$LOUT/system_tv_${NAME}_signed.tar.spec" \
    "$LOUT/system_tv_${NAME}_signed.tar.info" "$BUILDER:z9x/out/"
  thermal
  "${SSH[@]}" "cd ~/z9x/out && VARIANT=$VARIANT GMS=$GMS nice -n 10 ionice -c3 bash ../tools/lumen_v1.sh image"
  log "[remote] 6/6 image -> gsi/build/$NAME.img (delta rsync over the previous image, or a copy of an older one)"
  DST=$GSI/build/$NAME.img
  # (a public image is never seeded from a private one: a broken copy must not mix their bytes)
  if [ ! -f "$DST" ] && [ "$VARIANT" = private ] && [ -f "$GSI/build/system_tv_v652.img" ]; then
    cp "$GSI/build/system_tv_v652.img" "$DST"
  fi
  "${RS[@]}" --inplace --no-whole-file "$BUILDER:z9x/out/system_tv_$NAME.img" "$DST"
  want=$("${SSH[@]}" "cut -d' ' -f1 ~/z9x/out/system_tv_$NAME.img.sha256")
  got=$(sha256 "$DST")
  [ -n "$want" ] && [ "$got" = "$want" ] || die "image checksum mismatch after the copy ($got != $want)"
  printf '%s  %s\n' "$got" "$NAME.img" > "$GSI/build/SHA256SUMS_$NAME.txt"
  # owner decision 2026-10-09: only the no-Google edition is ever published (no release files otherwise)
  if [ "$VARIANT" = public ] && [ "$GMS" = 0 ]; then release_files "$DST" "$got"
  elif [ "$VARIANT" = public ]; then log "public Google edition (GMS=1): never published, no release files"; fi
  log "remote done: $DST sha256 $got (flash = the user's own step)"
}

# VARIANT=public GMS=0 (the only edition ever published), Mac: the files a release carries (docs/release.md)
# in $RELDIR (default $LOUT/release): lumen-os-$VER-nogms-system.img (an APFS clone of the pulled image),
# checked FIRST by the release gate's own file-by-file scan (tools/ota/check_release_assets.py: MediaTek /
# XGIMI files, Google members and packages, personal data), only then SHA256SUMS and SHA256SUMS.sig (OTA key
# from KEYS_DIR, never leaves the Mac; the installer checks it with installer/certs/ota.x509.pem), which the
# gate checks too. On any failure all three are deleted again (PARTS): no signed SHA256SUMS ever vouches for
# an image the gate refused or never scanned. Another image in that folder stops it (the installer would
# have to choose, and a release folder holds only what may be published). Nothing is published.
release_files() {
  local img=$1 sha=$2 rel=${RELDIR:-$LOUT/release} name=lumen-os-$VER$EDITION_SFX-system.img keys=${KEYS_DIR:-$HOME/.lumen-keys}
  local cert=$GSI/installer/certs/ota.x509.pem f
  [ "$GMS" = 0 ] || die "internal: release files only for the no-Google edition"
  [ -s "$keys/ota.pk8" ] || die "no $keys/ota.pk8 (SHA256SUMS.sig)"
  cmp -s "$cert" "$RELCERTS/ota.x509.pem" || die "installer/certs/ota.x509.pem differs from tools/sign/release_certs/ota.x509.pem"
  command -v dump.erofs >/dev/null 2>&1 \
    || die "no dump.erofs on this Mac (brew install erofs-utils): the release gate cannot scan the image"
  mkdir -p "$rel"
  for f in "$rel"/*.img; do
    [ -e "$f" ] || continue
    [ "${f##*/}" = "$name" ] || die "$rel holds another image (${f##*/}): a release folder carries only $name (use another RELDIR)"
  done
  rm -f "$rel/$name" "$rel/SHA256SUMS" "$rel/SHA256SUMS.sig"
  PARTS=("$rel/$name" "$rel/SHA256SUMS" "$rel/SHA256SUMS.sig")
  cp -c "$img" "$rel/$name" 2>/dev/null || cp "$img" "$rel/$name"
  [ "$(sha256 "$rel/$name")" = "$sha" ] || die "$rel/$name: copy differs from the pulled image"
  python3 "$TOOLS/ota/check_release_assets.py" "$rel/$name" \
    || die "tools/ota/check_release_assets.py refused $name (nothing signed)"
  printf '%s  %s\n' "$sha" "$name" > "$rel/SHA256SUMS"
  openssl dgst -sha256 -keyform DER -sign "$keys/ota.pk8" -out "$rel/SHA256SUMS.sig" "$rel/SHA256SUMS" \
    || die "signing SHA256SUMS failed"
  openssl x509 -in "$cert" -pubkey -noout > "$WORK/ota_pub.pem"
  openssl dgst -sha256 -verify "$WORK/ota_pub.pem" -signature "$rel/SHA256SUMS.sig" "$rel/SHA256SUMS" >/dev/null \
    || die "SHA256SUMS.sig does not verify with installer/certs/ota.x509.pem"
  python3 "$TOOLS/ota/check_release_assets.py" "$rel/SHA256SUMS" "$rel/SHA256SUMS.sig" \
    || die "tools/ota/check_release_assets.py refused SHA256SUMS / SHA256SUMS.sig"
  PARTS=()
  ls -la "$rel"
  log "release files: $rel ($name, SHA256SUMS, SHA256SUMS.sig; installer: --image $rel/$name)"
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
  members) stage_members ;;
  *) die "target: lint | prep | sign | image | remote" ;;
esac
