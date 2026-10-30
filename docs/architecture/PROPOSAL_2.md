# Proposal 2: capability-driven projector platform (2026-10-30)

Angle: one projector app built per device from a shared core plus a DeviceProfile (capabilities, HAL transport,
code tables, zoom model and other stock behaviours) plus thin adapters; one image compose driven by
`device.conf` and `image.conf`. This is a proposal, not a decision. It is built on the three read-only maps in
this folder (`MAP_apps.md`, `MAP_image.md`, `MAP_checks.md`); numbers and `file:line` facts come from them.
Paths are relative to `gsi/`. Nothing in the tree was changed to write this.

Devices: horse_popular = Z9X (64-bit, HIDL gmpf, released 1.0.x), dragon_healthy = Play 6 (32-bit, HIDL),
goat_elegant = Z9X HB (32-bit, stable AIDL gmpf).

## 1. The idea in five lines

1. There is one projector app. Each device gets its own APK, assembled at build time from layers: core, the
   optional modules the device has, and a small device layer. No file is copied into another app on disk.
2. Everything that differs between projectors and can be said as data is said in one place per device:
   `devices/<codename>/profile.conf` (plus the HAL code tables that already exist under `research/`). A generator
   turns it into one Java class of `static final` constants, so unsupported features are removed by the compiler
   and cost nothing on weak hardware.
3. Everything that differs and must be code (HAL transport, typed HAL clients, truly device-only screens) lives in
   the device layer behind the class names and signatures the core already uses (the HB pattern that lets 273 HB
   files stay identical today, `MAP_apps.md` section 4).
4. The image is built by one composer that runs the same steps for every device (base, debloat, Lumen common layer,
   device layer, props, privapp check, pack), reading every device fact from the registry.
5. Z9X stays provably unchanged at every step: each step either does not touch Z9X inputs at all, or is proven by
   the existing pin rebuild (16 of 16 APKs byte-identical). Only one step changes Z9X bytes on purpose, and it is
   optional and needs the owner's OK (section 5).

## 2. Module diagram

```
                         devices/<codename>/          (data only, one folder per projector)
                         +-- device.conf              identity, ABI, HAL, slots, keys, OTA, installer   (exists)
                         +-- image.conf               image recipe: base, layers, fs, variants          (exists)
                         +-- profile.conf             NEW: capabilities, behaviours, zoom model, modules
                         +-- research/*.tsv           HAL code tables: codes, allowed, banned           (exists for HB)
                         +-- image/                   device image layer: props, rc, audio, base pin
                                    |
                                    | read by
        +---------------------------+-----------------------------+
        |                                                         |
  APP SIDE                                                  IMAGE SIDE
  tools/devices/assemble_app.py                             tools/image/compose.sh + tools/image/lib/*.sh
        |                                                         |
        v                                                         v
  build/assembled/<codename>/Projector/                     one composer, same steps for every device:
   = layer 1  apps/core/projector/        (core)              1 base      (tar or GSI image, from image.conf)
   + layer 2  apps/core/modules/<m>/      (only modules       2 debloat   (shared list + device extra list)
              named in profile.conf)                          3 common    (overlay/v1 as the Lumen layer)
   + layer 3  apps/<Prefix>Projector/     (device layer:      4 device    (devices/<c>/image/)
              hal transport + typed       adapters, real      5 apps      (signed with the device key)
              clients, overrides listed   forks, manifest,    6 props     (generated from device.conf)
              in overrides.tsv)           strings)            7 checks    (privapp, signers, HAL dex scan)
   + generated DeviceProfile.java   (from profile.conf)       8 pack      (ext4 or erofs from device.conf)
        |
        v
  apps/build_apk.sh  (unchanged, gets one ready APP_DIR)
```

Inside the app, the dependency direction is one way:

```
  screens and panels (core, modules)
        |  call only
        v
  DeviceProfile (generated constants)  +  facades with fixed names:
        Hal, HalController, GmpfClient, Gmpf2Client, KstPoint, FitDevices, ZoomModel, KeystoneModel
        |  implemented by
        v
  device layer: transport (HidlCaller | AidlCaller | next one) + typed clients for that device's code table
```

Rules of the shape:

- The core never names a device. No `isPlay6()`, no `Hb` prefix, no `Z9x` check inside core code. It asks
  `DeviceProfile.X` or calls a facade.
- A device layer never edits a core file. It either adds a file at a path the core does not have, or overrides a
  core path, and every override is a row in `apps/<Prefix>Projector/overrides.tsv` with the core file's sha256 and a
  reason. An override without a row is a build error (this closes the silent `fit/FitDevices.java` hole,
  `MAP_apps.md` section 3.2).
- Selection is at build time, not runtime: no reflection, no service lookup by device name, no extra classes for
  features a device lacks. This matches the owner rule "fast on weak hardware".

## 3. What goes where

### 3.1 Core: `apps/core/projector/`

Everything that is byte-identical on two or more devices today, which is most of the app:

- the 230 files of `apps/common/projector/FILES` (home, mem, recents, remote, ui, voice, parts of power, source,
  panel, sys, their strings and drawables);
- the 273 files the HB keeps as `same:` copies of Z9X (keystone, focus, AK, picture, sound, dream, standby, CEC,
  game, their strings and tests);
- the shared HIDL transport `hal/HidlCaller.java` moves to a transport folder that both HIDL devices take
  (`apps/core/transport/hidl/`), not to the core proper, because the HB must not carry it.

Later, the 7 small HB forks (30 diff lines or fewer: FitDevices, TimerLamp, EyeGuard, SoundEq, DreamLamp, SpdifOff,
SetupBridgeProvider) collapse back into core with a profile question each (section 3.3). Example from today's
tree: the HB `power/TimerLamp.java` differs only in "do not fade the lamp when dynamic black is on". In the core
that becomes `if (DeviceProfile.LAMP_FADE_SKIPS_DYNAMIC_BLACK) { ... }`.

### 3.2 Optional modules: `apps/core/modules/<name>/`

A module is a folder with the same layout as an app (`src`, `res`, `test`, a manifest fragment). It is included
only when `profile.conf` names it, so a device without the hardware gets no classes, no strings, no rows.
Candidates from today's code:

| Module | Today | Devices that would include it |
|---|---|---|
| `fit` (fit to my screen) | Z9X and HB, adapter already exists (`FitDevices`) | Z9X, HB |
| `battery` | Play 6 only (`battery/*`) | Play 6 |
| `speaker` (speaker mode) | Play 6 only | Play 6 |
| `lens`, `ambient` | Play 6 only | Play 6 |
| `curtain` (curtain align) | HB only (`CurtainAlignOverlay`) | HB, later others |
| `report` | Z9X only | Z9X |
| `optical_zoom`, `zoom_shift` | Z9X/HB `OpticalZoomPanel`, Play 6 `Play6ZoomShiftPanel` | by zoom model |

A device-only feature starts life in the device layer. When a second projector needs it, it moves into a module.
That move is the only time code changes owner.

### 3.3 DeviceProfile: `devices/<codename>/profile.conf`

Data file, same format rules as `device.conf` (key=value, unknown keys an error, parsed by
`tools/devices/devices.py`, never sourced). Generated into `DeviceProfile.java` by
`tools/devices/gen_profile.py` with sorted output, so the same data always gives the same bytes.

