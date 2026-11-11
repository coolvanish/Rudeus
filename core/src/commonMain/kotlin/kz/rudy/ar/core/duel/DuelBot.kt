package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.abs
import kotlin.math.sqrt

/** How well a [DuelBot] plays ([BotSkill]): EASY loses to a child who taps and dashes, HARD seems to think. */
@Serializable
enum class BotLevel { EASY, NORMAL, HARD }

/**
 * Everything a bot remembers between decisions. Immutable and serializable: a saved match resumes with the same bot.
 *
 * @property rng the state of the bot's own random generator (SplitMix64: the same sequence on every platform, and a
 *   plain number in JSON).
 * @property nextThinkMs match time of its next decision; until then it answers [DuelInput.None] and its last orders
 *   (a walk) stand. This is its reaction time.
 * @property wander EASY: the spot it is wandering to until [wanderUntilMs]; null = not wandering.
 * @property push the direction it settled on pushing the opponent (a unit vector), kept so it does not dither; null
 *   before its first decision.
 */
@Serializable
data class BotMind(
    val level: BotLevel,
    val rng: Long,
    val nextThinkMs: Long = 0L,
    val wander: Vec2? = null,
    val wanderUntilMs: Long = 0L,
    val push: Vec2? = null,
)

/**
 * The duel's bot: reads a [DuelMatchState] (state only, never events: they can be lost on the first frame after a
 * tracking gap) and answers one [DuelInput] for its fighter. Pure: the same arguments give the same answer on every
 * platform (no clock, no `kotlin.random`, no trigonometry; its randomness is the generator in [BotMind]). Call it
 * through [DuelRunner], which asks it only on the [BotTuning.tickMs] grid of match time, so a match does not depend on
 * the frame rate. Every number is in [BotTuning] / [BotSkill].
 *
 * **The plan every level shares.** Push the opponent toward the edge nearest to it: stand so that the opponent is
 * between the bot and that edge ([BotTuning.standoffGapRadii] off it), walking round the opponent and round whatever
 * blocks (and every hole) rather than into them; dash when in range ([BotSkill.dashRangeShare] of a dash's reach) and
 * aligned ([BotSkill.alignCos]); keep away from the edge itself (every walk target is [BotTuning.edgeMarginRadii]
 * inside). Outside FIGHT it answers None.
 *
 * **It never gives the game away.** A [DuelInput.MoveTo] target is always inside the boundary by the margin (the
 * boundary is the convex hull a scan builds, so the walk there stays on the table) and off every hole; a dash or a ram
 * is started only when it would end [BotTuning.dashEndMarginRadii] inside, or when the opponent is in its path first;
 * no input carries a non-finite number.
 *
 * **Levels** ([BotSkill]): EASY is slow to react, wanders, dashes when close whatever the alignment, sometimes the wrong
 * way, never uses the special. NORMAL plays the plan and uses its special in the obvious situation: stomp with the
 * opponent in reach, ram when aligned with the opponent near an edge and room behind itself, lift-off or dodge when a
 * dash or a stomp is coming at it near an edge. HARD is NORMAL deciding at every tick, leading its aim, weighing every
 * push direction (how far the opponent is from the edge that way, the walk there, and the high ground of a PLATFORM),
 * getting out of the way of an incoming dash, countering after a dodge, and never ramming toward an edge it would roll
 * off.
 */
object DuelBot {

    /** A fresh mind for [level]; [seed] starts its generator (two bots in one match need two seeds). */
    fun mind(level: BotLevel, seed: Long): BotMind = BotMind(level, rng = seed)

