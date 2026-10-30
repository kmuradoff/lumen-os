# Status: plan steps 1 to 3 (2026-10-30)

Owner approval 2026-10-30 ("Да, делай все"): decisions O1, O2 and section 7 of `PLAN.md` (the HB follows the Z9X
automatically with a device check before an HB release; the red Z9X fixes are ported into Play 6 and HB). Paths are
relative to `gsi/`. Nothing was flashed, published, pushed or sent to a device; no image was built.

## One command for every check

    sh tools/devices/check.sh all            # or one codename; --release makes a changed fork origin red

Per device: recipe assembly (and, for a recipe still in compare mode, equality with the app folder), strings in all
locales, compile with a throwaway key, HAL check (with `--dex` for the HB), host tests of the assembled tree, fork
check, HAL check; the Z9X gate for horse_popular, check K for the Play 6; then the tests of `tools/devices/test/` and
the publish audit. The proof bundle stays `sh tools/devices/prove_unchanged.sh`; after an approved change the whole
baseline is re-recorded in one step with `sh tools/devices/record.sh --reason "TEXT"`.

## Step 1: checks green and honest (done)

| Item | Done |
|---|---|
| `fit/FitDevices.java` row | was already in `devices/goat_elegant/forks.tsv` (added by the Z9X fit work) |
| AIDL test count | `tools/devices/test/check_aidl_hal_test.sh` reads "N calls on M interfaces" from `allowed.tsv` (rows, distinct interfaces without the `-` meta rows): 31 of 31 |
| Play 6 red rows (7) | reviewed against the old Z9X origins (rebuilt from `~/lumen-snapshots/gsi-pre-rename-20261029-0246.tgz` with the org.z9x -> org.lumen rename; each one matched its recorded sha256). Ported: `MotorController.exitForAutofocus`; `ManualFocusActivity` any other key closes, volume keys pass, `closeForAutofocus`, called from `Hal.requestAutofocus` and the AF start events 4 / 8 / 333 / 334 / 335; `Play6RotatePanel` `Locale.ROOT` (2 places). Nothing to port, written in the row: `Hal` (585 on the ceiling: the Play 6 never refused it), `StandbyController` (no move-AF standby marker on the Play 6), `ManualKeystonePanel` (207 reset and setKstPrepare: the Play 6 fork has neither, O3 / B9), `AudioOutputChoice` (no format row on the Play 6). New sha256 recorded in `tools/play6/forks.tsv` |
| `res/values/strings_fit.xml` | already placed by the Z9X fit work (check F: 424 resources placed) |
| HB red rows (4) | already green (the Z9X test3 work re-recorded OpticalZoomPanel, QuickPanelController, GmpfClient, Gmpf2Client) |
| HB locales | `strings_hb_fit.xml` in ar, be, kk, pt-rBR, tr, uk (pt-rBR = the stock pt text, as `strings_hb.xml`; the others own translation, the stock HB has none) |
| Proof bundle | `prove_unchanged.sh` compares hashes and exit statuses only (pins counted from `overlay/apps_v1`, no "16 of 16", no check text); new `tools/devices/record.sh` (refuses unless the gate is GREEN with 0 changed inputs, every check exits 0, APKs repeat and no publish REVIEW row; keeps the old record dated; appends the reason to `devices/_baseline/BASELINE.md`) |
| HB in the combined run | `devices/_baseline/record_mac.sh` records `hb_check_forks`, `hb_check_hal`, `hb_aidl_test`, `hb_assemble_test`, `hb_host_tests` (exit status), `hb_tree.sha256` and `hb_apk.sha256` (APK built twice, unsigned and test-key hash); `prove_unchanged.sh` section 8 checks them |

Play 6 APK: `Play6Projector.apk` moves from `ef8e3054...` (baseline) to `bf1f7a95...` (Play 6 key) because of the
port; `tools/play6/build_play6.sh` is green again (checks H, K, F).

## Step 2: the assembler in compare mode (done, proven)

- `tools/devices/assemble_app.py` (recipe -> full tree; rules: every drop removes something, no path from two bases,
  a differing shadow needs a fork-table row with that origin, a byte-identical shadow is an error unless
  `identical allow`, a changed origin is a warning and red with `--release`); `--out`, `--compare`, `--list`.
  Tests: `tools/devices/test/assemble_app_test.sh`, 15 of 15.
- Recipes: `devices/goat_elegant/app/HbProjector.layers` (base `apps/Z9xProjector`, 6 drops covering 32 files: `report/`,
  `ThreeD`, `Z9xFitDevice`, `HidlCaller`, `LumenImport`, the 18 `strings_kst_restore.xml`; top `apps/HbProjector`)
  and `devices/dragon_healthy/app/Play6Projector.layers` (base `apps/common/projector` by its `FILES`, plus
  `HidlCaller` and `LumenImport`; top `apps/Play6Projector`; still in compare mode).
