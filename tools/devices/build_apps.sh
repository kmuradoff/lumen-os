#!/bin/sh
# build_apps.sh: build the device-only apps of a device of the registry (the apps key of
# devices/<codename>/device.conf), signed with that device's platform key. devices/DESIGN.md, section 7.
#
#   sh tools/devices/build_apps.sh [--root DIR] CODENAME [OUT]        default OUT: build/<codename>
#
#   --root     the tree whose registry and certificates are read (default: this tree; the tests use a copy)
#   KEYS_DIR   environment, tests only: a key dir in place of the registry's keys_dir
#
# For each name: KEY_PK8=<keys_dir>/platform.pk8 KEY_PEM=devices/<codename>/certs/platform.x509.pem, a scratch
# BUILD_DIR, sh apps/build_apk.sh apps/<name> OUT/<name>.apk; then apksigner verify --print-certs must show the
# device's platform certificate and none of the Z9X or Play 6 ones (rule S1). The app named by hal_app of an
# aidl-table device also passes tools/devices/check_hal.py --dex on its APK. The private key is read on the Mac
# only, never copied. Refuses a device without its key (tools/devices/gen_device_keys.sh first): it never falls
# back to the test key of apps/sdk. Exit 0 when every app is built and verified.
set -eu
H=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$H/../.." && pwd)
ROOT=$G
if [ "${1:-}" = "--root" ]; then ROOT=$(cd "${2:?--root needs a dir}" && pwd); shift 2; fi
C=${1:?usage: build_apps.sh [--root DIR] CODENAME [OUT]}
die() { echo "build_apps: $*" >&2; exit 1; }
reg() { python3 -B "$H/devices.py" --root "$ROOT" get "$C" "$1"; }
sha256in() { if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
certsha() { openssl x509 -in "$1" -outform DER | sha256in; }

APPS=$(reg apps) || die "unknown device $C"
[ -n "$APPS" ] || die "$C lists no apps (apps= in devices/$C/device.conf)"
KD=${KEYS_DIR:-$(reg keys_dir)}
case $KD in "~/"*) KD=$HOME/${KD#\~/} ;; esac
KEY_PK8=$KD/platform.pk8
KEY_PEM=$ROOT/devices/$C/certs/platform.x509.pem
[ -f "$KEY_PK8" ] || die "no platform key $KEY_PK8: run tools/devices/gen_device_keys.sh $C first (owner, on the Mac)"
[ -f "$KEY_PEM" ] || die "no certificate $KEY_PEM: run tools/devices/gen_device_keys.sh $C first"
[ "$(certsha "$KEY_PEM")" = "$(certsha "$KD/platform.x509.pem")" ] || die "$KEY_PEM is not the certificate of $KEY_PK8"
OUT=${2:-$ROOT/build/$C}
mkdir -p "$OUT"
ANDROID_SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
APKSIGNER=${BUILD_TOOLS:-$ANDROID_SDK/build-tools/36.0.0}/apksigner
WANT=$(certsha "$KEY_PEM")
# the certificates of the other devices and of the test keys: none may sign an app of this device
OTHER=$(for c in "$G/apps/sdk/platform.x509.pem" "$G"/tools/sign/testcerts/*.x509.pem "$G"/tools/play6/certs/*.x509.pem \
                 "$G"/tools/sign/release_certs/*.x509.pem; do [ -f "$c" ] && certsha "$c"; done)
W=$(mktemp -d "${TMPDIR:-/tmp}/build_apps.XXXXXX")
trap 'rm -rf "$W"' EXIT
for n in $APPS; do
  [ -d "$ROOT/apps/$n" ] || die "no app dir apps/$n"
  KEY_PK8=$KEY_PK8 KEY_PEM=$KEY_PEM BUILD_DIR=$W/$n sh "$G/apps/build_apk.sh" "$ROOT/apps/$n" "$OUT/$n.apk" > "$W/$n.log" 2>&1 \
    || { cat "$W/$n.log" >&2; die "$n: build failed"; }
  got=$("$APKSIGNER" verify --print-certs "$OUT/$n.apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
  [ "$got" = "$WANT" ] || die "$n: signed by $got, not the $C platform certificate $WANT"
  for o in $OTHER; do [ "$o" != "$got" ] || die "$n: signed with a certificate of another device or a test key"; done
  if [ "$(reg hal_check)" = aidl-table ] && [ "$(reg hal_app)" = "apps/$n" ]; then
    python3 -B "$H/check_hal.py" --root "$ROOT" --device "$C" --dex "$OUT/$n.apk" || die "$n: HAL gate failed on the APK"
  fi
  echo "build_apps: $n -> $OUT/$n.apk ($(sha256in < "$OUT/$n.apk"))"
done
