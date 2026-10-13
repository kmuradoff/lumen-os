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

## After 1.0.1: CEC device labels and the brand banner (2026-10-13, not pinned)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1` when built). The Z9X keeps its 1.0.1 pin.
- **Live child labels.** A CEC source first appears as `Playback_1` (framework placeholder) and sends its OSD name
  and vendor id about a second later. `HdmiInputService.onHdmiDeviceUpdated` (new) publishes the child input again
  with `TvInputManager.updateTvInputInfo` (same id, the new HdmiDeviceInfo and label) when the OSD name or vendor
  id changes; nothing for power status changes. One more look 3 s after `onHdmiDeviceAdded` covers an update the
  framework drops while it is still registering the child ("Could not resolve input ID").
- **Label** = `Brands.label`: the brand ("PlayStation", "Xbox", "Nintendo Switch", "Apple TV", "NVIDIA Shield",
  "Chromecast", "Fire TV", "Roku"), else the device's own OSD name, else (placeholder or empty name) the port's
  label "HDMI 1". A placeholder is never a label. `HdmiInputs.labelOf` shows just the port while the child carries
  the port's label ("HDMI 1", not "HDMI 1 · HDMI 1").
- **Brands** (new): one table by OSD name and CEC vendor id, sources in its comments (libCEC / v4l-utils vendor
  lists, IEEE registry). Kind (console, player, other) and accent colour (PlayStation blue, Xbox green, Nintendo
  red, neutral grey otherwise).
- **Banner** (`PassthroughActivity`): brand or device name large, our own glyph on an accent tile
  (`ic_banner_console`, `ic_banner_player`, `ic_banner_hdmi`, new vector drawables), the port small ("HDMI 1", plus
  its state when not connected); without a CEC device the port and its state, as before. Shown on tune, INFO / OK,
  the first picture after a tune or after the source was gone, and when the name or brand arrives while it is on
  screen (`onTvInputInfoUpdated`, `onInputAdded`). 3 s, then one 400 ms fade (`withLayer`); no blur, no shadow.
- **CecHelper**: `device(id)` and `sourceFor(port, child)` (fresh `getConnectedDevices`, a powered-on source first).
- No new string, permission or manifest change.

## After 1.0.1: one HDMI device state (2026-10-13, not pinned)
- **PortState** (new): per port and its CEC source one state, `on` / `asleep` / `no_signal` / `disconnected` /
  `unknown`, computed on demand from the port input's TIF state (CONNECTED = HDMI hotplug +5V; the vendor HAL
  reports cable status unknown), the CEC device list (fresh) with the source's power status, and our sessions
  (picture, no signal). Asleep = a listed source in standby / transient to standby, or no +5V while it is still
  listed (a source going to sleep drops +5V before its power status changes). Rule table in `PortState.classify`.
  Refreshed by events only: port and child input states (`HdmiWatcher`), CEC device added / updated / removed
  (`HdmiInputService`), session streaming / no signal (`HdmiSession`); coalesced, logged as transitions
  (`Z9xHdmiPortState`), announced on `content://org.z9x.tvinput.hdmistate/ports`. No background polling. Android 14
  gives an app no way to send `<Give Device Power Status>` alone, so the state relies on the framework's own
  power status updates and on +5V.
- **HdmiStateProvider** `hdmi_state`: new `port_ids` and `port_<n>` bundles (device_state, input_id, label,
  input_state, picture, kind, accent, power, vendor_id, child_input_id, title, brand, name); old keys unchanged.
- **Viewer** state card (BrandCard, the banner's style, in the middle): asleep "<brand> is asleep" / "Press OK to
  turn it on" (OK sends `deviceSelect`: the framework asks the power status, wakes and routes; CEC control
  setting on), "Turning on…" after OK, "No signal", "Device not connected" (return home countdown as before),
  "Connecting…". An asleep source no longer counts as unplugged: no return to the home screen.
- **BrandCard** (new): the tile + title + line card shared by banner and state card.
- Strings: + `status_device_disconnected`, `status_returning_home`, `state_asleep`, `state_asleep_device`,
  `state_press_ok_wake`, `state_waking`; - `status_no_cable`, `status_disconnected`, `status_disconnected_home`
  (unused now). All 17 locale dirs (values-ru by hand, the others from apps/i18n/langs).

## After 1.0.1: viewer cards sized to their text (2026-10-13, not pinned)
- **BrandCard**: the title and the line are WRAP_CONTENT. A vertical LinearLayout gives its children MATCH_PARENT
  by default, and such a TextView kept the width of its first text when a longer state followed with the same
  height ("Connecting…" then "PlayStation is asleep" / "Press OK to turn it on"): both were cut with an ellipsis.
  The card is as wide as its widest line plus paddings (at least 180 dp, the content centred; text column at most
  480 dp); longer text wraps with balanced lines (title up to 2, line up to 3) and the card grows in height, the
  tile centred against the text. An ellipsis only past those limits. Wrapped lines take their real width (no
  empty band at the end). End padding 16 dp (was 28), as the start. No new string, no animation, no blur.
- Checked with the card render harness on the test emulator (1920 x 1080): every state card and banner in
  English, Russian and German, longest and shortest names, no ellipsis.

## 1.0.3 (versionCode 104), Lumen OS 1.0.3 (2026-10-13)
- Build: `VERSION_CODE=104 VERSION_NAME=1.0.3`, sha256 92335a1299ffe0a6fd93e398d6ddf640dde54eab4bf46b78cd5f13e57d430333
  (test key), pinned in overlay/apps_v1/PINS.sha256 (was the 1.0.1 build 76c3afa5..., which 1.0.2 kept). The same
  sources built as 1.0.1 / 102 give the same classes.dex, and a second build gave the same bytes.
- It carries the three "After 1.0.1" sections above: live CEC device labels and the brand banner, one HDMI device
  state with the viewer's state cards, and cards sized to their text. The picture itself comes from Z9xProjector
  1.0.3 (IGmpf 698 follows this app's HDMI sessions; this app still makes no IGmpf call).
