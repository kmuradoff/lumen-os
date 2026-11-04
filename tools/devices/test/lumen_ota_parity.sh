#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Parity test: the Z9X gate scenarios of tools/ota/test/run.sh, each run twice from the same start, once against
# the Z9X gate tools/ota/image/z9x_ota.sh (unchanged, read only) and once against the neutral fork
# tools/devices/ota/image/lumen_ota.sh with the Z9X data (ota_gate.conf hal=aidl, health=zygote surfaceflinger
# gmpf_main, lamp=gmpf_main), through the same stubs (tools/ota/test/stubs: the AIDL boot HAL 'service call' in
# the shape measured on a Z9X, and its bootctl for the fallback cases). 
#   sh tools/devices/test/lumen_ota_parity.sh            under sh, dash and ksh (those present)
#   SH=dash sh tools/devices/test/lumen_ota_parity.sh    only under that shell
#   sh tools/devices/test/lumen_ota_parity.sh deadline_  only the scenarios whose name starts with this
# After every step both runs must leave the same published properties (sys.z9x.ota, .attempt, .why, sys.z9x.slot,
# sys.powerctl, the vbmeta flags), the same state files (attempts, no_rollback, rolledback without its date; the
# Z9X in /metadata/z9x_ota, the fork in /metadata/lumen_ota), the same HAL state (active, marked0, marked1), the
# same state-changing HAL calls (markBootSuccessful, setActiveBootSlot, bootctl mark / set-active) in the same
# order, the same update_verifier runs and the same sleeps. The fork reads the HAL more (z9x_ota_decide's probe)
# and publishes sys.z9x.ota.hal / .marked / .merge / .active on top: those are not compared. The Z9X boot rescue
# (z9x_rescue.sh) is not run: the projectors with the fork have none (design decision). A start is count, then (the
# fork only, as its rc trigger does) decide while pending, then verifier; ro.z9x.* settings of a scenario are
# given to the fork as ro.lumen.*. Exit status 0 only when every scenario gives the same trace.
# Deliberate differences (DIFF below, each with its reason): the step is still run on both and compared, only with
# the input each gate is written for.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../.." && pwd)
ZIMG=$G/tools/ota/image
LIMG=$G/tools/devices/ota/image
ZSTUBS=$G/tools/ota/test/stubs
LSTUBS=$HERE/stubs
ONLY=${1-}

if [ -z "${SH-}" ]; then
  rc=0
  for s in sh dash ksh; do
    command -v "$s" >/dev/null 2>&1 || { echo "($s not found, skipped)"; continue; }
    SH=$s sh "$0" "$@" || rc=1
  done
  exit $rc
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/lumen_ota_parity.XXXXXX") || exit 1
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT
BIN=$WORK/bin
mkdir -p "$BIN"
for f in getprop setprop service log timeout sleep pidof; do
  cp "$ZSTUBS/$f" "$BIN/$f" && chmod 755 "$BIN/$f"
done
PASS=0; FAIL=0; IMPL=; T=; R=; SD=

