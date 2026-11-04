#!/bin/sh
# Brightness in 3D, the stock dlpLumensLevel / iRISDeviceNotThreeDimension rule (panel/Lamp3d is plain Java):
# sh test/lamp3d/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$HERE/src/org/lumen/projector/panel/Lamp3d.java" "$HERE/test/lamp3d/Lamp3dTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.projector.panel.Lamp3dTest
