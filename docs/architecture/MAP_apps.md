# Map of the projector apps (2026-10-30)

Scope: `apps/Z9xProjector`, `apps/HbProjector`, `apps/Play6Projector`, `apps/common`, `apps/build_apk.sh`,
`apps/common/sync.sh`, the fork tables and their checks. Read-only survey: nothing was changed. Other work was
editing `apps/` while this was measured, so the numbers are a snapshot taken on 2026-10-30.

All paths are relative to `gsi/`. Line counts include comments and blank lines. Build output dirs (`build/`, `out/`)
and `.apk` files are left out.

## 1. Short answer

- There are three copies of one app (`org.lumen.projector`), one per device. Nothing is shared at build time:
  every APK carries its own copy of every file (`apps/common/sync.sh:3-4`).
- Two copy mechanisms exist side by side and do the same job:
  1. `apps/common/projector/FILES`: 230 files (49 Java, 9,930 lines; 181 resources, 6,609 lines) copied into all
     three apps by `sync.sh ... projector`.
  2. The HB `same:` rows of `devices/goat_elegant/forks.tsv`: 273 more files (64 Java, 19,403 lines; 209 other,
     6,225 lines) that are byte-identical copies of Z9X files, re-copied by
     `devices/goat_elegant/image/refresh_same.sh`.
- Real per-device forks: HB 19 files (18 tracked plus 1 untracked), Play 6 23 files that differ from the Z9X file
  of the same path (26 tracked rows in `tools/play6/forks.tsv`, some renamed, for example `Play6RotatePanel`).
- Right now `python3 tools/devices/check_forks.py --all` is red: Play 6 7 origin rows plus 1 resource error, HB 4
  origin rows. Every Z9X change to a forked file turns the other devices red; this has happened in every recorded
  round since 1.0.8 (section 6).
- What truly differs per device is small and well defined (section 7): the HAL transport and code table, the
  feature table, a handful of stock behaviours, device-only features, identity guards, manifest and strings.

## 2. Module layout

All three apps use the same Java package root `org.lumen.projector` and the same sub-packages.

| App | Files | Java files | Java lines | Sub-packages under `src/org/lumen/projector/` |
|---|---|---|---|---|
| Z9xProjector | 558 | 161 | 45,316 | ak audio cec display dream eye fit game guide hal home mem panel power recents remote report setup source sys ui voice |
| HbProjector | 593 | 166 | 44,264 | the Z9X list without `report`, plus `device` |
| Play6Projector | 330 | 109 | 28,599 | own layout with many `Play6*` classes, plus `battery`, `speaker`, `lens`, `ambient`, `diag`, and `org.lumen.play6` |

Composition of each app (files compared with the Z9X file of the same path):

| | in common FILES | identical to Z9X (not in FILES) | differs from Z9X | only in this app |
|---|---|---|---|---|
| Z9xProjector | 230 files, 16,539 lines | origin | origin | 328 own files, 43,522 lines |
| HbProjector | 230 / 16,539 | 291 / 28,343 | 19 / 10,674 | 53 / 2,789 |
| Play6Projector | 230 / 16,539 | 3 / 480 | 23 / 7,667 | 74 / 16,577 |

Reading: about 90 percent of the HB app is a copy of the Z9X app. The Play 6 app shares the common set and is
otherwise its own code (Play 6 was ported earlier and more of it was rewritten).

## 3. Shared vs copied

### 3.1 apps/common

| Item | Source | Copied to | Mechanism |
|---|---|---|---|
| tokens | `apps/common/tokens.xml` | `res/values/z9x_tokens.xml` | `sync.sh tokens` |
| geoip | `apps/common/GeoIp.java` | `src/org/lumen/common/GeoIp.java` | `sync.sh geoip` |
| migrate | `apps/common/LumenImport.java` | `src/org/lumen/common/LumenImport.java` | `sync.sh migrate` |
| hal | `apps/common/hal/HidlCaller.java` | `src/org/lumen/projector/hal/HidlCaller.java` | `sync.sh hal` (`apps/common/sync.sh:65`) |
| projector | 230 paths in `apps/common/projector/FILES` | same path in the app | `sync.sh projector` |
| keys | `apps/common/overlay/xml/global_keys.xml`, `overlay/layout/shutdown_dialog.xml` | framework key RRO | `sync.sh keys` |
| brand | `apps/common/brand/drawable` (16 files) | not in sync.sh | by hand |

