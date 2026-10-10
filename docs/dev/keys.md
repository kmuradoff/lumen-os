# Lumen OS release keys

Owner: kmuradoff. Generated 2026-10-06 by `tools/sign/gen_keys.sh` on the owner's Mac; the APEX keys
were added on 2026-10-07 by the same script.

## Where they are

- **Private keys:** `~/.lumen-keys/` on the owner's Mac only, directory mode 700, every `*.pk8` and every
  APEX payload key `apex/*.pem` mode 600.
- **Never** in the project tree, on the build laptop, in git, in iCloud/Dropbox or in
  a chat. `gen_keys.sh`, `sign_tar.py`, `apex_sign.py`, `sign_payload.py` and `make_manifest.py` refuse a
  keys directory inside the project tree; `apex_laptop.py` refuses to run on a machine that has
  `~/.lumen-keys`; `apex_sign.py` and `lumen_v1.sh remote` check that no private key (no `*.pk8`, no
  `PRIVATE KEY` block) is on the laptop; `tools/ota/check_publish.py` blocks every private key in the repo except the public AOSP test keys (`apps/sdk/testkeys/`, already published by AOSP and used only for local development builds; release images never carry them).
- **Public certificates:** `tools/sign/release_certs/` (published as `keys/public/`), with the APEX
  public keys in `tools/sign/release_certs/apex/`, plus `installer/certs/ota.x509.pem`. SHA-256
  fingerprints: `tools/sign/release_certs/FINGERPRINTS.txt` and `tools/sign/release_certs/apex/FINGERPRINTS.txt`.
- **Backups (to do by the owner, once, and again now that `apex/` exists):** two offline copies of the
  whole `~/.lumen-keys`, for example an encrypted USB stick and an attachment in a password manager.
  Losing `platform` means every install must be reflashed with a data wipe; losing `ota` means no more
  updates over Wi-Fi; losing an APEX key only means the next image re-signs that module with a new key
  (see "Rotation").

## The set

| Key | Type | Replaces the AOSP test key | Used for |
|---|---|---|---|
| `platform` | RSA-2048 | platform | framework-res, `android.uid.system` apps, TvSettings, TvSystemUI, Lineage SDK, **all `org.z9x.*` apps**; inside APEXes: PermissionController, DeviceConfigServiceResources, DeviceLockController, OnDevicePersonalization |
| `shared` | RSA-2048 | shared | `android.uid.shared` (contacts / user dictionary providers) |
| `media` | RSA-2048 | media | `android.media` (DownloadProvider, MediaProviderLegacy); MediaProvider (com.android.mediaprovider) |
| `networkstack` | RSA-2048 | networkstack | `android.uid.networkstack`: NetworkStack.apk, CaptivePortalLogin.apk and TetheringNext.apk (com.android.tethering) |
| `bluetooth` | RSA-2048 | bluetooth | Bluetooth.apk (com.android.btservices, `android.uid.bluetooth`) |
| `sdk_sandbox` | RSA-2048 | sdk_sandbox | SdkSandbox.apk (com.android.adservices) |
| `nfc` | RSA-2048 | nfc | reserved (no NFC app in the image; its seinfo entry points at it) |
| `releasekey` | RSA-2048 | testkey | every other test-key APK, and every APK inside an APEX that carried an AOSP *module development* key (see the APEX section) |
| `ota` | RSA-4096 | (OTA test key) | update payloads, `update-*.json` manifests, `SHA256SUMS`; first entry of `otacerts.zip` |
| `ota_next` | RSA-4096 | - | spare: second entry of `otacerts.zip`, for one future key rotation |
| `apex/<module>.pem` | RSA-4096 | the module's AOSP payload key | AVB hashtree footer of the module's `apex_payload.img`; public forms `<module>.avbpubkey` (= the `apex_pubkey` entry) and `<module>.pubkey.pem` |
| `apex/<module>.pk8` + `.x509.pem` | RSA-4096 | the module's AOSP container certificate | the APK-style signature of the `.apex` / `.capex` container (and of the `original_apex` inside a `.capex`) |

