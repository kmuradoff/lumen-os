#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds the erofs-utils programs the Lumen OS installer ships (installer/tools/erofs-utils/): mkfs.erofs,
# fsck.erofs and dump.erofs of ONE upstream tag, for each computer the installer runs on. The installer builds
# the Google services add-on image (z9x_gapps_<slot>, erofs) on the computer with them; see
# docs/install/ONE_VISIT_GAPPS.md.
#
#   bash tools/ota/build_erofs_utils.sh [all|linux|darwin]      (default all)
#
# Source: the upstream snapshot tarball, sha256 pinned below (the same tag and hash as Homebrew's formula,
# checked against the downloaded tarball 2026-10-30), and lz4 (static, its own pinned tarball, = Homebrew's): the
# installer reads files of the lz4hc-compressed Lumen OS system image with dump.erofs. No other library is
# linked: lzma, zlib, uuid, selinux, fuse, curl, openssl, libxml2, json-c and libnl are all off (the add-on
# image is not compressed, its uid / gid / modes / SELinux labels come from the tar the installer writes).
#   linux-x86_64, linux-aarch64  static (musl, Alpine 3.20) in docker (colima on the Mac); one file each
#   darwin                       universal2 (arm64 + x86_64, macOS 11+), only system libraries
#   windows-x64                  NOT built here (no official Windows build exists; Cygwin or MSYS2 is needed):
#                                until it is, the Windows installer stops before writing anything when
#                                --gapps-zip is used (docs/install/ONE_VISIT_GAPPS.md, section 2)
# Then: installer/lib/erofs_utils.txt (the sha256 of every program, published in git; the installer checks every
# program against it before running it), and next to the programs SOURCE, NOTICE, COPYING, LICENSES/.
# installer/tools/ is not in git (docs/repo/gitignore): the release copies installer/tools/erofs-utils into the
# installer zip (docs/release.md).
# Release check: every platform's mkfs.erofs must turn the reference zip into the image whose sha256 is in
# installer/lib/gapps_images.txt (tools/ota/test/gapps_erofs.sh does it for the programs this computer runs).
# GPL-2.0-or-later: the release that ships these programs also ships the source tarball as its own asset.
set -euo pipefail
VER=1.9.4
SRC_SHA=7d135aa2550326a5acf20f53c518aea5a8900015ce50700044e40f818c31dd80
URL=https://git.kernel.org/pub/scm/linux/kernel/git/xiang/erofs-utils.git/snapshot/erofs-utils-$VER.tar.gz
LZ4_VER=1.10.0           # static, BSD-2-Clause (lib/): dump.erofs must read the lz4hc-compressed Lumen OS system image
LZ4_SHA=537512904744b35e232912055ccf8ec66d768639ff3abe5788d90d792ec5f48b
LZ4_URL=https://github.com/lz4/lz4/releases/download/v$LZ4_VER/lz4-$LZ4_VER.tar.gz
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../.." && pwd)
OUT=$GSI/installer/tools/erofs-utils
WORK=${EROFS_BUILD_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/erofs-build.XXXXXX")}
WHAT=${1:-all}
CONF="--enable-lz4 --disable-lzma --without-zlib --without-uuid --without-selinux --disable-fuse
  --without-libcurl --without-openssl --without-libxml2 --without-json-c --without-libnl3 --disable-dependency-tracking"
PROGS="mkfs.erofs fsck.erofs dump.erofs"
PROGDIRS="mkfs fsck dump"
sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

mkdir -p "$WORK" "$OUT"
tgz=$WORK/erofs-utils-$VER.tar.gz
[ -f "$tgz" ] || curl -fsSL --retry 3 -o "$tgz" "$URL"
[ "$(sha "$tgz")" = "$SRC_SHA" ] || { echo "erofs-utils-$VER.tar.gz: sha256 differs from the pinned one" >&2; exit 1; }
ltgz=$WORK/lz4-$LZ4_VER.tar.gz
[ -f "$ltgz" ] || curl -fsSL --retry 3 -o "$ltgz" "$LZ4_URL"
[ "$(sha "$ltgz")" = "$LZ4_SHA" ] || { echo "lz4-$LZ4_VER.tar.gz: sha256 differs from the pinned one" >&2; exit 1; }

# the autogen'ed source tree (configure made by autoreconf in Alpine), reused for the Mac build
dist=$WORK/erofs-utils-$VER-dist.tar
if [ ! -f "$dist" ]; then
  docker run --rm -i alpine:3.20 sh -c 'set -e
    apk add -q build-base autoconf automake libtool pkgconf linux-headers >/dev/null
    cd /tmp && cat > s.tgz && tar xzf s.tgz && cd erofs-utils-'"$VER"' && ./autogen.sh >/dev/null 2>&1
    cd /tmp && tar cf - erofs-utils-'"$VER" < "$tgz" > "$dist"
fi

build_linux() {  # docker platform, output name
  local plat=$1 name=$2
  rm -rf "${OUT:?}/$name"; mkdir -p "$OUT/$name"
  ( cd "$WORK" && tar cf - "$(basename "$dist")" "$(basename "$ltgz")" ) | docker run --rm -i --platform "$plat" alpine:3.20 sh -c 'set -e
    apk add -q build-base linux-headers bash >/dev/null
    cd /tmp && tar xf - && tar xzf lz4-'"$LZ4_VER"'.tar.gz && make -s -C lz4-'"$LZ4_VER"'/lib liblz4.a CFLAGS="-O2" >/dev/null
    L=/tmp/lz4-'"$LZ4_VER"'/lib
    tar xf erofs-utils-'"$VER"'-dist.tar && cd erofs-utils-'"$VER"'
    CONFIG_SHELL=/bin/bash bash ./configure '"$(echo $CONF)"' --enable-static --disable-shared CFLAGS="-O2" \
      liblz4_CFLAGS="-I$L" liblz4_LIBS="$L/liblz4.a" LDFLAGS="-L$L" >/dev/null
    grep -q "^#define LZ4HC_ENABLED 1" config.h
    make -j4 LDFLAGS="-all-static -L$L" >/dev/null
    mkdir -p /o && for p in '"$PROGDIRS"'; do cp $p/$p.erofs /o/; strip /o/$p.erofs; done
    cd /o && tar cf - .' | tar xf - -C "$OUT/$name"
  for p in $PROGS; do file "$OUT/$name/$p" | grep -q "statically linked" || { echo "$name/$p is not static" >&2; exit 1; }; done
}

build_darwin() {
  local a d
  rm -rf "${OUT:?}/darwin"; mkdir -p "$OUT/darwin"
  for a in arm64 x86_64; do
    d=$WORK/darwin-$a; rm -rf "$d"; mkdir -p "$d"; tar xf "$dist" -C "$d"; tar xzf "$ltgz" -C "$d"
    make -s -C "$d/lz4-$LZ4_VER/lib" liblz4.a CC="clang -arch $a -mmacosx-version-min=11.0" CFLAGS="-O2" >/dev/null
    ( cd "$d/erofs-utils-$VER"
      # shellcheck disable=SC2086
      ./configure $CONF --host="$a-apple-darwin" CC="clang -arch $a -mmacosx-version-min=11.0" CFLAGS="-O2" \
        PKG_CONFIG=/usr/bin/false liblz4_CFLAGS="-I$d/lz4-$LZ4_VER/lib" liblz4_LIBS="$d/lz4-$LZ4_VER/lib/liblz4.a" \
        LDFLAGS="-L$d/lz4-$LZ4_VER/lib" >/dev/null
      grep -q "^#define LZ4HC_ENABLED 1" config.h
      make -j4 >/dev/null 2>&1 )
  done
  for p in $PROGDIRS; do
    lipo -create "$WORK/darwin-arm64/erofs-utils-$VER/$p/$p.erofs" "$WORK/darwin-x86_64/erofs-utils-$VER/$p/$p.erofs" \
      -output "$OUT/darwin/$p.erofs"
    strip "$OUT/darwin/$p.erofs" 2>/dev/null || true
    if otool -L "$OUT/darwin/$p.erofs" | grep '^[[:space:]]' | grep -v -e /usr/lib/ -e /System/Library/ | grep -q .; then
      echo "darwin/$p.erofs links a library outside macOS" >&2; exit 1
    fi
  done
}

case "$WHAT" in
  all) build_linux linux/arm64 linux-aarch64; build_linux linux/amd64 linux-x86_64; build_darwin ;;
  linux) build_linux linux/arm64 linux-aarch64; build_linux linux/amd64 linux-x86_64 ;;
  darwin) build_darwin ;;
  notice) ;;   # only SOURCE, NOTICE, the licenses and SHA256SUMS of what is there
  *) echo "usage: build_erofs_utils.sh [all|linux|darwin|notice]" >&2; exit 2 ;;
