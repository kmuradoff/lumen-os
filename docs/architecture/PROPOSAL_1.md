# Proposal 1: minimal change, layered apps (2026-10-30)

Angle: keep the current apps and pipelines. Replace file copies with build-time layers, and add small per-device
adapters only where duplication hurts most. Smallest migration risk for the Z9X.

Inputs: `MAP_apps.md`, `MAP_image.md`, `MAP_checks.md` (same folder), plus a read-only hash comparison of the three
projector app trees made for this proposal on 2026-10-30. Nothing outside this file was changed. Paths are relative
to `gsi/`.

## 1. Short answer

- The projector apps already share almost everything, but they do it by keeping copies on disk: 230 `FILES`
  copies in every app and 273 HB `same:` copies. About 42,000 lines exist only to stay equal to another file, and
  every Z9X edit turns the copies red.
- The core idea of this proposal: **a device app stops being a full source tree and becomes a recipe**. A recipe
  says "start from this base, drop these files, put my files on top". A small new assembler writes the full tree
  into a scratch folder at build time and calls the unchanged `apps/build_apk.sh`.
- Measured today (hash comparison of the trees, 2026-10-30):
  - HbProjector = Z9xProjector **minus 18 files** (the `report/` package, `ThreeD.java`, `Z9xFitDevice.java`,
    `HidlCaller.java`, `LumenImport.java`, 4 docs) **plus 19 forks plus 53 HB-only files**. The other 538 files are
    byte-identical to the Z9X. As a recipe the HB app is about 90 files instead of 610.
  - Play6Projector = the 230 `apps/common/projector` files plus its own 100 files. The common part becomes one
    recipe line.
- Stages 0 to 3 touch **no Z9X gate input at all**. The Z9X is then unchanged by construction, and the gate proves
  it with "0 changed inputs". That is the whole Z9X risk story for the first three stages.
- Stages 4 and 5 are optional and are the only ones that touch the Z9X. Stage 4 moves the shared code into its own
  folder (`apps/core/projector`) and proves the Z9X APK still comes out byte-identical. Stage 5 adds small
  "device questions" to shared code and does change Z9X bytes once. The owner decides whether and when.
- A fifth projector after stage 3 needs a recipe, a HAL client with the Z9X class names, a feature table, its real
  forks and its strings. The app side drops from "copy and track about 600 files" to "write about 60 to 100 files".
  The image and installer side is not changed by this proposal (section 9).

## 2. Constraints this proposal keeps

1. Z9X byte-identical: the 16 pins in `overlay/apps_v1` rebuild identically and no image input moves without the
   owner's OK (`tools/play6/z9x_gate.sh:57-72`, `:101-127`).
2. A need of another device never edits Z9X code without the owner's OK (`apps/common/projector/README.txt:10-20`).
3. A Z9X change that breaks another device is fixed on that device's side and never blocks a Z9X release.
4. Every fork stays visible and tracked against its origin.
5. Builds stay Gradle-free (aapt2, javac, d8, apksigner via `apps/build_apk.sh`).
6. Other workflows are editing `apps/` now. Nothing here needs a "big bang" day: every stage can start and stop
   on its own.

## 3. Module diagram

### 3.1 Today

```
apps/Z9xProjector/ (558 files, origin of everything)
  |  sync.sh copies 230 FILES  ----------------------------+
  |  refresh_same.sh copies 273 same: rows ---+            |
  v                                           v            v
apps/common/projector/ (copy 0)       apps/HbProjector/   apps/Play6Projector/
                                      (610 files, 90% copy) (330 files, 230 copied)
Each tree -> apps/build_apk.sh -> APK
Tracking: FILES + sync.sh --check, forks.tsv (HB 291 rows, Play 6 26 rows), two fork checkers
```

### 3.2 After stage 3 (no Z9X input touched)

