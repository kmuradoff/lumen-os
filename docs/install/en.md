# Installing Lumen OS on the XGIMI Z9X

[Русская версия](ru.md)

> [!NOTE]
> This guide starts with the XGIMI Z9X. For the XGIMI Play 6 (beta) go to [XGIMI Play 6](#play6): it
> installs differently (over Wi-Fi, with a cable only for the write) and needs its own release files.

This guide covers Lumen OS 1.0.4 without Google on the XGIMI Z9X (model code G0082) with XGIMI
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

## What is missing without Google

For now only the edition without Google is published. It has no Google Play, no Chromecast built-in, no
Google voice search, text-to-speech or TalkBack, and no remote in the Google TV phone app. Apps that need
Google Play services will not start. AirPlay, HDMI, the projector settings, Lumen OS updates and the
screensaver all work. You can add Google services yourself, see [Adding Google services](#google).

## What you need

- A Z9X on XGIMI firmware V6.15.58, V6.15.62 or V6.15.19.
- Everything works on stock: picture, autofocus, sound, HDMI. If something is already broken, sort that
  out first, or later you will not know what caused it.
- A computer with macOS or Linux. There is a Windows 10/11 installer, but it has not been tested yet.
- Android SDK Platform-Tools 35 or newer, [from Google](https://developer.android.com/tools/releases/platform-tools).
  Unpack the whole zip and add the `platform-tools` folder to PATH. On macOS and Linux, if you unpacked
  it into your home folder, run this in the same terminal you start the installer from:
  `export PATH="$HOME/platform-tools:$PATH"`. Do not take files out of the zip one by one: the
  installer needs `make_f2fs` and `mke2fs` next to `fastboot`.
- A USB A-to-A cable (plain USB-A on both ends). It goes into the projector's USB 2.0 port.
- 4 GB of free space on the computer.
- A USB stick, in case you need the stock restore.

On Windows, run `lumen-install.cmd` instead of `bash lumen-install.sh`. Options are spelled differently
there and go after the command: `-Lang en`, `-KeepData`, `-NoBlobs`, `-Zip`, `-Remove`. Unpack everything
into a folder whose path has only Latin letters, such as `C:\lumen`. If the projector does not show up in
fastboot, install the [Google USB Driver](https://developer.android.com/studio/run/win-usb).

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
2. From the latest release (now 1.0.4), download four files: `lumen-os-1.0.4-nogms-system.img`,
   `SHA256SUMS`, `SHA256SUMS.sig` and `lumen-os-1.0.4-installer.zip`.
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

```
bash lumen-install.sh flash
```

Windows: `lumen-install.cmd flash`

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

## 7. First start (5 min)

The first start takes 2 to 4 minutes. Then the setup asks, in this order: the remote (confirm it with
OK), the language, Wi-Fi and the picture. There is no Google step. The time zone is set automatically when
there is internet. Lumen Home opens after the setup.

Unplug the cable from the computer. While it is connected, the projector does not really sleep: the
lamp goes off, but the system keeps running.

If there is no setup screen after 10 minutes, see [Something went wrong](#problems).

<a name="verify"></a>

## 8. Optional: verify the install

On the projector, open Settings > Device Preferences > About. It should show Lumen OS 1.0.4.

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
2. Turn on USB debugging on the projector ([step 8](#verify)) and connect the cable.
3. Run:

   ```
   bash lumen-install.sh gapps --zip ~/Downloads/MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip
   ```

   Windows: `lumen-install.cmd gapps -Zip C:\Users\<you>\Downloads\MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip`
4. Type `ERASE`. The installer creates the partition `z9x_gapps` (512 MB), copies the Google apps from
   the file into it, checks each one, wipes the data and restarts the projector.

To keep your data, add `--keep-data` and type `WRITE` instead of `ERASE`. But then the phone remote may
not work properly until a factory reset.

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
| check | "make_f2fs/mke2fs missing" or "fastboot … is too old" | Get a fresh Platform-Tools zip and keep its files together. |
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

<a name="play6"></a>

## XGIMI Play 6

This part covers Lumen OS without Google on the XGIMI Play 6 (model code G0058) with the latest XGIMI
firmware V1.7.116. As on the Z9X, Lumen OS replaces only the system partition. XGIMI's firmware,
calibration and factory data are not touched.

The install takes about 30 minutes and **erases all data on the projector**. There is no vbmeta step and
no codec step: after the preparation below, one command does the rest and tells you what to do with the
cable and the power button.

What is different from the Z9X:

- The Play 6 has one USB-A port and no USB debugging. The computer talks to it over Wi-Fi.
- For the write itself the projector is connected to the computer with a USB A-to-A cable, only when the
  installer asks. While that cable is in, the Play 6 does not start, so it comes out right after the
  write.
- The projector's own fastboot mode cannot erase the data. So Lumen OS first starts once on your old
  data, then the installer asks Android for its own factory reset, and XGIMI's recovery erases the data,
  as Settings > Reset would.
- Google services cannot be added on the Play 6 yet.

<a name="play6-need"></a>

### What you need

- A Play 6 on the latest XGIMI firmware V1.7.116. If yours is older, let stock update over Wi-Fi first (or
  flash V1.7.116 from a stick, see [Back to stock](#play6-stock)). Other versions are refused.
- Its power adapter, connected for the whole install.
- A Wi-Fi network that the projector and the computer are both in. A guest network that keeps devices
  apart does not work.
- A computer with macOS or Linux. There is a Windows 10/11 installer, but it has not been tested yet.
- Android SDK Platform-Tools 35 or newer, [from Google](https://developer.android.com/tools/releases/platform-tools),
  on PATH as described for the Z9X in [What you need](#what-you-need).
- A USB A-to-A cable (plain USB-A on both ends).
- A USB stick: for SystemUI Tuner now, and as the stock restore stick if you ever need it.
- 4 GB of free space on the computer.

On Windows, run `lumen-install.cmd` instead of `bash lumen-install.sh`, with `-Ip` instead of `--ip` and
the options after the command, for example `lumen-install.cmd check -Ip 192.168.x.y`. For fastboot mode
on the cable Windows may need the [Google USB Driver](https://developer.android.com/studio/run/win-usb).

<a name="play6-prepare"></a>

### 1. Prepare the projector (10 min)

1. On stock, open the settings and look at the system version (About). You need V1.7.116. If it is older, let stock
   update itself over Wi-Fi to V1.7.116.
2. Connect the projector to your Wi-Fi. If the stock settings have automatic system updates, turn them off,
   so that no XGIMI update is downloaded during the install. If one is already waiting, the installer says
   so: then do not restart the projector yourself until Lumen OS is written.
3. Turn on debugging with SystemUI Tuner, as on the Z9X:
   1. Download the SystemUI Tuner APK file and copy it to a USB stick. Rename it so that it ends in
      `.apk1` (for example `SystemUITuner.apk1`): XGIMI's file manager does not open `.apk` files.
   2. Plug the stick into the projector's USB port. Open the stick in XGIMI's file manager, open the file
      and install it.
   3. Open SystemUI Tuner. In its first screens it offers to turn on USB debugging: confirm with OK. On the
      Play 6 this turns on debugging over the network at once. No restart is needed and no question
      appears on the screen.
4. Note the projector's IP address. The stock network settings show it on the page of the Wi-Fi network
   it is connected to. Your router's list of devices shows it too. If you do not find it, start the
   installer without `--ip`: it looks for the projector in the network and asks you.
5. Have the A-to-A cable ready, but do not plug it in yet. The stick may stay in the port: the installer
   tells you when to take it out.

<a name="play6-files"></a>

### 2. Download the files (5 min)

1. Open the [Releases page](https://github.com/kmuradoff/lumen-os/releases) and choose the release for
   the XGIMI Play 6: its name says Play 6 and its tag starts with `play6-`. The release marked "Latest" is
   the Z9X one.
2. Download four files: `lumen-os-play6-<version>-nogms-system.img`, `SHA256SUMS`, `SHA256SUMS.sig` and
   `lumen-os-play6-<version>-installer.zip`.
3. Unpack the installer zip and put the image and both `SHA256SUMS` files next to `lumen-install.sh`.
4. Open a terminal in that folder, as in [Download the files](#2-download-the-files-5-min) for the Z9X.

The Z9X files and the Play 6 files do not mix: each installer download checks its image with its own
key and refuses an image of the other projector.

<details>
<summary>Check the signature by hand</summary>

```
openssl x509 -in certs/play6/ota.x509.pem -pubkey -noout > lumen.pub
```

```
openssl dgst -sha256 -verify lumen.pub -signature SHA256SUMS.sig SHA256SUMS
```

The second command must print `Verified OK`. The certificate fingerprint
(`openssl x509 -in certs/play6/ota.x509.pem -noout -fingerprint -sha256`) must match the `ota` line in
the repository's `tools/play6/certs/FINGERPRINTS.txt`:
`61:34:06:4F:E6:53:3E:4A:17:D6:A0:EC:57:B3:19:3F:01:FA:74:31:78:04:85:45:F7:A4:E9:D9:67:E4:C8:72`.

</details>

<a name="play6-check"></a>

### 3. Run the check (2 min, writes nothing)

Replace `192.168.x.y` with your projector's address:

```
bash lumen-install.sh --ip 192.168.x.y check
```

The installer connects over Wi-Fi and checks the model, the XGIMI firmware, the tools on the computer and
the image: that it is from the same release as the installer, its signature and its checksum. The address is saved: later runs find the projector without `--ip`,
unless it gets a new address. At the end you should see lines like these:

```
✓ XGIMI Play 6 XXXXXXXXXXXX (192.168.x.y:5555), firmware v1.7.116, system: XGIMI stock
✓ SHA256SUMS signature: Lumen OS key for the XGIMI Play 6
✓ edition: Lumen OS without Google
✓ check passed; nothing was written
```

If the installer prints `STOP`, look up its message in [Something went wrong](#play6-problems).

<a name="play6-install"></a>

### 4. Install Lumen OS (about 20 minutes, erases all data)

```
bash lumen-install.sh --ip 192.168.x.y
```

The installer runs the check, offers a backup of your apps and files (see [step 5](#5-optional-back-up-apps-and-files-5-min)
for the Z9X: the same, over Wi-Fi, about a minute per GB), and then:

1. It shows what will be erased and what stays. Type `ERASE`.
2. "Take the USB stick out of the projector's USB port ... connect that port to this computer with the
   USB A-to-A cable." Do that and press Enter. Keep the power adapter connected.
3. The installer restarts the projector into fastboot mode over Wi-Fi (about 30 seconds; the picture may
   go dark), checks it there and writes the system (about a minute). Do not unplug the cable or the power
   now.
4. "Written. Unplug the A-to-A cable from the projector NOW." Unplug it and press Enter. If the picture
   does not come back within a minute, press the power button. A USB stick in the port helps the Play 6
   power on.
5. Lumen OS starts for the first time on your old data (up to 10 minutes). The installer finds it over
   Wi-Fi. If the projector asks "Allow USB debugging?", tick "Always allow from this computer" and press
   OK.
6. The installer asks Android for its factory reset. The projector restarts into XGIMI's recovery, which
   erases the data in a few minutes, and then starts Lumen OS clean.
7. When the projector shows Lumen OS again, answer `yes` to "Do you see Lumen Home?".

If the installer stops anywhere, fix what it says and run the same command again: it continues where it
stopped. If the projector is still in fastboot mode on the cable at that point, it continues there.

<a name="play6-wifi"></a>

### 5. First start: join Wi-Fi

The factory reset erased everything, the Wi-Fi network too. Lumen OS starts clean. If it shows a setup,
follow it. Otherwise Lumen Home opens directly: then, with the remote, open Settings > Network & Internet
and join your Wi-Fi. The language is in Settings > Device Preferences > Language.

A few demo files from XGIMI may show up in the Music folder: XGIMI's recovery puts them back after every
reset. You can delete them.

Apps install from a USB stick as on the Z9X, see [Install apps](#9-install-apps). A newer Lumen OS for the
Play 6 is installed with the installer later: `bash lumen-install.sh --ip ADDRESS --keep-data flash` keeps
your data (debugging must be on, see below).

<a name="play6-verify"></a>

### 6. Optional: check the install

1. Join Wi-Fi (step 5).
2. Open Settings > Device Preferences > About and press OK on Build 7 times.
3. Open Settings > Device Preferences > Developer options and turn on USB debugging. On the Play 6 this is
   debugging over the network.
4. Look up the address in Settings > Network & Internet, on your network's page.
5. Run, and allow the question on the projector:

   ```
   bash lumen-install.sh verify --ip 192.168.x.y
   ```

The installer shows the version, whether the data was really erased, whether the projector is marked as
set up, and the projector app and the audio outputs. Lines with `✓` are fine. Send lines with `!` along
with your question. Turn debugging off again afterwards if you like.

<a name="play6-problems"></a>

### Something went wrong

| Where | What you see | What to do |
|---|---|---|
| check | "give the projector's Wi-Fi address" | Run with `--ip ADDRESS`, see [step 1](#play6-prepare), item 4. Nothing was done. |
| check | "no adb connection to ... over Wi-Fi" | Is debugging on (step 1, item 3), are the projector and the computer in the same network, is the address right? Nothing was done. |
| any step | "did not allow this computer" | The projector asks whether this computer may use debugging. Tick "Always allow from this computer", press OK and run the same command again. |
| check | "XGIMI firmware ... is not supported" | Older: let stock update itself to V1.7.116. Newer or nothing else helps: install V1.7.116 from a stick, see [Back to stock](#play6-stock). Nothing was done. |
| check | "this projector is not supported" | This part is only for the Play 6 (G0058). Nothing was done. |
| check | "the device at ... is an XGIMI Z9X" | Use the Z9X part of this guide: the Z9X installs over the cable, without `--ip`. |
| check | "this download of the installer is for the XGIMI Z9X" | Download the Play 6 release, see [step 2](#play6-files). |
| check | "is an XGIMI Z9X image", "SHA256SUMS is not signed with the Lumen OS key for the XGIMI Play 6" or "checksum mismatch" | Wrong or damaged files. Download the Play 6 release files again, from GitHub only. |
| check | "is Lumen OS ..., but this installer download is for Lumen OS ..." or "is not named like a release file" | The image and the installer come from different releases, or the image was renamed. Download the image, both `SHA256SUMS` files and the installer zip from the same Play 6 release and keep the names. Nothing was written. |
| flash | "adb reboot fastboot failed" | Unplug the A-to-A cable and run the installer again. Nothing was written. |
| flash | "the projector did not appear on the cable within 3 minutes" | Is the cable in the projector's USB port, directly, without a hub? On Windows, install the Google USB Driver. On Linux, `fastboot devices` must not say "no permissions" (udev rules). Nothing was written. Run the installer again: it finds the projector in fastboot mode or sends it there again. |
| flash | "fastboot mode check failed" | The installer restarted the projector into its old system. Unplug the cable. Nothing was written. Report it with the installer output. |
| flash | "the image does not fit into super" | Nothing was written. Report it with the installer output. |
| flash | "system was not written completely" | Keep the cable in and run the installer again right away: it writes the system again. |
| flash | "neither in fastboot mode on the cable nor reachable over Wi-Fi" | Unplug the A-to-A cable and press the power button; a USB stick in the port helps it power on. When XGIMI's system is back, run the installer again. If the picture stays dark, do the [stock restore](#play6-stock). |
| flash | The power went off during the write | The projector cannot start: do the [stock restore](#play6-stock), then install again. |
| first start | "Lumen OS did not answer over Wi-Fi within 10 minutes" | Is the cable out? Press the power button; a USB stick in the port helps it power on. If Lumen Home is on the screen, look up its address (Settings > Network & Internet) and run the installer again with `--ip ADDRESS`. Your data is still there. |
| first start | "the projector still runs XGIMI's system" or "still runs the Lumen OS ... it had before" | The write did not take effect. Nothing was erased. Run the installer again with the A-to-A cable at hand: it writes the system again. If it happens again, report it. |
| first start | "! ... the image names its version differently from its release" | Nothing to do: the image was checked before the write and the install goes on. Report it, so that the next release is fixed. |
| first start | "Allow USB debugging?" on the projector | Tick "Always allow from this computer" and press OK. |
| wipe | "the factory reset did not start" or "adb root is not possible" | On the projector: Settings > Device Preferences > Reset > Factory reset. |
| wipe | "after 'adb root' the projector did not answer" | Allow debugging on the projector if it asks ("Always allow from this computer", OK) and run the installer again. Nothing was erased. |
| wipe | "the factory reset of an earlier run has already erased the data" | Join Wi-Fi and turn debugging on (step 6), then run the installer again with `--ip ADDRESS`: it sees that the data is erased and finishes. |
| after the wipe | The picture stays dark for more than 10 minutes | Press the power button once. If it stays dark, do the [stock restore](#play6-stock). |
| after the wipe | Lumen Home opens, but the remote's Home key does nothing | Run `verify` (step 6) and report the lines with `!`. |
| verify | "the data was NOT erased" | Answer `yes` to erase it now, or use Settings > Device Preferences > Reset > Factory reset. |
| rescue | "this computer last saw this projector on XGIMI's stock system" | `rescue` repairs Lumen OS only. To install, run the installer without a command (step 4). Nothing was written. |
| stick | Lines with `!` | Fix them as the line says, see [Back to stock](#play6-stock). |
| later | The projector does not power on | Unplug the A-to-A cable. Put a USB stick in the port and press the power button. |

The Play 6's progress is kept in `~/.lumen-installer/play6/`. `bash lumen-install.sh status` shows it and
`bash lumen-install.sh --device play6 reset` forgets it. Neither touches the projector. For a report,
attach the same as for the Z9X, see [If nothing helped](#if-nothing-helped).

<a name="play6-rescue"></a>

### Rescue from fastboot mode (keeps your data)

When the projector waits in fastboot mode, for example after a write that was cut off, the installer can
write the system again without erasing data:

```
bash lumen-install.sh --device play6 rescue
```

Type `WRITE` and connect the A-to-A cable. If this computer does not know the projector's serial number
yet, the installer shows the one it finds and asks you to type it, so that it never writes to another
projector. Unplug the cable when it says so.

Lumen OS on the Play 6 does not go to fastboot mode by itself after failed starts yet. If it does not
start and does not appear in fastboot mode, the stock restore is the way back.

<a name="play6-stock"></a>

### Back to stock (erases everything)

XGIMI's own USB update installs the stock firmware from a stick. It works only when the stick is prepared
exactly like this:

1. A USB stick with an **MBR** partition table and **one FAT32** partition. A stick of 32 GB or less is the
   easiest. On macOS: Disk Utility > View > Show All Devices, select the stick itself (not its volume),
   Erase, Format "MS-DOS (FAT)", Scheme "Master Boot Record". GPT, several partitions or a stick with other
   files did not work.
2. On it, only two files: the XGIMI firmware `XGIMI_DRAGONHEALTHY_XN03C.bin` (exactly this name) and an
   **empty** file `factory.txt`. On macOS copy in the terminal, because Finder adds hidden `._` files:

   ```
   cp -X ~/Downloads/XGIMI_DRAGONHEALTHY_XN03C.bin /Volumes/STICK/
   ```

   ```
   touch /Volumes/STICK/factory.txt
   ```

   Firmware V1.7.116 is on XGIMI's own server:
   [XGIMI_DRAGONHEALTHY_XN03C.bin](https://product-library.static.xgimi.com/firmware/release/MTK9270/dayao/MTK9270_APHRODIT_1.7.116_20251125-162039/XGIMI_DRAGONHEALTHY_XN03C.bin)
   (2 051 543 712 bytes, md5 `55ae66ed7e14a20759ea4b9d5686d591`).
3. Let the installer check the stick (read only, it never formats anything):

   ```
   bash lumen-install.sh stick /Volumes/STICK
   ```

   It checks the partition table, the file system, that nothing else is on the stick, that `factory.txt`
   is empty and that the firmware file is one of the two above.
4. Unplug the A-to-A cable and put the stick into the projector's USB port. Switch the projector off and
   pull its power cable.
5. Hold the projector's power button, plug the power cable in and keep holding about 5 seconds. The screen
   shows "正在检测加载更新包" (looking for the update), then "系统更新中，请勿断开电源，预计4分钟"
   (updating, do not cut the power, about 4 minutes).
6. The restore erases everything. Then the stock system starts as from the factory.

<a name="play6-changes"></a>

### What changes on the Play 6

| Partition | What happens |
|---|---|
| `system` | replaced by Lumen OS, written in fastboot mode |
| `userdata`, `cache` | erased once by Android's factory reset, which XGIMI's recovery carries out |
| `misc` | Android leaves the factory reset command for XGIMI's recovery there, as Settings > Reset does; the installer never writes it |
| vendor, boot, recovery, dtbo, MBOOT, MPOOL, tvconfig, tvservice, cusdata, project_id, tvcertificate, xgimi*, upgrade, calibration | never changed |

The installer never runs `fastboot -w`, `erase`, `format` or `flashing` on the Play 6, and no other
`flash` than system. Every partition write goes through an allow-list in the script.

Lumen OS is licensed under Apache-2.0, the AirPlay receiver under GPL-3.0. No XGIMI, MediaTek or Google
software is in the release files.

Lumen OS is an independent project. It is not made or supported by XGIMI, Google or LineageOS, and you
install it at your own risk.
