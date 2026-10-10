#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of 'lumen-install.sh rescue' (installer/). Runs on the Mac, no projector, no network:
#   bash tools/ota/test/installer_rescue.sh
# A copy of the installer goes into a temp dir with a throwaway test certificate (certs/ota.x509.pem),
# a fake image and SHA256SUMS(.sig) signed by that key. Fake fastboot/adb/sleep come first on PATH: the
# fake fastboot answers 'getvar all' from a file (stderr, like the real one) and logs every command;
# the fake adb must never be called (except "adb version" in the tool check). Checks the order and the set of fastboot commands per case, and
# that 'rescue' never formats, erases, wipes or flashes anything but system / z9x_blobs_<slot>.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/z9x_rescue_inst.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=ZF62TEST0001

mkdir -p "$W/bin" "$W/keys"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/k.pem" -out "$W/keys/c.pem" -subj /CN=lumen-test -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }

cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
# fake fastboot: state in $FB (getvar = 'getvar all' answer, present_after = polls before the device shows)
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then
  n=$(cat "$FB/polls" 2>/dev/null || echo 0); n=$((n + 1)); echo $n > "$FB/polls"
  [ "$n" -gt "$(cat "$FB/present_after" 2>/dev/null || echo 0)" ] && printf '%s\tfastboot\n' "$FB_SERIAL"
  exit 0
fi
[ "$1" = -s ] && shift 2
case "$1" in
  getvar) cat "$FB/getvar" >&2 ;;
  snapshot-update)
    [ -e "$FB/snapshot_fail" ] && { echo "FAILED (remote: 'cannot')" >&2; exit 1; }
    sed 's/^(bootloader) snapshot-update-status:.*/(bootloader) snapshot-update-status:none/' "$FB/getvar" > "$FB/getvar.tmp"
    mv "$FB/getvar.tmp" "$FB/getvar" ;;
  flash) [ -e "$FB/flash_fail" ] && exit 1 ;;
esac
exit 0
EOF
cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb: only 'adb version' (check_tools) is expected; anything else is logged as an error
[ "$1" = version ] && { echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0; }
echo "adb $*" >> "$FB/adb.log"
exit 1
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
chmod 755 "$W/bin/fastboot" "$W/bin/adb" "$W/bin/sleep"

begin() {
  CASE=$1; CFAIL=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst/certs" "$C/fb" "$C/state" "$C/backup"
  cp "$INST/lumen-install.sh" "$INST/release.conf" "$C/inst/"; cp -R "$INST/lib" "$C/inst/"
  cp "$W/keys/c.pem" "$C/inst/certs/ota.x509.pem"
  IMGF=$C/inst/lumen-os-1.0.1-system.img
  printf 'LUMEN-TEST-IMAGE' > "$IMGF"
  (cd "$C/inst" && sha256sum_() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }
   sha256sum_ lumen-os-1.0.1-system.img > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/k.pem" -out SHA256SUMS.sig SHA256SUMS)
  printf 'BLOBSIMG' > "$C/backup/blobs-$SER.img"
  { echo "(bootloader) is-userspace:yes"; echo "(bootloader) product:mt5877"; echo "(bootloader) unlocked:yes"
    echo "(bootloader) current-slot:b"; echo "(bootloader) snapshot-update-status:none"
    echo "(bootloader) partition-size:system_b:0x80000000"; echo "(bootloader) partition-size:z9x_blobs_b:0x400000"
    echo "(bootloader) partition-size:vendor_b:0x50000000"; echo "all: Done"; } > "$C/fb/getvar"
  : > "$C/fb/fb.log"
}
gv() { sed -i.bak "s/^(bootloader) $1:.*/(bootloader) $1:$2/" "$C/fb/getvar"; }
addgv() { echo "(bootloader) $1" >> "$C/fb/getvar"; }
rescue() {  # answer [lang] -> runs the installer copy, exit status in RC, output in $C/out
  printf '%s\n' "$1" | env PATH="$W/bin:$PATH" FB="$C/fb" FB_SERIAL=$SER LUMEN_STATE_DIR="$C/state" \
    LUMEN_BACKUP_DIR="$C/backup" LANG="${2:-en_US.UTF-8}" bash "$C/inst/lumen-install.sh" rescue > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
cmds() { grep -v -e '^--version$' -e '^devices$' "$C/fb/fb.log" | sed "s|$C/||g" | tr '\n' ';'; }
expect_cmds() { [ "$(cmds)" = "$1" ] || fail "fastboot commands: got '$(cmds)', want '$1'"; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -q "$1" "$C/out" || fail "output lacks '$1'"; }
end() {
  [ ! -s "$C/fb/adb.log" ] || fail "adb was called: $(cat "$C/fb/adb.log")"
  if grep -qE '(^| )(format|erase|-w|flashing|set_active|--set-active)( |:|$)' "$C/fb/fb.log"; then fail "forbidden fastboot command: $(cat "$C/fb/fb.log")"; fi
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -15; fi
}
S="-s $SER"

begin rescue_plain
rescue WRITE
expect_rc 0
expect_cmds "$S getvar all;$S flash system inst/lumen-os-1.0.1-system.img;$S resize-logical-partition z9x_blobs_b 4194304;$S flash z9x_blobs_b backup/blobs-$SER.img;$S reboot;"
grep -qx "slot=_b" "$C/state/state" || fail "state slot"
grep -q "^step_rescue=" "$C/state/state" || fail "state step_rescue"
expect_out "SHA256SUMS signature: Lumen OS release key"
end

begin rescue_plain_ru
rescue WRITE ru_RU.UTF-8
expect_rc 0
expect_out "РЕМОНТ: заново записывает Lumen OS"; expect_out "fastbootd на $SER, слот b"
end

begin rescue_waits_for_fastbootd
echo 5 > "$C/fb/present_after"
rescue WRITE
expect_rc 0
[ "$(cat "$C/fb/polls")" = 6 ] || fail "polls: $(cat "$C/fb/polls")"
end

begin rescue_not_confirmed
rescue no
expect_rc 1
expect_cmds ""
grep -q '^devices$' "$C/fb/fb.log" && fail "waited for the device although not confirmed"
end

begin rescue_snapshotted_cancel
gv snapshot-update-status snapshotted; addgv "partition-size:system_b-cow:0x1000"
rescue WRITE
expect_rc 0
expect_cmds "$S getvar all;$S snapshot-update cancel;$S getvar all;$S delete-logical-partition system_b-cow;$S flash system inst/lumen-os-1.0.1-system.img;$S resize-logical-partition z9x_blobs_b 4194304;$S flash z9x_blobs_b backup/blobs-$SER.img;$S reboot;"
end

begin rescue_merging_merge
gv snapshot-update-status merging
rescue WRITE
expect_rc 0
case "$(cmds)" in "$S getvar all;$S snapshot-update merge;$S getvar all;$S flash system "*) ;; *) fail "order: $(cmds)" ;; esac
end

