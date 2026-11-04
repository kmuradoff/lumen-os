#!/bin/sh
# Manual keystone top-speed game lock host checks (panel/KstGameLock is plain Java): sh test/kstgame/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/projector/panel/KstGameLock.java" "$HERE/test/kstgame/KstGameLockTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.panel.KstGameLockTest
