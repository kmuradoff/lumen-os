#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of the installer's device profiles (installer/lib/devices.conf) and of the XGIMI Z9X path, which
# must stay exactly as it was before the profiles came in. On the Mac or Linux, no projector, no network:
#   bash tools/ota/test/installer_profiles.sh            run every case
#   bash tools/ota/test/installer_profiles.sh --record   maintainers only: rewrite the golden files from the
#                                                        installer under test (see below)
# Part 1, Z9X golden logs: a fake Z9X (adb over the USB cable, fastbootd, getprop, getvar all) runs the Z9X
# commands that do not need the codec files: check (stock, Lumen OS, refusals), flash (full wipe, --keep-data,
# --wipe=factory-reset, cancelled), the resumed install, verify, backup, rescue, gsf, gapps, status, reset,
# unknown command / option. For each case the exit status, the whole output and every adb and fastboot
# command are compared byte for byte with tools/ota/test/golden/installer_z9x/<case>.txt, recorded from the
# installer as it was before the profiles (2026-10-13). A fake 'date' and 'df' make the output repeatable.
# LUMEN_INSTALLER=DIR tests the installer in DIR instead of installer/ (used to record from another copy).
# Part 2, profiles: lib/devices.conf is parsed strictly (a bad line, an unknown key or a missing file stops
# before anything touches the projector), its z9x lines equal the Z9X constants of both scripts, and no value of
# another projector (model code, board, firmware versions, restore checksums) appears in the scripts: those
# come only from lib/devices.conf. A Z9X install refuses an image named for another projector and a download
# meant for another projector, and points a known other projector to its own way of installing.
# Part 3, device separation: Z9X runs replayed with an unfinished XGIMI Play 6 install saved on the computer (the
# owner's case, both projectors) must match the same golden files byte for byte.
# Part 4, the installer's own Platform-Tools: every case of parts 1 and 3 runs with a checked
# tools/platform-tools (the fakes, with the marker a download leaves) and decoy adb/fastboot first on PATH that
# must never run; every adb call must reach the installer's own server port 5038, also when the environment
# names another port or server socket. Part 4 cases: a changed file in tools/platform-tools, a download that
# fails or is not Google's file (fake curl), --platform-tools with too old tools or without mke2fs, adb that
# hangs (a short time limit through LUMEN_TIMEOUT_MAX) and a fastboot flash that hangs.
# Golden files re-recorded on 2026-10-29 for the own Platform-Tools (start-server/kill-server, one version
# check per run, the Platform-Tools line); everything else in them is unchanged.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir (and, with --record,
# the golden folder).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "${LUMEN_INSTALLER:-$HERE/../../../installer}" && pwd)
GOLD=$HERE/golden/installer_z9x
RECORD=0; [ "${1-}" = --record ] && RECORD=1
W=$(mktemp -d "${TMPDIR:-/tmp}/lumen_profiles.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=ZF62TEST0009

mkdir -p "$W/bin" "$W/keys"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/k.pem" -out "$W/keys/c.pem" -subj /CN=lumen-test -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb of a Z9X on the USB cable: state in $FB (mode android|fastboot, usb = cable in, props, root)
echo "$*" >> "$FB/adb.log"
echo "${ANDROID_ADB_SERVER_PORT-none} ${ADB_SERVER_SOCKET-}" >> "$FB/port.log"
case "$1" in start-server|kill-server) exit 0 ;; esac
[ "$1" = devices ] && [ -e "$FB/hang_devices" ] && exec /bin/sleep 30
[ "$1" = version ] && { echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0; }
[ "$1" = mdns ] && { echo "List of discovered mdns services"; exit 0; }
if [ "$1" = devices ]; then
  echo "List of devices attached"
  [ -e "$FB/usb" ] && [ "$(cat "$FB/mode")" = android ] && printf '%s\tdevice\n' "$FB_SERIAL"
  echo; exit 0
