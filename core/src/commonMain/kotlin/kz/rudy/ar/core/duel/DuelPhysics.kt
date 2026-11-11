package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.physics.circlePolygonPushOut
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Two fighters on a scanned [Level] (ai/build-plan-v2.md, "The game"): walk, dash into each other, get knocked back by
 * their [kz.rudy.ar.core.domain.FighterStats], slam into a cup, fall off the table. Built on the walking game's
 * public API ([Physics], [circlePolygonPushOut]); the walking game itself is untouched.
 * Pure and deterministic: no clocks, no randomness, nothing mutated; every number is in [DuelParams]. Time is simulated
 * only: [DuelState.timeMs] moves by exactly the `dtMs` given.
 *
 * **Time.** [step] runs fixed sub-steps of [DuelParams.subStepMs] on a grid of [DuelState.timeMs]: a call from `t0`
 * to `t0 + dtMs` runs every whole grid cell that ends in `(t0, t0 + dtMs]`. Every split of the same time into frames
 * (one 33 ms call, 16 + 17 ms, 33 x 1 ms) therefore runs the very same sub-steps and lands on the very same state; the
 * bodies trail [DuelState.timeMs] by less than one sub-step. Inputs apply at the start of their call.
 *
 * **One sub-step**, in this order:
 * 1. Timers: dash cooldown and dash time count down; the stun timer counts down only while `|knock| <=
 *    knockStunMps`. A PAUSED fighter is frozen whole (timers, knockback, body) and takes no part in anything below.
 * 2. Duel motion: the dash (`dashSpeedMps` along `dashDir`) and the knockback (exact exponential decay over the
 *    sub-step) move the body through the obstacle rules walking uses: push-out from every object that [Physics.blocks]
 *    at the feet height, and an obstacle never pushes anyone over a cliff. A knockback pressed into a blocking object at
 *    `>= wallSlamMinMps` (its component into the surface) is a wall slam: knockback zeroed, `wallSlamStunMs` added,
 *    [DuelEvent.WallSlam]; slower contact slides (the into-surface part of the knockback is dropped). A fighter that is
 *    out ([isOut]) gets no duel motion; its knockback and dash are dropped.
 * 3. [Physics.step] with the fighter's walk speed ([DuelParams.walkSpeedMpsFor]): walking, jumping, step-up onto
 *    platforms, falling, respawning, exactly as the walking game does it, with two duel rules on top: an out fighter
 *    stays where it left the level until Physics respawns it, and a body that came down this sub-step is pushed out of
 *    what blocks at its new feet height at once (Physics would do it one sub-step later).
 * 4. Hits: circles overlapping in the plane (height ignored, so an airborne fighter is hit too) while one of them
 *    dashed in this sub-step. The victim gets [DuelParams.knockSpeedMpsFor] along the attacker's `dashDir`
 *    (x `fromAboveScale` when the attacker's feet are more than `fromAboveMinM` higher), its walk target is dropped and
 *    its stun timer set to at least [DuelParams.hitStunMsFor]; the attacker's dash ends, so a dash hits at most once.
 *    Both dashing: [DuelEvent.Clash], each takes the other's knockback and both dashes end. Neither PAUSED nor out
 *    fighters hit or are hit.
 * 5. Separation of two grounded (IDLE / WALKING) fighters that overlap, see [separate].
 * 6. Ring-out: a fighter that was not [isOut] at the start of the sub-step and is now gets [DuelEvent.RingOut] and
 *    loses its walk target. It stays out (and ignores input) until Physics respawns it, so one excursion is one
 *    ring-out. What follows the respawn is the caller's (T45).
 *
 * **Events** carry their simulated time ([DuelEvent.atMs]) and come in canonical order (time, kind, id), so neither the
 * frame rate nor the slot a fighter sits in changes the list.
 *
 * **Input** ([acceptsInput]): stunned, dashing, PAUSED or out fighters ignore it, and so does every fighter a non-finite
 * target or direction. `MoveTo` is [Physics.walkTo], `Jump` is [Physics.jump], `Dash` starts a dash
 * ([DuelEvent.DashStarted]) unless it is on cooldown or the fighter is not on the ground (IDLE / WALKING); it drops the
 * walk target and turns `facing` to the dash direction (a null or zero direction = along `facing`). `Special` is
 * accepted and does nothing (T45).
 */
object DuelPhysics {

    /**
     * Advances [state] by [dtMs] ms of duel on [level]: [inputs] (by fighter id; unknown ids ignored) apply first, then
     * the sub-steps described on [DuelPhysics]. `dtMs <= 0` returns [state] itself and no events (inputs ignored).
     * `dtMs` is not bounded here: a call runs `dtMs / subStepMs` sub-steps whatever their number, so the caller clamps
     * long gaps.
     */
    fun step(
        state: DuelState,
        level: Level,
        dtMs: Long,
        inputs: Map<String, DuelInput> = emptyMap(),
        params: DuelParams = DuelParams.DEFAULT,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): DuelStep {
        if (dtMs <= 0L) return DuelStep(state, emptyList())
        val sub = params.subStepMs
        val firstCell = state.timeMs.floorDiv(sub)
        val end = state.timeMs + dtMs
        val cells = end.floorDiv(sub) - firstCell
        val events = ArrayList<DuelEvent>(2)
        val appliedAt = firstCell * sub
        val a = Work(applyInput(state.a, inputs[state.a.id], level, params, physics, appliedAt, events), params, physics)
        val b = Work(applyInput(state.b, inputs[state.b.id], level, params, physics, appliedAt, events), params, physics)
        if (cells > 0L) {
            val c = Consts(level, params, physics)
            for (i in 1L..cells) subStep(a, b, c, (firstCell + i) * sub, events)
        }
        if (events.size > 1) events.sortWith(CANONICAL)
        return DuelStep(DuelState(a.done(), b.done(), end), events)
    }

    /** Stunned: the stun timer is still running or the knockback is faster than [DuelParams.knockStunMps]. */
    fun isStunned(f: DuelFighter, params: DuelParams = DuelParams.DEFAULT): Boolean =
        f.stunMs > 0L || f.knock.length > params.knockStunMps

    /** [f] takes input: not PAUSED, not dashing, not stunned, not out ([isOut]). */
    fun acceptsInput(f: DuelFighter, level: Level, params: DuelParams = DuelParams.DEFAULT): Boolean =
        f.body.state.mode != Mode.PAUSED && f.dashMs <= 0L && !isStunned(f, params) && !isOut(f.body, level)

    /** A Dash input would start a dash now: [acceptsInput], off cooldown, on the ground (IDLE / WALKING). */
    fun canDash(f: DuelFighter, level: Level, params: DuelParams = DuelParams.DEFAULT): Boolean {
        val mode = f.body.state.mode
        return f.dashCooldownMs <= 0L && (mode == Mode.IDLE || mode == Mode.WALKING) && acceptsInput(f, level, params)
    }

    /**
     * Out of the level, the ring-out condition:
     * - the centre is past `level.boundary` (plain polygon containment), whatever the mode, ability or height: crossing
     *   the edge is the ring-out, also for a FLY body floating above the floor;
     * - or it is falling into a CLIFF hole inside the boundary: FALLING over it, or JUMPING over it with the feet below
     *   the table plane (a FLY body gliding across a hole above the plane is not out);
     * - or it is RESPAWNING.
     * A PAUSED body is judged by the mode it had before the pause. Lasts until Physics respawns the body.
     */
    fun isOut(f: DuelFighter, level: Level): Boolean = isOut(f.body, level)

    /**
     * The walking game's collision resolution of a move from [from] to [to] as the duel runs it (a copy of the private
     * `Physics.resolveCollisions`; a test pins the two together until a physics brief exposes the original).
     */
    internal fun resolveMove(
        level: Level,
        from: Vec2,
        to: Vec2,
        feetH: Float,
        radius: Float,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): Vec2 = moveThrough(level, physics, from, to, feetH, radius).pos

    // ---------------------------------------------------------------------------------------------------------------

    private fun isOut(body: PhysicsBody, level: Level): Boolean {
        val s = body.state
        val mode = if (s.mode == Mode.PAUSED) body.modeBeforePause ?: Mode.PAUSED else s.mode
        if (mode == Mode.RESPAWNING) return true
        val boundary = level.boundary
        if (boundary != null && !boundary.contains(s.pos)) return true
        return when (mode) {
            Mode.FALLING -> Physics.isOverCliff(level, s.pos)
            Mode.JUMPING -> s.h < 0f && Physics.isOverCliff(level, s.pos)
            else -> false
        }
    }

    private fun Vec2.isFinite() = u.isFinite() && v.isFinite()

    private fun applyInput(
        f: DuelFighter,
        input: DuelInput?,
        level: Level,
        params: DuelParams,
        physics: PhysicsParams,
        atMs: Long,
        events: MutableList<DuelEvent>,
    ): DuelFighter = when (input) {
        null, DuelInput.None, DuelInput.Special -> f
        is DuelInput.MoveTo ->
            if (input.target.isFinite() && acceptsInput(f, level, params)) f.copy(body = Physics.walkTo(f.body, input.target)) else f
        DuelInput.Jump -> if (acceptsInput(f, level, params)) f.copy(body = Physics.jump(f.body, physics)) else f
        is DuelInput.Dash ->
            if (input.dir?.isFinite() != false && canDash(f, level, params)) startDash(f, input.dir, params, atMs, events) else f
    }

    private fun startDash(f: DuelFighter, dir: Vec2?, params: DuelParams, atMs: Long, events: MutableList<DuelEvent>): DuelFighter {
        val facing = f.body.state.facing.normalized()
        val d = dir?.normalized()?.takeIf { it != Vec2.ZERO } ?: facing.takeIf { it != Vec2.ZERO } ?: return f
        events += DuelEvent.DashStarted(f.id, atMs)
        val body = stopWalking(f.body)
        return f.copy(
            body = body.copy(state = body.state.copy(facing = d)),
            dashMs = params.dashMs,
            dashDir = d,
            dashCooldownMs = params.cooldownMsFor(f.stats),
        )
    }

    /** Drops the walk target; a WALKING body stands (IDLE). Airborne bodies keep their mode. */
    private fun stopWalking(body: PhysicsBody): PhysicsBody {
        val s = body.state
        if (s.target == null && s.mode != Mode.WALKING) return body
        return body.copy(state = s.copy(target = null, mode = if (s.mode == Mode.WALKING) Mode.IDLE else s.mode), blocked = false)
    }

    /** Per-call constants. */
    private class Consts(val level: Level, val params: DuelParams, val physics: PhysicsParams) {
        val sub: Long = params.subStepMs

        /** exp(-sub / tau): the knockback velocity factor over one sub-step. */
        val decay: Float = exp(-sub.toDouble() / params.knockTauMs.toDouble()).toFloat()

        /** Integral of exp(-t / tau) over one sub-step, in seconds: displacement = knock * gain. */
        val gainS: Float = (params.knockTauMs / 1000.0 * (1.0 - exp(-sub.toDouble() / params.knockTauMs.toDouble()))).toFloat()
    }

    /** One fighter during a call: local, mutable scratch (never visible outside [step]) that becomes a [DuelFighter]. */
    private class Work(private val original: DuelFighter, params: DuelParams, physics: PhysicsParams) {
        val id = original.id
        val stats = original.stats
        val dashDir = original.dashDir
        var body = original.body
        var knock = original.knock
        var stunMs = original.stunMs
        var dashMs = original.dashMs
        var dashCooldownMs = original.dashCooldownMs

        /** The walking-game parameters with this fighter's duel walk speed. */
        val walkPhysics: PhysicsParams =
            physics.copy(walkSpeedMps = params.walkSpeedMpsFor(stats, physics), rollSpeedScale = params.rollSpeedScale)

        val paused: Boolean get() = body.state.mode == Mode.PAUSED

        fun moveTo(p: Vec2) {
            body = body.copy(state = body.state.copy(pos = p))
        }

        fun done(): DuelFighter =
            original.copy(body = body, knock = knock, stunMs = stunMs, dashMs = dashMs, dashCooldownMs = dashCooldownMs)
    }

    private fun subStep(a: Work, b: Work, c: Consts, atMs: Long, events: MutableList<DuelEvent>) {
        val wasOutA = isOut(a.body, c.level)
        val wasOutB = isOut(b.body, c.level)
        val dashA = advance(a, wasOutA, c, atMs, events)
        val dashB = advance(b, wasOutB, c, atMs, events)
        physicsStep(a, wasOutA, c)
        physicsStep(b, wasOutB, c)
        hits(a, b, dashA, dashB, c, atMs, events)
        separate(a, b, c)
        ringOut(a, wasOutA, c, atMs, events)
        ringOut(b, wasOutB, c, atMs, events)
    }

    /** Timers and duel motion of one sub-step (steps 1 and 2). Returns true when [w] dashed during it. */
    private fun advance(w: Work, wasOut: Boolean, c: Consts, atMs: Long, events: MutableList<DuelEvent>): Boolean {
        if (w.paused) return false
        val p = c.params
        w.dashCooldownMs = (w.dashCooldownMs - c.sub).coerceAtLeast(0L)
        if (w.knock.length <= p.knockStunMps) w.stunMs = (w.stunMs - c.sub).coerceAtLeast(0L)
        val dashActiveMs = minOf(w.dashMs, c.sub)
        w.dashMs = (w.dashMs - c.sub).coerceAtLeast(0L)
        if (wasOut) {
            w.knock = Vec2.ZERO
            w.dashMs = 0L
            return false
        }
        val knock0 = w.knock
        var disp = Vec2.ZERO
        if (dashActiveMs > 0L) disp += w.dashDir * (p.dashSpeedMps * dashActiveMs / 1000f)
        if (knock0 != Vec2.ZERO) {
            disp += knock0 * c.gainS
            val k = knock0 * c.decay
            w.knock = if (k.length < p.knockStopMps) Vec2.ZERO else k
        }
        if (disp == Vec2.ZERO) return dashActiveMs > 0L

        val s = w.body.state
        val start = s.pos + disp
        val moved = moveThrough(c.level, c.physics, s.pos, start, s.h, s.radiusM)
        w.moveTo(moved.pos)
        val obstacle = moved.obstacle
        if (knock0 != Vec2.ZERO && obstacle != null) {
            // What the obstacle took back, as a direction into its surface.
            val back = start - moved.pos
            val len = back.length
            if (len > Vec2.EPS) {
                val into = back / len
                val speedInto = knock0.dot(into)
                if (speedInto >= p.wallSlamMinMps) {
                    w.knock = Vec2.ZERO
                    w.stunMs += p.wallSlamStunMs
                    events += DuelEvent.WallSlam(w.id, obstacle.id, atMs)
                } else if (speedInto > 0f && w.knock != Vec2.ZERO) {
                    w.knock -= into * w.knock.dot(into)
                    if (w.knock.length < p.knockStopMps) w.knock = Vec2.ZERO
                }
            }
        }
        return dashActiveMs > 0L
    }

    /** Step 3. */
    private fun physicsStep(w: Work, wasOut: Boolean, c: Consts) {
        val before = w.body
        val after = Physics.step(before, c.level, c.sub, w.walkPhysics)
        val s0 = before.state
        val s = after.state
        w.body = when {
            // Out: Physics may still push a falling body sideways; the duel keeps it where it left until the respawn.
            wasOut -> if (s0.mode == Mode.RESPAWNING || s.pos == s0.pos) after else after.copy(state = s.copy(pos = s0.pos))
            // Coming down: Physics resolves collisions at the feet height before the vertical move, so a body sinking
            // past an object's top (or landing beside a low one) would overlap it for a sub-step. Resolve it now.
            (s0.mode == Mode.JUMPING || s0.mode == Mode.FALLING) && s.h < s0.h -> {
                val p = moveThrough(c.level, c.physics, s.pos, s.pos, s.h, s.radiusM).pos
                if (p == s.pos) after else after.copy(state = s.copy(pos = p))
            }
            else -> after
        }
    }

    private class Moved(val pos: Vec2, val obstacle: LevelObject?)

    /**
     * [start] (= `from + displacement`) pushed out of the objects that block at [feetH], with the walking game's cliff
     * rule (`Physics.resolveCollisions`, rebuilt on public API): when the push-out would cross a cliff the unpushed
     * [start] is not over, the fighter stays at [from] if it was clear, goes to [start] if [from] is inside an object,
     * and otherwise (overlapping one) goes to [start] only if that is no deeper. A displacement may cross a cliff by
     * itself: that is how a fighter is knocked off the table. [Moved.obstacle] is the object that pushed hardest.
     */
    private fun moveThrough(level: Level, physics: PhysicsParams, from: Vec2, start: Vec2, feetH: Float, radius: Float): Moved {
        val pushed = pushOut(level, physics, start, feetH, radius)
        if (!Physics.isOverCliff(level, pushed.pos) || Physics.isOverCliff(level, start)) return pushed
        val overlap = (pushOut(level, physics, from, feetH, radius).pos - from).length
        if (overlap == 0f) return Moved(from, pushed.obstacle)
        if (level.objects.any { Physics.blocks(it, feetH, physics) && it.footprint.contains(from) }) return Moved(start, null)
        return if ((pushed.pos - start).length <= overlap + Physics.H_EPS) Moved(start, null) else Moved(from, pushed.obstacle)
    }

    private fun pushOut(level: Level, physics: PhysicsParams, start: Vec2, feetH: Float, radius: Float): Moved {
        var pos = start
        var best: LevelObject? = null
        var bestLen2 = 0f
        repeat(physics.pushOutPasses) {
            var moved = false
            for (o in level.objects) {
                if (!Physics.blocks(o, feetH, physics)) continue
                val push = circlePolygonPushOut(pos, radius, o.footprint) ?: continue
                pos += push
                moved = true
                val l2 = push.lengthSquared
                if (l2 > bestLen2) {
                    bestLen2 = l2
                    best = o
                }
            }
            if (!moved) return Moved(pos, best)
        }
        return Moved(pos, best)
    }

    /** Step 4. */
    private fun hits(a: Work, b: Work, dashA: Boolean, dashB: Boolean, c: Consts, atMs: Long, events: MutableList<DuelEvent>) {
        if (!dashA && !dashB) return
        if (a.paused || b.paused || isOut(a.body, c.level) || isOut(b.body, c.level)) return
        val sa = a.body.state
        val sb = b.body.state
        if (sa.pos.distanceTo(sb.pos) >= sa.radiusM + sb.radiusM) return
        val p = c.params
        if (dashA && dashB) {
            val toA = p.knockSpeedMpsFor(b.stats, a.stats, fromAbove(sb.h, sa.h, p))
            val toB = p.knockSpeedMpsFor(a.stats, b.stats, fromAbove(sa.h, sb.h, p))
            val dirA = a.dashDir
            val dirB = b.dashDir
            knockBack(a, dirB * toA, p)
            knockBack(b, dirA * toB, p)
            a.dashMs = 0L
            b.dashMs = 0L
            events += if (a.id < b.id) DuelEvent.Clash(a.id, b.id, atMs) else DuelEvent.Clash(b.id, a.id, atMs)
        } else if (dashA) {
            hit(a, b, p, atMs, events)
        } else {
            hit(b, a, p, atMs, events)
        }
    }

    private fun hit(attacker: Work, victim: Work, p: DuelParams, atMs: Long, events: MutableList<DuelEvent>) {
        val above = fromAbove(attacker.body.state.h, victim.body.state.h, p)
        val speed = p.knockSpeedMpsFor(attacker.stats, victim.stats, above)
        knockBack(victim, attacker.dashDir * speed, p)
        attacker.dashMs = 0L
        events += DuelEvent.Hit(attacker.id, victim.id, speed, above, atMs)
    }

    private fun knockBack(victim: Work, knock: Vec2, p: DuelParams) {
        victim.knock = knock
        victim.stunMs = maxOf(victim.stunMs, p.hitStunMsFor(victim.stats))
        victim.body = stopWalking(victim.body)
    }

    private fun fromAbove(attackerH: Float, victimH: Float, p: DuelParams) = attackerH - victimH > p.fromAboveMinM

    /**
     * Step 5. Two grounded (IDLE / WALKING) fighters whose circles overlap end exactly touching. For each search
     * direction (the line between the centres first, then turning by 22.5 degrees, counter-clockwise before clockwise,
     * up to the opposite direction: 16 in all), the candidates are, in order: both move, symmetric about their midpoint;
     * else one goes round the other, which stays: first the one whose own half-move fitted, the smaller id first when
     * both or neither did. A candidate fits only if [canStand]: its whole circle clear of what blocks at its feet height,
     * its centre inside the boundary and off any CLIFF, and the straight path to it crossing no blocking or CLIFF
     * footprint. If none of the 48 fits, both stay overlapped (the only way two grounded fighters end a sub-step so).
     * Centres on one point (closer than [Vec2.EPS]): the line is the u axis, the smaller id on the -u side.
     * Only `pos` changes.
     */
    private fun separate(a: Work, b: Work, c: Consts) {
        val sa = a.body.state
        val sb = b.body.state
        if (!grounded(sa.mode) || !grounded(sb.mode)) return
        val reach = sa.radiusM + sb.radiusM
        val d = sb.pos - sa.pos
        val dist = d.length
        if (dist >= reach) return
        val dir = when {
            dist > Vec2.EPS -> d / dist
            a.id < b.id -> Vec2(1f, 0f)
            else -> Vec2(-1f, 0f)
        }
        val mid = (sa.pos + sb.pos) * 0.5f
        val half = reach * 0.5f
        for (k in TURN_COS.indices) {
            val cs = TURN_COS[k]
            val sn = TURN_SIN[k]
            val u = Vec2(dir.u * cs - dir.v * sn, dir.u * sn + dir.v * cs)
            val qa = mid - u * half
            val qb = mid + u * half
            val okA = canStand(c, sa, qa)
            val okB = canStand(c, sb, qb)
            if (okA && okB) {
                a.moveTo(qa)
                b.moveTo(qb)
                return
            }
            val aFirst = if (okA != okB) okA else a.id < b.id
            val aAlone = sb.pos - u * reach
            val bAlone = sa.pos + u * reach
            if (aFirst) {
                if (canStand(c, sa, aAlone)) return a.moveTo(aAlone)
                if (canStand(c, sb, bAlone)) return b.moveTo(bAlone)
            } else {
                if (canStand(c, sb, bAlone)) return b.moveTo(bAlone)
                if (canStand(c, sa, aAlone)) return a.moveTo(aAlone)
            }
        }
    }

    private fun grounded(mode: Mode) = mode == Mode.IDLE || mode == Mode.WALKING

    /** A fighter in state [s] may be moved to [q] by separation (see [separate]). */
    private fun canStand(c: Consts, s: CharacterState, q: Vec2): Boolean {
        if (!q.isFinite() || Physics.isOverCliff(c.level, q)) return false
        for (o in c.level.objects) {
            val fp = o.footprint
            if (o.kind == ObjectKind.CLIFF) {
                if (segmentTouches(s.pos, q, fp)) return false
                continue
            }
            if (!Physics.blocks(o, s.h, c.physics)) continue
            if (fp.contains(q) || segmentTouches(s.pos, q, fp) || circlePolygonPushOut(q, s.radiusM, fp) != null) return false
        }
        return true
    }

    /** The segment [p]-[q] touches or crosses an edge of [poly]. */
    private fun segmentTouches(p: Vec2, q: Vec2, poly: Polygon): Boolean {
        val pts = poly.points
        val n = pts.size
        for (i in 0 until n) if (segmentsTouch(p, q, pts[i], pts[(i + 1) % n])) return true
        return false
    }

    private fun segmentsTouch(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Boolean {
        val d1 = orient(p3, p4, p1)
        val d2 = orient(p3, p4, p2)
        val d3 = orient(p1, p2, p3)
        val d4 = orient(p1, p2, p4)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))) return true
        return (d1 == 0f && within(p3, p4, p1)) || (d2 == 0f && within(p3, p4, p2)) ||
            (d3 == 0f && within(p1, p2, p3)) || (d4 == 0f && within(p1, p2, p4))
    }

    private fun orient(a: Vec2, b: Vec2, c: Vec2): Float = (b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u)

    private fun within(a: Vec2, b: Vec2, p: Vec2): Boolean =
        p.u >= min(a.u, b.u) && p.u <= max(a.u, b.u) && p.v >= min(a.v, b.v) && p.v <= max(a.v, b.v)

    // Search directions for [separate]: 0, +22.5, -22.5, +45, -45, ... +157.5, -157.5, 180 degrees.
    private const val C22 = 0.9238795f
    private const val S22 = 0.38268343f
    private const val C45 = 0.70710677f
    private val TURN_COS = floatArrayOf(1f, C22, C22, C45, C45, S22, S22, 0f, 0f, -S22, -S22, -C45, -C45, -C22, -C22, -1f)
    private val TURN_SIN = floatArrayOf(0f, S22, -S22, C45, -C45, C22, -C22, 1f, -1f, C22, -C22, C45, -C45, S22, -S22, 0f)

    /** Step 6. The walk target goes too, so nothing steers an out body back. */
    private fun ringOut(w: Work, wasOut: Boolean, c: Consts, atMs: Long, events: MutableList<DuelEvent>) {
        if (wasOut || !isOut(w.body, c.level)) return
        events += DuelEvent.RingOut(w.id, atMs)
        w.knock = Vec2.ZERO
        w.dashMs = 0L
        w.body = stopWalking(w.body)
    }

    private fun kindRank(e: DuelEvent): Int = when (e) {
        is DuelEvent.DashStarted -> 0
        is DuelEvent.WallSlam -> 1
        is DuelEvent.Hit -> 2
        is DuelEvent.Clash -> 3
        is DuelEvent.RingOut -> 4
    }

    private fun firstId(e: DuelEvent): String = when (e) {
        is DuelEvent.DashStarted -> e.id
        is DuelEvent.WallSlam -> e.id
        is DuelEvent.Hit -> e.attacker
        is DuelEvent.Clash -> e.a
        is DuelEvent.RingOut -> e.id
    }

    private fun secondId(e: DuelEvent): String = when (e) {
        is DuelEvent.WallSlam -> e.objectId
        is DuelEvent.Hit -> e.victim
        is DuelEvent.Clash -> e.b
        else -> ""
    }

    /** Canonical event order: time, kind, then ids (stable, so nothing else matters). */
    private val CANONICAL: Comparator<DuelEvent> = compareBy({ it.atMs }, { kindRank(it) }, { firstId(it) }, { secondId(it) })
}
