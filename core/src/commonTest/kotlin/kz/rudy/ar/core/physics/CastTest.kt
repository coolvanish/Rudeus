package kz.rudy.ar.core.physics

import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterEvent
import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.scenario.ScenarioPlayer
import kz.rudy.ar.core.scenario.ScenarioRunner
import kz.rudy.ar.core.scenario.ScenarioStep
import kz.rudy.ar.core.scenario.Scenarios
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val FRAME_MS = 16L

/** The Day 5 table without its objects: a ±0.35 m square. */
private val squareLevel = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.70f, 0.70f))

private fun member(id: String, pos: Vec2, radiusM: Float = 0.03f, ability: Ability = Ability.JUMP) =
    CastMember(id, PhysicsBody(CharacterState(pos = pos, radiusM = radiusM, ability = ability)))

private fun cupAt(center: Vec2, radius: Float = 0.04f) =
    LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.circle(center, radius), 0.09f)

private fun assertNear(expected: Vec2, actual: Vec2, tol: Float = 1e-6f, msg: String = "") {
    assertTrue(expected.distanceTo(actual) <= tol, "$msg expected $expected, got $actual")
}

class CastTest {

    @Test
    fun castOfOneStepsExactlyLikeASingleBody() {
        val level = Scenarios.day5Level()
        val scenario = Scenarios.DAY5
        for (ability in Ability.entries) {
            val start = PhysicsBody(CharacterState(pos = level.spawn, ability = ability, radiusM = SurfaceProfile.TABLE.radiusM))
            val alonePlayer = ScenarioPlayer(scenario, startedAtMs = 0L)
            val castPlayer = ScenarioPlayer(scenario, startedAtMs = 0L)
            var body = start
            var cast = Cast(listOf(CastMember("hero", start)))
            val bodies = ArrayList<PhysicsBody>()
            var t = 0L
            while (true) {
                for (s in alonePlayer.due(t)) body = when (s) {
                    is ScenarioStep.WalkTo -> Physics.walkTo(body, s.target)
                    is ScenarioStep.Jump -> Physics.jump(body)
                }
                for (s in castPlayer.due(t)) cast = when (s) {
                    is ScenarioStep.WalkTo -> CastPhysics.walkTo(cast, "hero", s.target)
                    is ScenarioStep.Jump -> CastPhysics.jump(cast, "hero")
                }
                body = Physics.step(body, level, FRAME_MS)
                cast = CastPhysics.step(cast, level, FRAME_MS)
                assertEquals(body, cast["hero"], "$ability at $t ms")
                assertEquals(listOf("hero"), cast.ids)
                bodies += body
                if (t >= scenario.durationMs) break
                t = minOf(t + FRAME_MS, scenario.durationMs)
            }
            // The single-body trace is the one the official runner produces, so the cast replays the v1.0 demo.
            assertEquals(ScenarioRunner.run(scenario, level, ability).bodies, bodies, "$ability vs ScenarioRunner")
        }
    }

    @Test
    fun twoWalkersMeetingHeadOnStopTouching() {
        val level = squareLevel
        val boundary = level.boundary!!
        var cast = Cast(listOf(member("a", Vec2(-0.1f, 0f)), member("b", Vec2(0.1f, 0f))))
        cast = CastPhysics.walkTo(cast, "a", Vec2(0.1f, 0f))
        cast = CastPhysics.walkTo(cast, "b", Vec2(-0.1f, 0f))
        val reach = 0.06f
        var met = false
        repeat(400) { i ->
            cast = CastPhysics.step(cast, level, FRAME_MS)
            val a = cast["a"]!!.state
            val b = cast["b"]!!.state
            val dist = a.pos.distanceTo(b.pos)
            if (dist <= reach + 1e-3f) met = true
            assertTrue(dist >= reach - 1e-4f, "frame $i: overlapping, dist $dist")
            for (s in listOf(a, b)) {
                assertTrue(boundary.contains(s.pos), "frame $i: left the table at ${s.pos}")
                assertTrue(s.mode == Mode.WALKING || s.mode == Mode.IDLE, "frame $i: mode ${s.mode}")
                assertEquals(0f, s.h)
            }
        }
        assertTrue(met, "the walkers never met")
        val a = cast["a"]!!
        val b = cast["b"]!!
        assertTrue(a.state.pos.distanceTo(b.state.pos) <= reach + 1e-3f, "stopped touching, not apart")
        assertEquals(Mode.WALKING, a.state.mode, "still pressing toward its target")
        assertEquals(Mode.WALKING, b.state.mode, "still pressing toward its target")
        assertTrue(!a.blocked && !b.blocked, "a friend is not a cup")
    }

