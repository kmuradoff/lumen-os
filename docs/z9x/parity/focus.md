# Паритет со стоком: фокус (Z9X, G0082, Lumen 1.0.7)

Область: автофокус, ручной фокус (шаги мотора), автофокус при включении, автофокус после перемещения,
умный автофокус, поведение кнопки фокуса на пульте.

Пути ниже относительно `gsi/`. Сокращения:
- `P/` = `apps/Z9xProjector/src/org/lumen/projector/`
- `SUI/` = `research/z9x_stock_decompiled/jadx/systemui/sources/com/xgimi/systemui/focus/`
- `SET/` = `research/z9x_stock_decompiled/jadx/settings/sources/com/xgimi/`
- `LOG103` = `logs/z9x_zoom_103/live_full3.txt` (живой лог Lumen на Z9X владельца, 14.10)

Проектор <lan-ip> сейчас: Lumen 1.0.7, пакет `org.z9x.projector` versionCode 107, аптайм 8 минут,
владелец смотрит фильм (`com.xgimiru.player`). Читал только getprop, dumpsys package, /proc/bus/input/devices,
keylayout и ini из /mnt/vendor/xgimiconfig. Буфер logcat с момента загрузки уже перезаписан, поэтому живые
доказательства по AF взяты из LOG103.

## Итог в одной таблице

| # | Пункт | Вердикт | Важность |
|---|---|---|---|
| 1 | Автофокус с картинкой-мишенью (событие 335 и др.): Lumen не показывает мишень и не отвечает 307(4), AF падает с EVENT_FLOW_ERR | MISSING | критично |
| 2 | Кнопка фокуса, короткое нажатие: автофокус 307(44) | SAME | |
| 3 | Кнопка фокуса, долгое нажатие: окно ручного фокуса | SAME (детали в 9-12) | |
| 4 | Умный автофокус: в стоке один переключатель включает два режима (IGmpf2 91 + IGmpf 587) | DIFF | высокая |
| 5 | Автофокус после перемещения (587/588) отдельной строкой | EXTRA | |
| 6 | Автофокус при включении (302/303) отдельной строкой | EXTRA | |
| 7 | Сигнал 307(17) при загрузке (запуск AF/AK при включении) | SAME | |
| 8 | "Автофокус" в меню: сток шлёт 301(46), Lumen 307(44) | DIFF (допустимо) | низкая |
| 9 | Окно ручного фокуса: автозакрытие 3 с в стоке, 8 с в Lumen | DIFF | низкая |
| 10 | Кнопка фокуса при открытом ручном фокусе: сток закрывает окно, Lumen оставляет его и потом шлёт 300(2) | DIFF | средняя |
| 11 | Другие кнопки в окне ручного фокуса: сток закрывает окно, Lumen только останавливает мотор | DIFF | низкая |
| 12 | Прокрутка колесом / аэромышью в окне ручного фокуса | MISSING | низкая |
| 13 | Отмена идущего AF кнопкой BACK, переход в ручной фокус по LEFT/RIGHT во время AF | MISSING | средняя |
| 14 | Подсказка после удачного AF "Фокус готов, долгое нажатие для подстройки" (событие 18) | MISSING | низкая |
| 15 | Ошибка AF (событие 2) и упор мотора (0/1) | SAME / лучше | |
| 16 | Картинка пульта на странице фокуса (remote_focus_img_guide) | MISSING | низкая |
| 17 | Компенсация теплового расфокуса (304/305, "AI focus") | SAME (в стоке Z9X скрыта) | опция "лучше стока" |
| 18 | Калибровка фокуса (focus_correction) | SAME (в стоке только в скрытом меню) | |
| 19 | Шаговые кнопки фокуса других пультов, ИК и кнопок на корпусе | SAME / лучше | |
| 20 | Остановка мотора при выключении экрана, после падения процесса | EXTRA (защита) | |

## Что есть в стоке Z9X (дерево настроек dapeng)

`research/z9x_stock_settings/tree_annot.txt:12-16` и `settings_tree_product_dapeng.json` (`page[0].page[7]`):

