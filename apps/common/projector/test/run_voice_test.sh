#!/bin/sh
# The assistant's offline parts (voice/Commands, voice/AppMatcher) on the Mac JDK, no Android classes.
set -eu
H=$(cd "$(dirname "$0")/.." && pwd)
O=$(mktemp -d); trap 'rm -rf "$O"' EXIT
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$O" "$H/src/org/z9x/projector/voice/Commands.java" \
  "$H/src/org/z9x/projector/voice/AppMatcher.java" "$H/test/org/z9x/projector/voice/VoiceTest.java"
"$JAVA_HOME/bin/java" -cp "$O" org.z9x.projector.voice.VoiceTest
