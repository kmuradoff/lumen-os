#!/bin/sh
# build.sh: compile check of LumenInfo with a THROWAWAY test key, then the host tests.
#
#   sh build.sh [OUT_APK]        default: build/LumenInfo-testkey.apk
#
# The manifest asks for sharedUserId android.uid.system, so a test-key APK does not install anywhere (Android
# refuses a shared-user certificate mismatch): it only proves that the sources build. The working build is
# build_hb.sh (goat_elegant platform key). The key pair is generated once into build/testkey/ (RSA 2048,
# self-signed, CN=LumenInfo throwaway test key) and is never an image key.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../.." && pwd)
K=$HERE/build/testkey
OUT=${1:-$HERE/build/LumenInfo-testkey.apk}
mkdir -p "$K"
if [ ! -f "$K/testkey.pk8" ] || [ ! -f "$K/testkey.x509.pem" ]; then
  echo "build.sh: generating throwaway test key in $K"
  (umask 077
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=LumenInfo throwaway test key" \
     -keyout "$K/testkey.key.pem" -out "$K/testkey.x509.pem" 2>/dev/null
   openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in "$K/testkey.key.pem" -out "$K/testkey.pk8"
   rm -f "$K/testkey.key.pem")
fi
KEY_PK8=$K/testkey.pk8 KEY_PEM=$K/testkey.x509.pem BUILD_DIR=$HERE/build/out-testkey \
  sh "$GSI/apps/build_apk.sh" "$HERE" "$OUT"
LUMEN_GSI=$GSI sh "$HERE/test/run.sh"
