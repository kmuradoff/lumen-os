# Z9xTvInput (`org.z9x.tvinput`): вход HDMI для v6

Собственное приложение, которое возвращает HDMI-входы на нашей системе (LineageOS 21 TV GSI) для проектора XGIMI Z9X.
Кода XGIMI в нём нет. Обращений к IGmpf и вообще к HwBinder вендора тоже нет: приложение работает только через system_server (`TvInputManager`, `HdmiControlManager`).

- Версия: `versionCode 61`, `versionName 6.1`. Значения задаются переменными сборки, в манифесте их нет.
- v6.1: базовый язык строк английский (`res/values`), русский перевод в `res/values-ru`. Кнопка Source теперь принадлежит `org.z9x.projector` (его оверлей выбора входа); здесь остались прямые кнопки HDMI 1 / HDMI 2 и просмотр, который оверлей открывает.
- Сборка: `gsi/apps/out/Z9xTvInput.apk`, подпись платформенным ключом (cert SHA-256 `c8a2e9bc…2ab8`), схемы v2+v3.

## Сборка

```sh
cd "<repo>/apps"
VERSION_CODE=61 VERSION_NAME=6.1 sh build_apk.sh Z9xTvInput out/Z9xTvInput.apk
```

`build_apk.sh` выполняет aapt2, javac (заголовки фреймворка, потом android-34), d8, zipalign, apksigner и проверку сертификата.
При каждой следующей прошивке увеличивайте `VERSION_CODE`.

## Установка в образ

Приложение ставится в `/system/app/Z9xTvInput/Z9xTvInput.apk` (каталог 0755, APK 0644, root:root, `u:object_r:system_file:s0`).
Ставить в priv-app **не нужно**, XML privapp-permissions тоже не нужен.

```sh
--mkdir system/app/Z9xTvInput 755
--add   system/app/Z9xTvInput/Z9xTvInput.apk ~/z9x/overlay/apps/Z9xTvInput.apk 644
```

Также нужны:

- `system/etc/sysconfig/z9x.xml` с `initial-package-state package="org.z9x.tvinput" stopped="false"`;
- `pm.boot.disable_package_cache=true` в `system/build.prop` (см. решения темы packaging).

Init/rc, vendor и bootclasspath приложение не трогает.

## Разрешения

Все разрешения выдаются через часть `signature` уровня защиты, то есть по платформенной подписи, поэтому priv-app не нужен.
Уровни проверены по исходникам Lineage 21, файл `frameworks/base/core/res/AndroidManifest.xml`:

| Разрешение | protectionLevel | Зачем |
|---|---|---|
| `TV_INPUT_HARDWARE` | signature\|privileged\|vendorPrivileged | `acquireTvInputHardware`, `getHardwareList`; без него TIS не считается аппаратным |
| `HDMI_CEC` | signature\|privileged\|vendorPrivileged | `portSelect`, `selectDevice`, `sendKeyEvent`, `setInputChangeListener`, `getPortInfo` |
| `CHANGE_HDMI_CEC_ACTIVE_SOURCE` | signature\|privileged | TvView сам вызывает `setMain()`, в ответ приходит `onSetMain(true)` и выполняется CEC-маршрутизация |
| `START_ACTIVITIES_FROM_BACKGROUND` | signature\|privileged\|…\|role | запасной путь для автопереключения (persistent-процесс и так допущен) |

`CAPTURE_TV_INPUT` и `READ_CONTENT_RATING_SYSTEMS` не запрашиваются: они не используются.

Прочие условия:

- `android:sharedUserId` нет.
- `CATEGORY_LAUNCHER` нет нигде, только `LEANBACK_LAUNCHER` (плитка «Источник сигнала» с баннером).
- `android:persistent="true"`.

## Компоненты

| Компонент | Что делает |
|---|---|
| `.HdmiInputService` | Аппаратный TvInputService. На `onHardwareAdded` с типом HDMI регистрирует входы `org.z9x.tvinput/.HdmiInputService/HW1` и `/HW2` с подписями «HDMI 1 (ARC)» и «HDMI 2». Дочерних CEC-входов не создаёт. |
| `HdmiSession` | Сессия одного порта. Подробности в разделе «HdmiSession» ниже. |
| `.PassthroughActivity` | Полноэкранный просмотр через TvView. Подробности в разделе «PassthroughActivity» ниже. |
| `.InputPickerActivity` | Свой список источников: входы, «Главный экран», «Настройки HDMI». Открывается плиткой в лаунчере, клавишей MENU в просмотре и как запасной вариант для клавиши SOURCE. |
| `.SettingsActivity` | 6 переключателей. В безопасном режиме показывает предупреждение. |
| `.GlobalKeyReceiver` | `ACTION_GLOBAL_BUTTON`, `exported=false`, без intent-filter; действие проверяется в `onReceive`. Подробности в разделе «Клавиши пульта» ниже. |
| `.KeyReceiver` | Тот же код под вторым именем. Черновик RRO темы «remote» указывал на `.KeyReceiver`; с этим псевдонимом работает любой вариант RRO. |
| `HdmiWatcher` | Автопереключение. Только пассивные колбэки в system_server. |
| `CrashGuard`, `Safe` | Защита persistent-процесса от цикла падений. |

