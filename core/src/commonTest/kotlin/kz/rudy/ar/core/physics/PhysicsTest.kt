package kz.rudy.ar.core.physics

import kz.rudy.ar.core.domain.Ability
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DT = 16L
private val P = PhysicsParams.DEFAULT

/** Run [steps] fixed-dt steps, returning every intermediate body (index 0 = input). */
private fun trace(body: PhysicsBody, level: Level, steps: Int, params: PhysicsParams = P): List<PhysicsBody> {
    val out = ArrayList<PhysicsBody>(steps + 1)
    var b = body
    out += b
    repeat(steps) {
        b = Physics.step(b, level, DT, params)
        out += b
    }
    return out
}

/** Step until [done] holds or [maxSteps] elapse; returns the trace. */
private fun stepUntil(
    body: PhysicsBody,
    level: Level,
    maxSteps: Int,
    params: PhysicsParams = P,
    done: (PhysicsBody) -> Boolean,
): List<PhysicsBody> {
    val out = ArrayList<PhysicsBody>()
    var b = body
    out += b
    var i = 0
    while (i < maxSteps && !done(b)) {
        b = Physics.step(b, level, DT, params)
        out += b
        i++
    }
    return out
}

private fun body(pos: Vec2 = Vec2.ZERO, ability: Ability = Ability.JUMP) =
    PhysicsBody(CharacterState(pos = pos, ability = ability))

private fun cup(center: Vec2, radius: Float = 0.04f, heightM: Float = 0.09f) =
    LevelObject("cup", "cup", ObjectKind.OBSTACLE, Polygon.circle(center, radius), heightM)

private fun book(center: Vec2, width: Float = 0.1f, depth: Float = 0.1f, heightM: Float = 0.03f) =
    LevelObject("book", "book", ObjectKind.PLATFORM, Polygon.rect(center, width, depth), heightM)

private fun lowObstacle(center: Vec2, width: Float = 0.02f, heightM: Float = 0.02f) =
    LevelObject("lid", "object", ObjectKind.OBSTACLE, Polygon.rect(center, width, 0.1f), heightM)

private val emptyLevel = Level(emptyList())

private fun distanceToPolygon(p: Vec2, poly: Polygon): Float =
    poly.edges().minOf { (a, b) -> p.distanceTo(closestPointOnSegment(p, a, b)) }

class CollisionTest {
    @Test
    fun closestPointClampsToSegmentEnds() {
        val a = Vec2(0f, 0f); val b = Vec2(1f, 0f)
        assertEquals(Vec2(0.5f, 0f), closestPointOnSegment(Vec2(0.5f, 2f), a, b))
        assertEquals(a, closestPointOnSegment(Vec2(-3f, 1f), a, b))
        assertEquals(b, closestPointOnSegment(Vec2(4f, -1f), a, b))
        assertEquals(a, closestPointOnSegment(Vec2(4f, -1f), a, a))
    }

    @Test
    fun pushOutFromOutsideInsideAndNone() {
        val square = Polygon.rect(Vec2.ZERO, 0.1f, 0.1f)
        assertNull(circlePolygonPushOut(Vec2(0.1f, 0f), 0.03f, square))
        // Outside, overlapping the right edge (u = 0.05) by 1 cm.
        val push = assertNotNull(circlePolygonPushOut(Vec2(0.07f, 0f), 0.03f, square))
        assertEquals(0.01f, push.u, 1e-5f); assertEquals(0f, push.v, 1e-5f)
        // Inside near the right edge: pushed out through that edge by (distance + radius).
        val inside = assertNotNull(circlePolygonPushOut(Vec2(0.04f, 0f), 0.03f, square))
        assertEquals(0.04f, inside.u, 1e-5f); assertEquals(0f, inside.v, 1e-5f)
        // Clockwise winding gives the same answer.
        val cw = Polygon(square.points.reversed())
        val insideCw = assertNotNull(circlePolygonPushOut(Vec2(0.04f, 0f), 0.03f, cw))
        assertEquals(0.04f, insideCw.u, 1e-5f); assertEquals(0f, insideCw.v, 1e-5f)
        // Resolved position no longer overlaps.
        assertNull(circlePolygonPushOut(Vec2(0.04f, 0f) + inside, 0.03f, square))
    }
}

