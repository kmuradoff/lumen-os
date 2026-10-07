# Z9X v6.1: requirements from the user (2026-10-03, after testing v6 on the projector)

The source is the user's own words, collected in chat. Every item is mandatory unless marked "optional".

## UI/UX: everything like stock XGIMI, light and fast
1. **Quick settings over the video** (XGIMI style), not a separate full-screen app.
   - A side panel is drawn over the running video or app.
   - The user changes settings while still watching.
   - It opens from the remote (gear key, see the key table).
   - It must look and feel like stock XGIMI.
2. **Every remote key that opens something shows a nice overlay** with a choice inside it, also over the video.
   - Source/input key → overlay with the inputs: HDMI 1 (ARC), HDMI 2, «Главный экран»/Android TV. Choose with the D-pad.
   - Mic key → Google Assistant (it already has its own overlay).
   - The other app/feature keys get the same treatment where a choice makes sense.
3. **Light and fast:** no heavy libraries, overlays appear instantly, no lag on the D-pad.
4. **Smooth keystone / fit-to-screen.** On stock the picture visibly glides into the screen frame instead of jumping. Reproduce that animation.
5. **All stock XGIMI features**, including:
   - picture modes, including the **AI colour / AI picture mode**;
   - game mode and 120 Hz (as far as the Z9X supports them);
   - sound modes (Harman, etc.);
   - focus, keystone, fit, obstacle avoidance;
   - eye protection, auto brightness, projection mode.
   - Reimplement in our own code over the vendor HAL. Do not ship XGIMI APKs.
6. **Multilingual UI:** all our apps (Проектор, Источник сигнала, overlays) translated into many languages. At least en, ru, uk, be, kk, de, fr, es, it, pt, pl, tr, zh-CN, zh-TW, ja, ko, ar. **English is the default** (base `values/` = English, `values-ru/` etc. are translations). The user writes in Russian, but the product default is English.
7a. **System default language English as well:**
    - `ro.product.locale=en-US` in product build.prop.
    - The one-time first-run override sets `persist.sys.locale=en-US` instead of ru-RU. Stock data carries zh-CN.
    - The user picks their language in the first-run setup.
    - Time zone: keep the one-time Europe/Moscow override; setup/check-in corrects it by IP.

## Power
7. **Short press POWER:** the lamp must turn OFF (not just a black screen). Waking must be quick and must not cold-boot.
8. **Long press POWER → «Выключить»:** a real power-off. Today it reboots.

## Sound
9. **UI click sounds follow the system volume.** Today they play at full scale: the vendor system/SPEAKER curve is FULL_SCALE.
   - Sound effects are temporarily disabled on the device; re-enable them after the fix.

## Remote
10. **Auto-pair the XGIMI BLE remote with an LE scan**, also during the first-run setup. The remote advertises LE *limited* discoverable, so the stock Android scanners never show it. Proven by org.z9x.btpair.

## Network / first run
11. **First-run reset also deletes the stock /data/system/netpolicy.xml.** Its stale REJECT_ALL uid policies combined with `restricted_networking_mode=1` cut Google apps off the network.
    - Also make sure `restricted_networking_mode` ends up 0.
12. **Avoid the one-time SettingsProvider crash** on the first boot after the settings reset (legacy ssaid upgrade NPE).

## Remove
13. **Remove the feature kill switch completely.**
    - The features are always on: BT LE privacy off, 30 s audio standby, the HAL handshake.
    - No lastboot/strike/featblock logic.
    - The logger may stay as a passive log with no effect on behaviour.

## Not possible (explained to the user)
- **Dolby Vision:** the vendor display reports only HDR10, HLG and HDR10+, the same as stock. There are no DV decoders.

## Decisions after research (2026-10-03 evening)
Sources:
- scratchpad research/v61/RESULT_{power,quicksettings,display}.json (verdict.corrected_decisions win);
- research/v61/inventory/FEATURE_SPEC.md;
- research/v6/RESULT_*.json.

- **Power, short press:**
  - org.z9x.projector PowerPolicy. On SCREEN_OFF: if IGmpf 196 getScreenOnOff() is true, call 195 setScreenOnOff(false); the lamp goes off at once, as on stock.
  - On SCREEN_ON: if no STR happened (suspend_stats/success unchanged), call 195(true) after 300–500 ms. After a real STR resume, poll 196 every 1 s, up to 7 times, before forcing it on.
  - STR resume currently cold-boots. So after SCREEN_OFF hold a partial wakelock for a "quick wake" window (default 30 min, set in the panel) and then release it so STR may happen. Investigating the STR resume is a later task.
