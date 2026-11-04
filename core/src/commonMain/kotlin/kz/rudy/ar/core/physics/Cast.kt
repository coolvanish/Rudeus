package kz.rudy.ar.core.physics

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.sqrt

/** One character on the table as physics sees it. */
@Serializable
data class CastMember(val id: String, val body: PhysicsBody)

/**
 * Every character on the table, in the order they were added (the first is the one Play started with). Immutable:
 * every operation returns a new Cast. Ids are unique; at most [MAX] members.
 */
@Serializable
data class Cast(val members: List<CastMember> = emptyList()) {
    init {
        require(members.size <= MAX) { "a cast holds at most $MAX members, got ${members.size}" }
        require(members.map { it.id }.distinct().size == members.size) { "member ids must be unique: ${members.map { it.id }}" }
    }

    /** Member ids in cast order. */
    val ids: List<String> get() = members.map { it.id }

    /** The body of member [id]; null for an unknown id. */
    operator fun get(id: String): PhysicsBody? = members.firstOrNull { it.id == id }?.body

    /** Replaces member [id] in place, or appends it (IllegalArgumentException past [MAX]). */
    fun with(id: String, body: PhysicsBody): Cast {
        val i = members.indexOfFirst { it.id == id }
        if (i < 0) return Cast(members + CastMember(id, body))
        if (members[i].body == body) return this
        return Cast(members.toMutableList().also { it[i] = CastMember(id, body) })
    }

    /** The cast without member [id]; an unknown id returns this. */
    fun without(id: String): Cast =
        if (members.none { it.id == id }) this else Cast(members.filter { it.id != id })

    companion object {
        const val MAX = 4
    }
}

/**
 * [Physics] for every member of a [Cast] at once. A cast of one steps exactly like a single [PhysicsBody] (separation
 * needs two). Pure and deterministic: every function returns a new value.
 */
object CastPhysics {

    /** [Physics.step] on every member (same level, dt, params), then [separate] when [separate] is true. */
    fun step(
        cast: Cast,
        level: Level,
        dtMs: Long,
        params: PhysicsParams = PhysicsParams.DEFAULT,
        separate: Boolean = true,
    ): Cast {
        val stepped = Cast(cast.members.map { CastMember(it.id, Physics.step(it.body, level, dtMs, params)) })
        return if (separate) separate(stepped, level, params) else stepped
    }

    /** [Physics.walkTo] on member [id]; an unknown id returns [cast]. */
    fun walkTo(cast: Cast, id: String, target: Vec2): Cast {
        val body = cast[id] ?: return cast
        return cast.with(id, Physics.walkTo(body, target))
    }

    /** [Physics.jump] on member [id]; an unknown id returns [cast]. */
    fun jump(cast: Cast, id: String, params: PhysicsParams = PhysicsParams.DEFAULT): Cast {
        val body = cast[id] ?: return cast
        return cast.with(id, Physics.jump(body, params))
    }

    /** [Physics.pause] on every member (idempotent). */
    fun pause(cast: Cast): Cast = Cast(cast.members.map { CastMember(it.id, Physics.pause(it.body)) })

    /** [Physics.resume] on every member: each gets back the exact mode it had before [pause]. */
    fun resume(cast: Cast): Cast = Cast(cast.members.map { CastMember(it.id, Physics.resume(it.body)) })

