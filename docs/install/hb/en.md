<a name="hb"></a>

# Installing Lumen OS on the XGIMI Z9X HB

[Русская версия](ru.md)

> [!NOTE]
> This guide is for the XGIMI Z9X HB (model code G0130). The plain XGIMI Z9X (G0082) and the XGIMI Play 6
> have their own guides and their own release files: [Z9X](../z9x/en.md), [Play 6](../play6/en.md).

This guide covers the test build Lumen OS 1.0.7 beta 1 on the XGIMI Z9X HB (model code G0130) with XGIMI
firmware V6.16.24. Lumen OS is built on LineageOS 21, it is Android TV 14. It replaces only the system
partition. XGIMI's firmware, boot, vendor and calibration are not touched.

> [!CAUTION]
> **This is a test build.** It was tested on one projector, the tester's. Something may not work on yours.
> The install **erases all data on the projector**. Google services and updates over Wi-Fi are not there yet.
> Going back to stock needs XGIMI's firmware file, which we do not host: XGIMI support gives it out, see
> [Back to stock](#hb-stock). Ask for it before you install.

The install takes about 30 minutes. You type a few commands in a terminal, and you do one step in
fastboot mode (vbmeta) yourself, without the installer.

What is different from the plain Z9X:

- On stock the HB has debugging over the network only: in normal Android the projector's USB port works as
  a port for USB sticks. So the computer talks to the projector over Wi-Fi.
- The USB A-to-A cable is needed only in fastboot mode: there the same USB port becomes a port for the
  computer. Plug the cable in when this guide or the installer asks.
- The HB's fastboot mode does not drop out after a couple of minutes as on the Z9X. There is no rush.

<a name="hb-need"></a>

## What you need

- An XGIMI Z9X HB (model code G0130) on XGIMI firmware V6.16.24. On stock, Settings > About shows the model
  and the firmware version. The installer also checks the model G0130 and the firmware V6.16.24 by itself and
  does nothing on another model or version. If About says G0082, you have a plain Z9X: use the
  [Z9X guide](../z9x/en.md).
- A Wi-Fi network that the projector and the computer are both in. A guest network that keeps devices apart
  does not work.
- A computer with macOS or Linux. There is a Windows 10/11 installer, but it has not been tested yet.
- Internet access on the first run: the installer downloads Google's Platform-Tools (adb and fastboot) into
  `tools/platform-tools` next to itself, checks them and uses only that copy. Without internet, see the Z9X
  guide, [What you need](../z9x/en.md#what-you-need).
- A USB A-to-A cable (plain USB-A on both ends).
- 4 GB of free space on the computer.
- A USB stick in case you go back to stock.

On Windows, run `lumen-install.cmd` instead of `bash lumen-install.sh`, with `-Ip` instead of `--ip` and the
options after the command, for example `lumen-install.cmd check -Ip 192.168.x.y`. Keep the installer folder on
the computer's own disk (for example `C:\lumen`), not on a USB stick. For fastboot mode on the cable Windows may
need the [Google USB Driver](https://developer.android.com/studio/run/win-usb).

<a name="hb-prepare"></a>

## 1. Prepare the projector (10 min)

1. On stock, open Settings > About and check the model (G0130) and the firmware version (V6.16.24).
2. Connect the projector to your Wi-Fi. If stock offers a system update, do not accept it: the installer
   knows only V6.16.24, and a downloaded update waits for a restart and gets in the way of the install.
3. Turn on debugging. In Settings > About, press OK on the build number until Developer options open. There,
   turn on USB debugging. On the HB's stock this turns on debugging over the network at once (port 5555). On
   the projector we tested, no question appeared on the screen.
4. Note the projector's IP address. The stock network settings show it on the page of the Wi-Fi network it
   is connected to. Your router's list of devices shows it too.
5. Have the A-to-A cable ready, but do not plug it in yet.

The menu names on stock may differ a little, depending on the language and the firmware version.

<a name="hb-files"></a>

## 2. Download the files (5 min)

1. Open the [Releases page](https://github.com/kmuradoff/lumen-os/releases) and choose the release for the
   XGIMI Z9X HB: the tag `hb-1.0.7-beta1`, marked Pre-release. The release marked "Latest" is the one for the
   plain Z9X.
2. Download four files: `lumen-os-hb-1.0.7-beta1-nogms-system.img`, `SHA256SUMS`, `SHA256SUMS.sig` and
   `lumen-os-hb-1.0.7-beta1-installer.zip`.
3. Unpack the installer zip and put the image and both `SHA256SUMS` files next to `lumen-install.sh`.
4. Open a terminal in that folder, as in [Download the files](../z9x/en.md#2-download-the-files-5-min) for the Z9X.

The files of different projectors do not mix: the installer checks the image with its own projector's key
and refuses an image of another one.

<details>
<summary>Check the signature by hand</summary>

```
openssl x509 -in certs/hb/ota.x509.pem -pubkey -noout > lumen.pub
```

```
openssl dgst -sha256 -verify lumen.pub -signature SHA256SUMS.sig SHA256SUMS
```

The second command must print `Verified OK`. The certificate fingerprint
(`openssl x509 -in certs/hb/ota.x509.pem -noout -fingerprint -sha256`) must match the `ota/ota` line in the
repository's `devices/goat_elegant/certs/FINGERPRINTS.txt`:
`8E:47:E4:95:F9:FC:C5:DB:46:4F:52:C5:78:20:9F:DA:3A:9E:47:FF:80:AA:FC:E6:F7:12:5D:2C:51:A9:86:24`.

</details>

<a name="hb-check"></a>

## 3. Run the check (2 min, writes nothing)

Replace `192.168.x.y` with your projector's address:

```
bash lumen-install.sh --ip 192.168.x.y check
```

Windows: `lumen-install.cmd check -Ip 192.168.x.y`

The installer connects over Wi-Fi and checks the model, the XGIMI firmware, the slot, the tools on the
computer and the image: that it is from the same release as the installer, its signature and its checksum.
It also tells you whether the vbmeta step is done yet. Before step 4 it is normal that it is not. At the end
you should see a line saying that the check passed and nothing was written.

If the installer prints `STOP`, look up its message in [Something went wrong](#hb-problems).

<a name="vbmeta"></a>
<a name="hb-vbmeta"></a>

## 4. Turn off vbmeta verification (5 min)

You do this step yourself. The installer never runs a vbmeta command. `bash lumen-install.sh vbmeta` only
reminds you what to do.

If you do not have adb and fastboot installed, use the ones the installer downloaded: type
`tools/platform-tools/adb` instead of `adb` and `tools/platform-tools/fastboot` instead of `fastboot`
(Windows: `tools\platform-tools\adb.exe` and `tools\platform-tools\fastboot.exe`).

1. From [Google's GSI page](https://developer.android.com/topic/generic-system-image/releases), download any
   arm64 zip, for example `aosp_arm64`, and take `vbmeta.img` out of it. It is the same file as for the Z9X,
   and it was tested on the HB. Put it in the installer folder.
2. Connect to the projector over the network:

   ```
   adb connect 192.168.x.y:5555
   ```

   Then check:

   ```
   adb devices
   ```

   You should see a line `192.168.x.y:5555` that ends in `device`.
3. Take whatever is in the projector's USB port out and connect that port to the computer with the A-to-A
   cable.
4. Put the projector into fastboot mode:

   ```
   adb -s 192.168.x.y:5555 reboot fastboot
   ```

   After 20 to 40 seconds, check:

   ```
   fastboot devices
   ```

   You should see one line that ends in `fastboot`.
5. Turn off verification on both slots at once:

   ```
   fastboot --disable-verity --disable-verification --slot=all flash vbmeta vbmeta.img
   ```

   The output must show `Writing 'vbmeta_a'` and `Writing 'vbmeta_b'`, both with `OKAY`.
6. Restart the projector:

   ```
   fastboot reboot
   ```

   Stock must start as usual, with your data. If the start hangs with the cable in, unplug the cable and
   press the power button.
7. Unplug the cable if it is still in, and run the check again (step 3). Now it should say that vbmeta
   verification is off.

Lumen OS is not signed by XGIMI, so the projector must stop verifying the system partition. This is the
standard step for any GSI; Google describes it in
[Requirements for flashing GSIs](https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis).
Stock works as before after this step.

<a name="hb-install"></a>

## 5. Install Lumen OS (about 10 minutes, erases all data)

```
bash lumen-install.sh --ip 192.168.x.y
```

Windows: `lumen-install.cmd -Ip 192.168.x.y`

The installer goes through the steps in order by itself. If it stops somewhere, fix what it says and run the
same command again: it goes on from where it stopped.

1. If the check (step 3) has passed already, it does not run it again; otherwise it runs it now. Then it
   offers a backup of your apps and files (see
   [step 5](../z9x/en.md#5-optional-back-up-apps-and-files-5-min) of the Z9X guide: the same, over Wi-Fi).
2. It asks about the vbmeta step only if the check did not already see that it is done. Then answer `yes`
   if you did step 4 and stock started after it.
3. It copies two XGIMI files from the projector (see below) and keeps a copy on the computer.
4. Before the write it checks the projector again (model, firmware, slot, the vbmeta step) and the image
   (signature and checksum, about a minute). Without the vbmeta step it writes nothing. Then it shows what
   will be erased. Apps, accounts, settings and files are erased. Type `ERASE` to go on.
5. It asks you to take everything out of the projector's USB port and connect that port to the computer
   with the A-to-A cable. Do that and press Enter.
6. From here the installer works without you: it restarts the projector into fastboot mode over Wi-Fi,
   checks it there, writes the XGIMI files into a small partition, then the system, formats the data and
   restarts the projector. Do not unplug the cable or the power now.
7. When the installer says that everything is written, unplug the cable. If the start hangs with the cable
   in, unplug it and press the power button.

<a name="hb-blobs"></a>

### Two files from your own projector

We are not allowed to publish XGIMI's and MediaTek's files, so they are not in the image. Before the write,
the installer copies two of them from your own projector: MediaTek's video library (without it there is no
hardware video decoding, and the HDMI picture may freeze) and XGIMI's sound settings file (without it only
the built-in speaker works). It checks each file against a checksum, and Lumen OS checks them again at every
start. The files go into a small partition, `z9x_blobs` (4 MB), and a copy stays on the computer in
`~/Lumen-backup/hb-blobs-<serial>.img`.

If the installer says a file does not match its checksum, it does not know your firmware's files. You can
still install without them by adding `--no-blobs`, but then you lose what is written above. Only do that to
look around. Better open a [GitHub issue](https://github.com/kmuradoff/lumen-os/issues) first and attach the
installer output.

<a name="hb-first-start"></a>

## 6. First start

The data is erased, the Wi-Fi network too. The first start after the wipe takes longer than usual, up to
about a minute and a half on the projector we tested. Then Lumen Setup opens: remote, language, Wi-Fi. Go
through it.

In Lumen OS the USB port works for USB sticks again. Apps install from a stick as on the Z9X, see
[Install apps](../z9x/en.md#9-install-apps).

<a name="hb-verify"></a>

## 7. Optional: verify the install

1. Connect to Wi-Fi (step 6).
2. Open Settings > Device Preferences > About and press OK on Build 7 times.
3. Open Settings > Device Preferences > Developer options and turn on USB debugging. On the HB this is
   debugging over the network.
4. Look up the address in Settings > Network & Internet, on the page of your network.
5. Run:

   ```
   bash lumen-install.sh --ip 192.168.x.y verify
   ```

   The projector asks "Allow USB debugging?". Tick "Always allow from this computer" and press OK.

The installer shows the Lumen OS version and whether the XGIMI files are in place. Lines with `✓` are fine.
Send the lines with `!` along with your question. You can turn debugging off again afterwards.

<a name="hb-missing"></a>

## Not there yet

- Google services. They are not in the image, and the installer cannot add them on the HB yet. The `gapps`
  step answers that it is not available for the HB yet.
- Updates over Wi-Fi. They are planned for the first full release. Until then a new version goes on with the
  installer of its release, over the cable. The installer remembers the last install and, without a reset,
  only says "Done". So reset the progress first: `bash lumen-install.sh --device hb reset` (Windows:
  `lumen-install.cmd reset -Device hb`), then install as in [step 5](#hb-install). The write command
  works too: `bash lumen-install.sh --ip 192.168.x.y flash`.
- The AirPlay receiver.
- Rescue from fastboot mode without data loss (`rescue`) and the stock restore stick check (`stick`): on the
  HB these commands do nothing yet.

<a name="hb-problems"></a>

## Something went wrong

| Where | What the installer says | What to do |
|---|---|---|
| any step | adb did not start (port 5038 busy?) | Usually another program with adb holds the projector: Android Studio, scrcpy, another adb. Close it (or run `adb kill-server` with its adb) and run the installer again. Nothing was done. |
| check | no adb connection to … over Wi-Fi | Is debugging on (step 1, item 3), are the projector and the computer in the same network, is the address right? Nothing was done. |
| check | the projector at … did not allow this computer | On the projector allow debugging, tick "Always allow from this computer" and press OK. Run it again. |
| check | this is not an XGIMI Z9X HB (model …, board …) | This guide is for the G0130 only. The plain Z9X and the Play 6 have their own guides. Nothing was done. |
| check | XGIMI firmware … is not supported | You need exactly V6.16.24. Nothing was done. If yours is another version, tell us. |
| check | the XGIMI Z9X HB runs from slot …, or this projector has no virtual A/B | Nothing was done. Open an issue and attach the installer output. |
| check | … is not an image for the XGIMI Z9X HB, … is not a release file, … is Lumen OS …, but this installer download is for Lumen OS … | Wrong image. Download the image, both `SHA256SUMS` files and the installer zip from one HB release and do not rename them. Nothing was written. |
| check | SHA256SUMS is not signed with the Lumen OS key, or checksum mismatch | Damaged files or not from GitHub. Download them again from one HB release. |
| flash | the running slot … still verifies its system (*-verity in /dev/block/mapper) | Do [step 4](#hb-vbmeta). Nothing was written. |
| blobs | … from the projector does not match the allow-list | See [Two files from your own projector](#hb-blobs). Nothing was written. |
| flash | the projector did not appear on the cable within 3 minutes | Is the cable in the projector's USB port, directly, without a hub? On Windows install the Google USB Driver. On Linux `fastboot devices` must not say "no permissions" (udev rules). Run the installer again. |
| flash | fastboot mode check failed: an XGIMI update is staged or not finished (or: *-cow partitions) | The projector starts its old system, nothing was written. Let it start, turn automatic updates off, wait 10 minutes and run the installer again. Do not install the update. |
| flash | fastboot mode check failed (another reason) | The projector starts its old system. Unplug the cable. Nothing was written. Open an issue and attach the installer output. |
| flash | no room in super for z9x_blobs_a, or the image does not fit into super | The projector starts its old system, nothing was written. Open an issue and attach the installer output. |
| flash | The write of this install is not finished: the projector stays in fastboot mode | Keep the cable in and the projector on. Fix what is said before it and run the same command again: the installer goes on in fastboot mode. |
| flash | system was not written completely, or the wipe (…) failed | The projector stays in fastboot mode. Keep the cable in and run the installer again. |
| flash | …/hb-blobs-….img (the MediaTek / XGIMI files of this projector) is missing | If the projector starts its old system, run the installer again: it copies the files again. If the write has begun (the projector stays in fastboot mode), put the file back into `~/Lumen-backup/` or run again with `--no-blobs` (no sound, no MediaTek video decoders). |
| flash | the projector is neither in fastboot mode on the cable nor reachable over the network | If its screen shows fastboot mode, check the cable and run it again. If its system runs, run it again: the installer starts over. |
| flash | The power went off during the write | Switch the projector on. If it does not start, go [back to stock](#hb-stock). |
| second install | Done (and nothing else) | The installer remembers the last install. Reset the progress: `bash lumen-install.sh --device hb reset` (Windows: `lumen-install.cmd reset -Device hb`) and run it again. |
| first start | The start hangs with the cable in | Unplug the cable and press the power button. |
| first start | The lamp never lights, the fans run, the projector restarts about every 20 seconds | Cut the power for 10 seconds and switch on again. If it goes on, go [back to stock](#hb-stock) and tell us. |
| verify | sys.z9x.blobs=… (a line with `!`) | Send the lines with `!` and the installer output. |
| gapps, rescue, stick | '…' is not available for the XGIMI Z9X HB yet | That is right, see [Not there yet](#hb-missing). Nothing was done. |

The installer keeps its HB progress in `~/.lumen-installer/hb/`. `bash lumen-install.sh status` shows it,
`bash lumen-install.sh --device hb reset` resets it. Neither touches the projector.

If nothing helped, open an [issue on GitHub](https://github.com/kmuradoff/lumen-os/issues) and attach the same
as for the Z9X, see [If nothing helped](../z9x/en.md#if-nothing-helped).

<a name="hb-stock"></a>

## Back to stock (erases everything)

XGIMI's own update from a USB stick puts the stock firmware back. This worked on the HB we tested.

1. Ask XGIMI support for the firmware for the model G0130. The file is named `XGIMI_GOATELEGANT_XR03D.bin`.
   We do not host it. Never use a file for another model (for example the plain Z9X's `.bin`).
2. A USB stick with an **MBR** partition table and **one FAT32 partition**. On macOS: Disk Utility > View >
   Show All Devices, select the stick itself (not its volume), Erase, format "MS-DOS (FAT)", scheme "Master
   Boot Record".
3. Only two files on the stick: `XGIMI_GOATELEGANT_XR03D.bin` (with exactly that name) and an **empty** file
   `factory.txt`. On macOS copy in the terminal, because Finder adds hidden `._` files:

   ```
   cp -X ~/Downloads/XGIMI_GOATELEGANT_XR03D.bin /Volumes/STICK/
   ```

   ```
   touch /Volumes/STICK/factory.txt
   ```

4. Unplug the A-to-A cable. Plug the stick into the projector's USB port (if nothing happens, try another
   port). Unplug the power cable.
5. Hold the projector's power button, plug the power cable in and keep holding the button for 6 to 7
   seconds. Without the button the stick does nothing.
6. The restore takes a few minutes and erases everything. Do not cut the power. If the power is lost anyway,
   leave the stick in and switch the projector on again. Then stock starts as from the factory.
7. Take the stick out and delete `factory.txt` from it, so the firmware does not go on again by accident.

After the restore vbmeta verification is on again. If you want Lumen OS back later, start again from
[step 1](#hb-prepare). Before step 3 reset the progress of the last install on this computer:
`bash lumen-install.sh --device hb reset` (Windows: `lumen-install.cmd reset -Device hb`). Without the reset
the installer takes everything as done and only says "Done". If XGIMI gives you firmware newer than V6.16.24, the installer does not accept it yet.

<a name="hb-changes"></a>

## What changes on the projector

| Partition | What happens |
|---|---|
| `system_a` | replaced by Lumen OS |
| `userdata`, `metadata` | formatted at the install |
| `z9x_blobs_a` (new, 4 MB) | the two XGIMI files from your projector |
| `vbmeta` | changed only by you, in step 4 |
| boot, vendor, product, system_ext, dtbo, the other slot, calibration | the installer does not touch them |

Lumen OS is licensed under Apache-2.0. No XGIMI, MediaTek or Google software is in the release files.

Lumen OS is an independent project. It is not made or supported by XGIMI, Google or LineageOS, and you
install it at your own risk.
