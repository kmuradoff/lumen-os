#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS PUBLIC images only (image path /system/etc/z9x/z9x_blobs.sh 0755; z9x_blobs.rc runs it as
# an exec at post-fs-data, before the media services of class main start).
# The owner's private image keeps the MediaTek Codec2 libs inside /system and does not ship this.
#
# The user's own MediaTek Codec2 libs live in the logical partition z9x_blobs<slot> in super (made
# by the installer from the user's own stock system: ota/SPEC.md 3.6). They survive OTAs (VAB renames
# untouched dynamic partitions with the same extents) and factory resets (not in /data).
#   1. read the partition (dm device mapped by first-stage init; else its extents from lpdump),
#   2. untar it into a private 4 MiB tmpfs /mnt/z9x_blobs,
#   3. accept only files whose sha256 is in /system/etc/z9x/blobs_allow.txt,
#   4. label them system_lib_file and bind-mount each over its 0-byte placeholder in /system.
# Result: sys.z9x.blobs = ok | missing | bad:<file> | partial, sys.z9x.blobs.set = <set id>.
# Without the partition the system boots normally; only DRM-secure video fails. Never writes any
# partition; reads at most 4 MiB.
TAG=z9x_blobs
ALLOW=/system/etc/z9x/blobs_allow.txt
M=/mnt/z9x_blobs
say() { log -t "$TAG" "$*"; }
fail() { setprop sys.z9x.blobs "$1"; say "$1${2:+: $2}"; exit 0; }

cur=$(getprop ro.boot.slot_suffix)
name=z9x_blobs$cur
dev=/dev/block/mapper/$name
[ "$(getprop sys.z9x.blobs)" = ok ] && exit 0

mkdir -p $M || fail bad:mkdir
mount -t tmpfs -o mode=0750,uid=0,gid=0,size=4m tmpfs $M 2>/dev/null || true
img=$M/.img
if [ -b "$dev" ]; then
  timeout 5 dd if="$dev" of=$img bs=65536 count=64 2>/dev/null || fail bad:read "$dev"
else
  # not mapped: find its extents in the super metadata of this slot ("  0 .. 8191 linear super 2048")
  case "$cur" in _a) sn=0 ;; _b) sn=1 ;; *) fail missing "no slot suffix" ;; esac
  ext=$(timeout 5 lpdump --slot=$sn /dev/block/by-name/super 2>/dev/null | awk -v p="$name" '
        /Name: / { inpart = ($2 == p) }
        inpart && / linear super / { print $1, $3, $6 }')
  [ -n "$ext" ] || fail missing "no partition $name"
  : > $img
  echo "$ext" | while read -r b e s; do
    timeout 5 dd if=/dev/block/by-name/super bs=512 skip="$s" count=$((e - b + 1)) 2>/dev/null >> $img
  done
fi
mkdir -p $M/x
( cd $M/x && tar -xf $img ) 2>/dev/null || fail bad:tar
rm -f $img
[ -f $M/x/MANIFEST ] || fail bad:manifest
set=$(sed -n 's/^set=//p' $M/x/MANIFEST)
want=$(sed -n 's/^set=//p' $ALLOW)
setprop sys.z9x.blobs.set "$set"
[ "$set" = "$want" ] || say "blob set '$set' is not this image's '$want' (files still checked one by one)"

n=0; bad=
while read -r h p src; do
  case "$h" in ''|\#*|set=*) continue ;; esac
  f=$M/x/$p
  [ -f "$f" ] || { bad=${bad:-missing:$p}; continue; }
  got=$(sha256sum "$f" | cut -d' ' -f1)
  [ "$got" = "$h" ] || { bad="bad:$p"; rm -f "$f"; continue; }
  dst=/system/$p
  [ -f "$dst" ] && [ ! -s "$dst" ] || { bad="noplaceholder:$p"; continue; }
  chmod 0644 "$f"; chown root:root "$f"
  chcon u:object_r:system_lib_file:s0 "$f"
  mount --bind "$f" "$dst" || { bad="mount:$p"; continue; }
  n=$((n + 1))
done < $ALLOW
chmod 0755 $M
if [ -z "$bad" ]; then
  setprop sys.z9x.blobs ok
  say "ok: $n files of set $set bound"
elif [ $n -gt 0 ]; then
  setprop sys.z9x.blobs partial
  say "partial: $n files bound, first problem $bad"
else
  fail "$bad"
fi
exit 0
