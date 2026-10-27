# T04 — scan-to-level

status: todo
created: 2026-10-28
day: Day 4
gate: 5 objects within 2 cm, character routes around; scan < 6 s
cut-if-slips: hand interaction (cut line 3) first; never the scan itself

## Goal
The explicit Scan button turns the real table into a `Level`: cups block, books lift, the table edge drops.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/scan/** (ScanInput assembly from frame + plane; debug footprint overlay)
- core: only via a separate brief if `SceneBuilder` needs changes (it is already tested).

## Contract
- On Scan: grab frame, save `camera.pose`, `imageIntrinsics`, anchor pose, plane polygon -> `ScanInput`
  (`PlaneFrame` from the anchor pose: origin = anchor position, normal = plane normal, uAxis/vAxis = anchor X/Z).
- `MetaModels.segment(ref, jpeg, ["cup","book","phone","bottle","hand","object"])` -> `LabeledMask`s -> `SceneBuilder.build(input, previous)`.
- Re-scan keeps ids (centroid match <= 5 cm); DYNAMIC objects re-placed.
- Debug overlay: footprints drawn as flat polygons on the plane in the ObjectKind tints from DESIGN.md; toggle in HUD.

## Tests required
- `scanInputFromAnchorPoseBuildsOrthonormalPlaneFrame` (core geometry test if a helper is added).
Tests not required: overlay rendering.

## Done when
- `scripts/verify.sh` exit 0
- Measured: 5 objects, footprint centroid error <= 2 cm each (ruler), scan wall time < 6 s, logged in runs/ `scans`.

## Result
