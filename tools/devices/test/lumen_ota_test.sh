#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Host test of the neutral OTA boot gate tools/devices/ota/image/lumen_ota.sh and its lumen_escape.sh fork
# (tools/devices/ota/image/lumen_ota_escape.sh), with the HB data devices/goat_elegant/image/ota_gate.conf
#.
# POSIX sh, on the Mac or Linux, no projector:
#   sh tools/devices/test/lumen_ota_test.sh             every case, the scripts under sh, dash and ksh (those present)
#   SH=dash sh tools/devices/test/lumen_ota_test.sh     only under that shell (ksh: ksh93, a relative of the
#                                                       device's mksh, builtin sleep dropped)
#   sh tools/devices/test/lumen_ota_test.sh hb_         only the cases whose name starts with this
# The 49 cases of tools/ota/test/run.sh (the Z9X gate and boot rescue) are ported to this gate: the gate cases
# keep their scenario and their expected result; the rescue cases become lumen_escape cases (the HB has no boot
# rescue counter, design decision: lumen_escape and the bootloader's tries are the way out). Then the HB cases.
# Each case gets a fresh temp dir: a fake root (LUMEN_OTA_ROOT: metadata/, data/system, system/bin/bootctl =
# stubs/bootctl_twin, system/etc/lumen/ota_gate.conf, proc/uptime, dev/block/by-name) and the stub state (Z9X_T:
# props, misc.img with the bootloader_control struct at 0x800, hal/ faults, logcat, setprop.log, hal.log). The Z9X
# stubs (tools/ota/test/stubs: getprop, setprop, service, log, timeout, pidof) are copied, read only; sleep is this
# file's own: it records the request, moves /proc/uptime of the fake root on (simulated time), sets
# sys.boot_completed=1 once uptime reaches $Z9X_T/complete_at, and ends a loop that never stops after 400 sleeps
# (or $Z9X_T/sleep_cap; a failure unless the case expects it). A start is simulated in init's order: the
# bootloader (bootctl_twin twin-boot: U-Boot ab_select_slot on misc, one try of an unsuccessful slot) ->
# lumen_ota.sh count (post-fs-data) -> lumen_ota.sh decide (the rc trigger on sys.z9x.ota=pending) -> lumen_ota.sh
# verifier (zygote-start); a reboot request (sys.powerctl) ends it like init would. complete() =
# sys.boot_completed=1: lumen_ota.sh health while sys.z9x.ota=pending. Every case fails on any output of the
# scripts (stderr included), on a property value the real setprop would refuse (> 91 bytes), when count called the
# boot HAL, and when a run changed any file of the fake root outside /metadata/lumen_ota and /data/system (no
# partition path is ever written; the misc state the HAL keeps lives outside it). Exit status 0 only when every
# case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
IMG=$G/tools/devices/ota/image
ZSTUBS=$G/tools/ota/test/stubs
TWIN=$HERE/stubs/bootctl_twin
CONF=${LUMEN_OTA_CONF:-$G/devices/goat_elegant/image/ota_gate.conf}
ONLY=${1-}

if [ -z "${SH-}" ]; then   # every shell present, one run each
  rc=0
  for s in sh dash ksh; do
    command -v "$s" >/dev/null 2>&1 || { echo "($s not found, skipped)"; continue; }
    SH=$s sh "$0" "$@" || rc=1
  done
  exit $rc
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/lumen_ota_test.XXXXXX") || exit 1
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT
BIN=$WORK/bin
mkdir -p "$BIN"
for f in getprop setprop service log timeout pidof; do
  cp "$ZSTUBS/$f" "$BIN/$f" && chmod 755 "$BIN/$f"
done
printf '%s\n' '#!/bin/sh' \
  '# lumen_ota_test.sh sleep stub: returns at once; simulated time on /proc/uptime of the fake root' \
  'echo "$*" >> "$Z9X_T/sleep.log"' \
  'u=; read -r u _ < "$LUMEN_OTA_ROOT/proc/uptime"; u=$(( ${u%%.*} + ${1%%.*} ))' \
  'echo "$u.00 1.00" > "$LUMEN_OTA_ROOT/proc/uptime"' \
  'c=; [ -f "$Z9X_T/complete_at" ] && read -r c < "$Z9X_T/complete_at"' \
  'if [ -n "$c" ] && [ "$u" -ge "$c" ]; then "$(dirname "$0")/setprop" sys.boot_completed 1; fi' \
  'm=400; [ -f "$Z9X_T/sleep_cap" ] && read -r m < "$Z9X_T/sleep_cap"' \
  'if [ "$(grep -c . "$Z9X_T/sleep.log")" -ge "$m" ]; then : > "$Z9X_T/capped"; "$(dirname "$0")/setprop" sys.boot_completed 1; fi' \
  'exit 0' > "$BIN/sleep"
chmod 755 "$BIN/sleep"
# the twin through the real interpreter (a version-manager shim costs ~0.3 s per call), as the fake root's bootctl
PY=$(python3 -c 'import sys; print(sys.executable)') || exit 1
printf '#!/bin/sh\nexec "%s" "%s" "$@"\n' "$PY" "$TWIN" > "$WORK/bootctl"
chmod 755 "$WORK/bootctl"
PASS=0; FAIL=0; CASE=; CFAIL=0; T=; R=; EXPECT_CAP=

# ------------------------------------------------------------------ fixtures
begin() {  # case name: fresh root; slot a runs Lumen (marked), slot b holds the stock leftovers; a healthy system
  CASE=$1; CFAIL=0; EXPECT_CAP=
  T=$WORK/$1; R=$T/root
  mkdir -p "$T/hal" "$R/metadata/ota" "$R/metadata/lumen_ota" "$R/data/system" "$R/system/bin" \
    "$R/system/etc/lumen" "$R/proc" "$R/dev/block/by-name"
  echo "12.34 5.67" > "$R/proc/uptime"
  printf '%s\n' ro.boot.slot_suffix=_a ro.lumen.build_id=lumen-hb-1.0.8-202611040000 \
    init.svc.zygote=running init.svc.surfaceflinger=running init.svc.xgimi_middle=running \
    sys.system_server.start_count=1 sys.z9x.blobs=ok > "$T/props"
  echo org.lumen.projector > "$T/procs"
  cp "$CONF" "$R/system/etc/lumen/ota_gate.conf"
  cp "$WORK/bootctl" "$R/system/bin/bootctl" && chmod 755 "$R/system/bin/bootctl"
  # the HB misc as read on a test unit: a = 15/2/successful, b = 14/3
  twin twin-init a=15/2/1 b=14/3/0
  : > "$T/hal.log"; : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/out"; : > "$T/uv.log"; : > "$T/sleep.log"
  printf '#!/bin/sh\necho "ran $*" >> "$Z9X_T/uv.log"\n' > "$R/system/bin/update_verifier"
  chmod 755 "$R/system/bin/update_verifier"
  for p in boot_a boot_b vbmeta_a vbmeta_b misc super; do echo "$p" > "$R/dev/block/by-name/$p"; done
}
twin() { Z9X_T=$T "$PY" "$TWIN" "$@"; }
ota_to_b() {  # update_engine on slot a wrote slot b (unbootable while writing, then setActiveBootSlot(1)); reboot
  printf _a > "$R/metadata/ota/snapshot-boot"
  twin twin-set a=14/2/1 b=15/6/0 merge=2
}
conf_set() {  # key value: change one line of the fake root's ota_gate.conf
  awk -v k="$1" -v v="$2" 'index($0, k "=") == 1 { print k "=" v; next } { print }' \
    "$R/system/etc/lumen/ota_gate.conf" > "$T/conf.tmp" && mv "$T/conf.tmp" "$R/system/etc/lumen/ota_gate.conf"
}

