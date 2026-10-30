# Map: image tooling across devices (2026-10-30)

Read-only survey of how a Lumen OS system image is described, built, signed, installed and released for each
projector today, what is repeated per device, and the steps it takes to add a projector. Facts only, with
`file:line` citations (paths relative to `gsi/`). Nothing was changed to write this.

Devices: horse_popular = XGIMI Z9X (G0082), dragon_healthy = XGIMI Play 6 (G0058), goat_elegant = XGIMI Z9X HB
(G0130).

## 1. Summary

- A device is described in two data files: `devices/<codename>/device.conf` (identity, ABI, HAL, slots, keys, OTA,
  installer id) and `devices/<codename>/image.conf` (how its image is composed). Both are parsed by one library,
  `tools/devices/devices.py`, never sourced (`tools/devices/devices.py:15-59`).
- Only one device (goat_elegant) actually goes through the generic path (`generic=yes`,
  `devices/goat_elegant/image.conf:4`). The Z9X and the Play 6 say `generic=no` and keep their own pipelines:
  `tools/lumen_v1.sh` (1810 lines) and `tools/play6/build_test.sh` + `tools/play6/compose.sh`
  (`devices/horse_popular/image.conf:4-5`, `devices/dragon_healthy/image.conf:4-6`).
- So there are three image composers, three signing paths, three key layouts, two installer flows (a third is
  designed but not built), and three release/OTA stories. The registry describes all three devices, but most
  tools still branch on the device by name.
- The constraint behind this shape: the Z9X must stay byte-identical, so every Z9X gate input
  (`tools/lumen_v1.sh`, `tools/ota`, `tools/sign`, `tools/lumen`, `overlay/v1`, `overlay/apps_v1`, `apps/Z9x*`, ...,
  `tools/play6/z9x_gate.sh:19-23`) is frozen. New devices are added beside it, not by refactoring it
  (`devices/DESIGN.md:10-30`).

## 2. How a device is described

### 2.1 `devices/<codename>/device.conf` (the registry)

Format: `key=value`, `#` comments, lists space-separated, unknown keys an error, folder must equal codename
(`tools/devices/devices.py:15-17`, `:106-128`, `:233-235`).

Required keys (`tools/devices/devices.py:68-70`) and their meaning (`:19-46`):

| group | keys |
|---|---|
| identity | codename, name, models, platform, hardware, fastboot_product |
| system | abi, base_product, vendor_sdk, vendor_firmware, hal_transport, image_fs, slots, prop_namespace |
| install / OTA | install_flow, ota_engine, ota_wire_id, installer_id, channel |
| keys / apps | keys_dir, app_prefix, apps (optional), hal_app |
| checks | forks, hal_check, hal_codes, hal_banned, hal_allowed (optional, required for aidl-table) |

Current values side by side: `devices/horse_popular/device.conf:3-28`, `devices/dragon_healthy/device.conf:3-28`,
`devices/goat_elegant/device.conf:3-32`. Differences that matter for tooling:

| key | Z9X | Play 6 | Z9X HB |
|---|---|---|---|
| abi | arm64 | arm32 | arm32 |
| base_product | prebuilt-tar system_tv_v4.tar | lineage_gsi_tv_arm | lineage_gsi_tv_arm |
| hal_transport | hidl | hidl | aidl |
| image_fs | erofs | ext4 | erofs |
| slots / install_flow | ab-virtual / z9x-usb | single / play6-wifi | ab-virtual / ab-fastbootd |
| ota_engine | update_engine | recovery-block | none |
| prop_namespace | ro.z9x | ro.lumen | ro.lumen |
| keys_dir | ~/.lumen-keys | ~/.lumen-keys/play6 | ~/.lumen-keys/goat_elegant |
| hal_check | z9x-callers | play6-hidl | aidl-table |

Closed vocabularies are hardcoded in the library: `abi`, `hal_transport`, `image_fs`, `slots`, `install_flow`,
`ota_engine`, `hal_check` (`tools/devices/devices.py:73-81`). A new install flow, OTA engine or HAL check needs a
code change there, not only data.

