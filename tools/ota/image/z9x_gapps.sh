#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS PUBLIC images (image path /system/etc/z9x/z9x_gapps.sh 0755; z9x_gapps.rc runs it as an exec
# at post-fs-data, like z9x_blobs.sh: default mount namespace, before zygote-start, so PackageManager and
# SystemConfig see the add-on at their first scan). Only Lumen OS without Google (ro.z9x.gms=0) uses it.
#
# The user's own Google services add-on lives in the logical partition z9x_gapps<slot> in super (ext4, made
# by the installer's 'gapps --zip FILE' from the user's own MindTheGapps download; no Google file is in any
# Lumen OS image or release). Not in /data, so it survives factory resets; a PARTIAL VAB payload (the only
# kind make_ota.py builds) keeps every partition it does not list, renamed to the target slot, so it
# survives Lumen OS updates (the same mechanism as z9x_blobs).
#   boot (the rc):
#    1. nothing on the Google edition (sys.z9x.gapps=skip) or without the partition (none);
#    2. off after failed starts: when the boot rescue counter (/metadata/z9x_rescue/count of z9x_rescue.sh,
#       this start included) says this is the 3rd start of this slot in a row that has not completed, the
#       add-on stays off from now on (marker /metadata/z9x_gapps/off = "<n> <slot> <ro.z9x.build_id>",
#       removed by the installer's 'gapps'), so a system that fails with Google services gets one start
#       without them before the rescue at the 4th start sends it to fastbootd. The marker holds only for
#       the slot and build that failed: after an update that did not start (the OTA gate rolls back at its
#       4th attempt, after the marker) or a 'rescue' with another build, the other slot or build keeps its
#       Google services;
#    3. the block device read-only, then the partition mounted read-only at /mnt/z9x_gapps (ext4, every file
#       labelled system_file as MindTheGapps' own installer does, nosuid, nodev; exec stays allowed: APKs
#       keep native code that is mapped straight from the APK);
#    4. checked against /system/etc/z9x/gapps_allow.txt: MANIFEST names one allow-listed zip and its file
#       lines are exactly that zip's lines; each file is there, a regular file of its size (metadata only:
#       hashing ~450 MB would take seconds while init waits; the installer hashed each one); nothing else is
#       in it (no other file, no link / device / fifo, no directory that holds no listed file);
#    5. read-only overlays (lower layers only): the image's sysconfig layer /system/etc/z9x/gapps/sysconfig
#       (the Google edition's sysconfig entries for these packages) over /system/etc/sysconfig, then the
#       partition's product, system_ext/priv-app, system_ext/etc/permissions, system/app and
#       system/etc/permissions over the same /system paths (/product and /system_ext are symlinks into
#       /system here). None of them holds a z9x_blobs target, so the order of the two scripts does not
#       matter. One overlay fails -> every overlay of this run and the partition are unmounted again;
#    6. ro.com.google.gmsversion from the zip's allow-list line (MindTheGapps' gapps.rc sets it at 'on init',
#       but init parses /product/etc/init long before this mount, so the add-on does not carry that file).
#   check: steps 3 and 4 only, at /mnt/z9x_gapps_check, on a running system (the installer, adb root, right
#       after filling the partition): prints 'ok: ...' (exit 0) or the problem (exit 1); no property, no
#       overlay, no marker, the block device's read-only flag untouched.
# Result: sys.z9x.gapps = ok | none | skip | off | bad:<why> (allow, config, mkdir, mount, manifest, zip,
# path, missing, size, entry, overlay, slow), sys.z9x.gapps.zip = the zip's sha256.
# Any failure: the system starts without Google services, never a boot loop. Never writes a partition; the
# only file it writes is the marker. Bounded (init waits for this exec and the vendor PWM watchdog reboots
# when early-boot is late, margin ~7 s): it reads only the partition's metadata (about 60 entries, one stat
# per listed file), the overlay mounts only look up directories: about 60 short processes, roughly 0.2 s.
# One time budget for the whole boot run, BUDGET (2 s), checked from /proc/uptime (a shell read, no
# process) before the mount, before every find and before every overlay: over it, bad:slow. With the ext4
# mount's own 2 s and each tree listing's 1 s timeout the worst case is about 3 s. 'check' has no budget.
TAG=z9x_gapps
R=${Z9X_ROOT:-}        # empty on the projector; tools/ota/test/gapps.sh points it at a temp dir
MODE=${1:-boot}
ALLOW=$R/system/etc/z9x/gapps_allow.txt
CFG=$R/system/etc/z9x/gapps/sysconfig
OFF=$R/metadata/z9x_gapps/off
CTX=u:object_r:system_file:s0
BUDGET=200             # centiseconds for the whole boot run (see above)
case "$MODE" in
  boot) M=$R/mnt/z9x_gapps ;;
  check) M=$R/mnt/z9x_gapps_check ;;
  *) echo "usage: z9x_gapps.sh [boot|check]" >&2; exit 2 ;;
