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
 * The Play HUD's Bigger / Smaller choice: a multiplier on the [SurfaceProfile]'s character. The billboard, the collision
 * circle and the walking speed follow it (`PhysicsParams.forProfile`). Heights do not: what counts as a platform and how
 * high a jump goes stay the surface's, so the level plays by the same rules at any size. A fixed list of steps, so
 * repeated taps cannot drift; 1× is 6 cm on the table, so the table range is 3–18 cm.
 */
object CharacterSize {
    const val DEFAULT = 1f
    val STEPS = listOf(0.5f, 0.75f, DEFAULT, 1.5f, 2f, 3f)
    val MIN = STEPS.first()
    val MAX = STEPS.last()

    fun bigger(size: Float): Float = STEPS.firstOrNull { it > size } ?: MAX

    fun smaller(size: Float): Float = STEPS.lastOrNull { it < size } ?: MIN
}

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
    /** Collision circle radius of a character at [size] ([CharacterSize]). */
    fun radiusAt(size: Float): Float = radiusM * size

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
