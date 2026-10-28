# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer for the XGIMI Z9X and the XGIMI Play 6 - Windows (PowerShell 5.1+). BETA: not yet tested on
# Windows. Same steps and the same safety rules as lumen-install.sh (macOS/Linux):
#
#   lumen-install.cmd [command] [-Image FILE] [-Serial S] [-KeepData] [-Wipe format|factory-reset]
#                     [-Lang en|ru] [-Blobs partition|embedded] [-NoBlobs] [-UnsignedImage] [-Zip FILE] [-Remove]
#                     [-Ip ADDRESS[:PORT]] [-Device z9x|play6] [-Path FOLDER] [-GappsZip FILE]
#   commands: (none = continue) check | backup | blobs | vbmeta | flash | verify | gsf | rescue | gapps | status | reset
#   XGIMI Play 6 (-Ip: its Wi-Fi address; it has no USB debugging): (none) | check | backup | flash | verify |
#   rescue | stick -Path E:\ | gapps | gsf | status | reset. 'flash' writes system in fastboot mode over the A-to-A
#   cable, waits for Lumen OS, then asks Android for its factory reset (XGIMI's recovery erases the data).
#   gapps -Zip FILE: Lumen OS without Google + Google services from YOUR OWN MindTheGapps download (the guide
#   names the one accepted file), in their own partition z9x_gapps_<slot> (Play 6: lumen_gapps), then the data
#   wipe (-KeepData: no wipe). gapps -Remove: takes them out (Z9X also from fastboot mode). gapps: their state.
#   -Blobs: published default partition; the owner's PRIVATE image needs -Blobs embedded.
#   -NoBlobs: a public image without the codec and audio files (skips 'blobs': NO SOUND, no protected video).
#   -UnsignedImage: the owner's own build without a signed SHA256SUMS (asks you to type UNSIGNED).
#   -Image: required when several lumen-os-*-system.img lie next to the installer (with and without Google).
#   gsf: only when Google services are there (built in, or added with 'gapps').
#   -GappsZip FILE: the full install (no command) with Google services in one pass and ONE data wipe (not with
#   -KeepData or -Wipe factory-reset): Lumen OS starts once on the old data, the Google apps are filled in as root
#   (Developer options > Rooted debugging; the installer waits for it), then the wipe.
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
# XGIMI Play 6: only system is written (Fb-Allowed), and 'gapps' creates, resizes or deletes lumen_gapps (formatted
# and filled on the projector over adb root, lib\play6\gapps_fill.sh); the data wipe is Android's own factory reset.
# Never vendor, boot, recovery, MBOOT, env, calibration or factory data. Its model, firmware list and image name:
# lib\devices.conf.
param([string]$Command = "", [string]$Image = "", [string]$Serial = "", [switch]$KeepData,
      [string]$Wipe = "format", [string]$Lang = "", [string]$Blobs = "", [switch]$NoBlobs, [switch]$UnsignedImage,
      [string]$Zip = "", [switch]$Remove, [string]$Ip = "", [string]$Device = "", [string]$Path = "",
      [string]$GappsZip = "")
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$Here = $PSScriptRoot
$StateDir = if ($env:LUMEN_STATE_DIR) { $env:LUMEN_STATE_DIR } else { Join-Path $HOME ".lumen-installer" }
$BackupRoot = if ($env:LUMEN_BACKUP_DIR) { $env:LUMEN_BACKUP_DIR } else { Join-Path $HOME "Lumen-backup" }
$ModelCode = "G0082"; $Board = "mt9952"; $FbProduct = "mt5877"; $VendorOk = @("v6.15.58", "v6.15.62", "v6.15.19")
$GuideBase = "https://github.com/kmuradoff/lumen-os/blob/main/docs/install"   # one guide per projector and language
$GuideEn = "$GuideBase/z9x/en.md"
$GuideRu = "$GuideBase/z9x/ru.md"
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
if (@("", "z9x", "play6") -notcontains $Device) { Write-Host "-Device z9x|play6"; exit 2 }
$RelDevice = if ($conf["DEVICE"]) { $conf["DEVICE"] } else { "" }   # the projector this download's image is for (none: z9x)
$script:Projector = "z9x"     # the projector profile (Resolve-Device); the Z9X path is the one this script always had
$script:SD = $StateDir        # where the device's progress is kept (the Z9X: $StateDir\state, as always)
$script:AdbT = ""             # XGIMI Play 6: adb over Wi-Fi, ADDR:PORT ($script:Serial is then the fastboot serial)
$script:ImgGlob = "lumen-os-*-system.img"
$script:EraseNow = $false; $script:WipedNow = $false

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
  $f = Join-Path $script:SD "state"
  if (-not (Test-Path $f)) { return "" }
  $l = Get-Content $f | Where-Object { $_ -like "$k=*" } | Select-Object -Last 1
  if ($l) { return $l.Substring($k.Length + 1) } else { return "" }
}
function State-Set($k, $v) {
  New-Item -ItemType Directory -Force -Path $script:SD | Out-Null
  $f = Join-Path $script:SD "state"
  $lines = @(); if (Test-Path $f) { $lines = @(Get-Content $f | Where-Object { $_ -notlike "$k=*" }) }
  $lines += "$k=$v"
  Set-Content -Path $f -Value $lines -Encoding UTF8
}

function Adb { $t = if ($script:AdbT) { $script:AdbT } else { $script:Serial }; & adb -s $t @args 2>$null }   # AdbT: never set on the Z9X
function Prop($p) { ((Adb shell getprop $p) | Out-String).Trim() }
function Fb { & fastboot -s $script:Serial @args 2>&1 | ForEach-Object { "$_" } }

function Fb-Allowed($act, $part) {
  # XGIMI Play 6: system, and the Google services add-on partition lumen_gapps ('gapps': create, resize, delete;
  # it is formatted and filled on the projector). Single slot; never format, erase or another flash.
  if ($script:Projector -eq "play6") { return (@("flash:system", "create:lumen_gapps", "resize:lumen_gapps", "delete:lumen_gapps") -ccontains "${act}:$part") }
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
  if ($script:Image) { if (-not (Test-Path $script:Image)) { Die (T "no image $($script:Image)" "нет образа $($script:Image)") }; Image-Owner; return }
  $g = $script:ImgGlob
  $c = @(Get-ChildItem -Path (Join-Path $Here $g), (Join-Path (Split-Path -Parent $Here) $g) -ErrorAction SilentlyContinue)
  if ($c.Count -lt 1) { Die (T "no $g found; use -Image FILE" "не найден $g; укажите -Image ФАЙЛ") }
  # never pick one of several by name order: the editions (with and without Google) differ
  if ($c.Count -gt 1) { Die (T "several images found (with and without Google?): choose one with -Image FILE" "найдено несколько образов (с Google и без?): выберите один через -Image ФАЙЛ") }
  $script:Image = $c[0].FullName
  Image-Owner
}

# a Z9X never takes an image named for another projector (lumen-os-play6-... matches lumen-os-*-system.img)
function Image-Owner {
  if ($script:Projector -ne "z9x") { return }
  if ((Split-Path -Leaf $script:Image) -like "*play6*") { Die (T "this image is for the $(Dev-Name play6), not for the XGIMI Z9X. Nothing was written." "этот образ для $(Dev-Name play6), а не для XGIMI Z9X. Ничего не записано.") }
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
  if ($script:Projector -eq "z9x" -and -not $KeepData -and $Wipe -eq "format") {
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
  if ($code -ne $ModelCode -or $board -ne $Board) { Die ((T "this is not an XGIMI Z9X (model '$code', board '$board'). Nothing was done." "это не XGIMI Z9X (код '$code', плата '$board'). Ничего не сделано.") + (Other-Hint $code $board)) }
  if ($VendorOk -notcontains $vend) { Die (T "XGIMI firmware $vend is not supported (V6.15.58, V6.15.62 or V6.15.19 only). If it is older, let stock update itself (Settings, Wi-Fi), then run again. If it is newer, Lumen OS does not support it yet. Nothing was written." "прошивка XGIMI $vend не поддерживается (только V6.15.58, V6.15.62 или V6.15.19). Если она старее, дайте стоку обновиться (настройки, Wi-Fi) и запустите снова. Если новее, Lumen OS её пока не поддерживает. Ничего не записано.") }
  State-Set serial $script:Serial; State-Set slot $slot
  if ($sys) { $g = Prop ro.z9x.gms; State-Set dev_gms $(if ($g) { $g } else { "1" }) } else { State-Set dev_gms stock }
  if (-not $sys) { $sys = T "XGIMI stock" "родная XGIMI" }
  Ok "XGIMI Z9X $($script:Serial), $(T 'firmware' 'прошивка') $vend, $(T 'slot' 'слот') $($slot.TrimStart('_')), $(T 'system' 'система'): $sys"
}

function Cmd-Check { Check-Tools; Check-Device; Check-Image; Ok (T "check passed; nothing was written" "проверка пройдена; ничего не записано"); State-Set step_check done }

function Cmd-Backup {
  $pre = ""
  if ($script:Projector -eq "play6") { if (-not (Play6-Adb 60)) { Die (P6-NoConn) }; $pre = "play6-" } else { Pick-Device }
  $d = Join-Path $BackupRoot ($pre + (Get-Date -Format "yyyyMMdd-HHmm"))
  New-Item -ItemType Directory -Force -Path (Join-Path $d "apps"), (Join-Path $d "sdcard"), (Join-Path $d "settings") | Out-Null
  Say (T "backup to $d" "резервная копия в $d")
  if ($script:Projector -eq "play6") {
    Say (T "over Wi-Fi this takes about 1 minute per GB" "по Wi-Fi это около минуты на каждый ГБ")
  } elseif ((State-Get image_gms) -eq "0") {
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
function Root-Setting-Wait {  # -GappsZip: Developer options > Rooted debugging is off; $true = the user turned it on, try again
  # (LineageOS adbd asks its adb_root service first: "ADB Root access is disabled by system setting"; never bypassed)
  Write-Host ""
  Write-Host (T "Root debugging is off on the projector, and the Google services step needs it. With the remote: Settings > Device Preferences > Developer options > Rooted debugging: turn it on. If Developer options is not in the list: Settings > Device Preferences > About, press Build 7 times, then go back. Nothing was erased; your data is still there." "На проекторе выключена отладка с правами root, а она нужна для шага с сервисами Google. Пультом: Настройки > Настройки устройства > Для разработчиков > Отладка суперпользователем: включите. Если пункта «Для разработчиков» нет: Настройки > Настройки устройства > Об устройстве, нажмите 7 раз на «Сборка» и вернитесь назад. Ничего не стёрто, данные на месте.")
  return (Ask (T "Is Rooted debugging on now?" "Отладка суперпользователем включена?"))
}
function Root-Setting-Stop {  # the user did not turn it on now: the next run continues at this step
  Die (T "turn on Rooted debugging (Settings > Device Preferences > Developer options) and run the same command again: it continues here. Nothing was erased." "включите отладку суперпользователем (Настройки > Настройки устройства > Для разработчиков) и запустите ту же команду снова: установка продолжится с этого места. Ничего не стёрто.")
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
    if ($GappsZip) {
      Write-Host (T "With -GappsZip the same session also creates the partition z9x_gapps$S ($([math]::Floor($script:GzPart / 1MB)) MB) for $($script:GzName) and does not wipe yet: Lumen OS starts once on the old data, the installer copies the Google apps into that partition as root (Rooted debugging), and only then erases the data, once." "С -GappsZip в той же сессии создаётся ещё раздел z9x_gapps$S ($([math]::Floor($script:GzPart / 1MB)) МБ) для $($script:GzName), а данные пока не стираются: Lumen OS один раз загружается на старых данных, установщик копирует приложения Google в этот раздел с правами root (отладка суперпользователем) и только потом стирает данные, один раз.")
    }
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
    $script:EraseNow = $true
  }
  # -GappsZip: the Google services of this write (Z9x-Gapps-Finish completes them); any other write forgets what an
  # earlier one-pass install left saved
  if ($GappsZip -or (State-Get gapps_plan) -or (State-Get step_gapps_part) -or (State-Get step_gapps_fill)) {
    State-Set gapps_plan $script:GzSha; State-Set step_gapps_part ""; State-Set step_gapps_fill ""
    if ($GappsZip) { State-Set step_wipe "" }
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
  if (-not $GappsZip -and $gv -match "partition-size:z9x_gapps${S}:") { Say (T "z9x_gapps$S (Google services you added) stays as it is; 'gapps -Remove' removes it" "z9x_gapps$S (добавленные вами сервисы Google) остаётся как есть; 'gapps -Remove' его удаляет") }
  Say (T "writing system (about 40 s)" "записываю system (около 40 секунд)")
  if (-not (Fb-Do flash system $script:Image)) { Fb reboot | Out-Null; Die (T "system was not written; the projector still has its old system" "system не записан; на проекторе осталась старая система") }
  State-Set step_flash_system done
  if ($blobImg) {
    if ($gv -match "partition-size:z9x_blobs${S}:") { $null = Fb-Do resize "z9x_blobs$S" $BlobSize } else { $null = Fb-Do create "z9x_blobs$S" $BlobSize }
    if (Fb-Do flash "z9x_blobs$S" $blobImg) { Ok "z9x_blobs$S" } else { Warn (T "codec and audio files not written: no sound and no protected video until 'blobs' + 'flash -KeepData'" "файлы кодеков и звука не записаны: не будет звука и защищённого видео до 'blobs' + 'flash -KeepData'") }
  }
  if (State-Get gapps_plan) { if (-not (Z9x-Gapps-Add $S)) { State-Set gapps_plan "" } }
  if (-not $KeepData) {
    if ((State-Get step_gapps_part) -eq "done") {
      Say (T "the data is wiped after the Google services are in (after the first start)" "данные стираются после того, как сервисы Google будут добавлены (после первого запуска)")
    } elseif ($Wipe -eq "format") {
      Say (T "wiping data (format userdata, metadata, cache)" "очистка данных (format userdata, metadata, cache)")
      if (-not (Fb-Do format userdata)) { Fb reboot | Out-Null; Die (T "the wipe failed: do Settings > Device > Reset on the projector instead" "очистка не удалась: сделайте сброс в настройках проектора") }
      $null = Fb-Do format metadata
      if ($gv -match "partition-type:cache:") { $null = Fb-Do format cache }
      State-Set step_wipe done
    } else { State-Set step_wipe factory-reset }
  }
  Fb reboot | Out-Null
  State-Set step_flash done
  if ((State-Get step_gapps_part) -eq "done") { Ok (T "written. Lumen OS now starts once on the old data (2-4 minutes); keep the cable in." "записано. Сейчас Lumen OS один раз загрузится на старых данных (2–4 минуты); кабель не вынимайте."); return }
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

# ------------------------------------------------------------------ XGIMI Z9X: Lumen OS and Google services in one pass (-GappsZip FILE)
# See lumen-install.sh, same section: the zip checked first; in the 'flash' session system (+ z9x_blobs), then
# z9x_gapps_<slot> created or resized and formatted, and NO wipe there (no room or a failure: Google left out, the
# session wipes as a clean install does); Lumen OS starts once on the old data (USB debugging carries over), adb root
# (Rooted debugging off: Root-Setting-Wait), gapps_fill.sh fills, z9x_gapps.sh 'check'; fastbootd again: Fb-Wipe.
# Filled on the projector, not built here: an image made on this computer would need e2fsdroid (root-owned files).
# State: gapps_plan, step_gapps_part, step_gapps_fill (started | done), step_wipe (empty until the wipe).
$script:GzSha = ""; $script:GzName = ""; $script:GzPart = [int64]0; $script:GzZip = ""
function Z9x-Gapps-Plan {  # -GappsZip, before anything else: the zip, and that the install saved here can take it
  if (-not (Test-Path -LiteralPath $GappsZip)) { Die (T "no file $GappsZip" "нет файла $GappsZip") }
  $script:GzZip = (Resolve-Path -LiteralPath $GappsZip).Path
  $zname = Split-Path -Leaf $script:GzZip
  Say (T "checking $zname" "проверяю $zname")
  $script:GzSha = Sha256 $script:GzZip
  $zl = Gapps-Line $script:GzSha
  if (-not $zl) { Die ((T "$zname is not the MindTheGapps file this installer accepts (sha256 $($script:GzSha.Substring(0,16))...). Download exactly the file the guide names, section 'Adding Google services'" "$zname не тот файл MindTheGapps, который принимает установщик (sha256 $($script:GzSha.Substring(0,16))...). Скачайте ровно тот файл, что указан в инструкции, раздел «Как поставить сервисы Google»") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  $script:GzName = ("$zl" -split " ")[1]; $script:GzPart = [int64](Gapps-Field $zl "part")
  Ok "$($script:GzName), sha256 $($script:GzSha.Substring(0,16))..."
  $plan = State-Get gapps_plan
  if ((State-Get step_flash) -eq "done") {
    if ((State-Get step_gapps_part) -ne "done" -or ((State-Get step_wipe) -and (State-Get step_gapps_fill) -ne "done")) {
      Die (T "Lumen OS was installed here without Google services. To add them: lumen-install.cmd gapps -Zip FILE. For a new install: lumen-install.cmd reset first. Nothing was written." "Lumen OS здесь уже установлена без сервисов Google. Добавить их: lumen-install.cmd gapps -Zip ФАЙЛ. Для новой установки сначала: lumen-install.cmd reset. Ничего не записано.")
    }
    if ($plan -and $plan -ne $script:GzSha) { Die (T "this install was started with another MindTheGapps file: use the same file. Nothing was erased." "эта установка начата с другим файлом MindTheGapps: укажите тот же файл. Ничего не стёрто.") }
  }
}
function Z9x-Gapps-Add($S) {  # fastbootd after system: z9x_gapps_<slot> made and formatted for the plan; $false = left out (warned)
  $p = "z9x_gapps$S"
  $part = [int64]0; $pt = Gapps-Field (Gapps-Line (State-Get gapps_plan)) "part"; if ($pt) { $part = [int64]$pt }
  $why = T "the install goes on without Google services (add them later: gapps -Zip FILE)" "установка продолжается без сервисов Google (добавить их позже: gapps -Zip ФАЙЛ)"
  $gv = Fb getvar all   # system and z9x_blobs have their new sizes now
  $free = Super-Free $gv; $cur = [int64]0
  $have = Gv-Size $gv $p; if ($null -ne $have) { $cur = $have }
  if ($part -le 0 -or ($free + $cur) -lt $part) { Warn ((T "not enough free space in super for the Google services ($([math]::Floor(($free + $cur) / 1MB)) MB): " "в super мало места для сервисов Google ($([math]::Floor(($free + $cur) / 1MB)) МБ): ") + $why); return $false }
  Say (T "creating $p ($([math]::Floor($part / 1MB)) MB) for the Google services" "создаю $p ($([math]::Floor($part / 1MB)) МБ) для сервисов Google")
  $made = if ($cur -gt 0) { Fb-Do resize $p $part } else { Fb-Do create $p $part }
  if (-not $made) { Warn ((T "$p could not be created: " "$p создать не удалось: ") + $why); return $false }
  if (-not (Fb-Do format $p)) { $null = Fb-Do delete $p; Warn ((T "$p could not be formatted and was removed again: " "$p не удалось отформатировать, раздел удалён: ") + $why); return $false }
  State-Set step_gapps_part done
  return $true
}
function Z9x-In-Fastboot {
  if (-not $script:Serial) { return $false }
  return (@(& fastboot devices 2>$null | ForEach-Object { ("$_".Trim() -split "\s+")[0] }) -ccontains $script:Serial)
}
function Z9x-Root-Wait {  # adb root over USB; Rooted debugging off: waits for the user; else STOP (the next run continues here)
  while ($true) {
    if ((Prop ro.debuggable) -ne "1") { Die (T "adb root is not possible on this system (Lumen OS is a userdebug build: is it really Lumen OS?). Nothing was erased." "adb root на этой системе невозможен (Lumen OS собрана как userdebug: точно ли это Lumen OS?). Ничего не стёрто.") }
    if (((Adb shell id -u) | Out-String).Trim() -eq "0") { return }
    $out = (& adb -s $script:Serial root 2>&1) | Out-String   # both streams: the refusal text decides
    if ($out -match "disabled by system setting") { if (-not (Root-Setting-Wait)) { Root-Setting-Stop }; continue }
    Start-Sleep 3; Adb wait-for-device | Out-Null
    if (((Adb shell id -u) | Out-String).Trim() -eq "0") { return }
    Die (T "adb root did not work ($($out.Trim())). Run the same command again: it continues here. Nothing was erased." "adb root не сработал ($($out.Trim())). Запустите ту же команду снова: установка продолжится с этого места. Ничего не стёрто.")
  }
}
function Z9x-Gapps-Fill {  # Lumen OS on the old data, root, fill, check; $false = Google left out (the wipe follows anyway)
  $slot = State-Get slot; $p = "z9x_gapps$slot"
  $again = T "Run the same command again: it continues here. Nothing was erased." "Запустите ту же команду снова: установка продолжится с этого места. Ничего не стёрто."
  Say (T "waiting for Lumen OS to start on the old data (up to 5 minutes)" "жду, пока Lumen OS загрузится на старых данных (до 5 минут)")
  if (-not (Boot-Wait)) {
    if (Z9x-In-Fastboot) {
      Warn (T "Lumen OS did not start on the old data: the projector went to fastboot mode after failed starts. The install finishes without Google services (add them later: gapps -Zip FILE)." "Lumen OS не загрузилась на старых данных: после неудачных запусков проектор ушёл в режим fastboot. Установка завершается без сервисов Google (добавить их позже: gapps -Zip ФАЙЛ).")
      return $false
    }
    Die (T "Lumen OS did not come back over USB within 5 minutes. If Lumen OS is on the screen: cable in, debugging allowed on the screen? Run the same command again: it continues here. If the projector restarts again and again: after 3 failed starts it goes to fastboot mode by itself; then run the same command again: it wipes the data and finishes without Google services. Nothing was erased." "Lumen OS не вернулась по USB за 5 минут. Если на экране Lumen OS: кабель вставлен, отладка на экране разрешена? Запустите ту же команду снова: установка продолжится с этого места. Если проектор перезагружается снова и снова: после 3 неудачных запусков он сам уходит в режим fastboot; тогда запустите ту же команду снова: она сотрёт данные и завершит установку без сервисов Google. Ничего не стёрто.")
  }
  if ((Prop ro.z9x.gms) -ne "0") { Die (T "the projector does not run the Lumen OS without Google that was written (ro.z9x.gms='$(Prop ro.z9x.gms)'). Nothing was erased." "на проекторе не та Lumen OS без Google, что была записана (ro.z9x.gms='$(Prop ro.z9x.gms)'). Ничего не стёрто.") }
  Ok (T "Lumen OS $(Prop ro.z9x.version) started on the old data" "Lumen OS $(Prop ro.z9x.version) загрузилась на старых данных")
  Z9x-Root-Wait
  $al = @((Adb shell cat /system/etc/z9x/gapps_allow.txt) | ForEach-Object { "$_".TrimEnd("`r") })
  if (-not ($al | Where-Object { $_ -like "zip=$($script:GzSha) *" })) { Warn (T "this Lumen OS does not accept $($script:GzName): the install finishes without Google services" "эта Lumen OS не принимает $($script:GzName): установка завершается без сервисов Google"); return $false }
  if ((State-Get step_gapps_fill) -eq "started") {
    # a fill of an earlier run did not finish: the partition must be empty again (gapps_fill.sh fills only that)
    Say (T "an earlier fill did not finish: formatting $p again" "прошлое заполнение не завершилось: снова форматирую $p")
    $null = Fbd-Enter $slot
    if (-not (Fb-Do format $p)) { Fb reboot | Out-Null; Die ((T "$p could not be formatted. " "$p отформатировать не удалось. ") + $again) }
    Fb reboot | Out-Null
    if (-not (Boot-Wait)) { Die ((T "the projector did not come back over USB within 5 minutes. " "проектор не вернулся по USB за 5 минут. ") + $again) }
    Z9x-Root-Wait
  }
  Gapps-Push-Fill
  $o = Gapps-Sh pre $script:GzSha (Get-Item -LiteralPath $script:GzZip).Length
  if (-not $o.StartsWith("OK pre ")) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "the projector is not ready" "проектор не готов") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Nothing was erased." "Ничего не стёрто.")) }
  Say (T "copying $($script:GzName) to the projector (about a minute)" "копирую $($script:GzName) на проектор (около минуты)")
  Adb push $script:GzZip "$GappsDir/gapps.zip" | Out-Null
  if ($LASTEXITCODE -ne 0) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "adb push of the zip failed. " "adb push архива не удался. ") + $again) }
  Say (T "copying the Google apps into $p and checking each one (1 to 2 minutes)" "копирую приложения Google в $p и проверяю каждое (1–2 минуты)")
  State-Set step_gapps_fill started
  $o = Gapps-Sh fill $script:GzSha
  if (-not $o.StartsWith("OK fill ")) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "the Google services were not added" "сервисы Google не добавлены") + ": " + ($o -replace "^FAIL ", "") + ". " + $again) }
  $c = ((Adb shell sh /system/etc/z9x/z9x_gapps.sh check) | Out-String).Trim()
  Adb shell rm -rf $GappsDir /metadata/z9x_gapps | Out-Null
  if (-not $c.StartsWith("ok: ")) { Die ((T "the projector's own check of $p failed" "собственная проверка $p на проекторе не прошла") + ": $c. " + $again) }
  Ok "${p}: $($c.Substring(4))"
  State-Set gapps $script:GzName
  State-Set step_gapps_fill done
  return $true
}
function Z9x-Gapps-Finish {  # after 'flash' with -GappsZip: the fill (unless done), then the one data wipe
  $slot = State-Get slot
  if ((State-Get step_gapps_fill) -ne "done" -and (State-Get gapps_plan)) {
    if (-not $GappsZip) {
      Say (T "this install was started with Google services (-GappsZip), and they are not in yet" "эта установка начата с сервисами Google (-GappsZip), и они ещё не добавлены")
      if (-not (Ask (T "Finish the install without Google services? (Adding them later with 'gapps' wipes the data once more.)" "Завершить установку без сервисов Google? (Если добавить их позже через 'gapps', данные сотрутся ещё раз.)"))) { Die (T "run the same command again with -GappsZip FILE: it continues there. Nothing was erased." "запустите ту же команду снова с -GappsZip ФАЙЛ: установка продолжится с того же места. Ничего не стёрто.") }
      State-Set gapps_plan ""
    } elseif (-not (Z9x-Gapps-Fill)) { State-Set gapps_plan "" }
  }
  # the one data wipe of this install, in fastbootd (where the projector may already wait after failed starts);
  # a later run than the one where ERASE was typed asks again
  if (-not $script:EraseNow) {
    Write-Host ""
    Write-Host (T "Lumen OS is written. Next the installer ERASES ALL DATA on the projector (apps, accounts, settings, files) in fastbootd, as at a clean install." "Lumen OS записана. Дальше установщик СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы) в fastbootd, как при чистой установке.")
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was erased" "отменено, ничего не стёрто") }
    $script:EraseNow = $true
  }
  $gv = if (Z9x-In-Fastboot) { Fbd-Check $slot } else { Fbd-Enter $slot }
  Fb-Wipe $gv
  Fb reboot | Out-Null
  if ((State-Get step_gapps_fill) -eq "done") { Ok (T "data wiped. The first start takes 2-4 minutes; the setup has a Google sign-in step." "данные стёрты. Первый запуск 2–4 минуты; в настройке есть шаг входа в Google.") }
  else { Ok (T "data wiped. The first start takes 2-4 minutes; the setup screen appears on the projector." "данные стёрты. Первый запуск 2–4 минуты; на проекторе появится экран настройки.") }
}