```
focus_tab (对焦)
  auto_focus          自动对焦       focus.focus.settings.autoFocus
  manual_focus        手动对焦       focus.focus.settings.manualFocus
  smart_auto_focus    智能自动对焦   focus.focus.settings.smart.auto.focus
  remote_focus_img_guide (картинка пульта)
```

Строк "автофокус при включении", "после перемещения" и "AI focus (тепловая компенсация)" на Z9X в стоке нет,
хотя код для них в Settings есть (`SET/focusing/FocusViewModel.java:175,186,191`). Калибровка фокуса
`focus_correction` лежит только в скрытых настройках (`tree_annot.txt:318`).

Кнопка фокуса пульта владельца ("XGIMI RC Keyboard", 000d:3841, `/proc/bus/input/devices`): короткое
нажатие даёт HID F11 (scan 0x70044), долгое нажатие пульт сам шлёт как F4 (0x7003d)
(`research/focus_getevent.txt:28-41`). Стоковый keylayout: `key 87 FOCUS_AUTO`, `key 62 FOCUS_MAN_NEW`
(на устройстве `/vendor/usr/keylayout/Vendor_000d_Product_3841.kl:36,40`).

## Подробно по пунктам

### 1. Автофокус с картинкой-мишенью. MISSING, критично

Сток:
- Вендор в части запусков AF (алгоритм 13 для пульта, `xlab/Autofocus.ini`, `focusTypeTable = {8, 8, 8, 12, 13, 13}`)
  решает, что нужен глобальный AF по камере, и шлёт событие 335 (а на других моделях 333/334, а также 4, 8, 10).
- SystemUI на 333/334/335 открывает полноэкранное окно AutoMaticFA с мишенью
  (`SUI/FocusUIV2.java:644-651`), для 335 это `R.drawable.auto_focus_dapeng` 1920x1080
  (`SUI/AutoMaticFA.java:262-263`, файл `research/z9x_stock_decompiled/jadx/systemui/resources/res/drawable/auto_focus_dapeng.webp`).
- Сразу после показа окна SystemUI шлёт `sendFocusStatus(4)` (AF_UI_OPEN) (`SUI/AutoMaticFA.java:234-240`).
- Окно закрывается по событию 5 (`SUI/FocusUIV2.java:625-628`, `1318-1324`), по таймауту 20 с
  (`SUI/AutoMaticFA.java:438-441`), по опросу `AF2_Get_State_Machine` через 2 с (`SUI/AutoMaticFA.java:126-157`)
  или по кнопке; при закрытии шлёт `sendFocusStatus(5)` и `sendFocusStatus(3)` (`SUI/AutoMaticFA.java:466-470`).

Lumen:
- На 4/8/333/334/335 только тост "Автофокус..." и флаг занятости на 15 с (`P/HalController.java:587-590`).
- Мишень не показывается, 307(4) не отправляется. Метод `sendAfCardClosed` (307(5)+307(3)) закомментирован
  (`P/hal/GmpfClient.java:1351-1358`), константы есть (`P/hal/GmpfClient.java:188-191`).

Доказательство на живом Z9X (LOG103, AF с пульта после оптического зума):
```
02:22:53.382 Z9xHal  sendFocusStatus(44)
02:22:53.640 XLAB_AUTO_FOCUS2 is_need_requeset_UI_img --- m_afMode:44,m_eAlgType:13 --- need requeset UI_img
02:22:54.333 Z9xHal  focusEvent type=335
02:22:54.333 XLAB_AUTO_FOCUS2 fast_global_focus_wait_UI --- wait ui begining
02:22:57.376 XLAB_AUTO_FOCUS2 fast_global_focus_wait_UI --- wait ui open time out
02:22:57.377 focusEvent 330 ... "final_state_after_af":"EVENT_FLOW_ERR", tof_distance -1000
```
То же повторилось в 02:23:14-02:23:18. Из 4 запусков AF с пульта в этом логе 2 прошли быстрым путём
(ToF + датчик Холла, без мишени, EVENT_DONE_OK), а 2, где вендору нужна была мишень, упали через 3 с
без движения мотора. Пользователь видит "Автофокус..." и картинка остаётся нерезкой. Особенно часто это
будет после оптического зума: `P/panel/OpticalZoomPanel.java:341,402` сразу зовёт автофокус.

