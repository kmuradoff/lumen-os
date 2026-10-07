#!/bin/zsh
# Попытка 3, этап 1: записать только систему Google (данные НЕ трогаем) и снять журналы,
# пока она работает на старых данных XGIMI и adb по USB доступен.
# vbmeta этот скрипт не трогает — это шаг пользователя, делается до запуска.
set -u
cd "$(dirname "$0")/.."
S=<SERIAL>
log() { print -P "%D{%H:%M:%S} $*"; }
mkdir -p logs

log "→ fastboot"
adb -s $S reboot fastboot
fastboot getvar all > build/getvar_attempt3.txt 2>&1          # ждёт появления устройства
grep -E "current-slot|snapshot-update-status" build/getvar_attempt3.txt
slot=$(awk -F: '/current-slot/{gsub(/ /,"",$NF); print $NF}' build/getvar_attempt3.txt)
st=$(awk -F: '/snapshot-update-status/{gsub(/ /,"",$NF); print $NF}' build/getvar_attempt3.txt)
if [ "$st" != none ]; then log "идёт обновление XGIMI ($st) — стоп"; fastboot reboot; exit 1; fi
for p in $(grep -o -E "partition-size:[a-z_]+_${slot}-cow" build/getvar_attempt3.txt | cut -d: -f2); do
  fastboot delete-logical-partition $p
done
fastboot flash system build/system_erofs_v2.img || { log "system не записан — стоп"; fastboot reboot; exit 1; }
fastboot reboot
log "система записана, жду adb"

for i in {1..90}; do adb -s $S get-state 2>/dev/null | grep -q device && break; sleep 2; done
adb -s $S get-state 2>/dev/null | grep -q device || { log "adb не появился за 3 минуты — стоп"; exit 1; }
adb -s $S root >/dev/null; sleep 4; adb -s $S wait-for-device
adb -s $S shell 'getprop ro.system.build.fingerprint; ps -A -o NAME | grep -E "^gmpf_main|gmpf@1.0"; cat /sys/class/thermal/thermal_zone0/temp'

log "собираю журналы минуту (несколько перезапусков системы)"
sleep 60
adb -s $S logcat -b all -d > logs/logcat_stockdata.txt
adb -s $S shell dmesg > logs/dmesg_stockdata.txt
adb -s $S shell getprop > logs/props_gsi.txt
adb -s $S shell 'ls -la /data/tombstones /data/anr 2>&1' > logs/crashes_list.txt
log "журналы в gsi/logs: $(wc -l < logs/logcat_stockdata.txt) строк logcat"
