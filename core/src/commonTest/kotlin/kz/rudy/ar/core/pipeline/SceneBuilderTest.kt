package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.geometry.pixelRay
import kz.rudy.ar.core.geometry.projectToPixel
import kz.rudy.ar.core.meta.MaskResult
import kz.rudy.ar.core.meta.SegmentResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Synthetic scan: 1280x720 camera, 0.45 m above the table and 0.45 m back from the look-at point (45° down),
 * horizontal table at y = 0 (u = world X, v = world Z).
 */
class SceneBuilderTest {
    private val intr = Intrinsics(fx = 1000f, fy = 1000f, cx = 640f, cy = 360f, width = 1280, height = 720)
    private val plane = PlaneFrame.horizontal(0f)
    private val lookAt = Vec3(0.10f, 0f, 0.20f)
    private val pose = Pose3.lookAt(Vec3(0.10f, 0.45f, 0.65f), lookAt)

    /** A solid defined in plane-local coordinates (u, v, h). */
    private interface Solid {
        /** True when the local-space ray (o + t d, t > 0) hits the solid. */
        fun hit(o: Vec3, d: Vec3): Boolean
        /** Points whose projections bound the silhouette. */
        fun hull(): List<Vec3>
    }

    private class Cylinder(val cu: Float, val cv: Float, val r: Float, val h: Float) : Solid {
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
    private class Box(val u0: Float, val u1: Float, val v0: Float, val v1: Float, val h: Float) : Solid {
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

        override fun hull(): List<Vec3> = listOf(u0, u1).flatMap { u -> listOf(v0, v1).flatMap { v -> listOf(Vec3(u, v, 0f), Vec3(u, v, h)) } }
    }

