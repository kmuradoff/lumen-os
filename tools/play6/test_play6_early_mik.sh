#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Host test of overlay/play6/play6_early_mik.rc (Play 6 DIAG images):
#   - rc syntax: comments, one 'on' block, 4-space commands, only insmod of /vendor/lib/modules/*.ko and two
#     'write /proc/boottime' marks, no service, no exec, no setprop, no other trigger
#   - the trigger is late-fs AND ro.boot.xgimi.modelname=G0058 (no other projector runs it)
#   - the insmod list equals the vendor's own order in init.fusion.rc from kheaders.ko to mik.ko (lines 28-49 of
#     the stock V1.7.116 vendor), checked against the pinned list below and, when the private stock dump is
#     there, against that file itself
#   - every module exists in the stock vendor dump (when it is there)
#   - with HOST_INIT_VERIFIER=<path> (the base build's out/host/linux-x86/bin/host_init_verifier), Android's own
#     init parser reads the file too
# Run: bash tools/play6/test_play6_early_mik.sh
set -u
G=$(cd "$(dirname "$0")/../.." && pwd)
RC=$G/overlay/play6/play6_early_mik.rc
V=${P6_STOCK_VENDOR:-}   # the private stock dump: logs/play6_<serial>/v1.7.116/fs/vendor, found below
if [ -z "$V" ]; then for d in "$G"/logs/play6_*/v1.7.116/fs/vendor; do [ -f "$d/etc/init/init.fusion.rc" ] && { V=$d; break; }; done; fi
fails=0; n=0
check() { n=$((n + 1)); if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fails=$((fails + 1)); fi; }
PINNED="kheaders kdrv_platform kdrv_xc kdrv_alg kdrv_dolby_vision libdmdbraslia utpa2k iniparser hdcp1x hdcp2x xcker mwgifker misck mik"

body=$(grep -v -e '^#' -e '^[[:space:]]*$' "$RC")
check "file exists, not empty" '[ -s "$RC" ]'
check "no tabs, no trailing spaces, ends with a newline" '! grep -q "$(printf "\t")" "$RC" && ! grep -q " $" "$RC" && [ -z "$(tail -c 1 "$RC")" ]'
check "exactly one block, and it is an 'on' block" '[ "$(echo "$body" | grep -c "^[^ ]")" = 1 ] && echo "$body" | head -1 | grep -q "^on "'
check "trigger: late-fs and the Play 6 model" '[ "$(echo "$body" | head -1)" = "on late-fs && property:ro.boot.xgimi.modelname=G0058" ]'
check "commands indented by 4 spaces" '[ -z "$(echo "$body" | tail -n +2 | grep -v "^    [^ ]")" ]'
cmds=$(echo "$body" | tail -n +2 | sed 's/^    //')
check "only insmod of vendor modules and boottime marks" '[ -z "$(echo "$cmds" | grep -v -E "^(insmod /vendor/lib/modules/[a-z0-9_]+\.ko|write /proc/boottime \"lumen mik chain (start|end)\")$")" ]'
check "marks first and last" '[ "$(echo "$cmds" | head -1)" = "write /proc/boottime \"lumen mik chain start\"" ] && [ "$(echo "$cmds" | tail -1)" = "write /proc/boottime \"lumen mik chain end\"" ]'
check "no service, exec, setprop, start, trigger" '! grep -q -E "^(service|import)|^ *(exec|exec_start|setprop|start|stop|restart|trigger|class_start) " "$RC"'
mods=$(echo "$cmds" | sed -n 's#^insmod /vendor/lib/modules/\(.*\)\.ko$#\1#p' | tr '\n' ' ' | sed 's/ $//')
check "module list equals the pinned vendor order" '[ "$mods" = "$PINNED" ]'
check "no module twice" '[ -z "$(echo "$mods" | tr " " "\n" | sort | uniq -d)" ]'

if [ -n "$V" ] && [ -f "$V/etc/init/init.fusion.rc" ]; then
  F=$V/etc/init/init.fusion.rc
  # the vendor's insmod lines from kheaders.ko to mik.ko, in its early-boot block
  vend=$(awk '$1=="insmod" && $2=="/vendor/lib/modules/kheaders.ko"{on=1} on && $1=="insmod"{m=$2; sub(".*/","",m); sub("[.]ko$","",m); printf "%s ", m; if (m=="mik") exit}' "$F" | sed 's/ $//')
  check "module list equals the stock init.fusion.rc order (kheaders..mik)" '[ "$mods" = "$vend" ]'
  check "the stock lines are 28 (kheaders) and 49 (mik)" '[ "$(grep -n "insmod /vendor/lib/modules/kheaders.ko" "$F" | cut -d: -f1)" = 28 ] && [ "$(grep -n "insmod /vendor/lib/modules/mik.ko" "$F" | cut -d: -f1)" = 49 ]'
  check "the stock loads them at early-boot" '[ "$(awk "/^on /{s=\$0} /insmod \/vendor\/lib\/modules\/mik.ko/{print s}" "$F")" = "on early-boot" ]'
  miss=; for m in $mods; do [ -f "$V/lib/modules/$m.ko" ] || miss="$miss $m"; done
  check "every module is in the stock vendor" '[ -z "$miss" ]'
else
  echo "skip stock vendor checks: no stock vendor dump (private; P6_STOCK_VENDOR=DIR)"
fi
if [ -n "${HOST_INIT_VERIFIER:-}" ]; then
  out=$("$HOST_INIT_VERIFIER" "$RC" 2>&1); rc=$?
  check "host_init_verifier reads it (exit 0)" '[ $rc = 0 ]'
  [ $rc = 0 ] || echo "$out" | tail -20
fi
echo "$((n - fails))/$n passed"
[ $fails = 0 ]
