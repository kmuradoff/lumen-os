#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Host test harness for the Lumen OS boot gate (tools/ota/image/z9x_ota.sh) and boot rescue
# (tools/ota/image/z9x_rescue.sh). POSIX sh, runs on the Mac:
#   sh tools/ota/test/run.sh              every case, the scripts under 'sh'
#   SH=dash sh tools/ota/test/run.sh      the scripts under another shell (dash: strict POSIX; SH=ksh:
#                                          ksh93, a relative of the device's mksh, builtin sleep dropped)
#   sh tools/ota/test/run.sh rescue_      only the cases whose name starts with this
# Each case gets a fresh temp dir: a fake root for the scripts (Z9X_ROOT: metadata/, data/system,
# system/bin, proc/uptime, dev/block/by-name) and the stub state (Z9X_T: props, hal/, logcat,
# setprop.log, hal.log). stubs/ stand in for getprop, setprop, service (the AIDL boot HAL, replies in
# the shape measured on a Z9X), log, timeout, sleep and pidof and come first on PATH; date, cat, dd, od,
# awk are the host's. A start is simulated in init's order: z9x_rescue.sh count ('on init') ->
# z9x_ota.sh count (post-fs-data) -> z9x_ota.sh verifier (zygote-start); a reboot request
# (sys.powerctl) ends it like init would. complete() = sys.boot_completed=1: z9x_rescue.sh ok, and
# z9x_ota.sh health while sys.z9x.ota=pending (the rc triggers). Every case also fails on any output of
# the scripts (stderr included) and on a property value the real setprop would refuse (> 91 bytes).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
IMG=$(cd "$HERE/../image" && pwd)
SH=${SH:-sh}
ONLY=${1-}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/z9x_boot_test.XXXXXX") || exit 1
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT
BIN=$WORK/bin
mkdir -p "$BIN"
for f in getprop setprop service log timeout sleep pidof; do
  cp "$HERE/stubs/$f" "$BIN/$f" && chmod 755 "$BIN/$f"
done
PASS=0; FAIL=0; CASE=; CFAIL=0; T=; R=

# ------------------------------------------------------------------ fixtures
begin() {  # case name: fresh root; slot B runs, both slots marked successful, a healthy system
  CASE=$1; CFAIL=0
  T=$WORK/$1; R=$T/root
  mkdir -p "$T/hal" "$R/metadata/ota" "$R/metadata/z9x_ota" "$R/data/system" "$R/system/bin" "$R/proc" \
    "$R/dev/block/by-name"
  echo "12.34 5.67" > "$R/proc/uptime"
  printf '%s\n' ro.boot.slot_suffix=_b ro.z9x.build_id=lumen-1.0.1-20261009 \
    init.svc.zygote=running init.svc.surfaceflinger=running init.svc.gmpf_main=running \
    sys.system_server.start_count=1 sys.z9x.blobs=ok > "$T/props"
  echo org.z9x.projector > "$T/procs"
  echo 1 > "$T/hal/current"; echo 1 > "$T/hal/active"; echo 0 > "$T/hal/merge"
  echo 1 > "$T/hal/marked0"; echo 1 > "$T/hal/marked1"
  echo 1 > "$T/hal/bootable0"; echo 1 > "$T/hal/bootable1"
  : > "$T/hal.log"; : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/out"; : > "$T/uv.log"
  printf '#!/bin/sh\necho "ran $*" >> "$Z9X_T/uv.log"\n' > "$R/system/bin/update_verifier"
  chmod 755 "$R/system/bin/update_verifier"
}
ota_to_b() {  # an update from slot A to B was installed and B is booting: B active, not marked
  printf _a > "$R/metadata/ota/snapshot-boot"
  echo 0 > "$T/hal/marked1"; echo 1 > "$T/hal/active"; echo 2 > "$T/hal/merge"
}
with_bootctl() { cp "$HERE/stubs/bootctl" "$R/system/bin/bootctl" && chmod 755 "$R/system/bin/bootctl"; }

