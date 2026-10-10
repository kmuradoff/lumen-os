# Список изменений / Changelog

Сверху новые версии. [English below](#english).

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
