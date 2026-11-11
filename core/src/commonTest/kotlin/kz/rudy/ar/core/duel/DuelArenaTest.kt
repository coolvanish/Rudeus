package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.DuelInput.MoveTo
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsParams
import kz.rudy.ar.core.physics.circlePolygonPushOut
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** The marks search's grid step on a table-sized board. */
private const val STEP = DuelArena.GRID_STEP_RADII * R

private val JUMPER = Contestant("x", FighterStats.BALANCED, Ability.JUMP)
private val OTHER = Contestant("y", FighterStats.BALANCED, Ability.JUMP)

private fun box(id: String, center: Vec2, w: Float, d: Float, h: Float = 0.2f) =
    LevelObject(id, "box", ObjectKind.OBSTACLE, Polygon.rect(center, w, d), h)

private fun table(w: Float, d: Float, vararg objects: LevelObject, spawn: Vec2 = Vec2.ZERO) =
    Level(objects.toList(), boundary = Polygon.rect(Vec2.ZERO, w, d), spawn = spawn)

class DuelArenaTest {

    /** Inside [level]'s boundary by at least 2 radii, off every hole by as much, its circle clear of what blocks. */
    private fun assertFree(level: Level, p: Vec2, r: Float, what: String) {
        val boundary = level.boundary!!
        assertTrue(boundary.contains(p), "$what $p is off the table")
        assertTrue(distanceToPolygon(p, boundary) >= 2 * r - 1e-6f, "$what $p is ${distanceToPolygon(p, boundary)} m from the edge")
        val h = Physics.groundHeight(level, p, 0f)
        assertNotNull(h, "$what $p has no ground")
        for (o in level.objects) {
            if (o.kind == ObjectKind.CLIFF) {
                assertTrue(!o.footprint.contains(p) && distanceToPolygon(p, o.footprint) >= 2 * r - 1e-6f, "$what $p next to hole ${o.id}")
            } else if (Physics.blocks(o, h)) {
                assertTrue(!o.footprint.contains(p) && circlePolygonPushOut(p, r, o.footprint) == null, "$what $p overlaps ${o.id}")
            }
        }
    }

    /** Both free, at least 3 radii apart, at the same ground height. Returns the pair's ground height. */
    private fun assertGoodMarks(level: Level, marks: Pair<Vec2, Vec2>, r: Float = R, what: String = ""): Float {
        val (a, b) = marks
        assertFree(level, a, r, "$what a")
        assertFree(level, b, r, "$what b")
        assertTrue(a.distanceTo(b) >= DuelArena.MIN_SEPARATION_RADII * r - 1e-6f, "$what marks ${a.distanceTo(b)} m apart")
        val ha = Physics.groundHeight(level, a, 0f)!!
        val hb = Physics.groundHeight(level, b, 0f)!!
        assertTrue(abs(ha - hb) <= DuelArena.SAME_HEIGHT_M, "$what marks at $ha and $hb m")
        return ha
    }

    @Test
    fun marksAreFreeInsideAndFaceEachOther() {
        // A cup right on the spawn and a book beside it: the marks go round the cup, 20 cm apart across the spawn, along
        // the table's long axis (u).
        val level = Level(
            objects = listOf(cup("cup-1", Vec2.ZERO), book("book-1", Vec2(0.15f, 0.15f))),
            boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.4f),
        )
        val (a, b) = assertNotNull(DuelArena.marks(level, R, 0.2f, PhysicsParams.DEFAULT))
        assertGoodMarks(level, a to b)
        assertEquals(Vec2(-0.1f, 0f) to Vec2(0.1f, 0f), a to b)

        // A match started on them: standing on the marks, facing each other.
        val start = DuelMatch.start(Contestant("x", FighterStats.BALANCED, Ability.FLY), Contestant("y", FighterStats.BALANCED, Ability.ROLL), level, a to b)
        val x = start.duel.a.body.state
        val y = start.duel.b.body.state
        assertEquals(a to b, x.pos to y.pos)
        assertEquals(Mode.IDLE to Mode.IDLE, x.mode to y.mode)
        assertTrue(x.facing.distanceTo((b - a).normalized()) < 1e-6f && y.facing.distanceTo((a - b).normalized()) < 1e-6f)
        assertEquals(a to b, start.startA to start.startB)