Note: `devices/DESIGN.md:94` and `:107` still say goat_elegant `fastboot_product=goat_elegant`; the registry says
`mt5873` (`devices/goat_elegant/device.conf:23`), which matches the later RUNBOOK measurement
(`devices/goat_elegant/install/RUNBOOK.md:348`). DESIGN is stale on this point.

Validation (`devices.py validate`, `tools/devices/devices.py:223-270`): required keys, enums, uniqueness of
codename / installer_id / app_prefix, ab-fastbootd needs ab slots, aidl-table needs hal_allowed, ota_wire_id
none exactly when ota_engine none, keys_dir under `~/.lumen-keys` and outside the tree, path keys exist.

### 2.2 `devices/<codename>/image.conf` (the image recipe)

Keys `generic entry compose host remote_dir stage variants fs` + optional `stage_dir`
(`tools/devices/devices.py:48-58`, `:83-85`). `fs` must equal device.conf `image_fs` (`:201-202`).

| | Z9X | Play 6 | Z9X HB |
|---|---|---|---|
| generic | no | no | yes |
| entry | tools/lumen_v1.sh | tools/play6/build_test.sh | tools/image/compose.sh |
| compose | none | tools/play6/compose.sh | devices/goat_elegant/image/compose_hb.sh |
| host | builder.env | builder.env | xgimi-builder |
| remote_dir | z9x/out | play6-work | hb-out |
| stage | none | none | 33 explicit paths |
| variants | (empty) | (empty) | adb:--usb-adb stick: |

Sources: `devices/horse_popular/image.conf:4-11`, `devices/dragon_healthy/image.conf:4-11`,
`devices/goat_elegant/image.conf:4-12`. For `generic=no` devices image.conf is documentation only: nothing reads
`entry`, `remote_dir` etc. to drive their builds; their scripts carry the same facts inline.

### 2.3 Other per-device description files (outside the registry)

| file | what | devices |
|---|---|---|
| `installer/lib/devices.conf` | installer profile `id.KEY=value` (NAME, MODEL, BOARD, FB_PRODUCT, VENDOR_OK, CERT, IMAGE_TOKEN, IMAGE_FS, VERSION_PROP, LUMEN_PROP, LOGICAL, SUPER_RESERVE, GUIDE, RESTORE_*) | z9x `:15-21`, play6 `:22-36`; no hb lines |
| `installer/release.conf` | DEVICE of a download (none = Z9X) | `installer/release.conf:10-14` |
| `devices/goat_elegant/image/sign.conf` | what sign_device.py signs (base image, pinned APKs, host paths, stage dir) | HB only, `:7-21` |
| `tools/play6/forks.tsv`, `devices/goat_elegant/forks.tsv` | deliberate copies of another device's files | Play 6 (58 lines), HB (305 lines) |
| `devices/goat_elegant/image/base.sha256`, `tools/play6/baseline/`, Z9X base check in `tools/lumen_v1.sh` | base pin | each its own way |
| prop snippets | `tools/ota/image/ota_props.txt` + `overlay/v1/*_prop_v1.txt` (Z9X), `overlay/play6/product_prop.txt` (Play 6), `devices/goat_elegant/image/hb_prop.txt` + `hb_lumen_prop.txt` (HB) | device id prop: `tools/ota/image/ota_props.txt:12` `ro.z9x.device=z9x`, `overlay/play6/product_prop.txt:4` `ro.lumen.device=play6`, `devices/goat_elegant/image/hb_prop.txt:5` `ro.lumen.device=goat_elegant` |

The installer profile repeats registry facts (name, model, board, fastboot product, firmware). `devices.py
check-installer` keeps them in agreement (`tools/devices/devices.py:288-318`). Its header says the Z9X lines repeat
constants the Z9X code path still hardcodes (`installer/lib/devices.conf:13-14`).

## 3. The three image pipelines