| Group | Keys (examples) | Replaces today |
|---|---|---|
| identity | `codename`, `model`, `stock_feature_xml` path | `Device.isPlay6()`, `HbFeatures.FEATURE_XML` |
| modules | `modules=fit curtain` | per-device folders with device-only code |
| capabilities | one line per feature: `cap.THREE_D=off`, `cap.REALTIME_KEYSTONE=on`, `cap.DOLBY_VISION=pending:<check>`, optional stock key `cap.THREE_D.stock=support_3D:0` | the 30-row `HbFeatures` table, `Z9xFitDevice.SUPPORTED` style constants |
| zoom model | `zoom=optical-motor`, `zoom.min`, `zoom.max`, `zoom.step` or `zoom=digital-shift` or `zoom=none` | forked `OpticalZoomPanel`, `Play6ZoomShiftPanel` |
| keystone | `keystone.points=4|8`, `keystone.reset=codes:38,84,85`, `keystone.realtime=on|off` | forked `ManualKeystonePanel`, `KstPoint` |
| behaviours | `lamp.fade_skips_dynamic_black=yes`, `eyeguard.gear_key=yes`, `wake.ok_focus_hold=yes`, `spdif.off_mode=...` | the small HB forks |
| HAL | `hal.transport=hidl|aidl`, `hal.codes=research/...tsv` (pointer to the tables that `device.conf` already names) | nothing new; one source for both the client and the checker |
| strings | `strings=hb` (device string set name) | hand-written `strings_hb*.xml` lists |

Runtime refinement stays as the HB does it now: a `pending` or `on` capability can still be turned off at start
when the stock `feature.xml` says off ("a stock off wins", `MAP_apps.md` section 5). An `off` capability is a
compile-time `false`, so its code is gone from the APK.

Pending capabilities keep the owner rule "never show a disabled row": `pending` builds no row until its named
check passes and the line changes to `on`.

### 3.4 Device layer: `apps/<Prefix>Projector/`

The existing app folders stay, with the same names (the gate, the publish audit and `apps=` already know them), but
shrink to:

| Kept in the device layer | HB today | Play 6 today |
|---|---|---|
| transport | `hal/AidlCaller.java` (715 lines) | takes `apps/core/transport/hidl/` |
| typed clients with core names and signatures | `hal/GmpfClient.java`, `hal/Gmpf2Client.java` (already shaped this way) | `Play6Gmpf`, `Play6Gmpf2` renamed or wrapped as `GmpfClient`, `Gmpf2Client` (stage 3) |
| facade owners | `HalController` fork (564 diff lines) until it splits into core + profile | `Play6App`, `Play6PanelController` until split |
| real forks that are not yet a profile question | listed in `overrides.tsv` | listed in `overrides.tsv` |
| manifest | a fragment merged over the core manifest | same |
| device-only strings | `strings_hb*.xml` | `strings_p6.xml` |
| device-only code not yet a module | `device/DeviceStateProvider`, `HbPanelRequestReceiver` | `BootHandshake` |

Expected size after migration: HB about 25 to 40 files (from 593), Play 6 about 60 to 90 (from 330, it has more
genuinely different code), Z9X about 10 to 20 (its transport clients, `report`, manifest).

### 3.5 Strings and tests

- One generator, `apps/i18n/gen.py`, with one target per layer (core, each module, each device layer). The Play 6
  generator `tools/play6/gen_play6_i18n.py` and the hand-written HB files become targets. The generator checks that
  every string exists in all 17 locales, which would have caught the 6 missing locales of `strings_hb_fit.xml`.
- Tests live next to the code they test (core, module or device layer). One runner,
  `tools/devices/test_app.sh <codename>`, compiles the tests against that device's assembled tree, so core tests
  run once per device with that device's profile. No more byte-identical test copies in the HB; Play 6 gets the core
  tests for free. A `ProfileTest` per device checks that every `cap.*` key is known and every module named exists.

### 3.6 Image side

- `tools/image/compose.sh` (already generic, 80 lines) stays the entry. The steps that the three composers repeat
  (`MAP_image.md` section 3.4) move into small shared step scripts under `tools/image/lib/`: `base_tar.sh`,
  `base_gsi.sh`, `debloat.sh`, `lumen_layer.sh`, `device_layer.sh`, `props.sh`, `privapp_check.sh`, `pack_ext4.sh`,
  `pack_erofs.sh`.
