#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of the installer's XGIMI Play 6 profile (installer/lumen-install.sh, profile play6 of
# installer/lib/devices.conf). On the Mac or Linux, no projector, no network:
#   bash tools/ota/test/installer_play6.sh
# A copy of the installer goes into a temp dir with a throwaway test certificate as certs/play6/ota.x509.pem,
# release.conf DEVICE=play6, and a fake 1 MiB ext4 image (the ext4 magic at byte 1080) listed in a SHA256SUMS
# signed by that key. The projector is a fake with a state machine (state in $FB):
#   mode          stock | lumen | fastboot | recovery: what runs on the projector
#   addr          the ADDR:PORT it answers at over Wi-Fi (adb connect / disconnect / get-state / devices; a
#                 changed address is found through 'adb mdns services', whose name carries the serial)
#   auth.lumen    how many more times Lumen OS answers 'unauthorized' (the allow prompt on the screen)
#   props.<mode>  getprop; settings (provisioning flags), packages3 (user apps), marker (the check file)
#   adb root      drops the connection until the next 'adb connect' (auth_after_root: the allow prompt again);
#                 adb reboot fastboot -> fastboot mode (reboot_fail: refused; reboot_fail_after: restarts, says error)
#   stale         an old connection still says 'device' after the projector went away (a new one does not)
#   am broadcast  FACTORY_RESET: logged; as root (unless reset_noop) the projector goes to its recovery and
#                 the data is erased (marker and apps gone, provisioning flags 0)
#   fastboot      devices / getvar all (the Play 6 layout, made from lib/devices.conf) / flash system /
#                 reboot (-> lumen when system was written, with props.written if there; else back to the old system)
# Fake date, df, sleep, debugfs (the image's build.prop files) and diskutil (the restore stick) make it repeatable.
# Cases: detection and refusals (unknown model, another MT5889 model, a Z9X over Wi-Fi, a second slot),
# firmware (both versions pass, others stop with the message), the address (none, mDNS), image checks (Z9X
# erofs image, sparse image, a SHA256SUMS of another key, no certificate, an image of another release by its
# name, a name that is not a release name, --unsigned-image with <img>.sha256, debugfs content read from the
# build.prop files in init's order), the whole install (exact fastboot commands; adb connect ... reboot
# fastboot ... root ... one factory reset with --user 0), --keep-data, every fastbootd refusal (getvar all;
# reboot only), resume in fastbootd and after the write, a failed write, adb reboot fastboot refused or
# reporting an error while it restarts, a projector neither in fastboot mode nor on Wi-Fi, the first start
# (allow prompt once or never accepted, new address by mDNS also with --ip, a write without effect over stock
# or over an older Lumen OS: the next run writes again, an image that names its version differently: a
# warning), the wipe guards (another projector, ERASE not typed again, no root, no answer after adb root, a
# reset that does not start, a stale connection, a reset already done by a cut-off run), verify (clean, data
# still there, not allowed), rescue (saved serial, typed serial, a projector last seen on stock), the commands
# the Play 6 does not need, the stick check, status / reset per projector, which download picks the projector
# (a Z9X download stays the Z9X with a Play 6 install saved), backup over Wi-Fi, Russian texts, and a
# later flash over super with the Google services add-on lumen_gapps (passes, its space counted; any other
# extra partition refused). 'gapps' and 'gsf' themselves: tools/ota/test/installer_play6_gapps.sh.
# Every case: the fastboot log never holds a format, erase, -w, flashing, set_active, oem, boot,
# snapshot-update, *-logical-partition or a flash of anything but system, and adb never sends the factory
# reset unless the case expects it.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/lumen_play6.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=P6TESTSER001
ADDR=192.0.2.50:5555
CONF=$INST/lib/devices.conf
conf() { awk -v k="$1.$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2) }' "$CONF"; }
NAME=$(conf play6 NAME); MODEL=$(conf play6 MODEL); BOARD=$(conf play6 BOARD); PRODUCT=$(conf play6 FB_PRODUCT)
FW1=$(conf play6 VENDOR_OK | awk '{print $1}'); FW2=$(conf play6 VENDOR_OK | awk '{print $2}')
# one supported firmware (owner, 2026-10-13: only the latest XGIMI firmware): the second slot of the tests is the same one
FW2=${FW2:-$FW1}
if [ "$FW1" = "$FW2" ]; then FWLIST="V${FW1#v} only"; else FWLIST="V${FW1#v} and V${FW2#v} only"; fi
TOK=$(conf play6 IMAGE_TOKEN)
IMGN=lumen-os-$TOK-1.1.0-nogms-system.img

mkdir -p "$W/bin" "$W/keys" "$W/nodbg"
for k in k o; do
  openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/$k.key" -out "$W/keys/$k.pem" -subj /CN=lumen-test-$k -days 2 >/dev/null 2>&1 \
    || { echo "openssl cannot make a test key"; exit 1; }
done

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb of an XGIMI Play 6 over Wi-Fi (see the header of installer_play6.sh)
case "$1" in start-server|kill-server) exit 0 ;; esac   # the installer's own adb server (port 5038)
echo "$*" >> "$FB/adb.log"
mode=$(cat "$FB/mode")
up() { case "$mode" in stock|lumen) [ ! -e "$FB/net_down" ] ;; *) return 1 ;; esac; }
state() {  # target -> device | unauthorized | offline | missing
  grep -qxF "$1" "$FB/connected" || { echo missing; return; }
  if ! up || [ "$1" != "$(cat "$FB/addr")" ]; then [ -e "$FB/stale" ] && { echo device; return; }; echo offline; return; fi
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
    unauthorized) echo "error: device unauthorized." >&2; echo "Otherwise check for a confirmation dialog on your device." >&2; exit 1 ;;
    offline) echo "error: device offline" >&2; exit 1 ;;
    *) echo "error: device '$T' not found" >&2; exit 1 ;;
  esac
fi
[ "$st" = device ] || { echo "error: device '$T' $st" >&2; exit 1; }
P=$FB/props.$mode
gp() { awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$P"; }
case "$1" in
  root)
    [ "$(gp ro.debuggable)" = 1 ] || { echo "adbd cannot run as root in production builds"; exit 1; }
    touch "$FB/root"; grep -vxF "$T" "$FB/connected" > "$FB/c.tmp"; mv "$FB/c.tmp" "$FB/connected"
    [ -f "$FB/auth_after_root" ] && cp "$FB/auth_after_root" "$FB/auth.$mode"
    echo "restarting adbd as root" ;;
  reboot) [ "${2-}" = fastboot ] || { echo "UNEXPECTED reboot $*" >> "$FB/unexpected"; exit 1; }
          [ -e "$FB/reboot_fail" ] && { echo "error: closed" >&2; exit 1; }
          echo "$mode" > "$FB/before_fastboot"; rm -f "$FB/root"; echo fastboot > "$FB/mode"
          [ -e "$FB/reboot_fail_after" ] && { echo "error: closed" >&2; exit 1; } ;;
  pull) mkdir -p "$3" 2>/dev/null; echo "pulled $2" > "$3/$(basename "$2")" ;;
  shell)
    shift
    case "$*" in
      "getprop "*) gp "$2" ;;
      "id -u") [ -e "$FB/root" ] && echo 0 || echo 2000 ;;
      "settings get system xgimi_ota_update") cat "$FB/ota_setting" 2>/dev/null || echo null ;;
      "settings get global device_provisioned"|"settings get secure user_setup_complete"|"settings get secure tv_user_setup_complete")
        v=$(awk -v k="$4" '$1 == k { print $2 }' "$FB/settings"); echo "${v:-null}" ;;
      "settings list "*) echo "$3_setting=1" ;;
      "echo "*" > /data/local/tmp/lumen-install-wipe") t=${*#echo }; echo "${t% > /data/local/tmp/lumen-install-wipe}" > "$FB/marker" ;;
      "cat /data/local/tmp/lumen-install-wipe") [ -f "$FB/marker" ] && cat "$FB/marker" || { echo "cat: /data/local/tmp/lumen-install-wipe: No such file or directory"; exit 1; } ;;
      "cat /data/local/tmp/lumen-install-wipe 2>/dev/null || echo lumen-install-no-check-file")
        [ -f "$FB/marker" ] && cat "$FB/marker" || echo lumen-install-no-check-file ;;
      "am broadcast --user 0 -a android.intent.action.FACTORY_RESET -p android --receiver-foreground --es android.intent.extra.REASON lumen-install")
        echo "Broadcasting: Intent { act=android.intent.action.FACTORY_RESET flg=0x10000000 pkg=android (has extras) }"
        echo "Broadcast completed: result=0"
        # a sender without MASTER_CLEAR is skipped silently by the receiver (am still says completed)
        if [ -e "$FB/root" ] && [ ! -e "$FB/reset_noop" ]; then
          echo recovery > "$FB/mode"; rm -f "$FB/marker" "$FB/root"; : > "$FB/packages3"
          printf '%s\n' "device_provisioned 0" "user_setup_complete 0" "tv_user_setup_complete 0" > "$FB/settings"
          touch "$FB/wiped"
        fi ;;
      "pidof org.lumen.projector"|"pidof org.lumen.projector org.z9x.projector") [ "$mode" = lumen ] && echo 1234 ;;
      "pm path "*) grep -qxF "$3" "$FB/packages3" && echo "package:/data/app/$3-1/base.apk" ;;
      "pm list packages -3") sed 's/^/package:/' "$FB/packages3" ;;
      "dumpsys media.audio_policy") echo "Outputs:" ;;
      "["*) exit 1 ;;
      *) echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1 ;;
    esac ;;
  *) echo "UNEXPECTED -s $T $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
