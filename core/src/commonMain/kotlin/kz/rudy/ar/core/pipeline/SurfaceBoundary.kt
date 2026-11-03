package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.convexHull
import kz.rudy.ar.core.physics.Physics

/**
 * [Level.boundary] as the surface known so far, not ARCore's latest plane polygon.
 *
 * The polygon only covers what has been detected: it can end mid-table and it can shift or shrink between frames, so
 * being outside it says nothing about a real edge. The boundary is the convex hull of everything seen to be table: the
 * polygon at each scan, the spawn, the accepted tap targets and where the character stands. It never shrinks, and
 * because it is convex a straight walk between two points inside can never cross it. Past the hull is still a cliff.
 * A null boundary (the floor, or before the first table scan) stays null.
 *
 * The live polygon between scans does not grow it: on the Pixel it ran on past the end of the table, and in a hull that
 * never shrinks that overshoot stays. A tap target is enough, since it is all a walk needs ([crossesEdge] checks it).
 *
 * Scanned objects do not grow it: anything behind the table (a chair on the floor) projects onto the table plane far
 * past the edge, and the hull never shrinks back. [Physics.step] keeps obstacles near the rim from pushing anyone off.
 */
object SurfaceBoundary {
    /**
     * Half-size of the square kept around a point the character stands on. `Polygon.contains` is false on some edges
     * and vertices, and a tap target that became a bare hull vertex would read as a cliff on arrival.
     */
    const val STAND_MARGIN_M = 0.02f

    /** Growth below this (m²) is not a change: a repeated tap lands on its own hull vertices. */
    private const val MIN_GROWTH_M2 = 1e-6f

    /**
     * How far below the plane the Depth API must see a surface for it not to be the table, metres on the table (scaled
     * by [SurfaceProfile.scale]). On the Pixel a tap past the end of the table read -29 cm, 22 others -7.5 to +13 cm.
     */
    const val EDGE_DROP_M = 0.15f

    /** [level] with [standing] (accepted taps) inside; the same instance if nothing grew. */
    fun grown(level: Level, standing: List<Vec2>): Level {
        val boundary = level.boundary ?: return level
        val outside = standing.flatMap(::square).filterNot(boundary::contains)
        if (outside.isEmpty()) return level
        val hull = hullOf(boundary, outside)
        return if (hull.area - boundary.area < MIN_GROWTH_M2) level else level.copy(boundary = hull)
    }

    /**
     * True when a depth reading on the way to a tap, as height above the plane (null = none there), shows no table:
     * ARCore's polygon runs past the real edge and across a gap to another surface at the same height.
     */
    fun crossesEdge(depthGapsM: List<Float?>, profile: SurfaceProfile): Boolean =
        depthGapsM.any { it != null && it < -EDGE_DROP_M * profile.scale }

    /**
     * The level to swap in when a scan returns: [scanned]'s polygon joined with [current]'s boundary, the spawn and the
     * [state] the character is in right now, so a rescan can never drop it. A character already over a cliff of
     * [current] is falling and does not pull the boundary after it.
     */
    fun afterScan(scanned: Level, current: Level, state: CharacterState): Level {
        val base = scanned.boundary ?: current.boundary ?: return scanned
        val standing = buildList {
            add(scanned.spawn)
            if (!Physics.isOverCliff(current, state.pos)) {
                add(state.pos)
                state.target?.let(::add)
            }
        }
        return scanned.copy(boundary = hullOf(base, current.boundary?.points.orEmpty() + standing.flatMap(::square)))
    }

    private fun hullOf(base: Polygon, more: List<Vec2>): Polygon =
        convexHull(base.points + more).takeIf { it.size >= 3 }?.let(::Polygon) ?: base

    private fun square(p: Vec2): List<Vec2> = listOf(
        Vec2(p.u - STAND_MARGIN_M, p.v - STAND_MARGIN_M),
        Vec2(p.u + STAND_MARGIN_M, p.v - STAND_MARGIN_M),
        Vec2(p.u + STAND_MARGIN_M, p.v + STAND_MARGIN_M),
        Vec2(p.u - STAND_MARGIN_M, p.v + STAND_MARGIN_M),
    )
}
