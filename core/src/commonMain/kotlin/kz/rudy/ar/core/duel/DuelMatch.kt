package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.abs

/**
 * A match between two fighters ([DuelMatch]); everything a HUD or a bot needs, without events.
 *
 * @property duel both fighters and the match clock: `duel.timeMs` is the simulated time since [DuelMatch.start],
 *   counting only tracked time, in every phase.
 * @property phaseLeftMs COUNTDOWN / POINT: time left in the phase; FIGHT: time left in the round; RESULT: 0.
 * @property round 1-based; replays count.
 * @property scoreA points of `duel.a`, [scoreB] of `duel.b` ([scoreOf] by id).
 * @property winner set in RESULT; null there = a draw.
 * @property startA the mark `duel.a` returns to before every round, [startB] the other.
 * @property lastPoint how the last round ended (who scored, or a replay, and why); null before the first point.
 */
@Serializable
data class DuelMatchState(
    val duel: DuelState,
    val phase: MatchPhase,
    val phaseLeftMs: Long,
    val round: Int,
    val scoreA: Int,
    val scoreB: Int,
    val winner: String? = null,
    val startA: Vec2,
    val startB: Vec2,
    val lastPoint: PointResult? = null,
) {
    /** Points of the fighter with [id]; 0 for an unknown id. */
    fun scoreOf(id: String): Int = when (id) {
        duel.a.id -> scoreA
        duel.b.id -> scoreB
        else -> 0
    }
}

/** Something that happened in a [DuelMatch.step], in the order it happened. */
@Serializable
sealed interface MatchEvent {
    /** FIGHT of [round] begins at simulated time [atMs]. */
    @Serializable
    data class RoundStarted(val round: Int, val atMs: Long) : MatchEvent

    /** A round ended at [atMs]: [winner] scores; null = a replay, nobody scores. */
    @Serializable
    data class Point(val winner: String?, val reason: PointReason, val atMs: Long) : MatchEvent

    /** RESULT reached at [atMs]: [winner] won; null = a draw. */
    @Serializable
    data class MatchOver(val winner: String?, val atMs: Long) : MatchEvent

    /** Everything [DuelPhysics] reported, in its order (FIGHT and POINT). */
    @Serializable
    data class Fight(val event: DuelEvent) : MatchEvent
}

/** The result of one [DuelMatch.step]. */
@Serializable
data class MatchStep(val state: DuelMatchState, val events: List<MatchEvent>)

