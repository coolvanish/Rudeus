package kz.rudy.ar.core.scenario

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams

/**
 * What one [ScenarioRunner.run] produced.
 *
 * @property events `(tick time ms, event)`: the tick at which the frame's steps were issued and physics advanced.
 * @property bodies the body after each physics step (tick `i` starts at `min(i * dtMs, durationMs)`).
 * @property final the last body.
 */
@Serializable
data class ScenarioTrace(
    val events: List<Pair<Long, CharacterEvent>>,
    val bodies: List<PhysicsBody>,
    val final: PhysicsBody,
) {
    fun eventNames(): List<CharacterEvent> = events.map { it.second }
}

object ScenarioRunner {
    /**
     * Fixed-dt replay, the way the host's frame loop runs it: at each tick (clock `0, dt, 2dt, ...`, the last tick
     * clamped to `durationMs` so a step at `durationMs` is still issued) the due steps are applied
     * (WalkTo -> `Physics.walkTo(body, target)`, Jump -> `Physics.jump(body, params)`), then
     * `Physics.step(body, level, dtMs, params)`, then `CharacterEvents.between(before, after)` across the whole tick.
     * Starts IDLE at `level.spawn` with [ability] and [radiusM]. Deterministic for fixed inputs.
     */
    fun run(
        scenario: Scenario,
        level: Level,
        ability: Ability,
        params: PhysicsParams = PhysicsParams.DEFAULT,
        radiusM: Float = SurfaceProfile.TABLE.radiusM,
        dtMs: Long = 16,
    ): ScenarioTrace {
        require(dtMs > 0) { "dtMs must be > 0, got $dtMs" }
        val player = ScenarioPlayer(scenario, startedAtMs = 0L)
        val events = ArrayList<Pair<Long, CharacterEvent>>()
        val bodies = ArrayList<PhysicsBody>((scenario.durationMs / dtMs + 2).toInt())
        var body = PhysicsBody(CharacterState(pos = level.spawn, ability = ability, radiusM = radiusM))
        var t = 0L
        while (true) {
            val before = body
            for (step in player.due(t)) {
                body = when (step) {
                    is ScenarioStep.WalkTo -> Physics.walkTo(body, step.target)
                    is ScenarioStep.Jump -> Physics.jump(body, params)
                }
            }
            body = Physics.step(body, level, dtMs, params)
            for (e in CharacterEvents.between(before, body)) events += t to e
            bodies += body
            if (t >= scenario.durationMs) break
            t = minOf(t + dtMs, scenario.durationMs)
        }
        return ScenarioTrace(events, bodies, body)
    }
}
