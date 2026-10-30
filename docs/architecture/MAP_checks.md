# Map: safety gates and checks (2026-10-30)

Read-only survey of the checks that guard Lumen OS while several projectors share one tree: what each one
protects, what it reads, who runs it, what it costs, why it goes red for reasons that have nothing to do with the
change at hand, and how it would have to change if the projector code moved to a shared core. Facts with
`file:line` citations (paths relative to `gsi/`). Section 7 is the only part that proposes anything. Nothing was
edited to write this; the check runs quoted in section 5 were read-only runs made on 2026-10-30 evening while
other workflows were editing `apps/`.

Devices: horse_popular = Z9X (64-bit, HIDL, released 1.0.x), dragon_healthy = Play 6 (32-bit, HIDL),
goat_elegant = Z9X HB (32-bit, stable AIDL gmpf).

## 1. Summary

- There are nine guards, written at different times for different devices:
  `tools/play6/z9x_gate.sh` (check G), `tools/devices/prove_unchanged.sh` (the proof bundle) with
  `devices/_baseline/record_mac.sh`, two fork checkers (`tools/play6/check_forks.py` = check F, and the generic
  `tools/devices/check_forks.py`), three HAL checkers behind one dispatcher (`apps/check_hal_callers.py`,
  `tools/play6/check_play6_hal.py`, `tools/devices/check_aidl_hal.py`, dispatched by `tools/devices/check_hal.py`),
  `apps/common/sync.sh --check`, and the publish pair `docs/repo/publish_audit.py` + `docs/repo/export_repo.py`.
- The strongest guard (the Z9X gate) protects the owner rule "Z9X stays byte-identical" by hashing every Z9X input,
  and it defines "Z9X input" by folder: `apps/Z9x*`, `apps/common`, `apps/i18n`, `apps/sdk`, `apps/LumenBridge`,
  `tools/ota`, `tools/lumen`, `tools/sign` and more (`tools/play6/z9x_gate.sh:57-72`). So the code that should be
  shared between projectors is exactly the code the gate freezes. This one fact explains most of the pain below.
- Because of that, the other devices copy instead of share: the HB keeps about 308 byte-identical `same:` copies
  of Z9X files plus 23 real forks (`devices/goat_elegant/forks.tsv`, 343 lines at the time of reading and growing),
  the Play 6 keeps 51 fork rows (`tools/play6/forks.tsv`), and both also receive 230 synced files through
  `apps/common/projector/FILES`. The HB design says so in plain words: "`apps/i18n` is a gate input, so HB copies
  them rather than adding a target" (`devices/goat_elegant/PROJECTOR_DESIGN.md:616`).
- Every Z9X edit therefore turns two other devices red (fork origin sha256 changed), and every intentional Z9X
  change needs a manual gate re-record plus a manual baseline refresh. On 2026-10-30 alone the gate was re-recorded
  five times (four dated backups `build/play6/z9x_inputs.ok.20261030-*` plus the current record of 18:21:34), and
  `overlay/apps_v1/Z9xProjector.apk` was re-pinned four times the same day (`overlay/apps_v1/README.txt`).
- The proof bundle and two tests compare exact counts and texts ("16 of 16", "151 transacting methods",
  "141 calls"), so they also go red on legitimate growth. At the time of writing `prove_unchanged.sh` would be red
  on at least six lines and `check_aidl_hal_test.sh` is red on two (section 5).
- The HB is not covered by any aggregate: `prove_unchanged.sh` and `record_mac.sh` run only Z9X and Play 6 checks;
  the HB checks exist (`check_forks.py --device goat_elegant`, `check_hal.py --device goat_elegant`,
  `build_apps.sh`) but nothing runs them together.

## 2. Inventory