/**
 * The rules of a duel around [DuelPhysics] (ai/build-plan-v2.md, "The game"): COUNTDOWN on the marks, FIGHT until a
 * ring-out or the round clock, POINT while the fall plays out, back to the marks; first to [DuelRules.pointsToWin], a
 * draw after [DuelRules.maxRounds]. Pure and deterministic, on simulated time only.
 *
 * **One clock on the sub-step grid.** `duel.timeMs` is the match clock in every phase. Every phase starts and ends on
 * the grid of [DuelParams.subStepMs] (its length is rounded up to whole sub-steps; a ring-out ends a round at the end of
 * its sub-step), so the match and the bodies never disagree and any split of the same time into frames goes through the
 * same transitions at the same simulated times: same states, same events. A call runs its time through the phases in
 * order: a ring-out at 1000 ms in a call from 990 to 1023 ms ends the round at 1000 and spends the 23 ms left in POINT.
 *
 * **Phases.**
 * - COUNTDOWN: both stand on their marks facing each other, inputs ignored, no physics. At 0: FIGHT, `round`'s clock
 *   ([DuelRules.roundMs]) starts, [MatchEvent.RoundStarted].
 * - FIGHT: the call's inputs go to [DuelPhysics] if the call starts in FIGHT (an input belongs to the time it is given
 *   at, the start of its call: given in a countdown, it is ignored even if the fight starts during that call). The
 *   first sub-step with a [DuelEvent.RingOut] ends the round at once, nothing after it is simulated: one fighter out =
 *   a point for the other; both out in that same sub-step (equal `atMs`) = a replay. So two ring-outs are judged by the
 *   sub-step they happened in, never by the call they arrived in. Round clock at 0 without a ring-out: the fighter
 *   whose centre is farther inside the boundary ([DuelArena.insideDistance]) takes the point; within
 *   [DuelRules.timeoutTieM] a replay. Either way [MatchEvent.Point].
 * - POINT ([DuelRules.pointPauseMs]): what both were doing stops (dash, walk target, lift-off, dodge, stomp, counter:
 *   [DuelPhysics.endActions]); inputs ignored. A fighter that is out ([DuelPhysics.isOut]) keeps falling as Physics
 *   makes it fall (that is the thing to see), and on the sub-step Physics would respawn it, it stands on its own mark
 *   instead, frozen (if the other one stands over that mark it stays out of sight until the end). A fighter that is not
 *   out is frozen where it stands for the whole phase: nothing moves it, nothing can make it fall. No event comes out of
 *   POINT. At 0 both are rebuilt on their marks (IDLE, no knockback, stun or special, cooldowns cleared); then RESULT if
 *   a fighter has [DuelRules.pointsToWin] (or [DuelRules.maxRounds] rounds were played: a draw, [MatchEvent.MatchOver]),
 *   else the next round's COUNTDOWN.
 * - RESULT: terminal, [step] returns the state unchanged with no events.
 *
 * **Between rounds** the arena may be rescanned (objects moved): [rearrange] takes the new arena's marks in COUNTDOWN
 * or POINT.
 *
 * **Tracking.** `tracking = false` (the AR session lost tracking) pauses BOTH bodies ([Physics.pause]): no clock runs,
 * no input applies, no event; the state stays paused until a call with `tracking = true` resumes both and goes on
 * exactly where it stopped. Never rely on an event on the first frame back: read the state.
 */
object DuelMatch {

    /**
     * The match before its first countdown: [a] in slot `a` on `marks.first`, [b] on `marks.second`, facing each other,
     * standing on whatever ground is there, with [radiusM] collision circles ([SurfaceProfile.radiusAt]) and their
     * abilities on both the duel fighter (the special) and the body (the walking game's FLY glide, ROLL no-jump).
     * `duel.timeMs` starts at 0. Requires (see [DuelArena.startProblem]) an arena with a boundary (a raw floor level
     * would make every match a draw: build it with [DuelArena.arena] first) and marks that are free spots for [radiusM]
     * on it, apart, at the same ground height ([DuelArena.marks] gives such marks).
     */
    fun start(
        a: Contestant,
        b: Contestant,
        arena: Level,
        marks: Pair<Vec2, Vec2>,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        radiusM: Float = SurfaceProfile.TABLE.radiusM,
    ): DuelMatchState {
        require(a.id != b.id) { "duel contestants need distinct ids, got '${a.id}' twice" }
        DuelArena.startProblem(arena, marks, radiusM, physics)?.let { throw IllegalArgumentException(it) }
        val (ma, mb) = marks
        fun fresh(c: Contestant) =
            DuelFighter(c.id, PhysicsBody(CharacterState(radiusM = radiusM, ability = c.ability)), c.stats, ability = c.ability)
        val duel = DuelState(onMark(fresh(a), ma, mb, arena, physics), onMark(fresh(b), mb, ma, arena, physics), timeMs = 0L)
        return DuelMatchState(
            duel = duel,
            phase = MatchPhase.COUNTDOWN,
            phaseLeftMs = aligned(rules.countdownMs, params),
            round = 1,
            scoreA = 0,
            scoreB = 0,
            startA = ma,
            startB = mb,
        )
    }

