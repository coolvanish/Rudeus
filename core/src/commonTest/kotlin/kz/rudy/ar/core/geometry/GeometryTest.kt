package kz.rudy.ar.core.geometry

import kz.rudy.ar.core.domain.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeometryTest {
    private val intr = Intrinsics(fx = 1000f, fy = 1000f, cx = 640f, cy = 360f, width = 1280, height = 720)

    private fun assertVec3(expected: Vec3, actual: Vec3, eps: Float, msg: String = "") {
        assertTrue(expected.distanceTo(actual) <= eps, "$msg expected $expected got $actual (eps=$eps)")
    }

    @Test
    fun quaternionRotateAndInverse() {
        val q = Quat.fromAxisAngle(Vec3(0f, 1f, 0f), (PI / 2).toFloat())
        val r = q.rotate(Vec3(1f, 0f, 0f))
        assertVec3(Vec3(0f, 0f, -1f), r, 1e-5f, "90° about +Y maps +X to −Z")
        assertVec3(Vec3(1f, 0f, 0f), q.inverse().rotate(r), 1e-5f, "inverse undoes rotation")
        val id = q * q.inverse()
        assertTrue(abs(id.w - 1f) < 1e-5f && abs(id.x) < 1e-5f && abs(id.y) < 1e-5f && abs(id.z) < 1e-5f)
        // Composition order: (a*b).rotate == a.rotate(b.rotate)
        val a = Quat.fromAxisAngle(Vec3(1f, 0f, 0f), 0.7f)
        val b = Quat.fromAxisAngle(Vec3(0f, 0f, 1f), -1.1f)
        val v = Vec3(0.3f, -0.2f, 0.9f)
        assertVec3(a.rotate(b.rotate(v)), (a * b).rotate(v), 1e-5f)
        // Pose inverse round-trip
        val pose = Pose3(Vec3(1f, 2f, 3f), a)
        assertVec3(v, pose.inverse().transform(pose.transform(v)), 1e-5f)
        assertVec3(pose.transform(v), pose.compose(Pose3.IDENTITY).transform(v), 1e-5f)
    }

    @Test
    fun rayPlaneHitsKnownPoint() {
        val pose = Pose3.lookAt(Vec3(0f, 0.5f, 0.5f), Vec3.ZERO)
        val plane = PlaneFrame.horizontal(0f)
        val ray = pixelRay(intr.cx, intr.cy, intr, pose)
        val hit = intersectRayPlane(ray, plane)
        assertNotNull(hit)
        assertVec3(Vec3.ZERO, hit, 1e-3f, "centre pixel ray hits the look-at point")
        val uv = pixelToPlane(intr.cx, intr.cy, intr, pose, plane)
        assertNotNull(uv)
        assertTrue(uv.length < 1e-3f)
        // Camera looks down; a ray pointing up (top of the image, far above the horizon) misses the plane.
        val upRay = Ray(Vec3(0f, 0.5f, 0.5f), Vec3(0f, 1f, 0f))
        assertEquals(null, intersectRayPlane(upRay, plane))
    }

    @Test
    fun pixelToPlaneRoundTrip() {
        val pose = Pose3.lookAt(Vec3(0.1f, 0.45f, 0.65f), Vec3(0.1f, 0f, 0.2f))
        val plane = PlaneFrame.horizontal(0f)
        val points = listOf(Vec2(0f, 0f), Vec2(0.1f, 0.2f), Vec2(-0.15f, 0.3f), Vec2(0.25f, 0.05f), Vec2(0.02f, 0.4f))
        for (p in points) {
            val px = projectToPixel(plane.fromPlane(p), intr, pose)
            assertNotNull(px, "point $p projects")
            val back = pixelToPlane(px.u, px.v, intr, pose, plane)
            assertNotNull(back)
            assertTrue(back.distanceTo(p) < 1e-3f, "round trip $p -> $px -> $back")
        }
        assertEquals(null, projectToPixel(Vec3(0.1f, 0.45f, 1.5f), intr, pose), "behind camera")
    }

    @Test
    fun largestComponentKeepsBiggestBlob() {
        val m = BooleanMask.generate(20, 20) { x, y ->
            (x in 2..6 && y in 3..7) || (x in 12..13 && y in 12..13) || (x == 18 && y == 1)
        }
        val big = m.largestComponent()
        assertEquals(25, big.count())
        assertTrue(big[4, 5])
        assertFalse(big[12, 12])
        assertFalse(big[18, 1])
        assertEquals(PixelRect(2, 3, 6, 7), big.boundingBox())
        assertTrue(BooleanMask(5, 5).largestComponent().isEmpty)
        // Must handle a full-HD mask without blowing the stack.
        val hd = BooleanMask.generate(1920, 1080) { x, y -> x in 100..1800 && y in 100..1000 }
        assertEquals(1701 * 901, hd.largestComponent().count())
    }

    @Test
    fun douglasPeuckerReducesNoisySquareToFourCorners() {
        val pts = ArrayList<PixelPoint>()
        val n = 100
        fun noise(i: Int) = if (i % 2 == 0) 0.4f else -0.4f
        for (i in 0 until n) pts.add(PixelPoint(i.toFloat(), 0f + noise(i)))
        for (i in 0 until n) pts.add(PixelPoint(n.toFloat() + noise(i), i.toFloat()))
        for (i in 0 until n) pts.add(PixelPoint((n - i).toFloat(), n.toFloat() + noise(i)))
        for (i in 0 until n) pts.add(PixelPoint(0f + noise(i), (n - i).toFloat()))
        val simplified = douglasPeucker(pts, 1.5f)
        assertEquals(4, simplified.size, "got $simplified")
        val corners = listOf(PixelPoint(0f, 0f), PixelPoint(100f, 0f), PixelPoint(100f, 100f), PixelPoint(0f, 100f))
        for (c in corners) assertTrue(simplified.any { it.distanceTo(c) < 1f }, "corner $c present in $simplified")
    }

    @Test
    fun contourOfRectangleMaskGivesFourCornersAfterSimplify() {
        val m = BooleanMask.generate(40, 30) { x, y -> x in 5..24 && y in 7..20 }
        val contour = traceContour(m)
        assertEquals(2 * (20 + 14) - 4, contour.size, "perimeter pixel count")
        val simplified = douglasPeucker(contour, 1f)
        assertEquals(4, simplified.size, "got $simplified")
        val expected = listOf(PixelPoint(5.5f, 7.5f), PixelPoint(24.5f, 7.5f), PixelPoint(24.5f, 20.5f), PixelPoint(5.5f, 20.5f))
        for (c in expected) assertTrue(simplified.any { it.distanceTo(c) < 1e-3f }, "corner $c in $simplified")
        // Single pixel and empty masks are handled.
        assertEquals(1, traceContour(BooleanMask.generate(3, 3) { x, y -> x == 1 && y == 1 }).size)
        assertTrue(traceContour(BooleanMask(3, 3)).isEmpty())
    }
}
