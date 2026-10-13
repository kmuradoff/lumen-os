#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# dexpreopt_play6.sh: build-time AOT ("speed") of one of the Play 6 image's /system APKs, inside the
# lineage-builder container on the build laptop (run by tools/play6/compose.sh), against the SAME boot image
# that the Play 6 base build put into its system image (docs/play6/inventory/D_play6_performance.md 6.4,
# docs/play6/SETTINGS_PLAN.md section 4 item 5 and step 14).
# A fork of tools/lumen/dexpreopt_lumen.sh (the Z9X build tool stays byte-identical; tools/play6/forks.tsv,
# check F), changed for the 32-bit arm base:
#   - out dir /out (~/play6-out) and source tree /src (~/lineage), both read-only, PRODUCT generic; boot image
#     /out/soong/dexpreopt_arm/dex_bootjars/.../arm/boot.art plus the mainline extension
#     dex_mainlinejars/.../arm/boot-framework-adservices.art
#   - --instruction-set=arm; the variant, the features, --android-root and the dex2oat binary are read from
#     the base build's own webview dexpreopt.sh (not hard-coded), as the boot class path is
#   - output oat/arm/<Name>.odex|vdex; classes*.dex must be stored and 4-aligned (--copy-dex-files=false)
#   - lumen_checks.py (oatkey, oatcheck) and apkinfo.py are used read only, next to this script (compose's
#     /work/in) or in tools/lumen (the tree); ionice only when the container has it
#
#   bash dexpreopt_play6.sh APK DEVICE_PATH OUTDIR REF_ODEX
#     APK          the exact APK that goes into the image (already signed with the Play 6 key: the odex is
#                  built after signing; re-signing would keep classes*.dex byte-identical anyway)
#     DEVICE_PATH  its final location, e.g. /system/priv-app/Z9xHome/Z9xHome.apk
#     OUTDIR       gets oat/arm/<Name>.odex and oat/arm/<Name>.vdex
#     REF_ODEX     an odex the base build made: /system/product/app/webview/oat/arm/webview.odex of the base;
#                  the GC mode (concurrent copying on the Play 6, no -Xgc:CMC) is taken from it, and the new
#                  odex must carry the same bootclasspath-checksums, else the run fails
#
# Flags = the base build's out/target/product/generic/obj/APPS/webview_intermediates/dexpreopt.sh with
# --compiler-filter=speed, and --avoid-storing-invocation like soong: without it the odex header key
# 'dex2oat-cmdline' stores this command line (lumen_checks.py oatcheck refuses an odex with that key). Class
# loader context PCL[] (no <uses-library>). Runs under nice (and ionice when present): the laptop is shared.
# Safe failure on the device: a stale or mismatching odex is rejected by ART and the app runs JIT.
# Env: OUT (default /out), SRC (/src), PRODUCT (generic), FILTER (speed), DEX2OAT (default: the dex2oat that
# the webview dexpreopt.sh names, else $OUT/host/linux-x86/bin/dex2oatd64).
set -euo pipefail
die() { echo "dexpreopt_play6: $*" >&2; exit 1; }
[ $# -eq 4 ] || die "usage: dexpreopt_play6.sh APK DEVICE_PATH OUTDIR REF_ODEX"
APK=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); LOC=$2; OUT_DIR=$3; REF=$(cd "$(dirname "$4")" && pwd)/$(basename "$4")
H=$(cd "$(dirname "$0")" && pwd)
LT=$H; [ -f "$LT/lumen_checks.py" ] || LT=$H/../lumen
[ -f "$LT/lumen_checks.py" ] && [ -f "$LT/apkinfo.py" ] || die "no lumen_checks.py / apkinfo.py next to $0 or in tools/lumen"
O=${OUT:-/out}
T=${SRC:-/src}
PRODUCT=${PRODUCT:-generic}
FILTER=${FILTER:-speed}
D=$O/soong/dexpreopt_arm
WV=$O/target/product/$PRODUCT/obj/APPS/webview_intermediates/dexpreopt.sh
NAME=$(basename "$LOC" .apk)
case $LOC in /system/app/*/*.apk|/system/priv-app/*/*.apk) ;; *) die "unexpected device path $LOC" ;; esac
[ "$(basename "$(dirname "$LOC")")" = "$NAME" ] || die "$LOC: directory and APK name differ"
# --boot-image names locations; the files live in the ISA subdirectory (arm/)
for f in "$APK" "$REF" "$WV" "$D/dex_bootjars/android/system/framework/arm/boot.art" \
         "$D/dex_mainlinejars/android/system/framework/arm/boot-framework-adservices.art"; do
  [ -e "$f" ] || die "missing $f"
