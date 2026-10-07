#!/system/bin/sh
# Z9X boot logger (/system/etc/xgimi/z9x_diag.sh, 0755). Started by z9x_diag.rc at 'on boot' as a
# background oneshot service (after early-boot), so it can never block init.
# mksh + toybox only: no bashisms, and no number above 2^31 in $(( )) (mksh arithmetic is 32-bit).
#
# v6.1/v6.2: PASSIVE. It only writes log files: /data/misc/z9x_diag (fallback /dev/z9x_diag) and
# <USB stick>/z9x_diag_logs/ ONLY on a stick that already has that folder (the user created it on
# purpose) or with the developer prop persist.z9x.diag_usb=1 (default unset: no stick is ever written).
# It never calls setprop (v6's persist.z9x.lastboot / strike kill-switch bookkeeping is gone with
# z9x_features.rc), never stops/restarts any service and has no effect on behaviour. Vendor files
# are only read. Files are group "log" readable (adb shell is in AID_LOG): adb pull works without root.
#
# Layout: $D/boot.1 = this boot, boot.2 = previous, boot.3 = the one before; $D/history.txt (one line
# at start and one at end of every boot); $D/bootcount. Cap: 3 boots and 40 MB.
# HEALTHY (a log marker file only) = sys.boot_completed=1 AND vendor.xgimi.ledOn=true seen (set by
# gmpf_main, libporting PlatformPanel::setLedOn) AND the gmpf_main pid never changed, held for 90 s
# after boot_completed. gmpf_main restarts / downtime are logged to events.txt (the vendor DrvWdt
# reboots ~11 s after a restart). Logging stops once the boot is healthy and the window has passed
# (default 15 min from logger start, persist.z9x.diag.minutes overrides; 60 min if never healthy);
# then the script exits.
# logcat capture silences the XGIMI spam tags Motion_det_ctl and XGIMI_PERCEPTION_DEBUG.
#
# v6.5 (still passive):
#  - history.txt classifies every boot from /sys/mtk_pm: REBOOT (0xD1), POWER-ON (0xFF + wake source),
#    AC-POWER-ON (0xF1 + wake source) and SELF-RESET (0xF1, no wake source: the projector switched
#    itself on); a self-reset also gets a "!!! ... SELF-RESET" line with the last power state the
#    previous boot logged. wdt_reset_chk is labelled sticky: it is set by every PM watchdog reset,
#    normal reboots included, and only AC loss clears it, so it says nothing about a self-reset.
#  - power watch for the whole boot (also after the 15 min log window, then every 5 s and nothing
#    else): a change of the display state (debug.tracing.screen_state), of the lamp
#    (vendor.xgimi.ledOn) or of the Projector app's saved power state (pw_state in its z9x_power.xml,
#    read-only) gives a "power:" line in history.txt (awake / standby = display on + lamp off or app
#    standby / sleep = display off) with /sys/power/suspend_stats, and ~6 s later a capture
#    boot.N/wake_<HHMMSS>_<state>.txt: suspend_stats, wakeup reasons, active /sys/power/wake_lock,
#    and the logcat / kernel lines of the display chain (LightsHAL backlight, PNL_CUST / VB1 RCon,
#    hwcomposer power mode, DLPC 0xE2) and of the Z9X power code (at most 60 captures per boot).
#  - review 6.5 (no separate test needed): every capture also counts the display-chain POWER-CYCLE
#    markers (LightsHAL "[STR check point]" backlight off/on, hwcomposer "close Gwin" /
#    displayGopOnOff, PNL_CUST, Panel Driver, SetVb1, PanelPowerOnOff) in logcat since the pass
#    before the change and writes a "power-check:" line to history.txt. For a lamp-only standby enter
#    or exit (Projector app 6.5) the expected count is 0: the panel / VB1 / DLPC chain is never
#    re-initialised, so the quadrant block noise cannot come back; a count > 0 there is the evidence
#    that gmpf's lamp-off path does more than the LED. A sleep (display off) is expected to count > 0.
#  - review 6.5: if the previous boot's pmsg tail shows the vendor PowerHAL suspend (gmpf_suspend)
#    although the Projector app's last saved state was not 'off' (our power-off) and
#    persist.z9x.allow_str is 0 (Lumen OS 1.0: STR is allowed unless the kill switch is set to 0, so
#    only then is z9x_no_str held), a "!!! ... vendor suspend" line goes to
#    history.txt: something reached STR on its own (no vendor userspace caller of the libmi3
#    /sys/power/state writers MI_PM_FastStandby / MI_PM_RtcWakeUp is known; see z9x_base.rc).

