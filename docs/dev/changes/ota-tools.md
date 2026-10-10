# Publishing guard: one local list, commit identities (2026-10-10)

- `check_publish.py`: `~/.config/lumen/personal_patterns` (local, mode 600, never published) is the one list
  of personal identifiers for this guard and for `docs/repo/export_repo.py`: `<regex>` or
  `<regex><TAB><replacement>` per line, and `!commit-identity<TAB><Name> <<e-mail>>`. `--push-stdin` also
  checks every pushed commit and annotated tag: author, committer and tagger must be a
  `users.noreply.github.com` identity or a local `!commit-identity`, and the message gets the personal-data
  checks (any other e-mail in it, an attribution trailer for example, blocks the push).
- `docs/repo/export_repo.py` (not published) holds no personal literal: generic rules (LAN IPs, home paths
  only where a path starts, the scratch path, MAC addresses that are not part of a longer hex run such as a
  certificate fingerprint, e-mails other than EMAIL_OK) plus the local list. The export scripts are copied
  as they are, and the export refuses to run without the local list. The old home-path rule had rewritten
  `org/z9x/home/...` to `org/z9x$HOME/...` in published files (apps/Z9xHome/tools/run_tests.sh).
- `docs/repo/publish_audit.py`: PRE_RULES (single files out of a collapsed folder: the stock remote's
  getevent capture is published as `docs/interop/stock_getevent_remote.txt`, `docs/4pda/shots/01_home.jpg`
  is excluded by name), `*.mp4` / `*.wav` excluded, a BIG (> 5 MB) file is excluded, and every check sees the
  file under its published path.

# 1.0.1, Google services add-on for the no-Google edition (2026-10-10, image/HANDOFF.md section 2b)

- `image/z9x_gapps.{sh,rc}`, `image/gapps_allow.txt`, `image/z9x-gapps-sysconfig.xml` (both public editions):
  at post-fs-data, only with `ro.z9x.gms=0` and the user's `z9x_gapps<slot>`: read-only ext4 mount
  (system_file context, block device read-only, 2 s timeout), MANIFEST and every file (name, size) against the
  allow-list, nothing else in the partition, then read-only lower-only overlays (the sysconfig layer over
  /system/etc/sysconfig; product, system_ext/priv-app, system_ext/etc/permissions, system/app,
  system/etc/permissions), all or nothing, and `ro.com.google.gmsversion`. One 2 s time budget for the whole
  boot run (`/proc/uptime`, checked before the mount, every find and every overlay; over it `bad:slow`; tree
  listings 1 s each), so at most about 3 s while init waits. Off (marker `/metadata/z9x_gapps/off` =
  `<n> <slot> <ro.z9x.build_id>`) at the 3rd start in a row without boot_completed, only for that slot and
  build: after an OTA rollback (the gate rolls back after the marker) or a `rescue` with another build the
  add-on stays on. `check` mode for the installer (no budget). Result `sys.z9x.gapps`, `sys.z9x.gapps.zip`.
- `gapps_allow.py`: the allow-list's format and rules (`gen` a block from a zip, `check`); the add-on's files
  are the zip's members the no-Google edition leaves out (nogms_remove.txt) minus gapps.rc; `part=` formula.
