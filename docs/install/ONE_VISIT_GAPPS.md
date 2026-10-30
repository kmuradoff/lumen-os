# Установка с Google за один заход в fastbootd

Статус (2026-10-30, вечер): для XGIMI Z9X РЕАЛИЗОВАНО в installer/lumen-install.sh и lumen-install.ps1,
tools/ota/image/z9x_gapps.sh, tools/ota/gapps_allow.py, installer/lib/gapps_images.txt,
installer/lib/erofs_utils.txt, tools/ota/build_erofs_utils.sh; что сделано и чем проверено, в разделе
«Реализация» в конце. На проекторе НЕ проверено (нужна полная очистка на Z9X владельца, по его решению).
Ниже исходный план; где реализация от него отошла, сказано в «Реализации».

## Решение владельца (2026-10-30)

Установка с сервисами Google проходит за ОДИН заход в fastbootd. Пользователь подтверждает «стереть
всё» (ERASE), и в той же сессии fastbootd установщик пишет system Lumen OS, пишет готовый раздел
z9x_gapps_<slot> с Google и стирает данные. Первый запуск уже чистый, и Google есть с первого экрана
настройки. Никакого промежуточного запуска на старых данных, никакой «Отладки по USB» и «Отладки с
правами root» после шага vbmeta на стоке.

Причина: сейчас (--gapps-zip, lumen-install.sh строки ~1288-1420, Play 6 ~2647-2760) после первой
записи Lumen OS один раз загружается на старых данных, и пользователь должен включить отладку и root,
чтобы gapps_fill.sh заполнил раздел на проекторе. Люди на этом застревают.

Порядок для пользователя: сток, шаг vbmeta в fastboot (как сейчас, вручную), перезагрузка в сток на
всякий случай, затем одна команда установщика: она сама уводит проектор в fastbootd и делает всё
остальное.

## Итог проверки

Осуществимо для Z9X без изменений ядра и без root на проекторе. Раздел собирается на компьютере как
**erofs** (ядро Z9X 5.15 уже читает erofs: сам system Lumen OS это erofs), с владельцем root, режимами
0755/0644 и меткой SELinux `u:object_r:system_file:s0` у каждого inode. Причина, по которой раньше
раздел заполнялся на проекторе (ext4 с файлами root требует e2fsdroid), для erofs не действует:
`mkfs.erofs --tar=f` берёт uid, gid, режим и xattr из заголовков tar, а tar мы пишем сами.

Доказано на Mac (раздел «Проверка на Mac»): образ из настоящего
MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip собирается за ~8 с, fsck.erofs ok, все 53 inode
root:root с меткой, все 23 файла читаются из образа с sha256 из gapps_allow.txt, правило ядра 5.15
(tools/erofs_kcheck.py) не нарушено, образ побайтно воспроизводим, и ТЕКУЩИЙ
`z9x_gapps.sh check` принимает извлечённое дерево без единой правки (`ok: 23 files`). Размер 429 МиБ
против part= 512 МиБ.

Play 6: в ядре нет erofs, но есть squashfs (см. раздел 6). HB: erofs есть, нужен 32-битный zip.

## 1. Образ add-on: erofs, root, метки SELinux

### Что сейчас ожидает образ (tools/ota/image/z9x_gapps.sh)

- Монтирует `/dev/block/mapper/z9x_gapps<slot>` как `mount -t ext4 -o ro,nosuid,nodev,context=u:object_r:system_file:s0`
  после `blockdev --setro`. Опция `context=` задаёт метку всему монтированию, xattr на диске
  игнорируются; сам скрипт ничего не перемечивает (relabel нет). gapps_fill.sh дополнительно делает
  `chcon -R system_file`, но при `context=` это ни на что не влияет.
- Проверяет содержимое (раздел 3), затем read-only overlay (только lower-слои) поверх
  /system/product, /system/system_ext/priv-app, /system/system_ext/etc/permissions, /system/app,
  /system/etc/permissions. Overlayfs показывает метки нижних файлов, то есть system_file.
- Владельца и режимы не проверяет. Их задаёт gapps_fill.sh: каталоги 0755, файлы 0644, root:root.
- Lumen на Z9X/HB работает в permissive, но метки должны быть правильными и в enforcing.

