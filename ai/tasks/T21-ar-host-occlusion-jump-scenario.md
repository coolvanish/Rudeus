# T21 — ar-host-occlusion-jump-scenario

status: todo
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

## Result (filled on acceptance)
- decisions: ...
- pixel checklist: ...
