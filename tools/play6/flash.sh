#!/bin/bash
# Flash a Lumen OS system image onto an XGIMI Play 6 (G0058, fastboot product mt5889).
# Writes ONLY the logical 'system' partition in super (fastbootd resizes it inside super's free space).
# Nothing else: no vbmeta (the Play 6 has none), no boot, recovery, MBOOT or vendor. It cannot wipe: XGIMI's
# fastbootd reports no partition-type, so `fastboot -w` answers "partition not found" (2026-10-13, plan
# review R-01). For a clean /data, do a Factory Reset in Settings after the first boot (XGIMI's recovery wipes
# /data and /cache), or the XGIMI USB force-flash before. Single slot, so there is no automatic rollback: the
# way back is XGIMI's USB force-flash, proven 2026-10-12 and 2026-10-13: a stick with an MBR table and ONE
# FAT32 partition holding the firmware .bin (XGIMI_DRAGONHEALTHY_XN03C.bin) plus an EMPTY factory.txt, no
# ._ files, in the USB-A port; power cable out, hold the projector's power button, plug the power in, keep
# holding ~5 s; about 5 min, wipes everything.
# --keep-data only changes the closing note (userdata is kept in every case).
# --gapps IMG   also the Google add-on partition lumen_gapps in super (tools/play6/gms/make_gapps_img.sh; PRIVATE):
#               created when missing, then flashed (fastboot resizes it to the image). Lumen updates never touch it.
# --recovery IMG  also the recovery partition, ONLY with an image recovery_trust.py made (XGIMI's recovery plus
#               the Lumen Play 6 OTA certificate; its SHA-256 must be one of RECOVERY_OK below). Owner decision D1.
# Run it only after the owner has said to flash.
#   tools/play6/flash.sh IMAGE [IP] [--keep-data] [--gapps IMG] [--recovery IMG]
#                                      IP: the projector's Wi-Fi address while Android runs (adb reboot
#                                      fastboot is sent there); omit it when the projector already is in
#                                      fastbootd. A USB cable must connect the projector to this Mac.
set -euo pipefail
IMG=${1:?usage: flash.sh IMAGE [IP] [--keep-data] [--gapps IMG] [--recovery IMG]}; shift; IP=; KEEP=0; GAPPS=; REC=
while [ $# -gt 0 ]; do
  case $1 in
    --keep-data) KEEP=1 ;;
    --gapps) GAPPS=${2:?--gapps IMG}; shift ;;
    --recovery) REC=${2:?--recovery IMG}; shift ;;
    -*) echo "ERROR: unknown option $1" >&2; exit 1 ;;
    *) IP=$1 ;;
  esac
  shift
done
# recovery_trust.py outputs: XGIMI V1.7.116 recovery + Lumen Play 6 OTA certificate, same layout as XGIMI's
RECOVERY_OK="e6e08333162442f2a721249fce3cf1b7ebe0241f42369d9efadeb3895cfe9a3e"
die() { echo "ERROR: $*" >&2; exit 1; }
[ -s "$IMG" ] || die "no image $IMG"
if [ -s "$IMG.sha256" ]; then
  want=$(awk '{print $1}' "$IMG.sha256"); got=$(shasum -a 256 "$IMG" | awk '{print $1}')
  [ "$want" = "$got" ] || die "image checksum mismatch ($got, expected $want)"
  echo "image checksum ok"
fi
size=$(stat -f%z "$IMG" 2>/dev/null || stat -c%s "$IMG")
gsize=0
if [ -n "$GAPPS" ]; then
  [ -s "$GAPPS" ] || die "no Google add-on image $GAPPS"
  if [ -s "$GAPPS.sha256" ]; then
    [ "$(awk '{print $1}' "$GAPPS.sha256")" = "$(shasum -a 256 "$GAPPS" | awk '{print $1}')" ] || die "add-on checksum mismatch"
  fi
  gsize=$(stat -f%z "$GAPPS" 2>/dev/null || stat -c%s "$GAPPS")
