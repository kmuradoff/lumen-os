#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer for the XGIMI Z9X and the XGIMI Play 6 (macOS and Linux; bash 3.2 compatible).
#
#   bash lumen-install.sh [options] [command]
#   bash lumen-install.sh --ip ADDRESS [options] [command]      the XGIMI Play 6 (over Wi-Fi, see below)
#
# Commands, XGIMI Z9X (without one: continue the install from where it stopped):
#   check      read-only: computer, cable, projector, image, signatures. Writes nothing anywhere.
#   backup     copy your user apps (APKs), photos/videos/downloads and setting lists to the computer
#   blobs      public images only: copy the MediaTek video codec and audio files from YOUR projector's
#              own system, check them against blobs_allow.txt, build z9x_blobs.img (flashed by 'flash')
#   vbmeta     explains the one manual step (vbmeta). The installer never runs a vbmeta command.
#   flash      writes Lumen OS: system (+ z9x_blobs), then the full data wipe (format, never erase)
#   verify     after the first boot: checks over USB that everything came up
#   rescue     Lumen OS that does not start any more: it restarts into fastboot mode by itself after
#              3 failed starts in a row; this waits for it there and writes system (+ z9x_blobs) again,
#              WITHOUT wiping data (no adb needed)
#   gsf        shows the Google Services Framework ID for google.com/android/uncertified (only when Google
#              services are there: built in, or added with 'gapps')
#   gapps      Lumen OS without Google: add Google services from YOUR OWN download of MindTheGapps (the
#              guide names the one accepted file): gapps --zip FILE. They go into their own partition
#              z9x_gapps_<slot> (kept by updates and factory resets), then the data is wiped like 'flash'
#              does (--keep-data: not). gapps --remove takes them out again (also from fastboot mode, after
#              failed starts), with the same wipe. 'gapps' alone shows their state
#   status     shows the saved progress;  reset   forgets it (does not touch the projector)
#
# Options:
#   --image FILE        system image (default: the one lumen-os-*-system.img next to this script or in ..;
#                       with several there, e.g. with and without Google, --image is required)
#   --serial SERIAL     the projector's adb serial (default: the only connected device)
#   --keep-data         'flash' writes system only, no wipe (repair of an installed Lumen OS)
#   --wipe=format|factory-reset   how the data wipe is done (default format; see the guide)
#   --lang en|ru        language of the messages (default from $LANG)
#   --yes               do not ask before read-only steps (the wipe is always confirmed)
#   --blobs=partition|embedded   override release.conf (published default: partition, codec and audio
#                       files from your own projector); the owner's private image carries them: --blobs=embedded
#   --no-blobs          public image without those files (skips 'blobs': NO SOUND from the projector and
#                       no protected video, e.g. Kinopoisk HD; only for a first test). Same as
#                       --blobs=none or the environment BLOBS=none
#   --unsigned-image    the owner's own build without SHA256SUMS + SHA256SUMS.sig: the image is NOT
#                       authenticated; asks you to type UNSIGNED (never use it for a downloaded image)
#   --zip FILE          'gapps': the MindTheGapps zip you downloaded;  --remove  'gapps': take them out
#   --ip ADDR[:PORT]    the XGIMI Play 6's Wi-Fi address (port 5555 if not given): it has no USB debugging
#   --device z9x|play6  which projector (default: the one this download is for, or an unfinished install)
#
# XGIMI Z9X, what it writes, ever: system_<slot>, z9x_blobs_<slot> (create/resize/flash), z9x_gapps_<slot>
# ('gapps': create/resize/format, filled over adb as root, delete), and formats userdata, metadata and cache. It may
# delete only *-cow leftovers and XGIMI's unused product_<slot> and system_ext_<slot>. Every other
# partition name is refused by fb_allowed(). 'rescue' may also finish
# ('snapshot-update merge') or cancel ('snapshot-update cancel') an unfinished Lumen OS update of the
# slot it repairs, through fb_snapshot(), and never formats anything. It NEVER touches vbmeta,
# boot, vendor, dtbo, mboot, persist, xgimi*, tvconfig, misc, frp or the other slot, and never runs
# 'fastboot -w' or 'fastboot erase' (proven boot hang on this projector).
#
# XGIMI Play 6 (its model code, firmware versions and image name are in lib/devices.conf; guide:
# docs/install/<language>.md, its own section). The computer talks to stock Android over Wi-Fi (network
# debugging on); fastboot mode is reached over a USB A-to-A cable in the projector's one USB port, plugged
# in only for the write. Commands:
#   (none)     check, backup (asked), flash, then the first start and the wipe; continues where it stopped
#   check      read-only: computer, Wi-Fi connection, projector, firmware, image, signature
#   backup     as for the Z9X, over Wi-Fi
#   flash      writes system in fastboot mode, waits for the first start of Lumen OS on the old data, then
#              asks Android for its own factory reset (as root over adb), which XGIMI's recovery carries
#              out: all data is erased, Wi-Fi too (--keep-data: no reset, a repair)
#   verify     later, after joining Wi-Fi and turning debugging on again: checks the clean install
#   rescue     the projector waits in fastboot mode on the cable: writes system again, keeps the data
#              (Lumen OS only: a projector this computer last saw on stock with nothing written is refused)
#   stick DIR  read-only check of a stock-restore USB stick (MBR, one FAT32 partition, the .bin, an empty
#              factory.txt)
#   gapps      add Google services from YOUR OWN download of MindTheGapps for 32-bit Android 14 TV (the
#              guide names the one accepted file): gapps --zip FILE. They go into their own partition
#              lumen_gapps (kept by updates and factory resets), then the data is wiped by Android's factory
#              reset (--keep-data: not). gapps --remove takes them out again, with the same wipe. 'gapps'
#              alone shows their state
#   gsf        shows the Google Services Framework ID for google.com/android/uncertified
#   status, reset (--device play6 reset forgets only the Play 6's progress)
# What it writes on the Play 6: 'system'; 'gapps' also creates, resizes or deletes the logical partition
# lumen_gapps in super (fb_allowed: nothing else, no format, erase or -w), then formats and fills it on the
# projector (adb root, lib/play6/gapps_fill.sh) and writes /cache/lumen_gapps_off while it does. The data
# wipe is Android's own factory reset (Android leaves the command for XGIMI's recovery, as Settings > Reset
# does). Never vendor, boot, recovery, MBOOT, env, calibration or factory data.
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
STATE_DIR=${LUMEN_STATE_DIR:-$HOME/.lumen-installer}
BACKUP_ROOT=${LUMEN_BACKUP_DIR:-$HOME/Lumen-backup}
MODEL_CODE=G0082
BOARD=mt9952
FB_PRODUCT=mt5877
# v6.15.19 (2025-10-14): same kernel, modules, projector HAL (gmpf) and PM51 firmware as v6.15.58;
# only hwcomposer, libwhitebalance and XGIMI apps differ (z9x-firmware-dump/v61519, 2026-10-08).
# v6.15.62 (2026-03-25, the current firmware of the Z9X 4K flagship variant): same kernel, gmpf, HALs, init,
# SELinux, VINTF, blobs and PM51 firmware as v6.15.58; libxgimi.so the same size, only rebuilt files differ
# (compatibility check of a 4PDA user's dumps, 2026-10-13 / 14).
VENDOR_OK="v6.15.58 v6.15.62 v6.15.19"
GUIDE_EN="https://github.com/kmuradoff/lumen-os/blob/main/docs/install/en.md"
GUIDE_RU="https://github.com/kmuradoff/lumen-os/blob/main/docs/install/ru.md"
GOOGLE_GSI="https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis"
BLOB_SIZE=4194304
SERIAL=""
IMG=""
KEEP_DATA=0
UNSIGNED=0
WIPE=format
YES=0
LANG_UI=en
case "${LANG:-}" in ru*|RU*) LANG_UI=ru ;; esac
BLOBS_ENV=${BLOBS:-}                                    # an explicit environment value wins over release.conf
DEVICE=""
[ -f "$HERE/release.conf" ] && . "$HERE/release.conf"   # BLOBS=partition|embedded, VERSION=..., DEVICE=z9x|play6
BLOBS=${BLOBS_ENV:-${BLOBS:-partition}}
REL_DEVICE=$DEVICE                                      # the projector this download's image is for (empty: z9x)
FORCE_HASH=0
GAPPS_ZIP=""
GAPPS_REMOVE=0
PROJECTOR=z9x         # the projector profile (resolve_device); the Z9X path is the one this script always had
DEVICE_OPT=""
IP=""
IP_GIVEN=0
ADB_T=""              # XGIMI Play 6: adb over Wi-Fi, ADDR:PORT (SERIAL is then the fastboot serial = ro.serialno)
SD=$STATE_DIR         # where the device's progress is kept (the Z9X: $STATE_DIR/state, as always)
IMG_GLOB="lumen-os-*-system.img"
STICK=""
ERASE_NOW=0
WIPED_NOW=0

# ------------------------------------------------------------------ helpers
t() { if [ "$LANG_UI" = ru ]; then printf '%s' "$2"; else printf '%s' "$1"; fi; }
say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*"; }
ok() { say "  ✓ $*"; }
warn() { say "  ! $*"; }
die() { say "$(t 'STOP' 'СТОП'): $*"; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "$(t "missing program $1: install Android SDK Platform-Tools 35 or newer and put it on PATH" "нет программы $1: установите Android SDK Platform-Tools версии 35 или новее и добавьте в PATH") (https://developer.android.com/tools/releases/platform-tools)"; }
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
# identity of the file on disk (size, mtime, inode): a cached check is only valid for exactly that file
filekey() { local m; m=$(stat -f '%z %m %i' "$1" 2>/dev/null) || m=$(stat -c '%s %Y %i' "$1" 2>/dev/null) || m="?"; printf '%s' "$m" | tr ' ' ':'; }
ask() {  # ask "question" -> 0 if yes
  local a
  printf '%s [%s] ' "$1" "$(t 'yes/no' 'да/нет')"
  read -r a
  case "$a" in y|Y|yes|Yes|YES|д|Д|да|Да|ДА) return 0 ;; *) return 1 ;; esac
}
# progress of the projector in use: $SD/state ($SD = $STATE_DIR on the Z9X, $STATE_DIR/play6 on the Play 6)
state_get() { [ -f "$SD/state" ] && sed -n "s/^$1=//p" "$SD/state" | tail -1; }
state_set() {
  mkdir -p "$SD"
  { [ -f "$SD/state" ] && grep -v "^$1=" "$SD/state"; echo "$1=$2"; } > "$SD/state.tmp"
  mv "$SD/state.tmp" "$SD/state"
}
adbs() { adb -s "${ADB_T:-$SERIAL}" "$@"; }   # ADB_T: the Play 6 over Wi-Fi; never set on the Z9X
prop() { adbs shell getprop "$1" 2>/dev/null | tr -d '\r'; }
fb() { fastboot -s "$SERIAL" "$@"; }

fb_allowed() {  # action partition -> 0 if the installer may touch it
  # XGIMI Play 6: system, and the Google services add-on partition lumen_gapps ('gapps': create, resize,
  # delete; it is formatted and filled on the projector). Single slot; never format, erase or another flash.
  if [ "$PROJECTOR" = play6 ]; then
    case "$1:$2" in flash:system|create:lumen_gapps|resize:lumen_gapps|delete:lumen_gapps) return 0 ;; esac
    return 1
  fi
  local s; s=$(state_get slot)
  case "$1:$2" in
    flash:system|flash:"system$s") return 0 ;;
    flash:"z9x_blobs$s"|create:"z9x_blobs$s"|resize:"z9x_blobs$s") return 0 ;;
    create:"z9x_gapps$s"|resize:"z9x_gapps$s"|format:"z9x_gapps$s"|delete:"z9x_gapps$s") return 0 ;;
    delete:*-cow) return 0 ;;
    delete:"product$s"|delete:"system_ext$s") return 0 ;;
    format:userdata|format:metadata|format:cache) return 0 ;;
  esac
  return 1
}
fb_do() {  # action partition [args...]: every partition write goes through here
  local act=$1 part=$2; shift 2
  fb_allowed "$act" "$part" || die "internal: $act $part is not on the allow-list"
  case "$act" in
    flash) fb flash "$part" "$@" ;;
    create) fb create-logical-partition "$part" "$@" ;;
    resize) fb resize-logical-partition "$part" "$@" ;;
    delete) fb delete-logical-partition "$part" ;;
    format)
      if [ "$part" = userdata ]; then fb --fs-options=casefold,projid format:f2fs userdata
      elif [ "$part" = metadata ]; then fb format:f2fs metadata
      else fb format:ext4 "$part"; fi ;;  # cache, z9x_gapps_<slot>
  esac
}

pick_device() {
  local list n
  list=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}')
  if [ -n "$SERIAL" ]; then
    printf '%s\n' "$list" | grep -qx "$SERIAL" && return 0
    [ "$SERIAL_FROM_STATE" = 1 ] || die "$(t "device $SERIAL is not connected over adb" "устройство $SERIAL не подключено по adb")"
    SERIAL=""
  fi
  n=$(printf '%s\n' "$list" | grep -c .)
  if [ "$n" != 1 ]; then
    adb devices
    die "$(t "exactly one projector must be connected over adb, found $n. USB debugging on? Projector restarted after enabling it? A-A cable in the projector's USB 2.0 port? Allowed the 'USB debugging' prompt on the screen?" "нужно ровно одно устройство в adb, найдено: $n. Отладка по USB включена? Проектор перезагружали после её включения? Кабель A–A в порту USB 2.0? Разрешили запрос «Отладка по USB» на экране?")"
  fi
  SERIAL=$list
}

find_image() {
  [ -n "$IMG" ] && { [ -f "$IMG" ] || die "$(t "no image $IMG" "нет образа $IMG")"; image_owner; return; }
  local found f n=0
  for f in "$HERE"/$IMG_GLOB "$HERE"/../$IMG_GLOB; do
    [ -f "$f" ] || continue
    n=$((n + 1)); found=$f
  done
  [ "$n" -gt 0 ] || die "$(t "no $IMG_GLOB found; use --image FILE" "не найден $IMG_GLOB; укажите --image ФАЙЛ")"
  # never pick one of several by name order: the editions (with and without Google) differ
  [ "$n" = 1 ] || die "$(t "several images found (with and without Google?): choose one with --image FILE" "найдено несколько образов (с Google и без?): выберите один через --image ФАЙЛ")"
  IMG=$found
  image_owner
}

# a Z9X never takes an image named for another projector (lumen-os-play6-... matches lumen-os-*-system.img)
image_owner() {
  [ "$PROJECTOR" = z9x ] || return 0
  case "$(basename "$IMG")" in
    *play6*) die "$(t "this image is for the $(dev_name play6), not for the XGIMI Z9X. Nothing was written." "этот образ для $(dev_name play6), а не для XGIMI Z9X. Ничего не записано.")" ;;
  esac
}

# the edition of the image (1.0.1): 0 = Lumen OS without Google (-nogms- in the name, ro.z9x.gms=0), 1 = with
# Google services. With dump.erofs on this computer the image's own product build.prop must agree.
image_edition() {
  local name g p bp
  name=$(basename "$IMG")
  case "$name" in *-nogms-*) g=0 ;; *) g=1 ;; esac
  if command -v dump.erofs >/dev/null 2>&1; then
    bp=$(dump.erofs --cat --path=/system/product/etc/build.prop "$IMG" 2>/dev/null) || bp=""
    p=$(printf '%s\n' "$bp" | sed -n 's/^ro\.z9x\.gms=//p' | tail -1)
    if [ -n "$bp" ] && { { [ "$g" = 0 ] && [ "$p" != 0 ]; } || { [ "$g" = 1 ] && [ "$p" = 0 ]; }; }; then
      die "$(t "$name: the file name and the image disagree about Google services (ro.z9x.gms='$p'): do not install it" "$name: имя файла и сам образ расходятся насчёт сервисов Google (ro.z9x.gms='$p'): не устанавливайте его")"
    fi
  fi
  state_set image_gms "$g"
  if [ "$g" = 0 ]; then ok "$(t "edition: Lumen OS without Google" "редакция: Lumen OS без Google")"
  else ok "$(t "edition: with Google services" "редакция: с сервисами Google")"; fi
}

# keep-data repairs never switch editions: Play and Google services updates in /data would be orphaned and
# Google accounts would survive. dev_gms comes from check_device (0, 1, or stock).
edition_guard() {
  local d i
  d=$(state_get dev_gms); i=$(state_get image_gms)
  case "$d" in
    0|1) [ "$d" = "$i" ] && return 0
         if [ "$d" = 0 ]; then
           die "$(t "this projector runs Lumen OS without Google and the image has Google services: switching editions needs a full install with a data wipe (without --keep-data)" "на проекторе Lumen OS без Google, а образ с сервисами Google: смена редакции требует полной установки с очисткой данных (без --keep-data)")"
         else
           die "$(t "this projector runs Lumen OS with Google services and the image is without Google: switching editions needs a full install with a data wipe (without --keep-data)" "на проекторе Lumen OS с сервисами Google, а образ без Google: смена редакции требует полной установки с очисткой данных (без --keep-data)")"
         fi ;;
    *) [ "$1" = rescue ] && warn "$(t "the edition installed on this projector is unknown here: make sure the image is the same edition (with or without Google)" "редакция на этом проекторе здесь неизвестна: убедитесь, что образ той же редакции (с Google или без)")" ;;
  esac
  return 0
}

# ------------------------------------------------------------------ check
check_tools() {
  need adb; need fastboot
  local v fbdir
  v=$(fastboot --version 2>/dev/null | head -1 | sed -n 's/^fastboot version \([0-9]*\).*/\1/p')
  [ -n "$v" ] && [ "$v" -ge 35 ] 2>/dev/null || die "$(t "fastboot $v is too old: need Platform-Tools 35 or newer" "fastboot $v слишком старый: нужны Platform-Tools 35 или новее")"
  fbdir=$(dirname "$(command -v fastboot)")
  if [ "$PROJECTOR" = z9x ] && [ "$KEEP_DATA" = 0 ] && [ "$WIPE" = format ]; then
    [ -x "$fbdir/make_f2fs" ] && [ -x "$fbdir/mke2fs" ] \
      || die "$(t "make_f2fs/mke2fs missing next to fastboot ($fbdir): use the official Platform-Tools zip" "нет make_f2fs/mke2fs рядом с fastboot ($fbdir): используйте официальный архив Platform-Tools")"
  fi
  ok "adb $(adb version 2>/dev/null | head -1 | awk '{print $NF}'), fastboot $(fastboot --version | head -1 | awk '{print $3}')"
}

check_image() {
  find_image
  local sums="$(dirname "$IMG")/SHA256SUMS" sig name want got cert="$HERE/certs/ota.x509.pem" pub a
  name=$(basename "$IMG")
  sig="$sums.sig"
  # image variant vs BLOBS: a -PRIVATE- image carries the codec files ('embedded'); with dump.erofs on
  # this computer the image itself says it (0-byte placeholder = public)
  case "$name" in *PRIVATE*) [ "$BLOBS" = embedded ] || die "$(t "$name is a PRIVATE image (codec files inside): run with --blobs=embedded" "$name: ЧАСТНЫЙ образ (файлы кодеков внутри): запустите с --blobs=embedded")" ;; esac
  [ "$BLOBS" != none ] || warn "$(t "--no-blobs: no sound and no protected video (e.g. Kinopoisk HD) with this install" "--no-blobs: не будет звука и защищённого видео (например, Кинопоиск HD)")"
  if command -v dump.erofs >/dev/null 2>&1; then
    local inf sz
    inf=$(dump.erofs --path=/system/system_ext/lib64/libc2plugin_store.so "$IMG" 2>/dev/null) || inf=""
    sz=$(printf '%s\n' "$inf" | sed -n 's/^Size: \([0-9]*\).*/\1/p' | head -1)
    if [ -n "$sz" ]; then
      if [ "$sz" -gt 0 ] && [ "$BLOBS" != embedded ]; then
        die "$(t "this image carries the codec files itself (private image): run with --blobs=embedded" "образ сам содержит файлы кодеков (частный образ): запустите с --blobs=embedded")"
      elif [ "$sz" -eq 0 ] && [ "$BLOBS" = embedded ]; then
        die "$(t "this is a public image (codec placeholder): run with --blobs=partition" "это публичный образ (заглушка кодеков): запустите с --blobs=partition")"
      fi
    fi
  fi
  if [ -f "$sums" ] && [ -f "$sig" ] && [ -f "$cert" ] && command -v openssl >/dev/null 2>&1; then
    pub=$(mktemp)
    openssl x509 -in "$cert" -pubkey -noout > "$pub"
    openssl dgst -sha256 -verify "$pub" -signature "$sig" "$sums" >/dev/null 2>&1 \
      || { rm -f "$pub"; die "$(t "SHA256SUMS is not signed by Lumen OS: do not install this image" "SHA256SUMS не подписан Lumen OS: не устанавливайте этот образ")"; }
    rm -f "$pub"
    ok "$(t "SHA256SUMS signature: Lumen OS release key" "подпись SHA256SUMS: ключ Lumen OS")"
  elif [ "$UNSIGNED" = 1 ]; then
    warn "$(t "--unsigned-image: this image is NOT authenticated (no signed SHA256SUMS). Only for your own build." "--unsigned-image: образ НЕ проверен (нет подписанного SHA256SUMS). Только для собственной сборки.")"
    if [ "$(state_get unsigned_ok)" != "$name:$(filekey "$IMG")" ]; then
      printf '%s ' "$(t "Type UNSIGNED to install $name anyway:" "Введите UNSIGNED, чтобы всё равно установить $name:")"
      read -r a
      [ "$a" = UNSIGNED ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
      state_set unsigned_ok "$name:$(filekey "$IMG")"
    fi
    if [ ! -f "$sums" ]; then   # e.g. the owner's build/SHA256SUMS_lumen_v1.txt: the file that lists this image
      local f; sums=""
      for f in "$(dirname "$IMG")"/SHA256SUMS*; do
        case "$f" in *.sig) continue ;; esac
        [ -f "$f" ] && awk -v n="$name" '$2==n || $2=="*"n {f=1} END{exit !f}' "$f" && { sums=$f; break; }
      done
    fi
  else
    [ -f "$sums" ] || die "$(t "no SHA256SUMS next to the image: it cannot be checked. Download SHA256SUMS and SHA256SUMS.sig from the same release." "рядом с образом нет SHA256SUMS: его нельзя проверить. Скачайте SHA256SUMS и SHA256SUMS.sig из того же релиза.")"
    [ -f "$sig" ] || die "$(t "no SHA256SUMS.sig next to the image: its origin cannot be checked" "рядом с образом нет SHA256SUMS.sig: происхождение образа нельзя проверить")"
    [ -f "$cert" ] || die "$(t "installer certificate certs/ota.x509.pem missing: re-download the installer" "нет сертификата установщика certs/ota.x509.pem: скачайте установщик заново")"
    die "$(t "openssl is needed to check the image signature (macOS and Linux have it; install it)" "для проверки подписи образа нужен openssl (есть в macOS и Linux; установите его)")"
  fi
  if [ -n "$sums" ] && [ -f "$sums" ]; then
    want=$(awk -v f="$name" '$2==f || $2=="*"f {print $1}' "$sums")
    [ -n "$want" ] || die "$(t "$name is not listed in $(basename "$sums")" "$name нет в $(basename "$sums")")"
    # the cache only spares a second hash in 'check' for exactly the same file (sha + size + mtime +
    # inode); 'flash' always hashes the file it is about to write (FORCE_HASH)
    if [ "$FORCE_HASH" = 1 ] || [ "$(state_get image_ok)" != "$want:$(filekey "$IMG")" ]; then
      say "$(t "checking the image checksum (about a minute)" "проверяю контрольную сумму образа (около минуты)")"
      got=$(sha256 "$IMG")
      [ "$got" = "$want" ] || { state_set image_ok none; die "$(t "checksum mismatch: the image is damaged or not the right file" "контрольная сумма не совпала: образ повреждён или не тот")"; }
      state_set image_ok "$want:$(filekey "$IMG")"
    fi
    ok "$name sha256 ${want:0:16}…"
  else
    warn "$(t "--unsigned-image without any SHA256SUMS file: checksum not checked" "--unsigned-image без файла SHA256SUMS: контрольная сумма не проверена")"
  fi
  image_edition
}