begin rescue_snapshot_cancel_fails
gv snapshot-update-status snapshotted; touch "$C/fb/snapshot_fail"
rescue WRITE
expect_rc 1
expect_cmds "$S getvar all;$S snapshot-update cancel;$S reboot;"
end

begin rescue_refuses_stock_slot
addgv "partition-size:product_b:0x10000000"
rescue WRITE
expect_rc 1
expect_cmds "$S getvar all;$S reboot;"
expect_out "not an installed Lumen OS"
end

begin rescue_not_userspace
gv is-userspace no
rescue WRITE
expect_rc 1; expect_cmds "$S getvar all;$S reboot;"
end

begin rescue_wrong_product
gv product mt9999
rescue WRITE
expect_rc 1; expect_cmds "$S getvar all;$S reboot;"
end

begin rescue_locked
gv unlocked no
rescue WRITE
expect_rc 1; expect_cmds "$S getvar all;$S reboot;"
end

begin rescue_slot_a_no_blobs_image
gv current-slot a; sed -i.bak 's/_b:/_a:/' "$C/fb/getvar"; rm -f "$C/backup/blobs-$SER.img"
rescue WRITE
expect_rc 0
expect_cmds "$S getvar all;$S flash system inst/lumen-os-1.0.1-system.img;$S reboot;"
expect_out "z9x_blobs_a kept as it is"
grep -qx "slot=_a" "$C/state/state" || fail "state slot"
end

begin rescue_flash_fails
touch "$C/fb/flash_fail"
rescue WRITE
expect_rc 1
expect_cmds "$S getvar all;$S flash system inst/lumen-os-1.0.1-system.img;$S reboot;"
end

begin rescue_bad_signature
printf 'tampered' >> "$C/inst/SHA256SUMS"
rescue WRITE
expect_rc 1; expect_cmds ""
expect_out "not signed by Lumen OS"
end

begin rescue_bad_image
printf 'X' >> "$C/inst/lumen-os-1.0.1-system.img"
rescue WRITE
expect_rc 1; expect_cmds ""
expect_out "checksum mismatch"
end

begin help_lists_rescue
bash "$INST/lumen-install.sh" --help > "$C/out" 2>&1; RC=$?
expect_rc 0; expect_out "rescue     Lumen OS that does not start any more"
grep -q '^set -u' "$C/out" && fail "--help prints past the header"
end

echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
