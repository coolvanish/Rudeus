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

    /** 05:10:52: a mug and the rabbit both run off the top of the frame. */
    private val edgeScan = ScanInput(
        listOf(
            LabeledMask("cup", outline("580.5,0.5;728.5,0.5;726.5,26.5;715.5,57.5;708.5,66.5;699.5,70.5;648.5,71.5;594.5,67.5;583.5,61.5;577.5,46.5;577.5,13.5")),
            LabeledMask("cup", outline("532.5,244.5;563.5,245.5;580.5,249.5;608.5,248.5;629.5,258.5;637.5,268.5;640.5,291.5;636.5,307.5;642.5,328.5;642.5,369.5;638.5,374.5;606.5,379.5;604.5,396.5;600.5,401.5;554.5,391.5;544.5,385.5;544.5,370.5;576.5,356.5;583.5,346.5;575.5,332.5;535.5,326.5;527.5,320.5;523.5,305.5;521.5,268.5;525.5,252.5")),
            LabeledMask("toy", outline("528.5,0.5;553.5,0.5;573.5,30.5;575.5,46.5;583.5,64.5;596.5,70.5;611.5,70.5;617.5,73.5;616.5,91.5;609.5,103.5;607.5,121.5;599.5,134.5;560.5,141.5;536.5,137.5;520.5,129.5;501.5,103.5;502.5,81.5;506.5,73.5;504.5,70.5;474.5,90.5;457.5,93.5;446.5,91.5;442.5,68.5;449.5,52.5;472.5,39.5;495.5,32.5;508.5,16.5;525.5,5.5")),
            LabeledMask("remote", outline("788.5,53.5;799.5,59.5;810.5,95.5;722.5,224.5;705.5,243.5;698.5,243.5;689.5,214.5;689.5,204.5;728.5,146.5;775.5,67.5")),
        ),
        Intrinsics(933.2064f, 932.54755f, 652.90564f, 357.89487f, 1280, 720),
        pose("0.02504,-0.03297,0.24479,-0.160407,-0.122321,-0.685924,0.699153"),
        PlaneFrame.fromAnchorPose(pose("-0.03871,-0.37649,-0.34868,0.006215,0.058010,-0.000905,0.998296")),
    )

    @Test
    fun aCupAndAToyAtTheEdgeOfTheFrameAreStillObjects() {
        val r = TableScan.build(edgeScan, emptyList(), null, SurfaceProfile.TABLE)
        assertEquals(listOf("cup", "cup", "remote", "toy"), assertNotNull(r.level).objects.map { it.label }.sorted())
        assertEquals(emptyList(), r.cutByFrame)
    }

    @Test
    fun aRescanKeepsWhatItKnewOverAMaskAtTheEdge() {
        val first = assertNotNull(TableScan.build(edgeScan, emptyList(), null, SurfaceProfile.TABLE).level)
        val again = TableScan.build(edgeScan, emptyList(), first, SurfaceProfile.TABLE)
        assertEquals(first.objects.map { it.id }.sorted(), assertNotNull(again.level).objects.map { it.id }.sorted())
        assertTrue(again.carried.isNotEmpty() && again.carried.all { it in first.objects }, "carried ${again.carried.map { it.id }}, cut ${again.cutByFrame}")
    }
}
