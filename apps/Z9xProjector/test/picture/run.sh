#!/bin/sh
# Colour temperature row host checks (panel/ColorTempRule is plain Java): sh test/picture/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/projector/panel/ColorTempRule.java" "$HERE/test/picture/ColorTempRuleTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.panel.ColorTempRuleTest "$HERE"
