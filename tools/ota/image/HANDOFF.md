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
M system/etc/init/update_verifier.rc
T system/product/etc/build.prop ro.product.ab_ota_partitions=boot,bootdata,dtbo,mboot,optee,owl,satf,tvconfig,tvservice,vbmeta_system,xgimiconfig
T system/product/etc/build.prop ro.z9x.keys=release
T system/product/etc/build.prop ro.z9x.ota.gate=1
```

`ro.z9x.keys=release` is true only after `tools/sign/sign_tar.py` ran on the Mac; the laptop tar
is the *input* of signing, the flashed image is always the signed one (`tools/sign/sign_release.sh`).

## 2. Files for PUBLIC images only (not the owner's v1)

The owner's image keeps the MediaTek Codec2 libs inside `/system` (as v6.5). A public image must
not: it gets them from the user's own stock via the installer (`installer/`, `blobs` step) into the
logical partition `z9x_blobs_<slot>` and bind-mounts them at boot.

| Source | Image path | Mode | Label |
|---|---|---|---|
| `z9x_blobs.sh` | `system/etc/z9x/z9x_blobs.sh` | 0755 | system_file |
| `z9x_blobs.rc` | `system/etc/init/z9x_blobs.rc` | 0644 | system_file |
| `blobs_allow.txt` | `system/etc/z9x/blobs_allow.txt` | 0644 | system_file |
| 0-byte files | the 6 paths of `blobs_allow.txt` under `system/` (`system/system_ext/lib{,64}/libc2plugin_store.so`, `.../vendor.mediatek.hardware.c2.info@1.0.so`, `system/lib{,64}/libcodec2_soft_common.so`) | 0644 | **system_lib_file** |

and **no** `libcodec2store.so`, no `system/etc/xgimi/libstagefright_foundation*.so`, no XGIMI
`audio_policy_configuration.xml`. Gate: `check_image.py --variant public`.

## 3. Properties and broadcasts the projector app may use (L-PROJECTOR, PLAN C16)

| Name | Values | Meaning |
|---|---|---|
| `sys.z9x.ota` | `none`, `pending`, `marked`, `merging`, `rollback`, `rolledback` | `pending` = this boot runs an unverified update slot: **never do a boot-dark power-off while pending** (a boot loop must look like a boot loop, so the gate can roll back) |
| reboot reasons | `reboot,z9x-ota` (updater), `reboot,z9x-ota-rollback` (gate) | attended reboots: no boot-dark power-off |
| `sys.z9x.ota.busy` | `1` while the updater downloads/installs, else `0` (best effort `SystemProperties.set`) | do not enter STR / real power-off while `1`; the updater also holds a partial wake lock while `update_engine` writes |
| broadcast `org.z9x.updater.action.OTA_STATE` | extras `busy` (boolean), `state` (string: idle, downloading, installing, ready, error) | sent to holders of the signature permission `org.z9x.updater.permission.OTA_STATE` |
| provider `content://org.z9x.updater/state` | `call("state", null, null)` -> Bundle {busy, state, version, ready, progress} | read permission `org.z9x.updater.permission.OTA_STATE` (signature) |

For Z9X Home (C17, "Update ready" badge): the same provider; `ready=true` means "restart to finish",
`state=available` means an update can be downloaded. Open the updater with
`new Intent("android.settings.SYSTEM_UPDATE_SETTINGS").setPackage("org.z9x.updater")`.

For Setup Done (C17): show "Protected video components missing" when `sys.z9x.blobs` is set and not
`ok` (public images only; the property is absent on the owner's image).
