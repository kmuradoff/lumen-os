# Паритет со стоком: звук (Z9X, G0082, Lumen 1.0.7)

Область: режимы звука и таблицы усилителя, AI-звук, эквалайзер, Harman Kardon / DTS / Dolby, выравнивание
громкости, выходы (динамик, ARC / eARC, Bluetooth, USB, наушники, оптика), формат цифрового выхода,
задержка звука (lip sync), звук нажатий, микрофон и караоке, звук при включении.

Пути ниже относительно `gsi/`. Сокращения:
- `P/` = `apps/Z9xProjector/src/org/lumen/projector/`
- `SET/` = `research/z9x_stock_decompiled/jadx/settings/sources/com/xgimi/`
- `XS/` = `research/z9x_stock_decompiled/jadx/xservice/sources/com/xgimi/xgimiservice/`
- `TREE` = `research/z9x_stock_settings/tree_annot.txt` (стоковое дерево настроек dapeng = Z9X)
- `CFG/` = `/mnt/vendor/xgimiconfig/G0082/` на проекторе (прочитано `adb pull`, только чтение; в дампе
  `z9x-firmware-dump/fs/mnt_vendor_xgimiconfig.tar` пустой, 0 байт)
- `VND` = `z9x-firmware-dump/fs/vendor.tar`, `SYS` = `z9x-firmware-dump/fs/system.tar`
- `STOCKPROP` = `z9x-firmware-dump/info/props.txt` (живой сток)

Проектор <lan-ip> сейчас (только пассивное чтение: getprop, settings get, dumpsys audio / media.player,
logcat -d, pm list, cat/pull файлов CFG/): Lumen OS 1.0.7, режим звука AI, подрежим Кино
(`xgimi.pkg.effect.mod=1`, лог `01:55:08 Z9xAiSound periodic (re-send): 63(1 MOVIE)`), `sound_effects_enabled=1`,
`persist.bluetooth.disableabsvol=false`, `persist.xgimi.uacmic.effect=6`, `persist.xgimi.uacmic.volume=8`
(остались от стока), свойства `vendor.xgimi.system.audio.digital_output_format` нет (в стоке было `pcm`),
STREAM_MUSIC 0..100, текущие устройства `speaker(2), spdif(80000)`. Буфер logcat почти пустой (перезаписан),
поэтому поведение по логам берётся из комментариев кода Lumen и CHANGES.md.

Важно: каталоги исследований, на которые ссылается код Lumen (`research/v63/ampeq`, `research/v64/aisound`,
`research/v64/peq`, `research/audio/dis`), в этом дереве отсутствуют (вероятно, остались на сборочной машине).
Выводы из них ниже помечены "по комментарию кода".

## Итог в одной таблице

