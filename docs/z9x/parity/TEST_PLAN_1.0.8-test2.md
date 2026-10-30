# Z9X: тестовый образ Lumen 1.0.8-test2 (план теста)

Дата сборки: 2026-10-30. Образ НЕ прошит и на проекторе НЕ проверен, установка по Wi-Fi тоже не запускалась.
Тестовый образ для проектора владельца (XGIMI Z9X, horse_popular, G0082, сейчас Lumen 1.0.7, слот `_b`), не релиз
и не для GitHub: ничего не опубликовано, канал обновлений (`update-*.json`) не менялся.

Основа: [TEST_PLAN_1.0.8-test1.md](TEST_PLAN_1.0.8-test1.md). Здесь только то, что изменилось, оба пути установки
и откат. Проверки на проекторе те же (раздел 6 ниже ссылается на разделы 5.x test1) плюс исправления 8 и 10.

## 1. Что за образ

| | |
|---|---|
| Файл | `gsi/build/lumen_v1-1.0.8-test2/release/lumen-os-1.0.8-test2-nogms-system.img` (989 380 608 байт) |
| sha256 | `4d18f1bc72e8262b485ef43aeb63289dae67f9a2d7740c28d8ad2396dceb6c9f` |
| Подпись | `SHA256SUMS` + `SHA256SUMS.sig` рядом (ключ OTA на Mac, проверено открытым сертификатом `ota.x509.pem`) |
| Tar | `gsi/build/lumen_v1-1.0.8-test2/system_tv_lumen_v1_public_nogms_signed.tar` |
| Build id | `lumen-1.0.8-20261030t2np`, About: `Lumen OS 1.0.8 (AP2A.240905.003, 20261030t2np)` |
| version_code | **10008**, как у 1.0.7 и test1 (то же правило: настоящий 1.0.8 = 10009 потом будет предложен) |
| Редакция | без Google (`ro.z9x.variant=public`, `ro.z9x.gms=0`); дополнение Google в `z9x_gapps_<слот>` остаётся |
| OTA для Wi-Fi | `gsi/build/lumen_v1-1.0.8-test2/ota/payload.bin` + `payload_properties.txt` (раздел 3) |

### Что изменилось по сравнению с test1

Только **Z9xProjector**: пересобран после пробы HAL с PS5 ([probe.md](probe.md)), тестовый APK
`5529ab6c5e8b4cd4b38d9945d1abc3c51485dcc241d338b0a4c71cbede93a9d7` (в test1 был `74ac6eca...`), 1.0.8 / 108.
В нём части исправлений 8 и 10, которые ждали пробу:
- **8 (игра)**: строка режима игры и плитка берут режим текущего входа из IGmpf2 55 (`{state, type, opt}`, если opt
  совпадает с 647); пока изменение ждёт применения, 10 с показывается выбранное. Блок трапеции и поворота в "Турбо"
  только когда 647 = 2/3 И 55 говорит "игра включена". `gameModeOn` для запретов изображения как в стоке.
- **10 (Ultra 120 Гц)**: строка показывает IGmpf 157 (`user_timing_idx == 4`); на HDMI включение разрешено при
  сигнале ниже 62 Гц (IGmpf 662), при 62 Гц и выше отказ "недоступно для этого контента"; после 161 чтение 157 назад.

Всё остальное байт в байт как в test1: 15 других APK (после переподписи совпадают с test1), 11 из 12 пар odex/vdex
(dex2oat детерминирован, совпали с test1; новая только пара Z9xProjector), `z9x_trapezoid.rc`, правки rc/xml/kl/sh.

### Как собран

Как test1 (раздел 1 test1): подписанный tar 1.0.7 + тот же список действий `actions.txt` (72 файла, 18 папок),
пути на `~/lumen-build-tmp/r108t2/`. Шаги: тестовые APK из `overlay/apps_v1` -> dex2oat speed в colima
(`dex/run.sh`, все 12, `oatcheck` OK) -> `resign.py` (16 APK ключами release) -> `build.sh`
(`BID=20261030t2np`, `patch_tar.py`, `mkfs.erofs -T1230768000`). С test1 в дереве поменялись только исходники и APK
Z9xProjector (проверено по времени файлов `overlay`, `tools`, `installer`, `devices`, `apps`).
Скрипты и логи: `~/lumen-build-tmp/r108t2/` (`build.sh`, `img.sh`, `resign.py`, `dex/run.sh`, `actions.txt`,
`ota.sh`, `build.log`, `ota.log`, `ts.txt`, `ueclient/`); образ возврата: `~/lumen-build-tmp/r107ret/` (`build.sh`, `img.sh`, логи).