run() {
  s=$1; shift
  if [ "${SH##*/}" = ksh ]; then   # ksh93 (mksh's relative): drop its builtin sleep, the stub must answer
    env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" ksh -c 'builtin -d sleep; f=$1; shift; . "$f"' ksh "$IMG/$s" "$@" >> "$T/out" 2>&1
  else env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$IMG/$s" "$@" >> "$T/out" 2>&1; fi
}
gate() { run z9x_ota.sh "$@"; }
resc() { run z9x_rescue.sh "$@"; }
prop() { Z9X_T=$T "$BIN/getprop" "$1"; }
setp() { awk -v k="$1" 'index($0, k "=") != 1' "$T/props" > "$T/props.tmp"; printf '%s=%s\n' "$1" "$2" >> "$T/props.tmp"; mv "$T/props.tmp" "$T/props"; }
rebooted() { [ -n "$(prop sys.powerctl)" ]; }

new_boot() {  # runtime properties of the last boot are gone; ro.* and init.svc.* stay
  grep -v -e '^sys\.boot_completed=' -e '^sys\.z9x\.' -e '^sys\.powerctl=' "$T/props" > "$T/props.tmp"
  mv "$T/props.tmp" "$T/props"
  : > "$T/uv.log"
}
boot() {  # one start, in init's order
  new_boot
  resc count; rebooted && return 0
  gate count; rebooted && return 0
  gate verifier
  return 0
}
complete() {  # sys.boot_completed=1 (z9x_rescue.rc / z9x_ota.rc triggers)
  setp sys.boot_completed 1
  resc ok
  [ "$(prop sys.z9x.ota)" = pending ] && gate health
  return 0
}
switch_slot() {  # a|b: the next start runs this slot
  setp ro.boot.slot_suffix "_$1"
  if [ "$1" = a ]; then echo 0 > "$T/hal/current"; else echo 1 > "$T/hal/current"; fi
}

# ------------------------------------------------------------------ assertions
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
eq() { [ "$2" = "$3" ] || fail "$1: got '$2', want '$3'"; }
expect_prop() { eq "$1" "$(prop "$1")" "$2"; }
expect_reboot() { eq "reboot request" "$(prop sys.powerctl)" "$1"; }     # '' = none
expect_file() { eq "$1" "$(cat "$R/metadata/$1" 2>/dev/null)" "$2"; }
expect_nofile() { [ ! -e "$R/metadata/$1" ] || fail "$1 exists: $(cat "$R/metadata/$1")"; }
expect_word1() { eq "first word of $1" "$(awk '{print $1; exit}' "$R/metadata/$1" 2>/dev/null)" "$2"; }
expect_hal() { grep -qx "$1" "$T/hal.log" || fail "no boot HAL call '$1' (calls: $(tr '\n' ';' < "$T/hal.log"))"; }
expect_no_hal() { if grep -q "^$1\$" "$T/hal.log" || grep -q "^$1 " "$T/hal.log"; then fail "boot HAL call $1 made"; fi; }
expect_no_hal_at_all() { [ ! -s "$T/hal.log" ] || fail "boot HAL called: $(tr '\n' ';' < "$T/hal.log")"; }
expect_log() { grep -q "$2" "$R/metadata/$1/log.txt" 2>/dev/null || fail "$1/log.txt lacks '$2'"; }
expect_uv() { if [ "$1" = ran ]; then [ -s "$T/uv.log" ] || fail "update_verifier not run"
              else [ ! -s "$T/uv.log" ] || fail "update_verifier ran"; fi; }
end() {
  [ ! -s "$T/out" ] || fail "script output: $(head -5 "$T/out" | tr '\n' ' ')"
  ! grep -q '^TOO_LONG' "$T/setprop.log" || fail "a property value longer than 91 bytes: $(grep '^TOO_LONG' "$T/setprop.log")"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else
    FAIL=$((FAIL + 1)); echo "FAIL $CASE"
    for f in z9x_ota z9x_rescue; do [ -f "$R/metadata/$f/log.txt" ] && sed "s/^/     $f| /" "$R/metadata/$f/log.txt"; done
  fi
}

