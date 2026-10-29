# T02 — ar-anchor-billboard-walk

status: doing
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

### Draft 1 (2026-10-28, branch day3/table-gate) — builds, untested on the Pixel

Design decisions (kept to the plan's thresholds):
- **Anchor gate.** The Day 1 probes measured an anchor placed 3.7 s after plane detection jumping 4.7 cm in its first
  10 s vs 0.07 cm / 50 s for one placed later. The first tap now creates the anchor only if the *hit plane* has been
  TRACKING for `ANCHOR_PLANE_SETTLE_MS = 2000` (`PlaneSettle.kt`, fed from `frame.getUpdatedTrackables(Plane)` per
  frame until the anchor exists). Earlier taps log `anchor_rejected plane_age_ms=…` and show a 1.5 s hint
  (`StringId.ar_wait_table`, kk/ru/en). Plane dots stay on until the anchor exists.
- **Character = child of the AnchorNode.** SceneView's `AnchorNode` keeps its transform equal to `anchor.pose` every
  frame, so the billboard `PlaneNode` is parented to it and its local position is the plane frame directly:
  `localPosition(u, v, h)` in `PlaneCoords.kt` (the inverse of `planeCoords`). No per-frame world-pose maths and the
  anchor refinement is applied by SceneView, not by us.
- **Billboard.** `PlaneNode(size = Size(w, 0.06, 0), center = Position(y = 0.03), normal = Direction(z = 1))` so the
  feet are at the node origin and the front is +Z; yaw = `atan2(camU − u, camV − v)` about Y only (camera position
  comes through `planeCoords(anchor, frame.camera.pose)`). Material: `materialLoader.createImageInstance(texture)` —
  decoded from the 4.31.0 AAR, `image_texture.filamat` is UNLIT / TRANSPARENT / cull NONE, so no material tweaks and
  the facing flip is a plain `scale.x = −1`. Flip has a 0.15 dead-band on `facing · right` so walking straight at the
  camera does not flicker. Walk anim = squash-and-stretch bob (±8 % Y, ∓5 % X, 400 ms) while `Mode.WALKING`; sprite
  sheet not used (Day 3 plan: pick one).
- **Game loop.** `GameLoop.kt` (plain holder) runs in `onSessionUpdated`: `dt = min(now − last, 50 ms)`, then
  `Physics.resume` → `Physics.step` → write node transform. Camera or anchor not TRACKING → `Physics.pause`, dt clock
  reset so the gap is not simulated. Nothing recreates the anchor or resets the body. Empty `Level` (no objects,
  `boundary = null`) until T04. Walk speed is `PhysicsParams.walkSpeedMps` = 0.05.
- **Sprite.** `androidApp/src/main/assets/sprites/toy_dino.png` (copy of docs/bundled/toy_dino). `spriteFromBytes()`
  is the T03 entry point for `CharacterResult.sprite`; `DEMO_SPRITE_FACES_RIGHT = false` because the dino faces left.
- No `:core` changes were needed: `CharacterState.facing` and `Mode.WALKING` already give the host what it needs.
- Rec button moved from TopEnd to CenterEnd (the shared Play HUD owns the top-right corner).

Files: `androidApp/src/main/kotlin/kz/rudy/ar/ar/{RudeusArView,CharacterNode,GameLoop,PlaneSettle,PlaneCoords}.kt`,
`androidApp/src/main/assets/sprites/toy_dino.png`, `shared/.../ui/Strings.kt` (+1 string). No Gradle edits, no new
dependencies. `scripts/verify.sh` exit 0.

### On-device check (Pixel 9 Pro XL) — fill the table below
1. Capture → pick a bundled character → Scanning → **Skip** → Play. Plane dots appear on the table.
2. Tap the table within ~1 s of the dots appearing: a hint pill ("Сәл күт…") shows, logcat `anchor_rejected`.
3. Wait, tap again (≥ 2 s after dots): dots vanish, the dino stands upright at the tap, feet on the table, facing you,
   ~6 cm tall. Check: sprite upright (not upside-down), transparent background, not mirrored oddly. Logcat
   `anchor t_ms=0` and `anchor_plane_age_ms=…`.
4. Tap 20 cm to the right: it walks there in ~4 s, bobbing, facing right; tap left: it flips. Walk the phone around
   the table: the quad keeps facing the camera and stays upright.
5. Cover the camera 3 s: it freezes (PausedOverlay not wired yet — judge by motion); uncover: it resumes where it
   was, same anchor.
6. Drift: tap the same cup-edge point at t = 0 and t = 60 (`tap u= v=` lines); Δ < 1 cm. Walk around the cup for 60 s.

| check | result |
|---|---|
| hint before 2 s | |
| upright, transparent, 6 cm | |
| walks to tap, flips | |
| billboard follows camera | |
| pause/resume keeps anchor | |
| 60 s drift (cm) | |

Known risks: sprite V orientation (Filament `flipUV` default is assumed; fix = `uvScale = UvScale(1f, -1f)` if upside
down); the AR view is re-created when the screen switches Scanning → Play (slot recomposes in a new parent), so place
the anchor in Play; `PausedOverlay` / `trackingLost` in the HUD is not wired (needs a callback through `App`).

- Review (rudeus-reviewer, draft 1): merge after fixes → done by the orchestrator: AnchorNode `isPositionEditable = false`
  and the sprite node not touchable (a drag detached and recreated the session anchor and froze the loop); anchor
  settle 2 s → 10 s (2 s sat inside the measured 4.7 cm jump window); walk taps only on the anchor's surface (±3 cm
  height, no walking off the table edge into the air); the hint timer restarts on every early tap. Open: Scanning and
  Play each create their own AR session (place the anchor in Play, don't leave Play during the 60 s run);
  PausedOverlay is not driven by the loop yet.
- Pixel, Oct 28 16:14–16:20 (`docs/evidence/t02-pixel-2026-10-28.log`, screenshot `t02-dino-on-table-2026-10-28.jpg`):
  the dino stands upright on a white table at the tap, ~6 cm; the 10 s settle gate rejected early taps
  (`anchor_rejected`) and accepted at 13.4 s / 14.0 s plane age. Anchor world pose, run A: 0.1 cm for 20 s, 3.2 cm at
  30 s, then 14–25 cm at 50–80 s together with the tapped plane height (the whole world frame moved, a map
  correction while walking around), back to 3.2 cm after a tracking loss at 90 s. Run B: 0.4–0.7 cm over 30 s.
  Open: whether the dino stayed on its real spot during run A's excursion (human eye), walking to taps confirmed?

### Day 3 gate verdict (orchestrator, 2026-10-29, Pixel not attached — see ai/day3-human.md)
| Criterion | Status | Evidence |
|---|---|---|
| 60 s walking around a real cup without drift | UNTESTED (Pixel offline during the autonomous run; Oct 28 run A showed a 14–25 cm map correction at 50–80 s, run B 0.4–0.7 cm / 30 s) | docs/evidence/t02-pixel-2026-10-28.log; the T02 fix for the second session (one AR slot across Scanning→Play) is in 54517e3 |
| capture → character < 60 s | UNTESTED (needs the key build on the Pixel; live latency 40–60 s for a first-try toy) | runs/ live run 5: p50 117 s with retries, 29 s first-try — gate at risk, reported not hidden |
