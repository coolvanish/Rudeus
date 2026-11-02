package kz.rudy.ar.core.physics

import kz.rudy.ar.core.domain.CharacterEvents
import kz.rudy.ar.core.domain.CharacterSize
import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.Semantics
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Physics on the floor profile: the table game scaled by [SurfaceProfile.FLOOR_SCALE]. */
class PhysicsSurfaceTest {
    private val dt = 16L
    private val table = PhysicsParams.DEFAULT
    private val floor = PhysicsParams.forSurface(Surface.FLOOR)
    private val empty = Level(emptyList())

    private fun body(profile: SurfaceProfile, pos: Vec2 = Vec2.ZERO) =
        PhysicsBody(CharacterState(pos = pos, radiusM = profile.radiusM))

    private fun run(start: PhysicsBody, level: Level, params: PhysicsParams, maxSteps: Int, done: (PhysicsBody) -> Boolean): List<PhysicsBody> {
        val out = arrayListOf(start)
        var b = start
        var i = 0
        while (i < maxSteps && !done(b)) {
            b = Physics.step(b, level, dt, params)
            out += b
            i++
        }
        return out
    }

    /** Highest feet height of one standing jump and the number of steps it spends in the air. */
    private fun hop(profile: SurfaceProfile, params: PhysicsParams): Pair<Float, Int> {
        val t = run(Physics.jump(body(profile), params), empty, params, 2000) { it.state.mode == Mode.IDLE }
        assertEquals(0f, t.last().state.h)
        return t.maxOf { it.state.h } to t.size - 1
    }

    @Test
    fun floorJumpApexScalesWithProfile() {
        val (tableApex, tableAir) = hop(SurfaceProfile.TABLE, table)
        val (floorApex, floorAir) = hop(SurfaceProfile.FLOOR, floor)
        val expected = SurfaceProfile.FLOOR_SCALE * tableApex
        assertTrue(abs(floorApex - expected) <= 0.05f * expected, "floor apex $floorApex vs ${SurfaceProfile.FLOOR_SCALE} x $tableApex")
        // Gravity scales with the jump velocity, so the hop lasts as long: same look, bigger arc.
        assertTrue(abs(floorAir - tableAir) <= 1, "air steps floor $floorAir vs table $tableAir")
        // Semi-implicit Euler at 16 ms peaks 4.8 % under v² / 2g (0.317 vs 0.333 m), on either surface alike.
        val analytic = floor.jumpVelocityMps * floor.jumpVelocityMps / (2f * floor.gravityMps2)
        assertTrue(abs(floorApex - analytic) <= 0.06f * analytic, "floor apex $floorApex vs analytic $analytic")
    }

    @Test
    fun floorWalkIsFourTimesFaster() {
        val distance = 0.4f
        // Steps needed to cover the same distance toward a far target (the arrival snap is profile-sized, so the walk
        // speed is measured on the way, not at the end).
        fun stepsToCover(profile: SurfaceProfile, params: PhysicsParams): Int {
            val t = run(Physics.walkTo(body(profile), Vec2(2f, 0f)), empty, params, 5000) { it.state.pos.u >= distance - 1e-4f }
            assertTrue(t.all { it.state.mode == Mode.WALKING && it.state.h == 0f })
            return t.size - 1
        }
        val tableSteps = stepsToCover(SurfaceProfile.TABLE, table)
        val floorSteps = stepsToCover(SurfaceProfile.FLOOR, floor)
        assertTrue(abs(floorSteps - tableSteps / SurfaceProfile.FLOOR_SCALE) <= 1f, "floor $floorSteps steps vs table $tableSteps / 4")

        // Both still arrive exactly on a target at that distance.
        for ((profile, params) in listOf(SurfaceProfile.TABLE to table, SurfaceProfile.FLOOR to floor)) {
            val end = run(Physics.walkTo(body(profile), Vec2(distance, 0f)), empty, params, 5000) { it.state.mode == Mode.IDLE }.last()
            assertEquals(Mode.IDLE, end.state.mode)
            assertEquals(Vec2(distance, 0f), end.state.pos)
        }
    }

