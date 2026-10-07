#!/system/bin/sh
# Z9X: временный журнал загрузки на флешку. Каждые 5 с: dmesg, свойства, процессы,
# этапы загрузки MediaTek и logcat. Работает 15 минут.
M=/dev/z9xlog
K=/vendor/lib/modules
mkdir -p $M
for i in $(seq 1 150); do
  if [ ! -d /sys/module/xhci_mtk_hcd_tv ]; then
    insmod $K/phy-mtk-tphy-tv.ko pm_degrad=1 2>/dev/null
    insmod $K/mtu3.ko mtu3_dev_en=1 2>/dev/null
    insmod $K/xhci-mtk-hcd-tv.ko 2>/dev/null
  fi
  for d in /dev/block/sd?1 /dev/block/sd?; do
    [ -b "$d" ] && mount -t vfat -o rw,noatime "$d" $M 2>/dev/null && break 2
  done
  sleep 2
done
grep -q " $M " /proc/mounts || exit 1
n=1
while [ -e $M/z9x_boot_$n ]; do n=$((n + 1)); done
D=$M/z9x_boot_$n
mkdir -p $D
cp -r /sys/fs/pstore $D/pstore_prev 2>/dev/null
cat /proc/cmdline > $D/cmdline.txt
i=0
while [ $i -lt 180 ]; do
  dmesg > $D/dmesg.txt 2>&1
  getprop > $D/props.txt 2>&1
  ps -A -o PID,PPID,S,WCHAN,TIME,NAME,ARGS > $D/ps.txt 2>&1
  cat /proc/boottime > $D/boottime.txt 2>&1
  logcat -d -b all > $D/logcat.txt 2>&1
  echo "$i $(cut -d' ' -f1 /proc/uptime) boot_completed=$(getprop sys.boot_completed)" >> $D/progress.txt
  sync
  i=$((i + 1))
  sleep 5
done
