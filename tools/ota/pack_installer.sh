#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# pack_installer.sh: make the installer zip of a release or pre-release FOR ONE IMAGE, so the image and the
# installer of a download always belong together (docs/release.md, "Before publishing").
#
#   tools/ota/pack_installer.sh IMAGE OUTDIR [--installer DIR] [--erofs-utils DIR]
#   tools/ota/pack_installer.sh --check ZIP IMAGE
#
#   IMAGE     the image exactly as it is published, next to its SHA256SUMS and SHA256SUMS.sig:
#               lumen-os-<version>-nogms-system.img          XGIMI Z9X
#               lumen-os-<token>-<version>-nogms-system.img  another projector (IMAGE_TOKEN of lib/devices.conf,
#                                                            e.g. play6)
#             <version> is x.y.z or, for a pre-release, x.y.z-<mark> (lower case letters and digits, e.g.
#             1.0.7-kst1). It is the version the installer checks: the name, release.conf VERSION and the
#             image's own version property (devices.conf VERSION_PROP) must be the same string.
#   OUTDIR    where lumen-os-[<token>-]<version>-installer.zip is written
#   --installer DIR    the installer folder to pack (default: $LUMEN_REPO/installer, LUMEN_REPO defaulting to
#                      ~/lumen-os: the exported repository, never the working tree, docs/repo/README.md)
#   --erofs-utils DIR  put DIR (the programs of tools/ota/build_erofs_utils.sh) into installer/tools/erofs-utils.
#                      Nothing else of an installer/tools folder is ever packed (Google's Platform-Tools that a
#                      working-tree installer downloaded for itself must not be passed on).
#   --check ZIP IMAGE  only the pair check below, on an existing zip (before uploading one, or a zip on GitHub)
#
# The zip's installer/release.conf is the folder's with its VERSION / DEVICE / BLOBS lines replaced:
#   Z9X:            BLOBS=partition (the public image), VERSION=<version>, no DEVICE line
#   other device:   VERSION=<version>, DEVICE=<id>, no BLOBS line
# Pair check (run on every zip this script writes; a failure deletes the zip and nothing is left):
#   - the zip's name is the one for IMAGE's projector and version
#   - its release.conf says VERSION=<version of the image name> and the image's projector (DEVICE)
#   - the image's own version property (read with debugfs for ext4, dump.erofs for erofs) is that version, and
#     the image is a release build of that projector (Play 6: ro.lumen.device=play6, ro.adb.secure=1)
#   - SHA256SUMS lists the image with its sha256, and SHA256SUMS.sig verifies with the certificate in the zip
#     (devices.conf CERT): the installer would accept this image
#   - the zip has the installer for macOS / Linux / Windows and nothing under installer/tools but erofs-utils
# A -PRIVATE- image or a name that is not a release name is refused. Exit status 0 only when everything holds.
set -euo pipefail
die() { echo "pack_installer: ERROR: $*" >&2; exit 1; }
say() { echo "pack_installer: $*"; }
usage() { sed -n '4,6p' "$0" | sed 's/^# *//' >&2; exit 2; }

E2=${E2FS:-/opt/homebrew/opt/e2fsprogs/sbin}
debugfs_bin() { if [ -x "$E2/debugfs" ]; then echo "$E2/debugfs"; else command -v debugfs || true; fi; }
sha256() { if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1"; else sha256sum "$1"; fi | awk '{print $1}'; }

# conf FILE ID KEY: a value of lib/devices.conf
conf() { awk -v k="$2.$3" 'index($0, k "=") == 1 { print substr($0, length(k) + 2) }' "$1"; }
# kv FILE KEY: the last KEY=value line of a release.conf (comments ignored, CR of a Windows edit dropped)
kv() { tr -d '\r' < "$1" | sed -n "s/^$2=//p" | tail -1; }

# parse_image NAME DEVICES_CONF -> sets DEV (z9x | id), TOK ('' for the Z9X), VER
parse_image() {
  local name=$1 dc=$2 mid id t
  case "$name" in *PRIVATE*) die "$name is a private image: never a release asset" ;; esac
  case "$name" in lumen-os-*-nogms-system.img) ;; *)
    die "$name is not named like a release image (lumen-os-[<token>-]<version>-nogms-system.img)" ;; esac
  mid=${name#lumen-os-}; mid=${mid%-nogms-system.img}
  DEV=z9x; TOK=""; VER=$mid
  for id in $(sed -n 's/^\([a-z0-9]*\)\.IMAGE_TOKEN=.*/\1/p' "$dc"); do
    t=$(conf "$dc" "$id" IMAGE_TOKEN)
    case "$mid" in "$t"-*) DEV=$id; TOK=$t; VER=${mid#"$t"-}; break ;; esac
  done
  echo "$VER" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+)?$' \
    || die "$name: '$VER' is not a version (x.y.z, or x.y.z-<mark> for a pre-release); unknown projector token?"
}

