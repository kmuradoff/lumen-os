#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Lumen OS installer for the XGIMI Z9X (macOS and Linux; bash 3.2 compatible).
#
#   bash lumen-install.sh [options] [command]
#
# Commands (without one: continue the install from where it stopped):
#   check      read-only: computer, cable, projector, image, signatures. Writes nothing anywhere.
#   backup     copy your user apps (APKs), photos/videos/downloads and setting lists to the computer
#   blobs      public images only: copy the MediaTek video codec files from YOUR projector's own
#              system, check them against blobs_allow.txt, build z9x_blobs.img (flashed by 'flash')
#   vbmeta     explains the one manual step (vbmeta). The installer never runs a vbmeta command.
#   flash      writes Lumen OS: system (+ z9x_blobs), then the full data wipe (format, never erase)
#   verify     after the first boot: checks over USB that everything came up
#   gsf        shows the Google Services Framework ID for google.com/android/uncertified
#   status     shows the saved progress;  reset   forgets it (does not touch the projector)
#
# Options:
#   --image FILE        system image (default: lumen-os-*-system.img next to this script or in ..)
#   --serial SERIAL     the projector's adb serial (default: the only connected device)
#   --keep-data         'flash' writes system only, no wipe (repair of an installed Lumen OS)
#   --wipe=format|factory-reset   how the data wipe is done (default format; see the guide)
#   --lang en|ru        language of the messages (default from $LANG)
#   --yes               do not ask before read-only steps (the wipe is always confirmed)
#   --blobs=partition|embedded   override release.conf (published default: partition, codec files from
#                       your own projector); the owner's private image carries them: --blobs=embedded
#   --no-blobs          public image without the codec files (skips 'blobs'; the placeholder stays, so
#                       protected video, e.g. Kinopoisk HD, will not work). Same as --blobs=none or the
#                       environment BLOBS=none
#   --unsigned-image    the owner's own build without SHA256SUMS + SHA256SUMS.sig: the image is NOT
#                       authenticated; asks you to type UNSIGNED (never use it for a downloaded image)
#
# What it writes, ever: system_<slot>, z9x_blobs_<slot> (create/resize/flash), and formats userdata,
# metadata and cache. It may delete only *-cow leftovers and XGIMI's unused product_<slot> and
# system_ext_<slot>. Every other partition name is refused by fb_allowed(). It NEVER touches vbmeta,
# boot, vendor, dtbo, mboot, persist, xgimi*, tvconfig, misc, frp or the other slot, and never runs
# 'fastboot -w' or 'fastboot erase' (proven boot hang on this projector).
set -u

HERE=$(cd "$(dirname "$0")" && pwd)
STATE_DIR=${LUMEN_STATE_DIR:-$HOME/.lumen-installer}
BACKUP_ROOT=${LUMEN_BACKUP_DIR:-$HOME/Lumen-backup}
MODEL_CODE=G0082
BOARD=mt9952
FB_PRODUCT=mt5877
VENDOR_OK="v6.15.58"
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
      else fb format:ext4 cache; fi ;;
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
  IMG=$(ls "$HERE"/lumen-os-*-system.img "$HERE"/../lumen-os-*-system.img 2>/dev/null | head -1)
  [ -n "$IMG" ] && [ -f "$IMG" ] || die "$(t "no lumen-os-*-system.img found; use --image FILE" "не найден lumen-os-*-system.img; укажите --image ФАЙЛ")"
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
  case "$name" in *PRIVATE*) [ "$BLOBS" = embedded ] || die "$(t "$name is a PRIVATE image (codec files inside): run with --blobs=embedded" "$name — ЧАСТНЫЙ образ (файлы кодеков внутри): запустите с --blobs=embedded")" ;; esac
  [ "$BLOBS" != none ] || warn "$(t "--no-blobs: protected video (e.g. Kinopoisk HD) will not work with this install" "--no-blobs: защищённое видео (например, Кинопоиск HD) работать не будет")"
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
    *) die "$(t "XGIMI firmware $vend is not supported. Let the stock system update itself to V6.15.58 (Settings, Wi-Fi), then run the installer again." "прошивка XGIMI $vend не поддерживается. Дайте стоку обновиться до V6.15.58 (настройки, Wi-Fi) и запустите установщик снова.")" ;;
  esac
  state_set serial "$SERIAL"; state_set slot "$slot"
  ok "XGIMI Z9X $SERIAL, $(t 'firmware' 'прошивка') $vend, $(t 'slot' 'слот') ${slot#_}, $(t 'system' 'система'): ${sys:+Lumen/Z9X OS }${sys:-$(t 'XGIMI stock' 'родная XGIMI')}"
  free=$(df -k "$HOME" | awk 'NR==2{print $4}')
  [ "${free:-0}" -ge 4194304 ] || warn "$(t "less than 4 GB free on this computer" "на компьютере свободно меньше 4 ГБ")"
}