        // A long table turned 30 degrees: the marks lie along it.
        val c = kotlin.math.cos(kotlin.math.PI / 6).toFloat()
        val s = kotlin.math.sin(kotlin.math.PI / 6).toFloat()
        val turned = Level(emptyList(), boundary = Polygon(listOf(Vec2(-0.5f, -0.15f), Vec2(0.5f, -0.15f), Vec2(0.5f, 0.15f), Vec2(-0.5f, 0.15f)).map { Vec2(it.u * c - it.v * s, it.u * s + it.v * c) }))
        val (p, q) = assertNotNull(DuelArena.marks(turned, R, 0.4f))
        assertGoodMarks(turned, p to q)
        assertTrue((q - p).normalized().distanceTo(Vec2(c, s)) < 1e-3f, "along the long axis: ${q - p}")
        assertEquals(0.4f, p.distanceTo(q), 1e-5f)

        // The spawn near a corner of a 30 cm table: no 20 cm pair around it; one near it, as close to 20 cm as the grid
        // allows. Asked for more than fits: as far apart as the grid allows (the inner 18 x 18 cm square is 25.5 cm across).
        val corner = table(0.3f, 0.3f, spawn = Vec2(0.07f, 0.07f))
        val (e, f) = assertNotNull(DuelArena.marks(corner, R, 0.2f))
        assertGoodMarks(corner, e to f)
        assertTrue(abs(e.distanceTo(f) - 0.2f) < STEP, "${e.distanceTo(f)}")
        val (g, k) = assertNotNull(DuelArena.marks(corner, R, 0.5f))
        assertGoodMarks(corner, g to k)
        val widest = 0.18f * sqrt(2f)
        assertTrue(g.distanceTo(k) > widest - 2f * sqrt(2f) * STEP, "${g.distanceTo(k)} of $widest")
    }

    @Test
    fun marksFoundWhereverThereIsRoom() {
        // The review's three tables, where the old search (separations only shrinking from the one asked for, centres only
        // the spawn and the centroid) found nothing.
        val laptop = table(1.0f, 0.8f, box("laptop", Vec2.ZERO, 0.3f, 0.3f))
        val big = table(0.6f, 0.6f, box("box", Vec2.ZERO, 0.25f, 0.25f))
        val cupped = table(0.5f, 0.4f, cup("cup", Vec2.ZERO, radius = 0.05f))
        for ((level, separations) in listOf(laptop to listOf(0.2f), big to listOf(0.2f, 0.5f), cupped to listOf(0.1f, 0.2f))) {
            for (sep in separations) {
                val marks = assertNotNull(DuelArena.marks(level, R, sep), "${level.objects.single().id} at $sep")
                assertGoodMarks(level, marks, what = "${level.objects.single().id} at $sep")
                assertTrue(abs(marks.first.distanceTo(marks.second) - sep) < 2 * STEP, "${level.objects.single().id}: ${marks.first.distanceTo(marks.second)} for $sep")
            }
        }
        // Beside the laptop, as close to the spawn as 20 cm marks clear of it allow (a pair along a long side has its
        // midpoint 18.75 cm out; round a corner it is nearer).
        val (a, b) = DuelArena.marks(laptop, R, 0.2f)!!
        val mid = (a + b) * 0.5f
        assertTrue(mid.length < 0.19f, "midpoint $mid of $a $b")
        println("marks beside the laptop: $a $b")

        // The cost on the biggest grid (100 x 80 cm: 1 600 points at most), best of three.
        var best = Double.MAX_VALUE
        repeat(3) {
            val mark = TimeSource.Monotonic.markNow()
            DuelArena.marks(laptop, R, 0.2f)
            best = minOf(best, mark.elapsedNow().inWholeMicroseconds / 1000.0)
        }
        println("marks: 100 x 80 cm table with a laptop: $best ms")
    }

    @Test
    fun marksStandAtOneHeightTableFirst() {
        // The review's case: a 3 cm book where the ideal pair's right mark would be. Both marks on the table instead.
        val booked = table(0.5f, 0.4f, book("book", Vec2(0.1f, 0f)))
        val pair = assertNotNull(DuelArena.marks(booked, R, 0.2f))
        assertEquals(0f, assertGoodMarks(booked, pair))
        // Same with a hole next to the spawn and a book across: still both on the table.
        val holed = Level(
            objects = listOf(LevelObject("hole-1", "hole", ObjectKind.CLIFF, Polygon.rect(Vec2(-0.1f, 0f), 0.04f, 0.3f), 0f), book("book-2", Vec2(0f, 0.1f))),
            boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.4f),
        )
        val holedPair = assertNotNull(DuelArena.marks(holed, R, 0.2f))
        assertEquals(0f, assertGoodMarks(holed, holedPair))
        val started = DuelMatch.start(JUMPER, OTHER, holed, holedPair)
        assertEquals(0f to 0f, started.duel.a.body.state.h to started.duel.b.body.state.h)
        // Only when the table itself has no room: both on one platform height (a 3 cm board over the whole table).
        val boarded = table(0.5f, 0.4f, LevelObject("board", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2.ZERO, 0.6f, 0.5f), 0.03f))
        assertEquals(0.03f, assertGoodMarks(boarded, assertNotNull(DuelArena.marks(boarded, R, 0.2f))))
        // Two platforms of different heights and no table: never one up, one down. A 2 cm board on the left half, a 3 cm
        // one on the right: each half has room for two, so a pair on one of them.
        val twoBoards = table(
            0.8f, 0.4f,
            LevelObject("low", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2(-0.2f, 0f), 0.4f, 0.5f), 0.02f),
            LevelObject("high", "book", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.2f, 0f), 0.4f, 0.5f), 0.03f),
        )
        assertGoodMarks(twoBoards, assertNotNull(DuelArena.marks(twoBoards, R, 0.3f)))
    }

    @Test
    fun marksExistForOneSeparationOnlyIfForAll() {
        // Whether marks exist depends on the table, never on the separation asked for; and whatever is found is good.
        val rnd = Random(9)
        val levels = arrayListOf(
            table(1.0f, 0.8f, box("laptop", Vec2.ZERO, 0.3f, 0.3f)),
            table(0.6f, 0.6f, box("box", Vec2.ZERO, 0.25f, 0.25f)),
            table(0.5f, 0.4f, cup("cup", Vec2.ZERO, radius = 0.05f)),
            table(0.15f, 0.15f),
            table(0.5f, 0.4f, box("box", Vec2(0.02f, 0f), 0.46f, 0.4f)),
            table(0.21f, 0.13f),
        )
        repeat(30) {
            val w = 0.15f + rnd.nextFloat() * 0.85f
            val d = 0.15f + rnd.nextFloat() * 0.65f
            fun at() = Vec2((rnd.nextFloat() - 0.5f) * w, (rnd.nextFloat() - 0.5f) * d)
            val objects = List(rnd.nextInt(5)) { i ->
                when (rnd.nextInt(4)) {
                    0 -> cup("cup-$i", at(), radius = 0.02f + rnd.nextFloat() * 0.08f)
                    1 -> book("book-$i", at(), 0.05f + rnd.nextFloat() * 0.3f, 0.05f + rnd.nextFloat() * 0.3f, if (rnd.nextBoolean()) 0.02f else 0.03f)
                    2 -> box("box-$i", at(), 0.05f + rnd.nextFloat() * 0.4f, 0.05f + rnd.nextFloat() * 0.3f)
                    else -> LevelObject("hole-$i", "hole", ObjectKind.CLIFF, Polygon.rect(at(), 0.03f + rnd.nextFloat() * 0.1f, 0.03f + rnd.nextFloat() * 0.1f), 0f)
                }
            }
            levels += table(w, d, *objects.toTypedArray(), spawn = at() * 0.5f)
        }
        var found = 0
        for ((i, level) in levels.withIndex()) {
            val results = listOf(-1f, 0f, 0.05f, 0.09f, 0.1f, 0.2f, 0.35f, 0.5f, 1f, 5f).map { it to DuelArena.marks(level, R, it) }
            val exist = results.map { it.second != null }.toSet()
            assertEquals(1, exist.size, "level $i: marks for some separations only: ${results.map { it.first to (it.second != null) }}")
            for ((sep, marks) in results) if (marks != null) assertGoodMarks(level, marks, what = "level $i at $sep")
            if (exist.single()) found++
        }
        assertTrue(found in 5 until levels.size, "a mix of tables with and without room: $found of ${levels.size}")
    }

    @Test
    fun noRoomForTwoGivesNoMarks() {
        // 15 cm square: the 3 x 3 cm of centres 2 radii from the edges is 4.2 cm across, two fighters need 9 cm.
        assertNull(DuelArena.marks(table(0.15f, 0.15f), R, 0.2f))
        // A big table filled by one tall box but for a sliver.
        assertNull(DuelArena.marks(table(0.5f, 0.4f, box("box", Vec2(0.02f, 0f), 0.46f, 0.4f)), R, 0.2f))
        // The same table with room: found. And bigger fighters need more room.
        assertNotNull(DuelArena.marks(table(0.3f, 0.3f), R, 0.2f))
        assertNull(DuelArena.marks(table(0.3f, 0.3f), 3 * R, 0.2f))
        // Marks need an edge to keep away from.
        assertFailsWith<IllegalArgumentException> { DuelArena.marks(Level(emptyList()), R, 0.2f) }
    }

    @Test
    fun startRefusesBadArenasAndMarks() {
        val level = table(0.5f, 0.4f, cup("cup", Vec2(0.1f, 0.1f)), book("book", Vec2(0.15f, -0.12f)))
        val good = Vec2(-0.1f, 0f) to Vec2(0.1f, -0.03f)
        DuelMatch.start(JUMPER, OTHER, level, good)
        fun refused(arena: Level, marks: Pair<Vec2, Vec2>, says: String) {
            val e = assertFailsWith<IllegalArgumentException>(says) { DuelMatch.start(JUMPER, OTHER, arena, marks) }
            assertTrue(says in (e.message ?: ""), "'$says' in '${e.message}'")
        }
        refused(level.copy(boundary = null), good, "DuelArena.arena")
        refused(level, Vec2(-0.3f, 0f) to good.second, "first mark")
        refused(level, Vec2(-0.23f, 0f) to good.second, "first mark") // its circle hangs over the edge
        refused(level, good.first to Vec2(0.1f, 0.09f), "second mark") // in the cup
        refused(level, good.first to Vec2(-0.06f, 0f), "closer than two radii")
        refused(level, good.first to Vec2(0.15f, -0.12f), "different heights") // on the book
        refused(level, good.first to Vec2(Float.NaN, 0f), "second mark")
    }

    @Test
    fun aLevelWithoutABoundaryGetsARing() {
        val floor = Level(listOf(cup("cup-1", Vec2(1f, 0f))), boundary = null, spawn = Vec2(0.2f, -0.1f))
        val ring = DuelArena.arena(floor, 0.6f)
        assertEquals(Polygon.circle(floor.spawn, 0.6f, 16), ring.boundary)
        assertEquals(DuelArena.RING_SEGMENTS, ring.boundary!!.size)
        assertEquals(floor.objects, ring.objects)
        assertEquals(floor.spawn, ring.spawn)
        // A level with a boundary is its own arena.
        val square = table(0.3f, 0.3f)
        assertSame(square, DuelArena.arena(square, 0.6f))

        // On the floor's scale: marks on the ring, and walking past it is a ring-out that scores.
        val radius = SurfaceProfile.FLOOR.radiusM
        val physics = PhysicsParams.forProfile(SurfaceProfile.FLOOR)
        val params = DuelParams.forProfile(SurfaceProfile.FLOOR)
        val (a, b) = assertNotNull(DuelArena.marks(ring, radius, 0.5f, physics))
        assertFree(ring, a, radius, "a")
        assertFree(ring, b, radius, "b")
        val start = DuelMatch.start(JUMPER, OTHER, ring, a to b, params = params, physics = physics, radiusM = radius)
        assertEquals(radius, start.duel.a.body.state.radiusM)
        var s = start
        var scored: MatchEvent.Point? = null
        while (scored == null && s.duel.timeMs < 20_000L) {
            val walkOff = if (s.phase == MatchPhase.FIGHT) mapOf("x" to MoveTo(a + (a - b) * 10f)) else emptyMap()
            val out = DuelMatch.step(s, ring, 33L, walkOff, params = params, physics = physics)
            scored = out.events.filterIsInstance<MatchEvent.Point>().firstOrNull()
            s = out.state
        }
        assertEquals("y", scored?.winner)
        assertEquals(PointReason.RING_OUT, scored?.reason)
    }
}