    @Test
    fun separationNeverPushesAMemberOverTheCliff() {
        val level = squareLevel
        // "a" stands 5 mm inside the u = 0.35 edge; "b" overlaps it by 1.5 cm from the inside.
        val a = member("a", Vec2(0.345f, 0f)).let { it.copy(body = Physics.walkTo(it.body, Vec2(0.345f, 0.1f))) }
        val b = member("b", Vec2(0.30f, 0f))
        val cast = Cast(listOf(a, b))
        val out = CastPhysics.separate(cast, level)
        assertEquals(a, CastMember("a", out["a"]!!), "the member at the edge is not moved")
        val nb = out["b"]!!
        assertNear(Vec2(0.285f, 0f), nb.state.pos, msg = "the other takes the whole overlap")
        assertEquals(b.body.state.copy(pos = nb.state.pos), nb.state, "only pos changes")
        assertTrue(out["a"]!!.state.pos.distanceTo(nb.state.pos) >= 0.06f - 1e-5f)
        assertTrue(level.boundary!!.contains(out["a"]!!.state.pos))
        assertEquals(listOf("a", "b"), out.ids, "member order is kept")
    }

    @Test
    fun separationNeverPushesAMemberIntoACup() {
        // Cup r 4 cm at (0.15, 0): its near edge is u = 0.11. "a" stands 5 mm short of it; "b" overlaps "a" by 3 cm.
        val cup = cupAt(Vec2(0.15f, 0f))
        val level = Level(listOf(cup), boundary = Polygon.rect(Vec2.ZERO, 0.70f, 0.70f))
        val a = member("a", Vec2(0.105f, 0f))
        val b = member("b", Vec2(0.075f, 0f))
        val out = CastPhysics.separate(Cast(listOf(a, b)), level)
        assertEquals(a.body, out["a"], "a half-push would put the centre in the cup: stays put")
        assertNear(Vec2(0.045f, 0f), out["b"]!!.state.pos, msg = "the other takes the whole overlap")
        assertTrue(!cup.footprint.contains(out["a"]!!.state.pos))

        // Same pair in a strip whose edge is at u = 0.06: "b" cannot take the whole overlap either, so both stay.
        val strip = Level(
            listOf(cup),
            boundary = Polygon(listOf(Vec2(0.06f, -0.1f), Vec2(0.35f, -0.1f), Vec2(0.35f, 0.1f), Vec2(0.06f, 0.1f))),
        )
        val stuck = Cast(listOf(a, b))
        assertEquals(stuck, CastPhysics.separate(stuck, strip), "neither can move: both stay")
    }

    @Test
    fun aJumpingMemberPassesOverAFriend() {
        val level = squareLevel
        val r = 0.015f
        val friend = member("friend", Vec2.ZERO, radiusM = r)
        val jumper0 = member("jumper", Vec2(-0.032f, 0f), radiusM = r)
        val startBody = Physics.jump(Physics.walkTo(jumper0.body, Vec2(0.15f, 0f)))
        var alone = startBody
        var cast = Cast(listOf(friend, jumper0))
        cast = CastPhysics.walkTo(cast, "jumper", Vec2(0.15f, 0f))
        cast = CastPhysics.jump(cast, "jumper")
        assertEquals(startBody, cast["jumper"])
        var overlappedInFlight = 0
        repeat(80) { i ->
            alone = Physics.step(alone, level, FRAME_MS)
            cast = CastPhysics.step(cast, level, FRAME_MS)
            val j = cast["jumper"]!!
            assertEquals(alone, j, "frame $i: the jumper follows its own path")
            assertEquals(friend.body, cast["friend"], "frame $i: the friend does not move")
            if (j.state.mode == Mode.JUMPING && j.state.pos.distanceTo(Vec2.ZERO) < 2 * r) overlappedInFlight++
        }
        assertTrue(overlappedInFlight > 0, "the jump never passed over the friend")
        assertTrue(cast["jumper"]!!.state.pos.u > 2 * r, "landed beyond the friend")
    }

    @Test
    fun pausedCastIsUnchangedByStep() {
        val level = squareLevel
        var start = Cast(
            listOf(
                member("a", Vec2(0f, 0f)),
                member("b", Vec2(0.03f, 0f)), // overlaps "a" by 3 cm
                member("c", Vec2(0.2f, -0.2f)),
            ),
        )
        start = CastPhysics.walkTo(start, "a", Vec2(0.2f, 0f))
        start = CastPhysics.jump(start, "c")
        val paused = CastPhysics.pause(start)
        assertTrue(paused.members.all { it.body.state.mode == Mode.PAUSED })
        assertEquals(paused, CastPhysics.pause(paused), "pause is idempotent")
        var c = paused
        repeat(200) { i ->
            c = CastPhysics.step(c, level, FRAME_MS)
            assertEquals(paused, c, "frame $i")
        }
        assertEquals(paused, CastPhysics.separate(paused, level), "paused members neither push nor move")
        assertEquals(start, CastPhysics.resume(c), "resume restores every member exactly")
    }

