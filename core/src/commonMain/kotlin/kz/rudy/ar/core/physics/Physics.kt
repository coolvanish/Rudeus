package kz.rudy.ar.core.physics

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterFrame
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.Vec2

/**
 * [CharacterState] plus the physics-only bookkeeping that the renderer does not need.
 *
 * @property state what the renderer draws.
 * @property verticalVelocity feet velocity along `h`, m/s (positive = up). Only meaningful while
 *   `state.mode` is [Mode.JUMPING] or [Mode.FALLING].
 * @property modeBeforePause mode to restore on [Physics.resume]; `null` when not paused.
 * @property blocked true after a step in which the character walked toward its target but push-out cancelled more
 *   than half of that step's intended displacement (an obstacle is in the way), see [Physics.step]. The HUD gets it
 *   as [kz.rudy.ar.core.domain.CharacterEvent.BLOCKED] on the rising edge.
 */
@Serializable
data class PhysicsBody(
    override val state: CharacterState = CharacterState(),
    val verticalVelocity: Float = 0f,
    val modeBeforePause: Mode? = null,
    override val blocked: Boolean = false,
) : CharacterFrame

/**
 * Pure, deterministic table physics in plane coordinates `(u, v, h)` (metres).
 *
 * Rules (see ai/build-plan.md Day 3–5):
 * - Walking moves toward `target` at [PhysicsParams.walkSpeedMps] (× [PhysicsParams.rollSpeedScale] for
 *   [Ability.ROLL], × [PhysicsParams.jumpSpeedScale] while [Mode.JUMPING]); within [PhysicsParams.arriveEpsilonM]
 *   the character snaps to the target and goes [Mode.IDLE]. `facing` follows the movement direction.
 * - [ObjectKind.OBSTACLE] / [ObjectKind.DYNAMIC] footprints push the collision circle out, but only while the feet
 *   are below the object's top (`h < heightM`): a jump clears a 2 cm object but not a 9 cm cup.
 * - [ObjectKind.PLATFORM]: entering the footprint with `heightM - h <= PhysicsParams.stepUpMaxM` lifts the feet to
 *   `heightM`; a taller platform blocks like an obstacle. Ground height under the character is the tallest standable
 *   object containing `pos`, else 0. Walking off a platform switches to [Mode.FALLING] (vertical velocity 0) and
 *   gravity brings the feet down.
 * - Objects no taller than [PhysicsParams.platformMaxHeightM] can also be landed on from above (feet at or above
 *   the top): that is how a jump "clears" a low obstacle. Taller objects are never stood on. Both heights come from
 *   the params ([Semantics] values on the table, [PhysicsParams.forSurface] scales them for the floor).
 * - Cliff: `pos` outside `level.boundary` (or inside a [ObjectKind.CLIFF] footprint) has no ground. On the ground
 *   that means [Mode.FALLING]; airborne non-[Ability.FLY] characters also start falling. Horizontal motion stops
 *   while falling off a cliff. When `h < respawnBelowH` the mode becomes [Mode.RESPAWNING]; the next step puts
 *   the character [Mode.IDLE] at `level.spawn` with `h = 0`.
 * - [Mode.JUMPING]: vertical velocity integrates gravity (× [PhysicsParams.flyGravityScale] for [Ability.FLY]),
 *   horizontal motion continues toward the target, landing happens when the feet reach the ground height.
 *   A FLY character airborne over a cliff keeps flying; it only falls if it comes down over the cliff.
 * - [Mode.PAUSED] freezes everything: [step] returns the body unchanged (including [PhysicsBody.blocked]).
 * - [PhysicsBody.blocked]: true after a step (`dtMs > 0`) that started on the ground walking toward a target, ended
 *   still [Mode.WALKING] toward it, and in which push-out cancelled more than half of the step's intended horizontal
 *   displacement (head-on into a cup: blocked; brushing along its side: not). A `dtMs <= 0` step that ends still
 *   walking toward a target keeps the previous value (a tracking resume must not re-announce BLOCKED). False after
 *   any other step; a [walkTo] to a different target clears it.
 *
 * Every function returns a new value; nothing is mutated.
 */
object Physics {

