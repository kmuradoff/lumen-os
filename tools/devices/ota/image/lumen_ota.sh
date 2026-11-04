#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS OTA boot gate for the projectors with the neutral update client (image path
# /system/etc/lumen/lumen_ota.sh, 0755 root:root; run only by /system/etc/init/lumen_ota.rc and the replaced
# /system/etc/init/update_verifier.rc (lumen_update_verifier.rc) as root, u:r:su:s0). Fork of the Z9X gate
# tools/ota/image/z9x_ota.sh (docs/architecture/OTA_CLIENT.md section 3); the Z9X keeps its own file.
#
#   lumen_ota.sh count     exec at post-fs-data: file reads and writes only, zero boot HAL calls, no conf. Counts
#                          the boot attempts of an update slot and publishes only 'pending' there, never a rollback
#   lumen_ota.sh decide    background (started by sys.z9x.ota=pending, and by sys.z9x.ota.request=probe): reads
#                          the boot HAL under a 2 s timeout per call with strict parsing and publishes
#                          sys.z9x.ota.hal; on an update slot (once per boot) it confirms a marked slot or rolls
#                          back after more than max_attempts attempts. An unknown answer never causes a reboot
#   lumen_ota.sh verifier  'exec_start update_verifier' at zygote-start: skipped while pending or rollback
#   lumen_ota.sh watch     background while pending: system_server started start_count_max times, or no
#                          boot_completed within deadline s -> roll back
#   lumen_ota.sh health    background at boot_completed + health_delay s: report a marked slot; mark a healthy
#                          unmarked slot (read back); roll back an unhealthy unmarked one
#   lumen_ota.sh mark      background on request (updater "Keep this version"): the health gate at once
#   lumen_ota.sh info      background at boot: read-only slot facts for the updater app; deletes the package
#                          parse cache that count set aside
#
# Why it differs from z9x_ota.sh: on the HB the boot HAL is the HIDL boot@1.2 one, reached through the pinned
# 32-bit AOSP bootctl (hal=bootctl), and 'count' blocks init at post-fs-data while the vendor's PWM watchdog
# reboots the projector when early boot is late. So 'count' never calls the HAL (its paths and property names
# are fixed here: a broken conf cannot stop it) and every HAL read moves to 'decide' in the background. hal=aidl
# keeps the Z9X 'service call' route (AIDL IBootControl, bootctl fallback) unchanged; tools/devices/test/
# lumen_ota_parity.sh runs the Z9X gate scenarios against it.
#
# What counts as "an update slot": libsnapshot's boot indicator /metadata/ota/snapshot-boot exists and names the
# OTHER slot (the slot that started the update), as on the Z9X. On every other boot this script changes nothing:
# 'verifier' runs the real /system/bin/update_verifier exactly like the stock rc, 'decide' is not started (a
# normal boot makes zero boot HAL calls) and the other modes only publish properties.
#
# Image data: /system/etc/lumen/ota_gate.conf (devices/<codename>/image/ota_gate.conf), read strictly by every
# mode except count and verifier: key=value lines, '#' comments, each key once, nothing else:
#   hal=bootctl|aidl  health=<1 to 8 init service names that must run>  lamp=<one of them: the lamp/fans service>
#   ns=ro.lumen  max_attempts=1..9  deadline=60..840  health_delay=0..600  start_count_max=2..20
# (lamp is optional; ns must be ro.lumen, the namespace count and verifier use without the conf.)
#
# Properties read: ro.boot.slot_suffix, ro.lumen.ota.gate (kill switch), ro.lumen.build_id,
# ro.lumen.ota.test_fail (rollback drill), sys.boot_completed, sys.system_server.start_count, init.svc.<health>.
# Properties published (read by org.lumen.updater and org.lumen.projector; the Z9X names, so BootReceiver and
# Outcome work unchanged):
#   sys.z9x.slot                    current slot suffix (_a/_b)
#   sys.z9x.ota                     none | pending | marked | merging | unhealthy | rollback | rolledback
#   sys.z9x.ota.attempt             boot attempt of the pending slot
#   sys.z9x.ota.why                 why 'unhealthy', or why a rollback is not possible (else empty)
#   sys.z9x.ota.hal                 probing, then ok or bad:<why> ('decide'; empty = not read this boot)
#   sys.z9x.ota.marked              1 / 0: the running slot is marked successful (empty = unknown)
#   sys.z9x.ota.merge               none | snapshotted | merging | cancelled | other (empty = unknown)
#   sys.z9x.ota.active              active slot suffix as the boot HAL reads it (empty = unknown)
#   sys.z9x.vbmeta_other.flags      AVB header flags of vbmeta_<other> (decimal), or 'none' / 'bad'
#   sys.z9x.vbmeta_cur.flags        the same for the running slot
# State files (root only) in /metadata/lumen_ota: attempts ("<n> <slot>"), rolledback ("<date> <slot> <reason>",
# slot = the update slot we left), no_rollback ("<slot> <reason>": this update slot can no longer be rolled back;
# lumen_escape.sh then stops waiting for the gate), log.txt (moves to log.old at 64 KiB). Each state file is
# written whole: a temp file in the same folder, then one rename (put), so a power cut leaves the old or the new one.
# Kill switch: ro.lumen.ota.gate=0 -> behave exactly like stock (update_verifier runs on every boot).
# NEVER writes any partition: only slot metadata through the boot HAL and its own files under
# /metadata/lumen_ota (plus the package parse cache below, in /data/system).