    @Test
    fun eventsAreKeyedByMemberId() {
        val level = Scenarios.day5Level()
        var cast = Cast(listOf(member("walker", Vec2.ZERO), member("hopper", Vec2(0f, -0.25f))))
        val events = ArrayList<Pair<String, CharacterEvent>>()
        repeat(250) { i ->
            val before = cast
            if (i == 0) {
                cast = CastPhysics.walkTo(cast, "walker", Vec2(0.30f, 0f)) // head-on into the cup
                cast = CastPhysics.jump(cast, "hopper")
            }
            cast = CastPhysics.step(cast, level, FRAME_MS)
            events += CastEvents.between(before, cast)
        }
        assertEquals(
            listOf("walker" to CharacterEvent.STARTED_WALKING, "hopper" to CharacterEvent.JUMPED),
            events.take(2),
            "one tick: in the cast's order",
        )
        assertEquals(listOf("hopper" to CharacterEvent.JUMPED), events.filter { it.second == CharacterEvent.JUMPED })
        assertEquals(listOf("walker" to CharacterEvent.BLOCKED), events.filter { it.second == CharacterEvent.BLOCKED })
        assertTrue("hopper" to CharacterEvent.LANDED in events)
        assertTrue(events.none { it.first == "hopper" && it.second == CharacterEvent.STARTED_WALKING })
    }

    @Test
    fun aNewMemberEmitsNoEventOnItsFirstFrame() {
        val level = squareLevel
        val prev = Cast(listOf(member("a", Vec2.ZERO), member("c", Vec2(0.2f, 0.2f))))
        val newcomer = member("b", Vec2(-0.2f, 0.2f)).body
        var joined = prev.without("c").with("b", newcomer)
        joined = CastPhysics.walkTo(joined, "b", Vec2(-0.1f, 0.2f))
        joined = CastPhysics.jump(joined, "b")
        val next = CastPhysics.step(joined, level, FRAME_MS)
        assertTrue(CharacterEvents.between(newcomer, next["b"]!!).isNotEmpty(), "the newcomer did change")
        assertEquals(emptyList(), CastEvents.between(prev, next), "nothing for b (new), c (gone) or a (idle)")
        // From its second frame on it is an ordinary member.
        val third = CastPhysics.step(CastPhysics.walkTo(next, "a", Vec2(0.1f, 0f)), level, FRAME_MS)
        assertEquals(listOf("a" to CharacterEvent.STARTED_WALKING), CastEvents.between(next, third))
    }

    @Test
    fun spawnNearPicksAFreeSpotInsideTheBoundary() {
        val level = Scenarios.day5Level()
        val cup = level.objects.first { it.id == "cup-1" }
        val r = 0.03f
        val near = Vec2(0.10f, 0f)
        // Candidates at 7.5 cm, starting +u: (0.175, 0) is in the cup, 45° and 90° are within 6 cm of "a".
        val cast = Cast(listOf(member("a", Vec2(0.15f, 0.07f))))
        val p = CastPhysics.spawnNear(level, cast, r, near)
        assertEquals(p, CastPhysics.spawnNear(level, cast, r, near), "deterministic")
        val s = sqrt(0.5f)
        assertNear(near + Vec2(-s, s) * (2.5f * r), p, msg = "the first free candidate (135°)")
        assertTrue(level.boundary!!.contains(p))
        assertTrue(!cup.footprint.contains(p))
        assertNull(circlePolygonPushOut(p, r, cup.footprint), "clear of the cup")
        assertTrue(p.distanceTo(cast["a"]!!.state.pos) >= r + 0.03f, "clear of the member")
        assertTrue(Physics.groundHeight(level, p, 0f) != null)

        // Rotating the preference rotates the search: pointing -u the first candidate is already free.
        assertNear(near + Vec2(-1f, 0f) * (2.5f * r), CastPhysics.spawnNear(level, cast, r, near, preferred = Vec2(-1f, 0f)))
        // An empty cast in an empty corner takes the preferred direction at once.
        val corner = Vec2(0.2f, -0.2f)
        assertNear(corner + Vec2(0f, 1f) * (2.5f * r), CastPhysics.spawnNear(level, Cast(), r, corner, Vec2(0f, 1f)))
    }