    /**
     * Advance [body] by [dtMs] milliseconds inside [level]. Deterministic for a fixed `dtMs`.
     * `dtMs <= 0` only applies instantaneous transitions (respawn, deferred jump launch).
     */
    fun step(
        body: PhysicsBody,
        level: Level,
        dtMs: Long,
        params: PhysicsParams = PhysicsParams.DEFAULT,
    ): PhysicsBody {
        val s = body.state
        when (s.mode) {
            Mode.PAUSED -> return body
            Mode.RESPAWNING -> return PhysicsBody(
                state = s.copy(pos = level.spawn, h = 0f, target = null, mode = Mode.IDLE),
                verticalVelocity = 0f,
            )
            else -> Unit
        }
        val dt = (dtMs.coerceAtLeast(0L)).toFloat() / 1000f

        var vv = body.verticalVelocity
        // A JUMPING body resting on the ground with no vertical velocity was produced by the
        // CharacterState-only `jump` overload: launch it now.
        if (s.mode == Mode.JUMPING && vv == 0f) {
            val g0 = groundHeight(level, s.pos, s.h, params)
            if (g0 != null && s.h <= g0 + H_EPS) vv = params.jumpVelocityMps
        }

        val airborne = s.mode == Mode.JUMPING || s.mode == Mode.FALLING
        val fallingOffCliff = s.mode == Mode.FALLING && isOverCliff(level, s.pos)

        // 1. Horizontal motion toward the target. `walking` = on the ground with somewhere to go (BLOCKED candidates).
        val walking = !airborne && s.target != null
        var pos = s.pos
        var target = s.target
        var facing = s.facing
        var mode = s.mode
        if (target != null && !fallingOffCliff) {
            var speed = params.walkSpeedMps
            if (s.ability == Ability.ROLL) speed *= params.rollSpeedScale
            if (s.mode == Mode.JUMPING) speed *= params.jumpSpeedScale
            val toTarget = target - pos
            val dist = toTarget.length
            val stepLen = speed * dt
            if (dist <= params.arriveEpsilonM || dist <= stepLen) {
                pos = target
            } else {
                val dir = toTarget / dist
                pos += dir * stepLen
                facing = dir
            }
            if (!airborne) mode = Mode.WALKING
        }

        // 2. Push the collision circle out of blocking objects (feet height before vertical integration).
        val moved = pos
        pos = resolveCollisions(level, pos, s.h, s.radiusM, params)
        // Blocked: the push-out took back more than half of the intended step. With d = intended, p = moved - pos
        // (what the push-out undid), the cancelled length along d is p·d / |d|; compare without a square root.
        val intended = moved - s.pos
        val pushedBack = walking && dt > 0f && intended.lengthSquared > Vec2.EPS * Vec2.EPS &&
            (moved - pos).dot(intended) > 0.5f * intended.lengthSquared

        // 3. Arrival check after collisions so a blocked character never "arrives"; snap onto the target.
        if (target != null && (target - pos).length <= params.arriveEpsilonM) {
            pos = resolveCollisions(level, target, s.h, s.radiusM, params)
            target = null
            if (!airborne) mode = Mode.IDLE
        }

        // 4. Vertical motion, landing, step-up, cliff.
        var h = s.h
        val ground = groundHeight(level, pos, s.h, params)
        if (airborne) {
            val g = params.gravityMps2 * if (s.ability == Ability.FLY) params.flyGravityScale else 1f
            vv -= g * dt
            h += vv * dt
            if (ground != null && vv <= 0f && h <= ground + H_EPS) {
                h = ground
                vv = 0f
                mode = if (target != null) Mode.WALKING else Mode.IDLE
            } else if (ground == null && mode == Mode.JUMPING && s.ability != Ability.FLY) {
                mode = Mode.FALLING
            }
            if (h < params.respawnBelowH) mode = Mode.RESPAWNING
        } else {
            when {
                ground == null -> { mode = Mode.FALLING; vv = 0f }
                ground > h + H_EPS -> h = ground
                ground < h - H_EPS -> { mode = Mode.FALLING; vv = 0f }
            }
        }

        return PhysicsBody(
            state = s.copy(pos = pos, h = h, target = target, mode = mode, facing = facing),
            verticalVelocity = vv,
            // An arrival or a fall this step is not "blocked", whatever the push-out did. A zero-length step (the host's
            // first frame after a tracking resume) measures nothing, so it keeps the previous flag.
            blocked = target != null && mode == Mode.WALKING && (if (dt > 0f) pushedBack else body.blocked),
        )
    }

    /** Set a walk target. Airborne characters steer toward it mid-air; a paused character keeps its pause. */
    fun walkTo(state: CharacterState, target: Vec2): CharacterState {
        val mode = when (state.mode) {
            Mode.IDLE, Mode.WALKING -> Mode.WALKING
            else -> state.mode
        }
        return state.copy(target = target, mode = mode)
    }

    /**
     * [walkTo] on a [PhysicsBody]. A new target clears [PhysicsBody.blocked], so walking into the obstacle again
     * announces it again; the current target again changes nothing (a host re-sending it every frame stays quiet).
     */
    fun walkTo(body: PhysicsBody, target: Vec2): PhysicsBody {
        val next = walkTo(body.state, target)
        return if (next == body.state) body else body.copy(state = next, blocked = false)
    }

    /**
     * Start a jump. No-op for [Ability.ROLL], while airborne, while paused or respawning.
     * This overload only sets [Mode.JUMPING]; [step] launches it with [PhysicsParams.jumpVelocityMps]
     * on the next call. Prefer the [PhysicsBody] overload, which sets the velocity immediately.
     */
    fun jump(state: CharacterState, params: PhysicsParams = PhysicsParams.DEFAULT): CharacterState {
        if (!canJump(state)) return state
        return state.copy(mode = Mode.JUMPING)
    }

