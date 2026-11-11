package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.PhysicsBody

/**
 * One fighter of a duel: the walking-game [body] plus the duel bookkeeping. Plane coordinates, metres, m/s, ms.
 * Everything a HUD or a bot needs is here (no event is needed to know what a fighter is doing); the helpers on
 * [DuelPhysics] read it ([DuelPhysics.isLifted], [DuelPhysics.dashCooldownLeft], ...).
 *
 * @property knock knockback velocity in the plane, m/s. Decays exponentially ([DuelParams.knockTauMs]).
 * @property stunMs stun timer; it only runs while `|knock| <= DuelParams.knockStunMps`. Stunned
 *   ([DuelPhysics.isStunned]) fighters ignore input.
 * @property dashMs > 0 while dashing along [dashDir] (unit vector); dashing fighters ignore input.
 * @property dashCooldownMs > 0: a Dash input is ignored. Counted from the dash start.
 * @property ability whose special a [DuelInput.Special] starts; null = none (a fighter built without a
 *   [Contestant], as every T42 test does: Special is accepted and does nothing). [DuelMatch.start] sets it.
 * @property specialMs > 0 while a timed special lasts: FLY lift-off ([DuelParams.liftMs]) or HIDE dodge
 *   ([DuelParams.dodgeMs]). The stomp lasts until its jump lands ([stomping]); the ram is a dash ([dashKind]).
 * @property specialCooldownMs > 0: a Special input is ignored. Counted from the special's start.
 * @property stomping JUMP: in the air on a stomp; the landing knocks the opponent away.
 * @property counterMs HIDE: > 0 while the next dash started is a counter ([DashKind.COUNTER]); set to
 *   [DuelParams.counterMs] by every hit its dodge let through.
 * @property dashKind what the current dash is; [DashKind.PLAIN] whenever [dashMs] is 0.
 * @property wobble T48: how unsteady the hits taken so far have made this fighter, 0..[DuelParams.wobbleMax]. Every hit
 *   it takes (a clash too) is knocked [DuelParams.wobbleKnockScale] x [wobble] harder than the plain knockback, then
 *   adds [DuelParams.wobblePerHit]; it fades by [DuelParams.wobbleDecayPerS]. Not hit points: nobody loses by wobble,
 *   it only brings the edge closer. 0 at the start of every round ([DuelMatch] rebuilds the fighters on their marks).
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
    val ability: Ability? = null,
    val specialMs: Long = 0,
    val specialCooldownMs: Long = 0,
    val stomping: Boolean = false,
    val counterMs: Long = 0,
    val dashKind: DashKind = DashKind.PLAIN,
    val wobble: Float = 0f,
)

/** What kind of dash a fighter is in ([DuelFighter.dashMs] > 0). */
@Serializable
enum class DashKind {
    /** A [DuelInput.Dash]: [DuelParams.dashMs] long, plain knockback. */
    PLAIN,

    /** ROLL's special: [DuelParams.ramDashMs] long, [DuelParams.ramKnockScale] x the knockback, along `facing`. */
    RAM,

    /** HIDE's first dash after its dodge let a hit through: [DuelParams.counterKnockScale] x the knockback. */
    COUNTER,
}

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

    /**
     * The fighter's special ([DuelFighter.ability]): JUMP stomp, FLY lift-off, ROLL ram, HIDE dodge. Ignored without an
     * ability, on cooldown, or when the fighter is stunned, dashing, airborne, out or PAUSED ([DuelPhysics.canSpecial]).
     */
    @Serializable
    data object Special : DuelInput
}

/**
 * Something that happened during a [DuelPhysics.step]. A step's list is in canonical order, the same whichever fighter
 * sits in slot `a`: by [atMs], then by kind (DashStarted, SpecialStarted, WallSlam, Hit, Clash, RingOut), then by
 * fighter id.
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

    /** Fighter [id] started the special of [ability] ([DuelInput.Special]); [atMs] is the grid time its input was applied at. */
    @Serializable
    data class SpecialStarted(val id: String, val ability: Ability, override val atMs: Long) : DuelEvent

    /**
     * [attacker] hit [victim], giving it [speedMps] of knockback: with a dash (plain, ram or counter), or with a stomp's
     * landing (always [fromAbove]). [fromAbove] on a dash: the attacker had the high ground.
     */
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