| Guard | Protects | Reads | Run by | Cost | Writes |
|---|---|---|---|---|---|
| `tools/play6/z9x_gate.sh` (check G) | Z9X APK pins rebuild byte for byte; no Z9X image input changed since the recorded green run | ~1971 files (`build/play6/z9x_gate.log` "inputs: 1971 files") | `tools/play6/build_test.sh:184`, `devices/_baseline/record_mac.sh:27`, by hand | incremental by default, `--full` rebuilds all 16 pins | `build/play6/z9x_gate.log`; `build/play6/z9x_inputs.ok` only with `--record` (`:230-233`) |
| `tools/devices/prove_unchanged.sh` | Z9X and Play 6 outputs equal `devices/_baseline/mac/` | everything `record_mac.sh` records, plus 5 installer mocks | by hand after every structural step (`devices/DESIGN.md:32-57`) | "about 5 minutes on the Mac" (`:7`) | scratch dir only (`:11-12`) |
| `devices/_baseline/record_mac.sh` | records the baseline | gate, Play 6 builds (twice), installer goldens, checks, publish audit | `prove_unchanged.sh:35`, by hand to refresh | "about 4 min" (`BASELINE.md:28`) | the OUT dir (`:17-18`) |
| `tools/play6/check_forks.py` (check F) | Play 6 copies of Z9X files do not drift; every Z9X projector resource is placed | `tools/play6/forks.tsv`, `apps/common/projector/FILES`, `tools/play6/res_not_shared.txt` | `tools/play6/build_play6.sh:37` (stops the build), `record_mac.sh:55`, `tools/devices/check_forks.py:33` | seconds | nothing |
| `tools/devices/check_forks.py` | the same for any device with a `forks=` table; `same:` rows must also be byte-identical | `devices/<c>/device.conf` `forks` key | by hand; `refresh_same.sh` tells you to (`devices/goat_elegant/image/refresh_same.sh:8`) | seconds | nothing |
| `apps/check_hal_callers.py` | Z9X HAL clients: no transacting method without a UI caller, no banned code | `hal/GmpfClient.java`, `hal/Gmpf2Client.java` (`:27`) | z9x_gate (`z9x_gate.sh:216`), `check_play6_hal.py` rule 6, `check_hal.py` (`:28-29`) | seconds | nothing |
| `tools/play6/check_play6_hal.py` (check H) | Play 6 HAL clients (1071 lines of Play 6 rules) | `apps/Play6Projector` | `build_play6.sh:35`, `check_hal.py:30-31` | seconds | nothing |
| `tools/devices/check_aidl_hal.py` | HB stable AIDL transport: one transact site, every Call in the allowed table, nothing banned, optional dex scan | `--codes`, `--banned`, `--allowed` TSVs from `device.conf` | `check_hal.py:32-36`, `tools/devices/build_apps.sh:53-55` (on the APK) | seconds | nothing |
| `apps/common/sync.sh --check` | synced copies equal `apps/common` | `apps/common/projector/FILES`, `hal/`, `overlay/` | `z9x_gate.sh:197-211`, `build_test.sh` step S, `record_mac.sh:58-64` | seconds | nothing in check mode |
| `docs/repo/publish_audit.py` | every tree file has a publish rule; nothing private or binary is published | the whole tree | `build_test.sh:206-212` (check P), `export_repo.py:70`, `record_mac.sh:66` | seconds | only the manifest path given (`:5-6`) |
| `docs/repo/export_repo.py` | the public repo gets only PUBLISH files, scrubbed | `docs/repo/PUBLISH_MANIFEST.tsv`, `~/.config/lumen/personal_patterns` | the owner, by hand | seconds | TARGET_DIR (copies and deletes, `:129-141`) |

Related but not in the task list: `tools/devices/check_device_signers.py` (rule S1, no image carries a certificate
another projector trusts, `:2-27`), `tools/devices/devices.py check-wire` (the OTA ids hard-coded in
`tools/ota/make_ota.py` and `make_manifest.py` agree with the registry, `tools/devices/devices.py:92-97`), and the
tests in `tools/devices/test/`.

## 3. What each guard does, in detail

### 3.1 The Z9X gate, `tools/play6/z9x_gate.sh`

- Inputs are listed by folder, not by what the Z9X image actually contains: every `apps/Z9x*/` app (manifest,
  `src res assets lib stub jni`), then `apps/common apps/i18n apps/sdk apps/LumenBridge overlay/v1 overlay/apps_v1
  overlay/debug overlay/v65 tools/lumen tools/sign tools/ota`, then five single files (`:57-72`). The header comment
  (`:19-23`) does not mention `apps/LumenBridge`; the code (`:66`) and `devices/DESIGN.md:12-15` do.
