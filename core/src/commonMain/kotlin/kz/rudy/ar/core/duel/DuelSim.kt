package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.PhysicsParams

/**
 * One simulated match ([DuelSim.run]).
 *
 * @property winner the winner's id; null for a draw or an unfinished match.
 * @property scoreA points of the first contestant, [scoreB] of the second.
 * @property rounds rounds begun (replays count).
 * @property finished RESULT was reached; false = stopped at `maxMatchMs` of match time.
 * @property matchMs match time when it stopped (countdowns and pauses included).
 * @property ringOuts rounds ended by a ring-out (a replay when both went out in one sub-step counts here too).
 * @property timeouts rounds ended by the round clock (a tied replay counts here too).
 * @property replays rounds nobody scored, whatever ended them.
 * @property hits [DuelEvent.Hit]s (dashes, rams, counters, stomps) plus [DuelEvent.Clash]es, each clash once.
 * @property wallSlams [DuelEvent.WallSlam]s.
 * @property specials [DuelEvent.SpecialStarted]s.
 * @property specialsA T57: the first contestant's [DuelEvent.SpecialStarted]s, [specialsB] the second's.
 * @property misses T57: [DuelEvent.Missed]s, dashes and stomps that met their victim out of height reach.
 */
@Serializable
data class SimResult(
    val winner: String?,
    val scoreA: Int,
    val scoreB: Int,
    val rounds: Int,
    val finished: Boolean,
    val matchMs: Long,
    val ringOuts: Int,
    val timeouts: Int,
    val replays: Int,
    val hits: Int,
    val wallSlams: Int,
    val specials: Int,
    val specialsA: Int = 0,
    val specialsB: Int = 0,
    val misses: Int = 0,
)

/**
 * [DuelSim.series]: [matches] matches between the same two contestants.
 *
 * @property winsA matches the first contestant won, [winsB] the second, [draws] finished without a winner,
 *   [unfinished] stopped at `maxMatchMs`.
 * @property meanMatchMs mean match time of all matches.
 * @property timeoutShare rounds ended by the clock / rounds ended at all (by the clock or a ring-out, replays included):
 *   the share of points decided (or replayed) by the clock. 0 when no round ended.
 * @property rounds, [ringOuts], [timeouts], [hits], [wallSlams], [specials]: the sums of [SimResult]'s.
 */
@Serializable
data class SeriesResult(
    val matches: Int,
    val winsA: Int,
    val winsB: Int,
    val draws: Int,
    val unfinished: Int,
    val meanMatchMs: Long,
    val timeoutShare: Float,
    val rounds: Int = 0,
    val ringOuts: Int = 0,
    val timeouts: Int = 0,
    val hits: Int = 0,
    val wallSlams: Int = 0,
    val specials: Int = 0,
) {
    /** Share of the matches the first contestant won. */
    val shareA: Float get() = if (matches == 0) 0f else winsA.toFloat() / matches

    /** Share of the matches the second contestant won. */
    val shareB: Float get() = if (matches == 0) 0f else winsB.toFloat() / matches

    /** Hits per ring-out: how many hits it takes to push someone off; [Float.POSITIVE_INFINITY] with none. */
    val hitsPerRingOut: Float get() = if (ringOuts == 0) Float.POSITIVE_INFINITY else hits.toFloat() / ringOuts
}

/** A simulated match and the state it ended in. */
data class SimMatch(val result: SimResult, val final: RunnerState)

/**
 * The headless simulator: bot-vs-bot matches ([DuelRunner], [DuelBot]) to RESULT, as fast as the physics runs, for the
 * CLI to tune [DuelParams] and [BotTuning] by the thousand (T48) and for the "whose drawing is stronger" show. Pure and
 * deterministic: a seed is a match. The marks are [DuelArenas.marks] of the arena (fighters of the table radius).
 */
object DuelSim {

    /** One bot-vs-bot match to RESULT (or to [maxMatchMs] of match time: `finished = false`), see [match]. */
    fun run(
        a: Contestant,
        b: Contestant,
        arena: Level,
        levelA: BotLevel,
        levelB: BotLevel,
        seed: Long,
        stepMs: Long = 33,
        maxMatchMs: Long = 600_000,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
    ): SimResult = match(a, b, arena, levelA, levelB, seed, stepMs, maxMatchMs, rules, params, physics, tuning).result

