# Z9X: яркость и свет, сток против Lumen 1.0.7

Область: уровни яркости, Boost, eco и режимы света, Smart Turbo Light, адаптация к окружающему свету.
Устройство: XGIMI Z9X (horse_popular, G0082, MT9952). Дата проверки: 2026-10-30.

Источники стока:
- конфиг G0082 с живого проектора (только чтение, cat): `/mnt/vendor/xgimiconfig/G0082/feature/feature.xml`,
  `dlp/dispcomm.ini`, `dlp/current.ini`, `dlp/current01.ini`, `dlp/dlp.ini`, `lightSensor/lightSensor.ini`,
  `tempCtrlCfg/*` (в дампе `z9x-firmware-dump/fs/mnt_vendor_xgimiconfig.tar` пустой, 0 байт, поэтому читал с устройства);
- дерево настроек `research/z9x_stock_settings/settings_tree_product_dapeng.json` и `tree_annot.txt`;
- декомпиляция `research/z9x_stock_decompiled/jadx/settings` и `.../systemui`, строки `libxgimi.str`.

Живой проектор (<lan-ip>): только getprop, settings list, dumpsys, logcat -d, cat. Ни одного вызова HAL,
ни одной записи.

## Коротко

1. **Boost на стоке для G0082 есть и показан в меню.** `feature.xml`: `support_brightness_boost = 2`
   ("Boost есть, без второго окна подтверждения"). Он спрятан за десяткой: на уровне 10 нажать вправо,
   появится подсказка "ещё раз → для Boost", второе нажатие вправо включает. Поэтому его легко не найти.
2. **Что Boost делает на самом деле.** Он не поднимает ток лазера. `setLensDispMode(1)` переключает
   DLP с 3-сегментной последовательности цветов на 5-сегментную (`dispcomm.ini: LensDispModeType = 2`,
   "переключение через смену Look"). Таблицы токов в `current.ini` (3 сегмента) и `current01.ini`
   (5 сегментов) для стандартного режима совпадают байт в байт, отличается только `look_duty`.
   Итог: картинка ярче, цвета менее насыщенные, цветовое пространство в Boost не переключается
   (`LensDispSupportColorSpace = {1, 0}`: для 5 сегментов 0). Сток при выходе пишет ровно это:
   "понизить яркость, чтобы получить более яркие (насыщенные) цвета".
3. Lumen Boost реализован и работает так же по клавишам. Расхождения: лишний температурный запрет 85 °C
   (на стоке его нет, и обоснование в коде неверное), нет стоковых запретов "в Boost нельзя менять цветовое
   пространство" и "в Boost нельзя включить режим Мастер-цвет".
4. Eco-режима, адаптации к окружающему свету и Smart Turbo Light в стоковом меню G0082 нет. Датчика
   освещённости в этом проекторе нет (подтверждено тремя способами, см. ниже). Добавлять их не нужно.

## Таблица