fi
if [ -n "$REC" ]; then
  [ -s "$REC" ] || die "no recovery image $REC"
  rs=$(shasum -a 256 "$REC" | awk '{print $1}')
  case " $RECOVERY_OK " in *" $rs "*) echo "recovery image $rs: a recovery_trust.py output" ;;
    *) die "recovery image $rs is not a known recovery_trust.py output: not flashed" ;; esac
fi

if [ -n "$IP" ]; then
  case "$IP" in *:*) ;; *) IP="$IP:5555" ;; esac
  adb connect "$IP" >/dev/null 2>&1 || true
  model=$(adb -s "$IP" shell getprop ro.boot.xgimi.modelname 2>/dev/null | tr -d '\r')
  [ "$model" = G0058 ] || die "the device at $IP is not a Play 6 (model '$model')"
  if adb -s "$IP" shell dumpsys media_session 2>/dev/null | grep -E 'state=PlaybackState \{state=3' >/dev/null; then
    die "something is playing on the projector; stop it first"
  fi
  echo "rebooting the Play 6 into fastbootd"
  adb -s "$IP" reboot fastboot
fi

dev=""
for i in $(seq 1 90); do
  dev=$(fastboot devices 2>/dev/null | awk '$2=="fastboot"{print $1; exit}')
  [ -n "$dev" ] && break; sleep 2
done
[ -n "$dev" ] || die "no fastboot device on USB (cable plugged into the projector's USB port?)"
gv() { fastboot -s "$dev" getvar "$1" 2>&1 | awk -v k="$1:" '$1==k{print $2; exit}'; }
[ "$(gv is-userspace)" = yes ] || die "$dev is not in fastbootd"
[ "$(gv product)" = mt5889 ] || die "$dev is not a Play 6 (product '$(gv product)')"
super=$(( $(gv partition-size:super) )); sys=$(( $(gv partition-size:system) )); ven=$(( $(gv partition-size:vendor) ))
# an add-on already in super keeps its space unless --gapps brings a bigger one
cur=0
[ "$(gv is-logical:lumen_gapps)" = yes ] && cur=$(( $(gv partition-size:lumen_gapps) ))
g=$(( gsize > cur ? gsize : cur ))
room=$(( super - ven - g - 4*1024*1024 ))       # keep 4 MB for super's metadata
echo "super $super, vendor $ven, system now $sys, image $size, add-on $g (now $cur), room for system $room"
[ "$size" -le "$room" ] || die "image too big for super"

echo "flashing system"
fastboot -s "$dev" flash system "$IMG"
if [ -n "$GAPPS" ]; then
  if [ "$(gv is-logical:lumen_gapps)" != yes ] && [ $KEEP = 1 ]; then
    echo "NOTE: Google is added to existing data: do a Factory reset after the first boot, or Google's default"
    echo "      permissions are never granted (Android grants them only on a first boot or a new fingerprint)."
  fi
  if [ "$(gv is-logical:lumen_gapps)" != yes ]; then
    echo "creating the logical partition lumen_gapps"
    fastboot -s "$dev" create-logical-partition lumen_gapps "$gsize"
  fi
  echo "flashing the Google add-on (lumen_gapps)"
  fastboot -s "$dev" flash lumen_gapps "$GAPPS"
fi
if [ -n "$REC" ]; then
  [ "$(( $(gv partition-size:recovery) ))" -ge "$(stat -f%z "$REC" 2>/dev/null || stat -c%s "$REC")" ] || die "recovery image too big"
  echo "flashing recovery (Lumen OTA certificate added)"
  fastboot -s "$dev" flash recovery "$REC"
fi
if [ $KEEP = 1 ]; then
  echo "keeping userdata and cache"
else
  echo "NOTE: XGIMI's fastbootd cannot wipe userdata and cache (fastboot -w: partition not found)."
  echo "      For a clean start: after the first boot, Settings > Device Preferences > Reset > Factory reset"
  echo "      (XGIMI's recovery wipes them; docs/play6/impl/installer-design.md 6.5 has the adb root way)."
fi
fastboot -s "$dev" reboot
echo "done. Unplug the USB-C cable from this Mac now: while it stays in, the Play 6 does not restart"
echo "(if the projector stays dark, press its power button). The first boot takes a few minutes."
