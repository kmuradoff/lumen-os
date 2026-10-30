#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of 'lumen-install.sh gapps' (installer/): the Google services add-on of Lumen OS without Google.
# On the Mac, no projector, no network, no Google file:
#   bash tools/ota/test/installer_gapps.sh
# gapps_fixture.py makes a fake MindTheGapps zip (real member paths, made-up contents) and its test
# allow-list; the installer copy and the fake projector both get that list. The fake projector is a tree
# (its image pieces: the real z9x_gapps.sh, the sysconfig layer, the overlay targets; /data, /metadata, the
# partitions as directories under dev/block/mapper) with a mode (android | fastboot):
#   fake adb      devices / getprop / root / push / reboot, and runs the pushed gapps_fill.sh and the image's
#                 z9x_gapps.sh 'check' on that tree with the stubs of tools/ota/test/stubs (only as root);
#   fake fastboot getvar all (a base answer plus every z9x_gapps_* partition), create / resize / delete /
#                 format of partitions, reboot;
#   a reboot      runs the real z9x_gapps.sh (boot) on the tree, as init would at post-fs-data.
# So every install case is a round trip: installer -> gapps_fill.sh -> z9x_gapps.sh. Cases: install with the
# wipe, with --keep-data, with --wipe=factory-reset, over an older partition (resize, the 'off' marker
# removed), over an add-on not in use (the --keep-data sign-out notice); the same add-on already on (nothing
# written); refused before anything is written (unknown zip, Google edition, stock, no adb root, an image that
# does not list the zip, no room in super); a failed format (partition deleted again); a failed fill (no
# wipe); --remove with the wipe, with --keep-data, from fastboot mode only, with nothing to remove; 'gapps'
# turning the add-on on again, its 'bad:slow' advice; 'gsf' with the add-on; --help. The full install with
# --gapps-zip (Lumen OS and Google services in ONE fastbootd visit, one wipe): a signed fake system image (a real
# small erofs with the image's z9x_gapps.sh and the test allow-list, a throwaway test key), stock on the projector,
# the add-on image built on the computer by the installer (the real erofs-utils of installer/tools/erofs-utils;
# its reference line in lib/gapps_images.txt made by the independent Python writer of tools/ota/gapps_allow.py),
# then one session: system, z9x_gapps_<slot> created and flashed (the fake fastboot unpacks the erofs image into
# the partition directory with fsck.erofs), the wipe, and the first start finds Google on (z9x_gapps.sh mounts it
# as erofs); never adb root, push or a start on the old data. Also in Russian, over an old ext4 partition
# (resized), no room in super (installed without Google), a failed flash (partition deleted, without Google),
# fastbootd gone during the flash (nothing erased; the same command again writes everything, the built image
# reused), an older installer's install waiting for its fill (finished with the flash), and refusals before
# anything is written: the zip, an image with Google built in, an image older than the erofs support, an image
# that does not list the zip, no erofs-utils for the computer, an add-on image that is not the checked one.
# Every case fails on an unexpected adb or fastboot command and on any fastboot erase / -w / flashing, or a
# flash other than system and z9x_gapps_<slot> (the image the installer built).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
IMGDIR=$(cd "$HERE/../image" && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/z9x_gapps_inst.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=ZF62TEST0004
S="-s $SER"
IMGN=lumen-os-1.0.7-nogms-system.img   # the release image of the --gapps-zip cases

mkdir -p "$W/bin" "$W/stubs" "$W/keys"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/k.key" -out "$W/keys/k.pem" -subj /CN=lumen-test -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }
for f in getprop setprop log timeout mount umount blockdev chcon chown; do cp "$HERE/stubs/$f" "$W/stubs/$f"; done
chmod 755 "$W/stubs/"*
python3 "$HERE/gapps_fixture.py" make "$IMGDIR/gapps_allow.txt" "$W/fx" || exit 1
ZIP=$(ls "$W/fx/zip/"*.zip); OTHER=$(ls "$W/fx/other/"*.zip)
NAME=$(basename "$ZIP")
BID=lumen-1.0.1-20261009fnp
NFILES=$(awk '$1 !~ /^#/ && $1 !~ /^zip=/ && NF == 3' "$W/fx/allow.txt" | wc -l | tr -d ' ')
ZSHA=$(sha256sum "$ZIP" | cut -d' ' -f1)
# the installer's own erofs-utils for this computer, and the reference line of the fake zip's add-on image (the
# independent Python tar writer of tools/ota/gapps_allow.py + the same mkfs.erofs)
case "$(uname -s)" in Darwin) EP=darwin ;; *) case "$(uname -m)" in x86_64|amd64) EP=linux-x86_64 ;; *) EP=linux-aarch64 ;; esac ;; esac
EU=$INST/tools/erofs-utils/$EP
[ -x "$EU/mkfs.erofs" ] || { echo "no installer/tools/erofs-utils/$EP (build it: tools/ota/build_erofs_utils.sh)"; exit 1; }
python3 "$HERE/../gapps_allow.py" image "$ZIP" --allow "$W/fx/allow.txt" --mkfs "$EU/mkfs.erofs" --out "$W/ref.img" > "$W/gapps_images.txt" \
  || { echo "gapps_allow.py image failed"; exit 1; }