    @Test
    fun twelveCmBoxIsAPlatformOnTheFloorButAnObstacleOnTheTable() {
        val box = LevelObject("box", "box", ObjectKind.PLATFORM, Polygon.rect(Vec2(0.5f, 0f), 0.4f, 0.4f), 0.12f)
        val level = Level(listOf(box))
        val target = Vec2(0.5f, 0f)

        // Floor: a 12 cm box is within the 16 cm step-up, so the character walks onto it.
        val onFloor = run(Physics.walkTo(body(SurfaceProfile.FLOOR), target), level, floor, 2000) { it.state.mode == Mode.IDLE }
        val end = onFloor.last().state
        assertEquals(Mode.IDLE, end.mode)
        assertEquals(target, end.pos)
        assertEquals(0.12f, end.h)
        for (b in onFloor) {
            assertEquals(if (box.footprint.contains(b.state.pos)) 0.12f else 0f, b.state.h, "h at ${b.state.pos}")
            assertTrue(b.state.mode != Mode.FALLING && b.state.mode != Mode.JUMPING)
            assertFalse(b.blocked)
        }

        // Table: the same box is three step-ups tall; it pushes the character out like a cup.
        val onTable = run(Physics.walkTo(body(SurfaceProfile.TABLE), target), level, table, 800) { false }
        for (b in onTable) {
            assertFalse(box.footprint.contains(b.state.pos), "entered the box at ${b.state.pos}")
            assertNull(circlePolygonPushOut(b.state.pos, b.state.radiusM, box.footprint), "overlaps the box at ${b.state.pos}")
            assertEquals(0f, b.state.h)
        }
        val pinned = onTable.last()
        assertEquals(Mode.WALKING, pinned.state.mode)
        assertEquals(0.3f - SurfaceProfile.TABLE.radiusM, pinned.state.pos.u, 1e-4f)
        assertTrue(pinned.blocked)

        // A 20 cm box is past the floor step-up too.
        val tall = box.copy(heightM = 0.2f)
        assertTrue(Physics.blocks(tall, 0f, floor))
        assertFalse(Physics.blocks(box, 0f, floor))
        assertTrue(Physics.blocks(box, 0f, table))
        assertTrue(Physics.blocks(box, 0f), "the default parameters are the table's")
    }

    @Test
    fun platformMaxHeightComesFromParams() {
        // A 12 cm obstacle (a shoe) can be landed on from above on the floor (<= 16 cm), never on the table (> 4 cm).
        val shoe = LevelObject("shoe", "object", ObjectKind.OBSTACLE, Polygon.rect(Vec2.ZERO, 0.2f, 0.1f), 0.12f)
        val level = Level(listOf(shoe))
        assertEquals(0.12f, Physics.groundHeight(level, Vec2.ZERO, 0.12f, floor))
        assertEquals(0f, Physics.groundHeight(level, Vec2.ZERO, 0.12f, table))
        assertEquals(0f, Physics.groundHeight(level, Vec2.ZERO, 0.12f))
        assertEquals(0f, Physics.groundHeight(level, Vec2.ZERO, 0.05f, floor), "not from below the top")
        // Only the params field decides: the floor parameters with the table's platform height cannot stand on it.
        assertEquals(0f, Physics.groundHeight(level, Vec2.ZERO, 0.12f, floor.copy(platformMaxHeightM = table.platformMaxHeightM)))
    }

    @Test
    fun characterSizeStepsStopAtBothEnds() {
        assertEquals(1.5f, CharacterSize.bigger(CharacterSize.DEFAULT))
        assertEquals(0.75f, CharacterSize.smaller(CharacterSize.DEFAULT))
        assertEquals(CharacterSize.MAX, CharacterSize.bigger(CharacterSize.MAX))
        assertEquals(CharacterSize.MIN, CharacterSize.smaller(CharacterSize.MIN))
        assertTrue(CharacterSize.DEFAULT in CharacterSize.STEPS)
        assertEquals(CharacterSize.STEPS.sorted(), CharacterSize.STEPS)
    }

