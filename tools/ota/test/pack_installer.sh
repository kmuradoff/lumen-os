#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Host test of the release / pre-release packaging: tools/ota/pack_installer.sh (installer zip made for one image,
# with the pair check), tools/play6/image_version.sh and the image tools that use it (tools/play6/release_image.sh,
# tools/play6/swap_sf_into_image.sh). On the Mac (e2fsprogs from Homebrew; erofs-utils for the Z9X case), no
# projector, no network:
#   bash tools/ota/test/pack_installer.sh
# Fixtures: a 16 MiB ext4 Play 6 test-build image (product + system_ext build.prop, a fake 32-bit ARM
# surfaceflinger), a throwaway signing key whose certificate replaces the installer's certificates in a copy of
# installer/ (without installer/tools), SHA256SUMS signed by it; for the Z9X an erofs image made by mkfs.erofs.
# The case that started it (play6-1.0.7-kst1, 2026-10-29): an image named 1.0.7-kst1 holding
# ro.lumen.version=1.0.7, shipped with an installer zip whose release.conf said VERSION=1.0.7. Each half is
# refused now: swap_sf_into_image.sh will not name an image for a version it does not carry, and
# pack_installer.sh / --check refuse any zip whose name, VERSION or DEVICE is not the image's.
# Also: the installer still checks the rule this packaging follows (name version = release.conf VERSION =
# the image's version property, lumen-install.sh and .ps1).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
PACK=$G/tools/ota/pack_installer.sh
W=$(mktemp -d "${TMPDIR:-/tmp}/lumen_pack.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0
ok() { PASS=$((PASS + 1)); echo "  ok   $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL $1"; [ -n "${2:-}" ] && sed 's/^/       | /' "$2" | tail -8; }
# expect NAME STATUS PATTERN CMD...: CMD exits with STATUS and its output has PATTERN (grep -E; '' = any)
expect() {
  local n=$1 want=$2 pat=$3 got; shift 3
  "$@" > "$W/out" 2>&1; got=$?
  if [ "$got" != "$want" ]; then bad "$n (exit $got, wanted $want)" "$W/out"; return; fi
  if [ -n "$pat" ] && ! grep -Eq -- "$pat" "$W/out"; then bad "$n (no '$pat' in the output)" "$W/out"; return; fi
  ok "$n"
}
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

if [ -x /opt/homebrew/opt/e2fsprogs/sbin/debugfs ]; then E2=/opt/homebrew/opt/e2fsprogs/sbin
elif command -v debugfs >/dev/null 2>&1; then E2=$(dirname "$(command -v debugfs)")
else echo "needs e2fsprogs (brew install e2fsprogs)"; exit 1; fi
export E2FS=$E2
D=$E2/debugfs
for c in openssl zip unzip file python3; do command -v $c >/dev/null || { echo "needs $c"; exit 1; }; done

# ---------------------------------------------------------------- fixtures
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/k.key" -out "$W/k.pem" -subj /CN=lumen-pack-test -days 2 >/dev/null 2>&1 \
  && openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/o.key" -out "$W/o.pem" -subj /CN=lumen-pack-other -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }
sign() {  # sign DIR [KEY]: SHA256SUMS of every *system.img in DIR, signed like the release
  (cd "$1" && shasum -a 256 *system.img > SHA256SUMS) && openssl dgst -sha256 -sign "${2:-$W/k.key}" -out "$1/SHA256SUMS.sig" "$1/SHA256SUMS"
}

# the installer as the exported repository has it: no tools/, our test certificate for every projector
mkdir -p "$W/src"
(cd "$G/installer" && find . -type f ! -path './tools/*' ! -name '.*' -print) | while IFS= read -r f; do
  mkdir -p "$W/src/installer/$(dirname "$f")"; cp "$G/installer/$f" "$W/src/installer/$f"
