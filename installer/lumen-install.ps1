# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer for the XGIMI Z9X - Windows (PowerShell 5.1+). BETA: not yet tested on Windows.
# Same steps and the same safety rules as lumen-install.sh (macOS/Linux):
#
#   lumen-install.cmd [command] [-Image FILE] [-Serial S] [-KeepData] [-Wipe format|factory-reset]
#                     [-Lang en|ru] [-Blobs partition|embedded] [-NoBlobs] [-UnsignedImage]
#   -Blobs: published default partition; the owner's PRIVATE image needs -Blobs embedded.
#   -NoBlobs: a public image without the codec files (skips 'blobs'; protected video will not work).
#   -UnsignedImage: the owner's own build without a signed SHA256SUMS (asks you to type UNSIGNED).
#   commands: (none = continue) check | backup | blobs | vbmeta | flash | verify | gsf | status | reset
#
# Writes only system_<slot>, z9x_blobs_<slot>, and formats userdata/metadata/cache. Deletes only *-cow
# leftovers and XGIMI's unused product_<slot>/system_ext_<slot>. NEVER vbmeta (your manual step), boot,
# vendor, persist, xgimi*, misc, frp, the other slot; never 'fastboot -w' or 'fastboot erase'.
# A Windows USB driver for the projector's adb/fastboot interface may be needed (Google USB Driver).
param([string]$Command = "", [string]$Image = "", [string]$Serial = "", [switch]$KeepData,
      [string]$Wipe = "format", [string]$Lang = "", [string]$Blobs = "", [switch]$NoBlobs, [switch]$UnsignedImage)
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Here = $PSScriptRoot
$StateDir = if ($env:LUMEN_STATE_DIR) { $env:LUMEN_STATE_DIR } else { Join-Path $HOME ".lumen-installer" }
$BackupRoot = if ($env:LUMEN_BACKUP_DIR) { $env:LUMEN_BACKUP_DIR } else { Join-Path $HOME "Lumen-backup" }
$ModelCode = "G0082"; $Board = "mt9952"; $FbProduct = "mt5877"; $VendorOk = @("v6.15.58")
$GuideEn = "https://github.com/kmuradoff/lumen-os/blob/main/docs/install/en.md"
$GuideRu = "https://github.com/kmuradoff/lumen-os/blob/main/docs/install/ru.md"
$GoogleGsi = "https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis"
$BlobSize = 4194304
if (-not $Lang) { $Lang = if ((Get-Culture).TwoLetterISOLanguageName -eq "ru") { "ru" } else { "en" } }
$conf = @{}
$rc = Join-Path $Here "release.conf"
if (Test-Path $rc) { Get-Content $rc | Where-Object { $_ -match "^\s*([A-Z_]+)=(.*)$" } | ForEach-Object { $null = $_ -match "^\s*([A-Z_]+)=(.*)$"; $conf[$Matches[1]] = $Matches[2].Trim() } }
if ($NoBlobs) { $Blobs = "none" }
if (-not $Blobs -and $env:BLOBS) { $Blobs = $env:BLOBS }      # an explicit environment value wins over release.conf
if (-not $Blobs) { $Blobs = if ($conf["BLOBS"]) { $conf["BLOBS"] } else { "partition" } }
if (@("partition", "embedded", "none") -notcontains $Blobs) { Write-Host "-Blobs partition|embedded|none (-NoBlobs)"; exit 2 }
if (@("format", "factory-reset") -notcontains $Wipe) { Write-Host "-Wipe format|factory-reset"; exit 2 }

function T($en, $ru) { if ($Lang -eq "ru") { $ru } else { $en } }
function Say($m) { Write-Host ("{0} {1}" -f (Get-Date -Format HH:mm:ss), $m) }
function Ok($m) { Say "  + $m" }
function Warn($m) { Say "  ! $m" }
function Die($m) { Say ((T "STOP" "СТОП") + ": $m"); exit 1 }
function Need($c) { if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { Die (T "missing ${c}: install Android SDK Platform-Tools 35+ and add the folder to PATH" "нет ${c}: установите Android SDK Platform-Tools 35+ и добавьте папку в PATH") } }
function Ask($q) { $a = Read-Host ("$q [" + (T "yes/no" "да/нет") + "]"); return @("y","yes","Yes","YES","д","да","Да","ДА") -contains $a }
function Sha256($f) { (Get-FileHash $f -Algorithm SHA256).Hash.ToLower() }
# identity of the file on disk (size, last write time): a cached check is only valid for exactly that file
function File-Key($f) { $i = Get-Item -LiteralPath $f; "{0}:{1}" -f $i.Length, $i.LastWriteTimeUtc.Ticks }

