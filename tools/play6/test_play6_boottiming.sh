#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Host test of overlay/play6/play6_boottiming.sh (DIAG images only) on a fake root: /proc, /sys, /dev and the
# boot logger's stick are folders in a temp dir; getprop and sleep are stubs. The sleep stub moves the fake
# uptime on and plays a scripted boot (stages, a module, a pwm chip, vendor services, boot_completed).
# Cases: the record lands in the stick folder of THIS boot (found by ro.boottime.init), not in an older one;
# no matching folder -> timing_<boot_id>.txt after 30 s; no stick -> only /dev; a boot that never completes
# stops at 870 s; nothing is written anywhere else. Run: bash tools/play6/test_play6_boottiming.sh
# With the Android shell and tools: P6T_SH=<host mksh> and toybox applets first in PATH (done 2026-10-31 on the
# builder: mksh R59 from external/mksh, prebuilts/build-tools toybox).
set -u
G=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT=$G/overlay/play6/play6_boottiming.sh
fails=0; n=0
check() { n=$((n + 1)); if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }

setup() {  # $1 = stick: match | other | none ; $2 = complete: 1 | 0
  R=$(mktemp -d)
  mkdir -p "$R/proc/sys/kernel/random" "$R/sys/module/ext4" "$R/sys/module/ehci_hcd" "$R/sys/class/pwm" \
    "$R/dev" "$R/stick" "$R/bin"
  echo "5.00 1.00" > "$R/proc/uptime"
  echo "1c2d3e4f-0000-1111-2222-333344445555" > "$R/proc/sys/kernel/random/boot_id"
  printf '    12.877 s - init:on early-init\tinit\n    13.006 s - init:on init\tinit\n' > "$R/proc/boottime"
  cat > "$R/props" <<'EOF'
[ro.boottime.init]: [1024793830]
[ro.boottime.zygote]: [9100000000]
[ro.boottime.gmpf_main]: [5973051561]
[ro.lumen.build_id]: [lumen-play6-1.0.7-20261024-aic-diag1]
[init.svc.ueventd]: [running]
[init.svc.logd]: [running]
EOF
  if [ "$1" != none ]; then
    mkdir -p "$R/stick/lumen_os_diag_logs/early/boot_1"
    echo "[ro.boottime.init]: [999]" > "$R/stick/lumen_os_diag_logs/early/boot_1/props.txt"
  fi
  printf '%s\n' "$1" > "$R/mode"; printf '%s\n' "$2" > "$R/complete"
  cat > "$R/bin/getprop" <<EOF
#!/bin/bash
if [ \$# = 0 ]; then cat "$R/props"; else sed -n "s/^\\[\$1\\]: \\[\\(.*\\)\\]\$/\\1/p" "$R/props" | tail -n 1; fi
EOF
  cat > "$R/bin/sync" <<'EOF'
#!/bin/bash
exit 0
EOF
  # sleep: uptime += arg, then the scripted boot at fixed uptimes (each event once)
  cat > "$R/bin/sleep" <<EOF
#!/bin/bash
R="$R"
read -r u _ < "\$R/proc/uptime"
u=\$(awk -v a="\$u" -v b="\$1" 'BEGIN{printf "%.2f", a + b}')
echo "\$u 1.00" > "\$R/proc/uptime"
ui=\${u%.*}
env_run() { P6T_PROC="\$R/proc" P6T_SYS="\$R/sys" P6T_DIR="\$R/dev/lumen_p6_timing" P6T_STICK="\$R/stick" ${P6T_SH:-/bin/sh} "$SCRIPT" "\$@"; }
once() { [ -e "\$R/done_\$1" ] && return 1; [ "\$ui" -ge "\$2" ] || return 1; touch "\$R/done_\$1"; }
setp() { grep -v "^\\[\$1\\]" "\$R/props" > "\$R/props.n"; echo "[\$1]: [\$2]" >> "\$R/props.n"; mv "\$R/props.n" "\$R/props"; }
if once pfd 8; then printf '    17.607 s - init:on post-fs-data\tinit\n' >> "\$R/proc/boottime"; env_run mark post-fs-data; fi
if once zyg 9; then env_run mark zygote-start; setp init.svc.zygote running; fi
if once mod 12; then mkdir -p "\$R/sys/module/mik" "\$R/sys/class/pwm/pwmchip0"; setp init.svc.gmpf_main running; setp init.svc.gmpfHw running; fi
if once eb 14; then printf '    19.776 s - init:on early-boot\tinit\n' >> "\$R/proc/boottime"; env_run mark early-boot; fi
if once stick 16; then
  case "\$(cat "\$R/mode")" in
    match) mkdir -p "\$R/stick/lumen_os_diag_logs/early/boot_2"; cp "\$R/props" "\$R/stick/lumen_os_diag_logs/early/boot_2/props.txt" ;;
    other) mkdir -p "\$R/stick/lumen_os_diag_logs/early/boot_2"; echo "[ro.boottime.init]: [777]" > "\$R/stick/lumen_os_diag_logs/early/boot_2/props.txt" ;;
  esac
