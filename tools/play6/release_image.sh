#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# release_image.sh: turn a tested Play 6 image (tools/play6/build_test.sh tN, no Google) into the public release
# image, the way tools/lumen_v1.sh finishes a Z9X release: only build.prop lines change, nothing else in the image.
#
#   tools/play6/release_image.sh IN.img VERSION OUT.img
#     IN.img   play6/tN/system_play6_tN.img (the build the owner tested on the device)
#     VERSION  x.y.z, the version the installer checks (installer/release.conf VERSION = ro.lumen.version)
#     OUT.img  e.g. dist/lumen-os-VERSION-play6-nogms-system.img
#
# Changes (Mac, debugfs of Homebrew e2fsprogs; the vendor fstab mounts system without verity, so the stale AVB
# footer is never read):
#   system_ext/etc/build.prop  ro.adb.secure=0 -> 1, persist.sys.usb.config=adb -> none (the GSI default lines)
#   product/etc/build.prop     drops the test lines ro.adb.secure=0 and service.adb.tcp.port=5555 (adb on Wi-Fi
#                              without authorisation), adds ro.adb.secure=1 and persist.sys.usb.config=none, sets
#                              ro.lumen.version=VERSION, ro.lumen.version_code=major*10000+minor*100+patch and
#                              ro.lumen.build_id=lumen-play6-VERSION-<UTC date of IN's build id>
# Each file keeps mode 0644, owner root and the label system_file. Checks afterwards: the lines above are there,
# the test lines are gone, no Google file, e2fsck clean. Writes OUT.img and OUT.img.sha256.
set -euo pipefail
die() { echo "release_image: ERROR: $*" >&2; exit 1; }
IN=${1:?usage: release_image.sh IN.img VERSION OUT.img}; VER=${2:?VERSION}; OUT=${3:?OUT.img}
E2=${E2FS:-/opt/homebrew/opt/e2fsprogs/sbin}
D=$E2/debugfs
[ -x "$D" ] || die "needs e2fsprogs (brew install e2fsprogs)"
[ -s "$IN" ] || die "no $IN"
echo "$VER" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "VERSION must be x.y.z, not $VER"
IFS=. read -r MA MI PA <<< "$VER"
VCODE=$((MA * 10000 + MI * 100 + PA))
T=$(mktemp -d "${TMPDIR:-/tmp}/p6rel.XXXXXX")
trap 'rm -rf "$T"' EXIT

PROD=/system/product/etc/build.prop
EXT=/system/system_ext/etc/build.prop
"$D" -R "cat $PROD" "$IN" > "$T/product.prop" 2>/dev/null
"$D" -R "cat $EXT" "$IN" > "$T/ext.prop" 2>/dev/null
[ -s "$T/product.prop" ] && [ -s "$T/ext.prop" ] || die "cannot read the build.prop files of $IN"
grep -qx 'ro.lumen.device=play6' "$T/product.prop" || die "$IN is not a Play 6 image"
grep -q '^ro.lumen.gms=' "$T/product.prop" && die "$IN carries a Google mark"
OLD_ID=$(sed -n 's/^ro\.lumen\.build_id=//p' "$T/product.prop")
STAMP=$(printf '%s' "$OLD_ID" | grep -oE '[0-9]{12}$' || true)
[ -n "$STAMP" ] || die "no build id with a date in $IN (ro.lumen.build_id=$OLD_ID)"
BUILD_ID=lumen-play6-$VER-${STAMP:0:8}

# system_ext: the GSI's own adb lines
sed -e 's/^ro\.adb\.secure=0$/ro.adb.secure=1/' -e 's/^persist\.sys\.usb\.config=adb$/persist.sys.usb.config=none/' \
    -e 's/^# GSI always disables adb authentication$/# Lumen OS release: adb needs authorisation, USB debugging off/' \
    "$T/ext.prop" > "$T/ext.new"
# product: drop the test-build adb lines and their comment, set the version lines, add the release adb lines
grep -v -E '^(ro\.adb\.secure=0|service\.adb\.tcp\.port=5555|# First-boot diagnostics: .*|ro\.lumen\.version=.*|ro\.lumen\.version_code=.*|ro\.lumen\.build_id=.*)$' \
    "$T/product.prop" > "$T/product.new"
{ echo "ro.adb.secure=1"; echo "persist.sys.usb.config=none"
  echo "ro.lumen.version=$VER"; echo "ro.lumen.version_code=$VCODE"; echo "ro.lumen.build_id=$BUILD_ID"; } >> "$T/product.new"

mkdir -p "$(dirname "$OUT")"
cp "$IN" "$OUT.part"
put() {  # put LOCAL IMAGE_PATH
  local dir=${2%/*} name=${2##*/}
  printf 'u:object_r:system_file:s0\0' > "$T/label"
  "$D" -w -f - "$OUT.part" >/dev/null 2>"$T/dbg.err" <<EOF
cd $dir
rm $name
write $1 $name
set_inode_field $2 mode 0100644
set_inode_field $2 uid 0
set_inode_field $2 gid 0
ea_set -f $T/label $2 security.selinux
EOF
  grep -v '^debugfs ' "$T/dbg.err" | grep . && die "debugfs on $2: see above"
  return 0
}
put "$T/ext.new" "$EXT"
put "$T/product.new" "$PROD"

# checks: what a release must have and must not have (as tools/lumen_v1.sh T / X lines)
"$D" -R "cat $PROD" "$OUT.part" > "$T/p.chk" 2>/dev/null
"$D" -R "cat $EXT" "$OUT.part" > "$T/e.chk" 2>/dev/null
cmp -s "$T/p.chk" "$T/product.new" || die "product build.prop read back differs"
cmp -s "$T/e.chk" "$T/ext.new" || die "system_ext build.prop read back differs"
for l in ro.adb.secure=1 persist.sys.usb.config=none "ro.lumen.version=$VER" "ro.lumen.version_code=$VCODE" \
         "ro.lumen.build_id=$BUILD_ID" ro.lumen.device=play6; do
  grep -qx "$l" "$T/p.chk" || die "product build.prop lacks $l"
done
for l in ro.adb.secure=0 service.adb.tcp.port=5555 persist.sys.usb.config=adb; do
  grep -qx "$l" "$T/p.chk" "$T/e.chk" && die "a build.prop still has $l"
done
grep -qx ro.adb.secure=1 "$T/e.chk" && grep -qx persist.sys.usb.config=none "$T/e.chk" || die "system_ext adb lines not set"
"$D" -R "stat $PROD" "$OUT.part" 2>/dev/null | grep -q "User:     0   Group:     0" || die "build.prop owner"
"$D" -R "ea_get $PROD security.selinux" "$OUT.part" 2>/dev/null | grep -q system_file || die "build.prop label"
for g in /system/product/priv-app/PrebuiltGmsCorePano /system/system_ext/priv-app/GoogleServicesFramework \
         /system/product/priv-app/Phonesky /system/product/priv-app/Tubesky; do
  "$D" -R "stat $g" "$OUT.part" 2>/dev/null | grep -q '^Inode' && die "Google file $g in the image"
done
"$E2/e2fsck" -fn "$OUT.part" >/dev/null 2>&1 || die "e2fsck found problems"
mv "$OUT.part" "$OUT"
shasum -a 256 "$OUT" | sed "s#  .*#  $(basename "$OUT")#" > "$OUT.sha256"
echo "release_image: $OUT ($(stat -f %z "$OUT") bytes), ro.lumen.version=$VER ($VCODE), $BUILD_ID, adb secured"
cat "$OUT.sha256"