- `tools/devices/check.sh` is the one entry point (above).

Proofs (listings in `devices/_baseline/arch_20261030/`):

| What | Result |
|---|---|
| HB tree | assembled = `apps/HbProjector`: 616 files, path and sha256 equal (tree sha256 `8bac0604...`) |
| Play 6 tree | assembled = `apps/Play6Projector`: 330 files equal |
| HB APK, throwaway key | full folder `15429714...` = assembled `15429714...` |
| Play 6 APK, Play 6 key | full folder `bf1f7a95...` = assembled `bf1f7a95...` = `build_play6.sh` output |
| `find` order in `build_apk.sh` | the 150 app sources come in the same order; only the scratch path of the generated `R.java` differs, which does not reach the APK |

## Step 3: the HB on its recipe (done, proven)

- `apps/HbProjector` keeps 78 files (19 forks, 59 HB-only); the 538 byte-identical copies are deleted (308 `same:`
  rows plus 230 synced common files). `forks.tsv` keeps 23 rows. `devices/goat_elegant/image/refresh_same.sh` is
  deleted. The recipe left compare mode.
- Built from the assembled tree: `apps/HbProjector/build.sh` (check_forks, assemble into `build/assembled`, build,
  `check_hal --dex`, host tests), `tools/devices/build_apps.sh goat_elegant`, `tools/devices/check_hal.py` (assembles a
  switched hal_app), `check_aidl_hal_test.sh`, `devices/goat_elegant/image/compare_hal_api.sh`. The HB host tests now
  run the Z9X test sources from the recipe; `test/power/run.sh` reads `LUMEN_GSI` for the image props.
- Backup of the folders before the change: `~/lumen-snapshots/gsi-arch-steps-20261030-pre.tgz`.

Proofs:

| What | Result |
|---|---|
| Assembled HB tree right after the switch | equal to the pre-switch `apps/HbProjector` (616 files, listing byte-identical, `hb_tree_step2` = `hb_tree_step3`) |
| HB APK, platform key (`build_apps.sh goat_elegant`) | before `dc3b1895...`, after `dc3b1895...` (also the hb-t8c APK of the HB lane) |
| HB APK, throwaway key (`build.sh`) | `15429714...`, as in step 2 |
| HB host tests | dream 43, features 30, fit 221, power 15, uires 120 / 12: all pass |
| `check_hal.py --device goat_elegant --dex` | OK, 143 calls on 10 interfaces |
| `check_forks.py --all` | green on all three devices |
| Z9X | no file of a Z9X gate input was touched in steps 1 to 3 (see below) |

Later edits of `apps/HbProjector/build.sh` and `test/power/run.sh` change the tree sha256 (both files are in the
tree), never the APK.

## What is not green, and why (not caused by these steps)

- **Resolved 2026-10-30 23:3x** (owner: "fix what needs fixing"): the Z9X gate was red only on the finished
  one-visit Google install (12 inputs: `tools/lumen_v1.sh` and `tools/ota/**`, among them
  `tools/ota/build_erofs_utils.sh`); re-recorded with `z9x_gate.sh --full --record` (16 of 16 pins rebuilt
  identical, old record kept as `build/play6/z9x_inputs.ok.20261030-232321`). The 2 publish REVIEW rows got rules:
  `docs/fit_screen/RESEARCH.md` and `docs/fit_screen/stock_cfg/` (copies of XGIMI config files) EXCLUDE; the
  erofs-utils programs under `installer/tools/` stay out of git (release asset). The HB pins of `z9x_gapps.rc` and
  `z9x_gapps.sh` in `compose_hb.sh` now name the new files. Then `record.sh --reason "one-visit Google install
  (erofs) finished 2026-10-30, owner OK"` (old baseline: `devices/_baseline/mac.20261030-233311`) and
  `prove_unchanged.sh` OK; `check.sh all` 25 ok, 0 red.
- `devices/goat_elegant/image/compare_hal_api.sh` is red: the HB clients lack public members the Z9X test3 work added
  (`getZoomLimitStep`, `setOpticalZoomLimitStep`, `resetKeystoneCorners`, ...) and differ in a few others. The tree it
  builds is byte-identical to the one before step 3, so this was red before; it is HB porting work, not part of
  these steps.

## Not done in these steps (by the plan or on purpose)

- The Play 6 is not switched (plan step 4); its recipe stays in compare mode.
- `tools/play6/check_forks.py` (check F, the Play 6 build gate) is unchanged: the assembler applies the shadow rule
  with the one fork-table format of both devices, but check F's own rules (key set, WIRE_SIZE, overlay config,
  resource placement) were not moved into it. `check_forks.py` still reports a drifted origin as red, so
  `check.sh` is red on a drift too; only the assembler step itself warns (red with `--release`).
- HB device smoke test before the next HB image (boot, quick panel, keystone, picture): owner decision 1, not run
  here (no device writes in this workflow).
