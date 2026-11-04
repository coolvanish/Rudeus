package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.PlaneFrame
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoScanTest {
    private val plane = PlaneFrame.horizontal(0f)

    /**
     * Camera [dist] m from the anchor along the plane at [bearingDeg] (atan2(v, u)), raised so the optical axis meets
     * the anchor at [elevationDeg] above the table, looking at it. [shift] moves the eye (world metres) without
     * turning the view much.
     */
    private fun pose(bearingDeg: Float, elevationDeg: Float = 45f, dist: Float = 0.5f, shift: Vec3 = Vec3.ZERO): Pose3 {
        val b = rad(bearingDeg)
        val h = dist * tan(rad(elevationDeg))
        val eye = plane.fromPlane(dist * cos(b), dist * sin(b), h) + shift
        return Pose3.lookAt(eye, plane.origin + shift)
    }

    private fun rad(deg: Float) = deg * PI.toFloat() / 180f

    private fun AutoScanPolicy.frame(t: Long, p: Pose3, tracking: Boolean = true, busy: Boolean = false): Int? =
        onFrame(t, p, plane, Vec2.ZERO, tracking, busy)

    /** Frames every 100 ms over [from, to]; returns each (t, result) that fired. */
    private fun AutoScanPolicy.hold(from: Long, to: Long, p: Pose3, tracking: Boolean = true, busy: Boolean = false): List<Pair<Long, Int>> =
        (from..to step 100).mapNotNull { t -> frame(t, p, tracking, busy)?.let { t to it } }

    @Test
    fun firesOnceWhenTheCameraSettlesInANewSector() {
        val policy = AutoScanPolicy()
        val p = pose(bearingDeg = 0f)
        assertNull(policy.frame(0, p))
        assertNull(policy.frame(200, p))
        assertNull(policy.frame(400, p))
        assertEquals(0, policy.frame(600, p))
        assertNull(policy.frame(700, p), "a sector fires once")
        assertEquals(1, policy.fired)
        assertEquals(setOf(0), policy.visited)
        assertEquals(0, policy.sector)
        assertTrue(policy.hold(800, 20_000, p).isEmpty(), "staying in a visited sector never fires again")
    }

    @Test
    fun movingRestartsTheDwell() {
        val policy = AutoScanPolicy()
        val p = pose(bearingDeg = 90f)
        val moved = pose(bearingDeg = 90f, shift = Vec3(0.05f, 0f, 0f))
        assertTrue(policy.hold(0, 200, p).isEmpty())
        assertTrue(policy.hold(300, 700, moved).isEmpty(), "a 5 cm move at t = 300 restarts the dwell")
        assertEquals(listOf(800L to 1), policy.hold(800, 1_000, moved))

        // Turning the view (same eye) past stillAngleRad restarts it too.
        val turning = AutoScanPolicy()
        val eye = pose(bearingDeg = 0f)
        val turned = Pose3.lookAt(eye.position, Vec3(0f, 0f, 0.15f)) // ~12° off the anchor
        assertTrue(turning.hold(0, 200, eye).isEmpty())
        assertTrue(turning.hold(300, 700, turned).isEmpty())
        assertEquals(800L to 0, turning.hold(800, 800, turned).single())
    }

    @Test
    fun aSteepCameraNeverFires() {
        val policy = AutoScanPolicy()
        val steep = pose(bearingDeg = 0f, elevationDeg = 80f)
        assertTrue(ScanAngle.cameraTooSteep(steep, plane))
        assertTrue(policy.hold(0, 5_000, steep).isEmpty())
        assertEquals(0, policy.fired)
        // Lowering the phone to 45° starts a fresh dwell there.
        val fine = pose(bearingDeg = 0f)
        assertEquals(listOf(5_600L to 0), policy.hold(5_100, 5_700, fine))
    }

    @Test
    fun notTrackingOrBusyNeverFires() {
        val p = pose(bearingDeg = 180f)
        val lost = AutoScanPolicy()
        assertTrue(lost.hold(0, 5_000, p, tracking = false).isEmpty())
        // Tracking returns: the dwell starts over from that frame.
        assertEquals(listOf(5_600L to 2), lost.hold(5_100, 5_700, p))

        val busy = AutoScanPolicy()
        assertTrue(busy.hold(0, 5_000, p, busy = true).isEmpty())
        assertEquals(0, busy.fired)
        // Busy does not restart the dwell: the camera has been still all along, so the first free frame fires.
        assertEquals(5_100L to 2, busy.hold(5_100, 5_100, p).single())
    }

    @Test
    fun theCooldownHoldsBetweenSectors() {
        val policy = AutoScanPolicy()
        val s1 = pose(bearingDeg = 90f)
        val s2 = pose(bearingDeg = 180f)
        val fired = policy.hold(0, 1_000, s1).single()
        assertEquals(1, fired.second)
        val t = fired.first
        assertTrue(policy.hold(t + 100, t + 2_000, s2).isEmpty(), "settled in sector 2 at t + 2 s, still cooling down")
        assertTrue(policy.hold(t + 2_100, t + 7_900, s2).isEmpty())
        assertEquals(listOf(t + 8_000 to 2), policy.hold(t + 8_000, t + 8_500, s2), "fires once the 8 s are up")
    }

    @Test
    fun atMostMaxScansPerSession() {
        val policy = AutoScanPolicy(AutoScanParams(cooldownMs = 0))
        var t = 0L
        for (s in 0 until 4) {
            val fired = policy.hold(t, t + 1_000, pose(bearingDeg = s * 90f))
            assertEquals(listOf(s), fired.map { it.second })
            t += 1_100
        }
        assertEquals(4, policy.fired)
        assertEquals(setOf(0, 1, 2, 3), policy.visited)

        assertTrue(
            (0 until 4).flatMap { s -> policy.hold(t + s * 1_100L, t + s * 1_100L + 1_000, pose(bearingDeg = s * 90f)) }.isEmpty(),
            "every sector is visited: going round again never fires",
        )
        // The cap binds before the sectors run out when maxScans < COUNT.
        val capped = AutoScanPolicy(AutoScanParams(maxScans = 2, cooldownMs = 0))
        assertEquals(1, capped.hold(0, 1_000, pose(0f)).size)
        assertEquals(1, capped.hold(1_100, 2_100, pose(90f)).size)
        assertTrue(capped.hold(2_200, 10_000, pose(180f)).isEmpty(), "the third sector never fires at maxScans = 2")

        policy.reset()
        assertEquals(0, policy.fired)
        assertTrue(policy.visited.isEmpty())
        assertNull(policy.sector)
        assertEquals(listOf(500L to 0), policy.hold(0, 600, pose(bearingDeg = 0f)), "reset allows again, no cooldown")
    }

    @Test
    fun aManualScanMarksItsSector() {
        val policy = AutoScanPolicy()
        val s0 = pose(bearingDeg = 10f)
        policy.markScanned(plane.toPlane(s0.position), nowMs = 0)
        assertEquals(setOf(0), policy.visited)
        assertEquals(0, policy.fired)
        assertTrue(policy.hold(100, 10_000, s0).isEmpty(), "sector 0 is already scanned")
        val s1 = pose(bearingDeg = 100f)
        val fired = assertNotNull(policy.hold(10_100, 11_000, s1).firstOrNull())
        assertEquals(1, fired.second)
        assertEquals(10_600L, fired.first)

        // The manual scan restarts the cooldown.
        val cool = AutoScanPolicy()
        cool.markScanned(plane.toPlane(s0.position), nowMs = 0)
        assertTrue(cool.hold(100, 7_900, s1).isEmpty())
        assertEquals(8_000L to 1, cool.hold(8_000, 8_000, s1).single())
    }

    @Test
    fun theSectorIsStickyOnTheBoundary() {
        fun at(deg: Float) = Vec2(cos(rad(deg)), sin(rad(deg)))
        assertEquals(0, ScanSectors.sectorOf(at(0f)))
        assertEquals(0, ScanSectors.sectorOf(at(44f)))
        assertEquals(1, ScanSectors.sectorOf(at(46f)))
        assertEquals(1, ScanSectors.sectorOf(at(90f)))
        assertEquals(2, ScanSectors.sectorOf(at(180f)))
        assertEquals(2, ScanSectors.sectorOf(at(-170f)))
        assertEquals(3, ScanSectors.sectorOf(at(-90f)))
        assertEquals(0, ScanSectors.sectorOf(at(-44f)))
        assertEquals(3, ScanSectors.sectorOf(at(-46f)))
        // Relative to the centre, not the anchor origin.
        assertEquals(2, ScanSectors.sectorOf(Vec2(0.5f, 0f), centre = Vec2(1f, 0f)))

        assertEquals(0, ScanSectors.sectorOf(at(46f), Vec2.ZERO, current = 0))
        assertEquals(0, ScanSectors.sectorOf(at(54f), Vec2.ZERO, current = 0))
        assertEquals(1, ScanSectors.sectorOf(at(56f), Vec2.ZERO, current = 0))
        assertEquals(1, ScanSectors.sectorOf(at(46f), Vec2.ZERO, current = null))
        // Across the wrap between sector 3 and sector 0.
        assertEquals(3, ScanSectors.sectorOf(at(-40f), Vec2.ZERO, current = 3))
        assertEquals(0, ScanSectors.sectorOf(at(-30f), Vec2.ZERO, current = 3))
        assertEquals(0, ScanSectors.sectorOf(at(-50f), Vec2.ZERO, current = 0))

        // The policy keeps the sticky sector frame to frame.
        val policy = AutoScanPolicy()
        policy.frame(0, pose(44f)); assertEquals(0, policy.sector)
        policy.frame(100, pose(46f)); assertEquals(0, policy.sector)
        policy.frame(200, pose(56f)); assertEquals(1, policy.sector)
    }
}
