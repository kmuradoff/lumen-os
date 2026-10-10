#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS OTA boot gate (image path /system/etc/z9x/z9x_ota.sh, 0755 root:root; run only by
# /system/etc/init/z9x_ota.rc and /system/etc/init/update_verifier.rc as root, u:r:su:s0).
#
#   z9x_ota.sh count      exec at post-fs-data: count boot attempts of an unverified OTA slot; more than
#                         3 -> roll back (boot HAL setActiveBootSlot(other) + reboot,z9x-ota-rollback)
#   z9x_ota.sh verifier   'exec_start update_verifier' at zygote-start (replaces update_verifier.rc)
#   z9x_ota.sh watch      background while pending: no boot_completed within 8 min -> roll back
#   z9x_ota.sh health     background at boot_completed: report; mark a healthy slot vold left unmarked
#   z9x_ota.sh info       background at boot: read-only slot facts for the updater app
#   z9x_ota.sh mark       background on request (updater "Keep this version"): health gate now
#
# Measured on a Z9X during the first OTA (1.0.0, 2026-10-08, the second projector, logs/friend_<serial>):
#  - /system/bin/bootctl is not in the image. 1.0.0's gate failed every bootctl call: it "rolled back"
#    a healthy slot, the rollback (set-active-boot-slot) failed as well, and the projector rebooted for
#    nothing and came back on the new slot, where 'rolledback' was then published.
#  - userdata is mounted with checkpoint=fs: vold marks the slot successful itself when boot completes
#    ("Checkpoint: Marked slot as booted successfully") and update_engine starts the snapshot merge at
#    once (MergeCompleted 47 s later). After boot_completed a rollback is impossible on this device.
# So the gate protects only the time BEFORE boot_completed (attempt counter + deadline) and after it
# only reports. The boot HAL is called with 'service call' (AIDL IBootControl, see hal()); bootctl is
# used only where a HAL call fails and /system/bin/bootctl exists.
#
# What counts as "an OTA slot": libsnapshot's boot indicator /metadata/ota/snapshot-boot exists and
# names the OTHER slot (it holds the slot that started the update; Lineage 21 libsnapshot
# GetCurrentSlot() == Target), and the current slot is not marked successful yet. On every other
# boot (normal boots, fastboot installs, the source slot after a rollback) this script changes
# nothing: 'verifier' runs the real /system/bin/update_verifier exactly like the stock rc, and the
# other modes only publish properties. HAL calls happen only on an OTA slot, each under a 2 s
# 'timeout': 'count' blocks init, and the vendor's PWM watchdog reboots the projector when early-boot
# is late. 'verifier' (blocks zygote-start) makes none: it reads what 'count' published.
#
# Properties published (read by org.z9x.updater and org.z9x.projector):
#   sys.z9x.slot                    current slot suffix (_a/_b)
#   sys.z9x.ota                     none | pending | marked | merging | unhealthy | rollback | rolledback
#   sys.z9x.ota.attempt             boot attempt of the pending slot (1..4)
#   sys.z9x.ota.why                 why 'unhealthy', or why a rollback is not possible (else empty)
#   sys.z9x.vbmeta_other.flags      AVB header flags of vbmeta_<other> (decimal; bit 2 = verification
#                                   disabled, the user's manual one-time step), or 'none' / 'bad'
#   sys.z9x.vbmeta_cur.flags        the same for the running slot
# State files (root only) in /metadata/z9x_ota: attempts ("<n> <slot>"), rolledback ("<date> <slot>
# <reason>", slot = the update slot we left), no_rollback ("<slot> <reason>": this update slot can no
# longer be rolled back; z9x_rescue.sh then stops waiting for the gate), log.txt.
# Kill switch: ro.z9x.ota.gate=0 -> behave exactly like stock (update_verifier runs on every boot).
# NEVER writes vbmeta, never writes any partition: only the boot HAL (slot metadata in misc) and its
# own files under /metadata/z9x_ota.

R=${Z9X_ROOT:-}        # empty on the projector; tools/ota/test/run.sh points it at a temp dir
D=$R/metadata/z9x_ota
MAX_ATTEMPTS=3
HEALTH_DELAY=90        # s after boot_completed before the health checks
DEADLINE=480           # s after post-fs-data: an OTA slot that never completes boot rolls back
HAL=android.hardware.boot.IBootControl/default
HAL_TIMEOUT=2          # s per boot HAL / bootctl call
BOOTCTL=$R/system/bin/bootctl
TAG=z9x_ota
NL='
'

say() { log -t "$TAG" "$*"; echo "$(date +%m%d-%H%M%S) $*" 2>/dev/null >> "$D/log.txt"; }

cur=$(getprop ro.boot.slot_suffix)
case "$cur" in
  _a) curn=0; other=_b; othern=1 ;;
  _b) curn=1; other=_a; othern=0 ;;
  *)  curn=; other=; othern= ;;
