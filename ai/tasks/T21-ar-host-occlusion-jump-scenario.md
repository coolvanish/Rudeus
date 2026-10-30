# T21 — ar-host-occlusion-jump-scenario

status: review
created: 2026-10-31
day: Day 5
gate: scripted 30 s scenario runs 3x in a row without code changes
cut-if-slips: cut line 4 (depth occlusion -> occluder boxes -> none); cut line 5 (affordances -> jump only)

## Goal
On the Pixel the character disappears behind the real cup (occlusion), jumps when the child taps Jump (flies with
FLY, cannot jump with ROLL), and a debug "Scenario" tap replays `Scenarios.DAY5` from `:core` as timed walk/jump
commands so the Day 5 gate can be run three times without touching code. Hand-review zone: a human runs every
iteration on the Pixel; keep the diff minimal and every decision under a `// HAND-REVIEW:` comment.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/RudeusArView.kt
- androidApp/src/main/kotlin/kz/rudy/ar/ar/GameLoop.kt
- androidApp/src/main/kotlin/kz/rudy/ar/ar/CharacterNode.kt (jump squash-stretch only)
- androidApp/src/main/kotlin/kz/rudy/ar/ar/Occluders.kt (new)
- this brief

Do NOT touch `core/**`, `shared/**`, `MainActivity.kt` (T23), `AndroidCaptureHost.kt` (orchestrator), Gradle files.
Inputs already merged on the branch: `ArSlot.ability / jumpRequest / scenarioRequest` (T20), `kz.rudy.ar.core.scenario.{Scenarios, ScenarioPlayer, ScenarioStep}` (T19).

## Contract
### (a) Occlusion — Plan A first, Plan B behind a constant
- Plan A: SceneView depth occlusion. `ARSceneView(cameraStream = rememberARCameraStream(materialLoader, creator = {
  createARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true } }))` with `config.depthMode =
  AUTOMATIC` when `session.isDepthModeSupported(AUTOMATIC)` (already configured). Verify the exact 4.31.0 signature
  with Context7 `/sceneview/sceneview` before writing it; if `rememberARCameraStream`'s `creator` does not exist in
  4.31.0, set the flag in a `LaunchedEffect` on the remembered stream instead.
- Plan B (`Occluders.kt`): `internal const val OCCLUDER_BOXES: Boolean` (default = the depth mode is NOT supported;
  also forced on by a second constant `FORCE_OCCLUDER_BOXES = false` for the side-by-side). Inside the session
  `AnchorNode`, one node per `LevelObject` whose kind is OBSTACLE, DYNAMIC or PLATFORM (never CLIFF): a `CylinderNode`
  (radius = sqrt(area / pi), `sideCount` 16) when `footprint.area / bboxArea >= 0.7` (a cup), else a `CubeNode` on the
  footprint's bounding box; height = `heightM`, base on the plane (center y = heightM / 2), position = plane coords
  as in `FootprintOverlay` (watch the v sign convention documented there). Material: ONE shared
  `materialLoader.createColorInstance(Color.Black)` with `setColorWrite(false)` and `setDepthWrite(true)` (Filament
  `MaterialInstance`; confirm the method names in the Filament Java API that SceneView 4.31.0 ships), so it writes depth
  only. Keyed on `(id, footprint, heightM)` like the overlay. The character's material is TRANSPARENT blending, so it
  is drawn after opaque occluders and depth-tested against them: no render-priority changes unless the Pixel proves
  otherwise (say so in the Result if you change priorities).
- Log once per session: `occlusion=depth|boxes|none depth_supported=<bool>`.

### (b) Jump + ability
- `GameLoop`: `fun jump()` = `body = Physics.jump(body, params)`; `fun setAbility(a: Ability)` = body.state.copy(ability = a)
  (main thread). `RudeusArView`: `LaunchedEffect(slot.ability) { loop.setAbility(it) }`; `LaunchedEffect(slot.jumpRequest)
  { if (slot.jumpRequest > 0) loop.jump() }`. A request while not tracking is ignored by physics (PAUSED) — log `jump_ignored`.
- `CharacterPoser`: DESIGN.md motion spec, minimal version: while JUMPING with vertical velocity > 0 (rising) scale
  (0.85, 1.15); falling (JUMPING with vv <= 0, or FALLING) scale (1, 1); for 120 ms after a LANDED event scale
  (1.25, 0.75) easing back to 1. The loop exposes what the poser needs (`verticalVelocity` is on `PhysicsBody`;
  record `landedAtMs` in GameLoop when `Physics` goes airborne -> ground). Feet stay on the plane (origin at the feet).

