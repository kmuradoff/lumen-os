# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer for the XGIMI Z9X - Windows (PowerShell 5.1+). BETA: not yet tested on Windows.
# Same steps and the same safety rules as lumen-install.sh (macOS/Linux):
#
#   lumen-install.cmd [command] [-Image FILE] [-Serial S] [-KeepData] [-Wipe format|factory-reset]
#                     [-Lang en|ru] [-Blobs partition|embedded] [-NoBlobs] [-UnsignedImage] [-Zip FILE] [-Remove]
#   commands: (none = continue) check | backup | blobs | vbmeta | flash | verify | gsf | rescue | gapps | status | reset
#   gapps -Zip FILE: Lumen OS without Google + Google services from YOUR OWN MindTheGapps download (the guide
#   names the one accepted file), in their own partition z9x_gapps_<slot>, then the data wipe of 'flash'
#   (-KeepData: no wipe). gapps -Remove: takes them out (also from fastboot mode). gapps: their state.
#   -Blobs: published default partition; the owner's PRIVATE image needs -Blobs embedded.
#   -NoBlobs: a public image without the codec and audio files (skips 'blobs': NO SOUND, no protected video).
#   -UnsignedImage: the owner's own build without a signed SHA256SUMS (asks you to type UNSIGNED).
#   -Image: required when several lumen-os-*-system.img lie next to the installer (with and without Google).
#   gsf: only when Google services are there (built in, or added with 'gapps').
#
# rescue: Lumen OS that no longer starts goes to fastboot mode by itself after 3 failed starts in a row;
# 'rescue' waits for it there and writes system (+ z9x_blobs) again WITHOUT wiping data (no adb needed).
# Writes only system_<slot>, z9x_blobs_<slot>, z9x_gapps_<slot> ('gapps': create/resize/format/delete, filled
# over adb root), and formats userdata/metadata/cache. Deletes only *-cow
# leftovers and XGIMI's unused product_<slot>/system_ext_<slot>. 'rescue' may also finish or cancel an
# unfinished Lumen OS update of the slot it repairs (fastboot snapshot-update merge|cancel) and never
# formats anything. NEVER vbmeta (your manual step), boot, vendor, persist, xgimi*, misc, frp, the
# other slot; never 'fastboot -w' or 'fastboot erase'.
# A Windows USB driver for the projector's adb/fastboot interface may be needed (Google USB Driver).
param([string]$Command = "", [string]$Image = "", [string]$Serial = "", [switch]$KeepData,
      [string]$Wipe = "format", [string]$Lang = "", [string]$Blobs = "", [switch]$NoBlobs, [switch]$UnsignedImage,
      [string]$Zip = "", [switch]$Remove)
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Here = $PSScriptRoot
$StateDir = if ($env:LUMEN_STATE_DIR) { $env:LUMEN_STATE_DIR } else { Join-Path $HOME ".lumen-installer" }
$BackupRoot = if ($env:LUMEN_BACKUP_DIR) { $env:LUMEN_BACKUP_DIR } else { Join-Path $HOME "Lumen-backup" }
$ModelCode = "G0082"; $Board = "mt9952"; $FbProduct = "mt5877"; $VendorOk = @("v6.15.58", "v6.15.19")
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
if ($Command -ne "gapps" -and ($Zip -or $Remove)) { Write-Host "-Zip / -Remove belong to 'gapps'"; exit 2 }

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
          "create:z9x_gapps$s", "resize:z9x_gapps$s", "format:z9x_gapps$s", "delete:z9x_gapps$s",
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
      else { Fb format:ext4 $part | Out-Host }  # cache, z9x_gapps_<slot>
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
  # never pick one of several by name order: the editions (with and without Google) differ
  if ($c.Count -gt 1) { Die (T "several images found (with and without Google?): choose one with -Image FILE" "найдено несколько образов (с Google и без?): выберите один через -Image ФАЙЛ") }
  $script:Image = $c[0].FullName
}

# the edition of the image (1.0.1): 0 = Lumen OS without Google (-nogms- in the name, ro.z9x.gms=0), 1 = with
# Google services. With dump.erofs on this computer the image's own product build.prop must agree.
function Image-Edition {
  $name = Split-Path -Leaf $script:Image
  $g = if ($name -like "*-nogms-*") { "0" } else { "1" }
  if (Get-Command dump.erofs -ErrorAction SilentlyContinue) {
    $bp = @(& dump.erofs --cat --path=/system/product/etc/build.prop $script:Image 2>$null)
    if ($LASTEXITCODE -eq 0 -and $bp.Count -gt 0) {
      $p = ($bp | Where-Object { $_ -like "ro.z9x.gms=*" } | Select-Object -Last 1)
      $p = if ($p) { $p.Substring(11).Trim() } else { "" }
      if (($g -eq "0" -and $p -ne "0") -or ($g -eq "1" -and $p -eq "0")) {
        Die (T "${name}: the file name and the image disagree about Google services (ro.z9x.gms='$p'): do not install it" "${name}: имя файла и сам образ расходятся насчёт сервисов Google (ro.z9x.gms='$p'): не устанавливайте его")
      }
    }
  }
  State-Set image_gms $g
  if ($g -eq "0") { Ok (T "edition: Lumen OS without Google" "редакция: Lumen OS без Google") } else { Ok (T "edition: with Google services" "редакция: с сервисами Google") }
}

# keep-data repairs never switch editions: Play and Google services updates in /data would be orphaned and
# Google accounts would survive. dev_gms comes from Check-Device (0, 1, or stock).
function Edition-Guard($mode) {
  $d = State-Get dev_gms; $i = State-Get image_gms
  if ($d -eq "0" -or $d -eq "1") {
    if ($d -eq $i) { return }
    if ($d -eq "0") { Die (T "this projector runs Lumen OS without Google and the image has Google services: switching editions needs a full install with a data wipe (without -KeepData)" "на проекторе Lumen OS без Google, а образ с сервисами Google: смена редакции требует полной установки с очисткой данных (без -KeepData)") }
    Die (T "this projector runs Lumen OS with Google services and the image is without Google: switching editions needs a full install with a data wipe (without -KeepData)" "на проекторе Lumen OS с сервисами Google, а образ без Google: смена редакции требует полной установки с очисткой данных (без -KeepData)")
  }
  if ($mode -eq "rescue") { Warn (T "the edition installed on this projector is unknown here: make sure the image is the same edition (with or without Google)" "редакция на этом проекторе здесь неизвестна: убедитесь, что образ той же редакции (с Google или без)") }
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
  if ($Blobs -eq "none") { Warn (T "-NoBlobs: no sound and no protected video (e.g. Kinopoisk HD) with this install" "-NoBlobs: не будет звука и защищённого видео (например, Кинопоиск HD)") }
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
    if (-not $sums) { Warn (T "-UnsignedImage without any SHA256SUMS file: checksum not checked" "-UnsignedImage без файла SHA256SUMS: контрольная сумма не проверена"); Image-Edition; return }
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
  Image-Edition
}

