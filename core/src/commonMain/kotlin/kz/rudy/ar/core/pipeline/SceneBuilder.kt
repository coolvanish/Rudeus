package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.PixelPoint
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.geometry.douglasPeucker
import kz.rudy.ar.core.geometry.pixelRay
import kz.rudy.ar.core.geometry.pixelToPlane
import kz.rudy.ar.core.geometry.traceContour
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.SegmentResult
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One SAM mask with the prompt that produced it. */
@Serializable
data class LabeledMask(val label: String, val mask: BooleanMask)

/** SAM table result -> the masks the [SceneBuilder] gets: one per detected object, not one merged blob per prompt. */
object ScanMasks {
    /**
     * One [LabeledMask] per [MaskResult.instances] entry (label = the prompt, best score first as stored); a prompt
     * with no instances but a non-empty merged mask (fixtures recorded before T15) yields its merged mask. Empty
     * masks are dropped, and a prompt listed in [SegmentResult.failedPrompts] yields nothing. RLE masks decode with
     * [BooleanMask.fromRle]; a bbox-only mask becomes its filled box.
     */
    fun of(result: SegmentResult): List<LabeledMask> {
        val failed = result.failedPrompts.toSet()
        val out = ArrayList<LabeledMask>()
        for (m in result.masks) {
            if (m.label in failed) continue
            val parts = m.instances.ifEmpty { listOf(m) }
            for (part in parts) {
                if (part.isEmpty || part.width <= 0 || part.height <= 0) continue
                out += LabeledMask(m.label, toBooleanMask(part))
            }
        }
        return out
    }

    private fun toBooleanMask(m: MaskResult): BooleanMask {
        val rle = m.rle
        if (rle != null) return BooleanMask.fromRle(m.width, m.height, rle.toIntArray())
        val box = m.bbox ?: return BooleanMask(m.width, m.height)
        return BooleanMask.generate(m.width, m.height) { x, y -> x >= box.x && x < box.x + box.w && y >= box.y && y < box.y + box.h }
    }
}

/** Everything a scan saves: masks + the camera geometry of the frame they were computed on + the table plane. */
@Serializable
data class ScanInput(
    val masks: List<LabeledMask>,
    val intrinsics: Intrinsics,
    val cameraPose: Pose3,
    val plane: PlaneFrame,
    /** Detected plane polygon in plane coordinates; passed through to [Level.boundary]. */
    val boundary: Polygon? = null,
    val spawn: Vec2 = Vec2.ZERO,
) {
    companion object {
        /**
         * The AR host's one call at scan time: the plane frame comes from the session [anchor]
         * ([PlaneFrame.fromAnchorPose]) and the detected plane polygon [planePolygonWorld] (world points) is projected
         * into it with [PlaneFrame.toPlane] as [boundary] (null when there is no polygon or it has fewer than 3 points).
         */
        fun fromAnchor(
            anchor: Pose3,
            cameraPose: Pose3,
            intrinsics: Intrinsics,
            masks: List<LabeledMask>,
            planePolygonWorld: List<Vec3>? = null,
            spawn: Vec2 = Vec2.ZERO,
        ): ScanInput {
            val plane = PlaneFrame.fromAnchorPose(anchor)
            val boundary = planePolygonWorld?.takeIf { it.size >= 3 }?.let { pts -> Polygon(pts.map { plane.toPlane(it) }) }
            return ScanInput(masks, intrinsics, cameraPose, plane, boundary, spawn)
        }
    }
}

@Serializable
data class SceneParams(
    /** Douglas–Peucker tolerance on the image contour, pixels. */
    val dpEpsilonPx: Float = 3f,
    /** Footprints smaller than this are noise and dropped, m². */
    val minFootprintAreaM2: Float = 1e-4f,
    /** Object heights are clamped to [0, maxHeightM]. */
    val maxHeightM: Float = 0.3f,
    /** Re-scan: an object keeps its id when the nearest previous object with the same label is closer than this. */
    val rematchMaxDistM: Float = 0.05f,
)

