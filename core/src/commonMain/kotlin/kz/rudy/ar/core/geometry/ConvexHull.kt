package kz.rudy.ar.core.geometry

import kz.rudy.ar.core.domain.Vec2

/**
 * Convex hull of [points], counter-clockwise (Andrew's monotone chain). Duplicates and points on a hull edge are
 * dropped. Fewer than 3 points come back when the input is degenerate (all collinear, or fewer than 3 distinct points).
 */
fun convexHull(points: List<Vec2>): List<Vec2> {
    val sorted = points.distinct().sortedWith(compareBy({ it.u }, { it.v }))
    if (sorted.size < 3) return sorted
    val lower = halfHull(sorted)
    val upper = halfHull(sorted.asReversed())
    return lower.dropLast(1) + upper.dropLast(1)
}

private fun halfHull(sorted: List<Vec2>): List<Vec2> {
    val chain = ArrayList<Vec2>()
    for (p in sorted) {
        while (chain.size >= 2 && (chain[chain.size - 1] - chain[chain.size - 2]).cross(p - chain[chain.size - 2]) <= 0f) {
            chain.removeAt(chain.size - 1)
        }
        chain.add(p)
    }
    return chain
}
