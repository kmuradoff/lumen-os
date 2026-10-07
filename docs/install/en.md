# Installing Lumen OS on the XGIMI Z9X

Lumen OS 1.0 · for XGIMI projectors · by kmuradoff

Lumen OS replaces XGIMI's Android on the **XGIMI Z9X** (model code `G0082`) with a fast Android TV 14
system (LineageOS 21 + Google TV services), a new home screen, and over-the-air updates.
XGIMI's firmware, the lamp, focus, keystone and your calibration stay exactly as they are.

> Lumen OS is an independent project. It is not made, approved or supported by XGIMI, Google or
> LineageOS. You install it at your own risk. The way back to stock is at the end of this guide.

**Time:** about 25 minutes. **Difficulty:** you type a few commands into a terminal.

```
 1 Prepare ──▶ 2 Check ──▶ 3 Back up ──▶ 4 vbmeta ──▶ 5 Install ──▶ 6 First start ──▶ 7 Google
   10 min       1 min       5 min        5 min        3 min         5 min             5 min
```

---

## Never do these (red box)

| Never | Why |
|---|---|
| `fastboot -w`, `fastboot erase userdata`, `fastboot erase metadata` | The Z9X then hangs at boot and can only be saved with the USB-stick stock restore. The installer wipes with `format` instead, which is safe. |
| Flash `vendor`, `boot`, `dtbo` or any partition the installer does not name | XGIMI firmware. Lumen OS never changes it. |
| Lock the bootloader (`fastboot flashing lock`) | The projector would refuse to start Lumen OS. |
| `adb reboot bootloader` | On the Z9X this simply switches the projector off. Use `adb reboot fastboot`. |
| Long breaks inside fastboot | XGIMI's fastboot mode leaves by itself after about 2 minutes. Each step is one short session. |
| Connect the stock system to Wi-Fi during the preparation (after step 1) | Stock installs XGIMI updates into the other slot by itself. |

---

## What you need

- The projector on **XGIMI firmware V6.15.58** (Settings > About on stock). Other versions are refused:
  let stock update itself over Wi-Fi first (step 1).
- A computer: **macOS or Linux** (fully supported) or **Windows 10/11** (beta: not tested yet).
- **Android SDK Platform-Tools 35 or newer** (adb + fastboot):
  https://developer.android.com/tools/releases/platform-tools  Unpack it and add the folder to PATH.
  Keep `make_f2fs` and `mke2fs` next to `fastboot` (they are in the zip): the safe data wipe needs them.
- A **USB A-to-A cable** for the projector's **USB 2.0** port.
- The Lumen OS release files in one folder: `lumen-os-<version>-system.img`, `SHA256SUMS`,
  `SHA256SUMS.sig` and the installer (`lumen-install.sh`, `lumen-install.cmd`, `lumen-install.ps1`,
  `lib/`, `certs/`, `release.conf`).
- For emergencies, a **USB stick for the stock restore** (see the end of this guide).

---

## 1. Prepare the projector (10 min)

1. On stock, make sure the firmware is **V6.15.58** (let it update over Wi-Fi if needed), then
   disconnect Wi-Fi.
2. **Remove your Google account** on stock (Settings > Accounts). Otherwise Factory Reset Protection
   may ask for it after the wipe.
3. Enable **USB debugging**. XGIMI hides the developer options; the common way is to install
   *SystemUI Tuner* from a USB stick (if the file manager refuses `.apk`, rename the file to `.apk1`,
   as described in the community thread), open its developer options and turn on USB debugging.
4. **Restart the projector** (USB debugging starts working only after a restart).
5. Connect the A-to-A cable to the projector's **USB 2.0** port and to the computer.

## 2. Check (1 min, writes nothing)

macOS / Linux:

```
cd lumen-os-1.0
bash lumen-install.sh check
```

Windows: `lumen-install.cmd check`

On the projector, confirm **Allow USB debugging** with the remote (tick "Always allow").
The check verifies the model (G0082), the XGIMI firmware, the tools, and the image: its checksum and
the Lumen OS signature on `SHA256SUMS`. Nothing is written.

You can also simply run `bash lumen-install.sh` (no command): it goes through steps 2 to 6 in order,
remembers where it stopped, and continues there next time.

## 3. Back up (optional, 5 min)

```
bash lumen-install.sh backup
```

