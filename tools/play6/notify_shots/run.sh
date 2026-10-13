#!/bin/sh
# run.sh: render the notification cards on the test emulator (docs/play6/impl/t17-notify.md).
#
#   sh tools/play6/notify_shots/run.sh SERIAL OUT_DIR [before]
#
# SERIAL must be an emulator (emulator-NNNN, e.g. the AVD kino_test: TV 1920 x 1080, density 320, started with
# -read-only); a projector is refused. Builds the harness APK in a scratch dir from app/ plus the real sources
# (apps/common/projector Notify.java, Theme.java and TextRow.java, apps/Z9xTvInput BrandCard.java and its glyphs; override with
# NOTIFY_SRC / BRANDCARD_SRC, e.g. a saved copy of the old file for "before"), the card list from cards.py,
# installs it as org.z9x.shots, runs it and pulls the PNGs and report.tsv into OUT_DIR. "before": frames only.
# Nothing in apps/ is written; the scratch dir is under TMPDIR.
set -eu
[ $# -ge 2 ] || { echo "usage: run.sh SERIAL OUT_DIR [before]" >&2; exit 2; }
SERIAL=$1; OUT=$2; MODE=${3:-}
case $SERIAL in emulator-*) ;; *) echo "run.sh: $SERIAL is not an emulator; refused" >&2; exit 2 ;; esac
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
NOTIFY_SRC=${NOTIFY_SRC:-$G/apps/common/projector/src/org/z9x/projector/ui/Notify.java}
BRANDCARD_SRC=${BRANDCARD_SRC:-$G/apps/Z9xTvInput/src/org/z9x/tvinput/BrandCard.java}
W=$(mktemp -d "${TMPDIR:-/tmp}/notify_shots.XXXXXX")
A=$W/app
cp -R "$HERE/app" "$A"
cp "$NOTIFY_SRC" "$A/src/org/z9x/projector/ui/Notify.java"
for f in Theme TextRow; do
  cp "$G/apps/common/projector/src/org/z9x/projector/ui/$f.java" "$A/src/org/z9x/projector/ui/$f.java"
done
cp "$BRANDCARD_SRC" "$A/src/org/z9x/tvinput/BrandCard.java"
mkdir -p "$A/res/drawable" "$A/assets"
cp "$G"/apps/Z9xTvInput/res/drawable/ic_banner_*.xml "$A/res/drawable/"
python3 -B "$HERE/cards.py" "$G" "$A/assets/cards.tsv"
BUILD_DIR=$W/build sh "$G/apps/build_apk.sh" "$A" "$W/shots.apk" > "$W/build.log" 2>&1 \
  || { tail -30 "$W/build.log" >&2; exit 1; }
"$ADB" -s "$SERIAL" install -r "$W/shots.apk" >/dev/null
"$ADB" -s "$SERIAL" shell am force-stop org.z9x.shots
D=/sdcard/Android/data/org.z9x.shots/files/shots
"$ADB" -s "$SERIAL" shell rm -rf "$D"
if [ "$MODE" = before ]; then EXTRA="--ez before true"; else EXTRA=""; fi
"$ADB" -s "$SERIAL" shell am start -W -n org.z9x.shots/.ShotsActivity $EXTRA >/dev/null
i=0
until "$ADB" -s "$SERIAL" shell test -f "$D/done.txt"; do
  i=$((i + 1)); [ $i -le 600 ] || { echo "run.sh: no done.txt after 600 s" >&2; exit 1; }
  sleep 1
done
mkdir -p "$OUT"
"$ADB" -s "$SERIAL" pull "$D/." "$OUT" >/dev/null
"$ADB" -s "$SERIAL" shell am force-stop org.z9x.shots
echo "run.sh: $(ls "$OUT"/*.png | wc -l | tr -d ' ') PNGs in $OUT ($(cat "$OUT/done.txt")); scratch $W"
