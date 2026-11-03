package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A 9 cm cup (r = 4 cm), with the masks a perfect segmenter would give. */
class ScanAngleTest {
    private val view = ScanViewFixture()
    private val plane = view.plane
    private val cup = Vec2(0.10f, 0.20f)

    @Test
    fun theLimitIsSeventyFiveDegreesAtAnyRoll() {
        for (roll in listOf(0f, 90f, 180f, 270f)) {
            for (tilt in listOf(30f, 45f, 60f, 74f)) assertFalse(ScanAngle.cameraTooSteep(view.pose(cup, tilt, tilt, roll), plane), "tilt $tilt roll $roll")
            for (tilt in listOf(76f, 80f, 85f, 89f)) assertTrue(ScanAngle.cameraTooSteep(view.pose(cup, tilt, tilt, roll), plane), "tilt $tilt roll $roll")
        }
    }

    @Test
    fun aCameraLookingUpIsNotTooSteep() {
        val up = Pose3(Vec3(0f, 0.5f, 0f), Quat.lookAlong(Vec3(0f, 1f, 0.05f)))
        assertFalse(ScanAngle.cameraTooSteep(up, plane))
    }

    @Test
    fun everyViewThatPassesKeepsTheCupWithinTheGate() {
        for (elevation in listOf(30f, 45f, 60f, 70f, 74f)) {
            val p = view.pose(cup, elevation)
            val split = ScanAngle.split(view.input(p, LabeledMask("cup", view.cupMask(p, cup))))
            assertTrue(split.tooSteep.isEmpty(), "$elevation degrees passes")
            val obj = SceneBuilder().build(split.usable, null, SurfaceProfile.TABLE).objects.single()
            assertTrue(obj.centroid.distanceTo(cup) < 0.02f, "$elevation degrees: centre ${obj.centroid}")
            assertTrue(obj.heightM in 0.08f..0.17f, "$elevation degrees: height ${obj.heightM}, it may only err high")
            assertEquals(ObjectKind.OBSTACLE, obj.kind)
        }
    }

    @Test
    fun aMaskPastTheLimitIsDroppedEvenUnderAModeratelyTiltedPhone() {
        for (elevation in listOf(80f, 85f, 89f)) {
            val p = view.pose(cup, elevationDeg = elevation, tiltDeg = 65f)
            assertFalse(ScanAngle.cameraTooSteep(p, plane), "the phone itself passes, the cup is low in the frame")
            val split = ScanAngle.split(view.input(p, LabeledMask("cup", view.cupMask(p, cup))))
            assertEquals(listOf("cup"), split.tooSteep.map { it.label }, "$elevation degrees")
            assertTrue(split.usable.masks.isEmpty())
        }
    }

    @Test
    fun onlyTheSteepMaskIsDropped() {
        val p = view.pose(cup, elevationDeg = 85f, tiltDeg = 65f)
        val high = BooleanMask.generate(view.intr.width, view.intr.height) { x, y -> x in 1100..1180 && y in 340..380 }
        val split = ScanAngle.split(view.input(p, LabeledMask("cup", view.cupMask(p, cup)), LabeledMask("book", high)))
        assertEquals(listOf("cup"), split.tooSteep.map { it.label })
        assertEquals(listOf("book"), split.usable.masks.map { it.label })
    }

    @Test
    fun anEmptyMaskIsNotTooSteep() {
        val split = ScanAngle.split(view.input(view.pose(cup, 89f), LabeledMask("cup", BooleanMask(view.intr.width, view.intr.height))))
        assertTrue(split.tooSteep.isEmpty())
    }
}