Copies your installed apps (APK files), Download/Movies/Music/Pictures/DCIM/Documents and the
settings lists to `~/Lumen-backup/<date>/`. App data (logins, progress) cannot be copied without root.

<a name="vbmeta"></a>

## 4. vbmeta: the one manual step (5 min)

Lumen OS is not signed by XGIMI. The projector therefore has to be told **once** not to verify its
system partition. This is the standard step for every Android "GSI" system, and **you do it yourself**
with Google's official instructions. The installer never runs any vbmeta command.

1. Open Google's guide: https://source.android.com/docs/core/tests/vts/gsi#flashing-gsis, section
   **"Requirements for flashing GSIs"**.
2. Enter fastboot with `adb reboot fastboot` (not `bootloader`).
3. Flash Google's unchanged `vbmeta.img` **with verification disabled**, exactly as the guide shows,
   **for both slots, A and B**, in this one session. Both slots matter: then later updates over Wi-Fi
   (which install into the other slot) need nothing more.
4. Restart. Stock starts as usual. Connect the cable again.

Then answer **yes** when the installer asks whether this step is done.

**Already on Lumen OS?** Settings > About > Lumen OS update tells you if the other slot still needs
this step ("One-time step needed for updates"). It reads the slot's vbmeta header; it never writes it.

## 5. Install (3 min, ERASES ALL DATA)

```
bash lumen-install.sh flash
```

The installer first checks the image: `SHA256SUMS` and `SHA256SUMS.sig` from the same release must sit
next to it, the signature must verify with the Lumen OS certificate inside the installer, and the
checksum must match. Without them it stops (only the owner's own builds use `--unsigned-image`, which
asks you to type `UNSIGNED`). Type `ERASE` when asked; the question also names the XGIMI partitions
that are removed. In one fastboot session (well under the 2-minute limit) the installer:

1. removes leftovers of unfinished updates (`*-cow`) and XGIMI's unused `product`/`system_ext`
   partitions of the current slot;
2. writes **system** (about 40 seconds);
3. public images only: writes the protected-video components into `z9x_blobs` (see below);
4. wipes your data the safe way: `fastboot format` of userdata (with the same file-system features as
   stock), metadata and cache. Never `-w`, never `erase`;
5. restarts the projector.

System is always written **before** the wipe: if fastboot ends early, the projector still starts
(Lumen OS on the old data) and you simply run `flash` again.

The installer touches **only** system, z9x_blobs, userdata, metadata and cache of the current slot.
XGIMI's calibration partitions (persist, xgimi*), firmware, vendor, boot, vbmeta and the other slot are
never touched. On a projector that already runs Lumen/Z9X OS it also records checksums of the
calibration partitions before and after (read-only) and tells you they are unchanged.

**Protected video (public images).** Kinopoisk HD and other DRM video need MediaTek codec files that
we are not allowed to share. The `blobs` step copies them from **your own** projector's stock system,
checks each one against a list of known checksums, and stores them in a small partition
(`z9x_blobs`). They survive updates and factory resets. A copy is kept in
`~/Lumen-backup/blobs-<serial>.img`. The owner's private build carries them in the image itself
(images named `lumen-os-<version>-PRIVATE-system.img`, installed with `--blobs=embedded`), so this step
is skipped there. The published installer defaults to `BLOBS=partition` and refuses a mismatch it can
see. If your firmware's codec files are not on the list (the `blobs` step stops and says so), you can
still install with `--no-blobs` (Windows: `-NoBlobs`): everything works except protected video.

**If the wipe ever fails** (`format` was not accepted): the projector starts Lumen OS on the old data.
Do *Settings > Device preferences > Reset > Factory reset* on the projector instead
(or run the installer with `--wipe=factory-reset`).

## 6. First start (5 min)

The first start takes 2 to 4 minutes.
The setup asks, in this order: **language**, Wi-Fi, the remote, Google sign-in (can be skipped),
autofocus and keystone, and the home screen (Lumen Home or the classic Android TV launcher).

After a wipe USB debugging is off (Lumen OS ships with it off and with adb authentication on), so
`bash lumen-install.sh verify` can only check the system once you enable it. That is optional: on the
projector open *Settings > Device preferences > About*, press OK on *Build* seven times, then
*Settings > Device preferences > Developer options > USB debugging*. Connect the cable and accept the
"Allow USB debugging?" question on the projector (tick "Always allow from this computer").

## 7. Google sign-in (5 min)

