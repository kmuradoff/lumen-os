# Lumen OS installer

Installs Lumen OS on an XGIMI Z9X (G0082, XGIMI firmware V6.15.58 or V6.15.19) from a computer.
Follow the install guide, it has every step in order:
[English](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/en.md),
[Русский](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/ru.md).

## Files

| File | What it is |
|---|---|
| `lumen-install.sh` | the installer for macOS and Linux (bash 3.2 or newer) |
| `lumen-install.cmd`, `lumen-install.ps1` | the installer for Windows 10/11; beta, not tested on Windows yet |
| `release.conf` | properties of the image this installer belongs to |
| `lib/blobs_allow.txt` | checksums of the nine MediaTek and XGIMI codec and audio files the `blobs` step accepts (hashes only, no files) |
| `lib/gapps_allow.txt` | the one MindTheGapps zip the `gapps` step accepts, its official URL, and the files it installs from it (hashes only) |
| `lib/gapps_fill.sh` | runs on the projector during `gapps` and fills the `z9x_gapps` partition |
| `certs/ota.x509.pem` | the Lumen OS certificate that checks `SHA256SUMS.sig` |

Put `lumen-os-<version>-nogms-system.img`, `SHA256SUMS` and `SHA256SUMS.sig` from the same release next
to `lumen-install.sh`.

## Commands

`bash lumen-install.sh [options] [command]`. On Windows: `lumen-install.cmd [command] [options]`.

| Command | What it does | Writes to the projector |
|---|---|---|
| none | runs `check`, `backup`, `blobs`, `vbmeta`, `flash` and `verify` in order and resumes where it stopped | yes |
| `check` | checks the computer, cable, projector, firmware, the image's signature and checksum, and the vbmeta step | no |
| `backup` | copies your apps (APK files), photos, videos, downloads and settings lists to `~/Lumen-backup` | no |
| `blobs` | copies the codec and audio files from your projector, checks them and builds the `z9x_blobs` image | no |
| `vbmeta` | explains the manual vbmeta step and asks whether it is done; it never runs a vbmeta command | no |
| `flash` | writes the system and `z9x_blobs`, then wipes all data | yes, erases data |
| `flash --keep-data` | repair: writes the system again and keeps the data | yes |
| `verify` | after the first start, checks the system over USB | no |
| `rescue` | for Lumen OS that no longer starts: waits for fastboot mode and writes the system again, keeps the data | yes |
| `gapps --zip FILE` | adds Google services from your own MindTheGapps download, then wipes the data (`--keep-data`: no wipe) | yes |
| `gapps --remove` | removes the Google services you added, with the same wipe | yes |
| `gapps` | shows the state of the Google services you added and can turn them back on | only if you agree |
| `gsf` | shows the GSF ID to register at google.com/android/uncertified, when Google services are installed | no |
| `status`, `reset` | shows or forgets the installer's saved progress | no |

Options:

| macOS, Linux | Windows | Meaning |
|---|---|---|
| `--image FILE` | `-Image FILE` | the system image to use; needed when the folder holds more than one |
| `--serial SERIAL` | `-Serial SERIAL` | the projector's serial when more than one device is connected |
| `--lang en\|ru` | `-Lang en\|ru` | language of the messages |
| `--keep-data` | `-KeepData` | no data wipe (`flash`, `gapps`) |
| `--wipe=factory-reset` | `-Wipe factory-reset` | leave the wipe to a factory reset on the projector instead of `format` |
| `--no-blobs` | `-NoBlobs` | install without the codec and audio files: no sound, no protected video |
| `--zip FILE`, `--remove` | `-Zip FILE`, `-Remove` | for `gapps` |

## What it writes

Only `system`, `z9x_blobs` and, with `gapps`, `z9x_gapps` of the current slot. It formats `userdata`,
`metadata` and `cache`, and deletes XGIMI's unused `product` and `system_ext` of the current slot and
leftover `*-cow` partitions of unfinished updates. `rescue` formats nothing.

It never touches vbmeta, boot, vendor, dtbo, mboot, persist, xgimi*, tvconfig, misc, frp or the other
slot, and never runs `fastboot -w` or `fastboot erase`, which hang the Z9X at boot. Every partition
write goes through an allow-list in the script.

## For developers

Mock tests: `tools/ota/test/installer_rescue.sh`, `installer_blobs.sh`, `installer_edition.sh` and
`installer_gapps.sh`. The `--blobs=embedded` and `--unsigned-image` options are only for maintainers'
own test builds, see `docs/dev/release.md`.
