#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# add_aic_to_image.sh: put the AIC8800D80X2 Wi-Fi/BT support (tools/play6/aic/BUILD.md) into an existing Play 6
# system image, the way tools/play6/release_image.sh edits a release image: debugfs of Homebrew e2fsprogs on the
# Mac, nothing else in the image changes (the vendor fstab mounts system without verity, so the stale AVB footer
# is never read). Used for the AIC pre-release on top of the published play6-1.0.6 image while the build laptop
# was away; tools/play6/compose.sh puts the same files into a fresh build.
#
#   tools/play6/aic/add_aic_to_image.sh IN.img OUT.img TAG [--diag]
#     TAG     short build mark, e.g. aic1: ro.lumen.build_id gets "-TAG" and ro.lumen.p6.aic=TAG is added
#     --diag  also the test builds' boot logger and escape (tools/play6/compose.sh --diag): z9x_earlylog writes a
#             boot log to a USB stick from 'on init' on (and the previous boot's pstore), earlylog_usb.sh loads
#             the Play 6 USB host modules for it, lumen_escape reboots into fastbootd when the boot does not
#             complete. ro.lumen.diag=1 is added. Never for a regular release.
#
# Adds (root, the labels the base gives such files):
#   /system/etc/aic8800/modules/*.ko, /system/etc/aic8800/firmware/*     0644 system_file   (dirs 0755)
#   /system/lib/libbt-vendor-aic.so                                     0644 vendor_file (bound over /vendor/lib)
#   /system/etc/bluetooth/aicbt.conf                                    0644 system_file   (dir 0755)
#   /system/etc/init/play6_aic.rc 0644, /system/etc/xgimi/play6_aic.sh 0755              system_file
# Inputs: build/play6/aic/{modules,firmware,bt/libbt-vendor-aic.so,bt/aicbt.conf} checked against
# tools/play6/aic/SHA256SUMS; overlay/play6/play6_aic.{rc,sh}. Checks afterwards: every file read back equal,
# modes, owner, labels, e2fsck clean. Writes OUT.img and OUT.img.sha256.
set -euo pipefail
die() { echo "add_aic_to_image: ERROR: $*" >&2; exit 1; }
IN=${1:?usage: add_aic_to_image.sh IN.img OUT.img TAG [--diag]}; OUT=${2:?OUT.img}; TAG=${3:?TAG}
DIAG=0; [ "${4:-}" = --diag ] && DIAG=1
echo "$TAG" | grep -Eq '^[a-z0-9]+$' || die "TAG must be lower case letters and digits, not $TAG"
G=$(cd "$(dirname "$0")/../../.." && pwd)
A=$G/build/play6/aic
E2=${E2FS:-/opt/homebrew/opt/e2fsprogs/sbin}
D=$E2/debugfs
[ -x "$D" ] || die "needs e2fsprogs (brew install e2fsprogs)"
[ -s "$IN" ] || die "no $IN"
cp "$G/tools/play6/aic/SHA256SUMS" "$A/SHA256SUMS"
(cd "$A" && shasum -a 256 -c SHA256SUMS >/dev/null) || die "build/play6/aic does not match tools/play6/aic/SHA256SUMS"
for f in bt/libbt-vendor-aic.so bt/aicbt.conf; do grep -q " $f\$" "$A/SHA256SUMS" || die "$f is not pinned in SHA256SUMS"; done

T=$(mktemp -d "${TMPDIR:-/tmp}/p6aic.XXXXXX")
trap 'rm -rf "$T"' EXIT
PROD=/system/product/etc/build.prop
"$D" -R "cat $PROD" "$IN" > "$T/product.prop" 2>/dev/null
grep -qx 'ro.lumen.device=play6' "$T/product.prop" || die "$IN is not a Play 6 image"
grep -q '^ro.lumen.p6.aic=' "$T/product.prop" && die "$IN already has the AIC files"
OLD_ID=$(sed -n 's/^ro\.lumen\.build_id=//p' "$T/product.prop")
[ -n "$OLD_ID" ] || die "no ro.lumen.build_id in $IN"
grep -v '^ro\.lumen\.build_id=' "$T/product.prop" > "$T/product.new"
{ echo "ro.lumen.build_id=$OLD_ID-$TAG"; echo "ro.lumen.p6.aic=$TAG"; [ $DIAG = 1 ] && echo "ro.lumen.diag=1"; } >> "$T/product.new"