# R is empty on the device (init gives services a clean environment); the stub test harness sets it.
R=${Z9X_DIAG_ROOT:-}
umask 022
D=$R/data/misc/z9x_diag
WINDOW_MIN=$(getprop persist.z9x.diag.minutes)
case "$WINDOW_MIN" in ''|*[!0-9]*) WINDOW_MIN=15 ;; esac
FAIL_MAX_MIN=60
LOGCAT_KB=1024
DMESG_BYTES=3145728
MAX_KB=40000
HOLD_S=90

# now(): sets LASTUP to the uptime in whole seconds (called directly, not in $(), so the fallback
# "previous + 5" survives when /proc/uptime cannot be read).
LASTUP=0
now() {
  u=$(cut -d' ' -f1 $R/proc/uptime 2>/dev/null)
  u=${u%%.*}
  case "$u" in ''|*[!0-9]*) u=$((LASTUP + 5)) ;; esac
  LASTUP=$u
}
gp() { getprop "$1"; }
# share: group log + g+rX on everything (logcat -f creates 0640 root:root files); errors ignored
share() { chgrp -R log $D 2>/dev/null; chmod -R g+rX $D 2>/dev/null; }
gv() { x=$(getprop "$1"); echo "${x:--}"; }   # value or "-" (keeps status columns aligned)

# v6.5: /sys/power/suspend_stats as one line (every read bounded by 'timeout 1')
sstats() {
  o=""
  for f in success fail failed_freeze failed_prepare failed_suspend failed_suspend_late failed_suspend_noirq \
           failed_resume failed_resume_early failed_resume_noirq last_failed_dev last_failed_errno last_failed_step; do
    v=$(timeout 1 cat $R/sys/power/suspend_stats/$f 2>/dev/null | tr -s ' \t\n' ' ')
    o="$o $f=${v:--}"
  done
  echo "$o"
}
sstats_short() {
  echo "success=$(timeout 1 cat $R/sys/power/suspend_stats/success 2>/dev/null) fail=$(timeout 1 cat $R/sys/power/suspend_stats/fail 2>/dev/null) last_failed_step=$(timeout 1 cat $R/sys/power/suspend_stats/last_failed_step 2>/dev/null | tr -s ' \t\n' ' ')"
}

mkdir -p $D 2>/dev/null
if ! touch $D/.w 2>/dev/null; then D=$R/dev/z9x_diag; mkdir -p $D; fi
rm -f $D/.w

# ---- rotate (keep 3 boots) ----
rm -rf $D/boot.3
[ -d $D/boot.2 ] && mv $D/boot.2 $D/boot.3
[ -d $D/boot.1 ] && mv $D/boot.1 $D/boot.2
B=$D/boot.1
mkdir -p $B/pstore $B/vendor_err
n=$(cat $D/bootcount 2>/dev/null)
case "$n" in ''|*[!0-9]*) n=0 ;; esac
n=$((n + 1))
echo $n > $D/bootcount
echo $n > $B/bootcount
BOOTID=$(cat $R/proc/sys/kernel/random/boot_id 2>/dev/null)
FEAT="z9x=$(gp ro.z9x.version) feat=$(gp sys.z9x.feat) clickcurve=$(gp ro.z9x.clickcurve)"

# ---- header ----
{
  echo "bootcount=$n"
  echo "boot_id=$BOOTID"
  echo "start_uptime=$(cut -d' ' -f1 $R/proc/uptime)"
  echo "date=$(date)"
  for p in ro.build.fingerprint ro.lineage.version ro.z9x.version ro.vendor.build.version.incremental \
           ro.boot.bootreason ro.boot.slot_suffix persist.sys.boot.reason sys.boot.reason \
           sys.z9x.feat ro.z9x.clickcurve persist.z9x.firstrun persist.sys.locale \
           bluetooth.core.gap.le.privacy.enabled ro.audio.flinger_standbytime_ms \
           ro.vendor.xgimi.watchdog.magic init.svc.gmpf_main init.svc_debug_pid.gmpf_main \
           ro.boottime.gmpf_main ro.boottime.gmpfHw; do
    echo "$p=$(gp $p)"
  done
  echo "persist.sys.boot.reason.history:"
  gp persist.sys.boot.reason.history
  echo "cmdline=$(cat $R/proc/cmdline)"
} > $B/info.txt 2>&1
echo "$(date +%Y%m%d-%H%M%S) start boot#$n id=$(echo $BOOTID | cut -c1-8) bootreason=$(gp ro.boot.bootreason) $FEAT" >> $D/history.txt