# fake fastboot of the Play 6's fastbootd; 'getvar all' answers on stderr like the real one
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
ser=$(cat "$FB/fbserial" 2>/dev/null || echo "$FB_SERIAL")
if [ "$1" = devices ]; then [ "$(cat "$FB/mode")" = fastboot ] && printf '%s\tfastboot\n' "$ser"; exit 0; fi
[ "$1" = -s ] && { [ "$2" = "$ser" ] || { echo "< waiting for $2 >" >&2; exit 1; }; shift 2; }
[ "$(cat "$FB/mode")" = fastboot ] || { echo "< waiting for any device >" >&2; exit 1; }
case "$1 ${2-}" in
  "getvar all") cat "$FB/getvar" >&2 ;;
  "flash system") [ -e "$FB/flash_fail" ] && { echo "FAILED (remote: 'write failed')" >&2; exit 1; }
                  touch "$FB/flashed"; echo "Writing 'system' OKAY" >&2 ;;
  "reboot "*) if [ -e "$FB/flashed" ] && [ ! -e "$FB/no_effect" ]; then echo lumen > "$FB/mode"; [ ! -f "$FB/props.written" ] || cp "$FB/props.written" "$FB/props.lumen"
              else cat "$FB/before_fastboot" > "$FB/mode" 2>/dev/null || echo stock > "$FB/mode"; fi
              rm -f "$FB/flashed" ;;
  *) echo "UNEXPECTED fastboot $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/date" <<'EOF'
#!/bin/sh
case "${1-}" in
  +%H:%M:%S) echo 12:00:00 ;;
  +%Y%m%d-%H%M) echo 20261013-1200 ;;
  +%Y%m%d-%H%M%S) echo 20261013-120000 ;;
  +%Y%m%d%H%M%S) echo 20261013120000 ;;
  *) exec /bin/date "$@" ;;
esac
EOF
cat > "$W/bin/df" <<'EOF'
#!/bin/sh
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk3s5 970000000 400000000 500000000 45%% /\n'
EOF
cat > "$W/bin/debugfs" <<'EOF'
#!/bin/sh
# fake debugfs: -R "cat FILE" IMAGE -> the image's product build.prop is $FB/buildprop, /system/build.prop
# $FB/buildprop.system, the system_ext one $FB/buildprop.system_ext (a missing file: nothing on stdout)
echo "debugfs 1.47.0 (5-Feb-2023)" >&2
case "$1 $2" in
  "-R cat /system/product/etc/build.prop") cat "$FB/buildprop" 2>/dev/null ;;
  "-R cat /system/build.prop") cat "$FB/buildprop.system" 2>/dev/null ;;
  "-R cat /system/system_ext/etc/build.prop") cat "$FB/buildprop.system_ext" 2>/dev/null ;;
  "-R cat /system/etc/prop.default") ;;
  *) exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/diskutil" <<'EOF'
#!/bin/sh
# fake diskutil for the restore stick: $FB/stick_pt (FDisk_partition_scheme | GUID_partition_scheme),
# $FB/stick_fs (MS-DOS FAT32 ...), $FB/stick_parts (number of partitions)
case "$1 $2" in
  "info disk9") echo "   Device Identifier:         disk9"; echo "   Content (IOContent):       $(cat "$FB/stick_pt")" ;;
  "info "*) echo "   Device Identifier:         disk9s1"; echo "   Part of Whole:             disk9"
            echo "   File System Personality:   $(cat "$FB/stick_fs")" ;;
  "list disk9") echo "/dev/disk9 (external, physical):"; echo "   #:                       TYPE NAME                    SIZE       IDENTIFIER"
                echo "   0:     $(cat "$FB/stick_pt")                        *15.5 GB    disk9"
                i=1; while [ "$i" -le "$(cat "$FB/stick_parts")" ]; do echo "   $i:                 DOS_FAT_32 STICK                   8.0 GB     disk9s$i"; i=$((i + 1)); done ;;
  *) exit 1 ;;
esac
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
chmod 755 "$W/bin/"*
for f in adb fastboot date df sleep diskutil; do ln -s "$W/bin/$f" "$W/nodbg/$f"; done

PROPS_STOCK="ro.boot.xgimi.modelname=$MODEL
ro.boot.hardware=$BOARD
ro.vendor.build.version.incremental=$FW2
ro.serialno=$SER
ro.build.type=user
ro.debuggable=0
ro.adb.secure=0
sys.boot_completed=1"
PROPS_LUMEN="ro.boot.xgimi.modelname=$MODEL
ro.boot.hardware=$BOARD
ro.vendor.build.version.incremental=$FW2
ro.serialno=$SER
ro.build.type=userdebug
ro.debuggable=1
ro.adb.secure=1
ro.lumen.device=play6
ro.lumen.version=1.1.0
sys.boot_completed=1"

