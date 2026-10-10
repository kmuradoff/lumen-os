# Lumen OS updates (OTA): how they work

Design: research v7 `ota/SPEC.md` + `PLAN.md` (C16, C17), decisions of 2026-10-06. Status: everything
below is implemented. Lumen OS 1.0.0 was the first update published and installed over the air
(2026-10-08, GitHub release v1.0.0). 1.0.1 (version_code 10002) brings the rewritten boot gate (boot HAL
through `service call`, measured on that update) and the boot rescue; its image is built, its OTA is not
made or published yet.

## In one picture

```
GitHub Releases (kmuradoff/lumen-os, latest)          Projector, running slot B
  update-stable.json + .sig  ──HTTPS──▶  org.z9x.updater: signature (otacerts.zip) ▶ preflight
  lumen-os-X-...-ota.zip     ──Range──▶  its own storage, sha256 ──fd──▶ update_engine
                                            metadata signature (otacerts.zip)
                                            system_a = Virtual A/B compressed snapshot
                                            vendor_a, z9x_blobs_a = same extents as _b (no copy)
                                            boot, bootdata, dtbo, mboot, optee, owl, satf, tvconfig,
                                            tvservice, vbmeta_system, xgimiconfig: copied B ▶ A
                                            unchanged (never vbmeta)
                                            slot A active, not yet "successful"
  reboot,z9x-ota ▶ slot A: z9x_ota.sh counts the attempt; update_verifier defers the mark;
  boot_completed ▶ vold marks slot A (userdata checkpoint) ▶ update_engine merges at once
  (before that: fails 3 times / no boot in 8 min ▶ boot HAL: slot B active ▶ reboot ▶ "restored")
```

## Pieces and owners

| Piece | Where | What |
|---|---|---|
| Updater app | `apps/Z9xUpdater` (`org.z9x.updater` 1.0.0, versionCode 101) | TvSettings "Lumen OS update", checks, download, install, About (version, build, author, project QR, licences) |
| Gate + slot facts | `tools/ota/image/z9x_ota.{rc,sh}`, `update_verifier.rc` | attempt counter, deadline, rollback before boot_completed, health report, `sys.z9x.*` properties, vbmeta header flags (read only) |
| Boot rescue | `tools/ota/image/z9x_rescue.{rc,sh}` (1.0.1) | 3 starts in a row without boot_completed ▶ fastbootd for `lumen-install rescue` |
| Host tests | `tools/ota/test/run.sh`, `installer_rescue.sh`, `blobs.sh`, `installer_blobs.sh`, `public_members.sh` | gate + rescue with stubbed getprop/setprop/boot HAL; installer `rescue` with a fake fastboot; z9x_blobs.sh, installer `blobs`/`check` and the public image members with made-up files |
| Public-image codec + audio files | `tools/ota/image/z9x_blobs.{rc,sh}`, `blobs_allow.txt`, `xgimi_compat_public.rc` | bind-mount the user's own MediaTek / XGIMI files from `z9x_blobs_<slot>` (`tools/lumen_v1.sh VARIANT=public`, HANDOFF.md section 2) |
| Google services add-on (public images) | `tools/ota/image/z9x_gapps.{rc,sh}`, `gapps_allow.txt`, `z9x-gapps-sysconfig.xml`, `tools/ota/gapps_allow.py`; installer `gapps` + `installer/lib/gapps_fill.sh` | on Lumen OS without Google: check and overlay (read-only) the user's own MindTheGapps files from `z9x_gapps_<slot>` at post-fs-data (HANDOFF.md section 2b) |
| Props | `tools/ota/image/ota_props.txt` | `ro.product.ab_ota_partitions` (static copy set, no vbmeta), `ro.z9x.build_id`, `ro.z9x.version_code`, `ro.z9x.keys`, `ro.z9x.ota.gate` |
| Payload tools | `tools/ota/make_ota.py`, `sign_payload.py`, `make_manifest.py`, `verify_manifest.py`, `build_otatools.sh` | partial system-only VABC payload, split signing, signed manifest |
| Keys | `docs/keys.md` | `ota` (+ spare `ota_next`) in `otacerts.zip` |
| Image integration | `tools/lumen_v1.sh` (L-SYSTEM) | copies the files above, unchanged |
| Power integration | `org.z9x.projector` (L-PROJECTOR) | `sys.z9x.ota` / `sys.z9x.ota.busy` / `OTA_STATE` broadcast, see `tools/ota/image/HANDOFF.md` |

