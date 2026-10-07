# Lane L-OTA: Lumen OS 1.0 (W1), 2026-10-07

Restarted after the 2026-10-07 01:45 interruption. The release keys from the first run were kept
(`~/.lumen-keys`, verified: dir 700, every pk8 600 and matching its certificate); nothing regenerated.

## Release keys and signing (Mac only) - `tools/sign/`
- `gen_keys.sh` (first run; refuses to overwrite), `release_certs/` (public), `testcerts/` (AOSP public).
- `keymap.json`: AOSP test cert SHA-256 -> Lumen key; MindTheGapps' own overlay key listed as presigned.
- `sign_tar.py`: inventory (incl. APKs inside APEX payloads via debugfs/fsck.erofs), re-sign by
  certificate with dex bytes unchanged, APEX-bound key detection (networkstack: shares
  `android.uid.networkstack` with TetheringNext in com.android.tethering, kept), mac_permissions
  (replace / keep+add / leave per cert), `otacerts.zip`={ota, ota_next}, leftover scan, `--verify`.
  Tested on the real v6.5 tar: 136 APKs, 116 re-signed, 18 presigned, 2 kept, 0 unclassified,
  0 shared-uid conflicts, 0 leftovers; `--verify` OK.
- `check_image.py`: release gate (keys, props incl. `ab_ota_partitions` without vbmeta, C18 tags,
  updater + sysconfig, OTA plumbing byte-identical, MTK files by sha256 for public images, image
  read-back with dump.erofs).
- `sign_release.sh`: pull tar (verified sha) -> sign -> mkfs.erofs -> check -> SHA256SUMS(.sig).
  Builder host comes from the environment / `~/.config/lumen/builder.env` (no host in the repo).
- APEX policy and key facts: `docs/keys.md`.

## OTA - `tools/ota/`
- `image/`: `z9x_ota.{rc,sh}` + `update_verifier.rc` (gate: attempt counter, health gate, rollback,
  `sys.z9x.*` facts incl. vbmeta header flags read-only), `z9x_blobs.{rc,sh}` + `blobs_allow.txt`
  (public images), `ota_props.txt`, `z9x-updater-default-permissions.xml`, `HANDOFF.md` (already wired
  by L-SYSTEM's `lumen_v1.sh`). Gate logic tested with stubs (normal boot, 3 attempts -> rollback,
  bootloader fallback detection, healthy mark, unhealthy rollback); POSIX-clean (dash/ash -n).
- `make_ota.py` (laptop: partial system-only VABC payload, minor 9, split signing), `sign_payload.py`
  (Mac: RSA-4096 over the two hashes), `make_manifest.py` + `verify_manifest.py` (signed manifest;
  tested end to end with throwaway keys, and the Java verifier accepts it), `build_otatools.sh`
  (one-time laptop build, NOT run), `check_publish.py` (pre-push guard; personal tokens matched by
  hash so the public file holds none).

## Updater app - `apps/Z9xUpdater` (see its CHANGES.md)
Built with build_apk.sh (VERSION_CODE=100, VERSION_NAME=1.0), reproducible, also in
`overlay/apps_v1/Z9xUpdater.apk`. UI checked on an emulator at 1920x1080 in English and Russian.
Strings: `apps/i18n/pending/ota/*.txt` (17 locales, gen.py-validated, test-built).

## Installer - `installer/`
`lumen-install.sh` (macOS/Linux; dry-run tested end to end against mock adb/fastboot),
`lumen-install.ps1` + `.cmd` (Windows beta, untested: no PowerShell here), partition allow-list,
`format` wipe (never `-w`/`erase`), vbmeta strictly manual, MTK codec extraction from the user's own
projector with sha256 allow-list, read-only calibration fingerprint before/after.

## Docs - `docs/`
`install/en.md`, `install/ru.md` (guide), `keys.md`, `ota.md`, `release.md`, `repo/` (LICENSE
Apache-2.0, NOTICE, gitignore, pre-push, publish_audit.py, PUBLISH_MANIFEST.tsv: 0 REVIEW).

## Not done (by decision or for later)
No release published, no OTA run, no laptop build of the OTA host tools, no vbmeta anything.

## Review fixes (2026-10-07, round 3)
- make_ota.py: fail-closed proprietary-blob gate on --new / --old (fsck.erofs --extract, check_image.py
  MTK_SHA / NEVER_PUBLIC / BLOB_PATHS); a private image needs --private, outputs are named -PRIVATE- and
  ota.json / META-INF metadata carry the private marker.
