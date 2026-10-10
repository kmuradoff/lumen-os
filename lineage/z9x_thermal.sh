#!/bin/bash
# Охрана температуры ноутбука-сборщика на время сборки: ограничивает процессор контейнера z9x-build
# через docker update --cpus, держит сглаженную температуру процессора примерно 80–88°C.
# Сама выключается, когда контейнер остановится. Запуск на ноутбуке:
#   setsid -f bash ~/lineage-docker/z9x_thermal.sh 10 >> ~/z9x_thermal.log 2>&1 < /dev/null
CPUS=${1:-10}; MIN=4; MAX=16; HI=88; LO=80
temp() {
  for h in /sys/class/hwmon/hwmon*; do
    [ "$(cat $h/name)" = k10temp ] && { echo $(( $(cat $h/temp1_input) / 1000 )); return; }
  done
  echo 0
}
docker update --cpus $CPUS z9x-build >/dev/null && echo "$(date +%T) старт: cpus $CPUS, $(temp)°C"
ema=$(temp); cool=0; n=0
while [ "$(docker inspect -f '{{.State.Running}}' z9x-build 2>/dev/null)" = true ]; do
  sleep 10
  ema=$(( (ema * 5 + $(temp)) / 6 ))
  n=$((n + 1))
  if [ $ema -ge $HI ] && [ $CPUS -gt $MIN ] && [ $n -ge 3 ]; then
    CPUS=$((CPUS - 1)); docker update --cpus $CPUS z9x-build >/dev/null; n=0; cool=0
    echo "$(date +%T) ${ema}°C → cpus $CPUS"
  elif [ $ema -le $LO ]; then
    cool=$((cool + 1))
    if [ $cool -ge 12 ] && [ $CPUS -lt $MAX ]; then
      CPUS=$((CPUS + 1)); docker update --cpus $CPUS z9x-build >/dev/null; cool=0; n=0
      echo "$(date +%T) ${ema}°C → cpus $CPUS"
    fi
  else
    cool=0
  fi
  [ $((SECONDS % 600)) -lt 10 ] && echo "$(date +%T) ${ema}°C, cpus $CPUS"
done
echo "$(date +%T) контейнер остановлен — охрана выключена"