check_device() {
  pick_device
  local code board vend slot sys free
  code=$(prop ro.boot.xgimi.modelname); board=$(prop ro.boot.hardware)
  vend=$(prop ro.vendor.build.version.incremental); slot=$(prop ro.boot.slot_suffix)
  sys=$(prop ro.z9x.version)
  [ "$code" = "$MODEL_CODE" ] && [ "$board" = "$BOARD" ] \
    || die "$(t "this is not an XGIMI Z9X (model '$code', board '$board'). Nothing was done." "это не XGIMI Z9X (код модели '$code', плата '$board'). Ничего не сделано.")$(other_hint "$code" "$board")"
  case " $VENDOR_OK " in
    *" $vend "*) ;;
    *) die "$(t "XGIMI firmware $vend is not supported (V6.15.58, V6.15.62 or V6.15.19 only). If it is older, let the stock system update itself (Settings, Wi-Fi), then run the installer again. If it is newer, Lumen OS does not support it yet. Nothing was written." "прошивка XGIMI $vend не поддерживается (только V6.15.58, V6.15.62 или V6.15.19). Если она старее, дайте стоку обновиться (настройки, Wi-Fi) и запустите установщик снова. Если новее, Lumen OS её пока не поддерживает. Ничего не записано.")" ;;
  esac
  state_set serial "$SERIAL"; state_set slot "$slot"
  if [ -n "$sys" ]; then local g; g=$(prop ro.z9x.gms); state_set dev_gms "${g:-1}"; else state_set dev_gms stock; fi
  ok "XGIMI Z9X $SERIAL, $(t 'firmware' 'прошивка') $vend, $(t 'slot' 'слот') ${slot#_}, $(t 'system' 'система'): ${sys:+Lumen/Z9X OS }${sys:-$(t 'XGIMI stock' 'родная XGIMI')}"
  free=$(df -k "$HOME" | awk 'NR==2{print $4}')
  [ "${free:-0}" -ge 4194304 ] || warn "$(t "less than 4 GB free on this computer" "на компьютере свободно меньше 4 ГБ")"
}

cmd_check() {
  check_tools
  check_device
  check_image
  check_vbmeta_effective "$(prop ro.boot.slot_suffix)"
  ok "$(t "check passed; nothing was written" "проверка пройдена; ничего не записано")"
  state_set step_check done
}

# ------------------------------------------------------------------ backup
cmd_backup() {
  local pre=""
  if [ "$PROJECTOR" = play6 ]; then play6_adb 60 || die "$(p6_noconn_text)"; pre=play6-; else pick_device; fi
  local d="$BACKUP_ROOT/$pre$(date +%Y%m%d-%H%M)" p path n=0
  mkdir -p "$d/apps" "$d/sdcard" "$d/settings"
  say "$(t "backup to $d" "резервная копия в $d")"
  if [ "$PROJECTOR" = play6 ]; then
    say "$(t "over Wi-Fi this takes about 1 minute per GB" "по Wi-Fi это около минуты на каждый ГБ")"
  elif [ "$(state_get image_gms)" = 0 ]; then
    say "$(t "Recommended: remove your Google account on the projector first (Settings > Accounts). Lumen OS without Google does not ask for it, but a later switch to an image with Google services would (Factory Reset Protection)." "Рекомендуется: сначала удалите аккаунт Google на проекторе (Настройки > Аккаунты). Lumen OS без Google его не спросит, но при переходе позже на образ с сервисами Google его спросит защита от сброса.")"
  else
    say "$(t "IMPORTANT: remove your Google account on the projector first (Settings > Accounts), or Factory Reset Protection may ask for it after the wipe." "ВАЖНО: сначала удалите аккаунт Google на проекторе (Настройки > Аккаунты), иначе после очистки защита от сброса может попросить его.")"
  fi
  for p in $(adbs shell pm list packages -3 2>/dev/null | tr -d '\r' | sed 's/^package://'); do
    for path in $(adbs shell pm path "$p" 2>/dev/null | tr -d '\r' | sed 's/^package://'); do
      mkdir -p "$d/apps/$p"
      adbs pull "$path" "$d/apps/$p/" >/dev/null 2>&1 && n=$((n + 1))
    done
  done
  ok "$(t "$n APK files of your apps (app data cannot be saved without root)" "$n APK ваших приложений (данные приложений без root сохранить нельзя)")"
  for p in Download Movies Music Pictures DCIM Documents; do
    adbs shell "[ -d /sdcard/$p ] && [ -n \"\$(ls -A /sdcard/$p 2>/dev/null)\" ]" 2>/dev/null \
      && adbs pull "/sdcard/$p" "$d/sdcard/" >/dev/null 2>&1 && ok "/sdcard/$p"
  done
  for p in global secure system; do adbs shell settings list "$p" 2>/dev/null | tr -d '\r' > "$d/settings/$p.txt"; done
  adbs shell pm list packages -3 2>/dev/null | tr -d '\r' > "$d/settings/user_apps.txt"
  state_set backup "$d"
  state_set step_backup done
  ok "$(t "backup done" "копия готова")"
}

# ------------------------------------------------------------------ blobs (public images)
# blobs_allow.txt lines: <sha256> <path in the tar> <stock source path> [bind=<path>] [from=<sha256>:<sed>].
# from=: a file the projector has only as the stock original (XGIMI's audio policy) is derived here by
# deleting lines ('N,Md' only), then checked against <sha256> like every other file.
cmd_blobs() {
  if [ "$BLOBS" = none ]; then
    warn "$(t "--no-blobs: codec and audio files skipped (no sound, no protected video)" "--no-blobs: файлы кодеков и звука пропущены (не будет звука и защищённого видео)")"
    state_set step_blobs skip; return 0
  fi
  if [ "$BLOBS" != partition ]; then
    ok "$(t "this image carries its codec files itself: no blobs step needed" "этот образ содержит файлы кодеков сам: шаг blobs не нужен")"
    state_set step_blobs skip; return 0
  fi
  pick_device
  local allow="$HERE/lib/blobs_allow.txt" w="$STATE_DIR/blobs" set h rel src opt o from got n=0 img out
  [ -f "$allow" ] || die "missing $allow"
  rm -rf "$w"; mkdir -p "$w/x"
  set=$(sed -n 's/^set=//p' "$allow")
  printf 'set=%s\n' "$set" > "$w/x/MANIFEST"
  while read -r h rel src opt; do
    case "$h" in ''|\#*|set=*) continue ;; esac
    from=""
    for o in $opt; do case "$o" in from=*) from=${o#from=} ;; esac; done
    case "${from#*:}" in *[!0-9,d\;]*) die "internal: $rel: from= may only delete lines" ;; esac
    mkdir -p "$w/x/$(dirname "$rel")"
    got=""
    for p in "$src" "/system/$rel"; do
      adbs pull "$p" "$w/x/$rel" >/dev/null 2>&1 || continue
      got=$(sha256 "$w/x/$rel")
      [ "$got" = "$h" ] && break
      if [ -n "$from" ] && [ "$got" = "${from%%:*}" ]; then
        sed "${from#*:}" "$w/x/$rel" > "$w/x/$rel.new" && mv "$w/x/$rel.new" "$w/x/$rel"
        got=$(sha256 "$w/x/$rel")
        [ "$got" = "$h" ] && break
      fi
      got="bad"
    done
    [ "$got" = "$h" ] || die "$(t "$rel from the projector does not match the allow-list ($got): this firmware is not supported. Run again with --no-blobs to install without these files (then there is NO SOUND and no protected video)." "$rel с проектора не совпадает со списком ($got): эта прошивка не поддерживается. Чтобы установить без этих файлов, запустите с --no-blobs (тогда НЕ БУДЕТ ЗВУКА и защищённого видео).")"
    printf '%s %s %s\n' "$rel" "$h" "$(wc -c < "$w/x/$rel" | tr -d ' ')" >> "$w/x/MANIFEST"
    n=$((n + 1))
  done < "$allow"
  img="$w/z9x_blobs.img"
  find "$w/x" -exec env TZ=UTC0 touch -t 197001010000 {} +
  if tar --version 2>/dev/null | grep -q bsdtar; then
    (cd "$w/x" && find . -mindepth 1 | sed 's|^\./||' | LC_ALL=C sort | tar --format ustar --uid 0 --gid 0 --uname root --gname root -n -cf "$img" -T -)
  else
    (cd "$w/x" && find . -mindepth 1 | sed 's|^\./||' | LC_ALL=C sort | tar --format=ustar --owner=0 --group=0 --numeric-owner --mtime=@0 --no-recursion -cf "$img" -T -)
  fi
  [ "$(wc -c < "$img")" -le "$BLOB_SIZE" ] || die "blobs tar larger than 4 MiB"
  dd if=/dev/null of="$img" bs=1 seek="$BLOB_SIZE" 2>/dev/null
  [ "$(wc -c < "$img" | tr -d ' ')" = "$BLOB_SIZE" ] || die "cannot pad $img"
  mkdir -p "$BACKUP_ROOT"
  out="$BACKUP_ROOT/blobs-$SERIAL.img"
  cp "$img" "$out"
  state_set blobs_img "$out"
  state_set step_blobs done
  ok "$(t "$n codec and audio files from your projector, set $set -> $out (keep this file: a later repair needs it)" "$n файлов кодеков и звука с вашего проектора, набор $set -> $out (сохраните файл: он нужен для ремонта)")"
}

# ------------------------------------------------------------------ vbmeta (manual)
cmd_vbmeta() {
  local g=$GUIDE_EN
  [ "$LANG_UI" = ru ] && g=$GUIDE_RU
  echo
  t "ONE MANUAL STEP: vbmeta (you do it yourself; this installer never runs any vbmeta command)
  Lumen OS is not signed by XGIMI, so the projector must be told once not to verify the system
  partition. You do this with Google's official GSI instructions, section 'Requirements for flashing
  GSIs': flash Google's unchanged vbmeta.img with verification disabled.
  Do it for BOTH slots (the current one and the other one) in the same fastboot session: then
  future updates over Wi-Fi need nothing more. Restart, let the projector start normally, connect
  the cable again.
" "ОДИН РУЧНОЙ ШАГ: vbmeta (вы делаете его сами; установщик никогда не выполняет команды vbmeta)
  Lumen OS не подписана XGIMI, поэтому проектору один раз нужно разрешить не проверять раздел
  system. Это делается по официальной инструкции Google для GSI, раздел «Requirements for flashing
  GSIs»: прошейте неизменённый vbmeta.img Google с отключённой проверкой (verification disabled).
  Сделайте это для ОБОИХ слотов (текущего и второго) в одной сессии fastboot: тогда будущие
  обновления по Wi-Fi ничего больше не потребуют. Перезагрузите, дайте проектору нормально
  загрузиться, снова подключите кабель.
"
  echo "  Google: $GOOGLE_GSI"
  echo "  $(t 'Guide' 'Инструкция'): $g#vbmeta"
  echo
  if ask "$(t "Have you done the vbmeta step and did the projector start normally after it?" "Вы сделали шаг vbmeta и проектор после него нормально загрузился?")"; then
    state_set step_vbmeta done
  else
    die "$(t "do the vbmeta step first, then run the installer again" "сначала шаг vbmeta, затем запустите установщик снова")"
  fi
}

# Read-only proof that the vbmeta step took effect on the running slot: with verification disabled,
# first-stage init sets up no dm-verity, so /dev/block/mapper has no 'system-verity' device. Stock with
# its own vbmeta shows system-verity, product-verity, vendor-verity... A system written over a slot
# that is still verified fails dm-verity in the first seconds of every boot (no USB, lamp never on).
vbmeta_effective() {  # 0 = verification off on the running slot
  local m
  m=$(adbs shell ls /dev/block/mapper/ 2>/dev/null | tr -d '\r')
  [ -n "$m" ] || return 2
  printf '%s\n' "$m" | grep -qx 'system-verity' && return 1
  return 0
}

check_vbmeta_effective() {
  vbmeta_effective; case $? in
    0) ok "$(t "vbmeta: verification is off on slot ${1#_} (no system-verity)" "vbmeta: проверка в слоте ${1#_} выключена (нет system-verity)")" ;;
    1) state_set step_vbmeta ""
       die "$(t "the running slot ${1#_} still verifies the system partition (system-verity is active): the vbmeta step was not done for this slot or did not take effect. Do it again for both slots, restart, then run the installer again. Nothing was written." "текущий слот ${1#_} всё ещё проверяет раздел system (активен system-verity): шаг vbmeta для этого слота не сделан или не применился. Сделайте его снова для обоих слотов, перезагрузите и запустите установщик ещё раз. Ничего не записано.")" ;;
    *) warn "$(t "could not read /dev/block/mapper; vbmeta state not checked" "не удалось прочитать /dev/block/mapper; состояние vbmeta не проверено")" ;;
  esac
}

# ------------------------------------------------------------------ flash
fb_wait() {  # wait up to 90 s for the projector in fastbootd
  local i
  for i in $(seq 1 45); do
    fastboot devices 2>/dev/null | awk '{print $1}' | grep -qx "$SERIAL" && return 0
    sleep 2
  done
  return 1
}
gv_val() { printf '%s\n' "$GV" | awk -F: -v k="(bootloader) $1" '$1==k {sub(/^[^:]*:/,""); gsub(/[ \r]/,""); print; exit}'; }

cmd_flash() {
  check_tools
  check_device
  FORCE_HASH=1 check_image
  [ "$KEEP_DATA" = 0 ] || edition_guard flash
  [ "$(state_get step_vbmeta)" = done ] || cmd_vbmeta
  check_vbmeta_effective "$(prop ro.boot.slot_suffix)"
  if [ "$BLOBS" = partition ] && [ "$(state_get step_blobs)" != done ]; then cmd_blobs; fi
  local slot S blobs="" p
  slot=$(state_get slot); S=$slot
  [ "$BLOBS" = partition ] && blobs=$(state_get blobs_img)
  echo
  local a
  if [ "$KEEP_DATA" = 1 ]; then
    t "Writing Lumen OS to slot ${S#_} WITHOUT wiping data (repair). On slot ${S#_} this also DELETES
XGIMI's product${S} and system_ext${S} partitions if they still exist (parts of the stock system,
unused by Lumen OS; the stock system on the other slot keeps its own) and any *-cow update leftovers.
Calibration, the other slot, vendor and boot stay untouched." \
      "Записываю Lumen OS в слот ${S#_} БЕЗ очистки данных (ремонт). В слоте ${S#_} также УДАЛЯЮТСЯ
разделы XGIMI product${S} и system_ext${S}, если они ещё есть (части стоковой системы, Lumen OS их
не использует; стоковая система в другом слоте сохраняет свои), и остатки обновлений *-cow.
Калибровка, второй слот, vendor и boot не затрагиваются."; echo
    printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
    read -r a
    [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  else
    t "Writing Lumen OS to slot ${S#_} and then ERASING ALL DATA on the projector (apps, accounts,
settings, files). On slot ${S#_} XGIMI's product${S} and system_ext${S} partitions are DELETED
(parts of the stock system that Lumen OS does not use; the stock system on the other slot keeps its
own), plus any *-cow update leftovers. XGIMI's calibration, the other slot, vendor and boot stay
untouched." \
      "Записываю Lumen OS в слот ${S#_}, затем СТИРАЮ ВСЕ ДАННЫЕ проектора (приложения, аккаунты,
настройки, файлы). В слоте ${S#_} УДАЛЯЮТСЯ разделы XGIMI product${S} и system_ext${S} (части
стоковой системы, Lumen OS их не использует; стоковая система в другом слоте сохраняет свои) и
остатки обновлений *-cow. Калибровка XGIMI, второй слот, vendor и boot не затрагиваются."; echo
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  fi
  try_root && calib_snapshot before
  say "$(t "restarting into fastbootd (about 30 s)" "перезагрузка в fastbootd (около 30 секунд)")"
  adbs reboot fastboot >/dev/null 2>&1 || die "adb reboot fastboot failed"
  fb_wait || die "$(t "the projector did not appear in fastboot within 90 s" "проектор не появился в fastboot за 90 секунд")"
  GV=$(fb getvar all 2>&1)
  [ "$(gv_val is-userspace)" = yes ] || { fb reboot; die "$(t "not fastbootd" "это не fastbootd")"; }
  [ "$(gv_val product)" = "$FB_PRODUCT" ] || { fb reboot; die "$(t "fastbootd is not the Z9X one" "fastbootd не от Z9X") ($(gv_val product))"; }
  [ "$(gv_val unlocked)" = yes ] || { fb reboot; die "$(t "the bootloader is locked" "загрузчик заблокирован")"; }
  [ "$(gv_val current-slot)" = "${S#_}" ] || { fb reboot; die "slot $(gv_val current-slot) != ${S#_}"; }
  if [ "$(gv_val snapshot-update-status)" != none ]; then
    fb reboot
    die "$(t "an XGIMI or Lumen update is still being finished ($(gv_val snapshot-update-status)). Let the projector start, wait 10 minutes, try again." "ещё завершается обновление XGIMI или Lumen ($(gv_val snapshot-update-status)). Дайте проектору загрузиться, подождите 10 минут и повторите.")"
  fi
  for p in $(printf '%s\n' "$GV" | grep -oE 'partition-size:[a-z0-9_]+-cow' | cut -d: -f2 | sort -u); do
    say "$(t "removing update leftover" "удаляю остаток обновления") $p"; fb_do delete "$p" || { fb reboot; die "delete $p"; }
  done
  for p in "product$S" "system_ext$S"; do
    if printf '%s\n' "$GV" | grep -q "partition-size:$p:"; then
      say "$(t "removing unused XGIMI partition" "удаляю неиспользуемый раздел XGIMI") $p"; fb_do delete "$p" || warn "delete $p"
    fi
  done
  if printf '%s\n' "$GV" | grep -q "partition-size:z9x_gapps$S:"; then
    say "$(t "z9x_gapps$S (Google services you added) stays as it is; 'gapps --remove' removes it" "z9x_gapps$S (добавленные вами сервисы Google) остаётся как есть; 'gapps --remove' его удаляет")"
  fi
  say "$(t "writing system (about 40 s)" "записываю system (около 40 секунд)")"
  fb_do flash system "$IMG" || { fb reboot; die "$(t "system was not written; the projector still has its old system" "system не записан; на проекторе осталась старая система")"; }
  state_set step_flash_system done
  if [ -n "$blobs" ]; then
    if printf '%s\n' "$GV" | grep -q "partition-size:z9x_blobs$S:"; then fb_do resize "z9x_blobs$S" "$BLOB_SIZE"
    else fb_do create "z9x_blobs$S" "$BLOB_SIZE"; fi || warn "z9x_blobs$S"
    fb_do flash "z9x_blobs$S" "$blobs" && ok "z9x_blobs$S" || warn "$(t "codec and audio files not written: no sound and no protected video until 'blobs' + 'flash --keep-data'" "файлы кодеков и звука не записаны: не будет звука и защищённого видео до 'blobs' + 'flash --keep-data'")"
  fi
  if [ "$KEEP_DATA" = 0 ]; then
    if [ "$WIPE" = format ]; then
      say "$(t "wiping data (format userdata, metadata, cache)" "очистка данных (format userdata, metadata, cache)")"
      fb_do format userdata || { fb reboot; die "$(t "the wipe failed: the projector starts Lumen OS on the old data; do Settings > Device > Reset instead" "очистка не удалась: проектор загрузит Lumen OS на старых данных; сделайте Настройки > Устройство > Сброс")"; }
      fb_do format metadata || warn "format metadata"
      printf '%s\n' "$GV" | grep -q "partition-type:cache:" && { fb_do format cache || warn "format cache"; }
      state_set step_wipe done
    else
      state_set step_wipe factory-reset
    fi
  fi
  fb reboot >/dev/null 2>&1
  state_set step_flash done
  ok "$(t "written. First start takes 2-4 minutes; the setup screen appears on the projector." "записано. Первый запуск 2–4 минуты; на проекторе появится экран настройки.")"
  if [ "$WIPE" = factory-reset ] && [ "$KEEP_DATA" = 0 ]; then
    say "$(t "Now on the projector: Settings > Device preferences > Reset > Factory reset." "Теперь на проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.")"
  fi
}

# ------------------------------------------------------------------ calibration fingerprint (read-only, root only)
try_root() {  # 0 if adb shell runs as root (Lumen/Z9X userdebug builds; never on XGIMI stock)
  [ "$(prop ro.debuggable)" = 1 ] || return 1
  [ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && return 0
  adbs root >/dev/null 2>&1 || return 1
  sleep 3; adbs wait-for-device
  [ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" = 0 ]
}
CALIB="persist xgimidatabase xgimisps xgimicri project_id tvcertificate"
calib_snapshot() {  # before|after: sha256 of XGIMI calibration partitions (read-only, needs adb root)
  local p f="$STATE_DIR/calib_$1.txt"
  : > "$f"
  for p in $CALIB; do
    adbs shell "[ -e /dev/block/by-name/$p ] && sha256sum /dev/block/by-name/$p" 2>/dev/null | tr -d '\r' >> "$f"
  done
  [ -s "$f" ] && ok "$(t "calibration fingerprint ($1) saved (read-only)" "отпечаток калибровки ($1) сохранён (только чтение)")"
}

# ------------------------------------------------------------------ verify
cmd_verify() {
  local i s
  say "$(t "waiting for the projector to finish starting (up to 5 minutes)" "жду, пока проектор загрузится (до 5 минут)")"
  for i in $(seq 1 150); do
    s=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}' | head -1)
    [ -n "$s" ] && SERIAL=${SERIAL:-$s} && [ "$(prop sys.boot_completed)" = 1 ] && break
    sleep 2
  done
  if [ "$(prop sys.boot_completed)" != 1 ]; then
    warn "$(t "no USB connection to the started system. After a wipe USB debugging is off: that is normal. Check the projector screen: the Lumen OS setup should be there. To verify over USB, enable USB debugging and run 'verify' again." "нет USB-связи с загруженной системой. После очистки отладка по USB выключена: это нормально. Посмотрите на экран: должна быть настройка Lumen OS. Чтобы проверить по USB, включите отладку и запустите 'verify' ещё раз.")"
    return 0
  fi
  local v keys bl gm data
  v=$(prop ro.z9x.version); keys=$(prop ro.z9x.keys); bl=$(prop sys.z9x.blobs); gm=$(prop init.svc.gmpf_main)
  local ng=""; [ "$(prop ro.z9x.gms)" = 0 ] && ng=", $(t "without Google" "без Google")"
  [ -n "$v" ] && ok "Lumen OS $v ($(prop ro.z9x.build_id), $(prop ro.z9x.variant | sed 's/^$/private/')$ng)" || warn "$(t "ro.z9x.version missing" "нет ro.z9x.version")"
  if [ -n "$ng" ] && [ "$(prop sys.z9x.gapps)" = ok ]; then
    ok "$(t "Google services added by you ('gapps'): on" "сервисы Google, добавленные вами ('gapps'): включены")"
  elif [ -n "$ng" ]; then
    local gp
    gp=$(adbs shell pm list packages 2>/dev/null | tr -d '\r' | sed 's/^package://' | grep -E '^(com\.google\.|com\.mtg\.|com\.android\.vending$)' | head -3 | tr '\n' ' ')
    [ -z "$gp" ] && ok "$(t "no Google package" "пакетов Google нет")" || warn "$(t "Google packages on Lumen OS without Google" "пакеты Google на Lumen OS без Google"): $gp"
    case "$(prop sys.z9x.gapps)" in
      off|bad:*) warn "$(t "the Google services add-on is not in use ($(prop sys.z9x.gapps)): bash lumen-install.sh gapps" "дополнение с сервисами Google не используется ($(prop sys.z9x.gapps)): bash lumen-install.sh gapps")" ;;
    esac
  fi
  [ "$keys" = release ] && ok "$(t "release keys" "релизные ключи")" || warn "ro.z9x.keys=$keys"
  [ "$gm" = running ] && ok "$(t "projector service (lamp, fans) running" "служба проектора (лампа, вентиляторы) работает")" || warn "gmpf_main: $gm"
  if [ "$BLOBS" = partition ]; then [ "$bl" = ok ] && ok "$(t "codec and audio files (z9x_blobs)" "файлы кодеков и звука (z9x_blobs)") ok" || warn "sys.z9x.blobs=$bl ($(t "no sound / protected video until 'blobs' + 'flash --keep-data'" "без звука и защищённого видео до 'blobs' + 'flash --keep-data'"))"; fi
  adbs shell dumpsys media.audio_policy 2>/dev/null | grep -q "Output" && ok "$(t "audio outputs present" "аудиовыходы есть")" || warn "$(t "no audio outputs listed" "аудиовыходы не найдены")"
  data=$(adbs shell 'mount | grep " /data "' 2>/dev/null | tr -d '\r')
  case "$data" in *f2fs*) ok "/data f2fs" ;; *) warn "/data: $data" ;; esac
  if [ -f "$STATE_DIR/calib_before.txt" ] && try_root; then
    calib_snapshot after
    # XGIMI's own services write persist, xgimidatabase, xgimisps, xgimicri and tvcertificate at every start
    # (measured 2026-10-10 on a Z9X: all five hashes change across a plain reboot), so a changed hash proves
    # nothing. The guarantee is fb_allowed(): the installer never writes them. The snapshots stay as a record.
    ok "$(t "calibration partitions: never written by the installer (snapshots in $STATE_DIR)" "разделы калибровки: установщик их не записывает (снимки в $STATE_DIR)")"
    adbs unroot >/dev/null 2>&1
  fi
  state_set step_verify done
}

