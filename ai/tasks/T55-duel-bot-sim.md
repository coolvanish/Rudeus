# T55 — duel-bot-sim

status: review
created: 2026-11-11
phase: v2 wave 3 (ai/build-plan-v2.md, "The game": the opponent is a bot in `:core`, one phone)
gate: G4 — every match ends; 30 Hz and 60 Hz agree on the winner; (the balance half of G4 is T48)
cut-if-slips: HARD (ship EASY and NORMAL); never the runner or the simulator
depends: T45 merged (`DuelMatch.step`, `DuelMatchState`, the specials). T48 (the CLI and the tuning) builds on this.
read first: the Results of `ai/tasks/T42-duel-physics.md` ("What T45 must know") and `ai/tasks/T45-duel-match-specials.md`
("What T55 must know").

## Goal
A child can fight their drawing against a bot, and two bots can fight each other with nobody touching the phone
("whose drawing is stronger"). The bot is pure `:core`: it reads the match state and answers one `DuelInput`, with a
seed and a level, the same on the phone, in the CLI and in tests. A runner steps a match with zero, one or two bots so
that a bot's decision lands at the same match time whatever the frame length, and a headless simulator plays whole
matches and series fast enough to tune the game by the thousand.

## Owns (exact paths)
- core/src/commonMain/kotlin/kz/rudy/ar/core/duel/DuelBot.kt, BotTuning.kt, DuelRunner.kt, DuelSim.kt, DuelArenas.kt (new)
- core/src/commonTest/kotlin/kz/rudy/ar/core/duel/DuelBotTest.kt, DuelRunnerTest.kt, DuelSimTest.kt (new)

Do not touch the existing files of `core/duel` (T42 / T45: if the bot needs something they do not expose, stop and
say what), `physics/*`, `domain/*`, `session/*`, `cli/**`, `shared/**`, `androidApp/**`, Gradle.

## Contract
```kotlin
@Serializable enum class BotLevel { EASY, NORMAL, HARD }

/** Everything a bot remembers between decisions. Immutable, serializable: a saved match resumes with the same bot. */
@Serializable data class BotMind(val level: BotLevel, val rng: Long, /* + what your design needs, all serializable */)

@Serializable data class BotTuning(/* every number of the bot: tick, reaction per level, ranges, margins, error rates */)

object DuelBot {
    fun mind(level: BotLevel, seed: Long): BotMind
    /** What fighter [me] wants at `state.duel.timeMs`. Pure: the same arguments give the same answer on every platform. */
    fun decide(mind: BotMind, me: String, state: DuelMatchState, arena: Level,
               params: DuelParams = DuelParams.DEFAULT, physics: PhysicsParams = PhysicsParams.DEFAULT,
               tuning: BotTuning = BotTuning()): Pair<BotMind, DuelInput>
}

/** A match with its bots. A side without a mind is played by the caller's inputs. */
@Serializable data class RunnerState(val match: DuelMatchState, val botA: BotMind? = null, val botB: BotMind? = null)
data class RunnerStep(val state: RunnerState, val events: List<MatchEvent>)

object DuelRunner {
    /**
     * [DuelMatch.step] with the bots playing their sides. Bots decide on a fixed grid of match time
     * (`BotTuning.tickMs`, a multiple of the physics sub-step): the frame is split at every tick it crosses, so a
     * bot's input is applied at the same match time whatever [dtMs] is. [inputs] (the human's) are applied at the
     * start of the call, as in [DuelMatch.step]; an input for a side that has a bot is ignored.
     */
    fun step(state: RunnerState, arena: Level, dtMs: Long, inputs: Map<String, DuelInput> = emptyMap(),
             tracking: Boolean = true, rules: DuelRules = DuelRules(), params: DuelParams = DuelParams.DEFAULT,
             physics: PhysicsParams = PhysicsParams.DEFAULT, tuning: BotTuning = BotTuning()): RunnerStep
}

@Serializable data class SimResult(
    val winner: String?, val scoreA: Int, val scoreB: Int, val rounds: Int, val finished: Boolean, val matchMs: Long,
    val ringOuts: Int, val timeouts: Int, val replays: Int, val hits: Int, val wallSlams: Int, val specials: Int,
)
@Serializable data class SeriesResult(
    val matches: Int, val winsA: Int, val winsB: Int, val draws: Int, val unfinished: Int,
    val meanMatchMs: Long, val timeoutShare: Float,   // timeouts / points awarded or replayed by the clock
)

object DuelSim {
    /** One bot-vs-bot match to RESULT (or to [maxMatchMs] of match time: `finished = false`). */
    fun run(a: Contestant, b: Contestant, arena: Level, levelA: BotLevel, levelB: BotLevel, seed: Long,
            stepMs: Long = 33, maxMatchMs: Long = 600_000, rules: DuelRules = DuelRules(),
            params: DuelParams = DuelParams.DEFAULT, physics: PhysicsParams = PhysicsParams.DEFAULT,
            tuning: BotTuning = BotTuning()): SimResult
    /** [n] matches with seeds `seed until seed + n`; the two contestants swap marks every other match. */
    fun series(a: Contestant, b: Contestant, arena: Level, levelA: BotLevel, levelB: BotLevel, n: Int, seed: Long,
               /* same options as run */): SeriesResult
}

/** The arenas the simulator and the CLI tune on: an empty 60 x 40 cm table; the same with a cup (OBSTACLE, 9 cm) and
 *  a book (PLATFORM, 3 cm); a small 30 x 30 cm table; all in plane coordinates, spawn at the origin. */
object DuelArenas { val EMPTY: Level; val CUP_AND_BOOK: Level; val SMALL: Level; val ALL: Map<String, Level> }
```
Behaviour:
- **Randomness** is the bot's own generator on the `rng: Long` in `BotMind` (SplitMix64 or xorshift: a few lines), not
  `kotlin.random`: the sequence must be identical on JVM and Kotlin/Native by construction and must survive JSON.
