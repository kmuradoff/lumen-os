#!/bin/bash
# Host test of overlay/play6/play6_aic.sh on a fake root: the USB bus, /proc/modules and /sys are files in a
# temp folder, insmod / setprop / log / sleep are stubs. Cases: MediaTek unit, AIC unit, no chip, AIC whose
# vendor never loads cfg80211. Run: bash tools/play6/aic/test_play6_aic.sh
set -u
G=$(cd "$(dirname "$0")/../../.." && pwd)
SCRIPT=$G/overlay/play6/play6_aic.sh
fails=0; n=0

setup() {   # $1 = usb id of the internal chip ("" = none), $2 = 1 when the vendor modules are in
  R=$(mktemp -d)
  mkdir -p "$R/sys/bus/usb/devices" "$R/sys/module" "$R/sys/class/net" "$R/proc" "$R/dev" "$R/bin" \
    "$R/system/lib/modules/aic"
  : > "$R/proc/modules"; : > "$R/props"; : > "$R/log"
  mkdir -p "$R/sys/bus/usb/devices/usb1"; echo 1d6b > "$R/sys/bus/usb/devices/usb1/idVendor"
  echo 0002 > "$R/sys/bus/usb/devices/usb1/idProduct"
  if [ -n "$1" ]; then
    mkdir -p "$R/sys/bus/usb/devices/3-1"
    echo "${1%:*}" > "$R/sys/bus/usb/devices/3-1/idVendor"; echo "${1#*:}" > "$R/sys/bus/usb/devices/3-1/idProduct"
  fi
  [ "$2" = 1 ] && mkdir -p "$R/sys/module/cfg80211" "$R/sys/module/firmware_class"
  cat > "$R/bin/insmod" <<EOF
#!/bin/bash
m=\$(basename "\$1" .ko); echo "\$m 1 0 - Live 0x0" >> "$R/proc/modules"; echo "insmod \$*" >> "$R/log"
case \$m in
  aic_load_fw) echo 8d91 > "$R/sys/bus/usb/devices/3-1/idProduct" ;;
  aic_btusb) touch "$R/dev/aicbt_dev" ;;
  aic8800_fdrv) mkdir -p "$R/sys/class/net/wlan0" "$R/sys/class/net/p2p0" ;;
esac
EOF
  printf '#!/bin/bash\necho "$1=$2" >> "%s/props"\n' "$R" > "$R/bin/setprop"
  printf '#!/bin/bash\nshift 2; echo "log $*" >> "%s/log"\n' "$R" > "$R/bin/log"
  printf '#!/bin/bash\nexit 0\n' > "$R/bin/sleep"
  chmod 755 "$R/bin/"*
}
run() { R=$R PATH="$R/bin:$PATH" sh "$SCRIPT"; }
prop() { grep "^$1=" "$R/props" | tail -1 | cut -d= -f2-; }
eq() {
  n=$((n + 1))
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: want '$2', got '$3'"; fails=$((fails + 1)); fi
}

setup 0e8d:7663 1; run
eq "MediaTek unit: chip" mediatek "$(prop sys.lumen.wifi_chip)"
eq "MediaTek unit: aic off" off "$(prop sys.lumen.aic)"
eq "MediaTek unit: nothing loaded" 0 "$(grep -c insmod "$R/log")"
rm -rf "$R"

setup 368b:8d90 1; run
eq "AIC unit: chip" aic "$(prop sys.lumen.wifi_chip)"
eq "AIC unit: ready" ready "$(prop sys.lumen.aic)"
eq "AIC unit: load order" "aic_load_fw aic_btusb aic8800_fdrv" "$(awk '{print $1}' "$R/proc/modules" | xargs)"
eq "AIC unit: firmware path" 1 "$(grep -c 'aic_load_fw.ko aic_fw_path=/system/etc/firmware/aic8800d80x2' "$R/log")"
eq "AIC unit: wlan0" yes "$([ -d "$R/sys/class/net/wlan0" ] && echo yes)"
run   # a second start loads nothing twice
eq "AIC unit: second run loads nothing again" 3 "$(grep -c insmod "$R/log")"
rm -rf "$R"

setup 368b:8d91 1; run
eq "AIC already with firmware: ready" ready "$(prop sys.lumen.aic)"
rm -rf "$R"

setup "" 1; run
eq "no chip: none" none "$(prop sys.lumen.wifi_chip)"
eq "no chip: nothing loaded" 0 "$(grep -c insmod "$R/log")"
rm -rf "$R"

setup 368b:8d90 0; run
eq "AIC, vendor without cfg80211: fails cleanly" failed:cfg80211 "$(prop sys.lumen.aic)"
eq "AIC, vendor without cfg80211: no Wi-Fi module" 0 "$(grep -c 'insmod .*aic8800_fdrv' "$R/log")"
rm -rf "$R"

echo; [ $fails = 0 ] && echo "OK: $n passed" || { echo "FAILED: $fails of $n"; exit 1; }