| # | Пункт | Вердикт | Важность |
|---|---|---|---|
| 1 | Таблицы усилителя TAS5805M (movie / music / sport / ktv / init / eco) | SAME | |
| 2 | Режимы звука: AI, Кино, Музыка, Спорт, Караоке | SAME | |
| 3 | Режим "Стандарт" (20) | DIFF (скрыт намеренно) | средняя |
| 4 | Режим по умолчанию после сброса: сток, видимо, 20 (Стандарт), Lumen Кино | DIFF | низкая |
| 5 | AI-звук (подрежим по приложению) | SAME по механизму, EXTRA по охвату | |
| 6 | Total Surround / Total Immersion (подменю Кино) | MISSING, но только для колонки XGIMI Sound | низкая |
| 7 | Улучшение звука: Harman Kardon / DTS Virtual:X | SAME (+ честные подсказки) | |
| 8 | Выравнивание громкости (音量平衡) | SAME | |
| 9 | Подопции DTS (TruBass, Dialog Clarity, TruSurround, TruVolume, DRC) | SAME (в стоке Z9X их нет) | |
| 10 | Dolby (AC-4 DE, Dialog Enhancer, Volume Leveler, DAP) | SAME (в стоке Z9X нет в меню) | |
| 11 | Декодеры AC3 / E-AC3 / AC4 / DTS | SAME | |
| 12 | Эквалайзер 7 полос | EXTRA (в стоке нет) | |
| 13 | Игровой звуковой режим (190..194, IGmpf2 1/2) | SAME (делает сам vendor), не проверено | низкая |
| 14 | "Синхронизация звука с картинкой" (IGmpf2 10/11) | SAME (в стоке нет в меню) | |
| 15 | Выход звука: Авто / Динамик / ARC / Bluetooth / Наушники / USB | SAME (другой механизм), не проверено на устройстве | средняя |
| 16 | Выходы XGIMI Sound / сабвуфер и их комбинации, "Аудиосистема" | MISSING (нет такого железа) | низкая |
| 17 | Оптический выход (SPDIF) | SAME: у Z9X его нет | |
| 18 | eARC Авто / Выкл | SAME (переключатель вместо выбора) | |
| 19 | Формат цифрового выхода (Авто / PCM / RAW): умолчание | DIFF: сток PCM, Lumen ничего не шлёт | высокая для владельцев саундбара |
| 20 | Громкость HDMI-источника на динамике | SAME (с 1.0.4) | |
| 21 | Bluetooth: звук, громкость, задержка | SAME (с 1.0.6, проверено на устройстве) | |
| 22 | Переключатель "Абсолютная громкость Bluetooth" | DIFF: в стоке переключатель (по умолчанию выкл), в Lumen всегда вкл | низкая |
| 23 | Задержка звука / lip sync (ручная) | SAME (в стоке Z9X нет в меню) | |
| 24 | Звук нажатий (按键音) | SAME (в настройках Android TV) | |
| 25 | Микрофон: режим Караоке / Конференция, громкость микрофона | MISSING | средняя |
| 26 | Звук при включении | SAME (с 1.0.4) | |
| 27 | Режим колонки Bluetooth (проектор как колонка) | MISSING (отклонено в V63) | низкая |

Коротко: всё, что в стоке реально меняет звук встроенного динамика Z9X (режимы, таблицы усилителя, Harman/DTS,
выравнивание), в Lumen есть и работает через те же вызовы vendor. Реальные пробелы: (а) формат цифрового выхода
по умолчанию не PCM, (б) нет меню микрофона для караоке, (в) нет режима "Стандарт" и выключателя абсолютной
громкости BT. Плюс эквалайзер и умный AI-звук, которых в стоке нет.

---

## 1. Таблицы усилителя TAS5805M

**Сток.** `CFG/amp/amp.ini`: `type = 1` (TI 5825-подобный), таблицы для 5805:
`init_5805, movie_5805, music_5805, sleep_5805, ktv_5805, sport_5805, bypass_5805 (DTS), lowpass_5805`, плюс
"节能模式" (эко) копии `init/movie/music/sleep/ktv/sport_eco_5805.cfg`. `news_5805` и `*_sub_5805` закомментированы.
Таблицы по 1159 строк (запись регистров `w 58 ...`); `sleep_5805.cfg` всего 5 строк (выключение усилителя).
eco-таблица `movie_eco_5805.cfg` отличается от `movie_5805.cfg` в 87 строках (другие коэффициенты, видимо меньше
усиление). Выбирает таблицу сам vendor (libxgimi `device_audio_amp_I2S_TI58XX::SetSoundEffects`,
`research/z9x_stock_decompiled/libxgimi.str:7626`) по вызову 61/63. В меню стока эко-режима звука нет
(`TREE` не содержит 节能), `CFG/system.ini` `[ECO_MODE] support = 1`: переключает vendor сам.

**Lumen.** Таблицы не трогает, только шлёт 61 setSoundeffect / 63 setAutoSoundeffect / 27 setSoundProcessType,
vendor пишет те же файлы с того же раздела (`P/panel/SoundProfiles.java:13-27`). Никогда не шлёт 61 со значениями
5..16, 50..53, 194, 200 и свойство `persist.vendor.xgimi.amptest` (`SoundProfiles.java:22-24`).

