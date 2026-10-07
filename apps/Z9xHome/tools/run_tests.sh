#!/bin/sh
# Lumen Home JVM tests (SPEC T12 IntentGuard, Ranker, Snapshot) on the Mac JDK; android.jar only to link.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
AJ=${ANDROID_SDK:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
HJ=$HERE/../sdk/framework-full-headers.jar
[ -f "$HERE/build/gen/org/z9x/home/R.java" ] || { echo "build the APK first (needs build/gen/R.java)"; exit 2; }
# -sourcepath pulls in only what the tested classes reference; runtime never calls an android.jar stub
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" -cp "$HJ:$AJ" \
  -sourcepath "$HERE/src:$HERE/build/gen" "$HERE"/test/org/z9x/home/data/*.java 2>&1 | grep -v "^Note:" || true
"$JAVA_HOME/bin/java" -cp "$OUT:$HJ:$AJ" org.z9x.home.data.AllTests
