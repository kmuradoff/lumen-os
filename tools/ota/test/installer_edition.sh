#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Mock test of the installer's edition rules (Lumen OS with Google services / "Lumen OS без Google",
# docs/NOGMS_PLAN.md section 11; installer/lumen-install.sh), on the Mac, no projector, no network:
#   bash tools/ota/test/installer_edition.sh
# A copy of the installer goes into a temp dir with a throwaway test certificate, fake images and
# SHA256SUMS(.sig) signed by that key. A fake adb answers getprop / pm from a props file, a fake dump.erofs the
# image's product build.prop (ro.z9x.gms) and the codec placeholder size, a fake fastboot only its version.
# Cases: two images next to the installer (refused: --image needed), 'check' of a no-Google image (edition
# shown and saved), file name and image disagreeing either way (refused), 'flash --keep-data' and 'rescue'
# with an image of the other edition than the projector runs (refused before anything is written), the same
# edition (passes the guard), 'gsf' on Lumen OS without Google and without GSF (nothing to register, no adb
# root), 'verify' of a no-Google system (edition shown, Google packages reported).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/z9x_edition_inst.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=
SER=ZF62TEST0003

mkdir -p "$W/bin" "$W/keys"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/keys/k.pem" -out "$W/keys/c.pem" -subj /CN=lumen-test -days 2 >/dev/null 2>&1 \
  || { echo "openssl cannot make a test key"; exit 1; }

cat > "$W/bin/adb" <<'EOF'
#!/bin/sh
# fake adb: getprop from $FB/props, pm from $FB/packages; everything is logged
echo "$*" >> "$FB/adb.log"
[ "$1" = version ] && { echo "Android Debug Bridge version 1.0.41"; echo "Version 35.0.2-12147458"; exit 0; }
if [ "$1" = devices ]; then printf 'List of devices attached\n%s\tdevice\n\n' "$FB_SERIAL"; exit 0; fi
[ "$1" = -s ] && shift 2
case "$1" in
  shell)
    case "$2 ${3-}" in
      "getprop "?*) awk -v k="$3" 'index($0, k "=") == 1 { print substr($0, length(k) + 2); exit }' "$FB/props" ;;
      "pm path") grep -qx "$4" "$FB/packages" 2>/dev/null && echo "package:/system/app/x/$4.apk" ;;
      "pm list") sed 's/^/package:/' "$FB/packages" ;;
      "ls /dev/block/mapper/") printf 'control\nsystem_b\nvendor_b\n' ;;
      *) exit 1 ;;
    esac ;;
  *) exit 1 ;;
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
# fake dump.erofs: --cat of the product build.prop = $FB/prop.<image name>; else the codec placeholder (0 bytes)
img=$(for a in "$@"; do :; done; echo "$a")
case "$*" in
  *--cat*--path=/system/product/etc/build.prop*) cat "$FB/prop.$(basename "$img")" 2>/dev/null || exit 1 ;;
  *) echo "Path : /system/system_ext/lib64/libc2plugin_store.so"; echo "Size: 0  On-disk size: 0" ;;
esac
EOF
printf '#!/bin/sh\nexit 0\n' > "$W/bin/sleep"
printf '#!/bin/sh\nexit 0\n' > "$W/bin/make_f2fs"; cp "$W/bin/make_f2fs" "$W/bin/mke2fs"
chmod 755 "$W/bin/"*

NOGMS_PROP='ro.z9x.variant=public
ro.z9x.gms=0
ro.z9x.ota.manifest_url=https://github.com/kmuradoff/lumen-os/releases/latest/download/update-public-nogms.json'
GMS_PROP='ro.z9x.variant=public
ro.z9x.ota.manifest_url=https://github.com/kmuradoff/lumen-os/releases/latest/download/update-public.json'