**Вердикт: SAME.** Исправлять нечего.

## 2. Режимы звука

**Сток.** `TREE:188-197` (`sound_effect`): AI音效 (`sound.mode.auto`), 音乐 Музыка, 观影 Кино (с подменю 3D
окружения), K歌 Караоке, 体育 Спорт, 标准 Стандарт. Значения: `SET/sound/SoundEnum.java`: AUTO 3, MUSIC 2,
MOVIE 1, KARAOKE 4, SPORTS 12, STANDARD 20 (NEWS 5, VOCAL 21, GAME 22 в enum есть, но в дереве Z9X их нет).
Выбор: `SET/sound/SoundHelper.java:50-72` `GmAudioManager.setSoundEffect(mode)` = IGmpf 61; чтение 62.
Караоке разрешено фичей `CFG/feature/feature.xml:128` `auto_audio_ktv_effect=true`.

**Lumen.** `P/panel/SoundProfiles.java:75-77` MODES = AI(3), Кино(1), Музыка(2), Спорт(12), Караоке(4);
`P/panel/QuickPanelController.java:681-702` строка "Режим звука", 61 на выбор, AI через `AiSoundEngine.userChoseAi`.
Метки `QuickPanelController.java:869-876`.

**Вердикт: SAME** для пяти режимов (те же значения 61, те же таблицы).

## 3. Режим "Стандарт" (20)

**Сток.** Есть в меню (`TREE:197`, `SoundEnum.MODE_STANDARD = 20`).

**Lumen.** Скрыт. По комментарию кода (`SoundProfiles.java:19-21`, ссылка на research/v63/ampeq): для 20 у G0082
нет своей таблицы 5805, vendor пишет "TI58XX mode err" и ничего не загружает, после перезагрузки усилитель
остаётся на INIT, а INIT самая "тонкая" таблица (ФВЧ 4-го порядка 110-115 Гц, на 13-15 дБ меньше баса, чем
Кино). При загрузке, если 62 читается как 20 или мусор, Lumen сам ставит 61(1) Кино (`SoundProfiles.java:145-170`).
`P/hal/GmpfClient.java:368` STANDARD(20) в enum есть, но не предлагается. `docs/z9x/OPEN_stock_gap.md:57`.

**Вердикт: DIFF, намеренно.** Пользователь стока, выбравший "Стандарт", получает звук хуже, чем в Кино.

**Что сделать.** Оставить скрытым (рекомендую: это "лучше стока"). Если владелец хочет буквальный паритет:
добавить в конец списка "Стандарт (как в стоке, тише бас)" с 61(20) и убрать 20 из условия boot check,
но это ухудшит звук. Перед решением стоит один раз проверить на слух (это вызов HAL, только с разрешения владельца).

## 4. Режим по умолчанию

**Сток.** `CFG/env/CustomerEnvTbl.xml:4` `SoundmodeLightType DEFAULT=20 FACTORY_RESET=20 USER_RESET=20`. Имя ключа
неочевидное, но значение совпадает с кодом Стандарта; вероятно, это и есть режим после сброса. Не подтверждено.
Процесс по умолчанию `sound_process_type DEFAULT=2` (Harman), `dts_effects_enable DEFAULT=0` (строки 65, 70).

**Lumen.** Кино, если 62 не валиден (`SoundProfiles.lastMode`, строка ~124), Harman по умолчанию.

**Вердикт: DIFF** (режим), SAME (Harman). Исправлять не нужно, Кино лучше по комментарию из п.3.

## 5. AI-звук

**Сток.** 61(3); подрежим выбирал XRMService по `/system/media/auto_effect_pkg.json` (только китайские
приложения), ставил свойство `xgimi.pkg.effect.mod` и 63, неизвестные приложения: ничего (по комментарию
`P/audio/AiSoundEngine.java:26-27`). Фича `CFG/feature/feature.xml:126` `auto_audio_effect=true`.