```
                      +-----------------------------+
                      | apps/Z9xProjector/          |  unchanged, still the Z9X app
                      | apps/common/projector/      |  unchanged, still synced into Z9X
                      +--------------+--------------+
                                     | read-only base layers
               +---------------------+----------------------+
               |                                            |
   devices/goat_elegant/app/projector.layers    devices/dragon_healthy/app/projector.layers
     base  apps/Z9xProjector                      base  apps/common/projector (FILES)
     drop  18 paths                               top   apps/Play6Projector (its own 100 files)
     top   apps/HbProjector (19 forks + 53 own)
               |                                            |
               +--------> tools/devices/assemble_app.py <---+
                          (writes build/assembled/<App>/, checks shadows)
                                     |
                                     v
                           apps/build_apk.sh (unchanged)
```

### 3.3 After optional stage 4 (shared folder, Z9X proven identical)

```
apps/core/projector/            shared code and resources (ex-common FILES + ex-Z9X files the HB also uses)
   ^          ^          ^
   |          |          |
apps/Z9xProjector/   apps/HbProjector/    apps/Play6Projector/     apps/<Next>Projector/
 Z9X layer only       HB layer only        Play 6 layer only        new layer only
 (HIDL clients,       (AidlCaller, HB       (Play6Gmpf*, battery,    (its HAL client,
  report/, ThreeD,     clients, HbFeatures,  speaker, lens, ...)      feature table, forks)
  Z9xFitDevice)        CurtainAlign, ...)
   each app: devices/<codename>/app/projector.layers -> assemble_app.py -> build_apk.sh
```

### 3.4 Adapter seams (optional stage 5)

```
shared panel code
   |  asks
   v
DeviceProfile (small interface in core)      FitDevices.get() already works this way today
   |-- Z9xProfile   answers "stock Z9X"
   |-- HbProfile    answers from HbFeatures and HB stock behaviour
   |-- Play6Profile answers from Device.isPlay6() rules
GmpfClient / Gmpf2Client: same class names and signatures on every device, body per device (the HB pattern)
```

## 4. What goes where

| What | Stages 0-3 | After stage 4 |
|---|---|---|
| Code used by the Z9X and at least one other device | stays in `apps/Z9xProjector` or `apps/common/projector`; other devices read it as a base layer | `apps/core/projector` |
| Z9X-only code (`report/`, `ThreeD`, `Z9xFitDevice`, HIDL client bodies) | `apps/Z9xProjector` | `apps/Z9xProjector` (now only the Z9X layer) |
| HB-only code (`AidlCaller`, HB clients, `HbFeatures`, `CurtainAlignOverlay`, `HbFitDevice`, `device/`) | `apps/HbProjector` | same |
| HB forks of shared files (19 today) | `apps/HbProjector` at the same path, one row each in `devices/goat_elegant/forks.tsv` | same, origin points into core |
| Play 6 own code (100 files incl. 23 forks) | `apps/Play6Projector` | same |
| `HidlCaller.java` (common `hal` item) | unchanged (`sync.sh hal`) | a core layer used by HIDL devices only: `apps/core/hal-hidl/` |
| Recipe per device app | `devices/<codename>/app/projector.layers` | same |
| Assembler | `tools/devices/assemble_app.py` (new, outside gate inputs) | same, and the Z9X gate calls it |
| Host tests | Z9X tests stay; HB stops copying them and runs them on its assembled tree | `apps/core/projector/test/` runs once per device app |
| Strings | unchanged generators; HB fills the 6 missing `strings_hb_fit.xml` locales | one per-device strings layer, generator config outside `apps/i18n` (section 7.6) |
| Device facts (identity, keys, HAL tables) | `devices/<codename>/device.conf` (unchanged) | same, plus key `app_layers=` |

### 4.1 Recipe format (proposal)

```
# devices/goat_elegant/app/projector.layers
app      HbProjector                     # output name, manifest comes from the top layer
base     apps/Z9xProjector               # full tree, read only
drop     src/org/lumen/projector/report/ # 18 paths in all, each must exist in the base
drop     src/org/lumen/projector/fit/Z9xFitDevice.java
...
top      apps/HbProjector                # files here win; a file that shadows a base file needs a fork row
skip     CHANGES.md README_ru.md SYNCED.txt   # docs never enter an APK tree
```

