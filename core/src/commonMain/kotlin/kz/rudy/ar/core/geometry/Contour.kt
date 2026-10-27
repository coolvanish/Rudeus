package kz.rudy.ar.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/** A point in image pixel coordinates (x right, y down). Contour points are pixel centres (i + 0.5, j + 0.5). */
@Serializable
data class PixelPoint(val x: Float, val y: Float) {
    fun distanceTo(o: PixelPoint): Float {
        val dx = x - o.x; val dy = y - o.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** Neighbour offsets in clockwise order on screen (y down), starting west. */
private val DX = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
private val DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

/**
 * Outer boundary of the largest 4-connected component of [mask], as an ordered closed polyline of pixel centres
 * (clockwise on screen). Moore-neighbour tracing with Jacob's stopping criterion. Returns an empty list for an
 * empty mask and a single point for a lone pixel.
 *
 * Pass [singleComponent] = true when [mask] is already the output of [BooleanMask.largestComponent] to skip the
 * (O(width*height)) component search.
 */
fun traceContour(mask: BooleanMask, singleComponent: Boolean = false): List<PixelPoint> {
    val m = if (singleComponent) mask else mask.largestComponent()
    val w = m.width; val h = m.height
    // Start pixel: first set pixel in raster order. Its west neighbour is unset by construction.
    var start = -1
    for (i in 0 until w * h) if (m.bits[i]) { start = i; break }
    if (start < 0) return emptyList()
    val sx = start % w; val sy = start / w
    val out = ArrayList<PixelPoint>()
    out.add(PixelPoint(sx + 0.5f, sy + 0.5f))

    var px = sx; var py = sy
    var backtrack = 0 // index into DX/DY, relative to p, of the last visited *unset* neighbour (west of start)
    var secondX = -1; var secondY = -1
    val maxSteps = 4 * (w * h) + 8
    var steps = 0
    while (steps++ < maxSteps) {
        var found = -1
        var k = backtrack
        for (n in 1..8) {
            k = (k + 1) and 7
            if (m[px + DX[k], py + DY[k]]) { found = k; break }
        }
        if (found < 0) break // isolated pixel
        val prev = (found + 7) and 7 // the unset neighbour visited just before `found` (8-adjacent to it)
        val nx = px + DX[found]; val ny = py + DY[found]
        // Jacob's stopping criterion: back at the start pixel and about to re-enter the second pixel.
        if (px == sx && py == sy && out.size > 1 && nx == secondX && ny == secondY) break
        if (out.size == 1 && secondX < 0) { secondX = nx; secondY = ny }
        val bx = px + DX[prev]; val by = py + DY[prev]
        px = nx; py = ny
        if (!(px == sx && py == sy)) out.add(PixelPoint(px + 0.5f, py + 0.5f))
        backtrack = relativeIndex(px, py, bx, by)
    }
    return out
}

/** Index in DX/DY of the offset from (px, py) to (qx, qy); the two pixels must be 8-neighbours. */
private fun relativeIndex(px: Int, py: Int, qx: Int, qy: Int): Int {
    val dx = qx - px; val dy = qy - py
    for (k in 0 until 8) if (DX[k] == dx && DY[k] == dy) return k
    return 0 // unreachable: consecutive Moore neighbours are always 8-adjacent
}

/** Perpendicular distance from [p] to the infinite line through [a] and [b] (segment length used if a == b). */
private fun lineDistance(p: PixelPoint, a: PixelPoint, b: PixelPoint): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1e-6f) return p.distanceTo(a)
    return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
}

/**
 * Ramer–Douglas–Peucker simplification of a **closed** polyline (the last point connects back to the first).
 * The ring is split at point 0 and at the point farthest from it, both halves are simplified with tolerance
 * [epsilonPx], then a closing pass drops vertices that lie within [epsilonPx] of the chord between their neighbours
 * (this removes point 0 when it happens to sit on a straight edge). Fewer than 3 input points are returned as is.
 */
fun douglasPeucker(points: List<PixelPoint>, epsilonPx: Float): List<PixelPoint> {
    if (points.size < 3) return points
    val n = points.size
    var far = 0
    var farD = -1f
    for (i in 1 until n) {
        val d = points[0].distanceTo(points[i])
        if (d > farD) { farD = d; far = i }
    }
    val keep = BooleanArray(n)
    keep[0] = true; keep[far] = true
    simplifyRange(points, 0, far, epsilonPx, keep)
    // Second half wraps: indices far..n-1 then 0; simplify on a rotated view.
    val wrapped = ArrayList<PixelPoint>(n - far + 1)
    for (i in far until n) wrapped.add(points[i])
    wrapped.add(points[0])
    val keepW = BooleanArray(wrapped.size)
    keepW[0] = true; keepW[wrapped.size - 1] = true
    simplifyRange(wrapped, 0, wrapped.size - 1, epsilonPx, keepW)
    for (i in far until n) if (keepW[i - far]) keep[i] = true

    val result = ArrayList<PixelPoint>()
    for (i in 0 until n) if (keep[i]) result.add(points[i])
    // Closing pass over the ring.
    var changed = true
    while (changed && result.size > 3) {
        changed = false
        var i = 0
        while (i < result.size && result.size > 3) {
            val a = result[(i + result.size - 1) % result.size]
            val b = result[(i + 1) % result.size]
            if (lineDistance(result[i], a, b) <= epsilonPx) { result.removeAt(i); changed = true } else i++
        }
    }
    return result
}

/** Iterative DP on points[from..to] (inclusive endpoints assumed kept). */
private fun simplifyRange(points: List<PixelPoint>, from: Int, to: Int, eps: Float, keep: BooleanArray) {
    val stack = ArrayList<Int>()
    stack.add(from); stack.add(to)
    while (stack.isNotEmpty()) {
        val b = stack.removeAt(stack.size - 1)
        val a = stack.removeAt(stack.size - 1)
        if (b - a < 2) continue
        var maxD = -1f
        var idx = -1
        val pa = points[a]; val pb = points[b]
        for (i in a + 1 until b) {
            val d = lineDistance(points[i], pa, pb)
            if (d > maxD) { maxD = d; idx = i }
        }
        if (maxD > eps && idx >= 0) {
            keep[idx] = true
            stack.add(a); stack.add(idx)
            stack.add(idx); stack.add(b)
        }
    }
}