**Lumen.** `P/audio/AiSoundEngine.java:7-90`: тот же механизм (свойство + 63), но подрежим берётся по
категории переднего приложения (списки пакетов + ApplicationInfo.category, AirPlay и Chromecast по типу
контента), с защитой от переключения под играющей музыкой, только на динамике, повторная отправка после
восстановлений vendor (`VendorSoundWatch`). Живое состояние: AI, подрежим Кино, отправляется каждые 60 с.

**Вердикт: SAME по вызовам, EXTRA по охвату** (в стоке на нероссийских приложениях AI был фактически всегда Кино).

## 6. Total Surround / Total Immersion

**Сток.** `TREE:193-194` в подменю Кино. Реализация: `SET/sound/SoundRepository.java:1044-1051`
`XgimiSpeakerManager.set3DSurroundSkySoundEnable / setSkySoundEnable`, видимость
`SET/sound/SoundViewModel.java:144-147` + `SoundHelper.isSpeakerEffectsInput()` (`SoundHelper.java:244-248`):
только когда выход 8, 9, 10, 12 или 13 (пути беспроводной колонки XGIMI Sound).

**Lumen.** Нет. `apps/Z9xProjector/CHANGES.md` (раздел 1.0.4, "Not brought over"): только для XGIMI Sound.

**Вердикт: MISSING, но без колонки XGIMI Sound пункт в стоке тоже не виден.** Исправлять не нужно.

## 7. Harman Kardon / DTS Virtual:X

**Сток.** `TREE:198-200` `sound_strong_effect`: 哈曼卡顿 и DTS Virtual:X. `SET/sound/SoundHelper.java:209-229`
`setDtsModeV5`: Harman = `setSoundProcessTypeFusion(2)`, DTS VX = `setSoundProcessTypeFusion(5)`.
`SET/device/GmAudioManager.java:659-681`: Fusion(p) = `setDtsEffectsEnable(false)` (44) затем
`setSoundProcessType(p)` (27); Fusion(100) = только 44(true). Обе строки доступны всегда
(`SoundRepository.java:236-260`: UiEnable всегда true). Фича `support_dts_vx_effect=true` (feature.xml:140).

**Lumen.** `P/panel/SoundProfiles.java:176-215`: 44(false) + 27(2|5), чтение 26/43, ровно как сток. Плюс:
DTS реально работает только на динамике и только когда приложение сообщило путь (59), иначе показывается
подсказка "только на динамике" / "выход ещё не готов" (`QuickPanelController.java:664-676`). Значение 43 == true
(старый DTS 100) показывается как прочерк, значение неизвестно (`SoundProfiles.fromState`).

**Вердикт: SAME** (те же вызовы), плюс честные подсказки.

## 8. Выравнивание громкости

**Сток.** `TREE:202` `sound_volume_balance`, `SET/sound/SoundRepository$setVolumeBalance$1.java`:
`GmAudioManager.setVolumeBalanceEnable` = IGmpf 42, чтение 41.

**Lumen.** `QuickPanelController.java:709-717` переключатель 41/42; выбор Harman/DTS больше не выключает его,
если пользователь включил (`SoundProfiles.java:177-186`).

**Вердикт: SAME.**

## 9. Подопции DTS и DTS DRC

**Сток.** Код есть (`SoundRepository.java:998-1016, 1093`: TruBass 25, Dialog Clarity 19, TruSurround 23,
TruVolume 21, DRC IGmpf2 6), но узлов `sound.bass_enhancement_mode`, `sound.clear_voice_mode`,
`sound.surround_sound_enhancement_mode`, `sound.dts.drc` в дереве dapeng нет (`TREE:186-238`).

**Lumen.** Не предлагает (`SoundProfiles.java:34-35`).

**Вердикт: SAME.**

## 10. Dolby (AC-4 DE, Dialog Enhancer, Volume Leveler, DAP)

**Сток.** Код в `SoundRepository` есть (`setDolbyAc4De`, `setDolbyVolumeBalancMode`), узлов `sound.atmos*`,
`sound.dolby_*` в дереве Z9X нет. `CFG/system.ini` `SupportHashkeyMode = 8` (dtsVx + DD+ + HDR10+ + IMAX, без
Atmos-обработки). Пакет `com.dolby.android.audio.service` был в стоке (`info/packages_paths.txt`), в Lumen его нет
(`pm list packages`).