R=${LUMEN_OTA_ROOT:-}  # empty on the projector; tools/devices/test/lumen_ota_test.sh points it at a temp dir
D=$R/metadata/lumen_ota
CONF=$R/system/etc/lumen/ota_gate.conf
HAL=android.hardware.boot.IBootControl/default
HAL_TIMEOUT=2          # s per boot HAL / bootctl call
BOOTCTL=$R/system/bin/bootctl
LOG_MAX=65536
TAG=lumen_ota
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
  src=; read -r src _ 2>/dev/null < "$R/metadata/ota/snapshot-boot"
  [ -n "$src" ] && [ "$src" != "$cur" ]
}

gate_off() { [ "$(getprop ro.lumen.ota.gate)" = "0" ]; }

firstword() { fw=; read -r fw _ 2>/dev/null < "$1"; echo "$fw"; }

setwhy() { setprop sys.z9x.ota.why "$(printf '%.90s' "$1")"; }

suffix() { case "$1" in 0) echo _a ;; 1) echo _b ;; esac; }

put() {  # file text -> 0 when written: a temp file in the same folder (one per process), then one rename over the old
  put_t=$1.$$.tmp   # a name of its own: the callers use t, n and the like
  printf '%s\n' "$2" 2>/dev/null > "$put_t" && mv -f "$put_t" "$1" 2>/dev/null && return 0
  rm -f "$put_t" 2>/dev/null; return 1
}

