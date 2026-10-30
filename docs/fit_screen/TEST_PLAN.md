# Fit to my screen size: test plan (HB and Z9X)

Date: 2026-10-30. Code: `apps/Z9xProjector/src/org/lumen/projector/fit/` (core, screens, Z9X adapter) and the
byte-identical HB copies plus `apps/HbProjector/src/org/lumen/projector/fit/HbFitDevice.java`. Design:
`docs/fit_screen/DESIGN.md`. Nothing in this plan has run on a projector yet.

## 0. What is built, and where it is visible

| Part | State |
|---|---|
| Core (geometry, throw model, planner, run, calibration, storage) | Done. Host test `sh apps/Z9xProjector/test/fit/run.sh`: 221 checks, the 33 tests of DESIGN 11 plus a calibration round trip and storage. Also run by `sh apps/HbProjector/build.sh`. |
| Screens | Quick panel, keystone page: row "Fit to screen size" (ru "Вписать по размеру полотна") opens the screen size page (diagonal + unit + aspect, or width x height; margin; fit mode; "Fit now"; "Calibrate the zoom"). Calibration: full screen white frame with a middle line, tape value with the arrows. Progress card at the bottom (BACK stops after the running move), result card. 17 languages. |
| HB adapter | Done. Two new reads, FOCUS 46 getTofDistance and MOTION 2 getMotionDetection (allowed.tsv, stock callers in the rows). HbFeatures `FIT_SCREEN` is **PENDING**: the row is hidden and both reads refuse until H1 below passed and the line is changed to ON. |
| Z9X adapter | Done since 2026-10-30 night (owner approved O1 and O2): `Z9xFitDevice` has the HB body with the Z9X codes, `SUPPORTED = true`, the row shows; a fit refuses until a calibration exists. Reads 279 / 584 in `GmpfClient` with the HB budgets. Image: lumen-1.0.8-test3 (docs/z9x/parity/TEST_PLAN_1.0.8-test3.md). |
| Z9X zoom audit fixes in this build | F2 (119 guard in GmpfClient), F5 (stock key timing in the zoom screen), F6, F7 (no zoom restore after auto keystone), and since test3 F1 (153(12,10), IGmpf2 13, IGmpf2 14 as a guarded EXCEPTION), F3 (14 positions), F4 (no half-step machinery). |

## 1. Rules for every device session

- The owner is there and agrees to the lens moving. The lens and the keystone move in every fit and calibration.
- Before anything: check what plays, in its own call, and touch nothing while a film plays:
  `adb -s <ip>:5555 shell dumpsys media_session | grep -E 'state=|package'` and
  `adb -s <ip>:5555 shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'`.
- Note the owner's zoom position ("Size N of 14" in the zoom screen) and keystone (manual or auto) before step 1;
  put them back at the end (zoom screen, or "Reset keystone" + auto keystone if that is what he had).