Исправление (в `P/`, без смены HAL-правил: используются только уже разрешённые коды 307):
1. Новый класс `AfCardOverlay` (окно `TYPE_APPLICATION_OVERLAY` или `TYPE_SYSTEM_ERROR` как в стоке, на весь экран,
   без фокуса ввода, как AkOverlay). Своя мишень 1920x1080 без логотипа XGIMI, но с той же геометрией:
   тёмно-серый фон с сеткой, четыре сектора "звезды" в углах, кольца в центре (алгоритм считает резкость по углам
   и центру, `AFG_CLARITY_CORNER_WIN` в Autofocus.ini). Логотип не копировать.
2. В `HalController.dispatchEvent` на 4, 8, 10, 333, 334, 335: показать окно, после первого кадра
   (`view.post`) отправить `307(4)`. Добавить в GmpfClient метод `sendAfUiOpen()` с константой 4 и разморозить
   `sendAfCardClosed()` (5, затем 3) вместе с вызывающим кодом (правило check_hal_callers.py).
3. Закрывать окно на событиях 5, 7, 2 и по таймауту 20 с; при закрытии один раз `307(5)` + `307(3)`.
4. BACK в окне: закрыть с 5+3 (это отмена AF, как в стоке); LEFT/RIGHT: закрыть с 3 и открыть ручной фокус
   (см. пункт 13).
5. Пока окно открыто, AkOverlay-правила не нарушаются: AF-окно не показывать, если `AkOverlay.isActive()`
   (вендорный AK рисует свою картинку сам).
6. Проверить на проекторе: AF с пульта сразу после зума должен закончиться `EVENT_DONE_OK`, а не
   `wait ui open time out`.

### 2. Кнопка фокуса, короткое нажатие. SAME

Сток: FOCUS_AUTO -> SystemUI intent type 8 -> `autoFocus()` = `sendFocusStatus(44)`
(`SUI/FocusUIV2.java:427-434, 453-455`; 44 = `EN_LED_AIRPLAY_DISCON` в `GmDeviceManager.java:82`).
Режим "профессиональный сдвиг" (кнопка открывает ручной фокус) на Z9X не применяется:
`support_process_tilt_shift_mode=0` в feature.xml.

Lumen: `key 87 FOCUS` (`overlay/v1/keylayout/Vendor_000d_Product_3841.kl:35`) -> KeyReceiver на UP
(`P/KeyReceiver.java:120-123`) -> `requestAutofocus()` -> `307(44)` (`P/HalController.java:650-676`).
Плюс защита: 1 с антидребезг, игнор во время AF, AK, keystone и движения мотора. Это не хуже стока
(сток во время AF просто игнорирует кнопку, `SUI/AutoMaticFA.java:480`).

Исправление: не нужно.

### 3. Кнопка фокуса, долгое нажатие. SAME

Сток: FOCUS_MAN_NEW -> intent type 7 -> сообщение 14 -> `FocusAdjust.showFocusAdjust(true, "remoteControl")`
(`SUI/FocusUIV2.java:445-447, 1332-1337`), при открытии `manualFocus(17)` (`SUI/FocusAdjust.java:314-325`).

Lumen: `key 62 TV_CONTENTS_MENU` -> первый DOWN -> `ManualFocusActivity.open` (`P/KeyReceiver.java:124-127`),
onResume -> `manualFocus(17)` (`P/MotorController.java:87-102`). LEFT -> 300(1), RIGHT -> 300(0), UP -> 300(2),
как в стоке (`SUI/FocusAdjust.java:427-447`, `P/ManualFocusActivity.java:96-105`). У Lumen сверху
сторож 700 мс без повторов клавиши и флаг `manual_moving` на диске: это защита, которой в стоке нет.

Отличия по окну в пунктах 9-12.

### 4. Умный автофокус. DIFF, высокая