done
SRC=$W/src/installer
for c in $(sed -n 's/^[a-z0-9]*\.CERT=//p' "$SRC/lib/devices.conf"); do mkdir -p "$(dirname "$SRC/$c")"; cp "$W/k.pem" "$SRC/$c"; done
mkdir -p "$SRC/tools/platform-tools"; echo adb > "$SRC/tools/platform-tools/adb"   # Google's: never packed
echo "state" > "$SRC/.lumen-state"; echo log > "$SRC/install.log"
mkdir -p "$W/eu/darwin"; echo prog > "$W/eu/darwin/mkfs.erofs"

# a fake surfaceflinger: 32-bit ARM PIE (what 'file' must say for swap_sf_into_image.sh)
python3 - "$W/sf" <<'EOF'
import struct, sys
interp = b"/system/bin/linker\0"
dyn = struct.pack("<iIiI", 0x6ffffffb, 0x08000000, 0, 0)        # DT_FLAGS_1 = DF_1_PIE, DT_NULL
eh, pe, pn = 52, 32, 2
oi = eh + pe * pn; od = (oi + len(interp) + 4) // 4 * 4
b = b"\x7fELF\x01\x01\x01" + b"\0" * 9
b += struct.pack("<HHIIIIIHHHHHH", 3, 40, 1, 0, eh, 0, 0x05000000, eh, pe, pn, 40, 0, 0)
b += struct.pack("<IIIIIIII", 3, oi, oi, oi, len(interp), len(interp), 4, 1)
b += struct.pack("<IIIIIIII", 2, od, od, od, len(dyn), len(dyn), 6, 4)
b += interp; b += b"\0" * (od - len(b)) + dyn
open(sys.argv[1], "wb").write(b)
EOF
file "$W/sf" | grep -q 'ELF 32-bit LSB pie executable, ARM' || { echo "cannot make the fake surfaceflinger"; exit 1; }

# play6 test build tN (what tools/play6/build_test.sh makes): adb open, ro.lumen.version=play6-t33
R=$W/root; mkdir -p "$R/system/product/etc" "$R/system/system_ext/etc" "$R/system/bin"
printf '%s\n' ro.lumen.device=play6 service.adb.tcp.port=5555 ro.adb.secure=0 ro.lumen.version=play6-t33 \
  ro.lumen.version_code=10007 ro.lumen.build_id=lumen-play6-t33-202610241200 > "$R/system/product/etc/build.prop"
printf '%s\n' '# GSI always disables adb authentication' ro.adb.secure=0 persist.sys.usb.config=adb > "$R/system/system_ext/etc/build.prop"
cp "$W/sf" "$R/system/bin/surfaceflinger"; chmod 755 "$R/system/bin/surfaceflinger"
T33=$W/system_play6_t33.img
"$E2/mke2fs" -q -t ext4 -b 4096 -d "$R" "$T33" 16M >/dev/null 2>&1 || { echo "mke2fs cannot make the fixture"; exit 1; }
printf 'u:object_r:system_file:s0\0' > "$W/lsys"
"$D" -w -f - "$T33" >/dev/null 2>&1 <<EOF
ea_set -f $W/lsys /system/bin/surfaceflinger security.selinux
ea_set -f $W/lsys /system/product/etc/build.prop security.selinux
ea_set -f $W/lsys /system/system_ext/etc/build.prop security.selinux
EOF
prop() { "$D" -R "cat /system/product/etc/build.prop" "$1" 2>/dev/null | sed -n "s/^$2=//p" | tail -1; }

