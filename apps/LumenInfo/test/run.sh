#!/bin/sh
# LumenInfo host tests (plain Java, no android.jar): EDID decoder, dump parsers, HB facts, the HAL allow list
# against devices/goat_elegant/research/*.tsv, static checks of src/.   sh test/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
GSI=${LUMEN_GSI:-$(cd "$HERE/../.." && pwd)}
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
C=$HERE/src/org/lumen/info/core
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$C/Vic.java" "$C/Fmt.java" "$C/Edid.java" "$C/Parse.java" "$C/Hb.java" "$C/HalStructs.java" \
  "$HERE/test/org/lumen/info/core/InfoTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.info.core.InfoTest "$GSI" "$HERE"
