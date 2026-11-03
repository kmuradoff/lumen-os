#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of the installer's FLOW=ab-fastbootd way (installer/lumen-install.sh, the abf_* functions), for the
# first projector of installer/lib/devices.conf with that FLOW. On the Mac or Linux, no projector, no network:
#   bash tools/devices/test/installer_hb.sh
# Every value of the projector (model, board, fastbootd product, firmware, slots, KEEP, WIPE_PARTS, SYSTEM_MAX,
# its Lumen OS property, image token, allow-list) is read from lib/devices.conf, so this file names none of them.
# A copy of the installer goes into a temp dir with a throwaway test certificate in the profile's CERT path,
# release.conf DEVICE=<id>, a fake 1 MiB erofs image (the erofs magic at byte 1024) listed in a SHA256SUMS signed
# by that key, and a TEST allow-list made by tools/ota/test/blobs_fixture.py from the profile's real one (its
# structure, made-up files; no proprietary file is ever used). The projector is a fake (state in $FB):
#   mode          stock | lumen | fastboot
#   addr          the ADDR:PORT it answers at over the network (adb connect / disconnect / get-state / devices,
#                 'adb mdns services' from $FB/mdns); auth.<mode>: that many 'unauthorized' answers first
#   props.<mode>  getprop; mapper.<mode> the /dev/block/mapper listing; $DEV the file tree 'adb pull' reads
#   fastboot      devices / getvar all (vars + the logical partitions in parts) / getvar partition-size:X /
#                 create-, resize-logical-partition / flash / format:ext4 / reboot (-> lumen once system was
#                 written, else back to the old system); *_fail files make a step fail
# Cases: check (stock, Lumen OS, another projector, firmware, slot, no virtual A/B, Lumen OS of another device,
# the vbmeta state, no mke2fs), the image (name, release version, ext4 and sparse images, too big, another
# key, unsigned, its content through a fake dump.erofs), vbmeta (only shown), blobs (stock with from=, the
# /system/<path> fallback, a wrong hash refused, --no-blobs, a round trip through the real, unchanged
# tools/ota/image/z9x_blobs.sh with the test allow-list: sys.z9x.blobs=ok), flash (the whole visit, exact
# fastboot commands; vbmeta not effective; every fastbootd refusal; no room; cancelled; an existing z9x_blobs;
# --no-blobs; failed blobs, system and wipe steps and the resumed run (without the blobs file once z9x_blobs is
# written, an image refused there, --no-blobs named when the file is still needed); a refused fastbootd check
# then fastboot mode entered by hand (never taken for that visit); a size that did not take), the full
# install, verify, the commands that are not there yet, the options it refuses, which download picks the
# projector, status / reset per projector.
# Every case: fb.log holds only the allowed commands (getvar, create / resize / flash of z9x_blobs_<slot>, flash
# system_<slot>, format:ext4 of WIPE_PARTS, reboot), never -w, erase, vbmeta, --slot, set_active, a partition of
# another slot, delete-logical-partition, snapshot-update, flashall, update, wipe-super, or a format without
# :ext4; and adb never writes anything but 'reboot fastboot'.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
INST=$G/installer
OTEST=$G/tools/ota/test
READER=$G/tools/ota/image/z9x_blobs.sh
W=$(mktemp -d "${TMPDIR:-/tmp}/lumen_abf.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
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
SYSMAX=$(conf "$ID" SYSTEM_MAX); ALLOW=$(conf "$ID" BLOBS_ALLOW)
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

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb of a projector over the network (see the header of installer_hb.sh)
case "$1" in start-server|kill-server) exit 0 ;; esac   # the installer's own adb server (port 5038)
echo "$*" >> "$FB/adb.log"
mode=$(cat "$FB/mode")
up() { case "$mode" in stock|lumen) [ ! -e "$FB/net_down" ] ;; *) return 1 ;; esac; }
state() {  # target -> device | unauthorized | offline | missing
  grep -qxF "$1" "$FB/connected" || { echo missing; return; }
  if ! up || [ "$1" != "$(cat "$FB/addr")" ]; then echo offline; return; fi
  n=$(cat "$FB/auth.$mode" 2>/dev/null || echo 0)
  if [ "$n" -gt 0 ]; then echo $((n - 1)) > "$FB/auth.$mode"; echo unauthorized; return; fi
  echo device
}
case "$1" in
  version) echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0 ;;
  connect)
    if up && [ "$2" = "$(cat "$FB/addr")" ]; then
      grep -qxF "$2" "$FB/connected" && { echo "already connected to $2"; exit 0; }
      echo "$2" >> "$FB/connected"; echo "connected to $2"; exit 0
    fi
    echo "failed to connect to '$2': Connection refused"; exit 1 ;;
  disconnect) grep -vxF "$2" "$FB/connected" > "$FB/c.tmp"; mv "$FB/c.tmp" "$FB/connected"; echo "disconnected $2"; exit 0 ;;
  devices) echo "List of devices attached"
           while read -r t; do [ -n "$t" ] && printf '%s\t%s\n' "$t" "$(state "$t" | sed 's/^missing$/offline/')"; done < "$FB/connected"
           echo; exit 0 ;;
  mdns) echo "List of discovered mdns services"; cat "$FB/mdns" 2>/dev/null; exit 0 ;;
  -s) T=$2; shift 2 ;;
  *) echo "UNEXPECTED $*" >> "$FB/unexpected"; exit 1 ;;