    /**
     * What fighter [me] wants at `state.duel.timeMs`, and the mind to ask next time. Pure: the same arguments give the
     * same answer on every platform. [me] not in the match, a level without a boundary, or a phase other than FIGHT:
     * [DuelInput.None] and the mind unchanged. Pass the [params] and [physics] the match runs with.
     */
    fun decide(
        mind: BotMind,
        me: String,
        state: DuelMatchState,
        arena: Level,
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
        tuning: BotTuning = BotTuning(),
    ): Pair<BotMind, DuelInput> {
        if (state.phase != MatchPhase.FIGHT) return mind to DuelInput.None
        val duel = state.duel
        val self = duel[me] ?: return mind to DuelInput.None
        val foe = if (duel.a.id == me) duel.b else duel.a
        val boundary = arena.boundary ?: return mind to DuelInput.None
        val now = duel.timeMs
        if (now < mind.nextThinkMs) return mind to DuelInput.None
        val skill = tuning.skill(mind.level)
        val rng = Rng(mind.rng)
        val wait = skill.thinkMs + tuning.tickMs * rng.int(skill.thinkJitterTicks + 1)
        val brain = Brain(self, foe, arena, boundary, params, physics, tuning, skill, rng, mind, now)
        val input = brain.checked(brain.think())
        val next = mind.copy(
            rng = rng.state,
            nextThinkMs = now + maxOf(wait, 1L),
            wander = brain.wander,
            wanderUntilMs = brain.wanderUntilMs,
            push = brain.push ?: mind.push,
        )
        return next to input
    }
}

/** SplitMix64 (Steele, Lea, Flood 2014): a few lines, wraps the same way on every platform. Local scratch only. */
private class Rng(var state: Long) {
    fun next(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1), 24 bits. */
    fun float(): Float = (next() ushr 40).toFloat() / 16_777_216f

    /** Uniform in [-1, 1). */
    fun signed(): Float = float() * 2f - 1f

    /** True with probability [p]; draws nothing when [p] is 0. */
    fun chance(p: Float): Boolean = p > 0f && float() < p

    /** Uniform in 0 until [n]; draws nothing when [n] <= 1. */
    fun int(n: Int): Int = if (n <= 1) 0 else ((next() ushr 33) % n).toInt()

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        const val MIX1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        const val MIX2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
    }
}

/** A circle the bot keeps out of: a blocking object or a hole, as its bounding circle grown by what it needs. */
private class Keep(val c: Vec2, val r: Float, val hole: Boolean, val bare: Float)

/** The boundary as edge lines with inward normals (exact inside distances for the convex hull a scan builds). */
private class Board(val poly: Polygon) {
    val n = poly.size
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private val nx = FloatArray(n)
    private val ny = FloatArray(n)
    private val used = BooleanArray(n)

    init {
        val ccw = poly.signedArea() >= 0f
        val pts = poly.points
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val eu = b.u - a.u
            val ev = b.v - a.v
            val len = sqrt(eu * eu + ev * ev)
            ax[i] = a.u
            ay[i] = a.v
            if (len > Vec2.EPS) {
                used[i] = true
                nx[i] = if (ccw) -ev / len else ev / len
                ny[i] = if (ccw) eu / len else -eu / len
            }
        }
    }

    private fun line(i: Int, u: Float, v: Float): Float = (u - ax[i]) * nx[i] + (v - ay[i]) * ny[i]

    /** The edge whose line is nearest inside (the one [p] would cross first going straight out). */
    fun nearest(p: Vec2): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            if (!used[i]) continue
            val d = line(i, p.u, p.v)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Distance inside the boundary (negative outside), by the edge lines. */
    fun inside(p: Vec2): Float {
        val i = nearest(p)
        return if (i < 0) 0f else line(i, p.u, p.v)
    }

    /** The outward normal of edge [i]: the way that edge's side of the table ends. */
    fun outward(i: Int): Vec2 = Vec2(-nx[i], -ny[i])

    /** How far [p] can go along unit [d] before it leaves the board. */
    fun exit(p: Vec2, d: Vec2): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until n) {
            if (!used[i]) continue
            val rate = d.u * nx[i] + d.v * ny[i]
            if (rate >= 0f) continue
            val t = line(i, p.u, p.v) / -rate
            if (t < best) best = t
        }
        return if (best == Float.MAX_VALUE) 0f else best.coerceAtLeast(0f)
    }

    /** [p] moved straight in until it is at least [margin] inside every edge line (a few passes for the corners). */
    fun clamp(p: Vec2, margin: Float): Vec2 {
        var u = p.u
        var v = p.v
        repeat(4) {
            var moved = false
            for (i in 0 until n) {
                if (!used[i]) continue
                val d = line(i, u, v)
                if (d < margin) {
                    u += nx[i] * (margin - d)
                    v += ny[i] * (margin - d)
                    moved = true
                }
            }
            if (!moved) return Vec2(u, v)
        }
        return Vec2(u, v)
    }
}