class PhysicsTest {

    @Test
    fun walkReachesTargetAndIdles() {
        val target = Vec2(0.1f, 0.05f)
        val start = Physics.walkTo(body(), target)
        assertEquals(Mode.WALKING, start.state.mode)
        val t = stepUntil(start, emptyLevel, 2000) { it.state.mode == Mode.IDLE }
        val end = t.last().state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(target, end.pos)
        assertNull(end.target)
        assertEquals(0f, end.h)
        // Arrives (and snaps) once within arriveEpsilonM of the target.
        val expectedSteps = kotlin.math.ceil((target.length - P.arriveEpsilonM) / (P.walkSpeedMps * DT / 1000f)).toInt()
        assertTrue(abs(t.size - 1 - expectedSteps) <= 1, "took ${t.size - 1} steps, expected ~$expectedSteps")
        val dir = target.normalized()
        assertEquals(dir.u, end.facing.u, 1e-4f); assertEquals(dir.v, end.facing.v, 1e-4f)
        // Every intermediate step moved by exactly one walk step.
        for (i in 1 until t.size - 1) {
            assertEquals(P.walkSpeedMps * DT / 1000f, t[i].state.pos.distanceTo(t[i - 1].state.pos), 1e-5f)
        }
    }

    @Test
    fun cupBlocksWalkAndPushesOut() {
        val cup = cup(Vec2.ZERO)
        val level = Level(listOf(cup))
        val start = Physics.walkTo(body(Vec2(-0.15f, 0f)), Vec2(0.15f, 0f))
        val t = trace(start, level, 600)
        for (b in t) {
            assertFalse(cup.footprint.contains(b.state.pos), "centre entered the cup at ${b.state.pos}")
            assertNull(circlePolygonPushOut(b.state.pos, b.state.radiusM, cup.footprint), "circle overlaps the cup")
            assertEquals(0f, b.state.h)
        }
        val end = t.last().state
        assertEquals(Mode.WALKING, end.mode, "still pressing against the cup")
        assertNotNull(end.target)
        assertEquals(end.radiusM, distanceToPolygon(end.pos, cup.footprint), 1e-3f)
        assertTrue(end.pos.u < 0f, "stays on the near side")
    }

    @Test
    fun bookStepUpSetsHeight() {
        val book = book(Vec2(0.1f, 0f))
        val level = Level(listOf(book))
        val onto = stepUntil(Physics.walkTo(body(), Vec2(0.1f, 0f)), level, 2000) { it.state.mode == Mode.IDLE }
        val end = onto.last().state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(Vec2(0.1f, 0f), end.pos)
        assertEquals(0.03f, end.h)
        // Height is set the moment the centre enters the footprint and never oscillates.
        for (b in onto) {
            val expected = if (book.footprint.contains(b.state.pos)) 0.03f else 0f
            assertEquals(expected, b.state.h, "h at ${b.state.pos}")
            assertTrue(b.state.mode != Mode.FALLING && b.state.mode != Mode.JUMPING)
        }
    }

    @Test
    fun leavingPlatformFallsBackToGround() {
        val book = book(Vec2(0.1f, 0f))
        val level = Level(listOf(book))
        val onBook = PhysicsBody(CharacterState(pos = Vec2(0.1f, 0f), h = 0.03f))
        val t = stepUntil(Physics.walkTo(onBook, Vec2(0.3f, 0f)), level, 2000) { it.state.mode == Mode.IDLE }
        val end = t.last().state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(Vec2(0.3f, 0f), end.pos)
        assertEquals(0f, end.h)
        assertTrue(t.any { it.state.mode == Mode.FALLING }, "drop uses FALLING")
        assertTrue(t.none { it.state.mode == Mode.RESPAWNING })
        val firstOff = t.first { !book.footprint.contains(it.state.pos) }
        val landed = t.first { it.state.h == 0f }
        assertTrue(landed.state.pos.u > firstOff.state.pos.u, "keeps walking while dropping")
        assertTrue(t.indexOf(landed) - t.indexOf(firstOff) < 30, "drop from 3 cm is brief")
    }

