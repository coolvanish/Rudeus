package kz.rudy.ar.core.physics

import kotlinx.serialization.Serializable
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile

/**
 * Tunable constants of the table physics. All distances are metres, speeds m/s, accelerations m/s².
 * The defaults are sized for a ~6 cm tall character on a real table; [forSurface] scales them for the floor.
 */
@Serializable
data class PhysicsParams(
    /** Horizontal walking speed. */
    val walkSpeedMps: Float = 0.05f,
    /** Downward acceleration while airborne (JUMP / default). */
    val gravityMps2: Float = 1.5f,
    /** Initial vertical velocity of a jump. Apex = v² / (2g) ≈ 8.3 cm with the defaults. */
    val jumpVelocityMps: Float = 0.5f,
    /** Gravity multiplier for [kz.rudy.ar.core.domain.Ability.FLY] (slower arcs that cross gaps). */
    val flyGravityScale: Float = 0.3f,
    /** Walk-speed multiplier for [kz.rudy.ar.core.domain.Ability.ROLL]. */
    val rollSpeedScale: Float = 2f,
    /**
     * Horizontal speed multiplier while [kz.rudy.ar.core.domain.Mode.JUMPING]. A jump carries the character
     * forward faster than a walk: with the defaults a 0.67 s hop spans ~6.6 cm, enough for the 6 cm wide
     * collision circle to cross a low object (at plain walk speed it would span only 3.3 cm).
     */
    val jumpSpeedScale: Float = 2f,
    /** Distance to the target at which the walk is considered complete. */
    val arriveEpsilonM: Float = 0.005f,
    /** When a falling character's feet go below this height it respawns. */
    val respawnBelowH: Float = -0.05f,
    /** Maximum number of push-out passes per step (several obstacles can overlap the circle). */
    val pushOutPasses: Int = 4,
    /**
     * A [kz.rudy.ar.core.domain.ObjectKind.PLATFORM] whose top is at most this far above the feet is stepped onto
     * while walking; a taller one blocks like an obstacle. Table: [Semantics.STEP_UP_MAX_M].
     */
    val stepUpMaxM: Float = Semantics.STEP_UP_MAX_M,
    /**
     * Obstacles no taller than this can be landed on from above (a jump "clears" them); taller ones are never stood
     * on. Table: [Semantics.PLATFORM_MAX_HEIGHT_M].
     */
    val platformMaxHeightM: Float = Semantics.PLATFORM_MAX_HEIGHT_M,
) {
    companion object {
        val DEFAULT = PhysicsParams()

        /**
         * Parameters for a [Surface]: the table defaults scaled by [SurfaceProfile.scale]. Speeds and distances scale
         * linearly; gravity scales too, so a jump apex (v² / 2g) and its duration keep the same look at any size.
         * `forSurface(Surface.TABLE) == DEFAULT`.
         */
        fun forSurface(surface: Surface): PhysicsParams = forProfile(SurfaceProfile.of(surface))

        /**
         * [DEFAULT] scaled by [SurfaceProfile.scale]; the step-up and platform heights are taken from the profile
         * as they are (they are already scaled there). Dimensionless multipliers and [pushOutPasses] stay.
         */
        fun forProfile(profile: SurfaceProfile): PhysicsParams {
            val k = profile.scale
            return PhysicsParams(
                walkSpeedMps = DEFAULT.walkSpeedMps * k,
                gravityMps2 = DEFAULT.gravityMps2 * k,
                jumpVelocityMps = DEFAULT.jumpVelocityMps * k,
                arriveEpsilonM = DEFAULT.arriveEpsilonM * k,
                respawnBelowH = DEFAULT.respawnBelowH * k,
                stepUpMaxM = profile.stepUpMaxM,
                platformMaxHeightM = profile.platformMaxHeightM,
            )
        }
    }
}
