#!/bin/sh
# compare_hal_api.sh: the public members of the Z9X and HB hal clients must be equal,
# except the known HB changes below. Builds apps/Z9xProjector and apps/HbProjector into a scratch dir with the
# test key of apps/sdk (nothing is written into the tree), then compares javap -public of GmpfClient, Gmpf2Client
# and KstPoint with their public nested types. The Z9X class FocusCallback is package-private (its public methods
# implement IHwBinder) and the superclass name differs (HidlCaller / AidlCaller): both are left out.
#
#   sh devices/goat_elegant/image/compare_hal_api.sh
#
# Allowed differences (anything else is red):
#   connect(IHwBinder$DeathRecipient, long) -> connect(GmpfClient$DeathListener)  
#   GmpfClient$DeathListener (new, the listener of connect; its closing brace is the one extra '}' line)
#   setBootAnimRunning() (new, HbBoot row A of the HB handshake, section 7.1)
set -eu
G=$(cd "$(dirname "$0")/../../.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/hal_api.XXXXXX")
trap 'rm -rf "$W"' EXIT
JH=${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17)}
# the HB app is assembled from its recipe first
python3 -B "$G/tools/devices/assemble_app.py" --device goat_elegant --app HbProjector --out "$W/tree/HbProjector" --quiet \
  || { echo "compare_hal_api: the HbProjector recipe does not assemble" >&2; exit 1; }
for a in Z9xProjector HbProjector; do
  src=$G/apps/$a
  [ $a = HbProjector ] && src=$W/tree/HbProjector
  BUILD_DIR=$W/$a sh "$G/apps/build_apk.sh" "$src" "$W/$a.apk" > "$W/$a.log" 2>&1 \
    || { cat "$W/$a.log" >&2; echo "compare_hal_api: $a does not build" >&2; exit 1; }
done
api() { # classes dir, class
  ( cd "$1/org/lumen/projector/hal" && for f in "$2.class" "$2"\$*.class; do
      case $f in *FocusCallback*) continue ;; esac
      [ -f "$f" ] && "$JH/bin/javap" -public "$f"
    done ) | grep -v '^Compiled from' | sed -E 's/ extends org\.lumen\.projector\.hal\.(HidlCaller|AidlCaller)//' | sort
}
RC=0
for c in GmpfClient Gmpf2Client KstPoint; do
  api "$W/Z9xProjector/classes" "$c" > "$W/z.$c"
  api "$W/HbProjector/classes" "$c" > "$W/h.$c"
  diff "$W/z.$c" "$W/h.$c" | grep '^[<>]' | grep -v -E \
    -e '^< +public boolean connect\(android\.os\.IHwBinder\$DeathRecipient, long\);$' \
    -e '^> +public boolean connect\(org\.lumen\.projector\.hal\.GmpfClient\$DeathListener\);$' \
    -e '^> public interface org\.lumen\.projector\.hal\.GmpfClient\$DeathListener \{$' -e '^> \}$' \
    -e '^> +public abstract void onVendorDied\(\);$' \
    -e '^> +public void setBootAnimRunning\(\) throws android\.os\.RemoteException;$' > "$W/d.$c" || true
  if [ -s "$W/d.$c" ]; then
    echo "compare_hal_api: $c differs beyond the allowed changes:"; cat "$W/d.$c"; RC=1
  else
    echo "compare_hal_api: $c: public members equal (allowed HB changes only)"
  fi
done
exit $RC
