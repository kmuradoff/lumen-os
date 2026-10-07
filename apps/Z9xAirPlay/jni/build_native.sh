#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# build_native.sh: build libz9xairplay.so (arm64-v8a + armeabi-v7a) with the Android NDK,
# offline, on the Mac, and install it into Z9xAirPlay/lib/<abi>/.
#
#   sh Z9xAirPlay/jni/build_native.sh            # full clean build (reproducible)
#   KEEP_OBJ=1 sh Z9xAirPlay/jni/build_native.sh # keep build/native/obj for a faster rebuild
#
# Inputs (read only):
#   gsi/apps/third_party/UxPlay      FDH2/UxPlay master 3dbf7ce (GPL-3.0)
#   gsi/apps/third_party/libplist    libplist 2.8.0 fe3dc34 (LGPL-2.1)
#   gsi/apps/third_party/boringssl   BoringSSL 0.20260929.0 (Apache-2.0)
#   gsi/apps/third_party/alac        macosforge/alac c38887c (Apache-2.0)
#   jni/patches/uxplay/*.patch       applied to a copy of UxPlay/lib  (patch -p1, no fuzz)
#   jni/patches/alac/*.patch         applied to a copy of alac/codec  (patch -p1, no fuzz)
#   jni/glue/                        our JNI glue (GPL-3.0)
#
# Steps: copy + patch sources into build/native -> ndk-build through a space-free symlink
# (GNU make cannot handle "XGIMI PLAY 6") -> ELF checks with llvm-readelf / llvm-nm ->
# copy to lib/<abi>/ -> write jni/BUILDINFO.txt.
#
# ELF checks (any failure aborts and nothing is installed):
#   NEEDED  subset of libandroid liblog libmediandk libaaudio libm libdl libc (.so)
#   no TEXTREL, every PT_LOAD aligned to 0x4000 (16 KB pages), SONAME libz9xairplay.so,
#   the only exported dynamic symbol is JNI_OnLoad, armeabi-v7a is EABI5, RegisterNatives
#   table present (string check of the Java method names and signatures).
#
# Environment overrides:
#   ANDROID_NDK   default ~/Library/Android/sdk/ndk/29.0.14206865
#   JOBS          default: number of CPUs
#   KEEP_OBJ      1 = do not wipe build/native/obj
set -eu

die() { echo "build_native: $*" >&2; exit 1; }
log() { echo "build_native: $*"; }

JNI=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$JNI/.." && pwd)
APPS=$(cd "$APP/.." && pwd)
TP=$APPS/third_party
B=$APP/build/native
NDK=${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/29.0.14206865}
JOBS=${JOBS:-$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)}
ABIS="arm64-v8a armeabi-v7a"

# ---- 1. inputs ------------------------------------------------------------------------
[ -x "$NDK/ndk-build" ] || die "no NDK at $NDK (set ANDROID_NDK)"
NDK_REV=$(sed -n 's/^Pkg.Revision *= *//p' "$NDK/source.properties")
TC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
[ -d "$TC" ] || TC=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)
READELF=$TC/llvm-readelf
NM=$TC/llvm-nm
[ -x "$READELF" ] && [ -x "$NM" ] || die "llvm-readelf/llvm-nm missing in $TC"

for d in UxPlay/lib libplist/src boringssl/gen alac/codec; do
  [ -d "$TP/$d" ] || die "missing $TP/$d"
done
[ -f "$TP/boringssl/gen/sources.mk" ] || die "BoringSSL gen/sources.mk missing"

git_head() { git -C "$1" rev-parse HEAD 2>/dev/null || echo "(no git metadata)"; }
UXPLAY_REV=$(git_head "$TP/UxPlay")
PLIST_REV=$(git_head "$TP/libplist")
UXPLAY_VER=$(sed -n 's/.*define VERSION "\(.*\)".*/\1/p' "$TP/UxPlay/uxplay.cpp" | head -1)
BSSL_VER=0.20260929.0
ALAC_REV=c38887c5c5e64a4b31108733bd79ca9b2496d987
log "NDK $NDK_REV"
log "UxPlay $UXPLAY_VER @ $UXPLAY_REV"
log "libplist 2.8.0 @ $PLIST_REV"
log "BoringSSL $BSSL_VER, ALAC $ALAC_REV"

# ---- 2. patched source copies -----------------------------------------------------------
if [ "${KEEP_OBJ:-0}" = 1 ] && [ -d "$B/obj" ]; then
  find "$B" -mindepth 1 -maxdepth 1 ! -name obj -exec rm -rf {} +
else
  rm -rf "$B"
fi
mkdir -p "$B"

