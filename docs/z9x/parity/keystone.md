# Паритет с стоком: трапеция, авто-коррекция, зум, сдвиг, поворот (Z9X)

Дата: 2026-10-30. Устройство: Z9X (G0082, horse_popular, MT9952), Lumen OS 1.0.7 (`lumen-1.0.7-20261028anp`).
Только чтение: дерево gsi, jadx стоковых настроек, дерево настроек Z9X, таблица кодов IGmpf, пассивные чтения
с проектора <lan-ip> (getprop, `cat` конфигов xgimiconfig, `logcat -d`). Ничего не менялось.

Сокращения путей:
- `ST/` = `research/z9x_stock_decompiled/jadx/settings/sources/com/xgimi/`
- `L/` = `apps/Z9xProjector/src/org/lumen/projector/`
- коды IGmpf / IGmpf2 из `research/play6/z9x_gmpf_codes.txt`

## 0. Что вообще есть в стоке Z9X (дерево и feature.xml)

Дерево настроек Z9X (`research/z9x_stock_settings/tree_annot.txt:2-27`), раздел «画面校正»:

| узел | что это |
|---|---|
| `keystone_non_inductive_real_time` | авто-коррекция в реальном времени (после сдвига проектора) |
| `auto_keystone_ak3` | авто-коррекция одной кнопкой |
| `boot_autoKeystone_v5` | авто-коррекция при включении |
| `projection_area_self_adapt` | обход препятствий |
| `curtain_projection_self_adapt` | вписать в экран (выравнивание по полотну) |
| `manual_keystone` | ручная трапеция (4 угла) |
| `zoom_and_move` | «масштаб и сдвиг» (оптический зум + сдвиг картинки) |
| `picture_screen_rotation` | поворот картинки |
| `reset_keystone` | вернуть картинку по умолчанию |
| `projection_settings.*` | авто / стол / потолок / задняя проекция |

Чего в дереве Z9X НЕТ: 8-точечной и многоточечной коррекции, цифрового зума, «вписать в экран сейчас»
отдельной кнопкой, калибровок (только заводские).

`/mnt/vendor/xgimiconfig/G0082/feature/feature.xml` (прочитан с проектора; в дампе `fs/mnt_vendor_xgimiconfig.tar`
пустой, 0 байт):
- `support_kst_adjust_type=2` (AK), `support_ak_two=true`, `support_optical_zoom=true`, `stepless_zooming=false`
- `support_screen_zoom=1` (цифрового зума НЕТ), `support_tiltshift_type=0` (сдвига объектива нет),
  `support_zoom_and_displacement_type=1` (новый экран «масштаб и сдвиг»)
- `support_Trapezoid_GAME=true`, `support_Trapezoid_3D=0`
- `unsupported_inversion_functions=1_1_1_0_1_1`: индекс 0 = «AK в реальном времени при потолочной установке»,
  значение 1 = ПОДДЕРЖИВАЕТСЯ (`ST/common/utils/FeatureUtils.java:581-588` getSupportTimelyAKUnderHoisting
  возвращает `get(0) == "1"`). Это важно для п. 2.
- `user_manu_keystone_step_x/_y` не заданы, значит действуют умолчания 6.0 / 3.375 (`ST/keystone/KeyStoneManager.java:92-117`).

`kstAdjust/kstAdjust.ini` (с проектора): `KstAdjustDeviceType=10` (гибрид), `KstDefaultMode=0` (2x2),
`realTimeKstType=1` (real-time AK идёт через MTK trapezoid, то есть через GPU-путь HWC), `[HybridKst] sw_width=3840
sw_height=2160 process_sw_enable=1`.

## 1. Сводная таблица

