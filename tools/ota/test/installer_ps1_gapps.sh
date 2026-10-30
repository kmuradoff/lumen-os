#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Host test of the Windows installer's one-pass add-on code (installer/lumen-install.ps1): the whole script must
# parse, and its OWN tar writer (Gi-Tar with Gi-Hdr, Gi-Entry, Gi-Pad, Gi-Lines, taken out of the parsed script, the
# installer's main part not run) must give, with the mkfs.erofs of installer/tools/erofs-utils and the 'plain'
# flags, exactly the image tools/ota/gapps_allow.py makes (the reference of lib/gapps_images.txt): for the fake zip
# of gapps_fixture.py and, when present, the real MindTheGapps arm64 zip at lineage/gapps/ (a local input only).
#   bash tools/ota/test/installer_ps1_gapps.sh
# Needs PowerShell: pwsh on PATH, else docker with mcr.microsoft.com/powershell:lts-alpine-3.20 (PowerShell 7 on
# Linux; the work folder is then under $HOME, which colima shares with its VM). Without either it says 'skip'.
# What it cannot test: Windows PowerShell 5.1 itself, the Windows erofs-utils build (not made yet), fastboot.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../../.." && pwd)
INST=$GSI/installer
REAL=$GSI/lineage/gapps/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip
IMAGE=mcr.microsoft.com/powershell:lts-alpine-3.20
if command -v pwsh >/dev/null 2>&1; then RUN=local
elif command -v docker >/dev/null 2>&1 && docker image inspect "$IMAGE" >/dev/null 2>&1; then RUN=docker
else echo "skip installer_ps1_gapps (no pwsh, no docker image $IMAGE)"; exit 0; fi
case "$(uname -s)" in Darwin) EP=darwin ;; *) case "$(uname -m)" in x86_64|amd64) EP=linux-x86_64 ;; *) EP=linux-aarch64 ;; esac ;; esac
EU=$INST/tools/erofs-utils/$EP
[ -x "$EU/mkfs.erofs" ] || { echo "no installer/tools/erofs-utils/$EP (build it: tools/ota/build_erofs_utils.sh)"; exit 1; }
if [ "$RUN" = docker ]; then W=$(mktemp -d "$HOME/.lumen-ps1-test.XXXXXX"); else W=$(mktemp -d "${TMPDIR:-/tmp}/lumen-ps1-test.XXXXXX"); fi
W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/g/lib" "$W/out"
cp "$INST/lumen-install.ps1" "$W/lumen-install.ps1"
python3 "$HERE/gapps_fixture.py" make "$GSI/tools/ota/image/gapps_allow.txt" "$W/fx" || exit 1
cp "$INST/lib/gapps_allow.txt" "$W/g/lib/gapps_allow.txt"
[ -f "$REAL" ] && ln "$REAL" "$W/g/real.zip" 2>/dev/null || { [ -f "$REAL" ] && cp "$REAL" "$W/g/real.zip"; }
cat > "$W/t.ps1" <<'EOF'
param([string]$W)
$ErrorActionPreference = "Stop"
$errs = $null; $tok = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile("$W/lumen-install.ps1", [ref]$tok, [ref]$errs)
if ($errs.Count) { $errs | ForEach-Object { "PARSE $($_.Extent.StartLineNumber): $($_.Message)" }; exit 1 }
"parse ok"
$want = @("Gi-Hdr", "Gi-Entry", "Gi-Pad", "Gi-Lines", "Gi-Tar", "Gapps-Line", "Sha256")
$defs = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $want -contains $n.Name }, $true)
foreach ($d in $defs) { . ([scriptblock]::Create($d.Extent.Text)) }
function Die($m) { throw "DIE $m" }
$GiLabel = "u:object_r:system_file:s0"; $GiMtime = [int64]1230768000
$cases = @(,@("$W/fx/allow.txt", (Get-ChildItem "$W/fx/zip/*.zip")[0].FullName, "fake"))
if (Test-Path "$W/g/real.zip") { $cases += ,@("$W/g/lib/gapps_allow.txt", "$W/g/real.zip", "real") }
foreach ($c in $cases) {
  $Here = "$W/h-$($c[2])"; New-Item -ItemType Directory -Force -Path "$Here/lib" | Out-Null
  Copy-Item $c[0] "$Here/lib/gapps_allow.txt" -Force
  $script:GzSha = Sha256 $c[1]; $script:GzName = (("$(Gapps-Line $script:GzSha)") -split " ")[1]
  New-Item -ItemType Directory -Force -Path "$W/out/$($c[2])" | Out-Null
  $why = Gi-Tar $c[1] "$W/out/$($c[2])/gapps.tar"
  "tar $($c[2]) '$why'"
}
EOF
if [ "$RUN" = local ]; then out=$(pwsh -NoProfile -File "$W/t.ps1" -W "$W" 2>&1)
else out=$(docker run --rm -v "$W":/w "$IMAGE" pwsh -NoProfile -File /w/t.ps1 -W /w 2>&1 | grep -v "^WARNING: The requested image's platform"); fi
PASS=0; FAIL=0
check() {  # name zip allow: the PowerShell tar -> mkfs.erofs = the Python reference
  local ref img
  if ! printf '%s\n' "$out" | grep -qx "tar $1 ''"; then echo "FAIL $1: $out"; FAIL=$((FAIL + 1)); return; fi
  ref=$(python3 "$GSI/tools/ota/gapps_allow.py" image "$2" --allow "$3" --mkfs "$EU/mkfs.erofs" --out "$W/out/py.img") || { echo "FAIL $1: gapps_allow.py"; FAIL=$((FAIL + 1)); return; }
  "$EU/mkfs.erofs" -b4096 -E noinline_data -T1230768000 -U 00000000-0000-0000-0000-000000000000 --all-root --workers=1 \
    -Lz9x_gapps --tar=f "$W/out/$1/ps.img" "$W/out/$1/gapps.tar" >/dev/null 2>&1
  img=$(shasum -a 256 "$W/out/$1/ps.img" | cut -d' ' -f1)
  if [ "$img" = "$(echo "$ref" | awk '{ print $4 }')" ]; then echo "ok   $1 ($img)"; PASS=$((PASS + 1))
  else echo "FAIL $1: PowerShell image $img, reference $ref"; FAIL=$((FAIL + 1)); fi
  rm -rf "${W:?}/out/$1"
}
if printf '%s\n' "$out" | grep -qx "parse ok"; then echo "ok   parse"; PASS=$((PASS + 1)); else echo "FAIL parse: $out"; FAIL=$((FAIL + 1)); fi
check fake "$(ls "$W/fx/zip/"*.zip)" "$W/fx/allow.txt"
if [ -f "$W/g/real.zip" ]; then check real "$W/g/real.zip" "$INST/lib/gapps_allow.txt"; else echo "skip real (no $REAL)"; fi
echo "installer ps1 gapps: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
