# Z9X v6.1: image changes needed by the org.z9x.projector foundation

**Scope.** Only what the projector app foundation needs from the `/system` image. The module agents add their own sections:
- power RRO and rc files;
- sys: netpolicy, locale, kill-switch removal;
- source+keys: global_keys, see `V61_KEYS_PLAN.md`.

The image is built by `gsi/tools/z9x_v6.sh` through `patch_tar.py`. For v6.1, copy it to `z9x_v61.sh` or extend it.

## 1. Runtime-permission pre-grant: `/system/etc/default-permissions/z9x-projector.xml`

**Why.** `org.z9x.projector` now requests `BLUETOOTH_SCAN` (with `neverForLocation`) and `BLUETOOTH_CONNECT` for BLE remote auto-pairing (module `remote`).
- Both are `dangerous`: `~/lineage/frameworks/base/core/res/AndroidManifest.xml`.
- A platform signature does not grant runtime permissions.
- On the live device the test app `org.z9x.btpair` holds them only because it was installed to `/data` and granted by hand (`dumpsys package org.z9x.btpair`).

**File content** (mode 644, root:root, `system_file`):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Z9X v6.1: runtime permissions of org.z9x.projector (/system/app, platform-signed, not priv-app).
     Read by DefaultPermissionGrantPolicy.grantDefaultPermissionExceptions. -->
<exceptions>
    <exception package="org.z9x.projector">
        <permission name="android.permission.BLUETOOTH_SCAN" fixed="false" />
        <permission name="android.permission.BLUETOOTH_CONNECT" fixed="false" />
    </exception>