Rules (`apps/common/projector/README.txt:10-20`): the shared files are owned by the Z9X app; a need of another
device alone never edits a shared file, that device forks the file instead; copy mode runs by hand, builds run
`--check` only. `sync.sh` refuses any path under `src/org/lumen/projector/hal/` (`apps/common/sync.sh:44`).

What the 230 common files are: `home`, `mem`, `recents`, `remote`, `ui` (17 files), `voice` (7), part of `power`,
`source`, `panel`, `sys`, plus their strings in 17 locales and 16 drawables. The core projector logic (keystone,
focus, AK, picture, sound, dream, standby, CEC, game) is not in the common set; it lives in the Z9X app and is
copied to the HB through `same:` rows.

`sync.sh --check` today: Z9xProjector and Play6Projector clean for `hal projector`; HbProjector clean for
`projector`, and `hal` reports a missing `HidlCaller.java`, which is expected because the HB is AIDL.
`apps/HbProjector/build.sh` only checks `projector`.

### 3.2 Fork tables

The registry key `forks=` in `devices/<codename>/device.conf:24` names each device's table:

| Device | Table | Rows | Of which `apps/*Projector` |
|---|---|---|---|
| horse_popular (Z9X) | none (origin only) | 0 | 0 |
| goat_elegant (HB) | `devices/goat_elegant/forks.tsv` | 295 (291 under `apps/`) | 273 `same:` + 18 fork rows |
| dragon_healthy (Play 6) | `tools/play6/forks.tsv` | 51 | 26 (the rest: Play6Updater 18, overlays, tools) |

`tools/devices/check_forks.py` checks each row: copy and origin exist, origin sha256 equals the recorded one; for
`same:` rows also the copy's sha256 (`tools/devices/check_forks.py:54-72`). For Play 6 it then runs
`tools/play6/check_forks.py` unchanged (`tools/devices/check_forks.py:33`), which adds resource coverage
(`FILES` or `tools/play6/res_not_shared.txt`), key codes and overlay config checks.

HB fork rows (not `same:`): `AndroidManifest.xml`, `App`, `HalController`, `SettingsActivity`, `audio/SoundEq`,
`audio/SpdifOff`, `panel/ProPicture`, `panel/QuickPanelController`, `panel/ManualKeystonePanel`,
`panel/OpticalZoomPanel`, `dream/DreamLamp`, `power/TimerLamp`, `power/StandbyActivity`, `eye/EyeGuard`,
`setup/SetupBridgeProvider`, `hal/GmpfClient`, `hal/Gmpf2Client`, `hal/KstPoint`.

Untracked fork found: `apps/HbProjector/src/org/lumen/projector/fit/FitDevices.java` differs from the Z9X file
(it returns `HbFitDevice` instead of `Z9xFitDevice`, 2 code lines) but has no row in
`devices/goat_elegant/forks.tsv`. A Z9X change to that file would not turn anything red.

`BootReceiver.java` and `panel/SoundProfiles.java` were listed as forks in the S5 plan
(`devices/goat_elegant/PROJECTOR_DESIGN.md:747`) and are now `same:` rows
(`devices/goat_elegant/forks.tsv:274-275`).

### 3.3 Size of the duplication

| What | Files | Lines | Copies on disk |
|---|---|---|---|
| common FILES | 230 | 16,539 | 4 (common + 3 apps) |
| HB `same:` rows | 273 | 25,628 | 2 |
| HB forks (whole files) | 19 | 10,674 | 2, with 4,654 diff lines between them |
| Play 6 files that differ from Z9X | 23 | 7,667 | 2, with 8,548 diff lines between them |