# ---- ota_gate.conf, strict: any doubt is 'invalid' (CONF_WHY says why), never a guess
num_in() {  # value min max -> 0 when a plain decimal (no sign, no leading zero, at most 4 digits) in range
  case "$1" in ''|*[!0-9]*|0?*|?????*) return 1 ;; esac
  [ "$1" -ge "$2" ] && [ "$1" -le "$3" ]
}
name_ok() { case "$1" in ''|*[!a-z0-9_.-]*) return 1 ;; esac; [ ${#1} -le 40 ]; }
load_conf() {  # -> HK HEALTH LAMP MAX_ATTEMPTS DEADLINE HEALTH_DELAY START_MAX; 1 = missing or invalid
  HK=; HEALTH=; LAMP=; NSV=; MAX_ATTEMPTS=; DEADLINE=; HEALTH_DELAY=; START_MAX=; CONF_WHY=; seen=' '; ln=0
  [ -f "$CONF" ] || { CONF_WHY="missing"; return 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    ln=$((ln + 1))
    case "$line" in ''|'#'*) continue ;; *=*) ;; *) CONF_WHY="line $ln: no key=value"; return 1 ;; esac
    k=${line%%=*}; v=${line#*=}
    case "$seen" in *" $k "*) CONF_WHY="line $ln: $k given twice"; return 1 ;; esac
    seen="$seen$k "
    case "$k" in
      hal) case "$v" in bootctl|aidl) HK=$v ;; esac ;;
      health)
        set -f; set -- $v; set +f
        [ $# -ge 1 ] && [ $# -le 8 ] && [ "$*" = "$v" ] || { CONF_WHY="line $ln: health: 1 to 8 names, one space apart"; return 1; }
        for w in "$@"; do name_ok "$w" || { CONF_WHY="line $ln: health: bad name"; return 1; }; done
        HEALTH=$v ;;
      lamp) name_ok "$v" && LAMP=$v ;;
      ns) [ "$v" = ro.lumen ] && NSV=$v ;;
      max_attempts) num_in "$v" 1 9 && MAX_ATTEMPTS=$v ;;
      deadline) num_in "$v" 60 840 && DEADLINE=$v ;;  # below the 900 s limit of lumen_ota_escape.sh
      health_delay) num_in "$v" 0 600 && HEALTH_DELAY=$v ;;
      start_count_max) num_in "$v" 2 20 && START_MAX=$v ;;
      *) CONF_WHY="line $ln: unknown key"; return 1 ;;
    esac
    case "$k" in
      hal) v2=$HK ;; health) v2=$HEALTH ;; lamp) v2=$LAMP ;; ns) v2=$NSV ;; max_attempts) v2=$MAX_ATTEMPTS ;;
      deadline) v2=$DEADLINE ;; health_delay) v2=$HEALTH_DELAY ;; *) v2=$START_MAX ;;
    esac
    [ -n "$v2" ] || { CONF_WHY="line $ln: $k: bad value"; return 1; }
  done < "$CONF"
  for k in hal health ns max_attempts deadline health_delay start_count_max; do
    case "$seen" in *" $k "*) ;; *) CONF_WHY="$k missing"; return 1 ;; esac
  done
  if [ -n "$LAMP" ]; then
    case " $HEALTH " in *" $LAMP "*) ;; *) CONF_WHY="lamp is not in health"; return 1 ;; esac
  fi
  return 0
}

# ---- boot HAL, hal=aidl: the Z9X route, unchanged (AIDL android.hardware.boot.IBootControl/default through
# 'service call', transaction = method order in the AIDL: 1 getActiveBootSlot, 2 getCurrentSlot,
# 4 getSnapshotMergeStatus, 6 isSlotBootable(i32), 7 isSlotMarkedSuccessful(i32), 8 markBootSuccessful,
# 9 setActiveBootSlot(i32)). A good reply is exactly one line (measured on a Z9X):
#   Result: Parcel(<tab>00000000 00000001   '........')    status EX_NONE + one value
#   Result: Parcel(<tab>00000000    '....')                status EX_NONE, void
# Anything else is a failure: logged, and the caller treats the answer as unknown. bootctl is used only where
# a HAL call fails and /system/bin/bootctl exists.
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
  # the strict bootctl reply shapes below (the Z9X gate reads a silent exit 70 as "no": the real bootctl prints
  # "0" there and nothing only on an error)
  bc_flag "$2" "$3"
}