esac
st=$(state "$T")
if [ "$1" = get-state ]; then
  case "$st" in
    device) echo device; exit 0 ;;
    unauthorized) echo "error: device unauthorized." >&2; exit 1 ;;
    offline) echo "error: device offline" >&2; exit 1 ;;
    *) echo "error: device '$T' not found" >&2; exit 1 ;;
  esac
fi
[ "$st" = device ] || { echo "error: device '$T' $st" >&2; exit 1; }
gp() { awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$FB/props.$mode"; }
case "$1" in
  reboot) [ "${2-}" = fastboot ] || { echo "UNEXPECTED reboot $*" >> "$FB/unexpected"; exit 1; }
          [ -e "$FB/reboot_fail" ] && { echo "error: closed" >&2; exit 1; }
          echo "$mode" > "$FB/before_fastboot"; echo fastboot > "$FB/mode" ;;
  pull) [ -f "$DEV$2" ] || { echo "adb: error: failed to stat remote object '$2': No such file or directory" >&2; exit 1; }
        if [ -d "$3" ]; then cp "$DEV$2" "$3/"; else cp "$DEV$2" "$3"; fi ;;
  shell)
    shift
    case "$*" in
      "getprop "*) gp "$2" ;;
      "ls /dev/block/mapper/") cat "$FB/mapper.$mode" 2>/dev/null ;;
      "pm list packages -3") : ;;
      "settings list "*) echo "$3_setting=1" ;;
      "["*) exit 1 ;;
      *) echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1 ;;
    esac ;;
  *) echo "UNEXPECTED -s $T $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
# fake fastboot of the projector's fastbootd; 'getvar' answers on stderr like the real one
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then [ "$(cat "$FB/mode")" = fastboot ] && printf '%s\tfastboot\n' "$FB_SERIAL"; exit 0; fi
[ "$1" = -s ] && { [ "$2" = "$FB_SERIAL" ] || { echo "< waiting for $2 >" >&2; exit 1; }; shift 2; }
[ "$(cat "$FB/mode")" = fastboot ] || { echo "< waiting for any device >" >&2; exit 1; }
size() { awk -v p="$1" '$1 == p { print $2 }' "$FB/parts"; }
setsize() { awk -v p="$1" '$1 != p' "$FB/parts" > "$FB/p.tmp"; echo "$1 $2" >> "$FB/p.tmp"; mv "$FB/p.tmp" "$FB/parts"; }
case "$1" in
  getvar)
    if [ "$2" = all ]; then
      cat "$FB/vars" >&2
      while read -r p s; do printf '(bootloader) is-logical:%s:yes\n(bootloader) partition-size:%s:0x%X\n' "$p" "$p" "$s" >&2; done < "$FB/parts"
      echo "all: Done" >&2
    else
      case "$2" in partition-size:*) s=$(size "${2#partition-size:}"); [ -n "$s" ] && printf '%s: 0x%X\n' "$2" "$s" >&2 ;; esac
      echo "Finished. Total time: 0.001s" >&2
    fi ;;
  create-logical-partition) [ -e "$FB/create_fail" ] && { echo "FAILED (remote: 'Not enough space')" >&2; exit 1; }
                            [ -z "$(size "$2")" ] || { echo "FAILED (remote: 'Partition already exists')" >&2; exit 1; }
                            setsize "$2" "$3" ;;
  resize-logical-partition) [ -n "$(size "$2")" ] || { echo "FAILED (remote: 'Could not find partition')" >&2; exit 1; }
                            setsize "$2" "$3" ;;
  flash)
    [ -e "$FB/flash_fail_$2" ] && { echo "FAILED (remote: 'write failed')" >&2; exit 1; }
    case "$2" in
      system_*) [ -e "$FB/no_resize" ] || setsize "$2" "$(wc -c < "$3" | tr -d ' ')"; touch "$FB/flashed" ;;
      z9x_blobs_*) cp "$3" "$FB/$2.img" ;;
    esac
    echo "Writing '$2' OKAY" >&2 ;;
  format:ext4) [ -e "$FB/format_fail_$2" ] && { echo "FAILED (remote: 'format failed')" >&2; exit 1; }
               touch "$FB/formatted_$2" ;;
  reboot) if [ -e "$FB/flashed" ]; then echo lumen > "$FB/mode"; touch "$FB/net_down"; else cat "$FB/before_fastboot" > "$FB/mode"; fi
          rm -f "$FB/flashed"; : > "$FB/connected" ;;
  *) echo "UNEXPECTED fastboot $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/dump.erofs" <<'EOF'