### Раскладка (ровно как gapps_fill.sh)

Корень раздела:

```
MANIFEST                                  0644  две строки комментария, "zip=<sha256> <имя zip>", строки файлов
product/...                               0755 каталоги, 0644 файлы
system/app/..., system/etc/permissions/...
system_ext/priv-app/..., system_ext/etc/permissions/...
```

Путь в разделе = путь в zip без `system/` (член zip `system/<путь>`). Пишутся только файлы блока этого
zip из gapps_allow.txt, каждый с проверкой sha256 и размера; ни одно имя члена zip не выбирает путь.
MANIFEST побайтно такой же, как пишет gapps_fill.sh (`echo "$zl" | cut -d' ' -f1,2` и строки файлов).

### Сборка

Два шага на компьютере, оба детерминированные:

1. **tar-писатель установщика** (новый код в lumen-install.sh и .ps1): для блока zip из
   installer/lib/gapps_allow.txt пишет PAX tar: запись `./` (корень 0755), все каталоги-родители
   (0755), файлы в порядке путей (0644), затем MANIFEST. Каждой записи: uid=gid=0, uname=gname=root,
   mtime=1230768000, PAX `SCHILY.xattr.security.selinux=u:object_r:system_file:s0\0` (ровно как
   tools/patch_tar.py для образа system). Данные идут потоком `unzip -p ZIP system/<путь>`
   (PowerShell: System.IO.Compression), sha256 и размер каждого файла сверяются по ходу; расхождение
   останавливает установку до записи. Заголовки ustar и PAX это ASCII с NUL-заполнением, их можно
   писать из bash (printf + head -c /dev/zero, контрольная сумма арифметикой) и тем более из
   PowerShell. Python для этого не нужен.
   Без записи `./` корень образа получается 0777 и без метки (проверено), поэтому она обязательна.
2. **mkfs.erofs** (поставляется с установщиком, раздел 2):

```
mkfs.erofs -b4096 -E noinline_data -T1230768000 -U 00000000-0000-0000-0000-000000000000 \
  --all-root --workers=1 -Lz9x_gapps --tar=f z9x_gapps.img gapps.tar
fsck.erofs z9x_gapps.img
```

- `-T` без `--mkfs-time`: компактные 32-байтные inode (урок Lumen OS 1.0, tools/erofs_kcheck.py).
- `-E noinline_data`, как у образа system; каталоги остаются inline, kcheck это проверяет.
- `--all-root` дублирует uid 0 из tar (на случай чужого tar-писателя).
- Без сжатия (рекомендуется): APK уже сжаты внутри, нативные библиотеки отображаются из APK
  напрямую, на слабом железе нет распаковки при чтении; меньше зависимостей у mkfs.erofs (без lz4).
  lz4hc уменьшает образ до 267 МиБ (−162 МиБ, ~8 с записи по USB), ядро Z9X lz4 читает (system сжат
  lz4hc). Оставить как запасной вариант, если 2 минуты fastbootd окажутся общим лимитом.
- `-U` нулевой UUID и `--workers=1`: образ побайтно одинаков при каждой сборке (проверено для обоих
  вариантов). Это даёт сильную проверку: см. «Эталонный хэш образа» в разделе 3.

### Метки SELinux

Двойная страховка:
- в образе: у каждого inode xattr `security.selinux` = `u:object_r:system_file:s0` (Xattr size 52 у
  всех 53 inode, проверено dump.erofs);
- при монтировании: та же опция `context=u:object_r:system_file:s0`, что и сейчас для ext4. Опция
  `context=` в SELinux общая для всех ФС; erofs в политике Lumen уже размечен (`fs_use_xattr`, раз
  system это erofs). Если ядро отвергнет `context=` для erofs (проверить на устройстве), скрипт
  монтирует без неё, и действуют метки на диске (они те же).

## 2. mkfs.erofs для установщика

Установщик уже везёт свой platform-tools (installer/tools/platform-tools, свой порт adb 5038). Рядом
кладётся `installer/tools/erofs-utils/<платформа>/` с тремя программами одной сборки: `mkfs.erofs`
(сборка), `fsck.erofs` (проверка), `dump.erofs` (чтение образа Lumen OS и обратное чтение файлов).