echo "image_version.sh"
. "$G/tools/play6/image_version.sh"
expect iv_keep 0 '^1\.0\.7$' iv_new_version 1.0.7 ""
expect iv_pre 0 '^1\.0\.7-kst1$' iv_new_version 1.0.7 1.0.7-kst1
expect iv_pre_of_pre 0 '^1\.0\.7-kst2$' iv_new_version 1.0.7-kst1 1.0.7-kst2
expect iv_other_release 1 'not a build of 1.0.7' iv_new_version 1.0.7 1.0.8-kst1
expect iv_bad_mark 1 'x.y.z-<mark>' iv_new_version 1.0.7 1.0.7-KST
expect iv_test_build 1 'test build' iv_new_version play6-t33 1.0.7-kst1
expect iv_no_version 1 'no ro.lumen.version' iv_new_version "" 1.0.7
expect iv_name_ok 0 '' iv_check_out_name /x/lumen-os-play6-1.0.7-kst1-nogms-system.img 1.0.7-kst1
expect iv_name_incident 1 'named for Lumen OS 1.0.7-kst1.*ro.lumen.version is 1.0.7' iv_check_out_name /x/lumen-os-play6-1.0.7-kst1-nogms-system.img 1.0.7
expect iv_name_free 0 '' iv_check_out_name /x/system_play6_kst1.img 1.0.7
expect iv_name_z9x_style 1 'not the Play 6 one' iv_check_out_name /x/lumen-os-1.0.7-nogms-system.img 1.0.7

echo "release_image.sh"
mkdir -p "$W/rel" "$W/pre"
REL=$W/rel/lumen-os-play6-1.0.7-nogms-system.img
expect rel_wrong_name 1 'named for Lumen OS 1.0.6' bash "$G/tools/play6/release_image.sh" "$T33" 1.0.7 "$W/rel/lumen-os-play6-1.0.6-nogms-system.img"
check rel_wrong_name_wrote_nothing '[ ! -e "$W/rel/lumen-os-play6-1.0.6-nogms-system.img" ] && [ ! -e "$W/rel/lumen-os-play6-1.0.6-nogms-system.img.part" ]'
expect rel_ok 0 'ro.lumen.version=1.0.7' bash "$G/tools/play6/release_image.sh" "$T33" 1.0.7 "$REL"
check rel_props '[ "$(prop "$REL" ro.lumen.version)" = 1.0.7 ] && [ "$(prop "$REL" ro.adb.secure)" = 1 ]'
rm -f "$REL.sha256"

echo "swap_sf_into_image.sh"
KST=$W/pre/lumen-os-play6-1.0.7-kst1-nogms-system.img
expect sf_incident 1 'named for Lumen OS 1.0.7-kst1' bash "$G/tools/play6/swap_sf_into_image.sh" "$REL" "$KST" kst1 "$W/sf"
check sf_incident_wrote_nothing '[ ! -e "$KST" ] && [ ! -e "$KST.part" ]'
expect sf_other_release 1 'not a build of 1.0.7' bash "$G/tools/play6/swap_sf_into_image.sh" "$REL" "$KST" kst1 "$W/sf" --version 1.0.8-kst1
expect sf_test_build 1 'test build' bash "$G/tools/play6/swap_sf_into_image.sh" "$T33" "$W/x.img" kst1 "$W/sf" --version 1.0.7-kst1
expect sf_unknown_arg 1 'unknown argument' bash "$G/tools/play6/swap_sf_into_image.sh" "$REL" "$KST" kst1 "$W/sf" --versoin 1.0.7-kst1
expect sf_keep_version 0 'ro.lumen.version=1.0.7,' bash "$G/tools/play6/swap_sf_into_image.sh" "$REL" "$W/sf_plain.img" kst1 "$W/sf"
check sf_keep_version_props '[ "$(prop "$W/sf_plain.img" ro.lumen.version)" = 1.0.7 ] && [ "$(prop "$W/sf_plain.img" ro.lumen.build_id)" = lumen-play6-1.0.7-20261024-kst1 ]'
expect sf_pre 0 'ro.lumen.version=1.0.7-kst1' bash "$G/tools/play6/swap_sf_into_image.sh" "$REL" "$KST" kst1 "$W/sf" --version 1.0.7-kst1
check sf_pre_props '[ "$(prop "$KST" ro.lumen.version)" = 1.0.7-kst1 ] && [ "$(prop "$KST" ro.lumen.version_code)" = 10007 ] && [ "$(prop "$KST" ro.lumen.build_id)" = lumen-play6-1.0.7-20261024-kst1 ] && [ "$(grep -c "^ro.lumen.version=" <("$D" -R "cat /system/product/etc/build.prop" "$KST" 2>/dev/null))" = 1 ]'
rm -f "$KST.sha256"
sign "$W/pre"; sign "$W/rel"

