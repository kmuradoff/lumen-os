#!/bin/bash
# build_play6.sh: build the APKs of the Play 6 port with the Play 6 platform key (docs/play6/SETTINGS_PLAN.md
# 2.1 rule S1, section 5 check B). Mac only, no device, no network.
#
#   tools/play6/build_play6.sh
#
# 1. checks H (check_play6_hal.py), K (check_handshake.py) and F (check_forks.py); any red stops the build
# 2. apps/build_apk.sh for apps/Play6Projector, apps/Play6FrameworkOverlay, apps/Play6Updater and, once it exists,
#    apps/Play6TvSettingsOverlay, signed with the Play 6 platform key -> build/play6/<App>.apk
#
# Key (S1: never a certificate a Z9X image trusts):
#   KEY_PK8  default ~/.lumen-keys/play6/platform.pk8 (made by tools/play6/gen_play6_keys.sh; the private key
#            stays on the Mac and outside this tree)
#   KEY_PEM  default tools/play6/certs/platform.x509.pem (the public cert, the only part in the tree)
# Refused: a key file inside the project tree, the public AOSP test certs (apps/sdk/platform.x509.pem,
# tools/sign/testcerts) and every Lumen release cert of tools/sign/release_certs/FINGERPRINTS.txt.
# build_apk.sh verifies after signing that the APK's signer is exactly KEY_PEM.
# OUT_DIR (default build/play6) takes the APKs; each app builds in OUT_DIR/apk/<App> (wiped by build_apk.sh),
# so apps/Play6*/build is not used.
# HOME_SRC=1 also builds Lumen Home from its source (apps/Z9xHome) with the Play 6 key -> OUT_DIR/Z9xHome.apk,
# for a test image that carries a Home newer than the Z9X pin (build_test.sh --home-src); TVINPUT_SRC=1 the same
# for the TV input app (apps/Z9xTvInput -> OUT_DIR/Z9xTvInput.apk, build_test.sh --tvinput-src), SETUP_SRC=1 for
# Lumen Setup (apps/Z9xSetup -> OUT_DIR/Z9xSetup.apk, build_test.sh --setup-src). Such a source
# build keeps the version of its pin: a manifest without versionCode / versionName (Z9xTvInput) gets the pin's
# (aapt2 dump badging of overlay/apps_v1/<App>.apk, as z9x_gate.sh rebuilds it); a manifest that has them keeps
# them. The Z9X pins in overlay/apps_v1 and the Z9X image are not touched.
set -euo pipefail
die() { echo "build_play6: $*" >&2; exit 1; }
G=$(cd "$(dirname "$0")/../.." && pwd)
P="$G/tools/play6"
OUT=${OUT_DIR:-$G/build/play6}
KEY_PK8=${KEY_PK8:-$HOME/.lumen-keys/play6/platform.pk8}
KEY_PEM=${KEY_PEM:-$P/certs/platform.x509.pem}

python3 -B "$P/check_play6_hal.py" "$G/apps/Play6Projector" || die "check H failed"
python3 -B "$P/check_handshake.py" "$G/apps/Play6Projector" || die "check K failed"
python3 -B "$P/check_forks.py" || die "check F failed"

[ -f "$KEY_PK8" ] || die "no Play 6 private key $KEY_PK8 (owner decision O1: tools/play6/gen_play6_keys.sh)"
[ -f "$KEY_PEM" ] || die "no Play 6 certificate $KEY_PEM"
KD=$(cd "$(dirname "$KEY_PK8")" && pwd -P)
case $KD in   # the same rule as tools/sign/gen_keys.sh
  *"XGIMI PLAY 6"*|*/gsi/*|*/gsi) die "the private key $KEY_PK8 lies inside the project tree" ;;
esac
FP=$(openssl x509 -in "$KEY_PEM" -outform DER | shasum -a 256 | cut -d' ' -f1)
for c in "$G/apps/sdk/platform.x509.pem" "$G"/tools/sign/testcerts/*.x509.pem; do
  [ -f "$c" ] || continue
  [ "$(openssl x509 -in "$c" -outform DER | shasum -a 256 | cut -d' ' -f1)" != "$FP" ] \
    || die "$KEY_PEM is the public AOSP test cert $(basename "$c") (rule S1)"
done
if grep -qi "$FP" "$G/tools/sign/release_certs/FINGERPRINTS.txt"; then
  die "$KEY_PEM is a Lumen release cert (rule S1)"
fi
echo "build_play6: Play 6 platform cert SHA-256 $FP"

mkdir -p "$OUT"
for app in Play6Projector Play6FrameworkOverlay Play6TvSettingsOverlay Play6Updater; do
  [ -d "$G/apps/$app" ] || continue
  BUILD_DIR="$OUT/apk/$app" KEY_PK8="$KEY_PK8" KEY_PEM="$KEY_PEM" \
    sh "$G/apps/build_apk.sh" "$G/apps/$app" "$OUT/$app.apk"
done
SRC=
[ "${HOME_SRC:-0}" = 1 ] && SRC="$SRC Z9xHome"
[ "${TVINPUT_SRC:-0}" = 1 ] && SRC="$SRC Z9xTvInput"
[ "${SETUP_SRC:-0}" = 1 ] && SRC="$SRC Z9xSetup"
AAPT2=${BUILD_TOOLS:-${ANDROID_SDK:-$HOME/Library/Android/sdk}/build-tools/36.0.0}/aapt2
for app in $SRC; do
  pin=$G/overlay/apps_v1/$app.apk
  badge=$("$AAPT2" dump badging "$pin" 2>/dev/null | head -1) || true
  vc=$(printf '%s' "$badge" | sed -n "s/.* versionCode='\([^']*\)'.*/\1/p")
  vn=$(printf '%s' "$badge" | sed -n "s/.* versionName='\([^']*\)'.*/\1/p")
  [ -n "$vc" ] && [ -n "$vn" ] || die "cannot read the version of $pin"
  key=   # Lumen Home: the TMDB key file when the pin carries one (z9x_gate.sh does the same)
  if [ "$app" = Z9xHome ] && unzip -l "$pin" assets/tmdb_key >/dev/null 2>&1; then key=${TMDB_KEY_FILE:-$HOME/.lumen-keys/tmdb.txt}; fi
  BUILD_DIR="$OUT/apk/$app" KEY_PK8="$KEY_PK8" KEY_PEM="$KEY_PEM" VERSION_CODE="$vc" VERSION_NAME="$vn" TMDB_KEY_FILE="$key" \
    sh "$G/apps/build_apk.sh" "$G/apps/$app" "$OUT/$app.apk"
done
echo "build_play6: OK ($OUT)"
