# tools/sign: changes

## 2026-10-09: the no-Google edition gate (check_image.py --gms 0)

`--gms 0|1` (default 1). `check_variant(variant, gms, p)`: the exact manifest URL of the edition, the
build id suffix formula (`suffix_errors`: public ends in 'p', GMS=0 if and only if the core ends in 'n'),
`ro.z9x.gms=0` only on no-Google images, no-Google only as a public image. `check_nogms`: every APK and
APK inside an APEX on a Lumen release key (all_signers.tsv), no member at or under a
`tools/lumen/nogms_remove.txt` path, no com.google.* / com.mtg.* / com.android.vending package, no
gmsversion (build.prop, init files), no kept sysconfig / permissions / default-permissions XML naming GMS,
GSF or Play; writes `edition.txt`. `--gms 1` only checks that GMS and Play are there.
`load_nogms_list` / `under_any` / `GOOGLE_PKG` are shared with tools/ota/check_release_assets.py.

## 2026-10-07: every APEX re-signed, no AOSP test certificate left in the image

Lumen OS 1.0 (image `998e263c...`) left every APEX on its AOSP keys, which forced NetworkStack.apk and
CaptivePortalLogin.apk to keep the AOSP networkstack test certificate and kept scoped test `<signer>`
stanzas in `plat_mac_permissions.xml`. All of that is gone. Policy and the full procedure:
`docs/keys.md#apex`.

### New
- `apex_sign.py` (Mac): `run IN.tar OUTDIR` re-signs every APEX of the tar; `verify OUTDIR` repeats the
  offline checks. Inventory of the APKs anywhere in each payload, re-signing them (test key -> mapped
  release key, AOSP module development key -> releasekey), payload AVB footer with the module's new
  payload key (original algorithm / hash / salt / `apex.key` prop, as apexer), container rebuilt with
  the new `apex_pubkey` and signed with the module's new container key (zipalign 4096, apksigner
  v1+v2+v3 `--align-file-size`), `.capex` re-compressed with `apex_compression_tool` and signed again.
  Output: `OUTDIR/final/<member>`, `apex_signed.json` (orig + final sha256 per member),
  `report/{apex_signed.tsv,apex_apks.tsv,laptop_*.json}`.
- `apex_laptop.py` (laptop, key-free, driven over ssh by `apex_sign.py`): `repack` (deapexer extract +
  `apexer --unsigned_payload_only --build_info`, then an entry-by-entry comparison of the new
  filesystem with the original: paths, type, mode, uid, gid, xattrs, bytes), `compress`
  (`apex_compression_tool`), `verify` (deapexer info / list -Z / extract of original vs final,
  filesystem metadata, capex digest, apex_pubkey). Refuses to run where `~/.lumen-keys` exists.
- `apexlib.py`: shared key-free helpers (pinned avbtool, apex_manifest.pb reader, container reader,
  `verify_signed_apex` = all public-key checks of one re-signed APEX).
- `third_party/avbtool.py`: unmodified AOSP avbtool 1.3.0 (android-14.0.0_r67), sha256-pinned.
- Keys: `gen_keys.sh` now also creates, per module of `keymap.json` `apex.modules` (35 on Lumen OS 1.0),
  `~/.lumen-keys/apex/<m>.pem` (payload, RSA-4096) and `<m>.pk8` + `<m>.x509.pem` (container,
  RSA-4096), derives `<m>.avbpubkey` / `<m>.pubkey.pem`, and copies the public files to
  `release_certs/apex/` (+ `FINGERPRINTS.txt`). Generated on the owner's Mac on 2026-10-07.
- `keymap.json`: `apex` section (modules, payload algorithm, `inner_apk_default` = releasekey).

### Changed
- `sign_tar.py`: signing needs `--apex-dir` (the `apex_sign.py` output for the same tar, matched by
  sha256); the APEX members are replaced and their directory mtime bumped (parse cache). The
  APEX-bound "keep" class and the scoped test `<signer>` stanzas are removed: every test certificate in
  `mac_permissions` is replaced by its release certificate. APK extraction from APEX payloads covers the
  whole payload (not only app/ and priv-app/) and fails loudly if debugfs extracts nothing (paths with
  spaces are quoted). `--verify` adds the APEX checks (`apexlib.verify_signed_apex` with the public keys
  of `KEYS_DIR/apex`, which must equal `release_certs/apex`), requires every APK inside an APEX to be on
  a release key, fails on any test certificate left in `mac_permissions`, and writes `all_signers.tsv`
  (every APK, APEX container and APK inside an APEX: no AOSP test certificate, no unknown O=Android
  certificate).
- `check_image.py`: documents the APEX part of the keys gate; the image read-back covers every APEX.
- `sign_release.sh`: new step 2/6 `apex_sign.py` (builder from `BUILDER` / `builder.env`).
- `tools/lumen_v1.sh`: the sign stage runs `apex_sign.py` first (laptop `BUILDER`, tar
  `REMOTE_UNSIGNED`), then `sign_tar.py --apex-dir`; `remote` ships `apexlib.py`, `apex_laptop.py`,
  `third_party/` and `release_certs/apex/` (public files only) to the laptop; the laptop's public-cert
  dir for `check_image.py` includes `apex/`; the private-key guards look for `*.pk8` / `*.key` and any
  `PRIVATE KEY` block.
