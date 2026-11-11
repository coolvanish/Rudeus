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
) {
    init {
        require(subStepMs in 1L..8L) { "subStepMs must be 1..8 ms, got $subStepMs" }
        require(knockTauMs > 0L) { "knockTauMs must be > 0, got $knockTauMs" }
        require(dashMs >= 0L && dashCooldownMs >= 0L && hitStunMs >= 0L && wallSlamStunMs >= 0L) { "durations must be >= 0" }
    }

    /** Walk speed of a fighter with [stats]: `physics.walkSpeedMps * duelSpeedScale * (speedBase + speedPerPoint * SPEED)`. */
    fun walkSpeedMpsFor(stats: FighterStats, physics: PhysicsParams = PhysicsParams.DEFAULT): Float =
        physics.walkSpeedMps * duelSpeedScale * (speedBase + speedPerPoint * clampStat(stats.speed))

    /** Dash cooldown of a fighter with [stats], counted from the dash start (>= 0). */
    fun cooldownMsFor(stats: FighterStats): Long =
        (dashCooldownMs * (cooldownBase - cooldownPerAgility * clampStat(stats.agility))).roundToLong().coerceAtLeast(0L)

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
