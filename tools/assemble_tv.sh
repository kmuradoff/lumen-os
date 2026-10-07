#!/bin/bash
# Финальный образ system для Z9X на ноутбуке: LineageOS 21 TV GSI + MindTheGapps ATV + исправления XGIMI.
#   bash ~/z9x/tools/assemble_tv.sh   → ~/z9x/out/system_tv.img (EROFS, блок 4K)
set -euo pipefail
Z=~/z9x
IMG=${1:-$HOME/lineage/out/target/product/generic_arm64/system.img}
OUT=$Z/out
mkdir -p "$OUT"
log() { echo "$(date +%T) $*"; }

log "1/4 ext4 → tar"
python3 "$Z/tools/ext4_to_tar.py" "$IMG" "$OUT/system_base.tar" "$OUT/manifest.tsv"

log "2/4 сервисы Google"
rm -rf "$OUT/gapps" && mkdir -p "$OUT/gapps"
unzip -q "$Z"/gapps/MindTheGapps-*.zip 'system/*' -d "$OUT/gapps"
rm -rf "$OUT/gapps/system/addon.d"

log "3/4 состав: база + Google + исправления"
python3 "$Z/tools/compose_tv.py" "$OUT/system_base.tar" "$OUT/system_tv.tar" "$OUT/gapps/system" "$Z/overlay"

log "4/4 EROFS"
rm -f "$OUT/system_tv.img"
mkfs.erofs -b4096 -zlz4hc -E noinline_data -T1230768000 --tar=f "$OUT/system_tv.img" "$OUT/system_tv.tar" | grep -E "total inodes|Build completed" || true
fsck.erofs "$OUT/system_tv.img" >/dev/null && log "fsck ok"
ls -la "$OUT/system_tv.img" | awk '{printf "%.2f ГБ\n", $5/1073741824}'
sha256sum "$OUT/system_tv.img" | cut -c1-16
