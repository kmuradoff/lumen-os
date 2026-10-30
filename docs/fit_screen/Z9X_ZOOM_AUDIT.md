# Z9X optical zoom audit (Lumen vs stock G0082)

Date: 2026-10-30. Read-only audit. Applied later the same day (Z9xProjector 1.0.8 rebuild, not device-tested): F2, F5,
F6 (comment) and F7. The same night, after the owner approved O1 ("Да, делай все"): F1, F3, F4 and the F6 wording
(Z9xProjector a2776ebb..., image lumen-1.0.8-test3, not device-tested; IGmpf2 14 is a guarded EXCEPTION in
`apps/check_hal_callers.py`). F8 is the device test (docs/z9x/parity/TEST_PLAN_1.0.8-test3.md).

Sources:
- Lumen: `apps/Z9xProjector/src/org/lumen/projector/panel/OpticalZoomPanel.java` (1.0.8 source, called `L` below),
  `hal/GmpfClient.java`, `hal/Gmpf2Client.java`, `apps/check_hal_callers.py`.
- Stock Z9X (V6.15.62 settings jadx), `ST/` = `research/z9x_stock_decompiled/jadx/settings/sources/com/xgimi/keystone/`:
  `view/zoommove/zoom/ZoomStateView.java`, `ZoomUtils.java`, `ZoomStateView$setZoom$2$2.java`, `$5$2.java`,
  `view/zoommove/view/ZoomMoveView.java`, `view/zoommove/view/ZoomOnlyView.java`, `KeyStoneManager.java`.
- HB fork: `apps/HbProjector/src/org/lumen/projector/panel/OpticalZoomPanel.java`, HB `hal/GmpfClient.java`,
  `devices/goat_elegant/SURVEY_hb-t5b.md` row Z1, `devices/goat_elegant/research/{allowed,banned}.tsv`, `gen/banned.py`.
- Vendor logs of the owner's Z9X: `logs/z9x_zoom_103/live_full2.txt`, `live_full3.txt` (2026-10-14).
- Vendor strings: `research/z9x_stock_decompiled/libxgimi.str`.
- Owner Z9X today (read-only): focus checked first (Lumen Play paused, nothing touched), `cat
  /mnt/vendor/xgimiconfig/G0082/zoom/zoom.ini`, `dumpsys package`, `logcat -d` (no zoom lines in the buffer).
  No HAL getter was called: 116 / 117 need a HIDL transaction through the app and 117 is only valid inside a 121
  session (121 is not a pure read); IGmpf2 13 and 153(12,10) are not allowlisted on the Z9X.

Code map (same vendor API, different numbers):

| meaning | Z9X | HB |
|---|---|---|
| prepare keystone for zoom | IGmpf2 18(true/false, 2) | 185 |
| session start / finish | IGmpf 121 / 120 | 93 / 94 |
| optical range | 117 | 97 |
| current optical index | 116 | 98 |
| one optical move | 119 | 95 |
| zoomControl (5,10 home; 12,10 limit count) | 153 | 63 |
| GetCurrentZoomLimitStep | IGmpf2 13 | 190 |
| SetOpticalZoomLimitStep | IGmpf2 14 (BANNED on the Z9X today) | 189 (EXCEPTION row, guarded) |

What runs on the owner's Z9X today: `org.z9x.projector` versionName 1.0.6 / versionCode 107 (the 1.0.7 image).
Its zoom is the 1.0.4/1.0.5 logic (resync, guard, bigger at index 0 = 153(5,10) home walk; checked in the
2026-10-24 build classes). The MENU zoom reset and the OK picture shift exist only in the 1.0.8 source.

## 1. Main finding: the Z9X has the same limit position as the HB, and Lumen does not know it

Stock Z9X `ZoomStateView` is the same code as the HB stock that the HB fork copied:

- `ST/view/zoommove/zoom/ZoomStateView.java:606-609` `getLimitStep()` = `zoomControl(12, 10)`, read in the
  constructor (`:153`). Constants `OPTICAL_LIMIT_ZOOM_STEP = 12`, `OPTICAL_LIMIT_ZOOM_TOTAL_STEP = 13` (`:63-64`).
- `initZoom` (`:509-510`): dial position = `cur + limitStep`, or `limitStep - |GetCurrentZoomLimitStep|` when
  116 = 0 and the limit step is not 0. Dial max = `limitStep + maxOptical` (`:532`).