### 3.1 Z9X: `tools/lumen_v1.sh` (monolith, gate input)

- Stages prep (laptop) -> sign (Mac) -> image (laptop), plus `remote` that runs the whole chain from the Mac
  (`tools/lumen_v1.sh:9-26`).
- Base: a prebuilt tar `system_tv_v4.tar` patched by member (`tools/lumen_v1.sh:3-4`, `:1119-1131`), not a
  mounted image.
- Inputs: `overlay/v1` (37 entries), `overlay/apps_v1` pinned APKs, `tools/ota/image`, `overlay/v65/z9x_setup.rc`
  (`tools/lumen_v1.sh:210-211`, `:317`, `:1649-1669`).
- Device facts inline: prop checks `ro.z9x.device=z9x`, `ro.z9x.version`, AB partitions etc.
  (`tools/lumen_v1.sh:679-704`), the debloat list, the 64-bit overlay names (`:338-368`).
- Signing: `tools/sign/apex_sign.py` + `tools/sign/sign_tar.py` with `~/.lumen-keys` release keys (header
  `:13-18`, `stage_sign` at `:1470`).
- Build host from `~/.config/lumen/builder.env` (`tools/lumen_v1.sh:280-288`).
- Legacy predecessors still in the tree: `tools/z9x_v6.sh` .. `tools/z9x_v65.sh`, `tools/assemble_tv.sh`,
  `tools/compose_tv.py`, `overlay/v6*`.

### 3.2 Play 6: `tools/play6/build_test.sh` -> `tools/play6/compose.sh`

- `build_test.sh` (335 lines) runs on the Mac: gate G, sync S, publish audit P; `build_play6.sh` builds the
  Play6* apps with the Play 6 key; `sign_play6.py pinned` re-signs the Z9X pins; base export and APEX re-sign;
  `check_signers.py`; stages `~/play6-work/in` on the laptop; runs compose in the lineage-builder container
  (`tools/play6/build_test.sh:31-55`, `:64`).
- Stage list is a hardcoded bash array of 38 paths (`tools/play6/build_test.sh:251-290`), plus `--ota`/`--diag`
  extras (`:291-293`). It mixes `overlay/v1` (shared Z9X files), `overlay/play6` and `build/play6`.
- `compose.sh` (562 lines) mounts the 32-bit GSI base and writes an ext4 image with the base build's
  `build_image` (`tools/play6/compose.sh:1-50`).
- Release: `tools/play6/release_image.sh` edits build.prop lines inside a tested image with debugfs
  (`tools/play6/release_image.sh:1-20`).

### 3.3 Z9X HB: `tools/image/compose.sh` -> `devices/goat_elegant/image/compose_hb.sh`

- `tools/image/compose.sh` (80 lines, generic): validates the registry, refuses `generic=no`, checks `fs`, scps
  exactly the `stage` files flat into `~/<remote_dir>/in`, copies `stage_dir` after a key-file scan, runs the
  composer once per variant with `LUMEN_IMAGE_DIR`, `BUILD_ID`, `SOURCE_DATE_EPOCH`
  (`tools/image/compose.sh:28-80`).
- `compose_hb.sh` (548 lines) is a fork of `tools/play6/compose.sh` (row in `devices/goat_elegant/forks.tsv`):
  same Play 6 LineageOS base image from `~/lumen-builder-backup/play6-out`
  (`devices/goat_elegant/image/compose_hb.sh:66-69`), output erofs with `mkfs.erofs` (`:520`).
- It re-lists its inputs that image.conf already lists: base files (`compose_hb.sh:82-85`), Lumen files
  (`:88-93`), and the signed APK names (`:100-101`), which repeat `apps=` of device.conf
  (`devices/goat_elegant/device.conf:32`) and `pinned=` of sign.conf (`devices/goat_elegant/image/sign.conf:12`).
- `run_on_builder.sh` is now a wrapper to compose.sh (`devices/goat_elegant/image/run_on_builder.sh:9`).

### 3.4 What the three composers do that is the same