    @Test
    fun tallObjectIsNeverSteppedOn() {
        // A 9 cm "book" is an OBSTACLE by Semantics; it must behave like a cup even when walked at and jumped at.
        val tall = LevelObject("tall", "book", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.1f, 0f), 0.1f, 0.1f), 0.09f)
        val level = Level(listOf(tall))
        var b = Physics.walkTo(body(), Vec2(0.1f, 0f))
        var maxH = 0f
        repeat(800) { i ->
            if (i % 60 == 0) b = Physics.jump(b)
            b = Physics.step(b, level, DT)
            maxH = maxOf(maxH, b.state.h)
            assertFalse(tall.footprint.contains(b.state.pos), "centre entered the tall object at ${b.state.pos}")
            assertNull(circlePolygonPushOut(b.state.pos, b.state.radiusM, tall.footprint))
            assertTrue(b.state.h < 0.09f)
        }
        assertTrue(maxH > 0.05f, "jumps happened (max h $maxH)")
        val settled = stepUntil(b, level, 200) { it.state.h == 0f && it.verticalVelocity == 0f }.last().state
        assertEquals(0f, settled.h)
        assertFalse(tall.footprint.contains(settled.pos))
    }

    @Test
    fun cliffFallThenRespawnAtSpawn() {
        val level = Level(emptyList(), boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f), spawn = Vec2(0.02f, 0.01f))
        val start = Physics.walkTo(body(), Vec2(0.25f, 0f))
        val t = stepUntil(start, level, 2000) { it.state.mode == Mode.IDLE }
        val modes = t.map { it.state.mode }
        val fallAt = modes.indexOf(Mode.FALLING)
        val respawnAt = modes.indexOf(Mode.RESPAWNING)
        assertTrue(fallAt > 0, "fell")
        assertTrue(respawnAt > fallAt, "respawned after falling")
        assertEquals(Mode.IDLE, modes.last())
        assertEquals(respawnAt + 1, t.size - 1, "IDLE the step after RESPAWNING")
        val end = t.last()
        assertEquals(level.spawn, end.state.pos)
        assertEquals(0f, end.state.h)
        assertNull(end.state.target)
        assertEquals(0f, end.verticalVelocity)
        // Fell past the edge, horizontally frozen, down to the respawn depth.
        val fall = t.subList(fallAt, respawnAt + 1)
        assertTrue(fall.all { it.state.pos.u > 0.15f - 1e-4f })
        assertEquals(1, fall.map { it.state.pos }.toSet().size, "no horizontal motion while falling off the cliff")
        assertTrue(fall.last().state.h < P.respawnBelowH)
        assertTrue(fall.zipWithNext().all { (a, b) -> b.state.h < a.state.h }, "monotonic fall")
    }

    @Test
    fun jumpParabolaReturnsToGround() {
        val jumped = Physics.jump(body())
        assertEquals(Mode.JUMPING, jumped.state.mode)
        assertEquals(P.jumpVelocityMps, jumped.verticalVelocity)
        // Jumping again while airborne is a no-op.
        assertEquals(jumped, Physics.jump(jumped))
        val t = stepUntil(jumped, emptyLevel, 500) { it.state.mode == Mode.IDLE }
        val hs = t.map { it.state.h }
        val apex = hs.max()
        val analyticApex = P.jumpVelocityMps * P.jumpVelocityMps / (2f * P.gravityMps2)
        assertTrue(abs(apex - analyticApex) < 0.006f, "apex $apex vs $analyticApex")
        val airSteps = t.size - 1
        val analyticAir = 2f * P.jumpVelocityMps / P.gravityMps2 / (DT / 1000f)
        assertTrue(abs(airSteps - analyticAir) <= 2, "air time $airSteps steps vs $analyticAir")
        assertEquals(0f, t.last().state.h)
        assertEquals(Mode.IDLE, t.last().state.mode)
        assertEquals(0f, t.last().verticalVelocity)
        assertEquals(Vec2.ZERO, t.last().state.pos)
        // Rises, then falls: one sign change.
        val apexIdx = hs.indexOf(apex)
        assertTrue(hs.subList(0, apexIdx + 1).zipWithNext().all { (a, b) -> b > a })
        assertTrue(hs.subList(apexIdx, hs.size).zipWithNext().all { (a, b) -> b < a })
        // The CharacterState-only overload is launched by the next step.
        val viaState = PhysicsBody(Physics.jump(CharacterState()))
        val launched = Physics.step(viaState, emptyLevel, DT)
        assertTrue(launched.state.h > 0f && launched.verticalVelocity > 0f)
    }

    @Test
    fun jumpClearsLowObstacleButNotCup() {
        val target = Vec2(0.12f, 0f)
        // Low (2 cm) obstacle between x = -0.01 and 0.01: start touching it, jump, keep walking.
        val lid = lowObstacle(Vec2.ZERO)
        val lowLevel = Level(listOf(lid))
        val lowStart = Physics.jump(Physics.walkTo(body(Vec2(-0.04f, 0f)), target))
        val low = stepUntil(lowStart, lowLevel, 2000) { it.state.mode == Mode.IDLE }
        val lowEnd = low.last().state
        assertEquals(Mode.IDLE, lowEnd.mode)
        assertEquals(target, lowEnd.pos)
        assertEquals(0f, lowEnd.h)
        for (b in low) {
            if (lid.footprint.contains(b.state.pos)) assertTrue(b.state.h >= lid.heightM - 1e-4f, "inside the lid at h=${b.state.h}")
        }
        assertTrue(low.any { lid.footprint.contains(it.state.pos) }, "passed over the lid")

        // Same jump at a 9 cm cup: blocked, never inside, stays on the near side.
        val cup = cup(Vec2.ZERO, radius = 0.01f)
        val cupLevel = Level(listOf(cup))
        val cupStart = Physics.jump(Physics.walkTo(body(Vec2(-0.04f, 0f)), target))
        val cupTrace = trace(cupStart, cupLevel, 600)
        for (b in cupTrace) {
            assertNull(circlePolygonPushOut(b.state.pos, b.state.radiusM, cup.footprint), "overlapped the cup at ${b.state.pos}")
        }
        val cupEnd = cupTrace.last().state
        assertTrue(cupEnd.pos.u < 0f)
        assertEquals(Mode.WALKING, cupEnd.mode)
        assertEquals(0f, cupEnd.h)
    }

    @Test
    fun rollIsTwiceAsFastAndCannotJump() {
        val target = Vec2(0.2f, 0f)
        val walker = stepUntil(Physics.walkTo(body(ability = Ability.JUMP), target), emptyLevel, 4000) { it.state.mode == Mode.IDLE }
        val roller = stepUntil(Physics.walkTo(body(ability = Ability.ROLL), target), emptyLevel, 4000) { it.state.mode == Mode.IDLE }
        assertEquals(target, roller.last().state.pos)
        val ratio = (walker.size - 1).toFloat() / (roller.size - 1).toFloat()
        assertTrue(abs(ratio - P.rollSpeedScale) < 0.05f, "ratio $ratio")

        val rolling = Physics.walkTo(body(ability = Ability.ROLL), target)
        assertEquals(rolling, Physics.jump(rolling))
        assertEquals(rolling.state, Physics.jump(rolling.state))
        val t = trace(rolling, emptyLevel, 50)
        assertTrue(t.all { it.state.h == 0f && it.state.mode == Mode.WALKING })
    }

    @Test
    fun flyDoesNotFallOverCliffWhileAirborne() {
        // Table with an 8 cm notch cut in from the bottom edge: at v = 0 the strip u in (-0.04, 0.04) is a cliff.
        val notched = Polygon(
            listOf(
                Vec2(-0.2f, -0.1f), Vec2(-0.04f, -0.1f), Vec2(-0.04f, 0.08f), Vec2(0.04f, 0.08f),
                Vec2(0.04f, -0.1f), Vec2(0.2f, -0.1f), Vec2(0.2f, 0.1f), Vec2(-0.2f, 0.1f),
            )
        )
        val level = Level(emptyList(), boundary = notched, spawn = Vec2(-0.15f, 0f))
        assertTrue(Physics.isOverCliff(level, Vec2.ZERO))
        assertFalse(Physics.isOverCliff(level, Vec2(-0.06f, 0f)))
        val target = Vec2(0.17f, 0f)

        val flyer = Physics.jump(Physics.walkTo(body(Vec2(-0.06f, 0f), Ability.FLY), target))
        val flight = stepUntil(flyer, level, 3000) { it.state.mode == Mode.IDLE }
        assertTrue(flight.any { Physics.isOverCliff(level, it.state.pos) && it.state.mode == Mode.JUMPING }, "crossed the gap airborne")
        assertTrue(flight.none { it.state.mode == Mode.FALLING || it.state.mode == Mode.RESPAWNING }, "flyer never fell")
        val end = flight.last().state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(target, end.pos)
        assertEquals(0f, end.h)
        val landing = flight.first { it.state.mode == Mode.WALKING }
        assertTrue(landing.state.pos.u > 0.04f, "landed on the far side at ${landing.state.pos}")
        // Fly gravity is scaled: the arc stays up longer than a plain jump.
        val flyAir = flight.indexOfFirst { it.state.mode == Mode.WALKING }
        val plainAir = stepUntil(Physics.jump(body()), emptyLevel, 500) { it.state.mode == Mode.IDLE }.size - 1
        assertTrue(flyAir > plainAir * 2, "fly air $flyAir steps vs jump $plainAir")

        // Control: the same jump without FLY falls into the gap and respawns.
        val jumper = Physics.jump(Physics.walkTo(body(Vec2(-0.06f, 0f), Ability.JUMP), target))
        val drop = stepUntil(jumper, level, 3000) { it.state.mode == Mode.IDLE }
        assertTrue(drop.any { it.state.mode == Mode.FALLING }, "jumper fell")
        assertTrue(drop.any { it.state.mode == Mode.RESPAWNING }, "jumper respawned")
        assertEquals(level.spawn, drop.last().state.pos)
    }

    @Test
    fun pausedStepIsIdentity() {
        val level = Level(listOf(cup(Vec2(0.1f, 0f))), boundary = Polygon.rect(Vec2.ZERO, 0.5f, 0.5f))
        val walking = trace(Physics.walkTo(body(), Vec2(0.2f, 0.1f)), level, 10).last()
        val midJump = trace(Physics.jump(walking), level, 5).last()
        assertEquals(Mode.JUMPING, midJump.state.mode)
        val paused = Physics.pause(midJump)
        assertEquals(Mode.PAUSED, paused.state.mode)
        assertEquals(paused, Physics.pause(paused))
        val frozen = trace(paused, level, 200)
        assertTrue(frozen.all { it == paused })
        // Resume restores the exact mode and the jump continues.
        val resumed = Physics.resume(paused)
        assertEquals(midJump, resumed)
        // The CharacterState-only overloads infer WALKING (target set) because they carry no memory.
        assertEquals(Mode.WALKING, Physics.resume(Physics.pause(midJump.state)).mode)
        val next = Physics.step(resumed, level, DT)
        assertEquals(Physics.step(midJump, level, DT), next)
        // Pause never resets.
        assertEquals(midJump.state.pos, paused.state.pos)
        assertEquals(midJump.state.h, paused.state.h)
    }

    @Test
    fun stepIsDeterministic() {
        val level = Level(
            listOf(cup(Vec2(0.05f, 0f)), book(Vec2(0.0f, 0.08f)), lowObstacle(Vec2(-0.05f, 0f))),
            boundary = Polygon.rect(Vec2.ZERO, 0.3f, 0.3f),
            spawn = Vec2(0.01f, -0.01f),
        )
        fun run(): List<PhysicsBody> {
            var b = Physics.walkTo(body(Vec2(-0.12f, 0f)), Vec2(0.12f, 0.08f))
            val out = ArrayList<PhysicsBody>()
            repeat(1500) { i ->
                if (i % 90 == 0) b = Physics.jump(b)
                if (i == 700) b = Physics.walkTo(b, Vec2(0.2f, 0f))
                b = Physics.step(b, level, DT)
                out += b
            }
            return out
        }
        val a = run(); val b = run()
        assertEquals(a, b)
        assertTrue(a.map { it.state.mode }.toSet().containsAll(setOf(Mode.WALKING, Mode.JUMPING)))
    }
}