Rules the assembler enforces (fail-closed):
- every `drop` path exists in the base (a renamed Z9X file is noticed);
- every top file that shadows a base file has a row in the device's `forks.tsv` (this makes the untracked
  `fit/FitDevices.java` fork from `MAP_apps.md` 3.2 impossible);
- no top file shadows a file that is byte-identical to it (that would be a pointless copy: delete it);
- the output is a plain folder with the same layout `apps/build_apk.sh` already expects (`apps/build_apk.sh:6-24`).

## 5. Where adapters pay off most (ranked)

Only these, in this order. Everything else stays a plain layer or a plain fork.

| Rank | Seam | Removes | Touches Z9X? |
|---|---|---|---|
| 1 | Recipe layers for the HB | 273 `same:` copies, `refresh_same.sh`, 230 `FILES` copies in the HB | no |
| 2 | Recipe layers for the Play 6 common part | 230 `FILES` copies in the Play 6 | no |
| 3 | Fork tracking from the assembler (shadow = fork row) | untracked forks, the `same:` row kind | no |
| 4 | `DeviceProfile` questions for the 7 small HB forks (`FitDevices`, `TimerLamp`, `EyeGuard`, `SoundEq`, `DreamLamp`, `SpdifOff`, `SetupBridgeProvider`, each 30 diff lines or fewer) | 7 whole-file forks | yes, once (stage 5) |
| 5 | Play 6 facade with the Z9X client names (`GmpfClient` over `Play6Gmpf`) | lets more Play 6 files join the shared layer later | no for the Z9X, yes for the released Play 6 app |

Rank 5 is listed for completeness. It is a Play 6 project of its own, not part of the minimal path.

## 6. Adding a new projector

### 6.1 Steps (after stage 3)

| # | Step | Time |
|---|---|---|
| 1 | `devices/<codename>/device.conf` and `image.conf`, `devices.py validate` green | 1 to 2 h |
| 2 | Pick the closest existing app as base: Z9X tree for HIDL, the HB recipe's result for stable AIDL (layers can stack: `base` may be another recipe) | 15 min |
| 3 | Write `devices/<codename>/app/projector.layers` with drops for features the device lacks | 30 min |
| 4 | HAL client: `GmpfClient` and `Gmpf2Client` with the Z9X names and signatures, body over the device transport; code tables in `devices/<codename>/research/` | 1 to 3 days (depends on the HAL; unchanged by this proposal) |
| 5 | Feature table on the `HbFeatures` model (read stock `feature.xml`, stock "off" wins, never show a disabled row) | 2 to 4 h |
| 6 | Forks for real stock differences, one row each; prefer a `DeviceProfile` answer once stage 5 exists | depends on the device; each fork 30 min to tracking |
| 7 | Manifest and device strings (17 locales) | 2 to 4 h |
| 8 | `tools/devices/check.sh --device <codename>`: assemble, fork check, HAL check, host tests, APK | minutes |
| 9 | Image, signing, installer, release: the step list in `MAP_image.md` section 10, unchanged | days |

App side today versus after stage 3:

| | Today | After stage 3 |
|---|---|---|
| Files to create in the app folder | about 600 (copy the Z9X tree) | about 60 to 100 (HAL, features, forks, own code) |
| Rows to keep in `forks.tsv` | about 300 (`same:` plus forks) | only real forks |
| Work after every Z9X change | re-copy and re-hash `same:` rows, review forks | review only forks that the Z9X change touched (shown as warnings) |
| Time to a first building APK | about 1 day of copying and tracking | 1 to 2 hours plus the HAL client |

The real cost of a projector (HAL work and stock 1:1 testing on the device) does not change. This proposal removes
the bookkeeping around it.

## 7. How checks change

### 7.1 Z9X gate (`tools/play6/z9x_gate.sh`)

