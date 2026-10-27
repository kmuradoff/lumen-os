#!/usr/bin/env bash
# Build libbt-vendor-aic.so (armeabi-v7a, API 30) with the Android NDK.
set -euo pipefail
W="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-$(ls -d "$HOME"/Library/Android/sdk/ndk/29.* | sort | tail -1)}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
CC="$TC/armv7a-linux-androideabi30-clang"
S="$W/tree/libbt"
OUT="$W/out"; OBJ="$W/obj"
rm -rf "$OBJ"; mkdir -p "$OUT" "$OBJ" "$W/gen"

"$W/tools/gen-buildcfg-portable.sh" "$S/include/vnd_generic.txt" "$W/gen/vnd_buildcfg.h"

SRCS="src/bt_vendor_aic.c src/hardware.c src/hardware_uart.c src/hardware_usb.c
src/userial_vendor.c src/upio.c src/aic_socket.c src/bt_list.c src/bt_skbuff.c
src/hci_h5.c src/aic_parse.c src/aic_btservice.c src/aic_heartbeat.c src/aic_poll.c
src/aic_btsnoop_net.c
codec/plc/sbcplc.c codec/plc/lowcfe_v3.c codec/sbc/sbc.c codec/sbc/sbc_primitives.c
codec/sbc/sbc_primitives_mmx.c codec/sbc/sbc_primitives_neon.c"

CFLAGS=(
  -march=armv7-a -mthumb -mfloat-abi=softfp -mfpu=neon
  -fPIC -O2 -g0
  -ffunction-sections -fdata-sections
  -fno-common -std=gnu99
  -DANDROID -DHAS_NO_BDROID_BUILDCFG
  -Wall -Werror -Wno-switch -Wno-unused-function -Wno-unused-parameter -Wno-unused-variable
  -Wno-unused-but-set-variable
  ${EXTRA_WNO:-}
  -I"$W/gen" -I"$W/shim"
  -I"$S/codec/sbc" -I"$S/codec/plc" -I"$S/include"
  -I"$W/aosp/hci/include" -I"$W/aosp/device/include"
)
# struct layout check against the AOSP android-11 headers (compile only)
"$CC" "${CFLAGS[@]}" -fsyntax-only "$W/tools/layout_check.c"

OBJS=()
for f in $SRCS; do
  o="$OBJ/$(echo "$f" | tr '/' '_').o"
  "$CC" "${CFLAGS[@]}" -c "$S/$f" -o "$o"
  OBJS+=("$o")
done

"$CC" -shared -o "$OUT/libbt-vendor-aic.so" "${OBJS[@]}" \
  -Wl,-soname,libbt-vendor.so \
  -Wl,--gc-sections -Wl,--no-undefined -Wl,-z,defs -Wl,-z,noexecstack \
  -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1 -Wl,--hash-style=both \
  -Wl,--pack-dyn-relocs=none \
  -Wl,--version-script="$W/libbt.map" \
  -nostdlib++ -llog -ldl -lm -lc -s
echo "built $OUT/libbt-vendor-aic.so"
