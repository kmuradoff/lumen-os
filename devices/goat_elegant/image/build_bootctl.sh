#!/bin/bash
# build_bootctl.sh: build the AOSP 14 /system/bin/bootctl (system/extras/bootctl) for the XGIMI Z9X HB
# (goat_elegant), 32-bit ARM, from the HB base tree: LineageOS 21 lineage_gsi_tv_arm userdebug, the tree of the
# base system.img pinned in base.sha256 (the design notes says what it links and
# how it reaches the boot HAL). Run on the Mac from anywhere:
#   bash devices/goat_elegant/image/build_bootctl.sh
# On the build host (image.conf host=, or HB_BUILDER) it
# 1. checks that ~/lineage is at the revisions of lineage/manifests/lumen-lineage-21.xml for build/make,
#    hardware/interfaces and system/extras (the projects bootctl and libboot_control_client come from);
# 2. builds only the soong module bootctl in the lineage-builder container, with the flags of
#    lineage/play6_build_loop.sh (the base build) and no play6 override (none of the patched files is a
#    bootctl dependency). ~/lineage is mounted READ-ONLY; everything is written under ~/lineage/out/hb-bootctl
#    (OUT_DIR), including the empty repo trace file the build-manifest rule needs (play6_sf_build.sh). The
#    container runs detached (name hb-bootctl): a dropped ssh does not stop it, and a second run waits for it
#    (on 2026-11-04 the first run's ssh was closed by the remote side after ~10 min of docker wait; run it again);
#    log ~/lineage/out/hb-bootctl/bootctl_build.log;
# 3. checks the result: ELF 32-bit ARM, interpreter /system/bin/linker, and every library it needs, and every
#    library those need, present in /system/lib of the HB image HB_IMG (default the 1.0.7-beta1 release
#    image, read with dump.erofs; extracted copies go to OUT_DIR/check), and prints for each one whether the
#    copy this tree built is byte-identical to the image's;
# 4. copies the binary to ~/hb-out/in/bootctl (0755).
# Then it fetches ~/hb-out/in/bootctl to build/goat_elegant/bootctl/bootctl (git-ignored) and checks its sha256
# against bootctl.sha256; with no hash pinned there yet it prints the line to pin. No device is contacted, no key
# is read.
# Environment: HB_BUILDER (host), HB_IMG (image path on the host, relative to ~ or absolute), JOBS (default 4).
set -euo pipefail
die() { echo "build_bootctl: $*" >&2; exit 1; }
G=$(cd "$(dirname "$0")/../../.." && pwd)          # the gsi folder
D="$G/devices/goat_elegant/image"
SRV=${HB_BUILDER:-$(python3 -B "$G/tools/devices/devices.py" image goat_elegant host)}
IMG=${HB_IMG:-hb_session/system_hb-1.0.7-beta1-release.img}
JOBS=${JOBS:-4}
case $JOBS in *[!0-9]*|'') die "JOBS must be a number" ;; esac
case $IMG in *[!A-Za-z0-9._/-]*|'') die "HB_IMG may hold only letters, digits and . _ / -" ;; esac
MF="$G/lineage/manifests/lumen-lineage-21.xml"
pin() {  # project path -> pinned revision
  sed -n "s#.* path=\"$1\" .*revision=\"\([0-9a-f]\{40\}\)\".*#\1#p" "$MF"
}
PINS=
for p in build/make hardware/interfaces system/extras; do
  r=$(pin "$p"); [ -n "$r" ] || die "no pinned revision for $p in $MF"
  PINS="$PINS $p=$r"
done