**Lumen.** Нет. `docs/z9x/OPEN_stock_gap.md:28` (Dolby Audio service: эффект НЕИЗВЕСТЕН).

**Вердикт: SAME** по меню. Влияние отсутствия DolbyAudioService на DD+ не проверено, но декодеры одинаковые (п.11).

## 11. Декодеры

**Сток / Lumen.** `info/codecs.txt` и `dumpsys media.player` на живом Lumen: одинаково `c2.mtk.ac3.decoder`
(ac3, eac3, eac3-joc), `c2.mtk.ac4.decoder`, `c2.mtk.dts.decoder` (dts, dts.hd).

**Вердикт: SAME.**

## 12. Эквалайзер

**Сток.** Нет пользовательского эквалайзера. `CFG/audio/aq/sound.ini`: `[ANALOG_EQ] EQ_Enable = 0`, свои 13 полос
есть только у DTS (`[DTS] EQ_Enable = 1`, `[DTS_MOVIE]`..`[DTS_GAME3]`), vendor грузит их в режиме DTS.

**Lumen.** `P/audio/SoundEq.java`, `EqCurve.java`, страница `QuickPanelController.java:752-850`: 7 полос
(60 Гц..15 кГц), пресеты Ровно / Бас / Голос / Ночь / Своё, на каждый режим звука; только Harman и только динамик
(в DTS отключён, чтобы не затирать DTS-полосы vendor); суммарный подъём не больше +6 дБ, 60 Гц не больше +3 дБ.

**Вердикт: EXTRA.** Проверено ли на слух, из кода не видно.

## 13. Игровой звуковой режим

**Сток.** `feature.xml:160` `support_gaming_audio_effect=true`; `SET/device/GmAudioManager.java:48-52` значения
190 Выкл, 191 Окружение, 192 Сюжет, 193 Чёткость, 194 Баланс; IGmpf2 1/2 (`research/play6/z9x_gmpf_codes.txt:703-704`);
таблицы `CFG/audio/aq/DtsVx_Game1..3.ini`. vendor хранит режим по источнику
(`libxgimi.str`: `Msrv_DataBase_Manage::GetGamingSoundEffectsMode(EN_XGIMI_INPUT_SOURCE)`). Ни одно стоковое
приложение не вызывает `setGamingSoundEffectMode` из UI (поиск по jadx settings / systemui / inui: только обёртки).
Значит, включает его сам vendor при игровом режиме.

**Lumen.** Не трогает; `AiSoundEngine` не шлёт 63 во время сессии HDMI, чтобы не мешать игровому режиму
(`AiSoundEngine.java:43`). 61 со значением 194 запрещён (`SoundProfiles.java:22-23`).

**Вердикт: SAME**, не проверено. Проверка: включить игровой режим с приставкой и посмотреть logcat vendor на
`GamingSoundEffect` (пассивно).

## 14. Синхронизация звука с картинкой

**Сток.** IGmpf2 10/11 `setSyncImageSoundEffects`; вызывающих в UI нет (только обёртки в systemui / settings).

**Lumen.** Нет. **Вердикт: SAME.**

## 15. Выход звука

**Сток.** `TREE:204-216` `voice_output`: Авто, Встроенный динамик, ARC, Наушники, Bluetooth, USB (+ варианты
XGIMI Sound, п.16). Реализация `GimiAudioService.setAudioDeviceOn` = `AudioSystem.setForceUse(FOR_MEDIA, 16/17/18)`,
работает только в патченом audioserver XGIMI (по комментарию `P/audio/AudioOutputChoice.java:9-13`).

**Lumen.** `P/audio/AudioOutputChoice.java`, строка "Выход звука" `QuickPanelController.java:723-734`:
`setPreferredDeviceForStrategy` (AOSP 14). Авто и Динамик всегда, ARC/eARC, Bluetooth, наушники, USB только
когда подключены. Путь vendor (59) следует за выбором, звук HDMI-входа тоже. SPDIF не показывается (п.17).

