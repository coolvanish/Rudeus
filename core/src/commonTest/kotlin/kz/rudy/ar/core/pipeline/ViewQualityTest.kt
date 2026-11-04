package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Orderings only: the numbers behind them are a tuning, the order is the contract [TableScan] relies on. */
class ViewQualityTest {
    private val view = ScanViewFixture()
    private val cup = Vec2(0.10f, 0.20f)

    /** Correctly rounded radians, so 20° and 75° land on the limits themselves. */
    private fun q(elevationDeg: Float, distanceM: Float) = ViewQuality.of((elevationDeg * PI / 180.0).toFloat(), distanceM)

    @Test
    fun aSideViewBeatsASteepOne() {
        assertTrue(q(55f, 0.6f) > q(74f, 0.6f), "55° ${q(55f, 0.6f)} vs 74° ${q(74f, 0.6f)}")
    }

    @Test
    fun aNearViewBeatsAFarOne() {
        assertTrue(q(55f, 0.6f) > q(55f, 1.4f), "0.6 m ${q(55f, 0.6f)} vs 1.4 m ${q(55f, 1.4f)}")
        for (d in listOf(0.2f, 0.3f, 0.4f)) assertEquals(q(55f, 0.5f), q(55f, d), "no gain closer than 0.5 m: $d m")
        var last = q(55f, 0.5f)
        for (i in 1..40) {
            val d = 0.5f + i * 0.1f
            assertTrue(q(55f, d) < last, "falls with distance at $d m")
            last = q(55f, d)
        }
        assertTrue(q(55f, 1.5f) <= q(55f, 0.5f) / 2f + 1e-6f, "halved by 1.5 m: ${q(55f, 1.5f)}")
    }

    @Test
    fun aLowViewBeatsAGrazingOne() {
        assertTrue(q(30f, 0.6f) > q(20f, 0.6f), "30° ${q(30f, 0.6f)} vs 20° ${q(20f, 0.6f)}")
    }

    @Test
    fun theSteepLimitAndGrazingViewsSeeNothing() {
        for (e in listOf(75f, 76f, 80f, 89f, 90f)) assertEquals(0f, q(e, 0.6f), "$e degrees")
        for (e in listOf(-10f, 0f, 10f, 20f)) assertEquals(0f, q(e, 0.6f), "$e degrees")
    }

    @Test
    fun itRisesToTheBestElevationThenFalls() {
        var last = q(20f, 0.6f)
        for (e in 21..55) {
            assertTrue(q(e.toFloat(), 0.6f) > last, "rises at $e degrees")
            last = q(e.toFloat(), 0.6f)
        }
        for (e in 56..75) {
            assertTrue(q(e.toFloat(), 0.6f) < last, "falls at $e degrees")
            last = q(e.toFloat(), 0.6f)
        }
        for (e in 0..90) for (d in listOf(0.1f, 0.64f, 1.5f, 5f)) assertTrue(q(e.toFloat(), d) in 0f..1f, "$e degrees, $d m")
        assertTrue(q(55f, 0.5f) > 0.99f, "the best view is about 1")
    }

    @Test
    fun theUnknownDefaultLosesToAnyRealSighting() {
        for (e in 21..74) for (d in listOf(0.1f, 0.64f, 1.5f, 3f, 10f)) {
            assertTrue(q(e.toFloat(), d) > 0f, "a sighting at $e degrees from $d m beats an unknown 0")
        }
    }

    @Test
    fun aMaskIsJudgedFromTheScanCamera() {
        fun ofCup(elevationDeg: Float, tiltDeg: Float = elevationDeg): Float {
            val p = view.pose(cup, elevationDeg, tiltDeg)
            return ViewQuality.of(view.cupMask(p, cup), view.input(p))
        }
        val side = ofCup(55f)
        val steep = ofCup(72f)
        assertTrue(side > steep, "55° $side vs 72° $steep")
        assertTrue(steep > 0f, "72° is still a sighting")
        assertEquals(0f, ofCup(85f, tiltDeg = 65f), "too steep for SceneBuilder, nothing for the merge either")
        assertEquals(0f, ViewQuality.of(BooleanMask(view.intr.width, view.intr.height), view.input(view.pose(cup, 55f))), "an empty mask")
    }

    @Test
    fun theFrameItsLargestPartSitsInDecides() {
        val p = view.pose(cup, 55f)
        val whole = view.cupMask(p, cup)
        val withSpeck = BooleanMask(whole.width, whole.height, whole.bits.copyOf()).also { m ->
            for (y in 700..702) for (x in 5..7) m[x, y] = true
        }
        assertEquals(ViewQuality.of(whole, view.input(p)), ViewQuality.of(withSpeck, view.input(p)), "a speck elsewhere changes nothing")
    }
}