snap() { (cd "$R" && find . \( -path ./metadata/lumen_ota -o -path ./data/system -o -path ./proc \) -prune -o -type f -exec cksum {} + | LC_ALL=C sort); }
run() {
  s=$1; shift
  snap > "$T/snap.before"
  if [ "${SH##*/}" = ksh ]; then   # ksh93 (mksh's relative): drop its builtin sleep, the stub must answer
    env LUMEN_OTA_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" ksh -c 'builtin -d sleep; f=$1; shift; . "$f"' ksh "$IMG/$s" "$@" >> "$T/out" 2>&1
  else env LUMEN_OTA_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$IMG/$s" "$@" >> "$T/out" 2>&1; fi
  snap > "$T/snap.after"
  cmp -s "$T/snap.before" "$T/snap.after" || fail "$s $*: changed a file outside metadata/lumen_ota and data/system: $(diff "$T/snap.before" "$T/snap.after" | grep '^[<>]' | head -3 | tr '\n' ' ')"
}
gate() { run lumen_ota.sh "$@"; }
escape() { run lumen_ota_escape.sh; }
prop() { Z9X_T=$T "$BIN/getprop" "$1"; }
setp() { awk -v k="$1" 'index($0, k "=") != 1' "$T/props" > "$T/props.tmp"; printf '%s=%s\n' "$1" "$2" >> "$T/props.tmp"; mv "$T/props.tmp" "$T/props"; }
rebooted() { [ -n "$(prop sys.powerctl)" ]; }
hal_calls() { grep -c . "$T/hal.log"; }

new_boot() {  # runtime properties of the last boot are gone; ro.* and init.svc.* stay; uptime starts again
  grep -v -e '^sys\.boot_completed=' -e '^sys\.z9x\.' -e '^sys\.powerctl=' "$T/props" > "$T/props.tmp"
  mv "$T/props.tmp" "$T/props"
  echo "12.34 5.67" > "$R/proc/uptime"
  : > "$T/uv.log"
}
bootloader() {  # the slot the bootloader starts (ro.boot.slot_suffix); 1 = no bootable slot
  BOOTED=$(twin twin-boot) || { BOOTED=none; return 1; }
  setp ro.boot.slot_suffix "$BOOTED"
}
count_only() {  # post-fs-data: lumen_ota.sh count, which must not call the boot HAL
  h0=$(hal_calls)
  gate count
  [ "$(hal_calls)" = "$h0" ] || fail "count called the boot HAL: $(tail -n +$((h0 + 1)) "$T/hal.log" | tr '\n' ';')"
}
boot() {  # one start, in init's order
  new_boot
  bootloader || return 0
  count_only; rebooted && return 0
  [ "$(prop sys.z9x.ota)" = pending ] && gate decide
  rebooted && return 0
  gate verifier
  return 0
}
die_early() {  # a start that dies before post-fs-data (kernel, first-stage mount, the PWM watchdog): one try gone
  new_boot; bootloader
}
complete() {  # sys.boot_completed=1 (lumen_ota.rc trigger)
  setp sys.boot_completed 1
  [ "$(prop sys.z9x.ota)" = pending ] && gate health
  return 0
}

# ------------------------------------------------------------------ assertions
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
eq() { [ "$2" = "$3" ] || fail "$1: got '$2', want '$3'"; }
expect_prop() { eq "$1" "$(prop "$1")" "$2"; }
expect_reboot() { eq "reboot request" "$(prop sys.powerctl)" "$1"; }     # '' = none
expect_file() { eq "$1" "$(cat "$R/metadata/$1" 2>/dev/null)" "$2"; }
expect_nofile() { [ ! -e "$R/metadata/$1" ] || fail "$1 exists: $(cat "$R/metadata/$1")"; }
expect_word1() { eq "first word of $1" "$(awk '{print $1; exit}' "$R/metadata/$1" 2>/dev/null)" "$2"; }
expect_bc() { grep -qx "bootctl $1" "$T/hal.log" || fail "no call 'bootctl $1' (calls: $(tr '\n' ';' < "$T/hal.log"))"; }
expect_no_bc() { if grep -q "^bootctl $1\$" "$T/hal.log" || grep -q "^bootctl $1 " "$T/hal.log"; then fail "bootctl $1 called"; fi; }
expect_no_hal_at_all() { [ ! -s "$T/hal.log" ] || fail "boot HAL called: $(tr '\n' ';' < "$T/hal.log")"; }
expect_slots() { eq "misc slots" "$(twin twin-show)" "$1"; }
expect_log() { grep -q "$1" "$R/metadata/lumen_ota/log.txt" 2>/dev/null || fail "lumen_ota/log.txt lacks '$1'"; }
expect_logcat() { grep -q "$1" "$T/logcat" || fail "logcat lacks '$1'"; }
expect_uv() { if [ "$1" = ran ]; then [ -s "$T/uv.log" ] || fail "update_verifier not run"
              else [ ! -s "$T/uv.log" ] || fail "update_verifier ran"; fi; }
end() {
  [ ! -s "$T/out" ] || fail "script output: $(head -5 "$T/out" | tr '\n' ' ')"
  ! grep -q '^TOO_LONG' "$T/setprop.log" || fail "a property value longer than 91 bytes: $(grep '^TOO_LONG' "$T/setprop.log")"
  if [ -e "$T/capped" ] && [ -z "$EXPECT_CAP" ]; then fail "a loop ran into the test's sleep cap"; fi
  # state files are written whole (a temp file, then one rename): no temp file may stay behind
  tl=$(find "$R/metadata" "$R/data" -name '*.tmp' 2>/dev/null | sed "s|^$R/||" | tr '\n' ' ')
  [ -z "$tl" ] || fail "temp files left: $tl"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else
    FAIL=$((FAIL + 1)); echo "FAIL $CASE"
    [ -f "$R/metadata/lumen_ota/log.txt" ] && sed 's/^/     lumen_ota| /' "$R/metadata/lumen_ota/log.txt"
  fi
}

# ================================================================== the 49 Z9X cases, ported
# ------------------------------------------------------------------ gate: normal boots
case_gate_normal_boot() {
  begin gate_normal_boot
  boot
  expect_prop sys.z9x.ota none; expect_prop sys.z9x.slot _a; expect_reboot ""
  expect_uv ran; expect_no_hal_at_all
  complete
  gate health; gate watch
  expect_prop sys.z9x.ota none; expect_reboot ""; expect_no_hal_at_all
  expect_file ../data/system/lumen_pkgcache.build_id lumen-hb-1.0.8-202611040000
  expect_slots "a=15/2/1 b=14/3/0 merge=0"
  end
}
case_gate_info_vbmeta() {
  begin gate_info_vbmeta
  { printf AVB0; dd if=/dev/zero bs=1 count=116 2>/dev/null; printf '\000\000\000\003'; } > "$R/dev/block/by-name/vbmeta_b"
  printf 'XXXX' > "$R/dev/block/by-name/vbmeta_a"
  gate info
  expect_prop sys.z9x.vbmeta_other.flags 3; expect_prop sys.z9x.vbmeta_cur.flags none; expect_no_hal_at_all
  end
}
case_gate_kill_switch() {
  begin gate_kill_switch
  ota_to_b; setp ro.lumen.ota.gate 0
  boot
  expect_prop sys.z9x.ota none; expect_uv ran; expect_no_hal_at_all; expect_nofile lumen_ota/attempts
  gate decide                                                   # a probe request: refused, no HAL call
  expect_prop sys.z9x.ota.hal "bad:gate off"; expect_no_hal_at_all
  end
}

