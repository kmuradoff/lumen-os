#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of the Windows installer's FLOW=ab-fastbootd way (installer/lumen-install.ps1, the Abf-* functions), the
# twin of tools/devices/test/installer_hb.sh for lumen-install.sh: the same fake projector (its fake adb, fastboot and
# dump.erofs are taken out of installer_hb.sh, so both tests drive one and the same fake), the same cases and the
# same fb.log invariants, with the ps1's option names (-Ip, -NoBlobs, ...). The whole ps1 runs, from its parameters
# to Pt-Cleanup, with the fake tools as -PlatformTools (LUMEN_PLATFORM_TOOLS).
# Two cases are the ps1's own: -ErofsUtils DIR with a plain dump.erofs (pwsh on macOS or Linux), and reset with
# -Device in upper case.
#   bash tools/devices/test/installer_ps1_hb.sh
# Every value of the projector is read from lib/devices.conf, so this file names none of them. Needs PowerShell:
# pwsh on PATH, else docker with mcr.microsoft.com/powershell:lts-alpine-3.20 (one container for all cases, removed at
# the end). Without either it says 'skip'. What it cannot test: Windows PowerShell 5.1 itself, adb.exe and
# fastboot.exe on Windows, Windows paths and drivers.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir (and its container).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
INST=$G/installer
OTEST=$G/tools/ota/test
HB=$HERE/installer_hb.sh
READER=$G/tools/ota/image/z9x_blobs.sh
IMAGE=mcr.microsoft.com/powershell:lts-alpine-3.20
if command -v pwsh >/dev/null 2>&1; then RUN=local
elif command -v docker >/dev/null 2>&1 && docker image inspect "$IMAGE" >/dev/null 2>&1; then RUN=docker
else echo "skip installer_ps1_hb (no pwsh, no docker image $IMAGE)"; exit 0; fi
# docker: the temp dir under $HOME (the folder colima / Docker Desktop share), mounted at the same path
if [ "$RUN" = docker ]; then W=$(mktemp -d "$HOME/.lumen-ps1-hb.XXXXXX"); else W=$(mktemp -d "${TMPDIR:-/tmp}/lumen-ps1-hb.XXXXXX"); fi
W=$(cd "$W" && pwd) || exit 1
CT=""
cleanup() { [ -z "$CT" ] || docker rm -f "$CT" >/dev/null 2>&1; rm -rf "$W"; }
trap cleanup EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=ABFTESTSER01
ADDR=192.0.2.60:5555
CONF=$INST/lib/devices.conf
conf() { awk -v k="$1.$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2) }' "$CONF"; }
ID=$(sed -n 's/^\([a-z0-9]*\)\.FLOW=ab-fastbootd$/\1/p' "$CONF" | head -n 1)
[ -n "$ID" ] || { echo "lib/devices.conf has no FLOW=ab-fastbootd projector"; exit 1; }
NAME=$(conf "$ID" NAME); MODEL=$(conf "$ID" MODEL); BOARD=$(conf "$ID" BOARD); PRODUCT=$(conf "$ID" FB_PRODUCT)
FW=$(conf "$ID" VENDOR_OK | awk '{print $1}'); TOK=$(conf "$ID" IMAGE_TOKEN); CERT=$(conf "$ID" CERT)
SL=$(conf "$ID" SLOTS | awk '{print $1}'); KEEP=$(conf "$ID" KEEP); WIPEP=$(conf "$ID" WIPE_PARTS)
ALLOW=$(conf "$ID" BLOBS_ALLOW)
LPROP=$(conf "$ID" LUMEN_PROP | awk '{print $1}'); LVAL=$(conf "$ID" LUMEN_PROP | awk '{print $2}')
VPROP=$(conf "$ID" VERSION_PROP)
case "$SL" in a) OSL=b ;; *) OSL=a ;; esac
VER=9.9.9-test
IMGN=lumen-os-$TOK-$VER-nogms-system.img
BLOBN=$ID-blobs-$SER.img
P6NAME=$(conf play6 NAME); P6MODEL=$(conf play6 MODEL); P6BOARD=$(conf play6 BOARD)
Z9MODEL=$(conf z9x MODEL); Z9BOARD=$(conf z9x BOARD)

mkdir -p "$W/bin" "$W/keys" "$W/files" "$W/dbin" "$W/nomke2fs"
for k in k o; do
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/$k.key" -out "$W/keys/$k.pem" -subj /CN=lumen-test-$k -days 2 >/dev/null 2>&1 \
    || { echo "openssl cannot make a test key"; exit 1; }
done
python3 "$OTEST/blobs_fixture.py" allow "$INST/$ALLOW" "$W/allow.txt" "$W/files" || exit 1
# the fake projector of installer_hb.sh: its adb, fastboot and dump.erofs, exactly as written there
fake() { awk -v h="cat > \"\$W/bin/$1\" <<'EOF'" '$0 == h { on = 1; next } on && $0 == "EOF" { exit } on { print }' "$HB"; }
for f in adb fastboot dump.erofs; do fake "$f" > "$W/bin/$f"; [ -s "$W/bin/$f" ] || { echo "no fake $f in $HB"; exit 1; }; done
printf '#!/bin/sh\nexit 0\n' > "$W/bin/mke2fs"; cp "$W/bin/mke2fs" "$W/bin/make_f2fs"
chmod 755 "$W/bin/"*
cp "$W/bin/adb" "$W/bin/fastboot" "$W/bin/make_f2fs" "$W/nomke2fs/"
for f in getprop setprop log timeout mount chcon chown; do cp "$OTEST/stubs/$f" "$W/dbin/$f" && chmod 755 "$W/dbin/$f"; done
if [ "$RUN" = docker ]; then
  CT=lumen-ps1-hb-$$
  docker run -d --name "$CT" -v "$W":"$W" "$IMAGE" sleep 86400 >/dev/null 2>&1 || { echo "docker run failed"; CT=""; exit 1; }
  CPATH=$(docker exec "$CT" sh -c 'echo $PATH')