### Проверено на Mac

- `fsck.erofs` OK (в colima и на Mac), полное извлечение OK, 74 изменённых файла прочитаны из образа и совпадают с
  tar; `erofs_kcheck`: 0 нарушений правила ядра 5.15.
- `tools/sign/check_image.py --variant public --gms 0`: **check_image OK** (96 подписантов, 294 члена прочитаны
  обратно, 73 APK, 26 путей Google удалены).
- `lumen_checks.py packages` (GMS=0): OK, 73 пакета; HOME: `org.lumen.setup=10`, `org.lumen.home=3`.
- `check_release_assets.py` (образ + SHA256SUMS): 0 проблем, 5879 файлов.
- `dump.erofs`: `/system/etc/init/z9x_trapezoid.rc` есть (root, 0644, xattr с меткой SELinux), совпадает с
  `overlay/v1/z9x_trapezoid.rc`. Все 16 APK из образа подписаны release platform `43690bdf...ecb320`.
  `org.lumen.projector` в образе: versionCode 108 / 1.0.8, `classes.dex` совпадает с тестовым APK `5529ab6c...`.
- В образе: `ro.z9x.build_id=lumen-1.0.8-20261030t2np`, `ro.z9x.version_code=10008`, `ro.lumen.version=1.0.8`.

## 2. Перед любой установкой

Всё как в разделе 2 test1: только когда владелец разрешил и **ничего не играет** (отдельной командой
`dumpsys media_session | grep state=`, затем `dumpsys window | grep mCurrentFocus`), снять "до" на 1.0.7.
vbmeta обоих слотов уже выключен (`sys.z9x.vbmeta_cur.flags=3`, `sys.z9x.vbmeta_other.flags=3`).

## 3. Установка по Wi-Fi (update_engine, как "Обновление Lumen OS")

Тот же механизм, что у обновлятора Lumen: `update_engine` на проекторе пишет **другой** слот (сейчас `_b` ->
пишется `_a`), данные не трогаются, текущий слот остаётся целым. Если новый слот не загрузится, загрузчик
вернётся в старый только до `sys.boot_completed`; после этого откат делается так же, по Wi-Fi (раздел 4).

### Что есть

- `ota/payload.bin` (697 352 721 байт), `ota/payload_properties.txt`, `ota/ota.json`, плюс
  `ota/lumen-os-1.0.8-test2-nogms-full-ota.zip` (тот же payload в обычном пакете).
- Полный payload, partial update только `system` (как все пакеты Lumen: `z9x_blobs_<слот>` и `z9x_gapps_<слот>`
  переезжают в новый слот сами), minor 9, VABC lz4, группа super `group_oem` (4 613 734 400) с `system`,
  `max_timestamp` и `--partition_timestamps=system:` = 1793360148. Собран `tools/ota/make_ota.py` в colima
  (`--vm-type vz --vz-rosetta`, `delta_generator` из `~/lumen-builder-backup/otatools`, TMPDIR на case-sensitive
  sparse-образе), подписан `sign_payload.py` ключом `ota` из `~/.lumen-keys` (это `ota.x509.pem` из
  `/system/etc/security/otacerts.zip` образа 1.0.7: отпечаток `a7e5a8cc...`; файла
  `update-payload-key.pub.pem` в образе нет, update_engine берёт ключ из `otacerts.zip`). `sign_payload.py`
  проверил: одна партиция system, partial, без postinstall, группа `group_oem`, timestamps, хэш нового system =
  sha256 образа. `make_ota.py finish` проверил подпись открытым сертификатом.
- **Метки времени.** update_engine отказывает payload с `max_timestamp` меньше `ro.build.date.utc` проектора
  (и `system:` меньше `ro.system.build.date.utc`), равный принимает. Во всех образах Lumen (1.0.7, test1, test2,
  1.0.7-return) оба свойства одинаковые: `1791024134` (3 октября, дата базы). Поэтому подходит любой payload с
  меткой не меньше этой, и обратный путь работает без изменения дат в образе.