apply_patches() { # $1 = dir to patch, $2 = patch dir
  for p in "$2"/*.patch; do
    [ -f "$p" ] || continue
    log "patch $(basename "$p")"
    (cd "$1" && patch -p1 --forward --fuzz=0 --no-backup-if-mismatch -s < "$p") \
      || die "patch failed: $p"
  done
  if find "$1" -name '*.rej' | grep -q .; then die "rejects in $1"; fi
}

cp -R "$TP/UxPlay/lib" "$B/uxplay-lib"
rm -rf "$B/uxplay-lib/dns_sd" "$B/uxplay-lib/mdnsd"   # never built (NsdManager is used)
apply_patches "$B/uxplay-lib" "$JNI/patches/uxplay"
mkdir -p "$B/alac-codec"
cp "$TP"/alac/codec/*.c "$TP"/alac/codec/*.cpp "$TP"/alac/codec/*.h "$B/alac-codec/"
apply_patches "$B/alac-codec" "$JNI/patches/alac"

# ---- 3. ndk-build through a symlink without spaces --------------------------------------
LINK=${TMPDIR:-/tmp}
LINK=${LINK%/}/z9xairplay-root.$$
ln -s "$APPS" "$LINK"
trap 'rm -f "$LINK"' EXIT
trap 'rm -f "$LINK"; exit 130' INT TERM
R=$LINK
RB=$R/Z9xAirPlay/build/native

log "ndk-build -j$JOBS ($ABIS)"
"$NDK/ndk-build" -j"$JOBS" \
  NDK_PROJECT_PATH="$R/Z9xAirPlay" \
  APP_BUILD_SCRIPT="$R/Z9xAirPlay/jni/Android.mk" \
  NDK_APPLICATION_MK="$R/Z9xAirPlay/jni/Application.mk" \
  NDK_OUT="$RB/obj" NDK_LIBS_OUT="$RB/libs" \
  Z9X_ROOT="$R" Z9X_BUILD="$RB" \
  ${V:+V=1} >"$B/ndk-build.log" 2>&1 || { tail -60 "$B/ndk-build.log" >&2; die "ndk-build failed (log: $B/ndk-build.log)"; }
grep -E "warning:" "$B/ndk-build.log" | grep -c . | sed 's/^/build_native: compiler warnings: /' || true

# ---- 4. ELF checks ----------------------------------------------------------------------
ALLOWED_NEEDED="libandroid.so liblog.so libmediandk.so libaaudio.so libm.so libdl.so libc.so"
# Java side (org.z9x.airplay.NativeBridge) that the RegisterNatives table must match.
JNI_METHODS="create start txt raopServiceName setSurface disconnectAll setOutputMuted updatePlaybackInfo isRunning stop destroy"
JNI_SIGS="(Lorg/z9x/airplay/NativeBridge\$Callback;[BLjava/lang/String;Ljava/lang/String;IIZZZIIII)J
(JI)I
(JI)[Ljava/lang/String;
(J)Ljava/lang/String;
(JLandroid/view/Surface;)V
(J)V
(JZ)V
(JFFFZ)V
(J)Z"

check_so() { # $1 abi, $2 so
  abi=$1; so=$2
  [ -f "$so" ] || die "$abi: $so not built"
  needed=$("$READELF" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
  for n in $needed; do
    case " $ALLOWED_NEEDED " in *" $n "*) ;; *) die "$abi: NEEDED $n is not an NDK stable lib" ;; esac
  done
  soname=$("$READELF" -d "$so" | sed -n 's/.*(SONAME).*\[\(.*\)\]/\1/p')
  [ "$soname" = libz9xairplay.so ] || die "$abi: SONAME is '$soname'"
  if "$READELF" -d "$so" | grep -q TEXTREL; then die "$abi: has TEXTREL"; fi
  bad=$("$READELF" -lW "$so" | awk '$1=="LOAD" && $NF!="0x4000"{print $NF}')
  [ -z "$bad" ] || die "$abi: PT_LOAD alignment $bad (need 0x4000)"
  exports=$("$NM" -D --defined-only "$so" | awk '{print $NF}' | sort -u | tr '\n' ' ')
  [ "$exports" = "JNI_OnLoad " ] || die "$abi: exported symbols are: $exports"
  # c++_static: no libc++ symbols may be imported (libc's __cxa_atexit/__cxa_finalize are fine)
  undef_cxx=$("$NM" -D --undefined-only "$so" | awk '{print $NF}' | grep -E '^_Z' || true)
  [ -z "$undef_cxx" ] || die "$abi: undefined C++ runtime symbols: $undef_cxx"
  if [ "$abi" = armeabi-v7a ]; then
    flags=$("$READELF" -h "$so" | sed -n 's/^ *Flags: *\(0x[0-9a-fA-F]*\).*/\1/p')
    case $flags in 0x5000*) ;; *) die "$abi: e_flags $flags is not EABI5" ;; esac
  fi
  for m in $JNI_METHODS; do
    "$READELF" -p .rodata "$so" | grep -q "] *$m\$" || die "$abi: JNI method name '$m' missing"
  done
  echo "$JNI_SIGS" | while IFS= read -r s; do
    "$READELF" -p .rodata "$so" | grep -qF "] $s" || "$READELF" -p .rodata "$so" | grep -qF "$s" \
      || die "$abi: JNI signature '$s' missing"
  done
  log "$abi: OK  NEEDED=[$(echo $needed)]  size=$(wc -c < "$so" | tr -d ' ') bytes"
}