- Installer `gapps --zip FILE` / `--remove` / status (sh + ps1), `lib/gapps_fill.sh` (runs on the projector),
  `lib/gapps_allow.txt` (= the image's); `fb_allowed` gains create / resize / format / delete of
  `z9x_gapps<slot>`; `gsf` works when GSF was added; `verify` reports the add-on; `flash` names a kept add-on.
  `gapps --zip` with the zip that is already on stops before anything is written (writing it again would
  mean one start without the Google apps, and PackageManager deletes their data); `--keep-data` over an
  add-on that is there says that Google signs out and the GSF ID needs registering again; status explains
  `bad:slow`.
- `lumen_v1.sh` VARIANT=public: `gapps_members`, `gapps_preflight` (rc, mounts, setprops, the one write, no
  unpacking; allow-list = installer copy + `gapps_allow.py check`), the sysconfig layer's drift check in the
  XML preflight; `remote` also sends `tools/ota/gapps_allow.py`. `check_image.py`: `check_gapps_addon`.
- Tests: `test/gapps.sh` (+ `gapps_fixture.py`, stubs `umount`, `blockdev`, `mount` for ext4 / overlay),
  `test/installer_gapps.sh`, new checks in `test/public_members.sh`.

# 1.0.1, no-Google edition (2026-10-09, docs/NOGMS_PLAN.md section 7)

- Only "Lumen OS без Google" is published. `check_release_assets.py`: an image must be the no-Google
  edition by name (`-nogms-`) and by its product build.prop (`ro.z9x.gms=0`, `update-public-nogms.json`),
  without a `tools/lumen/nogms_remove.txt` member or a com.google.* / com.mtg.* / Play APK; an OTA zip
  needs `lumen-edition=nogms` and `-nogms-` in its name; `update-*.json` other than
  `update-public-nogms.json` is refused. `scan_image(edition=True)` only for the gate (sign_payload /
  make_manifest keep deciding the variant by MediaTek / XGIMI content). `--self-test`: image fixtures
  (mkfs.erofs) and a fake APK manifest.
- `make_ota.py`: the product build.prop of every image; a delta across variants or editions is refused;
  ota.json `edition` + `manifest_file`; metadata `lumen-edition=`; `package_name()` (-nogms- if and only
  if nogms).
- `make_manifest.py`: `--channel` in stable / public / public-nogms and equal to the image's (and the old
  image's) manifest file; every package's `lumen-edition` is the image's.
- `verify_manifest.py`: file name = `update-<channel>.json`; with `--zips` the packages' edition.
- Tests: `test/nogms_check.py`, `test/nogms_ota.py`, `test/installer_edition.sh`, new cases in
  `test/public_members.sh`.

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

## Lumen OS 1.0.1: boot safety (2026-10-08)

After the first real OTA (1.0.0, logs/friend_<serial>): `/system/bin/bootctl` is not in the image,
so every bootctl call of the gate failed (needless "rollback" reboot of a healthy slot, a rollback that
could not switch slots, then `rolledback` published on the new slot); and userdata uses
`checkpoint=fs`, so vold marks the slot at boot_completed and the merge starts at once: no rollback is
possible after boot_completed. Design and measurements: docs/ota.md, "The boot gate" / "Boot rescue".

- `image/z9x_ota.sh`: boot HAL through `service call android.hardware.boot.IBootControl/default`
  (strict one-line Parcel parsing; any other reply = failure, logged, answer unknown; 2 s timeout
  each; bootctl only as a fallback when present). Protects only the time before boot_completed:
  attempt counter at post-fs-data (`<n> <slot>`), rollback at attempt 4 and at the 8 min deadline,
  only with a definite "not marked", no merge, other slot not unbootable, accepted + read-back
  setActiveBootSlot; otherwise no reboot and `no_rollback` (the deadline then restarts with
  `reboot,z9x-ota-stuck`). After boot_completed: slot marked by vold -> `merging`/`marked` at once,
  health checks 90 s later only reported (`unhealthy` + new `sys.z9x.ota.why`), never a reboot; a slot
  left unmarked is marked via the HAL when healthy. `rolledback` only when the recorded update slot is
  not the running one; a rollback that did not take effect is detected and not repeated. `verifier`
  makes no HAL call (reads `sys.z9x.ota`). Drill `ro.z9x.ota.test_fail=1` now rolls back at
  post-fs-data. Kill switch and property contract kept (`unhealthy`, `sys.z9x.ota.why` added).
- `image/z9x_rescue.{sh,rc}` (new): owner's fastboot rescue. Counter `/metadata/z9x_rescue/count`
  (`<n> <slot>`) incremented by an exec at `on init`, reset at `sys.boot_completed=1`; when the 3
  starts before this one all failed: reset, log, `reboot,fastboot`. Per slot (a slot change starts at
  0); waits for the OTA gate's own rollback on an update slot (at most 3 extra starts, none once
  `no_rollback` names the slot). Kill switch `ro.z9x.rescue=0` or `/metadata/z9x_rescue/off`. Log
  `/metadata/z9x_rescue/log.txt` (rotated at 64 KiB) + logcat `z9x_rescue`.
- `image/z9x_ota.rc`, `update_verifier.rc`: comments for the new design (triggers unchanged).
  `image/ota_props.txt`: `ro.z9x.rescue=1`. `image/HANDOFF.md`: new files, properties, reboot reasons,
  integration steps for `tools/lumen_v1.sh` and `check_image.py` `OTA_FILES`.
- `test/run.sh` + `test/stubs/` (new): host harness, POSIX sh with stubbed getprop/setprop/service
  (boot HAL replies in the measured shape, fault injection: no service, exception status, multi-line,
  garbled, hang, ignored setActive)/log/timeout/sleep/pidof and a temp root. 46 cases (normal boot,
  vold marks before health, unmarked + healthy, unhealthy rollback, 4th attempt, deadline, drill, mark
  request, restart during merge, every HAL failure, bootctl fallback, 1.0.0's leftovers, bootloader
  fallback, rollback that did not take, rescue 1-2-3 -> fastboot, reset at boot_completed, slot change,
  OTA grace, kill switches, unwritable / corrupt counter, log rotation, gate + rescue together). All
  pass with `sh`, `SH=dash` and `SH=bash`.
