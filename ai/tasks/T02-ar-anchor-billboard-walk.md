# T02 — ar-anchor-billboard-walk

status: todo
created: 2026-10-28
day: Day 3
gate: 60 s walking around a real cup without drift
cut-if-slips: never cut (the demo is this)

## Goal
The character stands on the real table and walks to where the player taps, routed by `:core` physics.
Hand-review zone: a human runs every iteration on the Pixel.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/ar/** (RudeusArView, PlaneCoords, CharacterNode, GameLoop)
- androidApp/src/main/kotlin/kz/rudy/ar/MainActivity.kt

## Contract
- One anchor per session: first plane tap creates it; later taps = `Physics.walkTo` with `planeCoords(anchor, hitPose)`.
- Billboard quad (Y-only) textured with the sprite PNG from `CharacterResult.sprite`; height 6 cm, width from the
  sprite aspect. Use SceneView's plane/quad geometry node with an unlit material; confirm the 4.31 API on Context7
  `/sceneview/sceneview` ("PlaneNode", "materialLoader.createImageInstance") before writing it.
- Game loop: a `LaunchedEffect` ticking at the frame callback (`onSessionUpdated`), calling `Physics.step(body, level, dtMs)`
  with the real dt (clamped to 50 ms) and writing the node's position via `toWorld(anchor, pos, h)`.
- `TrackingState.PAUSED` -> `Physics.pause`, resume on `TRACKING`. Never recreate the anchor.
- A `Level` with no objects until T04; `boundary = null`.

## Tests required
None (AR host). The physics it drives is already tested in `:core`.
Tests not required: anything in androidApp.

## Steps
1. Camera permission flow (done in MainActivity) -> ARSceneView visible with plane dots.
2. Tap -> anchor -> quad appears upright at the hit, facing the camera.
3. Tap elsewhere -> walks at 5 cm/s, flips facing. 4. Pause/resume on tracking loss. 5. 60 s drift check.

## Done when
- `scripts/verify.sh` exit 0
- Video or logcat evidence of 60 s walking around a cup with < 1 cm drift (compare tap-to-arrival error at t=0 and t=60).

## Result