# ------------------------------------------------------------------ gate: normal boots
case_gate_normal_boot() {
  begin gate_normal_boot
  boot
  expect_prop sys.z9x.ota none; expect_prop sys.z9x.slot _b; expect_reboot ""
  expect_uv ran; expect_no_hal_at_all
  expect_file z9x_rescue/count "1 _b"
  complete
  gate health; gate watch
  expect_prop sys.z9x.ota none; expect_reboot ""; expect_no_hal_at_all
  expect_file z9x_rescue/count "0 _b"
  expect_file ../data/system/z9x_pkgcache.build_id lumen-1.0.1-20261009
  end
}
case_gate_info_vbmeta() {
  begin gate_info_vbmeta
  { printf AVB0; dd if=/dev/zero bs=1 count=116 2>/dev/null; printf '\000\000\000\002'; } > "$R/dev/block/by-name/vbmeta_a"
  printf 'XXXX' > "$R/dev/block/by-name/vbmeta_b"
  gate info
  expect_prop sys.z9x.vbmeta_other.flags 2; expect_prop sys.z9x.vbmeta_cur.flags none; expect_no_hal_at_all
  end
}
case_gate_kill_switch() {
  begin gate_kill_switch
  ota_to_b; setp ro.z9x.ota.gate 0
  boot
  expect_prop sys.z9x.ota none; expect_uv ran; expect_no_hal_at_all; expect_nofile z9x_ota/attempts
  end
}

