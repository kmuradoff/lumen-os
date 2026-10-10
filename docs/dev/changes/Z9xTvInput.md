# Z9xTvInput — Lumen OS 1.0 (versionCode 100, W2 lane CEC)

HDMI-CEC phase 1 (research/v7/cec/SPEC.md 3.1, PLAN C14/C15), adapted to the real-STR power model.

- **CEC child inputs (F8 fix).** `HdmiInputService.onHdmiDeviceAdded` returns a child `TvInputInfo` for every
  CEC source (playback / tuner / recorder) behind one of our ports: `setHdmiDeviceInfo` + `setParentId(<port input>)`,
  label = OSD name. Without it `HdmiCecLocalDeviceTv` buffers a source's `<Active Source>` forever and One Touch Play
  never fires. Audio systems and unknown ports get none. `onHdmiDeviceRemoved` returns the child id.
  Child sessions play through the parent port's hardware; only routing differs (`deviceSelect`, falls back to
  `portSelect`). Port lists (`isOurPortInput`, picker, Source overlay) still ignore children; Lumen Home groups them (C15).
- **Viewer** follows the parent port's state for a child input, keeps the picture on the port input when the child
  disappears, banner "HDMI 2 · PS5".
- **HdmiWatcher** (`<Active Source>`): nothing before setup is complete (C14); with the projector in its lamp-only
  standby a CEC *source* asks the projector (`content://org.z9x.projector.standbystate` call `cec_wake`, extras
  port/la/type/vendor/osd) and its input opens only when `accepted`; while the projector is on, the v6.x One Touch Play
  switch, now to the child input; never re-tunes a port that is already shown.
- **HdmiStateProvider**: `hdmi_state` also returns `streams` (ports with a streaming session = a picture);
  new `get_cec_prefs` / `set_cec_pref` (keys cec_one_touch_play, cec_control, cec_internal_on_exit; HDMI_STATE).
- **SettingsActivity**: the three CEC check boxes moved to the projector's quick panel page "HDMI devices (CEC)";
  this page keeps auto switch, return home, open at power-on (opened from that page's "HDMI input options").

Not tested on the Z9X (no CEC device, no adb). Device tests: cec SPEC T1–T4, T13.

## 1.0.0 integration (2026-10-08)
- Build: `VERSION_CODE=101 VERSION_NAME=1.0.0`. Icon `brand_icon_tvinput`, banner `brand_banner_tvinput`
  (apps/common/brand). The unchanged sources first rebuilt to the 1.0 pin exactly; new pin
  3109240512be0c237421d3e40292047ee458f2a9c0e18dcfd3a146df534a8b37 in overlay/apps_v1/PINS.sha256.

## 1.0.1 integration (2026-10-08)
- Build: `VERSION_CODE=102 VERSION_NAME=1.0.1`. No code change: the sources first rebuilt exactly to the 1.0.0
  pin; new pin e9abdf4763a98bea70a2d5bf3de932e589597fb0dc39db07e41df9ea9f6911c1 in overlay/apps_v1/PINS.sha256.

## 1.0.1: UI resolution 1080p / 2K / 4K (2026-10-09, owner's request)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`).
- Checked at 1920x1080 @ 320, 2560x1440 @ 427 (the new default) and 3840x2160 @ 640 (960x540 dp each):
  all sizes are dp / sp; the viewer's TvView is MATCH_PARENT in a full-screen theme, so the HAL's sideband
  stream gets the whole window at any UI resolution (fitting it to the panel is the vendor HWC's job).
- `PassthroughActivity` keeps itself over a density change (`configChanges`, so the HDMI stream is not
  reopened): the status and banner text, padding and margins are px once set, so `sizeOverlays()` sets them
  again when `densityDpi` changes (crash-shielded). Nothing changes at a fixed resolution.
- Build: sha256 76c3afa56e7a8fdab47abe82e4b16177ddfb44b54c18433cc57062745ec58a71 (test key); the sources
  before this change rebuilt exactly to the 1.0.1 pin e9abdf47... first. Not pinned yet in
  overlay/apps_v1/PINS.sha256 (integration).
- Device checks (2K default, then 4K): HDMI 1 / 2 picture fills the screen with no crop, border or zoom
  (compare with 1080p); "Connecting" / "No signal" status and the "HDMI 2 · ..." banner have the 1080p size
  in proportion; the picker and HDMI settings pages look the same.
