#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer, 'gapps --zip FILE' (lumen-install.sh / lumen-install.ps1): runs ON the projector as
# root (adb root on Lumen OS without Google), pushed to /data/local/tmp/lumen-gapps/fill.sh next to the
# user's zip (gapps.zip). The allow-list is the RUNNING image's /system/etc/z9x/gapps_allow.txt: the very
# lines z9x_gapps.sh checks the partition against at every start.
#   sh fill.sh pre SHA256 BYTES  before anything is written: root, Lumen OS without Google with the add-on
#                                support, unzip, the zip in the allow-list, BYTES free in /data/local/tmp
#   sh fill.sh fill SHA256       the pushed zip must have that sha256; then fills the freshly formatted,
#                                empty z9x_gapps<slot>: exactly the allow-listed files of that zip (unzip -p
#                                of each member into its own path: nothing else of the zip is written, no
#                                member name chooses a path), each checked by sha256 and size, then
#                                MANIFEST (the zip line's sha256 and name, and the file lines), modes 0755 /
#                                0644, owner root, label system_file; unmounted cleanly (a clean journal:
#                                z9x_gapps.sh mounts it read-only)
# Output: 'OK ...' or 'FAIL <why>' (exit 1). Writes only that partition (nothing in 'pre').
set -u
R=${Z9X_ROOT:-}        # empty on the projector; tools/ota/test/installer_gapps.sh points it at a temp dir
W=$R/data/local/tmp/lumen-gapps
Z=$W/gapps.zip
ALLOW=$R/system/etc/z9x/gapps_allow.txt
M=$R/mnt/z9x_gapps_fill
mode=${1-}; want=${2-}
cur=$(getprop ro.boot.slot_suffix)
dev=$R/dev/block/mapper/z9x_gapps$cur
mounted=0
die() {
  if [ "$mounted" = 1 ]; then sync; umount "$M" 2>/dev/null; fi
  rmdir "$M" 2>/dev/null
  echo "FAIL $*"
  exit 1
}
case "$want" in *[!0-9a-f]*|'') die "usage: fill.sh pre|fill SHA256 [BYTES]" ;; esac
[ ${#want} = 64 ] || die "bad sha256"
[ -n "$R" ] || [ "$(id -u)" = 0 ] || die "not root"
[ "$(getprop ro.z9x.gms)" = 0 ] || die "not Lumen OS without Google"
[ -f "$ALLOW" ] && [ -f "$R/system/etc/z9x/z9x_gapps.sh" ] || die "this Lumen OS has no Google services add-on support"
zl=$(grep "^zip=$want " "$ALLOW" | head -n 1)
[ -n "$zl" ] || die "this zip is not in the projector's gapps_allow.txt"
lines=$(awk -v z="zip=$want" '$1 ~ /^zip=/ { on = ($1 == z); next } on && NF == 3 && $1 !~ /^#/ { print $1, $2, $3 }' "$ALLOW")
[ -n "$lines" ] || die "no files for this zip"

case "$mode" in
pre)
  command -v unzip >/dev/null 2>&1 || die "no unzip"
  case "${3-}" in ''|*[!0-9]*) die "pre needs BYTES" ;; esac
  mkdir -p "$W" || die "mkdir $W"
  kb=$(df -k "$W" 2>/dev/null | awk 'NR == 2 { print $4 }')
  case "$kb" in ''|*[!0-9]*) die "cannot read the free space of /data" ;; esac
  [ "$kb" -ge $(( $3 / 1024 + 65536 )) ] || die "not enough space in /data: ${kb} KiB free"
  echo "OK pre $(echo "$lines" | wc -l | tr -d ' ') files"
  ;;
fill)
  case "$cur" in _a|_b) ;; *) die "no slot suffix" ;; esac
  [ -b "$dev" ] || { [ -n "$R" ] && [ -d "$dev" ]; } || die "no z9x_gapps$cur"
  got=$(sha256sum "$Z" 2>/dev/null | cut -d' ' -f1)
  [ "$got" = "$want" ] || die "the zip on the projector has sha256 '$got'"
  mkdir -p "$M" || die "mkdir $M"
  blockdev --setrw "$dev" 2>/dev/null
  mount -t ext4 -o rw,nosuid,nodev "$dev" "$M" || die "mount z9x_gapps$cur"
  mounted=1
  [ -z "$(ls -A "$M" | grep -vx 'lost+found')" ] || die "z9x_gapps$cur is not empty (it must be formatted first)"
  n=0
  while read -r h s p; do
    case "$p" in ''|/*|*/|*//*|*..*) die "bad path $p" ;; esac
    mkdir -p "$M/${p%/*}" || die "mkdir ${p%/*}"
    unzip -p "$Z" "system/$p" > "$M/$p" || die "unzip system/$p"
    [ "$(sha256sum "$M/$p" | cut -d' ' -f1)" = "$h" ] || die "sha256 of $p"
    [ "$(wc -c < "$M/$p" | tr -d ' ')" = "$s" ] || die "size of $p"
    n=$((n + 1))
  done <<EOF
$lines
EOF
  {
    echo "# Lumen OS: Google services add-on (lumen-install gapps). z9x_gapps.sh checks it at every start"
    echo "# against /system/etc/z9x/gapps_allow.txt: do not edit."
    echo "$zl" | cut -d' ' -f1,2
    echo "$lines"
  } > "$M/MANIFEST.tmp" || die "MANIFEST"
  mv "$M/MANIFEST.tmp" "$M/MANIFEST" || die "MANIFEST"
  find "$M" -mindepth 1 -type d ! -path "$M/lost+found" -exec chmod 0755 {} + || die "chmod"
  find "$M" -type f -exec chmod 0644 {} + || die "chmod"
  chown -R root:root "$M" || die "chown"
  chcon -R "u:object_r:system_file:s0" "$M" 2>/dev/null
  sync
  umount "$M" || die "umount z9x_gapps$cur"
  mounted=0
  rmdir "$M" 2>/dev/null
  echo "OK fill $n files"
  ;;
*) die "usage: fill.sh pre|fill SHA256 [BYTES]" ;;
esac
exit 0
