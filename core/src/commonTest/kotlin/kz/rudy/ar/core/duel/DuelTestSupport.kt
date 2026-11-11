package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.physics.closestPointOnSegment

/** Table collision radius. */
internal const val R = 0.03f

/** Two touching table fighters plus 1 mm: the start gap of most hit tests. */
internal const val NEAR = 2 * R + 0.001f

internal val OPEN = Level(emptyList())

internal fun stats(power: Int = 5, speed: Int = 5, guard: Int = 5, agility: Int = 5) = FighterStats(power, speed, guard, agility)

internal fun fighter(
    id: String,
    pos: Vec2,
    stats: FighterStats = FighterStats.BALANCED,
    facing: Vec2 = Vec2(1f, 0f),
    h: Float = 0f,
    radiusM: Float = R,
    ability: Ability = Ability.JUMP,
) = DuelFighter(id, PhysicsBody(CharacterState(pos = pos, h = h, facing = facing, radiusM = radiusM, ability = ability)), stats)

internal fun cup(id: String, center: Vec2, radius: Float = 0.04f, heightM: Float = 0.09f) =
    LevelObject(id, "cup", ObjectKind.OBSTACLE, Polygon.circle(center, radius), heightM)

internal fun book(id: String, center: Vec2, width: Float = 0.1f, depth: Float = 0.1f, heightM: Float = 0.03f) =
    LevelObject(id, "book", ObjectKind.PLATFORM, Polygon.rect(center, width, depth), heightM)

internal fun distanceToPolygon(p: Vec2, poly: Polygon): Float =
    poly.edges().minOf { (a, b) -> p.distanceTo(closestPointOnSegment(p, a, b)) }

internal val DuelFighter.pos: Vec2 get() = body.state.pos

/** States after every step (index 0 = the start) and the events of each step (index i = the step that produced states[i]). */
internal class Trace(val states: List<DuelState>, val events: List<List<DuelEvent>>) {
    val all: List<DuelEvent> get() = events.flatten()
    val last: DuelState get() = states.last()

    /** Index into [states] of the step that emitted the first event matching [p]; -1 if none. */
    fun indexOf(p: (DuelEvent) -> Boolean): Int = events.indexOfFirst { list -> list.any(p) }
}

/**
 * Steps [start] for [totalMs] in frames of [dtMs] (the last one shorter if needed); [script] gives each step's inputs
 * from the state it starts from.
 */
internal fun runDuel(
    start: DuelState,
    level: Level,
    totalMs: Long,
    dtMs: Long = 16L,
    params: DuelParams = DuelParams.DEFAULT,
    physics: PhysicsParams = PhysicsParams.DEFAULT,
    script: (DuelState) -> Map<String, DuelInput> = { emptyMap() },
): Trace {
    val states = arrayListOf(start)
    val events = arrayListOf<List<DuelEvent>>(emptyList())
    var s = start
    val end = start.timeMs + totalMs
    while (s.timeMs < end) {
        val dt = minOf(dtMs, end - s.timeMs)
        val out = DuelPhysics.step(s, level, dt, script(s), params, physics)
        s = out.state
        states += s
        events += out.events
    }
    return Trace(states, events)
}

/** [this] with [DuelEvent.atMs] set to [at] (0 = "any time": compare the content of events only). */
internal fun DuelEvent.at(at: Long = 0L): DuelEvent = when (this) {
    is DuelEvent.DashStarted -> copy(atMs = at)
    is DuelEvent.Hit -> copy(atMs = at)
    is DuelEvent.Clash -> copy(atMs = at)
    is DuelEvent.WallSlam -> copy(atMs = at)
    is DuelEvent.RingOut -> copy(atMs = at)
}

/** The events without their times. */
internal fun List<DuelEvent>.untimed(): List<DuelEvent> = map { it.at(0L) }

internal fun dashStarted(id: String) = DuelEvent.DashStarted(id, 0L)
internal fun ringOut(id: String) = DuelEvent.RingOut(id, 0L)
internal fun wallSlam(id: String, objectId: String) = DuelEvent.WallSlam(id, objectId, 0L)
internal fun clash(a: String, b: String) = DuelEvent.Clash(a, b, 0L)

/** Fighter [id]'s events of kind [T]. */
internal inline fun <reified T : DuelEvent> List<DuelEvent>.of(id: String): List<T> = filterIsInstance<T>().filter {
    when (it) {
        is DuelEvent.DashStarted -> it.id == id
        is DuelEvent.WallSlam -> it.id == id
        is DuelEvent.RingOut -> it.id == id
        is DuelEvent.Hit -> it.attacker == id
        is DuelEvent.Clash -> it.a == id || it.b == id
        else -> false
    }
}

/** Inputs for one step: `"a" to Dash()` etc. */
internal fun inputs(vararg pairs: Pair<String, DuelInput>): Map<String, DuelInput> = mapOf(*pairs)

/** A script that sends [first] on the step starting at time 0 and nothing afterwards. */
internal fun atStart(vararg first: Pair<String, DuelInput>): (DuelState) -> Map<String, DuelInput> =
    { s -> if (s.timeMs == 0L) mapOf(*first) else emptyMap() }
