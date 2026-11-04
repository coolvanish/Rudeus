package kz.rudy.ar.core.pipeline

import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.geometry.BooleanMask
import kz.rudy.ar.core.geometry.Pose3
import kz.rudy.ar.core.geometry.Quat
import kz.rudy.ar.core.geometry.Vec3
import kz.rudy.ar.core.geometry.intersectRayPlane
import kz.rudy.ar.core.geometry.pixelRay
import kz.rudy.ar.core.geometry.projectToPixel
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A rescan merges into the level in play ([TableScan.build]). Perfect masks from [ScanViewFixture]: a side view at
 * 55° from 0.64 m builds a cup within the gate, a 72° view still builds it, only worse.
 */
class ScanMergeTest {
    private val view = ScanViewFixture()
    private val cup = Vec2(0.10f, 0.20f)
    private val side = view.pose(cup, 55f)
    private val steep = view.pose(cup, 72f)

    private fun scan(
        pose: Pose3,
        vararg masks: LabeledMask,
        previous: Level? = null,
        standing: List<Vec2> = emptyList(),
        failed: List<String> = emptyList(),
    ): TableScan.Result = TableScan.build(view.input(pose, *masks), failed, previous, SurfaceProfile.TABLE, standing)

    private fun cupMask(pose: Pose3, at: Vec2 = cup, label: String = "cup") = LabeledMask(label, view.cupMask(pose, at))
    private fun bookMask(pose: Pose3, at: Vec2 = cup) = LabeledMask("book", view.boxMask(pose, at, 0.15f, 0.10f, 0.02f))

    /** Farther along the [side] view than the cup and clear of it: a scan that saw the table, not nothing. */
    private val rest = Vec2(0.10f, 0.0f)
    private fun phoneMask(pose: Pose3, at: Vec2 = rest) = LabeledMask("phone", view.boxMask(pose, at, 0.07f, 0.14f, 0.01f))

    /** Where the optical axis of [pose] meets the table. */
    private fun aimOf(pose: Pose3): Vec2 =
        view.plane.toPlane(assertNotNull(intersectRayPlane(pixelRay(view.intr.cx, view.intr.cy, view.intr, pose), view.plane)))

    private fun middleIn(o: LevelObject, m: LabeledMask, pose: Pose3): Boolean {
        val box = assertNotNull(m.mask.boundingBox())
        val px = projectToPixel(view.plane.fromPlane(o.centroid, o.heightM / 2f), view.intr, pose) ?: return false
        return px.u >= box.minX && px.u <= box.maxX + 1 && px.v >= box.minY && px.v <= box.maxY + 1
    }

    /** [pose] turned about the vertical through the phone; negative = to the right. */
    private fun turned(pose: Pose3, yawDeg: Float) =
        Pose3(pose.position, Quat.fromAxisAngle(Vec3.UP, yawDeg * PI.toFloat() / 180f) * pose.rotation)

    private fun level(r: TableScan.Result): Level = assertNotNull(r.level, "a level to swap in")

    private fun projects(o: LevelObject, pose: Pose3): Boolean =
        projectToPixel(view.plane.fromPlane(o.centroid, o.heightM / 2f), view.intr, pose)
            ?.let { it.u in 0f..view.intr.width.toFloat() && it.v in 0f..view.intr.height.toFloat() } == true

    @Test
    fun anObjectOutsideTheFrameIsCarried() {
        val left = Vec2(-0.30f, 0.20f)
        val atLeft = view.pose(left, 55f)
        val first = level(scan(atLeft, cupMask(atLeft, left)))
        val known = first.objects.single()
        val right = turned(atLeft, -60f)
        assertTrue(!projects(known, right), "the cup is out of the turned frame")
        val onTheRight = phoneMask(right, aimOf(right))

        val once = scan(right, onTheRight, previous = first)
        assertEquals(listOf("cup", "phone"), level(once).objects.map { it.label }.sorted(), "the turned frame saw a phone")
        assertTrue(known in level(once).objects, "not seen to be gone: it was not in the frame, unseen stays 0")
        assertEquals(listOf(known), once.carried)
        assertEquals(emptyList(), once.removed)

        val twice = scan(right, onTheRight, previous = level(once))
        assertTrue(known in level(twice).objects, "a turned phone never forgets it")
        assertEquals(emptyList(), twice.removed)
    }

