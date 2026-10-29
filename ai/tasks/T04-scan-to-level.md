# T04 — scan-to-level

status: review
created: 2026-10-28
day: Day 4
gate: 5 objects within 2 cm, character routes around; scan < 6 s
cut-if-slips: hand interaction (cut line 3) first; never the scan itself

## Goal
The explicit Scan button turns the real table into a `Level`: cups block, books lift, the table edge drops.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/scan/** (ScanInput assembly from frame + plane; debug footprint overlay)
- core: only via a separate brief if `SceneBuilder` needs changes (it is already tested).

## Contract (revised 2026-10-29 after T15: the plane maths and mask handling are in :core already)
- On Scan (the Scan button in Scanning or Rescan in Play; `ArSlot` gets `scanRequests`/a `onScan` hook — add to the
  frozen contract with defaults only): `frame.acquireCameraImage()` (YUV → JPEG, ≤ 1280 px long side), save
  `frame.camera.pose` (sensor frame, NOT displayOrientedPose), `frame.camera.imageIntrinsics` scaled to the JPEG size,
  the anchor pose, and the hit plane's `polygon` transformed through `centerPose` to world points.
  Then in :core: `ScanInput.fromAnchor(anchorPose, cameraPose, intrinsics, ScanMasks.of(segmentResult), worldPolygon)`
  and `SceneBuilder.build(input, previous, SurfaceProfile.of(surface))`. The scan runs on IO; the AR loop keeps
  stepping the old level until the new one is swapped in on the main thread (`GameLoop.level = ...`).
- `MetaModels.segment(ref, jpeg, Jobs.TABLE_PROMPTS or ["cup","book","phone","bottle","hand","object"])` via the
  host's `MetaApi` (AndroidCaptureHost owns it; expose a `suspend fun scan(...)` on the host side — no new :shared
  network code). Floor mode prompts add "box","shoe","bag","ball".
- Timing: `scan_ms=` in the log; `MetricsLog.recordScan(objects, ms, withinCm = emptyList())` (accuracy is measured by
  the human with a ruler and filled into the brief).
- Scan result → the HUD: `ArSlot.onScanned(objects: Int)`/failure → `ScanState.DONE` with the real count, or NO_PLANE
  when the segment call failed or found nothing. Replace App's fake scan loop with the real one (the overlay still
  animates for the real duration).
- Re-scan keeps ids (centroid match <= 5 cm); DYNAMIC objects re-placed.
- Debug overlay: footprints drawn as flat polygons on the plane in the ObjectKind tints from DESIGN.md (one flat
  Filament geometry per object, child of the AnchorNode at h = 0.002 m), visible only when `slot.debug`; the HUD's
  `FootprintMiniMap` gets the real `Level` through `ArSlot.onLevel(level)`.

## Tests required
- `scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame` (core geometry test if a helper is added).
Tests not required: overlay rendering.

## Done when
- `scripts/verify.sh` exit 0
- Measured: 5 objects, footprint centroid error <= 2 cm each (ruler), scan wall time < 6 s, logged in runs/ `scans`.

## Result
Draft 2026-10-29 (rudeus-fable-high, worktree `day4/scan` on c5ec02d). Not committed; hand-review zone touched.
Verified in the worktree: `:core:jvmTest` 164/0 failed, `:shared:testAndroidHostTest` 12/0, `:androidApp:assembleDebug`
OK (with `ANDROID_HOME` set; the worktree has no machine-local SDK file, so run `scripts/verify.sh` from the main checkout
after merging). No Gradle edits, no new dependency. The required core test
(`scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame`) already exists from T15; :core is untouched.

### Files
| File | Change |
|---|---|
| `shared/.../ArSlot.kt` | Contract extended with defaults only: `scanRequest: Int = 0` (one increment = one scan), `onScanned: (Int?) -> Unit` (count, null = failed; exactly one call per request), `onLevel: (Level) -> Unit`. |
| `shared/.../App.kt` | Fake timers replaced. Scan/Rescan bump `scanRequest`; the overlay arc runs to 0.95 over `SCAN_DEFAULT_MS` and holds until the host answers (real duration). Scanning: count -> `DONE`, null/0 -> `NO_PLANE` (+failures, Skip after 2). Play: `hud.scanning` ends on the answer, `level` feeds `FootprintMiniMap`. Host-less shell (no AR view to answer) still ends after 4 s with an honest 0. Session end resets `level` and `scanRequest`. |
| `androidApp/.../scan/TableScanner.kt` (new) | Host-only seam: `segment(ref, jpeg, prompts)` + `recordScan(objects, ms)`; `ScanPrompts.TABLE` = cup, book, phone, bottle, hand, object; `FLOOR` adds box, shoe, bag, ball. |
| `androidApp/.../scan/ScanCapture.kt` (new) | Main-thread grab (`acquireCameraImage` YUV_420_888 -> NV21, closed in `finally`; `Camera.getPose()` sensor frame; `getImageIntrinsics()`; anchor pose; anchor plane polygon via `getPolygon()` x/z pairs through `centerPose`, following `subsumedBy`). IO half: NV21 -> JPEG (YuvImage q90) -> <= 1280 px -> `segment` under a 30 s timeout -> `ScanMasks.of` -> intrinsics rescaled to the masks' grid -> `ScanInput.fromAnchor` -> `SceneBuilder.build(input, previous, profile)`. Never throws; logs `scan_ms= segment_ms= objects=` and one `scan_object` line per object. |
| `androidApp/.../scan/FootprintOverlay.kt` (new) | One `ShapeNode` (Earcut flat polygon) per object in the DESIGN.md tint (unlit transparent, verified double-sided from the .filamat chunks), at h = 0.002 m under the AnchorNode; boundary as a closed `PathNode` loop in Hazard. Nodes keyed on (id, polygon). |
| `androidApp/.../ar/RudeusArView.kt` | `scanner: TableScanner?` param; `anchorPlane` holder set at anchor creation; `LaunchedEffect(slot.scanRequest)` arms a plain `scanPending` flag; the next `onSessionUpdated` grabs that frame and launches the IO scan; on return (main thread) `loop.level = built`, `onLevel`, `recordScan`, `onScanned`. Overlay composed only while `slot.debug`. |
| `androidApp/.../di/AndroidCaptureHost.kt` | Implements `TableScanner` over the same `MetaApi` (metered as `sam-table`) and `MetricsLog`; `recordScan` writes the run file; `latestRun()` now also counts scans (bundled-character sessions get a run). |
| `androidApp/.../MainActivity.kt` | `RudeusArView(modifier, slot, scanner = host)`. |

### Decisions
- Trigger is a counter in the slot (`scanRequest`), not a callback the host registers: it is Compose-idiomatic, the
  slot is already re-created cheaply, and a request can never fire per frame. The host answers every request exactly
  once (`onScanned`), including the rejections (no anchor, not tracking, a scan already running, no image).
- The camera image is grabbed inside `onSessionUpdated` (only the current frame can be acquired); only the NV21 copy
  happens on the main thread (~0.5 MB at 640x480), JPEG + network + geometry run on IO. Physics keeps stepping the
  old level; the swap is one reference write on the main thread. PAUSED never resets anything.
- Boundary = the anchor plane's convex polygon at scan time. Outside it is a cliff (`Level.boundary`), spawn stays
  `(0, 0)` = where the child placed the character.
- Overlay: SceneView's `ShapeNode` (not a CubeNode per bbox): a real flat polygon per footprint, no custom buffers.
  Boundary line is 1 px and solid (Filament lines), not dashed.
- Plane-coordinate conversion stays in `:androidApp` (`Pose.toPose3`, `planePolygonWorld`); `:core` sees Pose3/Vec3.
- Hand (DYNAMIC) objects are simply rebuilt on every scan; ids re-match within 5 cm by `SceneBuilder`.

### Risks / open points
1. **Cross-label duplicates**: `ScanMasks.of` suppresses overlaps within one prompt only. "object" will very likely
   also match the cup/book -> two footprints for one thing, inflating the count and doubling an obstacle. Check the
   `scan_object` lines on the Pixel; if it happens, either drop "object" from `ScanPrompts.TABLE` (one-line host
   change) or add cross-label suppression to `ScanMasks` (core brief, tested).
2. **Scan < 6 s**: 6 prompts at `MAX_SAM_CONCURRENCY = 3` = two rounds of SAM; the floor's 10 prompts = four. If
   `segment_ms` is over budget, cut the prompt list before touching anything else.
3. **ARCore polygon vs real table edge**: the detected polygon is usually smaller than the table, so the character
   falls (and respawns) before the physical edge, and a small early polygon could put the current position outside
   the boundary at the moment of the swap. If that reads badly on video, keep `boundary = null` on the table (one
   line in ScanCapture) and use cliffs only on the floor.
4. **Mirror check**: the overlay's v axis sign depends on SceneView's Euler handedness (derived from the source,
   not yet seen on a device). If footprints appear mirrored across the anchor's u axis, flip the sign in
   `FootprintOverlay.kt` (one character). The physics is unaffected either way; only the overlay would be wrong.
5. **0 objects -> NO_PLANE** (the brief's rule) shows the "No table found / use a patterned cloth" banner for an
   empty-but-detected table. Copy is misleading there; a dedicated "nothing on the table" string is a Day 6 polish.
6. `scanBusy` is only cleared when the running scan returns; a tracking loss during a scan ends the overlay (PAUSED)
   but a Scan tap before the old call returns is rejected (-> NO_PLANE). Expected within the 30 s timeout.

### Pixel checklist (Day 4 gate: 5 objects within 2 cm, scan < 6 s)
1. Install (`./gradlew :androidApp:installDebug && scripts/device.sh run`), bundled character, Table, place the
   anchor after the plane settles (10 s), tap Scan with 5 objects in view (cup, bottle, book, phone, hand or a toy).
2. Logcat (`scripts/device.sh log`): one `scan_ms=` line per tap, none without a tap (never per frame); `objects=`
   equals the HUD count; one `scan_object` line per real object (see risk 1).
3. Footprints toggle on: each footprint sits on its object (not mirrored, risk 4); mini-map matches; boundary loop
   follows the table top.
4. Ruler: for each of the 5 objects, distance from the overlay centroid (or the mini-map) to the object's real base
   centre; fill the table below and copy the five numbers into the latest `runs/<ts>.json` `scans[].withinCm`
   via the brief (the app writes `withinCm = []`).
5. Character routes around a cup (BLOCKED bubble), steps onto a book (PLATFORM, h <= 4 cm), falls off the boundary and
   respawns at the anchor.
6. Rescan with the cup moved 10 cm: the cup's id stays (`cup-1`) when moved <= 5 cm, a new id beyond that; hand
   re-placed.
7. Cover the camera during a scan: Paused scrim, no crash, no reset; on resume a new Scan works.
8. Wi-Fi off, Scan: "No table found" (failed call) within the 30 s timeout, Skip appears after the second try.

| # | object | label/id | centroid error (cm) | height est. (cm) | real height (cm) | scan_ms | segment_ms |
|---|---|---|---|---|---|---|---|
| 1 | cup | | | | | | |
| 2 | bottle | | | | | | |
| 3 | book | | | | | | |
| 4 | phone | | | | | | |
| 5 | hand / toy | | | | | | |
