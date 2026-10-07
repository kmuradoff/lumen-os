# Z9X v6.2: requirements from the user (2026-10-04, after testing v6.1 on the device)

v6.1 status on the device:
- **Works:** short POWER (sleep, lamp off); quick panel over the content; brightness via IGmpf 176; the handshake; the AK pattern lattice matches the stock one.
- **Power-off problem:** «Выключить» from the power menu really powers off, then the projector powers itself back on within seconds.
  - On stock, power-off is a true standby: on AC plug-in it "warms up" briefly and goes back off, and the remote power key turns it on.
  - Something triggers the wake-up (user suspects BT; candidates: RTC alarm, missing vendor power-off flag/env, CEC, WoW, WoBLE).

## Mandatory
1. **Power-off must stay off** until the remote power key (or IR / keypad) is pressed, like stock.
   - Find the root cause. Read the stock `com.xgimi.shutdown` (in the live /vendor/priv-app; our bind mount hides it — read-only access via root, e.g. dd the vendor block device), libxgimi power-off code, env keys power_off_state / boot_wakeup_mode, and wake sources.
   - Design a safe fix. No guesses shipped without evidence. Prepare a device test plan; tests run only with the user present.
2. **Own POWER key handling (remote BT power):**
   - **Short press:** minimal, beautiful fade-to-black animation (~400 ms), then sleep (lamp off).
   - **Wake:** minimal, beautiful fade-in with a thin logo.
   - **Long press:** our own XGIMI-style power menu with exactly 3 items: «Power off» / «Restart» / «Sleep timer».
   - Sleep timer choices: 15 min, 30 min, custom time. When it expires, sound and light fade out, then sleep.
   - The IR remote power key stays the system POWER as a fallback. Approach: research/v6/RESULT_remote.json Plan B, STB_POWER WAKE routed to our app; verify.
3. **Quick panel redesign:** not one long list. A top row/grid of **icon tiles** (focus, keystone, brightness, picture, sound, input, projection mode, game mode, eye protection, all settings); each tile opens its section page. Own vector icons, XGIMI look, fast.
4. **Remove the launcher tiles** of «Проектор» (org.z9x.projector SettingsActivity) and «Источник сигнала» (tvinput picker) from the home screen. All functions stay reachable through the quick panel and the remote keys.
5. **HDMI device recognition:**
   - Identify what is connected (HDMI-CEC vendor ID / OSD name / device type via HdmiControlManager / HdmiDeviceInfo; fallbacks such as source signal timing / ALLM / content type).
   - If it is a game console (PlayStation, Xbox, Nintendo Switch, …), apply the game settings automatically:
     - game mode (top speed, low latency);
     - ALLM;
     - 120 Hz when the source supports it;
     - all with the stock constraints, e.g. top speed resets keystone.
   - Show a short, pleasant "Play" animation. Restore the previous settings when the console disconnects or the input changes.
6. **Screensaver** (DreamService): a clock + date, minimal and beautiful, at low lamp brightness while dreaming (restore afterwards). Must be very light on CPU, GPU and power.
7. **Animations everywhere:** minimal and beautiful (power, wake, play, panel open/close).

## Keep / do not touch
- The iQIYI and bilibili keys: keep their current behaviour.
- No test grids.
- English base strings plus the 16 translations (as v6.1), including all new strings.
- All v6.1 rules: whitelist HAL calls only, nothing that can crash gmpf_main, no factory/calibration calls, /system/app platform-signed, light code.

## Implementation notes for the game profile (v6.2 build, UNVERIFIED until tested on the device)
- **120 Hz for consoles.** A new Game-page toggle, «120 Hz for consoles», is on by default.
  - While a console profile is applied, it moves game speed 0 (Standard) to 1 (Standard + HFR).
  - The vendor switches the HDMI EDID by game option, so this should let the console offer 120 Hz.
  - Option 3 (Top speed) is never moved to 2, because 2 and 3 need the 207 keystone reset.
  - The previous option comes back on restore (compare-and-restore).
  - The device's support_hfr=true is VERIFIED (G0082 feature.xml).
- **Per-port snapshots.** Each HDMI port has its own snapshot slot. A console on HDMI 2 gets its profile even while the HDMI 1 restore is still waiting for HDMI 1 to be back on screen.
- **IGmpf2 55.** 55 is used only after a reading with 647 = 2 or 3 has established its field order. The order learned is persisted. Until then, the quick panel's persisted game mode is used.

### Device tests for the game profile (run with the user present, in this order)
1. **696 under TIF.** Connect a console on HDMI and open it through our input picker. In `logcat -s Z9xGame`, check for `gate ok ... 696=<1|2>`.
   - If you see `696=0 is not HDMI` three times, the profile refuses: you get the «Game settings are unavailable on this input» card and detection stops for that session.
   - In that case, the 696 gate needs a tvinput-based fallback.
2. **648(1) from 0.** Start with game speed Standard (647 = 0) and apply the profile.
   - Check the console's video settings: is 120 Hz offered?
   - In logcat, check the HPD and CEC churn: there should be no restore loop.
   - On disconnect, check that 647 reads 0 again.
3. **55 layout.** With game speed 2 or 3, check that logcat shows `55 = {..} ... layout ... learned`.
4. **Two consoles.** Put one console on HDMI 1 and one on HDMI 2, then switch between them. Both should get the cue. Each port's restore should run when that port is on screen again.