    /**
     * Advances the match by [dtMs] (clamped to [DuelRules.maxStepMs]) of tracked time, see [DuelMatch]. [inputs] are by
     * fighter id. [tracking] false = the AR session lost tracking: bodies pause, no clock runs, no event; true resumes.
     * `dtMs <= 0` advances nothing (it still resumes a paused match). Use the same [rules], [params] and [physics] for
     * the whole match.
     */
    fun step(
        state: DuelMatchState,
        arena: Level,
        dtMs: Long,
        inputs: Map<String, DuelInput> = emptyMap(),
        tracking: Boolean = true,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): MatchStep {
        if (state.phase == MatchPhase.RESULT) return MatchStep(state, emptyList())
        if (!tracking) return MatchStep(pauseBoth(state), emptyList())
        var s = resumeBoth(state)
        var left = minOf(dtMs, rules.maxStepMs)
        if (left <= 0L) return MatchStep(s, emptyList())
        var pending = inputs
        val events = ArrayList<MatchEvent>(2)
        while (true) {
            when (s.phase) {
                MatchPhase.RESULT -> break
                MatchPhase.COUNTDOWN -> {
                    if (s.phaseLeftMs <= 0L) {
                        s = s.copy(phase = MatchPhase.FIGHT, phaseLeftMs = aligned(rules.roundMs, params))
                        events += MatchEvent.RoundStarted(s.round, s.duel.timeMs)
                        continue
                    }
                    if (left <= 0L) break
                    val d = minOf(left, s.phaseLeftMs)
                    s = s.copy(duel = s.duel.copy(timeMs = s.duel.timeMs + d), phaseLeftMs = s.phaseLeftMs - d)
                    left -= d
                    pending = emptyMap()
                }
                MatchPhase.FIGHT -> {
                    if (s.phaseLeftMs <= 0L) {
                        s = timeout(s, arena, rules, params, events)
                        continue
                    }
                    if (left <= 0L) break
                    val d = minOf(left, s.phaseLeftMs)
                    val out = DuelPhysics.stepToRingOut(s.duel, arena, d, pending, params, physics)
                    pending = emptyMap()
                    val used = out.state.timeMs - s.duel.timeMs
                    for (e in out.events) events += MatchEvent.Fight(e)
                    left -= used
                    s = s.copy(duel = out.state, phaseLeftMs = s.phaseLeftMs - used)
                    val rang = out.events.filterIsInstance<DuelEvent.RingOut>()
                    if (rang.isNotEmpty()) s = ringOutPoint(s, rang, rules, params, events)
                }
                MatchPhase.POINT -> {
                    if (s.phaseLeftMs <= 0L) {
                        s = afterPoint(s, arena, rules, params, physics, events)
                        continue
                    }
                    if (left <= 0L) break
                    val d = minOf(left, s.phaseLeftMs)
                    s = s.copy(duel = pointStep(s, arena, d, params, physics), phaseLeftMs = s.phaseLeftMs - d)
                    left -= d
                    pending = emptyMap()
                }
            }
        }
        return MatchStep(s, events)
    }

    /**
     * The arena was rescanned between rounds (objects moved): [marks], valid on the new [arena] as for [start]
     * ([DuelArena.startProblem], for the fighters' radius), become the marks both fighters start the next rounds on.
     * Allowed in COUNTDOWN, where both are moved onto them at once facing each other, and in POINT, where they are used
     * from now on (a loser that already stands on its old mark stays there until the phase ends). FIGHT and RESULT
     * refuse. Pass the new arena to every [step] from now on.
     */
    fun rearrange(
        state: DuelMatchState,
        arena: Level,
        marks: Pair<Vec2, Vec2>,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): DuelMatchState {
        require(state.phase == MatchPhase.COUNTDOWN || state.phase == MatchPhase.POINT) {
            "rearrange only between rounds (COUNTDOWN or POINT), not in ${state.phase}"
        }
        val a = state.duel.a
        val b = state.duel.b
        val radius = maxOf(a.body.state.radiusM, b.body.state.radiusM)
        DuelArena.startProblem(arena, marks, radius, physics)?.let { throw IllegalArgumentException(it) }
        val (ma, mb) = marks
        val moved = state.copy(startA = ma, startB = mb)
        if (state.phase != MatchPhase.COUNTDOWN) return moved
        fun keepPause(f: DuelFighter, g: DuelFighter) = if (f.body.state.mode == Mode.PAUSED) g.copy(body = Physics.pause(g.body)) else g
        return moved.copy(
            duel = state.duel.copy(a = keepPause(a, onMark(a, ma, mb, arena, physics)), b = keepPause(b, onMark(b, mb, ma, arena, physics))),
        )
    }

