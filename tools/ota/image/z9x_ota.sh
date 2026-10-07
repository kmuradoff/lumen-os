#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS OTA boot gate (image path /system/etc/z9x/z9x_ota.sh, 0755 root:root; run only by
# /system/etc/init/z9x_ota.rc and /system/etc/init/update_verifier.rc as root, u:r:su:s0).
#
#   z9x_ota.sh verifier   'exec_start update_verifier' at zygote-start (replaces update_verifier.rc)
#   z9x_ota.sh count      exec at post-fs-data: count boot attempts of an unverified OTA slot
#   z9x_ota.sh watch      background: deadline for an OTA slot that never reaches boot_completed
#   z9x_ota.sh health     background at boot_completed: health gate -> mark the slot or roll back
#   z9x_ota.sh info       background at boot: read-only slot facts for the updater app
#   z9x_ota.sh mark       background on request (updater "Keep this version"): health gate now
#
# What counts as "an OTA slot": libsnapshot's boot indicator /metadata/ota/snapshot-boot exists and
# names the OTHER slot (it holds the slot that started the update; Lineage 21 libsnapshot
# GetCurrentSlot() == Target), and the current slot is not marked successful yet. On every other
# boot (normal boots, fastboot installs, the source slot after a rollback) this script changes
# nothing: 'verifier' runs the real /system/bin/update_verifier exactly like the stock rc, and the
# other modes only publish properties. bootctl is called only for an OTA slot (plus the read-only
# get-current-slot in 'info'), always under 'timeout'.
#
# Properties published (read by org.z9x.updater and org.z9x.projector):
#   sys.z9x.slot                    current slot suffix (_a/_b)
#   sys.z9x.ota                     none | pending | marked | merging | rollback | rolledback
#   sys.z9x.ota.attempt             boot attempt of the pending slot (1..3)
#   sys.z9x.vbmeta_other.flags      AVB header flags of vbmeta_<other> (decimal; bit 2 = verification
#                                   disabled, the user's manual one-time step), or 'none' / 'bad'
#   sys.z9x.vbmeta_cur.flags        the same for the running slot
# State files (root only): /metadata/z9x_ota/{attempts,rolledback,log.txt}
# Kill switch: ro.z9x.ota.gate=0 -> behave exactly like stock (update_verifier marks every boot).
# NEVER writes vbmeta, never writes any partition: only bootctl (slot metadata in misc) and its own
# files under /metadata/z9x_ota.

D=/metadata/z9x_ota
MAX_ATTEMPTS=3
HEALTH_DELAY=90        # s after boot_completed before the health checks
DEADLINE=480           # s after post-fs-data: an OTA slot that never completes boot rolls back
TAG=z9x_ota

say() { log -t "$TAG" "$*"; echo "$(date +%m%d-%H%M%S) $*" >> "$D/log.txt" 2>/dev/null; }

cur=$(getprop ro.boot.slot_suffix)
case "$cur" in
  _a) curn=0; other=_b; othern=1 ;;
  _b) curn=1; other=_a; othern=0 ;;
  *)  curn=; other=; othern= ;;
esac

ota_slot() {  # 0 = this boot runs an update target slot
  [ -n "$curn" ] || return 1
  src=$(cat /metadata/ota/snapshot-boot 2>/dev/null)
  [ -n "$src" ] && [ "$src" != "$cur" ]
}

marked() { timeout 10 /system/bin/bootctl is-slot-marked-successful "$curn" >/dev/null 2>&1; }

gate_off() { [ "$(getprop ro.z9x.ota.gate)" = "0" ]; }

rollback() {  # reason
  say "ROLLBACK ($1): slot $cur -> $other"
  mkdir -p "$D"
  echo "$(date +%Y%m%d-%H%M%S) $cur $1" > "$D/rolledback"
  if ! timeout 10 /system/bin/bootctl set-active-boot-slot "$othern"; then
    say "bootctl set-active-boot-slot $othern FAILED; the bootloader retry counter is the last net"
  fi
  setprop sys.z9x.ota rollback
  setprop sys.powerctl "reboot,z9x-ota-rollback"
}