echo "pack_installer.sh (Play 6)"
OUT=$W/out1
expect pack_kst1 0 'OK lumen-os-play6-1.0.7-kst1-installer.zip' bash "$PACK" "$KST" "$OUT" --installer "$SRC" --erofs-utils "$W/eu"
Z=$OUT/lumen-os-play6-1.0.7-kst1-installer.zip
zrc() { unzip -p "$1" installer/release.conf | tr -d '\r' | grep -v '^#'; }
check pack_kst1_conf '[ "$(zrc "$Z")" = "$(printf "VERSION=1.0.7-kst1\nDEVICE=play6")" ]'
check pack_kst1_comments_kept 'unzip -p "$Z" installer/release.conf | grep -q "^# DEVICE: the projector"'
check pack_kst1_members 'unzip -l "$Z" | grep -q " installer/lumen-install.ps1$" && unzip -l "$Z" | grep -q " installer/tools/erofs-utils/darwin/mkfs.erofs$"'
check pack_kst1_no_google_tools '! unzip -l "$Z" | grep -Eq "platform-tools|\.lumen-state|install\.log"'
check pack_kst1_only_one_zip '[ "$(ls "$OUT")" = lumen-os-play6-1.0.7-kst1-installer.zip ]'
expect check_kst1 0 'OK' bash "$PACK" --check "$Z" "$KST"
expect pack_exists 1 'exists already' bash "$PACK" "$KST" "$OUT" --installer "$SRC"
expect pack_rel 0 'OK lumen-os-play6-1.0.7-installer.zip' bash "$PACK" "$REL" "$W/out2" --installer "$SRC"
check pack_rel_conf '[ "$(zrc "$W/out2/lumen-os-play6-1.0.7-installer.zip")" = "$(printf "VERSION=1.0.7\nDEVICE=play6")" ]'

# the incident, as it was published: the 1.0.7 zip renamed and also with the 1.0.7 release.conf
mkdir -p "$W/inc"; cp "$W/out2/lumen-os-play6-1.0.7-installer.zip" "$W/inc/"
expect check_incident_zip_name 1 'must be named lumen-os-play6-1.0.7-kst1-installer.zip' bash "$PACK" --check "$W/inc/lumen-os-play6-1.0.7-installer.zip" "$KST"
cp "$W/out2/lumen-os-play6-1.0.7-installer.zip" "$W/inc/lumen-os-play6-1.0.7-kst1-installer.zip"
expect check_incident_version 1 'VERSION=1.0.7 in release.conf, the image .* is 1.0.7-kst1' bash "$PACK" --check "$W/inc/lumen-os-play6-1.0.7-kst1-installer.zip" "$KST"
expect check_rel_with_kst_zip 1 'must be named lumen-os-play6-1.0.7-installer.zip' bash "$PACK" --check "$Z" "$REL"

# the hand fix of the incident: VERSION=1.0.7-kst1 over an image that still says 1.0.7
mkdir -p "$W/hf"; cp "$W/sf_plain.img" "$W/hf/lumen-os-play6-1.0.7-kst1-nogms-system.img"; sign "$W/hf"
expect pack_handfix_image 1 'holds ro.lumen.version=1.0.7 but is named 1.0.7-kst1' bash "$PACK" "$W/hf/lumen-os-play6-1.0.7-kst1-nogms-system.img" "$W/out3" --installer "$SRC"
check pack_handfix_left_nothing '[ -z "$(ls "$W/out3" 2>/dev/null)" ]'