zip_name() { if [ -n "$TOK" ]; then echo "lumen-os-$TOK-$VER-installer.zip"; else echo "lumen-os-$VER-installer.zip"; fi; }

# image_prop IMAGE FS PROP: the value init would see (system, then system_ext, then product build.prop)
image_prop() {
  local img=$1 fs=$2 p=$3 d f out=""
  if [ "$fs" = ext4 ]; then
    d=$(debugfs_bin); [ -n "$d" ] || die "needs debugfs to read the image (brew install e2fsprogs; or set E2FS)"
    for f in /system/build.prop /system/system_ext/etc/build.prop /system/product/etc/build.prop; do
      out="$out
$("$d" -R "cat $f" "$img" 2>/dev/null || true)"
    done
  else
    command -v dump.erofs >/dev/null 2>&1 || die "needs dump.erofs to read the image (brew install erofs-utils)"
    for f in /system/build.prop /system/system_ext/etc/build.prop /system/product/etc/build.prop; do
      out="$out
$(dump.erofs --cat --path="$f" "$img" 2>/dev/null || true)"
    done
  fi
  printf '%s\n' "$out" | tr -d '\r' | sed -n "s/^$p=//p" | tail -1
}

# check_pair ZIP IMAGE: every rule of the header; dies on the first broken one
check_pair() {
  local zip=$1 img=$2 t dc rc name dir want got fs vp lp lv cert v sec m
  [ -s "$zip" ] || die "no $zip"
  [ -s "$img" ] || die "no $img"
  t=$(mktemp -d "${TMPDIR:-/tmp}/packchk.XXXXXX")
  CHK_T=$t
  trap 'rm -rf "$CHK_T"' EXIT   # check_pair runs alone (--check) or in a subshell of its own
  unzip -qq "$zip" -d "$t" || die "$zip is not a readable zip"
  [ -d "$t/installer" ] || die "$zip has no installer/ folder"
  for m in lumen-install.sh lumen-install.ps1 lumen-install.cmd release.conf lib/devices.conf; do
    [ -s "$t/installer/$m" ] || die "$zip lacks installer/$m"
  done
  m=$(cd "$t/installer" && find tools -mindepth 1 -maxdepth 1 2>/dev/null | grep -vx tools/erofs-utils || true)
  [ -z "$m" ] || die "$zip carries installer/$m: only installer/tools/erofs-utils may be packed"
  dc=$t/installer/lib/devices.conf; rc=$t/installer/release.conf
  name=$(basename "$img"); dir=$(cd "$(dirname "$img")" && pwd)
  parse_image "$name" "$dc"
  want=$(zip_name)
  [ "$(basename "$zip")" = "$want" ] || die "$(basename "$zip") is not the installer of $name: it must be named $want"
  got=$(kv "$rc" VERSION)
  [ "$got" = "$VER" ] || die "$(basename "$zip") says VERSION=$got in release.conf, the image $name is $VER: the installer would refuse it"
  got=$(kv "$rc" DEVICE)
  if [ "$DEV" = z9x ]; then
    [ -z "$got" ] || [ "$got" = z9x ] || die "release.conf DEVICE=$got, the image $name is for the XGIMI Z9X"
    [ "$(kv "$rc" BLOBS)" = partition ] || die "release.conf of a Z9X release must say BLOBS=partition (public image)"
  else
    [ "$got" = "$DEV" ] || die "release.conf DEVICE=$got, the image $name is for $DEV"
  fi
  fs=$(conf "$dc" "$DEV" IMAGE_FS); fs=${fs:-erofs}
  vp=$(conf "$dc" "$DEV" VERSION_PROP); [ -n "$vp" ] || die "devices.conf has no $DEV.VERSION_PROP"
  v=$(image_prop "$img" "$fs" "$vp")
  [ -n "$v" ] || die "cannot read $vp from $name (not a $fs system image?)"
  [ "$v" = "$VER" ] || die "$name holds $vp=$v but is named $VER: the installer refuses such an image (give the image the version of its name: release_image.sh, or --version of swap_sf_into_image.sh / add_aic_to_image.sh)"
  read -r lp lv <<< "$(conf "$dc" "$DEV" LUMEN_PROP)"
  if [ -n "${lp:-}" ]; then
    got=$(image_prop "$img" "$fs" "$lp")
    [ "$got" = "$lv" ] || die "$name is not Lumen OS for $DEV ($lp='$got')"
  fi
  if [ "$DEV" != z9x ]; then
    sec=$(image_prop "$img" "$fs" ro.adb.secure)
    [ "$sec" = 1 ] || die "$name is a test build (ro.adb.secure='$sec'): the installer refuses it"
  fi
  [ -f "$dir/SHA256SUMS" ] || die "no SHA256SUMS next to $name"
  [ -f "$dir/SHA256SUMS.sig" ] || die "no SHA256SUMS.sig next to $name"
  want=$(awk -v f="$name" '$2==f || $2=="*"f {print $1}' "$dir/SHA256SUMS")
  [ -n "$want" ] || die "SHA256SUMS does not list $name"
  [ "$(sha256 "$img")" = "$want" ] || die "$name does not match its SHA256SUMS line"
  cert=$t/installer/$(conf "$dc" "$DEV" CERT)
  [ -s "$cert" ] || die "$zip has no certificate for $DEV ($(conf "$dc" "$DEV" CERT))"
  command -v openssl >/dev/null 2>&1 || die "needs openssl"
  openssl x509 -in "$cert" -pubkey -noout > "$t/pub" 2>/dev/null || die "bad certificate $cert"
  openssl dgst -sha256 -verify "$t/pub" -signature "$dir/SHA256SUMS.sig" "$dir/SHA256SUMS" >/dev/null 2>&1 \
    || die "SHA256SUMS.sig does not verify with the $DEV certificate in the zip: the installer would refuse it"
  say "OK $(basename "$zip") + $name: version $VER, projector $DEV, signature and checksum good"
}