#!/bin/sh
# fake dump.erofs: the image's files are $FB/erofs/<path> (no $FB/erofs: the image cannot be read)
[ -d "$FB/erofs" ] || exit 1
case "$1" in
  --cat) p=${2#--path=}; [ -f "$FB/erofs$p" ] && cat "$FB/erofs$p" ;;
  --path=*) p=${1#--path=}; [ -f "$FB/erofs$p" ] || exit 1; echo "Path : $p"; echo "Size: $(wc -c < "$FB/erofs$p" | tr -d ' ')  On-disk size: 0" ;;
  *) exit 1 ;;
esac
EOF
cat > "$W/bin/date" <<'EOF'
#!/bin/sh
case "${1-}" in
  +%H:%M:%S) echo 12:00:00 ;;
  +%Y%m%d-%H%M) echo 20261103-1200 ;;
  +%Y%m%d-%H%M%S) echo 20261103-120000 ;;
  *) exec /bin/date "$@" ;;
esac
EOF
cat > "$W/bin/df" <<'EOF'
#!/bin/sh
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk3s5 970000000 400000000 500000000 45%% /\n'
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
printf '#!/bin/sh\nexit 0\n' > "$W/bin/mke2fs"; cp "$W/bin/mke2fs" "$W/bin/make_f2fs"
chmod 755 "$W/bin/"*
cp "$W/bin/adb" "$W/bin/fastboot" "$W/bin/make_f2fs" "$W/nomke2fs/"
for f in getprop setprop log timeout mount chcon chown; do cp "$OTEST/stubs/$f" "$W/dbin/$f" && chmod 755 "$W/dbin/$f"; done

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
  cp "$INST/lumen-install.sh" "$C/inst/"; cp -R "$INST/lib" "$C/inst/"
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
  # super as on the projector's stock dump: system, vendor, product, system_ext on the slot; the other slot empty
  printf '%s\n' "system_$SL 688357376" "vendor_$SL 1369018368" "product_$SL 59363328" "system_ext_$SL 3317760" \
    "system_$OSL 0" "vendor_$OSL 0" "product_$OSL 0" "system_ext_$OSL 0" > "$FB/parts"
  echo stock > "$FB/mode"; echo "$ADDR" > "$FB/addr"
  : > "$FB/connected"; : > "$FB/adb.log"; : > "$FB/fb.log"
}
inst() {  # answers args... -> the installer copy; exit status in RC, output in $C/out
  local ans=$1; shift
  printf '%b' "$ans" | env LUMEN_PLATFORM_TOOLS="${TPT:-$W/bin}" PATH="$W/bin:$PATH" FB="$FB" DEV="$DEV" FB_SERIAL=$SER \
    LUMEN_STATE_DIR="$C/state" LUMEN_BACKUP_DIR="$C/backup" LUMEN_EROFS_UTILS= LANG="${LANG_CASE:-en_US.UTF-8}" \
    bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -qF -- "$1" "$C/out" || fail "output lacks '$1'"; }