# ------------------------------------------------------------------ gsf
cmd_gsf() {
  pick_device
  [ -n "$(prop ro.z9x.version)" ] || die "$(t "the projector is not running Lumen OS" "на проекторе не Lumen OS")"
  # Lumen OS without Google with the user's add-on ('gapps') has GSF too: the package decides, not ro.z9x.gms
  if [ -z "$(adbs shell pm path com.google.android.gsf 2>/dev/null | tr -d '\r')" ]; then
    ok "$(t "this Lumen OS has no Google services: nothing to register" "в этой Lumen OS нет сервисов Google: регистрировать нечего")"
    [ "$(prop ro.z9x.gms)" = 0 ] && say "$(t "To add them: bash lumen-install.sh gapps --zip FILE (guide, section 'Adding Google services')" "Как их добавить: bash lumen-install.sh gapps --zip ФАЙЛ (инструкция, раздел «Как поставить сервисы Google»)")"
    return 0
  fi
  adbs root >/dev/null 2>&1; sleep 3; adbs wait-for-device
  local id
  id=$(adbs shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db \"select value from main where name='android_id';\"" 2>/dev/null | tr -d '\r')
  adbs unroot >/dev/null 2>&1
  [ -n "$id" ] || die "$(t "no GSF ID yet: connect the projector to the internet, wait two minutes, try again" "GSF ID ещё нет: подключите проектор к интернету, подождите пару минут и повторите")"
  echo "GSF ID: $id"
  echo "$(t "Register it at https://www.google.com/android/uncertified (signed in with your Google account), wait 10-30 minutes, then sign in on the projector." "Зарегистрируйте его на https://www.google.com/android/uncertified (войдя в свой аккаунт Google), подождите 10–30 минут и войдите на проекторе.")"
}

# ------------------------------------------------------------------ rescue (projector already in fastbootd)
# Lumen OS 1.0.1+ restarts into fastbootd by itself after 3 starts in a row that did not complete
# (z9x_rescue.sh in the image). There is no adb: the model is checked by fastbootd's product name, the
# slot is the one fastbootd reports (the one that fails to start). XGIMI's fastbootd leaves by itself
# after about 2 minutes, so everything slow (image hash, the question) happens before the wait.
fbd_find() {  # wait up to $1 s for the projector in fastboot; sets SERIAL (0 = found)
  local i=0 list n
  while [ "$i" -lt "$1" ]; do
    list=$(fastboot devices 2>/dev/null | awk 'NF {print $1}')
    n=$(printf '%s\n' "$list" | grep -c .)
    if [ -n "$SERIAL" ] && printf '%s\n' "$list" | grep -qx "$SERIAL"; then return 0; fi
    if [ "$n" = 1 ] && { [ -z "$SERIAL" ] || [ "$SERIAL_FROM_STATE" = 1 ]; }; then
      [ -n "$SERIAL" ] && warn "$(t "using $list (the saved serial was $SERIAL)" "использую $list (сохранённый серийный номер был $SERIAL)")"
      SERIAL=$list
      return 0
    fi
    [ "$n" -gt 1 ] && die "$(t "several devices in fastboot mode: choose one with --serial" "несколько устройств в режиме fastboot: выберите одно через --serial")"
    [ "$i" -gt 0 ] && [ $((i % 120)) = 0 ] && say "$(t "still waiting for fastboot mode..." "всё ещё жду режима fastboot...")"
    sleep 2; i=$((i + 2))
  done
  return 1
}
fb_snapshot() {  # cancel|merge: only for an unfinished update of the slot 'rescue' repairs
  case "$1" in cancel|merge) fb snapshot-update "$1" ;; *) die "internal: snapshot-update $1" ;; esac
}

cmd_rescue() {
  local a S cs st p blobs=""
  KEEP_DATA=1
  check_tools
  FORCE_HASH=1 check_image
  edition_guard rescue
  echo
  t "RESCUE: writes Lumen OS again into the slot the projector cannot start from, WITHOUT wiping data
(apps, accounts, settings and files stay). If that slot holds an unfinished Lumen OS update, it is
finished or cancelled first (this image replaces its system anyway; a cancelled update may also leave
the other slot unable to start, as the two share that storage until an update is finished); *-cow
update leftovers are removed. Calibration, vendor and boot stay untouched, and so does the other slot
otherwise. Use it only for a projector that runs Lumen OS and no longer starts." \
    "РЕМОНТ: заново записывает Lumen OS в слот, из которого проектор не может загрузиться, БЕЗ
очистки данных (приложения, аккаунты, настройки и файлы сохраняются). Если в этом слоте незавершённое
обновление Lumen OS, оно сначала завершается или отменяется (system всё равно заменяется этим образом;
после отмены второй слот может не загрузиться: до завершения обновления у них общая область); остатки
обновлений *-cow удаляются. Калибровка, vendor и boot не затрагиваются, второй слот в остальных
случаях тоже. Только для проектора с Lumen OS, который больше не загружается."; echo
  printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
  read -r a
  [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  say "$(t "waiting for the projector in fastboot mode (up to 15 minutes). After 3 failed starts in a row Lumen OS goes there by itself; the picture may stay dark. Keep the A-to-A cable in the USB 2.0 port. Fastboot mode ends by itself after about 2 minutes and the projector tries to start again: this command simply waits for its next visit." "жду проектор в режиме fastboot (до 15 минут). После 3 неудачных загрузок подряд Lumen OS переходит туда сама; изображения может не быть. Кабель A–A должен быть в порту USB 2.0. Режим fastboot сам завершается примерно через 2 минуты, и проектор снова пытается загрузиться: команда просто дождётся следующего раза.")"
  fbd_find 900 || die "$(t "the projector did not appear in fastboot mode within 15 minutes. Is it running Lumen OS 1.0.1 or newer? Cable in the USB 2.0 port? Nothing was written." "проектор не появился в режиме fastboot за 15 минут. На нём Lumen OS 1.0.1 или новее? Кабель в порту USB 2.0? Ничего не записано.")"
  GV=$(fb getvar all 2>&1)
  [ "$(gv_val is-userspace)" = yes ] || { fb reboot; die "$(t "not fastbootd" "это не fastbootd")"; }
  [ "$(gv_val product)" = "$FB_PRODUCT" ] || { fb reboot; die "$(t "fastbootd is not the Z9X one" "fastbootd не от Z9X") ($(gv_val product))"; }
  [ "$(gv_val unlocked)" = yes ] || { fb reboot; die "$(t "the bootloader is locked" "загрузчик заблокирован")"; }
  cs=$(gv_val current-slot)
  case "$cs" in a|b) S=_$cs ;; *) fb reboot; die "current-slot '$cs'" ;; esac
  state_set serial "$SERIAL"; state_set slot "$S"
  ok "$(t "fastbootd on $SERIAL, slot ${S#_}" "fastbootd на $SERIAL, слот ${S#_}")"
  for p in "product$S" "system_ext$S"; do
    if printf '%s\n' "$GV" | grep -q "partition-size:$p:"; then
      fb reboot
      die "$(t "slot ${S#_} still has XGIMI's $p partition: it is not an installed Lumen OS. 'rescue' repairs only Lumen OS. Nothing was written." "в слоте ${S#_} ещё есть раздел XGIMI $p: это не установленная Lumen OS. 'rescue' чинит только Lumen OS. Ничего не записано.")"
    fi
  done
  st=$(gv_val snapshot-update-status)
  case "$st" in
    none) ;;
    snapshotted)
      say "$(t "cancelling the unfinished update (the system of slot ${S#_} is replaced now; the other slot shares that storage until an update is finished, so it may no longer start afterwards)" "отменяю незавершённое обновление (system слота ${S#_} сейчас будет заменён; второй слот до завершения обновления использует ту же область, поэтому после этого он может не загрузиться)")"
      fb_snapshot cancel || { fb reboot; die "$(t "the unfinished update could not be cancelled; nothing was written" "незавершённое обновление не удалось отменить; ничего не записано")"; } ;;
    merging)
      say "$(t "finishing the started update merge of slot ${S#_} first (can take a few minutes)" "сначала завершаю начатое слияние обновления слота ${S#_} (может занять несколько минут)")"
      fb_snapshot merge || { fb reboot; die "$(t "the update merge could not be finished; nothing was written" "слияние обновления не удалось завершить; ничего не записано")"; } ;;
    *) fb reboot; die "snapshot-update-status '$st'" ;;
  esac
  if [ "$st" != none ]; then
    GV=$(fb getvar all 2>&1)
    [ "$(gv_val snapshot-update-status)" = none ] || { fb reboot; die "snapshot-update-status '$(gv_val snapshot-update-status)'"; }
  fi
  for p in $(printf '%s\n' "$GV" | grep -oE 'partition-size:[a-z0-9_]+-cow' | cut -d: -f2 | sort -u); do
    say "$(t "removing update leftover" "удаляю остаток обновления") $p"; fb_do delete "$p" || { fb reboot; die "delete $p"; }
  done
  say "$(t "writing system (about 40 s)" "записываю system (около 40 секунд)")"
  fb_do flash system "$IMG" || { fb reboot; die "$(t "system was not written; nothing changed. Run 'rescue' again (the projector comes back to fastboot mode after 3 more failed starts)." "system не записан; ничего не изменилось. Запустите 'rescue' ещё раз (проектор вернётся в режим fastboot после ещё 3 неудачных загрузок).")"; }
  if [ "$BLOBS" = partition ]; then
    blobs=$(state_get blobs_img)
    [ -n "$blobs" ] && [ -f "$blobs" ] || blobs="$BACKUP_ROOT/blobs-$SERIAL.img"
    if [ -f "$blobs" ]; then
      if printf '%s\n' "$GV" | grep -q "partition-size:z9x_blobs$S:"; then fb_do resize "z9x_blobs$S" "$BLOB_SIZE"
      else fb_do create "z9x_blobs$S" "$BLOB_SIZE"; fi || warn "z9x_blobs$S"
      fb_do flash "z9x_blobs$S" "$blobs" && ok "z9x_blobs$S" || warn "$(t "codec and audio files not written: no sound and no protected video until 'blobs' + 'flash --keep-data'" "файлы кодеков и звука не записаны: не будет звука и защищённого видео до 'blobs' + 'flash --keep-data'")"
    elif printf '%s\n' "$GV" | grep -q "partition-size:z9x_blobs$S:"; then
      ok "$(t "z9x_blobs$S kept as it is (no codec image on this computer)" "z9x_blobs$S оставлен как есть (на компьютере нет образа кодеков)")"
    else
      warn "$(t "no z9x_blobs$S and no codec image on this computer: no sound and no protected video until 'blobs' + 'flash --keep-data'" "нет z9x_blobs$S и образа кодеков на компьютере: не будет звука и защищённого видео до 'blobs' + 'flash --keep-data'")"
    fi
  fi
  fb reboot >/dev/null 2>&1
  state_set step_rescue "$(date +%Y%m%d-%H%M)"
  ok "$(t "written. The projector starts now (1-3 minutes, your data is kept). If it ends up in fastboot mode again after 3 more failed starts, the system partition was not the cause: the stock restore at the end of the guide is the way out (it erases everything)." "записано. Проектор сейчас загрузится (1–3 минуты, данные сохранены). Если после ещё 3 неудачных загрузок он снова окажется в режиме fastboot, причина не в разделе system: остаётся восстановление стока в конце инструкции (стирает всё).")"
}

# ------------------------------------------------------------------ gapps (Lumen OS without Google + the user's Google services)
# The user's own download of MindTheGapps (only a zip whose sha256 is in lib/gapps_allow.txt, the same file as
# the image's /system/etc/z9x/gapps_allow.txt) goes into the logical partition z9x_gapps_<slot> (ext4), which
# the image's z9x_gapps.sh checks and overlays at every start. 'gapps --zip FILE':
#   1. checks: the zip (sha256), the projector (Lumen OS without Google, userdebug: adb root), its own
#      allow-list has the zip; lib/gapps_fill.sh, pushed to the projector, checks the rest (unzip, room in
#      /data for the zip). The same zip already on (sys.z9x.gapps=ok): it stops, nothing written (writing it
#      again means one start without the Google apps, and PackageManager then deletes their data);
#   2. fastbootd: room in super (else it stops, nothing written), create or resize z9x_gapps_<slot> to the
#      allow-list's part= size, 'fastboot format:ext4' (fastboot's own mke2fs), restart;
#   3. adb root: the zip is pushed, gapps_fill.sh fills the partition with exactly the allow-listed files
#      (each checked by sha256), writes MANIFEST, unmounts; the image's z9x_gapps.sh 'check' must say ok;
#      the zip is deleted again and a 'turned off after failed starts' marker of z9x_gapps.sh is removed
#      (an empty or half-filled partition has no MANIFEST: the system starts without Google services);
#   4. the data wipe of 'flash' (fastbootd format of userdata, metadata, cache), unless --keep-data or
#      --wipe=factory-reset. Why a wipe: on the old data the Google apps would miss the default permissions
#      and the setup's Google step that only a first start gives them.
# 'gapps --remove': deletes z9x_gapps_<slot> in fastbootd (reached with adb, or waited for like 'rescue'
# when the system no longer starts) and wipes the data the same way (Play and Google services updates in
# /data would stay behind as broken apps). 'gapps': the add-on's state; turns it on again after failed starts.
GAPPS_DIR=/data/local/tmp/lumen-gapps

gapps_line() { awk -v z="zip=$1" '$1 == z' "$HERE/lib/gapps_allow.txt" | head -n 1; }   # zip sha256 -> its zip= line
gapps_field() { printf '%s\n' "$1" | tr ' ' '\n' | sed -n "s/^$2=//p" | head -n 1; }    # zip= line, key -> value
gv_size() { printf '%s\n' "$GV" | sed -n "s/^(bootloader) partition-size:$1: *\(0x[0-9a-fA-F]*\).*/\1/p" | head -n 1; }
super_free() {  # bytes of super that no logical partition uses: each rounded up to 1 MiB, 2 MiB for metadata
  local sup p sz used=0
  sup=$(gv_size super)
  [ -n "$sup" ] || { echo 0; return; }
  for p in $(printf '%s\n' "$GV" | sed -n 's/^(bootloader) is-logical:\([^:]*\):yes.*/\1/p' | sort -u); do
    sz=$(gv_size "$p"); [ -n "$sz" ] || continue
    used=$(( used + (sz + 1048575) / 1048576 * 1048576 ))
  done
  echo $(( sup - used - 2097152 ))
}
fbd_check() {  # [slot]: the fastbootd checks of 'flash' on GV; sets S (the slot fastbootd runs)
  local cs
  GV=$(fb getvar all 2>&1)
  [ "$(gv_val is-userspace)" = yes ] || { fb reboot; die "$(t "not fastbootd" "это не fastbootd")"; }
  [ "$(gv_val product)" = "$FB_PRODUCT" ] || { fb reboot; die "$(t "fastbootd is not the Z9X one" "fastbootd не от Z9X") ($(gv_val product))"; }
  [ "$(gv_val unlocked)" = yes ] || { fb reboot; die "$(t "the bootloader is locked" "загрузчик заблокирован")"; }
  cs=$(gv_val current-slot)
  case "$cs" in a|b) ;; *) fb reboot; die "current-slot '$cs'" ;; esac
  if [ -n "${1-}" ] && [ "$cs" != "${1#_}" ]; then fb reboot; die "slot $cs != ${1#_}"; fi
  S=_$cs
  if [ "$(gv_val snapshot-update-status)" != none ]; then
    fb reboot
    die "$(t "a Lumen OS update is still being finished ($(gv_val snapshot-update-status)). Let the projector start, wait 10 minutes, try again. Nothing was written." "ещё завершается обновление Lumen OS ($(gv_val snapshot-update-status)). Дайте проектору загрузиться, подождите 10 минут и повторите. Ничего не записано.")"
  fi
}
fbd_enter() {  # slot: adb reboot fastboot, wait, check
  say "$(t "restarting into fastbootd (about 30 s)" "перезагрузка в fastbootd (около 30 секунд)")"
  adbs reboot fastboot >/dev/null 2>&1 || die "adb reboot fastboot failed"
  fb_wait || die "$(t "the projector did not appear in fastboot within 90 s" "проектор не появился в fastboot за 90 секунд")"
  fbd_check "$1"
}
fb_wipe() {  # the data wipe of 'flash', in fastbootd
  say "$(t "wiping data (format userdata, metadata, cache)" "очистка данных (format userdata, metadata, cache)")"
  fb_do format userdata || { fb reboot; die "$(t "the wipe failed: on the projector do Settings > Device preferences > Reset > Factory reset" "очистка не удалась: на проекторе сделайте Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам")"; }
  fb_do format metadata || warn "format metadata"
  printf '%s\n' "$GV" | grep -q "partition-type:cache:" && { fb_do format cache || warn "format cache"; }
  state_set step_wipe done
}
boot_wait() {  # up to 5 min for the projector back over adb with sys.boot_completed=1
  local i
  for i in $(seq 1 150); do
    if adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}' | grep -qx "$SERIAL" \
       && [ "$(prop sys.boot_completed)" = 1 ]; then return 0; fi
    sleep 2
  done
  return 1
}
gapps_sh() { adbs shell sh "$GAPPS_DIR/fill.sh" "$@" 2>&1 | tr -d '\r'; }
gapps_push_fill() {
  adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1
  adbs shell mkdir -p "$GAPPS_DIR" >/dev/null 2>&1
  adbs push "$HERE/lib/gapps_fill.sh" "$GAPPS_DIR/fill.sh" >/dev/null 2>&1 || die "adb push lib/gapps_fill.sh"
}

gapps_install() {
  local zip=$GAPPS_ZIP zsha zl name part fbdir slot p o a free cur have
  [ -f "$zip" ] || die "$(t "no file $zip" "нет файла $zip")"
  check_tools
  fbdir=$(dirname "$(command -v fastboot)")
  [ -x "$fbdir/mke2fs" ] || die "$(t "mke2fs missing next to fastboot ($fbdir): use the official Platform-Tools zip" "нет mke2fs рядом с fastboot ($fbdir): используйте официальный архив Platform-Tools")"
  say "$(t "checking $(basename "$zip")" "проверяю $(basename "$zip")")"
  zsha=$(sha256 "$zip")
  zl=$(gapps_line "$zsha")
  [ -n "$zl" ] || die "$(t "$(basename "$zip") is not the MindTheGapps file this installer accepts (sha256 ${zsha:0:16}...). Download exactly the file the guide names, section 'Adding Google services'" "$(basename "$zip") не тот файл MindTheGapps, который принимает установщик (sha256 ${zsha:0:16}...). Скачайте ровно тот файл, что указан в инструкции, раздел «Как поставить сервисы Google»"). $(t 'Nothing was written.' 'Ничего не записано.')"
  name=$(printf '%s\n' "$zl" | cut -d' ' -f2); part=$(gapps_field "$zl" part)
  case "$part" in ''|*[!0-9]*) die "internal: gapps_allow.txt part=" ;; esac
  ok "$name, sha256 ${zsha:0:16}..."
  check_device
  slot=$(state_get slot); p=z9x_gapps$slot
  case "$(state_get dev_gms)" in
    0) ;;
    1) die "$(t "this projector runs Lumen OS with Google services built in: nothing to add" "на проекторе Lumen OS со встроенными сервисами Google: добавлять нечего")" ;;
    *) die "$(t "this projector does not run Lumen OS: install Lumen OS without Google first" "на проекторе не Lumen OS: сначала установите Lumen OS без Google")" ;;
  esac
  # the same add-on, on: writing it again would only cost the Google apps their data (one start without them)
  have=$(prop sys.z9x.gapps)
  if [ "$have" = ok ] && [ "$(prop sys.z9x.gapps.zip)" = "$zsha" ]; then
    ok "$(t "these Google services ($name) are already installed and on: nothing to do. Nothing was written." "эти сервисы Google ($name) уже установлены и включены: делать нечего. Ничего не записано.")"
    return 0
  fi
  try_root || die "$(t "adb root is not possible on this system (Lumen OS is a userdebug build: is it really Lumen OS?)" "adb root на этой системе невозможен (Lumen OS собрана как userdebug: точно ли это Lumen OS?)")"
  if ! adbs shell cat /system/etc/z9x/gapps_allow.txt 2>/dev/null | tr -d '\r' | grep -q "^zip=$zsha "; then
    die "$(t "the Lumen OS on the projector does not accept this file yet: update Lumen OS first (Settings > Device preferences > About > Lumen OS update). Nothing was written." "Lumen OS на проекторе ещё не принимает этот файл: сначала обновите Lumen OS (Настройки > Настройки устройства > Об устройстве > Обновление Lumen OS). Ничего не записано.")"
  fi
  echo
  if [ "$KEEP_DATA" = 1 ] || [ "$WIPE" = factory-reset ]; then
    t "Adding Google services (slot ${slot#_}): the installer creates the partition $p ($((part / 1048576)) MB) in
