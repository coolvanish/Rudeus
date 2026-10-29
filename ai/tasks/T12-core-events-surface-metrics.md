# T12 — core-events-surface-metrics

status: done
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
2026-10-29 (rudeus-opus-extra). `./gradlew :core:jvmTest` green: **154 tests, 0 failures, 0 errors** (132 before + 22:
CharacterEventsTest 11 new, PhysicsSurfaceTest 5 new, PhysicsTest 12 -> 14, MetricsLogTest 5 -> 8,
CharacterPipelineTest 25 -> 26). `:cli:compileKotlin` OK. `scripts/verify.sh` left to the orchestrator.

**Files changed** (all under `core/src` unless noted)
- `commonMain/.../physics/PhysicsParams.kt`: `stepUpMaxM`, `platformMaxHeightM` (last two ctor params); `forProfile` fills them.
- `commonMain/.../physics/Physics.kt`: `PhysicsBody.blocked`; every `Semantics` threshold now read from params; blocked
  computed in `step`; `walkTo(body)` clears `blocked` on a new target.
- `commonMain/.../domain/CharacterEvent.kt`: transition fixes (below) + `between(PhysicsBody, PhysicsBody)`.
- `commonMain/.../metrics/MetricsLog.kt`: `CharacterMetrics.aborted`, `abortCharacter`, `summaryOf` skips aborted.
- `commonMain/.../pipeline/CharacterPipeline.kt`: outer `CancellationException` branch only (+ one KDoc sentence).
- Tests: `commonTest/.../domain/CharacterEventsTest.kt` (new), `commonTest/.../physics/PhysicsSurfaceTest.kt` (new),
  additions to `PhysicsTest`, `MetricsLogTest`, `CharacterPipelineTest`.
- `.claude/skills/metrics/SKILL.md`: `aborted` schema note. `Surface.kt` unchanged (already matched the contract).

**Public API added** (additions only; nothing from 2094177 removed)
- `PhysicsParams.stepUpMaxM: Float = Semantics.STEP_UP_MAX_M`, `PhysicsParams.platformMaxHeightM: Float = Semantics.PLATFORM_MAX_HEIGHT_M`.
- `PhysicsBody.blocked: Boolean = false` (last field).
- `Physics.groundHeight(level, pos, feetH, params = DEFAULT)`, `Physics.blocks(o, feetH, params = DEFAULT)`: trailing defaulted param.
- `CharacterEvents.between(prev: PhysicsBody, next: PhysicsBody): List<CharacterEvent>`.
- `CharacterMetrics.aborted: Boolean = false` (last field).
- `MetricsLog.abortCharacter(attempts: Int = <highest attempt among recorded stages>): CharacterMetrics?` (null, no-op,
  when no character is in progress, so any cancellation path may call it).

**`between(CharacterState, CharacterState)` fixes** (what the per-event tests showed wrong)
1. JUMPING -> WALKING (landing mid-walk) emitted STARTED_WALKING (the LANDED branch was unreachable) -> LANDED.
2. FALLING -> IDLE / WALKING (end of a step down from a book) emitted nothing / STARTED_WALKING -> LANDED.
3. RESPAWNING -> IDLE emitted `[RESPAWNED, STEPPED_UP]` (h goes from -5 cm to 0) -> RESPAWNED only; STEPPED_UP now needs
   both states IDLE/WALKING.
4. A FLY character coming down over a cliff goes JUMPING -> RESPAWNING (never FALLING) and emitted nothing -> FELL.

**Measured**
- Floor jump apex 0.31744 m = 4.000 x table 0.07936 m (relative error 0.0: v and g scale together); air time 41 steps on both.
  The 16 ms Euler apex is 4.8 % under the analytic v²/2g on both surfaces.
- 0.4 m walk: 500 steps on the table, 125 on the floor (exactly 1/4).
- Head-on at a 9 cm cup from 8 cm away: 100 free steps, blocked from step 101 on, pinned at u = -0.07.
- Brushing a cup (cup 4 cm + character 3 cm = 7 cm) at a lateral offset of: 0 -> pinned for good; 2 cm -> 131 blocked steps, then
  it slides round and arrives; 3 cm -> 61; 4 cm -> 28; >= 5 cm -> 0.

**Deviations from the contract, and why**
1. BLOCKED fires on the rising edge of the pair (`blocked`, `target`), not of `blocked` alone. T14's GameLoop diffs
   `lastReported` against `now`, so a tap's `walkTo` and the next step land in one diff. With a plain rising edge, a new
   tap while still pinned would never re-announce. BLOCKED still never repeats while pinned on the same walk.
2. `walkTo(body, t)` clears `blocked` when it changes the state. Re-sending the current target returns the body unchanged,
   so a host that repeats it every frame stays quiet.
