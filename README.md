# Lumen OS

English | [Русский](README.ru.md)

Android TV 14 for the XGIMI Z9X projector, built on LineageOS 21.

Status: 1.0.1, in testing.

<p>
<img src="docs/screenshots/01_sky.jpg" width="49%" alt="Lumen Home with the live sky">
<img src="docs/screenshots/05_proj.jpg" width="49%" alt="Projector settings in Lumen Home">
<img src="docs/screenshots/04_lang.jpg" width="49%" alt="Language choice in the first setup">
<img src="docs/screenshots/07_upd.jpg" width="49%" alt="Lumen OS update screen">
</p>

## What it is

Lumen OS replaces only the system partition of the Z9X. XGIMI's vendor firmware, boot, calibration,
lamp, focus and keystone stay as they are, and our apps drive them through XGIMI's own hardware service.
The interface runs at 1080p.

- Lumen Home: a clock over a live sky with the real sun and moon for your city (also the screensaver),
  weather, Continue watching from your installed apps, and all apps with favourites and A to Z.
- Projector app: autofocus, manual and auto keystone, picture and sound modes, eye protection, sleep,
  and a quick panel.
- Real sleep: the lamp goes off at once and the projector suspends. The remote wakes it in the same app.
- AirPlay receiver.
- 17 languages. The XGIMI remote pairs in the first setup.
- Updates over Wi-Fi, signed with the project's key. If a new version fails to start, the projector
  goes back to the previous one.
- Install from USB: any `.apk` from a USB stick, straight from Lumen Home.
- No ads and no Google services running in the background. At most 10 apps are kept cached.

Nothing about you is collected. The system goes online only for the time zone and city (ip-api.com over
plain HTTP, ipapi.co or ipwho.is), the weather (Open-Meteo) and updates (GitHub).

## Without Google

The published edition has no Google apps: no Google Play, no Chromecast built-in, no Google voice
search. Apps that need Google Play services will not run. Apps install from a USB stick or from any store
you choose. You can add Google services yourself from your own MindTheGapps download with one installer
command, see [Adding Google services](docs/install/en.md#google).

## Supported devices

| Device | Model code | XGIMI firmware | Status |
|---|---|---|---|
| XGIMI Z9X | G0082 | V6.15.58 or V6.15.19 | supported |

## Install

You need a computer with macOS or Linux (or Windows, in beta), Android Platform-Tools 35 or newer, a
USB A-to-A cable into the projector's USB 2.0 port, and one vbmeta step that you do by hand. The install
erases all data on the projector.

- [Install guide](docs/install/en.md)
- [Инструкция по установке](docs/install/ru.md)

Download only from this repository's [Releases](https://github.com/kmuradoff/lumen-os/releases). Every
release has `SHA256SUMS` and `SHA256SUMS.sig`; the installer checks both and refuses anything else.
Copies on forums and file hosts are not ours.

## Updates and going back

Lumen OS updates itself over Wi-Fi from Settings > Device Preferences > About > Lumen OS update. An update
installs into the other slot, keeps your data and waits for your OK. See [Updates](docs/install/en.md#updates)
and [CHANGELOG.md](CHANGELOG.md).

To go back, flash the official XGIMI firmware from a USB stick. This erases everything, see
[Back to stock](docs/install/en.md#stock).

## Known issues

- The Windows installer has not been tested yet.
- While a USB cable to a computer is connected, the projector does not really sleep. Unplug it after
  the install.
- HDMI-CEC has not been tested on the Z9X.
- The built-in keyboard types English; Lumen Home's search has its own keyboard.
- Once a new version has started, an update cannot be undone over the air.

## Help and bug reports

Open an [issue](https://github.com/kmuradoff/lumen-os/issues) and include the XGIMI firmware version,
the Lumen OS version (Settings > Device Preferences > About), what you did right before the problem and
the full installer output. Russian-speaking users also talk about Lumen OS in the
[XGIMI Z9X thread on 4PDA](https://4pda.to/forum/index.php?showtopic=1121837).

## Build

Lumen OS is put together from a LineageOS 21 TV GSI, our apps (`apps/`), system files (`system/`) and
the image tools (`tools/`). Notes for maintainers are in [docs/dev](docs/dev/README.md).

```
git clone --recurse-submodules https://github.com/kmuradoff/lumen-os.git
```

## Thanks

To the LineageOS, UxPlay, android-airplay-server and MindTheGapps projects, and to everyone who tests
Lumen OS and reports back.

## Support the project

Lumen OS is free and open source. If it is useful to you, you can support the work by card or in crypto.

Card (Russian bank cards and SBP): [pay.cloudtips.ru/p/723aaba1](https://pay.cloudtips.ru/p/723aaba1)

| Network | Address | QR |
|---|---|---|
| USDT on TRON (TRC20) only | `TRjBAWuPa7WjdsATNkgwy955imhobH6sQV` | <img src="docs/repo/support/usdt-trc20.png" width="140" alt="USDT TRC20 QR"> |
| TON, and USDT on TON | `UQA2yAApJyyZ_vOYwrM6E0K2hwmn_35Y7m68UbCN6Y_A1L6x` | <img src="docs/repo/support/ton.png" width="140" alt="TON QR"> |

Check the network before you send. USDT sent to the TRON address over another network (ERC20, BEP20) is lost.

Thank you.

## License

Apache License 2.0, except `apps/Z9xAirPlay` (GPL-3.0-only) and the third-party submodules under
`apps/third_party`, which keep their own licenses. See [LICENSE](LICENSE) and [NOTICE](NOTICE). This
repository holds no Google apps, no MediaTek or XGIMI files and no signing keys.

Lumen OS is an independent project by kmuradoff. It is not affiliated with XGIMI, Google, MediaTek or
the LineageOS project. "XGIMI", "Android", "Google" and "LineageOS" are trademarks of their owners.
