#!/bin/sh
# eARC decisions (cec/EarcRules is plain Java): sh test/earc/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/projector/cec/EarcRules.java" "$HERE/test/earc/EarcRulesTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.cec.EarcRulesTest
