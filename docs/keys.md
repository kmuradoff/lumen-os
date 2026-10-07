# Lumen OS release keys

Owner: kmuradoff. Generated 2026-10-06 by `tools/sign/gen_keys.sh` on the owner's Mac.

## Where they are

- **Private keys:** `~/.lumen-keys/` on the owner's Mac only, directory mode 700, every `*.pk8` mode
  600. **No password** (owner decision 2026-10-06): the directory itself is the protection.
- **Never** in the project tree, on the build laptop (it is borrowed), in git, in iCloud/Dropbox or in
  a chat. `gen_keys.sh`, `sign_tar.py`, `sign_payload.py` and `make_manifest.py` refuse a keys
  directory inside the project tree; `tools/ota/check_publish.py` blocks any private key in the repo.
- **Public certificates:** `tools/sign/release_certs/` (published as `keys/public/`), plus
  `installer/certs/ota.x509.pem`. SHA-256 fingerprints: `tools/sign/release_certs/FINGERPRINTS.txt`.
- **Backups (to do by the owner, once):** two offline copies of `~/.lumen-keys`, for example an
  encrypted USB stick and an attachment in a password manager. Losing `platform` means every install
  must be reflashed with a data wipe; losing `ota` means no more updates over Wi-Fi.

## The set

| Key | Type | Replaces the AOSP test key | Used for |
|---|---|---|---|
| `platform` | RSA-2048 | platform | framework-res, `android.uid.system` apps, TvSettings, TvSystemUI, Lineage SDK, **all `org.z9x.*` apps** |
| `shared` | RSA-2048 | shared | `android.uid.shared` (contacts / user dictionary providers) |
| `media` | RSA-2048 | media | `android.media` (DownloadProvider, MediaProviderLegacy) |
| `networkstack` | RSA-2048 | networkstack | **not used in 1.0** (see the APEX policy) |
| `releasekey` | RSA-2048 | testkey | every other test-key APK |
| `sdk_sandbox`, `bluetooth`, `nfc` | RSA-2048 | the same names | reserved: in 1.0 their users live inside APEXes (or do not exist) |
| `ota` | RSA-4096 | (OTA test key) | update payloads, `update-*.json` manifests, `SHA256SUMS`; first entry of `otacerts.zip` |
| `ota_next` | RSA-4096 | - | spare: second entry of `otacerts.zip`, for one future key rotation |

Subjects are `/O=Lumen OS/OU=kmuradoff/CN=Lumen OS <name>` (no e-mail, no country).

## What gets re-signed (`tools/sign/sign_tar.py`)

The image is composed on the laptop with test keys (as every build since v6), then the tar comes to
the Mac and `sign_tar.py` re-signs it **by certificate**: an APK signed with an AOSP test certificate
(SHA-256 list in `tools/sign/keymap.json`) gets the Lumen key that replaces it. On the v6.5 base tar
(2026-10-07 inventory): 136 APKs = 116 re-signed (platform 47, releasekey 64, shared 3, media 2),
18 presigned (Google apps, MindTheGapps' own overlay), 2 kept (networkstack, below), 0 unclassified.

- Zip entries outside `META-INF` keep their bytes (checked: CRC and size of every entry), so the
  dexpreopted `oat/*.odex|vdex` files and the boot image stay valid.
- `zipalign -P 16` + `apksigner --alignment-preserved`, with the same v1/v2/v3 schemes as before.
- SELinux `mac_permissions.xml`: the release certificate gets the seinfo of the test certificate it
  replaces (`platform`, `media`, `nfc` ...). Where an APK inside an APEX still uses the test certificate,
  the old entry stays next to the new one (see below).
- `system/etc/security/otacerts.zip` = `{ota, ota_next}`: update_engine and the updater trust only
  these.
- A leftover scan looks for any test certificate in text files and in the re-signed APKs' resources.
- `ro.build.tags` stays `test-keys` and `ro.build.type` `userdebug` on purpose (fingerprint unchanged,
  PLAN C18). `ro.z9x.keys=release` marks a signed image.

Verify a signed tar: `python3 tools/sign/sign_tar.py --verify <signed.tar>`;
the whole release gate: `tools/sign/check_image.py --tar <signed.tar> --img <system.img>`.

<a name="apex"></a>

## APEX policy (Lumen OS 1.0)

**APEX modules are not re-signed in 1.0.** apexd trusts the APEX files that are pre-installed on the
read-only `/system` whatever key signed them; the key matters only for *installing an APEX update*,
which Lumen OS never does (Google Play cannot replace our `com.android.*` modules). Re-signing an APEX
means rebuilding its payload image (ext4 + hashtree + AVB footer) and its container signature for
about 30 modules: planned for a later update, together with the items below.

Consequences, all found by `sign_tar.py`'s APEX inventory (`apex_apks.tsv`), and how 1.0 handles them:

| Inside an APEX, still test-signed | Effect | Handling in 1.0 |
|---|---|---|
| `TetheringNext.apk` (com.android.tethering), networkstack key, **sharedUserId `android.uid.networkstack`** | `/system/priv-app/NetworkStack` shares that uid; re-signing only one side would break boot ("Signature mismatch for shared user") | the networkstack key is **APEX-bound**: `NetworkStack.apk` and `CaptivePortalLogin.apk` keep the AOSP networkstack certificate; its seinfo entry is unchanged. Safe: the shared user is privileged, and Android 14 lets only platform-signed apps join a privileged shared user, so the public test key cannot be used to enter it |
| `PermissionController`, `DeviceConfigServiceResources`, `DeviceLockController`, `OnDevicePersonalization` (platform test key) | they no longer match the (release) platform certificate | like Google's own mainline modules on any OEM device: their access comes from `privileged`/`module`/role grants. The test-platform seinfo entry is kept next to the release one so their SELinux domains do not change |
| `MediaProvider` (media test key) | same | the test-media seinfo entry is kept next to the release one |
| any kept test certificate (platform, media, networkstack, bluetooth, sdk_sandbox) | the AOSP test keys are **public**: anyone can sign an app with them | every kept test `<signer>` is **scoped** to the packages that still carry it (`<package name="com.android.permissioncontroller"><seinfo value="platform"/></package>` ..., no default seinfo; `sign_tar.py`, checked by `--verify`). A sideloaded app signed with a public test key therefore gets the ordinary untrusted-app seinfo, never `platform_app` / `mediaprovider` / `network_stack`. Residual risk (accepted for 1.0): an *update* of one of exactly those packages (e.g. com.android.permissioncontroller), signed with the matching public test key, would still match its stanza and get that seinfo, if PackageManager accepts such an update of an APK-in-APEX. Today `ro.boot.selinux=permissive`, so domains are not enforced at all. Re-signing the APEXes (a later version) removes the test certificates completely |
| `Bluetooth.apk` (com.android.btservices), bluetooth key, `android.uid.bluetooth` | the only member of its shared uid | unchanged; known limitation until APEX re-signing |
| `SdkSandbox`, `ExtServices`, `rkpdapp`, `CtsShim*`, `virtualmachine.res` (test keys) | none for the system | unchanged |

The APEX containers themselves keep their AOSP container certificates (`apex.tsv`).

## Rotation and loss

- `ota_next` is already trusted by every Lumen image: to rotate, sign the next manifests and payloads
  with `ota_next`, ship an image whose `otacerts.zip` is `{ota_next, <new spare>}`, then retire `ota`.
- A new `platform` key cannot be rotated in: it means a reflash with a data wipe for everyone.