super, restarts the projector a few times and copies the Google apps from $name into it,
each one checked. Your data stays. system, the other slot, vendor, boot and XGIMI's calibration stay
untouched." "Добавляю сервисы Google (слот ${slot#_}): установщик создаёт раздел $p ($((part / 1048576)) МБ) в super,
несколько раз перезагружает проектор и копирует в этот раздел приложения Google из $name, проверяя
каждое. Ваши данные остаются. System, второй слот, vendor, boot и калибровка XGIMI не затрагиваются."; echo
    if [ "$KEEP_DATA" = 1 ]; then
      say "$(t "--keep-data: sign in to Google later in Settings > Accounts. Some Google features (Chromecast, the phone remote) may work fully only after a factory reset." "--keep-data: в Google войдите потом через Настройки > Аккаунты. Некоторые функции Google (Chromecast, пульт на телефоне) могут полностью заработать только после сброса к заводским настройкам.")"
      case "$have" in ''|none) ;; *)
        say "$(t "Google services are already installed: replacing them signs you out of Google, and the GSF ID must be registered again." "Сервисы Google уже установлены: после замены вы выйдете из аккаунта Google, а GSF ID нужно будет зарегистрировать снова.")" ;;
      esac
    fi
    printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
    read -r a
    [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  else
    t "Adding Google services (slot ${slot#_}): the installer creates the partition $p ($((part / 1048576)) MB) in
super, restarts the projector a few times, copies the Google apps from $name into it, each one
checked, and then ERASES ALL DATA on the projector (apps, accounts, settings, files), as at the install:
the first start then sets up Google services properly. system, the other slot, vendor, boot and
XGIMI's calibration stay untouched." "Добавляю сервисы Google (слот ${slot#_}): установщик создаёт раздел $p ($((part / 1048576)) МБ) в super,
несколько раз перезагружает проектор, копирует в этот раздел приложения Google из $name, проверяя
каждое, и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы), как при
установке: тогда первый запуск правильно настроит сервисы Google. System, второй слот, vendor, boot
и калибровка XGIMI не затрагиваются."; echo
    if [ "$(state_get step_backup)" != done ] && ask "$(t "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")"; then
      cmd_backup
    fi
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  fi
  # 1. the fill script's own checks on the projector (root, edition, its allow-list, unzip, room in /data
  # for the zip) before any partition is touched
  gapps_push_fill
  o=$(gapps_sh pre "$zsha" "$(wc -c < "$zip" | tr -d ' ')")
  adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1
  case "$o" in "OK pre "*) ;; *) die "$(t "the projector is not ready" "проектор не готов"): ${o#FAIL }. $(t 'Nothing was written.' 'Ничего не записано.')" ;; esac
  # 2. fastbootd: the partition
  fbd_enter "$slot"
  free=$(super_free); cur=0
  if printf '%s\n' "$GV" | grep -q "partition-size:$p:"; then cur=$(( $(gv_size "$p") )); fi
  if [ $(( free + cur )) -lt "$part" ]; then
    fb reboot
    die "$(t "not enough free space in super: $(( (free + cur) / 1048576 )) MB, the Google services need $((part / 1048576)) MB. Nothing was written." "в super мало места: свободно $(( (free + cur) / 1048576 )) МБ, сервисам Google нужно $((part / 1048576)) МБ. Ничего не записано.")"
  fi
  say "$(t "creating $p ($((part / 1048576)) MB, $(( (free + cur - part) / 1048576 )) MB of super stay free)" "создаю $p ($((part / 1048576)) МБ, в super останется $(( (free + cur - part) / 1048576 )) МБ)")"
  if [ "$cur" -gt 0 ]; then fb_do resize "$p" "$part"; else fb_do create "$p" "$part"; fi \
    || { fb reboot; die "$(t "$p could not be created; nothing was written" "$p создать не удалось; ничего не записано")"; }
  if ! fb_do format "$p"; then
    fb_do delete "$p" >/dev/null 2>&1
    fb reboot
    die "$(t "$p could not be formatted and was removed again; nothing else changed" "$p не удалось отформатировать, раздел удалён; больше ничего не изменилось")"
  fi
  fb reboot >/dev/null 2>&1
  # 3. filled over adb as root, checked by the image's own script
  say "$(t "waiting for the projector to start (up to 5 minutes)" "жду, пока проектор загрузится (до 5 минут)")"
  boot_wait || die "$(t "the projector did not come back over USB within 5 minutes. $p is still empty, so Lumen OS starts without Google services. Run 'gapps --zip' again." "проектор не вернулся по USB за 5 минут. $p пока пуст, Lumen OS запускается без сервисов Google. Запустите 'gapps --zip' ещё раз.")"
  try_root || die "adb root"
  gapps_push_fill
  say "$(t "copying $name to the projector (about a minute)" "копирую $name на проектор (около минуты)")"
  adbs push "$zip" "$GAPPS_DIR/gapps.zip" >/dev/null 2>&1 \
    || { adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1; die "$(t "adb push of the zip failed. $p is still empty, so Lumen OS starts without Google services. Run 'gapps --zip' again." "adb push архива не удался. $p пока пуст, Lumen OS запускается без сервисов Google. Запустите 'gapps --zip' ещё раз.")"; }
  say "$(t "copying the Google apps into $p and checking each one (1 to 2 minutes)" "копирую приложения Google в $p и проверяю каждое (1–2 минуты)")"
  o=$(gapps_sh fill "$zsha")
  case "$o" in
    "OK fill "*) ;;
    *) adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1
       die "$(t "the Google services were not added" "сервисы Google не добавлены"): ${o#FAIL }. $(t "Lumen OS starts without them as before. Run 'gapps --zip' again, or 'gapps --remove'." "Lumen OS запускается без них, как раньше. Запустите 'gapps --zip' ещё раз или 'gapps --remove'.")" ;;
  esac
  o=$(adbs shell sh /system/etc/z9x/z9x_gapps.sh check 2>&1 | tr -d '\r')
  adbs shell rm -rf "$GAPPS_DIR" /metadata/z9x_gapps >/dev/null 2>&1
  case "$o" in
    "ok: "*) ok "$p: ${o#ok: }" ;;
    *) die "$(t "the projector's own check of $p failed" "собственная проверка $p на проекторе не прошла"): $o. $(t "Lumen OS starts without Google services. Run 'gapps --zip' again, or 'gapps --remove'." "Lumen OS запускается без сервисов Google. Запустите 'gapps --zip' ещё раз или 'gapps --remove'.")" ;;
  esac
  state_set gapps "$name"
  # 4. the wipe, so that the first start sets up Google services
  if [ "$KEEP_DATA" = 1 ]; then
    adbs reboot >/dev/null 2>&1
    say "$(t "restarting" "перезагрузка")"
    if boot_wait && [ "$(prop sys.z9x.gapps)" = ok ]; then
      ok "$(t "Google services are on. Sign in: Settings > Accounts. If Google refuses the sign-in, register the GSF ID (bash lumen-install.sh gsf)." "сервисы Google включены. Вход: Настройки > Аккаунты. Если Google не пускает, зарегистрируйте GSF ID (bash lumen-install.sh gsf).")"
    else
      warn "$(t "the projector did not report the Google services as on yet (sys.z9x.gapps=$(prop sys.z9x.gapps)); check later with 'bash lumen-install.sh gapps'" "проектор пока не сообщил, что сервисы Google включены (sys.z9x.gapps=$(prop sys.z9x.gapps)); проверьте позже: 'bash lumen-install.sh gapps'")"
    fi
  elif [ "$WIPE" = factory-reset ]; then
    adbs reboot >/dev/null 2>&1
    state_set step_wipe factory-reset
    say "$(t "Now on the projector: Settings > Device preferences > Reset > Factory reset. The first start after it sets up Google services." "Теперь на проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам. Первый запуск после него настроит сервисы Google.")"
  else
    fbd_enter "$slot"
    fb_wipe
    fb reboot >/dev/null 2>&1
    ok "$(t "done. The first start takes 2-4 minutes; the setup now has a Google sign-in step. If Google refuses the sign-in, register the GSF ID (guide, section 'Adding Google services')." "готово. Первый запуск 2–4 минуты; в настройке теперь есть шаг входа в Google. Если Google не пускает, зарегистрируйте GSF ID (инструкция, раздел «Как поставить сервисы Google»).")"
  fi
  state_set step_gapps done
}

gapps_remove() {
  local a via removed=0 p
  check_tools
  if adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then via=adb; check_device; else via=fastboot; fi
  echo
  if [ "$KEEP_DATA" = 1 ] || [ "$WIPE" = factory-reset ]; then
    t "Removing the Google services you added: the installer deletes the partition z9x_gapps of the
current slot in fastboot mode and restarts. Your data stays, but Google apps updated from Play stay
behind as apps that may not work: a factory reset removes them." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot удаляет раздел z9x_gapps
текущего слота и перезагружает проектор. Данные остаются, но обновлённые из Play приложения Google
останутся и могут не работать: их уберёт сброс к заводским настройкам."; echo
    printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
    read -r a
    [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  else
    t "Removing the Google services you added: the installer deletes the partition z9x_gapps of the
current slot in fastboot mode and then ERASES ALL DATA on the projector (apps, accounts, settings,
files), as at the install. system, vendor, boot and XGIMI's calibration stay untouched." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot удаляет раздел z9x_gapps
текущего слота и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы), как
при установке. System, vendor, boot и калибровка XGIMI не затрагиваются."; echo
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  fi
  if [ "$via" = adb ]; then
    fbd_enter "$(state_get slot)"
  else
    say "$(t "no projector over adb: waiting for it in fastboot mode (up to 15 minutes; after 3 failed starts in a row Lumen OS goes there by itself)" "проектора нет в adb: жду его в режиме fastboot (до 15 минут; после 3 неудачных загрузок подряд Lumen OS переходит туда сама)")"
    fbd_find 900 || die "$(t "the projector did not appear in fastboot mode within 15 minutes; nothing was written" "проектор не появился в режиме fastboot за 15 минут; ничего не записано")"
    fbd_check ""
    state_set serial "$SERIAL"; state_set slot "$S"
  fi
  p=z9x_gapps$S
  if printf '%s\n' "$GV" | grep -q "partition-size:$p:"; then
    fb_do delete "$p" || { fb reboot; die "$(t "$p could not be deleted; nothing changed" "$p удалить не удалось; ничего не изменилось")"; }
    removed=1; ok "$(t "$p deleted" "$p удалён")"
  else
    ok "$(t "slot ${S#_} has no $p: nothing to remove" "в слоте ${S#_} нет $p: удалять нечего")"
  fi
  if [ "$removed" = 1 ] && [ "$KEEP_DATA" = 0 ] && [ "$WIPE" = format ]; then fb_wipe; fi
  fb reboot >/dev/null 2>&1
  state_set gapps ""
  if [ "$removed" = 1 ] && [ "$KEEP_DATA" = 0 ] && [ "$WIPE" = factory-reset ]; then
    say "$(t "Now on the projector: Settings > Device preferences > Reset > Factory reset." "Теперь на проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.")"
  fi
  ok "$(t "the projector starts Lumen OS without Google services" "проектор загружает Lumen OS без сервисов Google")"
}

gapps_status() {
  local v z a
  pick_device
  [ -n "$(prop ro.z9x.version)" ] || die "$(t "the projector is not running Lumen OS" "на проекторе не Lumen OS")"
  v=$(prop sys.z9x.gapps); z=$(prop sys.z9x.gapps.zip)
  case "$v" in
    ok) ok "$(t "Google services added by you: on" "добавленные вами сервисы Google: включены") ($(gapps_line "$z" | cut -d' ' -f2))" ;;
    skip) ok "$(t "this Lumen OS has Google services built in" "в этой Lumen OS сервисы Google встроены")" ;;
    none|'') say "$(t "no Google services added. To add them: bash lumen-install.sh gapps --zip FILE (guide, section 'Adding Google services')" "сервисы Google не добавлены. Как добавить: bash lumen-install.sh gapps --zip ФАЙЛ (инструкция, раздел «Как поставить сервисы Google»)")" ;;
    bad:slow) warn "$(t "at the last start the check of the Google services took too long, so the projector started without them: restart it" "при последнем запуске проверка сервисов Google шла слишком долго, поэтому проектор запустился без них: перезагрузите его")" ;;
    off)
      warn "$(t "the Google services were turned off: the projector failed to start twice in a row with them" "сервисы Google выключены: проектор дважды подряд не смог загрузиться с ними")"
      if ask "$(t "Turn them on again (the projector restarts)?" "Включить их снова (проектор перезагрузится)?")"; then
        try_root || die "adb root"
        adbs shell rm -rf /metadata/z9x_gapps >/dev/null 2>&1
        adbs reboot >/dev/null 2>&1
        ok "$(t "turned on; the projector restarts" "включены; проектор перезагружается")"
      fi ;;
    *) warn "$(t "the Google services you added cannot be used ($v): run 'gapps --zip FILE' again, or 'gapps --remove'" "добавленные сервисы Google не могут использоваться ($v): запустите 'gapps --zip ФАЙЛ' ещё раз или 'gapps --remove'")" ;;
  esac
}

cmd_gapps() {
  [ -z "$GAPPS_ZIP" ] || [ "$GAPPS_REMOVE" = 0 ] || die "gapps: --zip or --remove, not both"
  if [ "$GAPPS_REMOVE" = 1 ]; then gapps_remove
  elif [ -n "$GAPPS_ZIP" ]; then gapps_install
  else gapps_status; fi
}

# ------------------------------------------------------------------ projector profiles (lib/devices.conf)
# What a projector IS comes from lib/devices.conf: data only, checked line by line here and never sourced.
# What the installer DOES (its steps, and fb_allowed) stays in this script, so that file cannot widen what is
# written. The Z9X runs on the constants at the top and does not need lib/devices.conf (it only names other
# projectors from it).
DEVCONF="$HERE/lib/devices.conf"
P_NAME=""; P_MODEL=""; P_BOARD=""; P_FB_PRODUCT=""; P_VENDOR_OK=""; P_CERT=""; P_TOKEN=""; P_FS=""; P_VERSION_PROP=""
P_LUMEN_PROP=""; P_LOGICAL=""; P_RESERVE=0; P_GUIDE=""; P_RESTORE_BIN=""; P_RESTORE_MD5=""
P_KEYS="NAME MODEL BOARD FB_PRODUCT VENDOR_OK CERT IMAGE_TOKEN IMAGE_FS VERSION_PROP LUMEN_PROP LOGICAL SUPER_RESERVE GUIDE RESTORE_BIN RESTORE_MD5"
devconf_check() {  # stops on a missing file, a broken line, an unknown key or a key given twice
  local bad
  [ -f "$DEVCONF" ] || die "internal: lib/devices.conf is missing: re-download the installer"
  bad=$(awk -v keys=" $P_KEYS " '
    { sub(/\r$/, "") }
    /^#/ || /^$/ { next }
    !/^[a-z0-9]+\.[A-Z][A-Z0-9_]*=[A-Za-z0-9 ._:\/-]*$/ { print NR; exit }
    { k = substr($0, 1, index($0, "=") - 1); n = k; sub(/^[^.]*\./, "", n)
      if (index(keys, " " n " ") == 0 || (k in seen)) { print NR; exit }
      seen[k] = 1 }' "$DEVCONF")
  [ -z "$bad" ] || die "internal: lib/devices.conf line $bad is not valid (unknown key, a key given twice, or characters that are not allowed): re-download the installer"
}
devconf_get() {  # id KEY -> value (nothing without lib/devices.conf)
  [ -f "$DEVCONF" ] || return 0
  awk -v k="$1.$2=" '{ sub(/\r$/, "") } index($0, k) == 1 { print substr($0, length(k) + 1); exit }' "$DEVCONF"
}
dev_name() {  # id -> the name shown to the user
  local n; n=$(devconf_get "$1" NAME)
  [ -n "$n" ] || case "$1" in z9x) n="XGIMI Z9X" ;; play6) n="XGIMI Play 6" ;; *) n=$1 ;; esac
  printf '%s' "$n"
}
profile_of() {  # model board -> the id of the profile that has both (nothing: unknown)
  [ -f "$DEVCONF" ] || return 0
  awk -v m="$1" -v b="$2" '{ sub(/\r$/, "") }
    /^[a-z0-9]+\.MODEL=/ { i = substr($0, 1, index($0, ".") - 1); mo[i] = substr($0, index($0, "=") + 1); o[++n] = i }
    /^[a-z0-9]+\.BOARD=/ { i = substr($0, 1, index($0, ".") - 1); bo[i] = substr($0, index($0, "=") + 1) }
    END { for (j = 1; j <= n; j++) if (mo[o[j]] == m && bo[o[j]] == b) { print o[j]; exit } }' "$DEVCONF"
}
profiles_named() {  # "XGIMI Z9X (G0082) and XGIMI Play 6 (...)": every projector of lib/devices.conf
  awk -v and="$(t and и)" '{ sub(/\r$/, "") }
    /^[a-z0-9]+\.NAME=/ { i = substr($0, 1, index($0, ".") - 1); nm[i] = substr($0, index($0, "=") + 1); o[++n] = i }
    /^[a-z0-9]+\.MODEL=/ { i = substr($0, 1, index($0, ".") - 1); mo[i] = substr($0, index($0, "=") + 1) }
    END { for (j = 1; j <= n; j++) s = s (j > 1 ? (j == n ? " " and " " : ", ") : "") nm[o[j]] " (" mo[o[j]] ")"; print s }' "$DEVCONF"
}
load_profile() {  # id: the P_* values of that projector
  local k v
  devconf_check
  [ -n "$(devconf_get "$1" NAME)" ] || die "internal: lib/devices.conf has no projector '$1': re-download the installer"
  while IFS='=' read -r k v; do
    case "$k" in
      NAME) P_NAME=$v ;; MODEL) P_MODEL=$v ;; BOARD) P_BOARD=$v ;; FB_PRODUCT) P_FB_PRODUCT=$v ;;
      VENDOR_OK) P_VENDOR_OK=$v ;; CERT) P_CERT=$v ;; IMAGE_TOKEN) P_TOKEN=$v ;; IMAGE_FS) P_FS=$v ;;
      VERSION_PROP) P_VERSION_PROP=$v ;; LUMEN_PROP) P_LUMEN_PROP=$v ;; LOGICAL) P_LOGICAL=$v ;;
      SUPER_RESERVE) P_RESERVE=$v ;; GUIDE) P_GUIDE=$v ;; RESTORE_BIN) P_RESTORE_BIN=$v ;; RESTORE_MD5) P_RESTORE_MD5=$v ;;
    esac
  done <<EOF
$(awk -v id="$1." '{ sub(/\r$/, "") } index($0, id) == 1 { print substr($0, length(id) + 1) }' "$DEVCONF")
EOF
  for k in P_MODEL P_BOARD P_FB_PRODUCT P_VENDOR_OK P_CERT P_TOKEN P_FS P_VERSION_PROP P_LUMEN_PROP P_LOGICAL P_RESERVE; do
    [ -n "${!k}" ] || die "internal: lib/devices.conf: '$1' has no ${k#P_}: re-download the installer"
  done
  case "$P_RESERVE" in *[!0-9]*) die "internal: lib/devices.conf: $1.SUPER_RESERVE" ;; esac
  case "$P_LUMEN_PROP" in *" "*) ;; *) die "internal: lib/devices.conf: $1.LUMEN_PROP" ;; esac
}
other_hint() {  # model board: the Z9X check met another projector this installer knows: its way in
  case "$(profile_of "$1" "$2")" in
    play6) printf ' %s' "$(t "It is an $(dev_name play6): it installs over Wi-Fi, run the installer with --ip ADDRESS (see its section of the guide)." "Это $(dev_name play6): он ставится по Wi-Fi, запустите установщик с --ip АДРЕС (см. его раздел инструкции).")" ;;
  esac
}
p6_unfinished() {  # 0 = a Play 6 install is saved that was checked and has not reached the wipe yet
  local f="$STATE_DIR/play6/state"
  [ -f "$f" ] && grep -qx 'step_check=done' "$f" && ! grep -q '^step_wipe=.' "$f"
}
z9x_unfinished() {
  local f="$STATE_DIR/state"
  [ -f "$f" ] && grep -qx 'step_check=done' "$f" && ! grep -qx 'step_flash=done' "$f"
}
resolve_device() {  # the projector: --device, else --ip or 'stick' (Play 6), this download, an unfinished own build
  case "$DEVICE_OPT" in ''|z9x|play6) ;; *) die "--device z9x|play6" ;; esac
  case "$REL_DEVICE" in ''|z9x|play6) ;; *) die "internal: release.conf DEVICE=$REL_DEVICE: re-download the installer" ;; esac
  # A download names its projector (release.conf DEVICE, none = the Z9X), so a Z9X download behaves exactly as it
  # always did, also on a computer that keeps an unfinished Play 6 install. Only the owner's own builds
  # (--unsigned-image, not bound to a download) let an unfinished install name the projector.
  if [ -n "$DEVICE_OPT" ]; then PROJECTOR=$DEVICE_OPT
  elif [ "$IP_GIVEN" = 1 ] || [ "${CMD:-all}" = stick ]; then PROJECTOR=play6
  elif [ -n "$REL_DEVICE" ]; then PROJECTOR=$REL_DEVICE
  elif [ "$UNSIGNED" = 1 ] && p6_unfinished; then
    if z9x_unfinished; then
      die "$(t "two unfinished installs are saved, an XGIMI Z9X and an $(dev_name play6): choose one with --device z9x or --device play6" "сохранены две незаконченные установки, XGIMI Z9X и $(dev_name play6): выберите одну через --device z9x или --device play6")"
    fi
    PROJECTOR=play6
  fi
  if [ "$PROJECTOR" = z9x ] && [ "$IP_GIVEN" = 1 ]; then
    die "$(t "--ip is for the $(dev_name play6); the XGIMI Z9X installs over the USB cable (guide)" "--ip нужен для $(dev_name play6); XGIMI Z9X ставится по кабелю USB (инструкция)")"
  fi
  if [ "$PROJECTOR" = play6 ]; then
    load_profile play6
    SD=$STATE_DIR/play6
    IMG_GLOB="lumen-os-$P_TOKEN-*-system.img"
  fi
}
release_guard() {  # the image of this download belongs to one projector (release.conf DEVICE; none = the Z9X)
  local rel=${REL_DEVICE:-z9x}
  [ "$rel" = "$PROJECTOR" ] && return 0
  if [ "$UNSIGNED" = 1 ]; then
    warn "$(t "this installer folder came with the $(dev_name "$rel") release; your own image is used (--unsigned-image)" "эта папка установщика из релиза для $(dev_name "$rel"); используется ваш собственный образ (--unsigned-image)")"
    return 0
  fi
  die "$(t "this download of the installer is for the $(dev_name "$rel"), not for the $(dev_name "$PROJECTOR"): download the $(dev_name "$PROJECTOR") release (see the guide). Nothing was done." "этот установщик скачан для $(dev_name "$rel"), а не для $(dev_name "$PROJECTOR"): скачайте релиз для $(dev_name "$PROJECTOR") (см. инструкцию). Ничего не сделано.")"
}

# ------------------------------------------------------------------ XGIMI Play 6
# adb runs over Wi-Fi only (ADB_T = ADDR:PORT): the projector's one USB-A port is a host port under Android.
# fastbootd is reached with 'adb reboot fastboot' sent over Wi-Fi and a USB A-to-A cable in that port; the
# projector does not start while that cable is in, so it goes in only for the write. Steps: check -> backup
# (asked) -> write (fastbootd, system only) -> boot1 (the first start of Lumen OS on the old data, over Wi-Fi)
# -> wipe (Android's own factory reset, asked for as root over adb; XGIMI's recovery formats userdata and
# cache, as for Settings > Reset) -> verify (optional, later). Progress: $STATE_DIR/play6/state.
P6_APP=org.z9x.projector                          # the projector app of the image
P6_TUNER=com.zacharee1.systemuituner              # installed on stock to turn debugging on; a wipe removes it
P6_MARKER=/data/local/tmp/lumen-install-wipe      # written before the wipe; a real wipe removes it

p6_lprop() { printf '%s' "${P_LUMEN_PROP%% *}"; }  # the property that names the device on Lumen OS
p6_lval() { printf '%s' "${P_LUMEN_PROP#* }"; }    # and its value for this projector
p6_guide() { if [ "$LANG_UI" = ru ]; then printf '%s#%s' "$GUIDE_RU" "$P_GUIDE"; else printf '%s#%s' "$GUIDE_EN" "$P_GUIDE"; fi; }
P6_ST=""             # what 'adb get-state' said last in wifi_connect (unauthorized: the allow prompt was not accepted)
p6_noconn_text() {  # [seconds]: why wifi_connect gave up, as the user can act on it
  local en ru
  case "${1:-60}" in 60) en="within a minute"; ru="за минуту" ;; *) en="within $(( ${1} / 60 )) minutes"; ru="за $(( ${1} / 60 )) мин." ;; esac
  case "$P6_ST" in
    *unauthorized*)
      t "the projector at $ADB_T did not allow this computer $en. On the projector: allow debugging from this computer, tick \"Always allow from this computer\" and press OK; then run the installer again. Nothing was done." "проектор по адресу $ADB_T не разрешил отладку с этого компьютера $ru. На проекторе разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK, затем запустите установщик снова. Ничего не сделано." ;;
    *)
      t "no adb connection to $ADB_T over Wi-Fi $en. On the projector: is Wi-Fi on, is network debugging on (guide, section '$P_NAME'), is it this address (the projector's network settings show it)? This computer must be in the same network. Nothing was done." "нет связи adb с $ADB_T по Wi-Fi $ru. На проекторе включены Wi-Fi и отладка по сети (инструкция, раздел «${P_NAME}»), адрес тот же (он виден в настройках сети проектора)? Компьютер должен быть в той же сети. Ничего не сделано." ;;
  esac
}

p6_discover() {  # [serial]: ADDR:PORT of the one projector that 'adb mdns services' lists (with that serial)
  local l
  l=$(adb mdns services 2>/dev/null | tr -d '\r' \
      | awk -v s="${1:+adb-$1-}" '$1 ~ /^adb-/ && $2 ~ /^_adb\._tcp\.?$/ && (s == "" || index($1, s) == 1) { print $NF }' | sort -u)
  [ "$(printf '%s\n' "$l" | grep -c .)" = 1 ] && printf '%s\n' "$l"
  return 0
}