# ------------------------------------------------------------------ gate: an update slot
case_gate_ota_vold_marks_first() {
  begin gate_ota_vold_marks_first
  ota_to_b
  boot
  expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.attempt 1; expect_file z9x_ota/attempts "1 _b"
  expect_hal "7 1"; expect_uv "not run"; expect_reboot ""
  echo 1 > "$T/hal/marked1"; echo 3 > "$T/hal/merge"          # vold commits the checkpoint
  complete
  expect_prop sys.z9x.ota merging; expect_reboot ""; expect_nofile z9x_ota/attempts
  expect_word1 z9x_ota/no_rollback _b; expect_no_hal 8; expect_no_hal 9
  expect_log z9x_ota "marked successful (by vold at boot_completed)"; expect_log z9x_ota "health checks passed"
  grep -qx 90 "$T/sleep.log" || fail "no 90 s health delay"
  end
}
case_gate_ota_vold_marks_unhealthy() {
  begin gate_ota_vold_marks_unhealthy
  ota_to_b; boot
  echo 1 > "$T/hal/marked1"; echo 0 > "$T/hal/merge"; setp init.svc.gmpf_main stopped
  complete
  expect_prop sys.z9x.ota unhealthy; expect_prop sys.z9x.ota.why "gmpf_main (lamp/fans) not running"
  expect_reboot ""; expect_no_hal 9
  end
}
case_gate_ota_unmarked_healthy_mark() {
  begin gate_ota_unmarked_healthy_mark
  ota_to_b; boot; complete
  expect_hal 8; expect_prop sys.z9x.ota marked; expect_prop sys.z9x.ota.why ""; expect_reboot ""
  eq marked1 "$(cat "$T/hal/marked1")" 1; expect_nofile z9x_ota/attempts
  end
}
case_gate_ota_unmarked_unhealthy_rollback() {
  begin gate_ota_unmarked_unhealthy_rollback
  ota_to_b; boot
  setp sys.system_server.start_count 3
  complete
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota rollback; expect_hal "9 0"
  eq active "$(cat "$T/hal/active")" 0
  grep -q ' _b health: system_server restarted (3)$' "$R/metadata/z9x_ota/rolledback" || fail "rolledback: $(cat "$R/metadata/z9x_ota/rolledback")"
  switch_slot a; boot                                        # libsnapshot indicator still says _a
  expect_prop sys.z9x.ota rolledback; expect_nofile z9x_ota/rolledback; expect_nofile z9x_ota/attempts
  expect_file z9x_rescue/count "1 _a"
  end
}
case_gate_ota_4th_attempt_rollback() {
  begin gate_ota_4th_attempt_rollback
  ota_to_b
  for i in 1 2 3; do boot; expect_reboot ""; expect_prop sys.z9x.ota.attempt $i; done
  boot
  expect_log z9x_rescue "update slot, z9x_ota.sh rolls back first"
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota rollback; expect_hal "9 0"; expect_hal "6 0"
  expect_file z9x_rescue/count "4 _b"
  switch_slot a; boot
  expect_reboot ""; expect_prop sys.z9x.ota rolledback; expect_file z9x_rescue/count "1 _a"
  expect_log z9x_ota "booted _a after a failed update of _b: boot attempts"
  complete; expect_file z9x_rescue/count "0 _a"
  end
}
case_gate_test_fail_drill() {
  begin gate_test_fail_drill
  ota_to_b; setp ro.z9x.ota.test_fail 1
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota.attempt 1
  end
}
case_gate_mark_request() {
  begin gate_mark_request
  ota_to_b; boot
  gate mark
  expect_prop sys.z9x.ota marked; expect_hal 8; [ ! -s "$T/sleep.log" ] || fail "mark request slept"
  end
}
case_gate_restart_during_merge() {
  begin gate_restart_during_merge
  ota_to_b; echo 1 > "$T/hal/marked1"; echo 3 > "$T/hal/merge"
  boot
  expect_prop sys.z9x.ota merging; expect_nofile z9x_ota/attempts; expect_word1 z9x_ota/no_rollback _b
  expect_uv ran; expect_reboot ""
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
  expect_word1 z9x_ota/no_rollback _b
  expect_prop sys.z9x.ota.why "no rollback: cannot read whether slot _b is marked (boot HAL)"
  expect_log z9x_ota "boot HAL call 7 1 failed (rc 10): service: Service android.hardware.boot.IBootControl/default does not exist"
  boot                                                        # 5th start: the gate gave up
  expect_reboot reboot,fastboot; expect_file z9x_rescue/count "0 _b"
  expect_log z9x_rescue "RESCUE: 4 starts of _b in a row"
  end
}
hal_fault_case() {  # name fault-file: isSlotMarkedSuccessful answers badly -> unknown, never a rollback
  begin "$1"
  ota_to_b; touch "$T/hal/$2"
  printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending; expect_no_hal 9
  expect_log z9x_ota "boot HAL call 7 1 failed"; expect_word1 z9x_ota/no_rollback _b
  end
}
case_gate_hal_exception() { hal_fault_case gate_hal_exception exc_7; }
case_gate_hal_multiline() { hal_fault_case gate_hal_multiline multi_7; }
case_gate_hal_garbled() { hal_fault_case gate_hal_garbled garble_7; }
case_gate_hal_hang() { hal_fault_case gate_hal_hang hang; }
case_gate_setactive_fails() {
  begin gate_setactive_fails
  ota_to_b; touch "$T/hal/exc_9"; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending; expect_hal "9 0"
  expect_prop sys.z9x.ota.why "no rollback: setActiveBootSlot(0) failed"
  end
}
case_gate_setactive_ignored() {
  begin gate_setactive_ignored
  ota_to_b; touch "$T/hal/ignore_9"; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot ""; expect_hal 1; expect_log z9x_ota "active slot reads 1 after setActiveBootSlot(0)"
  end
}
case_gate_setactive_odd_reply() {   # the switch happened, the reply shape is not the measured one
  begin gate_setactive_odd_reply
  ota_to_b; touch "$T/hal/odd_9"; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot reboot,z9x-ota-rollback; eq active "$(cat "$T/hal/active")" 0
  expect_log z9x_ota "reply not understood, but the active slot reads 0"
  end
}
case_gate_other_not_bootable() {
  begin gate_other_not_bootable
  ota_to_b; echo 0 > "$T/hal/bootable0"; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot ""; expect_no_hal 9; expect_prop sys.z9x.ota.why "no rollback: slot _a is not bootable"
  end
}
case_gate_cancelled_no_rollback() {   # 'lumen-install rescue' cancelled the update and re-flashed system
  begin gate_cancelled_no_rollback
  ota_to_b; echo 4 > "$T/hal/merge"; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot ""; expect_no_hal 9; expect_prop sys.z9x.ota pending
  expect_prop sys.z9x.ota.why "no rollback: the update was cancelled (system written again)"
  expect_word1 z9x_ota/no_rollback _b
  end
}
case_gate_stale_no_rollback() {   # left by an earlier update of this slot (1.0.0 ran in between)
  begin gate_stale_no_rollback
  ota_to_b; echo "_b setActiveBootSlot(0) failed" > "$R/metadata/z9x_ota/no_rollback"
  boot
  expect_nofile z9x_ota/no_rollback; expect_log z9x_ota "no_rollback of an earlier update of _b removed"
  for i in 2 3; do boot; expect_reboot ""; done
  boot
  expect_reboot reboot,z9x-ota-rollback; expect_prop sys.z9x.ota.attempt 4
  end
}
case_gate_mark_fails_no_reboot() {   # 1.0.0 on 2026-10-08: the mark failed -> needless "rollback"
  begin gate_mark_fails_no_reboot
  ota_to_b; touch "$T/hal/exc_8"
  boot; complete
  expect_reboot ""; expect_no_hal 9; expect_prop sys.z9x.ota unhealthy
  expect_prop sys.z9x.ota.why "healthy, but markBootSuccessful failed (boot HAL)"
  end
}
case_gate_deadline_hal_down_stuck() {
  begin gate_deadline_hal_down_stuck
  ota_to_b; boot; touch "$T/hal/down"
  gate watch
  expect_reboot reboot,z9x-ota-stuck; expect_word1 z9x_ota/no_rollback _b
  end
}
case_gate_bootctl_fallback() {
  begin gate_bootctl_fallback
  ota_to_b; touch "$T/hal/down"; with_bootctl
  boot
  expect_prop sys.z9x.ota pending; grep -q '^bootctl is-slot-marked-successful 1$' "$T/hal.log" || fail "no bootctl fallback"
  complete
  expect_prop sys.z9x.ota marked; eq marked1 "$(cat "$T/hal/marked1")" 1
  end
  begin gate_bootctl_fallback_rollback
  ota_to_b; touch "$T/hal/down"; with_bootctl; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot reboot,z9x-ota-rollback; eq active "$(cat "$T/hal/active")" 0
  end
}
case_gate_bootctl_errors() {
  begin gate_bootctl_errors
  ota_to_b; touch "$T/hal/down" "$T/hal/bootctl_fail"; with_bootctl; printf '3 _b\n' > "$R/metadata/z9x_ota/attempts"
  boot
  expect_reboot ""; expect_prop sys.z9x.ota pending
  expect_log z9x_ota "bootctl is-slot-marked-successful 1 failed (rc 70)"
  end
}

