#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer, XGIMI Play 6, 'gapps --zip FILE' (lumen-install.sh / lumen-install.ps1): runs ON the
# projector as root (adb root over Wi-Fi), pushed to /data/local/tmp/lumen-gapps/fill.sh together with
# allow.txt (the installer's lib/play6/gapps_allow.txt), lumen-gapps.xml (lib/play6/lumen-gapps.xml) and, for
# 'fill', the user's zip (gapps.zip). Play 6 fork of installer/lib/gapps_fill.sh (the Z9X one is unchanged).
# The logical partition lumen_gapps (in super, no slot suffix, created by the installer in fastboot mode) is
# shown inside /system at every start by the image's /system/etc/xgimi/lumen_gapps.sh, which takes only files
# in its listed folders plus the file gmsversion. This script writes exactly that layout, the same files as
# tools/play6/gms/make_gapps_img.sh made for the owner's unit:
#   sh fill.sh pre SHA256 BYTES  nothing written: root, Lumen OS for the Play 6 with the add-on support, the
#                                tools (unzip, mke2fs, e2fsck), the zip and the sysconfig layer in allow.txt,
#                                BYTES (the zip) + 64 MiB free in /data
#   sh fill.sh off SHA256        writes only /cache/lumen_gapps_off: lumen_gapps.sh then leaves the partition
#                                unmounted at the next starts, so that it can be formatted
#   sh fill.sh fill SHA256       the pushed zip must have that sha256 and lumen_gapps must not be mounted;
#                                mke2fs (ext4 without a journal, 4 KiB blocks, label lumen_gapps), then the
#                                allow-listed files of that zip (unzip -p of each member into its own path:
#                                nothing else of the zip is written, no member name chooses a path), each
#                                checked by sha256 and size, etc/sysconfig/lumen-gapps.xml and gmsversion;
#                                modes 0755 / 0644, owner root, label system_file; unmounted, e2fsck clean;
#                                then /cache/lumen_gapps_off is removed (the next start mounts it)
# Allow-list paths are paths inside lumen_gapps; the zip member is system/<path> for product/ and system_ext/,
# system/system/<path> for the rest (app/, etc/...), as tools/play6/gapps_allow.py makes them.
# Output: 'OK ...' or 'FAIL <why>' (exit 1). Writes only lumen_gapps and /cache/lumen_gapps_off.
set -u
R=${LUMEN_ROOT:-}      # empty on the projector; tools/ota/test/installer_play6_gapps.sh points it at a temp dir
W=$R/data/local/tmp/lumen-gapps
Z=$W/gapps.zip
ALLOW=$W/allow.txt
SC=$W/lumen-gapps.xml
OFF=$R/cache/lumen_gapps_off
M=$R/mnt/lumen_gapps_fill
dev=$R/dev/block/mapper/lumen_gapps
# the folders lumen_gapps.sh overlays (its DIRS): every file of the partition but gmsversion lies in one
DIRS="app priv-app etc/permissions etc/sysconfig etc/default-permissions
      product/app product/priv-app product/overlay product/etc/permissions product/etc/sysconfig
      product/etc/default-permissions system_ext/app system_ext/priv-app system_ext/etc/permissions"
mode=${1-}; want=${2-}
mounted=0
die() {
  if [ "$mounted" = 1 ]; then sync; umount "$M" 2>/dev/null; fi
  rmdir "$M" 2>/dev/null
  echo "FAIL $*"
  exit 1
}
case "$want" in *[!0-9a-f]*|'') die "usage: fill.sh pre|off|fill SHA256 [BYTES]" ;; esac
[ ${#want} = 64 ] || die "bad sha256"
[ -n "$R" ] || [ "$(id -u)" = 0 ] || die "not root"
[ "$(getprop ro.lumen.device)" = play6 ] || die "not Lumen OS for the XGIMI Play 6"
[ -f "$R/system/etc/xgimi/lumen_gapps.sh" ] || die "this Lumen OS has no Google services add-on support"
[ -f "$ALLOW" ] && [ -f "$SC" ] || die "allow.txt or lumen-gapps.xml was not pushed"
zl=$(grep "^zip=$want " "$ALLOW" | head -n 1)
[ -n "$zl" ] || die "this zip is not in the installer's allow-list"
field() { echo "$zl" | tr ' ' '\n' | sed -n "s/^$1=//p" | head -n 1; }
gv=$(field gmsversion); sc=$(field sysconfig)
[ -n "$gv" ] && [ ${#sc} = 64 ] || die "the zip line has no gmsversion or sysconfig"
[ "$(sha256sum "$SC" | cut -d' ' -f1)" = "$sc" ] || die "lumen-gapps.xml is not the allow-listed one"
lines=$(awk -v z="zip=$want" '$1 ~ /^zip=/ { on = ($1 == z); next } on && NF == 3 && $1 !~ /^#/ { print $1, $2, $3 }' "$ALLOW")
[ -n "$lines" ] || die "no files for this zip"

case "$mode" in
pre)
  for t in unzip mke2fs e2fsck sha256sum; do command -v "$t" >/dev/null 2>&1 || die "no $t"; done
  case "${3-}" in ''|*[!0-9]*) die "pre needs BYTES" ;; esac
  kb=$(df -k "$W" 2>/dev/null | awk 'NR == 2 { print $4 }')
  case "$kb" in ''|*[!0-9]*) die "cannot read the free space of /data" ;; esac
  [ "$kb" -ge $(( $3 / 1024 + 65536 )) ] || die "not enough space in /data: ${kb} KiB free"
  echo "OK pre $(echo "$lines" | wc -l | tr -d ' ') files"
  ;;
off)
  mkdir -p "$R/cache" 2>/dev/null
  echo "lumen-install gapps" > "$OFF" || die "cannot write $OFF"
  sync
  echo "OK off"
  ;;