esac

ota_slot() {  # 0 = this boot runs an update target slot (marked or not)
  [ -n "$curn" ] || return 1
  src=$(cat "$R/metadata/ota/snapshot-boot" 2>/dev/null)
  [ -n "$src" ] && [ "$src" != "$cur" ]
}

gate_off() { [ "$(getprop ro.z9x.ota.gate)" = "0" ]; }

firstword() { fw=; [ -f "$1" ] && read -r fw _ < "$1"; echo "$fw"; }

setwhy() { setprop sys.z9x.ota.why "$(printf '%.90s' "$1")"; }

# ---- boot HAL: AIDL android.hardware.boot.IBootControl/default, transaction = method order in the
# AIDL: 1 getActiveBootSlot, 2 getCurrentSlot, 4 getSnapshotMergeStatus, 6 isSlotBootable(i32),
# 7 isSlotMarkedSuccessful(i32), 8 markBootSuccessful, 9 setActiveBootSlot(i32). A good reply is
# exactly one line (measured, for_101/bootcontrol_getters.txt):
#   Result: Parcel(<tab>00000000 00000001   '........')    status EX_NONE + one value
#   Result: Parcel(<tab>00000000    '....')                status EX_NONE, void
# Anything else (exception status, binder error, several lines, no such service, timeout) is a
# failure: logged, and the caller treats the answer as unknown.
hal() {  # code [i32 arg] -> HV = the value (one digit) or 'void'; 1 on a failed call
  hc="$*"
  if [ $# -gt 1 ]; then out=$(timeout $HAL_TIMEOUT service call $HAL "$1" i32 "$2" 2>&1)
  else out=$(timeout $HAL_TIMEOUT service call $HAL "$1" 2>&1); fi
  rc=$? HV=
  case "$out" in
    *"$NL"*) ;;
    "Result: Parcel("*"')")
      w=${out#"Result: Parcel("}; w=${w%")"}   # quoted: a bare '(' opens a pattern group in ksh
      set -f; set -- $w; set +f
      if [ "$rc" = 0 ] && [ "${1-}" = 00000000 ]; then
        case "$#:${2-}:${3-}" in
          "2:'....':") HV=void ;;
          3:0000000[0-9]:"'........'") HV=${2#0000000} ;;
        esac
      fi ;;
  esac
  [ -n "$HV" ] && return 0
  say "boot HAL call $hc failed (rc $rc): $(printf '%s\n' "$out" | tr '\t\n' '  ' | cut -c1-160)"
  return 1
}

slot_flag() {  # code bootctl-verb slot -> 0 yes, 1 no, 2 unknown
  if hal "$1" "$3"; then
    case "$HV" in 1) return 0 ;; 0) return 1 ;; esac
    say "boot HAL call $1 $3: '$HV' is not a boolean"; return 2
  fi
  [ -x "$BOOTCTL" ] || return 2
  bo=$(timeout $HAL_TIMEOUT "$BOOTCTL" "$2" "$3" 2>&1); brc=$?
  [ "$brc" = 0 ] && return 0
  # bootctl exits non-zero for "no" and for errors alike; only a silent non-zero exit means "no"
  [ "$brc" != 124 ] && [ -z "$bo" ] && return 1
  say "bootctl $2 $3 failed (rc $brc): $bo"; return 2
}
is_marked()   { slot_flag 7 is-slot-marked-successful "$1"; }
is_bootable() { slot_flag 6 is-slot-bootable "$1"; }

merge_state() {  # -> MS = none | snapshotted | merging | cancelled | other, or empty when unknown
  MS=
  if hal 4; then
    case "$HV" in 0) MS=none ;; 2) MS=snapshotted ;; 3) MS=merging ;; 4) MS=cancelled ;; *) MS=other ;; esac
  elif [ -x "$BOOTCTL" ]; then
    case "$(timeout $HAL_TIMEOUT "$BOOTCTL" get-snapshot-merge-status 2>/dev/null)" in
      none) MS=none ;; snapshotted) MS=snapshotted ;; merging) MS=merging ;; cancelled) MS=cancelled ;;
      unknown) MS=other ;;
    esac
  fi
}

set_active() {  # slot -> 0 when accepted and the active slot does not read back as something else
  if hal 9 "$1" && [ "$HV" = void ]; then :
  elif hal 1 && [ "$HV" = "$1" ]; then
    # a reply of a shape not measured yet, but the switch happened: finish the rollback. Going on would
    # leave the old slot active behind this one, which vold then marks and update_engine merges.
    say "setActiveBootSlot($1): reply not understood, but the active slot reads $1"; return 0
  elif [ -x "$BOOTCTL" ] && timeout $HAL_TIMEOUT "$BOOTCTL" set-active-boot-slot "$1" >/dev/null 2>&1; then :
  else return 1; fi
  hal 1 || return 0      # accepted; no read-back available
  [ "$HV" = "$1" ] && return 0
  say "active slot reads $HV after setActiveBootSlot($1)"
  return 1
}

