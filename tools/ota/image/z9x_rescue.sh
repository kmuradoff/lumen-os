#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS boot rescue (image path /system/etc/z9x/z9x_rescue.sh, 0755 root:root; run only by
# /system/etc/init/z9x_rescue.rc as root, u:r:su:s0).
#
#   z9x_rescue.sh count   exec at 'on init': one more start of this slot; when the 3 starts before it
#                         all failed to reach sys.boot_completed -> reboot,fastboot
#   z9x_rescue.sh ok      background at sys.boot_completed=1: the start completed, counter back to 0
#
# Owner decision (2026-10-08): a projector whose system fails to complete boot 3 times in a row
# restarts into fastbootd (the same 'reboot,fastboot' as 'adb reboot fastboot'), where the installer's
# 'rescue' command writes the system again without wiping data. Before, a Z9X in a boot loop (lamp off,
# no adb, no fastboot, e.g. the vendor PWM watchdog loop) could only be saved with the USB-stick stock
# restore. XGIMI's fastbootd leaves by itself after about 2 minutes and the projector starts normally
# again; a system that still fails comes back to fastbootd after 3 more failed starts.
#
# 'on init' is the first trigger after first-stage init mounted /metadata (fstab.mt9952:
# first_stage_mount) with logd started, so a loop that dies anywhere later (vendor init, data mount,
# post-fs-data, zygote, system_server, the PWM watchdog at ~20 s) is counted. A failure before init's
# second stage (kernel, first-stage mount, dm-verity) is not: nothing of /system runs there.
#
# Counter /metadata/z9x_rescue/count = "<n> <slot>": starts of <slot> since its last completed boot,
# this one included. Another slot starts at 0 (OTA rollback, bootloader slot fallback, an install into
# the other slot), so every slot gets its own 3 tries. Reset before rebooting to fastbootd.
# Not fighting the OTA gate (z9x_ota.sh): on an update slot (libsnapshot indicator names the other
# slot) the gate rolls back by itself at its attempt 4, at post-fs-data of this same start. Rescue waits
# for that while the gate can still do it: at most OTA_GRACE more starts (an update that dies before
# post-fs-data never reaches the gate), and not at all once z9x_ota.sh wrote no_rollback for this slot
# (slot marked successful, boot HAL failure, an update cancelled in fastbootd, or a rollback that did
# not take effect).
# Kill switch: ro.z9x.rescue=0 (image) or the file /metadata/z9x_rescue/off (adb root on a running
# system): starts are still counted and logged, nothing reboots.
# Log: /metadata/z9x_rescue/log.txt and logcat tag z9x_rescue (log.txt moves to log.old at 64 KiB).
# Writes only its own files; init writes the fastbootd request into misc (bootloader message) exactly
# as for 'adb reboot fastboot'. No HAL call, no /data access: a few short process spawns per boot.

R=${Z9X_ROOT:-}        # empty on the projector; tools/ota/test/run.sh points it at a temp dir
D=$R/metadata/z9x_rescue
RESCUE_AFTER=3         # failed starts in a row
OTA_GRACE=3            # more starts an update slot gets for z9x_ota.sh's own rollback
LOG_MAX=65536
TAG=z9x_rescue

say() {
  log -t "$TAG" "$*"
  up=; read -r up _ 2>/dev/null < "$R/proc/uptime"
  echo "$(date +%m%d-%H%M%S) up ${up%%.*}s $*" 2>/dev/null >> "$D/log.txt"
}

cur=$(getprop ro.boot.slot_suffix)

off() { [ "$(getprop ro.z9x.rescue)" = 0 ] || [ -e "$D/off" ]; }

ota_waits() {  # 0 = z9x_ota.sh can still roll this update slot back by itself
  [ -n "$cur" ] && [ "$(getprop ro.z9x.ota.gate)" != 0 ] || return 1
  src=; read -r src _ 2>/dev/null < "$R/metadata/ota/snapshot-boot"
  [ -n "$src" ] && [ "$src" != "$cur" ] || return 1
  nr=; read -r nr _ 2>/dev/null < "$R/metadata/z9x_ota/no_rollback"
  [ "$nr" != "$cur" ]
}

case "$1" in
count)
  mkdir -p "$D" 2>/dev/null
  if [ -f "$D/log.txt" ] && [ $(wc -c < "$D/log.txt") -gt $LOG_MAX ]; then mv -f "$D/log.txt" "$D/log.old" 2>/dev/null; fi
  n=; s=
  read -r n s 2>/dev/null < "$D/count"
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  if [ "$s" != "$cur" ]; then
    [ "$n" -gt 0 ] && say "slot ${s:-?} -> ${cur:-?}: count starts over (was $n)"
    n=0
  fi
  # n = earlier starts of this slot that did not complete boot
  if [ "$n" -ge $RESCUE_AFTER ]; then
    if ota_waits && [ "$n" -lt $((RESCUE_AFTER + OTA_GRACE)) ]; then
      say "start $((n + 1)) of $cur after $n failed: update slot, z9x_ota.sh rolls back first"
    elif off; then
      say "start $((n + 1)) of $cur after $n failed: rescue is off (ro.z9x.rescue=0 or $D/off), no reboot"
    elif echo "0 $cur" 2>/dev/null > "$D/count"; then
      say "RESCUE: $n starts of $cur in a row did not complete boot: reboot,fastboot (lumen-install rescue)"
      setprop sys.powerctl reboot,fastboot
      exit 0
    else
      say "RESCUE needed after $n failed starts of $cur, but $D/count cannot be reset: no reboot"
    fi
  fi
  n=$((n + 1))
  if echo "$n $cur" 2>/dev/null > "$D/count"; then say "start $n of $cur"
  else say "cannot write $D/count (start $n of $cur)"; fi
  ;;
ok)
  mkdir -p "$D" 2>/dev/null
  n=; read -r n _ 2>/dev/null < "$D/count"
  if echo "0 $cur" 2>/dev/null > "$D/count"; then say "boot completed on $cur (start ${n:-?})"
  else say "boot completed on $cur, but $D/count cannot be reset"; fi
  ;;
*)
  echo "usage: z9x_rescue.sh count|ok" >&2
  exit 2
  ;;
esac
exit 0
