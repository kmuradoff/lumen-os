# Lumen OS installer

Installs Lumen OS from a computer on an XGIMI Z9X (G0082, XGIMI firmware V6.15.58, V6.15.62 or V6.15.19) or an
XGIMI Play 6 (G0058, XGIMI firmware V1.7.116). Follow the install guide of your projector, it has every step in order. XGIMI Z9X:
[English](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/z9x/en.md),
[Русский](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/z9x/ru.md). XGIMI Play 6:
[English](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/play6/en.md),
[Русский](https://github.com/kmuradoff/lumen-os/blob/main/docs/install/play6/ru.md).

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
| `lib/gapps_images.txt` | the checksum and size of the Google services image `--gapps-zip` builds from that zip (a hash, no Google file); the installer writes only exactly that image |
| `lib/erofs_utils.txt` | the checksums of the erofs-utils programs in `tools/erofs-utils` (the installer runs nothing else) |
| `tools/erofs-utils/<platform>/` | `mkfs.erofs`, `fsck.erofs`, `dump.erofs` of erofs-utils 1.9.4 (GPL-2.0, source and licenses: `SOURCE`, `NOTICE`, `COPYING`, `LICENSES/` there), built by Lumen OS for macOS (universal) and Linux (x86-64, ARM64); they build the Google services image on your computer |
| `lib/play6/gapps_allow.txt` | XGIMI Play 6: the one MindTheGapps zip (32-bit Android 14 TV) its `gapps` step accepts, its official URL, and the files it installs from it (hashes only) |
| `lib/play6/gapps_fill.sh`, `lib/play6/lumen-gapps.xml` | XGIMI Play 6: runs on the projector during `gapps` and formats and fills the `lumen_gapps` partition; the Lumen settings it adds for the Google apps |
| `certs/ota.x509.pem` | the Lumen OS certificate that checks `SHA256SUMS.sig` (XGIMI Z9X) |
| `certs/play6/ota.x509.pem` | the same for the XGIMI Play 6 (in a Play 6 download) |

Put `lumen-os-<version>-nogms-system.img` (Play 6: `lumen-os-play6-<version>-nogms-system.img`),
`SHA256SUMS` and `SHA256SUMS.sig` from the same release next to `lumen-install.sh`. The installer refuses an
image of the other projector.

## adb and fastboot

The installer never uses an adb or fastboot installed on the computer. On the first run it downloads Google's
Android SDK Platform-Tools 35.0.2 from dl.google.com (`platform-tools_r35.0.2-<darwin|linux|win>.zip`), checks
the sha256 written in the scripts, unpacks it into `tools/platform-tools` next to itself and checks those files
again on every later run. The zip is not part of the download because Google's license does not allow passing it
on. Its adb server runs on port 5038 (adb's usual one is 5037), so adb servers of other programs (Android Studio,
phone managers, scrcpy) cannot get in the way; only that server is stopped at the end. Every adb and fastboot
call has a time limit; a call that hangs stops the installer with a message. Offline: put the zip into `tools/`,
or pass `--platform-tools DIR` (`-PlatformTools DIR`), a folder with adb and fastboot 35 or newer (and
`mke2fs`, `make_f2fs` for the Z9X wipe). Linux on ARM: Google's zip is x86-64 only, use `--platform-tools`.
On Windows the installer also warns when it runs from a USB stick or network drive, and when the projector does
not show up in fastboot mode it looks for a USB device without a driver and explains the Google USB Driver.

## erofs-utils (one-pass install with Google services)

`--gapps-zip` builds the Google services partition on the computer before the projector restarts: the installer
writes a tar of exactly the listed files of your MindTheGapps zip (each checked by sha256) with owner root and
the SELinux label on every file, and `mkfs.erofs` turns it into an erofs image. The image is the same bit for bit
on every computer, so the installer writes it only when its sha256 is the one in `lib/gapps_images.txt`. The
programs come with the installer (`tools/erofs-utils`, checked against `lib/erofs_utils.txt`, run from a copy in
`~/.lumen-installer`); `--erofs-utils DIR` (`-ErofsUtils DIR`) uses erofs-utils 1.9.4 from DIR instead. There is
no Windows build yet: on Windows `-GappsZip` stops before anything is written (install without it, then
`gapps -Zip FILE`).

## Commands

`bash lumen-install.sh [options] [command]`. On Windows: `lumen-install.cmd [command] [options]`.

| Command | What it does | Writes to the projector |
|---|---|---|
| none | runs `check`, `backup`, `blobs`, `vbmeta`, `flash` and `verify` in order and resumes where it stopped | yes |
| none, with `--gapps-zip FILE` | Lumen OS and Google services in one run (XGIMI Z9X): checks the zip, builds the Google services partition on the computer (erofs, `tools/erofs-utils`), then ONE fastbootd visit writes the system, `z9x_gapps_<slot>` and wipes the data (`format`); no USB debugging or Rooted debugging needed after the vbmeta step. macOS and Linux (x86-64, ARM64) only for now | yes, erases data |
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
| none, with `--gapps-zip FILE` | Lumen OS and Google services in one run: checks the zip, writes the system, creates `lumen_gapps` and leaves the erase request in the same fastboot session; after the light first start on clean data the user turns debugging on again, the installer adds the Google apps as root, then Android's factory reset of the clean system; resumes where it stopped | yes, erases data |
| `check` | connects over Wi-Fi, checks the computer, projector, firmware, and the image's release version (from its name, against `release.conf`), signature and checksum; the pre-checks: Wi-Fi and Bluetooth chip, serial batch, battery and power adapter (refuses below 50 % or on battery), what is in the USB port; all logged in `~/.lumen-installer/play6/install.log` | no |
| `backup` | as for the Z9X, over Wi-Fi, to `~/Lumen-backup/play6-<date>` | no |
| `flash` | asks for the A-to-A cable, writes the system and the erase request for XGIMI's recovery in one fastboot session; the recovery erases all data before the first start of Lumen OS (if the request is refused or not carried out: the first start on the old data, then Android's factory reset) | yes, erases data |
| `flash --keep-data` | repair: writes the system again and keeps the data | yes |
| `verify` | later, after joining Wi-Fi and turning debugging on again: checks the clean install | only if you agree to a wipe |
| `rescue` | the projector waits in fastboot mode on the cable: writes the system again, keeps the data (Lumen OS only: a projector this computer last saw on stock is refused) | yes |
| `stick DIR` | checks a stock-restore USB stick: MBR, one FAT32 partition, the firmware file, an empty `factory.txt` | no (nothing on the computer either) |
| `gapps --zip FILE` | adds Google services from your own MindTheGapps download: creates `lumen_gapps` in fastboot mode (A-to-A cable), fills it over Wi-Fi, then Android's factory reset (`--keep-data`: no reset) | yes, erases data |
| `gapps --remove` | removes the Google services you added (fastboot mode, A-to-A cable), with the same factory reset | yes, erases data |
| `gapps` | shows the state of the Google services you added | no |
| `gsf` | shows the GSF ID to register at google.com/android/uncertified, when Google services are installed | no |
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
| `--gapps-zip FILE` | `-GappsZip FILE` | install Lumen OS and Google services in one run, with one wipe; not with a command name, `--keep-data`, `--wipe=factory-reset` or an image with Google built in |
| `--ip ADDRESS[:PORT]` | `-Ip ADDRESS[:PORT]` | the XGIMI Play 6's Wi-Fi address (port 5555 if not given) |
| `--device z9x\|play6` | `-Device z9x\|play6` | which projector, when the installer cannot tell by itself |
| `--platform-tools DIR` | `-PlatformTools DIR` | adb and fastboot from DIR instead of the installer's own download (offline computers) |
| `stick DIR` | `stick -Path E:\` | the stick to check |

## What it writes

Only `system`, `z9x_blobs` and, with `gapps`, `z9x_gapps` of the current slot. It formats `userdata`,
`metadata` and `cache`, and deletes XGIMI's unused `product` and `system_ext` of the current slot and
leftover `*-cow` partitions of unfinished updates. `rescue` formats nothing.

It never touches vbmeta, boot, vendor, dtbo, mboot, persist, xgimi*, tvconfig, misc, frp or the other
slot, and never runs `fastboot -w` or `fastboot erase`, which hang the Z9X at boot. Every partition
write goes through an allow-list in the script.

On the XGIMI Play 6 it writes `system` and the first 2 KiB of `misc` and nothing else: no create, resize, delete,
format, erase or `-w` of its own (`fastboot flash system` itself fits the logical `system` partition in `super` to
the image, as every fastboot write of a logical partition does). The 2 KiB are the erase request for XGIMI's
recovery (`boot-recovery`, `--wipe_data`), byte for byte what Android's factory reset writes there; the installer
checks them against a pinned sha256 before the write and never writes them with `--keep-data`. XGIMI's recovery
then erases userdata and cache before the first start of Lumen OS, as Settings > Reset does, and clears the
request. If fastboot mode refuses it or the recovery does not carry it out, the data is erased the earlier way:
Android's own factory reset, asked for as root over adb on the first start of Lumen OS. Vendor, boot, recovery,
MBOOT, the env partition (MPOOL), calibration and factory data are never written.

## For developers

Mock tests: `tools/ota/test/installer_rescue.sh`, `installer_blobs.sh`, `installer_edition.sh`,
`installer_gapps.sh`, `installer_profiles.sh` (lib/devices.conf, and the Z9X command logs compared byte for
byte with `tools/ota/test/golden/installer_z9x/`), `installer_play6.sh` (a fake Play 6 over Wi-Fi and in
fastbootd), `installer_play6_gapps.sh`, and the PowerShell host tests `installer_ps1_gapps.sh` and
`installer_ps1_play6.sh` (the Play 6 erase request and pre-checks give the same results in both scripts). The `--blobs=embedded` and `--unsigned-image` options are only for maintainers'
own test builds, see `docs/dev/release.md`.
