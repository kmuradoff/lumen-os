#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Host test: the Windows installer (installer/lumen-install.ps1) and the shell installer (installer/lumen-install.sh)
# do the same in the XGIMI Play 6 pre-checks and the erase request of the light first start (2026-10-31):
#   - the whole ps1 parses;
#   - the erase request: P6-Bcb-Bytes (ps1) and p6_bcb_make (sh) give the same 2048 bytes, whose sha256 is the
#     pinned one of both scripts ($P6BcbSha, P6_BCB_SHA): "boot-recovery" at 0, the arguments at 64, zeros elsewhere;
#   - the USB list and the power log: P6-Usb-Parse / P6-Power-Parse (ps1) and p6_usb_parse / p6_power_parse (sh) read
#     the same fixtures the same way (chip, stick, other devices; battery level, adapter);
#   - the commands sent to the projector are the same strings in both scripts and hold no double quote (PowerShell
#     5.1 does not pass those to adb.exe intact);
#   - Fb-Allowed (ps1) lets 'flash misc' through only while the erase request is written.
# The functions are taken out of the parsed scripts; the installers' main parts are not run.
#   bash tools/ota/test/installer_ps1_play6.sh
# Needs PowerShell: pwsh on PATH, else docker with mcr.microsoft.com/powershell:lts-alpine-3.20. Without either it
# says 'skip'. What it cannot test: Windows PowerShell 5.1 itself, adb.exe and fastboot.exe on Windows.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
IMAGE=mcr.microsoft.com/powershell:lts-alpine-3.20
if command -v pwsh >/dev/null 2>&1; then RUN=local
elif command -v docker >/dev/null 2>&1 && docker image inspect "$IMAGE" >/dev/null 2>&1; then RUN=docker
else echo "skip installer_ps1_play6 (no pwsh, no docker image $IMAGE)"; exit 0; fi
if [ "$RUN" = docker ]; then W=$(mktemp -d "$HOME/.lumen-ps1-p6.XXXXXX"); else W=$(mktemp -d "${TMPDIR:-/tmp}/lumen-ps1-p6.XXXXXX"); fi
W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
cp "$INST/lumen-install.ps1" "$W/lumen-install.ps1"
PASS=0; FAIL=0
ok() { echo "ok   $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }
sha() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

# fixtures: USB lists ("<name> <vid>:<pid> <class>") and power logs, one per file
mkdir -p "$W/fx"
printf '%s\n' "usb1 1d6b:0002 09" "usb3 1d6b:0002 09" "3-1 0e8d:7663 ff" "1-1 0951:1666 08" > "$W/fx/usb1"
printf '%s\n' "usb1 1d6b:0002 09" "3-1 368b:8d90 ff" "1-1 0bda:8153 ff" > "$W/fx/usb2"
printf '%s\n' "usb1 1d6b:0002 09" "3-1 368b:8d91 ff" "1-1 05e3:0610 09" "1-1.1 0951:1666 08" "1-1.2 0bda:8153" > "$W/fx/usb3"
printf '%s\n' "usb1 1d6b:0002 09" "3-1 0e8d:7961 ff" "" "bogus" > "$W/fx/usb4"
: > "$W/fx/usb5"
printf '%s\n' "D/gmpf_main(  311): powerEvent:type{POWER_BATTERY_LEVEL},val{80}" "D/gmpf_main(  311): IsAdapterPowered:1" > "$W/fx/pwr1"
printf '%s\n' "D/gmpf_main(  311): powerEvent:type{POWER_BATTERY_LEVEL},val{91}" "D/gmpf_main(  311): powerEvent:type{POWER_BATTERY_LEVEL},val{LowPowerAlarm}" \
  "D/gmpf_main(  311): powerEvent:type{POWER_ADPATER},val{AdapterOut}" > "$W/fx/pwr2"
printf '%s\n' "I/P6Battery( 900): P6Battery: read level=64 adapter=true source=3" > "$W/fx/pwr3"
printf '%s\n' "D/gmpf_main(  311): IsAdapterPowered:0" "D/gmpf_main(  311): powerEvent:type{POWER_ADPATER},val{AdapterInsert}" > "$W/fx/pwr4"
: > "$W/fx/pwr5"

# the shell installer's functions, taken out of the script (from 'name() {' to the first line '}')
fn() { awk -v n="$1() {" 'index($0, n) == 1 { on = 1 } on { print } on && /^}/ { exit }' "$INST/lumen-install.sh"; }
{ grep '^P6_BCB_ARGS=' "$INST/lumen-install.sh"; for f in p6_chip_name p6_usb_parse p6_power_parse p6_bcb_make; do fn "$f"; done
  cat <<'EOF'
for i in 1 2 3 4 5; do
  p6_usb_parse "$(cat "$FX/usb$i")"; echo "usb$i chip=$P6_CHIP|id=$P6_CHIPID|stick=$P6_STICK|other=$P6_OTHER"
done
for i in 1 2 3 4 5; do
  p6_power_parse "$(cat "$FX/pwr$i")"; echo "pwr$i lvl=$P6_LVL|ad=$P6_AD"
done
p6_bcb_make "$FX/bcb_sh.bin"
EOF
} > "$W/sh_part.sh"
FX=$W/fx bash "$W/sh_part.sh" > "$W/sh.out" 2>&1 || { bad "shell functions: $(cat "$W/sh.out")"; }

cat > "$W/t.ps1" <<'EOF'
param([string]$W)
$ErrorActionPreference = "Stop"
$errs = $null; $tok = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile("$W/lumen-install.ps1", [ref]$tok, [ref]$errs)
if ($errs.Count) { $errs | ForEach-Object { "PARSE $($_.Extent.StartLineNumber): $($_.Message)" }; exit 1 }
"parse ok"
$vars = @("P6BcbArgs", "P6BcbSha", "P6UsbCmd", "P6PwrCmd", "P6MntCmd")
$asg = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.AssignmentStatementAst] -and $n.Left -is [System.Management.Automation.Language.VariableExpressionAst] -and $vars -contains $n.Left.VariablePath.UserPath }, $false)
foreach ($a in $asg) { . ([scriptblock]::Create($a.Extent.Text)) }
$want = @("P6-Chip-Name", "P6-Usb-Parse", "P6-Power-Parse", "P6-Bcb-Bytes", "Fb-Allowed")
$defs = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $want -contains $n.Name }, $true)
foreach ($d in $defs) { . ([scriptblock]::Create($d.Extent.Text)) }
function State-Get($k) { return "" }
foreach ($i in 1..5) {
  $u = P6-Usb-Parse ([IO.File]::ReadAllText("$W/fx/usb$i"))
  "usb$i chip=$($u.Chip)|id=$($u.ChipId)|stick=$(if ($u.Stick) { 1 } else { 0 })|other=$($u.Other)"
}
foreach ($i in 1..5) {
  $p = P6-Power-Parse ([IO.File]::ReadAllText("$W/fx/pwr$i"))
  "pwr$i lvl=$($p.Lvl)|ad=$($p.Ad)"
}
[IO.File]::WriteAllBytes("$W/fx/bcb_ps.bin", (P6-Bcb-Bytes))
"sha $P6BcbSha"
"cmd usb $P6UsbCmd"
"cmd pwr $P6PwrCmd"
"cmd mnt $P6MntCmd"
$script:Projector = "play6"; $script:P6BcbOk = $false
"allow misc off $(Fb-Allowed flash misc)"
$script:P6BcbOk = $true
"allow misc on $(Fb-Allowed flash misc)"
"allow userdata on $(Fb-Allowed format userdata) $(Fb-Allowed flash userdata) $(Fb-Allowed flash vendor)"
EOF
if [ "$RUN" = local ]; then out=$(pwsh -NoProfile -File "$W/t.ps1" -W "$W" 2>&1)
else out=$(docker run --rm -v "$W":/w "$IMAGE" pwsh -NoProfile -File /w/t.ps1 -W /w 2>&1 | grep -v "^WARNING: The requested image's platform" | sed "s|/w/|$W/|g"); fi
printf '%s\n' "$out" > "$W/ps.out"

