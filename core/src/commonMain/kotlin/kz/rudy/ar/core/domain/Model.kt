package kz.rudy.ar.core.domain

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * Coordinate system (locked decision, see ai/build-plan.md):
 * the level lives in the detected table plane. `u`/`v` are metres along the plane's
 * local X/Z axes measured from the session anchor; `h` is metres above the plane.
 * ARCore types never appear in :core — :androidApp converts (u, v, h) <-> Pose.
 */
@Serializable
data class Vec2(val u: Float, val v: Float) {
    operator fun plus(o: Vec2) = Vec2(u + o.u, v + o.v)
    operator fun minus(o: Vec2) = Vec2(u - o.u, v - o.v)
    operator fun times(s: Float) = Vec2(u * s, v * s)
    operator fun div(s: Float) = Vec2(u / s, v / s)
    operator fun unaryMinus() = Vec2(-u, -v)
    fun dot(o: Vec2) = u * o.u + v * o.v
    /** 2D cross product (z component). */
    fun cross(o: Vec2) = u * o.v - v * o.u
    val length: Float get() = sqrt(u * u + v * v)
    val lengthSquared: Float get() = u * u + v * v
    fun normalized(): Vec2 = if (length < EPS) ZERO else this / length
    fun distanceTo(o: Vec2) = (this - o).length
    /** Perpendicular (rotated +90°). */
    fun perp() = Vec2(-v, u)

    companion object {
        val ZERO = Vec2(0f, 0f)
        const val EPS = 1e-6f
    }
}

/** Simple polygon in plane coordinates. Winding may be either direction; consumers must not assume one. */
@Serializable
data class Polygon(val points: List<Vec2>) {
    init { require(points.size >= 3) { "Polygon needs >= 3 points, got ${points.size}" } }

    val size: Int get() = points.size

    /** Edges as (a, b) pairs, closing back to the first point. */
    fun edges(): List<Pair<Vec2, Vec2>> = points.indices.map { i -> points[i] to points[(i + 1) % points.size] }

    /** Signed area; positive for counter-clockwise in (u, v). */
    fun signedArea(): Float {
        var a = 0f
        for ((p, q) in edges()) a += p.cross(q)
        return a / 2f
    }

    val area: Float get() = kotlin.math.abs(signedArea())

    fun centroid(): Vec2 {
        val a = signedArea()
        if (kotlin.math.abs(a) < Vec2.EPS) {
            // Degenerate: fall back to vertex mean.
            return points.fold(Vec2.ZERO) { acc, p -> acc + p } / points.size.toFloat()
        }
        var cu = 0f; var cv = 0f
        for ((p, q) in edges()) {
            val c = p.cross(q)
            cu += (p.u + q.u) * c
            cv += (p.v + q.v) * c
        }
        return Vec2(cu / (6f * a), cv / (6f * a))
    }

