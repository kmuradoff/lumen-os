# Lumen OS

**An open Android TV system for XGIMI projectors.** First target: **XGIMI Z9X** (model G0082, vendor firmware V6.15.58).

Lumen OS replaces only the `system` partition. XGIMI's vendor, kernel, bootloader and calibration stay untouched, and every projector feature is driven through XGIMI's own vendor HAL by our code.

> Status: **1.0, in testing.** Not yet recommended for daily use by others.

## What you get

- **Real sleep.** The lamp turns off at once, the projector enters suspend-to-RAM (fans and indicator off) and wakes from the remote or the body button in seconds, back in the same app.
- **Lumen Home**: a fast home screen in the Google TV style, with recommendations from your installed apps. The classic Android TV launcher can be chosen instead.
- **Recent apps** on long-press HOME.
- **Projector controls over any content**: autofocus, auto and manual keystone (corners land exactly on the picture edge), screen fit, picture modes including AI, lamp brightness and Boost, game mode and 120 Hz, eye protection.
- **Sound**: Harman/DTS modes, AI sound (switches by app) and a 7-band equalizer.
- **AirPlay** receiver built in, HDMI inputs with console detection, HDMI-CEC.
- **First-run setup**, 17 languages, over-the-air updates signed with the project's own keys.
- **Faster and lighter**: Google Assistant and other unused services removed, apps compiled ahead of time, memory tuning for 2.8 GB RAM.

## Install

- English: [docs/install/en.md](docs/install/en.md)
- Русский: [docs/install/ru.md](docs/install/ru.md)

Flashing a projector always carries risk. The `vbmeta` step is always done by you by hand; the installer never runs it and never touches the persist/calibration partitions.

## Build

Lumen OS is assembled from a LineageOS 21 TV GSI base, our apps (`apps/`), init/overlay files (`system/`) and the image tooling (`tools/`). Start with [tools/lumen](tools/lumen) and [docs/release.md](docs/release.md). Over-the-air updates: [docs/ota.md](docs/ota.md). Keys policy: [docs/keys.md](docs/keys.md).

```bash
git clone --recurse-submodules https://github.com/kmuradoff/lumen-os.git
```

## What is not in this repository

- Google apps and services (MindTheGapps): proprietary, see their own terms.
- MediaTek or XGIMI binaries. The installer copies the MediaTek codec files from your own projector.
- Signing keys. Only public certificates are published.

## Support the project

**Поддержать проект.** Lumen OS is free and open. If it made your projector better, you can support the work with a card or crypto.
Lumen OS бесплатна и открыта. Если она сделала ваш проектор лучше, проект можно поддержать картой или криптой.

**Card / Картой** (Russian bank cards, SBP / карты российских банков, СБП): [pay.cloudtips.ru/p/723aaba1](https://pay.cloudtips.ru/p/723aaba1)

**Crypto / Криптой:**

| Network / Сеть | Address / Адрес | QR |
|---|---|---|
| **USDT — TRON (TRC20) only** | `TRjBAWuPa7WjdsATNkgwy955imhobH6sQV` | <img src="docs/repo/support/usdt-trc20.png" width="140" alt="USDT TRC20 QR"> |
| **TON, and USDT on TON** | `UQA2yAApJyyZ_vOYwrM6E0K2hwmn_35Y7m68UbCN6Y_A1L6x` | <img src="docs/repo/support/ton.png" width="140" alt="TON QR"> |

Check the network before you send: USDT sent to the TRON address over another network (ERC20, BEP20) is lost.
Проверьте сеть перед отправкой: USDT, отправленные на адрес TRON через другую сеть (ERC20, BEP20), пропадут.

Thank you! Спасибо!

## License

Apache License 2.0, except `apps/Z9xAirPlay` (GPL-3.0-only) and the third-party submodules under `apps/third_party` (their own licenses). See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Lumen OS is an independent project by **kmuradoff**. It is not affiliated with, endorsed by or sponsored by XGIMI, Google, MediaTek or the LineageOS project. "XGIMI", "Android", "Google" and "LineageOS" are trademarks of their respective owners.