- `image.conf` becomes the full recipe: which base step, which debloat extra list, which pack step, the stage list
  once (today the HB repeats it inside `compose_hb.sh:82-101`). The pinned app list comes from `device.conf apps=`
  only (today also in `sign_play6.py:134` and `sign.conf:12`).
- Props are generated from `device.conf` (`ro.lumen.device`, model, version), so the device id prop has one source.
- Signing: one signer `tools/devices/sign_device.py` that no longer imports `tools/play6/sign_play6.py`; the shared
  helpers move to `tools/devices/signlib.py`. `sign_play6.py` keeps working as a thin wrapper until the Play 6
  switches.
- `overlay/v1` stays where it is (it is a Z9X gate input and moving it would change nothing but cost a re-record).
  It is declared in every `image.conf` as the Lumen common layer, which is what it already is in practice.
- Installer: the ab-fastbootd flow already designed in `devices/DESIGN.md` section 5 is built once, driven by
  `installer/lib/devices.conf` profile lines. New projectors with A/B slots then need data, not code.

## 4. How a new projector is added (after migration)

Example: a fifth projector, codename `X`, with a gmpf HAL over one of the two known transports.

| # | Step | Who | Time (estimate) |
|---|---|---|---|
| 1 | `tools/devices/new_device.sh X` scaffolds `devices/X/` (device.conf, image.conf, profile.conf with every capability `pending`), `apps/<Prefix>Projector/` (manifest fragment, empty overrides.tsv) and publish rules (private by default) | me | 15 min |
| 2 | Stock survey over adb: props, `feature.xml`, settings tree, HAL service list, partitions, fastboot product | me | 0.5 day |
| 3 | Fill device.conf and image.conf; `devices.py validate` green | me | 1 hour |
| 4 | Keys: `tools/devices/gen_device_keys.sh X` | owner, on the Mac | 15 min |
| 5 | HAL: code table TSVs from the stock services; typed clients reuse the transport of the same kind; `check_hal.py --device X` green | me | 1 to 2 days (same transport); 3 to 5 days if the transport is new |
| 6 | Profile: set capabilities from the survey (`on`/`off`, keep unproven ones `pending`), zoom model, keystone, behaviours, modules | me | 2 to 4 hours |
| 7 | `bash tools/devices/check.sh --device X` (assemble, build, tests, HAL dex scan, signers, publish) green | me | 1 hour |
| 8 | `bash tools/image/compose.sh X t1`: no composer to fork, only `devices/X/image/` props and rc files | me | 2 to 4 hours |
| 9 | Install path: data lines in `installer/lib/devices.conf` if the flow is ab-fastbootd or a known one | me | 1 hour (a new flow: 2 to 4 days) |
| 10 | First boot, then the stock 1:1 loop: every `pending` capability tested against stock over adb, flipped to `on` one line at a time | me with the device | 2 to 5 days |
| 11 | `prove_unchanged.sh` per device for all existing projectors | script | about 5 min |

Total: about 3 to 5 working days to a booting, testable image when the HAL transport is known, then the parity loop.
For comparison, today steps 5 and 6 of `MAP_image.md` section 10 mean copying a 550-line composer and a few hundred
app files, and the HB route left 295 fork rows to maintain afterwards. The existing projectors are touched by none
of the steps above.

## 5. Keeping the Z9X provably unchanged

### 5.1 What stays as it is

- The owner rule and its two proofs stay: `tools/play6/z9x_gate.sh` (16 pinned APKs rebuild byte for byte, image
  inputs unchanged) and `tools/devices/prove_unchanged.sh`.
- `tools/lumen_v1.sh` stays the Z9X image builder until the very last optional stage. The generic composer may
  build a Z9X image only as a shadow for comparison; it does not replace the release path without the owner's OK.
- `apps/build_apk.sh` is not edited. Assembly happens before it, into a ready folder, so the builder that made the
  released pins is the builder that makes them after the move.

### 5.2 Why the move can be proven byte for byte