wifi_connect() {  # seconds: adb to ADB_T until it answers as 'device' (0) or the time is up (1)
  # The time counts both the pauses and the real clock (adb connect and get-state can take seconds each on a
  # network that drops packets), so "a minute" stays about a minute.
  local i=0 st said=0 a t0=$SECONDS
  while :; do
    adb connect "$ADB_T" >/dev/null 2>&1
    st=$(adb -s "$ADB_T" get-state 2>&1 | tr -d '\r')
    P6_ST=$st
    case "$st" in
      device) return 0 ;;
      *unauthorized*)
        [ "$said" = 1 ] || say "$(t "On the projector: allow debugging from this computer. Tick \"Always allow from this computer\" and press OK." "На проекторе разрешите отладку с этого компьютера: отметьте «Всегда разрешать с этого компьютера» и нажмите OK.")"
        said=1 ;;
      *offline*) adb disconnect "$ADB_T" >/dev/null 2>&1 ;;
    esac
    [ "$i" -lt "$1" ] && [ $((SECONDS - t0)) -lt "$1" ] || return 1
    # the address may have changed (DHCP): the projector's mDNS name carries its serial (only that projector,
    # also with --ip: p6_identity checks the serial again)
    if [ -n "$SERIAL" ] && [ $((i % 30)) = 25 ]; then
      a=$(p6_discover "$SERIAL")
      if [ -n "$a" ] && [ "$a" != "$ADB_T" ]; then
        say "$(t "the projector answers at a new address: $a" "проектор отвечает по новому адресу: $a")"
        ADB_T=$a
      fi
    fi
    sleep 5; i=$((i + 5))
  done
}

p6_alive() {  # 0 = the projector answers on a NEW connection (an old one may still say 'device' after a restart)
  adb disconnect "$ADB_T" >/dev/null 2>&1
  adb connect "$ADB_T" >/dev/null 2>&1
  [ "$(adb -s "$ADB_T" get-state 2>/dev/null | tr -d '\r')" = device ]
}
P6_NOMARK=lumen-install-no-check-file
p6_marker() {  # the wipe's check file: 'there' (no wipe since it was written), 'gone' (erased), '' (could not read)
  # Every attempt writes the file before it saves the token and before it asks for the reset, so a file that is
  # there, whatever token it holds, means that no wipe happened after the last request.
  case "$(adbs shell "cat $P6_MARKER 2>/dev/null || echo $P6_NOMARK" 2>/dev/null | tr -d '\r')" in
    "$P6_NOMARK") echo gone ;;
    lumen-*) echo there ;;
  esac
  return 0
}

p6_identity() {  # the device at ADB_T: this projector model, and the saved one (DHCP may give its address away)
  local code board ser
  code=$(prop ro.boot.xgimi.modelname); board=$(prop ro.boot.hardware); ser=$(prop ro.serialno)
  if [ "$code" != "$P_MODEL" ] || [ "$board" != "$P_BOARD" ]; then
    adb disconnect "$ADB_T" >/dev/null 2>&1
    if [ "$(profile_of "$code" "$board")" = z9x ]; then
      die "$(t "the device at $ADB_T is an XGIMI Z9X: it installs over the USB cable, without --ip (guide). Nothing was done." "устройство по адресу $ADB_T это XGIMI Z9X: он ставится по кабелю USB, без --ip (инструкция). Ничего не сделано.")"
    fi
    die "$(t "this projector is not supported: model '$code', board '$board'. Lumen OS installs on $(profiles_named). Nothing was done." "этот проектор не поддерживается: код модели '$code', плата '$board'. Lumen OS ставится на $(profiles_named). Ничего не сделано.")"
  fi
  [ -n "$ser" ] || die "$(t "the projector at $ADB_T reports no serial number. Nothing was done." "проектор по адресу $ADB_T не сообщает серийный номер. Ничего не сделано.")"
  if [ -n "$SERIAL" ] && [ "$SERIAL" != "$ser" ]; then
    adb disconnect "$ADB_T" >/dev/null 2>&1
    if [ "$SERIAL_FROM_STATE" = 1 ]; then
      die "$(t "the projector at $ADB_T has the serial $ser, but this install was started on $SERIAL. Another projector? Then first: bash lumen-install.sh --device play6 reset. Nothing was done." "у проектора по адресу $ADB_T серийный номер $ser, а установка начиналась на $SERIAL. Другой проектор? Тогда сначала: bash lumen-install.sh --device play6 reset. Ничего не сделано.")"
    fi
    die "$(t "the projector at $ADB_T has the serial $ser, not $SERIAL (--serial). Nothing was done." "у проектора по адресу $ADB_T серийный номер $ser, а не $SERIAL (--serial). Ничего не сделано.")"
  fi
  SERIAL=$ser
}

p6_usb_hint() {  # no address for a Play 6: a Z9X on the USB cable means the wrong download or the wrong option
  local l code board
  l=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" && $1 !~ /:/ {print $1}')
  [ "$(printf '%s\n' "$l" | grep -c .)" = 1 ] || return 0
  code=$(adb -s "$l" shell getprop ro.boot.xgimi.modelname 2>/dev/null | tr -d '\r')
  board=$(adb -s "$l" shell getprop ro.boot.hardware 2>/dev/null | tr -d '\r')
  if [ "$(profile_of "$code" "$board")" = z9x ]; then
    if [ "${REL_DEVICE:-z9x}" = play6 ]; then
      die "$(t "the projector on the USB cable is an XGIMI Z9X, and this download of the installer is for the $P_NAME: download the XGIMI Z9X release (see the guide). Nothing was done." "на кабеле USB проектор XGIMI Z9X, а этот установщик скачан для $P_NAME: скачайте релиз для XGIMI Z9X (см. инструкцию). Ничего не сделано.")"
    fi
    die "$(t "the projector on the USB cable is an XGIMI Z9X: run the installer without --device play6. Nothing was done." "на кабеле USB проектор XGIMI Z9X: запустите установщик без --device play6. Ничего не сделано.")"
  fi
}

play6_adb() {  # seconds: adb over Wi-Fi to the Play 6 (--ip, the saved address, or mDNS) and who it is; 1 = no answer
  local a
  if [ -z "$ADB_T" ]; then
    a=$(state_get ip)
    if [ -z "$a" ]; then
      a=$(p6_discover)
      if [ -z "$a" ] || ! ask "$(t "A projector answers at $a. Is it yours, the one to install on?" "По адресу $a отвечает проектор. Это ваш, на который ставим?")"; then
        p6_usb_hint
        die "$(t "give the projector's Wi-Fi address: bash lumen-install.sh --ip 192.168.x.y (the projector's network settings show it; see the guide, section '$P_NAME'). The $P_NAME has no USB debugging: this computer reaches it over Wi-Fi. Nothing was done." "укажите адрес проектора в Wi-Fi: bash lumen-install.sh --ip 192.168.x.y (он виден в настройках сети проектора; см. инструкцию, раздел «${P_NAME}»). У $P_NAME нет отладки по USB: компьютер подключается к нему по Wi-Fi. Ничего не сделано.")"
      fi
    fi
    ADB_T=$a
  fi
  say "$(t "connecting to $ADB_T over Wi-Fi" "подключаюсь к $ADB_T по Wi-Fi")"
  wifi_connect "$1" || return 1
  p6_identity
}

p6_fw_list() {  # "V<first> and V<second>" from VENDOR_OK
  printf '%s\n' "$P_VENDOR_OK" | awk -v and="$(t and и)" '{ for (i = 1; i <= NF; i++) s = s (i > 1 ? (i == NF ? " " and " " : ", ") : "") "V" substr($i, 2); print s }'
}
p6_fw_first() { printf '%s\n' "$P_VENDOR_OK" | awk '{ print "V" substr($1, 2) }'; }

play6_device() {  # connected: firmware, single slot, stock or Lumen OS; saves serial, address, firmware
  local vend slot lum ver up all first
  vend=$(prop ro.vendor.build.version.incremental); slot=$(prop ro.boot.slot_suffix); lum=$(prop "$(p6_lprop)")
  case " $P_VENDOR_OK " in
    *" $vend "*) ;;
    *) all=$(p6_fw_list); first=$(p6_fw_first)
       die "$(t "XGIMI firmware $vend is not supported: Lumen OS for the $P_NAME is tested on $all only. Older: let the stock system update itself to $first (Settings, Wi-Fi), then run the installer again. Newer, or XGIMI's global / Russian firmware (a version like RG0058V1.8xxx): Lumen OS does not support it yet. Do NOT install $first from a USB stick over it: on newer $P_NAME units Wi-Fi and Bluetooth then stop working (that Chinese firmware has no driver for their Wi-Fi chip). Nothing was done." "прошивка XGIMI $vend не поддерживается: Lumen OS для $P_NAME проверена только на $all. Если ваша старее, дайте стоку обновиться до $first (настройки, Wi-Fi) и запустите установщик снова. Если новее или это глобальная (российская) прошивка XGIMI (версия вида RG0058V1.8xxx), Lumen OS её пока не поддерживает. НЕ ставьте поверх неё $first с флешки: на новых $P_NAME после этого перестают работать Wi-Fi и Bluetooth (в этой китайской прошивке нет драйвера их чипа Wi-Fi). Ничего не сделано.")" ;;
  esac
  [ -z "$slot" ] || die "$(t "this projector has a second system slot ($slot): it is not the $P_NAME this installer knows. Nothing was done." "у этого проектора есть второй системный слот ($slot): это не тот $P_NAME, который знает установщик. Ничего не сделано.")"
  case "$lum" in
    '') state_set dev_lumen 0; state_set dev_version ""; ver=$(t "XGIMI stock" "родная XGIMI")
        # a downloaded XGIMI update arms the next restart; the installer's own restart into fastbootd does not
        # run it (adb reboot fastboot writes its own command, fastbootd clears it), a restart by hand would
        up=$(prop xgimi.ota.update.onboot)
        [ -n "$up" ] || up=$(adbs shell settings get system xgimi_ota_update 2>/dev/null | tr -d '\r')
        case "$up" in 1|2) warn "$(t "a downloaded XGIMI update waits for the next restart: do not restart the projector yourself until the installer has written Lumen OS (it replaces that system anyway). Next time turn automatic updates off first." "скачанное обновление XGIMI ждёт следующей перезагрузки: не перезагружайте проектор сами, пока установщик не запишет Lumen OS (она всё равно заменит эту систему). В следующий раз сначала выключите автообновление.")" ;; esac ;;
    "$(p6_lval)") ver=$(prop "$P_VERSION_PROP"); state_set dev_lumen 1; state_set dev_version "$ver"; ver="Lumen OS $ver" ;;
    *) die "$(t "this projector runs Lumen OS for another device ('$lum'). Nothing was done." "на проекторе Lumen OS для другого устройства ('$lum'). Ничего не сделано.")" ;;
  esac
  state_set serial "$SERIAL"; state_set ip "$ADB_T"; state_set vendor "$vend"
  ok "$P_NAME $SERIAL ($ADB_T), $(t 'firmware' 'прошивка') $vend, $(t 'system' 'система'): $ver"
}

p6_bound_text() {  # version from the name (empty: an own build): what binds the image without reading its content
  if [ -n "$1" ]; then
    t "the signed SHA256SUMS lists this file as Lumen OS $1, the version of this installer download" "подписанный SHA256SUMS называет этот файл Lumen OS $1, версией этого установщика"
  else
    t "an own build (--unsigned-image)" "собственная сборка (--unsigned-image)"
  fi
}
p6_image_props() {  # the image's properties in the order init reads them (a later file wins: product is read last)
  local p
  for p in /system/etc/prop.default /system/build.prop /system/system_ext/etc/build.prop /system/product/etc/build.prop; do
    debugfs -R "cat $p" "$IMG" 2>/dev/null
  done
  return 0
}

play6_check_image() {  # the release image of this projector: name, file system, size, signature, checksum, content
  local name dir sums sig cert pub want got a f sz bp v sec nv=""
  find_image
  name=$(basename "$IMG"); dir=$(dirname "$IMG"); sums="$dir/SHA256SUMS"; sig="$sums.sig"; cert="$HERE/$P_CERT"
  case "$name" in *"$P_TOKEN"*) ;; *) die "$(t "$name is not an image for the $P_NAME (its name must contain '$P_TOKEN'). Nothing was written." "$name не образ для $P_NAME (в имени должно быть '$P_TOKEN'). Ничего не записано.")" ;; esac
  case "$name" in *PRIVATE*) die "$(t "$name is a private image: it is never installed on the $P_NAME. Nothing was written." "$name частный образ: на $P_NAME он не ставится. Ничего не записано.")" ;; esac
  if [ "$UNSIGNED" = 0 ]; then
    case "$name" in *-nogms-*) ;; *) die "$(t "$name is not a release file (the $P_NAME has Lumen OS without Google only: '-nogms-' in the name). Nothing was written." "$name не файл релиза (для $P_NAME есть только Lumen OS без Google: '-nogms-' в имени). Ничего не записано.")" ;; esac
    # the release version is in the name, and the signed SHA256SUMS lists the image under that name: a file of
    # another release (signed with the same key) next to this installer stops here, before anything is written
    [ -n "${VERSION:-}" ] || die "internal: release.conf has no VERSION: re-download the installer of the $P_NAME release"
    case "$name" in "lumen-os-$P_TOKEN-"*"-nogms-system.img") nv=${name#"lumen-os-$P_TOKEN-"}; nv=${nv%-nogms-system.img} ;; esac
    [ -n "$nv" ] || die "$(t "$name is not named like a release file (lumen-os-$P_TOKEN-<version>-nogms-system.img): use the file exactly as downloaded. Nothing was written." "$name назван не как файл релиза (lumen-os-$P_TOKEN-<версия>-nogms-system.img): используйте файл ровно таким, как он скачан. Ничего не записано.")"
    [ "$nv" = "$VERSION" ] || die "$(t "$name is Lumen OS $nv, but this installer download is for Lumen OS $VERSION: download the image and the installer from the same release (guide, section '$P_NAME'). Nothing was written." "$name это Lumen OS $nv, а этот установщик скачан для Lumen OS $VERSION: скачайте образ и установщик из одного релиза (инструкция, раздел «${P_NAME}»). Ничего не записано.")"
  fi
  # the file system, read without any tool: raw ext4 has 53 ef at byte 1080
  if [ "$P_FS" = ext4 ] && [ "$(od -An -tx1 -j1080 -N2 "$IMG" 2>/dev/null | tr -d ' \n')" != 53ef ]; then
    if [ "$(od -An -tx1 -j1024 -N4 "$IMG" 2>/dev/null | tr -d ' \n')" = e2e1f5e0 ]; then
      die "$(t "$name is an XGIMI Z9X image (erofs), not one for the $P_NAME. Nothing was written." "$name образ для XGIMI Z9X (erofs), а не для $P_NAME. Ничего не записано.")"
    elif [ "$(od -An -tx1 -N4 "$IMG" 2>/dev/null | tr -d ' \n')" = 3aff26ed ]; then
      die "$(t "$name is a sparse image, not the release file. Nothing was written." "$name разреженный (sparse) образ, а не файл релиза. Ничего не записано.")"
    fi
    die "$(t "$name is not an ext4 system image. Nothing was written." "$name не системный образ ext4. Ничего не записано.")"
  fi
  sz=$(wc -c < "$IMG" | tr -d ' ')
  [ $((sz % 4096)) = 0 ] || die "$(t "$name has a size that is not a multiple of 4096: damaged. Nothing was written." "размер $name не кратен 4096: файл испорчен. Ничего не записано.")"
  if [ -f "$sums" ] && [ -f "$sig" ] && [ -f "$cert" ] && command -v openssl >/dev/null 2>&1; then
    pub=$(mktemp)
    openssl x509 -in "$cert" -pubkey -noout > "$pub"
    openssl dgst -sha256 -verify "$pub" -signature "$sig" "$sums" >/dev/null 2>&1 \
      || { rm -f "$pub"; die "$(t "SHA256SUMS is not signed with the Lumen OS key for the $P_NAME: do not install this image" "SHA256SUMS не подписан ключом Lumen OS для $P_NAME: не устанавливайте этот образ")"; }
    rm -f "$pub"
    ok "$(t "SHA256SUMS signature: Lumen OS key for the $P_NAME" "подпись SHA256SUMS: ключ Lumen OS для $P_NAME")"
  elif [ "$UNSIGNED" = 1 ]; then
    warn "$(t "--unsigned-image: this image is NOT authenticated (no signed SHA256SUMS). Only for your own build." "--unsigned-image: образ НЕ проверен (нет подписанного SHA256SUMS). Только для собственной сборки.")"
    if [ "$(state_get unsigned_ok)" != "$name:$(filekey "$IMG")" ]; then
      printf '%s ' "$(t "Type UNSIGNED to install $name anyway:" "Введите UNSIGNED, чтобы всё равно установить $name:")"
      read -r a
      [ "$a" = UNSIGNED ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
      state_set unsigned_ok "$name:$(filekey "$IMG")"
    fi
    if [ ! -f "$sums" ]; then   # a list that names the image, or the image's own .sha256 (tools/play6/build_test.sh)
      sums=""
      for f in "$dir"/SHA256SUMS* "$IMG.sha256"; do
        case "$f" in *.sig) continue ;; esac
        [ -f "$f" ] && awk -v n="$name" '$2==n || $2=="*"n {f=1} END{exit !f}' "$f" && { sums=$f; break; }
      done
    fi
  else
    [ -f "$sums" ] || die "$(t "no SHA256SUMS next to the image: it cannot be checked. Download SHA256SUMS and SHA256SUMS.sig from the same release." "рядом с образом нет SHA256SUMS: его нельзя проверить. Скачайте SHA256SUMS и SHA256SUMS.sig из того же релиза.")"
    [ -f "$sig" ] || die "$(t "no SHA256SUMS.sig next to the image: its origin cannot be checked" "рядом с образом нет SHA256SUMS.sig: происхождение образа нельзя проверить")"
    [ -f "$cert" ] || die "$(t "installer certificate $P_CERT missing: re-download the installer of the $P_NAME release" "нет сертификата установщика $P_CERT: скачайте установщик из релиза для $P_NAME заново")"
    die "$(t "openssl is needed to check the image signature (macOS and Linux have it; install it)" "для проверки подписи образа нужен openssl (есть в macOS и Linux; установите его)")"
  fi
  if [ -n "$sums" ] && [ -f "$sums" ]; then
    want=$(awk -v f="$name" '$2==f || $2=="*"f {print $1}' "$sums")
    [ -n "$want" ] || die "$(t "$name is not listed in $(basename "$sums")" "$name нет в $(basename "$sums")")"
    if [ "$FORCE_HASH" = 1 ] || [ "$(state_get image_ok)" != "$want:$(filekey "$IMG")" ]; then
      say "$(t "checking the image checksum (about a minute)" "проверяю контрольную сумму образа (около минуты)")"
      got=$(sha256 "$IMG")
      [ "$got" = "$want" ] || { state_set image_ok none; die "$(t "checksum mismatch: the image is damaged or not the right file" "контрольная сумма не совпала: образ повреждён или не тот")"; }
      state_set image_ok "$want:$(filekey "$IMG")"
    fi
    ok "$name sha256 ${want:0:16}…"
  else
    warn "$(t "--unsigned-image without any SHA256SUMS file: checksum not checked" "--unsigned-image без файла SHA256SUMS: контрольная сумма не проверена")"
  fi
  # what the image says about itself, when debugfs (e2fsprogs) is installed. Without it the name (its version,
  # checked above against release.conf) and the signed SHA256SUMS (which lists the file under that name) bind the
  # image to this release; this also catches a release built wrong.
  if command -v debugfs >/dev/null 2>&1; then
    bp=$(p6_image_props)
    if [ -n "$bp" ]; then
      v=$(printf '%s\n' "$bp" | sed -n "s/^$(p6_lprop)=//p" | tail -1)
      [ "$v" = "$(p6_lval)" ] || die "$(t "$name is not Lumen OS for the $P_NAME ($(p6_lprop)='$v'). Nothing was written." "$name не Lumen OS для $P_NAME ($(p6_lprop)='$v'). Ничего не записано.")"
      v=$(printf '%s\n' "$bp" | sed -n "s/^$P_VERSION_PROP=//p" | tail -1)
      sec=$(printf '%s\n' "$bp" | sed -n 's/^ro\.adb\.secure=//p' | tail -1)
      if [ "$UNSIGNED" = 0 ]; then
        [ "$v" = "${VERSION:-}" ] || die "$(t "$name holds Lumen OS '$v', the release is ${VERSION:-?}. Nothing was written." "в $name Lumen OS '$v', а релиз ${VERSION:-?}. Ничего не записано.")"
        [ "$sec" = 1 ] || die "$(t "$name is a test build (ro.adb.secure='$sec'), not a release. Nothing was written." "$name тестовая сборка (ro.adb.secure='$sec'), а не релиз. Ничего не записано.")"
      fi
      ok "$(t "image: Lumen OS $v for the $P_NAME" "образ: Lumen OS $v для $P_NAME")"
    else
      warn "$(t "debugfs could not read the image content (not needed: $(p6_bound_text "$nv"))" "debugfs не смог прочитать содержимое образа (не обязательно: $(p6_bound_text "$nv"))")"
    fi
  else
    say "$(t "image content not read (debugfs not installed): $(p6_bound_text "$nv")" "содержимое образа не прочитано (нет debugfs): $(p6_bound_text "$nv")")"
  fi
  state_set image_gms 0
  ok "$(t "edition: Lumen OS without Google" "редакция: Lumen OS без Google")"
}

play6_check() {
  local free
  check_tools
  play6_adb 60 || die "$(p6_noconn_text)"
  play6_device
  free=$(df -k "$HOME" | awk 'NR==2{print $4}')
  [ "${free:-0}" -ge 4194304 ] || warn "$(t "less than 4 GB free on this computer" "на компьютере свободно меньше 4 ГБ")"
  play6_check_image
  ok "$(t "check passed; nothing was written" "проверка пройдена; ничего не записано")"
  state_set step_check done
}