fi
if once boot 15; then env_run mark boot; fi
if [ "\$(cat "\$R/complete")" = 1 ] && once done 30; then setp sys.boot_completed 1; env_run mark boot_completed; fi
exit 0
EOF
  chmod +x "$R/bin/"*
  find "$R" -type f -o -type d | sort > "$R/before.txt"
}
run_watch() {
  PATH="$R/bin:$PATH" P6T_SLEEP="$R/bin/sleep" P6T_PROC="$R/proc" P6T_SYS="$R/sys" P6T_DIR="$R/dev/lumen_p6_timing" P6T_STICK="$R/stick" \
    ${P6T_SH:-/bin/sh} "$SCRIPT" watch > "$R/out.txt" 2>&1
}
new_files() {  # files created by the script outside its two places and the stubs' own bookkeeping
  find "$R" -type f -o -type d | sort | comm -13 "$R/before.txt" - \
    | grep -v -e "^$R/dev/lumen_p6_timing" -e "^$R/stick/lumen_os_diag_logs" -e "^$R/done_" -e "^$R/props.n" \
         -e "^$R/out.txt$" -e "^$R/sys/module/mik$" -e "^$R/sys/class/pwm/pwmchip0$"
}

echo "== case 1: stick with an older boot and this boot's folder"
setup match 1; run_watch
F=$R/stick/lumen_os_diag_logs/early/boot_2/timing.txt
check "no output on stdout/stderr" '[ ! -s "$R/out.txt" ]'
check "timing.txt in this boot's folder" '[ -s "$F" ]'
check "nothing in the older boot's folder" '[ ! -e "$R/stick/lumen_os_diag_logs/early/boot_1/timing.txt" ]'
check "summary stages" 'grep -q "^stages: post-fs-data=8.00 zygote-start=9.00 early-boot=14.00 boot=15.00 boot_completed=30.00$" "$F"'
check "gmpf_main first running at 12" 'grep -q "^first running: gmpf_main=12.00 gmpfHw=12.00 " "$F"'
check "services never seen are -" 'grep -q " trapezoidServer=- " "$F"'
check "zygote first running at 9" 'grep -q " zygote=9.00 " "$F"'
check "new pwm chip with its uptime" 'grep -q "^12.00 pwm: pwmchip0$" "$F"'
check "first new pwm chip line" 'grep -q "^first new pwm chip: 12.00 pwm: pwmchip0$" "$F"'
check "new module with its uptime" 'grep -q "^12.00 module: mik$" "$F"'
check "modules at start listed once" '[ "$(grep -c "modules at start: ehci_hcd ext4$" "$F")" = 1 ]'
check "pwm at start none" 'grep -q "pwm at start: none$" "$F"'
check "vendor boottime line seen" 'grep -q "^8.00 boottime: *17.607 s - init:on post-fs-data" "$F"'
check "boot_completed prop change" 'grep -q "^30.00 prop: \[sys.boot_completed\]: \[1\]$" "$F"'
check "unchanged props logged once" '[ "$(grep -c "prop: \[init.svc.logd\]: \[running\]" "$F")" = 1 ]'
check "mark keeps vendor clock" 'grep -q "^early-boot uptime=14.00 boottime_last=\[.*19.776 s - init:on early-boot" "$F"'
check "ro.boottime sorted, ns" 'grep -A3 "^## ro.boottime" "$F" | sed -n 2p | grep -q "^1024793830 init$"'
check "watch ends 45 s after boot_completed" 'grep -qE "^7[5-9]\.[0-9]+ watch end$" "$F"'
check "same record in /dev" 'cmp -s "$F" "$R/dev/lumen_p6_timing/timing.txt"'
check "build id in header" 'grep -q "build=lumen-play6-1.0.7-20261024-aic-diag1 \[ro.boottime.init\]: \[1024793830\]" "$F"'
check "no stray files" '[ -z "$(new_files)" ]'
check "no temp file left on stick" '[ -z "$(find "$R/stick" -name "*.new")" ]'
rm -rf "$R"

echo "== case 2: stick, but no folder of this boot (earlylog full or failed)"
setup other 1; run_watch
check "fallback file after 30 s" '[ -s "$R/stick/lumen_os_diag_logs/timing_1c2d3e4f-0000-1111-2222-333344445555.txt" ]'
check "no timing.txt in other boots" '[ -z "$(find "$R/stick/lumen_os_diag_logs/early" -name timing.txt)" ]'
check "no stray files" '[ -z "$(new_files)" ]'
rm -rf "$R"

echo "== case 3: no stick"
setup none 1; run_watch
check "record only in /dev" '[ -s "$R/dev/lumen_p6_timing/timing.txt" ] && [ -z "$(find "$R/stick" -type f)" ]'
check "no stray files" '[ -z "$(new_files)" ]'
rm -rf "$R"

echo "== case 4: the boot never completes"
setup match 0; run_watch
F=$R/stick/lumen_os_diag_logs/early/boot_2/timing.txt
check "stops at 870 s" 'grep -qE "^87[0-9]\.[0-9]+ watch end$" "$F"'
check "boot_completed is -" 'grep -q " boot_completed=-$" "$F"'
check "no stray files" '[ -z "$(new_files)" ]'
rm -rf "$R"

echo "== mark alone, no /proc/boottime"
R=$(mktemp -d); mkdir -p "$R/proc"; echo "7.25 1.00" > "$R/proc/uptime"
P6T_PROC="$R/proc" P6T_DIR="$R/t" ${P6T_SH:-/bin/sh} "$SCRIPT" mark post-fs-data
check "mark line" '[ "$(cat "$R/t/stages.txt")" = "post-fs-data uptime=7.25 boottime_last=[]" ]'
P6T_PROC="$R/proc" P6T_DIR="$R/t" ${P6T_SH:-/bin/sh} "$SCRIPT" bogus
check "unknown mode does nothing" '[ "$(wc -l < "$R/t/stages.txt" | tr -d " ")" = 1 ]'
rm -rf "$R"

echo "$((n - fails))/$n passed"
[ $fails = 0 ]
