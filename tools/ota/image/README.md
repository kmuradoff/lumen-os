# OTA pieces for the image (L-OTA -> L-SYSTEM, L-PROJECTOR)

L-OTA owns these files; L-SYSTEM copies them into the image from `tools/lumen_v1.sh`
(unchanged bytes: `tools/sign/check_image.py` compares them). Nothing here touches boot, vbmeta,
vendor or any XGIMI partition.

## 1. Files for EVERY Lumen image (v1 included)

| Source (`tools/ota/image/`) | Image path | Mode | Label | Why |
|---|---|---|---|---|
| `z9x_ota.sh` | `system/etc/z9x/z9x_ota.sh` | 0755 | system_file | OTA boot gate, slot facts |
| `z9x_ota.rc` | `system/etc/init/z9x_ota.rc` | 0644 | system_file | runs the gate |
| `update_verifier.rc` | `system/etc/init/update_verifier.rc` | 0644 | system_file | **replaces** the base member (same service name; stock behaviour on normal boots) |
| `z9x_rescue.sh` | `system/etc/z9x/z9x_rescue.sh` | 0755 | system_file | boot rescue (1.0.1): 3 failed starts in a row -> fastbootd |
| `z9x_rescue.rc` | `system/etc/init/z9x_rescue.rc` | 0644 | system_file | runs it (`exec` at `on init`, oneshot at boot_completed) |
| `z9x-updater-default-permissions.xml` | `system/product/etc/default-permissions/z9x-updater.xml` | 0644 | system_file | POST_NOTIFICATIONS pre-grant |
| `apps/Z9xUpdater` APK (`build_apk.sh`, VERSION_CODE=100 VERSION_NAME=1.0) | `system/app/Z9xUpdater/Z9xUpdater.apk` (dir 0755) | 0644 | system_file | updater; `/system/app`, NOT priv-app, platform-signed, not persistent |

`system/etc/z9x/` is a new directory (0755, system_file) if L-SYSTEM has not created it yet.

Props: append `ota_props.txt` to the **product** build.prop snippet (replace `<DATE>`).
Sysconfig (`system/etc/sysconfig/z9x.xml`, PLAN C6), add:

```xml
<initial-package-state package="org.z9x.updater" stopped="false" />
```

No privapp-permissions XML is needed (not priv-app; every permission it uses is `signature`
or normal).

Spec lines for `verify_tar` (if the v65 style is kept):

```
A system/etc/z9x/z9x_ota.sh 755
A system/etc/init/z9x_ota.rc 644
A system/etc/z9x/z9x_rescue.sh 755
A system/etc/init/z9x_rescue.rc 644
M system/etc/init/update_verifier.rc
T system/product/etc/build.prop ro.product.ab_ota_partitions=boot,bootdata,dtbo,mboot,optee,owl,satf,tvconfig,tvservice,vbmeta_system,xgimiconfig
T system/product/etc/build.prop ro.z9x.keys=release
T system/product/etc/build.prop ro.z9x.ota.gate=1
T system/product/etc/build.prop ro.z9x.rescue=1
```