| # | Пункт | Сток (доказательство) | Lumen (файл:строка) | Вердикт | Что сделать |
|---|---|---|---|---|---|
| 1 | Уровни яркости 1..10 | Узел `brightness_adjustment_progress`, `itemListMin 1`, `itemListMax 10` (`jadx/settings/resources/res/raw/brightness.json`); запись `GmDisplayManager.setDlpLumensLevel` (`brightness/BrightnessManager.java:52-55`), задержка 200 мс перед записью (`BrightnessRepository.java:1567`). По умолчанию 10 (`BrightnessManager.java:102-104`). | Слайдер 1..10, IGmpf 176/177 (`panel/QuickPanelController.java:537-554`, `hal/GmpfClient.java:664-668`), фиксация через 200 мс (`ui/SliderRow.java:13`, `133`). | SAME | Нет. |
| 2 | Где регулируется | Сток: Настройки > Яркость и дисплей > Регулировка яркости; плюс окно яркости по DIY/misc-кнопке (`misckey_default_config.json`: `com.xgimi.misckey.openBrightness`, `newsettings/misckeyui/BrightnessDialog.java`). | Плитка "Яркость" в быстрой панели (`QuickPanelController.java:465`), голос "яркость" (`voice/Commands.java:112`). | SAME (по сути) | Привязку DIY-кнопки к окну яркости проверяет область "кнопки". |
| 3 | Boost виден ли для G0082 | `support_brightness_boost = 2` (`feature.xml:36`); `getSupportBrightnessBoost()` = версия >= 1 (`common/utils/FeatureUtils.java:572-579`). Шкала до 11 (`BrightnessDialog.java:89`), подпись страницы "10+: переход на Boost даёт больше яркости" (строка `brightness_adjustment_back_only_boost`). | Шкала 1..10, за краем Boost, подпись "Boost" на шкале и плитке (`QuickPanelController.java:548`, `1454-1460`, `1587`), подсказка `panel_lamp_hint` "На уровне 10 нажмите → дважды". | SAME | Нет. В стоке в окне кнопки подпись "Яркость 10+" (`BrightnessDialog.java:516`), у нас "Boost": косметика, можно оставить. |
| 4 | Включение Boost | На 10 вправо: тост-окно `boost_tips` "ещё раз → для Boost"; второе вправо без повтора (repeatCount 0): для версии 2 сразу `setLensDispMode(1)`, шкала 11; влево закрывает окно (`brightness/BrightnessRepository.java:1185-1294`, `BrightnessRepository$setLedLight$index$1$onKeyDown$1.java`). Окно подтверждения "погасить экран и переключить" только для версии 1, у G0082 его нет. | Первое нажатие вправо за краем показывает Notify "Нажмите → ещё раз для Boost", второе в течение 3 с вызывает IGmpf2 52(1) (`QuickPanelController.java:549-551`, `1133-1144`, `1146-1164`; `hal/Gmpf2Client.java:124-131`). | SAME | Нет (у стока окно ждёт следующего нажатия, у нас окно 3 с: разница незаметна). |
| 5 | Выход из Boost | На Boost влево: тост `boost_close_tips` "ещё раз ← чтобы выйти", второе влево: `setLensDispMode(0)`, шкала 10 (`BrightnessRepository.java:1303-1409`, `...$index$2$onKeyDown$1.java`). | На Boost левая стрелка перехватывается, два нажатия за 3 с: IGmpf2 52(0); RTL учтён (`QuickPanelController.java:252-258`). | SAME | Нет. |
| 6 | Запрет Boost в 3D | `GmDisplayManager.is3dMode()` / `get3dMode() != 1`: тост "выключите 3D" (`BrightnessRepository.java:1188-1190`, `BrightnessDialog.java:251-253`, `359-361`); правило `threeDimensionMutexBoost` (`assets/platform/common.json`, dlpLumensLevel). | 192 != 1 или 193: Notify `panel_boost_3d` (`QuickPanelController.java:1150-1154`). | SAME | Нет. |
| 7 | Запрет Boost при IMAX | `isImaxVideoPlaying()` (`BrightnessRepository.java:1191-1193`). У G0082 `support_imax = false` (`feature.xml:38`). | Нет. | SAME (не применимо) | Нет. |
| 8 | Температурный запрет Boost | В стоке нет: `BrightnessManager.isHighTemperature()` (`BrightnessManager.java:173-175`) нигде не вызывается. Вендор сам снижает ток при перегреве: `current.ini [LOWPLUMENSCONMAP]`, Temp Low/Lower -> таблицы `high_current` / `user_current`. | Отказ при IGmpf 438 >= 85 °C, Notify "слишком нагрелся" (`QuickPanelController.java:91-92`, `1155-1160`). Комментарий "Raises the laser current" (`hal/Gmpf2Client.java:127`) неверен. | EXTRA | Убрать проверку 438 из `setBoost` (Boost не меняет ток, тепловую защиту делает вендор), строку `panel_boost_hot` удалить. Комментарий в `Gmpf2Client.java:127` заменить на "switches the DLP look 3-segment -> 5-segment (dispcomm.ini LensDispModeType 2, current01.ini); laser currents unchanged". Если хочется осторожности, оставить только запись в лог. |
| 9 | Что делает Boost (объяснение пользователю) | Строки стока: `boost_title` "переключиться на Boost для большей яркости?", `boost_close_desc` "понизить яркость, чтобы цвета стали ярче". | Подсказка говорит только как включить (`panel_lamp_hint`). | DIFF (мелкое) | Дописать в `panel_lamp_hint` (все языки): "Boost: ярче, но цвета менее насыщенные; цветовое пространство в Boost не меняется." |
| 10 | Boost и цветовое пространство | Правило `colorAdjustmentMutexBoost` (тост "в Boost нельзя менять цвет") при `isColorSpaceBoostMutex()` = нет ключа `support_boost_color_space` -> "0" -> true (`common/utils/FeatureUtils.java:624-630`, `mutex/helper/DisplayHelper.java:280-282`, `common.json` colorAdjustment). Железо: `dispcomm.ini LensDispSupportColorSpace = {1, 0}`. Строка `boost_mode_dont_support_color_space`. | Строка "Цветовое пространство" в профессиональных настройках не проверяет Boost (`panel/ProPicture.java:162-167`). | DIFF | В `ProPicture` читать `snap.boost` (или IGmpf2 51 в своём снимке); при Boost строку показывать с текущим значением, но выбор отклонять Notify "В режиме Boost цветовое пространство не меняется. Выключите Boost". Сам вызов 112 при Boost не слать. |
| 11 | Boost и режим "Мастер-цвет" (大师色准) | Правило `lumenLevelBoost` для `masterColorMode` и `masterColor`: тост "выключите Boost и повторите" (`common.json`, `mutex/module/manager/DisplayMutexManager.java:108`, `437`). | Режим `COLOR_ACCURACY(29)` выбирается при Boost без проверки (`QuickPanelController.java:105-108`, `1107-1119`). | DIFF | В `onPictureModeChosen`: если `boostOn` и выбран `COLOR_ACCURACY`, не слать 667, показать Notify "Выключите Boost и повторите", вернуть строку. |
| 12 | Запрет регулировки яркости в отдельных режимах | Правило `dlpLumensLevel`: нельзя менять яркость, если lumens mode (IGmpf 175) = 6 (свой), 12 (офис), 15 (производительность) (`mutex/helper/DisplayHelper.java:47-57`, `common.json`). В дереве G0082 цветовая температура реализована через lumens mode: D65 16, Цвет.темп.1 17, 2 -> 0, 3 -> 15 (`tree_annot.txt`, `image_mode_3_color_temp_dapeng`). | Слайдер яркости активен всегда, когда 177 читается (`QuickPanelController.java:1460`); `getColorTemp` возвращает null для всего кроме 16/17 (`hal/GmpfClient.java:670-674`). | DIFF (LIKELY) | Добавить в `Snapshot` сырое значение 175 (`lumensModeRaw`). Если 6, 12 или 15: слайдер выключить и показать стоковую подсказку "В режиме ... яркость не регулируется". Сначала проверить на устройстве (с разрешения владельца), какое значение 175 даёт картинка "Офис" и "Производительность": если вендор их не ставит, правило ничего не меняет. Сейчас в логе `LumensMode:17` (Цвет.темп.1, GM_DISP_SCENE_V3). |
| 13 | Запрет яркости в 3D | Правило `iRISDeviceNotThreeDimension`: G0082 не IRIS (`support_ambient_light_version = 3`, `IrisModeType = -1`), значит в 3D яркость менять нельзя (`DisplayHelper.java:150-152`, `common.json`). | 3D в интерфейсе Lumen нет; слайдер 3D не проверяет. | SAME (не применимо) | Дёшево подстраховаться: если `snap.fmt3d != 1`, выключать слайдер (192 уже читается, `panel/Snapshot.java:74`). |
| 14 | Яркость и адаптация цвета стены | Правило `wallColorAdapt` action 5: смена яркости сбрасывает адаптацию цвета стены (`common.json`). Сама адаптация есть в меню (`wall_color_calibration`, модуль brightness). | Адаптации цвета стены нет: код закомментирован (`hal/GmpfClient.java:1395-1404`). | MISSING (в другой области) | Отметить для области "картинка / коррекция". Если её добавят, при смене яркости сбрасывать флаг как сток. |
| 15 | Тихий подъём яркости для тестов магазинов | `dispcomm.ini`: `BrightnessBoostType = 3`, `BrightnessBoostLimitTimeSec = 360000`, порог белого 1014; `CivaIncreaseBrightnessEn = 1`. Делается внутри gmpf. | Работает и у нас: в логе раз в 8 с `GM_DISP_SCENE_V3: _switchHlightTestMode ... LumensMode:17`. | SAME (вендор) | Нет. Это не пользовательская функция, и после 100 часов работы она отключается. |
| 16 | Снижение тока при перегреве | `current.ini [LOWPLUMENSCONMAP] = {0,0xff,0xff,0xff,1,2,...}`: "Temp Low" -> таблица 1, "Temp Lower" -> таблица 2; `tempCtrlCfg/tempctrl.xml`. Внутри gmpf. | То же (вендор). | SAME (вендор) | Нет. |
| 17 | Яркость при защите глаз (расстояние) | `support_eye_protect_brightness_mode = true` (`feature.xml:91`): вендор сам приглушает свет на малом расстоянии, событие 608 "brightnessAdjust". | Вендор приглушает так же; Lumen показывает Notify не чаще раза в 30 мин (`eye/EyeGuard.java:255-262`). | SAME | Нет (подробно в области "защита глаз"). |
| 18 | Eco / энергосбережение | Флаг меню `support_energy_saving_mode` (`utils/GmFeatureSupportUtils.java:36`, `FeatureUtils.getSupportEco` `814-816`) в `feature.xml` G0082 отсутствует -> false; узла в дереве dapeng нет. В `current.ini [ECOMODE]` есть таблица `eco_current = 344,295,332` (примерно уровень 3), `tempctrl_pid_eco_mode.json` совпадает с обычным `tempctrl_pid.json` байт в байт. | Нет. | SAME (в стоке тоже нет) | Не добавлять. Тот же эффект даёт уровень яркости 3-4. |
| 19 | Адаптация к окружающему свету (аLOnOff, "яркость / окружение адаптивно") | `support_ambient_light = false`, `support_ambient_light_version = 3` (`feature.xml:103-105`): `getAmbientLight()` true только для 2/7, `getAmbientLightAdaptation()` только для 1 (`FeatureUtils.java:430-450`); узлов в дереве нет. Датчика нет: `lightSensor.ini type = 0; number = 0`; живой `dumpsys sensorservice` -> "No Sensors on the device"; logcat `DisplayPowerController2: cannot find light sensor`. | Нет. | SAME (в стоке тоже нет) | Не добавлять: нет датчика. |
| 20 | Smart Turbo Light | `support_smart_turbo_light = true` (`feature.xml:109`), но узла в дереве dapeng нет (поиск "turbo" = 0). Суть (строки `turbo_light_explain`, `turbo_light_switch_tips`): при сильном внешнем свете оптический зум уменьшает картинку на 0-20 %, чтобы она стала ярче. Запуск: окно `brightness/turbolight/TurboLightDialog.java` (через `Settings://.../turbo_light`) и подсказки systemui `global/turbolight/TurboLightUI.java` (интент `com.xgimi.ststemui.action.test.turbolight`, `com.xgimi.xrmservice` SMART_TL). Решение в вендоре `AL_Process::turboLightThread` по люксам (`ALA_turboLightStatus`, `CustomParam:turboLightTriLux` в `libxgimi.str`). | Нет. | SAME (в меню стока нет; на G0082 почти наверняка не срабатывает без датчика света, LIKELY) | Не добавлять. Если когда-нибудь нужен ручной вариант: у нас уже есть оптический зум (`panel/OpticalZoomPanel.java`), уменьшение картинки зумом и так даёт ту же прибавку яркости. |
| 21 | Тусклый свет в заставке и плавное затухание таймера сна | В стоке нет. | `dream/DreamLamp.java` (приглушение на время заставки, с маркером на случай сбоя), `power/TimerLamp.java` (затухание до 1 перед сном, возврат уровня). Оба не трогают свет при Boost. | EXTRA | Нет. Полезно и безопасно (уровень всегда возвращается). |
| 22 | Сохранение уровня после перезагрузки и сна | Вендор хранит 176 по lumens mode (`Msrv_DataBase_Manage::SaveDlpLumensLevel`, LIKELY). | Так же (вендор). Lumen не пишет 176 после 195(false) (`power/LampControl.java`, описание). | SAME | Нет. |

