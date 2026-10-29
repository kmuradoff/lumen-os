#!/bin/sh
# Lumen OS 1.0.8: build the bridge APKs (one per pre-1.0.8 package name) with apps/build_apk.sh.
#   sh apps/LumenBridge/build_bridges.sh OUT_DIR [z9x|play6]
# Writes OUT_DIR/LumenBridge_<app>.apk, signed with build_apk.sh's key (the release signing step re-signs them
# like every other app). z9x: projector home setup tvinput updater airplay. play6: projector home setup tvinput,
# updater (system uid, as apps/Play6Updater).
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:?usage: build_bridges.sh OUT_DIR [z9x|play6]}; DEV=${2:-z9x}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
case $DEV in
  z9x) LIST="projector home setup tvinput updater airplay" ;;
  play6) LIST="projector home setup tvinput updater" ;;   # updater: only an --ota image installs it
  *) echo "build_bridges: device must be z9x or play6" >&2; exit 2 ;;
esac
T=$(mktemp -d "${TMPDIR:-/tmp}/lumenbridge.XXXXXX"); trap 'rm -rf "$T"' EXIT
for a in $LIST; do
  d="$T/$a"; mkdir -p "$d"; cp -R "$HERE/src" "$d/src"
  su=""; [ "$DEV/$a" = play6/updater ] && su=' android:sharedUserId="android.uid.system"'
  awk -v pkg="org.z9x.$a" -v su="$su" '{gsub(/@PKG@/, pkg); gsub(/@SHARED_UID@/, su); print}' \
    "$HERE/AndroidManifest.xml.in" > "$d/AndroidManifest.xml"
  BUILD_DIR="$T/b_$a" sh "$HERE/../build_apk.sh" "$d" "$OUT/LumenBridge_$a.apk"
done
echo "build_bridges: $DEV: $LIST -> $OUT"
