#!/bin/sh
# run.sh: render Lumen Home's Inputs tiles on the test emulator (docs/play6/impl/t14-home.md, t17).
#
#   sh tools/play6/home_tile_shots/run.sh SERIAL OUT_DIR [before]
#
# SERIAL must be an emulator (emulator-NNNN, e.g. the AVD kino_test: TV 1920 x 1080, density 320, started with
# -read-only); a projector is refused. A private adb server is used when ANDROID_ADB_SERVER_PORT is set.
# Builds the harness APK in a scratch dir from Home's own src/, res/ and assets/ (CARDVIEW_SRC overrides
# ui/CardView.java, e.g. a saved copy of the old file for "before") plus app/ (manifest without HOME filter,
# org.z9x.home.shots.TileShotsActivity), installs it, runs it, pulls the PNGs and report.tsv into OUT_DIR and
# uninstalls it again. If the emulator already has an org.z9x.home, REPLACE_HOME=1 allows replacing it (only on an
# emulator started -read-only, which discards it at shutdown). Nothing in apps/ is written; scratch under TMPDIR.
set -eu
[ $# -ge 2 ] || { echo "usage: run.sh SERIAL OUT_DIR [before]" >&2; exit 2; }
SERIAL=$1; OUT=$2; MODE=${3:-}
case $SERIAL in emulator-*) ;; *) echo "run.sh: $SERIAL is not an emulator; refused" >&2; exit 2 ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
HOME_APP=$G/apps/Z9xHome
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
W=$(mktemp -d "${TMPDIR:-/tmp}/home_tile_shots.XXXXXX")
A=$W/app
mkdir -p "$A"
cp -R "$HOME_APP/src" "$HOME_APP/res" "$HOME_APP/assets" "$A/"
cp "$HERE/app/AndroidManifest.xml" "$A/AndroidManifest.xml"
cp -R "$HERE/app/src/." "$A/src/"
[ -z "${CARDVIEW_SRC:-}" ] || cp "$CARDVIEW_SRC" "$A/src/org/z9x/home/ui/CardView.java"
BUILD_DIR=$W/build sh "$G/apps/build_apk.sh" "$A" "$W/shots.apk" > "$W/build.log" 2>&1 \
  || { tail -30 "$W/build.log" >&2; exit 1; }
if [ "${REPLACE_HOME:-0}" != 1 ] && "$ADB" -s "$SERIAL" shell pm path org.z9x.home 2>/dev/null | grep -q package:; then
  echo "run.sh: org.z9x.home is already installed on $SERIAL; refused (REPLACE_HOME=1 on a -read-only emulator)" >&2; exit 2
fi
[ "${REPLACE_HOME:-0}" != 1 ] || "$ADB" -s "$SERIAL" uninstall org.z9x.home >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" install -r "$W/shots.apk" >/dev/null
D=/sdcard/Android/data/org.z9x.home/files/shots
if [ "$MODE" = before ]; then EXTRA="--ez before true"; else EXTRA=""; fi
"$ADB" -s "$SERIAL" shell am start -W -n org.z9x.home/.shots.TileShotsActivity $EXTRA >/dev/null
i=0
until "$ADB" -s "$SERIAL" shell test -f "$D/done.txt"; do
  i=$((i + 1)); [ $i -le 300 ] || { echo "run.sh: no done.txt after 300 s" >&2; exit 1; }
  sleep 1
done
mkdir -p "$OUT"
"$ADB" -s "$SERIAL" pull "$D/." "$OUT" >/dev/null
"$ADB" -s "$SERIAL" uninstall org.z9x.home >/dev/null
echo "run.sh: $(ls "$OUT"/*.png | wc -l | tr -d ' ') PNGs in $OUT ($(cat "$OUT/done.txt")); scratch $W"