| Платформа | Источник | Замечания |
|---|---|---|
| macOS arm64 + x86_64 | своя сборка, universal2 (lipo), `-mmacosx-version-min=11` | brew-сборка не годится для раздачи: тянет /opt/homebrew liblz4, liblzma, системные libcurl/libxml2 (otool -L) |
| Linux x86_64, arm64 | своя статическая сборка (musl, например Alpine), без fuse/selinux/uuid/zlib/lzma | один бинарник без зависимостей |
| Windows x64 | своя сборка под Cygwin или MSYS2 (MSYS-окружение) + cygwin1.dll / msys-2.0.dll рядом | официальных Windows-бинарников нет, пакета MSYS2 нет; был только сторонний архивный порт под Cygwin (affggh/erofs-utils_cygwin, x86_64, заархивирован 2023-09-27). Сборку надо довести самим |

Общее для всех:
- Исходник: один тег upstream erofs-utils, тот же, что проверен здесь: **1.9.4**,
  `https://git.kernel.org/pub/scm/linux/kernel/git/xiang/erofs-utils.git/snapshot/erofs-utils-1.9.4.tar.gz`,
  sha256 `7d135aa2550326a5acf20f53c518aea5a8900015ce50700044e40f818c31dd80` (значение из формулы
  Homebrew; перед сборкой сверить с тарболом и записать в installer/tools/erofs-utils/SOURCE).
- configure: `--disable-lz4 --disable-lzma --disable-fuse --without-selinux --without-uuid
  --without-zlib --without-libdeflate --without-libzstd --without-xxhash` (для варианта lz4hc нужен
  статический liblz4, BSD-2). Метки идут из tar, libselinux не нужен.
- Почему не 1.7.1, как у образа system: проблема 1.9 (inline-данные символьных ссылок через границу
  блока) касается symlink, в add-on их нет, kcheck на образе 1.9.4 даёт 0 нарушений. Сборка установщика
  всё равно гоняет правило ядра: либо порт erofs_kcheck в установщик, либо тест в CI на эталонном zip
  (раздел 3: при равенстве хэша образа с эталоном правило уже проверено).
- sha256 каждого собранного бинарника фиксируется в installer/tools/erofs-utils/SHA256SUMS и
  проверяется установщиком перед запуском, как platform-tools.
- Сборки делает CI/сборщик из исходника; тест: все пять бинарников на эталонном zip дают образ с одним
  и тем же sha256 (раздел 3).
- Лицензии: mkfs/fsck/dump.erofs GPL-2.0-or-later (liberofs двойная GPL-2.0+ / Apache-2.0).
  Раздаём бинарники, значит рядом COPYING, ссылка на точный тег и сам тарбол исходника в релизе
  установщика (самый простой способ выполнить GPL). Cygwin DLL LGPL-3.0, MSYS2 DLL тоже
  GPL/LGPL-семейство: те же правила. lz4 (если включим) BSD-2. Всё в installer/tools/erofs-utils/NOTICE.

## 3. Проверка содержимого

### Сейчас

- Установщик (компьютер): sha256 zip есть в installer/lib/gapps_allow.txt, иначе ничего не пишется.
- gapps_fill.sh (проектор, root): zip в gapps_allow.txt РАБОТАЮЩЕГО образа; каждый файл после
  `unzip -p` сверен по sha256 и размеру; MANIFEST; chmod/chown/chcon.
- `z9x_gapps.sh check` после заполнения и `z9x_gapps.sh` при каждом запуске: MANIFEST с одной строкой
  zip=, этот zip есть в /system/etc/z9x/gapps_allow.txt образа, строки файлов MANIFEST ровно равны
  строкам блока; каждый файл есть, обычный, нужного размера (только метаданные: хэшировать ~450 МБ при
  загрузке нельзя, сторожевой PWM-таймер даёт ~7 с запаса); ничего лишнего (ни файлов, ни ссылок,
  ни пустых каталогов); бюджет 2 с.

### С erofs

- Компьютер: те же проверки zip; tar-писатель сверяет sha256 и размер каждого файла (как
  gapps_fill.sh); после mkfs `fsck.erofs`, затем обратное чтение: `dump.erofs --cat` каждого файла
  из готового образа и сверка sha256 с allow-list (на Mac ~2 с для 450 МБ).
