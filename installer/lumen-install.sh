#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer for the XGIMI Z9X (macOS and Linux; bash 3.2 compatible).
#
#   bash lumen-install.sh [options] [command]
#
# Commands (without one: continue the install from where it stopped):
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
#
# What it writes, ever: system_<slot>, z9x_blobs_<slot> (create/resize/flash), z9x_gapps_<slot> ('gapps':
# create/resize/format, filled over adb as root, delete), and formats userdata, metadata and cache. It may
# delete only *-cow leftovers and XGIMI's unused product_<slot> and system_ext_<slot>. Every other
# partition name is refused by fb_allowed(). 'rescue' may also finish
# ('snapshot-update merge') or cancel ('snapshot-update cancel') an unfinished Lumen OS update of the
# slot it repairs, through fb_snapshot(), and never formats anything. It NEVER touches vbmeta,
# boot, vendor, dtbo, mboot, persist, xgimi*, tvconfig, misc, frp or the other slot, and never runs
# 'fastboot -w' or 'fastboot erase' (proven boot hang on this projector).
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
STATE_DIR=${LUMEN_STATE_DIR:-$HOME/.lumen-installer}
BACKUP_ROOT=${LUMEN_BACKUP_DIR:-$HOME/Lumen-backup}
MODEL_CODE=G0082
BOARD=mt9952
FB_PRODUCT=mt5877
# v6.15.19 (2025-10-14): same kernel, modules, projector HAL (gmpf) and PM51 firmware as v6.15.58;
# only hwcomposer, libwhitebalance and XGIMI apps differ (z9x-firmware-dump/v61519, 2026-10-08).
VENDOR_OK="v6.15.58 v6.15.19"
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
[ -f "$HERE/release.conf" ] && . "$HERE/release.conf"   # BLOBS=partition|embedded, VERSION=...
BLOBS=${BLOBS_ENV:-${BLOBS:-partition}}
FORCE_HASH=0
GAPPS_ZIP=""
GAPPS_REMOVE=0

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
state_get() { [ -f "$STATE_DIR/state" ] && sed -n "s/^$1=//p" "$STATE_DIR/state" | tail -1; }
state_set() {
  mkdir -p "$STATE_DIR"
  { [ -f "$STATE_DIR/state" ] && grep -v "^$1=" "$STATE_DIR/state"; echo "$1=$2"; } > "$STATE_DIR/state.tmp"
  mv "$STATE_DIR/state.tmp" "$STATE_DIR/state"
}
adbs() { adb -s "$SERIAL" "$@"; }
prop() { adbs shell getprop "$1" 2>/dev/null | tr -d '\r'; }
fb() { fastboot -s "$SERIAL" "$@"; }

fb_allowed() {  # action partition -> 0 if the installer may touch it
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
  [ -n "$IMG" ] && { [ -f "$IMG" ] || die "$(t "no image $IMG" "нет образа $IMG")"; return; }
  local found f n=0
  for f in "$HERE"/lumen-os-*-system.img "$HERE"/../lumen-os-*-system.img; do
    [ -f "$f" ] || continue
    n=$((n + 1)); found=$f
  done
  [ "$n" -gt 0 ] || die "$(t "no lumen-os-*-system.img found; use --image FILE" "не найден lumen-os-*-system.img; укажите --image ФАЙЛ")"
  # never pick one of several by name order: the editions (with and without Google) differ
  [ "$n" = 1 ] || die "$(t "several images found (with and without Google?): choose one with --image FILE" "найдено несколько образов (с Google и без?): выберите один через --image ФАЙЛ")"
  IMG=$found
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
  if [ "$KEEP_DATA" = 0 ] && [ "$WIPE" = format ]; then
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
    || die "$(t "this is not an XGIMI Z9X (model '$code', board '$board'). Nothing was done." "это не XGIMI Z9X (код модели '$code', плата '$board'). Ничего не сделано.")"
  case " $VENDOR_OK " in
    *" $vend "*) ;;
    *) die "$(t "XGIMI firmware $vend is not supported (V6.15.58 or V6.15.19 only). Let the stock system update itself to V6.15.58 (Settings, Wi-Fi), then run the installer again." "прошивка XGIMI $vend не поддерживается (только V6.15.58 или V6.15.19). Дайте стоку обновиться до V6.15.58 (настройки, Wi-Fi) и запустите установщик снова.")" ;;
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
  pick_device
  local d="$BACKUP_ROOT/$(date +%Y%m%d-%H%M)" p path n=0
  mkdir -p "$d/apps" "$d/sdcard" "$d/settings"
  say "$(t "backup to $d" "резервная копия в $d")"
  if [ "$(state_get image_gms)" = 0 ]; then
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
    -h|--help) awk 'NR == 1 { next } /^set -u$/ { exit } { print }' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) CMD=$1 ;;
  esac
  shift
done
case "$WIPE" in format|factory-reset) ;; *) die "--wipe=format|factory-reset" ;; esac
case "$BLOBS" in partition|embedded|none) ;; *) die "--blobs=partition|embedded|none (--no-blobs)" ;; esac
case "${CMD:-all}" in gapps) ;; *) [ -z "$GAPPS_ZIP" ] && [ "$GAPPS_REMOVE" = 0 ] || die "--zip / --remove belong to 'gapps'" ;; esac
SERIAL_FROM_STATE=0
if [ -z "$SERIAL" ]; then SERIAL=$(state_get serial); [ -n "$SERIAL" ] && SERIAL_FROM_STATE=1; fi
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
  status) [ -f "$STATE_DIR/state" ] && cat "$STATE_DIR/state" || echo "$(t "no saved progress" "сохранённого прогресса нет")" ;;
  reset) rm -rf "$STATE_DIR"; echo "$(t "progress forgotten" "прогресс сброшен")" ;;
  *) die "unknown command $CMD (see --help)" ;;
esac
