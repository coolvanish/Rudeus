package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.geometry.pixelRay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Perfect masks of solids on a horizontal table, seen at a chosen ray elevation, phone tilt and roll from 0.64 m.
 * Portrait is roll 90: the sensor image lies on its side.
 */
internal class ScanViewFixture(val intr: Intrinsics = Intrinsics(fx = 1000f, fy = 1000f, cx = 640f, cy = 360f, width = 1280, height = 720)) {
    val plane = PlaneFrame.horizontal(0f)

    fun pose(at: Vec2, elevationDeg: Float, tiltDeg: Float = elevationDeg, rollDeg: Float = 90f): Pose3 {
        val e = rad(elevationDeg); val t = rad(tiltDeg)
        val eye = Vec3(at.u, 0.636f * sin(e), at.v + 0.636f * cos(e))
        return Pose3(eye, Quat.lookAlong(Vec3(0f, -sin(t), -cos(t))) * Quat.fromAxisAngle(Vec3(0f, 0f, 1f), rad(rollDeg)))
    }

    /** Cylinder of radius [r] and height [h] standing at [at]. */
    fun cupMask(pose: Pose3, at: Vec2, r: Float = 0.04f, h: Float = 0.09f): BooleanMask = mask(pose) { o, d ->
        val ou = o.x - at.u; val ov = o.y - at.v
        val a = d.x * d.x + d.y * d.y
        val b = 2f * (d.x * ou + d.y * ov)
        val c = ou * ou + ov * ov - r * r
        val disc = b * b - 4f * a * c
        var hit = false
        if (disc >= 0f && a > 1e-12f) {
            val sq = sqrt(disc)
            for (t in listOf((-b - sq) / (2f * a), (-b + sq) / (2f * a))) if (t > 0f && (o.z + t * d.z) in 0f..h) hit = true
        }
        for (cap in listOf(0f, h)) {
            if (hit || abs(d.z) < 1e-9f) continue
            val t = (cap - o.z) / d.z
            if (t > 0f && (ou + t * d.x).let { it * it } + (ov + t * d.y).let { it * it } <= r * r) hit = true
        }
        hit
    }

    /** Box [du] x [dv] x [h] on the table, centred at [at], turned [yawDeg] around the plane normal. */
    fun boxMask(pose: Pose3, at: Vec2, du: Float, dv: Float, h: Float, yawDeg: Float = 0f): BooleanMask = mask(pose) { o, d ->
        val c = cos(rad(yawDeg)); val s = sin(rad(yawDeg))
        val ou = o.x - at.u; val ov = o.y - at.v
        val oo = floatArrayOf(c * ou + s * ov, -s * ou + c * ov, o.z)
        val dd = floatArrayOf(c * d.x + s * d.y, -s * d.x + c * d.y, d.z)
        val lo = floatArrayOf(-du / 2f, -dv / 2f, 0f); val hi = floatArrayOf(du / 2f, dv / 2f, h)
        var tMin = 1e-6f; var tMax = Float.MAX_VALUE; var hit = true
        for (i in 0..2) {
            if (abs(dd[i]) < 1e-9f) { if (oo[i] < lo[i] || oo[i] > hi[i]) hit = false; continue }
            val t1 = (lo[i] - oo[i]) / dd[i]; val t2 = (hi[i] - oo[i]) / dd[i]
            tMin = max(tMin, min(t1, t2)); tMax = min(tMax, max(t1, t2))
        }
        hit && tMin <= tMax
    }

    fun input(pose: Pose3, vararg masks: LabeledMask) = ScanInput(masks.toList(), intr, pose, plane, null, Vec2.ZERO)

    private fun mask(pose: Pose3, hit: (o: Vec3, d: Vec3) -> Boolean): BooleanMask {
        val o = plane.toLocal(pose.position)
        return BooleanMask.generate(intr.width, intr.height) { x, y ->
            val r = pixelRay(x + 0.5f, y + 0.5f, intr, pose).dir
            hit(o, Vec3(r.dot(plane.uAxis), r.dot(plane.vAxis), r.dot(plane.normal)))
        }
    }

    private fun rad(deg: Float) = deg * PI.toFloat() / 180f

    companion object {
        /** ARCore's CPU image on most devices (`ScanCapture`): the grid real masks come in. */
        val CPU_IMAGE = Intrinsics(fx = 500f, fy = 500f, cx = 320f, cy = 240f, width = 640, height = 480)
    }
}
