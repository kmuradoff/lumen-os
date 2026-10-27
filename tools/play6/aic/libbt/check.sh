#!/usr/bin/env bash
# Static checks of out/libbt-vendor-aic.so. Usage: tools/check.sh [path/to/vendor/lib]
set -u   # no pipefail: grep -q may close pipes early
W="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${NDK:-$(ls -d "$HOME"/Library/Android/sdk/ndk/29.* | sort | tail -1)}"
T="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"; RE="$T/bin/llvm-readelf"
SR="$T/sysroot/usr/lib/arm-linux-androideabi"
F="$W/out/libbt-vendor-aic.so"; VL="${1:-}"
fail=0; bad() { echo "FAIL: $*"; fail=1; }

echo "== file";      file "$F"
echo "== header";    "$RE" -h "$F" | grep -E 'Class|Machine|Flags'
"$RE" -h "$F" | grep -q 'Flags: *0x5000200' || bad "not EABI5 soft-float-ABI flags"
echo "== dynamic";   "$RE" -d "$F" | grep -E 'NEEDED|SONAME|FLAGS|TEXTREL|RELR'
need=$("$RE" -d "$F" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')
[ "$need" = "libc.so libdl.so liblog.so libm.so " ] || bad "NEEDED = $need"
"$RE" -d "$F" | grep -q TEXTREL && bad "TEXTREL"
echo "== exported (defined) dynamic symbols"
EXP="$("$RE" --dyn-syms "$F" | awk '$1 ~ /^[0-9]+:$/ && $7!="UND" && $5!="LOCAL" && $8!=""')"
echo "$EXP"
echo "$EXP" | grep -qE 'OBJECT +GLOBAL +DEFAULT .* BLUETOOTH_VENDOR_LIB_INTERFACE$' || bad "interface symbol"
[ "$(echo "$EXP" | grep -c .)" = 1 ] || bad "extra exports"
echo "== symbol versions needed"; "$RE" -V "$F" | sed -n '/Version needs/,$p' | grep -E 'File|Name'
for v in $("$RE" -V "$F" | sed -n '/Version needs/,$p' | sed -n 's/.*Name: \([A-Za-z0-9_]*\).*/\1/p'); do
  case "$v" in LIBC|LIBC_N|LIBC_O|LIBC_P|LIBC_Q|LIBC_R|LIBLOG) ;; *) bad "symbol version $v is newer than API 30 (LIBC_R)";; esac
done
echo "== undefined symbols: provider at API 30 stubs, lowest API that has it"
C="$(mktemp -d)"; trap 'rm -rf "$C"' EXIT
defs() { # $1=api $2=lib -> cached sorted list of defined dynamic symbols in the NDK stub
  local f="$C/$1.$2"
  [ -f "$f" ] || { "$RE" --dyn-syms "$SR/$1/$2.so" 2>/dev/null | awk '$1 ~ /^[0-9]+:$/ && $7!="UND" && $8!=""{print $8}' | sed 's/@.*//' | sort -u > "$f"; }
  echo "$f"
}
for s in $("$RE" --dyn-syms "$F" | awk '$1 ~ /^[0-9]+:$/ && $7=="UND" && $8!=""{print $8}' | sed 's/@.*//' | sort -u); do
  prov=""
  for l in libc libm libdl liblog; do
    grep -qx "$s" "$(defs 30 $l)" && { prov=$l; break; }
  done
  [ -n "$prov" ] || { bad "$s not in API 30 libc/libm/libdl/liblog"; continue; }
  min=""; for a in $(ls "$SR" | grep -E '^[0-9]+$' | sort -n); do
    grep -qx "$s" "$(defs $a $prov)" && { min=$a; break; }
  done
  [ "$min" -le 30 ] || bad "$s needs API $min"
  printf '  %-34s %-6s minAPI=%s\n' "$s" "$prov" "$min"
done
echo "== relocation types"; "$RE" -r "$F" | awk '/R_ARM/{print $3}' | sort | uniq -c
"$RE" -r "$F" | grep -qE 'IRELATIVE|TLS' && bad "IRELATIVE/TLS relocation"
"$RE" -l "$F" | grep -q ' TLS ' && bad "PT_TLS segment"
"$RE" --dyn-syms "$F" | grep -qE 'IFUNC|TLS' && bad "IFUNC/TLS symbol"
echo "== config strings"; strings -a "$F" | grep -E '^/system/etc/bluetooth/|^/dev/aicbt_dev$|^/sys/bus/usb/devices$'
if [ -n "$VL" ]; then
  echo "== vendor HAL $VL/hw/android.hardware.bluetooth@1.0-impl.so"
  strings -a "$VL/hw/android.hardware.bluetooth@1.0-impl.so" | grep -xE 'libbt-vendor.so|BLUETOOTH_VENDOR_LIB_INTERFACE' || bad "HAL strings"
  echo "== stock libbt-vendor.so (MTK) for comparison"
  "$RE" -d "$VL/libbt-vendor.so" | grep -E 'SONAME'
  "$RE" --dyn-syms "$VL/libbt-vendor.so" | grep BLUETOOTH_VENDOR_LIB_INTERFACE
fi
[ $fail = 0 ] && echo "ALL CHECKS PASSED" || { echo "CHECKS FAILED"; exit 1; }