# ------------------------------------------------------------------ gate: what the updater is told after a restart
case_gate_rolledback_only_when_real() {   # 1.0.0 published 'rolledback' on the new slot
  begin gate_rolledback_only_when_real
  mkdir -p "$R/metadata/z9x_ota"
  echo "20261008-215339 _b mark failed" > "$R/metadata/z9x_ota/rolledback"   # the file 1.0.0 left
  echo "1" > "$R/metadata/z9x_ota/attempts"                                  # 1.0.0 format
  boot                                                       # slot _b, merge done, no indicator
  expect_prop sys.z9x.ota none; expect_nofile z9x_ota/rolledback; expect_nofile z9x_ota/attempts
  expect_log z9x_ota "nothing was rolled back"; expect_no_hal_at_all
  end
}
case_gate_bootloader_fallback() {
  begin gate_bootloader_fallback
  ota_to_b; printf '2 _b\n' > "$R/metadata/z9x_ota/attempts"
  switch_slot a; printf _a > "$R/metadata/ota/snapshot-boot"
  boot
  expect_prop sys.z9x.ota rolledback; expect_log z9x_ota "bootloader fallback after 2 attempt(s)"
  end
}
case_gate_legacy_attempts() {
  begin gate_legacy_attempts
  mkdir -p "$R/metadata/z9x_ota"; echo 2 > "$R/metadata/z9x_ota/attempts"
  boot
  expect_prop sys.z9x.ota none; expect_nofile z9x_ota/attempts
  end
}
case_gate_rollback_did_not_take() {
  begin gate_rollback_did_not_take
  ota_to_b; printf '4 _b\n' > "$R/metadata/z9x_ota/attempts"
  echo "20261009-101010 _b boot attempts" > "$R/metadata/z9x_ota/rolledback"
  boot
  expect_reboot ""; expect_no_hal 9; expect_prop sys.z9x.ota pending; expect_prop sys.z9x.ota.attempt 5
  expect_file z9x_ota/no_rollback "_b rollback to _a did not take effect"; expect_nofile z9x_ota/rolledback
  expect_log z9x_ota "the boot rescue counter decides"
  end
}