- `test/installer_rescue.sh` (new): 16 mock cases of `lumen-install.sh rescue` (fake fastboot / adb,
  throwaway signing key in a temp dir), all pass.
- `installer/`: new command `rescue` in `lumen-install.sh` and `lumen-install.ps1` (Windows: written,
  not run, no PowerShell here): image check like `flash`, one WRITE confirmation, waits up to 15 min
  for fastbootd (`mt5877`, `is-userspace` yes, unlocked), slot from fastbootd, refuses a slot that still
  has XGIMI's product/system_ext, finishes (`snapshot-update merge`) or cancels (`snapshot-update
  cancel`) an unfinished update of that slot, deletes `*-cow`, writes system (+ z9x_blobs from the saved
  codec image when BLOBS=partition), reboots. Never formats or wipes. Help, README, guides (en, ru:
  section "Rescue") updated.

### Review fixes (1.0.1, same day)
- `z9x_ota.sh` `hal()`: `${out#Result: Parcel(}` is a syntax error in ksh93 (a bare `(` opens a pattern
  group there) and the device shell, mksh, is a ksh: a parse error would have stopped the whole gate,
  including `verifier`. The pattern is quoted now; `test/run.sh` also runs `ksh -n`, and `SH=ksh`
  runs every case under ksh93 (its builtin sleep dropped): all pass.
- Rollback refused when the boot HAL merge status is `CANCELLED`: `lumen-install rescue` cancels an
  unfinished update and then writes this slot's system over storage the old slot shares (Virtual A/B),
  so rolling back there later would start a damaged slot.
- `setActiveBootSlot` with a reply of an unmeasured shape: when the active slot already reads the other
  slot, the rollback reboot happens (before, the boot went on with the old slot active behind a new
  slot that vold then marks and update_engine merges).
- A `no_rollback` record of this slot found at the first attempt of an update (left by an earlier
  update, e.g. with 1.0.0 on the old slot in between, which does not know the file) is removed instead
  of taking the new update's rollback away.
- Installer `rescue` (sh + ps1, en + ru) and the guides: say that a cancelled update may leave the other
  slot unable to start. `HANDOFF.md`: the `fastboot` word in `lumen_v1.sh`'s block-device grep must not be
  applied to `z9x_rescue.sh`; lint with `ksh -n` too.
- `test/run.sh`: 49 cases (cancelled update, stale `no_rollback`, odd `setActiveBootSlot` reply).

### Integration (1.0.1, 2026-10-08)
- Wired into the image by tools/lumen_v1.sh (z9x_rescue.sh 0755 + z9x_rescue.rc 0644, lint as in HANDOFF.md,
  `ro.z9x.rescue=1` asserted) and tools/sign/check_image.py (OTA_FILES). ota_props.txt: build_id
  `lumen-1.0.1-<DATE>`, version_code 10002 (VER=1.0.1).

## Public image variant (1.0.1, 2026-10-09)

- `tools/lumen_v1.sh VARIANT=public` (default `private`, unchanged): the 6 Codec2 libs become 0-byte
  placeholders (0644, system_lib_file); the base's three XGIMI / MediaTek audio files
  (`system/etc/xgimi/audio_policy_configuration.xml`, `libstagefright_foundation{,64}.so`) are removed and
  `xgimi_compat.rc` is replaced by `image/xgimi_compat_public.rc` (the same without their binds);
  `z9x_blobs.{sh,rc}` + `blobs_allow.txt` are added (HANDOFF.md section 2). Props `ro.z9x.variant=public`,
  `ro.z9x.ota.manifest_url=.../update-public.json`; build id suffix must end in `p` (private: never).
  Outputs `system_tv_lumen_v1_public*`; `remote` also writes `$LUMEN_OUT/release/` (lumen-os-<ver>-system.img,
  SHA256SUMS, SHA256SUMS.sig with the OTA key) and runs `check_release_assets.py` on it.