About 42,000 lines exist only to be kept equal to another file (common plus `same:`). Of the 19 HB forks, 7 differ
by 30 diff lines or fewer: `fit/FitDevices` (10), `power/TimerLamp` (6), `eye/EyeGuard` (7), `audio/SoundEq` (17),
`dream/DreamLamp` (19), `audio/SpdifOff` (25), `setup/SetupBridgeProvider` (25). These are whole-file copies made
to change one condition or one constructor call.

## 4. HAL layers

| Layer | Z9X | HB | Play 6 |
|---|---|---|---|
| Transport | `hal/HidlCaller.java` (common copy, 229 lines): HIDL `xgimi.hardware.gmpf@1.0`, two-way, Status first (`apps/common/hal/HidlCaller.java:2-13`) | `hal/AidlCaller.java` (715 lines, HB only): raw Binder to 10 of 14 stable AIDL services, hard allowlist `enum Call` (`apps/HbProjector/src/org/lumen/projector/hal/AidlCaller.java:2-13`, `:85`) | `hal/HidlCaller.java` (same common copy) |
| Typed client 1 | `hal/GmpfClient.java` (1,560 lines, IGmpf code table, `apps/Z9xProjector/src/org/lumen/projector/hal/GmpfClient.java:2-13`) | `hal/GmpfClient.java` (1,185 lines): same public members and signatures as Z9X, body calls AIDL; missing features throw `NotOnHbException` (`apps/HbProjector/src/org/lumen/projector/hal/GmpfClient.java:2-13`) | `hal/Play6Gmpf.java` (910 lines): own name and own API (`apps/Play6Projector/src/org/lumen/projector/hal/Play6Gmpf.java:9-18`) |
| Typed client 2 | `hal/Gmpf2Client.java` (470 lines, IGmpf2) | `hal/Gmpf2Client.java` (294 lines): same signatures, IGmpf2 methods spread over 4 AIDL services | `hal/Play6Gmpf2.java` (458 lines) |
| Other | `hal/KstPoint.java` | `hal/KstPoint.java` (fork), `hal/HbFeatures.java` | `Play6KstPoint`, `Play6FocusCallback`, `Play6MusicPlayerCallback`, `Play6PowerCallback` |
| Facade | `Hal.java` (299 lines): threads `z9x-hal`, `z9x-ak`, `z9x-eye` (`apps/Z9xProjector/src/org/lumen/projector/Hal.java:11-22`) | `Hal.java` `same:` copy | `Hal.java` fork (767 lines, 844 diff lines) |
| Owner | `HalController.java` (866 lines, `apps/Z9xProjector/src/org/lumen/projector/HalController.java:29-40`) | `HalController.java` fork (992 lines, 564 diff lines) | none by that name; `Play6App.java` (421 lines) |

The HB approach (same class names and signatures, different body) is what lets 273 files stay byte-identical: the
panels compile against `GmpfClient` without knowing the transport. Play 6 renamed its clients, so every caller was
changed and stayed a fork.

Gates: `apps/build_apk.sh:119-122` runs `apps/check_hal_callers.py` when `hal/GmpfClient.java` exists (Z9X, HB).
The HB build also runs `tools/devices/check_hal.py --device goat_elegant --dex` (AIDL table check, 141 calls per
`devices/goat_elegant/image/T8B_NOTES.md:81`). The device registry names the HAL app and tables
(`devices/goat_elegant/device.conf`: `hal_check`, `hal_app`, `hal_codes`, `hal_allowed`, `hal_banned`).

## 5. Feature flags

- HB: `apps/HbProjector/src/org/lumen/projector/hal/HbFeatures.java` (198 lines): 30 features, 12 ON, 10 OFF,
  8 PENDING; read once from stock `feature.xml`, a stock "off" wins; `HbFeatures.on(F)` at line 149. Used 77 times
  in 14 HB source files. The rule "never show a disabled row" is written at line 12. Note: its log tag is still
  `"Z9xFeatures"` (line 30).
- Z9X: there is no `Z9xFeatures` class. Support is decided by constants inside device classes, for example
  `Z9xFitDevice.SUPPORTED = false` (`apps/Z9xProjector/src/org/lumen/projector/fit/Z9xFitDevice.java:30`), and
  by Z9X-only code paths.