**Вердикт: SAME** по набору. В CHANGES.md помечено "NOT device-tested". **Сделать:** проверить на устройстве с
саундбаром по ARC и BT-колонкой (переключение, звук HDMI-входа следует).

## 16. XGIMI Sound и сабвуфер

**Сток.** `TREE:207-212, 220-227` (внутренний динамик + сабвуфер, + XGIMI Sound и т.д.), фичи
`support_subwoofer_version=10`, `support_smartconnect=true`.

**Lumen.** Нет. `OPEN_stock_gap.md:22` (аксессуары XGIMI, уровень B). **Вердикт: MISSING**, без такого железа не нужно.

## 17. Оптический выход

**Сток.** `CFG/audio/audio.ini`: `spdif_jack_type = 0` (нет разъёма), `device_arc = 1`. Политика vendor всё равно
объявляет SPDIF подключённым (живой dumpsys: `Devices: speaker(2), spdif(80000)`). Пункты "光纤ARC" в
`misckey.ini` общие для всех моделей.

**Lumen.** `P/audio/SpdifOff.java`: SPDIF помечен не-устройством-по-умолчанию для медиа (иначе громкость шла бы
на SPDIF, а не на BT), в выборе выхода его нет.

**Вердикт: SAME** (у Z9X оптики нет).

## 18. eARC

**Сток.** `TREE:228-230` `voice_earc_sound_v5`: Авто / Выкл, `SET/voice/VoiceRepository.java:417-419`
`GmAudioManager.setEarcEnable` (через GmTvManager, IGmpf 633), чтение 632.

**Lumen.** `QuickPanelController.java:718-722, 1574-1575`: переключатель eARC (вкл = Авто), виден только если 632
отвечает.

**Вердикт: SAME** (переключатель вместо двух пунктов). Видна ли строка на этом проекторе, без вызова HAL не узнать.

## 19. Формат цифрового выхода (ARC)

**Сток.** `TREE:219` `voice_arc_format_auto_sound_v5`. `XS/wrapper/xgimi/XgimiAudio.java:60-99`: при старте
сервиса `init()` читает `Settings.System spdif_mode`, если пусто, пишет PCM и шлёт
`setParameters("digital_output_format=pcm")` + свойство `vendor.xgimi.system.audio.digital_output_format=pcm`.
Системная библиотека `SYS system/lib64/libxgimiaudiopolicy.so` тоже содержит строку `digital_output_format=pcm`
(по умолчанию). `STOCKPROP`: `vendor.xgimi.system.audio.digital_output_format = pcm`. То есть в стоке по умолчанию
на ARC всегда шёл PCM, пока пользователь не выбрал RAW/Авто.

**Lumen.** `P/audio/AudioOutputChoice.java:41-43, 145-148, 232-242`: шлёт параметр только после выбора
пользователя, в UI без выбора показывает "Авто" и считает это умолчанием HAL. На живом проекторе свойства нет,
значит параметр ни разу не отправлялся. Что HAL `audio.primary.mt5877` делает без параметра, не проверено.

**Вердикт: DIFF.** Если HAL по умолчанию отдаёт bitstream, саундбар без DD/DTS может молчать или шипеть на
фильмах с AC3/DTS, а в стоке тот же саундбар играл.

**Что сделать.** Повторить сток: если пользователь ничего не выбирал, при загрузке и при появлении ARC слать
`digital_output_format=pcm`, в UI показывать "PCM" как значение по умолчанию (`formatIndex` вернуть PCM, `sendFormat`
слать PCM при `format == null`). Порядок пунктов можно оставить (Авто / PCM / Passthrough).

## 20. Громкость HDMI-источника на динамике

**Сток.** Патченый AudioService слал `spk_volume=N` на каждое изменение громкости.