done
# the boot class path, the ISA details and the dex2oat binary exactly as the base build used them
wv() { tr ' ' '\n' < "$WV" | sed -n "s/^$1\\(.*\\)\$/\\1/p" | head -n 1; }
BCP_HOST=$(wv -Xbootclasspath:)
BCP_DEV=$(wv -Xbootclasspath-locations:)
[ -n "$BCP_HOST" ] && [ -n "$BCP_DEV" ] || die "cannot read the boot class path from $WV"
grep -q -- '--compilation-reason=prebuilt' "$WV" || die "$WV is not the expected dexpreopt script"
[ "$(wv --instruction-set=)" = arm ] || die "$WV does not compile for --instruction-set=arm (not the Play 6 base?)"
VARIANT=$(wv --instruction-set-variant=); FEATURES=$(wv --instruction-set-features=); AROOT=$(wv --android-root=)
[ -n "$VARIANT" ] && [ -n "$FEATURES" ] && [ -n "$AROOT" ] \
  || die "cannot read --instruction-set-variant / --instruction-set-features / --android-root from $WV"
if [ -z "${DEX2OAT:-}" ]; then
  DEX2OAT=$(tr ' ' '\n' < "$WV" | grep -E '/dex2oat[a-z0-9]*$' | head -n 1 || true)
  case $DEX2OAT in /*) ;; ?*) DEX2OAT=$T/$DEX2OAT ;; esac
  [ -n "$DEX2OAT" ] && [ -x "$DEX2OAT" ] || DEX2OAT=$O/host/linux-x86/bin/dex2oatd64
fi
[ -x "$DEX2OAT" ] || die "no dex2oat binary $DEX2OAT"
# --copy-dex-files=false maps classes*.dex from the APK: each must be stored (method 0) and 4-aligned
python3 -B "$LT/apkinfo.py" dex "$APK" | awk -v a="$APK" '
  { n++; if ($4 != 0 || $5 % 4 != 0) { print "dexpreopt_play6: " a ": " $1 " is not stored and 4-aligned (method " $4 ", offset " $5 ")" > "/dev/stderr"; bad = 1 } }
  END { if (n == 0) { print "dexpreopt_play6: " a ": no classes*.dex" > "/dev/stderr"; bad = 1 } exit bad }' \
  || die "$APK cannot be compiled with --copy-dex-files=false"
# GC mode: the same information as the build's uffd_gc_flag.txt is the 'concurrent-copying' key of every
# odex the build made (false = uffd / CMC; the Play 6 base is built with OVERRIDE_ENABLE_UFFD_GC=false)
CC=$(python3 -B "$LT/lumen_checks.py" oatkey "$REF" concurrent-copying)
case $CC in
  false) GC=(--runtime-arg -Xgc:CMC) ;;
  true) GC=() ;;
  *) die "reference odex $REF has no concurrent-copying key" ;;
esac
NICE=(nice -n 19); command -v ionice >/dev/null 2>&1 && NICE+=(ionice -c3)
mkdir -p "$OUT_DIR/oat/arm"
OUT_DIR=$(cd "$OUT_DIR" && pwd)
rm -f "$OUT_DIR/oat/arm/$NAME.odex" "$OUT_DIR/oat/arm/$NAME.vdex"
cd "$T"
ANDROID_LOG_TAGS='*:e' "${NICE[@]}" "$DEX2OAT" \
  --runtime-arg -Xms64m --runtime-arg -Xmx512m \
  --runtime-arg -Xbootclasspath:"$BCP_HOST" \
  --runtime-arg -Xbootclasspath-locations:"$BCP_DEV" \
  --class-loader-context='PCL[]' --stored-class-loader-context='PCL[]' \
  --boot-image="$D/dex_bootjars/android/system/framework/boot.art:$D/dex_mainlinejars/android/system/framework/boot-framework-adservices.art" \
  --dex-file="$APK" --dex-location="$LOC" \
  --oat-file="$OUT_DIR/oat/arm/$NAME.odex" \
  --android-root="$AROOT" \
  --instruction-set=arm --instruction-set-variant="$VARIANT" --instruction-set-features="$FEATURES" \
  --no-generate-debug-info --generate-build-id --abort-on-hard-verifier-error --force-determinism \
  --no-inline-from=core-oj.jar ${GC[@]+"${GC[@]}"} \
  --copy-dex-files=false --compiler-filter="$FILTER" --generate-mini-debug-info \
  --avoid-storing-invocation \
  --compilation-reason=prebuilt -j2
[ -s "$OUT_DIR/oat/arm/$NAME.odex" ] && [ -s "$OUT_DIR/oat/arm/$NAME.vdex" ] || die "dex2oat produced no odex/vdex for $NAME"
python3 -B "$LT/lumen_checks.py" oatcheck "$OUT_DIR/oat/arm/$NAME.odex" "$REF" "$FILTER"
ls -la "$OUT_DIR/oat/arm/$NAME.odex" "$OUT_DIR/oat/arm/$NAME.vdex"