mark_ok() {  # markBootSuccessful -> 0 when accepted and the slot does not read back as unmarked
  if hal 8 && [ "$HV" = void ]; then :
  elif [ -x "$BOOTCTL" ] && timeout $HAL_TIMEOUT "$BOOTCTL" mark-boot-successful >/dev/null 2>&1; then :
  else return 1; fi
  is_marked "$curn"; [ $? != 1 ]
}

# ---- gate actions
confirmed() {  # who: the update slot is marked successful, no rollback any more
  rm -f "$D/attempts"
  echo "$cur marked successful" > "$D/no_rollback"
  merge_state
  case "$MS" in snapshotted|merging) st=merging ;; *) st=marked ;; esac
  setprop sys.z9x.ota "$st"
  setprop sys.z9x.ota.why ""
  say "slot $cur is marked successful ($1); merge status ${MS:-unknown}: $st"
}

unhealthy() {  # why: report only, never reboot
  setprop sys.z9x.ota unhealthy
  setwhy "$1"
  say "UNHEALTHY (slot $cur, no reboot): $1"
}

rollback() {  # reason -> 0: the other slot is active and the reboot is requested; 1: not possible
  say "ROLLBACK ($1): slot $cur -> $other"
  nr=
  if [ -z "$other" ]; then nr="no slot suffix"
  else
    is_marked "$curn"
    case $? in
      0) nr="slot $cur is already marked successful" ;;
      2) nr="cannot read whether slot $cur is marked (boot HAL)" ;;
    esac
    if [ -z "$nr" ]; then
      merge_state
      case "$MS" in
        merging) nr="the snapshot merge has started" ;;
        # cancelled in fastbootd ('snapshot-update cancel', as 'lumen-install rescue' does before it writes
        # this slot's system again, over storage the old slot shares with it under Virtual A/B): no way back
        cancelled) nr="the update was cancelled (system written again)" ;;
      esac
    fi
    if [ -z "$nr" ]; then is_bootable "$othern"; [ $? = 1 ] && nr="slot $other is not bootable"; fi
    [ -n "$nr" ] || set_active "$othern" || nr="setActiveBootSlot($othern) failed"
  fi
  if [ -n "$nr" ]; then
    echo "$cur $nr" > "$D/no_rollback"
    setwhy "no rollback: $nr"
    say "rollback NOT possible: $nr (the boot rescue counter takes over)"
    return 1
  fi
  echo "$(date +%Y%m%d-%H%M%S) $cur $1" > "$D/rolledback"
  setprop sys.z9x.ota rollback
  setprop sys.powerctl "reboot,z9x-ota-rollback"
  return 0
}

avb_flags() {  # partition -> decimal flags, 'none' (no AVB0 magic) or 'bad'  (reads 128 bytes)
  dev=$R/dev/block/by-name/$1
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

case "$1" in
verifier)
  # Stock behaviour unless 'count' found an unverified OTA slot. No HAL call here (this blocks init
  # before zygote-start). With userdata checkpointing (this projector) update_verifier would not mark
  # the slot anyway (vold does at boot_completed); without it, deferring keeps the slot unmarked.
  if ! gate_off; then
    case "$(getprop sys.z9x.ota)" in
      pending|rollback) say "update_verifier: not run on update slot $cur ($(getprop sys.z9x.ota))"; exit 0 ;;
    esac
  fi
  shift
  exec "$R/system/bin/update_verifier" "$@"
  ;;