**1.0.1 (boot rescue), for L-SYSTEM / the integrator:** `tools/lumen_v1.sh` must add the two new
files exactly like `z9x_ota.{sh,rc}` (`add system/etc/z9x/z9x_rescue.sh "$OTAIMG/z9x_rescue.sh" 755`,
`add system/etc/init/z9x_rescue.rc "$OTAIMG/z9x_rescue.rc" 644`, plus `need` checks), and
`tools/sign/check_image.py` `OTA_FILES` must list
`"z9x_rescue.rc": "system/etc/init/z9x_rescue.rc"` and `"z9x_rescue.sh": "system/etc/z9x/z9x_rescue.sh"`
(byte-identical check like the gate). `ota_props.txt` now carries `ro.z9x.rescue=1` (kill switch,
0 = off). If `lumen_v1.sh` also lints `z9x_rescue.sh`: its "writes a block device / vbmeta" grep for
`z9x_ota.sh` matches the bare word `fastboot`, and `z9x_rescue.sh` necessarily runs
`setprop sys.powerctl reboot,fastboot` (init's reboot into fastbootd, no block device), so use that
grep without the `fastboot` alternative for it. `shlint` should also run `ksh -n` where ksh exists
(the device shell, mksh, is a ksh; 1.0.1 review found a pattern only ksh rejects). Host tests: `sh tools/ota/test/run.sh` (gate + rescue, stubbed) and
`bash tools/ota/test/installer_rescue.sh` (installer `rescue`, fake fastboot).

`ro.z9x.keys=release` is true only after `tools/sign/sign_tar.py` ran on the Mac; the laptop tar
is the *input* of signing, the flashed image is always the signed one (`tools/sign/sign_release.sh`).

## 2. Files for PUBLIC images only (not the owner's v1)

Implemented in 1.0.1 by `tools/lumen_v1.sh VARIANT=public` (2026-10-09). The owner's image keeps the
MediaTek Codec2 libs and the base's XGIMI / MediaTek audio files inside `/system` (as v6.5). A public
image must not: it gets all nine from the user's own system via the installer (`installer/`, `blobs`
step: stock or a running Lumen OS) into the logical partition `z9x_blobs_<slot>`, and `z9x_blobs.sh`
bind-mounts them at boot (post-fs-data exec, after init.rc's part: default mount namespace, APEXes
active; before `on boot`, where audioserver, the vendor audio HAL and the codecs start).

| Source | Image path | Mode | Label |
|---|---|---|---|
| `z9x_blobs.sh` | `system/etc/z9x/z9x_blobs.sh` | 0755 | system_file |
| `z9x_blobs.rc` | `system/etc/init/z9x_blobs.rc` | 0644 | system_file |
| `blobs_allow.txt` | `system/etc/z9x/blobs_allow.txt` | 0644 | system_file |
| `xgimi_compat_public.rc` | `system/etc/init/xgimi_compat.rc` (**replaces** the base member: the same file without the three binds below) | 0644 | system_file |
| 0-byte files | the 6 placeholder paths of `blobs_allow.txt` under `system/` (`system/system_ext/lib{,64}/libc2plugin_store.so`, `.../vendor.mediatek.hardware.c2.info@1.0.so`, `system/lib{,64}/libcodec2_soft_common.so`) | 0644 | **system_lib_file** |
| removed | `system/etc/xgimi/audio_policy_configuration.xml`, `system/etc/xgimi/libstagefright_foundation{,64}.so` (base members) | | |

The three removed files are the `bind=` lines of `blobs_allow.txt`: `z9x_blobs.sh` binds the user's copy
straight over `/vendor/etc/audio_policy_configuration.xml` and
`/apex/com.android.vndk.v34/lib{,64}/libstagefright_foundation.so` (what the base's xgimi_compat.rc does
from `/system/etc/xgimi` on the private image); without them the vendor audio HAL's primary module does
not load (**no sound**), so a public install without `z9x_blobs` has no sound and no secure video. The
audio policy is XGIMI's stock file without the subwoofer port and AV3A profiles: the installer derives it
from the stock original by deleting lines (`from=<sha256>:<sed 'N,Md'>`), a running Lumen OS already
has it. Set id `z9x-v61558-b` (9 files; `mtk-c2-v61558-a` was the 6 codec libs).

`z9x_blobs.sh` works in its own tmpfs `/mnt/z9x_blobs` (required: `/mnt` is noexec and a bind keeps the
flags of its source mount), refuses a partition with an absolute or `..` member name (`bad:path`) or with
a link / device / fifo member or any entry that is not a directory or a regular file of at most 4 MiB
(`bad:entry`: checked before extracting, and again before hashing, against sparse giants), and
remounts the tmpfs read-only after the binds. Result `sys.z9x.blobs` = `ok` | `missing` | `partial` |
`bad:<why>` (`tmpfs`, `read`, `path`, `tar`, `entry`, `manifest`, or the first problem file).
`z9x_blobs_<slot>` survives Lumen updates only because they are PARTIAL payloads (`make_ota.py`,
enforced by `sign_payload.py`); a non-partial payload would delete it (docs/ota.md "Variants").

