#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Host test of the erofs Google services add-on of the one-pass install (lumen-install.sh --gapps-zip, XGIMI Z9X):
# the installer's OWN code (the section "XGIMI Z9X: Lumen OS and Google services in one pass" of
# installer/lumen-install.sh, sourced: eu_setup, gi_tar, gi_build) builds the image from a zip with the shipped
# erofs-utils of installer/tools/erofs-utils, and the image's own z9x_gapps.sh checks the result. On the Mac or
# Linux, no projector:
#   bash tools/ota/test/gapps_erofs.sh
# For the fake zip of gapps_fixture.py (always) and the real MindTheGapps arm64 zip at lineage/gapps/ (a local
# input only, never copied; skipped when absent):
#   1. tools/ota/gapps_allow.py image (an independent Python tar writer, the same mkfs.erofs) makes the reference
#      line; the real zip's line must be the one in installer/lib/gapps_images.txt;
#   2. gi_build (bash tar writer -> mkfs.erofs -> fsck.erofs -> every file read back -> image sha256 against the
#      reference) must succeed, and its image must be byte for byte the Python one;
#   3. dump.erofs: the root, every directory, every file and MANIFEST: uid 0, gid 0, mode 0755 / 0644, an xattr
#      (the SELinux label); tools/erofs_kcheck.py (the Z9X kernel's inline rule) finds nothing;
#   4. the image unpacked (fsck.erofs --extract) is the partition of a fake root: the image's z9x_gapps.sh 'check'
#      says "ok: N files of NAME (erofs)" and a boot run sets sys.z9x.gapps=ok, sys.z9x.gapps.fs=erofs (the mount
#      stub accepts erofs for a partition marked so);
#   5. refusals of gi_build: a zip whose member differs from the allow-list (nothing built), and a reference line
#      that names another image (the built one is deleted, not kept).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../../.." && pwd)
INST=$GSI/installer
IMGDIR=$GSI/tools/ota/image
REAL=$GSI/lineage/gapps/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip
W=$(mktemp -d "${TMPDIR:-/tmp}/z9x_gapps_erofs.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'chmod -R u+w "$W" 2>/dev/null; rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
case "$(uname -s)" in Darwin) EP=darwin ;; *) case "$(uname -m)" in x86_64|amd64) EP=linux-x86_64 ;; *) EP=linux-aarch64 ;; esac ;; esac
EU=$INST/tools/erofs-utils/$EP
[ -x "$EU/mkfs.erofs" ] || { echo "no installer/tools/erofs-utils/$EP (build it: tools/ota/build_erofs_utils.sh)"; exit 1; }
mkdir -p "$W/stubs"
for f in getprop setprop log timeout mount umount blockdev; do cp "$HERE/stubs/$f" "$W/stubs/$f"; done
chmod 755 "$W/stubs/"*
python3 "$HERE/gapps_fixture.py" make "$IMGDIR/gapps_allow.txt" "$W/fx" || exit 1
# the installer's one-pass section, as the installer runs it
awk '/^# -+ XGIMI Z9X: Lumen OS and Google services in one pass/ { on = 1 } /^# -+ projector profiles/ { on = 0 } on' \
  "$INST/lumen-install.sh" > "$W/section.sh"
grep -q '^gi_build()' "$W/section.sh" || { echo "the one-pass section was not found in lumen-install.sh"; exit 1; }

fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
begin() { CASE=$1; CFAIL=0; }
end() { if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"; else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; fi; }
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

# gi ZIP ALLOW IMAGES STATE: gi_build of the installer in a bash of its own (an installer folder with that
# allow-list and gapps_images.txt, the real tools/); output in $W/gi.out, exit status in RC
gi() {
  local d=$W/inst.$CASE
  rm -rf "$d"; mkdir -p "$d/lib"; cp "$2" "$d/lib/gapps_allow.txt"; cp "$3" "$d/lib/gapps_images.txt"; cp "$INST/lib/erofs_utils.txt" "$d/lib/"; ln -s "$INST/tools" "$d/tools"
  bash -c '
    set -u
    HERE=$1; STATE_DIR=$2; ZIP=$3; LANG_UI=en
    t() { printf "%s" "$1"; }
    say() { printf "%s\n" "$*"; }; ok() { say "  ok $*"; }; warn() { say "  ! $*"; }
    die() { say "STOP: $*"; exit 1; }
    sha256() { shasum -a 256 "$1" | cut -d" " -f1; }
    gapps_line() { awk -v z="zip=$1" "\$1 == z" "$HERE/lib/gapps_allow.txt" | head -n 1; }
    . "$4"
    GZ_SHA=$(sha256 "$ZIP"); GZ_SHA=${GZ_SHA_FORCE:-$GZ_SHA}
    GZ_NAME=$(gapps_line "$GZ_SHA" | cut -d" " -f2)
    eu_setup
    gi_build "$ZIP"
    echo "IMG $GI_IMG"' gi "$d" "$4" "$1" "$W/section.sh" > "$W/gi.out" 2>&1
  RC=$?
}

# root ALLOWFILE PARTDIR: a fake projector root with the partition unpacked from the image, marked erofs
root() {
  R=$W/root.$CASE; T=$W/t.$CASE
  rm -rf "$R" "$T"; mkdir -p "$R/system/etc/z9x/gapps/sysconfig" "$R/dev/block/mapper" "$R/mnt" "$R/metadata" "$R/proc" "$T" \
    "$R/system/etc/sysconfig" "$R/system/product" "$R/system/system_ext/priv-app" "$R/system/system_ext/etc/permissions" \
    "$R/system/app" "$R/system/etc/permissions"
  echo "9.95 3.20" > "$R/proc/uptime"
  cp "$1" "$R/system/etc/z9x/gapps_allow.txt"
  cp "$IMGDIR/z9x_gapps.sh" "$R/system/etc/z9x/z9x_gapps.sh"
  cp "$IMGDIR/z9x-gapps-sysconfig.xml" "$R/system/etc/z9x/gapps/sysconfig/z9x-gapps.xml"
  "$EU/fsck.erofs" --extract="$R/dev/block/mapper/z9x_gapps_b" "$2" >/dev/null 2>&1 || fail "fsck.erofs --extract"
  echo erofs > "$R/dev/block/mapper/z9x_gapps_b.fs"
  printf '%s\n' ro.boot.slot_suffix=_b ro.z9x.gms=0 ro.z9x.build_id=lumen-1.0.8-test > "$T/props"
  : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/mounts"; : > "$T/mount.log"; : > "$T/umount.log"; : > "$T/blockdev.log"
}
zg() { env Z9X_ROOT="$R" Z9X_T="$T" PATH="$W/stubs:$PATH" sh "$R/system/etc/z9x/z9x_gapps.sh" "$@"; }
gprop() { Z9X_T=$T "$W/stubs/getprop" "$1"; }

# image ZIP ALLOW NAME [EXPECTED_LINE]: steps 1 to 4 for one zip
image() {
  local zip=$1 allow=$2 ref img n entries p info mode want
  begin "$3"
  ref=$(python3 "$GSI/tools/ota/gapps_allow.py" image "$zip" --allow "$allow" --mkfs "$EU/mkfs.erofs" --out "$W/py.img" --keep) \
    || { fail "gapps_allow.py image"; end; return; }
  if [ -n "${4-}" ] && [ "$ref" != "$4" ]; then fail "reference line '$ref', installer/lib/gapps_images.txt has '$4'"; fi
  printf '%s\n' "$ref" > "$W/images.txt"
  gi "$zip" "$allow" "$W/images.txt" "$W/state.$CASE"
  [ "$RC" = 0 ] || { fail "gi_build: $(tail -n 3 "$W/gi.out")"; end; return; }
  img=$(sed -n 's/^IMG //p' "$W/gi.out")
  cmp -s "$img" "$W/py.img" || fail "the installer's image differs from the Python writer's"
  [ "$(sha "$img")" = "$(echo "$ref" | awk '{ print $4 }')" ] || fail "image sha256"
  grep -q "Google services add-on image: .* files checked" "$W/gi.out" || fail "gi_build said: $(cat "$W/gi.out")"
  [ ! -e "$W/state.$CASE/gapps/gapps.tar" ] || fail "the tar was left behind"
  # 3. owner, mode, label of every entry; the kernel's inline rule
  n=$(awk -v z="zip=$(sha "$zip")" '$1 ~ /^zip=/ { on = ($1 == z); next } on && NF == 3 && $1 !~ /^#/' "$allow" | wc -l | tr -d ' ')
  entries=$( { echo /; echo /MANIFEST
    awk -v z="zip=$(sha "$zip")" '$1 ~ /^zip=/ { on = ($1 == z); next } on && NF == 3 && $1 !~ /^#/ { p = $3; print "/" p; while (sub(/\/[^\/]*$/, "", p)) print "/" p "/" }' "$allow"
  } | sort -u)
  for p in $entries; do
    info=$("$EU/dump.erofs" --path="${p%/}" "$img" 2>&1)
    case "$p" in */) want=0755 ;; /) want=0755 ;; *) want=0644 ;; esac
    printf '%s\n' "$info" | grep -q "Uid: 0 *Gid: 0" || fail "$p: owner: $(printf '%s\n' "$info" | grep -i uid)"
    mode=$(printf '%s\n' "$info" | sed -n 's/.*Access: \([0-7]*\).*/\1/p' | head -n 1)
    [ "${mode#0}" = "${want#0}" ] || fail "$p: mode '$mode', want $want"
    printf '%s\n' "$info" | grep -qE "Xattr size: [1-9]" || fail "$p: no xattr (SELinux label)"
  done
  python3 "$GSI/tools/erofs_kcheck.py" "$img" > "$W/kcheck.out" 2>&1 || fail "erofs_kcheck: $(tail -n 3 "$W/kcheck.out")"
  # 4. the image's own script on the unpacked image
  root "$allow" "$img"
  out=$(zg check 2>&1)
  [ "$out" = "ok: $n files of $(basename "$zip") (erofs)" ] || fail "z9x_gapps.sh check said: $out"
  root "$allow" "$img"
  zg > "$T/boot.out" 2>&1
  [ ! -s "$T/boot.out" ] || fail "boot run output: $(cat "$T/boot.out")"
  [ "$(gprop sys.z9x.gapps)" = ok ] && [ "$(gprop sys.z9x.gapps.fs)" = erofs ] \
    || fail "boot run: sys.z9x.gapps=$(gprop sys.z9x.gapps) fs=$(gprop sys.z9x.gapps.fs): $(cat "$T/logcat")"
  grep -q "^$R/mnt/z9x_gapps erofs .*context=u:object_r:system_file:s0\$" "$T/mounts" || fail "mount: $(cat "$T/mounts")"
  rm -f "$W/py.img"; rm -rf "$W/state.$CASE" "$R"
  end
}

