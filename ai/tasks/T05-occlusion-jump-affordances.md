# T05 — occlusion-jump-affordances

status: doing (split 2026-10-31: T19 core scenario script, T20 Jump/Fly HUD + ArSlot, T21 AR host occlusion/jump/replay)
created: 2026-10-28
day: Day 5
gate: scripted 30 s scenario runs 3x in a row without code changes
cut-if-slips: depth occlusion -> occluder boxes -> none (cut line 4); affordances -> jump only (cut line 5)

## Goal
The character disappears behind the real cup, jumps onto the book, and (if winged) flies over the gap.

## Owns
- androidApp/src/main/kotlin/kz/rudy/ar/ar/Occluders.kt, Affordances.kt
- shared/.../ui/components/AbilityChip.kt (wiring only)

## Contract
- Plan A: `depthMode = AUTOMATIC` + SceneView depth occlusion. Decide by Day 5 noon with a side-by-side screenshot.
- Plan B: one `CubeNode` per `LevelObject`, size from footprint bbox x `heightM`, material `depthWrite=true, colorWrite=false`.
- Jump: HUD button -> `Physics.jump`; FLY -> same button, lower gravity; ROLL -> button hidden, 2x speed.
- Scenario (30 s): place -> walk -> blocked by cup -> jump onto book -> fly over gap. Scripted as a list of taps with
  timestamps so it can be replayed on the ARCore recording.

## Tests required
- None new in core (abilities are covered by physics tests).
Tests not required: rendering, occluders.

## Done when
- `scripts/verify.sh` exit 0
- The scenario runs 3x consecutively on the real table; screenshots in docs/evidence/ (committed, small).

## Result

### Split (orchestrator, 2026-10-31)
This umbrella brief is executed as three disjoint briefs so agents can run in parallel:
- T19 `core-scenario` — `Scenario` / `ScenarioRunner` / `ScenarioPlayer` in `:core`, Day 5 beat sheet, 3x determinism test (JVM half of the gate).
- T20 `shared-jump-offline-hud` — `ArSlot.ability/jumpRequest/scenarioRequest`, Jump/Fly button (hidden for ROLL), debug Scenario button.
- T21 `ar-host-occlusion-jump-scenario` — depth occlusion (Plan A) with occluder boxes behind a constant (Plan B), `GameLoop.jump`, scenario replay from the frame loop.
The hand = stretch (plan) is not attempted. Gate evidence lands in those briefs' Result sections; the Pixel run is the human's.
