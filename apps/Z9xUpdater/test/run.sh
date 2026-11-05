#!/bin/sh
# Updater host checks (Outcome: the result after a restart, plain Java; 1.0.1: the edition's manifest file; the
# Beta updates switch): sh test/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/updater/Outcome.java" "$HERE/test/org/lumen/updater/OutcomeTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.updater.OutcomeTest
# 1.0.1: the edition's manifest file (Ota.channelFile; Ota links android classes: android.jar stubs)
# (hidden APIs: our framework header jar to compile, android.jar first to run)
AJ=${ANDROID_SDK:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar
HJ=$HERE/../sdk/framework-full-headers.jar
mkdir -p "$OUT/ch"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT/ch" -cp "$HJ:$AJ" \
  "$HERE/src/org/lumen/updater/Ota.java" "$HERE/test/org/lumen/updater/ChannelTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT/ch:$AJ:$HJ" org.lumen.updater.ChannelTest
# Beta updates switch: the channel read and no downgrade when it is turned off (Ota, Preflight with the
# SystemProperties stand-in of test/stubs, which comes first on the class path)
mkdir -p "$OUT/beta"
S=$HERE/src/org/lumen/updater
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT/beta" -cp "$HJ:$AJ" \
  "$HERE/test/stubs/android/os/SystemProperties.java" "$S/Ota.java" "$S/Preflight.java" "$S/Engine.java" \
  "$S/UpdateManifest.java" "$HERE/test/org/lumen/updater/BetaTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT/beta:$AJ:$HJ" org.lumen.updater.BetaTest
# Anonymous statistics: the body has exactly the documented fields
mkdir -p "$OUT/stats"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT/stats" -cp "$HJ:$AJ" -sourcepath "$HERE/src" \
  "$S/Stats.java" "$HERE/test/org/lumen/updater/StatsTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT/stats:$AJ:$HJ" org.lumen.updater.StatsTest