if grep -qx "parse ok" "$W/ps.out"; then ok parse; else bad "parse: $out"; fi
# the erase request: same bytes, the pinned sha256 in both scripts
SHA_SH=$(sed -n 's/^P6_BCB_SHA=//p' "$INST/lumen-install.sh")
SHA_PS=$(sed -n 's/^sha //p' "$W/ps.out")
if [ -f "$W/fx/bcb_sh.bin" ] && [ -f "$W/fx/bcb_ps.bin" ] && cmp -s "$W/fx/bcb_sh.bin" "$W/fx/bcb_ps.bin" \
   && [ "$(sha "$W/fx/bcb_sh.bin")" = "$SHA_SH" ] && [ "$SHA_SH" = "$SHA_PS" ] && [ "$(wc -c < "$W/fx/bcb_sh.bin" | tr -d ' ')" = 2048 ]; then
  ok "erase request (sha256 ${SHA_SH:0:16}...)"
else bad "erase request: sh $(sha "$W/fx/bcb_sh.bin" 2>/dev/null) ps $(sha "$W/fx/bcb_ps.bin" 2>/dev/null) pinned $SHA_SH / $SHA_PS"; fi
want=$(printf '%s' 'boot-recovery' | od -An -tx1 | tr -d ' \n')
[ "$(head -c 13 "$W/fx/bcb_sh.bin" | od -An -tx1 | tr -d ' \n')" = "$want" ] \
  && [ "$(dd if="$W/fx/bcb_sh.bin" bs=1 skip=64 count=44 2>/dev/null)" = "$(printf 'recovery\n--wipe_data\n--reason=lumen-install\n')" ] \
  && ok "erase request layout" || bad "erase request layout: $(od -c "$W/fx/bcb_sh.bin" | head -6)"
