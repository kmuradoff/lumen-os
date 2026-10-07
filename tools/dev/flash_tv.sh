#!/bin/zsh
# Прошивка Android TV: LineageOS 21 TV GSI + сервисы Google + исправления XGIMI (build/system_tv_v1.img).
# Пишет только system текущего слота, данные не трогает. vbmeta этот скрипт НЕ трогает:
# это шаг пользователя, он делается до запуска. Короткая сессия: fastbootd XGIMI сам выходит через пару минут.
set -u
cd "$(dirname "$0")/.."
S=<SERIAL>
IMG=${1:-build/system_tv_v1.img}
log() { print -P "%D{%H:%M:%S} $*"; }
[ -f "$IMG" ] || { log "нет $IMG"; exit 1; }

log "→ fastboot"
adb -s $S reboot fastboot
fastboot getvar all > build/getvar_tv.txt 2>&1          # ждёт появления устройства
grep -E "current-slot|snapshot-update-status" build/getvar_tv.txt
slot=$(awk -F: '/current-slot/{gsub(/ /,"",$NF); print $NF}' build/getvar_tv.txt)
st=$(awk -F: '/snapshot-update-status/{gsub(/ /,"",$NF); print $NF}' build/getvar_tv.txt)
if [ "$st" != none ]; then log "идёт обновление XGIMI ($st) — стоп"; fastboot reboot; exit 1; fi
for p in $(grep -o -E "partition-size:[a-z_]+-cow" build/getvar_tv.txt | cut -d: -f2); do
  fastboot delete-logical-partition $p
done
fastboot flash system "$IMG" || { log "system не записан — стоп"; fastboot reboot; exit 1; }
fastboot reboot
log "система записана, жду adb (до 3 минут)"

for i in {1..90}; do adb -s $S get-state 2>/dev/null | grep -q device && break; sleep 2; done
adb -s $S get-state 2>/dev/null | grep -q device || { log "adb не появился за 3 минуты"; exit 1; }
adb -s $S root >/dev/null; sleep 4; adb -s $S wait-for-device
adb -s $S shell 'getprop ro.lineage.version; getprop sys.boot_completed; ps -A -o NAME | grep -E "^gmpf_main|gmpf@1.0"; cat /sys/class/thermal/thermal_zone0/temp'