    /**
     * [d] ms of POINT on the sub-step grid. Only a fighter that is out moves: it falls as Physics makes it fall (its
     * position kept, as [DuelPhysics] keeps an out body's) and, on the sub-step Physics would respawn it, stands on its
     * own mark instead, frozen; if the other fighter stands over that mark it stays down (RESPAWNING) to the end of the
     * phase. A fighter that is not out does not move at all. No events.
     */
    private fun pointStep(s: DuelMatchState, arena: Level, d: Long, params: DuelParams, physics: PhysicsParams): DuelState {
        val sub = params.subStepMs
        val t0 = s.duel.timeMs
        val cells = (t0 + d).floorDiv(sub) - t0.floorDiv(sub)
        var a = s.duel.a
        var b = s.duel.b
        for (i in 0 until cells) {
            a = fallOrStand(a, b, s.startA, s.startB, arena, sub, physics)
            b = fallOrStand(b, a, s.startB, s.startA, arena, sub, physics)
        }
        return DuelState(a, b, t0 + d)
    }

    private fun fallOrStand(
        f: DuelFighter,
        other: DuelFighter,
        mark: Vec2,
        toward: Vec2,
        arena: Level,
        sub: Long,
        physics: PhysicsParams,
    ): DuelFighter {
        if (!DuelPhysics.isOut(f, arena)) return f
        val body = f.body
        val next = if (body.state.mode == Mode.RESPAWNING) {
            body
        } else {
            val after = Physics.step(body, arena, sub, physics)
            if (after.state.mode == Mode.RESPAWNING || after.state.pos == body.state.pos) after else after.copy(state = after.state.copy(pos = body.state.pos))
        }
        if (next.state.mode != Mode.RESPAWNING) return f.copy(body = next)
        if (other.body.state.pos.distanceTo(mark) < next.state.radiusM + other.body.state.radiusM) return f.copy(body = next)
        return onMark(f, mark, toward, arena, physics)
    }

    /** [ms] rounded up to whole sub-steps of [params]. */
    private fun aligned(ms: Long, params: DuelParams): Long {
        if (ms <= 0L) return 0L
        val sub = params.subStepMs
        return (ms + sub - 1L) / sub * sub
    }

    private fun pauseBoth(s: DuelMatchState): DuelMatchState {
        val a = s.duel.a
        val b = s.duel.b
        if (a.body.state.mode == Mode.PAUSED && b.body.state.mode == Mode.PAUSED) return s
        return s.copy(duel = s.duel.copy(a = a.copy(body = Physics.pause(a.body)), b = b.copy(body = Physics.pause(b.body))))
    }

    private fun resumeBoth(s: DuelMatchState): DuelMatchState {
        val a = s.duel.a
        val b = s.duel.b
        if (a.body.state.mode != Mode.PAUSED && b.body.state.mode != Mode.PAUSED) return s
        return s.copy(duel = s.duel.copy(a = a.copy(body = Physics.resume(a.body)), b = b.copy(body = Physics.resume(b.body))))
    }

