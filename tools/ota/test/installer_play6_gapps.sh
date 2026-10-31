#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of 'lumen-install.sh gapps' and 'gsf' on the XGIMI Play 6 (installer/, profile play6): the Google
# services add-on in the logical partition lumen_gapps. On the Mac or Linux, no projector, no network, no
# Google file:
#   bash tools/ota/test/installer_play6_gapps.sh
# A fake MindTheGapps arm-ATV zip (the real member paths of tools/play6/gapps_allow.py LEAN, made-up contents)
# and its block made by tools/play6/gapps_allow.py 'gen' go into the installer copy (lib/play6/gapps_allow.txt;
# the real lib/play6/lumen-gapps.xml). The fake projector is a tree (the partition lumen_gapps under
# dev/block/mapper: a plain file until mke2fs, then a directory; /system with the overlay targets; /data,
# /cache, proc/mounts) with a mode (lumen | stock | fastboot | recovery):
#   fake adb      over Wi-Fi as for installer_play6.sh (connect / get-state / disconnect), getprop, root, push,
#                 rm / mkdir of the work folder, the pushed lib/play6/gapps_fill.sh (as root only, with the
#                 stubs of tools/ota/test/stubs plus mke2fs and e2fsck), reboot, reboot fastboot, pm path,
#                 sqlite3, and the factory reset (as root: the projector goes to its recovery, /data and /cache
#                 are erased);
#   fake fastboot devices, getvar all (the Play 6 layout plus lumen_gapps when it exists), create / resize /
#                 delete of lumen_gapps, reboot (-> Lumen OS);
#   a start       runs the image's own overlay/play6/lumen_gapps.sh on the tree (paths moved under the tree,
#                 nothing else changed) as init does at post-fs-data: sys.lumen.gapps comes from the real script.
# So every install is a round trip: installer -> gapps_fill.sh -> lumen_gapps.sh. Cases: install with the wipe
# (also in Russian), with --keep-data, over a broken or smaller partition (resize, old content gone), over one of
# the right size (no create); Google already on (nothing written); refused before anything is written (unknown
# zip, stock system, no add-on support, Google built in, no adb root, not confirmed, --zip with --remove); no
# room in super (nothing created); a failed fill (no wipe, Google stays off); a failed push; a projector left
# in fastboot mode by an earlier run; --remove with the wipe, with --keep-data, with nothing to remove; the
# status (on, none, off, broken); gsf with and without Google; Z9X untouched (the Z9X allow-list and fill
# script are not used). The full install with --gapps-zip (Lumen OS and Google services in one pass):
# a signed fake image (a throwaway test key), stock on the projector, 'flash system', lumen_gapps and the erase
# request in misc in the same fastboot session (the fake recovery erases /data and /cache before the start, the
# projector is off Wi-Fi until the user joins it again), the light first start, the fill, one factory reset;
# with the erase request refused by fastboot mode the earlier way (the first start on the old data); a run that
# finishes without Google right after the light first start (no reset); also in Russian, over Google of
# an earlier install still in use (the 'off' restart), the zip refused before anything is written, Rooted debugging
# off (adb root says "disabled by system setting": turned on when asked, or not: STOP, then the next run goes on
# from there), a run without --gapps-zip that finishes without Google, the refusals (with a command, with
# --keep-data, with --wipe=factory-reset, an install saved here without Google), no room for both in super.
# Every case fails on an unexpected adb or fastboot command, on a fastboot format, erase,
# -w, flashing, set_active, oem, boot or flash (but system and the 2048-byte erase request in misc), and on a
# logical-partition command for any partition but lumen_gapps.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../../.." && pwd)
INST=$GSI/installer
W=$(mktemp -d "${TMPDIR:-/tmp}/lumen_p6gapps.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=P6TESTSER001
ADDR=192.0.2.50:5555
CONF=$INST/lib/devices.conf
conf() { awk -v k="$1.$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2) }' "$CONF"; }
NAME=$(conf play6 NAME); MODEL=$(conf play6 MODEL); BOARD=$(conf play6 BOARD); PRODUCT=$(conf play6 FB_PRODUCT)
FW=$(conf play6 VENDOR_OK | awk '{print $1}')

mkdir -p "$W/bin" "$W/stubs" "$W/fx" "$W/keys"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/k.key" -out "$W/keys/k.pem" -subj /CN=lumen-test -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }
for f in getprop setprop log mount umount blockdev chcon chown; do cp "$HERE/stubs/$f" "$W/stubs/$f"; done
cat > "$W/stubs/mke2fs" <<'EOF'
#!/bin/sh
# stub: the last argument is the partition (a file or a directory in the test): it becomes an empty ext4
echo "mke2fs $*" >> "$Z9X_T/mke2fs.log"
for a; do d=$a; done
rm -rf "$d" && mkdir -p "$d/lost+found"
EOF
cat > "$W/stubs/e2fsck" <<'EOF'
#!/bin/sh
echo "e2fsck $*" >> "$Z9X_T/e2fsck.log"
[ ! -e "$Z9X_T/e2fsck_fail" ]
EOF
chmod 755 "$W/stubs/"*

# the fake zip and its allow-list block (tools/play6/gapps_allow.py gen), and another zip it does not list
python3 -B - "$GSI" "$W/fx" <<'PY' || exit 1
import os, sys, zipfile
gsi, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, os.path.join(gsi, "tools", "play6"))
import gapps_allow as ga
def mk(path, salt):
    with zipfile.ZipFile(path, "w") as z:
        for i, n in enumerate(ga.LEAN):
            z.writestr(n, f"{salt} {n} {i}\n" * (3 + i))
        z.writestr(ga.GAPPS_RC, "on init\n    setprop ro.com.google.gmsversion 14_test\n")
        z.writestr("system/product/priv-app/TVLauncher/TVLauncher.apk", "not listed\n")
        z.writestr("system/product/priv-app/AndroidMediaShell/AndroidMediaShell.apk", "not listed\n")
        z.writestr("META-INF/com/google/android/update-binary", "#!/sbin/sh\n")
name = "MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip"
os.makedirs(os.path.join(out, "zip")); os.makedirs(os.path.join(out, "other"))
mk(os.path.join(out, "zip", name), "a")
mk(os.path.join(out, "other", name), "b")
url = "https://github.com/MindTheGapps/14.0.0-arm-ATV/releases/download/test/" + name
block = ga.gen(os.path.join(out, "zip", name), url)
with open(os.path.join(out, "allow.txt"), "w") as f:
    f.write("# test allow-list (installer_play6_gapps.sh)\n" + "\n".join(block) + "\n")
PY
ZIP=$(ls "$W/fx/zip/"*.zip); OTHER=$(ls "$W/fx/other/"*.zip); ZNAME=$(basename "$ZIP")
ZSHA=$( (sha256sum "$ZIP" 2>/dev/null || shasum -a 256 "$ZIP") | cut -d' ' -f1)
PART=$(sed -n 's/.* part=\([0-9]*\) .*/\1/p' "$W/fx/allow.txt")
NFILES=$(awk '$1 !~ /^#/ && $1 !~ /^zip=/ && NF == 3' "$W/fx/allow.txt" | wc -l | tr -d ' ')
NDIRS=10   # folders of lumen_gapps.sh's DIRS the lean set fills

# the image's own lumen_gapps.sh with its paths moved under the test tree ($LR); the partition is a directory
sed -e 's#^G=/dev/block/mapper/lumen_gapps#G=$LR/dev/block/mapper/lumen_gapps#' -e 's#^M=/mnt/lumen_gapps#M=$LR/mnt/lumen_gapps#' \
    -e 's#/cache/lumen_gapps_off#$LR/cache/lumen_gapps_off#g' -e 's#\[ ! -b "\$G" \]#[ ! -e "$G" ]#' \
    -e 's#"/system/\$d"#"$LR/system/$d"#g' -e 's#:/system/\$d#:$LR/system/$d#g' -e 's#done_list="/system/\$d#done_list="$LR/system/$d#' \
    "$GSI/overlay/play6/lumen_gapps.sh" > "$W/lumen_gapps.sh"
for l in 'G=$LR/dev/block/mapper/lumen_gapps' 'M=$LR/mnt/lumen_gapps' '[ ! -e "$G" ]' '[ -e $LR/cache/lumen_gapps_off ]' \
    '[ ! -d "$LR/system/$d" ]' 'lowerdir=$M/$d:$LR/system/$d" "$LR/system/$d"' 'done_list="$LR/system/$d '; do
  grep -qF -- "$l" "$W/lumen_gapps.sh" || { echo "overlay/play6/lumen_gapps.sh changed ('$l' not made): update the sed of this test"; exit 1; }
done

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb of an XGIMI Play 6 over Wi-Fi (see the header of installer_play6_gapps.sh)
case "$1" in start-server|kill-server) exit 0 ;; esac   # the installer's own adb server (port 5038)
echo "$*" >> "$FB/adb.log"
mode=$(cat "$FB/mode")
up() { case "$mode" in stock|lumen) [ ! -e "$FB/net_down" ] ;; *) return 1 ;; esac; }
state() { grep -qxF "$1" "$FB/connected" || { echo missing; return; }; if up; then echo device; else echo offline; fi; }
case "$1" in
  version) echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0 ;;
  connect) # net_down with a number: Wi-Fi comes back after that many more connect attempts (the user joins Wi-Fi)
           if [ -s "$FB/net_down" ]; then n=$(cat "$FB/net_down"); if [ "$n" -le 1 ]; then rm -f "$FB/net_down"; else echo $((n - 1)) > "$FB/net_down"; fi; fi
           if up && [ "$2" = "$ADDR" ]; then grep -qxF "$2" "$FB/connected" || echo "$2" >> "$FB/connected"; echo "connected to $2"; exit 0; fi
           echo "failed to connect to '$2'"; exit 1 ;;
  disconnect) grep -vxF "$2" "$FB/connected" > "$FB/c.tmp"; mv "$FB/c.tmp" "$FB/connected"; exit 0 ;;
  devices) echo "List of devices attached"; exit 0 ;;
  mdns) echo "List of discovered mdns services"; exit 0 ;;
  -s) shift 2 ;;
  *) echo "UNEXPECTED $*" >> "$FB/unexpected"; exit 1 ;;