| # | Пункт | Вердикт | Коротко |
|---|---|---|---|
| 1 | Авто-коррекция сейчас | SAME | 326 autoKst, как сток |
| 2 | Авто-коррекция в реальном времени | DIFF | Lumen запрещает её на потолке, сток Z9X разрешает |
| 3 | Авто-коррекция при включении | SAME | 552 / 553 |
| 4 | Обход препятствий | SAME | 292 / 291 и сцепка с «вписать в экран» как в стоке |
| 5 | Вписать в экран (переключатель) | SAME | 290 / 289 и сцепка |
| 6 | «Вписать в экран сейчас» | EXTRA | 272(11), в стоке Z9X такого пункта нет |
| 7 | Ручная трапеция 4 угла | DIFF | вызовы те же, но нет 194 и нет 18 (SW-превью), рендер только DLPC8445 |
| 8 | Сброс трапеции | DIFF | Lumen шлёт полный кадр 185; сток 207 + 181(50) + зум домой + 46(3) |
| 9 | Оптический зум | DIFF (мелкое) | всё основное есть, нет 194 и 158 при закрытии |
| 10 | Сдвиг картинки (часть «масштаб и сдвиг») | MISSING | код 141 есть, но экран не подключён |
| 11 | Цифровой зум | SAME (нет и там, и там) | `support_screen_zoom=1` |
| 12 | Поворот картинки | DIFF (мелкое) | нет 18, «сброс» возвращает к углу открытия, а не к 0 |
| 13 | GPU-путь трапеции (HWC) | DIFF (главное) | 7 свойств модели G0082 не грузятся, SW-трапеция выключена |
| 14 | 8 точек / многоточечная | SAME (нет и там, и там) | в дереве Z9X нет |
| 15 | Обход препятствий / вписать: сервис smart_ak_server | SAME | падает и в стоке, и в Lumen |
| 16 | Окно авто-коррекции (паттерн, подтверждения 146) | SAME с известным пробелом | шаги «Detect» не рисуются |

Ниже подробно по каждому пункту.

## 2. Подробно

### 1. Авто-коррекция сейчас (одна кнопка) - SAME

- Сток: `ST/keystone/KeyStoneViewModel.java:712` (`ACTION_AUTO_KEYSTONE`) -> `ST/keystone/KeyStoneManager.java:130-150`:
  на Android >= O (`ST/common/ext/XgimiDeviceKt.java:11`) вызывает `GmDisplayManager.autoKst()` = IGmpf 326; из
  быстрого меню (`com.xgimi.misckey`) `newAutoKst(9)` = 272(9). Окно паттерна рисует systemui по событиям 106-118.
- Lumen: кнопка «Auto keystone now» `L/panel/QuickPanelController.java:498-499` и `L/SettingsActivity.java:128`
  -> `L/Hal.java:212` -> `L/HalController.java:682-698, 704+` -> 326. Перед этим проверка готовности UI (390/394),
  остановка мотора фокуса, запрет второго запуска. Окно паттерна: `L/ak/AkOverlay.java` (протокол стоковых
  AK2Window/AkAnimView, подтверждения 146).
- Вердикт: SAME. Исправлять нечего.

### 2. Авто-коррекция в реальном времени - DIFF

- Сток: `ST/keystone/KeyStoneViewModel.java:733-735` -> `setAccTriggerAK` -> IGmpf 585, чтение 586
  (`ST/keystone/KeyStoneManager.java:152-160`). При переходе на потолок 585(false) шлётся ТОЛЬКО если
  `getSupportTimelyAKUnderHoisting()` ложно (`ST/pojector/ProjectionViewModel.java:292-295`). Для G0082 индекс 0 в
  `unsupported_inversion_functions` равен 1, то есть на Z9X real-time AK на потолке РАБОТАЕТ и не выключается.
- Lumen: `L/Hal.java:287-297` `setMoveAkChecked` отказывает во включении 585, если 173 = потолок (1/3);
  `L/panel/QuickPanelController.java:1401-1405` при переходе на потолок всегда шлёт 585(false);
  `L/panel/QuickPanelController.java:1517` делает строку неактивной на потолке (и это ещё и нарушает правило
  «не показывать неактивные строки»). Правило пришло из общего FEATURE_SPEC 2.2, а `docs/play6/SETTINGS_PLAN.md:87`
  уже отмечает, что в стоке такого правила нет.
- Вердикт: DIFF (у владельца на столе разницы нет; на потолке Lumen хуже стока).
- Исправление: для Z9X убрать потолочный запрет: в `setMoveAkChecked` не читать 173 (или читать индекс 0 из
  feature.xml G0082 и отказывать только при `0`), в `askPutMode` не слать 585(false) при `toCeiling`, в
  `applySnapshot` передавать `enabled=true`. Остальное (201 false при переходе на потолок) оставить, оно как в стоке.

### 3. Авто-коррекция при включении - SAME

- Сток: `ST/keystone/KeyStoneViewModel.java:702-704` -> `KeyStoneManager.setBootAk` -> 552, чтение 553
  (`ST/keystone/KeyStoneManager.java:342-348`).
