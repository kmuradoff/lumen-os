#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS: when the boot does not complete, reboot into fastbootd. Fork of overlay/debug/lumen_escape.sh for the
# projectors with the neutral OTA gate (tools/devices/ota/image/lumen_ota.sh); same image path
# /system/etc/xgimi/lumen_escape.sh, started by the unchanged lumen_escape.rc at post-fs-data. Its own file name
# differs from the origin's because tools/image/compose.sh stages every input flat into one folder.
# Triggers: system_server started 8 times without sys.boot_completed=1, or 15 min of uptime without it.
# Waits 15 s before the reboot so the USB boot logger (z9x_earlylog) writes one more round.
# Quiet while the OTA gate can still act on this boot: sys.z9x.ota is pending or rollback and
# /metadata/lumen_ota/no_rollback does not name this slot. The gate acts first (a rollback after 4 system_server
# starts or 480 s, or reboot,z9x-ota-stuck); once it wrote no_rollback for this slot, or the slot is marked, the
# triggers above apply again. The 15 min uptime limit holds even while the gate can act: should its watch not run,
# a crash loop still ends in fastbootd.
R=${LUMEN_OTA_ROOT:-}  # empty on the projector; tools/devices/test/lumen_ota_test.sh points it at a temp dir
cur=$(getprop ro.boot.slot_suffix)
waited=
gate_acts() {  # 0 = the OTA gate can still roll this boot back by itself
  case "$(getprop sys.z9x.ota)" in pending|rollback) ;; *) return 1 ;; esac
  nr=; read -r nr _ 2>/dev/null < "$R/metadata/lumen_ota/no_rollback"
  [ "$nr" != "$cur" ]
}
while :; do
  [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
  up=; read -r up _ 2>/dev/null < "$R/proc/uptime"; up=${up%%.*}
  case "$up" in ''|*[!0-9]*) up=0 ;; esac
  if [ "$up" -lt 900 ] && gate_acts; then
    [ -n "$waited" ] || log -t lumen_escape "update slot $cur: the OTA gate acts first (sys.z9x.ota=$(getprop sys.z9x.ota))"
    waited=1
    sleep 5
    continue
  fi
  n=$(getprop sys.system_server.start_count)
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  if [ "$n" -ge 8 ] || [ "$up" -ge 900 ]; then
    log -t lumen_escape "boot not completed (system_server starts=$n, uptime=${up}s): rebooting into fastbootd"
    sleep 15
    setprop sys.powerctl reboot,fastboot
    exit 0
  fi
  sleep 5
done
