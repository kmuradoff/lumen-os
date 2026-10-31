#!/bin/sh
# AF "after" queue host checks (AfAfter is plain Java): sh test/afcard/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/projector/AfAfter.java" "$HERE/test/afcard/AfAfterTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.AfAfterTest
