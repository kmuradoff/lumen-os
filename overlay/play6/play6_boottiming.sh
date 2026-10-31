#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS for the XGIMI Play 6, DIAG images only (never in a release image): boot timing record, started by
# /system/etc/init/play6_boottiming.rc.
#   play6_boottiming.sh mark STAGE   appends "STAGE uptime=<s> boottime_last=[<last /proc/boottime line>]"
#   play6_boottiming.sh watch        from 'on init' until 45 s after sys.boot_completed=1 (or 870 s of uptime):
#                                    every 0.5 s (1 s after 60 s, 3 s after 180 s) logs with its uptime each new
#                                    /proc/boottime line, new /sys/module entry, new /sys/class/pwm chip, and each
#                                    change of init.svc.*, sys.boot_completed, sys.system_server.start_count,
#                                    apexd.status, odsign.*, service.bootanim.exit, sys.lumen.*
# The record lives in /dev/lumen_p6_timing (tmpfs). While the USB-stick boot logger (z9x_earlylog) has its stick
# mounted at /dev/z9x_earlylog, the watcher writes timing.txt into that boot's folder
# (lumen_os_diag_logs/early/boot_N, found by this boot's ro.boottime.init line in its props.txt), every 2 s and
# at the end; with no such folder after 30 s, lumen_os_diag_logs/timing_<boot_id>.txt instead.
# Read-only towards the system: no setprop, no service control, no write outside those two places.
# P6T_* variables exist only for the host test (tools/play6/test_play6_boottiming.sh); init sets none.
PROC=${P6T_PROC:-/proc}
SYS=${P6T_SYS:-/sys}
R=${P6T_DIR:-/dev/lumen_p6_timing}
STICK=${P6T_STICK:-/dev/z9x_earlylog}
SLEEP=${P6T_SLEEP:-sleep}   # mksh has sleep built in; the host test passes its stub
mkdir -p $R 2>/dev/null

if [ "$1" = mark ]; then
  read -r U _ < $PROC/uptime
  L=
  if [ -r $PROC/boottime ]; then while read -r line; do L=$line; done < $PROC/boottime; fi
  echo "$2 uptime=$U boottime_last=[$L]" >> $R/stages.txt
  exit 0
fi
[ "$1" = watch ] || exit 0

PAT='^\[(init\.svc\.[^]]*|sys\.boot_completed|sys\.system_server\.start_count|apexd\.status|odsign\.[^]]*|service\.bootanim\.exit|sys\.lumen\.[^]]*|ro\.boottime\.init)\]'
EV=$R/events.txt
BOOT_ID=; read -r BOOT_ID 2>/dev/null < $PROC/sys/kernel/random/boot_id
KEY=        # this boot's "[ro.boottime.init]: [...]" line: the same line is in the earlylog's props.txt
TF=         # the file on the stick
mounted_at=
done_at=
echo '#' > $R/.prev
ev() { echo "$U $*" >> $EV; }

