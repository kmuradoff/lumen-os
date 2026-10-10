#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of the installer's 'blobs' and 'check' steps for a PUBLIC image (installer/lumen-install.sh),
# on the Mac, no projector, no network:
#   bash tools/ota/test/installer_blobs.sh
# A copy of the installer goes into a temp dir with a TEST allow-list (lib/blobs_allow.txt: the real one's
# structure, made-up files from blobs_fixture.py; no proprietary file is ever used), a throwaway test
# certificate, a fake image and SHA256SUMS(.sig) signed by that key. A fake adb serves 'pull' from a fake
# projector tree and 'shell getprop' from a props file; fake fastboot / make_f2fs / mke2fs / dump.erofs
# satisfy the tool checks (dump.erofs answers the placeholder size: 0 = public image).
# Cases: blobs from a running Lumen PRIVATE system (its own copies, the audio policy as bound), from XGIMI
# stock (the audio policy derived from the stock original with the allow-list's from=), a firmware whose
# file does not match (refused), --no-blobs; the partition the installer builds is then read by the real
# z9x_blobs.sh (round trip: every file bound). 'check' on a running Lumen 1.0.1 private system with
# firmware v6.15.58 and v6.15.19 (accepted) and v6.15.30 (refused), a public image with --blobs=embedded
# (refused). The installer must never write to the projector here (no push, no fastboot flash).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
IMGDIR=$(cd "$HERE/../image" && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/z9x_blobs_inst.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=ZF62TEST0002

mkdir -p "$W/bin" "$W/keys" "$W/files" "$W/dbin"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/k.pem" -out "$W/keys/c.pem" -subj /CN=lumen-test -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }
python3 "$HERE/blobs_fixture.py" allow "$IMGDIR/blobs_allow.txt" "$W/allow.txt" "$W/files" || exit 1

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb: the projector is the tree $DEV (pull) + $FB/props (getprop); everything is logged
echo "$*" >> "$FB/adb.log"
[ "$1" = version ] && { echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then printf 'List of devices attached\n%s\tdevice\n\n' "$FB_SERIAL"; exit 0; fi
[ "$1" = -s ] && shift 2
case "$1" in
  pull) [ -f "$DEV$2" ] || { echo "adb: error: failed to stat remote object '$2': No such file or directory" >&2; exit 1; }
        cp "$DEV$2" "$3" ;;
  shell)
    case "$2 ${3-}" in
      "getprop "?*) awk -v k="$3" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$FB/props" ;;
      "ls /dev/block/mapper/") printf 'control\nsystem_b\nvendor_b\nz9x_blobs_b\n' ;;
      *) echo "fake adb: unexpected shell $*" >&2; exit 1 ;;
    esac ;;
  *) echo "fake adb: unexpected $*" >&2; exit 1 ;;
esac
EOF
cat > "$W/bin/fastboot" <<'EOF'
#!/bin/sh
echo "$*" >> "$FB/fb.log"
[ "$1" = --version ] && { echo "fastboot version 35.0.2-12147458"; exit 0; }
exit 1
EOF
cat > "$W/bin/dump.erofs" <<'EOF'
#!/bin/sh
# fake dump.erofs: the codec placeholder's size inside the image ($FB/placeholder_size)
echo "Path : /system/system_ext/lib64/libc2plugin_store.so"; echo "Size: $(cat "$FB/placeholder_size")  On-disk size: 0"
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
printf '#!/bin/sh\nexit 0\n' > "$W/bin/make_f2fs"; cp "$W/bin/make_f2fs" "$W/bin/mke2fs"
chmod 755 "$W/bin/"*
for f in getprop setprop log timeout mount chcon chown; do cp "$HERE/stubs/$f" "$W/dbin/$f" && chmod 755 "$W/dbin/$f"; done

