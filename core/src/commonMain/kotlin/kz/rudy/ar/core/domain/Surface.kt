package kz.rudy.ar.core.domain

import kotlinx.serialization.Serializable

/**
 * Where the level lives. [TABLE] is the Day 1–5 plan (cups, books, a 6 cm character). [FLOOR] is the same game on a
 * floor, a carpet or the ground outside (grass, pavement): ARCore still finds a horizontal plane, only the scale
 * changes — a bigger character, faster walking, taller "platforms" (a shoe, a box). Chosen on the Scanning screen
 * before the anchor is placed; never per frame.
 */
@Serializable
enum class Surface { TABLE, FLOOR }

/**
 * Size parameters of one [Surface]. Everything in metres / m/s. :androidApp reads the character size and tap
 * tolerance; [kz.rudy.ar.core.physics.PhysicsParams.forSurface] reads the rest. All values are [scale] × the table
 * ones, so the table numbers stay the single source of truth.
 */
@Serializable
data class SurfaceProfile(
    val surface: Surface,
    /** Multiplier against the table profile (1.0 for the table). */
    val scale: Float,
    /** Billboard quad height. */
    val characterHeightM: Float,
    /** Collision circle radius ([CharacterState.radiusM]). */
    val radiusM: Float,
    /** A walk tap counts only when its hit is within this height of the anchor plane (RudeusArView). */
    val tapHeightToleranceM: Float,
    /** Objects at most this tall can be stepped onto (table: `Semantics.STEP_UP_MAX_M`). */
    val stepUpMaxM: Float,
    /** Objects at most this tall count as platforms, taller ones as obstacles. */
    val platformMaxHeightM: Float,
) {
    companion object {
        /** Floor profile = table × this. A 24 cm character walking 20 cm/s reads well from standing height. */
        const val FLOOR_SCALE = 4f

        val TABLE = SurfaceProfile(
            surface = Surface.TABLE,
            scale = 1f,
            characterHeightM = 0.06f,
            radiusM = 0.03f,
            tapHeightToleranceM = 0.03f,
            stepUpMaxM = Semantics.STEP_UP_MAX_M,
            platformMaxHeightM = Semantics.PLATFORM_MAX_HEIGHT_M,
        )

        val FLOOR = SurfaceProfile(
            surface = Surface.FLOOR,
            scale = FLOOR_SCALE,
            characterHeightM = TABLE.characterHeightM * FLOOR_SCALE,
            radiusM = TABLE.radiusM * FLOOR_SCALE,
            tapHeightToleranceM = TABLE.tapHeightToleranceM * FLOOR_SCALE,
            stepUpMaxM = TABLE.stepUpMaxM * FLOOR_SCALE,
            platformMaxHeightM = TABLE.platformMaxHeightM * FLOOR_SCALE,
        )

        fun of(surface: Surface): SurfaceProfile = when (surface) {
            Surface.TABLE -> TABLE
            Surface.FLOOR -> FLOOR
        }
    }
}