GSHA=$(awk '{ print $4 }' "$W/gapps_images.txt"); GSIZE=$(awk '{ print $5 }' "$W/gapps_images.txt")
NEED=$(( (GSIZE + 1048575) / 1048576 * 1048576 ))

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb (see the header of installer_gapps.sh)
case "$1" in start-server|kill-server) exit 0 ;; esac   # the installer's own adb server (port 5038)
echo "$*" >> "$FB/adb.log"
[ "$1" = version ] && { echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then
  echo "List of devices attached"
  [ "$(cat "$FB/mode")" = android ] && [ ! -e "$FB/noadb" ] && printf '%s\tdevice\n' "$FB_SERIAL"
  echo; exit 0
fi
[ "$1" = -s ] && shift 2
[ "$(cat "$FB/mode")" = android ] && [ ! -e "$FB/noadb" ] || { echo "adb: device '$FB_SERIAL' not found" >&2; exit 1; }
run_dev() {  # script on the projector (as root only)
  [ -f "$FB/root" ] || { echo "sh: $1: Permission denied"; return 1; }
  s=$1; shift
  env Z9X_ROOT="$DEV" Z9X_T="$T" PATH="$STUBS:$PATH" sh "$DEV$s" "$@"
}
case "$1" in
  root) [ -e "$FB/root_disabled" ] && { rm -f "$FB/root_disabled"; echo "ADB Root access is disabled by system setting - enable in Settings -> System -> Developer options"; exit 1; }
        touch "$FB/root"; echo "restarting adbd as root" ;;
  unroot) rm -f "$FB/root" ;;
  wait-for-device) ;;
  push) [ -e "$FB/push_fail" ] && case "$2" in *.zip) exit 1 ;; esac
        mkdir -p "$(dirname "$DEV$3")" && cp "$2" "$DEV$3" ;;
  reboot) if [ "${2-}" = fastboot ]; then rm -f "$FB/root"; echo fastboot > "$FB/mode"; else sh "$FB/boot.sh"; fi ;;
  shell)
    shift
    case "$1" in
      getprop) awk -v k="$2" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$T/props" ;;
      id) [ -f "$FB/root" ] && echo 0 || echo 2000 ;;
      ls) [ "$2" = /dev/block/mapper/ ] || { echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1; }
          printf 'control\nsystem_b\nvendor_b\n' ;;
      cat) [ "$2" = /system/etc/z9x/gapps_allow.txt ] || { echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1; }
           cat "$DEV$2" ;;
      rm) [ "$2" = -rf ] || { echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1; }
          shift 2
          for x in "$@"; do case "$x" in /data/local/tmp/lumen-gapps|/metadata/z9x_gapps) rm -rf "$DEV$x" ;;
            *) echo "UNEXPECTED shell rm -rf $x" >> "$FB/unexpected"; exit 1 ;; esac; done ;;
      mkdir) [ "$2 $3" = "-p /data/local/tmp/lumen-gapps" ] || { echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1; }
             mkdir -p "$DEV$3" ;;
      sh) case "$2" in /data/local/tmp/lumen-gapps/fill.sh|/system/etc/z9x/z9x_gapps.sh) ;;
            *) echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1 ;; esac
          shift; run_dev "$@" ;;
      pm) [ "$2" = path ] && grep -qx "$3" "$FB/packages" 2>/dev/null && echo "package:/product/priv-app/x/$3.apk" ;;
      sqlite3*) echo 3a7b11c0ffee1234 ;;
      *) echo "UNEXPECTED shell $*" >> "$FB/unexpected"; exit 1 ;;
    esac ;;
  *) echo "UNEXPECTED $*" >> "$FB/unexpected"; exit 1 ;;