- Smaller (key 21, `setZoom$lambda$1`, `:685-694`): `progress < limitStep` -> `ZoomUtils.setOpticalZoomLimitStep`
  (= GetCurrentZoomLimitStep + 1), else `zoomControl(0, 10)` = 119(116 + 1).
- Bigger (key 22, `setZoom$lambda$3`, `:846-853`): `progress <= limitStep` -> setOpticalZoomLimitStep
  (= GetCurrentZoomLimitStep - 1), else `zoomControl(1, 10)` = 119(116 - 1).
- `ST/view/zoommove/zoom/ZoomUtils.java` `getCurrentZoomLimitStep` = `GmDisplayManager.GetCurrentZoomLimitStep()
  .getZoomOpticalstep()`, `setOpticalZoomLimitStep(key)` = that value -1 (key 22) / +1 (key 21) ->
  `SetOpticalZoomLimitStep`.

The Z9X vendor has the same feature switched on, with the same numbers as the HB
(`devices/goat_elegant/research/projector_values.md:67`):

```
/mnt/vendor/xgimiconfig/G0082/zoom/zoom.ini
[ZOOM_LIMIT_POSITION]
zoomLimitStepEn = 1;
zoomMotorLimitStep = 129;
zoomLimitRatio = 132;
```

`libxgimi.str` has `Msrv_Zooming_Control::getZoomLimitTotalStep`, `GetCurrentZoomLimitStep`,
`SetOpticalZoomLimitStep`, "Get Zoom Limit Total Step = %d". On the HB the same config gives 153/63(12,10) = 1, so
the Z9X very likely has one limit position too: 14 stock positions (limit + optical 0..12), not 13.
UNVERIFIED on the Z9X: the 153(12,10) value and the IGmpf2 13 reply layout (expected `st_ZoomStep`, as 116).

### The "half-step sensor issue" is this limit position, used the wrong way

Lumen's comment (`L:45-58`) and `docs/z9x/OPEN_zoom_103.md` explain the half steps as a sensor fault. The vendor
log says otherwise. Every 119 that Lumen sent while the vendor flag `m_zoomInLimitPos` was 1 is logged by the
vendor as an error of its limit path:

- `live_full2.txt:4041-7038` (02:13:49-02:14:02, the 1.0.3/1.0.4 resync): `m_zoomInLimitPos:[1]` before each
  119, each 119 followed by `motor limit front run step err:0` (`:4536`, `:4968`, `:6195`, `:6729`), the index
  changing every second move.
- `live_full2.txt:7276-7293`: the 2nd bigger move in a row: `Reverse Limit Front Just Exit Pi or
  m_currMotorLimitStep is error:0`, then `m_zoomInLimitPos:[0]` and the counter set to 0 (the "lens lost" case).
- `live_full3.txt:71080-71097` (02:22:51): 119(1) sent at the full limit position: `zoom run front at limit
  pos!`, `m_currMotorLimitStep is error:-129`, `motor limit front run step err:0`, then flag 0, motor 240.
- `live_full3.txt:38910` and others: `zoomReset:1917 --- zoom in limit pos, walk to pi`: auto keystone and
  153(5,10) leave the lens IN the limit position (motor -129, flag 1).

So: after an auto keystone or 153(5,10) the lens sits in the limit position (stock dial position 0, "14 of 14").
Stock leaves it with one SetOpticalZoomLimitStep(0); Lumen sends 119 instead, which the vendor runs through its
error path (80-step moves, flag kept, a counter reset on the 2nd bigger move). The HB device test proved the
stock path on the same vendor code (`SURVEY_hb-t5b.md` Z1, 2026-10-29 22:18-22:20): 189(-1) runs the lens about
130 steps to the PI sensor, 189(0) back to index 0 and clears `m_zoomInLimitPos`, then every 95 is one full
position of 160 steps. The HB fork comment says the same: "the Z9X half-step mode is this vendor's limit
position, which stock reaches only through 189 and leaves through 189".

## 2. Line by line