### HdmiSession

- Вызывает `acquireTvInputHardware(deviceId, info, callback)`.
- Перед `setSurface` вызывает `setStreamVolume(1.0)`. Без этого звук HDMI тихий: `mSourceVolume=0`, а у порта «HDMI In» усиление MODE_JOINT.
- Выбирает первый конфиг `INDEPENDENT_VIDEO_SOURCE`.
- После hotplug повторяет `setSurface` один раз, потому что первый вызов возвращает `ERROR_STALE_CONFIG`.
- При закрытии сначала вызывает `setSurface(null)`, потом `releaseTvInputHardware`. Сам `TvInputHardwareImpl.release()` поток HAL не закрывает.
- На `onSetMain(true)` выполняет CEC `portSelect(port)`.
- Клавиши D-pad, OK, медиа и цифры пересылает по CEC, только если на порту есть CEC-источник.

### PassthroughActivity

- Принимает `ACTION_VIEW content://android.media.tv/passthrough/<id>`: этот интент посылает панель «Входы» Google TV. Наши собственные интенты приходят туда же.
- Сессия создаётся в `onStart` и освобождается в `onStop`, так что поток HAL и аудиопатч живут только пока картинка видна.
- BACK закрывает просмотр, MENU открывает список источников, INFO или OK показывают баннер.
- Если источник отключили, через 4 с возвращается на главный экран. После старта или пробуждения к этому добавляется до 10 с ожидания.

### Клавиши пульта

- `KEYCODE_TV_INPUT` (Source): с v6.1 RRO отдаёт его `org.z9x.projector/.KeyReceiver` (оверлей «Источник сигнала» поверх видео). Если клавиша всё же пришла сюда (старый RRO), она игнорируется, пока `org.z9x.projector/.KeyReceiver` существует; только без него работает прежнее поведение v6: панель `com.android.tv.action.VIEW_INPUTS` в `com.google.android.tvlauncher`, а если её нет, наш `InputPickerActivity`.
- `KEYCODE_TV_INPUT_HDMI_1` и `_2`, на отпускание: открывается соответствующий порт.

Панель запускается прямым `startActivity`, с запасным путём по `ActivityNotFoundException`. Предварительной проверки через `resolveActivity()` нет: на targetSdk 34 её режет фильтр видимости пакетов (у AppsFilter нет исключения для платформенной подписи). Кроме того, в манифест добавлен `<queries>`.

## Глобальные клавиши (RRO на `android`)

В единственный статический RRO `res/xml/global_keys.xml` (`org.z9x.overlay.framework`, приоритет 2000) должны попасть строки ниже. Записи TvFrameworkOverlay остаются, кроме TV_INPUT: с v6.1 она перенаправляется на `org.z9x.projector/.KeyReceiver`.

```xml
<key keyCode="KEYCODE_TV_INPUT"        component="org.z9x.projector/.KeyReceiver" />
<key keyCode="KEYCODE_TV_INPUT_HDMI_1" component="org.z9x.tvinput/.GlobalKeyReceiver" />
<key keyCode="KEYCODE_TV_INPUT_HDMI_2" component="org.z9x.tvinput/.GlobalKeyReceiver" />
```

Уже собранный `gsi/apps/out/Z9xFrameworkKeysOverlay.apk` направляет все три клавиши на `.GlobalKeyReceiver` (проверено через `aapt2 dump xmltree`).
Если RRO собран из черновика темы remote (`org.z9x.tvinput/.KeyReceiver`), клавиши тоже работают.

## Правила автопереключения (`HdmiWatcher`)

Общие правила для всех путей:

- ничего не происходит первые 30 с после старта процесса, то есть после загрузки;
- ничего не происходит при выключенном экране;
- ничего не происходит, пока не завершена настройка: `user_setup_complete` или `tv_user_setup_complete` равны 0 (отсутствующий ключ считается завершённым);
- уже показанный вход повторно не открывается.

| Путь | Условие |
|---|---|
| Подключение кабеля | Вход переходит в `INPUT_STATE_CONNECTED` и остаётся в нём 2 с. Игнорируется в течение 10 с после `SCREEN_ON`, потому что при пробуждении дребезжит HPD. Игнорируется, если на порту по CEC видна только аудиосистема (саундбар или AVR на ARC). `SCREEN_OFF` отменяет ожидающие переключения. Правила проверяются в момент события и ещё раз через 2 с. |
| CEC One Touch Play | `HdmiTvClient.InputChangeListener`, затем 2 с ожидания (экран успевает проснуться от `<Image View On>`), затем проверка общих правил. События в течение 10 с после нашего собственного `portSelect` игнорируются, иначе просмотр открывался бы снова сразу после выхода из него. Окно 10 с после пробуждения здесь **не** применяется, иначе One Touch Play не работал бы. Значит, если при включении проектора плеер уже работает и отвечает `<Active Source>`, проектор переключится на HDMI. Это отключается настройкой «HDMI-CEC: включать HDMI по команде устройства». |
| Открывать HDMI при включении | По умолчанию выключено. Срабатывает один раз за загрузку через 32 с. |