mkimg() {  # file [magic]: a 1 MiB image with the ext4 magic (or another file system's)
  dd if=/dev/zero of="$1" bs=4096 count=256 2>/dev/null
  case "${2:-ext4}" in
    ext4) printf '\123\357' | dd of="$1" bs=1 seek=1080 conv=notrunc 2>/dev/null ;;
    erofs) printf '\342\341\365\340' | dd of="$1" bs=1 seek=1024 conv=notrunc 2>/dev/null ;;
    sparse) printf '\072\377\046\355' | dd of="$1" bs=1 seek=0 conv=notrunc 2>/dev/null ;;
  esac
}
sign() {  # dir key: SHA256SUMS of the images in dir, signed with the key
  (cd "$1" && for f in lumen-os-*-system.img; do { sha256sum "$f" 2>/dev/null || shasum -a 256 "$f"; }; done > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/$2.key" -out SHA256SUMS.sig SHA256SUMS)
}
begin() {
  CASE=$1; CFAIL=0; BROADCAST=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst/certs/play6" "$C/fb" "$C/state" "$C/backup"
  FB=$C/fb
  cp "$INST/lumen-install.sh" "$C/inst/"; cp -R "$INST/lib" "$C/inst/"
  cp "$INST/certs/ota.x509.pem" "$C/inst/certs/"
  cp "$W/keys/k.pem" "$C/inst/certs/play6/ota.x509.pem"
  printf 'DEVICE=play6\nVERSION=1.1.0\n' > "$C/inst/release.conf"
  mkimg "$C/inst/$IMGN"; sign "$C/inst" k
  printf '%s\n' "$PROPS_STOCK" > "$FB/props.stock"; printf '%s\n' "$PROPS_LUMEN" > "$FB/props.lumen"
  printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=1.1.0" "ro.adb.secure=1" > "$FB/buildprop"
  { echo "(bootloader) is-userspace:yes"; echo "(bootloader) product:$PRODUCT"; echo "(bootloader) unlocked:yes"
    echo "(bootloader) serialno:$SER"; echo "(bootloader) slot-count:0"; echo "(bootloader) current-slot:"
    echo "(bootloader) super-partition-name:super"
    echo "(bootloader) vendor-fingerprint:/Product_test/$PRODUCT:11/RP1A.200720.011/${FW2#v}:user/dev-keys"
    echo "(bootloader) is-logical:system:yes"; echo "(bootloader) is-logical:vendor:yes"
    echo "(bootloader) is-logical:userdata:no"; echo "(bootloader) is-logical:cache:no"; echo "(bootloader) is-logical:misc:no"
    echo "(bootloader) partition-size:super:0xE0000000"; echo "(bootloader) partition-size:system:0x4409C000"
    echo "(bootloader) partition-size:vendor:0x79608000"; echo "(bootloader) partition-size:userdata:0x57CB00000"
    echo "(bootloader) partition-size:cache:0xAF000000"; echo "(bootloader) max-download-size:0x10000000"
    echo "all: Done"; } > "$FB/getvar"
  printf '%s\n' "device_provisioned 1" "user_setup_complete 1" "tv_user_setup_complete 1" > "$FB/settings"
  printf '%s\n' com.zacharee1.systemuituner com.example.player > "$FB/packages3"
  echo stock > "$FB/mode"; echo "$ADDR" > "$FB/addr"
  : > "$FB/connected"; : > "$FB/adb.log"; : > "$FB/fb.log"
}
inst() {  # answers args... -> the installer copy; exit status in RC, output in $C/out
  local ans=$1; shift
  printf '%b' "$ans" | env LUMEN_PLATFORM_TOOLS="$W/bin" PATH="${TPATH:-$W/bin:$PATH}" FB="$FB" FB_SERIAL=$SER LUMEN_STATE_DIR="$C/state" \
    LUMEN_BACKUP_DIR="$C/backup" LANG="${LANG_CASE:-en_US.UTF-8}" bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -qF -- "$1" "$C/out" || fail "output lacks '$1'"; }
expect_no_out() { if grep -qF -- "$1" "$C/out"; then fail "output has '$1'"; fi; }
cmds() { grep -v -e '^--version$' -e '^devices$' "$FB/fb.log" | sed "s|$C/||g" | tr '\n' ';'; }
expect_cmds() { [ "$(cmds)" = "$1" ] || fail "fastboot commands: got '$(cmds)', want '$1'"; }
state() { sed -n "s/^$1=//p" "$C/state/play6/state" 2>/dev/null | tail -1; }
setp() { sed -i.bak "s|^$2=.*|$2=$3|" "$FB/props.$1"; }
adb_has() { grep -qxF -- "$1" "$FB/adb.log"; }
writes() {  # adb commands that change the projector
  grep -E ' (reboot|root|push|install|uninstall|am broadcast)( |$)| shell echo ' "$FB/adb.log" | sed "s|$C/||g" | tr '\n' ';'
}
end() {
  [ ! -s "$FB/unexpected" ] || fail "unexpected: $(cat "$FB/unexpected")"
  if grep -qE '(^| )(format|erase|-w|flashing|set_active|--set-active|oem|boot|snapshot-update|[a-z]+-logical-partition)( |:|$)' "$FB/fb.log"; then
    fail "forbidden fastboot command: $(cat "$FB/fb.log")"
  fi
  if grep -E '(^| )flash ' "$FB/fb.log" | grep -vqE '^-s [A-Z0-9]+ flash system '; then fail "a flash of something but system: $(cat "$FB/fb.log")"; fi
  if [ "$BROADCAST" = 0 ] && grep -q 'am broadcast' "$FB/adb.log"; then fail "factory reset sent: $(grep 'am broadcast' "$FB/adb.log")"; fi
  if grep -q 'am broadcast' "$FB/adb.log" && [ "$(grep -c 'am broadcast' "$FB/adb.log")" != 1 ]; then fail "more than one factory reset"; fi
  if grep 'am broadcast' "$FB/adb.log" | grep -vq -- '--user 0 '; then fail "factory reset without --user 0"; fi
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; [ ! -f "$C/out" ] || sed 's/^/     | /' "$C/out" | tail -15; fi
}
S="-s $SER"
WRITE_CMDS="$S getvar all;$S flash system inst/$IMGN;$S reboot;"
STOP_CMDS="$S getvar all;$S reboot;"

# ------------------------------------------------------------------ check, detection, firmware
begin check_stock_fw2
inst '' --ip 192.0.2.50 check
expect_rc 0
expect_out "$NAME $SER ($ADDR), firmware $FW2, system: XGIMI stock"
expect_out "SHA256SUMS signature: Lumen OS key for the $NAME"; expect_out "image: Lumen OS 1.1.0 for the $NAME"
expect_out "edition: Lumen OS without Google"; expect_out "check passed; nothing was written"
[ "$(state serial)" = "$SER" ] && [ "$(state ip)" = "$ADDR" ] && [ "$(state vendor)" = "$FW2" ] && [ "$(state dev_lumen)" = 0 ] \
  || fail "state: $(cat "$C/state/play6/state")"
[ ! -e "$C/state/state" ] || fail "the Z9X state file was written"
[ -z "$(writes)" ] || fail "adb wrote: $(writes)"
expect_cmds ""
end

begin check_stock_fw1
setp stock ro.vendor.build.version.incremental "$FW1"
inst '' --ip 192.0.2.50 check
expect_rc 0; expect_out "firmware $FW1"
end

for fw in v1.7.200 v1.0.9; do
  begin "check_firmware_${fw}_refused"
  setp stock ro.vendor.build.version.incremental "$fw"
  inst '' --ip 192.0.2.50 check
  expect_rc 1
  expect_out "XGIMI firmware $fw is not supported: Lumen OS for the $NAME is tested on $FWLIST."
  expect_out "Do NOT install V${FW1#v} from a USB stick over it"; expect_out "RG0058V1.8xxx"; expect_out "Nothing was done."
  [ -z "$(writes)" ] || fail "adb wrote: $(writes)"
  end
done

begin check_unknown_model_refused
setp stock ro.boot.xgimi.modelname G0099; setp stock ro.boot.hardware mt9999
inst '' --ip 192.0.2.50 check
expect_rc 1
expect_out "this projector is not supported: model 'G0099', board 'mt9999'. Lumen OS installs on XGIMI Z9X (G0082) and $NAME ($MODEL). Nothing was done."
expect_cmds ""; [ -z "$(writes)" ] || fail "adb wrote: $(writes)"
end

begin check_other_model_same_board_refused
setp stock ro.boot.xgimi.modelname G0057
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "this projector is not supported: model 'G0057', board '$BOARD'"
expect_cmds ""
end

begin check_z9x_over_wifi_refused
setp stock ro.boot.xgimi.modelname G0082; setp stock ro.boot.hardware mt9952
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "is an XGIMI Z9X: it installs over the USB cable, without --ip"
end

begin check_second_slot_refused
echo "ro.boot.slot_suffix=_a" >> "$FB/props.stock"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "this projector has a second system slot (_a)"
end

begin check_no_address
inst '' check
expect_rc 1; expect_out "give the projector's Wi-Fi address: bash lumen-install.sh --ip 192.168.x.y"
expect_out "has no USB debugging"
[ -z "$(writes)" ] || fail "adb wrote: $(writes)"
end

begin check_found_by_mdns
printf 'adb-%s-AbCdEf\t_adb._tcp\t%s\n' "$SER" "$ADDR" > "$FB/mdns"
inst 'yes\n' check
expect_rc 0; expect_out "A projector answers at $ADDR. Is it yours"; expect_out "check passed"
[ "$(state ip)" = "$ADDR" ] || fail "ip $(state ip)"
end

begin check_no_answer
echo 192.0.2.99:5555 > "$FB/addr"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "no adb connection to $ADDR over Wi-Fi within a minute"
end

begin check_pending_stock_update_warns
echo 1 > "$FB/ota_setting"
inst '' --ip 192.0.2.50 check
expect_rc 0; expect_out "a downloaded XGIMI update waits for the next restart"
end

begin check_ru
LANG_CASE=ru_RU.UTF-8 inst '' --ip 192.0.2.50 check
expect_rc 0; expect_out "прошивка $FW2, система: родная XGIMI"; expect_out "проверка пройдена; ничего не записано"
if grep -q '—' "$C/out"; then fail "an em dash in the Russian text"; fi
end

# ------------------------------------------------------------------ the image
begin image_z9x_erofs_refused
mkimg "$C/inst/$IMGN" erofs; sign "$C/inst" k
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "is an XGIMI Z9X image (erofs)"
end

begin image_z9x_name_refused
mkimg "$C/lumen-os-1.0.2-nogms-system.img" erofs
inst '' --ip 192.0.2.50 --image "$C/lumen-os-1.0.2-nogms-system.img" check
expect_rc 1; expect_out "is not an image for the $NAME (its name must contain '$TOK')"
end

begin image_sparse_refused
mkimg "$C/inst/$IMGN" sparse; sign "$C/inst" k
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "is a sparse image, not the release file"
end

begin image_other_key_refused
sign "$C/inst" o
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "SHA256SUMS is not signed with the Lumen OS key for the $NAME"
end

begin image_no_certificate
rm -rf "$C/inst/certs/play6"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "installer certificate certs/play6/ota.x509.pem missing"
end

begin image_damaged
printf 'X' | dd of="$C/inst/$IMGN" bs=1 seek=100 conv=notrunc 2>/dev/null
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "checksum mismatch"
end

begin image_content_other_device
printf '%s\n' "ro.lumen.device=z9x" "ro.lumen.version=1.1.0" "ro.adb.secure=1" > "$FB/buildprop"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "is not Lumen OS for the $NAME (ro.lumen.device='z9x')"
end

begin image_content_test_build_refused
printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=1.1.0" "ro.adb.secure=0" > "$FB/buildprop"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "is a test build (ro.adb.secure='0'), not a release"
end

begin image_content_version_refused
printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=1.0.9" "ro.adb.secure=1" > "$FB/buildprop"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "holds Lumen OS '1.0.9', the release is 1.1.0"
end

begin image_without_debugfs
TPATH="$W/nodbg:/usr/bin:/bin" inst '' --ip 192.0.2.50 check
expect_rc 0; expect_out "image content not read (debugfs not installed): the signed SHA256SUMS lists this file as Lumen OS 1.1.0"
end

# the version is in the name, and the signed SHA256SUMS lists the image under it: an image of another release
# (signed with the same key) stops in 'check', before any write, also without debugfs (macOS, Windows)
begin image_other_release_refused
rm -f "$C/inst/$IMGN"; mkimg "$C/inst/lumen-os-$TOK-1.2.0-nogms-system.img"; sign "$C/inst" k
printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=1.2.0" "ro.adb.secure=1" > "$FB/buildprop"
TPATH="$W/nodbg:/usr/bin:/bin" inst 'no\nERASE\n\n' --ip 192.0.2.50
expect_rc 1
expect_out "lumen-os-$TOK-1.2.0-nogms-system.img is Lumen OS 1.2.0, but this installer download is for Lumen OS 1.1.0"
expect_out "Nothing was written."; expect_no_out "Type ERASE"
expect_cmds ""; [ -z "$(writes)" ] || fail "adb wrote: $(writes)"
end

begin image_not_named_like_a_release
rm -f "$C/inst/$IMGN"; mkimg "$C/inst/lumen-os-$TOK-nogms-system-1.1.0.img"; sign "$C/inst" k
inst '' --ip 192.0.2.50 --image "$C/inst/lumen-os-$TOK-nogms-system-1.1.0.img" check
expect_rc 1; expect_out "is not named like a release file (lumen-os-$TOK-<version>-nogms-system.img)"
end

begin image_no_version_in_release_conf
printf 'DEVICE=play6\n' > "$C/inst/release.conf"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "internal: release.conf has no VERSION"
end

# the image's properties as init reads them: system, then system_ext, then product (the last one wins)
begin image_content_effective_props
printf '%s\n' "ro.adb.secure=1" "ro.build.type=userdebug" > "$FB/buildprop.system"
printf '%s\n' "ro.adb.secure=0" > "$FB/buildprop.system_ext"
printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=1.1.0" > "$FB/buildprop"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "is a test build (ro.adb.secure='0'), not a release"
printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=1.1.0" "ro.adb.secure=1" > "$FB/buildprop"
inst '' --ip 192.0.2.50 check
expect_rc 0; expect_out "image: Lumen OS 1.1.0 for the $NAME"
end

begin image_unsigned_own_build
mkdir -p "$C/t18"; mkimg "$C/t18/system_${TOK}_t18.img"
(cd "$C/t18" && { sha256sum "system_${TOK}_t18.img" 2>/dev/null || shasum -a 256 "system_${TOK}_t18.img"; } > "system_${TOK}_t18.img.sha256")
printf '%s\n' "ro.lumen.device=play6" "ro.lumen.version=play6-t18" "ro.adb.secure=0" > "$FB/buildprop"
inst 'UNSIGNED\n' --ip 192.0.2.50 --unsigned-image --image "$C/t18/system_${TOK}_t18.img" check
expect_rc 0
expect_out "--unsigned-image: this image is NOT authenticated"; expect_out "system_${TOK}_t18.img sha256"
expect_out "image: Lumen OS play6-t18 for the $NAME"; expect_out "check passed"
end

begin image_unsigned_needs_typed_word
mkdir -p "$C/t18"; mkimg "$C/t18/system_${TOK}_t18.img"
inst 'yes\n' --ip 192.0.2.50 --unsigned-image --image "$C/t18/system_${TOK}_t18.img" check
expect_rc 1; expect_out "cancelled, nothing was written"
end

# ------------------------------------------------------------------ the whole install
begin install_all
inst 'no\nERASE\n\n\nyes\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1
expect_cmds "$WRITE_CMDS"
expect_out "check passed"; expect_out "Type ERASE to continue"
expect_out "Take the USB stick out of the projector's USB port"; expect_out "Press Enter when the cable is in."
expect_out "writing system (about 1 minute)"; expect_out "Unplug the A-to-A cable from the projector NOW"
expect_out "Lumen OS 1.1.0 started ($SER, $ADDR)"; expect_out "asking Android for its factory reset"
expect_out "The projector restarts into XGIMI's recovery, which erases the data"
expect_out "Done. On the projector: Settings > Network & Internet: join your Wi-Fi."
[ "$(grep -c 'Type ERASE' "$C/out")" = 1 ] || fail "ERASE asked $(grep -c 'Type ERASE' "$C/out") times"
[ "$(writes)" = "-s $ADDR reboot fastboot;-s $ADDR shell echo $(state wipe_marker) > /data/local/tmp/lumen-install-wipe;-s $ADDR root;-s $ADDR shell am broadcast --user 0 -a android.intent.action.FACTORY_RESET -p android --receiver-foreground --es android.intent.extra.REASON lumen-install;" ] \
  || fail "adb writes: $(writes)"
# order: the wipe only after the first start was seen; root before the reset
n_boot=$(grep -n "shell getprop ro.lumen.device" "$FB/adb.log" | tail -1 | cut -d: -f1)
n_root=$(grep -n " root$" "$FB/adb.log" | cut -d: -f1); n_bc=$(grep -n "am broadcast" "$FB/adb.log" | cut -d: -f1)
[ "$n_boot" -lt "$n_root" ] && [ "$n_root" -lt "$n_bc" ] || fail "order: lumen check $n_boot, root $n_root, broadcast $n_bc"
adb_has "disconnect $ADDR" || fail "no adb disconnect"
[ -e "$FB/wiped" ] || fail "the fake projector was not wiped"
for k in step_check step_flash_system step_flash step_boot1; do [ "$(state $k)" = done ] || fail "state $k=$(state $k)"; done
[ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
[ -n "$(state erase_ok)" ] && [ -n "$(state step_fastboot)" ] || fail "state erase_ok / step_fastboot"
end

begin install_all_ru
LANG_CASE=ru_RU.UTF-8 inst 'нет\nERASE\n\n\nда\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1
expect_out "Выньте флешку из USB-порта проектора"; expect_out "Сейчас выньте кабель A–A из проектора"
expect_out "Проектор перезагружается в recovery XGIMI"; expect_out "подключитесь к своему Wi-Fi"
if grep -q '—' "$C/out"; then fail "an em dash in the Russian text"; fi
end

begin install_not_confirmed
inst 'no\nno\n' --ip 192.0.2.50
expect_rc 1; expect_out "cancelled, nothing was written"; expect_cmds ""
[ -z "$(writes)" ] || fail "adb wrote: $(writes)"
end

begin install_backup_first
inst 'yes\nERASE\n\n\nyes\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1
expect_out "backup to $C/backup/play6-20261013-1200"; expect_out "over Wi-Fi this takes about 1 minute per GB"
expect_no_out "Google account"
[ -f "$C/backup/play6-20261013-1200/apps/com.example.player/base.apk" ] || fail "no APK in the backup"
[ "$(state step_backup)" = done ] || fail "step_backup"
end

begin flash_keep_data
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data flash
expect_rc 0
expect_cmds "$WRITE_CMDS"
expect_out "WITHOUT erasing data"; expect_out "Done. Lumen OS runs on the $NAME with your data kept."
expect_no_out "Type ERASE"
[ "$(state step_wipe)" = keep ] || fail "step_wipe=$(state step_wipe)"
grep -q " root$" "$FB/adb.log" && fail "adb root without a wipe"
end

# ------------------------------------------------------------------ fastbootd refusals: getvar all; reboot only
fbd_case() {  # name answers sed-on-getvar|- [extra setup] -> expected text
  begin "$1"
  [ "$2" = - ] || sed -i.bak "$2" "$FB/getvar"
  inst 'no\nERASE\n\n' --ip 192.0.2.50
  expect_rc 1; expect_cmds "$STOP_CMDS"; expect_out "$3"; expect_out "Nothing was written."
  [ "$(state step_flash_system)" = "" ] || fail "step_flash_system set"
  end
}
fbd_case fbd_wrong_product "s/^(bootloader) product:.*/(bootloader) product:mt5877/" "fastbootd is not the one of the $NAME (mt5877)"
fbd_case fbd_wrong_serial "s/^(bootloader) serialno:.*/(bootloader) serialno:OTHER0001/" "another projector in fastboot mode"
fbd_case fbd_slots "s/^(bootloader) slot-count:.*/(bootloader) slot-count:2/" "slot-count 2"
fbd_case fbd_extra_logical '$a\
(bootloader) is-logical:product:yes' "the partitions in super are not the known ones (product system vendor)"
fbd_case fbd_not_userspace "s/^(bootloader) is-userspace:.*/(bootloader) is-userspace:no/" "not fastbootd"
fbd_case fbd_locked "s/^(bootloader) unlocked:.*/(bootloader) unlocked:no/" "the bootloader is locked"
fbd_case fbd_other_firmware "s|/${FW2#v}:user|/1.6.0:user|" "XGIMI firmware not supported (/Product_test/$PRODUCT:11/RP1A.200720.011/1.6.0:user/dev-keys)"
fbd_case fbd_no_room "s/^(bootloader) partition-size:super:.*/(bootloader) partition-size:super:0x79A08000/" "the image does not fit into super (0 MB free, 1 MB needed)"

begin fbd_projector_not_on_cable
echo OTHERDEVICE > "$FB/fbserial"
inst 'no\nERASE\n\n' --ip 192.0.2.50
expect_rc 1; expect_out "the projector did not appear on the cable within 3 minutes"; expect_out "Nothing was written."
expect_cmds ""; [ -n "$(state step_fastboot)" ] || fail "step_fastboot not saved"
end

# ------------------------------------------------------------------ resume
begin resume_in_fastbootd
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=20261013-115900 > "$C/state/play6/state"
echo stock > "$FB/before_fastboot"; echo fastboot > "$FB/mode"
inst 'ERASE\n\nyes\n'
expect_rc 0; BROADCAST=1
expect_out "the projector waits in fastboot mode on the cable: continuing there"
expect_cmds "$WRITE_CMDS"
grep -q "reboot fastboot" "$FB/adb.log" && fail "adb reboot fastboot again"
expect_no_out "Press Enter when the cable is in"
[ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
end

begin resume_written_not_restarted
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=20261013-115900 step_flash_system=done > "$C/state/play6/state"
touch "$FB/flashed"; echo fastboot > "$FB/mode"
inst '\nERASE\nyes\n'
expect_rc 0; BROADCAST=1
expect_cmds "$S reboot;"
expect_out "system is already written; restarting the projector"
# nothing was typed in this run before the write: the wipe asks again
[ "$(grep -c 'Type ERASE' "$C/out")" = 1 ] || fail "ERASE asked $(grep -c 'Type ERASE' "$C/out") times"
end

begin flash_fails_stays_in_fastbootd
touch "$FB/flash_fail"
inst 'no\nERASE\n\n' --ip 192.0.2.50
expect_rc 1
expect_cmds "$S getvar all;$S flash system inst/$IMGN;"
expect_out "system was not written completely. The projector stays in fastboot mode"
[ "$(cat "$FB/mode")" = fastboot ] || fail "mode $(cat "$FB/mode")"
# the next run writes again from fastboot mode
rm -f "$FB/flash_fail"; : > "$FB/fb.log"
inst 'ERASE\n\nyes\n'
expect_rc 0; BROADCAST=1
expect_cmds "$WRITE_CMDS"
end

begin resume_after_first_start
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done step_boot1=done > "$C/state/play6/state"
echo lumen > "$FB/mode"
inst 'ERASE\nyes\n'
expect_rc 0; BROADCAST=1
expect_cmds ""
expect_out "Lumen OS runs on the old data. Next, Android's factory reset ERASES ALL DATA"
end

begin resume_after_first_start_not_confirmed
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done step_boot1=done > "$C/state/play6/state"
echo lumen > "$FB/mode"
inst 'no\n'
expect_rc 1; expect_out "cancelled, nothing was erased"
grep -q "lumen-install-wipe" "$FB/adb.log" && fail "the check file was written"
end

# ------------------------------------------------------------------ the first start
begin boot1_allow_prompt_once
echo 3 > "$FB/auth.lumen"
inst 'no\nERASE\n\n\nyes\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1
[ "$(grep -c 'allow debugging from this computer' "$C/out")" = 1 ] || fail "allow prompt $(grep -c 'allow debugging from this computer' "$C/out") times"
end

begin boot1_new_address_by_mdns
# the projector comes back at another address (DHCP); its mDNS name carries the serial
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done > "$C/state/play6/state"
echo lumen > "$FB/mode"; echo 192.0.2.77:5555 > "$FB/addr"
printf 'adb-%s-AbCdEf\t_adb._tcp\t192.0.2.77:5555\n' "$SER" > "$FB/mdns"
inst 'ERASE\nyes\n'
expect_rc 0; BROADCAST=1
expect_out "the projector answers at a new address: 192.0.2.77:5555"
[ "$(state ip)" = 192.0.2.77:5555 ] || fail "ip $(state ip)"
end

begin boot1_write_without_effect
touch "$FB/no_effect"
inst 'no\nERASE\n\n\n' --ip 192.0.2.50
expect_rc 1; expect_out "the projector still runs XGIMI's system: the write did not take effect"
expect_out "Run the installer again with the A-to-A cable at hand: it writes system again."
grep -q "lumen-install-wipe" "$FB/adb.log" && fail "the check file was written"
[ -z "$(state step_flash)$(state step_flash_system)$(state step_fastboot)" ] || fail "the write is still marked done: $(cat "$C/state/play6/state")"
# the next run writes again (no dead end), then goes on to the first start and the wipe
rm -f "$FB/no_effect"; : > "$FB/fb.log"
inst 'ERASE\n\n\nyes\n'
expect_rc 0; BROADCAST=1
expect_cmds "$WRITE_CMDS"; expect_out "Lumen OS 1.1.0 started"
[ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
end

begin boot1_old_lumen_still_runs
# a reinstall over Lumen OS 1.0.0 whose write did not take effect: the old version still runs
setp lumen ro.lumen.version 1.0.0; echo lumen > "$FB/mode"; touch "$FB/no_effect"
sed 's/^ro.lumen.version=.*/ro.lumen.version=1.1.0/' "$FB/props.lumen" > "$FB/props.written"
inst 'no\nERASE\n\n\n' --ip 192.0.2.50
expect_rc 1; expect_out "system: Lumen OS 1.0.0"
expect_out "the projector still runs the Lumen OS 1.0.0 it had before: the write did not take effect. Nothing was erased."
[ -z "$(state step_flash)" ] || fail "step_flash=$(state step_flash)"
grep -q "lumen-install-wipe" "$FB/adb.log" && fail "the check file was written"
rm -f "$FB/no_effect"; : > "$FB/fb.log"
inst 'ERASE\n\n\nyes\n'
expect_rc 0; BROADCAST=1; expect_cmds "$WRITE_CMDS"; expect_out "Lumen OS 1.1.0 started"
end

begin boot1_image_names_its_version_differently
# the release image calls itself play6-1.1.0 (compose.sh's test format): checked before the write, so the install
# goes on with a warning instead of a STOP that every run would repeat
setp lumen ro.lumen.version play6-1.1.0
TPATH="$W/nodbg:/usr/bin:/bin" inst 'no\nERASE\n\n\nyes\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1
expect_out "Lumen OS 'play6-1.1.0' runs, and this installer download is for 1.1.0: the image names its version differently"
[ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
end

begin boot1_lumen_not_on_wifi
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done > "$C/state/play6/state"
echo lumen > "$FB/mode"; touch "$FB/net_down"
inst '\n'
expect_rc 1; expect_out "Lumen OS did not answer over Wi-Fi within 10 minutes"; expect_out "your data is still there"
expect_out "Is the A-to-A cable out of the projector? The $NAME does not start while it is in."
end

begin boot1_not_allowed
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done > "$C/state/play6/state"
echo lumen > "$FB/mode"; echo 1000 > "$FB/auth.lumen"
inst '\n'
expect_rc 1; expect_out "Lumen OS runs, but it did not allow this computer within 10 minutes"; expect_out "Nothing was erased"
end

begin boot1_new_address_by_mdns_with_ip
# the guide always gives --ip: the projector is still found by its serial at a new address
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done > "$C/state/play6/state"
echo lumen > "$FB/mode"; echo 192.0.2.77:5555 > "$FB/addr"
printf 'adb-%s-AbCdEf\t_adb._tcp\t192.0.2.77:5555\n' "$SER" > "$FB/mdns"
inst 'ERASE\nyes\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1
expect_out "the projector answers at a new address: 192.0.2.77:5555"
end

# ------------------------------------------------------------------ fastboot mode not reached
begin fastboot_sent_projector_dark
# an earlier run sent the projector to fastboot mode; now it is neither there nor on Wi-Fi (dark, cable in)
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=20261013-115900 > "$C/state/play6/state"
echo dark > "$FB/mode"
inst ''
expect_rc 1
expect_out "the projector is neither in fastboot mode on the cable nor reachable over Wi-Fi at $ADDR"
expect_out "unplug the A-to-A cable (the $NAME does not start while it is in)"
expect_cmds ""
end

begin flash_again_after_finished_install_offline
# 'flash' after a finished install: the old step_fastboot does not make an offline projector look stuck
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done step_boot1=done step_wipe=done > "$C/state/play6/state"
echo lumen > "$FB/mode"; touch "$FB/net_down"
inst '' flash
expect_rc 1; expect_out "no adb connection to $ADDR over Wi-Fi within a minute"; expect_no_out "neither in fastboot mode"
[ -z "$(state step_fastboot)$(state step_flash)" ] || fail "old write progress kept: $(cat "$C/state/play6/state")"
end

begin reboot_fastboot_refused
touch "$FB/reboot_fail"
inst 'no\nERASE\n\n' --ip 192.0.2.50
expect_rc 1; expect_out "adb reboot fastboot failed: the projector still runs its system. Nothing was written."
[ -z "$(state step_fastboot)" ] || fail "step_fastboot=$(state step_fastboot)"
expect_cmds ""
end

begin reboot_fastboot_error_but_restarted
# adb reports an error because the connection drops with the restart: the install goes on in fastboot mode
touch "$FB/reboot_fail_after"
inst 'no\nERASE\n\n\nyes\n' --ip 192.0.2.50
expect_rc 0; BROADCAST=1; expect_cmds "$WRITE_CMDS"
end

# ------------------------------------------------------------------ the wipe guards
wipe_state() {
  mkdir -p "$C/state/play6"
  printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done step_boot1=done > "$C/state/play6/state"
  echo lumen > "$FB/mode"
}
begin wipe_other_projector_refused
wipe_state; setp lumen ro.serialno OTHER0002
inst 'ERASE\n'
expect_rc 1; expect_out "has the serial OTHER0002, but this install was started on $SER"
end

begin wipe_not_lumen_refused
wipe_state; sed -i.bak '/^ro.lumen.device=/d' "$FB/props.lumen"
inst 'ERASE\n'
expect_rc 1; expect_out "does not run Lumen OS for the $NAME: no factory reset is sent"
end

begin wipe_no_root_manual
wipe_state; setp lumen ro.debuggable 0
inst 'ERASE\n'
expect_rc 0
expect_out "adb root is not possible on this system"; expect_out "Settings > Device Preferences > Reset > Factory reset"
[ "$(state step_wipe)" = manual ] || fail "step_wipe=$(state step_wipe)"
end

begin wipe_reset_does_not_start
wipe_state; touch "$FB/reset_noop"; BROADCAST=1
inst 'ERASE\n'
expect_rc 1; expect_out "the projector still answers a minute after the factory reset was asked for: the factory reset did not start, your data is unchanged"
[ "$(state step_wipe)" = "" ] || fail "step_wipe=$(state step_wipe)"
end

begin wipe_stale_connection
# the computer's old connection still says 'device' after the projector went to its recovery: a new one does not
wipe_state; touch "$FB/stale"; BROADCAST=1
inst 'ERASE\nyes\n'
expect_rc 0; expect_out "The projector restarts into XGIMI's recovery"
[ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
end

begin wipe_not_allowed_after_root
# adb root restarts adbd; the projector asks again and nobody allows it: no 'manual', the next run tries again
wipe_state; echo 1000 > "$FB/auth_after_root"
inst 'ERASE\n'
expect_rc 1; expect_out "after 'adb root' the projector did not answer over Wi-Fi again within a minute"; expect_out "Nothing was erased."
[ "$(state step_wipe)" = "" ] || fail "step_wipe=$(state step_wipe)"
end

begin wipe_already_done_not_repeated
# an earlier run asked for the reset and was cut off before it saved that; the data is erased (no check file)
wipe_state; printf '%s\n' "wipe_marker=lumen-20261013120000-1" >> "$C/state/play6/state"; : > "$FB/packages3"
inst ''
expect_rc 0
expect_out "the data is already erased (the check file written before the factory reset is gone): no second factory reset"
expect_no_out "Type ERASE"
[ "$(state step_wipe)" = done ] || fail "step_wipe=$(state step_wipe)"
end

begin wipe_marker_saved_no_answer
# the same, but the projector is clean and off Wi-Fi: the STOP says that the reset may have run already
wipe_state; printf '%s\n' "wipe_marker=lumen-20261013120000-1" >> "$C/state/play6/state"; touch "$FB/net_down"
inst ''
expect_rc 1; expect_out "the factory reset of an earlier run has already erased the data, the Wi-Fi network too"
end

begin wipe_marker_saved_data_still_there
# an earlier run wrote the check file and stopped before the reset: this run asks ERASE and sends it once
wipe_state; printf '%s\n' "wipe_marker=lumen-20261013120000-1" >> "$C/state/play6/state"; echo lumen-20261013120000-1 > "$FB/marker"
BROADCAST=1
inst 'ERASE\nyes\n'
expect_rc 0; expect_out "Type ERASE"; [ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
end

begin wipe_keep_data_never
wipe_state
inst '' --keep-data
expect_rc 0; expect_out "with your data kept"
[ "$(state step_wipe)" = keep ] || fail "step_wipe=$(state step_wipe)"
end

# ------------------------------------------------------------------ verify
verify_state() {
  mkdir -p "$C/state/play6"
  printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_flash=done step_boot1=done step_wipe=requested wipe_marker=lumen-1-1 > "$C/state/play6/state"
  echo lumen > "$FB/mode"; : > "$FB/packages3"
}
begin verify_clean
verify_state
inst '' verify
expect_rc 0
expect_out "Lumen OS 1.1.0 ($SER, $ADDR)"; expect_out "data erased"; expect_out "SystemUI Tuner from stock is gone"
expect_out "no apps from before"; expect_out "the projector is marked set up"; expect_out "projector app running"
expect_out "debugging asks before a new computer may connect"
[ "$(state step_wipe)" = done ] && [ "$(state step_verify)" = done ] || fail "state: $(cat "$C/state/play6/state")"
grep -q '^  ! ' "$C/out" && fail "a warning: $(grep '^  ! ' "$C/out")"
end

begin verify_new_address
verify_state; echo 192.0.2.81:5555 > "$FB/addr"
inst '' verify --ip 192.0.2.81
expect_rc 0; expect_out "data erased"
end

begin verify_data_still_there
verify_state; echo lumen-1-1 > "$FB/marker"; printf '%s\n' com.zacharee1.systemuituner > "$FB/packages3"
inst 'no\n' verify
expect_rc 0
expect_out "the data was NOT erased"; expect_out "Erase all data now with Android's factory reset?"
expect_out "SystemUI Tuner from stock is still installed"
[ "$(state step_wipe)" = requested ] || fail "step_wipe=$(state step_wipe)"
end

begin verify_not_reachable
verify_state; touch "$FB/net_down"
inst '' verify
expect_rc 0; expect_out "After the wipe Wi-Fi and debugging are off: that is normal"
end

begin verify_not_allowed
verify_state; echo 1000 > "$FB/auth.lumen"
inst '' verify
expect_rc 0; expect_out "did not allow this computer"; expect_no_out "that is normal"
end

begin check_not_allowed
echo 1000 > "$FB/auth.stock"
inst '' --ip 192.0.2.50 check
expect_rc 1; expect_out "the projector at $ADDR did not allow this computer within a minute"
end

begin verify_never_picks_a_stranger
# nothing saved and no --ip: a projector that mDNS lists is not used (it may be someone else's)
printf 'adb-OTHER0003-AbCdEf\t_adb._tcp\t%s\n' "$ADDR" > "$FB/mdns"; echo lumen > "$FB/mode"
inst '' --device play6 verify
expect_rc 0; expect_out "After the wipe Wi-Fi and debugging are off: that is normal"
grep -q "^connect " "$FB/adb.log" && fail "connected to a projector found by mDNS"
end

begin all_after_wipe_verifies_when_reachable
mkdir -p "$C/state/play6"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_backup=skipped step_fastboot=x step_flash_system=done step_flash=done step_boot1=done step_wipe=requested wipe_marker=lumen-1-1 > "$C/state/play6/state"
echo lumen > "$FB/mode"; : > "$FB/packages3"
inst ''
expect_rc 0; expect_cmds ""; expect_out "data erased"; expect_out "Done. On the projector"
end

# ------------------------------------------------------------------ rescue
begin rescue_saved_serial
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" > "$C/state/play6/state"
echo lumen > "$FB/before_fastboot"; echo fastboot > "$FB/mode"
inst 'WRITE\n\n' --device play6 rescue
expect_rc 0
expect_cmds "$WRITE_CMDS"
expect_out "RESCUE ($NAME)"; expect_no_out "Type this serial"; expect_out "your data is kept"
[ -z "$(writes)" ] || fail "adb wrote: $(writes)"
end

begin rescue_needs_typed_serial
echo lumen > "$FB/before_fastboot"; echo fastboot > "$FB/mode"
inst "WRITE\n$SER\n\n" --device play6 rescue
expect_rc 0; expect_out "In fastboot mode: $SER. Type this serial"; expect_cmds "$WRITE_CMDS"
end

begin rescue_wrong_typed_serial
echo lumen > "$FB/before_fastboot"; echo fastboot > "$FB/mode"
inst 'WRITE\nNOPE\n' --device play6 rescue
expect_rc 1; expect_out "cancelled."; expect_cmds "$S reboot;"
end

begin rescue_refuses_stock_projector
# this computer saw the projector on stock and wrote nothing: fastbootd cannot tell, the saved progress can
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" dev_lumen=0 step_check=done > "$C/state/play6/state"
echo stock > "$FB/before_fastboot"; echo fastboot > "$FB/mode"
inst 'WRITE\n' --device play6 rescue
expect_rc 1; expect_out "this computer last saw this projector on XGIMI's stock system and has written nothing to it"
expect_out "Nothing was written."; expect_cmds "$S reboot;"
end

begin rescue_after_cut_off_write
# the same projector after a write that was cut off (fastboot mode was reached): rescue writes
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" dev_lumen=0 step_check=done step_fastboot=x > "$C/state/play6/state"
echo stock > "$FB/before_fastboot"; echo fastboot > "$FB/mode"
inst 'WRITE\n\n' --device play6 rescue
expect_rc 0; expect_cmds "$WRITE_CMDS"
end

begin rescue_not_confirmed
echo fastboot > "$FB/mode"
inst 'no\n' --device play6 rescue
expect_rc 1; expect_cmds ""
grep -q '^devices$' "$FB/fb.log" && fail "waited for the projector although not confirmed"
end

# ------------------------------------------------------------------ the Z9X commands the Play 6 does not need
for c in blobs vbmeta; do
  begin "not_needed_$c"
  inst '' --ip 192.0.2.50 "$c"
  expect_rc 1
  expect_out "'$c' is not needed on the $NAME"
  [ ! -s "$FB/adb.log" ] || fail "adb used: $(cat "$FB/adb.log")"
  end
done
# gapps and gsf of the Play 6: tools/ota/test/installer_play6_gapps.sh

# ------------------------------------------------------------------ super with the Google services add-on (lumen_gapps)
gapps_part() {  # size: the add-on partition lumen_gapps in super, as 'gapps' leaves it
  sed -i.bak '/^all: Done$/d' "$FB/getvar"
  { printf '(bootloader) is-logical:lumen_gapps:yes\n(bootloader) partition-size:lumen_gapps:0x%X\n' "$1"; echo "all: Done"; } >> "$FB/getvar"
}
begin flash_keep_data_with_gapps
# a later Lumen OS over an install with Google services: the known layout plus lumen_gapps passes, only system is written
echo lumen > "$FB/mode"; gapps_part 213909504
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data flash
expect_rc 0
expect_cmds "$WRITE_CMDS"
expect_out "fastboot mode: $NAME $SER"
end

begin flash_room_counts_gapps
# super with room for the image only without the add-on: the add-on keeps its space, nothing is written
echo lumen > "$FB/mode"; gapps_part 213909504
# super = vendor + 4 MiB reserve + the image (1 MiB) + 100 MiB: enough without the add-on, too small with it
sed -i.bak "s/^(bootloader) partition-size:super:.*/(bootloader) partition-size:super:0x$(printf '%X' $((0x79608000 + 4194304 + 1048576 + 104857600)))/" "$FB/getvar"
inst 'WRITE\n\n' --ip 192.0.2.50 --keep-data flash
expect_rc 1
expect_cmds "$STOP_CMDS"
expect_out "the image does not fit into super"
end

begin fbd_unknown_extra_partition_refused
echo lumen > "$FB/mode"; gapps_part 213909504
sed -i.bak 's/is-logical:lumen_gapps:yes/is-logical:other_part:yes/' "$FB/getvar"
inst 'WRITE\n\n' --ip 192.0.2.50 --keep-data flash
expect_rc 1
expect_cmds "$STOP_CMDS"
expect_out "the partitions in super are not the known ones"
end

# ------------------------------------------------------------------ the restore stick (read only)
BIN=$(conf play6 RESTORE_BIN)
stick() {  # a stick folder with the firmware file (fake, its md5 put into the copy's devices.conf) + factory.txt
  mkdir -p "$C/stick"; printf 'FAKE XGIMI FIRMWARE' > "$C/stick/$BIN"; : > "$C/stick/factory.txt"
  m=$( (md5sum "$C/stick/$BIN" 2>/dev/null || md5 -r "$C/stick/$BIN") | cut -d' ' -f1)
  sed -i.bak "s/^play6\.RESTORE_MD5=.*/play6.RESTORE_MD5=$m:V9.9.9/" "$C/inst/lib/devices.conf"
  echo FDisk_partition_scheme > "$FB/stick_pt"; echo "MS-DOS FAT32" > "$FB/stick_fs"; echo 1 > "$FB/stick_parts"
}
begin stick_ready
stick; mkdir -p "$C/stick/.fseventsd"
inst '' stick "$C/stick"
expect_rc 0; expect_out "partition table: MBR"; expect_out "one partition"; expect_out "file system: FAT32"
expect_out "factory.txt, empty"; expect_out "XGIMI firmware V9.9.9"; expect_out "the stick is ready"
[ ! -s "$FB/adb.log" ] && [ ! -s "$FB/fb.log" ] || fail "adb or fastboot used"
end

begin stick_problems
stick; echo GUID_partition_scheme > "$FB/stick_pt"; echo 2 > "$FB/stick_parts"; echo "MS-DOS FAT16" > "$FB/stick_fs"
: > "$C/stick/._$BIN"; echo 1 > "$C/stick/factory.txt"; : > "$C/stick/notes.txt"
inst '' stick "$C/stick"
expect_rc 1
expect_out "partition table 'gpt': it must be MBR"; expect_out "2 partitions: there must be exactly one"
expect_out "it must be FAT32"; expect_out "._$BIN: a macOS helper file"; expect_out "notes.txt: nothing else may be on the stick"
expect_out "factory.txt must be empty"; expect_out "the stick is not ready"
end

begin stick_unknown_firmware
stick; printf 'OTHER' > "$C/stick/$BIN"; rm -f "$C/stick/factory.txt"
inst '' stick "$C/stick"
expect_rc 1; expect_out "is not a firmware file the installer knows"; expect_out "no factory.txt"
end

# ------------------------------------------------------------------ progress per projector
begin status_reset_per_projector
mkdir -p "$C/state/play6"; printf '%s\n' "serial=ZF62TEST0001" step_check=done > "$C/state/state"
printf '%s\n' "serial=$SER" step_check=done > "$C/state/play6/state"
inst '' status
expect_out "serial=ZF62TEST0001"; expect_out "$NAME:"; expect_out "  serial=$SER"
inst '' --device play6 reset
[ ! -e "$C/state/play6" ] && [ -f "$C/state/state" ] || fail "play6 reset removed the wrong progress"
inst '' --device play6 status
expect_out "no saved progress"
end

begin two_unfinished_installs
# only the owner's own builds (not bound to a download) let an unfinished install name the projector
mkdir -p "$C/state/play6"; printf '%s\n' "serial=ZF62TEST0001" step_check=done > "$C/state/state"
printf '%s\n' "serial=$SER" step_check=done > "$C/state/play6/state"
rm -f "$C/inst/release.conf"
inst '' --unsigned-image check
expect_rc 1; expect_out "two unfinished installs are saved"
end

begin play6_download_ignores_unfinished_z9x
# a Play 6 download is for the Play 6, whatever the computer keeps for a Z9X
mkdir -p "$C/state/play6"; printf '%s\n' "serial=ZF62TEST0001" step_check=done > "$C/state/state"
printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done > "$C/state/play6/state"
inst '' check
expect_rc 0; expect_out "check passed"
end

begin unfinished_play6_continues_without_ip
# no --ip, a download without DEVICE (the Z9X's): the unfinished Play 6 install picks the profile and its address
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done > "$C/state/play6/state"
rm -f "$C/inst/release.conf"
inst '' --unsigned-image check
expect_rc 0; expect_out "connecting to $ADDR over Wi-Fi"; expect_out "check passed"
expect_out "this installer folder came with the XGIMI Z9X release; your own image is used (--unsigned-image)"
end

begin z9x_download_ignores_unfinished_play6
# a Z9X download behaves as it always did, also with an unfinished Play 6 install on this computer (device
# separation): its commands are the Z9X ones (tools/ota/test/installer_profiles.sh compares them byte for byte)
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done > "$C/state/play6/state"
cp "$C/state/play6/state" "$C/p6state"
rm -f "$C/inst/release.conf"
inst 'no\n' vbmeta
expect_rc 1; expect_out "ONE MANUAL STEP: vbmeta"; expect_no_out "$NAME"
inst '' check
expect_rc 1; expect_no_out "$NAME"; expect_no_out "download"
if grep -qvE '^(version|devices)$' "$FB/adb.log"; then fail "adb did more than list devices: $(cat "$FB/adb.log")"; fi
cmp -s "$C/p6state" "$C/state/play6/state" || fail "the Play 6 progress changed"
end

begin bad_ip_refused
inst '' --ip '192.0.2.50;reboot' check
expect_rc 1; expect_out "is not an address (for example --ip 192.168.x.y"
[ ! -s "$FB/adb.log" ] || fail "adb used"
end

begin help_lists_play6
bash "$INST/lumen-install.sh" --help > "$C/out" 2>&1; RC=$?
expect_rc 0; expect_out "stick DIR"; expect_out "What it writes on the Play 6: 'system'; 'gapps' also creates, resizes or deletes"
end

echo "installer play6: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