function Check-Device {
  Pick-Device
  $code = Prop ro.boot.xgimi.modelname; $board = Prop ro.boot.hardware
  $vend = Prop ro.vendor.build.version.incremental; $slot = Prop ro.boot.slot_suffix; $sys = Prop ro.z9x.version
  if ($code -ne $ModelCode -or $board -ne $Board) { Die (T "this is not an XGIMI Z9X (model '$code', board '$board'). Nothing was done." "это не XGIMI Z9X (код '$code', плата '$board'). Ничего не сделано.") }
  if ($VendorOk -notcontains $vend) { Die (T "XGIMI firmware $vend is not supported (V6.15.58 or V6.15.19 only). Let stock update itself to V6.15.58, then run again." "прошивка XGIMI $vend не поддерживается (только V6.15.58 или V6.15.19). Дайте стоку обновиться до V6.15.58 и запустите снова.") }
  State-Set serial $script:Serial; State-Set slot $slot
  if ($sys) { $g = Prop ro.z9x.gms; State-Set dev_gms $(if ($g) { $g } else { "1" }) } else { State-Set dev_gms stock }
  if (-not $sys) { $sys = T "XGIMI stock" "родная XGIMI" }
  Ok "XGIMI Z9X $($script:Serial), $(T 'firmware' 'прошивка') $vend, $(T 'slot' 'слот') $($slot.TrimStart('_')), $(T 'system' 'система'): $sys"
}

function Cmd-Check { Check-Tools; Check-Device; Check-Image; Ok (T "check passed; nothing was written" "проверка пройдена; ничего не записано"); State-Set step_check done }