# ------------------------------------------------------------------ one run of a scenario
begin() {  # impl: fresh root as tools/ota/test/run.sh begin(): slot B runs, both slots marked, a healthy system
  IMPL=$1; T=$WORK/$SCN/$1; R=$T/root
  if [ "$IMPL" = z9x ]; then SD=$R/metadata/z9x_ota; else SD=$R/metadata/lumen_ota; fi
  mkdir -p "$T/hal" "$R/metadata/ota" "$SD" "$R/data/system" "$R/system/bin" "$R/system/etc/lumen" "$R/proc" \
    "$R/dev/block/by-name"
  echo "12.34 5.67" > "$R/proc/uptime"
  printf '%s\n' ro.boot.slot_suffix=_b ro.z9x.build_id=lumen-1.0.1-20261009 ro.lumen.build_id=lumen-1.0.1-20261009 \
    init.svc.zygote=running init.svc.surfaceflinger=running init.svc.gmpf_main=running \
    sys.system_server.start_count=1 sys.z9x.blobs=ok > "$T/props"
  printf '%s\n' hal=aidl 'health=zygote surfaceflinger gmpf_main' lamp=gmpf_main ns=ro.lumen max_attempts=3 \
    deadline=480 health_delay=90 start_count_max=4 > "$R/system/etc/lumen/ota_gate.conf"
  echo org.lumen.projector > "$T/procs"
  echo 1 > "$T/hal/current"; echo 1 > "$T/hal/active"; echo 0 > "$T/hal/merge"
  echo 1 > "$T/hal/marked0"; echo 1 > "$T/hal/marked1"
  echo 1 > "$T/hal/bootable0"; echo 1 > "$T/hal/bootable1"
  : > "$T/hal.log"; : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/out"; : > "$T/uv.log"; : > "$T/sleep.log"
  : > "$T/trace"; STEP=0
  printf '#!/bin/sh\necho "ran $*" >> "$Z9X_T/uv.log"\n' > "$R/system/bin/update_verifier"
  chmod 755 "$R/system/bin/update_verifier"
}
gate() {
  if [ "$IMPL" = z9x ]; then s=$ZIMG/z9x_ota.sh; else s=$LIMG/lumen_ota.sh; fi
  if [ "${SH##*/}" = ksh ]; then
    env Z9X_ROOT="$R" LUMEN_OTA_ROOT="$R" Z9X_T="$T" BOOTCTL_INNER="$ZSTUBS/bootctl" PATH="$BIN:$PATH" ksh -c 'builtin -d sleep; f=$1; shift; . "$f"' ksh "$s" "$@" >> "$T/out" 2>&1
  else env Z9X_ROOT="$R" LUMEN_OTA_ROOT="$R" Z9X_T="$T" BOOTCTL_INNER="$ZSTUBS/bootctl" PATH="$BIN:$PATH" $SH "$s" "$@" >> "$T/out" 2>&1; fi
  case "$1" in count|decide|verifier) ;; *) trace "gate $1" ;; esac   # a start is traced as one step
}
prop() { Z9X_T=$T "$BIN/getprop" "$1"; }
setp() { awk -v k="$1" 'index($0, k "=") != 1' "$T/props" > "$T/props.tmp"; printf '%s=%s\n' "$1" "$2" >> "$T/props.tmp"; mv "$T/props.tmp" "$T/props"; }
setro() {  # ro.z9x.<x> value: the Z9X name for the Z9X gate, ro.lumen.<x> for the fork
  setp "$1" "$2"; setp "ro.lumen.${1#ro.z9x.}" "$2"
}
rebooted() { [ -n "$(prop sys.powerctl)" ]; }
ota_to_b() { printf _a > "$R/metadata/ota/snapshot-boot"; echo 0 > "$T/hal/marked1"; echo 1 > "$T/hal/active"; echo 2 > "$T/hal/merge"; }
with_bootctl() {
  if [ "$IMPL" = z9x ]; then cp "$ZSTUBS/bootctl" "$R/system/bin/bootctl"
  # DIFF: the fork parses the real bootctl yes/no shape ("1" exit 0, "0" exit 70; a silent exit 70 is an error,
  # F1 of the HB review); the Z9X gate reads the silent shape of its own stub. Same state, same answers.
  else cp "$LSTUBS/bootctl_realshape" "$R/system/bin/bootctl"; fi
  chmod 755 "$R/system/bin/bootctl"
}
new_boot() {
  grep -v -e '^sys\.boot_completed=' -e '^sys\.z9x\.' -e '^sys\.powerctl=' "$T/props" > "$T/props.tmp"
  mv "$T/props.tmp" "$T/props"
  : > "$T/uv.log"
}
boot() {
  new_boot
  gate count
  if ! rebooted && [ "$IMPL" = lumen ] && [ "$(prop sys.z9x.ota)" = pending ]; then gate decide; fi
  rebooted || gate verifier
  trace boot
}
complete() { setp sys.boot_completed 1; [ "$(prop sys.z9x.ota)" = pending ] && gate health; trace complete; }
switch_slot() { setp ro.boot.slot_suffix "_$1"; if [ "$1" = a ]; then echo 0 > "$T/hal/current"; else echo 1 > "$T/hal/current"; fi; }
st() { printf '%s\n' "$2" > "$SD/$1"; }      # a state file of the gate under test
trace() {  # step: the comparable state after it
  STEP=$((STEP + 1))
  p=; for k in sys.z9x.ota sys.z9x.ota.attempt sys.z9x.ota.why sys.z9x.slot sys.powerctl sys.z9x.vbmeta_other.flags sys.z9x.vbmeta_cur.flags; do
    p="$p $k=$(prop $k)"
  done
  f=; for n in attempts no_rollback rolledback; do
    v=$(cat "$SD/$n" 2>/dev/null | tr '\n' ' ')
    [ "$n" = rolledback ] && v=$(printf '%s' "$v" | sed 's/^[0-9]\{8\}-[0-9]\{6\} /<date> /')
    f="$f $n=[$v]"
  done
  h="active=$(cat "$T/hal/active") marked0=$(cat "$T/hal/marked0") marked1=$(cat "$T/hal/marked1")"
  w=$(grep -E '^(8|9)( |$)|^bootctl (mark-boot-successful|set-active-boot-slot)' "$T/hal.log" | tr '\n' ',')
  u=$(grep -c . "$T/uv.log"); z=$(grep -c . "$T/sleep.log")
  echo "$STEP $1 |$p |$f | $h | writes=$w | uv=$u | sleeps=$z" >> "$T/trace"
}