Lumen OS is not on Google's list of certified devices yet, so register your device once:

```
bash lumen-install.sh gsf
```

shows your *GSF ID* (needs USB debugging and a few minutes of internet). Enter it at
https://www.google.com/android/uncertified with your Google account, wait 10 to 30 minutes, then sign
in on the projector (Settings > Accounts, or the setup's Google step).

---

## Updates over Wi-Fi

*Settings > Device preferences > About > Lumen OS update* (or the "Update ready" badge on Lumen Home).

- Updates are signed by the Lumen OS release key; anything else is refused.
- The default is **download automatically, install only after you say so**. You can switch to
  "Only tell me" or "Off".
- An update is written to the **other** slot while you keep watching. Restart when you like; if the
  new version does not start properly, the projector automatically returns to the previous one
  ("The update could not start. Lumen OS x.y was restored").
- The **first** update into a slot asks once whether XGIMI's firmware may be **copied unchanged** to
  that slot (boot, bootloader, display and audio firmware, configuration). XGIMI's own updates do the
  same; nothing is modified, decrypted or sent anywhere, and vbmeta is never written.
- Offline: copy `update-stable.json`, `update-stable.json.sig`, the `-ota.zip` and its
  `-payload_metadata.bin` to the root of a USB stick and choose *Install from a USB stick*.

---

## Something went wrong

| Where | What you see | What to do |
|---|---|---|
| check | "exactly one projector must be connected" | USB debugging on? Projector restarted after enabling it? A-to-A cable in the **USB 2.0** port? Confirmed the prompt on screen? `adb devices` must list exactly one device. |
| check | "firmware ... is not supported" | Let stock update to V6.15.58 over Wi-Fi, then run again. |
| check | "checksum mismatch" / "not signed by Lumen OS" | Download the release again. Never install an image that fails this. |
| check | "make_f2fs/mke2fs missing" | Use the official Platform-Tools zip; keep its files together. |
| flash | "did not appear in fastboot" | Wait, unplug and replug the cable, run `flash` again. Windows: install the Google USB driver. |
| flash | "an update is still being finished" | Let the projector start, wait 10 minutes, try again. |
| flash | "system was not written" | Nothing changed: the old system still starts. Try again; check the cable. |
| first start | Restarts in a loop | The vbmeta step (section 4) was probably not done for this slot. A looping projector has no USB access: use the stock restore below, then start again from step 1. |
| first start | Setup asks for the old Google account | Factory Reset Protection: sign in with the account that was on stock. |
| Google | "Device is not Play Protect certified" | Register the GSF ID (step 7) and wait up to 30 minutes. |
| updates | "One-time step needed for updates" | The other slot needs the vbmeta step (section 4), done with a computer. |
| updates | "Protected-video components are missing" | Run `bash lumen-install.sh blobs` and `bash lumen-install.sh flash --keep-data` (public images). |

The installer keeps its progress in `~/.lumen-installer/`; `bash lumen-install.sh status` shows it,
`reset` forgets it.

## Back to stock (erases everything)

1. Get the stock firmware `.bin` for the Z9X from XGIMI support (we never host it).
2. Prepare a USB stick: **FAT32, MBR**. Copy the `.bin` and an empty file named `factory.txt` to it.
3. Unplug the A-to-A cable. Insert the stick into the **USB 2.0** port.
4. Switch the projector on while holding the power button for 6 to 7 seconds.
5. The restore takes about 4 minutes and wipes everything. Stock starts afterwards.

---

## For the curious: what exactly changes

| Part | Changed? |
|---|---|
| `system` (current slot) | Replaced by Lumen OS |
| `userdata`, `metadata`, `cache` | Wiped (formatted) once at install |
| `z9x_blobs` (new, 4 MB, public images) | Your own MediaTek codec files |
| `product`, `system_ext` (XGIMI, unused by Lumen OS) | Removed at install (164 MB freed) |
| `vbmeta` | Only by you, by hand, with Google's instructions |
| boot, vendor, dtbo, mboot, optee, tvconfig, persist, xgimi*, calibration | Never |
| Other slot | Only by a Lumen OS update you confirm (system written, XGIMI firmware copied unchanged) |

Licenses: Lumen OS is Apache-2.0 (the AirPlay receiver GPL-3.0). It contains AOSP and LineageOS
(Apache-2.0 and others) and Google's apps (Google's terms). No XGIMI or MediaTek software is shipped
in public files.