function State-Get($k) {
  $f = Join-Path $StateDir "state"
  if (-not (Test-Path $f)) { return "" }
  $l = Get-Content $f | Where-Object { $_ -like "$k=*" } | Select-Object -Last 1
  if ($l) { return $l.Substring($k.Length + 1) } else { return "" }
}
function State-Set($k, $v) {
  New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
  $f = Join-Path $StateDir "state"
  $lines = @(); if (Test-Path $f) { $lines = @(Get-Content $f | Where-Object { $_ -notlike "$k=*" }) }
  $lines += "$k=$v"
  Set-Content -Path $f -Value $lines -Encoding UTF8
}

function Adb { & adb -s $script:Serial @args 2>$null }
function Prop($p) { ((Adb shell getprop $p) | Out-String).Trim() }
function Fb { & fastboot -s $script:Serial @args 2>&1 | ForEach-Object { "$_" } }

function Fb-Allowed($act, $part) {
  $s = State-Get slot
  $ok = @("flash:system", "flash:z9x_blobs$s", "create:z9x_blobs$s", "resize:z9x_blobs$s",
          "delete:product$s", "delete:system_ext$s", "format:userdata", "format:metadata", "format:cache")
  return ($ok -contains "${act}:$part") -or ($act -eq "delete" -and $part -like "*-cow")
}
function Fb-Do($act, $part, $arg) {
  if (-not (Fb-Allowed $act $part)) { Die "internal: $act $part is not on the allow-list" }
  switch ($act) {
    "flash"  { Fb flash $part $arg | Out-Host }
    "create" { Fb create-logical-partition $part $arg | Out-Host }
    "resize" { Fb resize-logical-partition $part $arg | Out-Host }
    "delete" { Fb delete-logical-partition $part | Out-Host }
    "format" {
      if ($part -eq "userdata") { Fb "--fs-options=casefold,projid" format:f2fs userdata | Out-Host }
      elseif ($part -eq "metadata") { Fb format:f2fs metadata | Out-Host }
      else { Fb format:ext4 cache | Out-Host }
    }
  }
  return ($LASTEXITCODE -eq 0)
}

function Pick-Device {
  $list = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
  if ($script:Serial) {
    if ($list -contains $script:Serial) { return }
    if (-not $script:SerialFromState) { Die (T "device $($script:Serial) is not connected over adb" "устройство $($script:Serial) не подключено по adb") }
    $script:Serial = ""
  }
  if ($list.Count -ne 1) {
    & adb devices
    Die (T "exactly one projector must be connected over adb, found $($list.Count). USB debugging on? Restarted after enabling it? A-A cable in the USB 2.0 port? ADB driver installed? Allowed the prompt on the screen?" "нужно ровно одно устройство в adb, найдено: $($list.Count). Отладка включена? Проектор перезагружали после её включения? Кабель A–A в USB 2.0? Драйвер ADB установлен? Разрешили запрос на экране?")
  }
  $script:Serial = $list[0]
}

function Find-Image {
  if ($script:Image) { if (-not (Test-Path $script:Image)) { Die (T "no image $($script:Image)" "нет образа $($script:Image)") }; return }
  $c = @(Get-ChildItem -Path (Join-Path $Here "lumen-os-*-system.img"), (Join-Path (Split-Path -Parent $Here) "lumen-os-*-system.img") -ErrorAction SilentlyContinue)
  if ($c.Count -lt 1) { Die (T "no lumen-os-*-system.img found; use -Image FILE" "не найден lumen-os-*-system.img; укажите -Image ФАЙЛ") }
  $script:Image = $c[0].FullName
}

function Check-Tools {
  Need adb; Need fastboot
  $v = ((& fastboot --version | Select-Object -First 1) | Out-String).Trim()
  if ($v -notmatch "fastboot version (\d+)" -or [int]$Matches[1] -lt 35) { Die (T "fastboot is too old ($v): need Platform-Tools 35+" "fastboot слишком старый ($v): нужны Platform-Tools 35+") }
  $dir = Split-Path -Parent (Get-Command fastboot).Source
  if (-not $KeepData -and $Wipe -eq "format") {
    if (-not ((Test-Path (Join-Path $dir "make_f2fs.exe")) -and (Test-Path (Join-Path $dir "mke2fs.exe")))) {
      Die (T "make_f2fs.exe/mke2fs.exe missing next to fastboot ($dir): use the official Platform-Tools zip" "нет make_f2fs.exe/mke2fs.exe рядом с fastboot ($dir): используйте официальный архив Platform-Tools")
    }
  }
  Ok $v
}