# ------------------------------------------------------------------ gate: an update slot
case_gate_ota_vold_marks_first() {
  begin gate_ota_vold_marks_first
  ota_to_b
  boot
  eq booted "$BOOTED" _b
  expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.attempt 1; expect_file lumen_ota/attempts "1 _b"
  expect_bc "is-slot-marked-successful 1"; expect_uv "not run"; expect_reboot ""
  expect_prop sys.z9x.ota.hal ok; expect_prop sys.z9x.ota.marked 0; expect_prop sys.z9x.ota.merge snapshotted
  twin twin-set b=15/1/1 merge=3                                 # vold commits the checkpoint, the merge starts
  complete
  expect_prop sys.z9x.ota merging; expect_reboot ""; expect_nofile lumen_ota/attempts
  expect_word1 lumen_ota/no_rollback _b; expect_no_bc mark-boot-successful; expect_no_bc set-active-boot-slot
  expect_log "marked successful (by vold at boot_completed)"; expect_log "health checks passed"
  grep -qx 90 "$T/sleep.log" || fail "no 90 s health delay"
  end
}
case_gate_ota_vold_marks_unhealthy() {
  begin gate_ota_vold_marks_unhealthy
  ota_to_b; boot
  twin twin-set b=15/1/1 merge=0; setp init.svc.xgimi_middle stopped
  complete
  expect_prop sys.z9x.ota unhealthy; expect_prop sys.z9x.ota.why "xgimi_middle (lamp/fans) not running"
  expect_reboot ""; expect_no_bc set-active-boot-slot
  end
}
case_gate_ota_unmarked_healthy_mark() {
  begin gate_ota_unmarked_healthy_mark
  ota_to_b; boot; complete
  expect_bc mark-boot-successful; expect_prop sys.z9x.ota marked; expect_prop sys.z9x.ota.why ""; expect_reboot ""
  expect_slots "a=14/2/1 b=15/1/1 merge=2"; expect_nofile lumen_ota/attempts
  end
}
case_gate_ota_unmarked_unhealthy_rollback() {
  begin gate_ota_unmarked_unhealthy_rollback
  ota_to_b; boot
  setp sys.system_server.start_count 3
  complete
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota rollback; expect_bc "set-active-boot-slot 0"
  expect_bc get-active-boot-slot; expect_slots "a=15/6/1 b=14/5/0 merge=2"
  grep -q ' _b health: system_server restarted (3)$' "$R/metadata/lumen_ota/rolledback" || fail "rolledback: $(cat "$R/metadata/lumen_ota/rolledback")"
  boot                                                           # the bootloader takes a; the indicator still says _a
  eq booted "$BOOTED" _a
  expect_prop sys.z9x.ota rolledback; expect_nofile lumen_ota/rolledback; expect_nofile lumen_ota/attempts
  end
}
case_gate_ota_4th_attempt_rollback() {
  begin gate_ota_4th_attempt_rollback
  ota_to_b
  for i in 1 2 3; do boot; expect_reboot ""; expect_prop sys.z9x.ota.attempt $i; done
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota rollback; expect_bc "set-active-boot-slot 0"
  expect_bc "is-slot-bootable 0"; expect_slots "a=15/6/1 b=14/2/0 merge=2"
  boot
  eq booted "$BOOTED" _a; expect_reboot ""; expect_prop sys.z9x.ota rolledback
  expect_log "booted _a after a failed update of _b: boot attempts"
  complete; expect_prop sys.z9x.ota rolledback
  end
}
case_gate_test_fail_drill() {
  begin gate_test_fail_drill
  ota_to_b; setp ro.lumen.ota.test_fail 1
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota.attempt 1
  end
}
case_gate_mark_request() {
  begin gate_mark_request
  ota_to_b; boot
  gate mark
  expect_prop sys.z9x.ota marked; expect_bc mark-boot-successful; [ ! -s "$T/sleep.log" ] || fail "mark request slept"
  end
}
case_gate_restart_during_merge() {
  begin gate_restart_during_merge
  ota_to_b; twin twin-set b=15/1/1 merge=3
  boot
  expect_prop sys.z9x.ota merging; expect_nofile lumen_ota/attempts; expect_word1 lumen_ota/no_rollback _b
  expect_prop sys.z9x.ota.attempt ""; expect_uv ran; expect_reboot ""
  end
}
case_gate_deadline_rollback() {
  begin gate_deadline_rollback
  ota_to_b; boot
  gate watch
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota rollback
  eq "watch sleeps" "$(grep -c . "$T/sleep.log")" 48
  end
}
case_gate_deadline_boot_completed() {
  begin gate_deadline_boot_completed
  ota_to_b; boot; setp sys.boot_completed 1
  gate watch
  expect_reboot ""; expect_prop sys.z9x.ota pending
  end
}

