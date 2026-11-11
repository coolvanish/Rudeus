package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.physics.circlePolygonPushOut
import kz.rudy.ar.core.physics.closestPointOnSegment
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Where a duel is fought: the level with an edge, and the two marks the fighters start every round on. */
object DuelArena {
    /** Segments of the ring a level without a boundary gets. */
    const val RING_SEGMENTS = 16

    /** Marks keep at least this many fighter radii from the boundary and from every CLIFF hole. */
    const val EDGE_MARGIN_RADII = 2f

    /** Marks are never closer than this many fighter radii, centre to centre (one radius of table between the circles). */
    const val MIN_SEPARATION_RADII = 3f

    /** Separations tried shrink by this many radii at a time, from the one asked for down to [MIN_SEPARATION_RADII]. */
    const val SEPARATION_STEP_RADII = 0.25f

    /**
     * The level a duel is fought on: [level] itself when it has a boundary, else a copy with a circular ring
     * ([RING_SEGMENTS] segments, [Polygon.circle]) of [ringRadiusM] around the spawn, so a floor duel has an edge too.
     */
    fun arena(level: Level, ringRadiusM: Float): Level {
        if (level.boundary != null) return level
        require(ringRadiusM > 0f && ringRadiusM.isFinite()) { "ringRadiusM must be > 0 and finite, got $ringRadiusM" }
        return level.copy(boundary = Polygon.circle(level.spawn, ringRadiusM, RING_SEGMENTS))
    }

    /**
     * Two marks facing each other across the spawn: free of blocking objects, inside the boundary with a margin of
     * [EDGE_MARGIN_RADII] radii (holes count as edges), as far apart as [separationM] allows; null when the level has no
     * room for two fighters. The marks are `centre -+ dir * separation / 2`. Searched in this order, the first pair that
     * fits wins: separations from `max(separationM, MIN_SEPARATION_RADII radii)` down to the minimum in steps of
     * [SEPARATION_STEP_RADII] radii; for each, the spawn and then the boundary's centroid as the centre; for each,
     * directions from +u turning by 22.5 degrees (+22.5 before -22.5) up to +v. The first mark is on the -dir side.
     * A mark fits when its centre has ground ([Physics.groundHeight] from the table), is that far from every boundary
     * edge and hole, and its circle is clear of every object that [Physics.blocks] at that ground height.
     */
    fun marks(
        arena: Level,
        radiusM: Float,
        separationM: Float,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): Pair<Vec2, Vec2>? {
        require(radiusM > 0f && radiusM.isFinite()) { "radiusM must be > 0 and finite, got $radiusM" }
        require(!separationM.isNaN()) { "separationM must not be NaN" }
        val least = MIN_SEPARATION_RADII * radiusM
        val step = SEPARATION_STEP_RADII * radiusM
        val centres = buildList {
            add(arena.spawn)
            val c = arena.boundary?.centroid()
            if (c != null && c != arena.spawn && c.u.isFinite() && c.v.isFinite()) add(c)
        }
        var sep = if (separationM.isFinite()) max(separationM, least) else least
        while (true) {
            for (centre in centres) {
                for (k in DIRS_COS.indices) {
                    val half = Vec2(DIRS_COS[k], DIRS_SIN[k]) * (sep / 2f)
                    val first = centre - half
                    val second = centre + half
                    if (fits(arena, first, radiusM, physics) && fits(arena, second, radiusM, physics)) return first to second
                }
            }
            if (sep <= least) return null
            sep = max(least, sep - step)
        }
    }

    /** Signed distance from [p] to the nearest edge of [boundary]: > 0 inside, < 0 outside. */
    fun insideDistance(boundary: Polygon, p: Vec2): Float {
        val d = distanceToEdges(p, boundary)
        return if (boundary.contains(p)) d else -d
    }

    private fun fits(arena: Level, p: Vec2, r: Float, physics: PhysicsParams): Boolean {
        if (!p.u.isFinite() || !p.v.isFinite()) return false
        val margin = EDGE_MARGIN_RADII * r
        val boundary = arena.boundary
        if (boundary != null && (!boundary.contains(p) || distanceToEdges(p, boundary) < margin)) return false
        val h = Physics.groundHeight(arena, p, 0f, physics) ?: return false
        for (o in arena.objects) {
            val fp = o.footprint
            if (o.kind == ObjectKind.CLIFF) {
                if (fp.contains(p) || distanceToEdges(p, fp) < margin) return false
            } else if (Physics.blocks(o, h, physics) && (fp.contains(p) || circlePolygonPushOut(p, r, fp) != null)) {
                return false
            }
        }
        return true
    }

    private fun distanceToEdges(p: Vec2, poly: Polygon): Float {
        var best = Float.MAX_VALUE
        val pts = poly.points
        for (i in pts.indices) {
            val d = p.distanceTo(closestPointOnSegment(p, pts[i], pts[(i + 1) % pts.size]))
            if (d < best) best = d
        }
        return best
    }

    // 0, +22.5, -22.5, +45, -45, +67.5, -67.5, +90 degrees: a pair and its 180-degree turn are the same two points.
    private val DIRS_DEG = doubleArrayOf(0.0, 22.5, -22.5, 45.0, -45.0, 67.5, -67.5, 90.0)
    private val DIRS_COS = FloatArray(DIRS_DEG.size) { cos(DIRS_DEG[it] * PI / 180.0).toFloat() }
    private val DIRS_SIN = FloatArray(DIRS_DEG.size) { sin(DIRS_DEG[it] * PI / 180.0).toFloat() }
}
