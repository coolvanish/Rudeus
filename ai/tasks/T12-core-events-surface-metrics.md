# T12 — core-events-surface-metrics

status: doing
created: 2026-10-29
day: Day 3 (tech debt + floor/outdoor support)
gate: Day 3 — 60 s walking without drift; capture -> character < 60 s (this brief adds no risk to either)
cut-if-slips: BLOCKED detection first (bubbles still work from the other events), then floor physics tuning

## Goal
`:core` gives the AR host and the HUD everything they need without interpreting physics themselves: a complete
`CharacterEvents` stream (incl. BLOCKED), physics parameters that scale to a floor/outdoor level, and a metrics run
that keeps the paid stages of a cancelled character. The contract types already exist (commit 2094177); this brief
makes them correct and tested.

## Owns
- core/src/commonMain/kotlin/kz/rudy/ar/core/domain/CharacterEvent.kt, Surface.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/physics/** (Physics.kt, PhysicsParams.kt)
- core/src/commonMain/kotlin/kz/rudy/ar/core/metrics/MetricsLog.kt
- core/src/commonMain/kotlin/kz/rudy/ar/core/pipeline/CharacterPipeline.kt — ONLY the CancellationException paths
- core/src/commonTest/** (new tests + edits to PhysicsTest, MetricsLogTest, CharacterPipelineTest)
- .claude/skills/metrics/SKILL.md (schema note for the new field)
Not yours: anything in shared/, androidApp/, cli/; `Semantics.kindFor` (T04).

## Contract
1. **PhysicsParams per surface.** Add `stepUpMaxM: Float = Semantics.STEP_UP_MAX_M` and
   `platformMaxHeightM: Float = Semantics.PLATFORM_MAX_HEIGHT_M` to `PhysicsParams`; `Physics` reads them from params
   everywhere it reads the `Semantics` constants today. `PhysicsParams.forProfile` fills them from `SurfaceProfile`.
   `PhysicsParams.DEFAULT` behaviour is unchanged (all existing tests pass untouched).
2. **BLOCKED.** `PhysicsBody` gains `val blocked: Boolean = false` (serializable, default false): true after a step in
   which the character was `Mode.WALKING` toward a target and push-out cancelled more than half of that step's intended
   horizontal displacement (dt > 0). False on any other step. `CharacterEvents.between(prev: PhysicsBody, next: PhysicsBody)`
   overload = `between(prev.state, next.state)` plus `BLOCKED` on the rising edge of `blocked` only (never repeated
   while it stays true; a new `walkTo` after being blocked may emit it again).
3. **CharacterEvents.between(prev: CharacterState, next: CharacterState)** keeps the current semantics; fix anything
   the tests below show wrong. `PLACED` is never emitted by core (host-only). Identical states -> empty list.
4. **Cancelled character in the run JSON.** `MetricsLog.abortCharacter()` appends the in-progress character with its
   recorded stages, `gateScore = 0`, `gatePassed = false`, `retries` = attempts so far − 1 (≥ 0) and a new
   `CharacterMetrics.aborted: Boolean = false` field (last field; runs written before parse as false).
   `MetricsLog.summaryOf` excludes aborted characters from `n`, pass rate, p50/p90 and cost per character (their cost
   is still in the JSON, honestly). `CharacterPipeline.run` calls `metrics.abortCharacter()` in its outer
   `CancellationException` branch before rethrowing (and never `endCharacter` for a cancel). No other pipeline change.
5. **Semantics constants** stay; `SurfaceProfile.TABLE` must equal them (test).

## Tests required
- `CharacterEventsTest`: one case per event (STARTED_WALKING, ARRIVED, JUMPED, LANDED, FELL, RESPAWNED, PAUSED, RESUMED,
  STEPPED_UP), `unchangedStateEmitsNothing`, `blockedFiresOnceOnRisingEdge`, `arrivedNotEmittedForIdleToIdle`.
- `PhysicsTest` additions: `blockedIsSetWhenWalkingIntoAnObstacle` (walk at a 9 cm cup; blocked true while pinned,
  false once the target is cleared), `blockedIsFalseWhenPushOutIsSmall`.
- `PhysicsSurfaceTest` (new): `floorJumpApexScalesWithProfile` (apex within 5 % of FLOOR_SCALE × table apex),
  `floorWalkIsFourTimesFaster` (same distance, 1/4 the steps ± 1), `twelveCmBoxIsAPlatformOnTheFloorButAnObstacleOnTheTable`
  (step-up succeeds with `forSurface(FLOOR)`, push-out with DEFAULT), `tableProfileMatchesSemanticsConstants`.
- `MetricsLogTest` additions: `abortedCharacterKeepsStagesAndIsExcludedFromSummary`, round-trip of `aborted` through JSON,
  old JSON without the field still parses.
- `CharacterPipelineTest` addition: `cancelDuringStylizeRecordsAbortedCharacter` (FakeModels whose stylize suspends
  forever; cancel the job; `metrics.toRun()` has one character with `aborted = true` and the cutout stage).
Tests not required: anything about rendering, the HUD, or the CLI.

## Steps
1. Read Physics.kt, PhysicsParams.kt, CharacterEvent.kt, Surface.kt, MetricsLog.kt, the two catch blocks in
   CharacterPipeline.run, and the existing PhysicsTest/MetricsLogTest to reuse their helpers.
2. Write the tests first (they fail), then implement 1–4.
3. `./gradlew :core:jvmTest`; read `core/build/test-results/jvmTest/*.xml`; report counts.

## Done when
- `./gradlew :core:jvmTest` green (report the total; was 132 + the tests you add); `scripts/verify.sh` is run by the orchestrator.
- No public signature from commit 2094177 removed (`SurfaceProfile.of`, `PhysicsParams.forSurface/forProfile`,
  `CharacterEvents.between(CharacterState, CharacterState)`); additions only.

## Result