- Play 6: no feature table either. `Device.isPlay6()` checks three properties (model G0058, platform mt5889,
  `ro.lumen.device=play6`) and guards every HAL module (`apps/Play6Projector/src/org/lumen/projector/Device.java:10-28`).
- The adapter pattern `FitDevices.get()` / `FitDevice` (Z9X `Z9xFitDevice`, HB `HbFitDevice`) is the only place
  where a device choice is made behind a small interface instead of a forked file.

## 6. How often forks went red

| When | Device | What | Source |
|---|---|---|---|
| 2026-10-29 (hb-t6) | HB | 7 rows red after Z9X 1.0.8 parity; build.sh stopped at check_forks, APK built step by step; fixed in hb-t6 (4 re-copies, ProPicture and QuickPanelController ported) | `devices/goat_elegant/RESULTS_hb-t6.md:114-121`, `:139` |
| hb-t7 | Play 6 | 6 check_forks errors after the same Z9X work, `prove_unchanged.sh` red | `devices/goat_elegant/RESULTS_hb-t7.md:36-42` |
| hb-t8b | Play 6 | 7 origin rows red, left alone | `devices/goat_elegant/image/T8B_NOTES.md:83` |
| 2026-10-30 Z9X parity | Play 6 and HB | Play 6: Hal, StandbyController, ManualKeystonePanel, MotorController, ManualFocusActivity, AudioOutputChoice, then RotatePanel; HB: GameProfile, OpticalZoomPanel, HalController, ProPicture, QuickPanelController, GmpfClient, Gmpf2Client; `build_play6.sh` refuses | `docs/z9x/parity/README.md:70`, `:84-88` |
| 2026-10-30, this survey | Play 6 | 7 origin rows (Hal, StandbyController, ManualKeystonePanel, MotorController, ManualFocusActivity, RotatePanel, AudioOutputChoice) plus `res/values/strings_fit.xml` neither in FILES nor in `res_not_shared.txt` | `check_forks.py --all` output |
| 2026-10-30, this survey | HB | 4 origin rows: OpticalZoomPanel, QuickPanelController, GmpfClient, Gmpf2Client | `check_forks.py --all` output |

The saved baselines (`devices/_baseline/mac*/check_forks.txt`) are all green; they were recorded right after a
sync. Pattern: every Z9X parity round turns both other devices red, the Play 6 rows have stayed red across at
least three rounds, and the HB rows are fixed only inside an HB test session. The rule "fixed on the other
device's side, never blocks Z9X" (`apps/common/projector/README.txt:13-16`) works for Z9X safety but leaves the
other devices permanently behind.

## 7. What truly differs per device

| Kind | Z9X | HB | Play 6 |
|---|---|---|---|
| HAL transport | HIDL 64-bit | stable AIDL 32-bit | HIDL 32-bit |
| Code table | IGmpf/IGmpf2 TSVs | `aidl_codes.tsv`, `allowed.tsv` | `gmpf_codes_play6.tsv` |
| Features present | full Z9X set | 30-row `HbFeatures` (no 3D, DV, Ultra 120, DTS) | no table; battery, speaker mode, lens, ambient light exist only here |
| Stock behaviour | reference | keystone reset path (38/84/85), zoom dial limits, eye-guard gear key, wake-by-OK focus hold, no lamp dim with dynamic black | own keystone, zoom/shift, rotate, snapshot, standby, power key |
| Device-only code | `Z9xFitDevice`, `report/` | `CurtainAlignOverlay`, `HbFitDevice`, `HbSleepTimerPanel`, `HbPanelRequestReceiver`, `device/DeviceStateProvider` | `battery/*`, `speaker/*`, `lens/*`, `ambient/*`, `BootHandshake`, `Play6PanelController` (1,560 lines) |
| Identity | none | registry and signing key | `Device.isPlay6()` |
| Manifest | origin | fork (82 diff lines) | fork (379 diff lines) |
| Strings | `strings_*.xml` in Z9X and common | `strings_hb.xml` (17 locales), `strings_hb_fit.xml` (only 12 locales: missing ar, be, kk, pt-rBR, tr, uk) | `strings_p6.xml` (17 locales) |

