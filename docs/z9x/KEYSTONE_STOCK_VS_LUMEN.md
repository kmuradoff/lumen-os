# Трапеция (keystone) на Z9X: сток против Lumen

Дата: 2026-10-30. Устройство: Z9X (G0082, horse_popular, MT9952/mt5877), Lumen OS 1.0.7.
Только чтение: дамп стока, дерево gsi, пассивные чтения с проектора владельца (getprop, service list,
dumpsys composer/SurfaceFlinger, logcat -d, cat конфигов). Ничего не менялось.

## Коротко

1. На Z9X сток рисует программную трапецию НЕ в SurfaceFlinger, а в вендорном HWC
   (`/vendor/bin/hw/mtkhwcomposer`, класс `HWComposerGLComposite::drawTrapezoid`, GLES на Mali-G57).
   Там же живёт HAL `vendor.mediatek.hardware.trapezoid.ITrapezoid/default`. Это vendor, Lumen его
   сохраняет. Патч SurfaceFlinger как на Play 6 здесь не нужен.
2. Кроме этого есть аппаратный warp в DLP-контроллере DLPC8445 (`SetWarpMap` по I2C). Вендорный
   libxgimi работает в гибридном режиме: решает сам, где делать коррекцию, HW (8445) или SW (HWC GL).
3. Главная разница: вендорный HWC включает правильный режим SW-трапеции (4K-выход, две GOP,
   сглаживание XGIMI, защищённый путь для DRM) только по свойствам `vendor.*` из файла
   `/vendor/etc/xgimimodelname/G0082/build.prop`. Этот файл грузит СТОКОВЫЙ системный `init`
   (строка `/vendor/etc/xgimimodelname/%s/build.prop` в `system/bin/init`). Init LineageOS этого не
   делает, поэтому на Lumen 7 нужных свойств нет. Из-за этого SW-трапеция на Lumen считается не в той
   геометрии и без сглаживания, а Lumen 1.0 в ответ отключил её совсем (`persist.z9x.kst_prepare=0`)
   и всегда работает только через HW warp 8445. Сток в установившемся режиме для такой сильной
   трапеции использовал SW-путь (доказательство ниже).
4. `system/lib*/vendor.mediatek.hardware.trapezoid-V1-ndk.so` в стоковом system ни одна системная
   программа не использует (нет NEEDED ни у одного ELF, есть только запись в
   `product/etc/vintf/compatibility_matrix.xml`). Это ложный след.

## Статус (2026-10-30)

Шаги 1 и 2 плана сделаны в коде, на проекторе ещё не проверены:
- Шаг 1, вариант A: `overlay/v1/z9x_trapezoid.rc` (в образе `/system/etc/init/z9x_trapezoid.rc`, подключён в
  `tools/lumen_v1.sh` через RCS, предпроверка требует точное содержимое). Триггер
  `on early-init && property:ro.boot.xgimi.modelname=G0082`: `ro.boot.*` задаются из командной строки ядра до
  early-init, а HWC (класс hal) стартует позже. Только `setprop`, без exec и ожиданий.
- Шаг 2: `ManualKeystonePanel.ensurePrepared` шлёт 18 setKstPrepare(true,0) / (false,0) по умолчанию
  (`persist.z9x.kst_prepare` по умолчанию 1). Если на проекторе владельца это свойство раньше было выставлено в 0
  вручную, его надо выставить в 1 тем же способом, каким выставляли 0, иначе останется только HW warp. В
  logcat `Z9xPanelKst` видно, что выбрано: "18 setKstPrepare(true,0) -> ..." или "18 setKstPrepare not sent".
- Проверка и откат: раздел 6 ниже; откат без перепрошивки: `persist.z9x.kst_prepare=0` (только ручная трапеция),
  полностью: образ 1.0.7.

## 1. Как делает сток (доказательства)

### 1.1 Кто в стоковом system знает про трапецию

Поиск по всему system, system_ext, product (grep по ELF, jar):