# rebuilt zips with one thing wrong each
rezip() {  # rezip SRCZIP NEWZIP SED_ON_RELEASE_CONF [EXTRA_FILE]
  rm -rf "$W/rz"; mkdir -p "$W/rz"; unzip -qq "$1" -d "$W/rz"
  sed -i.bak "$3" "$W/rz/installer/release.conf"; rm -f "$W/rz/installer/release.conf.bak"
  [ -z "${4:-}" ] || { mkdir -p "$W/rz/installer/$(dirname "$4")"; echo x > "$W/rz/installer/$4"; }
  mkdir -p "$(dirname "$2")"; rm -f "$2"; (cd "$W/rz" && zip -qrX "$2" installer)
}
rezip "$Z" "$W/bad1/lumen-os-play6-1.0.7-kst1-installer.zip" 's/^DEVICE=play6$/DEVICE=z9x/'
expect check_wrong_device 1 'DEVICE=z9x, the image .* is for play6' bash "$PACK" --check "$W/bad1/lumen-os-play6-1.0.7-kst1-installer.zip" "$KST"
rezip "$Z" "$W/bad2/lumen-os-play6-1.0.7-kst1-installer.zip" 's/^DEVICE=play6$//'
expect check_no_device 1 'DEVICE=, the image' bash "$PACK" --check "$W/bad2/lumen-os-play6-1.0.7-kst1-installer.zip" "$KST"
rezip "$Z" "$W/bad3/lumen-os-play6-1.0.7-kst1-installer.zip" 's/^VERSION=1.0.7-kst1$/VERSION=1.0.7-kst1\r/'
expect check_crlf_conf_ok 0 'OK' bash "$PACK" --check "$W/bad3/lumen-os-play6-1.0.7-kst1-installer.zip" "$KST"
rezip "$Z" "$W/bad4/lumen-os-play6-1.0.7-kst1-installer.zip" 's/^x$//' tools/platform-tools/adb
expect check_google_tools 1 'only installer/tools/erofs-utils' bash "$PACK" --check "$W/bad4/lumen-os-play6-1.0.7-kst1-installer.zip" "$KST"

# image side refusals
mkdir -p "$W/osig"; cp "$KST" "$W/osig/"; sign "$W/osig" "$W/o.key"
expect pack_other_key 1 'does not verify' bash "$PACK" "$W/osig/$(basename "$KST")" "$W/out4" --installer "$SRC"
mkdir -p "$W/hash"; cp "$KST" "$W/hash/"; sign "$W/hash"; printf 'x' | dd of="$W/hash/$(basename "$KST")" bs=1 seek=2000000 conv=notrunc 2>/dev/null
expect pack_bad_hash 1 'does not match its SHA256SUMS' bash "$PACK" "$W/hash/$(basename "$KST")" "$W/out4" --installer "$SRC"
mkdir -p "$W/nosig"; cp "$KST" "$W/nosig/"; (cd "$W/nosig" && shasum -a 256 *.img > SHA256SUMS)
expect pack_no_sig 1 'no SHA256SUMS.sig' bash "$PACK" "$W/nosig/$(basename "$KST")" "$W/out4" --installer "$SRC"
mkdir -p "$W/tb"; cp "$T33" "$W/tb/lumen-os-play6-play6-t33-nogms-system.img"
expect pack_test_build_name 1 'is not a version' bash "$PACK" "$W/tb/lumen-os-play6-play6-t33-nogms-system.img" "$W/out4" --installer "$SRC"
mkdir -p "$W/tb2"; "$D" -R "cat /system/product/etc/build.prop" "$W/sf_plain.img" 2>/dev/null > "$W/p.prop"
cp "$T33" "$W/tb2/lumen-os-play6-1.0.7-nogms-system.img"
sed -e 's/^ro.lumen.version=.*/ro.lumen.version=1.0.7/' "$R/system/product/etc/build.prop" > "$W/tb.prop"
"$D" -w -f - "$W/tb2/lumen-os-play6-1.0.7-nogms-system.img" >/dev/null 2>&1 <<EOF
cd /system/product/etc
rm build.prop
write $W/tb.prop build.prop
EOF
sign "$W/tb2"
expect pack_adb_open 1 'test build \(ro.adb.secure=.0.\)' bash "$PACK" "$W/tb2/lumen-os-play6-1.0.7-nogms-system.img" "$W/out4" --installer "$SRC"
cp "$KST" "$W/pre/lumen-os-play6-1.0.7-kst1-PRIVATE-nogms-system.img"
expect pack_private 1 'private image' bash "$PACK" "$W/pre/lumen-os-play6-1.0.7-kst1-PRIVATE-nogms-system.img" "$W/out4" --installer "$SRC"
rm -f "$W/pre/lumen-os-play6-1.0.7-kst1-PRIVATE-nogms-system.img"
cp "$KST" "$W/pre/system_play6_kst1.img"
expect pack_not_release_name 1 'not named like a release image' bash "$PACK" "$W/pre/system_play6_kst1.img" "$W/out4" --installer "$SRC"
rm -f "$W/pre/system_play6_kst1.img"
check refusals_left_nothing '[ -z "$(ls "$W/out4" 2>/dev/null)" ]'