    /**
     * Keeps collision circles apart. Only members on the ground (IDLE or WALKING) take part; JUMPING, FALLING,
     * RESPAWNING and PAUSED members neither push nor move (a jump passes over a friend). Pairs in id order (ids sorted,
     * so the result does not depend on who joined first), one pass, each pair seeing the pushes before it.
     * For an overlapping pair (dist < r1 + r2) each moves half the overlap along the centre line (dist == 0: the
     * later id along +u, the earlier along -u).
     * A member whose half-push would land over a cliff ([Physics.isOverCliff]) or inside a blocking object
     * (`Physics.blocks(o, feetH) && o.footprint.contains(p)`) stays put and the other takes the whole overlap if it can,
     * else both stay. Only `pos` changes: `h`, mode, target, facing, `blocked` are untouched (a friend is not a cup).
     */
    fun separate(cast: Cast, level: Level, params: PhysicsParams = PhysicsParams.DEFAULT): Cast {
        val onGround = cast.members.filter { it.body.state.mode == Mode.IDLE || it.body.state.mode == Mode.WALKING }
        if (onGround.size < 2) return cast
        val order = onGround.sortedBy { it.id }
        val pos = Array(order.size) { order[it].body.state.pos }
        for (i in order.indices) {
            val a = order[i].body.state
            for (j in i + 1 until order.size) {
                val b = order[j].body.state
                val pa = pos[i]
                val pb = pos[j]
                val d = pb - pa
                val dist = d.length
                val reach = a.radiusM + b.radiusM
                if (dist >= reach) continue
                val dir = if (dist < Vec2.EPS) Vec2(1f, 0f) else d / dist
                val overlap = reach - dist
                val half = dir * (overlap / 2f)
                val aHalf = pa - half
                val bHalf = pb + half
                val aOk = isFree(level, aHalf, a.h, params)
                val bOk = isFree(level, bHalf, b.h, params)
                when {
                    aOk && bOk -> { pos[i] = aHalf; pos[j] = bHalf }
                    bOk -> (pb + dir * overlap).let { if (isFree(level, it, b.h, params)) pos[j] = it }
                    aOk -> (pa - dir * overlap).let { if (isFree(level, it, a.h, params)) pos[i] = it }
                }
            }
        }
        val moved = HashMap<String, Vec2>()
        for (k in order.indices) if (pos[k] != order[k].body.state.pos) moved[order[k].id] = pos[k]
        if (moved.isEmpty()) return cast
        return Cast(
            cast.members.map { m ->
                val p = moved[m.id] ?: return@map m
                m.copy(body = m.body.copy(state = m.body.state.copy(pos = p)))
            },
        )
    }

    /**
     * A free spot for a new member of radius [radiusM]: candidates at `near + dir * d` for the 8 directions starting at
     * [preferred] (unit vector in plane coords, rotated counter-clockwise by 45° steps), first with d = 2.5 * radiusM,
     * then d = 5 * radiusM. A candidate is free when `Physics.groundHeight(level, p, 0f, params) != null` (inside the
     * boundary, not a cliff), no blocking object (`Physics.blocks(o, 0f)`) contains it, and it is at least
     * `radiusM + member.radiusM` from every member. None free: `level.spawn`. Pure.
     */
    fun spawnNear(
        level: Level,
        cast: Cast,
        radiusM: Float,
        near: Vec2,
        preferred: Vec2 = Vec2(1f, 0f),
        params: PhysicsParams = PhysicsParams.DEFAULT,
    ): Vec2 {
        val first = preferred.normalized().takeIf { it != Vec2.ZERO } ?: Vec2(1f, 0f)
        for (scale in SPAWN_RINGS) {
            val d = scale * radiusM
            for ((c, s) in OCTANTS) {
                val dir = Vec2(first.u * c - first.v * s, first.u * s + first.v * c)
                val p = near + dir * d
                if (Physics.groundHeight(level, p, 0f, params) == null) continue
                if (level.objects.any { Physics.blocks(it, 0f, params) && it.footprint.contains(p) }) continue
                if (cast.members.any { p.distanceTo(it.body.state.pos) < radiusM + it.body.state.radiusM }) continue
                return p
            }
        }
        return level.spawn
    }

    /** True when a member with feet at [feetH] may stand at [p]: not over a cliff, not inside a blocking object. */
    private fun isFree(level: Level, p: Vec2, feetH: Float, params: PhysicsParams): Boolean =
        !Physics.isOverCliff(level, p) &&
            level.objects.none { Physics.blocks(it, feetH, params) && it.footprint.contains(p) }

    private val SPAWN_RINGS = floatArrayOf(2.5f, 5f)

    /** (cos, sin) of k * 45°, k = 0..7; exact on the axes. */
    private val OCTANTS: List<Pair<Float, Float>> = run {
        val s = sqrt(0.5f)
        listOf(1f to 0f, s to s, 0f to 1f, -s to s, -1f to 0f, -s to -s, 0f to -1f, s to -s)
    }
}

object CastEvents {
    /**
     * Per member, in [next]'s order: [CharacterEvents.between] (prevBody, nextBody) (the PhysicsBody overload, so
     * BLOCKED is included) for every id present in both casts. A member only in [next] yields nothing (the host
     * announces PLACED itself); one only in [prev] yields nothing.
     */
    fun between(prev: Cast, next: Cast): List<Pair<String, CharacterEvent>> {
        val out = ArrayList<Pair<String, CharacterEvent>>()
        for (m in next.members) {
            val before = prev[m.id] ?: continue
            for (e in CharacterEvents.between(before, m.body)) out += m.id to e
        }
        return out
    }
}
