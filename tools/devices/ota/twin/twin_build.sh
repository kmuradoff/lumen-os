#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# twin_build.sh: the storage twin of a Virtual A/B projector with Lumen OS installed, built from its stock dump on
# the build host (the first one is the XGIMI Z9X HB:
# the design notes). No projector, no root, no git. Run on the build host from a copy of
# the tree:
#   bash tools/devices/ota/twin/twin_build.sh --dump DIR --system IMG --blobs IMG --gapps IMG --out DIR
#     --dump    the stock dump (super.img, misc.img, the static *_a.img / *_b.img and their *.sha256); only read
#     --system  the Lumen release system image that slot a runs (erofs)
#     --blobs   the z9x_blobs_a image; --gapps the z9x_gapps_a image (any file system: update_engine never reads
#               them, they only keep their extents)
#     --out     the twin folder (made here; refused inside --dump)
# It
# 1. checks every dump file it reads against its .sha256 (before and after, so the dump is proven unchanged);
# 2. copies dump/super.img to OUT/super_twin.img and applies the installer's fastbootd steps to the copy with
#    twin_lp.py install, in the order of installer/lumen-install.sh 'flash' and the gapps step:
#    create-logical-partition z9x_blobs_a + flash, resize + flash system_a, create-logical-partition z9x_gapps_a +
#    flash (fastbootd creates in the group "default", system/core/fastboot/device/commands.cpp CreatePartitionHandler;
#    liblp allocates first fit, so the result is the device's layout after that sequence, not lpmake's packing).
#    vendor_a, product_a and system_ext_a stay the stock bytes in place (the installer keeps them: KEEP of
#    installer/lib/devices.conf);
# 3. checks the result twice: twin_lp.py verify (every written partition holds its image, the kept ones equal the
#    dump's), and the AOSP lpunpack (it parses the metadata twin_lp.py wrote and its system_a, z9x_blobs_a and
#    z9x_gapps_a must equal the inputs);
# 4. links every static slot pair of the dump (NAME_a.img + NAME_b.img) read only into OUT/static, except vbmeta:
#    OUT/static/vbmeta_{a,b}.img are the dump's vbmeta_a with the AVB flags 3 (verity and verification off), what
#    the installer's one-time step 'fastboot --disable-verity --disable-verification --slot=all flash vbmeta' leaves
#    on both slots (inferred: the flags are what the gate and the updater read; the rest of vbmeta is never read);
# 5. copies dump/misc.img to OUT/misc.img (the slot state: twin_misc.py) and writes OUT/TWIN.txt (inputs, sha256,
#    layout).
# Never extracts any file system: the static images (on the HB tvconfig, xgimiconfig, ...) are only read as raw
# blocks.
set -euo pipefail
die() { echo "twin_build: $*" >&2; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
DUMP= SYS= BLOBS= GAPPS= OUT=
while [ $# -gt 0 ]; do
  case $1 in
    --dump) DUMP=$2; shift 2 ;;
    --system) SYS=$2; shift 2 ;;
    --blobs) BLOBS=$2; shift 2 ;;
    --gapps) GAPPS=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    *) die "unknown argument $1 (see the header)" ;;
  esac