# ------------------------------------------------------------------ rescue
case_rescue_123_fastboot() {
  begin rescue_123_fastboot
  for i in 1 2 3; do boot; expect_reboot ""; expect_file z9x_rescue/count "$i _b"; done
  boot
  expect_reboot reboot,fastboot; expect_file z9x_rescue/count "0 _b"
  expect_log z9x_rescue "RESCUE: 3 starts of _b in a row did not complete boot"
  grep -q '^z9x_rescue: RESCUE' "$T/logcat" || fail "no logcat line"
  boot                                                        # fastbootd left by itself
  expect_reboot ""; expect_file z9x_rescue/count "1 _b"
  end
}
case_rescue_reset_on_completed() {
  begin rescue_reset_on_completed
  boot; boot; complete
  expect_file z9x_rescue/count "0 _b"; expect_log z9x_rescue "boot completed on _b (start 2)"
  for i in 1 2 3; do boot; expect_reboot ""; done
  complete; boot; expect_reboot ""
  end
}
case_rescue_normal_boots_never() {
  begin rescue_normal_boots_never
  i=0
  while [ $i -lt 10 ]; do boot; expect_reboot ""; complete; i=$((i + 1)); done
  expect_file z9x_rescue/count "0 _b"
  end
}
case_rescue_slot_change() {
  begin rescue_slot_change
  mkdir -p "$R/metadata/z9x_rescue"; echo "3 _a" > "$R/metadata/z9x_rescue/count"
  boot
  expect_reboot ""; expect_file z9x_rescue/count "1 _b"; expect_log z9x_rescue "slot _a -> _b: count starts over (was 3)"
  end
}
case_rescue_ota_grace_bound() {   # an update slot that dies before post-fs-data: the gate never runs
  begin rescue_ota_grace_bound
  ota_to_b
  for i in 1 2 3 4 5 6; do new_boot; resc count; expect_reboot ""; done
  expect_file z9x_rescue/count "6 _b"
  new_boot; resc count
  expect_reboot reboot,fastboot; expect_file z9x_rescue/count "0 _b"
  end
}
case_rescue_ota_marked_acts() {   # the update slot is marked (merge running) and fails: no waiting
  begin rescue_ota_marked_acts
  ota_to_b; echo 1 > "$T/hal/marked1"; echo 3 > "$T/hal/merge"
  for i in 1 2 3; do boot; expect_reboot ""; done
  expect_prop sys.z9x.ota merging
  boot
  expect_reboot reboot,fastboot
  end
}
case_rescue_kill_switch_prop() {
  begin rescue_kill_switch_prop
  setp ro.z9x.rescue 0
  for i in 1 2 3 4 5; do boot; expect_reboot ""; done
  expect_file z9x_rescue/count "5 _b"; expect_log z9x_rescue "rescue is off"
  end
}
case_rescue_kill_switch_file() {
  begin rescue_kill_switch_file
  mkdir -p "$R/metadata/z9x_rescue"; : > "$R/metadata/z9x_rescue/off"
  for i in 1 2 3 4; do boot; expect_reboot ""; done
  end
}
case_rescue_gate_off_no_wait() {
  begin rescue_gate_off_no_wait
  ota_to_b; setp ro.z9x.ota.gate 0
  for i in 1 2 3; do boot; expect_reboot ""; done
  boot; expect_reboot reboot,fastboot; expect_no_hal_at_all
  end
}
case_rescue_unwritable() {
  begin rescue_unwritable
  mkdir -p "$R/metadata/z9x_rescue"; echo "3 _b" > "$R/metadata/z9x_rescue/count"
  chmod 444 "$R/metadata/z9x_rescue/count"; chmod 555 "$R/metadata/z9x_rescue"
  if [ -w "$R/metadata/z9x_rescue" ]; then echo "     (skipped: running as root)"
  else
    new_boot; resc count
    expect_reboot ""; grep -q 'cannot be reset: no reboot' "$T/logcat" || fail "no 'cannot be reset' line"
  fi
  chmod 755 "$R/metadata/z9x_rescue"; chmod 644 "$R/metadata/z9x_rescue/count"
  end
}
case_rescue_corrupt_counter() {
  begin rescue_corrupt_counter
  mkdir -p "$R/metadata/z9x_rescue"; printf 'x\001y\n' > "$R/metadata/z9x_rescue/count"
  boot
  expect_reboot ""; expect_file z9x_rescue/count "1 _b"
  end
  begin rescue_missing_counter_ok
  resc ok
  expect_file z9x_rescue/count "0 _b"
  end
}
case_rescue_log_rotation() {
  begin rescue_log_rotation
  mkdir -p "$R/metadata/z9x_rescue"
  dd if=/dev/zero bs=1024 count=70 2>/dev/null | tr '\0' 'x' > "$R/metadata/z9x_rescue/log.txt"
  boot
  [ -f "$R/metadata/z9x_rescue/log.old" ] || fail "log.txt not rotated"
  eq "new log lines" "$(grep -c . "$R/metadata/z9x_rescue/log.txt")" 1
  end
}

