package kz.rudy.ar.core.physics

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
