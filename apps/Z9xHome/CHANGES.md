# Lumen Home 1.0 (org.z9x.home), lane L-HOME, wave 1

New app: Lumen OS launcher in Google TV style (home/SPEC.md + PLAN.md C1-C4, C7, C13, C15, C17).
versionCode 100, versionName 1.0. Author kmuradoff.

## Build and checks

```
cd gsi/apps
sh build_apk.sh Z9xHome                  # -> Z9xHome/Z9xHome.apk (~0.4 MB, platform test key)
sh Z9xHome/tools/run_tests.sh            # JVM tests: IntentGuard (T12), Ranker, Snapshot: 48 pass
python3 Z9xHome/tools/i18n_local.py --res  # validates 16 locales with gen.py rules, writes pending + res
sh common/sync.sh --check Z9xHome        # GeoIp.java + tokens copies are byte-identical to apps/common
python3 Z9xHome/tools/gen_icons.py       # regenerates res/drawable/ic_*.xml, wx_*.xml (own drawings)
```
Release signing is not done here: `sign_tar.py` (L-OTA) re-signs it with the platform release key.

## What is in it
- **For you**: hero (Watch Next "continue" first, then round robin over channel rows, feature
  slides when there is no content), rows (Your apps, Continue watching, app channels, Inputs,
  Projector), Customize pill. Browse mode keeps the focused row at y 120; the top bar fades out.
- **Apps**: Favorites + A-Z grids, "Get more apps", move mode for favorites.
- **Inputs**: HDMI inputs (C15: CEC child inputs grouped under their port, "PlayStation 5 / HDMI 1"),
  cast cards (Chromecast built-in, AirPlay how-to), projector tiles.
- **Search** (`.search.SearchActivity`, stays enabled in classic mode): apps (with Cyrillic/Latin
  transliteration), TvProvider titles, suggestion providers (GLOBAL_SEARCH), "Search in" chips
  (YouTube URL, ACTION_SEARCH apps), recent searches. Voice via the system recognizer
  (voice_recognition_service, never katniss); offline-language failure retries online once; other
  failures fall back to the keyboard and are remembered 24 h.
- **Customize**: rows on/off (writes channel `browsable`), row order, favorites, hidden apps,
  weather (on/off, city search, units), Spotlight (auto-advance, trailers OFF by default),
  Home screen (Lumen Home / classic Android TV via the projector), About.
- **Weather by IP**: shared GeoIp chain (apps/common) + Open-Meteo, keyless, only while Home is visible,
  never while the projector sleeps; https only except the ip-api.com fallback.
- **TvProvider plumbing** tvrecommendations used to provide: INITIALIZE_PROGRAMS (first run, new
  packages, after a switch back from classic), REQUEST_CHANNEL_BROWSABLE activity and
  CHANNEL_BROWSABLE_REQUESTED receiver (auto-approve, ownership checked), Watch Next / preview removal
  with the BROWSABLE_DISABLED broadcasts.
- **Speed/RAM**: views built in code, snapshot first frame (files/snapshot.bin, APK- and locale-stamped),
  HARDWARE bitmaps at card size from a 64 MB disk master cache (one download per URI), 22 MB memory
  LRU, art released on stop/trim. Emulator check (software GL): first frame ~0.5-1 s cold, Java heap
  ~3 MB, total PSS ~40 MB.
