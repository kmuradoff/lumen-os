# Fit to my screen size: design (Z9X and HB)

Date: 2026-10-30. Status: HIDDEN on the Z9X and the HB since 2026-10-31 by the owner's decision (the optical zoom
works well, entering the screen size is not needed): `Z9xFitDevice.SUPPORTED` and `HbFitDevice.SUPPORTED` are false,
the quick panel row and its summary are not built; the code stays in the tree, never device-tested. Before that:
implemented 2026-10-30, not device-tested (see `TEST_PLAN.md`). Code: the core and
screens in `apps/Z9xProjector/src/org/lumen/projector/fit/` (owned by the Z9X app), byte-identical `same:` copies in
the HB app (devices/goat_elegant/forks.tsv) instead of the HB-first home of 9.4, so no shared FILES entry and no
Play 6 change; HB adapter `HbFitDevice` (FIT_SCREEN PENDING until TEST_PLAN H1), Z9X adapter `Z9xFitDevice`
(hidden until O1 and O2; both approved 2026-10-30, so since Z9xProjector a2776ebb... / lumen-1.0.8-test3 the Z9X row
shows and a fit needs a calibration first). The entry is a row of the quick panel keystone page ("Fit to screen size"), not the
settings rows of 1.1. Host test: `sh apps/Z9xProjector/test/fit/run.sh` (the tests of section 11).

Owner request (2026-10-30, paraphrased): "the sensor knows the distance to the screen; let me type the size of my
screen once, and the projector computes and sets the zoom so the picture fits the screen exactly. The projector
hangs on the ceiling, the screen is a little lower, so there is a small angle: the top edge of the picture is
narrower than the bottom edge. Take that into account and still get the zoom as right as possible. Some zoom
values are forbidden: respect them."

Inputs to this design:
- `docs/fit_screen/RESEARCH.md` (sensors, optics, stock fit, banned values; cited as `RES <section>`).
- `docs/fit_screen/Z9X_ZOOM_AUDIT.md` (why the Z9X zoom is less exact than the HB zoom, fixes F1-F8; cited as
  `AUD <section>` / `F1`..`F8`).
- `apps/common/projector/README.txt` (shared-code rules), `apps/check_hal_callers.py` (Z9X banned codes),
  `devices/goat_elegant/research/{allowed,banned}.tsv` (HB lists).
- Numbers in the tests below were computed with the formulas of section 4 (script kept out of the tree; the values
  are repeated here so the Java tests can be written from this file alone).

Marks: **OWNER** = needs an owner decision; **MEASURE** = needs a device measurement; **UNVERIFIED** = from
code or logs, not seen on a device.

## 0. Short version

1. The user enters the screen size once (width x height in cm, or diagonal in inches or cm plus aspect 16:9,
   16:10, 4:3, 2.35:1), an optional margin, and a fit mode. Stored per device model.
2. "Fit" does: read the mount mode, the distance (ToF, median of 3 reads), the pitch (accelerometer); run the
   normal auto keystone (it homes the zoom to the biggest picture anyway); read the corrected keystone corners;
   predict the picture size at every one of the 14 zoom positions; pick the biggest position whose picture fits
   inside the screen (or the closest, by setting); walk the zoom there with single-position "smaller" moves only,
   reading the position back after each move; autofocus; show "picture about 216 x 122 cm, screen 221 x 125 cm".
3. Prediction = a small projective model: lens axis at the panel's bottom-centre (offset about 100%), projector
   tilted by the pitch, keystone corners mapped from the panel onto the screen plane. The top (lens-side) edge of
   a ceiling picture is the narrow one; the model gets that from the geometry, not from a fudge factor.
4. The throw ratio per zoom position is not in any vendor file (`RES 2.1, 2.5`). A one-time calibration fixes
   it: the owner measures the picture width with a tape at the biggest and the smallest position (optionally a
   third one as a check, or all 14). The other positions are interpolated (log of the size linear in motor steps).
