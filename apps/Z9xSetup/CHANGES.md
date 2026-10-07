# Z9xSetup (org.z9x.setup): Lumen OS first-run setup

## 1.0 (versionCode 100), lane L-SETUP, W1 (2026-10-07)

This is a new app. It implements `research/v7/setup/SPEC.md` and the binding PLAN rules C1-C3, C6, C12-C14, C17, C22, C23 and C25. The Alice step stays hidden, per the user's decision.

**App shape**
- One HOME activity (`.SetupActivity`, priority 10, no SETUP_WIZARD category). Steps are Views inside it. The app has no receivers, services or jobs.
- Install path: `/system/priv-app/Z9xSetup` (priv-app only, so the HOME priority of 10 is not capped). It is platform-signed.
- Plain Java and Views: no AndroidX, no bitmaps. The QR code, illustrations and launcher previews are drawn in code.
- APK size is about 210 KB. PSS measured on an Android 15 TV emulator was about 34 MB (budget 45).

**Step order**
1. Welcome + language: 17 native names, plus "More languages…".
   - `LocalePicker.updateLocales` applies the language without an activity relaunch; the strings re-bind and RTL is handled.
   - The pre-selected language is the current system locale (English on fresh data; C25 is owned by the image lane).
2. Network: our own Wi-Fi list and password/hidden-network pages.
   - Handles PSK, SAE, OWE and open networks. Wrong password, 30 s timeout, captive portal, no internet and Ethernet are all handled.
   - "Other options" opens the TvSettings Wi-Fi screen.
   - The IP time zone is fetched in the background. With C23, it writes `auto_time_zone=0` when it applies the zone.
3. Remote: the state comes from the SetupBridge (1 Hz poll plus the SETUP_STATE broadcast). The step is skipped when a remote is already connected, and auto-advances when one connects. There is no pairing code in this app.
4. Google (optional): `AccountManager.addAccount("com.google")`.
   - `device_provisioned=1` is set first.
   - After a failure the user gets Try again / Skip, plus "Problems signing in?" (GSF ID and a QR code to google.com/android/uncertified).
5. Picture: a full-screen grid and a card.
   - Buttons: af_run, kst_auto, kst_fit (only if curtainFit), kst_manual, focus_manual. Switches: POWER_ON_AF and MOVE_AK.
   - Each is a named bridge method; this app reaches no HAL code.
   - Busy/ready comes from hal_state. After 20 s without the HAL, the step says "later".
6. Home screen: Lumen Home (recommended) or classic Android TV. The choice is applied at Finish.
7. Alice: hidden unless the bridge's `feature("alice")` returns enabled + url.
8. Done: a summary with Change for the time zone and the projector name (picker per C12, via bridge `set_device_name`).
   - Shows the "protected video components missing" line when `sys.z9x.blobs` is set and not `ok` (C17).
   - Footer: "Lumen OS <ro.z9x.version> · by kmuradoff".

**Finish (idempotent, logged)**
1. Persist `state=finishing`.
2. Bridge `set_launcher`. No local copy of the switch (C1). If the bridge fails twice, the app only records `Settings.Secure z9x_launcher`, which LauncherSwitcher.ensure() applies at the next boot.
3. Set the three setup flags.
4. Send SetupWraith's two broadcasts.
5. Disable SetupWraith's MainActivity (belt and braces).
6. Disable our own activity, start HOME, and kill the process after HOME draws.

**Robustness**
- Re-entry rules from SPEC 4.10:
  - an interrupted finish runs again headless;
  - flags=1 with the launcher not applied shows the launcher step only;
  - flags=1 with the launcher applied runs only the tail of the finish.
- After a power cut, setup resumes at the stored step, or the nearest step that is available.
- Crash guard: 3 uncaught exceptions in one boot within 3 minutes trigger a safe finish. Low-memory kills and power cycles do not count.
- Holding BACK for 8 s shows "Skip setup?", then a safe finish.
- adb rescue on the device: `am start -S -n org.z9x.setup/.SetupActivity --ez safe_finish true`. It is honoured only for shell/root.
- Test hook (T13): on a userdebug build, `debug.z9x.setup.crash=1` makes onCreate throw.

**Shared sources**
- `res/values/z9x_tokens.xml` and `src/org/z9x/common/GeoIp.java` are copies made by `apps/common/sync.sh` (C7/C13); never edit them here.
- `net/TzLookup` adds setup's Gservices `ip_timezone` fallback on top of the shared chain.

**i18n**
- The English base is `res/values/strings.xml`, generated from `apps/i18n/pending/setup/en.txt`.
- All 16 translations are in `apps/i18n/pending/setup/<locale>.txt`. They pass gen.py's checks and compile with aapt2.
- W2 must add `"setup": APPS + "/Z9xSetup/res"` to gen.py `RES` and append the `@setup/strings.xml` sections to `langs/`.

**Permissions** (all granted by the platform signature)
- Beyond the spec sketch, two were found missing on the emulator: WRITE_SETTINGS (`updatePersistentConfiguration` also enforces it) and READ_WIFI_CREDENTIAL (`getPrivilegedConfiguredNetworks`).
- MANAGE_ROLE_HOLDERS is dropped (C1).

**Build:** `sh gsi/apps/build_apk.sh gsi/apps/Z9xSetup`. The version comes from the manifest.