# ---- v6.2: power/wake evidence (read-only): what woke or reset the box, PM wake sources, RTC alarm,
# watchdog reset flag; last line of z9x_poweroff.sh's log (power-off watchdog stop, z9x_poweroff.rc).
# Every vendor node read is bounded by 'timeout 1' (side effects / hang behaviour UNVERIFIED, as in
# z9x_poweroff.sh), so a hung node cannot stall the logger before its logcat loop ----
{
  for f in boot_reason wakeup_reason/id wakeup_reason/name wakeup_key max_cnt; do
    echo "mtk_pm/$f=$(timeout 1 cat $R/sys/mtk_pm/$f 2>&1 | tr '\n' ' ')"
  done
  for f in $R/sys/mtk_pm/wakeup_source/*; do echo "ws ${f##*/}=$(timeout 1 cat $f 2>&1)"; done
  W=$R/sys/bus/platform/devices/1c400600.wdt0/wdt_extend
  echo "wdt_reset_chk=$(timeout 1 cat $W/wdt_reset_chk 2>&1) wdtwake=$(timeout 1 cat $W/wdtwake 2>&1) wdt_ms_timeout=$(timeout 1 cat $W/wdt_ms_timeout 2>&1)"
  echo "  (wdt_reset_chk is STICKY: set by every PM watchdog reset incl. normal reboots, cleared only by AC loss; NOT a self-reset indicator)"
  echo "suspend_stats:$(sstats)"
  echo "last_resume_reason=$(timeout 1 cat $R/sys/kernel/wakeup_reasons/last_resume_reason 2>&1 | tr '\n' ' ')"
  echo "bt_wakeupsource=$(timeout 1 cat $R/sys/class/BT_chrdev/wakeupsource/wakeupsource 2>&1)"
  timeout 1 grep -E 'rtc_time|rtc_date|alrm_time|alrm_date|alarm_IRQ' $R/proc/driver/rtc 2>&1
  echo "last_poweroff: $(grep -E '^[0-9]{8}-' $D/poweroff.txt 2>/dev/null | tail -n 1)"
} > $B/pm_boot.txt 2>&1
# v6.5 boot class (what powered the SoC on) and the last power state of the previous boot
br=$(grep '^mtk_pm/boot_reason=' $B/pm_boot.txt | head -n 1 | cut -d= -f2 | tr -d ' ')
wn=$(grep '^mtk_pm/wakeup_reason/name=' $B/pm_boot.txt | head -n 1 | cut -d= -f2 | tr -d ' ')
wc0=$(grep '^wdt_reset_chk=' $B/pm_boot.txt | head -n 1 | cut -d' ' -f1 | cut -d= -f2)
prev_pw=$(grep ' power: ' $D/history.txt 2>/dev/null | tail -n 1)
case "$br" in
  0xF1|0xf1)
    case "$wn" in
      '(null)'|'') CLASS=SELF-RESET; WHY="cold reset, no wake source: nobody pressed a key" ;;
      *) CLASS=AC-POWER-ON; WHY="cold start by $wn (mains plugged in)" ;;
    esac ;;
  0xFF|0xff) CLASS=POWER-ON; WHY="power-on from power-off by ${wn:-?}" ;;
  0xD1|0xd1) CLASS=REBOOT; WHY="software reboot" ;;
  *) CLASS=UNKNOWN; WHY="boot_reason ${br:-?}" ;;
esac
echo "boot_class=$CLASS ($WHY)" >> $B/pm_boot.txt
echo "$(date +%Y%m%d-%H%M%S) boot#$n pm: class=$CLASS ($WHY) boot_reason=${br:--} wake=${wn:--} wdt_reset_chk(sticky)=${wc0:--}" >> $D/history.txt
if [ "$CLASS" = SELF-RESET ]; then
  echo "$(date +%Y%m%d-%H%M%S) !!! boot#$n SELF-RESET: the projector switched itself on (0xF1, no wake source). Previous boot's last power state: ${prev_pw:-none logged}" >> $D/history.txt
fi

