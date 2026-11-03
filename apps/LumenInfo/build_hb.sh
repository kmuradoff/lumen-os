#!/bin/sh
# build_hb.sh: LumenInfo signed with the platform key of the Z9X HB (goat_elegant), the build that works there.
#
#   sh build_hb.sh [OUT_APK]     default: build/LumenInfo-goat_elegant.apk
#
# Same key handling as tools/devices/build_apps.sh (the registry's keys_dir, the certificate in
# devices/goat_elegant/certs, both must match; never the apps/sdk test key), but for this app only (a test APK).
# From HB t11 the image copy comes from tools/devices/build_apps.sh (LumenInfo is in the apps= list of
# devices/goat_elegant/device.conf), the same build_apk.sh. After the build,
# apksigner must show the goat_elegant platform certificate. The host tests run first.
# Install (test): adb install -r build/LumenInfo-goat_elegant.apk (plain data app with the system uid).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../.." && pwd)
C=goat_elegant
OUT=${1:-$HERE/build/LumenInfo-$C.apk}
die() { echo "build_hb: $*" >&2; exit 1; }
certsha() { openssl x509 -in "$1" -outform DER | shasum -a 256 | cut -d' ' -f1; }
KD=$(python3 -B "$GSI/tools/devices/devices.py" get "$C" keys_dir) || die "no keys_dir for $C"
case $KD in "~/"*) KD=$HOME/${KD#\~/} ;; esac
KEY_PK8=$KD/platform.pk8
KEY_PEM=$GSI/devices/$C/certs/platform.x509.pem
[ -f "$KEY_PK8" ] || die "no platform key $KEY_PK8"
[ -f "$KEY_PEM" ] || die "no certificate $KEY_PEM"
[ "$(certsha "$KEY_PEM")" = "$(certsha "$KD/platform.x509.pem")" ] || die "$KEY_PEM is not the certificate of $KEY_PK8"
LUMEN_GSI=$GSI sh "$HERE/test/run.sh"
KEY_PK8=$KEY_PK8 KEY_PEM=$KEY_PEM BUILD_DIR=$HERE/build/out-$C sh "$GSI/apps/build_apk.sh" "$HERE" "$OUT"
APKSIGNER=${BUILD_TOOLS:-${ANDROID_SDK:-$HOME/Library/Android/sdk}/build-tools/36.0.0}/apksigner
GOT=$("$APKSIGNER" verify --print-certs "$OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$GOT" = "$(certsha "$KEY_PEM")" ] || die "signer $GOT is not the $C platform certificate"
echo "build_hb: OK $OUT ($C platform certificate $GOT)"