# ------------------------------------------------------------------ gate: boot HAL failures
case_gate_hal_down_no_rollback() {
  begin gate_hal_down_no_rollback
  ota_to_b; touch "$T/hal/down"
  for i in 1 2 3 4; do boot; expect_reboot ""; done
  expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.attempt 4; expect_uv "not run"
  expect_word1 lumen_ota/no_rollback _b; expect_prop sys.z9x.ota.hal "bad:current slot unknown"
  expect_prop sys.z9x.ota.why "no rollback: cannot read whether slot _b is marked (boot HAL)"
  expect_log "bootctl is-slot-marked-successful 1 failed (rc 70): Failed to get bootctl module."
  setp sys.system_server.start_count 8                           # crash loop on the 4th start: the gate gave up
  escape
  expect_reboot reboot,fastboot; expect_logcat "rebooting into fastbootd"
  end
}
hal_fault_case() {  # name fault-file: is-slot-marked-successful answers badly -> unknown, never a rollback
  begin "$1"
  ota_to_b; touch "$T/hal/$2"
  printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending; expect_no_bc set-active-boot-slot
  expect_log "bootctl is-slot-marked-successful 1 failed"; expect_word1 lumen_ota/no_rollback _b
  expect_prop sys.z9x.ota.hal "bad:marked unknown"
  end
}
case_gate_hal_exception() { hal_fault_case gate_hal_exception err_is-slot-marked-successful; }
case_gate_hal_multiline() { hal_fault_case gate_hal_multiline multi_is-slot-marked-successful; }
case_gate_hal_garbled() { hal_fault_case gate_hal_garbled garble_is-slot-marked-successful; }
case_gate_hal_hang() {
  begin gate_hal_hang
  ota_to_b; touch "$T/hal/hang"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending; expect_word1 lumen_ota/no_rollback _b
  expect_log "bootctl is-slot-marked-successful 1 failed (rc 124)"; expect_prop sys.z9x.ota.hal "bad:current slot unknown"
  end
}
case_gate_setactive_fails() {
  begin gate_setactive_fails
  ota_to_b; touch "$T/hal/err_set-active-boot-slot"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending; expect_bc "set-active-boot-slot 0"
  expect_prop sys.z9x.ota.why "no rollback: set-active-boot-slot 0 failed"
  end
}
case_gate_setactive_ignored() {
  begin gate_setactive_ignored
  ota_to_b; touch "$T/hal/ignore_set-active-boot-slot"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_bc get-active-boot-slot; expect_log "active slot reads 1 after set-active-boot-slot 0"
  end
}
case_gate_setactive_odd_reply() {   # the switch happened, the reply shape is not the expected one
  begin gate_setactive_odd_reply
  ota_to_b; touch "$T/hal/odd_set-active-boot-slot"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_slots "a=15/6/1 b=14/5/0 merge=2"
  expect_log "reply not understood, but the active slot reads 0"
  end
}
case_gate_other_not_bootable() {
  begin gate_other_not_bootable
  ota_to_b; twin twin-set a=14/0/0; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_no_bc set-active-boot-slot; expect_prop sys.z9x.ota.why "no rollback: slot _a is not bootable"
  end
}
case_gate_cancelled_no_rollback() {   # 'lumen-install rescue' cancelled the update and re-flashed system
  begin gate_cancelled_no_rollback
  ota_to_b; twin twin-set merge=4; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_no_bc set-active-boot-slot; expect_prop sys.z9x.ota pending
  expect_prop sys.z9x.ota.why "no rollback: the update was cancelled (system written again)"
  expect_word1 lumen_ota/no_rollback _b
  end
}
case_gate_stale_no_rollback() {   # left by an earlier update of this slot
  begin gate_stale_no_rollback
  ota_to_b; echo "_b set-active-boot-slot 0 failed" > "$R/metadata/lumen_ota/no_rollback"
  boot
  expect_nofile lumen_ota/no_rollback; expect_log "no_rollback of an earlier update of _b removed"
  for i in 2 3; do boot; expect_reboot ""; done
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota.attempt 4
  end
}
case_gate_mark_fails_no_reboot() {   # 1.0.0 on the Z9X, 2026-10-08: the mark failed -> needless "rollback"
  begin gate_mark_fails_no_reboot
  ota_to_b; touch "$T/hal/err_mark-boot-successful"
  boot; complete
  expect_reboot ""; expect_no_bc set-active-boot-slot; expect_prop sys.z9x.ota unhealthy
  expect_prop sys.z9x.ota.why "healthy, but markBootSuccessful failed (boot HAL)"
  end
}
case_gate_deadline_hal_down_stuck() {
  begin gate_deadline_hal_down_stuck
  ota_to_b; boot; touch "$T/hal/down"
  gate watch
  expect_reboot reboot,z9x-ota-stuck; expect_word1 lumen_ota/no_rollback _b
  end
}
aidl_begin() {  # the same gate with hal=aidl (the Z9X 'service call' route): stubs/service state, no bootctl
  begin "$1"
  conf_set hal aidl; rm -f "$R/system/bin/bootctl"
  setp ro.boot.slot_suffix _b; printf _a > "$R/metadata/ota/snapshot-boot"
  echo 1 > "$T/hal/current"; echo 1 > "$T/hal/active"; echo 2 > "$T/hal/merge"
  echo 1 > "$T/hal/marked0"; echo 0 > "$T/hal/marked1"; echo 1 > "$T/hal/bootable0"; echo 1 > "$T/hal/bootable1"
}
aidl_boot() { new_boot; count_only; [ "$(prop sys.z9x.ota)" = pending ] && gate decide; rebooted || gate verifier; }
case_gate_bootctl_fallback() {   # Z9X: the bootctl fallback of the AIDL route; here: the hal=aidl route itself
  aidl_begin gate_hal_aidl_mark
  aidl_boot
  expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.hal ok; grep -qx '7 1' "$T/hal.log" || fail "no AIDL call 7 1"
  complete
  expect_prop sys.z9x.ota marked; eq marked1 "$(cat "$T/hal/marked1")" 1
  end
  aidl_begin gate_hal_aidl_rollback
  printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  aidl_boot
  expect_reboot reboot,z9x-ota-rollback; eq active "$(cat "$T/hal/active")" 0; grep -qx '9 0' "$T/hal.log" || fail "no AIDL call 9 0"
  end
}
case_gate_bootctl_errors() {
  begin gate_bootctl_errors
  ota_to_b; touch "$T/hal/bootctl_fail"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending
  expect_log "bootctl is-slot-marked-successful 1 failed (rc 70)"
  end
}

# ------------------------------------------------------------------ gate: what the updater is told after a restart
case_gate_rolledback_only_when_real() {
  begin gate_rolledback_only_when_real
  echo "20261008-215339 _a mark failed" > "$R/metadata/lumen_ota/rolledback"   # left by an update of this slot
  echo "1" > "$R/metadata/lumen_ota/attempts"                                  # no slot named
  boot                                                       # slot _a, merge done, no indicator
  expect_prop sys.z9x.ota none; expect_nofile lumen_ota/rolledback; expect_nofile lumen_ota/attempts
  expect_log "nothing was rolled back"; expect_no_hal_at_all
  end
}
case_gate_bootloader_fallback() {   # the slot dies before post-fs-data until the bootloader's tries run out
  begin gate_bootloader_fallback
  ota_to_b
  boot; boot; expect_prop sys.z9x.ota.attempt 2; expect_reboot ""
  for i in 1 2 3 4; do die_early; eq "start $i" "$BOOTED" _b; done
  boot
  eq booted "$BOOTED" _a; expect_prop sys.z9x.ota rolledback; expect_log "bootloader fallback after 2 attempt(s)"
  expect_slots "a=14/2/1 b=15/0/0 merge=2"
  end
}
case_gate_legacy_attempts() {
  begin gate_legacy_attempts
  echo 2 > "$R/metadata/lumen_ota/attempts"
  boot
  expect_prop sys.z9x.ota none; expect_nofile lumen_ota/attempts
  end
}
case_gate_rollback_did_not_take() {
  begin gate_rollback_did_not_take
  ota_to_b; printf '4 _b\n' > "$R/metadata/lumen_ota/attempts"
  echo "20261009-101010 _b boot attempts" > "$R/metadata/lumen_ota/rolledback"
  boot
  expect_reboot ""; expect_no_bc set-active-boot-slot; expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.attempt 5
  expect_file lumen_ota/no_rollback "_b rollback to _a did not take effect"; expect_nofile lumen_ota/rolledback
  expect_log "lumen_escape and the bootloader's tries decide"
  end
}