esac
st=$(state "$ADDR")
if [ "$1" = get-state ]; then [ "$st" = device ] && { echo device; exit 0; }; echo "error: device $st" >&2; exit 1; fi
[ "$st" = device ] || { echo "error: device '$ADDR' $st" >&2; exit 1; }
gp() { awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$T/props"; }
run_dev() {  # a script on the projector (as root only)
  [ -f "$FB/root" ] || { echo "sh: $1: Permission denied"; return 1; }
  s=$1; shift
  env LUMEN_ROOT="$DEV" Z9X_T="$T" PATH="$STUBS:$PATH" sh "$DEV$s" "$@"
}
case "$1" in
  root) [ "$(gp ro.debuggable)" = 1 ] || { echo "adbd cannot run as root in production builds"; exit 1; }
        # Developer options > Rooted debugging off; the test turns it on once it was refused (the user did)
        [ -e "$FB/root_disabled" ] && { rm -f "$FB/root_disabled"; echo "ADB Root access is disabled by system setting - enable in Settings -> System -> Developer options"; exit 1; }
        touch "$FB/root"; grep -vxF "$ADDR" "$FB/connected" > "$FB/c.tmp"; mv "$FB/c.tmp" "$FB/connected"
        echo "restarting adbd as root" ;;
  push) [ -e "$FB/push_fail" ] && case "$2" in *.zip) exit 1 ;; esac
        mkdir -p "$(dirname "$DEV$3")" && cp "$2" "$DEV$3" ;;
  reboot) rm -f "$FB/root"
          if [ "${2-}" = fastboot ]; then echo fastboot > "$FB/mode"; else sh "$FB/boot.sh"; fi ;;
  shell)
    shift
    case "$*" in
      "getprop "*) gp "$2" ;;
      "id -u") [ -e "$FB/root" ] && echo 0 || echo 2000 ;;
      "settings get system xgimi_ota_update") echo null ;;
      "pidof org.lumen.projector"|"pidof org.lumen.projector org.z9x.projector") echo 1234 ;;
      "for d in /sys/bus/usb/devices/*;"*) printf '%s\n' "usb1 1d6b:0002 09" "3-1 0e8d:7663 ff" "1-1 0951:1666 08" ;;
      "logcat -d -v brief 2>/dev/null | grep -E "*) printf '%s\n' "D/gmpf_main(  311): powerEvent:type{POWER_BATTERY_LEVEL},val{80}" "D/gmpf_main(  311): IsAdapterPowered:1" ;;
      "grep ' /mnt/media_rw/' /proc/mounts") echo "/dev/block/vold/public:8,1 /mnt/media_rw/1234-ABCD vfat rw 0 0" ;;
      "rm -rf /data/local/tmp/lumen-gapps") rm -rf "$DEV/data/local/tmp/lumen-gapps" ;;
      "mkdir -p /data/local/tmp/lumen-gapps") mkdir -p "$DEV/data/local/tmp/lumen-gapps" ;;
      "sh /data/local/tmp/lumen-gapps/fill.sh "*) shift; run_dev "$@" ;;
      "pm path com.google.android.gsf") case "$(gp sys.lumen.gapps)" in mounted:*) echo "package:/system/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk" ;; esac ;;
      sqlite3*) [ -e "$FB/root" ] && echo 3a7b11c0ffee1234 ;;
      "echo "*" > /data/local/tmp/lumen-install-wipe") t=${*#echo }; echo "${t% > /data/local/tmp/lumen-install-wipe}" > "$DEV/data/local/tmp/lumen-install-wipe" ;;
      "cat /data/local/tmp/lumen-install-wipe") cat "$DEV/data/local/tmp/lumen-install-wipe" ;;
      "cat /data/local/tmp/lumen-install-wipe 2>/dev/null || echo lumen-install-no-check-file")
        cat "$DEV/data/local/tmp/lumen-install-wipe" 2>/dev/null || echo lumen-install-no-check-file ;;
      "am broadcast --user 0 -a android.intent.action.FACTORY_RESET -p android --receiver-foreground --es android.intent.extra.REASON lumen-install")
        echo "Broadcast completed: result=0"
        if [ -e "$FB/root" ]; then
          echo recovery > "$FB/mode"; rm -f "$FB/root"; : > "$FB/connected"
          rm -rf "$DEV/data" "$DEV/cache"; mkdir -p "$DEV/data/local/tmp" "$DEV/cache"; touch "$FB/wiped"
        fi ;;
      *) echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1 ;;
    esac ;;
  *) echo "UNEXPECTED -s $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
