#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# dexpreopt_lumen.sh: build-time AOT ("speed") of one of our /system APKs, on the build laptop,
# against the SAME boot image that ships in the base tar (plan C10, speed spec 4).
#
#   bash dexpreopt_lumen.sh APK DEVICE_PATH OUTDIR REF_ODEX
#     APK          the exact APK that goes into the image (before the release re-signing: re-signing
#                  keeps classes*.dex byte-identical, so the vdex dex checksums stay valid)
#     DEVICE_PATH  its final location, e.g. /system/priv-app/Z9xHome/Z9xHome.apk
#     OUTDIR       gets oat/arm64/<Name>.odex and oat/arm64/<Name>.vdex
#     REF_ODEX     an odex of the base image built by the real build (webview.odex from the base tar):
#                  the GC mode (uffd / CMC vs. concurrent copying) is taken from it, and the new odex
#                  must carry the same bootclasspath-checksums, else the run fails
#
# Flags = the build's own out/target/product/generic_arm64/obj/APPS/webview_intermediates/dexpreopt.sh
# with --compiler-filter=speed. Class loader context PCL[] (lumen_checks.py apks refuses APKs with
# <uses-library> for dexpreopt). Runs under nice/ionice (the laptop is shared: light use).
# Safe failure on the device: a stale or mismatching odex is rejected by ART and the app runs JIT.
# Env: LINEAGE (default ~/lineage), PRODUCT (generic_arm64), FILTER (speed), DEX2OAT (dex2oatd64).
set -euo pipefail
die() { echo "dexpreopt_lumen: $*" >&2; exit 1; }
[ $# -eq 4 ] || die "usage: dexpreopt_lumen.sh APK DEVICE_PATH OUTDIR REF_ODEX"
APK=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); LOC=$2; OUT=$3; REF=$(cd "$(dirname "$4")" && pwd)/$(basename "$4")
H=$(cd "$(dirname "$0")" && pwd)
T=${LINEAGE:-$HOME/lineage}
PRODUCT=${PRODUCT:-generic_arm64}
FILTER=${FILTER:-speed}
D=$T/out/soong/dexpreopt_arm64
WV=$T/out/target/product/$PRODUCT/obj/APPS/webview_intermediates/dexpreopt.sh
DEX2OAT=${DEX2OAT:-$T/out/host/linux-x86/bin/dex2oatd64}
NAME=$(basename "$LOC" .apk)
case $LOC in /system/app/*/*.apk|/system/priv-app/*/*.apk) ;; *) die "unexpected device path $LOC" ;; esac
[ "$(basename "$(dirname "$LOC")")" = "$NAME" ] || die "$LOC: directory and APK name differ"
# --boot-image names locations; the files live in the ISA subdirectory (arm64/)
for f in "$APK" "$REF" "$WV" "$DEX2OAT" "$D/dex_bootjars/android/system/framework/arm64/boot.art" \
         "$D/dex_mainlinejars/android/system/framework/arm64/boot-framework-adservices.art"; do
  [ -e "$f" ] || die "missing $f"
done
# the boot class path exactly as the build used it (host jars + device locations)
BCP_HOST=$(tr ' ' '\n' < "$WV" | sed -n 's/^-Xbootclasspath:\(.*\)$/\1/p' | head -n 1)
BCP_DEV=$(tr ' ' '\n' < "$WV" | sed -n 's/^-Xbootclasspath-locations:\(.*\)$/\1/p' | head -n 1)
[ -n "$BCP_HOST" ] && [ -n "$BCP_DEV" ] || die "cannot read the boot class path from $WV"
grep -q -- '--compilation-reason=prebuilt' "$WV" || die "$WV is not the expected dexpreopt script"
# GC mode: the build passes out/soong/dexpreopt/uffd_gc_flag.txt (root-only on the laptop); the same
# information is the 'concurrent-copying' key of every odex the build made (false = uffd / CMC)
CC=$(python3 "$H/lumen_checks.py" oatkey "$REF" concurrent-copying)
case $CC in
  false) GC=(--runtime-arg -Xgc:CMC) ;;
  true) GC=() ;;
  *) die "reference odex $REF has no concurrent-copying key" ;;
esac
mkdir -p "$OUT/oat/arm64"
OUT=$(cd "$OUT" && pwd)
rm -f "$OUT/oat/arm64/$NAME.odex" "$OUT/oat/arm64/$NAME.vdex"
cd "$T"
ANDROID_LOG_TAGS='*:e' nice -n 19 ionice -c3 "$DEX2OAT" \
  --runtime-arg -Xms64m --runtime-arg -Xmx512m \
  --runtime-arg -Xbootclasspath:"$BCP_HOST" \
  --runtime-arg -Xbootclasspath-locations:"$BCP_DEV" \
  --class-loader-context='PCL[]' --stored-class-loader-context='PCL[]' \
  --boot-image="$D/dex_bootjars/android/system/framework/boot.art:$D/dex_mainlinejars/android/system/framework/boot-framework-adservices.art" \
  --dex-file="$APK" --dex-location="$LOC" \
  --oat-file="$OUT/oat/arm64/$NAME.odex" \
  --android-root=out/empty \
  --instruction-set=arm64 --instruction-set-variant=generic --instruction-set-features=default \
  --no-generate-debug-info --generate-build-id --abort-on-hard-verifier-error --force-determinism \
  --no-inline-from=core-oj.jar "${GC[@]}" \
  --copy-dex-files=false --compiler-filter="$FILTER" --generate-mini-debug-info \
  --compilation-reason=prebuilt -j2
[ -s "$OUT/oat/arm64/$NAME.odex" ] && [ -s "$OUT/oat/arm64/$NAME.vdex" ] || die "dex2oat produced no odex/vdex for $NAME"
python3 "$H/lumen_checks.py" oatcheck "$OUT/oat/arm64/$NAME.odex" "$REF" "$FILTER"
ls -la "$OUT/oat/arm64/$NAME.odex" "$OUT/oat/arm64/$NAME.vdex"