fi
[ "$1" = -s ] && shift 2
[ "$(cat "$FB/mode")" = android ] && [ -e "$FB/usb" ] || { echo "adb: device '$FB_SERIAL' not found" >&2; exit 1; }
case "$1" in
  root) [ "$(awk -F= '$1 == "ro.debuggable" { print $2 }' "$FB/props")" = 1 ] || { echo "adbd cannot run as root in production builds"; exit 1; }
        touch "$FB/root"; echo "restarting adbd as root" ;;
  unroot) rm -f "$FB/root" ;;
  wait-for-device) ;;
  pull) mkdir -p "$3" 2>/dev/null; echo "pulled $2" > "$3/$(basename "$2")" ;;
  reboot) [ "${2-}" = fastboot ] && { rm -f "$FB/root"; echo fastboot > "$FB/mode"; } ;;
  shell)
    shift
    case "$*" in
      "getprop "*) awk -v k="$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$FB/props" ;;
      "id -u") [ -e "$FB/root" ] && echo 0 || echo 2000 ;;
      "ls /dev/block/mapper/") printf 'control\nsystem_b\nvendor_b\n' ;;
      "pm list packages -3") sed 's/^/package:/' "$FB/packages3" ;;
      "pm list packages") cat "$FB/packages3" "$FB/packages" | sed 's/^/package:/' ;;
      "pm path "*) grep -qx "$3" "$FB/packages3" && echo "package:/data/app/$3-1/base.apk" ;;
      "settings list "*) echo "$3_setting=1" ;;
      "dumpsys media.audio_policy") echo "Outputs dump:" ;;
      'mount | grep " /data "') echo "/dev/block/dm-9 on /data type f2fs (rw,lazytime)" ;;
      "["*) exit 1 ;;
      "sqlite3 "*) echo 3a7b11c0ffee1234 ;;
      *) echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1 ;;
    esac ;;
  *) echo "UNEXPECTED $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
# fake fastboot: 'getvar all' answers on stderr from $FB/getvar, like the real one; every command is logged
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then [ "$(cat "$FB/mode")" = fastboot ] && printf '%s\tfastboot\n' "$FB_SERIAL"; exit 0; fi
[ "$1" = -s ] && shift 2
[ "$(cat "$FB/mode")" = fastboot ] || { echo "< waiting for any device >" >&2; exit 1; }
case "$1" in
  getvar) cat "$FB/getvar" >&2 ;;
  reboot) echo android > "$FB/mode" ;;
  flash) [ -e "$FB/hang_flash" ] && exec /bin/sleep 30 ;;
esac
exit 0
EOF
mkdir -p "$W/decoy"   # adb and fastboot on PATH: the installer must never run them
printf '#!/bin/sh\necho "$(basename "$0") $*" >> "$FB/path_used"\nexit 1\n' > "$W/decoy/adb"
cp "$W/decoy/adb" "$W/decoy/fastboot"
chmod 755 "$W/decoy/adb" "$W/decoy/fastboot"
case "$(uname -s)" in Darwin) PT_OS=darwin ;; *) PT_OS=linux ;; esac
PT_SHA=$(sed -n "s/^PT_SHA_$PT_OS=//p" "$INST/lumen-install.sh")
pt_folder() {  # DIR: the fakes as a checked tools/platform-tools (the marker a real download leaves)
  mkdir -p "$1"; cp "$W/bin/adb" "$W/bin/fastboot" "$W/bin/mke2fs" "$W/bin/make_f2fs" "$1/"
  { echo "zip $PT_SHA"; for f in adb fastboot mke2fs make_f2fs; do echo "$(shasum -a 256 "$1/$f" | cut -d' ' -f1) $f"; done; } > "$1/.lumen-platform-tools"
}
cat > "$W/bin/date" <<'EOF'
#!/bin/sh
# fixed clock: the golden output does not depend on when the test runs
case "${1-}" in
  +%H:%M:%S) echo 12:00:00 ;;
  +%Y%m%d-%H%M) echo 20261013-1200 ;;
  +%Y%m%d-%H%M%S) echo 20261013-120000 ;;
  +%Y%m%d%H%M%S) echo 20261013120000 ;;
  +%s) echo 1791892800 ;;
  *) exec /bin/date "$@" ;;