# ---------------------------------------------------------------- main
if [ "${1:-}" = --check ]; then
  [ $# = 3 ] || usage
  check_pair "$2" "$3"; exit 0
fi
[ $# -ge 2 ] || usage
IMG=$1; OUTDIR=$2; shift 2
SRC=${LUMEN_REPO:-$HOME/lumen-os}/installer; EU=""
while [ $# -gt 0 ]; do
  case "$1" in
    --installer) SRC=${2:?--installer DIR}; shift 2 ;;
    --erofs-utils) EU=${2:?--erofs-utils DIR}; shift 2 ;;
    *) usage ;;
  esac
done
[ -s "$IMG" ] || die "no $IMG"
[ -s "$SRC/lumen-install.sh" ] && [ -s "$SRC/release.conf" ] && [ -s "$SRC/lib/devices.conf" ] \
  || die "$SRC is not an installer folder"
[ -z "$EU" ] || [ -d "$EU" ] || die "no $EU"
parse_image "$(basename "$IMG")" "$SRC/lib/devices.conf"
mkdir -p "$OUTDIR"; OUTDIR=$(cd "$OUTDIR" && pwd)
OUT=$OUTDIR/$(zip_name)
[ -e "$OUT" ] && die "$OUT exists already: remove it first"

W=$(mktemp -d "${TMPDIR:-/tmp}/pack.XXXXXX")
trap 'rm -rf "$W"; rm -f "$OUT.part"' EXIT
mkdir "$W/installer"
# the folder as published: no installer/tools (only --erofs-utils), no dot files, no logs or install state
(cd "$SRC" && find . -type f ! -path './tools/*' ! -name '.*' ! -name '*.log' -print) | while IFS= read -r f; do
  mkdir -p "$W/installer/$(dirname "$f")"; cp -p "$SRC/$f" "$W/installer/$f"
done
if [ -n "$EU" ]; then mkdir -p "$W/installer/tools"; cp -Rp "$EU" "$W/installer/tools/erofs-utils"; fi
RC=$W/installer/release.conf
B=$(kv "$SRC/release.conf" BLOBS)
tr -d '\r' < "$SRC/release.conf" | grep -vE '^(VERSION|DEVICE|BLOBS)=' > "$RC.new"
if [ "$DEV" = z9x ]; then
  [ -z "$B" ] || [ "$B" = partition ] || die "$SRC/release.conf says BLOBS=$B: a release is the public image (partition)"
  printf 'BLOBS=partition\nVERSION=%s\n' "$VER" >> "$RC.new"
else
  printf 'VERSION=%s\nDEVICE=%s\n' "$VER" "$DEV" >> "$RC.new"
fi
mv "$RC.new" "$RC"
(cd "$W" && zip -qrX "$OUT.part" installer) || die "zip failed"
mv "$OUT.part" "$OUT"
if ! (check_pair "$OUT" "$IMG"); then rm -f "$OUT"; die "self-check failed, $OUT removed"; fi
say "wrote $OUT"
