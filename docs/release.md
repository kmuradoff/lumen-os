# Making a Lumen OS release (owner's checklist)

Nothing here publishes anything by itself: `gh release create` is always a separate, explicit step
with the owner's OK. Keys never leave the Mac; the build laptop only ever receives signed outputs.

## 0. Once

- Keys exist in `~/.lumen-keys` (`tools/sign/gen_keys.sh`, never re-run over existing keys) and are
  backed up twice offline (`docs/keys.md`).
- `~/.config/lumen/builder.env` on the Mac: `BUILDER=<user>@<laptop>` and `SSH_KEY=<key file>`
  (personal, never in the repo).
- Mac tools: Android SDK build-tools 36 (apksigner, zipalign, aapt2), JDK 21, `brew install erofs-utils
  e2fsprogs openssl`.
- Laptop, before the first OTA only: `tools/ota/build_otatools.sh` (one 10-20 min build under the
  thermal guard) -> `otatools-lumen.tar`.

## 1. Build the image (laptop, test keys)

`tools/lumen_v1.sh` composes `system_tv_lumen<ver>.tar` from the base tar + overlay/v1 + APKs built on
the Mac with `apps/build_apk.sh` (Z9xUpdater: `VERSION_CODE=100 VERSION_NAME=1.0`).

## 2. Sign and make the image (Mac)

```
bash tools/sign/sign_release.sh 1.0          # pull tar, sign_tar.py, mkfs.erofs, check_image.py, SHA256SUMS(.sig)
```

Output in `gsi/build/lumen/<ver>/` (never published from there): `lumen-os-<ver>-PRIVATE-system.img`
(default `VARIANT=private`: the owner's image with the MediaTek codec libs inside; NEVER a release
asset), `SHA256SUMS`, `SHA256SUMS.sig`, `sign-report/` (inventory, APEX, mac_permissions, re-signed
pins), `check-report/`. `VARIANT=public` names it `lumen-os-<ver>-system.img` and `check_image.py`
refuses any MediaTek/XGIMI file; **Lumen OS 1.0 cannot build a public image yet** (no placeholders /
z9x_blobs in `tools/lumen_v1.sh`), so 1.0 has no public image and no public release.

The 1.0 install on the owner's projector: `installer/lumen-install.sh --image <img> --blobs=embedded`
(flash + wipe; `--unsigned-image` only for a build without `SHA256SUMS.sig`).

## 3. An OTA (from 1.0.1 on)

```
# laptop (no keys). Refuses an image with MediaTek / XGIMI files unless --private (owner's device
# only: outputs named ...-PRIVATE-..., refused by make_manifest / verify_manifest / check_release_assets):
python3 tools/ota/make_ota.py unsigned --new <new system.img> [--old <previous release system.img>] \
        --version 1.0.1 --timestamp <ro.build.date.utc of the new image> --out ota-1.0.1
# Mac (keys): copy the WHOLE ota-1.0.1 dir here (payload.unsigned.bin included). sign_payload.py
# re-parses the payload, checks it installs exactly the system image verified on the Mac (sha256 +
# size in the payload manifest), scans that image itself (MediaTek / XGIMI content = PRIVATE: then
# --private is required, and ota.json's variant must agree), recomputes both hashes and signs only its
# own digests:
python3 tools/ota/sign_payload.py ota-1.0.1 --image <new system.img> [--old-image <previous img>]
# laptop: copy back payload.sig + metadata.sig, then
python3 tools/ota/make_ota.py finish --out ota-1.0.1 --cert tools/sign/release_certs/ota.x509.pem
# Mac: manifest (+ .sig), checked like the updater does
python3 tools/ota/make_manifest.py --version 1.0.1 --version-code 10001 --build-id lumen-1.0.1-<date> \
        --build-utc <utc> --image <new system.img> --full ota-1.0.1/lumen-os-1.0.1-full-ota.zip \
        [--delta ota-1.0.1/lumen-os-1.0.1-from-1.0-ota.zip=lumen-1.0-<date>] \
        --changelog changelog.json --out ota-1.0.1
python3 tools/ota/verify_manifest.py ota-1.0.1/update-stable.json --zips ota-1.0.1
```

The delta's `--old` image must be **byte-identical** to the system on the projectors (the published
image of that release); the codec files live outside `system` in public images for exactly this reason.

## 4. Before publishing

- `python3 docs/repo/publish_audit.py <gsi> docs/repo/PUBLISH_MANIFEST.tsv` and, on the exported repo,
  `python3 tools/ota/check_publish.py <repo>` (0 blocked). The pre-push hook runs it on every push.
- Release assets: system image, OTA zips + `-payload_metadata.bin`, `update-stable.json` + `.sig`,
  `SHA256SUMS` + `.sig`, the installer zip, the GPL source of the AirPlay app as a plain `.zip`.
  A zip asset may not contain another archive or image (zip, tar, gz, xz, img, payload.bin, ...: refused
  by name and by magic bytes); a zip with `payload.bin` or OTA metadata is checked as an OTA whatever
  its name. Publish images and OTA packages as assets of their own.
- **Gate, right before `gh release create`:** `python3 tools/ota/check_release_assets.py <the exact
  asset files>` must print OK: no `PRIVATE` asset, every system image read file by file (dump.erofs)
  without a MediaTek/XGIMI file (MTK_SHA, never-public paths, 0-byte blob placeholders), every OTA
  payload bound (new_partition_info sha256) to a clean image of the same run, no key / .so / .apk.
- Test on the owner's projector first (W5 in PLAN.md): updater "verify only", then the full update with
  the owner present, the rollback drill (`ro.z9x.ota.test_fail=1`), then a delta.
