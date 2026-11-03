# T25 — scan-angle-and-boundary

status: review (needs a Pixel run: `ai/tasks/T25-scan-angle-and-boundary.md` "Pixel check")
created: 2026-11-03
day: Day 8 (buffer)
gate: Day 4 — 5 objects within 2 cm, character routes around; Day 8 — fix only what the shoot exposed
cut-if-slips: revert the whole brief; the level builds as it did at `ac819e3`

## Goal
Fixes from the Codex AR audit of 2026-11-02, confirmed by numbers. A scan from too far above must not build a level from
wrong geometry, and ARCore's plane polygon must not produce a cliff in the middle of a real table. Depth-based geometry
(the audit's second stage) is cut: it is a new subsystem and the plan allows only fixes today.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/geometry/ConvexHull.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/{ScanAngle,SurfaceBoundary,TableScan}.kt (new), SceneBuilder.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/physics/Physics.kt (push-out rule), domain/Model.kt (KDoc, `Semantics.liesFlat`, `isCompact`)
- core/src/commonTest/.../{geometry/ConvexHullTest,pipeline/ScanAngleTest,pipeline/SurfaceBoundaryTest,pipeline/TableScanTest,pipeline/ScanViewFixture}.kt, pipeline/SceneBuilderTest.kt
- shared/src/commonMain/kotlin/kz/rudy/ar/{ArSlot,App}.kt, ui/Strings.kt, ui/screens/ScanningScreen.kt
- docs/architecture.md (the scan section)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt, ar/CharacterNode.kt, ar/PlaneCoords.kt, scan/ScanCapture.kt (hand-review)

## Contract
- `ScanAngle`: a camera axis or a mask centre steeper than 75° above the plane is too steep. Pure, roll-independent.
- `TableScan.build(input, failedPrompts, previous, profile)`: too-steep masks never reach `SceneBuilder`; nothing built
  and a real object too steep = `level == null`, the old level stays; a previous object under a too-steep mask or of a
  failed prompt is carried (never DYNAMIC); `missed` counts real too-steep objects nothing stands in for. A hand or a
  speck of noise counts for neither.
- `TableScan`: a mask the frame cuts off (within 2 px of its edge) never reaches `SceneBuilder`; it keeps a previous object
  like a too-steep mask does and is never a reason to speak to the player. A hand is exempt.
- `SurfaceBoundary`: hull of the polygon at each scan, the spawn, accepted taps and the character. Never shrinks, null
  stays null. Neither the live polygon between scans nor scanned objects grow it: both can reach metres past the edge.
  `crossesEdge`: a depth reading more than 15 cm below the plane at a tap or on the way to it refuses the tap.
- `Physics`: push-out never moves a character over a cliff; it stops short and counts as blocked. One standing inside the
  obstacle walks on, so nothing traps it; one only overlapping it may move but not go deeper.
- `SceneBuilder`: the near rim runs on the raw shadow; a sliver falls back to the raw shadow capped at a depth equal to
  its width; a compact label (`Semantics.isCompact`: cups, bottles, toys) is never deeper than wide; the top pixel follows
  the projected plane normal; heights are clamped to at least 5 mm.
- `ArSlot.onScanned` takes a `ScanAnswer` (`Found(objects, missed)`, `Failed`, `TooSteep`). TOO_STEEP is not a failure.
- Locked decisions unchanged: one anchor, scan is a button, PAUSED freezes and never resets, no Depth API in the geometry.

## Tests required
Done: `ConvexHullTest`, `ScanAngleTest`, `TableScanTest`, `SurfaceBoundaryTest`, `RealScanTest` (the Pixel's own masks and poses), and in `SceneBuilderTest`
`aBookKeepsItsFootprintAtAnyYawOnTheCpuImageGrid`, `theHeightDoesNotDependOnHowTheSensorImageLies`,
`aCupOnTheCpuImageGridHasNoSpike`, `aMaskIsNeverANoHeightObject`.
Tests not required: the Compose banner, RudeusArView wiring (checked on the Pixel below).

## Done when
- `scripts/verify.sh` exit 0
- Pixel check passes

## Pixel check (only you can do this)
Read with `scripts/device.sh log`. Each line is one `RudeusGate` key.
1. Point almost straight down at the table, press Scan: instantly "Too straight down", logs `scan_too_steep where=camera`, no SAM call.
2. Normal angle with a cup low in the frame, nearly under the phone: `scan_too_steep where=object`, the bubble tells you to hold the phone more upright.
3. Normal angle: objects as before. `jpeg=` in `scan_ms` gives the real grid (the numbers below assume 640x480; the first run read 1280x720).
4. Scan early while the plane is small, then tap far along the real table: the character walks there, logs `boundary_grown`, no fall.
5. Footprints on: the boundary loop grows after such a tap and redraws without a crash. Rescan: the character stays put.
6. Scenario button x3 with a FLY character: the day-5 script still runs.
7. Book, cup: footprints sit on the real objects at a normal angle.
8. Feet: stand the character on the bare table and walk it around: the table no longer cuts them off, and it still disappears behind a real cup. Still cut: raise `FEET_DEPTH_BIAS`. `tap_depth_gap=` + means the plane is below the table the Depth API sees, - above it (null: no depth hit at that tap); `feet_h=` above 0 on the bare table means a footprint is lifting it.
9. Real cup on the table, scan: keep the `scan_replay` and `scan_replay_mask` lines, they let :core replay this mask on the JVM. Footprints on: the cup's footprint is about as deep as wide; `scan_object` reads it about 8 cm tall.
10. Tap past the end of the table (a chair, the cabinet behind it): `tap_past_edge`, the character stays and the orange line does not move. `way=` in the `tap_depth_gap` line shows whether the ray-cast gives depth at all (all null but the last = only the tap itself is checked). Tap around the table: no valid tap is refused.
11. A finger in the corner of the frame while scanning: `scan_cut_by_frame`, no invisible wall there.

## Result (filled on acceptance)
- measured: synthetic perfect masks only, see the table in `ScanAngle.kt`. A 15 x 10 x 2 book over 600 random poses at 640x480 (elevation 30-70°, any yaw): PLATFORM 577, centre under 2 cm 587, area within x0.5-x1.6 593; lying lengthwise (yaw 80-100°) PLATFORM 76 of 83. The reviewer's own 600 poses at `ac819e3`, a similar but not identical set, gave 483, 541 and 551.
- Pixel 2026-11-03 03:56, one cold session, RudeusGate lines from the user's terminal: no Rudeus crash. `jpeg=1280x720`, so the 640x480 figures here are not this phone's grid. `scan_ms=6193` cold, 1 object. Step 4 only as far as the log shows: 9 `boundary_grown`, every tap accepted; no gate key exists for a fall, so "no fall" still needs the user's eyes. Steps 1, 2, 5, 6, 7 have no lines in this log: not checked. `scan_object cup-1 kind=OBSTACLE h=0.005` is the clamp, so the real height read 5 mm or less. T04 already recorded this phone reading a cup at 0.015, so the low reading predates this brief; whether this brief made it worse is unknown until a replay (step 9). The anchor moved 3.2 cm, 2.3 of them in height, in its first 10 s, then stayed within about 4 mm.
- feet (user, same session): the table cuts off the feet, also on an older screenshot (12:07), so not from this brief. No bundled sprite has more than 0.4% transparent margin below it. T21 had listed depth jitter on the feet as a risk. Fix: `FEET_DEPTH_BIAS` scales the quad about the camera, which leaves every pixel in place and only shrinks its depth; not run on the Pixel yet. Feet floating above the table is not fixed: candidates are a plane above the table, a footprint lifting the character (physics steps up to 4 cm onto a platform footprint), and no contact shadow; `tap_depth_gap` and `feet_h` tell the first two apart.
- Pixel 2026-11-03 04:16-04:21, `docs/evidence/t25-pixel-2026-11-03.log` (user + log, the fixes below are not yet in this run): feet whole on the 04:20 screenshots, user: "намного лучше"; hiding behind the cup not reported. Scenario ran through (`scenario_done ms=15005`) next to user taps. 23 numeric `tap_depth_gap`: -7.5 to +13 cm except one at -28.8 cm, past the end of the table.
- Pixel 2026-11-03 04:37-04:47, same log, with the boundary, cup and finger fixes in: user: "края стола определяет хорошо"; ladybug "все по кайфу". Two `tap_past_edge`, both at the real end of the table (-17.5 and -28.8 cm), none reported on the table. Cups and the cart read 6.8 to 8.8 cm. The ray-cast gives real depth on most taps; on 9 of 37 taps all samples were one value up to 1.2 m above the plane, now dropped by an on-ray check, not yet run. Hiding behind a cup not reported.
- open from that run, with a bee character: (1) a long polygon drawn away from the rabbit, which it matches in shape, in the camera view only: the mini-map has it in the right place, and between two screenshots it moved against the table, so it is not explained. Only with Footprints on. The rabbit's footprint itself was 42 cm deep, capped now (`aPlushToySeenLowIsNoLongWall`); not run. (2) FLY rises v²/2g = 28 cm at any size (log: up to 27 cm) with the phone about 32 cm above the table, so a flying character can reach the lens and look huge. Left as it is: the user's decision, 2026-11-03.
- boundary: the user saw it stretched past the side of the table. The plane polygon accepted taps up to about a metre past the table's end (`u=1.377 v=-1.327` read -28.8 cm, `u=1.773 v=-1.386` -2.2 cm, likely another surface at table height, not established), and the hull kept every polygon and tap. Now only scans and accepted taps grow it, and a depth drop refuses the tap. The line no longer follows the table's edges by itself. A rescan can still pull in an overshoot from that moment's polygon. Not run on the Pixel.
- real mug (`RealScanTest`, replay of the 04:19 scan, about 20° above the table): at `HEAD` 31.2 cm deep, 9.3 wide, 5.3 high; with this brief before the round cap 31.1, 9.3, 2.1, so the over-deep footprint is older than this brief, and the low height is it combined with this brief's top pixel; now 9.3 deep, 8.4 wide, 8.3 high. Cause: the mask's sides meet the radial lines at the top of the mug, so the near rim reflects into the whole shadow. Only round labels are capped: a real box seen low can still be too deep and too low. The same footprint explains one floating report: `feet_h=0.0269` far from the mug, standing on its 31 cm footprint after a jump.
- synthetic on the phone's grid (1280x720, its intrinsics), 600 book poses from a new seed, not the same set as above: PLATFORM 567, centre under 2 cm 581, area in range 591. Cylinder r=4 h=9 from 15° to 70°: height 9.1 to 13.6 cm.
- finger: two masks at the frame's corner came back "laptop" and "remote" and became obstacles 8.9 and 4.3 cm tall. Dropped now. A real object the frame cuts is dropped too until a scan sees it whole.
- review relayed from the planning session: a 3× character overlapping a cup at the rim walked through it. Fixed, `anOverlappingCharacterAtTheRimDoesNotWalkThroughTheCup`.
- known, not fixed: a plate in the cabinet behind the table read `plate-1 c=(1.766,-22.895) h=0.300`, an object 23 m away that counts in `objects=`.
- found on the way: at 640x480 the DP pass grew a spike up to 19 cm on an 8 cm cup; fixed by running the near rim on the raw shadow. The roll bug the audit reported is fixed too (the book's platform kind needed it). A cup at 85° reads 30 cm (the clamp) here, not the 0.54 cm the audit reported; only 89° reads near 0.
- APIs: no new ARCore or SceneView call. `frame.camera.pose`, `anchor.pose`, and `planePolygonWorld` (`Plane.getPolygon`, `getSubsumedBy`, `getCenterPose`) were already in use for the scan.
- known limits: about 4% of poses still read a lying book as an OBSTACLE, area about x0.4, height usually 5-13 cm and at worst 28 cm, because reflection cannot recover the depth. A book standing upright seen square-on reads flat: one view cannot tell it from a lying one, and the label says it lies flat. A tall box (non-flat label) can still read flat from above about 60°, 3 of 48 poses for a 20 x 3 x 25 box, and its footprint is too deep (x8 at 45°). A tap on a neighbouring surface within 3 cm of the anchor's height bridges the gap, and a convex hull fills the inner corner of an L-shaped table. A character a rescan puts inside a cup can pop out by up to 7 cm in one frame.
- not measured: real masks from a steep or square-on view, the real CPU image size, polygon overshoot past the true edge.
- review: `rudeus-reviewer` ran three passes with its own harness. The last verdict was FIX FIRST on the lengthwise book only; that fix follows its suggestion and is measured above, without a further independent pass.
- cut/deferred: depth-based geometry. Free play still has no way to reach a real cliff by tapping: taps past the edge hit the floor plane and are filtered, as before this brief. README's "the table edge a cliff" is only reachable in the debug scenario.