# parsing: line by line the same
for k in usb1 usb2 usb3 usb4 usb5 pwr1 pwr2 pwr3 pwr4 pwr5; do
  a=$(grep "^$k " "$W/sh.out"); b=$(grep "^$k " "$W/ps.out")
  if [ -n "$a" ] && [ "$a" = "$b" ]; then ok "$a"; else bad "$k: sh '$a', ps '$b'"; fi
done
# and what they should say (the shell side; the ps1 side equals it above)
for l in "usb1 chip=MediaTek MT7663 (0e8d:7663)|id=0e8d:7663|stick=1|other=" "usb2 chip=AIC8800 (368b:8d90)|id=368b:8d90|stick=0|other=0bda:8153" \
         "usb3 chip=AIC8800 (368b:8d91)|id=368b:8d91|stick=1|other=05e3:0610 0bda:8153" "usb5 chip=|id=|stick=0|other=" \
         "pwr1 lvl=80|ad=1" "pwr2 lvl=91|ad=0" "pwr3 lvl=64|ad=1" "pwr4 lvl=|ad=1" "pwr5 lvl=|ad="; do
  grep -qxF -- "$l" "$W/sh.out" && ok "expected: $l" || bad "expected '$l': $(cat "$W/sh.out")"
done
# the commands: the same strings, no double quote
for c in usb:P6_USB_CMD pwr:P6_PWR_CMD mnt:P6_MNT_CMD; do
  k=${c%%:*}; v=${c#*:}
  shv=$(bash -c "$(grep "^$v=" "$INST/lumen-install.sh"); printf '%s' \"\$$v\"")
  psv=$(sed -n "s/^cmd $k //p" "$W/ps.out")
  if [ -n "$shv" ] && [ "$shv" = "$psv" ] && ! printf '%s' "$shv" | grep -q '"'; then ok "command $k"; else bad "command $k: sh '$shv' ps '$psv'"; fi
done
grep -qx "allow misc off False" "$W/ps.out" && grep -qx "allow misc on True" "$W/ps.out" && grep -qx "allow userdata on False False False" "$W/ps.out" \
  && ok "Fb-Allowed: misc only while the erase request is written" || bad "Fb-Allowed: $(grep '^allow' "$W/ps.out")"
echo "installer ps1 play6: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
