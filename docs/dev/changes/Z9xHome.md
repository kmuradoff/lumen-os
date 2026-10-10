# Lumen Home 1.0.1, no-Google edition (2026-10-09): Install from USB

Author kmuradoff. Still 1.0.1 / versionCode 102; one APK for both editions (docs/NOGMS_PLAN.md section 8).
- **Apps > Install from USB** (`usb/UsbApks`, `usb/UsbInstallActivity`, `usb/UsbInstallReceiver`): the .apk
  files in the root, `apks/` and `lumen-os/` of every mounted removable volume (one level deep; .apks/.xapk
  not in 1.0.1), in the Lumen side panel with icon, version and New / Update; the list follows sticks being
  inserted and removed. OK streams the file into a PackageInstaller session with USER_ACTION_REQUIRED (the
  system installer always shows its confirm screen) and commits to the non-exported receiver (explicit,
  mutable PendingIntent): confirm screen, toast on success (Apps page refreshed) or with the installer's
  message. The tile (`ic_usb`) is on the Apps page in every edition, after Play's "Get more apps".
- Manifest: INSTALL_PACKAGES, REQUEST_INSTALL_PACKAGES, MANAGE_EXTERNAL_STORAGE (granted by the platform
  signature); `image/privapp-permissions-z9xhome.xml` + INSTALL_PACKAGES. Both new components carry
  `org.z9x.keep_in_classic` (search may open the activity).
- Search: without Play the keyboard hint opens Install from USB (`kb_no_store`); without any speech
  recognizer the mic is hidden and the mic key goes straight to the keyboard (`voice_none`); "no
  recognizer" is no longer remembered for 24 h (`VoiceInput.available` is asked at every start).
- Strings (18 locales): apps_install_usb, usb_title, usb_none, usb_empty, usb_new, usb_update,
  usb_installing, usb_done, usb_failed, kb_no_store, voice_none. Test: `UsbApksTest` (tools/run_tests.sh).

# Lumen Home 1.0.1, last owner wishes (2026-10-09): no header plate; 1080p / 2K / 4K UI

Author kmuradoff. Still 1.0.1 / versionCode 102. Owner screenshot logs/owner_<serial>/02c.jpg (the
1.0.1 Home with the dark capsule behind the time, search and settings); owner decision: the UI resolution
becomes 1080p / 2K (2560x1440, the default) / 4K, the same 960x540 dp (320 / 427 / 640 dpi).