esac
EOF
cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
# fake fastboot (see the header of installer_gapps.sh); 'getvar all' answers on stderr like the real one
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then [ "$(cat "$FB/mode")" = fastboot ] && printf '%s\tfastboot\n' "$FB_SERIAL"; exit 0; fi
[ "$1" = -s ] && shift 2
[ "$(cat "$FB/mode")" = fastboot ] || { echo "< waiting for any device >" >&2; exit 1; }
MAP=$DEV/dev/block/mapper
case "$1" in
  getvar)
    { cat "$FB/getvar"
      for d in "$MAP"/z9x_gapps_*; do
        [ -d "$d" ] || continue; n=${d##*/}
        printf '(bootloader) partition-size:%s:0x%X\n(bootloader) is-logical:%s:yes\n' "$n" "$(cat "$FB/size.$n")" "$n"
      done
      echo "all: Done"; } >&2 ;;
  create-logical-partition) [ -e "$FB/create_fail" ] && exit 1; [ ! -d "$MAP/$2" ] || exit 1
                            mkdir -p "$MAP/$2"; echo "$3" > "$FB/size.$2" ;;
  resize-logical-partition) [ -d "$MAP/$2" ] || exit 1; echo "$3" > "$FB/size.$2" ;;
  delete-logical-partition) [ -d "$MAP/$2" ] || exit 1; rm -rf "$MAP/$2" "$MAP/$2.fs" "$FB/size.$2" ;;
  format:ext4) [ -e "$FB/format_fail" ] && exit 1
               case "$2" in z9x_gapps_*) [ -d "$MAP/$2" ] || exit 1; rm -rf "$MAP/$2" "$MAP/$2.fs"; mkdir -p "$MAP/$2/lost+found" ;; cache) ;; *) exit 1 ;; esac ;;
  --fs-options=casefold,projid) [ "$2 $3" = "format:f2fs userdata" ] || exit 1; rm -rf "$DEV/data"; mkdir -p "$DEV/data/local/tmp"
                                [ ! -e "$FB/noadb_after_wipe" ] || touch "$FB/noadb" ;;   # USB debugging is off after a wipe
  format:f2fs) [ "$2" = metadata ] || exit 1; rm -rf "$DEV/metadata"; mkdir -p "$DEV/metadata" ;;
  flash) case "$2" in
           system) [ -f "$3" ] || exit 1; cp "$FB/props_lumen" "$T/props" ;;   # the next start is Lumen OS
           z9x_gapps_*)   # the erofs add-on image: unpacked into the partition directory, marked erofs for the mount stub
             [ -d "$MAP/$2" ] && [ -f "$3" ] || exit 1
             [ -e "$FB/gflash_fail" ] && exit 1
             if [ -e "$FB/drop" ]; then rm -f "$FB/drop"; sh "$FB/boot.sh"; exit 1; fi   # fastbootd ends: the old system starts
             [ "$(wc -c < "$3" | tr -d ' ')" -le "$(cat "$FB/size.$2")" ] || exit 1
             rm -rf "$MAP/$2"; "$EU/fsck.erofs" --extract="$MAP/$2" "$3" >/dev/null 2>&1 || exit 1
             echo erofs > "$MAP/$2.fs" ;;
           *) exit 1 ;;
         esac ;;
  reboot) if [ -e "$FB/bootloop" ]; then echo fastboot > "$FB/mode"; else sh "$FB/boot.sh"; fi ;;
  *) echo "UNEXPECTED fastboot $*" >> "$FB/unexpected"; exit 1 ;;
esac
exit 0
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
printf '#!/bin/sh\nexit 0\n' > "$W/bin/make_f2fs"; cp "$W/bin/make_f2fs" "$W/bin/mke2fs"
printf '#!/bin/sh\nexit 1\n' > "$W/bin/dump.erofs"   # no erofs reader: the image content is not read
chmod 755 "$W/bin/"*

