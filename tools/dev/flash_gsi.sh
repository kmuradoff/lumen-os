#!/bin/zsh
# Вторая попытка: записать систему и подготовить чистые данные. Короткие заходы в fastbootd,
# потому что у XGIMI он сам выходит через пару минут. vbmeta этот скрипт НЕ трогает.
#
#   1) fastbootd: удалить устаревшие *-cow текущего слота (остатки OTA) → system → reboot
#   2) система Google грузится со старыми данными (цикл zygote), но adb по USB работает:
#      проверяем службу вентиляторов gmpf_main
#   3) fastbootd: отформатировать metadata (f2fs) → стереть userdata → reboot
#      (userdata Android отформатирует сам: в fstab стоит formattable)
set -u
cd "$(dirname "$0")/.."
[ $# -ge 1 ] || { echo "укажите серийный номер проектора (см. adb devices): zsh tools/flash_gsi.sh SERIAL" >&2; exit 2; }
S=$1
log() { print -P "%D{%H:%M:%S} $*"; }

log "1/3 → fastboot"
adb -s $S reboot fastboot
fastboot getvar all > build/getvar_attempt2.txt 2>&1          # ждёт появления устройства
grep -E "current-slot|snapshot-update-status" build/getvar_attempt2.txt
slot=$(awk -F: '/current-slot/{gsub(/ /,"",$NF); print $NF}' build/getvar_attempt2.txt)
st=$(awk -F: '/snapshot-update-status/{gsub(/ /,"",$NF); print $NF}' build/getvar_attempt2.txt)
if [ "$st" != none ]; then log "идёт обновление XGIMI ($st) — стоп"; fastboot reboot; exit 1; fi
for p in $(grep -o -E "partition-size:[a-z_]+_${slot}-cow" build/getvar_attempt2.txt | cut -d: -f2); do
  fastboot delete-logical-partition $p
done
fastboot flash system build/system_erofs.img || { log "system не записан — стоп"; fastboot reboot; exit 1; }
fastboot reboot
log "система записана, жду adb"

log "2/3 → проверка"
for i in {1..90}; do adb -s $S get-state 2>/dev/null | grep -q device && break; sleep 2; done
if ! adb -s $S get-state 2>/dev/null | grep -q device; then
  log "adb не появился за 3 минуты — стоп, данные не трогаю"; exit 1
fi
adb -s $S shell 'getprop ro.system.build.fingerprint; ps -A -o NAME | grep -E "^gmpf_main|gmpf@1.0"; cat /sys/class/thermal/thermal_zone0/temp'

log "3/3 → fastboot: metadata + userdata"
adb -s $S reboot fastboot
fastboot format:f2fs metadata && fastboot erase userdata && fastboot reboot
log "готово, первая загрузка на чистых данных"
