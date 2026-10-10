#!/bin/sh
# build_apk.sh: build a signed APK on the Mac without Gradle and without network.
#
#   sh build_apk.sh APP_DIR [OUT_APK]
#
# APP_DIR layout:
#   AndroidManifest.xml   required (package="..."; versionCode/Name optional, defaults 1 / 1.0)
#   res/                  optional Android resources
#   assets/               optional
#   src/**/*.java         optional; without Java sources the APK is resource-only (RRO etc.),
#                         and the manifest must then say <application android:hasCode="false"/>
#   stub/                 optional, RRO only: AndroidManifest.xml + res/ + ids.txt (aapt2 stable ids)
#                         describing a few resources of the overlay's target. It is linked as a
#                         shared library (--shared-lib) and passed with -I, so the overlay can say
#                         @*target.pkg:type/name and aapt2 writes dynamic references (library id
#                         0x02 -> target package). At runtime AssetManager2 maps that library id to
#                         the target, so the overlay can reuse target resources without overriding
#                         them. ids.txt must list the target's real ids (0x00 package byte); the
#                         stub itself never ships.
#   lib/<abi>/*.so        optional prebuilt JNI libraries (abi = arm64-v8a, armeabi-v7a, x86, x86_64).
#                         They are stored uncompressed and 16 KB page-aligned (zipalign -P 16) so
#                         they load straight from the APK; the manifest must then say
#                         <application android:extractNativeLibs="false">. Without lib/ nothing
#                         changes (zipalign -p 4 as before).
#
# Pipeline: aapt2 compile + link (-I android-34 android.jar, min/target SDK 34) -> javac
# (-source/-target 17 against Android's own java.base, classpath = our framework header jar,
# then android.jar) -> d8 (--min-api 34) -> classes.dex stored uncompressed -> zipalign -p 4 ->
# apksigner (v2 + v3) with the image's platform key -> apksigner verify, certificate check.
#
# Environment overrides (all optional):
#   ANDROID_SDK   default ~/Library/Android/sdk
#   BUILD_TOOLS   default $ANDROID_SDK/build-tools/36.0.0
#   PLATFORM      default android-34
#   JAVA_HOME     default: /usr/libexec/java_home -v 21, else -v 17
#   KEY_PK8 KEY_PEM   default gsi/apps/sdk/platform.pk8 + platform.x509.pem
#   HEADERS_JAR   default gsi/apps/sdk/framework-full-headers.jar
#   VERSION_CODE VERSION_NAME   used only if the manifest has none (aapt2 never overrides)
#   BUILD_DIR     default APP_DIR/build (wiped at start)
#   MIN_SDK TARGET_SDK  default 34 / 34
set -eu

die() { echo "build_apk: $*" >&2; exit 1; }
log() { echo "build_apk: $*"; }