- Lumen: `L/panel/QuickPanelController.java:526-527`, `L/hal/GmpfClient.java:648-649` (552/553), также
  `L/SettingsActivity.java:137`. Окно паттерна при старте рисует тот же AkOverlay.
- Вердикт: SAME.

### 4. Обход препятствий - SAME

- Сток: `ST/keystone/KeyStoneViewModel.java:497-499` -> `ST/keystone/KeyStoneRepository.java:585-612`: 292
  setProjScreenAdp; выключение также выключает «вписать в экран» (290 false).
- Lumen: `L/panel/QuickPanelController.java:532-533` -> `L/Hal.java:278-281` (292, при OFF ещё 290 false).
- Вердикт: SAME.

### 5. Вписать в экран (переключатель) - SAME

- Сток: `ST/keystone/KeyStoneViewModel.java:760-762` -> `ST/keystone/KeyStoneRepository.java:614-637`: 290
  setProjCurtainAdp; включение также включает обход препятствий (292 true).
- Lumen: `L/panel/QuickPanelController.java:530-531` -> `L/Hal.java:272-275`.
- Вердикт: SAME.

### 6. «Вписать в экран сейчас» - EXTRA

- Сток Z9X: отдельной кнопки нет (в дереве только переключатель; `ACTION_AUTO_KEYSTONE_SHIFT_CURTAIN` = 272(24)
  в коде есть, но узла в дереве Z9X нет).
- Lumen: `L/panel/QuickPanelController.java:500-501`, `L/SettingsActivity.java:129-133` -> 272(11), только при
  включённом 290.
- Вердикт: EXTRA. Оставить. Режим 11 подтверждён только логами зума (`docs/z9x/OPEN_zoom_103.md`), этого хватает.

### 7. Ручная трапеция 4 угла - DIFF

- Сток (`ST/keystone/view/keystone/KeyStoneWind.java`):
  - при открытии: 194 setPushPullScreenState(1) (:708), 586 и при включённом real-time 573(2,false) (:732-735);
  - на первую стрелку: IGmpf2 18 setKstPrepare(true,0) (`notifyKeystone(true,0)`, :1120);
  - на каждую стрелку: 186 чтение -> сдвиг угла на 6.0 / 3.375 -> 185 -> 150 (:309-338,
    `ST/keystone/KeyStoneManager.java:378-414`);
  - MENU открывает левое меню, на Z9X в нём только «Сброс» (8 точек показываются лишь при `support_laser_device`
    или `user_manu_keystone_type=1`, у G0082 их нет; `ST/keystone/view/keystone/KeyStoneLeftMenu.java:212-260`);
    сброс = 207(0) (`setCorrectKeystoneReset`, :1497-1500);
  - при закрытии: 18(false,0) (:996), 158(0) (:1030), 573(2,true) (:1032), 194(0) (:1034).
- Lumen: `L/panel/ManualKeystonePanel.java`: те же 586/573, 186, 150, 185 в координатах 3840x2160, шаг 6.0 / 3.375
  (:183), ускорение при удержании, проверки чтением 186, 158 при выходе (:812), восстановление 573 после
  смерти процесса. Отличия:
  - 18 setKstPrepare не шлётся (`persist.z9x.kst_prepare=0`, на проекторе так и есть; :509-525), значит
    превью и итог идут только через HW warp DLPC8445 (см. п. 13);
  - 194 setPushPullScreenState(1)/(0) не шлётся (в RotatePanel и ZoomShiftPanel шлётся, здесь нет);
  - сброс: удержание OK вместо MENU -> «Сброс»; делается полным кадром 150 -> 185, а не 207(0).
- Вердикт: DIFF. Шаги и координаты как в стоке, но рендер и два служебных вызова другие.
- Исправление:
  1. Добавить 194(1) после проверки входа и 194(0) в выходе (через уже существующий
     `Gmpf2Client.setAdjustScreenOpen`, `L/hal/Gmpf2Client.java:212`), как в RotatePanel:100/161.
  2. После п. 13 (свойства модели) включить 18 по умолчанию: `persist.z9x.kst_prepare=1`, сначала только для теста.
  3. Сброс внутри экрана перевести на стоковый 207(0) (см. п. 8), полный кадр оставить запасным путём.

### 8. Сброс трапеции - DIFF

