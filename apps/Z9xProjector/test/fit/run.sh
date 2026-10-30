#!/bin/sh
# Fit to my screen size, the device-neutral core (fit/*.java except the Android screens and the adapters) on the
# host JDK, no Android class: docs/fit_screen/DESIGN.md section 11. sh test/fit/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
F=$HERE/src/org/lumen/projector/fit
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$F/FitGeometry.java" "$F/ScreenSize.java" "$F/ZoomModel.java" "$F/ThrowModel.java" "$F/FitPlanner.java" \
  "$F/FitDevice.java" "$F/FitRun.java" "$F/FitCalibration.java" "$F/FitStore.java" "$HERE/test/fit/FitTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.fit.FitTest
