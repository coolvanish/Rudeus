package kz.rudy.ar.core.pipeline

import kz.rudy.ar.core.domain.CharacterState
import kz.rudy.ar.core.domain.Level
import kz.rudy.ar.core.domain.LevelObject
import kz.rudy.ar.core.domain.Mode
import kz.rudy.ar.core.domain.ObjectKind
import kz.rudy.ar.core.domain.Polygon
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.physics.Physics
import kz.rudy.ar.core.physics.PhysicsBody
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ARCore's polygon is the part of the table seen so far: outside it must not read as a real edge. */
class SurfaceBoundaryTest {
    private val small = Polygon.rect(Vec2.ZERO, 0.20f, 0.20f)
    private fun level(boundary: Polygon?, objects: List<LevelObject> = emptyList()) = Level(objects, boundary, Vec2.ZERO)
    private fun cup(at: Vec2) = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(at, 0.08f, 0.08f), 0.09f)

    /** Steps until the character stops walking; fails on the first step that leaves the ground. */
    private fun walkWithoutFalling(start: PhysicsBody, level: Level) {
        var body = start
        repeat(2000) {
            body = Physics.step(body, level, 16L)
            assertFalse(body.state.mode == Mode.FALLING || body.state.mode == Mode.RESPAWNING, "fell at ${body.state.pos}")
            if (body.state.mode == Mode.IDLE) return
        }
        error("never arrived, stuck at ${body.state.pos}")
    }

    @Test
    fun aTapPastTheOldPolygonIsWalkedToWithoutAFall() {
        val target = Vec2(0.30f, 0.10f)
        val grown = SurfaceBoundary.grown(level(small), standing = listOf(target))
        assertTrue(grown.boundary!!.contains(target), "the target itself is strictly inside, not a hull vertex")
        val start = PhysicsBody(state = Physics.walkTo(CharacterState(), target))
        walkWithoutFalling(start, grown)
    }

    @Test
    fun aRoundOfTapsAroundTheEdgeNeverFalls() {
        var level = level(small)
        var body = PhysicsBody()
        for (target in listOf(Vec2(0.30f, 0f), Vec2(0.30f, 0.30f), Vec2(-0.25f, 0.30f), Vec2(-0.25f, -0.30f), Vec2.ZERO)) {
            level = SurfaceBoundary.grown(level, standing = listOf(target))
            body = Physics.walkTo(body, target)
            repeat(2000) {
                body = Physics.step(body, level, 16L)
                assertFalse(body.state.mode == Mode.FALLING, "fell at ${body.state.pos} on the way to $target")
            }
            assertTrue(body.state.pos.distanceTo(target) < 0.01f, "reached $target, stopped at ${body.state.pos}")
        }
    }

    @Test
    fun aTapInsideChangesNothing() {
        val big = level(Polygon.rect(Vec2.ZERO, 1f, 1f))
        assertSame(big, SurfaceBoundary.grown(big, standing = listOf(Vec2(0.1f, 0.1f))))
    }

    /** The readings are the Pixel's, 2026-11-03: -7.5 cm was the lowest on the table, -28.8 cm a tap past its end. */
    @Test
    fun onlyADepthDropReadsAsPastTheEdge() {
        assertFalse(SurfaceBoundary.crossesEdge(listOf(null, -0.075f, 0.13f, 0.002f), SurfaceProfile.TABLE))
        assertFalse(SurfaceBoundary.crossesEdge(emptyList(), SurfaceProfile.TABLE))
        assertTrue(SurfaceBoundary.crossesEdge(listOf(0.01f, -0.288f, null), SurfaceProfile.TABLE))
        assertFalse(SurfaceBoundary.crossesEdge(listOf(-0.288f), SurfaceProfile.FLOOR), "scales with the surface")
    }

    /** A 3× character beside a cup at the rim, where the push-out points off the table (a review found it walking through). */
    @Test
    fun anOverlappingCharacterAtTheRimDoesNotWalkThroughTheCup() {
        val rim = Polygon.rect(Vec2(0.47f, 0f), 1f, 1f)
        val cupNearby = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.1f, 0f), 0.1f, 0.1f), 0.09f)
        val params = PhysicsParams.forProfile(SurfaceProfile.TABLE, 3f)
        val radius = SurfaceProfile.TABLE.radiusAt(3f)
        val lvl = level(rim, listOf(cupNearby))

        var through = PhysicsBody(state = Physics.walkTo(CharacterState(radiusM = radius), Vec2(0.30f, 0f)))
        repeat(600) { through = Physics.step(through, lvl, 16L, params) }
        assertTrue(through.state.pos.u < 0.05f, "walked into the cup, at ${through.state.pos}")

        var away = PhysicsBody(state = Physics.walkTo(CharacterState(radiusM = radius), Vec2(0f, 0.30f)))
        repeat(600) { away = Physics.step(away, lvl, 16L, params) }
        assertTrue(away.state.pos.v > 0.1f, "trapped beside the cup, at ${away.state.pos}")
    }

    @Test
    fun pastTheHullIsStillACliff() {
        val grown = SurfaceBoundary.grown(level(small), standing = listOf(Vec2(0.30f, 0f)))
        val body = Physics.step(PhysicsBody(state = CharacterState(pos = Vec2(0.60f, 0f))), grown, 16L)
        assertEquals(Mode.FALLING, body.state.mode)
    }

    @Test
    fun theFloorStaysUnbounded() {
        val floor = level(null)
        assertSame(floor, SurfaceBoundary.grown(floor, standing = listOf(Vec2(5f, 5f))))
        assertNull(SurfaceBoundary.afterScan(floor, floor, CharacterState(pos = Vec2(5f, 5f))).boundary)
    }

    @Test
    fun aRescanWithASmallerPolygonDoesNotDropTheCharacter() {
        val current = level(Polygon.rect(Vec2.ZERO, 1f, 1f))
        val target = Vec2(0.45f, 0f)
        val state = Physics.walkTo(CharacterState(pos = Vec2(0.40f, 0f)), target)
        val swapped = SurfaceBoundary.afterScan(level(small), current, state)
        assertTrue(swapped.boundary!!.area >= current.boundary!!.area, "never shrinks")
        walkWithoutFalling(PhysicsBody(state = state), swapped)
    }

    @Test
    fun aFirstScanWhosePolygonMissesTheSpawnDoesNotLoopRespawns() {
        val away = Polygon.rect(Vec2(1f, 1f), 0.40f, 0.40f)
        val swapped = SurfaceBoundary.afterScan(level(away), level(null), CharacterState())
        assertTrue(swapped.boundary!!.contains(Vec2.ZERO))
        val body = Physics.step(PhysicsBody(state = CharacterState(mode = Mode.RESPAWNING)), swapped, 16L)
        assertEquals(Mode.IDLE, Physics.step(body, swapped, 16L).state.mode)
    }

    @Test
    fun aFallingCharacterDoesNotPullTheBoundaryAfterIt() {
        val off = Vec2(0.60f, 0f)
        val state = CharacterState(pos = off, mode = Mode.FALLING)
        val swapped = SurfaceBoundary.afterScan(level(small), level(small), state)
        assertFalse(swapped.boundary!!.contains(off))
    }

    @Test
    fun objectsDoNotMoveTheBoundary() {
        // A chair on the floor behind the table projects far past the edge; the hull would never shrink back.
        val scanned = level(small, listOf(cup(Vec2(4f, 0f))))
        val swapped = SurfaceBoundary.afterScan(scanned, level(null), CharacterState())
        assertFalse(swapped.boundary!!.contains(Vec2(1f, 0f)))
    }

    @Test
    fun aRepeatedTapChangesNothing() {
        val once = SurfaceBoundary.grown(level(small), standing = listOf(Vec2(0.30f, 0.10f)))
        assertSame(once, SurfaceBoundary.grown(once, standing = listOf(Vec2(0.30f, 0.10f))))
    }

    @Test
    fun aCupAtTheRimDoesNotPushARescannedCharacterOff() {
        val rim = Polygon.rect(Vec2.ZERO, 0.40f, 0.40f)
        val cupOverRim = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.17f, 0f), 0.08f, 0.08f), 0.09f)
        val state = CharacterState(pos = Vec2(0.19f, 0f))
        val swapped = SurfaceBoundary.afterScan(level(rim, listOf(cupOverRim)), level(rim), state)
        var body = PhysicsBody(state = state)
        repeat(200) {
            body = Physics.step(body, swapped, 16L)
            assertFalse(body.state.mode == Mode.FALLING, "pushed off at ${body.state.pos}")
        }
    }

    @Test
    fun aHandAtTheRimDoesNotPushACharacterOff() {
        val rim = Polygon.rect(Vec2.ZERO, 0.40f, 0.40f)
        val hand = LevelObject("hand-1", "hand", ObjectKind.DYNAMIC, Polygon.rect(Vec2(0.17f, 0f), 0.08f, 0.08f), 0.09f)
        var body = PhysicsBody(state = CharacterState(pos = Vec2(0.19f, 0f)))
        repeat(200) {
            body = Physics.step(body, level(rim, listOf(hand)), 16L)
            assertFalse(body.state.mode == Mode.FALLING, "pushed off at ${body.state.pos}")
        }
    }

    /** Falls without the push-out rule: the cup slides the character over the rim on the way to the tap. */
    @Test
    fun aTapPastACupAtTheRimDoesNotFall() {
        val rim = Polygon.rect(Vec2.ZERO, 0.40f, 0.40f)
        val cupAtRim = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.circle(Vec2(-0.123f, 0.173f), 0.04f), 0.09f)
        val target = Vec2(-0.106f, 0.199f)
        val lvl = SurfaceBoundary.grown(level(rim, listOf(cupAtRim)), standing = listOf(target))
        var body = PhysicsBody(state = Physics.walkTo(CharacterState(), target))
        repeat(1500) {
            body = Physics.step(body, lvl, 16L)
            assertFalse(body.state.mode == Mode.FALLING, "fell at ${body.state.pos}")
        }
    }

    @Test
    fun aCupAtTheRimStopsAWalkerInsteadOfLettingItIn() {
        val rim = Polygon.rect(Vec2.ZERO, 0.40f, 0.40f)
        val at = Vec2(0.053f, 0.234f)
        val cupAtRim = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.circle(at, 0.04f), 0.09f)
        val target = Vec2(0.0628f, 0.2487f)
        val lvl = SurfaceBoundary.grown(level(rim, listOf(cupAtRim)), standing = listOf(target))
        var body = PhysicsBody(state = Physics.walkTo(CharacterState(pos = Vec2(0.163f, 0.116f)), target))
        repeat(1500) {
            body = Physics.step(body, lvl, 16L)
            assertFalse(body.state.mode == Mode.FALLING, "fell at ${body.state.pos}")
        }
        assertFalse(cupAtRim.footprint.contains(body.state.pos), "stopped inside the cup at ${body.state.pos}")
    }

    @Test
    fun aCharacterARescanPutACupOnWalksOut() {
        val rim = Polygon.rect(Vec2.ZERO, 0.40f, 0.40f)
        val cupOnIt = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.rect(Vec2(0.17f, 0f), 0.08f, 0.08f), 0.09f)
        var body = PhysicsBody(state = Physics.walkTo(CharacterState(pos = Vec2(0.19f, 0f)), Vec2(-0.10f, 0f)))
        repeat(600) { body = Physics.step(body, level(rim, listOf(cupOnIt)), 16L) }
        assertTrue(body.state.pos.distanceTo(Vec2(-0.10f, 0f)) < 0.01f, "trapped at ${body.state.pos}")
    }

    @Test
    fun aCupInMidTableStillBlocks() {
        val big = Polygon.rect(Vec2.ZERO, 1f, 1f)
        val mid = LevelObject("cup-1", "cup", ObjectKind.OBSTACLE, Polygon.circle(Vec2(0.20f, 0f), 0.04f), 0.09f)
        var body = PhysicsBody(state = Physics.walkTo(CharacterState(), Vec2(0.20f, 0f)))
        repeat(300) { body = Physics.step(body, level(big, listOf(mid)), 16L) }
        assertTrue(body.state.pos.distanceTo(Vec2(0.20f, 0f)) >= 0.04f + body.state.radiusM - 0.005f, "stopped at the cup: ${body.state.pos}")
    }
}