FZIP=$(ls "$W/fx/zip/"*.zip)
image "$FZIP" "$W/fx/allow.txt" fake_zip
if [ -f "$REAL" ]; then
  image "$REAL" "$INST/lib/gapps_allow.txt" real_arm64_zip "$(grep -v '^#' "$INST/lib/gapps_images.txt" | grep "^$(sha "$REAL") ")"
else
  echo "skip real_arm64_zip (no $REAL)"
fi

begin refuse_changed_member
# the zip of the allow-list's name, one member changed: gi_tar stops before mkfs, nothing is kept
python3 - "$FZIP" "$W/bad.zip" <<'EOF'
import sys, zipfile
src, dst = sys.argv[1:3]
with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst, "w") as b:
    done = False
    for i in a.infolist():
        data = a.read(i)
        if not done and i.filename.startswith("system/product/") and not i.is_dir():
            data, done = data + b"changed", True
        b.writestr(i, data)
EOF
python3 "$GSI/tools/ota/gapps_allow.py" image "$FZIP" --allow "$W/fx/allow.txt" --mkfs "$EU/mkfs.erofs" --out "$W/py.img" > "$W/images.txt"
GZ_SHA_FORCE=$(sha "$FZIP") gi "$W/bad.zip" "$W/fx/allow.txt" "$W/images.txt" "$W/state.$CASE"
[ "$RC" = 1 ] || fail "exit $RC"
grep -q "STOP: the zip does not hold the expected files (sha256 of product/" "$W/gi.out" || fail "said: $(cat "$W/gi.out")"
[ -z "$(ls "$W/state.$CASE/gapps/" 2>/dev/null | grep '\.img')" ] || fail "an image was kept"
end

begin refuse_other_reference
awk '{ $4 = "1111111111111111111111111111111111111111111111111111111111111111"; print }' "$W/images.txt" > "$W/images.bad"
gi "$FZIP" "$W/fx/allow.txt" "$W/images.bad" "$W/state.$CASE"
[ "$RC" = 1 ] || fail "exit $RC"
grep -q "is not the one checked for this release" "$W/gi.out" || fail "said: $(cat "$W/gi.out")"
[ -z "$(ls "$W/state.$CASE/gapps/" 2>/dev/null | grep '\.img')" ] || fail "the unchecked image was kept"
end

begin images_file_format
python3 "$GSI/tools/ota/gapps_allow.py" image-check "$INST/lib/gapps_images.txt" > "$W/ic.out" 2>&1 || fail "$(cat "$W/ic.out")"
end

echo "gapps erofs: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
