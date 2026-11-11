package kz.rudy.ar.core.emote

import kz.rudy.ar.core.domain.SpriteView
import kz.rudy.ar.core.domain.SpriteView.BACK
import kz.rudy.ar.core.domain.SpriteView.FRONT
import kz.rudy.ar.core.domain.SpriteView.SIDE
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ViewPickerTest {
    // The camera looks along +v; its right is +u. A facing at bearing θ (degrees, + = to the screen's right) is
    // (sin θ, cos θ): 0 walks away from the camera, 180 towards it.
    private val forward = Vec2(0f, 1f)
    private val right = Vec2(1f, 0f)

    private fun at(deg: Float): Vec2 = (deg * PI / 180.0).let { Vec2(sin(it).toFloat(), cos(it).toFloat()) }

    private fun png(n: Int) = byteArrayOf(n.toByte(), 0x50, 0x4E, 0x47)

    /** Base SIDE looking left (every sheet today), BACK and FRONT drawn. */
    private val sideBase = SpriteViews.of(SIDE, false, mapOf(BACK to png(1), FRONT to png(2)))

    /** Base FRONT (a drawing of the face), BACK and a right-looking SIDE drawn. */
    private val frontBase = SpriteViews.of(FRONT, false, mapOf(BACK to png(1), SIDE to png(3)))

    private fun pick(deg: Float, views: SpriteViews, previous: ViewChoice? = null) = ViewPicker.pick(at(deg), forward, right, previous, views)

    @Test
    fun pickFrontSideBackByBearing() {
        for (deg in listOf(0f, 30f, -30f, 44f, -44f)) assertEquals(ViewChoice(BACK, false), pick(deg, sideBase), "$deg")
        for (deg in listOf(180f, 150f, -150f, 136f, -136f)) assertEquals(ViewChoice(FRONT, false), pick(deg, sideBase), "$deg")
        // SIDE, both screen directions: the base looks left, so walking screen-right mirrors it.
        for (deg in listOf(90f, 46f, 134f)) assertEquals(ViewChoice(SIDE, true), pick(deg, sideBase), "$deg")
        for (deg in listOf(-90f, -46f, -134f)) assertEquals(ViewChoice(SIDE, false), pick(deg, sideBase), "$deg")
        // Vectors need not be unit length, nor the camera axes the same length.
        assertEquals(ViewChoice(SIDE, true), ViewPicker.pick(at(60f) * 3f, forward * 0.2f, right * 5f, null, sideBase))
        assertEquals(ViewChoice(FRONT, false), ViewPicker.pick(at(170f) * 0.01f, forward * 40f, right * 0.3f, null, sideBase))
        // A rotated camera: forward = (1, 0), right = (0, -1) (looking along +u). Facing +u walks away.
        assertEquals(ViewChoice(BACK, false), ViewPicker.pick(Vec2(1f, 0.2f), Vec2(1f, 0f), Vec2(0f, -1f), null, sideBase))
        assertEquals(ViewChoice(SIDE, false), ViewPicker.pick(Vec2(0f, 1f), Vec2(1f, 0f), Vec2(0f, -1f), null, sideBase), "+v is screen-left here")
    }

    @Test
    fun pickHoldsPreviousInsideHysteresis() {
        assertEquals(ViewChoice(BACK, false), pick(54f, sideBase, ViewChoice(BACK, false)))
        assertEquals(ViewChoice(BACK, false), pick(-54f, sideBase, ViewChoice(BACK, false)))
        assertEquals(ViewChoice(SIDE, true), pick(36f, sideBase, ViewChoice(SIDE, true)))
        assertEquals(ViewChoice(SIDE, false), pick(-144f, sideBase, ViewChoice(SIDE, false)))
        assertEquals(ViewChoice(FRONT, false), pick(126f, sideBase, ViewChoice(FRONT, false)))
        assertEquals(ViewChoice(FRONT, false), pick(-126f, sideBase, ViewChoice(FRONT, false)))
        // Without a previous view the plain sectors apply.
        assertEquals(ViewChoice(SIDE, true), pick(54f, sideBase))
        assertEquals(ViewChoice(BACK, false), pick(36f, sideBase))
    }

    @Test
    fun pickSwitchesPastHysteresis() {
        assertEquals(ViewChoice(SIDE, true), pick(56f, sideBase, ViewChoice(BACK, false)))
        assertEquals(ViewChoice(BACK, false), pick(34f, sideBase, ViewChoice(SIDE, true)))
        assertEquals(ViewChoice(FRONT, false), pick(146f, sideBase, ViewChoice(SIDE, true)))
        assertEquals(ViewChoice(SIDE, false), pick(-124f, sideBase, ViewChoice(FRONT, false)))

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
        // BACK wanted, only the base SIDE: the base, mirrored by the SIDE rule (screen-right, drawn looking left).
        assertEquals(ViewChoice(SIDE, true), pick(30f, none))
        assertEquals(ViewChoice(SIDE, false), pick(-30f, none))
        assertEquals(ViewChoice(SIDE, true), pick(170f, none), "FRONT wanted: the base too")
        // FRONT missing, BACK drawn: FRONT falls back to SIDE, BACK is real.
        val backOnly = SpriteViews.of(SIDE, false, mapOf(BACK to png(1)))
        assertEquals(ViewChoice(SIDE, false), pick(-160f, backOnly))
        assertEquals(ViewChoice(BACK, false), pick(20f, backOnly))
        // A FRONT base without a SIDE: the base stands in for SIDE and BACK, mirrored by the SIDE rule; for FRONT it is real.
        val frontNothing = SpriteViews.of(FRONT, false, emptyMap())
        assertEquals(ViewChoice(FRONT, true), pick(90f, frontNothing))
        assertEquals(ViewChoice(FRONT, false), pick(-90f, frontNothing))
        assertEquals(ViewChoice(FRONT, true), pick(20f, frontNothing))
        assertEquals(ViewChoice(FRONT, false), pick(180f, frontNothing))
        assertEquals(ViewChoice(FRONT, false), pick(150f, frontNothing))
        // A FRONT base with BACK only: SIDE wanted -> no SIDE -> the base.
        val frontBack = SpriteViews.of(FRONT, true, mapOf(BACK to png(1)))
        assertEquals(ViewChoice(FRONT, false), pick(90f, frontBack), "base looks right, walks screen-right")
        assertEquals(ViewChoice(FRONT, true), pick(-90f, frontBack))
        assertEquals(ViewChoice(BACK, false), pick(0f, frontBack))
    }

    @Test
    fun pickMirrorsSideByDrawnDirection() {
        // A drawn SIDE always looks right: mirrored when walking screen-left.
        assertEquals(ViewChoice(SIDE, false), pick(90f, frontBase))
        assertEquals(ViewChoice(SIDE, true), pick(-90f, frontBase))
        // The base SIDE uses its own direction: looking left (today's sprites) ...
        assertEquals(ViewChoice(SIDE, true), pick(90f, sideBase))
        assertEquals(ViewChoice(SIDE, false), pick(-90f, sideBase))
        // ... or right.
        val rightBase = SpriteViews.of(SIDE, true, emptyMap())
        assertEquals(ViewChoice(SIDE, false), pick(90f, rightBase))
        assertEquals(ViewChoice(SIDE, true), pick(-90f, rightBase))
        // A real BACK or FRONT picture is never mirrored, whatever the previous flag.
        assertEquals(ViewChoice(BACK, false), pick(30f, frontBase, ViewChoice(BACK, true)))
        assertEquals(ViewChoice(FRONT, false), pick(-150f, frontBase, ViewChoice(SIDE, true)))
        // Inside the dead band (|sin| <= 0.15) the flag of the same picture holds; another picture's flag does not carry.
        val none = SpriteViews.NONE
        assertEquals(ViewChoice(SIDE, true), pick(5f, none, ViewChoice(SIDE, true)))
        assertEquals(ViewChoice(SIDE, false), pick(-5f, none, ViewChoice(SIDE, false)))
        assertEquals(ViewChoice(SIDE, false), pick(5f, none))
        assertEquals(ViewChoice(SIDE, true), pick(10f, none), "sin 10 deg = 0.17: out of the dead band")
        val frontNothing = SpriteViews.of(FRONT, false, emptyMap())
        assertEquals(ViewChoice(FRONT, false), pick(3f, frontNothing, ViewChoice(SIDE, true)), "previous was another picture")
    }

    @Test
    fun pickDegenerateInputKeepsPrevious() {
        val prev = ViewChoice(SIDE, true)
        val zero = Vec2(0f, 0f)
        assertEquals(prev, ViewPicker.pick(zero, forward, right, prev, sideBase), "zero facing")
        assertEquals(prev, ViewPicker.pick(at(90f), zero, right, prev, sideBase), "camera straight above: no forward")
        assertEquals(prev, ViewPicker.pick(at(90f), forward, zero, prev, sideBase), "no right")
        assertEquals(prev, ViewPicker.pick(Vec2(1e-7f, 0f), forward, right, prev, sideBase), "below 1e-6")
        assertEquals(prev, ViewPicker.pick(Vec2(Float.NaN, 0f), forward, right, prev, sideBase), "NaN")
        assertEquals(prev, ViewPicker.pick(Vec2(1f, 0f), forward, forward, prev, sideBase), "forward = right and facing across both")
        assertEquals(ViewChoice(SIDE, false), ViewPicker.pick(zero, forward, right, null, sideBase), "no previous: the base unmirrored")
        assertEquals(ViewChoice(FRONT, false), ViewPicker.pick(at(90f), zero, right, null, frontBase))
    }

    /**
     * A character without drawn views flips exactly as androidApp CharacterNode does today: `along = facing · right`;
     * when |along| > FLIP_DEADBAND (0.15) the quad is flipped iff `(along > 0) != facesRight`, else the flip holds;
     * it starts unflipped.
     */
    @Test
    fun noneMatchesTheHostFlipRule() {
        var hostFlipped = false
        var previous: ViewChoice? = null
        var deg = 0f
        repeat(400) { i ->
            deg = (deg + 37f + (i % 5) * 0.9f) % 360f // wanders through every sector, sometimes into the dead band
            val facing = at(deg)
            val along = facing.dot(right)
            if (abs(along) > 0.15f) hostFlipped = (along > 0f) != false
            val c = ViewPicker.pick(facing, forward, right, previous, SpriteViews.NONE)
            assertEquals(ViewChoice(SIDE, hostFlipped), c, "step $i at $deg deg")
            previous = c
        }
    }

    @Test
    fun pickReturnsSharedInstances() {
        assertSame(pick(90f, sideBase), pick(100f, sideBase))
        assertSame(pick(0f, sideBase), pick(10f, frontBase, ViewChoice(BACK, false)))
    }
}