- **Эталонный хэш образа** (новое): образ детерминирован (zip, версия erofs-utils, флаги), поэтому
  его sha256 можно заранее записать. Новый файл только установщика `installer/lib/gapps_images.txt`:
  `<sha256 zip> <версия erofs-utils> <флаги-профиль> <sha256 образа> <байт>`. Для эталонного zip:
  plain `f56bf362049f77be7cf17b263049f61d42475b393963b687837500b8751389ea` 449957888,
  lz4hc `b08db8e318edd3090142b99b8ae30d5a6c4e20093de90d85bed5b3babcfbf9c1` 279666688 (erofs-utils
  1.9.4 brew, флаги выше). Совпало: в раздел идёт ровно проверенный образ. Не совпало: установщик не
  пишет Google и говорит почему. Хэш образа это не файл Google, его можно публиковать.
  В сам gapps_allow.txt такие строки класть НЕЛЬЗЯ: старые образы считают любую строку из трёх полей
  внутри блока строкой файла.
- Проектор, при каждом запуске: тот же z9x_gapps.sh, ни одной новой проверки содержимого не нужно
  (доказано: текущий `check` принимает извлечённое дерево). Меняется только монтирование:

```
# erofs (новые установки), затем ext4 (разделы старого способа): принимаются оба
timeout 2 mount -t erofs -o ro,nosuid,nodev,context=$CTX "$dev" "$M" 2>/dev/null \
  || timeout 2 mount -t erofs -o ro,nosuid,nodev "$dev" "$M" 2>/dev/null \
  || timeout 2 mount -t ext4  -o ro,nosuid,nodev,context=$CTX "$dev" "$M" 2>/dev/null \
  || fail bad:mount "$dev"
```

  Неверная ФС отвергается ядром сразу по magic, бюджет не страдает. Можно точнее: прочитать 4 байта
  по смещению 1024 (magic erofs e2e1f5e0) и монтировать один раз. Read-only гарантирован дважды:
  `blockdev --setro` и сама erofs. `lost+found` в erofs нет, исключение в скрипте безвредно.
  `sys.z9x.gapps.fs=erofs|ext4` для диагностики.
- Необязательно, позже: фоновая проверка после `sys.boot_completed` (sha256 всего блочного
  устройства против эталонного хэша, низкий приоритет) с `sys.z9x.gapps.verified`. Загрузку она не
  задерживает.

## 4. Новая последовательность установки

### До fastbootd (всё медленное здесь, проектор ещё на стоке)

1. Проверки как сейчас (`check`, копия данных, blobs, шаг vbmeta, `check_vbmeta_effective`).
2. zip: sha256 в gapps_allow.txt (gapps_zipcheck), образ без Google (image_gms=0).
3. Образ Lumen: в его /system/etc/z9x/z9x_gapps.sh есть поддержка erofs (через `dump.erofs --cat`
   образа на компьютере; признак, например `ro.z9x.gapps.fs=erofs,ext4` в build.prop). Нет: Google
   ставится старым способом или установка отказывается, решить при реализации.
4. Сборка z9x_gapps.img в рабочей папке установщика: tar-писатель, mkfs.erofs, fsck.erofs, обратное
   чтение, сверка с gapps_images.txt. Время на Mac: tar ~1.6 с, mkfs ~6 с (plain), обратное чтение
   ~2 с. Нужно ~1 ГБ свободного места на компьютере (tar + образ; tar удаляется сразу).
5. Один вопрос ERASE (текст: пишем Lumen OS, сервисы Google и стираем всё, за один заход).
   Состояние: `gapps_plan=<sha256 zip>`, `gapps_img=<sha256 образа>`.

### Одна сессия fastbootd (только запись, без пауз)

```
adb reboot fastboot -> fb_wait -> getvar all (is-userspace, product mt5877, unlocked, slot, snapshot none)
delete *-cow, product<S>, system_ext<S>          ~2 с
flash system                                      ~40 с (как сейчас)
z9x_blobs<S>: resize/create + flash               несколько секунд
z9x_gapps<S>: super_free + текущий размер >= образ (округл. 1 МиБ)?
    да:  resize/create до размера образа, flash z9x_gapps<S> z9x_gapps.img   ~21 с (430 МиБ при ~21 МБ/с)
    нет: delete z9x_gapps<S> (если был), пометка «без Google»
format userdata, format metadata (+ cache)        ~5-10 с
reboot
```