set_active_aidl() {  # slot -> 0 when accepted and the active slot does not read back as something else
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

mark_ok_aidl() {  # markBootSuccessful -> 0 marked (read back), 1 not accepted, 2 accepted but not read back as marked
  if hal 8 && [ "$HV" = void ]; then :
  elif [ -x "$BOOTCTL" ] && timeout $HAL_TIMEOUT "$BOOTCTL" mark-boot-successful >/dev/null 2>&1; then :
  else return 1; fi
  mark_readback
}

# ---- boot HAL, hal=bootctl: the pinned AOSP bootctl, every call under 'timeout'. Strict replies (stdout and
# stderr together):
#   get-current-slot, get-active-boot-slot   exit 0 and exactly '0' or '1'
#   is-slot-bootable, is-slot-marked-successful N   exit 0 and exactly '1' = yes; exit 70 (EX_SOFTWARE) and exactly
#                                            '0' = no; an error is exit 70 with a message or with nothing: unknown
#   get-snapshot-merge-status                exit 0 and exactly one of none unknown snapshotted merging cancelled
#   mark-boot-successful, set-active-boot-slot N   exit 0 and nothing = accepted
# Anything else (another exit status, a timeout = 124, any text, an extra line) is "unknown": logged, never acted on.
bc() {  # verb [slot] -> BO = what bootctl printed, BRC = its exit status
  if [ -x "$BOOTCTL" ]; then BO=$(timeout $HAL_TIMEOUT "$BOOTCTL" "$@" 2>&1); BRC=$?
  else BO="no $BOOTCTL"; BRC=127; fi
}
bc_bad() { say "bootctl $* failed (rc $BRC): $(printf '%s\n' "$BO" | tr '\t\n' '  ' | cut -c1-160)"; }
bc_slot() {  # verb -> HV = 0 or 1; 1 = unknown
  bc "$1"; HV=
  [ "$BRC" = 0 ] && case "$BO" in 0|1) HV=$BO ;; esac
  [ -n "$HV" ] && return 0
  bc_bad "$1"; return 1
}
bc_flag() {  # verb slot -> 0 yes, 1 no, 2 unknown (bootctl.cpp handle_return: printf("%d") of the bool)
  bc "$1" "$2"
  case "$BRC:$BO" in
    0:1) return 0 ;;
    70:0) return 1 ;;
  esac
  bc_bad "$1" "$2"; return 2
}
bc_void() {  # verb [slot] -> 0 accepted
  bc "$@"
  [ "$BRC" = 0 ] && [ -z "$BO" ] && return 0
  bc_bad "$@"; return 1
}

set_active_bc() {  # slot -> 0 when accepted and the active slot does not read back as something else
  if bc_void set-active-boot-slot "$1"; then
    if bc_slot get-active-boot-slot; then
      [ "$HV" = "$1" ] && return 0
      say "active slot reads $HV after set-active-boot-slot $1"; return 1
    fi
    say "set-active-boot-slot $1 accepted, the active slot cannot be read back"; return 0
  fi
  if bc_slot get-active-boot-slot && [ "$HV" = "$1" ]; then
    # the switch happened although the reply is not the expected one: finish the rollback (see set_active_aidl)
    say "set-active-boot-slot $1: reply not understood, but the active slot reads $1"; return 0
  fi
  return 1
}

mark_ok_bc() {  # mark-boot-successful -> 0 marked (read back), 1 not accepted, 2 accepted but not read back as marked
  bc_void mark-boot-successful || return 1
  mark_readback
}

mark_readback() {  # after an accepted mark: 0 only when the slot reads back as marked (an exit 0 alone proves nothing)
  is_marked "$curn"
  case $? in
    0) return 0 ;;
    1) say "mark accepted, but slot $cur reads back as not marked" ;;
    *) say "mark accepted, but whether slot $cur is marked cannot be read back" ;;
  esac
  return 2
}

