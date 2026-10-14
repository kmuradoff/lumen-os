# Lumen OS installer

Installs Lumen OS from a computer on an XGIMI Z9X (G0082, XGIMI firmware V6.15.58, V6.15.62 or V6.15.19) or an
XGIMI Play 6 (G0058, XGIMI firmware V1.7.116). Follow the install guide, it has every step in order:
[English](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/en.md),
[Русский](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/ru.md); the XGIMI Play 6 has its own
section there ([English](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/en.md#play6),
[Русский](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/ru.md#play6)).

## Files

| File | What it is |
|---|---|
| `lumen-install.sh` | the installer for macOS and Linux (bash 3.2 or newer) |
| `lumen-install.cmd`, `lumen-install.ps1` | the installer for Windows 10/11; beta, not tested on Windows yet |
| `release.conf` | properties of the image this installer belongs to (`DEVICE=play6` in a Play 6 download; none: the Z9X) |
| `lib/devices.conf` | what each projector is: model code, board, firmware versions, image name, certificate (data only; what the installer may write stays in the scripts) |
| `lib/blobs_allow.txt` | checksums of the nine MediaTek and XGIMI codec and audio files the `blobs` step accepts (hashes only, no files) |
| `lib/gapps_allow.txt` | the one MindTheGapps zip the `gapps` step accepts, its official URL, and the files it installs from it (hashes only) |
| `lib/gapps_fill.sh` | runs on the projector during `gapps` and fills the `z9x_gapps` partition |
| `certs/ota.x509.pem` | the Lumen OS certificate that checks `SHA256SUMS.sig` (XGIMI Z9X) |
| `certs/play6/ota.x509.pem` | the same for the XGIMI Play 6 (in a Play 6 download) |

Put `lumen-os-<version>-nogms-system.img` (Play 6: `lumen-os-play6-<version>-nogms-system.img`),
`SHA256SUMS` and `SHA256SUMS.sig` from the same release next to `lumen-install.sh`. The installer refuses an
image of the other projector.

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

XGIMI Play 6 (it has no USB debugging: `--ip ADDRESS` is its Wi-Fi address, saved after the first run):

| Command | What it does | Writes to the projector |
|---|---|---|
| none | runs `check`, `backup` (asked) and `flash` in order and resumes where it stopped | yes |
| `check` | connects over Wi-Fi, checks the computer, projector, firmware, and the image's release version (from its name, against `release.conf`), signature and checksum | no |
| `backup` | as for the Z9X, over Wi-Fi, to `~/Lumen-backup/play6-<date>` | no |
| `flash` | asks for the A-to-A cable, writes the system in fastboot mode, waits for the first start of Lumen OS, then asks Android for its factory reset, which erases all data | yes, erases data |
| `flash --keep-data` | repair: writes the system again and keeps the data | yes |
| `verify` | later, after joining Wi-Fi and turning debugging on again: checks the clean install | only if you agree to a wipe |
| `rescue` | the projector waits in fastboot mode on the cable: writes the system again, keeps the data (Lumen OS only: a projector this computer last saw on stock is refused) | yes |
| `stick DIR` | checks a stock-restore USB stick: MBR, one FAT32 partition, the firmware file, an empty `factory.txt` | no (nothing on the computer either) |
| `status`, `reset` | as above; `--device play6 reset` forgets only the Play 6 | no |

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
| `--ip ADDRESS[:PORT]` | `-Ip ADDRESS[:PORT]` | the XGIMI Play 6's Wi-Fi address (port 5555 if not given) |
| `--device z9x\|play6` | `-Device z9x\|play6` | which projector, when the installer cannot tell by itself |
| `stick DIR` | `stick -Path E:\` | the stick to check |

## What it writes

Only `system`, `z9x_blobs` and, with `gapps`, `z9x_gapps` of the current slot. It formats `userdata`,
`metadata` and `cache`, and deletes XGIMI's unused `product` and `system_ext` of the current slot and
leftover `*-cow` partitions of unfinished updates. `rescue` formats nothing.

It never touches vbmeta, boot, vendor, dtbo, mboot, persist, xgimi*, tvconfig, misc, frp or the other
slot, and never runs `fastboot -w` or `fastboot erase`, which hang the Z9X at boot. Every partition
write goes through an allow-list in the script.

On the XGIMI Play 6 it writes `system` and nothing else: no create, resize, delete, format, erase or
`-w` of its own (`fastboot flash system` itself fits the logical `system` partition in `super` to the image, as
every fastboot write of a logical partition does). The data is erased by Android's own factory reset, which the installer asks for as root over adb on
the first start of Lumen OS and which XGIMI's recovery carries out, as Settings > Reset does. Vendor,
boot, recovery, MBOOT, the env partition, calibration and factory data are never written.

## For developers

Mock tests: `tools/ota/test/installer_rescue.sh`, `installer_blobs.sh`, `installer_edition.sh`,
`installer_gapps.sh`, `installer_profiles.sh` (lib/devices.conf, and the Z9X command logs compared byte for
byte with `tools/ota/test/golden/installer_z9x/`) and `installer_play6.sh` (a fake Play 6 over Wi-Fi and in
fastbootd). The `--blobs=embedded` and `--unsigned-image` options are only for maintainers'
own test builds, see `docs/dev/release.md`.