/** One decision: the context and the plan of [DuelBot]. Local scratch, never visible outside [DuelBot.decide]. */
private class Brain(
    val self: DuelFighter,
    val foe: DuelFighter,
    val arena: Level,
    val boundary: Polygon,
    val params: DuelParams,
    val physics: PhysicsParams,
    val t: BotTuning,
    val skill: BotSkill,
    val rng: Rng,
    mind: BotMind,
    val now: Long,
) {
    var wander: Vec2? = mind.wander
    var wanderUntilMs: Long = mind.wanderUntilMs
    var push: Vec2? = null
    private val lastPush: Vec2? = mind.push

    val me: Vec2 = self.body.state.pos
    val them: Vec2 = foe.body.state.pos
    val r: Float = self.body.state.radiusM
    private val contact = r + foe.body.state.radiusM
    private val board = Board(boundary)
    private val dashReach = params.dashSpeedMps * params.dashMs / 1000f
    private val ramReach = params.dashSpeedMps * params.ramDashMs / 1000f
    private val toFoe = them - me
    private val dist = toFoe.length
    private val gap = dist - contact
    private val foeHittable = !DuelPhysics.isImmune(foe) && !DuelPhysics.isOut(foe, arena) && foe.body.state.mode != Mode.PAUSED

    /** Walk targets keep this far inside (less on a table too small for it). */
    private val margin: Float = minOf(t.edgeMarginRadii * r, 0.5f * board.inside(boundary.centroid()).coerceAtLeast(0f))

    private val keeps: List<Keep> = arena.objects.mapNotNull { o ->
        val hole = o.kind == ObjectKind.CLIFF
        if (!hole && !Physics.blocks(o, self.body.state.h, physics)) return@mapNotNull null
        val c = o.footprint.centroid()
        var bare = 0f
        for (p in o.footprint.points) bare = maxOf(bare, p.distanceTo(c))
        Keep(c, bare + if (hole) margin else r + t.clearanceRadii * r, hole, bare)
    }

    fun think(): DuelInput {
        if (!DuelPhysics.acceptsInput(self, arena, params) || DuelPhysics.isOut(foe, arena)) return DuelInput.None
        val canDash = DuelPhysics.canDash(self, arena, params)
        val canSpecial = skill.usesSpecial && DuelPhysics.canSpecial(self, arena, params)
        val ability = self.ability

        // 1. Something coming at it.
        incoming()?.let { threat ->
            val (dir, stomp) = threat
            val nearEdge = board.inside(me) <= skill.nearEdgeRadii * r
            if (canSpecial && (ability == Ability.FLY || ability == Ability.HIDE)) {
                if (nearEdge || (skill.sidesteps && ability == Ability.HIDE)) return DuelInput.Special
            }
            // Away from the edge a hit costs a few centimetres and leaves the attacker on cooldown: take it.
            if (skill.sidesteps && nearEdge) evade(dir, stomp, canDash)?.let { return it }
        }

        // 2. A counter while the window is open.
        if (skill.counters && self.counterMs > 0L && canDash && foeHittable && gap <= dashReach && clearLine(me, them)) {
            val dir = aim()
            if (dashSafe(dir, dashReach)) return DuelInput.Dash(dir)
        }

        val push = choosePush()
        this.push = push

        // 3. The dash.
        if (canDash && foeHittable && gap <= dashReach * skill.dashRangeShare && clearLine(me, them)) {
            var dir = aim()
            if (skill.alignCos <= -1f || dir.dot(push) >= skill.alignCos) {
                if (skill.aimErrorShare > 0f) dir = (dir + dir.perp() * (rng.signed() * skill.aimErrorShare)).normalized()
                if (rng.chance(skill.wrongWayChance)) dir = randomDirection()
                if (dir != Vec2.ZERO && dashSafe(dir, dashReach)) return DuelInput.Dash(dir)
            }
        }

        // 4. The special in its obvious situation.
        if (canSpecial && foeHittable) {
            when (ability) {
                Ability.JUMP -> {
                    val reach = params.stompRadiusRadii * r * t.stompReachShare
                    if (dist <= reach && (dist < Vec2.EPS || (toFoe / dist).dot(push) >= 0f)) return DuelInput.Special
                }
                Ability.ROLL -> if (ramReady(push)) return DuelInput.Special
                else -> Unit
            }
        }

        // 5. Walk: to the spot behind the opponent, or wherever it wanders.
        val goal = wanderGoal() ?: (them - push * (contact + t.standoffGapRadii * r))
        return DuelInput.MoveTo(route(goal))
    }

    /** [input] as sent: finite, a walk target inside by the margin, a unit dash direction; anything else is None. */
    fun checked(input: DuelInput): DuelInput = when (input) {
        is DuelInput.MoveTo -> {
            val p = input.target
            if (finite(p) && boundary.contains(p) && board.inside(p) >= margin - 1e-5f) input else DuelInput.None
        }
        is DuelInput.Dash -> {
            val d = input.dir
            if (d != null && finite(d) && abs(d.length - 1f) < 1e-3f) input else DuelInput.None
        }
        else -> input
    }

    // -- reading the opponent ------------------------------------------------------------------------------------

    /** The direction a hit coming at the bot would push it, and whether it is a stomp; null when nothing comes. */
    private fun incoming(): Pair<Vec2, Boolean>? {
        if (DuelPhysics.isImmune(self)) return null
        val slack = t.threatSlackRadii * r
        if (foe.dashMs > 0L) {
            val d = foe.dashDir
            val rel = me - them
            val along = rel.dot(d)
            val lateral = abs(rel.dot(d.perp()))
            val left = params.dashSpeedMps * foe.dashMs / 1000f
            if (along > 0f && lateral < contact + slack && along - contact <= left + slack) return d to false
        }
        if (foe.stomping && dist <= params.stompRadiusRadii * foe.body.state.radiusM + slack) {
            val away = if (dist > Vec2.EPS) (me - them) / dist else Vec2(1f, 0f)
            return away to true
        }
        return null
    }

    /** Unit aim at the opponent; a bot that leads aims where a walking opponent will be when the dash meets it. */
    private fun aim(): Vec2 {
        var at = them
        if (skill.leadsAim && foe.dashMs == 0L) {
            val s = foe.body.state
            val target = s.target
            var v = foe.knock * 0.5f
            if (target != null) {
                val d = target - s.pos
                val len = d.length
                if (len > Vec2.EPS) {
                    var speed = params.walkSpeedMpsFor(foe.stats, physics)
                    if (DuelPhysics.isLifted(foe)) speed *= params.liftSpeedScale
                    else if (s.mode == Mode.JUMPING) speed *= physics.jumpSpeedScale
                    v += d / len * minOf(speed, len * 1000f / maxOf(1f, params.dashMs.toFloat()))
                }
            }
            val ahead = gap.coerceAtLeast(0f) / maxOf(params.dashSpeedMps, 1e-3f) * t.leadShare
            at = them + v * ahead
        }
        val d = at - me
        return if (d.length > Vec2.EPS) d.normalized() else (if (dist > Vec2.EPS) toFoe / dist else self.body.state.facing.normalized())
    }

    // -- where to push -------------------------------------------------------------------------------------------

    /**
     * The direction to push the opponent: toward its nearest edge; a bot that weighs ([BotSkill.weighsPush]) picks the
     * best of that direction, the ones [TURNS] off it and the one it settled on before (a wider choice makes a bot
     * that decides every tick dither round the opponent, and lose).
     */
    private fun choosePush(): Vec2 {
        val edge = board.nearest(them)
        val nearest = if (edge < 0) Vec2(1f, 0f) else board.outward(edge)
        if (!skill.weighsPush) return nearest
        val standoff = contact + t.standoffGapRadii * r
        val ring = contact + t.clearanceRadii * r
        val bearing = if (dist > Vec2.EPS) (me - them) / dist else Vec2.ZERO
        var best = nearest
        var bestCost = Float.MAX_VALUE
        for (k in -1 until TURN_COS.size) {
            val d = if (k < 0) {
                lastPush?.takeIf { it.dot(nearest) >= TURN_COS.last() } ?: continue
            } else {
                Vec2(nearest.u * TURN_COS[k] - nearest.v * TURN_SIN[k], nearest.u * TURN_SIN[k] + nearest.v * TURN_COS[k])
            }
            val spot = them - d * standoff
            if (board.inside(spot) < margin || keeps.any { k -> spot.distanceTo(k.c) < k.r }) continue
            var exit = board.exit(them, d)
            // Something in the way stops the push before the edge (a wall slam, not a ring-out).
            val end = them + d * exit
            if (keeps.any { k -> !k.hole && near(them, end, k.c, k.bare + contact * 0.5f) }) exit += BLOCKED_PUSH_RADII * r
            val walk = spot.distanceTo(me) + (1f - bearing.dot(-d)) * ring
            var cost = exit + t.walkCost * walk
            val ground = Physics.groundHeight(arena, spot, self.body.state.h, physics)
            if (ground != null && ground - foe.body.state.h > params.fromAboveMinM) cost -= t.highGroundBonusRadii * r
            val settled = lastPush
            if (settled != null && d.dot(settled) < SAME_DIRECTION_COS) cost += t.switchCostRadii * r
            if (cost < bestCost) {
                bestCost = cost
                best = d
            }
        }
        return best
    }

    // -- the special ----------------------------------------------------------------------------------------------

    /** ROLL: aligned along its facing with the opponent near an edge ahead, and room behind itself. */
    private fun ramReady(push: Vec2): Boolean {
        val facing = self.body.state.facing.normalized()
        if (facing == Vec2.ZERO || dist < Vec2.EPS) return false
        val ahead = toFoe / dist
        if (facing.dot(ahead) < skill.alignCos || facing.dot(push) < skill.alignCos) return false
        if (board.inside(them) > t.ramNearEdgeRadii * r || board.inside(me) < t.ramRoomRadii * r) return false
        if (gap > ramReach * skill.dashRangeShare || abs(toFoe.dot(facing.perp())) > t.inPathLateralRadii * r) return false
        if (!clearLine(me, them)) return false
        val end = me + facing * ramReach
        val endSafe = endsOnTable(end)
        return if (skill.safeRamsOnly) endSafe else endSafe || inPath(facing, ramReach)
    }

    // -- getting out of the way -----------------------------------------------------------------------------------

    /** HARD: a dash across an incoming dash (away from a stomp), else a step aside; null when there is no safe way. */
    private fun evade(dir: Vec2, stomp: Boolean, canDash: Boolean): DuelInput? {
        val rel = me - them
        val side = if (rel.dot(dir.perp()) >= 0f) 1f else -1f
        val across = dir.perp() * side
        val ways = if (stomp) {
            listOf(dir, (dir + across).normalized(), (dir - across).normalized())
        } else {
            listOf(across, -across, (across + dir * -0.5f).normalized(), (-across + dir * -0.5f).normalized())
        }
        if (canDash) {
            for (w in ways) {
                if (w == Vec2.ZERO) continue
                // Not into the attacker, and never off the table (no "opponent in the path" excuse here).
                if (w.dot(toFoe) > 0f && abs(toFoe.dot(w.perp())) < contact) continue
                if (endsOnTable(me + w * dashReach) && clearLine(me, me + w * dashReach)) return DuelInput.Dash(w)
            }
        }
        for (w in ways.take(2)) {
            val spot = safe(me + w * (t.sidestepRadii * r))
            if (spot.distanceTo(me) >= r) return DuelInput.MoveTo(spot)
        }
        return null
    }

    // -- safety ---------------------------------------------------------------------------------------------------

    /** A dash along unit [dir] of [reach] is safe: it ends on the table, or the opponent is in its path first. */
    private fun dashSafe(dir: Vec2, reach: Float): Boolean =
        (endsOnTable(me + dir * reach) && clearOfHoles(me, me + dir * reach)) || inPath(dir, reach)

    private fun endsOnTable(end: Vec2): Boolean =
        finite(end) && boundary.contains(end) && board.inside(end) >= t.dashEndMarginRadii * r && keeps.none { it.hole && end.distanceTo(it.c) < it.bare + r }

    private fun clearOfHoles(from: Vec2, to: Vec2): Boolean = keeps.none { it.hole && near(from, to, it.c, it.bare) }

    /** The opponent can be hit and a dash along unit [dir] meets it early: within [BotTuning.inPathReachShare] of [reach]. */
    private fun inPath(dir: Vec2, reach: Float): Boolean {
        if (!foeHittable) return false
        val along = toFoe.dot(dir)
        val lateral = abs(toFoe.dot(dir.perp()))
        if (along <= 0f || lateral > t.inPathLateralRadii * r || lateral >= contact) return false
        val meet = along - sqrt(contact * contact - lateral * lateral)
        return meet <= reach * t.inPathReachShare
    }

    /** Nothing that blocks (at the bot's feet) stands between [from] and [to]. */
    private fun clearLine(from: Vec2, to: Vec2): Boolean = keeps.none { !it.hole && near(from, to, it.c, it.bare + r * 0.8f) }

    // -- walking --------------------------------------------------------------------------------------------------

    /** EASY: the spot it wanders to, picking a new one now and then; null = no wandering now. */
    private fun wanderGoal(): Vec2? {
        val w = wander
        if (w != null && now < wanderUntilMs) return w
        wander = null
        if (!rng.chance(skill.wanderChance)) return null
        val box = bounds()
        var spot: Vec2? = null
        for (i in 0 until 6) {
            val p = Vec2(box[0] + (box[1] - box[0]) * rng.float(), box[2] + (box[3] - box[2]) * rng.float())
            if (board.inside(p) >= margin && keeps.none { p.distanceTo(it.c) < it.r }) {
                spot = p
                break
            }
        }
        val goal = spot ?: safe(boundary.centroid())
        wander = goal
        wanderUntilMs = now + skill.wanderMs
        return goal
    }

    private fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in boundary.points) {
            b[0] = minOf(b[0], p.u); b[1] = maxOf(b[1], p.u)
            b[2] = minOf(b[2], p.v); b[3] = maxOf(b[3], p.v)
        }
        return b
    }

    /** The next walk target toward [goal]: round the opponent and round what blocks, always a [safe] spot. */
    private fun route(goal: Vec2): Vec2 {
        var target = safe(goal)
        val ring = contact + t.clearanceRadii * r
        if (target.distanceTo(them) >= ring * 0.9f && near(me, target, them, ring * 0.9f)) target = around(them, ring, target)
        for (k in keeps) {
            if (near(me, target, k.c, k.r * 0.95f)) {
                target = around(k.c, k.r, target)
                break
            }
        }
        return safe(target)
    }

    /** A waypoint going round the circle ([c], [rho]) from the bot toward [goal], on the side that is the shorter way and safe. */
    private fun around(c: Vec2, rho: Float, goal: Vec2): Vec2 {
        val rel = me - c
        val len = rel.length
        val un = if (len > Vec2.EPS) rel / len else (goal - c).normalized().perp().takeIf { it != Vec2.ZERO } ?: Vec2(1f, 0f)
        val preferred = if (un.cross(goal - c) >= 0f) 1f else -1f
        var fallback: Vec2? = null
        for (side in floatArrayOf(preferred, -preferred)) {
            val w = if (len > rho * 1.2f) {
                // Outside: head for the tangent point, and a little past it along the tangent.
                val cosA = rho / len
                val sinA = sqrt((1f - cosA * cosA).coerceAtLeast(0f))
                val dirT = un * cosA + un.perp() * (side * sinA)
                val touch = c + dirT * rho
                touch + (touch - me).normalized() * (rho * 0.5f)
            } else {
                // On the ring: one step round it.
                val s = t.detourStepSin * side
                val turned = Vec2(un.u * t.detourStepCos - un.v * s, un.u * s + un.v * t.detourStepCos)
                c + turned * (rho * 1.1f)
            }
            if (board.inside(w) >= margin && keeps.none { k -> k.c != c && w.distanceTo(k.c) < k.r }) return w
            if (fallback == null) fallback = w
        }
        return fallback ?: goal
    }

    /** [p] made a fine walk target: [margin] inside the boundary, out of every circle the bot keeps out of. */
    fun safe(p: Vec2): Vec2 {
        var q = if (finite(p)) p else boundary.centroid()
        repeat(3) {
            q = board.clamp(q, margin)
            for (k in keeps) {
                val d = q - k.c
                val len = d.length
                if (len < k.r) q = k.c + (if (len > Vec2.EPS) d / len else Vec2(1f, 0f)) * k.r
            }
        }
        q = board.clamp(q, margin)
        if (boundary.contains(q) && board.inside(q) >= margin - 1e-5f) return q
        // Not convex after all, or no room: halfway to the middle until it holds.
        val middle = boundary.centroid()
        repeat(8) {
            q = (q + middle) * 0.5f
            if (boundary.contains(q) && board.inside(q) >= margin - 1e-5f) return q
        }
        return middle
    }

    private fun randomDirection(): Vec2 {
        repeat(4) {
            val d = Vec2(rng.signed(), rng.signed())
            val len = d.length
            if (len > 0.1f && len <= 1f) return d / len
        }
        return Vec2(1f, 0f)
    }

    private companion object {
        /** The turns off the nearest-edge push a weighing bot considers: 0, +-22.5°, +-45°. */
        val TURN_COS = floatArrayOf(1f, C22, C22, C45, C45)
        val TURN_SIN = floatArrayOf(0f, S22, -S22, C45, -C45)

        /** Two push directions closer than this (cosine, about 11°) are the same one. */
        const val SAME_DIRECTION_COS = 0.98f

        /** A push that something stops before the edge counts as this many radii longer. */
        const val BLOCKED_PUSH_RADII = 10f
    }
}

private const val C22 = 0.9238795f
private const val S22 = 0.38268343f
private const val C45 = 0.70710677f

private fun finite(p: Vec2) = p.u.isFinite() && p.v.isFinite()

/** The segment [a]-[b] passes closer than [rho] to [c]. */
private fun near(a: Vec2, b: Vec2, c: Vec2, rho: Float): Boolean {
    val ab = b - a
    val len2 = ab.lengthSquared
    val s = if (len2 < Vec2.EPS * Vec2.EPS) 0f else ((c - a).dot(ab) / len2).coerceIn(0f, 1f)
    val q = a + ab * s
    return (c - q).lengthSquared < rho * rho
}