Оценка всей сессии 75-90 с. Скорость взята из нынешней записи system (~0.84 ГБ за ~40 с). Ограничение
fastbootd Z9X «около 2 минут» до сих пор описано как простой без команд; нынешний поток уже
укладывается в нём с system и очисткой. Проверить на устройстве: это лимит простоя или общий. Если
общий и запаса мало, переключить профиль на lz4hc (−8 с). На HB у fastbootd лимита нет вообще.

Порядок «стирание последним» оставлен специально: любой сбой до очистки оставляет данные, как сейчас.

### Сбои

- **Нет места в super**: z9x_gapps не создаётся (старый, если был, удаляется), очистка, установка
  завершается без Google; сообщение: «в super мало места (N МБ), Lumen OS установлена без Google,
  добавить позже: gapps --zip ФАЙЛ».
- **Ошибка create/resize/flash z9x_gapps**: `delete z9x_gapps<S>`, затем очистка и чистое завершение
  без Google, с тем же сообщением. Наполовину записанный erofs не опасен и так (z9x_gapps.sh не
  примет MANIFEST/размеры, `bad:*`, запуск без Google), но удаление делает итог однозначным.
- **Ошибка записи system**: как сейчас (стоп, reboot, текст).
- **Ошибка format userdata**: как сейчас (сброс к заводским на проекторе).
- **fastbootd пропал посреди сессии** (таймаут, кабель): проектор загружается сам. До очистки на
  старых данных: Lumen OS (если system записан) или сток. Отладка по USB и ключ компьютера остались в
  старых данных, поэтому повторный запуск той же команды снова делает `adb reboot fastboot` без
  действий пользователя и продолжает по состоянию. Если Lumen не стартует, z9x_rescue.sh после 3
  неудачных запусков сам уводит в fastbootd, установщик ждёт его там (как `rescue`).

### Состояние для продолжения

`gapps_plan`, `gapps_img` (sha256 собранного образа; другой zip или другой образ при продолжении:
отказ «используйте тот же файл»), `step_flash_system`, `step_gapps_flash` (`done` | `skipped:<почему>`),
`step_wipe`. Продолжение всегда начинает fastbootd-сессию с первого незавершённого шага; образ
z9x_gapps.img пересобирается перед fastbootd, если его нет (он детерминирован, хэш тот же). Старые
ключи `step_gapps_part`, `step_gapps_fill` больше не пишутся; установка, начатая старым способом,
доводится старым кодом или предлагает `reset`.

### Что уходит

Для --gapps-zip на Z9X больше не нужны: первый запуск на старых данных, `z9x_root_wait`,
`root_setting_wait`, push zip, gapps_fill.sh, второй заход в fastbootd. Отдельная команда `gapps --zip`
на уже установленной Lumen OS тоже может перейти на fastbootd + flash готового образа (без root);
очистка данных там по-прежнему решается отдельно.

## 5. Влияние на OTA

- Payload Lumen всегда PARTIAL (`make_ota.py --is_partial_update`, `sign_payload.py` отказывает без
  него). update_engine переименовывает нетронутый `z9x_gapps_<source>` в `z9x_gapps_<target>` с теми
  же экстентами. Это работа с блоками, тип ФС внутри неважен: erofs переживает обновление так же, как
  ext4 (docs/ota.md, раздел про z9x_blobs/z9x_gapps).
- Каждый образ, на который ведёт обновление, должен уметь монтировать erofs (новый z9x_gapps.sh) и
  по-прежнему ext4 (старые установки), и хранить все опубликованные блоки zip= (правило не меняется).
- Откат на образ без поддержки erofs (откат OTA, `rescue` старым образом): `bad:mount`, система
  стартует без Google, без цикла перезагрузок. Значит erofs-установку разрешать только с образом, где
  поддержка есть (пункт 3 до fastbootd).
- Плюс: раздел меньше (430 МиБ вместо 512 МиБ, с lz4hc 267 МиБ), больше места в super под снапшот
  обновления и меньше COW в /data.