first_running() {  # first_running SERVICE: uptime when init.svc.SERVICE first became running, or -
  t=$(grep -m 1 " prop: \[init\.svc\.$1\]: \[running\]" $EV 2>/dev/null)
  echo "${t%% *}" | sed 's/^$/-/'
}
stage_at() {  # stage_at STAGE: uptime of the init mark
  t=$(grep -m 1 "^$1 uptime=" $R/stages.txt 2>/dev/null)
  t=${t#* uptime=}; echo "${t%% *}" | sed 's/^$/-/'
}
report() {  # the whole record in one file
  {
    echo "# Lumen OS Play 6 boot timing (play6_boottiming.sh). Times are seconds of uptime (/proc/uptime)."
    echo "boot_id=$BOOT_ID build=$(getprop ro.lumen.build_id) $KEY"
    echo "written_at_uptime=$U"
    echo "## summary"
    echo "stages: post-fs-data=$(stage_at post-fs-data) zygote-start=$(stage_at zygote-start) early-boot=$(stage_at early-boot) boot=$(stage_at boot) boot_completed=$(stage_at boot_completed)"
    s="first running:"
    for v in gmpf_main gmpfHw trapezoidServer midaemon tv-input-1-0 bootvideo surfaceflinger zygote bootanim; do
      s="$s $v=$(first_running $v)"
    done
    echo "$s"
    p=$(grep -m 1 ' pwm: ' $EV 2>/dev/null); echo "first new pwm chip: ${p:--}"
    echo "## stages (init marks; boottime_last = the vendor's /proc/boottime clock at that moment)"
    cat $R/stages.txt 2>/dev/null
    echo "## events"
    cat $EV 2>/dev/null
    echo "## /proc/boottime (vendor clock, not uptime)"
    cat $PROC/boottime 2>/dev/null
    echo "## ro.boottime.* (service start, ns of uptime)"
    getprop | grep '^\[ro\.boottime\.' | sed 's/^\[ro\.boottime\.\(.*\)\]: \[\(.*\)\]$/\2 \1/' | sort -n
  } > $R/timing.txt.new 2>/dev/null
  mv -f $R/timing.txt.new $R/timing.txt
  if [ -n "$TF" ] && [ -d $STICK/lumen_os_diag_logs ]; then
    cp $R/timing.txt "$TF.new" 2>/dev/null && mv -f "$TF.new" "$TF" 2>/dev/null
  fi
}

mods=" "; pwms=" "; btn=0; round=0
while :; do
  read -r U _ < $PROC/uptime
  ui=${U%.*}
  # the vendor's own stage list (init:on <stage>, utpa2k.ko / mik.ko, MI daemon ...)
  if [ -r $PROC/boottime ]; then
    n=0
    while read -r line; do n=$((n + 1)); [ $n -gt $btn ] && ev "boottime: $line"; done < $PROC/boottime
    btn=$n
  fi
  # kernel modules and pwm chips: the first round only lists what is there already
  new=
  for m in $SYS/module/*; do
    m=${m##*/}
    case "$mods" in *" $m "*) ;; *) mods="$mods$m "; new="$new $m" ;; esac
  done
  if [ $round = 0 ]; then ev "modules at start:$new"; else for m in $new; do ev "module: $m"; done; fi
  new=
  for p in $SYS/class/pwm/pwmchip*; do
    [ -e "$p" ] || continue
    p=${p##*/}
    case "$pwms" in *" $p "*) ;; *) pwms="$pwms$p "; new="$new $p" ;; esac
  done
  if [ $round = 0 ]; then ev "pwm at start:${new:- none}"; else for p in $new; do ev "pwm: $p"; done; fi
  # service states and boot props: only what changed since the last round
  getprop | grep -E "$PAT" > $R/.cur 2>/dev/null
  grep -vxF -f $R/.prev $R/.cur 2>/dev/null | while read -r line; do ev "prop: $line"; done
  mv -f $R/.cur $R/.prev
  [ -n "$KEY" ] || KEY=$(grep -m 1 '^\[ro\.boottime\.init\]' $R/.prev)
  # the stick of the boot logger
  if [ -z "$TF" ] && [ -d $STICK/lumen_os_diag_logs/early ]; then
    [ -n "$mounted_at" ] || mounted_at=$ui
    if [ -n "$KEY" ]; then
      for d in $STICK/lumen_os_diag_logs/early/boot_*; do
        [ -f "$d/props.txt" ] && grep -qxF "$KEY" "$d/props.txt" && { TF=$d/timing.txt; break; }
      done
    fi
    [ -z "$TF" ] && [ $((ui - mounted_at)) -ge 30 ] && TF=$STICK/lumen_os_diag_logs/timing_$BOOT_ID.txt
  fi
  grep -qxF '[sys.boot_completed]: [1]' $R/.prev && [ -z "$done_at" ] && done_at=$ui
  if [ -n "$done_at" ] && [ $((ui - done_at)) -ge 45 ]; then break; fi   # before z9x_earlylog unmounts (>= 60 s)
  [ $ui -ge 870 ] && break
  round=$((round + 1))
  [ $((round % 4)) = 0 ] && report
  if [ $ui -lt 60 ]; then $SLEEP 0.5; elif [ $ui -lt 180 ]; then $SLEEP 1; else $SLEEP 3; fi
done
read -r U _ < $PROC/uptime
[ -z "$TF" ] && [ -d $STICK/lumen_os_diag_logs ] && TF=$STICK/lumen_os_diag_logs/timing_$BOOT_ID.txt
ev "watch end"
report
sync
exit 0
