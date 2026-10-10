#!/bin/sh
# Interface resolution, boot reason and AK 109 unit host checks (display/UiRes, sys/BootReason and
# ak/AkPatternSpec are plain Java): sh test/uires/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/z9x/projector/display/UiRes.java" "$HERE/src/org/z9x/projector/sys/BootReason.java" \
  "$HERE/src/org/z9x/projector/ak/AkPatternSpec.java" \
  "$HERE/test/uires/UiResTest.java" "$HERE/test/uires/AkUnitsTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.z9x.projector.display.UiResTest
"$JAVA_HOME/bin/java" -cp "$OUT" org.z9x.projector.ak.AkUnitsTest