All three: start from a LineageOS 21 TV base, debloat a fixed list, add Lumen pinned apps (Home, Setup, TvInput,
LineagePlatformOverlay), add `overlay/v1` privapp / default-permission / sysconfig XMLs, the boot animation, remote
key layouts and idc, `z9x_nui.rc/.sh`, write prop snippets, run `check_privapp.py`, and pack the fs. Each does it
in its own code (bash in three files, three different base forms: tar, mounted ext4 tree, mounted image ->
erofs). There is no shared "add Lumen common layer" step.

## 4. Signing and keys

| | Z9X | Play 6 | Z9X HB |
|---|---|---|---|
| private keys | `~/.lumen-keys` | `~/.lumen-keys/play6` | `~/.lumen-keys/goat_elegant` |
| public certs in tree | `tools/sign/release_certs/` | `tools/play6/certs/` | `devices/goat_elegant/certs/` |
| installer OTA cert | `installer/certs/ota.x509.pem` | `installer/certs/play6/` | `installer/certs/hb/` |
| key generator | `tools/sign/gen_keys.sh` | `tools/play6/gen_play6_keys.sh` (297 lines) | `tools/devices/gen_device_keys.sh` (generic) |
| app build | Z9X gate pins in `overlay/apps_v1` | `tools/play6/build_play6.sh` | `tools/devices/build_apps.sh` (generic, reads `apps=`) |
| image signing | `tools/sign/apex_sign.py`, `sign_tar.py` | `tools/play6/sign_play6.py` (1142 lines) | `tools/devices/sign_device.py` (856 lines, imports sign_play6 read only) |
| signer check | `tools/sign/check_image.py` | `tools/play6/check_signers.py` | `tools/devices/check_device_signers.py` |

Facts:
- `sign_device.py` imports `sign_play6` helpers read only (`tools/devices/sign_device.py:2-6`, `:62-68`) and is
  listed as a fork of it (`devices/goat_elegant/forks.tsv`). The generic signer depends on a Play 6 module.
- `check_device_signers.foreign_sets` walks the registry for other devices' certs
  (`tools/devices/check_device_signers.py:63-93`), so it scales to new devices. But it reads the Play 6 certs from a
  fixed path `PLAY6_CERTS` (`:71`) and the Z9X ones through `check_signers` (`:67-70`).
- `build_apps.sh` builds its "other devices" list from fixed folders: `apps/sdk`, `tools/sign/testcerts`,
  `tools/play6/certs`, `tools/sign/release_certs` (`tools/devices/build_apps.sh:42-43`). It does not look in
  `devices/*/certs`, so a fourth device's apps would not be checked against the HB certificate here (the image
  check in `check_device_signers.py` would still catch it).
- The pinned app list exists twice: `sign_play6.py:134` `PINNED` and `devices/goat_elegant/image/sign.conf:12`.

## 5. Checks per device

| check | generic entry | per-device backends |
|---|---|---|
| registry | `devices.py validate / check-installer / check-wire` | WIRE table hardcodes per OTA engine which files hold the device id (`tools/devices/devices.py:94-99`) |
| forks | `tools/devices/check_forks.py --device` | Play 6 delegates to its own `tools/play6/check_forks.py` via `OWN_CHECK` (`tools/devices/check_forks.py:33`) |
| HAL callers | `tools/devices/check_hal.py --device` | z9x-callers -> `apps/check_hal_callers.py`, play6-hidl -> `tools/play6/check_play6_hal.py` (1071 lines), aidl-table -> `tools/devices/check_aidl_hal.py` (`tools/devices/check_hal.py:5-12`) |
| Z9X unchanged | `tools/devices/prove_unchanged.sh` | Z9X gate + Play 6 compose inputs + installer goldens (`tools/devices/prove_unchanged.sh:38-79`) |
| OTA entry | `tools/devices/ota.py` | defaults to horse_popular, stops for the others (`tools/devices/ota.py:8`, `:30`) |

## 6. Installer