- Stages 0 to 3: **no change**. The new files live in `devices/` and `tools/devices/`, which are not gate inputs
  (`z9x_gate.sh:57-72`). The HB and Play 6 folders are not gate inputs either. Each stage gate below requires a
  GREEN run with `0 changed` inputs, which is the proof that nothing the Z9X ships moved.
- Stage 4: the input list gains `apps/core`, and the pin rebuild for `Z9xProjector` calls the assembler before
  `build_apk.sh` (around `z9x_gate.sh:175`). This is a gate change, so it needs the owner's OK and one re-record.
  After that, a core change triggers "rebuild every pin" exactly like `apps/common` does today (`:101-102`) and
  stays GREEN when the Z9X APKs come out identical.

### 7.2 Fork checks

- The HB `same:` row kind disappears (stage 2). `refresh_same.sh` is retired.
- A fork row is no longer typed by hand: the assembler lists shadows, and a shadow without a row is an error.
- "Origin changed" becomes a **warning in development builds and red only in release builds** (`--release`). So a
  Z9X change no longer stops `build_play6.sh:37` or the HB `build.sh`, but no release can ship with an unreviewed
  fork. The warning lists the forks and the Z9X diff to port.
- The two fork checkers merge (stage 1): the Play 6 semantic rules (`keys:`, `wire:`, `config:`) become row kinds
  in `tools/devices/check_forks.py`, and the duplicate pass (`tools/devices/check_forks.py:93-99`) goes.
- The rule "every Z9X resource is placed in FILES or `res_not_shared.txt`" (`tools/play6/check_forks.py:164`)
  becomes unnecessary for the HB (it gets every Z9X resource unless dropped) and stays for the Play 6 until a
  shared folder exists.

### 7.3 `sync.sh --check`

- HB and Play 6 hold no copies after stages 2 and 3, so the check stops running for them.
- It keeps running for the Z9X until stage 4, then `sync.sh projector` is retired. `sync.sh tokens`, `keys`,
  `geoip`, `migrate` stay as they are (outside the projector app).

### 7.4 HAL checks

- Unchanged in logic. `check_hal.py --device <c>` runs on the **assembled** tree, which is exactly what is
  compiled, so nothing can slip in through a base layer.
- Stage 0 fixes the stale literal 141 in `tools/devices/test/check_aidl_hal_test.sh` (read the count from
  `allowed.tsv`).
- Moving the Z9X banned list out of `apps/check_hal_callers.py` (a gate input) is worth doing but costs a Z9X
  re-record, so it rides along with stage 4.

### 7.5 Proof bundle (`tools/devices/prove_unchanged.sh`)

- Stage 0: compare exit codes and hashes instead of counts in text ("16 of 16" at `:41`, normalised check output
  at `:61-63`); one command refreshes the whole baseline after an owner OK.
- Stage 2: add the HB (assembled tree hash plus APK hash with the throwaway key) so the HB is covered by an
  aggregate for the first time.
- New baseline file per device: `assembled_tree.sha256` (sorted path and sha256 of the assembled folder). It is the
  cheapest proof that a recipe change did what it meant to do.

### 7.6 Strings

- Stage 0: fill the 6 missing locales in `strings_hb_fit.xml`.
- Later: a generator config per device that reads `apps/i18n/langs` read only and writes into the device layer, so
  `apps/i18n/gen.py` (a gate input) stays untouched.

### 7.7 One entry point

`tools/devices/check.sh [--device C | --all] [--release]` runs per registry device: assemble, fork check, HAL
check, host tests, compile with a throwaway key; plus the Z9X gate for horse_popular and the publish audit once.
This is the single command after any change, and it builds the HB and Play 6 on every Z9X change, so breakage is
seen the same day instead of at the next HB session.

### 7.8 Publish audit

- `devices/*/app/*.layers` of private devices fall under the existing `devices/` EXCLUDE rule
  (`docs/repo/publish_audit.py:59-65`), so recipes stay private by default.
- Stage 4 adds one PUBLISH rule for `apps/core/`.

## 8. Staged migration plan