# ------------------------------------------------------------------ rescue -> lumen_escape
escape_after() {  # n: system_server starts, the escape runs on this boot
  setp sys.system_server.start_count "$1"; escape
}
case_rescue_123_fastboot() {
  begin escape_8_starts_fastboot
  boot; escape_after 8
  expect_reboot reboot,fastboot; expect_logcat "lumen_escape: boot not completed (system_server starts=8"
  grep -qx 15 "$T/sleep.log" || fail "no 15 s wait before the reboot"
  end
}
case_rescue_reset_on_completed() {
  begin escape_exits_on_completed
  boot; complete; escape_after 9
  expect_reboot ""; [ ! -s "$T/sleep.log" ] || fail "escape slept after boot_completed"
  end
}
case_rescue_normal_boots_never() {
  begin escape_normal_boots_never
  echo 60 > "$T/complete_at"
  i=0
  while [ $i -lt 10 ]; do boot; expect_reboot ""; escape; expect_reboot ""; complete; i=$((i + 1)); done
  expect_prop sys.z9x.ota none; expect_no_hal_at_all
  end
}
case_rescue_slot_change() {   # after a rollback (sys.z9x.ota=rolledback) the escape acts as before
  begin escape_after_rollback_acts
  ota_to_b; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot; expect_reboot reboot,z9x-ota-rollback
  boot; expect_prop sys.z9x.ota rolledback
  escape_after 8; expect_reboot reboot,fastboot
  end
}
case_rescue_ota_grace_bound() {   # the gate can still act: quiet past 8 starts, but never past 15 min of uptime
  begin escape_quiet_while_pending
  ota_to_b; boot; setp sys.system_server.start_count 9
  escape                                                        # z9x_ota_watch never ran: the 900 s limit ends it
  expect_reboot reboot,fastboot; expect_logcat "the OTA gate acts first"
  eq "quiet log lines" "$(grep -c 'acts first' "$T/logcat")" 1
  expect_logcat "boot not completed (system_server starts=9, uptime=902s)"
  eq "escape sleeps" "$(grep -c . "$T/sleep.log")" 179           # 178 x 5 s from 12 s of uptime, then the 15 s wait
  end
}
case_rescue_ota_marked_acts() {   # the update slot is marked (merge running) and fails: no waiting
  begin escape_acts_when_marked
  ota_to_b; twin twin-set b=15/1/1 merge=3
  boot; expect_prop sys.z9x.ota merging
  escape_after 8; expect_reboot reboot,fastboot
  end
}
case_rescue_kill_switch_prop() {   # no_rollback names this slot: the gate cannot act any more
  begin escape_acts_after_no_rollback
  ota_to_b; twin twin-set a=14/0/0; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot; expect_prop sys.z9x.ota pending; expect_word1 lumen_ota/no_rollback _b
  escape_after 8; expect_reboot reboot,fastboot
  end
}
case_rescue_kill_switch_file() {   # a record of the other slot does not end the wait
  begin escape_quiet_other_slot_record
  ota_to_b; boot; echo "_a marked successful" > "$R/metadata/lumen_ota/no_rollback"
  EXPECT_CAP=1; echo 170 > "$T/sleep_cap"                      # 170 x 5 s: still below 15 min of uptime
  escape_after 8
  expect_reboot ""; [ -e "$T/capped" ] || fail "the escape did not wait"
  end
}
case_rescue_gate_off_no_wait() {
  begin escape_gate_off_acts
  ota_to_b; setp ro.lumen.ota.gate 0
  boot; expect_prop sys.z9x.ota none
  escape_after 8; expect_reboot reboot,fastboot; expect_no_hal_at_all
  end
}
case_rescue_unwritable() {
  begin gate_unwritable_metadata
  ota_to_b; chmod 555 "$R/metadata/lumen_ota"
  if [ -w "$R/metadata/lumen_ota" ]; then echo "     (skipped: running as root)"
  else
    boot
    expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.attempt 1; expect_reboot ""
    grep -q 'cannot be written' "$T/logcat" || fail "no 'cannot be written' line"
  fi
  chmod 755 "$R/metadata/lumen_ota"
  end
}
case_rescue_corrupt_counter() {
  begin gate_corrupt_attempts
  ota_to_b; printf 'x\001y _b\n' > "$R/metadata/lumen_ota/attempts"
  boot; expect_prop sys.z9x.ota.attempt 1; expect_file lumen_ota/attempts "1 _b"
  printf '08 _b\n' > "$R/metadata/lumen_ota/attempts"           # a leading zero is not a number here
  boot; expect_prop sys.z9x.ota.attempt 1; expect_reboot ""
  end
  begin gate_missing_state_dir
  ota_to_b; rm -rf "$R/metadata/lumen_ota"
  boot
  expect_prop sys.z9x.ota pending; expect_file lumen_ota/attempts "1 _b"
  end
}
case_rescue_log_rotation() {
  begin gate_log_rotation
  dd if=/dev/zero bs=1024 count=70 2>/dev/null | tr '\0' 'x' > "$R/metadata/lumen_ota/log.txt"
  echo lumen-hb-1.0.8-202611040000 > "$R/data/system/lumen_pkgcache.build_id"
  ota_to_b; new_boot; bootloader; count_only
  [ -f "$R/metadata/lumen_ota/log.old" ] || fail "log.txt not rotated"
  eq "new log lines" "$(grep -c . "$R/metadata/lumen_ota/log.txt")" 1
  end
}

# ------------------------------------------------------------------ both together
case_e2e_ota_rollback_then_normal() {
  begin e2e_ota_rollback_then_normal
  ota_to_b
  for i in 1 2 3 4; do boot; done                             # 4th: the gate rolls back
  expect_reboot reboot,z9x-ota-rollback
  boot; eq booted "$BOOTED" _a; expect_reboot ""; expect_prop sys.z9x.ota rolledback
  complete
  boot; expect_prop sys.z9x.ota none; expect_reboot ""      # the indicator still names _a: not an update slot
  end
}
case_e2e_source_slot_also_broken() {   # rollback worked, but the old slot loops as well
  begin e2e_source_slot_also_broken
  ota_to_b
  for i in 1 2 3 4; do boot; done
  boot; eq booted "$BOOTED" _a
  escape_after 8; expect_reboot reboot,fastboot
  end
}

# ------------------------------------------------------------------ static checks
case_syntax() {
  begin syntax
  for s in lumen_ota.sh lumen_ota_escape.sh; do
    sh -n "$IMG/$s" || fail "sh -n $s"
    if command -v dash >/dev/null 2>&1; then dash -n "$IMG/$s" || fail "dash -n $s"; fi
    # the device shell is mksh, a ksh: ksh93 is stricter about patterns ('(' in ${var#...} opens a group)
    if command -v ksh >/dev/null 2>&1; then ksh -n "$IMG/$s" || fail "ksh -n $s"; fi
    if grep -nE "<<-?[[:space:]]*['\"]?[A-Za-z_]" "$IMG/$s" | grep -v '^[0-9]*:#' | grep -q .; then fail "$s uses a here-document (mksh needs a writable TMPDIR)"; fi
  done
  rc=$IMG/lumen_ota.rc
  grep -q '^    exec u:r:su:s0 root root -- /system/bin/sh /system/etc/lumen/lumen_ota.sh count$' "$rc" || fail "lumen_ota.rc: exec line"
  for m in info decide watch health mark; do
    grep -q "^service z9x_ota_$m /system/bin/sh /system/etc/lumen/lumen_ota.sh $m\$" "$rc" || fail "lumen_ota.rc: service z9x_ota_$m"
  done
  awk '/^on property:sys.z9x.ota=pending$/ { p = 1; next } p && /^    start z9x_ota_decide$/ { d = 1 } /^$/ { p = 0 } END { exit !d }' "$rc" || fail "lumen_ota.rc: pending does not start z9x_ota_decide"
  grep -q '^service update_verifier /system/bin/sh /system/etc/lumen/lumen_ota.sh verifier$' "$IMG/lumen_update_verifier.rc" || fail "lumen_update_verifier.rc: service line"
  if grep -n 'z9x_ota\.sh\|/metadata/z9x_ota\|ro\.z9x\.' "$IMG/lumen_ota.sh" "$IMG/lumen_ota_escape.sh" "$IMG/lumen_ota.rc" "$IMG/lumen_update_verifier.rc" | grep -v ':#' | grep -q .; then
    fail "a Z9X gate path or ro.z9x property outside a comment"
  fi
  if grep -n -E 'G0130|mt9660|mt5873|goat_elegant|v6\.16\.24' "$IMG"/lumen_* | grep -q .; then fail "a projector literal in the neutral gate"; fi
  sh -n "$HERE/stubs/bootctl_realshape" || fail "sh -n bootctl_realshape"
  for f in "$IMG"/lumen_* "$CONF" "$TWIN" "$HERE/stubs/bootctl_realshape" "$0" "$HERE/lumen_ota_parity.sh"; do
    if LC_ALL=C grep -q "$(printf '\342\200\224')" "$f"; then fail "em dash in $f"; fi
  done
  end
}

