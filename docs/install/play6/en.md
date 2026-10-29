<a name="play6"></a>

# Installing Lumen OS on the XGIMI Play 6

[Русская версия](ru.md)

> [!NOTE]
> This guide is for the XGIMI Play 6. The XGIMI Z9X has [its own guide](../z9x/en.md): it installs over
> the cable and needs its own release files.

This guide covers Lumen OS on the XGIMI Play 6 (model code G0058) with the latest XGIMI
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
- Lumen OS for the Play 6 comes without Google. You can add Google Play and Google sign-in from your own
  download, together with the install or later, see below.

<a name="play6-choose"></a>

## Three ways to install

| | What you get | Data wiped | How |
|---|---|---|---|
| 1 | **Clean Lumen OS without Google** | once | `bash lumen-install.sh --ip ADDRESS`, [step 4](#play6-install) |
| 2 | **Lumen OS and Google services in one command** | once | `bash lumen-install.sh --ip ADDRESS --gapps-zip ...`, [Lumen OS and Google services in one command](#play6-one-pass) |
| 3 | **Google services later**, on an installed Lumen OS | once more | the `gapps` step, [Google services](#play6-google) |

Steps 1 to 3 are the same for 1 and 2. If you already know you want Google Play, take 2: you set the
projector up only once.

<a name="play6-need"></a>

## What you need

- A Play 6 on XGIMI firmware V1.7.116. If yours is older, let stock update over Wi-Fi first. Other versions are
  refused.
- The newer firmware V1.8.x (About shows it as RG0058V1.8xxx) is not supported by Lumen OS yet. It comes on newer
  Play 6 batches with another Wi-Fi chip. Do not replace it with V1.7.116 from a stick yourself, ask first:
  V1.7.116 has no driver for that chip, so on stock Wi-Fi and Bluetooth stop working. If you already did and
  Wi-Fi is gone: install Lumen OS 1.0.7 or newer (use a USB Ethernet adapter for now). Lumen OS detects this
  chip and loads its own driver, so Wi-Fi and Bluetooth work.
- Its power adapter, connected for the whole install.
- A Wi-Fi network that the projector and the computer are both in. A guest network that keeps devices
  apart does not work.
- A computer with macOS or Linux. There is a Windows 10/11 installer, but it has not been tested yet.
- Internet access on the first run: the installer downloads Google's Platform-Tools (adb and fastboot) for
  itself, checks them and uses only that copy, on its own adb port, with a time limit on every step. Without
  internet, see the Z9X guide, [What you need](../z9x/en.md#what-you-need).
- A USB A-to-A cable (plain USB-A on both ends).
- A USB stick: for SystemUI Tuner now, and as the stock restore stick if you ever need it.
- 4 GB of free space on the computer.

On Windows, run `lumen-install.cmd` instead of `bash lumen-install.sh`, with `-Ip` instead of `--ip` and
the options after the command, for example `lumen-install.cmd check -Ip 192.168.x.y`. `--gapps-zip` is
`-GappsZip` there. Keep the installer folder on the computer's own disk (for example `C:\lumen`), not on a USB
stick. For fastboot mode on the cable Windows may need the [Google USB Driver](https://developer.android.com/studio/run/win-usb):
the installer says so when it finds a USB device without a driver.

<a name="play6-prepare"></a>

## 1. Prepare the projector (10 min)

1. On stock, open the settings and look at the system version (About). You need V1.7.116. If it is older, let stock
   update itself over Wi-Fi to V1.7.116. If it is V1.8.x (shown as RG0058V1.8xxx), stop: Lumen OS does not support
   it yet, and do not put V1.7.116 on that projector from a stick.
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

## 2. Download the files (5 min)

1. Open the [Releases page](https://github.com/kmuradoff/lumen-os/releases) and choose the release for
   the XGIMI Play 6: its name says Play 6 and its tag starts with `play6-`. The release marked "Latest" is
   the Z9X one.
2. Download four files: `lumen-os-play6-<version>-nogms-system.img`, `SHA256SUMS`, `SHA256SUMS.sig` and
   `lumen-os-play6-<version>-installer.zip`.
3. Unpack the installer zip and put the image and both `SHA256SUMS` files next to `lumen-install.sh`.
4. Open a terminal in that folder, as in [Download the files](../z9x/en.md#2-download-the-files-5-min) for the Z9X.

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

## 3. Run the check (2 min, writes nothing)

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

## 4. Install Lumen OS (about 20 minutes, erases all data)

Pick one of the two commands:

| 1. Clean Lumen OS without Google | 2. Lumen OS and Google services in one command |
|---|---|
| `bash lumen-install.sh --ip 192.168.x.y` | `bash lumen-install.sh --ip 192.168.x.y --gapps-zip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip` |
| Windows: `lumen-install.cmd -Ip 192.168.x.y` | Windows: `lumen-install.cmd -Ip 192.168.x.y -GappsZip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip` |
| about 20 minutes, described below | longer, the Google apps are added in between, see [Lumen OS and Google services in one command](#play6-one-pass) |

To add Google services later to a Lumen OS that is already installed, see [Google services](#play6-google).

For the clean install, the installer runs the check, offers a backup of your apps and files (see
[step 5](../z9x/en.md#5-optional-back-up-apps-and-files-5-min) of the Z9X guide: the same, over Wi-Fi,
about a minute per GB), and then:

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

<a name="play6-one-pass"></a>

### Lumen OS and Google services in one command

> [!NOTE]
> New: this way has not been tried on a projector yet. If something goes wrong, the installer says how to
> go on. The proven way is way 1, then way 3.

This is way 2: Lumen OS with Google Play, Google Play services, the Google sign-in for TVs and the remote
in the Google TV phone app, with one data wipe in total. What you get and what is left out is the same as
in [Google services](#play6-google).

1. Download the MindTheGapps file named in [Google services](#play6-google), item 1, and put it in the
   installer folder, next to `lumen-install.sh`. It must be exactly that file, the installer refuses any
   other (the Z9X file too).
2. Do steps 1 to 3 as for a clean install.
3. Run:

   ```
   bash lumen-install.sh --ip 192.168.x.y --gapps-zip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip
   ```

   Windows: `lumen-install.cmd -Ip 192.168.x.y -GappsZip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip`

   Do not add a command name such as `flash`, and do not add `--keep-data`: the installer refuses them
   with `--gapps-zip`.
4. The installer checks the MindTheGapps file before it writes anything. Then it shows what will be
   erased. Type `ERASE`.
5. Connect the A-to-A cable when asked, as at a clean install. In fastboot mode the installer writes the
   system and creates the partition `lumen_gapps`, in the same session.
6. Unplug the cable when the installer says so. Lumen OS starts once on your old data. Wi-Fi and
   debugging carry over.
7. The installer needs `adb root` now. If "Rooted debugging" is off, it waits and tells you where to turn
   it on: Settings > Device Preferences > Developer options > Rooted debugging. Turn it on and answer
   `yes` to "Is Rooted debugging on now?": the installer continues.
8. It copies the Google apps to the projector and checks them (2 to 4 minutes). If Google services from
   an earlier install are still on the projector, it restarts the projector once first.
9. Then it asks Android for its factory reset. That is the only wipe. The first start after it sets the
   Google services up.
10. When the projector shows Lumen OS again, answer `yes` to "Do you see Lumen Home?".

After that, join Wi-Fi and sign in, as in [Google services](#play6-google): Settings > Accounts > Add
account. If Google refuses the sign-in, register the projector, see [GSF ID](#play6-gsf).

If the run stops anywhere, fix what the installer says and run the same command again: it continues
where it stopped.

If Lumen OS does not start on the old data, run the same command again: it wipes the data and finishes
the install without Google. Add them later with the `gapps` step, see [Google services](#play6-google).

If you run the installer without `--gapps-zip` while such an install is not finished, it asks whether to
finish it without Google.

<a name="play6-wifi"></a>

## 5. First start: join Wi-Fi

The factory reset erased everything, the Wi-Fi network too. Lumen OS starts clean. If it shows a setup,
follow it. Otherwise Lumen Home opens directly: then, with the remote, open Settings > Network & Internet
and join your Wi-Fi. The language is in Settings > Device Preferences > Language.

A few demo files from XGIMI may show up in the Music folder: XGIMI's recovery puts them back after every
reset. You can delete them.

Apps install from a USB stick as on the Z9X, see [Install apps](../z9x/en.md#9-install-apps). A newer Lumen OS for the
Play 6 is installed with the installer later: `bash lumen-install.sh --ip ADDRESS --keep-data flash` keeps
your data (debugging must be on, see below).

<a name="play6-verify"></a>

## 6. Optional: check the install

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

<a name="play6-google"></a>

## 7. Google services

This is way 3, for a Lumen OS that is already installed. To install Lumen OS and Google services
together, with one wipe, see [Lumen OS and Google services in one command](#play6-one-pass).

We are not allowed to redistribute Google's apps, so you download the file yourself and the installer adds
it. You get Google Play, Google Play services, the Google sign-in for TVs and the remote in the Google TV
phone app. Lumen Home stays the home screen. Left out on purpose, to keep the Play 6's 1.4 GB of memory for
your apps: the Google TV home screen, voice search, Google text-to-speech and TalkBack.

Chromecast built-in is not part of it and cannot be: Google gives the Cast device certificate only to
devices it has certified, and the Play 6 with Lumen OS is not one of them. The same holds on the Z9X, see
[Chromecast](../z9x/en.md#chromecast).

It takes about 20 minutes and **erases all data**, like the install. You need the A-to-A cable again,
and an installer download that has this step (an older one answers that Google services cannot be added
yet).

1. Download MindTheGapps for 32-bit Android 14 TV (223 MB). It must be exactly this file, the installer
   refuses any other (the Z9X file too):
   [MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip](https://github.com/MindTheGapps/14.0.0-arm-ATV/releases/download/MindTheGapps-14.0.0-arm-ATV-20240523_192108/MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip).
   Safari on a Mac sometimes unpacks the zip by itself. If that happens, download it with another browser:
   you need the `.zip`.
2. Turn debugging on and look up the address ([step 6](#play6-verify), items 1 to 4). In the same Developer options
   also turn on "Rooted debugging": this step needs root, without it the installer stops.
3. Run:

   ```
   bash lumen-install.sh --ip 192.168.x.y gapps --zip ~/Downloads/MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip
   ```

   Windows: `lumen-install.cmd gapps -Zip C:\Users\<you>\Downloads\MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip -Ip 192.168.x.y`
4. Type `ERASE`. Then, as at the install: connect the A-to-A cable when asked, and unplug it when the
   installer says so. In fastboot mode the installer creates the partition `lumen_gapps` (204 MB). When
   Lumen OS is back, it copies the file to the projector over Wi-Fi (a few minutes), puts the Google apps
   into that partition, checks each one, and asks Android for its factory reset.
5. When the projector shows Lumen OS again, answer `yes` to "Do you see Lumen Home?".

To keep your data, add `--keep-data` and type `WRITE` instead of `ERASE`. But then the phone remote may
not work properly until a factory reset.

<a name="play6-gsf"></a>

Then join Wi-Fi and sign in: Settings > Accounts > Add account. Usually the sign-in just works. If Google
refuses it, or Play says the device is not certified, register the projector:

1. Turn debugging on again ([step 6](#play6-verify); the factory reset turned it off).
2. Get the GSF ID:

   ```
   bash lumen-install.sh --ip 192.168.x.y gsf
   ```

3. Register it at [google.com/android/uncertified](https://www.google.com/android/uncertified), wait 10
   to 30 minutes, then sign in from Settings > Accounts.

Lumen OS updates and factory resets keep the Google services. System, vendor, boot, recovery and the
calibration do not change; only the `lumen_gapps` partition is added. The stock restore from a stick
removes it.

`bash lumen-install.sh --ip 192.168.x.y gapps` shows their state. If a run stopped halfway, the
projector starts without Google services until you run the same `gapps --zip` command again.

To remove Google services, first remove your Google account (Settings > Accounts), then run (this also
erases data; with `--keep-data` it does not):

```
bash lumen-install.sh --ip 192.168.x.y gapps --remove
```

<a name="play6-problems"></a>

## Something went wrong

| Where | What you see | What to do |
|---|---|---|
| check | "give the projector's Wi-Fi address" | Run with `--ip ADDRESS`, see [step 1](#play6-prepare), item 4. Nothing was done. |
| check | "no adb connection to ... over Wi-Fi" | Is debugging on (step 1, item 3), are the projector and the computer in the same network, is the address right? Nothing was done. |
| any step | "did not allow this computer" | The projector asks whether this computer may use debugging. Tick "Always allow from this computer", press OK and run the same command again. |
| check | "XGIMI firmware ... is not supported" | Older: let stock update itself to V1.7.116. Newer (V1.8.x, shown as RG0058V1.8xxx): Lumen OS does not support it yet. Do not install V1.7.116 from a stick: on newer Play 6 units Wi-Fi and Bluetooth stop working. Nothing was done. |
| check | "this projector is not supported" | This guide is only for the Play 6 (G0058). Nothing was done. |
| check | "the device at ... is an XGIMI Z9X" | Use the [Z9X guide](../z9x/en.md): the Z9X installs over the cable, without `--ip`. |
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
| gapps, wipe | "root debugging is off on the projector" | Settings > Device Preferences > Developer options > Rooted debugging: turn it on, then run the same command again. Nothing was written. |
| `--gapps-zip` | Rooted debugging is off, the installer waits | Settings > Device Preferences > Developer options > Rooted debugging: turn it on and answer `yes`. Or answer `no` and run the same command again later; nothing is erased. |
| `--gapps-zip` | refused together with `--keep-data` or a command name such as `flash` | The one-command install always wipes the data once and runs as a whole. Run it exactly as in [Lumen OS and Google services in one command](#play6-one-pass). Nothing was written. |
| `--gapps-zip` | Lumen OS does not start on the old data | Run the same command again: it wipes the data and finishes without Google. Add them later with `gapps`. |
| any step | asks whether to finish an unfinished install without Google | You ran the installer without `--gapps-zip` while a one-command install was not finished. Answer `no` and run the command with `--gapps-zip` again to get Google, or `yes` to finish without it. |
| wipe | "after 'adb root' the projector did not answer" | Allow debugging on the projector if it asks ("Always allow from this computer", OK) and run the installer again. Nothing was erased. |
| wipe | "the factory reset of an earlier run has already erased the data" | Join Wi-Fi and turn debugging on (step 6), then run the installer again with `--ip ADDRESS`: it sees that the data is erased and finishes. |
| after the wipe | The picture stays dark for more than 10 minutes | Press the power button once. If it stays dark, do the [stock restore](#play6-stock). |
| after the wipe | Lumen Home opens, but the remote's Home key does nothing | Run `verify` (step 6) and report the lines with `!`. |
| verify | "the data was NOT erased" | Answer `yes` to erase it now, or use Settings > Device Preferences > Reset > Factory reset. |
| rescue | "this computer last saw this projector on XGIMI's stock system" | `rescue` repairs Lumen OS only. To install, run the installer without a command (step 4). Nothing was written. |
| stick | Lines with `!` | Fix them as the line says, see [Back to stock](#play6-stock). |
| gapps | "is not the MindTheGapps file this installer accepts" | Download exactly the file named in [Google services](#play6-google), with its original name. The arm64 file of the Z9X does not fit the Play 6. Nothing was written. |
| gapps | "has no Google services add-on support" | Install a newer Lumen OS for the Play 6 first (`--keep-data flash`, see [step 5](#play6-wifi)). Nothing was written. |
| gapps | "not enough free space in super" | Nothing was written in super. Report it with the installer output. |
| gapps | "the Google services were not added" or "the Google services are off" | Run the same `gapps --zip` command again. Until then Lumen OS starts without Google services; your data is still there. |
| later | The projector does not power on | Unplug the A-to-A cable. Put a USB stick in the port and press the power button. |

The Play 6's progress is kept in `~/.lumen-installer/play6/`. `bash lumen-install.sh status` shows it and
`bash lumen-install.sh --device play6 reset` forgets it. Neither touches the projector. For a report,
attach the same as for the Z9X, see [If nothing helped](../z9x/en.md#if-nothing-helped).

<a name="play6-rescue"></a>

## Rescue from fastboot mode (keeps your data)

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

## Back to stock (erases everything)

**Only for a Play 6 that came with V1.7.116 or older.** The file below is firmware V1.7.116 for units with a
MediaTek Wi-Fi module. If your Play 6 had V1.8.x (About shows RG0058V1.8xxx), this file is not for it: Wi-Fi
and Bluetooth stop working after it, because newer batches have another Wi-Fi chip (AIC) and V1.7.116 has no
driver for it. For such a projector, ask XGIMI support for its own firmware.

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

## What changes on the Play 6

| Partition | What happens |
|---|---|
| `system` | replaced by Lumen OS, written in fastboot mode |
| `userdata`, `cache` | erased once by Android's factory reset, which XGIMI's recovery carries out |
| `misc` | Android leaves the factory reset command for XGIMI's recovery there, as Settings > Reset does; the installer never writes it |
| `lumen_gapps` | only with [Google services](#play6-google): created in super in fastboot mode, then formatted and filled on the projector; `gapps --remove` deletes it |
| vendor, boot, recovery, dtbo, MBOOT, MPOOL, tvconfig, tvservice, cusdata, project_id, tvcertificate, xgimi*, upgrade, calibration | never changed |

The installer never runs `fastboot -w`, `erase`, `format` or `flashing` on the Play 6, and no other
`flash` than system. Besides that it only creates, resizes or deletes the partition `lumen_gapps`, for
Google services. Every partition write goes through an allow-list in the script.

Lumen OS is licensed under Apache-2.0, the AirPlay receiver under GPL-3.0. No XGIMI, MediaTek or Google
software is in the release files.

Lumen OS is an independent project. It is not made or supported by XGIMI, Google or LineageOS, and you
install it at your own risk.