    /**
     * [run], also returning the final [RunnerState]. [a] starts on `marks.first` ([DuelArenas.marks] by default), [b] on
     * the other; the bots' generators start from `2 * seed` (a) and `2 * seed + 1` (b), so neighbouring seeds share
     * nothing. Frames are [stepMs] long (the runner cuts them on the bots' tick grid, so any frame length gives the same
     * match).
     */
    fun match(
        a: Contestant,
        b: Contestant,
        arena: Level,
        levelA: BotLevel,
        levelB: BotLevel,
        seed: Long,
        stepMs: Long = 33,
        maxMatchMs: Long = 600_000,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
        marks: Pair<Vec2, Vec2> = DuelArenas.marks(arena, physics = physics),
    ): SimMatch {
        require(stepMs > 0L) { "stepMs must be > 0, got $stepMs" }
        require(maxMatchMs >= 0L) { "maxMatchMs must be >= 0, got $maxMatchMs" }
        val start = DuelMatch.start(a, b, arena, marks, rules, params, physics)
        var state = RunnerState(start, DuelBot.mind(levelA, 2L * seed), DuelBot.mind(levelB, 2L * seed + 1L))
        val tally = Tally(a.id)
        while (state.match.phase != MatchPhase.RESULT && state.match.duel.timeMs < maxMatchMs) {
            val dt = minOf(stepMs, maxMatchMs - state.match.duel.timeMs)
            val out = DuelRunner.step(state, arena, dt, rules = rules, params = params, physics = physics, tuning = tuning)
            for (e in out.events) tally.add(e)
            state = out.state
        }
        val m = state.match
        val result = SimResult(
            winner = m.winner,
            scoreA = m.scoreA,
            scoreB = m.scoreB,
            rounds = m.round,
            finished = m.phase == MatchPhase.RESULT,
            matchMs = m.duel.timeMs,
            ringOuts = tally.ringOuts,
            timeouts = tally.timeouts,
            replays = tally.replays,
            hits = tally.hits,
            wallSlams = tally.wallSlams,
            specials = tally.specials,
            specialsA = tally.specialsA,
            specialsB = tally.specials - tally.specialsA,
            misses = tally.misses,
        )
        return SimMatch(result, state)
    }

    /**
     * [n] matches with seeds `seed until seed + n`; the two contestants swap marks every other match (even offsets:
     * [a] on the first mark), so neither side of the arena favours one of them. Wins are counted by contestant.
     */
    fun series(
        a: Contestant,
        b: Contestant,
        arena: Level,
        levelA: BotLevel,
        levelB: BotLevel,
        n: Int,
        seed: Long,
        stepMs: Long = 33,
        maxMatchMs: Long = 600_000,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
    ): SeriesResult {
        require(n >= 0) { "n must be >= 0, got $n" }
        val marks = DuelArenas.marks(arena, physics = physics)
        val swapped = marks.second to marks.first
        var winsA = 0
        var winsB = 0
        var draws = 0
        var unfinished = 0
        var totalMs = 0L
        var rounds = 0
        var ringOuts = 0
        var timeouts = 0
        var hits = 0
        var wallSlams = 0
        var specials = 0
        for (i in 0 until n) {
            val r = match(a, b, arena, levelA, levelB, seed + i, stepMs, maxMatchMs, rules, params, physics, tuning, if (i % 2 == 0) marks else swapped).result
            when {
                !r.finished -> unfinished++
                r.winner == a.id -> winsA++
                r.winner == b.id -> winsB++
                else -> draws++
            }
            totalMs += r.matchMs
            rounds += r.rounds
            ringOuts += r.ringOuts
            timeouts += r.timeouts
            hits += r.hits
            wallSlams += r.wallSlams
            specials += r.specials
        }
        val ended = ringOuts + timeouts
        return SeriesResult(
            matches = n,
            winsA = winsA,
            winsB = winsB,
            draws = draws,
            unfinished = unfinished,
            meanMatchMs = if (n == 0) 0L else totalMs / n,
            timeoutShare = if (ended == 0) 0f else timeouts.toFloat() / ended,
            rounds = rounds,
            ringOuts = ringOuts,
            timeouts = timeouts,
            hits = hits,
            wallSlams = wallSlams,
            specials = specials,
        )
    }

    /** Counts a match's events; [aId] is the first contestant's id. */
    private class Tally(private val aId: String) {
        var ringOuts = 0
        var timeouts = 0
        var replays = 0
        var hits = 0
        var wallSlams = 0
        var specials = 0
        var specialsA = 0
        var misses = 0

        fun add(e: MatchEvent) {
            when (e) {
                is MatchEvent.Point -> {
                    if (e.reason == PointReason.RING_OUT) ringOuts++ else timeouts++
                    if (e.winner == null) replays++
                }
                is MatchEvent.Fight -> when (e.event) {
                    is DuelEvent.Hit, is DuelEvent.Clash -> hits++
                    is DuelEvent.WallSlam -> wallSlams++
                    is DuelEvent.SpecialStarted -> {
                        specials++
                        if ((e.event as DuelEvent.SpecialStarted).id == aId) specialsA++
                    }
                    is DuelEvent.Missed -> misses++
                    else -> Unit
                }
                else -> Unit
            }
        }
    }
}