and **no** `libcodec2store.so`. Props: `ro.z9x.variant=public`, `ro.z9x.ota.manifest_url=.../update-public.json`
(docs/ota.md "Variants"), build id suffix ending in `p`. Gates: `lumen_v1.sh` preflight / prep
(`public_preflight`, `public_base_check`: the allow-list's bind= hashes are the base's own files, the
public rc is the base rc minus those binds), `check_image.py --variant public`, and on the Mac
`tools/ota/check_release_assets.py` on the release files. Host tests: `sh tools/ota/test/blobs.sh`,
`bash tools/ota/test/installer_blobs.sh`, `bash tools/ota/test/public_members.sh`.

## 2b. The Google services add-on (PUBLIC images, both editions; used by Lumen OS without Google)

Implemented 2026-10-10 (owner decision: only "Lumen OS без Google" is published, and the guide tells a user how
to add Google services himself). The user downloads the official MindTheGapps Android 14 ATV arm64 zip; the
installer's `gapps --zip FILE` (`installer/lumen-install.sh` / `.ps1`) accepts it only when its sha256 has a
block in `gapps_allow.txt`, creates or resizes the logical partition `z9x_gapps_<slot>` in super in fastbootd
(after checking super's free space; `part=` bytes, 512 MiB today), formats it with `fastboot format:ext4`,
restarts, fills it over adb root with `installer/lib/gapps_fill.sh` (exactly the 23 allow-listed files, `unzip -p`
of each member into its own path, each checked by sha256, then `MANIFEST`), runs the image's
`z9x_gapps.sh check`, and then wipes the data like `flash` (not with `--keep-data`). `gapps --remove` deletes
the partition (also from fastboot mode) with the same wipe.

| Source | Image path | Mode | Label |
|---|---|---|---|
| `z9x_gapps.sh` | `system/etc/z9x/z9x_gapps.sh` | 0755 | system_file |
| `z9x_gapps.rc` | `system/etc/init/z9x_gapps.rc` | 0644 | system_file |
| `gapps_allow.txt` | `system/etc/z9x/gapps_allow.txt` (= `installer/lib/gapps_allow.txt`) | 0644 | system_file |
| `z9x-gapps-sysconfig.xml` | `system/etc/z9x/gapps/sysconfig/z9x-gapps.xml` (dirs `gapps`, `gapps/sysconfig` 0755) | 0644 | system_file |

`z9x_gapps.sh` (exec at post-fs-data, like `z9x_blobs.sh`): only with `ro.z9x.gms=0` and the partition; not
when the boot rescue counter says this is the 3rd start of the slot in a row without boot_completed (then
the marker `/metadata/z9x_gapps/off` = `<n> <slot> <ro.z9x.build_id>` keeps it off on that slot and build
until the installer's `gapps` turns it on again; another slot or build ignores it, so an OTA that the gate
rolls back, after the marker, leaves the old slot with its Google services);
`blockdev --setro`, ext4 mounted read-only at `/mnt/z9x_gapps` with `context=u:object_r:system_file:s0`
(MindTheGapps' own installer labels every file system_file; the Z9X runs SELinux permissive anyway), nosuid,
nodev; `MANIFEST` must name an allow-listed zip and repeat exactly its file lines; every file present with its
size, nothing else (metadata only: no hashing of ~450 MB while init waits, no unzip at boot); then read-only,
lower-only overlays: the image's sysconfig layer over `/system/etc/sysconfig` (SetupWraith's wizard and boot
receiver off, GMS's update screen off, the 4 packages not stopped: the Google entries of the Google edition's
z9x.xml), the partition's `product`, `system_ext/priv-app`, `system_ext/etc/permissions`, `system/app`,
`system/etc/permissions` over the same `/system` paths (no `z9x_blobs` target is under any of them). One
overlay fails: all are unmounted again. Finally `ro.com.google.gmsversion` (MindTheGapps' `gapps.rc` value; the
add-on leaves that file out because init parses `/product/etc/init` before the mount). Result
`sys.z9x.gapps` = `ok` | `none` | `skip` | `off` | `bad:<why>`, `sys.z9x.gapps.zip` = the zip's sha256.
Bounded: one 2 s budget for the whole run (`bad:slow` when used up), the ext4 mount 2 s, each tree listing
1 s: at most about 3 s while init waits (the PWM watchdog margin is ~7 s).
Any failure: the system starts without Google services. The partition is not in /data (kept by factory
resets) and is kept by Lumen OS updates because every payload is PARTIAL (docs/ota.md). The add-on's files are
byte for byte the Google edition's MindTheGapps members minus the five it already dropped (Katniss,
DfuService, GoogleOneTimeInitializer, GoogleCalendarSyncAdapter, GoogleFeedback) and minus the two LineageOS
helpers that are not in the zip (LineageCustomizer, LineageGoogleSetupWraithOverlay).