p6_in_fastboot() { [ -n "$SERIAL" ] && fastboot devices 2>/dev/null | awk '{print $1}' | grep -qx "$SERIAL"; }
p6_fb_wait() {  # seconds: the projector (SERIAL) in fastboot mode on the cable
  local i=0
  while [ "$i" -lt "$1" ]; do
    p6_in_fastboot && return 0
    [ "$i" -gt 0 ] && [ $((i % 60)) = 0 ] && say "$(t "still waiting for fastboot mode..." "всё ещё жду режима fastboot...")"
    sleep 2; i=$((i + 2))
  done
  return 1
}
p6_fb_stop() {  # message: leave fastbootd for the old system, nothing was written
  fb reboot >/dev/null 2>&1
  die "$1 $(t "Nothing was written. Unplug the A-to-A cable: the projector then starts its old system (press the power button if the picture does not come back)." "Ничего не записано. Выньте кабель A–A: проектор загрузит прежнюю систему (если изображения нет, нажмите кнопку питания).")"
}
play6_fbd_check() {  # fastbootd: this Play 6 (serial), its firmware and the known layout of super; else STOP
  local why="" l want wantg v fw=0
  GV=$(fb getvar all 2>&1)
  [ "$(gv_val is-userspace)" = yes ] || why=$(t "not fastbootd" "это не fastbootd")
  [ -n "$why" ] || [ "$(gv_val product)" = "$P_FB_PRODUCT" ] || why="$(t "fastbootd is not the one of the $P_NAME" "fastbootd не от $P_NAME") ($(gv_val product))"
  [ -n "$why" ] || [ "$(gv_val unlocked)" = yes ] || why=$(t "the bootloader is locked" "загрузчик заблокирован")
  [ -n "$why" ] || [ "$(gv_val serialno)" = "$SERIAL" ] || why="$(t "another projector in fastboot mode" "в режиме fastboot другой проектор") (serialno $(gv_val serialno), $(t 'expected' 'ожидался') $SERIAL)"
  if [ -z "$why" ]; then case "$(gv_val slot-count)" in ''|0) ;; *) why="slot-count $(gv_val slot-count)" ;; esac; fi
  [ -n "$why" ] || [ -z "$(gv_val current-slot)" ] || why="current-slot $(gv_val current-slot)"
  [ -n "$why" ] || [ "$(gv_val super-partition-name)" = super ] || why="super-partition-name '$(gv_val super-partition-name)'"
  l=$(printf '%s\n' "$GV" | sed -n 's/^(bootloader) is-logical:\([^:]*\):yes.*/\1/p' | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')
  want=$(printf '%s\n' $P_LOGICAL | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')
  # the Google services add-on ('gapps') adds one partition of its own
  wantg=$(printf '%s\n' $P_LOGICAL "$P6_GAPPS" | LC_ALL=C sort -u | tr '\n' ' ' | sed 's/ $//')
  [ -n "$why" ] || [ "$l" = "$want" ] || [ "$l" = "$wantg" ] || why="$(t "the partitions in super are not the known ones" "разделы в super не те, что известны") ($l)"
  if [ -z "$why" ]; then
    for v in $P_VENDOR_OK; do case "$(gv_val vendor-fingerprint)" in */"${v#v}":*) fw=1 ;; esac; done
    [ "$fw" = 1 ] || why="$(t "XGIMI firmware not supported" "прошивка XGIMI не поддерживается") ($(gv_val vendor-fingerprint))"
  fi
  [ -z "$why" ] || p6_fb_stop "$(t "fastboot mode check failed" "проверка режима fastboot не пройдена"): $why."
  ok "$(t "fastboot mode: $P_NAME $SERIAL" "режим fastboot: $P_NAME $SERIAL")"
}
p6_room() {  # the image must fit into super next to the other logical partitions (each rounded up to 1 MiB)
  local sup p sz used=0 room
  sup=$(gv_size super)
  [ -n "$sup" ] || p6_fb_stop "$(t "fastbootd did not report the size of super." "fastbootd не сообщил размер super.")"
  for p in $P_LOGICAL; do
    [ "$p" = system ] && continue
    sz=$(gv_size "$p")
    [ -n "$sz" ] || p6_fb_stop "$(t "fastbootd did not report the size of $p." "fastbootd не сообщил размер $p.")"
    used=$(( used + (sz + 1048575) / 1048576 * 1048576 ))
  done
  sz=$(gv_size "$P6_GAPPS")   # the Google services add-on ('gapps') keeps its space
  [ -z "$sz" ] || used=$(( used + (sz + 1048575) / 1048576 * 1048576 ))
  room=$(( sup - used - P_RESERVE ))
  sz=$(wc -c < "$IMG" | tr -d ' ')
  [ "$sz" -le "$room" ] || p6_fb_stop "$(t "the image does not fit into super ($((room / 1048576)) MB free, $(( (sz + 1048575) / 1048576 )) MB needed)." "образ не помещается в super (свободно $((room / 1048576)) МБ, нужно $(( (sz + 1048575) / 1048576 )) МБ).")"
}
p6_cable_out() {  # after 'fastboot reboot': the projector does not start while the computer's cable is in
  local a
  echo
  t "Written. Unplug the A-to-A cable from the projector NOW: the $P_NAME does not start while it is
connected. Put any USB stick into its USB port instead (it helps the $P_NAME power on; do NOT hold the power
button, that starts the stock restore). If the picture does not come back within a minute, press the power button
once." "Записано. Сейчас выньте кабель A–A из проектора: пока он подключён, $P_NAME не загружается.
Вставьте вместо него в USB-порт любую флешку (с ней $P_NAME включается надёжнее; кнопку питания НЕ держите,
иначе начнётся откат на сток). Если через минуту изображения нет, нажмите кнопку питания один раз."; echo
  printf '%s ' "$(t "Press Enter when the cable is out." "Когда кабель вынут, нажмите Enter.")"
  read -r a
}

play6_write() {  # check, confirmation, cable, fastbootd, write system, restart (resumes in fastbootd)
  local a infb=0 k
  check_tools
  if [ -n "$(state_get step_fastboot)" ] && [ "$(state_get step_flash)" != done ] && p6_in_fastboot; then
    infb=1
    if [ "$(state_get step_flash_system)" = done ]; then   # fastboot said OKAY, the restart was not sent
      ok "$(t "system is already written; restarting the projector" "system уже записан; перезагружаю проектор")"
      fb reboot >/dev/null 2>&1
      state_set step_flash done
      p6_cable_out
      return 0
    fi
    say "$(t "the projector waits in fastboot mode on the cable: continuing there" "проектор ждёт в режиме fastboot на кабеле: продолжаю там")"
  else
    if ! play6_adb 60; then
      # an earlier run sent the projector to fastboot mode: it is neither there (on the cable) nor back in its system
      [ -z "$(state_get step_fastboot)" ] || die "$(t "the projector is neither in fastboot mode on the cable nor reachable over Wi-Fi at $ADB_T. If the picture is dark: unplug the A-to-A cable (the $P_NAME does not start while it is in) and press the power button; a USB stick in the port helps it start. When its system is back, run the installer again: it sends the projector to fastboot mode again. If it stays dark: guide, section '$P_NAME', 'Back to stock'." "проектора нет ни в режиме fastboot на кабеле, ни в Wi-Fi по адресу $ADB_T. Если изображения нет, выньте кабель A–A (пока он вставлен, $P_NAME не загружается) и нажмите кнопку питания; флешка в порту помогает ему загрузиться. Когда система загрузится, запустите установщик снова: он снова отправит проектор в режим fastboot. Если изображения так и нет: инструкция, раздел «${P_NAME}», «Откат на сток».")"
      die "$(p6_noconn_text)"
    fi
    play6_device
  fi
  FORCE_HASH=1 play6_check_image
  echo
  if [ "$KEEP_DATA" = 1 ]; then
    t "Writing Lumen OS to the system partition of the $P_NAME WITHOUT erasing data (a repair).
XGIMI's firmware stays untouched: vendor, boot, recovery, MBOOT, calibration and factory data." "Записываю Lumen OS в раздел system $P_NAME БЕЗ стирания данных (ремонт).
Прошивка XGIMI не затрагивается: vendor, boot, recovery, MBOOT, калибровка и заводские данные."; echo
    printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
    read -r a
    [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  else
    t "Writing Lumen OS to the system partition of the $P_NAME, then ERASING ALL DATA (apps, accounts,
settings, files, Wi-Fi networks) with Android's factory reset after the first start. XGIMI's firmware
stays untouched: vendor, boot, recovery, MBOOT, calibration and factory data. The $P_NAME has only one
system: if the write is cut off by a power loss, the way back is the USB stick restore (guide, 'Back
to stock'). Keep the power adapter connected." "Записываю Lumen OS в раздел system $P_NAME, затем СТИРАЮ ВСЕ ДАННЫЕ (приложения, аккаунты,
настройки, файлы, сети Wi-Fi) сбросом Android к заводским настройкам после первого запуска. Прошивка
XGIMI не затрагивается: vendor, boot, recovery, MBOOT, калибровка и заводские данные. Система у
$P_NAME одна: если запись оборвётся из-за отключения питания, вернуть проектор можно только
восстановлением с флешки (инструкция, «Откат на сток»). Блок питания держите подключённым."; echo
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
    ERASE_NOW=1
    state_set erase_ok "$(date +%Y%m%d-%H%M%S)"
  fi
  if [ "$infb" = 0 ]; then
    for k in step_fastboot step_flash_system step_flash step_boot1 step_wipe step_verify wipe_marker; do state_set "$k" ""; done
    echo
    t "Take the USB stick out of the projector's USB port, if one is in, and connect that port to this
computer with the USB A-to-A cable. Keep the power adapter connected." "Выньте флешку из USB-порта проектора, если она там, и соедините этот порт с компьютером кабелем
USB A–A. Блок питания оставьте подключённым."; echo
    printf '%s ' "$(t "Press Enter when the cable is in." "Когда кабель вставлен, нажмите Enter.")"
    read -r a
    say "$(t "restarting into fastboot mode (about 30 seconds)" "перезагрузка в режим fastboot (около 30 секунд)")"
    # saved first: a restart that happens although adb reports an error (the Wi-Fi connection drops with it) is
    # still continued in fastboot mode by the next run
    state_set step_fastboot "$(date +%Y%m%d-%H%M%S)"
    if ! adbs reboot fastboot >/dev/null 2>&1 && p6_alive; then
      state_set step_fastboot ""
      die "$(t "adb reboot fastboot failed: the projector still runs its system. Nothing was written. Unplug the A-to-A cable and run the installer again." "adb reboot fastboot не удался: проектор работает в своей системе. Ничего не записано. Выньте кабель A–A и запустите установщик снова.")"
    fi
    adb disconnect "$ADB_T" >/dev/null 2>&1
  fi
  p6_fb_wait 180 || die "$(t "the projector did not appear on the cable within 3 minutes. Is the cable in the projector's USB port (not a hub)? On Windows: the Google USB Driver. On Linux: udev permissions ('no permissions' in 'fastboot devices'). Nothing was written. Run the installer again: it finds the projector in fastboot mode or sends it there again." "проектор не появился на кабеле за 3 минуты. Кабель в USB-порту проектора (не через хаб)? На Windows нужен Google USB Driver. На Linux права udev ('no permissions' в 'fastboot devices'). Ничего не записано. Запустите установщик снова: он найдёт проектор в режиме fastboot или снова отправит его туда.")"
  play6_fbd_check
  p6_room
  say "$(t "writing system (about 1 minute). Do not unplug the cable or the power until it is done." "записываю system (около минуты). Не отключайте кабель и питание, пока запись не закончится.")"
  fb_do flash system "$IMG" || die "$(t "system was not written completely. The projector stays in fastboot mode: keep the cable in and run the installer again, it writes system again." "system записан не полностью. Проектор остаётся в режиме fastboot: не вынимайте кабель и запустите установщик снова, он запишет system ещё раз.")"
  state_set step_flash_system done
  fb reboot >/dev/null 2>&1
  state_set step_flash done
  p6_cable_out
}

p6_write_again() {  # the write did not take effect: the next run (or 'flash') writes system again from the start
  local k
  for k in step_fastboot step_flash_system step_flash; do state_set "$k" ""; done
}
play6_boot1() {  # the first start of Lumen OS on the old data: Wi-Fi and debugging carry over from stock
  local i=0 t0 v lum
  [ -n "$ADB_T" ] || ADB_T=$(state_get ip)
  [ -n "$ADB_T" ] || die "internal: no saved address of the projector"
  say "$(t "waiting for Lumen OS over Wi-Fi (the first start takes up to 10 minutes)" "жду Lumen OS по Wi-Fi (первый запуск занимает до 10 минут)")"
  if ! wifi_connect 600; then
    case "$P6_ST" in *unauthorized*)
      die "$(t "Lumen OS runs, but it did not allow this computer within 10 minutes. On the projector: allow debugging from this computer, tick \"Always allow from this computer\" and press OK; then run the installer again. Nothing was erased; your data is still there." "Lumen OS работает, но проектор за 10 минут не разрешил отладку с этого компьютера. На проекторе разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK, затем запустите установщик снова. Ничего не стёрто, данные на месте.")" ;;
    esac
    die "$(t "Lumen OS did not answer over Wi-Fi within 10 minutes. Is the A-to-A cable out of the projector? The $P_NAME does not start while it is in. If the picture stays dark: unplug the cable and press the power button; a USB stick in the projector's USB port helps the $P_NAME start. If Lumen Home is on the screen: look up its address in Settings > Network & Internet and run the installer again with --ip ADDRESS. Nothing else was changed; your data is still there." "Lumen OS не ответила по Wi-Fi за 10 минут. Кабель A–A вынут из проектора? Пока он вставлен, $P_NAME не загружается. Если изображения нет, выньте кабель и нажмите кнопку питания; флешка в USB-порту помогает $P_NAME загрузиться. Если на экране Lumen Home, посмотрите адрес в «Настройки > Сеть и Интернет» и запустите установщик снова с --ip АДРЕС. Больше ничего не изменено, данные на месте.")"
  fi
  p6_identity
  t0=$SECONDS
  while [ "$(prop sys.boot_completed)" != 1 ]; do
    [ "$i" -lt 300 ] && [ $((SECONDS - t0)) -lt 300 ] || die "$(t "Lumen OS did not finish starting within 5 more minutes. Restart the projector with the power button and run the installer again." "Lumen OS не загрузилась до конца ещё за 5 минут. Перезагрузите проектор кнопкой питания и запустите установщик снова.")"
    sleep 5; i=$((i + 5))
  done
  lum=$(prop "$(p6_lprop)")
  if [ -z "$lum" ]; then
    p6_write_again
    die "$(t "the projector still runs XGIMI's system: the write did not take effect. Nothing was erased. Run the installer again with the A-to-A cable at hand: it writes system again. If this happens again, report it with the installer output." "на проекторе всё ещё система XGIMI: запись не применилась. Ничего не стёрто. Запустите установщик снова, держа кабель A–A под рукой: он запишет system ещё раз. Если это повторится, напишите об этом и приложите вывод установщика.")"
  fi
  [ "$lum" = "$(p6_lval)" ] || die "$(t "the projector runs Lumen OS for another device ('$lum'). Nothing was erased." "на проекторе Lumen OS для другого устройства ('$lum'). Ничего не стёрто.")"
  v=$(prop "$P_VERSION_PROP")
  if [ "$UNSIGNED" = 0 ] && [ -n "${VERSION:-}" ] && [ "$v" != "$VERSION" ]; then
    if [ "$(state_get dev_lumen)" = 1 ] && [ "$v" = "$(state_get dev_version)" ]; then
      p6_write_again
      die "$(t "the projector still runs the Lumen OS $v it had before: the write did not take effect. Nothing was erased. Run the installer again with the A-to-A cable at hand: it writes system again." "на проекторе всё ещё прежняя Lumen OS $v: запись не применилась. Ничего не стёрто. Запустите установщик снова, держа кабель A–A под рукой: он запишет system ещё раз.")"
    fi
    # the image was checked against this release before the write (name, signature, checksum): only its own
    # version name differs, which does not make the wipe of this projector any less right
    warn "$(t "Lumen OS '$v' runs, and this installer download is for $VERSION: the image names its version differently from its release. Please report it; the install goes on." "работает Lumen OS '$v', а этот установщик для $VERSION: образ называет свою версию иначе, чем его релиз. Сообщите об этом; установка продолжается.")"
  fi
  ok "$(t "Lumen OS $v started" "Lumen OS $v запустилась") ($SERIAL, $ADB_T)"
  if [ -n "$(adbs shell pidof "$P6_APP" 2>/dev/null | tr -d '\r')" ]; then ok "$(t "projector app running" "приложение проектора работает")"
  else warn "$(t "the projector app ($P6_APP) is not running yet" "приложение проектора ($P6_APP) пока не запущено")"; fi
  state_set ip "$ADB_T"
  state_set step_boot1 done
}

p6_root() {  # adb root restarts adbd, so connect again. 0 = root; 1 = this system gives no root (not userdebug);
  # 2 = no answer after 'adb root' (the allow prompt was not accepted, or the network): try again later
  [ "$(prop ro.debuggable)" = 1 ] || return 1
  [ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && return 0
  adbs root >/dev/null 2>&1
  sleep 3
  wifi_connect 60 || return 2
  [ "$(adbs shell id -u 2>/dev/null | tr -d '\r')" = 0 ] || return 1
}

play6_wipe() {  # Android's own factory reset on the first Lumen OS start; XGIMI's recovery carries it out
  local tok got out i=0 t0 a rc
  [ "$(state_get step_boot1)" = done ] || die "internal: no factory reset before Lumen OS has started"
  [ "$KEEP_DATA" = 0 ] || die "internal: no factory reset with --keep-data"
  [ -n "$ADB_T" ] || ADB_T=$(state_get ip)
  if ! wifi_connect 120; then
    # a factory reset asked for by an earlier run (cut off before it saved that) erases Wi-Fi and turns debugging off
    if [ -n "$(state_get wipe_marker)" ]; then
      case "$P6_ST" in *unauthorized*) ;; *)
        die "$(t "the projector does not answer over Wi-Fi at $ADB_T. If it shows Lumen OS without your apps, the factory reset of an earlier run has already erased the data, the Wi-Fi network too: join Wi-Fi with the remote (Settings > Network & Internet), turn debugging on (guide, section '$P_NAME', 'Check the install') and run the installer again with --ip ADDRESS; it then sees that the data is erased. Otherwise: is the projector on, in the same network, debugging on? Nothing was done." "проектор не отвечает по Wi-Fi по адресу $ADB_T. Если на нём Lumen OS без ваших приложений, сброс прошлого запуска уже стёр данные и сеть Wi-Fi тоже: подключитесь к Wi-Fi пультом («Настройки > Сеть и Интернет»), включите отладку (инструкция, раздел «${P_NAME}», «Проверьте установку») и запустите установщик снова с --ip АДРЕС; он увидит, что данные стёрты. Иначе: проектор включён, в той же сети, отладка включена? Ничего не сделано.")" ;;
      esac
    fi
    die "$(p6_noconn_text 120)"
  fi
  p6_identity
  [ "$(prop "$(p6_lprop)")" = "$(p6_lval)" ] || die "$(t "the projector does not run Lumen OS for the $P_NAME: no factory reset is sent. Nothing was changed." "на проекторе не Lumen OS для $P_NAME: сброс не отправлен. Ничего не изменено.")"
  # an earlier run asked for the reset and was cut off before it saved that: the check file tells what happened
  if [ -n "$(state_get wipe_marker)" ] && [ "$(p6_marker)" = gone ]; then
    ok "$(t "the data is already erased (the check file written before the factory reset is gone): no second factory reset" "данные уже стёрты (проверочного файла, записанного до сброса, нет): второй сброс не нужен")"
    state_set step_wipe done
    return 0
  fi
  if [ "$ERASE_NOW" != 1 ]; then
    echo
    t "Lumen OS runs on the old data. Next, Android's factory reset ERASES ALL DATA on the projector (apps,
accounts, settings, files, Wi-Fi networks). XGIMI's recovery carries it out, as for Settings > Reset." "Lumen OS работает на старых данных. Дальше сброс Android к заводским настройкам СТИРАЕТ ВСЕ ДАННЫЕ
проектора (приложения, аккаунты, настройки, файлы, сети Wi-Fi). Его выполняет recovery XGIMI, как
при сбросе из настроек."; echo
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was erased" "отменено, ничего не стёрто")"
    ERASE_NOW=1
  fi
  # the proof for 'verify': /data/local/tmp does not survive a real wipe
  tok="lumen-$(date +%Y%m%d%H%M%S)-$$"
  adbs shell "echo $tok > $P6_MARKER" >/dev/null 2>&1
  got=$(adbs shell cat "$P6_MARKER" 2>/dev/null | tr -d '\r')
  [ "$got" = "$tok" ] || die "$(t "could not write the check file $P6_MARKER on the projector; nothing was erased" "не удалось записать проверочный файл $P6_MARKER на проектор; ничего не стёрто")"
  state_set wipe_marker "$tok"
  p6_root; rc=$?
  case "$rc" in
    0) ;;
    2) die "$(t "after 'adb root' the projector did not answer over Wi-Fi again within a minute (adbd restarts with it). If the projector asks: allow debugging from this computer, tick \"Always allow from this computer\" and press OK. Then run the installer again. Nothing was erased." "после 'adb root' проектор не ответил по Wi-Fi снова за минуту (adbd при этом перезапускается). Если проектор спрашивает, разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK. Затем запустите установщик снова. Ничего не стёрто.")" ;;
    *) warn "$(t "adb root is not possible on this system: the installer cannot start the factory reset" "adb root на этой системе невозможен: установщик не может запустить сброс")"
       say "$(t "On the projector: Settings > Device Preferences > Reset > Factory reset." "На проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.")"
       state_set step_wipe manual
       return 0 ;;
  esac
  say "$(t "asking Android for its factory reset" "запускаю сброс Android к заводским настройкам")"
  # --user 0: MasterClearReceiver wipes only the sending user when that is not user 0
  out=$(adbs shell am broadcast --user 0 -a android.intent.action.FACTORY_RESET -p android --receiver-foreground \
        --es android.intent.extra.REASON lumen-install 2>&1 | tr -d '\r')
  case "$out" in
    *"Broadcast completed"*) ;;
    *) die "$(t "the factory reset was not accepted ($out). Your data is unchanged. On the projector: Settings > Device Preferences > Reset > Factory reset." "сброс не принят ($out). Данные не изменены. На проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам.")" ;;
  esac
  # the projector restarts into XGIMI's recovery: it stops answering. Asked on a new connection each time, since
  # the computer's old one can still say 'device' for a while after the projector went away.
  t0=$SECONDS
  while p6_alive; do
    [ "$i" -lt 60 ] && [ $((SECONDS - t0)) -lt 60 ] || die "$(t "the projector still answers a minute after the factory reset was asked for: the factory reset did not start, your data is unchanged. On the projector: Settings > Device Preferences > Reset > Factory reset. To report it, send the logcat lines of MasterClearReceiver and uncrypt." "проектор отвечает и через минуту после запроса сброса: сброс не начался, данные не изменены. На проекторе: Настройки > Настройки устройства > Сброс > Сброс к заводским настройкам. Для отчёта пришлите строки logcat от MasterClearReceiver и uncrypt.")"
    sleep 5; i=$((i + 5))
  done
  adb disconnect "$ADB_T" >/dev/null 2>&1
  state_set step_wipe requested
  WIPED_NOW=1
  echo
  t "The projector restarts into XGIMI's recovery, which erases the data (a few minutes), then starts
Lumen OS clean. The Wi-Fi network is erased too." "Проектор перезагружается в recovery XGIMI, которое стирает данные (несколько минут), и затем
запускает чистую Lumen OS. Сеть Wi-Fi тоже стирается."; echo
  if ask "$(t "Wait for Lumen Home on the projector (up to 10 minutes). Do you see Lumen Home?" "Дождитесь Lumen Home на проекторе (до 10 минут). Lumen Home на экране?")"; then
    ok "$(t "Lumen OS started clean" "Lumen OS запустилась начисто")"
  else
    warn "$(t "Press the power button once. If the picture stays dark for 10 more minutes: guide, section '$P_NAME', 'Something went wrong'." "Нажмите кнопку питания один раз. Если ещё 10 минут изображения нет: инструкция, раздел «${P_NAME}», «Если что-то пошло не так».")"
  fi
}

play6_verify() {  # [seconds]: over Wi-Fi, after the wipe (the user joined Wi-Fi and turned debugging on again)
  local v sec n s flags="" st
  [ -n "$ADB_T" ] || ADB_T=$(state_get ip)
  if [ -z "$ADB_T" ] && [ -n "$SERIAL" ]; then ADB_T=$(p6_discover "$SERIAL"); fi   # only this projector, by its serial
  P6_ST=""
  if [ -z "$ADB_T" ] || ! wifi_connect "${1:-60}"; then
    case "$P6_ST" in
      *unauthorized*)
        warn "$(t "the projector at $ADB_T did not allow this computer. On the projector: allow debugging from this computer, tick \"Always allow from this computer\" and press OK; then run 'verify' again." "проектор по адресу $ADB_T не разрешил отладку с этого компьютера. На проекторе разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK, затем запустите 'verify' снова.")" ;;
      *)
        warn "$(t "no adb connection to the projector over Wi-Fi. After the wipe Wi-Fi and debugging are off: that is normal. To check the install: join Wi-Fi on the projector, turn on debugging (guide, section '$P_NAME', 'Check the install') and run: bash lumen-install.sh verify --ip ADDRESS" "нет связи adb с проектором по Wi-Fi. После сброса Wi-Fi и отладка выключены: это нормально. Чтобы проверить установку, подключите проектор к Wi-Fi, включите отладку (инструкция, раздел «${P_NAME}», «Проверьте установку») и запустите: bash lumen-install.sh verify --ip АДРЕС")" ;;
    esac
    return 0
  fi
  p6_identity
  [ "$(prop sys.boot_completed)" = 1 ] && ok "$(t "started" "загружена")" || warn "sys.boot_completed=$(prop sys.boot_completed)"
  [ "$(prop "$(p6_lprop)")" = "$(p6_lval)" ] || die "$(t "the projector does not run Lumen OS for the $P_NAME" "на проекторе не Lumen OS для $P_NAME")"
  v=$(prop "$P_VERSION_PROP"); sec=$(prop ro.adb.secure)
  if [ "$UNSIGNED" = 0 ] && [ -n "${VERSION:-}" ] && [ "$v" != "$VERSION" ]; then warn "Lumen OS $v ($(t "expected" "ожидалась") $VERSION)"
  else ok "Lumen OS $v ($SERIAL, $ADB_T)"; fi
  [ "$sec" = 1 ] && ok "$(t "debugging asks before a new computer may connect (ro.adb.secure=1)" "отладка спрашивает разрешение для нового компьютера (ro.adb.secure=1)")" || warn "ro.adb.secure=$sec ($(t "a test build" "тестовая сборка"))"
  st=$(state_get step_wipe)
  case "$st" in
    requested|manual|done)
      case "$(p6_marker)" in
        there)
          warn "$(t "the data was NOT erased: the check file from before the factory reset is still there" "данные НЕ стёрты: проверочный файл, записанный до сброса, на месте")"
          if ask "$(t "Erase all data now with Android's factory reset?" "Стереть все данные сейчас сбросом Android к заводским настройкам?")"; then
            ERASE_NOW=0; play6_wipe; return 0
          fi ;;
        gone)
          ok "$(t "data erased (the check file from before the factory reset is gone)" "данные стёрты (проверочного файла, записанного до сброса, нет)")"
          state_set step_wipe done ;;
        *) warn "$(t "the check file of the factory reset could not be read: run 'verify' again" "проверочный файл сброса прочитать не удалось: запустите 'verify' снова")" ;;
      esac
      [ -z "$(adbs shell pm path "$P6_TUNER" 2>/dev/null | tr -d '\r')" ] && ok "$(t "SystemUI Tuner from stock is gone" "SystemUI Tuner со стока удалён")" || warn "$(t "SystemUI Tuner from stock is still installed" "SystemUI Tuner со стока всё ещё установлен")"
      n=$(adbs shell pm list packages -3 2>/dev/null | tr -d '\r' | grep -c '^package:')
      [ "$n" = 0 ] && ok "$(t "no apps from before" "приложений со старой системы нет")" || say "$(t "user apps: $n" "приложений пользователя: $n")" ;;
    keep) ok "$(t "your data was kept (--keep-data)" "ваши данные сохранены (--keep-data)")" ;;
  esac
  for s in "global device_provisioned" "secure user_setup_complete" "secure tv_user_setup_complete"; do
    flags="$flags$(adbs shell settings get $s 2>/dev/null | tr -d '\r')"
  done
  [ "$flags" = 111 ] && ok "$(t "the projector is marked set up (Home and the remote work)" "проектор отмечен как настроенный (Home и пульт работают)")" \
    || warn "$(t "the projector is not marked set up ($flags): Home and the remote may not work; report it" "проектор не отмечен как настроенный ($flags): Home и пульт могут не работать; сообщите об этом")"
  [ -n "$(adbs shell pidof "$P6_APP" 2>/dev/null | tr -d '\r')" ] && ok "$(t "projector app running" "приложение проектора работает")" || warn "$(t "the projector app ($P6_APP) is not running" "приложение проектора ($P6_APP) не запущено")"
  adbs shell dumpsys media.audio_policy 2>/dev/null | grep -q "Output" && ok "$(t "audio outputs present" "аудиовыходы есть")" || warn "$(t "no audio outputs listed" "аудиовыходы не найдены")"
  state_set step_verify done
}

