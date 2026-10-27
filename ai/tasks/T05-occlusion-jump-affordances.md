# T05 — occlusion-jump-affordances

status: todo
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
