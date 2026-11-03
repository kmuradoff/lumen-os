#!/bin/sh
# ARC volume spike decisions (audio/HbAudioRules) and the sound output rows (audio/HbOutputRows), plain Java, no stubs,
# 2026-11-01: sh test/audio/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
T=$HERE/test/audio
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/projector/audio/HbAudioRules.java" "$T/HbAudioRulesTest.java" \
  "$HERE/src/org/lumen/projector/audio/HbOutputRows.java" "$T/HbOutputRowsTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.audio.HbAudioRulesTest
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.audio.HbOutputRowsTest