| Файл | Что там |
|---|---|
| `system/bin/init` | `/vendor/etc/xgimimodelname/%s/build.prop`: грузит свойства модели (ключевой момент) |
| `system/bin/surfaceflinger` | только `vendor.mstar.trapezoid.secure.enable`; шейдеров warp нет (в отличие от Play 6) |
| `system/lib*/libstagefright.so` | `vendor.mstar.trapezoid.enable`, "Trapezoid Configuring native window for tunneled mode with transform %d" |
| `system/bin/keydetectservice` | читает `vendor.mstar.trapezoid.enable` |
| `system_ext/lib*/libstagefright_utils.mtk.so` | `vendor.mstar.trapezoidCorrect.type` |
| `system/lib*/vendor.mediatek.hardware.trapezoid-V1-ndk.so` | интерфейс есть, но его никто не грузит |

То есть системная часть стока не вызывает HAL трапеции. Вызовы идут: стоковые настройки (Java) ->
IGmpf (`xgimi.hardware.gmpf`, вендорный libxgimi) -> HAL трапеции в процессе HWC или DLPC8445.

### 1.2 HAL трапеции и HWC (vendor)

- `vendor.mediatek.hardware.trapezoid-impl.so` и HAL регистрируются внутри `mtkhwcomposer`
  ("Instantiating ITrapezoid service...", `Trapezoid::m_pInstance`). Отдельный `trapezoidServer`
  стартует только при `vendor.xgimi.trapezoid.enable=1` (у G0082 его нет).
- Параметры HAL: 8 точек (LT, MT, RT, LM, RM, LB, MB, RB, `std::array<Point,8>`), углы, zoom,
  поворот видео, зеркало, тип коррекции (`TRAPEZOID_CORRECT_TYPE_BS_OFFSET / QC_OFFSET / HV_ROTATE`),
  сохранение в `/vendor/persist/trapezoid.ini`. Сетки 9x9 или 81 точки в HAL нет.
- Рендер: `HWComposerGLComposite::drawTrapezoid`, `HWCTrapezoidVertexGererator`, `generateMvp`.
  Фрагментный шейдер делает настоящую перспективу (деление `ptiOut.xy / ptiOut.z`, гомография), внутри
  картинки усредняет 2 выборки, на полосе 3-4 px у края делает ручную билинейную интерполяцию по 4
  текселям с обнулением вне кадра (сглаживание края). Режимы `TRAPEZOID_MTK` и `TRAPEZOID_XGIMI_AA`,
  проход `drawAntiAlias` ("GM_DISP_KST_AA"), есть `glFramebufferTexture2DMultisampleEXT`.
  Ограничения AA из строк: только UI, не для 4K-видео, не для real-time AK, только полная текстура.
- Свойства, которые читает HWC: `vendor.xgimi.hwc.aa`, `vendor.mstar.trapezoid.4K.enable`,
  `vendor.mstar.trapezoid.dual.gop.mode`, `vendor.mstar.trapezoid.video.gop`,
  `vendor.mstar.trapezoid.resolution.switch`, `vendor.mstar.trapezoid.secure.enable`,
  `vendor.mstar.hwc.tcvideo.format`, `vendor.mstar.hwc.glcomposite`, `vendor.mstar.trapezoid.enable`.
  Строка "XGIMI_HWC_AA is not enabled, skip anti-aliasing draw" прямо говорит, что без
  `vendor.xgimi.hwc.aa` сглаживания нет.
- Защищённое видео: `libkeystoneCA.so` (здесь Keystone это TEE, не трапеция), в HWC
  `keystone_open_session / keystone_authorize_buffer`, "Trapezoid authorize %s buffer". Включается
  `vendor.mstar.trapezoid.secure.enable=1`.
