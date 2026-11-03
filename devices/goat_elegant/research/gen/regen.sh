#!/bin/bash
# Regenerate the HB (goat_elegant, G0130) reference tables in ../ from the stock dump.
# Run on the Mac from anywhere. Reads the dump on xgimi-builder (read-only), never touches a device.
#   gen/regen.sh            all tables
# Needs on the server: ~/devices/g0130_mt9660/work/fs (unpacked stock partitions), a LineageOS clang
# (llvm-readelf, llvm-nm, llvm-objdump, llvm-cxxfilt) and prebuilts/sdk dexdump in ~/lineage.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(dirname "$HERE")"
GSI="$(cd "$OUT/../../.." && pwd)"
SRV=xgimi-builder
TMP=/tmp/hb_research_regen

ssh "$SRV" "rm -rf $TMP && mkdir -p $TMP"
scp -q "$HERE/ndk_codes.py" "$HERE/java_codes.py" "$SRV:$TMP/"
ssh "$SRV" bash -s <<REMOTE
set -euo pipefail
W=\$HOME/devices/g0130_mt9660/work/fs
B=\$(ls -d \$HOME/lineage/prebuilts/clang/host/linux-x86/clang-r*/bin | tail -1)
cd $TMP
sha256sum \$W/vendor/lib/xgimi.hardware.gmpf-V1-ndk.so \$W/system_ext/framework/com.xgimi.middle.jar
python3 ndk_codes.py "\$B" \$W/vendor/lib/xgimi.hardware.gmpf-V1-ndk.so ndk_codes.tsv
unzip -q -o \$W/system_ext/framework/com.xgimi.middle.jar classes.dex
\$HOME/lineage/prebuilts/sdk/tools/linux/bin/dexdump -f classes.dex > middle.dump 2>/dev/null
python3 java_codes.py middle.dump java_codes.tsv
REMOTE
L="$(mktemp -d)"
scp -q "$SRV:$TMP/ndk_codes.tsv" "$SRV:$TMP/java_codes.tsv" "$L/"
python3 "$HERE/merge_codes.py" "$L/ndk_codes.tsv" "$L/java_codes.tsv" "$OUT/aidl_codes.tsv"
python3 "$HERE/name_map.py" "$GSI" "$OUT/aidl_codes.tsv" "$HERE/replacements.tsv" "$OUT/name_map.tsv"
python3 "$HERE/banned.py" "$GSI" "$OUT/aidl_codes.tsv" "$OUT/name_map.tsv" "$OUT/banned.tsv"
rm -rf "$L"