| # | point | stock Z9X | Lumen Z9X (`L`) | HB fork | verdict |
|---|---|---|---|---|---|
| 1 | number of positions | `limitStep + maxOptical + 1` = 14 expected (`ZoomStateView.java:532`, `:684`) | 13 (`max` from 117, `L:191`); 12 after a resync in the same boot (`K_CAP = max - 1`, `L:367`, `topIndex` `L:642-648`) | 14 (`limitTotal + max`) | DIFF: one position missing always, two after a resync |
| 2 | counter text | dial with zoom ratio, limit zone drawn with `zoomControl(13,10)` ratio (`:594-604`) | "Size N of M", `N = top - cur + 1`, `M = top + 1` (`L:602-609`); limit position shows as index 0 = "13 of 13", the same as index 0 | "Size N of 14" from progress (HB `render`) | DIFF: two different lens positions show the same number; this is the owner's "13 of 13 does not change" |
| 3 | limit position | entered with SetOpticalZoomLimitStep(-1), left with (0) | never entered on purpose; entered by AK / 153(5,10), left by 119 (vendor error path) | 189(-1) / 189(0) with guards | DIFF (root cause) |
| 4 | step calls | 119(116 +- 1) outside the limit zone, IGmpf2 14 inside (`:685-694`, `:846-853`; `KeyStoneManager.java:191-199`) | always 119(116 +- 1) (`L:334-341`); plus the resync dance 4 x smaller, 2 x bigger, 1 x smaller (`L:347-371`) and 153(5,10) on "bigger" at index 0 after a resync (`L:223-239`) | stock rule | DIFF |
| 5 | read-back | dial is a local counter; `initZoom` re-reads 116 and the limit step on open and 500 ms after MENU (`ZoomMoveView.java:576-580`) | 116 before and right after every 119 (`L:221`, `L:337`) | 98 + 190 before and after every move | Lumen better (fresh read), but it never reads the limit step |
| 6 | half-step guards | none needed | MODE_UNKNOWN/HALF/GOOD in prefs per boot, resync, refuse 2nd bigger move (`L:255-260`), 116 jump detector (`L:264-273`) | removed | DIFF: workaround for #3; removable once #3 is fixed |
| 7 | key timing | 220 ms gap for EVERY key down (`ZoomStateView.java:624-630`); any key up drops the queued moves (`:633`, `handler.removeCallbacksAndMessages`) | 220 ms only for repeats (`L:457`); a key up drops the queue only after a hold of more than 500 ms (`L:452`); up to 2 presses queued | 220 ms for every down, every up drops the queue | DIFF (small): fast double taps queue moves stock would drop |
| 8 | keys | LEFT smaller, RIGHT bigger only (`:624`) | LEFT/DOWN smaller, RIGHT/UP bigger (`L:448-449`); OK = picture shift (1.0.8) | LEFT/DOWN, RIGHT/UP, OK nothing | EXTRA, harmless |
| 9 | 18(true,2) | on the first key press (`:618-621`) | at open (`L:183`) | at open | DIFF (tiny), keep |
| 10 | autofocus on close | if 116 at open != 116 at close (`isZoomFocus`, `:1228-1236`) | if `cur != start` or MENU moved the lens (`L:524-528`) | if progress moved | SAME in effect |
| 11 | MENU | on `zoom_and_move` (the Z9X screen): full `resetKeyStone` (setIChipReset, 181(50), 153(5,10)) then `initZoom` after 500 ms (`ZoomMoveView.java:576-580`, `KeyStoneManager.java` resetKeyStone). `ZoomOnlyView.java:419-421` (`resetOpticalZoomAndZoom`) is not on the Z9X tree | 1.0.8: only 153(5,10) on key up (owner decision 2026-10-30), then 116, mode set to HALF, saved user index cleared (`L:304-331`, `L:419-436`) | 63(5,10), then 98 + 190 | DIFF by owner decision (zoom only). After it Lumen shows "13 of 13" while the lens is in the limit position; the next "smaller" runs the resync dance instead of one IGmpf2 14(0) |
| 12 | restore after AK | none (stock leaves XGIMI's size) | puts the saved index back after an AK that is not a screen fit (`L:165-173`, `L:374-401`), through the resync | removed (log only) | EXTRA, not stock 1:1 |
| 13 | 119 client guard | n/a | `GmpfClient.setOpticalZoomStep` checks only 0..64 (`GmpfClient.java:980-982`); the neighbour rule lives in the panel | client refuses 95 outside a session, without a fresh 98, not a neighbour, or while 190 != 0 | DIFF: the HB guard is stronger |

## 3. What the HB fork learned that the Z9X lacks

From `SURVEY_hb-t5b.md` Z1 and the HB code:
1. The extra position exists and is reached only through the limit API (189/190 = Z9X IGmpf2 14/13), with the
   limit count from zoomControl(12,10).
2. Position model: `progress = limitTotal + limit` while optical = 0, else `optical + limitTotal`; a limit value
   that is not 0 away from optical index 0 is an error.
3. Smaller: `progress < limitTotal` -> limit + 1, else optical + 1. Bigger: `progress <= limitTotal` -> limit - 1,
   else optical - 1. One move = one position = one motor unit; no half steps, no resync, no lost positions.
4. Guards in the client, not only in the panel: the limit setter only next to a fresh limit read, inside
   [-limitTotal, 0], at optical 0, inside the prepare + start session; the optical move refused while the limit
   read is not 0.
5. MENU home (63/153(5,10)) leaves the lens in the limit position: shown as "14 of 14", LEFT then sends
   limit(0). Nothing to forget, no mode to track.
6. Banned list handling: an EXCEPTION row with the exact guard text in `banned.tsv` / `gen/banned.py`, the
   zoomControl(12,10) read added to `allowed.tsv`.

## 4. Concrete fixes (proposals, not done)

Owner approval needed for F1 because it lifts a banned call on the Z9X (IGmpf2 14 "zoom (large move)" in
`apps/check_hal_callers.py:39`). Stock itself sends it only as "current +- 1"; the HB device test showed one
limit move = about 130 motor steps, not a large move.

- F1. HAL (Z9X `Gmpf2Client` / `GmpfClient` / `check_hal_callers.py`):
  - add `getZoomLimitStep()` = IGmpf2 13 GetCurrentZoomLimitStep (read, `st_ZoomStep` reply as 116);
  - add `getZoomLimitTotal()` = 153(12, 10) (read) and allow `(12, 10)` next to `(5, 10)` in `ONLY_ARGS`;
  - add `setOpticalZoomLimitStep(step)` = IGmpf2 14 with the HB guards (inside 18(true,2) + 121, a 153(12,10)
    count and a fresh 13 read known, 116 read = 0, step next to the 13 value inside [-count, 0], both reads used
    up); move 14 from `BANNED` to a documented exception with that exact rule.
- F2. Strengthen `GmpfClient.setOpticalZoomStep` (119) as the HB 95: inside the session, a 117 range and a fresh
  116 read, step exactly one away, and refused while the limit read is not 0.
- F3. Panel: port the HB position model and step rule (section 3 points 2-3); counter and bar from `progress`
  with `limitTotal + max + 1` positions (14 expected); "biggest" message at progress 0, "smallest" at the last.
- F4. Remove the half-step machinery once F1-F3 pass a device test: `MODE_*`, `K_MODE*`, `resyncFromStart`, the
  2nd-bigger refusal, `K_CAP` / `topIndex`, the 153(5,10) home walk on "bigger" at index 0. Keep the 116 jump
  check as a log-and-stop safety for one release.
- F5. Keys as stock: the 220 ms gap for every key down (not only repeats), every arrow key up drops queued moves.
- F6. MENU (owner decision stays: zoom only, no keystone reset): after 153(5,10) read 116 and the limit step;
  expected "14 of 14" (limit -1); no MODE_HALF. Fix the comment `L:35-38`: on the Z9X screen stock MENU is the full
  `resetKeyStone`, not a zoom-only reset.
- F7. Restore after AK (`RESTORE_AFTER_AK`): not stock. Under the stock 1:1 rule turn it off (the HB did). If the
  owner wants it, rewrite it on the new model: from the limit position one limit(0), then 119 moves, no resync.
  It must stay off when the planned "fit to my screen size" feature owns the size.
- F8. Device test (only with the owner's OK, the lens moves): capture `logcat -v time Z9xZoom:V Z9xHal:V
  XGIMI_ZOOM_CTL:V '*:S'`. Expect on open "153(12,10) = 1, position 13 of 0..13" at index 12. RIGHT x13: 119(11)..
  119(0) then IGmpf2 14(-1) with `m_zoomInLimitPos:[1]`; one more RIGHT: "biggest", nothing sent. LEFT x14:
  14(0) with `m_zoomInLimitPos:[0]`, then 119(1)..119(12), and no `motor limit front run step err` line anywhere.
  Then MENU: "14 of 14", LEFT = 14(0). Put the zoom back where the owner had it.

## 5. Why this matters for "fit to my screen size"

The planned feature computes a target size from the ToF distance and the screen width and then drives the lens.
That needs a lens model where each position is a known, repeatable size. Today the Z9X panel has two positions
that read the same (limit and index 0), loses the smallest position after a resync, and after an AK or MENU
moves 80 instead of 160 steps until the vendor flag clears. With F1-F4 the Z9X has the 14 stock positions, each
one known size step (zoomRatio 130, zoomLimitRatio 132 in zoom.ini), the same as the HB that the owner says now
works perfectly.
