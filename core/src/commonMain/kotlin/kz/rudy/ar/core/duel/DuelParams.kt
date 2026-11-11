package kz.rudy.ar.core.duel

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.math.roundToLong

/**
 * Every tunable number of the duel ([DuelPhysics]), defaults sized for the table (a 6 cm fighter, radius 3 cm).
 * Distances in metres, speeds in m/s, times in ms. The walking-game numbers (gravity, jump, push-out, step-up) are not
 * here: they come from the [PhysicsParams] passed next to these, scaled by [PhysicsParams.forProfile].
 *
 * A stat factor reads `base + perPoint * stat`, with the stat clamped to [FighterStats.MIN]..[FighterStats.MAX] so a
 * hand-edited sheet cannot produce a negative speed or a reversed knockback.
 */
@Serializable
data class DuelParams(
    /** Duel walk speed multiplier on [PhysicsParams.walkSpeedMps] (the walking game strolls; a duel hurries). */
    val duelSpeedScale: Float = 2f,
    /** Walk speed factor = [speedBase] + [speedPerPoint] * SPEED (0.76 .. 1.30). */
    val speedBase: Float = 0.70f,
    val speedPerPoint: Float = 0.06f,
    /**
     * Replaces [PhysicsParams.rollSpeedScale] inside a duel. 1 = the SPEED formula alone sets the walk speed for every
     * ability (the walking game's ROLL x 2 would otherwise stack on top of the stats).
     */
    val rollSpeedScale: Float = 1f,

    /** How long a dash lasts. */
    val dashMs: Long = 250,
    /** Dash speed along the dash direction (replaces walking while it lasts). 250 ms x 0.30 m/s = 7.5 cm. */
    val dashSpeedMps: Float = 0.30f,
    /** Base dash cooldown, counted from the dash start: [dashCooldownMs] * ([cooldownBase] - [cooldownPerAgility] * AGILITY). */
    val dashCooldownMs: Long = 1500,
    val cooldownBase: Float = 1.30f,
    val cooldownPerAgility: Float = 0.06f,

    /** Knockback speed given by a POWER 5 attacker to a GUARD 5 victim. */
    val knockSpeedMps: Float = 0.25f,
    /** Attacker factor = [powerBase] + [powerPerPoint] * POWER. */
    val powerBase: Float = 0.55f,
    val powerPerPoint: Float = 0.09f,
    /** Victim divisor = [guardBase] + [guardPerPoint] * GUARD. */
    val guardBase: Float = 0.55f,
    val guardPerPoint: Float = 0.09f,
    /** Knockback multiplier when the attacker's feet are more than [fromAboveMinM] above the victim's (high ground). */
    val fromAboveScale: Float = 1.25f,
    val fromAboveMinM: Float = 0.01f,
    /** Knockback velocity decays as exp(-t / tau): a hit at v0 slides the victim about v0 * tau. */
    val knockTauMs: Long = 180,
    /** Above this knockback speed the fighter is still stunned; the stun timer only runs once it is below. */
    val knockStunMps: Float = 0.02f,
    /** Below this knockback speed the knockback is dropped to zero (a numerical cut-off, about 0.5 mm of slide left). */
    val knockStopMps: Float = 0.003f,

    /** Base stun after a hit: [hitStunMs] * ([stunBase] - [stunPerAgility] * AGILITY), counted once the knockback is spent. */
    val hitStunMs: Long = 300,
    val stunBase: Float = 1.30f,
    val stunPerAgility: Float = 0.06f,
    /** Stun added by slamming into an object that blocks the knockback. */
    val wallSlamStunMs: Long = 400,
    /**
     * A knockback pressing into a blocking object at least this fast (its component into the surface) is a wall slam;
     * slower contact slides along the surface instead.
     */
    val wallSlamMinMps: Float = 0.02f,

    /**
     * Internal fixed sub-step, 1..8 ms. Sub-steps sit on a fixed grid of `DuelState.timeMs`, so any split of the same
     * time into frames runs exactly the same sub-steps (see [DuelPhysics.step]).
     */
    val subStepMs: Long = 8,

    // Specials (T45): one per Ability, started by DuelInput.Special. Only times and dimensionless numbers (sizes in
    // fighter radii), so they follow the surface and the character size without being in [scaled].

    /**
     * Base special cooldown, counted from the special's start and scaled by agility like the dash:
     * [specialCooldownMs] * ([cooldownBase] - [cooldownPerAgility] * AGILITY) (7440 .. 4200 ms, 6000 at AGILITY 5).
     */
    val specialCooldownMs: Long = 6000,
    /** JUMP stomp: an opponent whose centre is within this many of the stomper's radii of the landing point is hit. */
    val stompRadiusRadii: Float = 3f,
    /** JUMP stomp: knockback = [stompScale] x the plain knockback (the high-ground bonus does not stack on it). */
    val stompScale: Float = 1.2f,
    /** FLY lift-off: how long the fighter hovers, cannot be hit and walks at [liftSpeedScale] x its walk speed. */
    val liftMs: Long = 1200,
    val liftSpeedScale: Float = 1.5f,
    /** FLY lift-off: hover height of the feet above the ground under them, in fighter radii (0.5 = 1.5 cm on the table). */
    val liftHeightRadii: Float = 0.5f,
    /** ROLL ram: a dash [ramDashScale] x as long as [dashMs] (550 ms, 16.5 cm) hitting with [ramKnockScale] x the knockback. */
    val ramDashScale: Float = 2.2f,
    val ramKnockScale: Float = 1.5f,
    /** HIDE dodge: for [dodgeMs] hits pass through the fighter (a dash of its own ends the dodge at once). */
    val dodgeMs: Long = 500,
    /**
     * HIDE counter: a hit the dodge let through opens a window of [counterMs] from that sub-step; the first dash started
     * in it hits with [counterKnockScale] x. A dodge that dodged nothing gives no counter.
     */
    val counterMs: Long = 1500,
    val counterKnockScale: Float = 1.5f,

    // Wobble (T48): every hit a fighter takes in a round makes the next one push it farther, so a ring-out takes a few
    // hits on a large table without one hit being a ring-out on a small one. Dimensionless and per second, so they
    // follow the surface without being in [scaled]. wobblePerHit = 0 is the physics without it, exactly.

    /** Wobble a fighter gains with every hit it takes (a dash, a ram, a counter, a stomp; each side of a clash). */
    val wobblePerHit: Float = 0f,
    /** A hit on a fighter with wobble w knocks it (1 + [wobbleKnockScale] * w) x the plain knockback. */
    val wobbleKnockScale: Float = 1f,
    /** Wobble never exceeds this. */
    val wobbleMax: Float = 0f,
    /** Wobble lost per second of fight (0 = it stays until the round ends). */
    val wobbleDecayPerS: Float = 0f,
) {
    init {
        // T48 searches these numbers: refuse any set that could divide by zero, reverse a push or stun forever.
        require(subStepMs in 1L..8L) { "subStepMs must be 1..8 ms, got $subStepMs" }
        require(knockTauMs > 0L) { "knockTauMs must be > 0, got $knockTauMs" }
        require(dashMs >= 0L) { "dashMs must be >= 0, got $dashMs" }
        require(dashCooldownMs >= 0L) { "dashCooldownMs must be >= 0, got $dashCooldownMs" }
        require(hitStunMs >= 0L) { "hitStunMs must be >= 0, got $hitStunMs" }
        require(wallSlamStunMs >= 0L) { "wallSlamStunMs must be >= 0, got $wallSlamStunMs" }
        positive("duelSpeedScale", duelSpeedScale)
        positive("rollSpeedScale", rollSpeedScale)
        positive("fromAboveScale", fromAboveScale)
        positive("knockStopMps", knockStopMps)
        nonNegative("dashSpeedMps", dashSpeedMps)
        nonNegative("knockSpeedMps", knockSpeedMps)
        nonNegative("knockStunMps", knockStunMps)
        nonNegative("wallSlamMinMps", wallSlamMinMps)
        nonNegative("fromAboveMinM", fromAboveMinM)
        statFactor("speedBase + speedPerPoint * SPEED", speedBase, speedPerPoint)
        statFactor("powerBase + powerPerPoint * POWER", powerBase, powerPerPoint)
        statFactor("guardBase + guardPerPoint * GUARD", guardBase, guardPerPoint)
        statFactor("cooldownBase - cooldownPerAgility * AGILITY", cooldownBase, -cooldownPerAgility)
        statFactor("stunBase - stunPerAgility * AGILITY", stunBase, -stunPerAgility)
        require(specialCooldownMs >= 0L) { "specialCooldownMs must be >= 0, got $specialCooldownMs" }
        require(liftMs >= 0L) { "liftMs must be >= 0, got $liftMs" }
        require(dodgeMs >= 0L) { "dodgeMs must be >= 0, got $dodgeMs" }
        require(counterMs >= 0L) { "counterMs must be >= 0, got $counterMs" }
        nonNegative("stompRadiusRadii", stompRadiusRadii)
        positive("stompScale", stompScale)
        positive("liftSpeedScale", liftSpeedScale)
        // 0 would put a hovering body on the ground with no vertical speed, which Physics launches as a full jump.
        positive("liftHeightRadii", liftHeightRadii)
        positive("ramDashScale", ramDashScale)
        positive("ramKnockScale", ramKnockScale)
        positive("counterKnockScale", counterKnockScale)
        require((dashMs * ramDashScale).isFinite()) { "ramDashScale must be finite, got $ramDashScale" }
        for ((name, v) in listOf(
            "wobblePerHit" to wobblePerHit, "wobbleKnockScale" to wobbleKnockScale, "wobbleMax" to wobbleMax, "wobbleDecayPerS" to wobbleDecayPerS,
        )) require(v >= 0f && v.isFinite()) { "$name must be >= 0 and finite, got $v" }
    }

    /** The knockback factor of a hit on a fighter with [wobble]: `1 + wobbleKnockScale * wobble` (wobble clamped to 0..[wobbleMax]). */
    fun wobbleFactor(wobble: Float): Float = 1f + wobbleKnockScale * wobble.coerceIn(0f, wobbleMax)

    /** Walk speed of a fighter with [stats]: `physics.walkSpeedMps * duelSpeedScale * (speedBase + speedPerPoint * SPEED)`. */
    fun walkSpeedMpsFor(stats: FighterStats, physics: PhysicsParams = PhysicsParams.DEFAULT): Float =
        physics.walkSpeedMps * duelSpeedScale * (speedBase + speedPerPoint * clampStat(stats.speed))

    /** Dash cooldown of a fighter with [stats], counted from the dash start (>= 0). */
    fun cooldownMsFor(stats: FighterStats): Long =
        (dashCooldownMs * (cooldownBase - cooldownPerAgility * clampStat(stats.agility))).roundToLong().coerceAtLeast(0L)

    /** Special cooldown of a fighter with [stats], counted from the special's start (>= 0). */
    fun specialCooldownMsFor(stats: FighterStats): Long =
        (specialCooldownMs * (cooldownBase - cooldownPerAgility * clampStat(stats.agility))).roundToLong().coerceAtLeast(0L)

    /** How long a ROLL ram lasts: [dashMs] * [ramDashScale]. */
    val ramDashMs: Long get() = (dashMs * ramDashScale).roundToLong()

    /** Stun timer a hit gives a victim with [victim] stats (>= 0); it starts once the knockback is below [knockStunMps]. */
    fun hitStunMsFor(victim: FighterStats): Long =
        (hitStunMs * (stunBase - stunPerAgility * clampStat(victim.agility))).roundToLong().coerceAtLeast(0L)

    /**
     * Knockback speed an [attacker] gives a [victim]:
     * `knockSpeedMps * (powerBase + powerPerPoint * POWER) / (guardBase + guardPerPoint * GUARD)`, x [fromAboveScale]
     * when [fromAbove].
     */
    fun knockSpeedMpsFor(attacker: FighterStats, victim: FighterStats, fromAbove: Boolean = false): Float {
        val push = powerBase + powerPerPoint * clampStat(attacker.power)
        val guard = guardBase + guardPerPoint * clampStat(victim.guard)
        val v = knockSpeedMps * push / guard
        return if (fromAbove) v * fromAboveScale else v
    }

    /**
     * This set scaled for [profile] at character [size] ([CharacterSize]), like [PhysicsParams.forProfile]: speeds
     * scale with `profile.scale * size` (horizontal motion), [fromAboveMinM] with `profile.scale` only (heights stay the
     * surface's at any size). Times, stat factors and other dimensionless numbers stay.
     */
    fun scaled(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT): DuelParams {
        val horizontal = profile.scale * size
        return copy(
            dashSpeedMps = dashSpeedMps * horizontal,
            knockSpeedMps = knockSpeedMps * horizontal,
            knockStunMps = knockStunMps * horizontal,
            knockStopMps = knockStopMps * horizontal,
            wallSlamMinMps = wallSlamMinMps * horizontal,
            fromAboveMinM = fromAboveMinM * profile.scale,
        )
    }

    companion object {
        val DEFAULT = DuelParams()

        /** [DEFAULT] scaled for [profile] and [size]; `forProfile(SurfaceProfile.TABLE) == DEFAULT`. */
        fun forProfile(profile: SurfaceProfile, size: Float = CharacterSize.DEFAULT): DuelParams = DEFAULT.scaled(profile, size)

        fun forSurface(surface: Surface, size: Float = CharacterSize.DEFAULT): DuelParams =
            forProfile(SurfaceProfile.of(surface), size)

        private fun clampStat(v: Int): Float = v.coerceIn(FighterStats.MIN, FighterStats.MAX).toFloat()
    }
}

// Validation helpers for DuelParams.init (top level, so DEFAULT's construction never depends on the companion).
// NaN fails every check (comparisons with NaN are false).
private fun positive(name: String, v: Float) = require(v > 0f) { "$name must be > 0, got $v" }

private fun nonNegative(name: String, v: Float) = require(v >= 0f) { "$name must be >= 0, got $v" }

/** `base + perPoint * stat` is linear in the stat: > 0 at both ends means > 0 for every stat. */
private fun statFactor(name: String, base: Float, perPoint: Float) {
    for (s in intArrayOf(FighterStats.MIN, FighterStats.MAX)) {
        val f = base + perPoint * s
        require(f > 0f) { "$name must be > 0 for every stat, got $f at $s" }
    }
}