# ------------------------------------------------------------------ both together
case_e2e_ota_rollback_then_normal() {
  begin e2e_ota_rollback_then_normal
  ota_to_b
  for i in 1 2 3 4; do boot; done                             # 4th: gate rolls back
  expect_reboot reboot,z9x-ota-rollback
  switch_slot a
  boot; expect_reboot ""; expect_prop sys.z9x.ota rolledback
  complete; expect_file z9x_rescue/count "0 _a"
  boot; expect_prop sys.z9x.ota none; expect_reboot ""      # the indicator still names _a: not an update slot
  end
}
case_e2e_source_slot_also_broken() {   # rollback worked, but the old slot loops as well
  begin e2e_source_slot_also_broken
  ota_to_b
  for i in 1 2 3 4; do boot; done
  switch_slot a
  for i in 1 2 3; do boot; expect_reboot ""; done
  boot; expect_reboot reboot,fastboot
  end
}

# ------------------------------------------------------------------ static checks
case_syntax() {
  begin syntax
  for s in z9x_ota.sh z9x_rescue.sh; do
    sh -n "$IMG/$s" || fail "sh -n $s"
    if command -v dash >/dev/null 2>&1; then dash -n "$IMG/$s" || fail "dash -n $s"; fi
    # the device shell is mksh, a ksh: ksh93 is stricter about patterns ('(' in ${var#...} opens a group)
    if command -v ksh >/dev/null 2>&1; then ksh -n "$IMG/$s" || fail "ksh -n $s"; fi
    if grep -nE "<<-?[[:space:]]*['\"]?[A-Za-z_]" "$IMG/$s" | grep -v '^[0-9]*:#' | grep -q .; then fail "$s uses a here-document (mksh needs a writable TMPDIR, not there at 'on init')"; fi
  done
  grep -q '^    exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_rescue.sh count$' "$IMG/z9x_rescue.rc" || fail "z9x_rescue.rc: exec line"
  grep -q '^on init$' "$IMG/z9x_rescue.rc" || fail "z9x_rescue.rc: on init"
  end
}

CASES="syntax gate_normal_boot gate_info_vbmeta gate_kill_switch gate_ota_vold_marks_first
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

echo "z9x boot tests (scripts under: $SH)"
for c in $CASES; do
  case "$c" in "$ONLY"*) "case_$c" ;; esac
done
echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
