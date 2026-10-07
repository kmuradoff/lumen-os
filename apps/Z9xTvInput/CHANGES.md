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