The APK depends on the file contents and their relative paths, not on which folder they were read from. If the
assembled Z9X tree (core + Z9X layer) has exactly the files `apps/Z9xProjector` has today, at the same relative
paths, then `build_apk.sh` produces the same APK. The gate already rebuilds every pin when shared code changes
(`z9x_gate.sh:101-102`) and is green only if all 16 are identical. So moving files out of `apps/Z9xProjector` into
`apps/core/projector` is proven by the gate itself. Before the move, a dry run (`assemble_app.py horse_popular
--compare apps/Z9xProjector`) proves the assembled tree equals today's tree file by file.

Two things to check in that dry run, because they could break identity: file order given to `javac` and `aapt2`
(`apps/build_apk.sh:198` passes the Java list straight from `find` with no sort, so order depends on the file
system; the assembler must write files so that `find` gives the same order, which the dry run proves; native libs
are already sorted at `:229`),
and build paths embedded in output (none expected, to verify on the first pin).

### 5.3 What the owner must accept

| Step | Changes Z9X APK bytes | Changes Z9X gate inputs | Owner action |
|---|---|---|---|
| Stages 1 to 3 (other devices layer on top of Z9X files read in place) | no | no | none |
| Stage 4 (physical move into `apps/core`) | no, proven 16 of 16 | yes: the gate input list gains `apps/core`, the assembler becomes an input | one OK for the gate script edit and one re-record |
| Stage 5 (seam release: DeviceProfile inside the core, small forks removed) | yes, on purpose | yes | accept a Z9X code change shipped as a normal Z9X release (for example 1.1.0) with the proof in section 5.4; or decline it and keep the small forks |
| Stage 7 (Z9X image through the generic composer) | no, if proven | yes | optional; only after a shadow build gives the same file listing and hashes as `lumen_v1.sh` |

So the honest position is: everything up to the physical move costs the Z9X nothing and is proven by the existing
gate. The capability seams inside the core (profile questions instead of whole-file forks) cannot be added without
changing Z9X bytes, because a new class or a new `if` in a Z9X file is a new APK. That step is optional. Without it
the platform still works, with same-name adapters and a few small forks per device listed in `overrides.tsv`.

### 5.4 Proof for the seam release, if the owner takes it

- A dex-level diff report (classes and methods changed) limited to the classes the seam release names; any other
  difference is red.
- Because `off` capabilities are compile-time `false` and Z9X profile values equal today's hardcoded behaviour, the
  Z9X branch of each seam is the old code. The diff report shows this per seam.
- All host tests green against the Z9X assembly.
- Z9X device check with the usual adb loop (picture, keystone, focus, zoom, sound, standby, CEC, voice), then a
  normal OTA release. After it, new pins are recorded once and the gate goes back to proving byte identity.

## 6. How the checks change

Based on `MAP_checks.md` section 7, adapted to this layout.

| Today | After |
|---|---|
| Z9X gate freezes folders, including the code that should be shared | gate input list adds `apps/core` and the assembler; core changes trigger "rebuild every pin", green when all pins are identical (the real rule). No logic change |
| Two copy systems (`sync.sh` FILES, HB `same:` rows) with two refresh commands | gone: there are no copies. `sync.sh projector` and `refresh_same.sh` are retired; `sync.sh` keeps only `tokens`, `geoip`, `migrate`, `keys` until those move too |
| Two fork checkers, origin sha256 change is red and blocks Play 6 builds | one `overrides.tsv` check inside `tools/devices/check_forks.py`: unlisted override is an error always; a changed core file under an override is a warning in dev builds (lists what to review) and red in release builds |
| Play 6 "every Z9X resource placed" rule | not needed: every core resource is core |
| Three HAL checkers in three styles, Z9X banned list inside a gate input | one table-driven checker on the model of `check_aidl_hal.py`, device tables under `devices/<c>/research/`; the HIDL checkers stay authoritative until the generic one gives the same verdicts on today's tree; the Z9X banned list moves to a data file outside gate inputs (one re-record) |
| Counts compared as text ("16 of 16", "151 methods", "141 calls") | hashes and exit codes; expected counts read from the pin list and the TSV tables |
| Scope by name prefix (gate glob, publish audit, foreign certificate list) | scope from the registry: `app_prefix`, a new `public=yes|no` key, `devices/*/certs` |
| No aggregate; HB checked by nothing together | `tools/devices/check.sh [--device C | --all] [--fast | --release]`: registry, assemble, build, tests, overrides, HAL, signers, device proof, publish audit once |
| `prove_unchanged.sh` covers Z9X and Play 6 only, manual partial refresh | loops over `devices.py list`; each device declares its proof (Z9X gate; APK repeatability; compose inputs); `record_mac.sh --refresh` writes the whole baseline in one step after an owner OK |