function Verify-Sums($sums) {
  # fails closed: a missing signature or certificate stops the install (only -UnsignedImage skips it)
  $sig = "$sums.sig"; $cert = Join-Path $Here "certs\ota.x509.pem"
  if (-not ((Test-Path $sums) -and (Test-Path $sig) -and (Test-Path $cert))) { return $false }
  $c = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList $cert
  $rsa = [System.Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($c)
  $ok = $rsa.VerifyData([IO.File]::ReadAllBytes($sums), [IO.File]::ReadAllBytes($sig),
        [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
  if (-not $ok) { Die (T "SHA256SUMS is not signed by Lumen OS: do not install this image" "SHA256SUMS не подписан Lumen OS: не устанавливайте этот образ") }
  Ok (T "SHA256SUMS signature: Lumen OS release key" "подпись SHA256SUMS: ключ Lumen OS")
  return $true
}

function Check-Image([switch]$Force) {
  Find-Image
  $name = Split-Path -Leaf $script:Image
  $dir = Split-Path -Parent $script:Image
  if ($name -match "PRIVATE" -and $Blobs -ne "embedded") { Die (T "$name is a PRIVATE image (codec files inside): run with -Blobs embedded" "$name - ЧАСТНЫЙ образ (файлы кодеков внутри): запустите с -Blobs embedded") }
  if ($Blobs -eq "none") { Warn (T "-NoBlobs: protected video (e.g. Kinopoisk HD) will not work with this install" "-NoBlobs: защищённое видео (например, Кинопоиск HD) работать не будет") }
  $sums = Join-Path $dir "SHA256SUMS"
  if (-not (Verify-Sums $sums)) {
    if (-not $UnsignedImage) {
      if (-not (Test-Path $sums)) { Die (T "no SHA256SUMS next to the image: it cannot be checked. Download SHA256SUMS and SHA256SUMS.sig from the same release." "рядом с образом нет SHA256SUMS: его нельзя проверить. Скачайте SHA256SUMS и SHA256SUMS.sig из того же релиза.") }
      Die (T "no SHA256SUMS.sig (or installer certificate) next to the image: its origin cannot be checked" "нет SHA256SUMS.sig (или сертификата установщика): происхождение образа нельзя проверить")
    }
    Warn (T "-UnsignedImage: this image is NOT authenticated (no signed SHA256SUMS). Only for your own build." "-UnsignedImage: образ НЕ проверен (нет подписанного SHA256SUMS). Только для собственной сборки.")
    if ((State-Get unsigned_ok) -ne "${name}:$(File-Key $script:Image)") {
      if ((Read-Host (T "Type UNSIGNED to install $name anyway" "Введите UNSIGNED, чтобы всё равно установить $name")) -cne "UNSIGNED") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
      State-Set unsigned_ok "${name}:$(File-Key $script:Image)"
    }
    if (-not (Test-Path $sums)) {
      $sums = $null
      foreach ($f in @(Get-ChildItem -Path (Join-Path $dir "SHA256SUMS*") -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*.sig" })) {
        if (Get-Content $f.FullName | Where-Object { $_ -match "\s\*?$([regex]::Escape($name))$" }) { $sums = $f.FullName; break }
      }
    }
    if (-not $sums) { Warn (T "-UnsignedImage without any SHA256SUMS file: checksum not checked" "-UnsignedImage без файла SHA256SUMS: контрольная сумма не проверена"); return }
  }
  $want = (Get-Content $sums | Where-Object { $_ -match "\s\*?$([regex]::Escape($name))$" } | ForEach-Object { ($_ -split "\s+")[0] }) | Select-Object -First 1
  if (-not $want) { Die (T "$name is not listed in $(Split-Path -Leaf $sums)" "$name нет в $(Split-Path -Leaf $sums)") }
  # the cache only spares a second hash in 'check' for exactly the same file; Cmd-Flash always re-hashes
  if ($Force -or (State-Get image_ok) -ne "${want}:$(File-Key $script:Image)") {
    Say (T "checking the image checksum (about a minute)" "проверяю контрольную сумму образа (около минуты)")
    if ((Sha256 $script:Image) -ne $want.ToLower()) { State-Set image_ok none; Die (T "checksum mismatch: the image is damaged or not the right file" "контрольная сумма не совпала: образ повреждён или не тот") }
    State-Set image_ok "${want}:$(File-Key $script:Image)"
  }
  Ok "$name sha256 $($want.Substring(0,16))..."
}

function Check-Device {
  Pick-Device
  $code = Prop ro.boot.xgimi.modelname; $board = Prop ro.boot.hardware
  $vend = Prop ro.vendor.build.version.incremental; $slot = Prop ro.boot.slot_suffix; $sys = Prop ro.z9x.version
  if ($code -ne $ModelCode -or $board -ne $Board) { Die (T "this is not an XGIMI Z9X (model '$code', board '$board'). Nothing was done." "это не XGIMI Z9X (код '$code', плата '$board'). Ничего не сделано.") }
  if ($VendorOk -notcontains $vend) { Die (T "XGIMI firmware $vend is not supported. Let stock update itself to V6.15.58, then run again." "прошивка XGIMI $vend не поддерживается. Дайте стоку обновиться до V6.15.58 и запустите снова.") }
  State-Set serial $script:Serial; State-Set slot $slot
  if (-not $sys) { $sys = T "XGIMI stock" "родная XGIMI" }
  Ok "XGIMI Z9X $($script:Serial), $(T 'firmware' 'прошивка') $vend, $(T 'slot' 'слот') $($slot.TrimStart('_')), $(T 'system' 'система'): $sys"
}

function Cmd-Check { Check-Tools; Check-Device; Check-Image; Ok (T "check passed; nothing was written" "проверка пройдена; ничего не записано"); State-Set step_check done }

function Cmd-Backup {
  Pick-Device
  $d = Join-Path $BackupRoot (Get-Date -Format "yyyyMMdd-HHmm")
  New-Item -ItemType Directory -Force -Path (Join-Path $d "apps"), (Join-Path $d "sdcard"), (Join-Path $d "settings") | Out-Null
  Say (T "backup to $d" "резервная копия в $d")
  Say (T "IMPORTANT: remove your Google account on the projector first (Settings > Accounts)." "ВАЖНО: сначала удалите аккаунт Google на проекторе (Настройки > Аккаунты).")
  $n = 0
  foreach ($p in @(Adb shell pm list packages -3 | ForEach-Object { "$_".Trim() -replace "^package:", "" } | Where-Object { $_ })) {
    foreach ($path in @(Adb shell pm path $p | ForEach-Object { "$_".Trim() -replace "^package:", "" } | Where-Object { $_ })) {
      $t = Join-Path (Join-Path $d "apps") $p; New-Item -ItemType Directory -Force -Path $t | Out-Null
      Adb pull $path $t | Out-Null; if ($LASTEXITCODE -eq 0) { $n++ }
    }
  }
  Ok (T "$n APK files of your apps (app data cannot be saved without root)" "$n APK ваших приложений (данные приложений без root сохранить нельзя)")
  foreach ($p in @("Download","Movies","Music","Pictures","DCIM","Documents")) { Adb pull "/sdcard/$p" (Join-Path $d "sdcard") | Out-Null }
  foreach ($p in @("global","secure","system")) { Adb shell settings list $p | Set-Content -Encoding UTF8 (Join-Path (Join-Path $d "settings") "$p.txt") }
  State-Set backup $d; State-Set step_backup done
  Ok (T "backup done" "копия готова")
}

# minimal POSIX ustar writer (deterministic: sorted, mtime 0, uid/gid 0), padded to 4 MiB
function Write-Ustar($root, $out) {
  $fs = [IO.File]::Create($out)
  $enc = [Text.Encoding]::ASCII
  $items = @(Get-ChildItem -Recurse -Path $root | ForEach-Object {
    $rel = $_.FullName.Substring($root.Length).TrimStart('\','/') -replace '\\','/'
    [pscustomobject]@{ Rel = $(if ($_.PSIsContainer) { "$rel/" } else { $rel }); Dir = $_.PSIsContainer; Full = $_.FullName }
  } | Sort-Object { $_.Rel } -Culture "")
  foreach ($it in $items) {
    $data = if ($it.Dir) { [byte[]]@() } else { [IO.File]::ReadAllBytes($it.Full) }
    $h = New-Object byte[] 512
    $put = { param($s, $off, $len) $b = $enc.GetBytes($s); [Array]::Copy($b, 0, $h, $off, [Math]::Min($b.Length, $len)) }
    & $put $it.Rel 0 100
    & $put $(if ($it.Dir) { "0000755" } else { "0000644" }) 100 8
    & $put "0000000" 108 8; & $put "0000000" 116 8
    & $put ([Convert]::ToString($data.Length, 8).PadLeft(11, '0')) 124 12
    & $put "00000000000" 136 12
    & $put "        " 148 8
    $h[156] = if ($it.Dir) { [byte][char]'5' } else { [byte][char]'0' }
    & $put "ustar" 257 6; & $put "00" 263 2; & $put "root" 265 32; & $put "root" 297 32
    $sum = 0; foreach ($x in $h) { $sum += $x }
    & $put ([Convert]::ToString($sum, 8).PadLeft(6, '0')) 148 6; $h[154] = 0; $h[155] = 32
    $fs.Write($h, 0, 512)
    if ($data.Length -gt 0) { $fs.Write($data, 0, $data.Length); $pad = (512 - ($data.Length % 512)) % 512; if ($pad) { $fs.Write((New-Object byte[] $pad), 0, $pad) } }
  }
  $fs.Write((New-Object byte[] 1024), 0, 1024)
  if ($fs.Length -gt $BlobSize) { $fs.Close(); Die "blobs tar larger than 4 MiB" }
  $fs.SetLength($BlobSize); $fs.Close()
}

function Cmd-Blobs {
  if ($Blobs -eq "none") { Warn (T "-NoBlobs: codec files skipped (protected video will not work)" "-NoBlobs: файлы кодеков пропущены (защищённое видео работать не будет)"); State-Set step_blobs skip; return }
  if ($Blobs -ne "partition") { Ok (T "this image carries its codec files itself: no blobs step needed" "этот образ содержит файлы кодеков сам: шаг blobs не нужен"); State-Set step_blobs skip; return }
  Pick-Device
  $allow = Join-Path $Here "lib\blobs_allow.txt"
  $w = Join-Path $StateDir "blobs"; Remove-Item -Recurse -Force $w -ErrorAction SilentlyContinue
  $x = Join-Path $w "x"; New-Item -ItemType Directory -Force -Path $x | Out-Null
  $set = ((Get-Content $allow | Where-Object { $_ -like "set=*" } | Select-Object -First 1) -replace "^set=", "")
  $manifest = @("set=$set"); $n = 0
  foreach ($line in Get-Content $allow) {
    if (-not $line -or $line.StartsWith("#") -or $line.StartsWith("set=")) { continue }
    $f = $line -split "\s+"; $h = $f[0]; $rel = $f[1]; $src = $f[2]
    $dst = Join-Path $x ($rel -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    $got = ""
    foreach ($p in @($src, "/system/$rel")) {
      Adb pull $p $dst | Out-Null
      if ($LASTEXITCODE -eq 0 -and (Test-Path $dst)) { $got = Sha256 $dst; if ($got -eq $h) { break } }
    }
    if ($got -ne $h) { Die (T "$rel from the projector does not match the allow-list: protected video is not supported on this firmware (run again with -NoBlobs to install without it)" "$rel с проектора не совпадает со списком: защищённое видео на этой прошивке не поддерживается (запустите с -NoBlobs, чтобы установить без него)") }
    $manifest += "$rel $h $((Get-Item $dst).Length)"; $n++
  }
  [IO.File]::WriteAllText((Join-Path $x "MANIFEST"), (($manifest -join "`n") + "`n"))
  $img = Join-Path $w "z9x_blobs.img"
  Write-Ustar $x $img
  New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
  $out = Join-Path $BackupRoot "blobs-$($script:Serial).img"; Copy-Item $img $out -Force
  State-Set blobs_img $out; State-Set step_blobs done
  Ok (T "$n codec files from your projector, set $set -> $out (keep this file)" "$n файлов кодеков с вашего проектора, набор $set -> $out (сохраните файл)")
}

function Cmd-Vbmeta {
  $g = if ($Lang -eq "ru") { $GuideRu } else { $GuideEn }
  Write-Host ""
  Write-Host (T @"
ONE MANUAL STEP: vbmeta (you do it yourself; this installer never runs any vbmeta command)
  Lumen OS is not signed by XGIMI, so the projector must be told once not to verify the system
  partition: Google's official GSI instructions, section 'Requirements for flashing GSIs' (flash
  Google's unchanged vbmeta.img with verification disabled). Do it for BOTH slots in the same
  fastboot session, then restart, let the projector start normally and connect the cable again.
"@ @"
ОДИН РУЧНОЙ ШАГ: vbmeta (вы делаете его сами; установщик никогда не выполняет команды vbmeta)
  Lumen OS не подписана XGIMI, поэтому проектору один раз нужно разрешить не проверять раздел
  system: официальная инструкция Google для GSI, раздел «Requirements for flashing GSIs»
  (неизменённый vbmeta.img Google с отключённой проверкой). Сделайте это для ОБОИХ слотов в одной
  сессии fastboot, перезагрузите, дайте проектору загрузиться и снова подключите кабель.
"@)
  Write-Host "  Google: $GoogleGsi"
  Write-Host "  $(T 'Guide' 'Инструкция'): $g#vbmeta"
  if (Ask (T "Have you done the vbmeta step and did the projector start normally after it?" "Вы сделали шаг vbmeta и проектор после него нормально загрузился?")) { State-Set step_vbmeta done }
  else { Die (T "do the vbmeta step first, then run the installer again" "сначала шаг vbmeta, затем запустите установщик снова") }
}

function Try-Root {
  if ((Prop ro.debuggable) -ne "1") { return $false }
  if (((Adb shell id -u) | Out-String).Trim() -eq "0") { return $true }
  Adb root | Out-Null; Start-Sleep 3; Adb wait-for-device | Out-Null
  return (((Adb shell id -u) | Out-String).Trim() -eq "0")
}
function Calib-Snapshot($tag) {
  $f = Join-Path $StateDir "calib_$tag.txt"
  $lines = foreach ($p in @("persist","xgimidatabase","xgimisps","xgimicri","project_id","tvcertificate")) {
    ((Adb shell "[ -e /dev/block/by-name/$p ] && sha256sum /dev/block/by-name/$p") | Out-String).Trim()
  }
  Set-Content -Path $f -Value ($lines | Where-Object { $_ }) -Encoding UTF8
  Ok (T "calibration fingerprint ($tag) saved (read-only)" "отпечаток калибровки ($tag) сохранён (только чтение)")
}

function Cmd-Flash {
  Check-Tools; Check-Device; Check-Image -Force
  if ((State-Get step_vbmeta) -ne "done") { Cmd-Vbmeta }
  if ($Blobs -eq "partition" -and (State-Get step_blobs) -ne "done") { Cmd-Blobs }
  $S = State-Get slot
  $blobImg = if ($Blobs -eq "partition") { State-Get blobs_img } else { "" }
  $sl = $S.TrimStart('_')
  if ($KeepData) {
    Write-Host (T "Writing Lumen OS to slot $sl WITHOUT wiping data (repair). On slot $sl this also DELETES XGIMI's product$S and system_ext$S partitions if they still exist (parts of the stock system, unused by Lumen OS; the stock system on the other slot keeps its own) and any *-cow update leftovers. Calibration, the other slot, vendor and boot stay untouched." "Записываю Lumen OS в слот $sl БЕЗ очистки данных (ремонт). В слоте $sl также УДАЛЯЮТСЯ разделы XGIMI product$S и system_ext$S, если они ещё есть (части стоковой системы, Lumen OS их не использует; стоковая система в другом слоте сохраняет свои), и остатки обновлений *-cow. Калибровка, второй слот, vendor и boot не затрагиваются.")
    if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  }
  else {
    Write-Host (T "Writing Lumen OS to slot $sl and then ERASING ALL DATA (apps, accounts, settings, files). On slot $sl XGIMI's product$S and system_ext$S partitions are DELETED (parts of the stock system that Lumen OS does not use; the stock system on the other slot keeps its own), plus any *-cow update leftovers. XGIMI calibration, the other slot, vendor and boot stay untouched." "Записываю Lumen OS в слот $sl, затем СТИРАЮ ВСЕ ДАННЫЕ (приложения, аккаунты, настройки, файлы). В слоте $sl УДАЛЯЮТСЯ разделы XGIMI product$S и system_ext$S (части стоковой системы, Lumen OS их не использует; стоковая система в другом слоте сохраняет свои) и остатки обновлений *-cow. Калибровка XGIMI, второй слот, vendor и boot не затрагиваются.")
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  }
  if (Try-Root) { Calib-Snapshot before }
  Say (T "restarting into fastbootd (about 30 s)" "перезагрузка в fastbootd (около 30 секунд)")
  Adb reboot fastboot | Out-Null
  $seen = $false
  for ($i = 0; $i -lt 45; $i++) { if ((& fastboot devices) -match "^$([regex]::Escape($script:Serial))\s") { $seen = $true; break }; Start-Sleep 2 }
  if (-not $seen) { Die (T "the projector did not appear in fastboot within 90 s (USB driver?)" "проектор не появился в fastboot за 90 секунд (драйвер USB?)") }
  $gv = Fb getvar all
  function Val($k) { $l = $gv | Where-Object { $_ -like "(bootloader) ${k}:*" } | Select-Object -First 1; if ($l) { ($l.Substring(("(bootloader) ${k}:").Length)).Trim() } else { "" } }
  if ((Val is-userspace) -ne "yes") { Fb reboot | Out-Null; Die (T "not fastbootd" "это не fastbootd") }
  if ((Val product) -ne $FbProduct) { Fb reboot | Out-Null; Die (T "fastbootd is not the Z9X one" "fastbootd не от Z9X") }
  if ((Val unlocked) -ne "yes") { Fb reboot | Out-Null; Die (T "the bootloader is locked" "загрузчик заблокирован") }
  if ((Val current-slot) -ne $S.TrimStart('_')) { Fb reboot | Out-Null; Die "slot mismatch" }
  if ((Val snapshot-update-status) -ne "none") { Fb reboot | Out-Null; Die (T "an update is still being finished; let the projector start, wait 10 minutes, try again" "ещё завершается обновление; дайте проектору загрузиться, подождите 10 минут и повторите") }
  $cows = $gv | ForEach-Object { if ($_ -match "partition-size:([a-z0-9_]+-cow):") { $Matches[1] } } | Sort-Object -Unique
  foreach ($p in $cows) { Say ((T "removing update leftover" "удаляю остаток обновления") + " $p"); if (-not (Fb-Do delete $p)) { Fb reboot | Out-Null; Die "delete $p" } }
  foreach ($p in @("product$S", "system_ext$S")) { if ($gv -match "partition-size:${p}:") { Say ((T "removing unused XGIMI partition" "удаляю неиспользуемый раздел XGIMI") + " $p"); $null = Fb-Do delete $p } }
  Say (T "writing system (about 40 s)" "записываю system (около 40 секунд)")
  if (-not (Fb-Do flash system $script:Image)) { Fb reboot | Out-Null; Die (T "system was not written; the projector still has its old system" "system не записан; на проекторе осталась старая система") }
  State-Set step_flash_system done
  if ($blobImg) {
    if ($gv -match "partition-size:z9x_blobs${S}:") { $null = Fb-Do resize "z9x_blobs$S" $BlobSize } else { $null = Fb-Do create "z9x_blobs$S" $BlobSize }
    if (Fb-Do flash "z9x_blobs$S" $blobImg) { Ok "z9x_blobs$S" } else { Warn (T "codec files not written" "файлы кодеков не записаны") }
  }
  if (-not $KeepData) {
    if ($Wipe -eq "format") {
      Say (T "wiping data (format userdata, metadata, cache)" "очистка данных (format userdata, metadata, cache)")
      if (-not (Fb-Do format userdata)) { Fb reboot | Out-Null; Die (T "the wipe failed: do Settings > Device > Reset on the projector instead" "очистка не удалась: сделайте сброс в настройках проектора") }
      $null = Fb-Do format metadata
      if ($gv -match "partition-type:cache:") { $null = Fb-Do format cache }
      State-Set step_wipe done
    } else { State-Set step_wipe factory-reset }
  }
  Fb reboot | Out-Null
  State-Set step_flash done
  Ok (T "written. First start takes 2-4 minutes; the setup screen appears on the projector." "записано. Первый запуск 2–4 минуты; на проекторе появится экран настройки.")
  if ($Wipe -eq "factory-reset" -and -not $KeepData) { Say (T "Now on the projector: Settings > Device preferences > Reset > Factory reset." "Теперь на проекторе: Настройки > Настройки устройства > Сброс.") }
}

function Cmd-Verify {
  Say (T "waiting for the projector to finish starting (up to 5 minutes)" "жду, пока проектор загрузится (до 5 минут)")
  for ($i = 0; $i -lt 150; $i++) {
    $l = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
    if ($l.Count -ge 1) { if (-not $script:Serial) { $script:Serial = $l[0] }; if ((Prop sys.boot_completed) -eq "1") { break } }
    Start-Sleep 2
  }
  if ((Prop sys.boot_completed) -ne "1") { Warn (T "no USB connection to the started system (normal after a wipe: USB debugging is off). Check the projector screen: the Lumen OS setup should be there." "нет USB-связи с загруженной системой (после очистки это нормально: отладка выключена). Посмотрите на экран: должна быть настройка Lumen OS."); return }
  $v = Prop ro.z9x.version; if ($v) { Ok "Lumen OS $v" } else { Warn "ro.z9x.version" }
  if ((Prop ro.z9x.keys) -eq "release") { Ok (T "release keys" "релизные ключи") } else { Warn "ro.z9x.keys" }
  if ((Prop init.svc.gmpf_main) -eq "running") { Ok (T "projector service running" "служба проектора работает") } else { Warn "gmpf_main" }
  if ($Blobs -eq "partition") { if ((Prop sys.z9x.blobs) -eq "ok") { Ok "blobs ok" } else { Warn "sys.z9x.blobs=$(Prop sys.z9x.blobs)" } }
  if ((Test-Path (Join-Path $StateDir "calib_before.txt")) -and (Try-Root)) {
    Calib-Snapshot after
    if ((Get-Content (Join-Path $StateDir "calib_before.txt") -Raw) -eq (Get-Content (Join-Path $StateDir "calib_after.txt") -Raw)) { Ok (T "calibration partitions unchanged" "разделы калибровки не изменились") }
    else { Warn (T "calibration fingerprint differs: keep the files in $StateDir and report it" "отпечаток калибровки отличается: сохраните файлы из $StateDir и сообщите") }
    Adb unroot | Out-Null
  }
  State-Set step_verify done
}

function Cmd-Gsf {
  Pick-Device
  if (-not (Prop ro.z9x.version)) { Die (T "the projector is not running Lumen OS" "на проекторе не Lumen OS") }
  Adb root | Out-Null; Start-Sleep 3; Adb wait-for-device | Out-Null
  $id = ((Adb shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db `"select value from main where name='android_id';`"") | Out-String).Trim()
  Adb unroot | Out-Null
  if (-not $id) { Die (T "no GSF ID yet: connect the projector to the internet, wait two minutes, try again" "GSF ID ещё нет: подключите проектор к интернету, подождите пару минут и повторите") }
  Write-Host "GSF ID: $id"
  Write-Host (T "Register it at https://www.google.com/android/uncertified, wait 10-30 minutes, then sign in on the projector." "Зарегистрируйте его на https://www.google.com/android/uncertified, подождите 10–30 минут и войдите на проекторе.")
}

function Cmd-All {
  if ((State-Get step_check) -ne "done") { Cmd-Check }
  if ((State-Get step_backup) -ne "done") { if (Ask (T "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")) { Cmd-Backup } else { State-Set step_backup skipped } }
  if ($Blobs -eq "partition" -and (State-Get step_blobs) -ne "done") { Cmd-Blobs }
  if ((State-Get step_vbmeta) -ne "done") { Cmd-Vbmeta }
  if ((State-Get step_flash) -ne "done") { Cmd-Flash }
  if ((State-Get step_verify) -ne "done") { Cmd-Verify }
  Write-Host (T "Done. Finish the setup on the projector. For Google sign-in register the GSF ID first: lumen-install.cmd gsf" "Готово. Завершите настройку на проекторе. Для входа в Google сначала зарегистрируйте GSF ID: lumen-install.cmd gsf")
}

$script:Image = $Image; $script:Serial = $Serial; $script:SerialFromState = $false
if (-not $script:Serial) { $script:Serial = State-Get serial; if ($script:Serial) { $script:SerialFromState = $true } }
switch ($Command) {
  ""       { Cmd-All }
  "check"  { Cmd-Check }
  "backup" { Cmd-Backup }
  "blobs"  { Cmd-Blobs }
  "vbmeta" { Cmd-Vbmeta }
  "flash"  { Cmd-Flash }
  "verify" { Cmd-Verify }
  "gsf"    { Cmd-Gsf }
  "status" { $f = Join-Path $StateDir "state"; if (Test-Path $f) { Get-Content $f } else { Write-Host (T "no saved progress" "сохранённого прогресса нет") } }
  "reset"  { Remove-Item -Recurse -Force $StateDir -ErrorAction SilentlyContinue; Write-Host (T "progress forgotten" "прогресс сброшен") }
  default  { Get-Content $PSCommandPath -TotalCount 11 | Select-Object -Skip 1; exit 2 }
}