## What changed
- **No plate behind the clock and the icons** (`ui/TopBar`, `ui/Scrims`, `ui/Stage`, `sky/SkyView`,
  `HomeActivity`, `ui/PillButton`, `ui/Glow`, `ui/PageApps`): the capsule is gone. Readability now comes
  from a **header scrim**: full width, ground colour at 74 % from the top edge down to y 140 (design px;
  every header item, the two-line clock included, ends at y 138), then eased out (smoothstep, flat at both
  ends, drawn as 10 dithered gradient pieces) to nothing at y 340. No edge anywhere: the steepest change
  is about 1.4 levels of 255 per design px, a linear fade of the same length has a visible line where it
  starts. It is drawn under the pages, by the sky in every state the sky shows (calm Home, browse mode,
  the Apps and Projector tabs under their veil; `SkyView.setHeaderScrim`, not in the screensaver) and by
  the stage over hero art (it replaces the follow-up's 62 % band; still under the framed card, which is
  never dimmed). Plus soft shadows: the tabs' labels and the search / settings icons
  (`PillButton.shadow`: a text shadow layer and a cached blurred ALPHA_8 copy of the icon, made once
  outside onDraw; the clock had its shadows already). Header pills fade (browse mode) without an
  offscreen layer, so the shadows are never cut into a box. Since the scrim is under the pages, the Apps
  tab now fades out the tiles and titles that would scroll under the header (as Home's rows do).
  Proof over a **pure white** backdrop, at every real pixel row of the header items in each mode
  (test/HeaderContrastTest, WCAG): time and icons 7.84:1, date 6.78:1, stale date and tabs 5.03:1,
  selected tab 5.30:1, update badge 5.47:1, focused item 17.7:1 (0.72 would leave the tabs at 4.65:1).
  The look: the top of hero art and of the calm sky is darker (a deeper "zenith" on a day sky), no box.
- **UI modes 1080p / 2K / 4K** (`ui/UiScale`, new, pure Java; `ui/Theme`, `img/HeroArt`,
  `img/ImageLoader`, `img/DiskCache`, `ui/Stage`, `ui/Glow`, `ui/CardView`, `ui/PillButton`,
  `sky/SkyRenderer`, `sky/SkyView`). The layout already was in design px (x real width / 1920 = half a
  dp), so it is the same at every mode; checked with each mode's rounding (test/ResolutionTest):

  | | 1080p @ 320 | 2K @ 427 (default) | 4K @ 640 |
  |---|---|---|---|
  | TV safe area (5 %) | 54 px | 72 px | 108 px |
  | "Continue watching" row / focused card ends at | y 740 / 1023 (<= 1026) | y 987 / 1365 (<= 1368) | y 1480 / 2046 (<= 2052) |
  | 16:9 row card art (decoded 1:1) | 384x216 | 512x288 | 768x432 |
  | hero 16:9 card box | 640x360 | 853x480 | 1280x720 |
  | art masters (disk) | 1920x1080 ("m2:", 1.0.1's) | 2560x1440 ("m2@2560x1440:") | 2560x1440 |
  | full-bleed hero: art of at least | 1536 px | 2048 px | none (every hero is a card) |
  | hero card may enlarge its art | never (1.0.1) | 1.25x | 1.25x |
  | decoded-art memory budget | 30 MiB (1.0.1) | 53 MiB | 64 MiB |
  | disk cache | 64 MiB (1.0.1) | 114 MiB | 114 MiB |
  | sky: one rendered minute | 8.0 MiB | 11.5 MiB | 21.7 MiB |

  - every rule about art uses **real pixels**: full-bleed only when the art covers the real screen with
    at most 1.25x (so at 2K the common 1920x1080 art is shown as the crisp card over its blurred copy,
    1.33x would be too much); masters stop at 2560x1440, so at 4K nothing fills (a 4K full-bleed bitmap
    would be 33 MB) and every hero is a card up to 1280x720 px decoded 1:1. A card never shows more than
    the art has at 1080p (as 1.0.1); at 2K / 4K it may enlarge up to 1.25x, so a small thumbnail keeps
    about its 1080p size on screen (640x360 art: 800x450 px, 94 % of its 1080p size at 2K) instead of
    shrinking to its own pixels; it is decoded at the art's own pixels and the card view scales it.
    Probed sizes are kept per master (a mode switch never mixes them). The first start at 2K / 4K
    fetches each picture once more for its 2560x1440 master;
  - **soft layers stay at 1080p's pixels** and the GPU enlarges them: the sky's gradient (960x540),
    clouds (410x160), fog (625x80), the 192x108 blurred art, the focus glows (`Glow.mask`, at most 10,
    about 1.4 MiB at every mode) and the icon shadows;
  - **crisp layers follow the real pixels**: the sky's land silhouettes (1920x620 / 2560x827 /
    3840x1240) and the moon (80 / 107 / 160 px, drawn 1:1 on whole pixels). At 2K the land layer starts
    on a whole pixel (y 613, not 613.33: drawn a third of a pixel off, the whole layer would be
    resampled); the film grain's specks stay one design px (filtered, not blocky). Stars, particles, the
    sun and the scrims are vectors, sharp anyway;
  - `Theme.px` uses `UiScale.px`; `Theme.scale()` / `Theme.softFactor()` are new; Home already rebuilt
    itself on a display size or density change (no process restart needed);
  - logs for the device: `Z9xHome: ui 2560x1440 dpi=427 scale=1.3333334` when Home builds, and the hero
    line now tells the mode: `hero art id=... src=1920x1080 ui=2560x1440 -> card16:9 853x480 #0`.
- **Memory estimate** (Home's own bitmaps at their most: image LRU full, the sky's shown set and its GPU
  copy, glows, the 16 blurred copies): 1080p about 49 MiB (peaks 57 while the sky renders its next
  minute), 2K about 79 MiB (peaks 91), 4K about 110 MiB (peaks 132). Not counted, outside the app: the
  window's own buffers, 3 x 7.9 / 14.1 / 31.6 MiB.

## Build and checks (last owner wishes)
```
cd gsi/apps
sh build_apk.sh Z9xHome                  # 0 errors (the 2 deprecation notes are SnapshotStore and TvKeyboard, as before)
sh Z9xHome/tools/run_tests.sh            # 189 pass (data: + ResolutionTest, HeroArt at 2K / 4K, header without plate
                                         # at 3 modes, and through the stage's crossfade), 23 pass (DreamDefault),
                                         # 20 pass (SkySizeTest, new), 760 pass (sky)
```
No new strings. Build: 1.0.1 (102), sha256 549cf80bbcec202c1ebc8dd0a2173f4571ad98d775a371b38c0d27831d276cbc (test key).

Not verified on the Z9X (no device in this lane), to check there, in each UI mode:
- the header over bright hero art (the white flag of "Холоп 3") and over a day sky with clouds: no box,
  the time, date, icons and tabs readable, no visible line where the scrim ends; focus on the clock (the
  light pill) and the update badge as before; browse mode and the Apps / Projector tabs (darker top
  under the veil); Apps tab with many apps: rows fade out before they reach the header;
- 2K (default): `ui 2560x1440 dpi=427`; text and icons crisp; hero logs with `ui=2560x1440`; a 1920 px
  art is a card 853x480 over its blurred copy, 2048 px art or more fills; "Великолепный век" card about
  its 1080p size; the sky's ridges and the moon sharp, grain not blocky; focus glows look as at 1080p;
- 4K: every hero a card (1280x720 at most); sky and focus animation smooth (gfxinfo); first focus of a
  card size without a hitch; the big calm clock (400 px glyphs, drawn as paths by Skia above 256 px);
- `dumpsys meminfo org.z9x.home` (Graphics + Native) within the estimates above;
- 1080p: everything as 1.0.1 except the header (scrim and shadows instead of the plate).

## Review fixes (last owner wishes)
- **Header readable during the stage's crossfade** (`ui/Scrims.headOverArt`, `ui/Stage`): when hero art
  faded to the sky (or in from it) the stage's header scrim faded with the art, over the sky's own scrim:
  over a white picture the tabs fell to 2.98:1 mid-fade. The stage now draws the header scrim at
  a / (0.26 + 0.74 a) of the art's share a: a white picture over a white sky stays exactly as dark as under
  the scrim alone at every step (tabs 5.03:1 throughout, test/HeaderContrastTest), smoothly 0 -> 1.
- **Apps tab: no half-transparent tile** (`ui/PageApps`): a tile's focus change (and the OK pulse) cancels
  its animator, the band fade in or out with it; UP, then LEFT / RIGHT / UP within 280 ms left the tile
  that had just faded in at a partial alpha for good (the focused one too). The band alpha is now put back
  after every focus change and pulse; a fading focused tile also gets no hardware layer, which cut its
  ring and glow for the fade.

## For other lanes (handoff, last owner wishes)
- **Display / integration lane**: Home reads only DisplayMetrics, no property: any of the three modes
  (or a fallback to 1080p) needs nothing from Home. 427 dpi gives 959.3x539.6 dp, not exactly 960x540
  (Home lays out by width, so it does not care; an app with `w960dp` resources would).

# Lumen Home 1.0.1, follow-up (owner report 2026-10-08 night: docs/BUGS_1.0.1_home_followup.md)

Author kmuradoff. Still 1.0.1 / versionCode 102. Screens: friend's 07_home (poster hero, right) and
08_holop ("Холоп 3": all five bugs), owner's 01_after_ota ("Великолепный век").

## What changed
- **Hero art by real pixels** (doc items 1 and 5; `img/HeroArt`, `ui/Stage`, `img/ImageLoader`, `data/TvpSource`,
  `data/Card`): a program now carries both of its pictures (poster art and thumbnail: `Card.image2` /
  `aspect2`); the stage reads each one's real size from its disk master (`ImageLoader.probe`: bounds
  only, `BitmapFactory.inJustDecodeBounds`, after the one download the cards share) and takes the
  larger. The layout follows the pixels, not the declared aspect:
  - **full-bleed** only for 16:9-ish art (1.6:1 .. 2:1) that covers the 1920x1080 backdrop with at most
    1.25x enlargement (a source of about 1600 px or more), cropped **from the top** (faces and title art
    kept; 1.0.0 centre-cropped, the owner saw the top of "Холоп 3" cut off);
  - **everything else** (smaller, portrait, square, 4:3, scope) gets the 07_home poster layout: the
    blurred, darkened copy full-bleed (192x108 blur, 0.8 over the ground, as 1.0.0's poster) and the
    picture itself as a crisp framed card at no more than its own pixels, drawn 1:1: 16:9 up to 640x360
    for landscape art, a 2:3 poster up to 340x510, a square up to 420 for art in between (album covers),
    end edge 220 px from the screen edge, centred between y 170 and 60 px above the first row (1.0.0's
    poster place). The card is drawn above the scrims (1.0.0 dimmed the poster's lower half by up to 63 %),
    with a 2 px paper hairline and its shadow. Nothing shown sharp on the stage is enlarged more than 1.25x.
  - hero art has its own memory keys ("hero:"; top crop, never enlarged), so it never mixes with the
    centre-cropped card art of the same size; the probe sizes are kept in memory (64 entries).
  - the optional trailer (off by default) plays full-bleed as before; next to a 16:9 or square card the
    card fades out while it plays and back when it stops; posters get no trailer (as 1.0.0).
  - log `Z9xHome: hero art id=... src=1280x720,640x960 -> card16:9 640x360 #0` tells the choice on the device.
- **Header readable over any art** (doc item 2; `ui/TopBar`, `ui/Stage`, `ui/Scrims`): the end-side cluster
  (update badge, search, settings, clock) sits on one plate of ground colour at 78 % (radius 28; 84 px
  high with the clock, 64 px around the icons on the calm Home's top, the clock alone in browse mode; it
  follows the fades). The 1.0.1 soft ellipse behind the clock is gone. Over the stage the start side
  gets a header band: ground 62 % down to y 140, gone at y 320 (was 50 % -> 0 over 280 px), which with
  D_Home's side scrim covers the mark and the tabs. Checked over a pure white backdrop
  (test/HeaderContrastTest, WCAG): time and icons 9.2:1, date 7.9:1, stale date 5.9:1, update badge
  6.2:1, focused item 17.7:1, tabs at 52 % of the width (past the longest tab row) 5.05:1, the selected
  tab 5.3:1.
- **TV safe area, 54 px from every edge** (doc item 3; `ui/Theme`, `ui/PageForYou`, `ui/RowView`,
  `ui/HeroView`, `ui/CalmView`, `ui/TopBar`, `ui/ListPanel`, `ui/PageApps`):
  - "Продолжить просмотр" at y 740 (D_Home 790): cards 792-1008, the focused card's scale and ring end
    at y 1023 (were 1058 / 1073); the hero's buttons sit 184 px above the row (556), dots at 666. A first
    row with taller cards (the user's row order: posters, squares) moves up until its focused card,
    ring and caption end 54 px above the bottom (`RowView.focusExtent`), the buttons and the art card
    with it;
  - rows and the "Customize Home" pill that the screen edge would cut are faded out until scrolling
    brings them in (at the top: the first row alone; before, the calm Home showed the next row's heading
    at y 1041-1071 and browse mode the cut bottoms of the previous row under the header). The fade uses
    a layer for its 280 ms only;
  - header line y 60-132 (D_Home 46-118), so the clock's two lines and the plate (y 54-138, also the
    clock's focus pill, which reached y 32 before) fit; the hero's text starts at y 150 at the earliest;
  - side panel (menus, Customize, weather): 64 px on the screen-edge side (the focus pill 56 px, was
    32 px), focused items never below 64 px from the bottom;
  - Apps tab: the focused card with its name stays 54 px above the bottom edge.
  - left as they are, on purpose: backgrounds (sky, art, scrims) run to the edges, and a row with more
    cards than fit still lets the next card peek in at the right edge while it scrolls (the focused card
    is always at x 96 or, at the row's end, ends at x 1824).
- **"Продолжить просмотр на главной": Показывать / Скрыть** (doc item 4; `customize/CustomizeActivity`,
  `Prefs.K_CONTINUE_HOME`, `data/HomeRepository`, `HomeActivity`): in Customize right after "Обои",
  a page with the two choices and a line that says what hiding does. Default: show. Hidden, Home is
  always D_Calm (big Prata clock, date, weather over the living sky, then the apps row; inputs and
  projector rows below as before) and makes no TvProvider query at all: `TvpSource.off()` instead of
  the load (no hero, no "Continue watching", no app channel rows either, they come from the same
  provider), the TvProvider content observer is unregistered, and a snapshot saved while it was shown
  loses its hero and TvProvider rows before its first frame (`Ranker.dropTvp`). Untouched while hidden:
  INITIALIZE_PROGRAMS to new apps (a broadcast, so their channels exist when it is shown again) and the
  system's channel-browsable requests from apps (`tvp/BrowsableRequest*`).
- **Cast how-to without an entry point** (left over from the Inputs tab): the Chromecast / AirPlay
  cards (Row.ID_CAST) lived only on the Inputs tab, gone in 1.0.1; per the owner (no filler, no
  duplicates) no page brings them back. Checked: no row, page or key leads to a CAST card any more; `HomeActivity.openCastHowTo` and the
  CAST drawing in `CardView` stay (reachable only from a CAST card, crash-free), `InputSource.casts` is no
  longer called on every refresh (no package lookups for nothing; `HomeModel.casts` stays empty, the
  snapshot keeps the field). The strings cast_* / feature_cast_title stay in res (shared i18n set).
- Snapshot format v3 (Card.image2 / aspect2); older snapshots are ignored once (the APK stamp changes
  with every build anyway).
- **Strings**: 4 new (cz_continue, cz_continue_sub, cz_continue_show, cz_continue_hide) in all 17
  languages: `python3 tools/i18n_home_e.py [--write]` (gen.py's rules, gen.render(); pending files in
  `tools/i18n_pending_e/`).
- Review fixes: the side panel's subtitle takes up to 6 lines (was 3): cz_continue_sub needs 4 lines in
  11 languages in the safe area's 536 px (6 at font scale 1.3), and German cz_wallpaper_sub went from
  3 to 4 lines with the narrower panel, so their last sentence was cut off. Customize > row order keeps
  rows that are not listed (hidden with "Continue watching on Home", or empty) at their saved place, and
  "Continue watching" that was never ordered first: a reorder while it was hidden used to put it below
  the projector row once shown again.

## Build and checks (1.0.1 follow-up)
```
cd gsi/apps
sh build_apk.sh Z9xHome                  # 0 errors
sh Z9xHome/tools/run_tests.sh            # 96 pass (data: + HeroArt, header contrast, hidden mode, snapshot v3),
                                         # 23 pass (DreamDefault), 760 pass (sky)
python3 Z9xHome/tools/i18n_home_e.py     # OK: 4 new strings x 16 locales
```
Build: 1.0.1 (102), sha256 90542072738e07c53bbfda609f15136a430ea07b9e75820c103b2fb07a912ac2 (test key, after the review fixes).
i18n handoff simulated in a scratch copy of apps/i18n: merge_pending.py --write home-e adds the 4 keys to
16 langs files and gen.render() reproduces res/values-*/strings.xml byte for byte; gen.py --check-only has
no Home errors left (the 160 left are the Updater lane's missing why_* keys, not this lane's).

Not verified on the Z9X (no device in this lane), to check there:
- "Холоп 3" (and any Kinopoisk item with small art): blurred backdrop + sharp 16:9 card on the right, no
  stretching; log `hero art ... -> card16:9`; an item with art of 1600 px or more fills, top not cut;
  "Хитрый Койот" still the poster layout (07_home); "Великолепный век" per its real art size.
- the header plate over bright art (white flag of "Холоп 3"): clock, date, icons readable; the plate's
  look on the calm sky and in browse mode (shrinks to the clock, no jump), focus on the clock (light pill
  = plate's clock part), the update badge on the plate.
- keystone: the focused "Продолжить просмотр" card and its ring fully visible (bottom at y 1023), nothing
  of the next row at the bottom edge, the header (y 54+) not cut; the side panel's focus pill off the edge.
- Customize > "Продолжить просмотр на главной" > Скрыть: Home switches to the calm layout at once (no
  hero, no row), log `continue on home=false` and `tvp=off` in the refresh lines; Показывать brings
  everything back; after a reboot with "Скрыть" the first frame is calm too.
- the rows' fade in/out when scrolling (no stutter on the MT9681: one layer per fading row).

## For other lanes (handoff, 1.0.1 follow-up)
- **i18n**: copy `Z9xHome/tools/i18n_pending_e/*.txt` to `apps/i18n/pending/home-e/` and run
  `merge_pending.py --write home-e`; gen.py --write then reproduces Z9xHome's res files unchanged.

# Lumen Home 1.0.1 (org.z9x.home): owner feedback on 1.0.0 (2026-10-08)

Author kmuradoff. The manifest still says 1.0.0 / 101: lumen_checks.py wants one versionName for every
app, so the version bump to 1.0.1 is the integration lane's (handoff below).

## What changed
- **No Inputs tab** (`HomeActivity`, `ui/TopBar`): the header has Главная / Приложения / Проектор. The
  Inputs page is not built any more (TAB_PROJECTOR is 2, three pages, three tabs in the focus order);
  the quick panel and the remote's input key already list the inputs, and Home's own Inputs row stays.
  The strings tab_inputs / inputs_connected / inputs_cast stay in res (unused now, the i18n set is shared);
  the cast how-to panel code stays (no card leads to it any more).
- **The time in every Home state** (`ui/TopBar.ClockBlock`): the header clock is a Prata time (56 px, was
  34 px Onest) over "ср, 8 октября · 12°" (21 px, #E6DFD3), centred on the header line, top right. A
  soft ground-coloured ellipse behind it (0.62 behind the digits, gone at the rim, dithered) and text
  shadows keep it readable over bright hero art, the sky or the rows; on dark ground they do not show.
  The block fades without an offscreen layer (`hasOverlappingRendering` false): a layer is cut to the
  view's bounds, so the ellipse would show as a hard-edged box during every fade.
  It is shown everywhere except the calm Home's top, where the big clock is: also on the hero, in browse
  mode (only the mark, tabs and buttons fade out now; on the calm Home the header time fades in as the
  big clock scrolls away) and on the other tabs. 24 h / 12 h follows the system setting (TextClock, AM/PM
  dropped as on the big clock), the date follows the activity's TIME_TICK. The TextClock has a fixed
  width (the widest time of its format: Prata has no tabular figures), so a new minute never moves the
  buttons or lays out the window; the header and calm dates only request a layout when the text really
  changes (1.0.0 laid out the whole window on every minute tick). Search and settings never jump while
  visible: they move while faded out and the clock crossfades over them. The hero's stage also gets a
  light top scrim (50 % -> 0 over 280 px).
- **Sky paused off Home** (`HomeActivity.updateSky`, `SkyView.setStill`): no motion and no minute render
  while the Apps or Projector tab is shown (it keeps its last frame under the veil); it runs again on
  Главная (and only while Home is resumed, as before). A tab opened from outside after the sky freed its
  bitmaps (Home hidden for 20 s, then the four-diamond key) gets one frame, faded in, then it stands
  still: the veil never sits on the bare ground colour where the dimmed sky belongs.
- **Crispness** (the owner saw a slightly soft Home; part of it is the vendor 1080p -> 4K scaler):
  - card art is decoded at the card's own pixels (`CardView`, `Theme.artW/artH`): 1.0.0 decoded at the
    focused size (x1.06), so every unfocused card (nearly all of them) was shrunk with one bilinear tap
    (Android draws scaled bitmaps without mipmaps), which blurred banners, logos and posters. Now 1:1 at
    rest; only the focused card is zoomed. The Apps grid (264x149) no longer gets 288x162 art either;
  - app banners (640x360) and icons (fallback tiles, the 36 px provider badge, was decoded at 40 and
    drawn at 36) are reduced properly (`AppArt.drawFitted`: halved with exact 2x2 averages, then one
    filtered draw) instead of one bilinear tap at 0.45 or less, which made logos and icon edges ragged;
  - app banners and generated tiles are kept on disk as PNG (`ImageLoader`, `DiskCache`; were JPEG q88):
    JPEG halves the colour resolution (4:2:0) and rings around logos and the tile's name, and every
    start after the first showed that copy. A PNG tile is a few times the JPEG's size, still small next to
    the 64 MB disk cache (the "app:" keys change with the new card sizes anyway, so nothing extra is
    rendered);
  - the hero's backdrop is decoded at the screen's pixels (`Stage`, `ImageLoader`): the disk master now
    covers the screen (at most 1920x1080, was 1280x720), so full-bleed art is no longer stretched x1.5
    when its source is big enough. Hero art is never enlarged by the decoder (a small source stays small
    and the GPU stretches it, no extra memory). New master key "m2:": each piece of remote art is
    downloaded once more after the update; the old "m:" files go with the disk LRU. Memory LRU 22 -> 30 MB
    (a 1920x1080 hero is 8.3 MB of GPU memory, was 3.7);
  - scrims are dithered (stage, card scrims, clock): no banding in the long dark ramps on a big screen;
  - the moon sprite is drawn on whole pixels (1:1, was up to half a pixel off);
  - checked and left as they are: no layer is rendered at reduced resolution except the sky's gradient
    (half resolution, smooth, no edges; the land is full resolution), the clouds and fog (blurred by
    design); no RGB_565 anywhere; every scaled bitmap is drawn filtered; no text is drawn into a bitmap
    except the name on the generated app tile, now 1:1; translations at rest are whole pixels.
  - a display size or density change (a 4K UI mode, for one) now rebuilds Home at the new resolution
    (`onConfigurationChanged`; the activity handles screenSize / density itself, so 1.0.0 kept its 1080p
    layout and art sizes).
- **Screensaver default on upgraded projectors** (`sky/DreamDefault`, once per data partition, first
  start, io thread): SettingsProvider copies config_dreamsDefaultComponent into Settings.Secure only on a
  fresh userdata. screensaver_default_component that is unset, a known Android default (BasicDreams
  Colors, Backdrop, DeskClock) or a dream that is not installed becomes the living sky.
  screensaver_components is replaced only when it names nothing but such entries; empty stays empty
  (DreamManager falls back to the default, and Z9xProjector's one-shot still decides on a fresh device);
  an installed dream (Z9xProjector's clock, the user's pick) is never replaced. Needs
  WRITE_SECURE_SETTINGS (new uses-permission; platform-signed priv-app; added to
  image/privapp-permissions-z9xhome.xml); without it nothing is written and the next start tries again.
  Marker `dream_default_v1` in the "sky" prefs. Log tag Z9xSky, lines "dream default: ...".
- No new user-visible strings.

## Build and checks (1.0.1)
```
cd gsi/apps
sh build_apk.sh Z9xHome                  # 0 errors
sh Z9xHome/tools/run_tests.sh            # 49 pass (data), 23 pass (DreamDefault, new), 760 pass (sky)
```
Build: sha256 7230a9164fb59290feeb2355bc8f1d49acc64dc34fd873d9d682f91ec78ed2a3 (test key, reproducible).
Not verified on the Z9X (no device in this lane): the header clock's size and contrast over real hero
art at 10 ft, the browse-mode fade (the clock's ellipse soft through the whole fade, no box), art
sharpness after the cache refill (app tiles after a reboot: no JPEG fringes), the sky standing still on
the other tabs (log `Z9xSky`; Приложения by the four-diamond key after 20 s in another app: the dimmed
sky fades in once, then stands still), the screensaver values on the owner's upgraded projector
(`settings get secure screensaver_components` / `screensaver_default_component` before and after).

## For other lanes (handoff, 1.0.1)
- **Integration**: bump every app to 1.0.1 (lumen_checks.py VERSION_NAME) together; Home's manifest is
  left at 1.0.0 / 101 so the checks pass until then.
- **Image / L-SYSTEM**: copy `image/privapp-permissions-z9xhome.xml` to
  `overlay/v1/privapp-permissions-z9xhome.xml` (adds WRITE_SECURE_SETTINGS; privapp permissions are
  not enforced today, the list keeps a future "enforce" bootable).
- **Projector lane (decision)**: on the owner's upgraded projector the active screensaver is most likely
  Z9xProjector's clock (its one-shot at the 1.0 first boot). Home keeps it, since it cannot tell that
  automatic choice from the user's; making the sky the active screensaver there would be a
  DreamSettings.applyDefaultsOnce v2 in Z9xProjector, if the owner wants it.
- **System (4K UI)**: the Android UI is 1920x1080 and the vendor pipeline scales it to the 3840x2160
  panel; a native 4K UI is a display-mode / density change in the image (4x the GPU fill and buffer
  memory on the MT9681). Home follows such a change (rebuild, art at real pixels); its known 4K limits:
  the sky renders at most at 1080p and is scaled (render cap in SkyView, memory), masters stay <= 1080p.

# Lumen Home 1.0.0 (org.z9x.home): direction D "Кинозал + Свет" (2026-10-08)

versionCode 101, versionName 1.0.0. Author kmuradoff. Mockups: D_Home (something to continue),
D_Calm (nothing started), D_Mark 2A (mark), D_Live (living sky; the sky itself is the Sky lane's
`src/org/z9x/home/sky/**`, used here through its public API only).

## What changed
- **Living sky behind Home** (`HomeActivity`): `SkyView` is the full-bleed bottom layer. It gets the
  place and the weather Home already knows (manual city or cached IP location via `SkyEnv.location`,
  the shown Open-Meteo code via `SkyEnv.weatherFromWmo`; no extra network), the scene from
  `SkySettings.scene`, and its scrims: D_Home side + bottom (1, 1); D_Calm bottom 0.92 and a side scrim
  that follows the sun (0.35 at night .. 0.8 by day) so the big clock stays readable on a bright sky.
  It is paused whenever Home is not resumed, the projector is in standby, media is playing
  (`AudioManager.isMusicActive`, re-checked on every playback change), or the hero art covers it.
- **Layers**: sky -> `ui/Stage` (the hero's art full-bleed with the D scrims, 700 ms crossfades,
  portrait art as a poster over its blurred enlargement, the optional silent trailer full-bleed, the old
  art kept until the new one arrives, the sky after 2.5 s without art) -> a veil (0.72 over the sky in
  the rows below the hero, 0.68 on the other tabs) -> pages -> header -> side panel. The per-card
  ambient backdrop is not used by Home any more (`ui/Backdrop` stays for the search screen).
- **Header** (`ui/TopBar`, `ui/Mark`): the 2A mark (six facets around a hexagonal opening, warm core,
  exact D_Mark geometry, drawn from paths) + "lumen"; tabs Home / Apps / Inputs / Projector; update
  badge (only when an update is ready), search, settings (the projector quick panel, as before) and
  the clock block: time over "ср, 8 октября · 12°". The clock block is a focus stop while weather is
  shown (OK = the forecast panel). The separate input button is gone from the header (D_Home has
  none): the Inputs tab lists and opens the same inputs.
- **Home, D_Home** (`ui/HeroView`, `ui/PageForYou`): when Watch Next has something (newest first, art
  optional: the sky stands in) or apps publish preview programs: overline "YOUTUBE · ПРОДОЛЖИТЬ"
  (Watch Next type or the channel name), Prata title 132 px (104 px when it needs two lines), meta,
  progress bar and "осталось 6 мин", two lines of description, buttons "Продолжить" (the program's
  own intent through IntentGuard) and "Подробнее" (details panel: description, play, open the app,
  remove from Continue watching / hide, hide the channel). The buttons stay on y 606; the text grows
  upwards. Then "Продолжить просмотр" at y 790: 16:9 cards with title, app · time left and progress
  inside the art; moving along it moves the hero (250 ms dwell). Then Apps, app channels, Inputs,
  Projector, "Customize Home".
- **Home, D_Calm**: nothing to continue and no preview programs: no hero card, the big Prata clock,
  "Среда, 8 октября", "12°, облачно · Москва" (its own focus stop, OK = forecast), the apps
  shelf at y 752. The old feature slides (cast / picture / customize) are not shown any more: D has no
  filler.
- **Cards** (`ui/CardView`, `ui/Glow`): radius 20 (apps 18), focus scale 1.06, a 4 dp light ring just
  outside the edge, the warm accent glow behind (a blurred ALPHA_8 mask made once per size, tinted when
  drawn) and the elevation shadow. No clipToOutline any more: art is clipped by a path so ring and glow
  may draw outside the bounds.
- **Apps**: TV apps show their 16:9 banner (`PackageManager.getActivityBanner`, then
  `getApplicationBanner`); apps without one get their icon centred on a 16:9 card tinted from the icon,
  the name below (the art key gained "|d", so cached tiles are redrawn once). Shelf tiles 272x153,
  the Apps grid 264x149 in 6 columns; the name only under the focused tile. Favourites, move mode,
  hide, app info, uninstall work as before.
- **Inputs** (connected devices, cast) and **Projector** (the projector shortcuts; each opens the
  existing panel section or action through `ProjectorBridge`) are their own tabs with Prata headlines.
- **Look**: `res/values/colors_lumen.xml` (ground #0A0908, text #F4EFE6 / #C9C1B4 / #9D9587, accent
  #F2B26B; all text >= 4.5:1 on every surface, see the file), fonts Prata (titles, big clock) and Onest
  (UI, variable 'wght') from `assets/fonts/` when the image ships them, else the system serif /
  sans-serif (`ui/Theme`). Panels, Customize, search and safe mode use the same palette.
- **Customize > Wallpaper** ("Обои"): "New every day" or one of the scenes (`SkyView.sceneIds()` /
  `sceneTitle()`), stored with `SkySettings.setScene`; Home and the screensaver read it.
- **Screensaver**: `.sky.SkyDreamService` registered (exported, BIND_DREAM_SERVICE, DreamService
  action + DEFAULT, label `@string/sky_dream_label`). Making it the default is an image change (below).
- **Data**: Watch Next first in the natural row order; hero = up to 3 Watch Next items (any type) +
  preview programs round robin (art required), max 6, no filler. `Card.left` holds "6 min left" (meta
  keeps the total time, release year first for films); snapshot format v2.
- **TvProvider permissions** checked against this image's TvProvider.apk (Android 14):
  `READ_TV_LISTINGS` dangerous; `READ_EPG_DATA`, `WRITE_EPG_DATA` normal; `ACCESS_ALL_EPG_DATA`,
  `ACCESS_WATCHED_PROGRAMS` signature|privileged; the provider has no read permission and filters in
  code. The manifest already asks for all Home needs; `image/default-permissions-z9xhome.xml` now also
  pre-grants READ_TV_LISTINGS (the limited fallback), `ACCESS_ALL_EPG_DATA` stays in the privapp file.
- **Strings**: 17 new (tab_home, hero_resume, hero_more, hero_kind_next, hero_kind_watchlist,
  weather_line, wx_* x 9, cz_wallpaper, cz_wallpaper_sub) in all 17 languages:
  `python3 tools/i18n_home_d.py [--write]` validates with gen.py's rules, renders
  res/values-*/strings.xml with gen.render() and writes `tools/i18n_pending_d/<locale>.txt`.

## Review fixes (1.0.0)
- The header clock is hidden only while the calm Home page itself is shown (its big clock replaces
  it); the Apps, Inputs and Projector tabs keep the time in the header.
- The veil over the sky (rows, other tabs) no longer asks for an offscreen layer: one colour fill with
  alpha, redrawn with every sky frame.
- The calm weather line draws a dark copy of its icon on the light focus pill (the light weather
  glyphs disappeared there); the weather line and the mark reuse their FontMetrics in onDraw.
- "More info" panel: the subtitle skips an empty app label (no leading " · ").

## Build and checks (1.0.0)
```
cd gsi/apps
sh build_apk.sh Z9xHome                  # 0 errors
sh Z9xHome/tools/run_tests.sh            # 49 pass (Ranker: D row order, hero without filler; Snapshot v2)
python3 Z9xHome/tools/i18n_home_d.py     # OK: 17 new strings x 16 locales
```
Build: 1.0.0 (101), sha256 f0a60dfbaed9ee0ddc543ed238269a1a68bdee0c1e2409a74537c524852a2e05 (test key,
after the review fixes, with the Sky lane's sources as they are now; any later Sky change gives a new hash). The i18n handoff below
was simulated in a scratch copy of apps/i18n: merge_pending + gen.py --check-only pass and gen.render
reproduces res/values-*/strings.xml byte for byte.
Not verified on the Z9X (no device run in this lane): the sky's frame rate next to the rows, the
glow / ring look at 10 ft, Prata/Onest rendering on the device, TvProvider content, the dream as default.

## For other lanes (handoff)
- **Image / L-SYSTEM**: copy `image/default-permissions-z9xhome.xml` to
  `overlay/v1/z9x-home-default-permissions.xml`; `tools/lumen_v1.sh` asserts that file holds only
  RECORD_AUDIO: it must accept `['android.permission.READ_TV_LISTINGS', 'android.permission.RECORD_AUDIO']`.
- **Default screensaver**: framework-res overlay `config_dreamsDefaultComponent` =
  `org.z9x.home/.sky.SkyDreamService` (SettingsProvider seeds `screensaver_components` and
  `screensaver_default_component` from it on a fresh userdata), plus `config_dreamsEnabledByDefault`
  true. Existing installs keep their old value (BasicDreams is removed from the image): set both
  Settings.Secure keys once on upgrade (projector/setup, or an init script).
- **i18n**: copy `tools/i18n_pending_d/*.txt` to `apps/i18n/pending/home-d/` and run
  `merge_pending.py --write home-d`; then `gen.py --write` reproduces the res files byte for byte.
- **Fonts**: `assets/fonts/Prata-Regular.ttf` and `assets/fonts/Onest-Variable.ttf` (both SIL OFL, with
  their OFL texts) are present in the tree and packed into the APK; if the owner does not want them,
  removing the two files is enough (Theme / SkyFonts fall back to the system serif / sans-serif).
- **Sky lane**: `SkyView.setScrim` draws the side scrim on the left only; Home in RTL (Arabic) has its
  text on the right.

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

## 1.0.0 integration (2026-10-08)
- Icon and banner: `brand_icon_home` / `brand_banner_home` (the 2A mark, apps/common/brand, sync.sh);
  `ic_launcher` / `banner` stay in res/ but are no longer the application's.
- `.sky.SkyDreamService` carries `org.z9x.keep_in_classic`, so Z9xProjector's LauncherSwitcher keeps the
  screensaver enabled in classic-launcher mode (the comment above it already promised that). The image makes
  it the framework default dream (Z9xFrameworkKeysOverlay `config_dreamsDefaultComponent`); the active
  screensaver on a fresh device is still Z9xProjector's clock (its one-shot default), see overlay/v1/CHANGES.md.
- i18n: tools/i18n_pending_d merged into apps/i18n/langs (pending/home-d, merge_pending.py --write);
  gen.py --check-only OK and gen.py renders these res files unchanged.
- Fonts packed from assets/fonts (SIL OFL 1.1, OFL texts next to them): Prata-Regular.ttf sha256
  3b2b880737be3bda5f03554297b758516876157c88f9e3b3bae8fa1fc96a2c2c, Onest-Variable.ttf sha256
  966c5c29b4755da84b6854d5c21dd4eaa2420225d0e9874de602de176d4a9f31. Not committed to the public repo
  (docs/repo/publish_audit.py: FETCH).
- tools/run_tests.sh also runs test/sky/run.sh. Build: sha256 fa276f2dba8aff6e97451d5615c01e31ed99089973859cd3847eca6a289b5b1b (test key).

## 1.0.1 integration (2026-10-08)
- versionCode 102, versionName 1.0.1 (manifest). overlay/v1/privapp-permissions-z9xhome.xml is now a copy of
  image/privapp-permissions-z9xhome.xml (+ GRANT_RUNTIME_PERMISSIONS, WRITE_SECURE_SETTINGS).
  sha256 992bf34c55ec5400cc1acf8d87030147243b7cda3f607de6327005b75e0b9278 (test key).
