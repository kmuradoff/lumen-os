#!/bin/sh
# Network folders host checks (org.lumen.home.net SMB2 client: plain Java, no android.jar): sh test/net/run.sh
set -eu
HERE=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
N=$HERE/src/org/lumen/home/net
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -proc:none -d "$OUT" \
  "$N/Le.java" "$N/SmbCrypto.java" "$N/Ntlm.java" "$N/Spnego.java" "$N/Srvsvc.java" "$N/SmbException.java" \
  "$N/Smb2.java" "$HERE/test/net/SmbTest.java" "$HERE/test/net/SmbLoopTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT" org.lumen.home.net.SmbTest