fill)
  [ -b "$dev" ] || { [ -n "$R" ] && [ -e "$dev" ]; } || die "no lumen_gapps partition"
  if grep -q " /mnt/lumen_gapps " "$R/proc/mounts" 2>/dev/null; then die "lumen_gapps is in use (mounted)"; fi
  got=$(sha256sum "$Z" 2>/dev/null | cut -d' ' -f1)
  [ "$got" = "$want" ] || die "the zip on the projector has sha256 '$got'"
  blockdev --setrw "$dev" 2>/dev/null
  # -F: no question about an old file system on it (a mounted one is still refused)
  mke2fs -F -q -t ext4 -b 4096 -I 256 -m 0 -O ^has_journal,^metadata_csum -L lumen_gapps "$dev" >/dev/null 2>&1 \
    || die "mke2fs lumen_gapps"
  mkdir -p "$M" || die "mkdir $M"
  mount -t ext4 -o rw,nosuid,nodev "$dev" "$M" || die "mount lumen_gapps"
  mounted=1
  [ -z "$(ls -A "$M" | grep -vx 'lost+found')" ] || die "lumen_gapps is not empty after mke2fs"
  n=0
  while read -r h s p; do
    case "$p" in ''|/*|*/|*//*|*..*|gmsversion|etc/sysconfig/lumen-gapps.xml) die "bad path $p" ;; esac
    ok=0
    for d in $DIRS; do case "$p" in "$d"/*) ok=1 ;; esac; done
    [ $ok = 1 ] || die "bad path $p"
    case "$p" in product/*|system_ext/*) mem=system/$p ;; *) mem=system/system/$p ;; esac
    mkdir -p "$M/${p%/*}" || die "mkdir ${p%/*}"
    unzip -p "$Z" "$mem" > "$M/$p" || die "unzip $mem"
    [ "$(sha256sum "$M/$p" | cut -d' ' -f1)" = "$h" ] || die "sha256 of $p"
    [ "$(wc -c < "$M/$p" | tr -d ' ')" = "$s" ] || die "size of $p"
    n=$((n + 1))
  done <<EOF
$lines
EOF
  mkdir -p "$M/etc/sysconfig" || die "mkdir etc/sysconfig"
  cat "$SC" > "$M/etc/sysconfig/lumen-gapps.xml" || die "lumen-gapps.xml"
  [ "$(sha256sum "$M/etc/sysconfig/lumen-gapps.xml" | cut -d' ' -f1)" = "$sc" ] || die "sha256 of lumen-gapps.xml"
  echo "$gv" > "$M/gmsversion" || die "gmsversion"
  find "$M" -mindepth 1 -type d ! -path "$M/lost+found" -exec chmod 0755 {} + || die "chmod"
  find "$M" -type f -exec chmod 0644 {} + || die "chmod"
  chown -R root:root "$M" || die "chown"
  chcon -R "u:object_r:system_file:s0" "$M" 2>/dev/null
  sync
  umount "$M" || die "umount lumen_gapps"
  mounted=0
  rmdir "$M" 2>/dev/null
  e2fsck -fn "$dev" >/dev/null 2>&1 || die "e2fsck found problems on lumen_gapps"
  rm -f "$OFF" || die "cannot remove $OFF"
  sync
  echo "OK fill $n files"
  ;;
*) die "usage: fill.sh pre|off|fill SHA256 [BYTES]" ;;
esac
exit 0