    @Test
    fun anObjectCoveredByAMaskIsCarried() {
        // Behind the cup and 2 cm clear of it: a book the cup stands on would be a sighting of the book.
        val book = LevelObject("book-1", "book", ObjectKind.PLATFORM, Polygon.rect(cup + Vec2(0f, -0.085f), 0.15f, 0.05f), 0.02f, quality = 0.8f)
        val newCup = cupMask(side)
        assertTrue(projects(book, side), "the book's place is in the frame")
        assertTrue(middleIn(book, newCup, side), "the cup's mask hides it")

        val r = scan(side, newCup, previous = Level(listOf(book)))
        val objects = level(r).objects
        assertEquals(listOf("book", "cup"), objects.map { it.label }.sorted())
        assertTrue(book in objects, "the book behind the cup keeps every byte")
        assertEquals(listOf(book), r.carried)
        assertEquals(emptyList(), r.removed)

        val again = scan(side, newCup, previous = level(r))
        assertTrue(book in level(again).objects, "still hidden, still there")
        assertEquals(emptyList(), again.removed)
    }

    @Test
    fun anEmptyPlaceIsForgottenOnlyAfterTwoScans() {
        val first = level(scan(side, cupMask(side), phoneMask(side)))
        val known = first.objects.single { it.label == "cup" }
        assertTrue(!middleIn(known, phoneMask(side), side), "the phone hides nothing")

        val once = scan(side, phoneMask(side), previous = first)
        val kept = level(once).objects.single { it.id == known.id }
        assertEquals(1, kept.unseen)
        assertEquals(known.footprint, kept.footprint)
        assertEquals(listOf(kept), once.carried)
        assertEquals(emptyList(), once.removed)

        val twice = scan(side, phoneMask(side), previous = level(once))
        assertEquals(listOf(known.id), twice.removed)
        assertEquals(listOf("phone"), level(twice).objects.map { it.label })
        assertEquals(emptyList(), twice.carried)
    }

    @Test
    fun aSightingResetsUnseen() {
        val first = level(scan(side, cupMask(side), phoneMask(side)))
        val id = first.objects.single { it.label == "cup" }.id
        val once = level(scan(side, phoneMask(side), previous = first))
        assertEquals(1, once.objects.single { it.id == id }.unseen)

        val seen = scan(side, cupMask(side), phoneMask(side), previous = once)
        val back = level(seen).objects.single { it.id == id }
        assertEquals(0, back.unseen)
        assertTrue(id in seen.kept, "the same view is no better: kept, only unseen reset")
        assertEquals(emptyList(), seen.carried)

        val gone = level(scan(side, phoneMask(side), previous = level(seen)))
        assertEquals(1, gone.objects.single { it.id == id }.unseen, "the count starts again from the sighting")
    }