Most of the 19 HB forks change behaviour that could be expressed as a device question ("does this device dim the
lamp with dynamic black on", "which keystone reset", "which fit device"), not as different code.

## 8. Strings and i18n

- `apps/i18n/gen.py` generates locale files for the targets `projector` (Z9xProjector), `tvinput`, `airplay`,
  `home`, `setup`, `updater` and `common` (`apps/i18n/gen.py:28-34`). There is no HB or Play 6 target.
- HB strings (`strings_hb.xml`, `strings_hb_fit.xml`) are hand-written per locale and are not checked by gen.py.
- Play 6 has its own source set `apps/Play6Projector/i18n/langs/*.txt` (16 files) and its own generator
  `tools/play6/gen_play6_i18n.py`.
- The Z9X `projector` strings reach the HB as `same:` copies (for example `res/values-*/strings_kst.xml`), so the
  HB has 17 locale dirs of copied XML on top of the common copies.

## 9. Tests

| Where | Tests | Runner |
|---|---|---|
| `apps/Z9xProjector/test` | `fit/FitTest`, `uires/UiResTest`, `uires/AkUnitsTest`, `dream/DreamDefaultsTest` | one `run.sh` per dir |
| `apps/HbProjector/test` | the same four files, byte-identical (5 `test/` rows in forks.tsv), plus `features/HbFeaturesTest`, `power/StrGateTest` with hand-written Android stubs | `apps/HbProjector/build.sh` runs every `test/*/run.sh` |
| `apps/common/projector/test` | `voice/VoiceTest` | `run_voice_test.sh` |
| `apps/Play6Projector` | none | none |

Plain-Java host tests with stubs; no JUnit, no shared test harness. The Z9X tests are not run by `build_apk.sh`;
only the HB `build.sh` runs its tests. The Z9X gate (`tools/play6/z9x_gate.sh:203`, `:216`) runs `sync --check` and
`check_hal_callers` for the Z9X app.

## 10. Build entry points

| Entry | Does |
|---|---|
| `apps/build_apk.sh APP_DIR` | Gradle-free aapt2, javac (Android `java.base` system image), d8, zipalign, apksigner; checks `global_keys.xml` and HAL callers; no per-device knowledge |
| `apps/HbProjector/build.sh` | `sync --check projector`, `check_forks --device goat_elegant`, build with a throwaway key, `check_hal --dex`, host tests |
| `tools/devices/build_apps.sh CODENAME` | builds the registry's `apps=` list with the device platform key, verifies the signer (`tools/devices/build_apps.sh:2-15`) |
| `tools/play6/build_play6.sh` | Play 6 build, stops on check F (its fork check) |

Each device has a different developer loop: Z9X has no app-level `build.sh`, HB has one, Play 6 uses its own tools
directory.

## 11. Friction points, as facts

1. One concept, two copy systems (common FILES and HB `same:` rows) with two refresh commands (`sync.sh`,
   `refresh_same.sh`) and two checks.
2. 273 HB files are byte-identical to Z9X but not in common FILES, so a Z9X edit to any of them turns the HB red
   until someone runs `refresh_same.sh`.
3. Whole-file forks for small differences: 7 HB forks differ by 30 diff lines or fewer.
4. One fork is untracked (`fit/FitDevices.java`), so drift there is silent.
5. Play 6 renamed its HAL clients, so its callers cannot be shared; HB kept the Z9X names and shares 90 percent.
6. Feature support is a table on HB, constants on Z9X, an identity guard on Play 6.
7. Strings have three generators or none: gen.py (Z9X, common), `gen_play6_i18n.py` (Play 6), hand-written (HB,
   with 6 locales missing in `strings_hb_fit.xml`).
8. Tests: the HB copies the Z9X tests; Play 6 has none; nothing runs all apps' tests together.
9. Fork checks are red today on two devices, and the Play 6 rows have been red through several rounds.