/**
 * Turns segmentation masks plus the scan-time camera geometry into a [Level] in plane coordinates, using
 * ray–plane intersection only (no depth).
 *
 * Per mask: largest 4-connected component → Moore contour → Douglas–Peucker → **footprint** → **height** →
 * [Semantics.kindFor] → stable id.
 *
 * ### Footprint: near rim + central symmetry
 * Casting every contour pixel onto the table gives the object's *perspective shadow* from the camera's ground point
 * `g`: the near part of the silhouette is the base's near rim (exact), while the far part is the top rim smeared
 * radially away from `g`. A single silhouette cannot tell a tall-narrow object from a short-deep one along the
 * viewing direction, so the far half must come from a prior. We sample the shadow's radial lower envelope (nearest
 * boundary crossing per angle, seen from `g`) — the base's near rim — anchored at the two points where the boundary
 * leaves the extreme radial lines (tangent points / base corners), and complete it by point reflection through the
 * midpoint of that tangent chord. This is exact for centrally symmetric bases (cylinders, boxes at any yaw whose side
 * face is visible from `g`); for a box seen exactly on-axis the chain degenerates and the raw shadow is used instead
 * (over-deep by `h / tan(elevation)`, 2 cm for a 2 cm book at 45°). Measured on the synthetic 45° scan: cup
 * (r = 4 cm, h = 9 cm) centroid error 0.9 cm, book (15 x 10 x 2 cm) 0.4 cm. The cup error comes from locating the
 * tangent point: the rim leaves the radial side edge tangentially, so with lateral pixel noise δ the chord endpoint
 * lands ~sqrt(2·r·δ) up the rim toward the camera.
 *
 * ### Height
 * The top-most silhouette pixel is the far top edge of the object. Its ray is intersected with the vertical plane,
 * perpendicular to the ray's horizontal direction, that passes through the *far boundary* of the footprint along
 * the ray's ground track (for a vertical object that boundary is directly below the top edge). Using the centroid
 * instead would bias the height by `r · tan(elevation)` (4 cm for the test cup), so the far boundary is used and
 * the centroid is the fallback when the ground track misses the footprint. Clamped to `[0, maxHeightM]`.
 * Measured: cup 1.4 cm high (inherits the footprint's far-edge error), book 0.6 cm.
 *
 * ### Ids
 * An object re-uses the id of the nearest previous object with the same label within [SceneParams.rematchMaxDistM]
 * (each previous id used at most once); otherwise it gets `"<label>-<n>"` with the first `n` not used before.
 */
class SceneBuilder(private val params: SceneParams = SceneParams()) {
    private companion object {
        /** Angular samples across the shadow for the near-rim envelope (footprint gets up to 2x this many vertices). */
        const val ENVELOPE_SAMPLES = 24

        /** Angular tolerance (rad) for "on the extreme radial line": ~1 px at 1 m with a 1000 px focal length. */
        const val EXTREME_ANGLE_TOL = 2e-3f
    }

    /** [profile] (table or floor) only decides each object's kind ([Semantics.kindFor]); footprints and heights are metric. */
    fun build(input: ScanInput, previous: Level? = null, profile: SurfaceProfile = SurfaceProfile.TABLE): Level {
        val candidates = ArrayList<Pair<String, Candidate>>()
        for (lm in input.masks) {
            val c = extract(lm.mask, input) ?: continue
            candidates.add(lm.label to c)
        }
        val objects = assignIds(candidates, previous, profile)
        return Level(objects = objects, boundary = input.boundary, spawn = input.spawn)
    }

    internal class Candidate(val footprint: Polygon, val heightM: Float) {
        val centroid: Vec2 = footprint.centroid()
    }

    /** Footprint + height of one mask, or null when the mask yields nothing usable. */
    internal fun extract(mask: BooleanMask, input: ScanInput): Candidate? {
        val component = mask.largestComponent()
        if (component.isEmpty) return null
        val contour = traceContour(component, singleComponent = true)
        if (contour.size < 3) return null
        val simplified = douglasPeucker(contour, params.dpEpsilonPx)
        if (simplified.size < 3) return null

        val shadow = simplified.mapNotNull { p -> pixelToPlane(p.x, p.y, input.intrinsics, input.cameraPose, input.plane) }
        if (shadow.size < 3) return null
        // Raw (unsimplified) shadow: used only for the tangent chord endpoints, which the pixel-space DP pass blurs.
        val rawShadow = contour.mapNotNull { p -> pixelToPlane(p.x, p.y, input.intrinsics, input.cameraPose, input.plane) }
        val cameraGround = input.plane.toPlane(input.cameraPose.position)
        val footprintPts = nearRimFootprint(shadow, cameraGround, rawShadow)
        val footprint = toPolygon(footprintPts) ?: toPolygon(shadow) ?: return null
        if (footprint.area < params.minFootprintAreaM2) return null

        val height = estimateHeight(component, footprint, input)
        return Candidate(footprint, height.coerceIn(0f, params.maxHeightM))
    }