New checks this layout needs:

- `assemble_app.py --check`: every override listed, no core path shadowed silently, modules in the profile exist,
  assembled tree deterministic (assemble twice, compare hashes).
- `gen_profile.py --check`: every `cap.*` key known to the core, every `pending` names its check.
- Locale completeness across all layers.

## 7. Staged migration plan

Each stage ends with a gate. A stage is not started until the previous gate is green. Z9X columns say what the
stage costs the Z9X.

### Stage 0: make the checks honest (no app change)

- Fix the stale test (`check_aidl_hal_test.sh` reads the count from `allowed.tsv`), add the missing
  `fit/FitDevices.java` row, add `devices/*/certs` to `build_apps.sh:42-43`, add the HB to `prove_unchanged.sh`,
  bring the current red rows to green (Play 6 7 rows and `strings_fit.xml`, HB 4 rows), add `check.sh` as a thin
  wrapper over the existing checks.
- Z9X: none (all files touched are outside gate inputs; `build_apps.sh` and `tools/devices/` are not inputs).
- Gate: `check.sh --all` green; `prove_unchanged.sh` green for all three; gate log GREEN, 0 changed.

### Stage 1: the assembler, proven on the HB (no copies removed yet)

- Write `tools/devices/assemble_app.py`: layers by path, `overrides.tsv`, deterministic order, `--compare`.
- Assemble the HB from layer 1 = `apps/Z9xProjector` read in place (with an exclude list for Z9X-only files such as
  `report/`) and layer 2 = the HB's real forks and own files. Compare with today's `apps/HbProjector` tree.
- Z9X: none (the Z9X folder is only read).
- Gate: assembled HB tree equals today's HB tree file by file; HB APK `classes.dex` and `resources.arsc` identical
  to the APK built from today's folder with the same key; `check.sh --device goat_elegant` green.

### Stage 2: remove the HB copies

- Delete the 273 `same:` copies and their rows; the HB layer keeps only real forks and HB-only files; `overrides.tsv`
  replaces the fork rows; `refresh_same.sh` retired.
- Z9X: none.
- Gate: HB APK content identical to stage 1; HB host tests green from the shared test location; one HB device smoke
  test (boot, quick panel, keystone, zoom, standby).

### Stage 3: Play 6 on the same model

- Play 6 typed clients adopt the core names and signatures (`GmpfClient`, `Gmpf2Client`, `KstPoint`), so its callers
  can be core files; the 230 synced files are read from the base layer instead of copied; Play 6-only code stays in
  its layer; `tools/play6/check_forks.py` rules become rows or are dropped.
- Z9X: none.
- Gate: `check_play6_hal.py` green on the assembled tree; Play 6 APK built twice gives the same hashes; Play 6
  device test list (battery, speaker mode, lens, ambient, keystone, zoom/shift, rotate, standby, power key) passes.
  The Play 6 APK changes on purpose here, so the proof is the device test, not byte identity.

### Stage 4: physical core

- Move shared files from `apps/Z9xProjector` and `apps/common/projector` into `apps/core/projector` and
  `apps/core/transport/hidl/`; Z9X becomes a layer; the gate input list adds `apps/core` and the assembler, and the
  gate rebuilds pins through the assembler.