# ------------------------------------------------------------------ projector profiles (lib\devices.conf)
# What a projector IS comes from lib\devices.conf: data only, checked line by line here and never run. What the
# installer DOES (its steps, and Fb-Allowed) stays in this script, so that file cannot widen what is written. The
# Z9X runs on the constants at the top and does not need lib\devices.conf (it only names other projectors from it).
$DevConf = Join-Path $Here "lib\devices.conf"
$PKeys = @("NAME", "MODEL", "BOARD", "FB_PRODUCT", "VENDOR_OK", "CERT", "IMAGE_TOKEN", "IMAGE_FS", "VERSION_PROP", "LUMEN_PROP",
           "LOGICAL", "SUPER_RESERVE", "GUIDE", "RESTORE_BIN", "RESTORE_MD5")
$script:Prof = @{}
function Devconf-Lines { if (Test-Path -LiteralPath $DevConf) { @(Get-Content -LiteralPath $DevConf -Encoding UTF8) } else { @() } }
function Devconf-Check {  # stops on a missing file, a broken line, an unknown key or a key given twice
  if (-not (Test-Path -LiteralPath $DevConf)) { Die "internal: lib\devices.conf is missing: re-download the installer" }
  $seen = @{}; $n = 0
  foreach ($l in (Devconf-Lines)) {
    $n++
    if ($l -eq "" -or $l.StartsWith("#")) { continue }
    $good = $false
    if ($l -cmatch '^([a-z0-9]+)\.([A-Z][A-Z0-9_]*)=[A-Za-z0-9 ._:/-]*$') {
      $k = "$($Matches[1]).$($Matches[2])"
      if (($PKeys -ccontains $Matches[2]) -and -not $seen.ContainsKey($k)) { $seen[$k] = $true; $good = $true }
    }
    if (-not $good) { Die "internal: lib\devices.conf line $n is not valid (unknown key, a key given twice, or characters that are not allowed): re-download the installer" }
  }
}
function Devconf-Get($id, $key) {  # id KEY -> value ("" without lib\devices.conf)
  $pre = "$id.$key="
  foreach ($l in (Devconf-Lines)) { if ($l.StartsWith($pre, [StringComparison]::Ordinal)) { return $l.Substring($pre.Length) } }
  return ""
}
function Dev-Name($id) {  # id -> the name shown to the user
  $n = Devconf-Get $id "NAME"
  if ($n) { return $n }
  if ($id -eq "z9x") { return "XGIMI Z9X" } elseif ($id -eq "play6") { return "XGIMI Play 6" } else { return $id }
}
function Profile-Of($model, $board) {  # model board -> the id of the profile that has both ("": unknown)
  $mo = @{}; $bo = @{}; $order = @()
  foreach ($l in (Devconf-Lines)) {
    if ($l -cmatch '^([a-z0-9]+)\.MODEL=(.*)$') { $mo[$Matches[1]] = $Matches[2]; $order += $Matches[1] }
    elseif ($l -cmatch '^([a-z0-9]+)\.BOARD=(.*)$') { $bo[$Matches[1]] = $Matches[2] }
  }
  foreach ($i in $order) { if ($mo[$i] -ceq $model -and $bo[$i] -ceq $board) { return $i } }
  return ""
}
function Join-And($items) {  # "a, b and c" (T)
  $items = @($items)
  if ($items.Count -le 1) { return ($items -join "") }
  return (($items[0..($items.Count - 2)] -join ", ") + " " + (T "and" "и") + " " + $items[-1])
}
function Profiles-Named {  # "XGIMI Z9X (G0082) and XGIMI Play 6 (...)": every projector of lib\devices.conf
  $nm = @{}; $mo = @{}; $order = @()
  foreach ($l in (Devconf-Lines)) {
    if ($l -cmatch '^([a-z0-9]+)\.NAME=(.*)$') { $nm[$Matches[1]] = $Matches[2]; $order += $Matches[1] }
    elseif ($l -cmatch '^([a-z0-9]+)\.MODEL=(.*)$') { $mo[$Matches[1]] = $Matches[2] }
  }
  return (Join-And @($order | ForEach-Object { "$($nm[$_]) ($($mo[$_]))" }))
}
function Load-Profile($id) {  # id: $script:Prof (NAME, MODEL, ...) of that projector
  Devconf-Check
  if (-not (Devconf-Get $id "NAME")) { Die "internal: lib\devices.conf has no projector '$id': re-download the installer" }
  $script:Prof = @{}
  foreach ($k in $PKeys) { $script:Prof[$k] = Devconf-Get $id $k }
  foreach ($k in @("MODEL", "BOARD", "FB_PRODUCT", "VENDOR_OK", "CERT", "IMAGE_TOKEN", "IMAGE_FS", "VERSION_PROP", "LUMEN_PROP", "LOGICAL", "SUPER_RESERVE")) {
    if (-not $script:Prof[$k]) { Die "internal: lib\devices.conf: '$id' has no ${k}: re-download the installer" }
  }
  if ($script:Prof["SUPER_RESERVE"] -notmatch '^[0-9]+$') { Die "internal: lib\devices.conf: $id.SUPER_RESERVE" }
  if ($script:Prof["LUMEN_PROP"] -notmatch ' ') { Die "internal: lib\devices.conf: $id.LUMEN_PROP" }
}
function Other-Hint($model, $board) {  # the Z9X check met another projector this installer knows: its way in
  if ((Profile-Of $model $board) -eq "play6") { return " " + (T "It is an $(Dev-Name play6): it installs over Wi-Fi, run the installer with -Ip ADDRESS (see its section of the guide)." "Это $(Dev-Name play6): он ставится по Wi-Fi, запустите установщик с -Ip АДРЕС (см. его раздел инструкции).") }
  return ""
}
function P6-Unfinished {  # a Play 6 install is saved that was checked and has not reached the wipe yet
  $f = Join-Path (Join-Path $StateDir "play6") "state"
  if (-not (Test-Path -LiteralPath $f)) { return $false }
  $c = @(Get-Content -LiteralPath $f)
  return (($c -contains "step_check=done") -and -not ($c | Where-Object { $_ -like "step_wipe=?*" }))
}
function Z9x-Unfinished {
  $f = Join-Path $StateDir "state"
  if (-not (Test-Path -LiteralPath $f)) { return $false }
  $c = @(Get-Content -LiteralPath $f)
  return (($c -contains "step_check=done") -and -not ($c -contains "step_flash=done"))
}
function Resolve-Device {  # the projector: -Device, else -Ip or 'stick' (Play 6), this download, an unfinished own build
  if (@("", "z9x", "play6") -notcontains $RelDevice) { Die "internal: release.conf DEVICE=${RelDevice}: re-download the installer" }
  # A download names its projector (release.conf DEVICE, none = the Z9X), so a Z9X download behaves exactly as it
  # always did, also on a computer that keeps an unfinished Play 6 install. Only the owner's own builds
  # (-UnsignedImage, not bound to a download) let an unfinished install name the projector.
  if ($Device) { $script:Projector = $Device }
  elseif ($Ip -or $Command -eq "stick") { $script:Projector = "play6" }
  elseif ($RelDevice) { $script:Projector = $RelDevice }
  elseif ($UnsignedImage -and (P6-Unfinished)) {
    if (Z9x-Unfinished) { Die (T "two unfinished installs are saved, an XGIMI Z9X and an $(Dev-Name play6): choose one with -Device z9x or -Device play6" "сохранены две незаконченные установки, XGIMI Z9X и $(Dev-Name play6): выберите одну через -Device z9x или -Device play6") }
    $script:Projector = "play6"
  }
  $script:Projector = "$($script:Projector)".ToLower()
  if ($script:Projector -eq "z9x" -and $Ip) { Die (T "-Ip is for the $(Dev-Name play6); the XGIMI Z9X installs over the USB cable (guide)" "-Ip нужен для $(Dev-Name play6); XGIMI Z9X ставится по кабелю USB (инструкция)") }
  if ($script:Projector -eq "play6") {
    Load-Profile "play6"
    $script:SD = Join-Path $StateDir "play6"
    $script:ImgGlob = "lumen-os-$($script:Prof['IMAGE_TOKEN'])-*-system.img"
  }
}
function Release-Guard {  # the image of this download belongs to one projector (release.conf DEVICE; none = the Z9X)
  $rel = if ($RelDevice) { "$RelDevice".ToLower() } else { "z9x" }
  if ($rel -eq $script:Projector) { return }
  if ($UnsignedImage) {
    Warn (T "this installer folder came with the $(Dev-Name $rel) release; your own image is used (-UnsignedImage)" "эта папка установщика из релиза для $(Dev-Name $rel); используется ваш собственный образ (-UnsignedImage)")
    return
  }
  Die (T "this download of the installer is for the $(Dev-Name $rel), not for the $(Dev-Name $script:Projector): download the $(Dev-Name $script:Projector) release (see the guide). Nothing was done." "этот установщик скачан для $(Dev-Name $rel), а не для $(Dev-Name $script:Projector): скачайте релиз для $(Dev-Name $script:Projector) (см. инструкцию). Ничего не сделано.")
}

