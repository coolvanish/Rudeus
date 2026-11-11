# T55 — duel-bot-sim

status: todo
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
- measured: ...
- cut/deferred: ...