play6_install() {  # write, first start, wipe: each step once, continued where it stopped
  [ "$(state_get step_flash)" = done ] || play6_write
  [ "$(state_get step_boot1)" = done ] || play6_boot1
  if [ -z "$(state_get step_wipe)" ]; then
    if [ "$KEEP_DATA" = 1 ]; then state_set step_wipe keep; else play6_wipe; fi
  fi
}
play6_flash() {  # the 'flash' command: a new write, unless a write waits in fastbootd (then it continues there)
  if ! { [ -n "$(state_get step_fastboot)" ] && [ "$(state_get step_flash)" != done ] && p6_in_fastboot; }; then
    # a finished earlier write leaves nothing to continue: its step_fastboot must not count for this new write
    [ "$(state_get step_flash)" != done ] || p6_write_again
  fi
  play6_install
  play6_done
}
play6_done() {
  local w
  w=$(state_get step_wipe)
  echo
  case "$w" in
    keep)
      [ "$(state_get step_verify)" = done ] || play6_verify 30
      t "Done. Lumen OS runs on the $P_NAME with your data kept." "Готово. Lumen OS работает на $P_NAME, ваши данные сохранены."; echo ;;
    manual)
      t "Lumen OS is written. Finish with the factory reset on the projector (Settings > Device Preferences >
Reset > Factory reset), then join your Wi-Fi in Settings > Network & Internet." "Lumen OS записана. Завершите сбросом на проекторе («Настройки > Настройки устройства > Сброс >
Сброс к заводским настройкам»), затем подключитесь к Wi-Fi в «Настройки > Сеть и Интернет»."; echo ;;
    *)
      # a later run: the projector may answer again (Wi-Fi joined, debugging on): then check it
      if [ "$WIPED_NOW" = 0 ] && [ "$(state_get step_verify)" != done ]; then
        [ -n "$ADB_T" ] || ADB_T=$(state_get ip)
        if [ -n "$ADB_T" ] && wifi_connect 0; then play6_verify 0; echo; fi
      fi
      t "Done. On the projector: Settings > Network & Internet: join your Wi-Fi. To check the install from
this computer later: turn on debugging (guide, section '$P_NAME', 'Check the install') and run:
bash lumen-install.sh verify --ip ADDRESS" "Готово. На проекторе: «Настройки > Сеть и Интернет», подключитесь к своему Wi-Fi. Проверить
установку с компьютера можно позже: включите отладку (инструкция, раздел «${P_NAME}», «Проверьте
установку») и запустите: bash lumen-install.sh verify --ip АДРЕС"; echo ;;
  esac
  echo "  $(t 'Guide' 'Инструкция'): $(p6_guide)"
}
play6_all() {
  release_guard
  [ "$(state_get step_check)" = done ] || play6_check
  if [ -z "$(state_get step_backup)" ] && ! p6_in_fastboot; then
    if ask "$(t "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")"; then cmd_backup; else state_set step_backup skipped; fi
  fi
  play6_install
  play6_done
}

play6_rescue() {  # the projector waits in fastboot mode: write system again, keep the data
  local a saved typed
  KEEP_DATA=1
  check_tools
  FORCE_HASH=1 play6_check_image
  echo
  t "RESCUE ($P_NAME): writes Lumen OS again into system from fastboot mode, WITHOUT erasing data (apps,
accounts, settings and files stay). XGIMI's firmware stays untouched. Use it for a projector with
Lumen OS that waits in fastboot mode (it does not start any more, or a write was cut off)." "РЕМОНТ ($P_NAME): заново записывает Lumen OS в system из режима fastboot, БЕЗ стирания данных
(приложения, аккаунты, настройки и файлы сохраняются). Прошивка XGIMI не затрагивается. Для проектора
с Lumen OS, который ждёт в режиме fastboot (больше не загружается или запись оборвалась)."; echo
  printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
  read -r a
  [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  saved=$(state_get serial)
  say "$(t "Take the USB stick out and connect the projector's USB port to this computer with the A-to-A cable. Waiting for the projector in fastboot mode (up to 15 minutes); the picture may stay dark." "Выньте флешку и соедините USB-порт проектора с компьютером кабелем A–A. Жду проектор в режиме fastboot (до 15 минут); изображения может не быть.")"
  fbd_find 900 || die "$(t "the projector did not appear in fastboot mode within 15 minutes. Cable in the projector's USB port? Nothing was written. If it never gets there: guide, 'Back to stock'." "проектор не появился в режиме fastboot за 15 минут. Кабель в USB-порту проектора? Ничего не записано. Если он туда не попадает: инструкция, «Откат на сток».")"
  # fastbootd cannot tell stock from Lumen OS here (its system-fingerprint is XGIMI's recovery's own, stock on every
  # Play 6), so the saved progress does: this projector last seen on stock with nothing written to it stays stock
  if [ "$SERIAL" = "$saved" ] && [ "$(state_get dev_lumen)" = 0 ] \
     && [ -z "$(state_get step_fastboot)$(state_get step_flash)$(state_get step_rescue)" ]; then
    p6_fb_stop "$(t "this computer last saw this projector on XGIMI's stock system and has written nothing to it: 'rescue' would put Lumen OS over the stock data. To install, run the installer without a command (guide, section '$P_NAME'). If Lumen OS was put on it another way since: bash lumen-install.sh --device play6 reset, then 'rescue' again." "этот компьютер в последний раз видел проектор на родной системе XGIMI и ничего на него не записывал: 'rescue' поставит Lumen OS поверх стоковых данных. Для установки запустите установщик без команды (инструкция, раздел «${P_NAME}»). Если Lumen OS с тех пор поставили на него иначе: bash lumen-install.sh --device play6 reset, затем снова 'rescue'.")"
  fi
  # fastbootd's product name is shared by other XGIMI models: the serial must be this projector's
  if [ -z "$saved" ] || [ "$SERIAL" != "$saved" ]; then
    printf '%s ' "$(t "In fastboot mode: $SERIAL. Type this serial to confirm it is your $P_NAME:" "В режиме fastboot: $SERIAL. Введите этот серийный номер, чтобы подтвердить, что это ваш $P_NAME:")"
    read -r typed
    [ "$typed" = "$SERIAL" ] || p6_fb_stop "$(t "cancelled." "отменено.")"
  fi
  play6_fbd_check
  p6_room
  say "$(t "writing system (about 1 minute). Do not unplug the cable or the power until it is done." "записываю system (около минуты). Не отключайте кабель и питание, пока запись не закончится.")"
  fb_do flash system "$IMG" || die "$(t "system was not written completely. The projector stays in fastboot mode: keep the cable in and run 'rescue' again." "system записан не полностью. Проектор остаётся в режиме fastboot: не вынимайте кабель и запустите 'rescue' снова.")"
  state_set serial "$SERIAL"
  fb reboot >/dev/null 2>&1
  state_set step_rescue "$(date +%Y%m%d-%H%M)"
  p6_cable_out
  ok "$(t "written; your data is kept. If Lumen OS still does not start, the system was not the cause: guide, 'Back to stock' (it erases everything)." "записано; данные сохранены. Если Lumen OS всё равно не загружается, дело не в системе: инструкция, «Откат на сток» (стирает всё).")"
}

# ------------------------------------------------------------------ XGIMI Play 6: gapps and gsf (the Google services add-on)
# The user's own download of MindTheGapps for 32-bit Android 14 TV (only a zip whose sha256 is in
# lib/play6/gapps_allow.txt) goes into the logical partition lumen_gapps in super (no slot suffix), which the
# image's /system/etc/xgimi/lumen_gapps.sh mounts read-only at every start and lays over /system
# (sys.lumen.gapps: none | off | mount_failed | bad:<what> | mounted:<n>). 'gapps --zip FILE':
#   1. checks: the zip (sha256, on this computer), the projector over Wi-Fi (Lumen OS for the Play 6 with the
#      add-on support, no Google built in, adb root); lib/play6/gapps_fill.sh, pushed to the projector with
#      the allow-list and the sysconfig layer, checks the rest (tools, room in /data for the zip). Google
#      already on (mounted): it stops, nothing written ('gapps --remove' first to put them in again);
#   2. gapps_fill.sh 'off' writes /cache/lumen_gapps_off, so that the next starts leave the partition unmounted;
#   3. fastboot mode over the A-to-A cable: room in super (else it stops, nothing written in super), create
#      lumen_gapps with the allow-list's part= size, or resize an existing one to it; restart, cable out;
#   4. Lumen OS back over Wi-Fi, adb root: the zip is pushed, gapps_fill.sh formats the partition (the
#      projector's own mke2fs: no e2fsprogs on the computer), fills it with exactly the allow-listed files
#      (each checked by sha256 and size), unmounts, checks it with e2fsck and removes the 'off' file;
#   5. the data wipe of the install (Android's factory reset, carried out by XGIMI's recovery: play6_wipe),
#      after which the first start sets Google up; --keep-data: a restart, then sys.lumen.gapps must say mounted.
# 'gapps --remove': deletes lumen_gapps in fastboot mode over the cable, then the same wipe (--keep-data: not).
# 'gapps': the add-on's state. 'gsf': the Google Services Framework ID for google.com/android/uncertified.
P6_GAPPS=lumen_gapps
P6_GAPPS_LIB=$HERE/lib/play6

p6_gapps_line() { awk -v z="zip=$1" '$1 == z' "$P6_GAPPS_LIB/gapps_allow.txt" 2>/dev/null | head -n 1; }
p6_gapps_push() {  # fill.sh, the allow-list and the sysconfig layer into GAPPS_DIR on the projector
  local f
  adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1
  adbs shell mkdir -p "$GAPPS_DIR" >/dev/null 2>&1
  for f in gapps_fill.sh:fill.sh gapps_allow.txt:allow.txt lumen-gapps.xml:lumen-gapps.xml; do
    adbs push "$P6_GAPPS_LIB/${f%%:*}" "$GAPPS_DIR/${f#*:}" >/dev/null 2>&1 || die "adb push lib/play6/${f%%:*}"
  done
}
p6_lumen_only() {  # connected: Lumen OS for this projector runs (play6_device said so), else STOP
  [ "$(state_get dev_lumen)" = 1 ] || die "$(t "this projector runs XGIMI's system: install Lumen OS first (guide, section '$P_NAME'). Nothing was written." "на проекторе система XGIMI: сначала установите Lumen OS (инструкция, раздел «${P_NAME}»). Ничего не записано.")"
}
p6_root_or_die() {  # adb root over Wi-Fi, else STOP (nothing written yet)
  local rc
  p6_root; rc=$?
  case "$rc" in
    0) ;;
    2) die "$(t "after 'adb root' the projector did not answer over Wi-Fi again within a minute (adbd restarts with it). If the projector asks: allow debugging from this computer, tick \"Always allow from this computer\" and press OK. Then run the installer again." "после 'adb root' проектор не ответил по Wi-Fi снова за минуту (adbd при этом перезапускается). Если проектор спрашивает, разрешите отладку с этого компьютера, отметьте «Всегда разрешать с этого компьютера» и нажмите OK. Затем запустите установщик снова.")" ;;
    *) die "$(t "adb root is not possible on this system (Lumen OS is a userdebug build: is it really Lumen OS?)" "adb root на этой системе невозможен (Lumen OS собрана как userdebug: точно ли это Lumen OS?)")" ;;
  esac
}
p6_wait_lumen() {  # after a restart: Lumen OS of this projector back over Wi-Fi and started (else STOP with $1)
  local i=0 t0
  say "$(t "waiting for Lumen OS over Wi-Fi (up to 10 minutes)" "жду Lumen OS по Wi-Fi (до 10 минут)")"
  wifi_connect 600 || die "$(t "Lumen OS did not answer over Wi-Fi within 10 minutes. Is the A-to-A cable out of the projector? Press the power button if the picture is dark. Then run the same command again." "Lumen OS не ответила по Wi-Fi за 10 минут. Кабель A–A вынут из проектора? Если изображения нет, нажмите кнопку питания. Затем запустите ту же команду снова.") $1"
  p6_identity
  t0=$SECONDS
  while [ "$(prop sys.boot_completed)" != 1 ]; do
    [ "$i" -lt 300 ] && [ $((SECONDS - t0)) -lt 300 ] || die "$(t "Lumen OS did not finish starting within 5 more minutes. Restart the projector with the power button and run the same command again." "Lumen OS не загрузилась до конца ещё за 5 минут. Перезагрузите проектор кнопкой питания и запустите ту же команду снова.") $1"
    sleep 5; i=$((i + 5))
  done
  [ "$(prop "$(p6_lprop)")" = "$(p6_lval)" ] || die "$(t "the projector does not run Lumen OS for the $P_NAME" "на проекторе не Lumen OS для $P_NAME")"
}
p6_to_fastboot() {  # Lumen OS runs: cable in, restart into fastboot mode, check it there
  local a
  echo
  t "Take the USB stick out of the projector's USB port, if one is in, and connect that port to this
computer with the USB A-to-A cable. Keep the power adapter connected." "Выньте флешку из USB-порта проектора, если она там, и соедините этот порт с компьютером кабелем
USB A–A. Блок питания оставьте подключённым."; echo
  printf '%s ' "$(t "Press Enter when the cable is in." "Когда кабель вставлен, нажмите Enter.")"
  read -r a
  say "$(t "restarting into fastboot mode (about 30 seconds)" "перезагрузка в режим fastboot (около 30 секунд)")"
  if ! adbs reboot fastboot >/dev/null 2>&1 && p6_alive; then
    die "$(t "adb reboot fastboot failed: the projector still runs its system. Unplug the A-to-A cable and run the same command again." "adb reboot fastboot не удался: проектор работает в своей системе. Выньте кабель A–A и запустите ту же команду снова.")"
  fi
  adb disconnect "$ADB_T" >/dev/null 2>&1
  p6_fb_wait 180 || die "$(t "the projector did not appear on the cable within 3 minutes. Is the cable in the projector's USB port (not a hub)? On Windows: the Google USB Driver. On Linux: udev permissions ('no permissions' in 'fastboot devices'). Unplug the cable, press the power button if the picture is dark, and run the same command again." "проектор не появился на кабеле за 3 минуты. Кабель в USB-порту проектора (не через хаб)? На Windows нужен Google USB Driver. На Linux права udev ('no permissions' в 'fastboot devices'). Выньте кабель, нажмите кнопку питания, если изображения нет, и запустите ту же команду снова.")"
  play6_fbd_check
}
p6_super_free() {  # bytes of super that no logical partition uses (each rounded up to 1 MiB), minus SUPER_RESERVE
  local sup p sz used=0
  sup=$(gv_size super)
  [ -n "$sup" ] || { echo 0; return; }
  for p in $(printf '%s\n' "$GV" | sed -n 's/^(bootloader) is-logical:\([^:]*\):yes.*/\1/p' | sort -u); do
    sz=$(gv_size "$p"); [ -n "$sz" ] || continue
    used=$(( used + (sz + 1048575) / 1048576 * 1048576 ))
  done
  echo $(( sup - used - P_RESERVE ))
}
p6_gapps_wipe() {  # the data wipe of the install (Android's factory reset), right after a 'gapps' change
  # play6_wipe works on Lumen OS that has started (step_boot1) and asks again unless ERASE was typed (ERASE_NOW).
  # A check file of an earlier wipe must not count for this one.
  state_set step_boot1 done; state_set wipe_marker ""; state_set step_wipe ""; state_set step_verify ""
  ERASE_NOW=1
  play6_wipe
}

play6_gapps_install() {
  local zip=$GAPPS_ZIP zsha zl name part sc have o a cur free mb f i
  [ -f "$zip" ] || die "$(t "no file $zip" "нет файла $zip")"
  for f in gapps_allow.txt gapps_fill.sh lumen-gapps.xml; do
    [ -f "$P6_GAPPS_LIB/$f" ] || die "internal: lib/play6/$f is missing: re-download the installer of the $P_NAME release"
  done
  check_tools
  say "$(t "checking $(basename "$zip")" "проверяю $(basename "$zip")")"
  zsha=$(sha256 "$zip")
  zl=$(p6_gapps_line "$zsha")
  [ -n "$zl" ] || die "$(t "$(basename "$zip") is not the MindTheGapps file this installer accepts for the $P_NAME (sha256 ${zsha:0:16}...). Download exactly the file the guide names, section '$P_NAME', 'Google services'" "$(basename "$zip") не тот файл MindTheGapps, который установщик принимает для $P_NAME (sha256 ${zsha:0:16}...). Скачайте ровно тот файл, что указан в инструкции, раздел «${P_NAME}», «Сервисы Google»"). $(t 'Nothing was written.' 'Ничего не записано.')"
  name=$(printf '%s\n' "$zl" | cut -d' ' -f2); part=$(gapps_field "$zl" part); sc=$(gapps_field "$zl" sysconfig)
  case "$part" in ''|*[!0-9]*) die "internal: lib/play6/gapps_allow.txt part=" ;; esac
  [ "$(sha256 "$P6_GAPPS_LIB/lumen-gapps.xml")" = "$sc" ] || die "internal: lib/play6/lumen-gapps.xml is not the allow-listed one: re-download the installer"
  mb=$((part / 1048576))
  ok "$name, sha256 ${zsha:0:16}..."
  if ! play6_adb 60; then
    # an earlier run may have stopped while the projector waited in fastboot mode on the cable
    if p6_in_fastboot; then
      fb reboot >/dev/null 2>&1
      die "$(t "the projector was waiting in fastboot mode (an earlier run stopped there) and restarts now. Unplug the A-to-A cable, wait for Lumen OS and run the same command again." "проектор ждал в режиме fastboot (прошлый запуск остановился там) и сейчас перезагружается. Выньте кабель A–A, дождитесь Lumen OS и запустите ту же команду снова.")"
    fi
    die "$(p6_noconn_text)"
  fi
  play6_device
  p6_lumen_only
  have=$(prop sys.lumen.gapps)
  case "$have" in
    '') die "$(t "the Lumen OS on the projector has no Google services add-on support: install a newer Lumen OS for the $P_NAME first (guide, section '$P_NAME'). Nothing was written." "в Lumen OS на проекторе нет поддержки дополнения с сервисами Google: сначала поставьте более новую Lumen OS для $P_NAME (инструкция, раздел «${P_NAME}»). Ничего не записано.")" ;;
    mounted:*)
      ok "$(t "Google services are already added and on: nothing to do. To put them in again, first: bash lumen-install.sh gapps --remove. Nothing was written." "сервисы Google уже добавлены и включены: делать нечего. Чтобы поставить их заново, сначала: bash lumen-install.sh gapps --remove. Ничего не записано.")"
      return 0 ;;
    none) [ -z "$(prop ro.com.google.gmsversion)" ] || die "$(t "this projector runs Lumen OS with Google services built in: nothing to add" "на проекторе Lumen OS со встроенными сервисами Google: добавлять нечего")" ;;
  esac
  p6_root_or_die
  echo
  if [ "$KEEP_DATA" = 1 ]; then
    t "Adding Google services: the installer creates the partition $P6_GAPPS ($mb MB) in super in fastboot
