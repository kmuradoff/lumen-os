<a name="hb"></a>

# Installing Lumen OS on the XGIMI Z9X HB

[Русская версия](ru.md)

Lumen OS is Android TV 14 based on LineageOS 21 for the XGIMI Z9X HB (model G0130) with XGIMI firmware
V6.16.24. It replaces only the system partition. XGIMI's firmware, boot, vendor and calibration are not
touched. New versions then arrive over Wi-Fi, and your data stays.

> [!CAUTION]
> The install **erases all data on the projector**. The stock firmware is put back with XGIMI's official
> V6.16.24 firmware file (2.2 GB, [download from XGIMI's server](https://product-library.static.xgimi.com/firmware/release/MTK9660_AOSP/dapeng/MTK9660_AOSP_GEMINI_6.16.24_20260428-152516/XGIMI_GOATELEGANT_XR03D.bin)), see
> ["Back to stock"](#hb-stock). Download it before the install.

> [!WARNING]
> **Note: this is firmware 6.16.24.** It is only for going back to stock from Lumen OS. If your projector came
> with firmware V6.17.12 (the menu may also show the GMUI launcher version 6.18.x), do not put 6.16.24 on it to install Lumen OS afterwards: nobody knows whether the old
> firmware fits the newer boards, and on other XGIMI models such a downgrade left Wi-Fi and Bluetooth dead.

The install takes about 30 minutes. You type a few commands in a terminal, and one step in fastboot mode
(vbmeta) you do yourself, without the installer.

**How the computer talks to the projector.** While the projector runs its normal system, the computer
connects to it over Wi-Fi: the check, copying files and the command to go into fastboot mode all go that way.
The USB A-to-A cable is needed only in fastboot mode, for the write: in the normal system the projector's USB
port works for USB sticks, and in fastboot mode it becomes a port for the computer. Plug the cable in only when
this guide or the installer says so. Fastboot mode does not end by itself, so there is no hurry.

<a name="hb-need"></a>

## What you need

- An XGIMI Z9X HB, model G0130, on XGIMI firmware V6.16.24. The model and the firmware version are shown on
  stock in "Settings → About". The installer checks them too and does nothing on another projector.
- A Wi-Fi network that both the projector and the computer are on. A guest network where devices cannot see
  each other will not do.
- A computer with macOS, Linux or Windows 10/11 (on Windows the install on the projector has not been tested
  yet).
- A USB A-to-A cable, a plain USB-A plug on both ends.
- 4 GB of free space on the computer.
- Internet at the first run: the installer downloads Google's Platform-Tools (adb and fastboot, about 10 MB)
  into the folder `tools/platform-tools` next to itself, checks them and works only with that copy. Without
  internet, give your own folder with adb and fastboot version 35 or newer: `--platform-tools FOLDER`
  (Windows: `-PlatformTools FOLDER`).
- A USB stick, in case you go back to stock.

On Windows run `lumen-install.cmd` instead of `bash lumen-install.sh` and write `-Ip` instead of `--ip`, for
example `lumen-install.cmd check -Ip 192.168.x.y`. Keep the installer
folder on the computer's own disk (for example `C:\lumen`), not on a USB stick. For fastboot mode on the cable
Windows may need the [Google USB Driver](https://developer.android.com/studio/run/win-usb).

<a name="hb-prepare"></a>

## 1. Prepare the projector (10 min)

1. Connect the projector to your Wi-Fi. If stock offers a system update, do not accept it: a downloaded update
   waits for a restart and gets in the way of the install.
2. Turn on debugging. In "Settings → About" press OK on the build number item until the "Developer options"
   menu opens. In it turn on "USB debugging". On the HB's stock this turns on debugging over Wi-Fi (port 5555).
3. Write down the projector's IP address. It is shown in stock's network settings, on the page of the Wi-Fi
   network the projector is connected to. The device list of your router shows it too.

The names of the menu items on stock may differ a little, depending on the language and the firmware version.

<a name="hb-files"></a>

## 2. Download the files (5 min)

1. Open the [releases page](https://github.com/kmuradoff/lumen-os/releases) on GitHub and pick the newest
   release for the XGIMI Z9X HB: its tag starts with `z9x-hb-`. Download only from there; I do not check
   copies on forums and file hosts.
2. Download four files from it: the image `lumen-os-hb-<version>-nogms-system.img`, `SHA256SUMS`,
   `SHA256SUMS.sig` and the installer `lumen-os-hb-<version>-installer.zip`.
3. Unpack the installer archive. Put the image and both `SHA256SUMS` files next to `lumen-install.sh`.
4. Open a terminal and go to that folder, for example `cd ~/lumen`.

The installer checks the signature and the checksum of the image itself and does not accept the files of
another projector.

<details>
<summary>Check the signature by hand</summary>

```
openssl x509 -in certs/hb/ota.x509.pem -pubkey -noout > lumen.pub
```

```
openssl dgst -sha256 -verify lumen.pub -signature SHA256SUMS.sig SHA256SUMS
```

The second command must answer `Verified OK`. The certificate's fingerprint
(`openssl x509 -in certs/hb/ota.x509.pem -noout -fingerprint -sha256`) must match the `ota/ota` line of the
repository file `devices/goat_elegant/certs/FINGERPRINTS.txt`:
`8E:47:E4:95:F9:FC:C5:DB:46:4F:52:C5:78:20:9F:DA:3A:9E:47:FF:80:AA:FC:E6:F7:12:5D:2C:51:A9:86:24`.

</details>

<a name="hb-check"></a>

## 3. Run the check (2 min, writes nothing)

Put your projector's address instead of `192.168.x.y`:

```
bash lumen-install.sh --ip 192.168.x.y check
```

The installer connects to the projector over Wi-Fi and checks the model, the firmware, the slot, the programs
on the computer and the image. It also says whether the vbmeta step is done: before step 4 it should not be.
At the end there must be a line saying that the check passed and nothing was written.

If the installer says "STOP", find its message in ["If something goes wrong"](#hb-problems).

<a name="vbmeta"></a>
<a name="hb-vbmeta"></a>

## 4. Turn off the vbmeta check (5 min)

You do this step yourself; the installer never runs vbmeta commands. Lumen OS is not signed by XGIMI, so the
check of the system partition has to be turned off. This is done for any GSI; Google describes it in
["Requirements for flashing GSIs"](https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis). Stock
works as before after this step.

If you do not have adb and fastboot installed, use the ones the installer downloaded: write
`tools/platform-tools/adb` instead of `adb` and `tools/platform-tools/fastboot` instead of `fastboot`
(Windows: `tools\platform-tools\adb.exe` and `tools\platform-tools\fastboot.exe`).

1. From [Google's GSI page](https://developer.android.com/topic/generic-system-image/releases) download any
   arm64 archive, for example `aosp_arm64`, take `vbmeta.img` out of it and put it into the installer folder.
2. Connect to the projector over Wi-Fi:

   ```
   adb connect 192.168.x.y:5555
   ```

   Then check:

   ```
   adb devices
   ```

   There must be a line `192.168.x.y:5555` that ends in `device`.
3. Take whatever is plugged into the projector's USB port out, and connect that port to the computer with the
   A-to-A cable.
4. Send the projector into fastboot mode (the command goes over Wi-Fi):

   ```
   adb -s 192.168.x.y:5555 reboot fastboot
   ```

   After 20-40 seconds check that the computer sees the projector on the cable:

   ```
   fastboot devices
   ```

   One line must appear that ends in `fastboot`.
5. Turn the check off in both slots at once:

   ```
   fastboot --disable-verity --disable-verification --slot=all flash vbmeta vbmeta.img
   ```

   The answer must have the lines `Writing 'vbmeta_a'` and `Writing 'vbmeta_b'`, both with `OKAY`.
6. Restart the projector:

   ```
   fastboot reboot
   ```

   Stock must start as usual, with your data. If the start hangs with the cable in, pull the cable and press
   the power button.
7. Pull the cable and run the check again (step 3). Now it must say that the vbmeta check is off.

<a name="hb-install"></a>

## 5. Install Lumen OS (about 10 minutes, erases all data)

```
bash lumen-install.sh --ip 192.168.x.y
```

To get Google Play right away, add `--gapps-zip` with the MindTheGapps file to this command, see
["Adding Google services"](#hb-google). The data is then erased once.

The installer goes through the steps in order by itself. If it stops somewhere, fix what it says and run the
same command again: it continues where it stopped.

1. If the check (step 3) has passed already, it does not repeat it.
2. It offers to save apps and files. If you agree, it copies the installed apps (the APK files) and the
   folders Download, Movies, Music, Pictures, DCIM and Documents into `~/Lumen-backup/<date>/` on the
   computer. App data (account sign-ins, watch progress) cannot be saved this way.
3. It copies two XGIMI files from the projector (see below) and keeps a copy of them on the computer.
4. It checks the projector and the image once more, shows what will be erased (apps, accounts, settings and
   files) and asks you to type `ERASE`. Without the vbmeta step it writes nothing.
5. It asks you to take everything out of the projector's USB port and connect that port to the computer with
   the A-to-A cable. Do that and press Enter.
6. From here it works without you: it sends the projector into fastboot mode, writes the XGIMI files and the
   system, formats the data and restarts the projector. Meanwhile do not pull the cable and do not cut the
   power.
7. When the installer says that everything is written, pull the cable. If the start hangs with the cable in,
   pull it and press the power button.

<a name="hb-blobs"></a>

### Two files from your projector

I have no right to publish XGIMI and MediaTek files, so the image does not have them. Before the write the
installer copies two such files from your own projector: MediaTek's video library (without it there is no
hardware video decoding, and the HDMI picture may freeze) and XGIMI's sound settings file (without it only the
built-in speaker works). It checks each file against a checksum, and Lumen OS checks them again at every start.
The files go into a small partition `z9x_blobs` (4 MB); a copy stays on the computer in
`~/Lumen-backup/hb-blobs-<serial number>.img`.

If the installer says that a file does not match its checksum, the files of your firmware are unknown to it.
Write in the [issues on GitHub](https://github.com/kmuradoff/lumen-os/issues) and attach the installer's
output. The system can be installed without these files too, with the option `--no-blobs`, but then you do
not get what is described above.

<a name="hb-first-start"></a>

## 6. First start

The first start after the erase takes longer than usual, up to a minute and a half. Then the first setup
opens: remote, language, Wi-Fi. Go through it.

From then on new versions of Lumen OS arrive over Wi-Fi: the projector finds an update by itself, installs it
while you watch and asks for a restart. Your data stays. While an update is being installed, until the
projector asks for the restart, do not change the picture settings and the keystone: the projector keeps them
in a partition that the update copies, and a change made during that time is lost.

### Installing apps

From a USB stick:

1. Copy the `.apk` files onto a FAT32 or exFAT stick and plug it into the projector.
2. In Lumen Home open "Files", pick the stick and press OK on the `.apk` file.
3. The name, icon, version and the label "New" or "Update" appear. Press OK once more and confirm the install.

Plain `.apk` files work, not `.apks` and not `.xapk`. Updates install the same way.

<a name="hb-verify"></a>

## 7. Optional: check the install

1. Open "Settings → Device Preferences → About" and press OK 7 times on the "Build" item.
2. Open "Settings → Device Preferences → Developer options" and turn on "USB debugging" (on the HB this is
   debugging over Wi-Fi).
3. Look up the projector's address in "Settings → Network & Internet", on the page of your network.
4. Run:

   ```
   bash lumen-install.sh --ip 192.168.x.y verify
   ```

   The projector asks "Allow debugging?". Tick "Always allow from this computer" and press OK.

The installer shows the Lumen OS version and whether the XGIMI files are in place. Lines with `✓` are fine.
Send the lines with `!` along with your question. After the check you can turn debugging off again.

<a name="hb-google"></a>

## Adding Google services

The release image is without Google. I cannot publish Google services, so you download the file with them
yourself, and the installer puts it into a partition of its own, `z9x_gapps`. You get Google Play, Google
Play services, Google text-to-speech, TalkBack and the remote in the Google TV app on the
phone. Chromecast built-in does not work: Google issues the Cast certificate only to models it has certified.
From an iPhone, iPad or Mac cast with AirPlay.

The installer builds the Google services partition on the computer, so you need a Mac or a computer with
Linux (x86-64 or ARM64) and about 1 GB of free space. On Windows the installer does not install Google
services yet and stops without writing anything.

1. Download MindTheGapps for 32-bit Android 14 TV (223 MB). It must be exactly this file, the installer does
   not accept another one:
   [MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip](https://github.com/MindTheGapps/14.0.0-arm-ATV/releases/download/MindTheGapps-14.0.0-arm-ATV-20240523_192108/MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip).
   Put it into the installer folder, next to `lumen-install.sh`. Safari on a Mac sometimes unpacks the archive
   by itself. If that happens, download it with another browser: it must be the `.zip`.
2. Pick one of the two ways.

**Right at the install** (the data is erased once). At [step 5](#hb-install) run:

```
bash lumen-install.sh --ip 192.168.x.y --gapps-zip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip
```

The installer checks the file, builds the Google services partition from it (10-30 seconds) and writes it in
the same visit to fastboot mode, after the system and before the data is erased. If the partition did not fit
or was not written, the installer deletes it and finishes the install without Google. Then install them the
second way.

**Later, on an installed Lumen OS** (the data is erased once more). Turn on debugging
([step 7](#hb-verify), items 1-3) and run:

```
bash lumen-install.sh --ip 192.168.x.y gapps --zip MindTheGapps-14.0.0-arm-ATV-full-20240523_191930.zip
```

Type `ERASE`, and when the installer asks, connect the projector's USB port to the computer with the A-to-A
cable. In one visit to fastboot mode it writes the partition `z9x_gapps`, erases the data and restarts the
projector.

Sign in to Google in the first setup or later in "Settings → Accounts". If Google refuses the sign-in or Play
says that the device is not certified, register the projector:

1. Connect the projector to the internet, turn on debugging ([step 7](#hb-verify), items 1-3), and in
   "Developer options" also "Rooted debugging".
2. Get the GSF ID:

   ```
   bash lumen-install.sh --ip 192.168.x.y gsf
   ```

3. Register it at [google.com/android/uncertified](https://www.google.com/android/uncertified), wait 10-30
   minutes and sign in in "Settings → Accounts".

The state of the Google services is shown by `bash lumen-install.sh --ip 192.168.x.y gapps`. To remove them
(this erases the data too): `bash lumen-install.sh --ip 192.168.x.y gapps --remove`. If Lumen OS failed to
start twice in a row with the Google services, the next start goes without them; the `gapps` command shows
that and offers to turn them on again (that needs "Rooted debugging" too).

<a name="hb-rescue"></a>

## Repair: Lumen OS does not start any more (your data stays)

If Lumen OS cannot finish starting, after a few minutes the projector goes into fastboot mode by itself and
waits there. `rescue` writes Lumen OS again into the slot the projector runs from, without erasing data:

1. Download the image of the newest release for the HB, both `SHA256SUMS` files and the installer archive, as
   in [step 2](#hb-files). An older system over newer data may not start.
2. Run `bash lumen-install.sh --device hb rescue` (Windows: `lumen-install.cmd rescue -Device hb`) and type
   `WRITE`.
3. When the installer asks, connect the projector's USB port to the computer with the A-to-A cable.

If an update over Wi-Fi is not finished yet, `rescue` writes nothing: a write would then destroy the way back.
The installer shows the commands that keep everything (most often `fastboot set_active` to the other slot,
then `fastboot reboot`) and leaves the projector in fastboot mode. Type them as shown.

<a name="hb-problems"></a>

## If something goes wrong

| Where | What the installer says | What to do |
|---|---|---|
| any step | adb did not start (port 5038 busy?) | Usually another program with adb already holds the projector: Android Studio, scrcpy, another adb. Close it (or run `adb kill-server` with its adb) and run the installer again. Nothing was done. |
| check | no adb connection to … over Wi-Fi | Is debugging on (step 1, item 2), are the projector and the computer on the same network, is the address right? Nothing was done. |
| check | the projector … did not allow debugging from this computer | On the projector allow debugging, tick "Always allow from this computer" and press OK. Run again. |
| check | this is not an XGIMI Z9X HB (model …, board …) | The installer is only for model G0130. Nothing was done. |
| check | XGIMI firmware … is not supported | It must be exactly V6.16.24. Nothing was done. If you have another version, write about it. |
| check | the XGIMI Z9X HB runs from slot …, or this projector has no virtual A/B | Nothing was done. Write about it and attach the installer's output. |
| check | … is not an image for the XGIMI Z9X HB, … is not a release file, … is Lumen OS …, but this installer download is for Lumen OS … | Not the right image. Download the image, both `SHA256SUMS` files and the installer archive from one HB release and do not rename them. Nothing was written. |
| check | SHA256SUMS is not signed with the Lumen OS key, or checksum mismatch | The files are damaged or not from GitHub. Download them again from one HB release. |
| flash | the running slot … still verifies its system (*-verity in /dev/block/mapper) | Do [step 4](#hb-vbmeta). Nothing was written. |
| blobs | … from the projector does not match the allow-list | See ["Two files from your projector"](#hb-blobs). Nothing was written. |
| flash | the projector did not appear on the cable within 3 minutes | Is the cable in the projector's USB port, directly, without a hub? On Windows install the Google USB Driver. On Linux `fastboot devices` must not say "no permissions" (udev rules). Run the installer again. |
| flash | fastboot mode check failed: an XGIMI update is staged or not finished (or: *-cow partitions) | The projector starts its old system, nothing was written. Let it start, turn automatic updates off, wait 10 minutes and run the installer again. Do not install the update. |
| flash | fastboot mode check failed (another reason) | The projector starts its old system. Pull the cable. Nothing was written. Write about it and attach the installer's output. |
| flash | no room in super for z9x_blobs_a, or the image does not fit into super | The projector starts its old system, nothing was written. Write about it and attach the installer's output. |
| flash | The write of this install is not finished: the projector stays in fastboot mode | Do not pull the cable and do not switch the projector off. Fix what is written before this and run the same command again: the installer continues in fastboot mode. |
| flash | system was not written completely, or the wipe (…) failed | The projector stays in fastboot mode. Do not pull the cable and run the installer again. |
| flash | …/hb-blobs-….img (the MediaTek / XGIMI files of this projector) is missing | If the projector starts its old system, run the installer again: it copies the files again. If the write has begun (the projector stays in fastboot mode), put the file back into `~/Lumen-backup/` or run again with `--no-blobs`. |
| flash | the projector is neither in fastboot mode on the cable nor reachable over the network | If the screen shows fastboot mode, check the cable and run again. If the projector's system runs, run again: the installer starts over. |
| flash | The power went off during the write | Switch the projector on. If it does not start, go [back to stock](#hb-stock). |
| second install | Done (and nothing else) | The installer remembers the last install. Reset the progress: `bash lumen-install.sh --device hb reset` (Windows: `lumen-install.cmd reset -Device hb`) and run again. |
| first start | The start hangs with the cable in | Pull the cable and press the power button. |
| first start | The lamp does not light up, the fans run, the projector restarts about every 20 seconds | Cut the power for 10 seconds and switch it on again. If it goes on like that, go [back to stock](#hb-stock) and write about it. |
| verify | sys.z9x.blobs=… (a line with `!`) | Send the lines with `!` and the installer's output. |
| rescue | 'rescue' wrote nothing: an update is not finished on slot … | Type the commands it showed above this line, see ["Repair"](#hb-rescue). The projector stays in fastboot mode. |
| Google | … is not the MindTheGapps file this installer accepts | Download exactly the file of ["Adding Google services"](#hb-google) and do not change it. Nothing was written. |
| Google | … and the Google services do not fit together, or not enough free space in super | Install Lumen OS without `--gapps-zip`. Nothing was written. Write about it and attach the installer's output. |
| Google | this installer has no erofs-utils for this computer | Google services are installed from a Mac or a computer with Linux. Nothing was written. |

The installer keeps the install progress in `~/.lumen-installer/hb/`. The command `bash lumen-install.sh status`
shows it, `bash lumen-install.sh --device hb reset` resets it. They do not touch the projector.

If nothing helps, write in the [4PDA topic](https://4pda.to/forum/index.php?showtopic=1121837) or in the
[issues on GitHub](https://github.com/kmuradoff/lumen-os/issues) and attach:

- the XGIMI firmware version and the Lumen OS version, if it is installed already;
- what you did and at which step everything stopped;
- the whole text from the terminal, not a screenshot;
- the output of `bash lumen-install.sh status`.

<a name="hb-stock"></a>

## Back to stock (erases everything)

The stock firmware is put back by XGIMI's own update from a USB stick.

1. Download XGIMI's official V6.16.24 firmware for model G0130 from XGIMI's server:
   [XGIMI_GOATELEGANT_XR03D.bin](https://product-library.static.xgimi.com/firmware/release/MTK9660_AOSP/dapeng/MTK9660_AOSP_GEMINI_6.16.24_20260428-152516/XGIMI_GOATELEGANT_XR03D.bin) (2.2 GB). A file for another model will not do.
   **Note: this is 6.16.24**, only for going back from Lumen OS, not for projectors on firmware V6.17.12.
2. A USB stick with an **MBR** partition scheme and **one FAT32 partition**. On macOS: "Disk Utility → View →
   Show All Devices", pick the stick itself (not its volume), "Erase", format "MS-DOS (FAT)", scheme "Master
   Boot Record".
3. Only two files on the stick: `XGIMI_GOATELEGANT_XR03D.bin` (with exactly this name) and an **empty** file
   `factory.txt`. On macOS copy in the terminal, because Finder adds hidden `._` files:

   ```
   cp -X ~/Downloads/XGIMI_GOATELEGANT_XR03D.bin /Volumes/STICK/
   ```

   ```
   touch /Volumes/STICK/factory.txt
   ```

4. Pull the A-to-A cable. Plug the stick into the projector's USB port (if nothing happens, try another port).
   Pull the power cable.
5. Hold the projector's power button, plug the power cable in and keep the button pressed for 6-7 seconds.
   Without the button the stick does nothing.
6. The restore takes a few minutes and erases everything. Do not cut the power. If the power does go off,
   leave the stick in the port and switch the projector on again. Then the stock system starts, as from the
   factory.
7. Take the stick out and delete `factory.txt` from it, so that the firmware is not installed again by
   accident.

After going back to stock the vbmeta check is on again. To install Lumen OS again, start at
[step 1](#hb-prepare), and before step 3 reset the progress of the last install:
`bash lumen-install.sh --device hb reset` (Windows: `lumen-install.cmd reset -Device hb`).

<a name="hb-changes"></a>

## What changes on the projector

| Partition | What happens |
|---|---|
| `system_a` | replaced by Lumen OS |
| `userdata`, `metadata` | formatted at the install |
| `z9x_blobs_a` (new, 4 MB) | two XGIMI files from your projector |
| `z9x_gapps_a` (new, about 370 MB) | only if you install Google services |
| `vbmeta` | changed only by you, at step 4 |
| boot, vendor, product, system_ext, dtbo, the other slot, calibration | the installer does not touch them |

Lumen OS is distributed under the Apache-2.0 licence. The release files contain no XGIMI, MediaTek or Google
programs.

I make Lumen OS on my own. XGIMI, Google and LineageOS have nothing to do with it. You install it at your own
risk.
