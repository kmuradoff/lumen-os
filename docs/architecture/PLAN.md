# Plan: one code base for many projectors (2026-10-30)

Inputs: `MAP_apps.md`, `MAP_image.md`, `MAP_checks.md`, `PROPOSAL_1.md`, `PROPOSAL_2.md`, `PROPOSAL_3.md` (same
folder), plus a read of the Z9X gate input list (`tools/play6/z9x_gate.sh:57-72`). Paths are relative to `gsi/`.
Nothing outside this folder was changed.

## 1. Decision in short

- **Winner: Proposal 1 (layered apps, minimal change) as the migration path.** It removes most of the copying in
  about 3 working days and touches no Z9X gate input on the way.
- **Target shape: from Proposal 2.** A shared core, optional modules, a thin device layer, and a device profile
  generated into `static final` Java constants.
- **Grafted from Proposal 3:** HAL tables as the single source for clients and checks, one table-driven HAL
  checker, the `lumen` command as the single entry point, a `record --reason` command, a recording transport for
  tests, and the stock product menu trees used as a parity to-do tool.
- **Not taken now:** Proposal 3's runtime menu engine and its mandatory Z9X switch release. Both are big and the
  second puts the released Z9X at risk. They stay as later options (section 7).

Why the Z9X stays safe: the gate freezes `apps/Z9x*`, `apps/common`, `apps/i18n`, `apps/sdk`, `apps/LumenBridge`,
`overlay/*`, `tools/lumen`, `tools/sign`, `tools/ota`, `apps/build_apk.sh`, `tools/lumen_v1.sh` and a few checkers
(`z9x_gate.sh:57-72`). It does **not** cover `devices/`, `tools/devices/`, `tools/image/`, `apps/HbProjector` or
`apps/Play6Projector`. Everything in steps 1 to 3 lives in the uncovered part, so the proof is always the same
line: gate GREEN, 0 changed inputs.

## 2. Scores

5 is best. Weights are equal; the owner rule makes "risk to Z9X" a veto, not just one score.

| Criterion | P1 layered apps | P2 capability platform | P3 data + generators |
|---|---|---|---|
| Speed of adding a projector | 3 (app side only, about 60 to 100 files) | 4 (scaffold, profile, modules, 3 to 5 days) | 5 (survey, generated tables, 5 to 8 days incl. stock testing) |
| Daily dev comfort | 4 (no copies, no re-sync) | 4 (same plus profile switches) | 3 (one command, but generators to learn and double upkeep until the Z9X switch) |
| Risk to released Z9X | 5 (no gate input until optional stage 4) | 4 (same, plus optional seam release) | 2 (full payoff needs a Z9X switch release with behaviour proof) |
| Effort | 5 (about 3 days for stages 0 to 3) | 3 (2 to 3 weeks) | 2 (about 6 weeks or more) |
| Weak hardware speed | 5 (APK content unchanged) | 5 (compile-time constants, absent features compiled out) | 4 (static arrays, but an extra menu layer at runtime) |
| Testability | 3 (tests run per assembled tree) | 4 (tests per device with its profile) | 5 (recording transport, HAL trace compare, per-locale checks) |
| **Total** | **25** | **24** | **21** |

Proposals 1 and 2 share stages 0 to 3 almost word for word. The real choice is how far to go after that, so the
plan takes Proposal 1's steps first and keeps Proposal 2's target as the direction.

## 3. Target structure

```
devices/<codename>/                 data only, outside the Z9X gate
  device.conf  image.conf           exist today (registry, image recipe)
  app/projector.layers              NEW recipe: base, drops, top layer, modules
  profile.conf                      NEW: features on/off/pending, zoom model, keystone, small behaviours
  hal/codes.tsv banned.tsv map.tsv  HAL tables; map.tsv is also the allow list (HB has most of this today)
  overrides.tsv                     every device file that replaces a base file, with its origin hash
  strings/                          device strings, all 17 locales

apps/Z9xProjector/                  frozen, read in place as the base (until optional stage 4)
apps/core/projector/                LATER (optional stage 4): the shared code, proven by identical Z9X pins
apps/core/modules/<m>/              LATER: fit, battery, speaker, lens, curtain, report, zoom variants
apps/HbProjector/  apps/Play6Projector/
                                    shrink to the device layer: transport, clients with Z9X class names,
                                    real forks, device-only code, manifest, strings

tools/devices/
  assemble_app.py                   NEW: recipe -> full tree in a scratch folder -> unchanged apps/build_apk.sh
  gen_profile.py                    LATER: profile.conf -> DeviceProfile.java (sorted, static final)
  check.sh  (then `lumen`)          one entry: build, test, HAL, forks, strings, publish, Z9X gate, per device
  record.sh (then `lumen record`)   one-step baseline re-record with a written reason

tools/image/                        LATER: one composer with shared steps, one signer, installer flows by registry
```