### (c) Scenario replay (debug)
- `LaunchedEffect(slot.scenarioRequest) { if (> 0) player = ScenarioPlayer(Scenarios.DAY5, now) }` (plain holder).
- In `onSessionUpdated`, while tracking and `player != null`: `for (step in player.due(now))` -> `WalkTo` ->
  `loop.walkTo(target)`, `Jump` -> `loop.jump()`; log `scenario_step idx=<n> at_ms=<t>`; when `player.finished` log
  `scenario_done ms=<elapsed>` and drop it. Targets are plane coords relative to the anchor (the script assumes the
  `Scenarios.day5Level` layout: cup at (0.15, 0), book at (0, 0.15), gap at (-0.20, 0)) — the human places real
  objects there, or runs it against the ARCore recording.
- A scan that lands while a scenario runs just swaps the level (today's behaviour); the scenario is not restarted.

## Tests required
- None (AR host). `./gradlew :androidApp:assembleDebug` must pass; `scripts/verify.sh` is the orchestrator's step.
Tests not required: rendering, occlusion.

## Steps
1. Read `.claude/skills/ar-sceneview/SKILL.md`, `RudeusArView.kt`, `GameLoop.kt`, `CharacterNode.kt`,
   `FootprintOverlay.kt` (frame convention), `core/scenario/*.kt`, `ArSlot.kt`, DESIGN.md motion table.
2. Context7 `/sceneview/sceneview`: `rememberARCameraStream`, `isDepthOcclusionEnabled`, `CylinderNode`, `CubeNode`;
   Filament `MaterialInstance.setColorWrite/setDepthWrite`. Never invent an API.
3. Implement (a), (b), (c); build `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :androidApp:assembleDebug --console=plain -q`
   from the MAIN checkout branch `day5/play` (this brief runs serially, no worktree).
4. Fill the Result: decisions, the Pixel checklist (what to look at for depth vs boxes, jump, scenario), logcat lines
   to grep. No git.

## Done when
- `:androidApp:assembleDebug` green; every changed file carries `// HAND-REVIEW:` for the new intent.
- Result has the Pixel checklist for: occlusion side-by-side (screenshot behind the cup, Plan A vs B), Jump/Fly/Roll
  per ability, Scenario x3 with `scenario_done` lines.

## Result (draft 2026-10-31, hand-review pending; `:androidApp:assembleDebug` green)
Files: `androidApp/.../ar/RudeusArView.kt`, `GameLoop.kt`, `CharacterNode.kt`, new `Occluders.kt`; every new intent
under `// HAND-REVIEW: T21`. No core/, shared/, Gradle or manifest change; no new dependency.

### decisions
- **APIs confirmed in the shipped 4.31.0 sources** (Gradle cache, not Context7's main-branch docs): `rememberARCameraStream(
  materialLoader, creator)` (ARSceneView.kt:1941), `ARCameraStream.isDepthOcclusionEnabled` var with a setter that swaps
  the camera material (ARCameraStream.kt:399), `NodeScope.CylinderNode(radius, height, center, sideCount, materialInstance,
  position, ..., apply)` and `CubeNode(size, center, materialInstance, position, ..., apply)` (SceneScope.kt:631/488; the
  cylinder axis is local Y, `center` is the axis midpoint), Filament 1.72.1 `MaterialInstance.setColorWrite/setDepthWrite`.
- **Occlusion default = Plan A (depth)** on a device with `isDepthModeSupported(AUTOMATIC)` (the Pixel). The decision is
  made ONCE in `sessionConfiguration` (`occlusionFor(depthSupported)`, Occluders.kt) and logged `occlusion=depth|boxes|none
  depth_supported=<bool>`. The flag is set on the remembered stream there rather than in a `creator` lambda, because the
  creator runs before the session exists and cannot know depth support. Flip for the side-by-side: `FORCE_OCCLUDER_BOXES
  = true` (Occluders.kt) -> boxes even with depth; `OCCLUDER_BOXES_WHEN_NO_DEPTH = false` -> "none" without depth (cut
  line 4 in two constants).
- **Plan B material deviates from the contract on purpose**: SceneView 4.31.0 ships `materialLoader.createOcclusionInstance()`
  (`assets/materials/occlusion.filamat`: opaque pass, depth write, no colour, double-sided) which is exactly "depth only";
  used instead of `createColorInstance(Black)` + `setColorWrite(false)`/`setDepthWrite(true)` (same effect, purpose-built
  shader, no PBR work). One shared instance; the MaterialLoader destroys it with the scene. No render-priority changes.
- Plan B geometry: cylinder (radius = sqrt(area / pi), 16 sides, at the centroid) when `area / bboxArea >= 0.7`, else a box
  on the bbox; base on the plane (`center.y = heightM / 2`); OBSTACLE / DYNAMIC / PLATFORM only, `heightM > 0`; keyed on
  `(id, footprint, heightM)`. Nodes sit directly in the anchor frame (local X = u, Z = v): no rotation and no v sign flip,
  unlike FootprintOverlay's Shape path.
- Jump / ability: `GameLoop.jump(): Boolean` (false when `Physics.jump` returned the same body -> host logs
  `jump_ignored mode= ability=`), `GameLoop.setAbility` (no-op when unchanged, so no spurious event diff),
  `GameLoop.landedAtMs` recorded from the LANDED event in the same diff the HUD gets. `LaunchedEffect(slot.ability)` and
  `LaunchedEffect(slot.jumpRequest)` in RudeusArView. Poser: rising (JUMPING, vv > 0) (0.85, 1.15); falling (1, 1);
  120 ms after LANDED (1.25, 0.75) easing out cubically to 1, overriding the walk bob. No anticipation phase (the jump
  launches on the tap frame; adding 80 ms of pre-jump delay would need a host-side timer: not in the minimal cut).
- Scenario: `Scenarios.DAY5.scaled(profile.scale)` (floor profile scales positions like `PhysicsParams.forProfile` scales
  speeds), started by `LaunchedEffect(slot.scenarioRequest)`; rejected without an anchor (`scenario_rejected`); steps fed
  in `onSessionUpdated` while tracking, before `loop.onFrame`; targets bypass the tap boundary check (script coordinates
  are relative to the anchor). A scan meanwhile only swaps the level.
  - **Every run starts at spawn** (wave-1 review): `GameLoop.restartAtSpawn()` puts the body at `level.spawn`, h = 0, no
    target, IDLE, vv = 0 (ability/radius kept; a PAUSED body stays paused and resumes IDLE). A FLY run ends IDLE at
    (-0.30, 0); replaying from there would walk through the gap and lose the BLOCKED beat. This is the debug replay, not the
    tracking path: PAUSED still freezes and never resets (locked decision untouched, said in the HAND-REVIEW comment).
  - **Simulated clock** (wave-1 review): `ScenarioPlayer` is started at and driven by `GameLoop.simMs`, the accumulated
    clamped dt physics actually stepped while tracking, not the wall clock. The hop/jump beats have 0.2-0.3 s windows; a
    0.7 s tracking stall or a frame hitch on the wall clock would make FLY come down in the gap. Consequence: a tracking
    loss simply pauses the script (no bunched steps on resume), and `scenario_done ms=` is simulated time (~25000 exactly
    plus one clamped frame), independent of real-time stalls.

### pixel checklist (grep `adb logcat -s RudeusGate`)
1. Session start: `occlusion=depth depth_supported=true` (Pixel). If `depth_supported=false` appears, expect `occlusion=boxes`.
2. **Occlusion Plan A**: place, scan the cup, walk the character behind the cup (tap past it). It must disappear behind the
   cup and reappear beside it. Watch the feet: depth-from-motion jitter may clip the bottom of the quad against the table
   or show a flickering edge near the cup rim -> screenshot to `docs/evidence/`. Move the phone slowly; depth needs motion.
3. **Occlusion Plan B** (side-by-side): set `FORCE_OCCLUDER_BOXES = true`, rebuild, repeat 2; log shows `occlusion=boxes`.
   The cup must hide the character with a hard edge matching the footprint; the character standing ON the book (platform)
   must stay visible (its feet at the box top). Expected: debug footprints are hidden inside the boxes. If the cylinder sits
   mirrored across the anchor's u axis the plane-frame assumption is wrong -> report, do not flip blindly. Decide A vs B from
   the two screenshots; whichever wins becomes the default by the constant.
4. **Jump** (JUMP ability, Play HUD Jump): stretch going up, neutral at the top, a visible squash on landing; no `jump_ignored`.
   Tap Jump twice quickly: the second logs `jump_ignored mode=JUMPING`. Under the Paused scrim (cover the camera):
   `jump_ignored mode=PAUSED`.
5. **Fly / Roll** (pick a bundled FLY and a ROLL character): FLY floats a long slow arc and crosses the gap at the script's
   10.4 s hop; ROLL moves ~2x faster and every Jump logs `jump_ignored ability=ROLL`.
6. **Scenario x3** (debug on, real cup at (0.15, 0), book at (0, 0.15), table edge or a CLIFF strip at (-0.20, 0); or the
   ARCore recording): tap Scenario three times, waiting for `scenario_done` each time; the character must snap back to
   the spawn point at each tap (also after a FLY run that ended past the gap). Expect per run: `scenario_start ... sim_ms=`,
   `scenario_step idx=0..4` with `at_ms=0, 4000, 9000, 10400, 11400`, then `scenario_done ms=` ~25000 (+ one frame; this is
   simulated time, so it stays ~25000 even if the run took longer in real time). HUD events in order: BLOCKED (cup),
   STEPPED_UP (book), JUMPED/LANDED (hop), then JUMPED/FELL/RESPAWNED (JUMP) or a landing past the gap (FLY). Cover the
   camera for ~1 s mid-run: the script must pause with the character and continue in step afterwards, not fire bunched
   taps. Gate = three `scenario_done` lines without a rebuild, FLY included.
