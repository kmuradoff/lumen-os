#!/bin/sh
# Lumen OS: copy rule for the shared sources (PLAN.md C7 tokens, C13 GeoIp, XGIMI HAL transport). No shared runtime library:
# each app carries a byte-identical copy, so build_apk.sh needs no change and every APK stays standalone.
#
#   sh apps/common/sync.sh APP_DIR [tokens] [geoip] [hal]     copy (default: tokens geoip)
#   sh apps/common/sync.sh --check APP_DIR [tokens] [geoip] [hal]   exit 1 if a copy differs from the source
#
# Copies:
#   common/tokens.xml  -> APP_DIR/res/values/z9x_tokens.xml
#   common/GeoIp.java  -> APP_DIR/src/org/z9x/common/GeoIp.java   (package org.z9x.common, unchanged)
#   common/hal/HidlCaller.java -> APP_DIR/src/org/z9x/projector/hal/HidlCaller.java (package kept, so the
#                      device clients next to it need no change): Z9xProjector, Play6Projector
# Never edit the copies; edit apps/common/* and re-run this for Z9xHome, Z9xSetup (and Z9xProjector
# once it adopts the tokens), and with `hal` for Z9xProjector and Play6Projector.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
[ $# -ge 1 ] || { echo "usage: sync.sh [--check] APP_DIR [tokens] [geoip] [hal]" >&2; exit 2; }
APP=$(cd "$1" && pwd); shift
WHAT=${*:-tokens geoip}
RC=0
one() { # src dst
  if [ $CHECK = 1 ]; then
    if ! cmp -s "$1" "$2"; then echo "sync: $2 differs from $1" >&2; RC=1; fi
  else
    mkdir -p "$(dirname "$2")"
    cp "$1" "$2"
    echo "sync: $2"
  fi
}
for w in $WHAT; do
  case $w in
    tokens) one "$HERE/tokens.xml" "$APP/res/values/z9x_tokens.xml" ;;
    geoip)  one "$HERE/GeoIp.java" "$APP/src/org/z9x/common/GeoIp.java" ;;
    hal)    one "$HERE/hal/HidlCaller.java" "$APP/src/org/z9x/projector/hal/HidlCaller.java" ;;
    *) echo "sync: unknown item $w" >&2; exit 2 ;;
  esac
done
exit $RC