- part= в gapps_allow.txt остаётся для старого способа (ext4, gapps_fill.sh, формула gapps_allow.py).
  Для erofs размер раздела = размер образа, округлённый до 1 МиБ (из gapps_images.txt или по факту).

## 6. Play 6 и HB

### Play 6

- В ядре нет erofs (docs/play6/PORTING_MAP.md, /proc/filesystems стока v1.7.116:
  ext2/3/4, squashfs, romfs, iso9660, vfat, overlay ...). Стоковое ядро Lumen не меняет.
- ext4 с компьютера без e2fsdroid не получается: mke2fs из нашего platform-tools 1.46.6 умеет `-d`
  только из каталога (проверено: tar не принимает), brew mke2fs 1.47.4 собран без libarchive
  (проверено). Каталог на компьютере даёт uid пользователя и нет меток.
- Путь для Play 6: **squashfs** из того же tar. squashfs хранит uid/gid/режимы, xattr при
  CONFIG_SQUASHFS_XATTR. Инструмент: squashfs-tools-ng `tar2sqfs` (читает PAX xattr, есть сборки под
  MinGW) или squashfs-tools 4.6 `sqfstar`. Проверить на устройстве (через adb на стоке/Lumen, без
  записи): `zcat /proc/config.gz | grep SQUASHFS` (XATTR, компрессоры gzip/lz4/xz/zstd), иначе
  несжатый squashfs с монтированием `context=`.
- Изменения образа Play 6: overlay/play6/lumen_gapps.sh монтирует `-t squashfs` и затем `-t ext4`
  (старые установки), с `context=` (сейчас его нет). Это изменение образа Play 6, нужно отдельное
  согласие владельца.
- Пока squashfs не проверен на Play 6, там остаётся нынешний способ (заполнение на проекторе).
  Устройства не смешиваются: свой профиль ФС и свой allow-list на устройство.

### HB (G0130, goat_elegant, MT9660)

- Ядро 5.15 GKI, system Lumen hb-t1 уже erofs и загрузился: erofs есть. Путь тот же, что у Z9X.
- Пользовательское пространство 32-битное (armeabi-v7a): нужен MindTheGapps **arm** ATV (в
  gsi/lineage/gapps есть MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip), свой блок allow-list
  и свой gapps_images.txt-профиль; arm64 zip на HB не ставить.
- fastbootd HB без лимита времени (11+ мин); `fastboot format` там требует `format:ext4`.
- В установщике HB ещё нет: сделать сразу один заход по этой схеме, со своим скриптом в образе HB
  (не трогая Z9X, общий только нейтральный код tar-писателя и вызова mkfs.erofs).

## Проверка на Mac (2026-10-30)

Окружение: macOS 27.0.1 arm64, erofs-utils 1.9.4 (Homebrew), python3 для прототипа tar-писателя
(прототип только для доказательства; в установщике писатель будет на bash/PowerShell).

| Шаг | Результат |
|---|---|
| zip | MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip, sha256 5a3bca84... есть в gapps_allow.txt |
| tar | 23 файла, 28 каталогов + корень + MANIFEST, каждый файл сверен по sha256/размеру, 1.6 с |
| mkfs.erofs plain | 449 957 888 байт (429.1 МиБ), 6 с; дважды подряд один sha256 f56bf362... |
| mkfs.erofs lz4hc | 279 666 688 байт (266.7 МиБ), 12 с; дважды подряд один sha256 b08db8e3... |
| part= | 536 870 912 (512 МиБ): plain запас 83 МиБ, lz4hc 245 МиБ |
| fsck.erofs | ok (оба), полное извлечение `--extract` ok |
| dump.erofs | 53 inode: все Uid 0 Gid 0, режимы 0755/0644, Xattr size 52 (метка system_file); дерево ровно как у gapps_fill.sh |
| обратное чтение | все 23 файла `dump.erofs --cat` = sha256 из allow-list (оба варианта) |
| erofs_kcheck.py | 53 inode (0 extended, 29 inline), 0 нарушений правила ядра 5.15 |
| текущий z9x_gapps.sh check | на извлечённом дереве с заглушками tools/ota/test/stubs: `ok: 23 files of MindTheGapps-...zip`, rc 0 |