    @Test
    fun defaultSizeLeavesTheSurfacePhysicsUntouched() {
        assertEquals(PhysicsParams.DEFAULT, PhysicsParams.forProfile(SurfaceProfile.TABLE, CharacterSize.DEFAULT))
        assertEquals(floor, PhysicsParams.forProfile(SurfaceProfile.FLOOR, CharacterSize.DEFAULT))
        assertEquals(SurfaceProfile.TABLE.radiusM, SurfaceProfile.TABLE.radiusAt(CharacterSize.DEFAULT))
    }

    @Test
    fun sizeScalesHorizontalMotionButNoHeight() {
        assertEquals(0.09f, SurfaceProfile.TABLE.radiusAt(3f), 1e-6f)
        val big = PhysicsParams.forProfile(SurfaceProfile.TABLE, 3f)
        assertEquals(table.walkSpeedMps * 3f, big.walkSpeedMps, 1e-6f)
        assertEquals(table.arriveEpsilonM * 3f, big.arriveEpsilonM, 1e-6f)
        assertEquals(table.jumpVelocityMps, big.jumpVelocityMps)
        assertEquals(table.gravityMps2, big.gravityMps2)
        assertEquals(table.respawnBelowH, big.respawnBelowH)
        // The scan classified the level with the surface's heights: a bigger character must not re-classify it.
        assertEquals(table.stepUpMaxM, big.stepUpMaxM)
        assertEquals(table.platformMaxHeightM, big.platformMaxHeightM)
    }

    @Test
    fun jumpHeightIsTheSameAtEverySize() {
        val expected = hop(SurfaceProfile.TABLE, table)
        for (size in CharacterSize.STEPS) {
            assertEquals(expected, hop(SurfaceProfile.TABLE, PhysicsParams.forProfile(SurfaceProfile.TABLE, size)), "size $size")
        }
    }