avb_flags() {  # partition -> decimal flags, 'none' (no AVB0 magic) or 'bad'  (reads 128 bytes)
  dev=/dev/block/by-name/$1
  [ -e "$dev" ] || { echo none; return; }
  magic=$(timeout 2 dd if="$dev" bs=4 count=1 2>/dev/null)
  [ "$magic" = AVB0 ] || { echo none; return; }
  # flags: big-endian u32 at offset 120 of the AVB vbmeta header (bit 2 = verification disabled)
  set -- $(timeout 2 dd if="$dev" bs=1 skip=120 count=4 2>/dev/null | od -A n -t u1)
  [ $# -eq 4 ] || { echo bad; return; }
  echo $(( ($1 << 24) + ($2 << 16) + ($3 << 8) + $4 ))
}

health_ok() {  # prints the failed check, returns 1 on failure
  sc=$(getprop sys.system_server.start_count)
  [ -z "$sc" ] || [ "$sc" -le 1 ] || { echo "system_server restarted ($sc)"; return 1; }
  [ "$(getprop init.svc.zygote)" = running ] || { echo "zygote not running"; return 1; }
  [ "$(getprop init.svc.surfaceflinger)" = running ] || { echo "surfaceflinger not running"; return 1; }
  [ "$(getprop init.svc.gmpf_main)" = running ] || { echo "gmpf_main (lamp/fans) not running"; return 1; }
  # soft checks: logged only
  [ -n "$(pidof org.z9x.projector)" ] || say "soft: org.z9x.projector not running"
  [ "$(getprop sys.z9x.blobs)" = ok ] || say "soft: sys.z9x.blobs=$(getprop sys.z9x.blobs)"
  return 0
}

do_mark() {
  if timeout 15 /system/bin/bootctl mark-boot-successful; then
    rm -f "$D/attempts"
    setprop sys.z9x.ota marked
    say "slot $cur marked successful; update_engine merges in the background"
    return 0
  fi
  say "bootctl mark-boot-successful FAILED"
  return 1
}

case "$1" in
verifier)
  # Stock behaviour unless this boot is an unverified OTA slot under our gate.
  if ! gate_off && ota_slot && ! marked; then
    say "update_verifier: mark deferred to the health gate (slot $cur)"
    exit 0
  fi
  shift
  exec /system/bin/update_verifier "$@"
  ;;
count)
  mkdir -p "$D" 2>/dev/null
  setprop sys.z9x.slot "$cur"
  if gate_off; then setprop sys.z9x.ota none; exit 0; fi
  if ota_slot; then
    if marked; then setprop sys.z9x.ota merging; exit 0; fi
    n=$(cat "$D/attempts" 2>/dev/null); n=$(( ${n:-0} + 1 ))
    echo "$n" > "$D/attempts"
    setprop sys.z9x.ota.attempt "$n"
    say "OTA slot $cur boot attempt $n/$MAX_ATTEMPTS"
    if [ "$n" -gt "$MAX_ATTEMPTS" ]; then rollback "boot attempts"; exit 0; fi
    setprop sys.z9x.ota pending
  else
    if [ -f "$D/rolledback" ] || [ -f "$D/attempts" ]; then
      # we rolled back (or the bootloader's retry counter did): tell the updater for this boot
      setprop sys.z9x.ota rolledback
      say "booted $cur after a failed update: $(cat "$D/rolledback" 2>/dev/null || echo 'bootloader fallback')"
      rm -f "$D/rolledback" "$D/attempts"
    else
      setprop sys.z9x.ota none
    fi
  fi
  exit 0
  ;;
watch)
  t=0
  while [ $t -lt $DEADLINE ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
    case "$(getprop sys.z9x.ota)" in pending) ;; *) exit 0 ;; esac
    sleep 10; t=$((t + 10))
  done
  [ "$(getprop sys.boot_completed)" = 1 ] || rollback "no boot_completed within ${DEADLINE}s"
  ;;
health|mark)
  [ "$(getprop sys.z9x.ota)" = pending ] || exit 0
  ota_slot || exit 0
  if marked; then setprop sys.z9x.ota merging; exit 0; fi
  [ "$1" = health ] && sleep $HEALTH_DELAY
  if [ "$(getprop ro.z9x.ota.test_fail)" = 1 ]; then rollback "test_fail (rollback drill)"; exit 0; fi
  why=$(health_ok) || { rollback "health: $why"; exit 0; }
  do_mark || rollback "mark failed"
  ;;
info)
  setprop sys.z9x.slot "$cur"
  if [ -n "$other" ]; then
    setprop sys.z9x.vbmeta_other.flags "$(avb_flags "vbmeta$other")"
    setprop sys.z9x.vbmeta_cur.flags "$(avb_flags "vbmeta$cur")"
  fi
  ;;
*)
  echo "usage: z9x_ota.sh verifier|count|watch|health|info|mark" >&2
  exit 2
  ;;
esac
exit 0