**Lumen.** `P/source/HdmiVolume.java` (CHANGES.md 1.0.4): `spk_volume` + `xgimi_audiopath_switch_start/end=0`,
только на динамике. Проверено вручную на 1.0.3 + Google.

**Вердикт: SAME.**

## 21. Bluetooth: звук, громкость, задержка

**Сток.** A2DP через vendor HAL, `GimiAudioService` при старте шлёт `xgimi_set_delay_bt=N` из
`Settings.System audio_delay_bt`, только если значение > 0 (`XS/common/GimiAudioService.java:98-100, 628-650`).
UI для этой задержки в стоке Z9X нет (вызовов `setAudioOutDeviceDelay` из UI не найдено).

**Lumen.** `P/audio/AudioPathReporter.java` (`switch_end=3`, `bluetooth_volume=100`), `P/audio/BtDspVolume.java`,
`P/audio/SpdifOff.java`; HAL сам вычитает задержку A2DP (~194 мс) из позиции воспроизведения. Проверено на
устройстве владельца 23-24.10 (звук, синхрон по вспышке и щелчку, громкость в обе стороны). `audio_delay_bt` на
живом проекторе `null`, как в стоке по умолчанию.

**Вердикт: SAME.**

## 22. Абсолютная громкость Bluetooth

**Сток.** Переключатель `TREE:185` `more_bluetooth_switch` (蓝牙绝对音量); по умолчанию выключено
(`STOCKPROP persist.bluetooth.disableabsvol = true`).

**Lumen.** `overlay/v1/product_prop_v1.txt:39-45` `persist.bluetooth.disableabsvol=false` всегда, переключателя нет.

**Вердикт: DIFF.** Умолчание Lumen лучше (одна громкость на проектор и колонку), но для колонки, которая плохо
работает с абсолютной громкостью, нет выключателя.

**Что сделать (низкий приоритет).** Переключатель в настройках проектора: пишет `persist.bluetooth.disableabsvol`
и говорит "переподключите колонку" (BlueZ-колонки кешируют SDP, см. память z9x-bluetooth-audio).

## 23. Задержка звука (lip sync) вручную

**Сток.** IGmpf 53/56 `getAudioDelay/setAudioDelay`, `setAudioOutDeviceDelay` есть в API, но в меню Z9X нет
(п.21; дерево `TREE:186-238` без такого узла).

**Lumen.** Нет; для BT HAL компенсирует сам, ручка `vendor.mtk.bt.latency` не выведена.

**Вердикт: SAME.** Возможное улучшение сверх стока: ползунок задержки для ARC/HDMI через IGmpf 56 (не исследовано).

## 24. Звук нажатий

**Сток.** `TREE:231` `voice_key_tone`; `SET/voice/VoiceRepository.java:50-54, 316-319`:
`Settings.System sound_effects_enabled` 0/1. В стоке включено (`info/settings_system.txt: sound_effects_enabled=1`).

**Lumen.** `P/sys/SystemFixes.java:31-33, 109`: один раз ставит 1 при первой загрузке, дальше уважает выбор;
переключатель "Системные звуки" в настройках Android TV (Экран и звук). `P/audio/Z9xAudioFixer.java` снимает ложный
mute STREAM_SYSTEM, из-за которого щелчки пропадали. Живое значение 1.

**Вердикт: SAME** (переключатель в другом месте).

## 25. Микрофон и караоке

**Сток.** `TREE:232-238`: Микрофон, Режим (K歌 Караоке / 会议 Конференция), Громкость микрофона.
- Видимость: только USB-устройство класса Audio с VID из {1105, 3190, 28741, 3468} и PID из
  {5659, 5639, 5887, 8, 6421}, кроме `XGIMI_C4` (`SET/sound/SoundHelper.java:40-41, 131-163`),
  отслеживание USB_DEVICE_ATTACHED / DETACHED (`SoundRepository$getMicrophone$2.java:50-60`).
- Режим: `MicroPhoneEnum` MEETING 5, KARAOKE 6 (`SET/sound/MicroPhoneEnum.java`) ->
  `XgimiAudioManager.setMicrophoneEffect` -> `SystemProperties.set("persist.xgimi.uacmic.effect", N)`
  (`XS/common/GimiAudioService.java:180-182`).