# the projector's files: path -> fake content (line: <sha256> <rel> <src> [bind=...] [from=...])
dev_lumen_private() {  # a running Lumen private image: codec libs in /system, the audio files as bound
  awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 {print $2, $3}' "$W/allow.txt" | while read -r rel src; do
    mkdir -p "$(dirname "$DEV$src")"; cp "$W/files/$rel" "$DEV$src"
  done
}
dev_stock() {  # XGIMI stock: the same libs; the audio policy is the stock original (from=)
  dev_lumen_private
  cp "$W/files/etc/xgimi/audio_policy_configuration.xml.stock" "$DEV/vendor/etc/audio_policy_configuration.xml"
}

begin() {
  CASE=$1; CFAIL=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst/certs" "$C/fb" "$C/state" "$C/backup" "$C/dev"
  DEV=$C/dev
  cp "$INST/lumen-install.sh" "$INST/release.conf" "$C/inst/"; mkdir -p "$C/inst/lib"
  cp "$W/allow.txt" "$C/inst/lib/blobs_allow.txt"
  cp "$W/keys/c.pem" "$C/inst/certs/ota.x509.pem"
  printf 'LUMEN-TEST-PUBLIC-IMAGE' > "$C/inst/lumen-os-1.0.1-system.img"
  (cd "$C/inst" && { sha256sum lumen-os-1.0.1-system.img 2>/dev/null || shasum -a 256 lumen-os-1.0.1-system.img; } > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/k.pem" -out SHA256SUMS.sig SHA256SUMS)
  printf '%s\n' ro.boot.xgimi.modelname=G0082 ro.boot.hardware=mt9952 ro.vendor.build.version.incremental=v6.15.58 \
    ro.boot.slot_suffix=_b ro.z9x.version=1.0.1 ro.z9x.build_id=lumen-1.0.1-20261009e ro.debuggable=1 > "$C/fb/props"
  echo 0 > "$C/fb/placeholder_size"
  : > "$C/fb/adb.log"; : > "$C/fb/fb.log"
}
inst() {  # args... -> runs the installer copy; exit status in RC, output in $C/out
  printf 'no\n' | env PATH="$W/bin:$PATH" FB="$C/fb" DEV="$DEV" FB_SERIAL=$SER LUMEN_STATE_DIR="$C/state" \
    LUMEN_BACKUP_DIR="$C/backup" LANG=en_US.UTF-8 bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -q "$1" "$C/out" || fail "output lacks '$1'"; }
end() {
  if grep -vE '^(version|devices|-s [A-Z0-9]+ (pull |shell getprop |shell ls /dev/block/mapper/$))' "$C/fb/adb.log" | grep -q .; then
    fail "adb did more than read: $(grep -vE '^(version|devices|-s [A-Z0-9]+ (pull |shell getprop |shell ls /dev/block/mapper/$))' "$C/fb/adb.log" | head -3)"
  fi
  grep -vx -e '--version' "$C/fb/fb.log" | grep -q . && fail "fastboot used: $(cat "$C/fb/fb.log")"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -12; fi
}
# the partition the installer made, read by the real z9x_blobs.sh: every line bound with its file
roundtrip() {
  local R=$C/root T=$C/dt img=$C/backup/blobs-$SER.img
  [ -f "$img" ] || { fail "no $img"; return; }
  [ "$(wc -c < "$img" | tr -d ' ')" = 4194304 ] || fail "blobs image is not 4 MiB"
  mkdir -p "$R/system/etc/z9x" "$R/dev/block/mapper" "$R/mnt" "$T"
  cp "$W/allow.txt" "$R/system/etc/z9x/blobs_allow.txt"; cp "$img" "$R/dev/block/mapper/z9x_blobs_b"
  awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { t = "/system/" $2; for (i = 4; i <= NF; i++) if ($i ~ /^bind=/) t = substr($i, 6); print $1, t }' \
    "$W/allow.txt" > "$T/want"
  while read -r h t; do mkdir -p "$(dirname "$R$t")"; case "$t" in /system/*) : > "$R$t" ;; *) echo own > "$R$t" ;; esac; done < "$T/want"
  echo ro.boot.slot_suffix=_b > "$T/props"; : > "$T/binds"
  env Z9X_ROOT="$R" Z9X_T="$T" PATH="$W/dbin:$PATH" sh "$IMGDIR/z9x_blobs.sh" > "$T/out" 2>&1
  [ ! -s "$T/out" ] || fail "z9x_blobs.sh output: $(cat "$T/out")"
  grep -qx 'sys.z9x.blobs=ok' "$T/props" || fail "round trip: $(grep sys.z9x.blobs "$T/props") $(cat "$T/logcat" 2>/dev/null)"
  while read -r h t; do grep -qx "$h $R$t" "$T/binds" || fail "round trip: $t not bound with its file"; done < "$T/want"
}

begin blobs_from_lumen_private
dev_lumen_private; inst blobs
expect_rc 0; expect_out "9 codec and audio files from your projector, set z9x-v61558-b"
roundtrip
end

begin blobs_from_stock
dev_stock; sed -i.bak '/^ro\.z9x\./d' "$C/fb/props"; inst blobs
expect_rc 0; expect_out "9 codec and audio files"
roundtrip
end

begin blobs_from_stock_v61519
dev_stock; sed -i.bak 's/^ro.vendor.build.version.incremental=.*/ro.vendor.build.version.incremental=v6.15.19/' "$C/fb/props"; inst blobs
expect_rc 0; roundtrip
end

begin blobs_other_firmware_refused
dev_stock; echo "<other firmware's policy/>" > "$DEV/vendor/etc/audio_policy_configuration.xml"; inst blobs
expect_rc 1; expect_out "etc/xgimi/audio_policy_configuration.xml from the projector does not match the allow-list"
expect_out "NO SOUND"
[ ! -f "$C/backup/blobs-$SER.img" ] || fail "a blobs image was written"
end

begin blobs_lib_missing_refused
dev_lumen_private; rm -f "$DEV/apex/com.android.vndk.v34/lib64/libstagefright_foundation.so"; inst blobs
expect_rc 1; expect_out "etc/xgimi/libstagefright_foundation64.so from the projector does not match"
end

begin blobs_system_fallback
# the bound copies gone, but a private image's /system/etc/xgimi copies are there ('/system/<path>' fallback)
dev_stock; mkdir -p "$DEV/system/etc/xgimi"
cp "$W/files/etc/xgimi/"libstagefright_foundation*.so "$DEV/system/etc/xgimi/"
echo "AOSP build" > "$DEV/apex/com.android.vndk.v34/lib/libstagefright_foundation.so"
echo "AOSP build" > "$DEV/apex/com.android.vndk.v34/lib64/libstagefright_foundation.so"
inst blobs
expect_rc 0; roundtrip
end

begin blobs_no_blobs
dev_stock; inst --no-blobs blobs
expect_rc 0; expect_out "codec and audio files skipped (no sound, no protected video)"
grep -qx 'step_blobs=skip' "$C/state/state" || fail "state step_blobs"
end

begin check_lumen_private_running_v61558
dev_lumen_private; inst check
expect_rc 0; expect_out "firmware v6.15.58"; expect_out "Lumen/Z9X OS 1.0.1"; expect_out "SHA256SUMS signature: Lumen OS release key"
expect_out "verification is off"; expect_out "check passed"
end

begin check_lumen_private_running_v61519
sed -i.bak 's/^ro.vendor.build.version.incremental=.*/ro.vendor.build.version.incremental=v6.15.19/' "$C/fb/props"; inst check
expect_rc 0; expect_out "firmware v6.15.19"; expect_out "check passed"
end

begin check_firmware_refused
sed -i.bak 's/^ro.vendor.build.version.incremental=.*/ro.vendor.build.version.incremental=v6.15.30/' "$C/fb/props"; inst check
expect_rc 1; expect_out "XGIMI firmware v6.15.30 is not supported (V6.15.58 or V6.15.19 only)"
end

begin check_public_image_embedded_refused
inst --blobs=embedded check
expect_rc 1; expect_out "this is a public image (codec placeholder): run with --blobs=partition"
end

begin check_private_image_partition_refused
echo 136152 > "$C/fb/placeholder_size"; inst check
expect_rc 1; expect_out "this image carries the codec files itself (private image): run with --blobs=embedded"
end

echo "installer blobs/check: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
