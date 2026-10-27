package kz.rudy.ar.core.geometry

import kz.rudy.ar.core.domain.Vec2
import kotlinx.serialization.Serializable

/**
 * Pinhole camera intrinsics in **pixels** for an image of [width] x [height] pixels.
 * Image pixels: x right, y down, origin top-left. ([cx], [cy]) is the principal point.
 */
@Serializable
data class Intrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
) {
    companion object {
        /** Symmetric intrinsics from a horizontal field of view (radians) and the image size. */
        fun fromFov(hFovRad: Float, width: Int, height: Int): Intrinsics {
            val f = (width / 2f) / kotlin.math.tan(hFovRad / 2f)
            return Intrinsics(f, f, width / 2f, height / 2f, width, height)
        }
    }
}

/** Half-line `origin + t * dir`, `t >= 0`, with unit [dir], both in world space. */
@Serializable
data class Ray(val origin: Vec3, val dir: Vec3) {
    fun at(t: Float): Vec3 = origin + dir * t
}

/**
 * World-space viewing ray through pixel ([px], [py]).
 *
 * Camera-space direction = normalize(((px − cx) / fx, −(py − cy) / fy, −1)): image y goes down while camera +Y goes up,
 * and the camera looks down −Z. The direction is rotated by [pose].rotation; the origin is [pose].position.
 */
fun pixelRay(px: Float, py: Float, intr: Intrinsics, pose: Pose3): Ray {
    val camDir = Vec3((px - intr.cx) / intr.fx, -(py - intr.cy) / intr.fy, -1f).normalized()
    return Ray(pose.position, pose.rotation.rotate(camDir))
}

/**
 * Inverse of [pixelRay]: projects a world point to pixel coordinates (x right, y down).
 * Returns null when the point is on or behind the camera plane (camera-space z >= 0).
 */
fun projectToPixel(world: Vec3, intr: Intrinsics, pose: Pose3): Vec2? {
    val cam = pose.inverse().transform(world)
    if (cam.z >= -1e-6f) return null
    val px = intr.cx + intr.fx * (cam.x / -cam.z)
    val py = intr.cy - intr.fy * (cam.y / -cam.z)
    return Vec2(px, py)
}