- With a record present it diffs the input hashes and decides per changed path (`:91-127`):
  - `apps/common/*`, `apps/i18n/*`, `apps/sdk/*`, `apps/build_apk.sh`, the two check scripts: rebuild every pin
    (`:101-102`). Green only if all 16 pins still come out identical.
  - a changed pin in `overlay/apps_v1`: rebuilt for the report and always RED "the owner confirms the new pin,
    then re-record" (`:103-107`); the pin list itself: RED (`:108-110`).
  - `apps/Z9x*/jni/*` of a pinned app: RED, no rebuild can prove it (`:114-118`).
  - an `apps/Z9x*` app that is not pinned (for example `apps/Z9xBtPair`, which the HB builds): ignored (`:119-122`).
  - anything else (overlay, `tools/ota`, `tools/lumen`, `tools/sign`, `tools/lumen_v1.sh`, `tools/compose_tv.py`):
    RED "image input changed, no rebuild can prove it" (`:123-124`).
- Each pin is rebuilt in a scratch dir with the version read from the pin (`:161-176`) and compared by sha256; on a
  mismatch it says whether the code or only resources/signature moved (`:184-191`). Lumen Home needs the private
  TMDB key file to rebuild identically (`:167-173`).
- Then the synced-copy checks for both Z9X and Play 6 projector apps and key overlays (`:197-211`), the i18n check
  (`:214-215`) and `check_hal_callers.py` on the Z9X (`:216-217`), then a second hash pass to catch edits made during
  the run (`:220-224`).
- RED never edits or records anything (`:15-18`, `:226-229`). Re-recording is `--full --record` after the owner's
  OK, keeping the old record under a dated name (`:10-13`).
- Callers: `tools/play6/build_test.sh:184-198` stops every Play 6 build on any RED, except "rebuilt X, pinned Y"
  lines for apps given as `--home-src`/`--tvinput-src`/`--setup-src` or the escape hatch `--z9x-ahead=APP`
  (`build_test.sh:186-194`), which exists because shared `apps/common` changes move a Z9X app ahead of its pin.

### 3.2 The proof bundle, `tools/devices/prove_unchanged.sh` + `devices/_baseline/`

- Runs `record_mac.sh` into a scratch dir (`:35`) and then checks seven things (`:38-79`):
  1. the gate output contains `GREEN`, `exit 0`, `green run (...): 0` and the literal `rebuilt: 16 of 16 planned`
     (`:40-41`), and `build/play6/z9x_inputs.ok` is byte-equal to `devices/_baseline/mac/z9x_inputs.ok`, that is
     "nobody re-recorded" (`:42-43`); then the full gate text is compared after date/scratch normalisation (`:44`);
  2. `z9x_apks.sha256`, `play6_apks.sha256`, `play6_pinned.tsv`, `overlay_inputs.sha256` byte for byte (`:47-49`);
  3. `scripts.sha256` except rows named in `devices/_baseline/expected_changes.txt` (`:52-58`; that file holds only
     its header line today);
  4. the outputs of the installer goldens, `check_forks.txt`, `check_play6_hal.txt`, `check_hal_callers.txt`,
     `sync_check.txt` as normalised text (`:61-63`), so any change of a count in their output is a FAIL;
  5. five installer mocks against `mac2/` (`:66-70`);
  6. the Play 6 compose inputs and planned operations (`:73-75`);
  7. publish audit exit 0 and no REVIEW row (`:78-79`).
- `record_mac.sh` builds the Play 6 APKs twice to prove repeatability (`:33-38`) and hashes overlay and script inputs
  of both devices (`:43-49`). It has no HB step. The HB part of the baseline is a static copy of builder outputs
  (`devices/_baseline/hb/`, `BASELINE.md:177-188`), compared by nothing.
- Refresh is manual and partial: the 2026-10-30 refresh rewrote only the files the Z9X parity fixes moved and left
  `check_forks.txt`, `play6_apks.sha256`, `play6_compose/` stale "so `tools/devices/prove_unchanged.sh` stays red
  there until the Play 6 lane acts" (`devices/_baseline/BASELINE.md:8-23`). Old copies are kept as
  `devices/_baseline/mac.20261029-180800/` and `mac.20261030-025054/`.

### 3.3 Fork checks

- `tools/play6/check_forks.py` (check F): every `forks.tsv` row's origin sha256 must equal the recorded one; extra
  semantic checks for `KeyReceiver.java` key sets, `WIRE_SIZE` 326, the overlay config items and the switch fix
  (`:7-17`, `:69-98`); every file under `apps/Z9xProjector/res` must be in `FILES` or `res_not_shared.txt`
  (`:18-20`, `:164`); a Play 6 file at the same path as a Z9X file must be synced or a fork row (`:21-22`,
  `:178-189`). `build_play6.sh:37` stops the Play 6 build when it is red.