## Что делать, по приоритету

1. Пункт 10 и 11: стоковые запреты при Boost (цветовое пространство и режим "Мастер-цвет"). Без них
   пользователь меняет настройку, а железо её не применяет (5-сегментный режим не поддерживает смену
   цветового пространства), и он думает, что сломано.
2. Пункт 8: убрать температурный запрет 85 °C и исправить комментарий в `Gmpf2Client.java:127`.
   Сейчас в тёплой комнате Boost может не включиться, а на стоке включится.
3. Пункт 9: одна строка в подсказке, что Boost даёт и чем платит.
4. Пункт 12: после проверки 175 на устройстве добавить запрет слайдера для lumens mode 6/12/15.
5. Пункт 13: страховка слайдера в 3D (одна строка).

## Как проверить на проекторе (когда владелец разрешит)

- Boost: на 10 два раза вправо. В logcat должно смениться `GM_DISP_COLORFIXER: GetCurrentLimit ... lensmode:0`
  на `lensmode:1`. Сейчас `lensmode:0`, то есть Boost выключен.
- Картинка "Офис" и "Производительность": прочитать IGmpf 175 и посмотреть, 12 / 15 ли это.
- После правки 10: в Boost открыть Профессиональные > Цветовое пространство, должна быть подсказка, а не вызов 112.