esac
ON=                    # overlay targets mounted by this run, newest first
MOUNTED=

say() { log -t "$TAG" "$*"; }
# undo: all or nothing, the overlays of this run (newest first), then the partition
undo() {
  for t in $ON; do umount "$t" 2>/dev/null || say "cannot unmount $t"; done
  ON=
  if [ -n "$MOUNTED" ]; then umount "$M" 2>/dev/null; MOUNTED=; fi
}
# parents: file lines on stdin, every directory that holds a listed file (at every level) on stdout
parents() {
  while read -r h s p; do
    d=$p
    while :; do
      case "$d" in */*) d=${d%/*}; echo "$d" ;; *) break ;; esac
    done
  done
}
fail() {
  undo
  if [ "$MODE" = check ]; then echo "$1${2:+: $2}"; exit 1; fi
  setprop sys.z9x.gapps "$1"
  say "$1${2:+: $2}"
  exit 0
}
# now: centiseconds since boot in c (/proc/uptime, "12.34 ..."); c empty when that cannot be read
now() {
  c=; read -r c _ 2>/dev/null < "$R/proc/uptime"
  case "$c" in [0-9]*.[0-9][0-9]) c=${c%.*}${c#*.} ;; *) c= ;; esac
  while :; do case "$c" in 0?*) c=${c#0} ;; *) break ;; esac; done
  case "$c" in *[!0-9]*) c= ;; esac
}
# budget STEP: in the boot run, fail (bad:slow) once BUDGET is used up; nothing when the clock is unreadable
budget() {
  [ -n "$t0" ] || return 0
  now
  [ -z "$c" ] || [ $((c - t0)) -lt $BUDGET ] || fail bad:slow "$((c - t0))0 ms, at $1"
}
t0=
if [ "$MODE" = boot ]; then now; t0=$c; fi

cur=$(getprop ro.boot.slot_suffix)
name=z9x_gapps$cur
dev=$R/dev/block/mapper/$name
if [ "$MODE" = boot ]; then
  [ "$(getprop sys.z9x.gapps)" = ok ] && exit 0
  [ "$(getprop ro.z9x.gms)" = 0 ] || fail skip "not Lumen OS without Google"
fi
case "$cur" in _a|_b) ;; *) fail none "no slot suffix" ;; esac
# (the host test's partition is a directory that its mount stub copies)
[ -b "$dev" ] || { [ -n "$R" ] && [ -d "$dev" ]; } || fail none "no $name"
if [ "$MODE" = boot ]; then
  bid=$(getprop ro.z9x.build_id)
  if [ -e "$OFF" ]; then
    os=; ob=
    read -r _ os ob 2>/dev/null < "$OFF"
    if [ "$os" = "$cur" ] && [ "$ob" = "$bid" ]; then
      fail off "turned off after failed starts of $cur $bid ($OFF; the installer's 'gapps' turns it on again)"
    fi
    say "ignoring $OFF (${os:-?} ${ob:-?}): another slot or build"
  fi
  n=; s=
  read -r n s 2>/dev/null < "$R/metadata/z9x_rescue/count"
  case "$n" in ''|*[!0-9]*) n=0 ;; esac
  if [ "$s" = "$cur" ] && [ "$n" -ge 3 ]; then
    mkdir -p "${OFF%/*}" 2>/dev/null
    echo "$n $cur $bid" 2>/dev/null > "$OFF" || say "cannot write $OFF"
    fail off "start $n of $cur in a row without a completed boot: from now on without Google services on $cur $bid"
  fi
fi
[ -f "$ALLOW" ] || fail bad:allow "no $ALLOW"
[ -f "$CFG/z9x-gapps.xml" ] || fail bad:config "no $CFG/z9x-gapps.xml"

mkdir -p "$M" || fail bad:mkdir "$M"
if [ "$MODE" = boot ]; then blockdev --setro "$dev" 2>/dev/null || say "cannot set $dev read-only"; fi
budget mount
MOUNTED=1
timeout 2 mount -t ext4 -o ro,nosuid,nodev,context=$CTX "$dev" "$M" 2>/dev/null || fail bad:mount "$dev"

mf=$M/MANIFEST
[ -f "$mf" ] && [ ! -L "$mf" ] || fail bad:manifest "no MANIFEST"
budget MANIFEST
[ -z "$(timeout 1 find "$mf" -size +16384c 2>/dev/null)" ] || fail bad:manifest "MANIFEST larger than 16 KiB"
[ "$(grep -c '^zip=' "$mf")" = 1 ] || fail bad:manifest "not exactly one zip= line"
z=$(sed -n 's/^zip=\([0-9a-f]\{64\}\) .*/\1/p' "$mf")
[ -n "$z" ] || fail bad:manifest "bad zip= line"
zl=$(grep "^zip=$z " "$ALLOW" | head -n 1)
[ -n "$zl" ] || fail bad:zip "$(echo "$z" | cut -c1-16)... is not in gapps_allow.txt"
zn=$(echo "$zl" | cut -d' ' -f2)
gv=$(echo "$zl" | sed -n 's/.* gmsversion=\([0-9A-Za-z_.-]*\) .*/\1/p')
want=$(awk -v z="zip=$z" '$1 ~ /^zip=/ { on = ($1 == z); next } on && NF == 3 && $1 !~ /^#/ { print $1, $2, $3 }' "$ALLOW")
have=$(awk '/^#/ || NF == 0 || $1 ~ /^zip=/ { next } { if (NF == 3) print $1, $2, $3; else print "BAD", NR }' "$mf")
[ -n "$want" ] && [ "$have" = "$want" ] || fail bad:manifest "file lines differ from gapps_allow.txt"

n=0
while read -r h s p; do
  case "$p" in ''|/*|*/|*//*|*..*) fail bad:path "$p" ;; esac
  case "$p" in
    product/?*|system_ext/priv-app/?*|system_ext/etc/permissions/?*|system/app/?*|system/etc/permissions/?*) ;;
    *) fail bad:path "$p" ;;
  esac
  case "$s" in ''|*[!0-9]*) fail bad:manifest "size of $p" ;; esac
  [ -f "$M/$p" ] && [ ! -L "$M/$p" ] || fail bad:missing "$p"
  budget "$p"
  [ -n "$(find "$M/$p" -type f -size "${s}c" 2>/dev/null)" ] || fail bad:size "$p"
  n=$((n + 1))
