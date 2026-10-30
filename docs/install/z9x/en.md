# Installing Lumen OS on the XGIMI Z9X

[Русская версия](ru.md)

> [!NOTE]
> This guide is for the XGIMI Z9X. The XGIMI Play 6 has [its own guide](../play6/en.md): it installs
> differently (over Wi-Fi, with a cable only for the write) and needs its own release files.

This guide covers Lumen OS 1.0.7 without Google on the XGIMI Z9X (model code G0082) with XGIMI
firmware V6.15.58, V6.15.62 or V6.15.19. Lumen OS is Android TV 14, built on LineageOS 21. It replaces only the
system partition. The XGIMI firmware and calibration are not touched, and focus, keystone and the lamp
work as on stock.

The install takes about 40 minutes and **erases all data on the projector**. You type a few commands in a
terminal and do one step in fastboot yourself, without the installer. You can go back to the stock
firmware from a USB stick, see [Back to stock](#stock).

> [!CAUTION]
> Read the whole guide once, then do the steps in order. Do not mix in commands from other guides:
> some of them can brick a Z9X. If anything is unclear, stop and ask in
> [GitHub issues](https://github.com/kmuradoff/lumen-os/issues).
>
> Never do these on a Z9X:
> - `fastboot -w` or `fastboot erase`. The projector then hangs at boot and only the USB-stick stock
>   restore brings it back. The installer wipes data with `format`, which is safe.
> - `adb reboot bootloader`. The projector just switches off. Use `adb reboot fastboot`.
> - Flashing `boot`, `vendor`, `dtbo` or any other XGIMI partition.
> - `fastboot flashing lock`. With a locked bootloader Lumen OS will not start.

<a name="choose"></a>

## Three ways to install

| | What you get | Data wiped | How |
|---|---|---|---|
| 1 | **Clean Lumen OS without Google** | once | `bash lumen-install.sh`, [step 6](#flash) |
| 2 | **Lumen OS and Google services in one command** | once | `bash lumen-install.sh --gapps-zip ...`, [Lumen OS and Google services in one command](#one-pass) |
| 3 | **Google services later**, on an installed Lumen OS | once more | the `gapps` step, [Adding Google services](#google) |

Steps 1 to 5 are the same for 1 and 2. If you already know you want Google Play, take 2: you set the
projector up only once.

## What is missing without Google

The published image is the edition without Google. It has no Google Play, no Chromecast built-in, no
Google voice search, text-to-speech or TalkBack, and no remote in the Google TV phone app. Apps that need
Google Play services will not start. AirPlay, HDMI, the projector settings, Lumen OS updates and the
screensaver all work. You can add Google services yourself from your own download: together with the
install ([way 2](#one-pass)) or later ([way 3](#google)).

## What you need

- A Z9X on XGIMI firmware V6.15.58, V6.15.62 or V6.15.19.
- Everything works on stock: picture, autofocus, sound, HDMI. If something is already broken, sort that
  out first, or later you will not know what caused it.
- A computer with macOS or Linux. There is a Windows 10/11 installer, but it has not been tested yet.
- Internet access on the first run. The installer then downloads Google's Android SDK Platform-Tools
  (adb and fastboot, about 10 MB) into `tools/platform-tools` next to itself, checks the file's sha256 and
  from then on uses only that copy, never an adb or fastboot you installed yourself. Its adb runs on its own
  port (5038), so Android Studio, phone apps or scrcpy cannot get in its way. Every adb and fastboot step has
  a time limit: if one hangs, the installer stops with a message instead of waiting. Without internet: put
  [platform-tools_r35.0.2-darwin.zip](https://dl.google.com/android/repository/platform-tools_r35.0.2-darwin.zip)
  (Linux: `-linux.zip`, Windows: `-win.zip`) into the installer's `tools` folder, or run it with
  `--platform-tools DIR` (Windows: `-PlatformTools DIR`), a folder with adb and fastboot 35 or newer.
- A USB A-to-A cable (plain USB-A on both ends). It goes into the projector's USB 2.0 port.
- 4 GB of free space on the computer.
- A USB stick, in case you need the stock restore.

On Windows, run `lumen-install.cmd` instead of `bash lumen-install.sh`. Options are spelled differently
there and go after the command: `-Lang en`, `-KeepData`, `-NoBlobs`, `-Zip`, `-Remove`, `-GappsZip`. Unpack everything
into a folder whose path has only Latin letters on the computer's own disk, such as `C:\lumen`, not on a USB
stick or network drive: writing from there can be very slow or hang (the installer warns about it). If the
projector does not show up in fastboot, install the [Google USB Driver](https://developer.android.com/studio/run/win-usb):
the installer looks for a USB device without a driver and says how to install it.

## 1. Prepare the projector (10 min)

1. On stock, open Settings > About and look at the firmware version. You need V6.15.58, V6.15.62 or
   V6.15.19. If yours is older, connect Wi-Fi and let stock update itself. If it is newer, Lumen OS
   cannot be installed yet: the installer refuses that firmware.
2. Turn Wi-Fi off and keep it off until the install is done. Stock installs XGIMI updates into the other
   slot on its own, and the installer only knows the two versions above.
3. Remove your Google account in Settings > Accounts. Otherwise Factory Reset Protection may ask for it
   if you add Google services later.
4. Turn on USB debugging. XGIMI hides the Developer options menu, so install SystemUI Tuner from a USB
   stick and turn on debugging there. If the stock file manager refuses the `.apk`, rename it to
   `.apk1`. The [4PDA thread](https://4pda.to/forum/index.php?showtopic=1121837) (in Russian) shows
   this in detail.
5. Restart the projector. USB debugging only works after a restart.
6. Connect the A-to-A cable to the projector's USB 2.0 port and to the computer.

## 2. Download the files (5 min)

1. Open the [Releases page](https://github.com/kmuradoff/lumen-os/releases) on GitHub. Download only
   from there. We do not check or support copies on forums or file hosts.
2. From the latest release (now 1.0.7), download four files: `lumen-os-1.0.7-nogms-system.img`,
   `SHA256SUMS`, `SHA256SUMS.sig` and `lumen-os-1.0.7-installer.zip`.
3. Unpack the installer zip. Put the image and both `SHA256SUMS` files next to `lumen-install.sh`.
   Remove any older `lumen-os-*-system.img` from that folder.
4. Open a terminal and go to that folder:

   ```
   cd ~/lumen
   ```

   Replace `~/lumen` with your folder.

The installer checks the image's signature and checksum itself in step 4. If either does not match, it
stops and writes nothing.

<details>
<summary>Check the signature by hand</summary>

```
openssl x509 -in certs/ota.x509.pem -pubkey -noout > lumen.pub
```

```
openssl dgst -sha256 -verify lumen.pub -signature SHA256SUMS.sig SHA256SUMS
```

The second command must print `Verified OK`. This command shows the certificate fingerprint:

```
openssl x509 -in certs/ota.x509.pem -noout -fingerprint -sha256
```

It must match the `ota` line in the repository's `keys/public/FINGERPRINTS.txt`:
`A7:E5:A8:CC:8C:14:4E:35:3A:91:27:CF:26:A9:50:25:7E:EE:DD:D4:3C:44:20:D6:15:7F:E4:F9:9B:8C:73:34`.

</details>

<a name="vbmeta"></a>

## 3. Turn off vbmeta verification (5 min)

You do this step yourself. The installer never runs a vbmeta command.

Do items 3, 4 and 5 back to back: the Z9X drops out of fastboot after about 2 minutes. If you run out of
time, start again from item 3.

1. From [Google's GSI page](https://developer.android.com/topic/generic-system-image/releases), download
   any arm64 zip, for example `aosp_arm64`, and take `vbmeta.img` out of it. Put it in the installer
   folder.
2. Check the connection to the projector:

   ```
   adb devices
   ```

   The projector asks "Allow USB debugging?". Tick "Always allow from this computer" and press OK. Run
   the command again: you should see one line that ends in `device`.
3. Put the projector into fastboot mode:

   ```
   adb reboot fastboot
   ```

   After 20 to 30 seconds, check:

   ```
   fastboot devices
   ```

   You should see one line that ends in `fastboot`.
4. Turn off verification on both slots at once:

   ```
   fastboot --disable-verity --disable-verification --slot=all flash vbmeta vbmeta.img
   ```

   The output must show `Writing 'vbmeta_a'` and `Writing 'vbmeta_b'`, both with `OKAY`.
5. Restart the projector:

   ```
   fastboot reboot
   ```

   The projector starts as usual.

Lumen OS is not signed by XGIMI, so the projector must stop verifying the system partition. This is the
standard step for any GSI; Google describes it in
[Requirements for flashing GSIs](https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis).
Both slots matter because Lumen OS installs its updates into the other slot.

If you came here from the "One-time step needed for updates" screen in Lumen OS, turn on USB debugging
(see [step 8](#verify)), connect the cable and do items 1 to 5. Your data is not erased.

## 4. Run the check (2 min, writes nothing)

```
bash lumen-install.sh check
```

Windows: `lumen-install.cmd check`

The installer checks the model and XGIMI firmware, the tools on the computer, the image's signature and
checksum, and that the vbmeta step worked. The image check takes about a minute. At the end you should
see lines like these (the slot letter may differ):

```
✓ edition: Lumen OS without Google
✓ vbmeta: verification is off on slot b (no system-verity)
✓ check passed; nothing was written
```

If the installer prints `STOP`, look up its message in [Something went wrong](#problems).

Or keep it simple: run `bash lumen-install.sh` without `check`. The installer then goes through steps 4
to 8 by itself and, if it stops, continues from the same place next time. Then you do not need to run
the commands of steps 5 to 8 yourself.

## 5. Optional: back up apps and files (5 min)

```
bash lumen-install.sh backup
```

The installer copies your installed apps (APK files), the Download, Movies, Music, Pictures, DCIM and
Documents folders and lists of your settings to `~/Lumen-backup/<date>/`. App data (account sign-ins,
watch progress) cannot be saved without root. You can install the saved APKs again from a USB stick.

<a name="flash"></a>

## 6. Install Lumen OS (5 min, erases all data)

Pick one of the two commands:

| 1. Clean Lumen OS without Google | 2. Lumen OS and Google services in one command |
|---|---|
| `bash lumen-install.sh flash` | `bash lumen-install.sh --gapps-zip MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip` |
| Windows: `lumen-install.cmd flash` | Windows: `lumen-install.cmd -GappsZip MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip` |
| about 5 minutes, described below | about 6 minutes, one visit to fastboot mode, see [Lumen OS and Google services in one command](#one-pass) |

To add Google services later to a Lumen OS that is already installed, see
[Adding Google services](#google).

For the clean install:

1. The installer checks the image again and asks whether the vbmeta step is done. Answer `yes`.
2. It copies the codec and audio files from the projector (see below) and keeps a copy on the computer.
3. It shows what will be erased: apps, accounts, settings and files. The XGIMI calibration, the other
   slot, vendor and boot are not touched. Type `ERASE` to continue.
4. The rest takes about 2 minutes and needs nothing from you: the installer restarts into fastboot,
   writes the system, wipes the data and restarts the projector. Keep the cable in until you see
   "written. First start takes 2-4 minutes; the setup screen appears on the projector."

### Sound and protected video

We are not allowed to redistribute MediaTek's and XGIMI's codec and audio files. So the installer takes
nine of them from your own projector, checks each one against a list of checksums and writes them into
a small partition, `z9x_blobs`. Without them there is no sound and no protected video, such as
Kinopoisk HD. A copy stays on the computer in `~/Lumen-backup/blobs-<serial>.img`. Keep it, you will
need it for a repair.

If the installer says a file "does not match the allow-list", it does not know your firmware's files.
You can still install without them by adding `--no-blobs`, but there will be no sound and no protected
video. Only do that to look around. Better open a
[GitHub issue](https://github.com/kmuradoff/lumen-os/issues) first and attach the installer output.

<a name="one-pass"></a>

### Lumen OS and Google services in one command

> [!NOTE]
> New: this way has not been tried on a projector yet. If something goes wrong, the installer says how to
> go on. The proven way is way 1, then way 3.

This is way 2: Lumen OS and Google Play, Google Play services, Google voice search and text-to-speech,
TalkBack and the remote in the Google TV phone app, with one data wipe in total. What you get and what
does not work (Chromecast) is the same as in [Adding Google services](#google).

The whole write happens in ONE visit to fastboot mode: the system, the Google services and the wipe. After
the vbmeta step you do not need to turn anything on in Lumen OS: no USB debugging, no Rooted debugging.
The installer builds the Google services partition on your computer before it restarts the projector.

The order is:

1. On the stock system: turn on USB debugging (step 1) and download the files
   (step 2). Also download the MindTheGapps file named in [Adding Google services](#google),
   item 1, and put it in the installer folder, next to `lumen-install.sh`. It must be exactly that file,
   the installer refuses any other.
2. Fastboot mode, the vbmeta step, back to the stock system once: [step 3](#vbmeta), items 1 to 5.
3. One command, the projector on the stock system with the cable in:

   ```
   bash lumen-install.sh --gapps-zip MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip
   ```

   Windows: `lumen-install.cmd -GappsZip MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip`
   (not yet: see the note below).

   Do not add `flash` or another command name, and do not add `--keep-data` or `--wipe=factory-reset`:
   the installer refuses them with `--gapps-zip`. It also refuses an image that already has Google built
   in.

What the command does:

1. It checks the MindTheGapps file, the computer, the projector, the image and the vbmeta step (as
   step 4) before it writes anything.
2. It builds the Google services partition on the computer: it takes exactly the listed files out of the
   MindTheGapps file, checks each one, and makes an image whose checksum must match the one the installer
   carries. This takes 10 to 30 seconds and needs about 1 GB of free space on the computer for a moment.
3. It offers the backup (step 5), copies the codec and audio files and shows what will be
   erased. Type `ERASE`.
4. One visit to fastboot mode, about 2 minutes, nothing to do for you: the system, the codec and audio
   files, the Google services partition `z9x_gapps_<slot>`, then the wipe with `format`, the same wipe as
   a clean install. That is the only wipe.
5. The projector restarts. The first start is clean and the setup has a Google sign-in step. If Google
   refuses the sign-in, register the projector, see [GSF ID](#gsf).

If the Google services do not fit into the system storage or cannot be written, the installer removes
that partition, wipes as usual and finishes without Google. Add them later with the `gapps` step, see
[Adding Google services](#google).

If fastboot mode ends in the middle (the Z9X leaves it by itself after about 2 minutes, or the cable came
out), nothing is erased: the projector starts its old system on the old data, USB debugging is still on.
Run the same command again: it writes everything again in one visit. The Google services image is built
only once.

> [!NOTE]
> The one-command install needs a Mac (Apple silicon or Intel) or a Linux computer (x86-64 or ARM64): the
> installer carries the program that builds the Google services partition (erofs-utils) only for them so
> far. On Windows, `-GappsZip` stops before anything is written. There, use way 1 and then way 3, or do
> the install from a Mac or a Linux computer.

## 7. First start (5 min)

The first start takes 2 to 4 minutes. Then the setup asks, in this order: the remote (confirm it with
OK), the language, Wi-Fi and the picture. There is no Google step, unless you installed with Google
services in one command (way 2). The time zone is set automatically when
there is internet. Lumen Home opens after the setup.

Unplug the cable from the computer. While it is connected, the projector does not really sleep: the
lamp goes off, but the system keeps running.

If there is no setup screen after 10 minutes, see [Something went wrong](#problems).

<a name="verify"></a>

## 8. Optional: verify the install

On the projector, open Settings > Device Preferences > About. It should show Lumen OS 1.0.7.

To check over USB:

1. In the same About screen, press OK on Build 7 times.
2. Open Settings > Device Preferences > Developer options and turn on USB debugging.
3. Connect the cable and allow debugging on the projector.
4. Run:

   ```
   bash lumen-install.sh verify
   ```

The installer shows the version and edition, the signing keys, the projector service, the codec and
audio files and the audio outputs. Lines with `✓` are fine. Send lines with `!` along with your
question. Unplug the cable afterwards.

## 9. Install apps

From a USB stick:

1. Copy `.apk` files to a FAT32 or exFAT stick.
2. Plug the stick into the projector.
3. In Lumen Home, open Files, choose the stick and find the `.apk` file. Since 1.0.4 Files also shows the
   projector's own storage, for example the Download folder.
4. Press OK on the file: the app's name, icon, version and "New" or "Update" appear. Press OK again and
   confirm the install.

Only plain `.apk` files work, not `.apks` or `.xapk`. Updates install the same way. Over the cable, with
USB debugging on, `adb install name.apk` works too.

Lumen OS has no built-in store, but you can install one from a stick: F-Droid, Aurora Store (Google
Play apps without an account) or Aptoide TV. The first time the store installs something, the system
asks for a permission. You can also give it in advance: Settings > Apps > Security & restrictions >
Unknown sources.

The built-in keyboard types English. Lumen Home's search has its own keyboard with Cyrillic layouts.
You can install another keyboard from a stick. For now the mic button opens search with the keyboard.

<a name="google"></a>

## Adding Google services

This is way 3, for a Lumen OS that is already installed. To install Lumen OS and Google services
together, with one wipe, see [Lumen OS and Google services in one command](#one-pass).

We are not allowed to redistribute Google's apps, so you download the file yourself and the installer
adds it with one command. You get Google Play, Google Play services, Google voice search and text-to-speech,
TalkBack and the remote in the Google TV phone app. Chromecast built-in does not work, see
[Chromecast](#chromecast) below. It takes about 5 minutes and
**erases all data**, like the install.

1. Download MindTheGapps for Android 14 TV (223 MB). It must be exactly this file, the installer
   refuses any other:
   [MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip](https://github.com/MindTheGapps/14.0.0-arm64-ATV/releases/download/MindTheGapps-14.0.0-arm64-ATV-20240523_192151/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip).
   Safari on a Mac sometimes unpacks the zip by itself. If that happens, download it with another
   browser: you need the `.zip`.
2. Turn on USB debugging on the projector ([step 8](#verify)) and connect the cable. In the same
   Developer options also turn on "Rooted debugging": this step needs root. If it is off, the installer
   says so and where to turn it on.
3. Run:

   ```
   bash lumen-install.sh gapps --zip ~/Downloads/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip
   ```

   Windows: `lumen-install.cmd gapps -Zip C:\Users\<you>\Downloads\MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip`
4. Type `ERASE`. The installer creates the partition `z9x_gapps` (512 MB), copies the Google apps from
   the file into it, checks each one, wipes the data and restarts the projector.

To keep your data, add `--keep-data` and type `WRITE` instead of `ERASE`. But then the phone remote may
not work properly until a factory reset.

<a name="gsf"></a>

The setup now has a Google sign-in step. Usually the sign-in just works. If Google refuses it, or Play
says the device is not certified, register the projector:

1. Connect the projector to the internet, turn USB debugging on again (the wipe turned it off) and
   connect the cable.
2. Get the GSF ID:

   ```
   bash lumen-install.sh gsf
   ```

3. Register it at [google.com/android/uncertified](https://www.google.com/android/uncertified), wait 10
   to 30 minutes, then sign in from Settings > Accounts.

Lumen OS updates and factory resets keep the Google services. System, vendor, boot and the calibration do
not change; only the `z9x_gapps` partition is added.

To remove Google services (this also erases data; with `--keep-data` it does not):

```
bash lumen-install.sh gapps --remove
```

This also works when the projector sits in fastboot mode after failed starts. If Lumen OS fails to start
twice in a row with Google services, the next start goes without them and they stay off. To check them
and turn them back on: `bash lumen-install.sh gapps`.

<a name="updates"></a>

## Chromecast

Chromecast built-in does not work on Lumen OS, with or without the Google services. Google gives the Cast
device certificate only to models it has certified, and the XGIMI Z9X never had Chromecast (the Chinese
firmware did not either): Google's server answers 403 to the projector's certificate request. Phones and
Chrome then see the projector but cannot cast to it ("Available for specific video sites"). Lumen OS
therefore switches Google's Cast receiver off once after the Google services are added; you can turn it
back on in Settings > Apps if you want to try yourself.

What works instead:
- AirPlay from an iPhone, iPad or Mac: Control Center > Screen Mirroring > the projector, or the AirPlay
  button in a video app.
- DLNA ("send to TV", from the next Lumen Play version): Lumen Play shows up as a TV in BubbleUPnP, Web Video Cast, VLC, Windows "Cast to
  device", many torrent clients and browser extensions.
- YouTube without Cast: in YouTube on the projector open Settings > Link with TV code, then in YouTube on
  the phone Settings > Watch on TV > Enter TV code. After that the phone's cast button finds the projector.

## Updates

Updates come over Wi-Fi: Settings > Device Preferences > About > Lumen OS update. When an update is
downloaded, Lumen Home shows an "Update ready" badge.

- By default an update downloads automatically and installs only after you say yes. You can pick
  "Only tell me" or "Off".
- The update is written to the other slot while you keep watching. Restart when it suits you. Your data,
  the codec files and any Google services you added stay.
- If the new version does not start, the projector goes back to the previous one and shows "The update
  could not start". Once the new version has started, there is no way back over the air.
- Before the first update, Lumen OS asks "Allow the firmware copy?". Choose "Allow and install". The
  XGIMI firmware (boot, bootloader, display and audio firmware, configuration) is copied unchanged into
  the other slot, the same way XGIMI's own updates do it. vbmeta is not written.
- Lumen OS accepts only updates signed with the Lumen OS key, and only for its own edition.
- Offline: put `update-public-nogms.json`, `update-public-nogms.json.sig`, the `…-ota.zip` and the
  `…-payload_metadata.bin` from the release into the root of a USB stick or into a folder `lumen-os` on
  it. Then, on the Lumen OS update screen, choose "Install from a USB stick". This is not the Install
  from USB tile in Lumen Home, which installs apps.

<a name="problems"></a>

## Something went wrong

| Where | What you see | What to do |
|---|---|---|
| check | "exactly one projector must be connected over adb, found 0" | Is USB debugging on, was the projector restarted after that, is the cable in the USB 2.0 port, did you allow the prompt on screen? Nothing was written. |
| check | "this is not an XGIMI Z9X" | The installer works only with the Z9X (G0082). Nothing was written. |
| check | "XGIMI firmware … is not supported" | An older firmware: let stock update itself over Wi-Fi, then run `check` again. A newer one is not supported yet. Nothing was written. |
| check | "the running slot … still verifies the system partition" | The vbmeta step did not work. Repeat [step 3](#vbmeta). Nothing was written. |
| check | "checksum mismatch" or "SHA256SUMS is not signed by Lumen OS" | Download the release files again, from GitHub only. Do not install that image. |
| check | "no SHA256SUMS" or "no SHA256SUMS.sig" | Put both files from the same release next to the image. |
| check | "several images found" | Keep one image in the folder, or name it: `--image FILE`. |
| check | "the file name and the image disagree" | The file was renamed or is damaged. Download it again. |
| check | "make_f2fs/mke2fs missing" or "too old or does not run" | Only with `--platform-tools DIR`: use a whole, fresh Platform-Tools zip. Without that option the installer uses its own copy. |
| any | "… did not answer within N s and was stopped" | Unplug and plug the cable, run the same command again. If it repeats, restart the computer. |
| first run | "the download failed" | No internet: see [What you need](#what-you-need) for the offline way. |
| flash | a file "does not match the allow-list" | The installer does not know your firmware's codec files, see [step 6](#flash). Nothing was written. |
| flash | "the projector did not appear in fastboot within 90 s" | Reconnect the cable and run `flash` again. On Windows, install the Google USB Driver. Nothing was written. |
| flash | "an XGIMI or Lumen update is still being finished" | Let the projector start, wait 10 minutes and try again. Nothing was written. |
| flash | "system was not written" | The old system is still there. Check the cable and try again. |
| flash | "the wipe failed" | Lumen OS starts on the old data. On the projector, do Settings > Device Preferences > Reset > Factory reset. |
| flash | "switching editions needs a full install" | A repair without a wipe cannot switch between the editions with and without Google. Install without `--keep-data`, which erases data. |
| first start | Restarts in a loop, the computer does not see it | The vbmeta step was most likely not done for this slot. Do the [stock restore](#stock) and start again at step 1. If the projector went to fastboot mode by itself, try [Rescue](#rescue) first. |
| later | Lumen OS no longer starts | After 3 failed starts in a row the projector goes to fastboot mode. Run [Rescue](#rescue); your data stays. |
| later | It does not sleep: lamp off, fans running | Unplug the USB cable from the computer. |
| Files | The stick is not listed | The stick must be FAT32 or exFAT. Take it out and plug it in again. |
| Installing an `.apk` | "was not installed" | The file is damaged, or the app is not for Android TV 14 (arm64). |
| an app | Asks for Google Play services | Look for a version without them, or [add Google services](#google). |
| gapps | "is not the MindTheGapps file this installer accepts" | Download exactly the file named above. Nothing was written. |
| gapps | "does not accept this file yet" | Update Lumen OS first. Nothing was written. |
| gapps | "not enough free space in super" | The system is unchanged. Report it with the installer output. |
| gapps | "root debugging is off on the projector" | Settings > Device Preferences > Developer options > Rooted debugging (if Developer options is missing: About > Build, press OK 7 times). Then run the same command again; nothing is erased. |
| `--gapps-zip` | refused together with `--keep-data`, `--wipe=factory-reset` or a command name such as `flash` | The one-command install always wipes the data once and runs as a whole. Run it exactly as in [Lumen OS and Google services in one command](#one-pass). Nothing was written. |
| `--gapps-zip` | refused because the image has Google built in | Use the image without Google from the release. Nothing was written. |
| `--gapps-zip` | "this installer has no erofs-utils for this computer" | The one-command install runs on a Mac or Linux (x86-64, ARM64), not on Windows yet. Use way 1 and then way 3, or another computer. Nothing was written. |
| `--gapps-zip` | "cannot take the Google services in one pass" or "does not accept" | The image is from another release than the installer. Use the image and the installer of the same release. Nothing was written. |
| `--gapps-zip` | "is not the one checked for this release" | The Google services image built on your computer differs from the checked one. Report it with the installer output; install with way 1 and add Google later. Nothing was written. |
| `--gapps-zip` | "fastbootd ended while z9x_gapps... was being written" | Nothing was erased. Wait until the projector has started, then run the same command again. |
| `--gapps-zip` | "not enough free space in super for the Google services" | The install finished without Google. Report it with the installer output. |
| any step | asks whether to finish an unfinished install without Google | An install started with an older installer waits for its Google step. Answer `no` and run the command with `--gapps-zip` again to get Google (now in one visit to fastboot mode), or `yes` to finish without it. |
| updates | "One-time step needed for updates" | The other slot needs the vbmeta step. Do [step 3](#vbmeta) from a computer. |
| updates | "Protected-video components are missing" | Run `bash lumen-install.sh blobs`, then `bash lumen-install.sh flash --keep-data`. |
| updates | "This update is for another Lumen OS edition" | The stick holds files for another edition. You need the `update-public-nogms.json` files. |

The installer keeps its progress in `~/.lumen-installer/`. `bash lumen-install.sh status` shows it and
`bash lumen-install.sh reset` forgets it. Neither touches the projector.

### If nothing helped

Open an [issue on GitHub](https://github.com/kmuradoff/lumen-os/issues) and attach:

- the XGIMI firmware version, and the Lumen OS version if it is installed;
- what you did and at which step it stopped;
- the full terminal text, not a screenshot;
- the output of `bash lumen-install.sh status`.

<a name="rescue"></a>

## Rescue (keeps your data)

Since 1.0.1, Lumen OS counts failed starts. If the system fails to finish starting 3 times in a row,
the projector goes to fastboot mode by itself. The picture may stay dark. From fastboot mode the
installer writes the system again, and your data stays.

1. Connect the A-to-A cable to the projector's USB 2.0 port.
2. Make sure the installer folder holds the image without Google, the same version or newer, with
   `SHA256SUMS` and `SHA256SUMS.sig`. Run:

   ```
   bash lumen-install.sh rescue
   ```

   Windows: `lumen-install.cmd rescue`
3. Type `WRITE`. The installer waits up to 15 minutes for the projector in fastboot mode. If the
   projector left fastboot and failed to start again, it comes back there after 3 more tries.
4. The installer does the rest by itself. If an unfinished update is left in that slot, it finishes or
   cancels it. Then it writes the system and your codec files from `~/Lumen-backup/blobs-<serial>.img`
   and restarts the projector. Your data is not erased.

If an update had to be cancelled, the previous version in the other slot may no longer start. If the
projector still ends up in fastboot mode after the rescue, the system was not the cause, and the stock
restore is what is left.

<a name="stock"></a>

## Back to stock (erases everything)

1. Ask XGIMI support for the stock firmware for the Z9X (a `.bin` file). We do not host it.
2. Format a USB stick as FAT32 with an MBR partition table. Copy the `.bin` and an empty file named
   `factory.txt` to it.
3. Unplug the A-to-A cable. Plug the stick into the USB 2.0 port.
4. Switch the projector on while holding the power button for 6 to 7 seconds.
5. The restore takes about 4 minutes and erases everything. Then stock starts.

There is a second way, but it has not been tested yet. Until you install your first Lumen OS update,
the other slot still holds XGIMI stock. You can switch to it from fastboot mode (`adb reboot fastboot`),
within the same 2 minutes. Find the current slot:

```
fastboot getvar current-slot
```

Make the other slot active. If the current one is `b`, use `a`. If it is `a`, use `b`. Then restart:

```
fastboot --set-active=a
```

```
fastboot reboot
```

With Lumen OS data on it, stock will most likely not start or will ask for a reset. Then the USB stick
is the way back.

## What changes on the projector

| Partition | What happens |
|---|---|
| `system` of the current slot | replaced by Lumen OS |
| `userdata`, `metadata`, `cache` | formatted once at install |
| `z9x_blobs` (new, 4 MB) | your own MediaTek and XGIMI codec and audio files |
| `z9x_gapps` (new, 512 MB) | only if you add Google services |
| `product`, `system_ext` of the current slot | deleted: parts of stock that Lumen OS does not use |
| `vbmeta` | changed only by you, in step 3 |
| the other slot | changed only by a Lumen OS update you allowed |
| boot, vendor, dtbo, mboot, optee, tvconfig, persist, xgimi*, calibration | never changed |

Lumen OS is licensed under Apache-2.0, the AirPlay receiver under GPL-3.0. No XGIMI, MediaTek or Google
software is in the release files.

Lumen OS is an independent project. It is not made or supported by XGIMI, Google or LineageOS, and you
install it at your own risk.