# ------------------------------------------------------------------ XGIMI Play 6 (see lumen-install.sh, section 'XGIMI Play 6')
# adb runs over Wi-Fi only ($script:AdbT = ADDR:PORT): the projector's one USB-A port is a host port under Android.
# fastbootd is reached with 'adb reboot fastboot' sent over Wi-Fi and a USB A-to-A cable in that port; the projector
# does not start while that cable is in, so it goes in only for the write. Steps: check -> backup (asked) -> write
# (fastbootd, system only) -> boot1 (first start on the old data) -> wipe (Android's own factory reset as root over
# adb, carried out by XGIMI's recovery) -> verify (optional, later). Progress: $StateDir\play6\state.
$P6App = "org.z9x.projector"                      # the projector app of the image
$P6Tuner = "com.zacharee1.systemuituner"          # installed on stock to turn debugging on; a wipe removes it
$P6Marker = "/data/local/tmp/lumen-install-wipe"  # written before the wipe; a real wipe removes it
function P6-LProp { return ($script:Prof["LUMEN_PROP"] -split " ", 2)[0] }
function P6-LVal { return ($script:Prof["LUMEN_PROP"] -split " ", 2)[1] }
function P6-Name { return $script:Prof["NAME"] }
function P6-Guide { $l = if ($Lang -eq "ru") { "ru" } else { "en" }; return "$GuideBase/$($script:Prof['GUIDE'])/$l.md" }
$script:P6St = ""   # what 'adb get-state' said last in Wifi-Connect (unauthorized: the allow prompt was not accepted)
function P6-NoConn($secs) {  # why Wifi-Connect gave up, as the user can act on it
  $nm = P6-Name; $t = $script:AdbT
  if (-not $secs -or $secs -eq 60) { $en = "within a minute"; $ru = "за минуту" } else { $en = "within $([int]($secs / 60)) minutes"; $ru = "за $([int]($secs / 60)) мин." }
  if ($script:P6St -match "unauthorized") {
    return (T "the projector at $t did not allow this computer $en. On the projector: allow debugging from this computer, tick `"Always allow from this computer`" and press OK; then run the installer again. Nothing was done." "проектор по адресу $t не разрешил отладку с этого компьютера $ru. На проекторе разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK, затем запустите установщик снова. Ничего не сделано.")
  }
  return (T "no adb connection to $t over Wi-Fi $en. On the projector: is Wi-Fi on, is network debugging on (guide, section '$nm'), is it this address (the projector's network settings show it)? This computer must be in the same network. Nothing was done." "нет связи adb с $t по Wi-Fi $ru. На проекторе включены Wi-Fi и отладка по сети (инструкция, раздел «${nm}»), адрес тот же (он виден в настройках сети проектора)? Компьютер должен быть в той же сети. Ничего не сделано.")
}
function P6-State { return ((@(& adb -s $script:AdbT get-state 2>&1 | ForEach-Object { "$_" }) -join "`n").Trim()) }
function P6-Alive {  # $true = the projector answers on a NEW connection (an old one may still say 'device' after a restart)
  & adb disconnect $script:AdbT 2>&1 | Out-Null
  & adb connect $script:AdbT 2>&1 | Out-Null
  return ((P6-State) -eq "device")
}
$P6NoMark = "lumen-install-no-check-file"
function P6-Marker {  # the wipe's check file: 'there' (no wipe since it was written), 'gone' (erased), '' (could not read)
  # Every attempt writes the file before it saves the token and before it asks for the reset, so a file that is
  # there, whatever token it holds, means that no wipe happened after the last request.
  $m = ((Adb shell "cat $P6Marker 2>/dev/null || echo $P6NoMark") | Out-String).Trim()
  if ($m -ceq $P6NoMark) { return "gone" }
  if ($m.StartsWith("lumen-")) { return "there" }
  return ""
}
function P6-Discover($ser) {  # ADDR:PORT of the one projector that 'adb mdns services' lists (with that serial), or ""
  $pre = if ($ser) { "adb-$ser-" } else { "" }
  $found = @(& adb mdns services 2>$null | ForEach-Object {
    $f = @("$_".Trim() -split "\s+")
    if ($f.Count -ge 3 -and $f[0] -like "adb-*" -and $f[1] -match '^_adb\._tcp\.?$' -and (-not $pre -or $f[0].StartsWith($pre))) { $f[-1] }
  } | Sort-Object -Unique)
  if ($found.Count -eq 1) { return $found[0] } else { return "" }
}
function Wifi-Connect($secs) {  # adb to $script:AdbT until it answers as 'device' ($true) or the time is up ($false)
  # The time counts both the pauses and the real clock (adb connect and get-state can take seconds each on a
  # network that drops packets), so "a minute" stays about a minute.
  $said = $false; $sw = [Diagnostics.Stopwatch]::StartNew()
  for ($i = 0; ; $i += 5) {
    & adb connect $script:AdbT 2>&1 | Out-Null
    $st = P6-State
    $script:P6St = $st
    if ($st -eq "device") { return $true }
    if ($st -match "unauthorized") {
      if (-not $said) { Say (T "On the projector: allow debugging from this computer. Tick `"Always allow from this computer`" and press OK." "На проекторе разрешите отладку с этого компьютера: отметьте «Всегда разрешать с этого компьютера» и нажмите OK."); $said = $true }
    } elseif ($st -match "offline") { & adb disconnect $script:AdbT 2>&1 | Out-Null }
    if ($i -ge $secs -or $sw.Elapsed.TotalSeconds -ge $secs) { return $false }
    # the address may have changed (DHCP): the projector's mDNS name carries its serial (only that projector, also
    # with -Ip: P6-Identity checks the serial again)
    if ($script:Serial -and ($i % 30) -eq 25) {
      $a = P6-Discover $script:Serial
      if ($a -and $a -ne $script:AdbT) { Say (T "the projector answers at a new address: $a" "проектор отвечает по новому адресу: $a"); $script:AdbT = $a }
    }
    Start-Sleep 5
  }
}
function P6-Identity {  # the device at AdbT: this projector model, and the saved one (DHCP may give its address away)
  $code = Prop ro.boot.xgimi.modelname; $board = Prop ro.boot.hardware; $ser = Prop ro.serialno; $t = $script:AdbT
  if ($code -cne $script:Prof["MODEL"] -or $board -cne $script:Prof["BOARD"]) {
    & adb disconnect $t 2>&1 | Out-Null
    if ((Profile-Of $code $board) -eq "z9x") { Die (T "the device at $t is an XGIMI Z9X: it installs over the USB cable, without -Ip (guide). Nothing was done." "устройство по адресу $t это XGIMI Z9X: он ставится по кабелю USB, без -Ip (инструкция). Ничего не сделано.") }
    Die (T "this projector is not supported: model '$code', board '$board'. Lumen OS installs on $(Profiles-Named). Nothing was done." "этот проектор не поддерживается: код модели '$code', плата '$board'. Lumen OS ставится на $(Profiles-Named). Ничего не сделано.")
  }
  if (-not $ser) { Die (T "the projector at $t reports no serial number. Nothing was done." "проектор по адресу $t не сообщает серийный номер. Ничего не сделано.") }
  if ($script:Serial -and $script:Serial -cne $ser) {
    & adb disconnect $t 2>&1 | Out-Null
    $saved = $script:Serial
    if ($script:SerialFromState) { Die (T "the projector at $t has the serial $ser, but this install was started on $saved. Another projector? Then first: lumen-install.cmd reset -Device play6. Nothing was done." "у проектора по адресу $t серийный номер $ser, а установка начиналась на $saved. Другой проектор? Тогда сначала: lumen-install.cmd reset -Device play6. Ничего не сделано.") }
    Die (T "the projector at $t has the serial $ser, not $saved (-Serial). Nothing was done." "у проектора по адресу $t серийный номер $ser, а не $saved (-Serial). Ничего не сделано.")
  }
  $script:Serial = $ser
}
function P6-Usb-Hint {  # no address for a Play 6: a Z9X on the USB cable means the wrong download or the wrong option
  $l = @(& adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] } | Where-Object { $_ -notmatch ":" })
  if ($l.Count -ne 1) { return }
  $code = ((& adb -s $l[0] shell getprop ro.boot.xgimi.modelname 2>$null) | Out-String).Trim()
  $board = ((& adb -s $l[0] shell getprop ro.boot.hardware 2>$null) | Out-String).Trim()
  if ((Profile-Of $code $board) -eq "z9x") {
    $nm = P6-Name
    if ($RelDevice -eq "play6") { Die (T "the projector on the USB cable is an XGIMI Z9X, and this download of the installer is for the ${nm}: download the XGIMI Z9X release (see the guide). Nothing was done." "на кабеле USB проектор XGIMI Z9X, а этот установщик скачан для ${nm}: скачайте релиз для XGIMI Z9X (см. инструкцию). Ничего не сделано.") }
    Die (T "the projector on the USB cable is an XGIMI Z9X: run the installer without -Device play6. Nothing was done." "на кабеле USB проектор XGIMI Z9X: запустите установщик без -Device play6. Ничего не сделано.")
  }
}
function Play6-Adb($secs) {  # adb over Wi-Fi to the Play 6 (-Ip, the saved address, or mDNS) and who it is; $false = no answer
  if (-not $script:AdbT) {
    $a = State-Get ip
    if (-not $a) {
      $a = P6-Discover ""
      if (-not $a -or -not (Ask (T "A projector answers at $a. Is it yours, the one to install on?" "По адресу $a отвечает проектор. Это ваш, на который ставим?"))) {
        P6-Usb-Hint
        $nm = P6-Name
        Die (T "give the projector's Wi-Fi address: lumen-install.cmd -Ip 192.168.x.y (the projector's network settings show it; see the guide, section '$nm'). The $nm has no USB debugging: this computer reaches it over Wi-Fi. Nothing was done." "укажите адрес проектора в Wi-Fi: lumen-install.cmd -Ip 192.168.x.y (он виден в настройках сети проектора; см. инструкцию, раздел «${nm}»). У $nm нет отладки по USB: компьютер подключается к нему по Wi-Fi. Ничего не сделано.")
      }
    }
    $script:AdbT = $a
  }
  Say (T "connecting to $($script:AdbT) over Wi-Fi" "подключаюсь к $($script:AdbT) по Wi-Fi")
  if (-not (Wifi-Connect $secs)) { return $false }
  P6-Identity
  return $true
}
function P6-Fw-List { return (Join-And @($script:Prof["VENDOR_OK"] -split " " | ForEach-Object { "V" + $_.Substring(1) })) }
function P6-Fw-First { return ("V" + ($script:Prof["VENDOR_OK"] -split " ")[0].Substring(1)) }
function Play6-Device {  # connected: firmware, single slot, stock or Lumen OS; saves serial, address, firmware
  $nm = P6-Name
  $vend = Prop ro.vendor.build.version.incremental; $slot = Prop ro.boot.slot_suffix; $lum = Prop (P6-LProp)
  if (@($script:Prof["VENDOR_OK"] -split " ") -cnotcontains $vend) {
    $all = P6-Fw-List; $first = P6-Fw-First
    Die (T "XGIMI firmware $vend is not supported: Lumen OS for the $nm is tested on $all only. Older: let the stock system update itself to $first (Settings, Wi-Fi), then run the installer again. Newer (for example V1.8.x, shown in Settings as R$($script:Prof["MODEL"])V1.8xxx): Lumen OS does not support it yet. Do NOT install $first from a USB stick over it: newer $nm units have another Wi-Fi chip, $first has no driver for it, and Wi-Fi and Bluetooth then stop working. Nothing was done." "прошивка XGIMI $vend не поддерживается: Lumen OS для $nm проверена только на $all. Если ваша старее, дайте стоку обновиться до $first (настройки, Wi-Fi) и запустите установщик снова. Если новее (например V1.8.x, в настройках видна как R$($script:Prof["MODEL"])V1.8xxx), Lumen OS её пока не поддерживает. НЕ ставьте поверх неё $first с флешки: в новых $nm другой чип Wi-Fi, драйвера для него в $first нет, и Wi-Fi с Bluetooth перестают работать. Ничего не сделано.")
  }
  if ($slot) { Die (T "this projector has a second system slot ($slot): it is not the $nm this installer knows. Nothing was done." "у этого проектора есть второй системный слот ($slot): это не тот $nm, который знает установщик. Ничего не сделано.") }
  if (-not $lum) {
    State-Set dev_lumen 0; State-Set dev_version ""; $ver = T "XGIMI stock" "родная XGIMI"
    # a downloaded XGIMI update arms the next restart; the installer's own restart into fastbootd does not run it
    $up = Prop xgimi.ota.update.onboot
    if (-not $up) { $up = ((Adb shell settings get system xgimi_ota_update) | Out-String).Trim() }
    if ($up -eq "1" -or $up -eq "2") { Warn (T "a downloaded XGIMI update waits for the next restart: do not restart the projector yourself until the installer has written Lumen OS (it replaces that system anyway). Next time turn automatic updates off first." "скачанное обновление XGIMI ждёт следующей перезагрузки: не перезагружайте проектор сами, пока установщик не запишет Lumen OS (она всё равно заменит эту систему). В следующий раз сначала выключите автообновление.") }
  } elseif ($lum -ceq (P6-LVal)) { $v = Prop $script:Prof['VERSION_PROP']; State-Set dev_lumen 1; State-Set dev_version $v; $ver = "Lumen OS $v" }
  else { Die (T "this projector runs Lumen OS for another device ('$lum'). Nothing was done." "на проекторе Lumen OS для другого устройства ('$lum'). Ничего не сделано.") }
  State-Set serial $script:Serial; State-Set ip $script:AdbT; State-Set vendor $vend
  Ok "$nm $($script:Serial) ($($script:AdbT)), $(T 'firmware' 'прошивка') $vend, $(T 'system' 'система'): $ver"
}
function Read-Hex($f, $off, $n) {  # n bytes of file f at off, as lower-case hex ("" when the file is shorter)
  $fs = [IO.File]::OpenRead($f)
  try {
    if ($fs.Length -lt ($off + $n)) { return "" }
    $null = $fs.Seek($off, [IO.SeekOrigin]::Begin)
    $b = New-Object byte[] $n
    $null = $fs.Read($b, 0, $n)
    return (($b | ForEach-Object { $_.ToString("x2") }) -join "")
  } finally { $fs.Close() }
}
function Verify-Sig($sums, $sig, $cert) {  # SHA256SUMS.sig made with the key of this certificate
  $c = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 -ArgumentList $cert
  $rsa = [System.Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($c)
  return $rsa.VerifyData([IO.File]::ReadAllBytes($sums), [IO.File]::ReadAllBytes($sig),
         [System.Security.Cryptography.HashAlgorithmName]::SHA256, [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
}
function Play6-Check-Image([switch]$Force) {  # name, file system, size, signature, checksum, content
  Find-Image
  $img = $script:Image; $name = Split-Path -Leaf $img; $dir = Split-Path -Parent $img; $nm = P6-Name; $tok = $script:Prof["IMAGE_TOKEN"]
  $sums = Join-Path $dir "SHA256SUMS"; $sig = "$sums.sig"; $cert = Join-Path $Here ($script:Prof["CERT"] -replace '/', '\')
  if (-not $name.Contains($tok)) { Die (T "$name is not an image for the $nm (its name must contain '$tok'). Nothing was written." "$name не образ для $nm (в имени должно быть '$tok'). Ничего не записано.") }
  if ($name -cmatch "PRIVATE") { Die (T "$name is a private image: it is never installed on the $nm. Nothing was written." "$name частный образ: на $nm он не ставится. Ничего не записано.") }
  $nv = ""
  if (-not $UnsignedImage) {
    if ($name -notlike "*-nogms-*") { Die (T "$name is not a release file (the $nm has Lumen OS without Google only: '-nogms-' in the name). Nothing was written." "$name не файл релиза (для $nm есть только Lumen OS без Google: '-nogms-' в имени). Ничего не записано.") }
    # the release version is in the name, and the signed SHA256SUMS lists the image under that name: a file of another
    # release (signed with the same key) next to this installer stops here, before anything is written
    if (-not $conf["VERSION"]) { Die "internal: release.conf has no VERSION: re-download the installer of the $nm release" }
    if ($name -cmatch "^lumen-os-$([regex]::Escape($tok))-(.+)-nogms-system\.img$") { $nv = $Matches[1] }
    if (-not $nv) { Die (T "$name is not named like a release file (lumen-os-$tok-<version>-nogms-system.img): use the file exactly as downloaded. Nothing was written." "$name назван не как файл релиза (lumen-os-$tok-<версия>-nogms-system.img): используйте файл ровно таким, как он скачан. Ничего не записано.") }
    if ($nv -cne $conf["VERSION"]) { Die (T "$name is Lumen OS $nv, but this installer download is for Lumen OS $($conf['VERSION']): download the image and the installer from the same release (guide, section '$nm'). Nothing was written." "$name это Lumen OS $nv, а этот установщик скачан для Lumen OS $($conf['VERSION']): скачайте образ и установщик из одного релиза (инструкция, раздел «${nm}»). Ничего не записано.") }
  }
  # the file system, read without any tool: raw ext4 has 53 ef at byte 1080
  if ($script:Prof["IMAGE_FS"] -eq "ext4" -and (Read-Hex $img 1080 2) -ne "53ef") {
    if ((Read-Hex $img 1024 4) -eq "e2e1f5e0") { Die (T "$name is an XGIMI Z9X image (erofs), not one for the $nm. Nothing was written." "$name образ для XGIMI Z9X (erofs), а не для $nm. Ничего не записано.") }
    if ((Read-Hex $img 0 4) -eq "3aff26ed") { Die (T "$name is a sparse image, not the release file. Nothing was written." "$name разреженный (sparse) образ, а не файл релиза. Ничего не записано.") }
    Die (T "$name is not an ext4 system image. Nothing was written." "$name не системный образ ext4. Ничего не записано.")
  }
  if ((Get-Item -LiteralPath $img).Length % 4096 -ne 0) { Die (T "$name has a size that is not a multiple of 4096: damaged. Nothing was written." "размер $name не кратен 4096: файл испорчен. Ничего не записано.") }
  if ((Test-Path -LiteralPath $sums) -and (Test-Path -LiteralPath $sig) -and (Test-Path -LiteralPath $cert)) {
    if (-not (Verify-Sig $sums $sig $cert)) { Die (T "SHA256SUMS is not signed with the Lumen OS key for the ${nm}: do not install this image" "SHA256SUMS не подписан ключом Lumen OS для ${nm}: не устанавливайте этот образ") }
    Ok (T "SHA256SUMS signature: Lumen OS key for the $nm" "подпись SHA256SUMS: ключ Lumen OS для $nm")
  } elseif ($UnsignedImage) {
    Warn (T "-UnsignedImage: this image is NOT authenticated (no signed SHA256SUMS). Only for your own build." "-UnsignedImage: образ НЕ проверен (нет подписанного SHA256SUMS). Только для собственной сборки.")
    if ((State-Get unsigned_ok) -ne "${name}:$(File-Key $img)") {
      if ((Read-Host (T "Type UNSIGNED to install $name anyway" "Введите UNSIGNED, чтобы всё равно установить $name")) -cne "UNSIGNED") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
      State-Set unsigned_ok "${name}:$(File-Key $img)"
    }
    if (-not (Test-Path -LiteralPath $sums)) {   # a list that names the image, or the image's own .sha256
      $sums = $null
      $cand = @(Get-ChildItem -Path (Join-Path $dir "SHA256SUMS*") -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike "*.sig" }) + @(Get-Item -LiteralPath "$img.sha256" -ErrorAction SilentlyContinue)
      foreach ($f in ($cand | Where-Object { $_ })) {
        if (Get-Content -LiteralPath $f.FullName | Where-Object { $_ -match "\s\*?$([regex]::Escape($name))$" }) { $sums = $f.FullName; break }
      }
    }
  } else {
    if (-not (Test-Path -LiteralPath $sums)) { Die (T "no SHA256SUMS next to the image: it cannot be checked. Download SHA256SUMS and SHA256SUMS.sig from the same release." "рядом с образом нет SHA256SUMS: его нельзя проверить. Скачайте SHA256SUMS и SHA256SUMS.sig из того же релиза.") }
    if (-not (Test-Path -LiteralPath $sig)) { Die (T "no SHA256SUMS.sig next to the image: its origin cannot be checked" "рядом с образом нет SHA256SUMS.sig: происхождение образа нельзя проверить") }
    Die (T "installer certificate $($script:Prof['CERT']) missing: re-download the installer of the $nm release" "нет сертификата установщика $($script:Prof['CERT']): скачайте установщик из релиза для $nm заново")
  }
  if ($sums -and (Test-Path -LiteralPath $sums)) {
    $want = (Get-Content -LiteralPath $sums | Where-Object { $_ -match "\s\*?$([regex]::Escape($name))$" } | ForEach-Object { ($_ -split "\s+")[0] }) | Select-Object -First 1
    if (-not $want) { Die (T "$name is not listed in $(Split-Path -Leaf $sums)" "$name нет в $(Split-Path -Leaf $sums)") }
    if ($Force -or (State-Get image_ok) -ne "${want}:$(File-Key $img)") {
      Say (T "checking the image checksum (about a minute)" "проверяю контрольную сумму образа (около минуты)")
      if ((Sha256 $img) -ne $want.ToLower()) { State-Set image_ok none; Die (T "checksum mismatch: the image is damaged or not the right file" "контрольная сумма не совпала: образ повреждён или не тот") }
      State-Set image_ok "${want}:$(File-Key $img)"
    }
    Ok "$name sha256 $($want.Substring(0,16))..."
  } else { Warn (T "-UnsignedImage without any SHA256SUMS file: checksum not checked" "-UnsignedImage без файла SHA256SUMS: контрольная сумма не проверена") }
  # what the image says about itself, when debugfs (e2fsprogs) is installed. Without it the name (its version, checked
  # above against release.conf) and the signed SHA256SUMS (which lists the file under that name) bind the image to
  # this release; this also catches a release built wrong. The files in the order init reads them: the last one wins.
  $bound = if ($nv) { T "the signed SHA256SUMS lists this file as Lumen OS $nv, the version of this installer download" "подписанный SHA256SUMS называет этот файл Lumen OS $nv, версией этого установщика" } else { T "an own build (-UnsignedImage)" "собственная сборка (-UnsignedImage)" }
  if (Get-Command debugfs -ErrorAction SilentlyContinue) {
    $bp = @(foreach ($pf in @("/system/etc/prop.default", "/system/build.prop", "/system/system_ext/etc/build.prop", "/system/product/etc/build.prop")) { & debugfs -R "cat $pf" $img 2>$null })
    if ($bp.Count -gt 0) {
      $pv = { param($k) $l = $bp | Where-Object { $_ -like "$k=*" } | Select-Object -Last 1; if ($l) { $l.Substring($k.Length + 1).Trim() } else { "" } }
      $v = & $pv (P6-LProp)
      if ($v -cne (P6-LVal)) { Die (T "$name is not Lumen OS for the $nm ($(P6-LProp)='$v'). Nothing was written." "$name не Lumen OS для $nm ($(P6-LProp)='$v'). Ничего не записано.") }
      $v = & $pv $script:Prof["VERSION_PROP"]; $sec = & $pv "ro.adb.secure"
      if (-not $UnsignedImage) {
        if ($v -cne $conf["VERSION"]) { Die (T "$name holds Lumen OS '$v', the release is $($conf['VERSION']). Nothing was written." "в $name Lumen OS '$v', а релиз $($conf['VERSION']). Ничего не записано.") }
        if ($sec -ne "1") { Die (T "$name is a test build (ro.adb.secure='$sec'), not a release. Nothing was written." "$name тестовая сборка (ro.adb.secure='$sec'), а не релиз. Ничего не записано.") }
      }
      Ok (T "image: Lumen OS $v for the $nm" "образ: Lumen OS $v для $nm")
    } else { Warn (T "debugfs could not read the image content (not needed: $bound)" "debugfs не смог прочитать содержимое образа (не обязательно: $bound)") }
  } else { Say (T "image content not read (debugfs not installed): $bound" "содержимое образа не прочитано (нет debugfs): $bound") }
  State-Set image_gms 0
  Ok (T "edition: Lumen OS without Google" "редакция: Lumen OS без Google")
}
function Play6-Check {
  Check-Tools
  if (-not (Play6-Adb 60)) { Die (P6-NoConn) }
  Play6-Device
  $free = (Get-PSDrive -Name (Get-Item -LiteralPath $HOME).PSDrive.Name -ErrorAction SilentlyContinue).Free
  if ($free -and $free -lt 4GB) { Warn (T "less than 4 GB free on this computer" "на компьютере свободно меньше 4 ГБ") }
  Play6-Check-Image
  Ok (T "check passed; nothing was written" "проверка пройдена; ничего не записано")
  State-Set step_check done
}
function P6-In-Fastboot {
  if (-not $script:Serial) { return $false }
  return (@(& fastboot devices 2>$null | ForEach-Object { ("$_".Trim() -split "\s+")[0] }) -ccontains $script:Serial)
}
function P6-Fb-Wait($secs) {  # the projector ($script:Serial) in fastboot mode on the cable
  for ($i = 0; $i -lt $secs; $i += 2) {
    if (P6-In-Fastboot) { return $true }
    if ($i -gt 0 -and ($i % 60) -eq 0) { Say (T "still waiting for fastboot mode..." "всё ещё жду режима fastboot...") }
    Start-Sleep 2
  }
  return $false
}
function P6-Fb-Stop($m) {  # leave fastbootd for the old system: nothing was written
  Fb reboot | Out-Null
  Die ($m + " " + (T "Nothing was written. Unplug the A-to-A cable: the projector then starts its old system (press the power button if the picture does not come back)." "Ничего не записано. Выньте кабель A–A: проектор загрузит прежнюю систему (если изображения нет, нажмите кнопку питания)."))
}
function Gv-Val($gv, $k) { $l = $gv | Where-Object { "$_" -like "(bootloader) ${k}:*" } | Select-Object -First 1; if ($l) { return ("$l".Substring(("(bootloader) ${k}:").Length)).Trim() } else { return "" } }
function Play6-Fbd-Check {  # fastbootd: this Play 6 (serial), its firmware and the known layout of super; returns 'getvar all'
  $gv = @(Fb getvar all); $nm = P6-Name; $why = ""
  if ((Gv-Val $gv "is-userspace") -ne "yes") { $why = T "not fastbootd" "это не fastbootd" }
  elseif ((Gv-Val $gv "product") -cne $script:Prof["FB_PRODUCT"]) { $why = (T "fastbootd is not the one of the $nm" "fastbootd не от $nm") + " ($(Gv-Val $gv 'product'))" }
  elseif ((Gv-Val $gv "unlocked") -ne "yes") { $why = T "the bootloader is locked" "загрузчик заблокирован" }
  elseif ((Gv-Val $gv "serialno") -cne $script:Serial) { $why = (T "another projector in fastboot mode" "в режиме fastboot другой проектор") + " (serialno $(Gv-Val $gv 'serialno'), $(T 'expected' 'ожидался') $($script:Serial))" }
  elseif (@("", "0") -notcontains (Gv-Val $gv "slot-count")) { $why = "slot-count $(Gv-Val $gv 'slot-count')" }
  elseif (Gv-Val $gv "current-slot") { $why = "current-slot $(Gv-Val $gv 'current-slot')" }
  elseif ((Gv-Val $gv "super-partition-name") -cne "super") { $why = "super-partition-name '$(Gv-Val $gv 'super-partition-name')'" }
  else {
    $l = (@($gv | ForEach-Object { if ("$_" -match '^\(bootloader\) is-logical:([^:]+):yes') { $Matches[1] } } | Sort-Object -Unique -CaseSensitive) -join " ")
    $want = (@($script:Prof["LOGICAL"] -split " " | Sort-Object -Unique -CaseSensitive) -join " ")
    # the Google services add-on ('gapps') adds one partition of its own
    $wantg = (@(@($script:Prof["LOGICAL"] -split " ") + @("lumen_gapps") | Sort-Object -Unique -CaseSensitive) -join " ")
    if ($l -cne $want -and $l -cne $wantg) { $why = (T "the partitions in super are not the known ones" "разделы в super не те, что известны") + " ($l)" }
    else {
      $fp = Gv-Val $gv "vendor-fingerprint"; $fw = $false
      foreach ($v in ($script:Prof["VENDOR_OK"] -split " ")) { if ($fp -like "*/$($v.Substring(1)):*") { $fw = $true } }
      if (-not $fw) { $why = (T "XGIMI firmware not supported" "прошивка XGIMI не поддерживается") + " ($fp)" }
    }
  }
  if ($why) { P6-Fb-Stop ((T "fastboot mode check failed" "проверка режима fastboot не пройдена") + ": $why.") }
  Ok (T "fastboot mode: $nm $($script:Serial)" "режим fastboot: $nm $($script:Serial)")
  return $gv
}
function P6-Room($gv, $gapps) {  # the image must fit into super next to the other logical partitions (each rounded up to 1 MiB);
  # with -GappsZip the image and lumen_gapps at $gapps bytes together (an existing lumen_gapps is resized to it)
  $sup = Gv-Size $gv "super"
  if ($null -eq $sup) { P6-Fb-Stop (T "fastbootd did not report the size of super." "fastbootd не сообщил размер super.") }
  $used = [int64]0
  foreach ($p in ($script:Prof["LOGICAL"] -split " ")) {
    if ($p -eq "system") { continue }
    $sz = Gv-Size $gv $p
    if ($null -eq $sz) { P6-Fb-Stop (T "fastbootd did not report the size of $p." "fastbootd не сообщил размер $p.") }
    $used += [int64]([math]::Ceiling($sz / 1MB) * 1MB)
  }
  $sz = Gv-Size $gv "lumen_gapps"   # the Google services add-on ('gapps') keeps its space
  if ($null -ne $sz -and -not $gapps) { $used += [int64]([math]::Ceiling($sz / 1MB) * 1MB) }
  $room = [int64]($sup - $used - [int64]$script:Prof["SUPER_RESERVE"])
  $len = (Get-Item -LiteralPath $script:Image).Length
  if ($gapps) {
    $need = [int64]([math]::Ceiling($len / 1MB) * 1MB) + [int64]([math]::Ceiling([int64]$gapps / 1MB) * 1MB)
    if ($need -gt $room) { P6-Fb-Stop (T "the image and the Google services do not fit into super together ($([math]::Truncate($room / 1MB)) MB free, $([math]::Truncate($need / 1MB)) MB needed)." "образ и сервисы Google вместе не помещаются в super (свободно $([math]::Truncate($room / 1MB)) МБ, нужно $([math]::Truncate($need / 1MB)) МБ).") }
    return
  }
  if ($len -gt $room) { P6-Fb-Stop (T "the image does not fit into super ($([math]::Truncate($room / 1MB)) MB free, $([math]::Ceiling($len / 1MB)) MB needed)." "образ не помещается в super (свободно $([math]::Truncate($room / 1MB)) МБ, нужно $([math]::Ceiling($len / 1MB)) МБ).") }
}
function P6-Cable-Out {  # after 'fastboot reboot': the projector does not start while the computer's cable is in
  $nm = P6-Name
  Write-Host ""
  Write-Host (T "Written. Unplug the A-to-A cable from the projector NOW: the $nm does not start while it is connected. Put any USB stick into its USB port instead (it helps the $nm power on; do NOT hold the power button, that starts the stock restore). If the picture does not come back within a minute, press the power button once." "Записано. Сейчас выньте кабель A–A из проектора: пока он подключён, $nm не загружается. Вставьте вместо него в USB-порт любую флешку (с ней $nm включается надёжнее; кнопку питания НЕ держите, иначе начнётся откат на сток). Если через минуту изображения нет, нажмите кнопку питания один раз.")
  $null = Read-Host (T "Press Enter when the cable is out" "Когда кабель вынут, нажмите Enter")
}
function Play6-Write {  # check, confirmation, cable, fastbootd, write system, restart (resumes in fastbootd)
  $nm = P6-Name; $infb = $false
  Check-Tools
  if ((State-Get step_fastboot) -and (State-Get step_flash) -ne "done" -and (P6-In-Fastboot)) {
    $infb = $true
    if ((State-Get step_flash_system) -eq "done") {   # fastboot said OKAY, the restart was not sent
      if ($GappsZip) { State-Set gapps_plan $script:P6gSha }
      if ((State-Get gapps_plan) -and (State-Get step_gapps_part) -ne "done") { $null = Play6-Fbd-Check; P6-Gapps-Add }
      Ok (T "system is already written; restarting the projector" "system уже записан; перезагружаю проектор")
      Fb reboot | Out-Null
      State-Set step_flash done
      P6-Cable-Out
      return
    }
    Say (T "the projector waits in fastboot mode on the cable: continuing there" "проектор ждёт в режиме fastboot на кабеле: продолжаю там")
  } else {
    if (-not (Play6-Adb 60)) {
      # an earlier run sent the projector to fastboot mode: it is neither there (on the cable) nor back in its system
      if (State-Get step_fastboot) { Die (T "the projector is neither in fastboot mode on the cable nor reachable over Wi-Fi at $($script:AdbT). If the picture is dark: unplug the A-to-A cable (the $nm does not start while it is in) and press the power button; a USB stick in the port helps it start. When its system is back, run the installer again: it sends the projector to fastboot mode again. If it stays dark: guide, section '$nm', 'Back to stock'." "проектора нет ни в режиме fastboot на кабеле, ни в Wi-Fi по адресу $($script:AdbT). Если изображения нет, выньте кабель A–A (пока он вставлен, $nm не загружается) и нажмите кнопку питания; флешка в порту помогает ему загрузиться. Когда система загрузится, запустите установщик снова: он снова отправит проектор в режим fastboot. Если изображения так и нет: инструкция, раздел «${nm}», «Откат на сток».") }
      Die (P6-NoConn)
    }
    Play6-Device
  }
  Play6-Check-Image -Force
  Write-Host ""
  if ($KeepData) {
    Write-Host (T "Writing Lumen OS to the system partition of the $nm WITHOUT erasing data (a repair). XGIMI's firmware stays untouched: vendor, boot, recovery, MBOOT, calibration and factory data." "Записываю Lumen OS в раздел system $nm БЕЗ стирания данных (ремонт). Прошивка XGIMI не затрагивается: vendor, boot, recovery, MBOOT, калибровка и заводские данные.")
    if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  } else {
    Write-Host (T "Writing Lumen OS to the system partition of the $nm, then ERASING ALL DATA (apps, accounts, settings, files, Wi-Fi networks) with Android's factory reset after the first start. XGIMI's firmware stays untouched: vendor, boot, recovery, MBOOT, calibration and factory data. The $nm has only one system: if the write is cut off by a power loss, the way back is the USB stick restore (guide, 'Back to stock'). Keep the power adapter connected." "Записываю Lumen OS в раздел system $nm, затем СТИРАЮ ВСЕ ДАННЫЕ (приложения, аккаунты, настройки, файлы, сети Wi-Fi) сбросом Android к заводским настройкам после первого запуска. Прошивка XGIMI не затрагивается: vendor, boot, recovery, MBOOT, калибровка и заводские данные. Система у $nm одна: если запись оборвётся из-за отключения питания, вернуть проектор можно только восстановлением с флешки (инструкция, «Откат на сток»). Блок питания держите подключённым.")
    if ($GappsZip) {
      Write-Host (T "With -GappsZip the same fastboot session also creates the partition $P6Gapps ($([math]::Floor($script:P6gPart / 1MB)) MB) for $($script:P6gName). After the first start on the old data the installer copies the Google apps into it as root (Rooted debugging), and only then erases the data, once." "С -GappsZip в той же сессии fastboot создаётся ещё раздел $P6Gapps ($([math]::Floor($script:P6gPart / 1MB)) МБ) для $($script:P6gName). После первого запуска на старых данных установщик копирует в него приложения Google с правами root (отладка суперпользователем) и только потом стирает данные, один раз.")
    }
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
    $script:EraseNow = $true
    State-Set erase_ok (Get-Date -Format "yyyyMMdd-HHmmss")
  }
  # -GappsZip: the Google services of this install (P6-Gapps-Fill-Step adds them after the first start). A new write
  # takes this run's choice; a write resumed in fastboot mode keeps the saved one unless -GappsZip is given.
  if (-not $infb -or $GappsZip -or $KeepData) { State-Set gapps_plan $script:P6gSha }
  if (-not $infb) {
    foreach ($k in @("step_fastboot", "step_flash_system", "step_flash", "step_boot1", "step_wipe", "step_verify", "wipe_marker", "step_gapps_part", "step_gapps_fill")) { State-Set $k "" }
    Write-Host ""
    Write-Host (T "Take the USB stick out of the projector's USB port, if one is in, and connect that port to this computer with the USB A-to-A cable. Keep the power adapter connected." "Выньте флешку из USB-порта проектора, если она там, и соедините этот порт с компьютером кабелем USB A–A. Блок питания оставьте подключённым.")
    $null = Read-Host (T "Press Enter when the cable is in" "Когда кабель вставлен, нажмите Enter")
    Say (T "restarting into fastboot mode (about 30 seconds)" "перезагрузка в режим fastboot (около 30 секунд)")
    # saved first: a restart that happens although adb reports an error (the Wi-Fi connection drops with it) is still
    # continued in fastboot mode by the next run
    State-Set step_fastboot (Get-Date -Format "yyyyMMdd-HHmmss")
    Adb reboot fastboot | Out-Null
    if ($LASTEXITCODE -ne 0 -and (P6-Alive)) {
      State-Set step_fastboot ""
      Die (T "adb reboot fastboot failed: the projector still runs its system. Nothing was written. Unplug the A-to-A cable and run the installer again." "adb reboot fastboot не удался: проектор работает в своей системе. Ничего не записано. Выньте кабель A–A и запустите установщик снова.")
    }
    & adb disconnect $script:AdbT 2>&1 | Out-Null
  }
  if (-not (P6-Fb-Wait 180)) { Die (T "the projector did not appear on the cable within 3 minutes. Is the cable in the projector's USB port (not a hub)? On Windows the Google USB Driver is needed. Nothing was written. Run the installer again: it finds the projector in fastboot mode or sends it there again." "проектор не появился на кабеле за 3 минуты. Кабель в USB-порту проектора (не через хаб)? На Windows нужен Google USB Driver. Ничего не записано. Запустите установщик снова: он найдёт проектор в режиме fastboot или снова отправит его туда.") }
  $gv = Play6-Fbd-Check
  P6-Room $gv (P6-Plan-Part)
  Say (T "writing system (about 1 minute). Do not unplug the cable or the power until it is done." "записываю system (около минуты). Не отключайте кабель и питание, пока запись не закончится.")
  if (-not (Fb-Do flash system $script:Image)) { Die (T "system was not written completely. The projector stays in fastboot mode: keep the cable in and run the installer again, it writes system again." "system записан не полностью. Проектор остаётся в режиме fastboot: не вынимайте кабель и запустите установщик снова, он запишет system ещё раз.") }
  State-Set step_flash_system done
  if (State-Get gapps_plan) { P6-Gapps-Add }
  Fb reboot | Out-Null
  State-Set step_flash done
  P6-Cable-Out
}
function P6-Write-Again {  # the write did not take effect: the next run (or 'flash') writes system again from the start
  foreach ($k in @("step_fastboot", "step_flash_system", "step_flash")) { State-Set $k "" }
}
function Play6-Boot1 {  # the first start of Lumen OS on the old data: Wi-Fi and debugging carry over from stock
  $nm = P6-Name
  if (-not $script:AdbT) { $script:AdbT = State-Get ip }
  if (-not $script:AdbT) { Die "internal: no saved address of the projector" }
  Say (T "waiting for Lumen OS over Wi-Fi (the first start takes up to 10 minutes)" "жду Lumen OS по Wi-Fi (первый запуск занимает до 10 минут)")
  if (-not (Wifi-Connect 600)) {
    if ($script:P6St -match "unauthorized") { Die (T "Lumen OS runs, but it did not allow this computer within 10 minutes. On the projector: allow debugging from this computer, tick `"Always allow from this computer`" and press OK; then run the installer again. Nothing was erased; your data is still there." "Lumen OS работает, но проектор за 10 минут не разрешил отладку с этого компьютера. На проекторе разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK, затем запустите установщик снова. Ничего не стёрто, данные на месте.") }
    Die (T "Lumen OS did not answer over Wi-Fi within 10 minutes. Is the A-to-A cable out of the projector? The $nm does not start while it is in. If the picture stays dark: unplug the cable and press the power button; a USB stick in the projector's USB port helps the $nm start. If Lumen Home is on the screen: look up its address in Settings > Network & Internet and run the installer again with -Ip ADDRESS. Nothing else was changed; your data is still there." "Lumen OS не ответила по Wi-Fi за 10 минут. Кабель A–A вынут из проектора? Пока он вставлен, $nm не загружается. Если изображения нет, выньте кабель и нажмите кнопку питания; флешка в USB-порту помогает $nm загрузиться. Если на экране Lumen Home, посмотрите адрес в «Настройки > Сеть и Интернет» и запустите установщик снова с -Ip АДРЕС. Больше ничего не изменено, данные на месте.")
  }
  P6-Identity
  $sw = [Diagnostics.Stopwatch]::StartNew()
  for ($i = 0; (Prop sys.boot_completed) -ne "1"; $i += 5) {
    if ($i -ge 300 -or $sw.Elapsed.TotalSeconds -ge 300) { Die (T "Lumen OS did not finish starting within 5 more minutes. Restart the projector with the power button and run the installer again." "Lumen OS не загрузилась до конца ещё за 5 минут. Перезагрузите проектор кнопкой питания и запустите установщик снова.") }
    Start-Sleep 5
  }
  $lum = Prop (P6-LProp)
  if (-not $lum) {
    P6-Write-Again
    Die (T "the projector still runs XGIMI's system: the write did not take effect. Nothing was erased. Run the installer again with the A-to-A cable at hand: it writes system again. If this happens again, report it with the installer output." "на проекторе всё ещё система XGIMI: запись не применилась. Ничего не стёрто. Запустите установщик снова, держа кабель A–A под рукой: он запишет system ещё раз. Если это повторится, напишите об этом и приложите вывод установщика.")
  }
  if ($lum -cne (P6-LVal)) { Die (T "the projector runs Lumen OS for another device ('$lum'). Nothing was erased." "на проекторе Lumen OS для другого устройства ('$lum'). Ничего не стёрто.") }
  $v = Prop $script:Prof["VERSION_PROP"]
  if (-not $UnsignedImage -and $conf["VERSION"] -and $v -cne $conf["VERSION"]) {
    if ((State-Get dev_lumen) -eq "1" -and $v -ceq (State-Get dev_version)) {
      P6-Write-Again
      Die (T "the projector still runs the Lumen OS $v it had before: the write did not take effect. Nothing was erased. Run the installer again with the A-to-A cable at hand: it writes system again." "на проекторе всё ещё прежняя Lumen OS ${v}: запись не применилась. Ничего не стёрто. Запустите установщик снова, держа кабель A–A под рукой: он запишет system ещё раз.")
    }
    # the image was checked against this release before the write (name, signature, checksum): only its own version
    # name differs, which does not make the wipe of this projector any less right
    Warn (T "Lumen OS '$v' runs, and this installer download is for $($conf['VERSION']): the image names its version differently from its release. Please report it; the install goes on." "работает Lumen OS '$v', а этот установщик для $($conf['VERSION']): образ называет свою версию иначе, чем его релиз. Сообщите об этом; установка продолжается.")
  }
  Ok ((T "Lumen OS $v started" "Lumen OS $v запустилась") + " ($($script:Serial), $($script:AdbT))")
  if (((Adb shell pidof $P6App) | Out-String).Trim()) { Ok (T "projector app running" "приложение проектора работает") } else { Warn (T "the projector app ($P6App) is not running yet" "приложение проектора ($P6App) пока не запущено") }
  State-Set ip $script:AdbT
  State-Set step_boot1 done
}
function P6-Root {  # adb root restarts adbd, so connect again. 0 = root; 1 = this system gives no root (not userdebug);
  # 2 = no answer after 'adb root' (the allow prompt was not accepted, or the network): try again later
  # 3 = Developer options > Rooted debugging is off (LineageOS adbd: "ADB Root access is disabled by system setting")
  if ((Prop ro.debuggable) -ne "1") { return 1 }
  if (((Adb shell id "-u") | Out-String).Trim() -eq "0") { return 0 }
  $t = if ($script:AdbT) { $script:AdbT } else { $script:Serial }
  $out = (& adb -s $t root 2>&1) | Out-String   # both streams: the refusal text decides return 3
  if ($out -match "disabled by system setting") { return 3 }
  Start-Sleep 3
  if (-not (Wifi-Connect 60)) { return 2 }
  if (((Adb shell id "-u") | Out-String).Trim() -eq "0") { return 0 } else { return 1 }
}
function Play6-Wipe {  # Android's own factory reset on the first Lumen OS start; XGIMI's recovery carries it out
  $nm = P6-Name
  if ((State-Get step_boot1) -ne "done") { Die "internal: no factory reset before Lumen OS has started" }
  if ($KeepData) { Die "internal: no factory reset with -KeepData" }
  if (-not $script:AdbT) { $script:AdbT = State-Get ip }
  if (-not (Wifi-Connect 120)) {
    # a factory reset asked for by an earlier run (cut off before it saved that) erases Wi-Fi and turns debugging off
    if ((State-Get wipe_marker) -and $script:P6St -notmatch "unauthorized") { Die (T "the projector does not answer over Wi-Fi at $($script:AdbT). If it shows Lumen OS without your apps, the factory reset of an earlier run has already erased the data, the Wi-Fi network too: join Wi-Fi with the remote (Settings > Network & Internet), turn debugging on (guide, section '$nm', 'Check the install') and run the installer again with -Ip ADDRESS; it then sees that the data is erased. Otherwise: is the projector on, in the same network, debugging on? Nothing was done." "проектор не отвечает по Wi-Fi по адресу $($script:AdbT). Если на нём Lumen OS без ваших приложений, сброс прошлого запуска уже стёр данные и сеть Wi-Fi тоже: подключитесь к Wi-Fi пультом («Настройки > Сеть и Интернет»), включите отладку (инструкция, раздел «${nm}», «Проверьте установку») и запустите установщик снова с -Ip АДРЕС; он увидит, что данные стёрты. Иначе: проектор включён, в той же сети, отладка включена? Ничего не сделано.") }
    Die (P6-NoConn 120)
  }
  P6-Identity
  if ((Prop (P6-LProp)) -cne (P6-LVal)) { Die (T "the projector does not run Lumen OS for the ${nm}: no factory reset is sent. Nothing was changed." "на проекторе не Lumen OS для ${nm}: сброс не отправлен. Ничего не изменено.") }
  # an earlier run asked for the reset and was cut off before it saved that: the check file tells what happened
  if ((State-Get wipe_marker) -and (P6-Marker) -eq "gone") {
    Ok (T "the data is already erased (the check file written before the factory reset is gone): no second factory reset" "данные уже стёрты (проверочного файла, записанного до сброса, нет): второй сброс не нужен")
    State-Set step_wipe done
    return
  }
  if (-not $script:EraseNow) {
    Write-Host ""
    Write-Host (T "Lumen OS runs on the old data. Next, Android's factory reset ERASES ALL DATA on the projector (apps, accounts, settings, files, Wi-Fi networks). XGIMI's recovery carries it out, as for Settings > Reset." "Lumen OS работает на старых данных. Дальше сброс Android к заводским настройкам СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы, сети Wi-Fi). Его выполняет recovery XGIMI, как при сбросе из настроек.")
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was erased" "отменено, ничего не стёрто") }
    $script:EraseNow = $true
  }
  # the proof for 'verify': /data/local/tmp does not survive a real wipe
  $tok = "lumen-$(Get-Date -Format 'yyyyMMddHHmmss')-$PID"
  Adb shell "echo $tok > $P6Marker" | Out-Null
  if (((Adb shell cat $P6Marker) | Out-String).Trim() -cne $tok) { Die (T "could not write the check file $P6Marker on the projector; nothing was erased" "не удалось записать проверочный файл $P6Marker на проектор; ничего не стёрто") }
  State-Set wipe_marker $tok
  $rc = P6-Root
  if ($rc -eq 2) { Die (T "after 'adb root' the projector did not answer over Wi-Fi again within a minute (adbd restarts with it). If the projector asks: allow debugging from this computer, tick `"Always allow from this computer`" and press OK. Then run the installer again. Nothing was erased." "после 'adb root' проектор не ответил по Wi-Fi снова за минуту (adbd при этом перезапускается). Если проектор спрашивает, разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK. Затем запустите установщик снова. Ничего не стёрто.") }
  if ($rc -ne 0) {
    if ($rc -eq 3) { Say (T "(root debugging is off: Settings > Device Preferences > Developer options > Rooted debugging)" "(выключена отладка с правами root: Настройки > Настройки устройства > Для разработчиков > Отладка суперпользователем)") }
    Warn (T "adb root is not possible on this system: the installer cannot start the factory reset" "adb root на этой системе невозможен: установщик не может запустить сброс")
    Say (T "On the projector: Settings > Device Preferences > Reset > Factory reset." "На проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.")
    State-Set step_wipe manual
    return
  }
  Say (T "asking Android for its factory reset" "запускаю сброс Android к заводским настройкам")
  # --user 0: MasterClearReceiver wipes only the sending user when that is not user 0
  $out = ((Adb shell am broadcast "--user" "0" "-a" "android.intent.action.FACTORY_RESET" "-p" "android" "--receiver-foreground" "--es" "android.intent.extra.REASON" "lumen-install") | Out-String).Trim()
  if ($out -notmatch "Broadcast completed") { Die (T "the factory reset was not accepted ($out). Your data is unchanged. On the projector: Settings > Device Preferences > Reset > Factory reset." "сброс не принят ($out). Данные не изменены. На проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.") }
  # the projector restarts into XGIMI's recovery: it stops answering. Asked on a new connection each time, since the
  # computer's old one can still say 'device' for a while after the projector went away.
  $sw = [Diagnostics.Stopwatch]::StartNew()
  for ($i = 0; (P6-Alive); $i += 5) {
    if ($i -ge 60 -or $sw.Elapsed.TotalSeconds -ge 60) { Die (T "the projector still answers a minute after the factory reset was asked for: the factory reset did not start, your data is unchanged. On the projector: Settings > Device Preferences > Reset > Factory reset. To report it, send the logcat lines of MasterClearReceiver and uncrypt." "проектор отвечает и через минуту после запроса сброса: сброс не начался, данные не изменены. На проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам. Для отчёта пришлите строки logcat от MasterClearReceiver и uncrypt.") }
    Start-Sleep 5
  }
  & adb disconnect $script:AdbT 2>&1 | Out-Null
  State-Set step_wipe requested
  $script:WipedNow = $true
  Write-Host ""
  Write-Host (T "The projector restarts into XGIMI's recovery, which erases the data (a few minutes), then starts Lumen OS clean. The Wi-Fi network is erased too." "Проектор перезагружается в recovery XGIMI, которое стирает данные (несколько минут), и затем запускает чистую Lumen OS. Сеть Wi-Fi тоже стирается.")
  if (Ask (T "Wait for Lumen Home on the projector (up to 10 minutes). Do you see Lumen Home?" "Дождитесь Lumen Home на проекторе (до 10 минут). Lumen Home на экране?")) { Ok (T "Lumen OS started clean" "Lumen OS запустилась начисто") }
  else { Warn (T "Press the power button once. If the picture stays dark for 10 more minutes: guide, section '$nm', 'Something went wrong'." "Нажмите кнопку питания один раз. Если ещё 10 минут изображения нет: инструкция, раздел «${nm}», «Если что-то пошло не так».") }
}
function Play6-Verify($secs) {  # over Wi-Fi, after the wipe (the user joined Wi-Fi and turned debugging on again)
  $nm = P6-Name
  if (-not $script:AdbT) { $script:AdbT = State-Get ip }
  if (-not $script:AdbT -and $script:Serial) { $script:AdbT = P6-Discover $script:Serial }   # only this projector, by its serial
  $script:P6St = ""
  if (-not $script:AdbT -or -not (Wifi-Connect $secs)) {
    if ($script:P6St -match "unauthorized") { Warn (T "the projector at $($script:AdbT) did not allow this computer. On the projector: allow debugging from this computer, tick `"Always allow from this computer`" and press OK; then run 'verify' again." "проектор по адресу $($script:AdbT) не разрешил отладку с этого компьютера. На проекторе разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK, затем запустите 'verify' снова.") }
    else { Warn (T "no adb connection to the projector over Wi-Fi. After the wipe Wi-Fi and debugging are off: that is normal. To check the install: join Wi-Fi on the projector, turn on debugging (guide, section '$nm', 'Check the install') and run: lumen-install.cmd verify -Ip ADDRESS" "нет связи adb с проектором по Wi-Fi. После сброса Wi-Fi и отладка выключены: это нормально. Чтобы проверить установку, подключите проектор к Wi-Fi, включите отладку (инструкция, раздел «${nm}», «Проверьте установку») и запустите: lumen-install.cmd verify -Ip АДРЕС") }
    return
  }
  P6-Identity
  if ((Prop sys.boot_completed) -eq "1") { Ok (T "started" "загружена") } else { Warn "sys.boot_completed=$(Prop sys.boot_completed)" }
  if ((Prop (P6-LProp)) -cne (P6-LVal)) { Die (T "the projector does not run Lumen OS for the $nm" "на проекторе не Lumen OS для $nm") }
  $v = Prop $script:Prof["VERSION_PROP"]; $sec = Prop ro.adb.secure
  if (-not $UnsignedImage -and $conf["VERSION"] -and $v -cne $conf["VERSION"]) { Warn "Lumen OS $v ($(T 'expected' 'ожидалась') $($conf['VERSION']))" } else { Ok "Lumen OS $v ($($script:Serial), $($script:AdbT))" }
  if ($sec -eq "1") { Ok (T "debugging asks before a new computer may connect (ro.adb.secure=1)" "отладка спрашивает разрешение для нового компьютера (ro.adb.secure=1)") } else { Warn "ro.adb.secure=$sec ($(T 'a test build' 'тестовая сборка'))" }
  $st = State-Get step_wipe
  if (@("requested", "manual", "done") -contains $st) {
    $mk = P6-Marker
    if ($mk -eq "there") {
      Warn (T "the data was NOT erased: the check file from before the factory reset is still there" "данные НЕ стёрты: проверочный файл, записанный до сброса, на месте")
      if (Ask (T "Erase all data now with Android's factory reset?" "Стереть все данные сейчас сбросом Android к заводским настройкам?")) { $script:EraseNow = $false; Play6-Wipe; return }
    } elseif ($mk -eq "gone") {
      Ok (T "data erased (the check file from before the factory reset is gone)" "данные стёрты (проверочного файла, записанного до сброса, нет)")
      State-Set step_wipe done
    } else { Warn (T "the check file of the factory reset could not be read: run 'verify' again" "проверочный файл сброса прочитать не удалось: запустите 'verify' снова") }
    if (-not ((Adb shell pm path $P6Tuner) | Out-String).Trim()) { Ok (T "SystemUI Tuner from stock is gone" "SystemUI Tuner со стока удалён") } else { Warn (T "SystemUI Tuner from stock is still installed" "SystemUI Tuner со стока всё ещё установлен") }
    $n = @(Adb shell pm list packages "-3" | Where-Object { "$_" -like "package:*" }).Count
    if ($n -eq 0) { Ok (T "no apps from before" "приложений со старой системы нет") } else { Say (T "user apps: $n" "приложений пользователя: $n") }
  } elseif ($st -eq "keep") { Ok (T "your data was kept (-KeepData)" "ваши данные сохранены (-KeepData)") }
  $flags = ""
  foreach ($s in @(@("global", "device_provisioned"), @("secure", "user_setup_complete"), @("secure", "tv_user_setup_complete"))) { $flags += ((Adb shell settings get $s[0] $s[1]) | Out-String).Trim() }
  if ($flags -eq "111") { Ok (T "the projector is marked set up (Home and the remote work)" "проектор отмечен как настроенный (Home и пульт работают)") }
  else { Warn (T "the projector is not marked set up ($flags): Home and the remote may not work; report it" "проектор не отмечен как настроенный ($flags): Home и пульт могут не работать; сообщите об этом") }
  if (((Adb shell pidof $P6App) | Out-String).Trim()) { Ok (T "projector app running" "приложение проектора работает") } else { Warn (T "the projector app ($P6App) is not running" "приложение проектора ($P6App) не запущено") }
  if ((Adb shell dumpsys media.audio_policy) -match "Output") { Ok (T "audio outputs present" "аудиовыходы есть") } else { Warn (T "no audio outputs listed" "аудиовыходы не найдены") }
  State-Set step_verify done
}
function Play6-Install {  # write, first start, wipe: each step once, continued where it stopped
  if ((State-Get step_flash) -ne "done") { Play6-Write }
  if ((State-Get step_boot1) -ne "done") { Play6-Boot1 }
  if (-not (State-Get step_wipe) -and (State-Get gapps_plan) -and (State-Get step_gapps_fill) -ne "done") { P6-Gapps-Fill-Step }
  if (-not (State-Get step_wipe)) { if ($KeepData) { State-Set step_wipe keep } else { Play6-Wipe } }
}
function Play6-Done {
  $nm = P6-Name; $w = State-Get step_wipe
  Write-Host ""
  if ($w -eq "keep") {
    if ((State-Get step_verify) -ne "done") { Play6-Verify 30 }
    Write-Host (T "Done. Lumen OS runs on the $nm with your data kept." "Готово. Lumen OS работает на $nm, ваши данные сохранены.")
  } elseif ($w -eq "manual") {
    Write-Host (T "Lumen OS is written. Finish with the factory reset on the projector (Settings > Device Preferences > Reset > Factory reset), then join your Wi-Fi in Settings > Network & Internet." "Lumen OS записана. Завершите сбросом на проекторе («Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам»), затем подключитесь к Wi-Fi в «Настройки > Сеть и Интернет».")
  } else {
    # a later run: the projector may answer again (Wi-Fi joined, debugging on): then check it
    if (-not $script:WipedNow -and (State-Get step_verify) -ne "done") {
      if (-not $script:AdbT) { $script:AdbT = State-Get ip }
      if ($script:AdbT -and (Wifi-Connect 0)) { Play6-Verify 0; Write-Host "" }
    }
    Write-Host (T "Done. On the projector: Settings > Network & Internet: join your Wi-Fi. To check the install from this computer later: turn on debugging (guide, section '$nm', 'Check the install') and run: lumen-install.cmd verify -Ip ADDRESS" "Готово. На проекторе: «Настройки > Сеть и Интернет», подключитесь к своему Wi-Fi. Проверить установку с компьютера можно позже: включите отладку (инструкция, раздел «${nm}», «Проверьте установку») и запустите: lumen-install.cmd verify -Ip АДРЕС")
    if ((State-Get gapps_plan) -and (State-Get step_gapps_fill) -eq "done") {
      Ok (T "Google services are in: after joining Wi-Fi, sign in with Settings > Accounts > Add account. If Google refuses the sign-in, register the GSF ID (guide, section '$nm', 'Google services')." "сервисы Google добавлены: после подключения к Wi-Fi войдите через Настройки > Аккаунты > Добавить аккаунт. Если Google не пускает, зарегистрируйте GSF ID (инструкция, раздел «${nm}», «Сервисы Google»).")
    }
  }
  Write-Host "  $(T 'Guide' 'Инструкция'): $(P6-Guide)"
}
function Play6-Flash {  # the 'flash' command: a new write, unless a write waits in fastbootd (then it continues there)
  if (-not ((State-Get step_fastboot) -and (State-Get step_flash) -ne "done" -and (P6-In-Fastboot))) {
    # a finished earlier write leaves nothing to continue: its step_fastboot must not count for this new write
    if ((State-Get step_flash) -eq "done") { P6-Write-Again }
  }
  Play6-Install
  Play6-Done
}
function Play6-All {
  if ($GappsZip) { P6-Gapps-Plan }
  if ((State-Get step_check) -ne "done") { Play6-Check }
  if (-not (State-Get step_backup) -and -not (P6-In-Fastboot)) {
    if (Ask (T "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")) { Cmd-Backup } else { State-Set step_backup skipped }
  }
  Play6-Install
  Play6-Done
}
function Play6-Rescue {  # the projector waits in fastboot mode: write system again, keep the data
  $nm = P6-Name
  $script:KeepData = $true
  Check-Tools
  Play6-Check-Image -Force
  Write-Host (T "RESCUE ($nm): writes Lumen OS again into system from fastboot mode, WITHOUT erasing data (apps, accounts, settings and files stay). XGIMI's firmware stays untouched. Use it for a projector with Lumen OS that waits in fastboot mode (it does not start any more, or a write was cut off)." "РЕМОНТ ($nm): заново записывает Lumen OS в system из режима fastboot, БЕЗ стирания данных (приложения, аккаунты, настройки и файлы сохраняются). Прошивка XGIMI не затрагивается. Для проектора с Lumen OS, который ждёт в режиме fastboot (больше не загружается или запись оборвалась).")
  if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  $saved = State-Get serial
  Say (T "Take the USB stick out and connect the projector's USB port to this computer with the A-to-A cable. Waiting for the projector in fastboot mode (up to 15 minutes); the picture may stay dark." "Выньте флешку и соедините USB-порт проектора с компьютером кабелем A–A. Жду проектор в режиме fastboot (до 15 минут); изображения может не быть.")
  if (-not (Fbd-Find 900)) { Die (T "the projector did not appear in fastboot mode within 15 minutes. Cable in the projector's USB port? USB driver? Nothing was written. If it never gets there: guide, 'Back to stock'." "проектор не появился в режиме fastboot за 15 минут. Кабель в USB-порту проектора? Драйвер USB? Ничего не записано. Если он туда не попадает: инструкция, «Откат на сток».") }
  # fastbootd cannot tell stock from Lumen OS here (its system-fingerprint is XGIMI's recovery's own, stock on every
  # Play 6), so the saved progress does: this projector last seen on stock with nothing written to it stays stock
  if ($script:Serial -ceq $saved -and (State-Get dev_lumen) -eq "0" -and -not ((State-Get step_fastboot) + (State-Get step_flash) + (State-Get step_rescue))) {
    P6-Fb-Stop (T "this computer last saw this projector on XGIMI's stock system and has written nothing to it: 'rescue' would put Lumen OS over the stock data. To install, run the installer without a command (guide, section '$nm'). If Lumen OS was put on it another way since: lumen-install.cmd reset -Device play6, then 'rescue' again." "этот компьютер в последний раз видел проектор на родной системе XGIMI и ничего на него не записывал: 'rescue' поставит Lumen OS поверх стоковых данных. Для установки запустите установщик без команды (инструкция, раздел «${nm}»). Если Lumen OS с тех пор поставили на него иначе: lumen-install.cmd reset -Device play6, затем снова 'rescue'.")
  }
  # fastbootd's product name is shared by other XGIMI models: the serial must be this projector's
  if (-not $saved -or $script:Serial -cne $saved) {
    if ((Read-Host (T "In fastboot mode: $($script:Serial). Type this serial to confirm it is your $nm" "В режиме fastboot: $($script:Serial). Введите этот серийный номер, чтобы подтвердить, что это ваш $nm")) -cne $script:Serial) { P6-Fb-Stop (T "cancelled." "отменено.") }
  }
  $gv = Play6-Fbd-Check
  P6-Room $gv
  Say (T "writing system (about 1 minute). Do not unplug the cable or the power until it is done." "записываю system (около минуты). Не отключайте кабель и питание, пока запись не закончится.")
  if (-not (Fb-Do flash system $script:Image)) { Die (T "system was not written completely. The projector stays in fastboot mode: keep the cable in and run 'rescue' again." "system записан не полностью. Проектор остаётся в режиме fastboot: не вынимайте кабель и запустите 'rescue' снова.") }
  State-Set serial $script:Serial
  Fb reboot | Out-Null
  State-Set step_rescue (Get-Date -Format "yyyyMMdd-HHmm")
  P6-Cable-Out
  Ok (T "written; your data is kept. If Lumen OS still does not start, the system was not the cause: guide, 'Back to stock' (it erases everything)." "записано; данные сохранены. Если Lumen OS всё равно не загружается, дело не в системе: инструкция, «Откат на сток» (стирает всё).")
}
function Stick-Check {  # the stock-restore USB stick of this projector, read only (it never formats anything)
  $nm = P6-Name; $bin = $script:Prof["RESTORE_BIN"]; $bad = $false
  if (-not $bin) { Die (T "'stick' checks the restore stick of the $(Dev-Name play6): lumen-install.cmd stick -Device play6 -Path E:\" "'stick' проверяет флешку восстановления $(Dev-Name play6): lumen-install.cmd stick -Device play6 -Path E:\") }
  $m = if ($Path) { $Path } elseif ($script:Image) { $script:Image } else { "" }   # 'stick E:\' binds E:\ to -Image
  if (-not $m) { Die (T "which stick? lumen-install.cmd stick -Path E:\ (the stick's drive)" "какая флешка? lumen-install.cmd stick -Path E:\ (диск флешки)") }
  if (-not (Test-Path -LiteralPath $m -PathType Container)) { Die (T "$m is not a folder" "$m не папка") }
  Say (T "checking the stock restore stick $m (read only)" "проверяю флешку восстановления $m (только чтение)")
  # the partition table and the file system (MBOOT reads only MBR + one FAT32 partition)
  $pt = ""
  try {
    $drv = (Get-Item -LiteralPath $m).PSDrive.Name
    $part = Get-Partition -DriveLetter $drv -ErrorAction Stop
    $pt = "$((Get-Disk -Number $part.DiskNumber -ErrorAction Stop).PartitionStyle)"
    $parts = @(Get-Partition -DiskNumber $part.DiskNumber -ErrorAction Stop).Count
    $fs = "$((Get-Volume -DriveLetter $drv -ErrorAction Stop).FileSystem)"
  } catch { $pt = "" }
  if (-not $pt) { Warn (T "the partition table could not be read on this computer: make sure it is MBR with one FAT32 partition" "схему разделов на этом компьютере прочитать не удалось: убедитесь, что это MBR с одним разделом FAT32") }
  else {
    if ($pt -eq "MBR") { Ok (T "partition table: MBR" "схема разделов: MBR") } else { Warn (T "partition table '$pt': it must be MBR (master boot record), not GPT" "схема разделов '$pt': нужна MBR (главная загрузочная запись), не GPT"); $bad = $true }
    if ($parts -eq 1) { Ok (T "one partition" "один раздел") } else { Warn (T "$parts partitions: there must be exactly one" "разделов: $parts, а нужен ровно один"); $bad = $true }
    if ($fs -eq "FAT32") { Ok (T "file system: FAT32" "файловая система: FAT32") } else { Warn (T "file system '$fs': it must be FAT32" "файловая система '$fs': нужна FAT32"); $bad = $true }
  }
  # exactly the firmware file and an empty factory.txt (folders the computer makes itself do no harm)
  foreach ($e in @(Get-ChildItem -LiteralPath $m -Force -ErrorAction SilentlyContinue)) {
    $n = $e.Name
    if (@($bin, "factory.txt", ".fseventsd", ".Spotlight-V100", ".Trashes", ".TemporaryItems", "System Volume Information") -ccontains $n) { continue }
    if ($n.StartsWith("._")) { Warn (T "${n}: a macOS helper file; delete it" "${n}: служебный файл macOS; удалите его"); $bad = $true }
    else { Warn (T "${n}: nothing else may be on the stick" "${n}: на флешке не должно быть ничего лишнего"); $bad = $true }
  }
  $ft = Join-Path $m "factory.txt"
  if (Test-Path -LiteralPath $ft) {
    if ((Get-Item -LiteralPath $ft).Length -gt 0) { Warn (T "factory.txt must be empty" "factory.txt должен быть пустым"); $bad = $true } else { Ok (T "factory.txt, empty" "factory.txt, пустой") }
  } else { Warn (T "no factory.txt: create an empty file with that name" "нет factory.txt: создайте пустой файл с таким именем"); $bad = $true }
  $bf = Join-Path $m $bin
  if (Test-Path -LiteralPath $bf) {
    Say (T "checking $bin (md5, about a minute)" "проверяю $bin (md5, около минуты)")
    $sum = (Get-FileHash -LiteralPath $bf -Algorithm MD5).Hash.ToLower()
    $ver = ""
    foreach ($e in ($script:Prof["RESTORE_MD5"] -split " ")) { $kv = $e -split ":", 2; if ($kv[0] -eq $sum) { $ver = $kv[1] } }
    if ($ver) { Ok "${bin}: $(T 'XGIMI firmware' 'прошивка XGIMI') $ver (md5 $sum)" }
    else { Warn (T "$bin is not a firmware file the installer knows (md5 $sum): download it again" "$bin не известный установщику файл прошивки (md5 $sum): скачайте его заново"); $bad = $true }
  } else { Warn (T "no $bin on the stick (exactly this name)" "на флешке нет $bin (ровно с таким именем)"); $bad = $true }
  if ($bad) { Die (T "the stick is not ready: fix the lines marked ! (guide, section '$nm', 'Back to stock')" "флешка не готова: исправьте строки с ! (инструкция, раздел «${nm}», «Откат на сток»)") }
  Ok (T "the stick is ready. Restore: power cable out, stick in the projector's USB port, hold the power button, plug the power in and keep holding about 5 seconds (guide)." "флешка готова. Восстановление: отключите питание, вставьте флешку в USB-порт проектора, зажмите кнопку питания, подключите питание и держите кнопку около 5 секунд (инструкция).")
}
# ------------------------------------------------------------------ XGIMI Play 6: gapps and gsf (see lumen-install.sh, same section)
# -Zip FILE (only a MindTheGapps zip whose sha256 is in lib\play6\gapps_allow.txt) goes into the logical partition
# lumen_gapps in super, which the image's lumen_gapps.sh mounts read-only at every start (sys.lumen.gapps). Checks,
# then lib\play6\gapps_fill.sh 'off' (the next starts leave the partition unmounted), fastboot mode over the cable:
# create or resize lumen_gapps (room in super checked first), back in Lumen OS: adb root, the zip pushed,
# gapps_fill.sh formats and fills it on the projector (no e2fsprogs on this computer), then Android's factory reset
# (not with -KeepData). -Remove deletes the partition in fastboot mode, with the same wipe. 'gapps': the state.
$P6Gapps = "lumen_gapps"
function P6-Gapps-Lib($f) { return (Join-Path (Join-Path (Join-Path $Here "lib") "play6") $f) }
function P6-Gapps-Lf($f) {  # a copy of lib\play6\<f> with LF line ends (whatever a Windows checkout made of it); its path
  New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
  $tmp = Join-Path $StateDir "play6-$f"
  [IO.File]::WriteAllText($tmp, ([IO.File]::ReadAllText((P6-Gapps-Lib $f)) -replace "`r", ""))
  return $tmp
}
function P6-Gapps-Line($sha) { Get-Content (P6-Gapps-Lf "gapps_allow.txt") | Where-Object { $_ -like "zip=$sha *" } | Select-Object -First 1 }
function P6-Gapps-Push {  # fill.sh, the allow-list and the sysconfig layer into $GappsDir on the projector
  Adb shell rm -rf $GappsDir | Out-Null
  Adb shell mkdir -p $GappsDir | Out-Null
  foreach ($f in @(@("gapps_fill.sh", "fill.sh"), @("gapps_allow.txt", "allow.txt"), @("lumen-gapps.xml", "lumen-gapps.xml"))) {
    Adb push (P6-Gapps-Lf $f[0]) "$GappsDir/$($f[1])" | Out-Null
    if ($LASTEXITCODE -ne 0) { Die "adb push lib\play6\$($f[0])" }
  }
}
function P6-Lumen-Only {  # connected: Lumen OS for this projector runs (Play6-Device said so), else STOP
  $nm = P6-Name
  if ((State-Get dev_lumen) -ne "1") { Die (T "this projector runs XGIMI's system: install Lumen OS first (guide, section '$nm'). Nothing was written." "на проекторе система XGIMI: сначала установите Lumen OS (инструкция, раздел «${nm}»). Ничего не записано.") }
}
function P6-Root-Or-Die {  # adb root over Wi-Fi, else STOP
  $rc = P6-Root
  if ($rc -eq 2) { Die (T "after 'adb root' the projector did not answer over Wi-Fi again within a minute (adbd restarts with it). If the projector asks: allow debugging from this computer, tick `"Always allow from this computer`" and press OK. Then run the installer again." "после 'adb root' проектор не ответил по Wi-Fi снова за минуту (adbd при этом перезапускается). Если проектор спрашивает, разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK. Затем запустите установщик снова.") }
  if ($rc -eq 3) { Die (T "root debugging is off on the projector. Turn it on: Settings > Device Preferences > Developer options > Rooted debugging. Then run the same command again. Nothing was written." "на проекторе выключена отладка с правами root. Включите: Настройки > Настройки устройства > Для разработчиков > Отладка суперпользователем. Затем запустите ту же команду снова. Ничего не записано.") }
  if ($rc -ne 0) { Die (T "adb root is not possible on this system (Lumen OS is a userdebug build: is it really Lumen OS?)" "adb root на этой системе невозможен (Lumen OS собрана как userdebug: точно ли это Lumen OS?)") }
}
function P6-Wait-Lumen($why) {  # after a restart: Lumen OS of this projector back over Wi-Fi and started (else STOP)
  $nm = P6-Name
  Say (T "waiting for Lumen OS over Wi-Fi (up to 10 minutes)" "жду Lumen OS по Wi-Fi (до 10 минут)")
  if (-not (Wifi-Connect 600)) { Die ((T "Lumen OS did not answer over Wi-Fi within 10 minutes. Is the A-to-A cable out of the projector? Press the power button if the picture is dark. Then run the same command again." "Lumen OS не ответила по Wi-Fi за 10 минут. Кабель A–A вынут из проектора? Если изображения нет, нажмите кнопку питания. Затем запустите ту же команду снова.") + " $why") }
  P6-Identity
  $sw = [Diagnostics.Stopwatch]::StartNew()
  for ($i = 0; (Prop sys.boot_completed) -ne "1"; $i += 5) {
    if ($i -ge 300 -or $sw.Elapsed.TotalSeconds -ge 300) { Die ((T "Lumen OS did not finish starting within 5 more minutes. Restart the projector with the power button and run the same command again." "Lumen OS не загрузилась до конца ещё за 5 минут. Перезагрузите проектор кнопкой питания и запустите ту же команду снова.") + " $why") }
    Start-Sleep 5
  }
  if ((Prop (P6-LProp)) -cne (P6-LVal)) { Die (T "the projector does not run Lumen OS for the $nm" "на проекторе не Lumen OS для $nm") }
}
function P6-To-Fastboot {  # Lumen OS runs: cable in, restart into fastboot mode, check it there; returns 'getvar all'
  Write-Host ""
  Write-Host (T "Take the USB stick out of the projector's USB port, if one is in, and connect that port to this computer with the USB A-to-A cable. Keep the power adapter connected." "Выньте флешку из USB-порта проектора, если она там, и соедините этот порт с компьютером кабелем USB A–A. Блок питания оставьте подключённым.")
  $null = Read-Host (T "Press Enter when the cable is in" "Когда кабель вставлен, нажмите Enter")
  Say (T "restarting into fastboot mode (about 30 seconds)" "перезагрузка в режим fastboot (около 30 секунд)")
  Adb reboot fastboot | Out-Null
  if ($LASTEXITCODE -ne 0 -and (P6-Alive)) { Die (T "adb reboot fastboot failed: the projector still runs its system. Unplug the A-to-A cable and run the same command again." "adb reboot fastboot не удался: проектор работает в своей системе. Выньте кабель A–A и запустите ту же команду снова.") }
  & adb disconnect $script:AdbT 2>&1 | Out-Null
  if (-not (P6-Fb-Wait 180)) { Die (T "the projector did not appear on the cable within 3 minutes. Is the cable in the projector's USB port (not a hub)? On Windows the Google USB Driver is needed. Unplug the cable, press the power button if the picture is dark, and run the same command again." "проектор не появился на кабеле за 3 минуты. Кабель в USB-порту проектора (не через хаб)? На Windows нужен Google USB Driver. Выньте кабель, нажмите кнопку питания, если изображения нет, и запустите ту же команду снова.") }
  return (Play6-Fbd-Check)
}
function P6-Super-Free($gv) {  # bytes of super no logical partition uses (each rounded up to 1 MiB), minus SUPER_RESERVE
  $sup = Gv-Size $gv "super"
  if ($null -eq $sup) { return [int64]0 }
  $used = [int64]0
  $names = @($gv | ForEach-Object { if ("$_" -match "^\(bootloader\) is-logical:([^:]+):yes") { $Matches[1] } } | Sort-Object -Unique)
  foreach ($n in $names) { $sz = Gv-Size $gv $n; if ($null -ne $sz) { $used += [int64][math]::Ceiling($sz / 1MB) * 1MB } }
  return [int64]($sup - $used - [int64]$script:Prof["SUPER_RESERVE"])
}
function P6-Gapps-Wipe {  # the data wipe of the install (Android's factory reset), right after a 'gapps' change
  # Play6-Wipe works on Lumen OS that has started (step_boot1) and asks again unless ERASE was typed; a check file
  # of an earlier wipe must not count for this one
  State-Set step_boot1 done; State-Set wipe_marker ""; State-Set step_wipe ""; State-Set step_verify ""
  $script:EraseNow = $true
  Play6-Wipe
}
function Play6-Gapps-Install {
  $nm = P6-Name
  if (-not (Test-Path -LiteralPath $Zip)) { Die (T "no file $Zip" "нет файла $Zip") }
  $zip = (Resolve-Path -LiteralPath $Zip).Path
  foreach ($f in @("gapps_allow.txt", "gapps_fill.sh", "lumen-gapps.xml")) { if (-not (Test-Path (P6-Gapps-Lib $f))) { Die "internal: lib\play6\$f is missing: re-download the installer of the $nm release" } }
  Check-Tools
  $zname = Split-Path -Leaf $zip
  Say (T "checking $zname" "проверяю $zname")
  $zsha = Sha256 $zip
  $zl = P6-Gapps-Line $zsha
  if (-not $zl) { Die ((T "$zname is not the MindTheGapps file this installer accepts for the $nm (sha256 $($zsha.Substring(0,16))...). Download exactly the file the guide names, section '$nm', 'Google services'" "$zname не тот файл MindTheGapps, который установщик принимает для $nm (sha256 $($zsha.Substring(0,16))...). Скачайте ровно тот файл, что указан в инструкции, раздел «${nm}», «Сервисы Google»") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  $name = ("$zl" -split " ")[1]; $part = [int64](Gapps-Field $zl "part"); $mb = [int64][math]::Floor($part / 1MB)
  if ((Sha256 (P6-Gapps-Lf "lumen-gapps.xml")) -ne (Gapps-Field $zl "sysconfig")) { Die "internal: lib\play6\lumen-gapps.xml is not the allow-listed one: re-download the installer" }
  Ok "$name, sha256 $($zsha.Substring(0,16))..."
  if (-not (Play6-Adb 60)) {
    # an earlier run may have stopped while the projector waited in fastboot mode on the cable
    if (P6-In-Fastboot) { Fb reboot | Out-Null; Die (T "the projector was waiting in fastboot mode (an earlier run stopped there) and restarts now. Unplug the A-to-A cable, wait for Lumen OS and run the same command again." "проектор ждал в режиме fastboot (прошлый запуск остановился там) и сейчас перезагружается. Выньте кабель A–A, дождитесь Lumen OS и запустите ту же команду снова.") }
    Die (P6-NoConn)
  }
  Play6-Device
  P6-Lumen-Only
  $have = Prop sys.lumen.gapps
  if (-not $have) { Die (T "the Lumen OS on the projector has no Google services add-on support: install a newer Lumen OS for the $nm first (guide, section '$nm'). Nothing was written." "в Lumen OS на проекторе нет поддержки дополнения с сервисами Google: сначала поставьте более новую Lumen OS для $nm (инструкция, раздел «${nm}»). Ничего не записано.") }
  if ($have -like "mounted:*") { Ok (T "Google services are already added and on: nothing to do. To put them in again, first: lumen-install.cmd gapps -Remove. Nothing was written." "сервисы Google уже добавлены и включены: делать нечего. Чтобы поставить их заново, сначала: lumen-install.cmd gapps -Remove. Ничего не записано."); return }
  if ($have -eq "none" -and (Prop ro.com.google.gmsversion)) { Die (T "this projector runs Lumen OS with Google services built in: nothing to add" "на проекторе Lumen OS со встроенными сервисами Google: добавлять нечего") }
  P6-Root-Or-Die
  Write-Host ""
  if ($KeepData) {
    Write-Host (T "Adding Google services: the installer creates the partition $P6Gapps ($mb MB) in super in fastboot mode (over the A-to-A cable), restarts the projector a few times and copies the Google apps from $name into it, each one checked. Your data stays. system, vendor, boot, recovery and XGIMI's calibration stay untouched." "Добавляю сервисы Google: установщик в режиме fastboot (через кабель A–A) создаёт раздел $P6Gapps ($mb МБ) в super, несколько раз перезагружает проектор и копирует в этот раздел приложения Google из $name, проверяя каждое. Ваши данные остаются. System, vendor, boot, recovery и калибровка XGIMI не затрагиваются.")
    Say (T "-KeepData: sign in to Google later in Settings > Accounts. Some Google features (the phone remote) may work fully only after a factory reset." "-KeepData: в Google войдите потом через Настройки > Аккаунты. Некоторые функции Google (пульт на телефоне) могут полностью заработать только после сброса к заводским настройкам.")
    if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  } else {
    Write-Host (T "Adding Google services: the installer creates the partition $P6Gapps ($mb MB) in super in fastboot mode (over the A-to-A cable), restarts the projector a few times, copies the Google apps from $name into it, each one checked, and then ERASES ALL DATA on the projector (apps, accounts, settings, files, Wi-Fi networks) with Android's factory reset, as at the install: the first start then sets up Google services properly. system, vendor, boot, recovery and XGIMI's calibration stay untouched." "Добавляю сервисы Google: установщик в режиме fastboot (через кабель A–A) создаёт раздел $P6Gapps ($mb МБ) в super, несколько раз перезагружает проектор, копирует в этот раздел приложения Google из $name, проверяя каждое, и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы, сети Wi-Fi) сбросом Android к заводским настройкам, как при установке: тогда первый запуск правильно настроит сервисы Google. System, vendor, boot, recovery и калибровка XGIMI не затрагиваются.")
    if ((State-Get step_backup) -ne "done" -and (Ask (T "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?"))) { Cmd-Backup }
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  }
  # 1. the fill script's own checks on the projector, then the 'off' file (the next starts leave the partition alone)
  P6-Gapps-Push
  $o = Gapps-Sh pre $zsha (Get-Item -LiteralPath $zip).Length
  if (-not $o.StartsWith("OK pre ")) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "the projector is not ready" "проектор не готов") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  $o = Gapps-Sh off $zsha
  Adb shell rm -rf $GappsDir | Out-Null
  if (-not $o.StartsWith("OK off")) { Die ((T "the projector is not ready" "проектор не готов") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  # 2. fastboot mode: the partition
  $gv = P6-To-Fastboot
  $free = P6-Super-Free $gv; $cur = Gv-Size $gv $P6Gapps; if ($null -eq $cur) { $cur = [int64]0 }
  if (($free + [int64][math]::Ceiling($cur / 1MB) * 1MB) -lt $part) { P6-Fb-Stop (T "not enough free space in super: $([math]::Truncate(($free + $cur) / 1MB)) MB, the Google services need $mb MB." "в super мало места: свободно $([math]::Truncate(($free + $cur) / 1MB)) МБ, сервисам Google нужно $mb МБ.") }
  if ($cur -eq 0) {
    Say (T "creating $P6Gapps ($mb MB)" "создаю $P6Gapps ($mb МБ)")
    if (-not (Fb-Do create $P6Gapps $part)) { P6-Fb-Stop (T "$P6Gapps could not be created." "$P6Gapps создать не удалось.") }
  } elseif ($cur -ne $part) {
    Say (T "resizing $P6Gapps to $mb MB" "меняю размер $P6Gapps на $mb МБ")
    if (-not (Fb-Do resize $P6Gapps $part)) { P6-Fb-Stop (T "$P6Gapps could not be resized." "размер $P6Gapps изменить не удалось.") }
  } else { Ok (T "$P6Gapps is there ($mb MB)" "$P6Gapps уже есть ($mb МБ)") }
  Fb reboot | Out-Null
  P6-Cable-Out
  # 3. filled over Wi-Fi as root
  P6-Wait-Lumen (T "$P6Gapps is still empty, so Lumen OS starts without Google services." "$P6Gapps пока пуст, Lumen OS запускается без сервисов Google.")
  P6-Root-Or-Die
  if ((Prop sys.lumen.gapps) -ne "off") { Die (T "the projector did not leave $P6Gapps unmounted (sys.lumen.gapps=$(Prop sys.lumen.gapps)). Restart it and run the same command again." "проектор не оставил $P6Gapps неподключённым (sys.lumen.gapps=$(Prop sys.lumen.gapps)). Перезагрузите его и запустите ту же команду снова.") }
  P6-Gapps-Push
  Say (T "copying $name to the projector over Wi-Fi (a few minutes)" "копирую $name на проектор по Wi-Fi (несколько минут)")
  Adb push $zip "$GappsDir/gapps.zip" | Out-Null
  if ($LASTEXITCODE -ne 0) { Adb shell rm -rf $GappsDir | Out-Null; Die (T "adb push of the zip failed. Lumen OS starts without Google services. Run the same command again." "adb push архива не удался. Lumen OS запускается без сервисов Google. Запустите ту же команду снова.") }
  Say (T "formatting $P6Gapps and copying the Google apps into it, each one checked (2 to 4 minutes)" "форматирую $P6Gapps и копирую в него приложения Google, проверяя каждое (2-4 минуты)")
  $o = Gapps-Sh fill $zsha
  Adb shell rm -rf $GappsDir | Out-Null
  if (-not $o.StartsWith("OK fill ")) { Die ((T "the Google services were not added" "сервисы Google не добавлены") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Lumen OS starts without them as before. Run the same command again, or 'gapps -Remove'." "Lumen OS запускается без них, как раньше. Запустите ту же команду снова или 'gapps -Remove'.")) }
  Ok "${P6Gapps}: $($o.Substring(8)) $(T 'of' 'из') $name"
  State-Set gapps $name
  # 4. the wipe, so that the first start sets up Google services
  if ($KeepData) {
    Say (T "restarting" "перезагрузка")
    Adb reboot | Out-Null
    for ($i = 0; (P6-Alive) -and $i -lt 60; $i += 5) { Start-Sleep 5 }   # until it goes away
    & adb disconnect $script:AdbT 2>&1 | Out-Null
    P6-Wait-Lumen ""
    $v = Prop sys.lumen.gapps
    if ($v -like "mounted:*") { Ok (T "Google services are on. Sign in: Settings > Accounts. If Google refuses the sign-in, register the GSF ID (lumen-install.cmd gsf)." "сервисы Google включены. Вход: Настройки > Аккаунты. Если Google не пускает, зарегистрируйте GSF ID (lumen-install.cmd gsf).") }
    else { Warn (T "the projector does not use the Google services yet (sys.lumen.gapps=$v); check with 'lumen-install.cmd gapps'" "проектор пока не использует сервисы Google (sys.lumen.gapps=$v); проверьте: 'lumen-install.cmd gapps'") }
  } else {
    P6-Gapps-Wipe
    Ok (T "done. Join Wi-Fi on the projector, then sign in to Google: Settings > Accounts > Add account. If Google refuses the sign-in, register the GSF ID (guide, section '$nm', 'Google services')." "готово. Подключите проектор к Wi-Fi и войдите в Google: Настройки > Аккаунты > Добавить аккаунт. Если Google не пускает, зарегистрируйте GSF ID (инструкция, раздел «${nm}», «Сервисы Google»).")
  }
  State-Set step_gapps done
}
function Play6-Gapps-Remove {
  Check-Tools
  if (-not (Play6-Adb 60)) { Die (P6-NoConn) }
  Play6-Device
  P6-Lumen-Only
  $v = Prop sys.lumen.gapps
  if (-not $v -or $v -eq "none") { Ok (T "this projector has no ${P6Gapps}: nothing to remove. Nothing was written." "на проекторе нет ${P6Gapps}: удалять нечего. Ничего не записано."); State-Set gapps ""; return }
  Write-Host ""
  if ($KeepData) {
    Write-Host (T "Removing the Google services you added: the installer deletes the partition $P6Gapps in fastboot mode (over the A-to-A cable) and restarts. Your data stays, but Google apps updated from Play stay behind as apps that may not work: a factory reset removes them." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot (через кабель A–A) удаляет раздел $P6Gapps и перезагружает проектор. Данные остаются, но обновлённые из Play приложения Google останутся и могут не работать: их уберёт сброс к заводским настройкам.")
    if ((Read-Host (T "Type WRITE to continue" "Введите WRITE, чтобы продолжить")) -cne "WRITE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  } else {
    Write-Host (T "Removing the Google services you added: the installer deletes the partition $P6Gapps in fastboot mode (over the A-to-A cable) and then ERASES ALL DATA on the projector (apps, accounts, settings, files, Wi-Fi networks) with Android's factory reset, as at the install. system, vendor, boot, recovery and XGIMI's calibration stay untouched." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot (через кабель A–A) удаляет раздел $P6Gapps и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы, сети Wi-Fi) сбросом Android к заводским настройкам, как при установке. System, vendor, boot, recovery и калибровка XGIMI не затрагиваются.")
    if ((Read-Host (T "Type ERASE to continue" "Введите ERASE, чтобы продолжить")) -cne "ERASE") { Die (T "cancelled, nothing was written" "отменено, ничего не записано") }
  }
  $gv = P6-To-Fastboot
  $removed = $false
  if ($null -ne (Gv-Size $gv $P6Gapps)) {
    if (-not (Fb-Do delete $P6Gapps)) { P6-Fb-Stop (T "$P6Gapps could not be deleted." "$P6Gapps удалить не удалось.") }
    $removed = $true; Ok (T "$P6Gapps deleted" "$P6Gapps удалён")
  } else { Ok (T "no $P6Gapps in super: nothing to remove" "в super нет ${P6Gapps}: удалять нечего") }
  Fb reboot | Out-Null
  P6-Cable-Out
  State-Set gapps ""
  if ($removed -and -not $KeepData) { P6-Wait-Lumen ""; P6-Gapps-Wipe }
  Ok (T "the projector starts Lumen OS without Google services" "проектор загружает Lumen OS без сервисов Google")
}
function Play6-Gapps-Status {
  $nm = P6-Name
  if (-not (Play6-Adb 60)) { Die (P6-NoConn) }
  Play6-Device
  P6-Lumen-Only
  $v = Prop sys.lumen.gapps
  if ($v -like "mounted:*") { Ok ((T "Google services added by you: on" "добавленные вами сервисы Google: включены") + " ($(Prop ro.com.google.gmsversion))") }
  elseif ($v -eq "none") {
    if (Prop ro.com.google.gmsversion) { Ok (T "this Lumen OS has Google services built in" "в этой Lumen OS сервисы Google встроены") }
    else { Say (T "no Google services added. To add them: lumen-install.cmd gapps -Zip FILE (guide, section '$nm', 'Google services')" "сервисы Google не добавлены. Как добавить: lumen-install.cmd gapps -Zip ФАЙЛ (инструкция, раздел «${nm}», «Сервисы Google»)") }
  }
  elseif ($v -eq "off") { Warn (T "the Google services are off: a 'gapps -Zip' run did not finish (the file /cache/lumen_gapps_off). Run 'gapps -Zip FILE' again, or 'gapps -Remove'." "сервисы Google выключены: запуск 'gapps -Zip' не завершился (файл /cache/lumen_gapps_off). Запустите 'gapps -Zip ФАЙЛ' ещё раз или 'gapps -Remove'.") }
  elseif (-not $v) { Say (T "this Lumen OS has no Google services add-on support" "в этой Lumen OS нет поддержки дополнения с сервисами Google") }
  else { Warn (T "the Google services you added cannot be used (${v}): run 'gapps -Zip FILE' again, or 'gapps -Remove'" "добавленные сервисы Google не могут использоваться (${v}): запустите 'gapps -Zip ФАЙЛ' ещё раз или 'gapps -Remove'") }
}
function Play6-Gapps {
  if ($Zip -and $Remove) { Die "gapps: -Zip or -Remove, not both" }
  if ($Remove) { Play6-Gapps-Remove } elseif ($Zip) { Play6-Gapps-Install } else { Play6-Gapps-Status }
}
function Play6-Gsf {
  $nm = P6-Name
  Check-Tools
  if (-not (Play6-Adb 60)) { Die (P6-NoConn) }
  Play6-Device
  P6-Lumen-Only
  if (-not ((Adb shell pm path com.google.android.gsf) | Out-String).Trim()) {
    Ok (T "this Lumen OS has no Google services: nothing to register" "в этой Lumen OS нет сервисов Google: регистрировать нечего")
    Say (T "To add them: lumen-install.cmd gapps -Zip FILE (guide, section '$nm', 'Google services')" "Как их добавить: lumen-install.cmd gapps -Zip ФАЙЛ (инструкция, раздел «${nm}», «Сервисы Google»)")
    return
  }
  P6-Root-Or-Die
  $id = ((Adb shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db `"select value from main where name='android_id';`"") | Out-String).Trim()
  if (-not $id) { Die (T "no GSF ID yet: connect the projector to the internet, wait two minutes, try again" "GSF ID ещё нет: подключите проектор к интернету, подождите пару минут и повторите") }
  Write-Host "GSF ID: $id"
  Write-Host (T "Register it at https://www.google.com/android/uncertified (signed in with your Google account), wait 10-30 minutes, then sign in on the projector." "Зарегистрируйте его на https://www.google.com/android/uncertified (войдя в свой аккаунт Google), подождите 10–30 минут и войдите на проекторе.")
}
# ------------------------------------------------------------------ XGIMI Play 6: Lumen OS and Google services in one pass (-GappsZip FILE)
# See lumen-install.sh, same section: the zip checked first (P6-Gapps-Plan); the write's fastboot session counts the
# image and lumen_gapps together (P6-Room), writes system, then creates or resizes lumen_gapps (P6-Gapps-Add; a
# failure leaves Google out); the first start on the old data, then P6-Gapps-Fill-Step (adb root with the Rooted
# debugging pause, a lumen_gapps still in use turned off with one restart, gapps_fill.sh pre / zip / fill), then
# Play6-Wipe. State: gapps_plan, step_gapps_part, step_gapps_fill.
$script:P6gSha = ""; $script:P6gName = ""; $script:P6gPart = [int64]0; $script:P6gZip = ""
function P6-Plan-Part {  # lumen_gapps bytes of the saved plan ("" without one)
  $sha = State-Get gapps_plan
  if (-not $sha) { return "" }
  return (Gapps-Field (P6-Gapps-Line $sha) "part")
}
function P6-Gapps-Plan {  # -GappsZip, before anything else: the zip, and that the install saved here can take it
  $nm = P6-Name
  if (-not (Test-Path -LiteralPath $GappsZip)) { Die (T "no file $GappsZip" "нет файла $GappsZip") }
  $script:P6gZip = (Resolve-Path -LiteralPath $GappsZip).Path
  foreach ($f in @("gapps_allow.txt", "gapps_fill.sh", "lumen-gapps.xml")) { if (-not (Test-Path (P6-Gapps-Lib $f))) { Die "internal: lib\play6\$f is missing: re-download the installer of the $nm release" } }
  $zname = Split-Path -Leaf $script:P6gZip
  Say (T "checking $zname" "проверяю $zname")
  $script:P6gSha = Sha256 $script:P6gZip
  $zl = P6-Gapps-Line $script:P6gSha
  if (-not $zl) { Die ((T "$zname is not the MindTheGapps file this installer accepts for the $nm (sha256 $($script:P6gSha.Substring(0,16))...). Download exactly the file the guide names, section '$nm', 'Google services'" "$zname не тот файл MindTheGapps, который установщик принимает для $nm (sha256 $($script:P6gSha.Substring(0,16))...). Скачайте ровно тот файл, что указан в инструкции, раздел «${nm}», «Сервисы Google»") + ". " + (T "Nothing was written." "Ничего не записано.")) }
  $script:P6gName = ("$zl" -split " ")[1]; $script:P6gPart = [int64](Gapps-Field $zl "part")
  if ((Sha256 (P6-Gapps-Lf "lumen-gapps.xml")) -ne (Gapps-Field $zl "sysconfig")) { Die "internal: lib\play6\lumen-gapps.xml is not the allow-listed one: re-download the installer" }
  Ok "$($script:P6gName), sha256 $($script:P6gSha.Substring(0,16))..."
  if ((State-Get step_flash) -ne "done") { return }
  $plan = State-Get gapps_plan
  if (State-Get step_wipe) {
    if ((State-Get step_gapps_fill) -ne "done") { Die (T "Lumen OS was installed here without Google services. To add them: lumen-install.cmd gapps -Zip FILE. For a new install: lumen-install.cmd reset -Device play6 first. Nothing was written." "Lumen OS здесь уже установлена без сервисов Google. Добавить их: lumen-install.cmd gapps -Zip ФАЙЛ. Для новой установки сначала: lumen-install.cmd reset -Device play6. Ничего не записано.") }
  } elseif (-not $plan) {
    Die (T "this install was started without Google services and Lumen OS is written already: finish it without -GappsZip, then add them with 'gapps -Zip FILE'. Nothing was erased." "эта установка начата без сервисов Google, и Lumen OS уже записана: завершите её без -GappsZip, затем добавьте их через 'gapps -Zip ФАЙЛ'. Ничего не стёрто.")
  } elseif ($plan -ne $script:P6gSha) {
    Die (T "this install was started with another MindTheGapps file: use the same file. Nothing was erased." "эта установка начата с другим файлом MindTheGapps: укажите тот же файл. Ничего не стёрто.")
  }
}
function P6-Gapps-Add {  # fastboot mode, system written: lumen_gapps for the saved plan; a failure leaves Google out (warned)
  $nm = P6-Name
  $pt = P6-Plan-Part
  $gv = Fb getvar all   # system has its new size now
  $why = "internal: lib\play6\gapps_allow.txt part="
  if ($pt) {
    $part = [int64]$pt; $mb = [int64][math]::Floor($part / 1MB)
    $free = P6-Super-Free $gv; $cur = Gv-Size $gv $P6Gapps; if ($null -eq $cur) { $cur = [int64]0 }
    if (($free + [int64][math]::Ceiling($cur / 1MB) * 1MB) -lt $part) {
      $why = T "not enough free space in super: $([math]::Truncate(($free + $cur) / 1MB)) MB, the Google services need $mb MB." "в super мало места: свободно $([math]::Truncate(($free + $cur) / 1MB)) МБ, сервисам Google нужно $mb МБ."
    } elseif ($cur -eq 0) {
      Say (T "creating $P6Gapps ($mb MB)" "создаю $P6Gapps ($mb МБ)")
      if (Fb-Do create $P6Gapps $part) { State-Set step_gapps_part done; return }
      $why = T "$P6Gapps could not be created." "$P6Gapps создать не удалось."
    } elseif ($cur -ne $part) {
      Say (T "resizing $P6Gapps to $mb MB" "меняю размер $P6Gapps на $mb МБ")
      if (Fb-Do resize $P6Gapps $part) { State-Set step_gapps_part done; return }
      $why = T "$P6Gapps could not be resized." "размер $P6Gapps изменить не удалось."
    } else { Ok (T "$P6Gapps is there ($mb MB)" "$P6Gapps уже есть ($mb МБ)"); State-Set step_gapps_part done; return }
  }
  Warn ($why + " " + (T "Lumen OS is installed without Google services; add them later: gapps -Zip FILE (guide, section '$nm', 'Google services')." "Lumen OS ставится без сервисов Google; добавить их можно позже: gapps -Zip ФАЙЛ (инструкция, раздел «${nm}», «Сервисы Google»)."))
  State-Set gapps_plan ""
}
function P6-Root-Wait {  # adb root over Wi-Fi; Rooted debugging off: waits for the user; else STOP (the next run continues here)
  while ($true) {
    $rc = P6-Root
    if ($rc -eq 0) { return }
    if ($rc -eq 3) { if (-not (Root-Setting-Wait)) { Root-Setting-Stop }; continue }
    if ($rc -eq 2) { Die (T "after 'adb root' the projector did not answer over Wi-Fi again within a minute (adbd restarts with it). If the projector asks: allow debugging from this computer, tick `"Always allow from this computer`" and press OK. Then run the same command again: it continues here. Nothing was erased." "после 'adb root' проектор не ответил по Wi-Fi снова за минуту (adbd при этом перезапускается). Если проектор спрашивает, разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK. Затем запустите ту же команду снова: установка продолжится с этого места. Ничего не стёрто.") }
    Die (T "adb root is not possible on this system (Lumen OS is a userdebug build: is it really Lumen OS?). Nothing was erased." "adb root на этой системе невозможен (Lumen OS собрана как userdebug: точно ли это Lumen OS?). Ничего не стёрто.")
  }
}
function P6-Gapps-Fill-Step {  # on the first start (old data): lumen_gapps filled as root; Google left out: plan cleared
  $nm = P6-Name
  $again = T "Run the same command again: it continues here. Nothing was erased." "Запустите ту же команду снова: установка продолжится с этого места. Ничего не стёрто."
  if (-not $GappsZip) {
    Say (T "this install was started with Google services (-GappsZip), and they are not in yet" "эта установка начата с сервисами Google (-GappsZip), и они ещё не добавлены")
    if (-not (Ask (T "Finish the install without Google services? (Adding them later with 'gapps' erases the data once more.)" "Завершить установку без сервисов Google? (Если добавить их позже через 'gapps', данные сотрутся ещё раз.)"))) { Die (T "run the same command again with -GappsZip FILE: it continues there. Nothing was erased." "запустите ту же команду снова с -GappsZip ФАЙЛ: установка продолжится с того же места. Ничего не стёрто.") }
    State-Set gapps_plan ""; return
  }
  if (-not $script:AdbT) { $script:AdbT = State-Get ip }
  if (-not (Wifi-Connect 120)) { Die ((P6-NoConn 120) + " " + (T "Nothing was erased." "Ничего не стёрто.")) }
  P6-Identity
  if ((Prop (P6-LProp)) -cne (P6-LVal)) { Die (T "the projector does not run Lumen OS for the $nm. Nothing was erased." "на проекторе не Lumen OS для $nm. Ничего не стёрто.") }
  $v = Prop sys.lumen.gapps
  if (-not $v -or $v -eq "none") {
    Warn (T "this Lumen OS shows no $P6Gapps (sys.lumen.gapps='$v'): the install finishes without Google services; add them later: gapps -Zip FILE" "эта Lumen OS не видит $P6Gapps (sys.lumen.gapps='$v'): установка завершается без сервисов Google; добавить их позже: gapps -Zip ФАЙЛ")
    State-Set gapps_plan ""; return
  }
  P6-Root-Wait
  if ((Prop sys.lumen.gapps) -like "mounted:*") {
    # Google from an earlier install is in use: the 'off' file, one restart, then the partition is free to format
    Say (T "Google services from an earlier install are in use: turning them off for one restart" "сервисы Google от прошлой установки используются: выключаю их на один перезапуск")
    P6-Gapps-Push
    $o = Gapps-Sh off $script:P6gSha
    Adb shell rm -rf $GappsDir | Out-Null
    if (-not $o.StartsWith("OK off")) { Die ((T "the projector is not ready" "проектор не готов") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Nothing was erased." "Ничего не стёрто.")) }
    Adb reboot | Out-Null
    for ($i = 0; (P6-Alive) -and $i -lt 60; $i += 5) { Start-Sleep 5 }   # until it goes away
    & adb disconnect $script:AdbT 2>&1 | Out-Null
    P6-Wait-Lumen (T "Nothing was erased." "Ничего не стёрто.")
    P6-Root-Wait
    if ((Prop sys.lumen.gapps) -ne "off") { Die (T "the projector did not leave $P6Gapps unmounted (sys.lumen.gapps=$(Prop sys.lumen.gapps)). Restart it and run the same command again. Nothing was erased." "проектор не оставил $P6Gapps неподключённым (sys.lumen.gapps=$(Prop sys.lumen.gapps)). Перезагрузите его и запустите ту же команду снова. Ничего не стёрто.") }
  }
  P6-Gapps-Push
  $o = Gapps-Sh pre $script:P6gSha (Get-Item -LiteralPath $script:P6gZip).Length
  if (-not $o.StartsWith("OK pre ")) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "the projector is not ready" "проектор не готов") + ": " + ($o -replace "^FAIL ", "") + ". " + (T "Nothing was erased." "Ничего не стёрто.")) }
  Say (T "copying $($script:P6gName) to the projector over Wi-Fi (a few minutes)" "копирую $($script:P6gName) на проектор по Wi-Fi (несколько минут)")
  Adb push $script:P6gZip "$GappsDir/gapps.zip" | Out-Null
  if ($LASTEXITCODE -ne 0) { Adb shell rm -rf $GappsDir | Out-Null; Die ((T "adb push of the zip failed. " "adb push архива не удался. ") + $again) }
  Say (T "formatting $P6Gapps and copying the Google apps into it, each one checked (2 to 4 minutes)" "форматирую $P6Gapps и копирую в него приложения Google, проверяя каждое (2-4 минуты)")
  $o = Gapps-Sh fill $script:P6gSha
  Adb shell rm -rf $GappsDir | Out-Null
  if (-not $o.StartsWith("OK fill ")) { Die ((T "the Google services were not added" "сервисы Google не добавлены") + ": " + ($o -replace "^FAIL ", "") + ". " + $again) }
  Ok "${P6Gapps}: $($o.Substring(8)) $(T 'of' 'из') $($script:P6gName)"
  State-Set gapps $script:P6gName
  State-Set step_gapps_fill done
}

function Play6-Not-Needed($c) {  # the Z9X commands the Play 6 has no use for
  $nm = P6-Name
  Die (T "'$c' is not needed on the $nm. Nothing was done." "на $nm шаг '$c' не нужен. Ничего не сделано.")
}

function Cmd-All {
  if ($GappsZip) { Z9x-Gapps-Plan }
  if ((State-Get step_check) -ne "done") { Cmd-Check }
  if ($GappsZip -and (State-Get image_gms) -ne "0") { Die (T "this image has Google services built in: -GappsZip is for Lumen OS without Google. Nothing was written." "в этом образе сервисы Google уже встроены: -GappsZip нужен для Lumen OS без Google. Ничего не записано.") }
  if ((State-Get step_backup) -ne "done") { if (Ask (T "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")) { Cmd-Backup } else { State-Set step_backup skipped } }
  if ($Blobs -eq "partition" -and (State-Get step_blobs) -ne "done") { Cmd-Blobs }
  if ((State-Get step_vbmeta) -ne "done") { Cmd-Vbmeta }
  if ((State-Get step_flash) -ne "done") { Cmd-Flash }
  if ((State-Get step_gapps_part) -eq "done" -and -not (State-Get step_wipe)) { Z9x-Gapps-Finish }
  if ((State-Get step_verify) -ne "done") { Cmd-Verify }
  if ((State-Get image_gms) -eq "0" -and (State-Get step_gapps_fill) -ne "done") {
    Write-Host (T "Done. Finish the setup on the projector. Install apps from a USB stick: Lumen Home > Apps > Install from USB. Google services can be added later: 'gapps' (guide)." "Готово. Завершите настройку на проекторе. Приложения ставятся с USB-накопителя: Lumen Home > Приложения > Установить с USB. Сервисы Google можно добавить позже: 'gapps' (инструкция).")
  } else {
    Write-Host (T "Done. Finish the setup on the projector. For Google sign-in register the GSF ID first: lumen-install.cmd gsf" "Готово. Завершите настройку на проекторе. Для входа в Google сначала зарегистрируйте GSF ID: lumen-install.cmd gsf")
  }
}

$script:Image = $Image; $script:Serial = $Serial; $script:SerialFromState = $false
if ($Ip) {
  if ($Ip -notmatch '^[A-Za-z0-9._-]+(:[0-9]+)?$') { Die (T "-Ip: '$Ip' is not an address (for example -Ip 192.168.x.y, as the projector's network settings show it)" "-Ip: '$Ip' не адрес (например -Ip 192.168.x.y, как в настройках сети проектора)") }
  $script:AdbT = if ($Ip -match ":") { $Ip } else { "${Ip}:5555" }
}
if ($Command -eq "status") {   # the saved progress only: no projector, no profile needed
  $shown = $false; $p6s = Join-Path (Join-Path $StateDir "play6") "state"; $f = Join-Path $StateDir "state"
  if ($Device -ne "play6" -and (Test-Path $f)) { Get-Content $f; $shown = $true }
  if ($Device -ne "z9x" -and (Test-Path $p6s)) { Write-Host "$(Dev-Name play6):"; Get-Content $p6s | ForEach-Object { "  $_" }; $shown = $true }
  if (-not $shown) { Write-Host (T "no saved progress" "сохранённого прогресса нет") }
  exit 0
}
if ($Command -eq "reset") {
  if ($Device -eq "play6") { Remove-Item -Recurse -Force (Join-Path $StateDir "play6") -ErrorAction SilentlyContinue }
  elseif ($Device -eq "z9x") { Get-ChildItem -LiteralPath $StateDir -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "play6" } | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue }
  else { Remove-Item -Recurse -Force $StateDir -ErrorAction SilentlyContinue }
  Write-Host (T "progress forgotten" "прогресс сброшен")
  exit 0
}
if ($GappsZip) {   # one pass, one wipe: the full install only
  if ($Command) { Die (T "-GappsZip belongs to the full install (no command). For Lumen OS that is installed already: gapps -Zip FILE. Nothing was done." "-GappsZip относится к полной установке (без команды). Для уже установленной Lumen OS: gapps -Zip ФАЙЛ. Ничего не сделано.") }
  if ($KeepData) { Die (T "-GappsZip installs with one data wipe, not with -KeepData. To keep the data: flash -KeepData, then gapps -KeepData -Zip FILE. Nothing was done." "-GappsZip ставит систему с одним стиранием данных, не с -KeepData. Чтобы сохранить данные: flash -KeepData, затем gapps -KeepData -Zip ФАЙЛ. Ничего не сделано.") }
  if ($Wipe -ne "format") { Die (T "-GappsZip wipes the data itself, not with -Wipe factory-reset. Nothing was done." "-GappsZip стирает данные сам, не с -Wipe factory-reset. Ничего не сделано.") }
}
Resolve-Device
if (-not $script:Serial) { $script:Serial = State-Get serial; if ($script:Serial) { $script:SerialFromState = $true } }
if (@("", "check", "flash", "rescue") -contains $Command) { Release-Guard }
if ($script:Projector -eq "play6") {
  switch ($Command) {
    ""       { Play6-All }
    "check"  { Play6-Check }
    "backup" { Cmd-Backup }
    "flash"  { Play6-Flash }
    "verify" { Play6-Verify 60 }
    "rescue" { Play6-Rescue }
    "stick"  { Stick-Check }
    "blobs"  { Play6-Not-Needed $Command }
    "vbmeta" { Play6-Not-Needed $Command }
    "gsf"    { Play6-Gsf }
    "gapps"  { Play6-Gapps }
    default  { Get-Content $PSCommandPath -TotalCount 20 | Select-Object -Skip 1; exit 2 }  # the usage lines above
  }
  exit 0
}
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
  "stick"  { Stick-Check }
  default  { Get-Content $PSCommandPath -TotalCount 20 | Select-Object -Skip 1; exit 2 }  # the usage lines above
}