- `installer/lumen-install.sh` (2875 lines) knows two projectors by name. Option and release.conf parsing accept
  only `z9x|play6` (`installer/lumen-install.sh:1515-1516`, `:2813`).
- Dispatch is two hardcoded blocks: `if [ "$PROJECTOR" = play6 ]` with 10 `play6_*` commands, else the Z9X
  `cmd_*` set (`installer/lumen-install.sh:2848-2875`). There are 56 `p6_`/`play6_` functions and 113 lines that
  name `play6`.
- `status` and `reset` name the state folders by device (`installer/lumen-install.sh:2826-2840`); `other_hint`
  knows only play6 (`:1501-1505`).
- The data-driven part is `load_profile` from `lib/devices.conf` with a strict key list `P_KEYS`
  (`installer/lumen-install.sh:1446`, `:1481-1500`). The Z9X does not read it at all.
- The generic `FLOW=ab-fastbootd` flow for the HB is designed (`devices/DESIGN.md:235-322`) but not implemented:
  "Step 7: installer FLOW and the hb profile: NOT done this round" (`devices/IMPLEMENTATION_LOG.md:108`). The
  same logic lives twice, in `.sh` and `.ps1`.

## 7. Releases and OTA

| | Z9X | Play 6 | Z9X HB |
|---|---|---|---|
| release image | `tools/lumen_v1.sh VARIANT=public` | `tools/play6/release_image.sh` on a tested tN image | none (test images only) |
| OTA | `tools/ota/make_ota.py`, `make_manifest.py`, `sign_payload.py` (update_engine) | `tools/play6/ota/*` (recovery-block, postponed) | none |
| asset gate | `tools/ota/check_release_assets.py` (erofs, Z9X props `ro.z9x.gms=0` etc.) | same gate is Z9X shaped (`tools/ota/check_release_assets.py:1-25`) | n/a |
| checklist | `docs/release.md` (Z9X only, sections 0-4) | memory notes / docs/play6 | n/a |

The OTA device ids are literals in code: `tools/ota/make_ota.py` `pre-device=z9x`, `tools/ota/make_manifest.py`
`"device": "z9x"`, `tools/play6/ota/make_manifest_play6.py`, `apps/Play6Updater/.../Ota.java`; `check-wire` only
pins them to the registry (`tools/devices/devices.py:92-99`, `devices/DESIGN.md:324-338`).

## 8. Overlay folders

- `overlay/v1`: Z9X image inputs and a gate input, but also read by the Play 6 stage
  (`tools/play6/build_test.sh:263-283`) and the HB stage (`devices/goat_elegant/image.conf:9`). It is the de facto
  shared Lumen layer, named and gated as Z9X.
- `overlay/play6`: Play 6 only (12 entries). HB-only files live in `devices/goat_elegant/image/` instead, so
  per-device overlays sit in two kinds of places.
- `overlay/debug`: shared logger and escape (`z9x_earlylog.*`, `lumen_escape.*`), staged by both 32-bit devices.
- `overlay/apps_v1`: the pinned Z9X APKs that the other two re-sign.
- Old: `overlay/v6 v61 .. v65 v64-fix apps_v64 apps_v65` (only `overlay/v65/z9x_setup.rc` is still read,
  `tools/lumen_v1.sh:317`).

## 9. What is duplicated per device

1. The composer: three (`tools/lumen_v1.sh`, `tools/play6/compose.sh`, `compose_hb.sh`); compose_hb.sh is a fork
   of the Play 6 one, both 550+ lines with the same steps (debloat, APEX, privapp, props, fs).
2. The input list: the Play 6 bash array (`build_test.sh:251-290`); the HB `stage=` line in image.conf and again
   in compose_hb.sh (`:82-101`).
3. Signing stage: `sign_play6.py` vs `sign_device.py` (forked); key generator twice; signer check twice.
4. The pinned app list: `sign_play6.py:134` and `sign.conf:12`.
5. The projector app: `apps/Play6Projector` (26 fork rows) and `apps/HbProjector` (291 fork rows from
   `apps/Z9xProjector`), ported by hand when the Z9X origin changes.
