package kz.rudy.ar.core.emote

import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.domain.SpriteView.BACK
import kz.rudy.ar.core.domain.SpriteView.FRONT
import kz.rudy.ar.core.domain.SpriteView.SIDE
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ViewPickerTest {
    // The camera stands 1 m towards -v from the character, so we look at it along +v; screen-right is then -u (the
    // right-handed plane frame, see ViewPicker). A facing at bearing θ (degrees, + = towards screen-right) is
    // cos θ · F + sin θ · R = (-sin θ, cos θ): 0 walks away from the camera, 180 towards it.
    private val toCamera = Vec2(0f, -1f)

    private fun at(deg: Float): Vec2 = (deg * PI / 180.0).let { Vec2(-sin(it).toFloat(), cos(it).toFloat()) }

    private fun png(n: Int) = byteArrayOf(n.toByte(), 0x50, 0x4E, 0x47)

    /** Base SIDE looking left (every v1.0 sheet), BACK and FRONT drawn: every sector has its own picture. */
    private val sideBase = SpriteViews.of(SIDE, false, mapOf(BACK to png(1), FRONT to png(2)))

    /** Base FRONT (a drawing of the face), BACK and a right-looking SIDE drawn. */
    private val frontBase = SpriteViews.of(FRONT, false, mapOf(BACK to png(1), SIDE to png(3)))

    private fun pick(deg: Float, views: SpriteViews, previous: ViewChoice? = null) = ViewPicker.pick(at(deg), toCamera, previous, views)

    private fun shown(c: ViewChoice) = c.view to c.mirrored

    @Test
    fun pickFrontSideBackByBearing() {
        for (deg in listOf(0f, 30f, -30f, 44f, -44f)) assertEquals(ViewChoice(BACK, false, BACK), pick(deg, sideBase), "$deg")
        for (deg in listOf(180f, 150f, -150f, 136f, -136f)) assertEquals(ViewChoice(FRONT, false, FRONT), pick(deg, sideBase), "$deg")
        // SIDE, both screen directions: the base looks left, so walking screen-right mirrors it.
        for (deg in listOf(90f, 46f, 134f)) assertEquals(ViewChoice(SIDE, true, SIDE), pick(deg, sideBase), "$deg")
        for (deg in listOf(-90f, -46f, -134f)) assertEquals(ViewChoice(SIDE, false, SIDE), pick(deg, sideBase), "$deg")
        // Screen-right is -u when we look along +v: a character walking towards -u walks screen-right.
        assertEquals(ViewChoice(SIDE, true, SIDE), ViewPicker.pick(Vec2(-1f, 0f), toCamera, null, sideBase))
        // Vectors need not be unit length.
        assertEquals(ViewChoice(SIDE, true, SIDE), ViewPicker.pick(at(60f) * 3f, toCamera * 0.2f, null, sideBase))
        assertEquals(ViewChoice(FRONT, false, FRONT), ViewPicker.pick(at(170f) * 0.01f, toCamera * 40f, null, sideBase))
        // The camera at +u: we look along -u, screen-right is (0, -1).
        val east = Vec2(2f, 0f)
        assertEquals(ViewChoice(BACK, false, BACK), ViewPicker.pick(Vec2(-1f, 0.2f), east, null, sideBase))
        assertEquals(ViewChoice(SIDE, true, SIDE), ViewPicker.pick(Vec2(0f, -1f), east, null, sideBase), "-v is screen-right here")
        assertEquals(ViewChoice(SIDE, false, SIDE), ViewPicker.pick(Vec2(0f, 1f), east, null, sideBase))
    }

    @Test
    fun pickHoldsPreviousInsideHysteresis() {
        assertEquals(ViewChoice(BACK, false, BACK), pick(54f, sideBase, pick(40f, sideBase)))
        assertEquals(ViewChoice(BACK, false, BACK), pick(-54f, sideBase, pick(0f, sideBase)))
        assertEquals(ViewChoice(SIDE, true, SIDE), pick(36f, sideBase, pick(90f, sideBase)))
        assertEquals(ViewChoice(SIDE, false, SIDE), pick(-144f, sideBase, pick(-90f, sideBase)))
        assertEquals(ViewChoice(FRONT, false, FRONT), pick(126f, sideBase, pick(170f, sideBase)))
        assertEquals(ViewChoice(FRONT, false, FRONT), pick(-126f, sideBase, pick(180f, sideBase)))
        // Without a previous choice the plain sectors apply.
        assertEquals(ViewChoice(SIDE, true, SIDE), pick(54f, sideBase))
        assertEquals(ViewChoice(BACK, false, BACK), pick(36f, sideBase))
    }

    @Test
    fun pickSwitchesPastHysteresis() {
        assertEquals(ViewChoice(SIDE, true, SIDE), pick(56f, sideBase, pick(0f, sideBase)))
        assertEquals(ViewChoice(BACK, false, BACK), pick(34f, sideBase, pick(90f, sideBase)))
        assertEquals(ViewChoice(FRONT, false, FRONT), pick(146f, sideBase, pick(90f, sideBase)))
        assertEquals(ViewChoice(SIDE, false, SIDE), pick(-124f, sideBase, pick(180f, sideBase)))

        // A slow turn towards the camera and back, in half-degree steps (no step lands on a boundary): one switch per
        // crossing, 10 deg past the boundary each way.
        var previous: ViewChoice? = null
        val switches = ArrayList<Pair<Float, SpriteView>>()
        for (deg in (0..179).map { it + 0.5f } + (179 downTo 0).map { it + 0.5f }) {
            val c = pick(deg, sideBase, previous)
            if (c.view != previous?.view) switches += deg to c.view
            previous = c
        }
        assertEquals(listOf(0.5f to BACK, 55.5f to SIDE, 145.5f to FRONT, 124.5f to SIDE, 34.5f to BACK), switches)
    }

    @Test
    fun pickFallsBackWhenAViewIsMissing() {
        val none = SpriteViews.NONE
        // BACK sector, only the base SIDE: the base, mirrored by the SIDE rule (screen-right, drawn looking left).
        assertEquals(ViewChoice(SIDE, true, BACK), pick(30f, none))
        assertEquals(ViewChoice(SIDE, false, BACK), pick(-30f, none))
        assertEquals(ViewChoice(SIDE, true, FRONT), pick(170f, none), "FRONT sector: the base too")
        // FRONT missing, BACK drawn: FRONT falls back to SIDE, BACK is real.
        val backOnly = SpriteViews.of(SIDE, false, mapOf(BACK to png(1)))
        assertEquals(ViewChoice(SIDE, false, FRONT), pick(-160f, backOnly))
        assertEquals(ViewChoice(BACK, false, BACK), pick(20f, backOnly))
        // A FRONT base without a SIDE: the base stands in for SIDE and BACK, mirrored by the SIDE rule; for FRONT it is real.
        val frontNothing = SpriteViews.of(FRONT, false, emptyMap())
        assertEquals(ViewChoice(FRONT, true, SIDE), pick(90f, frontNothing))
        assertEquals(ViewChoice(FRONT, false, SIDE), pick(-90f, frontNothing))
        assertEquals(ViewChoice(FRONT, true, BACK), pick(20f, frontNothing))
        assertEquals(ViewChoice(FRONT, false, FRONT), pick(180f, frontNothing))
        assertEquals(ViewChoice(FRONT, false, FRONT), pick(150f, frontNothing))
        // A FRONT base looking right with BACK only: SIDE wanted -> no SIDE -> the base.
        val frontBack = SpriteViews.of(FRONT, true, mapOf(BACK to png(1)))
        assertEquals(ViewChoice(FRONT, false, SIDE), pick(90f, frontBack), "base looks right, walks screen-right")
        assertEquals(ViewChoice(FRONT, true, SIDE), pick(-90f, frontBack))
        assertEquals(ViewChoice(BACK, false, BACK), pick(0f, frontBack))
    }

    @Test
    fun pickMirrorsSideByDrawnDirection() {
        // A drawn SIDE always looks right: mirrored when walking screen-left.
        assertEquals(ViewChoice(SIDE, false, SIDE), pick(90f, frontBase))
        assertEquals(ViewChoice(SIDE, true, SIDE), pick(-90f, frontBase))
        // The base SIDE uses its own direction: looking left (today's sprites) ...
        assertEquals(ViewChoice(SIDE, true, SIDE), pick(90f, sideBase))
        assertEquals(ViewChoice(SIDE, false, SIDE), pick(-90f, sideBase))
        // ... or right.
        val rightBase = SpriteViews.of(SIDE, true, emptyMap())
        assertEquals(ViewChoice(SIDE, false, SIDE), pick(90f, rightBase))
        assertEquals(ViewChoice(SIDE, true, SIDE), pick(-90f, rightBase))
        // A real BACK or FRONT picture is never mirrored, whatever the previous flag.
        assertEquals(ViewChoice(BACK, false, BACK), pick(30f, frontBase, ViewChoice(BACK, true, BACK)))
        assertEquals(ViewChoice(FRONT, false, FRONT), pick(-150f, frontBase, ViewChoice(SIDE, true, SIDE)))
        // Inside the dead band (|sin| <= 0.15) the flag of the same picture holds; another picture's flag does not carry.
        val none = SpriteViews.NONE
        assertEquals(ViewChoice(SIDE, true, BACK), pick(5f, none, ViewChoice(SIDE, true, BACK)))
        assertEquals(ViewChoice(SIDE, false, BACK), pick(-5f, none, ViewChoice(SIDE, false, BACK)))
        assertEquals(ViewChoice(SIDE, false, BACK), pick(5f, none))
        assertEquals(ViewChoice(SIDE, true, BACK), pick(10f, none), "sin 10 deg = 0.17: out of the dead band")
        val frontNothing = SpriteViews.of(FRONT, false, emptyMap())
        assertEquals(ViewChoice(FRONT, false, BACK), pick(3f, frontNothing, ViewChoice(SIDE, true, SIDE)), "previous was another picture")
    }

    @Test
    fun pickDegenerateInputKeepsPrevious() {
        val prev = ViewChoice(SIDE, true, SIDE)
        val zero = Vec2(0f, 0f)
        assertEquals(prev, ViewPicker.pick(zero, toCamera, prev, sideBase), "zero facing")
        assertEquals(prev, ViewPicker.pick(Vec2(1e-7f, 0f), toCamera, prev, sideBase), "facing below 1e-6")
        assertEquals(prev, ViewPicker.pick(Vec2(Float.NaN, 0f), toCamera, prev, sideBase), "NaN facing")
        assertEquals(prev, ViewPicker.pick(at(90f), zero, prev, sideBase), "camera straight above")
        assertEquals(prev, ViewPicker.pick(at(90f), Vec2(0.005f, 0.007f), prev, sideBase), "camera within 1 cm in the plane")
        assertEquals(prev, ViewPicker.pick(at(90f), Vec2(Float.NaN, 1f), prev, sideBase), "NaN camera")
        assertEquals(ViewChoice(SIDE, false, SIDE), ViewPicker.pick(zero, toCamera, null, sideBase), "no previous: the base unmirrored")
        assertEquals(ViewChoice(FRONT, false, FRONT), ViewPicker.pick(at(90f), zero, null, frontBase))
        assertEquals(ViewChoice(SIDE, true, SIDE), ViewPicker.pick(at(90f), Vec2(0f, -0.011f), null, sideBase), "1.1 cm is enough")
    }

    /**
     * The oracle is the Android host's flip, copied from androidApp/src/main/kotlin/kz/rudy/ar/ar/CharacterNode.kt:211-219
     * (`flip` starts at 1f, line 179; FLIP_DEADBAND = 0.15f, line 76):
     * ```
     * val yawRad = atan2(toCam.u, toCam.v)
     * val right = Vec2(cos(yawRad), -sin(yawRad))
     * val along = state.facing.dot(right)
     * if (abs(along) > FLIP_DEADBAND) { val wantsRight = along > 0f; flip = if (wantsRight == sprite.facesRight) 1f else -1f }
     * ```
     * A character without drawn views must get mirrored == (flip == -1f) on every frame of a walk in which both the
     * character and the camera move, the facing crosses every quadrant, and the dead band holds the flag.
     */
    @Test
    fun noneMatchesTheHostFlipRule() {
        for (facesRight in listOf(false, true)) {
            val views = SpriteViews.of(SIDE, facesRight, emptyMap())
            val rnd = Random(41)
            var flip = 1f
            var previous: ViewChoice? = null
            var pos = Vec2(0f, 0f)
            var heading = 0f
            var orbit = 0f
            var held = 0
            var mirroredFrames = 0
            val quadrants = HashSet<Int>()
            repeat(2_000) { i ->
                // Mostly small turns, now and then a sharp one; the camera orbits at 0.3-1.1 m.
                heading += if (rnd.nextInt(10) == 0) rnd.nextFloat() * 6f - 3f else rnd.nextFloat() * 0.3f - 0.15f
                val facing = Vec2(cos(heading), sin(heading))
                // It walks where it faces but stays within 20 cm of the origin, so the camera is never closer than 10 cm.
                pos = (pos + facing * 0.01f).let { if (it.length > 0.2f) it * (0.2f / it.length) else it }
                orbit += 0.013f
                val r = 0.7f + 0.4f * sin(orbit * 3.1f)
                val camera = Vec2(r * cos(orbit), r * sin(orbit))
                val toCam = camera - pos

                val yawRad = atan2(toCam.u, toCam.v)
                val right = Vec2(cos(yawRad), -sin(yawRad))
                val along = facing.dot(right)
                if (abs(along) > 0.15f) {
                    val wantsRight = along > 0f
                    flip = if (wantsRight == facesRight) 1f else -1f
                } else {
                    held++
                }

                val c = ViewPicker.pick(facing, toCam, previous, views)
                assertEquals(SIDE, c.view, "frame $i")
                assertEquals(flip < 0f, c.mirrored, "frame $i, facesRight=$facesRight, along=$along")
                if (c.mirrored) mirroredFrames++
                val fwd = -facing.dot(toCam) / toCam.length
                quadrants += (if (fwd >= 0f) 0 else 2) + (if (along >= 0f) 0 else 1)
                previous = c
            }
            assertTrue(held > 20, "the walk spends frames in the dead band ($held)")
            assertTrue(mirroredFrames in 100..1_900, "both flags occur ($mirroredFrames)")
            assertEquals(setOf(0, 1, 2, 3), quadrants, "the facing crosses every quadrant")
        }
    }

    /** The sets a child's drawing really produces when a Muse call fails, plus a full set. */
    private val fallbackSets: List<Pair<String, SpriteViews>> = listOf(
        "FRONT {BACK}" to SpriteViews.of(FRONT, false, mapOf(BACK to png(1))),
        "FRONT {}" to SpriteViews.of(FRONT, false, emptyMap()),
        "BACK {FRONT}" to SpriteViews.of(BACK, false, mapOf(FRONT to png(2))),
        "SIDE-left {}" to SpriteViews.of(SIDE, false, emptyMap()),
        "SIDE-right {BACK}" to SpriteViews.of(SIDE, true, mapOf(BACK to png(1))),
        "full" to SpriteViews.of(SIDE, false, mapOf(BACK to png(1), FRONT to png(2))),
    )

    /** Feeds [bearings] frame by frame from no previous choice; the frames at which (view, mirrored) changed. */
    private fun changes(bearings: List<Float>, views: SpriteViews): List<Int> {
        var previous: ViewChoice? = null
        val out = ArrayList<Int>()
        bearings.forEachIndexed { i, deg ->
            val c = pick(deg, views, previous)
            if (previous != null && shown(c) != shown(previous!!)) out += i
            previous = c
        }
        return out
    }

    @Test
    fun aMissingViewNeverFlickersOnASweep() {
        // 0..360 and back in half-degree steps, a quarter degree off so no step lands exactly on a boundary. The pair
        // can only change at the four sector boundaries and the two dead bands (straight towards / away from us).
        val up = (0 until 720).map { it * 0.5f + 0.25f }
        for ((name, views) in fallbackSets) {
            for (sweep in listOf(up, up.reversed(), up + up.reversed())) {
                val at = changes(sweep, views)
                val boundaries = if (sweep.size > 720) 12 else 6
                assertTrue(at.size <= boundaries, "$name: ${at.size} changes in ${sweep.size} frames: $at")
                assertTrue(at.zipWithNext().all { (a, b) -> b - a >= 20 }, "$name: changes closer than 10 deg: $at")
            }
        }
    }

    @Test
    fun aMissingViewNeverFlickersAroundABoundary() {
        // The review's cases first: a FRONT base standing in for SIDE at 124/126 deg, a BACK base at 54/56 deg.
        assertTrue(changes(List(20) { if (it % 2 == 0) 124f else 126f }, SpriteViews.of(FRONT, false, mapOf(BACK to png(1)))).size <= 1)
        assertTrue(changes(List(20) { if (it % 2 == 0) 126f else 124f }, SpriteViews.of(FRONT, false, emptyMap())).size <= 1)
        assertTrue(changes(List(20) { if (it % 2 == 0) 56f else 54f }, SpriteViews.of(BACK, false, mapOf(FRONT to png(2)))).size <= 1)
        // Every boundary, +-1 deg around it, starting on either side, for every set.
        for ((name, views) in fallbackSets) {
            for (b in listOf(0f, 45f, 135f, 180f, 225f, 315f, -45f, -135f)) {
                for (first in listOf(-1f, 1f)) {
                    val wobble = List(3) { b + first } + List(30) { if (it % 2 == 0) b - first else b + first }
                    val at = changes(wobble, views)
                    assertTrue(at.size <= 1, "$name around $b from ${b + first}: changes at $at")
                }
            }
        }
    }

    @Test
    fun aSteadyBearingGivesOneChoice() {
        for ((name, views) in fallbackSets) {
            for (k in 0 until 720) {
                val deg = k * 0.5f
                val first = pick(deg, views)
                var previous = first
                repeat(4) {
                    val c = pick(deg, views, previous)
                    assertEquals(first, c, "$name at $deg")
                    previous = c
                }
            }
        }
    }

    @Test
    fun pickReturnsSharedInstances() {
        assertSame(pick(90f, sideBase), pick(100f, sideBase))
        assertSame(pick(0f, sideBase), pick(10f, frontBase, ViewChoice(BACK, false, BACK)))
    }
}