fi

PROPS_STOCK="ro.boot.xgimi.modelname=$MODEL
ro.boot.hardware=$BOARD
ro.vendor.build.version.incremental=$FW
ro.serialno=$SER
ro.boot.slot_suffix=_$SL
ro.virtual_ab.enabled=true
ro.build.type=user
ro.adb.secure=0
sys.boot_completed=1"
PROPS_LUMEN="$PROPS_STOCK
$LPROP=$LVAL
$VPROP=$VER
sys.z9x.blobs=ok"

mkimg() {  # file [magic]: a 1 MiB image with the erofs magic (or another file system's)
  dd if=/dev/zero of="$1" bs=4096 count=256 2>/dev/null
  case "${2:-erofs}" in
    erofs) printf '\342\341\365\340' | dd of="$1" bs=1 seek=1024 conv=notrunc 2>/dev/null ;;
    ext4) printf '\123\357' | dd of="$1" bs=1 seek=1080 conv=notrunc 2>/dev/null ;;
    sparse) printf '\072\377\046\355' | dd of="$1" bs=1 seek=0 conv=notrunc 2>/dev/null ;;
  esac
}
sign() {  # dir key: SHA256SUMS of the images in dir, signed with the key
  (cd "$1" && for f in lumen-os-*-system.img; do { sha256sum "$f" 2>/dev/null || shasum -a 256 "$f"; }; done > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/$2.key" -out SHA256SUMS.sig SHA256SUMS)
}
dev_stock() {  # the projector's files: every allow-list source; a from= line has only the stock original there
  awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $2, $3, ($0 ~ / from=/) }' "$W/allow.txt" | while read -r rel src fr; do
    mkdir -p "$(dirname "$DEV$src")"
    if [ "$fr" = 1 ]; then cp "$W/files/$rel.stock" "$DEV$src"; else cp "$W/files/$rel" "$DEV$src"; fi
  done
}
begin() {
  CASE=$1; CFAIL=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst/$(dirname "$CERT")" "$C/fb" "$C/state" "$C/backup" "$C/dev"
  FB=$C/fb; DEV=$C/dev
  cp "$INST/lumen-install.ps1" "$C/inst/"; cp -R "$INST/lib" "$C/inst/"
  cp "$W/allow.txt" "$C/inst/$ALLOW"
  cp "$INST/certs/ota.x509.pem" "$C/inst/certs/"
  cp "$W/keys/k.pem" "$C/inst/$CERT"
  printf 'DEVICE=%s\nVERSION=%s\n' "$ID" "$VER" > "$C/inst/release.conf"
  mkimg "$C/inst/$IMGN"; sign "$C/inst" k
  printf '%s\n' "$PROPS_STOCK" > "$FB/props.stock"; printf '%s\n' "$PROPS_LUMEN" > "$FB/props.lumen"
  printf '%s\n' control "system_$SL" "vendor_$SL" "product_$SL" "system_ext_$SL" > "$FB/mapper.stock"
  cp "$FB/mapper.stock" "$FB/mapper.lumen"
  { echo "(bootloader) is-userspace:yes"; echo "(bootloader) product:$PRODUCT"; echo "(bootloader) serialno:$SER"
    echo "(bootloader) unlocked:yes"; echo "(bootloader) current-slot:$SL"; echo "(bootloader) slot-count:2"
    echo "(bootloader) slot-successful:$SL:yes"; echo "(bootloader) slot-unbootable:$OSL:no"
    echo "(bootloader) snapshot-update-status:none"; echo "(bootloader) super-partition-name:super"
    echo "(bootloader) max-download-size:0x10000000"; echo "(bootloader) partition-size:super:0xCE800000"
    echo "(bootloader) partition-type:userdata:raw"; echo "(bootloader) partition-type:metadata:raw"
    echo "(bootloader) partition-size:userdata:0x6C0000000"; echo "(bootloader) partition-size:metadata:0x1000000"
  } > "$FB/vars"
  printf '%s\n' "system_$SL 688357376" "vendor_$SL 1369018368" "product_$SL 59363328" "system_ext_$SL 3317760" \
    "system_$OSL 0" "vendor_$OSL 0" "product_$OSL 0" "system_ext_$OSL 0" > "$FB/parts"
  echo stock > "$FB/mode"; echo "$ADDR" > "$FB/addr"
  : > "$FB/connected"; : > "$FB/adb.log"; : > "$FB/fb.log"
}
inst() {  # answers args... -> the ps1 copy; exit status in RC, output in $C/out
  local ans=$1; shift
  local pt=${TPT:-$W/bin}
  if [ "$RUN" = local ]; then
    printf '%b' "$ans" | env LUMEN_PLATFORM_TOOLS="$pt" PATH="$W/bin:$PATH" FB="$FB" DEV="$DEV" FB_SERIAL=$SER \
      LUMEN_STATE_DIR="$C/state" LUMEN_BACKUP_DIR="$C/backup" LUMEN_TIMEOUT_MAX=60 \
      pwsh -NoProfile -NonInteractive -File "$C/inst/lumen-install.ps1" "$@" > "$C/out" 2>&1
  else
    printf '%b' "$ans" | docker exec -i -e LUMEN_PLATFORM_TOOLS="$pt" -e PATH="$W/bin:$CPATH" -e FB="$FB" -e DEV="$DEV" \
      -e FB_SERIAL=$SER -e LUMEN_STATE_DIR="$C/state" -e LUMEN_BACKUP_DIR="$C/backup" -e LUMEN_TIMEOUT_MAX=60 "$CT" \
      pwsh -NoProfile -File "$C/inst/lumen-install.ps1" "$@" > "$C/out" 2>&1
  fi
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -qF -- "$1" "$C/out" || fail "output lacks '$1'"; }
expect_no_out() { if grep -qF -- "$1" "$C/out"; then fail "output has '$1'"; fi; }
cmds() { grep -v -e '^--version$' -e '^devices$' "$FB/fb.log" | sed "s|$C/||g" | tr '\n' ';'; }
expect_cmds() { [ "$(cmds)" = "$1" ] || fail "fastboot commands: got '$(cmds)', want '$1'"; }
st() { sed -n "s/^$1=//p" "$C/state/$ID/state" 2>/dev/null | tr -d '\r\357\273\277' | tail -1; }
setp() { sed -i.bak "s|^$2=.*|$2=$3|" "$FB/props.$1"; }
adb_talked() { grep -qvx version "$FB/adb.log"; }   # anything but the version check of the Platform-Tools
adb_writes() { grep -E ' (reboot|root|push|install|uninstall|am)( |$)| shell (echo|rm|setprop|svc) ' "$FB/adb.log" | tr '\n' ';'; }
F="-s $SER"
FLASH_FULL="$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;$F flash system_$SL inst/$IMGN;$F getvar partition-size:system_$SL;"
for p in $WIPEP; do FLASH_FULL="$FLASH_FULL$F format:ext4 $p;"; done
FLASH_FULL="$FLASH_FULL$F reboot;"
end() {
  [ ! -s "$FB/unexpected" ] || fail "unexpected: $(cat "$FB/unexpected")"
  # the never list, and every fastboot command one of the allowed ones (the same invariants as installer_hb.sh)
  if grep -qE '(^| )(-w|erase|vbmeta|--slot|--slot=.*|set_active|--set-active|delete-logical-partition|snapshot-update|flashall|update|wipe-super|flashing|oem|boot)( |:|$)' "$FB/fb.log"; then
    fail "forbidden fastboot command: $(tr '\n' ';' < "$FB/fb.log")"
  fi
  grep -qE "_$OSL( |$)" "$FB/fb.log" && fail "a partition of the other slot: $(tr '\n' ';' < "$FB/fb.log")"
  local allowed="^(--version|devices|-s $SER (getvar all|getvar partition-size:system_$SL|reboot|create-logical-partition z9x_blobs_$SL 4194304|resize-logical-partition z9x_blobs_$SL 4194304|flash z9x_blobs_$SL [^ ]+|flash system_$SL [^ ]+|format:ext4 ($(echo $WIPEP | tr ' ' '|'))))$"
  if grep -vqE "$allowed" "$FB/fb.log"; then fail "fastboot command not on the allowed list: $(grep -vE "$allowed" "$FB/fb.log" | head -3 | tr '\n' ';')"; fi
  grep -E '(^| )format' "$FB/fb.log" | grep -vqE ' format:ext4 ' && fail "a format without :ext4"
  case "$(adb_writes)" in ''|"-s $ADDR reboot fastboot;") ;; *) fail "adb wrote: $(adb_writes)" ;; esac
  grep -qF "$EMDASH" "$C/out" && fail "an em dash in the output"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -14; fi
}
roundtrip() {  # the z9x_blobs partition the ps1 flashed (Write-Ustar), read by the real z9x_blobs.sh: every file bound
  local R=$C/root T=$C/dt img=${1:-$FB/z9x_blobs_$SL.img}
  [ -f "$img" ] || { fail "no $img"; return; }
  [ "$(wc -c < "$img" | tr -d ' ')" = 4194304 ] || fail "blobs image is not 4 MiB"
  mkdir -p "$R/system/etc/z9x" "$R/dev/block/mapper" "$R/mnt" "$T"
  cp "$W/allow.txt" "$R/system/etc/z9x/blobs_allow.txt"; cp "$img" "$R/dev/block/mapper/z9x_blobs_$SL"
  awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { t = "/system/" $2; for (i = 4; i <= NF; i++) if ($i ~ /^bind=/) t = substr($i, 6); print $1, t }' \
    "$W/allow.txt" > "$T/want"
  while read -r h t; do mkdir -p "$(dirname "$R$t")"; case "$t" in /system/*) : > "$R$t" ;; *) echo own > "$R$t" ;; esac; done < "$T/want"
  echo "ro.boot.slot_suffix=_$SL" > "$T/props"; : > "$T/binds"
  env Z9X_ROOT="$R" Z9X_T="$T" PATH="$W/dbin:$PATH" sh "$READER" > "$T/out" 2>&1
  [ ! -s "$T/out" ] || fail "z9x_blobs.sh output: $(cat "$T/out")"
  grep -qx 'sys.z9x.blobs=ok' "$T/props" || fail "round trip: $(grep sys.z9x.blobs "$T/props") $(cat "$T/logcat" 2>/dev/null)"
  while read -r h t; do grep -qx "$h $R$t" "$T/binds" || fail "round trip: $t not bound with its file"; done < "$T/want"
}
NB=$(awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3' "$W/allow.txt" | wc -l | tr -d ' ')
SET=$(sed -n 's/^set=//p' "$W/allow.txt")
EMDASH=$(printf '\342\200\224')

# ---------------------------------------------------------------- check
begin check_stock
inst '' -Ip "$ADDR" check
expect_rc 0; expect_out "$NAME $SER ($ADDR), firmware $FW, slot $SL, system: XGIMI stock"
expect_out "vbmeta: verification is off on slot $SL (no *-verity)"; expect_out "SHA256SUMS signature: Lumen OS key for the $NAME"
expect_out "dump.erofs could not read the image content (not needed: the signed SHA256SUMS lists this file as Lumen OS $VER"
expect_out "check passed; nothing was written"
[ "$(st step_check)" = done ] && [ "$(st serial)" = "$SER" ] && [ "$(st ip)" = "$ADDR" ] && [ "$(st slot)" = "_$SL" ] || fail "state"
expect_cmds ""
end

begin check_lumen
echo lumen > "$FB/mode"; inst '' -Ip "$ADDR" check
expect_rc 0; expect_out "system: Lumen OS $VER"
end

begin check_mdns_address
printf 'adb-%s-abc123\t_adb._tcp\t%s\n' "$SER" "$ADDR" > "$FB/mdns"
inst 'yes\n' check
expect_rc 0; expect_out "A projector answers at $ADDR"; expect_out "check passed"
end

begin check_no_address
inst 'no\n' check
expect_rc 1; expect_out "give the projector's network address: lumen-install.cmd -Ip 192.168.x.y"
end

begin check_z9x_refused
setp stock ro.boot.xgimi.modelname "$Z9MODEL"; setp stock ro.boot.hardware "$Z9BOARD"; inst '' -Device "$ID" -Ip "$ADDR" check
expect_rc 1; expect_out "this is not an $NAME (model '$Z9MODEL', board '$Z9BOARD'). Nothing was done. It is an XGIMI Z9X"
end

begin check_play6_refused
setp stock ro.boot.xgimi.modelname "$P6MODEL"; setp stock ro.boot.hardware "$P6BOARD"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "this is not an $NAME (model '$P6MODEL', board '$P6BOARD'). Nothing was done. It is an $P6NAME"
end

begin check_unknown_refused
setp stock ro.boot.xgimi.modelname G0099; setp stock ro.boot.hardware mt9999; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "this is not an $NAME (model 'G0099', board 'mt9999'). Nothing was done."
end

begin check_firmware_refused
setp stock ro.vendor.build.version.incremental v0.0.1; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "XGIMI firmware v0.0.1 is not supported: Lumen OS for the $NAME is made for V${FW#v} only"
end

begin check_slot_refused
setp stock ro.boot.slot_suffix "_$OSL"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "the $NAME runs from slot $OSL: Lumen OS installs only into slot $SL"
end

begin check_no_virtual_ab_refused
setp stock ro.virtual_ab.enabled false; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "this projector has no virtual A/B"
end

begin check_lumen_other_device_refused
echo lumen > "$FB/mode"; setp lumen "$LPROP" otherdevice; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "this projector runs Lumen OS for another device ('otherdevice')"
end

begin check_vbmeta_needed
printf '%s\n' system-verity vendor-verity >> "$FB/mapper.stock"; inst '' -Ip "$ADDR" check
expect_rc 0; expect_out "vbmeta: slot $SL still verifies its system (*-verity): the vbmeta step is needed before 'flash'"
expect_out "check passed"; [ -z "$(st step_vbmeta)" ] || fail "step_vbmeta set"
end

begin check_unauthorized_once
echo 1 > "$FB/auth.stock"; inst '' -Ip "$ADDR" check
expect_rc 0; expect_out "allow debugging from this computer"
end

begin check_no_mke2fs
TPT=$W/nomke2fs inst '' -Ip "$ADDR" check; TPT=""
expect_rc 1; expect_out "mke2fs missing next to fastboot"
! adb_talked || fail "adb used: $(cat "$FB/adb.log")"
end

# ---------------------------------------------------------------- the image
begin image_other_release_refused
mv "$C/inst/$IMGN" "$C/inst/lumen-os-$TOK-0.0.1-nogms-system.img"; sign "$C/inst" k; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is Lumen OS 0.0.1, but this installer download is for Lumen OS $VER"
end

begin image_not_release_name_refused
mv "$C/inst/$IMGN" "$C/inst/lumen-os-$TOK-$VER-system.img"; sign "$C/inst" k; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is not a release file"
end

begin image_ext4_refused
mkimg "$C/inst/$IMGN" ext4; sign "$C/inst" k; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is an ext4 image (another projector's), not one for the $NAME"
end

begin image_sparse_refused
mkimg "$C/inst/$IMGN" sparse; sign "$C/inst" k; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is a sparse image, not the release file"
end

begin image_too_big_refused
sed -i.bak "s/^$ID\.SYSTEM_MAX=.*/$ID.SYSTEM_MAX=4096/" "$C/inst/lib/devices.conf"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is too big for the $NAME"
end

begin image_too_big_with_blobs_refused
# with blobs the budget is SYSTEM_MAX less z9x_blobs (4 MiB) and SUPER_RESERVE: what the write's room check refuses
dd if=/dev/zero of="$C/inst/$IMGN" bs=4096 count=768 2>/dev/null
printf '\342\341\365\340' | dd of="$C/inst/$IMGN" bs=1 seek=1024 conv=notrunc 2>/dev/null; sign "$C/inst" k
res=$(conf "$ID" SUPER_RESERVE)
sed -i.bak "s/^$ID\.SYSTEM_MAX=.*/$ID.SYSTEM_MAX=$((2097152 + 4194304 + res))/" "$C/inst/lib/devices.conf"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is too big for the $NAME (3 MB, at most 2 MB fit"
inst '' -Ip "$ADDR" -NoBlobs check
expect_rc 0; expect_out "check passed"
end

begin image_other_key_refused
sign "$C/inst" o; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "SHA256SUMS is not signed with the Lumen OS key for the $NAME"
end

begin image_damaged_refused
printf 'x' | dd of="$C/inst/$IMGN" bs=1 seek=8192 conv=notrunc 2>/dev/null; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "checksum mismatch"
end

begin image_unsigned
rm -f "$C/inst/SHA256SUMS" "$C/inst/SHA256SUMS.sig"; inst 'UNSIGNED\n' -Ip "$ADDR" -UnsignedImage check
expect_rc 0; expect_out "-UnsignedImage: this image is NOT authenticated"; expect_out "checksum not checked"
end

image_content() {  # props-lines...: the fake image's product build.prop
  mkdir -p "$FB/erofs/system/product/etc"; printf '%s\n' "$@" > "$FB/erofs/system/product/etc/build.prop"
}
begin image_content_ok
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"; inst '' -Ip "$ADDR" check
expect_rc 0; expect_out "image: Lumen OS $VER for the $NAME"
end

begin image_content_test_build_refused
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=0"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is a test build (ro.adb.secure='0'), not a release"
end

begin image_content_other_device_refused
image_content "$LPROP=otherdevice" "$VPROP=$VER" "ro.adb.secure=1"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "is not Lumen OS for the $NAME ($LPROP='otherdevice')"
end

begin image_content_version_refused
image_content "$LPROP=$LVAL" "$VPROP=0.0.1" "ro.adb.secure=1"; inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "holds Lumen OS '0.0.1', the release is $VER"
end

begin image_content_private_refused
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"
rel=$(awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $2; exit }' "$W/allow.txt")
mkdir -p "$(dirname "$FB/erofs/system/$rel")"; echo inside > "$FB/erofs/system/$rel"
inst '' -Ip "$ADDR" check
expect_rc 1; expect_out "carries the MediaTek / XGIMI files itself (a private build): run with -Blobs embedded"
end

begin image_content_public_embedded_refused
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"; inst '' -Ip "$ADDR" -Blobs embedded check
expect_rc 1; expect_out "is a public image: its MediaTek / XGIMI files come from your projector"
end

begin image_content_erofs_utils_plain
# pwsh on macOS or Linux: -ErofsUtils DIR with a plain dump.erofs (not dump.erofs.exe) reads the image
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"
mkdir -p "$C/eu"; { echo '#!/bin/sh'; echo 'touch "$FB/eu_used"'; sed 1d "$W/bin/dump.erofs"; } > "$C/eu/dump.erofs"; chmod 755 "$C/eu/dump.erofs"
inst '' -Ip "$ADDR" -ErofsUtils "$C/eu" check
expect_rc 0; expect_out "image: Lumen OS $VER for the $NAME"
[ -e "$FB/eu_used" ] || fail "the dump.erofs of -ErofsUtils was not used"
end

# ---------------------------------------------------------------- vbmeta (shown, never run)
begin vbmeta_shown
inst 'yes\n' vbmeta
expect_rc 0; expect_out "ONE MANUAL STEP: vbmeta"; expect_out "fastboot --disable-verity --disable-verification --slot=all flash vbmeta vbmeta.img"
expect_out "adb -s ADDRESS:5555 reboot fastboot"; expect_out "Writing 'vbmeta_a' OKAY and Writing 'vbmeta_b' OKAY"
[ "$(st step_vbmeta)" = done ] || fail "step_vbmeta"
[ ! -s "$FB/adb.log" ] && [ ! -s "$FB/fb.log" ] || fail "adb or fastboot used"
end

begin vbmeta_not_done
inst 'no\n' vbmeta
expect_rc 1; expect_out "do the vbmeta step first"
end

# ---------------------------------------------------------------- blobs
begin blobs_from_stock
dev_stock; inst '' -Ip "$ADDR" blobs
expect_rc 0; expect_out "$NB MediaTek / XGIMI files from your projector, set $SET -> $C/backup/$BLOBN"
[ "$(st step_blobs)" = done ] && [ "$(st blobs_img)" = "$C/backup/$BLOBN" ] || fail "state"
[ -f "$C/state/$ID/blobs/z9x_blobs.img" ] || fail "work dir"
roundtrip "$C/backup/$BLOBN"
end

begin blobs_system_fallback
awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $2, $3 }' "$W/allow.txt" | while read -r rel src; do
  mkdir -p "$(dirname "$DEV$src")" "$(dirname "$DEV/system/$rel")"; echo "AOSP build" > "$DEV$src"; cp "$W/files/$rel" "$DEV/system/$rel"
done
inst '' -Ip "$ADDR" blobs
expect_rc 0; roundtrip "$C/backup/$BLOBN"
end

begin blobs_wrong_hash_refused
dev_stock; src=$(awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $3; exit }' "$W/allow.txt"); echo "other firmware" > "$DEV$src"
inst '' -Ip "$ADDR" blobs
expect_rc 1; expect_out "from the projector does not match the allow-list"; expect_out "NO SOUND"
[ ! -f "$C/backup/$BLOBN" ] || fail "a blobs image was written"
end

begin blobs_no_blobs
dev_stock; inst '' -Ip "$ADDR" -NoBlobs blobs
expect_rc 0; expect_out "-NoBlobs: the MediaTek / XGIMI files are skipped"; [ "$(st step_blobs)" = skip ] || fail "state"
! adb_talked || fail "adb used"
end

# ---------------------------------------------------------------- flash
begin flash_full
dev_stock; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 0; expect_cmds "$FLASH_FULL"
expect_out "Type ERASE to continue"; expect_out "Press Enter when the cable is in"; expect_out "fastboot mode: $NAME $SER, slot $SL"
expect_out "If it hangs with the cable in, pull the cable"
[ "$(st step_flash)" = done ] && [ "$(st step_wipe)" = done ] && [ "$(st step_system)" = done ] || fail "state"
for p in $WIPEP; do [ -e "$FB/formatted_$p" ] || fail "$p not formatted"; done
[ "$(cat "$FB/mode")" = lumen ] || fail "mode $(cat "$FB/mode")"
roundtrip
end

begin flash_vbmeta_not_effective
dev_stock; printf '%s\n' system-verity >> "$FB/mapper.stock"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "still verifies its system (*-verity in /dev/block/mapper)"; expect_out "Nothing was written."
expect_cmds ""; expect_no_out "Type ERASE"
end

begin flash_mapper_unreadable
dev_stock; : > "$FB/mapper.stock"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "could not read /dev/block/mapper"; expect_cmds ""
end

fbd_refusal() {  # case vars-sed expected
  begin "$1"
  dev_stock; [ -z "$2" ] || sed -i.bak "$2" "$FB/vars"
  inst 'ERASE\n\n' -Ip "$ADDR" flash
  expect_rc 1; expect_out "fastboot mode check failed: $3"; expect_out "Nothing was written. The projector starts its old system."
  expect_cmds "$F getvar all;$F reboot;"
  [ "$(cat "$FB/mode")" = stock ] || fail "mode $(cat "$FB/mode")"
  end
}
fbd_refusal flash_not_userspace 's/is-userspace:yes/is-userspace:no/' "not fastbootd"
fbd_refusal flash_other_product "s/product:$PRODUCT/product:other/" "fastbootd is not the one of the $NAME (other)"
fbd_refusal flash_other_serial "s/serialno:$SER/serialno:OTHER/" "another projector in fastboot mode (serialno OTHER, expected $SER)"
fbd_refusal flash_locked 's/unlocked:yes/unlocked:no/' "the bootloader is locked"
fbd_refusal flash_slot_other "s/current-slot:$SL/current-slot:$OSL/" "current-slot '$OSL'"
fbd_refusal flash_slot_count 's/slot-count:2/slot-count:1/' "slot-count '1'"
fbd_refusal flash_slot_unsuccessful "s/slot-successful:$SL:yes/slot-successful:$SL:no/" "slot-successful:$SL 'no'"
fbd_refusal flash_snapshot_pending 's/snapshot-update-status:none/snapshot-update-status:snapshotted/' "an XGIMI update is staged or not finished (snapshot-update-status snapshotted)"
fbd_refusal flash_super_name 's/super-partition-name:super/super-partition-name:other/' "super-partition-name 'other'"

begin flash_fbd_refused_then_manual_fastboot
# a refused fastbootd check ends that visit: fastboot mode entered by hand later is not taken for it (no write,
# no skipped vbmeta check: the default command needs the system running and stops)
dev_stock; sed -i.bak 's/snapshot-update-status:none/snapshot-update-status:snapshotted/' "$FB/vars"
inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "Nothing was written. The projector starts its old system."
[ -z "$(st step_fastboot)" ] || fail "step_fastboot kept after the restart"
mv "$FB/vars.bak" "$FB/vars"; echo fastboot > "$FB/mode"; : > "$FB/fb.log"; : > "$FB/adb.log"
inst 'no\nERASE\n\n'
expect_rc 1; expect_out "Nothing was done."; expect_no_out "continuing there"; expect_no_out "Type ERASE"
expect_cmds ""; [ "$(cat "$FB/mode")" = fastboot ] || fail "mode $(cat "$FB/mode")"
end

begin flash_cow_present
dev_stock; echo "system_${SL}-cow 1048576" >> "$FB/parts"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "fastboot mode check failed: *-cow partitions"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_keep_missing
dev_stock; k=$(echo "$KEEP" | awk '{print $NF}'); sed -i.bak "/^${k}_$SL /d" "$FB/parts"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "fastboot mode check failed: the partition ${k}_$SL is missing"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_system_not_logical
dev_stock; sed -i.bak "/^system_$SL /d" "$FB/parts"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "fastboot mode check failed: is-logical:system_$SL"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_no_room
dev_stock; sed -i.bak 's/partition-size:super:0xCE800000/partition-size:super:0x7E600000/' "$FB/vars"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "no room in super for z9x_blobs_$SL"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_cancelled
dev_stock; inst 'no\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "cancelled, nothing was written"; expect_cmds ""
grep -q 'reboot' "$FB/adb.log" && fail "adb reboot sent"
end

begin flash_reboot_refused
dev_stock; touch "$FB/reboot_fail"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "adb reboot fastboot failed: the projector still runs its system. Nothing was written."; expect_cmds ""
[ -z "$(st step_fastboot)" ] || fail "step_fastboot kept"
end

begin flash_blobs_existing
dev_stock; echo "z9x_blobs_$SL 4194304" >> "$FB/parts"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 0
expect_cmds "$(printf '%s' "$FLASH_FULL" | sed "s|create-logical-partition|resize-logical-partition|")"
roundtrip
end

begin flash_no_blobs
inst 'ERASE\n\n' -Ip "$ADDR" -NoBlobs flash
expect_rc 0; expect_no_out "z9x_blobs_$SL"
expect_cmds "$(printf '%s' "$FLASH_FULL" | sed "s|$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;||")"
end

begin flash_blobs_create_fails
dev_stock; touch "$FB/create_fail"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "the partition z9x_blobs_$SL could not be made. Nothing was written."
expect_cmds "$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F reboot;"
end

begin flash_blobs_write_fails
dev_stock; touch "$FB/flash_fail_z9x_blobs_$SL"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "z9x_blobs_$SL was not written; system was not touched. Nothing was written."
expect_cmds "$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;$F reboot;"
end

begin flash_system_fails_then_resumed
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "system was not written completely. The projector stays in fastboot mode"
expect_cmds "$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;$F flash system_$SL inst/$IMGN;"
[ "$(cat "$FB/mode")" = fastboot ] && [ "$(st step_system)" = started ] || fail "not left in fastboot mode"
rm -f "$FB/flash_fail_system_$SL"; : > "$FB/fb.log"; : > "$FB/adb.log"; inst 'ERASE\n' flash
expect_rc 0; expect_out "the projector waits in fastboot mode on the cable: continuing there"
expect_cmds "$(printf '%s' "$FLASH_FULL" | sed "s|$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;||")"
! adb_talked || fail "adb used: $(cat "$FB/adb.log")"
roundtrip
end

begin flash_resumed_cancel_stays
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' -Ip "$ADDR" flash
: > "$FB/fb.log"; inst 'no\n' flash
expect_rc 1; expect_out "The write of this install is not finished: the projector stays in fastboot mode."
expect_cmds ""; [ "$(cat "$FB/mode")" = fastboot ] || fail "restarted"
end

begin flash_resumed_blobs_file_gone
# z9x_blobs written in that visit: the resumed run does not need the blobs file again
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' -Ip "$ADDR" flash
[ "$(st step_zblobs)" = done ] && [ "$(st step_system)" = started ] || fail "state after the first run"
rm -f "$FB/flash_fail_system_$SL" "$C/backup/$BLOBN"; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 0; expect_no_out "is missing"
w="$F getvar all;$F flash system_$SL inst/$IMGN;$F getvar partition-size:system_$SL;"; for p in $WIPEP; do w="$w$F format:ext4 $p;"; done
expect_cmds "$w$F reboot;"
roundtrip
end

begin flash_resumed_image_refused_stays
# system begun: an image STOP of the resumed run keeps the projector in fastboot mode and never says nothing was written
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' -Ip "$ADDR" flash
mkimg "$C/inst/$IMGN" ext4; sign "$C/inst" k; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 1; expect_out "is an ext4 image (another projector's), not one for the $NAME."
expect_out "The write of this install is not finished: the projector stays in fastboot mode. Keep the cable in and run the installer again."
expect_no_out "Nothing was written"; expect_cmds ""; [ "$(cat "$FB/mode")" = fastboot ] || fail "restarted"
end

begin flash_resumed_image_refused_nothing_written
# a visit in which nothing was written yet: an image STOP sends the projector back to its old system
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' -Ip "$ADDR" flash
sed -i.bak -e 's/^step_system=.*/step_system=/' -e 's/^step_zblobs=.*/step_zblobs=/' "$C/state/$ID/state"
mkimg "$C/inst/$IMGN" ext4; sign "$C/inst" k; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 1; expect_out "is an ext4 image (another projector's), not one for the $NAME. Nothing was written. The projector starts its old system."
expect_cmds "$F reboot;"; [ -z "$(st step_fastboot)" ] || fail "step_fastboot kept"
end

begin flash_resumed_blobs_needed_no_blobs_named
# system begun without z9x_blobs (-NoBlobs), the next run without -NoBlobs and without the file: -NoBlobs is the way out
inst 'ERASE\n\n' -Ip "$ADDR" -NoBlobs flash
touch "$FB/flash_fail_system_$SL"; : > "$FB/fb.log"
sed -i.bak -e 's/^step_flash=.*/step_flash=/' -e 's/^step_wipe=.*/step_wipe=/' -e 's/^step_system=.*/step_system=started/' "$C/state/$ID/state"
echo fastboot > "$FB/mode"; inst 'ERASE\n' flash
expect_rc 1; expect_out "is missing. To finish without them (no sound, no MediaTek video decoders), run the installer again with -NoBlobs."
expect_out "The write of this install is not finished"; expect_no_out "Nothing was written"; expect_cmds ""
rm -f "$FB/flash_fail_system_$SL"; inst 'ERASE\n' -NoBlobs flash
expect_rc 0; expect_out "Written."
end

begin flash_size_not_taken
dev_stock; touch "$FB/no_resize"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "after the write system_$SL has 688357376 bytes, the image 1048576: the write did not take"
[ "$(cat "$FB/mode")" = fastboot ] || fail "restarted"
expect_no_out "wiping data"
end

begin flash_wipe_fails_then_resumed
dev_stock; p1=$(echo "$WIPEP" | awk '{print $1}'); touch "$FB/format_fail_$p1"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 1; expect_out "the wipe ($p1) failed. The projector stays in fastboot mode"
[ "$(cat "$FB/mode")" = fastboot ] && [ "$(st step_system)" = done ] || fail "state"
rm -f "$FB/format_fail_$p1"; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 0
w="$F getvar all;$F getvar partition-size:system_$SL;"; for p in $WIPEP; do w="$w$F format:ext4 $p;"; done
expect_cmds "$w$F reboot;"
end

begin flash_lumen_over_lumen
dev_stock; echo lumen > "$FB/mode"; inst 'ERASE\n\n' -Ip "$ADDR" flash
expect_rc 0; expect_out "system: Lumen OS $VER"; expect_cmds "$FLASH_FULL"
end

begin flash_ru
dev_stock; inst 'ERASE\n\n' -Ip "$ADDR" -Lang ru flash
expect_rc 0; expect_out "Введите ERASE, чтобы продолжить"; expect_out "Записано. Проектор загружает Lumen OS"
expect_cmds "$FLASH_FULL"
end

# ---------------------------------------------------------------- the whole install
begin all_full
dev_stock; inst 'no\nERASE\n\n' -Ip "$ADDR"
expect_rc 0; expect_out "check passed"; expect_out "Make a backup"; expect_no_out "ONE MANUAL STEP"
expect_out "$NB MediaTek / XGIMI files"; expect_cmds "$FLASH_FULL"; expect_out "lumen-install.cmd verify -Ip ADDRESS"
[ "$(st step_backup)" = skipped ] && [ "$(st step_flash)" = done ] || fail "state"
end

begin all_vbmeta_asked
dev_stock; printf '%s\n' system-verity >> "$FB/mapper.stock"; inst 'no\nno\n' -Ip "$ADDR"
expect_rc 1; expect_out "ONE MANUAL STEP: vbmeta"; expect_out "do the vbmeta step first"; expect_cmds ""
end

begin all_done_again
dev_stock; inst 'no\nERASE\n\n' -Ip "$ADDR"; : > "$FB/fb.log"; : > "$FB/adb.log"; inst '' -Ip "$ADDR"
expect_rc 0; expect_out "Done."; expect_cmds ""; ! adb_talked || fail "adb used"
end

# ---------------------------------------------------------------- verify
begin verify_ok
echo lumen > "$FB/mode"; inst '' -Ip "$ADDR" verify
expect_rc 0; expect_out "Lumen OS $VER on the $NAME $SER"; expect_out "MediaTek / XGIMI files (z9x_blobs"; expect_out "verify passed"
[ "$(st step_verify)" = done ] || fail "state"
end

begin verify_blobs_missing
echo lumen > "$FB/mode"; setp lumen sys.z9x.blobs missing; inst '' -Ip "$ADDR" verify
expect_rc 0; expect_out "sys.z9x.blobs=missing"; [ -z "$(st step_verify)" ] || fail "state"
end

begin verify_not_lumen
inst '' -Ip "$ADDR" verify
expect_rc 1; expect_out "this projector does not run Lumen OS for the $NAME"
end

begin verify_not_started
echo lumen > "$FB/mode"; setp lumen sys.boot_completed 0; inst '' -Ip "$ADDR" verify
expect_rc 1; expect_out "the system has not finished starting yet"
end

# ---------------------------------------------------------------- not there yet, refused options
for c in rescue gapps gsf stick; do
  begin "not_yet_$c"
  inst '' -Ip "$ADDR" "$c"
  expect_rc 1; expect_out "'$c' is not available for the $NAME yet. Nothing was done."
  [ ! -s "$FB/adb.log" ] || fail "adb used"
  end
done
begin keep_data_refused
inst '' -Ip "$ADDR" -KeepData flash
expect_rc 1; expect_out "'-KeepData' is not available for the $NAME yet"; ! adb_talked || fail "adb used"
end
begin wipe_factory_reset_refused
inst '' -Ip "$ADDR" -Wipe factory-reset check
expect_rc 1; expect_out "'-Wipe factory-reset' is not available for the $NAME yet"
end

# ---------------------------------------------------------------- which projector
begin device_ip_means_this_download
inst '' -Ip "$ADDR" check
expect_rc 0; expect_no_out "$P6NAME"
end

begin device_option_with_play6_download_refused
printf 'DEVICE=play6\nVERSION=%s\n' "$VER" > "$C/inst/release.conf"; inst '' -Device "$ID" -Ip "$ADDR" check
expect_rc 1; expect_out "this download of the installer is for the $P6NAME, not for the $NAME"
end

begin device_option_with_z9x_download_refused
printf 'BLOBS=partition\nVERSION=%s\n' "$VER" > "$C/inst/release.conf"; inst '' -Device "$ID" -Ip "$ADDR" check
expect_rc 1; expect_out "this download of the installer is for the XGIMI Z9X, not for the $NAME"
end

begin device_bad_option
inst '' -Device nosuch check
expect_rc 2; expect_out "-Device z9x|play6|$ID"
end

begin z9x_refuses_this_image
printf 'BLOBS=partition\nVERSION=%s\n' "$VER" > "$C/inst/release.conf"; inst '' -Device z9x -Image "$C/inst/$IMGN" rescue
expect_rc 1; expect_out "this image is for the $NAME, not for the XGIMI Z9X. Nothing was written."
end

# ---------------------------------------------------------------- status and reset
begin status_reset
mkdir -p "$C/state/play6" "$C/state/$ID"
printf '%s\n' serial=ZF62TEST0009 step_check=done > "$C/state/state"
printf '%s\n' serial=P6TESTSER001 step_check=done > "$C/state/play6/state"
printf '%s\n' "serial=$SER" step_check=done > "$C/state/$ID/state"
inst '' status
expect_rc 0; expect_out "serial=ZF62TEST0009"; expect_out "$P6NAME:"; expect_out "$NAME:"; expect_out "  serial=$SER"
inst '' -Device "$ID" status
expect_rc 0; expect_out "  serial=$SER"; expect_no_out "ZF62TEST0009"; expect_no_out "P6TESTSER001"
inst '' -Device z9x reset
[ ! -f "$C/state/state" ] && [ -f "$C/state/play6/state" ] && [ -f "$C/state/$ID/state" ] || fail "-Device z9x reset touched another projector"
inst '' -Device "$ID" reset
[ ! -d "$C/state/$ID" ] && [ -f "$C/state/play6/state" ] || fail "-Device $ID reset"
# the folder name is the id in lower case ((Join-Path $StateDir ("$Device".ToLower()))), whatever case -Device has
mkdir -p "$C/state/$ID"; printf '%s\n' "serial=$SER" > "$C/state/$ID/state"
inst '' -Device "$(printf '%s' "$ID" | tr '[:lower:]' '[:upper:]')" reset
[ ! -d "$C/state/$ID" ] && [ -f "$C/state/play6/state" ] || fail "-Device (upper case) reset"
inst '' reset
[ ! -d "$C/state" ] || fail "reset kept $C/state"
end

echo "installer ps1 $ID (ab-fastbootd): $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
