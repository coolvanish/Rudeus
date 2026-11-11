# T45 — duel-match-specials

status: todo
created: 2026-11-11
phase: v2 wave 2 (ai/build-plan-v2.md, "The game")
gate: G4 — every match ends, 30 Hz and 60 Hz agree on the winner
cut-if-slips: specials (the plan's cut 5: ship Dash + Jump only); never the match rules
depends: T42 merged (`core/duel`: `DuelPhysics.step`, `DuelState`, `DuelParams`). The bot and the headless simulator are T55.
read first: the Result of `ai/tasks/T42-duel-physics.md`, section "What T45 must know" (hits ignore height, no hit
source but a dash and no immunity hook yet, stun and cooldown carry over a respawn, params must be passed to the helpers).

## Goal
A duel has a beginning and an end. Two fighters start facing each other on the scanned table, a countdown runs, they
fight, a ring-out scores a point, the fighters go back to their marks, and the first to two points wins. Each ability
has one special move with a cooldown. All of it is `:core`, pure and deterministic, and steps on simulated time only:
a tracking loss freezes the round clock exactly as it freezes the bodies.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/DuelMatch.kt, DuelRules.kt, DuelArena.kt (new)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/DuelPhysics.kt, DuelParams.kt, DuelState.kt (T42's files: add the specials; change nothing the T42 tests pin)
- core/src/commonTest/kotlin/kz/rudy/ar/core/duel/*.kt

Do not touch `physics/*`, `domain/*`, `session/*`, `emote/*`, `meta/*`, `pipeline/*`, `shared/**`, `androidApp/**`, Gradle.

## Contract
```kotlin
@Serializable data class DuelRules(
    val pointsToWin: Int = 2,            // best of three
    val countdownMs: Long = 3000,
    val roundMs: Long = 60_000,          // simulated time
    val pointPauseMs: Long = 1500,       // after a point, before the next countdown
    val maxRounds: Int = 5,              // replays included; then the match is a draw
)

/** One side of a duel as the match needs it. */
@Serializable data class Contestant(val id: String, val stats: FighterStats, val ability: Ability)

@Serializable enum class MatchPhase { COUNTDOWN, FIGHT, POINT, RESULT }
@Serializable enum class PointReason { RING_OUT, TIMEOUT }

@Serializable data class DuelMatchState(
    val duel: DuelState,
    val phase: MatchPhase,
    val phaseLeftMs: Long,               // COUNTDOWN / POINT: time left in the phase; FIGHT: time left in the round
    val round: Int,                      // 1-based
    val scoreA: Int, val scoreB: Int,
    val winner: String? = null,          // set in RESULT; null there = a draw
    val startA: Vec2, val startB: Vec2,  // the marks both return to
)

sealed interface MatchEvent {
    data class RoundStarted(val round: Int) : MatchEvent          // FIGHT begins
    data class Point(val winner: String?, val reason: PointReason) : MatchEvent   // winner null = replay, nobody scores
    data class MatchOver(val winner: String?) : MatchEvent
    data class Fight(val event: DuelEvent) : MatchEvent           // everything DuelPhysics reported, in order
}
data class MatchStep(val state: DuelMatchState, val events: List<MatchEvent>)

object DuelArena {
    /** The level a duel is fought on: [level] itself when it has a boundary, else a copy with a circular ring
     *  (16 segments) of [ringRadiusM] around the spawn, so a floor duel has an edge too. */
    fun arena(level: Level, ringRadiusM: Float): Level
    /** Two marks facing each other across the spawn: free of blocking objects, inside the boundary with a margin of
     *  2 radii, as far apart as [separationM] allows; null when the level has no room for two fighters. */
    fun marks(arena: Level, radiusM: Float, separationM: Float, physics: PhysicsParams): Pair<Vec2, Vec2>?
}

object DuelMatch {
    fun start(a: Contestant, b: Contestant, arena: Level, marks: Pair<Vec2, Vec2>, rules: DuelRules = DuelRules(),
              params: DuelParams = DuelParams.DEFAULT, physics: PhysicsParams = PhysicsParams.DEFAULT): DuelMatchState
    /** [tracking] false = the AR session lost tracking: bodies pause, no clock runs, no event; true resumes. */
    fun step(state: DuelMatchState, arena: Level, dtMs: Long, inputs: Map<String, DuelInput> = emptyMap(),
             tracking: Boolean = true, rules: DuelRules = DuelRules(),
             params: DuelParams = DuelParams.DEFAULT, physics: PhysicsParams = PhysicsParams.DEFAULT): MatchStep
}
```
Rules:
- COUNTDOWN and POINT: fighters stand on their marks facing each other, input is ignored, `DuelPhysics` is not stepped
  for hits. FIGHT: inputs go to `DuelPhysics.step`.
- A `RingOut` in FIGHT ends the round at once: the point goes to the other fighter. Two ring-outs are compared by
  `DuelEvent.atMs` (the sub-step they happened in, added by the T42 review fixes), never by the call they arrived in or
  by their order in the list: the earlier one loses; equal `atMs` = no point, the round replays. The result must be the
  same at 30 and 60 Hz and with the fighters' slots swapped. Round clock at 0: the fighter whose centre is farther inside the boundary (distance to the
  nearest boundary edge) takes the point; within 1 mm: no point, replay.
- After POINT both fighters are rebuilt on their marks (IDLE, no knock, no stun, cooldowns cleared) whatever physics
  was doing (falling, respawning). `pointsToWin` reached -> RESULT with the winner. `maxRounds` played without a
  winner -> RESULT, `winner = null`. RESULT is terminal: `step` returns the state unchanged with no events.
- `tracking` false pauses BOTH fighters (a duel with one paused fighter lets the other dash through it, and `isOut`
  is false for a fighter paused mid-fall). The caller clamps `dtMs` (the host's `GameLoop` does, at 50 ms); `step`
  itself clamps to 250 ms so a hitch cannot run hundreds of sub-steps.
- All clocks advance only by the `dtMs` given while `tracking` is true. The step is frame-rate independent in the
  same sense as T42: any split of the same total time gives the same state and the same events.
- Never rely on a per-action event arriving on the first frame after a pause (see ai/build-plan-v2.md, "What T43's map
  changed"): read state, not events, across a tracking gap.

Specials (one per `Ability`; `DuelInput.Special`; own cooldown `specialCooldownMs` 6000 scaled by agility like the dash
cooldown; ignored while stunned, dashing, airborne, out, or on cooldown; every number in `DuelParams`; `DuelFighter`
gains `specialMs`, `specialCooldownMs` and what a special needs; a new `DuelEvent.SpecialStarted(id, ability)`):
- **JUMP — stomp.** A jump; when it lands, every opponent within `stompRadius` (3 fighter radii) is knocked straight
  away from the landing point with `stompScale` (1.2) x the normal knockback, `Hit(fromAbove = true)`.
- **FLY — lift-off.** For `liftMs` (1200) the fighter cannot be hit (a dash passes under it, no `Hit`, the dash carries
  on) and walks at `liftSpeedScale` (1.5); it cannot dash while lifted. It can still walk off the edge.
- **ROLL — ram.** A dash that lasts `ramDashScale` (2.2) x as long and hits with `ramKnockScale` (1.5) x knockback; it
  cannot be steered and does not stop at the boundary: a ram that misses near the edge is a ring-out of the roller.
- **HIDE — dodge.** For `dodgeMs` (500) hits pass through the fighter; its next dash started within `counterMs` (1500)
  hits with `counterKnockScale` (1.5) x knockback.
`DuelFighter` needs the ability: add `ability: Ability` with a default that keeps T42's tests compiling unchanged
(`Ability.JUMP`), set from `Contestant` by `DuelMatch.start`.

## Tests required
- `countdownIgnoresInputThenTheRoundStarts`
- `ringOutScoresOnceAndBothReturnToTheirMarks`
- `bothOutInTheSameSubStepReplaysTheRound` / `theEarlierRingOutLosesAtAnyFrameRateAndInEitherSlot`
- `timeoutGoesToTheFighterFartherInsideAndATieReplays`
- `firstToTwoWinsAndResultIsTerminal`
- `aMatchWithoutAWinnerAfterMaxRoundsIsADraw`
- `trackingLossFreezesEveryClockAndNothingIsLost` — 10 s untracked in COUNTDOWN, FIGHT and POINT: same phase, same time left, same positions; resumes
- `anySplitOfTheSameTimeGivesTheSameMatch` — a scripted match stepped at 16, 33 and random 5..50 ms: equal states and equal event lists
- `marksAreFreeInsideAndFaceEachOther` / `noRoomForTwoGivesNoMarks` / `aLevelWithoutABoundaryGetsARing`
- `stompKnocksEveryoneNearTheLandingAway`, `liftedFighterCannotBeHitAndTheDashCarriesOn`, `ramHitsHarderAndCanRollOffTheEdge`, `dodgeLetsTheHitThroughAndTheCounterHitsHarder`
- `specialOnCooldownOrWhileBusyIsIgnored`, `specialCooldownShortensWithAgility`
- `mirroredMatchesGetMirroredResults` — swap a and b with mirrored marks and inputs
- T42's tests still pass unchanged
Tests not required: balance (T48), a bot (T55), rendering.

## Done when
- `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :core:jvmTest :core:compileKotlinIosSimulatorArm64 --console=plain -q` green
- No file outside "Owns" changed

## Result (filled on acceptance)
- measured: ...
- cut/deferred: ...
