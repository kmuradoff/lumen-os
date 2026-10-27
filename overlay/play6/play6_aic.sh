#!/system/bin/sh
# Lumen OS on the XGIMI Play 6: load the AIC8800D80X2 Wi-Fi/BT modules, only on units that have that chip
# (play6_aic.rc). mksh + toybox only. Writes nothing but kernel modules into the kernel and two properties:
#   sys.lumen.wifi_chip  aic | mediatek | none   (what the USB bus shows)
#   sys.lumen.aic        off | ready | failed:<step>
# and, on an AIC unit only, one bind mount: AIC's Bluetooth vendor library (built by tools/play6/aic, it opens
# /dev/aicbt_dev and reads /system/etc/bluetooth/aicbt.conf) over MediaTek's /vendor/lib/libbt-vendor.so, which the
# stock HAL (android.hardware.bluetooth@1.0-impl, user bluetooth) dlopens when Bluetooth is switched on.
# The chip powers up as 368b:8d90; aic_load_fw sends the firmware from FW (read straight from the files, not
# through firmware_class) and the chip comes back as 368b:8d91; then aic_btusb (/dev/aicbt_dev) and
# aic8800_fdrv (wlan0 + p2p0, which the stock wpa_supplicant -N -ip2p0 needs). aic8800_fdrv uses symbols of
# aic_load_fw, cfg80211 and firmware_class: the vendor loads the last two (init.fusion.rc), so wait for them.
# R is empty on the device (init gives services a clean environment); tools/play6/aic/test_play6_aic.sh sets it.
M=$R/system/etc/aic8800/modules
FW=/system/etc/aic8800/firmware
BTLIB=$R/system/lib/libbt-vendor-aic.so
VBTLIB=$R/vendor/lib/libbt-vendor.so

say() { log -t lumen_aic "$*"; }
usb_ids() {
  for d in $R/sys/bus/usb/devices/*; do
    [ -f "$d/idVendor" ] && echo "$(cat "$d/idVendor"):$(cat "$d/idProduct")"
  done
}
has() { usb_ids | grep -qx "$1"; }
loaded() { grep -q "^$1 " $R/proc/modules; }
fail() { setprop sys.lumen.aic "failed:$1"; say "failed: $1"; exit 0; }

# 1. which chip: wait up to 20 s for the internal USB Wi-Fi/BT device to enumerate
chip=none
i=0
while [ $i -lt 20 ]; do
  ids=$(usb_ids)
  case "$ids" in
    *368b:8d90*|*368b:8d91*) chip=aic; break ;;
    *0e8d:*) chip=mediatek; break ;;
  esac
  sleep 1; i=$((i + 1))
done
setprop sys.lumen.wifi_chip $chip
if [ $chip != aic ]; then
  setprop sys.lumen.aic off
  say "chip $chip: nothing to load"
  exit 0
fi
say "AIC8800D80X2 found after $i s"

# 1b. Bluetooth library, before the framework first switches Bluetooth on (the HAL loads it only then)
if [ -f $BTLIB ] && ! grep -q " /vendor/lib/libbt-vendor.so " $R/proc/mounts; then
  mount -o bind $BTLIB $VBTLIB && say "AIC libbt-vendor in place" || say "libbt-vendor bind failed (Wi-Fi goes on)"
fi

# 2. firmware into the chip (368b:8d90 -> 368b:8d91)
if ! loaded aic_load_fw; then
  insmod $M/aic_load_fw.ko aic_fw_path=$FW || fail aic_load_fw
fi
i=0
while [ $i -lt 30 ] && ! has 368b:8d91; do sleep 1; i=$((i + 1)); done
has 368b:8d91 || fail firmware

# 3. Bluetooth node, then Wi-Fi once the vendor's cfg80211 and firmware_class are in
loaded aic_btusb || insmod $M/aic_btusb.ko || say "aic_btusb did not load (Wi-Fi goes on)"
if [ -e $R/dev/aicbt_dev ]; then   # the stock HAL runs as user bluetooth
  chown bluetooth:bluetooth $R/dev/aicbt_dev; chmod 0660 $R/dev/aicbt_dev
fi
i=0
while [ $i -lt 30 ] && ! { [ -d $R/sys/module/cfg80211 ] && [ -d $R/sys/module/firmware_class ]; }; do
  sleep 1; i=$((i + 1))
done
[ -d $R/sys/module/cfg80211 ] || fail cfg80211
if ! loaded aic8800_fdrv; then
  insmod $M/aic8800_fdrv.ko || fail aic8800_fdrv
fi
i=0
while [ $i -lt 10 ] && [ ! -d $R/sys/class/net/wlan0 ]; do sleep 1; i=$((i + 1)); done
[ -d $R/sys/class/net/wlan0 ] || fail wlan0
setprop sys.lumen.aic ready
say "ready: wlan0$( [ -d $R/sys/class/net/p2p0 ] && echo ' p2p0')$( [ -e $R/dev/aicbt_dev ] && echo ' aicbt_dev')"