# fake fastboot of the Play 6's fastbootd; 'getvar all' answers on stderr like the real one
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then [ "$(cat "$FB/mode")" = fastboot ] && printf '%s\tfastboot\n' "$FB_SERIAL"; exit 0; fi
[ "$1" = -s ] && shift 2
[ "$(cat "$FB/mode")" = fastboot ] || { echo "< waiting for any device >" >&2; exit 1; }
P=$DEV/dev/block/mapper/lumen_gapps
case "$1" in
  getvar) { cat "$FB/getvar"
            [ -e "$P" ] && printf '(bootloader) is-logical:lumen_gapps:yes\n(bootloader) partition-size:lumen_gapps:0x%X\n' "$(cat "$FB/size")"
            echo "all: Done"; } >&2 ;;
  create-logical-partition) [ "$2" = lumen_gapps ] && [ ! -e "$P" ] && [ ! -e "$FB/create_fail" ] || exit 1
                            : > "$P"; echo "$3" > "$FB/size" ;;
  resize-logical-partition) [ "$2" = lumen_gapps ] && [ -e "$P" ] || exit 1; echo "$3" > "$FB/size" ;;
  delete-logical-partition) [ "$2" = lumen_gapps ] && [ -e "$P" ] || exit 1; rm -rf "$P" "$FB/size" ;;
  flash) case "$2" in
           system) [ -f "$3" ] || exit 1; cp "$FB/props_lumen" "$T/base_props" ;;   # the next start is Lumen OS
           misc) [ "$(wc -c < "$3" | tr -d ' ')" = 2048 ] && [ ! -e "$FB/misc_fail" ] || exit 1; touch "$FB/bcb" ;;   # the erase request
           *) exit 1 ;;
         esac ;;
  reboot) if [ -e "$FB/bcb" ]; then   # XGIMI's recovery erases /data and /cache first; the Wi-Fi network is gone with it
            rm -rf "$DEV/data" "$DEV/cache" "$FB/bcb"; mkdir -p "$DEV/data/local/tmp" "$DEV/cache"; touch "$FB/wiped_bcb"
            cat "$FB/rejoin_after" > "$FB/net_down" 2>/dev/null || echo 20 > "$FB/net_down"
          fi
          sh "$FB/boot.sh" ;;
  *) echo "UNEXPECTED fastboot $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
printf '#!/bin/sh\nexit 1\n' > "$W/bin/debugfs"   # the image content is not read (the signed SHA256SUMS binds it)
cat > "$W/bin/date" <<'EOF'
#!/bin/sh
case "${1-}" in +%H:%M:%S) echo 12:00:00 ;; +%Y%m%d%H%M%S) echo 20261020120000 ;; *) exec /bin/date "$@" ;; esac
EOF
chmod 755 "$W/bin/"*

