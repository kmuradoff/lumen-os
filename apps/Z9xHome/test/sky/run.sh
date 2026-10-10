#!/bin/sh
# Live sky host checks (SolarCalc + SkyLook are plain Java): sh test/sky/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/z9x/home/sky/SolarCalc.java" "$HERE/src/org/z9x/home/sky/SkyLook.java" \
  "$HERE/test/sky/SolarCalcTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.z9x.home.sky.SolarCalcTest