Gates: `lumen_v1.sh` preflight (`gapps_preflight`: the rc is the one exec, the script's only mounts, setprops
and write, no unpacking, no read-write mount; the allow-list = the installer's copy and passes
`tools/ota/gapps_allow.py check`; the sysconfig layer = z9x-sysconfig.xml minus z9x-sysconfig-nogms.xml),
`check_image.py --variant public` (`check_gapps_addon`: byte-identical, modes, labels, allow-list rules).
Host tests: `sh tools/ota/test/gapps.sh`, `bash tools/ota/test/installer_gapps.sh`,
`bash tools/ota/test/public_members.sh`.

## 3. Properties and broadcasts the projector app may use (L-PROJECTOR, PLAN C16)

| Name | Values | Meaning |
|---|---|---|
| `sys.z9x.ota` | `none`, `pending`, `marked`, `merging`, `unhealthy`, `rollback`, `rolledback` | `pending` = this boot runs an unverified update slot: **never do a boot-dark power-off while pending** (a boot loop must look like a boot loop, so the gate can roll back). `unhealthy` (new in 1.0.1) = the update slot runs and cannot (or need not) roll back any more, but a health check failed or the boot HAL refused the mark; never a reboot |
| `sys.z9x.ota.why` | short text (<= 90 chars) | why `unhealthy`, or why a rollback was not possible (`no rollback: ...`); empty otherwise. For the updater to show next to `unhealthy` |
| reboot reasons | `reboot,z9x-ota` (updater), `reboot,z9x-ota-rollback`, `reboot,z9x-ota-stuck` (gate: 8 min without boot_completed and no rollback possible), `reboot,fastboot` (boot rescue) | attended reboots: no boot-dark power-off |
| `sys.z9x.ota.busy` | `1` while the updater downloads/installs, else `0` (best effort `SystemProperties.set`) | do not enter STR / real power-off while `1`; the updater also holds a partial wake lock while `update_engine` writes |
| broadcast `org.z9x.updater.action.OTA_STATE` | extras `busy` (boolean), `state` (string: idle, downloading, installing, ready, error) | sent to holders of the signature permission `org.z9x.updater.permission.OTA_STATE` |
| provider `content://org.z9x.updater/state` | `call("state", null, null)` -> Bundle {busy, state, version, ready, progress} | read permission `org.z9x.updater.permission.OTA_STATE` (signature) |

For Z9X Home (C17, "Update ready" badge): the same provider; `ready=true` means "restart to finish",
`state=available` means an update can be downloaded. Open the updater with
`new Intent("android.settings.SYSTEM_UPDATE_SETTINGS").setPackage("org.z9x.updater")`.

For Setup Done (C17): show "Protected video components missing" when `sys.z9x.blobs` is set and not
`ok` (public images only; the property is absent on the owner's image). Since the public 1.0.1 image the
same files also carry the audio: `missing` there also means no sound from the projector.