done
[ -n "$DUMP" ] && [ -n "$SYS" ] && [ -n "$BLOBS" ] && [ -n "$GAPPS" ] && [ -n "$OUT" ] || die "--dump --system --blobs --gapps --out are required"
DUMP=$(cd "$DUMP" && pwd)
for f in "$SYS" "$BLOBS" "$GAPPS" "$DUMP/super.img" "$DUMP/misc.img"; do [ -f "$f" ] || die "no $f"; done
SYS=$(readlink -f "$SYS") BLOBS=$(readlink -f "$BLOBS") GAPPS=$(readlink -f "$GAPPS")
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
case $OUT/ in "$DUMP"/*) die "--out must not be inside the dump" ;; esac
LPUNPACK=${LPUNPACK:-$HOME/lineage/prebuilts/extract-tools/linux-x86/bin/lpunpack}
[ -x "$LPUNPACK" ] || die "no lpunpack ($LPUNPACK; set LPUNPACK)"
STATIC=      # every static slot pair of the dump: NAME_a.img with NAME_b.img and both .sha256
for f in "$DUMP"/*_a.img; do
  p=$(basename "$f" _a.img)
  [ -f "$DUMP/${p}_b.img" ] && [ -f "$DUMP/${p}_a.sha256" ] && [ -f "$DUMP/${p}_b.sha256" ] && STATIC="$STATIC $p"
done
STATIC=${STATIC# }
case " $STATIC " in *" vbmeta "*) ;; *) die "no vbmeta_a.img / vbmeta_b.img pair in the dump" ;; esac

dump_check() {  # the dump files this twin reads, against their recorded sha256
  (cd "$DUMP" && sha256sum --quiet -c super.sha256 misc.sha256 $(for p in $STATIC; do echo "${p}_a.sha256 ${p}_b.sha256"; done)) \
    || die "the dump differs from its .sha256 files"
  echo "dump: super, misc and the static _a/_b images equal their .sha256 ($1)"
}
fs_of() {  # erofs (magic e2e1f5e0 at 1024), ext4 (ef53 at 1080) or raw
  if [ "$(od -A n -t x1 -j 1024 -N 4 "$1" | tr -d ' \n')" = e2e1f5e0 ]; then echo erofs
  elif [ "$(od -A n -t x1 -j 1080 -N 2 "$1" | tr -d ' \n')" = 53ef ]; then echo ext4
  else echo raw; fi
}

dump_check before
echo "inputs: system $(fs_of "$SYS") $(stat -c %s "$SYS") bytes, blobs $(fs_of "$BLOBS") $(stat -c %s "$BLOBS"), gapps $(fs_of "$GAPPS") $(stat -c %s "$GAPPS")"
cp --sparse=always "$DUMP/super.img" "$OUT/super_twin.img"
chmod 644 "$OUT/super_twin.img"
echo "== stock super (dump)"
python3 -B "$HERE/twin_lp.py" show "$DUMP/super.img"
echo "== installer steps on the copy"
python3 -B "$HERE/twin_lp.py" install "$OUT/super_twin.img" \
  "create:z9x_blobs_a=$BLOBS" "flash:system_a=$SYS" "create:z9x_gapps_a=$GAPPS"
echo "== twin_lp.py verify"
python3 -B "$HERE/twin_lp.py" verify "$OUT/super_twin.img" "system_a=$SYS" "z9x_blobs_a=$BLOBS" "z9x_gapps_a=$GAPPS"
U=$OUT/unpack
rm -rf "$U"; mkdir -p "$U"
"$LPUNPACK" -p system_a -p z9x_blobs_a -p z9x_gapps_a -p vendor_a -p product_a -p system_ext_a "$OUT/super_twin.img" "$U" > /dev/null
mkdir -p "$U/stock"
"$LPUNPACK" -p vendor_a -p product_a -p system_ext_a "$DUMP/super.img" "$U/stock" > /dev/null
cmp "$U/system_a.img" "$SYS" && cmp "$U/z9x_blobs_a.img" "$BLOBS" && cmp "$U/z9x_gapps_a.img" "$GAPPS" \
  || die "lpunpack of the twin differs from the inputs"
for p in vendor_a product_a system_ext_a; do
  cmp "$U/$p.img" "$U/stock/$p.img" || die "lpunpack: $p of the twin differs from the stock super's"
done
echo "lpunpack (AOSP liblp): the twin's metadata parses; system_a, z9x_blobs_a, z9x_gapps_a equal the inputs; vendor_a, product_a, system_ext_a equal the stock super's"
rm -rf "$U"

S=$OUT/static
rm -rf "$S"; mkdir -p "$S"
for p in $STATIC; do
  [ "$p" = vbmeta ] && continue
  for s in a b; do ln -s "$DUMP/${p}_$s.img" "$S/${p}_$s.img"; done
done
python3 -B - "$DUMP/vbmeta_a.img" "$S/vbmeta_a.img" <<'PY'
import struct, sys
b = bytearray(open(sys.argv[1], "rb").read())
if b[:4] != b"AVB0":
    sys.exit("twin_build: vbmeta_a has no AVB0 magic")
struct.pack_into(">I", b, 120, 3)       # flags: HASHTREE_DISABLED | VERIFICATION_DISABLED
open(sys.argv[2], "wb").write(b)
PY
cp "$S/vbmeta_a.img" "$S/vbmeta_b.img"
chmod 444 "$S/vbmeta_a.img" "$S/vbmeta_b.img"
cp "$DUMP/misc.img" "$OUT/misc.img"
chmod 444 "$OUT/misc.img"
python3 -B "$HERE/twin_misc.py" show "$OUT/misc.img"
dump_check after
{
  echo "storage twin built $(date -u +%Y-%m-%dT%H:%MZ) by tools/devices/ota/twin/twin_build.sh"
  echo "dump=$DUMP"
  echo "system=$SYS"
  echo "blobs=$BLOBS"
  echo "gapps=$GAPPS"
  for f in "$SYS" "$BLOBS" "$GAPPS"; do echo "input $(sha256sum "$f" | cut -d' ' -f1) $(stat -c %s "$f") $(fs_of "$f") $f"; done
  echo "super_twin.img $(sha256sum "$OUT/super_twin.img" | cut -d' ' -f1)"
  echo "misc.img $(sha256sum "$OUT/misc.img" | cut -d' ' -f1) (= dump misc.img)"
  echo "static=$STATIC"
  echo "static: links to the dump; vbmeta_a/_b = dump vbmeta_a with flags 3"
  python3 -B "$HERE/twin_lp.py" show "$OUT/super_twin.img"
} > "$OUT/TWIN.txt"
echo "twin_build: OK ($OUT/TWIN.txt)"
