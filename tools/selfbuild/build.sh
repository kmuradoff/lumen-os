#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# build.sh: build Lumen OS (the no-Google edition) yourself, from this repository and your own LineageOS 21
# build, signed with your own keys, on one Linux x86-64 machine. Guide: docs/selfbuild/en.md (ru.md).
#
#   tools/selfbuild/build.sh keys                                   your signing keys (once)
#   tools/selfbuild/build.sh base SYSTEM_IMG GAPPS_ZIP BLOBS_TAR    the base system tar
#   tools/selfbuild/build.sh tree                                   the build tree (+ generated inputs)
#   tools/selfbuild/build.sh apps                                   every Lumen OS app, from source
#   tools/selfbuild/build.sh image                                  compose, sign, image, release files
#   tools/selfbuild/build.sh all SYSTEM_IMG GAPPS_ZIP BLOBS_TAR     base, tree, apps, image
#
# Environment:
#   LINEAGE      your LineageOS 21 tree, synced from lineage/manifests/lumen-lineage-21.xml, with
#                lineage/patches applied, `breakfast gsi_tv_arm64` + `m systemimage apksigner` done (required)
#   APKSIGNER    an apksigner with --alignment-preserved (default: the one `m apksigner` builds; the
#                prebuilt in prebuilts/sdk is too old)
#   KEYS_DIR     your keys (default ~/.lumen-self-keys; made by 'keys'; never inside this repository)
#   KEY_OU       the name in your certificates (default self-build)
#   WORK         work folder, no spaces (default build/selfbuild in this repository)
#   ANDROID_NDK  Android NDK r29 (29.0.14206865) for the AirPlay receiver's native library ('apps')
#   AIRPLAY_NATIVE_DIR  instead of the NDK: a folder with arm64-v8a/ and armeabi-v7a/libz9xairplay.so that
#                you built yourself elsewhere with apps/Z9xAirPlay/jni/build_native.sh (e.g. on a Mac)
#   BOOTANIM_ZIP instead of Pillow + numpy here: a bootanimation.zip you made with tools/brand/gen_bootanim.py
#                (checked with its --check)
#   BUILD_DATE   yyyymmdd (default today, UTC)   BUILD_ID_SUFFIX (default snp: s = self, np = no Google)
# Needs: bash, python3 (+ Pillow and numpy for the boot animation), openssl, rsync, unzip, debugfs,
# dump.erofs (erofs-utils), and the tools of the LineageOS tree (aapt2, d8, zipalign, apksigner, JDK 21,
# mkfs.erofs, apexer, deapexer, dex2oat).
set -euo pipefail
die() { echo "selfbuild: $*" >&2; exit 1; }
log() { echo "selfbuild: $*"; }

