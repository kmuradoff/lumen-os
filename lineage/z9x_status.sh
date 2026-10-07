#!/bin/bash
# Одна строка состояния сборки на ноутбуке (для наблюдения с Мака):
#   статус|сделано/всего|попыток|EXIT|°C|охрана|cpus|память ГБ|подкачка ГБ|последний FAILED STEP
st=$(docker inspect -f '{{.State.Status}}' z9x-build 2>/dev/null || echo none)
prog=$(docker logs --tail 50 z9x-build 2>&1 | tr '\r' '\n' | grep -oE '[0-9]+/[0-9]+\]' | tail -1 | tr -d ']')
ev=$(docker logs z9x-build 2>&1 | grep -E '^=== ATTEMPT|^EXIT=|^=== FAILED STEP')
att=$(printf '%s\n' "$ev" | grep -c '^=== ATTEMPT')
ex=$(printf '%s\n' "$ev" | grep -oE '^EXIT=[0-9]+' | tail -1)
fl=$(printf '%s\n' "$ev" | grep '^=== FAILED STEP' | tail -1 | cut -c1-200)
t=0
for h in /sys/class/hwmon/hwmon*; do
  [ "$(cat $h/name)" = k10temp ] && t=$(( $(cat $h/temp1_input) / 1000 ))
done
g=$(pgrep -f 'z9x_thermal[.]sh' >/dev/null && echo on || echo off)
c=$(( $(docker inspect -f '{{.HostConfig.NanoCpus}}' z9x-build 2>/dev/null || echo 0) / 1000000000 ))
m=$(free -m | awk '/Mem/{printf "%.1f", $3/1024}')
s=$(free -m | awk '/Swap/{printf "%.1f", $3/1024}')
echo "$st|${prog:-?}|$att|${ex:-none}|$t|$g|$c|$m|$s|$fl"