3. `blocked` is also false when the step ends in an arrival or a fall (target cleared, or mode not WALKING), so ARRIVED and
   BLOCKED never come together. A PAUSED step stays the identity, so a body paused while pinned keeps `blocked = true`
   (CLAUDE.md: PAUSED freezes, never resets); no event results from that.
4. `abortCharacter` takes an optional `attempts`. The pipeline passes the attempts it had started
   (`abortCharacter(attempts = attempt)`), the same count its error path uses. Example: a cancel inside attempt 2's
   stylize records `retries = 1`; counting from the recorded stages would give 0. The no-argument call still compiles.
5. `floorWalkIsFourTimesFaster` counts the steps to cover 0.4 m toward a far target, not the steps to arrive.
   `arriveEpsilonM` scales x4 (0.5 cm -> 2 cm), which moves arrival earlier by 3ε/(4·step), about 4.7 steps at any
   distance (494 vs 119, not 494/4 ± 1). The test also checks that both surfaces arrive exactly on the target.
6. Extra test `platformMaxHeightComesFromParams` in PhysicsSurfaceTest. Inside `floorJumpApexScalesWithProfile`, the
   analytic-apex sub-check uses a 6 % tolerance (see the 4.8 % Euler gap above). The required check against
   FLOOR_SCALE x the table apex keeps its 5 % tolerance.

**Open issues**
- `Semantics.kindFor` (T04 / SceneBuilder) still classifies with the table thresholds. On the floor a 12 cm "book" becomes
  an OBSTACLE, and no "box" or "shoe" label ever becomes a PLATFORM. Floor physics steps onto an object only if it is
  already a PLATFORM, so the owner of `kindFor` needs a profile-aware version (e.g. a `platformMaxHeightM` argument).
- On a cancel, the concurrent `sheet` stage is not recorded even if Spark already replied (<= $0.005; it is still in
  `measuredUsd(SPARK_SHEET)`), and neither is the stage in flight. The inner cancel branch was left alone per "No other
  pipeline change".
- BLOCKED also fires on glancing hits (offset <= ~4.5 cm on a cup) that later slide round. T13's 2.5 s gap between bubbles
  covers spam; if it reads noisy on the Pixel, a debounce (N blocked steps in a row) is cheap to add.
- A FLY character over a cliff stays JUMPING with its feet below the plane (h between -5 cm and 0) until it respawns.
  That physics quirk is unchanged; the events now say FELL.
- `androidApp/.../GameLoop.kt` still carries the `// T12` note to switch to the PhysicsBody overload (T14).

**Review fixes** (2026-10-29, on top of 7f9b33d). `:core:jvmTest` **158 tests, 0 failures** (154 + 4 new:
`strayCancellationInStylizeFailsTheCharacter`, `trackingBlipDoesNotReannounceBlocked`, `kindForUsesTheSurfaceProfile`,
`floorProfileMakesATwelveCmBoxAPlatform`); `:cli:compileKotlin` and `:androidApp:compileDebugKotlin` compile unchanged.
1. Stray CancellationException = failure. `CharacterPipeline.run` aborts only when the caller is really cancelled
   (`!currentCoroutineContext().isActive`). A CancellationException while still active (a timeout) takes the failure
   path: `endCharacter(0, false, …)`, `Failed` emitted, `MetaError.Provider` thrown. The stage it hit is recorded
   `ok=false` (`stage()`), the stylize loop records the sheet as for any failure, and the matte SAM fallback treats it
   like its other errors (ships the unmatted sprite).
2. A `dtMs <= 0` step keeps the previous `blocked` while it still ends WALKING toward a target. The host's dt = 0 step
   after a tracking resume no longer clears the flag, so a blip while pinned emits PAUSED, RESUMED and no second BLOCKED.
   A dt = 0 step never raises the flag, and `walkTo` to a new target still clears it.
3. `Semantics.kindFor(label, heightM, profile = SurfaceProfile.TABLE)` compares with `profile.platformMaxHeightM`.
   `SceneBuilder.build(input, previous, profile = TABLE)` passes it through. "box" joined the platform labels (a
   shoebox on the floor). The table default matches the old rule for every label the table scan prompts, checked in the
   test against the old rule written out. The one difference: a "box" of at most 4 cm is now a PLATFORM on the table
   too; no table prompt produces "box".
4. On a real cancel, a sheet that had already come back (it was billed) is recorded with the aborted character. It is
   recorded silently: no StageFinished after a cancel. A sheet still running is not recorded.
5. No more package cycle: `domain` now declares `interface CharacterFrame { val state; val blocked }`, `PhysicsBody`
   implements it, and the overload is `CharacterEvents.between(prev: CharacterFrame, next: CharacterFrame)`. That differs
   from the suggested extension in `physics`: an extension would have needed a new
   `import kz.rudy.ar.core.physics.between` in androidApp's GameLoop. With the interface, the existing call
   `CharacterEvents.between(lastReported, body)` compiles with no androidApp change.
