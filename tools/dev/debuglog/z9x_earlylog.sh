#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS DIAG images only (never in a release image): boot log on a USB stick from the very start
# of the boot, for a boot that never reaches 'on boot' (no adb, no z9x_diag). Started at 'on init' by
# z9x_earlylog.rc. It loads the USB host modules itself (the vendor loads them only at
# load_bpf_programs), mounts the first FAT/exFAT stick and writes ONLY into
# <stick>/z9x_diag_logs/early/boot_N/ (N = 1..40; nothing else on the stick is read, changed or deleted).
#   once:   pstore of the previous boot (its logcat and kernel log: why it ended), cmdline, PM51
#           boot_reason / wakeup_reason, dmesg at the start
#   rounds: every 3 s (10 s after 3 min): dmesg, getprop, ps, /proc/boottime, logcat, a one-line
#           progress record (uptime, init stage, apexd / odsign / zygote / pwmchip8 / boot_completed)
#   stops:  120 s after sys.boot_completed=1, or after 15 min
# Read-only towards the system: no setprop, no service control, no write outside the stick folder.
M=/dev/z9x_earlylog
K=/vendor/lib/modules
mkdir -p $M
mounted=0
for i in $(seq 1 120); do
  if [ ! -d /sys/module/xhci_mtk_hcd_tv ]; then
    insmod $K/phy-mtk-tphy-tv.ko pm_degrad=1 2>/dev/null
    insmod $K/mtu3.ko mtu3_dev_en=1 2>/dev/null
    insmod $K/xhci-mtk-hcd-tv.ko 2>/dev/null
  fi
  for d in /dev/block/sd?1 /dev/block/sd?; do
    [ -b "$d" ] || continue
    if mount -t vfat -o rw,noatime "$d" $M 2>/dev/null || mount -t exfat -o rw,noatime "$d" $M 2>/dev/null; then
      mounted=1; break 2
    fi
  done
  sleep 1
done
[ $mounted = 1 ] || exit 0
B=$M/z9x_diag_logs/early
mkdir -p $B || { umount $M; exit 0; }
n=1
while [ -e $B/boot_$n ] && [ $n -le 40 ]; do n=$((n + 1)); done
[ $n -le 40 ] || { umount $M; exit 0; }
D=$B/boot_$n
mkdir -p $D
cp -r /sys/fs/pstore $D/pstore_prev 2>/dev/null
cat /proc/cmdline > $D/cmdline.txt 2>&1
{
  echo "boot_reason=$(cat /sys/mtk_pm/boot_reason 2>/dev/null)"
  echo "wakeup_reason=$(cat /sys/mtk_pm/wakeup_reason/name 2>/dev/null)"
  echo "slot=$(getprop ro.boot.slot_suffix) build=$(getprop ro.build.display.id)"
  echo "started_at_uptime=$(cut -d' ' -f1 /proc/uptime)"
} > $D/info.txt
dmesg > $D/dmesg_start.txt 2>&1
sync
i=0; done_at=
while [ $i -lt 400 ]; do
  up=$(cut -d' ' -f1 /proc/uptime)
  dmesg > $D/dmesg.txt 2>&1
  getprop > $D/props.txt 2>&1
  ps -A -o PID,PPID,S,WCHAN,TIME,NAME,ARGS > $D/ps.txt 2>&1
  cat /proc/boottime > $D/boottime.txt 2>&1
  logcat -d -b all > $D/logcat.txt 2>&1
  pwm=0; [ -e /sys/class/pwm/pwmchip8 ] && pwm=1
  echo "$up stage=$(tail -n 1 /proc/boottime 2>/dev/null | cut -c1-60) apexd=$(getprop init.svc.apexd)/$(getprop apexd.status) odsign=$(getprop init.svc.odsign)/key$(getprop odsign.key.done)/ver$(getprop odsign.verification.done) uv=$(getprop init.svc.update_verifier) zygote=$(getprop init.svc.zygote) gmpf=$(getprop init.svc.gmpf_main) pwmchip8=$pwm ss=$(getprop sys.system_server.start_count) boot_completed=$(getprop sys.boot_completed)" >> $D/progress.txt
  if [ -d /data/misc ]; then
    { ls -la /data/misc/odsign /data/misc/apexdata/com.android.art/dalvik-cache /data/apex/decompressed /data/apex/active; ls -laR /metadata; } > $D/dirs.txt 2>&1
  fi
  sync
  if [ "$(getprop sys.boot_completed)" = 1 ]; then
    [ -n "$done_at" ] || done_at=$i
    [ $((i - done_at)) -ge 20 ] && break
  fi
  i=$((i + 1))
  if [ $i -lt 60 ]; then sleep 3; else sleep 10; fi
done
sync
umount $M 2>/dev/null
exit 0