esac
EOF
cat > "$W/bin/df" <<'EOF'
#!/bin/sh
printf 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk3s5 970000000 400000000 500000000 45%% /\n'
EOF
printf '#!/bin/sh\nexit 1\n' > "$W/bin/dump.erofs"         # no erofs reader: the image content is not read
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
printf '#!/bin/sh\nexit 0\n' > "$W/bin/make_f2fs"; cp "$W/bin/make_f2fs" "$W/bin/mke2fs"
chmod 755 "$W/bin/"*

STOCK='ro.boot.xgimi.modelname=G0082
ro.boot.hardware=mt9952
ro.vendor.build.version.incremental=v6.15.58
ro.boot.slot_suffix=_b
ro.debuggable=0'
LUMEN='ro.z9x.version=1.0.2
ro.z9x.gms=0
ro.z9x.build_id=lumen-1.0.2-20261010enp
ro.z9x.variant=public
ro.z9x.keys=release
ro.debuggable=1
sys.boot_completed=1
init.svc.gmpf_main=running
sys.z9x.blobs=ok
sys.z9x.gapps=none'

begin() {
  CASE=$1; CFAIL=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst/certs" "$C/fb" "$C/state" "$C/backup"
  FB=$C/fb
  cp "$INST/lumen-install.sh" "$INST/release.conf" "$C/inst/"; cp -R "$INST/lib" "$C/inst/"
  cp "$W/keys/c.pem" "$C/inst/certs/ota.x509.pem"
  printf 'LUMEN-TEST-IMAGE-Z9X' > "$C/inst/lumen-os-1.0.2-nogms-system.img"
  (cd "$C/inst" && { sha256sum lumen-os-1.0.2-nogms-system.img 2>/dev/null || shasum -a 256 lumen-os-1.0.2-nogms-system.img; } > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/k.pem" -out SHA256SUMS.sig SHA256SUMS)
  printf '%s\n' "$STOCK" > "$FB/props"
  { echo "(bootloader) is-userspace:yes"; echo "(bootloader) product:mt5877"; echo "(bootloader) unlocked:yes"
    echo "(bootloader) current-slot:b"; echo "(bootloader) snapshot-update-status:none"
    echo "(bootloader) partition-size:super:0x113400000"; echo "(bootloader) partition-type:cache:raw"
    echo "(bootloader) partition-size:system_b:0x34000000"; echo "(bootloader) is-logical:system_b:yes"
    echo "(bootloader) partition-size:vendor_b:0x5A8FC000"; echo "(bootloader) is-logical:vendor_b:yes"
    echo "(bootloader) partition-size:product_b:0x10000000"; echo "(bootloader) is-logical:product_b:yes"
    echo "(bootloader) partition-size:system_ext_b:0x8000000"; echo "(bootloader) is-logical:system_ext_b:yes"
    echo "(bootloader) partition-size:system_b-cow:0x100000"; echo "all: Done"; } > "$FB/getvar"
  echo android > "$FB/mode"; touch "$FB/usb"
  pt_folder "$C/inst/tools/platform-tools"
  : > "$FB/adb.log"; : > "$FB/fb.log"; : > "$FB/packages3"; : > "$FB/packages"
}
lumen() { printf '%s\n' "$LUMEN" >> "$FB/props"; }
setprop() { sed -i.bak "s|^$1=.*|$1=$2|" "$FB/props"; }
inst() {  # answers args... -> the installer copy; exit status in RC, output in $C/out
  local ans=$1; shift
  printf '%b' "$ans" | env PATH="$W/decoy:$W/bin:$PATH" ANDROID_ADB_SERVER_PORT=5037 ADB_SERVER_SOCKET=tcp:localhost:5037 \
    FB="$FB" FB_SERIAL=$SER LUMEN_STATE_DIR="$C/state" \
    LUMEN_BACKUP_DIR="$C/backup" LANG="${LANG_CASE:-en_US.UTF-8}" bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -qF -- "$1" "$C/out" || fail "output lacks '$1'"; }
expect_no_out() { if grep -qF -- "$1" "$C/out"; then fail "output has '$1'"; fi; }
record() {  # the case as one text: exit status, output, adb and fastboot commands (temp paths replaced)
  { echo "== exit $RC"; echo "== output"; cat "$C/out"; echo "== adb"; cat "$FB/adb.log"
    echo "== fastboot"; cat "$FB/fb.log"; echo "== unexpected"; cat "$FB/unexpected" 2>/dev/null; } | sed "s|$C|<C>|g"
}
golden() {  # compare with (or, with --record, write) the golden file of this case
  local g=$GOLD/$CASE.txt
  if [ "$RECORD" = 1 ]; then mkdir -p "$GOLD"; record > "$g"; return; fi
  [ -f "$g" ] || { fail "no golden file $g"; return; }
  record > "$C/now.txt"
  cmp -s "$g" "$C/now.txt" || fail "differs from the golden log: $(diff "$g" "$C/now.txt" | head -8 | tr '\n' '|')"
}
end() {
  [ ! -s "$FB/path_used" ] || fail "adb or fastboot from PATH ran: $(tr '\n' ';' < "$FB/path_used")"
  if [ -s "$FB/port.log" ] && grep -vqx '5038 ' "$FB/port.log"; then fail "adb outside the own server: $(sort -u "$FB/port.log" | tr '\n' ';')"; fi
  if grep -qE '(^| )(erase|-w|flashing|set_active|--set-active)( |:|$)' "$FB/fb.log"; then fail "forbidden fastboot command: $(cat "$FB/fb.log")"; fi
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; [ ! -f "$C/out" ] || sed 's/^/     | /' "$C/out" | tail -12; fi
}

# ---------------------------------------------------------------- part 1: the Z9X path, golden logs
begin z9x_check_stock
inst '' check
golden; end

begin z9x_check_stock_ru
LANG_CASE=ru_RU.UTF-8 inst '' check
golden; end

begin z9x_check_lumen
lumen; inst '' check
golden; end

begin z9x_check_firmware_refused
setprop ro.vendor.build.version.incremental v6.15.30
inst '' check
golden; end

begin z9x_check_unknown_model
setprop ro.boot.xgimi.modelname G0099; setprop ro.boot.hardware mt9999
inst '' check
golden; end

begin z9x_check_no_device
rm -f "$FB/usb"
inst '' check
golden; end

begin z9x_flash_full
inst 'yes\nERASE\n' --no-blobs flash
golden; end

begin z9x_flash_full_ru
LANG_CASE=ru_RU.UTF-8 inst 'да\nERASE\n' --no-blobs flash
golden; end

begin z9x_flash_keep_data
lumen; printf 'step_vbmeta=done\n' > "$C/state/state"
inst 'WRITE\n' --keep-data --no-blobs flash
golden; end

begin z9x_flash_factory_reset
inst 'yes\nERASE\n' --no-blobs --wipe=factory-reset flash
golden; end

begin z9x_flash_cancelled
inst 'yes\nno\n' --no-blobs flash
golden; end

begin z9x_all_resume_verify
lumen; printf '%s\n' step_check=done step_backup=skipped step_vbmeta=done step_flash=done image_gms=0 > "$C/state/state"
inst '' --no-blobs
golden; end

begin z9x_verify
lumen; printf '%s\n' org.lumen.home > "$FB/packages"
inst '' verify
golden; end

begin z9x_backup
printf '%s\n' com.example.player org.example.notes > "$FB/packages3"
inst '' backup
golden; end

begin z9x_rescue
echo fastboot > "$FB/mode"; sed -i.bak -e '/product_b/d' -e '/system_ext_b/d' "$FB/getvar"
inst 'WRITE\n' --no-blobs rescue
golden; end

begin z9x_gsf
lumen; inst '' gsf
golden; end

begin z9x_gapps_status
lumen; inst '' gapps
golden; end

begin z9x_status_reset
printf '%s\n' serial=$SER slot=_b step_check=done > "$C/state/state"
inst '' status; cp "$C/out" "$C/out1"; inst '' reset; cat "$C/out" >> "$C/out1"; inst '' status; cat "$C/out" >> "$C/out1"
mv "$C/out1" "$C/out"
golden; end

begin z9x_unknown_command
inst '' frobnicate
golden; end

begin z9x_unknown_option
inst '' --bogus check
golden; end

if [ "$RECORD" = 1 ]; then echo "recorded $PASS golden file(s) in $GOLD"; exit 0; fi

# ---------------------------------------------------------------- part 2: profiles
CONF=$INST/lib/devices.conf
conf() { awk -v k="$1.$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2) }' "$CONF"; }

begin devices_conf_strict
# every line: a comment, empty, or id.KEY=value with a known key and only safe characters
bad=$(awk '!/^#/ && !/^$/ && !/^[a-z0-9]+\.[A-Z][A-Z0-9_]*=[A-Za-z0-9 ._:\/-]*$/' "$CONF")
[ -z "$bad" ] || fail "bad lines: $bad"
[ "$(conf z9x MODEL)" = "$(sed -n 's/^MODEL_CODE=//p' "$INST/lumen-install.sh")" ] || fail "z9x.MODEL differs from MODEL_CODE"
[ "$(conf z9x BOARD)" = "$(sed -n 's/^BOARD=//p' "$INST/lumen-install.sh")" ] || fail "z9x.BOARD differs from BOARD"
[ "$(conf z9x FB_PRODUCT)" = "$(sed -n 's/^FB_PRODUCT=//p' "$INST/lumen-install.sh")" ] || fail "z9x.FB_PRODUCT differs"
[ "\"$(conf z9x VENDOR_OK)\"" = "$(sed -n 's/^VENDOR_OK=//p' "$INST/lumen-install.sh")" ] || fail "z9x.VENDOR_OK differs"
ps=$(sed -n 's/^\$ModelCode = "\([^"]*\)"; \$Board = "\([^"]*\)"; \$FbProduct = "\([^"]*\)"; \$VendorOk = @(\(.*\))$/\1 \2 \3 \4/p' "$INST/lumen-install.ps1" | tr -d '",')
[ "$ps" = "$(conf z9x MODEL) $(conf z9x BOARD) $(conf z9x FB_PRODUCT) $(conf z9x VENDOR_OK)" ] || fail "the ps1 Z9X constants differ: '$ps'"
end

begin devices_conf_no_values_in_scripts
# the other projectors' values live only in lib/devices.conf
for id in $(sed -n 's/^\([a-z0-9]*\)\.NAME=.*/\1/p' "$CONF" | grep -vx z9x); do
  for v in $(conf "$id" MODEL) $(conf "$id" BOARD) $(conf "$id" FB_PRODUCT) $(conf "$id" VENDOR_OK) \
           $(conf "$id" RESTORE_MD5 | tr ' :' '\n\n' | grep -E '^[0-9a-f]{32}$'); do
    for f in lumen-install.sh lumen-install.ps1; do
      if grep -qiF -- "$v" "$INST/$f"; then fail "$f names '$v' ($id): take it from lib/devices.conf"; fi
    done
  done
done
end

# a profile-specific run of the installer copy with a changed devices.conf: it must stop before any adb call
conf_case() {  # name sed-expression expected-text
  begin "$1"
  sed -i.bak "$2" "$C/inst/lib/devices.conf"
  inst '' --device play6 --ip 192.0.2.10 check
  expect_rc 1; expect_out "$3"
  if grep -vxF version "$FB/adb.log" | grep -q .; then fail "adb was used: $(head -3 "$FB/adb.log")"; fi
  end
}
conf_case devices_conf_bad_line '$a\
play6.MODEL G0058' "internal: lib/devices.conf line"
conf_case devices_conf_unknown_key '$a\
play6.FLASH_ALSO=vendor' "unknown key"
conf_case devices_conf_bad_value '$a\
play6.NOTE=$(reboot)' "internal: lib/devices.conf line"
conf_case devices_conf_duplicate '$a\
play6.MODEL=G9999' "internal: lib/devices.conf line"

begin devices_conf_missing
rm -f "$C/inst/lib/devices.conf"
inst '' --device play6 --ip 192.0.2.10 check
expect_rc 1; expect_out "re-download the installer"
end

begin z9x_without_devices_conf
# the Z9X never needs lib/devices.conf (an old download, or a test that copies only lib/blobs_allow.txt)
rm -f "$C/inst/lib/devices.conf"
inst '' check
expect_rc 0; expect_out "check passed; nothing was written"
end

begin z9x_refuses_image_of_another_projector
P6TOK=$(conf play6 IMAGE_TOKEN)
mv "$C/inst/lumen-os-1.0.2-nogms-system.img" "$C/inst/lumen-os-$P6TOK-1.0.2-nogms-system.img"
inst '' check
expect_rc 1; expect_out "this image is for the $(conf play6 NAME)"
grep -q 'reboot' "$FB/adb.log" && fail "adb reboot"
end

begin z9x_refuses_download_for_another_projector
echo "DEVICE=play6" >> "$C/inst/release.conf"
inst '' --device z9x check
expect_rc 1; expect_out "this download of the installer is for the $(conf play6 NAME), not for the XGIMI Z9X"
grep -vxF version "$FB/adb.log" | grep -q . && fail "adb was used"
end

begin z9x_found_with_download_for_another_projector
# no --device: the download names the other projector; with no address for it the installer looks at the USB
# cable, finds the Z9X and says which download it needs (read only)
echo "DEVICE=play6" >> "$C/inst/release.conf"
inst '' check
expect_rc 1; expect_out "the projector on the USB cable is an XGIMI Z9X, and this download of the installer is for the $(conf play6 NAME)"
if grep -vE '^(version|start-server|kill-server|devices|mdns services|-s [A-Z0-9]+ shell getprop [a-z.]+)$' "$FB/adb.log" | grep -q .; then fail "adb did more than read: $(cat "$FB/adb.log")"; fi
if grep -vx -e '--version' "$FB/fb.log" | grep -q .; then fail "fastboot used: $(cat "$FB/fb.log")"; fi
end

begin z9x_points_other_projector_to_its_way
setprop ro.boot.xgimi.modelname "$(conf play6 MODEL)"; setprop ro.boot.hardware "$(conf play6 BOARD)"
inst '' check
expect_rc 1
expect_out "this is not an XGIMI Z9X (model '$(conf play6 MODEL)', board '$(conf play6 BOARD)'). Nothing was done."
expect_out "--ip"
end

begin help_lists_both
bash "$INST/lumen-install.sh" --help > "$C/out" 2>&1; RC=$?
expect_rc 0; expect_out "rescue     Lumen OS that does not start any more"; expect_out "--ip ADDR"; expect_out "--device z9x|play6"
grep -q '^set -u' "$C/out" && fail "--help prints past the header"
end

begin no_em_dash
for f in "$INST/lumen-install.sh" "$INST/lumen-install.ps1" "$CONF"; do
  if grep -q '—' "$f"; then fail "an em dash in $(basename "$f")"; fi
done
end

begin ps1_own_platform_tools
# Windows: adb/fastboot only through Pt-Adb / Pt-Fastboot. PowerShell names ignore case: '& adb' called the helper
# function Adb instead of adb.exe, an endless recursion (fixed 2026-10-29), so no call by name may come back.
PS1=$INST/lumen-install.ps1
grep -nE '&[[:space:]]*"?(adb|fastboot)(\.exe)?"?([[:space:]]|$)' "$PS1" && fail "the ps1 calls adb or fastboot by name"
[ "$(sed -n 's/^\$PtVer = "\(.*\)"$/\1/p' "$PS1")" = "$(sed -n 's/^PT_VER=\([0-9.]*\).*/\1/p' "$INST/lumen-install.sh")" ] || fail "Platform-Tools versions differ"
for os in darwin linux; do
  grep -q "$(sed -n "s/^PT_SHA_$os=//p" "$INST/lumen-install.sh")" "$PS1" || fail "the $os sha256 differs between the scripts"
done
grep -q '^\$AdbPort = "5038"' "$PS1" && grep -q '^ADB_PORT=5038 ' "$INST/lumen-install.sh" || fail "not the own adb port 5038 in both"
end

# ---------------------------------------------------------------- part 3: a Play 6 install saved, the Z9X unchanged
# A Z9X download (release.conf without DEVICE) must not change because this computer also keeps an unfinished
# Play 6 install: the same runs as in part 1, compared with the same golden files.
p6_pending() {
  mkdir -p "$C/state/play6"
  printf '%s\n' serial=P6TESTSER001 ip=192.0.2.50:5555 step_check=done step_backup=skipped > "$C/state/play6/state"
  cp "$C/state/play6/state" "$C/p6state"
}
golden_as() {  # golden file of another case; the Play 6 progress stays untouched
  local keep=$CASE; CASE=$1; golden; CASE=$keep
  cmp -s "$C/p6state" "$C/state/play6/state" || fail "the Play 6 progress changed"
}

begin p6pending_z9x_check_stock
p6_pending; inst '' check
golden_as z9x_check_stock; end

begin p6pending_z9x_check_no_device
p6_pending; rm -f "$FB/usb"; inst '' check
golden_as z9x_check_no_device; end

begin p6pending_z9x_flash_full
p6_pending; inst 'yes\nERASE\n' --no-blobs flash
golden_as z9x_flash_full; end

begin p6pending_z9x_all_resume_verify
p6_pending; lumen; printf '%s\n' step_check=done step_backup=skipped step_vbmeta=done step_flash=done image_gms=0 > "$C/state/state"
inst '' --no-blobs
golden_as z9x_all_resume_verify; end

begin p6pending_z9x_verify
p6_pending; lumen; printf '%s\n' org.lumen.home > "$FB/packages"
inst '' verify
golden_as z9x_verify; end

begin p6pending_z9x_backup
p6_pending; printf '%s\n' com.example.player org.example.notes > "$FB/packages3"
inst '' backup
golden_as z9x_backup; end

begin p6pending_z9x_rescue
p6_pending; echo fastboot > "$FB/mode"; sed -i.bak -e '/product_b/d' -e '/system_ext_b/d' "$FB/getvar"
inst 'WRITE\n' --no-blobs rescue
golden_as z9x_rescue; end

begin p6pending_z9x_gsf
p6_pending; lumen; inst '' gsf
golden_as z9x_gsf; end

begin p6pending_z9x_gapps_status
p6_pending; lumen; inst '' gapps
golden_as z9x_gapps_status; end

# ---------------------------------------------------------------- part 4: the installer's own Platform-Tools
nothing_ran() {  # neither adb nor fastboot of the projector was used
  grep -vxE 'version|--version|start-server|kill-server' "$FB/adb.log" "$FB/fb.log" | grep -q . && fail "the projector was used: $(cat "$FB/adb.log" "$FB/fb.log" | tr '\n' ';')"
  return 0
}
fake_curl() {  # what: junk|fail -> a curl on PATH that writes junk to the -o file, or cannot connect
  mkdir -p "$C/curl"
  if [ "$1" = junk ]; then
    printf '#!/bin/sh\necho "curl $*" >> "%s/curl.log"\nwhile [ $# -gt 1 ]; do [ "$1" = -o ] && printf "NOT-GOOGLE" > "$2"; shift; done\nexit 0\n' "$FB" > "$C/curl/curl"
  else
    printf '#!/bin/sh\necho "curl $*" >> "%s/curl.log"\nexit 6\n' "$FB" > "$C/curl/curl"
  fi
  chmod 755 "$C/curl/curl"
}
inst_path() {  # PATH answers args...: like inst, with another PATH in front
  local pre=$1 ans=$2; shift 2
  printf '%b' "$ans" | env PATH="$pre:$W/decoy:$W/bin:$PATH" FB="$FB" FB_SERIAL=$SER LUMEN_STATE_DIR="$C/state" \
    LUMEN_BACKUP_DIR="$C/backup" LANG=en_US.UTF-8 ${TMAX:+LUMEN_TIMEOUT_MAX=$TMAX} bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}

begin pt_changed_file_not_used
# a file in tools/platform-tools differs from the marker: not used, a new download; that one is not Google's file
printf '\n# changed\n' >> "$C/inst/tools/platform-tools/adb"
fake_curl junk; inst_path "$C/curl" '' check
expect_rc 1; expect_out "downloading Google's Android SDK Platform-Tools"; expect_out "is not Google's file (sha256 differs): removed, not used"
grep -q "dl.google.com/android/repository/platform-tools_r[0-9.]*-$PT_OS.zip" "$FB/curl.log" || fail "not the pinned URL: $(cat "$FB/curl.log")"
ls "$C/inst/tools/"*.part >/dev/null 2>&1 && fail "the partial download was kept"
nothing_ran; [ -s "$FB/adb.log" ] && fail "adb ran: $(cat "$FB/adb.log")"
end

begin pt_download_fails
rm -rf "$C/inst/tools"; fake_curl fail; inst_path "$C/curl" '' check
expect_rc 1; expect_out "the download failed (no internet?)"; expect_out "--platform-tools DIR"
[ -s "$FB/adb.log" ] && fail "adb ran: $(cat "$FB/adb.log")"
end

begin pt_given_dir
# --platform-tools DIR: used without the marker, version checked, nothing downloaded
rm -rf "$C/inst/tools"; mkdir -p "$C/pt"; cp "$W/bin/adb" "$W/bin/fastboot" "$W/bin/mke2fs" "$W/bin/make_f2fs" "$C/pt/"
fake_curl fail; inst_path "$C/curl" '' --platform-tools "$C/pt" check
expect_rc 0; expect_out "Platform-Tools 35.0.2 ($C/pt), own adb server on port 5038"; expect_out "check passed"
[ -e "$FB/curl.log" ] && fail "downloaded: $(cat "$FB/curl.log")"
end

begin pt_given_dir_too_old
mkdir -p "$C/pt"; sed 's/35\.0\.2/34.0.5/' "$W/bin/fastboot" > "$C/pt/fastboot"; cp "$W/bin/adb" "$C/pt/"; chmod 755 "$C/pt/fastboot"
inst_path "$C/pt" '' --platform-tools "$C/pt" check
expect_rc 1; expect_out "too old or does not run: need Platform-Tools 35 or newer. Nothing was done."
nothing_ran
end

begin pt_given_dir_without_mke2fs
mkdir -p "$C/pt"; cp "$W/bin/adb" "$W/bin/fastboot" "$C/pt/"
inst_path "$C/pt" 'yes\nERASE\n' --platform-tools "$C/pt" --no-blobs flash
expect_rc 1; expect_out "make_f2fs/mke2fs missing next to fastboot ($C/pt)"
nothing_ran
end

begin pt_adb_hangs
# 'adb devices' hangs (the 2026-10-29 Windows report): stopped at the time limit, the own server stopped
touch "$FB/hang_devices"
TMAX=2 inst_path /nonexistent '' check
expect_rc 1; expect_out "STOP: 'adb devices' did not answer within 2 s and was stopped"
[ "$(tail -n 1 "$FB/adb.log")" = kill-server ] || fail "the own adb server was not stopped: $(tr '\n' ';' < "$FB/adb.log")"
grep -q 'check passed' "$C/out" && fail "went on"
[ "$(tr '\n' ' ' < "$FB/adb.log")" = "version start-server devices kill-server " ] || fail "adb did more: $(tr '\n' ';' < "$FB/adb.log")"
end

begin pt_flash_hangs
touch "$FB/hang_flash"
TMAX=2 inst_path /nonexistent 'yes\nERASE\n' --no-blobs flash
expect_rc 1; expect_out "did not finish in 2 s and was stopped: the write is incomplete"
expect_out "system was not written; the projector still has its old system"
grep -q 'format' "$FB/fb.log" && fail "wiped after a failed write: $(tr '\n' ';' < "$FB/fb.log")"
end

begin pt_reused_without_download
fake_curl fail; inst_path "$C/curl" '' check
expect_rc 0; expect_out "Platform-Tools 35.0.2 (tools/platform-tools, checked), own adb server on port 5038"
[ -e "$FB/curl.log" ] && fail "downloaded again: $(cat "$FB/curl.log")"
end

echo "installer profiles: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
