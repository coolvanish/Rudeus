package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.physics.PhysicsParams

/**
 * A match with its bots: [botA] plays `match.duel.a`, [botB] plays `match.duel.b`. A side without a mind is played by
 * the caller's inputs (the child's taps, or nobody). Serializable: a saved match resumes with the same bots.
 */
@Serializable
data class RunnerState(val match: DuelMatchState, val botA: BotMind? = null, val botB: BotMind? = null) {
    /** The mind playing fighter [id]; null for a side the caller plays (or an unknown id). */
    fun botOf(id: String): BotMind? = when (id) {
        match.duel.a.id -> botA
        match.duel.b.id -> botB
        else -> null
    }
}

/** A bot's answer that was not [DuelInput.None]: fighter [id] sent [input] at match time [atMs] (a tick). */
@Serializable
data class BotDecision(val id: String, val atMs: Long, val input: DuelInput)

/**
 * The result of one [DuelRunner.step]: the new state, every match event in order (as [DuelMatch.step] reports them), and
 * what the bots sent ([decisions], for logs and tests; empty without bots).
 */
@Serializable
data class RunnerStep(val state: RunnerState, val events: List<MatchEvent>, val decisions: List<BotDecision> = emptyList())

/**
 * Steps a match ([DuelMatch.step]) with zero, one or two bots ([DuelBot]) playing their sides, so that a host calls it
 * once per AR frame and the simulator ([DuelSim]) as fast as it likes, with the same match coming out.
 *
 * **The decision grid.** Bots decide only at match times (`duel.timeMs`) that are multiples of [BotTuning.tickMs]: a
 * frame is cut at every tick it crosses, the bots decide on the state at the tick, and their inputs start the piece that
 * begins there. A tick is a multiple of the physics sub-step, so every input lands on the sub-step grid; together with
 * [DuelMatch]'s own guarantee (any split of the same time gives the same match) the frame length changes nothing: a
 * bot-vs-bot match stepped at 16, 33, 50 or random frames is the same match, state for state and event for event.
 *
 * **The human's side.** [step]'s `inputs` are applied at the start of the call, as in [DuelMatch.step] (in the piece
 * before the first tick, or with the bots' inputs when the call starts on a tick); an input for a side that has a bot is
 * ignored.
 *
 * **Tracking.** `tracking = false` pauses the match ([DuelMatch.step] with `tracking = false`): no clock runs and no bot
 * decides, so the minds are returned as they were. The first tracked call resumes both bodies before any bot reads the
 * state, so a bot never decides on a paused body.
 *
 * Without bots [step] is exactly [DuelMatch.step]. RESULT is terminal: the state comes back unchanged.
 */
object DuelRunner {

    /**
     * Advances [state] by [dtMs] of match time (clamped to [DuelRules.maxStepMs], as [DuelMatch.step] clamps), the bots
     * deciding on the [BotTuning.tickMs] grid. Use the same [rules], [params], [physics] and [tuning] for the whole match.
     */
    fun step(
        state: RunnerState,
        arena: Level,
        dtMs: Long,
        inputs: Map<String, DuelInput> = emptyMap(),
        tracking: Boolean = true,
        rules: DuelRules = DuelRules(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
    ): RunnerStep {
        var match = state.match
        if (state.botA == null && state.botB == null) {
            val out = DuelMatch.step(match, arena, dtMs, inputs, tracking, rules, params, physics)
            return RunnerStep(state.copy(match = out.state), out.events)
        }
        tuning.requireFits(params)
        if (match.phase == MatchPhase.RESULT) return RunnerStep(state, emptyList())
        if (!tracking) {
            val out = DuelMatch.step(match, arena, dtMs, emptyMap(), tracking = false, rules = rules, params = params, physics = physics)
            return RunnerStep(state.copy(match = out.state), out.events)
        }
        // Resume both bodies (a zero step advances nothing) so the bots read the bodies as they were before any pause.
        match = DuelMatch.step(match, arena, 0L, emptyMap(), tracking = true, rules = rules, params = params, physics = physics).state
        val idA = match.duel.a.id
        val idB = match.duel.b.id
        var botA = state.botA
        var botB = state.botB
        var pending: Map<String, DuelInput> = when {
            inputs.isEmpty() -> inputs
            botA != null && botB != null -> emptyMap()
            else -> inputs.filterKeys { (botA == null || it != idA) && (botB == null || it != idB) }
        }
        var left = minOf(dtMs, rules.maxStepMs)
        val tick = tuning.tickMs
        var events: ArrayList<MatchEvent>? = null
        var decisions: ArrayList<BotDecision>? = null
        while (left > 0L && match.phase != MatchPhase.RESULT) {
            val t = match.duel.timeMs
            var piece = pending
            if (t.mod(tick) == 0L) {
                var bots: HashMap<String, DuelInput>? = null
                if (botA != null) {
                    val (mind, input) = DuelBot.decide(botA, idA, match, arena, params, physics, tuning)
                    botA = mind
                    if (input != DuelInput.None) {
                        bots = HashMap(pending)
                        bots[idA] = input
                        (decisions ?: ArrayList<BotDecision>(2).also { decisions = it }) += BotDecision(idA, t, input)
                    }
                }
                if (botB != null) {
                    val (mind, input) = DuelBot.decide(botB, idB, match, arena, params, physics, tuning)
                    botB = mind
                    if (input != DuelInput.None) {
                        if (bots == null) bots = HashMap(pending)
                        bots[idB] = input
                        (decisions ?: ArrayList<BotDecision>(2).also { decisions = it }) += BotDecision(idB, t, input)
                    }
                }
                if (bots != null) piece = bots
            }
            val d = minOf(left, tick - t.mod(tick))
            val out = DuelMatch.step(match, arena, d, piece, tracking = true, rules = rules, params = params, physics = physics)
            if (out.events.isNotEmpty()) (events ?: ArrayList<MatchEvent>(4).also { events = it }) += out.events
            match = out.state
            pending = emptyMap()
            left -= d
        }
        return RunnerStep(RunnerState(match, botA, botB), events ?: emptyList(), decisions ?: emptyList())
    }
}