- **Safety**: IntentGuard (no selector, no grant flags, exported + permission check against the row's
  app, foreign targets only as browsable VIEW deep links), art URIs only https/own content/own
  resources, no file://, sources > 4096 px refused; crash-loop safe mode (3 crashes in 60 s); no HAL
  access at all (everything hardware goes through the projector's whitelisted receivers).

Verified on a throwaway local emulator (phone image at 1920x1080, data injected only in a scratch
copy): all three tabs, browse mode, hero, context menus, Customize, city search, search, voice
fallback, classic-mode search, Russian and Arabic (RTL). Not verified: anything that needs the Z9X
(TvProvider content, CEC, priv-app grants, projector receivers, real voice, D-pad remote timing).

## Contracts other lanes must implement (hooks)

**L-PROJECTOR (Z9xProjector)**
1. Mic key (C4): `uses-permission org.z9x.home.permission.MIC`. On KEYCODE_SEARCH DOWN (repeat 0, setup
   complete, interactive): `startActivity(new Intent("org.z9x.home.action.VOICE_SEARCH")
   .setPackage("org.z9x.home").putExtra("held", true).putExtra("down_uptime", event.getDownTime())
   .addFlags(NEW_TASK | NO_ANIMATION))`. On UP: `sendBroadcast(new Intent("org.z9x.home.action.MIC_UP")
   .setPackage("org.z9x.home"))`. Screen off / not interactive: same intent with `held=false` (search
   opens without listening). A tap shorter than 400 ms is tap-to-talk (the recognizer end-points).
   Recents' non-type-5 ASSIST: `Intent("android.search.action.GLOBAL_SEARCH").setPackage("org.z9x.home")`.
2. `SET_LAUNCHER` broadcast receiver (C1): action `org.z9x.projector.action.SET_LAUNCHER`, permission
   `org.z9x.projector.permission.SET_LAUNCHER`, extra `mode` (also sent as `target`) = `z9x|classic`.
   Z9X set = components of org.z9x.home: `.HomeActivity`, `.AllAppsAlias`,
   `.tvp.BrowsableRequestActivity`, `.tvp.BrowsableRequestReceiver`, `.tvp.PackageReceiver`. Never
   disable the package, `.search.SearchActivity`, `.search.VoiceSearchAlias` or `.search.MicUpReceiver`.
   HOME priority here is 3 (C3).
3. `SHOW_PANEL` receiver: action `org.z9x.projector.action.SHOW_PANEL`, permission
   `org.z9x.projector.permission.SHOW_PANEL`; extras `section` = keystone|picture|sound|eye|general,
   or `action` = autofocus|lamp_standby|sleep_timer; no extra = the quick-panel grid (top-bar gear).
   `lamp_standby` is labelled "Sleep" in Lumen Home: under the v1 power rules it is the projector's
   sleep (lamp off + STR, or lamp-only standby while USB is connected).
4. Existing, used read-only: `SHOW_SOURCE` (top-bar input button), `standbystate` provider
   `call("standby_state")`, broadcast `org.z9x.projector.action.STANDBY_CHANGED` (extra `standby`) sent
   with permission STANDBY_STATE (Lumen Home registers with that broadcast permission).
5. "Home screen" item in Projector settings / quick panel to switch back from classic (C1).

**L-SYSTEM (image)**
- `/system/priv-app/Z9xHome/Z9xHome.apk` (0644), `image/privapp-permissions-z9xhome.xml` ->
  `/system/etc/permissions/`, `image/default-permissions-z9xhome.xml` ->
  `/system/product/etc/default-permissions/` (RECORD_AUDIO), bump `ro.build.version.incremental`.
- C6: merge `image/sysconfig-z9xhome.snippet.xml` (`initial-package-state org.z9x.home stopped=false`).
- C10: dexpreopt with `--dex-location=/system/priv-app/Z9xHome/Z9xHome.apk`, oat in that dir.
- i18n W2: add `"home": APPS + "/Z9xHome/res"` to gen.py RES and append
  `apps/i18n/pending/L-HOME/<locale>.txt` to `langs/<locale>.txt` (en.txt is the base, for reference).
  The `res/values-*` files already in the app are exactly what `gen.py --write` produces.
- Branding: user-visible name "Lumen Home" / "Lumen OS"; internal ids stay org.z9x.*.

**L-OTA (updater)**: badge contract (C17): provider authority `org.z9x.updater`, uri
`content://org.z9x.updater/state`, protected by signature permission `org.z9x.updater.permission.OTA_STATE` (W2: aligned with Z9xUpdater)
(Lumen Home holds the uses-permission). Lumen Home calls `call("state", null, null)` and reads Bundle
key `state`; if that fails it queries the uri and reads column `state`. Values `ready`, `downloaded`
or `reboot_required` show the "Update ready" chip, whose OK starts
`Intent("android.settings.SYSTEM_UPDATE_SETTINGS").setPackage("org.z9x.updater")`.

**L-SETUP**: copy the shared sources with `sh apps/common/sync.sh Z9xSetup` (tokens -> res/values/
z9x_tokens.xml, GeoIp -> src/org/z9x/common/GeoIp.java). `GeoIp.lookup(timeoutMs, ua)` returns
city/region/countryCode/timezone/lat/lon/source; add the Gservices `ip_timezone` fallback on top.
If Setup uses the ip-api.com fallback it needs the same cleartext domain-config as Lumen Home.

## Shared files owned by this lane (apps/common)
- `tokens.xml`: brand colours (C7, brand/SPEC.md section 9 plus live/ok/surface3/glows).
- `GeoIp.java`: keyless IP geolocation chain ipwho.is -> ipapi.co -> ip-api.com, 6 h back-off on
  429/403 (C13).
- `sync.sh`: the copy rule (`--check` for CI).

## Files
- `AndroidManifest.xml`, `res/` (strings + 16 locales, themes, ~57 own vector icons, network config),
  `src/org/z9x/home/**` (51 Java files, ~10.7 k lines), `src/org/z9x/common/GeoIp.java` (copy), `test/**`,
  `tools/` (gen_icons.py, i18n_local.py, i18n_home_{a,b}.py, run_tests.sh), `image/` (XMLs for L-SYSTEM).

## Known limits / open
- Real publisher coverage of TvProvider rows on this device is unknown until the device test (A4); the
  per-package log line `Z9xHome: tvp pkg=... ch=a/b prev=n wn=m` answers it.
- YouTube's `results?search_query=` deep link and Russian recognition need the device test (T10).
- No notifications row (Q6, later via OTA).

## Review fixes (2026-10-07, round 3)
- Search keyboard: the image's only IME (LeanbackIME) types Latin only. With a Cyrillic UI language
  (ru, uk, be, kk) and no enabled IME subtype for it, search uses its own on-screen keyboard
  (`search/TvKeyboard`: native layout, Latin, digits/symbols, space, backspace, Search; D-pad driven,
  modal like an IME, BACK hides it, opens at once for keyboard search). For any non-Latin UI language
  without a keyboard (also zh, ja, ko, ar) a "Keyboard" row offers "Get a keyboard for <language>"
  (Gboard's Play page), which then also types in Settings.
- Push-to-talk: MicUpReceiver remembers the release time (extra `up_uptime`) even before the search
  screen exists; a press already released when the screen opens, or a tap shorter than 400 ms, opens
  keyboard search with "Hold the mic button while you speak" instead of listening to silence (the
  remote streams audio only while the key is held). The 400 ms tap-to-talk rule is gone.
- 4 new strings (voice_hold, kb_title, kb_get_app, kb_no_play) in 17 locales (i18n/pending/fix3, merged).
- Build: 1.0 (100), sha256 e2e29bb362f573d9c61441dee1fbd73ded18e487e676a57df0832733ade881c1 (test key).

## Review fixes, round 4 (2026-10-07)
- SearchActivity also handles `android.speech.action.WEB_SEARCH`, `android.intent.action.WEB_SEARCH` and
  `android.intent.action.SEARCH_LONG_PRESS` (DEFAULT; keyboard entry, SearchManager.QUERY prefilled):
  with Katniss gone they no longer end in ActivityNotFoundException or on TvFrameworkPackageStubs'
  "not supported" stub (priority -1).
- VoiceInput: only permanent failures are remembered for 24 h (no recognizer, RECORD_AUDIO not
  grantable, language not supported even online). ERROR_INSUFFICIENT_PERMISSIONS or a failed start: 5 min.
  SERVER_DISCONNECTED (recognizer process killed, e.g. lmkd during a 4K film), CLIENT, RECOGNIZER_BUSY:
  keyboard for this press only; after SERVER_DISCONNECTED the SpeechRecognizer is recreated.
- Tap-to-talk extra `tap_voice` (from org.z9x.projector for the classic launcher's voice orb): listen at once.
- Build: 1.0 (100), sha256 cdf42a8153dcba2fa3ec882fdca0983540e73e9abb4433c159d42767f9482180 (test key).