    /** Point-in-polygon (even-odd ray cast). Boundary points are treated as inside. */
    fun contains(p: Vec2): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val pi = points[i]; val pj = points[j]
            if ((pi.v > p.v) != (pj.v > p.v)) {
                val x = (pj.u - pi.u) * (p.v - pi.v) / (pj.v - pi.v) + pi.u
                if (p.u < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        fun rect(center: Vec2, width: Float, depth: Float): Polygon {
            val hw = width / 2f; val hd = depth / 2f
            return Polygon(
                listOf(
                    Vec2(center.u - hw, center.v - hd),
                    Vec2(center.u + hw, center.v - hd),
                    Vec2(center.u + hw, center.v + hd),
                    Vec2(center.u - hw, center.v + hd),
                )
            )
        }

        fun circle(center: Vec2, radius: Float, segments: Int = 16): Polygon = Polygon(
            (0 until segments).map { i ->
                val t = (2.0 * kotlin.math.PI * i / segments)
                Vec2(center.u + radius * kotlin.math.cos(t).toFloat(), center.v + radius * kotlin.math.sin(t).toFloat())
            }
        )
    }
}

/** What a scanned table object means to the game. */
@Serializable
enum class ObjectKind {
    /** Cup, bottle: solid, taller than a step. Character is pushed out. */
    OBSTACLE,
    /** Book, phone (a box on the floor): walkable if its top is <= the profile's platform height (table: [Semantics.PLATFORM_MAX_HEIGHT_M]). */
    PLATFORM,
    /** Hand: moves between scans; treated as an obstacle that can be re-placed. */
    DYNAMIC,
    /** Edge of the detected plane: stepping over it = fall + respawn. */
    CLIFF,
}

/** One segmented table object projected onto the plane. */
@Serializable
data class LevelObject(
    /** Stable across re-scans (matched by centroid, see SceneBuilder). */
    val id: String,
    /** SAM prompt that produced it: "cup", "book", "phone", "bottle", "hand", "object". */
    val label: String,
    val kind: ObjectKind,
    val footprint: Polygon,
    /** Height of the object's top above the plane, metres. */
    val heightM: Float,
) {
    val centroid: Vec2 get() = footprint.centroid()
}

/** The table as a level. */
@Serializable
data class Level(
    val objects: List<LevelObject>,
    /** Detected plane polygon. Outside it is a cliff. Null = unbounded (2D fallback mode). */
    val boundary: Polygon? = null,
    /** Spawn / respawn point in plane coordinates. */
    val spawn: Vec2 = Vec2.ZERO,
)

/** Character abilities from the Spark character sheet. Exactly one per character. */
@Serializable
enum class Ability { JUMP, FLY, ROLL, HIDE }

/** Output of the "character sheet" model call. */
@Serializable
data class CharacterSheet(
    val name: String,
    val personality: String,
    val ability: Ability,
    /** Language code ("kk", "ru") -> 5 short lines the character says. */
    val lines: Map<String, List<String>>,
)

/** Character finite-state machine modes. */
@Serializable
enum class Mode { IDLE, WALKING, JUMPING, FALLING, RESPAWNING, PAUSED }

/** Everything the renderer needs each frame. Pure data; mutated only by :core physics. */
@Serializable
data class CharacterState(
    val pos: Vec2 = Vec2.ZERO,
    /** Height of the character's feet above the plane, metres. */
    val h: Float = 0f,
    val target: Vec2? = null,
    val mode: Mode = Mode.IDLE,
    val ability: Ability = Ability.JUMP,
    /** Collision circle radius, metres. */
    val radiusM: Float = 0.03f,
    /** Facing direction in the plane (unit vector) for the Y-only billboard flip. */
    val facing: Vec2 = Vec2(1f, 0f),
)

/** Label -> ObjectKind mapping and the thresholds that make the table a level. */
object Semantics {
    /** Books/phones above this are too tall to step on; they become obstacles. */
    const val PLATFORM_MAX_HEIGHT_M = 0.04f

    /** Height the character can step up without jumping. */
    const val STEP_UP_MAX_M = 0.04f

    /** Flat things a character can stand on when low enough. "box" is for the floor (a shoebox); the table scan never prompts it. */
    private val platformLabels = setOf("book", "phone", "notebook", "card", "paper", "box")
    private val dynamicLabels = setOf("hand", "finger")

    /**
     * What a scanned object means at the scale of [profile]: a hand is [ObjectKind.DYNAMIC]; a platform label no
     * taller than [SurfaceProfile.platformMaxHeightM] (4 cm on the table, 16 cm on the floor) is a
     * [ObjectKind.PLATFORM]; anything else is an [ObjectKind.OBSTACLE].
     */
    fun kindFor(label: String, heightM: Float, profile: SurfaceProfile = SurfaceProfile.TABLE): ObjectKind = when {
        label.lowercase() in dynamicLabels -> ObjectKind.DYNAMIC
        label.lowercase() in platformLabels && heightM <= profile.platformMaxHeightM -> ObjectKind.PLATFORM
        else -> ObjectKind.OBSTACLE
    }
}
