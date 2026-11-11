package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Cast
import kz.rudy.ar.core.physics.CastMember
import kz.rudy.ar.core.physics.CastPhysics
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.physics.circlePolygonPushOut
import kotlin.math.exp

/**
 * Two fighters on a scanned [Level] (ai/build-plan-v2.md, "The game"): walk, dash into each other, get knocked back by
 * their [kz.rudy.ar.core.domain.FighterStats], slam into a cup, fall off the table. Built on the walking game's
 * public API ([Physics], [CastPhysics.separate], [circlePolygonPushOut]); the walking game itself is untouched.
 * Pure and deterministic: no clocks, no randomness, nothing mutated; every number is in [DuelParams].
 *
 * **Time.** [step] runs fixed sub-steps of [DuelParams.subStepMs] on a grid of [DuelState.timeMs]: a call from `t0`
 * to `t0 + dtMs` runs every whole grid cell that ends in `(t0, t0 + dtMs]`. Every split of the same time into frames
 * (one 33 ms call, 16 + 17 ms, 33 x 1 ms) therefore runs the very same sub-steps and lands on the very same state; the
 * bodies trail [DuelState.timeMs] by less than one sub-step. Inputs apply at the start of their call.
 *
 * **One sub-step**, in this order, fighter `a` then `b` where an order is needed:
 * 1. Timers: dash cooldown and dash time count down; the stun timer counts down only while `|knock| <=
 *    knockStunMps`. A PAUSED fighter is frozen whole (timers, knockback, body) and takes no part in anything below.
 * 2. Duel motion: the dash (`dashSpeedMps` along `dashDir`) and the knockback (exact exponential decay over the
 *    sub-step) move the body through the obstacle rules walking uses: push-out from every object that [Physics.blocks]
 *    at the feet height, and an obstacle never pushes anyone over a cliff. A knockback pressed into a blocking object at
 *    `>= wallSlamMinMps` (its component into the surface) is a wall slam: knockback zeroed, `wallSlamStunMs` added,
 *    [DuelEvent.WallSlam]; slower contact slides (the into-surface part of the knockback is dropped). A fighter that is
 *    out ([isOut]) gets no duel motion; its knockback and dash are dropped.
 * 3. [Physics.step] with the fighter's walk speed ([DuelParams.walkSpeedMpsFor]): walking, jumping, step-up onto
 *    platforms, falling, and the fall over a cliff exactly as the walking game does it.
 * 4. Hits: circles overlapping in the plane (height ignored, so an airborne fighter is hit too) while one of them
 *    dashed in this sub-step. The victim gets [DuelParams.knockSpeedMpsFor] along the attacker's `dashDir`
 *    (x `fromAboveScale` when the attacker's feet are more than `fromAboveMinM` higher), its walk target is dropped and
 *    its stun timer set to at least [DuelParams.hitStunMsFor]; the attacker's dash ends, so a dash hits at most once.
 *    Both dashing: [DuelEvent.Clash], each takes the other's knockback and both dashes end. Neither PAUSED nor out
 *    fighters hit or are hit.
 * 5. Separation: [CastPhysics.separate] on the two bodies (grounded fighters only; nobody pushed into an obstacle or
 *    over a cliff by separation alone).
 * 6. Ring-out: a fighter that was not [isOut] at the start of the sub-step and is now gets [DuelEvent.RingOut] and
 *    loses its walk target. Being out lasts through the fall until Physics respawns the body (out fighters ignore
 *    input, so nobody steers back), so one fall is one ring-out. What follows the respawn is the caller's (T45).
 *
 * **Input** ([acceptsInput]): stunned, dashing, PAUSED or out fighters ignore it. `MoveTo` is [Physics.walkTo], `Jump` is
 * [Physics.jump], `Dash` starts a dash ([DuelEvent.DashStarted]) unless it is on cooldown or the fighter is not on the
 * ground (IDLE / WALKING); it drops the walk target and turns `facing` to the dash direction. `Special` is accepted and
 * does nothing (T45).
 */
object DuelPhysics {