ssh -o ServerAliveInterval=30 "$SRV" bash -s -- "$IMG" "$JOBS" $PINS <<'REMOTE'
set -euo pipefail
die() { echo "build_bootctl: $*" >&2; exit 1; }
IMG=$1 JOBS=$2; shift 2
case $IMG in /*) ;; *) IMG=$HOME/$IMG ;; esac
SRC=$HOME/lineage OUT=$HOME/lineage/out/hb-bootctl DEST=$HOME/hb-out/in/bootctl NAME=hb-bootctl
RE=$SRC/prebuilts/clang/host/linux-x86/llvm-binutils-stable/llvm-readelf
[ -d "$SRC/.repo" ] || die "no LineageOS tree in $SRC"
[ -f "$IMG" ] || die "no HB image $IMG"
for kv in "$@"; do
  p=${kv%%=*} r=${kv#*=}
  h=$(git -C "$SRC/$p" rev-parse HEAD) || die "cannot read the revision of $SRC/$p"
  [ "$h" = "$r" ] || die "$SRC/$p is at $h, the pin is $r"
done
mkdir -p "$OUT"
[ -e "$OUT/repo_TRACE_FILE" ] || : > "$OUT/repo_TRACE_FILE"
state=$(docker inspect -f '{{.State.Status}}' "$NAME" 2>/dev/null || true)
if [ "$state" = running ]; then
  echo "build_bootctl: $NAME is already running, waiting for it"
else
  [ -z "$state" ] || docker rm "$NAME" > /dev/null
  docker run -d --name "$NAME" -v "$SRC:/src:ro" -v "$OUT:/out" \
    -v "$OUT/repo_TRACE_FILE:/src/.repo/TRACE_FILE" -e JOBS="$JOBS" lineage-builder bash -c '
cd /src
export OUT_DIR=/out PYTHONDONTWRITEBYTECODE=1
source build/envsetup.sh
export WITH_DEXPREOPT=true WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=true SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
export OVERRIDE_ENABLE_UFFD_GC=false
breakfast gsi_tv_arm || { echo "EXIT=2 breakfast $(date "+%F %T")"; exit 2; }
echo "=== BUILD $(date "+%F %T")"
m -j${JOBS:-4} bootctl 2>&1 | tee /out/bootctl_build.log
rc=${PIPESTATUS[0]}
echo "EXIT=$rc $(date "+%F %T")"
exit $rc' > /dev/null
  echo "build_bootctl: started $NAME (log $OUT/bootctl_build.log)"
fi
rc=$(docker wait "$NAME")
docker logs --tail 3 "$NAME" 2>&1
[ "$rc" = 0 ] || die "the build failed (exit $rc), see $OUT/bootctl_build.log"
docker rm "$NAME" > /dev/null

B=$OUT/target/product/generic/system/bin/bootctl
L=$OUT/target/product/generic/system/lib
[ -f "$B" ] || die "no $B"
ft=$(file -b "$B")
echo "file: $ft"
case $ft in "ELF 32-bit LSB"*ARM*"interpreter /system/bin/linker,"*) ;; *) die "not a 32-bit ARM Android executable" ;; esac
needed() { "$RE" -d "$1" | sed -n 's/.*(NEEDED) *Shared library: \[\(.*\)\]/\1/p'; }
C=$OUT/check
rm -rf "$C"; mkdir -p "$C"
dump.erofs --ls --path=/system/lib "$IMG" | awk 'NF >= 3 { print $2, $3 }' > "$C/system_lib.txt"
todo=$(needed "$B"); seen=" "; ok=1
echo "bootctl needs: $(echo $todo)"
while [ -n "$todo" ]; do
  next=
  for l in $todo; do
    case $seen in *" $l "*) continue ;; esac
    seen="$seen$l "
    t=$(awk -v n="$l" '$2 == n { print $1 }' "$C/system_lib.txt")
    case $t in
      1) dump.erofs --cat --path="/system/lib/$l" "$IMG" > "$C/$l"
         if [ -f "$L/$l" ] && cmp -s "$L/$l" "$C/$l"; then same="identical to this build"
         elif [ -f "$L/$l" ]; then same="DIFFERS from this build"
         else same="not built here"; fi
         echo "  /system/lib/$l  present, $(sha256sum < "$C/$l" | cut -c1-16)..., $same"
         next="$next $(needed "$C/$l")" ;;
      7) echo "  /system/lib/$l  present (symlink, bionic in the runtime APEX)" ;;
      *) echo "  /system/lib/$l  MISSING from $IMG"; ok=0 ;;
    esac
  done
  todo=$next
done
[ $ok = 1 ] || die "a library bootctl needs is not in the HB image"
mkdir -p "$(dirname "$DEST")"
install -m 0755 "$B" "$DEST"
echo "build_bootctl: $DEST $(sha256sum < "$DEST" | cut -d' ' -f1)"
REMOTE

OUTD="$G/build/goat_elegant/bootctl"
mkdir -p "$OUTD"
scp -q "${SRV}:hb-out/in/bootctl" "$OUTD/bootctl"
chmod 0755 "$OUTD/bootctl"
sha=$(shasum -a 256 "$OUTD/bootctl" | cut -d' ' -f1)
want=
[ ! -f "$D/bootctl.sha256" ] || want=$(sed -n '1s/^\([0-9a-f]\{64\}\) .*/\1/p' "$D/bootctl.sha256")
if [ -z "$want" ]; then
  echo "build_bootctl: nothing pinned yet; pin it in devices/goat_elegant/image/bootctl.sha256:"
  echo "$sha  bootctl"
elif [ "$sha" = "$want" ]; then
  echo "build_bootctl: OK $OUTD/bootctl $sha (pinned)"
else
  die "$OUTD/bootctl is $sha, bootctl.sha256 pins $want"
fi