    /** The round ended in the sub-step of [rang]: whoever is out in its earliest sub-step loses; both = a replay. */
    private fun ringOutPoint(
        s: DuelMatchState,
        rang: List<DuelEvent.RingOut>,
        rules: DuelRules,
        params: DuelParams,
        events: MutableList<MatchEvent>,
    ): DuelMatchState {
        val first = rang.minOf { it.atMs }
        val out = rang.filter { it.atMs == first }.map { it.id }.toSet()
        val a = s.duel.a.id
        val b = s.duel.b.id
        val winner = when {
            a in out && b in out -> null
            a in out -> b
            else -> a
        }
        return point(s, winner, PointReason.RING_OUT, first, rules, params, events)
    }

    private fun timeout(
        s: DuelMatchState,
        arena: Level,
        rules: DuelRules,
        params: DuelParams,
        events: MutableList<MatchEvent>,
    ): DuelMatchState {
        val boundary = arena.boundary
        val winner = if (boundary == null) {
            null
        } else {
            val da = DuelArena.insideDistance(boundary, s.duel.a.body.state.pos)
            val db = DuelArena.insideDistance(boundary, s.duel.b.body.state.pos)
            when {
                abs(da - db) <= rules.timeoutTieM -> null
                da > db -> s.duel.a.id
                else -> s.duel.b.id
            }
        }
        return point(s, winner, PointReason.TIMEOUT, s.duel.timeMs, rules, params, events)
    }

    private fun point(
        s: DuelMatchState,
        winner: String?,
        reason: PointReason,
        atMs: Long,
        rules: DuelRules,
        params: DuelParams,
        events: MutableList<MatchEvent>,
    ): DuelMatchState {
        events += MatchEvent.Point(winner, reason, atMs)
        val duel = s.duel.copy(a = DuelPhysics.endActions(s.duel.a), b = DuelPhysics.endActions(s.duel.b))
        return s.copy(
            duel = duel,
            phase = MatchPhase.POINT,
            phaseLeftMs = aligned(rules.pointPauseMs, params),
            scoreA = s.scoreA + if (winner == duel.a.id) 1 else 0,
            scoreB = s.scoreB + if (winner == duel.b.id) 1 else 0,
            lastPoint = PointResult(s.round, winner, reason),
        )
    }

    private fun afterPoint(
        s: DuelMatchState,
        arena: Level,
        rules: DuelRules,
        params: DuelParams,
        physics: PhysicsParams,
        events: MutableList<MatchEvent>,
    ): DuelMatchState {
        val a = s.duel.a
        val b = s.duel.b
        val duel = DuelState(onMark(a, s.startA, s.startB, arena, physics), onMark(b, s.startB, s.startA, arena, physics), s.duel.timeMs)
        val winner = when {
            s.scoreA >= rules.pointsToWin -> a.id
            s.scoreB >= rules.pointsToWin -> b.id
            else -> null
        }
        if (winner != null || s.round >= rules.maxRounds) {
            events += MatchEvent.MatchOver(winner, s.duel.timeMs)
            return s.copy(duel = duel, phase = MatchPhase.RESULT, phaseLeftMs = 0L, winner = winner)
        }
        return s.copy(duel = duel, phase = MatchPhase.COUNTDOWN, phaseLeftMs = aligned(rules.countdownMs, params), round = s.round + 1)
    }

    /** [f] rebuilt standing on [mark] facing [toward]: IDLE, nothing running, every timer cleared; id, stats, ability, size kept. */
    private fun onMark(f: DuelFighter, mark: Vec2, toward: Vec2, arena: Level, physics: PhysicsParams): DuelFighter {
        val s = f.body.state
        val facing = (toward - mark).normalized().takeIf { it != Vec2.ZERO } ?: Vec2(1f, 0f)
        val h = Physics.groundHeight(arena, mark, 0f, physics) ?: 0f
        val body = PhysicsBody(CharacterState(pos = mark, h = h, facing = facing, radiusM = s.radiusM, ability = s.ability))
        return DuelFighter(f.id, body, f.stats, ability = f.ability)
    }
}