    /**
     * Advances [state] by [dtMs] ms of duel on [level]: [inputs] (by fighter id; unknown ids ignored) apply first, then
     * the sub-steps described on [DuelPhysics]. `dtMs <= 0` returns [state] itself and no events (inputs ignored).
     * The host clamps long gaps itself: a call runs `dtMs / subStepMs` sub-steps whatever their number.
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
        val events = ArrayList<DuelEvent>(2)
        val a = Work(applyInput(state.a, inputs[state.a.id], level, params, physics, events), params, physics)
        val b = Work(applyInput(state.b, inputs[state.b.id], level, params, physics, events), params, physics)
        val sub = params.subStepMs
        val end = state.timeMs + dtMs
        val cells = end.floorDiv(sub) - state.timeMs.floorDiv(sub)
        if (cells > 0L) {
            val c = Consts(level, params, physics)
            for (i in 0L until cells) subStep(a, b, c, events)
        }
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
     * Rung out, i.e. committed to a fall off the level: over a cliff ([Physics.isOverCliff]: past `level.boundary` or
     * inside a CLIFF footprint) and FALLING, or JUMPING with the feet below the table plane (only a FLY body is still
     * JUMPING there: Physics lets it float over the edge), or RESPAWNING. Lasts until Physics respawns the body. A level
     * without a boundary or CLIFF objects has no ring-outs.
     */
    fun isOut(f: DuelFighter, level: Level): Boolean = isOut(f.body, level)

    // ---------------------------------------------------------------------------------------------------------------

    private fun isOut(body: PhysicsBody, level: Level): Boolean {
        val s = body.state
        return when (s.mode) {
            Mode.RESPAWNING -> true
            Mode.FALLING -> Physics.isOverCliff(level, s.pos)
            Mode.JUMPING -> s.h < 0f && Physics.isOverCliff(level, s.pos)
            else -> false
        }
    }

    private fun applyInput(
        f: DuelFighter,
        input: DuelInput?,
        level: Level,
        params: DuelParams,
        physics: PhysicsParams,
        events: MutableList<DuelEvent>,
    ): DuelFighter = when (input) {
        null, DuelInput.None, DuelInput.Special -> f
        is DuelInput.MoveTo -> if (acceptsInput(f, level, params)) f.copy(body = Physics.walkTo(f.body, input.target)) else f
        DuelInput.Jump -> if (acceptsInput(f, level, params)) f.copy(body = Physics.jump(f.body, physics)) else f
        is DuelInput.Dash -> if (canDash(f, level, params)) startDash(f, input.dir, params, events) else f
    }

    private fun startDash(f: DuelFighter, dir: Vec2?, params: DuelParams, events: MutableList<DuelEvent>): DuelFighter {
        val facing = f.body.state.facing.normalized()
        val d = dir?.normalized()?.takeIf { it != Vec2.ZERO } ?: facing.takeIf { it != Vec2.ZERO } ?: return f
        events += DuelEvent.DashStarted(f.id)
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

        fun done(): DuelFighter =
            original.copy(body = body, knock = knock, stunMs = stunMs, dashMs = dashMs, dashCooldownMs = dashCooldownMs)
    }

    private fun subStep(a: Work, b: Work, c: Consts, events: MutableList<DuelEvent>) {
        val wasOutA = isOut(a.body, c.level)
        val wasOutB = isOut(b.body, c.level)
        val dashA = advance(a, wasOutA, c, events)
        val dashB = advance(b, wasOutB, c, events)
        a.body = Physics.step(a.body, c.level, c.sub, a.walkPhysics)
        b.body = Physics.step(b.body, c.level, c.sub, b.walkPhysics)
        hits(a, b, dashA, dashB, c, events)
        separate(a, b, c)
        ringOut(a, wasOutA, c, events)
        ringOut(b, wasOutB, c, events)
    }

