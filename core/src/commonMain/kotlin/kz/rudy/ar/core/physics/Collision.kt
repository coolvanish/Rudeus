package kz.rudy.ar.core.physics

import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2

/**
 * Closest point to [p] on the segment `[a, b]` (inclusive of both ends).
 * A degenerate segment (`a == b`) returns [a].
 */
fun closestPointOnSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
    val ab = b - a
    val len2 = ab.lengthSquared
    if (len2 < Vec2.EPS * Vec2.EPS) return a
    val t = ((p - a).dot(ab) / len2).coerceIn(0f, 1f)
    return a + ab * t
}

/**
 * Minimum displacement that moves a circle (`center`, `radius`) out of [polygon].
 *
 * - Returns `null` when the circle does not overlap the polygon (touching counts as no overlap).
 * - When the centre is outside the polygon but closer than [radius] to an edge, the circle is pushed
 *   straight away from the closest edge point by the penetration depth.
 * - When the centre is inside the polygon it is pushed out through the nearest edge: the displacement
 *   is `(distance to that edge + radius)` along the edge's outward normal.
 *
 * Works for either winding of [polygon] (uses the sign of [Polygon.signedArea] to orient normals).
 */
fun circlePolygonPushOut(center: Vec2, radius: Float, polygon: Polygon): Vec2? {
    var bestDist2 = Float.MAX_VALUE
    var bestPoint = center
    var bestEdgeA = center
    var bestEdgeB = center
    for ((a, b) in polygon.edges()) {
        val q = closestPointOnSegment(center, a, b)
        val d2 = (center - q).lengthSquared
        if (d2 < bestDist2) {
            bestDist2 = d2
            bestPoint = q
            bestEdgeA = a
            bestEdgeB = b
        }
    }
    val dist = kotlin.math.sqrt(bestDist2)
    val inside = polygon.contains(center)
    if (!inside && dist >= radius - OVERLAP_EPS) return null

    val outward: Vec2 = if (dist > Vec2.EPS) {
        val away = (center - bestPoint) / dist
        if (inside) -away else away
    } else {
        edgeOutwardNormal(bestEdgeA, bestEdgeB, polygon)
    }
    val magnitude = if (inside) dist + radius else radius - dist
    return outward * magnitude
}

/** Unit normal of edge (a, b) pointing away from the polygon interior. */
private fun edgeOutwardNormal(a: Vec2, b: Vec2, polygon: Polygon): Vec2 {
    val left = (b - a).perp().normalized()
    // For a counter-clockwise polygon the interior is on the left of every edge.
    return if (polygon.signedArea() > 0f) -left else left
}

private const val OVERLAP_EPS = 1e-6f