Every stage ends with a gate. A stage that does not reach its gate is reverted by deleting its new files: no stage
before 4 edits an existing Z9X file, so a revert never touches the Z9X.

### Stage 0: hygiene (half a day)

Do:
- add the missing `fit/FitDevices.java` row to `devices/goat_elegant/forks.tsv`;
- fix the literal 141 in `check_aidl_hal_test.sh`;
- make `prove_unchanged.sh` compare hashes and exit codes, not counts in text; add a one-step refresh;
- add the 6 missing HB locales;
- bring the Play 6 fork rows up to date (7 origins plus `strings_fit.xml`), so the starting point is green.

Gate 0: `check_forks.py --all` green; `check_aidl_hal_test.sh` 33 of 33; Z9X gate GREEN with 0 changed inputs.

### Stage 1: assembler, no device switched (1 day)

Do:
- write `tools/devices/assemble_app.py` (layers, drops, shadow rule, `--check`, `--out`);
- write the HB and Play 6 recipes;
- merge the two fork checkers;
- add `tools/devices/check.sh`.

Gate 1 (equivalence, the important one):
- the assembled HB tree equals today's `apps/HbProjector` tree file for file (sorted path and sha256), except the
  4 docs on the skip list;
- the same for the Play 6;
- an APK built from the assembled tree is byte-identical to one built from today's tree with the same key
  (throwaway key for the HB, the Play 6 key for the Play 6). This proves that a build from a scratch folder does
  not change the output. The gate already relies on the same property when it rebuilds pins.
- Z9X gate GREEN, 0 changed inputs.

### Stage 2: switch the HB (1 day)

Do:
- `apps/HbProjector/build.sh` and `tools/devices/build_apps.sh goat_elegant` build from the assembled tree;
- delete the 538 base copies from `apps/HbProjector` (273 `same:` plus the common copies), keeping 19 forks plus
  53 own files;
- `forks.tsv` keeps only real fork rows; retire `refresh_same.sh`;
- the HB host tests run against the Z9X test sources through the recipe instead of copies;
- add the HB to the proof bundle.

Gate 2: the HB APK built after the switch equals the gate 1 APK byte for byte; HB host tests pass;
`check_hal.py --device goat_elegant --dex` OK; Z9X gate GREEN, 0 changed inputs. Before the next HB image
release: one device smoke test (boot, quick panel, keystone, picture), because from now on the HB follows Z9X
changes automatically.

### Stage 3: switch the Play 6 common part (half a day)

Do:
- `tools/play6/build_play6.sh` builds `Play6Projector` from its assembled tree;
- delete the 230 common copies from `apps/Play6Projector`;
- `sync.sh --check` stops running for the Play 6.

Gate 3: the four Play 6 APKs equal the stage 1 APKs (and `play6_apks.sha256` of the refreshed baseline); check H
and check K green; Z9X gate GREEN, 0 changed inputs.

Stop here and use it for a while. Stages 0 to 3 give most of the gain with zero Z9X risk.

### Stage 4 (optional): a shared folder (2 days, owner OK required)

Do:
- create `apps/core/projector` with the union of the 230 common files and the Z9X files the HB uses unchanged;
- the Z9X app gets its own recipe (core plus Z9X layer); the HB recipe base becomes core;
- the gate input list gains `apps/core`, the pin rebuild calls the assembler;
- retire `sync.sh projector` and `apps/common/projector/FILES`;
- move the Z9X HAL banned list to a data file outside the gate inputs.

Gate 4:
- `Z9xProjector.apk` rebuilt from core plus the Z9X layer is byte-identical to the current pin;
- all 16 pins identical, gate GREEN after one owner-approved re-record (the input list changed, the outputs did not);
- HB and Play 6 APKs identical to their stage 3 hashes.

What the owner must accept: one re-record of the gate whose only reason is new input paths, with the pins proving
that the outputs are the same.

### Stage 5 (optional, per file): device questions (ongoing)

Do:
- add `DeviceProfile` to core, with one method per real difference (for example "lamp dims with dynamic black",
  "keystone reset path", "fit device");