    @Test
    fun biggerCharacterStopsFartherFromTheBox() {
        val box = LevelObject("box", "box", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.5f, 0f), 0.4f, 0.4f), 0.12f)
        val level = Level(listOf(box))
        for (size in CharacterSize.STEPS) {
            val params = PhysicsParams.forProfile(SurfaceProfile.TABLE, size)
            val radius = SurfaceProfile.TABLE.radiusAt(size)
            val start = PhysicsBody(CharacterState(radiusM = radius))
            val end = run(Physics.walkTo(start, Vec2(0.5f, 0f)), level, params, 5000) { it.blocked }.last()
            assertTrue(end.blocked, "size $size never blocked")
            assertEquals(0.3f - radius, end.state.pos.u, 1e-3f, "size $size")
        }
    }

    @Test
    fun everySizeJumpsOverALowObstacle() {
        val low = LevelObject("low", "object", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.5f, 0f), 0.1f, 0.1f), 0.02f)
        val level = Level(listOf(low))
        val target = Vec2(1f, 0f)
        for (size in CharacterSize.STEPS) {
            val params = PhysicsParams.forProfile(SurfaceProfile.TABLE, size)
            val start = PhysicsBody(CharacterState(radiusM = SurfaceProfile.TABLE.radiusAt(size)))
            val blocked = run(Physics.walkTo(start, target), level, params, 5000) { it.blocked }.last()
            assertTrue(blocked.blocked, "size $size never reached the obstacle")
            val end = run(Physics.jump(blocked, params), level, params, 10_000) { it.state.mode == Mode.IDLE }.last()
            assertEquals(target, end.state.pos, "size $size did not clear it")
        }
    }

    @Test
    fun noSizeJumpsOverACup() {
        val cup = LevelObject("cup", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.5f, 0f), 0.1f, 0.1f), 0.09f)
        val level = Level(listOf(cup))
        for (size in CharacterSize.STEPS) {
            val params = PhysicsParams.forProfile(SurfaceProfile.TABLE, size)
            val radius = SurfaceProfile.TABLE.radiusAt(size)
            val start = PhysicsBody(CharacterState(radiusM = radius))
            val blocked = run(Physics.walkTo(start, Vec2(1f, 0f)), level, params, 5000) { it.blocked }.last()
            assertTrue(blocked.blocked, "size $size never reached the cup")
            // Long enough for the jump and the landing. A snap out of the footprint would show as a jump in u.
            for (b in run(Physics.jump(blocked, params), level, params, 100) { false }) {
                assertEquals(0.45f - radius, b.state.pos.u, 1e-3f, "size $size")
            }
        }
    }

    @Test
    fun growingBesideABoxPushesOutWithoutAnyEvent() {
        val box = LevelObject("box", "box", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.5f, 0f), 0.4f, 0.4f), 0.12f)
        val level = Level(listOf(box))
        val beside = PhysicsBody(CharacterState(pos = Vec2(0.3f - SurfaceProfile.TABLE.radiusM, 0f), radiusM = SurfaceProfile.TABLE.radiusM))
        val radius = SurfaceProfile.TABLE.radiusAt(3f)
        val grown = beside.copy(state = beside.state.copy(radiusM = radius))
        val next = Physics.step(grown, level, dt, PhysicsParams.forProfile(SurfaceProfile.TABLE, 3f))
        assertEquals(Mode.IDLE, next.state.mode)
        assertFalse(next.blocked)
        assertEquals(0.3f - radius, next.state.pos.u, 1e-3f)
        assertEquals(emptyList(), CharacterEvents.between(beside, next))
    }

    @Test
    fun standingStillIsNeverPushedOffTheTable() {
        // The spawn is 5 cm from the cup and 3 cm from the table edge: 3× overlaps the cup, and the push-out points off the table.
        val cup = LevelObject("cup", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.1f, 0f), 0.1f, 0.1f), 0.09f)
        val boundary = Polygon.rect(Vec2(0.47f, 0f), 1f, 1f)
        val level = Level(listOf(cup), boundary = boundary)
        val params = PhysicsParams.forProfile(SurfaceProfile.TABLE, 3f)
        val start = PhysicsBody(CharacterState(radiusM = SurfaceProfile.TABLE.radiusAt(3f)))
        for (b in run(start, level, params, 300) { false }) {
            assertEquals(Mode.IDLE, b.state.mode)
            assertEquals(Vec2.ZERO, b.state.pos)
        }
        // A walk does apply the push-out. Wherever it takes the character, it ends up standing, not in a loop.
        val walked = run(Physics.walkTo(start, Vec2(0f, 0.3f)), level, params, 2000) { it.state.mode == Mode.IDLE }
        assertEquals(Mode.IDLE, walked.last().state.mode)
        assertEquals(Mode.IDLE, Physics.step(walked.last(), level, dt, params).state.mode)
    }

    @Test
    fun tableProfileMatchesSemanticsConstants() {
        val t = SurfaceProfile.TABLE
        assertEquals(Semantics.STEP_UP_MAX_M, t.stepUpMaxM)
        assertEquals(Semantics.PLATFORM_MAX_HEIGHT_M, t.platformMaxHeightM)
        assertEquals(1f, t.scale)
        assertEquals(CharacterState().radiusM, t.radiusM)
        assertEquals(Semantics.STEP_UP_MAX_M, PhysicsParams.DEFAULT.stepUpMaxM)
        assertEquals(Semantics.PLATFORM_MAX_HEIGHT_M, PhysicsParams.DEFAULT.platformMaxHeightM)
        assertEquals(PhysicsParams.DEFAULT, PhysicsParams.forSurface(Surface.TABLE))
        assertEquals(t, SurfaceProfile.of(Surface.TABLE))

        val f = PhysicsParams.forSurface(Surface.FLOOR)
        val k = SurfaceProfile.FLOOR_SCALE
        assertEquals(SurfaceProfile.FLOOR.stepUpMaxM, f.stepUpMaxM)
        assertEquals(SurfaceProfile.FLOOR.platformMaxHeightM, f.platformMaxHeightM)
        assertEquals(Semantics.STEP_UP_MAX_M * k, f.stepUpMaxM, 1e-6f)
        assertEquals(PhysicsParams.DEFAULT.walkSpeedMps * k, f.walkSpeedMps, 1e-6f)
    }
}