cp "$IN" "$OUT.part"
CMDS=$T/cmds
: > "$CMDS"
label() { printf '%s\0' "$1" > "$T/l_$2"; echo "$T/l_$2"; }
LSYS=$(label u:object_r:system_file:s0 sys)
LLIB=$(label u:object_r:vendor_file:s0 lib)
mkd() {  # mkd IMAGE_DIR
  echo "cd ${1%/*}" >> "$CMDS"
  echo "mkdir ${1##*/}" >> "$CMDS"
  echo "set_inode_field $1 mode 040755" >> "$CMDS"
  echo "set_inode_field $1 uid 0" >> "$CMDS"
  echo "set_inode_field $1 gid 0" >> "$CMDS"
  echo "ea_set -f $LSYS $1 security.selinux" >> "$CMDS"
}
put() {  # put LOCAL IMAGE_PATH MODE LABELFILE
  echo "cd ${2%/*}" >> "$CMDS"
  echo "rm ${2##*/}" >> "$CMDS"
  echo "write $1 ${2##*/}" >> "$CMDS"
  echo "set_inode_field $2 mode 0100$3" >> "$CMDS"
  echo "set_inode_field $2 uid 0" >> "$CMDS"
  echo "set_inode_field $2 gid 0" >> "$CMDS"
  echo "ea_set -f $4 $2 security.selinux" >> "$CMDS"
}
LIST=$T/list   # IMAGE_PATH LOCAL MODE LABEL, for the read-back checks
: > "$LIST"
NF=0
add() {  # add IMAGE_PATH LOCAL MODE LABELFILE LABEL; debugfs splits paths at spaces, so write from $T
  NF=$((NF + 1)); cp "$2" "$T/f$NF"
  put "$T/f$NF" "$1" "$3" "$4"; echo "$1 $T/f$NF $3 $5" >> "$LIST"
}

mkd /system/etc/aic8800
mkd /system/etc/aic8800/modules
mkd /system/etc/aic8800/firmware
"$D" -R "stat /system/etc/bluetooth" "$IN" 2>/dev/null | grep -q '^Inode' || mkd /system/etc/bluetooth
for f in "$A"/modules/*.ko; do add "/system/etc/aic8800/modules/${f##*/}" "$f" 644 "$LSYS" system_file; done
for f in "$A"/firmware/*; do add "/system/etc/aic8800/firmware/${f##*/}" "$f" 644 "$LSYS" system_file; done
add /system/lib/libbt-vendor-aic.so "$A/bt/libbt-vendor-aic.so" 644 "$LLIB" vendor_file
add /system/etc/bluetooth/aicbt.conf "$A/bt/aicbt.conf" 644 "$LSYS" system_file
add /system/etc/init/play6_aic.rc "$G/overlay/play6/play6_aic.rc" 644 "$LSYS" system_file
add /system/etc/xgimi/play6_aic.sh "$G/overlay/play6/play6_aic.sh" 755 "$LSYS" system_file
if [ $DIAG = 1 ]; then
  add /system/etc/init/z9x_earlylog.rc "$G/overlay/debug/z9x_earlylog.rc" 644 "$LSYS" system_file
  add /system/etc/xgimi/z9x_earlylog.sh "$G/overlay/debug/z9x_earlylog.sh" 755 "$LSYS" system_file
  add /system/etc/xgimi/earlylog_usb.sh "$G/overlay/play6/earlylog_usb.sh" 644 "$LSYS" system_file
  add /system/etc/init/lumen_escape.rc "$G/overlay/debug/lumen_escape.rc" 644 "$LSYS" system_file
  add /system/etc/xgimi/lumen_escape.sh "$G/overlay/debug/lumen_escape.sh" 755 "$LSYS" system_file
fi
add $PROD "$T/product.new" 644 "$LSYS" system_file

"$D" -w -f "$CMDS" "$OUT.part" >/dev/null 2>"$T/dbg.err" || true
grep -v '^debugfs ' "$T/dbg.err" | grep -vE 'rm: File not found by ext2_lookup' | grep . && die "debugfs: see above"

# read back every file: content, mode, owner, label
while read -r p l m lab; do
  "$D" -R "cat $p" "$OUT.part" > "$T/rb" 2>/dev/null
  cmp -s "$T/rb" "$l" || die "$p read back differs"
  st=$("$D" -R "stat $p" "$OUT.part" 2>/dev/null)
  echo "$st" | grep -q "Mode:  0$m" || die "$p mode is not 0$m"
  echo "$st" | grep -q "User:     0   Group:     0" || die "$p owner is not root"
  "$D" -R "ea_get $p security.selinux" "$OUT.part" 2>/dev/null | grep -q "$lab" || die "$p label is not $lab"
done < "$LIST"
"$E2/e2fsck" -fn "$OUT.part" >/dev/null 2>&1 || die "e2fsck found problems"
mv "$OUT.part" "$OUT"
shasum -a 256 "$OUT" | sed "s#  .*#  $(basename "$OUT")#" > "$OUT.sha256"
echo "add_aic_to_image: $OUT ($(stat -f %z "$OUT") bytes), build id $OLD_ID-$TAG, $(wc -l < "$LIST" | tr -d ' ') files"
cat "$OUT.sha256"