PROPS_LUMEN="ro.boot.xgimi.modelname=$MODEL
ro.boot.hardware=$BOARD
ro.vendor.build.version.incremental=$FW
ro.serialno=$SER
ro.debuggable=1
ro.lumen.device=play6
ro.lumen.version=1.0.5"
PROPS_STOCK="ro.boot.xgimi.modelname=$MODEL
ro.boot.hardware=$BOARD
ro.vendor.build.version.incremental=$FW
ro.serialno=$SER
ro.debuggable=0"
IMGN=lumen-os-play6-1.0.5-nogms-system.img
begin() {
  CASE=$1; CFAIL=0; BROADCAST=0; FLASHOK=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst" "$C/fb" "$C/t" "$C/state" "$C/backup" "$C/dev"
  FB=$C/fb; T=$C/t; DEV=$C/dev
  cp "$INST/lumen-install.sh" "$C/inst/"; cp -R "$INST/lib" "$INST/certs" "$C/inst/"
  printf 'DEVICE=play6\nVERSION=1.0.5\n' > "$C/inst/release.conf"
  cp "$W/fx/allow.txt" "$C/inst/lib/play6/gapps_allow.txt"
  mkdir -p "$DEV/dev/block/mapper" "$DEV/mnt" "$DEV/cache" "$DEV/data/local/tmp" "$DEV/proc" "$DEV/system/etc/xgimi"
  for d in app priv-app etc/permissions etc/sysconfig etc/default-permissions product/app product/priv-app product/overlay \
      product/etc/permissions product/etc/sysconfig product/etc/default-permissions system_ext/app system_ext/priv-app \
      system_ext/etc/permissions; do mkdir -p "$DEV/system/$d"; done
  cp "$W/lumen_gapps.sh" "$DEV/system/etc/xgimi/lumen_gapps.sh"
  printf '%s\n' "$PROPS_LUMEN" > "$T/base_props"; printf '%s\n' "$PROPS_LUMEN" > "$FB/props_lumen"
  { echo "(bootloader) is-userspace:yes"; echo "(bootloader) product:$PRODUCT"; echo "(bootloader) unlocked:yes"
    echo "(bootloader) serialno:$SER"; echo "(bootloader) slot-count:0"; echo "(bootloader) current-slot:"
    echo "(bootloader) super-partition-name:super"
    echo "(bootloader) vendor-fingerprint:/Product_test/$PRODUCT:11/RP1A.200720.011/${FW#v}:user/dev-keys"
    echo "(bootloader) is-logical:system:yes"; echo "(bootloader) is-logical:vendor:yes"; echo "(bootloader) is-logical:userdata:no"
    echo "(bootloader) partition-size:super:0xE0000000"; echo "(bootloader) partition-size:system:0x4409C000"
    echo "(bootloader) partition-size:vendor:0x79608000"; } > "$FB/getvar"
  : > "$FB/connected"; : > "$FB/adb.log"; : > "$FB/fb.log"; : > "$T/mounts"
  cat > "$FB/boot.sh" <<EOB
# a start of the fake projector: sys.* props and mounts gone, lumen_gapps.sh at post-fs-data, boot completed
cp "$T/base_props" "$T/props"; : > "$T/mounts"; : > "$DEV/proc/mounts"; rm -rf "$DEV/mnt"; mkdir -p "$DEV/mnt"
env LR="$DEV" Z9X_T="$T" PATH="$W/stubs:\$PATH" sh "$DEV/system/etc/xgimi/lumen_gapps.sh" >> "$FB/boot.out" 2>&1
case "\$(awk -F= '\$1 == "sys.lumen.gapps" { print \$2 }' "$T/props")" in
  mounted:*) echo "/dev/block/dm-9 /mnt/lumen_gapps ext4 ro,nodev,nosuid,noatime 0 0" > "$DEV/proc/mounts" ;;