    /** [jump] on a [PhysicsBody]: sets the mode and the initial vertical velocity. */
    fun jump(body: PhysicsBody, params: PhysicsParams = PhysicsParams.DEFAULT): PhysicsBody {
        if (!canJump(body.state)) return body
        return body.copy(
            state = body.state.copy(mode = Mode.JUMPING),
            verticalVelocity = params.jumpVelocityMps,
        )
    }

    /** Freeze the character. [step] returns a paused body unchanged. Idempotent. */
    fun pause(state: CharacterState): CharacterState =
        if (state.mode == Mode.PAUSED) state else state.copy(mode = Mode.PAUSED)

    /** [pause] on a [PhysicsBody], remembering the mode to restore on [resume]. */
    fun pause(body: PhysicsBody): PhysicsBody =
        if (body.state.mode == Mode.PAUSED) body
        else body.copy(state = body.state.copy(mode = Mode.PAUSED), modeBeforePause = body.state.mode)

    /**
     * Unfreeze a paused state. Without the body's memory the mode is inferred: [Mode.WALKING] if a target is
     * set, else [Mode.IDLE] (a jump paused mid-air resumes as a drop). Non-paused states are returned unchanged.
     */
    fun resume(state: CharacterState): CharacterState {
        if (state.mode != Mode.PAUSED) return state
        return state.copy(mode = if (state.target != null) Mode.WALKING else Mode.IDLE)
    }

    /** [resume] on a [PhysicsBody]: restores the exact mode (and vertical velocity) from before [pause]. */
    fun resume(body: PhysicsBody): PhysicsBody {
        if (body.state.mode != Mode.PAUSED) return body
        val restored = body.modeBeforePause ?: resume(body.state).mode
        return body.copy(state = body.state.copy(mode = restored), modeBeforePause = null)
    }

    /**
     * Ground height under `pos` for a character whose feet are at `feetH`, or `null` over a cliff.
     * Candidates are objects whose footprint contains `pos` and that are standable from `feetH`:
     * platforms within [PhysicsParams.stepUpMaxM], or objects no taller than [PhysicsParams.platformMaxHeightM]
     * whose top is at or below the feet. The tallest candidate wins; no candidate means the table (0).
     */
    fun groundHeight(level: Level, pos: Vec2, feetH: Float, params: PhysicsParams = PhysicsParams.DEFAULT): Float? {
        if (isOverCliff(level, pos)) return null
        var ground = 0f
        for (o in level.objects) {
            if (o.kind == ObjectKind.CLIFF || !o.footprint.contains(pos)) continue
            if (isStandable(o, feetH, params) && o.heightM > ground) ground = o.heightM
        }
        return ground
    }

    /** True when `pos` is outside `level.boundary` or inside a [ObjectKind.CLIFF] footprint. */
    fun isOverCliff(level: Level, pos: Vec2): Boolean {
        val boundary = level.boundary
        if (boundary != null && !boundary.contains(pos)) return true
        return level.objects.any { it.kind == ObjectKind.CLIFF && it.footprint.contains(pos) }
    }

    /** True when [o] blocks a character whose feet are at `feetH` (a platform taller than [PhysicsParams.stepUpMaxM] does). */
    fun blocks(o: LevelObject, feetH: Float, params: PhysicsParams = PhysicsParams.DEFAULT): Boolean = when (o.kind) {
        ObjectKind.CLIFF -> false
        ObjectKind.PLATFORM -> o.heightM - feetH > params.stepUpMaxM + H_EPS
        ObjectKind.OBSTACLE, ObjectKind.DYNAMIC -> feetH < o.heightM - H_EPS
    }

    private fun isStandable(o: LevelObject, feetH: Float, params: PhysicsParams): Boolean = when (o.kind) {
        ObjectKind.PLATFORM -> o.heightM - feetH <= params.stepUpMaxM + H_EPS
        ObjectKind.OBSTACLE, ObjectKind.DYNAMIC ->
            o.heightM <= params.platformMaxHeightM + H_EPS && o.heightM <= feetH + H_EPS
        ObjectKind.CLIFF -> false
    }

    private fun canJump(state: CharacterState): Boolean =
        state.ability != Ability.ROLL && (state.mode == Mode.IDLE || state.mode == Mode.WALKING)

    private fun resolveCollisions(
        level: Level,
        start: Vec2,
        feetH: Float,
        radius: Float,
        params: PhysicsParams,
    ): Vec2 {
        var pos = start
        repeat(params.pushOutPasses) {
            var moved = false
            for (o in level.objects) {
                if (!blocks(o, feetH, params)) continue
                val push = circlePolygonPushOut(pos, radius, o.footprint) ?: continue
                pos += push
                moved = true
            }
            if (!moved) return pos
        }
        return pos
    }

    /** Height tolerance for "on the ground" / "at the top" comparisons, metres. */
    const val H_EPS = 1e-4f
}
