#!/bin/sh
# Lumen Sensors host checks (plain Java, no android.jar): parsing, HAL structs and allowlist, the Z9X HAL
# read plan, formatting, levels, profile, static checks of the HAL path and the device separation over src/;
# the X50 (G0111) profile: its reply decoders on the X50's raw replies, read plan, allow list (X50Test) and
# tools/devices/check_aidl_hal.py on its AIDL client alone.
# sh test/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
G=$(cd "$HERE/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
C=$HERE/src/org/lumen/sensors/core
H=$HERE/src/org/lumen/sensors/hal
Z=$HERE/src/org/lumen/sensors/z9x
X=$HERE/src/org/lumen/sensors/x50
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$C/Group.java" "$C/Levels.java" "$C/Sample.java" "$C/Words.java" "$C/Fmt.java" "$C/Parse.java" \
  "$C/DeviceProfile.java" "$C/Registry.java" "$H/GmpfAllowlist.java" "$H/GmpfStructs.java" "$Z/Z9xSchedule.java" \
  "$H/X50Structs.java" "$X/X50Schedule.java" \
  "$HERE/test/org/lumen/sensors/core/SensorsTest.java" "$HERE/test/org/lumen/sensors/core/X50Test.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.sensors.core.SensorsTest \
  "$H/Z9xGmpfReader.java" "$H/HidlCaller.java" "$HERE/../common/hal/HidlCaller.java" "$HERE/src"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.sensors.core.X50Test "$HERE/src" "$G/devices"
# the X50 AIDL client on its own (rules 1-6; the rest of the app has the Z9X HIDL path, which rule 1 would flag)
mkdir -p "$OUT/x50app/src/org/lumen/sensors/hal"
cp "$H/X50Gmpf.java" "$OUT/x50app/src/org/lumen/sensors/hal/"
python3 -B "$G/tools/devices/check_aidl_hal.py" --app "$OUT/x50app" \
  --codes "$G/devices/g0111/research/aidl_codes.tsv" \
  --banned "$G/devices/goat_elegant/research/banned.tsv" \
  --allowed "$G/devices/g0111/research/allowed_lumensensors.tsv"