# ---- one interface for both routes (HK from ota_gate.conf)
is_marked() {
  if [ "$HK" = bootctl ]; then bc_flag is-slot-marked-successful "$1"; else slot_flag 7 is-slot-marked-successful "$1"; fi
}
is_bootable() {
  if [ "$HK" = bootctl ]; then bc_flag is-slot-bootable "$1"; else slot_flag 6 is-slot-bootable "$1"; fi
}
merge_state() {  # -> MS = none | snapshotted | merging | cancelled | other, or empty when unknown
  MS=
  if [ "$HK" = bootctl ]; then
    bc get-snapshot-merge-status
    if [ "$BRC" = 0 ]; then
      case "$BO" in none|snapshotted|merging|cancelled) MS=$BO ;; unknown) MS=other ;; esac
    fi
    [ -n "$MS" ] || bc_bad get-snapshot-merge-status
  elif hal 4; then
    case "$HV" in 0) MS=none ;; 2) MS=snapshotted ;; 3) MS=merging ;; 4) MS=cancelled ;; *) MS=other ;; esac
  elif [ -x "$BOOTCTL" ]; then
    case "$(timeout $HAL_TIMEOUT "$BOOTCTL" get-snapshot-merge-status 2>/dev/null)" in
      none) MS=none ;; snapshotted) MS=snapshotted ;; merging) MS=merging ;; cancelled) MS=cancelled ;;
      unknown) MS=other ;;
    esac
  fi
}
slot_read() {  # active|current -> HV = slot number; 1 = unknown
  if [ "$HK" = bootctl ]; then
    if [ "$1" = active ]; then bc_slot get-active-boot-slot; else bc_slot get-current-slot; fi
    return
  fi
  if [ "$1" = active ]; then hal 1; else hal 2; fi || return 1
  case "$HV" in 0|1) return 0 ;; esac
  say "boot HAL: $1 slot '$HV' is not a slot"; HV=; return 1
}
set_active() { if [ "$HK" = bootctl ]; then set_active_bc "$1"; else set_active_aidl "$1"; fi; }
mark_ok() { if [ "$HK" = bootctl ]; then mark_ok_bc; else mark_ok_aidl; fi; }

yn() { case "$1" in 0) echo 1 ;; 1) echo 0 ;; *) echo '?' ;; esac; }

probe() {  # read every slot fact once -> M_CUR (0 yes, 1 no, 2 unknown) + the sys.z9x.ota.hal/.marked/.merge/.active props
  hw=; cs=; as=; b0=2; b1=2; m0=2; m1=2; MS=
  if [ -z "$curn" ]; then hw="no slot suffix"
  elif [ "$HK" = bootctl ] && [ ! -x "$BOOTCTL" ]; then hw="no bootctl"
  else
    slot_read current && cs=$HV
    slot_read active && as=$HV
    is_bootable 0; b0=$?; is_bootable 1; b1=$?
    is_marked 0; m0=$?; is_marked 1; m1=$?
    merge_state
    if [ -z "$cs" ]; then hw="current slot unknown"
    elif [ "$cs" != "$curn" ]; then hw="current slot $cs, slot suffix $cur"
    elif [ -z "$as" ]; then hw="active slot unknown"
    elif [ $b0 = 2 ] || [ $b1 = 2 ]; then hw="bootable unknown"
    elif [ $m0 = 2 ] || [ $m1 = 2 ]; then hw="marked unknown"
    elif [ -z "$MS" ]; then hw="merge status unknown"
    fi
  fi
  if [ "$curn" = 1 ]; then M_CUR=$m1; else M_CUR=$m0; fi
  case $M_CUR in 0) mp=1 ;; 1) mp=0 ;; *) mp= ;; esac
  setprop sys.z9x.ota.marked "$mp"
  setprop sys.z9x.ota.merge "$MS"
  setprop sys.z9x.ota.active "$(suffix "$as")"
  if [ -n "$hw" ]; then setprop sys.z9x.ota.hal "$(printf 'bad:%.80s' "$hw")"; else setprop sys.z9x.ota.hal ok; fi
  say "boot HAL ($HK): current ${cs:-?} active ${as:-?} bootable a=$(yn $b0) b=$(yn $b1) marked a=$(yn $m0) b=$(yn $m1) merge ${MS:-?}: ${hw:-ok}"
}