for abi in $ABIS; do
  check_so "$abi" "$B/libs/$abi/libz9xairplay.so"
done

# ---- 5. install + BUILDINFO -------------------------------------------------------------
for abi in $ABIS; do
  mkdir -p "$APP/lib/$abi"
  find "$APP/lib/$abi" -mindepth 1 ! -name libz9xairplay.so -exec rm -rf {} + 2>/dev/null || true
  cp "$B/libs/$abi/libz9xairplay.so" "$APP/lib/$abi/libz9xairplay.so"
done
for d in "$APP"/lib/*; do
  case $(basename "$d") in arm64-v8a|armeabi-v7a) ;; *) die "unexpected dir in lib/: $d" ;; esac
done

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
DL=$TP/_downloads
{
  echo "# Z9xAirPlay native build info (written by jni/build_native.sh, do not edit)"
  echo "built: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "ndk: r29 $NDK_REV ($("$TC/clang" --version | head -1))"
  echo "abis: $ABIS  platform: android-34  stl: c++_static  max-page-size: 16384"
  echo
  echo "sources:"
  echo "  UxPlay      $UXPLAY_VER  $UXPLAY_REV  https://github.com/FDH2/UxPlay  GPL-3.0 (lib/ LGPL-2.1+ origins; llhttp MIT; playfair GPL-3.0)"
  echo "  libplist    2.8.0  $PLIST_REV  https://github.com/libimobiledevice/libplist  LGPL-2.1"
  echo "  BoringSSL   $BSSL_VER  commit 427ec40cc8edc545253289232e885708c409e5ea  https://boringssl.googlesource.com/boringssl  Apache-2.0"
  echo "  ALAC        $ALAC_REV  https://github.com/macosforge/alac  Apache-2.0"
  echo "  glue        jni/glue (GPL-3.0-only where adapted from jqssun/android-airplay-server v0.0.31 c8defdd7, GPL-3.0; other files GPL-3.0-or-later)"
  echo
  echo "patches:"
  for p in "$JNI"/patches/*/*.patch; do
    [ -f "$p" ] && echo "  ${p#$JNI/}  sha256 $(sha "$p")"
  done
  echo
  echo "downloads (official sources, recorded at download time):"
  for f in "$DL"/boringssl-$BSSL_VER.tar.gz "$DL"/alac-c38887c.tar.gz; do
    [ -f "$f" ] || continue
    case $f in
      *boringssl*) url=https://codeload.github.com/google/boringssl/tar.gz/refs/tags/$BSSL_VER ;;
      *alac*)      url=https://codeload.github.com/macosforge/alac/tar.gz/$ALAC_REV ;;
    esac
    echo "  $url"
    echo "    $(wc -c < "$f" | tr -d ' ') bytes  sha256 $(sha "$f")  -> third_party/_downloads/$(basename "$f")"
  done
  echo
  echo "outputs:"
  for abi in $ABIS; do
    so=$APP/lib/$abi/libz9xairplay.so
    echo "  lib/$abi/libz9xairplay.so  $(wc -c < "$so" | tr -d ' ') bytes  sha256 $(sha "$so")"
    echo "    NEEDED: $("$READELF" -d "$so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')"
    echo "    build-id: $("$READELF" -n "$so" | sed -n 's/.*Build ID: *//p')"
  done
  echo
  echo "exported: JNI_OnLoad only. RegisterNatives class org/z9x/airplay/NativeBridge:"
  sed -n 's/^ *{ *"\([A-Za-z]*\)", *"\([^"]*\)".*/  \1 \2/p' "$JNI/glue/z9x_jni.cpp"
} > "$JNI/BUILDINFO.txt"

log "installed: $(cd "$APP" && ls lib/*/libz9xairplay.so | tr '\n' ' ')"
log "build info: $JNI/BUILDINFO.txt"