done <<EOF
$want
EOF
budget tree
odd=$(timeout 1 find "$M" ! -type d ! -type f 2>/dev/null | head -n 1)
[ -z "$odd" ] || fail bad:entry "${odd#"$M"/}"
budget tree
nf=$(timeout 1 find "$M" -type f 2>/dev/null | wc -l | tr -d ' ')
[ "$nf" = $((n + 1)) ] || fail bad:entry "$nf files, expected $((n + 1)) (the listed ones and MANIFEST)"
dw=$(echo "$want" | parents | sort -u)
budget tree
dh=$(timeout 1 find "$M" -mindepth 1 -type d ! -path "$M/lost+found" 2>/dev/null | sed "s|^$M/||" | sort -u)
[ "$dh" = "$dw" ] || fail bad:entry "directories differ from the listed files"

if [ "$MODE" = check ]; then
  undo
  echo "ok: $n files of $zn"
  exit 0
fi

# ov LOWER TARGET: LOWER over the /system path TARGET, read-only, TARGET itself as the bottom layer
ov() {
  [ -d "$1" ] || return 0
  [ -d "$R$2" ] || fail bad:overlay "no $2"
  budget "overlay $2"
  mount -t overlay overlay -o "ro,lowerdir=$1:$R$2" "$R$2" 2>/dev/null || fail bad:overlay "$2"
  ON="$R$2 $ON"
}
ov "$CFG" /system/etc/sysconfig
ov "$M/product" /system/product
ov "$M/system_ext/priv-app" /system/system_ext/priv-app
ov "$M/system_ext/etc/permissions" /system/system_ext/etc/permissions
ov "$M/system/app" /system/app
ov "$M/system/etc/permissions" /system/etc/permissions
if [ -n "$gv" ]; then setprop ro.com.google.gmsversion "$gv" 2>/dev/null || say "ro.com.google.gmsversion not set"; fi
setprop sys.z9x.gapps.zip "$z"
setprop sys.z9x.gapps ok
say "ok: $n files of $zn (gmsversion $gv)"
exit 0
