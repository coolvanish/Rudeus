package kz.rudy.ar.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D math used by the scan geometry. Pure Kotlin so :core never sees ARCore types.
 *
 * Conventions (shared by every file in this package):
 * - World space is right-handed with **Y up**.
 * - Camera space: +X right, +Y up, the camera looks down **−Z** (OpenGL / ARCore convention).
 * - Image pixels: x right, y **down**, origin at the top-left corner; pixel centres are at (i + 0.5, j + 0.5).
 */
@Serializable
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    val lengthSquared: Float get() = x * x + y * y + z * z
    fun normalized(): Vec3 = if (length < EPS) ZERO else this / length
    fun distanceTo(o: Vec3) = (this - o).length

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        const val EPS = 1e-7f
    }
}

/**
 * Unit quaternion (x, y, z, w) representing a rotation. [rotate] applies `q * v * q⁻¹`,
 * i.e. the active rotation of a vector; [times] is the Hamilton product, so `(a * b).rotate(v) == a.rotate(b.rotate(v))`.
 */
@Serializable
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    val length: Float get() = sqrt(x * x + y * y + z * z + w * w)

    fun normalized(): Quat {
        val l = length
        return if (l < Vec3.EPS) IDENTITY else Quat(x / l, y / l, z / l, w / l)
    }

    /** Inverse of a unit quaternion is its conjugate. */
    fun inverse() = Quat(-x, -y, -z, w)

    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** Rotates [v] by this quaternion (`q v q⁻¹`). */
    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w (u × v) + 2 u × (u × v), u = (x, y, z)
        val u = Vec3(x, y, z)
        val t = u.cross(v) * 2f
        return v + t * w + u.cross(t)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        /** Rotation of [angleRad] radians about [axis] (right-hand rule). */
        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quat {
            val a = axis.normalized()
            val h = angleRad / 2f
            val s = sin(h)
            return Quat(a.x * s, a.y * s, a.z * s, cos(h))
        }

        /**
         * Quaternion whose rotation maps the unit axes to the orthonormal basis columns [xAxis], [yAxis], [zAxis]
         * (i.e. local +X → [xAxis], local +Y → [yAxis], local +Z → [zAxis]). Shepperd's method.
         */
        fun fromBasis(xAxis: Vec3, yAxis: Vec3, zAxis: Vec3): Quat {
            val m00 = xAxis.x; val m10 = xAxis.y; val m20 = xAxis.z
            val m01 = yAxis.x; val m11 = yAxis.y; val m21 = yAxis.z
            val m02 = zAxis.x; val m12 = zAxis.y; val m22 = zAxis.z
            val trace = m00 + m11 + m22
            return if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }.normalized()
        }

        /**
         * Camera orientation that looks along [forward] (world) with [up] as the approximate up direction:
         * camera −Z → [forward], camera +Y ≈ [up], camera +X = right.
         */
        fun lookAlong(forward: Vec3, up: Vec3 = Vec3.UP): Quat {
            val z = (-forward).normalized()
            var xAxis = up.cross(z)
            if (xAxis.lengthSquared < 1e-10f) {
                // forward parallel to up: pick any perpendicular.
                xAxis = Vec3(1f, 0f, 0f).cross(z)
                if (xAxis.lengthSquared < 1e-10f) xAxis = Vec3(0f, 0f, 1f).cross(z)
            }
            val x = xAxis.normalized()
            val y = z.cross(x)
            return fromBasis(x, y, z)
        }
    }
}

/**
 * Rigid transform: `transform(p) = rotation.rotate(p) + position`. For a camera pose, [position] is the camera
 * centre in world space and [rotation] maps camera-space directions to world-space directions.
 */
@Serializable
data class Pose3(val position: Vec3, val rotation: Quat) {
    fun transform(point: Vec3): Vec3 = rotation.rotate(point) + position
    fun transformDirection(dir: Vec3): Vec3 = rotation.rotate(dir)

    fun inverse(): Pose3 {
        val invRot = rotation.inverse()
        return Pose3(-invRot.rotate(position), invRot)
    }

    /** `this ∘ other`: first applies [other], then this. */
    fun compose(other: Pose3): Pose3 =
        Pose3(rotation.rotate(other.position) + position, (rotation * other.rotation).normalized())

    companion object {
        val IDENTITY = Pose3(Vec3.ZERO, Quat.IDENTITY)

        /** Camera pose at [eye] looking at [target] (camera −Z points from eye to target). */
        fun lookAt(eye: Vec3, target: Vec3, up: Vec3 = Vec3.UP): Pose3 =
            Pose3(eye, Quat.lookAlong(target - eye, up))
    }
}

