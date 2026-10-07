#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# sign_release.sh: turn the laptop's test-key system tar into the flashable release-key image (Mac).
#
#   bash sign_release.sh VERSION [REMOTE_TAR]        e.g.  bash sign_release.sh 1.0
#
#   REMOTE_TAR   default z9x/out/system_tv_lumen<VERSION without dots>.tar on $BUILDER
#
# Steps (keys never leave this Mac; only signed outputs ever go back to the laptop):
#   1. pull   rsync REMOTE_TAR from the build laptop ($BUILDER) into $OUT (resumable), check its sha256
#             against the laptop's sha256sum
#   2. sign   sign_tar.py -> lumen-<ver>-system_signed.tar + report (inventory, APEX, mac_permissions,
#             resigned.tsv pins)
#   3. image  mkfs.erofs -b4096 -zlz4hc -E noinline_data -T1230768000 --mkfs-time --tar=f (tar mtimes kept
#             for the PackageManager parse cache, as tools/lumen_v1.sh) + fsck.erofs; MKFS_ON=mac (default, Homebrew erofs-utils 1.9.x) or
#             MKFS_ON=laptop (push the SIGNED tar, mkfs there with its erofs-utils, pull the image)
#   4. check  check_image.py --tar --img (--variant private for the owner's image)
#   5. sums   SHA256SUMS of the image (+ signature with the OTA key: SHA256SUMS.sig, checked by the
#             installer with the public ota certificate)
#
# VARIANT=private (default, the only variant Lumen OS 1.0 can build: MTK Codec2 libs inside) names the
# image lumen-os-<ver>-PRIVATE-system.img: never a release asset (tools/ota/check_release_assets.py
# refuses it before any gh release). VARIANT=public expects a blob-free image (check_image.py
# --variant public fails otherwise) and names it lumen-os-<ver>-system.img.
#
# Env: BUILDER (user@host of the build laptop) and SSH_KEY (its ssh key), from the environment or
#      from ~/.config/lumen/builder.env (BUILDER=... SSH_KEY=...; personal, never in the repo),
#      KEYS_DIR (default ~/.lumen-keys), OUT (default gsi/build/lumen/<ver>), VARIANT (private|public),
#      MKFS_ON (mac|laptop), BASE_TAR (optional: compat-prop comparison against this tar)
# Laptop use is light: one rsync read (nice/ionice), plus one mkfs only with MKFS_ON=laptop.
set -euo pipefail

die() { echo "sign_release: ERROR: $*" >&2; exit 1; }
log() { echo "$(date +%T) $*"; }

[ $# -ge 1 ] || die "usage: sign_release.sh VERSION [REMOTE_TAR]"
VER=$1
H=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$H/../.." && pwd)
[ -f "$HOME/.config/lumen/builder.env" ] && . "$HOME/.config/lumen/builder.env"
[ -n "${BUILDER:-}" ] || die "set BUILDER=user@host (or ~/.config/lumen/builder.env)"
SSH_KEY=${SSH_KEY:-$HOME/.ssh/id_ed25519}
KEYS_DIR=${KEYS_DIR:-$HOME/.lumen-keys}
VARIANT=${VARIANT:-private}
case $VARIANT in private|public) ;; *) die "VARIANT must be private or public" ;; esac
MKFS_ON=${MKFS_ON:-mac}
REMOTE_TAR=${2:-z9x/out/system_tv_lumen${VER//./}.tar}
OUT=${OUT:-$G/build/lumen/$VER}
SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes "$BUILDER")
NAME=lumen-os-$VER
IN=$OUT/$NAME-system_testkeys.tar
SIGNED=$OUT/$NAME-system_signed.tar
if [ "$VARIANT" = private ]; then IMG=$OUT/$NAME-PRIVATE-system.img; else IMG=$OUT/$NAME-system.img; fi

case $(cd "$KEYS_DIR" 2>/dev/null && pwd -P) in
  ""|*"XGIMI PLAY 6"*) die "keys dir $KEYS_DIR missing or inside the project tree" ;;
esac
mkdir -p "$OUT"

log "1/5 pull $BUILDER:$REMOTE_TAR"
rsync -e "ssh -i $SSH_KEY -o BatchMode=yes" --rsync-path="nice -n 19 ionice -c3 rsync" \
  --partial --append -t "$BUILDER:$REMOTE_TAR" "$IN"
want=$("${SSH[@]}" "nice -n 19 ionice -c3 sha256sum '$REMOTE_TAR'" | cut -d' ' -f1)
got=$(shasum -a 256 "$IN" | cut -d' ' -f1)
[ "$want" = "$got" ] || die "pulled tar sha256 $got != laptop $want (delete $IN and retry)"
log "   sha256 $got"

log "2/5 sign_tar.py"
python3 "$H/sign_tar.py" "$IN" "$SIGNED" --keys "$KEYS_DIR" --report-dir "$OUT/sign-report"

log "3/5 mkfs.erofs ($MKFS_ON)"
rm -f "$IMG" "$IMG.part"
if [ "$MKFS_ON" = laptop ]; then
  RT=z9x/out/$(basename "$SIGNED")
  rsync -e "ssh -i $SSH_KEY -o BatchMode=yes" -t "$SIGNED" "$BUILDER:$RT"
  "${SSH[@]}" "cd z9x/out && nice -n 10 mkfs.erofs -b4096 -zlz4hc -E noinline_data -T1230768000 --mkfs-time --tar=f '$(basename "$IMG")' '$(basename "$SIGNED")' >/dev/null && fsck.erofs '$(basename "$IMG")' >/dev/null"
  rsync -e "ssh -i $SSH_KEY -o BatchMode=yes" -t "$BUILDER:z9x/out/$(basename "$IMG")" "$IMG.part"
else
  command -v mkfs.erofs >/dev/null || die "no mkfs.erofs (brew install erofs-utils)"
  mkfs.erofs -b4096 -zlz4hc -E noinline_data -T1230768000 --mkfs-time --tar=f "$IMG.part" "$SIGNED" > "$OUT/mkfs.log" 2>&1 \
    || { cat "$OUT/mkfs.log"; die "mkfs.erofs failed"; }
fi
fsck.erofs "$IMG.part" >/dev/null || die "fsck.erofs failed"
mv "$IMG.part" "$IMG"

log "4/5 check_image.py ($VARIANT)"
python3 "$H/check_image.py" --tar "$SIGNED" --img "$IMG" --variant "$VARIANT" --keys "$KEYS_DIR" \
  --report-dir "$OUT/check-report" ${BASE_TAR:+--base "$BASE_TAR"}

log "5/5 SHA256SUMS"
(cd "$OUT" && shasum -a 256 "$(basename "$IMG")" > SHA256SUMS)
openssl dgst -sha256 -keyform DER -sign "$KEYS_DIR/ota.pk8" -out "$OUT/SHA256SUMS.sig" "$OUT/SHA256SUMS"
openssl x509 -in "$KEYS_DIR/ota.x509.pem" -pubkey -noout > "$OUT/.ota_pub.pem"
openssl dgst -sha256 -verify "$OUT/.ota_pub.pem" -signature "$OUT/SHA256SUMS.sig" "$OUT/SHA256SUMS" >/dev/null \
  || die "SHA256SUMS.sig does not verify"
rm -f "$OUT/.ota_pub.pem"
cat "$OUT/SHA256SUMS"
log "OK: $IMG (flash with installer/lumen-install.sh --image $IMG)"