    /** Timers and duel motion of one sub-step (steps 1 and 2). Returns true when [w] dashed during it. */
    private fun advance(w: Work, wasOut: Boolean, c: Consts, events: MutableList<DuelEvent>): Boolean {
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
        val moved = moveThrough(c, s.pos, start, s.h, s.radiusM)
        w.body = w.body.copy(state = s.copy(pos = moved.pos))
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
                    events += DuelEvent.WallSlam(w.id, obstacle.id)
                } else if (speedInto > 0f && w.knock != Vec2.ZERO) {
                    w.knock -= into * w.knock.dot(into)
                    if (w.knock.length < p.knockStopMps) w.knock = Vec2.ZERO
                }
            }
        }
        return dashActiveMs > 0L
    }

    private class Moved(val pos: Vec2, val obstacle: LevelObject?)

    /**
     * [start] (= `from + displacement`) pushed out of the objects that block at [feetH], with the walking game's cliff
     * rule (`Physics.resolveCollisions`, rebuilt on public API): when the push-out would cross a cliff the unpushed
     * [start] is not over, the fighter stays at [from] if it was clear, goes to [start] if [from] is inside an object,
     * and otherwise (overlapping one) goes to [start] only if that is no deeper. A displacement may cross a cliff by
     * itself: that is how a fighter is knocked off the table. [Moved.obstacle] is the object that pushed hardest.
     */
    private fun moveThrough(c: Consts, from: Vec2, start: Vec2, feetH: Float, radius: Float): Moved {
        val level = c.level
        val pushed = pushOut(c, start, feetH, radius)
        if (!Physics.isOverCliff(level, pushed.pos) || Physics.isOverCliff(level, start)) return pushed
        val overlap = (pushOut(c, from, feetH, radius).pos - from).length
        if (overlap == 0f) return Moved(from, pushed.obstacle)
        if (level.objects.any { Physics.blocks(it, feetH, c.physics) && it.footprint.contains(from) }) return Moved(start, null)
        return if ((pushed.pos - start).length <= overlap + Physics.H_EPS) Moved(start, null) else Moved(from, pushed.obstacle)
    }

    private fun pushOut(c: Consts, start: Vec2, feetH: Float, radius: Float): Moved {
        var pos = start
        var best: LevelObject? = null
        var bestLen2 = 0f
        repeat(c.physics.pushOutPasses) {
            var moved = false
            for (o in c.level.objects) {
                if (!Physics.blocks(o, feetH, c.physics)) continue
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
    private fun hits(a: Work, b: Work, dashA: Boolean, dashB: Boolean, c: Consts, events: MutableList<DuelEvent>) {
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
            events += DuelEvent.Clash(a.id, b.id)
        } else if (dashA) {
            hit(a, b, p, events)
        } else {
            hit(b, a, p, events)
        }
    }

    private fun hit(attacker: Work, victim: Work, p: DuelParams, events: MutableList<DuelEvent>) {
        val above = fromAbove(attacker.body.state.h, victim.body.state.h, p)
        val speed = p.knockSpeedMpsFor(attacker.stats, victim.stats, above)
        knockBack(victim, attacker.dashDir * speed, p)
        attacker.dashMs = 0L
        events += DuelEvent.Hit(attacker.id, victim.id, speed, above)
    }

    private fun knockBack(victim: Work, knock: Vec2, p: DuelParams) {
        victim.knock = knock
        victim.stunMs = maxOf(victim.stunMs, p.hitStunMsFor(victim.stats))
        victim.body = stopWalking(victim.body)
    }

    private fun fromAbove(attackerH: Float, victimH: Float, p: DuelParams) = attackerH - victimH > p.fromAboveMinM

    /** Step 5: [CastPhysics.separate] when both stand and overlap (it returns the bodies unchanged otherwise). */
    private fun separate(a: Work, b: Work, c: Consts) {
        val sa = a.body.state
        val sb = b.body.state
        if (!grounded(sa.mode) || !grounded(sb.mode)) return
        if (sa.pos.distanceTo(sb.pos) >= sa.radiusM + sb.radiusM) return
        val out = CastPhysics.separate(Cast(listOf(CastMember(a.id, a.body), CastMember(b.id, b.body))), c.level, c.physics)
        a.body = out.members[0].body
        b.body = out.members[1].body
    }

    private fun grounded(mode: Mode) = mode == Mode.IDLE || mode == Mode.WALKING

    /** Step 6. The walk target goes too: a FLY body could otherwise steer back onto the table from below it. */
    private fun ringOut(w: Work, wasOut: Boolean, c: Consts, events: MutableList<DuelEvent>) {
        if (wasOut || !isOut(w.body, c.level)) return
        events += DuelEvent.RingOut(w.id)
        w.knock = Vec2.ZERO
        w.dashMs = 0L
        w.body = stopWalking(w.body)
    }
}