Subjects are `/O=Lumen OS/OU=kmuradoff/CN=Lumen OS <name>` and `CN=Lumen OS APEX <module>` (no e-mail,
no country). The 35 APEX modules are listed in `tools/sign/keymap.json` (`apex.modules`).

## What gets re-signed (`tools/sign/apex_sign.py` + `tools/sign/sign_tar.py`)

The image is composed on the laptop with test keys (as every build since v6), then the tar comes to
the Mac. First `apex_sign.py` re-signs **every APEX** (next section), then `sign_tar.py --apex-dir`
puts them into the tar and re-signs every other APK **by certificate**: an APK signed with an AOSP
test certificate (SHA-256 list in `tools/sign/keymap.json`) gets the Lumen key that replaces it. On
the Lumen OS 1.0 tar (2026-10-07 dry run): 82 APKs = 69 re-signed (platform 41, releasekey 21,
shared 3, media 2, networkstack 2), 13 presigned (Google apps, MindTheGapps' own overlay), 0 kept,
0 unclassified; 35 APEXes re-signed with 23 APKs inside them.

- Zip entries outside `META-INF` keep their bytes (checked: CRC and size of every entry), so the
  dexpreopted `oat/*.odex|vdex` files and the boot image stay valid.
- `zipalign -P 16` + `apksigner --alignment-preserved`, with the same v1/v2/v3 schemes as before.
- SELinux `mac_permissions.xml`: every AOSP test certificate is **replaced** by the release
  certificate that takes its place (same seinfo: `platform`, `media`, `network_stack`, `bluetooth`,
  `sdk_sandbox`, `nfc`). No test `<signer>` stanza is kept: nothing in the image carries a test
  certificate any more, so an app signed with a published AOSP test key only gets the default
  (untrusted app) seinfo. (Lumen OS 1.0 had kept *scoped* test stanzas for the APKs inside APEXes;
  they are gone.)
- `system/etc/security/otacerts.zip` = `{ota, ota_next}`: update_engine and the updater trust only
  these.
- A leftover scan looks for any test certificate in text files and in the re-signed APKs' resources.
- Every rewritten member (APK, APEX, seinfo, otacerts) and its directory get the signing time as mtime,
  so PackageManager's parse cache re-parses them (for an APK inside an APEX it compares the mtime of
  the backing APEX file).
- `ro.build.tags` stays `test-keys` and `ro.build.type` `userdebug` on purpose (fingerprint unchanged,
  PLAN C18). `ro.z9x.keys=release` marks a signed image.

Verify a signed tar: `python3 tools/sign/sign_tar.py --verify <signed.tar>` (writes `all_signers.tsv`:
the signer of every APK, every APEX container and every APK inside an APEX; any AOSP test certificate
fails); the whole release gate: `tools/sign/check_image.py --tar <signed.tar> --img <system.img>`.

<a name="apex"></a>

## APEX policy (since 2026-10-07: every APEX re-signed)

**Every APEX in the image is re-signed with Lumen keys; no AOSP test or development key is left.**
The 35 modules of the Lumen OS 1.0 image: 30 in `system/apex` (19 `.capex`, 11 `.apex`) and the
five VNDK APEXes in `system/system_ext/apex` (v30 to v34). None in `product`.

### Keys: one payload key and one container key per module

AOSP gives every APEX its own payload key and its own container certificate (`apexkeys.txt`), and
`sign_target_files_apks.py` maps each to a release key. Lumen OS does the same: per module `<m>`,
`~/.lumen-keys/apex/<m>.pem` (payload, RSA-4096, the size AOSP uses and the only algorithm in the image,
`SHA256_RSA4096`) and `~/.lumen-keys/apex/<m>.pk8` + `<m>.x509.pem` (container, RSA-4096). Choice
of per-module container keys over one shared Lumen container key: it is what AOSP does, a key that
leaks or must change touches one module only, and it costs nothing (generated by `gen_keys.sh`, checked
by `apex_sign.py`). A module that is not in `keymap.json` `apex.modules` stops the build (a new module
needs a decision and keys).