# ---- gate actions
confirmed() {  # who: the update slot is marked successful, no rollback any more
  rm -f "$D/attempts"
  put "$D/no_rollback" "$cur marked successful"
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
        # cancelled in fastbootd ('fastboot snapshot-update cancel', typed by hand; 'lumen-install rescue' stops
        # on an update and never cancels): a later write of this slot's system lands on storage the old slot
        # shares with it under Virtual A/B, so no way back
        cancelled) nr="the update was cancelled (system written again)" ;;
        ''|other) [ "$HK" = bootctl ] && nr="the snapshot merge status is unknown (boot HAL)" ;;
      esac
    fi
    if [ -z "$nr" ]; then
      is_bootable "$othern"
      case $? in
        1) nr="slot $other is not bootable" ;;
        2) [ "$HK" = bootctl ] && nr="cannot read whether slot $other is bootable (boot HAL)" ;;
      esac
    fi
    if [ -z "$nr" ] && ! set_active "$othern"; then
      if [ "$HK" = bootctl ]; then nr="set-active-boot-slot $othern failed"; else nr="setActiveBootSlot($othern) failed"; fi
    fi
  fi
  if [ -n "$nr" ]; then
    put "$D/no_rollback" "$cur $nr"
    setwhy "no rollback: $nr"
    say "rollback NOT possible: $nr (lumen_escape and the bootloader's tries take over)"
    return 1
  fi
  put "$D/rolledback" "$(date +%Y%m%d-%H%M%S) $cur $1"
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

health_ok() {  # prints the failed check, returns 1 on failure (services from ota_gate.conf health=)
  sc=$(getprop sys.system_server.start_count)
  case "$sc" in
    '') ;;
    *[!0-9]*|?????*) echo "system_server restarted ($sc)"; return 1 ;;
    *) [ "$sc" -le 1 ] || { echo "system_server restarted ($sc)"; return 1; } ;;
  esac
  for s in $HEALTH; do
    [ "$(getprop "init.svc.$s")" = running ] && continue
    if [ "$s" = "$LAMP" ]; then echo "$s (lamp/fans) not running"; else echo "$s not running"; fi
    return 1
  done
  # soft checks: logged only
  [ -n "$(pidof org.lumen.projector)" ] || say "soft: org.lumen.projector not running"
  [ "$(getprop sys.z9x.blobs)" = ok ] || say "soft: sys.z9x.blobs=$(getprop sys.z9x.blobs)"
  return 0
}

case "$1" in
verifier)
  # Stock behaviour unless 'count' found an update slot. No HAL call and no conf here (this blocks init
  # before zygote-start). Skipping keeps the slot unmarked before boot_completed, so a broken update can
  # still roll back; with userdata checkpointing update_verifier would not mark it anyway (vold does).
  if ! gate_off; then
    case "$(getprop sys.z9x.ota)" in
      pending|rollback) say "update_verifier: not run on update slot $cur ($(getprop sys.z9x.ota))"; exit 0 ;;
    esac
  fi
  shift
  exec "$R/system/bin/update_verifier" "$@"
  ;;
