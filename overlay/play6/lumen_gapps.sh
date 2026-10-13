#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS on the XGIMI Play 6: show the Google add-on partition (lumen_gapps in super, made by
# tools/play6/gms/make_gapps_img.sh and written once by fastbootd) inside /system with read-only overlay
# mounts: for every listed folder of the add-on, /system/<dir> = add-on files over Lumen's. Nothing is written.
# All or nothing: the vendor enforces privileged-permission allow-lists, so a Google priv-app without its
# allow-list (one overlay mounted, another not) would stop the boot in PackageManager. Any problem leaves
# Google out completely and says why in sys.lumen.gapps.
#   none          no lumen_gapps partition (the no-Google setup)
#   off           rescue switch: the file /cache/lumen_gapps_off exists (adb, or fastbootd + recovery wipe cache)
#   mount_failed  the partition does not mount
#   bad:<what>    a file outside the listed folders, a folder /system lacks, or an overlay that failed
#   mounted:<n>   Google is in, n overlays
G=/dev/block/mapper/lumen_gapps
M=/mnt/lumen_gapps
DIRS="app priv-app etc/permissions etc/sysconfig etc/default-permissions
      product/app product/priv-app product/overlay product/etc/permissions product/etc/sysconfig
      product/etc/default-permissions system_ext/app system_ext/priv-app system_ext/etc/permissions"
if [ ! -b "$G" ]; then
  setprop sys.lumen.gapps none
  exit 0
fi
if [ -e /cache/lumen_gapps_off ]; then
  setprop sys.lumen.gapps off
  exit 0
fi
mkdir -p "$M"
if ! mount -t ext4 -o ro,nodev,nosuid,noatime "$G" "$M"; then
  setprop sys.lumen.gapps mount_failed
  exit 0
fi
give_up() {
  log -t lumen_gapps "Google add-on left out: $1"
  umount "$M"
  setprop sys.lumen.gapps "bad:$1"
  exit 0
}
# every file of the add-on lies in a listed folder (gmsversion aside), and /system has every folder it fills
for p in $(cd "$M" && find . -type f | sed 's#^\./##'); do
  ok=0
  [ "$p" = gmsversion ] && ok=1
  for d in $DIRS; do
    case "$p" in "$d"/*) ok=1; break ;; esac
  done
  [ $ok = 1 ] || give_up "$p"
done
for d in $DIRS; do
  [ -d "$M/$d" ] && [ ! -d "/system/$d" ] && give_up "/system/$d missing"
done
done_list=""
n=0
for d in $DIRS; do
  [ -d "$M/$d" ] || continue
  if mount -t overlay overlay -o "ro,lowerdir=$M/$d:/system/$d" "/system/$d"; then
    done_list="/system/$d $done_list"
    n=$((n+1))
  else
    for x in $done_list; do umount "$x"; done
    give_up "overlay $d"
  fi
done
[ -f "$M/gmsversion" ] && setprop ro.com.google.gmsversion "$(cat "$M/gmsversion")"
setprop sys.lumen.gapps "mounted:$n"
