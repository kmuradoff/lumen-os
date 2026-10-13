#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS DIAG/test images only: when the boot does not complete, reboot into fastbootd.
# Triggers: system_server started 8 times without sys.boot_completed=1, or 15 min of uptime without it.
# Waits 15 s before the reboot so the USB boot logger (z9x_earlylog) writes one more round.
while :; do
  [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
  n=$(getprop sys.system_server.start_count); n=${n:-0}
  up=$(cut -d. -f1 /proc/uptime)
  if [ "$n" -ge 8 ] || [ "$up" -ge 900 ]; then
    log -t lumen_escape "boot not completed (system_server starts=$n, uptime=${up}s): rebooting into fastbootd"
    sleep 15
    setprop sys.powerctl reboot,fastboot
    exit 0
  fi
  sleep 5
done