Сток: один переключатель `smart_auto_focus`. Клик делает
`setPowerOnAFUseDistance(z)` (IGmpf2 91) и сразу `setDisplacementFocus(z)` = `setAngTriggerAF(z)` (IGmpf 587)
(`SET/focusing/FocusRepository.java:166-184`, `SET/focusing/FocusViewModel.java:159-161`,
`SET/device/GmDeviceManager.java:876-892`). То есть в стоке "умный AF" = AF после перемещения + решение по
ToF-дистанции при включении (порог `AfDistanceOffset = 400` в `xlab/Autofocus.ini`, "только для умного AF").
Состояние строки читается только из IGmpf2 90 (`FocusRepository.java:84-104`).

Lumen: три независимых переключателя (`P/panel/QuickPanelController.java:487-492`): "AF после перемещения"
(587), "AF при включении" (302), "Умный автофокус" (IGmpf2 91). Умный AF в Lumen меняет только 91.
Если владелец включит "Умный автофокус" как в стоке, AF после перемещения останется выключенным, и проектор
не будет фокусироваться после сдвига, хотя в стоке будет.

Исправление: в обработчике строки smartAf (`QuickPanelController.java:491-492`) делать как сток:
`g2.setSmartAutofocus(w)`, затем `g.setMoveAf(w)`, затем перечитать снимок, чтобы строка "AF после
перемещения" показала новое значение. Учесть StandbyController: он временно выключает MOVE_AF в режиме
лампы (`P/power/StandbyController.java:1431-1479`), запись должна идти через тот же путь, чтобы восстановление
после standby не вернуло старое значение. Порядок строк поставить как в стоке: Автофокус, Ручной фокус,
Умный автофокус, затем дополнительные строки (пункты 5, 6).

### 5. Автофокус после перемещения. EXTRA

Сток Z9X эту строку не показывает (её нет в дереве dapeng), управляет ею только через умный AF (пункт 4) и
служебно в калибровке (`SET/calibration/CalibrationManager.java:109-209`).

Lumen: отдельная строка 587/588 (`QuickPanelController.java:487-488`, `P/SettingsActivity.java:138`).
Работает, вреда нет. Оставить как "лучше стока", но после пункта 4 она должна меняться вместе с умным AF.

### 6. Автофокус при включении. EXTRA

Сток Z9X строку не показывает (код есть: `SET/focusing/FocusViewModel.java:191-194` -> `powerOnAFEnable`, 302).
На проекторе владельца HAL отвечает `IsPowerOnAFEnabled: 0` (LOG103, FOCUS_CTL). При включении резкость
в стоке обычно наводит AK при включении (AF после AK, `trigger_type after_ak`, тип hall).

Lumen: отдельная строка 302/303 (`QuickPanelController.java:489-490`, `P/SettingsActivity.java:136`).
Оставить, это рабочая функция вендора.

### 7. Сигнал 307(17) при загрузке. SAME

Сток: `FocusUIV2.start()` один раз шлёт `sendFocusStatus(17)` (`SUI/FocusUIV2.java:386-399`).
Lumen: последним шагом рукопожатия, один раз на boot_id (`P/HalController.java:422-438`), после 237(1) и
окончания бутанимации. Порядок проверен в прошлых релизах. Исправление: не нужно.

### 8. "Автофокус" из меню. DIFF, допустимо

Сток: строка в Settings зовёт `GmDisplayManager.autoFocus((byte) 46)` = IGmpf 301
(`SET/focusing/FocusFunctionManager.java:68-70`), а не 307(44).
Lumen: везде 307(44) (`QuickPanelController.java:483-484`, `SettingsActivity.java:124`, Home tile, голос).
301 запрещён правилами проекта (`P/hal/GmpfClient.java:15`). Вендор считает оба пути "remote control"
(`EN_REMOTE_CONTROL_FOCUS_TYPE`: miskey/APP/voice/zoom, Autofocus.ini), алгоритм тот же 13.
Исправление: не нужно, оставить 44.

### 9. Автозакрытие окна ручного фокуса. DIFF, низкая

