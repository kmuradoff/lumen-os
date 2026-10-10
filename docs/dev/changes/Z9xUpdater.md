# 1.0.1, no-Google edition (2026-10-09)

- `Ota.channelFile()`: the image's own manifest file (basename of `ro.z9x.ota.manifest_url`, default
  update-stable.json). The USB check reads only that file; `Preflight.applicable` refuses a manifest whose
  `channel` is another edition's (`blk_channel`, 18 locales). One APK for every edition.
- About > Licences: the Google line only where `com.google.android.gms` is installed (`<queries>` for it).
- Test: `test/run.sh` also runs `ChannelTest`.

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

## 1.0.0 integration (2026-10-08)
- Build: `VERSION_CODE=101 VERSION_NAME=1.0.0`. Icon `brand_icon_updater` (apps/common/brand).
  sha256 ac3e9c0453b210ed833a76c76e00429feee92ccd90dd143e289c33233507cc5f (test key). No code change.
- The OS version_code scheme is now major*10000 + minor*100 + patch + 1 (Lumen OS 1.0.0 = 10001, docs/ota.md).
  `Ota.versionCode(String)` (fallback when ro.z9x.version_code is missing) and the "requires version x.y"
  text of `Preflight` still use the formula without the +1: cosmetic, every Lumen image sets the prop. To align
  in the next updater change.

## 1.0.1 integration (2026-10-08)
- Build: `VERSION_CODE=102 VERSION_NAME=1.0.1`. No code change. sha256
  20b571b28ee401a053654368b28dbdbba8e3a98d78fe50a442482e71249e676f (test key).
- Not done yet (L-OTA handoff): the gate's new `sys.z9x.ota=unhealthy` and `sys.z9x.ota.why` are not shown;
  nothing breaks (only `pending` blocks an install, `rolledback` now really means the old slot runs).

## 1.0.1 owner fixes: the boot gate's verdict after an update (2026-10-09)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`). Gate: `tools/ota/image/z9x_ota.sh` (read only).
- New `Outcome` (plain Java; host test `sh test/run.sh`, 47 checks): `afterBoot` (BootReceiver.evaluate),
  `afterGate` (the later look), `why` (reasons in plain words), `resultStale`.
- After the restart: the update's build (or its version on a build that is not the starting one) runs =
  "Updated to Lumen OS x" (or at once the problem below if the gate already says `unhealthy`); the
  starting build runs again (gate `rolledback`, bootloader fallback, switch undone while READY) = "could not
  start, x restored"; neither build runs (reinstalled from a computer) = no message.
  `rolledback` on the update's own slot (1.0.0's gate) is never "restored".
- The gate decides 90 s after boot_completed: `CheckJob` JOB_GATE / JOB_GATE_NEXT (no network, any update
  mode) asks 3 min after BOOT_COMPLETED, then every 2 min, 5 looks (`Store.gateWatch`). `unhealthy` (the
  slot is already marked, no way back) or, at the last look, a still-`pending` gate with `sys.z9x.ota.why`
  set (a refused rollback, "no rollback: ...") = "Lumen OS x is running. A check after the update found a
  problem: <reason>. If something does not work, use the installer's rescue. Your data stays." with a QR
  code to the guide's Rescue section (`Ota.GUIDE_RESCUE_*`) and the gate's own words as "Technical details".
  Same text as a notification. The main screen also shows it while `sys.z9x.ota=unhealthy` (this boot).
- Reasons (`why_*`): system restarted, a system service did not start, lamp and fan service did not start,
  the new version could not be confirmed, going back was not possible, no details; anything else is shown
  as the gate wrote it.
- A result is recorded with the running build id (`result_build`) and shown only on that build:
  results of the 1.0 / 1.0.0 updater (no build id) and results left from another build (a reinstall from
  a computer, a rollback after the result) are dropped at boot (logged) and never shown, so "restored"
  cannot appear on a running update.
- Strings: 10 new keys in all 17 languages (res/values-*, values-pt-rBR = pt). The same keys in gen.py's
  source format: `tools/i18n_pending_101/<locale>.txt` (en.txt = the base, for comparison only).
- Review: a slot the gate rolls back after boot_completed (unmarked, health failed; its "Updated to" is
  dropped as a result of the other build) is now said as "could not start, x restored" on the boot that
  follows (`Outcome.rolledBackLater`: a watched first boot, `rolledback`, another version running).
  The QR code of the problem screen keeps its gap to the text in RTL (margin start).
- Build: sha256 e6e7ca871f0a1baa9a1fa3307b3beee2f2a710907b006051044c6baa0761a038 (test key).
- Handoff: i18n, copy `tools/i18n_pending_101/*.txt` to `apps/i18n/pending/ota-101/` and run
  `merge_pending.py --write ota-101` before the next `gen.py --write` (gen.py would otherwise drop the new
  keys from res/values-*). Image: the new APK goes to `overlay/apps_v1/Z9xUpdater.apk`.
- Device checks: an update that boots fine ("Updated to", no problem after ~3 min); `setprop
  sys.z9x.ota unhealthy; setprop sys.z9x.ota.why 'zygote not running'` as root during the first 11 min
  shows the problem (notification + screen, QR readable); a rollback drill (`ro.z9x.ota.test_fail=1`)
  shows "restored" on the old slot only; About shows no stale result after a computer reinstall.

## 1.0.1: UI resolution 1080p / 2K / 4K (2026-10-09, owner's request)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`). No code change needed:
- Checked at 1920x1080 @ 320, 2560x1440 @ 427 (the new default) and 3840x2160 @ 640 (960x540 dp each):
  every size is dp (`Ui.dp`) or sp; the QR codes (About, the vbmeta and rescue guides) are drawn as
  rectangles at the view's size (no bitmap; crisp at any size); the progress line is 6 dp; the two columns
  are weights. The activity is relaunched on a density change (`configChanges` has no `density`).
- Build: identical to the 1.0.1 pin, sha256 e6e7ca871f0a1baa9a1fa3307b3beee2f2a710907b006051044c6baa0761a038.
  `sh test/run.sh`: 47 passed.
- Device checks (2K and 4K): About shows the same layout as at 1080p and its QR code scans; an update's
  progress screen and the problem screen's QR code (the `setprop sys.z9x.ota unhealthy` drill) look the same.