REPO=$(cd "$(dirname "$0")/../.." && pwd)
LINEAGE=${LINEAGE:-}
KEYS_DIR=${KEYS_DIR:-$HOME/.lumen-self-keys}
KEY_OU=${KEY_OU:-self-build}
WORK=${WORK:-$REPO/build/selfbuild}
TREE=$WORK/tree
SDK=$WORK/sdk
BASE_TAR=$WORK/base.tar
case $WORK in *[[:space:]]*) die "WORK must not contain spaces: $WORK" ;; esac
case $(cd "$(dirname "$KEYS_DIR")" 2>/dev/null && pwd)/ in "$REPO"/*) die "KEYS_DIR must be outside this repository" ;; esac
VER=$(sed -n 's/^VER=//p' "$REPO/tools/build_system.sh" 2>/dev/null || sed -n 's/^VER=//p' "$REPO/tools/lumen_v1.sh")
[ -n "$VER" ] || die "cannot read the version (VER=) of tools/build_system.sh"
VC=$(echo "$VER" | awk -F. '{print $1*100 + $2*10 + $3 + 1}')   # 1.0.2 -> 103 (apps' versionCode)

need_lineage() {
  [ -n "$LINEAGE" ] && [ -d "$LINEAGE/out/target/product/generic_arm64" ] \
    || die "set LINEAGE to your built LineageOS 21 tree (out/target/product/generic_arm64 missing)"
}

# The Lineage tree's own tools, laid out like an Android SDK (build_apk.sh, sign_tar.py and the image
# checks find them there): no separate SDK download.
sdk_view() {
  need_lineage
  local L=$LINEAGE bt=$SDK/build-tools/36.0.0 pl=$SDK/platforms/android-34 t p
  mkdir -p "$bt" "$pl"
  for t in aapt2 zipalign d8 apksigner dexdump; do
    p=""
    [ "$t" = apksigner ] && [ -n "${APKSIGNER:-}" ] && p=$APKSIGNER
    [ -n "$p" ] || for c in "$L/out/host/linux-x86/bin/$t" "$L/prebuilts/sdk/tools/linux/bin/$t" "$L/prebuilts/build-tools/linux-x86/bin/$t"; do
      [ -x "$c" ] && { p=$c; break; }
    done
    [ -n "$p" ] || die "no $t in $L (out/host or prebuilts): finish 'm systemimage' first"
    ln -sfn "$p" "$bt/$t"
  done
  # prebuilts/sdk/tools/linux/bin/apksigner looks for apksigner.jar next to itself or in ./lib, and the
  # prebuilt keeps it in ../lib: a copy of the wrapper with lib/apksigner.jar beside it
  if [ "$(readlink "$bt/apksigner")" = "$L/prebuilts/sdk/tools/linux/bin/apksigner" ]; then
    rm -f "$bt/apksigner"; cp "$L/prebuilts/sdk/tools/linux/bin/apksigner" "$bt/apksigner"; chmod 755 "$bt/apksigner"
    mkdir -p "$bt/lib"; ln -sfn "$L/prebuilts/sdk/tools/linux/lib/apksigner.jar" "$bt/lib/apksigner.jar"
  fi
  for t in android.jar core-for-system-modules.jar; do
    [ -f "$L/prebuilts/sdk/34/public/$t" ] || die "no $L/prebuilts/sdk/34/public/$t"
    ln -sfn "$L/prebuilts/sdk/34/public/$t" "$pl/$t"
  done
  export ANDROID_SDK=$SDK BUILD_TOOLS=$bt
  export JAVA_HOME=${JAVA_HOME:-$L/prebuilts/jdk/jdk21/linux-x86}
  [ -x "$JAVA_HOME/bin/java" ] || die "no JDK at $JAVA_HOME"
  export PATH=$JAVA_HOME/bin:$PATH
  # the signing tools keep each APK's alignment (APEX payload APKs): needs a recent apksigner
  # (output into a variable: grep -q in a pipe under pipefail can fail through SIGPIPE)
  local help; help=$("$bt/apksigner" sign --help 2>&1 || true)
  case $help in *--alignment-preserved*) ;; *) die "apksigner in $bt is too old (no --alignment-preserved): run 'm apksigner' in $L, or set APKSIGNER" ;; esac
}

cmd_keys() {
  if [ -s "$KEYS_DIR/platform.pk8" ]; then log "keys exist in $KEYS_DIR (kept)"; fi
  KEY_OU=$KEY_OU CERTS_OUT=$KEYS_DIR/public bash "$REPO/tools/sign/gen_keys.sh" "$KEYS_DIR"
  log "keys: $KEYS_DIR (private, back it up), certificates: $KEYS_DIR/public"
}

cmd_base() {
  [ $# -eq 3 ] || die "usage: build.sh base SYSTEM_IMG GAPPS_ZIP BLOBS_TAR"
  mkdir -p "$WORK"
  rm -f "$BASE_TAR"
  bash "$REPO/tools/selfbuild/make_base.sh" "$1" "$2" "$3" "$BASE_TAR"
}

cmd_tree() {
  sdk_view
  [ -f "$BASE_TAR" ] || die "no $BASE_TAR (run 'base' first)"
  local layout=$REPO/tools/selfbuild/layout.tsv a b n=0 L=$LINEAGE
  [ -f "$layout" ] || die "no $layout"
  log "1/6 tree: this repository in the layout of the build scripts ($TREE)"
  rm -rf "$TREE"; mkdir -p "$TREE"
  rsync -a --exclude=.git --exclude=/build/ "$REPO/" "$TREE/"
  while IFS=$'\t' read -r a b; do
    case $a in ''|\#*) continue ;; esac
    [ -e "$REPO/$a" ] || die "layout.tsv: no $a"
    mkdir -p "$TREE/$(dirname "$b")"; cp -p "$REPO/$a" "$TREE/$b"; n=$((n + 1))
  done < "$layout"
  log "  $n files moved to their build-tree paths"
  # z9x_setup.rc must stay byte-identical to the v6.5 file it came from (the build checks that copy)
  mkdir -p "$TREE/overlay/v65"; cp -p "$TREE/overlay/v1/z9x_setup.rc" "$TREE/overlay/v65/z9x_setup.rc"
  # the AOSP test platform key (public) signs the apps first; the sign stage re-signs them with yours
  cp -p "$REPO/apps/sdk/testkeys/platform.pk8" "$REPO/apps/sdk/testkeys/platform.x509.pem" "$TREE/apps/sdk/"
  log "2/6 framework header jar of your LineageOS build (apps compile against the hidden APIs)"
  a=$(ls "$L"/out/soong/.intermediates/frameworks/base/framework/android_common/*/turbine-combined/framework.jar 2>/dev/null | head -n 1)
  [ -n "$a" ] || die "no framework header jar in $L/out/soong (finish 'm systemimage')"
  cp "$a" "$TREE/apps/sdk/framework-full-headers.jar"
  log "3/6 libcodec2_vndk with lineage/patches (overlay/v1/c2vndk)"
  git -C "$L/frameworks/av" apply --reverse --check "$REPO/lineage/patches/frameworks_av_C2Store_igba_hidl.patch" 2>/dev/null \
    || die "lineage/patches/frameworks_av_C2Store_igba_hidl.patch is not applied in $L/frameworks/av (apply it, rebuild: docs/selfbuild)"
  # the system variant of the patched library (soong output; also there when the patch came after the
  # first build and only `m libcodec2_vndk` ran), else the installed copy; either must be newer than the
  # patched C2Store.cpp
  local src=$L/frameworks/av/media/codec2/vndk/C2Store.cpp so=$L/out/soong/.intermediates/frameworks/av/media/codec2/vndk/libcodec2_vndk
  for b in lib:android_arm_armv8-a_shared lib64:android_arm64_armv8-a_shared; do
    a=$(find "$so" -path "*/${b#*:}*/libcodec2_vndk.so" ! -path '*/unstripped/*' ! -path '*apex*' -newer "$src" 2>/dev/null | head -n 1)
    if [ -z "$a" ]; then
      a=$L/out/target/product/generic_arm64/system/${b%%:*}/libcodec2_vndk.so
      [ -f "$a" ] && [ "$a" -nt "$src" ] || die "no libcodec2_vndk.so (${b%%:*}) built after the patch: run 'm libcodec2_vndk' (or 'm systemimage') in $L"
    fi
    mkdir -p "$TREE/overlay/v1/c2vndk/system/${b%%:*}"; cp "$a" "$TREE/overlay/v1/c2vndk/system/${b%%:*}/libcodec2_vndk.so"
    log "  ${b%%:*}: ${a#$L/}"
  done
  log "4/6 boot animation (tools/brand/gen_bootanim.py)"
  if [ -n "${BOOTANIM_ZIP:-}" ]; then
    cp "$BOOTANIM_ZIP" "$TREE/overlay/v1/bootanimation.zip"
    # its own --check needs Pillow + numpy too; without them the image build's boot animation check
    # (stored entries, size, desc.txt parts) still runs
    if python3 -c 'import PIL, numpy' 2>/dev/null; then
      python3 "$TREE/tools/brand/gen_bootanim.py" --check "$TREE/overlay/v1/bootanimation.zip"
    fi
  else
    python3 "$TREE/tools/brand/gen_bootanim.py" --out "$TREE/overlay/v1/bootanimation.zip"
  fi
  log "5/6 LineageCustomizer without Play Movies (tools/lumen/gen_customizer.py)"
  ( cd "$TREE" && python3 tools/lumen/gen_customizer.py make "$BASE_TAR" overlay/v1/LineageCustomizer.apk )
  log "6/6 done: $TREE"
}