Сток: окно закрывается через 3 с после последнего отпускания кнопки
(`SUI/FocusAdjust.java:376-401`, `MicrophoneWindow.HIDE_MENU_DELAY_TIME = 3000`).
Lumen: 8 с (`P/ManualFocusActivity.java:21`).
Исправление: `IDLE_CLOSE_MS = 3_000` (при удержании кнопки окно и так не закрывается).

### 10. Кнопка фокуса при открытом ручном фокусе. DIFF, средняя

Сток: код 2099 (короткое нажатие фокуса) в окне ручного фокуса закрывает окно (`SUI/FocusAdjust.java:405-412`),
а событие начала AF (4/8/335) тоже закрывает ручной фокус (`SUI/FocusUIV2.java:1299-1304, 1338-1342`).
Lumen: кнопка фокуса глобальная, до окна не доходит; KeyReceiver запускает AF (`P/KeyReceiver.java:120-123`),
окно ручного фокуса остаётся открытым в режиме 17. Через 8 с окно закрывается и `exit()` шлёт `manualFocus(2)`
(`P/MotorController.java:127-133`), а по комментарию самого Lumen любой manualFocus прерывает идущий AF
(`P/MotorController.java:24-26`).
Исправление: в `requestAutofocus()` перед `307(44)` закрывать ManualFocusActivity (статическая ссылка на
открытое окно, `finish()`), а в `exit()` не слать 2, если мотор не двигается и `isAfBusy()`. Либо закрывать
окно ручного фокуса в `dispatchEvent` на событиях начала AF (4, 8, 333-335), как сток.

### 11. Прочие кнопки в окне ручного фокуса. DIFF, низкая

Сток: любая кнопка, кроме LEFT/RIGHT/BACK и служебных, закрывает окно (`SUI/FocusAdjust.java:455-459`),
BACK закрывает (`SUI/FocusAdjust.java:478-482`). OK в стоке тоже закрывает (попадает в "прочие").
Lumen: закрывают только BACK/OK/ENTER/ESC; UP/DOWN/MENU лишь останавливают мотор, окно висит до таймера
(`P/ManualFocusActivity.java:107-122`).
Исправление: на любой другой DOWN после `motor.stop()` вызывать `finish()` (громкость не трогать, она идёт
мимо окна).

### 12. Колесо мыши / аэромышь. MISSING, низкая

Сток: прокрутка колесом в окне ручного фокуса даёт импульс 200 мс влево или вправо
(`SUI/FocusAdjust.java:574-600`, сообщения 3/4/5).
Lumen: нет (`ManualFocusActivity` не обрабатывает `onGenericMotionEvent`).
Исправление: `onGenericMotionEvent` с `AXIS_VSCROLL`/`AXIS_HSCROLL`: `motor.start(dir)` и стоп через 200 мс
(через тот же MotorController, сторож уже есть). Актуально только для пультов с мышью.

### 13. Отмена AF и переход в ручной фокус во время AF. MISSING, средняя

Сток: пока открыто окно AF (AutoMaticFA), LEFT/RIGHT/BACK шлют `sendFocusStatus(3)` (стоп AF), закрывают окно,
а LEFT/RIGHT ещё и открывают ручной фокус (`SUI/AutoMaticFA.java:491-503`).
Lumen: окна AF нет, отменить AF нельзя, DPAD уходит в текущее приложение.
Исправление: делается вместе с пунктом 1 (кнопки в AfCardOverlay). Для быстрого AF без мишени (hall/ToF,
длится около 1 с, LOG103 `af hall total use 1161ms`) отмена не нужна.

### 14. Подсказка после удачного AF. MISSING, низкая

Сток: событие 18 со значением `AF_Success_Into_Adjust` -> тост "Focus complete, long-press the focus button
for fine-tuning", если не вставлена насадка-линза (`SUI/FocusUIV2.java:633-637`,
`systemui/resources/res/values/strings.xml:510`).
Lumen: на 18 ничего; тост "Автофокус" показывается при нажатии (`P/HalController.java:672`).
Событие 18 на Lumen приходит (LOG103: `focusEvent type=18 value=AF_Success_Into_Adjust`).
Исправление: в `onFocusEvent` на 18 с этим значением короткий тост "Фокус готов. Долгое нажатие кнопки фокуса:
точная подстройка" (свой текст, все языки). Проверку насадки-линзы пропустить, у Z9X её нет.