cmd_check() {
  check_tools
  check_device
  check_image
  ok "$(t "check passed; nothing was written" "проверка пройдена; ничего не записано")"
  state_set step_check done
}

# ------------------------------------------------------------------ backup
cmd_backup() {
  pick_device
  local d="$BACKUP_ROOT/$(date +%Y%m%d-%H%M)" p path n=0
  mkdir -p "$d/apps" "$d/sdcard" "$d/settings"
  say "$(t "backup to $d" "резервная копия в $d")"
  say "$(t "IMPORTANT: remove your Google account on the projector first (Settings > Accounts), or Factory Reset Protection may ask for it after the wipe." "ВАЖНО: сначала удалите аккаунт Google на проекторе (Настройки > Аккаунты), иначе после очистки защита от сброса может попросить его.")"
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
cmd_blobs() {
  if [ "$BLOBS" = none ]; then
    warn "$(t "--no-blobs: codec files skipped (protected video will not work)" "--no-blobs: файлы кодеков пропущены (защищённое видео работать не будет)")"
    state_set step_blobs skip; return 0
  fi
  if [ "$BLOBS" != partition ]; then
    ok "$(t "this image carries its codec files itself: no blobs step needed" "этот образ содержит файлы кодеков сам: шаг blobs не нужен")"
    state_set step_blobs skip; return 0
  fi
  pick_device
  local allow="$HERE/lib/blobs_allow.txt" w="$STATE_DIR/blobs" set h rel src got n=0 img out
  [ -f "$allow" ] || die "missing $allow"
  rm -rf "$w"; mkdir -p "$w/x"
  set=$(sed -n 's/^set=//p' "$allow")
  printf 'set=%s\n' "$set" > "$w/x/MANIFEST"
  while read -r h rel src; do
    case "$h" in ''|\#*|set=*) continue ;; esac
    mkdir -p "$w/x/$(dirname "$rel")"
    got=""
    for p in "$src" "/system/$rel"; do
      adbs pull "$p" "$w/x/$rel" >/dev/null 2>&1 || continue
      got=$(sha256 "$w/x/$rel")
      [ "$got" = "$h" ] && break
      got="bad"
    done
    [ "$got" = "$h" ] || die "$(t "$rel from the projector does not match the allow-list ($got): this firmware is not supported for protected video. Run again with --no-blobs to install without it (protected video will not work)." "$rel с проектора не совпадает со списком ($got): эта прошивка не поддерживается для защищённого видео. Чтобы установить без него, запустите с --no-blobs (защищённое видео работать не будет).")"
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
  ok "$(t "$n codec files from your projector, set $set -> $out (keep this file: a later repair needs it)" "$n файлов кодеков с вашего проектора, набор $set -> $out (сохраните файл: он нужен для ремонта)")"
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
  [ "$(state_get step_vbmeta)" = done ] || cmd_vbmeta
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
  say "$(t "writing system (about 40 s)" "записываю system (около 40 секунд)")"
  fb_do flash system "$IMG" || { fb reboot; die "$(t "system was not written; the projector still has its old system" "system не записан; на проекторе осталась старая система")"; }
  state_set step_flash_system done
  if [ -n "$blobs" ]; then
    if printf '%s\n' "$GV" | grep -q "partition-size:z9x_blobs$S:"; then fb_do resize "z9x_blobs$S" "$BLOB_SIZE"
    else fb_do create "z9x_blobs$S" "$BLOB_SIZE"; fi || warn "z9x_blobs$S"
    fb_do flash "z9x_blobs$S" "$blobs" && ok "z9x_blobs$S" || warn "$(t "codec files not written: protected video will not work until 'blobs' + 'flash --keep-data'" "файлы кодеков не записаны: защищённое видео не будет работать до 'blobs' + 'flash --keep-data'")"
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
  [ -n "$v" ] && ok "Lumen OS $v ($(prop ro.z9x.build_id))" || warn "$(t "ro.z9x.version missing" "нет ro.z9x.version")"
  [ "$keys" = release ] && ok "$(t "release keys" "релизные ключи")" || warn "ro.z9x.keys=$keys"
  [ "$gm" = running ] && ok "$(t "projector service (lamp, fans) running" "служба проектора (лампа, вентиляторы) работает")" || warn "gmpf_main: $gm"
  if [ "$BLOBS" = partition ]; then [ "$bl" = ok ] && ok "$(t "protected video components" "компоненты защищённого видео") ok" || warn "sys.z9x.blobs=$bl"; fi
  adbs shell dumpsys media.audio_policy 2>/dev/null | grep -q "Output" && ok "$(t "audio outputs present" "аудиовыходы есть")" || warn "$(t "no audio outputs listed" "аудиовыходы не найдены")"
  data=$(adbs shell 'mount | grep " /data "' 2>/dev/null | tr -d '\r')
  case "$data" in *f2fs*) ok "/data f2fs" ;; *) warn "/data: $data" ;; esac
  if [ -f "$STATE_DIR/calib_before.txt" ] && try_root; then
    calib_snapshot after
    if cmp -s "$STATE_DIR/calib_before.txt" "$STATE_DIR/calib_after.txt"; then ok "$(t "calibration partitions unchanged" "разделы калибровки не изменились")"
    else warn "$(t "calibration fingerprint differs: keep the files in $STATE_DIR and report it" "отпечаток калибровки отличается: сохраните файлы из $STATE_DIR и сообщите")"; fi
    adbs unroot >/dev/null 2>&1
  fi
  state_set step_verify done
}