What apexd checks on the device: it activates a **pre-installed** APEX after verifying the payload's
AVB footer against the `apex_pubkey` bundled in the same file, and that public key must equal the key
inside the vbmeta (`ApexFile::VerifyApexVerity`); the manifest inside the payload must equal the one
outside it. A `.capex` is decompressed only if the decompressed APEX has the same `apex_pubkey` and
version as the `.capex` and its payload root digest equals `capexMetadata.originalApexDigest`. The
key only decides which **updates** of the module would be accepted (an update must be signed with the
same payload key; Lumen OS installs none). The container certificate is what PackageManager reports for
the APEX package. vbmeta / boot / vendor are not involved.

### APKs inside APEXes

| APK (module) | AOSP key before | Lumen key now |
|---|---|---|
| TetheringNext (com.android.tethering), `android.uid.networkstack` | networkstack (test) | **networkstack**: NetworkStack.apk and CaptivePortalLogin.apk in /system move to it too, the shared uid stays one signer |
| PermissionController (com.android.permission), DeviceConfigServiceResources (com.android.configinfrastructure), DeviceLockController (com.android.devicelock), OnDevicePersonalization (com.android.ondevicepersonalization) | platform (test) | **platform** (as AOSP builds them: they are platform-signed again, as before 1.0's re-signing) |
| MediaProvider (com.android.mediaprovider) | media (test) | **media** |
| Bluetooth (com.android.btservices), `android.uid.bluetooth` | bluetooth (test) | **bluetooth** |
| SdkSandbox (com.android.adservices) | sdk_sandbox (test) | **sdk_sandbox** |
| ExtServices-sminus, rkpdapp, CtsShim, CtsShimPriv, android.system.virtualmachine.res | testkey | **releasekey** |
| AdServicesApk, HealthConnectController, HealthConnectBackupRestore, FederatedCompute, SafetyCenterResources, ServiceConnectivityResources, ServiceWifiResources, OsuLogin, WifiDialog, ServiceUwbResources | AOSP module development keys (in the AOSP source tree, `O=Android`) | **releasekey** (`keymap.json` `apex.inner_apk_default`) |

Checked before choosing releasekey for the module development keys: the Lineage 21 sources
(`packages/modules`, `frameworks/base`, `frameworks/opt`, `system/apex`, `system/sepolicy`) never pin
those certificates (the only hits are AdServices allow-lists of *client* app signatures and an
Android S/T-only consent path); the resource APKs are found by intent action + APEX path, not by
certificate; the vendor RROs of the Z9X (`TetheringRROOverlay`, `TetheringInprocessRROOverlay`,
`WifiRROOverlay`, XGIMI-signed) target overlayables whose policies are partition based
(`product|system|vendor`, `odm|...`), not `signature`, so they keep applying.

### How a module is re-signed (split: keys never leave the Mac)

`tools/sign/apex_sign.py run IN.tar OUTDIR` (called by `lumen_v1.sh sign` and `sign_release.sh`):

1. **Mac**: inventory of every APEX and of every APK anywhere in its payload (debugfs, whole tree);
   classify (above); re-sign the APKs exactly like `sign_tar.py` (zipalign -P 16, apksigner,
   entries outside META-INF byte-identical).
2. **Laptop** (`apex_laptop.py repack`, no key): for the 15 modules that hold such an APK, take the
   member from the laptop's copy of the same tar (sha256 per APEX checked), `deapexer decompress` /
   `extract`, swap in the re-signed APKs (old and new sha256 checked), rebuild the payload like AOSP's
   `apex_utils.RepackApexPayload`, but unsigned: `apexer --unsigned_payload_only --build_info
   apex_build_info.pb --manifest apex_manifest.pb` (the module's own file_contexts, canned fs_config,
   fs type), then compare the new filesystem with the original entry by entry: paths, type, mode,
   uid, gid, every xattr (SELinux label, capabilities), bytes; only the swapped APKs differ.
3. **Mac**: payload signing as `apexer` / `apex_utils.SignApexPayload`: `avbtool add_hashtree_footer
   --do_not_generate_fec` with the module's new payload key and the original algorithm, hash, salt
   (= sha256 of apex_manifest.pb) and `apex.key` prop, `resize_image` to the apexer size; the 20
   untouched payloads keep their filesystem byte for byte (same root digest). `avbtool verify_image
   --key` with the new public key. Container: the original entries in their order, new
   `apex_payload.img` and `apex_pubkey`, `zipalign 4096`, `apksigner --alignment-preserved
   --align-file-size` v1+v2+v3 with the module's container key (what `signapk -a 4096 --align-file-size`
   does in AOSP); payload stored and 4 KiB aligned, file size a multiple of 4 KiB.
