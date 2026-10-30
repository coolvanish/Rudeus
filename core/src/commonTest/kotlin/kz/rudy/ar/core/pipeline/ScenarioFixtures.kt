package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.geometry.pixelRay
import kz.rudy.ar.core.geometry.projectToPixel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ray-cast rasteriser for the Day 4 scenario tests: the same idiom as `SceneBuilderTest` (whose solids are private),
 * parameterised by the camera so a scenario can build its masks for the pose it hands to [ScanInput.fromAnchor].
 */
internal object ScenarioFixtures {
    /** A solid in plane-local coordinates (u, v, h). */
    interface Solid {
        /** True when the local-space ray (o + t d, t > 0) hits the solid. */
        fun hit(o: Vec3, d: Vec3): Boolean

        /** Points whose projections bound the silhouette. */
        fun hull(): List<Vec3>
    }

    /** Upright cylinder (a cup): centre ([cu], [cv]), radius [r], height [h]. */
    class Cylinder(val cu: Float, val cv: Float, val r: Float, val h: Float) : Solid {
        override fun hit(o: Vec3, d: Vec3): Boolean {
            val ou = o.x - cu; val ov = o.y - cv
            val a = d.x * d.x + d.y * d.y
            val b = 2f * (d.x * ou + d.y * ov)
            val c = ou * ou + ov * ov - r * r
            val disc = b * b - 4f * a * c
            if (disc >= 0f && a > 1e-12f) {
                val sq = sqrt(disc)
                for (t in listOf((-b - sq) / (2f * a), (-b + sq) / (2f * a))) {
                    if (t > 0f) { val z = o.z + t * d.z; if (z in 0f..h) return true }
                }
            }
            for (cap in listOf(0f, h)) {
                if (abs(d.z) < 1e-9f) continue
                val t = (cap - o.z) / d.z
                if (t <= 0f) continue
                val u = ou + t * d.x; val v = ov + t * d.y
                if (u * u + v * v <= r * r) return true
            }
            return false
        }

        override fun hull(): List<Vec3> = (0 until 32).flatMap { i ->
            val a = 2.0 * PI * i / 32
            val u = cu + r * cos(a).toFloat(); val v = cv + r * sin(a).toFloat()
            listOf(Vec3(u, v, 0f), Vec3(u, v, h))
        }
    }

    /** Axis-aligned box on the table: u in [u0,u1], v in [v0,v1], h in [0,h]. */
    class Box(val u0: Float, val u1: Float, val v0: Float, val v1: Float, val h: Float) : Solid {
        override fun hit(o: Vec3, d: Vec3): Boolean {
            var tMin = 1e-6f; var tMax = Float.MAX_VALUE
            val lo = floatArrayOf(u0, v0, 0f); val hi = floatArrayOf(u1, v1, h)
            val oo = floatArrayOf(o.x, o.y, o.z); val dd = floatArrayOf(d.x, d.y, d.z)
            for (i in 0..2) {
                if (abs(dd[i]) < 1e-9f) { if (oo[i] < lo[i] || oo[i] > hi[i]) return false; continue }
                var t1 = (lo[i] - oo[i]) / dd[i]; var t2 = (hi[i] - oo[i]) / dd[i]
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                tMin = max(tMin, t1); tMax = min(tMax, t2)
                if (tMin > tMax) return false
            }
            return true
        }

        override fun hull(): List<Vec3> =
            listOf(u0, u1).flatMap { u -> listOf(v0, v1).flatMap { v -> listOf(Vec3(u, v, 0f), Vec3(u, v, h)) } }
    }

    /** Silhouette of [solid] seen by a camera at [pose] with [intr], over the table [plane]. */
    fun rasterise(solid: Solid, intr: Intrinsics, pose: Pose3, plane: PlaneFrame): BooleanMask {
        var minX = intr.width; var minY = intr.height; var maxX = -1; var maxY = -1
        for (p in solid.hull()) {
            val px = projectToPixel(plane.fromPlane(p.x, p.y, p.z), intr, pose) ?: continue
            minX = min(minX, px.u.toInt() - 3); maxX = max(maxX, px.u.toInt() + 3)
            minY = min(minY, px.v.toInt() - 3); maxY = max(maxY, px.v.toInt() + 3)
        }
        minX = max(minX, 0); minY = max(minY, 0); maxX = min(maxX, intr.width - 1); maxY = min(maxY, intr.height - 1)
        val mask = BooleanMask(intr.width, intr.height)
        val o = plane.toLocal(pose.position)
        for (y in minY..maxY) for (x in minX..maxX) {
            val ray = pixelRay(x + 0.5f, y + 0.5f, intr, pose)
            val d = Vec3(ray.dir.dot(plane.uAxis), ray.dir.dot(plane.vAxis), ray.dir.dot(plane.normal))
            if (solid.hit(o, d)) mask[x, y] = true
        }
        return mask
    }
}
