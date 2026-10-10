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
the Mac with `apps/build_apk.sh` (Lumen OS 1.0.1: every app and RRO of ours is versionName `1.0.1`,
versionCode 102; the env-built ones with `VERSION_CODE=102 VERSION_NAME=1.0.1`, see
`overlay/apps_v1/README.txt`; 1.0.0 was `1.0.0` / 101). The OS version is `VER` in `tools/lumen_v1.sh`; `ro.z9x.version_code` in
`tools/ota/image/ota_props.txt` must follow it (docs/ota.md, "Version numbers").

## 2. Sign and make the image (Mac)

Lumen OS 1.0.x: `tools/lumen_v1.sh remote` runs the whole chain from the Mac (prep on the laptop, APEX +
APK signing on the Mac, erofs + `check_image.py` on the laptop, image pulled back). The variant decides
what the image may carry (docs/ota.md "Variants"):

```
# the RELEASE: Lumen OS without Google ("Lumen OS без Google"; public, no MediaTek / XGIMI file, no Google
# app; build id suffix ending in 'np')
VARIANT=public GMS=0 LUMEN_OUT=build/lumen_v1-1.0.1n BUILD_DATE=20261009 BUILD_ID_SUFFIX=fnp bash tools/lumen_v1.sh remote
# the owner's PRIVATE image (default; MediaTek / XGIMI files and Google apps inside, never a release asset)
LUMEN_OUT=build/lumen_v1-1.0.1j BUILD_DATE=20261009 BUILD_ID_SUFFIX=e bash tools/lumen_v1.sh remote
# lab only: the public image WITH Google apps (suffix ending in 'p', not 'np'); never published, so no
# release files are written for it
VARIANT=public LUMEN_OUT=build/lumen_v1-1.0.1p BUILD_DATE=20261009 BUILD_ID_SUFFIX=ep bash tools/lumen_v1.sh remote
```