- **The plan every level shares:** stand so the opponent is between you and its nearest boundary edge, close in, dash
  when in range and aligned; stay away from the edge yourself; walk round blocking objects instead of pressing into
  them; in COUNTDOWN, POINT and RESULT answer `None`.
- **Levels.** EASY: slow to react, wanders, dashes when close whatever the alignment, sometimes the wrong way, never
  uses the special. NORMAL: the shared plan, uses the special in its obvious situation (stomp with the opponent in
  radius, ram when aligned with the opponent near an edge and room behind itself, lift-off or dodge when a dash is
  coming at it near an edge). HARD: NORMAL with faster reactions, sidesteps an incoming dash, prefers the high ground
  of a PLATFORM, counters after a dodge, and does not ram towards an edge it would roll off.
- **A bot never gives the game away:** it never walks off the table on its own (a `MoveTo` target is always inside
  the boundary by a margin; a dash is not started when it would carry the bot past the edge unless the opponent is in
  its path first), and it never emits a non-finite direction or target.
- **Reads state, not events** (events can be lost on the first frame after a tracking gap).
- `DuelRunner.step` with `tracking = false` pauses the match and no bot decides; with no bots it is exactly
  `DuelMatch.step`.

## Tests required
- `sameSeedSameMatch` / `differentSeedsDiffer` — equal `SimResult` and equal final `RunnerState`
- `botMatchesAreIdenticalAtAnyFrameLength` — the same seeded bot-vs-bot match stepped at 16, 33, 50 and random 5..50 ms: equal final states and equal event lists (this is G4's 30/60 Hz half, exactly)
- `runnerWithoutBotsIsDuelMatch`
- `aHumanInputForABotSideIsIgnored`
- `trackingLossFreezesTheBotsToo`
- `everyMatchFinishes` — every level pair x every ability pair x the three arenas x 3 seeds: `finished`, within `maxMatchMs`
- `aBotAloneNeverLeavesTheTable` — each level against a stationary dummy in a far corner, 60 s x 20 seeds, on the three arenas: no ring-out of the bot
- `botsNeverEmitNonFiniteOrOutsideTargets` — asserted on every decision of a few hundred matches
- `hardBeatsEasy` — equal stats and ability, 200 matches on EMPTY and on CUP_AND_BOOK: HARD wins at least 70 %
- `normalIsBetweenEasyAndHard` — NORMAL beats EASY at least 60 %, loses to HARD at least 55 %
- `sidesAreFair` — NORMAL vs NORMAL, identical contestants, 400 matches: neither side wins more than 60 %
- `botMindSurvivesJson` — a match saved mid-fight (`RunnerState` through JSON) continues to the same result
- `simIsFastEnough` — report matches per second and steps per second in the Result; assert only a loose floor (20 matches/s)
Measure and report (do not assert): for every level pair on EMPTY, the share of points decided by the clock, mean
match length, hits per ring-out. If with the default `DuelParams` bots cannot push each other off the table often
enough for the win-rate tests to mean anything (T42 measured 4.5 cm of knockback for an even hit), do NOT change
`DuelParams.DEFAULT`: run those tests with a `DuelParams` copy you state in the test, and say so first in the Result,
with the numbers. Tuning the defaults is T48's.
Tests not required: balance between abilities or stats (T48), the CLI, rendering.

## Done when
- `flock /tmp/rudeus-gradle.lock env ANDROID_HOME=/opt/android-sdk ./gradlew :core:jvmTest :core:compileKotlinIosSimulatorArm64 --console=plain -q` green
- No file outside "Owns" changed

## Result (filled on acceptance)
- **No `DuelParams` copy**: every test runs `DuelParams.DEFAULT`. With the defaults the bots do push each other off:
  of the rounds in the win-rate tests 21 to 35 % end by the clock (table below), so the win-rate tests are decided mostly
  by ring-outs and mean something. The exception T48 must look at: **plain-dash play rarely rings anyone out.** Two
  NORMAL FLY or two NORMAL HIDE bots (their specials are defensive, so dashes only) ended 92 to 99 % of their rounds by
  the clock on EMPTY and CUP_AND_BOOK, about 700 hits per ring-out (measured mid-tuning, 40 matches each). The ring-outs
  come from the stomp (x 1.2) and the ram (x 1.5); a plain even hit (4.5 cm) is recovered from before the next one.
- Thresholds: the brief's, all of them, none lowered. `hardBeatsEasy` and `normalIsBetweenEasyAndHard` use equal stats
  and ability with **every ability** (its archetype stats on both sides, 50 matches each, 200 per arena), stricter
  than one ability. Measured: HARD v EASY 100 % on EMPTY and 100 % on CUP_AND_BOOK (every ability 50/50); NORMAL v EASY
  99.5 % and 99 %; HARD v NORMAL 94 % (JUMP 49, FLY 39, ROLL 50, HIDE 50 of 50) and 86 % (JUMP 43, FLY 34, ROLL 47, HIDE
  48). `sidesAreFair`: 400 NORMAL v NORMAL on EMPTY, 202 / 198. No level was cut.
- measured (2026-11-11, JVM, cloud container): `:core:jvmTest` 671 tests, 0 failures (650 before + 21 new); `duel`
  86 (65 + 10 `DuelBotTest` + 6 `DuelSimTest` + 5 `DuelRunnerTest`), **36 to 42 s** for the whole duel package (the
  costly ones: `everyMatchFinishes` 11 to 14 s, `normalIsBetweenEasyAndHard` 8 s, `hardBeatsEasy` 4 to 5 s);
  `:core:compileKotlinIosSimulatorArm64` green, the klib carries `DuelRunner` / `BotTuning`.
  - The bot in ten lines. (1) It decides only on the 48 ms tick grid of match time; between decisions its last orders
    (a walk) stand; `thinkMs` (+ random ticks) is its reaction time. (2) Outside FIGHT, stunned, dashing or out: None.
    (3) A dash or a stomp coming at it near an edge: FLY / HIDE use the special (NORMAL, HARD); HARD also dashes across
    it (else steps aside), and a HARD HIDE dodges anywhere for the counter. (4) HARD with its counter window open dashes
    at the opponent. (5) The push direction is the opponent's nearest edge; HARD takes a spot up to 45° off it when that
    spot is on a PLATFORM above the opponent and as deep inside. (6) Dash when the gap is within a share of the dash's
    reach and the aim pushes along the push direction (EASY: any alignment, aim error, 25 % a random direction; HARD:
    leads a walking opponent). (7) Special in its obvious situation: stomp with the opponent within 0.8 of the stomp's
    reach on its edge side; ram when aligned, opponent near an edge, room behind (HARD: anywhere a miss still ends on the
    table, never otherwise). (8) Else walk to the spot behind the opponent (round the opponent and round what blocks;
    EASY sometimes wanders to a random spot). (9) Every walk target 2 radii inside the boundary and off holes; a dash or
    ram only if it ends 1 radius inside or meets the opponent in its first half. (10) Randomness: SplitMix64 on
    `BotMind.rng`; no trigonometry anywhere, so the same bits on every platform.
  - Every `BotTuning` default (distances in fighter radii, cosines for angles): `tickMs` 48, `edgeMarginRadii` 2,
    `dashEndMarginRadii` 1, `inPathLateralRadii` 1, `inPathReachShare` 0.5, `standoffGapRadii` 0.6, `clearanceRadii`
    0.6, `detourStepCos`/`Sin` 0.7071 (45°), `threatSlackRadii` 0.5, `sidestepRadii` 2, `stompReachShare` 0.8,
    `ramNearEdgeRadii` 5, `ramRoomRadii` 3, `walkCost` 0.5, `switchCostRadii` 1, `highGroundBonusRadii` 3, `leadShare`
    1, `knockLeadShare` 0.5, `lineOfSightRadii` 0.8, `sameDirectionCos` 0.98, `blockedPushRadii` 10. `BotSkill`
    (EASY / NORMAL / HARD): `thinkMs` 384 / 144 / 48, `thinkJitterTicks` 4 / 2 / 1, `dashRangeShare` 0.7 / 0.8 / 0.85,
    `alignCos` -1 / 0.866 / 0.866, `aimErrorShare` 0.35 / 0.1 / 0, `wrongWayChance` 0.25 / 0 / 0, `wanderChance` 0.2 /
    0 / 0, `wanderMs` 1200 / 0 / 0, `leadsAim` no / no / yes, `weighsPush` no / no / yes, `usesSpecial` no / yes / yes,
    `nearEdgeRadii` 0 / 4 / 4, `sidesteps` no / no / yes, `counters` no / no / yes, `safeRamsOnly` no / no / yes.
  - The table: every level pair on each arena, archetype stats, the same ability on both sides, 10 matches per ability
    (40 per row), default rules, marks 16 cm apart. Wins a / b / draws in %; clock = rounds ended by the round clock;
    hits = Hit + Clash events. EMPTY's rows come from `theLevelTableOnEmpty` (printed on every run), the other two
    from a one-off run with the same code.

    | arena | a v b | a / b / draw % | clock | mean match | hits / ring-out |
    |---|---|---|---|---|---|
    | empty | EASY v EASY | 45 / 55 / 0 | 100 % | 174 s | no ring-out |
    | empty | EASY v NORMAL | 0 / 100 / 0 | 32 % | 71 s | 41.8 |
    | empty | EASY v HARD | 0 / 100 / 0 | 19 % | 71 s | 34.4 |
    | empty | NORMAL v EASY | 100 / 0 / 0 | 28 % | 79 s | 44.1 |
    | empty | NORMAL v NORMAL | 35 / 65 / 0 | 37 % | 84 s | 51.0 |
    | empty | NORMAL v HARD | 5 / 95 / 0 | 21 % | 56 s | 27.1 |
    | empty | HARD v EASY | 100 / 0 / 0 | 27 % | 71 s | 38.5 |
    | empty | HARD v NORMAL | 90 / 10 / 0 | 22 % | 56 s | 28.0 |
    | empty | HARD v HARD | 50 / 50 / 0 | 22 % | 79 s | 33.4 |
    | cup-and-book | EASY v EASY | 60 / 40 / 0 | 99 % | 162 s | 4127 |
    | cup-and-book | EASY v NORMAL | 0 / 100 / 0 | 30 % | 81 s | 45.9 |
    | cup-and-book | EASY v HARD | 0 / 100 / 0 | 29 % | 76 s | 41.5 |
    | cup-and-book | NORMAL v EASY | 100 / 0 / 0 | 32 % | 88 s | 48.1 |
    | cup-and-book | NORMAL v NORMAL | 42 / 57 / 0 | 52 % | 105 s | 78.9 |
    | cup-and-book | NORMAL v HARD | 17 / 82 / 0 | 27 % | 83 s | 39.8 |
    | cup-and-book | HARD v EASY | 100 / 0 / 0 | 24 % | 71 s | 36.0 |
    | cup-and-book | HARD v NORMAL | 95 / 5 / 0 | 31 % | 91 s | 47.0 |
    | cup-and-book | HARD v HARD | 55 / 45 / 0 | 39 % | 120 s | 61.3 |
    | small | EASY v EASY | 47 / 52 / 0 | 79 % | 140 s | 196.9 |
    | small | EASY v NORMAL | 0 / 100 / 0 | 11 % | 47 s | 19.7 |
    | small | EASY v HARD | 0 / 100 / 0 | 10 % | 49 s | 20.0 |
    | small | NORMAL v EASY | 100 / 0 / 0 | 13 % | 58 s | 24.4 |
    | small | NORMAL v NORMAL | 47 / 52 / 0 | 13 % | 49 s | 16.9 |
    | small | NORMAL v HARD | 12 / 87 / 0 | 7 % | 36 s | 10.9 |
    | small | HARD v EASY | 100 / 0 / 0 | 14 % | 48 s | 20.6 |
    | small | HARD v NORMAL | 77 / 22 / 0 | 10 % | 40 s | 14.0 |
    | small | HARD v HARD | 45 / 55 / 0 | 23 % | 58 s | 27.5 |

    (40 matches per row: equal-level rows within +-15 points of 50 are noise; `sidesAreFair`'s 400 give 50.5 / 49.5.)
  - Speed (`simIsFastEnough`, best of two, 72 matches mixed over the arenas, three level pairs, JUMP and HIDE): 94 to
    108 matches/s, 200 000 to 250 000 runner steps/s at 33 ms frames, 6 600 to 8 200 s of match per second. By pair
    and arena (table run): 25 matches/s (EASY v EASY on CUP_AND_BOOK, 162 s matches) to 625 matches/s (NORMAL v HARD on
    SMALL), mostly following match length. Not profiled: how the time splits between physics and bot decisions.
  - G4's halves this brief owns: **frame rate**: bot-vs-bot matches are bit-identical at 16, 33, 50, random 5..50 and
    random 1..250 ms frames (36 runs: states, events and decisions), so 30 and 60 Hz agree on 100 % of seeded matches;
    a mutation (bots deciding at every call instead of on ticks) fails the test, as does dropping the resume before
    the bots read a state after a tracking gap. **Every match ends**: 1296 matches (9 level pairs x 16 ability pairs x
    3 arenas x 3 seeds), all finished, 0 draws, 804 of 2890 rounds by the clock.
  - Safety: `aBotAloneNeverLeavesTheTable` (3 levels x 20 seeds x 3 arenas x 60 s, the dummy pushed off 70 to 168
    times per level and arena, the bot never); `botsNeverEmitNonFiniteOrOutsideTargets` (216 matches, 67 600 decisions:
    60 307 MoveTo, 6 574 Dash, 719 Special; EASY never sent Special).
- deviations:
  1. Added to the contract: `BotSkill` (the per-level numbers, `BotTuning.easy / normal / hard`, `skill(level)`,
     `requireFits(params)`); `BotMind.nextThinkMs / wander / wanderUntilMs / push`; `RunnerState.botOf`;
     `RunnerStep.decisions` with `BotDecision` (what the bots sent, for tests and T48's logs); `DuelSim.match` returning
     `SimMatch(result, final)` with an optional `marks`; `SeriesResult.rounds / ringOuts / timeouts / hits / wallSlams /
     specials` (defaults 0) and `shareA / shareB / hitsPerRingOut`; `DuelArenas.SEPARATION_M` (0.16) and
     `DuelArenas.marks`. Definitions: `timeoutShare` = rounds ended by the clock / rounds ended (replays counted on both
     sides); `hits` counts each Hit and each Clash once.
  2. Behaviour the brief's level text did not have, each forced by a measurement (series of 40): HARD sidesteps only
     near an edge (a mid-table sidestep spent its dash on a harmless hit: HARD lost to NORMAL on CUP_AND_BOOK, 14 of
     40); HARD's think time has one tick of jitter (two HARD bots on the same ticks mirrored each other: every round a
     tied timeout, every match a draw, against G4); a HARD ROLL rams wherever a miss still ends on the table, not only
     with the opponent near an edge (both conditions together meant it almost never rammed: 22 of 50 against NORMAL);
     HARD's "prefers the high ground" is the only reason it leaves the nearest-edge push (weighing every direction on a
     score made it walk along its own edge or round the cup and lose to NORMAL, down to 0 of 40 with ROLL); a HARD HIDE
     dodges anywhere (the counter is its point); NORMAL and HARD react to an incoming stomp as to a dash (T45's note);
     a stomp needs the opponent on its edge side (within 90°).
  3. Tests: the win-rate tests use all four abilities (above); `aBotAloneNeverLeavesTheTable` plays first-to-1000 so
     the rounds keep coming for the 60 s, the dummy 5 cm in from the far (+u, +v) corner, the bot on the first mark;
     `botsNeverEmitNonFiniteOrOutsideTargets` plays 20 s rounds (more resets per match). Added beyond the list:
     `theBotAnswersNoneOutsideTheFight`, `hardPrefersTheHighGround`, `aDashComingAtItNearAnEdge`,
     `hardCountersAfterADodge`, `theRamAndTheStomp` (one `decide` each, pinning the levels' traits),
     `arenasAreTheBriefs`, `theLevelTableOnEmpty` (prints, asserts nothing).
- cut/deferred: nothing cut. Not done: no bot plays the clock (when the round is about to end the bot still attacks
  rather than keeping deeper than the opponent); `DuelBot.decide` called directly on a paused match reads paused bodies
  as paused (it then answers None): call it through `DuelRunner`, which resumes first.

### What T48 must know
- Drive bots only through `DuelSim.series / match` or `DuelRunner.step`, with the same `rules`, `params`, `physics`
  and `tuning` as `DuelMatch.start` got. `tuning.tickMs` must be a multiple of `params.subStepMs` (`requireFits`
  refuses otherwise). A seed is a match: the bots' generators start at `2 * seed` and `2 * seed + 1`, and a series
  swaps the marks every other match, so side bias cancels in pairs (use even `n`).
- Every bot number is in `BotTuning` / `BotSkill`; distances are fighter radii and reaches come from `DuelParams`
  (`dashSpeedMps * dashMs`, `ramDashMs`), so tuning `DuelParams` moves the bot with it. `BotTuning` is `@Serializable`,
  so a tuned set can be saved next to a tuned `DuelParams`.
- Knockback first: two plain-dash bots almost never ring each other out (about 700 hits per ring-out, 92 to 99 % of the
  rounds to the clock with FLY v FLY or HIDE v HIDE at NORMAL); JUMP and ROLL owe their ring-outs to the stomp and
  the ram. EASY v EASY is all clock on EMPTY (174 s matches): the "whose drawing is stronger" show should use NORMAL
  or HARD.
- The clock: 7 % (SMALL) to 52 % (CUP_AND_BOOK, NORMAL v NORMAL) of the rounds between NORMAL and HARD bots are
  decided by distance to the edge, not a push.
  The bots do not play for it (see cut/deferred); a balance read on timeouts is partly a read on positioning.
- The weakest spot of the ordering is FLY: HARD over NORMAL is 39 / 50 on EMPTY and 34 / 50 on CUP_AND_BOOK with FLY on
  both sides (every other ability 43 to 50). The bots lift only when a dash or a stomp is coming near an edge.
- CUP_AND_BOOK has many wall slams (750 to 1650 per 40 matches between NORMAL / HARD bots): fighters get pinned
  against the cup, and matches there run 25 to 60 % longer than on EMPTY.
- Speed: about 100 matches/s mixed (25 to 625 by pair and arena), single-threaded; series are independent per seed, so
  the CLI can split seeds across threads (JVM only, not in `:core`).
- Measured here with archetype stats and the same ability on both sides only. Ability against ability and stat
  against stat is T48's.