Не проверено (нужен проектор): монтирование erofs с `context=` на Z9X, скорость flash z9x_gapps в
fastbootd, природа 2-минутного лимита fastbootd, первая загрузка с overlay из erofs (PackageManager,
SetupWraith), squashfs на Play 6.

## Что менять (по файлам)

- tools/ota/image/z9x_gapps.sh, z9x_gapps.rc: монтирование erofs, затем ext4; `sys.z9x.gapps.fs`;
  комментарии (сейчас написано «ext4»); признак поддержки в build.prop. Тест tools/ota/test/gapps.sh:
  заглушка mount для `-t erofs`, случаи erofs и ext4.
- installer/lumen-install.sh и .ps1: tar-писатель, вызов erofs-utils, gapps_images.txt, новая
  fastbootd-сессия, состояние; удалить для Z9X путь «старые данные + root» в --gapps-zip.
- installer/tools/erofs-utils/: бинарники пяти платформ, SHA256SUMS, SOURCE, NOTICE, COPYING, тарбол.
- tools/ota/gapps_allow.py: генерация строки gapps_images.txt (сборка образа из zip и хэш).
- docs/ota.md (размер раздела, ФС), docs/release.md ~150-158 (erofs-utils в релизе установщика,
  gapps_images.txt), docs/install/*: шаги без отладки и root.

## Реализация (2026-10-30)

### Что сделано

- **Установщик (sh и ps1, одинаковое поведение и тексты ru/en).** `--gapps-zip` / `-GappsZip` на Z9X:
  проверка zip, затем до всякой записи и до вопроса о копии: erofs-utils установщика (`eu_setup`), образ
  Lumen принимает erofs-дополнение этого zip (`gi_image_ok`: строка `# lumen-gapps-fs: erofs ext4` в его
  z9x_gapps.sh и zip= в его gapps_allow.txt, чтение нашим dump.erofs), сборка образа (`gi_build`: свой
  tar-писатель, mkfs.erofs, fsck.erofs, обратное чтение каждого файла и MANIFEST, sha256 и размер образа
  против lib/gapps_images.txt). Один вопрос ERASE, одна сессия fastbootd: system, z9x_blobs,
  `z9x_gapps_<slot>` create/resize до размера образа (округление до 1 МиБ) + flash (`z9x_gapps_write`),
  очистка, reboot. Нет места или сбой create/flash: раздел удаляется, установка без Google. fastbootd
  пропал во время flash: «ничего не стёрто, запустите ту же команду», повторный запуск пишет всё заново
  (образ берётся готовый после проверки sha256).
- Убраны из --gapps-zip на Z9X: запуск на старых данных, `z9x_root_wait`, push zip, gapps_fill.sh, второй
  заход в fastbootd. Установка, начатая старым установщиком (`step_gapps_part=done`, нет `step_wipe`),
  доводится `z9x_gapps_finish` тем же flash в fastbootd и очисткой, без root.
- Команда `gapps --zip` для уже установленной Lumen OS оставлена старой (ext4, заполнение на проекторе с
  root): она работает и с образами до 1.0.8. Перевести её на erofs можно позже теми же функциями
  (gi_build + flash, без root), если образ на проекторе уже с поддержкой erofs.
- **Образ.** z9x_gapps.sh монтирует erofs с `context=`, erofs без него, затем ext4 (старые установки);
  `sys.z9x.gapps.fs=erofs|ext4`; `check` пишет `ok: N files of ZIP (erofs|ext4)`. Строка-признак
  `# lumen-gapps-fs: erofs ext4` в заголовке скрипта: её читает установщик.
- **erofs-utils.** `tools/ota/build_erofs_utils.sh`: upstream 1.9.4 (sha256 7d135aa2... сверен с самим
  архивом) + lz4 1.10.0 статически (sha256 5375129..., как у Homebrew; lz4 нужен, чтобы dump.erofs читал
  сжатый lz4hc образ system). darwin universal2 (arm64 + x86_64, macOS 11+, только libSystem), Linux
  x86_64 и aarch64 статические (musl, Alpine 3.20 в docker). sha256 программ в git:
  `installer/lib/erofs_utils.txt` (отход от плана: не SHA256SUMS рядом с программами, потому что
  installer/tools/ не попадает в git). Программы лежат в installer/tools/erofs-utils/<платформа>/ с
  SOURCE, NOTICE, COPYING, LICENSES/; в zip установщика их кладёт выпуск (docs/release.md). Лицензия по
  факту: большинство файлов GPL-2.0+ OR MIT, но lib/hashmap.c GPL-2.0 only, поэтому программы целиком
  GPL-2.0; исходник прикладывается к выпуску отдельным файлом.
- **Windows.** Сборки erofs-utils под Windows нет (Cygwin/MSYS2 не сделаны). Код ps1 полностью готов и
  ищет `tools\erofs-utils\windows-x64\*.exe` по lib\erofs_utils.txt; пока их нет, `-GappsZip`
  останавливается до записи с объяснением (вариант 1 + 3 или Mac/Linux). Инструкции так и говорят.
- **gapps_allow.py.** `image ZIP` (независимый писатель tar на Python + mkfs.erofs установщика) печатает
  строку gapps_images.txt; `image-check` проверяет формат файла.

### Чем проверено (на Mac, без проектора)

| Проверка | Результат |
|---|---|
| bash tar-писатель установщика + darwin mkfs.erofs на настоящем arm64 zip | образ f56bf362... = эталон, побайтно как у Python-писателя |
| PowerShell tar-писатель (pwsh 7 в docker) на настоящем и тестовом zip | те же sha256 образа (installer_ps1_gapps.sh), ps1 разбирается без ошибок |
| tools/ota/test/gapps_erofs.sh | 5 из 5: тестовый и настоящий zip, владелец/режимы/метки всех inode, erofs_kcheck, `z9x_gapps.sh check` = `ok: 23 files ... (erofs)`, загрузка `sys.z9x.gapps=ok fs=erofs`, отказы |
| tools/ota/test/gapps.sh | erofs и ext4 разделы, erofs без context=, sh/dash/ksh |
| tools/ota/test/installer_gapps.sh | одна сессия fastbootd (порядок команд), ru, старый ext4-раздел, нет места, сбой flash, пропал fastbootd + повтор, старая установка, отказы |

### Что проверить на Z9X владельца (полная очистка, по его решению)

1. Сток, vbmeta на обоих слотах, загрузка в сток, затем одна команда `--gapps-zip`.
2. Время всей сессии fastbootd (оценка 75-90 с) и запись z9x_gapps (~430 МиБ): не выходит ли она за
   лимит около 2 минут (простой или общий).
3. Монтирование erofs с `context=` на ядре 5.15 (`getprop sys.z9x.gapps.fs` = erofs), первая загрузка:
   Google в настройке, вход, Play.
4. OTA на следующий выпуск: раздел сохраняется (частичный payload), Google остаётся.

### XGIMI Play 6: вывод

erofs в ядре Play 6 нет (/proc/filesystems стока v1.7.116), поэтому этот путь там не подготовлен в коде.
Play 6 остаётся с нынешним способом (запуск на старых данных, заполнение с root). Возможный путь: squashfs
из того же tar (раздел 6), нужно `zcat /proc/config.gz | grep SQUASHFS` на проекторе и отдельное согласие
владельца на изменение образа Play 6.

### HB (G0130, goat_elegant): запись на потом

Что понадобится, когда HB получит установщик:

- Список: devices/goat_elegant/image/gapps_allow.txt (zip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930,
  sha256 b8d81621...). Строка образа: `python3 tools/ota/gapps_allow.py image` не примет arm-zip (ZIP_RE
  требует arm64), поэтому через обёртку devices/goat_elegant/image/hb_gapps_allow.py (ей нужна команда
  `image` с тем же расширенным шаблоном). Свой файл образов только для HB, не в installer/lib/gapps_images.txt
  Z9X.
- Профиль тот же `plain`; размер раздела = образ, округлённый до 1 МиБ. fastbootd HB без лимита времени,
  `format` там требует `format:ext4`.
- devices/goat_elegant/image/compose_hb.sh закрепляет z9x_gapps.rc и z9x_gapps.sh по sha256. 2026-10-30
  закрепление обновлено на новые файлы (с erofs: 163578c1... для .sh, b70569b7... для .rc), так что
  следующая сборка HB (t8d) сразу умеет и erofs, и ext4. Сборка HB с ними ещё не делалась.

