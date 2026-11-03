package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Intrinsics
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real scan from the Pixel, 2026-11-03 04:19 (`scan_replay` lines in docs/evidence/t25-pixel-2026-11-03.log): a mug about
 * 9 cm wide seen low, about 20° above the table, and a finger over the lens that SAM called a laptop and a remote.
 */
class RealScanTest {
    private val intr = Intrinsics(938.54724f, 937.1438f, 637.3199f, 356.5582f, 1280, 720)
    private val camera = pose("0.47919,-0.17587,-0.46294,0.467490,-0.230855,0.509261,-0.684699")
    private val plane = PlaneFrame.fromAnchorPose(pose("-0.03909,-0.50265,-0.70040,0.000000,0.536565,0.000000,0.843859"))
    private val mug = LabeledMask("cup", outline("542.5,79.5;617.5,93.5;646.5,105.5;651.5,116.5;652.5,141.5;645.5,169.5;636.5,182.5;618.5,186.5;540.5,181.5;530.5,175.5;526.5,161.5;526.5,126.5;534.5,89.5"))
    private val laptop = LabeledMask("laptop", outline("1279.5,545.5;1279.5,719.5;987.5,719.5;1017.5,698.5"))
    private val remote = LabeledMask("remote", outline("868.5,609.5;929.5,633.5;917.5,652.5;862.5,716.5;856.5,719.5;783.5,719.5"))
    private val input = ScanInput(listOf(mug, laptop, remote), intr, camera, plane)

    private fun pose(s: String): Pose3 {
        val a = s.split(',').map { it.toFloat() }
        return Pose3(Vec3(a[0], a[1], a[2]), Quat(a[3], a[4], a[5], a[6]))
    }

    private fun outline(s: String): BooleanMask {
        val pts = s.split(';').map { p -> p.split(',').let { it[0].toFloat() to it[1].toFloat() } }
        return BooleanMask.generate(1280, 720) { x, y ->
            var inside = false
            var j = pts.size - 1
            for (i in pts.indices) {
                val (xi, yi) = pts[i]
                val (xj, yj) = pts[j]
                if ((yi > y + 0.5f) != (yj > y + 0.5f) && x + 0.5f < (xj - xi) * (y + 0.5f - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            inside
        }
    }

    @Test
    fun aMugSeenLowIsAsDeepAsItIsWideAndKeepsItsHeight() {
        val mugOnly = assertNotNull(SceneBuilder().extract(mug.mask, input, mug.label))
        val g: Vec2 = plane.toPlane(camera.position)
        val dir = (mugOnly.footprint.centroid() - g).normalized()
        val along = mugOnly.footprint.points.map { (it - g).dot(dir) }
        val across = mugOnly.footprint.points.map { it.dot(dir.perp()) }
        val depth = along.max() - along.min()
        val width = across.max() - across.min()
        assertTrue(width in 0.07f..0.12f, "width $width")
        assertTrue(depth in 0.07f..0.12f, "depth $depth: was 31 cm")
        assertTrue(mugOnly.heightM in 0.06f..0.13f, "height ${mugOnly.heightM}: was 2 cm")
    }

    /** 04:43, a plush rabbit about 14° above the table: its footprint read 42 cm deep and ran off across the table. */
    @Test
    fun aPlushToySeenLowIsNoLongWall() {
        val cam = pose("-0.01696,0.03006,0.12662,-0.073319,-0.198171,-0.718318,0.662851")
        val frame = PlaneFrame.fromAnchorPose(pose("0.03626,-0.29019,-0.59483,-0.002559,-0.036821,0.002695,0.999315"))
        val intrinsics = Intrinsics(931.0171f, 934.3986f, 649.98157f, 356.21692f, 1280, 720)
        val toy = LabeledMask("toy", outline("516.5,266.5;526.5,267.5;536.5,273.5;553.5,293.5;558.5,310.5;567.5,319.5;590.5,319.5;597.5,322.5;598.5,340.5;593.5,350.5;594.5,365.5;584.5,382.5;546.5,391.5;528.5,390.5;510.5,383.5;493.5,362.5;495.5,335.5;491.5,335.5;469.5,356.5;447.5,362.5;441.5,359.5;436.5,340.5;444.5,323.5;480.5,304.5;489.5,290.5"))
        val c = assertNotNull(SceneBuilder().extract(toy.mask, ScanInput(listOf(toy), intrinsics, cam, frame), toy.label))
        val g = frame.toPlane(cam.position)
        val dir = (c.footprint.centroid() - g).normalized()
        val along = c.footprint.points.map { (it - g).dot(dir) }
        assertTrue(along.max() - along.min() < 0.18f, "depth ${along.max() - along.min()}: was 42 cm")
        assertTrue(c.heightM in 0.08f..0.20f, "height ${c.heightM}")
    }

    @Test
    fun aFingerOverTheLensIsNoObject() {
        val r = TableScan.build(input, emptyList(), null, SurfaceProfile.TABLE)
        assertEquals(listOf("cup"), assertNotNull(r.level).objects.map { it.label })
        assertEquals(listOf("laptop", "remote"), r.cutByFrame)
        assertEquals(0, r.missed)
    }
}