- `tools/devices/check_forks.py`: generic row check for every device with `forks=` (`:9-14`, `:40-74`), `same:` rows
  also compare the copy (`:69-73`); then it re-runs the device's own checker, today only Play 6 (`OWN_CHECK`, `:33`,
  `:93-99`). So for the Play 6 every drifted row is reported twice, once by each part (seen in the run of
  section 5).
- HB maintenance helper: `devices/goat_elegant/image/refresh_same.sh` re-copies changed `same:` rows and writes the
  new sha256 (`:1-9`); ordinary fork rows are "reviewed and ported by hand" (`PROJECTOR_DESIGN.md:620-621`). The
  Play 6 has no such helper.
- Three kinds of file on the HB side (`devices/goat_elegant/PROJECTOR_DESIGN.md:604-621`): synced (230 FILES
  entries, `sync.sh --check apps/HbProjector projector` passes), `same:` copies, and real forks.

### 3.4 HAL checks

- `tools/devices/check_hal.py` dispatches on `hal_check` in `device.conf` and passes output through unchanged
  (`:7-13`, `:25-36`): `z9x-callers` (Z9X), `play6-hidl` (Play 6), `aidl-table` (HB).
- `apps/check_hal_callers.py`: works on two fixed file names, `GmpfClient.java` and `Gmpf2Client.java` (`:27`); the
  banned code list is a Python dict in the script (`:29-43`). The script is itself a Z9X gate input
  (`z9x_gate.sh:68`), so adding a banned code or a rule for another device re-records the Z9X gate.
- `tools/play6/check_play6_hal.py`: 1071 lines of Play 6 rules; its rule 6 runs `check_hal_callers` on the Play 6
  clients too (run output, section 5).
- `tools/devices/check_aidl_hal.py`: fail-closed, table-driven: one transport file, every `Call` equal to a row of the
  allowed table, nothing in the banned/REVIEW table, no integer codes, optional dex scan (`:1-31`). It imports
  `javascan` from `tools/play6/` (`:40-41`). This is the only HAL checker whose rules live in data files
  (`devices/goat_elegant/research/{aidl_codes,banned,allowed}.tsv`, `device.conf:27-29`).

### 3.5 Publish pair

- `publish_audit.py` classifies every file: PRE_RULES, then COLLAPSE folders, then RULES, first match wins; no match
  is REVIEW (`:28-29`, `:268-290`). App rules are by name prefix: `^apps/Hb...` EXCLUDE (`:210`), then
  `^apps/((?:Z9x|Play6|Lumen)...)/` PUBLISH (`:227`). `devices/` is EXCLUDE except the two public registry files
  (`:59-65`). `apps/Z9xBtPair/` is EXCLUDE as "obsolete helper" (`:109`), while the HB lists it as one of its apps
  (`devices/goat_elegant/device.conf:30-32`).
- A REVIEW row stops the Play 6 build (`build_test.sh:206-212`) and the export before anything is copied or deleted
  (`export_repo.py:7-10`, `:74-79`).
- `export_repo.py` copies PUBLISH files, scrubs personal identifiers with generic rules plus a local, never published
  pattern file (`:11-17`, `:44-57`), and deletes files no longer published (`:129-141`). It does not know about
  devices at all; device privacy is decided only by the audit rules.

## 4. What each guard protects well

- The gate is the only thing that proves a released Z9X APK still comes from today's sources. Its "never edit,
  never re-record on red" design (`z9x_gate.sh:15-18`) has kept the Z9X release path honest through the
  org.z9x to org.lumen rename and the 1.0.8 bridges (`BASELINE.md:3-5`).
- Fork tables make every copy visible: nothing is copied silently, and a changed origin always reaches the device
  that copied it.
- The AIDL checker is the best model in the tree: generic code, device data in TSV files, fail-closed, with a dex
  scan after the build (`check_aidl_hal.py:7-31`, `build_apps.sh:53-55`).
- The publish audit makes "private by default" the rule for a new device: everything under `devices/` and
  `apps/Hb*` is EXCLUDE until the owner opens it (`publish_audit.py:65`, `:210`).

