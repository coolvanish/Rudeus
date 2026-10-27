package kz.rudy.ar.core.physics

import kotlinx.serialization.Serializable

/**
 * Tunable constants of the table physics. All distances are metres, speeds m/s, accelerations m/s².
 * The defaults are sized for a ~6 cm tall character on a real table.
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
) {
    companion object {
        val DEFAULT = PhysicsParams()
    }
}