[ $# -ge 1 ] && [ $# -le 2 ] || die "usage: build_apk.sh APP_DIR [OUT_APK]"

HERE=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$1" && pwd) || die "no such dir: $1"
NAME=$(basename "$APP")
OUT=${2:-$APP/$NAME.apk}
case $OUT in /*) ;; *) OUT=$(pwd)/$OUT ;; esac

ANDROID_SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
BUILD_TOOLS=${BUILD_TOOLS:-$ANDROID_SDK/build-tools/36.0.0}
PLATFORM=${PLATFORM:-android-34}
AJ=$ANDROID_SDK/platforms/$PLATFORM/android.jar
CORE=$ANDROID_SDK/platforms/$PLATFORM/core-for-system-modules.jar
SDKDIR=$HERE/sdk
HEADERS_JAR=${HEADERS_JAR:-$SDKDIR/framework-full-headers.jar}
KEY_PK8=${KEY_PK8:-$SDKDIR/platform.pk8}
KEY_PEM=${KEY_PEM:-$SDKDIR/platform.x509.pem}
MIN_SDK=${MIN_SDK:-34}
TARGET_SDK=${TARGET_SDK:-34}
B=${BUILD_DIR:-$APP/build}

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)
fi
[ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ] || die "need a JDK 17+ (set JAVA_HOME)"
PATH=$JAVA_HOME/bin:$PATH   # d8 and apksigner are wrappers that run 'java' from PATH
export PATH JAVA_HOME

for f in "$APP/AndroidManifest.xml" "$AJ" "$KEY_PK8" "$KEY_PEM" \
         "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner"; do
  [ -e "$f" ] || die "missing $f"
done

HAS_CODE=0
if [ -d "$APP/src" ] && [ -n "$(find "$APP/src" -name '*.java' -type f | head -n 1)" ]; then
  HAS_CODE=1
fi
if [ $HAS_CODE = 0 ] && ! grep -q 'hasCode="false"' "$APP/AndroidManifest.xml"; then
  die "no Java sources, so the manifest must have <application android:hasCode=\"false\"/>"
fi

# Optional native libraries: only ABI dirs with .so files, and the manifest must not extract them.
HAS_LIBS=0
if [ -d "$APP/lib" ] && [ -n "$(find "$APP/lib" -type f ! -name .DS_Store | head -n 1)" ]; then
  HAS_LIBS=1
  grep -q 'android:extractNativeLibs="false"' "$APP/AndroidManifest.xml" \
    || die "lib/ has native libraries, so the manifest must have android:extractNativeLibs=\"false\""
  for d in "$APP"/lib/*; do
    [ -d "$d" ] || die "lib/ may only contain ABI directories: $(basename "$d")"
    case $(basename "$d") in
      arm64-v8a|armeabi-v7a|x86|x86_64) ;;
      *) die "lib/$(basename "$d"): unknown ABI" ;;
    esac
  done
  BADLIB=$(find "$APP/lib" -mindepth 2 ! -name .DS_Store \( ! -type f -o ! -name '*.so' \) | head -n 1)
  [ -z "$BADLIB" ] || die "lib/<abi>/ may only contain .so files: $BADLIB"
fi

# A framework RRO that replaces xml/global_keys: a typo there silently drops remote keys,
# so validate it like GlobalKeyManager would read it (gsi/apps/check_global_keys.py).
if [ -f "$APP/res/xml/global_keys.xml" ] && [ -f "$HERE/check_global_keys.py" ]; then
  KC=$HERE/../build/aosp14_keycodes.txt
  if [ -f "$KC" ]; then
    python3 "$HERE/check_global_keys.py" "$APP/res/xml/global_keys.xml" "$KC" || die "global_keys.xml check failed"
  else
    python3 "$HERE/check_global_keys.py" "$APP/res/xml/global_keys.xml" || die "global_keys.xml check failed"
  fi
fi

# org.z9x.projector: every HAL method that transacts must have a reviewed caller outside hal/
# (no dead whitelist entries in the dex; gsi/apps/check_hal_callers.py).
if [ -f "$APP/src/org/z9x/projector/hal/GmpfClient.java" ]; then
  [ -f "$HERE/check_hal_callers.py" ] || die "missing $HERE/check_hal_callers.py"
  python3 "$HERE/check_hal_callers.py" "$APP/src" || die "HAL caller check failed"
fi

rm -rf "$B"
mkdir -p "$B" "$(dirname "$OUT")"

# 1) resources
FLAT=
if [ -d "$APP/res" ] && [ -n "$(find "$APP/res" -type f | head -n 1)" ]; then
  log "aapt2 compile res/"
  "$BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$B/res.zip"
  FLAT=$B/res.zip
fi

STUB=
if [ -f "$APP/stub/AndroidManifest.xml" ]; then
  [ $HAS_CODE = 0 ] || die "stub/ is only for resource-only overlays"
  log "aapt2 stub library from stub/ (target ids from stub/ids.txt)"
  "$BUILD_TOOLS/aapt2" compile --dir "$APP/stub/res" -o "$B/stub-res.zip"
  "$BUILD_TOOLS/aapt2" link --shared-lib -o "$B/stub.apk" -I "$AJ" \
    --manifest "$APP/stub/AndroidManifest.xml" --stable-ids "$APP/stub/ids.txt" "$B/stub-res.zip"
  STUB=$B/stub.apk
fi

log "aapt2 link (min $MIN_SDK, target $TARGET_SDK)"
set -- link -o "$B/base.apk" -I "$AJ" --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}"
[ -n "$STUB" ] && set -- "$@" -I "$STUB"
[ -d "$APP/assets" ] && set -- "$@" -A "$APP/assets"
[ $HAS_CODE = 1 ] && set -- "$@" --java "$B/gen"
[ -n "$FLAT" ] && set -- "$@" "$FLAT"
"$BUILD_TOOLS/aapt2" "$@"

APK=$B/base.apk
if [ $HAS_CODE = 1 ]; then
  [ -e "$HEADERS_JAR" ] || die "missing $HEADERS_JAR"
  [ -e "$CORE" ] || die "missing $CORE"

  # 2) java.base of Android (core-for-system-modules.jar) as a javac --system image, built once
  #    and cached next to the keys. Without it javac -source 17 would compile java.* calls against
  #    the Mac JDK's java.base and could reference methods Android does not have.
  JV=$("$JAVA_HOME/bin/jlink" --version | cut -d. -f1)
  SYSMOD=$SDKDIR/system-modules-$PLATFORM-jdk$JV
  if [ ! -f "$SYSMOD/lib/modules" ] || [ ! -f "$SYSMOD/lib/jrt-fs.jar" ]; then
    log "building javac system image $SYSMOD (one time)"
    W=$B/sysmod
    mkdir -p "$W/cls" "$W/mi" "$W/jmods"
    (cd "$W/cls" && "$JAVA_HOME/bin/jar" xf "$CORE")
    rm -rf "$W/cls/META-INF"
    {
      echo "module java.base {"
      (cd "$W/cls" && find . -name '*.class' | sed 's|^\./||; s|/[^/]*$||' | sort -u | sed 's|/|.|g; s|^|    exports |; s|$|;|')
      echo "}"
    } > "$W/mi/module-info.java"
    "$JAVA_HOME/bin/javac" --system=none --patch-module=java.base="$CORE" -d "$W/cls" "$W/mi/module-info.java"
    "$JAVA_HOME/bin/jmod" create --module-version "$JV" --target-platform LINUX-OTHER \
      --class-path "$W/cls" "$W/jmods/java.base.jmod"
    rm -rf "$SYSMOD.tmp"
    "$JAVA_HOME/bin/jlink" --module-path "$W/jmods" --add-modules java.base \
      --output "$SYSMOD.tmp" --disable-plugin system-modules
    cp "$JAVA_HOME/lib/jrt-fs.jar" "$SYSMOD.tmp/lib/"
    rm -rf "$SYSMOD"
    mv "$SYSMOD.tmp" "$SYSMOD"
  fi

  # 3) javac. Argfile entries are quoted because the project path has spaces.
  { find "$APP/src" -name '*.java' -type f; [ -d "$B/gen" ] && find "$B/gen" -name '*.java' -type f; } \
    | sed 's/.*/"&"/' > "$B/sources.txt"
  log "javac $(wc -l < "$B/sources.txt" | tr -d ' ') files (-source/-target 17)"
  mkdir -p "$B/classes"
  "$JAVA_HOME/bin/javac" --system "$SYSMOD" -XDstringConcat=inline \
    -source 17 -target 17 -encoding UTF-8 -proc:none -g -Xlint:-options \
    -classpath "$HEADERS_JAR:$AJ" -d "$B/classes" @"$B/sources.txt"
  (cd "$B/classes" && "$JAVA_HOME/bin/jar" cf "$B/classes.jar" .)

  # 4) d8
  log "d8 --min-api $MIN_SDK"
  mkdir -p "$B/dex"
  "$BUILD_TOOLS/d8" --release --min-api "$MIN_SDK" --lib "$AJ" --classpath "$HEADERS_JAR" \
    --output "$B/dex" "$B/classes.jar"
  [ -f "$B/dex/classes.dex" ] || die "d8 produced no classes.dex"

  # 5) add classes*.dex, stored (not deflated) so ART can map it from /system directly
  #    with a fixed timestamp (as aapt2 does), so the same sources give a byte-identical APK
  cp "$B/base.apk" "$B/code.apk"
  (cd "$B/dex" && TZ=UTC0 touch -t 198001010000 classes*.dex && TZ=UTC0 zip -q -0 -X "$B/code.apk" classes*.dex)
  APK=$B/code.apk
fi

# 5b) lib/<abi>/*.so, stored (not deflated) with a fixed timestamp, no directory entries
if [ $HAS_LIBS = 1 ]; then
  mkdir -p "$B/native"
  cp -R "$APP/lib" "$B/native/lib"
  find "$B/native" -name .DS_Store -delete
  cp "$APK" "$B/libs.apk"
  log "add $(find "$B/native/lib" -type f | wc -l | tr -d ' ') native libraries (stored)"
  (cd "$B/native" && find lib -type f -exec env TZ=UTC0 touch -t 198001010000 {} + \
     && find lib -type f | LC_ALL=C sort | TZ=UTC0 zip -q -0 -X -D "$B/libs.apk" -@)
  APK=$B/libs.apk
  ALIGN="-P 16"   # 16 KB page alignment of the stored .so files (also satisfies 4 KB devices)
else
  ALIGN="-p"      # 4 KB page alignment of stored .so files (none here); the original behaviour
fi

# 6) zipalign + sign
log "zipalign $ALIGN 4"
"$BUILD_TOOLS/zipalign" $ALIGN -f 4 "$APK" "$B/aligned.apk"
log "apksigner sign (v2 + v3, $(basename "$KEY_PEM"))"
"$BUILD_TOOLS/apksigner" sign --key "$KEY_PK8" --cert "$KEY_PEM" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$B/aligned.apk"
rm -f "$OUT.idsig"

# 7) checks
"$BUILD_TOOLS/zipalign" -c $ALIGN 4 "$OUT" >/dev/null || die "zipalign check ($ALIGN) failed for $OUT"
if [ $HAS_LIBS = 1 ]; then
  NLIB=$(find "$B/native/lib" -type f | wc -l | tr -d ' ')
  NSTORED=$(unzip -Z "$OUT" 'lib/*' | grep -c ' stor ' || true)
  [ "$NSTORED" = "$NLIB" ] || die "expected $NLIB stored lib/ entries, found $NSTORED"
  WANTABI=$(cd "$B/native/lib" && ls | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')
  GOTABI=$("$BUILD_TOOLS/aapt2" dump badging "$OUT" | sed -n "s/^native-code: //p" | tr -d "'" | tr ' ' '\n' | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')
  [ "$WANTABI" = "$GOTABI" ] || die "native-code is '$GOTABI', expected '$WANTABI'"
  log "native-code: $GOTABI ($NLIB libraries stored, 16 KB aligned)"
fi
"$BUILD_TOOLS/apksigner" verify --min-sdk-version "$MIN_SDK" "$OUT" || die "apksigner verify failed"
WANT=$(openssl x509 -in "$KEY_PEM" -outform DER | shasum -a 256 | cut -d' ' -f1)
GOT=$("$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')
[ "$WANT" = "$GOT" ] || die "signer $GOT is not $WANT"
log "OK $OUT"
log "   signer SHA-256 $GOT"