4. **Laptop** (`apex_laptop.py compress`): `apex_compression_tool compress` of the 19 `.capex` (it
   writes the new payload root digest into `capexMetadata`).
5. **Mac**: `zipalign 4096` + apksigner of each `.capex` with the same container key, then the offline
   checks with public keys only, for every APEX: container and `original_apex` signed only by the
   module's Lumen cert; `apex_pubkey` (outer and inner) = the Lumen payload key; `avbtool verify_image
   --key <m>.pubkey.pem` passes and the vbmeta key is that key; algorithm SHA256_RSA4096;
   `apex_manifest.pb` byte-identical to the original (name and version unchanged) and identical to the
   copy inside the payload; capex manifest = original manifest except `capexMetadata`, whose digest is
   the payload root digest; every APK inside verifies with its planned release certificate.
6. **Laptop** (`apex_laptop.py verify`): for every module, original against final: `deapexer info`
   (manifest), `info --print-type`, `list --size -Z` (paths, sizes, SELinux contexts), `extract`
   (bytes), filesystem metadata of both payloads, the decompressed payload root digest against
   `capexMetadata`, `apex_pubkey`.

Then `sign_tar.py --apex-dir OUTDIR` takes only APEXes whose original sha256 matches the tar
(`apex_signed.json`) and re-checks all of it (`sign_tar.py --verify`, `check_image.py`), and
`lumen_checks.py signed` accepts an APEX change only for the pinned file. Transfers to the laptop are
APKs, unsigned payloads and signed (public) APEXes; its work dir is deleted at the end.

Laptop host tools: `deapexer`, `apexer`, `apex_compression_tool`, `debugfs_static`, `fsck.erofs`,
`mke2fs`, `e2fsdroid`, `resize2fs`, `sefcontext_compile`, `soong_zip` from `~/lineage/out/host/linux-x86/bin`
(already built). `avbtool` is the pinned copy `tools/sign/third_party/avbtool.py` (AOSP 1.3.0, the
version every APEX of the image was signed with) on both machines.

### Consequences for the device

- apexd behaviour is unchanged for pre-installed APEXes (it trusts the key bundled with each one).
  On a device that already ran another build, the decompressed copies in `/data/apex/decompressed`
  carry the old key, fail `ValidateDecompressedApex` and are decompressed again; odrefresh may
  recompile the system server artifacts once. APEX **updates** signed with AOSP keys (CTS shim test
  APEXes, Google mainline trains) are refused, as intended.
- Shared users: `android.uid.networkstack` = networkstack (3 APKs), `android.uid.bluetooth` =
  bluetooth, `com.android.cts.ctsshim` = releasekey; one signer each (checked).
- Upgrading a device that has data from a test-key build (or from Lumen OS 1.0) over the air is not
  supported: install with a data wipe (`installer/`), as for every Lumen OS key change.

## Rotation and loss

- `ota_next` is already trusted by every Lumen image: to rotate, sign the next manifests and payloads
  with `ota_next`, ship an image whose `otacerts.zip` is `{ota_next, <new spare>}`, then retire `ota`.
- A new `platform` key cannot be rotated in: it means a reflash with a data wipe for everyone.
- An APEX key can be replaced by deleting `~/.lumen-keys/apex/<m>.*` and re-running `gen_keys.sh`: the
  next image carries the new key; on a device, pre-installed APEXes are trusted by their bundled key, so
  no wipe is needed for that alone.