    /**
     * Near rim of the shadow as seen from the camera ground point [g], completed by point reflection; see class KDoc.
     *
     * The side edges of a vertical object's shadow are *radial* from [g], so vertex-based extreme picking is
     * ambiguous. Instead the radial lower envelope is sampled: for [ENVELOPE_SAMPLES] angles across the shadow's
     * angular span, the nearest crossing of the ray from [g] with the shadow boundary is taken. Those samples are the
     * base's near rim; the far rim is their point reflection through the midpoint of the first/last sample. The
     * result is simplified with a 2 mm Douglas–Peucker pass.
     */
    internal fun nearRimFootprint(shadow: List<Vec2>, g: Vec2, rawShadow: List<Vec2> = shadow): List<Vec2> {
        val n = shadow.size
        val centroid = shadow.fold(Vec2.ZERO) { a, p -> a + p } / n.toFloat()
        val ref = (centroid - g).normalized()
        if (ref.lengthSquared < 1e-12f) return shadow
        // Angular span from the raw shadow (its side edges are radial from g, so the extremes are sharp).
        val raw = if (rawShadow.size >= 3) rawShadow else shadow
        val angles = FloatArray(raw.size)
        var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
        for (i in raw.indices) {
            val d = raw[i] - g
            angles[i] = atan2(ref.cross(d), ref.dot(d))
            if (angles[i] < minA) minA = angles[i]
            if (angles[i] > maxA) maxA = angles[i]
        }
        val span = maxA - minA
        if (span <= 1e-6f) return shadow
        val perp = ref.perp()
        val near = ArrayList<Vec2>(ENVELOPE_SAMPLES + 2)
        // Chain endpoints: the raw vertex nearest to g among those lying *on* the extreme radial line (within a
        // pixel-scale angular tolerance). The side edges of the shadow are radial, so this picks the base end of the
        // edge (tangent point / base corner), not its smeared top end. The tolerance must stay at pixel scale: a wider
        // one admits points along the base rim itself, which are nearer to the camera than the tangent point.
        nearestVertexAt(raw, angles, g, minA, EXTREME_ANGLE_TOL)?.let { near.add(it) }
        for (i in 0 until ENVELOPE_SAMPLES) {
            val a = minA + span * (i + 0.5f) / ENVELOPE_SAMPLES
            val w = ref * cos(a) + perp * sin(a)
            var tNear = Float.MAX_VALUE
            for (k in 0 until n) {
                val t = raySegment(g, w, shadow[k], shadow[(k + 1) % n]) ?: continue
                if (t < tNear) tNear = t
            }
            if (tNear < Float.MAX_VALUE) near.add(g + w * tNear)
        }
        nearestVertexAt(raw, angles, g, maxA, EXTREME_ANGLE_TOL)?.let { near.add(it) }
        if (near.size < 2) return shadow
        val m = (near.first() + near.last()) * 0.5f
        val out = ArrayList<Vec2>(near.size * 2)
        out.addAll(near)
        for (j in 1 until near.size - 1) out.add(m * 2f - near[j]) // reflect(near[0]) == near.last(), so continue from j = 1
        // Simplify in millimetre units with a 2 mm tolerance.
        val simplified = douglasPeucker(out.map { PixelPoint(it.u * 1000f, it.v * 1000f) }, 2f)
        return simplified.map { Vec2(it.x / 1000f, it.y / 1000f) }
    }

    private fun nearestVertexAt(pts: List<Vec2>, angles: FloatArray, g: Vec2, target: Float, tol: Float): Vec2? {
        var best: Vec2? = null
        var bestD = Float.MAX_VALUE
        for (i in pts.indices) {
            if (abs(angles[i] - target) > tol) continue
            val d = pts[i].distanceTo(g)
            if (d < bestD) { bestD = d; best = pts[i] }
        }
        return best
    }