# ================================================================== HB cases
case_hb_count_zero_hal() {   # count alone, every kind of boot: not one boot HAL call
  begin hb_count_zero_hal
  new_boot; bootloader; count_only; expect_prop sys.z9x.ota none
  ota_to_b; new_boot; bootloader; count_only; expect_prop sys.z9x.ota pending
  twin twin-set b=15/1/1 merge=3; new_boot; bootloader; count_only; expect_prop sys.z9x.ota pending
  printf '9 _b\n' > "$R/metadata/lumen_ota/attempts"; new_boot; bootloader; count_only
  expect_prop sys.z9x.ota pending; expect_reboot ""
  rm -f "$R/system/etc/lumen/ota_gate.conf"; new_boot; bootloader; count_only; expect_prop sys.z9x.ota pending
  expect_no_hal_at_all
  end
}
case_hb_normal_boot_zero_bootctl() {
  begin hb_normal_boot_zero_bootctl
  for i in 1 2 3; do boot; complete; gate health; gate watch; gate info; gate verifier; done
  expect_no_hal_at_all; expect_prop sys.z9x.ota.hal ""
  end
}
case_hb_probe_request() {   # sys.z9x.ota.request=probe on a normal boot: facts only, nothing changes
  begin hb_probe_request
  boot; complete
  gate decide
  expect_prop sys.z9x.ota.hal ok; expect_prop sys.z9x.ota.marked 1; expect_prop sys.z9x.ota.merge none
  expect_prop sys.z9x.ota.active _a; expect_prop sys.z9x.ota none; expect_reboot ""
  expect_bc get-current-slot; expect_no_bc set-active-boot-slot; expect_no_bc mark-boot-successful
  expect_nofile lumen_ota/no_rollback; expect_nofile lumen_ota/attempts; expect_slots "a=15/2/1 b=14/3/0 merge=0"
  gate decide; expect_prop sys.z9x.ota.hal ok                   # a second request
  end
}
case_hb_probe_bad() {
  begin hb_probe_bad
  boot; rm -f "$R/system/bin/bootctl"; gate decide
  expect_prop sys.z9x.ota.hal "bad:no bootctl"; expect_prop sys.z9x.ota.marked ""; expect_no_hal_at_all
  end
  begin hb_probe_wrong_current
  boot; setp ro.boot.slot_suffix _b; echo 0 > "$T/hal/current_slot"; gate decide   # the HAL says a, the kernel b
  expect_prop sys.z9x.ota.hal "bad:current slot 0, slot suffix _b"
  end
  begin hb_probe_extra_line
  boot; touch "$T/hal/multi_get-snapshot-merge-status"; gate decide
  expect_prop sys.z9x.ota.hal "bad:merge status unknown"; expect_prop sys.z9x.ota.merge ""
  end
  begin hb_probe_bad_misc
  boot; printf 'XXXX' | dd of="$T/misc.img" bs=1 seek=2076 conv=notrunc 2>/dev/null   # break the CRC
  gate decide
  expect_prop sys.z9x.ota.hal "bad:current slot unknown"; expect_log "Error: bootloader_control in misc is invalid"
  end
}
case_hb_marked_high_attempts() {   # marked with any attempt count: marked or merging, never a rollback
  begin hb_marked_high_attempts
  ota_to_b; twin twin-set b=15/1/1 merge=3; printf '7 _b\n' > "$R/metadata/lumen_ota/attempts"
  setp ro.lumen.ota.test_fail 1
  boot
  expect_prop sys.z9x.ota merging; expect_reboot ""; expect_no_bc set-active-boot-slot; expect_nofile lumen_ota/attempts
  end
  begin hb_marked_no_merge
  ota_to_b; twin twin-set b=15/1/1 merge=0; printf '5 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_prop sys.z9x.ota marked; expect_reboot ""; expect_no_bc set-active-boot-slot
  end
}
case_hb_conf_missing() {
  begin hb_conf_missing
  ota_to_b; rm -f "$R/system/etc/lumen/ota_gate.conf"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.hal bad:conf; expect_reboot ""; expect_no_hal_at_all
  expect_file lumen_ota/no_rollback "_b gate conf missing or invalid"
  expect_prop sys.z9x.ota.why "no rollback: gate conf missing or invalid"
  gate watch; expect_reboot ""
  complete
  expect_prop sys.z9x.ota unhealthy; expect_prop sys.z9x.ota.why "gate conf missing or invalid: not checked, not marked"
  new_boot; bootloader; count_only; gate decide; escape_after 8
  expect_reboot reboot,fastboot
  end
}
bad_conf() {  # line... -> the conf with these lines must be refused
  printf '%s\n' "$@" > "$R/system/etc/lumen/ota_gate.conf"
  rm -f "$R/metadata/lumen_ota/no_rollback"; setp sys.z9x.ota pending; setp sys.z9x.ota.hal ""
  gate decide
  [ "$(prop sys.z9x.ota.hal)" = bad:conf ] || fail "conf accepted: $*"
  [ "$(awk '{print $1; exit}' "$R/metadata/lumen_ota/no_rollback" 2>/dev/null)" = _b ] || fail "no no_rollback for: $*"
}
case_hb_conf_invalid() {
  begin hb_conf_invalid
  ota_to_b; boot
  G1='hal=bootctl'; G2='health=zygote surfaceflinger xgimi_middle'; G3='lamp=xgimi_middle'; G4='ns=ro.lumen'
  G5='max_attempts=3'; G6='deadline=480'; G7='health_delay=90'; G8='start_count_max=4'
  bad_conf "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" "$G8"                         # hal missing
  bad_conf hal=hidl "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" "$G8"
  bad_conf "$G1" "$G1" "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" "$G8"             # twice
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" "$G8" "extra=1"         # unknown key
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" deadline=0480 "$G7" "$G8"           # leading zero
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" deadline=99999 "$G7" "$G8"
  bad_conf "$G1" "$G2" "$G3" "$G4" max_attempts=x "$G6" "$G7" "$G8"
  bad_conf "$G1" "$G2" "$G3" "$G4" max_attempts=0 "$G6" "$G7" "$G8"
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" start_count_max=1
  bad_conf "$G1" health= "$G4" "$G5" "$G6" "$G7" "$G8"
  bad_conf "$G1" 'health=zygote  surfaceflinger' "$G4" "$G5" "$G6" "$G7" "$G8"
  bad_conf "$G1" 'health=Zygote' "$G4" "$G5" "$G6" "$G7" "$G8"
  bad_conf "$G1" "$G2" lamp=gmpf_main "$G4" "$G5" "$G6" "$G7" "$G8"          # not in health
  bad_conf "$G1" "$G2" "$G3" ns=ro.z9x "$G5" "$G6" "$G7" "$G8"
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" "$(printf 'deadline=480\r')" "$G7" "$G8"
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" "$G8" "max_attempts 3"
  bad_conf "$G1" "$G2" "$G3" "$G4" "$G5" "$G6" " $G7" "$G8"
  printf '%s\n' "# a comment" "" "$G1" "$G2" "$G3" "$G4" "$G5" "$G6" "$G7" "$G8" > "$R/system/etc/lumen/ota_gate.conf"
  setp sys.z9x.ota.hal ""; gate decide; expect_prop sys.z9x.ota.hal ok
  printf '%s\n' "$G1" "$G2" "$G4" "$G5" "$G6" "$G7" > "$R/system/etc/lumen/ota_gate.conf"
  printf '%s' "$G8" >> "$R/system/etc/lumen/ota_gate.conf"                  # no lamp, no final newline
  setp sys.z9x.ota.hal ""; gate decide; expect_prop sys.z9x.ota.hal ok
  expect_no_bc set-active-boot-slot; expect_reboot ""
  end
}
case_hb_start_count_rollback() {   # a crash loop: system_server started 4 times while pending
  begin hb_start_count_below
  ota_to_b; boot
  setp sys.system_server.start_count 3; echo 60 > "$T/complete_at"
  gate watch                                                    # 3 starts: no rollback; the boot completes
  expect_reboot ""; expect_prop sys.z9x.ota pending
  end
  begin hb_start_count_rollback
  ota_to_b; boot
  setp sys.system_server.start_count 4
  gate watch
  expect_reboot reboot,z9x-ota-rollback; [ ! -s "$T/sleep.log" ] || fail "watch slept before the rollback"
  grep -q ' _b system_server restarted (4 starts)$' "$R/metadata/lumen_ota/rolledback" || fail "rolledback: $(cat "$R/metadata/lumen_ota/rolledback" 2>/dev/null)"
  boot; eq booted "$BOOTED" _a; expect_prop sys.z9x.ota rolledback
  end
  begin hb_start_count_no_rollback
  ota_to_b; twin twin-set a=14/0/0; boot
  setp sys.system_server.start_count 5
  gate watch                                                    # rollback impossible: the deadline, then stuck
  expect_reboot reboot,z9x-ota-stuck; expect_word1 lumen_ota/no_rollback _b
  eq "set-active calls" "$(grep -c '^bootctl set-active-boot-slot' "$T/hal.log")" 0
  end
}
case_hb_xgimi_middle_missing() {
  begin hb_xgimi_middle_missing
  ota_to_b; boot; setp init.svc.xgimi_middle restarting
  complete
  expect_reboot reboot,z9x-ota-rollback
  grep -q ' _b health: xgimi_middle (lamp/fans) not running$' "$R/metadata/lumen_ota/rolledback" || fail "rolledback: $(cat "$R/metadata/lumen_ota/rolledback" 2>/dev/null)"
  end
  begin hb_soft_checks_only_logged
  ota_to_b; boot; : > "$T/procs"; setp sys.z9x.blobs none
  complete
  expect_prop sys.z9x.ota marked; expect_log "soft: org.lumen.projector not running"; expect_log "soft: sys.z9x.blobs=none"
  end
}
case_hb_build_id_cache() {   # count only renames the cache (init waits for it); info deletes it in the background
  begin hb_build_id_cache
  DS=$R/data/system
  mkdir -p "$DS/package_cache/x"; : > "$DS/package_cache/x/f"
  boot
  [ ! -d "$DS/package_cache" ] || fail "package_cache kept on a new build"
  [ -f "$DS/package_cache.lumen_old/x/f" ] || fail "package_cache not set aside"
  expect_file ../data/system/lumen_pkgcache.build_id lumen-hb-1.0.8-202611040000; expect_log "package parse cache set aside"
  gate info
  [ ! -e "$DS/package_cache.lumen_old" ] || fail "info did not delete the old cache"; expect_log "old package parse cache deleted"
  mkdir -p "$DS/package_cache"; boot; gate info
  [ -d "$DS/package_cache" ] || fail "package_cache cleared again for the same build"
  setp ro.lumen.build_id lumen-hb-1.0.9-202611050000; boot
  [ ! -d "$DS/package_cache" ] || fail "package_cache kept after an update"
  expect_file ../data/system/lumen_pkgcache.build_id lumen-hb-1.0.9-202611050000
  setp ro.lumen.build_id ""; mkdir -p "$DS/package_cache"; boot
  [ -d "$DS/package_cache" ] || fail "package_cache cleared without a build id"
  end
  begin hb_build_id_cache_unfinished   # the last delete never finished (power cut): the rename moves into it
  DS=$R/data/system
  mkdir -p "$DS/package_cache.lumen_old/y" "$DS/package_cache/x"
  boot
  [ ! -e "$DS/package_cache" ] && [ -d "$DS/package_cache.lumen_old/package_cache/x" ] || fail "not set aside into the old one"
  expect_file ../data/system/lumen_pkgcache.build_id lumen-hb-1.0.8-202611040000
  gate info; [ ! -e "$DS/package_cache.lumen_old" ] || fail "info did not delete the old cache"
  end
  begin hb_build_id_cache_rename_fails   # the rename is refused: a bounded delete instead, then the build is recorded
  DS=$R/data/system
  mkdir -p "$DS/package_cache.lumen_old/package_cache/z" "$DS/package_cache/x"; : > "$DS/package_cache/x/f"
  boot
  [ ! -e "$DS/package_cache" ] || fail "package_cache kept although the delete could run"
  [ -d "$DS/package_cache.lumen_old/package_cache/z" ] || fail "the old aside was touched by count"
  expect_file ../data/system/lumen_pkgcache.build_id lumen-hb-1.0.8-202611040000
  end
  begin hb_build_id_cache_stuck   # neither the rename nor the delete works: the build is not recorded, next boot again
  DS=$R/data/system
  mkdir -p "$DS/package_cache/x"; chmod 555 "$DS"
  if [ -w "$DS" ]; then echo "     (skipped: running as root)"
  else
    boot
    expect_prop sys.z9x.ota none; expect_reboot ""; expect_log "neither renamed nor deleted within 2 s"
    [ ! -e "$DS/lumen_pkgcache.build_id" ] || fail "build recorded with the old cache in place"
  fi
  chmod 755 "$DS"
  end
}
reply_case() {  # name fault why [reply]: a bad bootctl reply on the 4th attempt -> no rollback, for this reason
  begin "$1"
  ota_to_b; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"; printf '%b' "${4-}" > "$T/hal/$2"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending; expect_no_bc set-active-boot-slot
  expect_prop sys.z9x.ota.why "no rollback: $3"; expect_word1 lumen_ota/no_rollback _b
  end
}
case_hb_bootctl_replies() {
  reply_case hb_reply_merge_garbled garble_get-snapshot-merge-status "the snapshot merge status is unknown (boot HAL)"
  reply_case hb_reply_merge_extra_line multi_get-snapshot-merge-status "the snapshot merge status is unknown (boot HAL)"
  reply_case hb_reply_bootable_garbled garble_is-slot-bootable "cannot read whether slot _a is bootable (boot HAL)"
  reply_case hb_reply_bootable_extra_line multi_is-slot-bootable "cannot read whether slot _a is bootable (boot HAL)"
  reply_case hb_reply_bootable_error err_is-slot-bootable "cannot read whether slot _a is bootable (boot HAL)"
  reply_case hb_reply_timeout hang "cannot read whether slot _b is marked (boot HAL)"
  # the real bootctl prints "0" for no; nothing on stdout with exit 70 is an error, never a "no"
  reply_case hb_reply_bootable_silent_70 reply_is-slot-bootable "cannot read whether slot _a is bootable (boot HAL)" '70\n'
  reply_case hb_reply_marked_silent_70 reply_is-slot-marked-successful "cannot read whether slot _b is marked (boot HAL)" '70\n'
  reply_case hb_reply_marked_transport transport_is-slot-marked-successful "cannot read whether slot _b is marked (boot HAL)"
  begin hb_reply_readback_extra_line   # set-active accepted, the read-back has an extra line: reboot (it was accepted)
  ota_to_b; touch "$T/hal/multi_get-active-boot-slot"; printf '3 _b\n' > "$R/metadata/lumen_ota/attempts"
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_log "accepted, the active slot cannot be read back"
  expect_slots "a=15/6/1 b=14/5/0 merge=2"
  end
  begin hb_reply_mark_odd             # mark-boot-successful prints a line: not accepted -> unhealthy, no reboot
  ota_to_b; touch "$T/hal/odd_mark-boot-successful"; boot; complete
  expect_reboot ""; expect_prop sys.z9x.ota unhealthy; expect_prop sys.z9x.ota.why "healthy, but markBootSuccessful failed (boot HAL)"
  end
  begin hb_reply_mark_ignored         # accepted, but the slot reads back unmarked
  ota_to_b; touch "$T/hal/ignore_mark-boot-successful"; boot; complete
  expect_reboot ""; expect_prop sys.z9x.ota unhealthy
  expect_prop sys.z9x.ota.why "healthy, but markBootSuccessful failed (no read-back) (boot HAL)"
  end
}
shape() {  # verb 'rc\nline...' want: one probe on a normal boot with this bootctl reply for both slots
  printf '%b' "$2" > "$T/hal/reply_$1"; setp sys.z9x.ota.hal ""
  gate decide
  case "$1:$3" in
    is-slot-marked-successful:yes) want="ok 1 a=1 b=1" ;;
    is-slot-marked-successful:no) want="ok 0 a=0 b=0" ;;
    is-slot-marked-successful:*) want="bad:marked unknown  a=? b=?" ;;
    is-slot-bootable:yes) want="ok 1 a=1 b=1" ;;
    is-slot-bootable:no) want="ok 1 a=0 b=0" ;;
    *) want="bad:bootable unknown 1 a=? b=?" ;;
  esac
  case "$1" in is-slot-bootable) w=bootable ;; *) w=marked ;; esac
  got="$(prop sys.z9x.ota.hal) $(prop sys.z9x.ota.marked) $(tail -n 1 "$R/metadata/lumen_ota/log.txt" | sed "s/.* $w \(a=. b=.\).*/\1/")"
  eq "$1 reply '$(printf '%b' "$2" | tr '\n' '|')'" "$got" "$want"
}
case_hb_bootctl_shapes() {   # bootctl.cpp: yes = exit 0 + "1", no = exit 70 + "0", all else unknown
  for v in is-slot-marked-successful is-slot-bootable; do
    begin "hb_shapes_$v"
    boot; complete
    shape $v '0\n1' yes; shape $v '70\n0' no
    shape $v '70' unknown                                       # no output, exit 70: an error, not a "no"
    shape $v '0' unknown                                        # no output, exit 0: not a "yes"
    shape $v '70\n1' unknown; shape $v '0\n0' unknown; shape $v '1\n1' unknown; shape $v '64\n0' unknown
    shape $v '0\nyes' unknown; shape $v '0\n1\n1' unknown; shape $v '0\n 1' unknown; shape $v '0\n01' unknown
    shape $v '70\nError calling isSlotBootable()' unknown
    rm -f "$T/hal/reply_$v"
    expect_reboot ""; expect_no_bc set-active-boot-slot; expect_no_bc mark-boot-successful; expect_prop sys.z9x.ota none
    end
  done
}
case_hb_mark_readback() {   # an accepted mark counts only when the slot reads back as marked
  begin hb_mark_readback_unknown
  ota_to_b; boot; touch "$T/hal/err_is-slot-marked-successful"
  complete
  expect_bc mark-boot-successful; expect_reboot ""; expect_prop sys.z9x.ota unhealthy
  expect_prop sys.z9x.ota.why "healthy, but markBootSuccessful failed (no read-back) (boot HAL)"
  expect_file lumen_ota/attempts "1 _b"; expect_nofile lumen_ota/no_rollback; expect_log "cannot be read back"
  rm -f "$T/hal/err_is-slot-marked-successful"
  boot                                                           # the mark had taken: confirmed, never rolled back
  expect_prop sys.z9x.ota merging; expect_nofile lumen_ota/attempts; expect_word1 lumen_ota/no_rollback _b
  expect_no_bc set-active-boot-slot
  end
  begin hb_mark_readback_no_counts_on   # the mark never takes: the attempts go on and the rollback still comes
  ota_to_b; touch "$T/hal/ignore_mark-boot-successful"
  for i in 1 2 3; do
    boot; complete
    expect_reboot ""; expect_prop sys.z9x.ota unhealthy; expect_file lumen_ota/attempts "$i _b"
    expect_nofile lumen_ota/no_rollback
  done
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota.attempt 4; expect_slots "a=15/6/1 b=14/2/0 merge=2"
  end
  aidl_begin hb_mark_readback_aidl   # the hal=aidl route: the same rule
  aidl_boot; touch "$T/hal/exc_7"
  complete
  grep -qx 8 "$T/hal.log" || fail "no AIDL call 8"
  expect_prop sys.z9x.ota unhealthy; expect_prop sys.z9x.ota.why "healthy, but markBootSuccessful failed (no read-back) (boot HAL)"
  expect_file lumen_ota/attempts "1 _b"; expect_nofile lumen_ota/no_rollback
  end
}
case_hb_state_files_whole() {   # a power cut before the rename leaves a temp file: count deletes it, the old file stays
  begin hb_state_files_whole
  ota_to_b; printf '2 _b\n' > "$R/metadata/lumen_ota/attempts"; : > "$R/metadata/lumen_ota/attempts.4242.tmp"
  printf '_b x' > "$R/metadata/lumen_ota/no_rollback.4242.tmp"; : > "$R/metadata/lumen_ota/attempts.tmp"
  boot
  expect_prop sys.z9x.ota.attempt 3; expect_file lumen_ota/attempts "3 _b"; expect_nofile lumen_ota/no_rollback
  expect_nofile lumen_ota/no_rollback.4242.tmp; expect_nofile lumen_ota/attempts.tmp
  boot                                                           # attempt 4: rolled back, rolledback written whole
  expect_reboot reboot,z9x-ota-rollback
  grep -qx '[0-9]\{8\}-[0-9]\{6\} _b boot attempts' "$R/metadata/lumen_ota/rolledback" || fail "rolledback: $(cat "$R/metadata/lumen_ota/rolledback" 2>/dev/null)"
  end
}
case_hb_verifier_before_decide() {   # init may run zygote-start before z9x_ota_decide finished
  begin hb_verifier_before_decide
  ota_to_b; twin twin-set b=15/1/1 merge=3
  new_boot; bootloader; count_only; gate verifier; gate decide
  expect_uv "not run"; expect_prop sys.z9x.ota merging; expect_reboot ""
  end
}
case_hb_partitions_untouched() {   # every run checks the fake root; here also the misc writes are the HAL's only
  begin hb_partitions_untouched
  ota_to_b; for i in 1 2 3 4; do boot; done; boot; complete; gate info
  expect_prop sys.z9x.ota rolledback
  eq "HAL writes" "$(grep -c -E '^bootctl (set-active-boot-slot|mark-boot-successful|set-slot-as-unbootable|set-snapshot-merge-status)' "$T/hal.log")" 1
  end
}

