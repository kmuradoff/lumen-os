# Z9xAirPlay changes

## 1.0 (versionCode 100), Lumen OS 1.0 review fix (2026-10-07)
- The advertised name follows the projector's name (PLAN C12, brand spec 8): `Prefs.name()` = the
  AirPlay-only name saved in our settings, else Settings.Global device_name (written by Lumen Setup's
  Done step and Android Settings) through `sanitizeName()`, else `default_device_name` ("XGIMI Z9X").
- AirPlayService observes Settings.Global device_name (ContentObserver, 500 ms debounce) and re-creates
  the receiver (new mDNS records) when the advertised name changed; a running mirror / audio session
  is never cut for a rename (retried every 30 s until it ends).
- Settings: saving a name equal to the device name clears the AirPlay-only override (follow the device).
- Build: VERSION_CODE=100 VERSION_NAME=1.0 build_apk.sh, sha256 7ac6f9fbf8e5254bbcc0287c6b434956b0589bac632e122d8ac6c213d07783b4, pinned in overlay/apps_v1/PINS.sha256.
  Native library unchanged.