</exceptions>
```

**patch_tar arguments.** The directory does not exist on the live `/system` (`ls /system/etc/default-permissions`: "No such file or directory"), so it has to be created:

```
--mkdir system/etc/default-permissions 755
--add   system/etc/default-permissions/z9x-projector.xml <file> 644
```

Add `system/etc/default-permissions` to the "already present" guard of the base check in the script.

**Alternative (no mkdir).** `/product/etc/default-permissions/` already exists; it holds `default-atv-permissions.xml`. `DefaultPermissionGrantPolicy` reads `/system`, `/vendor`, `/odm`, `/product`, `/system_ext` and `/oem` (DefaultPermissionGrantPolicy.java:1474-1494), so `system/product/etc/default-permissions/z9x-projector.xml` works the same way.

**Conditions, all verified in the Lineage source:**
- `DefaultPermissionGrantPolicy.parseExceptions` accepts the package only if `isSystemOrCertificateMatchingPackage` holds and the package supports runtime permissions. Our package is a system app with targetSdk 34, so both hold.
- `isSystemPackage` is true for a non-privileged `/system/app` app (lines 1711-1745).
- Only `dangerous` permissions are applied; anything else is logged and ignored.

**When the grant runs.** It happens only when the "permission upgrade" is needed, which is decided per user:
1. On the first boot of a user.
2. When the extended fingerprint `PackagePartitions.FINGERPRINT + "?pc_version=" + N` changes (`Settings.java:5992-6027`). The fingerprint is a digest of every `ro.<partition>.build.fingerprint` plus `ro.build.version.incremental` (`PackagePartitions.java:128-136`).

**Our case: the same system tar.** v6.1 rebuilt from the same `system_tv_v4.tar` keeps the same fingerprint (live: `ro.system.build.fingerprint ... eng.root.20261003.104216`), so the grant would **not** run on an upgrade from v6.

**Recommended:** change `ro.build.version.incremental` in `system/build.prop` once per image, for example `eng.root.20261003.104216.z9x61`:

```
--sub system/build.prop '^ro\.build\.version\.incremental=(.*)$' 'ro.build.version.incremental=\1.z9x61'
```

`patch_tar.py --sub` uses Python `re.subn(..., flags=re.M)` (patch_tar.py:73), so the `\1` back-reference works.

This marks the image as a system update:
- PackageManager re-scans and clears code caches. That is harmless, and `pm.boot.disable_package_cache=true` is already set.
- Runtime permission defaults are re-applied.
- `ro.build.fingerprint`, which Google certification checks, is untouched.

**Fallback, done in the app.** The `remote` module self-grants with `PackageManager.grantRuntimePermission(pkg, perm, UserHandle.SYSTEM)`, using `GRANT_RUNTIME_PERMISSIONS` (signature|installer|verifier, so granted by the platform key) before it scans. The pre-grant is therefore an optimisation, not a single point of failure.

## 2. The APK

**Path and version.**
- Same path as v6: `system/app/Z9xProjector/Z9xProjector.apk`, mode 644.
- versionCode 61, versionName 6.1. Build with `VERSION_CODE=61 VERSION_NAME=6.1 sh build_apk.sh Z9xProjector out/Z9xProjector.apk`.

**Permissions.** No `privapp-permissions` XML is needed. Every non-runtime permission requested is `normal` or carries `signature` (platform key); see the manifest comments.

| permission | level | purpose |
|---|---|---|
| SYSTEM_ALERT_WINDOW | signature\|setup\|appop\|... | overlay windows (already granted live) |
| INTERNAL_SYSTEM_WINDOW | signature\|module\|recents | overlays stay visible when an app hides non-system overlays |
| WRITE_SECURE_SETTINGS | signature\|privileged\|development\|role\|installer | module sys: `restricted_networking_mode` |
| WRITE_SETTINGS | signature\|preinstalled\|appop\|pre23\|role | module sys: `sound_effects_enabled` |
| MANAGE_NETWORK_POLICY | signature | module sys: stale REJECT_ALL uid policies |
| DEVICE_POWER | signature\|role | module power (PowerManager helpers, never `forceSuspend`) |
| BLUETOOTH_PRIVILEGED | signature\|privileged | module remote: bonding without a dialog |
| GRANT_RUNTIME_PERMISSIONS | signature\|installer\|verifier | module remote: self-grant fallback |
| PACKAGE_USAGE_STATS | signature\|privileged\|development\|appop\|retailDemo | module panel: optional AI picture scene hints |
| BLUETOOTH_SCAN, BLUETOOTH_CONNECT | dangerous | section 1 |

**Correction to research/v61/RESULT_power.json.** That file says `org.z9x.projector` "does not hold DEVICE_POWER". That was only because v6 did not request it. DEVICE_POWER is `signature|role`, so requesting it in a platform-signed APK grants it from `/system/app` as well.

**sysconfig:** `system/etc/sysconfig/z9x.xml` (`initial-package-state stopped="false"`) is unchanged and still needed.

## 3. The kill switch is gone from the app

`HalController.featureEnabled()` now always returns true. The app no longer reads `sys.z9x.feat` and never refuses HAL work because of it (V61_REQUIREMENTS item 13).

The image can therefore drop the following (owned by the sys/image agent):
- `system/etc/init/z9x_features.rc`;
- the `persist.z9x.featblock=0` and `persist.z9x.strike=0` lines in `overlay/v6/build_prop_v6.txt`;
- any lastboot/strike logic in `z9x_diag.sh`. The logger itself may stay, as a passive log only.

This app works whether or not those files are present.

## 4. Nothing else

The foundation needs nothing else from the image:
- no new rc, no new property, no SELinux change;
- IGmpf2 has the same `hal_gmpf_hwservice` label as IGmpf, and `platform_app` may already find and call it (`vendor_sepolicy.cil:10460-10463`, RESULT_hal verified_facts 20).

## 5. Flash notes (review fixes, 2026-10-04)

- **Rebuild the image.** The fix round rebuilt `apps/out/Z9xProjector.apk` and `apps/out/Z9xTvInput.apk` (new: tvinput `HdmiStateProvider` + permission `org.z9x.tvinput.permission.HDMI_STATE`, projector `SourceRequestReceiver` + permission `org.z9x.projector.permission.SHOW_SOURCE`; both signature, both APKs platform-signed). The old `build/system_tv_v61.img` (sha256 a563112d…) predated them; it was rebuilt on 2026-10-04 00:07 (build round 2): sha256 `1b1b015b…`. Build round 3 (2026-10-04 00:49) rebuilt it from the 00:43 APKs (projector + tvinput 61/6.1): sha256 `e4c4021cbe487d1653c004ab8e85ad4be3646f3200d02c4c8d9af6a7d8e30ac2` (build/SHA256SUMS_v61.txt). The round-2 image is superseded.
- **Rollback image.** No `system_tv_v61safe.img` was built. The rollback is `build/system_tv_v6.img` (sha256 `1c3110812ecadb83fea485ef9ed903fd5bf5c0c30c28ea3f8040ea88aafea2d1`), proven to boot on this device; `system_tv_v6safe.img` is the logger-only fallback. To get a v6.1 bisect image run `nice bash ../tools/z9x_v61.sh safe` on the laptop.
- **Over the existing v6 /data the language stays ru-RU and the setup wizard does not run again.** Live device (read-only check): `persist.z9x.firstrun=0`, `persist.sys.locale=ru-RU`, `/data/system/z9x_settings_reset` is already a directory. So `z9x_setup.rc` blocks 1 (settings reset + settings_global stub) and the en-US locale override are no-ops there; only the netpolicy reset runs (once). To test the en-US first run and the SettingsProvider stub fix, flash over stock data, or change the language in Settings. Deliberately not re-armed: forcing en-US on this device would override the user's own language choice.