- `tools/lumen/lumen_checks.py signed`: APEX members may change only to the file pinned in
  `apex_signed.json` (`SIGN_APEX_MANIFEST`), every APEX must be re-signed, no APK and no
  `mac_permissions` may keep a test certificate.

### Dry run on the Lumen OS 1.0 base tar (`system_tv_lumen_v1_unsigned.tar`, sha256 5f246260...)
- `apex_sign.py run`: 35 APEXes (19 capex, 16 apex), 15 payloads rebuilt with 23 re-signed APKs
  (laptop repack: every entry identical except the swapped APKs), 20 payloads untouched (same root
  digest), Mac checks 35/35 OK, laptop deapexer verify 35/35 OK; about 4 minutes.
- `sign_tar.py --apex-dir`: 69 APKs re-signed (platform 41, releasekey 21, shared 3, media 2,
  networkstack 2), 13 presigned, 0 unclassified, 0 shared-uid conflicts, 0 leftovers;
  `plat_mac_permissions.xml`: platform, media, networkstack, sdk_sandbox, bluetooth, nfc -> release.
- `sign_tar.py --verify`: OK; APKs inside APEXes: platform 4, media 1, networkstack 1, bluetooth 1,
  sdk_sandbox 1, releasekey 15; `all_signers.tsv` without any AOSP test certificate.
- `lumen_checks.py signed` and `packages`: OK. `check_image.py --variant private` with the image built
  on the Mac: OK (211 members read back). The same gate on the Lumen OS 1.0 signed tar now fails (265
  errors: test-signed APEXes and their APKs, test certificates in mac_permissions), as it should.
- Tamper tests of `apexlib.verify_signed_apex`: a flipped payload byte, a re-zipped unsigned container,
  the wrong module's keys and an AOSP-signed original are all rejected.
- The real `lumen_v1.sh image` stage on the laptop (public certificates only, `KEYS_DIR` = a copy of
  `release_certs` incl. `apex/`, its own mkfs.erofs) on the same signed tar: labels, read-back of 83
  members and `check_image.py --base system_tv_v4.tar` OK (dry-run image, not for flashing; the laptop
  dry-run files were deleted afterwards). Image size 1 266 987 008 bytes (+3.7 MB against 1.0, from the
  re-compressed capex containers).
- Dry-run outputs kept on the Mac: `build/lumen_v1/apex_lumen_v1/` (re-signed APEXes + reports),
  `build/lumen_v1/dryrun_apex/` (signed tar, sign / verify / check reports and logs).

## 2026-10-08: Lumen OS 1.0.1
- `check_image.py`: OTA_FILES also lists the boot rescue (`z9x_rescue.rc` -> system/etc/init/z9x_rescue.rc,
  `z9x_rescue.sh` -> system/etc/z9x/z9x_rescue.sh): both must be in the image and byte-identical to
  tools/ota/image.

## 2026-10-09: Lumen OS 1.0.1 final (UI resolution, build id suffix)
- `check_image.py` gate `uires`: `system/etc/z9x/z9x_uires.sh` and the replaced
  `system/product/etc/init/init.lineage.atv.scaling.rc` must be byte-identical to `overlay/v1/z9x_uires`;
  the effective `ro.surface_flinger.max_graphics_width/height` must be 3840/2160 and `ro.z9x.uires.allow`
  0 or 1; `ro.config.size_override`, `ro.config.density_override`, `vendor.display-size` and
  `vendor.mstar.resize.framebuffer` are set exactly once by that rc and by no build.prop or other init file
  (system, system_ext and product init dirs are now read for this).
- `ro.z9x.build_id` must be `lumen-<ro.z9x.version>-<yyyymmdd>` with an optional suffix (`tools/lumen_v1.sh`
  BUILD_ID_SUFFIX, 1-4 of [a-z0-9], a letter first).

## 2026-10-09: Lumen OS 1.0.1 final, 4K default (build id lumen-1.0.1-20261009c)
- `check_image.py` gate `uires`, for the OSD override (a RAM copy of the panel ini bound read-only over the
  vendor file by `z9x_uires.sh fs`): the exec `z9x_uires.sh fs` must be in `on fs` of
  `system/product/etc/init/init.lineage.atv.scaling.rc` (the product dir is parsed after /vendor/etc/init, so it
  runs after the vendor's tvconfig mount and before the MI daemon) and no other init file may run
  `z9x_uires.sh` or carry a second file of that name; no init file and no script of ours in `system/etc/z9x` /
  `system/etc/xgimi` other than `z9x_uires.sh` may touch `/vendor/tvconfig` (comments aside); no `.ini` member,
  no tvconfig path and nothing under `vendor/` or `system/vendor/` in the image (no XGIMI file ships).
