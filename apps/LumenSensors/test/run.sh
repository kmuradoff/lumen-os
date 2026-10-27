#!/bin/sh
# Lumen Sensors host checks (plain Java, no android.jar): parsing, HAL structs and allowlist, the Z9X HAL
# read plan, formatting, levels, profile, static checks of the HAL path and the device separation over src/.
# sh test/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
C=$HERE/src/org/lumen/sensors/core
H=$HERE/src/org/lumen/sensors/hal
Z=$HERE/src/org/lumen/sensors/z9x
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$C/Group.java" "$C/Levels.java" "$C/Sample.java" "$C/Words.java" "$C/Fmt.java" "$C/Parse.java" \
  "$C/DeviceProfile.java" "$C/Registry.java" "$H/GmpfAllowlist.java" "$H/GmpfStructs.java" "$Z/Z9xSchedule.java" \
  "$HERE/test/org/lumen/sensors/core/SensorsTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.sensors.core.SensorsTest \
  "$H/Z9xGmpfReader.java" "$H/HidlCaller.java" "$HERE/../common/hal/HidlCaller.java" "$HERE/src"