PORTED="syntax gate_normal_boot gate_info_vbmeta gate_kill_switch gate_ota_vold_marks_first
gate_ota_vold_marks_unhealthy gate_ota_unmarked_healthy_mark gate_ota_unmarked_unhealthy_rollback
gate_ota_4th_attempt_rollback gate_test_fail_drill gate_mark_request gate_restart_during_merge
gate_deadline_rollback gate_deadline_boot_completed gate_hal_down_no_rollback gate_hal_exception
gate_hal_multiline gate_hal_garbled gate_hal_hang gate_setactive_fails gate_setactive_ignored
gate_setactive_odd_reply gate_other_not_bootable gate_cancelled_no_rollback gate_stale_no_rollback gate_mark_fails_no_reboot gate_deadline_hal_down_stuck gate_bootctl_fallback
gate_bootctl_errors gate_rolledback_only_when_real gate_bootloader_fallback gate_legacy_attempts
gate_rollback_did_not_take rescue_123_fastboot rescue_reset_on_completed rescue_normal_boots_never
rescue_slot_change rescue_ota_grace_bound rescue_ota_marked_acts rescue_kill_switch_prop
rescue_kill_switch_file rescue_gate_off_no_wait rescue_unwritable rescue_corrupt_counter
rescue_log_rotation e2e_ota_rollback_then_normal e2e_source_slot_also_broken"
HB="hb_count_zero_hal hb_normal_boot_zero_bootctl hb_probe_request hb_probe_bad hb_marked_high_attempts
hb_conf_missing hb_conf_invalid hb_start_count_rollback hb_xgimi_middle_missing hb_build_id_cache
hb_bootctl_replies hb_bootctl_shapes hb_mark_readback hb_state_files_whole hb_verifier_before_decide
hb_partitions_untouched"

echo "lumen_ota boot tests (scripts under: $SH)"
for c in $PORTED; do
  case "$c" in "$ONLY"*) "case_$c" ;; esac
done
P1=$PASS; F1=$FAIL
for c in $HB; do
  case "$c" in "$ONLY"*) "case_$c" ;; esac
done
echo "ported Z9X cases: passed $P1, failed $F1; HB cases: passed $((PASS - P1)), failed $((FAIL - F1))"
echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