- make_manifest.py / verify_manifest.py refuse PRIVATE packages; verify_manifest --zips requires every
  listed package.
- sign_payload.py: no more signing oracle. It needs the whole payload.unsigned.bin on the Mac and
  --image (the verified system.img): parses the payload manifest (one partition "system", no
  postinstall, partial update, max_timestamp, new_partition_info size + sha256 = --image, delta
  old_partition_info = --old-image), recomputes both hashes like delta_generator HashPayloadForSigning,
  refuses any mismatch with the laptop's files and signs only its own digests. Self-tested on a
  synthetic payload; to be compared once with delta_generator's hashes before the first real OTA.
- check_release_assets.py (new): the gate before any `gh release`: no PRIVATE asset, every image read
  file by file with dump.erofs (works on the Mac), OTA payloads bound to a clean image, no keys/.so/.apk.
  On today's build/lumen_v1.img it reports the 6 MTK codec libs + 3 never-public files, as it must.
- check_publish.py: a private key passes only if its public key is a PUBLISHED AOSP test key
  (AOSP_TEST_PUBKEYS), never by path; `--push-stdin` checks every blob of the pushed commits;
  docs/repo/pre-push uses it. gitignore whitelists only apps/sdk/testkeys/platform.pk8.
- tools/sign: sign_release.sh names private images lumen-os-<ver>-PRIVATE-system.img and builds with
  mkfs.erofs --mkfs-time; sign_tar.py scopes every kept AOSP test certificate in mac_permissions to the
  packages that still carry it (no default seinfo; checked by --verify) and gives re-signed members the
  signing time as mtime; check_image.py checks the effective ro.adb.secure=1 / USB config / parse cache.
- installer: SHA256SUMS + SHA256SUMS.sig are required (fail closed); `--unsigned-image` (owner's own
  build, typed UNSIGNED) is the only way around it; the ERASE / new WRITE (--keep-data) confirmations name
  the XGIMI product/system_ext partitions that are deleted; release.conf publishes BLOBS=partition and
  the installer refuses a PRIVATE image without --blobs=embedded (and, with dump.erofs installed, a
  codec placeholder that does not match BLOBS). Same in lumen-install.ps1 (-UnsignedImage).

## Review fixes, round 4 (2026-10-07)
- check_release_assets.py: a zip with payload.bin or META-INF/com/android/metadata is an OTA whatever its
  name (variant + payload bound to a clean image; members outside the OTA set refused). Any other zip
  fails closed on a member that is itself an archive or image, by name (.zip .tar .gz .xz .img .bin
  payload.bin ...) or by magic (zip, gzip, xz, bzip2, zstd, lz4, 7z, rar, tar, sparse, CrAU, erofs,
  ext4), and on a private-key block. `--self-test` builds fixtures in a temp dir (nested zip holding the
  MTK libc2plugin_store.so, a renamed nested zip, an image in a zip, an OTA named -full.zip, the MTK lib
  by hash, a clean installer zip): all 7 OK.
- sign_payload.py: the variant comes from scanning --image on the Mac (check_release_assets.scan_image);
  --private is required if and only if the scan finds MediaTek / XGIMI content, and ota.json must agree.
- make_manifest.py: new required --image; it must scan clean and every package's payload must install
  exactly that image (new_partition_info sha256). docs/release.md updated.
- check_publish.py: DER private keys under any name (blobs < 16 KB starting with a DER SEQUENCE go to
  openssl pkey -inform DER; blocked unless a published AOSP test key) and the owner's release keys by
  hash from the local ~/.config/lumen/key_hashes (`--write-key-hashes`, written once on the Mac; it
  holds only hashes, mode 600). publish_audit.py inherits both through check_publish.check.
- installer: the image checksum cache is keyed by sha256 + size + mtime + inode (Windows: size +
  LastWriteTime), and `flash` always re-hashes the file it writes (FORCE_HASH / Check-Image -Force);
  unsigned_ok is keyed the same way. New --no-blobs / --blobs=none (Windows -NoBlobs): a public image
  without the codec files (skips 'blobs', refuses a private image); the allow-list mismatch message names
  it. An explicit BLOBS in the environment now wins over release.conf. Guides (en, ru) mention --no-blobs.
