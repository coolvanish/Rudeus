package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.PhysicsBody

/**
 * One fighter of a duel: the walking-game [body] plus the duel bookkeeping. Plane coordinates, metres, m/s, ms.
 *
 * @property knock knockback velocity in the plane, m/s. Decays exponentially ([DuelParams.knockTauMs]).
 * @property stunMs stun timer; it only runs while `|knock| <= DuelParams.knockStunMps`. Stunned
 *   ([DuelPhysics.isStunned]) fighters ignore input.
 * @property dashMs > 0 while dashing along [dashDir] (unit vector); dashing fighters ignore input.
 * @property dashCooldownMs > 0: a Dash input is ignored. Counted from the dash start.
 */
@Serializable
data class DuelFighter(
    val id: String,
    val body: PhysicsBody,
    val stats: FighterStats,
    val knock: Vec2 = Vec2.ZERO,
    val stunMs: Long = 0,
    val dashMs: Long = 0,
    val dashDir: Vec2 = Vec2.ZERO,
    val dashCooldownMs: Long = 0,
)

/**
 * Both fighters and the duel clock. [timeMs] advances by every `dtMs` given to [DuelPhysics.step]; the bodies are the
 * physics at the last whole sub-step on the grid of [timeMs] (at most `DuelParams.subStepMs - 1` ms behind it).
 */
@Serializable
data class DuelState(val a: DuelFighter, val b: DuelFighter, val timeMs: Long = 0) {
    init {
        require(a.id != b.id) { "duel fighters need distinct ids, got '${a.id}' twice" }
    }

    /** The fighter with [id]; null for an unknown id. */
    operator fun get(id: String): DuelFighter? = when (id) {
        a.id -> a
        b.id -> b
        else -> null
    }
}

/** What one fighter wants this step; at most one per fighter per [DuelPhysics.step]. A missing entry is [None]. */
@Serializable
sealed interface DuelInput {
    @Serializable
    data object None : DuelInput

    /** [kz.rudy.ar.core.physics.Physics.walkTo] the target. */
    @Serializable
    data class MoveTo(val target: Vec2) : DuelInput

    /** Start a dash along [dir] (normalised); null or zero = along the body's facing. Ignored on cooldown or airborne. */
    @Serializable
    data class Dash(val dir: Vec2? = null) : DuelInput

    /** [kz.rudy.ar.core.physics.Physics.jump]. */
    @Serializable
    data object Jump : DuelInput

    /** Reserved for the per-ability specials (T45): accepted, does nothing here. */
    @Serializable
    data object Special : DuelInput
}

/**
 * Something that happened during a [DuelPhysics.step]. A step's list is in canonical order, the same whichever fighter
 * sits in slot `a`: by [atMs], then by kind (DashStarted, WallSlam, Hit, Clash, RingOut), then by fighter id.
 */
@Serializable
sealed interface DuelEvent {
    /**
     * Simulated time it happened at, on the `DuelState.timeMs` sub-step grid: the end of its sub-step; for [DashStarted]
     * the grid time its input was applied at. Two events in one sub-step have the same value at any frame rate.
     */
    val atMs: Long

    @Serializable
    data class DashStarted(val id: String, override val atMs: Long) : DuelEvent

    /** [attacker]'s dash hit [victim], giving it [speedMps] of knockback; [fromAbove]: the attacker had the high ground. */
    @Serializable
    data class Hit(val attacker: String, val victim: String, val speedMps: Float, val fromAbove: Boolean, override val atMs: Long) : DuelEvent

    /** Both dashing when they met: each took the other's knockback. `a < b` (the two ids in sorted order). */
    @Serializable
    data class Clash(val a: String, val b: String, override val atMs: Long) : DuelEvent

    /** Fighter [id]'s knockback was stopped by the blocking object [objectId]. */
    @Serializable
    data class WallSlam(val id: String, val objectId: String, override val atMs: Long) : DuelEvent

    /** Fighter [id] left the level ([DuelPhysics.isOut]): once per excursion, on the sub-step it happened. */
    @Serializable
    data class RingOut(val id: String, override val atMs: Long) : DuelEvent
}

/** The result of one [DuelPhysics.step]. */
@Serializable
data class DuelStep(val state: DuelState, val events: List<DuelEvent>)