## 5. State at the time of writing (2026-10-30 evening, read-only runs)

- `python3 -B tools/devices/check_forks.py --all`: dragon_healthy FAILED (7 origins changed: `Hal.java`,
  `StandbyController.java`, `ManualKeystonePanel.java`, `MotorController.java`, `ManualFocusActivity.java`,
  `RotatePanel.java`, `AudioOutputChoice.java`, each printed twice; plus `res/values/strings_fit.xml` "neither in
  FILES nor in res_not_shared.txt"); goat_elegant FAILED (4 origins changed: `OpticalZoomPanel.java`,
  `QuickPanelController.java`, `hal/GmpfClient.java`, `hal/Gmpf2Client.java`); horse_popular "no forks". The cause in
  every case is Z9X work of the same day. With check F red, `build_play6.sh:37` refuses to build any Play 6 APK.
- `python3 -B tools/devices/check_hal.py --all`: all three OK (Play 6 OK; HB `OK: 143 calls on 10 interfaces`; Z9X
  `152 transacting methods`).
- `bash tools/devices/test/check_aidl_hal_test.sh`: 31 passed, 2 failed. Both failures are the hard-coded expectation
  `OK: 141 calls on 10 interfaces` (`tools/devices/test/check_aidl_hal_test.sh:16`, `:197`, `:260`) while the HB app
  now has 143 allowed calls. The checker is right; the test is stale.
- `bash tools/devices/test/check_forks_test.sh`: 11 passed. `bash tools/devices/test/devices_test.sh`: 23 passed.
- Last gate run (`build/play6/z9x_gate.log`, 18:39:47): GREEN, 1971 inputs, 0 changed, 16 of 16.
- `prove_unchanged.sh` was not run (5 minutes, and it would only confirm the following). By file comparison it would
  fail at least on: `z9x_inputs.ok` (the gate was re-recorded at 18:21:34, the baseline copy is from 02:43,
  `cmp` differs, `prove_unchanged.sh:42-43`); `z9x_apks.sha256` (baseline `Z9xProjector.apk` 74ac6eca..., pin now
  d4480b22...); `overlay_inputs.sha256` (same APK); `z9x_gate_full.txt` (pin hashes, "27 base files" from gen.py);
  `check_hal_callers.txt` (baseline 151 transacting methods, now 152); `check_forks.txt` (left stale on purpose,
  `BASELINE.md:19-23`).

## 6. Pain points, with evidence

### 6.1 Red for unrelated reasons

1. **Any Z9X edit reddens the other devices.** A fork row pins the origin's sha256 (`tools/devices/check_forks.py:65-68`),
   so a Z9X fix in a file the Play 6 or HB copied turns their check red even when the copy needs nothing. Today 11
   origins are red across two devices (section 5). For the Play 6 this also blocks the build (`build_play6.sh:37`),
   so Play 6 work that touches none of those files cannot produce an APK until someone reviews Z9X changes.
2. **A new Z9X resource reddens the Play 6.** Every file under `apps/Z9xProjector/res` must be placed in `FILES` or
   `res_not_shared.txt` (`tools/play6/check_forks.py:164`). Today `strings_fit.xml` (the Z9X "Fit to screen" work)
   fails check F.
3. **Shared code reddens the Z9X gate.** A change in `apps/common` or `apps/i18n` rebuilds all pins
   (`z9x_gate.sh:101-102`); if any Z9X app consumes the change, its pin no longer matches and the gate is red until
   the Z9X is re-pinned and re-recorded. `build_test.sh` grew `--z9x-ahead` to live with this
   (`build_test.sh:186-194`).
4. **Tool changes redden the Z9X gate.** Everything in `tools/ota`, `tools/lumen`, `tools/sign` (tests included) is an
   image input with no rebuild proof (`z9x_gate.sh:123-124`). So `make_ota.py` cannot get a `--device` option without
   a gate re-record (`devices/DESIGN.md:326-337`), and generic tools have to be written beside them
   (`tools/devices/ota.py`, a wrapper).
5. **Counts in texts.** `prove_unchanged.sh:41` requires the literal "16 of 16"; `prove_unchanged.sh:61-63` compares
   check outputs as text, so "151" vs "152 transacting methods" or "26" vs "27 base files" is a FAIL; the AIDL test
   expects "141 calls" (section 5). Each of these goes red on legitimate growth, not on a regression.