- Сток: `ST/keystone/KeyStoneViewModel.java:555-559` -> `ST/keystone/KeyStoneManager.java:254-276` и
  `systemui/.../gmuiapi/api3/V3DisplayManager.java:185-190, 318-320`:
  1. IGmpf 207 correctKeystoneReset(0); если 130 getCurrentKeystoneMode == 6, то 129 switchKeystoneMode(0,0);
  2. IGmpf 181 correctKeystone(50) (`setLowConfigureKeyStoneValue(50)`, старое одномерное значение в центр);
  3. `isInfiniteZoom` у Z9X нет; `isOpticalZoom` есть -> IGmpf 153 zoomControl(5,10) (объектив домой, самая большая
     картинка);
  4. сдвиг объектива: у Z9X нет (`support_tiltshift_type=0`), 88 не шлётся;
  5. IGmpf2 46 doKeystoneRatio(3); если false, тост `keystone_reset_toast`.
  Вероятно, 207 в вендоре сбрасывает и угол поворота, и сдвиг картинки 141 (не проверено). Что именно
  сбрасывает full-frame 185, тоже не проверено.
- Lumen: `L/panel/QuickPanelController.java:516-520, 1323-1356`: подтверждение, 647 не «top speed», 573 пауза,
  186 чтение, 150 + 185 полного кадра, 158(0). 207 разрешён только для режима игры «top speed»
  (`L/hal/GmpfClient.java:606-617`), 46 закомментирован (`L/hal/Gmpf2Client.java:426-427`), 153(5,10) в сбросе
  не шлётся, 181 нет.
- Что видно владельцу: после авто-коррекции с «вписать в экран» объектив уехал внутрь; стоковый сброс вернул бы
  полный размер (153(5,10)), сброс Lumen оставляет картинку маленькой. Поворот, сделанный в RotatePanel, сбросом
  Lumen, возможно, не убирается (комментарий `L/panel/RotatePanel.java:38` это обещает, но не проверено).
- Вердикт: DIFF.
- Исправление: коды теперь известны (раньше комментарий в :1313 говорил, что нет). Сделать сброс как в стоке:
  207(0) -> 130 и при 6 129(0,0) -> 181(50) -> 153(5,10) (уже есть `GmpfClient.zoomHome`, :886) -> IGmpf2 46(3)
  -> 158(0), в обёртке 573. Открыть 207 для этого вызова (новый метод `resetKeystoneStock()` рядом с
  `resetKeystoneForTopSpeedGame`), раскомментировать `resetKeystoneRatio`. После сброса сбросить сохранённую
  позицию зума в OpticalZoomPanel (`K_USER`), чтобы она не вернулась сама. Проверка: 186 после сброса = полный кадр,
  199 текущий угол = 0, 116 = 0.

### 9. Оптический зум - DIFF (мелкое)

- Сток: `zoom_and_move` -> `ST/keystone/view/zoommove/ZoomMoveWind.java:101-160` (194(1) при открытии) ->
  `ZoomMoveView` = `ZoomStateView` (зум) + `DisplacementView` (сдвиг). Зум: 121 zoomStart, 18(true,2) на первую
  клавишу (`zoom/ZoomStateView.java:617-621`), шаг `zoomControl(0|1,10)` = 119(116 +- 1)
  (`ST/keystone/KeyStoneManager.java:191-199`); при закрытии 120, 18(false,2), автофокус, если объектив сдвинулся
  (`zoom/ZoomStateView.java:1232-1267`); затем 158(0) и 194(0) (`ZoomMoveWind.java:333-334`).
- Lumen: `L/panel/OpticalZoomPanel.java`: 18(true,2) + 121 (:164), 116/117/119, обход ошибки датчика «половинного
  шага», 153(5,10) у широкого края, 120 + 18(false,2) (:353), автофокус после сдвига (:341, :402), восстановление
  позиции после AK. Нет: 194(1)/(0) и 158(0) при закрытии. Строка в панели: `L/panel/QuickPanelController.java:508-510`.
- Вердикт: DIFF (мелкое; по движению зума Lumen лучше стока, потому что обходит ошибку датчика).
- Исправление: в enter после 121 добавить `g2.setAdjustScreenOpen(true)`, в close после 18(false,2) добавить
  158(0) (если был сдвиг) и `setAdjustScreenOpen(false)`, восстановление сессии по маркеру дополнить так же.

### 10. Сдвиг картинки (вторая половина «масштаб и сдвиг») - MISSING

