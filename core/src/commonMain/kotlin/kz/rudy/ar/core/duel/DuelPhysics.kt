package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
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
import kotlin.math.ceil
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
 * 1. Timers: dash and special cooldowns, the counter window, the special's time (lift-off, dodge) and the dash time
 *    count down; the stun timer counts down only while `|knock| <= knockStunMps`. A fighter whose lift-off or dodge
 *    time was > 0 at the start of the sub-step is lifted / dodging for the whole sub-step (it cannot be hit). A PAUSED
 *    fighter is frozen whole (timers, knockback, body) and takes no part in anything below.
 * 2. Duel motion: the dash (`dashSpeedMps` along `dashDir`, a ram too) and the knockback (exact exponential decay over
 *    the sub-step) move the body through the obstacle rules walking uses: push-out from every object that
 *    [Physics.blocks] at the feet height, and an obstacle never pushes anyone over a cliff. The move is swept, in pieces
 *    of at most half a radius each resolved so ([sweepPieces]): no speed carries a fighter through a thin object (a
 *    move up to half a radius, every move before wobble, is one piece). A knockback pressed into a
 *    blocking object at `>= wallSlamMinMps` (its component into the surface) is a wall slam: knockback zeroed,
 *    `wallSlamStunMs` added, [DuelEvent.WallSlam]; slower contact slides (the into-surface part of the knockback is
 *    dropped). A fighter that is out ([isOut]) gets no duel motion; its knockback, dash, lift-off, dodge and stomp are
 *    dropped. No dash stops at the boundary: a dash or a ram that misses near the edge carries its fighter off.
 * 3. [Physics.step] with the fighter's walk speed ([DuelParams.walkSpeedMpsFor]): walking, jumping, step-up onto
 *    platforms, falling, respawning, exactly as the walking game does it, with duel rules on top: an out fighter
 *    stays where it left the level until Physics respawns it; a body that came down this sub-step is pushed out of
 *    what blocks at its new feet height at once (Physics would do it one sub-step later); a lifted FLY walks as a
 *    walker on the ground under it would and is held [DuelParams.liftHeightRadii] radii above that ground (JUMPING, no
 *    vertical speed), and on the lift's last sub-step is that walker again, standing on the ground, see [liftStep]; a
 *    stomping JUMP that was airborne and now stands has landed.
 * 4. Hits, every one judged from the positions after step 3, none of them by height (an airborne fighter is hit too),
 *    never on a lifted or dodging victim, never by or on a PAUSED or out fighter:
 *    - a dash: circles overlapping while one of them dashed in this sub-step. The victim gets
 *      [DuelParams.knockSpeedMpsFor] along the attacker's `dashDir` (x `fromAboveScale` when the attacker's feet are
 *      more than `fromAboveMinM` higher; x `ramKnockScale` for a ram, x `counterKnockScale` for a counter), its walk
 *      target is dropped, its own dash ends and its stun timer is set to at least [DuelParams.hitStunMsFor]; the
 *      attacker's dash ends, so a dash hits at most once. Both dashing and both hittable: [DuelEvent.Clash], each takes
 *      the other's knockback and both dashes end. A dash that meets a lifted or dodging fighter carries on; a dodging
 *      one gets a counter window for it (so does a dodged stomp).
 *    - a stomp's landing: an opponent whose centre is within `stompRadiusRadii` stomper radii of the landing point gets
 *      `stompScale` x the plain knockback straight away from it, the same stun, and a [DuelEvent.Hit] with
 *      `fromAbove = true`.
 * 5. Separation of two grounded (IDLE / WALKING) fighters that overlap, see [separate]; a dodging fighter stands firm.
 * 6. Ring-out: a fighter that was not [isOut] at the start of the sub-step and is now gets [DuelEvent.RingOut] and
 *    loses its walk target, lift-off, dodge, stomp and counter. It stays out (and ignores input) until Physics respawns
 *    it, so one excursion is one ring-out. What follows is the match's ([DuelMatch]).
 *
 * **Events** carry their simulated time ([DuelEvent.atMs]) and come in canonical order (time, kind, id), so neither the
 * frame rate nor the slot a fighter sits in changes the list.
 *
 * **Input** ([acceptsInput]): stunned, dashing, PAUSED or out fighters ignore it, and so does every fighter a non-finite
 * target or direction. `MoveTo` is [Physics.walkTo], `Jump` is [Physics.jump], `Dash` starts a dash
 * ([DuelEvent.DashStarted]) unless it is on cooldown or the fighter is not on the ground (IDLE / WALKING); it drops the
 * walk target and turns `facing` to the dash direction (a null or zero direction = along `facing`); inside a HIDE's
 * counter window it is a counter. `Special` starts the special of [DuelFighter.ability] when [canSpecial]
 * ([DuelEvent.SpecialStarted], the special cooldown starts):
 * - JUMP stomp: [Physics.jump] (the walk target stays); the landing is a hit (step 4).
 * - FLY lift-off: the body rises to the hover height at once and hovers for `liftMs`, walking as it would on the ground
 *   under it (same obstacles, same step-up) at `liftSpeedScale` x; it cannot dash while lifted (it is airborne) but can
 *   walk off the edge.
 * - ROLL ram: a dash along `facing`, `ramDashMs` long, `ramKnockScale` x the knockback; the dash cooldown is untouched.
 * - HIDE dodge: for `dodgeMs` hits pass through, until the fighter starts a dash (a dash ends the dodge at once). Each
 *   hit the dodge lets through (a dash or a stomp that would have hit) opens a counter window of `counterMs` from that
 *   sub-step: the next dash started in it is a counter. A dodge that dodged nothing gives no counter.
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
    ): DuelStep = run(state, level, dtMs, inputs, params, physics, stopAtRingOut = false)

    /**
     * [step], except that it stops at the end of the first sub-step in which somebody rang out: the returned state's
     * `timeMs` is then that sub-step's grid time (so `<= state.timeMs + dtMs`), and every [DuelEvent.RingOut] in the
     * list has that `atMs`. A round ends there ([DuelMatch]); nothing after it is simulated.
     */
    internal fun stepToRingOut(
        state: DuelState,
        level: Level,
        dtMs: Long,
        inputs: Map<String, DuelInput>,
        params: DuelParams,
        physics: PhysicsParams,
    ): DuelStep = run(state, level, dtMs, inputs, params, physics, stopAtRingOut = true)

    private fun run(
        state: DuelState,
        level: Level,
        dtMs: Long,
        inputs: Map<String, DuelInput>,
        params: DuelParams,
        physics: PhysicsParams,
        stopAtRingOut: Boolean,
    ): DuelStep {
        if (dtMs <= 0L) return DuelStep(state, emptyList())
        val sub = params.subStepMs
        val firstCell = state.timeMs.floorDiv(sub)
        var end = state.timeMs + dtMs
        val cells = end.floorDiv(sub) - firstCell
        val events = ArrayList<DuelEvent>(2)
        val appliedAt = firstCell * sub
        val a = Work(applyInput(state.a, inputs[state.a.id], level, params, physics, appliedAt, events), params, physics)
        val b = Work(applyInput(state.b, inputs[state.b.id], level, params, physics, appliedAt, events), params, physics)
        if (cells > 0L) {
            val c = Consts(level, params, physics)
            for (i in 1L..cells) {
                val t = (firstCell + i) * sub
                if (subStep(a, b, c, t, events) && stopAtRingOut) {
                    end = t
                    break
                }
            }
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
     * A Special input would start [f]'s special now: it has an [DuelFighter.ability], the special is off cooldown and
     * not running, [f] is on the ground (IDLE / WALKING) and [acceptsInput] (not stunned, dashing, PAUSED or out). A
     * JUMP stomp also needs a body that can jump (not a ROLL body), a ROLL ram a facing.
     */
    fun canSpecial(f: DuelFighter, level: Level, params: DuelParams = DuelParams.DEFAULT): Boolean {
        val ability = f.ability ?: return false
        val s = f.body.state
        if (f.specialCooldownMs > 0L || f.specialMs > 0L || !grounded(s.mode) || !acceptsInput(f, level, params)) return false
        return when (ability) {
            Ability.JUMP -> s.ability != Ability.ROLL
            Ability.ROLL -> s.facing.normalized() != Vec2.ZERO
            Ability.FLY, Ability.HIDE -> true
        }
    }

    /** FLY lift-off running: hovering, cannot be hit, cannot dash. */
    fun isLifted(f: DuelFighter): Boolean = f.ability == Ability.FLY && f.specialMs > 0L

    /** HIDE dodge running: hits pass through, separation leaves it where it stands. */
    fun isDodging(f: DuelFighter): Boolean = f.ability == Ability.HIDE && f.specialMs > 0L

    /** Cannot be hit (by a dash or a stomp): lifted or dodging. */
    fun isImmune(f: DuelFighter): Boolean = isLifted(f) || isDodging(f)

    /** In a ROLL ram. */
    fun isRamming(f: DuelFighter): Boolean = f.dashMs > 0L && f.dashKind == DashKind.RAM

    /** The HUD's dash cooldown: the fraction of it still to run, 1 = just dashed, 0 = ready. Pass the params in use. */
    fun dashCooldownLeft(f: DuelFighter, params: DuelParams = DuelParams.DEFAULT): Float =
        fraction(f.dashCooldownMs, params.cooldownMsFor(f.stats))

    /** The HUD's special cooldown: the fraction of it still to run, 1 = just used, 0 = ready. Pass the params in use. */
    fun specialCooldownLeft(f: DuelFighter, params: DuelParams = DuelParams.DEFAULT): Float =
        fraction(f.specialCooldownMs, params.specialCooldownMsFor(f.stats))

    private fun fraction(left: Long, total: Long): Float =
        if (left <= 0L || total <= 0L) 0f else (left.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    /**
     * [f] with everything it was doing stopped: no walk target (a WALKING body stands), no dash, no lift-off, dodge,
     * stomp or counter. Knockback, stun, cooldowns and the body's vertical motion stay. The match calls it at a point.
     */
    internal fun endActions(f: DuelFighter): DuelFighter = f.copy(
        body = stopWalking(f.body),
        dashMs = 0L,
        dashKind = DashKind.PLAIN,
        specialMs = 0L,
        stomping = false,
        counterMs = 0L,
    )

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
        null, DuelInput.None -> f
        is DuelInput.MoveTo ->
            if (input.target.isFinite() && acceptsInput(f, level, params)) f.copy(body = Physics.walkTo(f.body, input.target)) else f
        DuelInput.Jump -> if (acceptsInput(f, level, params)) f.copy(body = Physics.jump(f.body, physics)) else f
        is DuelInput.Dash ->
            if (input.dir?.isFinite() != false && canDash(f, level, params)) startDash(f, input.dir, params, atMs, events) else f
        DuelInput.Special -> if (canSpecial(f, level, params)) startSpecial(f, params, physics, atMs, events) else f
    }

    private fun startDash(f: DuelFighter, dir: Vec2?, params: DuelParams, atMs: Long, events: MutableList<DuelEvent>): DuelFighter {
        val facing = f.body.state.facing.normalized()
        val d = dir?.normalized()?.takeIf { it != Vec2.ZERO } ?: facing.takeIf { it != Vec2.ZERO } ?: return f
        events += DuelEvent.DashStarted(f.id, atMs)
        val body = stopWalking(f.body)
        val counter = f.counterMs > 0L
        return f.copy(
            body = body.copy(state = body.state.copy(facing = d)),
            dashMs = params.dashMs,
            dashDir = d,
            dashCooldownMs = params.cooldownMsFor(f.stats),
            dashKind = if (counter) DashKind.COUNTER else DashKind.PLAIN,
            counterMs = if (counter) 0L else f.counterMs,
            // Starting a dash ends a dodge at once: nobody dashes while it cannot be hit.
            specialMs = if (isDodging(f)) 0L else f.specialMs,
        )
    }

    /** Starts [f]'s special ([canSpecial] holds), see [DuelPhysics]. */
    private fun startSpecial(
        f: DuelFighter,
        params: DuelParams,
        physics: PhysicsParams,
        atMs: Long,
        events: MutableList<DuelEvent>,
    ): DuelFighter {
        val ability = f.ability ?: return f
        val started = when (ability) {
            Ability.JUMP -> {
                val body = Physics.jump(f.body, physics)
                if (body.state.mode != Mode.JUMPING) return f
                f.copy(body = body, stomping = true)
            }
            Ability.FLY -> {
                val s = f.body.state
                val lifted = s.copy(mode = Mode.JUMPING, h = s.h + params.liftHeightRadii * s.radiusM)
                f.copy(body = f.body.copy(state = lifted, verticalVelocity = 0f), specialMs = params.liftMs)
            }
            Ability.ROLL -> {
                val d = f.body.state.facing.normalized().takeIf { it != Vec2.ZERO } ?: return f
                val body = stopWalking(f.body)
                f.copy(body = body.copy(state = body.state.copy(facing = d)), dashMs = params.ramDashMs, dashDir = d, dashKind = DashKind.RAM)
            }
            Ability.HIDE -> f.copy(specialMs = params.dodgeMs)
        }
        events += DuelEvent.SpecialStarted(f.id, ability, atMs)
        return started.copy(specialCooldownMs = params.specialCooldownMsFor(f.stats))
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

        /** Wobble lost in one sub-step. */
        val wobbleDecay: Float = params.wobbleDecayPerS * sub / 1000f
    }

    /** One fighter during a call: local, mutable scratch (never visible outside [step]) that becomes a [DuelFighter]. */
    private class Work(private val original: DuelFighter, params: DuelParams, physics: PhysicsParams) {
        val id = original.id
        val stats = original.stats
        val dashDir = original.dashDir
        val ability = original.ability
        var body = original.body
        var knock = original.knock
        var stunMs = original.stunMs
        var dashMs = original.dashMs
        var dashCooldownMs = original.dashCooldownMs
        var specialMs = original.specialMs
        var specialCooldownMs = original.specialCooldownMs
        var stomping = original.stomping
        var counterMs = original.counterMs
        var dashKind = original.dashKind
        var wobble = original.wobble

        /** This sub-step only (set by [advance]): hovering on a FLY lift-off. */
        var lifted = false

        /** This sub-step only (set by [advance]): in a HIDE dodge. */
        var dodging = false

        /** Lifted or dodging this sub-step: it cannot be hit. */
        val immune: Boolean get() = lifted || dodging

        /** The walking-game parameters with this fighter's duel walk speed. */
        val walkPhysics: PhysicsParams =
            physics.copy(walkSpeedMps = params.walkSpeedMpsFor(stats, physics), rollSpeedScale = params.rollSpeedScale)

        /** A lifted FLY walks its base (see [liftStep]) at the duel walk speed x [DuelParams.liftSpeedScale]. */
        val liftWalkPhysics: PhysicsParams =
            if (ability == Ability.FLY) walkPhysics.copy(walkSpeedMps = walkPhysics.walkSpeedMps * params.liftSpeedScale) else walkPhysics

        val paused: Boolean get() = body.state.mode == Mode.PAUSED

        fun moveTo(p: Vec2) {
            body = body.copy(state = body.state.copy(pos = p))
        }

        fun done(): DuelFighter = original.copy(
            body = body, knock = knock, stunMs = stunMs, dashMs = dashMs, dashCooldownMs = dashCooldownMs,
            specialMs = specialMs, specialCooldownMs = specialCooldownMs, stomping = stomping, counterMs = counterMs,
            dashKind = dashKind, wobble = wobble,
        )
    }

    /** One sub-step ending at [atMs]; true when somebody rang out in it. */
    private fun subStep(a: Work, b: Work, c: Consts, atMs: Long, events: MutableList<DuelEvent>): Boolean {
        val wasOutA = isOut(a.body, c.level)
        val wasOutB = isOut(b.body, c.level)
        val dashA = advance(a, wasOutA, c, atMs, events)
        val dashB = advance(b, wasOutB, c, atMs, events)
        val landA = physicsStep(a, wasOutA, c)
        val landB = physicsStep(b, wasOutB, c)
        hits(a, b, dashA, dashB, landA, landB, c, atMs, events)
        separate(a, b, c)
        val outA = ringOut(a, wasOutA, c, atMs, events)
        val outB = ringOut(b, wasOutB, c, atMs, events)
        if (a.dashMs == 0L) a.dashKind = DashKind.PLAIN
        if (b.dashMs == 0L) b.dashKind = DashKind.PLAIN
        return outA || outB
    }

    /** Timers and duel motion of one sub-step (steps 1 and 2). Returns true when [w] dashed during it. */
    private fun advance(w: Work, wasOut: Boolean, c: Consts, atMs: Long, events: MutableList<DuelEvent>): Boolean {
        w.lifted = false
        w.dodging = false
        if (w.paused) return false
        val p = c.params
        w.dashCooldownMs = (w.dashCooldownMs - c.sub).coerceAtLeast(0L)
        if (w.specialCooldownMs > 0L) w.specialCooldownMs = (w.specialCooldownMs - c.sub).coerceAtLeast(0L)
        if (w.counterMs > 0L) w.counterMs = (w.counterMs - c.sub).coerceAtLeast(0L)
        if (w.wobble > 0f && p.wobbleDecayPerS > 0f) w.wobble = (w.wobble - c.wobbleDecay).coerceAtLeast(0f)
        if (w.specialMs > 0L) {
            w.lifted = w.ability == Ability.FLY
            w.dodging = w.ability == Ability.HIDE
            w.specialMs = (w.specialMs - c.sub).coerceAtLeast(0L)
        }
        if (w.knock.length <= p.knockStunMps) w.stunMs = (w.stunMs - c.sub).coerceAtLeast(0L)
        val dashActiveMs = minOf(w.dashMs, c.sub)
        w.dashMs = (w.dashMs - c.sub).coerceAtLeast(0L)
        if (wasOut) {
            w.knock = Vec2.ZERO
            w.dashMs = 0L
            w.specialMs = 0L
            w.stomping = false
            w.lifted = false
            w.dodging = false
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
        val origin = s.pos
        // A lifted FLY is moved at its base, like everything else it does (see liftStep).
        val feetH = if (w.lifted) s.h - liftHeight(s, p) else s.h
        // Swept (T48 review): the move runs in pieces no longer than SWEEP_PIECE_RADII radii, each resolved against what
        // blocks, so no speed can carry a circle through a thin object between two checks: a centre that stood at least
        // a radius from a face moves at most half a radius before the next push-out, which still sends it back. A move
        // up to that length is one piece: the single resolution as before, exactly. While nothing has pushed, every
        // piece aims at its exact share of the move, so an unobstructed move of any length ends where it always did.
        val pieces = sweepPieces(disp.length, s.radiusM)
        var pos = origin
        var clean = true
        for (k in 1..pieces) {
            val target = when {
                k == pieces && clean -> origin + disp
                clean -> origin + disp * (k.toFloat() / pieces)
                else -> pos + (disp * (k.toFloat() / pieces) - disp * ((k - 1).toFloat() / pieces))
            }
            val moved = moveThrough(c.level, c.physics, pos, target, feetH, s.radiusM)
            pos = moved.pos
            if (pos != target) clean = false
            val obstacle = moved.obstacle
            if (knock0 != Vec2.ZERO && obstacle != null) {
                // What the obstacle took back, as a direction into its surface.
                val back = target - moved.pos
                val len = back.length
                if (len > Vec2.EPS) {
                    val into = back / len
                    val speedInto = knock0.dot(into)
                    if (speedInto >= p.wallSlamMinMps) {
                        // The first block stops the knockback here, as the single move did: the rest of it is spent.
                        w.knock = Vec2.ZERO
                        w.stunMs += p.wallSlamStunMs
                        events += DuelEvent.WallSlam(w.id, obstacle.id, atMs)
                        break
                    } else if (speedInto > 0f && w.knock != Vec2.ZERO) {
                        w.knock -= into * w.knock.dot(into)
                        if (w.knock.length < p.knockStopMps) w.knock = Vec2.ZERO
                    }
                }
            }
        }
        w.moveTo(pos)
        return dashActiveMs > 0L
    }

    /**
     * How many pieces a move of [length] runs in for a fighter of [radius]: 1 up to [SWEEP_PIECE_RADII] radii, else
     * enough for none to be longer.
     */
    internal fun sweepPieces(length: Float, radius: Float): Int {
        val most = SWEEP_PIECE_RADII * radius
        if (!(most > 0f) || !(length > most)) return 1
        return ceil(length / most).toInt().coerceAtLeast(1)
    }

    /** The longest piece of a swept move, in fighter radii (1.5 cm for a table fighter). */
    internal const val SWEEP_PIECE_RADII = 0.5f

    /** Step 3. Returns true when [w]'s stomp landed in it. */
    private fun physicsStep(w: Work, wasOut: Boolean, c: Consts): Boolean {
        if (w.lifted) {
            liftStep(w, c)
            return false
        }
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
        if (wasOut || !w.stomping) return false
        val landed = (s0.mode == Mode.JUMPING || s0.mode == Mode.FALLING) && grounded(w.body.state.mode)
        if (landed) w.stomping = false
        return landed
    }

    /** The hover height of a lifted body: [DuelParams.liftHeightRadii] of its radii. */
    private fun liftHeight(s: CharacterState, p: DuelParams): Float = p.liftHeightRadii * s.radiusM

    /**
     * One sub-step of a FLY lift-off. The hover is height only: the fighter moves exactly as a walker standing on its
     * base would (the base = the hover height minus [liftHeight], the ground under it), at the lifted walk speed, so
     * what blocks it and what it can step up onto are decided at the base, never at the hover height. The base then
     * follows the ground the walker reached; over a hole or past the edge (where a walker would start to fall) it is the
     * table plane, so a lifted FLY glides over a hole and, past the boundary, is out. Coming down off something (a book)
     * the circle is pushed out of what blocks at the new base, as a walker coming down is. The body stays JUMPING at
     * base + lift with no vertical speed. On the lift's last sub-step the fighter becomes that walker: standing on its
     * base (or starting to fall where the walker would: off a book, into a hole), never descending through the air,
     * where a jump's step-up would let it climb what a walker cannot.
     */
    private fun liftStep(w: Work, c: Consts) {
        val s = w.body.state
        val lift = liftHeight(s, c.params)
        // The exact ground value (s.h - lift is a float or two off it); the table plane over a hole.
        val base = Physics.groundHeight(c.level, s.pos, s.h - lift, c.physics) ?: 0f
        val walker = PhysicsBody(s.copy(h = base, mode = if (s.target != null) Mode.WALKING else Mode.IDLE), 0f, null, w.body.blocked)
        val walked = Physics.step(walker, c.level, c.sub, w.liftWalkPhysics)
        if (w.specialMs == 0L) {
            w.body = walked
            return
        }
        val m = walked.state
        val ground = Physics.groundHeight(c.level, m.pos, m.h, c.physics) ?: 0f
        val pos = if (ground < base - Physics.H_EPS) moveThrough(c.level, c.physics, m.pos, m.pos, ground, s.radiusM).pos else m.pos
        val hovering = s.copy(pos = pos, h = ground + lift, target = m.target, facing = m.facing, mode = Mode.JUMPING)
        w.body = PhysicsBody(hovering, verticalVelocity = 0f, modeBeforePause = null, blocked = walked.blocked)
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

    /**
     * Step 4: dash contacts and stomp landings, all judged from the positions after step 3 (a knockback given here
     * moves nobody before the next sub-step, so the order of the calls below cannot change an outcome).
     */
    private fun hits(
        a: Work,
        b: Work,
        dashA: Boolean,
        dashB: Boolean,
        landA: Boolean,
        landB: Boolean,
        c: Consts,
        atMs: Long,
        events: MutableList<DuelEvent>,
    ) {
        if (!dashA && !dashB && !landA && !landB) return
        if (a.paused || b.paused || isOut(a.body, c.level) || isOut(b.body, c.level)) return
        val sa = a.body.state
        val sb = b.body.state
        val p = c.params
        val dist = sa.pos.distanceTo(sb.pos)
        val contact = dist < sa.radiusM + sb.radiusM
        // What would hit each one, were it not immune.
        val reachesB = (dashA && contact) || (landA && dist <= p.stompRadiusRadii * sa.radiusM)
        val reachesA = (dashB && contact) || (landB && dist <= p.stompRadiusRadii * sb.radiusM)
        // A dodge that lets a hit through opens the counter window, from this sub-step, for counterMs.
        if (b.dodging && reachesB) b.counterMs = p.counterMs
        if (a.dodging && reachesA) a.counterMs = p.counterMs
        val dashHitsB = dashA && contact && !b.immune
        val dashHitsA = dashB && contact && !a.immune
        val stompHitsB = landA && !b.immune && dist <= p.stompRadiusRadii * sa.radiusM
        val stompHitsA = landB && !a.immune && dist <= p.stompRadiusRadii * sb.radiusM
        if (dashHitsA && dashHitsB) {
            val toA = p.knockSpeedMpsFor(b.stats, a.stats, fromAbove(sb.h, sa.h, p)) * knockScale(b, p)
            val toB = p.knockSpeedMpsFor(a.stats, b.stats, fromAbove(sa.h, sb.h, p)) * knockScale(a, p)
            val dirA = a.dashDir
            val dirB = b.dashDir
            knockBack(a, dirB, toA, p)
            knockBack(b, dirA, toB, p)
            a.dashMs = 0L
            b.dashMs = 0L
            events += if (a.id < b.id) DuelEvent.Clash(a.id, b.id, atMs) else DuelEvent.Clash(b.id, a.id, atMs)
        } else if (dashHitsB) {
            hit(a, b, p, atMs, events)
        } else if (dashHitsA) {
            hit(b, a, p, atMs, events)
        }
        // A stomper is never dashing (a dash starts only on the ground, a stomp only when not dashing), so a fighter is
        // the victim of at most one of the hits above and one below, and never of both from the same attacker.
        if (stompHitsB) stomp(a, b, p, atMs, events)
        if (stompHitsA) stomp(b, a, p, atMs, events)
    }

    private fun hit(attacker: Work, victim: Work, p: DuelParams, atMs: Long, events: MutableList<DuelEvent>) {
        val above = fromAbove(attacker.body.state.h, victim.body.state.h, p)
        val speed = p.knockSpeedMpsFor(attacker.stats, victim.stats, above) * knockScale(attacker, p)
        val given = knockBack(victim, attacker.dashDir, speed, p)
        attacker.dashMs = 0L
        events += DuelEvent.Hit(attacker.id, victim.id, given, above, atMs)
    }

    /** The JUMP stomp's landing: [victim] knocked straight away from where [stomper] stands now. */
    private fun stomp(stomper: Work, victim: Work, p: DuelParams, atMs: Long, events: MutableList<DuelEvent>) {
        val s = stomper.body.state
        val away = (victim.body.state.pos - s.pos).normalized().takeIf { it != Vec2.ZERO }
            ?: s.facing.normalized().takeIf { it != Vec2.ZERO }
            ?: Vec2(1f, 0f)
        val speed = p.knockSpeedMpsFor(stomper.stats, victim.stats) * p.stompScale
        val given = knockBack(victim, away, speed, p)
        events += DuelEvent.Hit(stomper.id, victim.id, given, fromAbove = true, atMs = atMs)
    }

    private fun knockScale(attacker: Work, p: DuelParams): Float = when (attacker.dashKind) {
        DashKind.PLAIN -> 1f
        DashKind.RAM -> p.ramKnockScale
        DashKind.COUNTER -> p.counterKnockScale
    }

    /**
     * A hit along unit [dir] at [speed] (m/s, before wobble): the knockback, x [DuelParams.wobbleFactor] of the victim's
     * wobble, which then grows by [DuelParams.wobblePerHit] (capped); a stun, no walk target, and the victim's own dash
     * (if any) ends. Returns the speed given. With no wobble (0, and none per hit) it is the plain knockback, bit for bit.
     */
    private fun knockBack(victim: Work, dir: Vec2, speed: Float, p: DuelParams): Float {
        val given = speed * p.wobbleFactor(victim.wobble)
        victim.knock = dir * given
        victim.wobble = minOf(victim.wobble + p.wobblePerHit, p.wobbleMax)
        victim.stunMs = maxOf(victim.stunMs, p.hitStunMsFor(victim.stats))
        victim.body = stopWalking(victim.body)
        victim.dashMs = 0L
        return given
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
     * Only `pos` changes. A dodging fighter (HIDE, this sub-step) stands firm: when only one of the two dodges, the other
     * goes round it first (the same 16 directions); only if it fits nowhere does the search above run.
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
        if (a.immune != b.immune) {
            for (k in TURN_COS.indices) {
                val u = Vec2(dir.u * TURN_COS[k] - dir.v * TURN_SIN[k], dir.u * TURN_SIN[k] + dir.v * TURN_COS[k])
                if (a.immune) {
                    val q = sa.pos + u * reach
                    if (canStand(c, sb, q)) return b.moveTo(q)
                } else {
                    val q = sb.pos - u * reach
                    if (canStand(c, sa, q)) return a.moveTo(q)
                }
            }
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

    /**
     * Step 6. The walk target goes too, so nothing steers an out body back, and so do the lift-off (a FLY comes down),
     * the dodge, the stomp and the counter. True when [w] rang out in this sub-step.
     */
    private fun ringOut(w: Work, wasOut: Boolean, c: Consts, atMs: Long, events: MutableList<DuelEvent>): Boolean {
        if (wasOut || !isOut(w.body, c.level)) return false
        events += DuelEvent.RingOut(w.id, atMs)
        w.knock = Vec2.ZERO
        w.dashMs = 0L
        w.body = stopWalking(w.body)
        w.specialMs = 0L
        w.stomping = false
        w.counterMs = 0L
        return true
    }

    private fun kindRank(e: DuelEvent): Int = when (e) {
        is DuelEvent.DashStarted -> 0
        is DuelEvent.SpecialStarted -> 1
        is DuelEvent.WallSlam -> 2
        is DuelEvent.Hit -> 3
        is DuelEvent.Clash -> 4
        is DuelEvent.RingOut -> 5
    }

    private fun firstId(e: DuelEvent): String = when (e) {
        is DuelEvent.DashStarted -> e.id
        is DuelEvent.SpecialStarted -> e.id
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