- Видео (MM/sideband) захватывается через `MI_CAP` и тоже проходит warp ("HWComposerCapHalMiCap ...
  Trapezoid").

### 1.3 Вендорная политика (libxgimi + конфиг G0082)

`/mnt/vendor/xgimiconfig/G0082/kstAdjust/kstAdjust.ini` (прочитано с проектора):

- `KstAdjustDeviceType = 10` (гибрид), `KstDefaultMode = 0` (2x2, то есть 4 угла),
  `realTimeKstType = 1` (MTK trapezoid).
- `[HybridKst] sw_width = 3840; sw_height = 2160; sw_type = 1; process_sw_enable = 1;
  process_hw_sw_delay = 100; switch_delay = 50`.
- `dlp/dlp.ini`: `warpEnable = 1` (DLPC8445 warp включён).

Строки libxgimi: `GM_DISP_KST_HYBRID`, `GM_DISP_KST_POLICY`, `GM_DISP_KST_8445_LIMIT`
("Kst angle out of limit,use SWKeystone"), `GM_DISP_KST_SOC_MTK` / `DeviceMtkOpt`
("4k:%s -> fhd:%s, scale", "TrapezoidPointHal"), `Dlp8445Chip::_SetWarpMap`, `SetWarpMap 3x3`,
`_SetConfigureSmoothWarp`, `setKstPrepareMode`, `forceChooseKeystone`.
Итог: HW warp 8445 в пределах его лимитов, SW (HWC GL, 3840x2160) за пределами и во время
настройки (setKstPrepare). Точное правило переключения пассивно не подтвердить.

### 1.4 Что было включено на стоке у владельца

`z9x-firmware-dump/info/surfaceflinger.txt` (стоковый дамп, в покое, на заставке):

```
HWC Trapezoid Params
Trapezoid Enable:[1] GOP:[Dual] Resolution:[4k|2k] realTimeAKMode:[0] secure:[1|1]
IsNeedDoTrapezoid:[1], correctType:[4]
Points Offset: LB:[295.0, 22.0] RB:[50.0, 175.0] RT:[180.0, 71.0] LT:[294.0, 288.0]
```

плюс секция `HWComposerGLComposite info` (видео в `mDisplayFrame (0,0,3840,2160)`).
Смещения в координатах 1920x1080 с осью Y снизу совпадают с `persist.vendor.xgimi.keystone.logo =
295,23,1869,176,1739,1009,294,792` (например LT = (294, 1080-792=288), RT = (1920-1739, 1080-1009) =
(181, 71)). То есть в установившемся режиме сток рисовал трапецию в HWC на GPU: 4K-выход, две GOP,
сглаживание, защищённый путь. Свойства стока: `vendor.xgimi.hwc.aa=true`,
`vendor.mstar.trapezoid.4K.enable=1`, `dual.gop.mode=1`, `video.gop=0`, `resolution.switch=1`,
`secure.enable=1`, `vendor.mstar.hwc.tcvideo.format=1`. Все они из
`/vendor/etc/xgimimodelname/G0082/build.prop` (блок `##trapezoid`).

### 1.5 Многоточечная коррекция в стоке

- В вендоре есть API сетки: IGmpf `MultiCorrectKeystone / GetMultiCorrectKeystone /
  checkMultiTrapezoidCoordinate`, `KstGridState`, `KeyStonePoints65x65`, шаблоны
  `vendor/media/Pattern/more_keystone_grid_33x33.png` и `_65x65.png`, режимы `ENUM_KST_3_3 ...
  ENUM_KST_9_9, ENUM_KST_MULTI`.
- В стоковых настройках есть `com.xgimi.keystone.morepointkeystone.MorePointKeystoneDialog`, но в
  дереве настроек Z9X (`research/z9x_stock_settings/tree_annot.txt`) пункта нет: только
  `manual_keystone` (4 угла), авто, real-time, вписывание в экран, обход препятствий. Значит у
  владельца на стоке была 4-точечная коррекция, а не «коррекция неровной поверхности». Разница в
  качестве не из-за числа точек, а из-за движка рендера.

## 2. Как делает Lumen 1.0.7

- `apps/Z9xProjector/.../panel/ManualKeystonePanel.java` + `hal/KstPoint.java`: 4 угла в панельных
  координатах 3840x2160, IGmpf 186 (чтение) -> 150 (проверка) -> 185 (`CorrectKeystone`), это тот же
  вызов, что у стока. Но стоковый `18 setKstPrepare(true/false)` выключен
  (`persist.z9x.kst_prepare=0` по умолчанию). Комментарий в коде (1.0, отчёт 2026-10-07): с ним
  "геометрия SW-трапеции на нашем GSI отличается от HW warp DLPC8445 (G0082 trapezoid props are not
  loaded), картинка прыгала", а ещё DRM-видео давало зелёно-красный мусор.
