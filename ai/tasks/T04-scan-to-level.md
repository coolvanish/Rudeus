# T04 — scan-to-level

status: doing
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
