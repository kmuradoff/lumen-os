# org.z9x.updater ("Lumen OS update") 1.0 (versionCode 100)

New in Lumen OS 1.0 (lane L-OTA). Build: `VERSION_CODE=100 VERSION_NAME=1.0 sh ../build_apk.sh Z9xUpdater`.
Install: `/system/app/Z9xUpdater/Z9xUpdater.apk`, platform-signed (re-signed with the Lumen release
platform key by `tools/sign/sign_tar.py`), not priv-app, not persistent. Image wiring:
`tools/ota/image/HANDOFF.md`; design: `docs/ota.md`.

- TvSettings About > "Lumen OS update" (`android.settings.SYSTEM_UPDATE_SETTINGS`, label = row title).
- Check (daily job + 10 min after boot), signed manifest (`otacerts.zip`), applicability, delta or full.
- Resumable HTTPS download (Range/If-Range, network-loss waits, sha256, metadata = payload header).
- Install through `UpdateEngine.applyPayload(fd)`: preflight (other slot's vbmeta flag, pending/merge,
  thermal, engine), `verifyPayloadMetadata`, `allocateSpace`, wake lock, `sys.z9x.ota.busy`,
  `OTA_STATE` broadcast; one-time consent before XGIMI firmware is copied unchanged to the other slot.
- Ready: Restart now (`reboot,z9x-ota`) / at the next restart / cancel (`resetShouldSwitchSlotOnReboot`).
- After reboot: "Updated to x.y" or "could not start, x.y restored" (gate property / build id).
- Install from a USB stick; "verify only" dry run for test T5
  (`am start -n org.z9x.updater/.UpdaterActivity --ez verify_only true`).
- About: version, build, author kmuradoff, project page with an own QR encoder (verified by decoding
  with CoreImage), licences and disclaimer.
- `content://org.z9x.updater` call("state") for Lumen Home's badge and the projector's power policy
  (signature permission `org.z9x.updater.permission.OTA_STATE`).
- Strings: English base here; 16 translations in `apps/i18n/pending/ota/` (validated with gen.py's
  rules and test-built). W2: add `"updater": APPS + "/Z9xUpdater/res"` to gen.py's RES and merge.

## W2 integration (2026-10-07)
- Ota.setBusy also sends the explicit broadcast org.z9x.projector.action.OTA_STATE (extra busy) to
  org.z9x.projector (power.OtaStateReceiver keeps the SoC out of STR while busy); manifest now holds
  uses-permission org.z9x.projector.permission.OTA_STATE. sys.z9x.ota.busy stays as a best-effort duplicate.
- Lumen Home now requests org.z9x.updater.permission.OTA_STATE (was a non-existent ...permission.STATE).

## Review fixes (2026-10-07, round 3)
- The brand eyebrow reads "Lumen OS" (mixed case, the same mark as boot, wake and power-off).
- Build: 1.0 (100), sha256 0893ba3869b7f05d2032cb55baa81d97352cb50bb4279c7216d21ee32feedf0a (test key).
