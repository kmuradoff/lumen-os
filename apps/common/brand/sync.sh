#!/bin/sh
# Lumen OS brand drawables: copy rule (same model as apps/common/sync.sh: no shared library, each app
# carries byte-identical copies, build_apk.sh needs no change).
#
#   sh apps/common/brand/sync.sh APP_DIR NAME...           copy drawable/NAME.xml -> APP_DIR/res/drawable/
#   sh apps/common/brand/sync.sh --check APP_DIR NAME...   exit 1 if a copy differs from the source
#
# The source files are generated: edit tools/brand/lumen_brand.py (geometry) or
# tools/brand/gen_drawables.py (layout) and run  python3 tools/brand/gen_drawables.py , never the XML.
#
# Suggested names per app (manifest: android:icon / android:banner):
#   Z9xHome      brand_icon_home brand_banner_home      (+ lumen_mark lumen_mark_small lumen_wordmark)
#   Z9xProjector brand_icon_projector brand_banner_projector
#   Z9xUpdater   brand_icon_updater brand_banner_updater (+ lumen_mark_mono for its notifications)
#   Z9xAirPlay   brand_icon_airplay brand_banner_airplay (+ lumen_mark_mono for its notifications)
#   Z9xSetup     brand_icon_setup brand_banner_setup    (+ lumen_mark lumen_wordmark)
#   Z9xTvInput   brand_icon_tvinput brand_banner_tvinput
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CHECK=0
if [ "${1:-}" = "--check" ]; then CHECK=1; shift; fi
[ $# -ge 2 ] || { echo "usage: sync.sh [--check] APP_DIR NAME..." >&2; exit 2; }
APP=$(cd "$1" && pwd); shift
RC=0
for n in "$@"; do
  n=${n%.xml}
  src=$HERE/drawable/$n.xml
  dst=$APP/res/drawable/$n.xml
  [ -f "$src" ] || { echo "sync: no such brand drawable: $n" >&2; exit 2; }
  if [ $CHECK = 1 ]; then
    if ! cmp -s "$src" "$dst"; then echo "sync: $dst differs from $src" >&2; RC=1; fi
  else
    mkdir -p "$APP/res/drawable"
    cp "$src" "$dst"
    echo "sync: $dst"
  fi
done
exit $RC