Owner decision (2026-10-09): **only the no-Google edition is published; the Google edition never is.**
`GMS=0` is allowed only with `VARIANT=public`. `VARIANT=public GMS=0` writes, besides
`gsi/build/lumen_v1_public_nogms.img`, the release files in `$RELDIR` (default `$LUMEN_OUT/release/`):
`lumen-os-<ver>-nogms-system.img`, `SHA256SUMS` and `SHA256SUMS.sig` (signed on the Mac with the OTA key;
the installer checks it with `installer/certs/ota.x509.pem`), after `check_image.py --variant public
--gms 0` (laptop: no MediaTek / XGIMI hash over every member, 0-byte codec placeholders, the z9x_blobs
pieces byte-identical, `ro.z9x.variant=public`, `ro.z9x.gms=0`, `update-public-nogms.json`; every APK on a
Lumen release key, no `nogms_remove.txt` path, no com.google.* / com.mtg.* / Play package, no
gmsversion, no config XML naming GMS / GSF / Play) and `tools/ota/check_release_assets.py` on the image
BEFORE `SHA256SUMS` is written and signed (Mac, dump.erofs, required: every file of the image, MediaTek /
XGIMI files, Google members and packages, the edition by name and by prop, personal data such as device
serials or the build laptop's login), then on `SHA256SUMS` + `.sig`. If any step fails, all three
files are deleted again; another image in `$RELDIR` stops the step. A public build does not send
`overlay/v1/c2store` to the laptop. The public image needs the installer's `blobs` step
(`BLOBS=partition`): without the user's own nine files it has no sound and no secure video. Install it
with `installer/lumen-install.sh --image $RELDIR/lumen-os-<ver>-nogms-system.img` (blobs, flash + wipe).

The older `tools/sign/sign_release.sh` (1.0 test builds) remains for reference: `VARIANT=private` names its
image `lumen-os-<ver>-PRIVATE-system.img`, `VARIANT=public` `lumen-os-<ver>-system.img`; it runs mkfs.erofs
with `--mkfs-time`, which `tools/lumen_v1.sh` forbids for Lumen OS 1.0 images.

The owner's private install: `installer/lumen-install.sh --image <img> --blobs=embedded`
(flash + wipe; `--unsigned-image` only for a build without `SHA256SUMS.sig`).

## 3. An OTA (1.0.0 over the 1.0 test builds, then 1.0.1 ...)

```
# laptop (no keys). Refuses an image with MediaTek / XGIMI files unless --private (owner's device
# only: outputs named ...-PRIVATE-..., refused by make_manifest / verify_manifest / check_release_assets):
python3 tools/ota/make_ota.py unsigned --new <new system.img> [--old <previous release system.img>] \
        --version 1.0.1 --timestamp <ro.build.date.utc of the new image> --out ota-1.0.1
# (a delta between two editions or variants is refused; ota.json records the edition)
# Mac (keys): copy the WHOLE ota-1.0.1 dir here (payload.unsigned.bin included). sign_payload.py
# re-parses the payload, checks it installs exactly the system image verified on the Mac (sha256 +
# size in the payload manifest), scans that image itself (MediaTek / XGIMI content = PRIVATE: then
# --private is required, and ota.json's variant must agree), recomputes both hashes and signs only its
# own digests:
python3 tools/ota/sign_payload.py ota-1.0.1 --image <new system.img> [--old-image <previous img>]
# laptop: copy back payload.sig + metadata.sig, then
python3 tools/ota/make_ota.py finish --out ota-1.0.1 --cert tools/sign/release_certs/ota.x509.pem \
        [--name lumen-os-1.0.1-nogms-full-ota.zip | lumen-os-1.0.2-nogms-from-1.0.1-ota.zip]
# (default name lumen-os-<ver>-nogms-<full|delta>-ota.zip; -nogms- if and only if the no-Google edition;
#  META-INF metadata lumen-edition=nogms)
# Mac: manifest (+ .sig), checked like the updater does
python3 tools/ota/make_manifest.py --version 1.0.1 --version-code 10002 --build-id lumen-1.0.1-<date>np \
        --build-utc <utc> --image <new system.img> --full ota-1.0.1/lumen-os-1.0.1-nogms-full-ota.zip \
        [--delta ota-1.0.1/lumen-os-1.0.1-nogms-from-<prev>-ota.zip=<previous no-Google build id, ends in np>] \
        --channel public-nogms --blobs-set z9x-v61558-b --changelog changelog.json --out ota-1.0.1
python3 tools/ota/verify_manifest.py ota-1.0.1/update-public-nogms.json --zips ota-1.0.1
```

Channels (docs/ota.md "Variants"): `--channel` must be the image's own manifest file
(basename of its `ro.z9x.ota.manifest_url`): `public-nogms` for the published no-Google images
(`update-public-nogms.json`), `stable` for clean deltas between two PRIVATE images (the owner's
projector), `public` only for lab tests of the Google public image (never published). Every package's
`lumen-edition` must be the image's. A delta's `--old` image and `from_build_id` are those of the
previous no-Google release (build ids ending in `np`).

The delta's `--old` image must be **byte-identical** to the system on the projectors (the published
image of that release); the codec files live outside `system` in public images for exactly this reason.

`make_ota.py unsigned` also needs `--super-group NAME=SIZE`: system's group in `super` without the slot
suffix and its maximum size, from `adb shell lpdump` on the projector (libsnapshot resizes system in the
other slot only for a partition listed in a group of the payload). The partition version
(`--partition_timestamps=system:<ts>`) is added by the script.