Настройки хранятся в SharedPreferences `z9x_hdmi`:

- по умолчанию включены: автопереключение, возврат домой, One Touch Play, управление по CEC;
- по умолчанию выключены: «сообщать о выходе» и «открывать при включении».

## Безопасность загрузки и устойчивость

- **Вызовы HAL при загрузке.** Их нет. На старте процесс только регистрирует колбэки в system_server: `TvInputCallback`, приёмник `SCREEN_ON/OFF`, CEC `setInputChangeListener` (хранится в system_server, трафика на шину не даёт). `getPortInfo` и `getConnectedDevices` возвращают кэш HdmiControlService.
- **Когда трогается HAL.** HAL TV-входа (`openStream`) и CEC-шина используются только при открытом просмотре: по действию пользователя, по подключению кабеля или по One Touch Play, и не раньше чем через 30 с после старта.
- **IGmpf и gmpf_main.** Вызовов IGmpf нет, gmpf_main приложение не трогает.
- **Защита от падений.** Тело каждой точки входа обёрнуто в try/catch: колбэки фреймворка, `Runnable` в Handler, binder-колбэки, `BroadcastReceiver`, жизненный цикл Activity. Все вызовы `TvInputManager.Hardware` и CEC-маршрутизация выполняются в отдельном потоке `z9x-hdmi-hw`.
- **CrashGuard.** Неперехваченное исключение записывается в `files/crash_log.txt`. Если за 10 минут текущей загрузки было 3 падения и больше, процесс стартует в «безопасном режиме»: HdmiWatcher не запускается. Просмотр и клавиши при этом работают. Режим сбрасывается после перезагрузки.

## Ограничения и обслуживание

- **Обновления.** Приложение persistent, поэтому `adb install -r` его **не обновит** (`INSTALL_FAILED_INVALID_APK: Persistent apps are not updateable`). Обновление только перепрошивкой образа с бо́льшим `VERSION_CODE`.
- **Отключение без перепрошивки (root не нужен).** `adb shell pm disable-user --user 0 org.z9x.tvinput; adb reboot`. Перезагрузка обязательна: `pm` не убивает persistent-процесс. Включить обратно: `adb shell pm enable org.z9x.tvinput; adb reboot`. `adb root` на первой загрузке поверх свежего /data не работает, пока не включено Настройки → Об устройстве → 7 раз «Сборка ОС Android TV» → «Для разработчиков» → «Отладка суперпользователем» (при зависшей настройке экран открывается через `adb shell am start -a android.settings.SETTINGS`); `pm disable` без `-user` от имени shell запрещён (SecurityException).
- **Во время первоначальной настройки** кнопки Source / HDMI 1 / HDMI 2 ничего не открывают, только подсказка «Сначала завершите настройку».
- **Чёрный экран (главный риск, НЕ ПРОВЕРЕНО).** Вендорский `openStream` возвращает заглушку `{dup(0), streamId}`. Если картинка чёрная, а в logcat `apply(...) -> true`, нужен запасной путь `IGmpf.switchInputSource` (код 697). Он не реализован. Вызывать его можно только из приложения Projector и только после проверки значений.
- **ARC.** Подпись «(ARC)» берётся из `HdmiControlManager.getPortInfo()` в момент регистрации. Если порт TV HAL не совпадает с портом CEC HAL, подпись окажется не на том входе. Переименовать вход можно в настройках TV.

## Проверка на устройстве (только чтение)

```sh
adb shell ps -A -o NAME | grep -E "^gmpf_main|gmpf@1.0"      # сначала: оба процесса на месте
adb shell dumpsys package org.z9x.tvinput | grep -E "flags=|pkgFlags|versionCode|granted=true|stopped"
adb shell dumpsys tv_input | grep -A3 HW                     # HW1/HW2 принадлежат org.z9x.tvinput
adb shell dumpsys window policy | grep -A25 mKeyMapping      # TV_INPUT -> org.z9x.projector, HDMI_1/2 -> org.z9x.tvinput
adb logcat -s Z9xHdmiApp Z9xHdmiCrash Z9xHdmiTis Z9xHdmiSession Z9xHdmiWatcher Z9xHdmiViewer Z9xHdmiKeys Z9xHdmiCec Z9xHdmi TvInputHardwareManager TV_HAL
adb logcat -b crash -d | grep -i z9x                         # должно быть пусто
adb shell dumpsys media.audio_policy | grep -i -A3 "HDMI In" # аудиопатч во время просмотра
```