- Сток: `ST/keystone/view/zoommove/displacement/PtzViewModel.java:215-262`: стрелки -> 141 translationKst(dir, true),
  при 0 -> 141(dir, false); 573(2,false) на время; это есть на экране `zoom_and_move` Z9X (у G0082 нет сдвига
  объектива, поэтому сдвиг программный, движком трапеции). Работает, когда картинка меньше панели (после зума внутрь
  или трапеции).
- Lumen: `L/hal/GmpfClient.java:620-638` `shiftPicture` (141) есть, `L/panel/ZoomShiftPanel.java` (шаги 141 и 118)
  написан, но в интерфейс не выведен: только `ZoomShiftPanel.install` в `L/App.java:85` для восстановления
  маркера, в панели строки нет (`L/panel/QuickPanelController.java:506-507`). Его половина «размер» шлёт 118
  (цифровой зум), которого у G0082 нет (`support_screen_zoom=1`).
- Вердикт: MISSING.
- Исправление: в OpticalZoomPanel добавить режим «Сдвиг» по OK (как стоковые вкладки): стрелки = 141(dir,true),
  при 0 -> 141(dir,false), шаг вендорный, 573 пауза уже есть в коде ZoomShiftPanel (:110-141, :195-203), его и
  перенести. 118 в Z9X не использовать. Сбрасывается стоковым сбросом (п. 8, 207).

### 11. Цифровой зум - SAME

- Сток Z9X: `support_screen_zoom=1` (нет), `isInfiniteZoom` нет, узлов `zoom_only` / `digital_zoom_*` в дереве нет.
- Lumen: в интерфейсе нет. Вердикт: SAME.

### 12. Поворот картинки - DIFF (мелкое)

- Сток: `ST/keystone/view/RotateWind.java`: 194(1) и 573(2,false) (:283-288), 199 диапазон, на клавишу
  IGmpf2 207 setScreenRotation(5|6, 0.5) (:689-725, шаг `getRotateAngle`, у G0082 0.5, :395), 18(true,0) на первую
  клавишу (:651), MENU -> «Сброс» = поворот обратно в 0 градусов (:842-862), при закрытии 158(0), 194(0) (:603-604),
  18(false,0) (:638), 573(2,true) (:749).
- Lumen: `L/panel/RotatePanel.java:100-163`: 194, 573, 199, 207(5|6, 0.5), 158, 194(0), 573(true) как в стоке.
  Отличия: 18(true/false,0) не шлётся; OK возвращает к углу, с которым экран открылся, а не к 0.
- Вердикт: DIFF (мелкое).
- Исправление: добавить «Сброс в 0» (OK долгим нажатием или отдельная подсказка): 207(6, cur) при cur > 0, 207(5,
  |cur|) при cur < 0, как сток; 18 включить вместе с п. 13.

### 13. GPU-путь трапеции (HWC) - DIFF, главное

Подробно в `docs/z9x/KEYSTONE_STOCK_VS_LUMEN.md`, здесь итог и проверка 2026-10-30:
- Сток: системный `init` грузит `/vendor/etc/xgimimodelname/G0082/build.prop` (блок `##trapezoid`): `vendor.xgimi.hwc.aa`,
  `vendor.mstar.trapezoid.4K.enable`, `.dual.gop.mode`, `.video.gop`, `.resolution.switch`, `.secure.enable`,
  `vendor.mstar.hwc.tcvideo.format`. В стоковом дампе `z9x-firmware-dump/info/surfaceflinger.txt` трапеция рисуется
  в HWC на GPU (4K, две GOP, `secure:[1|1]`, сглаживание). Гибрид (kstAdjust.ini) сам выбирает DLPC8445 или GPU.
- Lumen на проекторе сейчас: `persist.z9x.kst_prepare=0`, 7 свойств трапеции отсутствуют (getprop), трапеция только
  через DLPC8445. Real-time AK (`realTimeKstType=1`) тоже идёт по GPU-пути, поэтому без `secure.enable` DRM-видео
  во время real-time коррекции даёт зелёно-красный мусор (это и записано в `L/panel/ManualKeystonePanel.java:157-162`).
