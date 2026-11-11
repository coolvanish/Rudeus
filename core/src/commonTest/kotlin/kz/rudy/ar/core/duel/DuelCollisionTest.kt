package kz.rudy.ar.core.duel

import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Separation (the duel's own) and the copy of the walking game's collision resolution. */
class DuelCollisionTest {

    /** A 4 mm wall, 10 cm tall, at u in [-0.012, -0.008]; the table's left edge is at u = -0.07. */
    private val wall = LevelObject(
        "wall-1", "box", ObjectKind.OBSTACLE,
        Polygon(listOf(Vec2(-0.012f, -0.15f), Vec2(-0.008f, -0.15f), Vec2(-0.008f, 0.15f), Vec2(-0.012f, 0.15f))), 0.1f,
    )

    private fun corridor(spawnU: Float) = Level(
        listOf(wall),
        boundary = Polygon(listOf(Vec2(-0.07f, -0.2f), Vec2(0.3f, -0.2f), Vec2(0.3f, 0.2f), Vec2(-0.07f, 0.2f))),
        spawn = Vec2(spawnU, 0f),
    )

    /** "b" stands on the spawn point; "a" is respawning, so the next sub-step puts it on the same point. */
    private fun respawnOntoB(level: Level): Trace {
        val a = DuelFighter("a", PhysicsBody(CharacterState(pos = Vec2(-0.1f, 0f), h = -0.06f, mode = Mode.RESPAWNING, radiusM = R)), FighterStats.BALANCED)
        return runDuel(DuelState(a, fighter("b", level.spawn)), level, 400L, dtMs = 8L)
    }

    /** Both on the table, apart, circles clear of the wall and on its near side, for the whole trace. */
    private fun assertSeparatedOnTheNearSide(t: Trace, level: Level, what: String) {
        for (s in t.states.drop(1)) {
            assertTrue(s.a.pos.distanceTo(s.b.pos) >= 2 * R - 1e-4f, "$what: overlap at ${s.timeMs} ms: ${s.a.pos} ${s.b.pos}")
            for (f in listOf(s.a, s.b)) {
                assertTrue(level.boundary!!.contains(f.pos), "$what: ${f.id} off the table at ${s.timeMs} ms: ${f.pos}")
                assertTrue(!wall.footprint.contains(f.pos) && distanceToPolygon(f.pos, wall.footprint) >= R - 1e-4f, "$what: ${f.id} in the wall: ${f.pos}")
                assertTrue(f.pos.u + R <= -0.012f + 1e-4f, "$what: ${f.id} got past the wall: ${f.pos}")
            }
        }
    }

    @Test
    fun separationNeverCrossesAWall() {
        // Review scenario (a): spawn at u = -0.0425. CastPhysics.separate put b at u = +0.0175, beyond the wall.
        val level = corridor(-0.0425f)
        val t = respawnOntoB(level)
        assertSeparatedOnTheNearSide(t, level, "spawn -0.0425")
        println("separation (a): a ${t.states[1].a.pos} b ${t.states[1].b.pos}, at rest a ${t.last.a.pos} b ${t.last.b.pos}")
    }

    @Test
    fun separationFreesTwoFightersOnOnePoint() {
        // Review scenario (b): spawn at u = -0.042 (b's circle touches the wall). They used to stay on one point.
        val level = corridor(-0.042f)
        val t = respawnOntoB(level)
        assertSeparatedOnTheNearSide(t, level, "spawn -0.042")
        println("separation (b): a ${t.states[1].a.pos} b ${t.states[1].b.pos}, at rest a ${t.last.a.pos} b ${t.last.b.pos}")

        // With no room at all (a 4 cm square table: two 6 cm circles cannot be apart with both centres on it), the
        // bounded search finds nothing and both stay where they are: overlapped, on the table, nobody thrown off.
        val pocket = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.04f, 0.04f), spawn = Vec2.ZERO)
        val jam = runDuel(DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(0.005f, 0f))), pocket, 200L)
        assertTrue(jam.states.all { it.a.pos == Vec2.ZERO && it.b.pos == Vec2(0.005f, 0f) }, "nobody moves: ${jam.last}")
        assertTrue(jam.all.isEmpty())
    }

    @Test
    fun aLandingNeverPushesAFriendIntoACup() {
        // Review scenario (c): b stands against a cup (its near edge at u = 0.06), a lands 1.5 cm from b on the open
        // side. CastPhysics.separate moved b's centre to u = 0.0525, its circle 22.5 mm inside the cup.
        val cup = cup("cup-1", Vec2(0.1f, 0f))
        val level = Level(listOf(cup), boundary = Polygon.rect(Vec2.ZERO, 0.6f, 0.4f))
        val landing = PhysicsBody(CharacterState(pos = Vec2(0.015f, 0f), h = 0.002f, mode = Mode.JUMPING, radiusM = R), verticalVelocity = -0.5f)
        val start = DuelState(DuelFighter("a", landing, FighterStats.BALANCED), fighter("b", Vec2(0.03f, 0f)))
        val t = runDuel(start, level, 400L, dtMs = 8L)
        val landed = t.states[1]
        assertEquals(Mode.IDLE, landed.a.body.state.mode)
        assertEquals(Vec2(0.03f, 0f), landed.b.pos, "b, blocked by the cup, stays")
        assertTrue(landed.a.pos.distanceTo(Vec2(-0.03f, 0f)) < 1e-5f, "a takes the whole overlap: ${landed.a.pos}")
        for (s in t.states.drop(1)) {
            assertTrue(s.a.pos.distanceTo(s.b.pos) >= 2 * R - 1e-4f)
            for (f in listOf(s.a, s.b)) {
                assertTrue(distanceToPolygon(f.pos, cup.footprint) >= R - 1e-4f && !cup.footprint.contains(f.pos), "${f.id} in the cup: ${f.pos}")
            }
        }
        println("separation (c): a ${landed.a.pos} b ${landed.b.pos}")
    }

    /**
     * The duel's collision code is a copy of the private `Physics.resolveCollisions`. With the walk speed high enough to
     * reach any target in one step and `arriveEpsilonM = 0`, `Physics.step` moves a WALKING body exactly to its target
     * and resolves it from where it stood, nothing else horizontal: its end position is `resolveCollisions(target,
     * from)`. The copy must give the same position on every case, including the cliff guard.
     */
    @Test
    fun collisionCopyMatchesPhysics() {
        val level = Level(
            objects = listOf(
                cup("cup-edge", Vec2(0.22f, 0f)), // spans u 0.18..0.26, past the edge at 0.25: push-outs toward the edge
                cup("cup-mid", Vec2(-0.05f, 0.05f), radius = 0.03f),
                LevelObject("box", "box", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.05f, -0.12f), 0.1f, 0.04f), 0.09f),
                LevelObject("lid", "lid", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.15f, -0.1f), 0.06f, 0.06f), 0.02f),
                book("book", Vec2(-0.15f, 0.12f), 0.08f, 0.06f, 0.03f),
                LevelObject("tall", "box", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.1f, 0.12f), 0.06f, 0.06f), 0.06f),
                LevelObject("wall", "box", ObjectKind.OBSTACLE, Polygon.rect(Vec2(-0.235f, 0f), 0.01f, 0.2f), 0.1f),
            ),
            boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.4f),
        )
        val physics = PhysicsParams.DEFAULT.copy(walkSpeedMps = 1000f, arriveEpsilonM = 0f)
        val rnd = Random(3)
        var kept = 0
        var pushed = 0
        var stopped = 0
        repeat(800) { case ->
            // Half the cases around an object, half anywhere (some past the edge).
            val from = if (rnd.nextBoolean()) {
                val o = level.objects[rnd.nextInt(level.objects.size)].centroid
                o + Vec2(rnd.nextFloat() * 0.16f - 0.08f, rnd.nextFloat() * 0.16f - 0.08f)
            } else {
                Vec2(rnd.nextFloat() * 0.56f - 0.28f, rnd.nextFloat() * 0.46f - 0.23f)
            }
            val to = from + Vec2(rnd.nextFloat() * 0.06f - 0.03f, rnd.nextFloat() * 0.06f - 0.03f)
            val h = floatArrayOf(0f, 0.02f, 0.03f, 0.05f)[rnd.nextInt(4)]
            val r = floatArrayOf(0.015f, 0.03f, 0.06f)[rnd.nextInt(3)]
            val body = PhysicsBody(CharacterState(pos = from, h = h, radiusM = r, target = to, mode = Mode.WALKING))
            val viaPhysics = Physics.step(body, level, 8L, physics).state.pos
            val viaDuel = DuelPhysics.resolveMove(level, from, to, h, r, physics)
            assertEquals(viaPhysics, viaDuel, "case $case: from $from to $to, h $h, r $r")
            when (viaDuel) {
                to -> kept++
                from -> stopped++
                else -> pushed++
            }
        }
        println("collision copy: $kept free, $pushed pushed out, $stopped stopped short (cliff guard)")
        assertTrue(kept > 50 && pushed > 50 && stopped > 3, "the cases cover every branch: $kept / $pushed / $stopped")
    }
}