echo "pack_installer.sh (XGIMI Z9X)"
if command -v mkfs.erofs >/dev/null 2>&1 && command -v dump.erofs >/dev/null 2>&1; then
  ZR=$W/zroot; mkdir -p "$ZR/system/product/etc" "$W/z9x"
  printf '%s\n' ro.z9x.version=1.0.8 ro.lumen.version=1.0.8 > "$ZR/system/build.prop"
  printf '%s\n' ro.z9x.gms=0 > "$ZR/system/product/etc/build.prop"
  ZI=$W/z9x/lumen-os-1.0.8-nogms-system.img
  mkfs.erofs "$ZI" "$ZR" >/dev/null 2>&1 || { bad "mkfs.erofs cannot make the Z9X fixture"; }
  sign "$W/z9x"
  expect z9x_pack 0 'OK lumen-os-1.0.8-installer.zip' bash "$PACK" "$ZI" "$W/out5" --installer "$SRC"
  check z9x_conf '[ "$(zrc "$W/out5/lumen-os-1.0.8-installer.zip")" = "$(printf "BLOBS=partition\nVERSION=1.0.8")" ]'
  mkdir -p "$W/z9b"; cp "$ZI" "$W/z9b/lumen-os-1.0.8-rc1-nogms-system.img"; sign "$W/z9b"
  expect z9x_version_mismatch 1 'holds ro.z9x.version=1.0.8 but is named 1.0.8-rc1' bash "$PACK" "$W/z9b/lumen-os-1.0.8-rc1-nogms-system.img" "$W/out6" --installer "$SRC"
  expect z9x_zip_for_play6_image 1 'must be named lumen-os-play6-1.0.7-kst1-installer.zip' bash "$PACK" --check "$W/out5/lumen-os-1.0.8-installer.zip" "$KST"
  sed 's/^BLOBS=.*/BLOBS=embedded/' "$SRC/release.conf" > "$W/rc.emb"; cp -R "$SRC" "$W/src_emb"; cp "$W/rc.emb" "$W/src_emb/release.conf"
  expect z9x_private_blobs 1 'BLOBS=embedded' bash "$PACK" "$ZI" "$W/out6" --installer "$W/src_emb"
else
  echo "  skip Z9X cases (no mkfs.erofs / dump.erofs: brew install erofs-utils)"
fi

echo "installer rule (lumen-install.sh / .ps1, read only)"
SH=$G/installer/lumen-install.sh; PS=$G/installer/lumen-install.ps1
check rule_sh_name 'grep -qF "\"lumen-os-\$P_TOKEN-\"*\"-nogms-system.img\") nv=\${name#\"lumen-os-\$P_TOKEN-\"}; nv=\${nv%-nogms-system.img}" "$SH"'
check rule_sh_version 'grep -qF "[ \"\$nv\" = \"\$VERSION\" ]" "$SH" && grep -qF "[ \"\$v\" = \"\${VERSION:-}\" ]" "$SH"'
check rule_ps1_version 'grep -qF "\$nv -cne \$conf[\"VERSION\"]" "$PS" && grep -qF "\$v -cne \$conf[\"VERSION\"]" "$PS"'
check rule_ps1_name 'grep -qF "lumen-os-\$tok-<version>-nogms-system.img" "$PS"'

echo
echo "pack_installer: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