5. Only the stock single-position zoom calls are sent, through the same guarded clients the zoom panels use.
   Nothing new is unbanned by this feature itself. On the Z9X it depends on the zoom fix F1-F4, which lifts the
   IGmpf2 14 ban with the HB guards: **OWNER**. Until then the feature is hidden on the Z9X (the "hide
   unsupported" rule), and it ships on the HB first.
6. Exactness: 14 discrete positions, each about 2.2% of the width apart (about 5 cm on a 100 inch screen). The
   fit picks the best position and tells the user how far to move the projector for a perfect fit at that position.

## 1. What the user sees

### 1.1 Entry points

- Projector settings, "Image" group: a row "Screen size" (shows "100 in, 16:9" or "not set") and a row
  "Fit to my screen". Russian strings: "Размер полотна", "Вписать в полотно".
- Zoom panel (OpticalZoomPanel on both devices): a one-line hint "INFO: fit to screen" is NOT added in v1 (the
  zoom panels keys are owner-tuned and stock-like; **OWNER** O9 if a key is wanted).
- Hidden entirely when the device adapter says the feature is unsupported (Z9X before F1-F4, Play 6, any model
  without an adapter, or a model whose zoom position count is not the verified 14).

### 1.2 Screen size page

Remote-friendly, no keyboard:
- Input mode (LEFT/RIGHT): "Diagonal" or "Width x height".
- Diagonal: preset list 80, 90, 100, 110, 120, 135, 150 in, then "Other" with a stepper (0.5 in steps, held key
  accelerates). Unit switch in / cm. Aspect: 16:9 (default), 16:10, 4:3, 2.35:1.
- Width x height: two steppers in cm, 0.5 cm steps, range 50..500 cm.
- Margin: 0..10 cm per side, 0.5 cm steps, default 0 ("Запас от края").
- Fit mode: "Inside the frame" (default: never larger than the screen) or "Closest size" (may be up to the error
  budget larger; section 6). Russian: "Не выходить за рамку" / "Максимально близко".
- Shows the computed width x height in cm under the inputs ("100 in 16:9 = 221.4 x 124.5 cm").

### 1.3 Fit run

1. Confirmation: "The lens will move and the keystone will be corrected. About 20 s." OK / BACK.
   (Nothing is paused or stopped; the projector app never touches playback.)
2. Progress lines: "Measuring distance", "Correcting keystone", "Zoom 3 of 9", "Focusing".
3. Result card (stays until BACK):
   - "Picture: 216.5 x 122.3 cm. Screen: 221.4 x 124.5 cm. Gap: 2.4 cm left and right, 1.1 cm top and bottom."
   - "Distance 2.45 m, tilt 4.1 deg."
   - When the next bigger position would also have fitted within the budget in "Closest" mode, or the fit is
     short by more than half a position: "For an exact fit move the projector 4 cm further away" (section 6.4).
   - Errors: "Screen smaller than the smallest picture: move the projector 37 cm closer", "Screen bigger than the
     biggest picture: move it 52 cm further", "Distance out of range (0.5..4.8 m)", "Calibration needed".
4. BACK during the run: the current move finishes, no further move is sent, the zoom session is closed, the card
   says "Stopped at zoom N of 14".

### 1.4 Calibration page (one time per model)

Section 8. Entry: "Calibrate" on the fit page; offered automatically when no calibration exists.

## 2. Preconditions and gating

The fit runs only when all hold; otherwise the reason is shown and nothing moves:
- `adapter.supported()` (section 9.2): the zoom position model is verified on this model (14 positions = 1 limit
  + 13 optical), every HAL call the run needs is in the device allow list.
- A screen size is stored.
- A calibration exists for this model (stored or shipped default, section 10).
- The mount mode is known (Z9X 173, HB 3 getter) and is one of 0..3.
- No other zoom or keystone panel of the app is open (they hold the zoom session).
- The ToF reading is inside 500..4800 mm (`RES 1.1`).
- Pitch inside -30..+30 deg (accelerometer). Larger tilts are outside the tested geometry and the stock keystone
  limits (`RES 3.3`: corrections keeping less than half the panel are refused by the vendor).

## 3. Inputs: where each one comes from

| Input | Z9X (HIDL) | HB (AIDL) | Today | Notes |
|---|---|---|---|---|
| Mount mode | IGmpf 173 `getProjectorPutMode` u8 0..3 | DisplayManager 3 (`RES 3.1`, `HBA:24-25`) | Z9X allowed (GmpfClient:719); HB check allowed.tsv | decides the sign conventions of 4.2 |
| Distance | IGmpf 279 `getTofDistance` i32 mm | ProjectorFocusManager 46 `getTofDistance` | Z9X: only in Lumen Sensors (GmpfAllowlist:21), not in Z9xProjector; HB: in aidl_codes.tsv:817, not in allowed.tsv | add as a read, rate-limited (9.3) |
| Pitch | IGmpf 584 `getMotionDetection` (i16 acc[3], i16 gyro[3], u8 type, order UNVERIFIED) | MotionDetectionManager 2 `getMotionDetection` (aidl_codes.tsv:695) | Z9X: only in Lumen Sensors; HB: not allowed | add as a read; field order MEASURE |
| Auto keystone | IGmpf 326 `autoKst` (GmpfClient:570) | ProjectorFocusManager 27 `autoKst` | allowed on both | blocks seconds: keystone thread |
| Keystone corners | IGmpf 186 `getCorrectKeystoneFullPiont(0)` | DisplayManager 16 | allowed on both | 4 corners, 3840x2160 panel pixels |
| Zoom count | 117 range + 153(12,10) limit count | 97 + 63(12,10) | Z9X 153(12,10) NOT allowed (F1); HB allowed | |
| Zoom position | 116 + IGmpf2 13 limit step | 98 + 190 | Z9X IGmpf2 13 NOT allowed (F1); HB allowed | |
| Zoom step | 119 (+-1), IGmpf2 14 (+-1, limit zone) | 95 (+-1), 189 (+-1, limit zone) | Z9X 14 BANNED (F1); HB 189 EXCEPTION with guards | the only moves the fit uses |
| Zoom home | 153(5,10) | 63(5,10) | allowed on both | |
| Zoom session | IGmpf2 18(true,2) + 121 / 120 + 18(false,2) | 185(true,2) + 93 / 94 + 185(false,2) | allowed on both | |
| Autofocus | `Hal.requestAutofocus` (as OpticalZoomPanel close) | same name in the HB app | allowed on both | |

Not used, on purpose: the 2x2 ToF zones and the vendor yaw / pitch / roll (no getter, logs only, `RES 1.3`),
IGmpf2 256 / HB GmFactoryManager 124 wall rectangle (factory service, banned family), IGmpf 189 / HB 19 keystone
angle (units UNVERIFIED), any keystone writer (the fit leaves the keystone to the stock auto keystone).

## 4. Geometry model

### 4.1 Coordinates and assumptions

- Panel: 3840 x 2160 pixels, PHYSICAL panel coordinates (the coordinates 186 / HB 16 return, see 4.4).
- Lens axis: through the panel's bottom-centre, `axisRow = 2160`, `axisCol = 1920` (vendor `defaultZoomCenter =
  1919, 2159`, `RES 2.1`). Offset about 100%: the picture starts at the lens axis and extends away from it.
  `axisRow` is a parameter (MEASURE: offset test, `RES 7` item 3); zoom keeps the lens-side edge fixed.
- Throw ratio `TR(p)` at zoom position p: perpendicular distance / full panel width on a perpendicular screen.
- Tilt `theta`: angle between the lens axis and the screen normal, positive when the picture side tilts away
  from the screen normal. Ceiling mount, screen below, projector pointing down: theta > 0. Desk mount pointing
  up: theta > 0. This single convention covers both mounts in physical coordinates: the far edge of the picture
  is always the wide one, the lens-side edge is the narrow one (top on the ceiling, bottom on a desk).
- Distance `D`: perpendicular distance from the lens to the screen plane, from the ToF: `D = d_tof + offsetMm`,
  `offsetMm` default 0 (MEASURE: tape vs 279 at two distances, `RES 7` item 2). A pure scale error of the ToF is
  absorbed by the calibration (TR is solved with the same D), only an additive offset matters.
- Yaw is not modelled (no getter). The keystone corners carry the vendor's yaw correction; section 4.4 handles
  the small mismatch this leaves.
- The screen is vertical and flat; the projector is centred on it horizontally (the fit is about size only:
  there is no optical lens shift; the OK picture shift of the Z9X 1.0.8 zoom panel stays a separate tool).

### 4.2 Panel pixel to screen point

For a panel pixel (px, py):

```
y  = ((axisRow - py) / 2160) * (9/16) / TR      // tangent of the angle from the lens axis, picture direction
u  = ((px - axisCol) / 3840) / TR               // horizontal tangent
den = cos(theta) - y * sin(theta)
X  = u * D / den                                // mm, horizontal, 0 = lens centre line
Y  = D * (y * cos(theta) + sin(theta)) / den    // mm along the screen, from the height of the lens axis,
                                                // growing away from the lens (down on a ceiling mount)
```

This is the exact pinhole projection onto a plane; lines stay lines, so the 4 corners define the picture.

For the full panel (no keystone) the picture is a trapezoid:
- lens-side edge (top on the ceiling): `Wt = D / (TR * cos(theta))`
- far edge: `Wb = D / (TR * (cos(theta) - y1 * sin(theta)))`, `y1 = (9/16) / TR`
- height `H = D * tan(theta + phi1) - D * tan(theta)`, `phi1 = atan(y1)`
- ratio `Wt / Wb = 1 - y1 * tan(theta)`: TR 1.2, theta 4.06 deg -> 0.9667 (the top edge 3.3% narrower), as
  `RES 3.3`.

### 4.3 Picture size at a zoom position

Two estimators, the first one preferred:

A. From the corrected corners (after an auto keystone, or any manual keystone): map the 4 corners of 186 / HB 16
   with 4.2 at `TR(p)`, then
   - picture width = mean of the far and near edge lengths, height = mean of the left and right edge lengths;
   - mismatch = half the larger relative difference between opposite edges (a measure of the unmodelled yaw /
     roll and of the vendor's own TR assumption, 4.4).
   For the "inside the frame" check the OUTER box of the mapped quad is also kept (the lit area must not spill).
B. Pitch only (no corners, or corners equal to the full panel = keystone off): if the keystone is off, the lit
   picture is the full trapezoid of 4.2 (outer box `Wb x H`, the wide far edge decides the fit). If corners are
   missing but a keystone is active, the vendor rectangle is approximated by the largest 16:9 rectangle inside the
   trapezoid, standing on the far edge:
   `w = Wb * H / (H + (Wb - Wt) / (16/9))`, `h = w * 9/16`, capped at `h <= H`.

The keystone warp is applied once, by the auto keystone at the biggest position, and stays in panel pixels while
the zoom moves. The model therefore maps the SAME corners at every `TR(p)`: the slight change of the trapezoid
with TR (ratio 0.956 at TR 0.90, 0.966 at TR 1.19, 4.2) is predicted, not ignored. No auto keystone is run after
the zoom moves, because the Z9X auto keystone walks the zoom home first (`RES 3.2` step 5).

### 4.4 Evidence for the coordinate convention, and the mismatch

The owner's logged AK corners (`RES 3.3`, LOG2:12891-12894, ceiling front, pitch 4.06 deg, centre distance about
2450 mm): TL (106, 23), TR (3683, 89), BR (3725, 2055), BL (68, 2057). In physical coordinates row 23 is the far
edge, so the far edge must be shrunk more: the logged panel rows agree (3577 px at row 23..89, 3657 px at
2055..2057). Mapped with 4.2, the quad is close to a rectangle (far / near width 1.020 at TR 0.90, 1.009 at TR
1.20, 1.007 at TR 1.30); the opposite convention would give about 0.93. So 186 is in physical coordinates
(UNVERIFIED until a ceiling and a desk read are compared, MEASURE M5).

The remaining 1.8% height mismatch (left 1519 mm vs right 1466 mm at TR 0.90) is the vendor's yaw correction
(yaw about 2 deg in the same log), which the model cannot see. Using the mean edges cancels it to first order;
the calibration (section 8) solves TR with the same estimator, so what is left is only the change of yaw between
calibration and use. The near-rectangle at TR 1.30 also suggests the vendor AK assumes TR 1.30 (its template
`AKCaliProjPara` fx 2500 / 1920, `RES 2.1`) at every zoom: an extra reason not to trust the AK's own size.

## 5. Zoom position model

- Positions as the stock dial and the HB panel: `progress` 0..N-1, 0 = biggest picture (the limit position),
  1..13 = optical index 0..12, N = limitTotal + max + 1 = 14 (`AUD 3`). The adapter reports `progress` and `N`;
  the core never sees vendor indexes.
- Motor coordinate (for interpolation only): `m(0) = 0`, `m(p) = 129 + 160 * (p - 1)` for p >= 1
  (`zoomMotorLimitStep = 129`, one optical position = 160 steps, `RES 2.2`); `m(13) = 2049`.
- Approach direction: every position used by the fit or the calibration is reached by "smaller" moves from a
  bigger position (from home when needed). The 80-step gear slack (`RES 2.2`) then always lies on the same side,
  so a calibrated position is the same lens position at fit time.
- Throw model `TR(p)`:
  - full table: 14 calibrated values, used as is;
  - two points (a, b) (default a = 0, b = 13): `ln(1/TR(p)) = ln(1/TR(a)) + (m(p) - m(a)) / (m(b) - m(a)) *
    ln(TR(a) / TR(b))` (picture width geometric in motor steps);
  - three points: as two points, with the middle point used only as a check: if the predicted TR at the middle
    differs from the measured one by more than 1%, the calibration asks for the full table (the vendor size curve
    `y = -0.1165 x^0.772 + 105.5` is curved, `RES 2.5`, so a straight log line may not hold).
- Plausibility (refuse the calibration otherwise): `0.6 <= TR <= 2.0`, `TR(13) / TR(0)` in 1.20..1.45 (vendor
  ratios 1.30 / 1.32 / 1.38, `RES 2.1, 2.5`), TR strictly growing with p.

## 6. Planner: choosing the position

### 6.1 Target box

`Ws = screenW - 2 * margin`, `Hs = screenH - 2 * margin`.

### 6.2 Error budget

`eps` = relative size uncertainty, default 1.0%:
- ToF noise +-3 mm at 2.45 m: 0.1% (`RES 1.1`);
- tape reading +-0.5 cm on 220 cm: 0.25% (calibration);
- pitch error 1 deg: about 0.8% on the inscribed width (4.2 ratio);
- additive ToF offset after a distance change of 1 m: unknown until M2 (target < 0.3%);
- gear slack repeatability: unknown until M6.
Plus `mismatch` of 4.3 A when it exceeds 3% (a strong yaw change since calibration): `eps += mismatch - 3%`.
`eps` is a constant in the core with this breakdown in a comment, revisited after the device test.

### 6.3 Rule

For p = 0..N-1 compute `size(p)` (4.3; outer box for the "inside" check, mean edges for the report).
- Mode "Inside the frame": target = the smallest p (biggest picture) with
  `outerW(p) * (1 + eps) <= Ws` and `outerH(p) * (1 + eps) <= Hs`.
- Mode "Closest size": target = the p minimising `max(|w(p) / Ws - 1|, |h(p) / Hs - 1|)` among the positions with
  `w(p) <= Ws * (1 + eps)` and `h(p) <= Hs * (1 + eps)`.
- No position fits (screen smaller than the smallest picture): target = N-1, result "move closer by
  `D - D * min(Ws / w, Hs / h) / (1 + eps)`".
- Even p = 0 is smaller than the screen by more than one position step: target = 0, result "move further by
  `D * min(Ws / w(0), Hs / h(0)) - D`".
- For a screen aspect other than 16:9 the limiting dimension decides automatically (2.35:1 screens: height).

### 6.4 Distance advice

After choosing p: `Dexact = D * min(Ws / w(p), Hs / h(p))`. If `|Dexact - D| >= 1 cm` the result card shows "for
an exact fit at this zoom move the projector N cm further / closer". This is the only way to beat the 2.2%
optical step (the Z9X has no digital zoom, `RES 8`; a keystone-rectangle shrink would lose pixels and is not
offered, **OWNER** O8).

## 7. Fit run: sequence, calls, guards

Runs on the app's HAL thread (the keystone thread for 326 / HB 27), never on the main thread. Each numbered
step is one adapter method; the core holds the state machine (`FitRun`).

1. `adapter.supported()`, preconditions of section 2. Load screen, calibration.
2. `mount = adapter.mountMode()`.
3. `d = median(adapter.readDistanceMm() x 3)`, 1 s apart (each read is an active 0.6 s measurement that blocks
   the gmpf thread; 3 reads per fit is the whole budget, the adapter refuses more, 9.3). If the spread of the 3
   reads exceeds 30 mm: stop, "Distance unstable (someone in front of the sensor?)".
4. `theta = adapter.readPitchDeg()` (accelerometer, averaged over 5 samples 100 ms apart; sign per mount mode).
5. `adapter.runAutoKeystone()` and wait for its end (existing AK code path of each app, same overlay as the
   user's AK). Then `adapter.readZoom()`: expected progress 0 on the Z9X (AK homes the zoom, `AUD 1`). If it is not
   0 (HB `zoomAKType = 1` may keep the zoom, `RES 6`): `adapter.homeZoom()` (153 / 63(5,10)) and read again;
   still not 0: stop "Lens not at home".
6. `corners = adapter.readKeystoneCorners()` (186 / HB 16). Null or invalid: estimator B.
7. Planner (section 6) -> target `t`. `t == 0`: skip to 9.
8. Zoom walk, inside one session (`adapter.beginZoomSession()`, `finally adapter.endZoomSession()`):
   for k = 1..t: `s = adapter.stepSmaller()`; the adapter sends exactly one stock single-position call (from
   progress 0 the limit call `limit + 1` = Z9X IGmpf2 14(0) / HB 189(0); then 119 / 95 with `optical + 1`) and
   returns the read-back progress; `s != k` -> stop at once, "Lens position unexpected", no further move.
   BACK pressed -> stop after the running move. Never a multi-step, absolute or home call inside the walk.
9. Autofocus (`adapter.autofocus()`), as the zoom panels do after the lens moved.
10. Re-read 186 / HB 16 corners and the progress; compute the reported size at the final progress with 4.3 A;
    show the result card. Store `lastFit` (section 10).

Timing estimate: ToF 3 s, AK 5-8 s, up to 13 moves x 0.55 s = 7 s, AF 2-3 s: 17-21 s.

What the fit never does (the banned / guarded list, `RES 5`): 297 / 283 / 457 (HB 28 / 42), 153 / 63 modes other
than (5,10) and the (12,10) read, IGmpf2 14 / HB 189 except `current +- 1` under the guards, 119 / 95 except
the neighbour of a fresh read, any 24 newAutoKst, HB 11 correctKeystone, IGmpf2 61, 200 / HB 32, 583 / 577 /
IGmpf2 263 / HB motion 10-12, 22, 23, ToF writers 269 / 270 / 243 / 252 / 255 / 441 / 549 / IGmpf2 261, any
focus or motion listener registration, IGmpf2 76. The fit core cannot even name these: it talks only to the
adapter interface, and the adapters reuse the existing guarded clients.

## 8. Calibration mode

Goal: `TR(p)` for this model. Same optics on every unit of a model, so one good calibration can later ship as the
model default (**OWNER** O6).

Setup text on the page: "Projector where it normally hangs. Tape measure. Takes 2 minutes."

1. Preconditions as the fit, without the calibration check.
2. Read mount, distance (3 reads, median), pitch; run the auto keystone; ensure progress 0 (home as 7.5); read
   the corners.
3. Show a calibration pattern on the corrected picture: black field, 2 px white outer frame, tick marks every
   10% along the top edge (reuse the AK pattern renderer, `ak/AkPatternRenderer`). Autofocus.
4. "Measure the width of the white frame, outer edge to outer edge, in cm." Stepper input (0.1 cm steps, starts at
   the value predicted by the current calibration or by TR 1.0). OK.
5. Walk to the smallest position (13 single "smaller" moves, read-back each, as 7.8). Autofocus. Same pattern,
   same question.
6. Optional "Check one more" (progress 7) and "Measure every position" (all 14, walking smaller one at a time).
7. For each measured position: solve `TR` such that the mean-edge width of 4.3 A (or B) equals the measured width
   (bisection on 0.5..3.0, monotonic). Check plausibility (section 5). Show the table: "position 1: 0.900, position
   14: 1.188, middle check: predicted 1.043, measured 1.047 (0.4%) OK".
8. Save. Leave the zoom where it is and offer "Fit now".

The calibration stores the distance, pitch and corners it was made with, so a later change of mount can be
detected (distance or pitch changed a lot -> the fit still works, the result card just says "calibrated at 2.45 m,
4.1 deg").

## 9. Code layout

### 9.1 Core (device neutral, plain Java, no Android imports, no HAL codes)

Final home: `apps/common/projector/src/org/lumen/projector/fit/`:
- `ScreenSize.java`: from width x height or diagonal (in / cm) + aspect; margin; validation.
- `FitGeometry.java`: 4.2 mapping, trapezoid, inscribed rectangle, corner estimator (mean edges, outer box,
  mismatch).
- `ThrowModel.java`: calibration points -> `TR(p)`, interpolation, plausibility, TR solve from a measured width.
- `ZoomModel.java`: progress <-> motor coordinate (limit 129 + 160 per optical step), N.
- `FitPlanner.java`: section 6 (modes, eps, advice). Returns a `FitPlan` (target, sizes per position, advice).
- `FitDevice.java` (interface, the adapter contract below) and `FitRun.java` (section 7 state machine, the only
  caller of `FitDevice`).
- `FitStore.java`: (de)serialises screen, calibration and lastFit to a JSON string (the Android side stores it).
The UI (`panel/FitScreenPanel.java`, strings) is Android code in each app, built on the shared `ui/Panel`.

```java
interface FitDevice {
    String modelId();                     // "G0082", "G0130"
    boolean supported();                  // verified 14-position model + all calls allowed
    int mountMode();                      // 0..3, or -1
    int readDistanceMm();                 // one ToF read; adapter enforces the per-fit budget
    double readPitchDeg();                // NaN if unknown; sign per mountMode already applied
    boolean runAutoKeystone();            // blocks until the AK finished; false on failure
    int[] readKeystoneCorners();          // 8 ints TL,TR,BR,BL (x,y) in 3840x2160 physical, or null
    int positionCount();                  // N (14)
    int readProgress();                   // 0..N-1, fresh read, -1 on error
    void homeZoom();                      // 153 / 63(5,10), then the caller reads back
    void beginZoomSession(); void endZoomSession();
    int stepSmaller();                    // one stock single-position move, returns the read-back progress
    void autofocus();
}
```

### 9.2 Adapters (per device, in each app, never shared)

- `apps/HbProjector/src/org/lumen/projector/fit/HbFitDevice.java`: HB codes, reuses the HB GmpfClient guards
  (95 only next to a fresh 98, refused while 190 != 0; 189 only `190 + 1` inside [-limitTotal, 0] at optical 0 in
  the 185 + 93 session).
- `apps/Z9xProjector/src/org/lumen/projector/fit/Z9xFitDevice.java`: Z9X codes, reuses the Z9X clients after F1
  and F2 (IGmpf2 13 / 14 with the HB guards, 119 guard as HB 95). `supported()` returns false until the Z9X zoom
  has the F1-F4 model (a build constant plus a runtime check that 153(12,10) returned 1).
- Play 6: no adapter (no ToF zoom path verified); the rows stay hidden.

### 9.3 Device HAL additions

| Device | Call | Change | Guard | Approval |
|---|---|---|---|---|
| Z9X | IGmpf 279 getTofDistance | add read to GmpfClient, allowlist in check_hal_callers.py | max 3 calls per fit run / calibration, >= 1 s apart, never in a loop or timer; refused while the Lumen Sensors app read one in the last 2 s is not detectable, so the 1 s gap is the guard | **OWNER** (Z9X change) |
| Z9X | IGmpf 584 getMotionDetection | add read | max 10 reads per run | **OWNER** |
| Z9X | IGmpf2 13, 153(12,10), IGmpf2 14 (guarded), 119 guard | the zoom fix F1-F2 (`AUD 4`) | as HB | **OWNER** (lifts a ban) |
| HB | ProjectorFocusManager 46 getTofDistance | allowed.tsv row + client | same budget as Z9X | HB app (device owner rules) |
| HB | MotionDetectionManager 2 getMotionDetection | allowed.tsv row + client | same | HB app |

`apps/check_hal_callers.py` keeps working unchanged for the core: the core has no `call(...)` sites.

### 9.4 Landing order (device separation)

The shared-code rules (`apps/common/projector/README.txt`) say a shared file changes only with an owner-approved
Z9X change, and the Z9X must not change because another device is added. So:
1. HB first: the core lives in `apps/HbProjector/src/org/lumen/projector/fit/` (same package and file names as
   the final common home, no HB imports), with its tests. The Z9X tree is untouched.
2. When the owner approves the Z9X part (after F1-F4 pass their device test): move the core files to
   `apps/common/projector/src/.../fit/`, list them in `apps/common/projector/FILES`, copy outward with
   `sync.sh`; from then on the HB copy is the synced copy (identical bytes). Add the Z9X adapter and UI.
3. If the HB later needs a core change the Z9X does not want, the HB forks that file per the README rule.

## 10. Storage

SharedPreferences `fit_screen` of the projector app, one JSON string per key, keys prefixed by `modelId()`:
- `<model>.screen`: `{mode:"diag"|"wh", wMm, hMm, diagIn, aspect, marginMm, fitMode:"inside"|"closest"}`
- `<model>.calib`: `{v:1, points:[[p, TR], ...], dMm, pitchDeg, corners:[8], date, kind:"two"|"three"|"full"}`
- `<model>.lastFit`: `{progress, dMm, pitchDeg, date}`
- Shipped default calibration per model in the core (`ThrowModel.DEFAULTS`), empty until the owner's
  calibration is reviewed (**OWNER** O6).
Per model, not per unit: a Z9X and an HB in the same house keep separate values in their own apps anyway.

## 11. Unit tests (plain JDK, like `apps/common/projector/test/run_voice_test.sh`)

File: `test/org/lumen/projector/fit/FitGeometryTest.java` next to the core, script `test/run_fit_test.sh`
(javac of the core + test, no Android). Lengths in mm, angles in deg, tolerance +-0.5 mm unless stated.
TR values marked "fixture" are test inputs, NOT device values (the device values come from the calibration).

Screen sizes (`ScreenSize`):
1. 100 in 16:9 -> 2213.8 x 1245.3. 120 in 16:10 -> 2584.7 x 1615.4. 100 in 4:3 -> 2032.0 x 1524.0.
   80 in 16:9 -> 1771.0 x 996.2. 150 in 16:9 -> 3320.7 x 1867.9. 110 in 2.35:1 -> 2570.9 x 1094.0.
2. 254 cm diagonal 16:9 = same as 100 in. Width x height input 221 x 124.5 cm kept as is. Margin 20 mm on 100 in
   16:9 -> target box 2173.8 x 1205.3. Refused: width < 500 mm or > 5000 mm, margin > 100 mm.

Trapezoid (`FitGeometry`, full panel):
3. Level: D 2400, TR 1.20 (fixture), theta 0 -> Wt = Wb = 2000.0, H = 1125.0; inscribed 2000.0 x 1125.0.
4. Owner-like tilt: D 2450, TR 1.20, theta 4.06 -> Wt 2046.8, Wb 2117.2, H 1193.9, Wt / Wb 0.9667 (+-0.0001);
   inscribed 2049.2 x 1152.7.
5. Same at TR 0.90 -> Wt 2729.1, Wb 2855.8, H 1610.4, ratio 0.9556; inscribed 2734.7 x 1538.3.
6. Same at TR 1.188 -> Wt 2067.5, Wb 2139.4, H 1206.4; inscribed 2070.0 x 1164.4.
7. Steep: D 3000, TR 1.20, theta 10 -> Wt 2538.6, Wb 2767.3, H 1580.6, ratio 0.9173; inscribed 2559.0 x 1439.4.
8. Point map of the full panel at D 2450, TR 1.20, theta 4.06: (0,0) -> (-1058.6, 1367.8), (3840,0) -> (1058.6,
   1367.8), (3840,2160) -> (1023.4, 173.9), (0,2160) -> (-1023.4, 173.9). Lens-side edge narrower than the far
   edge (the ceiling "top narrower" case) asserted explicitly.
9. Symmetry: theta 0 gives Wt == Wb for any TR; negative theta makes the lens-side edge the wider one.

Corner estimator (owner's logged corners TL (106,23), TR (3683,89), BR (3725,2055), BL (68,2057), D 2450,
theta 4.06):
10. TR 1.20 -> far / near width ratio 1.0093 (+-0.0005); mean edges 1961.4 x 1106.3; outer box 1970.5 x 1125.7;
    inner box 1952.4 x 1086.9; mismatch 0.0175 (+-0.0005).
11. TR 0.90 -> far / near 1.0201; mean edges 2630.8 x 1492.5; outer 2657.0 x 1518.9; inner 2604.6 x 1466.1.
12. TR 1.30 -> far / near 1.0068 (documents 4.4: the logged warp is near-rectangular around the vendor's TR 1.30).
13. Corners equal to the full panel -> estimator reports "keystone off" and the outer box equals Wb x H of test 4.

Throw model (`ThrowModel`, `ZoomModel`), two points p0 TR 0.900, p13 TR 1.188 (fixture, ratio 1.32 = vendor
`zoomLimitRatio`):
14. Motor coordinate: m(0) 0, m(1) 129, m(2) 289, m(7) 1089, m(13) 2049.
15. TR(p), +-0.0005: p1 0.9159, p2 0.9359, p4 0.9774, p7 1.0431, p10 1.1132, p12 1.1625, p0 / p13 exact.
16. Solve: mean-edge width 2630.8 at (D 2450, theta 4.06, logged corners) -> TR 0.9000 (+-0.0005); inscribed width
    2734.7 (estimator B) -> 0.9000; 2070.0 -> 1.1880; level 2000 at D 2400 -> 1.2000. Round trip for every p.
17. Plausibility: TR 0.55 refused; TR(13) / TR(0) = 1.10 refused; non-increasing table refused; middle check off by
    1.5% -> "full table needed", by 0.4% -> accepted.

Planner (`FitPlanner`), D 2450, theta 4.06, fixture TR table of test 15, eps 1%:
18. Estimator B, screen 100 in 16:9, "inside": sizes p0 2735x1538, p9 2258x1270, p10 2209x1243, p11 2162x1216,
    p13 2070x1164 -> target p11 (p10 fits without the budget but not with it: 2209 * 1.01 > 2213.8). Advice
    "Dexact 2509": move 6 cm further.
19. Same, "closest" -> target p10 (2209 x 1243, 0.2% under); Dexact 2455, 0.5 cm away -> no advice (under 1 cm);
    the card shows gaps of 0.2 cm left and right, 0.1 cm top and bottom.
20. Same, margin 20 mm, "inside" -> target p12.
21. Level (theta 0), 100 in, "inside" -> widths p10 2201, p11 2154 -> target p11.
22. Corner estimator A (logged corners), 100 in, "inside" (outer box): p9 2178 x 1245 fails on height
    (1245 * 1.01 > 1245.3), p10 2130 x 1217 fits -> target p10. Mean-edge report at p10: 2117 x 1196.
23. Screen 80 in: nothing fits (smallest p13 2070 > 1771) -> target p13, "move closer": D * (1771.0 / 2070.0) /
    1.01 = 2075 -> 37.5 cm closer.
24. Screen 150 in: p0 2735 < 3320.7 -> target p0, "move further": 2975 -> 52.5 cm further.
25. Screen 110 in 2.35:1 (2570.9 x 1094.0): height limits, every position is taller than 1094 * (1 / 1.01) -> p13,
    "move closer" to 2279.

Run state machine (`FitRun` with a `FakeFitDevice`):
26. Happy path: AK leaves progress 0, target 9 -> exactly 9 `stepSmaller` calls, read-back 1..9, session begun
    once and ended once, autofocus once, 3 distance reads, no `homeZoom`.
27. AK leaves progress 4 -> one `homeZoom`, then walk from 0. Target 0 -> no step, no session.
28. Read-back mismatch at the 3rd step (fake returns 4) -> no 4th call, session ended, result "unexpected".
29. Cancel during step 2 -> step 2 completes, no step 3, session ended.
30. Distance reads 2440, 2450, 2490 (spread 50 > 30) -> stop before the AK, no lens call at all.
31. `positionCount() != 14` or `supported() == false` -> no call except `supported()`.
32. A 4th `readDistanceMm` request inside one run -> the run refuses (budget), test asserts the counter.
33. The fake records every call: the test asserts the walk contains only `stepSmaller` (never a bigger move,
    home, or multi-step) and that progress never exceeds N-1.

## 12. Device test plan (only with the owner's OK: the lens moves)

HB first (when it is back on Lumen), then the Z9X after F8 of the audit passed:
1. Read-only part (no lens move): 279 / 46 three times, 584 / motion 2 five times, mount mode, 186 / 16 corners:
   check the 584 field order (pitch about 4 deg on the owner's ceiling unit), the corner convention (4.4).
2. M2 tape vs ToF at two distances (projector on a table is fine) -> `offsetMm`.
3. Calibration with all 14 positions once (the table also checks the log-linear interpolation; if the two-point
   model errs by more than 1% anywhere, three-point or full table becomes the default).
4. Fit with the owner's real screen size: measure the result with a tape, compare to the card.
5. Repeat the fit 3 times: same target and the same width within 0.5 cm (gear slack M6).
6. Put the zoom and keystone back where the owner had them (note them before step 1).
Logs: `logcat -v time Z9xZoom:V Z9xHal:V Z9xFit:V XGIMI_ZOOM_CTL:V '*:S'`; expect no "motor limit front run step
err" line.

## 13. Interactions

- Auto keystone at power-on, or triggered by movement (Z9X real-time AK is enabled, `RES 3.1`), walks the zoom
  home on the Z9X and so undoes the fit. Options for the owner (**OWNER** O4): (a) accept it, the user presses
  "Fit" again; (b) "Keep my fit after auto keystone": after an AK the app reads the distance once and, if it is
  within 2 cm of `lastFit.dMm`, walks back to `lastFit.progress` with the same guarded steps (no new AK). This
  replaces the Z9X `RESTORE_AFTER_AK` restore (audit F7), which must not run together with the fit.
- Curtain fit (stock camera fit, IGmpf 290 / HB 35): independent. If it is on, the auto keystone may zoom in
  itself (`RES 4.2`, usually 0 steps); step 5 of the run reads the real progress anyway.
- Manual zoom panel: after a fit the panel shows the new position ("10 of 14"); a manual change is simply the
  user's size, `lastFit` is cleared.
- Lumen Sensors app also reads 279 (at most once a minute): the 1 s gap and 3-read budget keep the gmpf thread
  load small; no coordination needed.

## 14. Open questions for the owner

- **O1** Z9X: approve the zoom fix F1-F4 (lifts the IGmpf2 14 ban with the HB guards, adds 153(12,10) and IGmpf2
  13 reads)? Without it the Z9X lens position is known only to 0.7..1.5 positions after AK / home (`RES 2.4`) and
  the fit stays hidden on the Z9X.
- **O2** Z9X: approve adding 279 (ToF) and 584 (accelerometer) as reads to Z9xProjector with the budgets of 9.3?
- **O3** Order: HB first (core in the HB app, moved to apps/common when the Z9X part is approved), as 9.4?
- **O4** After an auto keystone: re-apply the fit automatically (13 b) or leave it to the user (13 a)? Either way
  the Z9X `RESTORE_AFTER_AK` restore goes away.
- **O5** Default fit mode: "Inside the frame" (never spills, may be up to one position small) or "Closest size"?
- **O6** May the owner's calibration ship as the default for every unit of the same model (friend's Z9X etc.)?
- **O7** Calibration effort: two positions (default) or all 14 once on the owner's unit?
- **O8** Finer than one zoom position: only the "move the projector N cm" advice (proposed), or also a digital
  shrink of the keystone rectangle (loses pixels, not stock)?
- **O9** Where the entry lives: settings rows only (proposed), or also a key in the zoom panel / a quick panel tile?
- **O10** Keystone before the fit: always run the stock auto keystone (proposed: it homes the zoom anyway on the
  Z9X), or offer "keep my manual keystone" (then the run homes the zoom with 153(5,10) and uses the current
  corners)?

## 15. Measurements needed (from RESEARCH section 7, mapped to this design)

| # | What | Used in | Blocks |
|---|---|---|---|
| M1 | width at the 14 positions at one distance | `ThrowModel` defaults, interpolation check | default calibration only (user calibration works without it) |
| M2 | ToF vs tape at 2 distances | `offsetMm` | accuracy after moving the projector |
| M3 | offset (picture edge vs lens axis) | `axisRow` | accuracy of the tilt term |
| M4 | Z9X 153(12,10) = 1 and IGmpf2 13 layout | Z9X adapter `supported()` | Z9X release |
| M5 | 584 field order, 186 corner convention on ceiling and desk | pitch, 4.4 | first device test |
| M6 | gear slack per direction | `eps` | strict mode margin |

Not part of this design: the free storage question (176 MB free of 2.8 GB) from the same owner message.