mode (over the A-to-A cable), restarts the projector a few times and copies the Google apps from
$name into it, each one checked. Your data stays. system, vendor, boot, recovery and XGIMI's
calibration stay untouched." "Добавляю сервисы Google: установщик в режиме fastboot (через кабель A–A) создаёт раздел $P6_GAPPS
($mb МБ) в super, несколько раз перезагружает проектор и копирует в этот раздел приложения Google из
$name, проверяя каждое. Ваши данные остаются. System, vendor, boot, recovery и калибровка XGIMI не
затрагиваются."; echo
    say "$(t "--keep-data: sign in to Google later in Settings > Accounts. Some Google features (the phone remote) may work fully only after a factory reset." "--keep-data: в Google войдите потом через Настройки > Аккаунты. Некоторые функции Google (пульт на телефоне) могут полностью заработать только после сброса к заводским настройкам.")"
    printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
    read -r a
    [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  else
    t "Adding Google services: the installer creates the partition $P6_GAPPS ($mb MB) in super in fastboot
mode (over the A-to-A cable), restarts the projector a few times, copies the Google apps from $name
into it, each one checked, and then ERASES ALL DATA on the projector (apps, accounts, settings, files,
Wi-Fi networks) with Android's factory reset, as at the install: the first start then sets up Google
services properly. system, vendor, boot, recovery and XGIMI's calibration stay untouched." "Добавляю сервисы Google: установщик в режиме fastboot (через кабель A–A) создаёт раздел $P6_GAPPS
($mb МБ) в super, несколько раз перезагружает проектор, копирует в этот раздел приложения Google из
$name, проверяя каждое, и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки,
файлы, сети Wi-Fi) сбросом Android к заводским настройкам, как при установке: тогда первый запуск
правильно настроит сервисы Google. System, vendor, boot, recovery и калибровка XGIMI не затрагиваются."; echo
    if [ "$(state_get step_backup)" != done ] && ask "$(t "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")"; then
      cmd_backup
    fi
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  fi
  # 1. the fill script's own checks on the projector, then the 'off' file (the next starts leave the partition alone)
  p6_gapps_push
  o=$(gapps_sh pre "$zsha" "$(wc -c < "$zip" | tr -d ' ')")
  case "$o" in "OK pre "*) ;; *) adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1; die "$(t "the projector is not ready" "проектор не готов"): ${o#FAIL }. $(t 'Nothing was written.' 'Ничего не записано.')" ;; esac
  o=$(gapps_sh off "$zsha")
  adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1
  case "$o" in "OK off"*) ;; *) die "$(t "the projector is not ready" "проектор не готов"): ${o#FAIL }. $(t 'Nothing was written.' 'Ничего не записано.')" ;; esac
  # 2. fastboot mode: the partition
  p6_to_fastboot
  free=$(p6_super_free); cur=$(gv_size "$P6_GAPPS"); cur=$(( ${cur:-0} ))
  if [ $(( free + (cur + 1048575) / 1048576 * 1048576 )) -lt "$part" ]; then
    p6_fb_stop "$(t "not enough free space in super: $(( (free + cur) / 1048576 )) MB, the Google services need $mb MB." "в super мало места: свободно $(( (free + cur) / 1048576 )) МБ, сервисам Google нужно $mb МБ.")"
  fi
  if [ "$cur" = 0 ]; then
    say "$(t "creating $P6_GAPPS ($mb MB)" "создаю $P6_GAPPS ($mb МБ)")"
    fb_do create "$P6_GAPPS" "$part" || p6_fb_stop "$(t "$P6_GAPPS could not be created." "$P6_GAPPS создать не удалось.")"
  elif [ "$cur" != "$part" ]; then
    say "$(t "resizing $P6_GAPPS to $mb MB" "меняю размер $P6_GAPPS на $mb МБ")"
    fb_do resize "$P6_GAPPS" "$part" || p6_fb_stop "$(t "$P6_GAPPS could not be resized." "размер $P6_GAPPS изменить не удалось.")"
  else
    ok "$(t "$P6_GAPPS is there ($mb MB)" "$P6_GAPPS уже есть ($mb МБ)")"
  fi
  fb reboot >/dev/null 2>&1
  p6_cable_out
  # 3. filled over Wi-Fi as root
  p6_wait_lumen "$(t "$P6_GAPPS is still empty, so Lumen OS starts without Google services." "$P6_GAPPS пока пуст, Lumen OS запускается без сервисов Google.")"
  p6_root_or_die
  [ "$(prop sys.lumen.gapps)" = off ] || die "$(t "the projector did not leave $P6_GAPPS unmounted (sys.lumen.gapps=$(prop sys.lumen.gapps)). Restart it and run the same command again." "проектор не оставил $P6_GAPPS неподключённым (sys.lumen.gapps=$(prop sys.lumen.gapps)). Перезагрузите его и запустите ту же команду снова.")"
  p6_gapps_push
  say "$(t "copying $name to the projector over Wi-Fi (a few minutes)" "копирую $name на проектор по Wi-Fi (несколько минут)")"
  adbs push "$zip" "$GAPPS_DIR/gapps.zip" >/dev/null 2>&1 \
    || { adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1; die "$(t "adb push of the zip failed. Lumen OS starts without Google services. Run the same command again." "adb push архива не удался. Lumen OS запускается без сервисов Google. Запустите ту же команду снова.")"; }
  say "$(t "formatting $P6_GAPPS and copying the Google apps into it, each one checked (2 to 4 minutes)" "форматирую $P6_GAPPS и копирую в него приложения Google, проверяя каждое (2-4 минуты)")"
  o=$(gapps_sh fill "$zsha")
  adbs shell rm -rf "$GAPPS_DIR" >/dev/null 2>&1
  case "$o" in
    "OK fill "*) ok "$P6_GAPPS: ${o#OK fill } $(t "of" "из") $name" ;;
    *) die "$(t "the Google services were not added" "сервисы Google не добавлены"): ${o#FAIL }. $(t "Lumen OS starts without them as before. Run the same command again, or 'gapps --remove'." "Lumen OS запускается без них, как раньше. Запустите ту же команду снова или 'gapps --remove'.")" ;;
  esac
  state_set gapps "$name"
  # 4. the wipe, so that the first start sets up Google services
  if [ "$KEEP_DATA" = 1 ]; then
    say "$(t "restarting" "перезагрузка")"
    adbs reboot >/dev/null 2>&1
    i=0; while p6_alive && [ "$i" -lt 60 ]; do sleep 5; i=$((i + 5)); done   # until it goes away
    adb disconnect "$ADB_T" >/dev/null 2>&1
    p6_wait_lumen ""
    case "$(prop sys.lumen.gapps)" in
      mounted:*) ok "$(t "Google services are on. Sign in: Settings > Accounts. If Google refuses the sign-in, register the GSF ID (bash lumen-install.sh gsf)." "сервисы Google включены. Вход: Настройки > Аккаунты. Если Google не пускает, зарегистрируйте GSF ID (bash lumen-install.sh gsf).")" ;;
      *) warn "$(t "the projector does not use the Google services yet (sys.lumen.gapps=$(prop sys.lumen.gapps)); check with 'bash lumen-install.sh gapps'" "проектор пока не использует сервисы Google (sys.lumen.gapps=$(prop sys.lumen.gapps)); проверьте: 'bash lumen-install.sh gapps'")" ;;
    esac
  else
    p6_gapps_wipe
    ok "$(t "done. Join Wi-Fi on the projector, then sign in to Google: Settings > Accounts > Add account. If Google refuses the sign-in, register the GSF ID (guide, section '$P_NAME', 'Google services')." "готово. Подключите проектор к Wi-Fi и войдите в Google: Настройки > Аккаунты > Добавить аккаунт. Если Google не пускает, зарегистрируйте GSF ID (инструкция, раздел «${P_NAME}», «Сервисы Google»).")"
  fi
  state_set step_gapps done
}

play6_gapps_remove() {
  local a removed=0
  check_tools
  play6_adb 60 || die "$(p6_noconn_text)"
  play6_device
  p6_lumen_only
  case "$(prop sys.lumen.gapps)" in
    none|'') ok "$(t "this projector has no $P6_GAPPS: nothing to remove. Nothing was written." "на проекторе нет $P6_GAPPS: удалять нечего. Ничего не записано.")"
             state_set gapps ""; return 0 ;;
  esac
  echo
  if [ "$KEEP_DATA" = 1 ]; then
    t "Removing the Google services you added: the installer deletes the partition $P6_GAPPS in fastboot
mode (over the A-to-A cable) and restarts. Your data stays, but Google apps updated from Play stay
behind as apps that may not work: a factory reset removes them." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot (через кабель A–A) удаляет
раздел $P6_GAPPS и перезагружает проектор. Данные остаются, но обновлённые из Play приложения Google
останутся и могут не работать: их уберёт сброс к заводским настройкам."; echo
    printf '%s ' "$(t "Type WRITE to continue:" "Введите WRITE, чтобы продолжить:")"
    read -r a
    [ "$a" = WRITE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  else
    t "Removing the Google services you added: the installer deletes the partition $P6_GAPPS in fastboot
mode (over the A-to-A cable) and then ERASES ALL DATA on the projector (apps, accounts, settings,
files, Wi-Fi networks) with Android's factory reset, as at the install. system, vendor, boot,
recovery and XGIMI's calibration stay untouched." "Удаляю добавленные вами сервисы Google: установщик в режиме fastboot (через кабель A–A) удаляет
раздел $P6_GAPPS и затем СТИРАЕТ ВСЕ ДАННЫЕ проектора (приложения, аккаунты, настройки, файлы, сети
Wi-Fi) сбросом Android к заводским настройкам, как при установке. System, vendor, boot, recovery и
калибровка XGIMI не затрагиваются."; echo
    printf '%s ' "$(t "Type ERASE to continue:" "Введите ERASE, чтобы продолжить:")"
    read -r a
    [ "$a" = ERASE ] || die "$(t "cancelled, nothing was written" "отменено, ничего не записано")"
  fi
  p6_to_fastboot
  if [ -n "$(gv_size "$P6_GAPPS")" ]; then
    fb_do delete "$P6_GAPPS" || p6_fb_stop "$(t "$P6_GAPPS could not be deleted." "$P6_GAPPS удалить не удалось.")"
    removed=1; ok "$(t "$P6_GAPPS deleted" "$P6_GAPPS удалён")"
  else
    ok "$(t "no $P6_GAPPS in super: nothing to remove" "в super нет $P6_GAPPS: удалять нечего")"
  fi
  fb reboot >/dev/null 2>&1
  p6_cable_out
  state_set gapps ""
  if [ "$removed" = 1 ] && [ "$KEEP_DATA" = 0 ]; then
    p6_wait_lumen ""
    p6_gapps_wipe
  fi
  ok "$(t "the projector starts Lumen OS without Google services" "проектор загружает Lumen OS без сервисов Google")"
}

play6_gapps_status() {
  local v
  play6_adb 60 || die "$(p6_noconn_text)"
  play6_device
  p6_lumen_only
  v=$(prop sys.lumen.gapps)
  case "$v" in
    mounted:*) ok "$(t "Google services added by you: on" "добавленные вами сервисы Google: включены") ($(prop ro.com.google.gmsversion))" ;;
    none)
      if [ -n "$(prop ro.com.google.gmsversion)" ]; then ok "$(t "this Lumen OS has Google services built in" "в этой Lumen OS сервисы Google встроены")"
      else say "$(t "no Google services added. To add them: bash lumen-install.sh gapps --zip FILE (guide, section '$P_NAME', 'Google services')" "сервисы Google не добавлены. Как добавить: bash lumen-install.sh gapps --zip ФАЙЛ (инструкция, раздел «${P_NAME}», «Сервисы Google»)")"; fi ;;
    off) warn "$(t "the Google services are off: a 'gapps --zip' run did not finish (the file /cache/lumen_gapps_off). Run 'gapps --zip FILE' again, or 'gapps --remove'." "сервисы Google выключены: запуск 'gapps --zip' не завершился (файл /cache/lumen_gapps_off). Запустите 'gapps --zip ФАЙЛ' ещё раз или 'gapps --remove'.")" ;;
    '') say "$(t "this Lumen OS has no Google services add-on support" "в этой Lumen OS нет поддержки дополнения с сервисами Google")" ;;
    *) warn "$(t "the Google services you added cannot be used ($v): run 'gapps --zip FILE' again, or 'gapps --remove'" "добавленные сервисы Google не могут использоваться ($v): запустите 'gapps --zip ФАЙЛ' ещё раз или 'gapps --remove'")" ;;
  esac
}

play6_gapps() {
  [ -z "$GAPPS_ZIP" ] || [ "$GAPPS_REMOVE" = 0 ] || die "gapps: --zip or --remove, not both"
  if [ "$GAPPS_REMOVE" = 1 ]; then play6_gapps_remove
  elif [ -n "$GAPPS_ZIP" ]; then play6_gapps_install
  else play6_gapps_status; fi
}

play6_gsf() {
  local id
  check_tools
  play6_adb 60 || die "$(p6_noconn_text)"
  play6_device
  p6_lumen_only
  if [ -z "$(adbs shell pm path com.google.android.gsf 2>/dev/null | tr -d '\r')" ]; then
    ok "$(t "this Lumen OS has no Google services: nothing to register" "в этой Lumen OS нет сервисов Google: регистрировать нечего")"
    say "$(t "To add them: bash lumen-install.sh gapps --zip FILE (guide, section '$P_NAME', 'Google services')" "Как их добавить: bash lumen-install.sh gapps --zip ФАЙЛ (инструкция, раздел «${P_NAME}», «Сервисы Google»)")"
    return 0
  fi
  p6_root_or_die
  id=$(adbs shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db \"select value from main where name='android_id';\"" 2>/dev/null | tr -d '\r')
  [ -n "$id" ] || die "$(t "no GSF ID yet: connect the projector to the internet, wait two minutes, try again" "GSF ID ещё нет: подключите проектор к интернету, подождите пару минут и повторите")"
  echo "GSF ID: $id"
  echo "$(t "Register it at https://www.google.com/android/uncertified (signed in with your Google account), wait 10-30 minutes, then sign in on the projector." "Зарегистрируйте его на https://www.google.com/android/uncertified (войдя в свой аккаунт Google), подождите 10–30 минут и войдите на проекторе.")"
}

# ------------------------------------------------------------------ stick (read-only check of a restore stick)
p6_md5() { if command -v md5sum >/dev/null 2>&1; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi; }
stick_check() {  # DIR: the stock-restore USB stick of this projector, read only (it never formats anything)
  local m=$STICK n f bad=0 sum ver dev fs pt parts src disk
  [ -n "$P_RESTORE_BIN" ] || die "$(t "'stick' checks the restore stick of the $(dev_name play6): bash lumen-install.sh --device play6 stick FOLDER" "'stick' проверяет флешку восстановления $(dev_name play6): bash lumen-install.sh --device play6 stick ПАПКА")"
  [ -n "$m" ] || die "$(t "which stick? bash lumen-install.sh stick FOLDER (macOS: /Volumes/NAME, Linux: /media/${USER:-you}/NAME)" "какая флешка? bash lumen-install.sh stick ПАПКА (macOS: /Volumes/ИМЯ, Linux: /media/${USER:-you}/ИМЯ)")"
  [ -d "$m" ] || die "$(t "$m is not a folder" "$m не папка")"
  say "$(t "checking the stock restore stick $m (read only)" "проверяю флешку восстановления $m (только чтение)")"
  # the partition table and the file system (MBOOT reads only MBR + one FAT32 partition)
  if command -v diskutil >/dev/null 2>&1; then
    dev=$(diskutil info "$m" 2>/dev/null | sed -n 's/^ *Part of Whole: *//p' | head -1)
    fs=$(diskutil info "$m" 2>/dev/null | sed -n 's/^ *File System Personality: *//p' | head -1)
    pt=$(diskutil info "$dev" 2>/dev/null | sed -n 's/^ *Content (IOContent): *//p' | head -1)
    parts=$(diskutil list "$dev" 2>/dev/null | grep -cE '^ +[1-9][0-9]*:')
    case "$pt" in FDisk_partition_scheme) pt=mbr ;; GUID_partition_scheme) pt=gpt ;; esac
    case "$fs" in *FAT32*) fs=fat32 ;; esac
  elif command -v lsblk >/dev/null 2>&1 && command -v findmnt >/dev/null 2>&1; then
    src=$(findmnt -no SOURCE --target "$m" 2>/dev/null | head -1)
    disk=$(lsblk -no PKNAME "$src" 2>/dev/null | head -1)
    pt=$(lsblk -no PTTYPE "$src" 2>/dev/null | head -1)
    fs="$(lsblk -no FSTYPE "$src" 2>/dev/null | head -1) $(lsblk -no FSVER "$src" 2>/dev/null | head -1)"
    parts=$(lsblk -nlo TYPE "/dev/$disk" 2>/dev/null | grep -c '^part')
    case "$pt" in dos) pt=mbr ;; esac
    case "$fs" in *FAT32*|"vfat ") fs=fat32 ;; esac
  else
    pt=""
  fi
  if [ -z "$pt" ]; then warn "$(t "the partition table could not be read on this computer: make sure it is MBR with one FAT32 partition" "схему разделов на этом компьютере прочитать не удалось: убедитесь, что это MBR с одним разделом FAT32")"
  else
    [ "$pt" = mbr ] && ok "$(t "partition table: MBR" "схема разделов: MBR")" || { warn "$(t "partition table '$pt': it must be MBR (master boot record), not GPT" "схема разделов '$pt': нужна MBR (главная загрузочная запись), не GPT")"; bad=1; }
    [ "$parts" = 1 ] && ok "$(t "one partition" "один раздел")" || { warn "$(t "$parts partitions: there must be exactly one" "разделов: $parts, а нужен ровно один")"; bad=1; }
    [ "$fs" = fat32 ] && ok "$(t "file system: FAT32" "файловая система: FAT32")" || { warn "$(t "file system '$fs': it must be FAT32" "файловая система '$fs': нужна FAT32")"; bad=1; }
  fi
  # exactly the firmware file and an empty factory.txt (folders the computer makes itself do no harm)
  for f in "$m"/* "$m"/.[!.]* "$m"/..?*; do
    [ -e "$f" ] || continue
    n=$(basename "$f")
    case "$n" in
      "$P_RESTORE_BIN"|factory.txt|.fseventsd|.Spotlight-V100|.Trashes|.TemporaryItems|"System Volume Information") ;;
      ._*) warn "$(t "$n: a macOS helper file; delete it, and copy with 'cp -X' next time" "$n: служебный файл macOS; удалите его, а копируйте командой 'cp -X'")"; bad=1 ;;
      *) warn "$(t "$n: nothing else may be on the stick" "$n: на флешке не должно быть ничего лишнего")"; bad=1 ;;
    esac
  done
  if [ -f "$m/factory.txt" ]; then
    [ -s "$m/factory.txt" ] && { warn "$(t "factory.txt must be empty" "factory.txt должен быть пустым")"; bad=1; } || ok "$(t "factory.txt, empty" "factory.txt, пустой")"
  else warn "$(t "no factory.txt: create an empty file with that name" "нет factory.txt: создайте пустой файл с таким именем")"; bad=1; fi
  if [ -f "$m/$P_RESTORE_BIN" ]; then
    say "$(t "checking $P_RESTORE_BIN (md5, about a minute)" "проверяю $P_RESTORE_BIN (md5, около минуты)")"
    sum=$(p6_md5 "$m/$P_RESTORE_BIN")
    ver=$(printf '%s\n' $P_RESTORE_MD5 | awk -F: -v s="$sum" '$1 == s { print $2 }')
    if [ -n "$ver" ]; then ok "$P_RESTORE_BIN: $(t "XGIMI firmware" "прошивка XGIMI") $ver (md5 $sum)"
    else warn "$(t "$P_RESTORE_BIN is not a firmware file the installer knows (md5 $sum): download it again" "$P_RESTORE_BIN не известный установщику файл прошивки (md5 $sum): скачайте его заново")"; bad=1; fi
  else warn "$(t "no $P_RESTORE_BIN on the stick (exactly this name)" "на флешке нет $P_RESTORE_BIN (ровно с таким именем)")"; bad=1; fi
  [ "$bad" = 0 ] || die "$(t "the stick is not ready: fix the lines marked ! (guide, section '$P_NAME', 'Back to stock')" "флешка не готова: исправьте строки с ! (инструкция, раздел «${P_NAME}», «Откат на сток»)")"
  ok "$(t "the stick is ready. Restore: power cable out, stick in the projector's USB port, hold the power button, plug the power in and keep holding about 5 seconds (guide)." "флешка готова. Восстановление: отключите питание, вставьте флешку в USB-порт проектора, зажмите кнопку питания, подключите питание и держите кнопку около 5 секунд (инструкция).")"
}

play6_not_needed() {  # the Z9X commands the Play 6 has no use for
  die "$(t "'$1' is not needed on the $P_NAME. Nothing was done." "на $P_NAME шаг '$1' не нужен. Ничего не сделано.")"
}

# ------------------------------------------------------------------ main
cmd_all() {
  [ "$(state_get step_check)" = done ] || cmd_check
  if [ "$(state_get step_backup)" != done ]; then
    if ask "$(t "Make a backup of your apps and files first (recommended)?" "Сначала сделать копию ваших приложений и файлов (рекомендуется)?")"; then cmd_backup; else state_set step_backup skipped; fi
  fi
  [ "$BLOBS" != partition ] || [ "$(state_get step_blobs)" = done ] || cmd_blobs
  [ "$(state_get step_vbmeta)" = done ] || cmd_vbmeta
  [ "$(state_get step_flash)" = done ] || cmd_flash
  [ "$(state_get step_verify)" = done ] || cmd_verify
  echo
  if [ "$(state_get image_gms)" = 0 ]; then
    t "Done. On the projector: choose the language, Wi-Fi and the rest of the setup. Install apps from a
USB stick: Lumen Home > Apps > Install from USB. Google services can be added later: 'gapps' (guide)." "Готово. На проекторе выберите язык, Wi-Fi и пройдите настройку. Приложения ставятся с
USB-накопителя: Lumen Home > Приложения > Установить с USB. Сервисы Google можно добавить позже: 'gapps' (инструкция)."; echo
  else
    t "Done. On the projector: choose the language, Wi-Fi and the rest of the setup. For Google sign-in,
register the GSF ID first: bash lumen-install.sh gsf" "Готово. На проекторе выберите язык, Wi-Fi и пройдите настройку. Для входа в Google сначала
зарегистрируйте GSF ID: bash lumen-install.sh gsf"; echo
  fi
}

CMD=""
while [ $# -gt 0 ]; do
  case "$1" in
    --image) IMG=$2; shift ;;
    --image=*) IMG=${1#*=} ;;
    --serial) SERIAL=$2; shift ;;
    --keep-data) KEEP_DATA=1 ;;
    --wipe=*) WIPE=${1#*=} ;;
    --lang) LANG_UI=$2; shift ;;
    --lang=*) LANG_UI=${1#*=} ;;
    --yes) YES=1 ;;
    --blobs=*) BLOBS=${1#*=} ;;
    --no-blobs) BLOBS=none ;;
    --unsigned-image) UNSIGNED=1 ;;
    --zip) GAPPS_ZIP=$2; shift ;;
    --zip=*) GAPPS_ZIP=${1#*=} ;;
    --remove) GAPPS_REMOVE=1 ;;
    --ip) IP=${2-}; IP_GIVEN=1; shift ;;
    --ip=*) IP=${1#*=}; IP_GIVEN=1 ;;
    --device) DEVICE_OPT=${2-}; shift ;;
    --device=*) DEVICE_OPT=${1#*=} ;;
    -h|--help) awk 'NR == 1 { next } /^set -u$/ { exit } { print }' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) if [ "$CMD" = stick ] && [ -z "$STICK" ]; then STICK=$1; else CMD=$1; fi ;;
  esac
  shift
done
case "$WIPE" in format|factory-reset) ;; *) die "--wipe=format|factory-reset" ;; esac
case "$BLOBS" in partition|embedded|none) ;; *) die "--blobs=partition|embedded|none (--no-blobs)" ;; esac
case "${CMD:-all}" in gapps) ;; *) [ -z "$GAPPS_ZIP" ] && [ "$GAPPS_REMOVE" = 0 ] || die "--zip / --remove belong to 'gapps'" ;; esac
case "$DEVICE_OPT" in ''|z9x|play6) ;; *) die "--device z9x|play6" ;; esac
if [ "$IP_GIVEN" = 1 ]; then
  case "$IP" in ''|*[!A-Za-z0-9.:_-]*|:*|*:) die "$(t "--ip: '$IP' is not an address (for example --ip 192.168.x.y, as the projector's network settings show it)" "--ip: '$IP' не адрес (например --ip 192.168.x.y, как в настройках сети проектора)")" ;; esac
  case "$IP" in *:*) ADB_T=$IP ;; *) ADB_T=$IP:5555 ;; esac
fi
case "${CMD:-all}" in   # the saved progress only: no projector, no profile needed
  status)
    shown=0
    if [ "$DEVICE_OPT" != play6 ] && [ -f "$STATE_DIR/state" ]; then cat "$STATE_DIR/state"; shown=1; fi
    if [ "$DEVICE_OPT" != z9x ] && [ -f "$STATE_DIR/play6/state" ]; then echo "$(dev_name play6):"; sed 's/^/  /' "$STATE_DIR/play6/state"; shown=1; fi
    [ "$shown" = 1 ] || echo "$(t "no saved progress" "сохранённого прогресса нет")"
    exit 0 ;;
  reset)
    case "$DEVICE_OPT" in
      play6) rm -rf "$STATE_DIR/play6" ;;
      z9x) [ -d "$STATE_DIR" ] && find "$STATE_DIR" -mindepth 1 -maxdepth 1 ! -name play6 -exec rm -rf {} + ;;
      *) rm -rf "$STATE_DIR" ;;
    esac
    echo "$(t "progress forgotten" "прогресс сброшен")"
    exit 0 ;;
esac
resolve_device
SERIAL_FROM_STATE=0
if [ -z "$SERIAL" ]; then SERIAL=$(state_get serial); [ -n "$SERIAL" ] && SERIAL_FROM_STATE=1; fi
case "${CMD:-all}" in all|check|flash|rescue) release_guard ;; esac
if [ "$PROJECTOR" = play6 ]; then
  case "${CMD:-all}" in
    all) play6_all ;;
    check) play6_check ;;
    backup) cmd_backup ;;
    flash) play6_flash ;;
    verify) play6_verify 60 ;;
    rescue) play6_rescue ;;
    stick) stick_check ;;
    gapps) play6_gapps ;;
    gsf) play6_gsf ;;
    blobs|vbmeta) play6_not_needed "$CMD" ;;
    *) die "unknown command $CMD (see --help)" ;;
  esac
  exit 0
fi
case "${CMD:-all}" in
  all) cmd_all ;;
  check) cmd_check ;;
  backup) cmd_backup ;;
  blobs) cmd_blobs ;;
  vbmeta) cmd_vbmeta ;;
  flash) cmd_flash ;;
  verify) cmd_verify ;;
  gsf) cmd_gsf ;;
  rescue) cmd_rescue ;;
  gapps) cmd_gapps ;;
  stick) stick_check ;;
  *) die "unknown command $CMD (see --help)" ;;
esac
