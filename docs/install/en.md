# Installing Lumen OS on the XGIMI Z9X

[Русская версия](ru.md)

This guide covers Lumen OS 1.0.1 without Google on the XGIMI Z9X (model code G0082) with XGIMI
firmware V6.15.58 or V6.15.19. Lumen OS is Android TV 14, built on LineageOS 21. It replaces only the
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

- A Z9X on XGIMI firmware V6.15.58 or V6.15.19.
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

1. On stock, open Settings > About and look at the firmware version. You need V6.15.58 or V6.15.19.
   If yours is older, connect Wi-Fi and let stock update itself to V6.15.58. If it is newer, Lumen OS
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
2. From release 1.0.1, download four files: `lumen-os-1.0.1-nogms-system.img`, `SHA256SUMS`,
   `SHA256SUMS.sig` and `lumen-os-1.0.1-installer.zip`.
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

On the projector, open Settings > Device Preferences > About. It should show Lumen OS 1.0.1.

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

1. Copy `.apk` files to a FAT32 or exFAT stick, into its root or a folder named `apks`.
2. Plug the stick into the projector.
3. In Lumen Home, open Apps > Install from USB. A list shows each app's name, icon, version and
   "New" or "Update".
4. Choose an app, press OK and confirm the install.

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
adds it with one command. You get Google Play, Google Play services, Chromecast built-in, Google voice search
and text-to-speech, TalkBack and the remote in the Google TV phone app. It takes about 5 minutes and
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

To keep your data, add `--keep-data` and type `WRITE` instead of `ERASE`. But then Chromecast and the
phone remote may not work properly until a factory reset.

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
| check | "XGIMI firmware … is not supported" | Let stock update to V6.15.58 over Wi-Fi, then run `check` again. Nothing was written. |
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
| Install from USB | The list is empty | The `.apk` files must be in the stick's root or in a folder `apks`, on FAT32 or exFAT. Take the stick out and plug it in again. |
| Install from USB | "was not installed" | The file is damaged, or the app is not for Android TV 14 (arm64). |
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

Lumen OS is licensed under Apache-2.0, the AirPlay receiver under GPL-3.0. No XGIMI, MediaTek or Google
software is in the release files.

Lumen OS is an independent project. It is not made or supported by XGIMI, Google or LineageOS, and you
install it at your own risk.