esac
echo sys.boot_completed=1 >> "$T/props"
echo lumen > "$FB/mode"
EOB
  echo lumen > "$FB/mode"; echo "$ADDR" > "$FB/addr"
  sh "$FB/boot.sh"
}
inst() {  # answers args... -> the installer copy; exit status in RC, output in $C/out
  local ans=$1; shift
  printf '%b' "$ans" | env LUMEN_PLATFORM_TOOLS="$W/bin" PATH="$W/bin:$PATH" FB="$FB" T="$T" DEV="$DEV" STUBS="$W/stubs" ADDR="$ADDR" FB_SERIAL=$SER \
    LUMEN_STATE_DIR="$C/state" LUMEN_BACKUP_DIR="$C/backup" LANG="${LANG_CASE:-en_US.UTF-8}" \
    bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
partition() {  # [size]: a filled lumen_gapps (the real gapps_fill.sh as on the projector), then a start
  local w=$DEV/data/local/tmp/lumen-gapps
  : > "$DEV/dev/block/mapper/lumen_gapps"; echo "${1:-$PART}" > "$FB/size"
  mkdir -p "$w"; cp "$ZIP" "$w/gapps.zip"; cp "$INST/lib/play6/lumen-gapps.xml" "$w/"; cp "$W/fx/allow.txt" "$w/allow.txt"
  env LUMEN_ROOT="$DEV" Z9X_T="$T" PATH="$W/stubs:$PATH" sh "$INST/lib/play6/gapps_fill.sh" fill "$ZSHA" > "$C/fill.out" 2>&1 \
    || fail "setup fill: $(cat "$C/fill.out")"
  rm -rf "$w"
  sh "$FB/boot.sh"
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -qF -- "$1" "$C/out" || fail "output lacks '$1'"; }
expect_no_out() { if grep -qF -- "$1" "$C/out"; then fail "output has '$1'"; fi; }
cmds() { grep -v -e '^--version$' -e '^devices$' "$FB/fb.log" | tr '\n' ';'; }
expect_cmds() { [ "$(cmds)" = "$1" ] || fail "fastboot commands: got '$(cmds)', want '$1'"; }
gprop() { awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$T/props"; }
state() { sed -n "s/^$1=//p" "$C/state/play6/state" 2>/dev/null | tail -1; }
pushed() { grep -c " push " "$FB/adb.log" | tr -d ' '; }
end() {
  [ ! -s "$FB/unexpected" ] || fail "unexpected: $(cat "$FB/unexpected")"
  if grep -v -e "^-s $SER flash system $C/inst/$IMGN\$" -e "^-s $SER flash misc $C/state/play6/bcb.bin\$" "$FB/fb.log" | grep -qE '(^| )(format|erase|-w|flashing|set_active|--set-active|oem|boot|snapshot-update|flash)( |:|$)' \
     || { [ "$FLASHOK" = 0 ] && grep -q ' flash ' "$FB/fb.log"; }; then
    fail "forbidden fastboot command: $(cat "$FB/fb.log")"
  fi
  if grep -E -- '-logical-partition ' "$FB/fb.log" | grep -vqE -- '-logical-partition lumen_gapps( |$)'; then
    fail "a logical partition other than lumen_gapps: $(cat "$FB/fb.log")"
  fi
  if [ "$BROADCAST" = 0 ] && grep -q 'am broadcast' "$FB/adb.log"; then fail "factory reset sent"; fi
  if grep -q 'am broadcast' "$FB/adb.log" && [ "$(grep -c 'am broadcast' "$FB/adb.log")" != 1 ]; then fail "more than one factory reset"; fi
  if grep -E 'lib/gapps_allow\.txt|lib/gapps_fill\.sh' "$FB/adb.log" | grep -qv 'lib/play6/'; then fail "a Z9X file was used"; fi
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; [ ! -f "$C/out" ] || sed 's/^/     | /' "$C/out" | tail -15; fi
}
S="-s $SER"
CREATE="$S getvar all;$S create-logical-partition lumen_gapps $PART;$S reboot;"
mounted_ok() {  # after the next start: the image's lumen_gapps.sh mounts the add-on with every folder
  sh "$FB/boot.sh"
  [ "$(gprop sys.lumen.gapps)" = "mounted:$NDIRS" ] || fail "after a start sys.lumen.gapps=$(gprop sys.lumen.gapps): $(tail -3 "$FB/boot.out")"
  [ "$(gprop ro.com.google.gmsversion)" = 14_test ] || fail "gmsversion '$(gprop ro.com.google.gmsversion)'"
}

begin install_wipe
inst 'no\nERASE\n\n\nyes\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 0; BROADCAST=1
expect_cmds "$CREATE"
expect_out "$ZNAME, sha256 ${ZSHA:0:16}"; expect_out "ERASES ALL DATA"; expect_out "creating lumen_gapps ($((PART / 1048576)) MB)"
expect_out "Unplug the A-to-A cable from the projector NOW"; expect_out "lumen_gapps: $NFILES files of $ZNAME"
expect_out "asking Android for its factory reset"; expect_out "sign in to Google: Settings > Accounts > Add account"
[ "$(grep -c 'Type ERASE' "$C/out")" = 1 ] || fail "ERASE asked $(grep -c 'Type ERASE' "$C/out") times"
[ -e "$FB/wiped" ] || fail "not wiped"
[ ! -e "$DEV/cache/lumen_gapps_off" ] || fail "the off file stayed"
[ -f "$DEV/dev/block/mapper/lumen_gapps/gmsversion" ] && [ -f "$DEV/dev/block/mapper/lumen_gapps/etc/sysconfig/lumen-gapps.xml" ] \
  || fail "no gmsversion / sysconfig layer in the partition"
[ "$(cd "$DEV/dev/block/mapper/lumen_gapps" && find . -type f | wc -l | tr -d ' ')" = $((NFILES + 2)) ] || fail "files in the partition"
cmp -s "$DEV/dev/block/mapper/lumen_gapps/etc/sysconfig/lumen-gapps.xml" "$INST/lib/play6/lumen-gapps.xml" || fail "sysconfig differs"
[ ! -e "$DEV/data/local/tmp/lumen-gapps" ] || fail "the zip stayed on the projector"
grep -q "mke2fs -F -q -t ext4 -b 4096 -I 256 -m 0 -O ^has_journal,^metadata_csum -L lumen_gapps" "$T/mke2fs.log" || fail "mke2fs: $(cat "$T/mke2fs.log")"
[ -s "$T/e2fsck.log" ] || fail "no e2fsck"
grep -q "u:object_r:system_file:s0" "$T/chcon.log" || fail "no system_file label"
n_off=$(grep -n "fill.sh off" "$FB/adb.log" | cut -d: -f1); n_fb=$(grep -n "reboot fastboot" "$FB/adb.log" | cut -d: -f1)
n_fill=$(grep -n "fill.sh fill" "$FB/adb.log" | cut -d: -f1); n_bc=$(grep -n "am broadcast" "$FB/adb.log" | cut -d: -f1)
[ "$n_off" -lt "$n_fb" ] && [ "$n_fb" -lt "$n_fill" ] && [ "$n_fill" -lt "$n_bc" ] || fail "order: off $n_off, fastboot $n_fb, fill $n_fill, reset $n_bc"
[ "$(state gapps)" = "$ZNAME" ] && [ "$(state step_gapps)" = done ] && [ "$(state step_wipe)" = requested ] || fail "state: $(cat "$C/state/play6/state")"
mounted_ok
end

begin install_wipe_ru
LANG_CASE=ru_RU.UTF-8 inst 'нет\nERASE\n\n\nда\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 0; BROADCAST=1
expect_out "СТИРАЕТ ВСЕ ДАННЫЕ"; expect_out "войдите в Google: Настройки > Аккаунты > Добавить аккаунт"
if grep -q '—' "$C/out"; then fail "an em dash in the Russian text"; fi
end

begin install_keep_data
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$CREATE"
expect_out "Your data stays"; expect_out "Google services are on. Sign in: Settings > Accounts."
grep -q " reboot$" "$FB/adb.log" || fail "no adb reboot"
[ "$(gprop sys.lumen.gapps)" = "mounted:$NDIRS" ] || fail "sys.lumen.gapps=$(gprop sys.lumen.gapps)"
end

begin install_over_broken_smaller_partition
partition 104857600; echo old > "$DEV/dev/block/mapper/lumen_gapps/stray.bin"; sh "$FB/boot.sh"
[ "$(gprop sys.lumen.gapps)" = bad:stray.bin ] || fail "setup: sys.lumen.gapps=$(gprop sys.lumen.gapps)"
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$S getvar all;$S resize-logical-partition lumen_gapps $PART;$S reboot;"
[ ! -e "$DEV/dev/block/mapper/lumen_gapps/stray.bin" ] || fail "the old content stayed"
[ "$(gprop sys.lumen.gapps)" = "mounted:$NDIRS" ] || fail "sys.lumen.gapps=$(gprop sys.lumen.gapps)"
end

begin install_over_partition_of_the_right_size
partition; mkdir -p "$DEV/cache"; echo x > "$DEV/cache/lumen_gapps_off"; sh "$FB/boot.sh"
[ "$(gprop sys.lumen.gapps)" = off ] || fail "setup: sys.lumen.gapps=$(gprop sys.lumen.gapps)"
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$S getvar all;$S reboot;"
expect_out "lumen_gapps is there ($((PART / 1048576)) MB)"
[ "$(gprop sys.lumen.gapps)" = "mounted:$NDIRS" ] || fail "sys.lumen.gapps=$(gprop sys.lumen.gapps)"
end

begin already_on
partition
inst '' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 0; expect_cmds ""
expect_out "Google services are already added and on: nothing to do"
[ "$(pushed)" = 0 ] || fail "pushed"
grep -q " root$" "$FB/adb.log" && fail "adb root"
end

begin refuse_unknown_zip
inst 'no\nERASE\n' --ip 192.0.2.50 gapps --zip "$OTHER"
expect_rc 1; expect_cmds ""
expect_out "is not the MindTheGapps file this installer accepts for the $NAME"; expect_out "Nothing was written."
if grep -qvx 'version' "$FB/adb.log"; then fail "adb used: $(cat "$FB/adb.log")"; fi
end

begin refuse_z9x_zip_name
cp "$OTHER" "$C/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip"
inst '' --ip 192.0.2.50 gapps --zip "$C/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip"
expect_rc 1; expect_out "is not the MindTheGapps file this installer accepts for the $NAME"
end

begin refuse_missing_zip
inst '' --ip 192.0.2.50 gapps --zip "$C/nothing.zip"
expect_rc 1; expect_out "no file"; grep -vqx version "$FB/adb.log" && fail "adb used"
end

begin refuse_stock
sed -i.bak '/^ro\.lumen\./d' "$T/base_props"; sh "$FB/boot.sh"; sed -i.bak '/^sys\.lumen\.gapps=/d' "$T/props"
inst 'no\nERASE\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "install Lumen OS first"
[ "$(pushed)" = 0 ] || fail "pushed"
end

begin refuse_no_addon_support
sed -i.bak '/^sys\.lumen\.gapps=/d' "$T/props"
inst 'no\nERASE\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "has no Google services add-on support"
end

begin refuse_google_built_in
echo ro.com.google.gmsversion=14_202403 >> "$T/props"
inst 'no\nERASE\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "Google services built in: nothing to add"
end

begin refuse_no_root
sed -i.bak 's/^ro.debuggable=1/ro.debuggable=0/' "$T/props"
inst 'no\nERASE\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "adb root is not possible"
[ "$(pushed)" = 0 ] || fail "pushed"
end

begin refuse_not_confirmed
inst 'no\nno\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "cancelled, nothing was written"
[ "$(pushed)" = 0 ] || fail "pushed"; [ ! -e "$DEV/cache/lumen_gapps_off" ] || fail "off file written"
end

begin refuse_zip_and_remove
inst '' --ip 192.0.2.50 gapps --zip "$ZIP" --remove
expect_rc 1; expect_out "gapps: --zip or --remove, not both"; expect_cmds ""
end

begin no_room_in_super
sed -i.bak 's/^(bootloader) partition-size:super:.*/(bootloader) partition-size:super:0xBE600000/' "$FB/getvar"
inst 'no\nERASE\n\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1
expect_cmds "$S getvar all;$S reboot;"
expect_out "not enough free space in super"; expect_out "Nothing was written."
[ ! -e "$DEV/dev/block/mapper/lumen_gapps" ] || fail "partition created"
end

begin fill_fails_no_wipe
# the installer's list names another hash for one file (the zip line is right): the fill stops there
sed -i.bak '/product\/priv-app\/Tubesky\/Tubesky.apk$/s/^[0-9a-f]\{8\}/00000000/' "$C/inst/lib/play6/gapps_allow.txt"
inst 'no\nERASE\n\n\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1
expect_cmds "$CREATE"
expect_out "the Google services were not added: sha256 of product/priv-app/Tubesky/Tubesky.apk"
[ -e "$DEV/cache/lumen_gapps_off" ] || fail "the off file is gone: a half-filled partition would be mounted"
[ ! -e "$DEV/data/local/tmp/lumen-gapps" ] || fail "the zip stayed on the projector"
sh "$FB/boot.sh"; [ "$(gprop sys.lumen.gapps)" = off ] || fail "after a start sys.lumen.gapps=$(gprop sys.lumen.gapps)"
end

begin push_fails
touch "$FB/push_fail"
inst 'no\nERASE\n\n\n' --ip 192.0.2.50 gapps --zip "$ZIP"
expect_rc 1; expect_cmds "$CREATE"; expect_out "adb push of the zip failed"
end

begin left_in_fastboot_by_earlier_run
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" > "$C/state/play6/state"; echo fastboot > "$FB/mode"
inst '' gapps --zip "$ZIP"
expect_rc 1; expect_cmds "$S reboot;"
expect_out "was waiting in fastboot mode (an earlier run stopped there) and restarts now"
end

begin remove_wipe
partition
inst 'ERASE\n\n\nyes\n' --ip 192.0.2.50 gapps --remove
expect_rc 0; BROADCAST=1
expect_cmds "$S getvar all;$S delete-logical-partition lumen_gapps;$S reboot;"
expect_out "lumen_gapps deleted"; expect_out "asking Android for its factory reset"
[ ! -e "$DEV/dev/block/mapper/lumen_gapps" ] || fail "partition left"
sh "$FB/boot.sh"; [ "$(gprop sys.lumen.gapps)" = none ] || fail "sys.lumen.gapps=$(gprop sys.lumen.gapps)"
end

begin remove_keep_data
partition
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data gapps --remove
expect_rc 0
expect_cmds "$S getvar all;$S delete-logical-partition lumen_gapps;$S reboot;"
expect_out "the projector starts Lumen OS without Google services"
end

begin remove_nothing
inst '' --ip 192.0.2.50 gapps --remove
expect_rc 0; expect_cmds ""; expect_out "nothing to remove. Nothing was written."
end

begin status_on
partition
inst '' --ip 192.0.2.50 gapps
expect_rc 0; expect_cmds ""; expect_out "Google services added by you: on (14_test)"
end

begin status_none
inst '' --ip 192.0.2.50 gapps
expect_rc 0; expect_out "no Google services added. To add them: bash lumen-install.sh gapps --zip FILE"
end

begin status_off
partition; echo x > "$DEV/cache/lumen_gapps_off"; sh "$FB/boot.sh"
inst '' --ip 192.0.2.50 gapps
expect_rc 0; expect_out "a 'gapps --zip' run did not finish"
end

begin status_broken
partition; echo x > "$DEV/dev/block/mapper/lumen_gapps/stray.bin"; sh "$FB/boot.sh"
inst '' --ip 192.0.2.50 gapps
expect_rc 0; expect_out "the Google services you added cannot be used (bad:stray.bin)"
end

begin gsf_with_google
partition
inst '' --ip 192.0.2.50 gsf
expect_rc 0; expect_out "GSF ID: 3a7b11c0ffee1234"; expect_out "google.com/android/uncertified"
end

begin gsf_without_google
inst '' --ip 192.0.2.50 gsf
expect_rc 0; expect_out "nothing to register"; expect_out "gapps --zip FILE"
end

begin z9x_files_untouched
# the Play 6 gapps never reads or pushes the Z9X list or fill script: break both in the copy, it still works
echo broken > "$C/inst/lib/gapps_allow.txt"; echo broken > "$C/inst/lib/gapps_fill.sh"
inst 'WRITE\n\n\n' --ip 192.0.2.50 --keep-data gapps --zip "$ZIP"
expect_rc 0; expect_cmds "$CREATE"
end

# ---------------------------------------------------------------- the full install with --gapps-zip (one pass, one wipe)
release() {  # [stock]: a signed release image next to the installer copy (throwaway test key); the projector on stock
  cp "$W/keys/k.pem" "$C/inst/certs/play6/ota.x509.pem"
  head -c 1048576 /dev/zero > "$C/inst/$IMGN"
  printf '\123\357' | dd of="$C/inst/$IMGN" bs=1 seek=1080 conv=notrunc 2>/dev/null   # the ext4 magic
  (cd "$C/inst" && { sha256sum "$IMGN" 2>/dev/null || shasum -a 256 "$IMGN"; } > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/k.key" -out SHA256SUMS.sig SHA256SUMS)
  if [ "${1-}" = stock ]; then printf '%s\n' "$PROPS_STOCK" > "$T/base_props"; sh "$FB/boot.sh"; fi
  FLASHOK=1
}
ONE="ERASE\n\n\n"   # ERASE, cable in, cable out
FRESH="yes\n\n"     # Lumen OS seen after the erase before the first start; Enter: Wi-Fi joined, debugging on
filled() {  # the partition holds the allow-listed files, the next start mounts it, one factory reset
  [ "$(cd "$DEV/dev/block/mapper/lumen_gapps" 2>/dev/null && find . -type f | wc -l | tr -d ' ')" = $((NFILES + 2)) ] || fail "files in the partition"
  [ -e "$FB/wiped" ] || fail "not wiped"
  [ "$(grep -c 'am broadcast' "$FB/adb.log")" = 1 ] || fail "factory resets: $(grep -c 'am broadcast' "$FB/adb.log")"
  n_fill=$(grep -n "fill.sh fill" "$FB/adb.log" | cut -d: -f1); n_bc=$(grep -n "am broadcast" "$FB/adb.log" | cut -d: -f1)
  [ -n "$n_fill" ] && [ "$n_fill" -lt "$n_bc" ] || fail "order: fill $n_fill, reset $n_bc"
  mounted_ok
}

begin one_pass
release stock
inst "no\n${ONE}${FRESH}yes\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 0; BROADCAST=1
[ "$(cmds)" = "$S getvar all;$S flash system $C/inst/$IMGN;$S getvar all;$S create-logical-partition lumen_gapps $PART;$S flash misc $C/state/play6/bcb.bin;$S reboot;" ] \
  || fail "fastboot commands: $(cmds)"
expect_out "$ZNAME, sha256 ${ZSHA:0:16}"; expect_out "With --gapps-zip the same fastboot session also creates the partition lumen_gapps"
expect_out "creating lumen_gapps ($((PART / 1048576)) MB)"; expect_out "lumen_gapps: $NFILES files of $ZNAME"
# the light first start on clean data, then debugging on again for the fill, then one factory reset
expect_out "the projector is off Wi-Fi: the data was erased"; expect_out "Lumen OS runs on clean data. To copy the Google apps in"
expect_out "asking Android for its factory reset"; expect_out "Google services are in: after joining Wi-Fi"
[ -e "$FB/wiped_bcb" ] || fail "not erased before the first start"
expect_no_out "Root debugging is off"
[ "$(grep -c 'Type ERASE' "$C/out")" = 1 ] || fail "ERASE asked $(grep -c 'Type ERASE' "$C/out") times"
n_zip=$(grep -n "sha256 ${ZSHA:0:16}" "$C/out" | head -1 | cut -d: -f1); n_er=$(grep -n "Type ERASE" "$C/out" | cut -d: -f1)
[ "$n_zip" -lt "$n_er" ] || fail "the zip was checked after ERASE"
[ "$(state gapps_plan)" = "$ZSHA" ] && [ "$(state step_gapps_part)" = done ] && [ "$(state step_gapps_fill)" = done ] \
  && [ "$(state gapps)" = "$ZNAME" ] && [ "$(state step_wipe)" = requested ] || fail "state: $(cat "$C/state/play6/state")"
filled
end

begin one_pass_ru
release stock
LANG_CASE=ru_RU.UTF-8 inst "нет\n${ONE}да\n\nда\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 0; BROADCAST=1
expect_out "С --gapps-zip в той же сессии fastboot создаётся ещё раздел lumen_gapps"; expect_out "сервисы Google добавлены"
if grep -q "$(printf '\342\200\224')" "$C/out"; then fail "an em dash in the Russian text"; fi
filled
end

begin one_pass_over_google_in_use
release; partition   # Lumen OS with Google added earlier: lumen_gapps of the right size, mounted at every start
[ "$(gprop sys.lumen.gapps)" = "mounted:$NDIRS" ] || fail "setup: sys.lumen.gapps=$(gprop sys.lumen.gapps)"
inst "no\n${ONE}${FRESH}yes\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 0; BROADCAST=1
expect_out "lumen_gapps is there ($((PART / 1048576)) MB)"; expect_out "turning them off for one restart"
n_off=$(grep -n "fill.sh off" "$FB/adb.log" | cut -d: -f1); n_fill=$(grep -n "fill.sh fill" "$FB/adb.log" | cut -d: -f1)
[ -n "$n_off" ] && [ "$n_off" -lt "$n_fill" ] || fail "order: off $n_off, fill $n_fill"
filled
end

begin one_pass_zip_refused_first
release stock
inst "no\n${ONE}yes\n" --ip 192.0.2.50 --gapps-zip "$OTHER"
expect_rc 1; expect_cmds ""
expect_out "is not the MindTheGapps file this installer accepts for the $NAME"; expect_out "Nothing was written."
expect_no_out "Type ERASE"
if grep -q -- "-s " "$FB/adb.log"; then fail "the projector was used: $(cat "$FB/adb.log")"; fi
[ ! -f "$C/state/play6/state" ] || ! grep -q '^gapps_plan=.' "$C/state/play6/state" || fail "a plan saved"
end

begin one_pass_missing_zip
release stock
inst '' --ip 192.0.2.50 --gapps-zip "$C/nothing.zip"
expect_rc 1; expect_out "no file"; expect_cmds ""
end

begin one_pass_rooted_debugging_turned_on
release stock; touch "$FB/root_disabled"
inst "no\n${ONE}${FRESH}yes\nyes\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 0; BROADCAST=1
expect_out "Root debugging is off on the projector"; expect_out "Developer options > Rooted debugging"
expect_out "press Build 7 times"; expect_out "Is Rooted debugging on now?"
filled
end

begin one_pass_rooted_debugging_later
release stock; touch "$FB/root_disabled"
inst "no\n${ONE}${FRESH}no\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 1
expect_out "turn on Rooted debugging (Settings > Device Preferences > Developer options) and run the same command again: it continues here."
expect_no_out "Nothing was erased."   # the data was erased before the first start
[ ! -e "$FB/wiped" ] || fail "wiped"; [ "$(state step_boot1)" = done ] || fail "no first start saved"
[ "$(state step_gapps_part)" = done ] && [ -z "$(state step_gapps_fill)" ] && [ -z "$(state step_wipe)" ] || fail "state: $(cat "$C/state/play6/state")"
: > "$FB/fb.log"
inst "ERASE\nyes\n" --ip 192.0.2.50 --gapps-zip "$ZIP"   # the next run: the same command, Rooted debugging on now
expect_rc 0; BROADCAST=1; expect_cmds ""
expect_no_out "Root debugging is off"; expect_out "lumen_gapps: $NFILES files of $ZNAME"
expect_out "The Google apps are copied in. Next, Android's factory reset ERASES the projector once more"
filled
end

begin one_pass_finished_without_google
release stock; touch "$FB/root_disabled"
inst "no\n${ONE}${FRESH}no\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 1
inst "no\n" --ip 192.0.2.50   # without --gapps-zip: not now
expect_rc 1; expect_out "run the same command again with --gapps-zip FILE"; [ ! -e "$FB/wiped" ] || fail "wiped"
inst "yes\nERASE\nyes\n" --ip 192.0.2.50   # finish without Google: the clean system is reset once (the fill had begun)
expect_rc 0; BROADCAST=1
expect_out "this install was started with Google services (--gapps-zip), and they are not in yet"
[ -e "$FB/wiped" ] || fail "not wiped"; [ -z "$(state gapps_plan)" ] || fail "plan kept"
grep -q "fill.sh fill" "$FB/adb.log" && fail "filled"
expect_no_out "Google services are in"
end

begin one_pass_refusals
release stock
inst '' --ip 192.0.2.50 --gapps-zip "$ZIP" flash
expect_rc 1; expect_out "--gapps-zip belongs to the full install (no command)"
inst '' --ip 192.0.2.50 --gapps-zip "$ZIP" --keep-data
expect_rc 1; expect_out "--gapps-zip installs with one data wipe, not with --keep-data"
inst '' --ip 192.0.2.50 --gapps-zip "$ZIP" --wipe=factory-reset
expect_rc 1; expect_out "not with --wipe=factory-reset"
expect_cmds ""; if grep -q -- "-s " "$FB/adb.log"; then fail "the projector was used"; fi
end

begin one_pass_after_install_without_google
release stock
mkdir -p "$C/state/play6"; printf '%s\n' "serial=$SER" "ip=$ADDR" step_check=done step_flash=done step_boot1=done step_wipe=requested > "$C/state/play6/state"
inst '' --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 1; expect_out "Lumen OS was installed here without Google services. To add them: bash lumen-install.sh gapps --zip FILE"
expect_cmds ""
end

begin one_pass_finished_without_google_right_after_light_start
# after the light first start the projector never comes back on Wi-Fi: the run stops; the next run without
# --gapps-zip finishes without Google, with no factory reset at all (the data was erased before the first start)
release stock; echo 1000 > "$FB/rejoin_after"
inst "no\n${ONE}${FRESH}" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 1; expect_out "To finish without Google services: run it without --gapps-zip."
: > "$FB/adb.log"
inst "yes\n" --ip 192.0.2.50
expect_rc 0
expect_out "Finish the install without Google services?"
[ -z "$(state gapps_plan)" ] && [ "$(state step_wipe)" = requested ] || fail "state: $(cat "$C/state/play6/state")"
grep -q "am broadcast" "$FB/adb.log" && fail "a factory reset"
end

begin one_pass_light_start_refused_by_fastboot
# fastboot mode refuses the erase request: the earlier way, the first start on the old data, the fill, one reset
release stock; touch "$FB/misc_fail"
inst "no\n${ONE}yes\n" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 0; BROADCAST=1
expect_out "fastboot mode did not take the erase request"; expect_out "Lumen OS 1.0.5 started"
[ ! -e "$FB/wiped_bcb" ] || fail "erased by the request"
filled
end

begin one_pass_no_room_for_both
release stock
sed -i.bak 's/^(bootloader) partition-size:super:.*/(bootloader) partition-size:super:0x79F08000/' "$FB/getvar"
inst "no\n${ONE}" --ip 192.0.2.50 --gapps-zip "$ZIP"
expect_rc 1; expect_cmds "$S getvar all;$S reboot;"
expect_out "the image and the Google services do not fit into super together"; expect_out "Nothing was written."
end

echo "installer play6 gapps: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
