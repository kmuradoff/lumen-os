#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS PUBLIC images only (image path /system/etc/z9x/z9x_blobs.sh 0755; z9x_blobs.rc runs it as
# an exec at post-fs-data, after init.rc's part of that trigger, i.e. in the default mount namespace with
# the APEXes active, and before 'on boot', where audioserver and the vendor HALs start).
# The owner's private image keeps these files inside /system (and xgimi_compat.rc binds the audio ones)
# and does not ship this.
#
# The user's own MediaTek / XGIMI files live in the logical partition z9x_blobs<slot> in super (made
# by the installer from the user's own stock or Lumen system: blobs_allow.txt). They survive factory
# resets (not in /data) and Lumen OS updates: a PARTIAL VAB payload (the only kind make_ota.py builds and
# sign_payload.py signs) renames the untouched z9x_blobs_<source> to z9x_blobs_<target> with the same
# extents. A NON-partial payload would delete z9x_blobs_<target> (libsnapshot drops every target-slot
# partition missing from its manifest): no sound and no secure video until 'blobs' + 'flash --keep-data'.
#   1. read the partition: its dm device, mapped by first-stage init like every logical partition with
#      extents (no lpdump: on the device it is a binder client of lpdumpd, which init cannot start while
#      it waits for this exec, so every boot without the partition would stall here),
#   2. untar it into our own tmpfs /mnt/z9x_blobs (8 MiB: the 4 MiB partition copy + the files; required:
#      /mnt itself is noexec and a bind keeps the flags of the mount it comes from, so the libraries would
#      not load from there). Before extracting: no member name absolute or with '..', no link / device /
#      fifo member (listed as such), so nothing is written outside the tmpfs; after: the tree may hold
#      only directories and regular files no larger than the partition (no sparse giant to hash),
#   3. accept only files whose sha256 is in /system/etc/z9x/blobs_allow.txt,
#   4. label them (system_lib_file for a .so, system_file otherwise) and bind-mount each over its 0-byte
#      placeholder in /system, or, for a line with bind=<path> (the files xgimi_compat.rc binds on the
#      private image: the MediaTek VNDK libstagefright_foundation the vendor audio HAL needs, the audio
#      policy without XGIMI's enums), over that vendor / VNDK APEX file,
#   5. remount the tmpfs read-only: every bind shares it, so the bound files cannot be changed any more
#      (like the private image's erofs).
# Result: sys.z9x.blobs = ok | missing | bad:<why> | partial, sys.z9x.blobs.set = <set id>.
# Without the partition the system still boots; it has no sound (vendor audio HAL) and no DRM-secure
# video. Never writes any partition. Bounded, since init waits for this exec and the vendor PWM watchdog
# reboots when early-boot is late: the 4 MiB read, the two listings and the untar of that copy have a 3 s
# timeout each (each takes milliseconds on 4 MiB), then sha256 of at most the allow-listed files, each at
# most 4 MiB.
TAG=z9x_blobs
R=${Z9X_ROOT:-}        # empty on the projector; tools/ota/test/blobs.sh points it at a temp dir
ALLOW=$R/system/etc/z9x/blobs_allow.txt
M=$R/mnt/z9x_blobs
say() { log -t "$TAG" "$*"; }
fail() { setprop sys.z9x.blobs "$1"; say "$1${2:+: $2}"; exit 0; }

cur=$(getprop ro.boot.slot_suffix)
name=z9x_blobs$cur
dev=$R/dev/block/mapper/$name
[ "$(getprop sys.z9x.blobs)" = ok ] && exit 0
case "$cur" in _a|_b) ;; *) fail missing "no slot suffix" ;; esac
# (the host test's partition is a plain file)
[ -b "$dev" ] || { [ -n "$R" ] && [ -f "$dev" ]; } || fail missing "no $name"

mkdir -p $M || fail bad:mkdir
[ -n "$R" ] || mount -t tmpfs -o mode=0750,uid=0,gid=0,size=8m,nosuid,nodev tmpfs $M 2>/dev/null || fail bad:tmpfs
img=$M/.img
timeout 3 dd if="$dev" of=$img bs=65536 count=64 2>/dev/null || fail bad:read "$dev"
timeout 3 tar -tf $img 2>/dev/null | grep -qE '^/|^\.\.(/|$)|/\.\.(/|$)' && fail bad:path
odd=$(timeout 3 tar -tvf $img 2>/dev/null | grep '^[lhcbps]' | head -n 1)
[ -z "$odd" ] || fail bad:entry "$odd"
mkdir -p $M/x
( cd $M/x && timeout 3 tar -xf $img ) 2>/dev/null || fail bad:tar
rm -f $img
odd=$(find $M/x ! -type d \( ! -type f -o -size +4194304c \) 2>/dev/null | head -n 1)
[ -z "$odd" ] || fail bad:entry "${odd#$M/x/}"
[ -f $M/x/MANIFEST ] || fail bad:manifest
set=$(sed -n 's/^set=//p' $M/x/MANIFEST)
want=$(sed -n 's/^set=//p' $ALLOW)
setprop sys.z9x.blobs.set "$set"
[ "$set" = "$want" ] || say "blob set '$set' is not this image's '$want' (files still checked one by one)"

n=0; bad=
while read -r h p src opt; do
  case "$h" in ''|\#*|set=*) continue ;; esac
  f=$M/x/$p
  [ -f "$f" ] || { bad=${bad:-missing:$p}; continue; }
  got=$(sha256sum "$f" | cut -d' ' -f1)
  [ "$got" = "$h" ] || { bad=${bad:-bad:$p}; rm -f "$f"; continue; }
  dst=/system/$p
  case " $opt " in
    *" bind="*) dst=${opt#*bind=}; dst=${dst%% *}
      # only the vendor / VNDK APEX files xgimi_compat.rc binds on the private image
      case "$dst" in /vendor/etc/*|/apex/com.android.vndk.v*/lib/*|/apex/com.android.vndk.v*/lib64/*) ;;
        *) bad=${bad:-target:$p}; continue ;; esac
      [ -f "$R$dst" ] || { bad=${bad:-notarget:$p}; continue; } ;;
    *) [ -f "$R$dst" ] && [ ! -s "$R$dst" ] || { bad=${bad:-noplaceholder:$p}; continue; } ;;
  esac
  lab=u:object_r:system_file:s0
  case "$p" in *.so) lab=u:object_r:system_lib_file:s0 ;; esac
  chmod 0644 "$f"; chown root:root "$f"
  chcon "$lab" "$f"
  mount --bind "$f" "$R$dst" || { bad=${bad:-mount:$p}; continue; }
  n=$((n + 1))
done < $ALLOW
chmod 0755 $M
[ -n "$R" ] || mount -o remount,ro $M 2>/dev/null || say "$M stays writable (remount failed)"
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
