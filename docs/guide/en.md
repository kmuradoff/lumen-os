# Lumen OS guide for the XGIMI Z9X

English | [Русский](ru.md)

How to use the projector on Lumen OS 1.0.4. How to install the system is in the
[install guide](../install/en.md).

## Remote

| Key | Press | Hold |
|---|---|---|
| Gear | the projector's quick panel | Android TV settings |
| Focus | autofocus | manual focus |
| Source | choose the input: HDMI 1, HDMI 2 or the home screen | |
| Keystone (lens), if your remote has it | the Keystone page of the quick panel | |
| Microphone | Lumen Home search | voice search while held (with Google services) |
| Four diamonds | all apps | |
| Ambient | the living-sky screensaver | |
| App keys (iQIYI, bilibili) | the app you gave them | |
| Power | sleep: the lamp goes off at once | menu: sleep, restart, power off, sleep timer |

Give the app keys any app in "Projector > Remote keys".

## Quick panel

The gear opens a panel of tiles. Each tile opens its page, Back returns to the tiles. The panel remembers
the last tile.

- **Focus**: autofocus now, manual focus, autofocus after moving and at power-on, smart autofocus.
- **Keystone**: auto keystone now, "Fit to screen now", manual keystone by corners, optical zoom,
  picture rotation, reset. Below: real-time keystone, keystone at power-on, fit to screen and obstacle
  avoidance.
- **Brightness**: levels 1-10. Press Right twice at 10 for Boost.
- **Picture**: picture mode, brightness, contrast, saturation, sharpness, color temperature, motion
  smoothing, AI contrast, super resolution, Ultra 120 Hz, professional settings, reset. While HDMI is on
  screen, the HDMI settings are here too.
- **Sound**: Harman Kardon or DTS Virtual:X, sound mode, equalizer, volume leveling, eARC.
- **Input**: the same input chooser as the Source key.
- **Projection**: automatic, table, ceiling, rear projection.
- **Game mode**: HDMI only. Game mode, speed, ALLM auto switch, aspect ratio.
- **Eye protection**: the projector blanks the picture when someone stands in front of the lens.
- **All settings**: Android TV settings, Projector, HDMI-CEC, recent apps, home screen.

## Fitting the picture to the screen

If the picture is bigger than the screen or not straight, you do not have to move the projector:

1. "Keystone > Fit to screen now": the projector finds the screen frame and fits the picture.
2. "Keystone > Optical zoom": the lens makes the picture smaller or bigger without losing sharpness.
3. "Keystone > Picture rotation": Left and Right in 0.5° steps when the picture is slightly tilted. OK
   returns to the angle you started with.
4. "Keystone > Manual keystone": each corner on its own.

"Reset keystone" puts the keystone and the rotation back.

Auto keystone and "Fit to screen" change the zoom again.

## Professional picture settings

"Picture > Professional settings", as in XGIMI's stock firmware:

- **White balance**: red, green and blue gain and offset (2-point) and 11 brightness levels (11-point).
- **Color space**: native, BT.2020, Adobe RGB, DCI-P3, Rec.709.
- **Color optimization** and **color correction**: hue, saturation and lightness of seven colors (red,
  green, blue, cyan, magenta, yellow, skin tone).
- **Gamma**: the curve of the signal (BT.1886 for SDR, HLG, ST.2084 for HDR) or a manual value 1.8-2.6.
- **HDR dynamic tone mapping** (HDR10 only), **local** and **dynamic contrast**, **dynamic black
  enhancement**.
- **Original frame rate**: shows video at its own frame rate. It turns Ultra 120 Hz off, and motion
  smoothing stays as it is while it is on.
- **Noise reduction**.
- **Reset professional settings**.

The projector itself keeps the values, as on stock.

## HDMI

Choose the input with the Source key or the Input tile. While HDMI is on screen, Picture also has:

- **Color range**: auto, full, limited. Change it when black looks grey or shadows are crushed.
- **HDMI mode**: "Compatible" (HDMI 1.4) or "Enhanced" (HDMI 2.0, 4K 60 Hz). The source reconnects. If
  the picture disappears, choose "Compatible".

Both values belong to the input on screen.

In "Projector > Power on > Open at power-on" choose HDMI 1 or HDMI 2 to have that input open right after
power-on. If a device switched the projector on through HDMI-CEC, its own input opens.

## Files

"Files" is on the Lumen Home apps page and in the app list of any launcher. It shows the projector's
storage and any USB stick plugged in.

- OK opens a folder, Back goes one level up.
- Videos and music open in the player you chose (Kino, VLC). Without one, in the built-in player: OK
  pauses, Left and Right jump 10 seconds (30 while held), Back exits.
- Photos open full screen, Left and Right go through the folder.
- An `.apk` file installs as an app: OK, then confirm.
- Holding OK on a file or folder opens a menu: open, copy to the projector's storage or a stick, delete.

## Apps

Without Google services, apps come from a USB stick: "Lumen Home > Files", the stick, the `.apk` file, OK.

## Sleep and power-on

- A short press of Power: the projector sleeps, the lamp goes off at once. The remote wakes it where you
  left it.
- "Projector > Power on > Sound at power-on": the stock power-on sound.
- Sleep timer and the power menu: hold Power.

## Updates

"Settings > Device preferences > About > Lumen OS update". The update downloads over Wi-Fi, installs into
the second slot and waits for your OK. Your data stays.

## If something is wrong

- Blurry picture: the Focus key or "Focus > Autofocus now".
- Crooked picture: "Keystone > Auto keystone now", then "Reset keystone" if needed.
- No HDMI sound on a soundbar: check "Sound > eARC" and HDMI-CEC in "All settings".
- No HDMI picture after changing the mode: "Picture > HDMI mode > Compatible".
- Questions and bugs: the [4PDA thread](https://4pda.to/forum/index.php?showtopic=1121837) or
  [GitHub issues](https://github.com/kmuradoff/lumen-os/issues).