    @Test
    fun aNewObjectAppearsAtOnce() {
        val elsewhere = LevelObject("book-1", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2(-0.60f, 0.20f), 0.15f, 0.10f), 0.02f, quality = 0.8f)
        val r = scan(side, cupMask(side), previous = Level(listOf(elsewhere)))
        val objects = level(r).objects
        val newCup = objects.single { it.label == "cup" }
        assertTrue(newCup.centroid.distanceTo(cup) < 0.02f, "centre ${newCup.centroid}")
        assertTrue(newCup.quality > 0f, "a real sighting has a quality")
        assertEquals(0, newCup.unseen)
        assertTrue(elsewhere in objects)
        assertEquals(emptyList(), r.replaced + r.kept + r.removed)
    }

    @Test
    fun aBetterViewReplacesTheFootprint() {
        val fromSide = level(scan(side, cupMask(side))).objects.single()
        val old = level(scan(steep, cupMask(steep))).objects.single().copy(quality = 0.3f, unseen = 1)
        assertNotEquals(fromSide.footprint, old.footprint, "the two views build different footprints")

        val r = scan(side, cupMask(side), previous = Level(listOf(old)))
        val now = level(r).objects.single()
        assertEquals(listOf(old.id), r.replaced)
        assertEquals(old.id, now.id)
        assertEquals(fromSide.footprint, now.footprint)
        assertEquals(fromSide.heightM, now.heightM)
        assertTrue(now.quality > old.quality, "its own quality ${now.quality}")
        assertEquals(0, now.unseen)
        assertEquals(emptyList(), r.kept)
        assertEquals(emptyList(), r.carried)
    }

    @Test
    fun aWorseViewKeepsTheFootprint() {
        val fromSteep = level(scan(steep, cupMask(steep))).objects.single()
        val old = level(scan(side, cupMask(side))).objects.single().copy(quality = 0.9f)
        assertNotEquals(fromSteep.footprint, old.footprint, "the two views build different footprints")
        assertTrue(fromSteep.centroid.distanceTo(old.centroid) <= TableScan.KEEP_MAX_SHIFT_M, "the two agree on where it is")

        val r = scan(steep, cupMask(steep), previous = Level(listOf(old)))
        assertEquals(listOf(old), level(r).objects, "byte for byte")
        assertEquals(listOf(old.id), r.kept)
        assertEquals(emptyList(), r.replaced + r.removed)
        assertEquals(emptyList(), r.carried)

        val wasUnseen = scan(steep, cupMask(steep), previous = Level(listOf(old.copy(unseen = 1))))
        assertEquals(listOf(old), level(wasUnseen).objects, "kept, with unseen back to 0")
    }

    @Test
    fun anUnknownQualityLosesToAnyRealSighting() {
        val fixture = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(cup, 0.08f, 0.08f), 0.09f)
        assertEquals(0f, fixture.quality)
        for (pose in listOf(steep, view.pose(cup, 18f))) {
            val r = scan(pose, cupMask(pose), previous = Level(listOf(fixture)))
            assertEquals(listOf("cup-1"), r.replaced, "a sighting from $pose")
            assertTrue(level(r).objects.single().quality > 0f)
        }
    }

    @Test
    fun anObjectUnderAStandingCharacterNeverChanges() {
        val old = LevelObject("book-1", "book", ObjectKind.PLATFORM, Polygon.rect(cup, 0.15f, 0.10f), 0.02f, quality = 0.3f)
        val onIt = listOf(cup + Vec2(0.02f, 0.01f))

        val nobodyOnIt = scan(side, bookMask(side), previous = Level(listOf(old)))
        assertEquals(listOf(old.id), nobodyOnIt.replaced, "the side view is better: it would replace the footprint")
        val matched = scan(side, bookMask(side), previous = Level(listOf(old)), standing = onIt)
        assertEquals(listOf(old), level(matched).objects, "byte for byte under the character")
        assertEquals(listOf(old.id), matched.kept)
        assertEquals(emptyList(), matched.replaced)
        val seenOnce = scan(side, bookMask(side), previous = Level(listOf(old.copy(unseen = 1))), standing = onIt)
        assertEquals(listOf(old), level(seenOnce).objects, "the same geometry; a sighting still resets unseen")

        val gone = level(scan(side, phoneMask(side), previous = level(scan(side, phoneMask(side), previous = Level(listOf(old))))))
        assertTrue(gone.objects.none { it.id == old.id }, "nobody on it: an empty place is forgotten after two scans")
        val once = scan(side, phoneMask(side), previous = Level(listOf(old)), standing = onIt)
        val twice = scan(side, phoneMask(side), previous = level(once), standing = onIt)
        for (r in listOf(once, twice)) {
            assertTrue(old in level(r).objects, "byte for byte, unseen untouched")
            assertEquals(listOf(old), r.carried)
            assertEquals(emptyList(), r.removed)
        }
    }

    @Test
    fun aHandIsNeverCarried() {
        val hand = LevelObject("hand-1", "hand", ObjectKind.DYNAMIC, Polygon.rect(cup, 0.08f, 0.10f), 0.03f, quality = 0.9f)
        val outOfFrame = hand.copy(id = "hand-2", footprint = Polygon.rect(Vec2(-0.60f, 0.20f), 0.08f, 0.10f))
        val previous = Level(listOf(hand, outOfFrame))
        val empty = scan(side, previous = previous, failed = listOf("hand"))
        assertEquals(emptyList(), level(empty).objects)
        assertEquals(emptyList(), empty.carried, "out of frame, in frame or its prompt failed: a hand is re-placed every scan")
        assertEquals(emptyList(), empty.removed, "not dropped by the merge either: it is simply not in this scan")

        val covered = scan(side, cupMask(side), previous = previous, standing = listOf(cup))
        assertEquals(listOf("cup"), level(covered).objects.map { it.label }, "under a mask or a character, a hand is re-placed, not kept")
        assertEquals(emptyList(), covered.carried)
    }

    @Test
    fun aWorseViewStillMovesAnObjectThatShifted() {
        val old = level(scan(side, cupMask(side))).objects.single().copy(quality = 0.9f)
        val moved = cup + Vec2(0.04f, 0f)
        val fromSteep = level(scan(steep, cupMask(steep, moved))).objects.single()
        assertTrue(fromSteep.quality < old.quality, "a worse view")
        assertTrue(fromSteep.centroid.distanceTo(old.centroid) > TableScan.KEEP_MAX_SHIFT_M, "but the cup moved")

        val r = scan(steep, cupMask(steep, moved), previous = Level(listOf(old)))
        val now = level(r).objects.single()
        assertEquals(listOf(old.id), r.replaced, "where it is beats how well it was seen")
        assertEquals(old.id, now.id)
        assertEquals(fromSteep.footprint, now.footprint)
        assertEquals(fromSteep.quality, now.quality, "the quality is the new sighting's")
        assertEquals(emptyList(), r.carried)
    }

    @Test
    fun aRelabelledObjectAtTheSamePlaceIsTheSameObject() {
        val old = level(scan(side, cupMask(side))).objects.single()
        assertEquals("cup-1", old.id)

        val same = scan(side, cupMask(side, label = "bowl"), previous = Level(listOf(old)))
        assertEquals(listOf(old), level(same).objects, "no ghost: the bowl is the cup, seen no better, so kept")
        assertEquals(listOf("cup-1"), same.kept)
        assertEquals(emptyList(), same.carried)
        assertEquals(emptyList(), same.removed)

        val better = scan(side, cupMask(side, label = "bowl"), previous = Level(listOf(old.copy(quality = 0.1f, unseen = 1))))
        val now = level(better).objects.single()
        assertEquals(listOf("cup-1"), better.replaced)
        assertEquals("cup-1", now.id)
        assertEquals("bowl", now.label, "the new sighting's label")
        assertEquals(0, now.unseen)
        assertEquals(emptyList(), better.carried)
    }

    @Test
    fun aNudgedObjectBeyondTheRematchDistanceIsNotAGhost() {
        val old = level(scan(side, cupMask(side))).objects.single()
        val nudged = cup + Vec2(0f, -0.07f)
        val mask = cupMask(side, nudged)
        assertTrue(middleIn(old, mask, side), "the old cup's middle is inside the new mask's box: 'covered' would keep it")
        val fresh = level(scan(side, mask)).objects.single()
        assertTrue(fresh.centroid.distanceTo(old.centroid) > SceneParams().rematchMaxDistM, "beyond the rematch distance")

        val r = scan(side, mask, previous = Level(listOf(old)))
        val now = level(r).objects.single()
        assertEquals(old.id, now.id)
        assertEquals(fresh.footprint, now.footprint, "it moved 7 cm: the new footprint")
        assertEquals(listOf(old.id), r.replaced)
        assertEquals(emptyList(), r.carried)
    }

    @Test
    fun aScanThatSawNothingForgetsNothing() {
        val first = level(scan(side, cupMask(side), phoneMask(side)))
        val blank = LabeledMask("cup", BooleanMask(view.intr.width, view.intr.height))
        val once = scan(side, previous = first)
        val twice = scan(side, blank, previous = level(once))
        for (r in listOf(once, twice)) {
            assertEquals(first.objects, level(r).objects, "a dark or failed frame: every object unchanged, unseen 0")
            assertEquals(first.objects, r.carried)
            assertEquals(emptyList(), r.removed)
        }
    }

    @Test
    fun eachObjectGetsTheQualityOfItsOwnMask() {
        val alone = level(scan(side, cupMask(side))).objects.single()
        val speck = LabeledMask("cup", BooleanMask.generate(view.intr.width, view.intr.height) { x, y -> x in 1150..1151 && y == 360 })
        assertTrue(ViewQuality.of(speck.mask, view.input(side)) != alone.quality, "the speck is seen from elsewhere")
        val withSpeck = level(scan(side, speck, cupMask(side))).objects.single()
        assertEquals(alone.quality, withSpeck.quality, "a mask that builds nothing lends no quality to the next one")
        assertEquals(ViewQuality.of(cupMask(side).mask, view.input(side)), alone.quality)
    }

    @Test
    fun aRescanWithNothingToSeeKeepsTheLevelInPlay() {
        val old = level(scan(side, cupMask(side)))
        val tooSteep = view.pose(cup, elevationDeg = 85f, tiltDeg = 65f)
        val r = scan(tooSteep, cupMask(tooSteep), previous = old)
        assertNull(r.level, "nothing built and the cup too steep: no merge, the level in play stays")
    }

    @Test
    fun anOldLevelStillParses() {
        val json = """{"id":"cup-1","label":"cup","kind":"OBSTACLE","footprint":{"points":[{"u":0,"v":0},{"u":0.1,"v":0},{"u":0,"v":0.1}]},"heightM":0.09}"""
        val o = Json.decodeFromString(LevelObject.serializer(), json)
        assertEquals(0f, o.quality)
        assertEquals(0, o.unseen)
        val seen = o.copy(quality = 0.7f, unseen = 1)
        assertEquals(seen, Json.decodeFromString(LevelObject.serializer(), Json.encodeToString(LevelObject.serializer(), seen)))
    }
}