- Log capture, started before each step:
  `adb -s <ip>:5555 logcat -c` is NOT used (it would wipe the owner's buffer); instead
  `adb -s <ip>:5555 logcat -v time Z9xFit:V Z9xZoom:V Z9xGmpf:V Z9xHal:V XGIMI_ZOOM_CTL:V '*:S' > fit_<step>.txt`.
- Never type HAL calls by hand. Everything below goes through the app.

## 2. Tape calibration, how to measure

Same on both projectors. A tape measure, the projector in its normal place (ceiling), the screen where it hangs.

1. Open the quick panel, Keystone, "Fit to screen size", "Calibrate the zoom". Choose the number of positions:
   - "2, quick": the biggest and the smallest picture (enough for the fit if the lens is regular);
   - "3, with a check": also the middle one (zoom 7 of 14); refused if the middle is more than 1 % off the line;
   - "All 14": the first time on each model (this is measurement M1 of the design, and it becomes the reference).
2. Confirm. The app measures the distance (3 reads), the tilt, runs the normal auto keystone and makes sure the lens
   is at the biggest picture. The progress card hides during the auto keystone.
3. A black picture with a white frame appears. Measure the WIDTH of the white frame along the white MIDDLE line
   (half height), from the outer edge of the left line to the outer edge of the right line, in cm to 1 mm if you
   can. On a ceiling the top edge is narrower than the bottom edge: the middle line is the mean of the two, which is
   what the fit uses.
4. Set the value with the remote: LEFT / RIGHT 1 mm (held: 5 mm steps), UP / DOWN 10 cm. OK saves it.
5. The lens moves to the next position (smaller moves only, one position at a time), autofocus, the frame comes
   back: measure again. BACK at any time stops; the lens stays where it is.
6. At the end: "Calibration saved", or "not accepted" with the reason (a width outside the lens range, a width that
   did not shrink, the middle not on the line). The screen size page then shows "Calibrated at 2.45 m".

Tips: the tape must be straight and level; if the screen frame is black velvet, hold the tape on the wall above
the screen at the middle line height. A reading error of 5 mm on 2.2 m is 0.25 % (inside the 1 % budget).

## 3. HB (G0130), first

The HB must be back on Lumen with a build that has this code. H1 needs a TEST build with `FIT_SCREEN(State.ON, ...)`
in `apps/HbProjector/src/org/lumen/projector/hal/HbFeatures.java` (one line; never shipped before H1 passes).

### H1. The two new reads (no zoom step; the normal auto keystone runs)

Open "Calibrate the zoom" ("2, quick"), confirm, and press BACK on the first white frame screen. That runs mount, 3
ToF reads, 5 accelerometer reads and the auto keystone (which may walk the zoom home, as every AK does), and no
zoom step.

Expected log:
```
Z9xFit: mount mode 1                                  (1 = ceiling front, the owner's mount)
Z9xFit: 46 getTofDistance -> 24xx mm                  three lines, 1 s apart, within 30 mm of each other
Z9xFit: motion 2 -> acc A0 A1 A2, gyro G0 G1 G2, type 1   five lines, 100 ms apart
Z9xFit: pitch 4.xx deg (acc ..., ceiling)
```
Checks:
- ToF: compare with a tape from the lens front to the screen, perpendicular. Note both (measurement M2). A
  constant offset above 2 cm goes into `FitGeometry` as `offsetMm` (DESIGN 4.1) before the fit test.
- Accelerometer: one of A0..A2 is about +-4096 (1 g), the others small. The vendor's own AK log line
  `Msensor dir0 = ..., dir1 = ..., dir2 = ...` (logcat, no filter, during the auto keystone) shows the vendor's
  mapped values: on the owner's Z9X it was `294, -22, 4078` = 4.12 degrees. Lumen's `pitch` must be the same within
  about 0.5 degree and POSITIVE on the ceiling unit pointing down at the screen. If the axis or the sign differ,
  change `AXIS_FORWARD` / `AXIS_UP` or the sign in `HbFitDevice.pitch` (and say so in the commit), then repeat.
- If possible, the same on a desk with the front lifted: positive again.
Pass: the three items above. Then FIT_SCREEN goes ON in the next HB test build.

### H2. Calibration "All 14"

Section 2 with "All 14". Expected log per position:
```
Z9xFit: 185(true,2) -> true, 93(0) -> 0
Z9xFit: 97 -> optical 0..12, 63(12,10) -> 1
Z9xFit: 189(0) -> 0 in ~900 ms, position 0 -> 1           (first move: out of the limit position)
Z9xFit: 95(2) -> 0 in ~450 ms, position 1 -> 2            (then one optical position per move)
Z9xFit: 94(0) -> 0
Z9xFit: 185(false,2) -> true
Z9xFit: calibration position N: WWWW.W mm -> TR T.TTTT
Z9xFit: calibration result: NONE
Z9xFit: calibration saved: p0=... p13=...
```
On `XGIMI_ZOOM_CTL`: no `motor limit front run step err` line anywhere, and `m_zoomInLimitPos:[0]` after the 189(0).
Checks: TR grows at every position; TR(13) / TR(0) between 1.20 and 1.45 (vendor 1.30 / 1.32). Keep the log: the
14 values decide whether the two-point line is good enough (if any position is more than 1 % off the line through
p0 and p13, "3, with a check" will refuse on that model and "All 14" becomes the advice).

### H3. Fit on the ceiling, small angle

Screen size page: the owner's real screen (diagonal and aspect, or width x height measured with the tape), margin
0, "Never past the frame". "Fit now", confirm.

Expected log:
```
Z9xFit: fit start: model G0130, screen 2213.8 x 1245.3 mm, margin 0.0, mode INSIDE
Z9xFit: 46 getTofDistance -> ...                       x3
Z9xFit: pitch ... deg
Z9xFit: auto keystone -> true
Z9xFit: position 0
Z9xFit: 16 corners not read (...): inscribed estimator (while MANUAL_KEYSTONE is PENDING on the HB)
Z9xFit: 189(0) / 95(n) ...                             exactly "target" moves, each "position k-1 -> k"
Z9xFit: fit end: DONE, final position T, size W x H (outer ...), target T FIT, Dexact NNNN mm
Z9xFit: fit inputs: mount 1, distance reads [...] -> NNNN mm, pitch x.xx deg, estimator INSCRIBED
```
The result card shows picture and screen size, the gaps, the distance, tilt and "zoom N of 14", and, when it is
worth 1 cm or more, "move the projector N cm further / closer".
Checks (tape on the screen):
- The lit picture does not spill past the screen frame on any side (mode "Never past the frame").
- Measured picture width at the middle line vs the card: within 1 % (2 cm on a 100 inch screen).
- The gap left/right vs the card: within 1 cm.
- On the ceiling the top edge is the narrow one; with the auto keystone it should be square (equal top and bottom
  within 1 cm). If the top is still narrower, write down both widths (keystone corners after AK).
- "As close as possible" picks the same or the next bigger position; it may spill by up to 1 %.
- Expected order of magnitude for a 100 inch 16:9 screen at 2.45 m, 4 degrees, with lens values like TR 0.90 at the
  biggest and 1.19 at the smallest: zoom 3 or 4 of 14 (target 11 or 10 counted from the biggest); real values come
  from H2.

### H4. Repeatability and stop

- Run H3 three times: the same final position and the same width within 0.5 cm (gear slack, M6).
- Run once and press BACK on the progress card during the zoom steps: the running move ends, no further move, the
  card says "Stopped at zoom N of 14", the log has no step after it and the session closed (94 + 185(false,2)).
- Stand in front of the ToF sensor during "Measuring the distance": "The distance keeps changing", nothing moved.

### H5. Corners (only when MANUAL_KEYSTONE becomes ON on the HB)

After that change the log shows `16 corners [x, y, ...]` and `estimator CORNERS`. Compare a ceiling read and a
desk read (M5): on the ceiling the corners near row 0 must be the ones pulled in more (physical coordinates, DESIGN
4.4). Repeat H3: the card should agree with the tape at least as well as with the inscribed estimator.

### H6. Put back

Zoom and keystone as noted in section 1.

## 4. Z9X (G0082)

### Z1. Today, read only, no new APK needed

Nothing of the fit runs on the Z9X build (hidden). Allowed today without touching anything: the vendor's own AK log
after the owner runs an auto keystone himself (`logcat -d | grep -E 'Msensor dir|AKCaliProjPara|zoomReset'`), and
`cat /mnt/vendor/xgimiconfig/G0082/zoom/zoom.ini` (limit step settings).

### Z2. The zoom audit fixes in this build (needs the owner's OK to install the new Z9xProjector)

Not tested yet. With the zoom screen open (Keystone, "Optical zoom"):
- Tap LEFT twice very fast (less than 220 ms apart): one move only (F5). Hold LEFT: one move per 220 ms, and the
  moves stop at once when the key is released (no queued move runs after the release).
- Normal use from "14 of 14" down and back (since test3): the log shows `IGmpf2 14(0) -> 0` for the first move out of
  the limit position, then `119(n) -> 0` for every move, and never `outside an 18(true,2) + 121 session`,
  `is not next to the` or `may be in the limit zone` (the client guards only refuse calls the panel never makes).
- Run an auto keystone after changing the zoom: the zoom is NOT put back any more (F7); the log line
  `AK report: zoom left as XGIMI set it (stock), screen fit=false` appears and no `restore:` lines follow.

### Z3. After O1 (zoom fix F1-F4) and O2 (279 / 584 reads): done in lumen-1.0.8-test3

`Z9xFitDevice` has the same body as `HbFitDevice` with the Z9X codes (IGmpf2 18 + 121 / 120, 117, 153(12,10),
116 + IGmpf2 13, IGmpf2 14 (+1 from the limit position) and 119 (+1)), `SUPPORTED = true`, and H1-H6 are repeated
on the Z9X with the codes of the log lines changed accordingly:
```
Z9xFit: 18(true,2) -> true, 121(0) -> 0
Z9xFit: 117 -> optical 0..12, 153(12,10) -> 1
Z9xFit: IGmpf2 14(0) -> 0 ..., position 0 -> 1
Z9xFit: 119(2) -> 0 ..., position 1 -> 2
```
and on `XGIMI_ZOOM_CTL` no `motor limit front run step err` line (audit F8). The ceiling test is the owner's real
case: ceiling front (mount 1), screen a little lower, pitch about 4 degrees, distance about 2.45 m.

## 5. Pass criteria for release

- HB: H1 to H4 pass; FIT_SCREEN ON in `HbFeatures` (one reviewed line), the H2 table kept in
  `docs/fit_screen/` for the model default question (owner decision O6).
- Z9X: O1 and O2 decided, Z2 passes, then Z3 (H1-H4 on the Z9X).
- In both: no `motor limit front run step err`, no lens move after BACK, the picture never past the frame in
  "Never past the frame".
