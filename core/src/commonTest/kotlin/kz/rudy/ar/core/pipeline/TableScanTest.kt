package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableScanTest {
    private val view = ScanViewFixture()
    private val cup = Vec2(0.10f, 0.20f)
    private val steepPose = view.pose(cup, elevationDeg = 85f, tiltDeg = 65f)
    private val steepCup = LabeledMask("cup", view.cupMask(steepPose, cup))
    /** High in the same frame: seen from the side, so it builds. */
    private val usableBook = LabeledMask("book", BooleanMask.generate(view.intr.width, view.intr.height) { x, y -> x in 1100..1180 && y in 340..380 })
    private val tinySpeck = LabeledMask("phone", BooleanMask.generate(view.intr.width, view.intr.height) { x, y -> x in 1150..1151 && y == 360 })

    private fun obj(id: String, label: String, at: Vec2, kind: ObjectKind = ObjectKind.OBSTACLE) =
        LevelObject(id, label, kind, Polygon.rect(at, 0.08f, 0.08f), 0.09f)

    private fun build(vararg masks: LabeledMask, failed: List<String> = emptyList(), previous: Level? = null) =
        TableScan.build(view.input(steepPose, *masks), failed, previous, SurfaceProfile.TABLE)

    @Test
    fun everythingTooSteepLeavesNoLevelToSwapIn() {
        val r = build(steepCup, previous = Level(listOf(obj("cup-1", "cup", cup))))
        assertNull(r.level)
        assertEquals(listOf("cup"), r.tooSteep)
    }

    @Test
    fun aSteepMaskBesideOneThatBuildsNothingIsStillTooSteep() {
        val r = build(steepCup, tinySpeck)
        assertNull(r.level, "not an empty table: there is a cup on it")
    }

    @Test
    fun anEmptyTableIsStillALevel() {
        val r = build()
        assertEquals(0, assertNotNull(r.level).objects.size)
        assertEquals(0, r.missed)
    }

    @Test
    fun aFirstScanCountsTheObjectItCouldNotSee() {
        val r = build(steepCup, usableBook)
        assertEquals(listOf("book"), assertNotNull(r.level).objects.map { it.label })
        assertEquals(1, r.missed)
    }

    @Test
    fun aRescanKeepsTheOldObjectUnderASteepMask() {
        val old = obj("cup-1", "cup", cup)
        val r = build(steepCup, usableBook, previous = Level(listOf(old)))
        assertTrue(old in assertNotNull(r.level).objects)
        assertEquals(listOf(old), r.carried)
        assertEquals(0, r.missed)
    }

    @Test
    fun aCupThatMovedAwayLeavesNoGhostBehind() {
        val elsewhere = obj("cup-2", "cup", Vec2(-0.60f, -0.60f))
        val r = build(steepCup, usableBook, previous = Level(listOf(elsewhere)))
        assertTrue(r.carried.isEmpty())
        assertEquals(1, r.missed)
    }

    @Test
    fun aHandLowInTheFrameIsNoReasonToMoveThePhone() {
        val hand = steepCup.copy(label = "hand")
        val onEmptyTable = build(hand)
        assertEquals(0, assertNotNull(onEmptyTable.level).objects.size, "an empty table, not 'too straight down'")
        assertEquals(0, onEmptyTable.missed)
        val beside = build(hand, usableBook)
        assertEquals(listOf("book"), assertNotNull(beside.level).objects.map { it.label })
        assertEquals(0, beside.missed)
    }

    @Test
    fun aSteepSpeckOfNoiseIsNoReasonEither() {
        val speck = LabeledMask("object", BooleanMask.generate(view.intr.width, view.intr.height) { x, y -> x in 20..22 && y in 360..362 })
        assertTrue(assertNotNull(ScanAngle.split(view.input(steepPose, speck)).tooSteep).isNotEmpty(), "the speck itself is too steep")
        assertEquals(0, assertNotNull(build(speck).level).objects.size)
        assertEquals(0, build(speck, usableBook).missed)
    }

    @Test
    fun aSteepMaskOfAnotherLabelDoesNotKeepAnObject() {
        val old = obj("book-1", "book", cup)
        val r = build(steepCup, usableBook, previous = Level(listOf(old)))
        assertTrue(r.carried.isEmpty())
        assertEquals(1, r.missed)
    }

    @Test
    fun aSteepGenericMaskKeepsTheNamedObjectUnderIt() {
        val old = obj("cup-1", "cup", cup)
        val r = build(steepCup.copy(label = "object"), usableBook, previous = Level(listOf(old)))
        assertEquals(listOf(old), r.carried)
        assertEquals(0, r.missed)
    }

    @Test
    fun anObjectTheFrameNowCutsIsKeptAndAHandStays() {
        val atTheEdge = BooleanMask.generate(view.intr.width, view.intr.height) { x, y -> x in 1100..view.intr.width - 1 && y in 340..380 }
        val old = obj("book-1", "book", Vec2(0.10f, 0.20f))
        val px = kz.rudy.ar.core.geometry.projectToPixel(view.plane.fromPlane(old.centroid, old.heightM / 2f), view.intr, steepPose)
        val over = assertNotNull(px, "the old book is in front of the camera")
        val covering = LabeledMask("book", BooleanMask.generate(view.intr.width, view.intr.height) { x, y ->
            (x >= over.u - 30 && x <= view.intr.width - 1 && y >= over.v - 30 && y <= over.v + 30) || (x == view.intr.width - 1 && y == 0)
        })
        val r = build(covering, usableBook.copy(label = "phone"), previous = Level(listOf(old)))
        assertEquals(listOf("book"), r.cutByFrame)
        assertTrue(old in assertNotNull(r.level).objects)
        assertEquals(0, r.missed)

        val hand = build(LabeledMask("hand", atTheEdge))
        assertEquals(emptyList(), hand.cutByFrame)
    }

    @Test
    fun aFailedPromptKeepsItsObjectsButNotAHand() {
        val book = obj("book-1", "bottle", Vec2(0.30f, 0.30f))
        val hand = obj("hand-1", "bottle", Vec2(0.40f, 0.40f), ObjectKind.DYNAMIC)
        val r = build(usableBook, failed = listOf("bottle"), previous = Level(listOf(book, hand)))
        assertEquals(listOf(book), r.carried)
    }
}