- Вердикт: DIFF. Это и есть причина «неровной» картинки при сильной трапеции и мусора на DRM.
- Исправление (только Z9X, без правки vendor): rc-файл в `overlay/v1` (например `init.z9x.trapezoid.rc`) с 7
  `setprop` до старта `vendor.hwcomposer-3`, по условию `ro.boot.xgimi.modelname=G0082`; затем
  `persist.z9x.kst_prepare=1` для теста ручной трапеции, зума и поворота; затем сменить умолчание. Риски и план
  проверки в KEYSTONE_STOCK_VS_LUMEN.md, разделы 5-6 (цикл перезапуска HWC, переключатель разрешения UI, TEE).
  `tools/lumen_v1.sh` и `overlay/v1` сейчас эти свойства не задают.

### 14. 8 точек и многоточечная коррекция - SAME

- Сток: код `MorePointKeystoneDialog`, `keystone_8_bt_id` есть, но на Z9X не показывается (п. 7; узлов в дереве нет).
- Lumen: нет. Вердикт: SAME. Возможная надстройка потом (IGmpf2 15-17, шаблоны 33x33/65x65 в vendor), не для паритета.

### 15. smart_ak_server (вендорный сервис «вписать в экран») - SAME

- Сток: в `z9x-firmware-dump/info/props.txt` `init.svc.smart_ak_server=restarting`,
  `sys.init.updatable_crashing_process_name=smart_ak_server`, то есть он падал и на стоке.
- Lumen (logcat -b crash сейчас): `CANNOT LINK EXECUTABLE "/vendor/bin/smart_ak_server": cannot locate symbol
  "_ZN2cv5splitERKNS_11_InputArrayERKNS_12_OutputArrayE" referenced by "/vendor/lib/libseg.so"`,
  `init.svc.smart_ak_server=stopped`.
- Вердикт: SAME (сломан и там, и там; вписывание в экран делает сам AK по 290/292). Чинить не нужно для паритета.
  Если захочется лучше стока: найти, какой libopencv ждёт libseg.so (в vendor нет `cv::split`), отдельная задача.

### 16. Окно авто-коррекции (паттерн и подтверждения) - SAME с известным пробелом

- Сток: systemui AK2Window / AkAnimView, подтверждения IGmpf 146 по шагам 106-118, карточки результатов 105.
- Lumen: `L/ak/AkOverlay.java:41-75`, `L/ak/AkNotices.java`. Пробел, записанный в коде: шаги «AK_Ui_Mode_Detect»
  не рисуются и не подтверждаются (вендор ждёт 5 с и идёт без UI); у карточек «вписать в экран» нет 192
  notifyAutokst(0) и кнопки «ручное позиционирование».
- Вердикт: SAME по результату. Исправление по желанию: после проверки на устройстве, какой триггер шлёт Detect,
  нарисовать паттерн окружения и подтверждать его.

## 3. Порядок работ (что делать)

1. п. 13: rc со свойствами модели G0082 + тест `persist.z9x.kst_prepare=1` (главное, влияет на качество картинки,
   DRM-видео при real-time AK, ручную трапецию, зум и поворот).
2. п. 8: стоковый сброс (207, 129, 181(50), 153(5,10), 46(3), 158) вместо полного кадра.
3. п. 2: убрать потолочный запрет real-time AK для G0082.
4. п. 10: сдвиг картинки (141) внутри экрана оптического зума.
5. п. 7, 9, 12: 194 вокруг ручной трапеции и зума, 158 после зума, «сброс в 0» в повороте.

Всё это только код Z9xProjector и overlay/v1 образа Z9X; общий с Play 6 код не трогается.

## 4. Как проверить на проекторе (после сборки, не сейчас)

- `logcat -v time Z9xPanelKst:V Z9xZoom:V Z9xRotate:V Z9xPanel:V Z9xHal:V '*:S'` во время каждого действия.
- Сброс: до сброса сделать AK с «вписать в экран» (объектив внутрь) и поворот 2 градуса; после сброса 186 = полный
  кадр, 199 cur = 0, 116 = 0, картинка во весь размер.
- Real-time на потолке: выбрать «Потолок», переключатель должен включаться и оставаться включённым.
- Сдвиг: зум на 3 позиции внутрь, OK -> сдвиг, стрелки двигают картинку до края (141 возвращает -1), сброс
  возвращает в центр.
- GPU-путь: `dumpsys android.hardware.graphics.composer3.IComposer/default` должен показать «HWC Trapezoid Params»
  с `secure:[1|1]`, DRM-приложение без мусора при включённом real-time AK.
