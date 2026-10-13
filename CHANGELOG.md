# Список изменений / Changelog

Сверху новые версии. Версии 1.0.x выходят для XGIMI Z9X, у XGIMI Play 6 свои записи с пометкой Play 6.
[English below](#english).

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