    @Test
    fun spawnNearFallsBackToTheLevelSpawn() {
        val spawn = Vec2(0.01f, -0.02f)
        // Every candidate (7.5 cm and 15 cm out) is past the edge of a 10 cm table.
        val tiny = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.1f, 0.1f), spawn = spawn)
        assertEquals(spawn, CastPhysics.spawnNear(tiny, Cast(), 0.03f, Vec2.ZERO))
        // Every candidate is inside one huge cup.
        val cupped = Level(listOf(cupAt(Vec2.ZERO, radius = 0.5f)), boundary = null, spawn = spawn)
        assertEquals(spawn, CastPhysics.spawnNear(cupped, Cast(), 0.03f, Vec2.ZERO))
        // Every candidate is within reach of a crowd of big members.
        val crowd = Cast(
            listOf(
                member("n", Vec2(0f, 0.1f), radiusM = 0.1f),
                member("s", Vec2(0f, -0.1f), radiusM = 0.1f),
                member("e", Vec2(0.1f, 0f), radiusM = 0.1f),
                member("w", Vec2(-0.1f, 0f), radiusM = 0.1f),
            ),
        )
        assertEquals(spawn, CastPhysics.spawnNear(squareLevel.copy(spawn = spawn), crowd, 0.03f, Vec2.ZERO))
    }

    @Test
    fun castRefusesAFifthMember() {
        val four = Cast(listOf("a", "b", "c", "d").mapIndexed { i, id -> member(id, Vec2(0.1f * i, 0f)) })
        assertEquals(Cast.MAX, four.members.size)
        assertFailsWith<IllegalArgumentException> { four.with("e", member("e", Vec2.ZERO).body) }
        assertFailsWith<IllegalArgumentException> { Cast(four.members + member("e", Vec2.ZERO)) }
        assertFailsWith<IllegalArgumentException> { Cast(listOf(member("a", Vec2.ZERO), member("a", Vec2(0.1f, 0f)))) }
        // Replacing a member keeps its place; removing frees a slot.
        val moved = member("b", Vec2(0.2f, 0.2f)).body
        val replaced = four.with("b", moved)
        assertEquals(listOf("a", "b", "c", "d"), replaced.ids)
        assertEquals(moved, replaced["b"])
        val three = four.without("c")
        assertEquals(listOf("a", "b", "d"), three.ids)
        assertEquals(listOf("a", "b", "d", "e"), three.with("e", moved).ids)
        assertSame(four, four.without("zz"))
        assertNull(four["zz"])
        assertSame(four, CastPhysics.walkTo(four, "zz", Vec2.ZERO))
        assertSame(four, CastPhysics.jump(four, "zz"))
    }

    @Test
    fun castRoundTripsThroughJson() {
        var cast = Cast(listOf(member("a", Vec2(0.1f, 0f)), member("b", Vec2(-0.1f, 0.05f), ability = Ability.FLY)))
        cast = CastPhysics.jump(CastPhysics.walkTo(cast, "b", Vec2(0.2f, 0.2f)), "b")
        val text = Json.encodeToString(Cast.serializer(), cast)
        assertEquals(cast, Json.decodeFromString(Cast.serializer(), text))
        assertTrue("ids" !in text, "derived properties stay out of the log")
    }

    @Test
    fun nearestInsideReturnsAPointTheBoundaryContains() {
        val level = squareLevel
        val boundary = level.boundary!!
        val cases = listOf(
            Vec2(0.5f, 0.1f) to Vec2(0.35f, 0.1f),
            Vec2(-0.5f, -0.1f) to Vec2(-0.35f, -0.1f),
            Vec2(0.1f, 0.5f) to Vec2(0.1f, 0.35f),
            Vec2(-0.1f, -0.5f) to Vec2(-0.1f, -0.35f),
            Vec2(2f, 2f) to Vec2(0.35f, 0.35f),
            Vec2(-3f, -1.5f) to Vec2(-0.35f, -0.35f),
            Vec2(0.35f, 0.2f) to Vec2(0.35f, 0.2f), // exactly on an edge
        )
        for ((p, closest) in cases) {
            val q = level.nearestInside(p)
            assertTrue(boundary.contains(q), "$p -> $q is not inside")
            val nudge = q.distanceTo(closest)
            assertTrue(nudge in 0.0009f..0.0011f, "$p -> $q is $nudge m from the edge point $closest")
            assertTrue((q - closest).dot(boundary.centroid() - closest) > 0f, "$p -> $q not nudged toward the centre")
        }
        val inside = Vec2(0.1f, -0.2f)
        assertSame(inside, level.nearestInside(inside))
        val far = Vec2(5f, 5f)
        assertSame(far, Level(emptyList()).nearestInside(far), "no boundary: unchanged")
    }
}
