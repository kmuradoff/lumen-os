#!/bin/sh
# Updater host checks (Outcome: the result after a restart, plain Java; 1.0.1: the edition's manifest file): sh test/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/z9x/updater/Outcome.java" "$HERE/test/org/z9x/updater/OutcomeTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.z9x.updater.OutcomeTest
# 1.0.1: the edition's manifest file (Ota.channelFile; Ota links android classes: android.jar stubs)
# (hidden APIs: our framework header jar to compile, android.jar first to run)
AJ=${ANDROID_SDK:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar
HJ=$HERE/../sdk/framework-full-headers.jar
mkdir -p "$OUT/ch"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT/ch" -cp "$HJ:$AJ" \
  "$HERE/src/org/z9x/updater/Ota.java" "$HERE/test/org/z9x/updater/ChannelTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT/ch:$AJ:$HJ" org.z9x.updater.ChannelTest
