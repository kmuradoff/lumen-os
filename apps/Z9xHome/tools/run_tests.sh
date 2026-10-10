#!/bin/sh
# Lumen Home JVM tests (SPEC T12 IntentGuard, Ranker, Snapshot; 1.0.1: hero art layout, header contrast,
# UI modes 1080p / 2K / 4K, the files Install from USB offers)
# on the Mac JDK; android.jar only to link.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
AJ=${ANDROID_SDK:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
HJ=$HERE/../sdk/framework-full-headers.jar
[ -f "$HERE/build/gen/org/z9x/home/R.java" ] || { echo "build the APK first (needs build/gen/R.java)"; exit 2; }
# -sourcepath pulls in only what the tested classes reference; runtime never calls an android.jar stub
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" -cp "$HJ:$AJ" \
  -sourcepath "$HERE/src:$HERE/build/gen" "$HERE"/test/org/z9x/home/data/*.java \
  "$HERE"/test/org/z9x/home/sky/*.java 2>&1 | grep -v "^Note:" || true
# header contrast reads res/values/colors_lumen.xml
"$JAVA_HOME/bin/java" -Dhome.dir="$HERE" -cp "$OUT:$HJ:$AJ" org.z9x.home.data.AllTests
# Install from USB (1.0.1): the files of a stick that are offered; android.jar first (PackageManager classes)
"$JAVA_HOME/bin/java" -cp "$OUT:$AJ:$HJ" org.z9x.home.data.UsbApksTest
# screensaver one-shot decisions (1.0.1)
"$JAVA_HOME/bin/java" -cp "$OUT:$HJ:$AJ" org.z9x.home.sky.DreamDefaultTest
# the sky's bitmaps at 1080p / 2K / 4K (1.0.1); android.jar first: verifying SkyRenderer loads
# android.graphics classes, which the header jar has without code
"$JAVA_HOME/bin/java" -cp "$OUT:$AJ:$HJ" org.z9x.home.sky.SkySizeTest
# living sky (org.z9x.home.sky: SolarCalc + SkyLook, plain JDK, no R.java)
sh "$HERE/test/sky/run.sh"