begin() {
  CASE=$1; CFAIL=0
  C=$W/$1; rm -rf "$C"; mkdir -p "$C/inst/certs" "$C/fb" "$C/state" "$C/backup"
  cp "$INST/lumen-install.sh" "$INST/release.conf" "$C/inst/"; cp -R "$INST/lib" "$C/inst/"
  cp "$W/keys/c.pem" "$C/inst/certs/ota.x509.pem"
  printf '%s\n' ro.boot.xgimi.modelname=G0082 ro.boot.hardware=mt9952 ro.vendor.build.version.incremental=v6.15.58 \
    ro.boot.slot_suffix=_b ro.debuggable=1 > "$C/fb/props"
  : > "$C/fb/packages"; : > "$C/fb/adb.log"; : > "$C/fb/fb.log"
}
image() {  # name prop-text: an image next to the installer, listed in the signed SHA256SUMS
  printf 'LUMEN-TEST-IMAGE %s' "$1" > "$C/inst/$1"
  printf '%s\n' "$2" > "$C/fb/prop.$1"
  (cd "$C/inst" && for f in lumen-os-*-system.img; do { sha256sum "$f" 2>/dev/null || shasum -a 256 "$f"; }; done > SHA256SUMS
   openssl dgst -sha256 -sign "$W/keys/k.pem" -out SHA256SUMS.sig SHA256SUMS)
}
running() {  # a running Lumen OS: version [gms prop value or empty]
  printf '%s\n' "ro.z9x.version=$1" sys.boot_completed=1 init.svc.gmpf_main=running ro.z9x.keys=release \
    ro.z9x.build_id=lumen-1.0.1-20261009enp ro.z9x.variant=public >> "$C/fb/props"
  [ -n "${2-}" ] && echo "ro.z9x.gms=$2" >> "$C/fb/props"
  return 0
}
inst() {  # args... -> runs the installer copy; exit status in RC, output in $C/out
  printf 'no\n' | env PATH="$W/bin:$PATH" FB="$C/fb" FB_SERIAL=$SER LUMEN_STATE_DIR="$C/state" \
    LUMEN_BACKUP_DIR="$C/backup" LANG=en_US.UTF-8 bash "$C/inst/lumen-install.sh" "$@" > "$C/out" 2>&1
  RC=$?
}
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
expect_rc() { [ "$RC" = "$1" ] || fail "exit $RC, want $1"; }
expect_out() { grep -q "$1" "$C/out" || fail "output lacks '$1'"; }
expect_no_out() { if grep -q "$1" "$C/out"; then fail "output has '$1'"; fi; }
state() { sed -n "s/^$1=//p" "$C/state/state" 2>/dev/null | tail -1; }
end() {
  # never more than reading: no root, push, reboot, install; fastboot only for its version
  if grep -vE '^(version|devices|-s [A-Z0-9]+ shell (getprop |pm path |pm list packages|ls /dev/block/mapper/|dumpsys |mount ))' "$C/fb/adb.log" | grep -q .; then
    fail "adb did more than read: $(grep -vE '^(version|devices|-s [A-Z0-9]+ shell (getprop |pm path |pm list packages|ls /dev/block/mapper/|dumpsys |mount ))' "$C/fb/adb.log" | head -3)"
  fi
  grep -vx -e '--version' "$C/fb/fb.log" | grep -q . && fail "fastboot used: $(cat "$C/fb/fb.log")"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -12; fi
}

begin two_images_refused
image lumen-os-1.0.1-system.img "$GMS_PROP"; image lumen-os-1.0.1-nogms-system.img "$NOGMS_PROP"
inst check
expect_rc 1; expect_out "several images found"
end

begin check_nogms_image
image lumen-os-1.0.1-nogms-system.img "$NOGMS_PROP"
inst check
expect_rc 0; expect_out "edition: Lumen OS without Google"; expect_out "check passed"
[ "$(state image_gms)" = 0 ] || fail "image_gms=$(state image_gms), want 0"
[ "$(state dev_gms)" = stock ] || fail "dev_gms=$(state dev_gms), want stock (XGIMI firmware)"
end

begin check_gms_image_with_image_flag
image lumen-os-1.0.1-system.img "$GMS_PROP"; image lumen-os-1.0.1-nogms-system.img "$NOGMS_PROP"
inst check --image "$C/inst/lumen-os-1.0.1-system.img"
expect_rc 0; expect_out "edition: with Google services"
[ "$(state image_gms)" = 1 ] || fail "image_gms=$(state image_gms), want 1"
end

begin name_nogms_prop_google_refused
image lumen-os-1.0.1-nogms-system.img "$GMS_PROP"
inst check
expect_rc 1; expect_out "the file name and the image disagree"
end

begin name_google_prop_nogms_refused
image lumen-os-1.0.1-system.img "$NOGMS_PROP"
inst check
expect_rc 1; expect_out "the file name and the image disagree"
end

begin keepdata_nogms_projector_google_image_refused
running 1.0.1 0
image lumen-os-1.0.1-system.img "$GMS_PROP"
inst flash --keep-data
expect_rc 1; expect_out "runs Lumen OS without Google and the image has Google services"
expect_no_out "Type WRITE"
end

begin keepdata_google_projector_nogms_image_refused
running 1.0.1
image lumen-os-1.0.1-nogms-system.img "$NOGMS_PROP"
inst flash --keep-data
expect_rc 1; expect_out "runs Lumen OS with Google services and the image is without Google"
end

begin keepdata_same_edition_passes_guard
running 1.0.1 0
image lumen-os-1.0.1-nogms-system.img "$NOGMS_PROP"
inst flash --keep-data
# past the guard: the manual vbmeta question comes (answered no here)
expect_rc 1; expect_out "do the vbmeta step first"; expect_no_out "switching editions"
end

begin rescue_other_edition_refused
image lumen-os-1.0.1-system.img "$GMS_PROP"
printf 'dev_gms=0\n' > "$C/state/state"
inst rescue
expect_rc 1; expect_out "switching editions needs a full install"; expect_no_out "Type WRITE"
end

begin gsf_on_nogms_nothing_to_register
running 1.0.1 0
inst gsf
expect_rc 0; expect_out "no Google services: nothing to register"
end

begin gsf_without_gsf_package
running 1.0.1
inst gsf
expect_rc 0; expect_out "nothing to register"
end

begin verify_nogms_reports_google_packages
running 1.0.1 0
printf '%s\n' org.z9x.home com.google.android.gms > "$C/fb/packages"
inst verify
expect_rc 0; expect_out "without Google)"; expect_out "Google packages on Lumen OS without Google: com.google.android.gms"
end

begin verify_nogms_clean
running 1.0.1 0
printf '%s\n' org.z9x.home com.android.webview > "$C/fb/packages"
inst verify
expect_rc 0; expect_out "no Google package"
end

echo "installer edition: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