- Громкость 0..10: `persist.xgimi.uacmic.volume` + `AudioSystem.setParameters("xgimi_uacmic_set_volume=N")`
  (`GimiAudioService.java:194-203`).
- Сам микрофонный тракт (USB-микрофон -> ревербератор -> динамик) живёт в vendor HAL:
  `VND vendor/lib64/hw/audio.primary.mt5877.so` строки `global_mic uacmic_start`, `Turn on low latency path for
  Karaoke`, `xgimi_uacmic_set_volume`, `xgimi_uacmic_flush_effect`, `xgimi_usb_device_connect`, свойства
  `persist.xgimi.uacmic.*`, `runtime.xgimi.uacmic.*`. Стоковое приложение `com.xgimi.karaokesdk` (priv-app, uid 1000)
  было отдельным.

**Lumen.** Ничего нет (поиск `uacmic`, `Microphone` по `P/`: пусто). Свойства стока
`persist.xgimi.uacmic.effect=6`, `volume=8` на живом проекторе сохранились, так что HAL, возможно, уже включает
микрофон при подключении, но без меню нельзя сменить режим и громкость. Кто в стоке шлёт
`xgimi_usb_device_connect` (сам HAL по hotplug или система), не выяснено.

**Вердикт: MISSING.**

**Что сделать.** В `Z9xProjector` модуль `audio/UsbMic`: приёмник USB_DEVICE_ATTACHED/DETACHED, тот же список
VID/PID и исключение `XGIMI_C4`; на странице Звук строки (только пока микрофон подключён) "Микрофон: Караоке /
Конференция" (свойство `persist.xgimi.uacmic.effect` 6/5; устройство в permissive, свойство пишется, как уже делает
AiSoundEngine с `xgimi.pkg.effect.mod`) и "Громкость микрофона 0..10" (свойство + `setParameters`). Сначала тест
с реальным USB-микрофоном: подключить, пассивно посмотреть logcat HAL на `global_mic`, есть ли звук без всяких
вызовов. Если нет, выяснить, нужен ли `xgimi_usb_device_connect`.

## 26. Звук при включении

**Сток.** Общие > Дополнительно > Включение/выключение (IGmpf 420/419).

**Lumen.** `P/SettingsActivity.java:145`, `P/hal/GmpfClient.java:142-143, 1187-1192`. **Вердикт: SAME.**

## 27. Режим колонки Bluetooth

**Сток.** `research/z9x_stock_decompiled/x/vendor/etc/xgimiconfig/global_Product_dapeng.ini`
(`sounder_Mode_light`, ярлык "音响模式"), IGmpf 213/224.

**Lumen.** Нет, отклонено в V63 (`OPEN_stock_gap.md:52`). **Вердикт: MISSING** (решение владельца).

---

## Что проверить на устройстве (с разрешения владельца, это уже не пассивное чтение)

1. Формат ARC без выбора: подключить саундбар, фильм с AC3/DTS, послушать; затем выбрать PCM и сравнить (п.19).
2. Выход звука: Авто / Динамик / ARC / BT, звук HDMI-входа следует за выбором (п.15).
3. USB-микрофон из списка VID/PID: есть ли звук без меню (п.25).
4. Строка eARC: видна ли на этом проекторе (п.18).
5. На слух Harman против DTS Virtual:X и эквалайзер в режиме Harman (п.7, п.12).
6. Игровой режим с приставкой: включает ли vendor игровой звук сам (logcat, п.13).

## Порядок работ

1. Формат цифрового выхода по умолчанию PCM, как в стоке (п.19): маленькая правка `AudioOutputChoice`.
2. Меню микрофона (п.25): новый модуль, нужен тест с микрофоном.
3. Переключатель абсолютной громкости BT (п.22): по желанию.
4. "Стандарт" (п.3): только если владелец хочет буквальный паритет; по звуку хуже Кино.