### Скрипт `apply_wifi.sh` (не запускался)

`gsi/build/lumen_v1-1.0.8-test2/apply_wifi.sh`:

```
cd "gsi/build/lumen_v1-1.0.8-test2"
bash apply_wifi.sh <lan-ip>:5555            # ставит test2, НЕ перезагружает
bash apply_wifi.sh <lan-ip>:5555 --reboot   # то же и сразу перезагрузка
bash apply_wifi.sh <lan-ip>:5555 --revert   # отменить записанное до перезагрузки
```

По шагам (останавливается на первой проблеме):
1. Локально: размер и sha256 `payload.bin` равны `FILE_SIZE` / `FILE_HASH` из `payload_properties.txt`.
2. **Отдельный вызов** `dumpsys media_session`: если что-то в `PLAYING`, стоп.
3. `adb root`. **Отладка с правами root ("Rooted debugging") должна быть уже включена** в Настройки > Система >
   Для разработчиков. Скрипт её не включает и не обходит: при отказе пишет, что включить, и выходит. После
   `adb root` adbd перезапускается, скрипт заново подключается по сети и проверяет `id -u` = 0.
4. Проверки: `ro.z9x.device=z9x`, слот `_a`/`_b`, у другого слота vbmeta без проверки (`sys.z9x.vbmeta_other.flags`
   & 2), не `sys.z9x.ota=pending`, update_engine в `IDLE` (6 = уже записано и ждёт перезагрузки, 11 = идёт слияние
   прошлого обновления), в `/data` свободно не меньше размера payload + примерно 1,3 ГБ (снимок COW около 1 ГБ).
   Печатает режим SELinux и список разделов XGIMI, которые update_engine скопирует без изменений из текущего слота в
   другой (`ro.product.ab_ota_partitions`: boot, bootdata, dtbo, mboot, optee, owl, satf, tvconfig, tvservice,
   vbmeta_system, xgimiconfig), и спрашивает `YES` (как согласие в обновляторе; `--yes` без вопроса).
5. Копирует `payload.bin` и `payload_properties.txt` в `/data/ota_package/` (около 700 МБ по Wi-Fi), `chmod 644`,
   `restorecon`, сверяет sha256 на проекторе.
6. Ставит `sys.z9x.ota.busy=1` (Projector не уводит в сон во время записи, как при обновляторе), вызывает
   `applyPayload("file:///data/ota_package/payload.bin", 0, FILE_SIZE, заголовки + SWITCH_SLOT_ON_REBOOT=1)` и
   следит за статусом до `UPDATED_NEED_REBOOT`. Если Wi-Fi оборвётся, подключается снова и продолжает следить
   (update_engine пишет дальше сам). Потом удаляет копию payload, `sys.z9x.ota.busy=0`, печатает слот следующей
   загрузки (другой слот) и `snapshotctl dump`.
7. Перезагружает только с `--reboot`. Без него: перезагрузить, когда удобно (`adb reboot` или "Перезапуск");
   до перезагрузки можно отменить (`--revert`: `resetShouldSwitchSlotOnReboot` + `resetStatus`).

**Почему не `update_engine_client`.** В образе Lumen (и 1.0.7, и test2) есть `/system/bin/update_engine`, но нет
`update_engine_client`. Поэтому рядом `tools/lumen-ueclient.jar` (исходник `tools/UeClient.java`, сборка
`~/lumen-build-tmp/r108t2/ueclient/build.sh`): маленький dex для `app_process` от root с теми же вызовами
`android.os.UpdateEngine`, что у обновлятора Lumen (`bind`, `applyPayload`, `resetStatus`...). Команды:
`status`, `apply URL ФАЙЛ_ЗАГОЛОВКОВ`, `follow`, `revert`. Проверен только запуск в эмуляторе (класс грузится,
без update_engine честно пишет "not reachable", код 3); с настоящим update_engine не запускался.