6. The installer flows: one per device by name, twice (sh and ps1).
7. Device facts: registry vs installer profile vs prop snippets vs inline constants in lumen_v1.sh.
8. The base: the Play 6 and the HB share one base image but pin it separately (`base.sha256`,
   `tools/play6/baseline`).
9. Release scripts: Z9X inside lumen_v1.sh, Play 6 its own `release_image.sh`.

## 10. Adding a projector today, step by step

What a fourth device (codename `X`) takes with the current tools, following the HB path (the only generic one):

1. Dump stock props (ro.product.device, modelname, hardware, platform, vndk, firmware, fastboot product).
2. `devices/X/device.conf` with all 26 required keys. If its install flow, OTA engine, ABI or HAL check is new,
   edit the enums in `tools/devices/devices.py:73-81` and add code for it.
3. `tools/devices/gen_device_keys.sh X` on the Mac (owner): writes `~/.lumen-keys/X`, copies certs to
   `devices/X/certs/` and `installer/certs/<installer_id>/`.
4. HAL: if AIDL, write `research/aidl_codes.tsv`, `banned.tsv`, `allowed.tsv` for it; if a new transport, a new
   checker plus a branch in `tools/devices/check_hal.py`.
5. Projector app: copy `apps/Z9xProjector` (or `apps/HbProjector`) to `apps/<Prefix>Projector`, rename, adapt the
   HAL layer, and add every copied file as a row of `devices/X/forks.tsv` (the HB has 291 such rows). Also a
   framework overlay app per device (`Play6FrameworkOverlay`, `HbFrameworkOverlay`). List them in `apps=`.
6. `devices/X/image/`: copy `compose_hb.sh` and adapt (base path, fs, props, rc files, hardcoded input lists),
   write `X_prop.txt`, `X_lumen_prop.txt`, compat rc, audio files, `sign.conf`, `base.sha256`, `verify_tree.py`.
7. `devices/X/image.conf` with `generic=yes`, the host, and the full `stage=` list (repeated by hand inside the
   composer).
8. `sign_device.py X all` (base-import, pinned, APEX repack/sign on the host, base, stage).
9. `bash tools/devices/build_apps.sh X`, then `bash tools/image/compose.sh X tN`.
10. Installer: not possible without code today. `lumen-install.sh` accepts only `z9x|play6`
    (`installer/lumen-install.sh:1515`); the ab-fastbootd flow of DESIGN section 5 must be built first, in sh and
    ps1, plus the profile lines in `installer/lib/devices.conf`.
11. OTA and releases: none for a new device (`tools/devices/ota.py` stops; `check_release_assets.py` and
    `docs/release.md` are Z9X shaped).
12. Publishing: add rules to `docs/repo/publish_audit.py` (the HB has 4: `:169-178`, `:210`), otherwise every new
    file is REVIEW.
13. Prove the Z9X unchanged: `sh tools/devices/prove_unchanged.sh` (about 5 min).

Steps 5 and 6 are the large manual parts (a 550-line composer fork and a few hundred forked app files); step 10 is
blocked on code.

## 11. Open points seen while mapping (facts, not proposals)

- `devices/DESIGN.md:94,107` says goat_elegant `fastboot_product=goat_elegant`; registry and RUNBOOK say `mt5873`
  (`devices/goat_elegant/device.conf:23`, `devices/goat_elegant/install/RUNBOOK.md:348`). The design doc is stale.
- `build_apps.sh` foreign-cert list omits `devices/*/certs` (`tools/devices/build_apps.sh:42-43`).
- `compose_hb.sh` says the image is "not proven byte for byte yet" reproducible
  (`devices/goat_elegant/image/compose_hb.sh:40-41`).
- The generic layer (`tools/devices/sign_device.py`) imports a device-specific module (`tools/play6/sign_play6.py`).
- `overlay/v1` is both "the Z9X" (gate input) and the shared Lumen layer for the other two.