- Z9X: APK bytes unchanged; gate script changed. Needs the owner's OK and one re-record.
- Gate: dry run first (`assemble_app.py horse_popular --compare` against the pre-move tree, identical); then
  `z9x_gate.sh --full`: 16 of 16 pins identical; `prove_unchanged.sh` green except the expected gate-record line,
  then baseline refreshed once with `record_mac.sh --refresh`.

### Stage 5: modules and profile on the non-Z9X side

- Introduce `profile.conf` and `gen_profile.py`; the HB `HbFeatures` table becomes `devices/goat_elegant/profile.conf`;
  device-only code with a second user moves to `apps/core/modules/`. Profile constants are used from device layers
  and modules only, so no core file changes.
- Z9X: none (Z9X does not use the profile yet; its assembly does not include the generated class).
- Gate: HB and Play 6 APKs differ only in the classes the stage names (dex diff report); device smoke tests; Z9X gate
  GREEN with 0 changed.

### Stage 6: seam release (optional, owner decision)

- Core files gain profile questions; the small forks (7 on the HB, the Play 6 equivalents) are deleted; Z9X gets its
  own `profile.conf` whose values equal today's behaviour.
- Z9X: APK bytes change on purpose.
- Gate: section 5.4 proof; shipped as a normal Z9X release; new pins recorded once.

### Stage 7: image side

- 7a: `tools/image/lib/` step scripts; HB `compose_hb.sh` becomes a recipe in `image.conf` plus device files. Gate:
  HB image built twice from the same inputs is identical (closes `compose_hb.sh:40-41`), and its file listing equals
  today's HB image.
- 7b: Play 6 through the generic composer as a shadow build. Gate: compose inputs and planned operations equal
  `devices/_baseline/mac/play6_compose/`, image file listing and hashes equal the `compose.sh` image; then the switch.
- 7c: one signer (`signlib.py`), props from `device.conf`, pinned list once, installer ab-fastbootd flow from data.
  Gate: `check_device_signers.py` green on all images; installer goldens unchanged for Z9X and Play 6.
- 7d (optional): Z9X shadow build compared with `lumen_v1.sh` output; switch only with the owner's OK and an equal
  file listing.

### Stage 8: new-device scaffold

- `tools/devices/new_device.sh`, publish rules from the registry, a short `docs/architecture/NEW_DEVICE.md`.
- Gate: scaffold a dummy codename in a scratch copy, `check.sh --device dummy` reaches the expected "pending" state
  without touching any existing device; dummy removed.

Rough effort for stages 0 to 5 and 7a: about 2 to 3 weeks of focused work, mostly mechanical and checkable. Stage 3
(Play 6) is the largest single piece because Play 6 has the most genuinely different code.

## 8. Risks and how they are handled

| Risk | Handling |
|---|---|
| Assembly order changes javac or aapt2 output | dry-run compare before stage 4; gate rebuild proves pins anyway |
| A core edit for one device breaks another | `check.sh --all` builds and tests every device assembly; tests run per device with that device's profile |
| A core edit changes the Z9X APK by accident | the gate goes red exactly as today; the change becomes a deliberate Z9X change or is reverted |
| Profile grows into a second programming language | the profile holds only facts and switches; behaviour stays in Java; a new key needs a core reader and a test |
| Work in `apps/` by other workflows during migration | each stage is short, starts from a green `check.sh --all`, and is one commit series per stage |
| Same-name adapters hide a missing method | the HB pattern already throws `NotOnHbException` for absent features; the profile makes those features `off`, so no row calls them |

## 9. Open questions for the owner

1. Accept stage 4 (gate script edit and one re-record, Z9X APKs proven identical)?
2. Take the seam release (stage 6) as a future Z9X version, or keep small forks forever for the Z9X?
3. Should the Z9X image ever move off `tools/lumen_v1.sh` (stage 7d), or stay on it for the life of the 1.0.x line?
4. Is `apps/core/` the right name for the shared layer, given that `apps/common/` exists and would be emptied?