- Получается, что Lumen всегда работает только через HW warp 8445 (SetWarpMap, 2x2 с "smooth warp").
- `lineage/patches/z9x` трапецию не трогает (там только tvinput). `tools/lumen_v1.sh` и `overlay/v1`
  не задают ни одного из свойств трапеции.

На проекторе владельца (пассивно):

```
getprop: vendor.mstar.trapezoid.enable=1, vendor.mstar.trapezoid.enable.vndbinder=1,
         vendor.trapezoid.xgimi.aa=1, vendor.mstar.hwc.glcomposite=1,
         vendor.mstar.hwc.framebuffer.format=1, persist.vendor.xgimi.keystone.logo=291,29,1868,90,1812,1000,300,833
НЕТ:     vendor.xgimi.hwc.aa, vendor.mstar.trapezoid.4K.enable, .dual.gop.mode, .video.gop,
         .resolution.switch, .secure.enable, vendor.mstar.hwc.tcvideo.format
service list: ITrapezoid/default есть (в процессе HWC), composer3 есть
dumpsys composer: 1 слой DEVICE, нет секций "HWComposerGLComposite info" и "HWC Trapezoid Params"
SurfaceFlinger: usesDeviceComposition=true, usesClientComposition=false
```

Трапеция у владельца на Lumen сейчас примерно такая же сильная, как была на стоке, но делается
только DLPC8445, а GPU-путь HWC не работает.

## 3. Точная разница

| | Сток | Lumen 1.0.7 |
|---|---|---|
| Свойства модели G0082 (`xgimimodelname/G0082/build.prop`) | грузит системный init | не грузятся (нет 7 свойств трапеции) |
| SW-трапеция HWC (GPU, гомография) | активна, 4K-выход, две GOP | выключена Lumen (`kst_prepare=0`); при включении считалась бы в 2K-геометрии |
| Сглаживание XGIMI (`vendor.xgimi.hwc.aa`) | да | нет |
| DRM-видео через трапецию (`trapezoid.secure.enable`, Keystone TEE) | да | нет (отсюда мусор при SW) |
| HW warp DLPC8445 | в пределах лимита и как часть гибрида | единственный путь, при любой величине |
| setKstPrepare вокруг ручной настройки | да | нет |

Вероятная причина «неровной» картинки на Lumen: при сильной трапеции DLPC8445 с сеткой 2x2 и
сглаживающим warp не даёт точной перспективы и даёт неравномерную плотность пикселей/резкость к
краям, тогда как сток для такой геометрии переходит на точную гомографию на GPU в 4K со
сглаживанием. Это вывод из кода и дампов. Сравнение на фото ниже нужно, чтобы его подтвердить.

## 4. План исправления

Шаг 1 (главное, одно место, только Z9X). Вернуть свойства модели, которые раньше задавал стоковый
init. Вариант A (рекомендую): rc-файл только для Z9X в overlay/v1, например
`init.z9x.trapezoid.rc`:

```
on early-init && property:ro.boot.xgimi.modelname=G0082
    setprop vendor.xgimi.hwc.aa true
    setprop vendor.mstar.trapezoid.secure.enable 1
    setprop vendor.mstar.trapezoid.4K.enable 1
    setprop vendor.mstar.trapezoid.dual.gop.mode 1
    setprop vendor.mstar.trapezoid.video.gop 0
    setprop vendor.mstar.hwc.tcvideo.format 1
    setprop vendor.mstar.trapezoid.resolution.switch 1
```

(Синтаксис триггера проверить: для `early-init` с условием по свойству может понадобиться обычный
`on init` и проверка modelname в скрипте. Свойства должны появиться ДО старта
`vendor.hwcomposer-3`, класс hal.) Проверить SELinux: init может ставить `vendor.*`, но контекст
свойств надо посмотреть (`getprop -Z`). Вариант B: патч init в Lineage, чтобы он, как сток, грузил
весь `/vendor/etc/xgimimodelname/${ro.boot.xgimi.modelname}/build.prop`. Это ближе к стоку, но
файл содержит и `ro.product.*`, и другие свойства, которые перебьют Lumen, поэтому нужен белый
список. В том же белом списке стоит подумать о `persist.vendor.xgimi.hd_server=2` (защита глаз),
его на Lumen тоже нет.