expect_no_out() { if grep -qF -- "$1" "$C/out"; then fail "output has '$1'"; fi; }
cmds() { grep -v -e '^--version$' -e '^devices$' "$FB/fb.log" | sed "s|$C/||g" | tr '\n' ';'; }
expect_cmds() { [ "$(cmds)" = "$1" ] || fail "fastboot commands: got '$(cmds)', want '$1'"; }
st() { sed -n "s/^$1=//p" "$C/state/$ID/state" 2>/dev/null | tail -1; }
setp() { sed -i.bak "s|^$2=.*|$2=$3|" "$FB/props.$1"; }
adb_talked() { grep -qvx version "$FB/adb.log"; }   # anything but the version check of the Platform-Tools
adb_writes() { grep -E ' (reboot|root|push|install|uninstall|am)( |$)| shell (echo|rm|setprop|svc) ' "$FB/adb.log" | tr '\n' ';'; }
F="-s $SER"
FLASH_FULL="$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;$F flash system_$SL inst/$IMGN;$F getvar partition-size:system_$SL;"
for p in $WIPEP; do FLASH_FULL="$FLASH_FULL$F format:ext4 $p;"; done
FLASH_FULL="$FLASH_FULL$F reboot;"
end() {
  [ ! -s "$FB/unexpected" ] || fail "unexpected: $(cat "$FB/unexpected")"
  # the never list, and every fastboot command one of the allowed ones
  if grep -qE '(^| )(-w|erase|vbmeta|--slot|--slot=.*|set_active|--set-active|delete-logical-partition|snapshot-update|flashall|update|wipe-super|flashing|oem|boot)( |:|$)' "$FB/fb.log"; then
    fail "forbidden fastboot command: $(tr '\n' ';' < "$FB/fb.log")"
  fi
  grep -qE "_$OSL( |$)" "$FB/fb.log" && fail "a partition of the other slot: $(tr '\n' ';' < "$FB/fb.log")"
  local allowed="^(--version|devices|-s $SER (getvar all|getvar partition-size:system_$SL|reboot|create-logical-partition z9x_blobs_$SL 4194304|resize-logical-partition z9x_blobs_$SL 4194304|flash z9x_blobs_$SL [^ ]+|flash system_$SL [^ ]+|format:ext4 ($(echo $WIPEP | tr ' ' '|'))))$"
  if grep -vqE "$allowed" "$FB/fb.log"; then fail "fastboot command not on the allowed list: $(grep -vE "$allowed" "$FB/fb.log" | head -3 | tr '\n' ';')"; fi
  grep -E '(^| )format' "$FB/fb.log" | grep -vqE ' format:ext4 ' && fail "a format without :ext4"
  case "$(adb_writes)" in ''|"-s $ADDR reboot fastboot;") ;; *) fail "adb wrote: $(adb_writes)" ;; esac
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -14; fi
}
roundtrip() {  # the z9x_blobs partition the installer flashed, read by the real z9x_blobs.sh: every file bound
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

# ---------------------------------------------------------------- check
begin check_stock
inst '' --ip "$ADDR" check
expect_rc 0; expect_out "$NAME $SER ($ADDR), firmware $FW, slot $SL, system: XGIMI stock"
expect_out "vbmeta: verification is off on slot $SL (no *-verity)"; expect_out "SHA256SUMS signature: Lumen OS key for the $NAME"
expect_out "dump.erofs could not read the image content (not needed: the signed SHA256SUMS lists this file as Lumen OS $VER"
expect_out "check passed; nothing was written"
[ "$(st step_check)" = done ] && [ "$(st serial)" = "$SER" ] && [ "$(st ip)" = "$ADDR" ] && [ "$(st slot)" = "_$SL" ] || fail "state"
expect_cmds ""
end

begin check_lumen
echo lumen > "$FB/mode"; inst '' --ip "$ADDR" check
expect_rc 0; expect_out "system: Lumen OS $VER"
end

begin check_mdns_address
printf 'adb-%s-abc123\t_adb._tcp\t%s\n' "$SER" "$ADDR" > "$FB/mdns"
inst 'yes\n' check
expect_rc 0; expect_out "A projector answers at $ADDR"; expect_out "check passed"
end

begin check_no_address
inst 'no\n' check
expect_rc 1; expect_out "give the projector's network address: bash lumen-install.sh --ip 192.168.x.y"
end

begin check_z9x_refused
setp stock ro.boot.xgimi.modelname "$Z9MODEL"; setp stock ro.boot.hardware "$Z9BOARD"; inst '' --device "$ID" --ip "$ADDR" check
expect_rc 1; expect_out "this is not an $NAME (model '$Z9MODEL', board '$Z9BOARD'). Nothing was done. It is an XGIMI Z9X"
end

begin check_play6_refused
setp stock ro.boot.xgimi.modelname "$P6MODEL"; setp stock ro.boot.hardware "$P6BOARD"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "this is not an $NAME (model '$P6MODEL', board '$P6BOARD'). Nothing was done. It is an $P6NAME"
end

begin check_unknown_refused
setp stock ro.boot.xgimi.modelname G0099; setp stock ro.boot.hardware mt9999; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "this is not an $NAME (model 'G0099', board 'mt9999'). Nothing was done."
end

begin check_firmware_refused
setp stock ro.vendor.build.version.incremental v0.0.1; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "XGIMI firmware v0.0.1 is not supported: Lumen OS for the $NAME is made for V${FW#v} only"
end

begin check_slot_refused
setp stock ro.boot.slot_suffix "_$OSL"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "the $NAME runs from slot $OSL: Lumen OS installs only into slot $SL"
end

begin check_no_virtual_ab_refused
setp stock ro.virtual_ab.enabled false; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "this projector has no virtual A/B"
end

begin check_lumen_other_device_refused
echo lumen > "$FB/mode"; setp lumen "$LPROP" otherdevice; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "this projector runs Lumen OS for another device ('otherdevice')"
end

begin check_vbmeta_needed
printf '%s\n' system-verity vendor-verity >> "$FB/mapper.stock"; inst '' --ip "$ADDR" check
expect_rc 0; expect_out "vbmeta: slot $SL still verifies its system (*-verity): the vbmeta step is needed before 'flash'"
expect_out "check passed"; [ -z "$(st step_vbmeta)" ] || fail "step_vbmeta set"
end

begin check_unauthorized_once
echo 1 > "$FB/auth.stock"; inst '' --ip "$ADDR" check
expect_rc 0; expect_out "allow debugging from this computer"
end

begin check_no_mke2fs
TPT=$W/nomke2fs inst '' --ip "$ADDR" check; TPT=""
expect_rc 1; expect_out "mke2fs missing next to fastboot"
! adb_talked || fail "adb used: $(cat "$FB/adb.log")"
end

# ---------------------------------------------------------------- the image
begin image_other_release_refused
mv "$C/inst/$IMGN" "$C/inst/lumen-os-$TOK-0.0.1-nogms-system.img"; sign "$C/inst" k; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is Lumen OS 0.0.1, but this installer download is for Lumen OS $VER"
end

begin image_not_release_name_refused
mv "$C/inst/$IMGN" "$C/inst/lumen-os-$TOK-$VER-system.img"; sign "$C/inst" k; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is not a release file"
end

begin image_ext4_refused
mkimg "$C/inst/$IMGN" ext4; sign "$C/inst" k; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is an ext4 image (another projector's), not one for the $NAME"
end

begin image_sparse_refused
mkimg "$C/inst/$IMGN" sparse; sign "$C/inst" k; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is a sparse image, not the release file"
end

begin image_too_big_refused
sed -i.bak "s/^$ID\.SYSTEM_MAX=.*/$ID.SYSTEM_MAX=4096/" "$C/inst/lib/devices.conf"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is too big for the $NAME"
end

begin image_too_big_with_blobs_refused
# with blobs the budget is SYSTEM_MAX less z9x_blobs (4 MiB) and SUPER_RESERVE: what the write's room check refuses
dd if=/dev/zero of="$C/inst/$IMGN" bs=4096 count=768 2>/dev/null
printf '\342\341\365\340' | dd of="$C/inst/$IMGN" bs=1 seek=1024 conv=notrunc 2>/dev/null; sign "$C/inst" k
res=$(conf "$ID" SUPER_RESERVE)
sed -i.bak "s/^$ID\.SYSTEM_MAX=.*/$ID.SYSTEM_MAX=$((2097152 + 4194304 + res))/" "$C/inst/lib/devices.conf"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is too big for the $NAME (3 MB, at most 2 MB fit"
inst '' --ip "$ADDR" --no-blobs check
expect_rc 0; expect_out "check passed"
end

begin image_other_key_refused
sign "$C/inst" o; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "SHA256SUMS is not signed with the Lumen OS key for the $NAME"
end

begin image_damaged_refused
printf 'x' | dd of="$C/inst/$IMGN" bs=1 seek=8192 conv=notrunc 2>/dev/null; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "checksum mismatch"
end

begin image_unsigned
rm -f "$C/inst/SHA256SUMS" "$C/inst/SHA256SUMS.sig"; inst 'UNSIGNED\n' --ip "$ADDR" --unsigned-image check
expect_rc 0; expect_out "--unsigned-image: this image is NOT authenticated"; expect_out "checksum not checked"
end

image_content() {  # props-lines...: the fake image's product build.prop
  mkdir -p "$FB/erofs/system/product/etc"; printf '%s\n' "$@" > "$FB/erofs/system/product/etc/build.prop"
}
begin image_content_ok
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"; inst '' --ip "$ADDR" check
expect_rc 0; expect_out "image: Lumen OS $VER for the $NAME"
end

begin image_content_test_build_refused
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=0"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is a test build (ro.adb.secure='0'), not a release"
end

begin image_content_other_device_refused
image_content "$LPROP=otherdevice" "$VPROP=$VER" "ro.adb.secure=1"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "is not Lumen OS for the $NAME ($LPROP='otherdevice')"
end

begin image_content_version_refused
image_content "$LPROP=$LVAL" "$VPROP=0.0.1" "ro.adb.secure=1"; inst '' --ip "$ADDR" check
expect_rc 1; expect_out "holds Lumen OS '0.0.1', the release is $VER"
end

begin image_content_private_refused
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"
rel=$(awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $2; exit }' "$W/allow.txt")
mkdir -p "$(dirname "$FB/erofs/system/$rel")"; echo inside > "$FB/erofs/system/$rel"
inst '' --ip "$ADDR" check
expect_rc 1; expect_out "carries the MediaTek / XGIMI files itself (a private build): run with --blobs=embedded"
end

begin image_content_public_embedded_refused
image_content "$LPROP=$LVAL" "$VPROP=$VER" "ro.adb.secure=1"; inst '' --ip "$ADDR" --blobs=embedded check
expect_rc 1; expect_out "is a public image: its MediaTek / XGIMI files come from your projector"
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
dev_stock; inst '' --ip "$ADDR" blobs
expect_rc 0; expect_out "$NB MediaTek / XGIMI files from your projector, set $SET -> $C/backup/$BLOBN"
[ "$(st step_blobs)" = done ] && [ "$(st blobs_img)" = "$C/backup/$BLOBN" ] || fail "state"
[ -f "$C/state/$ID/blobs/z9x_blobs.img" ] || fail "work dir"
roundtrip "$C/backup/$BLOBN"
end

begin blobs_system_fallback
# the stock sources gone, but a running private test image's /system/<path> copies are there
awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $2, $3 }' "$W/allow.txt" | while read -r rel src; do
  mkdir -p "$(dirname "$DEV$src")" "$(dirname "$DEV/system/$rel")"; echo "AOSP build" > "$DEV$src"; cp "$W/files/$rel" "$DEV/system/$rel"
done
inst '' --ip "$ADDR" blobs
expect_rc 0; roundtrip "$C/backup/$BLOBN"
end

begin blobs_wrong_hash_refused
dev_stock; src=$(awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { print $3; exit }' "$W/allow.txt"); echo "other firmware" > "$DEV$src"
inst '' --ip "$ADDR" blobs
expect_rc 1; expect_out "from the projector does not match the allow-list"; expect_out "NO SOUND"
[ ! -f "$C/backup/$BLOBN" ] || fail "a blobs image was written"
end

begin blobs_no_blobs
dev_stock; inst '' --ip "$ADDR" --no-blobs blobs
expect_rc 0; expect_out "--no-blobs: the MediaTek / XGIMI files are skipped"; [ "$(st step_blobs)" = skip ] || fail "state"
! adb_talked || fail "adb used"
end

# ---------------------------------------------------------------- flash
begin flash_full
dev_stock; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 0; expect_cmds "$FLASH_FULL"
expect_out "Type ERASE to continue:"; expect_out "Press Enter when the cable is in."; expect_out "fastboot mode: $NAME $SER, slot $SL"
expect_out "If it hangs with the cable in, pull the cable"
[ "$(st step_flash)" = done ] && [ "$(st step_wipe)" = done ] && [ "$(st step_system)" = done ] || fail "state"
for p in $WIPEP; do [ -e "$FB/formatted_$p" ] || fail "$p not formatted"; done
[ "$(cat "$FB/mode")" = lumen ] || fail "mode $(cat "$FB/mode")"
roundtrip
end

begin flash_vbmeta_not_effective
dev_stock; printf '%s\n' system-verity >> "$FB/mapper.stock"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "still verifies its system (*-verity in /dev/block/mapper)"; expect_out "Nothing was written."
expect_cmds ""; expect_no_out "Type ERASE"
end

begin flash_mapper_unreadable
dev_stock; : > "$FB/mapper.stock"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "could not read /dev/block/mapper"; expect_cmds ""
end

fbd_refusal() {  # case vars-sed expected
  begin "$1"
  dev_stock; [ -z "$2" ] || sed -i.bak "$2" "$FB/vars"
  inst 'ERASE\n\n' --ip "$ADDR" flash
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
inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "Nothing was written. The projector starts its old system."
[ -z "$(st step_fastboot)" ] || fail "step_fastboot kept after the restart"
mv "$FB/vars.bak" "$FB/vars"; echo fastboot > "$FB/mode"; : > "$FB/fb.log"; : > "$FB/adb.log"
inst 'no\nERASE\n\n'
expect_rc 1; expect_out "Nothing was done."; expect_no_out "continuing there"; expect_no_out "Type ERASE"
expect_cmds ""; [ "$(cat "$FB/mode")" = fastboot ] || fail "mode $(cat "$FB/mode")"
end

begin flash_cow_present
dev_stock; echo "system_${SL}-cow 1048576" >> "$FB/parts"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "fastboot mode check failed: *-cow partitions"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_keep_missing
dev_stock; k=$(echo "$KEEP" | awk '{print $NF}'); sed -i.bak "/^${k}_$SL /d" "$FB/parts"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "fastboot mode check failed: the partition ${k}_$SL is missing"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_system_not_logical
dev_stock; sed -i.bak "/^system_$SL /d" "$FB/parts"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "fastboot mode check failed: is-logical:system_$SL"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_no_room
dev_stock; sed -i.bak 's/partition-size:super:0xCE800000/partition-size:super:0x7E600000/' "$FB/vars"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "no room in super for z9x_blobs_$SL"; expect_cmds "$F getvar all;$F reboot;"
end

begin flash_cancelled
dev_stock; inst 'no\n' --ip "$ADDR" flash
expect_rc 1; expect_out "cancelled, nothing was written"; expect_cmds ""
grep -q 'reboot' "$FB/adb.log" && fail "adb reboot sent"
end

begin flash_reboot_refused
dev_stock; touch "$FB/reboot_fail"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "adb reboot fastboot failed: the projector still runs its system. Nothing was written."; expect_cmds ""
[ -z "$(st step_fastboot)" ] || fail "step_fastboot kept"
end

begin flash_blobs_existing
dev_stock; echo "z9x_blobs_$SL 4194304" >> "$FB/parts"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 0
expect_cmds "$(printf '%s' "$FLASH_FULL" | sed "s|create-logical-partition|resize-logical-partition|")"
roundtrip
end

begin flash_no_blobs
inst 'ERASE\n\n' --ip "$ADDR" --no-blobs flash
expect_rc 0; expect_no_out "z9x_blobs_$SL"
expect_cmds "$(printf '%s' "$FLASH_FULL" | sed "s|$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;||")"
end

begin flash_blobs_create_fails
dev_stock; touch "$FB/create_fail"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "the partition z9x_blobs_$SL could not be made. Nothing was written."
expect_cmds "$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F reboot;"
end

begin flash_blobs_write_fails
dev_stock; touch "$FB/flash_fail_z9x_blobs_$SL"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "z9x_blobs_$SL was not written; system was not touched. Nothing was written."
expect_cmds "$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;$F reboot;"
end

begin flash_system_fails_then_resumed
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "system was not written completely. The projector stays in fastboot mode"
expect_cmds "$F getvar all;$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;$F flash system_$SL inst/$IMGN;"
[ "$(cat "$FB/mode")" = fastboot ] && [ "$(st step_system)" = started ] || fail "not left in fastboot mode"
# the next run continues in fastboot mode: no adb, z9x_blobs (written in this visit) kept, system written, the wipe
rm -f "$FB/flash_fail_system_$SL"; : > "$FB/fb.log"; : > "$FB/adb.log"; inst 'ERASE\n' flash
expect_rc 0; expect_out "the projector waits in fastboot mode on the cable: continuing there"
expect_cmds "$(printf '%s' "$FLASH_FULL" | sed "s|$F create-logical-partition z9x_blobs_$SL 4194304;$F flash z9x_blobs_$SL backup/$BLOBN;||")"
! adb_talked || fail "adb used: $(cat "$FB/adb.log")"
roundtrip
end

begin flash_resumed_cancel_stays
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' --ip "$ADDR" flash
: > "$FB/fb.log"; inst 'no\n' flash
expect_rc 1; expect_out "The write of this install is not finished: the projector stays in fastboot mode."
expect_cmds ""; [ "$(cat "$FB/mode")" = fastboot ] || fail "restarted"
end

begin flash_resumed_blobs_file_gone
# z9x_blobs written in that visit: the resumed run does not need the blobs file again
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' --ip "$ADDR" flash
[ "$(st step_zblobs)" = done ] && [ "$(st step_system)" = started ] || fail "state after the first run"
rm -f "$FB/flash_fail_system_$SL" "$C/backup/$BLOBN"; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 0; expect_no_out "is missing"
w="$F getvar all;$F flash system_$SL inst/$IMGN;$F getvar partition-size:system_$SL;"; for p in $WIPEP; do w="$w$F format:ext4 $p;"; done
expect_cmds "$w$F reboot;"
roundtrip
end

begin flash_resumed_image_refused_stays
# system begun: an image STOP of the resumed run keeps the projector in fastboot mode and never says nothing was written
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' --ip "$ADDR" flash
mkimg "$C/inst/$IMGN" ext4; sign "$C/inst" k; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 1; expect_out "is an ext4 image (another projector's), not one for the $NAME."
expect_out "The write of this install is not finished: the projector stays in fastboot mode. Keep the cable in and run the installer again."
expect_no_out "Nothing was written"; expect_cmds ""; [ "$(cat "$FB/mode")" = fastboot ] || fail "restarted"
end

begin flash_resumed_image_refused_nothing_written
# a visit in which nothing was written yet: an image STOP sends the projector back to its old system
dev_stock; touch "$FB/flash_fail_system_$SL"; inst 'ERASE\n\n' --ip "$ADDR" flash
sed -i.bak -e 's/^step_system=.*/step_system=/' -e 's/^step_zblobs=.*/step_zblobs=/' "$C/state/$ID/state"
mkimg "$C/inst/$IMGN" ext4; sign "$C/inst" k; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 1; expect_out "is an ext4 image (another projector's), not one for the $NAME. Nothing was written. The projector starts its old system."
expect_cmds "$F reboot;"; [ -z "$(st step_fastboot)" ] || fail "step_fastboot kept"
end

begin flash_resumed_blobs_needed_no_blobs_named
# system begun without z9x_blobs (--no-blobs), the next run without --no-blobs and without the file: --no-blobs is the way out
inst 'ERASE\n\n' --ip "$ADDR" --no-blobs flash
touch "$FB/flash_fail_system_$SL"; : > "$FB/fb.log"
sed -i.bak -e 's/^step_flash=.*/step_flash=/' -e 's/^step_wipe=.*/step_wipe=/' -e 's/^step_system=.*/step_system=started/' "$C/state/$ID/state"
echo fastboot > "$FB/mode"; inst 'ERASE\n' flash
expect_rc 1; expect_out "is missing. To finish without them (no sound, no MediaTek video decoders), run the installer again with --no-blobs."
expect_out "The write of this install is not finished"; expect_no_out "Nothing was written"; expect_cmds ""
rm -f "$FB/flash_fail_system_$SL"; inst 'ERASE\n' --no-blobs flash
expect_rc 0; expect_out "Written."
end

begin flash_size_not_taken
dev_stock; touch "$FB/no_resize"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "after the write system_$SL has 688357376 bytes, the image 1048576: the write did not take"
[ "$(cat "$FB/mode")" = fastboot ] || fail "restarted"
expect_no_out "wiping data"
end

begin flash_wipe_fails_then_resumed
dev_stock; p1=$(echo "$WIPEP" | awk '{print $1}'); touch "$FB/format_fail_$p1"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 1; expect_out "the wipe ($p1) failed. The projector stays in fastboot mode"
[ "$(cat "$FB/mode")" = fastboot ] && [ "$(st step_system)" = done ] || fail "state"
rm -f "$FB/format_fail_$p1"; : > "$FB/fb.log"; inst 'ERASE\n' flash
expect_rc 0
w="$F getvar all;$F getvar partition-size:system_$SL;"; for p in $WIPEP; do w="$w$F format:ext4 $p;"; done
expect_cmds "$w$F reboot;"
end

begin flash_lumen_over_lumen
dev_stock; echo lumen > "$FB/mode"; inst 'ERASE\n\n' --ip "$ADDR" flash
expect_rc 0; expect_out "system: Lumen OS $VER"; expect_cmds "$FLASH_FULL"
end

begin flash_ru
dev_stock; LANG_CASE=ru_RU.UTF-8 inst 'ERASE\n\n' --ip "$ADDR" flash; LANG_CASE=""
expect_rc 0; expect_out "Введите ERASE, чтобы продолжить:"; expect_out "Записано. Проектор загружает Lumen OS"
expect_cmds "$FLASH_FULL"
end

# ---------------------------------------------------------------- the whole install
begin all_full
dev_stock; inst 'no\nERASE\n\n' --ip "$ADDR"
expect_rc 0; expect_out "check passed"; expect_out "Make a backup"; expect_no_out "ONE MANUAL STEP"
expect_out "$NB MediaTek / XGIMI files"; expect_cmds "$FLASH_FULL"; expect_out "bash lumen-install.sh verify --ip ADDRESS"
[ "$(st step_backup)" = skipped ] && [ "$(st step_flash)" = done ] || fail "state"
end

begin all_vbmeta_asked
dev_stock; printf '%s\n' system-verity >> "$FB/mapper.stock"; inst 'no\nno\n' --ip "$ADDR"
expect_rc 1; expect_out "ONE MANUAL STEP: vbmeta"; expect_out "do the vbmeta step first"; expect_cmds ""
end

begin all_done_again
dev_stock; inst 'no\nERASE\n\n' --ip "$ADDR"; : > "$FB/fb.log"; : > "$FB/adb.log"; inst '' --ip "$ADDR"
expect_rc 0; expect_out "Done."; expect_cmds ""; ! adb_talked || fail "adb used"
end

# ---------------------------------------------------------------- verify
begin verify_ok
echo lumen > "$FB/mode"; inst '' --ip "$ADDR" verify
expect_rc 0; expect_out "Lumen OS $VER on the $NAME $SER"; expect_out "MediaTek / XGIMI files (z9x_blobs"; expect_out "verify passed"
[ "$(st step_verify)" = done ] || fail "state"
end

begin verify_blobs_missing
echo lumen > "$FB/mode"; setp lumen sys.z9x.blobs missing; inst '' --ip "$ADDR" verify
expect_rc 0; expect_out "sys.z9x.blobs=missing"; [ -z "$(st step_verify)" ] || fail "state"
end

begin verify_not_lumen
inst '' --ip "$ADDR" verify
expect_rc 1; expect_out "this projector does not run Lumen OS for the $NAME"
end

begin verify_not_started
echo lumen > "$FB/mode"; setp lumen sys.boot_completed 0; inst '' --ip "$ADDR" verify
expect_rc 1; expect_out "the system has not finished starting yet"
end

# ---------------------------------------------------------------- not there yet, refused options
for c in rescue gapps gsf stick; do
  begin "not_yet_$c"
  inst '' --ip "$ADDR" "$c"
  expect_rc 1; expect_out "'$c' is not available for the $NAME yet. Nothing was done."
  [ ! -s "$FB/adb.log" ] || fail "adb used"
  end
done
begin keep_data_refused
inst '' --ip "$ADDR" --keep-data flash
expect_rc 1; expect_out "'--keep-data' is not available for the $NAME yet"; ! adb_talked || fail "adb used"
end
begin wipe_factory_reset_refused
inst '' --ip "$ADDR" --wipe=factory-reset check
expect_rc 1; expect_out "'--wipe=factory-reset' is not available for the $NAME yet"
end

# ---------------------------------------------------------------- which projector
begin device_ip_means_this_download
inst '' --ip "$ADDR" check
expect_rc 0; expect_no_out "$P6NAME"
end

begin device_option_with_play6_download_refused
printf 'DEVICE=play6\nVERSION=%s\n' "$VER" > "$C/inst/release.conf"; inst '' --device "$ID" --ip "$ADDR" check
expect_rc 1; expect_out "this download of the installer is for the $P6NAME, not for the $NAME"
end

begin device_option_with_z9x_download_refused
printf 'BLOBS=partition\nVERSION=%s\n' "$VER" > "$C/inst/release.conf"; inst '' --device "$ID" --ip "$ADDR" check
expect_rc 1; expect_out "this download of the installer is for the XGIMI Z9X, not for the $NAME"
end

begin device_bad_option
inst '' --device nosuch check
expect_rc 1; expect_out "--device z9x|play6|$ID"
end

begin z9x_refuses_this_image
printf 'BLOBS=partition\nVERSION=%s\n' "$VER" > "$C/inst/release.conf"; inst '' --device z9x --image "$C/inst/$IMGN" rescue
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
inst '' --device "$ID" status
expect_rc 0; expect_out "  serial=$SER"; expect_no_out "ZF62TEST0009"; expect_no_out "P6TESTSER001"
inst '' --device z9x reset
[ ! -f "$C/state/state" ] && [ -f "$C/state/play6/state" ] && [ -f "$C/state/$ID/state" ] || fail "--device z9x reset touched another projector"
inst '' --device "$ID" reset
[ ! -d "$C/state/$ID" ] && [ -f "$C/state/play6/state" ] || fail "--device $ID reset"
inst '' reset
[ ! -d "$C/state" ] || fail "reset kept $C/state"
end

echo "installer $ID (ab-fastbootd): $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
