package kz.rudy.ar.core.duel

import kotlinx.serialization.json.Json
import kz.rudy.ar.core.domain.FighterStats
import kz.rudy.ar.core.domain.Surface
import kz.rudy.ar.core.domain.SurfaceProfile
import kz.rudy.ar.core.domain.Vec2
import kz.rudy.ar.core.duel.DuelInput.Dash
import kz.rudy.ar.core.physics.PhysicsParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DuelParamsTest {
    private val table = DuelParams.DEFAULT

    /** A balanced dash hit on an open level at [profile]'s size: (victim slide, stun length, dash length). */
    private fun exchange(params: DuelParams, physics: PhysicsParams, radius: Float): Triple<Float, Long, Float> {
        val start = DuelState(fighter("a", Vec2.ZERO, radiusM = radius), fighter("b", Vec2(2 * radius + 0.001f * radius / R, 0f), radiusM = radius))
        val t = runDuel(start, OPEN, 2500L, dtMs = 8L, params = params, physics = physics, script = atStart("a" to Dash()))
        val hit = t.indexOf { it is DuelEvent.Hit }
        val free = (hit until t.states.size).first { !DuelPhysics.isStunned(t.states[it].b, params) }
        val miss = runDuel(DuelState(fighter("a", Vec2.ZERO, radiusM = radius), fighter("b", Vec2(0f, 10f), radiusM = radius)), OPEN, 600L,
            params = params, physics = physics, script = atStart("a" to Dash()))
        return Triple(t.last.b.pos.u - start.b.pos.u, t.states[free].timeMs - t.states[hit].timeMs, miss.last.a.pos.u)
    }

    @Test
    fun duelParamsScaleWithTheSurface() {
        assertEquals(table, DuelParams.forProfile(SurfaceProfile.TABLE))
        assertEquals(table, DuelParams.forSurface(Surface.TABLE))
        val k = SurfaceProfile.FLOOR_SCALE
        val floor = DuelParams.forSurface(Surface.FLOOR)
        assertEquals(table.dashSpeedMps * k, floor.dashSpeedMps, 1e-6f)
        assertEquals(table.knockSpeedMps * k, floor.knockSpeedMps, 1e-6f)
        assertEquals(table.knockStunMps * k, floor.knockStunMps, 1e-6f)
        assertEquals(table.knockStopMps * k, floor.knockStopMps, 1e-6f)
        assertEquals(table.wallSlamMinMps * k, floor.wallSlamMinMps, 1e-6f)
        assertEquals(table.fromAboveMinM * k, floor.fromAboveMinM, 1e-6f)
        // T57: the hit's height reach is a height too.
        assertEquals(table.hitReachUpM * k, floor.hitReachUpM, 1e-6f)
        assertEquals(table.hitReachDownM * k, floor.hitReachDownM, 1e-6f)
        // Times and dimensionless factors stay: put the scaled numbers back and nothing else differs.
        val unscaled = floor.copy(
            dashSpeedMps = table.dashSpeedMps, knockSpeedMps = table.knockSpeedMps, knockStunMps = table.knockStunMps,
            knockStopMps = table.knockStopMps, wallSlamMinMps = table.wallSlamMinMps, fromAboveMinM = table.fromAboveMinM,
            hitReachUpM = table.hitReachUpM, hitReachDownM = table.hitReachDownM,
        )
        assertEquals(table, unscaled)
        for (p in 1..10) for (g in 1..10) {
            val a = FighterStats(p, 5, 5, 5)
            val v = FighterStats(5, 5, g, 5)
            assertEquals(table.knockSpeedMpsFor(a, v) * k, floor.knockSpeedMpsFor(a, v), 1e-5f)
            assertEquals(table.cooldownMsFor(v), floor.cooldownMsFor(v))
            assertEquals(table.hitStunMsFor(a), floor.hitStunMsFor(a))
        }
        // The walk speed comes from the scaled PhysicsParams, so it follows the surface by itself.
        assertEquals(
            table.walkSpeedMpsFor(FighterStats.BALANCED) * k,
            floor.walkSpeedMpsFor(FighterStats.BALANCED, PhysicsParams.forSurface(Surface.FLOOR)),
            1e-6f,
        )

        // A bigger character on the table: horizontal speeds follow the size, the high-ground height does not.
        val big = DuelParams.forProfile(SurfaceProfile.TABLE, 2f)
        assertEquals(table.dashSpeedMps * 2f, big.dashSpeedMps, 1e-6f)
        assertEquals(table.knockSpeedMps * 2f, big.knockSpeedMps, 1e-6f)
        assertEquals(table.fromAboveMinM, big.fromAboveMinM)
        assertEquals(table.hitReachUpM to table.hitReachDownM, big.hitReachUpM to big.hitReachDownM)

        // Behaviour: the same exchange on the floor slides and dashes 4x as far and stuns exactly as long.
        val (tSlide, tStun, tDash) = exchange(table, PhysicsParams.DEFAULT, R)
        val (fSlide, fStun, fDash) = exchange(floor, PhysicsParams.forSurface(Surface.FLOOR), SurfaceProfile.FLOOR.radiusM)
        assertEquals(k * tSlide, fSlide, 0.01f * k * tSlide, "slide table $tSlide floor $fSlide")
        assertEquals(k * tDash, fDash, 1e-3f * k * tDash, "dash table $tDash floor $fDash")
        assertEquals(tStun, fStun, "stun table $tStun floor $fStun")
    }

    @Test
    fun badParamsAreRejectedByName() {
        // Each of these would divide by zero, reverse a push, stun forever or turn positions into NaN.
        val bad: List<Pair<String, () -> DuelParams>> = listOf(
            "guardBase" to { DuelParams(guardBase = -0.09f) }, // GUARD 1: zero divisor, infinite knockback
            "powerPerPoint" to { DuelParams(powerPerPoint = -0.2f) }, // POWER 10 pushes backwards
            "speedBase" to { DuelParams(speedBase = -1f) },
            "cooldownPerAgility" to { DuelParams(cooldownPerAgility = 0.2f) }, // AGILITY 10: negative cooldown
            "stunBase" to { DuelParams(stunBase = Float.NaN) },
            "knockStunMps" to { DuelParams(knockStunMps = -0.01f) }, // the stun timer would never run
            "knockStopMps" to { DuelParams(knockStopMps = 0f) },
            "duelSpeedScale" to { DuelParams(duelSpeedScale = 0f) },
            "fromAboveScale" to { DuelParams(fromAboveScale = -1.25f) },
            "dashSpeedMps" to { DuelParams(dashSpeedMps = -0.3f) },
            "knockTauMs" to { DuelParams(knockTauMs = 0L) },
            "subStepMs" to { DuelParams(subStepMs = 16L) },
        )
        for ((name, make) in bad) {
            val e = assertFailsWith<IllegalArgumentException>(name) { make() }
            assertTrue(name in (e.message ?: ""), "the message names $name: ${e.message}")
        }
        // The defaults, the floor set and a size-3 set are all valid.
        DuelParams.forSurface(Surface.FLOOR, 3f)
        DuelParams(knockStunMps = 0f)
    }

    @Test
    fun duelStateRoundTripsThroughJson() {
        val start = DuelState(fighter("a", Vec2.ZERO), fighter("b", Vec2(NEAR, 0f), FighterStats(3, 7, 6, 4)))
        val mid = DuelPhysics.step(start, OPEN, 33L, inputs("a" to Dash(Vec2(1f, 0f)), "b" to DuelInput.Jump))
        val step = DuelStep(
            mid.state,
            listOf(
                DuelEvent.DashStarted("a", 0L), DuelEvent.Hit("a", "b", 0.25f, true, 8L), DuelEvent.Clash("a", "b", 16L),
                DuelEvent.WallSlam("b", "cup-1", 24L), DuelEvent.RingOut("b", 32L),
            ),
        )
        val text = Json.encodeToString(DuelStep.serializer(), step)
        assertEquals(step, Json.decodeFromString(DuelStep.serializer(), text))
        val all: List<DuelInput> = listOf(DuelInput.None, DuelInput.MoveTo(Vec2(0.1f, 0.2f)), Dash(), Dash(Vec2(0f, 1f)), DuelInput.Jump, DuelInput.Special)
        for (i in all) assertEquals(i, Json.decodeFromString(DuelInput.serializer(), Json.encodeToString(DuelInput.serializer(), i)))
        val tuned = table.copy(knockTauMs = 200L, dashSpeedMps = 0.35f)
        assertEquals(tuned, Json.decodeFromString(DuelParams.serializer(), Json.encodeToString(DuelParams.serializer(), tuned)))
        assertTrue("timeMs" in text)
    }
}
