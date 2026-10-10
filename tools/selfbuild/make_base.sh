#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# make_base.sh: the Lumen OS base system tar from your own LineageOS 21 TV GSI build.
#
#   bash tools/selfbuild/make_base.sh SYSTEM_IMG GAPPS_ZIP BLOBS_TAR OUT_TAR
#
#   SYSTEM_IMG  out/target/product/generic_arm64/system.img of `breakfast gsi_tv_arm64; m systemimage`
#               (docs/selfbuild: pinned source lineage/manifests/lumen-lineage-21.xml, WITH_DEXPREOPT flags
#               of lineage/z9x_build_loop.sh)
#   GAPPS_ZIP   MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip, the zip= line of
#               tools/ota/image/gapps_allow.txt (sha256 checked). The no-Google image removes these apps
#               again (tools/lumen/nogms_remove.txt); they are here only so the base has the same layout
#               as the one Lumen OS releases are built from.
#   BLOBS_TAR   the codec and audio files of YOUR projector: backup/blobs-<serial>.img, written by the
#               installer's `blobs` step (`installer/lumen-install.sh blobs`, on stock XGIMI firmware or on
#               Lumen OS). Every file is checked against tools/ota/image/blobs_allow.txt.
#   OUT_TAR     the base tar for tools/build_system.sh (BASE=OUT_TAR)
#
# These are the steps the Lumen OS base (system_tv_v4.tar, 2026-10-03) was made with, in order:
#   1. tools/ext4_to_tar.py: the GSI image as a tar with SELinux labels
#   2. tools/compose_tv.py: + MindTheGapps ATV, the XGIMI compatibility rc, the fixed audio policy,
#      key layouts (the published overlay/v1 copies of the three remotes; build_system.sh replaces all
#      key layouts with those anyway)
#   3. v3: no Lineage post-fs-data UI scaling (Lumen's own rc does it), build characteristics 'tv'
#   4. v4: no TvProvision (second setup wizard), no Lineage Updater (+ its RRO), model "XGIMI Z9X",
#      no 32-bit ABI warning, MediaTek's VNDK libstagefright_foundation bound for the audio HAL
# Needs: python3, debugfs (e2fsprogs), unzip, sha256sum or shasum. Writes only OUT_TAR (+ a work dir
# next to it, removed at the end).
set -euo pipefail
die() { echo "make_base: $*" >&2; exit 1; }
log() { echo "make_base: $*"; }
[ $# -eq 4 ] || die "usage: make_base.sh SYSTEM_IMG GAPPS_ZIP BLOBS_TAR OUT_TAR"
IMG=$1 ZIP=$2 BLOBS=$3 OUT=$4
H=$(cd "$(dirname "$0")/.." && pwd)            # tools/
REPO=$(cd "$H/.." && pwd)
for f in "$IMG" "$ZIP" "$BLOBS"; do [ -f "$f" ] || die "no $f"; done
[ ! -e "$OUT" ] || die "$OUT exists (remove it first)"
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
# overlay/v1 in the working tree, system/ in the published repository (same files)
V1=$REPO/overlay/v1; [ -d "$V1/keylayout" ] || V1=$REPO/system
KLDIR=$V1/keylayout; [ -d "$KLDIR" ] || KLDIR=$REPO/system/usr/keylayout
[ -d "$KLDIR" ] || die "no key layouts (overlay/v1/keylayout or system/usr/keylayout)"
GALLOW=$H/ota/image/gapps_allow.txt BALLOW=$H/ota/image/blobs_allow.txt
[ -f "$GALLOW" ] && [ -f "$BALLOW" ] || die "missing $GALLOW or $BALLOW"

W=$(mktemp -d "$(dirname "$OUT")/make_base.XXXXXX")
trap 'rm -rf "$W"' EXIT

log "1/6 MindTheGapps zip"
zs=$(sha256 "$ZIP")
grep -q "^zip=$zs " "$GALLOW" || die "$ZIP (sha256 $zs) is not a zip= line of $GALLOW"
mkdir -p "$W/gapps"
unzip -q "$ZIP" 'system/*' -d "$W/gapps"
rm -rf "$W/gapps/system/addon.d"

log "2/6 your projector's files ($BLOBS)"
mkdir -p "$W/blobs"
tar -xf "$BLOBS" -C "$W/blobs"
pick() {  # tar path in blobs_allow.txt -> checked file
  local want
  want=$(awk -v p="$1" '$1 !~ /^#/ && $2 == p {print $1}' "$BALLOW")
  [ -n "$want" ] || die "$1 is not in $BALLOW"
  [ -f "$W/blobs/$1" ] || die "$BLOBS has no $1 (made by an older installer?)"
  [ "$(sha256 "$W/blobs/$1")" = "$want" ] || die "$1 in $BLOBS is not the $BALLOW file"
  echo "$W/blobs/$1"
}
APC=$(pick etc/xgimi/audio_policy_configuration.xml)
SF32=$(pick etc/xgimi/libstagefright_foundation.so)
SF64=$(pick etc/xgimi/libstagefright_foundation64.so)

log "3/6 ext4 -> tar"
python3 "$H/ext4_to_tar.py" "$IMG" "$W/base.tar" "$W/manifest.tsv"

log "4/6 compose: + MindTheGapps, XGIMI compatibility, audio policy, key layouts"
mkdir -p "$W/overlay/keylayout"
cp "$APC" "$W/overlay/audio_policy_configuration.xml"
for k in Vendor_000d_Product_3841 Vendor_3697_Product_0001 Vendor_3697_Product_0002; do
  cp "$KLDIR/$k.kl" "$W/overlay/keylayout/"
done
python3 "$H/compose_tv.py" "$W/base.tar" "$W/tv.tar" "$W/gapps/system" "$W/overlay"
rm -f "$W/base.tar"

log "5/6 v3: UI scaling rc, build characteristics"
python3 "$H/patch_tar.py" "$W/tv.tar" "$W/v3.tar" \
  --sub system/product/etc/init/init.lineage.atv.scaling.rc "^on post-fs-data\n(?:[ \t]+.*\n?)*" "" \
  --sub system/product/etc/build.prop "^ro\.build\.characteristics=emulator$" "ro.build.characteristics=tv"
rm -f "$W/tv.tar"

log "6/6 v4: setup / updater cleanup, model, audio HAL library"
python3 "$H/patch_tar.py" "$W/v3.tar" "$OUT.part" \
  --remove system/product/priv-app/TvProvision \
  --remove system/system_ext/priv-app/Updater --remove system/product/overlay/Updater__lineage_gsi_tv_arm64__auto_generated_rro_product.apk \
  --sub system/build.prop "^# end of file$" "# Z9X: SetupWraith is 32-bit only; do not show the deprecated-ABI warning.\ndebug.wm.disable_deprecated_abi_dialog=true\n# end of file" \
  --sub system/build.prop "^ro\.product\.system\.model=atv_generic$" "ro.product.system.model=XGIMI Z9X" \
  --sub system/product/etc/build.prop "^ro\.product\.product\.model=AOSP TV on ARM64$" "ro.product.product.model=XGIMI Z9X" \
  --sub system/system_ext/etc/build.prop "^ro\.product\.system_ext\.model=AOSP TV on ARM64$" "ro.product.system_ext.model=XGIMI Z9X" \
  --sub system/etc/init/xgimi_compat.rc "^(    mount none /system/etc/xgimi/audio_policy_configuration\.xml /vendor/etc/audio_policy_configuration\.xml bind)$" "\1\n    # 3) The vendor audio HAL (audio.primary.mt5877.so) needs android::MEDIA_MIMETYPE_AUDIO_AV3A,\n    #    exported only by MediaTek's build of the VNDK libstagefright_foundation. Without it the\n    #    primary module does not load and there is no sound at all.\n    mount none /system/etc/xgimi/libstagefright_foundation.so /apex/com.android.vndk.v34/lib/libstagefright_foundation.so bind\n    mount none /system/etc/xgimi/libstagefright_foundation64.so /apex/com.android.vndk.v34/lib64/libstagefright_foundation.so bind" \
  --add system/etc/xgimi/libstagefright_foundation.so "$SF32" 644 \
  --add system/etc/xgimi/libstagefright_foundation64.so "$SF64" 644
mv "$OUT.part" "$OUT"
log "done: $OUT ($(sha256 "$OUT"))"