cmd_apps() {
  sdk_view
  [ -d "$TREE/apps" ] || die "no $TREE (run 'tree' first)"
  local out=$TREE/overlay/apps_v1 a
  mkdir -p "$out"; rm -f "$out"/*.apk "$out/PINS.sha256"
  if [ -n "${AIRPLAY_NATIVE_DIR:-}" ]; then
    log "AirPlay native library from $AIRPLAY_NATIVE_DIR (built with apps/Z9xAirPlay/jni/build_native.sh)"
    for a in arm64-v8a armeabi-v7a; do
      [ -f "$AIRPLAY_NATIVE_DIR/$a/libz9xairplay.so" ] || die "no $AIRPLAY_NATIVE_DIR/$a/libz9xairplay.so"
      mkdir -p "$TREE/apps/Z9xAirPlay/lib/$a"; cp "$AIRPLAY_NATIVE_DIR/$a/libz9xairplay.so" "$TREE/apps/Z9xAirPlay/lib/$a/"
    done
  else
    log "AirPlay native library (apps/Z9xAirPlay/jni/build_native.sh, NDK ${ANDROID_NDK:-?})"
    [ -n "${ANDROID_NDK:-}" ] && [ -x "$ANDROID_NDK/ndk-build" ] || die "set ANDROID_NDK to Android NDK r29 (29.0.14206865)"
    [ -d "$TREE/apps/third_party/UxPlay/lib" ] || die "no apps/third_party/UxPlay: clone with --recurse-submodules (git submodule update --init)"
    ANDROID_NDK=$ANDROID_NDK sh "$TREE/apps/Z9xAirPlay/jni/build_native.sh"
  fi
  for a in Z9xProjector Z9xTvInput Z9xAirPlay Z9xHome Z9xSetup Z9xUpdater Z9xFrameworkKeysOverlay \
           Z9xLineagePlatformOverlay Z9xDeviceConfigOverlay Z9xTvSettingsHdrOverlay; do
    log "app $a"
    VERSION_CODE=$VC VERSION_NAME=$VER BUILD_DIR=$WORK/apkbuild/$a sh "$TREE/apps/build_apk.sh" "$TREE/apps/$a" "$out/$a.apk"
  done
  log "apps: $out"
}

cmd_image() {
  sdk_view
  [ -f "$TREE/tools/lumen_v1.sh" ] || die "no $TREE (run 'tree' first)"
  [ -s "$KEYS_DIR/platform.pk8" ] && [ -s "$KEYS_DIR/public/apex/FINGERPRINTS.txt" ] || die "no keys in $KEYS_DIR (run 'keys')"
  command -v dump.erofs >/dev/null || die "no dump.erofs (install erofs-utils)"
  ( cd "$TREE" && LUMEN_SELF=1 KEYS_DIR=$KEYS_DIR LUMEN_RELEASE_CERTS=$KEYS_DIR/public VARIANT=public GMS=0 \
      BASE=$BASE_TAR LUMEN_OUT=$WORK/out RELDIR=$WORK/release LINEAGE=$LINEAGE \
      BUILD_DATE=${BUILD_DATE:-$(date -u +%Y%m%d)} BUILD_ID_SUFFIX=${BUILD_ID_SUFFIX:-snp} \
      bash tools/lumen_v1.sh self )
  log "release files: $WORK/release (install: $WORK/release/installer/lumen-install.sh --image $WORK/release/lumen-os-$VER-nogms-system.img)"
}

cmd=${1:-}; [ $# -gt 0 ] && shift
case $cmd in
  keys) cmd_keys ;;
  base) cmd_base "$@" ;;
  tree) cmd_tree ;;
  apps) cmd_apps ;;
  image) cmd_image ;;
  all) [ $# -eq 3 ] || die "usage: build.sh all SYSTEM_IMG GAPPS_ZIP BLOBS_TAR"
       cmd_base "$@"; cmd_tree; cmd_apps; cmd_image ;;
  *) sed -n '3,22p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
