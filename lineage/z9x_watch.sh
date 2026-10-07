#!/bin/bash
# Наблюдение с Мака за сборкой на ноутбуке. Выходит через 30 минут с отчётом
# или сразу, если сборка остановилась, упала, застряла, перегрелась или пропала связь.
L="ssh -i $HOME/.ssh/<builder-ssh-key> -o BatchMode=yes -o ConnectTimeout=15 $BUILDER"
start=$(date +%s); d0=""; att0=""; lastd=""; lastchg=$start; noconn=0; tsum=0; tn=0
while :; do
  r=$($L 'bash ~/lineage-docker/z9x_status.sh' 2>/dev/null)
  now=$(date +%s)
  if [ -z "$r" ]; then
    noconn=$((noconn + 1))
    [ $noconn -ge 10 ] && { echo "$(date +%T) СТОП: нет связи с ноутбуком 10 минут"; exit 1; }
    sleep 60; continue
  fi
  noconn=0
  IFS='|' read -r st prog att ex t g c m s fl <<< "$r"
  d=${prog%/*}; tot=${prog#*/}
  [[ $d =~ ^[0-9]+$ ]] || { d=${lastd:-0}; tot=0; }
  [ -z "$d0" ] && { d0=$d; att0=$att; }
  [ "$d" != "$lastd" ] && { lastd=$d; lastchg=$now; }
  tsum=$((tsum + t)); tn=$((tn + 1))
  [ "$st" != running ] && { echo "$(date +%T) СТОП: сборка остановилась ($st, $ex) на $prog"; exit 0; }
  [ "$att" -gt 1 ] && [ "$att" -gt "$att0" ] && { echo "$(date +%T) СБОЙ: попытка $att; $fl"; exit 0; }
  [ $((now - lastchg)) -ge 1200 ] && { echo "$(date +%T) СТОП: 20 минут без движения на $prog"; exit 0; }
  [ "$g" != on ] && { echo "$(date +%T) охрана температуры не работает"; exit 0; }
  [ "${t:-0}" -ge 97 ] && { echo "$(date +%T) ЖАРКО: $t°C, cpus $c"; exit 0; }
  el=$((now - start))
  if [ $el -ge 1800 ]; then
    rate=$(( (d - d0) * 60 / el ))
    eta="?"; [ $rate -gt 0 ] && [ "$tot" -gt 0 ] && eta=$(( (tot - d) / rate ))
    echo "$(date +%T) ОТЧЁТ: $prog, $rate шагов/мин, осталось ~$eta мин, средняя $((tsum / tn))°C, cpus $c, память $m ГБ, подкачка $s ГБ"
    exit 0
  fi
  sleep 60
done