function Cmd-Backup {
  Pick-Device
  $d = Join-Path $BackupRoot (Get-Date -Format "yyyyMMdd-HHmm")
  New-Item -ItemType Directory -Force -Path (Join-Path $d "apps"), (Join-Path $d "sdcard"), (Join-Path $d "settings") | Out-Null
  Say (T "backup to $d" "резервная копия в $d")
  if ((State-Get image_gms) -eq "0") {
    Say (T "Recommended: remove your Google account on the projector first (Settings > Accounts). Lumen OS without Google does not ask for it, but a later switch to an image with Google services would (Factory Reset Protection)." "Рекомендуется: сначала удалите аккаунт Google на проекторе (Настройки > Аккаунты). Lumen OS без Google его не спросит, но при переходе позже на образ с сервисами Google его спросит защита от сброса.")
  } else {
    Say (T "IMPORTANT: remove your Google account on the projector first (Settings > Accounts)." "ВАЖНО: сначала удалите аккаунт Google на проекторе (Настройки > Аккаунты).")
  }
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

# blobs_allow.txt from=<sha256>:<'N,Md' commands>: the stock original of a file (XGIMI's audio policy) is
# turned into the listed file by deleting those lines (same bytes as sed on macOS/Linux), then checked.
function Remove-Lines($path, $cmds) {
  $del = @{}
  foreach ($c in ($cmds -split ";")) {
    if ($c -notmatch '^(\d+)(,(\d+))?d$') { Die "internal: bad from= command '$c'" }
    $a = [int]$Matches[1]; $b = if ($Matches[3]) { [int]$Matches[3] } else { $a }
    for ($i = $a; $i -le $b; $i++) { $del[$i] = $true }
  }
  $bytes = [IO.File]::ReadAllBytes($path)
  $out = New-Object IO.MemoryStream
  $ln = 1; $start = 0
  for ($i = 0; $i -lt $bytes.Length; $i++) {
    if ($bytes[$i] -eq 10) {
      if (-not $del.ContainsKey($ln)) { $out.Write($bytes, $start, $i - $start + 1) }
      $ln++; $start = $i + 1
    }
  }
  if ($start -lt $bytes.Length -and -not $del.ContainsKey($ln)) { $out.Write($bytes, $start, $bytes.Length - $start) }
  [IO.File]::WriteAllBytes($path, $out.ToArray())
}

function Cmd-Blobs {
  if ($Blobs -eq "none") { Warn (T "-NoBlobs: codec and audio files skipped (no sound, no protected video)" "-NoBlobs: файлы кодеков и звука пропущены (не будет звука и защищённого видео)"); State-Set step_blobs skip; return }
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
    $from = ""; foreach ($o in ($f | Select-Object -Skip 3)) { if ($o -like "from=*") { $from = $o.Substring(5) } }
    $dst = Join-Path $x ($rel -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
    $got = ""
    foreach ($p in @($src, "/system/$rel")) {
      Adb pull $p $dst | Out-Null
      if ($LASTEXITCODE -eq 0 -and (Test-Path $dst)) {
        $got = Sha256 $dst; if ($got -eq $h) { break }
        if ($from -and $got -eq ($from -split ":", 2)[0]) {
          Remove-Lines $dst ($from -split ":", 2)[1]
          $got = Sha256 $dst; if ($got -eq $h) { break }
        }
      }
    }
    if ($got -ne $h) { Die (T "$rel from the projector does not match the allow-list: this firmware is not supported (run again with -NoBlobs to install without these files: then there is NO SOUND and no protected video)" "$rel с проектора не совпадает со списком: эта прошивка не поддерживается (запустите с -NoBlobs, чтобы установить без этих файлов: тогда НЕ БУДЕТ ЗВУКА и защищённого видео)") }
    $manifest += "$rel $h $((Get-Item $dst).Length)"; $n++
  }
  [IO.File]::WriteAllText((Join-Path $x "MANIFEST"), (($manifest -join "`n") + "`n"))
  $img = Join-Path $w "z9x_blobs.img"
  Write-Ustar $x $img
  New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
  $out = Join-Path $BackupRoot "blobs-$($script:Serial).img"; Copy-Item $img $out -Force
  State-Set blobs_img $out; State-Set step_blobs done
  Ok (T "$n codec and audio files from your projector, set $set -> $out (keep this file)" "$n файлов кодеков и звука с вашего проектора, набор $set -> $out (сохраните файл)")
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
  if ($KeepData) { Edition-Guard flash }
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
  if ($gv -match "partition-size:z9x_gapps${S}:") { Say (T "z9x_gapps$S (Google services you added) stays as it is; 'gapps -Remove' removes it" "z9x_gapps$S (добавленные вами сервисы Google) остаётся как есть; 'gapps -Remove' его удаляет") }
  Say (T "writing system (about 40 s)" "записываю system (около 40 секунд)")
  if (-not (Fb-Do flash system $script:Image)) { Fb reboot | Out-Null; Die (T "system was not written; the projector still has its old system" "system не записан; на проекторе осталась старая система") }
  State-Set step_flash_system done
  if ($blobImg) {
    if ($gv -match "partition-size:z9x_blobs${S}:") { $null = Fb-Do resize "z9x_blobs$S" $BlobSize } else { $null = Fb-Do create "z9x_blobs$S" $BlobSize }
    if (Fb-Do flash "z9x_blobs$S" $blobImg) { Ok "z9x_blobs$S" } else { Warn (T "codec and audio files not written: no sound and no protected video until 'blobs' + 'flash -KeepData'" "файлы кодеков и звука не записаны: не будет звука и защищённого видео до 'blobs' + 'flash -KeepData'") }
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
  $nogms = (Prop ro.z9x.gms) -eq "0"
  $v = Prop ro.z9x.version; if ($v) { Ok ("Lumen OS $v" + $(if ($nogms) { ", " + (T "without Google" "без Google") } else { "" })) } else { Warn "ro.z9x.version" }
  if ($nogms -and (Prop sys.z9x.gapps) -eq "ok") {
    Ok (T "Google services added by you ('gapps'): on" "сервисы Google, добавленные вами ('gapps'): включены")
  } elseif ($nogms) {
    $gp = @(Adb shell pm list packages | ForEach-Object { "$_".Trim() -replace "^package:", "" } | Where-Object { $_ -match '^(com\.google\.|com\.mtg\.|com\.android\.vending$)' } | Select-Object -First 3)
    if ($gp.Count -eq 0) { Ok (T "no Google package" "пакетов Google нет") } else { Warn ((T "Google packages on Lumen OS without Google" "пакеты Google на Lumen OS без Google") + ": " + ($gp -join " ")) }
  }
  if ((Prop ro.z9x.keys) -eq "release") { Ok (T "release keys" "релизные ключи") } else { Warn "ro.z9x.keys" }
  if ((Prop init.svc.gmpf_main) -eq "running") { Ok (T "projector service running" "служба проектора работает") } else { Warn "gmpf_main" }
  if ($Blobs -eq "partition") { if ((Prop sys.z9x.blobs) -eq "ok") { Ok "blobs ok" } else { Warn "sys.z9x.blobs=$(Prop sys.z9x.blobs) (no sound / protected video until 'blobs' + 'flash -KeepData')" } }
  if ((Test-Path (Join-Path $StateDir "calib_before.txt")) -and (Try-Root)) {
    Calib-Snapshot after
    # XGIMI's own services write these partitions at every start (all five hashes change across a plain reboot),
    # so a changed hash proves nothing; the guarantee is the allow-list: the installer never writes them.
    Ok (T "calibration partitions: never written by the installer (snapshots in $StateDir)" "разделы калибровки: установщик их не записывает (снимки в $StateDir)")
    Adb unroot | Out-Null
  }
  State-Set step_verify done
}

# rescue: the projector is already in fastbootd (Lumen OS 1.0.1+ goes there by itself after 3 starts in a
# row that did not complete). No adb: the model is checked by fastbootd's product name, the slot is the
# one fastbootd reports. XGIMI's fastbootd leaves by itself after about 2 minutes, so the image hash and
# the question come before the wait.
function Fbd-Find($secs) {
  for ($i = 0; $i -lt $secs; $i += 2) {
    $list = @(& fastboot devices 2>$null | ForEach-Object { ("$_".Trim() -split "\s+")[0] } | Where-Object { $_ })
    if ($script:Serial -and ($list -contains $script:Serial)) { return $true }
    if ($list.Count -eq 1 -and (-not $script:Serial -or $script:SerialFromState)) {
      if ($script:Serial) { Warn (T "using $($list[0]) (the saved serial was $($script:Serial))" "использую $($list[0]) (сохранённый серийный номер был $($script:Serial))") }
      $script:Serial = $list[0]; return $true
    }
    if ($list.Count -gt 1) { Die (T "several devices in fastboot mode: choose one with -Serial" "несколько устройств в режиме fastboot: выберите одно через -Serial") }
    if ($i -gt 0 -and ($i % 120) -eq 0) { Say (T "still waiting for fastboot mode..." "всё ещё жду режима fastboot...") }
    Start-Sleep 2
  }
  return $false
}

function Fb-Snapshot($what) {  # cancel|merge: only for an unfinished update of the slot 'rescue' repairs
  if (@("cancel", "merge") -notcontains $what) { Die "internal: snapshot-update $what" }
  Fb snapshot-update $what | Out-Host
  return ($LASTEXITCODE -eq 0)
}

function Cmd-Rescue {
  $script:KeepData = $true
  Check-Tools; Check-Image -Force
  Edition-Guard rescue
  Write-Host (T "RESCUE: writes Lumen OS again into the slot the projector cannot start from, WITHOUT wiping data (apps, accounts, settings and files stay). If that slot holds an unfinished Lumen OS update, it is finished or cancelled first (this image replaces its system anyway; a cancelled update may also leave the other slot unable to start, as the two share that storage until an update is finished); *-cow update leftovers are removed. Calibration, vendor and boot stay untouched, and so does the other slot otherwise. Use it only for a projector that runs Lumen OS and no longer starts." "РЕМОНТ: заново записывает Lumen OS в слот, из которого проектор не может загрузиться, БЕЗ очистки данных (приложения, аккаунты, настройки и файлы сохраняются). Если в этом слоте незавершённое обновление Lumen OS, оно сначала завершается или отменяется (system всё равно заменяется этим образом; после отмены второй слот может не загрузиться: до завершения обновления у них общая область); остатки обновлений *-cow удаляются. Калибровка, vendor и boot не затрагиваются, второй слот в остальных случаях тоже. Только для проектора с Lumen OS, который больше не загружается.")
  if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  Say (T "waiting for the projector in fastboot mode (up to 15 minutes). After 3 failed starts in a row Lumen OS goes there by itself; the picture may stay dark. Keep the A-to-A cable in the USB 2.0 port. Fastboot mode ends by itself after about 2 minutes and the projector tries to start again: this command simply waits for its next visit." "жду проектор в режиме fastboot (до 15 минут). После 3 неудачных загрузок подряд Lumen OS переходит туда сама; изображения может не быть. Кабель A–A должен быть в порту USB 2.0. Режим fastboot сам завершается примерно через 2 минуты, и проектор снова пытается загрузиться: команда просто дождётся следующего раза.")
  if (-not (Fbd-Find 900)) { Die (T "the projector did not appear in fastboot mode within 15 minutes. Is it running Lumen OS 1.0.1 or newer? Cable in the USB 2.0 port? USB driver? Nothing was written." "проектор не появился в режиме fastboot за 15 минут. На нём Lumen OS 1.0.1 или новее? Кабель в порту USB 2.0? Драйвер USB? Ничего не записано.") }
  $gv = Fb getvar all
  function Val($k) { $l = $gv | Where-Object { $_ -like "(bootloader) ${k}:*" } | Select-Object -First 1; if ($l) { ($l.Substring(("(bootloader) ${k}:").Length)).Trim() } else { "" } }
  if ((Val is-userspace) -ne "yes") { Fb reboot | Out-Null; Die (T "not fastbootd" "это не fastbootd") }
  if ((Val product) -ne $FbProduct) { Fb reboot | Out-Null; Die (T "fastbootd is not the Z9X one" "fastbootd не от Z9X") }
  if ((Val unlocked) -ne "yes") { Fb reboot | Out-Null; Die (T "the bootloader is locked" "загрузчик заблокирован") }
  $cs = Val current-slot
  if (@("a", "b") -notcontains $cs) { Fb reboot | Out-Null; Die "current-slot '$cs'" }
  $S = "_$cs"; State-Set serial $script:Serial; State-Set slot $S
  Ok (T "fastbootd on $($script:Serial), slot $cs" "fastbootd на $($script:Serial), слот $cs")
  foreach ($p in @("product$S", "system_ext$S")) {
    if ($gv -match "partition-size:${p}:") { Fb reboot | Out-Null; Die (T "slot $cs still has XGIMI's $p partition: it is not an installed Lumen OS. 'rescue' repairs only Lumen OS. Nothing was written." "в слоте $cs ещё есть раздел XGIMI ${p}: это не установленная Lumen OS. 'rescue' чинит только Lumen OS. Ничего не записано.") }
  }
  $st = Val snapshot-update-status
  if ($st -eq "snapshotted") {
    Say (T "cancelling the unfinished update (the system of slot $cs is replaced now; the other slot shares that storage until an update is finished, so it may no longer start afterwards)" "отменяю незавершённое обновление (system слота $cs сейчас будет заменён; второй слот до завершения обновления использует ту же область, поэтому после этого он может не загрузиться)")
    if (-not (Fb-Snapshot cancel)) { Fb reboot | Out-Null; Die (T "the unfinished update could not be cancelled; nothing was written" "незавершённое обновление не удалось отменить; ничего не записано") }
  } elseif ($st -eq "merging") {
    Say (T "finishing the started update merge of slot $cs first (can take a few minutes)" "сначала завершаю начатое слияние обновления слота $cs (может занять несколько минут)")
    if (-not (Fb-Snapshot merge)) { Fb reboot | Out-Null; Die (T "the update merge could not be finished; nothing was written" "слияние обновления не удалось завершить; ничего не записано") }
  } elseif ($st -ne "none") { Fb reboot | Out-Null; Die "snapshot-update-status '$st'" }
  if ($st -ne "none") {
    $gv = Fb getvar all
    if ((Val snapshot-update-status) -ne "none") { Fb reboot | Out-Null; Die "snapshot-update-status '$(Val snapshot-update-status)'" }
  }
  $cows = $gv | ForEach-Object { if ($_ -match "partition-size:([a-z0-9_]+-cow):") { $Matches[1] } } | Sort-Object -Unique
  foreach ($p in $cows) { Say ((T "removing update leftover" "удаляю остаток обновления") + " $p"); if (-not (Fb-Do delete $p)) { Fb reboot | Out-Null; Die "delete $p" } }
  Say (T "writing system (about 40 s)" "записываю system (около 40 секунд)")
  if (-not (Fb-Do flash system $script:Image)) { Fb reboot | Out-Null; Die (T "system was not written; nothing changed. Run 'rescue' again (the projector comes back to fastboot mode after 3 more failed starts)." "system не записан; ничего не изменилось. Запустите 'rescue' ещё раз (проектор вернётся в режим fastboot после ещё 3 неудачных загрузок).") }
  if ($Blobs -eq "partition") {
    $blobImg = State-Get blobs_img
    if (-not $blobImg -or -not (Test-Path $blobImg)) { $blobImg = Join-Path $BackupRoot "blobs-$($script:Serial).img" }
    if (Test-Path $blobImg) {
      if ($gv -match "partition-size:z9x_blobs${S}:") { $null = Fb-Do resize "z9x_blobs$S" $BlobSize } else { $null = Fb-Do create "z9x_blobs$S" $BlobSize }
      if (Fb-Do flash "z9x_blobs$S" $blobImg) { Ok "z9x_blobs$S" } else { Warn (T "codec and audio files not written: no sound and no protected video until 'blobs' + 'flash -KeepData'" "файлы кодеков и звука не записаны: не будет звука и защищённого видео до 'blobs' + 'flash -KeepData'") }
    } elseif ($gv -match "partition-size:z9x_blobs${S}:") {
      Ok (T "z9x_blobs$S kept as it is (no codec image on this computer)" "z9x_blobs$S оставлен как есть (на компьютере нет образа кодеков)")
    } else {
      Warn (T "no z9x_blobs$S and no codec image on this computer: no sound and no protected video until 'blobs' + 'flash -KeepData'" "нет z9x_blobs$S и образа кодеков на компьютере: не будет звука и защищённого видео до 'blobs' + 'flash -KeepData'")
    }
  }
  Fb reboot | Out-Null
  State-Set step_rescue (Get-Date -Format "yyyyMMdd-HHmm")
  Ok (T "written. The projector starts now (1-3 minutes, your data is kept). If it ends up in fastboot mode again after 3 more failed starts, the system partition was not the cause: the stock restore at the end of the guide is the way out (it erases everything)." "записано. Проектор сейчас загрузится (1–3 минуты, данные сохранены). Если после ещё 3 неудачных загрузок он снова окажется в режиме fastboot, причина не в разделе system: остаётся восстановление стока в конце инструкции (стирает всё).")
}

function Cmd-Gsf {
  Pick-Device
  if (-not (Prop ro.z9x.version)) { Die (T "the projector is not running Lumen OS" "на проекторе не Lumen OS") }
  # Lumen OS without Google with the user's add-on ('gapps') has GSF too: the package decides, not ro.z9x.gms
  if (-not ((Adb shell pm path com.google.android.gsf) | Out-String).Trim()) {
    Ok (T "this Lumen OS has no Google services: nothing to register" "в этой Lumen OS нет сервисов Google: регистрировать нечего")
    if ((Prop ro.z9x.gms) -eq "0") { Say (T "To add them: lumen-install.cmd gapps -Zip FILE (guide, section 'Adding Google services')" "Как их добавить: lumen-install.cmd gapps -Zip ФАЙЛ (инструкция, раздел «Как поставить сервисы Google»)") }
    return
  }
  Adb root | Out-Null; Start-Sleep 3; Adb wait-for-device | Out-Null
  $id = ((Adb shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db `"select value from main where name='android_id';`"") | Out-String).Trim()
  Adb unroot | Out-Null
  if (-not $id) { Die (T "no GSF ID yet: connect the projector to the internet, wait two minutes, try again" "GSF ID ещё нет: подключите проектор к интернету, подождите пару минут и повторите") }
  Write-Host "GSF ID: $id"
  Write-Host (T "Register it at https://www.google.com/android/uncertified, wait 10-30 minutes, then sign in on the projector." "Зарегистрируйте его на https://www.google.com/android/uncertified, подождите 10–30 минут и войдите на проекторе.")
}

# gapps (Lumen OS without Google + the user's own Google services; see lumen-install.sh, section 'gapps'):
# -Zip FILE (only a MindTheGapps zip whose sha256 is in lib\gapps_allow.txt) goes into the logical partition
# z9x_gapps_<slot>: created / resized and formatted in fastbootd (room in super checked first), filled over
# adb root by lib\gapps_fill.sh, checked by the image's z9x_gapps.sh, then the data wipe of 'flash' (not with
# -KeepData; -Wipe factory-reset leaves it to the projector). -Remove deletes the partition (also from fastboot
# mode, waited for like 'rescue') with the same wipe. 'gapps' alone shows the state.
$GappsDir = "/data/local/tmp/lumen-gapps"
function Gapps-Line($sha) { Get-Content (Join-Path $Here "lib\gapps_allow.txt") | Where-Object { $_ -like "zip=$sha *" } | Select-Object -First 1 }
function Gapps-Field($line, $key) { foreach ($w in ("$line" -split " ")) { if ($w.StartsWith("$key=")) { return $w.Substring($key.Length + 1) } }; return "" }
function Gv-Size($gv, $name) {
  $l = $gv | Where-Object { $_ -like "(bootloader) partition-size:${name}:*" } | Select-Object -First 1
  if (-not $l) { return $null }
  return [Convert]::ToInt64(($l.Substring(("(bootloader) partition-size:${name}:").Length)).Trim(), 16)
}
function Super-Free($gv) {  # bytes of super no logical partition uses: each rounded up to 1 MiB, 2 MiB for metadata
  $sup = Gv-Size $gv "super"
  if ($null -eq $sup) { return [int64]0 }
  $used = [int64]0
  $names = @($gv | ForEach-Object { if ("$_" -match "^\(bootloader\) is-logical:([^:]+):yes") { $Matches[1] } } | Sort-Object -Unique)
  foreach ($n in $names) { $sz = Gv-Size $gv $n; if ($null -ne $sz) { $used += [int64][math]::Ceiling($sz / 1MB) * 1MB } }
  return [int64]($sup - $used - 2MB)
}
function Fbd-Check($slot) {  # the fastbootd checks of 'flash'; returns 'getvar all', sets $script:FbdSlot
  $gv = Fb getvar all
  function V($k) { $l = $gv | Where-Object { $_ -like "(bootloader) ${k}:*" } | Select-Object -First 1; if ($l) { ($l.Substring(("(bootloader) ${k}:").Length)).Trim() } else { "" } }
  if ((V is-userspace) -ne "yes") { Fb reboot | Out-Null; Die (T "not fastbootd" "это не fastbootd") }
  if ((V product) -ne $FbProduct) { Fb reboot | Out-Null; Die (T "fastbootd is not the Z9X one" "fastbootd не от Z9X") }
  if ((V unlocked) -ne "yes") { Fb reboot | Out-Null; Die (T "the bootloader is locked" "загрузчик заблокирован") }
  $cs = V current-slot
  if (@("a", "b") -notcontains $cs) { Fb reboot | Out-Null; Die "current-slot '$cs'" }
  if ($slot -and $cs -ne $slot.TrimStart('_')) { Fb reboot | Out-Null; Die "slot mismatch" }
  $script:FbdSlot = "_$cs"
  if ((V snapshot-update-status) -ne "none") { Fb reboot | Out-Null; Die (T "a Lumen OS update is still being finished. Let the projector start, wait 10 minutes, try again. Nothing was written." "ещё завершается обновление Lumen OS. Дайте проектору загрузиться, подождите 10 минут и повторите. Ничего не записано.") }
  return $gv
}
function Fbd-Enter($slot) {
  Say (T "restarting into fastbootd (about 30 s)" "перезагрузка в fastbootd (около 30 секунд)")
  Adb reboot fastboot | Out-Null
  $seen = $false
  for ($i = 0; $i -lt 45; $i++) { if ((& fastboot devices) -match "^$([regex]::Escape($script:Serial))\s") { $seen = $true; break }; Start-Sleep 2 }
  if (-not $seen) { Die (T "the projector did not appear in fastboot within 90 s (USB driver?)" "проектор не появился в fastboot за 90 секунд (драйвер USB?)") }
  return (Fbd-Check $slot)
}
function Fb-Wipe($gv) {  # the data wipe of 'flash', in fastbootd
  Say (T "wiping data (format userdata, metadata, cache)" "очистка данных (format userdata, metadata, cache)")
  if (-not (Fb-Do format userdata)) { Fb reboot | Out-Null; Die (T "the wipe failed: on the projector do Settings > Device preferences > Reset > Factory reset" "очистка не удалась: на проекторе сделайте Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам") }
  $null = Fb-Do format metadata
  if ($gv -match "partition-type:cache:") { $null = Fb-Do format cache }
  State-Set step_wipe done
}
function Boot-Wait {  # up to 5 min for the projector back over adb with sys.boot_completed=1
  for ($i = 0; $i -lt 150; $i++) {
    $l = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
    if (($l -contains $script:Serial) -and ((Prop sys.boot_completed) -eq "1")) { return $true }
    Start-Sleep 2
  }
  return $false
}
function Gapps-Sh { ((Adb shell sh "$GappsDir/fill.sh" @args) | Out-String).Trim() }
function Gapps-Push-Fill {
  # the device's sh needs LF line ends whatever a Windows checkout made of the file
  New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
  $tmp = Join-Path $StateDir "gapps_fill.sh"
  [IO.File]::WriteAllText($tmp, ([IO.File]::ReadAllText((Join-Path $Here "lib\gapps_fill.sh")) -replace "`r", ""))
  Adb shell rm -rf $GappsDir | Out-Null
  Adb shell mkdir -p $GappsDir | Out-Null
  Adb push $tmp "$GappsDir/fill.sh" | Out-Null
  if ($LASTEXITCODE -ne 0) { Die "adb push lib\gapps_fill.sh" }
}

function Gapps-Install {
  if (-not (Test-Path -LiteralPath $Zip)) { Die (T "no file $Zip" "нет файла $Zip") }
  $zip = (Resolve-Path -LiteralPath $Zip).Path
  Check-Tools
  $dir = Split-Path -Parent (Get-Command fastboot).Source
  if (-not (Test-Path (Join-Path $dir "mke2fs.exe"))) { Die (T "mke2fs.exe missing next to fastboot ($dir): use the official Platform-Tools zip" "нет mke2fs.exe рядом с fastboot ($dir): используйте официальный архив Platform-Tools") }
  $zname = Split-Path -Leaf $zip
  Say (T "checking $zname" "проверяю $zname")
  $zsha = Sha256 $zip
  $zl = Gapps-Line $zsha
  if (-not $zl) { Die ((T "$zname is not the MindTheGapps file this installer accepts (sha256 $($zsha.Substring(0,16))...). Download exactly the file the guide names, section 'Adding Google services'" "$zname не тот файл MindTheGapps, который принимает установщик (sha256 $($zsha.Substring(0,16))...). Скачайте ровно тот файл, что указан в инструкции, раздел «Как поставить сервисы Google»") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  $name = ("$zl" -split " ")[1]; $part = [int64](Gapps-Field $zl "part"); $mb = [int64][math]::Floor($part / 1MB)
  Ok "$name, sha256 $($zsha.Substring(0,16))..."
  Check-Device
  $slot = State-Get slot; $p = "z9x_gapps$slot"; $sl = $slot.TrimStart('_')
  $dg = State-Get dev_gms
  if ($dg -eq "1") { Die (T "this projector runs Lumen OS with Google services built in: nothing to add" "на проекторе Lumen OS со встроенными сервисами Google: добавлять нечего") }
  if ($dg -ne "0") { Die (T "this projector does not run Lumen OS: install Lumen OS without Google first" "на проекторе не Lumen OS: сначала установите Lumen OS без Google") }
  # the same add-on, on: writing it again would only cost the Google apps their data (one start without them)
  $have = Prop sys.z9x.gapps
  if ($have -eq "ok" -and (Prop sys.z9x.gapps.zip) -eq $zsha) {
    Ok (T "these Google services ($name) are already installed and on: nothing to do. Nothing was written." "эти сервисы Google ($name) уже установлены и включены: делать нечего. Ничего не записано.")
    return
  }
  if (-not (Try-Root)) { Die (T "adb root is not possible on this system (Lumen OS is a userdebug build: is it really Lumen OS?)" "adb root на этой системе невозможен (Lumen OS собрана как userdebug: точно ли это Lumen OS?)") }
  $al = @((Adb shell cat /system/etc/z9x/gapps_allow.txt) | ForEach-Object { "$_".TrimEnd("`r") })
  if (-not ($al | Where-Object { $_ -like "zip=$zsha *" })) { Die (T "the Lumen OS on the projector does not accept this file yet: update Lumen OS first (Settings > Device preferences > About > Lumen OS update). Nothing was written." "Lumen OS на проекторе ещё не принимает этот файл: сначала обновите Lumen OS (Настройки > Настройки устройства > Об устройстве > Обновление Lumen OS). Ничего не записано.") }
  if ($KeepData -or $Wipe -eq "factory-reset") {
    Write-Host (T "Adding Google services (slot $sl): the installer creates the partition $p ($mb MB) in super, restarts the projector a few times and copies the Google apps from $name into it, each one checked. Your data stays. system, the other slot, vendor, boot and XGIMI's calibration stay untouched." "Добавляю сервисы Google (слот $sl): установщик создаёт раздел $p ($mb МБ) в super, несколько раз перезагружает проектор и копирует в этот раздел приложения Google из $name, проверяя каждое. Ваши данные остаются. System, второй слот, vendor, boot и калибровка XGIMI не затрагиваются.")
    if ($KeepData) {
      Say (T "-KeepData: sign in to Google later in Settings > Accounts. Some Google features (Chromecast, the phone remote) may work fully only after a factory reset." "-KeepData: в Google войдите потом через Настройки > Аккаунты. Некоторые функции Google (Chromecast, пульт на телефоне) могут полностью заработать только после сброса к заводским настройкам.")
      if ($have -and $have -ne "none") { Say (T "Google services are already installed: replacing them signs you out of Google, and the GSF ID must be registered again." "Сервисы Google уже установлены: после замены вы выйдете из аккаунта Google, а GSF ID нужно будет зарегистрировать снова.") }
    }
    if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  } else {
    Write-Host (T "Adding Google services (slot $sl): the installer creates the partition $p ($mb MB) in super, restarts the projector a few times, copies the Google apps from $name into it, each one checked, and then ERASES ALL DATA on the projector (apps, accounts, settings, files), as at the install: the first start then sets up Google services properly. system, the other slot, vendor, boot and XGIMI's calibration stay untouched." "Добавляю сервисы Google (слот $sl): установщик создаёт раздел $p ($mb МБ) в super, несколько раз перезагружает проектор, копирует в этот раздел приложения Google из $name, проверяя каждое, и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы), как при установке: тогда первый запуск правильно настроит сервисы Google. System, второй слот, vendor, boot и калибровка XGIMI не затрагиваются.")
    if ((State-Get step_backup) -ne "done" -and (Ask (T "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?"))) { Cmd-Backup }
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  }
  # 1. the fill script's own checks on the projector, before any partition is touched
  Gapps-Push-Fill
  $o = Gapps-Sh pre $zsha (Get-Item -LiteralPath $zip).Length
  Adb shell rm -rf $GappsDir | Out-Null
  if (-not $o.StartsWith("OK pre ")) { Die ((T "the projector is not ready" "проектор не готов") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  # 2. fastbootd: the partition
  $gv = Fbd-Enter $slot
  $free = Super-Free $gv; $cur = [int64]0
  $have = Gv-Size $gv $p; if ($null -ne $have) { $cur = $have }
  $avail = [int64][math]::Floor(($free + $cur) / 1MB)
  if (($free + $cur) -lt $part) { Fb reboot | Out-Null; Die (T "not enough free space in super: $avail MB, the Google services need $mb MB. Nothing was written." "в super мало места: свободно $avail МБ, сервисам Google нужно $mb МБ. Ничего не записано.") }
  Say (T "creating $p ($mb MB, $($avail - $mb) MB of super stay free)" "создаю $p ($mb МБ, в super останется $($avail - $mb) МБ)")
  $made = if ($cur -gt 0) { Fb-Do resize $p $part } else { Fb-Do create $p $part }
  if (-not $made) { Fb reboot | Out-Null; Die (T "$p could not be created; nothing was written" "$p создать не удалось; ничего не записано") }
  if (-not (Fb-Do format $p)) { $null = Fb-Do delete $p; Fb reboot | Out-Null; Die (T "$p could not be formatted and was removed again; nothing else changed" "$p не удалось отформатировать, раздел удалён; больше ничего не изменилось") }
  Fb reboot | Out-Null
  # 3. filled over adb as root, checked by the image's own script
  Say (T "waiting for the projector to start (up to 5 minutes)" "жду, пока проектор загрузится (до 5 минут)")
  if (-not (Boot-Wait)) { Die (T "the projector did not come back over USB within 5 minutes. $p is still empty, so Lumen OS starts without Google services. Run 'gapps -Zip' again." "проектор не вернулся по USB за 5 минут. $p пока пуст, Lumen OS запускается без сервисов Google. Запустите 'gapps -Zip' ещё раз.") }
  if (-not (Try-Root)) { Die "adb root" }
  Gapps-Push-Fill
  Say (T "copying $name to the projector (about a minute)" "копирую $name на проектор (около минуты)")
  Adb push $zip "$GappsDir/gapps.zip" | Out-Null
  if ($LASTEXITCODE -ne 0) { Adb shell rm -rf $GappsDir | Out-Null; Die (T "adb push of the zip failed. $p is still empty, so Lumen OS starts without Google services. Run 'gapps -Zip' again." "adb push архива не удался. $p пока пуст, Lumen OS запускается без сервисов Google. Запустите 'gapps -Zip' ещё раз.") }
  Say (T "copying the Google apps into $p and checking each one (1 to 2 minutes)" "копирую приложения Google в $p и проверяю каждое (1–2 минуты)")
  $o = Gapps-Sh fill $zsha
  if (-not $o.StartsWith("OK fill ")) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "the Google services were not added" "сервисы Google не добавлены") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Lumen OS starts without them as before. Run 'gapps -Zip' again, or 'gapps -Remove'." "Lumen OS запускается без них, как раньше. Запустите 'gapps -Zip' ещё раз или 'gapps -Remove'.")) }
  $c = ((Adb shell sh /system/etc/z9x/z9x_gapps.sh check) | Out-String).Trim()
  Adb shell rm -rf $GappsDir /metadata/z9x_gapps | Out-Null
  if (-not $c.StartsWith("ok: ")) { Die ((T "the projector's own check of $p failed" "собственная проверка $p на проекторе не прошла") + ": $c. " + (T "Lumen OS starts without Google services. Run 'gapps -Zip' again, or 'gapps -Remove'." "Lumen OS запускается без сервисов Google. Запустите 'gapps -Zip' ещё раз или 'gapps -Remove'.")) }
  Ok "${p}: $($c.Substring(4))"
  State-Set gapps $name
  # 4. the wipe, so that the first start sets up Google services
  if ($KeepData) {
    Adb reboot | Out-Null
    Say (T "restarting" "перезагрузка")
    if ((Boot-Wait) -and (Prop sys.z9x.gapps) -eq "ok") { Ok (T "Google services are on. Sign in: Settings > Accounts. If Google refuses the sign-in, register the GSF ID (lumen-install.cmd gsf)." "сервисы Google включены. Вход: Настройки > Аккаунты. Если Google не пускает, зарегистрируйте GSF ID (lumen-install.cmd gsf).") }
    else { Warn (T "the projector did not report the Google services as on yet (sys.z9x.gapps=$(Prop sys.z9x.gapps)); check later with 'lumen-install.cmd gapps'" "проектор пока не сообщил, что сервисы Google включены (sys.z9x.gapps=$(Prop sys.z9x.gapps)); проверьте позже: 'lumen-install.cmd gapps'") }
  } elseif ($Wipe -eq "factory-reset") {
    Adb reboot | Out-Null
    State-Set step_wipe factory-reset
    Say (T "Now on the projector: Settings > Device preferences > Reset > Factory reset. The first start after it sets up Google services." "Теперь на проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам. Первый запуск после него настроит сервисы Google.")
  } else {
    $gv = Fbd-Enter $slot
    Fb-Wipe $gv
    Fb reboot | Out-Null
    Ok (T "done. The first start takes 2-4 minutes; the setup now has a Google sign-in step. If Google refuses the sign-in, register the GSF ID (guide, section 'Adding Google services')." "готово. Первый запуск 2–4 минуты; в настройке теперь есть шаг входа в Google. Если Google не пускает, зарегистрируйте GSF ID (инструкция, раздел «Как поставить сервисы Google»).")
  }
  State-Set step_gapps done
}

function Gapps-Remove {
  Check-Tools
  $list = @(& adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
  $viaAdb = $list.Count -gt 0
  if ($viaAdb) { Check-Device }
  if ($KeepData -or $Wipe -eq "factory-reset") {
    Write-Host (T "Removing the Google services you added: the installer deletes the partition z9x_gapps of the current slot in fastboot mode and restarts. Your data stays, but Google apps updated from Play stay behind as apps that may not work: a factory reset removes them." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot удаляет раздел z9x_gapps текущего слота и перезагружает проектор. Данные остаются, но обновлённые из Play приложения Google останутся и могут не работать: их уберёт сброс к заводским настройкам.")
    if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  } else {
    Write-Host (T "Removing the Google services you added: the installer deletes the partition z9x_gapps of the current slot in fastboot mode and then ERASES ALL DATA on the projector (apps, accounts, settings, files), as at the install. system, vendor, boot and XGIMI's calibration stay untouched." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot удаляет раздел z9x_gapps текущего слота и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы), как при установке. System, vendor, boot и калибровка XGIMI не затрагиваются.")
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  }
  if ($viaAdb) { $gv = Fbd-Enter (State-Get slot) }
  else {
    Say (T "no projector over adb: waiting for it in fastboot mode (up to 15 minutes; after 3 failed starts in a row Lumen OS goes there by itself)" "проектора нет в adb: жду его в режиме fastboot (до 15 минут; после 3 неудачных загрузок подряд Lumen OS переходит туда сама)")
    if (-not (Fbd-Find 900)) { Die (T "the projector did not appear in fastboot mode within 15 minutes; nothing was written" "проектор не появился в режиме fastboot за 15 минут; ничего не записано") }
    $gv = Fbd-Check ""
    State-Set serial $script:Serial; State-Set slot $script:FbdSlot
  }
  $S = $script:FbdSlot; $p = "z9x_gapps$S"; $removed = $false
  if ($gv -match "partition-size:${p}:") {
    if (-not (Fb-Do delete $p)) { Fb reboot | Out-Null; Die (T "$p could not be deleted; nothing changed" "$p удалить не удалось; ничего не изменилось") }
    $removed = $true; Ok (T "$p deleted" "$p удалён")
  } else { Ok (T "slot $($S.TrimStart('_')) has no ${p}: nothing to remove" "в слоте $($S.TrimStart('_')) нет ${p}: удалять нечего") }
  if ($removed -and -not $KeepData -and $Wipe -eq "format") { Fb-Wipe $gv }
  Fb reboot | Out-Null
  State-Set gapps ""
  if ($removed -and -not $KeepData -and $Wipe -eq "factory-reset") { Say (T "Now on the projector: Settings > Device preferences > Reset > Factory reset." "Теперь на проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.") }
  Ok (T "the projector starts Lumen OS without Google services" "проектор загружает Lumen OS без сервисов Google")
}

function Gapps-Status {
  Pick-Device
  if (-not (Prop ro.z9x.version)) { Die (T "the projector is not running Lumen OS" "на проекторе не Lumen OS") }
  $v = Prop sys.z9x.gapps; $z = Prop sys.z9x.gapps.zip
  if ($v -eq "ok") { Ok ((T "Google services added by you: on" "добавленные вами сервисы Google: включены") + " (" + ("$(Gapps-Line $z)" -split " ")[1] + ")") }
  elseif ($v -eq "skip") { Ok (T "this Lumen OS has Google services built in" "в этой Lumen OS сервисы Google встроены") }
  elseif (-not $v -or $v -eq "none") { Say (T "no Google services added. To add them: lumen-install.cmd gapps -Zip FILE (guide, section 'Adding Google services')" "сервисы Google не добавлены. Как добавить: lumen-install.cmd gapps -Zip ФАЙЛ (инструкция, раздел «Как поставить сервисы Google»)") }
  elseif ($v -eq "bad:slow") { Warn (T "at the last start the check of the Google services took too long, so the projector started without them: restart it" "при последнем запуске проверка сервисов Google шла слишком долго, поэтому проектор запустился без них: перезагрузите его") }
  elseif ($v -eq "off") {
    Warn (T "the Google services were turned off: the projector failed to start twice in a row with them" "сервисы Google выключены: проектор дважды подряд не смог загрузиться с ними")
    if (Ask (T "Turn them on again (the projector restarts)?" "Включить их снова (проектор перезагрузится)?")) {
      if (-not (Try-Root)) { Die "adb root" }
      Adb shell rm -rf /metadata/z9x_gapps | Out-Null
      Adb reboot | Out-Null
      Ok (T "turned on; the projector restarts" "включены; проектор перезагружается")
    }
  }
  else { Warn (T "the Google services you added cannot be used (${v}): run 'gapps -Zip FILE' again, or 'gapps -Remove'" "добавленные сервисы Google не могут использоваться (${v}): запустите 'gapps -Zip ФАЙЛ' ещё раз или 'gapps -Remove'") }
}

function Cmd-Gapps {
  if ($Zip -and $Remove) { Die "gapps: -Zip or -Remove, not both" }
  if ($Remove) { Gapps-Remove } elseif ($Zip) { Gapps-Install } else { Gapps-Status }
}

function Cmd-All {
  if ((State-Get step_check) -ne "done") { Cmd-Check }
  if ((State-Get step_backup) -ne "done") { if (Ask (T "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")) { Cmd-Backup } else { State-Set step_backup skipped } }
  if ($Blobs -eq "partition" -and (State-Get step_blobs) -ne "done") { Cmd-Blobs }
  if ((State-Get step_vbmeta) -ne "done") { Cmd-Vbmeta }
  if ((State-Get step_flash) -ne "done") { Cmd-Flash }
  if ((State-Get step_verify) -ne "done") { Cmd-Verify }
  if ((State-Get image_gms) -eq "0") {
    Write-Host (T "Done. Finish the setup on the projector. Install apps from a USB stick: Lumen Home > Apps > Install from USB. Google services can be added later: 'gapps' (guide)." "Готово. Завершите настройку на проекторе. Приложения ставятся с USB-накопителя: Lumen Home > Приложения > Установить с USB. Сервисы Google можно добавить позже: 'gapps' (инструкция).")
  } else {
    Write-Host (T "Done. Finish the setup on the projector. For Google sign-in register the GSF ID first: lumen-install.cmd gsf" "Готово. Завершите настройку на проекторе. Для входа в Google сначала зарегистрируйте GSF ID: lumen-install.cmd gsf")
  }
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
  "rescue" { Cmd-Rescue }
  "gapps"  { Cmd-Gapps }
  "status" { $f = Join-Path $StateDir "state"; if (Test-Path $f) { Get-Content $f } else { Write-Host (T "no saved progress" "сохранённого прогресса нет") } }
  "reset"  { Remove-Item -Recurse -Force $StateDir -ErrorAction SilentlyContinue; Write-Host (T "progress forgotten" "прогресс сброшен") }
  default  { Get-Content $PSCommandPath -TotalCount 15 | Select-Object -Skip 1; exit 2 }  # the usage lines above
}