**Clean delta between two PRIVATE images** (1.0 -> 1.0.0: the owner's projector, before the public image;
`update-stable.json`, never the public channel):
`make_ota.py unsigned ... --old <old private img> --clean-delta`, then on the Mac
`sign_payload.py DIR --image <new> --old-image <old> --clean-delta`, `make_ota.py finish --name
lumen-os-<ver>-from-<old ver>-ota.zip`, `make_manifest.py ... --delta ZIP=<old build_id> --old-image <old>`
(no `--full`), `verify_manifest.py ... --delta-only`, `check_release_assets.py --clean-delta <new> <old>
<assets>`. Each of these re-runs `tools/ota/delta_proof.py`: every operation of the payload is checked
against the byte ranges of the MediaTek / XGIMI files of both images (SOURCE_COPY carries no data; no
REPLACE / diff may write or read a protected block). The private images are never assets. Since the
owner decision of 2026-10-09 such a delta is the Google edition (`lumen-edition=gms`), so
`check_release_assets.py` refuses it as a release asset: it is for the owner's projector over a USB
stick (the updater's "Install from a USB stick" reads `update-stable.json` there), not for GitHub.

## 4. Before publishing

- `python3 docs/repo/publish_audit.py <gsi> docs/repo/PUBLISH_MANIFEST.tsv` and, on the exported repo,
  `python3 tools/ota/check_publish.py <repo>` (0 blocked). The pre-push hook runs it on every push.
- Release assets: `lumen-os-<ver>-nogms-system.img`, the `-nogms-` OTA zips + `-payload_metadata.bin`,
  `update-public-nogms.json` + `.sig`, `SHA256SUMS` + `.sig`, the installer zip
  (`lumen-os-<ver>-installer.zip`, the name the install guide gives), the GPL source of the
  AirPlay app as a plain `.zip`. Nothing of the Google edition (image, OTA zip, `update-public.json`,
  `update-stable.json`): `check_release_assets.py` refuses it.
  A zip asset may not contain another archive or image (zip, tar, gz, xz, img, payload.bin, ...: refused
  by name and by magic bytes); a zip with `payload.bin` or OTA metadata is checked as an OTA whatever
  its name. Publish images and OTA packages as assets of their own.
- **Gate, right before `gh release create`:** `python3 tools/ota/check_release_assets.py <the exact
  asset files>` must print OK: no `PRIVATE` asset, every system image read file by file (dump.erofs)
  without a MediaTek/XGIMI file (MTK_SHA, never-public paths, 0-byte blob placeholders), every image the
  no-Google edition by name and by prop with no Google member or package, every OTA zip
  `lumen-edition=nogms` with `-nogms-` in its name and its payload bound (new_partition_info sha256) to a
  clean image of the same run, the only manifest `update-public-nogms.json`, no key / .so / .apk, and
  no personal data in any asset, image file or zip member (`check_publish.py` PERSONAL_TOKEN_SHA by token
  hash + the local `~/.config/lumen/personal_patterns`).
- **Owner's decision (2026-10-09): only "Lumen OS без Google" is published.** The Google edition (the
  base tar's MindTheGapps ATV, which this repository keeps out of git as Google proprietary apps) is
  never published, as an image, an OTA package or a manifest; `check_release_assets.py` enforces it. Users
  who want Google services add them themselves on their own projector (installer `gapps --zip FILE`,
  docs/install, "Adding Google services"): their own MindTheGapps download goes into `z9x_gapps_<slot>`.
  Switching edition is a reinstall with a data wipe, never an OTA.
- **Google services add-on and updates.** Every release image must keep every published `zip=` block of
  `tools/ota/image/gapps_allow.txt` (= `installer/lib/gapps_allow.txt`; a projector keeps its
  `z9x_gapps_<slot>` across updates and `z9x_gapps.sh` refuses a zip its image does not list), and every
  payload must stay PARTIAL (it keeps `z9x_gapps_<slot>` like `z9x_blobs_<slot>`). A new MindTheGapps zip
  gets a new block (`python3 tools/ota/gapps_allow.py gen ZIP --url URL`), never a replaced one.
- Test on the owner's projector first (W5 in PLAN.md): updater "verify only", then the full update with
  the owner present, the rollback drill (`ro.z9x.ota.test_fail=1`), then a delta.