Шаг 2. После шага 1 вернуть стоковую последовательность ручной трапеции: `18 setKstPrepare(true,0)`
при входе и `18(false,0)` при выходе, как в стоковом KeyStoneWind, и дать вендорной гибридной политике
выбирать HW/SW самой. Делать через уже существующий переключатель `persist.z9x.kst_prepare=1`
сначала для теста, потом сменить умолчание. Код рендера не писать: он уже есть в вендорном HWC.

Шаг 3 (наша надстройка, потом). Многоточечная коррекция для неровной поверхности через вендорный
`MultiCorrectKeystone` (сетка 3x3 ... 9x9, шаблоны 33x33/65x65 уже в vendor). У стока на Z9X пункта
нет, поддержку G0082 надо проверить отдельно (`checkMultiTrapezoidCoordinate`, режимы из
`KstGridState`), и, скорее всего, это пойдёт в 8445, а не в HAL трапеции (там только 8 точек).

Патч SurfaceFlinger как на Play 6 для Z9X не нужен и вреден: warp был бы двойным.

## 5. Риски

- HWC с `onrestart restart surfaceflinger`: если новые свойства уведут HWC в ошибку, будет цикл
  перезапуска графики. Проверять с готовым откатом (удалить rc / вернуть образ) и при включённом
  ADB.
- `resolution.switch=1` и `4K.enable=1` взаимодействуют с нашим переключателем разрешения UI
  (`z9x_uires`, `vendor.display-size`, `vendor.mstar.resize.framebuffer`). Проверить и 1080p-, и
  4K-интерфейс.
- Защищённый путь (TEE Keystone) раньше не включался на Lumen. Возможны ошибки авторизации буферов
  на DRM-видео ("Trapezoid authorize ... fail").
- Нагрузка на GPU: композиция 4K на Mali-G57 при каждом кадре, когда SW-трапеция активна. Проверить
  плавность UI и видео, температуру.
- PWM watchdog на раннем старте: setprop в rc почти бесплатны, но не добавлять ожиданий в early-boot.
- Устройства разделены: правило только для `ro.boot.xgimi.modelname=G0082` и только в образе Z9X,
  Play 6 не трогать.

## 6. Как проверить на проекторе

1. До изменений (Lumen): `adb shell dumpsys android.hardware.graphics.composer3.IComposer/default`
   и сохранить. Ожидаем отсутствие секций трапеции (как сейчас).
2. После шага 1 и перезагрузки: `getprop | grep -E "hwc.aa|trapezoid"` (7 свойств), затем выставить
   ту же трапецию, что на стоке. В dumpsys composer должны появиться `HWComposerGLComposite info` и
   `HWC Trapezoid Params` со строкой `Trapezoid Enable:[1] GOP:[Dual] Resolution:[4k|2k] ...
   secure:[1|1]` и `IsNeedDoTrapezoid:[1]`, как в стоковом дампе.
3. Фото: проектор и камера на штативе, не двигать между снимками, ручная экспозиция и фокус.
   Тестовая картинка: сетка (например стоковый `more_keystone_grid_33x33.png` или своя
   с тонкими линиями 1 px и мелким текстом) на весь экран. Снять: (a) Lumen сейчас, (b) Lumen после
   шагов 1-2, (c) по возможности сток со старых фото. Сравнить: прямизну длинных линий (приложить
   линейку в редакторе), одинаковость клеток слева/справа и сверху/снизу, ступеньки на наклонных
   краях (кроп 100 %), резкость текста в углах и в центре.
4. Видео: обычное, 4K HDR и DRM-приложение (без зелёно-красного мусора), плюс HDMI-вход.
5. В logcat искать `HWComposerGLComposite`, `Trapezoid`, `GM_DISP_KST`, `authorize`, ошибки шейдера.
