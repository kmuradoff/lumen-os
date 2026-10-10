# Собрать Lumen OS самому

[English](en.md) | Русский

Если не хотите ставить готовый образ, Lumen OS можно собрать целиком из исходников на своём компьютере и
подписать своими ключами. Получится та же версия без Google, что в релизах.

Что нужно знать заранее:

- Образ будет подписан вашими ключами. Наши обновления по воздуху на него не встанут: обновляете его
  новой сборкой. Чтобы вернуться на наши релизы, ставите их установщиком заново, со стиранием данных.
- Ваш образ не совпадёт с нашим байт в байт: у него другие подписи, своя дата сборки, а библиотеки и
  приложения собраны вашим компилятором. Состав и проверки те же.
- Первая сборка LineageOS идёт несколько часов.

## Что понадобится

- Компьютер с Linux x86-64 (проверено на Ubuntu), 16 ГБ памяти и swap 32 ГБ (лучше 32 ГБ памяти),
  около 400 ГБ на диске: исходники LineageOS занимают около 150 ГБ, сборка ещё около 200 ГБ.
- Docker для сборки LineageOS (или пакеты из `lineage/Dockerfile`).
- `python3`, `openssl`, `rsync`, `unzip`, `debugfs` (пакет e2fsprogs), `dump.erofs` (пакет erofs-utils),
  для заставки `Pillow` и `numpy` (`pip install pillow numpy`).
- [Android NDK r29](https://developer.android.com/ndk/downloads) (29.0.14206865) для приёмника AirPlay.
- Ваш проектор XGIMI Z9X на прошивке V6.15.58 или V6.15.19. С него, как и при обычной установке,
  берутся файлы видеокодеков и звука MediaTek. Их нет в репозитории и в образе.
- `MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip`, ссылка в
  `tools/ota/image/gapps_allow.txt`. Он нужен, только чтобы база собралась такой же, как у нас. В образе
  без Google этих приложений нет.

## 1. Исходники LineageOS

В `lineage/manifests/lumen-lineage-21.xml` перечислены все проекты LineageOS 21 ровно в тех версиях, из
которых собрана наша база.

```sh
mkdir ~/lineage && cd ~/lineage
repo init -u https://github.com/LineageOS/android.git -b lineage-21.0 --git-lfs
cp ~/lumen-os/lineage/manifests/lumen-lineage-21.xml .repo/manifests/
repo init -m lumen-lineage-21.xml
repo sync -c -j8
```

Наш патч для кодеков MediaTek:

```sh
cd ~/lineage/frameworks/av
git apply ~/lumen-os/lineage/patches/frameworks_av_C2Store_igba_hidl.patch
```

## 2. Сборка LineageOS

```sh
cd ~/lineage
source build/envsetup.sh
export WITH_DEXPREOPT=true WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=true SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
breakfast gsi_tv_arm64
m systemimage apksigner
```

`apksigner` нужен свежий, из исходников: тот, что лежит в `prebuilts`, слишком старый для подписи модулей
APEX. Предкомпиляция (`WITH_DEXPREOPT`) обязательна. Без неё первый запуск слишком долгий, и защита XGIMI
перезагружает проектор. Так же собирает и наш скрипт `lineage/z9x_build_loop.sh` в контейнере из
`lineage/Dockerfile`.

Результат: `~/lineage/out/target/product/generic_arm64/system.img`.

## 3. Файлы с вашего проектора

Подключите проектор по USB с отладкой по USB, как в [инструкции по установке](../install/ru.md), и
запустите из репозитория:

```sh
bash installer/lumen-install.sh blobs
```

Установщик заберёт файлы кодеков и звука, проверит их по `blobs_allow.txt` и сохранит в
`~/Lumen-backup/blobs-<серийный номер>.img`. Работает и на родной прошивке XGIMI, и на Lumen OS.

## 4. Сборка Lumen OS

```sh
cd ~/lumen-os
export LINEAGE=~/lineage ANDROID_NDK=~/android-ndk-r29
tools/selfbuild/build.sh keys
tools/selfbuild/build.sh all ~/lineage/out/target/product/generic_arm64/system.img \
    ~/Downloads/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip ~/Lumen-backup/blobs-<серийный номер>.img
```

- `keys` создаёт ваши ключи в `~/.lumen-self-keys`. Сделайте две копии этой папки на флешку или в
  менеджер паролей. Без ключа `platform` новую сборку получится поставить только со стиранием данных.
- `all` по очереди делает базу (`base`), дерево сборки (`tree`), все приложения из исходников (`apps`) и
  образ (`image`). Каждый шаг можно запустить отдельно.

Готовые файлы окажутся в `build/selfbuild/release/`: образ, `SHA256SUMS`, подпись `SHA256SUMS.sig` вашим
ключом и копия установщика, которая доверяет вашему ключу.

## 5. Установка

Дальше всё как в [инструкции по установке](../install/ru.md), только установщик берите из папки сборки:

```sh
build/selfbuild/release/installer/lumen-install.sh --image build/selfbuild/release/lumen-os-1.0.2-nogms-system.img
```

## Как это проверено

- Скрипт базы `tools/selfbuild/make_base.sh` из того же образа LineageOS собирает нашу базу один в один:
  все 6560 файлов совпадают по содержимому, правам и меткам SELinux. Отличаются только три раскладки
  пультов, которые сборка всё равно заменяет своими.
- Полную сборку прогнали на Linux с чистой копии репозитория и с другими ключами. В образе ровно те же
  6444 файла, что в нашем релизе 1.0.2, и 6326 из них совпадают с нашими байт в байт. Остальные 118
  отличаются только из-за ключей: подписи приложений и модулей APEX, их предкомпилированный код,
  сертификат обновлений, правила SELinux с сертификатом платформы и номер сборки в build.prop.
- При сборке образа работают те же проверки, что и для наших релизов. Нет файлов MediaTek и XGIMI, нет
  приложений Google, каждое приложение и модуль APEX подписаны вашими ключами, каждый файл образа
  совпадает с собранным. Отличие одно: вместо наших контрольных сумм собственных сборок
  (`libcodec2_vndk`, TvInput, AirPlay) проверяются ваши.

Если что-то не собирается, напишите в [Issues](https://github.com/kmuradoff/lumen-os/issues) и
приложите вывод команды.