count)
  mkdir -p "$D" 2>/dev/null
  setprop sys.z9x.slot "$cur"
  # PackageManager parse cache, once per Lumen build (before system_server starts). The image keeps one
  # fixed mtime for every file (compact erofs inodes, tools/lumen_v1.sh) and the fingerprint stays the
  # base one, so after an update the cached parse results would still look current.
  bid=$(getprop ro.z9x.build_id)
  if [ -n "$bid" ] && [ -d "$R/data/system" ] && [ "$(cat "$R/data/system/z9x_pkgcache.build_id" 2>/dev/null)" != "$bid" ]; then
    rm -rf "$R/data/system/package_cache"
    echo "$bid" > "$R/data/system/z9x_pkgcache.build_id"
    say "package parse cache cleared for build $bid"
  fi
  if gate_off; then setprop sys.z9x.ota none; exit 0; fi
  if ota_slot; then
    is_marked "$curn"; m=$?
    if [ $m = 0 ]; then confirmed "seen at post-fs-data, e.g. a restart during the merge"; exit 0; fi
    n=; s=
    [ -f "$D/attempts" ] && read -r n s < "$D/attempts"
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    [ "$s" = "$cur" ] || n=0
    if [ "$n" = 0 ] && [ "$(firstword "$D/no_rollback")" = "$cur" ]; then
      # first attempt of this update, so the record is from an earlier one (1.0.0 on the old slot in
      # between does not know the file and leaves it): it must not take this update's rollback away
      say "no_rollback of an earlier update of $cur removed ($(cut -d' ' -f2- "$D/no_rollback"))"
      rm -f "$D/no_rollback"
    fi
    n=$((n + 1))
    echo "$n $cur" > "$D/attempts"
    setprop sys.z9x.ota.attempt "$n"
    if [ $m = 2 ]; then say "OTA slot $cur boot attempt $n/$MAX_ATTEMPTS (marked state unknown)"
    else say "OTA slot $cur boot attempt $n/$MAX_ATTEMPTS"; fi
    if [ -f "$D/rolledback" ]; then
      rd=; rs=; rr=
      read -r rd rs rr < "$D/rolledback"
      if [ "$rs" = "$cur" ]; then
        say "the rollback requested at $rd ($rr) did not take effect: still on $cur"
        echo "$cur rollback to $other did not take effect" > "$D/no_rollback"
      fi
      rm -f "$D/rolledback"
    fi
    rb=
    if [ "$(getprop ro.z9x.ota.test_fail)" = 1 ]; then rb="test_fail (rollback drill)"
    elif [ "$n" -gt "$MAX_ATTEMPTS" ]; then rb="boot attempts"; fi
    if [ -n "$rb" ]; then
      if [ "$(firstword "$D/no_rollback")" = "$cur" ]; then
        say "$rb: slot $cur cannot be rolled back ($(cut -d' ' -f2- "$D/no_rollback")); the boot rescue counter decides"
      else
        rollback "$rb" && exit 0
      fi
    fi
    setprop sys.z9x.ota pending
  else
    tgt=; how=
    if [ -f "$D/rolledback" ]; then read -r rd tgt how < "$D/rolledback"; how="$how ($rd)"
    elif [ -f "$D/attempts" ]; then read -r rn tgt < "$D/attempts"; how="bootloader fallback after $rn attempt(s)"
    fi
    if [ -n "$tgt" ] && [ -n "$cur" ] && [ "$tgt" != "$cur" ]; then
      # the update slot is not the one running: we (or the bootloader's retry counter) went back
      setprop sys.z9x.ota rolledback
      say "booted $cur after a failed update of $tgt: $how"
    else
      if [ -f "$D/rolledback" ] || [ -f "$D/attempts" ]; then
        say "slot $cur is no longer an update slot (state of ${tgt:-an unknown slot} cleared): nothing was rolled back"
      fi
      setprop sys.z9x.ota none
    fi
    rm -f "$D/rolledback" "$D/attempts" "$D/no_rollback"
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
  [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
  [ "$(getprop sys.z9x.ota)" = pending ] || exit 0
  rollback "no boot_completed within ${DEADLINE}s" && exit 0
  # stuck and no way back: restart, so the boot rescue counter (z9x_rescue.sh) sees a failed start
  say "slot $cur did not complete boot within ${DEADLINE}s: restarting (reboot,z9x-ota-stuck)"
  setprop sys.powerctl "reboot,z9x-ota-stuck"
  ;;
health|mark)
  [ "$(getprop sys.z9x.ota)" = pending ] || exit 0
  ota_slot || exit 0
  # vold marks the slot at boot_completed (userdata checkpoint commit): usually it is marked already
  is_marked "$curn"; m=$?
  [ $m = 0 ] && confirmed "by vold at boot_completed"
  [ "$1" = health ] && sleep $HEALTH_DELAY
  if [ $m != 0 ]; then
    is_marked "$curn"; m=$?
    [ $m = 0 ] && confirmed "by vold after boot_completed"
  fi
  hw=$(health_ok); hok=$?
  if [ $m = 0 ]; then
    # marked: the merge runs or ran, a rollback is impossible. Report only, never reboot.
    if [ $hok = 0 ]; then say "health checks passed on $cur"; else unhealthy "$hw"; fi
    exit 0
  fi
  if [ $hok = 0 ]; then
    if mark_ok; then
      rm -f "$D/attempts"
      echo "$cur marked successful" > "$D/no_rollback"
      setprop sys.z9x.ota marked
      setprop sys.z9x.ota.why ""
      say "slot $cur marked successful by the health gate; update_engine merges in the background"
    else
      unhealthy "healthy, but markBootSuccessful failed (boot HAL)"
    fi
    exit 0
  fi
  rollback "health: $hw" && exit 0
  unhealthy "$hw"
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
