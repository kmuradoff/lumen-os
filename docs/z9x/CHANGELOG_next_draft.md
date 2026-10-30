# Z9X next release: changelog entries (move into docs/repo/CHANGELOG.md at release)

Draft of 2026-10-30 for the parity fixes (docs/z9x/parity/README.md). Merge with the 1.0.8 rename notes
(org.z9x -> org.lumen, bridges) when the release is cut. Not device-tested yet: check every line against the test
image before it goes into the public changelog, and drop what did not hold.

## 1.0.8, ноябрь 2026

Обновление придёт по Wi-Fi: «Настройки → Об устройстве → Обновление Lumen OS». Данные сохраняются.

- Автофокус после оптического зума больше не срывается: на время автофокуса показывается мишень на весь экран,
  как в стоке. Кнопка «Назад» отменяет автофокус, «влево» и «вправо» переводят в ручной фокус.
- Ручной фокус как в стоке: окно закрывается через 3 секунды и от любой другой кнопки, а кнопка фокуса сначала
  закрывает его и запускает автофокус.
- «Умный автофокус» как в стоке: включает и автофокус после перемещения проектора.
- Трапеция как в стоке: при сильной коррекции картинку снова рисует графический процессор в 4K со сглаживанием,
  защищённое видео (Netflix и другие) при коррекции в реальном времени показывается без цветного мусора.
- Сброс трапеции как в стоке: возвращает и размер картинки (объектив домой), если «Вписать в экран» его уменьшил.
- Коррекция трапеции в реальном времени работает и при потолочной установке.
- В окне оптического зума кнопка OK переключает на сдвиг картинки: стрелки двигают уменьшенную картинку.
- Саундбар по ARC: формат цифрового звука по умолчанию PCM, как в стоке. Фильмы с AC3 и DTS теперь звучат и на
  саундбарах без Dolby и DTS.
- Boost: включается и в тёплой комнате (как в стоке, ток лазера при перегреве снижает сам проектор), в
  подсказке сказано, что Boost ярче, но цвета бледнее. В Boost нельзя менять цветовое пространство и выбирать
  «Точность цвета», как в стоке.
- Изображение: новая строка «Сбросить все настройки изображения»; яркость, контраст, насыщенность и резкость
  меняются во всех режимах, как в стоке; «Точность цвета» доступна и для HLG и HDR10+; в игровом режиме и в 3D
  режим изображения не меняется, как в стоке.
- Игровой режим: не включается в 3D и с Dolby Vision; перед «Турбо» проектор спрашивает, если настроена
  трапеция; «Формат кадра» перенесён в строки HDMI на странице «Изображение»; «120 Гц для приставок» по
  умолчанию выключено.

## English

## 1.0.8, November 2026

The update arrives over Wi-Fi: Settings → About → Lumen OS update. Your data stays.

- Autofocus after the optical zoom no longer fails: a full-screen focus card is shown while it runs, as on stock.
  Back cancels the autofocus, Left and Right switch to manual focus.
- Manual focus as on stock: the window closes after 3 seconds and on any other key, and the focus key closes it
  first and starts the autofocus.
- Smart autofocus as on stock: it also turns on autofocus after the projector is moved.
- Keystone as on stock: a strong correction is drawn by the GPU again, at 4K with anti-aliasing, and protected
  video (Netflix and others) under real-time correction shows without colored garbage.
- Keystone reset as on stock: it also brings the picture size back (lens home) after Fit to the screen made it
  smaller.
- Real-time keystone correction also works on a ceiling mount.
- In the optical zoom screen OK switches to moving the picture: the arrows move a smaller picture around.
- Soundbar over ARC: the digital audio format is PCM by default, as on stock. Films with AC3 and DTS now play on
  soundbars without Dolby and DTS too.
- Boost: it turns on in a warm room as well (as on stock, the projector lowers the laser current itself when
  hot), and the hint says that Boost is brighter but colors are paler. In Boost the color space and Color
  accuracy cannot be changed, as on stock.
- Picture: a new Reset all picture settings row; brightness, contrast, saturation and sharpness can be changed
  in every mode, as on stock; Color accuracy is offered for HLG and HDR10+ too; the picture mode does not change
  in game mode or in 3D, as on stock.
- Game mode: it does not turn on in 3D or with Dolby Vision; before Top speed the projector asks if a keystone
  correction is set; Aspect ratio moved to the HDMI rows of the Picture page; 120 Hz for consoles is off by
  default.