begin() {
  CASE=$1; CFAIL=0; FLASHOK=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst" "$C/fb" "$C/t" "$C/state" "$C/backup" "$C/dev"
  FB=$C/fb; T=$C/t; DEV=$C/dev
  cp "$INST/lumen-install.sh" "$INST/release.conf" "$C/inst/"; cp -R "$INST/lib" "$INST/certs" "$C/inst/"
  cp "$W/fx/allow.txt" "$C/inst/lib/gapps_allow.txt"
  mkdir -p "$DEV/system/etc/z9x/gapps/sysconfig" "$DEV/dev/block/mapper" "$DEV/mnt" "$DEV/metadata" "$DEV/data/local/tmp" \
    "$DEV/system/etc/sysconfig" "$DEV/system/product" "$DEV/system/system_ext/priv-app" "$DEV/system/system_ext/etc/permissions" \
    "$DEV/system/app" "$DEV/system/etc/permissions"
  cp "$W/fx/allow.txt" "$DEV/system/etc/z9x/gapps_allow.txt"
  cp "$IMGDIR/z9x_gapps.sh" "$DEV/system/etc/z9x/z9x_gapps.sh"
  cp "$IMGDIR/z9x-gapps-sysconfig.xml" "$DEV/system/etc/z9x/gapps/sysconfig/z9x-gapps.xml"
  printf '%s\n' ro.boot.xgimi.modelname=G0082 ro.boot.hardware=mt9952 ro.vendor.build.version.incremental=v6.15.58 \
    ro.boot.slot_suffix=_b ro.z9x.version=1.0.1 ro.z9x.build_id=$BID ro.z9x.gms=0 ro.debuggable=1 sys.boot_completed=1 \
    sys.z9x.gapps=none > "$T/props"
  cp "$T/props" "$FB/props_lumen"
  { echo "(bootloader) is-userspace:yes"; echo "(bootloader) product:mt5877"; echo "(bootloader) unlocked:yes"
    echo "(bootloader) current-slot:b"; echo "(bootloader) snapshot-update-status:none"
    echo "(bootloader) partition-size:super:0x113400000"; echo "(bootloader) partition-type:cache:raw"
    echo "(bootloader) partition-size:system_b:0x34000000"; echo "(bootloader) is-logical:system_b:yes"
    echo "(bootloader) partition-size:vendor_b:0x5A8FC000"; echo "(bootloader) is-logical:vendor_b:yes"
    echo "(bootloader) partition-size:z9x_blobs_b:0x400000"; echo "(bootloader) is-logical:z9x_blobs_b:yes"; } > "$FB/getvar"
  echo android > "$FB/mode"
  : > "$FB/adb.log"; : > "$FB/fb.log"; : > "$T/mounts"
  cat > "$FB/boot.sh" <<EOF
# a start of the fake projector: sys.* props and mounts gone, z9x_gapps.sh at post-fs-data, boot completed
awk '!/^sys\\./' "$T/props" > "$T/props.tmp" && mv "$T/props.tmp" "$T/props"
: > "$T/mounts"; rm -rf "$DEV/mnt"; mkdir -p "$DEV/mnt"; rm -f "$FB/root"
env Z9X_ROOT="$DEV" Z9X_T="$T" PATH="$W/stubs:\$PATH" sh "$DEV/system/etc/z9x/z9x_gapps.sh" >> "$FB/boot.out" 2>&1
echo sys.boot_completed=1 >> "$T/props"
echo android > "$FB/mode"
EOF
}
partition() {  # a filled partition z9x_gapps_b (the real gapps_fill.sh, as on the projector)
  mkdir -p "$DEV/dev/block/mapper/z9x_gapps_b/lost+found" "$DEV/data/local/tmp/lumen-gapps"
  echo "${1:-536870912}" > "$FB/size.z9x_gapps_b"
  cp "$ZIP" "$DEV/data/local/tmp/lumen-gapps/gapps.zip"
  env Z9X_ROOT="$DEV" Z9X_T="$T" PATH="$W/stubs:$PATH" sh "$INST/lib/gapps_fill.sh" fill "$(sha256sum "$ZIP" | cut -d' ' -f1)" > "$C/fill.out" 2>&1 \
    || fail "setup fill: $(cat "$C/fill.out")"
  rm -rf "$DEV/data/local/tmp/lumen-gapps"
}
inst() {  # answers args... -> runs the installer copy; exit status in RC, output in $C/out
  local ans=$1; shift
  printf '%b' "$ans" | env LUMEN_PLATFORM_TOOLS="$W/bin" PATH="$W/bin:$PATH" FB="$FB" T="$T" DEV="$DEV" STUBS="$W/stubs" FB_SERIAL=$SER EU="$EU" \
    LUMEN_STATE_DIR="$C/state" LUMEN_BACKUP_DIR="$C/backup" LANG="${LANG_CASE:-en_US.UTF-8}" \
    bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
cmds() { grep -v -e '^--version$' -e '^devices$' "$FB/fb.log" | tr '\n' ';'; }
expect_cmds() { [ "$(cmds)" = "$1" ] || fail "fastboot commands: got '$(cmds)', want '$1'"; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -qF -- "$1" "$C/out" || fail "output lacks '$1'"; }
gprop() { awk -v k="$1" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$T/props"; }
pushed() { grep -c "^-s $SER push " "$FB/adb.log" | tr -d ' '; }
end() {
  [ ! -s "$FB/unexpected" ] || fail "unexpected: $(cat "$FB/unexpected")"
  if grep -v -e "^-s $SER flash system $C/inst/$IMGN\$" -e "^-s $SER flash $P $C/state/gapps/z9x_gapps-[0-9a-f]*\.img\$" "$FB/fb.log" \
       | grep -qE '(^| )(erase|-w|flashing|set_active|--set-active|flash)( |:|$)' \
     || { [ "$FLASHOK" = 0 ] && grep -q ' flash ' "$FB/fb.log"; }; then fail "forbidden fastboot command: $(cat "$FB/fb.log")"; fi
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -15; fi
}
P=z9x_gapps_b
CREATE="$S getvar all;$S create-logical-partition $P 536870912;$S format:ext4 $P;$S reboot;"
WIPE="$S getvar all;$S --fs-options=casefold,projid format:f2fs userdata;$S format:f2fs metadata;$S format:ext4 cache;$S reboot;"

begin install_wipe
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$CREATE$WIPE"
expect_out "$NAME, sha256"; expect_out "ERASES ALL DATA"; expect_out "$P: $NFILES files of $NAME"
expect_out "the setup now has a Google sign-in step"
[ -f "$DEV/dev/block/mapper/$P/MANIFEST" ] || fail "no MANIFEST in the partition"
[ "$(gprop sys.z9x.gapps)" = ok ] || fail "after the last start sys.z9x.gapps=$(gprop sys.z9x.gapps): $(cat "$FB/boot.out")"
[ "$(gprop ro.com.google.gmsversion)" = 14_test ] || fail "gmsversion $(gprop ro.com.google.gmsversion)"
grep -q "^-s $SER shell rm -rf /data/local/tmp/lumen-gapps /metadata/z9x_gapps$" "$FB/adb.log" || fail "the zip / marker were not removed"
[ "$(pushed)" = 3 ] || fail "$(pushed) pushes, want 3 (fill.sh, fill.sh, the zip)"
grep -qx "step_gapps=done" "$C/state/state" && grep -qx "gapps=$NAME" "$C/state/state" || fail "state: $(cat "$C/state/state")"
end

begin install_wipe_ru
LANG_CASE=ru_RU.UTF-8 inst 'нет\nERASE\n' gapps --zip "$ZIP"
expect_rc 0
expect_out "СТИРАЕТ ВСЕ ДАННЫЕ"; expect_out "в настройке теперь есть шаг входа в Google"
if grep -q '—' "$C/out"; then fail "an em dash in the Russian text"; fi
end

begin install_keep_data
inst 'WRITE\n' --keep-data gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$CREATE"
expect_out "Your data stays"; expect_out "Google services are on"
grep -q "signs you out of Google" "$C/out" && fail "the replace notice on a first install"
grep -q "^-s $SER reboot$" "$FB/adb.log" || fail "no adb reboot"
end

begin already_on
# the same zip already on: nothing to do, nothing written, no adb root, no question
partition; sh "$FB/boot.sh"
inst '' --keep-data gapps --zip "$ZIP"
expect_rc 0; expect_cmds ""
expect_out "are already installed and on: nothing to do. Nothing was written."
[ "$(pushed)" = 0 ] || fail "pushed"
grep -q "^-s $SER root$" "$FB/adb.log" && fail "adb root"
[ "$(gprop sys.z9x.gapps)" = ok ] || fail "sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin replace_keep_data_notice
# an add-on that is there but not in use: it is written again, with the notice that Google signs out
partition; sed -i.bak 's/^sys.z9x.gapps=.*/sys.z9x.gapps=bad:size/' "$T/props"
inst 'WRITE\n' --keep-data gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$S getvar all;$S resize-logical-partition $P 536870912;$S format:ext4 $P;$S reboot;"
expect_out "Google services are already installed: replacing them signs you out of Google, and the GSF ID must be registered again."
[ "$(gprop sys.z9x.gapps)" = ok ] || fail "sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin install_factory_reset
inst 'WRITE\n' --wipe=factory-reset gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$CREATE"
expect_out "Settings > Device preferences > Reset > Factory reset"
end

begin install_over_old_partition
partition 268435456; echo "old" > "$DEV/dev/block/mapper/$P/old.apk"
mkdir -p "$DEV/metadata/z9x_gapps"; echo "3 _b $BID" > "$DEV/metadata/z9x_gapps/off"
inst 'WRITE\n' --keep-data gapps --zip "$ZIP"
expect_rc 0
expect_cmds "$S getvar all;$S resize-logical-partition $P 536870912;$S format:ext4 $P;$S reboot;"
[ ! -e "$DEV/dev/block/mapper/$P/old.apk" ] || fail "the old content stayed"
[ ! -e "$DEV/metadata/z9x_gapps" ] || fail "the 'off' marker stayed"
[ "$(gprop sys.z9x.gapps)" = ok ] || fail "sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin refuse_unknown_zip
inst 'no\nERASE\n' gapps --zip "$OTHER"
expect_rc 1; expect_cmds ""
expect_out "is not the MindTheGapps file this installer accepts"; expect_out "Nothing was written."
[ "$(pushed)" = 0 ] || fail "pushed"
end

begin refuse_missing_zip
inst 'no\nERASE\n' gapps --zip "$C/nothing.zip"
expect_rc 1; expect_cmds ""; expect_out "no file"
end

begin refuse_google_edition
sed -i.bak '/^ro\.z9x\.gms=/d' "$T/props"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "Google services built in: nothing to add"
end

begin refuse_stock
sed -i.bak -e '/^ro\.z9x\./d' "$T/props"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "install Lumen OS without Google first"
end

begin refuse_no_root
sed -i.bak 's/^ro.debuggable=1/ro.debuggable=0/' "$T/props"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "adb root is not possible"
[ "$(pushed)" = 0 ] || fail "pushed"
end

begin refuse_image_without_the_zip
grep '^#' "$W/fx/allow.txt" > "$DEV/system/etc/z9x/gapps_allow.txt"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "does not accept this file yet"
end

begin refuse_not_confirmed
inst 'no\nno\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds ""; expect_out "cancelled, nothing was written"
[ "$(pushed)" = 0 ] || fail "pushed"
end

begin refuse_no_room_in_super
sed -i.bak 's/^(bootloader) partition-size:super:.*/(bootloader) partition-size:super:0xA0000000/' "$FB/getvar"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds "$S getvar all;$S reboot;"
expect_out "not enough free space in super"; expect_out "Nothing was written."
[ ! -e "$DEV/dev/block/mapper/$P" ] || fail "partition created"
[ ! -e "$DEV/data/local/tmp/lumen-gapps" ] || fail "files left on the projector"
end

begin format_fails
touch "$FB/format_fail"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1
expect_cmds "$S getvar all;$S create-logical-partition $P 536870912;$S format:ext4 $P;$S delete-logical-partition $P;$S reboot;"
expect_out "could not be formatted and was removed again"
[ ! -e "$DEV/dev/block/mapper/$P" ] || fail "partition left"
end

begin fill_fails_no_wipe
# the projector's list names another hash for one file: the fill stops there, nothing is wiped
sed -i.bak '/product\/app\/talkback\/talkback.apk$/s/^[0-9a-f]\{8\}/00000000/' "$DEV/system/etc/z9x/gapps_allow.txt"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1
expect_cmds "$CREATE"
expect_out "the Google services were not added: sha256 of product/app/talkback/talkback.apk"
[ ! -e "$DEV/dev/block/mapper/$P/MANIFEST" ] || fail "MANIFEST written"
[ ! -e "$DEV/data/local/tmp/lumen-gapps" ] || fail "the zip stayed on the projector"
end

begin push_fails
touch "$FB/push_fail"
inst 'no\nERASE\n' gapps --zip "$ZIP"
expect_rc 1; expect_cmds "$CREATE"; expect_out "adb push of the zip failed"
end

begin remove_wipe
partition
inst 'ERASE\n' gapps --remove
expect_rc 0
expect_cmds "$S getvar all;$S delete-logical-partition $P;${WIPE#"$S getvar all;"}"
[ ! -e "$DEV/dev/block/mapper/$P" ] || fail "partition left"
expect_out "$P deleted"
[ "$(gprop sys.z9x.gapps)" = none ] || fail "sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin remove_keep_data
partition
inst 'WRITE\n' --keep-data gapps --remove
expect_rc 0
expect_cmds "$S getvar all;$S delete-logical-partition $P;$S reboot;"
end

begin remove_from_fastboot_mode
partition; echo fastboot > "$FB/mode"
inst 'ERASE\n' gapps --remove
expect_rc 0
expect_cmds "$S getvar all;$S delete-logical-partition $P;${WIPE#"$S getvar all;"}"
expect_out "waiting for it in fastboot mode"
grep -qx "slot=_b" "$C/state/state" || fail "state slot"
end

begin remove_nothing
inst 'ERASE\n' gapps --remove
expect_rc 0
expect_cmds "$S getvar all;$S reboot;"
expect_out "nothing to remove"
end

begin status_off_turn_on
partition; mkdir -p "$DEV/metadata/z9x_gapps"; echo "3 _b $BID" > "$DEV/metadata/z9x_gapps/off"; sh "$FB/boot.sh"
[ "$(gprop sys.z9x.gapps)" = off ] || fail "with the marker sys.z9x.gapps=$(gprop sys.z9x.gapps)"
inst 'yes\n' gapps
expect_rc 0; expect_cmds ""
expect_out "failed to start twice in a row"
[ ! -e "$DEV/metadata/z9x_gapps" ] || fail "marker left"
[ "$(gprop sys.z9x.gapps)" = ok ] || fail "after the restart sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin status_ok
partition; sh "$FB/boot.sh"
inst '' gapps
expect_rc 0; expect_out "Google services added by you: on ($NAME)"
end

begin status_none
inst '' gapps
expect_rc 0; expect_out "no Google services added"
end

begin status_slow
sed -i.bak 's/^sys.z9x.gapps=.*/sys.z9x.gapps=bad:slow/' "$T/props"
inst '' gapps
expect_rc 0; expect_cmds ""; expect_out "took too long, so the projector started without them: restart it"
end

begin gsf_with_addon
echo com.google.android.gsf > "$FB/packages"
inst '' gsf
expect_rc 0; expect_out "GSF ID: 3a7b11c0ffee1234"
end

begin gsf_without
inst '' gsf
expect_rc 0; expect_out "nothing to register"; expect_out "gapps --zip FILE"
end

begin zip_needs_gapps
inst '' --zip "$ZIP" check
expect_rc 1; expect_out "--zip / --remove belong to 'gapps'"; expect_cmds ""
end

begin help_lists_gapps
bash "$INST/lumen-install.sh" --help > "$C/out" 2>&1; RC=$?
expect_rc 0; expect_out "gapps      Lumen OS without Google: add Google services"; expect_out "--zip FILE"
grep -q '^set -u' "$C/out" && fail "--help prints past the header"
end

# ---------------------------------------------------------------- the full install with --gapps-zip (ONE fastbootd visit, one wipe)
release() {  # [name] [old|nozip]: a signed release image next to the installer copy (a small real erofs with the
  # image's z9x_gapps.sh and allow-list; throwaway test key), erofs-utils and lib/gapps_images.txt; stock on the projector
  local n=${1:-$IMGN} d=$C/sysimg/system/etc/z9x
  mkdir -p "$d" "$C/inst/tools"
  cp "$IMGDIR/z9x_gapps.sh" "$d/z9x_gapps.sh"; cp "$W/fx/allow.txt" "$d/gapps_allow.txt"
  case "${2-}" in
    old) sed -i.bak '/lumen-gapps-fs:/d' "$d/z9x_gapps.sh"; rm -f "$d/z9x_gapps.sh.bak" ;;   # an image from before erofs
    nozip) grep -v "^zip=$ZSHA " "$W/fx/allow.txt" > "$d/gapps_allow.txt" ;;
  esac
  "$EU/mkfs.erofs" --quiet -T1230768000 "$C/inst/$n" "$C/sysimg" >/dev/null 2>&1 || fail "mkfs.erofs of the test image"
  cp -R "$INST/tools/erofs-utils" "$C/inst/tools/erofs-utils"
  cp "$W/gapps_images.txt" "$C/inst/lib/gapps_images.txt"
  cp "$W/keys/k.pem" "$C/inst/certs/ota.x509.pem"
  (cd "$C/inst" && { sha256sum "$n" 2>/dev/null || shasum -a 256 "$n"; } > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/k.key" -out SHA256SUMS.sig SHA256SUMS)
  printf '%s\n' ro.boot.xgimi.modelname=G0082 ro.boot.hardware=mt9952 ro.vendor.build.version.incremental=v6.15.58 \
    ro.boot.slot_suffix=_b ro.debuggable=0 sys.boot_completed=1 > "$T/props"
  touch "$FB/noadb_after_wipe"; FLASHOK=1
}
GIMG="$W/CASE/state/gapps/z9x_gapps-$(printf '%s' "$ZSHA" | cut -c1-16).img"
ONE="$S getvar all;$S flash system $W/CASE/inst/$IMGN;$S getvar all;$S create-logical-partition $P $NEED;$S flash $P $GIMG;"
one() { printf '%s' "$ONE" | sed "s|/CASE/|/$CASE/|g"; }
FMT="$S --fs-options=casefold,projid format:f2fs userdata;$S format:f2fs metadata;$S format:ext4 cache;$S reboot;"   # the wipe in the same session
google_on() {  # the one wipe after the add-on in the same session; Google on at the first start, from erofs
  [ "$(grep -c 'format:f2fs userdata' "$FB/fb.log")" = 1 ] || fail "userdata formatted $(grep -c 'format:f2fs userdata' "$FB/fb.log") times"
  [ "$(grep -c 'reboot fastboot' "$FB/adb.log")" = 1 ] || fail "fastbootd entered $(grep -c 'reboot fastboot' "$FB/adb.log") times"
  [ "$(gprop sys.z9x.gapps)" = ok ] || fail "after the first start sys.z9x.gapps=$(gprop sys.z9x.gapps): $(tail -3 "$FB/boot.out")"
  [ "$(gprop sys.z9x.gapps.fs)" = erofs ] || fail "sys.z9x.gapps.fs=$(gprop sys.z9x.gapps.fs)"
  [ "$(gprop ro.com.google.gmsversion)" = 14_test ] || fail "gmsversion $(gprop ro.com.google.gmsversion)"
  grep -qx "step_gapps_flash=done" "$C/state/state" && grep -qx "step_wipe=done" "$C/state/state" && grep -qx "gapps=$NAME" "$C/state/state" \
    && grep -qx "gapps_img=$GSHA" "$C/state/state" || fail "state: $(cat "$C/state/state")"
  if grep -qE -- "-s $SER (push|shell sh)" "$FB/adb.log"; then fail "a push or a script on the projector: $(grep -E 'push|shell sh' "$FB/adb.log")"; fi
}

begin one_pass
release
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 0
expect_cmds "$(one)$FMT"
expect_out "$NAME, sha256"; expect_out "erofs-utils 1.9.4 (tools/erofs-utils, checked)"
expect_out "the image takes the Google services add-on as erofs"
expect_out "Google services add-on image: $NFILES files checked"
expect_out "With --gapps-zip the same session also writes the Google services from $NAME"
expect_out "writing the Google services into $P"; expect_out "  ✓ $P: $NAME"
expect_out "written. First start takes 2-4 minutes; the setup screen appears on the projector, with the Google sign-in step."
expect_out "register the GSF ID first: bash lumen-install.sh gsf"
[ "$(grep -c 'Type ERASE' "$C/out")" = 1 ] || fail "ERASE asked $(grep -c 'Type ERASE' "$C/out") times"
n_img=$(grep -n "Google services add-on image:" "$C/out" | head -1 | cut -d: -f1); n_er=$(grep -n "Type ERASE" "$C/out" | cut -d: -f1)
n_bk=$(grep -n "Make a backup" "$C/out" | head -1 | cut -d: -f1)
[ -n "$n_img" ] && [ "$n_img" -lt "$n_bk" ] && [ "$n_bk" -lt "$n_er" ] || fail "order: image $n_img, backup question $n_bk, ERASE $n_er"
[ "$(sha256sum "$C/state/gapps/"*.img | cut -d' ' -f1)" = "$GSHA" ] || fail "the flashed image is not the reference one"
[ ! -e "$C/state/gapps/gapps.tar" ] || fail "the tar was left behind"
google_on
end

begin one_pass_ru
release
LANG_CASE=ru_RU.UTF-8 inst 'нет\nда\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 0
expect_out "образ дополнения с сервисами Google: $NFILES файлов проверено"
expect_out "С --gapps-zip в той же сессии в раздел z9x_gapps_b"; expect_out "записываю сервисы Google в z9x_gapps_b"
expect_out "в нём есть шаг входа в Google"
if grep -q "$(printf '\342\200\224')" "$C/out"; then fail "an em dash in the Russian text"; fi
google_on
end

begin one_pass_over_old_partition
partition 536870912; release   # an ext4 add-on of the old 'gapps' fill is there: resized to the image and flashed
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 0
expect_cmds "$S getvar all;$S flash system $C/inst/$IMGN;$S getvar all;$S resize-logical-partition $P $NEED;$S flash $P $(printf '%s' "$GIMG" | sed "s|/CASE/|/$CASE/|");$FMT"
[ ! -e "$DEV/dev/block/mapper/$P/lost+found" ] || fail "the old ext4 content stayed"
google_on
end

begin one_pass_no_room_in_super
sed -i.bak 's/partition-size:super:0x113400000/partition-size:super:0x7DA00000/' "$FB/getvar"; rm -f "$FB/getvar.bak"
partition 536870912; release   # an old add-on that does not leave room for the new one: deleted, so the result is plain
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 0
expect_cmds "$S getvar all;$S flash system $C/inst/$IMGN;$S getvar all;$S delete-logical-partition $P;$FMT"
expect_out "not enough free space in super for the Google services"; expect_out "Google services can be added later"
grep -qx "step_gapps_flash=skipped:room" "$C/state/state" && grep -qx "step_wipe=done" "$C/state/state" || fail "state: $(cat "$C/state/state")"
[ "$(gprop sys.z9x.gapps)" = none ] || fail "sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin one_pass_flash_fails
release; touch "$FB/gflash_fail"
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 0
expect_cmds "$(one)$S delete-logical-partition $P;$FMT"
expect_out "$P could not be written and was removed again"; expect_out "Google services can be added later"
grep -qx "step_gapps_flash=skipped:flash" "$C/state/state" || fail "state: $(cat "$C/state/state")"
[ "$(gprop sys.z9x.gapps)" = none ] || fail "sys.z9x.gapps=$(gprop sys.z9x.gapps)"
end

begin one_pass_fastbootd_gone
release; touch "$FB/drop"
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1
expect_out "fastbootd ended while $P was being written. Nothing was erased"
grep -q "format:f2fs userdata" "$FB/fb.log" && fail "wiped"
[ -z "$(sed -n 's/^step_flash=//p' "$C/state/state")" ] || fail "state: $(cat "$C/state/state")"
: > "$FB/fb.log"; : > "$FB/adb.log"
sed -i.bak 's/^ro.debuggable=1$/ro.debuggable=0/' "$T/props"; rm -f "$T/props.bak"   # (no calibration snapshot in this fake)
inst 'no\nERASE\n' --no-blobs --gapps-zip "$ZIP"   # the same command again: Lumen OS on the old data, adb as before
expect_rc 0
expect_out "Google services add-on image: built earlier, checked"
# the partition the first run created is there: resized (to the same size) and written again
expect_cmds "$(one | sed "s|create-logical-partition $P|resize-logical-partition $P|")$FMT"
google_on
end

begin one_pass_older_installer_waiting
# an older installer wrote system and an empty ext4 z9x_gapps_b, Lumen OS runs on the old data waiting for the fill
release; cp "$FB/props_lumen" "$T/props"
mkdir -p "$DEV/dev/block/mapper/$P/lost+found"; echo 536870912 > "$FB/size.$P"
printf '%s\n' serial=$SER slot=_b step_check=done step_backup=done step_vbmeta=done image_gms=0 step_flash_system=done \
  step_flash=done "gapps_plan=$ZSHA" step_gapps_part=done > "$C/state/state"
inst 'ERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 0
expect_out "Next the installer writes the Google services (if asked) and ERASES ALL DATA"
expect_cmds "$S getvar all;$S getvar all;$S resize-logical-partition $P $NEED;$S flash $P $(printf '%s' "$GIMG" | sed "s|/CASE/|/$CASE/|");$FMT"
expect_out "data wiped. The first start takes 2-4 minutes; the setup has a Google sign-in step."
google_on
end

begin one_pass_zip_refused_first
release
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$OTHER"
expect_rc 1; expect_cmds ""
expect_out "is not the MindTheGapps file this installer accepts"; expect_out "Nothing was written."
if grep -q -- "-s $SER" "$FB/adb.log"; then fail "the projector was used: $(cat "$FB/adb.log")"; fi
end

begin one_pass_google_image_refused
release lumen-os-1.0.7-system.img
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1; expect_cmds ""
expect_out "this image has Google services built in: --gapps-zip is for Lumen OS without Google"
end

begin one_pass_old_image_refused
release "" old
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1; expect_cmds ""
expect_out "cannot take the Google services in one pass (its add-on support is older than this installer)"
if grep -q "reboot" "$FB/adb.log"; then fail "restarted: $(cat "$FB/adb.log")"; fi
end

begin one_pass_image_without_the_zip
release "" nozip
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1; expect_cmds ""
expect_out "does not accept $NAME (not in its gapps_allow.txt)"
end

begin one_pass_no_erofs_utils
release; rm -rf "$C/inst/tools/erofs-utils"
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1; expect_cmds ""
expect_out "this installer has no erofs-utils for this computer"; expect_out "Nothing was written."
end

begin one_pass_damaged_erofs_utils
release; printf 'x' >> "$C/inst/tools/erofs-utils/$EP/mkfs.erofs"
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1; expect_cmds ""
expect_out "tools/erofs-utils/$EP/mkfs.erofs is missing or damaged"
end

begin one_pass_image_not_the_checked_one
release; awk '{ if ($1 !~ /^#/) $4 = "0000000000000000000000000000000000000000000000000000000000000000"; print }' "$W/gapps_images.txt" > "$C/inst/lib/gapps_images.txt"
inst 'no\nyes\nERASE\n' --no-blobs --gapps-zip "$ZIP"
expect_rc 1; expect_cmds ""
expect_out "is not the one checked for this release"; expect_out "Nothing was written."
[ -z "$(ls "$C/state/gapps/" 2>/dev/null | grep '\.img')" ] || fail "an unchecked image was kept: $(ls "$C/state/gapps")"
end

begin one_pass_refusals
release
inst '' --gapps-zip "$ZIP" flash
expect_rc 1; expect_out "--gapps-zip belongs to the full install (no command)"
inst '' --gapps-zip "$ZIP" --keep-data
expect_rc 1; expect_out "not with --keep-data"
inst '' --gapps-zip "$ZIP" --wipe=factory-reset
expect_rc 1; expect_out "not with --wipe=factory-reset"
expect_cmds ""
end

echo "installer gapps: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