    private fun rasterise(solid: Solid): BooleanMask {
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

    private fun scan(vararg objects: Pair<String, Solid>) = ScanInput(
        masks = objects.map { (label, s) -> LabeledMask(label, rasterise(s)) },
        intrinsics = intr,
        cameraPose = pose,
        plane = plane,
        spawn = Vec2(0f, 0f),
    )

    @Test
    fun sceneBuilderRecoversCupFootprintWithin2cm() {
        val cup = Cylinder(cu = 0.10f, cv = 0.20f, r = 0.04f, h = 0.09f)
        val input = scan("cup" to cup)
        assertTrue(input.masks[0].mask.count() > 1000, "mask rasterised")
        val level = SceneBuilder().build(input)
        assertEquals(1, level.objects.size)
        val obj = level.objects[0]
        val c = obj.centroid
        val centroidErr = c.distanceTo(Vec2(0.10f, 0.20f))
        val heightErr = abs(obj.heightM - 0.09f)
        println("cup centroid=$c (err ${centroidErr * 100} cm) height=${obj.heightM} (err ${heightErr * 100} cm) area=${obj.footprint.area}")
        assertTrue(centroidErr < 0.02f, "centroid error ${centroidErr}m")
        assertTrue(heightErr < 0.02f, "height error ${heightErr}m")
        // Footprint area should be close to the real base (π r² = 0.00503 m²), not the stretched shadow.
        val expectedArea = (PI * 0.04 * 0.04).toFloat()
        assertTrue(abs(obj.footprint.area - expectedArea) < 0.4f * expectedArea, "area ${obj.footprint.area} vs $expectedArea")
        assertEquals(ObjectKind.OBSTACLE, obj.kind)
        assertEquals("cup-1", obj.id)
    }

    @Test
    fun bookBecomesPlatformAndCupObstacle() {
        val book = Box(u0 = -0.10f, u1 = 0.05f, v0 = 0.05f, v1 = 0.15f, h = 0.02f)
        val cup = Cylinder(cu = 0.20f, cv = 0.25f, r = 0.035f, h = 0.10f)
        val level = SceneBuilder().build(scan("book" to book, "cup" to cup))
        assertEquals(2, level.objects.size)
        val b = level.objects.first { it.label == "book" }
        val c = level.objects.first { it.label == "cup" }
        println("book h=${b.heightM} centroid=${b.centroid}; cup h=${c.heightM} centroid=${c.centroid}")
        assertEquals(ObjectKind.PLATFORM, b.kind)
        assertEquals(ObjectKind.OBSTACLE, c.kind)
        assertTrue(abs(b.heightM - 0.02f) < 0.02f, "book height ${b.heightM}")
        assertTrue(b.centroid.distanceTo(Vec2(-0.025f, 0.10f)) < 0.02f, "book centroid ${b.centroid}")
        assertTrue(c.centroid.distanceTo(Vec2(0.20f, 0.25f)) < 0.02f, "cup centroid ${c.centroid}")
        // A tall "book" is an obstacle.
        val tall = SceneBuilder().build(scan("book" to Box(-0.05f, 0.05f, 0.1f, 0.2f, 0.08f)))
        assertEquals(ObjectKind.OBSTACLE, tall.objects[0].kind)
    }

    @Test
    fun floorProfileMakesATwelveCmBoxAPlatform() {
        val box = scan("box" to Box(u0 = -0.10f, u1 = 0.05f, v0 = 0.05f, v1 = 0.15f, h = 0.12f))
        val onFloor = SceneBuilder().build(box, profile = SurfaceProfile.FLOOR).objects.single()
        val onTable = SceneBuilder().build(box).objects.single()
        println("box h=${onFloor.heightM}")
        assertTrue(abs(onFloor.heightM - 0.12f) < 0.02f, "box height ${onFloor.heightM}")
        assertEquals(ObjectKind.PLATFORM, onFloor.kind)
        assertEquals(ObjectKind.OBSTACLE, onTable.kind)
        assertEquals(onTable.copy(kind = ObjectKind.PLATFORM), onFloor, "only the kind depends on the profile")
    }

    @Test
    fun idsStayStableAcrossRescanWhenObjectMovesLessThan5cm() {
        val builder = SceneBuilder()
        val first: Level = builder.build(scan("cup" to Cylinder(0.10f, 0.20f, 0.04f, 0.09f), "cup" to Cylinder(-0.15f, 0.10f, 0.03f, 0.08f)))
        assertEquals(listOf("cup-1", "cup-2"), first.objects.map { it.id })
        // Both cups move by 3 cm: ids are kept (order of masks swapped on purpose).
        val second = builder.build(
            scan("cup" to Cylinder(-0.13f, 0.12f, 0.03f, 0.08f), "cup" to Cylinder(0.13f, 0.20f, 0.04f, 0.09f)),
            previous = first,
        )
        assertEquals("cup-2", second.objects[0].id)
        assertEquals("cup-1", second.objects[1].id)
        // One cup moves 10 cm: it becomes a new object with a fresh id; a book never steals a cup id.
        val third = builder.build(
            scan("cup" to Cylinder(0.20f, 0.20f, 0.04f, 0.09f), "book" to Box(-0.16f, -0.10f, 0.06f, 0.14f, 0.02f)),
            previous = first,
        )
        val movedCup = third.objects.first { it.label == "cup" }
        assertNotEquals("cup-1", movedCup.id)
        assertNotEquals("cup-2", movedCup.id)
        assertEquals("cup-3", movedCup.id)
        assertEquals("book-1", third.objects.first { it.label == "book" }.id)
    }

    /** One SAM "cup" prompt that found every solid in [cups] (instances in the given order = best score first). */
    private fun onePrompt(label: String, vararg cups: Solid): SegmentResult {
        val instances = cups.mapIndexed { i, c ->
            val m = rasterise(c)
            MaskResult.fromBits(label, m.width, m.height, m.bits, score = 0.9f - 0.1f * i)
        }
        val rasters = instances.map { it.toBooleanArray() }
        val merged = BooleanArray(intr.width * intr.height) { px -> rasters.any { it[px] } }
        return SegmentResult(listOf(MaskResult.fromBits(label, intr.width, intr.height, merged, 0.9f).copy(instances = instances)))
    }

    private fun scanOf(result: SegmentResult) = ScanInput(ScanMasks.of(result), intr, pose, plane)

    @Test
    fun twoCupsFromOnePromptGetDistinctStableIds() {
        val builder = SceneBuilder()
        val first = builder.build(scanOf(onePrompt("cup", Cylinder(0.10f, 0.20f, 0.04f, 0.09f), Cylinder(-0.15f, 0.10f, 0.03f, 0.08f))))
        assertEquals(listOf("cup-1", "cup-2"), first.objects.map { it.id }, "one object per detection, distinct ids")
        assertTrue(first.objects[0].centroid.distanceTo(Vec2(0.10f, 0.20f)) < 0.02f)
        assertTrue(first.objects[1].centroid.distanceTo(Vec2(-0.15f, 0.10f)) < 0.02f)

        // Re-scan: both cups moved ~3 cm and SAM now ranks them the other way round; ids follow the centroid.
        val second = builder.build(
            scanOf(onePrompt("cup", Cylinder(-0.13f, 0.12f, 0.03f, 0.08f), Cylinder(0.13f, 0.20f, 0.04f, 0.09f))),
            previous = first,
        )
        assertEquals(listOf("cup-2", "cup-1"), second.objects.map { it.id })

        // Before T15 the merged mask was all the level builder saw: one cup for two.
        val mergedOnly = onePrompt("cup", Cylinder(0.10f, 0.20f, 0.04f, 0.09f), Cylinder(-0.15f, 0.10f, 0.03f, 0.08f))
            .let { r -> SegmentResult(r.masks.map { it.copy(instances = emptyList()) }) }
        assertEquals(1, builder.build(scanOf(mergedOnly)).objects.size)
    }
}