- **Power, long press:** like stock, sleep instead of a real power-off. Real shutdown auto-boots after 20–40 s, and stock never offered it.
  - Power menu: `config_globalActionsList=['restart']` via the framework RRO. config_shortPressOnPowerBehavior via RRO only if verified.
  - Review fix (2026-10-04): `config_longPressOnPowerBehavior=0` (LONG_PRESS_POWER_NOTHING, as stock) in the framework RRO, so a held POWER sleeps on release (lamp off via PowerPolicy) and no menu appears; the restart-only menu stays as defence in depth. `config_shortPressOnPowerBehavior` is NOT overlaid (live value 1 kept) until a device test verifies 2. Device test still open: a 2 s hold turns the lamp off, no menu.
  - Never call IGmpf 241/422 at shutdown. No forceSuspend.
- **Dolby Vision:** impossible (SupportHashkeyMode=8 SKU). Ship the TvSettings RRO with config_deviceSupportedHdrFormats=[2,3,4] so DV disappears from the menu. Never fake DV.
- **120 Hz / game mode:**
  - Ultra 120 Hz: IGmpf 161 setOutputTiming(4)/(6), read via 157. Refused while 3D is on or the HDMI input is ≥62 Hz.
  - HDMI game mode: IGmpf 648 (0..3); "максимальная скорость" also calls 207 correctKeystoneReset(0), as stock does.
  - ALLM: IGmpf2 312. VRR: IGmpf2 301 — not offered; 300/301 removed from Gmpf2Client until a device test fixes the state values (review fix 2026-10-04). HAL methods without a reviewed caller are parked (not compiled); `check_hal_callers.py` (run by build_apk.sh) fails the build otherwise.
  - Game-mode readback 55: skip; show the last value we set.
- **Auto keystone overlay (P0):**
  - Events 106/107/109/113/114/115/116/118/110 arrive via focusEvent; ack each one with IGmpf 146 uiAkDisplay(type).
  - Animate the corners with setPolyToPoly, 500 ms.
  - Test pattern generated at runtime from the device's /mnt/vendor/xgimiconfig/public/AK/config.yaml. Never ship XGIMI's PNG.
  - 8 s watchdog.
  - Known gap (review 2026-10-04): steps with the value `AK_Ui_Mode_Detect` (107 for vendor trigger 13, 118 at the end) need the stock env-monitor pattern, whose geometry is not verified. They are NOT acked (the vendor times out after 5 s and takes its no-UI branch); the overlay is hidden at once so the 106 curtain does not stay up for 8 s. Before shipping: check on the device (logcat -d, tag Z9xAk) which trigger sends Detect. Once the geometry is verified, render it and ack 107/115/118 Detect like the normal steps.
- **Quick panel:**
  - TYPE_APPLICATION_OVERLAY (or INTERNAL_SYSTEM_WINDOW) on the right side, 60 s auto-hide, XGIMI visual style per FEATURE_SPEC.
  - Opened by a short press on the gear key. That needs a keycode that reaches global_keys (SETTINGS is consumed by Lineage); a long press opens TvSettings.
- **Source key:** an overlay with the input chooser.
- **Notifications:** one shared XGIMI-style card for all of them (top-right, 468x156 dp, #FA292929, radius 36, slides in over 500 ms, held 5.5 s).
  - org.z9x.tvinput sends its messages to the card through org.z9x.projector's NotifyRequestReceiver (signature permission `org.z9x.projector.permission.NOTIFY`); a Toast only if the projector app is missing.
- **Eye protection runtime (review fix 2026-10-04):** module `eye.EyeGuard` handles 502 (notice once per day), 503 (transparent key-eating layer, BACK sends 148(true)), 602/603 (layer removed; 603 notice). 148 only ever with TRUE.
- **Remote pairing (review fix 2026-10-04):** "Pair remote" in the quick panel (General) and in Projector settings starts the 120 s window. Automatic mode also re-pairs our own bonded remote when it advertises pairing mode (LE limited) and is not connected; it never bonds a new device while an XGIMI remote is bonded.
- **Arabic / RTL:** org.z9x.projector declares supportsRtl=true; the Canvas rows mirror in RTL (title right, values/switches/chevrons left, LEFT/RIGHT swapped on choice rows and sliders). Physical controls (manual focus, manual keystone) keep physical directions. Not yet seen on the device.
