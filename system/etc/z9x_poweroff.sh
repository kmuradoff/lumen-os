#!/system/bin/sh
# Z9X v6.2: called by init 'on shutdown' (root). Power-off only.
# init runs this as a blocking 'exec' with no time limit, and its shutdown_done (DoReboot, which
# starts init's shutdown watchdog) waits behind it. So only the watchdog stop runs synchronously;
# the diagnostics run in a detached background job and the script exits at once. toybox 'timeout'
# alone would not be enough: it waits for its child, and a write stuck in uninterruptible I/O
# (D state) never returns. A stuck background job is killed or abandoned by DoReboot, it cannot
# hold up the power-off.
P=$(getprop sys.powerctl)
case "$P" in
  shutdown*) ;;
  *) exit 0 ;;                       # reboot / anything else: leave the watchdog alone
esac
L=/data/misc/z9x_diag/poweroff.txt
W=$(getprop persist.z9x.poweroff.wdtstop)
R="wdt not stopped (no /dev/watchdog0)"
if [ "$W" = "0" ]; then
  log -t Z9xPowerOff "wdtstop disabled by persist.z9x.poweroff.wdtstop=0"
  R="wdtstop disabled"
elif [ -c /dev/watchdog0 ]; then
  # open + 'V' + close = magic close -> watchdog_stop -> mtktv_wdt_stop (zeroes the PM WDT counters)
  if echo V > /dev/watchdog0; then
    log -t Z9xPowerOff "watchdog0 stopped for power-off ($P)"; R="wdt stopped"
  else
    log -t Z9xPowerOff "watchdog0 stop FAILED"; R="wdt stop failed"
  fi
fi
# Evidence for the power-off tests (UNVERIFIED side effects of reading /sys/mtk_pm/*): in the
# background, at most 3 s in total, each read at most 1 s. Best effort: DoReboot may end it first.
( timeout 3 /system/bin/sh -c '
  {
    echo "$(date +%Y%m%d-%H%M%S) up=$(cut -d" " -f1 /proc/uptime) powerctl=$1 wdtstop=$2 -> $3"
    for f in /sys/mtk_pm/wakeup_source/*; do echo "  ws ${f##*/}=$(timeout 1 cat "$f" 2>&1)"; done
    echo "  wdt_ms_timeout=$(timeout 1 cat /sys/bus/platform/devices/1c400600.wdt0/wdt_extend/wdt_ms_timeout 2>&1)"
    echo "  rtc: $(timeout 1 grep -E "alrm_time|alrm_date|alarm_IRQ" /proc/driver/rtc 2>&1 | tr -s " \t\n" " ")"
  } >> "$4" 2>/dev/null
' z9x_poweroff "$P" "$W" "$R" "$L" ) </dev/null >/dev/null 2>&1 &
exit 0
