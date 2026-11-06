#!/bin/sh
# Host tests of the device-neutral updater layer. tools/devices/check.sh
# runs this in the assembled tree (test/*/run.sh, LUMEN_GSI = gsi/); by hand: sh apps/LumenUpdater/test/ota/run.sh
# (the tree is then assembled first, into a temp dir, from the first devices/*/app/LumenUpdater.layers).
#   1. recipe copies: every devices/*/app/LumenUpdater.layers is a byte copy of apps/LumenUpdater/LumenUpdater.layers
#     
#   2. strings: key parity of strings_lumen_ota.xml across all locales of the app (strings_parity.py)
#   3. ChannelTest, PreflightHbTest, PreflightGateTest and BetaTest (Ota and Preflight with the SystemProperties
#      stand-in of stubs/; the gate probe with a fake gate; the Beta updates switch and no downgrade), and the Z9X
#      OutcomeTest unchanged
# Mac only, no device, no network. Writes only a temp dir.
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
G=${LUMEN_GSI:-$(cd "$HERE/../.." && pwd)}
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
AJ=${ANDROID_SDK:-$HOME/Library/Android/sdk}/platforms/android-34/android.jar
HJ=$G/apps/sdk/framework-full-headers.jar
OUT=$(mktemp -d "${TMPDIR:-/tmp}/lumen_updater_test.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

n=0
for r in "$G"/devices/*/app/LumenUpdater.layers; do
  [ -f "$r" ] || continue
  if ! cmp -s "$G/apps/LumenUpdater/LumenUpdater.layers" "$r"; then
    echo "FAIL ${r#"$G"/} is not a byte copy of apps/LumenUpdater/LumenUpdater.layers"
    exit 1
  fi
  n=$((n + 1))
  [ -n "${RCP:-}" ] || RCP=$r
done
[ $n -gt 0 ] || { echo "FAIL no device copy devices/*/app/LumenUpdater.layers"; exit 1; }
echo "recipe copies: $n identical to apps/LumenUpdater/LumenUpdater.layers"

T=$HERE
if [ ! -f "$T/src/org/lumen/updater/Engine.java" ]; then
  python3 -B "$G/tools/devices/assemble_app.py" --root "$G" "$RCP" --out "$OUT/tree" --quiet
  T=$OUT/tree
fi

python3 -B "$T/test/ota/strings_parity.py" "$T" "$G/apps/Z9xUpdater/res"

S=$T/src/org/lumen/updater
mkdir -p "$OUT/j"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT/j" -cp "$HJ:$AJ" \
  "$T/test/ota/stubs/android/os/SystemProperties.java" "$S/Ota.java" "$S/Channels.java" "$S/Preflight.java" "$S/Engine.java" \
  "$S/UpdateManifest.java" "$S/Outcome.java" "$T/test/org/lumen/updater/ChannelTest.java" \
  "$T/test/org/lumen/updater/PreflightHbTest.java" "$T/test/org/lumen/updater/PreflightGateTest.java" \
  "$T/test/org/lumen/updater/OutcomeTest.java" "$T/test/org/lumen/updater/BetaTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT/j:$AJ:$HJ" org.lumen.updater.OutcomeTest
"$JAVA_HOME/bin/java" -cp "$OUT/j:$AJ:$HJ" org.lumen.updater.ChannelTest
"$JAVA_HOME/bin/java" -cp "$OUT/j:$AJ:$HJ" org.lumen.updater.PreflightHbTest
"$JAVA_HOME/bin/java" -cp "$OUT/j:$AJ:$HJ" org.lumen.updater.PreflightGateTest
"$JAVA_HOME/bin/java" -cp "$OUT/j:$AJ:$HJ" org.lumen.updater.BetaTest
echo "LumenUpdater host tests: green"