# ---- evidence of the PREVIOUS boot: pstore (kernel console tail + pmsg logcat tail) ----
cp -f $R/sys/fs/pstore/* $B/pstore/ 2>/dev/null
ls -la $R/sys/fs/pstore > $B/pstore/ls.txt 2>&1
timeout 20 logcat -L -b all -d -v threadtime > $B/prev_logcat_pmsg.txt 2>&1
{
  echo "== previous boot: kernel console (pstore) markers"
  grep -ahE 'Received sys.powerctl|Restarting system|System Reboot|Kernel panic|Oops|watchdog|wdt|sysrq|thermal|Thermal|Power down|panic' $B/pstore/console-ramoops* 2>/dev/null | tail -n 60
  echo "== previous boot: logcat (pmsg) markers"
  grep -aE 'gmpf_main|DrvWdt|reset system after bad|sys.powerctl|dlpCheckStatusRefine|led on|wait cw|iic on|Fatal signal|PlatformPwm|pwm wait|GM_DISP_CTRL|LightsHAL|PowerHAL|apm_thread|z9x' $B/prev_logcat_pmsg.txt 2>/dev/null | tail -n 120
  if [ -d $D/boot.2 ] && [ ! -e $D/boot.2/HEALTHY ]; then echo "!! PREVIOUS BOOT NEVER BECAME HEALTHY"; fi
} > $B/prev_summary.txt 2>&1
# review 6.5: vendor suspend in the previous boot although STR was blocked and we did not power off.
# The Projector app has not started yet ('on boot'), so its prefs still hold the previous boot's
# last power state (read-only).
prev_app=$(grep -o '"pw_state">[a-z]*' $R/data/data/org.z9x.projector/shared_prefs/z9x_power.xml 2>/dev/null | head -n 1)
prev_app=${prev_app##*>}
if grep -aq 'gmpf_suspend' $B/prev_logcat_pmsg.txt 2>/dev/null; then
  if [ "$prev_app" = off ]; then
    echo "previous boot: vendor PowerHAL gmpf_suspend at our power-off (app state off): expected" >> $B/prev_summary.txt
  elif [ "$(gp persist.z9x.allow_str)" = 0 ]; then
    echo "$(date +%Y%m%d-%H%M%S) !!! boot#$n previous boot reached the vendor suspend path (PowerHAL gmpf_suspend) with STR blocked (z9x_no_str) and app state '${prev_app:--}', not our power-off: a vendor path suspended on its own" >> $D/history.txt
  fi
fi

# ---- XGIMI's own DLP/boot error logs (read-only copies) ----
for f in $R/mnt/vendor/xgimisps/devErrlog.xml $R/mnt/vendor/xgimisps/devErrBootlog.xml \
         $R/vendor/xgimisps/devErrlog.xml $R/vendor/xgimisps/devErrBootlog.xml; do
  [ -f "$f" ] && cp -f "$f" "$B/vendor_err/$(echo $f | tr / _)" 2>/dev/null
done
ls -la $R/mnt/vendor/xgimisps $R/data/xgimilog > $B/vendor_err/ls.txt 2>&1

# ---- continuous logs (children die with this service's process group) ----
start_logcat() {
  logcat -b all -v threadtime -f $B/logcat.txt -r $LOGCAT_KB -n 4 \
    'Motion_det_ctl:S' 'XGIMI_PERCEPTION_DEBUG:S' '*:V' &
  LC=$!
}
start_logcat
LC_RESTARTS=0
dmesg -w 2>&1 | head -c $DMESG_BYTES > $B/dmesg.txt &
DM=$!

snap() {
  getprop > $B/props_$1.txt 2>&1
  ps -A -o PID,PPID,S,NI,PRI,TIME,WCHAN,NAME,ARGS > $B/ps_$1.txt 2>&1
  cat $R/proc/boottime > $B/boottime_$1.txt 2>/dev/null
}

tombs() {
  ls -la $R/data/tombstones > $B/tombstones_ls.txt 2>&1
  for tf in $(ls -t $R/data/tombstones/tombstone_[0-9][0-9] 2>/dev/null | head -n 2); do
    cp -f "$tf" $B/ 2>/dev/null
  done
  timeout 10 logcat -b crash -d -v threadtime > $B/logcat_crash.txt 2>&1
}

# usb_copy early|now : early = previous boots (boot.2, boot.3), now = this boot (boot.1).
# Only a stick that vold has already mounted (/mnt/media_rw/*) is used. The logger never mounts a
# block device itself, so it can never race vold's fsck/mount of the user's stick (which may be the
# XGIMI firmware rescue stick). Every write stays inside <stick>/z9x_diag_logs/.
# Opt-in (review 2026-10-07: the logs hold the serial, account names, Wi-Fi SSIDs, URLs): a stick is
# used only when it ALREADY has a z9x_diag_logs/ folder (made by the user on purpose), or, for a
# developer, with persist.z9x.diag_usb=1 (then the folder is created). A stick plugged in to play a
# film, or a friend's stick, is never written.
usb_copy() {
  mp=""
  usb_dev=$(gp persist.z9x.diag_usb)
  for m in $R/mnt/media_rw/*; do
    [ -d "$m" ] || continue
    if [ ! -d "$m/z9x_diag_logs" ]; then
      [ "$usb_dev" = 1 ] || continue
      mkdir -p "$m/z9x_diag_logs" 2>/dev/null || continue
    fi
    touch "$m/z9x_diag_logs/.w" 2>/dev/null || continue
    rm -f "$m/z9x_diag_logs/.w"
    mp=$m; break
  done
  if [ -z "$mp" ]; then
    echo "$LASTUP usb $1: no USB stick with a z9x_diag_logs folder (opt-in; persist.z9x.diag_usb=$usb_dev)" >> $B/events.txt
    return 1
  fi
  dst=$mp/z9x_diag_logs/$(gp ro.serialno)
  mkdir -p $dst
  # previous boots go once: in the early attempt or, if that found no stick, with the first 'now' copy
  if [ "$1" = early ]; then list="2 3"; elif [ $prev_done = 0 ]; then list="2 3 1"; else list="1"; fi
  prev_done=1
  for x in $list; do
    [ -d $D/boot.$x ] || continue
    c=$(cat $D/boot.$x/bootcount 2>/dev/null)
    [ -n "$c" ] || c=x$x
    mkdir -p $dst/b$c
    cp -rf $D/boot.$x/* $dst/b$c/ 2>/dev/null
  done
  cp -f $D/history.txt $dst/ 2>/dev/null
  sync; last_sync=$LASTUP
  echo "$LASTUP usb copy $1 -> $mp" >> $B/events.txt
  return 0
}

# seconds part of a nanosecond string without 32-bit overflow ("3890123456" -> 3)
ns2s() { case "$1" in ??????????*) echo "${1%?????????}" ;; *[0-9]*) echo 0 ;; *) echo "" ;; esac; }

# ---- v6.5 power watch (passive): display state + lamp, suspend_stats around every change ----
# debug.tracing.screen_state is set by DisplayPowerController (Display.STATE_*: 1 off, 2 on, 3 doze,
# 4 doze-suspend); vendor.xgimi.ledOn by gmpf_main (lamp).
PWRE='LightsHAL|Backlight|STR check point|PNL_CUST|Panel Driver|TCON|SetVb1|setPowerMode|SetPowerMode|Gwin|displayGopOnOff|AFBC|0xE2|Dlp8445|DLP_8445|DisplayExecuteQueued|DLPC|dlpCheck|led on|GoToShutdown|gmpf_suspend|DrvWdt|PowerHAL|APM_|screen_toggled|power_screen_state|DisplayPowerController|PowerManagerService: (Going to sleep|Waking up|Sleeping|Dreaming|Nap)|Z9x(Standby|Power|Lamp|Curtain|Idle|BootInfo|Dream|SleepTimer|Eye)|z9x_no_str'
KRE='PNL_CUST|RCon|BiasCon|Double_term|PanelPowerOnOff|VrrModeChange|Panel Driver|STR check|Backlight|HWI2C|PgammaIC|Levelshift|TCON|OverDriver|PM: |Freezing|Restarting tasks|suspend entry|suspend exit|wdt|Gwin'
# display-chain POWER-CYCLE markers (review 6.5): 0 expected around a lamp-only standby enter / exit
CHAINRE='STR check point|close Gwin|displayGopOnOff|PNL_CUST|Panel Driver|SetVb1|PanelPowerOnOff'
ss_get() {
  s=$(gp debug.tracing.screen_state)
  case "$s" in 1) echo off ;; 2) echo on ;; 3) echo doze ;; 4) echo doze-suspend ;; '') echo "-" ;; *) echo "s$s" ;; esac
}
# power state the Projector app last saved (StandbyController marker: active / standby / sleep / off);
# read-only, the app's prefs file (root can read it)
APPPREFS=$R/data/data/org.z9x.projector/shared_prefs/z9x_power.xml
app_state() {
  a=$(grep -o '"pw_state">[a-z]*' $APPPREFS 2>/dev/null | head -n 1)
  a=${a##*>}
  echo "${a:--}"
}
pw_ss=""; pw_led=""; pw_app=""; pw_n=0; pw_due=""; pw_tag=""; pw_pass_ts=""; pw_since=""; pw_ex=""
# pcap TAG: one capture file per power change (at most 60 per boot)
pcap() {
  [ $pw_n -lt 60 ] || return 0
  pw_n=$((pw_n + 1))
  {
    echo "== $(date) uptime=$(cut -d' ' -f1 $R/proc/uptime) $1"
    echo "== app power state: $(app_state) ($(grep -o '"pw_reason">[^<]*' $APPPREFS 2>/dev/null | head -n 1 | cut -d'>' -f2))"
    echo "== props: screen_state=$(gv debug.tracing.screen_state) brightness=$(gv debug.tracing.screen_brightness) ledOn=$(gv vendor.xgimi.ledOn) xgimi.str=$(gv sys.xgimi.str) allow_str=$(gv persist.z9x.allow_str) boot_reason=$(gv sys.z9x.boot_reason)"
    echo "== suspend_stats:$(sstats)"
    echo "== wakeup: mtk_pm=$(timeout 1 cat $R/sys/mtk_pm/wakeup_reason/name 2>&1 | tr -d '\n') pm_wakeup_irq=$(timeout 1 cat $R/sys/power/pm_wakeup_irq 2>&1 | tr -d '\n') last_resume_reason=$(timeout 1 cat $R/sys/kernel/wakeup_reasons/last_resume_reason 2>&1 | tr '\n' ' ')"
    echo "== /sys/power/wake_lock: $(timeout 1 cat $R/sys/power/wake_lock 2>&1)"
    # Lumen OS 1.0: STR is refused while a USB host keeps the UDC clock on (PM51 'clk is enable' -> 0xF1 reset)
    echo "== usb: udc=$(for u in $R/sys/class/udc/*; do [ -e "$u/state" ] && printf '%s=%s ' "${u##*/}" "$(timeout 1 cat "$u/state" 2>&1)"; done) config=$(gv persist.sys.usb.config)/$(gv sys.usb.config) state=$(gv sys.usb.state)"
    echo "== ws:$(for f in $R/sys/mtk_pm/wakeup_source/*; do [ -e "$f" ] && printf ' %s=%s' "${f##*/}" "$(timeout 1 cat "$f" 2>&1 | tr -d '\n')"; done)"
    echo "== logcat: display chain / lamp / power markers (last 400 lines)"
    timeout 10 logcat -d -b main,system,events -v threadtime 2>/dev/null | grep -aE "$PWRE" | grep -avE 'setBoost|Mode Type = [56] ' | tail -n 400
    echo "== kernel: panel / VB1 / power markers (last 150 lines)"
    timeout 10 dmesg 2>/dev/null | grep -aE "$KRE" | tail -n 150
  } > "$B/wake_$1.txt" 2>&1
  # review 6.5: display-chain power-cycle markers since the pass before the change (logcat -T)
  if [ -n "$pw_since" ]; then
    nc=$(timeout 10 logcat -d -b main,system -v threadtime -T "$pw_since" 2>/dev/null | grep -acE "$CHAINRE")
  else
    nc="?"
  fi
  echo "== display-chain power-cycle markers since $pw_since: ${nc:-0}" >> "$B/wake_$1.txt"
  echo "$(date +%Y%m%d-%H%M%S) boot#$n power-check: $1 display-chain markers=${nc:-0} ($pw_ex)" >> $D/history.txt
  share
}
# pwatch: called every loop pass with $t set
pwatch() {
  ss=$(ss_get); ld=$(gv vendor.xgimi.ledOn); ap=$(app_state)
  # logcat -T time of this pass; a change seen now happened after the previous pass
  prev_ts=$pw_pass_ts; pw_pass_ts=$(date '+%m-%d %H:%M:%S.000')
  # until boot_completed only follow the values (the boot itself is not a power change)
  if [ -z "$pw_ss" ] || [ -z "$bc_at" ]; then pw_ss=$ss; pw_led=$ld; pw_app=$ap; return 0; fi
  if [ "$ss" != "$pw_ss" ] || [ "$ld" != "$pw_led" ] || [ "$ap" != "$pw_app" ]; then
    case "$ss/$ld/$ap" in
      off/*) st=sleep ;;
      on/*/standby) st=standby ;;
      on/false/*) st=standby ;;
      on/true/*) st=awake ;;
      *) st=other ;;
    esac
    case "$pw_ss/$ss" in
      on/on) ex_new="display stayed on: 0 expected, panel / VB1 / GOP untouched" ;;
      *) ex_new="display state changed: > 0 expected" ;;
    esac
    echo "$t power: screen $pw_ss->$ss lamp $pw_led->$ld app $pw_app->$ap = $st" >> $B/events.txt
    echo "$(date +%Y%m%d-%H%M%S) boot#$n power: $st (screen=$ss lamp=$ld app=$ap) $(sstats_short)" >> $D/history.txt
    pw_ss=$ss; pw_led=$ld; pw_app=$ap
    if [ -n "$pw_due" ]; then pcap "$pw_tag"; fi
    pw_since=${prev_ts:-$pw_pass_ts}; pw_ex=$ex_new
    pw_due=$((t + 6)); pw_tag="$(date +%H%M%S)_$st"
  fi
  if [ -n "$pw_due" ] && [ $t -ge $pw_due ]; then
    pw_due=""
    pcap "$pw_tag"
  fi
  return 0
}

echo "# uptime boot_completed bootanim gmpf_main pid gmpfHw ledOn xgimi.sleep xgimi.str power_hal audioserver le_privacy temp0 temp1 pwmchip8" > $B/status.txt
now; T0=$LASTUP
echo "$T0 logger start; $FEAT" >> $B/events.txt
first_led=""; bc_at=""; lastpid=""; laststate=""; restarts=0; healthy=0; healthy_at=""
down_since=""; down_logged=0

# down_check: gmpf_main not running for >= 10 s while no shutdown/reboot is in progress
# (sys.powerctl is set before init stops services at shutdown) -> one log line (log only).
down_check() {
  if [ "$gst" != running ] && [ -z "$(gp sys.powerctl)" ]; then
    [ -n "$down_since" ] || down_since=$t
    if [ $down_logged = 0 ] && [ $((t - down_since)) -ge 10 ]; then
      down_logged=1
      echo "$t gmpf_main not running ('$gst') for $((t - down_since)) s" >> $B/events.txt
      echo "$(date +%Y%m%d-%H%M%S) boot#$n gmpf_main not running for $((t - down_since)) s" >> $D/history.txt
    fi
  else
    down_since=""; down_logged=0
  fi
}
usb_early=0; prev_done=0; usb_last=0; usb_hl=0; s90=0; s300=0; dumped=0; lastdu=0; last_sync=0

# gmpf_main restarted BEFORE this logger started? Compare the running instance's start time
# (/proc/<pid>/stat field 22, clock ticks at USER_HZ=100) with ro.boottime.gmpf_main (first start, ns).
gpid0=$(gp init.svc_debug_pid.gmpf_main)
bt0=$(ns2s "$(gp ro.boottime.gmpf_main)")
if [ -n "$gpid0" ] && [ -n "$bt0" ]; then
  st=$(cut -d' ' -f22 $R/proc/$gpid0/stat 2>/dev/null)
  case "$st" in
    ''|*[!0-9]*) ;;
    *) st=$((st / 100))
       if [ $((st - bt0)) -gt 3 ] || [ $((bt0 - st)) -gt 3 ]; then
         restarts=1
         echo "$T0 gmpf_main pid $gpid0 started at ${st}s but first start was ${bt0}s: RESTARTED before the logger" >> $B/events.txt
       fi ;;
  esac
fi

while :; do
  now; t=$LASTUP
  bc=$(gp sys.boot_completed)
  led=$(gp vendor.xgimi.ledOn)
  gst=$(gp init.svc.gmpf_main)
  gpid=$(gp init.svc_debug_pid.gmpf_main)
  [ -z "$gpid" ] && gpid=$(pidof gmpf_main 2>/dev/null)
  t0=$(cat $R/sys/class/thermal/thermal_zone0/temp 2>/dev/null)
  t1=$(cat $R/sys/class/thermal/thermal_zone1/temp 2>/dev/null)
  pwm=0; [ -e $R/sys/class/pwm/pwmchip8 ] && pwm=1
  echo "$t ${bc:--} $(gv init.svc.bootanim) ${gst:--} ${gpid:--} $(gv init.svc.gmpfHw) ${led:--} $(gv persist.vendor.xgimi.sleep) $(gv sys.xgimi.str) $(gv init.svc.vendor.power-mediatek) $(gv init.svc.audioserver) $(gv bluetooth.core.gap.le.privacy.enabled) ${t0:--} ${t1:--} $pwm" >> $B/status.txt

  # keep logcat alive (it exits if logd restarts); -f appends, so the file just continues
  if ! kill -0 $LC 2>/dev/null && [ $LC_RESTARTS -lt 5 ]; then
    LC_RESTARTS=$((LC_RESTARTS + 1))
    echo "$t logcat exited, restart $LC_RESTARTS" >> $B/events.txt
    start_logcat
  fi

  # gmpf_main lifecycle (it switches the DLP LED on and runs the fan/temperature loop)
  if [ "$gst" != "$laststate" ]; then
    echo "$t gmpf_main state '$laststate' -> '$gst' pid=$gpid" >> $B/events.txt
    laststate=$gst
  fi
  if [ -n "$gpid" ] && [ -n "$lastpid" ] && [ "$gpid" != "$lastpid" ]; then
    restarts=$((restarts + 1))
    echo "$(date +%Y%m%d-%H%M%S) boot#$n gmpf_main restart (pid $lastpid -> $gpid)" >> $D/history.txt
    echo "$t gmpf_main RESTARTED pid $lastpid -> $gpid (count $restarts); vendor DrvWdt reboots ~11 s after a restart" >> $B/events.txt
    snap gmpf_restart_$restarts
    tombs
  fi
  [ -n "$gpid" ] && lastpid=$gpid
  down_check
  pwatch
  if [ -z "$first_led" ] && [ "$led" = true ]; then first_led=$t; echo "$t ledOn=true (first)" >> $B/events.txt; fi
  if [ -z "$bc_at" ] && [ "$bc" = 1 ]; then bc_at=$t; echo "$t boot_completed" >> $B/events.txt; snap boot_completed; fi

  if [ $healthy = 0 ] && [ -n "$bc_at" ] && [ -n "$first_led" ] && [ $restarts = 0 ] &&
     [ "$gst" = running ] && [ $((t - bc_at)) -ge $HOLD_S ]; then
    healthy=1; healthy_at=$t
    echo "healthy at $t (boot_completed $bc_at, first ledOn $first_led)" > $B/HEALTHY
    echo "$t HEALTHY" >> $B/events.txt
    snap healthy
  fi

  if [ $s90 = 0 ] && [ $t -ge 90 ]; then s90=1; snap 90; fi
  if [ $s300 = 0 ] && [ $t -ge 300 ]; then s300=1; snap 300; fi
  if [ $dumped = 0 ] && [ $healthy = 1 ] && [ $((t - healthy_at)) -ge 60 ]; then
    dumped=1
    timeout 20 dumpsys power > $B/dumpsys_power.txt 2>&1
    timeout 20 dumpsys media.audio_flinger > $B/dumpsys_audio_flinger.txt 2>&1
  fi

  # USB stick: previous boots in one single attempt; this boot every 60 s while not healthy
  # (from t=120), once 90 s after healthy, and at the end.
  if [ $usb_early = 0 ] && [ $t -ge 20 ]; then
    usb_early=1
    usb_copy early
  fi
  if [ $healthy = 0 ] && [ $t -ge 120 ] && [ $((t - usb_last)) -ge 60 ]; then
    usb_last=$t
    tombs
    usb_copy now
  fi
  if [ $usb_hl = 0 ] && [ $healthy = 1 ] && [ $((t - healthy_at)) -ge 90 ]; then
    usb_hl=1
    usb_copy now
  fi

  # size guard: 40 MB for the whole folder; oldest boots go first
  if [ $((t - lastdu)) -ge 60 ]; then
    lastdu=$t
    for old in 3 2; do
      kb=$(du -sk $D 2>/dev/null | cut -f1)
      case "$kb" in ''|*[!0-9]*) kb=0 ;; esac
      if [ $kb -gt $MAX_KB ] && [ -d $D/boot.$old ]; then
        rm -rf $D/boot.$old
        echo "$t size cap: removed boot.$old (${kb} KB)" >> $B/events.txt
      fi
    done
  fi

  # flush to disk at most every 10 s (every 30 s after 5 min)
  if [ $t -lt 300 ]; then si=10; else si=30; fi
  if [ $((t - last_sync)) -ge $si ]; then share; sync; last_sync=$t; fi

  if [ $healthy = 1 ] && [ $((t - T0)) -ge $((WINDOW_MIN * 60)) ]; then break; fi
  if [ $((t - T0)) -ge $((FAIL_MAX_MIN * 60)) ]; then break; fi
  sleep 5
done

snap end
tombs
kill $LC 2>/dev/null
kill $DM 2>/dev/null
echo "$(date +%Y%m%d-%H%M%S) end   boot#$n healthy=$healthy boot_completed_at=$bc_at first_ledOn_at=$first_led gmpf_restarts=$restarts $FEAT" >> $D/history.txt
tail -n 300 $D/history.txt > $D/history.tmp 2>/dev/null && mv $D/history.tmp $D/history.txt
share
usb_copy now || sync

# ---- v6.5: power watch for the rest of this boot (two getprop calls every 5 s, nothing else) ----
echo "$LASTUP log window over: power watch only" >> $B/events.txt
while :; do
  now; t=$LASTUP
  hl=$(grep -c '' $D/history.txt 2>/dev/null)
  pwatch
  case "$hl" in ''|*[!0-9]*) hl=0 ;; esac
  if [ $hl -gt 400 ]; then tail -n 300 $D/history.txt > $D/history.tmp 2>/dev/null && mv $D/history.tmp $D/history.txt; share; fi
  if [ -n "$pw_due" ] || [ $((t - last_sync)) -ge 300 ]; then sync; last_sync=$t; fi
  sleep 5
done
exit 0
