#!/bin/sh
# Lumen OS: copy rule for the shared sources (PLAN.md C7 tokens, C13 GeoIp, XGIMI HAL transport, projector app
# code and resources, framework key RRO files). No shared runtime library:
# each app carries a byte-identical copy, so build_apk.sh needs no change and every APK stays standalone.
#
#   sh apps/common/sync.sh APP_DIR [tokens] [geoip] [hal] [projector] [keys]     copy (default: tokens geoip)
#   sh apps/common/sync.sh --check APP_DIR [tokens] [geoip] [hal] [projector] [keys]   exit 1 if a copy differs
#
# Copies:
#   common/tokens.xml  -> APP_DIR/res/values/z9x_tokens.xml
#   common/GeoIp.java  -> APP_DIR/src/org/lumen/common/GeoIp.java   (package org.lumen.common, unchanged)
#   migrate: common/LumenImport.java -> APP_DIR/src/org/lumen/common/LumenImport.java (the 1.0.8 import of the
#                      pre-1.0.8 org.z9x data through apps/LumenBridge): every app that had an org.z9x name
#   common/hal/HidlCaller.java -> APP_DIR/src/org/lumen/projector/hal/HidlCaller.java (package kept, so the
#                      device clients next to it need no change): every device projector app
#   projector: every PATH listed in common/projector/FILES -> APP_DIR/PATH, from common/projector/PATH
#                      (one path per line, relative to the app dir, no spaces; # starts a comment): every
#                      device projector app. Refused: a path under src/org/lumen/projector/hal/ (the device HAL
#                      clients are per device; HidlCaller is the `hal` item). Rules: common/projector/README.txt
#   keys:  common/overlay/xml/global_keys.xml -> APP_DIR/res/xml/global_keys.xml and
#          common/overlay/layout/shutdown_dialog.xml -> APP_DIR/res/layout/shutdown_dialog.xml: every device
#                      framework key RRO
# Never edit the copies; edit apps/common/* and re-run this for Z9xHome, Z9xSetup (and Z9xProjector
# once it adopts the tokens), with `hal projector` for every device projector app and with `keys` for every
# device framework key RRO.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
[ $# -ge 1 ] || { echo "usage: sync.sh [--check] APP_DIR [tokens] [geoip] [migrate] [hal] [projector] [keys]" >&2; exit 2; }
APP=$(cd "$1" && pwd); shift
WHAT=${*:-tokens geoip}
RC=0
# projector: every line of FILES is checked before the first copy, so a bad FILES copies nothing
PFILES=
case " $WHAT " in
  *" projector "*)
    F=$HERE/projector/FILES
    [ -f "$F" ] || { echo "sync: no $F" >&2; exit 2; }
    set -f   # FILES holds literal paths: no pathname expansion of its words
    for p in $(sed 's/#.*//' "$F"); do
      case $p in
        /*|..|../*|*/..|*/../*|*[*?[]*) echo "sync: $F: bad path $p" >&2; exit 2 ;;
        src/org/lumen/projector/hal/*) echo "sync: $F: $p is a device HAL file (never shared)" >&2; exit 2 ;;
      esac
      [ -f "$HERE/projector/$p" ] || { echo "sync: $F lists $p, but $HERE/projector/$p is missing" >&2; exit 2; }
      PFILES="$PFILES $p"
    done
    set +f ;;
esac
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
    geoip)  one "$HERE/GeoIp.java" "$APP/src/org/lumen/common/GeoIp.java" ;;
    migrate) one "$HERE/LumenImport.java" "$APP/src/org/lumen/common/LumenImport.java" ;;
    hal)    one "$HERE/hal/HidlCaller.java" "$APP/src/org/lumen/projector/hal/HidlCaller.java" ;;
    projector)
      set -f
      for p in $PFILES; do one "$HERE/projector/$p" "$APP/$p"; done
      set +f ;;
    keys)
      one "$HERE/overlay/xml/global_keys.xml" "$APP/res/xml/global_keys.xml"
      one "$HERE/overlay/layout/shutdown_dialog.xml" "$APP/res/layout/shutdown_dialog.xml" ;;
    *) echo "sync: unknown item $w" >&2; exit 2 ;;
  esac
done
exit $RC