- Found while wiring it: without the audio files a public image has no sound at all (the vendor audio HAL
  needs MediaTek's VNDK libstagefright_foundation, the AOSP parser needs the audio policy without XGIMI's
  enums). `blobs_allow.txt` (set `z9x-v61558-b`) now lists 9 files: 6 placeholder lines + 3 `bind=` lines
  that `z9x_blobs.sh` binds over the vendor / VNDK APEX file directly; `from=<sha256>:<'N,Md'>` lets the
  installer derive the audio policy from XGIMI's stock original (verified against the v6.15.58 dump: the
  line deletion gives exactly the private image's copy).
- `z9x_blobs.sh`: no lpdump fallback (on the device lpdump is a binder client of lpdumpd, which init
  cannot start while it waits for this exec: every boot without the partition would have stalled ~5 s
  before early-boot, PWM-watchdog territory); first-stage init maps every logical partition with
  extents, so `/dev/block/mapper/z9x_blobs<slot>` is the only source. tmpfs 8 MiB (with 4 MiB the
  partition copy left no room for the files: every boot would have ended in `bad:tar`). dd bounded by
  `timeout 3`. `Z9X_ROOT` for the host test. Labels: .so system_lib_file, others system_file.
- Installer (sh + ps1): `blobs` reads the optional fields, derives `from=` files, messages say that
  without these files there is no sound; ps1 accepts V6.15.19 like the sh installer.
- `check_image.py --variant public`: MTK_SHA + the four audio hashes, placeholder mode/label, the
  z9x_blobs files and the public xgimi_compat.rc byte-identical, every allow-list hash known, no init
  file using a dropped file; variant marks checked for both variants. `check_publish.py`: the four
  hashes. `check_release_assets.py`: accepts `SHA256SUMS`. `make_manifest.py`: channel by variant
  (`public*` only for clean images), default vendor list v6.15.58 + v6.15.19.
- Tests: `test/blobs.sh` (14 cases x sh/dash/ksh + the allow-list format), `test/installer_blobs.sh`
  (12 cases incl. the round trip installer -> z9x_blobs.sh), `test/public_members.sh` (lumen_v1.sh
  `members` target on a test base + check_image's public rules, negatives); `blobs_fixture.py` makes
  the fake files (no proprietary file in any test).

### Review fixes (public image, same day)

- Personal data would have shipped in the image (both variants): the six odex of our apps stored the
  dex2oat command line (key `dex2oat-cmdline`: the build laptop's home paths, i.e. its login), and
  `z9x_ota.sh` named the second projector's serial in a comment. `tools/lumen/dexpreopt_lumen.sh` passes
  `--avoid-storing-invocation` (as soong does; the laptop's dex2oatd64 supports it) and
  `lumen_checks.py oatcheck` refuses an odex with that key; the comment says `logs/friend_<serial>`.
- `check_release_assets.py` now scans every file of an image, every zip member and every text asset for
  personal data: `check_publish.py` PERSONAL_TOKEN_SHA by token hash (streamed, words carried across
  chunk boundaries) + the local `personal_patterns`. On the private 20261009e image it reports exactly
  those 7 files besides the 9 MediaTek / XGIMI ones. `check_publish.py`: the second serial's hash, and
  tokens are also matched inside `_`-joined words (`friend_<serial>` was one token before).
- `z9x_blobs.sh`: its tmpfs is required (`bad:tmpfs`; on `/mnt`, which first-stage init mounts noexec,
  the bound libraries could not be loaded) with nosuid,nodev, and is remounted read-only after the binds
  (every bind shares it); the untar is bounded (`timeout 3`), a `..` member name is refused before
  extraction (`bad:path`), and any entry that is not a directory or a regular file <= 4 MiB is refused
  before hashing (`bad:entry`: a symlink out of the tmpfs, a device, a sparse giant that sha256sum would
  read for minutes while init waits). Preflight checks all of it. New host cases `bad_symlink`,
  `bad_sparse` (an old-GNU sparse member of 64 MiB), `bad_dotdot` (17 cases per shell now); each fails
  against the previous script.
- The partition survives an OTA only because every Lumen payload is partial (a non-partial payload makes
  libsnapshot delete `z9x_blobs_<target>`): said in z9x_blobs.sh, HANDOFF.md, docs/ota.md and at the
  `--is_partial_update` of make_ota.py (sign_payload.py already refuses a non-partial payload).
- `tools/lumen_v1.sh remote`, public: `check_release_assets.py` runs on the image BEFORE SHA256SUMS is
  written and signed, dump.erofs is required (was a WARN), and a failure deletes the three release files;
  overlay/v1/c2store is no longer sent to the laptop (excluded, so the laptop's copy for private builds
  stays).
- docs/release.md: the Google apps (MindTheGapps) in the image are an open owner decision before the
  first public release; no gate checks them.