### 15. Ошибка AF и упор мотора. SAME / лучше

Сток: событие 2 -> тост "Camera initialization failed, please restart the system" (`SUI/FocusUIV2.java:617-619`,
`1312-1314`); 0/1 (упор) -> только анимация (`SUI/FocusUIV2.java:610-612`).
Lumen: 2 -> "Автофокус не удался"; 0/1 -> стоп мотора 300(2) (`P/HalController.java:594-599`). Не хуже.
Событие 7 (таймаут UI, см. пункт 1) Lumen не обрабатывает; после исправления пункта 1 оно должно закрывать
окно AF.

### 16. Картинка пульта на странице фокуса. MISSING, низкая

Сток: `remote_focus_img_guide` (картинка пульта с кнопкой фокуса) под строками фокуса; ещё есть разовый гид
при первом нажатии, но только для пультов B980C/B990C (`SUI/FocusUIV2.java:1531-1554`).
Lumen: нет.
Исправление: строка-подсказка внизу страницы фокуса (не отключённая строка, а текст): "Кнопка фокуса: короткое
нажатие = автофокус, долгое = ручной фокус". Без картинок XGIMI.

### 17. Компенсация теплового расфокуса. SAME (скрыто в стоке), опция "лучше"

Сток: код есть (`SET/focusing/FocusRepository.java:154-158`, IGmpf 304/305 `focusAutoCompensateEnable`),
строки на Z9X нет. В `xlab/Autofocus.ini` секция `DF_ALG_PARAMETERS`, первый параметр
`DF_DEFAULT_SWITCH_STATUS = 0` (по умолчанию выключено), подстройки на 5, 10, 20 минутах после включения.
Lumen: нет.
Исправление (по желанию владельца, после теста): строка "Подстройка фокуса при нагреве" с 305/304.
До проверки на проекторе не добавлять: вендор снимает камерой текущий кадр и двигает мотор во время фильма.

### 18. Калибровка фокуса. SAME

Только в скрытом меню стока, заводская функция. Не переносить.

### 19. Шаговые кнопки фокуса других пультов. SAME / лучше

Сток: коды 2113/2114, 156/157, 2097/2098 работают только внутри окна ручного фокуса (`SUI/FocusAdjust.java:427-447`).
Lumen: `MEDIA_STEP_BACKWARD/FORWARD` (IR 193/194, 61/62; BT 67/68; кнопки корпуса 253/254) двигают мотор
пока кнопка нажата, из любого места, только от устройств XGIMI (`P/KeyReceiver.java:128-141`,
`P/Z9xKeys.java:17-20`). Не хуже стока.

### 20. Защита мотора. EXTRA

Lumen останавливает мотор на любой первой кнопке, на выключении экрана (`P/App.java:153-160`), после
перезапуска процесса и HAL по флагу `manual_moving` (`P/MotorController.java:14-27`). В стоке такого нет.
Оставить.

## Что сделать (по порядку)

1. Пункт 1: окно мишени AF + 307(4) / 307(5)+307(3). Без этого автофокус после зума и в части случаев с
   пульта не работает. Нужна проверка на проекторе (вендорный лог `fast_global_focus_wait_UI`).
2. Пункт 4: умный AF меняет и 91, и 587, как сток.
3. Пункт 10: AF закрывает окно ручного фокуса и не прерывается его закрытием.
4. Пункты 9, 11: окно ручного фокуса закрывается через 3 с и от любой другой кнопки.
5. Пункты 13, 14, 16: отмена AF кнопками, тост "фокус готов", подсказка про кнопку.
6. По желанию: пункт 12 (колесо), пункт 17 (тепловая подстройка, только после теста).

Все исправления только в `apps/Z9xProjector` (общий код Z9X); на Play 6 это не влияет, если не копировать
файлы в `apps/Play6Projector`.