- replace an HB fork with an HB answer **only when the Z9X file is changed anyway for a Z9X reason** (piggyback
  rule), so no Z9X release carries a change made only for another device.

Gate 5 (per file): Z9X host tests plus one new test that the Z9X answer equals the old behaviour; Z9X device test
of the touched screen; owner OK; the Z9X pin is re-pinned as part of a normal Z9X release; the HB fork row is
removed.

What the owner must accept: this stage changes Z9X bytes. The Z9X behaviour stays the same, but byte-identical is
replaced, for those files, by "behaviour proven equal by tests and a device check in a release". Skipping stage 5
is a valid choice: the cost is 7 small HB forks that need a look when their Z9X origin changes.

### Effort summary

| Stage | Effort | Z9X inputs touched | Gain |
|---|---|---|---|
| 0 | half a day | none | green start, honest checks |
| 1 | 1 day | none | assembler proven equivalent |
| 2 | 1 day | none | HB: 538 copies and `refresh_same.sh` gone, HB follows Z9X automatically |
| 3 | half a day | none | Play 6: 230 copies gone |
| 4 | 2 days | gate input list (one re-record) | one shared folder, `sync.sh projector` gone |
| 5 | per file | Z9X bytes, in normal releases | small forks gone |

## 9. What this proposal does not change

- Image pipelines: `tools/lumen_v1.sh` (Z9X), `tools/play6/build_test.sh` with `compose.sh` (Play 6) and
  `compose_hb.sh` (HB) stay as they are. The 548-line `compose_hb.sh` fork of the Play 6 composer is the next big
  duplicate; a shared composer is a separate proposal.
- Installer: `installer/lumen-install.sh` still knows only `z9x|play6` (`:1515`, `:2813`); the generic
  `ab-fastbootd` flow is still to be built (`devices/goat_elegant/IMPLEMENTATION_LOG.md:108`).
- Signing, release and OTA: unchanged. Two cheap fixes are worth doing next to stage 1 because they only read the
  registry: the certificate list in `tools/devices/build_apps.sh:42-43` should include `devices/*/certs`, and the
  publish audit app rules should come from `app_prefix` instead of name prefixes
  (`docs/repo/publish_audit.py:210-227`).
- Other apps (`HbFrameworkOverlay`, `Play6FrameworkOverlay`, `Play6Updater`, `HbBoot`): the same recipe mechanism
  works for them later, but they are small and not where the duplication is.

## 10. Risks

| Risk | Effect | Mitigation |
|---|---|---|
| The HB now follows Z9X changes automatically | a Z9X change can alter HB behaviour without an HB review | `check.sh --all` compiles and tests the HB on every Z9X change; `HbFeatures` hides missing features; forks still warn; a smoke test before each HB release |
| A build from a scratch folder differs from a build in place | APK hashes move | gate 1 proves equality before any switch; the gate already rebuilds pins from scratch build dirs |
| Recipe drops hide a new Z9X file the HB cannot use | HB compile error or a dead feature row | a compile error is loud; a feature row is guarded by `HbFeatures` (rule "never show a disabled row") |
| Parallel work in `apps/` during the switch | lost edits | switch one device per day, after `check_forks --device` is green, and announce the switch in that device's notes |
| Owner rule read as "no file under the Z9X folder may be read by others" | stage 2 looks like a rule change | nothing writes into Z9X folders in stages 0 to 3; reading is what the `same:` copies already do, just without the copy |

## 11. Questions for the owner

1. Is it acceptable that the HB picks up Z9X changes automatically (with warnings on forks and red only at
   release), instead of staying frozen until someone re-copies? This is the core trade of the proposal.
2. Stage 4: accept one gate re-record whose only reason is new input paths, proven by identical pins?
3. Stage 5: accept Z9X byte changes for device questions, only inside normal Z9X releases and only when the file is
   touched anyway? Or keep the small HB forks?
4. Should the Play 6 facade (rank 5) be planned, given the Play 6 app is released and its HAL clients were renamed?
