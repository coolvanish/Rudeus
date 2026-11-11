package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.physics.circlePolygonPushOut
import kz.rudy.ar.core.physics.closestPointOnSegment
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a duel is fought: the level with an edge, and the two marks the fighters start every round on. */
object DuelArena {
    /** Segments of the ring a level without a boundary gets. */
    const val RING_SEGMENTS = 16

    /** Marks found by [marks] keep at least this many fighter radii from the boundary and from every CLIFF hole. */
    const val EDGE_MARGIN_RADII = 2f

    /** Marks are never closer than this many fighter radii, centre to centre (one radius of table between the circles). */
    const val MIN_SEPARATION_RADII = 3f

    /** The [marks] search grid's step, in fighter radii, while the board holds at most [MAX_GRID_POINTS] of them. */
    const val GRID_STEP_RADII = 0.5f

    /** A bigger board gets a coarser grid (25 % steps) so that it never holds more than this many points. */
    const val MAX_GRID_POINTS = 1600

    /** Two marks stand at the same ground height when their heights differ by no more than this. */
    const val SAME_HEIGHT_M = 0.001f

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
     * Two marks facing each other for fighters of [radiusM]: both free (see below), at the same ground height (within
     * [SAME_HEIGHT_M]), at least [MIN_SEPARATION_RADII] radii apart. Null only when no such pair exists among the points
     * of the search grid; whether marks exist does not depend on [separationM].
     *
     * A point is free when it has ground ([Physics.groundHeight] from the table), keeps [EDGE_MARGIN_RADII] radii from
     * every boundary edge and every hole, and its circle is clear of every object that [Physics.blocks] at that height.
     *
     * **Search.** The grid is anchored at the spawn and runs along the boundary's long axis (its principal axis of area;
     * u for a square or a circle) and across it, every [GRID_STEP_RADII] radii (1.5 cm for a table fighter), or coarser
     * on a board whose bounding box would hold more than [MAX_GRID_POINTS] points (a 100 x 80 cm table: 2.3 cm). So any
     * free patch holding a disk of half a step x sqrt 2 holds a grid point. Added to the grid: the spawn and the
     * boundary's centroid each +- separation / 2 along and across the long axis (the ideal pairs, often off the grid).
     * Every pair of free candidates is a choice; the best by, in order: both on the table itself (h = 0) before both on
     * platforms of one height; separation closest to `max(separationM, MIN_SEPARATION_RADII radii)` (in grid steps);
     * midpoint closest to the spawn (in grid steps); closest to the long axis; then the exact separation and midpoint
     * errors. Cost: one free test per grid point and a scan of the pairs, at most about 1.3 million (tens of ms on the
     * JVM, measured in `DuelArenaTest`). The first mark is the one lower along the long axis.
     */
    fun marks(
        arena: Level,
        radiusM: Float,
        separationM: Float,
        physics: PhysicsParams = PhysicsParams.DEFAULT,
    ): Pair<Vec2, Vec2>? {
        val boundary = requireNotNull(arena.boundary) { NO_BOUNDARY }
        require(radiusM > 0f && radiusM.isFinite()) { "radiusM must be > 0 and finite, got $radiusM" }
        require(separationM.isFinite()) { "separationM must be finite, got $separationM" }
        val least = MIN_SEPARATION_RADII * radiusM
        val wanted = max(separationM, least)
        val margin = EDGE_MARGIN_RADII * radiusM
        val axis = longAxis(boundary)
        val side = axis.perp()
        val origin = arena.spawn
        val box = frameBox(boundary, origin, axis, side)
        val step = gridStep(box, radiusM)
        val i0 = ceil(box[0] / step).toInt()
        val i1 = floor(box[1] / step).toInt()
        val j0 = ceil(box[2] / step).toInt()
        val j1 = floor(box[3] / step).toInt()
        val capacity = (i1 - i0 + 1).coerceAtLeast(0) * (j1 - j0 + 1).coerceAtLeast(0) + 8
        val us = FloatArray(capacity)
        val vs = FloatArray(capacity)
        val hs = FloatArray(capacity)
        val onGrid = BooleanArray(capacity)
        var n = 0
        fun add(p: Vec2, grid: Boolean) {
            val h = freeHeight(arena, p, radiusM, margin, physics) ?: return
            us[n] = p.u
            vs[n] = p.v
            hs[n] = h
            onGrid[n] = grid
            n++
        }
        for (i in i0..i1) for (j in j0..j1) add(origin + axis * (i * step) + side * (j * step), grid = true)
        val centroid = boundary.centroid()
        for (c in listOf(origin, centroid)) {
            for (d in listOf(axis, side)) {
                add(c - d * (wanted / 2f), grid = false)
                add(c + d * (wanted / 2f), grid = false)
            }
        }

        val least2 = least * least
        var anyGrid = false
        var best = -1 to -1
        var bHigh = Int.MAX_VALUE
        var bSep = Int.MAX_VALUE
        var bMid = Int.MAX_VALUE
        var bTurn = Float.MAX_VALUE
        var bSepErr = Float.MAX_VALUE
        var bMidErr = Float.MAX_VALUE
        for (a in 0 until n) {
            for (b in a + 1 until n) {
                val du = us[b] - us[a]
                val dv = vs[b] - vs[a]
                val d2 = du * du + dv * dv
                if (d2 < least2 || abs(hs[a] - hs[b]) > SAME_HEIGHT_M) continue
                if (onGrid[a] && onGrid[b]) anyGrid = true
                val high = if (abs(hs[a]) <= SAME_HEIGHT_M && abs(hs[b]) <= SAME_HEIGHT_M) 0 else 1
                if (high > bHigh) continue
                val d = sqrt(d2)
                val sepErr = abs(d - wanted)
                val sep = (sepErr / step).toInt()
                if (high == bHigh && sep > bSep) continue
                val mu = (us[a] + us[b]) * 0.5f - origin.u
                val mv = (vs[a] + vs[b]) * 0.5f - origin.v
                val midErr = sqrt(mu * mu + mv * mv)
                val mid = (midErr / step).toInt()
                if (high == bHigh && sep == bSep && mid > bMid) continue
                val turn = abs(du * axis.v - dv * axis.u) / d
                val better = when {
                    high != bHigh -> high < bHigh
                    sep != bSep -> sep < bSep
                    mid != bMid -> mid < bMid
                    turn != bTurn -> turn < bTurn
                    sepErr != bSepErr -> sepErr < bSepErr
                    else -> midErr < bMidErr
                }
                if (!better) continue
                best = a to b
                bHigh = high; bSep = sep; bMid = mid; bTurn = turn; bSepErr = sepErr; bMidErr = midErr
            }
        }
        if (!anyGrid || best.first < 0) return null
        val p = Vec2(us[best.first], vs[best.first])
        val q = Vec2(us[best.second], vs[best.second])
        val xp = (p - origin).dot(axis)
        val xq = (q - origin).dot(axis)
        val pFirst = xp < xq || (xp == xq && (p - origin).dot(side) <= (q - origin).dot(side))
        return if (pFirst) p to q else q to p
    }

    /** Signed distance from [p] to the nearest edge of [boundary]: > 0 inside, < 0 outside. */
    fun insideDistance(boundary: Polygon, p: Vec2): Float {
        val d = distanceToEdges(p, boundary)
        return if (boundary.contains(p)) d else -d
    }

    /**
     * Why [marks] cannot start a match for fighters of [radiusM] on [arena], or null when they can: the arena has a
     * boundary; each mark is a free spot with a margin of one radius (its whole circle on the table, off every hole,
     * clear of what blocks at its ground height); the two circles do not overlap; both stand at the same ground height
     * (within [SAME_HEIGHT_M]: no fighter starts with the high ground). [DuelMatch.start] and [DuelMatch.rearrange]
     * require it.
     */
    fun startProblem(arena: Level, marks: Pair<Vec2, Vec2>, radiusM: Float, physics: PhysicsParams = PhysicsParams.DEFAULT): String? {
        if (arena.boundary == null) return NO_BOUNDARY
        if (!(radiusM > 0f && radiusM.isFinite())) return "radiusM must be > 0 and finite, got $radiusM"
        val (a, b) = marks
        val ha = freeHeight(arena, a, radiusM, radiusM, physics)
            ?: return "the first mark $a is not a free spot for a fighter of radius $radiusM m (whole circle on the table, off holes, clear of what blocks)"
        val hb = freeHeight(arena, b, radiusM, radiusM, physics)
            ?: return "the second mark $b is not a free spot for a fighter of radius $radiusM m (whole circle on the table, off holes, clear of what blocks)"
        if (a.distanceTo(b) < 2f * radiusM) return "the marks $a and $b are closer than two radii: the fighters would overlap"
        if (abs(ha - hb) > SAME_HEIGHT_M) return "the marks stand at different heights ($ha m and $hb m): one fighter would start with the high ground"
        return null
    }

    private const val NO_BOUNDARY = "the arena has no boundary: build it with DuelArena.arena(level, ringRadiusM) first"

    /** The ground height at [p] when it is free for a circle of [r] with [margin] to the edges and holes; else null. */
    private fun freeHeight(arena: Level, p: Vec2, r: Float, margin: Float, physics: PhysicsParams): Float? {
        if (!p.u.isFinite() || !p.v.isFinite()) return null
        val boundary = arena.boundary ?: return null
        if (!boundary.contains(p) || distanceToEdges(p, boundary) < margin) return null
        val h = Physics.groundHeight(arena, p, 0f, physics) ?: return null
        for (o in arena.objects) {
            val fp = o.footprint
            if (o.kind == ObjectKind.CLIFF) {
                if (fp.contains(p) || distanceToEdges(p, fp) < margin) return null
            } else if (Physics.blocks(o, h, physics) && (fp.contains(p) || circlePolygonPushOut(p, r, fp) != null)) {
                return null
            }
        }
        return h
    }

    /** [boundary]'s box in the search frame (origin, along [axis], along [side]): minX, maxX, minY, maxY. */
    private fun frameBox(boundary: Polygon, origin: Vec2, axis: Vec2, side: Vec2): FloatArray {
        val box = floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE)
        for (p in boundary.points) {
            val x = (p - origin).dot(axis)
            val y = (p - origin).dot(side)
            box[0] = minOf(box[0], x); box[1] = maxOf(box[1], x)
            box[2] = minOf(box[2], y); box[3] = maxOf(box[3], y)
        }
        return box
    }

    /** [GRID_STEP_RADII] radii, coarser by 25 % steps while [box] would hold more than [MAX_GRID_POINTS] grid points. */
    private fun gridStep(box: FloatArray, r: Float): Float {
        var step = GRID_STEP_RADII * r
        while (((box[1] - box[0]) / step + 1f) * ((box[3] - box[2]) / step + 1f) > MAX_GRID_POINTS) step *= 1.25f
        return step
    }

    /**
     * The long axis of [boundary]: the major principal axis of its area (second moments about the centroid), as a unit
     * vector with u >= 0. A shape without one (a square, a circle) gets u; an axis within 1e-4 of u or v is snapped to it.
     */
    internal fun longAxis(boundary: Polygon): Vec2 {
        val c = boundary.centroid()
        val pts = boundary.points
        var area = 0.0
        var suu = 0.0
        var svv = 0.0
        var suv = 0.0
        for (i in pts.indices) {
            val p = pts[i] - c
            val q = pts[(i + 1) % pts.size] - c
            val pu = p.u.toDouble(); val pv = p.v.toDouble(); val qu = q.u.toDouble(); val qv = q.v.toDouble()
            val cr = pu * qv - qu * pv
            area += cr
            suu += cr * (pu * pu + pu * qu + qu * qu)
            svv += cr * (pv * pv + pv * qv + qv * qv)
            suv += cr * (pu * qv + 2.0 * pu * pv + 2.0 * qu * qv + qu * pv)
        }
        val sign = if (area < 0.0) -1.0 else 1.0
        val uu = sign * suu / 12.0
        val vv = sign * svv / 12.0
        val uv = sign * suv / 24.0
        val spread = sqrt((uu - vv) * (uu - vv) + 4.0 * uv * uv)
        if (!(spread > 1e-3 * (uu + vv))) return Vec2(1f, 0f)
        val theta = 0.5 * atan2(2.0 * uv, uu - vv)
        val x = cos(theta)
        val y = sin(theta)
        return when {
            abs(y) < 1e-4 -> Vec2(1f, 0f)
            abs(x) < 1e-4 -> Vec2(0f, 1f)
            x < 0.0 -> Vec2(-x.toFloat(), -y.toFloat())
            else -> Vec2(x.toFloat(), y.toFloat())
        }
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
}
