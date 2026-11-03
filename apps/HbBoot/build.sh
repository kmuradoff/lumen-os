#!/bin/sh
# build.sh: compile check of HbBoot with a THROWAWAY test key (never the image keys).
#
#   sh build.sh [OUT_APK]        default: build/HbBoot-testkey.apk
#
# The key pair is generated once into build/testkey/ (RSA 2048, self-signed, CN=HbBoot throwaway test
# key) and passed to gsi/apps/build_apk.sh through KEY_PK8 / KEY_PEM. build_apk.sh wipes its BUILD_DIR,
# so that is build/out/, next to the key and not around it. An APK signed with this key is only good
# for a compile check: it is not the platform certificate, so android.uid.system would refuse it.
# After the build the HAL gate runs on the APK (allowlist, banned and REVIEW rows, one IBinder.transact in the
# dex); a red gate fails the build.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../.." && pwd)
K=$HERE/build/testkey
OUT=${1:-$HERE/build/HbBoot-testkey.apk}
mkdir -p "$K"
if [ ! -f "$K/testkey.pk8" ] || [ ! -f "$K/testkey.x509.pem" ]; then
  echo "build.sh: generating throwaway test key in $K"
  (umask 077
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=HbBoot throwaway test key" \
     -keyout "$K/testkey.key.pem" -out "$K/testkey.x509.pem" 2>/dev/null
   openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in "$K/testkey.key.pem" -out "$K/testkey.pk8"
   rm -f "$K/testkey.key.pem")
fi
KEY_PK8=$K/testkey.pk8 KEY_PEM=$K/testkey.x509.pem BUILD_DIR=$HERE/build/out \
  sh "$GSI/apps/build_apk.sh" "$HERE" "$OUT"
# the HAL gate over the sources and the compiled dex (tools/devices/check_aidl_hal.py, rules 1 to 7)
python3 -B "$GSI/tools/devices/check_hal.py" --device goat_elegant --dex "$OUT"
