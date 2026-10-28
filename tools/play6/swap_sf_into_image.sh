#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# swap_sf_into_image.sh: put a newly built /system/bin/surfaceflinger (lineage/play6_sf_build.sh) into an existing
# Play 6 system image, the way tools/play6/aic/add_aic_to_image.sh edits one: debugfs, nothing else changes except
# ro.lumen.build_id (gets "-TAG"). Mode, owner and SELinux label are copied from the binary it replaces.
#
#   tools/play6/swap_sf_into_image.sh IN.img OUT.img TAG SURFACEFLINGER
#     TAG  short build mark, e.g. kst1
# Checks afterwards: the file reads back equal, mode/owner/label as before, e2fsck clean. Writes OUT.img and
# OUT.img.sha256.
set -euo pipefail
die() { echo "swap_sf_into_image: ERROR: $*" >&2; exit 1; }
IN=${1:?usage: swap_sf_into_image.sh IN.img OUT.img TAG SURFACEFLINGER}; OUT=${2:?OUT.img}; TAG=${3:?TAG}
SF=${4:?SURFACEFLINGER}
echo "$TAG" | grep -Eq '^[a-z0-9]+$' || die "TAG must be lower case letters and digits, not $TAG"
E2=${E2FS:-/opt/homebrew/opt/e2fsprogs/sbin}
[ -x "$E2/debugfs" ] || E2=/sbin
D=$E2/debugfs
[ -x "$D" ] || die "needs e2fsprogs (debugfs)"
[ -s "$IN" ] || die "no $IN"
[ -s "$SF" ] || die "no $SF"
file "$SF" | grep -q 'ELF 32-bit LSB pie executable, ARM' || die "$SF is not a 32-bit ARM executable"

T=$(mktemp -d "${TMPDIR:-/tmp}/p6sf.XXXXXX")
trap 'rm -rf "$T"' EXIT
PROD=/system/product/etc/build.prop
BIN=/system/bin/surfaceflinger
"$D" -R "cat $PROD" "$IN" > "$T/product.prop" 2>/dev/null
grep -qx 'ro.lumen.device=play6' "$T/product.prop" || die "$IN is not a Play 6 image"
OLD_ID=$(sed -n 's/^ro\.lumen\.build_id=//p' "$T/product.prop")
[ -n "$OLD_ID" ] || die "no ro.lumen.build_id in $IN"
grep -v '^ro\.lumen\.build_id=' "$T/product.prop" > "$T/product.new"
echo "ro.lumen.build_id=$OLD_ID-$TAG" >> "$T/product.new"

attrs() {  # attrs IMAGE PATH -> "MODE UID GID LABEL"
  local st m u g l
  st=$("$D" -R "stat $2" "$1" 2>/dev/null)
  m=$(echo "$st" | sed -n 's/.*Mode: *\(0[0-7]*\).*/\1/p' | head -1)
  u=$(echo "$st" | sed -n 's/.*User: *\([0-9]*\).*/\1/p' | head -1)
  g=$(echo "$st" | sed -n 's/.*Group: *\([0-9]*\).*/\1/p' | head -1)
  l=$("$D" -R "ea_get $2 security.selinux" "$1" 2>/dev/null | grep -o 'u:object_r:[a-z0-9_]*:s0' | head -1)
  [ -n "$m" ] && [ -n "$u" ] && [ -n "$g" ] && [ -n "$l" ] || die "cannot read the attributes of $2"
  echo "$m $u $g $l"
}
read -r BM BU BG BL < <(attrs "$IN" $BIN)
read -r PM PU PG PL < <(attrs "$IN" $PROD)
printf '%s\0' "$BL" > "$T/lbin"; printf '%s\0' "$PL" > "$T/lprop"
cp "$SF" "$T/sf"
cp "$IN" "$OUT.part"
cat > "$T/cmds" <<EOF
cd /system/bin
rm surfaceflinger
write $T/sf surfaceflinger
set_inode_field $BIN mode 0100${BM#0}
set_inode_field $BIN uid $BU
set_inode_field $BIN gid $BG
ea_set -f $T/lbin $BIN security.selinux
cd /system/product/etc
rm build.prop
write $T/product.new build.prop
set_inode_field $PROD mode 0100${PM#0}
set_inode_field $PROD uid $PU
set_inode_field $PROD gid $PG
ea_set -f $T/lprop $PROD security.selinux
EOF
"$D" -w -f "$T/cmds" "$OUT.part" >/dev/null 2>"$T/dbg.err" || true
grep -v '^debugfs ' "$T/dbg.err" | grep . && die "debugfs: see above"

for pair in "$BIN $T/sf $BM $BU $BG $BL" "$PROD $T/product.new $PM $PU $PG $PL"; do
  set -- $pair
  "$D" -R "cat $1" "$OUT.part" > "$T/rb" 2>/dev/null
  cmp -s "$T/rb" "$2" || die "$1 read back differs"
  [ "$(attrs "$OUT.part" "$1")" = "$3 $4 $5 $6" ] || die "$1 attributes changed: $(attrs "$OUT.part" "$1")"
done
"$E2/e2fsck" -fn "$OUT.part" >/dev/null 2>&1 || die "e2fsck found problems"
mv "$OUT.part" "$OUT"
if command -v shasum >/dev/null; then shasum -a 256 "$OUT"; else sha256sum "$OUT"; fi \
  | sed "s#  .*#  $(basename "$OUT")#" > "$OUT.sha256"
echo "swap_sf_into_image: $OUT, build id $OLD_ID-$TAG, surfaceflinger $BM $BU:$BG $BL"
cat "$OUT.sha256"
