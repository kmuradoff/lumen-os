# Список изменений / Changelog

Сверху новые версии. Версии 1.0.x выходят для XGIMI Z9X, у XGIMI Play 6 свои записи с пометкой Play 6.
[English below](#english).

## 1.0.6, октябрь 2026

Обновление придёт по Wi-Fi: «Настройки → Об устройстве → Обновление Lumen OS». Данные сохраняются.

- Bluetooth-колонки и наушники: звук идёт в колонку (раньше подключённая колонка могла молчать), а при
  переключении обратно на проектор звук возвращается сразу.
- Громкость синхронизируется с колонкой в обе стороны: кнопки пульта меняют громкость самой колонки, а её
  кнопки меняют громкость на проекторе. Колонку, подключённую до обновления, может понадобиться удалить из
  списка Bluetooth и подключить заново.
- Lumen Home, «Файлы → Сетевые папки»: общие папки компьютера или NAS (Windows, Mac, Linux, Synology и другие)
  по адресу, логину и паролю. Видео, музыка и фото открываются прямо с сервера, файл подкачивается заранее,
  чтобы фильм не ждал сеть. SMB 2 и 3, включая шифрование.
- Lumen Play 1.1 (обновляется сам, отдельно от системы): Dolby Vision как в Kodi, продолжение сериалов с
  следующей серии, пауза с кнопок Bluetooth-колонки, быстрее торренты, плеер не зависает после сна и при
  отключении колонки.

## XGIMI Play 6, 1.0.6, октябрь 2026

Ставится тем же установщиком, `--keep-data flash` сохраняет данные. Первая версия Play 6 после беты 1.

- Всё, что есть в стоковых настройках Play 6, теперь есть и в Lumen, кроме того, что работает только через
  облако XGIMI. Список по пунктам: [docs/play6/TASKS.md](docs/play6/TASKS.md).
- Зум и сдвиг картинки как на стоке: стрелки двигают, кнопки громкости меняют размер, шестерёнка открывает
  «Сброс» (полная картинка по центру). После сдвига картинку снова можно увеличить до полного размера.
- Поворот изображения без задержек, шумоподавление, гамма, динамический и локальный контраст, цветовая
  температура с усилением R, G и B, сброс трапеции как на стоке.
- Bluetooth-колонки и наушники: звук идёт ровно и с нужной скоростью, динамик проектора при этом молчит,
  громкость синхронизируется с колонкой. «Звук → Вывод звука»: динамик проектора или Bluetooth.
- «Колонка и свет»: один режим, как у XGIMI. Проектор светится цветом, а телефон может играть на нём музыку
  по Bluetooth. Кнопка-ярлык на пульте включает режим, повторное нажатие меняет цвет. Пока проектор закрыт,
  «Назад» свет не выключает.
- Магнитные линзы (луна, мечта, рябь, закат): поставьте линзу, и проектор покажет её картинки и видео с музыкой
  из стоковой прошивки. Влево и вправо листают, вверх и вниз меняют яркость.
- Сетевые папки в «Файлах», как на Z9X.
- Эквалайзер и пресеты звука, как на стоке. Новый Lumen Home и бегущая строка для длинных названий.
- Голосовой ассистент, как на Z9X (нужен распознаватель речи Google).
- Переключатели в «Установка неизвестных приложений» и похожих экранах настроек теперь двигаются.
- Кнопка питания гасит только лампу. Глубокий сон выключен: выход из него на Play 6 был холодным стартом.

## 1.0.5, октябрь 2026

Обновление придёт по Wi-Fi: «Настройки → Об устройстве → Обновление Lumen OS». Данные сохраняются.

- Голосовой ассистент. Зажмите кнопку микрофона на пульте и говорите: поверх любого приложения сверху опускается
  тёмная стеклянная капсула с цветной волной, слова появляются по мере речи. Отпустите кнопку, и проектор
  сам выполнит команду: «открой ютуб», «громче», «громкость 30», «пауза», «на главную», «автофокус», «выровняй
  картинку», «настройки звука», «который час», «найди Интерстеллар», «котики на ютубе». Остальное ищется в Lumen Home.
  Время и ответы звучат голосом. Язык речи и голос выбираются в «Проектор → Голосовой ассистент»; если пакета
  языка нет, ассистент предложит его скачать. Голос Gemini включается
  вашим ключом Gemini API; в Google уходит только текст ответа, команды разбирает сам проектор. Нужен распознаватель
  речи Google, в выпуске без Google кнопка по-прежнему открывает поиск.
- Новый главный экран Lumen Home. Один ряд «Для вас»: сначала то, что вы смотрите, потом рекомендации, без
  повторов. Фон на весь экран берётся из выбранной карточки и медленно движется. В «Продолжить просмотр» у
  сериалов теперь постер сериала, а не кадр из серии.
- Приложение «Кино» стало Lumen Play: главная, ТВ (IPTV-плейлисты с программой передач), торренты с поиском по
  источникам Jackett и Torznab, файлы. Lumen Play виден телефонам и компьютерам как DLNA-телевизор: видео можно
  отправить на проектор из приложений с кнопкой «Воспроизвести на устройстве». Lumen Play обновляется сам, отдельно
  от системы.
- Рекомендации магазинов приложений (RuStore, Google Play) больше не попадают в «Для вас»: это реклама приложений.
  Показать их можно в «Настроить главный экран».
- AirPlay: картинка и звук идут вместе (раньше картинка отставала на секунду), меньше обрывов звука по Wi-Fi.
- Длинные названия в настройках проектора и быстрой панели бегут строкой, пока на них фокус, и их можно
  прочитать целиком.
- Chromecast built-in убран: Google не выдаёт ключи устройства для несертифицированных проекторов, поэтому
  проектор появлялся в списке, но не подключался. Что использовать вместо него: раздел
  [«Chromecast»](docs/install/ru.md#chromecast).

## 1.0.4, октябрь 2026

Обновление придёт по Wi-Fi: «Настройки → Об устройстве → Обновление Lumen OS». Данные сохраняются.

Из стоковой прошивки XGIMI перенесено то, чего в Lumen не было:

- «Изображение → Профессиональные настройки»: баланс белого (2 и 11 точек), цветовое пространство, оптимизация и
  коррекция цвета (7 цветов), гамма, динамическое отображение HDR, локальный и динамический контраст, усиление
  чёрного, исходная частота кадров, шумоподавление.
- Настройки HDMI для входа на экране: цветовой диапазон и режим HDMI (совместимый 1.4 или улучшенный 2.0).
- «Трапеция → Поворот изображения».
- «Звук»: выравнивание громкости, eARC и выбор вывода звука (автоматически, динамик проектора, HDMI ARC, Bluetooth,
  наушники, USB). Формат звука для ARC: автоматически, PCM или без обработки.
- «Проектор → Включение»: мелодия при включении и вход, который открывается при включении (HDMI 1 или HDMI 2).
- «Файлы»: встроенный файловый менеджер для памяти проектора и флешек. Видео, музыка и фото открываются во
  встроенном плеере, .apk ставятся прямо отсюда, а если удерживать OK на файле или папке, их можно скопировать или
  удалить. Отдельные плитки «Установка с USB» и «Больше приложений» убраны.
- Руководство пользователя ([docs/guide](docs/guide/ru.md)) и QR-код на него в настройках проектора.

Исправления:

- Громкость HDMI-источника (приставки, консоли) теперь меняется пультом. В 1.0.3 кнопки громкости на HDMI
  ничего не делали.
- Игровой режим, «Скорость отклика»: при смене больше не появляется «Не удалось», когда скорость на самом деле
  сменилась.
- «Для разработчиков → ADB over network» больше не выключается после перезагрузки, если вы сами его включили.
  Подключение компьютера по-прежнему нужно подтвердить на экране проектора.
- Ручной оптический зум («Трапеция → Оптический зум»): одно нажатие теперь один шаг, нажатия не теряются,
  объектив не сбивается со счёта. Ещё одно «больше» на самом большом размере ставит объектив в настоящий
  максимум, как после автотрапеции. После автотрапеции зум возвращается к вашему размеру, если проектор не
  вписывал картинку в экран сам. Первое «меньше» после автотрапеции немного качает объектив: так проектор
  переходит на целые шаги.
- Bluetooth-наушники: проектор выставляет им громкость так же, как стоковая прошивка. Без этого наушники могли
  молчать.
- Длинные значения в быстрой панели больше не налезают на стрелки.

## XGIMI Play 6, бета 1, октябрь 2026

Первая бета Lumen OS для XGIMI Play 6 (G0058) с прошивкой XGIMI V1.7.116. Релиз с тегом `play6-beta1`
помечен как Pre-release, инструкция в разделе [«XGIMI Play 6»](docs/install/ru.md#play6). Это бета: ставьте,
если готовы тестировать и писать о проблемах.

- Установка с компьютера по Wi-Fi. Кабель USB A–A нужен только для записи системы. Установка стирает все
  данные: после первого запуска установщик делает сброс Android к заводским настройкам.
- Lumen Home, облегчённая для слабой графики Play 6: живое небо рисуется не чаще 12 кадров в секунду и
  частями, а пока вы нажимаете кнопки, оно стоит.
- Своё приложение «Проектор» для Play 6: фокус, трапеция, режимы изображения и звука, быстрая панель,
  энергосбережение при работе от аккумулятора. Короткое нажатие кнопки питания гасит лампу. Пунктов, которых
  у Play 6 нет, в настройках не видно.
- HDMI: есть картинка, смена сигнала не перезапускает систему, громкость меняется пультом. Игровой режим как
  на стоке. Приставки и консоли называются по имени (PlayStation, Xbox и другие).
- Первая настройка Lumen Setup: пульт, язык, Wi-Fi, часовой пояс по IP.
- Новая версия для Play 6 ставится тем же установщиком: `--keep-data flash` сохраняет данные.
- Пока нет: обновлений по Wi-Fi, приёмника AirPlay, сервисов Google. Кинопоиск работает; на проверенном Play 6
  Widevine уровня L3, как и на стоке, поэтому защищённые фильмы могут идти в пониженном качестве.

## 1.0.3, октябрь 2026

HDMI, заставка, 3D и оптический зум. На 1.0.2 обновление приходит по Wi-Fi: «Настройки → Настройки устройства →
Об устройстве → Обновление Lumen OS».

- HDMI: на входах HDMI 1 и HDMI 2 теперь есть картинка. Раньше экран оставался чёрным.
- HDMI: если сменить сигнал или переподключить кабель, пока на экране HDMI, система больше не зависает и не
  перезапускается.
- HDMI-CEC: приставки и консоли называются по имени («PlayStation», «Xbox», «Nintendo Switch», «Apple TV» и
  другие) вместо «Playback_1». Когда открывается вход, на пару секунд видна табличка с именем устройства и номером
  входа.
- HDMI-CEC: на экране входа и на карточках «Входы» в Lumen Home видно, что с устройством: включено, спит, нет
  сигнала или не подключено. Спящую консоль будит кнопка OK.
- Заставка снова включается сама, когда проектором долго не пользуются (а после неё, в своё время, режим
  ожидания). Раньше при питании от сети она не запускалась. Параметр для разработчиков «Не выключать экран»
  теперь сразу выключается обратно, иначе заставка и режим ожидания снова не работали бы.
- Игры по HDMI: консоль на входе HDMI теперь сама включает игровой режим. Её узнают по HDMI-CEC, по ALLM, по
  частоте 100 Гц и выше или по отметке «Консоль на этом входе». Когда вы уходите со входа, прежние настройки
  изображения возвращаются. Раньше эти настройки на входах HDMI не срабатывали. Выключается в быстрой панели:
  «Игры (HDMI) → Автовключение на консолях».
- Живое небо: дневное небо больше не темнеет до ночного через минуту после запуска заставки. Новая погода
  «пасмурно»: сплошные серые облака без солнца.
- 3D: в быстрой панели на странице «Изображение» можно включить 3D («Бок о бок» или «Сверху и снизу») или
  показать такой фильм обычной картинкой («как 2D»). Работает, пока идёт видео; для объёма нужны активные очки
  DLP-Link.
- Оптический зум: новая строка на странице «Трапеция» быстрой панели. Влево и вправо меняют размер картинки
  без потери чёткости. Известная проблема 1.0.3: число размера на этой странице не меняется, а после
  нескольких шагов картинка может перестать меняться.
- Уведомления проектора, карточки на экране HDMI и карточки «Входы» в Lumen Home подстраиваются под текст:
  длинный текст больше не обрезается, короткий не стоит в широкой пустой карточке.

## 1.0.2, октябрь 2026

Исправления к 1.0.1. На 1.0.1 обновление приходит по Wi-Fi: «Настройки → Настройки устройства → Об устройстве →
Обновление Lumen OS».

- Настройки Android: переключатели в «Установке неизвестных приложений», у служб специальных
  возможностей и в паре параметров для разработчиков теперь сдвигаются, когда вы что-то разрешаете
  или запрещаете. Раньше менялась только подпись, а сам переключатель оставался выключенным. Это была
  ошибка Android 14 для телевизоров, сами настройки работали.

## 1.0.1, октябрь 2026

Первый релиз версии без Google, который может поставить любой владелец Z9X: образ системы и установщик
для компьютера.

- Версия без Google: в образе нет приложений Google. Файлы кодеков и звука установщик берёт с вашего
  же проектора.
- Сервисы Google можно добавить самому командой установщика `gapps` из своего файла MindTheGapps.
- В Lumen Home появилась плитка «Установить с USB»: она ставит `.apk` с флешки.
- В фоне держится не больше 10 приложений, системе стало легче.
- Главная: «Продолжить просмотр» можно скрыть в «Настроить главный экран» и оставить только часы и
  живое небо. Картинки стали чётче, часы и значки видны на любом фоне, по краям экрана ничего не
  обрезается.
- Вкладки «Входы» больше нет. Входы остались в быстрой панели и на кнопке источника.
- Живое небо стало заставкой и включено сразу после установки.
- «Недавние приложения»: под карточками вместо «1 января 1970» теперь видно, когда приложение открывали.
- Трапеция: углы быстрее идут за пультом, «Назад» сохраняет ровно то, что на экране.
- Автотрапеция: её окно можно закрыть кнопкой «Назад», если оно висит на экране дольше 2 секунд. Если
  приложение проектора в это время зависнет, оно перезапустится само.
- Первая настройка: пульт подтверждается кнопкой OK.
- AirPlay: исправлены разрывы звука.
- Обновления: после установки обновления нет лишней перезагрузки.
- Ремонт: если система 3 раза подряд не загрузилась, проектор уходит в режим fastboot и ждёт
  компьютер. Команда установщика `rescue` записывает систему заново и не стирает данные.

Исходный код приёмника AirPlay (GPL-3.0) приложен к релизу файлом `lumen-os-1.0.1-airplay-source.zip`.

Версии до 1.0.1 были тестовыми.

---

<a name="english"></a>

Newest first. Versions 1.0.x are for the XGIMI Z9X; the XGIMI Play 6 has its own entries marked Play 6.

## 1.0.6, October 2026

The update arrives over Wi-Fi: Settings > About > Lumen OS update. Your data stays.

- Bluetooth speakers and headphones: the sound goes to the speaker (a connected speaker could stay silent before),
  and switching back to the projector brings the sound back at once.
- The volume follows the speaker both ways: the remote changes the speaker's own volume and the speaker's buttons
  change it on the projector. A speaker paired before the update may need to be removed and paired again.
- Lumen Home, Files > Network folders: shared folders of a computer or a NAS (Windows, Mac, Linux, Synology and
  others) by address, login and password. Videos, music and photos open straight from the server, the file is
  read ahead so a film does not wait for the network. SMB 2 and 3, encryption included.
- Lumen Play 1.1 (updates itself, apart from the system): Dolby Vision as in Kodi, series continue with the next
  episode, pause from the Bluetooth speaker's buttons, faster torrents, no freeze after sleep or when the speaker
  disconnects.

## XGIMI Play 6, 1.0.6, October 2026

Installs with the same installer; `--keep-data flash` keeps your data. The first Play 6 version after beta 1.

- Everything in the Play 6 stock settings is now in Lumen too, except what only works through XGIMI's cloud.
  The item-by-item list: [docs/play6/TASKS.md](docs/play6/TASKS.md).
- Zoom and shift as on stock: the arrows move, the volume keys change the size, the gear opens Reset (the full
  picture in the centre). After a move the picture grows back to full size.
- Picture rotation without lag, noise reduction, gamma, dynamic and local contrast, colour temperature with R, G
  and B gain, keystone reset as on stock.
- Bluetooth speakers and headphones: smooth sound at the right speed, the projector speaker stays silent, the
  volume follows the speaker. Sound > Sound output: the projector speaker or Bluetooth.
- Ambient speaker: one mode, as on XGIMI. The projector glows in colour and a phone can play music to it over
  Bluetooth. The remote's shortcut key starts it, pressing again changes the colour. While the projector is
  closed, Back does not turn the light off.
- Magnetic lenses (lunar, dreamscape, ripple, sunset): put a lens on and the projector shows its pictures and
  videos with music from the stock firmware. Left and right change the item, up and down the brightness.
- Network folders in Files, as on the Z9X.
- Equalizer and sound presets, as on stock. The new Lumen Home and scrolling long names.
- The voice assistant, as on the Z9X (needs Google's speech recognizer).
- The switches of Install unknown apps and similar settings screens now move.
- The power button turns off only the lamp. Deep sleep is off: waking from it was a cold start on the Play 6.

## 1.0.5, October 2026

The update comes over Wi-Fi: Settings > Device Preferences > About > Lumen OS update. Your data stays.

- A voice assistant. Hold the remote's mic button and speak: over any app a dark glass capsule with a colour wave
  comes down from the top and your words appear as you speak. Release the button and the projector does it
  itself: "open YouTube", "louder", "volume 30", "pause", "go home", "focus", "keystone", "sound settings", "what time
  is it", "find Interstellar", "cats on YouTube". Anything else is searched in Lumen Home. The time and answers
  are spoken. The speech language and the voice are in Projector > Voice assistant; when a language pack is
  missing, the assistant offers to download it. Gemini's voice comes with your own Gemini
  API key; only the reply text goes to Google, the projector understands the commands itself. It needs Google's
  speech recognition; in the edition without Google the button still opens search.
- A new Lumen Home. One "For you" row: what you are watching first, then recommendations, no repeats. The
  full-screen background comes from the focused card and moves slowly. Series in "Continue watching" now show the
  series poster, not a frame of the episode.
- The "Kino" app is now Lumen Play: home, TV (IPTV playlists with a programme guide), torrents with search through
  Jackett and Torznab sources, files. Phones and computers see Lumen Play as a DLNA TV, so apps with "Play on
  device" can send video to the projector. Lumen Play updates itself, apart from the system.
- App store recommendations (RuStore, Google Play) no longer appear in "For you": they only advertise apps. Customize
  Home can still show them.
- AirPlay: picture and sound stay together (the picture used to lag a second behind), fewer sound dropouts over
  Wi-Fi.
- Long names in the projector settings and the quick panel scroll while focused, so they can be read in full.
- Chromecast built-in is gone: Google gives no device keys to uncertified projectors, so the projector showed up
  in the list but never connected. What to use instead: [Chromecast](docs/install/en.md#chromecast).

## 1.0.4, October 2026

The update comes over Wi-Fi: Settings > Device Preferences > About > Lumen OS update. Your data stays.

Brought over from XGIMI's stock firmware, what Lumen did not have:

- "Picture > Professional settings": white balance (2 and 11 points), color space, color optimization and
  correction (7 colors), gamma, HDR dynamic tone mapping, local and dynamic contrast, dynamic black enhancement,
  original frame rate, noise reduction.
- HDMI settings of the input on screen: color range and HDMI mode (Compatible 1.4 or Enhanced 2.0).
- "Keystone > Picture rotation".
- "Sound": volume leveling, eARC and the sound output choice (auto, projector speaker, HDMI ARC, Bluetooth,
  headphones, USB). Audio format for ARC: auto, PCM or passthrough.
- "Projector > Power on": power-on sound and the input to open at power-on (HDMI 1 or HDMI 2).
- "Files": a built-in file manager for the projector's storage and USB sticks. Videos, music and photos open in
  the built-in player, .apk files install from here, and holding OK on a file or folder copies or deletes it. The
  separate "Install from USB" and "Get more apps" tiles are gone.
- A user guide ([docs/guide](docs/guide/en.md)) and a QR code to it in the projector settings.

Fixes:

- The volume of an HDMI source (set-top box, console) now follows the remote. On 1.0.3 the volume keys did
  nothing on HDMI.
- Game mode, "Response speed": changing it no longer says "Could not" when the speed did change.
- "Developer options > ADB over network" stays on after a restart when you turned it on yourself. A computer still
  has to be allowed on the projector screen.

- Manual optical zoom ("Keystone > Optical zoom"): one press is now one step, presses are not lost, and the lens
  no longer loses count. One more "bigger" at the biggest size puts the lens at its real maximum, as after auto
  keystone. After auto keystone the zoom goes back to your size, unless the projector fitted the picture to the
  screen itself. The first "smaller" after auto keystone moves the lens back and forth a little: that is the
  projector switching to whole steps.
- Bluetooth headphones: the projector sets their volume the way the stock firmware does. Without it the
  headphones could stay silent.
- Long values on the quick panel no longer run over their arrows.

## XGIMI Play 6, beta 1, October 2026

The first beta of Lumen OS for the XGIMI Play 6 (G0058) on XGIMI firmware V1.7.116. The release has the tag
`play6-beta1` and is marked Pre-release; the guide is the [XGIMI Play 6](docs/install/en.md#play6) part of the
install guide. This is a beta: install it if you are ready to test and report back.

- Installs from a computer over Wi-Fi. The USB A-to-A cable is needed only to write the system. The install
  erases all data: after the first start the installer runs Android's factory reset.
- Lumen Home, made lighter for the Play 6's weak graphics: the live sky draws at most 12 frames a second and
  in parts, and it holds still while you press keys.
- The Play 6's own projector app: focus, keystone, picture and sound modes, the quick panel and power saving
  on battery. A short press on the power button turns the lamp off. Rows the Play 6 does not have are hidden.
- HDMI: a picture, no restart on a signal change, and the remote sets the volume. Game mode as on stock.
  Players and consoles show their name (PlayStation, Xbox and others).
- First setup with Lumen Setup: remote, language, Wi-Fi, time zone from the IP address.
- A newer version for the Play 6 goes on with the same installer: `--keep-data flash` keeps your data.
- Not there yet: updates over Wi-Fi, the AirPlay receiver, Google services. Kinopoisk works; on the Play 6 we
  tested Widevine is L3, as on stock, so protected films may play in lower quality.

## 1.0.3, October 2026

HDMI, the screensaver, 3D and optical zoom. On 1.0.2 the update comes over Wi-Fi: Settings > Device Preferences >
About > Lumen OS update.

- HDMI: HDMI 1 and HDMI 2 now show a picture. Before, the screen stayed black.
- HDMI: changing the signal or plugging the cable in again while HDMI is on screen no longer freezes and restarts
  the system.
- HDMI-CEC: players and consoles show their name (PlayStation, Xbox, Nintendo Switch, Apple TV and others) instead
  of "Playback_1". When an input opens, a banner with the device name and the input shows for a few seconds.
- HDMI-CEC: the input screen and the Inputs cards in Lumen Home show what the device is doing: on, asleep, no
  signal or not connected. OK wakes a console that is asleep.
- The screensaver starts by itself again when the projector is not used for a while (and standby after it, at
  its time). Before, it never started on mains power. The developer option Stay awake now turns itself off again
  at once; with it on, the screensaver and standby would stop working again.
- HDMI games: a console on an HDMI input now turns on game mode by itself. It is recognised by HDMI-CEC, by ALLM,
  by a rate of 100 Hz or more, or by "This input is a console". When you leave the input, the previous picture
  settings come back. Before, these settings never took effect on the HDMI inputs. Turn it off in the quick panel:
  Game (HDMI) > Auto game mode for consoles.
- Live sky: a daytime sky no longer turns into a dark night a minute after the screensaver starts. New weather,
  overcast: a closed grey cloud deck without the sun.
- 3D: on the Picture page of the quick panel you can turn on 3D (Side by side or Top and bottom) or show such a
  film as a normal picture ("as 2D"). It works while a video plays; the 3D effect needs active DLP-Link glasses.
- Optical zoom: a new row on the Keystone page of the quick panel. Left and right change the picture size
  without losing sharpness. Known issue in 1.0.3: the size number on that page does not change, and after a
  few steps the picture may stop changing.
- Projector notifications, the cards on the HDMI screen and the Inputs cards in Lumen Home fit their text: long
  text is no longer cut, and short text no longer sits in a wide empty card.

## 1.0.2, October 2026

Fixes for 1.0.1. On 1.0.1 the update comes over Wi-Fi: Settings > Device Preferences > About > Lumen OS update.

- Android settings: the switches in Install unknown apps, on accessibility services and in a couple of
  developer options now move when you allow or deny something. Before, only the label changed and the
  switch stayed off. This was a bug of Android 14 for TV; the settings themselves worked.

## 1.0.1, October 2026

The first release of the edition without Google that any Z9X owner can install: the system image and
the installer for a computer.

- Edition without Google: the image has no Google apps. The installer takes the codec and audio files
  from your own projector.
- You can add Google services yourself with the installer's `gapps` command and your own MindTheGapps
  download.
- Lumen Home has an Install from USB tile that installs `.apk` files from a USB stick.
- At most 10 apps are kept cached in the background, so the system has more room.
- Home: you can hide Continue watching in Customize Home and keep only the clock and the live sky.
  Pictures are sharper, the clock and icons are readable on any background, and nothing is cut off at
  the screen edges.
- The Inputs tab is gone. Inputs are on the quick panel and the input key.
- The live sky is now the screensaver, on right after the install.
- Recent apps: the cards show when an app was last used instead of "1 January 1970".
- Keystone: corners follow the remote faster, and Back saves exactly what you see.
- Auto keystone: Back closes its on-screen window once it has been up for 2 seconds. If the projector
  app hangs while the window is up, it restarts by itself.
- First setup: confirm the remote with OK.
- AirPlay: sound dropouts fixed.
- Updates: no extra restart after an update.
- Rescue: if the system fails to start 3 times in a row, the projector goes to fastboot mode and waits
  for a computer. The installer's `rescue` command writes the system again without erasing data.

The AirPlay receiver source (GPL-3.0) is attached to the release as `lumen-os-1.0.1-airplay-source.zip`.

Versions before 1.0.1 were test builds.