    /** Dedupes consecutive points and builds a Polygon, or null when fewer than 3 distinct points remain. */
    private fun toPolygon(pts: List<Vec2>): Polygon? {
        val out = ArrayList<Vec2>()
        for (p in pts) if (out.isEmpty() || out.last().distanceTo(p) > 1e-4f) out.add(p)
        if (out.size > 1 && out.first().distanceTo(out.last()) <= 1e-4f) out.removeAt(out.size - 1)
        if (out.size < 3) return null
        val poly = Polygon(out)
        return if (poly.area < 1e-9f) null else poly
    }

    /** Height from the top-most silhouette pixel; see class KDoc. */
    internal fun estimateHeight(component: BooleanMask, footprint: Polygon, input: ScanInput): Float {
        val top = topPixel(component) ?: return 0f
        val ray = pixelRay(top.x, top.y, input.intrinsics, input.cameraPose)
        val plane = input.plane
        val g = plane.toPlane(ray.origin)
        val camH = plane.heightOf(ray.origin)
        val horiz = plane.directionToPlane(ray.dir)
        val vert = ray.dir.dot(plane.normal)
        val hl = horiz.length
        if (hl < 1e-6f) return 0f
        val w = horiz / hl
        // Farthest crossing of the ground track g + t·w with the footprint boundary.
        var tFar = Float.NEGATIVE_INFINITY
        for ((a, b) in footprint.edges()) {
            val t = raySegment(g, w, a, b) ?: continue
            if (t > tFar) tFar = t
        }
        if (tFar == Float.NEGATIVE_INFINITY) tFar = (footprint.centroid() - g).dot(w)
        if (tFar <= 0f) return 0f
        val s = tFar / hl // ray parameter at that horizontal distance
        return camH + s * vert
    }

    /** Mean x of the pixels in the top-most set row (smallest y). */
    private fun topPixel(mask: BooleanMask): PixelPoint? {
        for (y in 0 until mask.height) {
            var sum = 0f; var cnt = 0
            val row = y * mask.width
            for (x in 0 until mask.width) if (mask.bits[row + x]) { sum += x; cnt++ }
            if (cnt > 0) return PixelPoint(sum / cnt + 0.5f, y + 0.5f)
        }
        return null
    }

    /** Parameter t >= 0 where ray (o, d) crosses segment [a, b], or null. */
    private fun raySegment(o: Vec2, d: Vec2, a: Vec2, b: Vec2): Float? {
        val e = b - a
        val denom = d.cross(e)
        if (abs(denom) < 1e-9f) return null
        val ao = a - o
        val t = ao.cross(e) / denom
        val u = ao.cross(d) / denom
        return if (t >= 0f && u >= 0f && u <= 1f) t else null
    }

    private fun assignIds(candidates: List<Pair<String, Candidate>>, previous: Level?, profile: SurfaceProfile): List<LevelObject> {
        val usedIds = HashSet<String>()
        previous?.objects?.forEach { usedIds.add(it.id) }
        val taken = HashSet<String>()
        val out = ArrayList<LevelObject>(candidates.size)
        // Greedy nearest-first matching so the closest pairs win when several candidates compete for one old object.
        data class Match(val ci: Int, val prevId: String, val dist: Float)
        val matches = ArrayList<Match>()
        if (previous != null) {
            for ((ci, lc) in candidates.withIndex()) {
                val (label, cand) = lc
                for (po in previous.objects) {
                    if (po.label != label) continue
                    val d = po.centroid.distanceTo(cand.centroid)
                    if (d <= params.rematchMaxDistM) matches.add(Match(ci, po.id, d))
                }
            }
            matches.sortBy { it.dist }
        }
        val idFor = arrayOfNulls<String>(candidates.size)
        for (mt in matches) {
            if (idFor[mt.ci] != null || mt.prevId in taken) continue
            idFor[mt.ci] = mt.prevId
            taken.add(mt.prevId)
        }
        val counters = HashMap<String, Int>()
        for ((ci, lc) in candidates.withIndex()) {
            val (label, cand) = lc
            var id = idFor[ci]
            if (id == null) {
                var n = counters[label] ?: 1
                while ("$label-$n" in usedIds || "$label-$n" in taken) n++
                id = "$label-$n"
                counters[label] = n + 1
                taken.add(id)
            }
            out.add(
                LevelObject(
                    id = id,
                    label = label,
                    kind = Semantics.kindFor(label, cand.heightM, profile),
                    footprint = cand.footprint,
                    heightM = cand.heightM,
                )
            )
        }
        return out
    }
}