# ------------------------------------------------------------------ the Z9X gate scenarios (tools/ota/test/run.sh)
sc_normal_boot() { boot; complete; gate health; gate watch; }
sc_info_vbmeta() {
  { printf AVB0; dd if=/dev/zero bs=1 count=116 2>/dev/null; printf '\000\000\000\002'; } > "$R/dev/block/by-name/vbmeta_a"
  printf 'XXXX' > "$R/dev/block/by-name/vbmeta_b"
  gate info
}
sc_kill_switch() { ota_to_b; setro ro.z9x.ota.gate 0; boot; }
sc_ota_vold_marks_first() { ota_to_b; boot; echo 1 > "$T/hal/marked1"; echo 3 > "$T/hal/merge"; complete; }
sc_ota_vold_marks_unhealthy() { ota_to_b; boot; echo 1 > "$T/hal/marked1"; echo 0 > "$T/hal/merge"; setp init.svc.gmpf_main stopped; complete; }
sc_ota_unmarked_healthy_mark() { ota_to_b; boot; complete; }
sc_ota_unmarked_unhealthy_rollback() { ota_to_b; boot; setp sys.system_server.start_count 3; complete; switch_slot a; boot; }
sc_ota_4th_attempt_rollback() { ota_to_b; boot; boot; boot; boot; switch_slot a; boot; complete; }
sc_test_fail_drill() { ota_to_b; setro ro.z9x.ota.test_fail 1; boot; }
sc_mark_request() { ota_to_b; boot; gate mark; }
sc_restart_during_merge() { ota_to_b; echo 1 > "$T/hal/marked1"; echo 3 > "$T/hal/merge"; boot; }
sc_deadline_rollback() { ota_to_b; boot; gate watch; }
sc_deadline_boot_completed() { ota_to_b; boot; setp sys.boot_completed 1; gate watch; }
sc_hal_down_no_rollback() { ota_to_b; touch "$T/hal/down"; boot; boot; boot; boot; }
fault_scn() { ota_to_b; touch "$T/hal/$1"; st attempts "3 _b"; boot; }
sc_hal_exception() { fault_scn exc_7; }
sc_hal_multiline() { fault_scn multi_7; }
sc_hal_garbled() { fault_scn garble_7; }
sc_hal_hang() { fault_scn hang; }
sc_setactive_fails() { fault_scn exc_9; }
sc_setactive_ignored() { fault_scn ignore_9; }
sc_setactive_odd_reply() { fault_scn odd_9; }
sc_other_not_bootable() { ota_to_b; echo 0 > "$T/hal/bootable0"; st attempts "3 _b"; boot; }
sc_cancelled_no_rollback() { ota_to_b; echo 4 > "$T/hal/merge"; st attempts "3 _b"; boot; }
sc_stale_no_rollback() { ota_to_b; st no_rollback "_b setActiveBootSlot(0) failed"; boot; boot; boot; boot; }
sc_mark_fails_no_reboot() { ota_to_b; touch "$T/hal/exc_8"; boot; complete; }
sc_deadline_hal_down_stuck() { ota_to_b; boot; touch "$T/hal/down"; gate watch; }
sc_bootctl_fallback() { ota_to_b; touch "$T/hal/down"; with_bootctl; boot; complete; }
sc_bootctl_fallback_rollback() { ota_to_b; touch "$T/hal/down"; with_bootctl; st attempts "3 _b"; boot; }
sc_bootctl_errors() { ota_to_b; touch "$T/hal/down" "$T/hal/bootctl_fail"; with_bootctl; st attempts "3 _b"; boot; }
sc_rolledback_only_when_real() { st rolledback "20261008-215339 _b mark failed"; st attempts 1; boot; }
sc_bootloader_fallback() { ota_to_b; st attempts "2 _b"; switch_slot a; printf _a > "$R/metadata/ota/snapshot-boot"; boot; }
sc_legacy_attempts() { st attempts 2; boot; }
sc_rollback_did_not_take() { ota_to_b; st attempts "4 _b"; st rolledback "20261009-101010 _b boot attempts"; boot; }
sc_e2e_ota_rollback_then_normal() { ota_to_b; boot; boot; boot; boot; switch_slot a; boot; complete; boot; }