# ------------------------------------------------------------------ gsf
cmd_gsf() {
  pick_device
  [ -n "$(prop ro.z9x.version)" ] || die "$(t "the projector is not running Lumen OS" "на проекторе не Lumen OS")"
  adbs root >/dev/null 2>&1; sleep 3; adbs wait-for-device
  local id
  id=$(adbs shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db \"select value from main where name='android_id';\"" 2>/dev/null | tr -d '\r')
  adbs unroot >/dev/null 2>&1
  [ -n "$id" ] || die "$(t "no GSF ID yet: connect the projector to the internet, wait two minutes, try again" "GSF ID ещё нет: подключите проектор к интернету, подождите пару минут и повторите")"
  echo "GSF ID: $id"
  echo "$(t "Register it at https://www.google.com/android/uncertified (signed in with your Google account), wait 10-30 minutes, then sign in on the projector." "Зарегистрируйте его на https://www.google.com/android/uncertified (войдя в свой аккаунт Google), подождите 10–30 минут и войдите на проекторе.")"
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
  t "Done. On the projector: choose the language, Wi-Fi and the rest of the setup. For Google sign-in,
register the GSF ID first: bash lumen-install.sh gsf" "Готово. На проекторе выберите язык, Wi-Fi и пройдите настройку. Для входа в Google сначала
зарегистрируйте GSF ID: bash lumen-install.sh gsf"; echo
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
    -h|--help) sed -n '2,37p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) CMD=$1 ;;
  esac
  shift
done
case "$WIPE" in format|factory-reset) ;; *) die "--wipe=format|factory-reset" ;; esac
case "$BLOBS" in partition|embedded|none) ;; *) die "--blobs=partition|embedded|none (--no-blobs)" ;; esac
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
  status) [ -f "$STATE_DIR/state" ] && cat "$STATE_DIR/state" || echo "$(t "no saved progress" "сохранённого прогресса нет")" ;;
  reset) rm -rf "$STATE_DIR"; echo "$(t "progress forgotten" "прогресс сброшен")" ;;
  *) die "unknown command $CMD (see --help)" ;;
esac
