#!/bin/sh
# build.sh: compile check of HbProjector with a THROWAWAY test key (never the image keys).
#
#   sh build.sh [OUT_APK]        default: build/HbProjector-testkey.apk
#
# The image build signs with the goat_elegant platform key: sh tools/devices/build_apps.sh goat_elegant.
# Since 2026-10-30 this folder is only the HB layer: the real forks (each with a
# row in devices/goat_elegant/forks.tsv) and the HB-only files. The full tree is assembled from the recipe
# devices/goat_elegant/app/HbProjector.layers (apps/Z9xProjector minus the Z9X-only files, then this folder on top),
# so the HB follows Z9X changes automatically. This script:
#   1. the fork rows (tools/devices/check_forks.py --device goat_elegant);
#   2. assembles the tree into build/assembled/ (tools/devices/assemble_app.py: shadow rules; an origin that changed
#      under a fork is a warning here, red only with --release in tools/devices/check.sh --release);
#   3. build_apk.sh on the assembled tree with a key pair generated once into build/testkey/ (RSA 2048,
#      self-signed, CN=HbProjector throwaway test key); BUILD_DIR is build/out/;
#   4. the HAL gate over the sources and the compiled dex (tools/devices/check_aidl_hal.py, rules 1 to 7, through
#      check_hal.py --device goat_elegant, which assembles the hal_app the same way);
#   5. the plain-Java host tests of the assembled tree (test/*/run.sh: the Z9X tests and the HB ones).
# A red step fails the build. An APK signed with this key is only good for a compile check: it is not the
# platform certificate, so android.uid.system would refuse it.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../.." && pwd)
K=$HERE/build/testkey
T=$HERE/build/assembled
OUT=${1:-$HERE/build/HbProjector-testkey.apk}
python3 -B "$GSI/tools/devices/check_forks.py" --device goat_elegant
python3 -B "$GSI/tools/devices/assemble_app.py" --device goat_elegant --app HbProjector --out "$T"
mkdir -p "$K"
if [ ! -f "$K/testkey.pk8" ] || [ ! -f "$K/testkey.x509.pem" ]; then
  echo "build.sh: generating throwaway test key in $K"
  (umask 077
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=HbProjector throwaway test key" \
     -keyout "$K/testkey.key.pem" -out "$K/testkey.x509.pem" 2>/dev/null
   openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in "$K/testkey.key.pem" -out "$K/testkey.pk8"
   rm -f "$K/testkey.key.pem")
fi
KEY_PK8=$K/testkey.pk8 KEY_PEM=$K/testkey.x509.pem BUILD_DIR=$HERE/build/out \
  sh "$GSI/apps/build_apk.sh" "$T" "$OUT"
python3 -B "$GSI/tools/devices/check_hal.py" --device goat_elegant --dex "$OUT"
for t in "$T"/test/*/run.sh; do LUMEN_GSI=$GSI sh "$t"; done