**SELinux.** На Z9X SELinux в режиме permissive по аргументу ядра от MBOOT (`androidboot.selinux=permissive`,
`logs/v2_boot/*/cmdline.txt`, там же `getenforce` = `Permissive`; release notes 1.0: "SELinux работает в режиме
permissive"). Значит вызов update_engine из `adb root` (домен `su`) и чтение `/data/ota_package` ничем не
блокируются; даже в enforcing у `update_engine` в AOSP есть доступ к `ota_package_file`, а файлы получают эту метку
через `restorecon`. Скрипт печатает `getenforce`.

**Что ещё учесть.** Если обновлятор Lumen сам что-то качает или ставит, update_engine не будет `IDLE`, скрипт
остановится. Кнопку POWER во время записи не нажимать (короткое нажатие = сон STR, update_engine встанет).
После перезагрузки update_engine сам сливает снимок (около минуты после загрузки, vold отмечает слот); пока слияние
идёт, новое обновление (например, обратно) скрипт не начнёт ("merging", подождать пару минут).

## 4. Установка по кабелю (как test1)

Раздел 3 test1 без изменений, только другой файл:

```
bash installer/lumen-install.sh \
  --image build/lumen_v1-1.0.8-test2/release/lumen-os-1.0.8-test2-nogms-system.img \
  --keep-data --no-blobs flash
```

или в fastbootd `fastboot flash system lumen-os-1.0.8-test2-nogms-system.img` (проверка
`shasum -a 256 -c SHA256SUMS` в `build/lumen_v1-1.0.8-test2/release`). Кабель пишет **текущий** слот (`system_b`),
по Wi-Fi пишется другой. Подпись `SHA256SUMS.sig` пройдёт (ключ OTA).

## 5. Откат на 1.0.7

### 5.1 По Wi-Fi: образ 1.0.7-return

`gsi/build/lumen_v1-1.0.7-return/`:

| | |
|---|---|
| Образ | `release/lumen-os-1.0.7-return-nogms-system.img` (989 048 832 байт), sha256 `9c4ccb64b014d20704ad247b2d7f0a9f68ad646eb4f2e5628b3fefe66dba55c9`, `SHA256SUMS` + `.sig` |
| Что внутри | подписанный tar 1.0.7 **без изменений**, кроме двух строк: `ro.build.display.id=Lumen OS 1.0.7 (AP2A.240905.003, 20261030rnp)` и `ro.z9x.build_id=lumen-1.0.7-20261030rnp` (сверено: остальные 6445 членов tar совпадают с 1.0.7) |
| version_code | 10008 (настоящее значение 1.0.7; в задании было "10007 остаётся", но у 1.0.7 он 10008, не менялся) |
| OTA | `ota/payload.bin` (697 490 453 байт) + `payload_properties.txt` (+ zip), та же сборка и подпись (`sign_payload.py` проверил группу `group_oem`, timestamps, хэш = образ), метка 1793360208 (новее test2) |
| Даты сборки | не менялись (`ro.build.date.utc=1791024134`, как у test2): не нужно, см. "Метки времени" в разделе 3. Менять их было бы хуже: дата в образе стала бы новой нижней границей для всех будущих payload |

```
cd "gsi/build/lumen_v1-1.0.8-test2"
bash apply_wifi.sh <lan-ip>:5555 --ota-dir ../lumen_v1-1.0.7-return/ota
```

Запись идёт обратно в другой слот (после test2 по Wi-Fi это снова `_b`). Отдельный build id нужен, чтобы видеть, что
это возврат, а не старый 1.0.7 (`getprop ro.z9x.build_id` = `lumen-1.0.7-20261030rnp`). Обновлятор потом предложит
только то, что выше 10008 (настоящий 1.0.8, 10009), как и с исходным 1.0.7. Технически и опубликованный полный
payload 1.0.7 (метка 1793188578) подошёл бы, но тогда build id совпал бы с исходным.

`check_image.py` на этом образе даёт 6 ошибок, и это ожидаемо: он сравнивает с текущим деревом после
переименования (`org.z9x.updater` вместо `org.lumen.updater`, старые `z9x_ota.sh`, `z9x_uires.sh`, `z9x-gapps.xml`),
а образ должен быть именно 1.0.7. `lumen_checks.py packages` по той же причине ругается на имена HOME.
`check_release_assets.py`: 0 проблем.

### 5.2 По кабелю

Раздел 4 test1 без изменений: `lumen-os-1.0.7-nogms-system.img` (исходный 1.0.7, `0c027012...`) через установщик
`--keep-data --no-blobs flash` или `rescue`, или в fastbootd. Можно и образ 1.0.7-return.
Важно после Wi-Fi: кабель пишет **текущий** слот. Если test2 стоит в `_a` после Wi-Fi и не грузится,
проектор сам уходит в fastbootd после 3 неудач (`z9x_rescue`): `rescue` пишет тот слот, на котором он застрял.

### 5.3 Что происходит с данными при возврате (после моста переименования)

Как в разделе 4 test1, путь (Wi-Fi или кабель) не важен:
- При загрузке test2 мосты `LumenBridge*` (`org.z9x.*`, versionCode 900000) заняли старые имена, а LumenImport
  **скопировал** данные `org.z9x.*` в `org.lumen.*` (DE, затем CE, один раз). У `org.z9x.*` ничего не удалено.
- После возврата старые приложения `org.z9x.*` снова на своих путях с versionCode 104..107, а в packages.xml записан
  мост 900000. Для системного приложения при смене system Android принимает пониженную версию и данные оставляет
  (так рассчитано в test1, на проекторе ещё не проверено). Настройки вернутся такими, какими были на 1.0.7 до теста.
- Данные `org.lumen.*` удалятся (их APK больше нет). Всё, что изменили во время теста (избранное, SMB-папки,
  настройки изображения в Lumen), потеряется: на 1.0.7 будет состояние до теста.
- Повторный переход на test2 (или на настоящий 1.0.8) снова запустит LumenImport? Нет: LumenImport отмечает
  импорт в данных `org.lumen.*`, а их удалили при возврате, поэтому импорт пройдёт заново со свежими данными 1.0.7.
- **Системные настройки Secure сами не вернутся.** Projector test2 один раз (`LumenImport.migrateSystemSettings`)
  меняет `org.z9x.` на `org.lumen.` в `screensaver_components`, `screensaver_default_component`,
  `tv_input_custom_labels`, `tv_input_hidden_inputs`, `assistant`, `voice_interaction_service`,
  `voice_recognition_service`. На 1.0.7 эти значения укажут на несуществующие компоненты: пропадут подписи и
  скрытие входов HDMI, заставка и голосовой ассистент вернутся к умолчанию. Поэтому **до** установки test2 сохранить
  их (`for k in <ключи выше>; do echo "$k=$(adb shell settings get secure $k)"; done > before_secure.txt`), а после
  возврата вернуть (ничего не играет, отдельный вызов перед этим):
  ```
  for k in screensaver_components screensaver_default_component tv_input_custom_labels tv_input_hidden_inputs \
           assistant voice_interaction_service voice_recognition_service; do
    v=$(adb -s <lan-ip>:5555 shell settings get secure $k | tr -d '\r')
    case "$v" in *org.lumen.*) adb -s <lan-ip>:5555 shell settings put secure $k "'${v//org.lumen./org.z9x.}'" ;; esac
  done
  ```
  (если в подписи HDMI есть одинарная кавычка, вписать это значение вручную из `before_secure.txt`).
  Маркер `settings_done` лежит в данных `org.lumen.projector`, поэтому при следующем переходе на 1.0.8 замена
  пройдёт снова.
- Если после возврата что-то из Lumen не стартует: `adb logcat -d | grep -iE "downgrade|org.z9x"`; крайний случай
  полная установка 1.0.7 с очисткой и снова Google через `gapps --zip`.
- После теста с `persist.z9x.kst_prepare=1` и возврата на 1.0.7 вернуть `adb shell setprop persist.z9x.kst_prepare 0`.

## 6. Проверки на проекторе

Порядок как в test1: сначала 5.0 test1 (загрузка и переименование), ожидаемый build id теперь
`lumen-1.0.8-20261030t2np`. Дополнительно после установки по Wi-Fi: `getprop ro.boot.slot_suffix` = другой слот
(`_a`), `getprop sys.z9x.ota` (гейт: `ok` / `healthy` после 90 с, не `rolledback`), `getprop sys.z9x.blobs` = `ok`,
`getprop sys.z9x.gapps` = `ok` (разделы переехали в новый слот).

Затем 5.1 - 5.9 test1 без изменений (AF-мишень, PCM, GPU-трапеция и фото, сброс трапеции, фокус, Boost, игра,
изображение, потолок и сдвиг). В 5.7 и 5.8 test1 добавить проверки ниже. Пункт 5.10 test1 ("чего нет")
теперь: 2 (EDID) решено без изменений (661 = 1 на HDMI 2.0, шкала верна), 12, 13 и слайдер яркости (175) по-прежнему нет.

### 6.1 Игра по 55 (исправление 8, новая часть)

PS5 на HDMI 1, `adb logcat -c`, затем `adb logcat -d -s Z9xGame Z9xPanel`.
- Строка режима игры и плитка показывают режим входа: на PS5 вендор сам включает игру, строка "Вкл". Выбрать "Авто",
  10 с показывается выбранное, потом строка из 55. Плохо, если на живом HDMI 1 в логе есть
  `55 = {a,b,c} vs 647=N: does not fit, not used` или `55 unreadable (...)`.
- Скорость "Стандарт": трапеция и поворот НЕ блокируются (раньше блок мог стоять по одному 647).
  "Турбо" (647 = 2/3) при включённой игре: блок есть. В логе при Турбо: `profile now top speed (gen N)`,
  при ненулевой трапеции `keystone active ...: top speed not used` или сначала диалог и `keystone full frame (...)`.
- Режим изображения и формат кадра в игре отказывают (как в test1 5.7).

### 6.2 Ultra 120 Гц по 157 / 662 (исправление 10, новая часть)

`adb logcat -d -s Z9xPanel Z9xGame`.
- Без HDMI (YouTube или главный экран): включить Ultra 120 Гц, ожидается
  `161(4) -> 157 user=4 out=1920x1080@120 timing=4`; выключить: `161(6) -> 157 user=6 out=3840x2160@60 timing=6`.
  Строка меню после перезагрузки показывает то же, что 157 (не только сохранённое значение). Строки "ещё не проверено"
  в диалоге нет.
- На HDMI 1 с PS5 в 4K 59.94 (ниже 62 Гц): включение разрешено, та же строка `161(4) -> 157 ...`.
- На HDMI с сигналом 62 Гц и выше (PS5 в 120 Гц, раздел 6.3): отказ с подсказкой "недоступно для этого контента",
  в логе `161(4) refused: 696=<src> tvinput session=true 662=<w>x<h>p@119.88 hdmi raw=...`.
- Игровой режим временно закрывает Ultra 120 и возвращает после сессии:
  `Ultra 120 Hz closed by game mode: re-sent after the session`, позже
  `Ultra 120 Hz given back after game mode: 161(4) -> true`.

### 6.3 PS5 в 120 Гц (нужно переподключить HDMI)

Проба показала: `648(1)` сразу переключает EDID (`setEdid 4 -> 5`, `edid20_120hz_1.bin`), но PS5 перечитывает EDID
только при hot-plug, поэтому без переподключения 120 Гц не появится.
1. Ничего не играет (отдельный вызов). HDMI 1 на экране, игра "Вкл".
2. Включить "120 Гц для приставок". В логе `Z9xGame: 120 Hz for consoles true`, у вендора
   `logcat -d | grep -iE "setEdid|edid20_"`: `setEdid 4 -> 5`, `edid20_120hz_1.bin`.
3. **Вынуть и вставить кабель HDMI** (или полностью выключить и включить PS5, не режим покоя).
4. На PS5: Настройки > Экран и видео > Видеовыход > Вывод 120 Гц: Автоматически; игра с режимом 120 Гц.
5. Ожидается: `Z9xGame: HDMI 1 signal <w>x<h>p@119.88 hdmi` (или 120.0), затем `input >= 100 Hz: top speed + HFR`;
   у вендора `HDMI FrameRate Change ... -> 11988` (или 12000). Картинка и звук без сбоев, PS5 в информации о
   видеосигнале показывает 120 Гц.
6. Выключить "120 Гц для приставок": `120 Hz for consoles false`, `edid20_non_hrr_1.bin`; снова переподключить HDMI,
   PS5 вернётся к 60 Гц. По умолчанию после установки эта настройка выключена.

## 7. Что записать после теста

В этот файл: дату, путь установки (Wi-Fi или кабель), слот, что прошло и что нет по 5.x test1 и 6.x, строки логов,
номера фото (`gsi/docs/z9x/parity/photos/1.0.8-test2/`). Если всё хорошо: версия 1.0.8 собирается как релиз
(version_code 10009, своё build id), сначала OTA 1.0.7 -> 1.0.8 на этом же проекторе (`docs/release.md`, раздел 4).
