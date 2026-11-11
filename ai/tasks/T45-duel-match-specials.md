# T45 — duel-match-specials

status: review
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
- measured (2026-11-11, JVM, cloud container): `:core:jvmTest` 611 tests, 0 failures (589 before + 22 new; `duel` 51 =
  T42's 29 unchanged + 11 `DuelMatchTest` + 8 `DuelSpecialsTest` + 3 `DuelArenaTest`); `:core:compileKotlinIosSimulatorArm64`
  green, the klib carries `DuelMatch`.
  - Default numbers. `DuelRules`: pointsToWin 2, countdown 3000, round 60 000, point pause 1500 (runs as 1504, see
    below), maxRounds 5, `maxStepMs` 250, `timeoutTieM` 0.001. `DuelParams` (all times or dimensionless, so `scaled()`
    and T42's scale test are untouched): specialCooldownMs 6000 x (1.30 - 0.06 AGILITY) = 7440 .. 4200 ms; stomp
    `stompRadiusRadii` 3 (centre to centre, 9 cm on the table), `stompScale` 1.2 (0.30 m/s at 5 v 5); lift `liftMs` 1200,
    `liftSpeedScale` 1.5, `liftHeightRadii` 0.5 (new: hover 1.5 cm); ram `ramDashScale` 2.2 (550 ms, 16.5 cm),
    `ramKnockScale` 1.5 (0.375 m/s at 5 v 5); dodge `dodgeMs` 500, `counterMs` 1500, `counterKnockScale` 1.5. Every
    number of the brief kept.
  - One clock on the sub-step grid. `duel.timeMs` is the match clock in every phase (tracked time only). Every phase
    length is rounded up to whole sub-steps when it starts, and a ring-out ends FIGHT at the end of its sub-step, so
    every transition lies on the 8 ms grid the bodies use; a call runs its time through the phases in order (a ring-out
    at 1000 in a call 990..1023 spends the last 23 ms in POINT). Same states and the same event lists (with times) at
    16 ms, 33 ms, random 5..50 and random 1..250 ms frames, and with 10 s tracking gaps in each phase.
  - Ring-outs: FIGHT steps physics with an internal `DuelPhysics.stepToRingOut`, which stops after the first sub-step
    holding a `RingOut`; the round ends there and nothing later is simulated in FIGHT. One out = the other scores; both
    out in that sub-step (equal `atMs`) = replay. A later ring-out never reaches the judge, so neither the call it would
    have arrived in nor the slot order matters (test: y out at 312 ms, x at 328 ms, both in one 33 ms call: x wins at
    16/33/50/random frames and in both slots; x's own fall then plays out in POINT at 328 ms, shown, not scored).
  - Specials inside the sub-step (input at the start of the call, at the grid time, like a dash): timers count down in
    step 1 and a lift/dodge whose time was > 0 at the start of a sub-step protects for that whole sub-step. JUMP stomp =
    `Physics.jump` + `stomping`; the sub-step where the body goes from airborne to standing is the landing (step 3),
    and step 4 knocks the opponent within 3 radii straight away from it: a second hit source next to the dash, judged
    from the same post-physics positions. FLY lift = the body becomes JUMPING 1.5 cm above the ground under it, held
    there each sub-step after `Physics.step` (walk speed x 1.5 via `jumpSpeedScale`), so it is truly airborne: no dash,
    no special, no separation (a dash passes under it, no Hit, the dash runs on), over a hole it stays up, past the edge
    it rings out; gravity takes it down the sub-step after the lift ends. ROLL ram = a dash of kind RAM (550 ms, x 1.5),
    unsteerable like any dash and, like any dash, not stopped by the edge. HIDE dodge = immune for 500 ms and stands
    firm in separation (the other goes round it); `counterMs` 1500 makes the next dash a COUNTER (x 1.5).
  - HUD reads (no event needed): `phase`, `phaseLeftMs` (FIGHT = round time left), `round`, `scoreA/B` / `scoreOf(id)`,
    `lastPoint` (who scored and why), `winner`; per fighter `DuelPhysics.dashCooldownLeft(f, params)` and
    `specialCooldownLeft(f, params)` = fraction of the cooldown still to run (1 just used .. 0 ready),
    `isLifted` / `isDodging` / `isImmune` / `isRamming`, `stomping`, `counterMs > 0`, `canDash` / `canSpecial`.
  - Speed (test JVM, best of two check-free replays): specials fuzz 103 000 .. 121 000 steps/s (4 ability pairs x 10 000
    frames of 16..50 ms); T42's fuzz 101 000 .. 110 000 (90 000 before: no slowdown); random matches 137 000 match
    steps/s, 90 matches/s (16 seeded matches, 20 s rounds, 3 % untracked frames: 17 ring-out points, 23 timeouts).
- deviations (each one forced by a test or an invariant, or a gap in the brief):
  1. `DuelFighter.ability` is `Ability? = null`, not `Ability = JUMP`: with JUMP as default T42's
     `specialIsAcceptedAndDoesNothing` (fighters built without an ability press Special) would stomp and fail. null =
     no special; `DuelMatch.start` sets it, and also puts the ability on the body (FLY glides, ROLL cannot jump: the
     walking game's rules).
  2. POINT is not "on the marks": the bodies go on under `DuelPhysics` with no input after every action is cut (dash,
     walk target, lift, dodge, stomp, counter), so the loser's fall is shown; nothing can hit. Its events are passed on as
     `Fight` (a WallSlam, the other one falling off too); only `Point` scores. Both are rebuilt on the marks at its end.
  3. Phase lengths run in whole sub-steps: POINT 1500 -> 1504 (3000 and 60 000 are already whole).
  4. HIDE: "hits pass through" is no Hit, no knockback, the dash not spent; the bodies do not overlap (the attacker goes
     round the dodger), so T42's "grounded fighters never overlap" holds as it is. The FLY's pass-under is real (airborne).
  5. Added, not in the contract: `atMs` on `RoundStarted` / `Point` / `MatchOver`, `DuelMatchState.lastPoint`
     (`PointResult`), `DuelMatch.start(..., radiusM = 3 cm)`, `DuelRules.maxStepMs` / `timeoutTieM`, `DashKind`,
     `DuelFighter.stomping` / `counterMs` / `dashKind`, `DuelArena.insideDistance`, the `DuelPhysics` helpers above;
     `MatchEvent`, `MatchStep`, `DuelMatchState` are `@Serializable`.
  6. Choices the brief left open: the stomp's knockback has no high-ground bonus and, like every T42 hit, ignores height
     (an airborne opponent is hit); the ram emits only `SpecialStarted` (no `DashStarted`) and leaves the dash cooldown
     alone; the counter window runs from the dodge's start, and a dash during the dodge is a counter while the dodge
     still protects; marks keep 2 radii from holes too, are >= 3 radii apart, and fall back from the spawn to the
     boundary's centroid before shrinking; a timeout on a level without a boundary is a replay.
  7. T42 test files: one `when` branch for `SpecialStarted` in each of two helpers (`DuelTestSupport.at`,
     `DuelScenarioTest.swapIds`); `DuelEvent` is sealed, so they would not compile otherwise. No T42 test body changed.
- cut/deferred: nothing cut. Not handled: a rescan between rounds (marks are fixed in the state; a host can
  `state.copy(startA = ..., startB = ...)` during POINT and the rebuild uses them, but nothing checks the new arena).

### What T55 must know
- Drive a match only through `DuelMatch.step` with the same `rules`, `params`, `physics` (and the `radiusM` given to
  `start`) every call. Frames above `rules.maxStepMs` (250) are clamped: that time is lost, so step <= 250 ms.
- Inputs belong to the start of their call and apply only if the call starts in FIGHT: a call that starts in the
  countdown drops its inputs even if the fight begins inside it. One input per fighter per call (a MoveTo and a Special
  need two calls). In RESULT the clock stops at `MatchOver.atMs`; the rest of that call's dt is discarded.
- For 30 vs 60 Hz agreement (G4) the bot must decide on state the two rates share: decide at fixed simulated times
  (multiples of 8 ms, e.g. every 96 ms) and cut the simulator's frames there, as the tests' `play` driver does; then any
  frame rate gives the same match bit for bit.
- What a bot reads: `DuelPhysics.canDash` / `canSpecial` (pass the params), `isImmune` (do not dash at a lifted or
  dodging fighter: the dash is wasted), `isRamming`, `stomping`, `dashKind`, knock/stun, `DuelArena.insideDistance` to
  the edge. ROLL cannot jump; a FLY's plain Jump floats for 2.2 s (walking-game fly gravity) and is hittable all along.
- Random chase-and-dash play rarely rings anyone out from the middle of a big table (in the random-match test 23 of 40
  points were timeouts): the bot has to push toward an edge, which is T48's balance question.