6. **Duplicate reports.** For the Play 6, `tools/devices/check_forks.py` prints each drifted row from its own row check
   and again from `tools/play6/check_forks.py` (`:93-99`), which doubles the noise in every red run.

### 6.2 Manual re-records

- Gate: 16 dated backups of `build/play6/z9x_inputs.ok` from 2026-10-12 to 2026-10-30, four of them on 2026-10-30
  (`024221`, `140459`, `172016`, `182049`), plus the current record of 18:21:34. Each one needs the owner's OK by rule
  (`z9x_gate.sh:10-13`).
- Pins: `Z9xProjector.apk` re-pinned four times on 2026-10-30, each with a prose entry in
  `overlay/apps_v1/README.txt`.
- Baseline: two dated copies of `devices/_baseline/mac/` in two days, refreshed file by file with prose in
  `BASELINE.md:8-23`; there is no "refresh" command, and the bundle is knowingly left red between lanes.
- Forks: HB `same:` rows have `refresh_same.sh`; Play 6 rows and HB fork rows are re-hashed by hand after review.
  `expected_changes.txt` exists for script rows but is empty, so it is not the path anyone uses.

### 6.3 Structure that does not scale to more projectors

- **Names, not registry, decide scope.** The gate globs `apps/Z9x*` (`z9x_gate.sh:59`); the publish audit matches
  `Z9x|Play6|Lumen` and `Hb` prefixes (`publish_audit.py:210-227`); `build_apps.sh` lists the "other device"
  certificates as `apps/sdk`, `tools/sign/testcerts`, `tools/play6/certs`, `tools/sign/release_certs` only
  (`tools/devices/build_apps.sh:42-43`), not `devices/*/certs` (the generic `check_device_signers.py` does include
  them, `:13-17`). A fourth projector with a new prefix gets REVIEW rows in the audit (which stops builds and the
  export) and is invisible to the gate and to `build_apps.sh`'s foreign-certificate list until each is edited.
- **Three HAL checkers, three styles.** Only the AIDL one is table-driven. The Z9X rules are a Python dict inside a gate
  input (`apps/check_hal_callers.py:29-43`); the Play 6 rules are 1071 lines of code.
- **Two fork checkers** for one idea, with the Play 6 one hard-coding its own table path
  (`tools/play6/check_forks.py:112`).
- **No per-device aggregate.** The gate is Z9X-only, check F and H run only inside `build_play6.sh`, the HB checks run
  only by hand or inside `build_apps.sh`. `prove_unchanged.sh` covers Z9X and Play 6, not the HB.
- **Copying is the default way to share.** About 308 HB `same:` copies exist only because the origin folders are
  gate inputs (`PROJECTOR_DESIGN.md:610-618`). Each copy is a row to re-hash when the Z9X moves.

## 7. How the guards would work with a shared core

These are options derived from the facts above, not decisions. They assume a layout where device-neutral code lives
in one shared module (for example `apps/core/projector/`) and each device app holds only its HAL client, features
and real forks, and they keep the owner rule (Z9X byte-identical) intact.

### 7.1 The Z9X gate: protect outputs, not folders

- The owner rule is about what the Z9X ships: the 16 pinned APKs and the image inputs. The pin rebuild already proves
  the APK part by output (`z9x_gate.sh:146-193`). With a shared core, any core change triggers "rebuild every pin"
  (as `apps/common` does now, `:101-102`); it stays GREEN when the Z9X APKs come out identical, which is the real
  rule. A core change that does alter a Z9X APK is a Z9X change and needs the owner's OK, exactly as today.
- What has to change: the input list (`:57-72`) gains the core folder; nothing else in the logic. The core then stops
  being a reason to copy files into other devices.
- To cut re-records: let the record be a pair (input hashes, pin hashes) per release, written by one command that
  also refreshes the baseline copy, so "owner OK, re-record" is one step instead of three (gate, `apps_v1/README.txt`,
  `devices/_baseline/mac/`).

### 7.2 The proof bundle: per device, from the registry

- Replace the fixed Z9X/Play 6 script with a loop over `devices.py list`, each device declaring its own proof
  (gate for the Z9X, APK repeatability and compose inputs for the Play 6, BUILD_INFO/verify for the HB), so a new
  projector adds a registry key, not a new script section.
