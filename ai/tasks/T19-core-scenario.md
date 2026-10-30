# T19 — core-scenario

status: todo
created: 2026-10-31
day: Day 5
gate: scripted 30 s scenario runs 3x in a row without code changes (this brief is the JVM half: the script, its
runner, and the proof it is deterministic; T21 replays it on the phone)
cut-if-slips: never cut (pure core + tests); drop the FLY variant first if time is short

## Goal
The Day 5 demo is a script, not a hand: "place -> walk -> blocked by cup -> jump onto book -> fly over gap" exists in
`:core` as a serialisable list of timed taps (`Scenario`), with a pure runner that plays it against a `Level` through
`Physics.step` and returns the events it produced, and a small incremental player the AR host can feed from its frame
loop. A JVM test runs the Day 5 scenario three times and proves the traces are identical and hit every beat.
No ARCore, no Compose, no network.

## Owns (exact paths; no other brief may touch these while this one is `doing`)
- core/src/commonMain/kotlin/kz/rudy/ar/core/scenario/Scenario.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/scenario/ScenarioRunner.kt (new; may be merged into Scenario.kt)
- core/src/commonTest/kotlin/kz/rudy/ar/core/scenario/ScenarioTest.kt (new)
- this brief

Do NOT touch `physics/**`, `domain/**`, `pipeline/**`. If a physics behaviour blocks a beat, tune the SCENARIO
(positions, timings), not the physics, and say so in the Result. (T20 owns `shared/`, T22 owns `core/meta/`.)

## Contract (package `kz.rudy.ar.core.scenario`, all `@Serializable`, metres / ms)
```kotlin
sealed class ScenarioStep { abstract val atMs: Long }            // time since the scenario started
  @SerialName("walk") data class WalkTo(override val atMs: Long, val target: Vec2) : ScenarioStep()
  @SerialName("jump") data class Jump(override val atMs: Long) : ScenarioStep()

data class Scenario(
    val id: String,
    val durationMs: Long,                 // the runner stops here; the Day 5 script is <= 30_000
    val steps: List<ScenarioStep>,        // init: sorted by atMs, every atMs in 0..durationMs
)

object Scenarios {
    /** The Day 5 beat sheet. Positions assume [day5Level]: spawn (0,0); cup OBSTACLE r 0.04 at (0.15, 0) h 0.09;
     *  book PLATFORM 0.15 x 0.10 at (0, 0.15) h 0.02; gap = CLIFF strip rect 0.10 wide (u) x 0.30 deep (v)
     *  centred at (-0.20, 0); boundary +-0.35 square. Kinds via Semantics.kindFor, not hard-coded. */
    val DAY5: Scenario                    // steps: walk to (0.30, 0) [blocked by the cup] -> walk to (0, 0.15)
                                          // [steps onto the book] -> walk toward (-0.30, 0) and Jump just before
                                          // the gap edge [JUMP falls + respawns; FLY crosses]
    fun day5Level(profile: SurfaceProfile = SurfaceProfile.TABLE): Level   // scaled by profile.scale
    val json: Json                        // polymorphic config for ScenarioStep; Scenario.toJson()/fromJson()
}

/** Incremental: the host calls [due] once per frame with its clock; each step is returned exactly once, in order. */
class ScenarioPlayer(val scenario: Scenario, private val startedAtMs: Long) {
    fun due(nowMs: Long): List<ScenarioStep>      // steps with startedAt + atMs <= nowMs not yet issued
    val finished: Boolean                          // every step issued and nowMs - startedAt >= durationMs (set by due)
    val issued: Int
}

data class ScenarioTrace(val events: List<Pair<Long, CharacterEvent>>, val bodies: List<PhysicsBody> /* one per step */, val final: PhysicsBody) {
    fun eventNames(): List<CharacterEvent>
}

object ScenarioRunner {
    /** Fixed-dt replay: Physics.step(body, level, dtMs, params) + CharacterEvents.between(PhysicsBody, PhysicsBody)
     *  after every step; WalkTo -> Physics.walkTo(body, target); Jump -> Physics.jump(body, params). Starts IDLE at
     *  level.spawn with the given ability and radius. Deterministic for fixed inputs. */
    fun run(scenario: Scenario, level: Level, ability: Ability, params: PhysicsParams = PhysicsParams.DEFAULT,
            radiusM: Float = SurfaceProfile.TABLE.radiusM, dtMs: Long = 16): ScenarioTrace
}
```
Edge cases: a step at `atMs == durationMs` is still issued; two steps with the same `atMs` keep list order;
`due` with a clock that jumps past several steps returns them all, in order; `run` with `dtMs <= 0` throws
`IllegalArgumentException`. `PhysicsBody` is `kz.rudy.ar.core.physics.PhysicsBody` (already serialisable).

## Tests required (`ScenarioTest`)
- `day5RunsThreeTimesIdentically` — `run(DAY5, day5Level(), JUMP)` three times: the three `ScenarioTrace`s are `==`.
- `day5JumpVariantHitsEveryBeat` — ability JUMP: `eventNames()` contains, in this order, STARTED_WALKING, BLOCKED
  (exactly once), STEPPED_UP (feet at 0.02 +- 0.002 while on the book), JUMPED, FELL, RESPAWNED; the final body is
  IDLE at spawn with h = 0; the whole trace is within `durationMs <= 30_000`.
- `day5FlyVariantCrossesTheGap` — ability FLY, same level and script: JUMPED then LANDED on the far side
  (`pos.u < -0.25`), no FELL, no RESPAWNED.
- `playerIssuesEachStepOnceInOrder` — `due` called at 0, 1000, 20000 and 40000 ms (a clock that skips) issues every
  step exactly once in `atMs` order; `finished` only after the last call.
- `scenarioJsonRoundTrip` — `Scenario.fromJson(DAY5.toJson()) == DAY5` and the JSON names the step kinds `walk` / `jump`.
Sanity-check each assertion once by flipping it (e.g. BLOCKED count 1 -> 2), then restore.
Tests not required: rendering, the host player loop, CLI.

## Steps
1. Read `Physics.kt`, `PhysicsParams.kt`, `CharacterEvent.kt`, `Model.kt` (`Semantics.kindFor`, `Polygon.rect`),
   `Day4ScenarioTest.kt` (test 2 is the closest idiom: cup / book / cliff), `PhysicsTest.kt` (`flyDoesNotFallOverCliffWhileAirborne`).
2. Build the level and the script; run `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest --console=plain -q`
   from your worktree; read `core/build/test-results/jvmTest/*.xml`.
3. Tune the script's timings (not the physics) until every beat lands with margin: e.g. the walk toward the gap must
   reach the edge AFTER the jump launches, and the FLY arc (gravity x 0.3, jumpSpeedScale 2) must clear 0.10 m.
   Record the numbers you ended with (jump launch distance from the gap edge, airtime, landing u) in the Result.
4. Fill the Result (beats table with timestamps from the JUMP trace), then stop. No git (the orchestrator commits).

## Done when
- `:core:jvmTest` green in the worktree, the five tests present and passing.
- Result lists the per-beat timestamps and the final JSON of `DAY5` (short).

## Result (filled on acceptance)
- measured: ...
- cut/deferred: ...