count)
  # blocks init at post-fs-data: a few getprop/setprop calls and file reads, no boot HAL, no conf
  mkdir -p "$D" 2>/dev/null
  rm -f "$D"/*.tmp 2>/dev/null   # temp files of a state write that a power cut stopped (put)
  if [ -f "$D/log.txt" ] && [ $(( $(wc -c < "$D/log.txt" 2>/dev/null) + 0 )) -gt $LOG_MAX ]; then
    mv -f "$D/log.txt" "$D/log.old" 2>/dev/null
  fi
  setprop sys.z9x.slot "$cur"
  # PackageManager parse cache, once per Lumen build (before system_server starts). The image keeps one
  # fixed mtime for every file and the fingerprint stays the base one, so after an update the cached parse
  # results would still look current. Init waits for this exec and the PWM watchdog punishes a late early boot,
  # so the cache is only renamed here (one rename); 'info' deletes it in the background. Should the rename fail,
  # a delete of at most 2 s. The build is recorded only once package_cache is gone, else the next boot tries again.
  bid=$(getprop ro.lumen.build_id)
  if [ -n "$bid" ] && [ -d "$R/data/system" ]; then
    old=; read -r old 2>/dev/null < "$R/data/system/lumen_pkgcache.build_id"
    if [ "$old" != "$bid" ]; then
      pc=$R/data/system/package_cache   # an unfinished delete: pc.lumen_old exists, the rename moves pc into it
      if [ ! -e "$pc" ] || mv "$pc" "$pc.lumen_old" 2>/dev/null || { timeout 2 rm -rf "$pc" 2>/dev/null; [ ! -e "$pc" ]; }; then
        put "$R/data/system/lumen_pkgcache.build_id" "$bid"
        say "package parse cache set aside for build $bid"
      else
        say "package parse cache of an older build neither renamed nor deleted within 2 s: tried again next boot"
      fi
    fi
  fi
  if gate_off; then setprop sys.z9x.ota none; exit 0; fi
  if ota_slot; then
    n=; s=
    read -r n s 2>/dev/null < "$D/attempts"
    case "$n" in 0|[1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]) ;; *) n=0 ;; esac
    [ "$s" = "$cur" ] || n=0
    n=$((n + 1))
    if put "$D/attempts" "$n $cur"; then say "update slot $cur boot attempt $n"
    else say "update slot $cur boot attempt $n, but $D/attempts cannot be written"; fi
    setprop sys.z9x.ota.attempt "$n"
    # pending is the conservative value (StrGate keeps the projector awake, Preflight blocks installs);
    # the rc trigger on it starts z9x_ota_decide and z9x_ota_watch once this exec has returned
    setprop sys.z9x.ota pending
  else
    tgt=; how=
    if [ -f "$D/rolledback" ]; then read -r rd tgt how 2>/dev/null < "$D/rolledback"; how="$how ($rd)"
    elif [ -f "$D/attempts" ]; then read -r rn tgt 2>/dev/null < "$D/attempts"; how="bootloader fallback after $rn attempt(s)"
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
decide)
  # first run of this boot (sys.* props start empty): the only one that acts; a later run (probe request)
  # only reads the HAL again and publishes what it read
  first=; [ -z "$(getprop sys.z9x.ota.hal)" ] && first=1
  setprop sys.z9x.ota.hal probing
  if gate_off; then setprop sys.z9x.ota.hal "bad:gate off"; exit 0; fi
  if ! load_conf; then
    setprop sys.z9x.ota.hal "bad:conf"
    say "$CONF: $CONF_WHY"
    if [ -n "$first" ] && [ "$(getprop sys.z9x.ota)" = pending ] && ota_slot; then
      put "$D/no_rollback" "$cur gate conf missing or invalid"
      setwhy "no rollback: gate conf missing or invalid"
      say "rollback NOT possible on $cur: gate conf missing or invalid (lumen_escape and the bootloader's tries take over)"
    fi
    exit 0
  fi
  probe
  [ -n "$first" ] || exit 0
  [ "$(getprop sys.z9x.ota)" = pending ] && ota_slot || exit 0
  if [ "$M_CUR" = 0 ]; then
    # marked already (e.g. a restart during the merge): never a rollback, whatever the attempt count says;
    # count's attempt was provisional
    confirmed "seen at boot, e.g. a restart during the merge"
    setprop sys.z9x.ota.attempt ""
    exit 0
  fi
  n=$(getprop sys.z9x.ota.attempt)
  case "$n" in [1-9]|[1-9][0-9]|[1-9][0-9][0-9]|[1-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9]) ;; *) n=1 ;; esac
  if [ "$n" = 1 ] && [ "$(firstword "$D/no_rollback")" = "$cur" ]; then
    # first attempt of this update, so the record is from an earlier one: it must not take this update's
    # rollback away
    say "no_rollback of an earlier update of $cur removed ($(cut -d' ' -f2- "$D/no_rollback"))"
    rm -f "$D/no_rollback"
  fi
  if [ $M_CUR = 2 ]; then say "update slot $cur boot attempt $n/$MAX_ATTEMPTS (marked state unknown)"
  else say "update slot $cur boot attempt $n/$MAX_ATTEMPTS"; fi
  if [ -f "$D/rolledback" ]; then
    rd=; rs=; rr=
    read -r rd rs rr 2>/dev/null < "$D/rolledback"
    if [ "$rs" = "$cur" ]; then
      say "the rollback requested at $rd ($rr) did not take effect: still on $cur"
      put "$D/no_rollback" "$cur rollback to $other did not take effect"
    fi
    rm -f "$D/rolledback"
  fi
  rb=
  if [ "$(getprop ro.lumen.ota.test_fail)" = 1 ]; then rb="test_fail (rollback drill)"
  elif [ "$n" -gt "$MAX_ATTEMPTS" ]; then rb="boot attempts"; fi
  if [ -n "$rb" ]; then
    if [ "$(firstword "$D/no_rollback")" = "$cur" ]; then
      say "$rb: slot $cur cannot be rolled back ($(cut -d' ' -f2- "$D/no_rollback")); lumen_escape and the bootloader's tries decide"
    else
      rollback "$rb" && exit 0
    fi
  fi
  exit 0
  ;;
watch)
  if ! load_conf; then say "watch: $CONF: $CONF_WHY (nothing watched)"; exit 0; fi
  t=0; sct=
  while [ $t -lt $DEADLINE ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
    case "$(getprop sys.z9x.ota)" in pending) ;; *) exit 0 ;; esac
    if [ -z "$sct" ]; then
      # a crash loop: init restarts zygote and system_server while boot never completes. This comes before
      # lumen_escape's 8 starts, which waits for the gate while it can still act
      sc=$(getprop sys.system_server.start_count)
      case "$sc" in
        ''|*[!0-9]*) ;;
        *) if [ ${#sc} -gt 4 ] || [ "$sc" -ge "$START_MAX" ]; then
             sct=1
             rollback "system_server restarted ($sc starts)" && exit 0
           fi ;;
      esac
    fi
    sleep 10; t=$((t + 10))
  done
  [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
  [ "$(getprop sys.z9x.ota)" = pending ] || exit 0
  rollback "no boot_completed within ${DEADLINE}s" && exit 0
  # stuck and no way back: restart, so the bootloader's tries (and lumen_escape) see a failed start
  say "slot $cur did not complete boot within ${DEADLINE}s: restarting (reboot,z9x-ota-stuck)"
  setprop sys.powerctl "reboot,z9x-ota-stuck"
  ;;
health|mark)
  [ "$(getprop sys.z9x.ota)" = pending ] || exit 0
  ota_slot || exit 0
  if ! load_conf; then
    say "$1: $CONF: $CONF_WHY"
    unhealthy "gate conf missing or invalid: not checked, not marked"
    exit 0
  fi
  # with userdata checkpointing vold marks the slot at boot_completed: then it is usually marked already
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
    mark_ok; mk=$?
    if [ $mk = 0 ]; then
      rm -f "$D/attempts"
      put "$D/no_rollback" "$cur marked successful"
      setprop sys.z9x.ota marked
      setprop sys.z9x.ota.why ""
      say "slot $cur marked successful by the health gate; update_engine merges in the background"
    elif [ $mk = 2 ]; then
      # not proven marked: attempts stays and no no_rollback is written, so the next boot counts on and a slot that
      # stays unmarked still rolls back after max_attempts (rollback refuses a slot that reads back as marked)
      unhealthy "healthy, but markBootSuccessful failed (no read-back) (boot HAL)"
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
  # the parse cache count set aside (init does not wait for this service)
  if [ -e "$R/data/system/package_cache.lumen_old" ]; then
    rm -rf "$R/data/system/package_cache.lumen_old" 2>/dev/null
    say "old package parse cache deleted"
  fi
  ;;
*)
  echo "usage: lumen_ota.sh verifier|count|decide|watch|health|mark|info" >&2
  exit 2
  ;;
esac
exit 0