## Variants and editions: private, public, without Google (since 1.0.1)

`tools/lumen_v1.sh` builds three combinations of `VARIANT` (MediaTek / XGIMI files inside or not) and `GMS`
(Google apps or not; `GMS=0` only with `VARIANT=public`, owner decision 2026-10-09). **Only the no-Google
edition, "Lumen OS без Google", is ever published**; the Google edition (private or public) never is
(`tools/ota/check_release_assets.py` refuses it).

| | Private (`VARIANT=private`, the owner's) | Public, Google (`VARIANT=public`, lab only) | Public, no Google (`VARIANT=public GMS=0`, published) |
|---|---|---|---|
| MediaTek / XGIMI files | inside `/system` | none; the user's own copies from `z9x_blobs_<slot>` | same as public |
| Google apps (MindTheGapps) | yes | yes | none (`tools/lumen/nogms_remove.txt`) |
| `ro.z9x.variant` | absent | `public` | `public` |
| `ro.z9x.gms` | absent | absent | `0` |
| `ro.z9x.ota.manifest_url` | `.../update-stable.json` | `.../update-public.json` | `.../update-public-nogms.json` |
| build id suffix | never ends in `p` or `n` (`e`) | ends in `p`, not `np` (`ep`) | ends in `np` (`enp`) |
| outputs | `system_tv_lumen_v1*`, as an asset never | `system_tv_lumen_v1_public*`, as an asset never | `system_tv_lumen_v1_public_nogms*` + `lumen-os-<ver>-nogms-system.img`, `SHA256SUMS`, `SHA256SUMS.sig` |
| OTA packages | `make_ota.py --private` or clean deltas (owner only) | lab only | `lumen-os-<ver>-nogms-full-ota.zip`, `lumen-os-<ver>-nogms-from-<old>-ota.zip` |
| manifest channel | `stable` | `public` (never published: 404 = no update) | `public-nogms` |

Suffix formula (bash `suffix_check` and `check_image.suffix_errors`): `core` = the suffix without its
final `p` for a public build; public suffixes end in `p`, private ones never do; `GMS=0` if and only if
`core` ends in `n`. So builds of two variants or editions never share a build id.

Why: the updater has no variant field. It reads the manifest at `ro.z9x.ota.manifest_url`, picks a
delta only when `from_build_id` equals `ro.z9x.build_id`, else the full package. Each edition reads its
own manifest file and their build ids never coincide. Since 1.0.1 the updater also refuses a manifest
whose `channel` is not its own file (`Preflight.applicable`, `blk_channel`: a USB stick or a mirror may
carry another edition's signed manifest) and its USB check looks only for its own file
(`Ota.channelFile()`). The packaging tools bind every package to its image's edition: `make_ota.py`
refuses a delta across variants or editions and writes `lumen-edition=gms|nogms` into the package
metadata (`-nogms-` in the name if and only if nogms), `make_manifest.py --channel` must be the image's own
manifest file and every package's edition the image's, `verify_manifest.py` checks the file name against
the `channel` field. A missing manifest (404) is "no update".

**Switching edition is a reinstall with a data wipe, never an OTA**: Play and Google services updates in
/data would become orphaned user apps and Google accounts would survive. The installer refuses
`flash --keep-data` and `rescue` across editions.

The release step: `make_ota.py` over two published no-Google images (or a full package),
`make_manifest.py --channel public-nogms --blobs-set z9x-v61558-b`, assets `update-public-nogms.json` +
`.sig`. Moving a private projector to the public image is a reinstall with the installer (`blobs`,
`flash`), not an OTA. `z9x_blobs_<slot>` survives an update only because every Lumen payload is PARTIAL
(`make_ota.py` always passes `--is_partial_update`, `sign_payload.py` refuses a payload without it):
update_engine then renames the untouched `z9x_blobs_<source>` to `z9x_blobs_<target>` with the same
extents. A non-partial payload would delete it (libsnapshot drops every target-slot partition its
manifest does not list), leaving a public install without sound and secure video. The same holds for
`z9x_gapps_<slot>`, the Google services a user added with the installer's `gapps` (HANDOFF.md section 2b):
a partial payload keeps them, a non-partial one would delete them (the system then starts without Google
services, `sys.z9x.gapps=none`). Every image a published update leads to must still list the user's zip in
`gapps_allow.txt` (never remove a `zip=` block): otherwise `z9x_gapps.sh` refuses the partition
(`bad:zip`). The partition (512 MiB) also takes that much room in super away from the update's snapshot,
which then uses more of the COW space in /data.

## The updater

- **No persistent process.** A daily job (+ one 10 minutes after boot) checks; a foreground service
  runs only while downloading or installing. Idle cost: 0 MB.
- **Trust chain:** `update-stable.json.sig` = RSA-SHA256 by a key in `/system/etc/security/otacerts.zip`
  (Lumen `ota` or `ota_next`) ▶ zip sha256 ▶ the side-car `payload_metadata.bin` must equal the
  payload's own header ▶ `update_engine` checks the metadata and payload signatures against the same
  `otacerts.zip` ▶ `max_timestamp` forbids downgrades.
- **Applicability:** device `z9x`, `ro.vendor.build.version.incremental` in `requires.vendor_incremental`
  (`make_manifest.py` default: v6.15.58 and v6.15.19, as the installer), newer `version_code`, current
  version >= `min_version_code`; a delta only when `from_build_id` equals `ro.z9x.build_id`, else the full
  package (see "Variants" for the manifest each image reads).
- **Version numbers (since 1.0.0):** `ro.z9x.version` = `major.minor.patch` (About shows
  "Lumen OS 1.0.0"), `ro.z9x.build_id` = `lumen-<version>-<yyyymmdd>[suffix]` (suffix: `BUILD_ID_SUFFIX` of
  `tools/lumen_v1.sh`, 1-4 of [a-z0-9] starting with a letter, for a second build of one date, e.g.
  `lumen-1.0.1-20261009b`), `ro.z9x.version_code` =
  major*10000 + minor*100 + patch **+ 1** (1.0.0 = 10001, 1.0.1 = 10002, 1.2.3 = 10204). The +1 keeps
  every release above the 1.0 test builds (`lumen-1.0-<date>`, version_code 10000), so they are offered
  1.0.0 over the air. `tools/lumen_v1.sh` (VER) and `tools/ota/make_manifest.py` enforce the formula.
  Note: the updater's own display of a refused `min_version_code` and its fallback for images without
  `ro.z9x.version_code` still use the formula without the +1 (cosmetic; every Lumen image sets the prop).
- **Before installing (read-only):** the other slot's vbmeta header has "verification disabled"
  (`sys.z9x.vbmeta_other.flags & 2`, else the "One-time step needed" screen with a QR code to the
  guide), the current slot is not an unverified update (`sys.z9x.ota != pending`), no merge is running,
  thermal status below SEVERE, update_engine reachable, `verifyPayloadMetadata` and `allocateSpace`
  pass. Missing protected-video components only warn.
- **Firmware copy consent:** the first install asks once whether XGIMI's firmware partitions may be
  copied unchanged into the other slot (owner decision 2026-10-06). "Not now" installs nothing.
- **Modes:** Off / Only tell me / Download automatically (default). Never installs by itself.
- **Restart:** "Restart now" (`PowerManager.reboot("z9x-ota")`) or "At the next restart": the slot is
  already switched, any restart or full power-off finishes it. Short POWER presses (STR sleep) do not.
- **After the restart:** "Updated to Lumen OS x.y", or "The update could not start. Lumen OS x.y was
  restored" (new build not running, or `sys.z9x.ota=rolledback`).
- **USB stick:** the image's own manifest file (`update-stable.json`, `update-public-nogms.json`: the
  basename of `ro.z9x.ota.manifest_url`) + `.sig` + the zip + its `-payload_metadata.bin` in the root or
  in `lumen-os/`; same checks (the channel guard too), the zip is read straight from the stick.
- **Network:** HTTPS only, `Range` resume through the stable URL (fresh CDN redirect each time), only a
  `User-Agent: Lumen-OS-Updater/1.0` header. No device ID, no telemetry.

## The boot gate (`z9x_ota.sh`)

### What the first real OTA showed (1.0.0, 2026-10-08)

Measured on a Z9X (`logs/friend_<serial>`: `live_100.log` = first start of the new slot B,
`for_101/` = state pulled afterwards):

1. **`/system/bin/bootctl` is not in the Lumen image.** Every bootctl call of the 1.0.0 gate failed:
   `mark-boot-successful` failed ▶ the gate "rolled back" (`sys.powerctl reboot`) although the slot was
   healthy, `set-active-boot-slot` failed too ▶ a needless reboot back into the new slot B, where the
   leftover state then published `sys.z9x.ota=rolledback` (the updater still showed success, because
   it compares the running build id first).
2. **userdata is mounted with `checkpoint=fs`** (`fstab.mt9952`). vold commits the checkpoint when boot
   completes and marks the slot successful itself (`ActivityManager: About to commit checkpoint` ▶
   `Checkpoint: Marked slot as booted successfully`, about 2 s before `sys.boot_completed=1`);
   update_engine then logged `Attempting to initiate merge` and `MergeCompleted` 47 s later. Deferring update_verifier therefore does **not** keep the slot unmarked (update_verifier
   never marks when checkpointing is supported): **after boot_completed a rollback is impossible on
   this device.** After the update, slot B was active, current, bootable and successful; slot A was
   not bootable.
3. **The AIDL boot HAL works from the gate's domain** with
   `service call android.hardware.boot.IBootControl/default <n>` (method order of the AIDL: 1
   getActiveBootSlot, 2 getCurrentSlot, 3 getNumberSlots, 4 getSnapshotMergeStatus, 5 getSuffix,
   6 isSlotBootable, 7 isSlotMarkedSuccessful, 8 markBootSuccessful, 9 setActiveBootSlot,
   10 setSlotAsUnbootable, 11 setSnapshotMergeStatus). A reply is one line,
   `Result: Parcel(<tab>00000000 00000001   '........')`: first word = status, second = value.
4. Static partitions are copied by update_engine; the gate never writes partitions.

### Design since 1.0.1

The gate protects only the time **before** boot_completed and after it only reports. An "update slot"
is a boot where libsnapshot's indicator `/metadata/ota/snapshot-boot` names the *other* slot and the
current slot is not marked successful.

| When | What |
|---|---|
| post-fs-data (`count`, exec) | update slot: attempt counter `/metadata/z9x_ota/attempts` (`<n> <slot>`), `sys.z9x.ota=pending`. Attempt 4 (3 failed) ▶ roll back. Already marked (a restart during the merge) ▶ `merging`/`marked`, no counting. A normal boot: two file reads, no HAL call |
| zygote-start (`verifier`) | while `pending`/`rollback`: update_verifier is not run; otherwise the stock one, unchanged. No HAL call here (reads what `count` published) |
| pending, no boot_completed within 8 min (`watch`) | roll back; when that is not possible, `reboot,z9x-ota-stuck` so the boot rescue counts a failed start |
| boot_completed (`health`) | slot already marked (vold, the normal case) ▶ publish `merging`/`marked` at once, run the health checks 90 s later and only report (`unhealthy` + `sys.z9x.ota.why`), **never reboot**. Slot left unmarked (no checkpointing) ▶ +90 s: system_server started once, zygote / surfaceflinger / gmpf_main running ▶ `markBootSuccessful` via the HAL; unhealthy ▶ roll back |
| request `sys.z9x.ota.request=mark` | the same health gate at once ("Keep this version") |

**Roll back** = boot HAL `setActiveBootSlot(other)` + `reboot,z9x-ota-rollback`, only when the HAL
answers that the current slot is **not** marked, no merge is running, the update was not cancelled
in fastbootd (merge status `CANCELLED`, set by `snapshot-update cancel`, which `lumen-install rescue`
sends before it writes this slot's system again, over storage the old slot shares under Virtual A/B),
the other slot is not reported unbootable, the call succeeds and the active slot reads back as the
other one (a reply of a shape not measured yet counts when the active slot already reads the other
one: a switch that happened must end in the rollback reboot, or the new slot would boot on with the
old one active behind it). Anything else ▶ no
reboot, `/metadata/z9x_ota/no_rollback` (`<slot> <reason>`) + `sys.z9x.ota.why`, and from then on the
boot rescue decides. A rollback that "succeeded" but the next start is the same update slot again is
detected (the `rolledback` record names the running slot) and not repeated. A `no_rollback` record
for this slot found at the first attempt of an update is from an earlier update (a 1.0.0 old slot in
between leaves it) and is removed.

**Boot HAL calls** go through `service call` and are parsed strictly: exactly one line, status word
`00000000`, a one-digit value (or the void shape). Anything else (exception status, binder error,
several lines, no such service, the 2 s `timeout`) is a failure, logged in
`/metadata/z9x_ota/log.txt`, and the answer counts as unknown; an unknown "marked" never leads to a
rollback. `/system/bin/bootctl` is used only where a HAL call fails and the binary exists. HAL calls
happen only on update slots, never in `verifier`, and at most a few in `count` (init waits for it;
the vendor PWM watchdog reboots when early-boot is late).

**What the updater is told after a restart:** `rolledback` only when the slot recorded in
`rolledback` (or in `attempts`, for a bootloader fallback) is **not** the running slot. On the update
slot itself (1.0.0's case) the leftovers are cleared and `none` is published.

`sys.z9x.ota`: `none | pending | marked | merging | unhealthy | rollback | rolledback`
(`unhealthy` is new; the updater does not show it yet). Kill switch: `ro.z9x.ota.gate=0` (stock
behaviour). Rollback drill: an image with `ro.z9x.ota.test_fail=1` rolls back at post-fs-data of
the first attempt (before boot_completed, where a rollback is still possible). The gate never writes
vbmeta or any partition.

## Boot rescue (`z9x_rescue.sh`, 1.0.1)

Owner decision (2026-10-08): when the system fails to complete boot **3 times in a row**, the next
start reboots into **fastbootd** (`reboot,fastboot`, the same path as `adb reboot fastboot`, which
works on the Z9X), so `lumen-install rescue` can write the system again without wiping data. Before
this, a looping Z9X (lamp off, no adb, e.g. the vendor PWM watchdog loop) needed the USB-stick stock
restore.

| When | What |
|---|---|
| `on init` (exec) | first trigger after first-stage init mounted `/metadata` (logd started): `/metadata/z9x_rescue/count` = `<n> <slot>` += 1. When the 3 starts before this one all failed: counter back to 0, log, `reboot,fastboot` |
| `sys.boot_completed=1` | counter back to 0 |

- **Per slot:** another slot starts at 0 (OTA rollback, bootloader fallback, a new install), so every
  slot gets its own 3 tries.
- **Not fighting the OTA gate:** on an update slot the gate rolls back by itself at its attempt 4
  (post-fs-data of that same start). Rescue waits for it while the gate can still act: at most 3 more
  starts (an update that dies before post-fs-data never reaches the gate), and not at all once the gate
  wrote `no_rollback` for this slot (marked, HAL failure, update cancelled in fastbootd, rollback that
  did not take).
- XGIMI's fastbootd leaves by itself after about 2 minutes and the projector starts again; a system
  that still fails comes back to fastbootd after 3 more failed starts.
- Not covered: failures before init's second stage (kernel, first-stage mount, dm-verity, e.g. a
  missing vbmeta step); nothing of `/system` runs there.
- Kill switch: `ro.z9x.rescue=0` (image) or the file `/metadata/z9x_rescue/off` (adb root): starts are
  still counted and logged, nothing reboots. Log: `/metadata/z9x_rescue/log.txt` + logcat tag
  `z9x_rescue`.
- The installer side: `lumen-install.sh rescue` (and `lumen-install.cmd rescue`) checks the image like
  `flash`, asks once, waits up to 15 minutes for fastbootd (product `mt5877`, `is-userspace` yes), then
  in one session: finishes (`snapshot-update merge`) or cancels (`snapshot-update cancel`) an
  unfinished update of that slot, removes `*-cow` leftovers, writes system (+ `z9x_blobs` when
  `BLOBS=partition` and the codec image is on the computer) and restarts. It refuses a slot that still
  has XGIMI's `product`/`system_ext` (not a Lumen install). No wipe, ever.

Host tests (run on the Mac): `sh tools/ota/test/run.sh` (49 cases: normal boot, update slot marked by
vold before health, unmarked + healthy, 4th attempt, deadline, every HAL failure shape, bootctl
fallback, rescue 1-2-3 ▶ fastboot, reset at boot_completed, both together; also `SH=dash` and
`SH=ksh`, ksh93 being the nearest relative of the device's mksh) and
`bash tools/ota/test/installer_rescue.sh` (16 cases with a fake fastboot). Public images:
`sh tools/ota/test/blobs.sh` (z9x_blobs.sh, 17 cases under sh/dash/ksh + the allow-list format),
`bash tools/ota/test/installer_blobs.sh` (installer `blobs` from stock and from a running Lumen OS,
`check`, round trip into z9x_blobs.sh), `bash tools/ota/test/public_members.sh` (`lumen_v1.sh members`
on a test base + check_image's public rules; GMS=0: the Google members removed, wrong suffixes refused).
No test uses a MediaTek / XGIMI file (blobs_fixture.py). The no-Google edition (1.0.1):
`python3 tools/ota/test/nogms_check.py` (check_image's edition gate and variant marks on a synthetic tar),
`python3 tools/ota/test/nogms_ota.py` (make_ota / make_manifest edition rules on tiny erofs images),
`bash tools/ota/test/installer_edition.sh` (installer: several images, edition check, keep-data and
rescue across editions, gsf, verify), `python3 tools/ota/check_release_assets.py --self-test` (Google
edition images, packages and manifests refused). The Google services add-on: `sh tools/ota/test/gapps.sh`
(z9x_gapps.sh after the real gapps_fill.sh, every refusal, the all-or-nothing overlays, the 'off' marker of
one slot and build (an OTA rollback keeps the add-on), the 2 s time budget on a fake clock, 'check'; sh, dash,
ksh; the allow-list against a local MindTheGapps zip when there is one), `bash tools/ota/test/installer_gapps.sh`
(installer `gapps`: install with and without the wipe, refusals, the same add-on already on, --remove, the
round trip through the projector's scripts). Both use made-up files only (gapps_fixture.py).

## What the first update into slot A needs (the owner's projector)

1. The owner flashes **vbmeta for slot A** by hand (Google's GSI instructions, verification disabled).
   The updater shows whether it is done (`sys.z9x.vbmeta_other.flags`).
2. Recommended once: a read-only backup of the static partitions of both slots to the computer.
3. On the projector, the updater asks for the **firmware copy consent**; then it installs.
4. Be present for that first update with a computer at hand (the bootloader's own retry counter on
   this device is untested; the gate covers the update slot from post-fs-data to boot_completed, and
   since 1.0.1 the boot rescue covers any loop that gets past first-stage init).
