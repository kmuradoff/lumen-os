#!/bin/sh
# check.sh: the one command that runs every check of the device tree (docs/architecture/PLAN.md, step 2).
#
#   sh tools/devices/check.sh [--release] (CODENAME | all)
#
# Per device of the registry (tools/devices/devices.py list), in this order:
#   recipes   each devices/<c>/app/*.layers: tools/devices/assemble_app.py into a scratch dir (shadow rules; an
#             override whose origin changed is a warning, red with --release); a recipe still in compare mode
#             ('identical allow') must also equal apps/<App> file by file; every strings file of the top layer's
#             res/values has all the locales of the app; the assembled tree compiles (apps/build_apk.sh, a
#             throwaway key made once in build/devices_check/testkey, never an image key); the hal_app of an
#             aidl-table device also passes check_hal.py --dex on that APK; host tests test/*/run.sh of the
#             assembled tree.
#   forks     tools/devices/check_forks.py --device <c>
#   hal       tools/devices/check_hal.py --device <c>
#   device    horse_popular: the Z9X gate (tools/play6/z9x_gate.sh, incremental; it never records);
#             dragon_healthy: check K (tools/play6/check_handshake.py)
# Once: the tests in tools/devices/test/*.sh and the publish audit (to a temp manifest; no REVIEW row).
# Mac only, no device, no network, no key of an image. Writes only build/devices_check/ (the throwaway key) and
# what the Z9X gate writes itself (build/play6/z9x_gate.log). Prints one line per step and a summary.
# Exit 0 when every step is green, 1 otherwise, 2 usage.
set -u
G=$(cd "$(dirname "$0")/../.." && pwd)
cd "$G" || exit 2
REL=
if [ "${1:-}" = "--release" ]; then REL=--release; shift; fi
[ $# = 1 ] || { echo "usage: check.sh [--release] (CODENAME | all)" >&2; exit 2; }
ALL=$(python3 -B tools/devices/devices.py list | awk '{ print $1 }') || exit 2
if [ "$1" = all ]; then DEVS=$ALL; else
  echo "$ALL" | grep -qx "$1" || { echo "check.sh: unknown device $1 (devices.py list)" >&2; exit 2; }
  DEVS=$1
fi
W=$(mktemp -d "${TMPDIR:-/tmp}/devices_check.XXXXXX") || exit 2
trap 'rm -rf "$W"' EXIT
RED=0
SUM=$W/summary
: > "$SUM"
step() { # name command...: runs it, log in $W/log/<name>.log, one line (variables _sn _rc: the callers' stay)
  _sn=$1; shift
  mkdir -p "$W/log"
  "$@" > "$W/log/$_sn.log" 2>&1
  _rc=$?
  if [ $_rc = 0 ]; then echo "ok   $_sn: $(tail -1 "$W/log/$_sn.log")" | tee -a "$SUM"
  else RED=1; echo "RED  $_sn (exit $_rc): $(tail -1 "$W/log/$_sn.log")" | tee -a "$SUM"; sed 's/^/     /' "$W/log/$_sn.log" | tail -25; fi
  return $_rc
}
reg() { python3 -B tools/devices/devices.py get "$1" "$2"; }

K=$G/build/devices_check/testkey
if [ ! -f "$K/testkey.pk8" ] || [ ! -f "$K/testkey.x509.pem" ]; then
  mkdir -p "$K"
  (umask 077
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=Lumen devices check throwaway key" \
     -keyout "$K/testkey.key.pem" -out "$K/testkey.x509.pem" 2>/dev/null
   openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in "$K/testkey.key.pem" -out "$K/testkey.pk8"
   rm -f "$K/testkey.key.pem")
fi

strings_locales() { # assembled-tree top-layer recipe: every res/values/strings*.xml of the top layer's own (one no
  # base has) must exist in every locale dir of the assembled tree (variables s_*: the callers' stay)
  s_t=$1 s_top=$2 s_rcp=$3 s_bad=0 s_n=0
  [ -d "$s_top/res/values" ] || { echo "no res/values in the top layer"; return 0; }
  s_locs=$(cd "$s_t/res" && ls -d values-* 2>/dev/null | grep -E '^values-[a-z]{2}(-r[A-Z]{2})?$')
  for s_f in "$s_top"/res/values/strings*.xml; do
    [ -f "$s_f" ] || continue
    s_b=$(basename "$s_f")
    # only the top layer's own strings files: a file of the same name in a base is the base's (its locales are
    # the base app's business)
    s_own=1
    for s_bd in $(awk '$1 == "base" { print $2 }' "$s_rcp"); do [ -f "$G/$s_bd/res/values/$s_b" ] && s_own=0; done
    [ $s_own = 1 ] || continue
    for s_l in $s_locs; do
      if [ ! -f "$s_t/res/$s_l/$s_b" ]; then echo "missing res/$s_l/$s_b"; s_bad=1; fi
    done
    s_n=$((s_n + 1))
  done
  echo "strings: $s_n top-layer file(s), $(echo "$s_locs" | grep -c .) locales each"
  return $s_bad
}

recipe_steps() { # codename recipe
  c=$1 r=$2
  app=$(awk '$1 == "app" { print $2; exit }' "$r")
  top=$(awk '$1 == "top" { print $2; exit }' "$r")
  out=$W/apps/$c/$app
  cmp_arg=
  grep -qE '^[[:space:]]*identical[[:space:]]+allow' "$r" && cmp_arg="--compare $top"
  # shellcheck disable=SC2086
  step "$c.$app.assemble" python3 -B tools/devices/assemble_app.py $REL "$r" --out "$out" $cmp_arg || return
  [ -d "$out" ] || return
  step "$c.$app.strings" strings_locales "$out" "$G/$top" "$r"
  step "$c.$app.compile" env KEY_PK8="$K/testkey.pk8" KEY_PEM="$K/testkey.x509.pem" BUILD_DIR="$W/build/$c/$app" \
    sh apps/build_apk.sh "$out" "$W/apk/$c/$app.apk"
  if [ "$(reg "$c" hal_check)" = aidl-table ] && [ "$(reg "$c" hal_app)" = "$top" ] && [ -f "$W/apk/$c/$app.apk" ]; then
    step "$c.$app.hal_dex" python3 -B tools/devices/check_hal.py --device "$c" --dex "$W/apk/$c/$app.apk"
  fi
  for t in "$out"/test/*/run.sh; do
    [ -f "$t" ] || continue
    tn=$(basename "$(dirname "$t")")
    step "$c.$app.test_$tn" env LUMEN_GSI="$G" sh "$t"
  done
}

for c in $DEVS; do
  for r in devices/"$c"/app/*.layers; do
    [ -f "$r" ] && recipe_steps "$c" "$r"
  done
  step "$c.forks" python3 -B tools/devices/check_forks.py --device "$c"
  step "$c.hal" python3 -B tools/devices/check_hal.py --device "$c"
  case $c in
    horse_popular) step "$c.z9x_gate" sh tools/play6/z9x_gate.sh ;;
    dragon_healthy) step "$c.check_k" python3 -B tools/play6/check_handshake.py apps/Play6Projector ;;
  esac
done

for t in tools/devices/test/*.sh; do
  step "test.$(basename "$t" .sh)" bash "$t"
done
publish() {
  python3 -B docs/repo/publish_audit.py . "$W/manifest.tsv" || return 1
  n=$(awk -F'\t' '!/^#/ && $2 == "REVIEW"' "$W/manifest.tsv" | wc -l | tr -d ' ')
  awk -F'\t' '!/^#/ && $2 == "REVIEW"' "$W/manifest.tsv"
  echo "publish audit: $n REVIEW row(s)"
  [ "$n" = 0 ]
}
step publish_audit publish

echo "check.sh: $(grep -c '^ok' "$SUM") ok, $(grep -c '^RED' "$SUM") red"
[ $RED = 0 ] && { echo "check.sh: GREEN"; exit 0; }
echo "check.sh: RED"
exit 1