Rules that hold in the target:
- A device file that shadows a base file without an `overrides.tsv` row is a build error (closes the untracked
  `fit/FitDevices.java` hole from `MAP_apps.md`).
- A file in the top layer that is byte-identical to the base is an error (no pointless copies).
- A changed origin under an override is a warning in development builds and red only in release builds, so Z9X
  work never blocks HB or Play 6 builds, and nothing slips into a release unreviewed.
- Features a device lacks are compiled out. Pending features build no row until their check passes ("never show
  a disabled row" holds by construction).
- Checks compare hashes and exit codes, never counts in text.

## 4. The first 3 steps

Each step is small, ends green, and can be reverted by deleting its new files. Start each one only when the other
workflows that are editing `apps/` have landed, from a green `check_forks --all`.

### Step 1: make today's checks green and honest (half a day to 1 day)

Do:
- Add the missing `fit/FitDevices.java` row to `devices/goat_elegant/forks.tsv`.
- Replace the literal 141 in `check_aidl_hal_test.sh` with "equal to the number of allowed rows" (HB now sends 143).
- Close the 7 red Play 6 origin rows and place `res/values/strings_fit.xml`: for each one, either take the Z9X fix
  into Play 6 (owner choice, changes the Play 6 APK) or record it as an accepted difference.
- Close the 4 red HB origin rows (OpticalZoomPanel, QuickPanelController, GmpfClient, Gmpf2Client) the same way.
- Add the 6 missing locales to `strings_hb_fit.xml` (ar, be, kk, pt-rBR, tr, uk).
- `prove_unchanged.sh`: compare hashes and exit codes instead of "16 of 16" text; add `tools/devices/record.sh`
  that re-records the whole baseline once and appends the reason to `BASELINE.md`.
- Add the HB to the combined check run.

Proof for the Z9X: every touched file is under `devices/`, `tools/devices/`, `apps/HbProjector` or
`apps/Play6Projector`, none of which the gate reads. Gate GREEN, 0 changed inputs; `prove_unchanged.sh` green after
one recorded re-record. What changes: only Play 6 or HB APKs, and only where the owner chose to take a Z9X fix.

### Step 2: the assembler, in compare mode only (1 day)

Do:
- Write `tools/devices/assemble_app.py`: reads `devices/<c>/app/projector.layers` (base, drop, top, skip), writes
  the full tree to a scratch folder, enforces the rules in section 3, and has `--compare <folder>`.
- Write the HB recipe (base `apps/Z9xProjector`, 18 drops, top `apps/HbProjector`) and the Play 6 recipe (base
  `apps/common/projector`, top `apps/Play6Projector`).
- Merge the two fork checkers into the assembler's shadow rule (one table format, `overrides.tsv`).
- Add `tools/devices/check.sh <codename|all>` as the one entry point.

Proof (the important one):
- the assembled HB and Play 6 trees equal today's trees file by file (sorted path plus sha256), docs excepted;
- an APK built from the assembled scratch folder is byte-identical to one built from today's folder with the same
  key. This also covers the known risk that `apps/build_apk.sh:198` passes the Java file list from `find` without
  sorting: if the order differs, this check is red and the assembler must write files so that the order matches.
  `build_apk.sh` itself is a gate input and is not edited.
- Z9X gate GREEN, 0 changed inputs. Nothing builds from the assembler yet.

### Step 3: switch the HB to its recipe (1 day)

Do:
- `apps/HbProjector/build.sh` and `tools/devices/build_apps.sh goat_elegant` build from the assembled tree.
- Delete the 538 base copies from `apps/HbProjector`; keep the 19 real forks and 53 HB-only files.
- Retire `refresh_same.sh` and the `same:` rows; HB host tests run on the Z9X test sources through the recipe
  instead of copies.
- Add the HB to the proof bundle.

Proof: the HB APK after the switch equals the step 2 APK byte for byte; HB host tests pass;
`check_hal.py --device goat_elegant --dex` OK; Z9X gate GREEN, 0 changed inputs. What changes in behaviour:
nothing today. From now on the HB follows Z9X changes automatically, so before the next HB image there is one
device smoke test (boot, quick panel, keystone, picture) with the usual adb loop.

After step 3 the HB app is about 90 files instead of 610, and `apps/HbProjector` is what a fourth device will
look like.

## 5. Next steps after the first three (direction, not yet scheduled)

| # | Step | Touches Z9X | Gain |
|---|---|---|---|
| 4 | Play 6 common part on its recipe (230 copies go, `sync.sh --check` stops for Play 6) | no | Play 6 copies gone |
| 5 | `profile.conf` plus `gen_profile.py` for HB and Play 6; `HbFeatures` and `isPlay6()` become profile data; HB HAL clients generated from `map.tsv`; one table-driven HAL checker; Z9X banned list moved to a data file outside gate inputs (read, not edited, until stage 7) | no | features as data, HAL checked before compile |
| 6 | Image side: one composer with shared steps, image built twice equal; one signer that does not import `sign_play6.py`; pinned list written once; ab-fastbootd installer flow built once and chosen by registry; cert list in `build_apps.sh:42-43` and publish rules read the registry | no (Z9X keeps `lumen_v1.sh`) | no more 550-line composer forks |
| 7 | `lumen` command (`new-device --like`, `survey`, `build`, `check`, `prove`, `record`) and a rehearsal with a fake fourth device | no | new projector from a scaffold |
| 8 | Optional: physical `apps/core/projector` (owner OK, one re-record, all 16 pins identical) | gate input list only | one shared folder, `apps/common` FILES retired |
| 9 | Optional: seam release, small forks become profile questions inside shared code, only when the Z9X file changes anyway in a normal Z9X release | yes, on purpose | the last small forks go |

## 6. Stop doing now

- **No new `same:` copies and no new whole-file forks for a small difference.** A device difference goes into a
  device-only class or the device feature table; if a fork is truly needed it gets an `overrides.tsv` (today
  `forks.tsv`) row in the same commit.
- **No new files added to `apps/common/projector/FILES`.** It is a gate input and will be retired.
- **No new device name literals** (`z9x|play6` style) in the installer `.sh` or `.ps1`, and no new
  `isPlay6()`-style identity checks to switch features.
- **No copying a composer or signer for a new device.** Wait for step 6.
- **No starting a fourth projector app before step 3 lands.** Start it from the HB recipe shape.
- **No piecemeal hand re-records of the gate.** Use one re-record with a written reason (step 1 adds the command;
  until then write the reason in `BASELINE.md`).
- **No fixed counts in tests** (like the 141 in the AIDL test).
- **No hand-written device strings without all 17 locales.**

## 7. Open decisions for the owner

1. **The HB follows Z9X automatically** after step 3 (warnings on forks, red only at release, smoke test before
   each HB image), instead of staying frozen until someone re-copies. This is the main trade. Yes or no?
2. **Red fork rows today (step 1):** take the 7 Play 6 and 4 HB Z9X fixes into those devices (their APKs change),
   or record them as accepted differences for now?
3. **Physical shared folder (step 8):** accept one gate script edit and one re-record whose only reason is new
   input paths, proven by 16 of 16 identical pins? Is `apps/core` the right name?
4. **Seam release (step 9):** allow Z9X bytes to change for device questions, only inside normal Z9X releases and
   only when the file changes anyway? Or keep a few small forks forever?
5. **Z9X image builder:** keep `tools/lumen_v1.sh` for the whole 1.0.x line (recommended), or plan a move to the
   shared composer after a shadow build proves the same image?
6. **Play 6 clients:** rename or wrap `Play6Gmpf`/`Play6Gmpf2` as `GmpfClient`/`Gmpf2Client` in a future Play 6
   release, so more Play 6 files can use the shared code?
7. **Menus from stock trees:** use the stock product trees only as a parity to-do list (recommended now), or later
   build a generated menu engine for new projectors?
