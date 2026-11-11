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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun marksAreFreeInsideAndFaceEachOther() {
        // A cup right on the spawn and a book beside it: the marks go round the cup, 20 cm apart across the spawn.
        val level = Level(
            objects = listOf(cup("cup-1", Vec2.ZERO), book("book-1", Vec2(0.15f, 0.15f))),
            boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.4f),
        )
        val (a, b) = assertNotNull(DuelArena.marks(level, R, 0.2f, PhysicsParams.DEFAULT))
        assertFree(level, a, R, "a")
        assertFree(level, b, R, "b")
        assertEquals(0.2f, a.distanceTo(b), 1e-5f)
        assertTrue(((a + b) * 0.5f).distanceTo(level.spawn) < 1e-6f, "across the spawn: $a $b")
        assertEquals(Vec2(-0.1f, 0f), a, "along u first")

        // A match started on them: standing on the marks, facing each other.
        val start = DuelMatch.start(Contestant("x", FighterStats.BALANCED, Ability.FLY), Contestant("y", FighterStats.BALANCED, Ability.ROLL), level, a to b)
        val x = start.duel.a.body.state
        val y = start.duel.b.body.state
        assertEquals(a to b, x.pos to y.pos)
        assertEquals(Mode.IDLE to Mode.IDLE, x.mode to y.mode)
        assertTrue(x.facing.distanceTo((b - a).normalized()) < 1e-6f && y.facing.distanceTo((a - b).normalized()) < 1e-6f)
        assertEquals(a to b, start.startA to start.startB)

        // The spawn near a corner of a 30 cm table: around the spawn only a short pair fits; around the middle the
        // asked-for 20 cm along the diagonal does (the inner 18 x 18 cm square is 25 cm across).
        val corner = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = Vec2(0.07f, 0.07f))
        val (c, d) = assertNotNull(DuelArena.marks(corner, R, 0.2f))
        assertFree(corner, c, R, "c")
        assertFree(corner, d, R, "d")
        assertEquals(0.2f, c.distanceTo(d), 1e-5f)
        assertTrue(((c + d) * 0.5f).distanceTo(Vec2.ZERO) < 1e-5f, "moved to the middle: $c $d")
        // Asked for more than fits: as far apart as the table allows (within one search step), on the diagonal.
        val (e, f) = assertNotNull(DuelArena.marks(corner, R, 0.5f))
        val widest = 0.18f * kotlin.math.sqrt(2f)
        assertTrue(e.distanceTo(f) <= widest + 1e-5f && e.distanceTo(f) > widest - DuelArena.SEPARATION_STEP_RADII * R, "${e.distanceTo(f)} of $widest")
        assertFree(corner, e, R, "e")
        assertFree(corner, f, R, "f")

        // Holes count as edges (a hole 2 cm left of the spawn turns the pair to run along v); a platform under a mark is
        // stood on.
        val holed = Level(
            objects = listOf(LevelObject("hole-1", "hole", ObjectKind.CLIFF, Polygon.rect(Vec2(-0.1f, 0f), 0.04f, 0.3f), 0f), book("book-2", Vec2(0f, 0.1f))),
            boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.4f),
        )
        val (g, k) = assertNotNull(DuelArena.marks(holed, R, 0.2f))
        assertFree(holed, g, R, "g")
        assertFree(holed, k, R, "k")
        assertTrue(g.distanceTo(Vec2(0f, -0.1f)) < 1e-6f && k.distanceTo(Vec2(0f, 0.1f)) < 1e-6f, "$g $k")
        val onBook = DuelMatch.start(Contestant("x", FighterStats.BALANCED, Ability.JUMP), Contestant("y", FighterStats.BALANCED, Ability.JUMP), holed, g to k)
        assertEquals(0f, onBook.duel.a.body.state.h)
        assertEquals(0.03f, onBook.duel.b.body.state.h, "on the book")
    }

    @Test
    fun noRoomForTwoGivesNoMarks() {
        // 15 cm square: the 3 x 3 cm of centres 2 radii from the edges is 4.2 cm across, two fighters need 9 cm.
        assertNull(DuelArena.marks(Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.15f, 0.15f)), R, 0.2f))
        // A big table filled by one tall box but for a sliver.
        val crowded = Level(
            listOf(LevelObject("box", "box", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.02f, 0f), 0.46f, 0.4f), 0.2f)),
            boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.4f),
        )
        assertNull(DuelArena.marks(crowded, R, 0.2f))
        // The same table with room: found. And bigger fighters need more room.
        val table = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f))
        assertNotNull(DuelArena.marks(table, R, 0.2f))
        assertNull(DuelArena.marks(table, 3 * R, 0.2f))
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
        val table = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f))
        assertSame(table, DuelArena.arena(table, 0.6f))

        // On the floor's scale: marks on the ring, and walking past it is a ring-out that scores.
        val radius = SurfaceProfile.FLOOR.radiusM
        val physics = PhysicsParams.forProfile(SurfaceProfile.FLOOR)
        val params = DuelParams.forProfile(SurfaceProfile.FLOOR)
        val (a, b) = assertNotNull(DuelArena.marks(ring, radius, 0.5f, physics))
        assertFree(ring, a, radius, "a")
        assertFree(ring, b, radius, "b")
        val start = DuelMatch.start(
            Contestant("x", FighterStats.BALANCED, Ability.JUMP), Contestant("y", FighterStats.BALANCED, Ability.JUMP), ring, a to b,
            params = params, physics = physics, radiusM = radius,
        )
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