SCENARIOS="normal_boot info_vbmeta kill_switch ota_vold_marks_first ota_vold_marks_unhealthy ota_unmarked_healthy_mark
ota_unmarked_unhealthy_rollback ota_4th_attempt_rollback test_fail_drill mark_request restart_during_merge
deadline_rollback deadline_boot_completed hal_down_no_rollback hal_exception hal_multiline hal_garbled hal_hang
setactive_fails setactive_ignored setactive_odd_reply other_not_bootable cancelled_no_rollback stale_no_rollback
mark_fails_no_reboot deadline_hal_down_stuck bootctl_fallback bootctl_fallback_rollback bootctl_errors
rolledback_only_when_real bootloader_fallback legacy_attempts rollback_did_not_take e2e_ota_rollback_then_normal"

echo "lumen_ota parity with z9x_ota.sh (scripts under: $SH)"
for SCN in $SCENARIOS; do
  case "$SCN" in "$ONLY"*) ;; *) continue ;; esac
  for i in z9x lumen; do begin $i; "sc_$SCN"; done
  bad=
  for i in z9x lumen; do
    [ ! -s "$WORK/$SCN/$i/out" ] || bad="$bad; $i printed: $(head -3 "$WORK/$SCN/$i/out" | tr '\n' ' ')"
  done
  grep -q 'sys\.z9x\.slot=_' "$WORK/$SCN/z9x/trace" 2>/dev/null || bad="$bad; the Z9X gate published nothing (stubs?)"
  if ! cmp -s "$WORK/$SCN/z9x/trace" "$WORK/$SCN/lumen/trace"; then
    bad="$bad; traces differ"
  fi
  if [ -z "$bad" ]; then PASS=$((PASS + 1)); echo "ok   $SCN"
  else
    FAIL=$((FAIL + 1)); echo "FAIL $SCN${bad}"
    diff "$WORK/$SCN/z9x/trace" "$WORK/$SCN/lumen/trace" | sed 's/^/     /'
  fi
done
echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
