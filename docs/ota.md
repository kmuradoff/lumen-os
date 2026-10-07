# Lumen OS updates (OTA): how they work

Design: research v7 `ota/SPEC.md` + `PLAN.md` (C16, C17), decisions of 2026-10-06. Status in 1.0:
everything below is implemented; **no update has been published or installed yet** (the owner tests
1.0 first).

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
  boot_completed + 90 s: health gate ▶ bootctl mark-boot-successful ▶ update_engine merges
  (fails 3 times / no boot in 8 min / unhealthy ▶ slot B active ▶ reboot ▶ "restored")
```

## Pieces and owners

| Piece | Where | What |
|---|---|---|
| Updater app | `apps/Z9xUpdater` (`org.z9x.updater` 1.0, versionCode 100) | TvSettings "Lumen OS update", checks, download, install, About (version, build, author, project QR, licences) |
| Gate + slot facts | `tools/ota/image/z9x_ota.{rc,sh}`, `update_verifier.rc` | attempt counter, health gate, rollback, `sys.z9x.*` properties, vbmeta header flags (read only) |
| Public-image codec files | `tools/ota/image/z9x_blobs.{rc,sh}`, `blobs_allow.txt` | bind-mount the user's own MediaTek files from `z9x_blobs_<slot>` |
| Props | `tools/ota/image/ota_props.txt` | `ro.product.ab_ota_partitions` (static copy set, no vbmeta), `ro.z9x.build_id`, `ro.z9x.version_code`, `ro.z9x.keys`, `ro.z9x.ota.gate` |
| Payload tools | `tools/ota/make_ota.py`, `sign_payload.py`, `make_manifest.py`, `verify_manifest.py`, `build_otatools.sh` | partial system-only VABC payload, split signing, signed manifest |
| Keys | `docs/keys.md` | `ota` (+ spare `ota_next`) in `otacerts.zip` |
| Image integration | `tools/lumen_v1.sh` (L-SYSTEM) | copies the files above, unchanged |
| Power integration | `org.z9x.projector` (L-PROJECTOR) | `sys.z9x.ota` / `sys.z9x.ota.busy` / `OTA_STATE` broadcast, see `tools/ota/image/HANDOFF.md` |

## The updater

- **No persistent process.** A daily job (+ one 10 minutes after boot) checks; a foreground service
  runs only while downloading or installing. Idle cost: 0 MB.
- **Trust chain:** `update-stable.json.sig` = RSA-SHA256 by a key in `/system/etc/security/otacerts.zip`
  (Lumen `ota` or `ota_next`) ▶ zip sha256 ▶ the side-car `payload_metadata.bin` must equal the
  payload's own header ▶ `update_engine` checks the metadata and payload signatures against the same
  `otacerts.zip` ▶ `max_timestamp` forbids downgrades.
- **Applicability:** device `z9x`, `ro.vendor.build.version.incremental` in `requires.vendor_incremental`
  (V6.15.58), newer `version_code`, current version >= `min_version_code`; a delta only when
  `from_build_id` equals `ro.z9x.build_id`, else the full package.
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
- **USB stick:** `update-stable.json` + `.sig` + the zip + its `-payload_metadata.bin` in the root or in
  `lumen-os/`; same checks, the zip is read straight from the stick.
- **Network:** HTTPS only, `Range` resume through the stable URL (fresh CDN redirect each time), only a
  `User-Agent: Lumen-OS-Updater/1.0` header. No device ID, no telemetry.

## The boot gate (`z9x_ota.sh`)

An "update slot" is a boot where libsnapshot's indicator `/metadata/ota/snapshot-boot` names the
*other* slot and the current slot is not marked successful. Only then:

| When | What |
|---|---|
| post-fs-data (exec, < 20 ms) | count the attempt in `/metadata/z9x_ota/attempts`; more than 3 ▶ roll back |
| zygote-start (`update_verifier`) | do not mark (normal boots run the stock `update_verifier` unchanged) |
| pending, no `sys.boot_completed` within 8 min | roll back |
| boot_completed + 90 s | system_server started once, zygote / surfaceflinger / gmpf_main running ▶ `bootctl mark-boot-successful`; else roll back |
| request `sys.z9x.ota.request=mark` | the same health gate at once ("Keep this version") |

Roll back = `bootctl set-active-boot-slot <other>` + `reboot,z9x-ota-rollback`; libsnapshot on the old
slot then cancels the update. Rollback is possible only **before** the merge. Kill switch:
`ro.z9x.ota.gate=0` (stock behaviour). Test drill: an image with `ro.z9x.ota.test_fail=1` always rolls
back. The gate never writes vbmeta or any partition.

## What the first update into slot A needs (the owner's projector)

1. The owner flashes **vbmeta for slot A** by hand (Google's GSI instructions, verification disabled).
   The updater shows whether it is done (`sys.z9x.vbmeta_other.flags`).
2. Recommended once: a read-only backup of the static partitions of both slots to the computer.
3. On the projector, the updater asks for the **firmware copy consent**; then it installs.
4. Be present for that first update with a computer at hand (the bootloader's own retry counter on
   this device is untested; the software gate covers everything after post-fs-data).