- Compare facts, not prose: exit status and hashes instead of "16 of 16" and normalised check output
  (`prove_unchanged.sh:41`, `:61-63`). A count can be checked against the registry or the pin list, which already
  holds the truth.
- Give `record_mac.sh` a refresh mode that writes all files at once, with `expected_changes.txt` used for every file
  kind, not only `scripts.sha256` (`prove_unchanged.sh:52-58`).

### 7.3 Fork checks: fewer copies, softer coupling

- With the shared core, `same:` copies disappear: a file that is byte-identical on two devices belongs in the core.
  For the HB that is about 308 rows (`devices/goat_elegant/forks.tsv`), for the Play 6 the 230 `FILES` entries stop
  being copies, and `sync.sh --check` for them becomes unnecessary.
- What remains are real forks (23 HB, 51 Play 6 today). Two changes would stop unrelated reds:
  - make "origin changed" a warning that lists the forks to review, and red only in release builds, so a Play 6 APK
    can still be built during Z9X work (today `build_play6.sh:37` stops it);
  - merge the two checkers: keep the Play 6 semantic rules as per-row checks in data (kind of row, for example
    `keys:`, `wire:`, `config:`) inside `tools/devices/check_forks.py`, and drop the duplicate pass (`:93-99`).
- The "every Z9X resource placed" rule (`tools/play6/check_forks.py:164`) becomes "every core resource is core", which
  needs no list.

### 7.4 HAL checks: one table-driven checker

- `check_aidl_hal.py` already shows the shape: generic rules, device tables. The HIDL checkers could be moved to the
  same model (codes, banned, allowed per device in `devices/<c>/research/`), keeping the current scripts as the
  source of truth until a generic HIDL checker reproduces their verdicts on today's tree (the condition stated in
  `devices/DESIGN.md:177-179`).
- The banned list in `apps/check_hal_callers.py:29-43` would move to a data file outside the gate inputs, so adding a
  rule for another device does not re-record the Z9X gate.
- Fix `check_aidl_hal_test.sh` to read the expected count from `allowed.tsv` instead of the literal 141.

### 7.5 Publish audit and export

- Drive the app rules from the registry: a device's `app_prefix` and a per-device `public=yes|no` key would decide
  PUBLISH or EXCLUDE (`publish_audit.py:210-227` today hard-codes `Hb`, `Z9x|Play6|Lumen`). A core folder gets one
  PUBLISH rule. A new projector is then private by default without a REVIEW row that stops builds.
- `apps/Z9xBtPair/` needs a decision: the audit calls it obsolete (`publish_audit.py:109`), the HB builds it
  (`devices/goat_elegant/device.conf:32`). In a shared-core layout it would be a core app.
- `export_repo.py` needs no change: it is device-agnostic and follows the manifest.

### 7.6 One entry point

- A single `tools/devices/check.sh [--device C | --all] [--fast | --release]` that runs, per device from the registry:
  fork check, HAL check, sync check (while it exists), signer check, the device's own proof (the Z9X gate for
  horse_popular), and the publish audit once. Today these are started from five different places (section 2), and
  the HB set is started from none.

## 8. Files read

`tools/play6/z9x_gate.sh`, `tools/devices/prove_unchanged.sh`, `devices/_baseline/record_mac.sh`,
`devices/_baseline/BASELINE.md`, `devices/_baseline/expected_changes.txt`, `tools/devices/check_forks.py`,
`tools/play6/check_forks.py`, `tools/devices/check_hal.py`, `tools/devices/check_aidl_hal.py`,
`apps/check_hal_callers.py`, `docs/repo/publish_audit.py`, `docs/repo/export_repo.py`, `tools/devices/build_apps.sh`,
`tools/devices/check_device_signers.py` (header), `tools/devices/devices.py` (WIRE), `tools/play6/build_play6.sh`,
`tools/play6/build_test.sh`, `apps/common/sync.sh`, `devices/*/device.conf`, `devices/goat_elegant/forks.tsv`,
`devices/goat_elegant/image/refresh_same.sh`, `devices/goat_elegant/PROJECTOR_DESIGN.md` (section 10),
`devices/DESIGN.md` (sections 0, 1, 3, 6, 7, 8, 10), `overlay/apps_v1/README.txt`, `build/play6/z9x_gate.log`,
the tests in `tools/devices/test/`.
