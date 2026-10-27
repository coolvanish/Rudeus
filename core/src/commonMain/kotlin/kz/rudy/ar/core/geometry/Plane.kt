package kz.rudy.ar.core.geometry

import kz.rudy.ar.core.domain.Vec2
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Orthonormal frame of the detected table plane.
 *
 * [origin] is the session anchor on the plane, [normal] the unit "up" of the plane, [uAxis] and [vAxis] unit vectors
 * in the plane. Plane coordinates `(u, v)` are metres along [uAxis]/[vAxis] from [origin]; `h` is metres along [normal].
 * For [horizontal] tables `u` runs along world +X and `v` along world +Z (the ARCore plane-local X/Z convention in
 * Model.kt), so `uAxis × vAxis == −normal`; consumers must not assume a winding.
 */
@Serializable
data class PlaneFrame(
    val origin: Vec3,
    val normal: Vec3,
    val uAxis: Vec3,
    val vAxis: Vec3,
) {
    /** Full local coordinates (u, v, h) of a world point. */
    fun toLocal(world: Vec3): Vec3 {
        val d = world - origin
        return Vec3(d.dot(uAxis), d.dot(vAxis), d.dot(normal))
    }

    /** Orthogonal projection of a world point onto plane coordinates. */
    fun toPlane(world: Vec3): Vec2 {
        val d = world - origin
        return Vec2(d.dot(uAxis), d.dot(vAxis))
    }

    /** Height of a world point above the plane (signed, metres). */
    fun heightOf(world: Vec3): Float = (world - origin).dot(normal)

    fun fromPlane(u: Float, v: Float, h: Float = 0f): Vec3 = origin + uAxis * u + vAxis * v + normal * h
    fun fromPlane(p: Vec2, h: Float = 0f): Vec3 = fromPlane(p.u, p.v, h)

    /** Direction in plane coordinates of a world direction (its component along the normal is dropped). */
    fun directionToPlane(dir: Vec3): Vec2 = Vec2(dir.dot(uAxis), dir.dot(vAxis))

    companion object {
        /** Horizontal table at world height [originY] (normal +Y, u along +X, v along +Z). */
        fun horizontal(originY: Float, originX: Float = 0f, originZ: Float = 0f) = PlaneFrame(
            origin = Vec3(originX, originY, originZ),
            normal = Vec3(0f, 1f, 0f),
            uAxis = Vec3(1f, 0f, 0f),
            vAxis = Vec3(0f, 0f, 1f),
        )
    }
}

/**
 * Intersection of [ray] with the plane of [plane] (ignoring its extent).
 * Returns null when the ray is parallel to the plane or the hit lies behind the ray origin.
 */
fun intersectRayPlane(ray: Ray, plane: PlaneFrame): Vec3? = intersectRayPlane(ray, plane.origin, plane.normal)

/** Same as [intersectRayPlane] for an arbitrary plane given by a point and a unit normal. */
fun intersectRayPlane(ray: Ray, point: Vec3, normal: Vec3): Vec3? {
    val denom = ray.dir.dot(normal)
    if (abs(denom) < 1e-8f) return null
    val t = (point - ray.origin).dot(normal) / denom
    if (t < 0f) return null
    return ray.at(t)
}

/** Pixel → viewing ray → table plane → `(u, v)`. Null when the pixel's ray misses the plane. */
fun pixelToPlane(px: Float, py: Float, intr: Intrinsics, pose: Pose3, plane: PlaneFrame): Vec2? {
    val hit = intersectRayPlane(pixelRay(px, py, intr, pose), plane) ?: return null
    return plane.toPlane(hit)
}