esac

rm -rf "${WORK:?}/lic"; mkdir -p "$WORK/lic"
tar xzf "$tgz" -C "$WORK/lic" "erofs-utils-$VER/COPYING" "erofs-utils-$VER/LICENSES"
rm -rf "${OUT:?}/LICENSES"; cp -R "$WORK/lic/erofs-utils-$VER/LICENSES" "$OUT/LICENSES"
cp "$WORK/lic/erofs-utils-$VER/COPYING" "$OUT/COPYING"
printf 'erofs-utils %s\n%s\nsha256 %s\nconfigure %s\nlz4 %s\n%s\nsha256 %s\n' "$VER" "$URL" "$SRC_SHA" "$(echo $CONF)" \
  "$LZ4_VER" "$LZ4_URL" "$LZ4_SHA" > "$OUT/SOURCE"
cat > "$OUT/NOTICE" <<EOF
erofs-utils $VER (mkfs.erofs, fsck.erofs, dump.erofs), https://git.kernel.org/pub/scm/linux/kernel/git/xiang/erofs-utils.git
Built unmodified from the tarball named in SOURCE (sha256 there) by tools/ota/build_erofs_utils.sh of Lumen OS.
Licenses (COPYING, LICENSES/): most files GPL-2.0+ OR MIT or MIT; some files linked into these programs are
GPL-2.0 only (for example lib/hashmap.c), so the programs as built are distributed under GPL-2.0.
The corresponding source is published with every Lumen OS release that ships these programs (release asset
erofs-utils-$VER.tar.gz, the same file) and is available from the URL in SOURCE.
All builds contain lz4 $LZ4_VER (lib/, BSD-2-Clause, $LZ4_URL, sha256 $LZ4_SHA);
the Linux builds are static and also contain musl libc (MIT license, https://musl.libc.org).
EOF
tar xzf "$ltgz" -C "$WORK/lic" "lz4-$LZ4_VER/lib/LICENSE" && cp "$WORK/lic/lz4-$LZ4_VER/lib/LICENSE" "$OUT/LICENSES/lz4-BSD-2-Clause"
SUMS=$GSI/installer/lib/erofs_utils.txt
{ echo "# SPDX-License-Identifier: Apache-2.0"
  echo "# sha256 of the erofs-utils $VER programs in installer/tools/erofs-utils/<platform>/ (made by tools/ota/build_erofs_utils.sh)."
  echo "# The installer runs a program only when its sha256 is the one here. <sha256>  <platform>/<file>"; } > "$OUT/SHA256SUMS.new"
for d in darwin linux-aarch64 linux-x86_64 windows-x64; do
  for p in $PROGS mkfs.erofs.exe fsck.erofs.exe dump.erofs.exe cygwin1.dll msys-2.0.dll; do
    if [ -f "$OUT/$d/$p" ]; then printf '%s  %s\n' "$(sha "$OUT/$d/$p")" "$d/$p" >> "$OUT/SHA256SUMS.new"; fi
  done
done
mv "$OUT/SHA256SUMS.new" "$SUMS"; rm -f "$OUT/SHA256SUMS"
cat "$SUMS"
